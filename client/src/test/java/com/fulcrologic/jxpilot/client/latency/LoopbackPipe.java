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
package com.fulcrologic.jxpilot.client.latency;

import com.fulcrologic.jxpilot.client.net.Transport;
import com.fulcrologic.jxpilot.server.net.Datagrams;
import com.fulcrologic.jxpilot.server.net.ServerNet;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.function.LongSupplier;

/**
 * An in-memory datagram pipe between one client and a {@link ServerNet}, with no delay or loss of its own:
 * client sends are handed to {@link ServerNet#onDatagram} at once (at the clock's time); server sends queue in a
 * preallocated ring until the client side receives them. Wrap the client side in an {@code EmulatedTransport}
 * for a lossy link. Allocation free after construction.
 */
final class LoopbackPipe implements Transport, Datagrams {
    /** The client's source address as the server sees it. */
    static final SocketAddress CLIENT = new InetSocketAddress(InetAddress.getLoopbackAddress(), 40000);
    static final int SLOTS = 256;
    static final int SLOT_BYTES = 2048;

    private final LongSupplier clock;
    private ServerNet net;
    private final byte[][] ring = new byte[SLOTS][SLOT_BYTES];
    private final int[] len = new int[SLOTS];
    private int head;
    private int count;
    private long overflow;

    LoopbackPipe(LongSupplier clock) {
        this.clock = clock;
    }

    void connect(ServerNet server) {
        this.net = server;
    }

    /** BEHAVIOR: the client's datagram reaches the server now, from {@link #CLIENT} on channel 0. */
    @Override
    public boolean send(ByteBuffer packet) {
        net.onDatagram(0, CLIENT, packet, clock.getAsLong());
        packet.position(packet.limit());
        return true;
    }

    /** BEHAVIOR: oldest server datagram first; 0 when none waits. */
    @Override
    public int receive(ByteBuffer buf) {
        if (count == 0) {
            return 0;
        }
        int n = Math.min(len[head], buf.remaining());
        buf.put(ring[head], 0, n);
        head = (head + 1) % SLOTS;
        count--;
        return n;
    }

    /** BEHAVIOR: a server datagram is queued for the client (dropped and counted when the ring is full). */
    @Override
    public void send(int channel, SocketAddress to, ByteBuffer data) {
        if (count == SLOTS) {
            overflow++;
            data.position(data.limit());
            return;
        }
        int slot = (head + count) % SLOTS;
        int n = data.remaining();
        data.get(ring[slot], 0, n);
        len[slot] = n;
        count++;
    }

    long overflow() {
        return overflow;
    }

    @Override
    public void close() {
    }
}
