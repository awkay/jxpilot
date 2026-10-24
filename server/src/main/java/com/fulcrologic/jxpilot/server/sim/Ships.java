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
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.FuelStation;
import com.fulcrologic.jxpilot.common.map.Wormhole;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Ship rules: the per-player part of C {@code Update_objects} ({@code src/server/update.c}), and from
 * {@code src/server/player.c} {@code Init_player}, {@code Pick_startpos}, {@code Go_home},
 * {@code Player_set_mass}, {@code Kill_player}/{@code Player_death_reset}, {@code Delete_player}, plus
 * {@code Handle_login} (netserver.c) and {@code Pause_player} (event.c).
 * <p>
 * One sim tick is one C frame: every rule is C's per-frame rule, with C's float ({@code DFLOAT}) arithmetic.
 */
public final class Ships {
    /** C {@code ED_SHIELD} after the {@code (long)} cast in update.c: fuel per frame with shields up. */
    static final long ED_SHIELD = (long) (-0.20 * 256);
    /** C {@code REFUEL_RATE} (fixed point). */
    static final int REFUEL_RATE = 5 * 256;
    /** C {@code MAX_PLAYER_FUEL} = TANK_CAP(0) (fixed point). */
    static final double MAX_PLAYER_FUEL = 2600 * 256;
    /** C {@code WORMCOUNT}. */
    static final int WORMCOUNT = 64;
    /** C {@code TURN_FUEL} factor: 0.005 * FUEL_SCALE_FACT. */
    static final double TURN_FUEL = 0.005 * 256;
    /** C {@code DIR_UP}. */
    static final int DIR_UP = Const.RES / 4;
    /** Client default settings (C client default.c), used until the client sends SETTINGS. */
    static final float DEFAULT_POWER = 45.0f;
    static final float DEFAULT_TURN_SPEED = 35.0f;
    static final float DEFAULT_TURN_RESISTANCE = 0.12f;
    static final float DEFAULT_ALT_POWER = 35.0f;
    static final float DEFAULT_ALT_TURN_SPEED = 25.0f;
    static final float DEFAULT_ALT_TURN_RESISTANCE = 0.12f;

    private final World w;
    private final Simulation sim;
    /** Race-mode start order (WorldMap.baseOrder() copies on every call). */
    private final int[] baseOrder;

    /** Told when a human has entered the game (see {@link #enterListener}). */
    @FunctionalInterface
    public interface EnterListener {
        /** {@code pl} is in the game (JOIN, BASE_OWNER and SPAWN emitted); Handle_login's round part follows. */
        void entered(Player pl);
    }

    /**
     * Called in {@link #spawn} for a human right after it entered the game and before Handle_login's round
     * part, where C sets the "Welcome to ..." / "... has entered ..." message (the network layer's text);
     * null for none.
     */
    public EnterListener enterListener;

    public Ships(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        this.baseOrder = w.map.baseOrder();
    }

    // ================================================================ join / leave / home

    /**
     * Reserve a slot and id for a connecting player (the contact part of C's login). The player is not in
     * the game (not drawn, not simulated, owns no base) until {@link #spawn}.
     * <p>
     * BEHAVIOR: returns null when every slot (one per base) is taken, reserved ones included.
     * BEHAVIOR: the name is cut to {@link Const#MAX_NAME_LEN} chars and made Latin-1 (other chars become '?').
     */
    public Player reserve(CharSequence name) {
        return reserve(name, false);
    }

    /**
     * {@link #reserve(CharSequence)} for a human or a robot (C {@code Robot_create} logs a robot in through the
     * same steps).
     * <p>
     * BEHAVIOR: marks the player as a robot iff {@code robot}, so {@link #spawn} sees it.
     */
    public Player reserve(CharSequence name, boolean robot) {
        Player pl = w.addPlayer();
        if (pl == null) {
            return null;
        }
        pl.robot = robot;
        pl.name.setLength(0);
        int n = Math.min(name.length(), Const.MAX_NAME_LEN);
        for (int i = 0; i < n; i++) {
            char c = name.charAt(i);
            pl.name.append(c > 0xFF ? '?' : c);
        }
        return pl;
    }

    /**
     * Put a reserved player into the game (C {@code Handle_login} from {@code Init_player} on, when the
     * client is READY).
     * <p>
     * BEHAVIOR: returns false (nothing changes, the reservation stays) when the player is not reserved, is
     * already in the game, or no team/base can be picked.
     * BEHAVIOR: in team play a request for an invalid, reserved robot (unless the player is a robot: C
     * Robot_create picks its own team), baseless or full team is replaced by C {@code Pick_team}.
     * BEHAVIOR: Init_player runs (start values, the client default power 45, turnspeed 35, turnresistance
     * 0.12 and alternates 35/25/0.12 until {@link #applySettings}); with limitedLives a joiner arriving after
     * a non-team player lost a life waits ('W', GAME_OVER, no lives), judged after the team is chosen.
     * BEHAVIOR: emits JOIN (b = {@code Msg.PlayerJoin.FLAG_ROBOT} for robots, else 0), BASE_OWNER and SCORE,
     * and the ship starts at home.
     * BEHAVIOR: with teamShareScore the first member resets the team score; the share is redistributed to the
     * members already in the game (as C, the joiner itself keeps 0 until the next team score change).
     * BEHAVIOR: for a human, the {@link #enterListener} is told first; then resetOnHuman ends the round for the
     * first N humans; a join during the round delay, or the first player, (re)starts the round delay with a
     * message (C Handle_login). A robot's round
     * part is C Robot_create's, which the robot manager runs after the robot's ENTER talk
     * ({@code Rounds.robotEntered}).
     */
    public boolean spawn(Player pl, int team) {
        if (!pl.inUse || pl.spawned) {
            return false;
        }
        Params p = w.params;
        int t = Const.TEAM_NOT_SET;
        if (p.teamPlay) {
            t = chooseTeam(team, pl);
            if (t == Const.TEAM_NOT_SET) {
                return false;
            }
        }
        initPlayer(pl);
        pl.team = t;
        if (!pickStartPos(pl)) {
            return false;
        }
        tooLate(pl);
        goHome(pl);
        if (pl.team != Const.TEAM_NOT_SET) {
            w.teamMembers[pl.team]++;
            if (p.teamShareScore) {
                if (w.teamMembers[pl.team] == 1) {
                    w.teamScore[pl.team] = 0;
                }
                sim.scoring.teamScore(pl.team, 0);
            }
        }
        w.enter(pl);
        pl.prevKeys = 0;
        pl.keysCleared = true;
        w.events.add(Events.JOIN, pl.id, pl.robot ? Msg.PlayerJoin.FLAG_ROBOT : 0, 0, 0f);
        w.events.add(Events.BASE_OWNER, pl.homeBase, pl.id, 0, 0f);
        w.events.add(Events.GAME_EVENT, Msg.GameEvent.SPAWN, pl.id, pl.homeBase, 0f);
        pl.scoreDirty = true;
        if (!pl.robot) {
            if (enterListener != null) {
                enterListener.entered(pl);
            }
            sim.rounds.playerEntered(pl);
        }
        return true;
    }

