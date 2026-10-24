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

import com.fulcrologic.jxpilot.client.Fixtures;
import com.fulcrologic.jxpilot.client.paint.PaintStyle;
import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.Fixtures.map;
import static com.fulcrologic.jxpilot.client.Fixtures.mapWith;
import static org.junit.jupiter.api.Assertions.*;

/** The live look options in the static map mesh, the radar picture and the dead-cannon dots. */
class MapLookTest {
    static final Palette P = Palette.xpilotDefault();
    static final int WHITE = Palette.pack(255, 255, 255, 255);

    static VertexBatch build(WorldMap m, PaintStyle st, boolean tileWalls, boolean tileDecor) {
        return StaticMap.build(m, P, st, tileWalls, tileDecor);
    }

    @Nested
    class Walls {
        @Test
        void outlineWorldDropsSharedSides() {
            WorldMap m = map(false, "xx");
            PaintStyle st = new PaintStyle();
            int grid = build(m, st, false, false).segmentCount();
            st.outlineWorld = true;
            List<String> outline = Segs.lines(build(m, st, false, false));
            assertAll(
                () -> assertEquals(7 + 4, grid, "grid: 4 + 3 sides (the left of block 1 is shared) plus the border"),
                () -> assertEquals(List.of("0,0-0,35", "0,0-35,0", "0,35-35,35", "35,0-70,0", "70,0-70,35", "35,35-70,35",
                    "0,0-0,35", "70,0-70,35", "0,0-70,0", "0,35-70,35"), outline,
                    "outline: no shared side at x 35, then the border"));
        }

        @Test
        void filledAndTexturedWallsFillAndDropOutlines() {
            WorldMap m = map(false, "x");
            PaintStyle st = new PaintStyle();
            st.filledWorld = true;
            st.wallColor = 5;
            VertexBatch filled = build(m, st, false, false);
            st.filledWorld = false;
            st.texturedWalls = true;
            VertexBatch untiled = build(m, st, false, false);
            VertexBatch tiled = build(m, st, true, false);
            VertexBatch tiles = StaticMap.wallTriangles(m);
            assertAll(
                () -> assertEquals(List.of("0,0 35,0 35,35", "0,0 35,35 0,35"), Segs.triangles(filled), "filled: the block"),
                () -> assertEquals(P.color(5), filled.color(0), "in wallColor"),
                () -> assertEquals(4, filled.segmentCount(), "only the border, no block outline"),
                () -> assertEquals(P.color(5), filled.segColor(0), "the border in wallColor too"),
                () -> assertEquals(2 * 3, untiled.vertexCount(), "textured without a texture: solid fill"),
                () -> assertEquals(0, tiled.vertexCount(), "tiled: no solid fill in the mesh"),
                () -> assertEquals(4, tiled.segmentCount(), "and no outline"),
                () -> assertEquals(Segs.triangles(filled), Segs.triangles(tiles), "the tile mesh has the same triangles"),
                () -> assertEquals(WHITE, tiles.color(0), "in white, for the texture to show as is"));
        }
    }

    @Nested
    class Decor {
        @Test
        void filledDecorationsAreTheirBlockShapes() {
            WorldMap m = map(false, "bh", "gy", "t.");
            VertexBatch b = StaticMap.decorTriangles(m);
            assertAll(
                () -> assertEquals(List.of("0,0 35,35 35,0", "0,70 35,70 35,35", "35,35 35,70 70,35",
                    "0,70 35,70 35,105", "0,70 35,105 0,105", "35,70 35,105 70,105"), Segs.triangles(b),
                    "rows bottom-up: RD (t), RU (g), LD (y), the full block (b), LU (h)"),
                () -> assertEquals(WHITE, b.color(0), "white for the texture"));
        }

