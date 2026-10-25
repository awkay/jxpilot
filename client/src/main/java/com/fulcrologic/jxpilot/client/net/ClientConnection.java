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
import java.nio.ByteBuffer;

/**
 * Client side of the JXPilot protocol (docs/PROTOCOL.md) over a {@link Transport}: the
 * CONNECT / ACCEPT / reliable WELCOME handshake, config (map) transfer, the reliable channel, INPUT
 * with redundancy, timeouts and reconnects. Decoded data goes to a {@link ConnectionListener}.
 * <p>
 * Time is passed in (nanoseconds, any monotonic origin) so the class is deterministic under test.
 * Single threaded. Per client frame call, in order: {@link #update(long)} (receive and timers),
 * {@link #sendInput(long, int, long)} (it decides itself whether an INPUT goes out this frame), then
 * {@link #flush(long)} (reliable resends, idle ACK).
 * <p>
 * Hostile or broken servers cannot crash the client: every datagram is decoded under a
 * {@code RuntimeException} guard and counted as {@link #malformed()} when it fails, and a failing reliable
 * message is skipped so it cannot wedge the channel. Transport {@link IOException}s (VPN or Wi-Fi drop, ICMP
 * port unreachable) count as "nothing received" / "not sent", so the normal timeout and reconnect run.
 * <p>
 * Allocation free while {@link ConnectionState#PLAYING}; the handshake and map build allocate.
 */
public final class ClientConnection {
    /** Most INPUT entries per packet ({@link InputHistory#MAX_REDUNDANCY}). */
    public static final int MAX_INPUT_REDUNDANCY = InputHistory.MAX_REDUNDANCY;
    /**
     * Shortest gap between two INPUT datagrams (a 120/s ceiling). A time, not a fraction of the tick: at
     * 14 Hz a key change must still go out at once, or it can miss the server's next frame.
     */
    public static final long MIN_INPUT_GAP_NANOS = 1_000_000_000L / 120;
    /** After a transport error INPUT, reliable data and ACKs pause this long (CONNECT keeps its own timer). */
    public static final long IO_BACKOFF_NANOS = Protocol.CONNECT_RESEND_MS * 1_000_000L;
    /** Reject/disconnect text shown in the status line at most (C {@code MAX_CHARS}). */
    public static final int MAX_STATUS_TEXT = 80;
    /** Size of a PING body ({@link Msg.Ping}: u32 id, u64 nanos); a PONG echoes exactly this. */
    public static final int PING_SIZE = 12;
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
    private final Msg.Ping ping = new Msg.Ping();
    private final StringBuilder status = new StringBuilder(256);
    private final ReliableHandler rxHandler = this::onReliable;

    /** CONNECT body; set name, team and shape before {@link #start(long)}. */
    public final Msg.Connect connect = new Msg.Connect();
    /** Control settings sent (reliable SETTINGS) before READY on every join, as C sends them with PKT_PLAY. */
    public final Msg.Settings settings = new Msg.Settings();
    /** View size in world pixels sent (reliable DISPLAY) before READY on every join, as C Net_start's PKT_DISPLAY. */
    public final Msg.Display display = new Msg.Display();
    /**
     * The four modifier bank texts sent (reliable MODIFIER_BANK, banks 0..3, empty ones too) after DISPLAY and
     * before READY on every join, as C sends {@code modBankStr} at login ({@code Send_modifier_bank}, items-2).
     */
    public final String[] modifierBanks = {"", "", "", ""};
    private final Msg.ModifierBank bankMsg = new Msg.ModifierBank();
    /** Reconnect automatically when an established session times out (default true). */
    private boolean autoReconnect = true;

    private ConnectionState state = ConnectionState.IDLE;
    private int session;
    private int playerId = Protocol.NO_ID;
    private long lastReceive;
    private long lastConnectSend;
    /** server-commands: waiting in the server's login queue (REJECT QUEUED); 0 when not queued. */
    private int queuePosition;
    /** CONNECT resend interval while queued (C re-requests a queue place; 1 s keeps the place and the position fresh). */
    static final long QUEUE_RESEND_NANOS = 1_000_000_000L;
    private int attempts;
    private boolean sentThisFrame;
    private ConfigTransfer.Receiver config;
    private LobbyClient lobby;
    /** The game being connected to was chosen in a lobby: a refusal returns to the list instead of closing. */
    private boolean viaLobby;
    private boolean haveTick;
    private int latestTick;
    private long now;

