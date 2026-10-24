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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.sim.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
/**
 * C {@code Receive_modifier_bank} (netserver.c:2844): a client's modifier bank text ({@code modifierBank1..4},
 * e.g. {@code "FN C V2 X4 LS"}) parsed into packed {@link Mods}. Pure; allocation free.
 */
public final class ModifierBanks {
    private ModifierBanks() {}

    /**
     * C {@code MAX_CHARS}: C's {@code Packet_scanf} {@code %s} fails on a text of this many chars or more (string
     * overflow), so no bank is stored.
     */
    public static final int MAX_TEXT = 80;

    /**
     * BEHAVIOR: true iff C would store a bank: {@code bank < NUM_MODBANKS} (4) and the text is shorter than
     * {@link #MAX_TEXT} (C's packet scan fails otherwise).
     */
    public static boolean accepted(int bank, CharSequence text) {
        return bank >= 0 && bank < Player.BANKS && text.length() < MAX_TEXT;
    }

    /**
     * Parse one bank text with the world's rules ({@code World.rules->mode} bits in C).
     * <p>
     * BEHAVIOR: without allowModifiers the bank is empty (0) whatever the text.
     * BEHAVIOR: letters are case-insensitive; other chars are skipped; the text ends at its end or a NUL.
     * BEHAVIOR: {@code N} sets NUCLEAR and {@code F} directly followed by {@code N} also FULLNUCLEAR (the N then
     * sets NUCLEAR), both only with allowNukes; {@code C} sets CLUSTER only with allowClusters; {@code I} sets
     * IMPLOSION.
     * BEHAVIOR: {@code V}, {@code Z}, {@code B} read the digits after them (none = 0) as velocity, spread, power,
     * each 0..3; {@code X} reads mini as digits - 1 for 1..4 (none = mini 0); a number out of range gives the
     * minimum (C {@code str2num}); the char after the digits is parsed next.
     * BEHAVIOR: {@code L} always consumes the next char; with allowLaserModifiers that char {@code S} sets STUN
     * and {@code B} BLIND. An {@code L} at the very end ends the text (C reads past the terminator there).
     */
    public static int parse(CharSequence str, boolean modifiers, boolean nukes, boolean clusters,
                            boolean laserModifiers) {
        int m = 0;
        if (!modifiers) {
            return 0;
        }
        int n = end(str);
        for (int i = 0; i < n; i++) {
            switch (str.charAt(i)) {
                case 'F', 'f' -> {
                    if (nukes && i + 1 < n && (str.charAt(i + 1) == 'N' || str.charAt(i + 1) == 'n')) {
                        m = Mods.withNuclear(m, Mods.nuclear(m) | Mods.FULLNUCLEAR);
                    }
                }
                case 'N', 'n' -> {
                    if (nukes) {
                        m = Mods.withNuclear(m, Mods.nuclear(m) | Mods.NUCLEAR);
                    }
                }
                case 'C', 'c' -> {
                    if (clusters) {
                        m |= Obj.MOD_CLUSTER;
                    }
                }
                case 'I', 'i' -> m |= Obj.MOD_IMPLOSION;
                case 'V', 'v' -> {
                    int e = digitsEnd(str, i + 1, n);
                    m = Mods.withVelocity(m, num(str, i + 1, e, 0, Mods.VELOCITY_MAX));
                    i = e - 1;
                }
                case 'X', 'x' -> {
                    int e = digitsEnd(str, i + 1, n);
                    m = Mods.withMini(m, num(str, i + 1, e, 1, Mods.MINI_MAX + 1) - 1);
                    i = e - 1;
                }
                case 'Z', 'z' -> {
                    int e = digitsEnd(str, i + 1, n);
                    m = Mods.withSpread(m, num(str, i + 1, e, 0, Mods.SPREAD_MAX));
                    i = e - 1;
                }
                case 'B', 'b' -> {
                    int e = digitsEnd(str, i + 1, n);
                    m = Mods.withPower(m, num(str, i + 1, e, 0, Mods.POWER_MAX));
                    i = e - 1;
                }
                case 'L', 'l' -> {
                    i++;
                    if (i >= n) {
                        return m;
                    }
                    if (laserModifiers) {
                        char c = str.charAt(i);
                        if (c == 'S' || c == 's') {
                            m = Mods.withLaser(m, Mods.laser(m) | Mods.STUN);
                        }
                        if (c == 'B' || c == 'b') {
                            m = Mods.withLaser(m, Mods.laser(m) | Mods.BLIND);
                        }
                    }
                }
                default -> { }
            }
        }
        return m;
    }

    /** Index of the first NUL, else the length (C strings end at the NUL). */
    private static int end(CharSequence s) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) == 0) {
                return i;
            }
        }
        return n;
    }

    /** Index of the first non-digit at or after {@code from} (before {@code n}). */
    private static int digitsEnd(CharSequence s, int from, int n) {
        int i = from;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return i;
    }

    /**
     * C {@code str2num}: the decimal number in {@code s[from, to)} (0 when empty), or {@code min} when outside
     * {@code [min, max]}. Saturates instead of overflowing (C's int overflow is undefined; any such value is
     * out of range).
     */
    static int num(CharSequence s, int from, int to, int min, int max) {
        int v = 0;
        for (int i = from; i < to; i++) {
            v = Math.min(v * 10 + (s.charAt(i) - '0'), 1_000_000);
        }
        return v < min || v > max ? min : v;
    }
}