    /**
     * Reserve and spawn in one step (tests, robots, the headless runner).
     * <p>
     * BEHAVIOR: returns null (and frees the slot) when either step fails.
     */
    public Player join(CharSequence name, int team) {
        return join(name, team, false);
    }

    /**
     * {@link #join(CharSequence, int)} for a human or a robot: {@link #reserve(CharSequence, boolean)} then
     * {@link #spawn} (the robot manager logs robots in this way).
     * <p>
     * BEHAVIOR: returns null (and frees the slot) when either step fails.
     */
    public Player join(CharSequence name, int team, boolean robot) {
        Player pl = reserve(name, robot);
        if (pl == null) {
            return null;
        }
        if (!spawn(pl, team)) {
            w.removePlayer(pl);
            return null;
        }
        return pl;
    }

    /**
     * Remove a player (C {@code Delete_player}).
     * <p>
     * BEHAVIOR: a reserved player that never spawned just frees its slot (no events).
     * BEHAVIOR: the player's debris and sparks lose their owner and keep flying; its other objects lose their
     * owner and, without keepShots, die (life 0) and cannon shots lose their mass so they do not explode;
     * without keepShots EVERY cannon shot in the world dies the same way (a C quirk, kept).
     * BEHAVIOR: team membership drops; with teamShareScore the leaver's score is taken back from the team
     * (C TEAM_SCORE(team, -score)) and the rest is shared among the remaining members.
     * BEHAVIOR: shove records naming the player are cleared; the home base is released
     * (BASE_OWNER(base, NO_ID)) and LEAVE is emitted.
     */
    public void leave(Player pl) {
        if (!pl.inUse) {
            return;
        }
        if (!pl.spawned) {
            w.removePlayer(pl);
            return;
        }
        int id = pl.id;
        Objects o = w.objects;
        boolean keep = w.params.keepShots;
        for (int k = o.count() - 1; k >= 0; k--) {
            int h = o.active[k];
            int type = o.type[h];
            if (o.owner[h] == id) {
                if ((type & (Obj.DEBRIS | Obj.SPARK)) == 0 && !keep) {
                    o.life[h] = 0;
                    if ((type & (Obj.CANNON_SHOT | Obj.MINE | Obj.SMART_SHOT | Obj.HEAT_SHOT | Obj.TORPEDO)) != 0) {
                        o.mass[h] = 0;
                    }
                }
                o.owner[h] = Obj.NO_ID;
                if ((type & Obj.MINE) != 0) {
                    o.mineOwner[h] = Obj.NO_ID;
                }
            } else if ((type & Obj.MINE) != 0) {
                if (o.mineOwner[h] == id) {
                    o.mineOwner[h] = Obj.NO_ID;
                    if (!keep) {
                        o.life[h] = 0;
                        o.mass[h] = 0;
                    }
                }
            } else if (type == Obj.CANNON_SHOT && !keep) {
                o.life[h] = 0;
                o.mass[h] = 0;
            }
        }
        if (pl.numPulses != 0) {
            sim.lasers.removeOwnedBy(pl);
        }
        int team = pl.team;
        w.removePlayer(pl);
        if (team != Const.TEAM_NOT_SET) {
            w.teamMembers[team]--;
            if (w.params.teamShareScore) {
                sim.scoring.teamScore(team, -pl.score);
            }
        }
        for (int k = 0; k < w.numPlayers; k++) {
            Player other = w.playerAt(k);
            for (int i = 0; i < Player.MAX_SHOVES; i++) {
                if (other.shovePusher[i] == id) {
                    other.shovePusher[i] = Obj.NO_ID;
                }
            }
            if ((other.lockTagged & (Player.LOCK_PLAYER | Player.LOCK_VISIBLE)) != 0
                && (other.lockPlId == id || w.numPlayers <= 1)) {
                other.lockTagged &= ~(Player.LOCK_PLAYER | Player.LOCK_VISIBLE);
                other.used &= ~Has.TRACTOR_BEAM;
            }
            for (int i = 0; i < Player.BANKS; i++) {
                if (other.lockBank[i] == id) {
                    other.lockBank[i] = -1;
                }
            }
        }
        if (pl.homeBase >= 0) {
            w.events.add(Events.BASE_OWNER, pl.homeBase, Obj.NO_ID, 0, 0f);
        }
        w.events.add(Events.LEAVE, id, 0, 0, 0f);
    }

