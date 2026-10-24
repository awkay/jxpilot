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

import com.fulcrologic.jxpilot.client.game.CharBuf;

/**
 * Plain draw list produced by {@link Hud} for one frame: line segments, rectangles and text runs in
 * window pixels (origin top-left, y down, unscaled, as X11 XPilot), each with a {@link HudColor}.
 * Text positions are the left end of the baseline; each run has the {@link FontRole} it is drawn and was
 * measured with. Preallocated; filling it allocates nothing.
 * The renderer walks the three lists and draws them.
 */
public final class HudFrame {
    /** Solid line. */
    public static final int SOLID = 0;
    /** Dashed line (XPilot's HUD frame uses LineOnOffDash). */
    public static final int DASHED = 1;

    private final int lineCap;
    private final int rectCap;
    private final int textCap;

    private int lines;
    private final float[] lx0;
    private final float[] ly0;
    private final float[] lx1;
    private final float[] ly1;
    private final int[] lineStyle;
    private final int[] lineColor;

    private int rects;
    private final float[] rx;
    private final float[] ry;
    private final float[] rw;
    private final float[] rh;
    private final boolean[] rectFilled;
    private final int[] rectColor;

    private int texts;
    private final CharBuf[] text;
    private final int[] textLen;
    private final float[] tx;
    private final float[] ty;
    private final int[] textColor;
    private final FontRole[] textRole;

    public HudFrame(int lineCap, int rectCap, int textCap, int textLen) {
        this.lineCap = lineCap;
        this.rectCap = rectCap;
        this.textCap = textCap;
        lx0 = new float[lineCap];
        ly0 = new float[lineCap];
        lx1 = new float[lineCap];
        ly1 = new float[lineCap];
        lineStyle = new int[lineCap];
        lineColor = new int[lineCap];
        rx = new float[rectCap];
        ry = new float[rectCap];
        rw = new float[rectCap];
        rh = new float[rectCap];
        rectFilled = new boolean[rectCap];
        rectColor = new int[rectCap];
        text = new CharBuf[textCap];
        this.textLen = new int[textCap];
        tx = new float[textCap];
        ty = new float[textCap];
        textColor = new int[textCap];
        textRole = new FontRole[textCap];
        for (int i = 0; i < textCap; i++) {
            text[i] = new CharBuf(textLen);
        }
    }

    /** Capacities that fit the full HUD, messages, talk line and a full scoreboard. */
    public HudFrame() {
        this(128, 64, 320, 256);
    }

    /** BEHAVIOR: empties all three lists. */
    public void clear() {
        lines = 0;
        rects = 0;
        texts = 0;
    }

    /** BEHAVIOR: appends a segment; dropped when full. */
    public void line(float x0, float y0, float x1, float y1, int style, int color) {
        if (lines >= lineCap) {
            return;
        }
        int i = lines++;
        lx0[i] = x0;
        ly0[i] = y0;
        lx1[i] = x1;
        ly1[i] = y1;
        lineStyle[i] = style;
        lineColor[i] = color;
    }

    /** BEHAVIOR: appends a rectangle (outline or filled); dropped when full. */
    public void rect(float x, float y, float w, float h, boolean filled, int color) {
        if (rects >= rectCap) {
            return;
        }
        int i = rects++;
        rx[i] = x;
        ry[i] = y;
        rw[i] = w;
        rh[i] = h;
        rectFilled[i] = filled;
        rectColor[i] = color;
    }

    /** A {@link FontRole#GAME} text run ({@link #text(float, float, FontRole, int)}). */
    public CharBuf text(float x, float y, int color) {
        return text(x, y, FontRole.GAME, color);
    }

    /**
     * Append a text run in {@code role}'s font and return its buffer for the caller to fill (or null when full).
     * {@link #setTextLength} limits how many chars are drawn (typing effect); default all.
     * <p>
     * BEHAVIOR: records position, colour and role; dropped (null) when full.
     */
    public CharBuf text(float x, float y, FontRole role, int color) {
        if (texts >= textCap) {
            return null;
        }
        int i = texts++;
        tx[i] = x;
        ty[i] = y;
        textColor[i] = color;
        textRole[i] = role;
        textLen[i] = -1;
        return text[i].clear();
    }

    /** Limit the drawn length of the last text run. */
    public void setTextLength(int len) {
        textLen[texts - 1] = len;
    }

    public int lineCount() { return lines; }

    public float lineX0(int i) { return lx0[i]; }

    public float lineY0(int i) { return ly0[i]; }

    public float lineX1(int i) { return lx1[i]; }

    public float lineY1(int i) { return ly1[i]; }

    public int lineStyle(int i) { return lineStyle[i]; }

    public int lineColor(int i) { return lineColor[i]; }

    public int rectCount() { return rects; }

    public float rectX(int i) { return rx[i]; }

    public float rectY(int i) { return ry[i]; }

    public float rectW(int i) { return rw[i]; }

    public float rectH(int i) { return rh[i]; }

    public boolean rectFilled(int i) { return rectFilled[i]; }

    public int rectColor(int i) { return rectColor[i]; }

    public int textCount() { return texts; }

    public CharBuf text(int i) { return text[i]; }

    /** Characters to draw of text i. */
    public int textLength(int i) { return textLen[i] < 0 ? text[i].length() : Math.min(textLen[i], text[i].length()); }

    public float textX(int i) { return tx[i]; }

    public float textY(int i) { return ty[i]; }

    public int textColor(int i) { return textColor[i]; }

    /** The font role of text i. */
    public FontRole textRole(int i) { return textRole[i]; }
}
