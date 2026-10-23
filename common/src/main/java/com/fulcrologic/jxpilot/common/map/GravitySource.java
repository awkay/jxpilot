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

/**
 * A local gravity block. {@code type} is one of the {@link BlockType} *_GRAV codes:
 * {@code +} POS_GRAV (repels, force -2.7), {@code -} NEG_GRAV (attracts, +2.7),
 * {@code >} CWISE_GRAV (+2.7), {@code <} ACWISE_GRAV (-2.7),
 * {@code i} UP_GRAV (+2.7), {@code m} DOWN_GRAV (-2.7), {@code k} RIGHT_GRAV (+2.7), {@code j} LEFT_GRAV (-2.7).
 * Its effect is baked into {@link WorldMap#gravityX(int, int)}/{@link WorldMap#gravityY(int, int)}.
 */
public record GravitySource(int index, int bx, int by, byte type, double force) {
}
