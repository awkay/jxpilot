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
import com.fulcrologic.jxpilot.common.net.InputReader;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.ReliableHandler;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Server connection layer (docs/PROTOCOL.md): sessions, the reliable channel, config transfer,
 * input sequencing, snapshots, timeouts, malformed-packet containment and per-source rate limiting.
 * Pure with respect to I/O and time: datagrams come in through {@link #onDatagram}, go out through
 * {@link Datagrams}, and the clock is a nanosecond value passed in. Single-threaded.
 * Allocation free in steady state (joins, leaves and logging allocate).
 * <p>
 * A peer is identified by (channel, source address) and every reply goes out through the channel
 * it arrived on, so it leaves from the address the client contacted.
 */
public final class ServerNet {
    /** Upper bound on snapshot datagrams per viewer per tick. */
    public static final int MAX_SNAPSHOT_PARTS = 4;
    /** Offset of the snapshot partCount byte within a SNAPSHOT datagram. */
    static final int PART_COUNT_OFFSET = PacketHeader.SIZE + 9;
    /** Offset of the snapshot flags byte within a SNAPSHOT datagram. */
    static final int FLAGS_OFFSET = PacketHeader.SIZE + 10;
    /** Minimum CONNECT body: nonce, team, empty str8 name, empty str16 shape. */
    static final int MIN_CONNECT = 8;
    /** PING body: u32 id, u64 nanos. */
    static final int PING_SIZE = 12;
    /** DISCONNECT is sent this many times (it is unreliable). */
    static final int DISCONNECT_REPEAT = 3;

    private final ServerNetConfig cfg;
    private final GameHost host;
    private final Datagrams out;
    private final NetLog log;
    private final ConfigTransfer.Blob blob;
    private final RateLimiter limiter;

    private final Connection[] active;
    private int activeCount;
    private final Connection[] free;
    private int freeCount;
    @SuppressWarnings("unchecked")
    private HashMap<SocketAddress, Connection>[] byChannel = new HashMap[4];

    private final PacketHeader header = new PacketHeader();
    private final InputReader input = new InputReader();
    private final Msg.Connect connect = new Msg.Connect();
    private final Msg.Accept accept = new Msg.Accept();
    private final Msg.Reject reject = new Msg.Reject();
    private final Msg.Disconnect disconnect = new Msg.Disconnect();
    private final Msg.Ping ping = new Msg.Ping();
    private final Msg.Welcome welcome = new Msg.Welcome();
    private final SnapshotWriter writer = new SnapshotWriter();
    private final ByteBuffer outBuf = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
    private final ByteBuffer[] parts = new ByteBuffer[MAX_SNAPSHOT_PARTS];

    private int tick;
    private int lastSession;
    private long malformed;
    private long rateLimited;
    private long stray;
    private long truncatedSnapshots;

    /** One peer. Pooled: created up front, reset on reuse. */
    final class Connection implements ReliableHandler {
        final ReliableChannel ch = new ReliableChannel();
        int channel;
        SocketAddress addr;
        int session;
        int nonce;
        int playerId;
        long lastHeard;
        ConfigTransfer.Sender sender;
        boolean initialSent;
        boolean snapshotsOn;
        boolean hasInput;
        int lastInputSeq;
        int ackTick;

        @Override
        public void onReliableMessage(int type, ByteBuffer payload) {
            host.clientMessage(playerId, type, payload);
        }
    }

    /**
     * @param blob the config/map transfer, encoded once per map load and shared by all connections
     */
    public ServerNet(ServerNetConfig cfg, GameHost host, Datagrams out, NetLog log, ConfigTransfer.Blob blob) {
        this.cfg = cfg;
        this.host = host;
        this.out = out;
        this.log = log;
        this.blob = blob;
        this.limiter = new RateLimiter(1024, cfg.ratePerSecond, cfg.rateBurst);
        active = new Connection[cfg.maxConnections];
        free = new Connection[cfg.maxConnections];
        for (int i = 0; i < cfg.maxConnections; i++) {
            free[freeCount++] = new Connection();
        }
        for (int i = 0; i < MAX_SNAPSHOT_PARTS; i++) {
            parts[i] = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
        }
        writer.setWorld(cfg.worldWidth, cfg.worldHeight, cfg.edgeWrap);
    }

    // ------------------------------------------------------------------ receiving

    /**
     * Handle one received datagram.
     * <p>
     * BEHAVIOR: drops (counting {@link #rateLimited()}) packets beyond the source IP's rate.
     * BEHAVIOR: drops (counting {@link #malformed()}) packets with a bad header or a body that does not
     * decode; no exception escapes.
     * BEHAVIOR: CONNECT goes to the join logic; other types need a known peer on this channel and its
     * session id, else they are dropped (counting {@link #stray()}).
     * BEHAVIOR: any valid packet from a peer refreshes its timeout and feeds the header ack to its
     * reliable channel; RELIABLE delivers client messages to the host in order; INPUT applies new
     * inputs; PING is answered with PONG; DISCONNECT removes the peer.
     */
    public void onDatagram(int channel, SocketAddress from, ByteBuffer data, long now) {
        int key = sourceKey(from);
        if (!limiter.allow(key, 1, now)) {
            rateLimited++;
            return;
        }
        if (!header.read(data)) {
            malformed++;
            return;
        }
        try {
            HashMap<SocketAddress, Connection> peers = peers(channel);
            Connection c = peers.get(from);
            if (header.type() == PacketType.CONNECT) {
                onConnect(channel, from, key, c, data, now);
                return;
            }
            if (c == null || header.session() != c.session) {
                stray++;
                return;
            }
            c.lastHeard = now;
            c.ch.onAck(header.reliableAck(), now);
            switch (header.type()) {
                case PacketType.RELIABLE -> {
                    if (c.ch.read(data, c) < 0) {
                        malformed++;
                    }
                }
                case PacketType.INPUT -> onInput(c, data);
                case PacketType.PING -> onPing(c, data);
                case PacketType.DISCONNECT -> drop(c, Msg.Disconnect.REASON_QUIT, "quit", false);
                case PacketType.ACK -> { }
                default -> malformed++;
            }
        } catch (RuntimeException e) {
            malformed++;
        }
    }

    private void onConnect(int channel, SocketAddress from, int key, Connection existing, ByteBuffer data, long now) {
        if (data.remaining() < MIN_CONNECT) {
            malformed++;
            return;
        }
        connect.read(data);
        if (existing != null) {
            if (existing.nonce == connect.nonce) {
                existing.lastHeard = now;
                sendAccept(existing);
                return;
            }
            drop(existing, Msg.Disconnect.REASON_QUIT, "reconnected", false);
        }
        if (!limiter.allow(key, cfg.connectCost, now)) {
            rateLimited++;
            return;
        }
        if (connect.name.isEmpty()) {
            sendReject(channel, from, Msg.Reject.REASON_BAD_NAME, "empty player name");
            return;
        }
        if (freeCount == 0) {
            sendReject(channel, from, Msg.Reject.REASON_FULL, "server is full");
            return;
        }
        int id = host.join(connect.name, connect.team, connect.shape);
        if (id < 0) {
            sendReject(channel, from, -id, rejectText(-id));
            return;
        }
        Connection c = free[--freeCount];
        c.ch.reset();
        c.channel = channel;
        c.addr = from;
        c.session = nextSession();
        c.nonce = connect.nonce;
        c.playerId = id;
        c.lastHeard = now;
        c.initialSent = false;
        c.snapshotsOn = false;
        c.hasInput = false;
        c.lastInputSeq = 0;
        c.ackTick = 0;
        c.sender = new ConfigTransfer.Sender(blob);
        active[activeCount++] = c;
        peers(channel).put(from, c);
        sendAccept(c);
        welcome.playerId = id;
        welcome.team = host.team(id);
        welcome.simHz = cfg.simHz;
        welcome.snapshotHz = cfg.simHz / cfg.snapshotDivisor;
        welcome.flags = cfg.welcomeFlags;
        welcome.gameFps = cfg.gameFps;
        blob.fill(welcome);
        welcome.serverName.setLength(0);
        welcome.serverName.append(cfg.serverName);
        welcome.send(c.ch);
        log.log("join: player " + id + " '" + connect.name + "' from " + from + " on channel " + channel);
    }

    private void onInput(Connection c, ByteBuffer data) {
        if (!input.wrap(data)) {
            malformed++;
            return;
        }
        for (int i = input.count() - 1; i >= 0; i--) {
            int s = input.seq(i);
            if (!c.hasInput || s - c.lastInputSeq > 0) {
                host.input(c.playerId, s, input.keys(i), input.aimTurn(i));
                c.lastInputSeq = s;
                c.hasInput = true;
            }
        }
        c.ackTick = input.ackTick();
    }

    private void onPing(Connection c, ByteBuffer data) {
        if (data.remaining() < PING_SIZE) {
            malformed++;
            return;
        }
        ping.read(data);
        outBuf.clear();
        PacketHeader.write(outBuf, PacketType.PONG, c.session, c.ch.ack());
        ping.write(outBuf);
        outBuf.flip();
        out.send(c.channel, c.addr, outBuf);
    }

    // ------------------------------------------------------------------ ticking

    /**
     * Advance one simulation tick and service every peer.
     * <p>
     * BEHAVIOR: increments the tick and runs {@link GameHost#tick} with it.
     * BEHAVIOR: drops peers silent for longer than the timeout, sending DISCONNECT (timeout) 3 times.
     * BEHAVIOR: streams the config until it is all queued, then queues the host's initial state once;
     * afterwards drains the host's events every tick.
     * BEHAVIOR: sends due reliable messages (at most maxReliablePacketsPerTick datagrams).
     * BEHAVIOR: on every snapshotDivisor-th tick sends a snapshot to peers whose reliable channel has
     * drained once after the initial state, with radar on every radarDivisor-th snapshot.
     * BEHAVIOR: sends a bare ACK when reliable data arrived and nothing else went out this tick.
     */
    public void tick(long now) {
        tick++;
        host.tick(tick);
        boolean snapshot = tick % cfg.snapshotDivisor == 0;
        boolean radar = snapshot && (tick / cfg.snapshotDivisor) % cfg.radarDivisor == 0;
        for (int i = activeCount - 1; i >= 0; i--) {
            Connection c = active[i];
            if (now - c.lastHeard > cfg.timeoutNanos) {
                drop(c, Msg.Disconnect.REASON_TIMEOUT, "timeout", true);
                continue;
            }
            service(c, snapshot, radar, now);
        }
    }

    private void service(Connection c, boolean snapshot, boolean radar, long now) {
        if (!c.initialSent) {
            if (c.sender.pump(c.ch, cfg.configReserveSlots)) {
                host.initialState(c.playerId, c.ch);
                c.initialSent = true;
            }
        } else {
            host.drainEvents(c.playerId, c.ch);
            if (!c.snapshotsOn && c.ch.unacked() == 0 && c.ch.overflowing() == 0) {
                c.snapshotsOn = true;
            }
        }
        boolean sent = false;
        for (int k = 0; k < cfg.maxReliablePacketsPerTick && c.ch.wantsSend(now); k++) {
            outBuf.clear();
            PacketHeader.write(outBuf, PacketType.RELIABLE, c.session, c.ch.ack());
            if (c.ch.write(outBuf, now) == 0) {
                break;
            }
            outBuf.flip();
            out.send(c.channel, c.addr, outBuf);
            sent = true;
        }
        if (snapshot && c.snapshotsOn) {
            sendSnapshot(c, radar);
            sent = true;
        }
        if (c.ch.consumeAckPending() && !sent) {
            sendBare(c, PacketType.ACK);
        }
    }

    private void sendSnapshot(Connection c, boolean radar) {
        double vx = host.viewX(c.playerId);
        double vy = host.viewY(c.playerId);
        int n = 0;
        for (int part = 0; part < MAX_SNAPSHOT_PARTS; part++) {
            ByteBuffer b = parts[part];
            b.clear();
            PacketHeader.write(b, PacketType.SNAPSHOT, c.session, c.ch.ack());
            writer.begin(b, tick, c.lastInputSeq, part, 0, 0, vx, vy);
            n = part + 1;
            if (!host.fillSnapshot(c.playerId, part, radar, writer)) {
                break;
            }
            if (part == MAX_SNAPSHOT_PARTS - 1) {
                truncatedSnapshots++;
            }
        }
        for (int p = 0; p < n; p++) {
            ByteBuffer b = parts[p];
            b.put(PART_COUNT_OFFSET, (byte) n);
            b.put(FLAGS_OFFSET, (byte) (p == n - 1 ? SnapshotWriter.FLAG_LAST_PART : 0));
            b.flip();
            out.send(c.channel, c.addr, b);
        }
    }

    // ------------------------------------------------------------------ leaving

    /**
     * Disconnect everyone (server shutting down).
     * <p>
     * BEHAVIOR: each peer gets DISCONNECT (shutdown) 3 times and the host a {@link GameHost#leave}.
     */
    public void shutdown() {
        for (int i = activeCount - 1; i >= 0; i--) {
            drop(active[i], Msg.Disconnect.REASON_SHUTDOWN, "shutdown", true);
        }
    }

    private void drop(Connection c, int reason, String why, boolean notify) {
        if (notify) {
            disconnect.reason = reason;
            disconnect.text.setLength(0);
            disconnect.text.append(why);
            for (int k = 0; k < DISCONNECT_REPEAT; k++) {
                outBuf.clear();
                PacketHeader.write(outBuf, PacketType.DISCONNECT, c.session, c.ch.ack());
                disconnect.write(outBuf);
                outBuf.flip();
                out.send(c.channel, c.addr, outBuf);
            }
        }
        host.leave(c.playerId);
        peers(c.channel).remove(c.addr);
        for (int i = 0; i < activeCount; i++) {
            if (active[i] == c) {
                active[i] = active[--activeCount];
                active[activeCount] = null;
                break;
            }
        }
        c.sender = null;
        c.addr = null;
        free[freeCount++] = c;
        log.log("leave: player " + c.playerId + " (" + why + ")");
    }

    // ------------------------------------------------------------------ helpers

    private void sendAccept(Connection c) {
        outBuf.clear();
        PacketHeader.write(outBuf, PacketType.ACCEPT, c.session, c.ch.ack());
        accept.nonce = c.nonce;
        accept.playerId = c.playerId;
        accept.write(outBuf);
        outBuf.flip();
        out.send(c.channel, c.addr, outBuf);
    }

    private void sendReject(int channel, SocketAddress to, int reason, String text) {
        outBuf.clear();
        PacketHeader.write(outBuf, PacketType.REJECT, 0, 0);
        reject.nonce = connect.nonce;
        reject.reason = reason;
        reject.text.setLength(0);
        reject.text.append(text);
        reject.write(outBuf);
        outBuf.flip();
        out.send(channel, to, outBuf);
    }

    private void sendBare(Connection c, int type) {
        outBuf.clear();
        PacketHeader.write(outBuf, type, c.session, c.ch.ack());
        outBuf.flip();
        out.send(c.channel, c.addr, outBuf);
    }

    /** BEHAVIOR: operator text for a REJECT reason code. */
    static String rejectText(int reason) {
        return switch (reason) {
            case Msg.Reject.REASON_FULL -> "server is full";
            case Msg.Reject.REASON_VERSION -> "incompatible version";
            case Msg.Reject.REASON_BAD_NAME -> "name not accepted";
            default -> "join refused";
        };
    }

    /** BEHAVIOR: session ids count up from 1, wrap at 16 bits and skip 0 (0 means "no session"). */
    int nextSession() {
        lastSession = (lastSession + 1) & 0xFFFF;
        if (lastSession == 0) {
            lastSession = 1;
        }
        return lastSession;
    }

    /** BEHAVIOR: rate-limit key: the source IP's hash (all ports of one host share a bucket). */
    static int sourceKey(SocketAddress from) {
        if (from instanceof InetSocketAddress isa && isa.getAddress() != null) {
            return isa.getAddress().hashCode();
        }
        return from.hashCode();
    }

    private HashMap<SocketAddress, Connection> peers(int channel) {
        if (channel >= byChannel.length) {
            byChannel = Arrays.copyOf(byChannel, Math.max(channel + 1, byChannel.length * 2));
        }
        HashMap<SocketAddress, Connection> m = byChannel[channel];
        if (m == null) {
            m = new HashMap<>();
            byChannel[channel] = m;
        }
        return m;
    }

    // ------------------------------------------------------------------ inspection

    /** Ticks run so far (the last tick number). */
    public int currentTick() { return tick; }

    /** Connected peers. */
    public int connections() { return activeCount; }

    /** Packets dropped as malformed. */
    public long malformed() { return malformed; }

    /** Packets dropped by the per-source rate limit. */
    public long rateLimited() { return rateLimited; }

    /** Non-CONNECT packets from unknown peers or with a wrong session. */
    public long stray() { return stray; }

    /** Snapshots cut short at {@link #MAX_SNAPSHOT_PARTS}. */
    public long truncatedSnapshots() { return truncatedSnapshots; }

    /** Session id of the peer at {@code from} on {@code channel}, or -1. */
    public int sessionOf(int channel, SocketAddress from) {
        Connection c = peers(channel).get(from);
        return c == null ? -1 : c.session;
    }
}
