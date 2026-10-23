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
 * Ship id to frame index map for interpolation (an open-addressing hash with generation stamps, so
 * {@link #clear()} is O(1)). Holds at most {@code capacity} entries. Allocation free after construction.
 */
final class ShipIndex {
    private final int mask;
    private final int[] keys;
    private final int[] values;
    private final int[] stamp;
    private int gen = 1;

    /** @param capacity most entries between two clears (the table is at least twice as large) */
    ShipIndex(int capacity) {
        int n = Integer.highestOneBit(Math.max(2, 2 * capacity) - 1) << 1;
        mask = n - 1;
        keys = new int[n];
        values = new int[n];
        stamp = new int[n];
    }

    /** Table slots (a power of two, at least 2 x capacity). */
    int tableSize() {
        return mask + 1;
    }

    /** BEHAVIOR: forgets every entry. */
    void clear() {
        gen++;
        if (gen == 0) {
            java.util.Arrays.fill(stamp, 0);
            gen = 1;
        }
    }

    /** BEHAVIOR: maps id to index; a repeated id is overwritten. */
    void put(int id, int index) {
        int h = hash(id);
        while (stamp[h] == gen && keys[h] != id) {
            h = (h + 1) & mask;
        }
        stamp[h] = gen;
        keys[h] = id;
        values[h] = index;
    }

    /** BEHAVIOR: the index of id, -1 when absent. */
    int get(int id) {
        int h = hash(id);
        while (stamp[h] == gen) {
            if (keys[h] == id) {
                return values[h];
            }
            h = (h + 1) & mask;
        }
        return -1;
    }

    private int hash(int id) {
        int h = id * 0x9E3779B9;
        return (h ^ (h >>> 16)) & mask;
    }
}
