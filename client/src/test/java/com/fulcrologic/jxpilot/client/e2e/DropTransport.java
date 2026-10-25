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
package com.fulcrologic.jxpilot.client.e2e;

import com.fulcrologic.jxpilot.client.net.Transport;
import com.fulcrologic.jxpilot.common.net.LobbyMsg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A client transport that drops chosen datagrams: the n-th of a packet type in either direction (a lobby JOIN can be
 * picked by its result), to replay a lossy link's worst case deterministically. Logs every datagram.
 */
final class DropTransport implements Transport {
    /** Drop the {@code nth} (1-based) datagram of {@code type} going out ({@code out}) or coming in. */
    record Rule(boolean out, int type, int joinResult, int nth) { }

    final Transport inner;
    final List<Rule> rules;
    final int[] seen;
    final List<String> log = new ArrayList<>();
    /** Hold outgoing datagrams of type {@link #holdType} (from the {@link #holdFrom}-th on, once) until one of type {@link #releaseAfter} has gone out. */
    int holdType = -1;
    int holdFrom = 1;
    int releaseAfter = -1;
    private int holdSeen;
    private ByteBuffer held;
    /** After each SELECT, send the last LIST_REQUEST again (an older client's list confirm racing the SELECT). */
    boolean replayListAfterSelect;
    private ByteBuffer lastList;
    int replayed;

    DropTransport(Transport inner, List<Rule> rules) {
        this.inner = inner;
        this.rules = rules;
        this.seen = new int[rules.size()];
    }

    private boolean drop(boolean out, ByteBuffer d) {
        int p = d.position();
        if (d.remaining() < PacketHeader.SIZE) {
            return false;
        }
        int type = d.get(p + 3) & 0xFF;
        int result = type == PacketType.LOBBY_JOIN && d.remaining() > PacketHeader.SIZE + 2
            ? d.get(p + PacketHeader.SIZE + 2) & 0xFF : -1;
        boolean dropped = false;
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (r.out() == out && r.type() == type && (r.joinResult() < 0 || r.joinResult() == result)
                && ++seen[i] == r.nth()) {
                dropped = true;
            }
        }
        log.add((out ? "out " : "in  ") + type + (result >= 0 ? "/" + result : "") + (dropped ? " DROPPED" : ""));
        return dropped;
    }

    @Override
    public boolean send(ByteBuffer packet) throws IOException {
        int type = packet.remaining() >= PacketHeader.SIZE ? packet.get(packet.position() + 3) & 0xFF : -1;
        if (held == null && type == holdType && ++holdSeen == holdFrom) {
            held = ByteBuffer.allocate(packet.remaining());
            held.put(packet).flip();
            log.add("out " + type + " HELD");
            return true;
        }
        if (drop(true, packet)) {
            packet.position(packet.limit());
            return true;
        }
        ByteBuffer copy = type == PacketType.LOBBY_LIST_REQUEST ? packet.duplicate() : null;
        boolean ok = inner.send(packet);
        if (copy != null) {
            lastList = ByteBuffer.allocate(copy.remaining());
            lastList.put(copy).flip();
        }
        if (replayListAfterSelect && type == PacketType.LOBBY_SELECT && lastList != null) {
            log.add("out " + PacketType.LOBBY_LIST_REQUEST + " REPLAYED");
            inner.send(lastList.duplicate());
            replayed++;
        }
        if (held != null && type == releaseAfter) {
            log.add("out " + holdType + " RELEASED");
            inner.send(held);
            held = null;
            holdType = -1;
        }
        return ok;
    }

    @Override
    public int receive(ByteBuffer buf) throws IOException {
        while (true) {
            int start = buf.position();
            int n = inner.receive(buf);
            if (n <= 0) {
                return n;
            }
            ByteBuffer d = buf.duplicate();
            d.position(start).limit(start + n);
            if (!drop(false, d)) {
                return n;
            }
            buf.position(start);
        }
    }

    @Override
    public void close() {
        inner.close();
    }

    static final int JOIN_OK = LobbyMsg.JOIN_OK;
}
