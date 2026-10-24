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

import com.fulcrologic.jxpilot.client.Fixtures;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.Item;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.paint.PaintTests.*;
import static org.junit.jupiter.api.Assertions.*;

class ObjectPainterTest {
    static final Palette P = Palette.xpilotDefault();
    static final int WHITE = P.color(Palette.WHITE);
    static final int BLUE = P.color(Palette.BLUE);
    static final int RED = P.color(Palette.RED);
    static final ObjectPainter OP = new ObjectPainter(P);
    static final TextWriter TEXT = new TextWriter(Fixtures.font());
    /** UVs are stored as 16-bit normalized values. */
    static final float UV = 1f / 65535;

    @Nested
    class Shots {
        @Test
        void shotIsAThreePixelWhiteOrBlueSquare() {
            VertexBatch b = batch();
            OP.shot(b, 10, 20, Palette.WHITE, 1);
            OP.shot(b, 10, 20, Palette.BLUE, 1);
            OP.shot(b, 10, 20, Palette.RED, 1);
            assertAll(
                () -> assertEquals(List.of("10,20/3", "10,20/3", "10,20/3"), points(b), "3-pixel squares centred on the shot"),
                () -> assertEquals(WHITE, b.pointColor(0), "white"),
                () -> assertEquals(BLUE, b.pointColor(1), "blue"),
                () -> assertEquals(WHITE, b.pointColor(2), "any other colour draws white"));
        }

        @Test
        void shotSquaresScaleByWinscaleFromTheirWorldCorner() {
            VertexBatch b = batch();
            OP.shot(b, 10, 20, Palette.WHITE, 2);
            OP.shot(b, 10, 20, Palette.WHITE, 0.25);
            OP.shot(b, 10, 20, Palette.WHITE, 4);
            assertEquals(List.of("10.25,19.75/6", "9,21/1", "10.375,19.625/12"), points(b),
                "corner at world (x-1, y+1); side WINSCALE(3) = 6, 1 (at least one pixel), 12; centre the corner "
                    + "pixel plus (side-1)/2 pixels: 2.5 px = 1.25, 0, 5.5 px = 1.375 world");
        }

        @Test
        void teamShotIsAnXOrAPlusWithOnePixelArms() {
            VertexBatch x = batch();
            OP.teamShot(x, 10, 20, Palette.BLUE, true, 1);
            VertexBatch plus = batch();
            OP.teamShot(plus, 10, 20, Palette.RED, false, 0.5);
            VertexBatch near = batch();
            OP.teamShot(near, 10, 20, Palette.RED, false, 2.5);
            assertAll(
                () -> assertEquals(List.of("9,21-11,19", "11,21-9,19"), segs(x), "diagonal: screen (x-1,y-1)-(x+1,y+1) and (x+1,y-1)-(x-1,y+1)"),
                () -> assertEquals(BLUE, x.segColor(0), "in the given colour"),
                () -> assertEquals(List.of("8,20-12,20", "10,22-10,18"), segs(plus),
                    "plus; zoom 0.5: WINSCALE(1) is still 1 pixel = 2 world units"),
                () -> assertEquals(List.of("8.8,20-11.2,20", "10,21.2-10,18.8"), segs(near),
                    "zoom 2.5: WINSCALE(1) = 3 pixels = 1.2 world units (rounded, not 2.5 pixels)"),
                () -> assertEquals(RED, plus.segColor(1), "red (nuclear blink)"));
        }

        @Test
        void missileIsAFourPixelWhiteLineBehindTheHeading() {
            VertexBatch b = batch();
            OP.missile(b, 100, 100, 15, 0);
            OP.missile(b, 100, 100, 20, 32);
            assertAll(
                () -> assertEquals(List.of("100,100-85,100", "100,100-100,80"), segs(b),
                    "heading right: tail to the left; heading up: tail below"),
                () -> assertEquals(4f, b.segWidth(0), "4 pixels wide"),
                () -> assertEquals(VertexBatch.CAP_BUTT, b.segCap(0), "butt caps (C CapButt): ends exactly at its ends"),
                () -> assertEquals(WHITE, b.segColor(1), "white"),
                () -> assertEquals(1f, b.lineWidth(), "line width restored"),
                () -> assertEquals(VertexBatch.CAP_SQUARE, b.lineCap(), "square caps restored"));
        }

