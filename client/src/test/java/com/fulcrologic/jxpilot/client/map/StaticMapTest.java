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
package com.fulcrologic.jxpilot.client.map;

import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Dir;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

class StaticMapTest {
    static final Palette P = Palette.xpilotDefault();
    static final int RED = P.color(Palette.RED);
    static final int WHITE = P.color(Palette.WHITE);
    static final int BLUE = P.color(Palette.BLUE);

    @Nested
    class Dots {
        @Test
        void hardEdgedWorldDotsEveryEighthBlockFromZero() {
            assertAll(
                () -> assertTrue(StaticMap.dotPosition(0, 0, false), "origin"),
                () -> assertTrue(StaticMap.dotPosition(8, 16, false), "multiples of 8"),
                () -> assertFalse(StaticMap.dotPosition(8, 3, false), "y not a multiple"),
                () -> assertFalse(StaticMap.dotPosition(1, 0, false), "x not a multiple"),
                () -> assertFalse(StaticMap.dotPosition(0, 5, false), "column 0 is not special without wrap"));
        }

        @Test
        void wrappingWorldDotsRowAndColumnZeroAndEveryEighthFromEight() {
            assertAll(
                () -> assertTrue(StaticMap.dotPosition(0, 5, true), "every block of column 0"),
                () -> assertTrue(StaticMap.dotPosition(5, 0, true), "every block of row 0"),
                () -> assertTrue(StaticMap.dotPosition(8, 8, true), "grid from 8"),
                () -> assertTrue(StaticMap.dotPosition(16, 8, true), "grid at 16"),
                () -> assertFalse(StaticMap.dotPosition(4, 8, true), "x off the grid"),
                () -> assertFalse(StaticMap.dotPosition(8, 4, true), "y off the grid"));
        }

        @Test
        void onlySpaceAndHiddenDecorationsBecomeDots() {
            assertAll(
                () -> assertTrue(StaticMap.dotType(BlockType.SPACE, true), "space"),
                () -> assertTrue(StaticMap.dotType(BlockType.DECOR_LU, false), "decoration when decorations are hidden"),
                () -> assertFalse(StaticMap.dotType(BlockType.DECOR_LU, true), "not a shown decoration"),
                () -> assertFalse(StaticMap.dotType(BlockType.FILLED, false), "not a wall"));
        }

        @Test
        void dotIsATwoPixelSquareAtTheBlockCentre() {
            VertexBatch b = Segs.batch();
            StaticMap.dot(b, 35, 70, BLUE);
            assertAll(
                () -> assertEquals(List.of("52,88/2"), Segs.points(b),
                    "a 2-pixel point covering [x+16, x+18) x [y+17, y+19) as C's Y(y+17)-1 does: centre (x+17, y+18)"),
                () -> assertEquals(List.of(BLUE), Segs.colors(b), "in the given colour"));
        }

        @Test
        void backgroundDotsStayInsideTheMap() {
            String[] rows = new String[9];
            for (int i = 0; i < 9; i++) {
                rows[i] = "........";
            }
            VertexBatch b = Segs.batch();
            StaticMap.backgroundDots(map(false, rows), true, b, BLUE);
            assertEquals(List.of("17,18/2", "17,298/2"),
                Segs.points(b), "8 wide: dots only in column 0 (none past the right edge at x=8)");
        }

        @Test
        void backgroundDotsSkipNonSpaceBlocks() {
            String[] rows = new String[9];
            for (int i = 0; i < 9; i++) {
                rows[i] = ".........";
            }
            rows[8] = "x........"; // bottom-left block (0,0) is a wall
            VertexBatch b = Segs.batch();
            StaticMap.backgroundDots(map(false, rows), true, b, BLUE);
            assertEquals(List.of("297,18/2", "17,298/2", "297,298/2"), Segs.points(b),
                "three dots: (8,0), (0,8), (8,8); not on the wall at (0,0)");
        }
    }

    @Nested
    class Gravity {
        List<String> grav(int type) {
            VertexBatch b = Segs.batch();
            StaticMap.gravity(b, type, 0, 0, RED);
            return Segs.lines(b);
        }

