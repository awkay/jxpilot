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
}
