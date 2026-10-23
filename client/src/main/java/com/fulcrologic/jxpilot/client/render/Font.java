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
package com.fulcrologic.jxpilot.client.render;

/**
 * Baked glyph metrics for printable ASCII ({@link #FIRST}..{@link #LAST}) in an atlas texture.
 * Atlas rectangles are in texels (y down, as baked by STB); offsets are in pixels relative to the
 * pen position on the baseline, with {@code yoff} measured downward (STB convention).
 * Characters outside the range render as {@link #FALLBACK}.
 */
public final class Font {
    public static final char FIRST = 32;
    public static final char LAST = 126;
    public static final char FALLBACK = '?';
    static final int COUNT = LAST - FIRST + 1;

    final int atlasW;
    final int atlasH;
    final float ascent;
    final float descent;
    final float[] x0 = new float[COUNT];
    final float[] y0 = new float[COUNT];
    final float[] x1 = new float[COUNT];
    final float[] y1 = new float[COUNT];
    final float[] xoff = new float[COUNT];
    final float[] yoff = new float[COUNT];
    final float[] advance = new float[COUNT];

    /**
     * @param atlasW  atlas width in texels
     * @param atlasH  atlas height in texels
     * @param ascent  pixels above the baseline (positive)
     * @param descent pixels below the baseline (positive)
     */
    public Font(int atlasW, int atlasH, float ascent, float descent) {
        this.atlasW = atlasW;
        this.atlasH = atlasH;
        this.ascent = ascent;
        this.descent = descent;
    }

    /** Define one glyph (atlas rectangle in texels, offsets and advance in pixels). */
    public void setGlyph(char c, float ax0, float ay0, float ax1, float ay1, float xo, float yo, float adv) {
        int i = c - FIRST;
        x0[i] = ax0;
        y0[i] = ay0;
        x1[i] = ax1;
        y1[i] = ay1;
        xoff[i] = xo;
        yoff[i] = yo;
        advance[i] = adv;
    }

    /** Glyph slot for a character (out-of-range characters map to {@link #FALLBACK}). */
    static int slot(char c) {
        return (c < FIRST || c > LAST ? FALLBACK : c) - FIRST;
    }

    /** Horizontal advance of a character in pixels. */
    public float advance(char c) {
        return advance[slot(c)];
    }

    public float ascent() { return ascent; }

    public float descent() { return descent; }

    /** Line height (ascent + descent). */
    public float lineHeight() { return ascent + descent; }

    public int atlasWidth() { return atlasW; }

    public int atlasHeight() { return atlasH; }
}
