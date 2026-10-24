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
package com.fulcrologic.jxpilot.server.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The laser pulse list (C Pulses[] / NumPulses, Laser_pulse_destroy_one's compaction). */
@DisplayName("Pulses (C Pulses[])")
class PulsesTest {
    /** Fill pulse {@code i} with values derived from {@code v}. */
    static void fill(Pulses p, int i, int v) {
        p.x[i] = v + 0.5f;
        p.y[i] = v + 0.25f;
        p.dir[i] = v + 1;
        p.len[i] = v + 2;
        p.life[i] = v + 3;
        p.owner[i] = v + 4;
        p.team[i] = v + 5;
        p.mods[i] = v + 6;
        p.refl[i] = (v & 1) == 1;
    }

    @Nested
    @DisplayName("add")
    class Add {
        @Test
        @DisplayName("hands out 0, 1, ... and -1 once MAX_TOTAL_PULSES (5*64) are in use")
        void untilFull() {
            Pulses p = new Pulses();
            int first = p.add();
            int second = p.add();
            for (int i = 2; i < Pulses.CAPACITY; i++) {
                p.add();
            }
            int full = p.add();
            assertAll(
                () -> assertEquals(320, Pulses.CAPACITY, "the capacity is C MAX_TOTAL_PULSES = 5 * 64 (serverconst.h:147)"),
                () -> assertEquals(0, first, "the first pulse is index 0"),
                () -> assertEquals(1, second, "the second pulse is index 1"),
                () -> assertEquals(-1, full, "a full list returns -1 (Fire_general_laser returns)"),
                () -> assertEquals(Pulses.CAPACITY, p.count(), "the count stays at the capacity"));
        }
    }

    @Nested
    @DisplayName("remove / clear")
    class Remove {
        @Test
        @DisplayName("removing a middle pulse moves the last one into its slot (every field)")
        void middle() {
            Pulses p = new Pulses();
            for (int i = 0; i < 3; i++) {
                fill(p, p.add(), 10 * i);
            }
            p.remove(0);
            assertAll(
                () -> assertEquals(2, p.count(), "the count drops by one"),
                () -> assertEquals(20.5f, p.x[0], "x of the last pulse moves to slot 0"),
                () -> assertEquals(20.25f, p.y[0], "y moves"),
                () -> assertEquals(21, p.dir[0], "dir moves"),
                () -> assertEquals(22, p.len[0], "len moves"),
                () -> assertEquals(23, p.life[0], "life moves"),
                () -> assertEquals(24, p.owner[0], "owner moves"),
                () -> assertEquals(25, p.team[0], "team moves"),
                () -> assertEquals(26, p.mods[0], "mods moves"),
                () -> assertFalse(p.refl[0], "refl moves (pulse 2 is not reflected)"),
                () -> assertEquals(11, p.dir[1], "the pulse in slot 1 stays"));
        }

        @Test
        @DisplayName("refl is copied when true; removing the last pulse copies nothing")
        void lastAndRefl() {
            Pulses p = new Pulses();
            fill(p, p.add(), 0);
            fill(p, p.add(), 1);
            fill(p, p.add(), 3);
            p.remove(0);
            boolean moved = p.refl[0];
            p.dir[1] = 99;
            p.remove(1);
            assertAll(
                () -> assertTrue(moved, "the reflected flag of the moved pulse is copied"),
                () -> assertEquals(1, p.count(), "removing the last pulse only drops the count"),
                () -> assertEquals(4, p.dir[0], "the remaining pulse is the one moved from the end"),
                () -> assertEquals(99, p.dir[1], "the removed last slot is not overwritten"));
        }

        @Test
        @DisplayName("clear empties the list; adding starts at 0 again")
        void clear() {
            Pulses p = new Pulses();
            p.add();
            p.add();
            p.clear();
            int again = p.add();
            assertAll(
                () -> assertEquals(0, again, "the next pulse is index 0"),
                () -> assertEquals(1, p.count(), "only it is counted"));
        }
    }
}
