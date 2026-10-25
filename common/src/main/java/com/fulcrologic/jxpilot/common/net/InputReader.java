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
 * Server side reader of an INPUT body (see {@link InputHistory}): entry i is for tick {@code newestTick() - i},
 * 0 = newest. {@link #wrap} decodes the (variable-length) entries into preallocated arrays; allocation free.
 */
public final class InputReader {
    private int seq;
    private int newestTick;
    private int count;
    private final long[] keys = new long[InputHistory.MAX_REDUNDANCY];
    private final long[] taps = new long[InputHistory.MAX_REDUNDANCY];
    private final int[] aim = new int[InputHistory.MAX_REDUNDANCY];

    /**
     * Decode the body at the buffer's position (absolute reads; the position does not move).
     * <p>
     * BEHAVIOR: rejects bodies shorter than the header, with no entry or more than 8, with an unknown flag bit, or
     * shorter than their entries' fields; an entry without keys has the next-newer entry's (0 for the newest), one
     * without taps or aim has 0.
     *
     * @return false if malformed
     */
    public boolean wrap(ByteBuffer in) {
        int p = in.position();
        int end = in.limit();
        if (end - p < InputHistory.HEADER_SIZE) {
            return false;
        }
        int n = in.get(p + 6) & 0xFF;
        if (n == 0 || n > InputHistory.MAX_REDUNDANCY) {
            return false;
        }
        int o = p + InputHistory.HEADER_SIZE;
        long ref = 0;
        for (int i = 0; i < n; i++) {
            if (o >= end) {
                return false;
            }
            int f = in.get(o++) & 0xFF;
            if ((f & ~(InputHistory.F_KEYS | InputHistory.F_TAPS | InputHistory.F_AIM)) != 0) {
                return false;
            }
            int need = ((f & InputHistory.F_KEYS) != 0 ? 8 : 0) + ((f & InputHistory.F_TAPS) != 0 ? 8 : 0)
                + ((f & InputHistory.F_AIM) != 0 ? 2 : 0);
            if (end - o < need) {
                return false;
            }
            if ((f & InputHistory.F_KEYS) != 0) {
                ref = in.getLong(o);
                o += 8;
            }
            keys[i] = ref;
            if ((f & InputHistory.F_TAPS) != 0) {
                taps[i] = in.getLong(o);
                o += 8;
            } else {
                taps[i] = 0;
            }
            if ((f & InputHistory.F_AIM) != 0) {
                aim[i] = in.getShort(o);
                o += 2;
            } else {
                aim[i] = 0;
            }
        }
        seq = in.getShort(p) & 0xFFFF;
        newestTick = in.getInt(p + 2);
        count = n;
        return true;
    }

    /** The datagram's send sequence (u16). */
    public int seq() { return seq; }

    /** The tick of the newest entry. */
    public int newestTick() { return newestTick; }

    public int count() { return count; }

    /** Tick of entry i (0 = newest). */
    public int tick(int i) { return newestTick - i; }

    /** {@link ControlKey} mask of entry i. */
    public long keys(int i) { return keys[i]; }

    /** Keys tapped (down and up) within entry i's tick. */
    public long taps(int i) { return taps[i]; }

    /** Aim turn of entry i (pointer movement, signed). */
    public int aimTurn(int i) { return aim[i]; }
}
