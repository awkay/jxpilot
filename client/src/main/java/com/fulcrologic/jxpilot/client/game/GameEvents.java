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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.common.net.Msg;

/**
 * The most recent GAME_EVENTs ({@link Msg.GameEvent}: kills, crashes, spawns...) in arrival order, for
 * the HUD and sound. A fixed ring; consumers remember the last {@link #total()} they handled.
 */
public final class GameEvents {
    private final int capacity;
    private final int[] kind;
    private final int[] a;
    private final int[] b;
    private final float[] value;
    private long total;
    private long first;

    public GameEvents(int capacity) {
        this.capacity = capacity;
        kind = new int[capacity];
        a = new int[capacity];
        b = new int[capacity];
        value = new float[capacity];
    }

    /** BEHAVIOR: appends; when full the oldest is overwritten. */
    public void add(Msg.GameEvent e) {
        int i = (int) (total % capacity);
        kind[i] = e.kind;
        a[i] = e.a;
        b[i] = e.b;
        value[i] = e.value;
        total++;
    }

    /** BEHAVIOR: forgets all events (the total keeps counting). */
    public void clear() {
        first = total;
    }

    /** Events ever added. */
    public long total() { return total; }

    /** BEHAVIOR: events still held: min(capacity, added since the last clear). */
    public int size() {
        return (int) Math.min(capacity, total - first);
    }

    /** Index (into the accessors) of the event with sequence {@code seq} (0-based, &lt; total). */
    private int slot(long seq) {
        return (int) (seq % capacity);
    }

    /**
     * BEHAVIOR: i-th held event, 0 = oldest held.
     */
    public int kind(int i) { return kind[slot(total - size() + i)]; }

    public int a(int i) { return a[slot(total - size() + i)]; }

    public int b(int i) { return b[slot(total - size() + i)]; }

    public float value(int i) { return value[slot(total - size() + i)]; }
}
