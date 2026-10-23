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
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Ship rules: the per-player part of C {@code Update_objects} ({@code src/server/update.c}), and from
 * {@code src/server/player.c} {@code Init_player}, {@code Pick_startpos}, {@code Go_home},
 * {@code Player_set_mass}, {@code Kill_player}/{@code Player_death_reset}.
 * <p>
 * Time scaling (SPEC): per-frame rates (fuel use, shield drain, refuelling, acceleration, timers) are
 * multiplied by {@code dt}; turning integrates C's {@code turnvel = (turnvel + turnacc) * resistance}
 * recurrence exactly over {@code dt} frames.
 */
public final class Ships {
    /** C {@code ED_SHIELD} after the {@code (long)} cast in update.c: fuel per frame with shields up. */
    static final long ED_SHIELD = (long) (-0.20 * 256);
    /** C {@code REFUEL_RATE} (fixed point). */
    static final double REFUEL_RATE = 5 * 256;
    /** C {@code MAX_PLAYER_FUEL} = TANK_CAP(0) (fixed point). */
    static final double MAX_PLAYER_FUEL = 2600 * 256;
    /** C {@code WORMCOUNT}. */
    static final int WORMCOUNT = 64;
    /** C {@code TURN_FUEL} factor: 0.005 * FUEL_SCALE_FACT. */
    static final double TURN_FUEL = 0.005 * 256;

    private final World w;
    private final Simulation sim;
    /** Race-mode start order (WorldMap.baseOrder() copies on every call). */
    private final int[] baseOrder;

    public Ships(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        this.baseOrder = w.map.baseOrder();
    }

    // ================================================================ join / home

    /**
     * Add a player (C {@code Init_player} + {@code Pick_startpos} + {@code Go_home}).
     * <p>
     * BEHAVIOR: returns null when the world is full or no base is free for the team.
     * BEHAVIOR: the player starts PLAYING at the home base with initialFuel, power 45, turnspeed 30,
     * turnresistance 0.12 (alternate 35/25/0.12), shields per DEF_USED, and emits JOIN, BASE_OWNER and SCORE.
     * BEHAVIOR: in team play a request for an invalid, reserved robot, baseless or full team is replaced by
     * C {@code Pick_team}; when no team can be picked the join fails (null).
     * BEHAVIOR: with teamShareScore the first member resets the team score, and every join shares the team
     * score out among the members (netserver.c Handle_login: TEAM_SCORE(team, 0)).
     */
    public Player join(CharSequence name, int team) {
        Player pl = w.addPlayer();
        if (pl == null) {
            return null;
        }
        initPlayer(pl, name);
        if (w.params.teamPlay) {
            pl.team = chooseTeam(team, pl);
            if (pl.team == Const.TEAM_NOT_SET) {
                w.removePlayer(pl);
                return null;
            }
        }
        if (!pickStartPos(pl)) {
            w.removePlayer(pl);
            return null;
        }
        if (pl.team != Const.TEAM_NOT_SET) {
            w.teamMembers[pl.team]++;
            if (w.params.teamShareScore) {
                if (w.teamMembers[pl.team] == 1) {
                    w.teamScore[pl.team] = 0;
                }
                sim.scoring.teamScore(pl.team, 0);
            }
        }
        w.events.add(Events.JOIN, pl.id, 0, 0, 0f);
        w.events.add(Events.BASE_OWNER, pl.homeBase, pl.id, 0, 0f);
        goHome(pl);
        pl.scoreDirty = true;
        return pl;
    }

