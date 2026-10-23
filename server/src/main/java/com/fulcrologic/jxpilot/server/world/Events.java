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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;

/**
 * The simulation's output for the network layer: two preallocated rings that the sim appends to and
 * any number of consumers read by sequence number (each client keeps its own cursor). Allocation free.
 * <ul>
 *   <li><b>Reliable events</b> ({@link #seq()}): one record per change that clients must not miss,
 *       mapped 1:1 onto {@code ReliableType} messages: {@link #JOIN}, {@link #LEAVE}, {@link #TEAM},
 *       {@link #SCORE}, {@link #TEAM_SCORE}, {@link #MESSAGE}, {@link #BASE_OWNER}, {@link #GAME_EVENT},
 *       {@link #THRUST}. Fields: {@code a, b, c} ints and {@code value} float as documented per kind;
 *       {@link #text} for MESSAGE.</li>
 *   <li><b>Bursts</b> ({@link #burstSeq()}): wire-quantized spark/debris bursts (one per C
 *       {@code Make_debris}) with the sim tick they were made in, for the SNAPSHOT BURST section.</li>
 * </ul>
 * A consumer whose cursor is older than {@link #oldestSeq()} has lost events (it should resync).
 */
public final class Events {
    /** Reliable event ring size (power of two). */
    public static final int CAPACITY = 1024;
    /** Burst ring size (power of two): several ticks of a large fight. */
    public static final int BURSTS = 4096;

    /** a = player id, b = {@code Msg.PlayerJoin} flags ({@code FLAG_ROBOT} for robots). */
    public static final int JOIN = 1;
    /** a = player id. */
    public static final int LEAVE = 2;
    /** a = player id, b = team. */
    public static final int TEAM = 3;
    /** a = player id, b = lives (-1 unlimited), c = Msg.Score status bits, value = score. */
    public static final int SCORE = 4;
    /** a = team, value = team score. */
    public static final int TEAM_SCORE = 5;
    /** a = Msg.Text kind, b = sender id or {@link Obj#NO_ID}; text in {@link #text}. */
    public static final int MESSAGE = 6;
    /** a = base index, b = player id. */
    public static final int BASE_OWNER = 7;
    /** a = Msg.GameEvent kind, b = first id (killer, or NO_ID), c = second id (victim), value. */
    public static final int GAME_EVENT = 8;
    /** a = player id, b = afterburners, value = power. */
    public static final int THRUST = 9;

    public final int[] kind = new int[CAPACITY];
    public final int[] a = new int[CAPACITY];
    public final int[] b = new int[CAPACITY];
    public final int[] c = new int[CAPACITY];
    public final float[] value = new float[CAPACITY];
    public final StringBuilder[] text = new StringBuilder[CAPACITY];
    private long seq;

    public final DebrisBurst[] burst = new DebrisBurst[BURSTS];
    /** {@code Records.BurstKind}. */
    public final int[] burstKind = new int[BURSTS];
    /** {@code Records.ObjColor}. */
    public final int[] burstColor = new int[BURSTS];
    /** Owner player id or {@link Obj#NO_ID}. */
    public final int[] burstOwner = new int[BURSTS];
    public final int[] burstTick = new int[BURSTS];
    private long burstSeq;

    public Events() {
        for (int i = 0; i < CAPACITY; i++) {
            text[i] = new StringBuilder(Const.MSG_LEN);
        }
        for (int i = 0; i < BURSTS; i++) {
            burst[i] = new DebrisBurst();
        }
    }

    /** Ring index of an event sequence number. */
    public static int index(long seq) {
        return (int) (seq & (CAPACITY - 1));
    }

    /** Sequence number the next event will get (events {@code [oldestSeq, seq)} are readable). */
    public long seq() {
        return seq;
    }

    /** BEHAVIOR: oldest readable event sequence number: 0 until the ring wraps, then {@code seq - CAPACITY}. */
    public long oldestSeq() {
        return Math.max(0, seq - CAPACITY);
    }

    /**
     * Append an event.
     * <p>
     * BEHAVIOR: takes the next ring slot (overwriting the oldest when full), clears its text, returns its index.
     */
    public int add(int k, int va, int vb, int vc, float v) {
        int i = index(seq++);
        kind[i] = k;
        a[i] = va;
        b[i] = vb;
        c[i] = vc;
        value[i] = v;
        text[i].setLength(0);
        return i;
    }

    /** Append a MESSAGE and return its cleared text buffer for the caller to fill. */
    public StringBuilder message(int msgKind, int from) {
        return text[add(MESSAGE, msgKind, from, 0, 0f)];
    }

    // ------------------------------------------------------------------ bursts

    /** Ring index of a burst sequence number. */
    public static int burstIndex(long seq) {
        return (int) (seq & (BURSTS - 1));
    }

    /** Sequence number the next burst will get. */
    public long burstSeq() {
        return burstSeq;
    }

    /** BEHAVIOR: oldest readable burst sequence number (0 until the ring wraps). */
    public long oldestBurstSeq() {
        return Math.max(0, burstSeq - BURSTS);
    }

    /**
     * Take the next burst slot for {@code tick}; the caller fills (and quantizes) the returned parameters.
     * <p>
     * BEHAVIOR: overwrites the oldest burst when the ring is full.
     */
    public DebrisBurst addBurst(int bKind, int color, int owner, int tick) {
        int i = burstIndex(burstSeq++);
        burstKind[i] = bKind;
        burstColor[i] = color;
        burstOwner[i] = owner;
        burstTick[i] = tick;
        return burst[i];
    }
}
