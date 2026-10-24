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

import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Two-body collision responses between a ship and a pool object, or two pool objects: ports of
 * {@code Obj_repel}, {@code Delta_mv} and {@code Delta_mv_elastic} ({@code src/server/ship.c}) in C float
 * arithmetic. (The ship-ship repel and ship-object {@code Delta_mv} live in {@link Collisions}.)
 * A ship pushed by an object owned by another player records that player as a shover.
 */
public final class ObjPhysics {
    private final World w;
    private final Simulation sim;

    public ObjPhysics(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    /** Record {@code ownerId} as a shover of {@code pl} unless it is {@code pl} itself or gone. */
    private void shovedBy(Player pl, int ownerId) {
        if (ownerId == Obj.NO_ID) {
            return;
        }
        Player pusher = w.byId(ownerId);
        if (pusher != null && pusher != pl) {
            sim.ships.recordShove(pl, pusher);
        }
    }

    /**
     * C {@code Obj_repel(pl, obj, dist)}.
     * <p>
     * BEHAVIOR: when closer than {@code dist}, both are pushed apart along the RES-quantized line between them
     * by min(10, dist - distance), the ship by force / dm and the object by force * dm (dm = ship mass /
     * object mass); an owned object records its owner as the ship's shover. Farther apart: nothing.
     */
    public void repel(Player pl, int h, int dist) {
        Objects o = w.objects;
        float xd = (float) w.wrapDx(o.px[h] - pl.px);
        float yd = (float) w.wrapDy(o.py[h] - pl.py);
        float force = (float) (dist - StrictMath.hypot(xd, yd));
        if (force <= 0) {
            return;
        }
        force = Math.min(force, 10);
        int theta = (int) XMath.findDir(xd, yd);
        float dm = pl.mass / o.mass[h];
        float dvx2 = Trig.tcos(theta) * force * dm;
        float dvy2 = Trig.tsin(theta) * force * dm;
        float dvx1 = -(Trig.tcos(theta) * force / dm);
        float dvy1 = -(Trig.tsin(theta) * force / dm);
        shovedBy(pl, o.owner[h]);
        pl.vx += dvx1;
        pl.vy += dvy1;
        o.vx[h] += dvx2;
        o.vy[h] += dvy2;
    }

    /**
     * C {@code Obj_repel(obj1, obj2, dist)} for two pool objects.
     * <p>
     * BEHAVIOR: as {@link #repel(Player, int, int)} with obj1 in the ship's role; no shoves.
     */
    public void repel(int h1, int h2, int dist) {
        Objects o = w.objects;
        float xd = (float) w.wrapDx(o.px[h2] - o.px[h1]);
        float yd = (float) w.wrapDy(o.py[h2] - o.py[h1]);
        float force = (float) (dist - StrictMath.hypot(xd, yd));
        if (force <= 0) {
            return;
        }
        force = Math.min(force, 10);
        int theta = (int) XMath.findDir(xd, yd);
        float dm = o.mass[h1] / o.mass[h2];
        float dvx2 = Trig.tcos(theta) * force * dm;
        float dvy2 = Trig.tsin(theta) * force * dm;
        float dvx1 = -(Trig.tcos(theta) * force / dm);
        float dvy1 = -(Trig.tsin(theta) * force / dm);
        o.vx[h1] += dvx1;
        o.vy[h1] += dvy1;
        o.vx[h2] += dvx2;
        o.vy[h2] += dvy2;
    }

    /**
     * C {@code Delta_mv(obj1, obj2)} for two pool objects (inelastic).
     * <p>
     * BEHAVIOR: both end with v = (v1*m1 + v2*m2) / (m1 + |m2|).
     */
    public void deltaMv(int h1, int h2) {
        Objects o = w.objects;
        float m = o.mass[h1] + Math.abs(o.mass[h2]);
        float vx = (o.vx[h1] * o.mass[h1] + o.vx[h2] * o.mass[h2]) / m;
        float vy = (o.vy[h1] * o.mass[h1] + o.vy[h2] * o.mass[h2]) / m;
        o.vx[h1] = vx;
        o.vy[h1] = vy;
        o.vx[h2] = vx;
        o.vy[h2] = vy;
    }

    /**
     * C {@code Delta_mv_elastic(pl, obj)} (elastic).
     * <p>
     * BEHAVIOR: v1' = (m1-m2)/ms*v1 + 2*m2/ms*v2, v2' = 2*m1/ms*v1 + (m2-m1)/ms*v2 (ms = m1 + m2); a
     * COLLISIONSHOVE object records its owner as the ship's shover.
     */
    public void deltaMvElastic(Player pl, int h) {
        Objects o = w.objects;
        float m1 = pl.mass;
        float m2 = o.mass[h];
        float ms = m1 + m2;
        float v1x = pl.vx;
        float v1y = pl.vy;
        float v2x = o.vx[h];
        float v2y = o.vy[h];
        pl.vx = (m1 - m2) / ms * v1x + 2 * m2 / ms * v2x;
        pl.vy = (m1 - m2) / ms * v1y + 2 * m2 / ms * v2y;
        o.vx[h] = 2 * m1 / ms * v1x + (m2 - m1) / ms * v2x;
        o.vy[h] = 2 * m1 / ms * v1y + (m2 - m1) / ms * v2y;
        if ((o.status[h] & Obj.COLLISIONSHOVE) != 0) {
            shovedBy(pl, o.owner[h]);
        }
    }

    /** C {@code Delta_mv_elastic(obj1, obj2)} for two pool objects. BEHAVIOR: as for a ship, no shoves. */
    public void deltaMvElastic(int h1, int h2) {
        Objects o = w.objects;
        float m1 = o.mass[h1];
        float m2 = o.mass[h2];
        float ms = m1 + m2;
        float v1x = o.vx[h1];
        float v1y = o.vy[h1];
        float v2x = o.vx[h2];
        float v2y = o.vy[h2];
        o.vx[h1] = (m1 - m2) / ms * v1x + 2 * m2 / ms * v2x;
        o.vy[h1] = (m1 - m2) / ms * v1y + 2 * m2 / ms * v2y;
        o.vx[h2] = 2 * m1 / ms * v1x + (m2 - m1) / ms * v2x;
        o.vy[h2] = 2 * m1 / ms * v1y + (m2 - m1) / ms * v2y;
    }
}
