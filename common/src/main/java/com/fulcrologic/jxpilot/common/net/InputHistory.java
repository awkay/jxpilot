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
 * Client side of the INPUT packet (protocol v2, docs/latency-fixes.md §4.3): one entry per local tick, keyed by the
 * tick it is for, written redundantly so a lost datagram does not lose key edges. Allocation free.
 * <p>
 * INPUT body: {@code u32 newestTick, u8 count}, then {@code count *} ({@code u64 keys, u64 taps, i16 aimTurn}),
 * entries newest first (entry i is for tick {@code newestTick - i}).
 * <ul>
 *   <li>{@code keys}: the {@link ControlKey} mask held at the end of the tick (or when last sent);</li>
 *   <li>{@code taps}: keys that went down and came up again within the tick, so a quick tap is not lost; the server
 *   applies {@code keys | taps}, then {@code keys};</li>
 *   <li>{@code aimTurn}: pointer control's horizontal movement summed over the tick (C {@code PKT_POINTER_MOVE}),
 *   0 when unused.</li>
 * </ul>
 * The history keeps the last {@link #RING} ticks, so a client can also look up what it sent for a tick the server
 * reports back (the snapshot header's {@code inputTick}).
 */
public final class InputHistory {
    /** Most entries per packet. */
    public static final int MAX_REDUNDANCY = 8;
    /** Ticks remembered (a power of two). */
    public static final int RING = 32;
    /** Bytes per entry. */
    public static final int ENTRY_SIZE = 18;
    /** Bytes before the entries. */
    public static final int HEADER_SIZE = 5;

    private final long[] keys = new long[RING];
    private final long[] taps = new long[RING];
    private final short[] aim = new short[RING];
    private final int[] tickOf = new int[RING];
    private boolean any;
    private int newest;
    /** The first tick set since the reset: nothing older is ever sent. */
    private int first;

    /** Forget every entry (a new session). */
    public void reset() {
        any = false;
        newest = 0;
    }

    /** Whether any entry has been set since the last {@link #reset}. */
    public boolean started() {
        return any;
    }

    /** The newest tick with an entry (meaningless before {@link #started}). */
    public int newestTick() {
        return newest;
    }

    /**
     * Set the entry for {@code tick}.
     * <p>
     * BEHAVIOR: the first entry starts the history; an entry for the newest tick replaces it; a newer tick becomes
     * the newest, and the ticks skipped in between (at most the last {@link #RING} - 1) get the previous newest
     * entry's keys with no taps and no aim, as the keys stayed held; an entry older than the newest is ignored
     * and false is returned.
     * BEHAVIOR: aimTurn is clamped to i16.
     */
    public boolean set(int tick, long keyMask, long tapMask, int aimTurn) {
        if (any && tick - newest < 0) {
            return false;
        }
        if (any && tick - newest > 1) {
            long held = keys[newest & (RING - 1)];
            int from = Math.max(newest + 1, tick - (RING - 1));
            for (int t = from; t - tick < 0; t++) {
                put(t, held, 0, 0);
            }
        }
        if (!any) {
            first = tick;
        }
        put(tick, keyMask, tapMask, aimTurn);
        newest = tick;
        any = true;
        return true;
    }

    private void put(int tick, long k, long t, int a) {
        int s = tick & (RING - 1);
        keys[s] = k;
        taps[s] = t;
        aim[s] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, a));
        tickOf[s] = tick;
    }

    /** BEHAVIOR: true iff an entry for {@code tick} is still remembered. */
    public boolean has(int tick) {
        return any && tickOf[tick & (RING - 1)] == tick && newest - tick >= 0 && newest - tick < RING;
    }

    /** Keys of the remembered entry for {@code tick} (see {@link #has}). */
    public long keys(int tick) {
        return keys[tick & (RING - 1)];
    }

    /** Taps of the remembered entry for {@code tick}. */
    public long taps(int tick) {
        return taps[tick & (RING - 1)];
    }

    /** Aim turn of the remembered entry for {@code tick}. */
    public int aimTurn(int tick) {
        return aim[tick & (RING - 1)];
    }

    /**
     * Entries {@link #write} would send: those newer than {@code ackedTick}, at least 1 and at most
     * {@link #MAX_REDUNDANCY}, never one older than the first tick set.
     * <p>
     * BEHAVIOR: 0 before the first entry.
     */
    public int pending(int ackedTick) {
        if (!any) {
            return 0;
        }
        int d = Math.min(newest - ackedTick, newest - first + 1);
        return Math.max(1, Math.min(MAX_REDUNDANCY, d));
    }

    /**
     * Write an INPUT body: the newest entry and every older one newer than {@code ackedTick} (the snapshot header's
     * inputTick), newest first, at least 1 and at most {@link #MAX_REDUNDANCY}.
     * <p>
     * BEHAVIOR: writes {@link #pending(int)} entries; returns that count (0 and nothing written before the first
     * entry).
     */
    public int write(ByteBuffer out, int ackedTick) {
        int n = pending(ackedTick);
        if (n == 0) {
            return 0;
        }
        out.putInt(newest);
        out.put((byte) n);
        for (int i = 0; i < n; i++) {
            int s = (newest - i) & (RING - 1);
            out.putLong(keys[s]);
            out.putLong(taps[s]);
            out.putShort(aim[s]);
        }
        return n;
    }
}
