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

import com.fulcrologic.jxpilot.common.net.ControlKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C {@code noQuit} (server.c:278-291): the server ends when the last human has left, unless -noQuit. */
class NoQuitTest {
    static final long MIN = 60_000_000_000L;

    @Test
    @DisplayName("quitReason: keep running with noQuit or any connection; quit once a human was in; no-show after 5 min")
    void quitReason() {
        assertAll(
            () -> assertNull(Server.quitReason(true, 0, true, 10 * MIN), "noQuit"),
            () -> assertNull(Server.quitReason(false, 1, true, 10 * MIN), "a client is connected or logging in"),
            () -> assertTrue(Server.quitReason(false, 0, true, 0).startsWith("No human players left"), "last human left"),
            () -> assertNull(Server.quitReason(false, 0, false, 5 * MIN), "nobody came yet: wait 5 minutes"),
            () -> assertEquals("First player has yet to show his butt, I'm bored... Bye!",
                Server.quitReason(false, 0, false, 5 * MIN + 1), "C's no-show message after 5 minutes"));
    }

    /** Join a real game on loopback, play until snapshots flow, leave; return the server log. */
    private static Server playAndLeave(InetAddress lo, int port, List<String> log, String... extra) throws Exception {
        Server.Settings s = LoopbackIntegrationTest.settings(new LoopbackIntegrationTest.MutableInterfaces(lo), log);
        s.factory = new com.fulcrologic.jxpilot.server.host.SimGameHostFactory(() -> 3, line -> { });
        List<String> args = new ArrayList<>(List.of("--map", LoopbackIntegrationTest.MAP, "--port",
            Integer.toString(port), "--bind", "127.0.0.1", "-robots", "1"));
        args.addAll(List.of(extra));
        Server server = Server.start(args.toArray(String[]::new), s);
        try (LoopbackIntegrationTest.UdpClient u = new LoopbackIntegrationTest.UdpClient(41,
            new InetSocketAddress(lo, port))) {
            u.connect("Leaver");
            long end = System.nanoTime() + 10_000_000_000L;
            while (u.c.snapshotTicks.size() < 20 && System.nanoTime() < end) {
                if (u.c.hasMap() && !u.c.readySent) {
                    u.c.ready();
                }
                for (ByteBuffer b : u.c.frame(System.nanoTime(), ControlKey.THRUST.mask())) {
                    u.send(b);
                }
                Thread.sleep(16);
                u.pump();
            }
            assertTrue(u.c.snapshotTicks.size() >= 20, "the client played");
            assertFalse(server.termination().isDone(), "the server runs while the human plays");
            u.send(u.c.disconnect());
        }
        return server;
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a real server stops by itself when its only human leaves")
    void quits() throws Exception {
        InetAddress lo = InetAddress.ofLiteral("127.0.0.1");
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Server server = playAndLeave(lo, LoopbackIntegrationTest.freePort(lo), log);
        server.termination().get(5, TimeUnit.SECONDS);
        server.close();
        assertTrue(log.stream().anyMatch(l -> l.startsWith("No human players left")), "logged why: " + log);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("with -noQuit the server keeps running after the last human left")
    void noQuit() throws Exception {
        InetAddress lo = InetAddress.ofLiteral("127.0.0.1");
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Server server = playAndLeave(lo, LoopbackIntegrationTest.freePort(lo), log, "-noQuit");
        try {
            assertThrows(TimeoutException.class, () -> server.termination().get(2, TimeUnit.SECONDS), "still running");
        } finally {
            server.close();
        }
    }
}
