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
                () -> assertEquals(0, allocated, "4 s of 60 Hz receive/dispatch/tick/send on the loop thread allocate nothing"),
                () -> assertTrue(snapshots > 90, "snapshots flowed during the measurement (" + snapshots + ")"));
        }
    }
}