        @Test
        void decorLooksFollowTheirOptions() {
            WorldMap m = map(false, "bb");
            PaintStyle st = new PaintStyle();
            st.decorColor = 7;
            VertexBatch grid = build(m, st, false, false);
            st.outlineDecor = true;
            VertexBatch outline = build(m, st, false, false);
            st.outlineDecor = false;
            st.filledDecor = true;
            VertexBatch filled = build(m, st, false, false);
            st.filledDecor = false;
            st.texturedDecor = true;
            VertexBatch tiled = build(m, st, false, true);
            VertexBatch untiled = build(m, st, false, false);
            st.showDecor = false;
            st.filledDecor = true;
            VertexBatch hidden = build(m, st, false, false);
            assertAll(
                () -> assertEquals(7 + 4, grid.segmentCount(), "grid decor outline plus the border"),
                () -> assertEquals(P.color(7), grid.segColor(0), "in decorColor"),
                () -> assertEquals(6 + 4, outline.segmentCount(), "outlineDecor drops the shared side"),
                () -> assertEquals(4 * 3, filled.vertexCount(), "filledDecor: both blocks filled"),
                () -> assertEquals(P.color(7), filled.color(0), "in decorColor"),
                () -> assertEquals(4, filled.segmentCount(), "no decor outline when filled"),
                () -> assertEquals(0, tiled.vertexCount(), "texturedDecor drawn tiled: not in the mesh"),
                () -> assertEquals(4, tiled.segmentCount(), "and not outlined"),
                () -> assertEquals(4 * 3, untiled.vertexCount(), "texturedDecor without a texture: solid fill"),
                () -> assertEquals(0, hidden.vertexCount(), "hidden decorations are not filled"),
                () -> assertEquals(List.of("17,18/2"), Segs.points(hidden), "but become dot positions"));
        }
    }

    @Nested
    class Dots {
        @Test
        void dotPositionsFollowTheDistance() {
            assertAll(
                () -> assertTrue(StaticMap.dotPosition(0, 5, true, 8), "wrap: column 0 always"),
                () -> assertFalse(StaticMap.dotPosition(3, 3, true, 0), "wrap, distance 0: only row and column 0"),
                () -> assertTrue(StaticMap.dotPosition(2, 4, true, 2), "wrap: multiples from the distance"),
                () -> assertFalse(StaticMap.dotPosition(1, 4, true, 2), "not a multiple"),
                () -> assertFalse(StaticMap.dotPosition(8, 1, true, 8), "wrap: y below the distance is no dot"),
                () -> assertFalse(StaticMap.dotPosition(0, 0, false, 0), "hard edge, distance 0: none"),
                () -> assertTrue(StaticMap.dotPosition(4, 6, false, 2), "hard edge: multiples from 0"),
                () -> assertTrue(StaticMap.dotPosition(16, 8, false), "the default distance is 8"));
        }

        @Test
        void dotSizeAndDistanceComeFromTheStyle() {
            WorldMap m = map(false, "...");
            PaintStyle st = new PaintStyle();
            st.pointDist = 2;
            st.pointSize = 4;
            VertexBatch four = build(m, st, false, false);
            st.pointSize = 3;
            VertexBatch three = build(m, st, false, false);
            st.pointSize = 0;
            VertexBatch none = build(m, st, false, false);
            assertAll(
                () -> assertEquals(List.of("17,18/4", "87,18/4"), Segs.points(four), "every second block, 4 px"),
                () -> assertEquals(List.of("17.5,17.5/3", "87.5,17.5/3"), Segs.points(three),
                    "odd size: corner at 17 - 1, centre 1.5 further"),
                () -> assertEquals(0, none.pointCount(), "size 0: no dots"));
        }

        @Test
        void deadCannonDotsUseTheLiveDistanceAndSize() {
            WorldMap m = mapWith(false, "", "........r", ".........", ".........", ".........", "....1....",
                ".........", ".........", ".........", "rr.......");
            MapSymbolsTest.State dead = new MapSymbolsTest.State();
            dead.cannons = false;
            PaintStyle st = new PaintStyle();
            st.pointSize = 4;
            VertexBatch four = draw(m, st, dead);
            st.pointDist = 3;
            VertexBatch three = draw(m, st, dead);
            st.pointSize = 0;
            VertexBatch none = draw(m, st, dead);
            assertAll(
                () -> assertEquals(2, four.pointCount(), "cannons (0,0) and (8,8) on the default distance"),
                () -> assertEquals(4f, four.pointSize(0), "4-pixel dots"),
                () -> assertEquals(1, three.pointCount(), "distance 3: only (0,0)"),
                () -> assertEquals(0, none.pointCount(), "size 0: none"));
        }

