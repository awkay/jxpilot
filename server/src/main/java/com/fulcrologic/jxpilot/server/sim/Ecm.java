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
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.server.world.CannonItems;
import com.fulcrologic.jxpilot.server.world.Ecms;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * ECM: ports of {@code Fire_ecm} and {@code Fire_general_ecm} ({@code src/server/item.c}) and the ECM and
 * transporter beam updates of {@code Update_objects} (update.c:588-609).
 */
public final class Ecm {
    /** C {@code ECM_DISTANCE}. */
    static final double ECM_DISTANCE = Const.VISIBILITY_DISTANCE * 0.4;
    /** C {@code ED_ECM}. */
    static final long ED_ECM = -60 * 256;
    /** C {@code MAX_PLAYER_ECMS}. */
    static final int MAX_PLAYER_ECMS = 8;
    /** C {@code CONFUSED_TIME}. */
    static final int CONFUSED_TIME = 3;

    private final World w;
    private final Simulation sim;

    /**
     * Called when an ECM reprograms a robot to hunt a player (C {@code Robot_program}; the robot manager sets it).
     * Arguments: the robot, the target id.
     */
    public interface RobotProgrammer {
        void program(Player robot, int targetId);
    }

    /** Robot reprogramming hook (null: robots are blinded like humans). */
    public RobotProgrammer robotProgrammer;

    /**
     * ECM ball detaching (item.c:1168-1181) for the ball owner's code: for each ball {@code p} tows, detach it
     * when {@code (int)(rfrac()*100) < (int)(20*range)+5}.
     */
    public interface BallDetacher {
        void ecmDetach(Player p, float range);
    }

    /** Ball detaching hook (null until balls exist). */
    public BallDetacher ballDetacher;

