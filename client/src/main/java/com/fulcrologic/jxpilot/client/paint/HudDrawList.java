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
 * A read-only HUD draw list in C window coordinates (logical pixels, origin top-left, y down):
 * line segments, rectangles and text runs, each with a list colour index. The accessors match
 * {@code client.game.hud.HudFrame}, which can implement this interface as it is.
 * <p>
 * Lines join two points ({@link #SOLID} or {@link #DASHED} X11 {@code LineOnOffDash}); rectangles
 * are X11 {@code XDrawRectangle} outlines or {@code XFillRectangle} fills of {@code w x h}; text
 * positions are the left end of the baseline. Colours are list indices that {@link HudListPainter}
 * maps to palette indices.
 */
public interface HudDrawList {
    /** Solid line style (same value as {@code HudFrame.SOLID}). */
    int SOLID = 0;
    /** Dashed line style (same value as {@code HudFrame.DASHED}). */
    int DASHED = 1;

    int lineCount();

    float lineX0(int i);

    float lineY0(int i);

    float lineX1(int i);

    float lineY1(int i);

    /** {@link #SOLID} or {@link #DASHED}. */
    int lineStyle(int i);

    int lineColor(int i);

    int rectCount();

    float rectX(int i);

    float rectY(int i);

    float rectW(int i);

    float rectH(int i);

    /** True for a fill, false for an outline. */
    boolean rectFilled(int i);

    int rectColor(int i);

    int textCount();

    /** Text of run {@code i}; only the first {@link #textLength} chars are drawn. */
    CharSequence text(int i);

    /** Characters of run {@code i} to draw. */
    int textLength(int i);

    /** Left end of the baseline. */
    float textX(int i);

    /** Baseline row. */
    float textY(int i);

    int textColor(int i);

    /**
     * The font of run {@code i}: an index into the {@link HudListPainter}'s text writers (the client HUD's
     * {@code FontRole} ordinal); 0, the game font, by default.
     */
    default int textFont(int i) {
        return 0;
    }
}
