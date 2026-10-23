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

import com.fulcrologic.jxpilot.common.net.ConfigTransfer;
import com.fulcrologic.jxpilot.common.net.PacketHeader;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** In-memory server: {@link ServerNet} + {@link FakeHost} + a capturing {@link Datagrams} and a fake clock. */
final class Harness implements Datagrams {
    static final long TICK = 16_666_667L;
    static final long SESSION_SEED = 0x5EED;
    static final long KEY_SECRET = 0x5EC12E7L;

    /** One datagram the server sent. */
    record Sent(int channel, SocketAddress to, ByteBuffer data) {
        int type() {
            return data.get(3) & 0xFF;
        }

        int session() {
            return data.getShort(4) & 0xFFFF;
        }
    }

    final List<Sent> sent = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    final FakeHost host = new FakeHost();
    final ServerNetConfig cfg;
    final ConfigTransfer.Blob blob;
    final String configText;
    ServerNet net;
    long now = 5_000_000_000L;

    Harness(ServerNetConfig cfg, String configText) {
        this.cfg = cfg;
        this.configText = configText;
        this.blob = ConfigTransfer.encode(configText);
        this.net = new ServerNet(cfg, host, this, log::add, blob, SESSION_SEED, KEY_SECRET);
    }

    Harness() {
        this(new ServerNetConfig(), "mapName: test\n");
    }

    static InetSocketAddress addr(String ip, int port) {
        return new InetSocketAddress(InetAddress.ofLiteral(ip), port);
    }

    /** When set, datagrams go here instead of being captured (allocation tests). */
    Datagrams passthrough;

    @Override
    public void send(int channel, SocketAddress to, ByteBuffer data) {
        if (passthrough != null) {
            passthrough.send(channel, to, data);
            return;
        }
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data.duplicate()).flip();
        data.position(data.limit());
        sent.add(new Sent(channel, to, copy));
    }

    void in(int channel, SocketAddress from, ByteBuffer b) {
        net.onDatagram(channel, from, b, now);
    }

    void tick() {
        now += TICK;
        net.tick(now);
    }

    /** Remove and return what was sent to {@code to}. */
    List<Sent> take(SocketAddress to) {
        List<Sent> out = new ArrayList<>();
        for (Iterator<Sent> it = sent.iterator(); it.hasNext(); ) {
            Sent s = it.next();
            if (s.to().equals(to)) {
                out.add(s);
                it.remove();
            }
        }
        return out;
    }

    List<Integer> types(List<Sent> l) {
        List<Integer> out = new ArrayList<>();
        for (Sent s : l) {
            out.add(s.type());
        }
        return out;
    }

    /** Deliver everything sent to {@code to} to the client. */
    List<Sent> deliver(TestClient c, SocketAddress to) {
        List<Sent> l = take(to);
        for (Sent s : l) {
            c.onPacket(s.data().duplicate(), now);
        }
        return l;
    }

    /** One client frame up (reliable + input) then one server tick, then delivery down. */
    void step(TestClient c, int channel, SocketAddress from, long keys) {
        for (ByteBuffer b : c.frame(now, keys)) {
            in(channel, from, b);
        }
        tick();
        deliver(c, from);
    }

    /** Connect, receive the map and send READY; runs ticks until snapshots flow. */
    void join(TestClient c, int channel, SocketAddress from, String name) {
        in(channel, from, c.connect(name, 255, ""));
        deliver(c, from);
        for (int i = 0; i < 600 && c.snapshotTicks.isEmpty(); i++) {
            if (c.hasMap() && !c.readySent) {
                c.ready();
            }
            step(c, channel, from, 0);
        }
        if (c.snapshotTicks.isEmpty()) {
            throw new AssertionError("client never got a snapshot");
        }
    }

    boolean isConnected(int channel, SocketAddress from) {
        return net.sessionOf(channel, from) != -1;
    }

    static int headerAck(ByteBuffer b) {
        return b.getShort(6) & 0xFFFF;
    }

    static int bodyStart() {
        return PacketHeader.SIZE;
    }
}
