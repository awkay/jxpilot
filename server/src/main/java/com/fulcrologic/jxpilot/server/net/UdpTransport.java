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

import com.fulcrologic.jxpilot.common.net.Protocol;

import java.io.Closeable;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The UDP edge: one non-blocking {@link DatagramChannel} per bound local address, all registered
 * with one {@link Selector}. Channel ids are assigned in increasing order and never reused, so a
 * peer pinned to a closed channel can never be answered from a different address; its sends are
 * dropped until it times out.
 * <p>
 * Receive and send use one preallocated direct buffer and do not allocate, with one JDK exception:
 * {@code DatagramChannel.receive} caches only the most recent sender's address object, so a channel
 * shared by several peers allocates an {@link InetSocketAddress} whenever the sender changes.
 * Must be used from one thread, except {@link #localAddress} and {@link #boundTargets}.
 */
public final class UdpTransport implements Datagrams, Closeable {
    /** Datagrams drained per channel per readiness event, so one flooded channel cannot starve the loop. */
    static final int MAX_DRAIN = 256;
    private static final int SOCKET_BUFFER = 1 << 20;

    /** Receives each inbound datagram; {@code data} is valid only during the call. */
    @FunctionalInterface
    public interface Receiver {
        void onDatagram(int channel, SocketAddress from, ByteBuffer data, long nowNanos);
    }

    private static final class Bound {
        final int id;
        final BindTarget target;
        final DatagramChannel ch;
        final InetSocketAddress local;

        Bound(int id, BindTarget target, DatagramChannel ch, InetSocketAddress local) {
            this.id = id;
            this.target = target;
            this.ch = ch;
            this.local = local;
        }
    }

    private final int port;
    private final NetLog log;
    private final LongSupplier clock;
    private final Selector selector;
    private final ArrayList<Bound> channels = new ArrayList<>();
    private final ByteBuffer rx = ByteBuffer.allocateDirect(Protocol.MAX_PACKET + 1);
    private final Consumer<SelectionKey> onReady = this::drain;
    private volatile List<Bound> snapshot = List.of();
    private Receiver receiver = (c, f, d, n) -> { };
    private long oversized;
    private long ioErrors;

    /**
     * @param port  UDP port for every channel; 0 gives each channel its own ephemeral port (tests)
     * @param clock nanosecond clock stamped on received datagrams
     */
    public UdpTransport(int port, NetLog log, LongSupplier clock) throws IOException {
        this.port = port;
        this.log = log;
        this.clock = clock;
        this.selector = Selector.open();
    }

    public void setReceiver(Receiver r) {
        this.receiver = r;
    }

    /**
     * Open and close channels. Logs every change with {@code address:port (interface)}. A target that
     * fails to bind is logged and skipped.
     *
     * @return the number of channels opened
     */
    public int apply(BindPlan.Diff diff) {
        for (BindTarget t : diff.close()) {
            for (int i = 0; i < channels.size(); i++) {
                Bound b = channels.get(i);
                if (b != null && b.target.equals(t)) {
                    closeQuietly(b.ch);
                    channels.set(i, null);
                    log.log("unbound " + t.describe(b.local.getPort()));
                }
            }
        }
        int opened = 0;
        for (BindTarget t : diff.open()) {
            DatagramChannel ch = null;
            try {
                ch = DatagramChannel.open(t.address() instanceof Inet4Address
                    ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6);
                ch.setOption(StandardSocketOptions.SO_RCVBUF, SOCKET_BUFFER);
                ch.setOption(StandardSocketOptions.SO_SNDBUF, SOCKET_BUFFER);
                ch.configureBlocking(false);
                ch.bind(new InetSocketAddress(t.address(), port));
                InetSocketAddress local = (InetSocketAddress) ch.getLocalAddress();
                Bound b = new Bound(channels.size(), t, ch, local);
                ch.register(selector, SelectionKey.OP_READ, b);
                channels.add(b);
                opened++;
                log.log("listening on " + t.describe(local.getPort()));
            } catch (IOException | RuntimeException e) {
                closeQuietly(ch);
                log.log("cannot bind " + t.describe(port) + ": " + e);
            }
        }
        publish();
        return opened;
    }

    /**
     * Wait up to {@code timeoutNanos} for datagrams and deliver everything that is ready.
     * Sub-millisecond waits park the thread then poll, since the selector counts in milliseconds.
     */
    public void poll(long timeoutNanos) throws IOException {
        long ms = timeoutNanos / 1_000_000L;
        if (ms >= 1) {
            selector.select(onReady, ms);
        } else {
            if (timeoutNanos > 0) {
                LockSupport.parkNanos(timeoutNanos);
            }
            selector.selectNow(onReady);
        }
    }

    private void drain(SelectionKey key) {
        Bound b = (Bound) key.attachment();
        for (int i = 0; i < MAX_DRAIN; i++) {
            rx.clear();
            SocketAddress from;
            try {
                from = b.ch.receive(rx);
            } catch (IOException e) {
                ioErrors++;
                return;
            }
            if (from == null) {
                return;
            }
            rx.flip();
            if (rx.remaining() > Protocol.MAX_PACKET) {
                oversized++;
                continue;
            }
            receiver.onDatagram(b.id, from, rx, clock.getAsLong());
        }
    }

    @Override
    public void send(int channel, SocketAddress to, ByteBuffer data) {
        Bound b = channel < channels.size() ? channels.get(channel) : null;
        if (b == null) {
            return;
        }
        try {
            b.ch.send(data, to);
        } catch (IOException | RuntimeException e) {
            ioErrors++;
        }
    }

    /** Targets currently bound, in channel order. Safe from any thread. */
    public List<BindTarget> boundTargets() {
        List<BindTarget> out = new ArrayList<>();
        for (Bound b : snapshot) {
            out.add(b.target);
        }
        return out;
    }

    /** Actual local address of the channel bound to {@code target}, or null. Safe from any thread. */
    public InetSocketAddress localAddress(BindTarget target) {
        for (Bound b : snapshot) {
            if (b.target.equals(target)) {
                return b.local;
            }
        }
        return null;
    }

    /** Datagrams dropped for exceeding {@link Protocol#MAX_PACKET}. */
    public long oversized() { return oversized; }

    /** Send/receive I/O errors swallowed. */
    public long ioErrors() { return ioErrors; }

    /** Wake a thread blocked in {@link #poll}. */
    public void wakeup() {
        selector.wakeup();
    }

    @Override
    public void close() {
        for (int i = 0; i < channels.size(); i++) {
            Bound b = channels.get(i);
            if (b != null) {
                closeQuietly(b.ch);
                channels.set(i, null);
            }
        }
        publish();
        try {
            selector.close();
        } catch (IOException e) {
            ioErrors++;
        }
    }

    private void publish() {
        List<Bound> live = new ArrayList<>();
        for (Bound b : channels) {
            if (b != null) {
                live.add(b);
            }
        }
        snapshot = List.copyOf(live);
    }

    private static void closeQuietly(DatagramChannel ch) {
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
                // closing a datagram channel has nothing to flush
            }
        }
    }
}
