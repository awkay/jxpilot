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

import com.fulcrologic.jxpilot.client.JitQuiet;
import com.fulcrologic.jxpilot.client.game.predict.OwnShipPredictor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game frame with the own ship predicted allocates nothing in steady state: the predictor's reconciliations,
 * replays and speculative steps, the predicted ship's drawing and exhaust, against the real server over a jittery,
 * lossy link with robots around (so PREDICTs disagree now and then and the ring is replayed), while the pilot turns,
 * thrusts and fires. Only the game frame is measured ({@link LatencyRig#gameFrameMeter}); the connection's receive
 * and send are ClientNetAllocationTest's. After a warm-up until the JIT is quiet ({@link JitQuiet}), at most
 * {@value #MAX_NONZERO_FRAMES} frames may allocate (at most {@value #MAX_FRAME_BYTES} bytes each) and none in the
 * second half. Runs in the net allocation execution, escape analysis off.
 */
class PredictNetAllocationTest {
    static final int MAX_NONZERO_FRAMES = 3;
    static final long MAX_FRAME_BYTES = 1024;
    static final long MS = 1_000_000L;
    static final long WARMUP_SECONDS = 400;
    static final long MEASURED_SECONDS = 300;

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @DisplayName("steady-state game frames with prediction, replays and exhaust allocate nothing")
    void predictedFramesAllocateNothing() throws Exception {
        Scenario s = new Scenario(100, Scenario.Jitter.NORMAL_15, Scenario.Loss.UNIFORM_1);
        LatencyRig rig = new LatencyRig(Course.DOGFIGHT.serverArgs(4), s.direction(), s.direction(), 5,
            InputToPhoton.tickPhase(5));
        rig.setKeys(t -> Course.pilot(t - LatencyRig.START));
        long[] stats = new long[4];
        int[] frame = {0};
        boolean[] measuring = {false};
        long[] active = {0};
        OwnShipPredictor p = rig.client.game().predictor();
        rig.gameFrameMeter = d -> {
            frame[0]++;
            if (measuring[0]) {
                if (p.active()) {
                    active[0]++;
                }
                if (d != 0) {
                    stats[0]++;
                    stats[1] += d;
                    stats[2] = Math.max(stats[2], d);
                    stats[3] = frame[0];
                }
            }
        };
        long[] until = {LatencyRig.START + WARMUP_SECONDS * LatencyRig.SEC};
        rig.runUntil(until[0]);
        try (JitQuiet jit = new JitQuiet()) {
            jit.warm(() -> {
                until[0] += LatencyRig.SEC / LatencyRig.FRAME_HZ;
                rig.runUntil(until[0]);
            }, 1000, 2000, 40_000, 2);
        }
        long correctionsBefore = p.corrections();
        long reconBefore = p.reconciliations();
        int start = frame[0];
        measuring[0] = true;
        rig.runUntil(until[0] + MEASURED_SECONDS * LatencyRig.SEC);
        int measured = frame[0] - start;
        long corrections = p.corrections() - correctionsBefore;
        long recon = p.reconciliations() - reconBefore;
        long act = active[0];
        String detail = stats[0] + " frames allocated " + stats[1] + " B, worst " + stats[2] + " B, last at frame "
            + stats[3] + " (measured from " + start + ")";
        long nonZero = stats[0];
        long worst = stats[2];
        long last = stats[3];
        assertAll(
            () -> assertTrue(act * 4 > measured, "the ship was predicted in over a quarter of the frames: " + act
                + " of " + measured),
            () -> assertTrue(recon > 500, "PREDICTs were compared while measured: " + recon),
            () -> assertTrue(corrections > 10, "and some corrected (replays): " + corrections),
            () -> assertTrue(nonZero <= MAX_NONZERO_FRAMES, "at most 3 allocating frames: " + detail),
            () -> assertTrue(worst <= MAX_FRAME_BYTES, "each at most 1 KB: " + detail),
            () -> assertTrue(last < start + measured / 2 || nonZero == 0, "none in the second half: " + detail));
    }
}
