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
package com.fulcrologic.jxpilot.server.snapshot;

import com.fulcrologic.jxpilot.common.sim.Click;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.World;

import java.util.Arrays;

/**
 * A coarse spatial hash of the live shots (shots and cannon shots), in compressed-row form: built once per
 * snapshot round by counting sort over {@link #CELL}-pixel cells, with each shot's handle and position
 * copied next to each other. Viewers walk the cells of their view nearest first ({@link #ring}), so a
 * snapshot that runs out of room drops the farthest shots. Allocation free after construction.
 */
public final class ShotHash {
    /** Cell size in pixels. */
    public static final int CELL = 128;
    /** Ring radius in cells: covers a view half-size of up to 16 * 128 = 2048 px. */
    static final int RADIUS = 16;

    private final World w;
    final int cw;
    final int ch;
    /** Shots of cell c are entries {@code [start[c], start[c+1])}. */
    final int[] start;
    final int[] handle;
    final double[] x;
    final double[] y;
    private final int[] cellOf;
    private final int[] tmp;
    /** Cell offsets sorted by distance from (0, 0): the walk order. */
    final int[] ringX;
    final int[] ringY;
    /** Stamp per cell so a cell reached twice through wrapping is visited once. */
    private final int[] seen;
    /** Current walk stamp (package visible so tests can drive it to the wrap-around). */
    int stamp;
    /** Number of shots in the hash. */
    int count;

    public ShotHash(World w) {
        this.w = w;
        cw = (w.pixelWidth + CELL - 1) / CELL;
        ch = (w.pixelHeight + CELL - 1) / CELL;
        start = new int[cw * ch + 1];
        seen = new int[cw * ch];
        int cap = w.objects.capacity;
        handle = new int[cap];
        x = new double[cap];
        y = new double[cap];
        cellOf = new int[cap];
        tmp = new int[cap];
        int side = 2 * RADIUS + 1;
        int n = side * side;
        long[] keys = new long[n];
        for (int k = 0; k < n; k++) {
            int dx = k % side - RADIUS;
            int dy = k / side - RADIUS;
            keys[k] = ((long) (dx * dx + dy * dy) << 20) | k;
        }
        Arrays.sort(keys);
        ringX = new int[n];
        ringY = new int[n];
        for (int i = 0; i < n; i++) {
            int k = (int) (keys[i] & 0xFFFFF);
            ringX[i] = k % side - RADIUS;
            ringY[i] = k / side - RADIUS;
        }
    }

    /**
     * Rebuild from the live objects.
     * <p>
     * BEHAVIOR: every live (life &gt; 0) shot or cannon shot is in the cell of its pixel position (clamped into
     * the grid), with its position in pixels (clicks / 64); entries of a cell keep pool order.
     */
    public void rebuild() {
        Objects o = w.objects;
        Arrays.fill(start, 0);
        int m = 0;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if ((o.type[h] & (Obj.SHOT | Obj.CANNON_SHOT)) == 0 || o.life[h] <= 0) {
                continue;
            }
            int gx = Math.min(cw - 1, Math.max(0, o.px[h] / CELL));
            int gy = Math.min(ch - 1, Math.max(0, o.py[h] / CELL));
            int c = gy * cw + gx;
            cellOf[m] = c;
            tmp[m++] = h;
            start[c + 1]++;
        }
        for (int c = 0; c < cw * ch; c++) {
            start[c + 1] += start[c];
        }
        for (int i = 0; i < m; i++) {
            int pos = start[cellOf[i]]++;
            int h = tmp[i];
            handle[pos] = h;
            x[pos] = o.cx[h] / (double) Click.CLICK;
            y[pos] = o.cy[h] / (double) Click.CLICK;
        }
        for (int c = cw * ch; c > 0; c--) {
            start[c] = start[c - 1];
        }
        start[0] = 0;
        count = m;
    }

    /** Begin a walk: cells reached again through wrapping will be skipped. */
    void beginWalk() {
        if (++stamp == 0) {
            Arrays.fill(seen, 0);
            stamp = 1;
        }
    }

    /**
     * The cell index at ring position {@code k} around the cell of (vx, vy), or -1.
     * <p>
     * BEHAVIOR: -1 outside the view's cell rectangle ({@code rx}, {@code ry} cells each side), outside a
     * non-wrapping world, or for a cell already visited in this walk; on wrapping worlds cells wrap.
     */
    int cellAt(int k, int vcx, int vcy, int rx, int ry) {
        int ox = ringX[k];
        int oy = ringY[k];
        if (ox < -rx || ox > rx || oy < -ry || oy > ry) {
            return -1;
        }
        int gx = vcx + ox;
        int gy = vcy + oy;
        if (gx < 0 || gx >= cw) {
            if (!w.wrap) {
                return -1;
            }
            gx = Math.floorMod(gx, cw);
        }
        if (gy < 0 || gy >= ch) {
            if (!w.wrap) {
                return -1;
            }
            gy = Math.floorMod(gy, ch);
        }
        int c = gy * cw + gx;
        if (seen[c] == stamp) {
            return -1;
        }
        seen[c] = stamp;
        return c;
    }
}
