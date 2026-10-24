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
package com.fulcrologic.jxpilot.client.xp;

import com.fulcrologic.jxpilot.client.net.ConnectionListener;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.net.Transport;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Client side of the original XPilot 4.5.5 protocol, feeding the same {@link ConnectionListener} as the
 * native {@link com.fulcrologic.jxpilot.client.net.ClientConnection}. A port of the C client's
 * {@code client/textinterface.c} contact code and {@code client/netclient.c}:
 * <ol>
 * <li>CONTACT: {@code CONTACT_pack} to the contact port; the reply magic gives the server version
 *     (the lower of ours and the server's).</li>
 * <li>QUEUE: {@code ENTER_QUEUE_pack} with nick, display, host and team until an
 *     {@code ENTER_GAME_pack} reply carries the per-player login port (the path the 4.5.5 client
 *     uses; {@code ENTER_GAME_pack} directly makes the server connect back to the client's host
 *     <i>name</i>).</li>
 * <li>VERIFY ({@code Net_verify}): {@code PKT_VERIFY} to the login port until the reliable stream
 *     brings {@code PKT_REPLY(VERIFY, SUCCESS)} and {@code PKT_MAGIC}.</li>
 * <li>SETUP ({@code Net_setup}): the stream carries the setup header and the compressed map, built
 *     into a {@link com.fulcrologic.jxpilot.common.map.WorldMap} ({@link XpMap}).</li>
 * <li>START ({@code Net_start}): shape, {@code PKT_PLAY}, power settings, display size and frame rate
 *     until {@code PKT_REPLY(PLAY, SUCCESS)}.</li>
 * <li>PLAYING: frames ({@link XpFrameDecoder}) become snapshots ({@link XpTranslator}); reliable
 *     packets ({@link XpReliableDecoder}) become players, scores and messages; keys go out as
 *     {@code PKT_KEYBOARD} ({@link XpKeyboard}), chat as {@code PKT_TALK} ({@link XpTalk}).</li>
 * </ol>
 * Malformed or unknown packets are counted and dropped like C drops them, never thrown. Single
 * threaded; time is passed in. Allocation free while PLAYING (the handshake and map build allocate).
 */
public final class XpConnection implements GameConnection {
    /** Opens a datagram transport to a port of the server host. */
    @FunctionalInterface
    public interface PortOpener {
        Transport open(int port) throws IOException;
    }

    /** The control settings the server was last sent (PKT_PLAY or a settings change). */
    private final Msg.Settings sent = new Msg.Settings();

    /** Login and play settings. */
    public static final class Login {
        /** Nick (first letter is capitalised, at most 15 chars, as the C server does). */
        public String nick = "Jxpilot";
        public String realName = "jxpilot";
        public String host = "jxpilot";
        public String display = ":0";
        /** Team 0..9 or {@link Protocol#NO_TEAM}. */
        public int team = Protocol.NO_TEAM;
        /** The shipShape resource, empty for the default ship; converted when sent (C Send_shape). */
        public String shape = "";
        public final Msg.Settings settings = new Msg.Settings();
        /** View size requested with PKT_DISPLAY (C limits it to 384..1024). */
        public int viewWidth = XpProto.MAX_VIEW_SIZE;
        public int viewHeight = XpProto.MAX_VIEW_SIZE;
        /** Debris colours requested (below 3 the server sends object colours). */
        public int debrisColors = 0;
        /** Chance a spark is sent each frame, 0..1 (C {@code sparkProb}, default 0.5). */
        public double sparkProb = 0.5;
        /** Ask for PKT_AUDIO (C {@code Send_audio_request(1)} in a client built with sound). */
        public boolean audio;
        /** C {@code maxFPS}: 0 asks for the server's full rate (JXPilot), else limited to FPS/2..FPS (C). */
        public int maxFps;
        /** C {@code modBankStr}: the modifier banks sent with every play request (PKT_MODIFIERBANK). */
        public final String[] modifierBanks = {"", "", "", ""};
    }

    /** Internal steps of the handshake. */
    public enum Phase { IDLE, CONTACT, QUEUE, VERIFY, SETUP, START, PLAYING, CLOSED }

    public static final long SECOND = 1_000_000_000L;
    /** Contact and verify packets are resent this often. */
    public static final long RESEND_NANOS = SECOND;
    /** ENTER_QUEUE is repeated this often while queued (the server forgets after 30 s). */
    public static final long QUEUE_RESEND_NANOS = 2 * SECOND;
    /** Queued: give up when the contact port has been silent this long (C textinterface.c: 12 s per reply). */
    public static final long QUEUE_TIMEOUT_NANOS = 12 * SECOND;
    /** Contact, verify and play requests are tried this often before giving up (C uses 10). */
    public static final int MAX_TRIES = 10;
    /** C sends the ship shape only with the first 5 play requests. */
    public static final int SHAPE_TRIES = 5;
    /** Verify, setup or start: silence this long fails the login (C Net_setup: 10 reads of 2 s). */
    public static final long TIMEOUT_NANOS = 20 * SECOND;
    /**
     * Playing: each this much silence says "No response from server" (C Input_loop's 10 s select). Silence never ends
     * the connection while playing (C); the player quits.
     */
    public static final long NO_RESPONSE_NANOS = 10 * SECOND;
    /** Datagrams handled per {@link #update} call at most. */
    public static final int MAX_RECEIVE_PER_UPDATE = 256;
    /** Bytes kept free in the send buffer for keyboard and talk. */
    private static final int RESERVE = XpKeyboard.SIZE + 1 + 4 + XpProto.MAX_CHARS;

    private final PortOpener opener;
    private final ConnectionListener listener;
    private final String label;
    private final Login login;

    private final ByteBuffer rx = ByteBuffer.allocate(16 * 1024);
    private final ByteBuffer tx = ByteBuffer.allocate(1024);
    private final XpReader reader = new XpReader();
    private final XpReader streamReader = new XpReader();
    private final XpKeyboard keyboard = new XpKeyboard();
    private final XpTalk talk = new XpTalk();
    private final XpFrame frame = new XpFrame();
    private final XpFrameDecoder frames = new XpFrameDecoder(keyboard);
    private final XpReliableReceiver reliable = new XpReliableReceiver(64 * 1024);
    private final XpSetup setup = new XpSetup();
    private final XpTranslator translator;
    private final XpReliableDecoder events;
    private final Msg.Welcome welcome = new Msg.Welcome();
    private final StringBuilder status = new StringBuilder(160);
    private final StringBuilder scratch = new StringBuilder(XpProto.MSG_LEN);

    private Transport contact;
    private Transport player;
    private Phase phase = Phase.IDLE;
    private ConnectionState state = ConnectionState.IDLE;
    private int version = XpProto.MY_VERSION;
    private int loginPort;
    private int queuePos;
    private int connMagic;
    private boolean verified;
    private int tries;
    private long lastSend;
    private long phaseStart;
    private long lastReceive;
    /** Silence notices given since the last datagram while playing. */
    private int noResponse;
    private long now;
    private int lastSendAnything;
    private boolean newFrame;
    private XpMap.Built built;

    private long packetsReceived;
    private long packetsSent;
    private long malformed;
    private long unknown;
    private long stale;
    private long ioErrors;
    private long framesDone;
    private long badStream;

    public XpConnection(PortOpener opener, ConnectionListener listener, String label, Login login) {
        this.opener = opener;
        this.listener = listener;
        this.label = label;
        this.login = login;
        this.translator = new XpTranslator(listener);
        this.events = new XpReliableDecoder(translator, new Control());
        frames.setOut(tx);
    }

    // ================================================================ accessors

    @Override
    public ConnectTarget.Protocol protocol() { return ConnectTarget.Protocol.XPILOT; }

    @Override
    public ConnectionState state() { return state; }

    public Phase phase() { return phase; }

    @Override
    public CharSequence status() { return status; }

    @Override
    public long packetsReceived() { return packetsReceived; }

    @Override
    public long packetsSent() { return packetsSent; }

    /** Truncated packets, bad reliable chunks, premature keyboard acks and unknown packet types. */
    @Override
    public long malformed() { return malformed + unknown; }

    /** Datagrams cut short at an unknown packet type (C "Received unknown packet type"). */
    public long unknownPackets() { return unknown; }

    /** Frames older than the newest one (dropped). */
    public long staleFrames() { return stale; }

    /** Transport IOExceptions on send or receive (each counts as not sent / nothing received). */
    public long ioErrors() { return ioErrors; }

    @Override
    public long snapshotsReceived() { return framesDone; }

    /** Negotiated protocol version (min of server and client). */
    public int version() { return version; }

    public int loginPort() { return loginPort; }

    /** Last queue position reported by the server (0 once admitted). */
    public int queuePosition() { return queuePos; }

    /** Own C player id, {@link Protocol#NO_ID} until the server announced it. */
    public int playerId() { return translator.selfId(); }

    /** C {@code eyesId} of the newest frame ({@link XpTranslator#eyesId}). */
    @Override
    public int eyesId() { return translator.eyesId(); }

    /** The map built from the setup (null before). */
    public XpMap.Built map() { return built; }

    public XpSetup setup() { return setup; }

    public XpKeyboard keyboard() { return keyboard; }

    public XpTalk talk() { return talk; }

    public XpTranslator translator() { return translator; }

    public XpReliableDecoder reliableDecoder() { return events; }

    /** Newest frame number (C {@code last_loops}). */
    public int lastLoops() { return frames.lastLoops(); }

    /** The connection magic from the verify (unused by the protocol afterwards). */
    public int magic() { return connMagic; }

    // ================================================================ life cycle

    /** BEHAVIOR: opens the contact transport and sends the first CONTACT; state CONNECTING. */
    @Override
    public void start(long nowNanos) {
        now = nowNanos;
        closeTransports();
        keyboard.reset();
        talk.reset();
        frames.reset();
        reliable.reset();
        setup.reset();
        built = null;
        verified = false;
        version = XpProto.MY_VERSION;
        lastReceive = nowNanos;
        noResponse = 0;
        try {
            contact = opener.open(portOf(label));
        } catch (IOException e) {
            fail("Cannot open socket to " + label + ": " + e.getMessage());
            return;
        }
        enter(Phase.CONTACT, nowNanos);
        sendContact();
        setState(ConnectionState.CONNECTING, "Contacting " + label + "...");
    }

    private static int portOf(String label) {
        int i = label.lastIndexOf(':');
        try {
            return i >= 0 ? Integer.parseInt(label.substring(i + 1)) : XpProto.SERVER_PORT;
        } catch (NumberFormatException e) {
            return XpProto.SERVER_PORT;
        }
    }

    /**
     * BEHAVIOR: sends {@code PKT_QUIT} three times when a login port is open (C {@code Net_cleanup}),
     * closes the transports; state CLOSED.
     */
    @Override
    public void disconnect(long nowNanos) {
        now = nowNanos;
        if (player != null && phase != Phase.CLOSED) {
            for (int i = 0; i < 3; i++) {
                tx.clear();
                XpWriter.u8(tx, XpProto.PKT_QUIT);
                transmit(player);
            }
        }
        closeTransports();
        phase = Phase.CLOSED;
        setState(ConnectionState.CLOSED, "Disconnected from " + label);
    }

    @Override
    public void close() {
        closeTransports();
    }

    private void closeTransports() {
        if (contact != null) {
            contact.close();
            contact = null;
        }
        if (player != null) {
            player.close();
            player = null;
        }
    }

    private void enter(Phase p, long nowNanos) {
        phase = p;
        phaseStart = nowNanos;
        tries = 0;
        lastSend = nowNanos;
    }

    private void fail(String why) {
        closeTransports();
        phase = Phase.CLOSED;
        setState(ConnectionState.CLOSED, why);
    }

    private void setState(ConnectionState s, CharSequence text) {
        status.setLength(0);
        status.append(text);
        state = s;
        listener.onStateChange(s, status);
    }

    // ================================================================ per frame

    /**
     * Receive everything waiting, then run timers.
     * <p>
     * BEHAVIOR: contact replies are read while contacting/queued; login-port datagrams from VERIFY on.
     * BEHAVIOR: after new frames, the C {@code Net_input} rule: resend the keyboard when its last change
     * is unacked and was not sent this frame, or as a keep-alive after 5 seconds of frames without a
     * send; pending acks are flushed.
     * BEHAVIOR: resends CONTACT / ENTER_QUEUE / VERIFY / the play request on their timers and gives up
     * after {@link #MAX_TRIES}; queued, {@link #QUEUE_TIMEOUT_NANOS} without a contact reply closes (no cap
     * while replies come, as C); verifying to starting, {@link #TIMEOUT_NANOS} of silence closes.
     * BEHAVIOR: playing, each {@link #NO_RESPONSE_NANOS} of silence adds C's "No response from server" as a
     * message; silence never closes the connection (C keeps waiting).
     * BEHAVIOR: a transport IOException (ICMP port unreachable, network down) counts in {@link #ioErrors()} and
     * is "nothing received" / "not sent" (C Sockbuf_read / Sockbuf_flush retry and carry on); it never escapes.
     */
    @Override
    public void update(long nowNanos) {
        now = nowNanos;
        newFrame = false;
        if (phase == Phase.IDLE || phase == Phase.CLOSED) {
            return;
        }
        if (contact != null && (phase == Phase.CONTACT || phase == Phase.QUEUE)) {
            receiveContact();
        }
        if (player != null) {
            receivePlayer();
        }
        if (phase == Phase.CLOSED) {
            return;
        }
        if (phase == Phase.PLAYING && newFrame) {
            int loops = frames.lastLoops();
            if (keyboard.resendDue(loops) || loops - lastSendAnything > 5 * fps()) {
                sendDatagram(true);
            }
        }
        flushPending();
        timers(nowNanos);
    }

    private int fps() {
        return setup.fps > 0 ? setup.fps : 14;
    }

    private void timers(long nowNanos) {
        switch (phase) {
            case CONTACT -> {
                if (nowNanos - lastSend >= RESEND_NANOS) {
                    if (tries >= MAX_TRIES) {
                        fail("No answer from " + label);
                        return;
                    }
                    sendContact();
                }
            }
            case QUEUE -> {
                if (nowNanos - lastReceive >= QUEUE_TIMEOUT_NANOS) {
                    fail("No answer from " + label + " (queued at " + queuePos + ")");
                    return;
                }
                if (nowNanos - lastSend >= QUEUE_RESEND_NANOS) {
                    sendQueue();
                }
            }
            case VERIFY -> {
                if (nowNanos - lastSend >= RESEND_NANOS) {
                    if (tries >= MAX_TRIES) {
                        fail("Can't connect to " + label + " after " + tries + " verify attempts");
                        return;
                    }
                    sendVerify();
                }
            }
            case START -> {
                if (nowNanos - lastSend >= RESEND_NANOS) {
                    if (tries >= MAX_TRIES) {
                        fail("Can't start play on " + label + " after " + tries + " attempts");
                        return;
                    }
                    sendPlay();
                }
            }
            default -> { }
        }
        long silent = nowNanos - lastReceive;
        if (phase == Phase.PLAYING) {
            if (silent >= (noResponse + 1) * NO_RESPONSE_NANOS) {
                noResponse++;
                translator.message(NO_RESPONSE);
            }
        } else if (phase.ordinal() >= Phase.VERIFY.ordinal() && silent >= TIMEOUT_NANOS) {
            fail("No answer from " + label + " (timeout)");
        }
    }

    /** C Input_loop's error text for 10 s without a datagram. */
    static final String NO_RESPONSE = "No response from server";

    /**
     * Keys for this frame.
     * <p>
     * BEHAVIOR: only while PLAYING; a changed key set is a new keyboard change sent at once (C
     * {@code Net_key_change}); returns the change number when sent, else 0.
     * BEHAVIOR: a non-zero {@code aimTurn} (pointer movement in window pixels) is C's {@code Send_pointer_move}:
     * {@code PKT_POINTER_MOVE %hd} queued for this frame's datagram, for servers above version 0x3201 only.
     */
    @Override
    public int sendInput(long keys, int aimTurn, long nowNanos) {
        now = nowNanos;
        if (phase != Phase.PLAYING) {
            return 0;
        }
        if (aimTurn != 0 && version > 0x3201) {
            ensureRoom(3);
            XpWriter.u8(tx, XpProto.PKT_POINTER_MOVE);
            XpWriter.s16(tx, Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, aimTurn)));
        }
        if (!keyboard.setKeys(keys)) {
            return 0;
        }
        sendDatagram(true);
        return keyboard.change();
    }

    /** BEHAVIOR: sends pending acks (and due talk) if any. */
    @Override
    public void flush(long nowNanos) {
        now = nowNanos;
        if (phase != Phase.CLOSED && phase != Phase.IDLE) {
            flushPending();
        }
    }

    /**
     * C {@code Send_power} ... {@code Send_turnresistance_s}.
     * <p>
     * BEHAVIOR: the settings become the login's (sent with the next PKT_PLAY); while PLAYING only the values
     * that differ from what the server was last sent go out at once, one packet each (value * 256): a power or
     * turn-speed key sends exactly the one packet C's key handler sends; true iff something was sent.
     */
    @Override
    public boolean sendSettings(Msg.Settings settings, long nowNanos) {
        now = nowNanos;
        NativeGameConnection.copy(settings, login.settings);
        if (phase != Phase.PLAYING) {
            return false;
        }
        Msg.Settings st = login.settings;
        ensureRoom(6 * 3);
        int n = 0;
        n += changed(XpProto.PKT_POWER, st.power, sent.power);
        n += changed(XpProto.PKT_POWER_S, st.altPower, sent.altPower);
        n += changed(XpProto.PKT_TURNSPEED, st.turnSpeed, sent.turnSpeed);
        n += changed(XpProto.PKT_TURNSPEED_S, st.altTurnSpeed, sent.altTurnSpeed);
        n += changed(XpProto.PKT_TURNRESISTANCE, st.turnResistance, sent.turnResistance);
        n += changed(XpProto.PKT_TURNRESISTANCE_S, st.altTurnResistance, sent.altTurnResistance);
        NativeGameConnection.copy(st, sent);
        if (n > 0) {
            flushPending();
        }
        return n > 0;
    }

    private int changed(int type, float now, float before) {
        if ((int) (now * 256.0) == (int) (before * 256.0)) {
            return 0;
        }
        power(type, now);
        return 1;
    }

    /** BEHAVIOR: the banks become the login's, sent with every play request (C sends them only at login). */
    @Override
    public void setModifierBanks(String[] banks) {
        System.arraycopy(banks, 0, login.modifierBanks, 0, login.modifierBanks.length);
    }

    private void writeSettings(Msg.Settings st) {
        power(XpProto.PKT_POWER, st.power);
        power(XpProto.PKT_POWER_S, st.altPower);
        power(XpProto.PKT_TURNSPEED, st.turnSpeed);
        power(XpProto.PKT_TURNSPEED_S, st.altTurnSpeed);
        power(XpProto.PKT_TURNRESISTANCE, st.turnResistance);
        power(XpProto.PKT_TURNRESISTANCE_S, st.altTurnResistance);
        NativeGameConnection.copy(st, sent);
    }

    /**
     * BEHAVIOR: only while PLAYING; queues the line (C {@code Net_talk}) and sends it at once.
     */
    @Override
    public boolean sendChat(CharSequence text, long nowNanos) {
        now = nowNanos;
        if (phase != Phase.PLAYING || !talk.add(text, frames.lastLoops())) {
            return false;
        }
        flushPending();
        return true;
    }

    // ================================================================ contact socket

    private void sendContact() {
        tries++;
        lastSend = now;
        tx.clear();
        contactHeader(XpProto.MAGIC, XpProto.CONTACT_PACK);
        transmit(contact);
    }

    /** {@code %u%s%hu%c}: magic, real name, port (ignored by the server), request. */
    private void contactHeader(int magic, int request) {
        XpWriter.s32(tx, magic);
        XpWriter.string(tx, login.realName, XpProto.MAX_CHARS);
        XpWriter.s16(tx, 0);
        XpWriter.u8(tx, request);
    }

    /** {@code ENTER_QUEUE_pack %s%s%s%d}: nick, display, host, team (-1 = none). */
    private void sendQueue() {
        tries++;
        lastSend = now;
        tx.clear();
        contactHeader(XpProto.versionToMagic(version), XpProto.ENTER_QUEUE_PACK);
        XpWriter.string(tx, nick(), XpProto.MAX_NAME_LEN);
        XpWriter.string(tx, login.display, XpProto.MAX_CHARS);
        XpWriter.string(tx, login.host, XpProto.MAX_HOST_LEN);
        XpWriter.s32(tx, login.team >= 0 && login.team < 10 ? login.team : -1);
        transmit(contact);
    }

    /** BEHAVIOR: the nick with its first letter capitalised (C {@code Fix_nick_name} / {@code CAP_LETTER}). */
    String nick() {
        String n = login.nick.isEmpty() ? "Jxpilot" : login.nick;
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private void receiveContact() {
        for (int i = 0; i < MAX_RECEIVE_PER_UPDATE && contact != null; i++) {
            int n = receive(contact);
            if (n <= 0) {
                return;
            }
            packetsReceived++;
            reader.wrap(rx, 0, n);
            onContactReply();
        }
    }

    /**
     * {@code %u%c%c[%hu]}: magic, request answered, status, queue position or login port.
     * <p>
     * BEHAVIOR: replies without the XPilot magic are malformed. A CONTACT reply gives the version (too
     * old: closed) and starts the queue; ENTER_QUEUE success records the position; ENTER_GAME success
     * opens the login port and starts VERIFY; any error status closes with the C text.
     */
    private void onContactReply() {
        if (!reader.has(6)) {
            malformed++;
            return;
        }
        int magic = reader.s32();
        int to = reader.u8();
        int st = reader.u8();
        if ((magic & 0xFFFF) != XpProto.MAGIC_LOW) {
            malformed++;
            return;
        }
        lastReceive = now;
        if (st != XpProto.SUCCESS) {
            fail(XpProto.statusText(st, login.team));
            return;
        }
        if (phase == Phase.CONTACT && to == XpProto.CONTACT_PACK) {
            int v = XpProto.magicToVersion(magic);
            if (v < XpProto.MIN_SERVER_VERSION) {
                fail("Server " + label + " runs protocol 0x" + Integer.toHexString(v)
                    + "; at least 0x" + Integer.toHexString(XpProto.MIN_SERVER_VERSION) + " is needed");
                return;
            }
            version = Math.min(v, XpProto.MY_VERSION);
            enter(Phase.QUEUE, now);
            sendQueue();
            setState(ConnectionState.CONNECTING, "Joining " + label + "...");
            return;
        }
        if (phase != Phase.QUEUE || !reader.has(2)) {
            return;
        }
        int arg = reader.u16();
        if (to == XpProto.ENTER_QUEUE_PACK) {
            queuePos = arg;
            setState(ConnectionState.CONNECTING, "Queued at position " + arg + " on " + label);
        } else if (to == XpProto.ENTER_GAME_PACK && arg > 0) {
            queuePos = 0;
            loginPort = arg;
            try {
                player = opener.open(arg);
            } catch (IOException e) {
                fail("Cannot open socket to " + label + " port " + arg + ": " + e.getMessage());
                return;
            }
            enter(Phase.VERIFY, now);
            sendVerify();
        }
    }

    // ================================================================ login socket

    /** C {@code Net_verify}: {@code PKT_VERIFY %s%s%s} real name, nick, display. */
    private void sendVerify() {
        tries++;
        lastSend = now;
        XpWriter.u8(tx, XpProto.PKT_VERIFY);
        XpWriter.string(tx, login.realName, XpProto.MAX_CHARS);
        XpWriter.string(tx, nick(), XpProto.MAX_NAME_LEN);
        XpWriter.string(tx, login.display, XpProto.MAX_CHARS);
        transmit(player);
    }

    /**
     * C {@code Net_start}: shape (first {@value #SHAPE_TRIES} tries), PKT_PLAY, the six power
     * settings (value * 256), PKT_DISPLAY, the four PKT_MODIFIERBANK (C {@code Client_power}'s
     * {@code Send_modifier_bank}: {@code %c%c%s} bank, text), PKT_REQUEST_AUDIO (on iff {@link Login#audio}) and
     * PKT_ASYNC_FPS (the server rate).
     */
    private void sendPlay() {
        tries++;
        lastSend = now;
        if (tries <= SHAPE_TRIES) {
            // C Send_shape: Convert_shape_str (no limits; empty or invalid is the default ship), then
            // Convert_ship_2_string's buf, and its ext for servers newer than 3.2.
            ShipShape.WireParts w = ShipShape.convert(login.shape).toWireParts();
            XpWriter.u8(tx, XpProto.PKT_SHAPE);
            XpWriter.string(tx, w.buf(), XpProto.MSG_LEN);
            if (version > 0x3200) {
                XpWriter.string(tx, w.ext(), XpProto.MSG_LEN);
            }
        }
        XpWriter.u8(tx, XpProto.PKT_PLAY);
        writeSettings(login.settings);
        XpWriter.u8(tx, XpProto.PKT_DISPLAY);
        XpWriter.s16(tx, clampView(login.viewWidth));
        XpWriter.s16(tx, clampView(login.viewHeight));
        XpWriter.u8(tx, login.debrisColors);
        XpWriter.u8(tx, (int) Math.round(Math.max(0, Math.min(1, login.sparkProb)) * XpProto.MAX_SPARK_RAND));
        // C Client_power: Startup_server_motd (autoServerMotdPopup), in every play request: a C server still
        // draining the setup discards the whole first bundle ("Discarding packet 57"), only a retry gets through
        XpWriter.u8(tx, XpProto.PKT_MOTD);
        XpWriter.s32(tx, 0);
        XpWriter.s32(tx, XpProto.MAX_MOTD_SIZE);
        for (int i = 0; i < login.modifierBanks.length; i++) {
            XpWriter.u8(tx, XpProto.PKT_MODIFIERBANK);
            XpWriter.u8(tx, i);
            XpWriter.string(tx, login.modifierBanks[i], XpProto.MAX_CHARS);
        }
        XpWriter.u8(tx, XpProto.PKT_REQUEST_AUDIO);
        XpWriter.u8(tx, login.audio ? 1 : 0);
        XpWriter.u8(tx, XpProto.PKT_ASYNC_FPS);
        XpWriter.u8(tx, requestedFps(login.maxFps, fps()));
        transmit(player);
    }

    /**
     * The frame rate asked for (C {@code Check_client_fps}).
     * <p>
     * BEHAVIOR: maxFps 0 (JXPilot's default, a documented deviation) asks for the server's rate; otherwise maxFps
     * limited to serverFps / 2 .. serverFps, as C.
     */
    static int requestedFps(int maxFps, int serverFps) {
        return maxFps <= 0 ? serverFps : Math.max(serverFps / 2, Math.min(serverFps, maxFps));
    }

    /**
     * C {@code Send_display} after a resize or a scale change ({@code xinit.c} {@code Resize},
     * {@code configure.c}): PKT_DISPLAY with the view size (limited to C's 384..1024), the debris colours and the
     * spark chance.
     * <p>
     * BEHAVIOR: the size becomes the login's (sent with every later play request); while playing it is sent now
     * and the call returns true.
     */
    @Override
    public boolean sendDisplay(int viewWidth, int viewHeight, long nowNanos) {
        now = nowNanos;
        login.viewWidth = viewWidth;
        login.viewHeight = viewHeight;
        if (phase != Phase.PLAYING) {
            return false;
        }
        ensureRoom(7);
        XpWriter.u8(tx, XpProto.PKT_DISPLAY);
        XpWriter.s16(tx, clampView(login.viewWidth));
        XpWriter.s16(tx, clampView(login.viewHeight));
        XpWriter.u8(tx, login.debrisColors);
        XpWriter.u8(tx, (int) Math.round(Math.max(0, Math.min(1, login.sparkProb)) * XpProto.MAX_SPARK_RAND));
        flushPending();
        return true;
    }

    /**
     * C {@code Net_ask_for_motd(0, MAX_MOTD_SIZE)} (the MOTD menu entry with refreshMotd).
     * <p>
     * BEHAVIOR: while playing, PKT_MOTD for offset 0 and the whole size goes out and the call returns true.
     */
    @Override
    public boolean requestMotd(long nowNanos) {
        now = nowNanos;
        if (phase != Phase.PLAYING) {
            return false;
        }
        ensureRoom(9);
        XpWriter.u8(tx, XpProto.PKT_MOTD);
        XpWriter.s32(tx, 0);
        XpWriter.s32(tx, XpProto.MAX_MOTD_SIZE);
        flushPending();
        return true;
    }

    /**
     * C {@code Send_fps_request} after a maxFPS change ({@code configure.c} {@code Config_update_maxFPS}).
     * <p>
     * BEHAVIOR: maxFps becomes the login's; while playing PKT_ASYNC_FPS with {@link #requestedFps} goes out and the
     * call returns true.
     */
    @Override
    public boolean sendFps(int maxFps, long nowNanos) {
        now = nowNanos;
        login.maxFps = maxFps;
        if (phase != Phase.PLAYING) {
            return false;
        }
        ensureRoom(2);
        XpWriter.u8(tx, XpProto.PKT_ASYNC_FPS);
        XpWriter.u8(tx, requestedFps(maxFps, fps()));
        flushPending();
        return true;
    }

    private void power(int type, double v) {
        XpWriter.u8(tx, type);
        XpWriter.s16(tx, (int) (v * 256.0));
    }

    /** BEHAVIOR: C {@code LIMIT(MIN_VIEW_SIZE, MAX_VIEW_SIZE)}. */
    static int clampView(int v) {
        return Math.max(XpProto.MIN_VIEW_SIZE, Math.min(XpProto.MAX_VIEW_SIZE, v));
    }

    private void receivePlayer() {
        for (int i = 0; i < MAX_RECEIVE_PER_UPDATE && player != null && phase != Phase.CLOSED; i++) {
            int n = receive(player);
            if (n <= 0) {
                return;
            }
            packetsReceived++;
            lastReceive = now;
            noResponse = 0;
            onDatagram(n);
        }
    }

    /**
     * One datagram from the login port: a sequence of packets (C {@code Net_packet}).
     * <p>
     * BEHAVIOR: PKT_RELIABLE chunks feed the stream and are acked; PKT_QUIT closes with its reason;
     * everything else is a frame packet once the map exists. A truncated packet, a bad reliable chunk
     * or a premature keyboard ack counts as malformed, an unknown type as unknown, an old frame as
     * stale; each drops the rest of the datagram (as C). A completed frame becomes a snapshot.
     */
    private void onDatagram(int n) {
        reader.wrap(rx, 0, n);
        while (reader.has(1) && phase != Phase.CLOSED) {
            int type = reader.peekU8();
            if (type == XpProto.PKT_RELIABLE) {
                if (!onReliableChunk()) {
                    return;
                }
                continue;
            }
            if (type == XpProto.PKT_QUIT) {
                reader.skip(1);
                int len = reader.string(scratch, XpProto.MAX_CHARS);
                if (len <= 0) {
                    scratch.setLength(0);
                    scratch.append("unknown reason");
                }
                fail(XpProto.quitText(scratch));
                return;
            }
            if (built == null) {
                unknown++;
                return;
            }
            int res = frames.decode(reader, frame);
            if (res != XpFrameDecoder.OK) {
                switch (res) {
                    case XpFrameDecoder.STALE -> stale++;
                    case XpFrameDecoder.UNKNOWN -> unknown++;
                    default -> malformed++;
                }
                return;
            }
            if (frame.ended && frame.started) {
                frame.started = false;
                framesDone++;
                newFrame = true;
                translator.frame(frame, now);
            }
        }
    }

    /** BEHAVIOR: false when the rest of the datagram must be dropped. */
    private boolean onReliableChunk() {
        int res = reliable.receive(reader);
        switch (res) {
            case XpReliableReceiver.APPENDED, XpReliableReceiver.DUPLICATE, XpReliableReceiver.GAP -> {
                ensureRoom(9);
                reliable.writeAck(tx);
                if (res == XpReliableReceiver.APPENDED) {
                    drainStream();
                }
                return true;
            }
            case XpReliableReceiver.OVERFLOW -> {
                malformed++;
                return true;
            }
            default -> {
                malformed++;
                return false;
            }
        }
    }

    /**
     * Consume complete items from the reliable stream according to the phase.
     * <p>
     * BEHAVIOR: SETUP reads the setup header, then map bytes; a complete map is built and the play
     * request starts. Other phases decode reliable packets; a bad packet (C fatal) closes the
     * connection.
     */
    private void drainStream() {
        while (phase != Phase.CLOSED && reliable.length() > 0) {
            streamReader.wrap(reliable.data(), 0, reliable.length());
            int used;
            if (phase == Phase.SETUP) {
                used = setup.haveHeader() ? setup.readMap(streamReader) : setup.readHeader(streamReader);
                if (used < 0) {
                    badStream++;
                    malformed++;
                    fail("Bad setup from " + label + ": " + setup.error());
                    return;
                }
                if (used > 0) {
                    reliable.consume(used);
                }
                if (setup.complete()) {
                    buildMap();
                    continue;
                }
            } else {
                used = events.decode(streamReader);
                if (used == XpReliableDecoder.BAD) {
                    badStream++;
                    malformed++;
                    fail("Bad reliable data (type " + events.lastBad() + ") from " + label);
                    return;
                }
                if (used > 0) {
                    reliable.consume(used);
                }
            }
            if (used == 0) {
                return;
            }
        }
    }

    private void buildMap() {
        try {
            built = XpMap.build(setup);
        } catch (RuntimeException e) {
            malformed++;
            fail("Bad map from " + label + ": " + e.getMessage());
            return;
        }
        translator.configure(built, setup.mode, version);
        frames.setMap(setup.width, setup.height);
        events.setVersion(version);
        welcome.playerId = Protocol.NO_ID;
        welcome.team = login.team;
        welcome.simHz = Math.max(1, setup.fps);
        welcome.snapshotHz = welcome.simHz;
        welcome.gameFps = welcome.simHz;
        welcome.flags = ((setup.mode & XpProto.MODE_TEAM_PLAY) != 0 ? Msg.Welcome.FLAG_TEAM_PLAY : 0)
            | ((setup.mode & XpProto.MODE_WRAP_PLAY) != 0 ? Msg.Welcome.FLAG_EDGE_WRAP : 0)
            | ((setup.mode & XpProto.MODE_TIMING) != 0 ? Msg.Welcome.FLAG_TIMING : 0)
            | Msg.Welcome.FLAG_RADAR_BLINKED;
        welcome.serverName.setLength(0);
        welcome.serverName.append(label);
        listener.onWelcome(welcome);
        listener.onConfig(welcome, built.options(), built.map());
        enter(Phase.START, now);
        sendPlay();
    }

    /** Connection-level reliable packets. */
    private final class Control implements XpReliableDecoder.Control {
        /**
         * BEHAVIOR: VERIFY success waits for the magic, failure closes; PLAY success starts playing
         * (the next PKT_PLAYER is us; the keyboard is announced), failure closes; other replies are
         * ignored (C only prints them).
         */
        @Override
        public void reply(int to, int result) {
            if (to == XpProto.PKT_VERIFY && phase == Phase.VERIFY) {
                if (result != XpProto.PKT_SUCCESS) {
                    fail("Verification failed by " + label + " (" + result + ")");
                    return;
                }
                verified = true;
            } else if (to == XpProto.PKT_PLAY && phase == Phase.START) {
                if (result != XpProto.PKT_SUCCESS) {
                    fail("Start play not allowed by " + label + " (" + result + ")");
                    return;
                }
                enter(Phase.PLAYING, now);
                translator.expectSelf();
                keyboard.forceChange();
                setState(ConnectionState.PLAYING, "Playing on " + label);
                sendDatagram(true);
            }
        }

        /** BEHAVIOR: after a successful verify the magic ends VERIFY: the setup follows. */
        @Override
        public void magic(int m) {
            if (phase == Phase.VERIFY && verified) {
                connMagic = m;
                if (contact != null) {
                    contact.close();
                    contact = null;
                }
                listener.onAccepted(Protocol.NO_ID);
                enter(Phase.SETUP, now);
                setState(ConnectionState.JOINING, "Receiving map from " + label + "...");
            }
        }

        @Override
        public void quit(CharSequence reason) {
            fail(XpProto.quitText(reason));
        }

        @Override
        public void talkAck(int seq) {
            talk.onAck(seq, frames.lastLoops());
        }
    }

    // ================================================================ sending

    private void ensureRoom(int n) {
        if (tx.remaining() < n + RESERVE) {
            sendDatagram(false);
        }
    }

    /** BEHAVIOR: sends what is queued in the buffer, plus a due talk line or an unacked keyboard. */
    private void flushPending() {
        if (player == null) {
            return;
        }
        boolean talkDue = phase == Phase.PLAYING && talk.due(frames.lastLoops());
        if (tx.position() > 0 || talkDue) {
            sendDatagram(false);
        }
    }

    /**
     * One datagram to the login port (C {@code Net_flush} / {@code Send_keyboard}).
     * <p>
     * BEHAVIOR: while PLAYING the keyboard is added when asked or when its last change is unacked, and
     * a due talk line; at most one keyboard per datagram (the server kicks "macros").
     */
    private void sendDatagram(boolean withKeyboard) {
        if (player == null) {
            tx.clear();
            return;
        }
        int loops = frames.lastLoops();
        if (phase == Phase.PLAYING) {
            if (withKeyboard || keyboard.unacked()) {
                keyboard.write(tx, loops);
            }
            if (talk.due(loops) && tx.remaining() >= talk.size()) {
                talk.write(tx, loops);
            }
            lastSendAnything = loops;
        }
        if (tx.position() > 0) {
            transmit(player);
        }
        tx.clear();
    }

    private void transmit(Transport t) {
        tx.flip();
        try {
            if (t.send(tx)) {
                packetsSent++;
            }
        } catch (IOException e) {
            ioErrors++;
        }
        tx.clear();
    }

    private int receive(Transport t) {
        rx.clear();
        try {
            return t.receive(rx);
        } catch (IOException e) {
            ioErrors++;
            return 0;
        }
    }

    /** Counters for diagnostics: stream errors (C fatal). */
    public long badStream() { return badStream; }
}
