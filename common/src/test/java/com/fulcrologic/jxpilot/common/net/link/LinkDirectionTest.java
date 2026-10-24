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
package com.fulcrologic.jxpilot.common.net.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LinkDirection")
class LinkDirectionTest {
    static final long MS = 1_000_000L;

    static ByteBuffer packet(int... bytes) {
        ByteBuffer b = ByteBuffer.allocate(bytes.length);
        for (int x : bytes) {
            b.put((byte) x);
        }
        return b.flip();
    }

    static ByteBuffer sized(int n, int fill) {
        ByteBuffer b = ByteBuffer.allocate(n);
        for (int i = 0; i < n; i++) {
            b.put((byte) fill);
        }
        return b.flip();
    }

    /** The first byte of the packet due at {@code now}, or -1. */
    static int take(LinkDirection d, long now) {
        ByteBuffer dst = ByteBuffer.allocate(64);
        int n = d.receive(dst, now);
        return n < 0 ? -1 : dst.get(0) & 0xFF;
    }

    /** Delivery time of each packet in send order: sends {@code count} 1-byte packets (byte = index) at {@code times}. */
    static long[] deliveries(LinkProfile p, long seed, long[] times) {
        LinkDirection d = new LinkDirection(p, seed, 2 * times.length + 8, 64);
        long[] at = new long[times.length];
        java.util.Arrays.fill(at, -1);
        for (int i = 0; i < times.length; i++) {
            d.send(packet(i & 0xFF, i >> 8), times[i], 0, null);
        }
        ByteBuffer dst = ByteBuffer.allocate(8);
        while (d.inFlight() > 0) {
            long t = d.nextDue();
            dst.clear();
            d.receive(dst, t);
            int idx = (dst.get(0) & 0xFF) | (dst.get(1) & 0xFF) << 8;
            if (at[idx] < 0) {
                at[idx] = t;
            }
        }
        return at;
    }

    /** The draws one send consumes, replayed from the same seed. */
    record Draws(double loss, double transition, double gauss, double spike, double pareto, double reorder,
                 double dup) {
        static Draws next(LinkRandom r, double xm, double shape) {
            double loss = r.nextDouble();
            double tr = r.nextDouble();
            double g = r.nextGaussian();
            double s = r.nextDouble();
            double p = r.nextPareto(xm, shape);
            double ro = r.nextDouble();
            double du = r.nextDouble();
            return new Draws(loss, tr, g, s, p, ro, du);
        }
    }

    @Nested
    @DisplayName("perfect link")
    class Perfect {
        @Test
        @DisplayName("delivers the same bytes, tag and address at the send time, never before; the buffer is consumed")
        void passesThrough() {
            LinkDirection d = new LinkDirection(new LinkProfile(), 1, 8, 64);
            Object addr = new Object();
            ByteBuffer p = packet(7, 8, 9);
            boolean queued = d.send(p, 100, 42, addr);
            ByteBuffer early = ByteBuffer.allocate(16);
            int before = d.receive(early, 99);
            ByteBuffer dst = ByteBuffer.allocate(16);
            int n = d.receive(dst, 100);
            byte[] got = new byte[3];
            dst.flip().get(got);
            assertAll(
                () -> assertTrue(queued, "a packet on a perfect link is in flight"),
                () -> assertEquals(p.limit(), p.position(), "send consumes the buffer like a channel send"),
                () -> assertEquals(-1, before, "nothing is delivered before its time"),
                () -> assertEquals(0, early.position(), "an empty receive writes nothing"),
                () -> assertEquals(3, n, "receive returns the datagram length"),
                () -> assertArrayEquals(new byte[] {7, 8, 9}, got, "the bytes arrive unchanged"),
                () -> assertEquals(42, d.lastTag(), "the tag travels with the packet"),
                () -> assertSame(addr, d.lastAddress(), "the address travels with the packet"),
                () -> assertEquals(1, d.sent(), "one sent"),
                () -> assertEquals(1, d.delivered(), "one delivered"),
                () -> assertEquals(Long.MAX_VALUE, d.nextDue(), "nothing left in flight"),
                () -> assertEquals(-1, d.receive(dst, 1000), "an empty link delivers nothing"));
        }

