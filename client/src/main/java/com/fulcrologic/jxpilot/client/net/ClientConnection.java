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
package com.fulcrologic.jxpilot.client.net;

import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.ConfigTransfer;
import com.fulcrologic.jxpilot.common.net.InputHistory;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.ReliableHandler;
import com.fulcrologic.jxpilot.common.net.ReliableType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/**
 * Client side of the JXPilot protocol (docs/PROTOCOL.md) over a {@link Transport}: the
 * CONNECT / ACCEPT / reliable WELCOME handshake, config (map) transfer, the reliable channel, INPUT
 * with redundancy, timeouts and reconnects. Decoded data goes to a {@link ConnectionListener}.
 * <p>
 * Time is passed in (nanoseconds, any monotonic origin) so the class is deterministic under test.
 * Single threaded. Per client frame call, in order: {@link #update(long)} (receive and timers),
 * {@link #sendInput(long, int, long)}, then {@link #flush(long)} (reliable resends, idle ACK).
 * Allocation free while {@link ConnectionState#PLAYING}; the handshake and map build allocate.
 */
public final class ClientConnection {
    /** INPUT entries per packet (PROTOCOL.md: "the last 4 inputs"). */
    public static final int INPUT_REDUNDANCY = 4;
    /** Upper bound of datagrams processed per {@link #update} call. */
    public static final int MAX_RECEIVE_PER_UPDATE = 256;
    /** Upper bound of RELIABLE datagrams sent per {@link #flush} call. */
    public static final int MAX_RELIABLE_PER_FLUSH = 8;
    /** Number of DISCONNECT copies sent on quit (PROTOCOL.md). */
    public static final int DISCONNECT_COPIES = 3;

    private static final long CONNECT_RESEND_NANOS = Protocol.CONNECT_RESEND_MS * 1_000_000L;
    private static final long TIMEOUT_NANOS = Protocol.TIMEOUT_MS * 1_000_000L;

    private final Transport transport;
    private final ConnectionListener listener;
    private final String serverLabel;
    private final Rng rng;
    private final ByteBuffer tx = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
    private final ByteBuffer rx = ByteBuffer.allocateDirect(2048);
    private final PacketHeader header = new PacketHeader();
    private final ReliableChannel channel = new ReliableChannel();
    private final InputHistory input = new InputHistory();
    private final Msg.Accept accept = new Msg.Accept();
    private final Msg.Reject reject = new Msg.Reject();
    private final Msg.Disconnect disconnectMsg = new Msg.Disconnect();
    private final Msg.Welcome welcome = new Msg.Welcome();
    private final StringBuilder status = new StringBuilder(128);
    private final ReliableHandler rxHandler = this::onReliable;

    /** CONNECT body; set name, team and shape before {@link #start(long)}. */
    public final Msg.Connect connect = new Msg.Connect();
    /** Reconnect automatically when an established session times out (default true). */
    private boolean autoReconnect = true;

    private ConnectionState state = ConnectionState.IDLE;
    private int session;
    private int playerId = Protocol.NO_ID;
    private long lastReceive;
    private long lastConnectSend;
    private int attempts;
    private boolean sentThisFrame;
    private ConfigTransfer.Receiver config;
    private boolean haveTick;
    private int latestTick;
    private long now;

    private long packetsReceived;
    private long packetsSent;
    private long malformed;
    private long snapshots;
    private int reconnects;

    /**
     * @param serverLabel shown in status lines, e.g. {@code host:port}
     * @param nonceSeed   seeds the CONNECT nonce generator
     */
    public ClientConnection(Transport transport, ConnectionListener listener, String serverLabel, int nonceSeed) {
        this.transport = transport;
        this.listener = listener;
        this.serverLabel = serverLabel;
        this.rng = new Rng(nonceSeed);
    }

    // ---------------------------------------------------------------- accessors

    public ConnectionState state() { return state; }

    /** Player id from ACCEPT, {@link Protocol#NO_ID} before. */
    public int playerId() { return playerId; }

