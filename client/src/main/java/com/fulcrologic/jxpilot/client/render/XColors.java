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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * X11 colour specifications as {@code XParseColor} reads them (the C client's {@code color0..15},
 * {@code black}/{@code white}/{@code blue}/{@code red} resources and XPM texture colours):
 * <ul>
 *   <li>{@code #RGB}, {@code #RRGGBB}, {@code #RRRGGGBBB}, {@code #RRRRGGGGBBBB}: each component's digits are
 *   the most significant bits of a 16-bit value ({@code #fa0} is {@code #f000a0000000}, not scaled);</li>
 *   <li>{@code rgb:r/g/b}, each 1 to 4 hex digits, scaled to the full range ({@code rgb:f/a/0} is
 *   {@code #ffffaaaa0000});</li>
 *   <li>{@code rgbi:r/g/b}, each a number from 0.0 to 1.0;</li>
 *   <li>a colour name from X.Org's {@code rgb.txt} (bundled as {@code /colors/rgb.txt}), ignoring case and
 *   blanks, as the X server's colour database does.</li>
 * </ul>
 * A TrueColor display keeps the top 8 bits of each 16-bit component, so that is the packed colour
 * ({@link Palette#pack}, opaque). Parsing allocates; it runs when options change, never per frame.
 */
public final class XColors {
    /** {@link #parse} result for a specification it cannot read. */
    public static final long INVALID = -1;
    /** Classpath resource of the colour names. */
    public static final String NAMES = "/colors/rgb.txt";

    private static volatile Map<String, Integer> names;

    private XColors() {}

    /**
     * Parse a colour specification.
     * <p>
     * BEHAVIOR: returns the opaque packed colour (0 .. 0xFFFFFFFF as a long), or {@link #INVALID} for null,
     * blank or unreadable text; leading and trailing blanks are ignored.
     * BEHAVIOR: {@code #} needs 3, 6, 9 or 12 hex digits; {@code rgb:} three '/'-separated fields of 1-4 hex
     * digits; {@code rgbi:} three numbers in [0, 1]; anything else is looked up by name.
     */
    public static long parse(String spec) {
        if (spec == null) {
            return INVALID;
        }
        String s = spec.strip();
        if (s.isEmpty()) {
            return INVALID;
        }
        if (s.charAt(0) == '#') {
            return hash(s.substring(1));
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("rgb:")) {
            return rgb(s.substring(4));
        }
        if (lower.startsWith("rgbi:")) {
            return rgbi(s.substring(5));
        }
        Integer c = names().get(key(s));
        return c == null ? INVALID : c & 0xFFFFFFFFL;
    }

    /** BEHAVIOR: {@code #} digits: 3/6/9/12 hex digits, each third the high bits of a 16-bit component. */
    static long hash(String digits) {
        int n = digits.length();
        if (n == 0 || n % 3 != 0 || n > 12 || !hex(digits)) {
            return INVALID;
        }
        int per = n / 3;
        int[] c = new int[3];
        for (int i = 0; i < 3; i++) {
            int v = Integer.parseInt(digits.substring(i * per, (i + 1) * per), 16);
            c[i] = (v << (16 - 4 * per)) >> 8;
        }
        return Palette.pack(c[0], c[1], c[2], 255) & 0xFFFFFFFFL;
    }

    /** BEHAVIOR: {@code rgb:} fields of 1-4 hex digits, each scaled: {@code v * 65535 / (16^n - 1)}. */
    static long rgb(String fields) {
        String[] f = fields.split("/", -1);
        if (f.length != 3) {
            return INVALID;
        }
        int[] c = new int[3];
        for (int i = 0; i < 3; i++) {
            int n = f[i].length();
            if (n < 1 || n > 4 || !hex(f[i])) {
                return INVALID;
            }
            long v = Long.parseLong(f[i], 16);
            long max = (1L << (4 * n)) - 1;
            c[i] = (int) (v * 0xFFFF / max) >> 8;
        }
        return Palette.pack(c[0], c[1], c[2], 255) & 0xFFFFFFFFL;
    }

    /** BEHAVIOR: {@code rgbi:} numbers in [0, 1], each {@code (int)(v * 65535 + 0.5)} as a 16-bit component. */
    static long rgbi(String fields) {
        String[] f = fields.split("/", -1);
        if (f.length != 3) {
            return INVALID;
        }
        int[] c = new int[3];
        for (int i = 0; i < 3; i++) {
            double v;
            try {
                v = Double.parseDouble(f[i]);
            } catch (NumberFormatException e) {
                return INVALID;
            }
            if (!(v >= 0 && v <= 1)) {
                return INVALID;
            }
            c[i] = (int) (v * 0xFFFF + 0.5) >> 8;
        }
        return Palette.pack(c[0], c[1], c[2], 255) & 0xFFFFFFFFL;
    }

    private static boolean hex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** BEHAVIOR: a name's lookup key: lower case without blanks ("Ghost White" and "ghostwhite" match). */
    static String key(String name) {
        StringBuilder b = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch != ' ' && ch != '\t') {
                b.append(Character.toLowerCase(ch));
            }
        }
        return b.toString();
    }

    private static Map<String, Integer> names() {
        Map<String, Integer> m = names;
        if (m == null) {
            try (InputStream in = XColors.class.getResourceAsStream(NAMES)) {
                if (in == null) {
                    throw new IllegalStateException("missing resource " + NAMES);
                }
                m = parseNames(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + NAMES, e);
            }
            names = m;
        }
        return m;
    }

    /**
     * The colour table of an {@code rgb.txt} text.
     * <p>
     * BEHAVIOR: each line {@code R G B name} (decimal components, the name may contain blanks) maps
     * {@link #key}(name) to the opaque colour; lines starting with '!' or '#', blank lines and lines that do not
     * start with three numbers are skipped; the first line for a key wins.
     */
    static Map<String, Integer> parseNames(String text) {
        Map<String, Integer> m = new HashMap<>();
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.charAt(0) == '!' || t.charAt(0) == '#') {
                continue;
            }
            String[] f = t.split("\\s+", 4);
            if (f.length < 4) {
                continue;
            }
            try {
                int r = Integer.parseInt(f[0]);
                int g = Integer.parseInt(f[1]);
                int b = Integer.parseInt(f[2]);
                m.putIfAbsent(key(f[3]), Palette.pack(r, g, b, 255));
            } catch (NumberFormatException e) {
                // not a colour line
            }
        }
        return m;
    }
}
