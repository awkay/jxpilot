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

import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.net.LobbyMsg;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A scripted lobby server for client tests: a {@link Transport} that records what the client sends and hands it
 * the datagrams a test queues, with builders for the lobby server's datagrams and decoders for the client's.
 */
public final class LobbyWire implements Transport {
    public final List<byte[]> sent = new ArrayList<>();
    public final ArrayDeque<byte[]> inbox = new ArrayDeque<>();

    @Override
    public boolean send(ByteBuffer packet) {
        byte[] b = new byte[packet.remaining()];
        packet.get(b);
        sent.add(b);
        return true;
    }

    @Override
    public int receive(ByteBuffer buf) {
        byte[] b = inbox.pollFirst();
        if (b == null) {
            return 0;
        }
        buf.put(b);
        return b.length;
    }

    @Override
    public void close() { }

    /** Queue a datagram for the client. */
    public LobbyWire tell(byte[] datagram) {
        inbox.addLast(datagram);
        return this;
    }

    // ---- server datagrams

    static byte[] dg(int type, Consumer<ByteBuffer> body) {
        ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
        PacketHeader.write(b, type, 0, 0);
        body.accept(b);
        b.flip();
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    public static byte[] info(int nonce, long cookie, String name) {
        LobbyMsg.Info i = new LobbyMsg.Info();
        i.nonce = nonce;
        i.cookie = cookie;
        i.name.append(name);
        return dg(PacketType.LOBBY_INFO, i::write);
    }

    /** All the pages of a list, as separate datagrams. */
    public static List<byte[]> list(int version, List<LobbyEntry> entries, int maxBody) {
        List<byte[]> out = new ArrayList<>();
        for (ByteBuffer page : LobbyMsg.ListPage.encode(version, entries, maxBody)) {
            out.add(dg(PacketType.LOBBY_LIST, b -> b.put(page)));
        }
        return out;
    }

    public static byte[] join(int mapId, int result, String text) {
        LobbyMsg.Join j = new LobbyMsg.Join();
        j.mapId = mapId;
        j.result = result;
        j.text.append(text);
        return dg(PacketType.LOBBY_JOIN, j::write);
    }

    public static byte[] rejectLobby(int nonce) {
        return dg(PacketType.REJECT, b -> {
            Msg.Reject r = new Msg.Reject();
            r.nonce = nonce;
            r.reason = Msg.Reject.REASON_LOBBY;
            r.text.append("lobby");
            r.write(b);
        });
    }

    public static LobbyEntry entry(int id, String name, boolean active, int humans) {
        return new LobbyEntry(id, name, "Title of " + name, "Author", 40, 40, 10, active, humans);
    }

    // ---- what the client sent

    public static int type(byte[] p) {
        return p[3] & 0xFF;
    }

    public List<byte[]> sentOf(int type) {
        return sent.stream().filter(p -> type(p) == type).toList();
    }

    /** The body of a sent datagram, positioned after the header. */
    public static ByteBuffer body(byte[] p) {
        ByteBuffer b = ByteBuffer.wrap(p);
        b.position(PacketHeader.SIZE);
        return b;
    }

    public static LobbyMsg.Hello hello(byte[] p) {
        LobbyMsg.Hello h = new LobbyMsg.Hello();
        h.read(body(p));
        return h;
    }

    public static LobbyMsg.ListRequest request(byte[] p) {
        LobbyMsg.ListRequest r = new LobbyMsg.ListRequest();
        r.read(body(p));
        return r;
    }

    public static LobbyMsg.Select select(byte[] p) {
        LobbyMsg.Select s = new LobbyMsg.Select();
        s.read(body(p));
        return s;
    }
}
