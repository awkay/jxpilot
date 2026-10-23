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
package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;

/**
 * A home base ({@code _} or team digit).
 *
 * @param index     index in {@link WorldMap#bases()} (C order: x-major, y ascending)
 * @param bx        block x
 * @param by        block y (0 = bottom row)
 * @param dir       heading the ship faces when placed, RES units ({@link Dir})
 * @param team      owning team in team play, else {@link Const#TEAM_NOT_SET}
 * @param teamDigit the digit written in the map (0-9), or -1 for {@code _}
 */
public record Base(int index, int bx, int by, int dir, int team, int teamDigit) {
    /** Block centre x in pixels. */
    public double x() { return (bx + 0.5) * Const.BLOCK_SZ; }

    /** Block centre y in pixels. */
    public double y() { return (by + 0.5) * Const.BLOCK_SZ; }

    /** Heading in radians. */
    public double headingRadians() { return Dir.toRadians(dir); }
}
