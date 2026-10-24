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

import com.fulcrologic.jxpilot.common.sim.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
/**
 * Laser pulses in flight (C {@code Pulses[]} of {@code pulse_t}), a fixed-capacity struct of arrays kept dense
 * in C order: removing pulse {@code i} moves the last one into its place, like C {@code Laser_pulse_destroy_one}.
 */
public final class Pulses {
    /** C {@code MAX_TOTAL_PULSES}. */
    public static final int CAPACITY = 5 * 64;

    /** Start position in pixels (C {@code position}, a float). */
    public final float[] x = new float[CAPACITY];
    public final float[] y = new float[CAPACITY];
    /** Direction (RES units). */
    public final int[] dir = new int[CAPACITY];
    /** Length in pixels. */
    public final int[] len = new int[CAPACITY];
    /** Frames left. */
    public final int[] life = new int[CAPACITY];
    /** Owner id or {@link Obj#NO_ID} (cannon). */
    public final int[] owner = new int[CAPACITY];
    public final int[] team = new int[CAPACITY];
    /** {@link Mods} of the shooter. */
    public final int[] mods = new int[CAPACITY];
    /** Reflected by a mirror. */
    public final boolean[] refl = new boolean[CAPACITY];
    private int count;

    /** C {@code NumPulses}. */
    public int count() {
        return count;
    }

    /** BEHAVIOR: returns the index of a new pulse, or -1 when all {@link #CAPACITY} are in use. */
    public int add() {
        return count < CAPACITY ? count++ : -1;
    }

    /** BEHAVIOR: the last pulse moves into slot {@code i}; the count drops by one. */
    public void remove(int i) {
        int last = --count;
        if (i < last) {
            x[i] = x[last];
            y[i] = y[last];
            dir[i] = dir[last];
            len[i] = len[last];
            life[i] = life[last];
            owner[i] = owner[last];
            team[i] = team[last];
            mods[i] = mods[last];
            refl[i] = refl[last];
        }
    }

    /** Remove every pulse. */
    public void clear() {
        count = 0;
    }
}
