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
import com.fulcrologic.jxpilot.common.net.InputHistory;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.ReliableHandler;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A scripted client built only from common's codecs: produces datagrams (fresh heap buffers) and
 * consumes the server's, recording what it learned. Not allocation free; test use only.
 */
final class TestClient implements ReliableHandler {
    final ReliableChannel ch = new ReliableChannel();
    final InputHistory inputs = new InputHistory();
    final PacketHeader header = new PacketHeader();
    final SnapshotReader snap = new SnapshotReader();
    final Records.Self self = new Records.Self();
    final int nonce;
    int session;
    int playerId = -1;
    boolean accepted;
    int rejectReason = -1;
    int rejectNonce = -1;
    String rejectText;
    int rejectPosition;
    int disconnectReason = -1;
    int disconnects;
    String disconnectText;
    int lastRadarCellX = -1;
    final Msg.Welcome welcome = new Msg.Welcome();
    boolean welcomed;
    ConfigTransfer.Receiver receiver;
    String configText;
    final List<String> reliableLog = new ArrayList<>();
    final List<Integer> snapshotTicks = new ArrayList<>();
    /** The snapshot header's inputTick and inputSlack of each part 0. */
    final List<Integer> snapshotInputTicks = new ArrayList<>();
    final List<Integer> snapshotInputSlacks = new ArrayList<>();
    final List<Boolean> snapshotStarved = new ArrayList<>();
    final List<String> snapshotParts = new ArrayList<>();
    int radarSnapshots;
    float lastSelfX;
    float lastSelfY;
    int pongs;
    long lastPongNanos;
    int lastAckTick;
    /** The server tick the next input is for, minus 1 ({@link Harness} points this at the server). */
    java.util.function.IntSupplier serverTick = () -> lastAckTick + 1;
    /** How many ticks after the server's current tick {@link #input(long)}'s entry is for. */
    int lead = 1;
    /** The inputTick of the newest snapshot: the INPUT redundancy covers the ticks after it. */
    int lastInputTick;
    boolean readySent;
    /** Session packets dropped because they arrived before ACCEPT or carry another session. */
    int staleSession;

    TestClient(int nonce) {
        this.nonce = nonce;
    }

    private static ByteBuffer buf() {
        return ByteBuffer.allocate(Protocol.MAX_PACKET);
    }

    ByteBuffer connect(String name, int team, String shape) {
        return connect(name, team, shape, "", "");
    }

    /** A CONNECT with the real and host names (server-batch2). */
    ByteBuffer connect(String name, int team, String shape, String realName, String hostName) {
        ByteBuffer b = buf();
        PacketHeader.write(b, PacketType.CONNECT, 0, 0);
        Msg.Connect c = new Msg.Connect();
        c.nonce = nonce;
        c.team = team;
        c.name.append(name);
        c.shape.append(shape);
        c.realName.append(realName);
        c.hostName.append(hostName);
        c.write(b);
        return b.flip();
    }

    /** An INPUT holding {@code keys} for the server's tick {@link #lead} ahead. */
    ByteBuffer input(long keys) {
        return input(serverTick.getAsInt() + lead, keys, 0);
    }

    /** An INPUT with the entry for {@code tick} (and the redundant older ones the server has not applied). */
    ByteBuffer input(int tick, long keys, long taps) {
        inputs.set(tick, keys, taps, 0);
        ByteBuffer b = buf();
        PacketHeader.write(b, PacketType.INPUT, session, ch.ack());
        inputs.write(b, lastInputTick);
        return b.flip();
    }

    ByteBuffer bare(int type) {
        ByteBuffer b = buf();
        PacketHeader.write(b, type, session, ch.ack());
        return b.flip();
    }

    ByteBuffer ping(int id, long nanos) {
        ByteBuffer b = buf();
        PacketHeader.write(b, PacketType.PING, session, ch.ack());
        Msg.Ping p = new Msg.Ping();
        p.id = id;
        p.timeNanos = nanos;
        p.write(b);
        return b.flip();
    }

    ByteBuffer disconnect() {
        ByteBuffer b = buf();
        PacketHeader.write(b, PacketType.DISCONNECT, session, ch.ack());
        Msg.Disconnect d = new Msg.Disconnect();
        d.reason = Msg.Disconnect.REASON_QUIT;
        d.write(b);
        return b.flip();
    }

    void ready() {
        readySent = true;
        Msg.sendReady(ch);
    }

    void chat(String s) {
        Msg.Text t = new Msg.Text();
        t.text.append(s);
        t.sendChat(ch);
    }