    // INPUT rate control (see sendInput)
    private long inputIntervalNanos = 1_000_000_000L / 60;
    private boolean inputSent;
    private long lastInputSend;
    private long nextInputDue;
    private long lastSentKeys;
    private int pendingAim;
    private int ackedInputSeq;

    // transport errors
    private boolean ioBackoff;
    private long ioBackoffUntil;
    private long ioErrors;

    private long packetsReceived;
    /** C's packet meter measurements (client-ui-look), in snapshot slots: one slot per snapshot interval. */
    private final PacketStats stats = new PacketStats();
    private int slotTicks = 1;
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

    /** Datagrams (or reliable messages) dropped as malformed: bad header, truncated or undecodable body. */
    public long malformed() { return malformed; }

    /** Transport IOExceptions on send or receive (each counts as not sent / nothing received). */
    public long ioErrors() { return ioErrors; }

    /** Newest INPUT sequence the server echoed in a snapshot header (lastInputSeq). */
    public int ackedInputSeq() { return ackedInputSeq; }

    /** Minimum time between two INPUT datagrams without a key change: 1 / simHz (from WELCOME). */
    public long inputIntervalNanos() { return inputIntervalNanos; }

    public long snapshotsReceived() { return snapshots; }

    /**
     * The packet meters' measurements: snapshots per slot of one snapshot interval (simHz / snapshotHz ticks),
     * one-second windows of snapshotHz slots; lag is the INPUT round trip in snapshot intervals.
     */
    public PacketStats packetStats() { return stats; }

