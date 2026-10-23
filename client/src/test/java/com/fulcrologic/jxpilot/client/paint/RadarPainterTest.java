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

import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.paint.PaintTests.*;
import static org.junit.jupiter.api.Assertions.*;

class RadarPainterTest {
    static final Palette P = Palette.xpilotDefault();
    static final int WHITE = P.color(Palette.WHITE);
    static final int GREEN = P.color(4);

    /** 256x128 radar drawn 1:1 at the framebuffer origin over a 2560x1280 world: 10 world px per radar px. */
    static RadarPainter radar(boolean wrap) {
        RadarPainter r = new RadarPainter(P);
        r.begin(0, 0, 256, 128, 128, 2560, 1280, wrap);
        return r;
    }

    @Nested
    class Self {
        @Test
        void headingLineIsEightRadarPixelsFromTheShip() {
            VertexBatch b = batch();
            radar(false).self(b, 1280, 640, 0, 0);
            radar(false).self(b, 1280, 640, Math.PI / 2, 0);
            assertAll(
                () -> assertEquals(List.of("128.5,64.5-136.5,64.5", "128.5,64.5-128.5,72.5"), segs(b),
                    "pixel (128, 128 - 64 - 1 = 63): right 8, or up 8 (row 55)"),
                () -> assertEquals(WHITE, b.segColor(0), "white"));
        }

        @Test
        void blinksOffForThreeFramesInSixteen() {
            VertexBatch b = batch();
            RadarPainter r = radar(false);
            r.self(b, 1280, 640, 0, 12);
            r.self(b, 1280, 640, 0, 13);
            r.self(b, 1280, 640, 0, 15);
            r.self(b, 1280, 640, 0, 16);
            assertEquals(2, b.segmentCount(), "loops 12 and 16 draw; 13 and 15 do not");
        }

        @Test
        void wrappingRepeatsThePartPastTheEdge() {
            VertexBatch w = batch();
            radar(true).self(w, 2550, 640, 0, 0);
            VertexBatch h = batch();
            radar(false).self(h, 2550, 640, 0, 0);
            assertAll(
                () -> assertEquals(List.of("255.5,64.5-255.5,64.5", "0.5,64.5-7.5,64.5"), segs(w),
                    "from pixel 255 the line is clipped at the edge and continues from x -1 on the left"),
                () -> assertEquals(List.of("255.5,64.5-255.5,64.5"), segs(h), "no repeat without wrap"));
        }

        @Test
        void aShipAtTheLeftOrTopEdgeIsMovedOneRadarForward() {
            VertexBatch w = batch();
            radar(true).self(w, 0, 1275, 0, 0);
            VertexBatch h = batch();
            radar(false).self(h, 0, 1275, 0, 0);
            assertAll(
                () -> assertEquals(List.of("0.5,0.5-8.5,0.5"), segs(w),
                    "x 0 becomes 256 and row -1 becomes 127: the line from (256,127) is off the radar, its wrap copy from column 0 shows"),
                () -> assertEquals(0, h.segmentCount(), "without wrap the line is off the radar"));
        }

        @Test
        void verticalWrapRepeatsDownwardAndDiagonally() {
            VertexBatch b = batch();
            // pixel (255, 2) heading 45 degrees: end (260, -3); repeats shifted by 256 in x and 128 in y
            radar(true).self(b, 2550, 1250, Math.PI / 4, 0);
            assertEquals(List.of("255.5,125.5-255.5,125.5", "0.5,126.5-1.5,127.5", "2.5,0.5-4.5,2.5"), segs(b),
                "original clipped to its start pixel; x repeat (-1,2)-(4,-3) clipped to (0,1)-(1,0); the y repeat"
                    + " (255,130)-(260,125) is entirely outside; xy repeat (-1,130)-(4,125) clipped to (2,127)-(4,125)");
        }
    }

    @Nested
    class Blips {
        @Test
        void blipIsASizedSquareAroundTheRadarPixel() {
            VertexBatch b = batch();
            radar(false).blip(b, 1280, 640, 3, false);
            radar(false).blip(b, 1280, 640, 0, true);
            assertAll(
                () -> assertEquals(List.of("127,63 130,63 130,66", "127,63 130,66 127,66",
                    "128,64 129,64 129,65", "128,64 129,65 128,65"), tris(b),
                    "size 3 from (128-1, 63-1); size 0 drawn as 1 pixel at (128, 63)"),
                () -> assertEquals(WHITE, b.color(0), "enemies white"),
                () -> assertEquals(GREEN, b.color(6), "friends in colour 4"));
        }

        @Test
        void wrapRepeatsSquaresCrossingAnEdgeAndClipsThem() {
            VertexBatch w = batch();
            radar(true).blip(w, 2555, 640, 3, false);
            VertexBatch h = batch();
            radar(false).blip(h, 2555, 640, 3, false);
            assertAll(
                () -> assertEquals(List.of("255,63 256,63 256,66", "255,63 256,66 255,66",
                    "0,63 2,63 2,66", "0,63 2,66 0,66"), tris(w),
                    "column 255 clipped to the radar, the rest from column 0"),
                () -> assertEquals(2, h.vertexCount() / 3, "hard edges: just the clipped square"));
        }