    /** Session id from the ACCEPT header (0 before). */
    public int session() { return session; }

    /** The last status line (connecting, lost, rejected...). */
    public CharSequence status() { return status; }

    /** Newest snapshot tick received (the INPUT ackTick). */
    public int latestTick() { return latestTick; }

    /** Client-to-server reliable channel: queue CHAT/SETTINGS/... with {@code Msg.*.send(channel())}. */
    public ReliableChannel channel() { return channel; }

    /** The INPUT history (sequence numbers). */
    public InputHistory input() { return input; }

    /** CONNECT datagrams sent in the current attempt cycle. */
    public int attempts() { return attempts; }

    public long packetsReceived() { return packetsReceived; }

    public long packetsSent() { return packetsSent; }

    /** Datagrams dropped as malformed (bad header, truncated body, bad reliable framing). */
    public long malformed() { return malformed; }

    public long snapshotsReceived() { return snapshots; }

    /** Times an established session was lost and the connection restarted. */
    public int reconnects() { return reconnects; }

    public void setAutoReconnect(boolean on) { autoReconnect = on; }

    // ---------------------------------------------------------------- life cycle

    /**
     * Start (or restart) the handshake.
     * <p>
     * BEHAVIOR: picks a fresh nonce, resets session, reliable channel and tick tracking, enters
     * CONNECTING and sends the first CONNECT at once.
     */
    public void start(long nowNanos) {
        now = nowNanos;
        resetSession(nowNanos);
        attempts = 0;
        sendConnect(nowNanos);
        setState(ConnectionState.CONNECTING);
    }

    private void resetSession(long nowNanos) {
        connect.nonce = rng.nextInt();
        session = 0;
        playerId = Protocol.NO_ID;
        channel.reset();
        config = null;
        haveTick = false;
        latestTick = 0;
        lastReceive = nowNanos;
    }

    /**
     * Leave: send DISCONNECT {@link #DISCONNECT_COPIES} times (when a session exists) and close.
     * <p>
     * BEHAVIOR: no packets when not yet accepted; state becomes CLOSED either way.
     */
    public void disconnect(long nowNanos) {
        now = nowNanos;
        sendDisconnectCopies();
        status.setLength(0);
        status.append("Disconnected from ").append(serverLabel);
        setState(ConnectionState.CLOSED);
    }

    private void sendDisconnectCopies() {
        if (state == ConnectionState.JOINING || state == ConnectionState.PLAYING) {
            disconnectMsg.reason = Msg.Disconnect.REASON_QUIT;
            disconnectMsg.text.setLength(0);
            disconnectMsg.text.append("quit");
            for (int i = 0; i < DISCONNECT_COPIES; i++) {
                begin(PacketType.DISCONNECT);
                disconnectMsg.write(tx);
                transmit();
            }
        }
    }

    // ---------------------------------------------------------------- per frame

