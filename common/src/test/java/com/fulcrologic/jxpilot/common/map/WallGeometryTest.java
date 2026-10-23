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
package com.fulcrologic.jxpilot.common.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WallGeometry")
class WallGeometryTest {
    static float[] poly(WorldMap m, int bx, int by) {
        float[] out = new float[2 * WallGeometry.MAX_VERTS];
        int n = WallGeometry.solidPolygon(m, bx, by, out);
        return Arrays.copyOf(out, 2 * n);
    }

    @Nested
    @DisplayName("solidPolygon")
    class SolidPolygon {
        @Test
        @DisplayName("returns the C solid shape of each block type in world pixels")
        void shapes() {
            WorldMap m = TestMaps.build("", "x#!aswq ", "rdfc*  _");
            assertAll(
                () -> assertArrayEquals(new float[] {0, 35, 35, 35, 35, 70, 0, 70}, poly(m, 0, 1),
                    "FILLED is the whole block, offset to block (0,1)"),
                () -> assertArrayEquals(new float[] {35, 35, 70, 35, 70, 70, 35, 70}, poly(m, 1, 1), "FUEL is the whole block"),
                () -> assertArrayEquals(new float[] {70, 35, 105, 35, 105, 70, 70, 70}, poly(m, 2, 1),
                    "TARGET is the whole block"),
                () -> assertArrayEquals(new float[] {140, 35, 140, 70, 105, 70}, poly(m, 3, 1),
                    "'a' REC_RU is the upper-right triangle"),
                () -> assertArrayEquals(new float[] {140, 35, 175, 70, 140, 70}, poly(m, 4, 1),
                    "'s' REC_LU is the upper-left triangle"),
                () -> assertArrayEquals(new float[] {175, 35, 210, 35, 175, 70}, poly(m, 5, 1),
                    "'w' REC_LD is the lower-left triangle"),
                () -> assertArrayEquals(new float[] {210, 35, 245, 35, 245, 70}, poly(m, 6, 1),
                    "'q' REC_RD is the lower-right triangle"),
                () -> assertArrayEquals(new float[0], poly(m, 7, 1), "space has no polygon"),
                () -> assertArrayEquals(new float[0], poly(m, 7, 0), "a base has no polygon"),
                () -> assertArrayEquals(new float[] {0, 0, 35, 0, 17.5f, 35f / 3}, poly(m, 0, 0),
                    "'r' sits on the floor with its apex a third up"),
                () -> assertArrayEquals(new float[] {70, 0, 70, 35, 35 + 70f / 3, 17.5f}, poly(m, 1, 0),
                    "'d' hangs on the right wall pointing left"),
                () -> assertArrayEquals(new float[] {70, 0, 70 + 35f / 3, 17.5f, 70, 35}, poly(m, 2, 0),
                    "'f' hangs on the left wall pointing right"),
                () -> assertArrayEquals(new float[] {105, 35, 122.5f, 70f / 3, 140, 35}, poly(m, 3, 0), 1e-4f,
                    "'c' hangs from the ceiling with its apex a third down (tolerance: float thirds)"),
                () -> assertEquals(22, poly(m, 4, 0).length, "a treasure is 11 vertices"),
                () -> assertArrayEquals(new float[] {140, 0, 175, 0, 175, 17.5f}, Arrays.copyOf(poly(m, 4, 0), 6), 1e-4f,
                    "a treasure starts with its flat bottom half"),
                () -> assertArrayEquals(new float[] {157.5f, 35}, Arrays.copyOfRange(poly(m, 4, 0), 12, 14), 1e-4f,
                    "a treasure's arc passes the top centre (tolerance: float trig)"),
                () -> assertArrayEquals(new float[] {140, 17.5f}, Arrays.copyOfRange(poly(m, 4, 0), 20, 22), 1e-4f,
                    "a treasure's arc ends at the left middle"));
        }
    }

