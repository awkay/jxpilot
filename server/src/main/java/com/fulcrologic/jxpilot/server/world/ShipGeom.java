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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.math.ShipShape;

/**
 * A ship shape with its points pre-rotated to every RES heading, as C {@code Rotate_point} fills
 * {@code shipobj.pts[i][dir]} ({@code src/common/shipshape.c}): in float arithmetic over the float
 * {@link Trig} tables, so the hull points truncate to exactly C's clicks. Built once per player shape
 * (join time, never per tick).
 */
public final class ShipGeom {
    public final ShipShape shape;
    public final int numPoints;
    /** {@code ptX[i][dir]}: hull point i rotated to heading dir. */
    public final float[][] ptX;
    public final float[][] ptY;
    public final float[] engineX = new float[Const.RES];
    public final float[] engineY = new float[Const.RES];
    public final float[] gunX = new float[Const.RES];
    public final float[] gunY = new float[Const.RES];

    public ShipGeom(ShipShape shape) {
        this.shape = shape;
        this.numPoints = shape.numPoints();
        ptX = new float[numPoints][Const.RES];
        ptY = new float[numPoints][Const.RES];
        for (int i = 0; i < numPoints; i++) {
            rotate(shape.ptX[i], shape.ptY[i], ptX[i], ptY[i]);
        }
        rotate(shape.engineX, shape.engineY, engineX, engineY);
        rotate(shape.mainGunX, shape.mainGunY, gunX, gunY);
    }

    /**
     * C {@code Rotate_point}.
     * <p>
     * BEHAVIOR: out[0] = (x, y); out[dir] = (tcos(dir) x - tsin(dir) y, tsin(dir) x + tcos(dir) y) in float.
     */
    static void rotate(int x, int y, float[] outX, float[] outY) {
        float fx = x;
        float fy = y;
        outX[0] = fx;
        outY[0] = fy;
        for (int d = 1; d < Const.RES; d++) {
            outX[d] = Trig.tcos(d) * fx - Trig.tsin(d) * fy;
            outY[d] = Trig.tsin(d) * fx + Trig.tcos(d) * fy;
        }
    }

    private static final ShipGeom DEFAULT = new ShipGeom(ShipShape.defaultShip());

    /** The default triangle ship. */
    public static ShipGeom defaultShip() {
        return DEFAULT;
    }
}
