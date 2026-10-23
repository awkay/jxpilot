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
 * Cursor over a region of a byte buffer, decoding fields the way C {@code Packet_scanf}
 * ({@code common/net.c}) does: big endian {@code %c %hd %hu %d %u %ld}, and NUL-terminated
 * {@code %s} (max {@link XpProto#MAX_CHARS}) / {@code %S} (max {@link XpProto#MSG_LEN}) strings.
 * The fixed-size getters do not check bounds: callers check {@link #has(int)} first, as each
 * {@code Receive_*} handler effectively does for a whole packet. Allocation free.
 */
public final class XpReader {
    /** {@link #string} result: no NUL before the end of the data (C "not enough input", failure 3). */
    public static final int INCOMPLETE = 0;
    /** {@link #string} result: no NUL within the size limit (C "String overflow while scanning"). */
    public static final int OVERFLOW = -1;

    private ByteBuffer b;
    private int pos;
    private int lim;

    /** Read {@code buf[from, to)}. */
    public XpReader wrap(ByteBuffer buf, int from, int to) {
        b = buf;
        pos = from;
        lim = to;
        return this;
    }

    public int pos() { return pos; }

    public void pos(int p) { pos = p; }

    public int limit() { return lim; }

    public int remaining() { return lim - pos; }

    /** BEHAVIOR: true iff at least {@code n} bytes remain. */
    public boolean has(int n) { return lim - pos >= n; }

    public ByteBuffer buffer() { return b; }

    public int peekU8() { return b.get(pos) & 0xFF; }

    public int u8() { return b.get(pos++) & 0xFF; }

    /** {@code %hd}: sign extended. */
    public int s16() {
        int v = b.getShort(pos);
        pos += 2;
        return v;
    }

    /** {@code %hu}. */
    public int u16() {
        int v = b.getShort(pos) & 0xFFFF;
        pos += 2;
        return v;
    }

    /** {@code %d}, {@code %u}, {@code %ld} (the C client's 32-bit wire long). */
    public int s32() {
        int v = b.getInt(pos);
        pos += 4;
        return v;
    }

    public void skip(int n) { pos += n; }

    /**
     * {@code %s}/{@code %S}: a NUL-terminated Latin-1 string of at most {@code max - 1} chars.
     * <p>
     * BEHAVIOR: on success appends the chars to {@code out} (cleared first), advances past the NUL and
     * returns the bytes consumed (chars + 1).
     * BEHAVIOR: data ending before a NUL returns {@link #INCOMPLETE}; {@code max} bytes without a NUL
     * return {@link #OVERFLOW}; in both cases the position is unchanged and {@code out} is empty.
     */
    public int string(StringBuilder out, int max) {
        out.setLength(0);
        int n = scan(max);
        if (n <= 0) {
            return n;
        }
        for (int i = 0; i < n - 1; i++) {
            out.append((char) (b.get(pos + i) & 0xFF));
        }
        pos += n;
        return n;
    }

    /** Like {@link #string} without copying the chars. */
    public int skipString(int max) {
        int n = scan(max);
        if (n > 0) {
            pos += n;
        }
        return n;
    }

    private int scan(int max) {
        for (int k = 0; ; k++) {
            if (pos + k >= lim) {
                return INCOMPLETE;
            }
            if (b.get(pos + k) == 0) {
                return k + 1;
            }
            if (k + 1 >= max) {
                return OVERFLOW;
            }
        }
    }
}
