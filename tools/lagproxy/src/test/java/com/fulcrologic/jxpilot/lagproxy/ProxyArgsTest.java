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
package com.fulcrologic.jxpilot.lagproxy;

import com.fulcrologic.jxpilot.common.net.link.LinkProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ProxyArgs")
class ProxyArgsTest {
    static final long MS = 1_000_000L;

    static ProxyArgs parse(String... extra) {
        String[] base = {"--listen", "15000", "--server", "example.org:15345"};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return ProxyArgs.parse(all);
    }

    static String error(String... args) {
        return assertThrows(IllegalArgumentException.class, () -> ProxyArgs.parse(args)).getMessage();
    }

    @Nested
    @DisplayName("defaults and addresses")
    class Defaults {
        @Test
        @DisplayName("a perfect link, seed 1, stats every 10 s, idle 60 s; a bare listen port means every address")
        void defaults() {
            ProxyArgs a = parse();
            LinkProfile clean = new LinkProfile();
            assertAll(
                () -> assertEquals(InetSocketAddress.createUnresolved("0.0.0.0", 15000), a.listen(), "listen on all"),
                () -> assertEquals(InetSocketAddress.createUnresolved("example.org", 15345), a.server(), "server"),
                () -> assertEquals(clean.delayNanos, a.up().delayNanos, "no up delay"),
                () -> assertEquals(clean.lossGood, a.down().lossGood, "no down loss"),
                () -> assertTrue(a.up().fifo && a.down().fifo, "FIFO both ways"),
                () -> assertEquals(1, a.seed(), "seed 1"),
                () -> assertEquals(10, a.statsSeconds(), "stats every 10 s"),
                () -> assertEquals(60, a.idleSeconds(), "idle 60 s"));
        }

        @Test
        @DisplayName("host:port, [v6]:port; listen also a bare port or port 0; bad ports and shapes are refused")
        void addresses() {
            assertAll(
                () -> assertEquals(InetSocketAddress.createUnresolved("10.0.0.5", 9), ProxyArgs.address("--x", "10.0.0.5:9",
                    false), "IPv4"),
                () -> assertEquals(InetSocketAddress.createUnresolved("::1", 15345),
                    ProxyArgs.address("--x", "[::1]:15345", false), "IPv6 in brackets"),
                () -> assertEquals(InetSocketAddress.createUnresolved("0.0.0.0", 7), ProxyArgs.address("--x", "7", true),
                    "bare port allowed for --listen"),
                () -> assertEquals(65535, ProxyArgs.address("--x", "h:65535", false).getPort(), "the largest port"),
                () -> assertEquals("--x needs a host and a port in 1..65535, got h:0",
                    assertThrows(IllegalArgumentException.class, () -> ProxyArgs.address("--x", "h:0", false)).getMessage(),
                    "a server port range starts at 1"),
                () -> assertEquals("--x needs a host and a port in 0..65535, got h:70000",
                    assertThrows(IllegalArgumentException.class, () -> ProxyArgs.address("--x", "h:70000", true))
                        .getMessage(), "a listen port range starts at 0"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.address("--x", "7", false),
                    "bare port not allowed for --server"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.address("--x", "h:0", false),
                    "port 0 is no server"),
                () -> assertEquals(InetSocketAddress.createUnresolved("h", 0), ProxyArgs.address("--x", "h:0", true),
                    "port 0 listens on any free port"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.address("--x", "-1", true),
                    "a negative listen port"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.address("--x", "h:65536", false),
                    "port too large"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.address("--x", "h:p", false),
                    "port not a number"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.address("--x", "[::1]15", false),
                    "brackets without :port"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.address("--x", ":15", false),
                    "empty host"));
        }

        @Test
        @DisplayName("seed, stats and idle are read")
        void misc() {
            ProxyArgs a = parse("--seed", "-42", "--stats", "0", "--idle", "5");
            assertAll(
                () -> assertEquals(-42, a.seed(), "seed"),
                () -> assertEquals(0, a.statsSeconds(), "stats off"),
                () -> assertEquals(5, a.idleSeconds(), "idle"));
        }
    }

    @Nested
    @DisplayName("impairments")
    class Impairments {
        @Test
        @DisplayName("unprefixed flags set both directions; --rtt halves; up-/down- set one; later flags win")
        void directions() {
            ProxyArgs a = parse("--rtt", "200", "--down-delay", "80", "--jitter", "15", "--up-jitter", "5",
                "--loss", "1%", "--down-loss", "0.05");
            assertAll(
                () -> assertEquals(100 * MS, a.up().delayNanos, "rtt 200: 100 ms up"),
                () -> assertEquals(80 * MS, a.down().delayNanos, "down delay overridden"),
                () -> assertEquals(5 * MS, a.up().jitterSigmaNanos, "up jitter overridden"),
                () -> assertEquals(15 * MS, a.down().jitterSigmaNanos, "down jitter from the shared flag"),
                () -> assertEquals(0.01, a.up().lossGood, 1e-12, "1% is 0.01"),
                () -> assertEquals(0.05, a.down().lossGood, 1e-12, "down loss overridden"));
        }

