package com.fulcrologic.jxpilot.client.map;

import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

class MapEdgesTest {
    static final int C = 0x12345678;

    static List<String> walls(WorldMap m, boolean outline) {
        VertexBatch b = Segs.batch();
        MapEdges.walls(m, outline, b, C);
        return Segs.lines(b);
    }

    static List<String> decor(WorldMap m, boolean outline) {
        VertexBatch b = Segs.batch();
        MapEdges.decor(m, outline, b, C);
        return Segs.lines(b);
    }

    @Nested
    class SingleBlocks {
        @Test
        void filledBlockDrawsLeftDownRightUp() {
            WorldMap m = map(false, "...", ".x.", "...");
            VertexBatch b = Segs.batch();
            MapEdges.walls(m, false, b, C);
            assertAll(
                () -> assertEquals(List.of("35,35-35,70", "35,35-70,35", "70,35-70,70", "35,70-70,70"), Segs.lines(b),
                    "all four sides in C order"),
                () -> assertEquals(List.of(C), Segs.colors(b), "in the given colour"));
        }

        @Test
        void fuelIsAWallBlock() {
            assertEquals(4, walls(map(false, "...", ".#.", "..."), false).size(), "fuel gets all four sides");
        }

        @Test
        void diagonalsDrawTheirTwoSidesAndTheDiagonal() {
            assertAll(
                () -> assertEquals(List.of("70,35-70,70", "35,70-70,70", "35,70-70,35"),
                    walls(map(false, "...", ".a.", "..."), false), "a (REC_RU): right, up, closed diagonal"),
                () -> assertEquals(List.of("35,35-35,70", "35,70-70,70", "35,35-70,70"),
                    walls(map(false, "...", ".s.", "..."), false), "s (REC_LU): left, up, open diagonal"),
                () -> assertEquals(List.of("35,35-35,70", "35,35-70,35", "35,70-70,35"),
                    walls(map(false, "...", ".w.", "..."), false), "w (REC_LD): left, down, closed diagonal"),
                () -> assertEquals(List.of("35,35-70,35", "70,35-70,70", "35,35-70,70"),
                    walls(map(false, "...", ".q.", "..."), false), "q (REC_RD): down, right, open diagonal"));
        }

        @Test
        void nonWallBlocksDrawNothing() {
            assertEquals(List.of(), walls(map(false, "...", ".+.", "..."), false), "gravity is not a wall");
        }
    }

    @Nested
    class Neighbours {
        @Test
        void defaultGridDrawsSharedVerticalEdgesOnceFromTheLeftBlock() {
            List<String> segs = walls(map(false, "....", ".xx.", "...."), false);
            assertAll(
                () -> assertEquals(7, segs.size(), "4 + 3 sides"),
                () -> assertTrue(segs.contains("70,35-70,70"), "the shared edge is drawn as the left block's right side"),
                () -> assertEquals(1, segs.stream().filter("70,35-70,70"::equals).count(), "exactly once"));
        }

        @Test
        void outlineModeDropsSharedEdges() {
            List<String> segs = walls(map(false, "....", ".xx.", "...."), true);
            assertAll(
                () -> assertEquals(6, segs.size(), "only the outer outline"),
                () -> assertFalse(segs.contains("70,35-70,70"), "no edge between the blocks"),
                () -> assertTrue(segs.contains("105,35-105,70"), "the outer right edge stays"));
        }

        @Test
        void sharedHorizontalEdgeIsTheLowerBlocksTopInDefaultMode() {
            WorldMap m = map(false, "...", ".x.", ".x.", "...");
            List<String> def = walls(m, false);
            List<String> out = walls(m, true);
            assertAll(
                () -> assertEquals(7, def.size(), "default: 4 + 3 sides"),
                () -> assertEquals(1, def.stream().filter("35,70-70,70"::equals).count(), "shared edge drawn once"),
                () -> assertEquals(6, out.size(), "outline: no shared edge"),
                () -> assertFalse(out.contains("35,70-70,70"), "outline omits the shared edge"));
        }

