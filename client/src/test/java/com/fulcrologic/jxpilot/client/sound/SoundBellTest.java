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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SoundPlayer bell")
class SoundBellTest {
    @Test
    @DisplayName("the bell plays the blip clip as a one-shot at maxVolume, unless muted or absent")
    void bell() {
        SoundMap map = SoundMap.parse("start a.wav\nthrust t_loop.wav\nchange_home blip.wav\n");
        FakeDevice dev = new FakeDevice(4);
        SoundPlayer p = new SoundPlayer(dev, map, new int[] {100, 101, 102});
        p.setMaxVolume(50);
        boolean rang = p.bell(1);
        List<String> first = List.copyOf(dev.log);
        dev.log.clear();
        p.setMuted(true);
        dev.log.clear();
        boolean muted = p.bell(2);
        List<String> mutedLog = List.copyOf(dev.log);
        p.setMuted(false);
        p.setMaxVolume(0);
        boolean silent = p.bell(3);
        SoundPlayer none = new SoundPlayer(new FakeDevice(4), SoundMap.parse("start a.wav\n"), new int[] {100});
        assertAll(
            () -> assertTrue(rang, "it played"),
            () -> assertEquals(List.of("play v1 c102 0.5"), first, "on the first one-shot voice (v0 is the loop's), clip 102, half gain"),
            () -> assertFalse(muted, "muted: nothing"),
            () -> assertEquals(List.of(), mutedLog, "no device call while muted"),
            () -> assertFalse(silent, "maxVolume 0: nothing"),
            () -> assertFalse(none.bell(4), "no blip.wav in the map: nothing"),
            () -> assertEquals("blip.wav", SoundPlayer.BELL_CLIP, "the stand-in clip"));
    }
}
