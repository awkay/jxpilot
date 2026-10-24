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
package com.fulcrologic.jxpilot.common.math;

/**
 * XPilot 4.5.5's random number generator ({@code src/common/randommt.c}, Shawn Cokus' Mersenne Twister with
 * Knuth's seeding) and its {@code rfrac()} ({@code src/common/math.c}), bit for bit. The game uses its own
 * generator ({@link Rng}); this one replays a C server's stream in tests ({@link Rng#useC}). Allocation free
 * after construction.
 */
public final class CRandom {
    private static final int N = 624;
    private static final int M = 397;
    private static final int K = 0x9908B0DF;

    /** C {@code state[N + 1]}. */
    private final int[] state = new int[N + 1];
    /** C {@code next} as an index into {@link #state}. */
    private int next;
    /** C {@code left}: values left before the next reload; -1 before seeding. */
    private int left = -1;
    /** Values drawn since the last seed or state load (the C trace counts the same way). */
    private long draws;

    /** C {@code seedMT(seed)}. */
    public CRandom(int seed) {
        seed(seed);
    }

    /** BEHAVIOR: C {@code seedMT}: state[0] = seed | 1, then x *= 69069 for the other 623 words; left = 0. */
    public void seed(int seed) {
        int x = seed | 1;
        state[0] = x;
        for (int j = 1; j < N; j++) {
            x *= 69069;
            state[j] = x;
        }
        left = 0;
        next = 0;
        draws = 0;
    }

    /**
     * Load a C generator's state as the reference server printed it ({@code left}, {@code next - state} and
     * the 625 state words); the draw counter starts at {@code draws}.
     */
    public void load(int left, int nextIndex, int[] words, long draws) {
        System.arraycopy(words, 0, state, 0, N + 1);
        this.left = left;
        this.next = nextIndex;
        this.draws = draws;
    }

    /** C {@code left} (tests). */
    int left() {
        return left;
    }

    /** C {@code next - state} (tests). */
    int nextIndex() {
        return next;
    }

    /** Copy the 625 state words into {@code words} (tests). */
    void snapshot(int[] words) {
        System.arraycopy(state, 0, words, 0, N + 1);
    }

    /** Values drawn ({@code randomMT()} calls) since the last seed or load, plus the load's start count. */
    public long draws() {
        return draws;
    }

    /** BEHAVIOR: C {@code randomMT()}: the next 32 tempered bits (unsigned in C, the same bits here). */
    public int next() {
        draws++;
        if (--left < 0) {
            return reload();
        }
        int y = state[next++];
        return temper(y);
    }

    /** BEHAVIOR: C {@code rfrac()}: {@code (double) randomMT() * 2^-32}, in [0, 1). */
    public double rfrac() {
        return (next() & 0xFFFFFFFFL) * 0.00000000023283064365386962890625;
    }

    private static int temper(int y) {
        y ^= y >>> 11;
        y ^= (y << 7) & 0x9D2C5680;
        y ^= (y << 15) & 0xEFC60000;
        return y ^ (y >>> 18);
    }

    private static int mix(int u, int v) {
        return (u & 0x80000000) | (v & 0x7FFFFFFF);
    }

    /** C {@code reloadMT()}. */
    private int reload() {
        if (left < -1) {
            seed(4357);
        }
        left = N - 1;
        next = 1;
        int p0 = 0;
        int p2 = 2;
        int pM = M;
        int s0 = state[0];
        int s1 = state[1];
        for (int j = N - M + 1; --j > 0; s0 = s1, s1 = state[p2++]) {
            state[p0++] = state[pM++] ^ (mix(s0, s1) >>> 1) ^ ((s1 & 1) != 0 ? K : 0);
        }
        pM = 0;
        for (int j = M; --j > 0; s0 = s1, s1 = state[p2++]) {
            state[p0++] = state[pM++] ^ (mix(s0, s1) >>> 1) ^ ((s1 & 1) != 0 ? K : 0);
        }
        s1 = state[0];
        state[p0] = state[pM] ^ (mix(s0, s1) >>> 1) ^ ((s1 & 1) != 0 ? K : 0);
        s1 ^= s1 >>> 11;
        s1 ^= (s1 << 7) & 0x9D2C5680;
        s1 ^= (s1 << 15) & 0xEFC60000;
        return s1 ^ (s1 >>> 18);
    }
}
