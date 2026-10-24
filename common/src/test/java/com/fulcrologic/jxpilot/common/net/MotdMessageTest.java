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
package com.fulcrologic.jxpilot.common.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Msg.Motd}: the message of the day in reliable pieces. */
class MotdMessageTest {

    /** Send through one channel and capture what arrives. */
    private static List<ByteBuffer> roundTrip(byte[] text) {
        ReliableChannel tx = new ReliableChannel();
        Msg.Motd.sendAll(tx, text);
        ReliableChannel rx = new ReliableChannel();
        List<ByteBuffer> got = new ArrayList<>();
        ReliableHandler h = (type, payload) -> {
            assertEquals(ReliableType.MOTD, type, "MOTD type");
            ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
            copy.put(payload).flip();
            got.add(copy);
        };
        for (int i = 0; i < 20; i++) {
            ByteBuffer p = ByteBuffer.allocate(Protocol.MAX_PACKET);
            tx.write(p, i);
            p.flip();
            rx.read(p, h);
            ByteBuffer ack = ByteBuffer.allocate(Protocol.MAX_PACKET);
            rx.write(ack, i);
            ack.flip();
            tx.read(ack, (t, x) -> { });
        }
        return got;
    }

    @Test
    @DisplayName("a long text arrives in slot-sized pieces that reassemble exactly; each piece carries the total")
    void pieces() {
        byte[] text = new byte[3000];
        for (int i = 0; i < text.length; i++) {
            text[i] = (byte) ('a' + i % 26);
        }
        List<ByteBuffer> got = roundTrip(text);
        byte[] dest = new byte[Msg.Motd.MAX_SIZE];
        List<Integer> totals = new ArrayList<>();
        for (ByteBuffer b : got) {
            totals.add(Msg.Motd.read(b, dest));
        }
        assertAll(
            () -> assertTrue(got.size() >= 3, "several pieces (" + got.size() + ")"),
            () -> assertTrue(totals.stream().allMatch(t -> t == 3000), "every piece knows the total"),
            () -> assertArrayEquals(text, Arrays.copyOf(dest, 3000), "reassembled exactly"));
    }

    @Test
    @DisplayName("nothing is sent for an empty text; a text over 30 KB is cut to 30 KB (C MAX_MOTD_SIZE)")
    void limits() {
        ReliableChannel tx = new ReliableChannel();
        Msg.Motd.sendAll(tx, new byte[0]);
        ByteBuffer p = ByteBuffer.allocate(Protocol.MAX_PACKET);
        int emptyBytes = tx.write(p, 0);
        ReliableChannel big = new ReliableChannel();
        Msg.Motd.sendAll(big, new byte[Msg.Motd.MAX_SIZE + 5000]);
        ByteBuffer q = ByteBuffer.allocate(Protocol.MAX_PACKET);
        big.write(q, 0);
        q.flip();
        List<Integer> totals = new ArrayList<>();
        new ReliableChannel().read(q, (t, b) -> {
            b.getInt();
            totals.add(b.getInt());
        });
        assertAll(
            () -> assertEquals(0, emptyBytes, "an empty MOTD queues nothing"),
            () -> assertEquals(30 * 1024, totals.get(0), "the total is capped at 30 KB"));
    }

    @Test
    @DisplayName("read refuses pieces that do not fit and copies nothing then")
    void refuses() {
        byte[] dest = new byte[10];
        ByteBuffer past = ByteBuffer.allocate(12).putInt(8).putInt(10).put(new byte[] {1, 2, 3, 4}).flip();
        ByteBuffer tooBig = ByteBuffer.allocate(9).putInt(0).putInt(11).put((byte) 1).flip();
        ByteBuffer negative = ByteBuffer.allocate(9).putInt(-1).putInt(5).put((byte) 1).flip();
        ByteBuffer offPastTotal = ByteBuffer.allocate(8).putInt(6).putInt(5).flip();
        ByteBuffer ok = ByteBuffer.allocate(10).putInt(3).putInt(5).put(new byte[] {7, 8}).flip();
        assertAll(
            () -> assertEquals(-1, Msg.Motd.read(past, dest), "bytes beyond the total"),
            () -> assertEquals(-1, Msg.Motd.read(tooBig, dest), "a total larger than the buffer"),
            () -> assertEquals(-1, Msg.Motd.read(negative, dest), "a negative offset"),
            () -> assertEquals(-1, Msg.Motd.read(offPastTotal, dest), "an offset past the total"),
            () -> assertArrayEquals(new byte[10], dest, "nothing copied"),
            () -> assertEquals(5, Msg.Motd.read(ok, dest), "a fitting piece gives the total"),
            () -> assertEquals(7, dest[3], "and lands at its offset"));
    }
}
