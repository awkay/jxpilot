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

/** Allocation-free decimal formatting of numbers into a {@code char[]}. */
public final class NumberText {
    private NumberText() {}

    /** Longest output of {@link #formatInt} ("-2147483648"). */
    public static final int MAX_INT_CHARS = 11;

    private static final long[] POW10 = {
        1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L, 100_000_000L, 1_000_000_000L
    };

    /** 2^52: from here on {@code scaled + 0.5} is not exact in a double. */
    static final double EXACT_LIMIT = 4503599627370496.0;

    /** Largest number of decimals {@link #formatFixed} supports. */
    public static final int MAX_DECIMALS = 9;

    /**
     * Write {@code v} in decimal at {@code out[off]}.
     * <p>
     * BEHAVIOR: negative values get a leading '-'; {@code Integer.MIN_VALUE} is exact.
     *
     * @return number of chars written
     */
    public static int formatInt(int v, char[] out, int off) {
        return formatLong(v, out, off);
    }

    /** As {@link #formatInt} for a long (but {@code Long.MIN_VALUE} is not supported). */
    static int formatLong(long v, char[] out, int off) {
        int n = 0;
        if (v < 0) {
            out[off + n++] = '-';
            v = -v;
        }
        int digits = 1;
        for (long t = v; t >= 10; t /= 10) {
            digits++;
        }
        for (int i = digits - 1; i >= 0; i--) {
            out[off + n + i] = (char) ('0' + (v % 10));
            v /= 10;
        }
        return n + digits;
    }

    /**
     * Write {@code v} rounded half-up (away from zero) to {@code decimals} places.
     * <p>
     * BEHAVIOR: {@code decimals} is clamped to 0..{@link #MAX_DECIMALS}; 0 decimals prints no point.
     * BEHAVIOR: NaN prints "NaN"; infinities, and magnitudes whose scaled value {@code |v| * 10^decimals}
     * reaches 2^52 (where adding 0.5 for rounding stops being exact), print "Inf"/"-Inf".
     * BEHAVIOR: a value that rounds to zero prints without a minus sign.
     *
     * @return number of chars written
     */
    public static int formatFixed(double v, int decimals, char[] out, int off) {
        if (Double.isNaN(v)) {
            return put(out, off, 'N', 'a', 'N');
        }
        int d = Math.max(0, Math.min(MAX_DECIMALS, decimals));
        double a = Math.abs(v);
        if (!(a * POW10[d] < EXACT_LIMIT)) {
            if (v < 0) {
                out[off] = '-';
                return 1 + put(out, off + 1, 'I', 'n', 'f');
            }
            return put(out, off, 'I', 'n', 'f');
        }
        long scaled = (long) Math.floor(a * POW10[d] + 0.5);
        int n = 0;
        if (v < 0 && scaled != 0) {
            out[off + n++] = '-';
        }
        n += formatLong(scaled / POW10[d], out, off + n);
        if (d > 0) {
            out[off + n++] = '.';
            long frac = scaled % POW10[d];
            for (int i = d - 1; i >= 0; i--) {
                out[off + n + i] = (char) ('0' + (frac % 10));
                frac /= 10;
            }
            n += d;
        }
        return n;
    }

    private static int put(char[] out, int off, char a, char b, char c) {
        out[off] = a;
        out[off + 1] = b;
        out[off + 2] = c;
        return 3;
    }
}
