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
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real UDP over loopback only, ephemeral ports, bounded by timeouts. Excluded from PIT.
 */
@DisplayName("Server over real loopback UDP")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LoopbackIntegrationTest {
    static final String MAP = Path.of("..", "maps", "doggy.xp").toString();
    static final InetAddress V4 = InetAddress.ofLiteral("127.0.0.1");
    static final InetAddress V6 = InetAddress.ofLiteral("::1");

    /** An interface source tests can change while the server runs. */
    static final class MutableInterfaces implements InterfaceSource {
        volatile List<NetIf> current;

        MutableInterfaces(InetAddress... lo) {
            set(lo);
        }

        void set(InetAddress... lo) {
            current = List.of(new NetIf("lo0", true, true, List.of(lo)));
        }

        @Override
        public List<NetIf> snapshot() {
            return current;
        }
    }

    static Server.Settings settings(InterfaceSource src, List<String> log) {
        Server.Settings s = new Server.Settings();
        s.interfaces = src;
        s.resolver = h -> List.of();
        s.log = line -> {
            synchronized (log) {
                log.add(line);
            }
        };
        s.rescanMillis = 100;
        s.factory = (o, m) -> new StubGameHost(m, 32);
        return s;
    }

    static int freePort(InetAddress a) throws IOException {
        try (DatagramChannel ch = DatagramChannel.open(a instanceof java.net.Inet4Address
            ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6)) {
            ch.bind(new InetSocketAddress(a, 0));
            return ((InetSocketAddress) ch.getLocalAddress()).getPort();
        }
    }

    static boolean canBind(InetAddress a) {
        try {
            freePort(a);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** A {@link TestClient} on a real non-blocking DatagramChannel. */
    static final class UdpClient implements AutoCloseable {
        final TestClient c;
        final DatagramChannel ch;
        final InetSocketAddress server;
        final ByteBuffer rx = ByteBuffer.allocate(Protocol.MAX_PACKET + 16);
        final List<SocketAddress> replySources = new ArrayList<>();

        UdpClient(int nonce, InetSocketAddress server) throws IOException {
            this.c = new TestClient(nonce);
            this.server = server;
            this.ch = DatagramChannel.open(server.getAddress() instanceof java.net.Inet4Address
                ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6);
            ch.bind(new InetSocketAddress(server.getAddress(), 0));
            ch.configureBlocking(false);
        }

        void send(ByteBuffer b) throws IOException {
            ch.send(b, server);
        }

        /** Receive everything waiting. */
        void pump() throws IOException {
            while (true) {
                rx.clear();
                SocketAddress from = ch.receive(rx);
                if (from == null) {
                    return;
                }
                replySources.add(from);
                rx.flip();
                c.onPacket(rx, System.nanoTime());
            }
        }

        /** Send CONNECT every 50 ms until ACCEPT or REJECT, at most 5 s. */
        void connect(String name) throws Exception {
            long end = System.nanoTime() + 5_000_000_000L;
            while (!c.accepted && c.rejectReason < 0 && System.nanoTime() < end) {
                send(c.connect(name, 255, ""));
                Thread.sleep(50);
                pump();
            }
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }
    }

    @Test
    @DisplayName("two bound addresses: each client's replies come from the address it contacted")
    void repliesFromContactedAddress() throws Exception {
        boolean aliased = canBind(InetAddress.ofLiteral("127.0.0.2"));
        InetAddress second = aliased ? InetAddress.ofLiteral("127.0.0.2") : V6;
        Assumptions.assumeTrue(aliased || canBind(V6),
            "needs 127.0.0.2 (aliased loopback) or ::1; neither is available, skipping");
        if (!aliased) {
            System.out.println("[LoopbackIntegrationTest] 127.0.0.2 is not aliased (macOS default); using 127.0.0.1 + ::1");
        }
        List<String> log = new ArrayList<>();
        int port = freePort(V4);
        MutableInterfaces lo = new MutableInterfaces(V4, second);
        try (Server server = Server.start(new String[] {"--map", MAP, "--port", Integer.toString(port), "--bind", "loopback"},
            settings(lo, log))) {
            BindTarget t1 = new BindTarget(V4, "lo0");
            BindTarget t2 = new BindTarget(second, "lo0");
            InetSocketAddress a1 = server.localAddress(t1);
            InetSocketAddress a2 = server.localAddress(t2);
            try (UdpClient c1 = new UdpClient(1, new InetSocketAddress(V4, port));
                 UdpClient c2 = new UdpClient(2, new InetSocketAddress(second, port))) {
                c1.connect("one");
                c2.connect("two");
                for (ByteBuffer b : c1.c.frame(System.nanoTime(), 0)) {
                    c1.send(b);
                }
                for (ByteBuffer b : c2.c.frame(System.nanoTime(), 0)) {
                    c2.send(b);
                }
                Thread.sleep(200);
                c1.pump();
                c2.pump();
                List<String> lines = List.copyOf(log);
                assertAll(
                    () -> assertEquals(List.of(t1, t2), server.bound(), "both loopback addresses are bound"),
                    () -> assertEquals(port, a1.getPort(), "on the configured port"),
                    () -> assertEquals(port, a2.getPort(), "both on the same port"),
                    () -> assertTrue(c1.c.accepted && c2.c.accepted, "both clients are accepted"),
                    () -> assertTrue(c1.replySources.size() > 1 && c2.replySources.size() > 1, "both got several replies"),
                    () -> assertTrue(c1.replySources.stream().allMatch(s -> s.equals(new InetSocketAddress(V4, port))),
                        "every reply to client 1 comes from 127.0.0.1:port: " + c1.replySources),
                    () -> assertTrue(c2.replySources.stream().allMatch(s -> s.equals(new InetSocketAddress(second, port))),
                        "every reply to client 2 comes from " + second + ":port: " + c2.replySources),
                    () -> assertTrue(lines.contains("listening on 127.0.0.1:" + port + " (lo0)"), "startup logs address:port (iface)"),
                    () -> assertTrue(lines.contains("listening on " + t2.describe(port)),
                        "the second address is logged too: " + lines));
            }
        }
    }

    @Test
    @DisplayName("join, map transfer, 80 frames of snapshots at the map's 14 fps, input and chat over real UDP")
    void fullSession() throws Exception {
        List<String> log = new ArrayList<>();
        int port = freePort(V4);
        try (Server server = Server.start(new String[] {"--map", MAP, "--port", Integer.toString(port), "--bind", "127.0.0.1"},
            settings(new MutableInterfaces(V4), log));
             UdpClient u = new UdpClient(77, new InetSocketAddress(V4, port))) {
            u.connect("scripted");
            long thrust = ControlKey.THRUST.mask();
            long end = System.nanoTime() + 20_000_000_000L;
            boolean chatted = false;
            float yBeforeThrust = Float.NaN;
            while (System.nanoTime() < end) {
                TestClient c = u.c;
                if (c.hasMap() && !c.readySent) {
                    c.ready();
                }
                if (!c.snapshotTicks.isEmpty() && !chatted) {
                    c.chat("hello loopback");
                    chatted = true;
                }
                int ticksSeen = c.snapshotTicks.isEmpty() ? 0
                    : c.snapshotTicks.get(c.snapshotTicks.size() - 1) - c.snapshotTicks.get(0);
                if (ticksSeen >= 40 && Float.isNaN(yBeforeThrust)) {
                    yBeforeThrust = c.lastSelfY;
                }
                for (ByteBuffer b : c.frame(System.nanoTime(), Float.isNaN(yBeforeThrust) ? 0 : thrust)) {
                    u.send(b);
                }
                Thread.sleep(16);
                u.pump();
                if (ticksSeen >= 80 && c.reliableLog.contains("MESSAGE hello loopback")) {
                    break;
                }
            }
            TestClient c = u.c;
            Options clientOpts = new Options();
            ConfigParser.parse(c.configText == null ? "" : c.configText, "server", OptionOrigin.MAP, clientOpts);
            WorldMap clientMap = c.configText == null ? null : WorldMap.fromOptions(clientOpts);
            WorldMap serverMap = WorldMap.load(Path.of(MAP));
            List<Integer> ticks = c.snapshotTicks;
            boolean ordered = true;
            for (int i = 1; i < ticks.size(); i++) {
                ordered &= ticks.get(i) > ticks.get(i - 1);
            }
            boolean inOrder = ordered;
            float y0 = yBeforeThrust;
            assertAll(
                () -> assertTrue(c.accepted, "the client is accepted"),
                () -> assertNotNull(clientMap, "the map arrives"),
                () -> assertEquals(serverMap.width(), clientMap.width(), "the client builds the same map (width)"),
                () -> assertEquals(serverMap.height(), clientMap.height(), "the client builds the same map (height)"),
                () -> assertEquals(serverMap.bases().size(), clientMap.bases().size(), "the client builds the same map (bases)"),
                () -> assertTrue(c.reliableLog.contains("JOIN " + c.playerId + " self"), "the initial state names the client"),
                () -> assertTrue(ticks.get(ticks.size() - 1) - ticks.get(0) >= 80, "snapshots span at least 80 ticks"),
                () -> assertTrue(ticks.size() >= 75, "about one snapshot per tick arrives (" + ticks.size() + ")"),
                () -> assertEquals(14, c.welcome.simHz, "the server ticks at doggy.xp's (default) 14 fps"),
                () -> assertTrue(inOrder, "loopback delivers them in order"),
                () -> assertTrue(c.radarSnapshots >= ticks.size() / 3 - 2, "radar in every 3rd snapshot"),
                () -> assertTrue(c.lastSelfY > y0, "held THRUST moves the stub ship (input reaches the host)"),
                () -> assertTrue(c.reliableLog.contains("MESSAGE hello loopback"), "chat is echoed back"),
                () -> assertTrue(log.stream().anyMatch(l -> l.startsWith("join: player 0 'scripted'")), "the join is logged"));
            server.close();
            Thread.sleep(100);
            u.pump();
            assertAll(
                () -> assertEquals(3, u.c.disconnects, "shutdown sends DISCONNECT three times"),
                () -> assertEquals(Msg.Disconnect.REASON_SHUTDOWN, u.c.disconnectReason, "with the shutdown reason"));
        }
    }

    @Test
    @DisplayName("rescans bind an address that appears and close one that vanishes")
    void rescan() throws Exception {
        Assumptions.assumeTrue(canBind(V6), "needs ::1 to simulate an interface coming up; skipping");
        List<String> log = new ArrayList<>();
        int port = freePort(V4);
        MutableInterfaces lo = new MutableInterfaces(V4);
        try (Server server = Server.start(new String[] {"--map", MAP, "--port", Integer.toString(port), "--bind", "if:lo0"},
            settings(lo, log))) {
            List<BindTarget> before = server.bound();
            lo.set(V4, V6);
            BindTarget v6 = new BindTarget(V6, "lo0");
            waitFor(() -> server.bound().contains(v6));
            boolean acceptedOnNew;
            try (UdpClient c = new UdpClient(5, new InetSocketAddress(V6, port))) {
                c.connect("late");
                acceptedOnNew = c.c.accepted;
            }
            lo.set(V4);
            waitFor(() -> !server.bound().contains(v6));
            List<BindTarget> after = server.bound();
            List<String> lines;
            synchronized (log) {
                lines = List.copyOf(log);
            }
            boolean ok = acceptedOnNew;
            assertAll(
                () -> assertEquals(List.of(new BindTarget(V4, "lo0")), before, "only 127.0.0.1 at first"),
                () -> assertTrue(ok, "a client can join on the newly bound address"),
                () -> assertEquals(List.of(new BindTarget(V4, "lo0")), after, "the vanished address is closed"),
                () -> assertTrue(lines.contains("listening on [::1]:" + port + " (lo0)"), "the new bind is logged"),
                () -> assertTrue(lines.contains("unbound [::1]:" + port + " (lo0)"), "the unbind is logged"));
        }
    }

    @Test
    @DisplayName("startup: unmatched selectors fail, bindWait waits, a missing map fails")
    void startup() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        MutableInterfaces lo = new MutableInterfaces(V4);
        IllegalArgumentException noMatch = assertThrows(IllegalArgumentException.class,
            () -> Server.start(new String[] {"--map", MAP, "--port", "0", "--bind", "10.99.0.0/16"}, settings(lo, log)),
            "a selector matching nothing is a startup error");
        IllegalArgumentException noMap = assertThrows(IllegalArgumentException.class,
            () -> Server.start(new String[] {"--port", "0"}, settings(lo, log)), "--map is required");

        MutableInterfaces later = new MutableInterfaces();
        int port = freePort(V4);
        CompletableFuture<Server> starting = CompletableFuture.supplyAsync(() -> {
            try {
                return Server.start(new String[] {"--map", MAP, "--port", Integer.toString(port), "--bind", "127.0.0.1",
                    "--bindWait"}, settings(later, log));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(300);
        boolean waiting = !starting.isDone();
        later.set(V4);
        try (Server s = starting.get(10, TimeUnit.SECONDS)) {
            assertAll(
                () -> assertTrue(noMatch.getMessage().contains("10.99.0.0/16"), "the error names the selector"),
                () -> assertTrue(noMap.getMessage().contains("--map"), "the error names --map"),
                () -> assertTrue(waiting, "with bindWait, start blocks while nothing matches"),
                () -> assertEquals(List.of(new BindTarget(V4, "lo0")), s.bound(), "and binds once the address appears"),
                () -> assertTrue(log.stream().anyMatch(l -> l.startsWith("bindWait: nothing matches")), "waiting is logged"));
        }
    }

    @Test
    @DisplayName("UdpTransport drops oversized datagrams and never reuses a closed channel id")
    void transportEdges() throws Exception {
        List<String> log = new ArrayList<>();
        List<String> got = new ArrayList<>();
        try (UdpTransport t = new UdpTransport(0, log::add, System::nanoTime)) {
            t.setReceiver((ch, from, data, now) -> got.add(ch + ":" + data.remaining()));
            BindTarget a = new BindTarget(V4, "lo0");
            t.apply(BindPlan.diff(List.of(), List.of(a)));
            InetSocketAddress addrA = t.localAddress(a);
            try (DatagramChannel c = DatagramChannel.open(StandardProtocolFamily.INET)) {
                c.bind(new InetSocketAddress(V4, 0));
                c.send(ByteBuffer.allocate(Protocol.MAX_PACKET + 1), addrA);
                c.send(ByteBuffer.allocate(Protocol.MAX_PACKET), addrA);
                long end = System.nanoTime() + 2_000_000_000L;
                while (got.isEmpty() && System.nanoTime() < end) {
                    t.poll(10_000_000L);
                }
                t.apply(BindPlan.diff(List.of(a), List.of()));
                t.apply(BindPlan.diff(List.of(), List.of(a)));
                InetSocketAddress addrA2 = t.localAddress(a);
                ByteBuffer ping = ByteBuffer.allocate(8);
                t.send(0, c.getLocalAddress(), ping);
                c.configureBlocking(false);
                Thread.sleep(50);
                boolean oldChannelSilent = c.receive(ByteBuffer.allocate(16)) == null;
                c.send(ByteBuffer.allocate(10), addrA2);
                got.clear();
                end = System.nanoTime() + 2_000_000_000L;
                while (got.isEmpty() && System.nanoTime() < end) {
                    t.poll(500_000L);
                }
                assertAll(
                    () -> assertEquals(1, t.oversized(), "a datagram over 1200 bytes is dropped"),
                    () -> assertEquals(List.of("1:10"), got, "the reopened address gets a new channel id, 1"),
                    () -> assertTrue(oldChannelSilent, "sending on the closed channel id 0 goes nowhere"),
                    () -> assertFalse(log.isEmpty(), "binds are logged"),
                    () -> assertTrue(log.get(1).startsWith("unbound 127.0.0.1:"), "unbinding is logged"));
            }
        }
    }

    @Test
    @DisplayName("an interface renumbered with the same address keeps its channel on the same port")
    void renumberSamePort() throws Exception {
        int port = freePort(V4);
        List<String> log = new ArrayList<>();
        List<String> got = new ArrayList<>();
        try (UdpTransport t = new UdpTransport(port, log::add, System::nanoTime);
             DatagramChannel peer = DatagramChannel.open(StandardProtocolFamily.INET)) {
            t.setReceiver((ch, from, data, now) -> got.add(ch + ":" + data.remaining()));
            BindTarget utun4 = new BindTarget(V4, "utun4");
            BindTarget utun5 = new BindTarget(V4, "utun5");
            t.apply(BindPlan.diff(List.of(), List.of(utun4)));
            peer.bind(new InetSocketAddress(V4, 0));
            peer.send(ByteBuffer.allocate(3), new InetSocketAddress(V4, port));
            pollUntil(t, got, 1);
            int opened = t.apply(BindPlan.diff(t.boundTargets(), List.of(utun5)));
            peer.send(ByteBuffer.allocate(4), new InetSocketAddress(V4, port));
            pollUntil(t, got, 2);
            assertAll(
                () -> assertEquals(0, opened, "nothing is reopened"),
                () -> assertEquals(List.of(utun5), t.boundTargets(), "the target now carries the new interface name"),
                () -> assertEquals(port, t.localAddress(utun5).getPort(), "on the same port"),
                () -> assertEquals(List.of("0:3", "0:4"), got, "the same channel keeps receiving"),
                () -> assertTrue(log.contains("interface renamed: 127.0.0.1:" + port + " (utun4) is now utun5"),
                    "the rename is logged: " + log),
                () -> assertTrue(BindPlan.diff(t.boundTargets(), List.of(utun5)).isEmpty(), "and the next rescan sees no change"));
        }
    }

    private static void pollUntil(UdpTransport t, List<String> got, int n) throws IOException {
        long end = System.nanoTime() + 2_000_000_000L;
        while (got.size() < n && System.nanoTime() < end) {
            t.poll(5_000_000L);
        }
    }

    @Test
    @DisplayName("a bind that fails is retried by the next rescan")
    void retryFailedBind() throws Exception {
        Assumptions.assumeTrue(canBind(V6), "needs ::1; skipping");
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        int port = freePort(V4);
        MutableInterfaces lo = new MutableInterfaces(V4);
        try (Server server = Server.start(new String[] {"--map", MAP, "--port", Integer.toString(port), "--bind", "if:lo0"},
            settings(lo, log))) {
            BindTarget v6 = new BindTarget(V6, "lo0");
            boolean failedWhileBusy;
            try (DatagramChannel blocker = DatagramChannel.open(StandardProtocolFamily.INET6)) {
                blocker.bind(new InetSocketAddress(V6, port));
                lo.set(V4, V6);
                waitFor(() -> log.stream().anyMatch(l -> l.startsWith("cannot bind [::1]:" + port)));
                failedWhileBusy = !server.bound().contains(v6);
            }
            waitFor(() -> server.bound().contains(v6));
            boolean busy = failedWhileBusy;
            assertAll(
                () -> assertTrue(busy, "the address is not bound while its port is taken"),
                () -> assertEquals(List.of(new BindTarget(V4, "lo0"), v6), server.bound(), "a later rescan binds it"));
        }
    }

    @Test
    @DisplayName("literal selectors with bindWait bind an address that appears later")
    void literalBindWait() throws Exception {
        Assumptions.assumeTrue(canBind(V6), "needs ::1; skipping");
        List<String> log = new ArrayList<>();
        MutableInterfaces lo = new MutableInterfaces(V4);
        int port = freePort(V4);
        try (Server s = Server.start(new String[] {"--map", MAP, "--port", Integer.toString(port),
            "--bind", "127.0.0.1,::1", "--bindWait"}, settings(lo, log))) {
            List<BindTarget> before = s.bound();
            lo.set(V4, V6);
            waitFor(() -> s.bound().size() == 2);
            assertAll(
                () -> assertEquals(List.of(new BindTarget(V4, "lo0")), before, "only 127.0.0.1 exists at first"),
                () -> assertEquals(List.of(new BindTarget(V4, "lo0"), new BindTarget(V6, "lo0")), s.bound(),
                    "::1 is bound once it appears"));
        }
    }

    @Test
    @DisplayName("a dead server loop completes termination() exceptionally")
    void loopDeath() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Server.Settings st = settings(new MutableInterfaces(V4), log);
        IllegalStateException boom = new IllegalStateException("simulated host failure");
        st.factory = (o, m) -> new DyingHost(new StubGameHost(m, 4), 5, boom);
        Server s = Server.start(new String[] {"--map", MAP, "--port", "0", "--bind", "127.0.0.1"}, st);
        Throwable cause = null;
        try {
            s.termination().get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            cause = e.getCause();
        } finally {
            s.close();
        }
        Throwable c = cause;
        assertAll(
            () -> assertEquals(boom, c, "termination fails with the loop's exception"),
            () -> assertTrue(log.contains("server loop failed: " + boom), "the failure is logged"),
            () -> assertTrue(s.bound().isEmpty(), "the channels are closed"));
    }

    @Test
    @DisplayName("close() completes termination() normally")
    void normalTermination() throws Exception {
        Server s = Server.start(new String[] {"--map", MAP, "--port", "0", "--bind", "127.0.0.1"},
            settings(new MutableInterfaces(V4), new ArrayList<>()));
        boolean runningBefore = !s.termination().isDone();
        s.close();
        assertAll(
            () -> assertTrue(runningBefore, "termination is pending while the server runs"),
            () -> assertTrue(s.termination().isDone() && !s.termination().isCompletedExceptionally(),
                "it completes normally after close"));
    }

    interface Cond {
        boolean ok();
    }

    static void waitFor(Cond c) throws InterruptedException {
        long end = System.nanoTime() + 10_000_000_000L;
        while (!c.ok()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not met within 10 s");
            }
            Thread.sleep(20);
        }
    }
}
