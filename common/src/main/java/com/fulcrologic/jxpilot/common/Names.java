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
package com.fulcrologic.jxpilot.common;

/**
 * C's checks of real and host names (common/checknames.c {@code Fix_real_name}, {@code Fix_host_name}), applied by
 * the server to what a client sends and to the robot and tank names of the options.
 */
public final class Names {
    /** C {@code MAX_NAME_LEN} (pack.h): a real name keeps at most this many characters minus one. */
    public static final int MAX_NAME_LEN = 16;
    /** C {@code MAX_HOST_LEN} (pack.h): a host name keeps at most this many characters minus one. */
    public static final int MAX_HOST_LEN = 64;

    private Names() {}

    /**
     * C {@code Fix_real_name}.
     * <p>
     * BEHAVIOR: cut to 15 characters; empty becomes "X"; every character that is not printable ASCII other than
     * space (C {@code isgraph}) becomes 'x'.
     */
    public static void fixRealName(StringBuilder s) {
        if (s.length() > MAX_NAME_LEN - 1) {
            s.setLength(MAX_NAME_LEN - 1);
        }
        if (s.isEmpty()) {
            s.append('X');
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 33 || c > 126) {
                s.setCharAt(i, 'x');
            }
        }
    }

    /**
     * C {@code Fix_host_name}.
     * <p>
     * BEHAVIOR: cut to 63 characters; one that does not start with a letter or digit (or is empty) becomes
     * "xxx.xxx"; then every character other than a letter, digit, '.' or '-' becomes 'x', and so does a '.' or '-'
     * followed by '.', '-' or the end.
     */
    public static void fixHostName(StringBuilder s) {
        if (s.length() > MAX_HOST_LEN - 1) {
            s.setLength(MAX_HOST_LEN - 1);
        }
        if (s.isEmpty() || !alnum(s.charAt(0))) {
            s.setLength(0);
            s.append("xxx.xxx");
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (alnum(c)) {
                continue;
            }
            if (c == '.' || c == '-') {
                char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                if (next == '.' || next == '-' || next == 0) {
                    s.setCharAt(i, 'x');
                }
            } else {
                s.setCharAt(i, 'x');
            }
        }
    }

    /** BEHAVIOR: C {@code strlcpy(dst, src, MAX_CHARS)}: {@code dst} becomes at most MAX_CHARS - 1 characters of src. */
    public static void copy(StringBuilder dst, CharSequence src) {
        dst.setLength(0);
        dst.append(src, 0, Math.min(src.length(), Const.MAX_CHARS - 1));
    }

    /** C {@code is_alpha_numeric}: ASCII letters and digits only. */
    private static boolean alnum(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
