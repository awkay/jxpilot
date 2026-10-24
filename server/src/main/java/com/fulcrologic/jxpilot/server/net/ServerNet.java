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

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.net.ConfigTransfer;
import com.fulcrologic.jxpilot.common.net.InputReader;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.ReliableHandler;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.common.net.Wire;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.SplittableRandom;

/**
 * Server connection layer (docs/PROTOCOL.md): sessions, the reliable channel, config transfer,
 * input sequencing, snapshots, timeouts, malformed-packet containment and rate limiting.
 * Pure with respect to I/O and time: datagrams come in through {@link #onDatagram}, go out through
 * {@link Datagrams}, and the clock is a nanosecond value passed in. Single-threaded.
 * Allocation free in steady state (joins, leaves and logging allocate).
 * <p>
 * A peer is identified by (channel, source address) and every reply goes out through the channel
 * it arrived on, so it leaves from the address the client contacted.
 * <p>
 * Anti-reflection: a new session is <em>unverified</em> until a packet arrives carrying the random
 * session id sent in ACCEPT, proving the client receives at its source address. Until then it gets
 * only a few ACCEPTs (each smaller than a CONNECT) and no other traffic, and it expires after
 * {@link ServerNetConfig#acceptResends} backed-off resends. No reply is ever larger than the CONNECT
 * that caused it.
 * <p>
 * Rate limits: packets that belong to a session are limited per session; everything else (CONNECT,
 * strays, garbage) per source (IPv4 address or IPv6 /64, secret-keyed), so many players behind one
 * NAT are not throttled by each other while spoofed floods still hit a per-source bucket.
 */
public final class ServerNet {
    /** Upper bound on snapshot datagrams per viewer per tick. */
    public static final int MAX_SNAPSHOT_PARTS = 4;
    /** Offset of the snapshot partCount byte within a SNAPSHOT datagram. */
    static final int PART_COUNT_OFFSET = PacketHeader.SIZE + 9;
    /** Offset of the snapshot flags byte within a SNAPSHOT datagram. */
    static final int FLAGS_OFFSET = PacketHeader.SIZE + 10;
    /** PING body: u32 id, u64 nanos. */
    static final int PING_SIZE = 12;
    /** DISCONNECT is sent this many times (it is unreliable). */
    static final int DISCONNECT_REPEAT = 3;
    /** REJECT bytes before its text: header, u32 nonce, u8 reason, u8 text length. */
    static final int REJECT_OVERHEAD = PacketHeader.SIZE + 6;
    /** Limiter table size for per-source buckets. */
    static final int SOURCE_SLOTS = 4096;

    private final ServerNetConfig cfg;
    private final GameHost host;
    private final Datagrams out;
    private final NetLog log;
    private final ConfigTransfer.Blob blob;
    private final RateLimiter limiter;
    private final SourceKeys keys;
    private final SplittableRandom sessions;
    private final double sessionPerNano;
    private final int reliablePerTick;

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
    private boolean shutdownDue;
    private long malformed;
    private long rateLimited;
    private long stray;
    private long denied;
    private long truncatedSnapshots;
    private long versionRejects;

    /** One peer. Pooled: created up front, reset on reuse. */
    final class Connection implements ReliableHandler {
        final ReliableChannel ch = new ReliableChannel();
        final StringBuilder name = new StringBuilder();
        int channel;
        SocketAddress addr;
        long sourceKey;
        int session;
        int nonce;
        int playerId;
        boolean verified;
        int acceptSends;
        long nextAcceptAt;
        long lastHeard;
        double tokens;
        long tokensAt;
        ConfigTransfer.Sender sender;
        boolean initialSent;
        int syncSequence;
        boolean snapshotsOn;
        boolean hasInput;
        int lastInputSeq;

        /** A host that throws on a message must not wedge the channel: the message counts as malformed. */
        @Override
        public void onReliableMessage(int type, ByteBuffer payload) {
            try {
                host.clientMessage(playerId, type, payload);
            } catch (RuntimeException e) {
                malformed++;
            }
        }

        /** Spend one token of this session's bucket. */
        boolean allow(long now) {
            tokens = Math.min(cfg.sessionRateBurst, tokens + (now - tokensAt) * sessionPerNano);
            tokensAt = now;
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }
    }

    /**
     * Production constructor: session ids and the source-key secret come from {@link SecureRandom}.
     *
     * @param blob the config/map transfer, encoded once per map load and shared by all connections
     */
    public ServerNet(ServerNetConfig cfg, GameHost host, Datagrams out, NetLog log, ConfigTransfer.Blob blob) {
        this(cfg, host, out, log, blob, new SecureRandom().nextLong(), new SecureRandom().nextLong());
    }

