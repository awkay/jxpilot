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
package com.fulcrologic.jxpilot.client.latency;

import com.fulcrologic.jxpilot.client.net.PredictedClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The I2P harness measures what it should (bug-hunter M1). Protocol v2 tags each input with the predicted tick, so
 * the tick a key lands on is the client's choice (its lead over the server), no longer its arrival phase; the
 * exact relation left to check is the harness's own arithmetic: every trial's I2P is the time to the server tick
 * the key reached plus the downlink, plus at most one frame for the draw. The stratified phases are still those
 * of the schedule. On a clean link the predicted clock settles before the first trial, at both RTTs to a slack
 * within the controller's deadband of the same target ({@link PredictedClock}), so RTT 100 adds 100 ms on average
 * within that band, and per trial within a tick.
 */
@DisplayName("InputToPhoton calibration")
class InputToPhotonCalibrationTest {
    static final int TRIALS = 24;
    static final double FRAME_MS = 1000.0 / LatencyRig.FRAME_HZ;
    static final double TICK_MS = 1000.0 / 14;

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("per trial, I2P is the time to the tick the key reached plus the downlink, within one frame; RTT 100 adds 100 ms")
    void tickPlusDownlink() {
        InputToPhoton.Result zero = InputToPhoton.measure(Course.WALLS,
            new Scenario(0, Scenario.Jitter.NONE, Scenario.Loss.NONE), TRIALS, 3);
        InputToPhoton.Result hundred = InputToPhoton.measure(Course.WALLS,
            new Scenario(100, Scenario.Jitter.NONE, Scenario.Loss.NONE), TRIALS, 3);
        double worst = 0;
        double low = Double.MAX_VALUE;
        double worstShift = 0;
        int compared = 0;
        boolean phasesMatch = true;
        boolean stratified = true;
        for (int j = 0; j < TRIALS; j++) {
            InputToPhoton.Trial a = zero.trials().get(j);
            InputToPhoton.Trial b = hundred.trials().get(j);
            phasesMatch &= a.phaseNanos() == b.phaseNanos();
            stratified &= a.phaseNanos() == InputToPhoton.arrivalPhase(j, TRIALS, 14);
            if (a.outcome() == InputToPhoton.Outcome.MEASURED && b.outcome() == InputToPhoton.Outcome.MEASURED) {
                double ra = a.ms() - a.tickMs();
                double rb = b.ms() - b.tickMs() - 50;
                worstShift = Math.max(worstShift, Math.abs(b.ms() - a.ms() - 100));
                worst = Math.max(worst, Math.max(ra, rb));
                low = Math.min(low, Math.min(ra, rb));
                compared++;
            }
        }
        double w = worst;
        double lo = low;
        double ws = worstShift;
        int n = compared;
        boolean pm = phasesMatch;
        boolean st = stratified;
        double shift = hundred.mean() - zero.mean();
        System.out.printf(java.util.Locale.ROOT, "[I2P calibration] I2P - (tick + downlink) in [%.3f, %.3f] ms;"
            + " mean at RTT 0 %.2f ms, at RTT 100 %.2f ms (shift %.2f)%n", low, worst, zero.mean(), hundred.mean(), shift);
        assertAll(
            () -> assertEquals(TRIALS, n, "every trial measured at both RTTs"),
            () -> assertEquals(0, zero.invisible(), "at RTT 0 every I2P frame draws what the baseline does not"),
            () -> assertEquals(0, hundred.invisible(), "at RTT 100 too"),
            () -> assertEquals(0, zero.preT0Mismatches() + hundred.preT0Mismatches(), "runs identical before t0"),
            () -> assertTrue(st, "trial j arrives at phase (j + 1/2) / N of a tick"),
            () -> assertTrue(pm, "the same phases at both RTTs"),
            () -> assertTrue(lo >= 0, "never drawn before the tick's snapshot can have arrived: " + lo + " ms"),
            () -> assertTrue(w <= FRAME_MS, "drawn within a frame of its arrival: worst " + w + " ms"),
            () -> assertTrue(ws <= TICK_MS + FRAME_MS, "every trial is 100 ms later within a tick: the settled clocks"
                + " agree within the deadband, which can move a key across a tick boundary: worst " + ws + " ms"),
            () -> assertEquals(100, shift, PredictedClock.DEADBAND * TICK_MS + FRAME_MS,
                "on average RTT 100 adds 100 ms within the controller's deadband"));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("shots: per press, the own ship shows it within a frame of tick + downlink and the shot follows by the"
        + " interpolation delay; every press fires and shows; RTT 100 adds 100 ms")
    void shotTickPlusDownlinkPlusDelay() {
        // as tickPlusDownlink above (protocol v2: the client's lead decides the landing tick), plus the shot's part
        InputToPhoton.Result zero = InputToPhoton.measureShots(Course.FREE_FLIGHT,
            new Scenario(0, Scenario.Jitter.NONE, Scenario.Loss.NONE), TRIALS, 3);
        InputToPhoton.Result hundred = InputToPhoton.measureShots(Course.FREE_FLIGHT,
            new Scenario(100, Scenario.Jitter.NONE, Scenario.Loss.NONE), TRIALS, 3);
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        int compared = 0;
        for (int j = 0; j < TRIALS; j++) {
            InputToPhoton.Trial a = zero.trials().get(j);
            InputToPhoton.Trial b = hundred.trials().get(j);
            if (a.outcome() == InputToPhoton.Outcome.MEASURED && b.outcome() == InputToPhoton.Outcome.MEASURED) {
                double ra = a.selfMs() - a.tickMs();
                double rb = b.selfMs() - b.tickMs() - 50;
                low = Math.min(low, Math.min(ra, rb));
                high = Math.max(high, Math.max(ra, rb));
                compared++;
            }
        }
        double lo = low;
        double hi = high;
        int n = compared;
        Stats draw = zero.drawDelayStats();
        Stats draw100 = hundred.drawDelayStats();
        double shift = hundred.mean() - zero.mean();
        // the interpolation delay: 1 snapshot interval + 20 ms = 1.28 ticks = 91.4 ms at 14 Hz; the render tick passes the
        // shot's tick 1.28 ticks after the clock's estimate of the tick now, which runs within a ms of the SELF's arrival;
        // drawn on the next 120 Hz frame: 11 or 12 frames. Tolerance: a frame + 1 ms
        double delayMs = 1000.0 / 14 + 20;
        System.out.printf(java.util.Locale.ROOT, "[shot calibration] SELF - (tick + downlink) in [%.3f, %.3f] ms, shot"
            + " after own ship %.1f..%.1f ms; mean at RTT 0 %.2f ms, at RTT 100 %.2f ms (shift %.2f)%n", low, high,
            Math.min(draw.percentile(0), draw100.percentile(0)), Math.max(draw.max(), draw100.max()), zero.mean(),
            hundred.mean(), shift);
        assertAll(
            () -> assertEquals(TRIALS, n, "every press fired a shot that was drawn, at both RTTs"),
            () -> assertEquals(0, zero.invisible() + hundred.invisible(), "no shot drawn before a snapshot of its tick"),
            () -> assertEquals(0, zero.preT0Mismatches() + hundred.preT0Mismatches(), "runs identical before t0"),
            () -> assertTrue(lo >= 0 && hi <= FRAME_MS, "the own ship shows the press within a frame of its tick's"
                + " snapshot arriving: " + lo + ".." + hi + " ms"),
            () -> assertTrue(Math.min(draw.percentile(0), draw100.percentile(0)) >= delayMs - FRAME_MS - 1
                && Math.max(draw.max(), draw100.max()) <= delayMs + FRAME_MS + 1,
                "each shot is drawn the interpolation delay (" + delayMs + " ms, within a frame) after the own ship's"
                    + " SELF of its tick, at both RTTs"),
            () -> assertEquals(100, shift, PredictedClock.DEADBAND * TICK_MS + FRAME_MS,
                "on average RTT 100 adds 100 ms within the controller's deadband"));
    }
}
