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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Wav")
class WavTest {
    /** RIFF/WAVE bytes from chunks (id + body each); the RIFF size is filled in. */
    static ByteBuffer riff(Object... idAndBody) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < idAndBody.length; i += 2) {
            byte[] body = (byte[]) idAndBody[i + 1];
            o.writeBytes(((String) idAndBody[i]).getBytes());
            o.writeBytes(le(body.length, 4));
            o.writeBytes(body);
            if ((body.length & 1) != 0) {
                o.write(0);
            }
        }
        byte[] chunks = o.toByteArray();
        ByteBuffer b = ByteBuffer.allocate(12 + chunks.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(4 + chunks.length).put("WAVE".getBytes()).put(chunks);
        return b.flip();
    }

    static byte[] le(long v, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (v >> (8 * i));
        }
        return b;
    }

    static byte[] fmt(int format, int channels, int rate, int bits) {
        ByteBuffer b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) format).putShort((short) channels).putInt(rate).putInt(rate * channels * bits / 8)
            .putShort((short) (channels * bits / 8)).putShort((short) bits);
        return b.array();
    }

    static String error(ByteBuffer b) {
        return assertThrows(IllegalArgumentException.class, () -> Wav.parse(b)).getMessage();
    }

    @Test
    @DisplayName("a shipped clip: 16-bit mono 44.1 kHz with its length")
    void shipped() throws Exception {
        Wav w = Wav.parse(SoundSystem.bytes("/sounds/shot.wav"));
        assertAll(
            () -> assertEquals(1, w.channels(), "mono"),
            () -> assertEquals(44100, w.sampleRate(), "44.1 kHz"),
            () -> assertEquals(0.150, w.seconds(), 0.001, "0.150 s (tools/sounds report; 1 ms tolerance for rounding)"));
    }

    @Test
    @DisplayName("chunks in any order, odd chunks padded, PCM a view of exactly the data chunk")
    void chunks() {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
        ByteBuffer b = riff("LIST", new byte[] {9, 9, 9}, "data", data, "fmt ", fmt(1, 2, 22050, 16));
        int pos = b.position();
        Wav w = Wav.parse(b);
        byte[] got = new byte[w.pcm().remaining()];
        w.pcm().duplicate().get(got);
        assertAll(
            () -> assertEquals(2, w.channels(), "stereo"),
            () -> assertEquals(22050, w.sampleRate(), "the rate"),
            () -> assertEquals(java.util.Arrays.toString(data), java.util.Arrays.toString(got), "the data chunk's bytes"),
            () -> assertEquals(ByteOrder.LITTLE_ENDIAN, w.pcm().order(), "little-endian samples"),
            () -> assertEquals(2.0 / 22050, w.seconds(), 1e-12, "two stereo frames at 22050 Hz"),
            () -> assertEquals(pos, b.position(), "the input buffer is not moved"));
    }

    @Test
    @DisplayName("everything but 16-bit PCM mono/stereo with fmt and data is refused, saying why")
    void errors() {
        byte[] two = {0, 0};
        ByteBuffer notRiff = ByteBuffer.wrap("RIFX....WAVE".getBytes());
        ByteBuffer longChunk = riff("fmt ", fmt(1, 1, 44100, 16));
        longChunk.putInt(16, 17);
        ByteBuffer shortFmt = riff("fmt ", new byte[14], "data", two);
        assertAll(
            () -> assertEquals("not a RIFF/WAVE file", error(notRiff), "wrong magic"),
            () -> assertEquals("not a RIFF/WAVE file", error(ByteBuffer.wrap(new byte[8])), "too short"),
            () -> assertEquals("no fmt chunk", error(riff("data", two)), "no fmt"),
            () -> assertEquals("no fmt chunk", error(riff()), "a bare 12-byte header has no chunks"),
            () -> assertEquals(0, Wav.parse(riff("fmt ", fmt(1, 1, 44100, 16), "data", new byte[0])).pcm().remaining(),
                "an empty data chunk at the very end is read"),
            () -> assertEquals("no data chunk", error(riff("fmt ", fmt(1, 1, 44100, 16))), "no data"),
            () -> assertEquals("chunk longer than the file", error(longChunk), "a chunk one byte past the end"),
            () -> assertEquals("fmt chunk too short", error(shortFmt), "a short fmt chunk"),
            () -> assertEquals("not PCM (format 3)", error(riff("fmt ", fmt(3, 1, 44100, 16), "data", two)), "float"),
            () -> assertEquals("not 16-bit (8 bits)", error(riff("fmt ", fmt(1, 1, 44100, 8), "data", two)), "8-bit"),
            () -> assertEquals("3 channels (only 1 or 2)", error(riff("fmt ", fmt(1, 3, 44100, 16), "data", new byte[6])),
                "three channels"),
            () -> assertEquals("0 channels (only 1 or 2)", error(riff("fmt ", fmt(1, 0, 44100, 16), "data", two)),
                "no channels"),
            () -> assertEquals("sample rate 0", error(riff("fmt ", fmt(1, 1, 0, 16), "data", two)), "rate 0"),
            () -> assertEquals("data is not whole sample frames", error(riff("fmt ", fmt(1, 2, 44100, 16), "data", two)),
                "half a stereo frame"),
            () -> assertEquals(1, Wav.parse(riff("fmt ", fmt(1, 1, 1, 16), "data", two)).sampleRate(),
                "a rate of 1 is accepted"));
    }
}