        @Test
        @DisplayName("packets due at the same time come out in send order; earlier due times first")
        void ordering() {
            LinkDirection d = new LinkDirection(new LinkProfile().fifo(false), 1, 8, 64);
            d.send(packet(1), 50, 0, null);
            d.send(packet(2), 10, 0, null);
            d.send(packet(3), 10, 0, null);
            d.send(packet(4), 30, 0, null);
            long next = d.nextDue();
            List<Integer> got = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                got.add(take(d, 100));
            }
            assertAll(
                () -> assertEquals(10, next, "nextDue is the earliest delivery"),
                () -> assertEquals(List.of(2, 3, 4, 1), got, "by due time, ties in send order"));
        }

        @Test
        @DisplayName("1000 packets with random due times come out in due order, ties in send order (heap order)")
        void heapOrder() {
            LinkDirection d = new LinkDirection(new LinkProfile().fifo(false), 1, 1024, 8);
            java.util.SplittableRandom rnd = new java.util.SplittableRandom(42);
            long[] due = new long[1000];
            for (int i = 0; i < due.length; i++) {
                due[i] = rnd.nextInt(50); // many ties
                ByteBuffer b = ByteBuffer.allocate(2).putShort((short) i).flip();
                d.send(b, due[i], 0, null);
            }
            int[] got = new int[due.length];
            ByteBuffer dst = ByteBuffer.allocate(8);
            int n = 0;
            while (d.inFlight() > 0) {
                dst.clear();
                d.receive(dst, Long.MAX_VALUE);
                got[n++] = dst.getShort(0);
            }
            boolean sorted = true;
            for (int k = 1; k < n; k++) {
                int a = got[k - 1];
                int b = got[k];
                sorted &= due[a] < due[b] || due[a] == due[b] && a < b;
            }
            boolean ok = sorted;
            int count = n;
            assertAll(
                () -> assertEquals(1000, count, "every packet delivered"),
                () -> assertTrue(ok, "by due time, then send order"));
        }

        @Test
        @DisplayName("a receive buffer smaller than the datagram gets its first bytes (truncation)")
        void truncates() {
            LinkDirection d = new LinkDirection(new LinkProfile(), 1, 8, 64);
            d.send(packet(1, 2, 3, 4), 0, 0, null);
            ByteBuffer dst = ByteBuffer.allocate(2);
            int n = d.receive(dst, 0);
            assertAll(
                () -> assertEquals(2, n, "the length is what fit"),
                () -> assertEquals(1, dst.get(0), "first byte"),
                () -> assertEquals(2, dst.get(1), "second byte"));
        }

