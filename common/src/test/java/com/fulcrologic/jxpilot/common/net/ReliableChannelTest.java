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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ReliableChannel")
class ReliableChannelTest {
    static final long MS = 1_000_000L;

    /** Records delivered messages as "type:firstPayloadByte". */
    static final class Recorder implements ReliableHandler {
        final List<String> got = new ArrayList<>();

        @Override
        public void onReliableMessage(int type, ByteBuffer payload) {
            got.add(type + ":" + (payload.hasRemaining() ? payload.get() : -1) + "/" + payload.remaining());
        }
    }

    static void queue(ReliableChannel ch, int type, int value) {
        ch.begin(type).put((byte) value);
        ch.end();
    }

    static ByteBuffer packet(ReliableChannel ch, long now) {
        ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
        ch.write(b, now);
        b.flip();
        return b;
    }

    @Nested
    @DisplayName("send/receive")
    class SendReceive {
        @Test
        @DisplayName("delivers in order, resends only after the interval, stops after ack")
        void basicFlow() {
            ReliableChannel tx = new ReliableChannel(8, 64, 100);
            ReliableChannel rx = new ReliableChannel(8, 64, 100);
            Recorder rec = new Recorder();
            queue(tx, 1, 10);
            queue(tx, 2, 20);
            boolean wantsInitially = tx.wantsSend(0);
            ByteBuffer p1 = packet(tx, 0);
            int count1 = p1.get(0);
            boolean wantsSoon = tx.wantsSend(50 * MS);
            ByteBuffer early = ByteBuffer.allocate(100);
            int earlyCount = tx.write(early, 50 * MS);
            int earlyPos = early.position();
            boolean wantsLate = tx.wantsSend(100 * MS);
            int delivered = rx.read(p1, rec);
            int ack = rx.ack();
            boolean ackPending = rx.consumeAckPending();
            boolean ackPendingTwice = rx.consumeAckPending();
            tx.onAck(ack, 0L);
            assertAll(
                () -> assertTrue(wantsInitially, "wants to send new messages"),
                () -> assertEquals(2, count1, "packs both messages into one body"),
                () -> assertFalse(wantsSoon, "does not resend before the interval"),
                () -> assertEquals(0, earlyCount, "writes nothing when nothing is due"),
                () -> assertEquals(0, earlyPos, "leaves the buffer untouched when nothing is due"),
                () -> assertTrue(wantsLate, "resends once the interval has passed"),
                () -> assertEquals(2, delivered, "delivers both messages"),
                () -> assertEquals(List.of("1:10/0", "2:20/0"), rec.got, "delivers type and payload in order"),
                () -> assertEquals(2, ack, "acks the next expected sequence"),
                () -> assertTrue(ackPending, "flags that an ack should be sent"),
                () -> assertFalse(ackPendingTwice, "clears the ack flag once consumed"),
                () -> assertEquals(0, tx.unacked(), "drops acknowledged messages"),
                () -> assertFalse(tx.wantsSend(1000 * MS), "has nothing to resend after the ack"));
        }

        @Test
        @DisplayName("duplicates are ignored and out-of-order messages are buffered then delivered in order")
        void reorderAndDuplicates() {
            ReliableChannel tx = new ReliableChannel(8, 64, 100);
            ReliableChannel rx = new ReliableChannel(8, 64, 100);
            Recorder rec = new Recorder();
            queue(tx, 1, 1);
            ByteBuffer lost = packet(tx, 0);
            queue(tx, 1, 2);
            queue(tx, 1, 3);
            ByteBuffer later = packet(tx, 10 * MS);
            int deliveredEarly = rx.read(later, rec);
            int ackBeforeGap = rx.ack();
            int deliveredAfterGap = rx.read(lost, rec);
            lost.rewind();
            int dup = rx.read(lost, rec);
            assertAll(
                () -> assertEquals(0, deliveredEarly, "holds messages that arrive before a gap"),
                () -> assertEquals(0, ackBeforeGap, "keeps acking the missing sequence"),
                () -> assertEquals(3, deliveredAfterGap, "delivers the gap and the buffered messages"),
                () -> assertEquals(List.of("1:1/0", "1:2/0", "1:3/0"), rec.got, "delivers in sequence order"),
                () -> assertEquals(0, dup, "ignores an old duplicate"),
                () -> assertEquals(0, new ReliableChannel(4, 16, 1).read(ByteBuffer.wrap(new byte[] {0}), (t, p) -> { }),
                    "a body with count 0 is valid and delivers nothing"),
                () -> assertEquals(3, rx.ack(), "acks past all delivered messages"));
        }

