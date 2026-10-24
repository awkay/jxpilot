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

import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.server.host.SimGameHostFactory;
import com.fulcrologic.jxpilot.server.net.NetIf;
import com.fulcrologic.jxpilot.server.net.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * server-commands end to end: talk commands, team change, custom ship shapes and alliance marks through the real
 * Java server (ServerNet + SimGameHost) on loopback UDP and the real client stack, as a C player would use them
 * (the talk line "/team N", the shipShape resource).
 */
@DisplayName("Java server end to end: commands, team change, shapes, alliances")
class CommandsEndToEndTest {
    static final long SEC = 1_000_000_000L;
    static final String WIDE = "(SH: 15,0 -9,15 -9,-15)(EN: -9,0)(MG: 15,0)";

    @TempDir
    Path dir;

    final List<E2eClient> clients = new ArrayList<>();

    static final class Running implements AutoCloseable {
        final Server server;
        final int port;
        final List<String> log = Collections.synchronizedList(new ArrayList<>());

        Running(String map, String... options) throws IOException {
            InetAddress lo = InetAddress.ofLiteral("127.0.0.1");
            port = JavaServerEndToEndTest.freePort(lo);
            Server.Settings s = new Server.Settings();
            s.interfaces = () -> List.of(new NetIf("lo0", true, true, List.of(lo)));
            s.resolver = h -> List.of();
            s.log = log::add;
            s.factory = new SimGameHostFactory(() -> 4242);
            List<String> args = new ArrayList<>(List.of("--map", map, "--port", Integer.toString(port), "--bind",
                "127.0.0.1", "--defaults", "none", "-maxRobots", "0",
                // every test client comes from 127.0.0.1 (C's default allows 2 per address); clients come and go
                "-maxClientsPerIP", "8", "-noQuit"));
            args.addAll(Arrays.asList(options));
            server = Server.start(args.toArray(String[]::new), s);
        }

        @Override
        public void close() throws InterruptedException {
            server.close();
        }
    }

    private String map(String extra, String row) throws IOException {
        Path p = dir.resolve("m.xp");
        Files.writeString(p, "mapWidth: 11\nmapHeight: 4\nframesPerSecond: 30\ngravity: 0\n" + extra
            + "mapData: \\multiline: EOF\nxxxxxxxxxxx\nx         x\n" + row + "\nxxxxxxxxxxx\nEOF\n", StandardCharsets.ISO_8859_1);
        return p.toString();
    }

    private E2eClient client(Running srv, String name, int team, String shape, int seed) throws IOException {
        E2eClient c = new E2eClient("127.0.0.1", srv.port, name, team, shape, seed);
        clients.add(c);
        c.start(System.nanoTime());
        return c;
    }

