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

import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEHAVIOR (PredictedClock): NaN until a snapshot; starts at the server's present plus the initial lead
 * ceil((RTT/2 + 30 ms)/T) + 1; advances at the server's rate times (1 + dilation); the first RTT moves a running
 * clock forward to the initial lead (never back), later ones do not; slack reports set a proportional dilation
 * clamped to ±3% with a deadband, on the report corrected for the clock's own adjustment since the entries it
 * covers; a starvation jumps up by max(1, shortfall), guarded until snapshots reach the first tick sent after it;
 * once per session after simHz slack reports the excess slack is removed at once; guard rails re-initialize on a
 * negative or over-long lead; reset forgets everything.
 */
@DisplayName("PredictedClock")
class PredictedClockTest {
    static final double HZ = 14;
    static final double T = 1e9 / HZ;
    static final long MS = 1_000_000L;
    static final int NO = SnapshotWriter.NO_SLACK;

    static PredictedClock clock() {
        PredictedClock c = new PredictedClock();
        c.reset(HZ);
        return c;
    }

    /** Time of {@code ticks} ticks after 0. */
    static long at(double ticks) {
        return Math.round(ticks * T);
    }

    @Test
    @DisplayName("NaN before a snapshot; then the present plus the initial lead; advances one tick per tick")
    void start() {
        PredictedClock c = clock();
        double before = c.tick(0);
        boolean readyBefore = c.ready();
        c.snapshot(100, 0);
        double first = c.tick(0);
        double later = c.tick(at(3));
        assertAll(
            () -> assertTrue(Double.isNaN(before), "NaN before a snapshot"),
            () -> assertFalse(readyBefore, "not ready"),
            () -> assertTrue(c.ready(), "ready after one"),
            () -> assertEquals(102, first, 1e-9, "RTT 0 at 14 Hz: ceil(30 ms / 71.4 ms) + 1 = 2 ticks ahead"),
            () -> assertEquals(105, later, 1e-6, "three ticks later: 105"),
            () -> assertEquals(2, c.leadTicks(at(3)), 1e-6, "the lead over the server's present"),
            () -> assertTrue(Double.isNaN(new PredictedClock().leadTicks(0)), "no lead before it runs"));
    }

    @Test
    @DisplayName("initial lead: ceil((RTT/2 + 30 ms)/T) + 1")
    void initialLead() {
        PredictedClock c = clock();
        int zero = c.initialLead();
        c.rtt(100 * MS, 0);
        int hundred = c.initialLead();
        c.reset(HZ);
        c.rtt(300 * MS, 0);
        int three = c.initialLead();
        c.reset(60);
        int sixty = c.initialLead();
        assertAll(
            () -> assertEquals(2, zero, "RTT 0: ceil(0.42) + 1"),
            () -> assertEquals(3, hundred, "RTT 100: ceil(1.12) + 1"),
            () -> assertEquals(4, three, "RTT 300: ceil(2.52) + 1"),
            () -> assertEquals(3, sixty, "RTT 0 at 60 Hz: ceil(1.8) + 1"));
    }

    @Test
    @DisplayName("the first RTT moves a running clock forward to the initial lead; later RTTs do not move it")
    void rtt() {
        PredictedClock c = clock();
        c.snapshot(100, 0);
        c.tick(0);
        c.rtt(100 * MS, 0);
        double moved = c.tick(0);
        c.rtt(0, 0);
        double kept = c.tick(0);
        double adjusted = c.adjustment();
        PredictedClock zero = clock();
        zero.rtt(0, 0);
        boolean zeroCounts = zero.haveRtt();
        PredictedClock d = clock();
        d.rtt(-5, 0);
        boolean ignored = d.haveRtt();
        d.rtt(Double.NaN, 0);
        assertAll(
            () -> assertEquals(100 + 0.7 + 3, moved, 1e-9, "present (100 + RTT/2 = 0.7 tick) + 3"),
            () -> assertEquals(moved, kept, 1e-9, "a later RTT does not move it"),
            () -> assertEquals(0.7 + 3 - 2, adjusted, 1e-9, "the move counts as the clock's own adjustment"),
            () -> assertTrue(zeroCounts, "an RTT of 0 is a sample"),
            () -> assertEquals(3 + 0.7 - 0, c.leadTicks(0), 1, "the lead is measured with the newest RTT (0 now)"),
            () -> assertFalse(ignored, "a negative RTT is ignored"),
            () -> assertFalse(d.haveRtt(), "and NaN"));
    }

