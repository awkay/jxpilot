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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The I2P harness measures what it should (bug-hunter M1): on a clean link the same trial at RTT 100 ms takes
 * exactly 100 ms more than at RTT 0, and the stratified phases give the mean the stages add up to.
 */
@DisplayName("InputToPhoton calibration")
class InputToPhotonCalibrationTest {
    static final int TRIALS = 24;
    static final double FRAME_MS = 1000.0 / LatencyRig.FRAME_HZ;

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("per trial, I2P at RTT 100 minus I2P at RTT 0 is 100 ms within one frame; phases are stratified")
    void rttShiftsEveryTrial() {
        InputToPhoton.Result zero = InputToPhoton.measure(Course.WALLS,
            new Scenario(0, Scenario.Jitter.NONE, Scenario.Loss.NONE), TRIALS, 3);
        InputToPhoton.Result hundred = InputToPhoton.measure(Course.WALLS,
            new Scenario(100, Scenario.Jitter.NONE, Scenario.Loss.NONE), TRIALS, 3);
        double worst = 0;
        int compared = 0;
        boolean phasesMatch = true;
        boolean stratified = true;
        for (int j = 0; j < TRIALS; j++) {
            InputToPhoton.Trial a = zero.trials().get(j);
            InputToPhoton.Trial b = hundred.trials().get(j);
            phasesMatch &= a.phaseNanos() == b.phaseNanos();
            stratified &= a.phaseNanos() == InputToPhoton.arrivalPhase(j, TRIALS, 14);
            if (a.outcome() == InputToPhoton.Outcome.MEASURED && b.outcome() == InputToPhoton.Outcome.MEASURED) {
                worst = Math.max(worst, Math.abs(b.ms() - a.ms() - 100));
                compared++;
            }
        }
        double w = worst;
        int n = compared;
        boolean pm = phasesMatch;
        boolean st = stratified;
        // tick wait averages T/2 = 35.7 ms over stratified phases, plus about half a frame each for the key poll and
        // the draw: about 44 ms
        double mean = zero.mean();
        System.out.printf(java.util.Locale.ROOT, "[I2P calibration] worst |diff - 100| %.3f ms, mean at RTT 0 %.2f ms,"
            + " at RTT 100 %.2f ms%n", worst, mean, hundred.mean());
        assertAll(
            () -> assertEquals(TRIALS, n, "every trial measured at both RTTs"),
            () -> assertEquals(0, zero.invisible(), "at RTT 0 every I2P frame draws what the baseline does not"),
            () -> assertEquals(0, hundred.invisible(), "at RTT 100 too"),
            () -> assertEquals(0, zero.preT0Mismatches() + hundred.preT0Mismatches(), "runs identical before t0"),
            () -> assertTrue(st, "trial j arrives at phase (j + 1/2) / N of a tick"),
            () -> assertTrue(pm, "the same phases at both RTTs"),
            () -> assertTrue(w <= FRAME_MS, "every trial is exactly 100 ms later, within a frame: worst " + w + " ms"),
            () -> assertTrue(mean >= 40 && mean <= 48, "mean I2P at RTT 0 is about T/2 + one frame: " + mean));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("shots: per trial, shot I2P at RTT 100 minus RTT 0 is 100 ms within one frame; every press fires and"
        + " shows; the shot follows the own ship's SELF by the interpolation delay")
    void shotRttShiftsEveryTrial() {
        // Shot I2P = the own-ship path (calibrated by rttShiftsEveryTrial above, also in this default-build class)
        // + the interpolation delay. Its own mean bound below catches a regression in either part on its own.
        InputToPhoton.Result zero = InputToPhoton.measureShots(Course.FREE_FLIGHT,
            new Scenario(0, Scenario.Jitter.NONE, Scenario.Loss.NONE), TRIALS, 3);
        InputToPhoton.Result hundred = InputToPhoton.measureShots(Course.FREE_FLIGHT,
            new Scenario(100, Scenario.Jitter.NONE, Scenario.Loss.NONE), TRIALS, 3);
        double worst = 0;
        int compared = 0;
        for (int j = 0; j < TRIALS; j++) {
            InputToPhoton.Trial a = zero.trials().get(j);
            InputToPhoton.Trial b = hundred.trials().get(j);
            if (a.outcome() == InputToPhoton.Outcome.MEASURED && b.outcome() == InputToPhoton.Outcome.MEASURED) {
                worst = Math.max(worst, Math.abs(b.ms() - a.ms() - 100));
                compared++;
            }
        }
        double w = worst;
        int n = compared;
        Stats draw = zero.drawDelayStats();
        // the interpolation delay: 1 snapshot interval + 20 ms = 1.28 ticks = 91.4 ms at 14 Hz, seen at 120 Hz frames
        double delayMs = 1000.0 / 14 + 20;
        System.out.printf(java.util.Locale.ROOT, "[shot calibration] worst |diff - 100| %.3f ms, mean at RTT 0 %.2f ms,"
            + " own-ship part %.2f ms, shot after own ship %.1f..%.1f ms%n", worst, zero.mean(), zero.selfStats().p50(),
            draw.percentile(0), draw.max());
        assertAll(
            () -> assertEquals(TRIALS, n, "every press fired a shot that was drawn, at both RTTs"),
            // own-ship mean (40..48) + 11-12 frames (91.7..100): free flight measured 137.6
            () -> assertTrue(zero.mean() >= 130 && zero.mean() <= 148, "mean shot I2P at RTT 0: " + zero.mean()),
            () -> assertEquals(0, zero.invisible() + hundred.invisible(), "no shot drawn before a snapshot of its tick"),
            () -> assertEquals(0, zero.preT0Mismatches() + hundred.preT0Mismatches(), "runs identical before t0"),
            () -> assertTrue(w <= FRAME_MS, "every shot is exactly 100 ms later, within a frame: worst " + w + " ms"),
            // the render tick passes the shot's tick 1.28 ticks after the clock's estimate of the tick now, which runs
            // within a ms of the SELF's arrival; drawn on the next 120 Hz frame: 11 or 12 frames. Tolerance: a frame + 1 ms
            () -> assertTrue(draw.percentile(0) >= delayMs - FRAME_MS - 1 && draw.max() <= delayMs + FRAME_MS + 1,
                "each shot is drawn the interpolation delay (" + delayMs + " ms, within a frame) after the own ship's"
                    + " SELF of its tick: " + draw.percentile(0) + ".." + draw.max()));
    }
}