        @Test
        @DisplayName("messages at or beyond the window are dropped; the last in-window one is buffered")
        void windowEdge() {
            ReliableChannel rx = new ReliableChannel(4, 16, 1);
            Recorder rec = new Recorder();
            ByteBuffer atWindow = ByteBuffer.wrap(new byte[] {1, 0, 4, 0, 1, 9});
            ByteBuffer lastIn = ByteBuffer.wrap(new byte[] {1, 0, 3, 0, 1, 8});
            int r1 = rx.read(atWindow, rec);
            int r2 = rx.read(lastIn, rec);
            ByteBuffer fill = ByteBuffer.wrap(new byte[] {3, 0, 0, 0, 1, 5, 0, 1, 0, 1, 6, 0, 2, 0, 1, 7});
            int r3 = rx.read(fill, rec);
            assertAll(
                () -> assertEquals(0, r1, "sequence == window is not delivered"),
                () -> assertEquals(0, r2, "sequence window-1 waits for the gap"),
                () -> assertEquals(4, r3, "filling the gap delivers 0,1,2 and the buffered 3"),
                () -> assertEquals(List.of("5:-1/0", "6:-1/0", "7:-1/0", "8:-1/0"), rec.got,
                    "the message at the window edge was dropped, not buffered"),
                () -> assertEquals(4, rx.ack(), "acks up to 4, so 4 must be resent"));
        }

        @Test
        @DisplayName("out-of-order delivery across the 65535 -> 0 wrap")
        void reorderAcrossWrap() {
            ReliableChannel tx = new ReliableChannel(8, 16, 1);
            ReliableChannel rx = new ReliableChannel(8, 16, 1);
            Recorder rec = new Recorder();
            long now = 0;
            for (int i = 0; i < 65534; i++) {
                queue(tx, 1, 0);
                ByteBuffer p = packet(tx, now);
                now += 2 * MS;
                rx.read(p, rec);
                tx.onAck(rx.ack(), 0L);
            }
            rec.got.clear();
            queue(tx, 2, 1); // seq 65534
            ByteBuffer first = packet(tx, now);
            queue(tx, 2, 2); // seq 65535
            queue(tx, 2, 3); // seq 0
            ByteBuffer rest = packet(tx, now);
            int early = rx.read(rest, rec);
            int all = rx.read(first, rec);
            assertAll(
                () -> assertEquals(0, early, "65535 and 0 wait for 65534"),
                () -> assertEquals(3, all, "65534 releases the buffered 65535 and 0"),
                () -> assertEquals(List.of("2:1/0", "2:2/0", "2:3/0"), rec.got, "delivered in sequence order across the wrap"),
                () -> assertEquals(1, rx.ack(), "ack wrapped to 1"));
        }

        @Test
        @DisplayName("sequence numbers wrap at 16 bits")
        void wraps() {
            ReliableChannel tx = new ReliableChannel(4, 16, 1);
            ReliableChannel rx = new ReliableChannel(4, 16, 1);
            Recorder rec = new Recorder();
            long now = 0;
            for (int i = 0; i < 70000; i++) {
                queue(tx, 5, i & 0x7F);
                ByteBuffer p = packet(tx, now);
                now += 2 * MS;
                rx.read(p, rec);
                tx.onAck(rx.ack(), 0L);
                rec.got.clear();
            }
            queue(tx, 6, 99);
            rx.read(packet(tx, now), rec);
            assertAll(
                () -> assertEquals(List.of("6:99/0"), rec.got, "still delivers after 16-bit wrap"),
                () -> assertEquals(70001 & 0xFFFF, rx.ack(), "ack wraps modulo 65536"));
        }
    }

