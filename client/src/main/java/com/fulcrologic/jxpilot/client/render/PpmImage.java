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
 * A binary PPM (P6) picture, the format of the C client's block bitmaps ({@code texturedObjects},
 * {@code gfx2d.c} {@code Picture_load}): "P6", width, height and a maximum value of 255 (decimal, separated by
 * blanks, '#' comments allowed), one blank, then RGB bytes row by row.
 * <p>
 * Pure black pixels are transparent (alpha 0), as C's block bitmap masks make them
 * ({@code Block_bitmap_set_pixel}: mask bit = colour != 0); every other pixel is opaque. Pixels are packed like
 * {@link Palette}, row 0 at the top. Parsing allocates; it runs when the bitmaps are first needed.
 */
public final class PpmImage {
    private PpmImage() {}

    /**
     * Parse PPM bytes.
     * <p>
     * BEHAVIOR: returns {width, height} in {@code size} and the pixels; black is transparent.
     * BEHAVIOR: throws IllegalArgumentException for another magic than "P6", a maximum value other than 255
     * (C refuses those), non-positive sizes, a header without a blank after the maximum, or too few pixel bytes.
     */
    public static int[] parse(byte[] data, int[] size) {
        int[] pos = {0};
        if (data.length < 2 || data[0] != 'P' || data[1] != '6') {
            throw new IllegalArgumentException("not a binary PPM (P6)");
        }
        pos[0] = 2;
        int w = number(data, pos);
        int h = number(data, pos);
        int max = number(data, pos);
        if (max != 255 || w <= 0 || h <= 0) {
            throw new IllegalArgumentException("unsupported PPM header: " + w + "x" + h + " max " + max);
        }
        if (pos[0] >= data.length || !blank(data[pos[0]])) {
            throw new IllegalArgumentException("PPM header not followed by a blank");
        }
        int p = pos[0] + 1;
        if (data.length - p < 3L * w * h) {
            throw new IllegalArgumentException("PPM has " + (data.length - p) + " pixel bytes, needs " + 3L * w * h);
        }
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int r = data[p++] & 0xFF;
            int g = data[p++] & 0xFF;
            int b = data[p++] & 0xFF;
            px[i] = (r | g | b) == 0 ? 0 : Palette.pack(r, g, b, 255);
        }
        size[0] = w;
        size[1] = h;
        return px;
    }

    /** Skip blanks and '#' comments, then read a decimal number (-1 when there is none). */
    private static int number(byte[] d, int[] pos) {
        int i = pos[0];
        while (i < d.length) {
            if (d[i] == '#') {
                while (i < d.length && d[i] != '\n') {
                    i++;
                }
            } else if (blank(d[i])) {
                i++;
            } else {
                break;
            }
        }
        int v = -1;
        while (i < d.length && d[i] >= '0' && d[i] <= '9') {
            v = (v < 0 ? 0 : v * 10) + (d[i] - '0');
            if (v > 1 << 20) {
                throw new IllegalArgumentException("PPM number too large");
            }
            i++;
        }
        pos[0] = i;
        return v;
    }

    private static boolean blank(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }
}
