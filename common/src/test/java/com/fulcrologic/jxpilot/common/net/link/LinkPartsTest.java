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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("link emulator parts")
class LinkPartsTest {
    static final long MS = 1_000_000L;

    @Nested
    @DisplayName("LinkRandom")
    class Random {
        @Test
        @DisplayName("SplitMix64: the reference outputs for seed 0; same seed, same sequence")
        void splitMix() {
            LinkRandom r = new LinkRandom(0);
            long a = r.nextLong();
            long b = r.nextLong();
            LinkRandom again = new LinkRandom(0);
            assertAll(
                // reference values of SplitMix64 from seed 0 (Vigna's splitmix64.c)
                () -> assertEquals(0xE220A8397B1DCDAFL, a, "first output"),
                () -> assertEquals(0x6E789E6AA1B965F4L, b, "second output"),
                () -> assertEquals(a, again.nextLong(), "the same seed repeats the sequence"));
        }

        @Test
        @DisplayName("nextDouble is in [0, 1) from the top 53 bits; chance(p) is never for 0, always for 1")
        void uniform() {
            LinkRandom r = new LinkRandom(3);
            LinkRandom same = new LinkRandom(3);
            double d = r.nextDouble();
            double expect = (same.nextLong() >>> 11) * 0x1.0p-53;
            double min = 1;
            double max = 0;
            int hits = 0;
            boolean never = false;
            boolean always = true;
            for (int i = 0; i < 100_000; i++) {
                double x = r.nextDouble();
                min = Math.min(min, x);
                max = Math.max(max, x);
                never |= r.chance(0);
                always &= r.chance(1);
                if (r.chance(0.25)) {
                    hits++;
                }
            }
            double mn = min;
            double mx = max;
            boolean nv = never;
            boolean al = always;
            double rate = hits / 100_000.0;
            assertAll(
                () -> assertEquals(expect, d, "nextDouble = top 53 bits of nextLong / 2^53"),
                () -> assertTrue(mn >= 0 && mn < 0.001, "reaches near 0, never below: " + mn),
                () -> assertTrue(mx < 1 && mx > 0.999, "reaches near 1, never 1: " + mx),
                () -> assertFalse(nv, "chance(0) is never true"),
                () -> assertTrue(al, "chance(1) is always true"),
                // tolerance: binomial standard error sqrt(0.25*0.75/1e5) = 0.0014
                () -> assertEquals(0.25, rate, 0.006, "chance(0.25) is true a quarter of the time: " + rate));
        }

        @Test
        @DisplayName("each draw kind consumes exactly one nextLong draw (chance, open uniform, Pareto, Gaussian)")
        void drawCounts() {
            LinkRandom a = new LinkRandom(9);
            LinkRandom b = new LinkRandom(9);
            a.chance(0.5);
            b.nextLong();
            long afterChance = a.nextLong() ^ b.nextLong();
            a.nextPareto(1, 2);
            b.nextLong();
            long afterPareto = a.nextLong() ^ b.nextLong();
            a.nextGaussian();
            b.nextLong();
            long afterGauss = a.nextLong() ^ b.nextLong();
            double open = a.nextOpen();
            double expectOpen = ((b.nextLong() >>> 11) + 0.5) * 0x1.0p-53;
            assertAll(
                () -> assertEquals(0, afterChance, "chance takes one draw"),
                () -> assertEquals(0, afterPareto, "nextPareto takes one draw"),
                () -> assertEquals(0, afterGauss, "nextGaussian takes one draw"),
                () -> assertEquals(expectOpen, open, "nextOpen is the 53-bit cell midpoint, inside (0, 1)"));
        }

        @Test
        @DisplayName("inverseNormal matches the normal quantiles in the centre and both tails, odd about 0.5")
        void quantiles() {
            // reference quantiles (R qnorm); tolerance 1e-8: Acklam's relative error is below 1.2e-9
            assertAll(
                () -> assertEquals(0, LinkRandom.inverseNormal(0.5), 1e-12, "median 0"),
                () -> assertEquals(1.959963984540054, LinkRandom.inverseNormal(0.975), 1e-8, "central: 97.5%"),
                () -> assertEquals(-0.6744897501960817, LinkRandom.inverseNormal(0.25), 1e-8, "central: 25%"),
                () -> assertEquals(-2.326347874040841, LinkRandom.inverseNormal(0.01), 1e-8, "lower tail: 1%"),
                () -> assertEquals(3.090232306167814, LinkRandom.inverseNormal(0.999), 1e-8, "upper tail: 99.9%"),
                () -> assertEquals(-1.9773684281819468, LinkRandom.inverseNormal(0.024), 1e-8, "just inside the lower tail"),
                () -> assertEquals(1.9773684281819466, LinkRandom.inverseNormal(0.976), 1e-8, "just inside the upper tail"),
                () -> assertEquals(-LinkRandom.inverseNormal(0.3), LinkRandom.inverseNormal(0.7), 1e-12, "odd about 0.5"));
        }

