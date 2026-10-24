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

/**
 * Sounds received from the server (SNAPSHOT SOUND records, C {@code PKT_AUDIO}) in arrival order: event id
 * ({@link com.fulcrologic.jxpilot.common.SoundEvent} ordinal) and volume. A fixed ring addressed by sequence
 * number; a consumer remembers the last {@link #total()} it played. Allocation free.
 */
public final class SoundQueue {
    private final int capacity;
    private final int[] event;
    private final int[] volume;
    private long total;

    public SoundQueue(int capacity) {
        this.capacity = capacity;
        event = new int[capacity];
        volume = new int[capacity];
    }

    /** BEHAVIOR: appends; when full the oldest is overwritten. */
    public void add(int ev, int vol) {
        int i = (int) (total % capacity);
        event[i] = ev;
        volume[i] = vol;
        total++;
    }

    /** Sounds ever added (the next sequence number). */
    public long total() { return total; }

    /** BEHAVIOR: the oldest sequence number still held: 0 until the ring wraps, then {@code total - capacity}. */
    public long oldest() {
        return Math.max(0, total - capacity);
    }

    /** Event id of the sound with sequence {@code seq} ({@link #oldest()} &lt;= seq &lt; {@link #total()}). */
    public int event(long seq) { return event[(int) (seq % capacity)]; }

    /** Volume of the sound with sequence {@code seq}. */
    public int volume(long seq) { return volume[(int) (seq % capacity)]; }
}
