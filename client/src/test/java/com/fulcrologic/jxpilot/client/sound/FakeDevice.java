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

import java.util.ArrayList;
import java.util.List;

/**
 * A recording {@link AudioDevice}: every call as a line ("play v3 c7 0.5 loop"), and per-voice playing
 * state that a play sets and a stop clears; tests end a sound with {@link #finish}.
 */
final class FakeDevice implements AudioDevice {
    final List<String> log = new ArrayList<>();
    final boolean[] playing;
    final List<Wav> loaded = new ArrayList<>();
    boolean closed;
    /** Off: no log lines (allocation-free calls for the allocation test). */
    boolean record = true;

    FakeDevice(int voices) {
        playing = new boolean[voices];
    }

    /** The voice's sound ended by itself. */
    void finish(int voice) {
        playing[voice] = false;
    }

    @Override
    public int voices() { return playing.length; }

    @Override
    public int load(Wav wav) {
        loaded.add(wav);
        return 100 + loaded.size() - 1;
    }

    @Override
    public void play(int voice, int clip, float gain, boolean loop) {
        playing[voice] = true;
        if (record) {
            log.add("play v" + voice + " c" + clip + " " + gain + (loop ? " loop" : ""));
        }
    }

    @Override
    public void setGain(int voice, float gain) {
        if (record) {
            log.add("gain v" + voice + " " + gain);
        }
    }

    @Override
    public void stop(int voice) {
        playing[voice] = false;
        if (record) {
            log.add("stop v" + voice);
        }
    }

    @Override
    public boolean playing(int voice) {
        return playing[voice];
    }

    @Override
    public void close() {
        closed = true;
    }
}
