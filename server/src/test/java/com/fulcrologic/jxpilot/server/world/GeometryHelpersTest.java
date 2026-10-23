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
import com.fulcrologic.jxpilot.common.math.ShipShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Click (click.h / walls.h DOUBLE_TO_INT), Trig (const.h tsin/tcos) and ShipGeom (shipshape.c Rotate_point). */
class GeometryHelpersTest {
    /** Tolerance for table trigonometry: StrictMath results, compared with exact closed forms. */
    private static final double EPS = 1e-12;

    @Nested
    @DisplayName("Click")
    class ClickConversions {
        @Test
        @DisplayName("constants and conversions follow click.h")
        void conversions() {
            assertAll(
                () -> assertEquals(64, Click.CLICK, "CLICK is 1 << CLICK_SHIFT = 64 clicks per pixel"),
                () -> assertEquals(35 * 64, Click.BLOCK_CLICKS, "BLOCK_CLICKS is BLOCK_SZ << CLICK_SHIFT"),
                () -> assertEquals(127, Click.fromPixels(1.99), "FLOAT_TO_CLICK truncates 127.36 down to 127"),
                () -> assertEquals(-127, Click.fromPixels(-1.99), "FLOAT_TO_CLICK truncates -127.36 toward zero"),
                () -> assertEquals(1, Click.toPixel(127), "CLICK_TO_PIXEL shifts 127 down to pixel 1"),
                () -> assertEquals(-1, Click.toPixel(-1), "CLICK_TO_PIXEL is an arithmetic shift: -1 click is pixel -1"),
                () -> assertEquals(-2, Click.toPixel(-65), "CLICK_TO_PIXEL floors -65 clicks to pixel -2"),
                () -> assertEquals(1.515625, Click.toFloat(97), "CLICK_TO_FLOAT divides by 64"),
                () -> assertEquals(-0.015625, Click.toFloat(-1), "CLICK_TO_FLOAT keeps the sign"));
        }

        @Test
        @DisplayName("roundHalfAway is DOUBLE_TO_INT: halves round away from zero")
        void rounding() {
            assertAll(
                () -> assertEquals(3, Click.roundHalfAway(2.5), "2.5 rounds up to 3"),
                () -> assertEquals(2, Click.roundHalfAway(2.49), "2.49 rounds down to 2"),
                () -> assertEquals(-3, Click.roundHalfAway(-2.5), "-2.5 rounds away from zero to -3"),
                () -> assertEquals(-2, Click.roundHalfAway(-2.49), "-2.49 rounds toward zero to -2"),
                () -> assertEquals(0, Click.roundHalfAway(-0.0), "-0 rounds to 0"),
                () -> assertEquals(0, Click.roundHalfAway(0.49), "0.49 rounds to 0"));
        }
    }

    @Nested
    @DisplayName("Trig")
    class TrigTables {
        @Test
        @DisplayName("tables hold the exact quarter values and wrap modulo RES")
        void identities() {
            boolean cosIsShiftedSin = true;
            boolean pythagoras = true;
            for (int d = -Const.RES; d < 2 * Const.RES; d++) {
                cosIsShiftedSin &= Trig.tcos(d) == Trig.tsin(d + Const.RES / 4);
                double s = Trig.tsin(d);
                double c = Trig.tcos(d);
                pythagoras &= Math.abs(s * s + c * c - 1) < EPS;
            }
            boolean cosOk = cosIsShiftedSin;
            boolean pyOk = pythagoras;
            assertAll(
                () -> assertEquals(0.0, Trig.tsin(0), "sin(0) = 0"),
                () -> assertEquals(1.0, Trig.tsin(32), "sin(RES/4) = 1"),
                () -> assertEquals(-1.0, Trig.tsin(96), "sin(3RES/4) = -1"),
                () -> assertEquals(1.0, Trig.tcos(0), "cos(0) = 1"),
                () -> assertEquals(-1.0, Trig.tcos(64), "cos(RES/2) = -1"),
                () -> assertEquals(Math.sqrt(0.5), Trig.tsin(16), EPS, "sin(RES/8) = sqrt(1/2)"),
                () -> assertEquals(StrictMath.sin(5 * 2.0 * Math.PI / 128), Trig.tsin(5), "entry i is StrictMath.sin(2 pi i / RES)"),
                () -> assertEquals(Trig.tsin(96), Trig.tsin(-32), "a negative heading is taken modulo RES"),
                () -> assertEquals(Trig.tsin(32), Trig.tsin(160), "a heading above RES is taken modulo RES"),
                () -> assertEquals(Trig.tcos(1), Trig.tcos(129), "tcos also takes the heading modulo RES"),
                () -> assertEquals(true, cosOk, "tcos(d) == tsin(d + RES/4) for every heading"),
                () -> assertEquals(true, pyOk, "sin^2 + cos^2 = 1 for every heading"),
                () -> assertEquals(127, Trig.mod(-1), "MOD2(-1, RES) is RES-1"),
                () -> assertEquals(2, Trig.mod(130), "MOD2(130, RES) is 2"),
                () -> assertEquals(0, Trig.mod(128), "MOD2(RES, RES) is 0"));
        }
    }

