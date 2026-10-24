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

import com.fulcrologic.jxpilot.client.game.SoundQueue;
import com.fulcrologic.jxpilot.common.SoundEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SoundPlayer")
class SoundPlayerTest {
    static final int START = SoundEvent.START.ordinal();
    static final int FIRE = SoundEvent.FIRE_SHOT.ordinal();
    static final int CANNON = SoundEvent.CANNON_FIRE.ordinal();
    static final int THRUST = SoundEvent.THRUST.ordinal();
    static final long MS = 1_000_000L;

    /** Clips: 0 a.wav (start, cannon_fire), 1 b.wav (fire_shot), 2 t_loop.wav (thrust); handles 100-102. */
    static final SoundMap MAP = SoundMap.parse("start a.wav\nfire_shot b.wav\nthrust t_loop.wav\ncannon_fire a.wav\n");

    FakeDevice dev = new FakeDevice(4);
    SoundPlayer p = new SoundPlayer(dev, MAP, new int[] {100, 101, 102});

    List<String> log() {
        List<String> l = List.copyOf(dev.log);
        dev.log.clear();
        return l;
    }

    @Test
    @DisplayName("gain is min(volume, maxVolume)/100, clamped to 0..1 (C MIN(volume, maxVolume))")
    void gain() {
        assertAll(
            () -> assertEquals(1.0f, SoundPlayer.gain(100, 100), "full volume"),
            () -> assertEquals(0.5f, SoundPlayer.gain(50, 100), "half volume"),
            () -> assertEquals(0.4f, SoundPlayer.gain(80, 40), "capped at maxVolume"),
            () -> assertEquals(0.3f, SoundPlayer.gain(30, 40), "below maxVolume it is not scaled (C caps, does not scale)"),
            () -> assertEquals(0.0f, SoundPlayer.gain(10, 0), "maxVolume 0 is silent"),
            () -> assertEquals(1.0f, SoundPlayer.gain(150, 255), "above 100 plays at full gain"),
            () -> assertEquals(0.0f, SoundPlayer.gain(-5, 100), "a negative volume is silent"));
    }

    @Test
    @DisplayName("a loop clip needs a voice to spare for one-shots")
    void voices() {
        assertThrows(IllegalArgumentException.class, () -> new SoundPlayer(new FakeDevice(1), MAP, new int[3]),
            "one voice and one loop clip is refused");
    }

    @Nested
    @DisplayName("one-shots")
    class OneShots {
        @Test
        @DisplayName("each takes a free one-shot voice (the loop voice 0 stays reserved), with its clip's handle and gain")
        void freeVoices() {
            p.play(START, 100, 1);
            p.play(FIRE, 50, 1);
            p.play(FIRE, 30, 2);
            assertEquals(List.of("play v1 c100 1.0", "play v2 c101 0.5", "play v3 c101 0.3"), log(),
                "voices 1, 2, 3 in turn; a repeat at another time is a new voice");
        }

        @Test
        @DisplayName("the same clip again at the same time shares the voice, at the louder gain")
        void merge() {
            p.play(START, 50, 1);
            log();
            p.play(CANNON, 40, 1);
            List<String> quieter = log();
            p.play(CANNON, 90, 1);
            List<String> louder = log();
            p.play(START, 70, 1);
            p.play(CANNON, 90, 1);
            List<String> between = log();
            dev.finish(1);
            p.play(START, 50, 1);
            assertAll(
                () -> assertEquals(List.of(), quieter, "a quieter event of the same clip changes nothing"),
                () -> assertEquals(List.of("gain v1 0.9"), louder, "a louder one raises the gain"),
                () -> assertEquals(List.of(), between, "the gain never drops back, nor is an equal gain set again"),
                () -> assertEquals(List.of("play v1 c100 0.5"), log(), "once that voice has ended, the clip plays anew"));
        }