        @Test
        void laserIsAThreePixelLineForwardInItsColour() {
            VertexBatch b = batch();
            OP.laser(b, 0, 0, 50, 32, Palette.BLUE);
            OP.laser(b, 0, 0, 50, 64, Palette.RED);
            OP.laser(b, 0, 0, 50, 0, 4);
            OP.laser(b, 0, 0, 50, 0, -1);
            assertAll(
                () -> assertEquals(List.of("0,0-0,50", "0,0-(-50),0".replace("(", "").replace(")", ""), "0,0-50,0", "0,0-50,0"), segs(b),
                    "forward along the heading: up, left, right, right"),
                () -> assertEquals(3f, b.segWidth(0), "3 pixels wide"),
                () -> assertEquals(VertexBatch.CAP_BUTT, b.segCap(3), "butt caps"),
                () -> assertEquals(VertexBatch.CAP_SQUARE, b.lineCap(), "square caps restored"),
                () -> assertEquals(BLUE, b.segColor(0), "blue"),
                () -> assertEquals(RED, b.segColor(1), "red (index 3)"),
                () -> assertEquals(WHITE, b.segColor(2), "index 4 is past C's 4 colours: white"),
                () -> assertEquals(WHITE, b.segColor(3), "negative: white"),
                () -> assertEquals(1f, b.lineWidth(), "line width restored"));
        }
    }

    @Nested
    class Mines {
        @Test
        void enemyMineIsABlueBlockUnderAWhiteOutline() {
            VertexBatch b = batch();
            OP.mine(b, 0, 0, false, 1);
            List<String> s = segs(b);
            assertAll(
                () -> assertEquals(List.of("-7.5,-2.5 7.5,-2.5 7.5,2.5", "-7.5,-2.5 7.5,2.5 -7.5,2.5"), tris(b),
                    "15x5 pixels centred on the mine"),
                () -> assertEquals(BLUE, b.color(0), "blue"),
                () -> assertEquals(List.of(VertexBatch.TRIANGLES, VertexBatch.LINES), modes(b), "fill first"),
                () -> assertEquals(20, s.size(), "21 points: 20 segments"),
                () -> assertEquals("-8,1--7,1", s.get(0), "from screen (-8,-1) one pixel right"),
                () -> assertEquals("-7,1--7,2", s.get(1), "then one pixel up on screen, i.e. +1 in world y"),
                () -> assertEquals("3,3-3,2", s.get(5), "the right shoulder"),
                () -> assertEquals("8,1-8,-1", s.get(9), "the 2-pixel step down the right end"),
                () -> assertEquals("-8,-1--8,1", s.get(19), "the last move closes the outline at the start"),
                () -> assertEquals(WHITE, b.segColor(0), "outline white"));
        }

        @Test
        void teamMineHasNoBlueBlock() {
            VertexBatch b = batch();
            OP.mine(b, 5, 5, true, 1);
            assertAll(
                () -> assertEquals(0, b.vertexCount(), "no fill"),
                () -> assertEquals(20, b.segmentCount(), "just the outline"),
                () -> assertEquals("-3,6--2,6", segs(b).get(0), "offset by the mine position"));
        }

        @Test
        void mineOutlineMovesScaleByWinscaleFromTheWorldStart() {
            VertexBatch half = batch();
            OP.mineOutline(half, 0, 0, 0.5);
            VertexBatch twice = batch();
            OP.mineOutline(twice, 0, 0, 2);
            assertAll(
                () -> assertEquals("-8,1--6,1", segs(half).get(0), "zoom 0.5: from world (-8, 1); WINSCALE(1) = 1 px = 2 units"),
                () -> assertEquals("-6,1--6,3", segs(half).get(1), "then 1 px up"),
                () -> assertEquals("-6,3--2,3", segs(half).get(2), "WINSCALE(4) = 2 px = 4 units (not 8)"),
                () -> assertEquals("-8,1--7,1", segs(twice).get(0), "zoom 2: WINSCALE(1) = 2 px = 1 unit"),
                () -> assertEquals("-7,2--3,2", segs(twice).get(2), "WINSCALE(4) = 8 px = 4 units"));
        }

        @Test
        void mineNameIsCentredUnderTheMine() {
            VertexBatch b = batch();
            OP.mineName(b, TEXT, 100, 200, "ab", 2, 1);
            VertexBatch zoomed = batch();
            OP.mineName(zoomed, TEXT, 100, 200, "ab", 2, 2);
            assertAll(
                () -> assertEquals(94f, b.x(0), "pen 100 - 14/2 = 93, xoff 1"),
                () -> assertEquals(188f, b.y(2), "baseline 200 - (4 + 1) * 2 - 10 = 180; glyph top 8 above"),
                () -> assertEquals(180f, zoomed.y(2), "zoom 2: WINSCALE(4) = 8: baseline 200 - (8 + 1) * 2 - 10 = 172"),
                () -> assertEquals(WHITE, b.color(0), "white"));
        }
    }

