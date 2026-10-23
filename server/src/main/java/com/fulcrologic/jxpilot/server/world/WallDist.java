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

import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.map.Wormhole;

/**
 * Distance from each block to the nearest wall-like block in half blocks (C {@code walldist},
 * {@code Walldist_init} in {@code src/server/walls.c}). Used to skip exact wall tracing for objects far
 * from any wall. Indexed {@code by*w + bx}.
 */
public final class WallDist {
    private WallDist() {}

    /** Blocks that count as walls ({@code WALLDIST_MASK}); out-wormholes are excluded separately. */
    static boolean wallLike(int type) {
        return switch (type) {
            case BlockType.FILLED, BlockType.REC_LU, BlockType.REC_LD, BlockType.REC_RU, BlockType.REC_RD,
                 BlockType.FUEL, BlockType.CANNON, BlockType.TREASURE, BlockType.TARGET, BlockType.CHECK,
                 BlockType.WORMHOLE -> true;
            default -> false;
        };
    }

    /**
     * Breadth-first distance transform.
     * <p>
     * BEHAVIOR: wall-like blocks (except OUT wormholes) get 0; others start at min(255, 2*min(w,h)).
     * BEHAVIOR: without wrap the outermost ring of non-wall blocks gets at most 2.
     * BEHAVIOR: each step to one of the 8 neighbours adds 2; leaving a diagonal wall block through its
     * open corner adds 3 (the far corner of the triangle is open space).
     * BEHAVIOR: with wrap distances propagate across the world edges.
     */
    public static byte[] compute(WorldMap m) {
        int w = m.width();
        int h = m.height();
        boolean wrap = m.edgeWrap();
        int[] dist = new int[w * h];
        int maxdist = Math.min(255, 2 * Math.min(w, h));
        int[] qx = new int[w * h];
        int[] qy = new int[w * h];
        int qfront = 0;
        int qback = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int t = m.block(x, y);
                if (wallLike(t) && (t != BlockType.WORMHOLE
                    || m.wormholes().get(m.itemId(x, y)).type() != Wormhole.Type.OUT)) {
                    dist[y * w + x] = 0;
                    qx[qback] = x;
                    qy[qback] = y;
                    qback++;
                } else {
                    dist[y * w + x] = maxdist;
                }
            }
        }
        if (!wrap) {
            for (int x = 0; x < w; x++) {
                int step = (x == 0 || x == w - 1) ? 1 : (h - (h > 1 ? 1 : 0));
                for (int y = 0; y < h; y += step) {
                    if (dist[y * w + x] > 1) {
                        dist[y * w + x] = 2;
                        qx[qback] = x;
                        qy[qback] = y;
                        qback++;
                    }
                }
            }
        }
        int n = w * h;
        while (qfront != qback) {
            int x = qx[qfront];
            int y = qy[qfront];
            if (++qfront == n) {
                qfront = 0;
            }
            int d = dist[y * w + x];
            int mindist = d + 2;
            if (mindist >= 255) {
                continue;
            }
            int type = m.block(x, y);
            for (int dx = -1; dx <= 1; dx++) {
                if (!wrap && (x + dx < 0 || x + dx >= w)) {
                    continue;
                }
                int wx = Math.floorMod(x + dx, w);
                for (int dy = -1; dy <= 1; dy++) {
                    if (!wrap && (y + dy < 0 || y + dy >= h)) {
                        continue;
                    }
                    int wy = Math.floorMod(y + dy, h);
                    int k = wy * w + wx;
                    if (dist[k] > mindist) {
                        int nd = mindist;
                        if (d == 0 && openCorner(type, dx, dy)) {
                            nd = mindist + 1;
                        }
                        if (nd < dist[k]) {
                            dist[k] = nd;
                            qx[qback] = wx;
                            qy[qback] = wy;
                            if (++qback == n) {
                                qback = 0;
                            }
                        }
                    }
                }
            }
        }
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) dist[i];
        }
        return out;
    }

    /** The neighbour across the open (hypotenuse) side of a diagonal block. */
    private static boolean openCorner(int type, int dx, int dy) {
        return switch (type) {
            case BlockType.REC_LD -> dx == 1 && dy == 1;
            case BlockType.REC_RD -> dx == -1 && dy == 1;
            case BlockType.REC_LU -> dx == 1 && dy == -1;
            case BlockType.REC_RU -> dx == -1 && dy == -1;
            default -> false;
        };
    }
}