    @Test
    @DisplayName("slack steers the dilation: deadband, proportional, clamped to 3%; the target adds 2 sigma of jitter")
    void dilation() {
        PredictedClock c = clock();
        c.snapshot(100, 0);
        c.tick(0);
        c.feedback(100, 9, false, 0, 0);
        double inBand = c.dilation();
        c.feedback(100, 10, false, 0, 0);
        double edge = c.dilation();
        c.feedback(100, 12, false, 0, 0);
        double quarter = c.dilation();
        c.feedback(100, 30, false, 0, 0);
        double over = c.dilation();
        c.feedback(100, -20, false, 0, 0);
        double under = c.dilation();
        c.feedback(100, NO, false, 0, 0);
        double kept = c.dilation();
        double from = c.tick(0);
        double slow = c.tick(at(10)) - from;
        assertAll(
            () -> assertEquals(0, inBand, 1e-12, "9/16: 0.0625 over the 0.5 target is inside the 0.125 band"),
            () -> assertEquals(-0.0075, edge, 1e-12, "10/16: exactly 0.125 over is outside the band"),
            () -> assertEquals(-0.015, quarter, 1e-12, "12/16: 0.25 over: half the 3%, running slow"),
            () -> assertEquals(-0.03, over, 1e-12, "far over: -3%"),
            () -> assertEquals(0.03, under, 1e-12, "late arrivals (negative slack): +3%"),
            () -> assertEquals(0.03, kept, 1e-12, "NO_SLACK keeps the dilation"),
            () -> assertEquals(10.3, slow, 1e-6, "10 ticks at +3% advance 10.3"),
            () -> assertEquals(-20 / 16.0, c.lastSlack(), 1e-12, "the last slack report"),
            () -> assertEquals(0.5, c.targetSlack(Double.NaN), 1e-12, "no jitter: 0.5"),
            () -> assertEquals(0.5 + 2 * 0.25, c.targetSlack(T / 4), 1e-9, "a quarter tick of jitter: 1.0"));
    }

    @Test
    @DisplayName("a starvation jumps up at once, by 1 or the estimated shortfall; once until snapshots reach the guard")
    void starvation() {
        PredictedClock c = clock();
        c.snapshot(100, 0);
        c.tick(0);
        c.feedback(100, NO, true, 0, 0);
        double jumped = c.tick(0);
        double stopped = c.dilation();
        c.feedback(102, NO, true, 0, 0);
        double guarded = c.tick(0);
        c.feedback(104, -24, true, 0, 0);
        double second = c.tick(0);
        c.feedback(106, 8, false, 0, 0);
        double steering = c.dilation();
        assertAll(
            () -> assertEquals(103, jumped, 1e-9, "102 + 1 (no slack report: one tick)"),
            () -> assertEquals(0, stopped, 1e-12, "the dilation stops at the jump"),
            () -> assertEquals(103, guarded, 1e-9,
                "the guard is tick 104, the first sent at a boundary after the jump: snapshot 102 is ignored"),
            () -> assertEquals(103 + 2, second, 1e-9,
                "at snapshot 104 (the guard) a report of -1.5 ticks, no entry recorded: 2 short of the 0.5 target, +2"),
            () -> assertEquals(2, c.jumps(), "two jumps"),
            () -> assertEquals(0, steering, 1e-12, "snapshot 106 clears the new guard; a slack at the target: no dilation"));
    }

    @Test
    @DisplayName("the estimate adds the clock's own adjustment since the least-adjusted entry the report can cover")
    void estimate() {
        PredictedClock c = clock();
        c.snapshot(100, 0);
        c.tick(0);
        double none = c.estimate(100, 0.25);
        c.sent(102, 0);
        c.feedback(100, NO, true, 0, 0);
        c.sent(103, 0);
        double afterJump = c.estimate(102, -0.75);
        double outOfWindow = c.estimate(102 + 14, 0.5);
        assertAll(
            () -> assertEquals(0.25, none, 1e-12, "no entry sent: the report itself"),
            () -> assertEquals(0.25, afterJump, 1e-12,
                "entry 102 went out before the +1 jump: its late margin -0.75 is 0.25 now"),
            () -> assertEquals(0.5, outOfWindow, 1e-12,
                "only entries after snapshotTick - simHz count: 103 is the only one, sent after the jump"),
            () -> assertEquals(1, c.adjustment(), 1e-12, "the adjustment is the jump"));
    }

