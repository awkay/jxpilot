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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.map.MapState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Frame pacing (R13) and radar target changes (R14)")
class PacingAndRadarTest {
    @Test
    @DisplayName("FramePacer: vsync floor; cap at 60 fps when hidden or after 30 non-blocking swaps; no cap without vsync")
    void pacer() {
        FramePacer p = new FramePacer();
        long blocking = p.minFrameNanos(true, false, 16_000_000L);
        long hidden = p.minFrameNanos(true, true, 16_000_000L);
        long fast = 0;
        for (int i = 0; i < FramePacer.FAST_SWAPS - 1; i++) {
            fast = p.minFrameNanos(true, false, 100_000L);
        }
        long thirtieth = p.minFrameNanos(true, false, 100_000L);
        long noVsync = p.minFrameNanos(false, true, 100_000L);
        long reset = p.minFrameNanos(true, false, 2_000_000L);
        long f = fast;
        assertAll(
            () -> assertEquals(FramePacer.VSYNC_FLOOR_NANOS, blocking, "a blocking swap: only the 250 fps floor"),
            () -> assertEquals(FramePacer.CAP_NANOS, hidden, "an iconified window: 60 fps"),
            () -> assertEquals(FramePacer.VSYNC_FLOOR_NANOS, f, "29 fast swaps: not yet"),
            () -> assertEquals(FramePacer.CAP_NANOS, thirtieth, "30 fast swaps: vsync does not block, 60 fps"),
            () -> assertEquals(0L, noVsync, "--no-vsync: uncapped"),
            () -> assertEquals(FramePacer.VSYNC_FLOOR_NANOS, reset, "a blocking swap resets the count"));
    }

    /** Targets alive per a boolean array. */
    record Targets(boolean[] alive) implements MapState {
        public double fuel(int i) { return 0; }
        public boolean cannonAlive(int i) { return true; }
        public boolean targetAlive(int i) { return alive[i]; }
        public double targetDamage(int i) { return 0; }
        public int selfTeam() { return 0; }
        public int nextCheckpoint() { return 0; }
        public long loops() { return 0; }
    }

    @Test
    @DisplayName("TargetWatch: a change of any of 100 targets is noticed once; a new map is a change")
    void watch() {
        boolean[] alive = new boolean[100];
        java.util.Arrays.fill(alive, true);
        Targets st = new Targets(alive);
        TargetWatch w = new TargetWatch();
        w.reset(100);
        boolean first = w.changed(st);
        boolean same = w.changed(st);
        alive[90] = false;
        boolean target90 = w.changed(st);
        boolean again = w.changed(st);
        w.reset(100);
        boolean newMap = w.changed(st);
        assertAll(
            () -> assertTrue(first, "after reset: rebuild"),
            () -> assertFalse(same, "nothing changed"),
            () -> assertTrue(target90, "target 90 (beyond the old 64-bit key) died"),
            () -> assertFalse(again, "reported once"),
            () -> assertTrue(newMap, "reset reports again"));
    }
}
