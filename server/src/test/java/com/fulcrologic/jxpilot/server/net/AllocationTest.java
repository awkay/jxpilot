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
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Steady-state server networking allocates nothing (SPEC GC rules). Runs in its own surefire
 * execution with {@code -XX:-DoEscapeAnalysis}, so a pass does not depend on scalar replacement.
 */
@DisplayName("Server networking hot paths are allocation free")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class AllocationTest {
    private static final com.sun.management.ThreadMXBean MX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final int CLIENTS = 3;

    /** Routes the server's datagrams back to the clients' reliable channels without allocating. */
    static final class Loop implements Datagrams {
        final SocketAddress[] addrs = new SocketAddress[CLIENTS];
        final TestClient[] clients = new TestClient[CLIENTS];
        long now;
        long bytes;
        long packets;

        @Override
        public void send(int channel, SocketAddress to, ByteBuffer data) {
            bytes += data.remaining();
            packets++;
            for (int i = 0; i < CLIENTS; i++) {
                if (addrs[i] == to && clients[i] != null && clients[i].accepted) {
                    clients[i].ch.onAck(data.getShort(data.position() + 6) & 0xFFFF, now);
                }
            }
            data.position(data.limit());
        }
    }

    @Test
    @DisplayName("ServerNet: inputs, chat, acks, ticks, snapshots for 3 peers")
    void serverNet() {
        Loop loop = new Loop();
        Harness h = new Harness();
        // Join through the capturing harness, then route the same ServerNet's output through the loop.
        TestClient[] cs = new TestClient[CLIENTS];
        for (int i = 0; i < CLIENTS; i++) {
            cs[i] = new TestClient(100 + i);
            loop.addrs[i] = Harness.addr("10.0.0." + (i + 1), 5000);
            h.join(cs[i], 0, loop.addrs[i], "p" + i);
        }
        h.host.record = false;
        h.sent.clear();
        h.passthrough = loop;
        ServerNet net = h.net;
        FakeHost host = h.host;
        ByteBuffer[] input = new ByteBuffer[CLIENTS];
        ByteBuffer[] rel = new ByteBuffer[CLIENTS];
        Msg.Text chat = new Msg.Text();
        chat.text.append("steady chat line");
        int[] seq = new int[CLIENTS];
        for (int i = 0; i < CLIENTS; i++) {
            input[i] = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
            rel[i] = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
            seq[i] = cs[i].inputs.latestSeq();
            loop.clients[i] = cs[i];
        }
        long keys = ControlKey.THRUST.mask() | ControlKey.FIRE_SHOT.mask();
        Runnable step = () -> {
            for (int i = 0; i < CLIENTS; i++) {
                TestClient c = cs[i];
                if ((seq[i] & 15) == 0) {
                    chat.sendChat(c.ch);
                }
                if (c.ch.wantsSend(h.now)) {
                    ByteBuffer r = rel[i];
                    r.clear();
                    PacketHeader.write(r, PacketType.RELIABLE, c.session, c.ch.ack());
                    c.ch.write(r, h.now);
                    r.flip();
                    net.onDatagram(0, loop.addrs[i], r, h.now);
                }
                seq[i]++;
                ByteBuffer b = input[i];
                b.clear();
                PacketHeader.write(b, PacketType.INPUT, c.session, c.ch.ack());
                b.putInt(seq[i]).putInt(0).put((byte) 4);
                for (int k = 0; k < 4; k++) {
                    b.putLong(keys ^ (seq[i] - k & 1)).putShort((short) 0);
                }
                b.flip();
                net.onDatagram(0, loop.addrs[i], b, h.now);
            }
            h.now += Harness.TICK;
            loop.now = h.now;
            net.tick(h.now);
        };
        for (int i = 0; i < 20_000; i++) {
            step.run();
        }
        long inputsBefore = host.inputCount;
        long before = MX.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 20_000; i++) {
            step.run();
        }
        long allocated = MX.getCurrentThreadAllocatedBytes() - before;
        long inputs = host.inputCount - inputsBefore;
        assertAll(
            () -> assertEquals(0, allocated, "20000 steady-state ticks with 3 peers allocate nothing"),
            () -> assertEquals(20_000L * CLIENTS, inputs, "every input was applied (the loop did real work)"),
            () -> assertEquals(CLIENTS, net.connections(), "no peer timed out"),
            () -> assertEquals(0, net.malformed(), "no packet was malformed"),
            () -> assertTrue(loop.packets > 20_000, "snapshots and acks were sent (" + loop.packets + ")"));
    }

    @Test
    @DisplayName("pre-session floods: malformed CONNECTs, other-version CONNECTs and strays allocate nothing")
    void preSessionFloods() {
        Harness h = new Harness();
        InetSocketAddress[] src = new InetSocketAddress[64];
        for (int i = 0; i < src.length; i++) {
            src[i] = Harness.addr("10.1.0." + i, 999);
        }
        ByteBuffer badConnect = ByteBuffer.allocateDirect(64);   // name length 200, 3 bytes present
        PacketHeader.write(badConnect, PacketType.CONNECT, 0, 0);
        badConnect.putInt(1).put((byte) 0).put((byte) 200).put((byte) 'a').put((byte) 'b').put((byte) 'c').flip();
        ByteBuffer otherVersion = ByteBuffer.allocateDirect(64);
        PacketHeader.write(otherVersion, PacketType.CONNECT, 0, 0);
        otherVersion.putInt(7).put((byte) 0).put((byte) 1).put((byte) 'x').putShort((short) 0).flip();
        otherVersion.put(2, (byte) 9);
        ByteBuffer stray = ByteBuffer.allocateDirect(64);
        PacketHeader.write(stray, PacketType.INPUT, 5, 0);
        stray.putLong(0).flip();
        ByteBuffer[] kinds = {badConnect, otherVersion, stray};
        Runnable flood = () -> {
            for (int i = 0; i < 30_000; i++) {
                ByteBuffer b = kinds[i % 3];
                b.rewind();
                h.now += 3_000_000L;   // stays under the per-source rate, so every packet is parsed
                h.net.onDatagram(0, src[i & 63], b, h.now);
            }
        };
        h.passthrough = (c, t, d) -> d.position(d.limit());
        for (int i = 0; i < 5; i++) {
            flood.run();
        }
        // A late JIT install can allocate ~2 KB once on the measured thread (seen on CI). Measure several
        // rounds: one allocation per packet would show as >= 30000 B in every round, so require a clean
        // round and bound the worst one well below that.
        long malformedBefore = h.net.malformed();
        long cleanest = Long.MAX_VALUE;
        long worst = 0;
        for (int round = 0; round < 4; round++) {
            long before = MX.getCurrentThreadAllocatedBytes();
            flood.run();
            long allocated = MX.getCurrentThreadAllocatedBytes() - before;
            cleanest = Math.min(cleanest, allocated);
            worst = Math.max(worst, allocated);
        }
        long clean = cleanest;
        long worstRound = worst;
        long malformed = h.net.malformed() - malformedBefore;
        assertAll(
            () -> assertEquals(0, clean, "a round of 30000 malformed, other-version and stray packets allocates nothing"),
            () -> assertTrue(worstRound <= 4096, "no round allocates per packet (worst " + worstRound + " B)"),
            () -> assertEquals(40_000, malformed, "every bad CONNECT was parsed and refused"),
            () -> assertEquals(0, h.net.rateLimited(), "nothing was rate limited (all were parsed)"),
            () -> assertEquals(90_000, h.net.versionRejects(), "other-version CONNECTs were answered (all nine rounds)"));
    }

    @Test
    @DisplayName("the server loop thread over real loopback UDP (one peer)")
    void realLoop() throws Exception {
        InetAddress lo = InetAddress.ofLiteral("127.0.0.1");
        int port = LoopbackIntegrationTest.freePort(lo);
        List<String> log = new ArrayList<>();
        Server.Settings s = LoopbackIntegrationTest.settings(new LoopbackIntegrationTest.MutableInterfaces(lo), log);
        try (Server server = Server.start(new String[] {"--map", Path.of("..", "maps", "doggy.xp").toString(),
                "--port", Integer.toString(port), "--bind", "127.0.0.1"}, s);
             LoopbackIntegrationTest.UdpClient u = new LoopbackIntegrationTest.UdpClient(9, new InetSocketAddress(lo, port))) {
            u.connect("alloc");
            Thread loopThread = null;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().equals("jxpilot-server-loop")) {
                    loopThread = t;
                }
            }
            long id = loopThread.threadId();
            long warmEnd = System.nanoTime() + 4_000_000_000L;
            long measureStart = 0;
            long before = 0;
            int snapshotsBefore = 0;
            long end = warmEnd + 4_000_000_000L;
            while (System.nanoTime() < end) {
                TestClient c = u.c;
                if (c.hasMap() && !c.readySent) {
                    c.ready();
                }
                for (ByteBuffer b : c.frame(System.nanoTime(), ControlKey.THRUST.mask())) {
                    u.send(b);
                }
                Thread.sleep(16);
                u.pump();
                if (measureStart == 0 && System.nanoTime() > warmEnd) {
                    measureStart = System.nanoTime();
                    before = MX.getThreadAllocatedBytes(id);
                    snapshotsBefore = c.snapshotTicks.size();
                }
            }
            long allocated = MX.getThreadAllocatedBytes(id) - before;
            int snapshots = u.c.snapshotTicks.size() - snapshotsBefore;
            assertAll(
                () -> assertEquals(0, allocated, "4 s of 14 fps receive/dispatch/tick/send on the loop thread allocate nothing"),
                () -> assertTrue(snapshots > 40, "snapshots flowed during the measurement (" + snapshots + ")"));
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("the real loop with the real game: one peer and 8 robots on doggy.xp allocate nothing on the loop thread")
    void realLoopSimHost() throws Exception {
        InetAddress lo = InetAddress.ofLiteral("127.0.0.1");
        int port = LoopbackIntegrationTest.freePort(lo);
        Server.Settings s = LoopbackIntegrationTest.settings(new LoopbackIntegrationTest.MutableInterfaces(lo), new ArrayList<>());
        java.util.concurrent.atomic.AtomicReference<com.fulcrologic.jxpilot.server.host.SimGameHost> hostRef =
            new java.util.concurrent.atomic.AtomicReference<>();
        com.fulcrologic.jxpilot.server.host.SimGameHostFactory real =
            new com.fulcrologic.jxpilot.server.host.SimGameHostFactory(() -> 77);
        s.factory = (o, m) -> {
            com.fulcrologic.jxpilot.server.host.SimGameHost h =
                (com.fulcrologic.jxpilot.server.host.SimGameHost) real.create(o, m);
            hostRef.set(h);
            return h;
        };
        long measuredTicks;
        long allocated;
        int snapshots;
        String[] args = {"--map", Path.of("..", "maps", "doggy.xp").toString(), "--port", Integer.toString(port),
            "--bind", "127.0.0.1", "-maxRobots", "8", "-minRobots", "8"};
        warmUp(args);
        try (Server server = Server.start(args, s);
             LoopbackIntegrationTest.UdpClient u = new LoopbackIntegrationTest.UdpClient(31, new InetSocketAddress(lo, port))) {
            u.connect("alloc");
            Thread loopThread = null;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().equals("jxpilot-server-loop")) {
                    loopThread = t;
                }
            }
            long id = loopThread.threadId();
            // robots join one per 2*FPS frames once the human is in: 8 robots after ~17 s
            long warmEnd = System.nanoTime() + 22_000_000_000L;
            long end = warmEnd + 10_000_000_000L;
            long before = 0;
            int snapshotsBefore = 0;
            int tickBefore = 0;
            boolean measuring = false;
            long keys = ControlKey.THRUST.mask() | ControlKey.FIRE_SHOT.mask();
            long frame = 0;
            while (System.nanoTime() < end) {
                if (!measuring && System.nanoTime() > warmEnd) {
                    measuring = true;
                    before = MX.getThreadAllocatedBytes(id);
                    snapshotsBefore = u.c.snapshotTicks.size();
                    tickBefore = server.net().currentTick();
                }
                TestClient c = u.c;
                if (c.hasMap() && !c.readySent) {
                    c.ready();
                }
                frame++;
                for (ByteBuffer b : c.frame(System.nanoTime(), (frame / 40) % 2 == 0 ? keys : ControlKey.TURN_LEFT.mask())) {
                    u.send(b);
                }
                Thread.sleep(16);
                u.pump();
            }
            allocated = MX.getThreadAllocatedBytes(id) - before;
            snapshots = u.c.snapshotTicks.size() - snapshotsBefore;
            measuredTicks = server.net().currentTick() - tickBefore;
        }
        com.fulcrologic.jxpilot.server.world.World w = hostRef.get().simulation().world;
        int robots = hostRef.get().simulation().robots.numRobots();
        int robotJoins = 0;
        com.fulcrologic.jxpilot.server.robot.RobotRoster roster = hostRef.get().simulation().robots.roster();
        for (int i = 0; i < roster.size(); i++) {
            robotJoins += roster.used(i);
        }
        int joins = robotJoins;
        long allocatedBytes = allocated;
        int snaps = snapshots;
        long ticks = measuredTicks;
        System.out.printf("[AllocationTest] real game: %d B over %d ticks, %d snapshots, %d robots, %d events%n",
            allocatedBytes, ticks, snaps, robots, w.events.seq());
        assertAll(
            () -> assertEquals(0, allocatedBytes, "8 s of the real game (1 peer, 8 robots) allocate nothing on the loop thread"),
            () -> assertTrue(ticks > 100, "the game ticked during the measurement (" + ticks + ")"),
            () -> assertTrue(snaps > 80, "snapshots flowed during the measurement (" + snaps + ")"),
            () -> assertTrue(joins >= 8, "8 robots joined (" + joins + " robot joins)"),
            () -> assertTrue(robots >= 7, "and were playing at the end (" + robots + "; one may be between leaving and"
                + " its replacement, 2*FPS frames later)"));
    }

    /**
     * Before the real-time run: the same game in-process for 12000 ticks (1 human, 8 robots, every viewer path),
     * so the JIT has compiled the per-tick code (about 5000 calls each; at 14 ticks/s the real loop alone would
     * need minutes) and the rare first-time paths (class loading, constant resolution) are behind us. Only the
     * real-time window afterwards is measured.
     */
    static void warmUp(String[] args) throws Exception {
        com.fulcrologic.jxpilot.common.config.Options o = new com.fulcrologic.jxpilot.common.config.Options();
        o.parseCommandLine(args);
        com.fulcrologic.jxpilot.common.config.ConfigParser.parseFile(Path.of(o.getString(
            com.fulcrologic.jxpilot.common.config.Opt.MAP_FILE_NAME)), com.fulcrologic.jxpilot.common.config.OptionOrigin.MAP, o);
        o.finishParsing();
        com.fulcrologic.jxpilot.server.host.SimGameHost h = (com.fulcrologic.jxpilot.server.host.SimGameHost)
            new com.fulcrologic.jxpilot.server.host.SimGameHostFactory(() -> 5).create(o,
                com.fulcrologic.jxpilot.common.map.WorldMap.fromOptions(o));
        int id = h.join("warm", Protocol.NO_TEAM, "");
        h.initialState(id, new com.fulcrologic.jxpilot.common.net.ReliableChannel());
        com.fulcrologic.jxpilot.common.net.ReliableChannel out = new com.fulcrologic.jxpilot.common.net.ReliableChannel();
        ByteBuffer ready = ByteBuffer.allocate(0);
        h.clientMessage(id, com.fulcrologic.jxpilot.common.net.ReliableType.READY, ready);
        com.fulcrologic.jxpilot.common.net.SnapshotWriter wr = new com.fulcrologic.jxpilot.common.net.SnapshotWriter();
        com.fulcrologic.jxpilot.common.map.WorldMap m = com.fulcrologic.jxpilot.common.map.WorldMap.fromOptions(o);
        wr.setWorld(m.pixelWidth(), m.pixelHeight(), m.edgeWrap());
        ByteBuffer pkt = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
        long keys = ControlKey.THRUST.mask() | ControlKey.FIRE_SHOT.mask();
        for (int t = 1; t <= 12_000; t++) {
            h.input(id, t, (t / 40) % 2 == 0 ? keys : ControlKey.TURN_LEFT.mask(), 0);
            h.tick(t);
            for (int part = 0; part < 4; part++) {
                pkt.clear();
                com.fulcrologic.jxpilot.common.net.PacketHeader.write(pkt, PacketType.SNAPSHOT, 1, 0);
                wr.begin(pkt, t, t, part, 0, 0, h.viewX(id), h.viewY(id));
                if (!h.fillSnapshot(id, part, t % 3 == 0, wr)) {
                    break;
                }
            }
            h.drainEvents(id, out);
            for (int k = 0; k < 8; k++) {
                pkt.clear();
                if (out.write(pkt, t * 71_000_000L) == 0) {
                    break;
                }
            }
            out.onAck(out.nextSequence(), t * 71_000_000L);
        }
    }

    @Test
    @DisplayName("the real loop with two peers on one channel: at most 256 B per received datagram (JDK sender address)")
    void realLoopTwoPeers() throws Exception {
        InetAddress lo = InetAddress.ofLiteral("127.0.0.1");
        int port = LoopbackIntegrationTest.freePort(lo);
        Server.Settings s = LoopbackIntegrationTest.settings(new LoopbackIntegrationTest.MutableInterfaces(lo), new ArrayList<>());
        try (Server server = Server.start(new String[] {"--map", Path.of("..", "maps", "doggy.xp").toString(),
                "--port", Integer.toString(port), "--bind", "127.0.0.1"}, s);
             LoopbackIntegrationTest.UdpClient a = new LoopbackIntegrationTest.UdpClient(21, new InetSocketAddress(lo, port));
             LoopbackIntegrationTest.UdpClient b = new LoopbackIntegrationTest.UdpClient(22, new InetSocketAddress(lo, port))) {
            a.connect("alloc-a");
            b.connect("alloc-b");
            LoopbackIntegrationTest.UdpClient[] peers = {a, b};
            Thread loopThread = null;
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().equals("jxpilot-server-loop")) {
                    loopThread = t;
                }
            }
            long id = loopThread.threadId();
            long warmEnd = System.nanoTime() + 4_000_000_000L;
            long end = warmEnd + 4_000_000_000L;
            long before = 0;
            long sent = 0;
            boolean measuring = false;
            while (System.nanoTime() < end) {
                if (!measuring && System.nanoTime() > warmEnd) {
                    Thread.sleep(50);
                    for (LoopbackIntegrationTest.UdpClient u : peers) {
                        u.pump();
                    }
                    measuring = true;
                    before = MX.getThreadAllocatedBytes(id);
                }
                for (LoopbackIntegrationTest.UdpClient u : peers) {
                    TestClient c = u.c;
                    if (c.hasMap() && !c.readySent) {
                        c.ready();
                    }
                    for (ByteBuffer buf : c.frame(System.nanoTime(), ControlKey.THRUST.mask())) {
                        u.send(buf);
                        sent += measuring ? 1 : 0;
                    }
                }
                Thread.sleep(16);
                for (LoopbackIntegrationTest.UdpClient u : peers) {
                    u.pump();
                }
            }
            Thread.sleep(100);
            long allocated = MX.getThreadAllocatedBytes(id) - before;
            long datagrams = sent;
            double perDatagram = (double) allocated / datagrams;
            System.out.printf("[AllocationTest] two peers: %d B over %d received datagrams = %.1f B/datagram%n",
                allocated, datagrams, perDatagram);
            assertAll(
                () -> assertTrue(datagrams > 300, "both peers sent steadily (" + datagrams + ")"),
                () -> assertTrue(perDatagram <= 256, "at most 256 B per received datagram (" + perDatagram + ")"),
                () -> assertTrue(a.c.snapshotTicks.size() > 40 && b.c.snapshotTicks.size() > 40, "both peers got snapshots"));
        }
    }
}