        @Test
        void wrapRepeatsAcrossTheTopAndBothEdges() {
            VertexBatch b = batch();
            radar(true).blip(b, 2555, 1275, 3, false);
            assertEquals(8, b.vertexCount() / 3, "a corner blip shows in all four corners (2 triangles each)");
        }

        @Test
        void squaresOutsideTheRadarDrawNothing() {
            VertexBatch b = batch();
            radar(false).rect(b, -5, 10, 3, WHITE);
            radar(false).rect(b, 10, 128, 3, WHITE);
            assertEquals(0, b.vertexCount(), "fully left of or below the radar");
        }
    }

    @Test
    void checkpointIsADiamondAboveTheBlockCorner() {
        VertexBatch b = batch();
        radar(false).checkpoint(b, 10, 5);
        assertAll(
            () -> assertEquals(List.of("35.5,14.5-39.5,18.5", "39.5,18.5-35.5,22.5", "35.5,22.5-31.5,18.5",
                "31.5,18.5-35.5,14.5"), segs(b), "x (int)(350 * 0.1 + 0.5) = 35, y 128 - 18 + 4 - 1 = 113"),
            () -> assertEquals(WHITE, b.segColor(0), "white"));
    }

    @Nested
    class Clipping {
        @Test
        void linesAreClippedToThePixelCentreBox() {
            VertexBatch b = batch();
            RadarPainter r = radar(false);
            r.line(b, -10, 10, 300, 10, WHITE);
            r.line(b, 10, -10, 10, 200, WHITE);
            r.line(b, -5, -5, -1, -1, WHITE);
            r.line(b, 300, 10, 310, 10, WHITE);
            r.line(b, 10, 0, 20, 0, WHITE);
            assertAll(
                () -> assertEquals(List.of("0.5,117.5-255.5,117.5", "10.5,127.5-10.5,0.5", "10.5,127.5-20.5,127.5"), segs(b),
                    "horizontal and vertical clipped at both ends; outside lines dropped; a line on row 0 kept"));
        }

        @Test
        void theRectangleScalesWithTheRadarQuad() {
            RadarPainter r = new RadarPainter(P);
            r.begin(100, 50, 612, 306, 128, 2560, 1280, false); // 2 framebuffer px per radar px
            VertexBatch b = batch();
            r.blip(b, 1280, 640, 1, false);
            r.self(b, 1280, 640, 0, 0);
            assertAll(
                () -> assertEquals("356,178 358,178 358,180", tris(b).get(0), "pixel (128, 63) is 2x2 at (100 + 256, 306 - 128)"),
                () -> assertEquals("357,179-373,179", segs(b).get(0), "line ends at pixel centres, scaled"));
        }
    }

    @Nested
    class Edges {
        @Test
        void squaresAndLinesStartingAtZeroMoveOneRadarForward() {
            VertexBatch b = batch();
            RadarPainter r = radar(false);
            r.blip(b, 10, 640, 3, false);   // radar x 1 - 1 = 0: moved to 256, off the radar
            r.blip(b, 1280, 1260, 3, false); // row 128 - 126 - 1 - 1 = 0: moved to 128, off the radar
            r.self(b, 1280, 1270, 0, 0);     // row 128 - 127 - 1 = 0: moved to 128, off the radar
            assertAll(
                () -> assertEquals(0, b.vertexCount(), "no squares"),
                () -> assertEquals(0, b.segmentCount(), "no heading line"));
        }

        @Test
        void checkpointAtColumnZeroIsMovedToTheRightEdge() {
            VertexBatch b = batch();
            radar(false).checkpoint(b, 0, 5);
            assertEquals(List.of("255.5,21.5-252.5,18.5", "252.5,18.5-255.5,15.5"), segs(b),
                "x 0 becomes 256: only the diamond's left half shows, clipped at column 255");
        }

        @Test
        void verticalWrapAloneRepeatsTheLineFromTheBottom() {
            VertexBatch b = batch();
            // pixel (128, 2) heading up: (128, 2)-(128, -6)
            radar(true).self(b, 1280, 1250, Math.PI / 2, 0);
            assertEquals(List.of("128.5,125.5-128.5,127.5", "128.5,0.5-128.5,5.5"), segs(b),
                "the top part, then the rest from row 127 up to row 122 (shifted by 128)");
        }

        @Test
        void horizontalLinesOutsideAndZeroWidthSquaresDrawNothing() {
            VertexBatch b = batch();
            RadarPainter r = radar(false);
            r.line(b, 10, -5, 20, -5, WHITE);
            r.line(b, 10, 130, 20, 130, WHITE);
            r.rect(b, 256, 10, 3, WHITE);
            r.rect(b, 10, 128, 3, WHITE);
            assertAll(
                () -> assertEquals(0, b.segmentCount(), "lines above and below the radar are dropped"),
                () -> assertEquals(0, b.vertexCount(), "squares starting on the right or bottom edge are empty"));
        }
    }
}
