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
package com.fulcrologic.jxpilot.client.render;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class CameraTest {
    /** Exact binary fractions are used throughout; tolerance only covers float conversion. */
    static final float EPS = 1e-4f;

    static Camera camera(double ww, double wh, boolean wrap, int vw, int vh, double ratio, double zoom,
                         double cx, double cy) {
        Camera c = new Camera();
        c.setWorld(ww, wh, wrap);
        c.setViewport(vw, vh, ratio);
        c.setZoom(zoom);
        c.setCentre(cx, cy);
        c.update();
        return c;
    }

    @Nested
    class Mapping {
        @Test
        void worldToScreenUsesZoomTimesPixelRatioAroundTheViewportCentre() {
            Camera c = camera(1000, 1000, false, 800, 600, 2, 0.5, 100, 200);
            assertAll(
                () -> assertEquals(1.0, c.scale(), "scale = zoom * pixel ratio"),
                () -> assertEquals(400f, c.screenX(100, 0), EPS, "centre x maps to viewport centre"),
                () -> assertEquals(300f, c.screenY(200, 0), EPS, "centre y maps to viewport centre"),
                () -> assertEquals(410f, c.screenX(110, 0), EPS, "10 world px right is 10 screen px right"),
                () -> assertEquals(290f, c.screenY(190, 0), EPS, "y is up: 10 world px down is 10 px down"),
                () -> assertEquals(110.0, c.worldX(410), 1e-9, "worldX inverts screenX"),
                () -> assertEquals(190.0, c.worldY(290), 1e-9, "worldY inverts screenY"));
        }

        @Test
        void inverseMappingUsesTheScale() {
            Camera c = camera(1000, 1000, false, 800, 600, 2, 1, 100, 200);
            assertAll(
                () -> assertEquals(105.0, c.worldX(410), 1e-9, "10 screen px at scale 2 is 5 world px"),
                () -> assertEquals(195.0, c.worldY(290), 1e-9, "same for y"),
                () -> assertEquals(320f, c.screenY(210, 0), EPS, "10 world px up is 20 screen px up at scale 2"));
        }

        @Test
        void viewportIsAtLeastOnePixel() {
            Camera c = camera(1000, 1000, false, 0, -5, 1, 1, 0, 0);
            assertAll(
                () -> assertEquals(1, c.viewWidth(), "width clamps to 1"),
                () -> assertEquals(1, c.viewHeight(), "height clamps to 1"));
        }

        @Test
        void applyToMakesTheBatchWriteScreenPixels() {
            Camera c = camera(1000, 1000, true, 800, 600, 1, 2, 990, 500);
            VertexBatch b = VertexBatch.fixed(ByteBuffer.allocateDirect(64 * VertexBatch.STRIDE), 4, x -> { });
            for (int k = 0; k < c.offsetCount(); k++) {
                c.applyTo(b, k);
                b.line(5, 500, 0, 0, 0);
            }
            assertAll(
                () -> assertEquals(2, c.offsetCount(), "two copies near the right seam"),
                () -> assertEquals(c.screenX(5, 0), b.x(0), EPS, "copy 0 matches screenX"),
                () -> assertEquals(c.screenX(5, 1), b.x(2), EPS, "copy 1 matches screenX"),
                () -> assertEquals(430f, b.x(2), EPS, "x=5 in the next copy is 15 world px right of centre"),
                () -> assertEquals(c.screenY(500, 1), b.y(2), EPS, "y matches screenY"));
        }

        @Test
        void ndcTransformsAgreeWithScreenMapping() {
            Camera c = camera(1000, 1000, true, 800, 600, 1, 2, 990, 10);
            float[] t = new float[4];
            c.worldToNdc(c.offsetCount() - 1, t);
            int k = c.offsetCount() - 1;
            float wx = 7;
            float wy = 3;
            float ndcX = wx * t[0] + t[2];
            float ndcY = wy * t[1] + t[3];
            float[] s = new float[4];
            c.screenToNdc(s);
            assertAll(
                () -> assertEquals(c.screenX(wx, k) * 2 / 800 - 1, ndcX, EPS, "world NDC x = screen x / width * 2 - 1"),
                () -> assertEquals(c.screenY(wy, k) * 2 / 600 - 1, ndcY, EPS, "world NDC y = screen y / height * 2 - 1"),
                () -> assertEquals(2f / 800, s[0], "screen scale x"),
                () -> assertEquals(2f / 600, s[1], "screen scale y"),
                () -> assertEquals(-1f, s[2], "screen origin x at NDC -1"),
                () -> assertEquals(-1f, s[3], "screen origin y at NDC -1"));
        }
    }

    @Nested
    class Wrap {
        @Test
        void nonWrappingWorldHasOneOffsetAndKeepsTheCentre() {
            Camera c = camera(1000, 1000, false, 800, 600, 1, 1, -50, 1200);
            assertAll(
                () -> assertEquals(1, c.offsetCount(), "single copy"),
                () -> assertEquals(0.0, c.offsetX(0), "at x offset 0"),
                () -> assertEquals(0.0, c.offsetY(0), "at y offset 0"),
                () -> assertEquals(-50.0, c.centreX(), "centre x not wrapped"),
                () -> assertEquals(1200.0, c.centreY(), "centre y not wrapped"));
        }

        @Test
        void wrappingWorldReducesTheCentreIntoTheWorld() {
            Camera c = camera(1000, 500, true, 100, 100, 1, 1, -50, 1200);
            assertAll(
                () -> assertEquals(950.0, c.centreX(), 1e-9, "negative x wraps up"),
                () -> assertEquals(200.0, c.centreY(), 1e-9, "large y wraps down"));
        }

        @Test
        void centreExactlyOneWorldBackWrapsToZero() {
            Camera c = camera(1000, 1000, true, 100, 100, 1, 1, -1000, 2000);
            assertAll(
                () -> assertEquals(0.0, c.centreX(), "-1000 wraps to 0, not 1000"),
                () -> assertEquals(0.0, c.centreY(), "2000 wraps to 0"));
        }

        @Test
        void tinyNegativeCentreWrapsToZeroNotTheWorldSize() {
            Camera c = camera(1000, 1000, true, 100, 100, 1, 1, -1e-14, 0);
            assertEquals(0.0, c.centreX(), "-1e-14 + 1000 rounds to 1000, which is reduced to 0");
        }

        @Test
        void viewInsideTheWorldNeedsOneCopy() {
            Camera c = camera(1000, 1000, true, 200, 200, 1, 1, 500, 500);
            assertAll(
                () -> assertEquals(1, c.offsetCount(), "one copy"),
                () -> assertEquals(0.0, c.offsetX(0), "offset x 0"),
                () -> assertEquals(0.0, c.offsetY(0), "offset y 0"));
        }

        @Test
        void viewOverTheLeftSeamDrawsThePreviousCopyFirst() {
            Camera c = camera(1000, 1000, true, 200, 200, 1, 1, 50, 500);
            assertAll(
                () -> assertEquals(2, c.offsetCount(), "two copies"),
                () -> assertEquals(-1000.0, c.offsetX(0), "the copy left of the seam"),
                () -> assertEquals(0.0, c.offsetX(1), "then the world itself"),
                () -> assertEquals(0.0, c.offsetY(1), "no y offset"));
        }

        @Test
        void viewOverACornerDrawsFourCopiesYOuterXInner() {
            Camera c = camera(1000, 1000, true, 200, 200, 1, 1, 980, 20);
            double[][] expected = {{0, -1000}, {1000, -1000}, {0, 0}, {1000, 0}};
            assertEquals(4, c.offsetCount(), "four copies");
            for (int i = 0; i < 4; i++) {
                int k = i;
                assertAll(
                    () -> assertEquals(expected[k][0], c.offsetX(k), "copy " + k + " x"),
                    () -> assertEquals(expected[k][1], c.offsetY(k), "copy " + k + " y"));
            }
        }

        @Test
        void viewEdgeExactlyOnASeamDoesNotNeedTheNextCopy() {
            Camera right = camera(1000, 1000, true, 200, 200, 1, 1, 900, 500);
            Camera left = camera(1000, 1000, true, 200, 200, 1, 1, 100, 500);
            assertAll(
                () -> assertEquals(1, right.offsetCount(), "right edge at x=1000 stays in one copy"),
                () -> assertEquals(1, left.offsetCount(), "left edge at x=0 stays in one copy"));
        }

        @Test
        void zoomedFarOutKeepsAtMostThreeCopiesPerAxisCentredOnTheView() {
            // view spans 10000 world px over a 1000 px world: 11 copies, keep the middle 3
            Camera c = camera(1000, 1000, true, 1000, 100, 1, 0.1, 500, 500);
            assertAll(
                () -> assertEquals(3, c.offsetCount(), "three x copies, one y copy"),
                () -> assertEquals(-1000.0, c.offsetX(0), "left neighbour"),
                () -> assertEquals(0.0, c.offsetX(1), "the world"),
                () -> assertEquals(1000.0, c.offsetX(2), "right neighbour"));
        }

        @Test
        void copyRangeHelpers() {
            assertAll(
                () -> assertEquals(-1, Camera.copyFirst(50, 100, 1000), "left edge at -50 is in copy -1"),
                () -> assertEquals(0, Camera.copyLast(50, 100, 1000, -1), "right edge at 150 is in copy 0"),
                () -> assertEquals(0, Camera.copyFirst(100, 100, 1000), "left edge exactly at 0 is copy 0"),
                () -> assertEquals(1, Camera.copyLast(950, 100, 1000, 0), "right edge at 1050 is copy 1"),
                () -> assertEquals(-1, Camera.copyFirst(500, 2000, 1000), "five copies trimmed from -2 to start at -1"),
                () -> assertEquals(1, Camera.copyLast(500, 2000, 1000, -1), "and end at +1"));
        }
    }

    @Nested
    class Visibility {
        @Test
        void rectanglesOverlappingOrTouchingTheViewAreVisible() {
            Camera c = camera(10000, 10000, false, 100, 100, 1, 1, 500, 500); // view [450,550]^2
            assertAll(
                () -> assertTrue(c.isVisible(400, 400, 460, 460, 0, 0), "overlapping corner"),
                () -> assertTrue(c.isVisible(550, 500, 600, 510, 0, 0), "touching the right edge"),
                () -> assertFalse(c.isVisible(551, 500, 600, 510, 0, 0), "one pixel right of the view"),
                () -> assertFalse(c.isVisible(400, 500, 449, 510, 0, 0), "one pixel left of the view"),
                () -> assertFalse(c.isVisible(500, 551, 510, 600, 0, 0), "one pixel above the view"),
                () -> assertFalse(c.isVisible(500, 400, 510, 449, 0, 0), "one pixel below the view"),
                () -> assertTrue(c.isVisible(500, 400, 510, 449, 0, 1), "a margin of one pixel brings it in"),
                () -> assertTrue(c.isVisible(551, 500, 600, 510, 0, 1), "margin applies on the right too"));
        }

        @Test
        void visibilityUsesScaleAndOffset() {
            // pixel ratio 2, zoom 1 (scale 2), centre 25 in a 1000 px wrapping world, view 200 px = 100 world px:
            // world [0,75] in copy 0 (k=1) and [975,1000] in copy -1 (k=0)
            Camera c = camera(1000, 1000, true, 200, 200, 2, 1, 25, 500);
            assertAll(
                () -> assertEquals(-1000.0, c.offsetX(0), "copy -1 is first"),
                () -> assertTrue(c.isVisible(74, 500, 120, 510, 1, 0), "x 74 is inside copy 0's view"),
                () -> assertFalse(c.isVisible(76, 500, 120, 510, 1, 0), "x 76 is past the right edge at scale 2"),
                () -> assertTrue(c.isVisible(980, 500, 999, 510, 0, 0), "x 980 is visible in copy -1"),
                () -> assertFalse(c.isVisible(900, 500, 974, 510, 0, 0), "x 974 is left of the view in copy -1"),
                () -> assertTrue(c.isVisible(10, 549, 20, 600, 1, 0), "y 549..600 overlaps the top (550)"),
                () -> assertFalse(c.isVisible(10, 551, 20, 600, 1, 0), "y from 551 is above the view"),
                () -> assertFalse(c.isVisible(10, 400, 20, 449, 1, 0), "y up to 449 is below the view"),
                () -> assertTrue(c.isVisible(10, 400, 20, 450, 1, 0), "y up to 450 touches the bottom"));
        }

        @Test
        void verticalCopiesUseTheYOffset() {
            // centre y 25, view 100 px at scale 1: [-25, 75] spans the y seam
            Camera c = camera(1000, 1000, true, 100, 100, 1, 1, 500, 25);
            assertAll(
                () -> assertEquals(-1000.0, c.offsetY(0), "copy below the seam first"),
                () -> assertEquals(15f, c.screenY(990, 0), EPS, "y 990 in the copy below: 990 - 1000 - 25 + 50 = 15"),
                () -> assertTrue(c.isVisible(500, 990, 510, 999, 0, 0), "and is visible there"),
                () -> assertFalse(c.isVisible(500, 990, 510, 999, 1, 0), "but not in the world copy"),
                () -> assertTrue(c.isVisible(500, 70, 510, 80, 1, 0), "y 70 is visible in the world copy"));
        }

        @Test
        void rectanglesExactlyOnTheViewEdgesAreVisible() {
            Camera c = camera(10000, 10000, false, 100, 100, 1, 1, 500, 500); // view [450,550]^2
            assertAll(
                () -> assertTrue(c.isVisible(400, 500, 450, 510, 0, 0), "right edge on the left view edge"),
                () -> assertTrue(c.isVisible(500, 550, 510, 600, 0, 0), "bottom edge on the top view edge"));
        }

        @Test
        void visibilityUsesTheCopyOffset() {
            Camera c = camera(1000, 1000, true, 200, 200, 1, 1, 50, 500);
            assertAll(
                () -> assertTrue(c.isVisible(960, 500, 970, 510, 0, 0), "x=960 is visible in the left copy"),
                () -> assertFalse(c.isVisible(960, 500, 970, 510, 1, 0), "but not in the world itself"));
        }
    }

    @Test
    void accessorsReportSettings() {
        Camera c = camera(300, 200, true, 640, 480, 2, 1.5, 10, 20);
        assertAll(
            () -> assertEquals(300.0, c.worldWidth(), "world width"),
            () -> assertEquals(200.0, c.worldHeight(), "world height"),
            () -> assertTrue(c.wraps(), "wraps"),
            () -> assertFalse(camera(1, 1, false, 1, 1, 1, 1, 0, 0).wraps(), "does not wrap when told not to"),
            () -> assertEquals(2.0, c.pixelRatio(), "pixel ratio"),
            () -> assertEquals(1.5, c.zoom(), "zoom"),
            () -> assertEquals(640, c.viewWidth(), "view width"),
            () -> assertEquals(480, c.viewHeight(), "view height"));
    }
}