    /** Step every client at ~120 Hz until {@code done} or the deadline. */
    private boolean until(BooleanSupplier done, long nanos) throws InterruptedException {
        long end = System.nanoTime() + nanos;
        long next = System.nanoTime();
        while (System.nanoTime() < end) {
            long now = JavaServerEndToEndTest.pace(next);
            next += 8_000_000L;
            for (E2eClient c : clients) {
                c.step(now, 0);
            }
            if (done.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    private static boolean inGame(E2eClient c) {
        return c.playing() && c.game.world().hasSelf();
    }

    private static boolean said(E2eClient c, String text) {
        return c.texts.stream().anyMatch(t -> t.text().equals(text));
    }

    private void closeAll() {
        for (E2eClient c : clients) {
            c.close();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("\"/team 2\" swaps the player (both clients see it, with the new base); a custom shape reaches the other"
        + " client; an operator's /lock refuses a third client")
    void teamShapeLock() throws Exception {
        try (Running srv = new Running(map("teamPlay: yes\nreserveRobotTeam: no\n", "x 1 1 2 2 x"), "-password", "pw")) {
            E2eClient a = client(srv, "Ann", 1, WIDE, 11);
            E2eClient b = client(srv, "Bob", 2, null, 12);
            assertTrue(until(() -> inGame(a) && inGame(b) && a.game.players().count() == 2
                && b.game.players().count() == 2, 15 * SEC), "both play and see each other");
            int annId = a.selfId();
            a.chat("/team 2", System.nanoTime());
            boolean swapped = until(() -> {
                Players pa = a.game.players();
                Players pb = b.game.players();
                return pa.team(pa.slotOf(annId)) == 2 && pb.team(pb.slotOf(annId)) == 2;
            }, 10 * SEC);
            Players pb = b.game.players();
            ShipShape annShape = pb.shape(pb.slotOf(annId));
            a.chat("/password pw", System.nanoTime());
            a.chat("/lock 1", System.nanoTime());
            assertTrue(until(() -> said(b, " < The game has been locked by Ann! >"), 10 * SEC), "the lock is announced");
            E2eClient c = client(srv, "Cid", 1, null, 13);
            boolean refused = until(() -> c.game.status().toString().contains("game locked")
                || c.conn.state() == ConnectionState.CLOSED, 10 * SEC);
            String cStatus = c.game.status().toString();
            assertAll(
                () -> assertTrue(swapped, "Ann is on team 2 for herself and for Bob"),
                () -> assertTrue(said(b, "Ann has swapped to team 2."), "Bob got the notice"),
                () -> assertTrue(said(a, "You got operator status. [*Server reply*]"), "Ann became an operator"),
                () -> assertTrue(a.baseOwners.stream().anyMatch(o -> o.player() == annId && o.base() >= 2),
                    "Ann's new home is a team 2 base (index 2 or 3): " + a.baseOwners),
                () -> assertEquals(15, annShape.ptY[1], "Bob draws Ann's wide ship"),
                () -> assertEquals(ShipShape.parse(WIDE).toWireString(), annShape.toWireString(), "exactly her shape"),
                () -> assertTrue(refused && cStatus.contains("game locked"), "Cid is refused: " + cStatus));
        } finally {
            closeAll();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("/ally invite and accept: both clients mark each other '+'; a third client sees no mark")
    void alliance() throws Exception {
        try (Running srv = new Running(map("", "x _ _ _ _ x"))) {
            E2eClient a = client(srv, "Ann", 255, null, 21);
            E2eClient b = client(srv, "Bob", 255, null, 22);
            E2eClient c = client(srv, "Cid", 255, null, 23);
            assertTrue(until(() -> inGame(a) && inGame(b) && inGame(c) && a.game.players().count() == 3
                && b.game.players().count() == 3 && c.game.players().count() == 3, 15 * SEC), "all three play");
            int ann = a.selfId();
            int bob = b.selfId();
            a.chat("/ally invite Bob", System.nanoTime());
            assertTrue(until(() -> said(b, " < Ann seeks an alliance with you >"), 10 * SEC), "Bob is invited");
            b.chat("/ally accept Ann", System.nanoTime());
            boolean marked = until(() -> {
                Players pa = a.game.players();
                Players pb = b.game.players();
                return pa.alliance(pa.slotOf(bob)) == '+' && pa.alliance(pa.slotOf(ann)) == '+'
                    && pb.alliance(pb.slotOf(ann)) == '+';
            }, 10 * SEC);
            Players pc = c.game.players();
            assertAll(
                () -> assertTrue(marked, "Ann and Bob see the alliance"),
                () -> assertTrue(said(a, " < You have formed an alliance with Bob >"), "Ann's message"),
                () -> assertEquals(' ', pc.alliance(pc.slotOf(ann)), "Cid sees no mark (not announced)"));
        } finally {
            closeAll();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("login queue: a third player on a two-base map waits with its position, then plays when a base frees up")
    void queue() throws Exception {
        try (Running srv = new Running(map("", "x _   _   x"))) {
            E2eClient a = client(srv, "Ann", 255, null, 31);
            E2eClient b = client(srv, "Bob", 255, null, 32);
            assertTrue(until(() -> inGame(a) && inGame(b), 15 * SEC), "two players");
            E2eClient c = client(srv, "Cid", 255, null, 33);
            boolean waiting = until(() -> c.game.status().toString().contains("Waiting in queue"), 10 * SEC);
            String status = c.game.status().toString();
            a.chat("/queue", System.nanoTime());
            boolean listed = until(() -> said(a, "Queue: 1. Cid [*Server reply*]"), 10 * SEC);
            b.close();
            clients.remove(b);
            boolean admitted = until(() -> inGame(c), 20 * SEC);
            assertAll(
                () -> assertTrue(waiting, "Cid waits"),
                () -> assertTrue(status.endsWith("(position 1)"), "at position 1: " + status),
                () -> assertTrue(listed, "/queue lists Cid"),
                () -> assertTrue(admitted, "Cid plays after Bob left"));
        } finally {
            closeAll();
        }
    }
}
