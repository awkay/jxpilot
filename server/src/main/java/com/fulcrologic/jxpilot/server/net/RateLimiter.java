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
 * Per-source token buckets in a fixed table: allocation free, bounded memory. A source is a 64-bit
 * key, already a secret-keyed hash ({@link SourceKeys}), so its low bits pick the slot. Sources that
 * land on the same slot share its bucket: the tokens carry over, so a collision only ever makes the
 * limit stricter, never resets it to full.
 */
public final class RateLimiter {
    private final int mask;
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
        used = new boolean[n];
        tokens = new double[n];
        last = new long[n];
        perNano = perSecond / 1e9;
        this.burst = burst;
    }

    /**
     * Spend {@code cost} tokens of source {@code key}.
     * <p>
     * BEHAVIOR: the first source on a slot starts with a full bucket.
     * BEHAVIOR: a source colliding with another on its slot shares that bucket (tokens carry over).
     * BEHAVIOR: buckets refill at perSecond, capped at burst.
     * BEHAVIOR: allowed (and charged) iff the bucket holds at least {@code cost}; refused requests
     * cost nothing.
     */
    public boolean allow(long key, double cost, long nowNanos) {
        int s = slot(key);
        if (!used[s]) {
            used[s] = true;
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

    /** BEHAVIOR: table slot of a key: its low bits (keys are already well mixed). */
    int slot(long key) {
        return (int) key & mask;
    }

    int capacity() {
        return mask + 1;
    }
}
