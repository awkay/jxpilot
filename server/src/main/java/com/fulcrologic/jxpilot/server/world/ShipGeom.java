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
 * {@code shipobj.pts[i][dir]} ({@code src/common/shipshape.c}). Built once per player shape (join time,
 * never per tick).
 */
public final class ShipGeom {
    public final ShipShape shape;
    public final int numPoints;
    /** {@code ptX[i][dir]}: hull point i rotated to heading dir. */
    public final double[][] ptX;
    public final double[][] ptY;
    public final double[] engineX = new double[Const.RES];
    public final double[] engineY = new double[Const.RES];
    public final double[] gunX = new double[Const.RES];
    public final double[] gunY = new double[Const.RES];

    public ShipGeom(ShipShape shape) {
        this.shape = shape;
        this.numPoints = shape.numPoints();
        ptX = new double[numPoints][Const.RES];
        ptY = new double[numPoints][Const.RES];
        for (int i = 0; i < numPoints; i++) {
            rotate(shape.ptX[i], shape.ptY[i], ptX[i], ptY[i]);
        }
        rotate(shape.engineX, shape.engineY, engineX, engineY);
        rotate(shape.mainGunX, shape.mainGunY, gunX, gunY);
    }

    /** BEHAVIOR: out[dir] = (x tcos(dir) - y tsin(dir), x tsin(dir) + y tcos(dir)). */
    static void rotate(int x, int y, double[] outX, double[] outY) {
        for (int d = 0; d < Const.RES; d++) {
            outX[d] = Trig.tcos(d) * x - Trig.tsin(d) * y;
            outY[d] = Trig.tsin(d) * x + Trig.tcos(d) * y;
        }
    }

    private static final ShipGeom DEFAULT = new ShipGeom(ShipShape.defaultShip());

    /** The default triangle ship. */
    public static ShipGeom defaultShip() {
        return DEFAULT;
    }
}