    @Nested
    class Balls {
        @Test
        void ballIsAWhiteCircleOfBallRadius() {
            VertexBatch b = batch();
            OP.ball(b, 50, 60);
            assertAll(
                () -> assertEquals(ObjectPainter.CIRCLE_SEGMENTS, b.segmentCount(), "24 chords"),
                () -> assertEquals("60,60-59.659,62.588", segs(b).get(0), "radius 10 from 3 o'clock"),
                () -> assertEquals("59.659,57.412-60,60", segs(b).get(23), "closing at the start"),
                () -> assertEquals(WHITE, b.segColor(0), "white"));
        }

        @Test
        void ballConnectorIsOneSolidWhiteLine() {
            VertexBatch b = batch();
            OP.ballConnector(b, 1, 2, 30, 40);
            assertAll(
                () -> assertEquals(List.of("1,2-30,40"), segs(b), "carrier to ball"),
                () -> assertEquals(WHITE, b.segColor(0), "white"));
        }
    }

    @Nested
    class Connectors {
        @Test
        void linksUseShipDashesAtTheRotatingOffset() {
            VertexBatch b = batch();
            OP.dashedLink(b, 0, 0, 30, 0, 0, 1);
            VertexBatch later = batch();
            OP.dashedLink(later, 0, 0, 30, 0, 4, 1);
            VertexBatch half = batch();
            OP.dashedLink(half, 0, 0, 30, 0, 1, 0.5);
            assertAll(
                () -> assertEquals(List.of("0,0-8,0", "12,0-20,0", "24,0-30,0"), segs(b), "loops 0: offset 12 = 0"),
                () -> assertEquals(List.of("4,0-12,0", "16,0-24,0", "28,0-30,0"), segs(later), "loops 4: offset 8 starts in the gap"),
                () -> assertEquals(List.of("0,0-8,0", "12,0-20,0", "24,0-30,0"), segs(half),
                    "zoom 0.5, loops 1: dashes WINSCALE 4/2 px, offset WINSCALE(11) = 6 px = one whole period"),
                () -> assertEquals(WHITE, b.segColor(0), "white"));
        }

        @Test
        void tractorBeamUsesThreeOnNineOffFromOffsetZero() {
            VertexBatch b = batch();
            OP.tractorBeam(b, 0, 0, 30, 0, 1);
            VertexBatch half = batch();
            OP.tractorBeam(half, 0, 0, 30, 0, 0.5);
            assertAll(
                () -> assertEquals(List.of("0,0-3,0", "12,0-15,0", "24,0-27,0"), segs(b), "3 on, 9 off, from the start"),
                () -> assertEquals(List.of("0,0-4,0", "14,0-18,0", "28,0-30,0"), segs(half),
                    "zoom 0.5: WINSCALE(3) = 2 px = 4 units on, WINSCALE(9) = 5 px = 10 units off"));
        }

        @Test
        void ballConnectingIsShipDashesFromOffsetZero() {
            VertexBatch b = batch();
            OP.connector(b, 0, 0, 30, 0, 1);
            VertexBatch half = batch();
            OP.connector(half, 0, 0, 30, 0, 0.5);
            assertAll(
                () -> assertEquals(List.of("0,0-8,0", "12,0-20,0", "24,0-30,0"), segs(half),
                    "zoom 0.5: WINSCALE 4 px on, 2 off at 2 units per pixel: the same world pattern"),
                () -> assertEquals(List.of("0,0-8,0", "12,0-20,0", "24,0-30,0"), segs(b), "8 on, 4 off, from the start"),
                () -> assertEquals(WHITE, b.segColor(0), "white"));
        }
    }

    @Nested
    class Debris {
        @Test
        void wreckIsItsShapeRotatedAndScaledBySizeOver256() {
            VertexBatch full = batch();
            OP.wreck(full, 0, 0, true, 0, 0, 256);
            VertexBatch half = batch();
            OP.wreck(half, 100, 100, false, 3, 0, 128);
            VertexBatch turned = batch();
            OP.wreck(turned, 0, 0, true, 0, 32, 256);
            assertAll(
                () -> assertEquals(12, full.segmentCount(), "12-point closed outline"),
                () -> assertEquals("-9,6--2,8", segs(full).get(0), "shape 0 unscaled at size 256"),
                () -> assertEquals("-5,2--9,6", segs(full).get(11), "the last point joins the first"),
                () -> assertEquals(WHITE, full.segColor(0), "deadly: white"),
                () -> assertEquals("95,103-99,104", segs(half).get(0),
                    "type 3 is shape 0; size 128: (int)(-9*128) >> 8 = -5 (floor), 6 -> 3, -2 -> -1, 8 -> 4"),
                () -> assertEquals(RED, half.segColor(0), "harmless: red"),
                () -> assertEquals("-6,-9--8,-2", segs(turned).get(0), "rotated a quarter turn: (x,y) -> (-y,x)"));
        }

