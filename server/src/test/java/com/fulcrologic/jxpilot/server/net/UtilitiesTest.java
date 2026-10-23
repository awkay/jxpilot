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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Server net utilities")
class UtilitiesTest {
    static final long S = 1_000_000_000L;

    @Nested
    @DisplayName("RateLimiter")
    class Rate {
        @Test
        @DisplayName("token bucket per source: burst, refill, cap, refusals are free")
        void bucket() {
            RateLimiter r = new RateLimiter(16, 10, 5);
            boolean[] burst = new boolean[6];
            for (int i = 0; i < 6; i++) {
                burst[i] = r.allow(7, 1, 0);
            }
            boolean otherSource = r.allow(8, 1, 0);
            boolean afterTenthSecond = r.allow(7, 1, S / 10);
            boolean onlyOne = r.allow(7, 1, S / 10);
            boolean bigRefused = r.allow(9, 6, 0);
            boolean smallAfterBigRefused = r.allow(9, 5, 0);
            RateLimiter cap = new RateLimiter(16, 10, 5);
            cap.allow(1, 5, 0);
            boolean capped = cap.allow(1, 5, 100 * S);
            boolean overCap = cap.allow(1, 1, 100 * S);
            assertAll(
                () -> assertArrayEquals(new boolean[] {true, true, true, true, true, false}, burst,
                    "a new source gets a full burst, then is refused"),
                () -> assertTrue(otherSource, "sources have separate buckets"),
                () -> assertTrue(afterTenthSecond, "a tenth of a second refills one token at 10/s"),
                () -> assertFalse(onlyOne, "and only one"),
                () -> assertFalse(bigRefused, "a cost above the bucket is refused"),
                () -> assertTrue(smallAfterBigRefused, "a refused request costs nothing"),
                () -> assertTrue(capped, "a long idle refills to the burst"),
                () -> assertFalse(overCap, "but never beyond it"));
        }

        @Test
        @DisplayName("sources sharing a slot share its bucket (tokens carry over); table rounds to a power of two")
        void collisions() {
            RateLimiter r = new RateLimiter(2, 1, 2);
            long a = 0;
            long b = 2;
            boolean sameSlot = r.slot(a) == r.slot(b);
            boolean aFirst = r.allow(a, 1, 0);
            boolean bShares = r.allow(b, 1, 0);
            boolean bEmpty = r.allow(b, 1, 0);
            boolean aEmpty = r.allow(a, 1, 0);
            boolean otherSlot = r.allow(1, 1, 0);
            assertAll(
                () -> assertTrue(sameSlot, "keys 0 and 2 share a slot of a 2-slot table"),
                () -> assertTrue(aFirst && bShares, "the two-token bucket pays once for each"),
                () -> assertFalse(bEmpty, "a colliding source does not get a fresh bucket"),
                () -> assertFalse(aEmpty, "and the first source sees the shared bucket drained"),
                () -> assertTrue(otherSlot, "a key on another slot starts full"),
                () -> assertEquals(1024, new RateLimiter(1000, 1, 1).capacity(), "1000 slots round up to 1024"),
                () -> assertEquals(1024, new RateLimiter(1024, 1, 1).capacity(), "a power of two is kept"),
                () -> assertEquals(2, new RateLimiter(1, 1, 1).capacity(), "the minimum table is 2"));
        }

        @Test
        @DisplayName("slot is the key's low bits, always within the table")
        void slots() {
            RateLimiter r = new RateLimiter(1024, 1, 1);
            assertAll(
                () -> assertEquals(5, r.slot(0x7_0000_0405L), "the low 10 bits of the key pick the slot"),
                () -> assertEquals(1023, r.slot(-1L), "a negative key stays within the table"));
        }
    }

