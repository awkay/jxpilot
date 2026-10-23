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
package com.fulcrologic.jxpilot.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ServerSim")
class ServerSimTest {
    @Nested
    @DisplayName("Args.parse")
    class Parse {
        @Test
        @DisplayName("reads every flag and defaults the rest")
        void flags() {
            ServerSim.Args all = ServerSim.Args.parse(new String[] {"--map", "m.xp", "--ticks", "9", "--seed", "3",
                "--players", "2", "--every", "4"});
            ServerSim.Args dflt = ServerSim.Args.parse(new String[] {"--map", "m.xp"});
            assertAll(
                () -> assertEquals(new ServerSim.Args("m.xp", 9, 3, 2, 4), all, "all flags are read"),
                () -> assertEquals(new ServerSim.Args("m.xp", 600, 1, 4, 0), dflt, "defaults: 600 ticks, seed 1, 4 players"),
                () -> assertThrows(IllegalArgumentException.class, () -> ServerSim.Args.parse(new String[] {"--ticks", "3"}),
                    "a missing --map is an error"),
                () -> assertThrows(IllegalArgumentException.class,
                    () -> ServerSim.Args.parse(new String[] {"--map", "m", "--bogus", "1"}), "unknown flags are errors"),
                () -> assertThrows(IllegalArgumentException.class, () -> ServerSim.Args.parse(new String[] {"--map"}),
                    "a flag without a value is an error (not an index error)"));
        }
    }

    @Nested
    @DisplayName("run")
    class Run {
        String out(ServerSim.Args a, long[] digest) {
            return out(TestWorlds.options("", Arena.rows()), a, digest);
        }

        String out(com.fulcrologic.jxpilot.common.config.Options o, ServerSim.Args a, long[] digest) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            digest[0] = ServerSim.run(o, a, ps);
            return bytes.toString(StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("prints periodic digests, per-player lines and the final digest; same args give the same output")
        void output() {
            ServerSim.Args a = new ServerSim.Args("arena", 40, 5, 3, 20);
            long[] d1 = new long[1];
            long[] d2 = new long[1];
            String o1 = out(a, d1);
            String o2 = out(a, d2);
            String[] lines = o1.split("\n");
            assertAll(
                () -> assertEquals(o1, o2, "the run is deterministic"),
                () -> assertEquals(d1[0], d2[0], "the returned digest is deterministic"),
                () -> assertTrue(lines[0].startsWith("tick 20 digest "), "a digest every 20 ticks: " + lines[0]),
                () -> assertTrue(lines[1].startsWith("tick 40 digest "), "and again at 40: " + lines[1]),
                () -> assertTrue(lines[2].contains("players 3 ticks 40 seed 5"), "summary line: " + lines[2]),
                () -> assertTrue(lines[3].startsWith("  pilot0 id 1 "), "one line per player"),
                () -> assertTrue(lines[5].startsWith("  pilot2 id 3 "), "three players"),
                () -> assertEquals(String.format("digest %016x", d1[0]), lines[lines.length - 1].trim(),
                    "the last line is the final digest"),
                () -> assertEquals(8, lines.length, "2 digest lines, summary, 3 players, counters, digest"));
        }

        @Test
        @DisplayName("the result is the digest of a world driven by RandomPilot(seed + 1), not of the initial state")
        void sameAsManualRun() {
            long[] d = new long[1];
            out(new ServerSim.Args("arena", 30, 5, 2, 0), d);
            com.fulcrologic.jxpilot.server.sim.Simulation s = new com.fulcrologic.jxpilot.server.sim.Simulation(
                new com.fulcrologic.jxpilot.server.world.World(TestWorlds.options("", Arena.rows()), 5));
            s.ships.join("pilot0", 0);
            s.ships.join("pilot1", 1);
            long initial = com.fulcrologic.jxpilot.server.sim.Digest.of(s.world);
            com.fulcrologic.jxpilot.server.sim.RandomPilot p =
                new com.fulcrologic.jxpilot.server.sim.RandomPilot(6, s.world.players.length);
            for (int i = 0; i < 30; i++) {
                s.tick(p.next());
            }
            long manual = com.fulcrologic.jxpilot.server.sim.Digest.of(s.world);
            assertAll(
                () -> assertEquals(manual, d[0], "run equals 30 ticks with RandomPilot(seed + 1)"),
                () -> org.junit.jupiter.api.Assertions.assertNotEquals(initial, d[0], "the world advanced"));
        }

        @Test
        @DisplayName("players alternate teams 0, 1 in team play")
        void teams() {
            long[] d = new long[1];
            String o = out(TestWorlds.options("teamPlay: yes\nreserveRobotTeam: no", "xxxxxxxxxx", "x 0 1 0 1x", "x        x", "xxxxxxxxxx"),
                new ServerSim.Args("t", 1, 1, 4, 0), d);
            assertTrue(o.contains("players 4 "), "four players joined (two per team): " + o);
        }
    }
}
