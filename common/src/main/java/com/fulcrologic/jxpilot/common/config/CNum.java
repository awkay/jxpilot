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
package com.fulcrologic.jxpilot.common.config;

/**
 * C-compatible number parsing used by option conversion: {@code strtol(s, &end, 0)},
 * {@code strtod} and XPilot's boolean words. Like C, a numeric prefix is enough
 * ({@code "12abc"} parses as 12).
 */
public final class CNum {
    private CNum() {}

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0b;
    }

    /**
     * strtol with base 0.
     * <p>
     * BEHAVIOR: skips leading ASCII whitespace and accepts one sign.
     * BEHAVIOR: "0x"/"0X" followed by a hex digit is hex; a leading 0 is octal; otherwise decimal.
     * BEHAVIOR: stops at the first non-digit; returns true iff at least one digit was consumed.
     * BEHAVIOR: magnitudes are clamped to 4 * Integer.MAX_VALUE.
     */
    public static boolean strtol(String s, long[] out) {
        int i = 0;
        int n = s.length();
        while (i < n && isSpace(s.charAt(i))) {
            i++;
        }
        boolean neg = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        int base = 10;
        if (i + 1 < n && s.charAt(i) == '0' && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')
            && i + 2 < n && Character.digit(s.charAt(i + 2), 16) >= 0) {
            base = 16;
            i += 2;
        } else if (i < n && s.charAt(i) == '0') {
            base = 8;
        }
        long v = 0;
        int start = i;
        while (i < n) {
            int d = Character.digit(s.charAt(i), base);
            if (d < 0 || s.charAt(i) > 127) {
                break;
            }
            v = v * base + d;
            if (v > Integer.MAX_VALUE * 4L) {
                v = Integer.MAX_VALUE * 4L;
            }
            i++;
        }
        out[0] = neg ? -v : v;
        return i > start;
    }

    /**
     * strtod on the longest valid decimal prefix.
     * <p>
     * BEHAVIOR: accepts sign, digits, one '.', and an exponent only if it has digits.
     * BEHAVIOR: returns false (value 0) when there is no mantissa digit.
     */
    public static boolean strtod(String s, double[] out) {
        int i = 0;
        int n = s.length();
        while (i < n && isSpace(s.charAt(i))) {
            i++;
        }
        int start = i;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        int digits = 0;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
            digits++;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            out[0] = 0.0;
            return false;
        }
        int mantEnd = i;
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                j++;
            }
            int ed = 0;
            while (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                j++;
                ed++;
            }
            if (ed > 0) {
                mantEnd = j;
            }
        }
        String num = s.substring(start, mantEnd);
        out[0] = Double.parseDouble(num);
        return true;
    }

    /** BEHAVIOR: 1 for yes/on/true, 0 for no/off/false (case-insensitive), -1 otherwise. */
    public static int bool(String s) {
        if (s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on") || s.equalsIgnoreCase("true")) {
            return 1;
        }
        if (s.equalsIgnoreCase("no") || s.equalsIgnoreCase("off") || s.equalsIgnoreCase("false")) {
            return 0;
        }
        return -1;
    }
}