    /**
     * @param sessionSeed seeds the session id generator (a fast PRNG, so CONNECT never allocates)
     * @param keySecret   secret of the per-source keys
     */
    ServerNet(ServerNetConfig cfg, GameHost host, Datagrams out, NetLog log, ConfigTransfer.Blob blob,
              long sessionSeed, long keySecret) {
        this.cfg = cfg;
        this.host = host;
        this.out = out;
        this.log = log;
        this.blob = blob;
        this.limiter = new RateLimiter(SOURCE_SLOTS, cfg.ratePerSecond, cfg.rateBurst);
        this.keys = new SourceKeys(keySecret);
        this.sessions = new SplittableRandom(sessionSeed);
        this.sessionPerNano = cfg.sessionRatePerSecond / 1e9;
        this.reliablePerTick = cfg.reliablePacketsPerTick();
        active = new Connection[cfg.maxConnections];
        free = new Connection[cfg.maxConnections];
        for (int i = 0; i < cfg.maxConnections; i++) {
            free[freeCount++] = new Connection();
        }
        for (int i = 0; i < MAX_SNAPSHOT_PARTS; i++) {
            parts[i] = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
        }
        writer.setWorld(cfg.worldWidth, cfg.worldHeight, cfg.edgeWrap);
        host.attach(new GameHost.Sessions() {
            @Override
            public void kick(int playerId, CharSequence reason) {
                ServerNet.this.kick(playerId, reason);
            }

            @Override
            public void optionChanged(Opt o, Options options) {
                ServerNet.this.optionChanged(o, options);
            }
        });
    }

    /**
     * server-commands: an operator changed an option at run time ({@link GameHost.Sessions#optionChanged}).
     * <p>
     * BEHAVIOR: denyHosts (C tuner {@code Set_deny_hosts}) and maxClientsPerIP take their new values for the
     * next CONNECTs; other options are not the network layer's.
     */
    void optionChanged(Opt o, Options options) {
        if (o == Opt.DENY_HOSTS) {
            cfg.denyHosts = DenyList.parse(options.getString(Opt.DENY_HOSTS));
        } else if (o == Opt.MAX_CLIENTS_PER_IP) {
            cfg.maxPerIp = options.getInt(Opt.MAX_CLIENTS_PER_IP);
        }
    }

