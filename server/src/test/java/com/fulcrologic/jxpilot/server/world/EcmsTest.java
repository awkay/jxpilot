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

/** ECM blasts and transporter beams (C Ecms[] / Transporters[]). */
@DisplayName("Ecms (C Ecms[], Transporters[])")
class EcmsTest {
    @Nested
    @DisplayName("ECM blasts")
    class Blasts {
        @Test
        @DisplayName("add hands out 0.. and -1 once MAX_TOTAL_ECMS (64) exist")
        void untilFull() {
            Ecms e = new Ecms();
            int first = e.add();
            for (int i = 1; i < Ecms.CAPACITY; i++) {
                e.add();
            }
            int full = e.add();
            assertAll(
                () -> assertEquals(64, Ecms.CAPACITY, "the capacity is C MAX_TOTAL_ECMS (serverconst.h:148)"),
                () -> assertEquals(0, first, "the first blast is index 0"),
                () -> assertEquals(-1, full, "a full list returns -1"),
                () -> assertEquals(64, e.count(), "the count stays at the capacity"));
        }

        @Test
        @DisplayName("remove moves the last blast into the slot; removing the last only drops the count")
        void remove() {
            Ecms e = new Ecms();
            for (int i = 0; i < 3; i++) {
                int k = e.add();
                e.x[k] = 10 + i;
                e.y[k] = 20 + i;
                e.size[k] = 30 + i;
                e.owner[k] = 40 + i;
            }
            e.remove(0);
            e.size[1] = 77;
            e.remove(1);
            assertAll(
                () -> assertEquals(1, e.count(), "two removals leave one blast"),
                () -> assertEquals(12f, e.x[0], "x of the last blast moved to slot 0"),
                () -> assertEquals(22f, e.y[0], "y moved"),
                () -> assertEquals(32, e.size[0], "size moved"),
                () -> assertEquals(42, e.owner[0], "owner moved"),
                () -> assertEquals(77, e.size[1], "removing the last slot overwrites nothing"));
        }
    }

    @Nested
    @DisplayName("transporter beams")
    class Transporters {
        @Test
        @DisplayName("addTransporter hands out 0.. and -1 once MAX_TOTAL_TRANSPORTERS (128) exist")
        void untilFull() {
            Ecms e = new Ecms();
            int first = e.addTransporter();
            for (int i = 1; i < Ecms.TRANSPORTER_CAPACITY; i++) {
                e.addTransporter();
            }
            int full = e.addTransporter();
            assertAll(
                () -> assertEquals(128, Ecms.TRANSPORTER_CAPACITY, "the capacity is C MAX_TOTAL_TRANSPORTERS = 2*64"),
                () -> assertEquals(0, first, "the first beam is index 0"),
                () -> assertEquals(-1, full, "a full list returns -1"),
                () -> assertEquals(128, e.transporters(), "the count stays at the capacity"));
        }

        @Test
        @DisplayName("removeTransporter moves the last beam into the slot; removing the last only drops the count")
        void remove() {
            Ecms e = new Ecms();
            for (int i = 0; i < 3; i++) {
                int k = e.addTransporter();
                e.transX[k] = 10 + i;
                e.transY[k] = 20 + i;
                e.transTarget[k] = 30 + i;
                e.transOwner[k] = 40 + i;
                e.transCount[k] = 50 + i;
            }
            e.removeTransporter(0);
            e.transCount[1] = 77;
            e.removeTransporter(1);
            assertAll(
                () -> assertEquals(1, e.transporters(), "two removals leave one beam"),
                () -> assertEquals(12f, e.transX[0], "x moved"),
                () -> assertEquals(22f, e.transY[0], "y moved"),
                () -> assertEquals(32, e.transTarget[0], "target moved"),
                () -> assertEquals(42, e.transOwner[0], "owner moved"),
                () -> assertEquals(52, e.transCount[0], "count moved"),
                () -> assertEquals(77, e.transCount[1], "removing the last slot overwrites nothing"));
        }
    }
}
