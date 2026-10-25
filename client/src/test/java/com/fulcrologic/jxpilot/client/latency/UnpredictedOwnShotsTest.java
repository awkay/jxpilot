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
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Records;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Own bullets the client did not predict are still drawn (bug-hunter M1): the uplink's delay steps from 50 to 400 ms
 * while the pilot taps FIRE, so presses land late (the server starves and holds the previous keys) and the server
 * fires bullets the predictor fired a tick earlier or not at all. Every own server bullet that has been in a snapshot
 * for a while must be drawn near where the server has it at the frame's input tick.
 */
class UnpredictedOwnShotsTest {
    static final long MS = 1_000_000L;
    /** Bullets this close to the own ship are in every snapshot's view. */
    static final double VIEW_PX = 300;

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("after an uplink step, every own server bullet is drawn, predicted or not")
    void drawn() {
        Scenario s = new Scenario(100, Scenario.Jitter.NONE, Scenario.Loss.NONE);
        LatencyRig rig = new LatencyRig(Course.FREE_FLIGHT.serverArgs(0), s.direction(), s.direction(), 4,
            InputToPhoton.tickPhase(4));
        long spawn = rig.runUntilPlaying(LatencyRig.START + InputToPhoton.SPAWN_LIMIT);
        rig.setKeys(t -> {
            long since = t - spawn;
            long k = since >= 100 * MS && since < 200 * MS ? ControlKey.SHIELD.mask() : 0;
            long phase = (since / MS) % 330;
            return since > 1000 * MS && phase < 60 ? k | ControlKey.FIRE_SHOT.mask() : k;
        });
        ServerTruth own = new ServerTruth(rig.client.connection().playerId());
        Map<Integer, Integer> firstTick = new HashMap<>();
        rig.onTick = (r, t) -> {
            int tick = r.server.net.currentTick();
            own.recordOwnShots(r.server.world(), tick);
            for (int id : own.otherShotIds(tick)) {
                firstTick.putIfAbsent(id, tick);
            }
        };
        long step = spawn + 3 * LatencyRig.SEC;
        java.util.List<double[]> frames = new java.util.ArrayList<>();
        double[] world = new double[3];
        rig.onFrame = (r, t) -> {
            if (t >= step && t - step < LatencyRig.SEC / LatencyRig.FRAME_HZ) {
                r.link.up().profile().delayMs(400);
            }
            ClientWorld w = r.world();
            world[0] = w.worldWidth();
            world[1] = w.worldHeight();
            world[2] = w.wraps() ? 1 : 0;
            if (t < step + LatencyRig.SEC || !(w.hasSelf() && w.selfPlaying() && w.selfPredicted())) {
                return;
            }
            // per frame: the input's tick, the newest snapshot tick, then the drawn own shots' positions
            int nOwn = 0;
            for (int i = 0; i < w.shotCount(); i++) {
                nOwn += w.shotColor(i) == Records.ObjColor.OWN ? 1 : 0;
            }
            double[] f = new double[4 + 2 * nOwn];
            f[0] = r.client.connection().predictedTick(t);
            f[1] = w.newestTick();
            f[2] = w.selfX();
            f[3] = w.selfY();
            int k = 4;
            for (int i = 0; i < w.shotCount(); i++) {
                if (w.shotColor(i) == Records.ObjColor.OWN) {
                    f[k++] = w.shotX(i);
                    f[k++] = w.shotY(i);
                }
            }
            frames.add(f);
        };
        rig.runUntil(step + 8 * LatencyRig.SEC);
        long[] judged = {0};
        long[] missing = {0};
        Set<Integer> missedIds = new HashSet<>();
        double[] p = new double[2];
        boolean wrap = world[2] != 0;
        for (double[] f : frames) {
            int newest = (int) f[1];
            // judged once the bullet has been in snapshots for 3 ticks, at the tick the frame's input lands
            for (int id : own.otherShotIds(newest - 1)) {
                Integer born = firstTick.get(id);
                if (born == null || newest - born < 3 || !own.otherShotAt(id, f[0], world[0], world[1], wrap, p)
                    || MotionMetrics.distance(f[2], f[3], p[0], p[1], world[0], world[1], wrap) > VIEW_PX) {
                    continue; // young (not in a snapshot yet), gone by then, or out of the view the snapshots cover
                }
                judged[0]++;
                boolean seen = false;
                for (int k = 4; k < f.length && !seen; k += 2) {
                    seen = MotionMetrics.distance(f[k], f[k + 1], p[0], p[1], world[0], world[1], wrap) < 12;
                }
                if (!seen) {
                    missing[0]++;
                    missedIds.add(id);
                }
            }
        }
        long j = judged[0];
        long m = missing[0];
        assertAll(
            () -> assertTrue(j > 500, "own bullets judged: " + j),
            () -> assertTrue(m * 100 <= j, "at most 1% of judged bullet-frames not drawn near the server's: " + m
                + " of " + j + " (bullets " + missedIds + ")"));
    }
}
