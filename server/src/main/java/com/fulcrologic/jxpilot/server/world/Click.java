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

/** Sub-pixel fixed point positions ({@code src/server/click.h}): 64 clicks per pixel. */
public final class Click {
    private Click() {}

    public static final int SHIFT = 6;
    public static final int CLICK = 1 << SHIFT;
    public static final int BLOCK_CLICKS = Const.BLOCK_SZ << SHIFT;

    /** C {@code FLOAT_TO_CLICK}: truncates toward zero. */
    public static int fromPixels(double px) {
        return (int) (px * CLICK);
    }

    /** C {@code CLICK_TO_PIXEL}: arithmetic shift (floor). */
    public static int toPixel(int c) {
        return c >> SHIFT;
    }

    /** C {@code CLICK_TO_FLOAT}. */
    public static double toFloat(int c) {
        return c * (1.0 / CLICK);
    }

    /** C {@code DOUBLE_TO_INT}: rounds half away from zero. */
    public static int roundHalfAway(double d) {
        return d < 0 ? -(int) (0.5 - d) : (int) (d + 0.5);
    }
}
