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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("SoundSystem")
class SoundSystemTest {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final PrintStream log = new PrintStream(out, true, StandardCharsets.UTF_8);
    boolean opened;

    String logged() {
        return out.toString(StandardCharsets.UTF_8).strip();
    }

    /** Plays START and THRUST and reports whether the player's device did anything (a silent player does not). */
    static boolean audible(SoundPlayer p, FakeDevice d) {
        p.play(SoundEvent.START.ordinal(), 100, 1);
        return d != null && !d.log.isEmpty();
    }

    @Nested
    @DisplayName("no sound: the game runs on a silent player")
    class Silent {
        @Test
        @DisplayName("sound off or maxVolume 0: the device is never opened")
        void off() {
            SoundSystem.Opener o = why -> {
                opened = true;
                return new FakeDevice(4);
            };
            SoundPlayer off = SoundSystem.open(false, 100, o, log);
            String offLog = logged();
            out.reset();
            SoundPlayer zero = SoundSystem.open(true, 0, o, log);
            assertAll(
                () -> assertFalse(opened, "no device is opened"),
                () -> assertEquals("sound off.", offLog, "--no-sound says so"),
                () -> assertEquals("maxVolume is 0: no sound.", logged(), "maxVolume 0 says what C says"),
                () -> assertEquals(100, off.maxVolume(), "the player keeps maxVolume"),
                () -> assertEquals(0, zero.maxVolume(), "even 0"));
            off.play(SoundEvent.START.ordinal(), 100, 1);
            off.update(1);
            off.close();
        }

        @Test
        @DisplayName("no device, or the OpenAL library failing to load: a silent player and the reason")
        void noDevice() {
            SoundPlayer none = SoundSystem.open(true, 100, why -> {
                why[0] = "no audio device";
                return null;
            }, log);
            String noneLog = logged();
            out.reset();
            SoundSystem.open(true, 100, why -> {
                throw new UnsatisfiedLinkError("libopenal");
            }, log);
            assertAll(
                () -> assertEquals("no sound: no audio device", noneLog, "the opener's reason"),
                () -> assertEquals("no sound: java.lang.UnsatisfiedLinkError: libopenal", logged(), "the error"),
                () -> assertFalse(none.muted(), "a silent player is simply quiet, not muted"));
        }

        @Test
        @DisplayName("headless: real OpenAL with a device that does not exist gives a silent player")
        void realOpenAlNoDevice() {
            String[] why = {null};
            OpenAlDevice d = OpenAlDevice.open("JXPilot no such device", 4, why);
            SoundPlayer p = SoundSystem.open(true, 100,
                w -> OpenAlDevice.open("JXPilot no such device", SoundPlayer.VOICES, w), log);
            p.play(SoundEvent.START.ordinal(), 100, 1);
            assertAll(
                () -> assertNull(d, "no device is opened"),
                () -> assertEquals("no audio device 'JXPilot no such device'", why[0], "with the reason"),
                () -> assertEquals("no sound: no audio device 'JXPilot no such device'", logged(), "logged once"));
        }
    }

    @Test
    @DisplayName("with a device: every clip of the shipped map is loaded and events play on it at maxVolume")
    void loads() {
        FakeDevice d = new FakeDevice(SoundPlayer.VOICES);
        SoundPlayer p = SoundSystem.open(true, 70, why -> d, log);
        boolean played = audible(p, d);
        List<Double> secs = d.loaded.stream().map(Wav::seconds).toList();
        assertAll(
            () -> assertEquals(22, d.loaded.size(), "22 clips are loaded"),
            () -> assertTrue(secs.stream().allMatch(s -> s > 0.04 && s < 2.1), "every clip lasts 0.04..2.1 s"),
            () -> assertTrue(played, "START plays on the device"),
            () -> assertEquals("play v1 c" + (100 + SoundMap.parse(SoundSystem.text(SoundSystem.MAP))
                .clip(SoundEvent.START.ordinal())) + " 0.7", d.log.get(0), "with START's clip, at maxVolume 70"),
            () -> assertEquals("", logged(), "nothing is logged"));
    }

    @Test
    @DisplayName("a clip that cannot be loaded closes the device and gives a silent player")
    void badClip() {
        FakeDevice d = new FakeDevice(SoundPlayer.VOICES);
        SoundPlayer p = SoundSystem.open(true, 100, why -> d, log, "/sounds-bad/");
        p.play(SoundEvent.START.ordinal(), 100, 1);
        assertAll(
            () -> assertTrue(d.closed, "the device is closed"),
            () -> assertEquals("no sound: missing resource /sounds-bad/missing.wav", logged(), "the reason is logged"),
            () -> assertEquals(List.of(), d.log, "nothing plays on it"),
            () -> assertEquals(100, p.maxVolume(), "the silent player keeps maxVolume"));
    }

    @Test
    @DisplayName("the Silent device: its voices, nothing loaded, never playing")
    void silentDevice() {
        AudioDevice s = new AudioDevice.Silent(3);
        SoundPlayer p = new SoundPlayer(s, SoundMap.parse("start a.wav"), new int[] {s.load(null)});
        p.play(SoundEvent.START.ordinal(), 100, 1);
        p.play(SoundEvent.START.ordinal(), 100, 2);
        assertAll(
            () -> assertEquals(3, s.voices(), "the voice count given"),
            () -> assertEquals(0, s.load(null), "loads to handle 0"),
            () -> assertFalse(s.playing(1), "nothing ever plays"));
        s.setGain(0, 1);
        s.stop(0);
        s.close();
    }

    @Test
    @DisplayName("a real device, when this machine has one: load, play silently, stop, close")
    void realDevice() {
        String[] why = {null};
        AudioDevice d = SoundSystem.openAl(why);
        assumeTrue(d != null, "no audio device here: " + why[0]);
        try {
            int clip = d.load(Wav.parse(SoundSystem.bytes("/sounds/explode_nuke.wav")));
            d.play(1, clip, 0f, false);
            boolean playing = d.playing(1);
            d.setGain(1, 0f);
            d.stop(1);
            assertAll(
                () -> assertEquals(SoundPlayer.VOICES, d.voices(), "the default voices"),
                () -> assertTrue(playing, "a started voice is playing"),
                () -> assertFalse(d.playing(1), "a stopped voice is not"),
                () -> assertFalse(d.playing(0), "an unused voice is not"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        } finally {
            d.close();
        }
    }
}