    /**
     * Engine settings from the client (C {@code Receive_power}: assigned directly; the per-tick limits apply
     * in {@link #update}).
     * <p>
     * BEHAVIOR: sets power, turnspeed, turnresistance and their alternates; emits THRUST when power changed.
     */
    public void applySettings(Player pl, float power, float turnSpeed, float turnResistance, float altPower,
                              float altTurnSpeed, float altTurnResistance) {
        float old = pl.power;
        pl.power = power;
        pl.turnSpeed = turnSpeed;
        pl.turnResistance = turnResistance;
        pl.powerS = altPower;
        pl.turnSpeedS = altTurnSpeed;
        pl.turnResistanceS = altTurnResistance;
        if (pl.power != old) {
            w.events.add(Events.THRUST, pl.id, pl.afterburners(), 0, pl.power);
        }
    }

    /** Scratch for {@link #pickTeam}. */
    private final int[] teamFree = new int[Const.MAX_TEAMS];
    private final int[] teamPlaying = new int[Const.MAX_TEAMS];
    private final long[] teamScoreSum = new long[Const.MAX_TEAMS];
    private final int[] teamAvailable = new int[Const.MAX_TEAMS];

    /**
     * The team check of C {@code Handle_login} (netserver.c): a requested team is kept when it is valid, not the
     * reserved robot team, has bases and has fewer members than bases; otherwise {@link #pickTeam}.
     */
    private int chooseTeam(int team, Player joining) {
        Params p = w.params;
        if (team >= 0 && team < Const.MAX_TEAMS && (joining.robot || !(p.reserveRobotTeam && team == p.robotTeam))
            && w.map.teamBaseCount(team) > 0 && w.teamMembers[team] < w.map.teamBaseCount(team)) {
            return team;
        }
        int t = pickTeam(joining);
        return t == p.robotTeam && p.reserveRobotTeam ? Const.TEAM_NOT_SET : t;
    }

