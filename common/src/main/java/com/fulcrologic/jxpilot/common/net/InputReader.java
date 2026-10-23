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
 * Server side flyweight over an INPUT body (see {@link InputHistory}). Typical use: for
 * {@code i = count()-1 .. 0}, apply entries whose {@link #seq(int)} is newer than the last applied.
 */
public final class InputReader {
    private ByteBuffer buf;
    private int base;
    private int latestSeq;
    private int ackTick;
    private int count;

    /**
     * Wrap the body at the buffer's position.
     * <p>
     * BEHAVIOR: rejects bodies shorter than the header, with more than 8 entries, or shorter than the entries.
     *
     * @return false if malformed
     */
    public boolean wrap(ByteBuffer in) {
        int p = in.position();
        if (in.remaining() < InputHistory.HEADER_SIZE) {
            return false;
        }
        latestSeq = in.getInt(p);
        ackTick = in.getInt(p + 4);
        count = in.get(p + 8) & 0xFF;
        if (count > InputHistory.MAX_REDUNDANCY
            || in.remaining() < InputHistory.HEADER_SIZE + count * InputHistory.ENTRY_SIZE) {
            return false;
        }
        buf = in;
        base = p + InputHistory.HEADER_SIZE;
        return true;
    }

    public int latestSeq() { return latestSeq; }

    /** Newest snapshot tick the client has received. */
    public int ackTick() { return ackTick; }

    public int count() { return count; }

    /** Sequence of entry i (0 = newest). */
    public int seq(int i) { return latestSeq - i; }

    /** {@link ControlKey} mask of entry i. */
    public long keys(int i) { return buf.getLong(base + i * InputHistory.ENTRY_SIZE); }

    /** Aim turn of entry i (signed, u16 angle units). */
    public int aimTurn(int i) { return buf.getShort(base + i * InputHistory.ENTRY_SIZE + 8); }
}
