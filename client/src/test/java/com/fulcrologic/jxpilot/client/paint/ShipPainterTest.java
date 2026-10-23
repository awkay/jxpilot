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
import com.fulcrologic.jxpilot.common.math.ShipShape;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.paint.PaintTests.*;
import static org.junit.jupiter.api.Assertions.*;

class ShipPainterTest {
    static final Palette P = Palette.xpilotDefault();
    static final ShipShape SHIP = ShipShape.defaultShip(); // (15,0) (-9,8) (-9,-8), lights (-9,8)/(-9,-8)
    static final int WHITE = P.color(Palette.WHITE);
    static final int BLUE = P.color(Palette.BLUE);
    static final int RED = P.color(Palette.RED);
    static final int GREEN = P.color(4);
    static final ShipPainter SP = new ShipPainter(P);

    @Nested
    class Colour {
        @Test
        void friendsAreBlueSelfIsNeverBlueAndRoundDelayIsRed() {
            assertAll(
                () -> assertEquals(Palette.WHITE, ShipPainter.shipColor(false, false, false), "an enemy is white"),
                () -> assertEquals(Palette.BLUE, ShipPainter.shipColor(true, false, false), "a teammate is blue"),
                () -> assertEquals(Palette.WHITE, ShipPainter.shipColor(true, true, false), "self is white even if friendly"),
                () -> assertEquals(Palette.RED, ShipPainter.shipColor(false, false, true), "red before a round"),
                () -> assertEquals(Palette.BLUE, ShipPainter.shipColor(true, false, true), "friendly wins over round red"),
                () -> assertEquals(Palette.RED, ShipPainter.shipColor(true, true, true), "self before a round is red"));
        }

        @Test
        void roundDelayShowsInTheFirstHalfOfEachSecond() {
            assertAll(
                () -> assertTrue(ShipPainter.showRoundDelay(28, 14), "28 % 14 = 0 < 7"),
                () -> assertTrue(ShipPainter.showRoundDelay(34, 14), "34 % 14 = 6 < 7"),
                () -> assertFalse(ShipPainter.showRoundDelay(35, 14), "35 % 14 = 7: second half"),
                () -> assertFalse(ShipPainter.showRoundDelay(0, 14), "no round delay"),
                () -> assertFalse(ShipPainter.showRoundDelay(-14, 14), "negative: none"));
        }
    }

    @Nested
    class Hull {
        @Test
        void solidHullIsTheRotatedOutlineClosed() {
            VertexBatch b = batch();
            SP.ship(b, SHIP, 100, 200, Math.PI / 2, Palette.BLUE, 0, 0, 1);
            assertAll(
                () -> assertEquals(List.of("100,215-92,191", "92,191-108,191", "108,191-100,215"), segs(b),
                    "nose (15,0) turned to (0,15); (-9,8) to (-8,-9); (-9,-8) to (8,-9); back to the nose"),
                () -> assertEquals(List.of(BLUE, BLUE, BLUE), segColors(b), "in the ship colour"),
                () -> assertEquals(0, b.vertexCount(), "no fills"));
        }

        @Test
        void cloakedOrPhasingHullIsDashedWithTheRotatingOffset() {
            VertexBatch cloaked = batch();
            SP.ship(cloaked, SHIP, 0, 0, 0, Palette.WHITE, ShipPainter.CLOAKED, 0, 1);
            VertexBatch phased = batch();
            SP.ship(phased, SHIP, 0, 0, 0, Palette.WHITE, ShipPainter.PHASING, 0, 1);
            VertexBatch later = batch();
            SP.ship(later, SHIP, 0, 0, 0, Palette.WHITE, ShipPainter.CLOAKED, 2, 1);
            // loops 0: offset 12 = 0 mod 12; edge (15,0)->(-9,8) is 25.3 px: dashes at 0-8, 12-20, 24-25.3
            assertAll(
                () -> assertEquals("15,0-7.411,2.53", segs(cloaked).get(0), "first 8 px dash along the first edge"),
                () -> assertEquals("3.616,3.795-(-3.974),6.325".replace("(", "").replace(")", ""), segs(cloaked).get(1),
                    "then 4 px gap and the next dash"),
                () -> assertEquals(segs(cloaked), segs(phased), "phasing looks the same"),
                () -> assertEquals("13.103,0.632-5.513,3.162", segs(later).get(0),
                    "loops 2: offset 10 is 2 px into the gap, so the first dash starts 2 px along"),
                () -> assertTrue(segs(cloaked).size() > 3, "more pieces than a solid hull"));
        }

