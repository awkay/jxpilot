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
package com.fulcrologic.jxpilot.common.sim;

import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.PriorityQueue;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** C {@code Walldist_init} (walls.c:165-264). Rows are given top first; block y = height-1-row. */
class WallDistTest {
    private static WorldMap map(String extra, String... rows) {
        return WorldMap.fromOptions(KernelFx.options(extra, rows));
    }

    /** Distance of block (bx, by) where by counts from the bottom. */
    private static int d(byte[] wd, WorldMap m, int bx, int by) {
        return wd[by * m.width() + bx] & 0xFF;
    }

    @Nested
    @DisplayName("without wrap")
    class NoWrap {
        @Test
        @DisplayName("the outer ring starts at 2 and each ring inward adds 2")
        void rings() {
            WorldMap m = map("", "       ", "       ", "       ", "       ", "       ", "       ", "       ");
            byte[] wd = WallDist.compute(m);
            assertAll(
                () -> assertEquals(2, d(wd, m, 0, 0), "a corner block is on the border: 2"),
                () -> assertEquals(2, d(wd, m, 3, 6), "a top border block: 2"),
                () -> assertEquals(2, d(wd, m, 6, 3), "a right border block: 2"),
                () -> assertEquals(4, d(wd, m, 1, 3), "one ring in: 4"),
                () -> assertEquals(6, d(wd, m, 2, 4), "two rings in: 6"),
                () -> assertEquals(8, d(wd, m, 3, 3), "the centre, three rings in: 8"));
        }

        @Test
        @DisplayName("a wall next to the border keeps 0 and its neighbours get 2")
        void wallAtBorder() {
            WorldMap m = map("", "     ", "     ", "x    ", "     ", "     ");
            byte[] wd = WallDist.compute(m);
            assertAll(
                () -> assertEquals(0, d(wd, m, 0, 2), "the wall block on the border stays 0, not 2"),
                () -> assertEquals(2, d(wd, m, 1, 2), "its neighbour is 2"),
                () -> assertEquals(4, d(wd, m, 2, 2), "the centre is 4 (one step from ring 1 or from the wall's ring)"));
        }

        @Test
        @DisplayName("a one-row map is all border")
        void oneRow() {
            WorldMap m = map("", "    ");
            assertArrayEquals(new byte[] {2, 2, 2, 2}, WallDist.compute(m), "every block of a 1-high map is 2");
        }
    }

    @Nested
    @DisplayName("with wrap")
    class Wrap {
        @Test
        @DisplayName("distances propagate across the world edges")
        void propagatesAcrossEdges() {
            String e = "         ";
            WorldMap m = map("edgeWrap: yes", e, e, e, e, "x        ", e, e, e, e);
            byte[] wd = WallDist.compute(m);
            assertAll(
                () -> assertEquals(0, d(wd, m, 0, 4), "the wall is 0"),
                () -> assertEquals(2, d(wd, m, 8, 4), "the block left of the wall across the edge is 2"),
                () -> assertEquals(6, d(wd, m, 6, 4), "x=6 is 3 steps away through the edge: 6"),
                () -> assertEquals(8, d(wd, m, 5, 4), "x=5 is 4 steps through the edge (5 directly): 8"),
                () -> assertEquals(8, d(wd, m, 4, 4), "x=4 is 4 steps directly: 8"),
                () -> assertEquals(2, d(wd, m, 8, 3), "the diagonal neighbour across the edge is 2"),
                () -> assertEquals(2, d(wd, m, 1, 4), "the direct neighbour is 2"));
        }

        @Test
        @DisplayName("a map without walls stays at maxdist = min(255, 2*min(w,h))")
        void noWalls() {
            WorldMap small = map("edgeWrap: yes", "    ", "    ", "    ", "    ", "    ", "    ", "    ", "    ", "    ");
            StringBuilder row = new StringBuilder();
            row.append(" ".repeat(128));
            String[] rows = new String[128];
            java.util.Arrays.fill(rows, row.toString());
            WorldMap big = map("edgeWrap: yes", rows);
            byte[] s = WallDist.compute(small);
            byte[] b = WallDist.compute(big);
            assertAll(
                () -> assertEquals(8, d(s, small, 2, 5), "4x9: maxdist 2*4 = 8"),
                () -> assertEquals(255, d(b, big, 64, 64), "128x128: 2*128 = 256 is capped at 255"));
        }
    }

