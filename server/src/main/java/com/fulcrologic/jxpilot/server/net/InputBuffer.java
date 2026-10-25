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

import com.fulcrologic.jxpilot.common.net.SnapshotWriter;

/**
 * One client's inputs by the tick they are for (protocol v2, docs/latency-fixes.md §4.3): a ring of {@link #RING}
 * entries. At tick T the server takes entry T. A missing entry repeats the last applied mask (keys are usually held)
 * and counts a starvation; an entry for a tick already taken is dropped (counted late when that tick starved); an
 * entry more than {@code maxAhead} ticks ahead is dropped. It also keeps the input slack: the smallest margin, in
 * 1/16 tick, by which entries arrived before their tick over the last {@code window} ticks. The margins that count
 * are those of the copies that delivered what the server applies: an entry's first copy, a later copy that
 * changes it (the client's final send at the tick boundary after an early copy), and the first late copy of a
 * starved tick (negative). Copies that repeat what is stored (redundancy, mid-tick repeats) arrive later by design
 * and do not count. Allocation free; ticks are compared by difference, so the counter may wrap.
 */
public final class InputBuffer {
    /** Entries kept (a power of two). */
    public static final int RING = 32;
    /** {@link #offer} results. */
    public static final int STORED = 0;
    /** The tick's entry was stored before; this copy (redundancy) replaced it. */
    public static final int REVISED = 1;
    /** The tick was already taken: dropped. */
    public static final int PAST = 2;
    /** More than {@code maxAhead} ticks ahead: dropped. */
    public static final int FAR_FUTURE = 3;
    /** Older than the copy that wrote the slot (a reordered packet): dropped. */
    public static final int STALE = 4;
    /** Slack windows kept at most (one per tick). */
    public static final int MAX_WINDOW = 64;

    private final long[] keys = new long[RING];
    private final long[] taps = new long[RING];
    private final int[] aim = new int[RING];
    private final int[] tickOf = new int[RING];
    private final int[] writtenBy = new int[RING];
    private final boolean[] full = new boolean[RING];
    /** Ticks that were taken without an entry. */
    private final boolean[] starved = new boolean[RING];
    /** A tick taken (with an entry, or starved) that a late copy may still be counted against, and which tick. */
    private final boolean[] open = new boolean[RING];
    private final int[] takenTick = new int[RING];
    private final int[] slackMin = new int[MAX_WINDOW];
    private final int maxAhead;
    private final int window;
    private int slackSlot;

    private boolean started;
    private boolean anyTaken;
    private int lastTaken;
    private long heldKeys;
    private int heldTick;
    private long outKeys;
    private long outTaps;
    private int outAim;
    private int appliedTick;

    private long starvations;
    private long late;
    private long farFuture;

    /**
     * @param maxAhead entries more than this many ticks ahead of the next tick are dropped (clamped to 0..RING-1)
     * @param window   ticks the slack minimum is taken over (clamped to 1..{@link #MAX_WINDOW})
     */
    public InputBuffer(int maxAhead, int window) {
        this.maxAhead = Math.clamp(maxAhead, 0, RING - 1);
        this.window = Math.clamp(window, 1, MAX_WINDOW);
        reset();
    }

    /** Forget everything (a new session): no entries, no held mask, counters at 0, no slack report. */
    public void reset() {
        java.util.Arrays.fill(full, false);
        java.util.Arrays.fill(starved, false);
        java.util.Arrays.fill(open, false);
        java.util.Arrays.fill(slackMin, Integer.MAX_VALUE);
        started = false;
        anyTaken = false;
        heldKeys = 0;
        heldTick = 0;
        appliedTick = 0;
        starvations = 0;
        late = 0;
        farFuture = 0;
    }

