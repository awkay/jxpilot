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

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.link.LinkProfile;
import com.fulcrologic.jxpilot.server.net.NetIf;
import com.fulcrologic.jxpilot.server.net.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The proxy on real loopback sockets, in real time: an echo server, then a real JXPilot server. */
class LagProxyLoopbackTest {
    static final InetAddress LO = InetAddress.getLoopbackAddress();

    /** A UDP echo server on loopback that records the source of every datagram. */
    static final class Echo implements AutoCloseable {
        final DatagramChannel ch = DatagramChannel.open();
        final Set<SocketAddress> sources = java.util.Collections.synchronizedSet(new HashSet<>());
        final Thread t;

        Echo() throws IOException {
            ch.bind(new InetSocketAddress(LO, 0));
            t = new Thread(() -> {
                ByteBuffer b = ByteBuffer.allocate(2048);
                try {
                    while (true) {
                        b.clear();
                        SocketAddress from = ch.receive(b);
                        sources.add(from);
                        b.flip();
                        ch.send(b, from);
                    }
                } catch (IOException e) {
                    // closed
                }
            });
            t.setDaemon(true);
            t.start();
        }

        int port() throws IOException {
            return ((InetSocketAddress) ch.getLocalAddress()).getPort();
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }
    }

    /** A proxy running on its own thread. */
    static final class Running implements AutoCloseable {
        final LagProxy proxy;
        final Thread t;
        final ByteArrayOutputStream log = new ByteArrayOutputStream();

