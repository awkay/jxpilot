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

import com.fulcrologic.jxpilot.common.SoundEvent;

/**
 * One player's sounds waiting for its next snapshot (C {@code saudio.c} {@code pl->audio}): each
 * {@link SoundEvent} at most once, in the order first queued, at the loudest volume queued since the last
 * {@link #clear}. Fixed size ({@link SoundEvent#COUNT} entries), allocation free.
 */
public final class AudioQueue {
    private final int[] volume = new int[SoundEvent.COUNT];
    private final int[] order = new int[SoundEvent.COUNT];
    private int count;

    /**
     * C {@code queue_audio}.
     * <p>
     * BEHAVIOR: a new event is appended with its volume; an event already queued keeps its place and takes the
     * louder of the two volumes. Unknown event ids and volumes below 1 are ignored.
     */
    public void queue(int event, int vol) {
        if (event < 0 || event >= SoundEvent.COUNT || vol < 1) {
            return;
        }
        if (volume[event] == 0) {
            order[count++] = event;
            volume[event] = vol;
        } else if (volume[event] < vol) {
            volume[event] = vol;
        }
    }

    /** Events queued. */
    public int count() { return count; }

    /** The i-th queued event id (0 = first queued). */
    public int event(int i) { return order[i]; }

    /** Volume of the i-th queued event. */
    public int volume(int i) { return volume[order[i]]; }

    /** BEHAVIOR: forgets every queued event (C {@code sound_play_queued} after sending, {@code sound_player_init}). */
    public void clear() {
        for (int i = 0; i < count; i++) {
            volume[order[i]] = 0;
        }
        count = 0;
    }
}