    @Nested
    @DisplayName("overflow queue")
    class Overflow {
        @Test
        @DisplayName("messages beyond the window wait in order and flow in as acks free slots")
        void overflow() {
            ReliableChannel tx = new ReliableChannel(2, 16, 1);
            ReliableChannel rx = new ReliableChannel(2, 16, 1);
            Recorder rec = new Recorder();
            for (int i = 0; i < 5; i++) {
                queue(tx, 1, i);
            }
            int inWindow = tx.unacked();
            int waiting = tx.overflowing();
            ByteBuffer opened = tx.begin(2);
            boolean openedIsOverflow = tx.overflowing() == 3 && opened.capacity() == 16;
            tx.abort();
            int afterAbort = tx.overflowing();
            long now = 0;
            int rounds = 0;
            while ((tx.unacked() > 0 || tx.overflowing() > 0) && rounds++ < 20) {
                ByteBuffer p = ByteBuffer.allocate(200);
                tx.write(p, now);
                p.flip();
                rx.read(p, rec);
                tx.onAck(rx.ack(), now);
                now += 2 * MS;
            }
            int pooledAfterDrain = tx.pooledBuffers();
            queue(tx, 3, 9);
            int straightIntoWindow = tx.unacked();
            queue(tx, 3, 9);
            queue(tx, 3, 9); // overflows: takes a pooled buffer
            int pooledWhileOverflowing = tx.pooledBuffers();
            tx.reset();
            int pooledAfterReset = tx.pooledBuffers();
            assertAll(
                () -> assertEquals(2, inWindow, "the window holds 2"),
                () -> assertEquals(3, waiting, "3 messages overflow instead of throwing"),
                () -> assertTrue(openedIsOverflow, "begin while overflowing hands out an overflow buffer"),
                () -> assertEquals(3, afterAbort, "abort returns the overflow buffer without queueing"),
                () -> assertEquals(List.of("1:0/0", "1:1/0", "1:2/0", "1:3/0", "1:4/0"), rec.got,
                    "all 5 arrive in order"),
                () -> assertEquals(1, straightIntoWindow, "once drained, new messages go straight into the window"),
                () -> assertEquals(4, pooledAfterDrain, "the 3 drained overflow buffers and the aborted one are pooled"),
                () -> assertEquals(3, pooledWhileOverflowing, "an overflowing message takes a pooled buffer"),
                () -> assertEquals(4, pooledAfterReset, "reset returns overflowing buffers to the pool"),
                () -> assertEquals(0, tx.overflowing(), "reset clears the overflow"),
                () -> assertEquals(0, tx.unacked(), "reset clears the window"));
        }