        @Test
        void directionalArrowsMatchC() {
            assertAll(
                () -> assertEquals(List.of("17,0-17,35", "17,35-7,25", "17,35-27,25"), grav(BlockType.UP_GRAV), "up arrow"),
                () -> assertEquals(List.of("17,0-17,35", "17,0-7,10", "17,0-27,10"), grav(BlockType.DOWN_GRAV), "down arrow"),
                () -> assertEquals(List.of("0,17-35,17", "35,17-25,27", "35,17-25,7"), grav(BlockType.RIGHT_GRAV), "right arrow"),
                () -> assertEquals(List.of("0,17-35,17", "0,17-10,27", "0,17-10,7"), grav(BlockType.LEFT_GRAV), "left arrow"));
        }

        @Test
        void plusAndMinusAreCirclesWithCrossOrBar() {
            List<String> plus = grav(BlockType.POS_GRAV);
            List<String> minus = grav(BlockType.NEG_GRAV);
            assertAll(
                () -> assertEquals(StaticMap.CIRCLE_SEGMENTS + 2, plus.size(), "circle chords plus two bars"),
                () -> assertTrue(plus.get(0).startsWith("34,17.5-"), "circle of radius 16.5 around the centre starts at angle 0"),
                () -> assertEquals("17,5-17,30", plus.get(StaticMap.CIRCLE_SEGMENTS), "vertical bar"),
                () -> assertEquals("5,17-30,17", plus.get(StaticMap.CIRCLE_SEGMENTS + 1), "horizontal bar"),
                () -> assertEquals(StaticMap.CIRCLE_SEGMENTS + 1, minus.size(), "circle plus one bar"),
                () -> assertEquals("5,17-30,17", minus.get(StaticMap.CIRCLE_SEGMENTS), "only the horizontal bar"));
        }

        @Test
        void rotatingGravityIsAThreeHundredDegreeArcWithAnArrowheadAtTheTop() {
            List<String> cw = grav(BlockType.CWISE_GRAV);
            List<String> acw = grav(BlockType.ACWISE_GRAV);
            int n = StaticMap.ARC_SEGMENTS;
            assertAll(
                () -> assertEquals(n + 2, cw.size(), "arc chords plus two arrowhead strokes"),
                () -> assertTrue(cw.get(0).startsWith("17.5,30-"), "clockwise arc starts at the top (90 degrees, r 12.5)"),
                () -> assertEquals("17,30-13,34", cw.get(n), "clockwise arrowhead opens to the left"),
                () -> assertEquals("17,30-13,26", cw.get(n + 1), "second stroke"),
                () -> assertEquals("17,30-21,34", acw.get(n), "anticlockwise arrowhead opens to the right"),
                () -> assertEquals("17,30-21,26", acw.get(n + 1), "second stroke"),
                () -> assertTrue(acw.get(n - 1).endsWith("-17.5,30"), "anticlockwise arc (150 + 300) ends at the top"));
        }

        @Test
        void otherBlocksDrawNothing() {
            assertEquals(List.of(), grav(BlockType.FILLED), "no marker for walls");
        }
    }

    @Test
    void baseLineIsOutsideTheBlockOppositeTheHeading() {
        VertexBatch b = Segs.batch();
        StaticMap.baseLine(b, Dir.UP, 0, 0, WHITE);
        StaticMap.baseLine(b, Dir.DOWN, 0, 0, WHITE);
        StaticMap.baseLine(b, Dir.LEFT, 0, 0, WHITE);
        StaticMap.baseLine(b, Dir.RIGHT, 0, 0, WHITE);
        assertEquals(List.of("0,-1-35,-1", "0,36-35,36", "36,35-36,0", "-1,35--1,0"), Segs.lines(b),
            "up: below; down: above; left: right side; right: left side");
    }

    static VertexBatch features(WorldMap m, boolean showDecor) {
        VertexBatch b = VertexBatch.growable(16);
        StaticMap.features(m, P, showDecor, b);
        return b;
    }

    @Nested
    class Builders {
        static final int WALL = P.color(Palette.WALL_COLOR);

        @Test
        void outlineMeshIsDotsThenFeatureAndWallSegmentsPlusBorder() {
            WorldMap m = map(false, "...", ".x.", "...");
            VertexBatch b = StaticMap.build(m, P, false, true);
            List<String> l = Segs.lines(b);
            assertAll(
                () -> assertEquals(List.of(VertexBatch.POINTS, VertexBatch.LINES), Segs.modes(b),
                    "two commands: dots, then every segment"),
                () -> assertEquals(List.of("17,18/2"), Segs.points(b), "the dot at block (0,0)"),
                () -> assertEquals(List.of("35,35-35,70", "35,35-70,35", "70,35-70,70", "35,70-70,70",
                    "0,0-0,105", "105,0-105,105", "0,0-105,0", "0,105-105,105"), l,
                    "the wall's four sides, then the four border lines"),
                () -> assertEquals(List.of(BLUE), Segs.colors(b), "blue dot, walls in wallColor (the same palette entry)"),
                () -> assertEquals(WALL, b.segColor(0), "walls in wallColor"),
                () -> assertEquals(0, b.vertexCount(), "no triangles"));
        }