        @Test
        void asteroidIsItsShapeTimesSizeTimesOnePointFour() {
            VertexBatch b = batch();
            OP.asteroid(b, 0, 0, 0, 0, 2);
            VertexBatch other = batch();
            OP.asteroid(other, 0, 0, 3, 0, 1);
            assertAll(
                () -> assertEquals("-28,0--19,16", segs(b).get(0), "(int)(-10 * 2.8) = -28; (-7, 6) -> (-19, 16)"),
                () -> assertEquals(12, b.segmentCount(), "closed 12-gon"),
                () -> assertEquals(WHITE, b.segColor(0), "white"),
                () -> assertEquals("-14,0--11,9", segs(other).get(0), "type 3 is shape 1: (-8, 7) * 1.4 -> (-11, 9)"));
        }
    }

    @Nested
    class Effects {
        @Test
        void ecmIsAWhiteCircleOfDiameterSize() {
            VertexBatch b = batch();
            OP.ecm(b, 0, 0, 20);
            assertAll(
                () -> assertEquals("10,0", segs(b).get(0).split("-")[0], "radius 10"),
                () -> assertEquals(24, b.segmentCount(), "24 chords"),
                () -> assertEquals(WHITE, b.segColor(0), "white"));
        }

        @Test
        void pausedIsABlueSquareWithABlinkingWhiteFrameAndP() {
            VertexBatch b = batch();
            OP.pausedFill(b, 0, 0);
            OP.pausedOutline(b, 0, 0);
            VertexBatch t = batch();
            OP.pausedLetter(t, TEXT, 100, 100);
            assertAll(
                () -> assertEquals("-15.5,-15.5 15.5,-15.5 15.5,15.5", tris(b).get(0), "31 pixels centred: half 15 = 3 * 35 / 7"),
                () -> assertEquals(BLUE, b.color(0), "blue"),
                () -> assertEquals(List.of("-16,-16-16,-16", "16,-16-16,16", "16,16-(-16),16".replace("(", "").replace(")", ""),
                    "-16,16--16,-16"), segs(b), "frame one pixel outside"),
                () -> assertEquals(WHITE, b.segColor(0), "white frame"),
                () -> assertEquals(98f, t.x(0), "P centred: 100 - 7/2 (int 3) + xoff 1"),
                () -> assertEquals(103f, t.y(2), "baseline 100 - 5, top 8 above"));
        }

        @Test
        void pausedBlinkIsOnWhenNotCountingOrInTheSecondHalfOfTenFrames() {
            assertAll(
                () -> assertTrue(ObjectPainter.pausedBlinkOn(0, 0), "count 0: always on"),
                () -> assertTrue(ObjectPainter.pausedBlinkOn(-3, 1), "negative count: on"),
                () -> assertFalse(ObjectPainter.pausedBlinkOn(5, 4), "counting, loops % 10 = 4: off"),
                () -> assertTrue(ObjectPainter.pausedBlinkOn(5, 5), "loops % 10 = 5: on"),
                () -> assertTrue(ObjectPainter.pausedBlinkOn(5, 19), "loops % 10 = 9: on"),
                () -> assertFalse(ObjectPainter.pausedBlinkOn(5, 20), "loops % 10 = 0: off"));
        }
    }

    @Nested
    class Items {
        @Test
        void triangleIsBlueAndPointsDown() {
            VertexBatch b = batch();
            OP.itemTriangle(b, 0, 0, 1);
            assertAll(
                () -> assertEquals(12, PaintConfig.ITEM_TRIANGLE_SIZE, "precondition: S = 5 * 16 / 7 + 1"),
                () -> assertEquals(List.of("-12,12-0,-12", "0,-12-12,12", "12,12--12,12"), segs(b),
                    "screen (-S,-S), (0,S), (S,-S): apex below"),
                () -> assertEquals(BLUE, b.segColor(0), "blue"));
        }