    @Test
    @DisplayName("the first simHz slack reports settle the clock once: the excess over the target goes at once")
    void settle() {
        PredictedClock c = clock();
        c.snapshot(100, 0);
        c.tick(0);
        for (int i = 0; i < HZ - 1; i++) {
            c.feedback(100, 32, false, 0, 0);
        }
        boolean before = c.settled();
        c.feedback(100, 32, false, 0, 0);
        double settled = c.tick(0);
        c.feedback(100, 32, false, 0, 0);
        double steady = c.dilation();
        PredictedClock d = clock();
        d.snapshot(100, 0);
        d.tick(0);
        for (int i = 0; i < HZ; i++) {
            d.feedback(100, 10, false, 0, 0);
        }
        assertAll(
            () -> assertFalse(before, "not before simHz reports"),
            () -> assertTrue(c.settled(), "settled at the 14th"),
            () -> assertEquals(1.5 - 0.0625, c.settledBy(), 1e-12, "slack 2 over a 0.5 target: down by 1.5 minus half the band"),
            () -> assertEquals(102 - 1.4375, settled, 1e-9, "the clock moved down"),
            () -> assertEquals(-0.03, steady, 1e-12,
                "no entry was recorded, so the next report of 2 ticks still reads as too much: full slow-down"),
            () -> assertTrue(d.settled(), "a slack inside the band settles too"),
            () -> assertEquals(0, d.settledBy(), 1e-12, "without moving"));
    }

    @Test
    @DisplayName("guard rails: a negative lead moves up to the initial lead; one over simHz - 1 ticks comes back")
    void guardRails() {
        PredictedClock c = clock();
        c.snapshot(100, 0);
        c.tick(0);
        c.snapshot(110, at(0.01));
        for (int i = 0; i < 4; i++) {
            c.snapshot(110, at(0.02 + i * 0.01));
        }
        double up = c.tick(at(1));
        long upResyncs = c.resyncs();
        PredictedClock d = clock();
        d.snapshot(100, 0);
        d.tick(0);
        for (int i = 0; i < 20; i++) {
            d.feedback(100, NO, true, 0, 0);
            d.feedback(200, NO, false, 0, 0);
        }
        double back = d.tick(0);
        d.tick(at(0.5));
        long stable = d.resyncs();
        assertAll(
            () -> assertEquals(1, stable, "after the resync the lead is in range: no further resync"),
            () -> assertTrue(up - 110 > 1, "the server estimate jumped ahead of the clock: the clock follows (" + up + ")"),
            () -> assertEquals(1, upResyncs, "counted"),
            () -> assertEquals(102, back, 1e-6, "20 jumps would lead by 22 ticks > 13: back to the initial lead"),
            () -> assertEquals(1, d.resyncs(), "counted"));
    }

    @Test
    @DisplayName("the estimate counts the dilation up to the report's arrival; sent() records the adjustment of its time")
    void dilationInEstimate() {
        PredictedClock c = clock();
        c.snapshot(100, 0);
        c.tick(0);
        c.feedback(100, -20, false, 0, 0);
        c.sent(102, 0);
        c.feedback(105, -20, false, 0, at(10));
        double estimate = c.slackEstimate();
        PredictedClock d = clock();
        d.snapshot(100, 0);
        d.tick(0);
        d.feedback(100, -20, false, 0, 0);
        d.sent(102, at(10));
        d.feedback(105, -20, false, 0, at(10));
        double later = d.slackEstimate();
        PredictedClock z = new PredictedClock();
        z.reset(0);
        assertAll(
            () -> assertEquals(-1.25 + 10 * 0.03, estimate, 1e-9,
                "10 ticks at +3% since entry 102 went out: the report plus 0.3"),
            () -> assertEquals(-1.25, later, 1e-9, "an entry sent at the report's time: the report itself"),
            () -> assertEquals(60, z.simHz(), 1e-12, "a rate of 0 means the 60 Hz default"));
    }

    @Test
    @DisplayName("feedback before the clock runs only records the slack; reset forgets everything")
    void resetAndIdle() {
        PredictedClock c = clock();
        c.feedback(1, 8, true, 0, 0);
        double slack = c.lastSlack();
        long jumps = c.jumps();
        c.snapshot(100, 0);
        c.tick(0);
        c.rtt(100 * MS, 0);
        c.feedback(100, NO, true, 0, 0);
        c.reset(HZ);
        assertAll(
            () -> assertEquals(0.5, slack, 1e-12, "the slack is recorded"),
            () -> assertEquals(0, jumps, "no jump before the clock runs"),
            () -> assertFalse(c.ready(), "reset: not ready"),
            () -> assertFalse(c.haveRtt(), "no RTT"),
            () -> assertEquals(0, c.jumps(), "no jumps"),
            () -> assertFalse(c.settled(), "not settled"),
            () -> assertTrue(Double.isNaN(c.lastSlack()), "no slack"),
            () -> assertEquals(HZ, c.simHz(), 1e-12, "the rate kept"),
            () -> assertEquals(60, new PredictedClock().simHz(), 1e-12, "60 Hz before a reset"));
    }
}