        @Test
        @DisplayName("--rtt alone puts half on each direction; spikes with three values keep the default cap; burst length 1")
        void edges() {
            ProxyArgs a = parse("--rtt", "150", "--spikes", "0.1,30,2", "--bursty", "0.5,1");
            assertAll(
                () -> assertEquals(75 * MS, a.up().delayNanos, "75 ms up"),
                () -> assertEquals(75 * MS, a.down().delayNanos, "75 ms down"),
                () -> assertEquals(2.0, a.down().spikeShape, "third value is the shape"),
                () -> assertEquals(0, a.down().spikeCapNanos, "no fourth value: no cap"),
                () -> assertEquals(1.0, a.up().badToGood, 1e-12, "bursts of exactly 1 packet are allowed"));
        }

        @Test
        @DisplayName("spikes, bursty, reorder, dup, rate and no-fifo")
        void others() {
            ProxyArgs a = parse("--spikes", "5%,20", "--up-spikes", "0.1,30,1.2,250", "--bursty", "5%,4",
                "--reorder", "0.01,40", "--dup", "2%", "--rate", "64000,200", "--up-rate", "1000", "--down-no-fifo");
            assertAll(
                () -> assertEquals(0.05, a.down().spikeProbability, 1e-12, "spike share"),
                () -> assertEquals(20 * MS, a.down().spikeScaleNanos, "spike scale"),
                () -> assertEquals(1.5, a.down().spikeShape, "default shape 1.5"),
                () -> assertEquals(0, a.down().spikeCapNanos, "default no cap"),
                () -> assertEquals(1.2, a.up().spikeShape, "explicit shape"),
                () -> assertEquals(250 * MS, a.up().spikeCapNanos, "explicit cap"),
                () -> assertEquals(0.25, a.up().badToGood, 1e-12, "bursts of 4"),
                () -> assertEquals(0.05, a.up().goodToBad / (a.up().goodToBad + a.up().badToGood), 1e-12, "5% share"),
                () -> assertEquals(0.01, a.down().reorderProbability, 1e-12, "reorder share"),
                () -> assertEquals(40 * MS, a.down().reorderDelayNanos, "reorder delay"),
                () -> assertEquals(0.02, a.up().duplicateProbability, 1e-12, "dup"),
                () -> assertEquals(64_000, a.down().bandwidthBytesPerSecond, "down rate"),
                () -> assertEquals(200 * MS, a.down().queueLimitNanos, "queue limit"),
                () -> assertEquals(1000, a.up().bandwidthBytesPerSecond, "up rate"),
                () -> assertEquals(0, a.up().queueLimitNanos, "no queue limit given"),
                () -> assertTrue(a.up().fifo, "up still FIFO"),
                () -> assertFalse(a.down().fifo, "down not FIFO"));
        }

        @Test
        @DisplayName("probabilities: fraction or percent within [0, 1]")
        void probabilities() {
            assertAll(
                () -> assertEquals(0.05, ProxyArgs.probability("--loss", "5%"), 1e-12, "percent"),
                () -> assertEquals(1, ProxyArgs.probability("--loss", "1"), "1 is allowed"),
                () -> assertEquals(0, ProxyArgs.probability("--loss", "0%"), "0% is allowed"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.probability("--loss", "101%"),
                    "above 100%"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.probability("--loss", "1.5"),
                    "above 1"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.number("--delay", "-3"), "negative"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.number("--delay", "NaN"), "NaN"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.number("--delay", "Infinity"),
                    "infinite"),
                () -> assertThrows(IllegalArgumentException.class, () -> ProxyArgs.number("--delay", "x"), "not a number"));
        }
    }

    @Test
    @DisplayName("errors name the problem")
    void errors() {
        assertAll(
            () -> assertEquals("missing --listen", error("--server", "h:1"), "no listen"),
            () -> assertEquals("missing --server", error("--listen", "1"), "no server"),
            () -> assertEquals("missing value for --delay", error("--listen", "1", "--server", "h:1", "--delay"),
                "no value"),
            () -> assertEquals("unknown flag --wobble", error("--listen", "1", "--server", "h:1", "--wobble", "3"),
                "unknown flag"),
            () -> assertEquals("unknown flag --up-wobble", error("--listen", "1", "--server", "h:1", "--up-wobble", "3"),
                "unknown prefixed flag"),
            () -> assertEquals("unexpected argument stray", error("stray"), "not a flag"),
            () -> assertEquals("--spikes takes 2 to 4 comma-separated values, got 0.1",
                error("--listen", "1", "--server", "h:1", "--spikes", "0.1"), "spikes needs 2-4 values"),
            () -> assertEquals("--spikes takes 2 to 4 comma-separated values, got 1,2,3,4,5",
                error("--listen", "1", "--server", "h:1", "--spikes", "1,2,3,4,5"), "not 5"),
            () -> assertEquals("--bursty takes 2 comma-separated values, got 0.1",
                error("--listen", "1", "--server", "h:1", "--bursty", "0.1"), "bursty takes exactly 2"),
            () -> assertEquals("--stats needs a non-negative integer, got 3000000000",
                error("--listen", "1", "--server", "h:1", "--stats", "3000000000"), "beyond an int"),
            () -> assertTrue(error("--listen", "1", "--server", "h:1", "--bursty", "1,4").contains("share below 1"),
                "bursty share 1 refused"),
            () -> assertTrue(error("--listen", "1", "--server", "h:1", "--bursty", "0.1,0.5").contains("burst length"),
                "burst below 1 refused"),
            () -> assertEquals("--stats needs a non-negative integer, got -1",
                error("--listen", "1", "--server", "h:1", "--stats", "-1"), "negative stats"),
            () -> assertEquals("--seed needs an integer, got x", error("--listen", "1", "--server", "h:1", "--seed", "x"),
                "bad seed"));
    }
}
