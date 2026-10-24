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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Port of cell.c: Init_cell_dist, Cell_add_object, Cell_get_objects. */
class GridTest {
    private static final int B = Const.BLOCK_SZ;

    /** Allocate an object at the centre of block (bx, by). */
    private static int at(Objects o, int bx, int by) {
        int h = o.alloc();
        o.initPixels(h, bx * B + 17, by * B + 17);
        return h;
    }

    private static int[] result(Grid g) {
        return Arrays.copyOf(g.result, g.resultCount);
    }

    @Nested
    @DisplayName("query order")
    class QueryOrder {
        @Test
        @DisplayName("nearest blocks first; equal distances by Chebyshev distance, then scan order (y, then x)")
        void nearestFirst() {
            Grid g = new Grid(20, 20, false, 16);
            Objects o = new Objects(16);
            int far5 = at(o, 15, 10);      // offset (5, 0): dist 5, Chebyshev 5
            int diag = at(o, 11, 11);      // offset (1, 1): dist 1.41
            int knight = at(o, 13, 14);    // offset (3, 4): dist 5, Chebyshev 4
            int right = at(o, 11, 10);     // offset (1, 0)
            int left = at(o, 9, 10);       // offset (-1, 0)
            int below = at(o, 10, 9);      // offset (0, -1)
            int centre = at(o, 10, 10);
            int two = at(o, 8, 10);        // offset (-2, 0)
            g.rebuild(o);
            g.query(10, 10, 4, 100);
            assertArrayEquals(new int[] {centre, below, left, right, diag, two, knight, far5}, result(g),
                "centre, then (0,-1), (-1,0), (1,0) (scan order), then the diagonal, then distance 2, then (3,4) before"
                    + " (5,0) (same distance 5, smaller Chebyshev distance first: Compare_cell_dist)");
        }

        @Test
        @DisplayName("objects in one block are listed latest join first (created or moved), as C prepends on Cell_add_object")
        void joinOrder() {
            Grid g = new Grid(5, 5, false, 8);
            Objects o = new Objects(8);
            int a = at(o, 2, 2);
            int b = at(o, 2, 2);
            int c = at(o, 2, 2);
            g.rebuild(o);
            g.query(2, 2, 0, 100);
            int[] created = result(g);
            o.free(a);                     // c moves into a's slot: active = [c, b]; C's cell list is not reordered
            g.rebuild(o);
            g.query(2, 2, 0, 100);
            int[] afterFree = result(g);
            o.touchCell(b);                // b moved (e.g. an asteroid moved after the other objects)
            g.rebuild(o);
            g.query(2, 2, 0, 100);
            int[] afterMove = result(g);
            int d = at(o, 2, 2);           // created after the moves
            g.rebuild(o);
            g.query(2, 2, 0, 100);
            assertAll(
                () -> assertArrayEquals(new int[] {c, b, a}, created, "the last created comes first"),
                () -> assertArrayEquals(new int[] {c, b}, afterFree, "a swap-remove keeps the others' order"),
                () -> assertArrayEquals(new int[] {b, c}, afterMove, "the last moved comes first"),
                () -> assertArrayEquals(new int[] {d, b, c}, result(g), "an object created after it is first"));
        }
    }

    @Nested
    @DisplayName("rebuild")
    class Rebuild {
        @Test
        @DisplayName("a fresh grid is empty; each rebuild starts from empty cells")
        void rebuildClears() {
            Grid g = new Grid(4, 4, false, 4);
            g.query(1, 1, 3, 100);
            int fresh = g.resultCount;
            Objects o = new Objects(4);
            int a = at(o, 1, 1);
            g.rebuild(o);
            o.free(a);
            g.rebuild(o);
            g.query(1, 1, 0, 100);
            assertAll(
                () -> assertEquals(0, fresh, "nothing before the first rebuild"),
                () -> assertEquals(0, g.resultCount, "a freed object is gone after the next rebuild"));
        }

        @Test
        @DisplayName("an object at block x = width (just off the right edge) is in no cell")
        void rightEdge() {
            Grid g = new Grid(4, 4, false, 4);
            Objects o = new Objects(4);
            int h = o.alloc();
            o.setClicks(h, 4 * B * 64, B * 64 + 64);
            g.rebuild(o);
            g.query(0, 2, 3, 100);
            int[] r = result(g);
            assertArrayEquals(new int[] {}, r, "block (4,1) of a 4-wide map is outside: never listed");
        }
    }

    @Nested
    @DisplayName("query limits")
    class Limits {
        @Test
        @DisplayName("range cuts off at range*sqrt(2) block distance; max caps the count")
        void rangeAndMax() {
            Grid g = new Grid(10, 10, false, 8);
            Objects o = new Objects(8);
            int centre = at(o, 5, 5);
            int diag = at(o, 6, 6);
            int two = at(o, 7, 5);
            g.rebuild(o);
            g.query(5, 5, 1, 100);
            int[] range1 = result(g);
            g.query(5, 5, 0, 100);
            int[] range0 = result(g);
            g.query(5, 5, 2, 100);
            int[] range2 = result(g);
            g.query(5, 5, 2, 2);
            int[] max2 = result(g);
            g.query(5, 5, 2, 0);
            int[] max0 = result(g);
            assertAll(
                () -> assertArrayEquals(new int[] {centre, diag}, range1, "range 1 reaches the diagonal (1.41) not distance 2"),
                () -> assertArrayEquals(new int[] {centre}, range0, "range 0 is the block itself"),
                () -> assertArrayEquals(new int[] {centre, diag, two}, range2, "range 2 reaches distance 2"),
                () -> assertArrayEquals(new int[] {centre, diag}, max2, "max 2 stops after two objects"),
                () -> assertArrayEquals(new int[] {}, max0, "max 0 returns nothing"));
        }

