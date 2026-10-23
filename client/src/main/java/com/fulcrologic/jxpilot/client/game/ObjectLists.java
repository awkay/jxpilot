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

import com.fulcrologic.jxpilot.common.math.XMath;

/**
 * The M2 objects of one frame (items, mines, missiles, balls, lasers, asteroids, wreckage, ECM flashes,
 * connectors, transporter beams), each kind a fixed-capacity list of {@link #STRIDE} floats per object.
 * Field layout per kind (angles in radians, ids as floats, {@code NO_ID} = 65535):
 * <pre>
 *   ITEM        x, y, item type
 *   MINE        x, y, flags (Records.Mine), owner id
 *   MISSILE     x, y, heading, kind, length, id
 *   BALL        x, y, team, holder id, id
 *   LASER       x, y, heading, length, colour (Records.ObjColor)
 *   ASTEROID    x, y, size, rotation
 *   WRECK       x, y, shape, size, rotation
 *   ECM         x, y, radius
 *   CONNECTOR   x0, y0, x1, y1, kind (Records.Connector)
 *   TRANSPORTER x0, y0, x1, y1
 * </pre>
 * Allocation free after construction.
 */
public final class ObjectLists {
    public static final int ITEM = 0;
    public static final int MINE = 1;
    public static final int MISSILE = 2;
    public static final int BALL = 3;
    public static final int LASER = 4;
    public static final int ASTEROID = 5;
    public static final int WRECK = 6;
    public static final int ECM = 7;
    public static final int CONNECTOR = 8;
    public static final int TRANSPORTER = 9;
    public static final int KINDS = 10;
    /** Floats per object. */
    public static final int STRIDE = 6;
    /** Missile and ball id field. */
    static final int MISSILE_ID = 5;
    static final int BALL_ID = 4;

    private final int cap;
    private final int[] count = new int[KINDS];
    private final float[][] data = new float[KINDS][];

    /** @param cap objects per kind */
    public ObjectLists(int cap) {
        this.cap = cap;
        for (int k = 0; k < KINDS; k++) {
            data[k] = new float[cap * STRIDE];
        }
    }

    public int capacity() { return cap; }

    /** BEHAVIOR: empties every kind. */
    public void clear() {
        for (int k = 0; k < KINDS; k++) {
            count[k] = 0;
        }
    }

    /** BEHAVIOR: appends an object of {@code kind}; false (nothing added) when that kind is full. */
    public boolean add(int kind, float f0, float f1, float f2, float f3, float f4, float f5) {
        if (count[kind] == cap) {
            return false;
        }
        float[] d = data[kind];
        int o = count[kind]++ * STRIDE;
        d[o] = f0;
        d[o + 1] = f1;
        d[o + 2] = f2;
        d[o + 3] = f3;
        d[o + 4] = f4;
        d[o + 5] = f5;
        return true;
    }

    public int count(int kind) { return count[kind]; }

    /** Field {@code field} of object {@code i} of {@code kind}. */
    public float get(int kind, int i, int field) { return data[kind][i * STRIDE + field]; }

    void set(int kind, int i, int field, float v) { data[kind][i * STRIDE + field] = v; }

    /** BEHAVIOR: replaces this list's contents with {@code src}'s. */
    public void copyFrom(ObjectLists src) {
        for (int k = 0; k < KINDS; k++) {
            int n = Math.min(src.count[k], cap);
            System.arraycopy(src.data[k], 0, data[k], 0, n * STRIDE);
            count[k] = n;
        }
    }

    /**
     * Move this frame's missiles and balls (a copy of the earlier frame) toward their continuations in
     * the later frame {@code b} by fraction {@code t}, wrap-aware.
     * <p>
     * BEHAVIOR: a ball or missile with an id (not 65535) continues as the later object with the same id;
     * one without an id as the nearest later object of its kind within {@code maxDist} that no earlier
     * object has taken (greedy in list order; exclusivity is tracked for the first 64 later objects); unmatched objects stay where they are. A missile's heading
     * turns the short way.
     */
    public void interpolateMoving(ObjectLists b, double t, double maxDist, double worldW, double worldH,
                                  boolean wrap) {
        interpolate(MISSILE, MISSILE_ID, b, t, maxDist, worldW, worldH, wrap);
        interpolate(BALL, BALL_ID, b, t, maxDist, worldW, worldH, wrap);
    }

    private void interpolate(int kind, int idField, ObjectLists b, double t, double maxDist, double worldW,
                             double worldH, boolean wrap) {
        int nb = b.count[kind];
        long used = 0;
        for (int i = 0; i < count[kind]; i++) {
            float id = get(kind, i, idField);
            int best = -1;
            double bestD = maxDist * maxDist;
            for (int j = 0; j < nb; j++) {
                float bid = b.get(kind, j, idField);
                if (id != 65535f) {
                    if (bid == id) {
                        best = j;
                        break;
                    }
                    continue;
                }
                if (j < 64 && (used & (1L << j)) != 0) {
                    continue;
                }
                double dx = XMath.wrapDelta(b.get(kind, j, 0) - get(kind, i, 0), worldW, wrap);
                double dy = XMath.wrapDelta(b.get(kind, j, 1) - get(kind, i, 1), worldH, wrap);
                double d = dx * dx + dy * dy;
                if (d <= bestD) {
                    bestD = d;
                    best = j;
                }
            }
            if (best < 0) {
                continue;
            }
            if (best < 64) {
                used |= 1L << best;
            }
            double dx = XMath.wrapDelta(b.get(kind, best, 0) - get(kind, i, 0), worldW, wrap);
            double dy = XMath.wrapDelta(b.get(kind, best, 1) - get(kind, i, 1), worldH, wrap);
            set(kind, i, 0, (float) wrapTo(get(kind, i, 0) + dx * t, worldW, wrap));
            set(kind, i, 1, (float) wrapTo(get(kind, i, 1) + dy * t, worldH, wrap));
            if (kind == MISSILE) {
                double h = get(kind, i, 2);
                set(kind, i, 2, (float) XMath.normalizeAngle(h + XMath.angleDiff(h, b.get(kind, best, 2)) * t));
            }
        }
    }

    private static double wrapTo(double v, double size, boolean wrap) {
        return wrap ? XMath.wrap(v, size) : v;
    }
}
