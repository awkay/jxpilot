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

/**
 * Expanding ECM blasts (C {@code Ecms[]} of {@code ecm_t}) and transporter beams (C {@code Transporters[]} of
 * {@code trans_t}): two small fixed-capacity, dense struct-of-arrays lists; removing entry {@code i} moves the
 * last one into its place, as C does.
 */
public final class Ecms {
    /** C {@code MAX_TOTAL_ECMS}. */
    public static final int CAPACITY = 64;
    /** C {@code MAX_TOTAL_TRANSPORTERS}. */
    public static final int TRANSPORTER_CAPACITY = 2 * 64;

    // ---- ECM blasts
    public final float[] x = new float[CAPACITY];
    public final float[] y = new float[CAPACITY];
    /** C {@code size}: the radius, halved every frame; the blast ends at 0. */
    public final int[] size = new int[CAPACITY];
    /** Owner id or {@link Obj#NO_ID}. */
    public final int[] owner = new int[CAPACITY];
    private int count;

    // ---- transporter beams
    public final float[] transX = new float[TRANSPORTER_CAPACITY];
    public final float[] transY = new float[TRANSPORTER_CAPACITY];
    /** Victim id. */
    public final int[] transTarget = new int[TRANSPORTER_CAPACITY];
    /** Thief id or {@link Obj#NO_ID} (a cannon). */
    public final int[] transOwner = new int[TRANSPORTER_CAPACITY];
    /** Frames left. */
    public final int[] transCount = new int[TRANSPORTER_CAPACITY];
    private int transporters;

    /** C {@code NumEcms}. */
    public int count() {
        return count;
    }

    /** BEHAVIOR: index of a new blast, or -1 when full. */
    public int add() {
        return count < CAPACITY ? count++ : -1;
    }

    /** BEHAVIOR: the last blast moves into slot {@code i}. */
    public void remove(int i) {
        int last = --count;
        if (i < last) {
            x[i] = x[last];
            y[i] = y[last];
            size[i] = size[last];
            owner[i] = owner[last];
        }
    }

    /** C {@code NumTransporters}. */
    public int transporters() {
        return transporters;
    }

    /** BEHAVIOR: index of a new transporter beam, or -1 when full. */
    public int addTransporter() {
        return transporters < TRANSPORTER_CAPACITY ? transporters++ : -1;
    }

    /** BEHAVIOR: the last beam moves into slot {@code i}. */
    public void removeTransporter(int i) {
        int last = --transporters;
        if (i < last) {
            transX[i] = transX[last];
            transY[i] = transY[last];
            transTarget[i] = transTarget[last];
            transOwner[i] = transOwner[last];
            transCount[i] = transCount[last];
        }
    }
}