        @Test
        @DisplayName("the address reference is released on delivery (slot reuse keeps nothing alive)")
        void releasesAddress() {
            LinkDirection d = new LinkDirection(new LinkProfile(), 1, 1, 64);
            Object a = new Object();
            d.send(packet(1), 0, 0, a);
            take(d, 0);
            d.send(packet(2), 0, 0, null);
            take(d, 0);
            assertNull(d.lastAddress(), "the reused slot carries the new packet's (null) address");
        }
    }

    @Nested
    @DisplayName("delay, jitter and spikes")
    class Delay {
        @Test
        @DisplayName("base delay: delivered exactly delay later")
        void baseDelay() {
            long[] at = deliveries(new LinkProfile().delayMs(40), 3, new long[] {0, 5 * MS, 100 * MS});
            assertArrayEquals(new long[] {40 * MS, 45 * MS, 140 * MS}, at, "each packet arrives 40 ms after it was sent");
        }

        @Test
        @DisplayName("normal jitter: delay + sigma * the replayed Gaussian draw, per packet")
        void normalJitterExact() {
            LinkProfile p = new LinkProfile().delayMs(100).normalJitterMs(15).fifo(false);
            long[] times = {0, 10 * MS, 20 * MS};
            long[] at = deliveries(p, 77, times);
            LinkRandom r = new LinkRandom(77);
            long[] expect = new long[3];
            for (int i = 0; i < 3; i++) {
                Draws dr = Draws.next(r, 1, 1.5);
                expect[i] = times[i] + Math.max(0, Math.round(100 * MS + dr.gauss() * 15 * MS));
            }
            assertArrayEquals(expect, at, "delivery = send + delay + sigma * N(0,1) from the seeded stream");
        }

        @Test
        @DisplayName("normal jitter statistics: mean delay and standard deviation match the profile (20k packets)")
        void normalJitterStats() {
            int n = 20_000;
            long[] times = new long[n];
            for (int i = 0; i < n; i++) {
                times[i] = i * MS;
            }
            long[] at = deliveries(new LinkProfile().delayMs(100).normalJitterMs(15).fifo(false), 5, times);
            double sum = 0;
            double sq = 0;
            for (int i = 0; i < n; i++) {
                double d = (at[i] - times[i]) / (double) MS;
                sum += d;
                sq += d * d;
            }
            double mean = sum / n;
            double sd = Math.sqrt(sq / n - mean * mean);
            // tolerance: the standard error of the mean is 15/sqrt(20000) = 0.11 ms; of the sd about 0.075 ms
            assertAll(
                () -> assertEquals(100, mean, 0.5, "mean one-way delay is the base delay: " + mean),
                () -> assertEquals(15, sd, 0.5, "standard deviation is sigma: " + sd));
        }

        @Test
        @DisplayName("jitter larger than the delay never delivers before the send time")
        void clampsAtZero() {
            int n = 2000;
            long[] times = new long[n];
            for (int i = 0; i < n; i++) {
                times[i] = 1000 * MS + i * MS;
            }
            long[] at = deliveries(new LinkProfile().normalJitterMs(15).fifo(false), 9, times);
            int early = 0;
            int atSend = 0;
            for (int i = 0; i < n; i++) {
                if (at[i] < times[i]) {
                    early++;
                }
                if (at[i] == times[i]) {
                    atSend++;
                }
            }
            int e = early;
            int z = atSend;
            assertAll(
                () -> assertEquals(0, e, "no packet arrives before it was sent"),
                () -> assertTrue(z > n / 3, "about half the draws are negative and clamp to the send time: " + z));
        }

        @Test
        @DisplayName("FIFO (default): jitter never lets a packet overtake an earlier one; without FIFO it does")
        void fifo() {
            int n = 500;
            long[] times = new long[n];
            for (int i = 0; i < n; i++) {
                times[i] = i * MS;
            }
            long[] fifo = deliveries(new LinkProfile().delayMs(50).normalJitterMs(15), 4, times);
            long[] free = deliveries(new LinkProfile().delayMs(50).normalJitterMs(15).fifo(false), 4, times);
            int fifoInversions = 0;
            int freeInversions = 0;
            for (int i = 1; i < n; i++) {
                if (fifo[i] < fifo[i - 1]) {
                    fifoInversions++;
                }
                if (free[i] < free[i - 1]) {
                    freeInversions++;
                }
            }
            int a = fifoInversions;
            int b = freeInversions;
            assertAll(
                () -> assertEquals(0, a, "FIFO: deliveries are in send order"),
                () -> assertTrue(b > 50, "without FIFO, 15 ms jitter at 1 ms spacing reorders often: " + b));
        }

        @Test
        @DisplayName("spikes: with probability 1 every packet gets min(cap, Pareto) extra; uncapped without a cap")
        void spikes() {
            LinkProfile capped = new LinkProfile().delayMs(10).paretoSpikes(1, 20, 1.5, 60).fifo(false);
            int n = 2000;
            long[] times = new long[n];
            for (int i = 0; i < n; i++) {
                times[i] = i * MS;
            }
            long[] at = deliveries(capped, 11, times);
            LinkRandom r = new LinkRandom(11);
            boolean exact = true;
            long max = 0;
            long min = Long.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                Draws dr = Draws.next(r, 20 * MS, 1.5);
                long expect = times[i] + Math.round(10 * MS + Math.min(60 * MS, dr.pareto()));
                exact &= expect == at[i];
                max = Math.max(max, at[i] - times[i]);
                min = Math.min(min, at[i] - times[i]);
            }
            long[] uncapped = deliveries(new LinkProfile().paretoSpikes(1, 20, 1.5, 0).fifo(false), 11, times);
            long umax = 0;
            for (int i = 0; i < n; i++) {
                umax = Math.max(umax, uncapped[i] - times[i]);
            }
            LinkDirection counted = new LinkDirection(capped, 11, 8, 64);
            counted.send(packet(1), 0, 0, null);
            boolean ex = exact;
            long mx = max;
            long mn = min;
            long um = umax;
            assertAll(
                () -> assertTrue(ex, "delivery = send + delay + min(cap, Pareto draw), per packet"),
                () -> assertTrue(mn >= 30 * MS, "a spike is at least the Pareto scale (20 ms) on top of 10 ms: " + mn),
                () -> assertEquals(70 * MS, mx, "the cap bounds the spike: 10 + 60 ms"),
                () -> assertTrue(um > 70 * MS, "without a cap the heavy tail goes beyond 60 ms: " + um),
                () -> assertEquals(1, counted.spikes(), "a spiked packet is counted"));
        }

        @Test
        @DisplayName("an uncapped spike with a very heavy tail is clamped at 60 s, never overflowing")
        void hugeSpike() {
            LinkDirection d = new LinkDirection(new LinkProfile().paretoSpikes(1, 20, 0.05, 0).fifo(false), 3, 2048, 8);
            long send = 1_000 * MS;
            long max = 0;
            long min = Long.MAX_VALUE;
            for (int i = 0; i < 1000; i++) {
                d.send(packet(1), send, 0, null);
                ByteBuffer dst = ByteBuffer.allocate(8);
                while (d.inFlight() > 0) {
                    long t = d.nextDue();
                    max = Math.max(max, t - send);
                    min = Math.min(min, t - send);
                    d.receive(dst.clear(), t);
                }
            }
            long mx = max;
            long mn = min;
            assertAll(
                () -> assertTrue(mn >= 20 * MS, "every packet still gets at least the scale"),
                () -> assertEquals(LinkDirection.MAX_DELAY_NANOS, mx, "the tail is clamped at 60 s (shape 0.05 reaches it)"));
        }

        @Test
        @DisplayName("spike probability 0: no spike even though the Pareto value is drawn")
        void noSpikes() {
            LinkDirection d = new LinkDirection(new LinkProfile().delayMs(10).paretoSpikes(0, 20, 1.5, 0), 1, 8, 64);
            d.send(packet(1), 0, 0, null);
            assertAll(
                () -> assertEquals(10 * MS, d.nextDue(), "only the base delay"),
                () -> assertEquals(0, d.spikes(), "no spike counted"));
        }
    }

    @Nested
    @DisplayName("loss")
    class Loss {
        static double rate(LinkProfile p, int n, long seed, int[] longestBurst, double[] meanBurst) {
            LinkDirection d = new LinkDirection(p, seed, 8, 64);
            int lost = 0;
            int run = 0;
            int runs = 0;
            long runTotal = 0;
            int longest = 0;
            ByteBuffer dst = ByteBuffer.allocate(8);
            for (int i = 0; i < n; i++) {
                boolean ok = d.send(packet(1), i, 0, null);
                if (ok) {
                    dst.clear();
                    d.receive(dst, i);
                    if (run > 0) {
                        runs++;
                        runTotal += run;
                    }
                    run = 0;
                } else {
                    lost++;
                    run++;
                    longest = Math.max(longest, run);
                }
            }
            if (longestBurst != null) {
                longestBurst[0] = longest;
                meanBurst[0] = runs == 0 ? 0 : runTotal / (double) runs;
            }
            assertEquals(lost, d.lost(), "every loss is counted");
            return lost / (double) n;
        }

        @Test
        @DisplayName("uniform loss: p 0 loses nothing, p 1 everything, p 0.3 about 30% (50k packets)")
        void uniform() {
            double none = rate(new LinkProfile().uniformLoss(0), 1000, 1, null, null);
            double all = rate(new LinkProfile().uniformLoss(1), 1000, 1, null, null);
            double some = rate(new LinkProfile().uniformLoss(0.3), 50_000, 1, null, null);
            // tolerance: the standard error is sqrt(0.3*0.7/50000) = 0.002
            assertAll(
                () -> assertEquals(0, none, "p 0: no loss"),
                () -> assertEquals(1, all, "p 1: every packet lost"),
                () -> assertEquals(0.3, some, 0.01, "p 0.3: 30% lost: " + some));
        }

        @Test
        @DisplayName("bursty (Gilbert): 5% long-run loss in bursts of mean length 4 (200k packets)")
        void bursty() {
            int[] longest = new int[1];
            double[] mean = new double[1];
            double r = rate(new LinkProfile().burstyLoss(0.05, 4), 200_000, 2, longest, mean);
            double uniformMean;
            int[] ul = new int[1];
            double[] um = new double[1];
            rate(new LinkProfile().uniformLoss(0.05), 200_000, 2, ul, um);
            uniformMean = um[0];
            double uniformBurst = uniformMean;
            // tolerance: bursts are correlated, so the error is ~4x the binomial one (0.0005): 0.005 is ~2.5 sigma
            assertAll(
                () -> assertEquals(0.05, r, 0.005, "long-run loss rate is 5%: " + r),
                () -> assertEquals(4, mean[0], 0.3, "mean burst length is 1/badToGood = 4: " + mean[0]),
                () -> assertTrue(uniformBurst < 1.2, "uniform 5% loss has bursts of about 1.05: " + uniformBurst),
                () -> assertTrue(longest[0] >= 15, "bursty loss has long runs: " + longest[0]));
        }

        @Test
        @DisplayName("Gilbert-Elliott: loss in the good state uses lossGood, in the bad state lossBad; starts good")
        void states() {
            // goodToBad 1, badToGood 1: the state alternates good, bad, good, ... from packet 1 on
            LinkProfile p = new LinkProfile().gilbertElliott(1, 1, 0, 1);
            LinkDirection d = new LinkDirection(p, 3, 8, 64);
            List<Boolean> got = new ArrayList<>();
            ByteBuffer dst = ByteBuffer.allocate(8);
            for (int i = 0; i < 6; i++) {
                got.add(d.send(packet(1), i, 0, null));
                dst.clear();
                d.receive(dst, i);
            }
            assertEquals(List.of(true, false, true, false, true, false), got,
                "good (kept), bad (lost), good, bad: the first packet sees the good state");
        }

        @Test
        @DisplayName("a lost packet consumes the buffer and is not in flight")
        void lostConsumed() {
            LinkDirection d = new LinkDirection(new LinkProfile().uniformLoss(1), 1, 8, 64);
            ByteBuffer p = packet(1, 2);
            boolean ok = d.send(p, 0, 0, null);
            assertAll(
                () -> assertFalse(ok, "send reports the loss"),
                () -> assertEquals(2, p.position(), "the buffer is consumed"),
                () -> assertEquals(0, d.inFlight(), "nothing in flight"));
        }
    }

    @Nested
    @DisplayName("reordering and duplication")
    class ReorderDup {
        @Test
        @DisplayName("a reordered packet is held back by reorderDelay and later packets pass it, even with FIFO")
        void reorder() {
            LinkProfile p = new LinkProfile().delayMs(10).reorder(1, 50);
            LinkDirection d = new LinkDirection(p, 1, 8, 64);
            d.send(packet(1), 0, 0, null);
            long firstDue = d.nextDue();
            p.reorder(0, 50);
            d.send(packet(2), MS, 0, null);
            int first = take(d, 100 * MS);
            int second = take(d, 100 * MS);
            assertAll(
                () -> assertEquals(60 * MS, firstDue, "held back: delay + reorderDelay"),
                () -> assertEquals(2, first, "the later packet arrives first"),
                () -> assertEquals(1, second, "the held-back packet arrives after it"),
                () -> assertEquals(1, d.reordered(), "one reordered packet counted"));
        }

        @Test
        @DisplayName("a reordered packet does not hold back later FIFO packets")
        void reorderExemptFromFifo() {
            LinkProfile p = new LinkProfile().delayMs(10).reorder(1, 50);
            LinkDirection d = new LinkDirection(p, 1, 8, 64);
            d.send(packet(1), 0, 0, null);
            p.reorder(0, 0);
            d.send(packet(2), 0, 0, null);
            ByteBuffer dst = ByteBuffer.allocate(8);
            assertAll(
                () -> assertEquals(10 * MS, d.nextDue(), "the FIFO packet keeps its own 10 ms"),
                () -> assertEquals(1, d.receive(dst, 10 * MS), "it is delivered at 10 ms"),
                () -> assertEquals(2, dst.get(0), "and it is the second packet"));
        }

        @Test
        @DisplayName("duplication delivers two identical copies at the same time")
        void duplicate() {
            LinkDirection d = new LinkDirection(new LinkProfile().delayMs(5).duplicate(1), 1, 8, 64);
            d.send(packet(9, 8), 0, 3, null);
            int inFlight = d.inFlight();
            ByteBuffer a = ByteBuffer.allocate(8);
            ByteBuffer b = ByteBuffer.allocate(8);
            int na = d.receive(a, 5 * MS);
            int nb = d.receive(b, 5 * MS);
            assertAll(
                () -> assertEquals(2, inFlight, "two copies in flight"),
                () -> assertEquals(2, na, "first copy"),
                () -> assertEquals(2, nb, "second copy at the same time"),
                () -> assertEquals(a.flip(), b.flip(), "identical bytes"),
                () -> assertEquals(1, d.duplicated(), "one duplicated datagram counted"),
                () -> assertEquals(2, d.delivered(), "two deliveries counted"));
        }

        @Test
        @DisplayName("duplication probability 0: one copy")
        void noDuplicate() {
            LinkDirection d = new LinkDirection(new LinkProfile().duplicate(0), 1, 8, 64);
            d.send(packet(1), 0, 0, null);
            assertAll(
                () -> assertEquals(1, d.inFlight(), "one copy"),
                () -> assertEquals(0, d.duplicated(), "none counted"));
        }
    }

    @Nested
    @DisplayName("bandwidth cap")
    class Bandwidth {
        @Test
        @DisplayName("packets are serialized at the rate (plus overhead) before the delay; back to back they queue")
        void serializes() {
            LinkProfile p = new LinkProfile().delayMs(10).bandwidth(1000, 0);
            p.overheadBytes = 0;
            LinkDirection d = new LinkDirection(p, 1, 8, 256);
            d.send(sized(100, 1), 0, 0, null); // 100 ms on the wire
            d.send(sized(100, 2), 0, 0, null); // waits for the first
            ByteBuffer dst = ByteBuffer.allocate(256);
            long first = d.nextDue();
            d.receive(dst, first);
            long second = d.nextDue();
            d.receive(dst.clear(), second);
            d.send(sized(50, 3), 500 * MS, 0, null); // the link is idle again
            long third = d.nextDue();
            assertAll(
                () -> assertEquals(110 * MS, first, "100 bytes at 1000 B/s = 100 ms, plus 10 ms delay"),
                () -> assertEquals(210 * MS, second, "the second waits for the first: 200 ms + 10 ms"),
                () -> assertEquals(560 * MS, third, "an idle link starts at once: 500 + 50 + 10 ms"));
        }

        @Test
        @DisplayName("overhead bytes count at the bottleneck")
        void overhead() {
            LinkProfile p = new LinkProfile().bandwidth(1000, 0);
            p.overheadBytes = 28;
            LinkDirection d = new LinkDirection(p, 1, 8, 256);
            d.send(sized(72, 1), 0, 0, null);
            assertEquals(100 * MS, d.nextDue(), "72 + 28 bytes at 1000 B/s = 100 ms");
        }

        @Test
        @DisplayName("queue limit: a packet that would wait longer is dropped and takes no link time")
        void dropTail() {
            LinkProfile p = new LinkProfile().bandwidth(1000, 150);
            p.overheadBytes = 0;
            LinkDirection d = new LinkDirection(p, 1, 8, 256);
            boolean a = d.send(sized(100, 1), 0, 0, null); // leaves at 100
            boolean b = d.send(sized(100, 2), 0, 0, null); // waits 100 <= 150: leaves at 200
            boolean c = d.send(sized(100, 3), 0, 0, null); // would wait 200 > 150: dropped
            boolean e = d.send(sized(100, 4), 60 * MS, 0, null); // waits 140: leaves at 300
            boolean exact = d.send(sized(100, 5), 150 * MS, 0, null); // waits exactly 150: fits, leaves at 400
            assertAll(
                () -> assertTrue(a && b, "the first two fit in the queue"),
                () -> assertFalse(c, "the third would wait 200 ms > 150 ms: dropped"),
                () -> assertTrue(e, "a later one that waits 140 ms fits (the dropped one took no time)"),
                () -> assertTrue(exact, "a wait of exactly the limit fits"),
                () -> assertEquals(1, d.queueDrops(), "one queue drop counted"),
                () -> assertEquals(4, d.inFlight(), "four in flight"));
        }
    }

    @Nested
    @DisplayName("capacity")
    class Capacity {
        @Test
        @DisplayName("a datagram larger than a slot is dropped and counted")
        void oversize() {
            LinkDirection d = new LinkDirection(new LinkProfile(), 1, 8, 4);
            boolean ok = d.send(sized(5, 1), 0, 0, null);
            boolean fits = d.send(sized(4, 1), 0, 0, null);
            assertAll(
                () -> assertFalse(ok, "5 bytes do not fit 4-byte slots"),
                () -> assertTrue(fits, "4 bytes do"),
                () -> assertEquals(1, d.oversize(), "one oversize counted"));
        }

        @Test
        @DisplayName("with every slot in flight a packet is dropped; a duplicate without a slot too; freed slots are reused")
        void overflow() {
            LinkDirection d = new LinkDirection(new LinkProfile().delayMs(10), 1, 2, 8);
            boolean a = d.send(packet(1), 0, 0, null);
            boolean b = d.send(packet(2), 0, 0, null);
            boolean c = d.send(packet(3), 0, 0, null);
            long drops = d.overflowDrops();
            take(d, 10 * MS);
            d.profile().duplicate(1);
            boolean e = d.send(packet(4), 0, 0, null); // one slot free: the original fits, the copy does not
            assertAll(
                () -> assertTrue(a && b, "two slots hold two packets"),
                () -> assertFalse(c, "the third finds no slot"),
                () -> assertEquals(1, drops, "one overflow drop"),
                () -> assertTrue(e, "after a delivery the slot is reused"),
                () -> assertEquals(2, d.overflowDrops(), "the duplicate without a slot is an overflow drop too"),
                () -> assertEquals(2, d.inFlight(), "two in flight"));
        }
    }

    @Nested
    @DisplayName("reproducibility")
    class Reproducibility {
        long[] run(LinkProfile p, long seed) {
            int n = 300;
            long[] times = new long[n];
            for (int i = 0; i < n; i++) {
                times[i] = i * 7 * MS;
            }
            return deliveries(p, seed, times);
        }

        static LinkProfile impaired() {
            return new LinkProfile().delayMs(40).normalJitterMs(15).paretoSpikes(0.05, 20, 1.5, 300).fifo(false);
        }

        @Test
        @DisplayName("same seed, same deliveries; another seed, other deliveries")
        void seeded() {
            long[] a = run(impaired(), 5);
            long[] b = run(impaired(), 5);
            long[] c = run(impaired(), 6);
            assertAll(
                () -> assertArrayEquals(a, b, "the same seed reproduces every delivery time"),
                () -> assertFalse(java.util.Arrays.equals(a, c), "another seed gives other times"));
        }

        @Test
        @DisplayName("every send consumes the same draws: turning on duplication leaves the jitter unchanged")
        void fixedDraws() {
            long[] plain = run(impaired(), 8);
            long[] dup = run(impaired().duplicate(0.5), 8);
            long[] lossy = run(impaired().uniformLoss(0.2), 8);
            boolean sameWhereKept = true;
            for (int i = 0; i < plain.length; i++) {
                sameWhereKept &= lossy[i] < 0 || lossy[i] == plain[i];
            }
            boolean kept = sameWhereKept;
            assertAll(
                () -> assertArrayEquals(plain, dup, "first-copy delivery times are identical with and without duplication"),
                () -> assertTrue(kept, "with loss on, every packet that survives keeps the delivery time it had without loss"));
        }
    }
}