        Running(int serverPort, String... impairments) throws IOException {
            List<String> a = new ArrayList<>(List.of("--listen", "127.0.0.1:0", "--server", "127.0.0.1:" + serverPort,
                "--stats", "0"));
            a.addAll(List.of(impairments));
            proxy = new LagProxy(ProxyArgs.parse(a.toArray(String[]::new)), new PrintStream(log, true));
            t = new Thread(() -> {
                try {
                    proxy.run();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            t.setDaemon(true);
            t.start();
        }

        InetSocketAddress address() throws IOException {
            return new InetSocketAddress(LO, proxy.localAddress().getPort());
        }

        @Override
        public void close() throws InterruptedException {
            proxy.close();
            t.join(5000);
        }
    }

    static DatagramChannel client() throws IOException {
        DatagramChannel c = DatagramChannel.open();
        c.bind(new InetSocketAddress(LO, 0));
        c.configureBlocking(false);
        return c;
    }

    /** Wait up to {@code ms} for a datagram on {@code c}; its length, or -1. */
    static int await(DatagramChannel c, ByteBuffer into, long ms) throws Exception {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (System.nanoTime() < end) {
            into.clear();
            if (c.receive(into) != null) {
                return into.position();
            }
            Thread.sleep(1);
        }
        return -1;
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("an echo through --rtt 100 takes about 100 ms (never less); full loss returns nothing")
    void delayAndLoss() throws Exception {
        try (Echo echo = new Echo(); Running p = new Running(echo.port(), "--rtt", "100");
             DatagramChannel c = client()) {
            ByteBuffer out = ByteBuffer.allocate(8);
            ByteBuffer in = ByteBuffer.allocate(64);
            long[] rtt = new long[10];
            for (int i = 0; i < rtt.length; i++) {
                long t0 = System.nanoTime();
                out.clear().putLong(t0).flip();
                c.send(out, p.address());
                int n = await(c, in, 2000);
                rtt[i] = n == 8 && in.getLong(0) == t0 ? System.nanoTime() - t0 : -1;
            }
            long min = java.util.Arrays.stream(rtt).min().getAsLong();
            long[] sorted = rtt.clone();
            java.util.Arrays.sort(sorted);
            long median = sorted[sorted.length / 2];
            int sessions = p.proxy.sessions();
            assertAll(
                () -> assertTrue(min >= 100_000_000L, "never faster than the emulated RTT: " + min / 1e6 + " ms"),
                // tolerance: 1 ms select granularity each way plus scheduling on a busy machine
                () -> assertTrue(median < 140_000_000L, "about 100 ms: " + median / 1e6 + " ms"),
                () -> assertEquals(1, sessions, "one client, one upstream socket"));
        }
        try (Echo echo = new Echo(); Running p = new Running(echo.port(), "--loss", "1");
             DatagramChannel c = client()) {
            c.send(ByteBuffer.wrap(new byte[] {1}), p.address());
            int n = await(c, ByteBuffer.allocate(8), 300);
            assertAll(
                () -> assertEquals(-1, n, "a fully lossy link returns nothing"),
                () -> assertEquals(1, p.proxy.link().up().lost(), "the uplink lost it"));
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("each client gets its own upstream socket: the server sees distinct sources, replies go back to each")
    void perClientUpstream() throws Exception {
        try (Echo echo = new Echo(); Running p = new Running(echo.port()); DatagramChannel a = client();
             DatagramChannel b = client()) {
            a.send(ByteBuffer.wrap(new byte[] {'a'}), p.address());
            b.send(ByteBuffer.wrap(new byte[] {'b'}), p.address());
            ByteBuffer ia = ByteBuffer.allocate(8);
            ByteBuffer ib = ByteBuffer.allocate(8);
            int na = await(a, ia, 2000);
            int nb = await(b, ib, 2000);
            assertAll(
                () -> assertEquals(1, na, "a got a reply"),
                () -> assertEquals('a', ia.get(0), "its own"),
                () -> assertEquals(1, nb, "b got a reply"),
                () -> assertEquals('b', ib.get(0), "its own"),
                () -> assertEquals(2, echo.sources.size(), "the server saw two source ports"),
                () -> assertEquals(2, p.proxy.sessions(), "two sessions"));
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("three clients CONNECT to a real JXPilot server through the proxy with -maxClientsPerIP 0: all ACCEPTed")
    void realServer() throws Exception {
        int port;
        try (DatagramChannel probe = DatagramChannel.open()) {
            probe.bind(new InetSocketAddress(LO, 0));
            port = ((InetSocketAddress) probe.getLocalAddress()).getPort();
        }
        Server.Settings s = new Server.Settings();
        s.interfaces = () -> List.of(new NetIf("lo0", true, true, List.of(LO)));
        s.resolver = h -> List.of();
        s.log = m -> { };
        Server server = Server.start(new String[] {"--map", "../../maps/doggy.xp", "--port", Integer.toString(port),
            "--bind", LO.getHostAddress(), "-maxClientsPerIP", "0", "-maxRobots", "0", "-minRobots", "0"}, s);
        try (Running p = new Running(port, "--rtt", "40"); DatagramChannel c1 = client(); DatagramChannel c2 = client();
             DatagramChannel c3 = client()) {
            List<Integer> replies = new ArrayList<>();
            int nonce = 100;
            for (DatagramChannel c : List.of(c1, c2, c3)) {
                ByteBuffer tx = ByteBuffer.allocate(Protocol.MAX_PACKET);
                PacketHeader.write(tx, PacketType.CONNECT, 0, 0);
                Msg.Connect m = new Msg.Connect();
                m.name.append("Pilot").append(nonce);
                m.nonce = nonce++;
                m.write(tx);
                c.send(tx.flip(), p.address());
                ByteBuffer rx = ByteBuffer.allocate(Protocol.MAX_PACKET);
                int n = await(c, rx, 5000);
                replies.add(n < 4 ? -1 : rx.get(3) & 0xFF);
            }
            assertEquals(List.of(PacketType.ACCEPT, PacketType.ACCEPT, PacketType.ACCEPT), replies,
                "every client is accepted although all come from the proxy's address");
        } finally {
            server.close();
        }
    }

    @Test
    @DisplayName("describe and counters read the profile and the direction")
    void text() {
        LinkProfile prof = new LinkProfile().delayMs(50).normalJitterMs(15).uniformLoss(0.01).fifo(false);
        String d = LagProxy.describe("up", prof);
        com.fulcrologic.jxpilot.common.net.link.LinkDirection dir =
            new com.fulcrologic.jxpilot.common.net.link.LinkDirection(new LinkProfile(), 1, 4, 16);
        dir.send(ByteBuffer.allocate(2), 0, 0, null);
        assertAll(
            () -> assertTrue(d.startsWith("up: delay 50.0 ms, jitter 15.0 ms"), d),
            () -> assertTrue(d.contains("loss good 0.0100"), d),
            () -> assertTrue(d.endsWith(", no FIFO"), d),
            () -> assertEquals("sent 1 delivered 0 lost 0 queue 0 dup 0 reordered 0 spikes 0 in flight 1",
                LagProxy.counters(dir), "counters"));
    }
}
