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

import com.fulcrologic.jxpilot.common.SoundEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("AudioQueue (saudio.c queue_audio)")
class AudioQueueTest {
    static List<String> contents(AudioQueue q) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < q.count(); i++) {
            out.add(q.event(i) + ":" + q.volume(i));
        }
        return out;
    }

    @Test
    @DisplayName("each event once, in first-queued order, at the loudest volume")
    void dedup() {
        AudioQueue q = new AudioQueue();
        q.queue(5, 40);
        q.queue(0, 10);
        q.queue(5, 90);
        q.queue(5, 20);
        q.queue(SoundEvent.COUNT - 1, 100);
        assertAll(
            () -> assertEquals(List.of("5:90", "0:10", (SoundEvent.COUNT - 1) + ":100"), contents(q),
                "a repeat raises the volume in place; a quieter repeat changes nothing; event 0 and the last id work"));
    }

    @Test
    @DisplayName("invalid events and volumes are ignored")
    void invalid() {
        AudioQueue q = new AudioQueue();
        q.queue(-1, 50);
        q.queue(SoundEvent.COUNT, 50);
        q.queue(3, 0);
        q.queue(4, 1);
        assertEquals(List.of("4:1"), contents(q), "only a known event with a volume of at least 1 is queued");
    }

    @Test
    @DisplayName("clear empties the queue, and an event queued again starts over")
    void clear() {
        AudioQueue q = new AudioQueue();
        q.queue(7, 80);
        q.queue(8, 30);
        q.clear();
        int afterClear = q.count();
        q.queue(8, 20);
        q.queue(7, 10);
        assertAll(
            () -> assertEquals(0, afterClear, "nothing is left after clear"),
            () -> assertEquals(List.of("8:20", "7:10"), contents(q), "old volumes and order are forgotten"));
    }

    @Test
    @DisplayName("every event fits at once (the queue never overflows)")
    void full() {
        AudioQueue q = new AudioQueue();
        for (int e = SoundEvent.COUNT - 1; e >= 0; e--) {
            q.queue(e, 50);
            q.queue(e, 60);
        }
        assertAll(
            () -> assertEquals(SoundEvent.COUNT, q.count(), "holds all events"),
            () -> assertEquals(SoundEvent.COUNT - 1, q.event(0), "first queued first"),
            () -> assertEquals(60, q.volume(SoundEvent.COUNT - 1), "the last one took its louder repeat"));
    }
}
