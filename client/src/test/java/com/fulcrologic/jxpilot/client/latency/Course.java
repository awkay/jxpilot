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
package com.fulcrologic.jxpilot.client.latency;

import com.fulcrologic.jxpilot.common.net.ControlKey;

/**
 * A scripted flight on a shipped map (docs/latency-fixes.md §7.4 courses): the map, and the keys held as a
 * function of the time since the ship first appeared. Every course thrusts away from the base for half a second
 * and then coasts, so the map decides the motion: open space with mild gravity, a tight walled box, or a field
 * of gravity attractors.
 */
enum Course {
    /** globe.xp: a long coast across open space under the map's gravity. */
    FREE_FLIGHT("globe"),
    /** tournament.xp: a small walled box; the ship keeps bouncing off its walls. */
    WALLS("tournament"),
    /** pad.xp: no global gravity, but gravity attractor blocks pull the ship back and forth. */
    GRAVITY_WELL("pad");

    static final long MS = 1_000_000L;
    static final long THRUST = ControlKey.THRUST.mask();

    final String map;

    Course(String map) {
        this.map = map;
    }

    /** BEHAVIOR: thrust from 300 to 800 ms after the spawn, nothing otherwise (also before the spawn). */
    long keys(long sinceSpawn) {
        return sinceSpawn >= 300 * MS && sinceSpawn < 800 * MS ? THRUST : 0;
    }

    /** The server command line for this course with {@code robots} robots (min and max). */
    String[] serverArgs(int robots) {
        String r = Integer.toString(robots);
        return new String[] {"--map", "../maps/" + map + ".xp", "-maxRobots", r, "-minRobots", r};
    }
}
