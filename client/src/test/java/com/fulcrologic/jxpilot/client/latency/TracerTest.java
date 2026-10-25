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

import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.Records;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With prediction on, bullets are drawn dead-reckoned to the own ship's predicted tick, so a new one is first drawn
 * RTT plus the lead of flight away from where it was fired. Each new bullet gets a tracer from its source to where it
 * is drawn ({@link ClientWorld#tracerCount}). Against the real server on the dogfight course with robots and cannons
 * firing, RTT 100. Prints {@code TRACER}: first-sight tracers, how far they reach (the gap a bullet would pop across),
 * and how many cannon bullets' tracers start at their cannon; and the precision against the server's truth: tracers
 * whose bullet was fired more than a snapshot interval plus the RTT before the frame (an old bullet the tracker failed
 * to link to its earlier records, so it looked new).
 */
class TracerTest {
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @DisplayName("each new bullet's first drawn frame has a tracer from its source across the gap, at full alpha;"
        + " cannon bullets' tracers start at their cannon")
    void tracers() {
        Scenario s = new Scenario(100, Scenario.Jitter.NONE, Scenario.Loss.NONE);
        int[] stats = new int[4]; // first-sight tracers, cannon ones, cannon ones at a cannon, full alpha
        double[] len = new double[1 << 16];
        int[] nl = {0};
        long[] tracersSeen = {0};
        // per bullet: tracer frames not matched to the truth, old ones drawn before (tracker misses), fresh ones,
        // old ones first drawn then (entered the view); old = fired more than an interval plus the RTT before
        int[] precision = new int[4];
        java.util.Map<Integer, Long> lastEvent = new java.util.HashMap<>(); // bullet id: last first-sight frame
        long[] frame = {0};
        int[] limit = {0}; // "old": fired more than this many ticks before the frame (interval + RTT at the sim rate)
        int[] events = {0};
        java.util.Set<Integer> drawnIds = new java.util.HashSet<>();
        LatencyRig.withPrediction(true, () -> {
            LatencyRig rig = new LatencyRig(Course.DOGFIGHT.serverArgs(6), s.direction(), s.direction(), 1,
                InputToPhoton.tickPhase(1));
            long spawn = rig.runUntilPlaying(LatencyRig.START + InputToPhoton.SPAWN_LIMIT);
            List<Cannon> cannons = rig.server.world().map.cannons();
            // a tracer drawn less than 1.5 render frames after its bullet's first sight is that bullet's first frame
            float firstAlpha = 1f - 1.5f * (1e9f / LatencyRig.FRAME_HZ) / ClientWorld.TRACER_NANOS;
            ServerTruth truth = new ServerTruth(rig.client.connection().playerId());
            rig.onTick = (r, t) -> truth.recordOtherShots(r.server.world(), r.server.net.currentTick());
            rig.onFrame = (r, t) -> {
                ClientWorld w = r.world();
                tracersSeen[0] += w.tracerCount();
                int serverTick = r.server.net.currentTick();
                int oldLimit = (int) Math.ceil(w.snapshotIntervalTicks() + s.rttMs() * r.client.game().simHz() / 1000.0);
                limit[0] = oldLimit;
                int[] frameIds = new int[w.shotCount()];
                for (int k = 0; k < w.shotCount(); k++) {
                    frameIds[k] = w.shotSourceTick(k) == Integer.MIN_VALUE ? -1 : truth.otherShotIdAt(
                        w.shotSourceTick(k), w.shotSourceX(k), w.shotSourceY(k), 1, w.worldWidth(), w.worldHeight(),
                        w.wraps());
                }
                for (int i = 0; i < w.tracerCount(); i++) {
                    if (w.tracerAlpha(i) < firstAlpha) {
                        continue;
                    }
                    stats[0]++;
                    // its bullet: the drawn shot at the tracer's end, then the server bullet its record stands for
                    int id = -1;
                    for (int k = 0; k < w.shotCount(); k++) {
                        if (w.shotX(k) == w.tracerX2(i) && w.shotY(k) == w.tracerY2(i)) {
                            id = frameIds[k];
                            break;
                        }
                    }
                    int first = id < 0 ? Integer.MIN_VALUE : truth.shotFirstTick(id);
                    if (first == Integer.MIN_VALUE) {
                        precision[0]++; // unmatched
                    } else if (frame[0] - lastEvent.getOrDefault(id, Long.MIN_VALUE / 2) > 3) {
                        // a tracer event: the first of its (one or two) first-sight frames
                        events[0]++;
                        if (serverTick - first <= oldLimit) {
                            precision[2]++;
                        } else if (drawnIds.contains(id)) {
                            precision[1]++; // old, and drawn before: the tracker lost it and it looked new
                        } else {
                            precision[3]++; // old, first drawn now: it flew into the view
                        }
                    }
                    if (id >= 0) {
                        lastEvent.put(id, frame[0]);
                    }
                    stats[3] += w.tracerAlpha(i) > 0.9f ? 1 : 0;
                    double dx = w.tracerX2(i) - w.tracerX1(i);
                    double dy = w.tracerY2(i) - w.tracerY1(i);
                    if (nl[0] < len.length) {
                        len[nl[0]++] = Math.hypot(dx, dy);
                    }
                    if (Records.ShotKind.kindOf(w.tracerVariant(i)) == Records.ShotKind.CANNON_SHOT) {
                        stats[1]++;
                        for (Cannon c : cannons) {
                            double ex = XMath.wrapDelta(c.x() - w.tracerX1(i), w.worldWidth(), w.wraps());
                            double ey = XMath.wrapDelta(c.y() - w.tracerY1(i), w.worldHeight(), w.wraps());
                            if (Math.hypot(ex, ey) <= 1) {
                                stats[2]++;
                                break;
                            }
                        }
                    }
                }
                for (int id : frameIds) {
                    drawnIds.add(id);
                }
                frame[0]++;
            };
            rig.runUntil(spawn + 20 * LatencyRig.SEC);
            return null;
        });
        Stats l = new Stats(len, nl[0]);
        System.out.printf(java.util.Locale.ROOT, "TRACER first-sight tracers %d (cannon %d, %d at their cannon),"
                + " length p50 %.1f px p95 %.1f px; tracer-frames %d; precision per tracer (old > %d ticks): %d fresh, %d old"
                + " and drawn before (tracker misses), %d old entering the view; %d tracer frames unmatched%n",
            stats[0], stats[1], stats[2], l.p50(), l.p95(), tracersSeen[0], limit[0], precision[2], precision[1], precision[3],
            precision[0]);
        assertAll(
            () -> assertTrue(stats[0] > 50, "new bullets got a tracer at first sight: " + stats[0]),
            () -> assertTrue(stats[3] == stats[0], "at full alpha (> 0.9): " + stats[3] + " of " + stats[0]),
            () -> assertTrue(l.p50() > 10, "bridging the gap the bullet would pop across: p50 " + l.p50() + " px"),
            () -> assertTrue(stats[1] > 10, "cannon bullets among them: " + stats[1]),
            () -> assertTrue(stats[2] * 10 >= stats[1] * 9, "nine in ten cannon bullets' tracers start at a cannon: "
                + stats[2] + " of " + stats[1]),
            () -> assertTrue(tracersSeen[0] > 2L * stats[0], "a tracer stays for several frames (fading): "
                + tracersSeen[0]),
            () -> assertTrue(precision[0] * 20 <= stats[0], "nearly every tracer's bullet is found in the server's"
                + " truth: " + precision[0] + " unmatched of " + stats[0]),
            () -> assertTrue(precision[1] * 100 <= events[0], "precision: at most 1 in 100 tracers is on an"
                + " old bullet drawn before (fired more than a snapshot interval plus the RTT before, the tracker lost"
                + " it and it got a second tracer): " + precision[1] + " of " + events[0]),
            () -> assertTrue(precision[3] * 20 <= events[0], "old bullets flying into the view (a true first"
                + " sight) are few: " + precision[3] + " of " + events[0]),
            () -> assertTrue(events[0] > 100, "tracer events judged: " + events[0]));
    }
}