        @Test
        @DisplayName("Gaussian: mean 0, sd 1; Pareto: never below the scale, mean xm*a/(a-1) (200k draws)")
        void distributions() {
            LinkRandom r = new LinkRandom(17);
            int n = 200_000;
            double sum = 0;
            double sq = 0;
            double psum = 0;
            double pmin = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double g = r.nextGaussian();
                sum += g;
                sq += g * g;
                double p = r.nextPareto(2, 3);
                psum += p;
                pmin = Math.min(pmin, p);
            }
            double mean = sum / n;
            double sd = Math.sqrt(sq / n - mean * mean);
            double pmean = psum / n;
            double pm = pmin;
            // tolerances: standard error of the mean 1/sqrt(n) = 0.0022; Pareto(2, 3) has sd 1.73, se 0.004
            assertAll(
                () -> assertEquals(0, mean, 0.01, "Gaussian mean 0: " + mean),
                () -> assertEquals(1, sd, 0.01, "Gaussian sd 1: " + sd),
                () -> assertTrue(pm >= 2, "Pareto never below its scale: " + pm),
                () -> assertEquals(3, pmean, 0.03, "Pareto(2, 3) mean is 2*3/2 = 3: " + pmean));
        }
    }

    @Nested
    @DisplayName("LinkProfile")
    class Profile {
        @Test
        @DisplayName("a fresh profile is a perfect FIFO link with the IPv4+UDP overhead")
        void defaults() {
            LinkProfile p = new LinkProfile();
            assertAll(
                () -> assertEquals(0, p.delayNanos, "no delay"),
                () -> assertEquals(0, p.jitterSigmaNanos, "no jitter"),
                () -> assertEquals(0, p.spikeProbability, "no spikes"),
                () -> assertEquals(0, p.lossGood, "no loss when good"),
                () -> assertEquals(0, p.goodToBad, "never goes bad"),
                () -> assertEquals(0, p.reorderProbability, "no reordering"),
                () -> assertEquals(0, p.duplicateProbability, "no duplication"),
                () -> assertEquals(0, p.bandwidthBytesPerSecond, "no cap"),
                () -> assertEquals(28, p.overheadBytes, "28 bytes of IPv4 + UDP header at a cap"),
                () -> assertTrue(p.fifo, "FIFO"));
        }

        @Test
        @DisplayName("fluent setters convert milliseconds to nanoseconds")
        void setters() {
            LinkProfile p = new LinkProfile().delayMs(150.5).normalJitterMs(15).paretoSpikes(0.05, 20, 1.2, 300)
                .reorder(0.01, 30).duplicate(0.02).bandwidth(64_000, 200).fifo(false);
            assertAll(
                () -> assertEquals(150_500_000L, p.delayNanos, "delay"),
                () -> assertEquals(15 * MS, p.jitterSigmaNanos, "sigma"),
                () -> assertEquals(0.05, p.spikeProbability, "spike probability"),
                () -> assertEquals(20 * MS, p.spikeScaleNanos, "spike scale"),
                () -> assertEquals(1.2, p.spikeShape, "spike shape"),
                () -> assertEquals(300 * MS, p.spikeCapNanos, "spike cap"),
                () -> assertEquals(0.01, p.reorderProbability, "reorder probability"),
                () -> assertEquals(30 * MS, p.reorderDelayNanos, "reorder delay"),
                () -> assertEquals(0.02, p.duplicateProbability, "duplication"),
                () -> assertEquals(64_000, p.bandwidthBytesPerSecond, "rate"),
                () -> assertEquals(200 * MS, p.queueLimitNanos, "queue limit"),
                () -> assertFalse(p.fifo, "FIFO off"));
        }

        @Test
        @DisplayName("burstyLoss(avg, burst): badToGood = 1/burst, goodToBad so the bad share is avg; uniformLoss resets it")
        void lossModels() {
            LinkProfile p = new LinkProfile().burstyLoss(0.05, 4);
            double share = p.goodToBad / (p.goodToBad + p.badToGood);
            LinkProfile g = new LinkProfile().gilbertElliott(0.1, 0.2, 0.3, 0.4);
            LinkProfile u = new LinkProfile().burstyLoss(0.05, 4).uniformLoss(0.01);
            assertAll(
                () -> assertEquals(0.25, p.badToGood, 1e-12, "mean burst 4 packets"),
                () -> assertEquals(0.05, share, 1e-12, "stationary bad share is the average loss"),
                () -> assertEquals(1, p.lossBad, "every bad-state packet lost"),
                () -> assertEquals(0, p.lossGood, "no good-state loss"),
                () -> assertEquals(0.1, g.goodToBad, "gilbertElliott goodToBad"),
                () -> assertEquals(0.2, g.badToGood, "gilbertElliott badToGood"),
                () -> assertEquals(0.3, g.lossGood, "gilbertElliott lossGood"),
                () -> assertEquals(0.4, g.lossBad, "gilbertElliott lossBad"),
                () -> assertEquals(0, u.goodToBad, "uniform: never bad"),
                () -> assertEquals(1, u.badToGood, "uniform: badToGood 1"),
                () -> assertEquals(0.01, u.lossGood, "uniform: lossGood is p"),
                () -> assertEquals(0, u.lossBad, "uniform: lossBad 0"));
        }

        @Test
        @DisplayName("copyFrom copies every setting")
        void copy() {
            LinkProfile src = new LinkProfile().delayMs(1).normalJitterMs(2).paretoSpikes(0.3, 4, 5, 6)
                .gilbertElliott(0.7, 0.8, 0.9, 0.95).reorder(0.11, 12).duplicate(0.13).bandwidth(14, 15).fifo(false);
            src.overheadBytes = 16;
            LinkProfile c = new LinkProfile().copyFrom(src);
            assertAll(
                () -> assertEquals(src.delayNanos, c.delayNanos, "delay"),
                () -> assertEquals(src.jitterSigmaNanos, c.jitterSigmaNanos, "sigma"),
                () -> assertEquals(src.spikeProbability, c.spikeProbability, "spike p"),
                () -> assertEquals(src.spikeScaleNanos, c.spikeScaleNanos, "spike scale"),
                () -> assertEquals(src.spikeShape, c.spikeShape, "spike shape"),
                () -> assertEquals(src.spikeCapNanos, c.spikeCapNanos, "spike cap"),
                () -> assertEquals(src.goodToBad, c.goodToBad, "goodToBad"),
                () -> assertEquals(src.badToGood, c.badToGood, "badToGood"),
                () -> assertEquals(src.lossGood, c.lossGood, "lossGood"),
                () -> assertEquals(src.lossBad, c.lossBad, "lossBad"),
                () -> assertEquals(src.reorderProbability, c.reorderProbability, "reorder p"),
                () -> assertEquals(src.reorderDelayNanos, c.reorderDelayNanos, "reorder delay"),
                () -> assertEquals(src.duplicateProbability, c.duplicateProbability, "dup"),
                () -> assertEquals(src.bandwidthBytesPerSecond, c.bandwidthBytesPerSecond, "rate"),
                () -> assertEquals(16, c.overheadBytes, "overhead"),
                () -> assertEquals(src.queueLimitNanos, c.queueLimitNanos, "queue limit"),
                () -> assertFalse(c.fifo, "fifo"));
        }
    }

    @Nested
    @DisplayName("LinkEmulator and VirtualClock")
    class Emulator {
        @Test
        @DisplayName("two directions with their own profiles and distinct seeded streams; nextDue is the earlier")
        void directions() {
            VirtualClock clock = new VirtualClock(5);
            LinkProfile up = new LinkProfile().delayMs(30);
            LinkProfile down = new LinkProfile().delayMs(20);
            LinkEmulator e = new LinkEmulator(clock, up, down, 99);
            long empty = e.nextDue();
            e.up().send(ByteBuffer.allocate(1), 0, 0, null);
            long upOnly = e.nextDue();
            e.down().send(ByteBuffer.allocate(1), 0, 0, null);
            long both = e.nextDue();
            LinkRandom r = new LinkRandom(99);
            long s1 = r.nextLong();
            long s2 = r.nextLong();
            assertAll(
                () -> assertEquals(Long.MAX_VALUE, empty, "nothing in flight"),
                () -> assertEquals(30 * MS, upOnly, "the uplink's delivery"),
                () -> assertEquals(20 * MS, both, "the earlier of the two"),
                () -> assertTrue(e.up().profile() == up && e.down().profile() == down, "each direction reads its profile"),
                () -> assertEquals(5, e.now(), "now() reads the clock"),
                () -> assertEquals(s1, LinkEmulator.upSeed(99), "uplink seed: first SplitMix64 output of the seed"),
                () -> assertEquals(s2, LinkEmulator.downSeed(99), "downlink seed: the second"),
                () -> assertNotEquals(LinkEmulator.upSeed(99), LinkEmulator.downSeed(99), "the streams differ"));
        }

        @Test
        @DisplayName("the clock moves forward only")
        void clock() {
            VirtualClock c = new VirtualClock(10);
            c.advanceTo(10);
            long same = c.getAsLong();
            c.advanceTo(25);
            long later = c.getAsLong();
            assertAll(
                () -> assertEquals(10, same, "advancing to now is allowed"),
                () -> assertEquals(25, later, "advances"),
                () -> assertThrows(IllegalArgumentException.class, () -> c.advanceTo(24), "going back throws"));
        }
    }
}
