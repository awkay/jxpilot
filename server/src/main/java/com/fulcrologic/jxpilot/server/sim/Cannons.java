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
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Cannons: the cannon part of C {@code Update_objects} and {@code Cannon_check_fire}, {@code Cannon_aim},
 * {@code Cannon_fire} ({@code src/server/cannon.c}) for the plain-shot weapon (cannons have no items in
 * S1, so {@code Cannon_select_weapon} always picks CW_SHOT and draws no random numbers).
 * <p>
 * Time scaling: C checks a live cannon with probability 1/16 per frame; we use dt/16 per tick, so cannons
 * fire at the same average rate.
 */
public final class Cannons {
    /** C {@code CANNON_DISTANCE}. */
    static final double CANNON_DISTANCE = Const.VISIBILITY_DISTANCE * 0.5;
    /** C {@code CANNON_SHOT_LIFE_MAX}. */
    static final int CANNON_SHOT_LIFE_MAX = 8 + 24;
    /** C {@code CANNON_SPREAD}. */
    static final int CANNON_SPREAD = Const.RES / 3;

    private final World w;
    private final Simulation sim;
    /** Outputs of {@link #aim}. */
    int target;
    int aimDir;

    public Cannons(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    /**
     * One tick for every cannon.
     * <p>
     * BEHAVIOR: a dead cannon counts down and comes back (its block becomes CANNON again) at zero.
     * BEHAVIOR: a live cannon looks for a target with probability dt/16 and fires if it finds one.
     */
    public void update() {
        double dt = w.params.dt;
        for (int i = 0; i < w.cannonDeadTime.length; i++) {
            if (w.cannonDeadTime[i] > 0) {
                w.cannonDeadTime[i] = Math.max(0, w.cannonDeadTime[i] - dt);
                if (w.cannonDeadTime[i] == 0) {
                    Cannon c = w.map.cannons().get(i);
                    w.map.setBlock(c.bx(), c.by(), BlockType.CANNON);
                }
                continue;
            }
            if (w.rfrac() * 16 < dt) {
                checkFire(i);
            }
        }
    }

    /** C {@code Cannon_check_fire}: aim and, with a target, fire. */
    void checkFire(int i) {
        if (aim(i)) {
            fire(i, aimDir);
        }
    }

    /**
     * C {@code Cannon_aim} for CW_SHOT.
     * <p>
     * BEHAVIOR: candidates are playing ships within the visual range (500 px) that are not on the cannon's team
     * (team play); smartness 3 ignores phased ships.
     * BEHAVIOR: smartness 0 fires straight ahead at anyone seen; 1 fires ahead with random spread +-RES/6 at
     * anyone within range (32 * shotSpeed); 2 aims at the closest, limited to the cannon's sector, +-3 noise;
     * 3 leads the target (flight time = tdist / speed in whole frames, C integer division) and fires only
     * at a predicted position inside the sector.
     * BEHAVIOR: returns false (no target) when nobody qualifies.
     */
    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    boolean aim(int i) {
        Cannon c = w.map.cannons().get(i);
        int smart = w.params.cannonSmartness;
        int speed = (int) w.params.shotSpeed;
        int range = CANNON_SHOT_LIFE_MAX * speed;
        int cpx = (int) c.x();
        int cpy = (int) c.y();
        int visualRange = (int) CANNON_DISTANCE;
        boolean found = false;
        boolean ready = false;
        int closest = range;
        int dir = 0;
        target = -1;
        for (int k = 0; k < w.numPlayers && !ready; k++) {
            Player pl = w.playerAt(k);
            int tdx = (int) w.wrapDx(pl.px - cpx);
            if (Math.abs(tdx) >= visualRange) {
                continue;
            }
            int tdy = (int) w.wrapDy(pl.py - cpy);
            if (Math.abs(tdy) >= visualRange) {
                continue;
            }
            int tdist = (int) Math.hypot(tdx, tdy);
            if (tdist > visualRange) {
                continue;
            }
            if ((pl.status & (Obj.PLAYING | Obj.GAME_OVER | Obj.PAUSE | Obj.KILLED)) != Obj.PLAYING
                || (w.params.teamPlay && pl.team == c.team())
                || (smart > 2 && (pl.used & Has.PHASING_DEVICE) != 0)) {
                continue;
            }
            switch (smart) {
                case 0 -> ready = true;
                case 2 -> {
                    if (tdist < closest) {
                        dir = (int) XMath.findDir(tdx, tdy);
                        found = true;
                    }
                }
                case 3 -> {
                    if (tdist < range) {
                        // C: DFLOAT time = tdist / speed with two ints, an integer division.
                        double time = tdist / speed;
                        int npx = (int) (pl.px + pl.vx * time + pl.accX * time * time);
                        int npy = (int) (pl.py + pl.vy * time + pl.accY * time * time);
                        int ndx = (int) w.wrapDx(npx - cpx);
                        int ndy = (int) w.wrapDy(npy - cpy);
                        int tdir = (int) XMath.findDir(ndx, ndy);
                        int ddir = (tdir - c.dir()) & (Const.RES - 1);
                        if ((ddir < (CANNON_SPREAD * 0.5) || ddir > Const.RES - (CANNON_SPREAD * 0.5))
                            && (int) Math.hypot(ndx, ndy) < closest) {
                            dir = tdir;
                            found = true;
                        }
                    }
                }
                default -> {
                    if (tdist < range) {
                        ready = true;
                    }
                }
            }
            if (found || ready) {
                closest = tdist;
                target = k;
            }
        }
        if (!(found || ready)) {
            target = -1;
            return false;
        }
        switch (smart) {
            case 0 -> dir = c.dir();
            case 2 -> {
                int ddir = (dir - c.dir()) & (Const.RES - 1);
                if (ddir > (CANNON_SPREAD * 0.5) && ddir < Const.RES / 2) {
                    dir = (int) (c.dir() + (CANNON_SPREAD * 0.5) + 3);
                } else if (ddir < Const.RES - (CANNON_SPREAD * 0.5) && ddir > Const.RES / 2) {
                    dir = (int) (c.dir() - (CANNON_SPREAD * 0.5) - 3);
                }
                dir += (int) (w.rfrac() * 7) - 3;
            }
            case 3 -> { }
            default -> dir = c.dir() + (int) ((w.rfrac() - 0.5f) * CANNON_SPREAD);
        }
        aimDir = dir & (Const.RES - 1);
        return true;
    }

    /**
     * C {@code Cannon_fire} CW_SHOT without items: one cannon shot from the cannon's firing point, a
     * cluster (flak) shot with cannonFlak.
     */
    void fire(int i, int dir) {
        Cannon c = w.map.cannons().get(i);
        int mods = w.params.cannonFlak ? Obj.MOD_CLUSTER : 0;
        sim.shots.fire(null, c.team(), true, (int) c.x(), (int) c.y(), Obj.CANNON_SHOT, dir, mods);
    }
}
