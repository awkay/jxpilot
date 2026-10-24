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

import com.fulcrologic.jxpilot.common.net.link.LinkDirection;
import com.fulcrologic.jxpilot.common.net.link.LinkEmulator;
import com.fulcrologic.jxpilot.common.net.link.LinkProfile;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A UDP proxy that puts the lossy-link emulator ({@link LinkEmulator}, the harness's own) between real clients and a
 * real server, in real time (docs/latency-fixes.md §7.2). Clients send to the proxy; each client gets its own
 * upstream socket, so the server sees one peer per client (all from the proxy's address: run the server with
 * {@code -maxClientsPerIP 0}), and its own emulated path both ways (its own FIFO queue, bandwidth cap and random
 * stream), as each friend has their own internet path. Single threaded.
 * <p>
 * Timing: the loop sleeps in {@code select} until {@code --spin-ms} before the next delivery and then polls
 * ({@code selectNow} plus {@link Thread#onSpinWait}), because {@code select} wakes on whole milliseconds and the OS
 * adds timer slack. Steady state allocates nothing in the proxy's own code (the JDK's {@code receive} may).
 */
public final class LagProxy implements AutoCloseable {
    /** Largest datagram carried (the JXPilot protocol's are at most 1200 bytes). */
    static final int MAX_DATAGRAM = 2048;
    /** Packets in flight per client and direction. */
    static final int SLOTS = 256;
    /** Most clients at once. */
    static final int MAX_SESSIONS = 64;

    /** One client: its address, upstream socket and emulated path. */
    static final class Session {
        final SocketAddress client;
        final DatagramChannel upstream;
        final LinkEmulator link;
        long lastActive;

        Session(SocketAddress client, DatagramChannel upstream, LinkEmulator link, long now) {
            this.client = client;
            this.upstream = upstream;
            this.link = link;
            this.lastActive = now;
        }
    }

    private final ProxyArgs args;
    private final InetSocketAddress server;
    private final Selector selector;
    private final DatagramChannel listen;
    private final Map<SocketAddress, Session> byAddress = new HashMap<>();
    private final Session[] sessions = new Session[MAX_SESSIONS];
    private int count;
    private long created;
    private final ByteBuffer buf = ByteBuffer.allocateDirect(MAX_DATAGRAM);
    private final PrintStream log;
    private final long spinNanos;
    private final Consumer<SelectionKey> onKey = this::onKey;
    private volatile boolean running = true;
    private long nextStats;
    private long nextExpiry;
    private long now;
    private long refused;

    /** Bind the listening socket (the server name is resolved now). */
    public LagProxy(ProxyArgs args, PrintStream log) throws IOException {
        this.args = args;
        this.log = log;
        this.spinNanos = Math.round(args.spinMs() * 1_000_000);
        this.server = new InetSocketAddress(args.server().getHostString(), args.server().getPort());
        if (server.isUnresolved()) {
            throw new IOException("cannot resolve " + args.server().getHostString());
        }
        InetSocketAddress at = new InetSocketAddress(args.listen().getHostString(), args.listen().getPort());
        selector = Selector.open();
        listen = DatagramChannel.open(at.getAddress() instanceof java.net.Inet6Address
            ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
        listen.bind(at);
        listen.configureBlocking(false);
        listen.register(selector, SelectionKey.OP_READ);
    }

    /** The bound listening address (with the real port when 0 was asked for). */
    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) listen.getLocalAddress();
    }

    /** Clients with an upstream socket now. */
    public int sessions() {
        return count;
    }

    /** Client {@code i} (0 .. {@link #sessions()} - 1) in arrival order, for tests. */
    Session session(int i) {
        return sessions[i];
    }

    /** BEHAVIOR: the seed of the n-th client's path (0 first): the run's seed, then steps of 7919. */
    static long sessionSeed(long seed, long n) {
        return seed + 7919L * n;
    }

    /**
     * BEHAVIOR: how long to sleep in {@code select}, in whole milliseconds, for a delivery {@code waitNanos} away:
     * 0 (poll, do not sleep) within {@code spinNanos} of it; otherwise until {@code spinNanos} before it, rounded
     * down, at least 1 ms. With nothing in flight ({@link Long#MAX_VALUE}) 100 ms.
     */
    static long sleepMillis(long waitNanos, long spinNanos) {
        if (waitNanos == Long.MAX_VALUE) {
            return 100;
        }
        if (waitNanos <= spinNanos) {
            return 0;
        }
        return Math.max(1, (waitNanos - spinNanos) / 1_000_000);
    }

    /** Relay until {@link #close()}. */
    public void run() throws IOException {
        now = System.nanoTime();
        nextStats = now + args.statsSeconds() * 1_000_000_000L;
        nextExpiry = now + 1_000_000_000L;
        try {
            while (running) {
                now = System.nanoTime();
                deliver(now);
                long due = Long.MAX_VALUE;
                for (int i = 0; i < count; i++) {
                    due = Math.min(due, sessions[i].link.nextDue());
                }
                long sleep = sleepMillis(due == Long.MAX_VALUE ? Long.MAX_VALUE : due - now, spinNanos);
                if (sleep == 0) {
                    selector.selectNow(onKey);
                    if (due - System.nanoTime() > 0) {
                        Thread.onSpinWait();
                    }
                } else {
                    selector.select(onKey, sleep);
                }
                housekeeping(System.nanoTime());
            }
        } finally {
            for (int i = 0; i < count; i++) {
                sessions[i].upstream.close();
            }
            listen.close();
            selector.close();
        }
    }

    private void onKey(SelectionKey k) {
        long t = System.nanoTime();
        try {
            if (k.channel() == listen) {
                fromClients(t);
            } else {
                fromServer((Session) k.attachment(), t);
            }
        } catch (IOException e) {
            log.println("lagproxy: " + e);
        }
    }

    private void fromClients(long t) throws IOException {
        while (true) {
            buf.clear();
            SocketAddress from = listen.receive(buf);
            if (from == null) {
                return;
            }
            buf.flip();
            Session s = byAddress.get(from);
            if (s == null) {
                s = open(from, t);
                if (s == null) {
                    continue;
                }
            }
            s.lastActive = t;
            s.link.up().send(buf, t, 0, null);
        }
    }

    private Session open(SocketAddress from, long t) throws IOException {
        if (count == MAX_SESSIONS) {
            refused++;
            return null;
        }
        DatagramChannel up = DatagramChannel.open();
        up.connect(server);
        up.configureBlocking(false);
        LinkEmulator link = new LinkEmulator(System::nanoTime, new LinkProfile().copyFrom(args.up()),
            new LinkProfile().copyFrom(args.down()), sessionSeed(args.seed(), created++), SLOTS, MAX_DATAGRAM);
        Session s = new Session(from, up, link, t);
        up.register(selector, SelectionKey.OP_READ, s);
        byAddress.put(from, s);
        sessions[count++] = s;
        log.println("lagproxy: new client " + from + " -> server via " + up.getLocalAddress());
        return s;
    }

    private void fromServer(Session s, long t) {
        while (true) {
            buf.clear();
            int n;
            try {
                n = s.upstream.read(buf);
            } catch (IOException e) {
                return; // e.g. ICMP port unreachable while the server is down: as a lost datagram
            }
            if (n <= 0) {
                return;
            }
            buf.flip();
            s.link.down().send(buf, t, 0, null);
        }
    }

    private void deliver(long t) {
        for (int i = 0; i < count; i++) {
            Session s = sessions[i];
            while (true) {
                buf.clear();
                if (s.link.up().receive(buf, t) < 0) {
                    break;
                }
                buf.flip();
                try {
                    s.upstream.write(buf);
                } catch (IOException e) {
                    // the server is not there: lost, as UDP
                }
            }
            while (true) {
                buf.clear();
                if (s.link.down().receive(buf, t) < 0) {
                    break;
                }
                buf.flip();
                try {
                    listen.send(buf, s.client);
                } catch (IOException e) {
                    // the client is gone: lost
                }
            }
        }
    }

    private void housekeeping(long t) throws IOException {
        if (t - nextExpiry >= 0) {
            nextExpiry = t + 1_000_000_000L;
            long idle = args.idleSeconds() * 1_000_000_000L;
            for (int i = count - 1; i >= 0; i--) {
                Session s = sessions[i];
                if (t - s.lastActive > idle) {
                    s.upstream.close();
                    byAddress.remove(s.client);
                    sessions[i] = sessions[--count];
                    sessions[count] = null;
                    log.println("lagproxy: client " + s.client + " idle, dropped");
                }
            }
        }
        if (args.statsSeconds() > 0 && t - nextStats >= 0) {
            nextStats = t + args.statsSeconds() * 1_000_000_000L;
            log.println("lagproxy: clients " + count + (refused == 0 ? "" : " (refused " + refused + ")"));
            for (int i = 0; i < count; i++) {
                Session s = sessions[i];
                log.println("lagproxy:   " + s.client + "  up " + counters(s.link.up()) + "  down "
                    + counters(s.link.down()));
            }
        }
    }

    /** BEHAVIOR: "sent S delivered D lost L queue Q dup U reordered R spikes K in flight F". */
    static String counters(LinkDirection d) {
        return "sent " + d.sent() + " delivered " + d.delivered() + " lost " + d.lost() + " queue " + d.queueDrops()
            + " dup " + d.duplicated() + " reordered " + d.reordered() + " spikes " + d.spikes() + " in flight "
            + d.inFlight();
    }

    /** BEHAVIOR: one line per direction: delay, jitter, spikes, loss model, reorder, duplication, rate. */
    static String describe(String name, LinkProfile p) {
        return String.format(Locale.ROOT,
            "%s: delay %.1f ms, jitter %.1f ms, spikes %.3f x %.1f ms (shape %.2f, cap %.1f ms), loss good %.4f bad %.4f"
                + " (g->b %.4f, b->g %.4f), reorder %.3f +%.1f ms, dup %.3f, rate %d B/s (queue %.1f ms)%s",
            name, p.delayNanos / 1e6, p.jitterSigmaNanos / 1e6, p.spikeProbability, p.spikeScaleNanos / 1e6,
            p.spikeShape, p.spikeCapNanos / 1e6, p.lossGood, p.lossBad, p.goodToBad, p.badToGood,
            p.reorderProbability, p.reorderDelayNanos / 1e6, p.duplicateProbability, p.bandwidthBytesPerSecond,
            p.queueLimitNanos / 1e6, p.fifo ? "" : ", no FIFO");
    }

    /** Stop {@link #run()} (from another thread). */
    @Override
    public void close() {
        running = false;
        selector.wakeup();
    }

    public static void main(String[] argv) throws IOException {
        ProxyArgs a;
        try {
            a = ProxyArgs.parse(argv);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(ProxyArgs.USAGE);
            System.exit(2);
            return;
        }
        LagProxy p = new LagProxy(a, System.err);
        System.err.println("lagproxy: " + p.localAddress() + " -> " + a.server() + ", seed " + a.seed()
            + ", spin " + a.spinMs() + " ms, one emulated path per client");
        System.err.println("lagproxy: " + describe("up  ", a.up()));
        System.err.println("lagproxy: " + describe("down", a.down()));
        System.err.println("lagproxy: every client shares this proxy's address: run the server with -maxClientsPerIP 0");
        p.run();
    }
}