    @Nested
    @DisplayName("ShipGeom")
    class ShipGeometry {
        @Test
        @DisplayName("the default ship is rotated to every heading as Rotate_point")
        void defaultShipRotation() {
            ShipGeom g = ShipGeom.defaultShip();
            double r = Math.sqrt(0.5);
            assertAll(
                () -> assertEquals(3, g.numPoints, "the default ship has 3 hull points"),
                () -> assertEquals(ShipShape.defaultShip(), g.shape, "it wraps the default shape"),
                () -> assertEquals(15.0, g.ptX[0][0], "heading 0 keeps the nose at (15, 0): x"),
                () -> assertEquals(0.0, g.ptY[0][0], "heading 0 keeps the nose at (15, 0): y"),
                () -> assertEquals(0.0, g.ptX[0][32], EPS, "heading up puts the nose at x 0"),
                () -> assertEquals(15.0, g.ptY[0][32], EPS, "heading up puts the nose at y 15"),
                () -> assertEquals(15 * r, g.ptX[0][16], EPS, "heading 45 degrees: nose x = 15 cos 45"),
                () -> assertEquals(15 * r, g.ptY[0][16], EPS, "heading 45 degrees: nose y = 15 sin 45"),
                () -> assertEquals(-8.0, g.ptX[1][32], EPS, "(-9, 8) rotated by 90 degrees: x = -y = -8"),
                () -> assertEquals(-9.0, g.ptY[1][32], EPS, "(-9, 8) rotated by 90 degrees: y = x = -9"),
                () -> assertEquals(9.0, g.ptX[2][64], EPS, "(-9, -8) rotated by 180 degrees: x = 9"),
                () -> assertEquals(8.0, g.ptY[2][64], EPS, "(-9, -8) rotated by 180 degrees: y = 8"),
                () -> assertEquals(9.0, g.engineX[64], EPS, "the engine (-9, 0) rotated by 180 degrees is at x 9"),
                () -> assertEquals(-15.0, g.gunY[96], EPS, "the gun (15, 0) at heading down is at y -15"),
                () -> assertEquals(0.0, g.gunX[96], EPS, "the gun (15, 0) at heading down is at x 0"));
        }

        @Test
        @DisplayName("rotate uses (x cos - y sin, x sin + y cos) with the table values")
        void rotateFormula() {
            double[] ox = new double[Const.RES];
            double[] oy = new double[Const.RES];
            ShipGeom.rotate(3, -7, ox, oy);
            assertAll(
                () -> assertEquals(Trig.tcos(5) * 3 - Trig.tsin(5) * -7, ox[5], "x is x cos - y sin"),
                () -> assertEquals(Trig.tsin(5) * 3 + Trig.tcos(5) * -7, oy[5], "y is x sin + y cos"),
                () -> assertEquals(Trig.tcos(127) * 3 - Trig.tsin(127) * -7, ox[127], "the last heading is filled"));
        }

        @Test
        @DisplayName("a custom shape is rotated point by point")
        void customShape() {
            ShipShape s = ShipShape.parse("(SH: 10,0 0,10 -10,0 0,-10)(EN: -10,0)(MG: 10,0)");
            ShipGeom g = new ShipGeom(s);
            assertAll(
                () -> assertEquals(4, g.numPoints, "all 4 points are kept"),
                () -> assertEquals(-10.0, g.ptX[1][32], EPS, "(0, 10) at 90 degrees is (-10, 0)"),
                () -> assertEquals(10.0, g.ptY[3][64], EPS, "(0, -10) at 180 degrees is (0, 10)"),
                () -> assertEquals(-10.0, g.engineX[0], EPS, "the engine at heading 0 is unrotated"),
                () -> assertEquals(10.0, g.gunX[0], EPS, "the main gun at heading 0 is unrotated"),
                () -> assertEquals(10.0, g.gunY[32], EPS, "the main gun at heading up is at y 10"));
        }
    }
}
