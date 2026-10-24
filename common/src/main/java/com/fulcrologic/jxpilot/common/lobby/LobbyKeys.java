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
package com.fulcrologic.jxpilot.common.lobby;

/**
 * The one-key shortcuts of the lobby list: 0-9, then a-z, then A-Z, so the first 62 rows have one. Rows after
 * that are reached by scrolling.
 */
public final class LobbyKeys {
    /** Shortcut characters in row order. */
    private static final String KEYS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** How many rows have a shortcut. */
    public static final int COUNT = KEYS.length();

    private LobbyKeys() {}

    /**
     * BEHAVIOR: row 0..9 is '0'..'9', 10..35 is 'a'..'z', 36..61 is 'A'..'Z'; any other row (negative or 62 and
     * more) has none (0).
     */
    public static char keyFor(int row) {
        return row >= 0 && row < COUNT ? KEYS.charAt(row) : 0;
    }

    /** BEHAVIOR: the row a typed character selects (inverse of {@link #keyFor}); -1 for any other character. */
    public static int rowOf(int ch) {
        return ch >= 0 && ch < 128 ? KEYS.indexOf(ch) : -1;
    }
}