        @Test
        void onlyTheFacingSideSuppresses() {
            // s (REC_LU) has LEFT and UP but no RIGHT: the filled block on its right keeps its left side
            List<String> sx = walls(map(false, "....", ".sx.", "...."), false);
            // x then s: s's left is covered by x's right
            List<String> xs = walls(map(false, "....", ".xs.", "...."), false);
            assertAll(
                () -> assertTrue(sx.contains("70,35-70,70"), "x left of nothing-facing s is drawn"),
                () -> assertEquals(1, xs.stream().filter("70,35-70,70"::equals).count(),
                    "x's right is drawn and s's left suppressed"));
        }

        @Test
        void decorationsAndWallsAreSeparateFamilies() {
            WorldMap m = map(false, "....", ".bx.", "....");
            List<String> w = walls(m, true);
            List<String> d = decor(m, true);
            assertAll(
                () -> assertTrue(w.contains("70,35-70,70"), "wall's left side is drawn next to a decoration"),
                () -> assertTrue(d.contains("70,35-70,70"), "decoration's right side is drawn next to a wall"),
                () -> assertEquals(4, w.size(), "wall pass draws only the wall"),
                () -> assertEquals(4, d.size(), "decor pass draws only the decoration"));
        }

        @Test
        void decorationsFollowTheSameRules() {
            WorldMap m = map(false, "....", ".bb.", "....");
            assertAll(
                () -> assertEquals(7, decor(m, false).size(), "default grid draws the shared edge once"),
                () -> assertEquals(6, decor(m, true).size(), "outline drops it"),
                () -> assertEquals(List.of("70,35-70,70", "35,70-70,70", "35,70-70,35"),
                    decor(map(false, "...", ".g.", "..."), false), "g is the RU decoration"));
        }
    }

    @Nested
    class Edges {
        @Test
        void hardEdgeAlwaysDrawsTheSide() {
            List<String> segs = walls(map(false, "x.x"), true);
            assertAll(
                () -> assertTrue(segs.contains("0,0-0,35"), "left side at the world edge"),
                () -> assertTrue(segs.contains("105,0-105,35"), "right side at the world edge"),
                () -> assertEquals(8, segs.size(), "two isolated blocks"));
        }

        @Test
        void blocksAtIndexZeroAreRealNeighbours() {
            List<String> row = walls(map(false, "xx"), true);
            List<String> col = walls(map(false, "x", "x"), true);
            assertAll(
                () -> assertEquals(6, row.size(), "x=1's left is covered by the block at x=0"),
                () -> assertFalse(row.contains("35,0-35,35"), "no shared vertical edge"),
                () -> assertEquals(6, col.size(), "y=1's down is covered by the block at y=0"),
                () -> assertFalse(col.contains("0,35-35,35"), "no shared horizontal edge"));
        }

        @Test
        void wrapUsesTheOppositeEdgeAsNeighbour() {
            WorldMap m = map(true, "...", "x.x", "...");
            List<String> def = walls(m, false);
            List<String> out = walls(m, true);
            assertAll(
                () -> assertFalse(def.contains("0,35-0,70"), "left side at x=0 is covered by the right block's right side"),
                () -> assertTrue(def.contains("105,35-105,70"), "default draws the right block's right side"),
                () -> assertEquals(7, def.size(), "default: 3 + 4 sides"),
                () -> assertFalse(out.contains("105,35-105,70"), "outline drops it since x=0 has a left side"),
                () -> assertEquals(6, out.size(), "outline: both seam sides dropped"));
        }

        @Test
        void wrapAppliesVertically() {
            WorldMap m = map(true, "x", ".", "x");
            List<String> def = walls(m, false);
            assertAll(
                () -> assertFalse(def.contains("0,0-35,0"), "bottom row's down side is covered by the top row's up side"),
                () -> assertTrue(def.contains("0,105-35,105"), "top row's up side is drawn"),
                () -> assertFalse(walls(m, true).contains("0,105-35,105"), "outline drops the seam"));
        }

        @Test
        void borderIsDrawnOnlyForHardEdges() {
            VertexBatch hard = Segs.batch();
            MapEdges.border(map(false, "...", "..."), hard, C);
            VertexBatch soft = Segs.batch();
            MapEdges.border(map(true, "...", "..."), soft, C);
            assertAll(
                () -> assertEquals(List.of("0,0-0,70", "105,0-105,70", "0,0-105,0", "0,70-105,70"), Segs.lines(hard),
                    "left, right, bottom, top border"),
                () -> assertEquals(0, soft.vertexCount(), "no border when wrapping"));
        }
    }
}
