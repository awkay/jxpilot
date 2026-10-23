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
package com.fulcrologic.jxpilot.client.xp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** Receive_reliable / Send_ack port. */
class XpReliableReceiverTest {

    private static XpReader reader(byte[] a) {
        return new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
    }

    private static byte[] chunk(int rel, int loops, String data) {
        return new CPackets().reliable(rel, loops, data.getBytes()).bytes();
    }

    private static String stream(XpReliableReceiver r) {
        return new String(r.data().array(), 0, r.length());
    }

    private static byte[] ack(XpReliableReceiver r) {
        ByteBuffer b = ByteBuffer.allocate(9);
        r.writeAck(b);
        return b.array();
    }

    @Nested
    @DisplayName("receive")
    class Receive {
        @Test
        void inOrderChunksAppendAndAdvanceTheOffset() {
            XpReliableReceiver r = new XpReliableReceiver(64);
            XpReader in = reader(chunk(0, 7, "abc"));
            int a = r.receive(in);
            int b = r.receive(reader(chunk(3, 9, "de")));
            assertAll(
                () -> assertEquals(XpReliableReceiver.APPENDED, a, "first chunk appended"),
                () -> assertEquals(XpReliableReceiver.APPENDED, b, "second chunk appended"),
                () -> assertEquals(0, in.remaining(), "reader after the chunk"),
                () -> assertEquals("abcde", stream(r), "stream holds both"),
                () -> assertEquals(5, r.offset(), "offset advanced"),
                () -> assertEquals(9, r.ackLoops(), "ack echoes the last chunk's loop"),
                () -> assertEquals(2, r.lastLength(), "last appended length"),
                () -> assertArrayEquals(new CPackets().c(XpProto.PKT_ACK).d(5).d(9).bytes(), ack(r),
                    "Send_ack: PKT_ACK, offset, loops"));
        }

        @Test
        void aChunkAfterAGapIsDroppedButAcked() {
            XpReliableReceiver r = new XpReliableReceiver(64);
            r.receive(reader(chunk(0, 1, "ab")));
            XpReader in = reader(chunk(5, 4, "xyz"));
            int res = r.receive(in);
            assertAll(
                () -> assertEquals(XpReliableReceiver.GAP, res, "gap"),
                () -> assertEquals("ab", stream(r), "nothing appended"),
                () -> assertEquals(2, r.offset(), "offset unchanged"),
                () -> assertEquals(4, r.ackLoops(), "acked with the chunk loop"),
                () -> assertEquals(0, in.remaining(), "reader past the chunk"));
        }

        @Test
        void aRepeatedChunkIsADuplicate() {
            XpReliableReceiver r = new XpReliableReceiver(64);
            r.receive(reader(chunk(0, 1, "ab")));
            int res = r.receive(reader(chunk(0, 2, "ab")));
            assertAll(
                () -> assertEquals(XpReliableReceiver.DUPLICATE, res, "duplicate"),
                () -> assertEquals("ab", stream(r), "not appended twice"),
                () -> assertEquals(2, r.ackLoops(), "acked again"));
        }

        @Test
        void anOverlappingChunkAppendsOnlyTheNewTail() {
            XpReliableReceiver r = new XpReliableReceiver(64);
            r.receive(reader(chunk(0, 1, "abc")));
            int res = r.receive(reader(chunk(1, 2, "bcde")));
            assertAll(
                () -> assertEquals(XpReliableReceiver.APPENDED, res, "appended"),
                () -> assertEquals("abcde", stream(r), "overlap trimmed"),
                () -> assertEquals(5, r.offset(), "offset counts new bytes only"),
                () -> assertEquals(2, r.lastLength(), "two new bytes"));
        }

        @Test
        void truncatedHeaderIsIncompleteAndSkipsTheDatagram() {
            XpReliableReceiver r = new XpReliableReceiver(64);
            byte[] c = chunk(0, 1, "ab");
            XpReader in = new XpReader().wrap(ByteBuffer.wrap(c), 0, 10);
            assertAll(
                () -> assertEquals(XpReliableReceiver.INCOMPLETE, r.receive(in), "header needs 11 bytes"),
                () -> assertEquals(10, in.pos(), "rest of datagram dropped"));
        }

        @Test
        void badLengthsAreRejected() {
            XpReliableReceiver r = new XpReliableReceiver(64);
            byte[] zero = new CPackets().c(XpProto.PKT_RELIABLE).hd(0).d(0).d(0).bytes();
            byte[] longer = new CPackets().c(XpProto.PKT_RELIABLE).hd(5).d(0).d(0).raw(new byte[] {1, 2}).bytes();
            XpReader in = reader(longer);
            assertAll(
                () -> assertEquals(XpReliableReceiver.BAD_LENGTH, r.receive(reader(zero)), "length 0 is bad"),
                () -> assertEquals(XpReliableReceiver.BAD_LENGTH, r.receive(in), "length past the datagram is bad"),
                () -> assertEquals(in.limit(), in.pos(), "rest of datagram dropped"),
                () -> assertEquals(0, r.offset(), "nothing taken"));
        }

        @Test
        void aChunkThatDoesNotFitOverflowsWithoutAdvancing() {
            XpReliableReceiver r = new XpReliableReceiver(4);
            r.receive(reader(chunk(0, 1, "abc")));
            XpReader in = reader(chunk(3, 2, "de"));
            assertAll(
                () -> assertEquals(XpReliableReceiver.OVERFLOW, r.receive(in), "only 1 byte free"),
                () -> assertEquals(3, r.offset(), "offset unchanged (will be resent)"),
                () -> assertEquals(0, in.remaining(), "reader past the chunk"));
        }

        @Test
        void exactlyFullBufferFits() {
            XpReliableReceiver r = new XpReliableReceiver(4);
            assertEquals(XpReliableReceiver.APPENDED, r.receive(reader(chunk(0, 1, "abcd"))), "4 bytes fit 4");
        }
    }

    @Nested
    @DisplayName("consume and reset")
    class Consume {
        @Test
        void consumeMovesTheRestToTheFront() {
            XpReliableReceiver r = new XpReliableReceiver(64);
            r.receive(reader(chunk(0, 1, "abcdef")));
            r.consume(2);
            String after2 = stream(r);
            r.consume(4);
            assertAll(
                () -> assertEquals("cdef", after2, "first two consumed"),
                () -> assertEquals(0, r.length(), "all consumed"),
                () -> assertEquals(6, r.offset(), "offset is the stream position, not the buffer"));
        }

        @Test
        void resetRestartsTheStream() {
            XpReliableReceiver r = new XpReliableReceiver(64);
            r.receive(reader(chunk(0, 3, "abc")));
            r.reset();
            assertAll(
                () -> assertEquals(0, r.offset(), "offset 0"),
                () -> assertEquals(0, r.length(), "empty"),
                () -> assertEquals(0, r.ackLoops(), "no ack loop"),
                () -> assertEquals(XpReliableReceiver.APPENDED, r.receive(reader(chunk(0, 1, "x"))),
                    "offset 0 accepted again"));
        }
    }
}
