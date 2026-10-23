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
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * The fixed-rate (60 Hz) simulation step: C {@code Handle_keyboard} for every player, then
 * {@code Update_objects} ({@code src/server/update.c}), in C's order. Deterministic: all randomness comes
 * from {@link World#rng}; no clock, no I/O, no allocation per tick.
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
 *   <li>killed ships explode and reset ({@link Ships#kill});</li>
 *   <li>object lifetimes run down, expired objects are deleted ({@link Shots#delete});</li>
 *   <li>score events for changed players/teams; tick and frame clock advance.</li>
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
    public final Crashes crashes;
    public final Mover mover;
    public final Shots shots;
    public final Ships ships;
    public final Collisions collisions;
    public final Cannons cannons;
    public final Keys keys;

    public Simulation(World world) {
        this.world = world;
        this.scoring = new Scoring(world);
        this.debris = new Debris(world);
        this.crashes = new Crashes(world, this);
        this.mover = new Mover(world, this);
        this.shots = new Shots(world, this);
        this.ships = new Ships(world, this);
        this.collisions = new Collisions(world, this);
        this.cannons = new Cannons(world, this);
        this.keys = new Keys(world, this);
    }

    /**
     * Advance one sim tick.
     *
     * @param input control key masks indexed by player slot ({@link Player#slot}); slots not in the game
     *              are ignored
     */
    public void tick(long[] input) {
        World w = world;
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            keys.apply(pl, input[pl.slot]);
        }
        autofire();
        regenerateFuel();
        moveObjects();
        cannons.update();
        for (int k = 0; k < w.numPlayers; k++) {
            ships.update(w.playerAt(k));
        }
        countDownWormholes();
        w.grid.rebuild(w.objects);
        collisions.check();
        for (int k = w.numPlayers - 1; k >= 0; k--) {
            Player pl = w.playerAt(k);
            if ((pl.status & Obj.KILLED) != 0) {
                ships.kill(pl);
            }
        }
        expireObjects();
        emitScores();
        w.tick++;
        w.frames += w.params.dt;
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
     * Fuel stations regenerate (int)(players * 0.06*256) per frame, times dt, up to 500*256 (update.c:530-549).
     * <p>
     * BEHAVIOR: nothing without players.
     */
    void regenerateFuel() {
        World w = world;
        if (w.numPlayers <= 0) {
            return;
        }
        double fuel = (int) (w.numPlayers * STATION_REGENERATION) * w.params.dt;
        for (int i = 0; i < w.fuelLevel.length; i++) {
            w.fuelLevel[i] = Math.min(MAX_STATION_FUEL, w.fuelLevel[i] + fuel);
        }
    }

    /**
     * Object speed and movement (update.c:551-582): {@code vel += (acc + gravity if GRAVITY) * dt}, then move.
     */
    void moveObjects() {
        World w = world;
        Objects o = w.objects;
        double dt = w.params.dt;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if ((o.status[h] & Obj.GRAVITY) != 0) {
                int bx = Math.floorDiv(o.px[h], Const.BLOCK_SZ);
                int by = Math.floorDiv(o.py[h], Const.BLOCK_SZ);
                o.vx[h] += (o.accX[h] + w.gravityX(bx, by)) * dt;
                o.vy[h] += (o.accY[h] + w.gravityY(bx, by)) * dt;
            } else {
                o.vx[h] += o.accX[h] * dt;
                o.vy[h] += o.accY[h] * dt;
            }
            mover.moveObject(h);
        }
    }

    /** Wormhole countdowns run down by dt (update.c:1222-1230). */
    void countDownWormholes() {
        double[] c = world.wormCountdown;
        for (int i = c.length - 1; i >= 0; i--) {
            if (c[i] > 0) {
                c[i] = Math.max(0, c[i] - world.params.dt);
            }
        }
    }

    /** Lifetimes run down by dt; objects at or below zero are deleted (update.c:1310-1315). */
    void expireObjects() {
        Objects o = world.objects;
        double dt = world.params.dt;
        for (int k = o.count() - 1; k >= 0; k--) {
            int h = o.active[k];
            o.life[h] -= dt;
            if (o.life[h] <= 0) {
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

    /** {@code Msg.Score} status bits for a player. */
    static int scoreStatus(Player pl) {
        int s = 0;
        if ((pl.status & Obj.PLAYING) == 0) {
            s |= Msg.Score.STATUS_DEAD;
        }
        if ((pl.status & Obj.PAUSE) != 0) {
            s |= Msg.Score.STATUS_PAUSED;
        }
        if ((pl.status & Obj.GAME_OVER) != 0) {
            s |= Msg.Score.STATUS_OUT_OF_LIVES;
        }
        return s;
    }
}
