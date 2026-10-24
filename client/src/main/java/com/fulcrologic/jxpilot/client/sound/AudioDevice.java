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

/**
 * The audio output {@link SoundPlayer} drives: clips loaded once, and a fixed set of voices (0 ..
 * {@link #voices()}-1), each playing at most one clip. Implementations: {@link OpenAlDevice}, and
 * {@link Silent} when there is no device or sound is off. Called from one thread; per-event calls must not
 * allocate.
 */
public interface AudioDevice extends AutoCloseable {
    /** Number of voices. */
    int voices();

    /** Upload a clip (load time); returns its handle for {@link #play}. */
    int load(Wav wav);

    /** Start {@code clip} on {@code voice} from its beginning at {@code gain} (0..1), replacing what it played. */
    void play(int voice, int clip, float gain, boolean loop);

    /** Change the gain of a playing voice. */
    void setGain(int voice, float gain);

    /** Stop a voice. */
    void stop(int voice);

    /** Whether a voice is still playing. */
    boolean playing(int voice);

    /** Release everything. */
    @Override
    void close();

    /** No output: nothing ever plays. */
    final class Silent implements AudioDevice {
        private final int voices;

        public Silent(int voices) {
            this.voices = voices;
        }

        @Override
        public int voices() { return voices; }

        @Override
        public int load(Wav wav) { return 0; }

        @Override
        public void play(int voice, int clip, float gain, boolean loop) { }

        @Override
        public void setGain(int voice, float gain) { }

        @Override
        public void stop(int voice) { }

        @Override
        public boolean playing(int voice) { return false; }

        @Override
        public void close() { }
    }
}
