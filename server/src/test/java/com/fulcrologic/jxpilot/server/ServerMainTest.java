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

import com.fulcrologic.jxpilot.server.net.DyingHost;
import com.fulcrologic.jxpilot.server.net.NetIf;
import com.fulcrologic.jxpilot.server.net.Server;
import com.fulcrologic.jxpilot.server.net.StubGameHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ServerMain#run}: exit statuses. Real loopback UDP, ephemeral port, bounded by a timeout. */
@DisplayName("ServerMain exit status")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ServerMainTest {
    static final String MAP = Path.of("..", "maps", "doggy.xp").toString();

    static Server.Settings settings() {
        Server.Settings s = new Server.Settings();
        InetAddress lo = InetAddress.ofLiteral("127.0.0.1");
        s.interfaces = () -> List.of(new NetIf("lo0", true, true, List.of(lo)));
        s.resolver = h -> List.of();
        s.log = line -> { };
        s.factory = (o, m) -> new StubGameHost(m, 4);
        return s;
    }

    @Test
    @DisplayName("1 for bad arguments, 2 when the server loop dies")
    void statuses() {
        ByteArrayOutputStream usage = new ByteArrayOutputStream();
        // --map is optional now (the bundled default map, as C's MAPDIR/default.xp); a missing defaults file is bad
        int bad = ServerMain.run(new String[] {"--defaults", "/nonexistent/jxpilot-defaults", "--port", "0"}, settings(),
            new PrintStream(usage, true, StandardCharsets.UTF_8));
        ByteArrayOutputStream usage2 = new ByteArrayOutputStream();
        int badMap = ServerMain.run(new String[] {"--map", "no-such-map", "--port", "0"}, settings(),
            new PrintStream(usage2, true, StandardCharsets.UTF_8));
        ByteArrayOutputStream died = new ByteArrayOutputStream();
        Server.Settings s = settings();
        s.factory = (o, m) -> new DyingHost(new StubGameHost(m, 4), 3, new IllegalStateException("boom"));
        int dead = ServerMain.run(new String[] {"--map", MAP, "--port", "0", "--bind", "127.0.0.1"}, s,
            new PrintStream(died, true, StandardCharsets.UTF_8));
        String deathText = died.toString(StandardCharsets.UTF_8);
        assertAll(
            () -> assertEquals(ServerMain.EXIT_USAGE, bad, "a missing defaults file exits with 1"),
            () -> assertEquals(ServerMain.EXIT_USAGE, badMap, "an unknown map exits with 1"),
            () -> assertTrue(usage2.toString(StandardCharsets.UTF_8).contains("usage: ServerMain"), "with the usage"),
            () -> assertTrue(usage.toString(StandardCharsets.UTF_8).contains("usage: ServerMain"), "and prints the usage"),
            () -> assertEquals(ServerMain.EXIT_LOOP_FAILED, dead, "a dead loop exits with 2 instead of hanging"),
            () -> assertTrue(deathText.contains("server loop failed: java.lang.IllegalStateException: boom"),
                "and says why: " + deathText));
    }
}
