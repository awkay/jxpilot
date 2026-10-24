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
package com.fulcrologic.jxpilot.client.paint;

import com.fulcrologic.jxpilot.client.render.VertexBatch;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.fulcrologic.jxpilot.client.paint.PaintTests.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockBitmapsTest {
    static final float UV = 1e-4f;
    static final float A = ObjectPainter.ATLAS_SIZE;

    @Nested
    class Table {
        @Test
        void picturesAndImagesFollowCsRotations() {
            assertAll(
                () -> assertEquals(44, BlockBitmap.values().length, "C's NUM_BITMAPS"),
                () -> assertTrue(BlockBitmap.SHIP_SELF.rotates(), "ships rotate"),
                () -> assertEquals(1, BlockBitmap.SHIP_SELF.pictures(), "one stored picture"),
                () -> assertEquals(128, BlockBitmap.SHIP_SELF.images(), "128 rotation steps"),
                () -> assertFalse(BlockBitmap.FUEL.rotates(), "fuel is an animation"),
                () -> assertEquals(16, BlockBitmap.FUEL.pictures(), "16 pictures side by side"),
                () -> assertEquals(16, BlockBitmap.FUEL.images(), "16 images"),
                () -> assertFalse(BlockBitmap.BALL.rotates(), "rotations 1 is a plain picture"),
                () -> assertEquals(1, BlockBitmap.BALL.images(), "one image"),
                () -> assertFalse(BlockBitmap.SCORE_BG.inAtlas(), "the score background has its own texture"),
                () -> assertFalse(BlockBitmap.LOGO.inAtlas(), "so has the logo"),
                () -> assertTrue(BlockBitmap.METER.inAtlas(), "the rest is in the atlas"));
        }
    }

    @Nested
    class Layout {
        @Test
        void everyPictureHasItsOwnPlaceInsideTheBlock() {
            Set<String> seen = new HashSet<>();
            boolean inside = true;
            boolean apart = true;
            List<int[]> rects = new java.util.ArrayList<>();
            for (BlockBitmap b : BlockBitmap.values()) {
                if (!b.inAtlas()) {
                    continue;
                }
                for (int p = 0; p < b.pictures(); p++) {
                    int x = BlockBitmaps.atlasX(b, p);
                    int y = BlockBitmaps.atlasY(b, p);
                    inside &= x >= BlockBitmaps.X0 && x + b.width <= BlockBitmaps.X0 + BlockBitmaps.W
                        && y >= BlockBitmaps.Y0 && y + b.height <= BlockBitmaps.Y0 + BlockBitmaps.H;
                    seen.add(x + "," + y);
                    for (int[] r : rects) {
                        apart &= x + b.width <= r[0] || r[0] + r[2] <= x || y + b.height <= r[1] || r[1] + r[3] <= y;
                    }
                    rects.add(new int[] {x, y, b.width, b.height});
                }
            }
            boolean in = inside;
            boolean ap = apart;
            assertAll(
                () -> assertTrue(in, "every picture inside the free atlas block"),
                () -> assertTrue(ap, "no two pictures overlap"),
                () -> assertEquals(rects.size(), seen.size(), "distinct corners"),
                () -> assertEquals(BlockBitmaps.X0, BlockBitmaps.atlasX(BlockBitmap.HOLDER_FRIEND, 0), "packing starts at the block's corner"),
                () -> assertEquals(BlockBitmaps.Y0, BlockBitmaps.atlasY(BlockBitmap.HOLDER_FRIEND, 0), "top row"),
                () -> assertEquals(BlockBitmaps.X0 + 36, BlockBitmaps.atlasX(BlockBitmap.HOLDER_ENEMY, 0), "the next a texel apart"),
                () -> assertEquals(BlockBitmaps.atlasX(BlockBitmap.FUEL, 0), BlockBitmaps.atlasX(BlockBitmap.FUEL, 16),
                    "picture numbers wrap"),
                () -> assertTrue(BlockBitmaps.usedRows() <= BlockBitmaps.H, "fits (" + BlockBitmaps.usedRows() + " rows)"));
        }
    }

    @Nested
    class Bake {
        @Test
        void theBundledFilesLoadIntoTheirPlaces() throws Exception {
            int[] region = BlockBitmaps.bake(BlockBitmaps.CLASSPATH);
            int x = BlockBitmaps.atlasX(BlockBitmap.BALL, 0) - BlockBitmaps.X0;
            int y = BlockBitmaps.atlasY(BlockBitmap.BALL, 0) - BlockBitmaps.Y0;
            int[] bg = BlockBitmaps.loadSeparate(BlockBitmap.SCORE_BG, BlockBitmaps.CLASSPATH);
            boolean ballHasPixels = false;
            for (int j = 0; j < 21; j++) {
                for (int i = 0; i < 21; i++) {
                    ballHasPixels |= region[(y + j) * BlockBitmaps.W + x + i] != 0;
                }
            }
            boolean ball = ballHasPixels;
            assertAll(
                () -> assertEquals(BlockBitmaps.W * BlockBitmaps.H, region.length, "the whole block"),
                () -> assertTrue(ball, "the ball picture is there"),
                () -> assertEquals(256 * 442, bg.length, "the clouds picture"));
        }

        @Test
        void wrongSizesAreRefused() {
            byte[] tiny = "P6\n1 1\n255\n\1\1\1".getBytes(StandardCharsets.ISO_8859_1);
            assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> BlockBitmaps.bake(f -> tiny),
                    "a 1x1 file for a 35x35 bitmap"),
                () -> assertThrows(IllegalArgumentException.class,
                    () -> BlockBitmaps.loadSeparate(BlockBitmap.LOGO, f -> tiny), "a 1x1 logo"),
                () -> assertThrows(java.io.IOException.class, () -> BlockBitmaps.CLASSPATH.read("missing.ppm"),
                    "a missing resource"));
        }

        @Test
        void picturesOfAFileGoSideBySide() throws Exception {
            // every file a solid colour; the checkpoint file's second picture brighter
            BlockBitmaps.Source src = f -> solid(f);
            int[] region = BlockBitmaps.bake(src);
            int x0 = BlockBitmaps.atlasX(BlockBitmap.CHECKPOINT, 0) - BlockBitmaps.X0;
            int y0 = BlockBitmaps.atlasY(BlockBitmap.CHECKPOINT, 0) - BlockBitmaps.Y0;
            int x1 = BlockBitmaps.atlasX(BlockBitmap.CHECKPOINT, 1) - BlockBitmaps.X0;
            int y1 = BlockBitmaps.atlasY(BlockBitmap.CHECKPOINT, 1) - BlockBitmaps.Y0;
            assertAll(
                () -> assertEquals(com.fulcrologic.jxpilot.client.render.Palette.pack(10, 10, 10, 255),
                    region[y0 * BlockBitmaps.W + x0], "first picture from the left half"),
                () -> assertEquals(com.fulcrologic.jxpilot.client.render.Palette.pack(20, 20, 20, 255),
                    region[(y1 + 34) * BlockBitmaps.W + x1 + 34], "second picture from the right half, to its last pixel"));
        }

        /** A file of the bitmap's size: the left half (first pictures) grey 10, the rest grey 20. */
        byte[] solid(String file) {
            BlockBitmap b = null;
            for (BlockBitmap c : BlockBitmap.values()) {
                if (c.file.equals(file)) {
                    b = c;
                }
            }
            int w = b.width * b.pictures();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes(("P6\n" + w + " " + b.height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            for (int y = 0; y < b.height; y++) {
                for (int x = 0; x < w; x++) {
                    int v = x < b.width ? 10 : 20;
                    o.write(v);
                    o.write(v);
                    o.write(v);
                }
            }
            return o.toByteArray();
        }
    }

    @Nested
    class Draw {
        @Test
        void aPictureIsAQuadWithItsTopRowAtTheTop() {
            VertexBatch b = batch();
            BlockBitmaps.draw(b, BlockBitmap.BALL, 0, 10, 50, 21, 21);
            VertexBatch rows = batch();
            BlockBitmaps.drawRows(rows, BlockBitmap.FUEL, 2, 0, 10, 29, 10, 10);
            VertexBatch none = batch();
            BlockBitmaps.drawRows(none, BlockBitmap.FUEL, 2, 0, 10, 29, 0, 0);
            float u0 = BlockBitmaps.atlasX(BlockBitmap.BALL, 0) / A;
            float vt = BlockBitmaps.atlasY(BlockBitmap.BALL, 0) / A;
            assertAll(
                () -> assertEquals(List.of("10,29 31,29 31,50", "10,29 31,50 10,50"), tris(b), "21 x 21 down from the top"),
                () -> assertEquals(u0, b.u(0), UV, "left texel column at the left"),
                () -> assertEquals(vt + 21 / A, b.v(0), UV, "bottom row at the bottom"),
                () -> assertEquals(vt, b.v(2), UV, "top row at the top"),
                () -> assertEquals(-1, b.color(0), "untinted"),
                () -> assertEquals((BlockBitmaps.atlasY(BlockBitmap.FUEL, 2) + 10) / A, rows.v(0), UV,
                    "a slice samples only the picture's top 10 rows"),
                () -> assertEquals(0, none.vertexCount(), "no rows: nothing"));
        }

        @Test
        void aTurnedPictureRotatesCounterClockwise() {
            VertexBatch b = batch();
            BlockBitmaps.drawTurned(b, BlockBitmap.SHIP_SELF, 100, 100, 32, 0);
            VertexBatch q = batch();
            BlockBitmaps.drawTurned(q, BlockBitmap.SHIP_SELF, 100, 100, 32, 32);
            assertAll(
                () -> assertEquals(List.of("84,84 116,84 116,116", "84,84 116,116 84,116"), tris(b), "unturned: the square"),
                () -> assertEquals(List.of("116,84 116,116 84,116", "116,84 84,116 84,84"), tris(q),
                    "a quarter turn: the picture's bottom-left corner moves to the bottom right"),
                () -> assertEquals(b.u(0), q.u(0), UV, "same texture corner"));
        }
    }
}