    @Nested
    @DisplayName("SourceKeys")
    class Keys {
        @Test
        @DisplayName("secret-keyed: IPv4 per address, IPv6 per /64, ports shared, families apart")
        void keys() throws Exception {
            SourceKeys k = new SourceKeys(42);
            SourceKeys other = new SourceKeys(43);
            java.net.InetSocketAddress v4a = Harness.addr("10.0.0.1", 1);
            java.net.InetSocketAddress u = java.net.InetSocketAddress.createUnresolved("h", 1);
            assertAll(
                () -> assertEquals(k.of(v4a), k.of(Harness.addr("10.0.0.1", 2)), "ports of one IPv4 address share a key"),
                () -> assertNotEquals(k.of(v4a), k.of(Harness.addr("10.0.0.2", 1)), "IPv4 addresses differ"),
                () -> assertEquals(k.of(Harness.addr("2001:db8:1:2::1", 1)), k.of(Harness.addr("2001:db8:1:2:ffff:ffff:ffff:ffff", 9)),
                    "every address of an IPv6 /64 shares a key"),
                () -> assertNotEquals(k.of(Harness.addr("2001:db8:1:2::1", 1)), k.of(Harness.addr("2001:db8:1:3::1", 1)),
                    "neighbouring /64s (differing only in bit 64) differ"),
                () -> assertEquals(k.of(Harness.addr("2001:db8:1:2::1", 1)), k.of(Harness.addr("2001:db8:1:2:8000::1", 1)),
                    "bit 65 already belongs to the host part"),
                () -> assertNotEquals(k.of(Harness.addr("10.0.0.1", 1)), k.of(Harness.addr("0:0:a00:1::1", 1)),
                    "an IPv6 address whose /64 value equals an IPv4 address keys differently"),
                () -> assertNotEquals(k.of(v4a), other.of(v4a), "another secret gives an unrelated key"),
                () -> assertEquals(k.of(u), k.of(java.net.InetSocketAddress.createUnresolved("h", 1)),
                    "an unresolved address keys by its hash"),
                () -> assertNotEquals(k.of(u), other.of(u), "unresolved keys depend on the secret too"),
                () -> assertEquals(0x5692161D100B05E5L, SourceKeys.mix(1), "mix is SplitMix64's finalizer"));
        }
    }

    @Nested
    @DisplayName("TickScheduler")
    class Ticks {
        @Test
        @DisplayName("counts due ticks, waits, and re-bases after a stall")
        void schedule() {
            TickScheduler t = new TickScheduler(60, 5, 1000);
            long p = t.periodNanos();
            int early = t.due(1000 + p - 1);
            long wait = t.nanosUntilNext(1000 + p - 1);
            int one = t.due(1000 + p);
            int two = t.due(1000 + 3 * p + 5);
            long waitAfter = t.nanosUntilNext(1000 + 3 * p + 5);
            int stall = t.due(1000 + 100 * p);
            long skipped = t.skipped();
            long waitAfterStall = t.nanosUntilNext(1000 + 100 * p);
            TickScheduler five = new TickScheduler(60, 5, 0);
            int exactlyFive = five.due(5 * p + 3);
            long phaseKept = five.nanosUntilNext(5 * p + 3);
            assertAll(
                () -> assertEquals(16_666_666L, p, "60 Hz period in ns"),
                () -> assertEquals(0, early, "no tick before the first deadline"),
                () -> assertEquals(1, wait, "waits until the deadline"),
                () -> assertEquals(1, one, "a tick is due exactly at its deadline"),
                () -> assertEquals(2, two, "two elapsed periods give two ticks"),
                () -> assertEquals(p - 5, waitAfter, "the schedule keeps its phase"),
                () -> assertEquals(5, stall, "a stall runs at most maxCatchUp ticks"),
                () -> assertEquals(97 - 5, skipped, "the rest are counted as skipped"),
                () -> assertEquals(p, waitAfterStall, "after a stall the next tick is one period away"),
                () -> assertEquals(5, exactlyFive, "exactly maxCatchUp due ticks all run"),
                () -> assertEquals(p - 3, phaseKept, "and keep the schedule's phase (no re-base)"),
                () -> assertEquals(0, t.nanosUntilNext(1000 + 200 * p), "a passed deadline needs no wait"));
        }
    }