        @Test
        @DisplayName("all busy: the quietest voice is taken, the oldest among equals; a free voice comes first")
        void stealing() {
            p.play(START, 50, 1);
            p.play(FIRE, 30, 2);
            p.play(START, 30, 3);
            log();
            p.play(FIRE, 100, 4);
            List<String> steal = log();
            dev.finish(3);
            p.play(FIRE, 100, 5);
            List<String> free = log();
            p.play(START, 100, 6);
            assertAll(
                () -> assertEquals(List.of("play v2 c101 1.0"), steal, "v2 (0.3, older than v3) is replaced"),
                () -> assertEquals(List.of("play v3 c101 1.0"), free, "a voice that ended is used first"),
                () -> assertEquals(List.of("play v1 c100 1.0"), log(), "then the quietest left, v1 at 0.5"));
        }

        @Test
        @DisplayName("ties: among equally quiet voices the oldest goes; a louder older voice and a same-time one stay")
        void tieBreaks() {
            p.play(START, 30, 5);
            p.play(FIRE, 30, 1);
            p.play(FIRE, 60, 2);
            log();
            p.play(START, 100, 6);
            List<String> olderQuiet = log();
            FakeDevice d2 = new FakeDevice(4);
            SoundPlayer q = new SoundPlayer(d2, MAP, new int[] {100, 101, 102});
            q.play(START, 30, 5);
            q.play(FIRE, 50, 1);
            q.play(FIRE, 60, 2);
            d2.log.clear();
            q.play(FIRE, 100, 6);
            List<String> quietNewer = List.copyOf(d2.log);
            FakeDevice d3 = new FakeDevice(4);
            SoundPlayer r = new SoundPlayer(d3, MAP, new int[] {100, 101, 102});
            r.play(START, 30, 1);
            r.play(FIRE, 30, 1);
            r.play(START, 60, 2);
            d3.log.clear();
            r.play(FIRE, 100, 3);
            assertAll(
                () -> assertEquals(List.of("play v2 c100 1.0"), olderQuiet, "equally quiet: the older (v2) goes"),
                () -> assertEquals(List.of("play v1 c101 1.0"), quietNewer, "the quietest goes even when newer than a louder one"),
                () -> assertEquals(List.of("play v1 c101 1.0"), List.copyOf(d3.log), "equally quiet and as old: the first stays first"));
        }

        @Test
        @DisplayName("silent and unknown events, and gain 0, play nothing")
        void silent() {
            p.play(SoundEvent.REFUEL.ordinal(), 100, 1);
            p.play(200, 100, 1);
            p.play(-1, 100, 1);
            p.setMaxVolume(0);
            p.play(START, 100, 1);
            assertAll(
                () -> assertEquals(List.of(), log(), "nothing is played"),
                () -> assertEquals(0, p.maxVolume(), "maxVolume is kept"));
        }
    }

    @Nested
    @DisplayName("loops (thrust)")
    class Loops {
        @Test
        @DisplayName("start on the loop voice, follow the gain, stop once the hold time passes without an event")
        void hold() {
            p.setFrameRate(10);
            p.play(THRUST, 60, 0);
            p.play(THRUST, 60, 10 * MS);
            List<String> start = log();
            p.play(THRUST, 80, 20 * MS);
            List<String> louder = log();
            p.update(270 * MS);
            List<String> atHold = log();
            p.update(270 * MS + 1);
            List<String> after = log();
            p.update(400 * MS);
            p.play(THRUST, 50, 500 * MS);
            assertAll(
                () -> assertEquals(List.of("play v0 c102 0.6 loop"), start, "one looping start; a repeat keeps it going"),
                () -> assertEquals(List.of("gain v0 0.8"), louder, "a new volume changes the gain"),
                () -> assertEquals(List.of(), atHold, "kept exactly until 20 ms + 2.5 frames at 10 fps (250 ms)"),
                () -> assertEquals(List.of("stop v0"), after, "stopped right after"),
                () -> assertEquals(List.of("play v0 c102 0.5 loop"), log(), "a later thrust starts it again"));
        }