        @Test
        void symbolIsTheRedAtlasQuadEightLeftAndSAbove() {
            VertexBatch b = batch();
            OP.itemSymbol(b, Item.TANK.ordinal(), 0, 0, 1);
            float u0 = (768 + 7 * 16) / 2048f;
            assertAll(
                () -> assertEquals(List.of("-8,-6 8,-6 8,10", "-8,-6 8,10 -8,10"), tris(b),
                    "16 px square, top-left at screen (x - 8, y - S + 2)"),
                () -> assertEquals(u0, b.u(0), UV, "left edge at the tank's atlas column"),
                () -> assertEquals(16 / 2048f, b.v(0), UV, "bottom samples the symbol's last row"),
                () -> assertEquals(0f, b.v(2), UV, "top samples its first row"),
                () -> assertEquals(u0 + 16 / 2048f, b.u(1), UV, "16 texels wide"),
                () -> assertEquals(RED, b.color(0), "red"));
        }

        @Test
        void symbolInTheSecondAtlasRow() {
            VertexBatch b = batch();
            ObjectPainter.symbol(b, Item.ARMOR.ordinal(), 10, 20, 32, WHITE);
            assertAll(
                () -> assertEquals("10,-12 42,-12 42,20", tris(b).get(0), "side 32 hanging down from the top-left"),
                () -> assertEquals((768 + 4 * 16) / 2048f, b.u(0), UV, "armor (20) is fifth in row 2"),
                () -> assertEquals(32 / 2048f, b.v(0), UV, "rows 16..31"),
                () -> assertEquals(16 / 2048f, b.v(2), UV, "top row 16"));
        }
    }

    @Nested
    class Scaling {
        @Test
        void itemsKeepTheirPixelSizeWhileMinesScaleByWinscale() {
            VertexBatch tri = batch();
            OP.itemTriangle(tri, 0, 0, 0.5);
            VertexBatch sym = batch();
            OP.itemSymbol(sym, 0, 0, 0, 0.5);
            VertexBatch fill = batch();
            OP.mineFill(fill, 0, 0, 0.5);
            VertexBatch big = batch();
            OP.mineFill(big, 0, 0, 2);
            assertAll(
                () -> assertEquals("-24,24-0,-24", segs(tri).get(0), "triangle half-size 12 px at any zoom = 24 units at 0.5"),
                () -> assertEquals("-16,-12 16,-12 16,20", tris(sym).get(0), "symbol: 16 px = 32 units square, left x - 8 px, top y + 10 px"),
                () -> assertEquals("-8,-3 8,-3 8,3", tris(fill).get(0),
                    "zoom 0.5: corner world (-7, 2) less half a pixel; WINSCALE(15) = 8 px = 16, WINSCALE(5) = 3 px = 6 units"),
                () -> assertEquals("-7.25,-2.75 7.75,-2.75 7.75,2.25", tris(big).get(0),
                    "zoom 2: from (-7.25, 2.25), WINSCALE 30 x 10 px = 15 x 5 units"));
        }

        @Test
        void squareCentresAreTheCornerPixelPlusHalfTheScaledSideLessOne() {
            assertAll(
                () -> assertEquals(-0.5f, ObjectPainter.squareCentreX(0, -1, 2, 1), "spark at zoom 1: half a pixel left"),
                () -> assertEquals(0.5f, ObjectPainter.squareCentreY(0, -1, 2, 1), "and half a pixel above"),
                () -> assertEquals(0.5f, ObjectPainter.squareCentreX(0, -2, 6, 1), "light square at zoom 1: half a pixel right"),
                () -> assertEquals(-0.5f, ObjectPainter.squareCentreY(0, -2, 6, 1), "and half a pixel below"),
                () -> assertEquals(-0.25f, ObjectPainter.squareCentreX(0, -1, 2, 2), "spark at zoom 2: -1 + 1.5 px / 2"),
                () -> assertEquals(0.25f, ObjectPainter.squareCentreY(0, -1, 2, 2), "y mirrored"));
        }

        @Test
        void colourZeroIsAValidLaserColour() {
            VertexBatch b = batch();
            OP.laser(b, 0, 0, 10, 0, 0);
            assertEquals(P.color(Palette.BLACK), b.segColor(0), "index 0 (black) is below C's NUM_COLORS: kept");
        }

        @Test
        void asteroidsRotateByTheirRotation() {
            VertexBatch b = batch();
            OP.asteroid(b, 0, 0, 0, 32, 2);
            assertEquals("0,-28--16,-19", segs(b).get(0), "a quarter turn: (-10, 0) -> (0, -10), (-7, 6) -> (-6, -7); times 2.8");
        }
    }
}
