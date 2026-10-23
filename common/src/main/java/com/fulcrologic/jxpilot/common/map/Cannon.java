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
 * A cannon. Map chars: {@code r} faces up (sits on the block floor), {@code c} faces down,
 * {@code f} faces right (mounted on left wall), {@code d} faces left (mounted on right wall).
 * The solid part is a triangle with its base on the mounting side and apex 1/3 block into the block.
 *
 * @param dir  facing in RES units ({@link Dir})
 * @param x    firing position in pixels (C {@code pix_pos})
 * @param y    firing position in pixels
 * @param team closest team when {@code teamCannons} and team play, else {@link Const#TEAM_NOT_SET}
 */
public record Cannon(int index, int bx, int by, int dir, double x, double y, int team) {
    public double headingRadians() { return Dir.toRadians(dir); }
}