    @Nested
    @DisplayName("diagonal walls")
    class Diagonals {
        private int[] around(char c) {
            WorldMap m = map("edgeWrap: yes", "     ", "     ", "  " + c + "  ", "     ", "     ");
            byte[] wd = WallDist.compute(m);
            return new int[] {d(wd, m, 3, 3), d(wd, m, 1, 3), d(wd, m, 3, 1), d(wd, m, 1, 1), d(wd, m, 2, 2)};
        }

        @Test
        @DisplayName("the neighbour across the open corner gets 3; the other diagonal neighbours 2")
        void openCorner() {
            assertAll(
                () -> assertArrayEquals(new int[] {3, 2, 2, 2, 0}, around('w'),
                    "REC_LD (w, solid lower-left): up-right (+1,+1) is 3"),
                () -> assertArrayEquals(new int[] {2, 3, 2, 2, 0}, around('q'),
                    "REC_RD (q, solid lower-right): up-left (-1,+1) is 3"),
                () -> assertArrayEquals(new int[] {2, 2, 3, 2, 0}, around('s'),
                    "REC_LU (s, solid upper-left): down-right (+1,-1) is 3"),
                () -> assertArrayEquals(new int[] {2, 2, 2, 3, 0}, around('a'),
                    "REC_RU (a, solid upper-right): down-left (-1,-1) is 3"),
                () -> assertArrayEquals(new int[] {2, 2, 2, 2, 0}, around('x'),
                    "a filled block gives 2 to every neighbour"));
        }

        @Test
        @DisplayName("the +1 only applies to the step out of the wall block; it carries on from there")
        void carriesOn() {
            String e = "       ";
            WorldMap m = map("edgeWrap: yes", e, e, e, "   w   ", e, e, e);
            byte[] wd = WallDist.compute(m);
            assertAll(
                () -> assertEquals(3, d(wd, m, 4, 4), "the open-corner neighbour is 3"),
                () -> assertEquals(5, d(wd, m, 5, 5), "the next diagonal step from it adds 2: 5"),
                () -> assertEquals(4, d(wd, m, 4, 5), "a block reachable from a 2-block is 4"),
                () -> assertEquals(7, d(wd, m, 6, 6), "two diagonal steps from 3: 7"),
                () -> assertEquals(2, d(wd, m, 2, 2), "the closed corner is 2"));
        }
    }

    @Nested
    @DisplayName("block kinds")
    class Kinds {
        @Test
        @DisplayName("walls, fuel, cannons, treasures, targets and IN/NORMAL wormholes are 0; OUT wormholes and"
            + " open blocks are not")
        void kinds() {
            WorldMap m = map("edgeWrap: yes",
                "x # r * ! ( @ )     ",
                "                    ",
                "                    ");
            WorldMap open = map("edgeWrap: yes",
                "  _ + z b ",
                "          ",
                "          ");
            byte[] wd = WallDist.compute(m);
            byte[] od = WallDist.compute(open);
            int y = 2;
            assertAll(
                () -> assertEquals(BlockType.WORMHOLE, m.block(14, y), "(fixture: ')' is a wormhole)"),
                () -> assertEquals(0, d(wd, m, 0, y), "FILLED is a wall"),
                () -> assertEquals(0, d(wd, m, 2, y), "FUEL is a wall"),
                () -> assertEquals(0, d(wd, m, 4, y), "CANNON is a wall"),
                () -> assertEquals(0, d(wd, m, 6, y), "TREASURE is a wall"),
                () -> assertEquals(0, d(wd, m, 8, y), "TARGET is a wall"),
                () -> assertEquals(0, d(wd, m, 10, y), "an IN wormhole is a wall"),
                () -> assertEquals(0, d(wd, m, 12, y), "a NORMAL wormhole is a wall"),
                () -> assertEquals(2, d(wd, m, 13, y), "the space between is 2"),
                () -> assertEquals(4, d(wd, m, 14, y), "an OUT wormhole is not a wall: two steps from '@' is 4"),
                () -> assertEquals(6, d(wd, m, 15, y), "and the block after it is 6"),
                () -> assertEquals(6, d(od, open, 2, y), "a BASE is not a wall (maxdist 2*3 = 6 stays)"),
                () -> assertEquals(6, d(od, open, 4, y), "a gravity block is not a wall"),
                () -> assertEquals(6, d(od, open, 6, y), "a friction block is not a wall"),
                () -> assertEquals(6, d(od, open, 8, y), "a decoration is not a wall"),
                () -> assertEquals(true, WallDist.wallLike(BlockType.CHECK), "a checkpoint is wall-like (WALLDIST_MASK)"),
                () -> assertEquals(false, WallDist.wallLike(BlockType.SPACE), "space is not wall-like"));
        }
    }

