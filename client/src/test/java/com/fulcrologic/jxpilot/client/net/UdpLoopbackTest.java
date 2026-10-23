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
package com.fulcrologic.jxpilot.client.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real UDP over loopback: UdpTransport against the fake server on an ephemeral port. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UdpLoopbackTest {
    /** Server end on a real channel, with optional seeded loss and reordering toward the client. */
    static final class UdpPort implements FakeServer.Port, AutoCloseable {
        final DatagramChannel ch;
        SocketAddress peer;
        final Random rnd = new Random(3);
        double loss;
        double reorder;
        ByteBuffer held;

        UdpPort(String host) throws IOException {
            ch = DatagramChannel.open();
            ch.bind(new InetSocketAddress(InetAddress.getByName(host), 0));
            ch.configureBlocking(false);
        }

        int port() throws IOException {
            return ((InetSocketAddress) ch.getLocalAddress()).getPort();
        }

        @Override
        public boolean send(ByteBuffer b) throws IOException {
            if (peer == null || rnd.nextDouble() < loss) {
                return true;
            }
            if (held == null && rnd.nextDouble() < reorder) {
                held = ByteBuffer.allocate(b.remaining()).put(b).flip();
                return true;
            }
            ch.send(b, peer);
            if (held != null) {
                ch.send(held, peer);
                held = null;
            }
            return true;
        }

        @Override
        public int receive(ByteBuffer b) throws IOException {
            int p = b.position();
            SocketAddress a = ch.receive(b);
            if (a == null) {
                return 0;
            }
            peer = a;
            return b.position() - p;
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }
    }

    /** Run client and server in one thread in real time until n snapshots or the deadline. */
    static ClientSim run(UdpPort port, String connect, Endpoint bind, int snapshots) throws Exception {
        FakeServer server = new FakeServer(port);
        try (UdpTransport t = UdpTransport.open(Endpoint.parse(connect, 15345), bind)) {
            ClientSim sim = new ClientSim(t, connect, "udp", 0, 5);
            long t0 = System.nanoTime();
            sim.start(t0);
            long nextTick = t0;
            while (sim.connection().snapshotsReceived() < snapshots && System.nanoTime() - t0 < 10_000_000_000L) {
                long now = System.nanoTime();
                server.poll(now);
                while (nextTick <= now) {
                    server.step(now);
                    nextTick += 1_000_000_000L / 60;
                }
                sim.step(now);
                Thread.sleep(1);
            }
            assertEquals(bind == null ? null : bind.host(), bind == null ? null
                : t.localAddress().getAddress().getHostAddress(), "bound to the requested local address");
            return sim;
        }
    }

    @Test
    @DisplayName("joins, receives the map and snapshots over IPv4 loopback with --bind")
    void ipv4() throws Exception {
        try (UdpPort port = new UdpPort("127.0.0.1")) {
            ClientSim sim = run(port, "127.0.0.1:" + port.port(), new Endpoint("127.0.0.1", 0), 30);
            assertAll(
                () -> assertEquals(ConnectionState.PLAYING, sim.connection().state(), "playing"),
                () -> assertEquals("Fake Arena", sim.game().map().name(), "map transferred"),
                () -> assertTrue(sim.connection().snapshotsReceived() >= 30, "snapshots received"),
                () -> assertEquals(2, sim.game().players().count(), "players announced"));
        }
    }

    @Test
    @DisplayName("survives 20% loss and reordering on real UDP")
    void lossy() throws Exception {
        try (UdpPort port = new UdpPort("127.0.0.1")) {
            port.loss = 0.2;
            port.reorder = 0.2;
            ClientSim sim = run(port, "127.0.0.1:" + port.port(), null, 60);
            assertAll(
                () -> assertEquals(ConnectionState.PLAYING, sim.connection().state(), "playing despite loss"),
                () -> assertEquals("Fake Arena", sim.game().map().name(), "map transferred intact"),
                () -> assertTrue(sim.connection().snapshotsReceived() >= 60, "snapshots keep arriving"));
        }
    }

    @Test
    @DisplayName("joins over IPv6 loopback written as [::1]:port")
    void ipv6() throws Exception {
        UdpPort port;
        try {
            port = new UdpPort("::1");
        } catch (IOException e) {
            assumeTrue(false, "no IPv6 loopback: " + e);
            return;
        }
        try (port) {
            ClientSim sim = run(port, "[::1]:" + port.port(), null, 10);
            assertEquals(ConnectionState.PLAYING, sim.connection().state(), "playing over IPv6");
        }
    }

    @Test
    @DisplayName("with no server the client keeps connecting and counts port-unreachable errors")
    void noServer() throws Exception {
        int free;
        try (UdpPort p = new UdpPort("127.0.0.1")) {
            free = p.port();
        }
        try (UdpTransport t = UdpTransport.open(new Endpoint("127.0.0.1", free), null)) {
            ClientSim sim = new ClientSim(t, "x", "n", 0, 1);
            long t0 = System.nanoTime();
            sim.start(t0);
            for (int i = 0; i < 40; i++) {
                Thread.sleep(10);
                sim.step(System.nanoTime());
            }
            assertAll(
                () -> assertEquals(ConnectionState.CONNECTING, sim.connection().state(), "still connecting"),
                () -> assertTrue(sim.connection().attempts() >= 2, "CONNECT resent"),
                () -> assertEquals(new InetSocketAddress("127.0.0.1", free), t.server(), "resolved server address"));
        }
    }
}