    @Nested
    @DisplayName("ServerArgs")
    class Args {
        @Test
        @DisplayName("joinRepeated merges every --bind into one list")
        void join() {
            String[] in = {"--map", "m.xp", "--bind", "utun4", "-port", "1", "-BIND", "loopback", "cfg", "--bind"};
            assertAll(
                () -> assertArrayEquals(new String[] {"--map", "m.xp", "--bind", "utun4,loopback", "-port", "1", "cfg", "--bind"},
                    ServerArgs.joinRepeated(in, Opt.BIND),
                    "values merge at the first occurrence; a trailing flag without value stays"),
                () -> assertArrayEquals(new String[] {"--map", "m.xp"}, ServerArgs.joinRepeated(new String[] {"--map", "m.xp"}, Opt.BIND),
                    "without the option the arguments are unchanged"),
                () -> assertArrayEquals(new String[] {"-", "x", "+bind", "y"},
                    ServerArgs.joinRepeated(new String[] {"-", "x", "+bind", "y"}, Opt.BIND),
                    "a lone dash and a +flag are not the option"));
        }

        @Test
        @DisplayName("configFile finds the single positional argument")
        void config() {
            assertAll(
                () -> assertNull(ServerArgs.configFile(List.of()), "no positional argument, no config file"),
                () -> assertEquals(Path.of("server.cfg"), ServerArgs.configFile(List.of("server.cfg")), "one positional is the file"),
                () -> assertEquals(Path.of("-"), ServerArgs.configFile(List.of("-")), "a lone dash is a path"),
                () -> assertThrows(IllegalArgumentException.class, () -> ServerArgs.configFile(List.of("--nope")),
                    "an unconsumed option is unknown"),
                () -> assertThrows(IllegalArgumentException.class, () -> ServerArgs.configFile(List.of("+nope")),
                    "an unconsumed +option is unknown"),
                () -> assertThrows(IllegalArgumentException.class, () -> ServerArgs.configFile(List.of("a", "b")),
                    "two positionals are an error"));
        }
    }

    @Test
    @DisplayName("ServerNetConfig.of mirrors the map")
    void configOf() {
        Options plain = new Options();
        ConfigParser.parse("mapWidth: 3\nmapHeight: 2\nmapName: Tiny\nmapData: \\multiline: E\nxxx\nx_x\nE\n",
            "t", OptionOrigin.MAP, plain);
        Options team = new Options();
        ConfigParser.parse("mapWidth: 3\nmapHeight: 2\nteamPlay: yes\nedgeWrap: yes\nframesPerSecond: 50\n"
            + "mapData: \\multiline: E\nxxx\nx_x\nE\n", "t", OptionOrigin.MAP, team);
        Options race = new Options();
        ConfigParser.parse("mapWidth: 4\nmapHeight: 2\ntiming: yes\n"
            + "mapData: \\multiline: E\nxxxx\nxA_x\nE\n", "t", OptionOrigin.MAP, race);
        ServerNetConfig p = ServerNetConfig.of(plain, WorldMap.fromOptions(plain));
        ServerNetConfig f = ServerNetConfig.of(team, WorldMap.fromOptions(team));
        ServerNetConfig r = ServerNetConfig.of(race, WorldMap.fromOptions(race));
        assertAll(
            () -> assertEquals(0, p.welcomeFlags, "no flags for a plain map"),
            () -> assertEquals(Msg.Welcome.FLAG_TEAM_PLAY | Msg.Welcome.FLAG_EDGE_WRAP, f.welcomeFlags,
                "teamPlay and edgeWrap set their flags"),
            () -> assertEquals(Msg.Welcome.FLAG_TIMING, r.welcomeFlags, "timing (race mode) sets its flag"),
            () -> assertEquals(14f, p.gameFps, "gameFps defaults to 14"),
            () -> assertEquals(50f, f.gameFps, "gameFps follows framesPerSecond"),
            () -> assertEquals("JXPilot: Tiny", p.serverName, "the server name carries the map name"),
            () -> assertEquals(105.0, p.worldWidth, "world width in pixels"),
            () -> assertEquals(70.0, p.worldHeight, "world height in pixels"),
            () -> assertFalse(p.edgeWrap, "no wrap"),
            () -> assertTrue(f.edgeWrap, "wrap"));
    }
}
