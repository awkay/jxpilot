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
package com.fulcrologic.jxpilot.client.game;

/**
 * A fixed-capacity, reusable character buffer for HUD and scoreboard text that changes every frame.
 * Appending never allocates; characters past the capacity are dropped. Implements {@link CharSequence}
 * so renderers can draw it directly (do not call {@link #toString()} on hot paths).
 */
public final class CharBuf implements CharSequence {
    private final char[] chars;
    private int len;

    public CharBuf(int capacity) {
        chars = new char[capacity];
    }

    /** The backing array; valid characters are {@code [0, length())}. */
    public char[] array() {
        return chars;
    }

    public int capacity() {
        return chars.length;
    }

    @Override
    public int length() {
        return len;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index >= len) {
            throw new IndexOutOfBoundsException(index);
        }
        return chars[index];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new String(chars, start, end - start);
    }

    @Override
    public String toString() {
        return new String(chars, 0, len);
    }

    /** BEHAVIOR: empties the buffer. */
    public CharBuf clear() {
        len = 0;
        return this;
    }

    /** BEHAVIOR: truncates to {@code n} characters when longer; no-op otherwise. */
    public void truncate(int n) {
        if (n >= 0 && n < len) {
            len = n;
        }
    }

    /** BEHAVIOR: sets the length to {@code n} clamped to 0..capacity (contents beyond the old length are whatever the array holds). */
    public void setLength(int n) {
        len = Math.max(0, Math.min(chars.length, n));
    }

    /** BEHAVIOR: appends one char; dropped when full. */
    public CharBuf append(char c) {
        if (len < chars.length) {
            chars[len++] = c;
        }
        return this;
    }

    /** BEHAVIOR: appends {@code n} copies of {@code c} (clipped at capacity). */
    public CharBuf repeat(char c, int n) {
        for (int i = 0; i < n; i++) {
            append(c);
        }
        return this;
    }

    /** BEHAVIOR: appends the characters of {@code s} up to capacity. */
    public CharBuf append(CharSequence s) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            append(s.charAt(i));
        }
        return this;
    }

    /** BEHAVIOR: appends {@code s[off, off+n)} up to capacity. */
    public CharBuf append(char[] s, int off, int n) {
        for (int i = 0; i < n; i++) {
            append(s[off + i]);
        }
        return this;
    }

    /** BEHAVIOR: appends the decimal digits of {@code v} (with '-' when negative), like C {@code %d}. */
    public CharBuf appendInt(int v) {
        return appendInt(v, 0, ' ');
    }

    /**
     * Append {@code v} right-aligned in at least {@code width} chars padded with {@code pad}, like C
     * {@code %5d} (pad ' ') or {@code %04d} (pad '0').
     * <p>
     * BEHAVIOR: space padding goes before the sign, zero padding after it (C semantics).
     * BEHAVIOR: Integer.MIN_VALUE is printed exactly.
     */
    public CharBuf appendInt(int v, int width, char pad) {
        long x = v;
        boolean neg = x < 0;
        if (neg) {
            x = -x;
        }
        int digits = 1;
        for (long t = x; t >= 10; t /= 10) {
            digits++;
        }
        int total = digits + (neg ? 1 : 0);
        int padN = Math.max(0, width - total);
        if (pad == '0') {
            if (neg) {
                append('-');
            }
            repeat('0', padN);
        } else {
            repeat(pad, padN);
            if (neg) {
                append('-');
            }
        }
        appendDigits(x, digits);
        return this;
    }

    private void appendDigits(long x, int digits) {
        long div = 1;
        for (int i = 1; i < digits; i++) {
            div *= 10;
        }
        for (; div > 0; div /= 10) {
            append((char) ('0' + (x / div) % 10));
        }
    }

    /**
     * Append {@code v} with {@code decimals} fraction digits right-aligned in {@code width} chars, like C
     * {@code %*.*f}: the exact binary value is rounded to nearest, ties to even (glibc printf), so 2.25
     * prints 2.2 while 2.35 (really 2.35000000000000009) prints 2.4.
     * <p>
     * BEHAVIOR: decimals 0 prints no decimal point; a negative value (or -0.0) keeps its minus sign even when
     * it rounds to zero, as printf does: -0.04 prints "-0.0".
     * BEHAVIOR: decimals is clamped to 0..6.
     */
    public CharBuf appendFixed(double v, int decimals, int width) {
        int d = Math.max(0, Math.min(6, decimals));
        long scale = 1;
        for (int i = 0; i < d; i++) {
            scale *= 10;
        }
        long q = roundScaled(Math.abs(v), scale);
        boolean neg = Double.doubleToRawLongBits(v) < 0; // the sign bit, as printf: also -0.0
        long ip = q / scale;
        long fp = q % scale;
        int intDigits = 1;
        for (long t = ip; t >= 10; t /= 10) {
            intDigits++;
        }
        int total = intDigits + (neg ? 1 : 0) + (d > 0 ? d + 1 : 0);
        repeat(' ', Math.max(0, width - total));
        if (neg) {
            append('-');
        }
        appendDigits(ip, intDigits);
        if (d > 0) {
            append('.');
            appendDigits(fp, d);
        }
        return this;
    }

    /**
     * BEHAVIOR: a * scale rounded to nearest, ties to even, decided on the exact product: when the
     * double product lands on a .5 the fma residual says whether the true value was above, below or on it.
     */
    static long roundScaled(double a, long scale) {
        double p = a * scale;
        double fl = Math.floor(p);
        if (p - fl == 0.5) {
            double err = Math.fma(a, scale, -p);
            if (err > 0) {
                return (long) fl + 1;
            }
            if (err < 0) {
                return (long) fl;
            }
        }
        return (long) Math.rint(p);
    }

    /** BEHAVIOR: true iff the contents equal {@code s} character for character. */
    public boolean contentEquals(CharSequence s) {
        if (s.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (chars[i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