        @Test
        @DisplayName("max also stops in the middle of a crowded block")
        void maxInsideBlock() {
            Grid g = new Grid(3, 3, false, 8);
            Objects o = new Objects(8);
            at(o, 1, 1);
            int b = at(o, 1, 1);
            int c = at(o, 1, 1);
            g.rebuild(o);
            g.query(1, 1, 1, 2);
            assertArrayEquals(new int[] {c, b}, result(g), "only the first two objects of the block are returned");
        }

        @Test
        @DisplayName("offsets reach MAX_CELL_DIST = 28 blocks and no further")
        void maxCellDist() {
            Grid g = new Grid(40, 1, false, 8);
            Objects o = new Objects(8);
            int at28 = at(o, 28, 0);
            at(o, 29, 0);
            g.rebuild(o);
            g.query(0, 0, 25, 100);
            assertArrayEquals(new int[] {at28}, result(g),
                "block 28 is found, block 29 is beyond the C cell_dist table even though 25*sqrt(2) > 29");
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {
        @Test
        @DisplayName("blocks across the world edge are found only when the map wraps")
        void wrapVsNoWrap() {
            Grid wrap = new Grid(6, 6, true, 8);
            Grid flat = new Grid(6, 6, false, 8);
            Objects o = new Objects(8);
            int left = at(o, 5, 0);
            int up = at(o, 0, 5);
            int corner = at(o, 5, 5);
            wrap.rebuild(o);
            flat.rebuild(o);
            wrap.query(0, 0, 1, 100);
            int[] w = result(wrap);
            flat.query(0, 0, 1, 100);
            int[] f = result(flat);
            assertAll(
                () -> assertArrayEquals(new int[] {up, left, corner}, w,
                    "with wrap (0,-1)->(0,5), (-1,0)->(5,0) and (-1,-1)->(5,5) are neighbours"),
                () -> assertArrayEquals(new int[] {}, f, "without wrap nothing across the edge is found"));
        }

        @Test
        @DisplayName("a small wrapping map has asymmetric reach so no block is visited twice")
        void smallWrapVisitsOnce() {
            Grid g = new Grid(4, 1, true, 4);
            Objects o = new Objects(4);
            int opposite = at(o, 2, 0);
            g.rebuild(o);
            g.query(0, 0, 3, 100);
            assertArrayEquals(new int[] {opposite}, result(g),
                "block 2 is reached by +2 only (left reach is (4-1)/2 = 1), so it is listed once");
        }

        @Test
        @DisplayName("blocks come from pixel / BLOCK_SZ truncated; objects off the map are in no cell")
        void outsideObjects() {
            Grid g = new Grid(4, 4, false, 8);
            Objects o = new Objects(8);
            int nearEdge = o.alloc();
            o.setClicks(nearEdge, -10 * 64, 5 * 64);   // pixel x -10 truncates to block 0 (C pos.bx)
            int offLeft = o.alloc();
            o.setClicks(offLeft, -35 * 64, 5 * 64);    // block -1
            int offTop = o.alloc();
            o.setClicks(offTop, 5 * 64, 4 * B * 64);   // block y 4 = height
            int inside = at(o, 3, 3);
            g.rebuild(o);
            g.query(0, 0, 0, 100);
            int[] b00 = result(g);
            g.query(3, 3, 4, 100);
            int[] all = result(g);
            assertAll(
                () -> assertArrayEquals(new int[] {nearEdge}, b00, "pixel -10 is in block 0 by truncation"),
                () -> assertArrayEquals(new int[] {inside, nearEdge}, all,
                    "objects at block -1 or block y = height are never returned"));
        }
    }

    @Nested
    @DisplayName("cell_dist table")
    class Table {
        @Test
        @DisplayName("the offset table is sorted by distance and covers the reach rectangle")
        void table() {
            Grid g = new Grid(5, 3, false, 1);
            boolean sorted = true;
            for (int i = 1; i < g.offDist.length; i++) {
                sorted &= g.offDist[i - 1] <= g.offDist[i];
            }
            boolean ok = sorted;
            assertAll(
                () -> assertEquals(9 * 5, g.offX.length, "without wrap the reach is w-1 = 4 left/right, h-1 = 2 up/down"),
                () -> assertEquals(0, g.offX[0] | g.offY[0], "the first offset is the block itself"),
                () -> assertEquals(true, ok, "distances are non-decreasing"),
                () -> assertEquals((float) Math.hypot(4, 2), g.offDist[g.offDist.length - 1], "the last offset is a far corner"),
                () -> assertEquals(5 * 3, new Grid(5, 3, true, 1).offX.length,
                    "with wrap reach is w/2=2 right, (w-1)/2=2 left, h/2=1 up, (h-1)/2=1 down"));
        }
    }
}
