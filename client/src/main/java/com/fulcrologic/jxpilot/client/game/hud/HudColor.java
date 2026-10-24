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
package com.fulcrologic.jxpilot.client.game.hud;

/** Semantic colours of {@link HudFrame} items; the renderer maps them to its palette (XPilot defaults in comments). */
public final class HudColor {
    private HudColor() {}

    /** {@code hudColor} (XPilot default: colour 2, blue-ish). */
    public static final int HUD = 0;
    /** WHITE. */
    public static final int WHITE = 1;
    /** RED (fuel meter bar, flashing new messages). */
    public static final int RED = 2;
    /** {@code oldMessagesColor}. */
    public static final int OLD_MESSAGE = 3;
    /** BLACK (talk window background). */
    public static final int BLACK = 4;
    /** Score list text (white with a shadow in C). */
    public static final int SCORE = 5;
    /** Score list text of dead/paused/waiting players (C: BLACK on the blue window, grey 12 with blockBitmaps). */
    public static final int SCORE_DIM = 6;
    /** C {@code windowColor} (BLUE): the score list, button bar forms and information windows. */
    public static final int WINDOW = 7;
    /** C {@code buttonColor} (RED): button faces. */
    public static final int BUTTON = 8;
    /** C {@code DRAW_EMPHASIZED} (BLUE): selected text in the talk line and messages, the inverse talk cursor. */
    public static final int EMPHASIZED = 9;
    /**
     * client-ui-look, {@code texturedObjects}: a filled rect of the meter bitmap's full picture, cropped from its left
     * end (C {@code PaintMeter}: the filled part of a meter).
     */
    public static final int METER_FULL = 10;
    /** The meter bitmap's empty picture, cropped to the rect's width from its right end (the meter's empty part). */
    public static final int METER_EMPTY = 11;
}
