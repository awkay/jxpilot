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

    /** The wire's "no id" (65535) as stored. */
    static final float NO_ID = 65535f;

    private final int cap;
    private final int[] count = new int[KINDS];
    /** Interpolation scratch: later objects already continued. */
    private final boolean[] taken;
    /** Per kind: continuation in the later frame of the last association, or -1. */
    final int[][] next = new int[KINDS][];
    /** Per kind: estimated velocity (px per original frame) and {@link Tracker} state. */
    final float[][] vx = new float[KINDS][];
    final float[][] vy = new float[KINDS][];
    final byte[][] state = new byte[KINDS][];
    private final float[][] data = new float[KINDS][];

    /** @param cap objects per kind */
    public ObjectLists(int cap) {
        this.cap = cap;
        taken = new boolean[cap];
        for (int k = 0; k < KINDS; k++) {
            next[k] = new int[cap];
            vx[k] = new float[cap];
            vy[k] = new float[cap];
            state[k] = new byte[cap];
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
        int i = o / STRIDE;
        next[kind][i] = -1;
        vx[kind][i] = 0;
        vy[kind][i] = 0;
        state[kind][i] = Tracker.UNKNOWN;
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

    /** Kinds that move and are tracked between snapshots (U1): positions interpolated, velocities estimated. */
    static final int[] TRACKED = {ITEM, MINE, MISSILE, BALL, ASTEROID, WRECK};
    /** Fastest an untracked object is assumed to move (px per original frame) when looking for it. */
    public static final double MAX_OBJECT_SPEED = 100;

    /**
     * BEHAVIOR: the object id of missiles (field 5) and balls (field 4); -1 for kinds without one.
     */
    static int idField(int kind) {
        return kind == MISSILE ? MISSILE_ID : kind == BALL ? BALL_ID : -1;
    }

    /**
     * Association key of object i: its id when it has one (only the same id continues it), else a per-kind
     * class (item type, missile kind, asteroid size, wreck shape; 0 for mines and balls) kept apart from ids.
     */
    int key(int kind, int i) {
        int f = idField(kind);
        if (f >= 0 && get(kind, i, f) != NO_ID) {
            return (int) get(kind, i, f);
        }
        int cls = switch (kind) {
            case ITEM, ASTEROID, WRECK -> (int) get(kind, i, 2);
            case MISSILE -> (int) get(kind, i, 3);
            default -> 0;
        };
        return -1 - cls;
    }

    /**
     * Link this (earlier) frame's moving objects to their continuations in the later frame {@code b} with the
     * {@link Tracker} and estimate {@code b}'s velocities (U1: fast objects are followed by their velocity,
     * untracked ones within {@link #MAX_OBJECT_SPEED} only when unambiguous).
     * <p>
     * BEHAVIOR: per tracked kind: keys from {@link #key}; this frame's states and velocities from the previous
     * association; afterwards {@link #next} of this frame and the velocities/states of {@code b} are set.
     */
    void associate(ObjectLists b, Tracker t, Scratch s, double frames, double worldW, double worldH, boolean wrap) {
        for (int kind : TRACKED) {
            int n = count[kind];
            int m = b.count[kind];
            for (int i = 0; i < n; i++) {
                s.ax[i] = get(kind, i, 0);
                s.ay[i] = get(kind, i, 1);
                s.ka[i] = key(kind, i);
            }
            for (int j = 0; j < m; j++) {
                s.bx[j] = b.get(kind, j, 0);
                s.by[j] = b.get(kind, j, 1);
                s.kb[j] = b.key(kind, j);
            }
            t.link(n, s.ax, s.ay, vx[kind], vy[kind], state[kind], s.ka, m, s.bx, s.by, s.kb, frames, MAX_OBJECT_SPEED,
                2, 0, worldW, worldH, wrap, next[kind]);
            t.estimate(m, s.ax, s.ay, s.bx, s.by, b.vx[kind], b.vy[kind], b.state[kind], frames);
        }
    }

    /** Preallocated association scratch (one per {@link ClientWorld}). */
    static final class Scratch {
        final float[] ax;
        final float[] ay;
        final float[] bx;
        final float[] by;
        final int[] ka;
        final int[] kb;

        Scratch(int cap) {
            ax = new float[cap];
            ay = new float[cap];
            bx = new float[cap];
            by = new float[cap];
            ka = new int[cap];
            kb = new int[cap];
        }
    }

    /**
     * This list is a copy of {@code a}: move its tracked objects to the render time.
     * <p>
     * BEHAVIOR: an object linked to {@code b} ({@code a}'s association) moves toward its continuation by
     * {@code t}, wrap-aware (a missile's heading turns the short way); an unlinked object with a known velocity
     * moves by it for {@code dtFrames} original frames; others stay. With {@code b} null every object with a
     * known velocity is extrapolated.
     */
    void moveTracked(ObjectLists a, ObjectLists b, double t, double dtFrames, double worldW, double worldH,
                     boolean wrap) {
        for (int kind : TRACKED) {
            for (int i = 0; i < count[kind]; i++) {
                int j = b == null ? -1 : a.next[kind][i];
                if (j >= 0 && j < b.count[kind]) {
                    move(kind, i, b, j, 0, t, worldW, worldH, wrap);
                    if (kind == MISSILE) {
                        double h = get(kind, i, 2);
                        set(kind, i, 2, (float) XMath.normalizeAngle(h + XMath.angleDiff(h, b.get(kind, j, 2)) * t));
                    }
                } else if (a.state[kind][i] == Tracker.KNOWN) {
                    set(kind, i, 0, (float) wrapTo(get(kind, i, 0) + a.vx[kind][i] * dtFrames, worldW, wrap));
                    set(kind, i, 1, (float) wrapTo(get(kind, i, 1) + a.vy[kind][i] * dtFrames, worldH, wrap));
                }
            }
        }
    }

    /**
     * Move this frame's connectors and transporter beams (a copy of the earlier frame) toward their
     * continuations in the later frame {@code b} by fraction {@code t}, wrap-aware.
     * <p>
     * BEHAVIOR: a connector or beam continues as the later one of the same kind (connectors) whose endpoints
     * are each within {@code maxDist} and nearest in sum; both endpoints are interpolated; unmatched stay.
     */
    public void interpolateLines(ObjectLists b, double t, double maxDist, double worldW, double worldH, boolean wrap) {
        interpolateLines(CONNECTOR, 4, b, t, maxDist, worldW, worldH, wrap);
        interpolateLines(TRANSPORTER, -1, b, t, maxDist, worldW, worldH, wrap);
    }

    private void interpolateLines(int kind, int kindField, ObjectLists b, double t, double maxDist, double worldW,
                                  double worldH, boolean wrap) {
        int n = count[kind];
        int nb = b.count[kind];
        java.util.Arrays.fill(taken, 0, nb, false);
        double max2 = maxDist * maxDist;
        for (int i = 0; i < n; i++) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int j = 0; j < nb; j++) {
                if (taken[j] || kindField >= 0 && b.get(kind, j, kindField) != get(kind, i, kindField)) {
                    continue;
                }
                double d0 = dist2(kind, i, b, j, 0, worldW, worldH, wrap);
                double d1 = dist2(kind, i, b, j, 2, worldW, worldH, wrap);
                if (d0 <= max2 && d1 <= max2 && d0 + d1 < bestD) {
                    bestD = d0 + d1;
                    best = j;
                }
            }
            if (best >= 0) {
                taken[best] = true;
                move(kind, i, b, best, 0, t, worldW, worldH, wrap);
                move(kind, i, b, best, 2, t, worldW, worldH, wrap);
            }
        }
    }

    /** Squared wrap-aware distance between point (field, field + 1) of this object i and b's object j. */
    private double dist2(int kind, int i, ObjectLists b, int j, int field, double worldW, double worldH,
                         boolean wrap) {
        double dx = XMath.wrapDelta(b.get(kind, j, field) - get(kind, i, field), worldW, wrap);
        double dy = XMath.wrapDelta(b.get(kind, j, field + 1) - get(kind, i, field + 1), worldH, wrap);
        return dx * dx + dy * dy;
    }

    private void move(int kind, int i, ObjectLists b, int j, int field, double t, double worldW, double worldH,
                      boolean wrap) {
        double dx = XMath.wrapDelta(b.get(kind, j, field) - get(kind, i, field), worldW, wrap);
        double dy = XMath.wrapDelta(b.get(kind, j, field + 1) - get(kind, i, field + 1), worldH, wrap);
        set(kind, i, field, (float) wrapTo(get(kind, i, field) + dx * t, worldW, wrap));
        set(kind, i, field + 1, (float) wrapTo(get(kind, i, field + 1) + dy * t, worldH, wrap));
    }

    private static double wrapTo(double v, double size, boolean wrap) {
        return wrap ? XMath.wrap(v, size) : v;
    }
}
