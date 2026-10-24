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
 * and then coasts, so the map decides the motion: open space with mild gravity, a tight walled box, a field
 * of gravity attractors, or a map small enough that the robots are always close. Ball carrying (§7.4) is left
 * for phase 3, which models the ball connector.
 */
enum Course {
    /** globe.xp: a long coast across open space under the map's gravity. */
    FREE_FLIGHT("globe", 3, true),
    /** tournament.xp: a small walled box; the ship keeps bouncing off its walls. */
    WALLS("tournament", 3, true),
    /** pad.xp: no global gravity, but gravity attractor blocks pull the ship back and forth. */
    GRAVITY_WELL("pad", 3, false),
    /**
     * doggy.xp: the smallest shipped map (49 x 50 blocks), so the robots of the motion runs stay in view and the
     * others' aim error is measured in every cell.
     */
    DOGFIGHT("doggy", 6, false);

    static final long MS = 1_000_000L;
    static final long THRUST = ControlKey.THRUST.mask();

    final String map;
    /** Robots in the motion runs (the I2P runs have none). */
    final int motionRobots;
    /**
     * Shot I2P is measured on this course. Shot trials drop the spawn shield (a shielded ship cannot fire), and
     * unshielded the ship crashes within seconds on the gravity well (into the attractor field's blocks) and the
     * dogfight (walls and robots), losing whole blocks of trials, which would bias the stratified phases. Shot I2P
     * does not depend on the course, so free flight and walls measure it.
     */
    final boolean shotTrials;

    Course(String map, int motionRobots, boolean shotTrials) {
        this.map = map;
        this.motionRobots = motionRobots;
        this.shotTrials = shotTrials;
    }

    /** BEHAVIOR: thrust from 300 to 800 ms after the spawn, nothing otherwise (also before the spawn). */
    long keys(long sinceSpawn) {
        return sinceSpawn >= 300 * MS && sinceSpawn < 800 * MS ? THRUST : 0;
    }

    /**
     * BEHAVIOR: an active pilot for the exactness runs, repeating every 2 s after the spawn: turn left 0-300 ms,
     * thrust 400-700 ms, fire 900-950 ms, shield 1200-1300 ms (its release also drops the spawn shield).
     */
    static long pilot(long sinceSpawn) {
        if (sinceSpawn < 0) {
            return 0;
        }
        long ms = (sinceSpawn / MS) % 2000;
        long k = 0;
        if (ms < 300) {
            k |= ControlKey.TURN_LEFT.mask();
        }
        if (ms >= 400 && ms < 700) {
            k |= THRUST;
        }
        if (ms >= 900 && ms < 950) {
            k |= ControlKey.FIRE_SHOT.mask();
        }
        if (ms >= 1200 && ms < 1300) {
            k |= ControlKey.SHIELD.mask();
        }
        return k;
    }

    /** The server command line for this course with {@code robots} robots (min and max). */
    String[] serverArgs(int robots) {
        return serverArgs(robots, false);
    }

    /** As {@link #serverArgs(int)}, with the server option predictableBounces when {@code predictable}. */
    String[] serverArgs(int robots, boolean predictable) {
        String r = Integer.toString(robots);
        return predictable
            ? new String[] {"--map", "../maps/" + map + ".xp", "-maxRobots", r, "-minRobots", r, "-predictableBounces", "yes"}
            : new String[] {"--map", "../maps/" + map + ".xp", "-maxRobots", r, "-minRobots", r};
    }
}
