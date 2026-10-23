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

import java.nio.ByteBuffer;

/**
 * Client end of the C reliable data stream (port of {@code Receive_reliable} and {@code Send_ack} in
 * {@code client/netclient.c}). The server sends a byte stream in {@code PKT_RELIABLE} chunks
 * ({@code %c%hd%ld%ld}: type, length, stream offset, server loop) and retransmits until acked; the
 * client appends in-order bytes to its control buffer ({@code cbuf}) and acks its stream position
 * after every chunk. Unconsumed stream bytes are read through {@link #data()} and released with
 * {@link #consume(int)}. Allocation free.
 */
public final class XpReliableReceiver {
    /** Bytes of the PKT_RELIABLE header. */
    public static final int HEADER_SIZE = 1 + 2 + 4 + 4;

    /** New bytes were appended. */
    public static final int APPENDED = 1;
    /** Chunk already received (an ack was lost): acked again, nothing appended. */
    public static final int DUPLICATE = 2;
    /** Chunk beyond the stream position (an earlier one was lost): dropped and acked. */
    public static final int GAP = 3;
    /** Header truncated (C "Incomplete reliable data packet"): the rest of the datagram is dropped. */
    public static final int INCOMPLETE = -1;
    /** Length &lt;= 0 or larger than the datagram (C treats both as fatal): dropped, not acked. */
    public static final int BAD_LENGTH = -2;
    /** No room in the control buffer (C "Can't copy reliable data to buffer"): dropped, not acked. */
    public static final int OVERFLOW = -3;

    private final ByteBuffer data;
    private int len;
    private int offset;
    private int lastRelLoops;
    private int lastLength;

    /** @param capacity control buffer size (C uses CLIENT_RECV_SIZE = 4096; bigger is harmless) */
    public XpReliableReceiver(int capacity) {
        data = ByteBuffer.allocate(capacity);
    }

    /** BEHAVIOR: forgets all stream data and restarts at offset 0. */
    public void reset() {
        len = 0;
        offset = 0;
        lastRelLoops = 0;
    }

    /**
     * Handle one PKT_RELIABLE packet at the reader's position.
     * <p>
     * BEHAVIOR: the reader always ends after the packet (header plus data) except for INCOMPLETE and
     * BAD_LENGTH, which leave it at the end of the datagram.
     * BEHAVIOR: data at the stream position is appended (an overlapping start is trimmed) and the
     * offset advances; data entirely before it is a DUPLICATE; data after it is a GAP. All three set
     * {@link #ackLoops()} to the chunk's server loop: the caller must send {@code PKT_ACK}
     * ({@link #writeAck}).
     */
    public int receive(XpReader in) {
        if (!in.has(HEADER_SIZE)) {
            in.pos(in.limit());
            return INCOMPLETE;
        }
        in.skip(1);
        int n = in.s16();
        int rel = in.s32();
        int relLoops = in.s32();
        if (n <= 0 || !in.has(n)) {
            in.pos(in.limit());
            return BAD_LENGTH;
        }
        int start = in.pos();
        in.skip(n);
        if (rel - offset > 0) {
            lastRelLoops = relLoops;
            return GAP;
        }
        if (rel + n - offset <= 0) {
            lastRelLoops = relLoops;
            return DUPLICATE;
        }
        int skip = offset - rel;
        int fresh = n - skip;
        if (fresh > data.capacity() - len) {
            return OVERFLOW;
        }
        data.put(len, in.buffer(), start + skip, fresh);
        len += fresh;
        offset += fresh;
        lastLength = fresh;
        lastRelLoops = relLoops;
        return APPENDED;
    }

    /** C {@code Send_ack}: {@code PKT_ACK %ld reliable_offset %ld rel_loops}. Needs 9 bytes. */
    public void writeAck(ByteBuffer out) {
        XpWriter.u8(out, XpProto.PKT_ACK);
        XpWriter.s32(out, offset);
        XpWriter.s32(out, lastRelLoops);
    }

    /** Unconsumed stream bytes are {@code data()[0, length())}. */
    public ByteBuffer data() { return data; }

    public int length() { return len; }

    /** BEHAVIOR: drops the first {@code n} bytes, moving the rest to the front. */
    public void consume(int n) {
        int rest = len - n;
        if (rest > 0) {
            System.arraycopy(data.array(), n, data.array(), 0, rest);
        }
        len = Math.max(0, rest);
    }

    /** Stream offset of the next expected byte (C {@code reliable_offset}). */
    public int offset() { return offset; }

    /** Server loop of the last acked chunk (echoed in the ack for the server's RTT estimate). */
    public int ackLoops() { return lastRelLoops; }

    /** Bytes appended by the last APPENDED chunk. */
    public int lastLength() { return lastLength; }
}