    @Nested
    @DisplayName("isSolidPoint")
    class IsSolidPoint {
        @Test
        @DisplayName("diagonals are solid strictly inside their triangle")
        void diagonals() {
            WorldMap m = TestMaps.build("", "aswq");
            assertAll(
                () -> assertTrue(WallGeometry.isSolidPoint(m, 20, 20), "'a': ox+oy > 35 is solid"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 17.5, 17.5), "'a': on the diagonal is open"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 35 + 10, 11), "'s': ox < oy is solid"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 35 + 10, 10), "'s': on the diagonal is open"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 70 + 10, 24), "'w': ox+oy < 35 is solid"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 70 + 10, 25), "'w': on the diagonal is open"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 105 + 11, 10), "'q': ox > oy is solid"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 105 + 10, 10), "'q': on the diagonal is open"));
        }

        @Test
        @DisplayName("full blocks, cannons, treasures and space")
        void others() {
            WorldMap m = TestMaps.build("", "x#! rdfc*");
            assertAll(
                () -> assertTrue(WallGeometry.isSolidPoint(m, 0, 0), "FILLED is solid at its corner"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 50, 34), "FUEL is solid"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 80, 1), "TARGET is solid"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 120, 10), "SPACE is open"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 140 + 15, 10), "'r' left half: 3y <= 2x (30 <= 30)"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 140 + 15, 10.1), "'r' left half: just above the edge"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 140 + 20, 10), "'r' right half: 3y <= 2(35-x) (30 <= 30)"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 140 + 20, 10.1), "'r' right half: just above"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 175 + 32, 17.5), "'d' is solid near the right wall"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 175 + 5, 17.5), "'d' is open near the left"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 210 + 3, 17.5), "'f' is solid near the left wall"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 210 + 30, 17.5), "'f' is open near the right"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 245 + 17.5, 32), "'c' is solid near the ceiling"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 245 + 17.5, 5), "'c' is open near the floor"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 280 + 1, 17.5), "treasure: lower half (oy <= r) is solid"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 280 + 1, 25), "treasure: above half and outside the circle is open"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 280 + 17.5, 35), "treasure: top of the circle is solid"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 280 + 2, 33), "treasure: upper corner is open"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 280 + 27.5, 30), "treasure: (10,12.5) from the centre is inside r"),
                () -> assertFalse(WallGeometry.isSolidPoint(m, 280 + 32.5, 27), "treasure: (15,9.5) from the centre is outside r"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 280 + 7.5, 30), "treasure: (-10,12.5) from the centre is inside r"),
                () -> assertTrue(WallGeometry.isSolidPoint(m, 280 + 28, 31.5), "treasure: exactly on the circle (10.5,14) is solid"));
        }

        @Test
        @DisplayName("outside the world: solid without wrap, wrapped with wrap")
        void outside() {
            WorldMap flat = TestMaps.build("", "x  ", "   ");
            WorldMap wrap = TestMaps.build("edgeWrap: yes", "  x", "   ");
            WorldMap diag = TestMaps.build("edgeWrap: yes", "  a", "   ");
            assertAll(
                () -> assertTrue(WallGeometry.isSolidPoint(flat, -1, 10), "left of a flat world is solid"),
                () -> assertTrue(WallGeometry.isSolidPoint(flat, 106, 10), "right of a flat world is solid"),
                () -> assertTrue(WallGeometry.isSolidPoint(flat, 50, -1), "below a flat world is solid"),
                () -> assertTrue(WallGeometry.isSolidPoint(flat, 50, 71), "above a flat world is solid"),
                () -> assertTrue(WallGeometry.isSolidPoint(wrap, -1, 50), "left of x=0 wraps to the 'x' in the last column"),
                () -> assertFalse(WallGeometry.isSolidPoint(wrap, -1, 10), "the wrapped block below it is open"),
                () -> assertTrue(WallGeometry.isSolidPoint(wrap, 105 + 70 + 1, 36 + 70), "wraps more than one size"),
                () -> assertFalse(WallGeometry.isSolidPoint(wrap, 106, 50), "right of the world wraps to column 0"),
                () -> assertTrue(WallGeometry.isSolidPoint(diag, -1, 70 - 1), "wrapped offsets: (34,34) in 'a' is solid"),
                () -> assertFalse(WallGeometry.isSolidPoint(diag, -34, 35 + 1), "wrapped offsets: (1,1) in 'a' is open"),
                () -> assertTrue(WallGeometry.isSolidPoint(diag, 70 + 34, -1), "wrapped y offset: (34,34) in 'a' is solid"));
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {
        @Test
        @DisplayName("exposed sides and diagonals of wall blocks")
        void wallEdges() {
            WorldMap single = TestMaps.build("", "     ", "  x  ", "     ");
            WorldMap pair = TestMaps.build("", "      ", "  ax  ", "      ");
            WorldMap stack = TestMaps.build("", "   ", " x ", " # ", "   ");
            WorldMap diags = TestMaps.build("", "     ", " s w ", "     ");
            WorldMap qa = TestMaps.build("", "     ", " q   ", "     ");
            assertAll(
                () -> assertArrayEquals(new float[] {70, 35, 70, 70, 105, 35, 105, 70, 70, 35, 105, 35, 70, 70, 105, 70},
                    WallGeometry.buildEdges(single), "a lone block has left, right, bottom, top edges"),
                () -> assertArrayEquals(new float[] {70, 70, 105, 70, 105, 35, 70, 70,
                    140, 35, 140, 70, 105, 35, 140, 35, 105, 70, 140, 70}, WallGeometry.buildEdges(pair),
                    "'a' next to 'x': shared side hidden, 'a' keeps its top and diagonal"),
                () -> assertArrayEquals(new float[] {35, 35, 35, 70, 70, 35, 70, 70, 35, 35, 70, 35,
                    35, 70, 35, 105, 70, 70, 70, 105, 35, 105, 70, 105}, WallGeometry.buildEdges(stack),
                    "fuel below a wall hides the shared side"),
                () -> assertArrayEquals(new float[] {35, 35, 35, 70, 35, 70, 70, 70, 35, 35, 70, 70,
                    105, 35, 105, 70, 105, 35, 140, 35, 140, 35, 105, 70}, WallGeometry.buildEdges(diags),
                    "'s' draws left, top, diagonal up-right; 'w' draws left, bottom, diagonal down-right"),
                () -> assertArrayEquals(new float[] {70, 35, 70, 70, 35, 35, 70, 35, 35, 35, 70, 70},
                    WallGeometry.buildEdges(qa), "'q' draws right, bottom and its rising diagonal"));
        }

        @Test
        @DisplayName("world edges: drawn without wrap, covered by the opposite side with wrap")
        void worldEdges() {
            WorldMap flat = TestMaps.build("", "xx");
            WorldMap wrap = TestMaps.build("edgeWrap: yes", "xx");
            WorldMap tall = TestMaps.build("edgeWrap: yes", "x", "x");
            assertAll(
                () -> assertEquals(6 * 4, WallGeometry.buildEdges(flat).length, "six outer sides in a flat world"),
                () -> assertArrayEquals(new float[0], WallGeometry.buildEdges(wrap), "a fully covered wrapping row has none"),
                () -> assertArrayEquals(new float[0], WallGeometry.buildEdges(tall), "a fully covered wrapping column has none"));
        }

        @Test
        @DisplayName("decoration edges are separate from wall edges")
        void decor() {
            WorldMap m = TestMaps.build("", "    ", " xb ", "    ");
            WorldMap shapes = TestMaps.build("", "      ", " hgyt ", "      ");
            assertAll(
                () -> assertArrayEquals(new float[] {35, 35, 35, 70, 70, 35, 70, 70, 35, 35, 70, 35, 35, 70, 70, 70},
                    WallGeometry.buildEdges(m), "decor does not hide a wall side"),
                () -> assertArrayEquals(new float[] {70, 35, 70, 70, 105, 35, 105, 70, 70, 35, 105, 35, 70, 70, 105, 70},
                    WallGeometry.buildDecorEdges(m), "a decor block gets its own outline"),
                () -> assertEquals(4 * 10, WallGeometry.buildDecorEdges(shapes).length,
                    "h g y t: the g|y shared side is hidden; 4 diagonals and 6 open sides remain"));
        }
    }

    @Nested
    @DisplayName("buildWallTriangles")
    class Triangles {
        @Test
        @DisplayName("two triangles per full block, one per diagonal, nothing for others")
        void triangles() {
            WorldMap m = TestMaps.build("", "xa#");
            assertArrayEquals(new float[] {0, 0, 35, 0, 35, 35, 0, 0, 35, 35, 0, 35, 70, 0, 70, 35, 35, 35},
                WallGeometry.buildWallTriangles(m), "fans the square and the triangle; fuel is drawn elsewhere");
            assertArrayEquals(new float[] {70, 0, 105, 0, 105, 35, 70, 0, 105, 35, 70, 35},
                WallGeometry.buildWallTriangles(TestMaps.build("", "  x")),
                "a square away from the diagonal (x != y) fans vertices 0-1-2 and 0-2-3");
        }

        @Test
        @DisplayName("large maps grow the buffers")
        void bigMap() throws Exception {
            WorldMap m = WorldMap.load(java.nio.file.Path.of("..", "maps", "planetx.xp"));
            int full = 0;
            int diag = 0;
            for (byte b : m.blocks()) {
                if (b == BlockType.FILLED) {
                    full++;
                } else if (BlockType.isDiagonal(b)) {
                    diag++;
                }
            }
            int floats = WallGeometry.buildWallTriangles(m).length;
            int expected = 6 * (2 * full + diag);
            assertAll(
                () -> assertEquals(expected, floats, "6 floats per triangle: 2 per full block, 1 per diagonal"),
                () -> assertTrue(floats > 8192, "far more than the initial 8192-float buffer"));
        }
    }

    @Test
    @DisplayName("sides lists the full sides of each shape")
    void sides() {
        int all = WallGeometry.SIDE_UP | WallGeometry.SIDE_RIGHT | WallGeometry.SIDE_DOWN | WallGeometry.SIDE_LEFT;
        assertAll(
            () -> assertEquals(all, WallGeometry.sides(BlockType.FILLED), "FILLED has all sides"),
            () -> assertEquals(all, WallGeometry.sides(BlockType.FUEL), "FUEL has all sides"),
            () -> assertEquals(all, WallGeometry.sides(BlockType.DECOR_FILLED), "DECOR_FILLED has all sides"),
            () -> assertEquals(WallGeometry.SIDE_RIGHT | WallGeometry.SIDE_UP, WallGeometry.sides(BlockType.REC_RU), "RU"),
            () -> assertEquals(WallGeometry.SIDE_RIGHT | WallGeometry.SIDE_DOWN, WallGeometry.sides(BlockType.DECOR_RD), "RD"),
            () -> assertEquals(WallGeometry.SIDE_LEFT | WallGeometry.SIDE_UP, WallGeometry.sides(BlockType.DECOR_LU), "LU"),
            () -> assertEquals(WallGeometry.SIDE_LEFT | WallGeometry.SIDE_DOWN, WallGeometry.sides(BlockType.REC_LD), "LD"),
            () -> assertEquals(0, WallGeometry.sides(BlockType.CANNON), "a cannon has no full side"));
    }
}
