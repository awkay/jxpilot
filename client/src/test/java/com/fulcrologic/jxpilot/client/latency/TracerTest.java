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
 * and how many cannon bullets' tracers start at their cannon.
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
        LatencyRig.withPrediction(true, () -> {
            LatencyRig rig = new LatencyRig(Course.DOGFIGHT.serverArgs(6), s.direction(), s.direction(), 1,
                InputToPhoton.tickPhase(1));
            long spawn = rig.runUntilPlaying(LatencyRig.START + InputToPhoton.SPAWN_LIMIT);
            List<Cannon> cannons = rig.server.world().map.cannons();
            // a tracer drawn less than 1.5 render frames after its bullet's first sight is that bullet's first frame
            float firstAlpha = 1f - 1.5f * (1e9f / LatencyRig.FRAME_HZ) / ClientWorld.TRACER_NANOS;
            rig.onFrame = (r, t) -> {
                ClientWorld w = r.world();
                tracersSeen[0] += w.tracerCount();
                for (int i = 0; i < w.tracerCount(); i++) {
                    if (w.tracerAlpha(i) < firstAlpha) {
                        continue;
                    }
                    stats[0]++;
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
            };
            rig.runUntil(spawn + 20 * LatencyRig.SEC);
            return null;
        });
        Stats l = new Stats(len, nl[0]);
        System.out.printf(java.util.Locale.ROOT, "TRACER first-sight tracers %d (cannon %d, %d at their cannon),"
                + " length p50 %.1f px p95 %.1f px; tracer-frames %d%n", stats[0], stats[1], stats[2], l.p50(),
            l.p95(), tracersSeen[0]);
        assertAll(
            () -> assertTrue(stats[0] > 50, "new bullets got a tracer at first sight: " + stats[0]),
            () -> assertTrue(stats[3] == stats[0], "at full alpha (> 0.9): " + stats[3] + " of " + stats[0]),
            () -> assertTrue(l.p50() > 10, "bridging the gap the bullet would pop across: p50 " + l.p50() + " px"),
            () -> assertTrue(stats[1] > 10, "cannon bullets among them: " + stats[1]),
            () -> assertTrue(stats[2] * 10 >= stats[1] * 9, "nine in ten cannon bullets' tracers start at a cannon: "
                + stats[2] + " of " + stats[1]),
            () -> assertTrue(tracersSeen[0] > 2L * stats[0], "a tracer stays for several frames (fading): "
                + tracersSeen[0]));
    }
}
