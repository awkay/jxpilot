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
package com.fulcrologic.jxpilot.client.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NetDiagnostics")
class NetDiagnosticsTest {
    static final long MS = 1_000_000L;
    /** One tick at 10 Hz: whole milliseconds, so the arithmetic is exact. */
    static final long TICK = 100 * MS;

    static NetDiagnostics tenHz(int divisor) {
        NetDiagnostics d = new NetDiagnostics();
        d.reset(10, divisor);
        return d;
    }

    @Nested
    @DisplayName("RTT")
    class Rtt {
        @Test
        @DisplayName("first sample sets it, later ones move it by 1/8; out-of-range samples are ignored")
        void smoothing() {
            NetDiagnostics d = new NetDiagnostics();
            double before = d.rttMs();
            double lastBefore = d.lastRttMs();
            d.rttSample(200 * MS);
            double first = d.rttMs();
            d.rttSample(280 * MS);
            double second = d.rttMs();
            d.rttSample(-1);
            d.rttSample(NetDiagnostics.MAX_RTT_NANOS);
            double afterBad = d.rttMs();
            d.rttSample(NetDiagnostics.MAX_RTT_NANOS - 1);
            assertAll(
                () -> assertTrue(Double.isNaN(before), "unknown before any PONG"),
                () -> assertTrue(Double.isNaN(lastBefore), "no last sample before any PONG"),
                () -> assertEquals(200, first, 1e-9, "the first sample"),
                () -> assertEquals(210, second, 1e-9, "200 + (280 - 200) / 8"),
                () -> assertEquals(210, afterBad, 1e-9, "negative and >= 10 s samples ignored"),
                () -> assertEquals(9999.999999, d.lastRttMs(), 1e-9, "just under 10 s is a sample"));
        }

        @Test
        @DisplayName("a 0 ns sample (PONG read in the same frame) is a valid sample")
        void zero() {
            NetDiagnostics d = new NetDiagnostics();
            d.rttSample(0);
            assertAll(
                () -> assertEquals(0, d.rttMs(), "smoothed 0"),
                () -> assertEquals(0, d.lastRttMs(), "last sample 0, not unknown"));
        }

        @Test
        @DisplayName("a new game's reset keeps the RTT (same path)")
        void survivesReset() {
            NetDiagnostics d = new NetDiagnostics();
            d.rttSample(50 * MS);
            d.reset(14, 1);
            assertEquals(50, d.rttMs(), 1e-9, "RTT kept");
        }
    }

    @Nested
    @DisplayName("jitter")
    class Jitter {
        @Test
        @DisplayName("RFC 3550: |arrival gap - tick gap|, smoothed by 1/16; NaN before the second snapshot")
        void rfc3550() {
            NetDiagnostics d = tenHz(1);
            d.snapshot(1, 0);
            double one = d.jitterMs();
            d.snapshot(2, TICK + 16 * MS); // 16 ms late
            double two = d.jitterMs();
            d.snapshot(4, 3 * TICK + 16 * MS); // two ticks later, on time: D = 0
            double three = d.jitterMs();
            assertAll(
                () -> assertTrue(Double.isNaN(one), "unknown after one snapshot"),
                () -> assertEquals(16, two, 1e-9, "the first difference sets it"),
                () -> assertEquals(15, three, 1e-9, "16 + (0 - 16) / 16"));
        }

        @Test
        @DisplayName("a tick that is not newer changes nothing; reset forgets jitter and loss")
        void ignoresOld() {
            NetDiagnostics d = tenHz(1);
            d.snapshot(5, 0);
            d.snapshot(6, TICK + 8 * MS);
            d.snapshot(6, 5 * TICK);
            d.snapshot(4, 9 * TICK);
            double j = d.jitterMs();
            d.snapshot(7, 2 * TICK + 8 * MS); // the old ones did not move the reference
            double after = d.jitterMs();
            d.reset(10, 1);
            assertAll(
                () -> assertEquals(8, j, 1e-9, "unchanged by the old ticks"),
                () -> assertEquals(7.5, after, 1e-9, "the next gap is measured from tick 6 at 108 ms: D = 0"),
                () -> assertTrue(Double.isNaN(d.jitterMs()), "reset forgets the jitter"),
                () -> assertTrue(Double.isNaN(d.lossPercent()), "reset forgets the loss"));
        }
    }