        @Test
        @DisplayName("the hold follows the frame rate; a rate of 0 keeps the last one")
        void frameRate() {
            p.setFrameRate(5);
            p.setFrameRate(0);
            p.play(THRUST, 100, 0);
            p.update(500 * MS);
            List<String> held = log();
            p.update(500 * MS + 1);
            assertAll(
                () -> assertEquals(List.of("play v0 c102 1.0 loop"), held, "2.5 frames at 5 fps hold 500 ms"),
                () -> assertEquals(List.of("stop v0"), log(), "and stop after"));
        }
    }

    @Nested
    @DisplayName("mute and the queue")
    class Queue {
        @Test
        @DisplayName("drain plays new sounds in order once; a full ring plays only what it still holds")
        void drain() {
            SoundQueue q = new SoundQueue(2);
            q.add(START, 100);
            q.add(FIRE, 50);
            p.drain(q, 1);
            List<String> first = log();
            p.drain(q, 2);
            List<String> again = log();
            q.add(FIRE, 10);
            q.add(FIRE, 20);
            q.add(START, 30);
            p.drain(q, 3);
            assertAll(
                () -> assertEquals(List.of("play v1 c100 1.0", "play v2 c101 0.5"), first, "both, in order"),
                () -> assertEquals(List.of(), again, "nothing twice"),
                () -> assertEquals(List.of("play v3 c101 0.2", "play v3 c100 0.3"), log(),
                    "the overwritten sound is skipped: the last two play (the second on the quietest voice)"));
        }

        @Test
        @DisplayName("muting stops every voice and drops sounds for good; unmuting plays only new ones")
        void mute() {
            SoundQueue q = new SoundQueue(8);
            p.play(THRUST, 100, 0);
            p.play(START, 100, 0);
            log();
            boolean on = p.toggleMuted();
            boolean mutedNow = p.muted();
            List<String> stops = log();
            q.add(FIRE, 100);
            p.drain(q, 1);
            p.play(THRUST, 100, 1);
            List<String> whileMuted = log();
            p.update(10_000 * MS);
            List<String> updateMuted = log();
            boolean off = p.toggleMuted();
            List<String> unmute = log();
            q.add(START, 40);
            p.drain(q, 2);
            assertAll(
                () -> assertTrue(on, "the first toggle mutes"),
                () -> assertTrue(mutedNow, "muted() says so"),
                () -> assertFalse(p.muted(), "and follows the second toggle"),
                () -> assertEquals(List.of("stop v0", "stop v1", "stop v2", "stop v3"), stops, "muting stops all voices"),
                () -> assertEquals(List.of(), whileMuted, "nothing plays while muted"),
                () -> assertEquals(List.of(), updateMuted, "the stopped loop is not stopped again"),
                () -> assertFalse(off, "the second toggle unmutes"),
                () -> assertEquals(List.of(), unmute, "unmuting plays nothing by itself"),
                () -> assertEquals(List.of("play v1 c100 0.4"), log(), "only the sound after unmuting plays"));
        }

        @Test
        @DisplayName("frame: plays what arrived, then stops loops past their hold at the given rate")
        void frame() {
            SoundQueue q = new SoundQueue(8);
            q.add(THRUST, 100);
            p.frame(q, 10, 0);
            List<String> first = log();
            p.frame(q, 10, 250 * MS);
            List<String> held = log();
            p.frame(q, 10, 250 * MS + 1);
            assertAll(
                () -> assertEquals(List.of("play v0 c102 1.0 loop"), first, "the thrust starts"),
                () -> assertEquals(List.of(), held, "it holds 2.5 frames at the given 10 fps (250 ms, not the default 14 fps)"),
                () -> assertEquals(List.of("stop v0"), log(), "and stops right after without another"));
        }

        @Test
        @DisplayName("close closes the device")
        void close() {
            p.close();
            assertTrue(dev.closed, "the device is closed");
        }
    }
}