    @Nested
    @DisplayName("against an independent shortest-path oracle")
    class Oracle {
        @Test
        @DisplayName("random maps match a Dijkstra over the same rules, with and without wrap")
        void randomMaps() {
            SplittableRandom r = new SplittableRandom(7);
            char[] kinds = {' ', ' ', ' ', ' ', ' ', ' ', 'x', 'w', 'q', 's', 'a'};
            for (int trial = 0; trial < 40; trial++) {
                int w = 3 + r.nextInt(10);
                int h = 3 + r.nextInt(10);
                String[] rows = new String[h];
                for (int y = 0; y < h; y++) {
                    StringBuilder sb = new StringBuilder();
                    for (int x = 0; x < w; x++) {
                        sb.append(r.nextInt(3) == 0 ? kinds[r.nextInt(kinds.length)] : ' ');
                    }
                    rows[y] = sb.toString();
                }
                boolean wrap = (trial & 1) == 0;
                WorldMap m = map(wrap ? "edgeWrap: yes" : "", rows);
                int[] expect = dijkstra(m, wrap);
                byte[] got = WallDist.compute(m);
                int[] g = new int[got.length];
                for (int i = 0; i < g.length; i++) {
                    g[i] = got[i] & 0xFF;
                }
                assertArrayEquals(expect, g, "trial " + trial + " (wrap " + wrap + ") matches the oracle");
            }
        }

        private int[] dijkstra(WorldMap m, boolean wrap) {
            int w = m.width();
            int h = m.height();
            int max = Math.min(255, 2 * Math.min(w, h));
            int[] dist = new int[w * h];
            java.util.Arrays.fill(dist, max);
            PriorityQueue<int[]> q = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int t = m.block(x, y);
                    if (t == BlockType.FILLED || BlockType.isDiagonal(t)) {
                        dist[y * w + x] = 0;
                        q.add(new int[] {0, x, y});
                    } else if (!wrap && (x == 0 || y == 0 || x == w - 1 || y == h - 1) && max > 1) {
                        dist[y * w + x] = 2;
                        q.add(new int[] {2, x, y});
                    }
                }
            }
            while (!q.isEmpty()) {
                int[] c = q.poll();
                if (c[0] != dist[c[2] * w + c[1]]) {
                    continue;
                }
                int t = m.block(c[1], c[2]);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int nx = c[1] + dx;
                        int ny = c[2] + dy;
                        if (!wrap && (nx < 0 || ny < 0 || nx >= w || ny >= h)) {
                            continue;
                        }
                        nx = Math.floorMod(nx, w);
                        ny = Math.floorMod(ny, h);
                        int step = 2;
                        if (c[0] == 0 && ((t == BlockType.REC_LD && dx == 1 && dy == 1)
                            || (t == BlockType.REC_RD && dx == -1 && dy == 1)
                            || (t == BlockType.REC_LU && dx == 1 && dy == -1)
                            || (t == BlockType.REC_RU && dx == -1 && dy == -1))) {
                            step = 3;
                        }
                        int nd = c[0] + step;
                        if (nd < dist[ny * w + nx]) {
                            dist[ny * w + nx] = nd;
                            q.add(new int[] {nd, nx, ny});
                        }
                    }
                }
            }
            return dist;
        }
    }
}