    /**
     * The host removed a player on its own ({@link GameHost.Sessions#kick}).
     * <p>
     * BEHAVIOR: the connection of {@code playerId} gets DISCONNECT (kicked, {@code reason}) 3 times if verified
     * and is dropped (the host gets {@link GameHost#leave}); without such a connection nothing happens.
     */
    void kick(int playerId, CharSequence reason) {
        for (int i = 0; i < activeCount; i++) {
            Connection c = active[i];
            if (c.playerId == playerId) {
                drop(c, Msg.Disconnect.REASON_KICKED, reason, true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ receiving

    /**
     * Handle one received datagram.
     * <p>
     * BEHAVIOR: a packet carrying a known peer's session id (other than CONNECT) is charged to that
     * session's bucket; anything else to its source's bucket. Over the limit it is dropped, counting
     * {@link #rateLimited()}.
     * BEHAVIOR: a CONNECT with the right magic but another protocol version is counted in
     * {@link #versionRejects()} and, if at least as large as a bare REJECT, gets REJECT VERSION; any other
     * bad header is dropped as {@link #malformed()}.
     * BEHAVIOR: bodies that do not decode are dropped as malformed; no exception escapes.
     * BEHAVIOR: a packet from a source in denyHosts that is not a known peer is ignored first of all, counting
     * {@link #denied()} (no REJECT, no rate-limit charge).
     * BEHAVIOR: CONNECT goes to the join logic; other types need a known peer on this channel and its
     * session id, else they are dropped (counting {@link #stray()}).
     * BEHAVIOR: the first packet with the session id verifies an unverified session and queues WELCOME.
     * BEHAVIOR: any valid packet from a peer refreshes its timeout and feeds the header ack to its
     * reliable channel; RELIABLE delivers client messages to the host in order (a message the host
     * throws on counts as malformed and is skipped); INPUT applies new inputs (one the host throws on
     * counts as malformed and is skipped, not retried from redundancy); PING is answered with PONG;
     * DISCONNECT removes the peer.
     */
    public void onDatagram(int channel, SocketAddress from, ByteBuffer data, long now) {
        int start = data.position();
        boolean valid = header.read(data);
        Connection c = valid ? peers(channel).get(from) : null;
        if (c == null && cfg.denyHosts.denied(from)) {
            denied++; // C contact.c:330 checks the address before anything else
            return;
        }
        boolean inSession = c != null && header.type() != PacketType.CONNECT && header.session() == c.session;
        if (inSession ? !c.allow(now) : !limiter.allow(keys.of(from), 1, now)) {
            rateLimited++;
            return;
        }
        if (!valid) {
            if (isOtherVersionConnect(data)) {
                versionRejects++;
                if (data.remaining() >= REJECT_OVERHEAD) {
                    sendReject(channel, from, data.getInt(start + PacketHeader.SIZE), Msg.Reject.REASON_VERSION,
                        rejectText(Msg.Reject.REASON_VERSION), data.remaining());
                }
            } else {
                malformed++;
            }
            return;
        }
        try {
            if (header.type() == PacketType.CONNECT) {
                onConnect(channel, from, c, data, data.limit() - start, now);
                return;
            }
            if (!inSession) {
                stray++;
                return;
            }
            if (!c.verified) {
                verify(c);
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

    /** BEHAVIOR: true for a datagram with the JX magic, type CONNECT and a protocol version other than ours. */
    static boolean isOtherVersionConnect(ByteBuffer d) {
        int p = d.position();
        return d.remaining() >= PacketHeader.SIZE
            && (d.getShort(p) & 0xFFFF) == Protocol.MAGIC
            && (d.get(p + 2) & 0xFF) != Protocol.VERSION
            && (d.get(p + 3) & 0xFF) == PacketType.CONNECT;
    }

    private void onConnect(int channel, SocketAddress from, Connection existing, ByteBuffer data, int size, long now) {
        if (!connect.tryRead(data)) {
            malformed++;
            return;
        }
        if (existing != null && existing.nonce == connect.nonce) {
            sendAccept(existing);
            return;
        }
        long source = keys.of(from);
        if (!limiter.allow(source, cfg.connectCost, now)) {
            rateLimited++;
            return;
        }
        if (connect.name.isEmpty()) {
            sendReject(channel, from, connect.nonce, Msg.Reject.REASON_BAD_NAME, "empty player name", size);
            return;
        }
        if (freeCount == 0 && existing == null) {
            sendReject(channel, from, connect.nonce, Msg.Reject.REASON_FULL, "server is full", size);
            return;
        }
        if (cfg.maxPerIp > 0 && sessionsFrom(source, existing) >= cfg.maxPerIp) {
            // C Check_max_clients_per_IP answers E_GAME_LOCKED (contact.c:743-751); the lock itself is the host's,
            // checked in join after a queued player's own request (C Queue_player keeps its place when locked)
            sendReject(channel, from, connect.nonce, Msg.Reject.REASON_LOCKED, "too many players from your address", size);
            return;
        }
        int id = host.join(connect.name, connect.team, connect.shape, source);
        if (id == -Msg.Reject.REASON_QUEUED) {
            int pos = host.queuePosition();
            reject.position = pos;
            sendReject(channel, from, connect.nonce, Msg.Reject.REASON_QUEUED, "queued at position " + pos, size);
            return;
        }
        if (id < 0) {
            sendReject(channel, from, connect.nonce, -id, rejectText(-id), size);
            return;
        }
        if (existing != null && existing.addr != null) {
            drop(existing, Msg.Disconnect.REASON_QUIT, "reconnected", false);
        }
        Connection c = free[--freeCount];
        c.channel = channel;
        c.addr = from;
        c.sourceKey = source;
        c.session = newSession();
        c.nonce = connect.nonce;
        c.playerId = id;
        c.name.setLength(0);
        c.name.append(connect.name);
        c.verified = false;
        c.acceptSends = 1;
        c.nextAcceptAt = now + cfg.acceptResendNanos;
        c.lastHeard = now;
        c.tokens = cfg.sessionRateBurst;
        c.tokensAt = now;
        c.sender = null;
        c.initialSent = false;
        c.snapshotsOn = false;
        c.hasInput = false;
        c.lastInputSeq = 0;
        active[activeCount++] = c;
        peers(channel).put(from, c);
        sendAccept(c);
    }

    /** The client proved it receives at its address: start the session proper. */
    private void verify(Connection c) {
        c.verified = true;
        c.sender = new ConfigTransfer.Sender(blob);
        welcome.playerId = c.playerId;
        welcome.team = host.team(c.playerId);
        welcome.simHz = cfg.simHz;
        welcome.snapshotHz = cfg.simHz / cfg.snapshotDivisor;
        welcome.flags = cfg.welcomeFlags;
        welcome.gameFps = cfg.gameFps;
        blob.fill(welcome);
        welcome.serverName.setLength(0);
        welcome.serverName.append(cfg.serverName);
        welcome.send(c.ch);
        log.log("join: player " + c.playerId + " '" + c.name + "' from " + c.addr + " on channel " + c.channel);
    }

    private int sessionsFrom(long source, Connection except) {
        int n = 0;
        for (int i = 0; i < activeCount; i++) {
            Connection c = active[i];
            if (c != except && c.sourceKey == source) {
                n++;
            }
        }
        return n;
    }

    private void onInput(Connection c, ByteBuffer data) {
        if (!input.wrap(data)) {
            malformed++;
            return;
        }
        for (int i = input.count() - 1; i >= 0; i--) {
            int s = input.seq(i);
            if (!c.hasInput || s - c.lastInputSeq > 0) {
                c.lastInputSeq = s;
                c.hasInput = true;
                try {
                    host.input(c.playerId, s, input.keys(i), input.aimTurn(i));
                } catch (RuntimeException e) {
                    malformed++;
                }
            }
        }
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
     * BEHAVIOR: increments the tick and runs {@link GameHost#tick} with it; when the host then wants the server
     * to stop ({@link GameHost#shutdownDue}) nothing else happens this tick and {@link #shutdownDue} is true.
     * BEHAVIOR: an unverified session gets ACCEPT again when its resend time comes, with the interval
     * doubling each time, at most acceptResends times; one doubled interval after the last it is dropped
     * silently (the host gets {@link GameHost#leave}). It gets nothing else.
     * BEHAVIOR: drops verified peers, sending DISCONNECT (timeout) 3 times, that were silent for longer
     * than the timeout, whose oldest unacknowledged reliable message is older than the timeout, or whose
     * reliable overflow exceeds maxOverflow (they receive but do not acknowledge).
     * BEHAVIOR: streams the config until it is all queued, then queues the host's initial state once;
     * afterwards drains the host's events every tick.
     * BEHAVIOR: sends due reliable messages (at most {@link ServerNetConfig#reliablePacketsPerTick()} datagrams).
     * BEHAVIOR: on every snapshotDivisor-th tick sends a snapshot to peers that have acknowledged
     * everything up to and including their initial state (later events need not be acked), with radar
     * on every radarDivisor-th snapshot.
     * BEHAVIOR: sends a bare ACK when reliable data arrived and nothing else went out this tick.
     */
    public void tick(long now) {
        tick++;
        host.tick(tick);
        if (host.shutdownDue()) {
            shutdownDue = true;
            return;
        }
        boolean snapshot = tick % cfg.snapshotDivisor == 0;
        boolean radar = snapshot && (tick / cfg.snapshotDivisor) % cfg.radarDivisor == 0;
        for (int i = activeCount - 1; i >= 0; i--) {
            Connection c = active[i];
            if (!c.verified) {
                resendAccept(c, now);
            } else if (now - c.lastHeard > cfg.timeoutNanos) {
                drop(c, Msg.Disconnect.REASON_TIMEOUT, "timeout", true);
            } else if (c.ch.unackedAgeNanos(now) > cfg.timeoutNanos) {
                drop(c, Msg.Disconnect.REASON_TIMEOUT, "reliable data unacknowledged", true);
            } else if (c.ch.overflowing() > cfg.maxOverflow) {
                drop(c, Msg.Disconnect.REASON_TIMEOUT, "reliable backlog", true);
            } else {
                service(c, snapshot, radar, now);
            }
        }
    }

    private void resendAccept(Connection c, long now) {
        if (now - c.nextAcceptAt < 0) {
            return;
        }
        if (c.acceptSends > cfg.acceptResends) {
            drop(c, Msg.Disconnect.REASON_TIMEOUT, "unverified", false);
            return;
        }
        sendAccept(c);
        c.nextAcceptAt = now + (cfg.acceptResendNanos << c.acceptSends);
        c.acceptSends++;
    }

    private void service(Connection c, boolean snapshot, boolean radar, long now) {
        if (!c.initialSent) {
            if (c.sender.pump(c.ch, cfg.configReserveSlots)) {
                host.initialState(c.playerId, c.ch);
                c.initialSent = true;
                c.syncSequence = c.ch.nextSequence();
            }
        } else {
            host.drainEvents(c.playerId, c.ch);
            if (!c.snapshotsOn && c.ch.allAckedBefore(c.syncSequence)) {
                c.snapshotsOn = true;
            }
        }
        boolean sent = false;
        for (int k = 0; k < reliablePerTick && c.ch.wantsSend(now); k++) {
            outBuf.clear();
            PacketHeader.write(outBuf, PacketType.RELIABLE, c.session, c.ch.ack());
            if (c.ch.write(outBuf, now) == 0) {
                break;
            }
            outBuf.flip();
            out.send(c.channel, c.addr, outBuf);
            sent = true;
        }
        if (snapshot && c.snapshotsOn && host.snapshotDue(c.playerId)) {
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

    /** BEHAVIOR: true once the host asked for the server to stop; ticks then do nothing more. */
    public boolean shutdownDue() {
        return shutdownDue;
    }

    // ------------------------------------------------------------------ leaving

    /**
     * Disconnect everyone (server shutting down).
     * <p>
     * BEHAVIOR: each verified peer gets DISCONNECT (shutdown) 3 times, unverified ones nothing; every
     * player gets a {@link GameHost#leave}.
     */
    public void shutdown() {
        for (int i = activeCount - 1; i >= 0; i--) {
            drop(active[i], Msg.Disconnect.REASON_SHUTDOWN, "shutdown", true);
        }
    }

    /**
     * Remove a peer. DISCONNECT goes only to verified peers (never reflect to an unproven address); the
     * reliable channel is reset at once so a peer dropped for a backlog releases its overflow memory.
     */
    private void drop(Connection c, int reason, CharSequence why, boolean notify) {
        if (notify && c.verified) {
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
        c.ch.reset();
        c.sender = null;
        c.addr = null;
        free[freeCount++] = c;
        if (c.verified) {
            log.log("leave: player " + c.playerId + " (" + why + ")");
        }
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

    /**
     * REJECT with session 0, its text cut so the reply is never larger than the request ({@code maxBytes},
     * at least {@link #REJECT_OVERHEAD}: a parsed CONNECT always is, an unparsed one is checked first).
     */
    private void sendReject(int channel, SocketAddress to, int nonce, int reason, String text, int maxBytes) {
        outBuf.clear();
        PacketHeader.write(outBuf, PacketType.REJECT, 0, 0);
        reject.nonce = nonce;
        reject.reason = reason;
        reject.text.setLength(0);
        reject.text.append(text);
        Wire.cap(reject.text, maxBytes - REJECT_OVERHEAD - (reason == Msg.Reject.REASON_QUEUED ? 2 : 0));
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
            case Msg.Reject.REASON_LOCKED -> "game locked";
            case Msg.Reject.REASON_QUEUED -> "queued";
            default -> "join refused";
        };
    }

    /**
     * BEHAVIOR: a random 16-bit session id that is not 0 ("no session") and not used by any active peer.
     */
    int newSession() {
        while (true) {
            int s = sessions.nextInt() & 0xFFFF;
            if (s != 0 && !sessionInUse(s)) {
                return s;
            }
        }
    }

    private boolean sessionInUse(int s) {
        for (int i = 0; i < activeCount; i++) {
            if (active[i].session == s) {
                return true;
            }
        }
        return false;
    }

    /** Per-source key of an address (see {@link SourceKeys}). */
    long sourceKey(SocketAddress from) {
        return keys.of(from);
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

    /** Connected peers, verified or not. */
    public int connections() { return activeCount; }

    /** {@link GameHost#humanEntered()} of the hosted game. */
    public boolean humanEntered() { return host.humanEntered(); }

    /** Packets dropped as malformed (including client messages the host could not decode). */
    public long malformed() { return malformed; }

    /** Packets dropped by the per-source or per-session rate limit. */
    public long rateLimited() { return rateLimited; }

    /** Non-CONNECT packets from unknown peers or with a wrong session. */
    public long stray() { return stray; }


    /** Packets outside a session ignored because their source is in denyHosts (C contact.c Check_address). */
    public long denied() { return denied; }

    /** Snapshots cut short at {@link #MAX_SNAPSHOT_PARTS}. */
    public long truncatedSnapshots() { return truncatedSnapshots; }

    /** CONNECTs of another protocol version answered with REJECT VERSION. */
    public long versionRejects() { return versionRejects; }

    /** Session id of the peer at {@code from} on {@code channel}, or -1. */
    public int sessionOf(int channel, SocketAddress from) {
        Connection c = peers(channel).get(from);
        return c == null ? -1 : c.session;
    }

    /** Whether the peer at {@code from} on {@code channel} exists and has verified its session. */
    public boolean isVerified(int channel, SocketAddress from) {
        Connection c = peers(channel).get(from);
        return c != null && c.verified;
    }
}
