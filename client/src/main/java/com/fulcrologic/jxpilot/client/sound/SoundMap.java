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
package com.fulcrologic.jxpilot.client.sound;

import com.fulcrologic.jxpilot.common.SoundEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which clip each {@link SoundEvent} plays: the client's sound file ({@code sounds/sounds.map}, C's
 * {@code sounds} resource file read by {@code audioInit}). One line per event, {@code event clip}
 * separated by tabs or spaces; blank lines and lines starting with {@code #} are skipped. Events without a
 * line are silent. A clip whose name contains {@value #LOOP_MARK} is a loop. Load-time only.
 */
public final class SoundMap {
    /** Clip names containing this loop seamlessly (the Junkala pack's convention). */
    public static final String LOOP_MARK = "_loop";

    private final int[] clipOf = new int[SoundEvent.COUNT];
    private final List<String> clips = new ArrayList<>();

    private SoundMap() {
        Arrays.fill(clipOf, -1);
    }

    /** A map where every event is silent. */
    public static SoundMap empty() {
        return new SoundMap();
    }

    /**
     * Parse a sound map.
     * <p>
     * BEHAVIOR: each {@code event clip} line maps the C event name to the clip; a clip named on several
     * lines is one clip (same index, in order of first use); a later line for the same event wins (as C).
     * BEHAVIOR: throws IllegalArgumentException naming the line for an unknown event name, a line without a
     * clip, or a line with more than two fields.
     */
    public static SoundMap parse(String text) {
        SoundMap m = new SoundMap();
        String[] lines = text.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("[ \t]+");
            if (f.length != 2) {
                throw new IllegalArgumentException("sound map line " + (n + 1) + ": expected 'event clip': " + line);
            }
            SoundEvent e = SoundEvent.byName(f[0]);
            if (e == null) {
                throw new IllegalArgumentException("sound map line " + (n + 1) + ": unknown sound '" + f[0] + "'");
            }
            int c = m.clips.indexOf(f[1]);
            if (c < 0) {
                c = m.clips.size();
                m.clips.add(f[1]);
            }
            m.clipOf[e.ordinal()] = c;
        }
        return m;
    }

    /** BEHAVIOR: the clip index of an event id, -1 when the event is silent or the id is unknown. */
    public int clip(int event) {
        return event >= 0 && event < clipOf.length ? clipOf[event] : -1;
    }

    /** Number of distinct clips. */
    public int clipCount() {
        return clips.size();
    }

    /** File name of clip {@code i}. */
    public String clipName(int i) {
        return clips.get(i);
    }

    /** BEHAVIOR: true iff the clip's name contains {@link #LOOP_MARK}. */
    public boolean loops(int clip) {
        return clips.get(clip).contains(LOOP_MARK);
    }
}
