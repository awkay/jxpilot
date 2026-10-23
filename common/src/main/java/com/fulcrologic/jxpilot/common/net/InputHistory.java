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
 * Client side of the INPUT packet: remembers the last inputs and writes them redundantly so a lost
 * datagram does not lose key edges. Allocation free.
 * <p>
 * INPUT body: {@code u32 latestSeq, u32 ackTick, u8 count, count * (u64 keys, i16 aimTurn)},
 * entries newest first (entry i has sequence {@code latestSeq - i}).
 * {@code keys} is a {@link ControlKey} mask; {@code aimTurn} is a pointer-control turn request in
 * u16 angle units (65536 = full turn, positive = counter-clockwise), 0 when unused.
 */
public final class InputHistory {
    /** Maximum entries per packet. */
    public static final int MAX_REDUNDANCY = 8;
    /** Bytes per entry. */
    public static final int ENTRY_SIZE = 10;
    /** Bytes before the entries. */
    public static final int HEADER_SIZE = 9;

    private final long[] keys = new long[MAX_REDUNDANCY];
    private final short[] aim = new short[MAX_REDUNDANCY];
    private int latestSeq;
    private int stored;

    /** BEHAVIOR: stores the input under the next sequence number (keeping the last 8) and returns it. */
    public int record(long keyMask, int aimTurn) {
        latestSeq++;
        int slot = latestSeq & (MAX_REDUNDANCY - 1);
        keys[slot] = keyMask;
        aim[slot] = (short) aimTurn;
        if (stored < MAX_REDUNDANCY) {
            stored++;
        }
        return latestSeq;
    }

    /** Sequence of the newest recorded input (0 before the first). */
    public int latestSeq() {
        return latestSeq;
    }

    /**
     * Write an INPUT body with up to {@code redundancy} newest entries.
     * <p>
     * BEHAVIOR: writes min(redundancy, 8, recorded) entries newest first.
     *
     * @param ackTick tick of the newest snapshot the client has received
     */
    public void write(ByteBuffer out, int ackTick, int redundancy) {
        int n = Math.min(Math.min(redundancy, MAX_REDUNDANCY), stored);
        out.putInt(latestSeq);
        out.putInt(ackTick);
        out.put((byte) n);
        for (int i = 0; i < n; i++) {
            int slot = (latestSeq - i) & (MAX_REDUNDANCY - 1);
            out.putLong(keys[slot]);
            out.putShort(aim[slot]);
        }
    }
}