    public Ecm(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    /** {@code StrictMath.pow(0.75, n)} for item counts 0..255, computed once: pow allocates without escape analysis. */
    private static final double[] POW075 = new double[256];

    static {
        for (int n = 0; n < POW075.length; n++) {
            POW075[n] = StrictMath.pow(0.75, n);
        }
    }

    /** BEHAVIOR: C's pow(0.75, n), from the table for 0..255. */
    static double pow075(int n) {
        return n >= 0 && n < POW075.length ? POW075[n] : StrictMath.pow(0.75, n);
    }

    /**
     * C {@code Fire_ecm} (KEY_ECM).
     * <p>
     * BEHAVIOR: nothing without an ECM, with fuel at or below 60 units, with MAX_PLAYER_ECMS blasts still
     * expanding, or while phased (item.c:1222-1226); otherwise a blast at the ship.
     */
    public void fire(Player pl) {
        if (pl.items[Item.ECM.ordinal()] == 0 || pl.fuel <= -ED_ECM || pl.ecmCount >= MAX_PLAYER_ECMS
            || (pl.used & Has.PHASING_DEVICE) != 0) {
            return;
        }
        fireGeneral(pl, pl.team, pl.px, pl.py);
    }

    /**
     * C {@code Fire_general_ecm} by a player ({@code pl}) or a cannon ({@code pl == null}) at (x, y).
     * <p>
     * BEHAVIOR: nothing when MAX_TOTAL_ECMS blasts exist. A blast of radius ECM_DISTANCE starts; a player pays an
     * ECM and its fuel, counts the blast and plays ECM at (x, y) (a cannon's blast is silent).
     * BEHAVIOR: smart missiles and mines within ECM_DISTANCE are hit, except own owner-immune mines, own
     * missiles after somebody else, and team-immune or (team play) same-team objects of others. (C compares the
     * owner's player index where it means its id, for the missile target and the team-immunity test; kept.) A smart missile
     * is confused for CONFUSED_TIME re-targets and will re-lock on the shooter's visible lock (or a random
     * player). A mine explodes with 5-15% by closeness (always inside its own range), else is confused for
     * 2-10 s; the closest unconfused one may be reprogrammed (with ecmsReprogramMines, 50-100% by closeness)
     * to the shooter and its team.
     * BEHAVIOR: cannons (in team play, or any player's ECM) not on the team within range lose lasers by
     * closeness and are damaged 24 * range * 0.75^sensors frames.
     * BEHAVIOR: other playing, unphased players within range, not on the team (team play): forceVisible grows by
     * 24*closeness (less with cloaks), towed balls may detach, lasers are lost by closeness; humans (or all,
     * without ecmsReprogramRobots or a player shooter) are blinded for 24*closeness*0.75^sensors frames (the
     * shooter is recorded as a shover until then); robots are reprogrammed to hunt the shooter's visible lock.
     */
    public void fireGeneral(Player pl, int team, float x, float y) {
        Ecms e = w.ecms;
        int ei = e.add();
        if (ei < 0) {
            return;
        }
        e.x[ei] = x;
        e.y[ei] = y;
        e.owner[ei] = pl != null ? pl.id : Obj.NO_ID;
        e.size[ei] = (int) ECM_DISTANCE;
        if (pl != null) {
            pl.ecmCount++;
            pl.items[Item.ECM.ordinal()]--;
            pl.addFuel(ED_ECM);
            Sounds.play(w, x, y, SoundEvent.ECM);
        }
        Objects o = w.objects;
        int closestMine = -1;
        float closestMineRange = (float) w.params.hypotenuse;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if ((o.type[h] & (Obj.SMART_SHOT | Obj.MINE)) == 0) {
                continue;
            }
            float range = w.wrapLength(x - o.px[h], y - o.py[h]);
            if (range > ECM_DISTANCE) {
                continue;
            }
            if (o.owner[h] != Obj.NO_ID) {
                int owner = o.owner[h];
                if (pl != null && owner == pl.id) {
                    if (o.type[h] == Obj.MINE && (o.status[h] & Obj.OWNERIMMUNE) != 0) {
                        continue;
                    }
                    if (o.type[h] == Obj.SMART_SHOT && o.info[h] != ownerIndex(owner)) {
                        continue;
                    }
                } else if ((pl != null && w.teamImmune(pl.id, ownerIndex(owner)))
                    || (w.params.teamPlay && team == o.team[h])) {
                    continue;
                }
            }
            if (o.type[h] == Obj.SMART_SHOT) {
                o.status[h] |= Obj.CONFUSED;
                o.ecmRange[h] = range;
                o.counter[h] = CONFUSED_TIME;
                Player t = pl != null ? w.byId(pl.lockPlId) : null;
                if (pl != null && (pl.lockTagged & Player.LOCK_PLAYER) != 0
                    && (pl.lockDistance <= pl.sensorRange || !w.items.limitedVisibility)
                    && t != null && pl.canSee[t.ind]) {
                    o.newInfo[h] = pl.lockPlId;
                } else {
                    o.newInfo[h] = w.playerAt((int) (w.rfrac() * w.numPlayers)).id;
                }
            } else {
                o.ecmRange[h] = range;
                float perim = (float) (Mines.MINE_RANGE / (Mods.mini(o.mods[h]) + 1));
                range = (float) ((range - perim) / (ECM_DISTANCE - perim));
                if (range <= 0 || (int) (w.rfrac() * 100.0f) < ((int) (10 * (1 - range)) + 5)) {
                    o.life[h] = 0;
                    continue;
                }
                o.counter[h] = ((int) (8 * (1 - range)) + 2) * w.params.fps;
                if ((o.status[h] & Obj.CONFUSED) == 0 && (closestMine == -1 || range < closestMineRange)) {
                    closestMine = h;
                    closestMineRange = range;
                }
                o.status[h] |= Obj.CONFUSED;
                if (o.counter[h] <= 0) {
                    o.status[h] &= ~Obj.CONFUSED;
                }
            }
        }
        if (w.items.ecmsReprogramMines && closestMine != -1) {
            float range = closestMineRange;
            if (range <= 0 || (int) (w.rfrac() * 100.0f) < (100 - (int) (50 * range))) {
                o.owner[closestMine] = pl != null ? pl.id : Obj.NO_ID;
            }
            o.team[closestMine] = team;
        }
        if (w.params.teamPlay || pl != null) {
            CannonItems ci = w.cannonItems;
            for (int i = 0; i < w.map.cannons().size(); i++) {
                Cannon c = w.map.cannons().get(i);
                if (w.params.teamPlay && c.team() == team) {
                    continue;
                }
                float range = w.wrapLength(x - (float) c.x(), y - (float) c.y());
                if (range > ECM_DISTANCE) {
                    continue;
                }
                float damage = (float) ((ECM_DISTANCE - range) / ECM_DISTANCE);
                int lasers = ci.item(i, Item.LASER);
                if (lasers != 0) {
                    ci.add(i, Item.LASER, -(int) (damage * lasers + 0.5));
                }
                ci.damaged[i] += (int) (24 * range * pow075(ci.item(i, Item.SENSOR)));
            }
        }
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (p == pl) {
                continue;
            }
            if (w.params.teamPlay && p.team == team) {
                continue;
            }
            if ((p.used & Has.PHASING_DEVICE) != 0) {
                continue;
            }
            if ((p.status & (Obj.PLAYING | Obj.GAME_OVER | Obj.PAUSE)) != Obj.PLAYING) {
                continue;
            }
            float range = w.wrapLength(x - p.px, y - p.py);
            if (range > ECM_DISTANCE) {
                continue;
            }
            range = (float) ((ECM_DISTANCE - range) / ECM_DISTANCE);
            float damage = 24.0f * range;
            int cloaks = p.items[Item.CLOAK.ordinal()];
            if (cloaks <= 1) {
                p.forceVisible += (int) damage;
            } else {
                p.forceVisible += (int) (damage * pow075(cloaks - 1));
            }
            if ((p.have & Has.BALL) != 0 && ballDetacher != null) {
                ballDetacher.ecmDetach(p, range);
            }
            if (p.items[Item.LASER.ordinal()] > 0) {
                p.items[Item.LASER.ordinal()] -= (int) (range * p.items[Item.LASER.ordinal()] + 0.5);
            }
            if (!p.robot || !w.items.ecmsReprogramRobots || pl == null) {
                long duration = (int) (damage * pow075(p.items[Item.SENSOR.ordinal()]));
                p.damaged += (int) duration;
                if (pl != null) {
                    sim.ships.recordShove(p, pl, w.frames + duration);
                }
            } else {
                Player t = w.byId(pl.lockPlId);
                if ((pl.lockTagged & Player.LOCK_PLAYER) != 0
                    && (pl.lockDistance < pl.sensorRange || !w.items.limitedVisibility)
                    && t != null && pl.canSee[t.ind] && pl.lockPlId != p.id && robotProgrammer != null) {
                    robotProgrammer.program(p, pl.lockPlId);
                    w.events.add(com.fulcrologic.jxpilot.server.world.Events.SEEK, pl.id, p.id, pl.lockPlId, 0f);
                }
            }
        }
    }

    /**
     * C compares a smart missile's target id ({@code info}) with the owner's player index ({@code GetInd}).
     * <p>
     * BEHAVIOR: the owner's position in the player order, or -1 (C's quirk kept: own missiles are only hit when
     * their target id equals that index).
     */
    int ownerIndex(int id) {
        for (int k = 0; k < w.numPlayers; k++) {
            if (w.playerAt(k).id == id) {
                return k;
            }
        }
        return -1;
    }

    /**
     * ECM blasts and transporter beams age (update.c:588-609).
     * <p>
     * BEHAVIOR: each blast's radius halves; at 0 it ends and its owner's blast count drops. Each transporter beam
     * counts down and ends at 0. Removal moves the last entry into the slot, which is then looked at again.
     */
    public void update() {
        Ecms e = w.ecms;
        for (int i = 0; i < e.count(); i++) {
            if ((e.size[i] >>= 1) == 0) {
                if (e.owner[i] != Obj.NO_ID) {
                    Player p = w.byId(e.owner[i]);
                    if (p != null) {
                        p.ecmCount--;
                    }
                }
                e.remove(i);
                i--;
            }
        }
        for (int i = 0; i < e.transporters(); i++) {
            if (--e.transCount[i] <= 0) {
                e.removeTransporter(i);
                i--;
            }
        }
    }
}
