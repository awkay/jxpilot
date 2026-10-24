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

import com.fulcrologic.jxpilot.client.net.Transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Scripted stand-in for the C XPilot 4.5.5 server (contact.c + netserver.c + frame.c), speaking through
 * in-memory transports. Packets are built with {@link CPackets} (the server's encoders). It follows
 * the server's connection states: contact/queue on the contact port, then LISTENING (verify), DRAIN
 * and SETUP (map through the reliable stream), LOGIN (play request), READY/PLAYING (frames with
 * piggybacked reliable data). Anything the real server would reject (undefined packet in a state, two
 * keyboard packets in one datagram, acks of unsent data) is recorded in {@link #violations}.
 * Allocates freely (tests only).
 */
final class FakeCServer implements XpConnection.PortOpener {
    static final int CONTACT_PORT = 15345;
    static final int LOGIN_PORT = 15346;
    static final int SELF_ID = 3;
    static final int ROBOT_ID = 1;

    enum State { LISTENING, DRAIN_SETUP, SETUP, DRAIN_LOGIN, LOGIN, READY, PLAYING, CLOSED }

    /** One end of an in-memory datagram pipe; the client side of a port. */
    final class Pipe implements Transport {
        final int port;
        final ArrayDeque<byte[]> toClient = new ArrayDeque<>();
        final ArrayDeque<byte[]> toServer = new ArrayDeque<>();
        boolean closed;

        Pipe(int port) {
            this.port = port;
        }

        @Override
        public boolean send(ByteBuffer p) {
            byte[] a = new byte[p.remaining()];
            p.get(a);
            if (dropToServer > 0) {
                dropToServer--;
                return true;
            }
            toServer.add(a);
            clientSent++;
            return true;
        }

        @Override
        public int receive(ByteBuffer b) {
            byte[] a = toClient.poll();
            if (a == null) {
                return 0;
            }
            b.put(a);
            return a.length;
        }

        @Override
        public void close() {
            closed = true;
        }

        void deliver(byte[] a) {
            if (dropToClient > 0) {
                dropToClient--;
                return;
            }
            toClient.add(a);
        }
    }

    // ---------------------------------------------------------------- script knobs
    int version = XpProto.MY_VERSION;
    int contactStatus = XpProto.SUCCESS;
    int queueStatus = XpProto.SUCCESS;
    int queueSteps = 1;
    int verifyResult = XpProto.PKT_SUCCESS;
    int playResult = XpProto.PKT_SUCCESS;
    boolean silentContact;
    boolean silentLogin;
    boolean silentFrames;
    boolean ignorePlay;
    /** Send a fuel station update (acked by the client) in every frame. */
    boolean mapUpdates = true;
    /** Replaces the setup header + map bytes when set. */
    byte[] customSetup;
    int chunk = 300;
    int retransmitSteps = 5;
    int dropToClient;
    int dropToServer;
    String[] rows = XpTranslatorTest.rows();
    int mode = XpProto.MODE_WRAP_PLAY;
    int fps = 12;
    IOException openFailure;

    // ---------------------------------------------------------------- recorded
    final List<String> violations = new ArrayList<>();
    final List<String> talk = new ArrayList<>();
    final List<String> contactRequests = new ArrayList<>();
    final List<Integer> keyboardChanges = new ArrayList<>();
    final List<String> loginPackets = new ArrayList<>();
    /** Every PKT_SHAPE received: its shape string and extension (C Receive_shape, version > 0x3200). */
    final List<List<String>> shapes = new ArrayList<>();
    long lastKeys;
    int lastKeyChange;
    /** Every PKT_KEYBOARD received, with the server frame it arrived in. */
    final List<Integer> keyboardFrames = new ArrayList<>();
    int verifies;
    int plays;
    int quits;
    int mapAcks;
    int clientSent;
    int framesSent;
    String verifiedNick;
    String nick;
    int team = -1;
    int queueVersion;

    final List<Pipe> opened = new ArrayList<>();
    Pipe contact;
    Pipe login;
    State state = State.LISTENING;
    int queueWait;
    boolean admitted;
    int loops = 100;
    int talkSeq;
    int shipX = 1000;
    int shipY = 1000;
    int robotX = 1100;

    // reliable stream (connp->c)
    final ByteBuffer stream = ByteBuffer.allocate(256 * 1024);
    int streamBase;
    int acked;
    int sentUpTo;
    int stepsSinceAck;
    int setupEnd;
    int replyEnd;

    @Override
    public Transport open(int port) throws IOException {
        if (openFailure != null) {
            throw openFailure;
        }
        Pipe p = new Pipe(port);
        opened.add(p);
        if (port == CONTACT_PORT) {
            contact = p;
        } else if (port == LOGIN_PORT) {
            login = p;
        } else {
            violations.add("opened unknown port " + port);
        }
        return p;
    }

    /** Process everything the client sent, then emit this step's datagrams. */
    void step() {
        if (contact != null) {
            for (byte[] a = contact.toServer.poll(); a != null; a = contact.toServer.poll()) {
                onContact(a);
            }
            if (!admitted && nick != null && queueStatus == XpProto.SUCCESS && ++queueWait >= queueSteps) {
                admitted = true;
                contactReply(XpProto.ENTER_GAME_PACK, XpProto.SUCCESS, LOGIN_PORT);
            }
        }
        if (login != null) {
            for (byte[] a = login.toServer.poll(); a != null; a = login.toServer.poll()) {
                onLogin(a);
            }
            emit();
        }
    }

    // ---------------------------------------------------------------- contact.c

    private void contactReply(int to, int status, int arg) {
        if (silentContact) {
            return;
        }
        contact.deliver(new CPackets().contactReply(XpProto.versionToMagic(version), to, status, arg).bytes());
    }

    private void onContact(byte[] a) {
        XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
        int magic = r.s32();
        StringBuilder sb = new StringBuilder();
        r.string(sb, XpProto.MAX_CHARS);
        r.u16();
        int to = r.u8();
        contactRequests.add(to + " v" + Integer.toHexString(XpProto.magicToVersion(magic)) + " real=" + sb);
        if (to == XpProto.CONTACT_PACK) {
            contactReply(to, contactStatus, -1);
        } else if (to == XpProto.ENTER_QUEUE_PACK) {
            queueVersion = XpProto.magicToVersion(magic);
            StringBuilder n = new StringBuilder();
            r.string(n, XpProto.MAX_CHARS);
            r.string(new StringBuilder(), XpProto.MAX_CHARS);
            r.string(new StringBuilder(), XpProto.MAX_CHARS);
            team = r.s32();
            if (nick != null) {
                return; // Queue_player: same nick still queued, no reply
            }
            nick = n.toString();
            contactReply(to, queueStatus, queueStatus == XpProto.SUCCESS ? 1 : -1);
        } else {
            violations.add("contact request " + to);
        }
    }

    // ---------------------------------------------------------------- netserver.c

    private void streamAppend(CPackets p) {
        stream.put(p.bytes());
    }

    private void onLogin(byte[] a) {
        XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
        if (state == State.LISTENING) {
            if (r.u8() != XpProto.PKT_VERIFY) {
                violations.add("first packet is not VERIFY");
                return;
            }
            StringBuilder real = new StringBuilder();
            StringBuilder nk = new StringBuilder();
            r.string(real, XpProto.MAX_CHARS);
            r.string(nk, XpProto.MAX_CHARS);
            verifies++;
            verifiedNick = nk.toString();
            streamAppend(new CPackets().reply(XpProto.PKT_VERIFY, verifyResult));
            if (verifyResult == XpProto.PKT_SUCCESS) {
                streamAppend(new CPackets().magic(0x1234ABCD));
                byte[] blocks = CPackets.setupBlocks(rows, rows[0].length(), rows.length,
                    (mode & XpProto.MODE_TEAM_PLAY) != 0, (mode & XpProto.MODE_TIMING) != 0);
                byte[] data = CPackets.compress(blocks);
                if (customSetup != null) {
                    stream.put(customSetup);
                } else {
                    streamAppend(new CPackets().setupHeader(data.length, mode, 0, rows[0].length(), rows.length, fps,
                        XpProto.SETUP_MAP_ORDER_XY, "Fake Map", "Fake Author").raw(data));
                }
                setupEnd = stream.position();
                state = State.DRAIN_SETUP;
            }
            return;
        }
        int keyboards = 0;
        while (r.has(1)) {
            int type = r.u8();
            if (type == XpProto.PKT_ACK) {
                int rel = r.s32();
                r.s32();
                if (rel > sentUpTo) {
                    violations.add("ack of unsent data " + rel + " > " + sentUpTo);
                }
                if (rel > acked) {
                    acked = rel;
                    stepsSinceAck = 0;
                }
                if (state == State.DRAIN_SETUP && acked >= streamBase + 8) {
                    state = State.SETUP;
                }
                if (state == State.SETUP && acked >= setupEnd) {
                    state = State.LOGIN;
                }
                if (state == State.READY && acked >= replyEnd) {
                    state = State.PLAYING;
                }
                continue;
            }
            if (type == XpProto.PKT_QUIT) {
                quits++;
                state = State.CLOSED;
                return;
            }
            boolean playing = state == State.READY || state == State.PLAYING;
            if (state == State.DRAIN_SETUP || state == State.SETUP || state == State.DRAIN_LOGIN) {
                if (type == XpProto.PKT_VERIFY || type == XpProto.PKT_PLAY || type == XpProto.PKT_SHAPE) {
                    if (type == XpProto.PKT_VERIFY) {
                        verifies++;
                    }
                    return; // Receive_discard: rest of the datagram dropped
                }
                violations.add("packet " + type + " during setup");
                return;
            }
            switch (type) {
                case XpProto.PKT_VERIFY -> {
                    verifies++;
                    return;
                }
                case XpProto.PKT_SHAPE -> {
                    StringBuilder s = new StringBuilder();
                    StringBuilder ext = new StringBuilder();
                    r.string(s, XpProto.MSG_LEN);
                    r.string(ext, XpProto.MSG_LEN);
                    shapes.add(List.of(s.toString(), ext.toString()));
                    loginPackets.add("shape");
                }
                case XpProto.PKT_PLAY -> {
                    plays++;
                    loginPackets.add("play");
                    if (state == State.LOGIN && !ignorePlay) {
                        streamAppend(new CPackets().reply(XpProto.PKT_PLAY, playResult));
                        if (playResult == XpProto.PKT_SUCCESS) {
                            replyEnd = stream.position();
                            CPackets p = new CPackets().player(SELF_ID, 255, ' ', verifiedNick, "real", "host", "", "");
                            score(p, SELF_ID, 0).base(SELF_ID, 0)
                                .player(ROBOT_ID, 255, 'R', "Robby", "robot", "host", "", "");
                            score(p, ROBOT_ID, 12.5).base(ROBOT_ID, 1)
                                .message("Welcome to \"Fake Map\", made by Fake Author.");
                            streamAppend(p);
                            state = State.READY;
                        }
                    }
                }
                case XpProto.PKT_POWER, XpProto.PKT_POWER_S, XpProto.PKT_TURNSPEED, XpProto.PKT_TURNSPEED_S,
                     XpProto.PKT_TURNRESISTANCE, XpProto.PKT_TURNRESISTANCE_S -> {
                    loginPackets.add("power" + type + "=" + r.s16());
                }
                case XpProto.PKT_DISPLAY -> {
                    loginPackets.add("display " + r.s16() + "x" + r.s16() + " colors=" + r.u8() + " spark=" + r.u8());
                }
                case XpProto.PKT_MODIFIERBANK -> {
                    int bank = r.u8();
                    StringBuilder s = new StringBuilder();
                    r.string(s, XpProto.MAX_CHARS);
                    loginPackets.add("modbank " + bank + "=" + s);
                }
                case XpProto.PKT_REQUEST_AUDIO -> loginPackets.add("audio " + r.u8());
                case XpProto.PKT_ASYNC_FPS -> loginPackets.add("fps " + r.u8());
                case XpProto.PKT_KEYBOARD -> {
                    if (!playing) {
                        violations.add("keyboard in state " + state);
                        return;
                    }
                    if (++keyboards > 1) {
                        violations.add("no macros: two keyboards in one datagram");
                    }
                    int change = r.s32();
                    keyboardFrames.add(loops);
                    long keys = 0;
                    for (int i = 0; i < XpProto.KEYBOARD_SIZE; i++) {
                        keys |= (long) r.u8() << (8 * i);
                    }
                    if (change > lastKeyChange) {
                        lastKeyChange = change;
                        lastKeys = keys >>> 1;
                        keyboardChanges.add(change);
                    }
                }
                case XpProto.PKT_TALK -> {
                    if (!playing) {
                        violations.add("talk in state " + state);
                        return;
                    }
                    int seq = r.s32();
                    StringBuilder s = new StringBuilder();
                    r.string(s, XpProto.MAX_CHARS);
                    if (seq > talkSeq) {
                        talkSeq = seq;
                        talk.add(s.toString());
                        streamAppend(new CPackets().talkAck(seq).message(s + " [" + verifiedNick + "]"));
                    }
                }
                case XpProto.PKT_ACK_CANNON, XpProto.PKT_ACK_FUEL, XpProto.PKT_ACK_TARGET -> {
                    if (!playing) {
                        violations.add("map ack in state " + state);
                    }
                    r.s32();
                    r.u16();
                    mapAcks++;
                }
                default -> {
                    violations.add("undefined packet " + type + " in state " + state);
                    return;
                }
            }
        }
    }

    /** Send_score in the client version's format. */
    private CPackets score(CPackets p, int id, double sc) {
        return version >= 0x4500 ? p.score(id, sc, 0, id == ROBOT_ID ? 'R' : ' ', ' ')
            : p.scoreOld(id, sc, 0, id == ROBOT_ID ? 'R' : ' ');
    }

    /** Send_reliable: new data after sentUpTo, or a retransmission from the acked offset. */
    private byte[] reliableChunk(int maxLen) {
        int end = stream.position();
        int from = sentUpTo;
        if (stepsSinceAck >= retransmitSteps && acked < sentUpTo) {
            from = acked;
            stepsSinceAck = 0;
        }
        if (from >= end) {
            return null;
        }
        int len = Math.min(maxLen, end - from);
        byte[] data = new byte[len];
        stream.get(from, data);
        sentUpTo = Math.max(sentUpTo, from + len);
        return new CPackets().reliable(from, loops, data).bytes();
    }

    private void emit() {
        if (state == State.CLOSED) {
            return;
        }
        stepsSinceAck++;
        if (state == State.PLAYING && !silentFrames) {
            loops++;
            framesSent++;
            CPackets p = new CPackets().start(loops, lastKeyChange)
                .self(shipX, shipY, 0, 0, 32, 45, 35, 30, -1, 0, 0, 0, 0, 1000, 2600, 1024, 1024, 0,
                    XpProto.STATUS_PLAYING, 0)
                .ship(shipX, shipY, SELF_ID, 32).ship(robotX + 2 * framesSent, 1000, ROBOT_ID, 0);
            if (mapUpdates) {
                p.fuel(0, 500 << 8);
            }
            p.debris(XpProto.RED * 16 + 5, 10, 20, 30, 40)
                .fastshot(XpProto.WHITE * 16 + 5, 50, 60)
                .fastRadar(10, 20, 3)
                .end(loops);
            byte[] rel = reliableChunk(512);
            if (rel != null) {
                p.raw(rel);
            }
            login.deliver(p.bytes());
            return;
        }
        if (silentLogin) {
            return;
        }
        byte[] rel = reliableChunk(chunk);
        if (rel != null) {
            login.deliver(rel);
        }
    }
}
