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
package com.fulcrologic.jxpilot.client.sound;

import com.fulcrologic.jxpilot.common.SoundEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SoundMap")
class SoundMapTest {
    static String error(String text) {
        return assertThrows(IllegalArgumentException.class, () -> SoundMap.parse(text)).getMessage();
    }

    @Test
    @DisplayName("parses event-clip lines; comments and blanks skipped; clips shared; the last line for an event wins")
    void parse() {
        SoundMap m = SoundMap.parse("# comment\n\nstart\ta.wav\n  fire_shot   b.wav  \nthrust x_loop.wav\n"
            + "cannon_fire a.wav\nstart b.wav\r\n");
        assertAll(
            () -> assertEquals(3, m.clipCount(), "three distinct clips"),
            () -> assertEquals(List.of("a.wav", "b.wav", "x_loop.wav"), List.of(m.clipName(0), m.clipName(1), m.clipName(2)),
                "in order of first use"),
            () -> assertEquals(1, m.clip(SoundEvent.START.ordinal()), "a later line for start wins (CR stripped)"),
            () -> assertEquals(1, m.clip(SoundEvent.FIRE_SHOT.ordinal()), "spaces separate fields too"),
            () -> assertEquals(0, m.clip(SoundEvent.CANNON_FIRE.ordinal()), "a clip used twice is one clip"),
            () -> assertEquals(-1, m.clip(SoundEvent.REFUEL.ordinal()), "an event without a line is silent"),
            () -> assertEquals(-1, m.clip(-1), "a negative id is silent"),
            () -> assertEquals(-1, m.clip(SoundEvent.COUNT), "an id past the events is silent"),
            () -> assertTrue(m.loops(2), "a _loop clip loops"),
            () -> assertFalse(m.loops(0), "other clips do not"),
            () -> assertEquals(0, SoundMap.empty().clipCount(), "the empty map has no clips"),
            () -> assertEquals(-1, SoundMap.empty().clip(0), "and every event is silent"));
    }

    @Test
    @DisplayName("bad lines are refused with their line number")
    void errors() {
        assertAll(
            () -> assertEquals("sound map line 2: unknown sound 'bang'", error("start a.wav\nbang b.wav"), "unknown event"),
            () -> assertEquals("sound map line 1: expected 'event clip': start", error("start"), "no clip"),
            () -> assertEquals("sound map line 1: expected 'event clip': start a.wav b.wav", error("start a.wav b.wav"),
                "too many fields"));
    }

    @Test
    @DisplayName("the shipped map: every event but refuel sounds, every clip exists, only thrust loops")
    void shipped() throws Exception {
        SoundMap m = SoundMap.parse(SoundSystem.text(SoundSystem.MAP));
        List<SoundEvent> silent = new ArrayList<>();
        Set<String> loops = new TreeSet<>();
        for (SoundEvent e : SoundEvent.values()) {
            int c = m.clip(e.ordinal());
            if (c < 0) {
                silent.add(e);
            } else if (m.loops(c)) {
                loops.add(e.name());
            }
        }
        for (int c = 0; c < m.clipCount(); c++) {
            assertNotNull(SoundSystem.class.getResource(SoundSystem.DIR + m.clipName(c)), m.clipName(c) + " is bundled");
        }
        assertAll(
            () -> assertEquals(List.of(SoundEvent.REFUEL), silent, "refuel (never played by C servers) is the only silent event"),
            () -> assertEquals(22, m.clipCount(), "22 clips"),
            () -> assertEquals(Set.of("THRUST"), loops, "thrust is the only loop"),
            () -> assertEquals(m.clip(SoundEvent.PLAYER_EAT_SHOT.ordinal()), m.clip(SoundEvent.PLAYER_EAT_LASER.ordinal()),
                "all player_eat_* share hit.wav (SOUND-PLAN.md)"),
            () -> assertEquals("explode_nuke.wav", m.clipName(m.clip(SoundEvent.NUKE_EXPLOSION.ordinal())), "nukes"));
    }
}