        @Test
        void filledMeshIsFillTrianglesThenDotsAndSegmentsWithoutBlockOutlines() {
            WorldMap m = map(true, "...", ".xs", "...");
            VertexBatch b = StaticMap.build(m, P, true, true);
            assertAll(
                () -> assertEquals(List.of(VertexBatch.TRIANGLES, VertexBatch.POINTS), Segs.modes(b),
                    "fills first, then dots; a wrapping map with no features has no segments"),
                () -> assertEquals(List.of("35,35 70,35 70,70", "35,35 70,70 35,70", "70,35 105,70 70,70"),
                    Segs.triangles(b), "two triangles for x, one for s"),
                () -> assertEquals(List.of(), Segs.lines(b), "no block outlines and no border when wrapping"),
                () -> assertEquals(WALL, b.color(0), "fill in wallColor"),
                () -> assertEquals(BLUE, b.pointColor(0), "blue dots"),
                () -> assertEquals(4, StaticMap.build(map(false, "x"), P, true, true).segmentCount(),
                    "a hard-edged map gets its four border lines"));
        }

        @Test
        void filledMeshFillsEveryFuelBlockLikeAWall() {
            WorldMap m = map(false, "#x.", "..#");
            VertexBatch b = VertexBatch.growable(16);
            StaticMap.wallFill(m, b, WALL);
            List<String> t = Segs.triangles(b);
            assertAll(
                () -> assertEquals(List.of("35,35 70,35 70,70", "35,35 70,70 35,70"), t.subList(0, 2),
                    "the wall block (1,1) first"),
                () -> assertEquals(List.of("0,35 35,35 35,70", "0,35 35,70 0,70",
                    "70,0 105,0 105,35", "70,0 105,35 70,35"), t.subList(2, 6),
                    "then every fuel block in C's x-major order: (0,1) at the hard edge, (2,0) in the open"),
                () -> assertEquals(6, t.size(), "nothing else"),
                () -> assertEquals(List.of(WALL), Segs.colors(b), "all in wallColor"));
        }

        @Test
        void featuresDrawDotsThenDecorGravityAndBases() {
            WorldMap m = map(false, "b+", "._");
            VertexBatch b = features(m, true);
            List<String> lines = Segs.lines(b);
            assertAll(
                () -> assertEquals(VertexBatch.POINTS, b.commandMode(0), "dots come first"),
                () -> assertEquals(List.of("17,18/2"), Segs.points(b), "one dot, at block (0,0)"),
                () -> assertEquals(List.of(BLUE, P.color(Palette.DECOR_COLOR), RED, WHITE), Segs.colors(b),
                    "dot blue, decor, gravity red, base white in that order"),
                () -> assertEquals(4 + StaticMap.CIRCLE_SEGMENTS + 2 + 1, lines.size(),
                    "decor square, plus marker, one base line"),
                () -> assertEquals("35,-1-70,-1", lines.get(lines.size() - 1), "base at (1,0) faces up by default"));
        }

        @Test
        void featuresAreAtTheirBlocks() {
            WorldMap m = map(false, "...", "..i", "..x", "._.", "...");
            List<String> lines = Segs.lines(features(m, true));
            assertAll(
                () -> assertEquals(Dir.DOWN, m.bases().get(0).dir(), "precondition: the loader faces this base down"),
                () -> assertEquals("87,105-87,140", lines.get(0), "up gravity at block (2,3)"),
                () -> assertEquals("35,71-70,71", lines.get(lines.size() - 1), "down-facing base at block (1,1): line above"));
        }

        @Test
        void hiddenDecorationsAreNotOutlined() {
            WorldMap m = map(false, "b.", "..");
            assertAll(
                () -> assertEquals(4, Segs.lines(features(m, true)).size(), "shown: decor outline"),
                () -> assertEquals(0, Segs.lines(features(m, false)).size(), "hidden: no outline"));
        }
    }
}
