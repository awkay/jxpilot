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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.host.HostFx.FOUR;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gameDuration clock and the shutdown after it in the host's tick (C {@code Main_loop}, server.c:248-268,
 * and {@code End_game}).
 */
@DisplayName("SimGameHost gameDuration and shutdown")
class GameDurationHostTest {
    @Test
    @DisplayName("the clock starts on the first tick with a human; after game over the server stops 30 * FPS + 1 ticks later, humans or not")
    void shutdown() {
        Simulation s = sim("gameDuration: 0.1\n", FOUR);
        World w = s.world;
        long[] now = {1000};
        w.wallSeconds = () -> now[0];
        SimGameHost h = new SimGameHost(s);
        java.util.List<String> log = new java.util.ArrayList<>();
        h.log = line -> log.add(line.toString());
        h.tick(1);
        long noHuman = w.gameOverTime;
        int id = enter(h, "Ann", 0);
        h.tick(2);
        long started = w.gameOverTime;
        now[0] = 1006;
        h.tick(3);
        int countdown = w.shutdownServer;
        h.leave(id);
        long frames = w.frames;
        int tick = 3;
        for (int i = 0; i < 30 * 60; i++) {
            h.tick(++tick);
        }
        boolean beforeEnd = h.shutdownDue();
        int zero = w.shutdownServer;
        h.tick(++tick);
        boolean end = h.shutdownDue();
        int lastTick = w.tick;
        h.tick(++tick);
        assertAll(
            () -> assertEquals(0, noHuman, "no human yet: no end time"),
            () -> assertEquals(1006, started, "the first human starts 6 s"),
            () -> assertEquals(1, log.size(), "logged once"),
            () -> assertTrue(log.get(0).matches("\\d\\d [A-Z][a-z]{2} \\d\\d:\\d\\d:\\d\\d Server will stop in 0.1 minutes."),
                "C's line: " + log.get(0)),
            () -> assertEquals(30 * 60, countdown, "game over: 30 s of 60 fps frames"),
            () -> assertFalse(beforeEnd, "still counting after 1800 ticks"),
            () -> assertEquals(0, zero, "at 0"),
            () -> assertTrue(end, "the next tick ends the server (End_game)"),
            () -> assertEquals(frames, w.frames, "the countdown ran without the game running (nobody left)"),
            () -> assertEquals(lastTick, w.tick, "after that the host does nothing"),
            () -> assertTrue(h.shutdownDue(), "and stays due"));
    }

    @Test
    @DisplayName("rawMode with robots only: no human starts the clock, so C's end time 0 is already past and the game ends at once")
    void rawMode() {
        Options o = TestWorlds.options(HostFx.BASE + "gameDuration: 5\n", FOUR);
        o.force(Opt.IDLE_RUN, "true"); // command line only
        Simulation s = new Simulation(new World(o, 1));
        World w = s.world;
        w.wallSeconds = () -> 1000;
        SimGameHost h = new SimGameHost(s);
        h.tick(1);
        assertAll(
            () -> assertEquals(0, w.gameOverTime, "no end time without a human"),
            () -> assertTrue(w.noPlayersEnteredYet, "still waiting for one"),
            () -> assertEquals(30 * 60, w.shutdownServer, "game over on the first frame"));
    }

    @Test
    @DisplayName("formatG is C's %g: 6 significant digits, no trailing zeros, exponent form outside 1e-4..1e6")
    void formatG() {
        assertAll(
            () -> assertEquals("5", SimGameHost.formatG(5)),
            () -> assertEquals("0.1", SimGameHost.formatG(0.1f), "a float 0.1 prints as 0.1"),
            () -> assertEquals("1.5", SimGameHost.formatG(1.5)),
            () -> assertEquals("123457", SimGameHost.formatG(123456.7)),
            () -> assertEquals("1.23457e+06", SimGameHost.formatG(1234567)),
            () -> assertEquals("0.0001", SimGameHost.formatG(0.0001)),
            () -> assertEquals("1e-05", SimGameHost.formatG(0.00001)),
            () -> assertEquals("100", SimGameHost.formatG(100)));
    }
}
