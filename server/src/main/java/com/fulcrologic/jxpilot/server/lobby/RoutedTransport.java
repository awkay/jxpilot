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
package com.fulcrologic.jxpilot.server.lobby;

import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.server.net.NetTransport;
import com.fulcrologic.jxpilot.server.net.UdpTransport;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The datagram edge of one map's game inside a lobby. The lobby's thread owns the socket and {@link #offer}s each
 * datagram of a routed peer here; the game's loop thread {@link #poll}s them out and hands them to its connection
 * layer, which answers through {@link #send}, straight out of the lobby's socket by the same channel the peer
 * arrived on.
 * <p>
 * Datagrams are copied into a fixed pool of buffers, so nothing is allocated per datagram. When the pool is empty
 * (the game loop fell behind a flood) the datagram is dropped, as a full socket buffer would.
 */
public final class RoutedTransport implements NetTransport {
    /** Datagrams that can wait for the game loop. */
    static final int SLOTS = 512;
    /** Datagrams delivered per {@link #poll}. */
    static final int MAX_DRAIN = 256;

    private static final class Slot {
        final ByteBuffer buf = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
        int channel;
        SocketAddress from;
        long nanos;
    }

    private static final Slot WAKE = new Slot();

    private final NetTransport out;
    private final ArrayBlockingQueue<Slot> ready = new ArrayBlockingQueue<>(SLOTS + 1);
    private final ArrayBlockingQueue<Slot> free = new ArrayBlockingQueue<>(SLOTS);
    private final AtomicLong dropped = new AtomicLong();
    private volatile UdpTransport.Receiver receiver = (c, f, d, n) -> { };
    private volatile boolean closed;

    /** @param out where replies go: the lobby's socket */
    public RoutedTransport(NetTransport out) {
        this.out = out;
        for (int i = 0; i < SLOTS; i++) {
            free.add(new Slot());
        }
    }

    /**
     * Queue a datagram for the game loop. Any thread.
     * <p>
     * BEHAVIOR: the bytes from the buffer's position to its limit are copied; the buffer's position is unchanged.
     * BEHAVIOR: false (and counted in {@link #dropped()}) when {@link #SLOTS} datagrams are already waiting;
     * false (not counted) once closed.
     */
    public boolean offer(int channel, SocketAddress from, ByteBuffer data, long nanos) {
        if (closed) {
            return false;
        }
        Slot s = free.poll();
        if (s == null) {
            dropped.incrementAndGet();
            return false;
        }
        int p = data.position();
        s.buf.clear();
        s.buf.put(data);
        data.position(p);
        s.buf.flip();
        s.channel = channel;
        s.from = from;
        s.nanos = nanos;
        ready.add(s);
        return true;
    }

    @Override
    public void setReceiver(UdpTransport.Receiver r) {
        receiver = r;
    }

    /**
     * Wait up to {@code timeoutNanos} for a datagram, then deliver everything that is waiting (at most
     * {@link #MAX_DRAIN}), each with the time it was offered.
     * <p>
     * BEHAVIOR: returns at once with nothing waiting and a timeout of zero or less; returns early when
     * {@link #wakeup} is called; returns (keeping the interrupt) when the thread is interrupted.
     */
    @Override
    public void poll(long timeoutNanos) {
        Slot s;
        try {
            s = timeoutNanos > 0 ? ready.poll(timeoutNanos, TimeUnit.NANOSECONDS) : ready.poll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        for (int i = 0; s != null && i < MAX_DRAIN; i++) {
            if (s != WAKE) {
                try {
                    receiver.onDatagram(s.channel, s.from, s.buf, s.nanos);
                } finally {
                    s.from = null;
                    free.add(s);
                }
            }
            s = ready.poll();
        }
        if (s != null) { // more than MAX_DRAIN waiting: keep it for the next poll
            ready.add(s);
        }
    }

    @Override
    public void wakeup() {
        ready.offer(WAKE);
    }

    @Override
    public void send(int channel, SocketAddress to, ByteBuffer data) {
        out.send(channel, to, data);
    }

    /** BEHAVIOR: later {@link #offer}s are refused; datagrams already waiting are still delivered. */
    @Override
    public void close() {
        closed = true;
    }

    /** Datagrams dropped because the pool was empty. */
    public long dropped() {
        return dropped.get();
    }
}
