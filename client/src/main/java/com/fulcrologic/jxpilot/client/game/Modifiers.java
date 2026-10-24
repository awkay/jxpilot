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

import static com.fulcrologic.jxpilot.common.net.Records.Self.*;

/**
 * Weapon modifiers: the {@link com.fulcrologic.jxpilot.common.net.Records.Self#mods()} bits to and from
 * the C server's HUD string (C {@code Frame_status} in {@code server/frame.c}, e.g. {@code "FNCI V2 X3 Z1 B2 LS"}).
 * Pure and allocation free.
 */
public final class Modifiers {
    private Modifiers() {}

    /**
     * Append the C modifier string of {@code bits}.
     * <p>
     * BEHAVIOR: 'F' full nuclear, 'N' nuclear, 'C' cluster, 'I' implosion run together; then, each preceded
     * by a space when anything came before: V velocity, X mini + 1, Z spread, B power (only when non-zero),
     * and L followed by 'S' (stun) or 'B' (blind) when a laser modifier is set. No bits: nothing.
     */
    public static CharBuf format(int bits, CharBuf out) {
        int start = out.length();
        if ((bits & MOD_FULL_NUCLEAR) != 0) {
            out.append('F');
        }
        if ((bits & MOD_NUCLEAR) != 0) {
            out.append('N');
        }
        if ((bits & MOD_CLUSTER) != 0) {
            out.append('C');
        }
        if ((bits & MOD_IMPLOSION) != 0) {
            out.append('I');
        }
        number(out, start, 'V', field(bits, MOD_VELOCITY_SHIFT), 0);
        number(out, start, 'X', field(bits, MOD_MINI_SHIFT), 1);
        number(out, start, 'Z', field(bits, MOD_SPREAD_SHIFT), 0);
        number(out, start, 'B', field(bits, MOD_POWER_SHIFT), 0);
        if ((bits & (MOD_LASER_STUN | MOD_LASER_BLIND)) != 0) {
            space(out, start);
            out.append('L').append((bits & MOD_LASER_STUN) != 0 ? 'S' : 'B');
        }
        return out;
    }

    private static void number(CharBuf out, int start, char letter, int value, int shown) {
        if (value != 0) {
            space(out, start);
            out.append(letter).appendInt(value + shown);
        }
    }

    private static void space(CharBuf out, int start) {
        if (out.length() > start) {
            out.append(' ');
        }
    }

    /** The 2-bit field at {@code shift}. */
    public static int field(int bits, int shift) {
        return bits >> shift & 3;
    }

    /**
     * Parse a C modifier string (the inverse of {@link #format}).
     * <p>
     * BEHAVIOR: F, N, C, I set their bits; V, X, Z, B take the digit after them (X stores the digit - 1),
     * each clamped to 0..3; "LS" is stun and "LB" blind; spaces and unknown characters are skipped.
     */
    public static int parse(CharSequence s) {
        int bits = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            int digit = i + 1 < n ? Character.digit(s.charAt(i + 1), 10) : -1;
            switch (c) {
                case 'F' -> bits |= MOD_FULL_NUCLEAR;
                case 'N' -> bits |= MOD_NUCLEAR;
                case 'C' -> bits |= MOD_CLUSTER;
                case 'I' -> bits |= MOD_IMPLOSION;
                case 'V' -> bits |= put(digit, 0, MOD_VELOCITY_SHIFT);
                case 'X' -> bits |= put(digit, 1, MOD_MINI_SHIFT);
                case 'Z' -> bits |= put(digit, 0, MOD_SPREAD_SHIFT);
                case 'B' -> bits |= put(digit, 0, MOD_POWER_SHIFT);
                case 'L' -> {
                    char k = i + 1 < n ? s.charAt(i + 1) : ' ';
                    if (k == 'S') {
                        bits |= MOD_LASER_STUN;
                        i++;
                    } else if (k == 'B') {
                        bits |= MOD_LASER_BLIND;
                        i++;
                    }
                }
                default -> { }
            }
        }
        return bits;
    }

    private static int put(int digit, int shown, int shift) {
        if (digit < 0) {
            return 0;
        }
        return Math.min(3, Math.max(0, digit - shown)) << shift;
    }
}
