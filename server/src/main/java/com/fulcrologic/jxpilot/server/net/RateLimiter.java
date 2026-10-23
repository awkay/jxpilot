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
package com.fulcrologic.jxpilot.server.net;

/**
 * Per-source token buckets in a fixed open table: allocation free, bounded memory. A source is an
 * int key (the source IP's hash). Sources that collide on a slot evict each other and start with a
 * full bucket, which only ever makes the limit more lenient.
 */
public final class RateLimiter {
    private final int mask;
    private final int[] keys;
    private final boolean[] used;
    private final double[] tokens;
    private final long[] last;
    private final double perNano;
    private final double burst;

    /**
     * @param slots     table size (rounded up to a power of two)
     * @param perSecond sustained tokens per second
     * @param burst     bucket capacity
     */
    public RateLimiter(int slots, double perSecond, double burst) {
        int n = Integer.highestOneBit(Math.max(1, slots - 1)) << 1;
        mask = n - 1;
        keys = new int[n];
        used = new boolean[n];
        tokens = new double[n];
        last = new long[n];
        perNano = perSecond / 1e9;
        this.burst = burst;
    }

    /**
     * Spend {@code cost} tokens of source {@code key}.
     * <p>
     * BEHAVIOR: a new (or evicting) source starts with a full bucket.
     * BEHAVIOR: buckets refill at perSecond, capped at burst.
     * BEHAVIOR: allowed (and charged) iff the bucket holds at least {@code cost}; refused requests
     * cost nothing.
     */
    public boolean allow(int key, double cost, long nowNanos) {
        int s = slot(key);
        if (!used[s] || keys[s] != key) {
            used[s] = true;
            keys[s] = key;
            tokens[s] = burst;
        } else {
            tokens[s] = Math.min(burst, tokens[s] + (nowNanos - last[s]) * perNano);
        }
        last[s] = nowNanos;
        if (tokens[s] < cost) {
            return false;
        }
        tokens[s] -= cost;
        return true;
    }

    /** BEHAVIOR: table slot of a key (hash spread so nearby addresses use different slots). */
    int slot(int key) {
        int h = key * 0x9E3779B9;
        return (h ^ (h >>> 16)) & mask;
    }

    int capacity() {
        return mask + 1;
    }
}