    /**
     * Receive and handle all waiting datagrams, then run the timers.
     * <p>
     * BEHAVIOR: datagrams with a bad header are counted as malformed and dropped.
     * BEHAVIOR: once a session exists, datagrams for another session are ignored, and every accepted
     * datagram refreshes the timeout and acknowledges reliable messages via its header.
     * BEHAVIOR: CONNECT is resent every {@link Protocol#CONNECT_RESEND_MS} ms while CONNECTING.
     * BEHAVIOR: after {@link Protocol#TIMEOUT_MS} ms of silence an established session is lost: it
     * reconnects when auto-reconnect is on (status says so), else CLOSED. While CONNECTING the attempt
     * cycle restarts (auto-reconnect) or the connection closes ("no answer").
     */
    public void update(long nowNanos) {
        now = nowNanos;
        sentThisFrame = false;
        if (state == ConnectionState.IDLE || state == ConnectionState.CLOSED) {
            return;
        }
        for (int i = 0; i < MAX_RECEIVE_PER_UPDATE && state != ConnectionState.CLOSED; i++) {
            rx.clear();
            int n;
            try {
                n = transport.receive(rx);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (n <= 0) {
                break;
            }
            rx.flip();
            handlePacket(rx);
        }
        timers(nowNanos);
    }

    private void timers(long nowNanos) {
        if (state == ConnectionState.CLOSED) {
            return;
        }
        if (nowNanos - lastReceive >= TIMEOUT_NANOS) {
            onTimeout(nowNanos);
            return;
        }
        if (state == ConnectionState.CONNECTING && nowNanos - lastConnectSend >= CONNECT_RESEND_NANOS) {
            sendConnect(nowNanos);
        }
    }

    private void onTimeout(long nowNanos) {
        boolean established = state != ConnectionState.CONNECTING;
        if (autoReconnect) {
            if (established) {
                reconnects++;
                attempts = 0;
            }
            resetSession(nowNanos);
            sendConnect(nowNanos);
            status.setLength(0);
            status.append(established ? "Connection to " : "No answer from ").append(serverLabel)
                .append(established ? " lost (timeout), reconnecting..." : ", still trying...");
            state = ConnectionState.CONNECTING;
            listener.onStateChange(state, status);
        } else {
            status.setLength(0);
            status.append(established ? "Connection to " : "No answer from ").append(serverLabel)
                .append(established ? " lost (timeout)" : "");
            setState(ConnectionState.CLOSED);
        }
    }

    /**
     * Record this frame's input and send an INPUT datagram with the last {@link #INPUT_REDUNDANCY}
     * inputs.
     * <p>
     * BEHAVIOR: only while PLAYING (before that the server has not spawned the player); returns the
     * input sequence or 0 when nothing was sent.
     *
     * @param keys    {@link com.fulcrologic.jxpilot.common.net.ControlKey} mask
     * @param aimTurn pointer-control turn in u16 angle units (0 = none)
     */
    public int sendInput(long keys, int aimTurn, long nowNanos) {
        now = nowNanos;
        if (state != ConnectionState.PLAYING) {
            return 0;
        }
        int seq = input.record(keys, aimTurn);
        begin(PacketType.INPUT);
        input.write(tx, latestTick, INPUT_REDUNDANCY);
        transmit();
        return seq;
    }

    /**
     * Send reliable messages that are new or due for resend; send a header-only ACK if reliable data
     * arrived and no datagram went out since (the ack rides in every header).
     * <p>
     * BEHAVIOR: nothing is sent unless a session exists (JOINING or PLAYING).
     */
    public void flush(long nowNanos) {
        now = nowNanos;
        if (state != ConnectionState.JOINING && state != ConnectionState.PLAYING) {
            return;
        }
        for (int i = 0; i < MAX_RELIABLE_PER_FLUSH; i++) {
            begin(PacketType.RELIABLE);
            if (channel.write(tx, nowNanos) == 0) {
                break;
            }
            transmit();
        }
        if (channel.consumeAckPending() && !sentThisFrame) {
            begin(PacketType.ACK);
            transmit();
        }
    }

    // ---------------------------------------------------------------- receiving

    private void handlePacket(ByteBuffer in) {
        if (!header.read(in)) {
            malformed++;
            return;
        }
        int type = header.type();
        if (state == ConnectionState.CONNECTING) {
            if (type == PacketType.ACCEPT) {
                onAccept(in);
            } else if (type == PacketType.REJECT) {
                onReject(in);
            }
            return;
        }
        if (header.session() != session) {
            return;
        }
        packetsReceived++;
        lastReceive = now;
        channel.onAck(header.reliableAck(), now);
        try {
            switch (type) {
                case PacketType.RELIABLE -> {
                    if (channel.read(in, rxHandler) < 0) {
                        malformed++;
                    }
                }
                case PacketType.SNAPSHOT -> onSnapshot(in);
                case PacketType.PING -> onPing(in);
                case PacketType.DISCONNECT -> onDisconnect(in);
                default -> { } // ACK, PONG, duplicate ACCEPT: header already processed
            }
        } catch (java.nio.BufferUnderflowException e) {
            malformed++;
        }
    }

    private void onAccept(ByteBuffer in) {
        if (in.remaining() < 6) {
            malformed++;
            return;
        }
        accept.read(in);
        if (accept.nonce != connect.nonce) {
            return;
        }
        packetsReceived++;
        lastReceive = now;
        session = header.session();
        playerId = accept.playerId;
        listener.onAccepted(playerId);
        status.setLength(0);
        status.append("Joining ").append(serverLabel).append("...");
        setState(ConnectionState.JOINING);
    }

    private void onReject(ByteBuffer in) {
        try {
            reject.read(in);
        } catch (java.nio.BufferUnderflowException e) {
            malformed++;
            return;
        }
        if (reject.nonce != connect.nonce) {
            return;
        }
        packetsReceived++;
        status.setLength(0);
        status.append("Rejected by ").append(serverLabel).append(": ").append(reject.text);
        setState(ConnectionState.CLOSED);
    }

    private void onDisconnect(ByteBuffer in) {
        disconnectMsg.read(in);
        status.setLength(0);
        status.append("Server ").append(serverLabel).append(" closed the connection");
        if (disconnectMsg.text.length() > 0) {
            status.append(": ").append(disconnectMsg.text);
        }
        setState(ConnectionState.CLOSED);
    }

    private void onPing(ByteBuffer in) {
        int start = in.position();
        int len = in.remaining();
        begin(PacketType.PONG);
        tx.put(tx.position(), in, start, len);
        tx.position(tx.position() + len);
        transmit();
    }

    private void onSnapshot(ByteBuffer in) {
        if (state != ConnectionState.PLAYING) {
            return;
        }
        if (in.remaining() < 4) {
            malformed++;
            return;
        }
        int tick = in.getInt(in.position());
        if (!haveTick || tick - latestTick > 0) {
            latestTick = tick;
            haveTick = true;
        }
        snapshots++;
        listener.onSnapshot(in, now);
    }

    private void onReliable(int type, ByteBuffer payload) {
        switch (type) {
            case ReliableType.WELCOME -> {
                welcome.read(payload);
                config = new ConfigTransfer.Receiver(welcome);
                listener.onWelcome(welcome);
                if (config.complete()) {
                    finishConfig();
                }
            }
            case ReliableType.DATA_CHUNK -> {
                if (config != null && state == ConnectionState.JOINING) {
                    try {
                        if (config.onChunk(payload)) {
                            finishConfig();
                        }
                    } catch (IllegalStateException e) {
                        badConfig(e);
                    }
                }
            }
            default -> listener.onReliable(type, payload);
        }
    }

    private void finishConfig() {
        Options opts;
        WorldMap map;
        try {
            opts = config.options();
            map = WorldMap.fromOptions(opts);
        } catch (RuntimeException e) {
            badConfig(e);
            return;
        }
        config = null;
        listener.onConfig(welcome, opts, map);
        Msg.sendReady(channel);
        status.setLength(0);
        status.append("Playing on ").append(serverLabel);
        setState(ConnectionState.PLAYING);
    }

    private void badConfig(RuntimeException e) {
        config = null;
        sendDisconnectCopies();
        status.setLength(0);
        status.append("Bad map/config from ").append(serverLabel).append(": ").append(e.getMessage());
        setState(ConnectionState.CLOSED);
    }

    // ---------------------------------------------------------------- sending

    private void sendConnect(long nowNanos) {
        lastConnectSend = nowNanos;
        attempts++;
        status.setLength(0);
        status.append("Connecting to ").append(serverLabel).append(" (attempt ").append(attempts).append(")...");
        begin(PacketType.CONNECT);
        connect.write(tx);
        transmit();
    }

    private void begin(int type) {
        tx.clear();
        PacketHeader.write(tx, type, session, channel.ack());
    }

    private void transmit() {
        tx.flip();
        try {
            if (transport.send(tx)) {
                packetsSent++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        sentThisFrame = true;
    }

    private void setState(ConnectionState s) {
        if (state != s) {
            state = s;
            listener.onStateChange(s, status);
        }
    }
}