    /**
     * C {@code Pick_team(PickForHuman)} (server.c).
     * <p>
     * BEHAVIOR: free bases per team = bases - members (0 for the reserved robot team).
     * BEHAVIOR: with at most one team playing (unpaused members), the candidates are the teams without players
     * that have free bases; otherwise the teams with free bases and the fewest players among playing teams
     * (any team with free bases when no playing team has one); without candidates, any team with free bases.
     * BEHAVIOR: one candidate is chosen; among several the one with the lowest summed (long) score that is not
     * the robot team; none gives TEAM_NOT_SET.
     */
    public int pickTeam(Player joining) {
        Params p = w.params;
        for (int i = 0; i < Const.MAX_TEAMS; i++) {
            teamFree[i] = w.map.teamBaseCount(i) - w.teamMembers[i];
            teamPlaying[i] = 0;
            teamScoreSum[i] = 0;
        }
        if (p.reserveRobotTeam && p.robotTeam >= 0 && p.robotTeam < Const.MAX_TEAMS) {
            teamFree[p.robotTeam] = 0;
        }
        int players = 0;
        int playingTeams = 0;
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (o == joining || o.team == Const.TEAM_NOT_SET) {
                continue;
            }
            players++;
            if ((o.status & Obj.PAUSE) != 0) {
                continue;
            }
            if (teamPlaying[o.team]++ == 0) {
                playingTeams++;
            }
            teamScoreSum[o.team] = (long) (teamScoreSum[o.team] + o.score);
        }
        int n = 0;
        if (playingTeams <= 1) {
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (teamPlaying[i] == 0 && teamFree[i] > 0) {
                    teamAvailable[n++] = i;
                }
            }
        } else {
            int least = players;
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (teamPlaying[i] > 0 && teamFree[i] > 0 && teamPlaying[i] < least) {
                    least = teamPlaying[i];
                }
            }
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (teamFree[i] > 0 && (least == players || teamPlaying[i] == least)) {
                    teamAvailable[n++] = i;
                }
            }
        }
        if (n == 0) {
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (teamFree[i] > 0) {
                    teamAvailable[n++] = i;
                }
            }
        }
        if (n == 1) {
            return teamAvailable[0];
        }
        int losing = Const.TEAM_NOT_SET;
        long losingScore = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int t = teamAvailable[i];
            if (teamScoreSum[t] < losingScore && t != p.robotTeam) {
                losing = t;
                losingScore = teamScoreSum[t];
            }
        }
        return losing;
    }

    private boolean baseTaken(int base, Player except) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (o != except && o.homeBase == base) {
                return true;
            }
        }
        return false;
    }

    /** C {@code Init_player} (the parts that exist in S1), before the team is set. */
    void initPlayer(Player pl) {
        Params p = w.params;
        pl.team = Const.TEAM_NOT_SET;
        pl.homeBase = -1;
        pl.ship = com.fulcrologic.jxpilot.server.world.ShipGeom.defaultShip();
        pl.vx = pl.vy = 0;
        pl.accX = pl.accY = 0;
        pl.dir = DIR_UP;
        pl.floatDir = pl.dir;
        pl.turnVel = pl.oldTurnVel = pl.turnAcc = 0;
        pl.mass = pl.emptyMass = p.shipMass;
        pl.velocity = 0;
        for (int i = 0; i < Item.COUNT; i++) {
            pl.items[i] = (i == Item.FUEL.ordinal() || i == Item.TANK.ordinal()) ? 0 : p.initialItems[i];
        }
        initFuel(pl, p.initialFuel * 256);
        pl.power = DEFAULT_POWER;
        pl.turnSpeed = DEFAULT_TURN_SPEED;
        pl.turnResistance = DEFAULT_TURN_RESISTANCE;
        pl.powerS = DEFAULT_ALT_POWER;
        pl.turnSpeedS = DEFAULT_ALT_TURN_SPEED;
        pl.turnResistanceS = DEFAULT_ALT_TURN_RESISTANCE;
        pl.count = -1;
        pl.shieldTime = 0;
        pl.lastWallTouch = 0;
        pl.shots = 0;
        pl.shotMax = p.maxPlayerShots;
        pl.shotTime = 0;
        pl.score = 0;
        pl.fs = 0;
        pl.damaged = 0;
        pl.stunned = 0;
        pl.status = Obj.PLAYING | Obj.GRAVITY;
        pl.have = p.defHave;
        pl.used = p.defUsed;
        pl.mychar = ' ';
        pl.life = p.worldLives;
        pl.kills = 0;
        pl.deaths = 0;
        pl.shoveNext = 0;
        for (int i = 0; i < Player.MAX_SHOVES; i++) {
            pl.shovePusher[i] = Obj.NO_ID;
            pl.shoveTime[i] = 0;
        }
        pl.wormHoleHit = -1;
        pl.wormHoleDest = -1;
        pl.prevKeys = 0;
        pl.lastReceivedKeys = 0;
        pl.keysCleared = false;
        pl.frameLastBusy = w.frames;
        pl.scoreDirty = false;
        initItemState(pl);
        setMass(pl);
    }

    /** The item parts of C {@code Init_player} (server-items): locks, modifiers, banks, timers, sensors. */
    void initItemState(Player pl) {
        pl.tank = false;
        pl.lockTagged = 0;
        pl.lockPlId = 0;
        pl.lockDistance = 0;
        pl.mods = 0;
        for (int i = 0; i < Player.BANKS; i++) {
            pl.modBank[i] = 0;
            pl.lockBank[i] = -1;
        }
        pl.missileRack = 0;
        pl.forceVisible = 0;
        pl.updateVisibility = false;
        pl.numPulses = 0;
        pl.ecmCount = 0;
        pl.emergencyThrustLeft = pl.emergencyThrustMax = 0;
        pl.emergencyShieldLeft = pl.emergencyShieldMax = 0;
        pl.phasingLeft = pl.phasingMax = 0;
        pl.loseItem = 0;
        pl.loseItemState = 0;
        pl.tractorIsPressor = false;
        for (int i = 0; i < w.players.length; i++) {
            pl.canSee[i] = true;
            w.players[i].canSee[pl.slot] = true;
        }
        sim.locks.sensorRange(pl);
    }

    /**
     * Init_player's limited-lives check, run once the team is known: if a non-team player has already lost a
     * life it is too late to join this round.
     * <p>
     * BEHAVIOR: such a joiner waits: mychar 'W', life 0, GAME_OVER.
     */
    void tooLate(Player pl) {
        if (!w.params.limitedLives) {
            return;
        }
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (o != pl && o.life < w.params.worldLives && !w.sameTeam(pl, o)) {
                pl.mychar = 'W';
                pl.life = 0;
                pl.status |= Obj.GAME_OVER;
                return;
            }
        }
    }

    /**
     * C {@code Player_init_fuel} ({@link Tanks#initFuel}): the main tank first, then initialTanks extra tanks.
     */
    void initFuel(Player pl, double fuel) {
        sim.tanks.initFuel(pl, fuel);
    }

    /**
     * C {@code Pick_startpos}.
     * <p>
     * BEHAVIOR: candidate bases are those of the player's team (all bases outside team play) not used as home
     * by another player; in race mode the first free base in {@code baseOrder}, else a random free base.
     * BEHAVIOR: returns false when no base is free.
     */
    public boolean pickStartPos(Player pl) {
        int nb = w.map.bases().size();
        int numFree = 0;
        for (int i = 0; i < nb; i++) {
            if (isFree(i, pl)) {
                numFree++;
            }
        }
        if (numFree == 0) {
            return false;
        }
        if (w.map.timing()) {
            for (int b : baseOrder) {
                if (isFree(b, pl)) {
                    pl.homeBase = b;
                    return true;
                }
            }
            return false;
        }
        int pick = (int) (w.rfrac() * numFree);
        int seen = 0;
        for (int i = 0; i < nb; i++) {
            if (isFree(i, pl)) {
                if (seen == pick) {
                    pl.homeBase = i;
                    return true;
                }
                seen++;
            }
        }
        return false;
    }

    private boolean isFree(int base, Player pl) {
        return w.map.bases().get(base).team() == pl.team && !baseTaken(base, pl);
    }

    /**
     * C {@code Go_home}: put the ship on its base, at rest, facing the base direction.
     * <p>
     * BEHAVIOR: clears velocity, acceleration, turning and the key state: keys still held are ignored until the
     * client's key state changes (C clears {@code last_keyv}/{@code prev_keyv}).
     * BEHAVIOR: C {@code Player_used_kill}: USED_KILL abilities off, and without default shields HAS_SHIELD is
     * lost; with playerStartsShielded shields go up (for 2*FPS frames when shields are not otherwise allowed).
     * BEHAVIOR: thrust stops; a ship already in the game emits a SPAWN game event (a joining ship's SPAWN
     * follows its JOIN).
     */
    public void goHome(Player pl) {
        Base b = w.map.bases().get(pl.homeBase);
        pl.dir = b.dir();
        pl.floatDir = b.dir();
        pl.initPixels((b.bx() + 0.5) * Const.BLOCK_SZ, (b.by() + 0.5) * Const.BLOCK_SZ);
        pl.vx = pl.vy = 0;
        pl.velocity = 0;
        pl.accX = pl.accY = 0;
        pl.turnAcc = pl.turnVel = 0;
        pl.prevKeys = 0;
        pl.keysCleared = true;
        pl.used &= ~Has.USED_KILL;
        if ((w.params.defHave & Has.SHIELD) == 0) {
            pl.have &= ~Has.SHIELD;
        }
        if (w.params.playerStartsShielded) {
            pl.used |= Has.SHIELD;
            if (!w.params.playerShielding) {
                pl.shieldTime = 2 * w.params.fps;
                pl.have |= Has.SHIELD;
            }
            if ((pl.have & Has.DEFLECTOR) != 0) {
                sim.devices.deflector(pl, true);
            }
        }
        pl.status &= ~Obj.THRUSTING;
        sim.locks.resetVisibility(pl);
        if (pl.spawned) {
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.SPAWN, pl.id, pl.homeBase, 0f);
        }
    }

    /**
     * C {@code Pause_player}.
     * <p>
     * BEHAVIOR: on (when not paused): count = 10*FPS, SELF_DESTRUCT and PLAYING off, PAUSE on, mychar 'P'.
     * BEHAVIOR: off (when paused and the count has run out): PAUSE off; with limitedLives, if a non-team player
     * has lost a life it is too late: life 0, 'W', GAME_OVER; otherwise ' ', Go_home, PLAYING, and full lives
     * with limitedLives. Otherwise nothing happens.
     */
    public void pause(Player pl, boolean on) {
        Params p = w.params;
        if (on && (pl.status & Obj.PAUSE) == 0) {
            pl.count = 10 * p.fps;
            pl.updateVisibility = true;
            pl.status &= ~(Obj.SELF_DESTRUCT | Obj.PLAYING);
            pl.status |= Obj.PAUSE;
            pl.mychar = 'P';
            pl.scoreDirty = true;
        } else if (!on && (pl.status & Obj.PAUSE) != 0 && pl.count <= 0) {
            pl.status &= ~Obj.PAUSE;
            pl.scoreDirty = true;
            boolean late = false;
            if (p.limitedLives) {
                for (int k = 0; k < w.numPlayers; k++) {
                    Player o = w.playerAt(k);
                    if (o != pl && o.life < p.worldLives && !w.sameTeam(pl, o)) {
                        late = true;
                        break;
                    }
                }
            }
            if (late) {
                pl.life = 0;
                pl.mychar = 'W';
                pl.status |= Obj.GAME_OVER;
            } else {
                pl.mychar = ' ';
                goHome(pl);
                pl.status |= Obj.PLAYING;
                if (p.limitedLives) {
                    pl.life = p.worldLives;
                }
            }
        }
    }

    // ================================================================ per tick

    /**
     * The per-player body of the C player loop for one frame.
     * <p>
     * BEHAVIOR: power/turnspeed/turnresistance are limited to their C ranges.
     * BEHAVIOR: a counting-down ship that is not playing (dead, or paused) is carried toward home
     * (Transport_to_home) and moved (paused ships do not move), nothing else.
     * BEHAVIOR: when the count reaches 0 a not-playing ship gets PLAYING and goes home (a paused one stays
     * paused) and its SCORE status is resent; a self-destructing ship dies (score -Rate(0, score) *
     * selfDestructScoreMult).
     * BEHAVIOR: paused ships lose 0.01 points per frame (scored every 64th frame); paused, game-over or dead
     * ships stop here; during the round delay every ship stops here.
     * BEHAVIOR: then stuns and shields time out, the ship turns, shields drain fuel, refuelling transfers fuel,
     * fuel exhaustion drops shields and thrust, thrust sets the acceleration and uses fuel, mass is updated,
     * a warping ship goes through its wormhole, gravity and acceleration change the velocity, the ship
     * moves and thrust/turn sparks are made.
     */
    public void update(Player pl) {
        Params p = w.params;
        pl.power = clamp(pl.power, Const.MIN_PLAYER_POWER, Const.MAX_PLAYER_POWER);
        pl.turnSpeed = clamp(pl.turnSpeed, Const.MIN_PLAYER_TURNSPEED, Const.MAX_PLAYER_TURNSPEED);
        pl.turnResistance = clamp(pl.turnResistance, Const.MIN_PLAYER_TURNRESISTANCE,
            Const.MAX_PLAYER_TURNRESISTANCE);
        if (pl.damaged > 0) {
            pl.damaged--;
        }
        if (pl.count > 0) {
            pl.count--;
            if ((pl.status & Obj.PLAYING) == 0) {
                transportToHome(pl);
                sim.mover.movePlayer(pl);
                return;
            }
        }
        if (pl.count == 0) {
            pl.count = -1;
            if ((pl.status & Obj.PLAYING) == 0) {
                pl.status |= Obj.PLAYING;
                pl.scoreDirty = true;
                goHome(pl);
            }
            if ((pl.status & Obj.SELF_DESTRUCT) != 0) {
                selfDestruct(pl);
            }
        }
        if ((pl.status & (Obj.PLAYING | Obj.GAME_OVER | Obj.PAUSE)) != Obj.PLAYING) {
            if ((pl.status & Obj.PAUSE) != 0) {
                pl.score -= 0.01;
                if ((w.frames & 63) == 0) {
                    pl.scoreDirty = true;
                }
            }
            return;
        }
        if (w.roundDelay > 0) {
            return;
        }
        if (pl.stunned > 0) {
            pl.stunned--;
            pl.used &= ~(Has.SHIELD | Has.LASER | Has.SHOT);
            pl.status &= ~Obj.THRUSTING;
        }
        updateShieldTime(pl);
        sim.devices.timers(pl);
        sim.devices.use(pl);
        long tf = turn(pl, p);
        sim.mover.turnPlayer(pl);

        if ((pl.used & Has.SHIELD) != 0) {
            pl.addFuel(ED_SHIELD);
        }
        sim.devices.drain(pl);
        sim.locks.updateVisibility(pl);
        refuel(pl);
        if (pl.fuel <= 0) {
            pl.used &= ~(Has.SHIELD | Has.CLOAKING_DEVICE | Has.DEFLECTOR);
            pl.status &= ~Obj.THRUSTING;
        }
        if (pl.fuel > pl.fuelMax - REFUEL_RATE) {
            pl.used &= ~Has.REFUEL;
        }
        thrustAcceleration(pl);
        setMass(pl);
        if ((pl.status & Obj.WARPING) != 0) {
            warp(pl);
        }
        if ((pl.status & Obj.PAUSE) == 0) {
            if ((pl.status & Obj.GRAVITY) != 0) {
                pl.vx += pl.accX + w.gravityX(pl.bx, pl.by);
                pl.vy += pl.accY + w.gravityY(pl.bx, pl.by);
            } else {
                pl.vx += pl.accX;
                pl.vy += pl.accY;
            }
            sim.mover.movePlayer(pl);
        }
        if (((pl.used & Has.CLOAKING_DEVICE) == 0 || p.cloakedExhaust) && (pl.used & Has.PHASING_DEVICE) == 0) {
            if ((pl.status & Obj.THRUSTING) != 0) {
                sim.debris.thrust(pl);
            }
            if (tf != 0 && p.turnThrust) {
                sim.debris.turnThrust(pl, (int) (5 + (tf >> 2)));
            }
        }
        sim.locks.sensorRange(pl);
        pl.used &= pl.have;
    }

    /** C {@code LIMIT} of a float field against double bounds. */
    private static float clamp(float v, double lo, double hi) {
        return v > hi ? (float) hi : (v < lo ? (float) lo : v);
    }

    /** Shield timer (update.c:793-805). */
    private void updateShieldTime(Player pl) {
        if (pl.shieldTime > 0) {
            if (--pl.shieldTime == 0 && (pl.used & Has.EMERGENCY_SHIELD) == 0) {
                pl.used &= ~Has.SHIELD;
            }
            if ((pl.used & Has.SHIELD) == 0) {
                if ((pl.used & Has.EMERGENCY_SHIELD) == 0) {
                    pl.have &= ~Has.SHIELD;
                }
                pl.shieldTime = 0;
            }
        }
    }

    /**
     * Turning (update.c:860-905), in float like C.
     * <p>
     * BEHAVIOR: turnVel += turnAcc, then *= turnResistance unless it is 0 (pointer control).
     * BEHAVIOR: with turnThrust, turning costs C's double truncation (long)(1.28 * |(long)(oldTurnVel - turnVel)|)
     * fuel (C TURN_FUEL on a long); without enough fuel (fuel &lt;= cost) the turn is cancelled (turnAcc = 0,
     * turnVel restored) and costs nothing. Returns the cost (C {@code tf}, for turn sparks).
     * BEHAVIOR: floatDir += turnVel, wrapped into [0, RES); with resistance 0 turnVel then becomes 0.
     */
    long turn(Player pl, Params p) {
        pl.turnVel += pl.turnAcc;
        if (pl.turnResistance != 0) {
            pl.turnVel *= pl.turnResistance;
        }
        long tf = 0;
        if (p.turnThrust) {
            tf = (long) (pl.oldTurnVel - pl.turnVel);
            tf = (long) (TURN_FUEL * Math.abs(tf));
            if (pl.fuel <= tf) {
                tf = 0;
                pl.turnAcc = 0;
                pl.turnVel = pl.oldTurnVel;
            } else {
                pl.addFuel(-tf);
                pl.oldTurnVel = pl.turnVel;
            }
        }
        pl.floatDir += pl.turnVel;
        while (pl.floatDir < 0) {
            pl.floatDir += Const.RES;
        }
        while (pl.floatDir >= Const.RES) {
            pl.floatDir -= Const.RES;
        }
        if (pl.turnResistance == 0) {
            pl.turnVel = 0;
        }
        return tf;
    }

    /**
     * Refuelling (update.c:950-987) with one tank.
     * <p>
     * BEHAVIOR: stops when more than 90 px from the station, full, the station is gone, phased, or (team play
     * with teamFuel) the station belongs to another team.
     * BEHAVIOR: otherwise moves REFUEL_RATE from the station into each tank in turn (numTanks + 1 times per
     * frame); a station with less gives the rest and refuelling stops. The current tank is kept.
     */
    void refuel(Player pl) {
        if ((pl.used & Has.REFUEL) == 0) {
            return;
        }
        FuelStation f = w.map.fuels().get(pl.fs);
        Params p = w.params;
        if (w.wrapLength(pl.px - (float) f.x(), pl.py - (float) f.y()) > 90.0
            || pl.fuel >= pl.fuelMax
            || w.map.block(f.bx(), f.by()) != BlockType.FUEL
            || (pl.used & Has.PHASING_DEVICE) != 0
            || (p.teamPlay && p.teamFuel && f.team() != pl.team)) {
            pl.used &= ~Has.REFUEL;
            return;
        }
        int i = pl.numTanks;
        int ct = pl.currentTank;
        do {
            if (w.fuelLevel[pl.fs] > REFUEL_RATE) {
                w.fuelLevel[pl.fs] -= REFUEL_RATE;
                pl.addFuel(REFUEL_RATE);
            } else {
                pl.addFuel(w.fuelLevel[pl.fs]);
                w.fuelLevel[pl.fs] = 0;
                pl.used &= ~Has.REFUEL;
                break;
            }
            if (pl.currentTank == pl.numTanks) {
                pl.currentTank = 0;
            } else {
                pl.currentTank += 1;
            }
        } while (i-- != 0);
        pl.currentTank = ct;
    }

    /**
     * C {@code Refuel} (event.c): pick the nearest existing fuel station.
     * <p>
     * BEHAVIOR: without HAS_REFUEL nothing happens; with no station refuelling stays off.
     */
    public void startRefuel(Player pl) {
        if ((pl.have & Has.REFUEL) == 0) {
            return;
        }
        pl.used &= ~Has.REFUEL;
        float best = 1e9f;
        java.util.List<FuelStation> fuels = w.map.fuels();
        for (int i = 0; i < fuels.size(); i++) {
            FuelStation f = fuels.get(i);
            if (w.map.block(f.bx(), f.by()) == BlockType.FUEL) {
                float l = w.wrapLength(pl.px - (float) f.x(), pl.py - (float) f.y());
                if ((pl.used & Has.REFUEL) == 0 || l < best) {
                    pl.used |= Has.REFUEL;
                    pl.fs = f.index();
                    best = l;
                }
            }
        }
    }

    /**
     * Thrust (update.c:1018-1037), C float arithmetic.
     * <p>
     * BEHAVIOR: acc = power * (tcos, tsin)(dir) / mass, with afterburners power *= 1 + n*(3.2/16); fuel use per
     * frame (long)(-f * 256) with f = (float)(power*0.0008), ((f*(16+2n))/16 with afterburners).
     * BEHAVIOR: without thrust the acceleration is zero.
     */
    void thrustAcceleration(Player pl) {
        if ((pl.status & Obj.THRUSTING) == 0) {
            pl.accX = pl.accY = 0;
            return;
        }
        float power = pl.power;
        float f = (float) (pl.power * 0.0008);
        int a = pl.afterburners();
        float inert = pl.mass;
        if (a != 0) {
            power = (float) (power * (1.0 + a * ((4.2 - 1.0) / (15 + 1.0))));
            f = (float) ((f * ((15 + 1) + a * (3 - 1))) / (15 + 1.0));
        }
        pl.accX = power * Trig.tcos(pl.dir) / inert;
        pl.accY = power * Trig.tsin(pl.dir) / inert;
        pl.addFuel((long) (-f * 256));
    }

    /**
     * C {@code Player_set_mass}.
     * <p>
     * BEHAVIOR: mass = (float)(emptyMass + fuel*0.005/256 + sum), sum in float of armor*(shipMass/14) and
     * minItemMass per other item.
     */
    void setMass(Player pl) {
        float items = 0;
        for (int i = 0; i < Item.COUNT; i++) {
            if (i == Item.FUEL.ordinal() || i == Item.TANK.ordinal()) {
                continue;
            }
            if (i == Item.ARMOR.ordinal()) {
                items += pl.items[i] * (w.params.shipMass / 14);
            } else {
                items += pl.items[i] * w.params.minItemMass;
            }
        }
        pl.mass = (float) (pl.emptyMass + pl.fuel * 0.005 / 256 + items);
    }

    /** C {@code Player_hit_armor}: one armor is used up; the last one removes HAS_ARMOR. */
    public void hitArmor(Player pl) {
        if (--pl.items[Item.ARMOR.ordinal()] <= 0) {
            pl.have &= ~Has.ARMOR;
        }
    }

    /**
     * C {@code Transport_to_home}: velocity that carries a dead ship to its base in RECOVERY_DELAY frames,
     * accelerating for the first half and braking for the second (C floats).
     * <p>
     * BEHAVIOR: with t = count + 0.5 and T = 3*FPS: m = 2/t while 2t &lt;= T, else t' = T - t and
     * m = 4t'/(T^2 - 2t'^2); velocity = wrapped distance to the base centre * m.
     */
    void transportToHome(Player pl) {
        Base b = w.map.bases().get(pl.homeBase);
        float bx = (float) ((b.bx() + 0.5) * Const.BLOCK_SZ);
        float by = (float) ((b.by() + 0.5) * Const.BLOCK_SZ);
        float dx = (float) w.wrapDx(bx - pl.px);
        float dy = (float) w.wrapDy(by - pl.py);
        int bigT = 3 * w.params.fps;
        float t = pl.count + 0.5f;
        float m;
        if (2 * t <= bigT) {
            m = 2 / t;
        } else {
            t = bigT - t;
            m = (4 * t) / (bigT * bigT - 2 * t * t);
        }
        pl.vx = dx * m;
        pl.vy = dy * m;
    }

    /** Self-destruct completes (update.c:765-778). */
    private void selfDestruct(Player pl) {
        w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, pl.id, pl.id, 0f);
        if (w.params.selfDestructScoreMult != 0) {
            sim.scoring.score(pl, -Scoring.rate(0, pl.score) * w.params.selfDestructScoreMult, pl.bx, pl.by,
                "Self-Destruct");
        }
        pl.status |= Obj.KILLED;
        w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name).append(" has committed suicide.");
        sim.items.throwItems(pl);
        kill(pl);
    }

    /**
     * Wormhole travel (update.c:1041-1188, hyperjump excluded).
     * <p>
     * BEHAVIOR: a wormhole still counting down sends to its last destination; otherwise 10% of the time a
     * random non-IN other wormhole, else the non-IN wormhole closest to the line of flight ahead (or a random
     * one if none is ahead).
     * BEHAVIOR: the ship appears at the destination's centre, the source remembers the destination for
     * wormTime frames (WORMCOUNT without wormTime), and the ship is WARPED (not WARPING).
     */
    void warp(Player pl) {
        int hit = pl.wormHoleHit;
        int n = w.map.wormholes().size();
        if (hit >= n || hit < -1) {
            pl.status &= ~Obj.WARPING;
            return;
        }
        if (hit == -1) {
            sim.devices.hyperjumpDestination(pl);
            pl.wormHoleDest = -2;
            pl.initPixels(sim.devices.jumpX, sim.devices.jumpY);
            pl.forceVisible += 15;
            pl.status &= ~Obj.WARPING;
            pl.status |= Obj.WARPED;
            return;
        }
        int j;
        if (w.wormCountdown[hit] > 0) {
            j = w.wormLastDest[hit];
        } else if (w.rfrac() < 0.10f) {
            do {
                j = (int) (w.rfrac() * n);
            } while (w.map.wormholes().get(j).type() == Wormhole.Type.IN || hit == j);
        } else {
            j = nearestAhead(pl, hit, n);
        }
        Wormhole dest = w.map.wormholes().get(j);
        pl.wormHoleDest = j;
        pl.initPixels((dest.bx() + 0.5) * Const.BLOCK_SZ, (dest.by() + 0.5) * Const.BLOCK_SZ);
        pl.forceVisible += 15;
        if (j != hit) {
            w.wormLastDest[hit] = j;
            w.wormCountdown[hit] = w.params.wormTime != 0 ? w.params.wormTime : WORMCOUNT;
        }
        pl.status &= ~Obj.WARPING;
        pl.status |= Obj.WARPED;
    }

    /** The RANDOM_REAR_WORM choice of update.c:1065-1110 (note: C wraps the y offset with WRAP_DX). */
    private int nearestAhead(Player pl, int hit, int n) {
        Wormhole from = w.map.wormholes().get(hit);
        int nearestFront = -1;
        int proxFront = 10000000;
        for (int j = 0; j < n; j++) {
            Wormhole h = w.map.wormholes().get(j);
            if (j == hit || h.type() == Wormhole.Type.IN) {
                continue;
            }
            int wx = (int) w.wrapDx((h.bx() - from.bx()) * Const.BLOCK_SZ);
            int wy = (int) w.wrapDx((h.by() - from.by()) * Const.BLOCK_SZ);
            int proximity = Math.abs((int) (pl.vy * wx + pl.vx * wy));
            if (!(pl.vx * wx + pl.vy * wy < 0) && proximity < proxFront) {
                nearestFront = j;
                proxFront = proximity;
            }
        }
        if (nearestFront >= 0) {
            return nearestFront;
        }
        int j;
        do {
            j = (int) (w.rfrac() * n);
        } while (w.map.wormholes().get(j).type() == Wormhole.Type.IN || j == hit);
        return j;
    }

    // ================================================================ death

    /**
     * C {@code Kill_player}: explosion debris, then {@link #deathReset}.
     */
    public void kill(Player pl) {
        sim.debris.explodeShip(pl);
        deathReset(pl);
    }

    /**
     * C {@code Player_death_reset}.
     * <p>
     * BEHAVIOR: velocity and acceleration zeroed, mass reset, KILL_BITS cleared (so not PLAYING), items reset,
     * respawn count = max(3*FPS, count).
     * BEHAVIOR: fuel = max((long)(fuel*0.9), initialFuel*256 + (int)(rfrac*(1+that)*0.2)), capped at the tank.
     * BEHAVIOR: unless paused: deaths++; with limited lives a life is lost and the last one ends the game
     * (GAME_OVER, mychar 'D'), without limited lives life counts up (C).
     * BEHAVIOR: abilities reset to DEF_HAVE / DEF_USED minus USED_KILL.
     */
    public void deathReset(Player pl) {
        Params p = w.params;
        if ((pl.used & Has.AUTOPILOT) != 0 || (pl.status & Obj.HOVERPAUSE) != 0) {
            pl.status &= ~Obj.HOVERPAUSE;
            sim.devices.autopilot(pl, false);
        }
        pl.vx = pl.vy = 0;
        pl.accX = pl.accY = 0;
        pl.emptyMass = pl.mass = p.shipMass;
        pl.status &= ~Obj.KILL_BITS;
        for (int i = 0; i < Item.COUNT; i++) {
            if (i != Item.FUEL.ordinal() && i != Item.TANK.ordinal()) {
                pl.items[i] = p.initialItems[i];
            }
        }
        pl.forceVisible = 0;
        pl.shotMax = p.maxPlayerShots;
        pl.count = Math.max(3 * p.fps, pl.count);
        pl.ecmCount = 0;
        pl.emergencyThrustLeft = pl.emergencyThrustMax = 0;
        pl.emergencyShieldLeft = pl.emergencyShieldMax = 0;
        pl.phasingLeft = pl.phasingMax = 0;
        pl.damaged = 0;
        pl.stunned = 0;
        pl.lockDistance = 0;
        double fuel = (long) (pl.fuel * 0.90);
        double minFuel = p.initialFuel * 256;
        minFuel += (int) (w.rfrac() * (1 + minFuel) * 0.2f);
        initFuel(pl, Math.max(fuel, minFuel));
        if ((pl.status & Obj.PAUSE) == 0) {
            pl.deaths++;
            if (p.limitedLives) {
                pl.life--;
                if (pl.life == -1) {
                    pl.life = 0;
                    pl.status |= Obj.GAME_OVER;
                    pl.mychar = 'D';
                    sim.locks.lockClosest(pl, false);
                }
            } else {
                pl.life++;
            }
        }
        pl.have = p.defHave;
        pl.used |= p.defUsed;
        pl.used &= ~Has.USED_KILL;
        pl.used &= pl.have;
        pl.scoreDirty = true;
    }

    /** C {@code Record_shove}: remember who pushed this ship and when (ring of MAX_SHOVES). */
    public void recordShove(Player pl, Player pusher) {
        recordShove(pl, pusher, w.frames);
    }

    /** C {@code Record_shove} with an explicit time (ECM blinding and blinding lasers count until it). */
    public void recordShove(Player pl, Player pusher, long time) {
        pl.shovePusher[pl.shoveNext] = pusher.id;
        pl.shoveTime[pl.shoveNext] = time;
        if (++pl.shoveNext == Player.MAX_SHOVES) {
            pl.shoveNext = 0;
        }
    }
}