        VertexBatch draw(WorldMap m, PaintStyle st, MapState state) {
            Camera c = new Camera();
            c.setWorld(m.pixelWidth(), m.pixelHeight(), m.edgeWrap());
            c.setViewport(400, 400, 1);
            c.setZoom(1);
            c.setCentre(157, 157);
            c.update();
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P, st).draw(b, new TextWriter(Fixtures.font()), c, state);
            return b;
        }
    }

    @Nested
    class Radar {
        @Test
        void radarColoursComeFromTheStyle() {
            WorldMap m = map(false, "xb", "..");
            PaintStyle st = new PaintStyle();
            st.wallRadarColor = 7;
            st.decorRadarColor = 9;
            int[] px = RadarImage.build(m, P, st, i -> true, new int[RadarImage.WIDTH * RadarImage.height(m)]);
            st.showDecor = false;
            int[] hidden = RadarImage.build(m, P, st, i -> true, new int[RadarImage.WIDTH * RadarImage.height(m)]);
            assertAll(
                () -> assertEquals(P.color(7), px[0], "top-left (the wall) in wallRadarColor"),
                () -> assertEquals(P.color(9), px[RadarImage.WIDTH - 1], "top-right (the decoration) in decorRadarColor"),
                () -> assertEquals(P.color(Palette.BLACK), hidden[RadarImage.WIDTH - 1], "hidden decorations are black"),
                () -> assertEquals(7, RadarImage.colorIndex(com.fulcrologic.jxpilot.common.map.BlockType.FILLED, true, 7, 8, 9),
                    "colorIndex takes the wall colour"),
                () -> assertEquals(8, RadarImage.colorIndex(com.fulcrologic.jxpilot.common.map.BlockType.TARGET, true, 7, 8, 9),
                    "the target colour"));
        }
    }

    @Nested
    class Bitmaps {
        static final float A = com.fulcrologic.jxpilot.client.paint.ObjectPainter.ATLAS_SIZE;

        boolean samples(VertexBatch v, int i, com.fulcrologic.jxpilot.client.paint.BlockBitmap b, int p) {
            float u = v.u(i) * A;
            int x = com.fulcrologic.jxpilot.client.paint.BlockBitmaps.atlasX(b, p);
            float t = v.v(i) * A;
            int y = com.fulcrologic.jxpilot.client.paint.BlockBitmaps.atlasY(b, p);
            return u >= x - 0.01f && u <= x + b.width + 0.01f && t >= y - 0.01f && t <= y + b.height + 0.01f;
        }

        boolean any(VertexBatch v, com.fulcrologic.jxpilot.client.paint.BlockBitmap b) {
            for (int i = 0; i < v.vertexCount(); i += 6) {
                if (samples(v, i, b, 0)) {
                    return true;
                }
            }
            return false;
        }

        @Test
        void wallsAreSideBitmapsPlacedAsC() {
            WorldMap m = map(false, "x");
            PaintStyle st = new PaintStyle();
            st.texturedObjects = true;
            VertexBatch b = build(m, st, false, false);
            st.filledWorld = true;
            VertexBatch filled = build(m, st, false, false);
            WorldMap diag = map(false, "as");
            VertexBatch d = Segs.batch();
            StaticMap.wallBitmaps(diag, false, d);
            List<String> t = Segs.triangles(b);
            assertAll(
                () -> assertEquals(List.of("-1,0 34,0 34,35", "-1,0 34,35 -1,35", "0,-1 35,-1 35,34", "0,-1 35,34 0,34",
                    "1,0 36,0 36,35", "1,0 36,35 1,35", "0,1 35,1 35,36", "0,1 35,36 0,36"), t,
                    "left a pixel left, bottom a pixel lower, right a pixel right, top a pixel higher"),
                () -> assertEquals(4, b.segmentCount(), "only the border lines remain"),
                () -> assertTrue(samples(b, 0, com.fulcrologic.jxpilot.client.paint.BlockBitmap.WALL_LEFT, 0), "left picture"),
                () -> assertTrue(samples(b, 18, com.fulcrologic.jxpilot.client.paint.BlockBitmap.WALL_TOP, 0), "top picture"),
                () -> assertEquals(6, filled.vertexCount(), "filled walls are fills, not bitmaps"),
                () -> assertTrue(any(d, com.fulcrologic.jxpilot.client.paint.BlockBitmap.WALL_UL),
                    "a closed diagonal (a = REC_RU) is WALL_UL"),
                () -> assertTrue(any(d, com.fulcrologic.jxpilot.client.paint.BlockBitmap.WALL_UR),
                    "an open diagonal (s = REC_LU) is WALL_UR"),
                () -> assertTrue(samples(d, d.vertexCount() - 6, com.fulcrologic.jxpilot.client.paint.BlockBitmap.WALL_UR, 0),
                    "each block's diagonal comes after its sides"));
        }

        @Test
        void baseLinesAndPlusMinusGravityLinesAreLeftToTheBitmaps() {
            WorldMap m = map(false, "_+i");
            PaintStyle st = new PaintStyle();
            int plain = build(m, st, false, false).segmentCount();
            st.texturedObjects = true;
            int bm = build(m, st, false, false).segmentCount();
            assertAll(
                () -> assertEquals(plain - 1 - 2, bm, "no base line and no + cross; the up-gravity arrow stays"),
                () -> assertTrue(StaticMap.bitmapGravity(com.fulcrologic.jxpilot.common.map.BlockType.NEG_GRAV), "-"),
                () -> assertFalse(StaticMap.bitmapGravity(com.fulcrologic.jxpilot.common.map.BlockType.UP_GRAV), "arrows"));
        }

        @Test
        void mapSymbolsAreBitmaps() {
            WorldMap m = mapWith(false, "", "#r*+%&()", "_.......");
            PaintStyle st = new PaintStyle();
            st.texturedObjects = true;
            MapSymbolsTest.State state = new MapSymbolsTest.State();
            Camera c = new Camera();
            c.setWorld(m.pixelWidth(), m.pixelHeight(), m.edgeWrap());
            c.setViewport(400, 200, 1);
            c.setZoom(1);
            c.setCentre(122, 35);
            c.update();
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P, st).draw(b, new TextWriter(Fixtures.font()), c, state);
            VertexBatch arcs = Segs.batch();
            new MapSymbols(m, P, st).drawArcs(arcs, c, state);
            assertAll(
                () -> assertEquals(VertexBatch.TRIANGLES, b.commandMode(0), "pictures first"),
                () -> assertEquals(6 * 9, b.commandLength(0),
                    "fuel cell + level, cannon, base, treasure, +, two concentrators, the in-wormhole (not the out one)"),
                () -> assertEquals(0, b.segmentCount(), "no symbol lines (no target here)"),
                () -> assertEquals(0, arcs.segmentCount(), "no + circle, wormhole or treasure arcs"));
        }

        @Test
        void framesAndPlacesFollowC() {
            VertexBatch d = Segs.batch();
            MapSymbols.directed(d, com.fulcrologic.jxpilot.common.map.Dir.DOWN, 0, 0,
                com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_DOWN, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_UP,
                com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_RIGHT, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_LEFT);
            MapSymbols.directed(d, com.fulcrologic.jxpilot.common.map.Dir.RIGHT, 0, 0,
                com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_DOWN, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_UP,
                com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_RIGHT, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_LEFT);
            MapSymbols.directed(d, com.fulcrologic.jxpilot.common.map.Dir.UP, 0, 0,
                com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_DOWN, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_UP,
                com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_RIGHT, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_LEFT);
            MapSymbols.directed(d, com.fulcrologic.jxpilot.common.map.Dir.LEFT, 0, 0,
                com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_DOWN, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_UP,
                com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_RIGHT, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_LEFT);
            VertexBatch f = Segs.batch();
            MapSymbols.fuelBitmap(f, 0, 0, com.fulcrologic.jxpilot.common.Const.MAX_STATION_FUEL / 2, 0);
            List<String> t = Segs.triangles(d);
            assertAll(
                () -> assertEquals("0,-1 35,-1 35,34", t.get(0), "facing down: a pixel lower"),
                () -> assertTrue(samples(d, 0, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_UP, 0), "the up picture"),
                () -> assertEquals("-1,0 34,0 34,35", t.get(2), "facing right: a pixel left"),
                () -> assertTrue(samples(d, 6, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_LEFT, 0), "the left picture"),
                () -> assertEquals("0,0 35,0 35,35", t.get(4), "facing up: on the block"),
                () -> assertTrue(samples(d, 12, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_DOWN, 0), "the down picture"),
                () -> assertTrue(samples(d, 18, com.fulcrologic.jxpilot.client.paint.BlockBitmap.BASE_RIGHT, 0), "left: right"),
                () -> assertEquals(List.of("0,0 35,0 35,35", "0,0 35,35 0,35", "3,3 32,3 32,17", "3,3 32,17 3,17"),
                    Segs.triangles(f), "the cell, then 14 rows of level ((35 - 6) * 0.5) from 3 inside the bottom"),
                () -> assertEquals(5, MapSymbols.fuelFrame(0, 0, 5), "frames run up"),
                () -> assertEquals(15, MapSymbols.fuelFrame(0, 0, 16), "and back: 16 is 15"),
                () -> assertEquals(0, MapSymbols.fuelFrame(0, 0, 31), "31 is 0"),
                () -> assertEquals((int) ((3 + 35 + 35 * 70) % 32), MapSymbols.concentratorFrame(1, 2, 3),
                    "(loops + x + x * y) % 32 for block (1, 2)"));
        }
    }
}
