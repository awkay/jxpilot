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
package com.fulcrologic.jxpilot.common.net;

import java.nio.ByteBuffer;

/**
 * The 8-byte header that starts every datagram:
 * <pre>
 *   u16 magic (0x4A58 "JX") | u8 version | u8 packetType | u16 sessionId | u16 reliableAck
 * </pre>
 * {@code sessionId} is 0 until the server assigns one in ACCEPT. {@code reliableAck} is the next
 * reliable sequence number the sender expects from the peer (cumulative ack), see
 * {@link ReliableChannel#ack()}.
 * <p>
 * Use one instance as a reusable reader; {@link #write} is static. Allocation free.
 */
public final class PacketHeader {
    public static final int SIZE = 8;

    private int type;
    private int session;
    private int reliableAck;

    /** Write a header at the buffer's position (relative puts). */
    public static void write(ByteBuffer out, int type, int session, int reliableAck) {
        write(out, Protocol.VERSION, type, session, reliableAck);
    }

    /**
     * Write a header with another protocol's version byte: the REJECT that tells a peer of that version why it is
     * refused (every version so far has this header and REJECT layout).
     */
    public static void write(ByteBuffer out, int version, int type, int session, int reliableAck) {
        out.putShort((short) Protocol.MAGIC);
        out.put((byte) version);
        out.put((byte) type);
        out.putShort((short) session);
        out.putShort((short) reliableAck);
    }

    /**
     * BEHAVIOR: the version byte of a JX header at the buffer's position (nothing is moved), or -1 when shorter than a
     * header or not the JX magic.
     */
    public static int peekVersion(ByteBuffer in) {
        int p = in.position();
        return in.remaining() >= SIZE && (in.getShort(p) & 0xFFFF) == Protocol.MAGIC ? in.get(p + 2) & 0xFF : -1;
    }

    /** BEHAVIOR: the packet type byte of a header at the buffer's position (check {@link #peekVersion} first). */
    public static int peekType(ByteBuffer in) {
        return in.get(in.position() + 3) & 0xFF;
    }

    /**
     * Read and validate a header at the buffer's position, advancing past it.
     * <p>
     * BEHAVIOR: accepts only the JX magic and this protocol version; unsigned fields are decoded.
     *
     * @return false (position unchanged) if too short, wrong magic or wrong version
     */
    public boolean read(ByteBuffer in) {
        if (in.remaining() < SIZE) {
            return false;
        }
        int p = in.position();
        if ((in.getShort(p) & 0xFFFF) != Protocol.MAGIC || (in.get(p + 2) & 0xFF) != Protocol.VERSION) {
            return false;
        }
        type = in.get(p + 3) & 0xFF;
        session = in.getShort(p + 4) & 0xFFFF;
        reliableAck = in.getShort(p + 6) & 0xFFFF;
        in.position(p + SIZE);
        return true;
    }

    /** {@link PacketType} of the last header read. */
    public int type() { return type; }

    public int session() { return session; }

    public int reliableAck() { return reliableAck; }
}
