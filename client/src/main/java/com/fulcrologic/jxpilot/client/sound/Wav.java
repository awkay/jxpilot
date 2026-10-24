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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A decoded RIFF/WAVE file of 16-bit PCM: the format and the sample data. Load-time only.
 *
 * @param channels   1 (mono) or 2 (stereo)
 * @param sampleRate samples per second
 * @param pcm        the data chunk's samples, little-endian, interleaved; a view of the parsed buffer
 */
public record Wav(int channels, int sampleRate, ByteBuffer pcm) {
    /** WAVE_FORMAT_PCM. */
    static final int FORMAT_PCM = 1;

    /** Duration in seconds. */
    public double seconds() {
        return pcm.remaining() / (2.0 * channels * sampleRate);
    }

    /**
     * Parse a WAV file from {@code file}'s position to its limit (the buffer itself is not moved).
     * <p>
     * BEHAVIOR: reads the "fmt " and "data" chunks in any order and skips other chunks (odd sizes are padded
     * to even, as RIFF requires); the PCM is a view of {@code file} without copying.
     * BEHAVIOR: throws IllegalArgumentException, saying why, for: no RIFF/WAVE header, a missing fmt or data
     * chunk, a chunk longer than the file, a format other than PCM, other than 16 bits, other than 1 or 2
     * channels, a sample rate below 1, and a data size that is not a whole number of sample frames.
     */
    public static Wav parse(ByteBuffer file) {
        ByteBuffer b = file.slice().order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 12 || b.getInt(0) != tag("RIFF") || b.getInt(8) != tag("WAVE")) {
            throw new IllegalArgumentException("not a RIFF/WAVE file");
        }
        int pos = 12;
        boolean haveFmt = false;
        int channels = 0;
        int rate = 0;
        ByteBuffer pcm = null;
        while (b.limit() - pos >= 8) {
            int id = b.getInt(pos);
            long size = b.getInt(pos + 4) & 0xFFFFFFFFL;
            int body = pos + 8;
            if (size > b.limit() - body) {
                throw new IllegalArgumentException("chunk longer than the file");
            }
            if (id == tag("fmt ")) {
                if (size < 16) {
                    throw new IllegalArgumentException("fmt chunk too short");
                }
                int format = b.getShort(body) & 0xFFFF;
                channels = b.getShort(body + 2) & 0xFFFF;
                rate = b.getInt(body + 4);
                int bits = b.getShort(body + 14) & 0xFFFF;
                if (format != FORMAT_PCM) {
                    throw new IllegalArgumentException("not PCM (format " + format + ")");
                }
                if (bits != 16) {
                    throw new IllegalArgumentException("not 16-bit (" + bits + " bits)");
                }
                if (channels != 1 && channels != 2) {
                    throw new IllegalArgumentException(channels + " channels (only 1 or 2)");
                }
                if (rate < 1) {
                    throw new IllegalArgumentException("sample rate " + rate);
                }
                haveFmt = true;
            } else if (id == tag("data")) {
                pcm = b.slice(body, (int) size).order(ByteOrder.LITTLE_ENDIAN);
            }
            pos = (int) (body + size + (size & 1));
        }
        if (!haveFmt) {
            throw new IllegalArgumentException("no fmt chunk");
        }
        if (pcm == null) {
            throw new IllegalArgumentException("no data chunk");
        }
        if (pcm.remaining() % (2 * channels) != 0) {
            throw new IllegalArgumentException("data is not whole sample frames");
        }
        return new Wav(channels, rate, pcm);
    }

    /** A four-character chunk id as a little-endian int. */
    static int tag(String s) {
        return s.charAt(0) | s.charAt(1) << 8 | s.charAt(2) << 16 | s.charAt(3) << 24;
    }
}
