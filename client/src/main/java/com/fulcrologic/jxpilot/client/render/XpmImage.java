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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An XPM (X PixMap, version 3) image, the format of the C client's textures ({@code wallTextureFile},
 * {@code decorTextureFile}, {@code ballTextureFile}; {@code xpmread.c}): a C array of strings holding
 * "width height ncolors chars_per_pixel", the colour table and the pixel rows.
 * <p>
 * Colours are read like {@code xpmread.c}: of a colour line's keys the colour visual's ({@code c}) is used, else
 * the grey ones ({@code g}, {@code g4}), else mono ({@code m}); values are X colour specifications
 * ({@link XColors}) and {@code None} is transparent. Pixels are packed like {@link Palette}, row 0 at the top.
 * Parsing allocates; it runs when a texture option changes.
 */
public final class XpmImage {
    public final int width;
    public final int height;
    /** {@code width * height} packed colours, row-major, top row first. */
    public final int[] pixels;

    public XpmImage(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    /**
     * Parse XPM text.
     * <p>
     * BEHAVIOR: after the "/* XPM *&#47;" header (required, as {@code xpmread.c}), the quoted strings outside
     * comments are, in order, the values line, {@code ncolors} colour lines
     * and {@code height} pixel rows of {@code width * cpp} characters; text outside strings is ignored.
     * BEHAVIOR: throws IllegalArgumentException (with the reason) for a missing or bad values line, non-positive
     * sizes, too few lines, a colour that does not parse, a short row or a pixel of an undefined colour.
     */
    public static XpmImage parse(String text) {
        int header = text.indexOf("/* XPM */");
        if (header < 0) {
            throw new IllegalArgumentException("not an XPM: no \"/* XPM */\" header");
        }
        List<String> s = strings(text.substring(header + 9));
        if (s.isEmpty()) {
            throw new IllegalArgumentException("not an XPM: no strings");
        }
        String[] v = s.get(0).trim().split("\\s+");
        int w;
        int h;
        int ncolors;
        int cpp;
        try {
            w = Integer.parseInt(v[0]);
            h = Integer.parseInt(v[1]);
            ncolors = Integer.parseInt(v[2]);
            cpp = Integer.parseInt(v[3]);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad XPM values line: \"" + s.get(0) + "\"");
        }
        if (w <= 0 || h <= 0 || ncolors <= 0 || cpp <= 0) {
            throw new IllegalArgumentException("bad XPM size: \"" + s.get(0) + "\"");
        }
        if (s.size() < 1 + ncolors + h) {
            throw new IllegalArgumentException("XPM has " + (s.size() - 1) + " lines, needs " + (ncolors + h));
        }
        Map<String, Integer> colors = new HashMap<>();
        for (int i = 0; i < ncolors; i++) {
            String line = s.get(1 + i);
            if (line.length() < cpp) {
                throw new IllegalArgumentException("short XPM colour line: \"" + line + "\"");
            }
            colors.put(line.substring(0, cpp), color(line.substring(cpp)));
        }
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            String row = s.get(1 + ncolors + y);
            if (row.length() < w * cpp) {
                throw new IllegalArgumentException("short XPM row " + y);
            }
            for (int x = 0; x < w; x++) {
                Integer c = colors.get(row.substring(x * cpp, (x + 1) * cpp));
                if (c == null) {
                    throw new IllegalArgumentException("undefined XPM colour \"" + row.substring(x * cpp, (x + 1) * cpp)
                        + "\" in row " + y);
                }
                px[y * w + x] = c;
            }
        }
        return new XpmImage(w, h, px);
    }

    /**
     * BEHAVIOR: the colour of a colour line's key/value part: the {@code c} value, else {@code g}, {@code g4},
     * then {@code m}; a value may span several words up to the next key; {@code None} is transparent (0).
     */
    static int color(String spec) {
        String[] t = spec.trim().split("\\s+");
        Map<String, String> byKey = new HashMap<>();
        String key = null;
        StringBuilder val = new StringBuilder();
        for (String tok : t) {
            if (KEYS.contains(tok) && (key == null || !val.isEmpty())) {
                if (key != null) {
                    byKey.put(key, val.toString());
                }
                key = tok;
                val.setLength(0);
            } else if (key != null) {
                if (!val.isEmpty()) {
                    val.append(' ');
                }
                val.append(tok);
            }
        }
        if (key != null && !val.isEmpty()) {
            byKey.put(key, val.toString());
        }
        for (String k : PREFERENCE) {
            String c = byKey.get(k);
            if (c != null) {
                if (c.toLowerCase(Locale.ROOT).equals("none")) {
                    return 0;
                }
                long p = XColors.parse(c);
                if (p == XColors.INVALID) {
                    throw new IllegalArgumentException("bad XPM colour \"" + c + "\"");
                }
                return (int) p;
            }
        }
        throw new IllegalArgumentException("XPM colour line without a colour: \"" + spec + "\"");
    }

    private static final List<String> KEYS = List.of("c", "m", "s", "g", "g4");
    private static final List<String> PREFERENCE = List.of("c", "g", "g4", "m");

    /** BEHAVIOR: the double-quoted strings of a C source text, in order, skipping {@code /* ... *&#47;} comments. */
    static List<String> strings(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '"') {
                int end = text.indexOf('"', i + 1);
                if (end < 0) {
                    break;
                }
                out.add(text.substring(i + 1, end));
                i = end + 1;
            } else {
                i++;
            }
        }
        return out;
    }
}
