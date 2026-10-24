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
package com.fulcrologic.jxpilot.client.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.game.WorldFixture.T0;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("sounds received")
class SoundQueueTest {
    static List<String> held(SoundQueue q) {
        List<String> out = new ArrayList<>();
        for (long s = q.oldest(); s < q.total(); s++) {
            out.add(q.event(s) + ":" + q.volume(s));
        }
        return out;
    }

    @Nested
    @DisplayName("SoundQueue")
    class Ring {
        @Test
        @DisplayName("keeps sounds in arrival order; when full the oldest are overwritten")
        void ring() {
            SoundQueue q = new SoundQueue(3);
            q.add(1, 10);
            q.add(2, 20);
            List<String> two = held(q);
            long oldestBefore = q.oldest();
            q.add(3, 30);
            q.add(4, 40);
            assertAll(
                () -> assertEquals(List.of("1:10", "2:20"), two, "holds what was added, in order"),
                () -> assertEquals(0, oldestBefore, "nothing is lost before the ring is full"),
                () -> assertEquals(4, q.total(), "counts every sound"),
                () -> assertEquals(1, q.oldest(), "the oldest is lost when a fourth arrives"),
                () -> assertEquals(List.of("2:20", "3:30", "4:40"), held(q), "the newest three remain"));
        }
    }

    @Nested
    @DisplayName("ClientWorld SOUND ingest")
    class Ingest {
        @Test
        @DisplayName("only the newest tick's SOUND records are queued: new ticks and their parts, not repeats, late or stale ticks")
        void newestOnly() {
            WorldFixture fx = new WorldFixture(false);
            SoundQueue q = fx.world.sounds();
            fx.begin(100).sound(5, 40).sound(23, 100).ingest(T0);
            List<String> first = held(q);
            fx.begin(100).sound(5, 40).ingest(T0);
            long afterRepeat = q.total();
            fx.begin(104, 0, 2).sound(1, 50).ingest(T0);
            fx.begin(102).sound(9, 90).ingest(T0);
            long afterLate = q.total();
            fx.begin(104, 1, 2).sound(2, 60).ingest(T0);
            fx.begin(90).sound(8, 80).ingest(T0);
            assertAll(
                () -> assertEquals(List.of("5:40", "23:100"), first, "a new tick queues its sounds in order"),
                () -> assertEquals(2, afterRepeat, "a repeated part queues nothing again"),
                () -> assertEquals(3, afterLate, "a late (older) tick queues nothing"),
                () -> assertEquals(List.of("5:40", "23:100", "1:50", "2:60"), held(q),
                    "another part of the newest tick is queued; a stale tick is not"));
        }
    }
}