    /**
     * An entry arrived in the datagram with send sequence {@code packetSeq} (u16, INPUT's {@code seq}),
     * {@code margin16} sixteenths of a tick before entry {@code tick} is due.
     * <p>
     * BEHAVIOR: a tick already taken is PAST; it is counted late, and its margin enters the slack, once, when that
     * tick starved or when the copy differs from the entry that was applied (the client's final send lost to an
     * early copy). More than maxAhead ahead of {@code nextTick} is FAR_FUTURE (counted). Otherwise the first copy
     * is STORED (its margin enters the slack window), a copy from a datagram sent at or after the one that wrote
     * the entry REVISED it (its margin enters the slack only when it changes the entry), and a copy from a datagram
     * sent before it is STALE (reordering: an early copy arriving after the final send of the same tick).
     */
    public int offer(int tick, long keyMask, long tapMask, int aimTurn, int packetSeq, int nextTick, int margin16) {
        int d = tick - nextTick;
        if (d < 0) {
            int s = tick & (RING - 1);
            if (open[s] && takenTick[s] == tick && (starved[s]
                || tickOf[s] == tick && (keys[s] != keyMask || taps[s] != tapMask || aim[s] != aimTurn))) {
                open[s] = false;
                starved[s] = false;
                late++;
                fold(margin16);
            }
            return PAST;
        }
        if (d > maxAhead) {
            farFuture++;
            return FAR_FUTURE;
        }
        int s = tick & (RING - 1);
        boolean present = full[s] && tickOf[s] == tick;
        if (present && (short) (packetSeq - writtenBy[s]) < 0) {
            return STALE;
        }
        boolean changed = !present || keys[s] != keyMask || taps[s] != tapMask || aim[s] != aimTurn;
        keys[s] = keyMask;
        taps[s] = tapMask;
        aim[s] = aimTurn;
        tickOf[s] = tick;
        writtenBy[s] = packetSeq & 0xFFFF;
        full[s] = true;
        if (present) {
            if (changed) {
                fold(margin16);
            }
            return REVISED;
        }
        fold(margin16);
        return STORED;
    }

    private void fold(int margin16) {
        slackMin[slackSlot] = Math.min(slackMin[slackSlot], margin16);
    }

    /**
     * Take the input for {@code tick} (call once per tick, in order).
     * <p>
     * BEHAVIOR: with an entry: its keys, taps and aim come out, its keys become the held mask, the applied tick
     * is {@code tick} and the buffer has started. Without one: the held mask comes out with no taps and no aim, the
     * applied tick stays the held entry's tick, and a starvation is counted once the buffer has started (the ticks
     * before the first applied entry are no starvation, even while that entry waits for its tick).
     * BEHAVIOR: the slack window moves on by one tick. Returns whether the tick had an entry.
     */
    public boolean take(int tick) {
        int s = tick & (RING - 1);
        boolean present = full[s] && tickOf[s] == tick;
        if (present) {
            outKeys = keys[s];
            outTaps = taps[s];
            outAim = aim[s];
            heldKeys = outKeys;
            heldTick = tick;
            full[s] = false;
            starved[s] = false;
            started = true;
            open[s] = true;
            takenTick[s] = tick;
        } else {
            outKeys = heldKeys;
            outTaps = 0;
            outAim = 0;
            if (started) {
                starvations++;
                starved[s] = true;
                open[s] = true;
                takenTick[s] = tick;
            }
        }
        appliedTick = heldTick;
        anyTaken = true;
        lastTaken = tick;
        slackSlot = (slackSlot + 1) % window;
        slackMin[slackSlot] = Integer.MAX_VALUE;
        return present;
    }

    /** Keys {@link #take} gave out. */
    public long keys() { return outKeys; }

    /** Taps {@link #take} gave out (keys pressed and released within the tick). */
    public long taps() { return outTaps; }

    /** Aim turn {@link #take} gave out. */
    public int aimTurn() { return outAim; }

    /** The tick whose entry the last {@link #take} applied, or held (0 before any entry). */
    public int appliedTick() { return appliedTick; }

    /** Whether an entry has been applied (inputs flow; the host gets one per tick from then on). */
    public boolean started() { return started; }

    /** The last tick taken (meaningless before the first take). */
    public int lastTaken() { return anyTaken ? lastTaken : 0; }

    /**
     * The newest tick an entry is stored for, as a lead over {@code nextTick} (ticks; 0 when none is ahead): how far
     * ahead of the server this client runs.
     */
    public int lead(int nextTick) {
        int best = 0;
        for (int i = 0; i < RING; i++) {
            if (full[i]) {
                best = Math.max(best, tickOf[i] - nextTick);
            }
        }
        return best;
    }

    /**
     * The slack report: the smallest counted arrival margin (1/16 tick) over the window (see the class comment).
     * <p>
     * BEHAVIOR: {@link SnapshotWriter#NO_SLACK} when no counted arrival fell in the window.
     */
    public int slack16() {
        int m = Integer.MAX_VALUE;
        for (int i = 0; i < window; i++) {
            m = Math.min(m, slackMin[i]);
        }
        return m == Integer.MAX_VALUE ? SnapshotWriter.NO_SLACK : m;
    }

    /** Ticks taken without an entry after inputs started. */
    public long starvations() { return starvations; }

    /** Copies that arrived after their tick ran without them: the tick starved, or applied another copy. */
    public long late() { return late; }

    /** Entries dropped as more than maxAhead ticks ahead. */
    public long farFuture() { return farFuture; }
}