    /** The next RELIABLE datagram, or null when nothing is due. */
    ByteBuffer reliable(long now) {
        if (!ch.wantsSend(now)) {
            return null;
        }
        ByteBuffer b = buf();
        PacketHeader.write(b, PacketType.RELIABLE, session, ch.ack());
        if (ch.write(b, now) == 0) {
            return null;
        }
        return b.flip();
    }

    /** Everything the client would send this frame: due reliable data, then an input. */
    List<ByteBuffer> frame(long now, long keys) {
        List<ByteBuffer> out = new ArrayList<>();
        for (ByteBuffer r = reliable(now); r != null; r = reliable(now)) {
            out.add(r);
        }
        if (accepted) {
            out.add(input(keys));
        }
        return out;
    }

    boolean hasMap() {
        return configText != null;
    }

    void onPacket(ByteBuffer data, long now) {
        if (!header.read(data)) {
            throw new AssertionError("server sent a bad header");
        }
        switch (header.type()) {
            case PacketType.ACCEPT -> {
                Msg.Accept a = new Msg.Accept();
                a.read(data);
                if (a.nonce == nonce && !accepted) {
                    accepted = true;
                    session = header.session();
                    playerId = a.playerId;
                }
            }
            case PacketType.REJECT -> {
                Msg.Reject r = new Msg.Reject();
                r.read(data);
                rejectReason = r.reason;
                rejectNonce = r.nonce;
                rejectText = r.text.toString();
                rejectPosition = r.position;
            }
            case PacketType.DISCONNECT -> {
                Msg.Disconnect d = new Msg.Disconnect();
                d.read(data);
                disconnectReason = d.reason;
                disconnectText = d.text.toString();
                disconnects++;
            }
            case PacketType.PONG -> {
                Msg.Ping p = new Msg.Ping();
                p.read(data);
                pongs++;
                lastPongNanos = p.timeNanos;
            }
            default -> {
                if (!accepted || header.session() != session) {
                    staleSession++;
                    return;
                }
                ch.onAck(header.reliableAck(), now);
                if (header.type() == PacketType.RELIABLE) {
                    if (ch.read(data, this) < 0) {
                        throw new AssertionError("malformed RELIABLE from server");
                    }
                } else if (header.type() == PacketType.SNAPSHOT) {
                    onSnapshot(data);
                }
            }
        }
    }

    private void onSnapshot(ByteBuffer data) {
        if (!snap.begin(data)) {
            throw new AssertionError("malformed snapshot");
        }
        snapshotParts.add(snap.tick() + ":" + snap.part() + "/" + snap.partCount() + ((snap.flags() & SnapshotWriter.FLAG_LAST_PART) != 0 ? "L" : ""));
        if (snap.part() == 0) {
            snapshotTicks.add(snap.tick());
            snapshotInputTicks.add(snap.inputTick());
            snapshotInputSlacks.add(snap.inputSlack());
            snapshotStarved.add((snap.flags() & SnapshotWriter.FLAG_INPUT_STARVED) != 0);
            lastInputTick = snap.inputTick();
            lastAckTick = snap.tick();
        }
        while (snap.nextSection()) {
            if (snap.type() == Section.SELF && snap.count() > 0) {
                self.wrap(snap, 0);
                lastSelfX = self.x();
                lastSelfY = self.y();
            } else if (snap.type() == Section.RADAR && snap.part() == 0) {
                radarSnapshots++;
                lastRadarCellX = new Records.Radar().wrap(snap, 0).cellX();
            }
        }
    }

    @Override
    public void onReliableMessage(int type, ByteBuffer payload) {
        switch (type) {
            case ReliableType.WELCOME -> {
                welcome.read(payload);
                welcomed = true;
                receiver = new ConfigTransfer.Receiver(welcome);
                reliableLog.add("WELCOME " + welcome.playerId);
                if (welcome.configCompressedLength == 0) {
                    configText = receiver.text();
                }
            }
            case ReliableType.DATA_CHUNK -> {
                if (receiver.onChunk(payload)) {
                    configText = receiver.text();
                    reliableLog.add("MAP");
                }
            }
            case ReliableType.PLAYER_JOIN -> {
                Msg.PlayerJoin j = new Msg.PlayerJoin();
                j.read(payload);
                reliableLog.add("JOIN " + j.id + ((j.flags & Msg.PlayerJoin.FLAG_SELF) != 0 ? " self" : ""));
            }
            case ReliableType.PLAYER_LEAVE -> {
                Msg.PlayerLeave l = new Msg.PlayerLeave();
                l.read(payload);
                reliableLog.add("LEAVE " + l.id);
            }
            case ReliableType.MESSAGE -> {
                Msg.Text t = new Msg.Text();
                t.read(payload);
                reliableLog.add("MESSAGE " + t.text);
            }
            default -> reliableLog.add("TYPE " + type);
        }
    }
}
