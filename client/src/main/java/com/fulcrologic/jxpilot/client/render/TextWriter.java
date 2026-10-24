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
 * Lays out text as textured quads in a {@link VertexBatch} using a {@link Font}. Coordinates are
 * screen pixels, y up; {@code (x, baseline)} is the pen start. Glyph quads are snapped to whole
 * pixels so text stays crisp. Numbers are written without creating Strings.
 * <p>
 * Not thread-safe (holds a scratch buffer for number formatting).
 */
public final class TextWriter {
    private final Font font;
    private final char[] scratch = new char[32];

    public TextWriter(Font font) {
        this.font = font;
    }

    public Font font() { return font; }

    /**
     * Draw {@code len} chars of {@code s} from {@code off}.
     * <p>
     * BEHAVIOR: each glyph's quad spans x {@code [round(pen + xoff), +glyphWidth]} and y from
     * {@code round(baseline - yoff)} down by the glyph height; spaces (empty glyphs) emit no quad.
     *
     * @return pen x after the last char
     */
    public float draw(VertexBatch b, CharSequence s, int off, int len, float x, float baseline, int rgba) {
        float pen = x;
        for (int i = off; i < off + len; i++) {
            pen = glyph(b, s.charAt(i), pen, baseline, rgba);
        }
        return pen;
    }

    /** Draw a whole CharSequence (no allocation for Strings). */
    public float draw(VertexBatch b, CharSequence s, float x, float baseline, int rgba) {
        return draw(b, s, 0, s.length(), x, baseline, rgba);
    }

    /** Draw chars from an array. */
    public float draw(VertexBatch b, char[] s, int off, int len, float x, float baseline, int rgba) {
        float pen = x;
        for (int i = off; i < off + len; i++) {
            pen = glyph(b, s[i], pen, baseline, rgba);
        }
        return pen;
    }

    /** Draw one char. */
    public float draw(VertexBatch b, char c, float x, float baseline, int rgba) {
        return glyph(b, c, x, baseline, rgba);
    }

    /**
     * Draw one char in two colours, cut along the horizontal screen line {@code splitY}: {@code below} under it,
     * {@code above} over it (a pixel-exact stand-in for C's GXxor text over a bar).
     * <p>
     * BEHAVIOR: the quad is placed as {@link #draw(VertexBatch, char, float, float, int)}; {@code splitY} is
     * rounded to a whole pixel, and the texture is cut at the same line so no texel moves.
     * BEHAVIOR: a line at or under the glyph's bottom draws all of it {@code above}; at or over its top, all of
     * it {@code below}; otherwise two quads, the lower one first.
     * BEHAVIOR: an empty glyph draws nothing.
     *
     * @return pen x after the char
     */
    public float drawSplit(VertexBatch b, char c, float x, float baseline, float splitY, int below, int above) {
        int g = Font.slot(c);
        float gw = font.x1[g] - font.x0[g];
        float gh = font.y1[g] - font.y0[g];
        if (gw > 0 && gh > 0) {
            float left = Math.round(x + font.xoff[g]);
            float top = Math.round(baseline - font.yoff[g]);
            float bottom = top - gh;
            float cut = Math.round(splitY);
            float u0 = font.x0[g] / font.atlasW;
            float u1 = font.x1[g] / font.atlasW;
            float vBottom = font.y1[g] / font.atlasH;
            float vTop = font.y0[g] / font.atlasH;
            if (cut <= bottom || cut >= top) {
                b.texRect(left, bottom, left + gw, top, u0, vBottom, u1, vTop, cut >= top ? below : above);
            } else {
                float vCut = vBottom + (vTop - vBottom) * ((cut - bottom) / gh);
                b.texRect(left, bottom, left + gw, cut, u0, vBottom, u1, vCut, below);
                b.texRect(left, cut, left + gw, top, u0, vCut, u1, vTop, above);
            }
        }
        return x + font.advance[g];
    }

    /** Draw an int in decimal. */
    public float drawInt(VertexBatch b, int v, float x, float baseline, int rgba) {
        int n = NumberText.formatInt(v, scratch, 0);
        return draw(b, scratch, 0, n, x, baseline, rgba);
    }

    /** Draw a number with a fixed number of decimals (see {@link NumberText#formatFixed}). */
    public float drawFixed(VertexBatch b, double v, int decimals, float x, float baseline, int rgba) {
        int n = NumberText.formatFixed(v, decimals, scratch, 0);
        return draw(b, scratch, 0, n, x, baseline, rgba);
    }

    /** Advance width of a CharSequence in pixels. */
    public float width(CharSequence s) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += font.advance(s.charAt(i));
        }
        return w;
    }

    /** Advance width of one char. */
    public float width(char c) {
        return font.advance(c);
    }

    /** Advance width of an int as {@link #drawInt} would draw it. */
    public float widthInt(int v) {
        int n = NumberText.formatInt(v, scratch, 0);
        float w = 0;
        for (int i = 0; i < n; i++) {
            w += font.advance(scratch[i]);
        }
        return w;
    }

    /** Advance width of a number as {@link #drawFixed} would draw it. */
    public float widthFixed(double v, int decimals) {
        int n = NumberText.formatFixed(v, decimals, scratch, 0);
        float w = 0;
        for (int i = 0; i < n; i++) {
            w += font.advance(scratch[i]);
        }
        return w;
    }

    private float glyph(VertexBatch b, char c, float pen, float baseline, int rgba) {
        int g = Font.slot(c);
        float gw = font.x1[g] - font.x0[g];
        float gh = font.y1[g] - font.y0[g];
        if (gw > 0 && gh > 0) {
            float left = Math.round(pen + font.xoff[g]);
            float top = Math.round(baseline - font.yoff[g]);
            b.texRect(left, top - gh, left + gw, top,
                font.x0[g] / font.atlasW, font.y1[g] / font.atlasH,
                font.x1[g] / font.atlasW, font.y0[g] / font.atlasH, rgba);
        }
        return pen + font.advance[g];
    }
}
