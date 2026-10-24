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
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Target;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Targets: ports of {@code Object_hits_target} ({@code src/server/walls.c}), the target update of
 * {@code Update_objects} (repair over time, {@code targetSync}) and the target repair of the player loop
 * ({@code update.c}), and {@code Repair} ({@code event.c}).
 * <p>
 * Damage entry points: {@link #objectHits} for a pool object that crashed into a target (shots, missiles,
 * mines, laser pulses: the caller's {@link MoveState} gives the target), {@link #playerHits} for a ship, and
 * {@link #damage} for callers that already know the C object fields.
 */
public final class Targets {
    /** C {@code ED_SHOT_HIT}. */
    static final double ED_SHOT_HIT = -25.0 * 256;
    /** C {@code ED_SMART_SHOT_HIT}. */
    static final double ED_SMART_SHOT_HIT = -120.0 * 256;
    /** C {@code ED_LASER_HIT}. */
    static final double ED_LASER_HIT = -100.0 * 256;

    private final World w;
    private final Simulation sim;
    /** C {@code TARGET_REPAIR_PER_FRAME} = TARGET_DAMAGE / (FPS * 600). */
    final int repairPerFrame;
    /** C {@code TARGET_FUEL_REPAIR_PER_FRAME} = TARGET_DAMAGE / (FPS * 10). */
    final int fuelRepairPerFrame;

    public Targets(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        int fps = w.params.fps;
        repairPerFrame = ModeState.TARGET_DAMAGE / (fps * 600);
        fuelRepairPerFrame = ModeState.TARGET_DAMAGE / (fps * 10);
    }

    // ================================================================ per frame

    /**
     * The target loop of C {@code Update_objects}.
     * <p>
     * BEHAVIOR: a dead target counts down; when it reaches 0 it reappears (its block is a TARGET again) and with
     * targetSync every target of its team reappears fully repaired at the same time.
     * BEHAVIOR: a damaged live target repairs TARGET_REPAIR_PER_FRAME per frame up to full strength.
     */
    public void update() {
        ModeState m = w.modes;
        int[] dead = w.targetDeadTime;
        for (int i = 0; i < dead.length; i++) {
            if (dead[i] > 0) {
                if (--dead[i] == 0) {
                    revive(i);
                    if (w.params.targetSync) {
                        int team = m.targetTeam[i];
                        for (int j = 0; j < dead.length; j++) {
                            if (m.targetTeam[j] == team) {
                                revive(j);
                                dead[j] = 0;
                                m.targetDamage[j] = ModeState.TARGET_DAMAGE;
                            }
                        }
                    }
                }
                continue;
            } else if (m.targetDamage[i] == ModeState.TARGET_DAMAGE) {
                continue;
            }
            m.targetDamage[i] += repairPerFrame;
            if (m.targetDamage[i] >= ModeState.TARGET_DAMAGE) {
                m.targetDamage[i] = ModeState.TARGET_DAMAGE;
            }
        }
    }

    private void revive(int i) {
        Target t = w.map.targets().get(i);
        w.map.setBlock(t.bx(), t.by(), BlockType.TARGET);
    }

    /**
     * C {@code Repair} (KEY_REPAIR pressed): repair the nearest live target of the own team.
     * <p>
     * BEHAVIOR: needs HAS_REPAIR; picks the nearest live own-team target, or none (repair off).
     */
    public void startRepair(Player pl) {
        if ((pl.have & Has.REPAIR) == 0) {
            return;
        }
        ModeState m = w.modes;
        pl.used &= ~Has.REPAIR;
        double dist = 1e9;
        for (int i = 0; i < w.targetDeadTime.length; i++) {
            if (m.targetTeam[i] == pl.team && w.targetDeadTime[i] <= 0) {
                Target t = w.map.targets().get(i);
                float x = t.bx() * Const.BLOCK_SZ + Const.BLOCK_SZ / 2;
                float y = t.by() * Const.BLOCK_SZ + Const.BLOCK_SZ / 2;
                float l = w.wrapLength(pl.px - x, pl.py - y);
                if ((pl.used & Has.REPAIR) == 0 || l < dist) {
                    pl.used |= Has.REPAIR;
                    pl.repairTarget = i;
                    dist = l;
                }
            }
        }
    }

    /**
     * Target repair in the C player loop (update.c:995-1028).
     * <p>
     * BEHAVIOR: repair stops when more than 90 px from the target, it is at full strength, dead, or the ship
     * phases.
     * BEHAVIOR: otherwise each of the ship's tanks in turn (num_tanks + 1 steps from the current one, which is
     * restored afterwards): a tank holding more than REFUEL_RATE gives REFUEL_RATE and the target gains
     * TARGET_FUEL_REPAIR_PER_FRAME (capped at full, which ends the round); a tank with less turns repair off
     * (later tanks still give).
     */
    public void repair(Player pl) {
        if ((pl.used & Has.REPAIR) == 0) {
            return;
        }
        ModeState m = w.modes;
        int i = pl.repairTarget;
        Target t = w.map.targets().get(i);
        float x = (float) ((t.bx() + 0.5) * Const.BLOCK_SZ);
        float y = (float) ((t.by() + 0.5) * Const.BLOCK_SZ);
        if (w.wrapLength(pl.px - x, pl.py - y) > 90.0
            || m.targetDamage[i] >= ModeState.TARGET_DAMAGE
            || w.targetDeadTime[i] > 0
            || (pl.used & Has.PHASING_DEVICE) != 0) {
            pl.used &= ~Has.REPAIR;
            return;
        }
        // C goes round all num_tanks + 1 tanks from the current one (update.c:1006-1027)
        int n = pl.numTanks;
        int ct = pl.currentTank;
        do {
            if (pl.tankFuel[pl.currentTank] > Ships.REFUEL_RATE) {
                m.targetDamage[i] += fuelRepairPerFrame;
                pl.addFuel(-Ships.REFUEL_RATE);
                if (m.targetDamage[i] > ModeState.TARGET_DAMAGE) {
                    m.targetDamage[i] = ModeState.TARGET_DAMAGE;
                    break;
                }
            } else {
                pl.used &= ~Has.REPAIR;
            }
            if (pl.currentTank == pl.numTanks) {
                pl.currentTank = 0;
            } else {
                pl.currentTank += 1;
            }
        } while (n-- != 0);
        pl.currentTank = ct;
    }

    // ================================================================ hits

    /**
     * C {@code Object_hits_target} for a pool object that crashed into target {@code target}
     * ({@code Object_crash} CrashTarget).
     * <p>
     * BEHAVIOR: passes the object's type, owner, team, velocity and mass; warhead modifiers come from
     * {@link #nuclear}, {@link #mini} and {@link #shotMult}.
     */
    public void objectHits(int target, int h) {
        Objects o = w.objects;
        damage(target, o.type[h], o.owner[h], o.team[h], o.vx[h], o.vy[h], o.mass[h], nuclear(h), mini(h),
            shotMult(h), -1);
    }

    /**
     * C {@code Object_hits_target} for a ship: a fatal crash ({@code playerCost} -1) or a survived bounce (the
     * bounce's fuel cost).
     */
    public void playerHits(int target, Player pl, long playerCost) {
        damage(target, Obj.PLAYER, pl.id, pl.team, pl.vx, pl.vy, pl.mass, false, 0, 1.0f, playerCost);
    }

    /** C {@code BIT(mods.nuclear, NUCLEAR)} of an object. */
    boolean nuclear(int h) {
        return Mods.isNuclear(w.objects.mods[h]);
    }

    /** C {@code mods.mini} of an object. */
    int mini(int h) {
        return Mods.mini(w.objects.mods[h]);
    }

    /** C {@code mods.power} of an object. */
    int power(int h) {
        return Mods.power(w.objects.mods[h]);
    }

    /** C {@code SHOT_MULT(obj)}: nukeClusterDamage for nuclear cluster shots, else 1. */
    float shotMult(int h) {
        return sim.collisions.shotMult(h);
    }

    /**
     * C {@code Object_hits_target}.
     * <p>
     * BEHAVIOR: only killing shots (with player killing on), mines, laser pulses and ships do damage, only when
     * owned by a player, and never to their own team's target.
     * BEHAVIOR: damage: a shot (int)(ED_SHOT_HIT * drain * shotMult) with drain = |v|^2*|mass| /
     * (shotSpeed^2*shotMass) under shotHitFuelDrainUsesKineticEnergy, else 1; a pulse ED_LASER_HIT; a missile
     * (int)(ED_SMART_SHOT_HIT / (mini+1)), a nuclear one all of it; a mine TARGET_DAMAGE / (mini+1); a ship its
     * cost, at most (and by default) TARGET_DAMAGE / 4. Massless missiles and mines (end of round) do nothing.
     * BEHAVIOR: at zero strength the target dies for targetDeadTime frames: full strength restored, its block
     * becomes SPACE and 75-150 red debris fly; a TARGET_DESTROYED game event follows.
     * BEHAVIOR: scoring only in team play while a member of the target's team is still in the game: with
     * targets of that team left, the killer gets Rate(score, CANNON_SCORE)/4 * destroyed/(total+1) (at least
     * 0.01, "Target: ") and with targetTeamCollision and fewer than 10 targets "NAME blew up one of team T's
     * targets."; for the last one "NAME blew up team T's [last ]target.", with targetKillTeam the killer gets a
     * kill and every live member of the target's team dies, the target's team loses Rate(winners, losers) each
     * and the killer's team gains it * losers / winners each.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public void damage(int target, int type, int ownerId, int team, float vx, float vy, float mass,
                       boolean nuclear, int mini, float shotMult, long playerCost) {
        Params p = w.params;
        ModeState m = w.modes;
        if (target < 0) {
            return;
        }
        if ((type & (p.killingShots | Obj.MINE | Obj.PULSE | Obj.PLAYER)) == 0) {
            return;
        }
        if (ownerId <= 0) {
            return;
        }
        Player killer = w.byId(ownerId);
        if (killer == null || m.targetTeam[target] == team) {
            return;
        }
        switch (type) {
            case Obj.SHOT -> {
                float drainFactor;
                if (p.shotHitFuelDrainUsesKineticEnergy) {
                    drainFactor = (float) StrictMath.hypot(vx, vy);
                    drainFactor = (drainFactor * drainFactor * Math.abs(mass)) / (p.shotSpeed * p.shotSpeed * p.shotMass);
                } else {
                    drainFactor = 1.0f;
                }
                m.targetDamage[target] += (int) (ED_SHOT_HIT * drainFactor * shotMult);
            }
            case Obj.PULSE -> m.targetDamage[target] += (int) ED_LASER_HIT;
            case Obj.SMART_SHOT, Obj.TORPEDO, Obj.HEAT_SHOT -> {
                if (mass == 0) {
                    return;
                }
                if (nuclear) {
                    m.targetDamage[target] = 0;
                } else {
                    m.targetDamage[target] += (int) (ED_SMART_SHOT_HIT / (mini + 1));
                }
            }
            case Obj.MINE -> {
                if (mass == 0) {
                    return;
                }
                m.targetDamage[target] -= ModeState.TARGET_DAMAGE / (mini + 1);
            }
            case Obj.PLAYER -> {
                if (playerCost <= 0 || playerCost > ModeState.TARGET_DAMAGE / 4) {
                    playerCost = ModeState.TARGET_DAMAGE / 4;
                }
                m.targetDamage[target] -= playerCost;
            }
            default -> { }
        }
        if (m.targetDamage[target] > 0) {
            return;
        }
        destroy(target, killer);
    }

    private void destroy(int target, Player killer) {
        Params p = w.params;
        ModeState m = w.modes;
        Target targ = w.map.targets().get(target);
        int targTeam = m.targetTeam[target];
        m.targetDamage[target] = ModeState.TARGET_DAMAGE;
        w.targetDeadTime[target] = p.targetDeadTime;
        w.map.setBlock(targ.bx(), targ.by(), BlockType.SPACE);
        sim.debris.make((targ.bx() + 0.5f) * Const.BLOCK_SZ, (targ.by() + 0.5f) * Const.BLOCK_SZ, 0f, 0f, Obj.NO_ID,
            targTeam, Obj.DEBRIS, 4.5f, Obj.GRAVITY, Records.ObjColor.RED, Records.BurstKind.DEBRIS, 6, 75, 150, 0,
            Const.RES - 1, 20, 70, 10, 100);
        w.events.add(Events.GAME_EVENT, Msg.GameEvent.TARGET_DESTROYED, killer.id, target, 0f);

        float winScore = 0;
        float loseScore = 0;
        int winMembers = 0;
        int loseMembers = 0;
        boolean somebody = false;
        if (p.teamPlay) {
            for (int k = 0; k < w.numPlayers; k++) {
                Player pl = w.playerAt(k);
                if (Balls.ignored(pl)) {
                    continue;
                }
                if (pl.team == targTeam) {
                    loseScore += pl.score;
                    loseMembers++;
                    if ((pl.status & Obj.GAME_OVER) == 0) {
                        somebody = true;
                    }
                } else if (pl.team == killer.team) {
                    winScore += pl.score;
                    winMembers++;
                }
            }
        }
        if (!somebody) {
            return;
        }
        // C passes the target's block coordinates as the sound position (walls.c:1714, 1771), so only ships
        // near the map origin hear it; kept for parity
        Sounds.play(w, targ.bx(), targ.by(), SoundEvent.DESTROY_TARGET);
        int total = 0;
        int remaining = 0;
        for (int j = 0; j < w.targetDeadTime.length; j++) {
            if (m.targetTeam[j] == targTeam) {
                total++;
                if (w.targetDeadTime[j] == 0) {
                    remaining++;
                }
            }
        }
        if (remaining > 0) {
            float sc = Scoring.rate(killer.score, Scoring.CANNON_SCORE) / 4;
            sc = sc * (total - remaining) / (total + 1);
            if (sc >= 0.01) {
                sim.scoring.score(killer, sc, targ.bx(), targ.by(), "Target: ");
            }
            if (p.targetTeamCollision && total < 10) {
                w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(killer.name).append(" blew up one of team ")
                    .append(targTeam).append("'s targets.");
            }
            return;
        }
        w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(killer.name).append(" blew up team ")
            .append(targTeam).append("'s ").append(total > 1 ? "last " : "").append("target.");
        if (p.targetKillTeam) {
            killer.kills++;
        }
        float sc = Scoring.rate(winScore, loseScore);
        float por = (sc * loseMembers) / winMembers;
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if (Balls.ignored(pl)) {
                continue;
            }
            if (pl.team == targTeam) {
                if (p.targetKillTeam && (pl.status & (Obj.KILLED | Obj.PAUSE | Obj.GAME_OVER)) == 0) {
                    pl.status |= Obj.KILLED;
                }
                sim.scoring.score(pl, -sc, targ.bx(), targ.by(), "Target: ");
            } else if (pl.team == killer.team && (pl.team != Const.TEAM_NOT_SET || pl == killer)) {
                sim.scoring.score(pl, por, targ.bx(), targ.by(), "Target: ");
            }
        }
    }

    /** The target part of C {@code Reset_all_players} (team play with endOfRoundReset): all targets intact. */
    public void resetRound() {
        ModeState m = w.modes;
        for (int i = 0; i < w.targetDeadTime.length; i++) {
            if (m.targetDamage[i] != ModeState.TARGET_DAMAGE || w.targetDeadTime[i] != 0) {
                revive(i);
                w.targetDeadTime[i] = 0;
                m.targetDamage[i] = ModeState.TARGET_DAMAGE;
            }
        }
    }
}
