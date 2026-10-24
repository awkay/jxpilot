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

import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.client.render.WinScale;

/**
 * Draws a {@link HudDrawList} through {@link ScreenSpace} with the X11 look of the C client:
 * dashed lines are {@code LineOnOffDash} {8, 4} from offset 0 (each line restarting the pattern;
 * the lengths scaled by {@code WINSCALE} at the zoom set with {@link #setScale}, as C's
 * {@code Scale_dashes}), outlines and fills follow X11 pixel coverage, and text sits on its C baseline.
 * Stateless apart from its configuration; allocation-free.
 * <p>
 * The list's coordinates are drawn as given: C applies {@code WINSCALE} to the HUD's geometry anchors
 * and then adds unscaled font offsets, which only the list's builder can do, so a builder for a
 * zoomed view lays the list out in scaled window coordinates ({@link WinScale#of}).
 * <p>
 * List colour {@code c} is drawn in palette colour {@code paletteOf[c]}. Text in a colour with
 * {@code shadowed[c]} is drawn as C's {@code ShadowDrawString}: black one pixel down-right, then
 * the colour one pixel up-left.
 * <p>
 * Order: all rectangles, then all lines, then all text, each in list order (fills under their
 * outlines and text, as the C client's deferred rectangle/segment flushes and later text draws).
 */
public final class HudListPainter {
    /**
     * Palette index per {@code HudColor} of client-net's {@code Hud}: HUD 4 ({@code hudColor}),
     * WHITE 1, RED 3, OLD_MESSAGE 1 ({@code oldMessagesColor}), BLACK 0, SCORE 1 (white), SCORE_DIM 0
     * for 'D'/'P'/'W' players (C {@code paint.c} {@code Paint_score_entry}: black on the blue score window; grey
     * 12 with {@code blockBitmaps}, see {@link #setPaletteIndex}), WINDOW 2 (C {@code windowColor} BLUE) and
     * BUTTON 3 (C {@code buttonColor} RED).
     */
    public static final int[] XPILOT_PALETTE = {
        PaintConfig.HUD_COLOR, Palette.WHITE, Palette.RED, PaintConfig.OLD_MESSAGES_COLOR, Palette.BLACK,
        Palette.WHITE, Palette.BLACK, Palette.BLUE, Palette.RED
    };
    /** Shadowed text colours of {@code HudColor}: only SCORE (index 5), C's {@code ShadowDrawString} white text. */
    public static final boolean[] XPILOT_SHADOW = {false, false, false, false, false, true, false, false, false};

    /** X11's default dash list {4, 4} (a GC whose dashes were never set). */
    static final int X_DASH = 4;

    private final TextWriter text;
    private final Palette palette;
    private final int[] paletteOf;
    private final boolean[] shadowed;
    private double scale = 1;

    /**
     * @param paletteOf palette index for each list colour index (copied)
     * @param shadowed  per list colour index, whether its text is shadowed (copied; shorter arrays mean no shadow)
     */
    public HudListPainter(TextWriter text, Palette palette, int[] paletteOf, boolean[] shadowed) {
        this.text = text;
        this.palette = palette;
        this.paletteOf = paletteOf.clone();
        this.shadowed = new boolean[paletteOf.length];
        System.arraycopy(shadowed, 0, this.shadowed, 0, Math.min(shadowed.length, paletteOf.length));
    }

    /** The painter for client-net's {@code HudColor} indices with the XPilot defaults. */
    public static HudListPainter xpilot(TextWriter text, Palette palette) {
        return new HudListPainter(text, palette, XPILOT_PALETTE, XPILOT_SHADOW);
    }

    /** The zoom used as C's {@code WINSCALE} factor for the dash lengths (1 = unscaled). */
    public void setScale(double zoom) {
        this.scale = zoom;
    }

    /**
     * BEHAVIOR: list colour {@code listColor} draws in palette colour {@code paletteIndex} from now on (live
     * colour options: hudColor, oldMessagesColor, the score list's dim colour).
     */
    public void setPaletteIndex(int listColor, int paletteIndex) {
        paletteOf[listColor] = paletteIndex;
    }

    /** Packed colour of list colour {@code c}. */
    int rgba(int c) {
        return palette.color(paletteOf[c]);
    }

    /**
     * Draw the whole list.
     * <p>
     * BEHAVIOR: filled rects cover pixels {@code [x, x + w) x [y, y + h)} (nothing unless both sides are
     * positive); outlines run through the pixel centres of columns x..x+w and rows y..y+h (w + 1 pixels).
     * BEHAVIOR: solid lines join the pixel centres of their ends; dashed lines are dashed
     * {@code WINSCALE(8)} on / {@code WINSCALE(4)} off from offset 0, in logical pixels; DASHED_X lines 4 on / 4
     * off, unscaled (X11's default dashes). Lines are 1 logical pixel wide.
     * BEHAVIOR: text draws {@code textLength} chars with its pen at {@code textX} and baseline at
     * {@code textY}; shadowed colours draw black at (+1, +1) first and the colour at (-1, -1).
     */
    public void paint(VertexBatch b, ScreenSpace ss, HudDrawList list) {
        b.setLineWidth(VertexBatch.DEFAULT_LINE_WIDTH);
        for (int i = 0; i < list.rectCount(); i++) {
            int c = rgba(list.rectColor(i));
            if (list.rectFilled(i)) {
                HudPainter.fill(b, ss, list.rectX(i), list.rectY(i), list.rectW(i), list.rectH(i), c);
            } else {
                HudPainter.rect(b, ss, list.rectX(i), list.rectY(i), list.rectW(i), list.rectH(i), c);
            }
        }
        for (int i = 0; i < list.lineCount(); i++) {
            int c = rgba(list.lineColor(i));
            if (list.lineStyle(i) == HudDrawList.DASHED) {
                HudPainter.dash(b, ss, list.lineX0(i), list.lineY0(i), list.lineX1(i), list.lineY1(i),
                    WinScale.of(PaintConfig.DASH_ON, scale), WinScale.of(PaintConfig.DASH_OFF, scale), c);
            } else if (list.lineStyle(i) == HudDrawList.DASHED_X) {
                HudPainter.dash(b, ss, list.lineX0(i), list.lineY0(i), list.lineX1(i), list.lineY1(i), X_DASH, X_DASH, c);
            } else {
                HudPainter.seg(b, ss, list.lineX0(i), list.lineY0(i), list.lineX1(i), list.lineY1(i), c);
            }
        }
        int black = palette.color(Palette.BLACK);
        for (int i = 0; i < list.textCount(); i++) {
            int col = list.textColor(i);
            CharSequence s = list.text(i);
            int n = list.textLength(i);
            float x = list.textX(i);
            float y = list.textY(i);
            if (shadowed[col]) {
                text.draw(b, s, 0, n, ss.edgeX(x + 1), ss.baseline(y + 1), black);
                text.draw(b, s, 0, n, ss.edgeX(x - 1), ss.baseline(y - 1), rgba(col));
            } else {
                text.draw(b, s, 0, n, ss.edgeX(x), ss.baseline(y), rgba(col));
            }
        }
    }
}
