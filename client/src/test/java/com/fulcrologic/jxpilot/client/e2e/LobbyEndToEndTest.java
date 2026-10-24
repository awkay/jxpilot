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
package com.fulcrologic.jxpilot.client.e2e;

import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.net.LobbyClient;
import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.map.BundledMaps;
import com.fulcrologic.jxpilot.server.host.SimGameHostFactory;
import com.fulcrologic.jxpilot.server.lobby.LobbyServer;
import com.fulcrologic.jxpilot.server.net.NetIf;
import com.fulcrologic.jxpilot.server.net.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A real lobby server (real games, real UDP on loopback, one port) and real clients: pick a map, start it on demand,
 * join it, watch the list change live, and see the game stop when its last human leaves while the lobby stays up.
 */
@DisplayName("Lobby end to end")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class LobbyEndToEndTest {
    static final long SEC = 1_000_000_000L;
    static final long FRAME = SEC / 60;

    /** The lobby on loopback, bundled maps only. */
    static final class Lab implements AutoCloseable {
        final LobbyServer lobby;
        final int port;
        final List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<E2eClient> clients = new ArrayList<>();
        long next = System.nanoTime();

        Lab() throws IOException {
            InetAddress lo = InetAddress.ofLiteral("127.0.0.1");
            try (DatagramChannel ch = DatagramChannel.open()) {
                ch.bind(new InetSocketAddress(lo, 0));
                port = ((InetSocketAddress) ch.getLocalAddress()).getPort();
            }
            Server.Settings s = new Server.Settings();
            s.interfaces = () -> List.of(new NetIf("lo0", true, true, List.of(lo)));
            s.resolver = h -> List.of();
            s.log = log::add;
            s.rescanMillis = 100;
            s.factory = new SimGameHostFactory(() -> 4242);
            lobby = LobbyServer.start(new String[] {"--lobby", "--defaults", "none", "--port", Integer.toString(port),
                "--bind", "127.0.0.1", "--lobbyName", "e2e lobby", "--maxRobots", "0"}, s);
        }

        E2eClient client(String name, int seed) throws IOException {
            E2eClient c = new E2eClient(port, name, seed);
            clients.add(c);
            c.start(System.nanoTime());
            return c;
        }

        /** Step every client at 60 Hz until the condition holds. */
        boolean until(BooleanSupplier cond, int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + seconds * SEC;
            while (System.nanoTime() < deadline) {
                long now = JavaServerEndToEndTest.pace(next);
                next = Math.max(next + FRAME, now - FRAME);
                for (E2eClient c : clients) {
                    if (c.conn.state() != ConnectionState.CLOSED) {
                        c.step(now, 0);
                    }
                }
                if (cond.getAsBoolean()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() throws InterruptedException {
            for (E2eClient c : clients) {
                c.close();
            }
            lobby.close();
        }
    }

    static boolean inLobbyWithList(E2eClient c) {
        LobbyClient l = c.conn.lobby();
        return l != null && l.greeted() && !l.entries().isEmpty();
    }

    static LobbyEntry entryOf(E2eClient c, String name) {
        LobbyClient l = c.conn.lobby();
        if (l == null) {
            return null;
        }
        return l.entries().stream().filter(e -> e.name().equals(name)).findFirst().orElse(null);
    }

    static void pick(E2eClient c, String name) {
        LobbyEntry e = entryOf(c, name);
        assertNotNull(e, "the lobby lists " + name);
        assertTrue(c.conn.lobby().select(e.id(), e.name(), System.nanoTime()), "select accepted");
    }

    @Test
    @DisplayName("two clients pick a map: it starts on demand, the second joins the running game, the list updates live, "
        + "and the game stops when the last human leaves while the lobby keeps running")
    void lobbyLifecycle() throws Exception {
        try (Lab lab = new Lab()) {
            // --- alice connects to the lobby port and sees the overlay's list: every bundled map, none active
            E2eClient alice = lab.client("alice", 1);
            assertTrue(lab.until(() -> inLobbyWithList(alice), 10), "alice reached the lobby list; log " + lab.log);
            LobbyClient list = alice.conn.lobby();
            List<String> bundled = BundledMaps.standard().names().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            assertAll(
                () -> assertEquals(ConnectionState.LOBBY, alice.conn.state(), "alice is in the lobby state"),
                () -> assertEquals("e2e lobby", list.serverName(), "the lobby's name"),
                () -> assertEquals(bundled, list.entries().stream().map(LobbyEntry::name).toList(),
                    "all bundled maps, alphabetical, since none is active"),
                () -> assertTrue(list.entries().stream().noneMatch(LobbyEntry::active), "no map is running"),
                () -> assertEquals(0, lab.lobby.registry().runningCount(), "no game runs"));

            // --- alice picks doggy: the lobby starts it, she is routed to it and plays
            pick(alice, "doggy");
            assertTrue(lab.until(alice::playing, 30), "alice is playing on doggy; state " + alice.conn.state()
                + " status " + alice.conn.status() + " log " + lab.log);
            assertEquals(1, lab.lobby.registry().runningCount(), "one game runs");

            // --- bob arrives while alice plays: the list shows doggy first, active, with one human
            E2eClient bob = lab.client("bob", 2);
            assertTrue(lab.until(() -> inLobbyWithList(bob) && entryOf(bob, "doggy").active()
                && entryOf(bob, "doggy").humans() == 1, 15), "bob sees doggy active with 1 human; list "
                + (bob.conn.lobby() == null ? "none" : bob.conn.lobby().entries().get(0)));
            LobbyEntry first = bob.conn.lobby().entries().get(0);
            assertAll(
                () -> assertEquals("doggy", first.name(), "active maps are listed first"),
                () -> assertTrue(first.active(), "doggy is active"),
                () -> assertEquals(1, first.humans(), "with alice"),
                () -> assertTrue(bob.conn.lobby().entries().stream().skip(1).noneMatch(LobbyEntry::active),
                    "everything else idle"));

            // --- carol watches the lobby the whole time (live updates)
            E2eClient carol = lab.client("carol", 3);
            assertTrue(lab.until(() -> inLobbyWithList(carol), 10), "carol is in the lobby");

            // --- bob joins the running game (no second game starts) and sees alice there
            pick(bob, "doggy");
            assertTrue(lab.until(bob::playing, 20), "bob is playing; log " + lab.log);
            assertTrue(lab.until(() -> alice.joins.stream().anyMatch(j -> j.name().equals("bob")), 10),
                "alice sees bob join");
            assertAll(
                () -> assertEquals(1, lab.lobby.registry().runningCount(), "still one game"),
                () -> assertTrue(bob.joins.stream().anyMatch(j -> j.name().equals("alice")), "bob sees alice in the game"));
            assertTrue(lab.until(() -> entryOf(carol, "doggy").humans() == 2, 10),
                "carol's list updated live to 2 humans");

            // --- bob leaves: doggy stays up for alice, carol sees 1 human again
            bob.conn.disconnect(System.nanoTime());
            assertTrue(lab.until(() -> entryOf(carol, "doggy").humans() == 1, 15),
                "carol's list went back to 1 human; entry " + entryOf(carol, "doggy"));
            assertTrue(entryOf(carol, "doggy").active(), "doggy is still active while alice plays");
            assertEquals(1, lab.lobby.registry().runningCount(), "the game keeps running");

            // --- alice leaves: nobody is left, the map stops, the lobby stays up
            alice.conn.disconnect(System.nanoTime());
            assertTrue(lab.until(() -> !entryOf(carol, "doggy").active(), 20),
                "carol sees doggy go idle; entry " + entryOf(carol, "doggy") + " log " + lab.log);
            assertAll(
                () -> assertEquals(0, lab.lobby.registry().runningCount(), "no game runs"),
                () -> assertFalse(lab.lobby.termination().isDone(), "the lobby itself is still running"),
                () -> assertEquals(0, entryOf(carol, "doggy").humans(), "no humans listed"),
                () -> assertTrue(lab.log.stream().anyMatch(l -> l.contains("doggy ended")), "the lobby logged it"));

            // --- carol picks the map that just stopped: a fresh game starts and she plays
            pick(carol, "doggy");
            assertTrue(lab.until(carol::playing, 30), "carol plays on a restarted doggy; log " + lab.log);
            assertEquals(1, lab.lobby.registry().runningCount(), "a new game runs");
        }
    }

    @Test
    @DisplayName("an unknown map in the lobby is refused with a message and the player can choose again")
    void unknownMap() throws Exception {
        try (Lab lab = new Lab()) {
            E2eClient c = lab.client("dave", 4);
            assertTrue(lab.until(() -> inLobbyWithList(c), 10), "in the lobby");
            assertTrue(c.conn.lobby().select(30000, "ghost", System.nanoTime()), "asked");
            assertTrue(lab.until(() -> c.conn.lobby().selecting() < 0, 10), "answered");
            assertAll(
                () -> assertEquals(ConnectionState.LOBBY, c.conn.state(), "still in the lobby"),
                () -> assertEquals("The server has no map ghost.", c.conn.lobby().message(), "message"),
                () -> assertTrue(c.conn.lobby().select(entryOf(c, "default").id(), "default", System.nanoTime()),
                    "another choice is possible"));
            if (!lab.until(c::playing, 30)) {
                fail("dave plays on default; log " + lab.log);
            }
        }
    }
}
