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

/** C {@code tcos}/{@code tsin} tables over RES headings, computed with StrictMath so every JVM agrees. */
public final class Trig {
    private Trig() {}

    private static final double[] SIN = new double[Const.RES];

    static {
        for (int i = 0; i < Const.RES; i++) {
            SIN[i] = StrictMath.sin(i * 2.0 * Math.PI / Const.RES);
        }
    }

    /** BEHAVIOR: sine of a RES heading; any int is taken modulo RES (C {@code MOD2}). */
    public static double tsin(int dir) {
        return SIN[dir & (Const.RES - 1)];
    }

    /** BEHAVIOR: cosine of a RES heading, {@code tsin(dir + RES/4)}. */
    public static double tcos(int dir) {
        return SIN[(dir + Const.RES / 4) & (Const.RES - 1)];
    }

    /** C {@code MOD2(x, RES)}. */
    public static int mod(int dir) {
        return dir & (Const.RES - 1);
    }
}
