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
package com.fulcrologic.jxpilot.client.game.predict;

/**
 * Hides prediction corrections (docs/latency-fixes.md §4.2): when a reconciliation moves the predicted ship, the
 * drawn ship stays where it was and the difference becomes an offset that decays exponentially (position and
 * heading together, so the camera, which follows the drawn ship, glides with it). Large offsets snap. Near walls
 * the offset decays faster: crossing a wall with an offset looks like clipping. Pure; allocation free.
 */
public final class CorrectionSmoother {
    /** Default half-life of an offset (seconds). */
    public static final double HALF_LIFE_SECONDS = 0.1;
    /** Half-life while the ship touches walls. */
    public static final double WALL_HALF_LIFE_SECONDS = 0.03;
    /** Offsets above this (pixels; 3 ship lengths) snap instead of gliding. */
    public static final double SNAP_PX = 3 * 32;
    /** Offsets below this (pixels and RES units) are dropped. */
    public static final double EPSILON = 1e-3;

    /** Half-life used away from walls (tunable). */
    public double halfLifeSeconds = HALF_LIFE_SECONDS;
    private double ox;
    private double oy;
    private double oh;
    private long last;
    private boolean timed;
    private long snaps;

    /**
     * A correction: the predicted ship moved by (dx, dy) px and its heading by dh (RES units, signed shortest way).
     * <p>
     * BEHAVIOR: the offset grows by the opposite, so the drawn ship stays put; if the resulting offset is longer than
     * {@link #SNAP_PX}, or {@code snap} is asked for, it is dropped instead (the ship snaps), and a snap that was not
     * asked for counts in {@link #snaps()}.
     */
    public void correct(double dx, double dy, double dh, boolean snap) {
        double nx = ox - dx;
        double ny = oy - dy;
        if (snap || nx * nx + ny * ny > SNAP_PX * SNAP_PX) {
            if (!snap) {
                snaps++;
            }
            clear();
            return;
        }
        ox = nx;
        oy = ny;
        oh -= dh;
    }

    /**
     * Age the offset to {@code nowNanos}.
     * <p>
     * BEHAVIOR: the first call only starts the clock; then the offset is multiplied by 0.5^(elapsed / half-life),
     * the wall half-life when {@code touchingWall}; an offset under {@link #EPSILON} becomes 0. Time never runs
     * backwards (an earlier time is ignored).
     */
    public void decay(long nowNanos, boolean touchingWall) {
        if (!timed) {
            timed = true;
            last = nowNanos;
            return;
        }
        long dt = nowNanos - last;
        if (dt <= 0) {
            return;
        }
        last = nowNanos;
        double half = touchingWall ? WALL_HALF_LIFE_SECONDS : halfLifeSeconds;
        double f = Math.pow(0.5, dt / 1e9 / half);
        ox *= f;
        oy *= f;
        oh *= f;
        if (Math.abs(ox) < EPSILON && Math.abs(oy) < EPSILON && Math.abs(oh) < EPSILON) {
            ox = oy = oh = 0;
        }
    }

    /** BEHAVIOR: no offset; the clock restarts at the next {@link #decay}. */
    public void clear() {
        ox = oy = oh = 0;
        timed = false;
    }

    public double offsetX() { return ox; }

    public double offsetY() { return oy; }

    /** Heading offset in RES units. */
    public double offsetHeading() { return oh; }

    /** BEHAVIOR: the length of the position offset. */
    public double offsetPx() {
        return Math.hypot(ox, oy);
    }

    /** Corrections that snapped because they were too large (not the asked-for snaps). */
    public long snaps() { return snaps; }
}
