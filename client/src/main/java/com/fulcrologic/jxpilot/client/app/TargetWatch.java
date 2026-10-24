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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.map.MapState;

/**
 * R14: notices when the set of destroyed targets changes (C redraws the radar picture then), for any number
 * of targets. Allocates only when the map changes ({@link #reset}).
 */
public final class TargetWatch {
    private boolean[] alive = new boolean[0];
    private boolean fresh = true;

    /** BEHAVIOR: a new map with {@code n} targets: the next {@link #changed} reports a change. */
    public void reset(int n) {
        if (alive.length != n) {
            alive = new boolean[n];
        }
        fresh = true;
    }

    /**
     * BEHAVIOR: true when any target's alive state differs from the last call (or after {@link #reset});
     * remembers the states.
     */
    public boolean changed(MapState st) {
        boolean c = fresh;
        fresh = false;
        for (int i = 0; i < alive.length; i++) {
            boolean a = st.targetAlive(i);
            if (a != alive[i]) {
                alive[i] = a;
                c = true;
            }
        }
        return c;
    }
}
