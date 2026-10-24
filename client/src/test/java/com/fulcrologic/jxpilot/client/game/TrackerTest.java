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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tracker (association of position-only objects)")
class TrackerTest {
    final Tracker t = new Tracker(16);
    final int[] next = new int[16];

    static float[] f(double... v) {
        float[] r = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = (float) v[i];
        }
        return r;
    }

    static int[] keys(int n, int k) {
        int[] r = new int[n];
        java.util.Arrays.fill(r, k);
        return r;
    }

    int link(float[] ax, float[] avx, byte[] st, float[] bx, double ratio, double minSpeed, boolean wrap) {
        int n = ax.length;
        int m = bx.length;
        return t.link(n, ax, new float[n], avx, new float[n], st, keys(n, 1), m, bx, new float[m], keys(m, 1), 1, 64,
            ratio, minSpeed, wrap ? 1000 : 100000, 1000, wrap, next);
    }

    @Nested
    @DisplayName("link")
    class Link {
        @Test
        @DisplayName("KNOWN: nearest to the prediction within the slack, even when others are close")
        void known() {
            int n = link(f(100), f(21), new byte[] {Tracker.KNOWN}, f(100, 120, 131), 2, 2, false);
            int j = next[0];
            int out = link(f(100), f(21), new byte[] {Tracker.KNOWN}, f(130), 2, 2, false);
            assertAll(
                () -> assertEquals(1, n, "linked"),
                () -> assertEquals(1, j, "120 is 1 px from the prediction 121; 131 is 10 (radius 4 + 0.15 * 21)"),
                () -> assertEquals(0, out, "130 is 9 px off: beyond 7.15, not linked"));
        }

        @Test
        @DisplayName("GUESSED: around the prediction, only when unambiguous")
        void guessed() {
            link(f(100), f(21), new byte[] {Tracker.GUESSED}, f(121, 100), 2, 2, false);
            int clear = next[0];
            int amb = link(f(100), f(21), new byte[] {Tracker.GUESSED}, f(118, 124), 2, 2, false);
            assertAll(
                () -> assertEquals(0, clear, "121 at 0 px; 100 is outside the 12.4 px radius"),
                () -> assertEquals(0, amb, "118 and 124 are 3 px either side: ambiguous, not linked"));
        }

        @Test
        @DisplayName("UNKNOWN: within the maximum speed, unambiguous, never standing still; ratio 1 takes the nearest")
        void unknown() {
            int a = link(f(100), f(0), new byte[] {Tracker.UNKNOWN}, f(100, 121), 2, 2, false);
            int aj = next[0];
            int b = link(f(100), f(0), new byte[] {Tracker.UNKNOWN}, f(110, 115), 2, 0, false);
            int c = link(f(100), f(0), new byte[] {Tracker.UNKNOWN}, f(110, 115), 1, 0, false);
            int cj = next[0];
            int far = link(f(100), f(0), new byte[] {Tracker.UNKNOWN}, f(200), 1, 0, false);
            assertAll(
                () -> assertEquals(1, a, "linked"),
                () -> assertEquals(1, aj, "100 is not a move: 121 is the continuation"),
                () -> assertEquals(0, b, "10 and 15 px: ambiguous at ratio 2"),
                () -> assertEquals(1, c, "ratio 1 accepts the nearest"),
                () -> assertEquals(0, cj, "the nearest"),
                () -> assertEquals(0, far, "100 px is beyond 64 + 4"));
        }

        @Test
        @DisplayName("each later object keeps its closest proposer; the loser takes the next acceptable one")
        void conflicts() {
            int n = link(f(100, 105), f(20, 0), new byte[] {Tracker.KNOWN, Tracker.UNKNOWN}, f(120, 140), 1, 0, false);
            assertAll(
                () -> assertEquals(2, n, "both linked"),
                () -> assertEquals(0, next[0], "the KNOWN shot keeps 120 (0 px from its prediction)"),
                () -> assertEquals(1, next[1], "the other, beaten to 120 (15 px), takes 140 in the second pass"),
                () -> assertEquals(1, t.claimOf(1), "claimOf: 140 continues earlier object 1"));
        }

        @Test
        @DisplayName("keys must match; wrap-aware; frames <= 0 or no objects link nothing")
        void edges() {
            int keyed = t.link(1, f(100), f(0), f(20), f(0), new byte[] {Tracker.KNOWN}, new int[] {1}, 1, f(120),
                f(0), new int[] {2}, 1, 64, 2, 0, 1000, 1000, false, next);
            int wrapped = link(f(990), f(20), new byte[] {Tracker.KNOWN}, f(10), 2, 0, true);
            int unwrapped = link(f(990), f(20), new byte[] {Tracker.KNOWN}, f(10), 2, 0, false);
            int noFrames = t.link(1, f(100), f(0), f(20), f(0), new byte[] {Tracker.KNOWN}, new int[] {1}, 1, f(120),
                f(0), new int[] {1}, 0, 64, 2, 0, 1000, 1000, false, next);
            int noLater = link(f(100), f(20), new byte[] {Tracker.KNOWN}, f(), 2, 0, false);
            assertAll(
                () -> assertEquals(0, keyed, "a different key (variant/colour) is never a continuation"),
                () -> assertEquals(1, wrapped, "990 + 20 wraps to 10"),
                () -> assertEquals(0, unwrapped, "without wrap 10 is 1000 px away"),
                () -> assertEquals(0, noFrames, "no frames between: nothing"),
                () -> assertEquals(-1, next[0], "next reset"),
                () -> assertEquals(0, noLater, "no later objects: nothing"));
        }

        @Test
        @DisplayName("a world smaller than the search visits every cell once (no double counting)")
        void tinyWorld() {
            int n = t.link(1, f(10), f(10), f(0), f(0), new byte[] {Tracker.UNKNOWN}, new int[] {1}, 2, f(30, 12),
                f(10, 10), new int[] {1, 1}, 1, 64, 2, 5, 40, 40, true, next);
            int j = next[0];
            assertAll(
                () -> assertEquals(1, n, "linked: 30 is the only move of at least 5 px (12 is 2 px away)"),
                () -> assertEquals(0, j, "30, found once although the 68 px search spans the 40 px world"));
        }
    }

    @Test
    @DisplayName("estimate: linked later objects get the displacement / frames and KNOWN; others 0 and UNKNOWN")
    void estimate() {
        float[] ax = f(100, 500);
        float[] bx = f(121, 900);
        link(ax, f(21, 0), new byte[] {Tracker.KNOWN, Tracker.KNOWN}, bx, 2, 0, false);
        float[] vx = new float[2];
        float[] vy = new float[2];
        byte[] st = new byte[2];
        t.estimate(2, ax, new float[2], bx, new float[2], vx, vy, st, 1);
        assertAll(
            () -> assertEquals(21f, vx[0], "21 px in one frame"),
            () -> assertEquals(Tracker.KNOWN, st[0], "known"),
            () -> assertEquals(0f, vx[1], "unlinked: 0"),
            () -> assertEquals(Tracker.UNKNOWN, st[1], "unknown"));
    }
}