    /**
     * Remove a player.
     * <p>
     * BEHAVIOR: their objects lose their owner (they keep flying), team membership drops and LEAVE is emitted.
     */
    public void leave(Player pl) {
        if (!pl.inUse) {
            return;
        }
        int id = pl.id;
        if (pl.team != Const.TEAM_NOT_SET) {
            w.teamMembers[pl.team]--;
        }
        for (int k = 0; k < w.objects.count(); k++) {
            int h = w.objects.active[k];
            if (w.objects.owner[h] == id) {
                w.objects.owner[h] = Obj.NO_ID;
            }
        }
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            for (int i = 0; i < Player.MAX_SHOVES; i++) {
                if (o.shovePusher[i] == id) {
                    o.shovePusher[i] = Obj.NO_ID;
                }
            }
        }
        w.removePlayer(pl);
        w.events.add(Events.LEAVE, id, 0, 0, 0f);
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
        if (team >= 0 && team < Const.MAX_TEAMS && !(p.reserveRobotTeam && team == p.robotTeam)
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
    int pickTeam(Player joining) {
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

    /** C {@code Init_player} (the parts that exist in S1). */
    void initPlayer(Player pl, CharSequence name) {
        Params p = w.params;
        pl.name.setLength(0);
        pl.name.append(name);
        pl.team = Const.TEAM_NOT_SET;
        pl.homeBase = -1;
        pl.vx = pl.vy = 0;
        pl.accX = pl.accY = 0;
        pl.dir = Const.RES / 4;
        pl.floatDir = pl.dir;
        pl.turnVel = pl.oldTurnVel = pl.turnAcc = 0;
        pl.mass = pl.emptyMass = p.shipMass;
        for (int i = 0; i < Item.COUNT; i++) {
            pl.items[i] = (i == Item.FUEL.ordinal() || i == Item.TANK.ordinal()) ? 0 : p.initialItems[i];
        }
        initFuel(pl, p.initialFuel * 256);
        pl.power = 45.0;
        pl.turnSpeed = 30.0;
        pl.turnResistance = 0.12;
        pl.powerS = 35.0;
        pl.turnSpeedS = 25.0;
        pl.turnResistanceS = 0.12;
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
        pl.life = p.worldLives;
        pl.kills = 0;
        pl.deaths = 0;
        pl.shoveNext = 0;
        for (int i = 0; i < Player.MAX_SHOVES; i++) {
            pl.shovePusher[i] = Obj.NO_ID;
        }
        pl.wormHoleHit = -1;
        pl.wormHoleDest = -1;
        pl.prevKeys = 0;
        pl.remX = pl.remY = 0;
        if (p.limitedLives) {
            for (int k = 0; k < w.numPlayers; k++) {
                Player o = w.playerAt(k);
                if (o != pl && o.life < p.worldLives && !w.sameTeam(pl, o)) {
                    pl.life = 0;
                    pl.status |= Obj.GAME_OVER;
                    break;
                }
            }
        }
        setMass(pl);
    }

    /**
     * C {@code Player_init_fuel} with a single tank: max = TANK_CAP(0), fuel = min(fuel, max), no extra tanks
     * (item count 0; initialTanks is not supported).
     */
    static void initFuel(Player pl, double fuel) {
        pl.fuelMax = MAX_PLAYER_FUEL;
        pl.fuel = Math.min(fuel, pl.fuelMax);
        pl.items[Item.TANK.ordinal()] = 0;
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
     * BEHAVIOR: clears acceleration, turning, key state (held keys re-trigger next tick), USED_KILL
     * abilities and thrust; with playerStartsShielded raises shields (for 2*FPS frames when shields are
     * not otherwise allowed); emits a SPAWN game event.
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
        pl.used &= ~Has.USED_KILL;
        if (w.params.playerStartsShielded) {
            pl.used |= Has.SHIELD;
            if (!w.params.playerShielding) {
                pl.shieldTime = 2 * w.params.fps;
                pl.have |= Has.SHIELD;
            }
        }
        pl.status &= ~Obj.THRUSTING;
        w.events.add(Events.GAME_EVENT, Msg.GameEvent.SPAWN, pl.id, pl.homeBase, 0f);
    }

    // ================================================================ per tick

    /**
     * The per-player body of the C player loop for one sim tick.
     * <p>
     * BEHAVIOR: power/turnspeed/turnresistance are limited to their C ranges.
     * BEHAVIOR: a counting-down dead ship is carried home (Transport_to_home) and moved, nothing else.
     * BEHAVIOR: when the count reaches 0 a dead ship respawns at home (PLAYING + Go_home); a
     * self-destructing ship dies (score -Rate(0, score) * selfDestructScoreMult).
     * BEHAVIOR: paused, game-over or dead ships stop here.
     * BEHAVIOR: then shields time out, the ship turns, shields drain fuel, refuelling transfers fuel, fuel
     * exhaustion drops shields and thrust, thrust sets the acceleration and uses fuel, mass is updated,
     * a warping ship goes through its wormhole, gravity and acceleration change the velocity, the ship
     * moves and thrust/turn sparks are made.
     */
    public void update(Player pl) {
        Params p = w.params;
        double dt = p.dt;
        pl.power = clamp(pl.power, Const.MIN_PLAYER_POWER, Const.MAX_PLAYER_POWER);
        pl.turnSpeed = clamp(pl.turnSpeed, Const.MIN_PLAYER_TURNSPEED, Const.MAX_PLAYER_TURNSPEED);
        pl.turnResistance = clamp(pl.turnResistance, Const.MIN_PLAYER_TURNRESISTANCE,
            Const.MAX_PLAYER_TURNRESISTANCE);
        if (pl.damaged > 0) {
            pl.damaged = Math.max(0, pl.damaged - dt);
        }
        if (pl.count > 0) {
            pl.count = Math.max(0, pl.count - dt);
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
                goHome(pl);
            }
            if ((pl.status & Obj.SELF_DESTRUCT) != 0) {
                selfDestruct(pl);
            }
        }
        if ((pl.status & (Obj.PLAYING | Obj.GAME_OVER | Obj.PAUSE)) != Obj.PLAYING) {
            if ((pl.status & Obj.PAUSE) != 0) {
                pl.score -= 0.01 * dt;
            }
            return;
        }
        if (pl.stunned > 0) {
            pl.stunned = Math.max(0, pl.stunned - dt);
            pl.used &= ~(Has.SHIELD | Has.LASER | Has.SHOT);
            pl.status &= ~Obj.THRUSTING;
        }
        updateShieldTime(pl, dt);
        double tf = turn(pl, p, dt);
        sim.mover.turnPlayer(pl);

        if ((pl.used & Has.SHIELD) != 0) {
            pl.addFuel(ED_SHIELD * dt);
        }
        refuel(pl, dt);
        if (pl.fuel <= 0) {
            pl.used &= ~(Has.SHIELD | Has.CLOAKING_DEVICE | Has.DEFLECTOR);
            pl.status &= ~Obj.THRUSTING;
        }
        if (pl.fuel > pl.fuelMax - REFUEL_RATE) {
            pl.used &= ~Has.REFUEL;
        }
        thrustAcceleration(pl, dt);
        setMass(pl);
        if ((pl.status & Obj.WARPING) != 0) {
            warp(pl);
        }
        if ((pl.status & Obj.PAUSE) == 0) {
            pl.vx += (pl.accX + w.gravityX(pl.bx, pl.by)) * dt;
            pl.vy += (pl.accY + w.gravityY(pl.bx, pl.by)) * dt;
            sim.mover.movePlayer(pl);
        }
        if (((pl.used & Has.CLOAKING_DEVICE) == 0 || p.cloakedExhaust) && (pl.used & Has.PHASING_DEVICE) == 0) {
            if ((pl.status & Obj.THRUSTING) != 0) {
                sim.debris.thrust(pl);
            }
            if (tf > 0 && p.turnThrust) {
                int sparks = (int) ((5 + (((long) tf) >> 2)) * dt + w.rfrac());
                if (sparks > 0) {
                    sim.debris.turnThrust(pl, sparks);
                }
            }
        }
        pl.used &= pl.have;
    }

    private static double clamp(double v, double lo, double hi) {
        return v > hi ? hi : (v < lo ? lo : v);
    }

    /** Shield timer (update.c:793-805). */
    private void updateShieldTime(Player pl, double dt) {
        if (pl.shieldTime > 0) {
            pl.shieldTime = Math.max(0, pl.shieldTime - dt);
            if (pl.shieldTime == 0 && (pl.used & Has.EMERGENCY_SHIELD) == 0) {
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
     * Turning (update.c:860-905) over dt frames.
     * <p>
     * BEHAVIOR: with resistance r &gt; 0, turnVel follows v' = (v + acc) * r per frame, integrated exactly:
     * v = v*r^dt + acc*r*(1-r^dt)/(1-r) (acc*dt when r = 1); floatDir += v*dt.
     * BEHAVIOR: with r = 0 (pointer control) floatDir += (turnVel + acc)*dt and turnVel becomes 0.
     * BEHAVIOR: with turnThrust, turning costs (long)(0.005*256*|oldTurnVel - turnVel|) fuel (C TURN_FUEL). Not
     * multiplied by dt: the cost is per change of turn velocity, and the per-tick change already shrinks with dt,
     * so the total over a turn matches C. Without enough fuel (fuel &lt;= cost) the turn is cancelled
     * (turnAcc = 0, turnVel restored). Returns the fuel used (for turn sparks).
     * BEHAVIOR: floatDir is wrapped into [0, RES).
     */
    double turn(Player pl, Params p, double dt) {
        double r = pl.turnResistance;
        if (r != 0) {
            if (r == 1) {
                pl.turnVel += pl.turnAcc * dt;
            } else {
                double rdt = Math.pow(r, dt);
                pl.turnVel = pl.turnVel * rdt + pl.turnAcc * r * (1 - rdt) / (1 - r);
            }
        } else {
            pl.turnVel += pl.turnAcc;
        }
        double tf = 0;
        if (p.turnThrust) {
            tf = (long) (TURN_FUEL * Math.abs(pl.oldTurnVel - pl.turnVel));
            if (pl.fuel <= tf) {
                tf = 0;
                pl.turnAcc = 0;
                pl.turnVel = pl.oldTurnVel;
            } else {
                pl.addFuel(-tf);
                pl.oldTurnVel = pl.turnVel;
            }
        }
        pl.floatDir += pl.turnVel * dt;
        while (pl.floatDir < 0) {
            pl.floatDir += Const.RES;
        }
        while (pl.floatDir >= Const.RES) {
            pl.floatDir -= Const.RES;
        }
        if (r == 0) {
            pl.turnVel = 0;
        }
        return tf;
    }

    /**
     * Refuelling (update.c:950-987) with one tank.
     * <p>
     * BEHAVIOR: stops when more than 90 px from the station, full, the station is gone, phased, or (team play
     * with teamFuel) the station belongs to another team.
     * BEHAVIOR: otherwise moves REFUEL_RATE*dt from the station; a station with less gives the rest and
     * refuelling stops.
     */
    void refuel(Player pl, double dt) {
        if ((pl.used & Has.REFUEL) == 0) {
            return;
        }
        FuelStation f = w.map.fuels().get(pl.fs);
        Params p = w.params;
        if (w.wrapLength(pl.px - f.x(), pl.py - f.y()) > 90.0
            || pl.fuel >= pl.fuelMax
            || w.map.block(f.bx(), f.by()) != BlockType.FUEL
            || (pl.used & Has.PHASING_DEVICE) != 0
            || (p.teamPlay && p.teamFuel && f.team() != pl.team)) {
            pl.used &= ~Has.REFUEL;
            return;
        }
        double rate = REFUEL_RATE * dt;
        if (w.fuelLevel[pl.fs] > rate) {
            w.fuelLevel[pl.fs] -= rate;
            pl.addFuel(rate);
        } else {
            pl.addFuel(w.fuelLevel[pl.fs]);
            w.fuelLevel[pl.fs] = 0;
            pl.used &= ~Has.REFUEL;
        }
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
        double best = 1e9;
        java.util.List<FuelStation> fuels = w.map.fuels();
        for (int i = 0; i < fuels.size(); i++) {
            FuelStation f = fuels.get(i);
            if (w.map.block(f.bx(), f.by()) == BlockType.FUEL) {
                double l = w.wrapLength(pl.px - f.x(), pl.py - f.y());
                if ((pl.used & Has.REFUEL) == 0 || l < best) {
                    pl.used |= Has.REFUEL;
                    pl.fs = f.index();
                    best = l;
                }
            }
        }
    }

    /**
     * Thrust (update.c:1018-1037).
     * <p>
     * BEHAVIOR: acc = power * (cos, sin)(dir) / mass, with afterburners power *= 1 + n*(3.2/16); fuel use per
     * frame (long)(power*0.0008*256) ((f*(16+2n))/16 with afterburners), times dt.
     * BEHAVIOR: without thrust the acceleration is zero.
     */
    void thrustAcceleration(Player pl, double dt) {
        if ((pl.status & Obj.THRUSTING) == 0) {
            pl.accX = pl.accY = 0;
            return;
        }
        double power = pl.power;
        double f = pl.power * 0.0008;
        int a = pl.afterburners();
        if (a != 0) {
            power = power * (1.0 + a * ((4.2 - 1.0) / (15 + 1.0)));
            f = (f * ((15 + 1) + a * (3 - 1))) / (15 + 1.0);
        }
        pl.accX = power * Trig.tcos(pl.dir) / pl.mass;
        pl.accY = power * Trig.tsin(pl.dir) / pl.mass;
        pl.addFuel(-((long) (f * 256)) * dt);
    }

    /**
     * C {@code Player_set_mass}.
     * <p>
     * BEHAVIOR: mass = emptyMass + fuel*0.005/256 + armor*shipMass/14 + minItemMass per other item.
     */
    void setMass(Player pl) {
        double items = 0;
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
        pl.mass = pl.emptyMass + pl.fuel * 0.005 / 256 + items;
    }

    /** C {@code Player_hit_armor}: one armor is used up; the last one removes HAS_ARMOR. */
    public void hitArmor(Player pl) {
        if (--pl.items[Item.ARMOR.ordinal()] <= 0) {
            pl.have &= ~Has.ARMOR;
        }
    }

    /**
     * C {@code Transport_to_home}: velocity that carries a dead ship to its base in RECOVERY_DELAY frames,
     * accelerating for the first half and braking for the second.
     * <p>
     * BEHAVIOR: with t = count + 0.5 and T = 3*FPS: m = 2/t while 2t &lt;= T, else t' = T - t and
     * m = 4t'/(T^2 - 2t'^2); velocity = wrapped distance to the base centre * m.
     */
    void transportToHome(Player pl) {
        Base b = w.map.bases().get(pl.homeBase);
        double bx = (b.bx() + 0.5) * Const.BLOCK_SZ;
        double by = (b.by() + 0.5) * Const.BLOCK_SZ;
        double dx = w.wrapDx(bx - pl.px);
        double dy = w.wrapDy(by - pl.py);
        int bigT = 3 * w.params.fps;
        double t = pl.count + 0.5f;
        double m;
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
        if (w.params.selfDestructScoreMult != 0) {
            sim.scoring.score(pl, -Scoring.rate(0, pl.score) * w.params.selfDestructScoreMult);
        }
        pl.status |= Obj.KILLED;
        w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name).append(" has committed suicide.");
        w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, pl.id, pl.id, 0f);
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
        if (hit < 0 || hit >= n) {
            pl.status &= ~Obj.WARPING;
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
     * C {@code Kill_player}: explosion debris, then {@code Player_death_reset}.
     * <p>
     * BEHAVIOR: velocity and acceleration zeroed, mass reset, KILL_BITS cleared (so not PLAYING), items reset,
     * respawn count = max(3*FPS, count).
     * BEHAVIOR: fuel = max((long)(fuel*0.9), initialFuel*256 + (int)(rfrac*(1+that)*0.2)), capped at the tank.
     * BEHAVIOR: unless paused: deaths++; with limited lives a life is lost and the last one ends the game
     * (GAME_OVER), without limited lives life counts up (C).
     * BEHAVIOR: abilities reset to DEF_HAVE / DEF_USED minus USED_KILL.
     */
    public void kill(Player pl) {
        sim.debris.explodeShip(pl);
        Params p = w.params;
        pl.vx = pl.vy = 0;
        pl.accX = pl.accY = 0;
        pl.emptyMass = pl.mass = p.shipMass;
        pl.status &= ~Obj.KILL_BITS;
        for (int i = 0; i < Item.COUNT; i++) {
            if (i != Item.FUEL.ordinal() && i != Item.TANK.ordinal()) {
                pl.items[i] = p.initialItems[i];
            }
        }
        pl.shotMax = p.maxPlayerShots;
        pl.count = Math.max(3 * p.fps, pl.count);
        pl.damaged = 0;
        pl.stunned = 0;
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
        pl.shovePusher[pl.shoveNext] = pusher.id;
        pl.shoveTime[pl.shoveNext] = w.frames;
        if (++pl.shoveNext == Player.MAX_SHOVES) {
            pl.shoveNext = 0;
        }
    }
}
