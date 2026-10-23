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

import com.fulcrologic.jxpilot.common.Const;

import java.util.Arrays;

/**
 * Spatial bucketing of pool objects by map block, a port of {@code src/server/cell.c}. Rebuilt once per
 * tick (after objects moved); {@link #query} lists the objects in the blocks around a block, nearest
 * blocks first, exactly as C {@code Cell_get_objects}. Preallocated, allocation free per tick.
 */
public final class Grid {
    /** C {@code MAX_CELL_DIST} (cell.c). */
    static final int MAX_CELL_DIST = 28;

    private final int w;
    private final int h;
    private final boolean wrap;
    private final int[] head;
    private final int[] next;
    /** Block offsets sorted by distance (C {@code cell_dist}). */
    final int[] offX;
    final int[] offY;
    final float[] offDist;
    /** Result of the last {@link #query}. */
    public final int[] result;
    public int resultCount;

    public Grid(int width, int height, boolean wrap, int capacity) {
        this.w = width;
        this.h = height;
        this.wrap = wrap;
        head = new int[width * height];
        next = new int[capacity];
        result = new int[capacity];
        int right;
        int left;
        int up;
        int down;
        if (wrap) {
            right = Math.min(MAX_CELL_DIST, width / 2);
            left = Math.min(MAX_CELL_DIST, (width - 1) / 2);
            up = Math.min(MAX_CELL_DIST, height / 2);
            down = Math.min(MAX_CELL_DIST, (height - 1) / 2);
        } else {
            right = Math.min(MAX_CELL_DIST, width - 1);
            left = right;
            up = Math.min(MAX_CELL_DIST, height - 1);
            down = up;
        }
        int n = (left + 1 + right) * (down + 1 + up);
        long[] keys = new long[n];
        int k = 0;
        for (int y = -down; y <= up; y++) {
            for (int x = -left; x <= right; x++) {
                float d = (float) StrictMath.hypot(x, y);
                int max = Math.max(Math.abs(x), Math.abs(y));
                // sort key: distance, then Chebyshev distance (C Compare_cell_dist), then scan order
                keys[k] = ((long) Float.floatToIntBits(d) << 32) | ((long) max << 16) | k;
                k++;
            }
        }
        Arrays.sort(keys);
        offX = new int[n];
        offY = new int[n];
        offDist = new float[n];
        int width0 = left + 1 + right;
        for (int i = 0; i < n; i++) {
            int idx = (int) (keys[i] & 0xFFFF);
            offX[i] = idx % width0 - left;
            offY[i] = idx / width0 - down;
            offDist[i] = Float.intBitsToFloat((int) (keys[i] >>> 32));
        }
        Arrays.fill(head, -1);
    }

    /**
     * Bucket every live object by its block, as C {@code Cell_add_object} after a frame of moves.
     * <p>
     * BEHAVIOR: the block is {@code pixel / BLOCK_SZ} truncated toward zero (C {@code pos.bx}); an object whose
     * block is outside the world is in no cell and is never returned by {@link #query}.
     * BEHAVIOR: within a block, objects are listed in reverse pool order ({@code Objects.active} from last to
     * first): C prepends each object to its cell list as it is moved in pool order, and objects created later
     * in the frame (higher indices) are prepended after that.
     */
    public void rebuild(Objects o) {
        Arrays.fill(head, -1);
        int n = o.count();
        for (int k = 0; k < n; k++) {
            int hnd = o.active[k];
            int bx = o.px[hnd] / Const.BLOCK_SZ;
            int by = o.py[hnd] / Const.BLOCK_SZ;
            if (bx < 0 || bx >= w || by < 0 || by >= h) {
                continue;
            }
            int c = by * w + bx;
            next[hnd] = head[c];
            head[c] = hnd;
        }
    }

    /**
     * Objects in blocks within {@code range * sqrt(2)} blocks of (bx, by), nearest blocks first, at most
     * {@code max} (C {@code Cell_get_objects}). Results in {@link #result}[0..{@link #resultCount}).
     * <p>
     * BEHAVIOR: blocks across a world edge wrap when the map wraps, else are skipped.
     */
    public void query(int bx, int by, int range, int max) {
        float dist = (float) (range * Math.sqrt(2.0));
        int count = 0;
        for (int i = 0; i < offX.length && count < max; i++) {
            if (dist < offDist[i]) {
                break;
            }
            int xw = bx + offX[i];
            int yw = by + offY[i];
            if (xw < 0 || xw >= w) {
                if (!wrap) {
                    continue;
                }
                xw = Math.floorMod(xw, w);
            }
            if (yw < 0 || yw >= h) {
                if (!wrap) {
                    continue;
                }
                yw = Math.floorMod(yw, h);
            }
            for (int o = head[yw * w + xw]; o >= 0 && count < max; o = next[o]) {
                result[count++] = o;
            }
        }
        resultCount = count;
    }
}