    /**
     * The lobby dialogue while the state is {@link ConnectionState#LOBBY}, else null: the map list to show and
     * the way to choose one.
     */
    public LobbyClient lobby() { return state == ConnectionState.LOBBY ? lobby : null; }

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
        queuePosition = 0;
        session = 0;
        playerId = Protocol.NO_ID;
        channel.reset();
        config = null;
        haveTick = false;
        latestTick = 0;
        lastReceive = nowNanos;
        inputSent = false;
        pendingAim = 0;
        ackedInputSeq = input.latestSeq();
        ioBackoff = false;
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
     * BEHAVIOR: the session-starting ACCEPT, and every repeated ACCEPT while JOINING, is answered at once with a
     * bare ACK carrying the session id (the server's proof that the client receives at its address).
     * BEHAVIOR: CONNECT is resent every {@link Protocol#CONNECT_RESEND_MS} ms while CONNECTING.
     * BEHAVIOR: after {@link Protocol#TIMEOUT_MS} ms of silence an established session is lost: it
     * reconnects when auto-reconnect is on (status says so), else CLOSED. While CONNECTING the attempt
     * cycle restarts (auto-reconnect) or the connection closes ("no answer").
     * BEHAVIOR: a receive IOException counts in {@link #ioErrors()}, ends the receive loop as "nothing
     * received" and starts the {@link #IO_BACKOFF_NANOS} send pause; it never escapes.
     * BEHAVIOR: a datagram whose decoding throws any RuntimeException counts as malformed; it never escapes.
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
                ioError(nowNanos);
                break;
            }
            if (n <= 0) {
                break;
            }
            rx.flip();
            try {
                handlePacket(rx);
            } catch (RuntimeException e) {
                malformed++;
            }
        }
        timers(nowNanos);
    }

    private void ioError(long nowNanos) {
        ioErrors++;
        ioBackoff = true;
        ioBackoffUntil = nowNanos + IO_BACKOFF_NANOS;
    }

    /** True while sends pause after a transport error (cleared by the pause ending or any valid datagram). */
    private boolean inIoBackoff(long nowNanos) {
        if (ioBackoff && nowNanos - ioBackoffUntil >= 0) {
            ioBackoff = false;
        }
        return ioBackoff;
    }

    private void timers(long nowNanos) {
        if (state == ConnectionState.CLOSED) {
            return;
        }
        if (nowNanos - lastReceive >= TIMEOUT_NANOS) {
            onTimeout(nowNanos);
            return;
        }
        if (state == ConnectionState.CONNECTING
            && nowNanos - lastConnectSend >= (queuePosition > 0 ? QUEUE_RESEND_NANOS : CONNECT_RESEND_NANOS)) {
            sendConnect(nowNanos);
        }
        if (state == ConnectionState.LOBBY) {
            lobby.tick(nowNanos);
            if (lobby.takeJoined()) {
                start(nowNanos); // the lobby routed this address to the map's game: connect as usual
                viaLobby = true;
            }
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
     * Offer this frame's input; an INPUT datagram goes out only when it is due, so a fast render loop does
     * not flood the server (PROTOCOL.md "INPUT").
     * <p>
     * BEHAVIOR: only while PLAYING (before that the server has not spawned the player); returns the new
     * input sequence, or 0 when nothing was sent this frame.
     * BEHAVIOR: sends at once when the key mask differs from the last one sent (and for the first input);
     * otherwise when {@link #inputIntervalNanos()} (1/simHz) has passed since the last periodic send.
     * BEHAVIOR: never two sends closer than {@link #MIN_INPUT_GAP_NANOS} (a 120/s ceiling at any tick
     * rate): a change inside that window waits and goes out with whatever mask is current then (coalesced).
     * BEHAVIOR: aimTurn of frames that sent nothing is summed into the next INPUT (clamped to i16).
     * BEHAVIOR: a sequence is recorded only for a datagram actually sent; it carries every history entry
     * newer than the lastInputSeq the server echoed in its newest snapshot, at least 1 and at most
     * {@link #MAX_INPUT_REDUNDANCY}.
     * BEHAVIOR: nothing is sent during the transport-error pause ({@link #IO_BACKOFF_NANOS}).
     *
     * @param keys    {@link com.fulcrologic.jxpilot.common.net.ControlKey} mask
     * @param aimTurn pointer-control turn in u16 angle units (0 = none)
     */
    public int sendInput(long keys, int aimTurn, long nowNanos) {
        now = nowNanos;
        if (state != ConnectionState.PLAYING) {
            return 0;
        }
        pendingAim = clampAim(pendingAim + aimTurn);
        if (inIoBackoff(nowNanos)) {
            return 0;
        }
        boolean changed = !inputSent || keys != lastSentKeys;
        boolean timer = !inputSent || nowNanos - nextInputDue >= 0;
        if (!changed && !timer) {
            return 0;
        }
        if (inputSent && nowNanos - lastInputSend < MIN_INPUT_GAP_NANOS) {
            return 0;
        }
        int seq = input.record(keys, pendingAim);
        stats.keyboardSent(seq, latestTick / slotTicks);
        begin(PacketType.INPUT);
        input.write(tx, latestTick, inputRedundancy());
        transmit();
        if (!inputSent) {
            nextInputDue = nowNanos + inputIntervalNanos;
        } else if (timer) {
            nextInputDue += inputIntervalNanos;
            if (nextInputDue - nowNanos <= 0) {
                nextInputDue = nowNanos + inputIntervalNanos;
            }
        }
        inputSent = true;
        lastInputSend = nowNanos;
        lastSentKeys = keys;
        pendingAim = 0;
        return seq;
    }

    private static int clampAim(int aim) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, aim));
    }

    /** BEHAVIOR: entries newer than the echoed lastInputSeq, clamped to 1..{@link #MAX_INPUT_REDUNDANCY}. */
    int inputRedundancy() {
        int d = input.latestSeq() - ackedInputSeq;
        return Math.max(1, Math.min(MAX_INPUT_REDUNDANCY, d));
    }

    /**
     * Send reliable messages that are new or due for resend; send a header-only ACK if reliable data
     * arrived and no datagram went out since (the ack rides in every header).
     * <p>
     * BEHAVIOR: nothing is sent unless a session exists (JOINING or PLAYING), nor during the
     * transport-error pause.
     */
    public void flush(long nowNanos) {
        now = nowNanos;
        if (state != ConnectionState.JOINING && state != ConnectionState.PLAYING || inIoBackoff(nowNanos)) {
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
        if (state == ConnectionState.LOBBY) {
            if (lobby.onPacket(type, in, now)) {
                packetsReceived++;
                lastReceive = now;
                ioBackoff = false;
                if (lobby.takeJoined()) {
                    start(now);
                    viaLobby = true;
                }
            } else if (type == PacketType.REJECT) {
                onReject(in); // a lobby that restarted refuses again; the nonce check ignores a stale one
            }
            return;
        }
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
        ioBackoff = false;
        channel.onAck(header.reliableAck(), now);
        switch (type) {
            case PacketType.RELIABLE -> {
                if (channel.read(in, rxHandler) < 0) {
                    malformed++;
                }
            }
            case PacketType.SNAPSHOT -> onSnapshot(in);
            case PacketType.PING -> onPing(in);
            case PacketType.DISCONNECT -> onDisconnect(in);
            case PacketType.ACCEPT -> {
                if (state == ConnectionState.JOINING) {
                    proveSession();
                }
            }
            default -> { } // ACK, PONG: header already processed
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
        queuePosition = 0;
        packetsReceived++;
        lastReceive = now;
        session = header.session();
        playerId = accept.playerId;
        listener.onAccepted(playerId);
        status.setLength(0);
        status.append("Joining ").append(serverLabel).append("...");
        setState(ConnectionState.JOINING);
        proveSession();
    }

    private void onReject(ByteBuffer in) {
        reject.read(in);
        if (reject.nonce != connect.nonce) {
            return;
        }
        packetsReceived++;
        if (reject.reason == com.fulcrologic.jxpilot.common.net.Msg.Reject.REASON_LOBBY) {
            boolean handedOff = viaLobby && state == ConnectionState.CONNECTING;
            enterLobby();
            if (handedOff) {
                // the lobby answered the CONNECT it should have handed to the game: say so instead of a silent list
                lobby.notice("Could not join " + lobby.joinedName() + " (the lobby did not hand us to the game)."
                    + " Choose it again.");
            }
            return;
        }
        if (reject.reason == com.fulcrologic.jxpilot.common.net.Msg.Reject.REASON_QUEUED) {
            // server-commands: C's "... queued at position N"; keep asking (same nonce) until admitted
            queuePosition = Math.max(1, reject.position);
            lastReceive = now;
            status.setLength(0);
            status.append("Waiting in queue on ").append(serverLabel).append(" (position ").append(queuePosition)
                .append(')');
            listener.onStateChange(state, status);
            return;
        }
        if (viaLobby) {
            // the lobby routed us to a game that refused (full, locked, name taken ...): choose another map
            String why = reject.text.toString();
            enterLobby();
            lobby.notice("Refused: " + why);
            return;
        }
        status.setLength(0);
        status.append("Rejected by ").append(serverLabel).append(": ");
        appendCapped(reject.text);
        setState(ConnectionState.CLOSED);
    }

    /**
     * A lobby server answered the CONNECT: show its maps instead of waiting for ACCEPT.
     * <p>
     * BEHAVIOR: the state becomes LOBBY, the lobby dialogue starts afresh (HELLO goes out at once) and the status
     * line names the server; the connection's timeout restarts, so a lobby that goes silent for
     * {@link Protocol#TIMEOUT_MS} ms is handled like a game server that does (reconnect).
     */
    private void enterLobby() {
        if (lobby == null) {
            lobby = new LobbyClient(transport);
        }
        lastReceive = now;
        queuePosition = 0;
        viaLobby = false;
        lobby.begin(now, connect.nonce);
        status.setLength(0);
        status.append("Lobby on ").append(serverLabel).append(": choose a map");
        setState(ConnectionState.LOBBY);
    }

    /** server-commands: the login queue position while waiting (REJECT QUEUED), else 0. */
    public int queuePosition() {
        return queuePosition;
    }

    private void onDisconnect(ByteBuffer in) {
        disconnectMsg.read(in);
        status.setLength(0);
        status.append("Server ").append(serverLabel).append(" closed the connection");
        if (disconnectMsg.text.length() > 0) {
            status.append(": ");
            appendCapped(disconnectMsg.text);
        }
        setState(ConnectionState.CLOSED);
    }

    /** BEHAVIOR: server-supplied text goes into the status line cut at {@link #MAX_STATUS_TEXT} chars. */
    private void appendCapped(CharSequence text) {
        status.append(text, 0, Math.min(text.length(), MAX_STATUS_TEXT));
    }

    /**
     * BEHAVIOR: PONG echoes exactly the 12-byte PING body; a shorter body underflows before anything is
     * sent and counts as malformed.
     */
    private void onPing(ByteBuffer in) {
        ping.read(in);
        begin(PacketType.PONG);
        ping.write(tx);
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
            int drawn = latestTick / slotTicks;
            latestTick = tick;
            haveTick = true;
            stats.measure(tick / slotTicks, PacketStats.DRAW);
            stats.frameSize(in.limit());
            if (in.remaining() >= 8) {
                int seq = in.getInt(in.position() + 4);
                if (seq - ackedInputSeq > 0 && seq - input.latestSeq() <= 0) {
                    ackedInputSeq = seq;
                    stats.keyboardAck(seq, drawn);
                }
            }
        } else {
            stats.measure(tick / slotTicks, PacketStats.DROP);
        }
        snapshots++;
        listener.onSnapshot(in, now);
    }

    /**
     * One reliable message, in order.
     * <p>
     * BEHAVIOR: a message whose handling throws counts as malformed and is skipped (the channel moves on,
     * so one bad message cannot wedge the session); a bad WELCOME or DATA_CHUNK is a bad config.
     */
    private void onReliable(int type, ByteBuffer payload) {
        try {
            switch (type) {
                case ReliableType.WELCOME -> onWelcome(payload);
                case ReliableType.DATA_CHUNK -> {
                    if (config != null && state == ConnectionState.JOINING) {
                        try {
                            if (config.onChunk(payload)) {
                                finishConfig();
                            }
                        } catch (RuntimeException e) {
                            badConfig(e);
                        }
                    }
                }
                default -> listener.onReliable(type, payload);
            }
        } catch (RuntimeException e) {
            malformed++;
        }
    }

    /** BEHAVIOR: sets the INPUT interval from simHz; announced config sizes out of range are a bad config. */
    private void onWelcome(ByteBuffer payload) {
        welcome.read(payload);
        inputIntervalNanos = 1_000_000_000L / (welcome.simHz > 0 ? welcome.simHz : 60);
        int simHz = welcome.simHz > 0 ? welcome.simHz : 60;
        int snapHz = welcome.snapshotHz > 0 ? welcome.snapshotHz : simHz;
        slotTicks = Math.max(1, simHz / snapHz);
        stats.reset(Math.max(1, simHz / slotTicks));
        try {
            config = new ConfigTransfer.Receiver(welcome);
        } catch (RuntimeException e) {
            badConfig(e);
            return;
        }
        listener.onWelcome(welcome);
        if (config.complete()) {
            finishConfig();
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
        settings.send(channel);
        display.send(channel);
        for (int i = 0; i < Msg.ModifierBank.BANKS; i++) {
            bankMsg.bank = i;
            bankMsg.text.setLength(0);
            bankMsg.text.append(modifierBanks[i]);
            bankMsg.send(channel);
        }
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
        if (queuePosition == 0) {
            status.setLength(0);
            status.append("Connecting to ").append(serverLabel).append(" (attempt ").append(attempts).append(")...");
        }
        begin(PacketType.CONNECT);
        connect.write(tx);
        transmit();
    }

    /**
     * The server sends a new session nothing but ACCEPT until a packet with the session id proves the client
     * receives at its address (PROTOCOL.md "Connection flow"): answer every ACCEPT with a bare ACK.
     */
    private void proveSession() {
        begin(PacketType.ACK);
        transmit();
    }

    private void begin(int type) {
        tx.clear();
        PacketHeader.write(tx, type, session, channel.ack());
    }

    /** BEHAVIOR: a send IOException counts in {@link #ioErrors()} as "not sent" and starts the send pause. */
    private void transmit() {
        tx.flip();
        try {
            if (transport.send(tx)) {
                packetsSent++;
            }
        } catch (IOException e) {
            ioError(now);
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