        @Test
        @DisplayName("a join with 31 existing players plus map chunks never hits 'window full'")
        void bigJoin() throws Exception {
            ReliableChannel tx = new ReliableChannel();
            ReliableChannel rx = new ReliableChannel();
            ConfigTransfer.Blob blob = ConfigTransfer.encode(new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("..", "maps", "planetx.xp")), java.nio.charset.StandardCharsets.ISO_8859_1));
            Msg.Welcome w = new Msg.Welcome();
            blob.fill(w);
            w.send(tx);
            ConfigTransfer.Sender sender = new ConfigTransfer.Sender(blob);
            int chunks = 0;
            while (!sender.done()) {
                // pump everything at once: overflow absorbs it
                sender.pump(tx, -1_000_000);
                chunks++;
            }
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            Msg.Score sc = new Msg.Score();
            for (int id = 0; id < 32; id++) {
                j.id = id;
                j.name.setLength(0);
                j.name.append("player").append(id);
                j.send(tx);
                sc.id = id;
                sc.send(tx);
            }
            int queued = tx.unacked() + tx.overflowing();
            List<Integer> types = new ArrayList<>();
            ConfigTransfer.Receiver[] recv = {null};
            boolean[] complete = {false};
            long now = 0;
            for (int round = 0; round < 200 && (tx.unacked() > 0 || tx.overflowing() > 0); round++) {
                ByteBuffer p = ByteBuffer.allocate(Protocol.MAX_PACKET);
                tx.write(p, now);
                p.flip();
                rx.read(p, (type, payload) -> {
                    types.add(type);
                    if (type == ReliableType.WELCOME) {
                        Msg.Welcome got = new Msg.Welcome();
                        got.read(payload);
                        recv[0] = new ConfigTransfer.Receiver(got);
                    } else if (type == ReliableType.DATA_CHUNK) {
                        complete[0] = recv[0].onChunk(payload);
                    }
                });
                tx.onAck(rx.ack(), now);
                now += 1_000_000L;
            }
            int expectedChunks = (blob.compressed.length + Protocol.DATA_CHUNK_SIZE - 1) / Protocol.DATA_CHUNK_SIZE;
            int total = 1 + expectedChunks + 64;
            int chunkCount = chunks;
            assertAll(
                () -> assertEquals(1, chunkCount, "one pump queues all chunks when the reserve allows it"),
                () -> assertEquals(total, queued, "WELCOME + all chunks + 32 joins + 32 scores are queued"),
                () -> assertTrue(total > Protocol.RELIABLE_WINDOW, "more than the 32-slot window"),
                () -> assertEquals(total, types.size(), "everything is delivered"),
                () -> assertEquals(ReliableType.WELCOME, types.get(0), "WELCOME first"),
                () -> assertEquals(ReliableType.PLAYER_JOIN, types.get(1 + expectedChunks), "joins after the map"),
                () -> assertTrue(complete[0], "the map arrives complete"));
        }
    }

    @Nested
    @DisplayName("adaptive resend timeout")
    class Rto {
        @Test
        @DisplayName("RFC 6298 smoothing with Karn's rule and clamping")
        void rto() {
            ReliableChannel ch = new ReliableChannel(8, 16, 150, 50, 2000);
            long before = ch.resendTimeoutNanos();
            queue(ch, 1, 1);
            ch.write(ByteBuffer.allocate(100), 0);
            ch.onAck(1, 40 * MS); // first sample 40 ms: srtt 40, rttvar 20 -> 120
            long first = ch.resendTimeoutNanos();
            queue(ch, 1, 2);
            ch.write(ByteBuffer.allocate(100), 100 * MS);
            ch.onAck(2, 120 * MS); // R = 20: rttvar = (60+20)/4 = 20, srtt = (280+20)/8 = 37.5 -> 117.5
            long second = ch.resendTimeoutNanos();
            queue(ch, 1, 3);
            ch.write(ByteBuffer.allocate(100), 200 * MS);
            ch.write(ByteBuffer.allocate(100), 400 * MS); // resent: Karn's rule, no sample
            ch.onAck(3, 410 * MS);
            long afterRetransmit = ch.resendTimeoutNanos();
            ReliableChannel fast = new ReliableChannel(8, 16, 150, 50, 2000);
            queue(fast, 1, 1);
            fast.write(ByteBuffer.allocate(100), 0);
            fast.onAck(1, 1 * MS);
            ReliableChannel slow = new ReliableChannel(8, 16, 150, 50, 2000);
            queue(slow, 1, 1);
            slow.write(ByteBuffer.allocate(100), 0);
            slow.onAck(1, 5000 * MS);
            ReliableChannel dflt = new ReliableChannel();
            assertAll(
                () -> assertEquals(150 * MS, before, "starts at the initial timeout"),
                () -> assertEquals(120 * MS, first, "first sample: srtt + 4 * (R/2) = 3R"),
                () -> assertEquals(117_500_000L, second, "second sample smooths srtt and rttvar"),
                () -> assertEquals(117_500_000L, afterRetransmit, "a retransmitted message gives no sample"),
                () -> assertEquals(50 * MS, fast.resendTimeoutNanos(), "the timeout never drops below the floor"),
                () -> assertEquals(2000 * MS, slow.resendTimeoutNanos(), "the timeout never exceeds the ceiling"),
                () -> assertEquals(Protocol.RELIABLE_RESEND_MS * MS, dflt.resendTimeoutNanos(),
                    "the default channel starts at RELIABLE_RESEND_MS"));
        }

        @Test
        @DisplayName("a zero first sample is still the first sample")
        void zeroSample() {
            ReliableChannel ch = new ReliableChannel(8, 16, 150, 1, 2000);
            queue(ch, 1, 1);
            ch.write(ByteBuffer.allocate(100), 0);
            ch.onAck(1, 0); // R = 0
            long afterZero = ch.resendTimeoutNanos();
            queue(ch, 1, 2);
            ch.write(ByteBuffer.allocate(100), 0);
            ch.onAck(2, 40 * MS); // srtt 0 -> 5 ms, rttvar 0 -> 10 ms: 45 ms
            assertAll(
                () -> assertEquals(1 * MS, afterZero, "R = 0 gives srtt 0, rttvar 0, clamped to the 1 ms floor"),
                () -> assertEquals(45 * MS, ch.resendTimeoutNanos(), "the next sample smooths from srtt 0"));
        }

        @Test
        @DisplayName("reset forgets the RTT estimate")
        void resetRto() {
            ReliableChannel ch = new ReliableChannel(8, 16, 150, 50, 2000);
            queue(ch, 1, 1);
            ch.write(ByteBuffer.allocate(100), 0);
            ch.onAck(1, 40 * MS);
            ch.reset();
            long afterReset = ch.resendTimeoutNanos();
            queue(ch, 1, 1);
            ch.write(ByteBuffer.allocate(100), 0);
            ch.onAck(1, 100 * MS);
            assertAll(
                () -> assertEquals(150 * MS, afterReset, "reset restores the initial timeout"),
                () -> assertEquals(300 * MS, ch.resendTimeoutNanos(), "the next sample is treated as the first (3R)"));
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {
        @Test
        @DisplayName("window, buffer size, acks and protocol misuse")
        void limits() {
            ReliableChannel tx = new ReliableChannel(2, 64, 100);
            queue(tx, 1, 1);
            queue(tx, 1, 2);
            int free = tx.freeSlots();
            ByteBuffer tiny = ByteBuffer.allocate(5);
            int tinyCount = tx.write(tiny, 0);
            ByteBuffer one = ByteBuffer.allocate(1 + 4 + 2 + 4 + 1);
            int oneCount = tx.write(one, 0);
            tx.onAck(5, 0L); // bogus ack beyond what was sent
            int unackedAfterBogus = tx.unacked();
            tx.onAck(1, 0L);
            int unackedAfterOne = tx.unacked();
            // with txBase = 1: a bogus ack must still be rejected, a stale one ignored
            queue(tx, 1, 3);
            tx.onAck(4, 0L);
            int afterBogusBase1 = tx.unacked();
            tx.onAck(0, 0L);
            int afterStale = tx.unacked();
            tx.onAck(3, 0L);
            int afterGood = tx.unacked();
            ReliableChannel open = new ReliableChannel(2, 64, 100);
            open.begin(1);
            assertAll(
                () -> assertEquals(0, free, "reports a full window"),
                () -> assertEquals(0, tinyCount, "writes nothing into a buffer too small for any message"),
                () -> assertEquals(1, oneCount, "writes only the messages that fit"),
                () -> assertEquals(2, unackedAfterBogus, "ignores an ack for unsent sequences"),
                () -> assertEquals(1, unackedAfterOne, "a partial cumulative ack releases only older messages"),
                () -> assertEquals(2, afterBogusBase1, "with txBase 1, an ack beyond the sent range is ignored"),
                () -> assertEquals(2, afterStale, "a stale ack (behind txBase) is ignored"),
                () -> assertEquals(0, afterGood, "a valid cumulative ack releases everything before it"),
                () -> assertThrows(IllegalStateException.class, () -> open.begin(2), "refuses a second open message"),
                () -> assertThrows(IllegalStateException.class, () -> new ReliableChannel(2, 64, 1).end(),
                    "refuses end without begin"),
                () -> assertThrows(IllegalArgumentException.class, () -> new ReliableChannel(3, 64, 1),
                    "requires a power-of-two window"),
                () -> assertThrows(IllegalArgumentException.class, () -> new ReliableChannel(32768, 8, 1),
                    "limits the window to 16384"),
                () -> assertEquals(16384, new ReliableChannel(16384, 1, 1).freeSlots(), "accepts a 16384 window"));
        }

        @Test
        @DisplayName("out-of-order messages up to maxMessage are buffered, larger ones dropped")
        void maxSizeBuffered() {
            ReliableChannel rx = new ReliableChannel(4, 4, 1);
            Recorder rec = new Recorder();
            // seq 1 (len 4 = maxMessage) and seq 2 (len 5) arrive before seq 0
            ByteBuffer early = ByteBuffer.wrap(new byte[] {2, 0, 1, 0, 4, 7, 1, 1, 1, 0, 2, 0, 5, 8, 2, 2, 2, 2});
            int r1 = rx.read(early, rec);
            ByteBuffer zero = ByteBuffer.wrap(new byte[] {1, 0, 0, 0, 1, 6});
            int r2 = rx.read(zero, rec);
            assertAll(
                () -> assertEquals(0, r1, "nothing is delivered before the gap is filled"),
                () -> assertEquals(2, r2, "seq 0 releases the buffered max-size seq 1 only"),
                () -> assertEquals(List.of("6:-1/0", "7:1/2"), rec.got, "the 4-byte message was buffered intact"),
                () -> assertEquals(2, rx.ack(), "the oversize seq 2 was dropped and must be resent"));
        }

        @Test
        @DisplayName("resend timing measured from a first send at t != 0")
        void resendFromNonZero() {
            ReliableChannel tx = new ReliableChannel(4, 16, 100);
            queue(tx, 1, 1);
            tx.write(ByteBuffer.allocate(100), 500 * MS);
            assertAll(
                () -> assertFalse(tx.wantsSend(599 * MS), "not due 99 ms after a send at 500 ms"),
                () -> assertTrue(tx.wantsSend(600 * MS), "due 100 ms after a send at 500 ms"));
        }

        @Test
        @DisplayName("exact buffer fits, the 255 message cap and the resend instant")
        void boundaries() {
            ReliableChannel tx = new ReliableChannel(512, 8, 100);
            tx.begin(7);
            tx.end();
            ByteBuffer six = ByteBuffer.allocate(6);
            int sixCount = tx.write(six, 0);
            boolean dueAtInterval = tx.wantsSend(100 * MS);
            boolean dueBefore = tx.wantsSend(100 * MS - 1);
            ByteBuffer exactAgain = ByteBuffer.allocate(6);
            int notDue = tx.write(exactAgain, 100 * MS - 1);
            int resent = tx.write(exactAgain, 100 * MS);
            for (int i = 0; i < 299; i++) {
                tx.begin(1);
                tx.end();
            }
            ByteBuffer huge = ByteBuffer.allocate(4000);
            int capped = tx.write(huge, 200 * MS);
            ReliableChannel rx = new ReliableChannel(512, 8, 100);
            huge.flip();
            int delivered = rx.read(huge, (t, p) -> { });
            ByteBuffer fourExact = ByteBuffer.wrap(new byte[] {1, 0, 0, 0, 0});
            ByteBuffer threeHeader = ByteBuffer.wrap(new byte[] {1, 0, 0, 0});
            ByteBuffer lenExact = ByteBuffer.wrap(new byte[] {1, 0, 0, 0, 2, 5, 6});
            ReliableChannel rx2 = new ReliableChannel(4, 8, 1);
            ReliableChannel rx3 = new ReliableChannel(4, 8, 1);
            assertAll(
                () -> assertEquals(1, sixCount, "a type-only message fits exactly in 6 bytes (count + seq + len + type)"),
                () -> assertTrue(dueAtInterval, "a message is due exactly at the resend interval"),
                () -> assertFalse(dueBefore, "a message is not due 1 ns before the interval"),
                () -> assertEquals(0, notDue, "write skips messages that are not due"),
                () -> assertEquals(1, resent, "write resends exactly at the interval"),
                () -> assertEquals(255, capped, "one body carries at most 255 messages"),
                () -> assertEquals(255, delivered, "all 255 are delivered"),
                () -> assertEquals(-1, rx2.read(threeHeader, (t, p) -> { }), "3 bytes of message header are rejected"),
                () -> assertEquals(-1, rx2.read(fourExact, (t, p) -> { }), "a 4-byte header with length 0 is rejected"),
                () -> assertEquals(1, rx3.read(lenExact, (t, p) -> { }), "a length equal to the remaining bytes is accepted"));
        }

        @Test
        @DisplayName("malformed bodies are rejected and reset clears state")
        void malformedAndReset() {
            ReliableChannel stale = new ReliableChannel(4, 16, 1);
            stale.read(ByteBuffer.wrap(new byte[] {1, 0, 1, 0, 1, 9}), (t, p) -> { }); // buffers seq 1
            stale.reset();
            Recorder afterReset = new Recorder();
            stale.read(ByteBuffer.wrap(new byte[] {1, 0, 0, 0, 1, 8}), afterReset);
            ReliableChannel rx = new ReliableChannel(4, 16, 1);
            Recorder rec = new Recorder();
            ByteBuffer empty = ByteBuffer.allocate(0);
            ByteBuffer truncatedHeader = ByteBuffer.wrap(new byte[] {1, 0, 0});
            ByteBuffer badLen = ByteBuffer.wrap(new byte[] {1, 0, 0, 0, 9, 1});
            ByteBuffer zeroLen = ByteBuffer.wrap(new byte[] {1, 0, 0, 0, 0});
            ByteBuffer good = ByteBuffer.wrap(new byte[] {1, 0, 0, 0, 1, 7});
            int r1 = rx.read(empty, rec);
            int r2 = rx.read(truncatedHeader, rec);
            int r3 = rx.read(badLen, rec);
            int r4 = rx.read(zeroLen, rec);
            int r5 = rx.read(good, rec);
            int ackBefore = rx.ack();
            ReliableChannel tx = new ReliableChannel(4, 16, 1);
            queue(tx, 1, 1);
            tx.reset();
            rx.reset();
            assertAll(
                () -> assertEquals(-1, r1, "rejects an empty body"),
                () -> assertEquals(-1, r2, "rejects a truncated message header"),
                () -> assertEquals(-1, r3, "rejects a length beyond the body"),
                () -> assertEquals(-1, r4, "rejects a zero length message"),
                () -> assertEquals(1, r5, "accepts a one byte message"),
                () -> assertEquals(List.of("7:-1/0"), rec.got, "delivers a type-only message with an empty payload"),
                () -> assertEquals(1, ackBefore, "advances the ack"),
                () -> assertEquals(0, rx.ack(), "reset clears the receive sequence"),
                () -> assertEquals(0, tx.unacked(), "reset clears queued messages"),
                () -> assertEquals(List.of("8:-1/0"), afterReset.got, "reset discards buffered out-of-order messages"));
        }
    }
}