        @Test
        void dashOffsetCountsDownFromTwelve() {
            assertAll(
                () -> assertEquals(12, ShipPainter.dashOffset(0), "loops 0"),
                () -> assertEquals(11, ShipPainter.dashOffset(1), "loops 1"),
                () -> assertEquals(1, ShipPainter.dashOffset(11), "loops 11"),
                () -> assertEquals(12, ShipPainter.dashOffset(24), "loops 24 wraps"));
        }

        @Test
        void lockedFillIsATriangleFanFromTheShipCentre() {
            VertexBatch b = batch();
            SP.lockedFill(b, SHIP, 10, 20, 0, Palette.RED);
            assertAll(
                () -> assertEquals(List.of("10,20 25,20 1,28", "10,20 1,28 1,12", "10,20 1,12 25,20"), tris(b),
                    "one triangle per edge from the centre"),
                () -> assertEquals(RED, b.color(0), "in the given colour"));
        }
    }

    @Nested
    class Shields {
        static int circles(VertexBatch b) {
            return b.segmentCount();
        }

        /** Distance of segment i's start from the origin. */
        static double radius(VertexBatch b, int i) {
            return Math.hypot(b.segX1(i), b.segY1(i));
        }

        /** Segment starts [from, to) lie on chords of a 32-gon of radius r: between r cos(pi/32) and r. */
        static void assertOnCircle(VertexBatch b, int from, int to, double r, String msg) {
            for (int i = from; i < to; i++) {
                double d = radius(b, i);
                assertTrue(d <= r + 1e-3 && d >= r * Math.cos(Math.PI / 32) - 1e-3, msg + ": piece " + i + " at " + d);
            }
        }

        static List<Integer> colors(VertexBatch b) {
            return segColors(b).stream().distinct().toList();
        }

        @Test
        void shieldAloneIsADashedCircleOfHalfTheShieldRadiusInTheShipColour() {
            VertexBatch b = batch();
            SP.shields(b, SHIP, 0, 0, Palette.WHITE, ShipPainter.SHIELD, 0, 1);
            assertAll(
                () -> assertEquals(34, SHIP.shieldRadius, "precondition: default shield radius"),
                () -> assertEquals("17,0", segs(b).get(0).split("-")[0], "radius 17, starting at 3 o'clock"),
                () -> assertEquals(List.of(WHITE), colors(b), "ship colour"),
                () -> assertTrue(b.segmentCount() > 8, "dashed into many pieces"));
        }

        @Test
        void deflectorAloneIsTheOuterCircleRedOrBlueByLoops() {
            VertexBatch red = batch();
            SP.shields(red, SHIP, 0, 0, Palette.WHITE, ShipPainter.DEFLECTOR, 2, 1);
            VertexBatch blue = batch();
            SP.shields(blue, SHIP, 0, 0, Palette.WHITE, ShipPainter.DEFLECTOR, 1, 1);
            assertAll(
                () -> assertOnCircle(red, 0, red.segmentCount(), 19, "outer radius (34 + 4) / 2"),
                () -> assertEquals(List.of(RED), colors(red), "loops & 2: red"),
                () -> assertEquals(List.of(BLUE), colors(blue), "otherwise blue"));
        }

        @Test
        void emergencyShieldSwapsColoursWithADeflectorOrDoublesTheShield() {
            VertexBatch withDefl = batch();
            SP.shields(withDefl, SHIP, 0, 0, Palette.WHITE,
                ShipPainter.SHIELD | ShipPainter.EMERGENCY_SHIELD | ShipPainter.DEFLECTOR, 2, 1);
            VertexBatch noDefl = batch();
            SP.shields(noDefl, SHIP, 0, 0, Palette.BLUE, ShipPainter.SHIELD | ShipPainter.EMERGENCY_SHIELD, 0, 1);
            VertexBatch noShield = batch();
            SP.shields(noShield, SHIP, 0, 0, Palette.WHITE, ShipPainter.EMERGENCY_SHIELD, 0, 1);
            assertAll(
                () -> assertEquals(WHITE, withDefl.segColor(0), "outer circle (drawn first) takes the ship colour"),
                () -> assertOnCircle(withDefl, 0, 1, 19, "and is the outer one"),
                () -> assertOnCircle(withDefl, withDefl.segmentCount() - 1, withDefl.segmentCount(), 17,
                    "the last piece is on the inner one"),
                () -> assertEquals(RED, withDefl.segColor(withDefl.segmentCount() - 1), "inner takes the deflector red"),
                () -> assertEquals(List.of(BLUE), colors(noDefl), "no deflector: both circles in the ship colour"),
                () -> assertEquals("19,0", segs(noDefl).get(0).split("-")[0], "outer circle drawn"),
                () -> assertEquals(0, noShield.segmentCount(), "emergency shield without shield draws nothing"));
        }

