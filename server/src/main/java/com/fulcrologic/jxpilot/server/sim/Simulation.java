/*
 * JXPilot, a Java reimplementation of XPilot, a multiplayer gravity war game.
 * Copyright (C) 2026 Tony Kay and the JXPilot contributors.
 *
 * Derived from XPilot 4.5.5, Copyright (C) 1991-2002 by Bjørn Stabell,
 * Ken Ronny Schouten, Bert Gijsbers and Dick Balaska. JXPilot is an
 * independent derivative port: the original XPilot authors were not involved
 * in it and do not endorse it. Ported to Java and modified starting in 2026;
 * see the git history for the changes.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <https://www.gnu.org/licenses/>.
 */
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.robot.RobotManager;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * One simulation tick = one C frame ({@code Main_loop}: {@code Handle_keyboard} for every player, then
 * {@code Update_objects} in {@code src/server/update.c}), in C's order, at the map's framesPerSecond (SPEC
 * "Time model"). Deterministic: all randomness comes from {@link World#rng}; no clock, no I/O, no allocation
 * per tick.
 * <p>
 * Tick phases:
 * <ol>
 *   <li>input edges ({@link Keys});</li>
 *   <li>autofire;</li>
 *   <li>fuel stations regenerate;</li>
 *   <li>objects: gravity and acceleration, then move ({@link Mover#moveObject});</li>
 *   <li>cannons ({@link Cannons});</li>
 *   <li>players ({@link Ships#update}: turn, fuel, thrust, warp, move, sparks);</li>
 *   <li>wormhole countdowns;</li>
 *   <li>collisions ({@link Collisions}) over the rebuilt {@link com.fulcrologic.jxpilot.server.world.Grid};</li>
 *   <li>killed ships explode and reset ({@link Ships#kill}), idle pause, pause-time kick;</li>
 *   <li>object lifetimes run down, expired objects are deleted ({@link Shots#delete});</li>
 *   <li>game status: rounds end, round delay ({@link Rounds});</li>
 *   <li>score events for changed players/teams; the frame clock advances.</li>
 * </ol>
 */
public final class Simulation {
    /** C {@code STATION_REGENERATION} (fixed point per frame per player). */
    static final double STATION_REGENERATION = 0.06 * 256;
    /** C {@code MAX_STATION_FUEL} (fixed point). */
    static final double MAX_STATION_FUEL = Const.MAX_STATION_FUEL * 256;

    public final World world;
    public final Scoring scoring;
    public final Debris debris;
    /** C {@code Make_wreckage} (ship.c). */
    public final Wreckage wreckage;
    public final Crashes crashes;
    public final Mover mover;
    public final Shots shots;
    public final Ships ships;
    public final Collisions collisions;
    public final Cannons cannons;
    public final Keys keys;
    public final Rounds rounds;
    /** Robot players (C {@code Robot_update}); off until {@link RobotManager#setEnabled enabled}. */
    public final RobotManager robots;

    public Simulation(World world) {
        this.world = world;
        this.scoring = new Scoring(world);
        this.debris = new Debris(world);
        this.wreckage = new Wreckage(world);
        this.crashes = new Crashes(world, this);
        this.mover = new Mover(world, this);
        this.shots = new Shots(world, this);
        this.ships = new Ships(world, this);
        this.collisions = new Collisions(world, this);
        this.cannons = new Cannons(world, this);
        this.keys = new Keys(world, this);
        this.rounds = new Rounds(world, this);
        com.fulcrologic.jxpilot.server.snapshot.SnapshotBuilder.initRecordClasses();
        Sounds.initClasses();
        this.robots = new RobotManager(world, this);
    }

    /**
     * Run the next tick ({@code world.tick + 1}).
     *
     * @param input control key masks indexed by player slot ({@link Player#slot}); slots not in the game
     *              are ignored
     */
    public void tick(long[] input) {
        tick(world.tick + 1, input);
    }

    /**
     * Run tick number {@code t}: {@link World#tick} is {@code t} during and after the tick, so the network
     * layer can number ticks itself (bursts are tagged and thrust sparks seeded with it, and a snapshot
     * built after this call shows tick {@code t}).
     * <p>
     * BEHAVIOR: sets the tick number, runs the phases in C order and advances {@link World#frames} by one.
     */
    public void tick(int t, long[] input) {
        World w = world;
        w.tick = t;
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            keys.apply(pl, input[pl.slot]);
        }
        robots.update();
        autofire();
        regenerateFuel();
        moveObjects();
        cannons.update();
        for (int k = 0; k < w.numPlayers; k++) {
            ships.update(w.playerAt(k));
        }
        countDownWormholes();
        w.grid.rebuild(w.objects);
        if (w.roundDelay == 0) {
            collisions.check();
        }
        killPlayers();
        expireObjects();
        if (w.gameDuration >= 0 || w.params.maxRoundTime > 0) {
            rounds.computeGameStatus();
        }
        emitScores();
        w.frames++;
    }

    /** Autorepeat fire for players holding fire (update.c:507-514). */
    void autofire() {
        if (world.params.fireRepeatRate <= 0) {
            return;
        }
        for (int k = 0; k < world.numPlayers; k++) {
            Player pl = world.playerAt(k);
            if ((pl.used & Has.SHOT) != 0) {
                shots.fireNormalShots(pl);
            }
        }
    }

    /**
     * Fuel stations regenerate (int)(players * 0.06*256) per frame up to 500*256 (update.c:530-549).
     * <p>
     * BEHAVIOR: nothing without players.
     */
    void regenerateFuel() {
        World w = world;
        if (w.numPlayers <= 0) {
            return;
        }
        int fuel = (int) (w.numPlayers * STATION_REGENERATION);
        for (int i = 0; i < w.fuelLevel.length; i++) {
            w.fuelLevel[i] = Math.min(MAX_STATION_FUEL, w.fuelLevel[i] + fuel);
        }
    }

    /**
     * Object speed and movement (update.c:551-582, C {@code update_object_speed} in float):
     * wreckage turns first ({@link Wreckage#rotate}), then {@code vel += acc + gravity} (gravity only with
     * GRAVITY), then move.
     */
    void moveObjects() {
        World w = world;
        Objects o = w.objects;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if (o.type[h] == Obj.WRECKAGE) {
                wreckage.rotate(h);
            }
            if ((o.status[h] & Obj.GRAVITY) != 0) {
                int bx = o.px[h] / Const.BLOCK_SZ;
                int by = o.py[h] / Const.BLOCK_SZ;
                o.vx[h] += o.accX[h] + w.gravityX(bx, by);
                o.vy[h] += o.accY[h] + w.gravityY(bx, by);
            } else {
                o.vx[h] += o.accX[h];
                o.vy[h] += o.accY[h];
            }
            mover.moveObject(h);
        }
    }

    /** Wormhole countdowns run down by one frame (update.c:1222-1230). */
    void countDownWormholes() {
        int[] c = world.wormCountdown;
        for (int i = c.length - 1; i >= 0; i--) {
            if (c[i] > 0) {
                c[i]--;
            }
        }
    }

    /**
     * The kill loop of update.c:1287-1316, last player first.
     * <p>
     * BEHAVIOR: a KILLED player explodes and resets ({@link Ships#kill}); a human who has been idle for more
     * than 60*FPS frames is then paused when another human is in the game.
     * BEHAVIOR: a human paused for longer than maxPauseTime (if &gt; 0) is kicked with a message
     * ({@link Ships#leave}: the network layer sees the LEAVE event and closes the connection).
     */
    void killPlayers() {
        World w = world;
        for (int k = w.numPlayers - 1; k >= 0; k--) {
            Player pl = w.playerAt(k);
            if ((pl.status & Obj.KILLED) != 0) {
                ships.kill(pl);
                if (!pl.inUse) {
                    continue; // a robot out of lives left the game (player.c:1951)
                }
                if (!pl.robot && w.frames - pl.frameLastBusy > 60L * w.params.fps && humans() > 1) {
                    ships.pause(pl, true);
                }
            }
            if (w.params.maxPauseTime > 0 && !pl.robot && (pl.status & Obj.PAUSE) != 0
                && w.frames - pl.frameLastBusy > w.params.maxPauseTime) {
                w.events.message(Msg.Text.KIND_SERVER, Obj.NO_ID).append(pl.name)
                    .append(" was auto-kicked for pausing too long [*Server notice*]");
                ships.leave(pl);
            }
        }
    }

    /** Human players in the game (C {@code NumPlayers - NumRobots - NumPseudoPlayers}). */
    int humans() {
        int n = 0;
        for (int k = 0; k < world.numPlayers; k++) {
            if (!world.playerAt(k).robot) {
                n++;
            }
        }
        return n;
    }

    /** Lifetimes run down by one frame; objects at or below zero are deleted, last first (update.c:1310-1315). */
    void expireObjects() {
        Objects o = world.objects;
        for (int k = o.count() - 1; k >= 0; k--) {
            int h = o.active[k];
            if (--o.life[h] <= 0) {
                shots.delete(h);
            }
        }
    }

    /**
     * Emit SCORE events for players whose score/lives/status changed and TEAM_SCORE for changed teams.
     * <p>
     * BEHAVIOR: each dirty player/team gives exactly one event and is cleaned.
     */
    void emitScores() {
        World w = world;
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if (pl.scoreDirty) {
                pl.scoreDirty = false;
                w.events.add(Events.SCORE, pl.id, w.params.limitedLives ? pl.life : -1, scoreStatus(pl),
                    (float) pl.score);
            }
        }
        for (int t = 0; t < Const.MAX_TEAMS; t++) {
            if (w.teamScoreDirty[t]) {
                w.teamScoreDirty[t] = false;
                w.events.add(Events.TEAM_SCORE, t, 0, 0, (float) w.teamScore[t]);
            }
        }
    }

    /**
     * {@code Msg.Score} status bits for a player (the network layer sends them in SCORE messages).
     * <p>
     * BEHAVIOR: DEAD when not playing, PAUSED when paused, WAITING for C's 'W' (a late joiner waiting for
     * the next round), OUT_OF_LIVES for GAME_OVER otherwise.
     */
    public static int scoreStatus(Player pl) {
        int s = 0;
        if ((pl.status & Obj.PLAYING) == 0) {
            s |= Msg.Score.STATUS_DEAD;
        }
        if ((pl.status & Obj.PAUSE) != 0) {
            s |= Msg.Score.STATUS_PAUSED;
        }
        if ((pl.status & Obj.GAME_OVER) != 0) {
            s |= pl.mychar == 'W' ? Msg.Score.STATUS_WAITING : Msg.Score.STATUS_OUT_OF_LIVES;
        }
        return s;
    }
}
