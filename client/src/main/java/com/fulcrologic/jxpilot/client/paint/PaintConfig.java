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
package com.fulcrologic.jxpilot.client.paint;

/**
 * Look-and-feel constants and option defaults of the XPilot 4.5.5 client ({@code default.c},
 * {@code paint.h}, {@code xinit.c}, {@code common/draw.h}, {@code common/item.h}). Sizes are in
 * (logical) pixels unless they are world lengths, as in C.
 */
public final class PaintConfig {
    private PaintConfig() {}

    /** {@code shotSize}: own/enemy shot square side. */
    public static final int SHOT_SIZE = 3;
    /** {@code teamShotSize} (C's non-bitmap team shot actually uses {@code shotSize / 2} arms). */
    public static final int TEAM_SHOT_SIZE = 2;
    /** {@code sparkSize}: spark/debris square side. */
    public static final int SPARK_SIZE = 2;
    /** {@code sparkColors} "5,6,7,3": palette index per debris colour level, coolest first. */
    public static final int[] SPARK_COLORS = {5, 6, 7, 3};
    /** {@code dashes} {8, 4}: on/off lengths of ship, shield and connector dashes. */
    public static final int DASH_ON = 8;
    public static final int DASH_OFF = 4;
    /** {@code cdashes} {3, 9}: tractor/pressor beam dashes. */
    public static final int CDASH_ON = 3;
    public static final int CDASH_OFF = 9;
    /** {@code DASHES_LENGTH}: the ship dash offset rotates through this many pixels. */
    public static final int DASHES_LENGTH = 12;
    /** Missile line width ({@code Gui_paint_missiles_begin}). */
    public static final int MISSILE_WIDTH = 4;
    /** Laser line width ({@code Gui_paint_lasers_begin}). */
    public static final int LASER_WIDTH = 3;
    /** {@code ITEM_SIZE}. */
    public static final int ITEM_SIZE = 16;
    /** {@code ITEM_TRIANGLE_SIZE} = 5 * ITEM_SIZE / 7 + 1. */
    public static final int ITEM_TRIANGLE_SIZE = 5 * ITEM_SIZE / 7 + 1;
    /** {@code DSIZE}: half-size of the radar checkpoint diamond. */
    public static final int RADAR_DIAMOND = 4;
    /** {@code hudColor} default (palette index). */
    public static final int HUD_COLOR = 4;
    /** {@code hudLockColor} default. */
    public static final int HUD_LOCK_COLOR = 4;
    /** {@code oldMessagesColor} default. */
    public static final int OLD_MESSAGES_COLOR = 1;
    /** Radar colour of teammates when the display has more than 4 colours. */
    public static final int RADAR_FRIEND_COLOR = 4;
}