    @Nested
    @DisplayName("loss")
    class Loss {
        @Test
        @DisplayName("a window of 28 snapshot intervals: none lost reads 0%, 7 lost reads 25%; NaN before")
        void windows() {
            NetDiagnostics d = tenHz(1);
            d.snapshot(100, 0);
            for (int k = 101; k < 128; k++) {
                d.snapshot(k, 0);
            }
            double partial = d.lossPercent();
            d.snapshot(128, 0);
            double clean = d.lossPercent();
            for (int k = 129; k <= 156; k++) {
                if (k % 4 != 1) {
                    d.snapshot(k, 0);
                }
            }
            double lossy = d.lossPercent();
            assertAll(
                () -> assertTrue(Double.isNaN(partial), "no reading before the window is full"),
                () -> assertEquals(0, clean, 1e-9, "28 of 28 received"),
                () -> assertEquals(25, lossy, 1e-9, "21 of 28 received (129, 133, ... 153 lost)"));
        }

        @Test
        @DisplayName("with a snapshot divisor, expected counts snapshot intervals, not ticks")
        void divisor() {
            NetDiagnostics d = tenHz(2);
            d.snapshot(0, 0);
            for (int k = 2; k <= 54; k += 2) {
                d.snapshot(k, 0);
            }
            double before = d.lossPercent();
            d.snapshot(56, 0);
            assertAll(
                () -> assertTrue(Double.isNaN(before), "the window is 28 intervals = 56 ticks: no reading at tick 54"),
                () -> assertEquals(0, d.lossPercent(), 1e-9, "28 snapshots every 2 ticks: none lost"));
        }

        @Test
        @DisplayName("a nearly empty window reads high; a huge jump restarts the window without a reading")
        void edges() {
            NetDiagnostics d = tenHz(1);
            d.snapshot(0, 0);
            d.snapshot(28, 0); // 1 received of 28 expected
            double big = d.lossPercent();
            NetDiagnostics four = tenHz(1);
            four.snapshot(0, 0);
            four.snapshot(4 * 28, 0); // exactly 4 windows: still a reading
            NetDiagnostics j = tenHz(1);
            j.snapshot(0, 0);
            j.snapshot(10, 0);
            j.snapshot(10_000, 0); // a server restart, far ahead
            double afterJump = j.lossPercent();
            for (int k = 10_001; k <= 10_028; k++) {
                j.snapshot(k, 0);
            }
            assertAll(
                () -> assertEquals(100 * (1 - 1 / 28.0), big, 1e-9, "1 of 28"),
                () -> assertEquals(100 * (1 - 1 / 112.0), four.lossPercent(), 1e-9, "a jump of exactly 4 windows is read"),
                () -> assertTrue(Double.isNaN(afterJump), "the jump gives no reading"),
                () -> assertEquals(0, j.lossPercent(), 1e-9, "the window restarted at the jump"));
        }
    }

    @Test
    @DisplayName("before any reset a tick is 1/14 s (XPilot's default frame rate)")
    void defaultRate() {
        NetDiagnostics d = new NetDiagnostics();
        d.snapshot(1, 0);
        d.snapshot(2, 1_000_000_000L / 14 + 5 * MS);
        // tolerance: 1e9/14 is not a whole number of nanoseconds
        assertEquals(5, d.jitterMs(), 1e-5, "one 14 Hz tick plus 5 ms");
    }

    @Test
    @DisplayName("the prediction fields start unknown")
    void placeholders() {
        NetDiagnostics d = new NetDiagnostics();
        assertAll(
            () -> assertTrue(Double.isNaN(d.leadTicks), "lead"),
            () -> assertTrue(Double.isNaN(d.inputSlackTicks), "slack"),
            () -> assertTrue(Double.isNaN(d.correctionsPerSecond), "corrections"),
            () -> assertTrue(Double.isNaN(d.lastCorrectionPx), "last correction"));
    }
}
