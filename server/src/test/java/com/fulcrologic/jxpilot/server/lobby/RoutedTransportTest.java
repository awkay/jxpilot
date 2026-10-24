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
package com.fulcrologic.jxpilot.server.lobby;

import com.fulcrologic.jxpilot.server.net.NetTransport;
import com.fulcrologic.jxpilot.server.net.UdpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RoutedTransport: a game's datagram edge inside the lobby")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RoutedTransportTest {
    /** Records what the game sends. */
    static final class Sink implements NetTransport {
        final List<String> sent = new ArrayList<>();

        @Override
        public void setReceiver(UdpTransport.Receiver r) { }

        @Override
        public void poll(long timeoutNanos) { }

        @Override
        public void wakeup() { }

        @Override
        public void close() { }

        @Override
        public void send(int channel, SocketAddress to, ByteBuffer data) {
            byte[] b = new byte[data.remaining()];
            data.duplicate().get(b);
            sent.add(channel + "|" + to + "|" + new String(b, java.nio.charset.StandardCharsets.ISO_8859_1));
        }
    }

    record Got(int channel, SocketAddress from, String text, long nanos) { }

    static ByteBuffer bytes(String s) {
        return ByteBuffer.wrap(s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    static List<Got> collect(RoutedTransport t) {
        List<Got> got = new ArrayList<>();
        t.setReceiver((channel, from, data, nanos) -> {
            byte[] b = new byte[data.remaining()];
            data.get(b);
            got.add(new Got(channel, from, new String(b, java.nio.charset.StandardCharsets.ISO_8859_1), nanos));
        });
        return got;
    }

    static final SocketAddress A = new InetSocketAddress("127.0.0.1", 4001);
    static final SocketAddress B = new InetSocketAddress("127.0.0.1", 4002);

    @Nested
    @DisplayName("offer and poll")
    class OfferPoll {
        @Test
        @DisplayName("a datagram is delivered with its channel, sender, bytes and the time it was offered, in order")
        void delivers() {
            RoutedTransport t = new RoutedTransport(new Sink());
            List<Got> got = collect(t);
            t.offer(2, A, bytes("first"), 111);
            t.offer(0, B, bytes("second"), 222);
            t.poll(0);
            assertEquals(List.of(new Got(2, A, "first", 111), new Got(0, B, "second", 222)), got,
                "both, in the order offered, each with its own channel and time");
        }

        @Test
        @DisplayName("offer copies from the buffer's position to its limit and leaves the position alone")
        void copies() {
            RoutedTransport t = new RoutedTransport(new Sink());
            List<Got> got = collect(t);
            ByteBuffer b = bytes("xxhello");
            b.position(2);
            t.offer(0, A, b, 1);
            b.put(2, (byte) 'J'); // changing the source afterwards must not change what was queued
            t.poll(0);
            assertAll(
                () -> assertEquals(2, b.position(), "the caller's position is unchanged"),
                () -> assertEquals("hello", got.get(0).text(), "only the remaining bytes, as they were when offered"));
        }

        @Test
        @DisplayName("nothing waiting and a zero timeout returns at once without calling the receiver")
        void empty() {
            RoutedTransport t = new RoutedTransport(new Sink());
            List<Got> got = collect(t);
            t.poll(0);
            assertTrue(got.isEmpty(), "no delivery");
        }

        @Test
        @DisplayName("poll waits for a datagram offered from another thread and returns when it arrives")
        void waits() throws Exception {
            RoutedTransport t = new RoutedTransport(new Sink());
            List<Got> got = collect(t);
            Thread offerer = new Thread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
                t.offer(0, A, bytes("late"), 5);
            });
            offerer.start();
            long start = System.nanoTime();
            t.poll(10_000_000_000L);
            long waited = System.nanoTime() - start;
            offerer.join();
            assertAll(
                () -> assertEquals(1, got.size(), "the datagram was delivered"),
                () -> assertTrue(waited < 5_000_000_000L, "poll returned when it came, not at the 10 s timeout"));
        }

        @Test
        @DisplayName("a timeout with nothing offered returns after about that long")
        void times() {
            RoutedTransport t = new RoutedTransport(new Sink());
            long start = System.nanoTime();
            t.poll(60_000_000L);
            long waited = System.nanoTime() - start;
            assertTrue(waited >= 50_000_000L && waited < 2_000_000_000L, "waited " + waited / 1e6 + " ms");
        }

        @Test
        @DisplayName("wakeup ends a poll that is waiting, delivering nothing")
        void wakeup() throws Exception {
            RoutedTransport t = new RoutedTransport(new Sink());
            List<Got> got = collect(t);
            Thread waker = new Thread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
                t.wakeup();
            });
            waker.start();
            long start = System.nanoTime();
            t.poll(10_000_000_000L);
            long waited = System.nanoTime() - start;
            waker.join();
            assertAll(
                () -> assertTrue(waited < 5_000_000_000L, "returned long before the timeout"),
                () -> assertTrue(got.isEmpty(), "the wake marker is not a datagram"));
        }

        @Test
        @DisplayName("an interrupted poll returns and keeps the interrupt flag")
        void interrupted() {
            RoutedTransport t = new RoutedTransport(new Sink());
            Thread.currentThread().interrupt();
            t.poll(10_000_000_000L);
            assertTrue(Thread.interrupted(), "the flag is still set (and now cleared by this check)");
        }

        @Test
        @DisplayName("more than MAX_DRAIN waiting: one poll delivers MAX_DRAIN, the next the rest")
        void drainLimit() {
            RoutedTransport t = new RoutedTransport(new Sink());
            List<Got> got = collect(t);
            int n = RoutedTransport.MAX_DRAIN + 10;
            for (int i = 0; i < n; i++) {
                t.offer(0, A, bytes("p" + i), i);
            }
            t.poll(0);
            int first = got.size();
            t.poll(0);
            assertAll(
                () -> assertEquals(RoutedTransport.MAX_DRAIN, first, "the first poll stops at the limit"),
                () -> assertEquals(n, got.size(), "the second delivers the remainder"),
                () -> assertEquals(n, got.stream().map(Got::text).distinct().count(), "none lost, none twice"));
        }
    }

    @Nested
    @DisplayName("limits and closing")
    class Limits {
        @Test
        @DisplayName("when SLOTS datagrams wait, the next is dropped and counted; delivering frees the slots again")
        void poolExhausted() {
            RoutedTransport t = new RoutedTransport(new Sink());
            List<Got> got = collect(t);
            boolean allTaken = true;
            for (int i = 0; i < RoutedTransport.SLOTS; i++) {
                allTaken &= t.offer(0, A, bytes("x"), i);
            }
            boolean extra = t.offer(0, A, bytes("overflow"), 0);
            long dropped = t.dropped();
            while (got.size() < RoutedTransport.SLOTS) {
                t.poll(0);
            }
            boolean afterDrain = t.offer(0, A, bytes("again"), 0);
            boolean allTakenFinal = allTaken;
            assertAll(
                () -> assertTrue(allTakenFinal, "SLOTS datagrams fit"),
                () -> assertFalse(extra, "one more is refused"),
                () -> assertEquals(1, dropped, "and counted"),
                () -> assertEquals(RoutedTransport.SLOTS, got.size(), "the waiting ones were all delivered"),
                () -> assertTrue(afterDrain, "slots are free again after delivery"));
        }

        @Test
        @DisplayName("after close offers are refused without counting a drop")
        void closed() {
            RoutedTransport t = new RoutedTransport(new Sink());
            t.close();
            assertAll(
                () -> assertFalse(t.offer(0, A, bytes("x"), 1), "refused"),
                () -> assertEquals(0, t.dropped(), "not a pool drop"));
        }

        @Test
        @DisplayName("a receiver that throws still returns the slot to the pool")
        void receiverThrows() {
            RoutedTransport t = new RoutedTransport(new Sink());
            t.setReceiver((c, f, d, n) -> {
                throw new IllegalStateException("boom");
            });
            for (int i = 0; i < RoutedTransport.SLOTS; i++) {
                t.offer(0, A, bytes("x"), i);
                try {
                    t.poll(0);
                } catch (IllegalStateException expected) {
                    // the game loop's own guard handles it in production
                }
            }
            assertTrue(t.offer(0, A, bytes("y"), 0), "the pool was not drained by the failures");
        }
    }

    @Nested
    @DisplayName("send")
    class Sending {
        @Test
        @DisplayName("replies go to the lobby's socket on the channel the peer arrived on")
        void sendPassesThrough() {
            Sink sink = new Sink();
            RoutedTransport t = new RoutedTransport(sink);
            t.send(3, A, bytes("reply"));
            assertEquals(List.of("3|" + A + "|reply"), sink.sent, "forwarded unchanged");
        }
    }
}