        @Test
        void shipDrawsItsShieldsAfterTheHull() {
            VertexBatch b = batch();
            SP.ship(b, SHIP, 0, 0, 0, Palette.WHITE, ShipPainter.SHIELD, 0, 1);
            assertAll(
                () -> assertEquals("15,0-(-9),8".replace("(", "").replace(")", ""), segs(b).get(0), "hull first"),
                () -> assertEquals("17,0", segs(b).get(3).split("-")[0], "then the shield"));
        }

        @Test
        void dashLengthsScaleWithPx() {
            VertexBatch b = batch();
            SP.shields(b, SHIP, 0, 0, Palette.WHITE, ShipPainter.SHIELD, 0, 0.5f);
            VertexBatch c = batch();
            SP.shields(c, SHIP, 0, 0, Palette.WHITE, ShipPainter.SHIELD, 0, 1);
            assertTrue(b.segmentCount() > c.segmentCount(), "zoomed in (px 0.5) the same circle has more dashes");
        }
    }

    @Nested
    class Lights {
        @Test
        void leftLightsFlashRedWhenLoopsPlusIdIsZeroMod16() {
            VertexBatch b = batch();
            SP.markingLights(b, SHIP, 100, 100, 0, 3, 13, 1);
            assertAll(
                () -> assertEquals(List.of("92,107/6"), points(b), "left light (-9,8): square centred 1 px right and down"),
                () -> assertEquals(List.of("83,108-99,108", "91,100-91,116"), segs(b), "cross of 8 px arms"),
                () -> assertEquals(RED, b.pointColor(0), "red"),
                () -> assertEquals(List.of(VertexBatch.POINTS, VertexBatch.LINES), modes(b), "points then lines"));
        }

        @Test
        void rightLightsFlashInColourFourTwoFramesLater() {
            VertexBatch b = batch();
            SP.markingLights(b, SHIP, 0, 0, Math.PI, 0, 18, 2);
            assertAll(
                () -> assertEquals(List.of("11,6/6"), points(b),
                    "right light (-9,-8) turned 180 degrees is (9,8); +2 world px right, -2 down at px 2"),
                () -> assertEquals(GREEN, b.pointColor(0), "colour 4"),
                () -> assertEquals("-7,8-25,8", segs(b).get(0), "arms of 8 px are 16 world units at px 2"));
        }

        @Test
        void otherFramesDrawNoLights() {
            VertexBatch b = batch();
            SP.markingLights(b, SHIP, 0, 0, 0, 0, 1, 1);
            SP.markingLights(b, SHIP, 0, 0, 0, 0, 3, 1);
            SP.markingLights(b, SHIP, 0, 0, 0, 0, 15, 1);
            assertEquals(0, b.commandCount(), "phases 1, 3 and 15 are dark");
        }
    }

    @Nested
    class Labels {
        final TextWriter text = new TextWriter(Fixtures.font());

        @Test
        void nameIsCentredSixteenPixelsAndAnAscentBelowTheShip() {
            VertexBatch b = batch();
            SP.name(b, text, 200, 300, "abc", 2);
            assertAll(
                () -> assertEquals(191f, b.x(0), "pen 200 - 21/2 (int 10) = 190, glyph xoff 1"),
                () -> assertEquals(266f, b.y(2), "baseline 300 - 16 * 2 - 10 = 258; the glyph top is 8 above"),
                () -> assertEquals(WHITE, b.color(0), "white"));
        }

        @Test
        void roundDelayIsTheSecondsCentredOnTheShip() {
            VertexBatch b = batch();
            SP.roundDelay(b, text, 100, 100, 7);
            assertAll(
                () -> assertEquals(98f, b.x(0), "pen 100 - 7/2 (int 3) = 97, + xoff 1"),
                () -> assertEquals(103f, b.y(2), "baseline 100 - 10/2 = 95; top 8 above"),
                () -> assertEquals(1, b.vertexCount() / 6, "one digit"));
        }
    }

    @Test
    void rightLightCrossesAreAtTheRightLights() {
        VertexBatch b = batch();
        SP.markingLightCrosses(b, SHIP, 0, 0, 0, 0, 2, 1);
        assertAll(
            () -> assertEquals(List.of("-17,-8--1,-8", "-9,-16--9,0"), segs(b), "right light (-9,-8)"),
            () -> assertEquals(GREEN, b.segColor(0), "colour 4"));
    }
}
