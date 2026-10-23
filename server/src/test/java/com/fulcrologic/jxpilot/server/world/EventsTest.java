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

import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Events")
class EventsTest {
    @Nested
    @DisplayName("add / message")
    class Add {
        @Test
        @DisplayName("events get consecutive sequence numbers and keep their fields")
        void fields() {
            Events e = new Events();
            int i0 = e.add(Events.SCORE, 3, -1, 2, 12.5f);
            StringBuilder sb = e.message(4, Obj.NO_ID);
            sb.append("hello");
            int i1 = Events.index(1);
            assertAll(
                () -> assertEquals(0, i0, "the first event is at ring index 0"),
                () -> assertEquals(2, e.seq(), "two events were added"),
                () -> assertEquals(Events.SCORE, e.kind[i0], "kind is stored"),
                () -> assertEquals(3, e.a[i0], "a is stored"),
                () -> assertEquals(-1, e.b[i0], "b is stored"),
                () -> assertEquals(2, e.c[i0], "c is stored"),
                () -> assertEquals(12.5f, e.value[i0], "value is stored"),
                () -> assertEquals(Events.MESSAGE, e.kind[i1], "message() adds a MESSAGE"),
                () -> assertEquals(4, e.a[i1], "the message kind is in a"),
                () -> assertEquals(Obj.NO_ID, e.b[i1], "the sender is in b"),
                () -> assertEquals("hello", e.text[i1].toString(), "the returned builder is the event's text"),
                () -> assertEquals(0, e.oldestSeq(), "nothing has been overwritten yet"));
        }

        @Test
        @DisplayName("the ring overwrites the oldest event and clears reused text")
        void wraps() {
            Events e = new Events();
            e.message(0, 0).append("old");
            for (int i = 1; i < Events.CAPACITY + 5; i++) {
                e.add(Events.JOIN, i, 0, 0, 0f);
            }
            int reused = Events.index(Events.CAPACITY);
            assertAll(
                () -> assertEquals(Events.CAPACITY + 5, e.seq(), "every event got a sequence number"),
                () -> assertEquals(5, e.oldestSeq(), "the oldest readable event is seq - capacity"),
                () -> assertEquals(Events.JOIN, e.kind[reused], "slot 0 now holds a later event"),
                () -> assertEquals(Events.CAPACITY, e.a[reused], "with that event's fields"),
                () -> assertEquals("", e.text[reused].toString(), "reused text is cleared"));
        }
    }

    @Nested
    @DisplayName("addBurst")
    class Bursts {
        @Test
        @DisplayName("bursts get consecutive sequence numbers, kind, colour, owner and tick; the ring wraps")
        void bursts() {
            Events e = new Events();
            DebrisBurst d0 = e.addBurst(1, 4, 7, 99);
            for (int i = 1; i < Events.BURSTS + 2; i++) {
                e.addBurst(2, 5, 8, 100 + i);
            }
            assertAll(
                () -> assertSame(e.burst[0], d0, "the returned parameters are the ring slot"),
                () -> assertEquals(Events.BURSTS + 2, e.burstSeq(), "every burst got a sequence number"),
                () -> assertEquals(2, e.oldestBurstSeq(), "the oldest readable burst is seq - capacity"),
                () -> assertEquals(2, e.burstKind[0], "slot 0 was overwritten by a later burst"),
                () -> assertEquals(5, e.burstColor[1], "colour is stored"),
                () -> assertEquals(8, e.burstOwner[1], "owner is stored"),
                () -> assertEquals(100 + Events.BURSTS + 1, e.burstTick[1], "tick is stored"),
                () -> assertEquals(3, Events.burstIndex(Events.BURSTS + 3), "burst index wraps"));
        }

        @Test
        @DisplayName("before wrapping the oldest burst is 0")
        void oldest() {
            Events e = new Events();
            e.addBurst(0, 0, 0, 0);
            assertEquals(0, e.oldestBurstSeq(), "no burst has been overwritten");
        }
    }
}
