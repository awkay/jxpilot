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
package com.fulcrologic.jxpilot.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("SoundEvent")
class SoundEventTest {
    @Test
    @DisplayName("ordinals and names are C audio.h's")
    void cTable() {
        assertAll(
            () -> assertEquals(83, SoundEvent.COUNT, "has C's MAX_SOUNDS events"),
            () -> assertEquals(SoundEvent.COUNT, SoundEvent.values().length, "COUNT matches the enum"),
            () -> assertEquals(0, SoundEvent.START.ordinal(), "START_SOUND is 0"),
            () -> assertEquals(1, SoundEvent.FIRE_SHOT.ordinal(), "FIRE_SHOT_SOUND is 1"),
            () -> assertEquals(23, SoundEvent.THRUST.ordinal(), "THRUST_SOUND is 23"),
            () -> assertEquals(33, SoundEvent.CANNON_FIRE.ordinal(), "CANNON_FIRE_SOUND is 33"),
            () -> assertEquals(82, SoundEvent.ASTEROID_BREAK.ordinal(), "ASTEROID_BREAK_SOUND is the last, 82"),
            () -> assertEquals("smart_shot_pickup", SoundEvent.ROCKET_PACK_PICKUP.cName(),
                "keeps C's name for ROCKET_PACK_PICKUP_SOUND"),
            () -> assertEquals("player_shot_themself", SoundEvent.PLAYER_SHOT_THEMSELF.cName(), "names are C's soundNames"),
            () -> assertEquals(100, SoundEvent.MAX_VOLUME, "SOUND_MAX_VOLUME is 100"),
            () -> assertEquals(10, SoundEvent.MIN_VOLUME, "SOUND_MIN_VOLUME is 10"));
    }

    @Test
    @DisplayName("lookups by wire id and by name")
    void lookups() {
        assertAll(
            () -> assertEquals(SoundEvent.START, SoundEvent.of(0), "id 0 is START"),
            () -> assertEquals(SoundEvent.ASTEROID_BREAK, SoundEvent.of(82), "the last id is ASTEROID_BREAK"),
            () -> assertNull(SoundEvent.of(83), "an id past the end is unknown"),
            () -> assertNull(SoundEvent.of(-1), "a negative id is unknown"),
            () -> assertEquals(SoundEvent.THRUST, SoundEvent.byName("thrust"), "finds an event by its C name"),
            () -> assertNull(SoundEvent.byName("THRUST"), "names are case sensitive, as C's strcmp"),
            () -> assertNull(SoundEvent.byName("no_such_sound"), "an unknown name is null"));
    }
}
