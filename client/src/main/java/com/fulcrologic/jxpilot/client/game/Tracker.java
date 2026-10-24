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
 * Links the objects of an earlier snapshot to their continuations in a later one, for objects the wire sends
 * as bare positions (C fast shots, debris, M2 objects), so the client can interpolate them. Pure and allocation
 * free after construction.
 * <p>
 * Each earlier object has a velocity state: {@link #KNOWN} (from the wire or an earlier unambiguous link: the
 * continuation is searched for tightly around the predicted position), {@link #GUESSED} (a seed such as the
 * firing ship's heading plus the shot speed: searched around the prediction, but only taken when unambiguous)
 * or {@link #UNKNOWN} (searched within the maximum speed, only taken when unambiguous). Unambiguous: the
 * second-nearest candidate is at least {@code ratio} times as far as the nearest (R2).
 * <p>
 * Candidates are found through a uniform grid hash over the later objects ({@link #CELL_PX} cells), so
 * association is O(n) and there is no size cliff (R5).
 */
public final class Tracker {
    /** No velocity estimate. */
    public static final byte UNKNOWN = 0;
    /** A seeded guess (not yet confirmed by a link). */
    public static final byte GUESSED = 1;
    /** Velocity from the wire or a confirmed link. */
    public static final byte KNOWN = 2;

    /** Search radius around a KNOWN prediction: a fixed slack (integer positions, gravity) ... */
    public static final double KNOWN_SLACK_PX = 4;
    /** ... plus this fraction of the distance travelled. */
    public static final double KNOWN_SLACK_FRACTION = 0.15;
    /** Search radius around a GUESSED prediction: slack plus this fraction of the distance travelled. */
    public static final double GUESS_SLACK_FRACTION = 0.4;

    private static final int BUCKETS = 4096;
    /**
     * Fine grid cell (px), about the KNOWN/GUESSED search radius: a tracked object looks at a 3x3 block of
     * nearly empty cells. UNKNOWN searches use a second grid whose cell is their (much larger) radius.
     */
    static final double CELL_PX = 16;

    /**
     * A uniform grid hash over the later objects. The world is a whole number of cells (each at least the
     * requested size), so neighbouring cells across the wrap seam are as wide as any others; without wrap,
     * positions outside the world fall in the edge cells.
     */
    private static final class Grid {
        final int[] head = new int[BUCKETS];
        final int[] chain;
        double cellW;
        double cellH;
        int cellsX;
        int cellsY;
        boolean wrap;

        Grid(int cap) {
            chain = new int[cap];
        }

        void build(int m, float[] bx, float[] by, double size, double worldW, double worldH, boolean wrapping) {
            cellsX = Math.max(1, (int) (worldW / size));
            cellsY = Math.max(1, (int) (worldH / size));
            cellW = worldW / cellsX;
            cellH = worldH / cellsY;
            wrap = wrapping;
            java.util.Arrays.fill(head, -1);
            for (int j = 0; j < m; j++) {
                int h = bucket(cellX(bx[j]), cellY(by[j]));
                chain[j] = head[h];
                head[h] = j;
            }
        }

        int cellX(double x) {
            return cell(x / cellW, cellsX);
        }

        int cellY(double y) {
            return cell(y / cellH, cellsY);
        }

        private int cell(double c, int cells) {
            int i = (int) Math.floor(c);
            return wrap ? Math.floorMod(i, cells) : Math.max(0, Math.min(cells - 1, i));
        }

        static int bucket(int cx, int cy) {
            return (cx * 73856093 ^ cy * 19349663) & (BUCKETS - 1);
        }
    }

    private final Grid fine;
    private final Grid coarse;
    private final int[] stamp;
    private int query;
    private final int[] claimA;
    private final float[] claimD;
    private double worldW;
    private double worldH;
    private boolean wrap;
    // results of the last search
    private int best;
    private double bestD;
    private double secondD;
    private double minUnknown2;

    /** @param cap most later objects per link */
    public Tracker(int cap) {
        fine = new Grid(cap);
        coarse = new Grid(cap);
        stamp = new int[cap];
        claimA = new int[cap];
        claimD = new float[cap];
    }

    /** The earlier object that continues as later object {@code j} after the last {@link #link}, or -1. */
    public int claimOf(int j) {
        return claimA[j];
    }

    /**
     * Link {@code n} earlier objects to {@code m} later ones of the same key.
     * <p>
     * BEHAVIOR: an earlier object's prediction is its position plus velocity * frames (its position when
     * UNKNOWN); KNOWN objects take the nearest same-key later object within {@link #KNOWN_SLACK_PX} +
     * {@link #KNOWN_SLACK_FRACTION} * travel; GUESSED ones within the slack + {@link #GUESS_SLACK_FRACTION} *
     * travel and UNKNOWN ones within {@code maxSpeed * frames} + slack, and those two only when the second
     * nearest is at least {@code ratio} times farther than the nearest (ratio 1 accepts the nearest); an
     * UNKNOWN object never continues as one closer than {@code minSpeed * frames} (a shot does not stand
     * still: in a stream the shot behind arrives where it was).
     * BEHAVIOR: each later object keeps its closest proposer; earlier objects left over then take the best
     * acceptable later object nobody claimed. {@code next[i]} is the later index or -1, wrap-aware throughout.
     * BEHAVIOR: frames &lt;= 0 links nothing.
     *
     * @return how many earlier objects were linked
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public int link(int n, float[] ax, float[] ay, float[] avx, float[] avy, byte[] aState, int[] aKey,
                    int m, float[] bx, float[] by, int[] bKey, double frames, double maxSpeed, double ratio,
                    double minSpeed, double worldWidth, double worldHeight, boolean wrapping, int[] next) {
        for (int j = 0; j < m; j++) {
            claimA[j] = -1;
        }
        for (int i = 0; i < n; i++) {
            next[i] = -1;
        }
        if (frames <= 0 || n == 0 || m == 0) {
            return 0;
        }
        worldW = worldWidth;
        worldH = worldHeight;
        wrap = wrapping;
        fine.build(m, bx, by, CELL_PX, worldW, worldH, wrap);
        boolean anyUnknown = false;
        for (int i = 0; i < n && !anyUnknown; i++) {
            anyUnknown = aState[i] == UNKNOWN;
        }
        if (anyUnknown) {
            coarse.build(m, bx, by, Math.max(CELL_PX, maxSpeed * frames + KNOWN_SLACK_PX), worldW, worldH, wrap);
        }
        minUnknown2 = minSpeed * frames * minSpeed * frames;
        for (int i = 0; i < n; i++) {
            if (search(i, ax, ay, avx, avy, aState, aKey, bx, by, bKey, frames, maxSpeed, ratio, false)
                && (claimA[best] < 0 || bestD < claimD[best])) {
                claimA[best] = i;
                claimD[best] = (float) bestD;
            }
        }
        int linked = 0;
        for (int j = 0; j < m; j++) {
            if (claimA[j] >= 0) {
                next[claimA[j]] = j;
                linked++;
            }
        }
        for (int i = 0; i < n; i++) {
            if (next[i] < 0 && search(i, ax, ay, avx, avy, aState, aKey, bx, by, bKey, frames, maxSpeed, ratio, true)) {
                next[i] = best;
                claimA[best] = i;
                claimD[best] = (float) bestD;
                linked++;
            }
        }
        return linked;
    }

    /** Nearest and second-nearest same-key candidates of earlier object i; true when acceptable. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private boolean search(int i, float[] ax, float[] ay, float[] avx, float[] avy, byte[] aState, int[] aKey,
                           float[] bx, float[] by, int[] bKey, double frames, double maxSpeed, double ratio,
                           boolean unclaimedOnly) {
        byte state = aState[i];
        double vx = state == UNKNOWN ? 0 : avx[i];
        double vy = state == UNKNOWN ? 0 : avy[i];
        double px = ax[i] + vx * frames;
        double py = ay[i] + vy * frames;
        double travel = Math.sqrt(vx * vx + vy * vy) * frames;
        double r = switch (state) {
            case KNOWN -> KNOWN_SLACK_PX + KNOWN_SLACK_FRACTION * travel;
            case GUESSED -> KNOWN_SLACK_PX + GUESS_SLACK_FRACTION * travel;
            default -> maxSpeed * frames + KNOWN_SLACK_PX;
        };
        if (wrap) {
            px = XMath.wrap(px, worldW);
            py = XMath.wrap(py, worldH);
        }
        double r2 = r * r;
        best = -1;
        bestD = Double.MAX_VALUE;
        secondD = Double.MAX_VALUE;
        query++;
        Grid g = state == UNKNOWN ? coarse : fine;
        int kx = (int) Math.ceil(r / g.cellW);
        int ky = (int) Math.ceil(r / g.cellH);
        int cx = g.cellX(px);
        int cy = g.cellY(py);
        // with wrap, a span covering the whole world visits each column/row once
        boolean allX = wrap && 2 * kx + 1 >= g.cellsX;
        boolean allY = wrap && 2 * ky + 1 >= g.cellsY;
        int x0 = allX ? 0 : cx - kx;
        int nx = allX ? g.cellsX : 2 * kx + 1;
        int y0 = allY ? 0 : cy - ky;
        int ny = allY ? g.cellsY : 2 * ky + 1;
        int key = aKey[i];
        for (int ty = 0; ty < ny; ty++) {
            int gy = y0 + ty;
            if (gy < 0 || gy >= g.cellsY) {
                if (!wrap) {
                    continue;
                }
                gy = gy < 0 ? gy + g.cellsY : gy - g.cellsY;
            }
            for (int tx = 0; tx < nx; tx++) {
                int gx = x0 + tx;
                if (gx < 0 || gx >= g.cellsX) {
                    if (!wrap) {
                        continue;
                    }
                    gx = gx < 0 ? gx + g.cellsX : gx - g.cellsX;
                }
                for (int j = g.head[Grid.bucket(gx, gy)]; j >= 0; j = g.chain[j]) {
                    if (stamp[j] == query) {
                        continue;
                    }
                    stamp[j] = query;
                    if (bKey[j] != key || unclaimedOnly && claimA[j] >= 0) {
                        continue;
                    }
                    double ddx = XMath.wrapDelta(bx[j] - px, worldW, wrap);
                    double ddy = XMath.wrapDelta(by[j] - py, worldH, wrap);
                    double d = ddx * ddx + ddy * ddy;
                    if (d > r2 || state == UNKNOWN && d < minUnknown2) {
                        continue;
                    }
                    if (d < bestD) {
                        secondD = bestD;
                        bestD = d;
                        best = j;
                    } else if (d < secondD) {
                        secondD = d;
                    }
                }
            }
        }
        if (best < 0) {
            return false;
        }
        return state == KNOWN || secondD == Double.MAX_VALUE || secondD >= ratio * ratio * bestD;
    }

    /**
     * Velocities of the later objects from the last {@link #link}.
     * <p>
     * BEHAVIOR: a later object continuing an earlier one gets the wrap-aware displacement / frames and KNOWN;
     * the others get 0 and UNKNOWN.
     */
    public void estimate(int m, float[] ax, float[] ay, float[] bx, float[] by, float[] bvx, float[] bvy,
                         byte[] bState, double frames) {
        for (int j = 0; j < m; j++) {
            int i = claimA[j];
            if (i >= 0 && frames > 0) {
                bvx[j] = (float) (XMath.wrapDelta(bx[j] - ax[i], worldW, wrap) / frames);
                bvy[j] = (float) (XMath.wrapDelta(by[j] - ay[i], worldH, wrap) / frames);
                bState[j] = KNOWN;
            } else {
                bvx[j] = 0;
                bvy[j] = 0;
                bState[j] = UNKNOWN;
            }
        }
    }
}
