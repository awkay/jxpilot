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
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * A UDP proxy that puts the lossy-link emulator ({@link LinkEmulator}, the harness's own) between real clients and a
 * real server, in real time (docs/latency-fixes.md §7.2). Clients send to the proxy; each client gets its own
 * upstream socket, so the server sees one peer per client (all from the proxy's address: run the server with
 * {@code -maxClientsPerIP 0}). One emulated path per direction, shared by every client. Single threaded.
 */
public final class LagProxy implements AutoCloseable {
    /** Largest datagram carried (the JXPilot protocol's are at most 1200 bytes). */
    static final int MAX_DATAGRAM = 2048;

    /** One client and its upstream socket. */
    static final class Session {
        final SocketAddress client;
        final DatagramChannel upstream;
        long lastActive;
        boolean closed;

        Session(SocketAddress client, DatagramChannel upstream, long now) {
            this.client = client;
            this.upstream = upstream;
            this.lastActive = now;
        }
    }

    private final ProxyArgs args;
    private final InetSocketAddress server;
    private final Selector selector;
    private final DatagramChannel listen;
    private final LinkEmulator link;
    private final Map<SocketAddress, Session> sessions = new HashMap<>();
    private final ByteBuffer buf = ByteBuffer.allocateDirect(MAX_DATAGRAM);
    private final PrintStream log;
    private volatile boolean running = true;
    private long nextStats;
    private long nextExpiry;

    /** Bind the listening socket (the server name is resolved now). */
    public LagProxy(ProxyArgs args, PrintStream log) throws IOException {
        this.args = args;
        this.log = log;
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
        link = new LinkEmulator(System::nanoTime, args.up(), args.down(), args.seed(), LinkEmulator.DEFAULT_SLOTS,
            MAX_DATAGRAM);
    }

    /** The bound listening address (with the real port when 0 was asked for). */
    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) listen.getLocalAddress();
    }

    /** Clients with an upstream socket now. */
    public int sessions() {
        return sessions.size();
    }

    LinkEmulator link() {
        return link;
    }

    /** Relay until {@link #close()}. */
    public void run() throws IOException {
        long now = System.nanoTime();
        nextStats = now + args.statsSeconds() * 1_000_000_000L;
        nextExpiry = now + 1_000_000_000L;
        try {
            while (running) {
                now = System.nanoTime();
                deliver(now);
                long due = link.nextDue();
                long waitNanos = due == Long.MAX_VALUE ? 100_000_000L : due - now;
                if (waitNanos <= 0) {
                    selector.selectNow();
                } else {
                    selector.select(Math.max(1, (waitNanos + 999_999) / 1_000_000));
                }
                now = System.nanoTime();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey k = it.next();
                    it.remove();
                    if (k.channel() == listen) {
                        fromClients(now);
                    } else {
                        fromServer((Session) k.attachment(), now);
                    }
                }
                housekeeping(now);
            }
        } finally {
            for (Session s : sessions.values()) {
                s.upstream.close();
            }
            listen.close();
            selector.close();
        }
    }

    private void fromClients(long now) throws IOException {
        while (true) {
            buf.clear();
            SocketAddress from = listen.receive(buf);
            if (from == null) {
                return;
            }
            buf.flip();
            Session s = sessions.get(from);
            if (s == null) {
                DatagramChannel up = DatagramChannel.open();
                up.connect(server);
                up.configureBlocking(false);
                s = new Session(from, up, now);
                up.register(selector, SelectionKey.OP_READ, s);
                sessions.put(from, s);
                log.println("lagproxy: new client " + from + " -> server via " + up.getLocalAddress());
            }
            s.lastActive = now;
            link.up().send(buf, now, 0, s);
        }
    }

    private void fromServer(Session s, long now) {
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
            link.down().send(buf, now, 0, s);
        }
    }

    private void deliver(long now) {
        while (true) {
            buf.clear();
            if (link.up().receive(buf, now) < 0) {
                break;
            }
            buf.flip();
            Session s = (Session) link.up().lastAddress();
            if (!s.closed) {
                try {
                    s.upstream.write(buf);
                } catch (IOException e) {
                    // the server is not there: lost, as UDP
                }
            }
        }
        while (true) {
            buf.clear();
            if (link.down().receive(buf, now) < 0) {
                break;
            }
            buf.flip();
            Session s = (Session) link.down().lastAddress();
            try {
                listen.send(buf, s.client);
            } catch (IOException e) {
                // the client is gone: lost
            }
        }
    }

    private void housekeeping(long now) throws IOException {
        if (now - nextExpiry >= 0) {
            nextExpiry = now + 1_000_000_000L;
            long idle = args.idleSeconds() * 1_000_000_000L;
            Iterator<Session> it = sessions.values().iterator();
            while (it.hasNext()) {
                Session s = it.next();
                if (now - s.lastActive > idle) {
                    s.closed = true;
                    s.upstream.close();
                    it.remove();
                    log.println("lagproxy: client " + s.client + " idle, dropped");
                }
            }
        }
        if (args.statsSeconds() > 0 && now - nextStats >= 0) {
            nextStats = now + args.statsSeconds() * 1_000_000_000L;
            log.println("lagproxy: clients " + sessions.size() + "  up " + counters(link.up()) + "  down "
                + counters(link.down()));
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
        System.err.println("lagproxy: " + p.localAddress() + " -> " + a.server() + ", seed " + a.seed());
        System.err.println("lagproxy: " + describe("up  ", a.up()));
        System.err.println("lagproxy: " + describe("down", a.down()));
        System.err.println("lagproxy: every client shares this proxy's address: run the server with -maxClientsPerIP 0");
        p.run();
    }
}
