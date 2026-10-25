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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With prediction on, other players' and cannons' bullets are drawn dead-reckoned to the own ship's predicted tick
 * (where they will be when the player's input lands); off, interpolated an interpolation delay in the past. Against
 * the real server on the dogfight course with robots firing, RTT 100 ({@link ThreatMetrics}).
 */
class PredictedShotsTest {
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @DisplayName("on: drawn bullets within a few px of the server's at the input's tick; off: tens of px behind")
    void threat() {
        Scenario s = new Scenario(100, Scenario.Jitter.NONE, Scenario.Loss.NONE);
        ThreatMetrics.Result on = LatencyRig.withPrediction(true, () -> ThreatMetrics.measure(s, 6, 20, 1));
        ThreatMetrics.Result off = LatencyRig.withPrediction(false, () -> ThreatMetrics.measure(s, 6, 20, 1));
        assertAll(
            () -> assertTrue(on.drawnShots() > 1000 && off.drawnShots() > 1000, "bullets drawn: " + on.drawnShots()
                + ", " + off.drawnShots()),
            () -> assertTrue(on.threatError().p50() < 3, "on: p50 " + on.threatError().p50()),
            () -> assertTrue(on.threatError().p95() < 30, "on: p95 " + on.threatError().p95()),
            () -> assertTrue(off.threatError().p50() > 30, "off: p50 " + off.threatError().p50()),
            () -> assertTrue(on.goneByThen() * 2 < off.goneByThen(), "on: fewer drawn after their end: "
                + on.goneByThen() + " vs " + off.goneByThen()));
    }
}
