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
package com.fulcrologic.jxpilot.server.robot;

import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.server.TestWorlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The stuck-run split of {@link RobotStats} (the regression test's thrust comparison relies on it). */
class RobotStatsTest {
    private static final int ALIVE = RobotStats.PLAYING;
    private static final int THRUST = RobotStats.PLAYING | RobotStats.THRUSTING;

    private static RobotStats stats() {
        return new RobotStats(WorldMap.fromOptions(TestWorlds.options("", RobotFixture.open(20, 20, "_"))), 12);
    }

    @Test
    @DisplayName("120+ frames within 3 px are stuck, shorter runs are moving; thrust is split between them")
    void split() {
        RobotStats s = stats();
        long t = 1;
        for (int i = 0; i < 120; i++) {
            s.sample(t++, 1, "A", 100 + (i % 4), 100, 0, 0, 1000 * 256, i < 30 ? THRUST : ALIVE);
        }
        for (int i = 0; i < 119; i++) {
            s.sample(t++, 1, "A", 300, 300, 0, 0, 1000 * 256, i < 60 ? THRUST : ALIVE);
        }
        s.sample(t++, 1, "A", 310, 300, 0, 0, 1000 * 256, THRUST);
        Properties p = s.summary();
        assertAll(
            () -> assertEquals(120, s.stuckFrames, "the first run (x moves 100..103, 3 px) is stuck"),
            () -> assertEquals(30, s.stuckThrustFrames, "its thrust frames"),
            () -> assertEquals(120, s.movingFrames, "119 frames are not enough, plus the frame that moved away"),
            () -> assertEquals(61, s.movingThrustFrames, "their thrust frames"),
            () -> assertEquals("0.5000", p.getProperty("stuckShare"), "stuckShare"),
            () -> assertEquals("0.2500", p.getProperty("stuckThrustFraction"), "stuckThrustFraction"),
            () -> assertEquals(RobotStats.fmt(61 / 120.0), p.getProperty("movingThrustFraction"), "movingThrustFraction"));
    }

    @Test
    @DisplayName("a death, a gap in frames or a new robot in the slot ends the run; add pools two runs")
    void breaks() {
        RobotStats s = stats();
        for (int i = 0; i < 100; i++) {
            s.sample(i + 1, 1, "A", 100, 100, 0, 0, 0, ALIVE);
        }
        s.sample(101, 1, "A", 100, 100, 0, 0, 0, 0);
        for (int i = 0; i < 100; i++) {
            s.sample(102 + i, 1, "A", 100, 100, 0, 0, 0, ALIVE);
        }
        for (int i = 0; i < 100; i++) {
            s.sample(205 + i, 1, "A", 100, 100, 0, 0, 0, ALIVE);
        }
        for (int i = 0; i < 100; i++) {
            s.sample(305 + i, 1, "B", 100, 100, 0, 0, 0, ALIVE);
        }
        RobotStats other = stats();
        for (int i = 0; i < 130; i++) {
            other.sample(i + 1, 2, "C", 50, 50, 0, 0, 0, THRUST);
        }
        s.add(other);
        assertAll(
            () -> assertEquals(130, s.stuckFrames, "only the other run's 130 frames are stuck"),
            () -> assertEquals(130, s.stuckThrustFrames, "with their thrust"),
            () -> assertEquals(400, s.movingFrames, "four broken runs of 100"),
            () -> assertEquals(530, s.aliveFrames, "pooled alive frames"));
    }
}
