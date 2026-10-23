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
package com.fulcrologic.jxpilot.common.math;

import com.fulcrologic.jxpilot.common.Item;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Math utilities")
class MathTest {
    /** Tolerance for double math that is exact up to rounding. */
    static final double EPS = 1e-9;

    @Nested
    @DisplayName("XMath")
    class XMathTest {
        @Test
        @DisplayName("wrapping positions and deltas")
        void wrapping() {
            assertAll(
                () -> assertEquals(5.0, XMath.wrap(105.0, 100.0), EPS, "wraps values above the size"),
                () -> assertEquals(0.0, XMath.wrap(-200.0, 100.0), EPS, "an exact negative multiple wraps to 0"),
                () -> assertEquals(0.0, XMath.wrap(-1e-18, 100.0), EPS, "a tiny negative wraps to 0, never to size"),
                () -> assertEquals(0.0, XMath.wrap(200.0, 100.0), EPS, "an exact positive multiple wraps to 0"),
                () -> assertEquals(95.0, XMath.wrap(-5.0, 100.0), EPS, "wraps negative values"),
                () -> assertEquals(0.0, XMath.wrap(100.0, 100.0), EPS, "the size itself wraps to 0"),
                () -> assertEquals(0.0, XMath.wrap(0.0, 100.0), EPS, "0 stays 0"),
                () -> assertEquals(99.5, XMath.wrap(99.5, 100.0), EPS, "values inside are unchanged"),
                () -> assertEquals(50.0, XMath.wrap(-250.0, 100.0), EPS, "wraps values more than one size below"),
                () -> assertEquals(50.0, XMath.wrap(350.0, 100.0), EPS, "wraps values more than one size above"),
                () -> assertEquals(5.0f, XMath.wrap(105.0f, 100.0f), 1e-6f, "float overload wraps"),
                () -> assertEquals(9, XMath.wrapBlock(-1, 10), "block index -1 wraps to n-1"),
                () -> assertEquals(0, XMath.wrapBlock(10, 10), "block index n wraps to 0"),
                () -> assertEquals(-10.0, XMath.wrapDelta(90.0, 100.0, true), EPS, "deltas above half wrap down"),
                () -> assertEquals(10.0, XMath.wrapDelta(-90.0, 100.0, true), EPS, "deltas below -half wrap up"),
                () -> assertEquals(50.0, XMath.wrapDelta(50.0, 100.0, true), EPS, "exactly half does not wrap (C WRAP_DX)"),
                () -> assertEquals(-50.0, XMath.wrapDelta(-50.0, 100.0, true), EPS, "exactly -half does not wrap"),
                () -> assertEquals(90.0, XMath.wrapDelta(90.0, 100.0, false), EPS, "no wrapping when wrap is off"),
                () -> assertEquals(5.0, XMath.wrapDistance(98, 0, 1, 4, 100, 100, true), EPS,
                    "distance takes the short way around (3,4,5)"),
                () -> assertEquals(97.0, XMath.wrapDistance(98, 0, 1, 0, 100, 100, false), EPS,
                    "distance goes straight without wrap"),
                () -> assertEquals(25.0, XMath.wrapDistanceSq(98, 0, 1, 4, 100, 100, true), EPS,
                    "squared distance wraps"),
                () -> assertEquals(5.0, XMath.wrapDistance(0, 3, 3, 7, 100, 100, false), EPS,
                    "distance uses y2 - y1 (3,4,5 with y1 = 3)"),
                () -> assertEquals(25.0, XMath.wrapDistanceSq(0, 3, 3, 7, 100, 100, false), EPS,
                    "squared distance uses y2 - y1"));
        }

        @Test
        @DisplayName("angles and small helpers")
        void angles() {
            assertAll(
                () -> assertEquals(Math.PI / 2, XMath.normalizeAngle(-3 * Math.PI / 2), EPS, "normalizes negatives"),
                () -> assertEquals(0.5, XMath.normalizeAngle(2 * Math.PI + 0.5), EPS, "normalizes above 2pi"),
                () -> assertEquals(0.0, XMath.normalizeAngle(0.0), EPS, "0 stays 0, not 2pi"),
                () -> assertEquals(-0.2, XMath.angleDiff(0.1, 2 * Math.PI - 0.1), EPS, "takes the short way across 0"),
                () -> assertEquals(Math.PI, XMath.angleDiff(0, Math.PI), EPS, "a half turn is +pi"),
                () -> assertEquals(Math.PI, XMath.angleDiff(Math.PI, 0), EPS, "-pi maps to +pi"),
                () -> assertEquals(-0.5, XMath.angleDiff(0.5, 0), EPS, "negative differences stay negative"),
                () -> assertEquals(Math.PI / 2, XMath.resToRadians(32), EPS, "RES 32 is a quarter turn"),
                () -> assertEquals(64.0, XMath.radiansToRes(Math.PI), EPS, "pi is RES 64"),
                () -> assertEquals(32.0, XMath.findDir(0, 1), EPS, "up is 32"),
                () -> assertEquals(96.0, XMath.findDir(0, -1), EPS, "down is 96 (wraps negatives)"),
                () -> assertEquals(0.0, XMath.findDir(0, 0), EPS, "zero vector is 0"),
                () -> assertEquals((float) (StrictMath.atan2(4, 3) / (2 * Math.PI)) * 128, XMath.findDir(3, 4),
                    "C DFLOAT: the angle is rounded to float before scaling by RES (bit-exact)"),
                () -> assertEquals(((float) (StrictMath.atan2(-4, 3) / (2 * Math.PI)) + 1f) * 128,
                    XMath.findDir(3, -4), "C DFLOAT: angle++ happens in float"),
                () -> assertEquals(3.0, XMath.clamp(5.0, 1.0, 3.0), EPS, "clamps high"),
                () -> assertEquals(1.0, XMath.clamp(-5.0, 1.0, 3.0), EPS, "clamps low"),
                () -> assertEquals(2.0, XMath.clamp(2.0, 1.0, 3.0), EPS, "keeps values inside"),
                () -> assertEquals(3, XMath.clamp(5, 1, 3), "int clamp high"),
                () -> assertEquals(1, XMath.clamp(-5, 1, 3), "int clamp low"),
                () -> assertEquals(2, XMath.clamp(2, 1, 3), "int clamp keeps values inside"),
                () -> assertEquals(-1, XMath.toBlock(-0.5), "toBlock floors negatives"),
                () -> assertEquals(1, XMath.toBlock(35.0), "toBlock: 35 px is block 1"),
                () -> assertEquals(52.5, XMath.blockCenter(1), EPS, "block 1 centre is 52.5"),
                () -> assertEquals(5.0, XMath.length(3, 4), EPS, "length is hypot"));
        }
    }

    @Nested
    @DisplayName("ShipShape")
    class Shapes {
        @Test
        @DisplayName("default ship matches C Default_ship()")
        void defaultShip() {
            ShipShape s = ShipShape.defaultShip();
            assertAll(
                () -> assertArrayEquals(new int[] {15, -9, -9}, s.ptX, "outline x"),
                () -> assertArrayEquals(new int[] {0, 8, -8}, s.ptY, "outline y"),
                () -> assertEquals(3, s.numPoints(), "three points"),
                () -> assertEquals(-9, s.engineX, "engine at the rear"),
                () -> assertEquals(0, s.engineY, "engine on the axis"),
                () -> assertEquals(15, s.mainGunX, "main gun at the nose"),
                () -> assertArrayEquals(new int[] {8}, s.leftLightY, "left light on the left wing"),
                () -> assertArrayEquals(new int[] {-8}, s.rightLightY, "right light on the right wing"),
                () -> assertArrayEquals(new int[] {15}, s.missileRackX, "missile rack at the nose"),
                () -> assertEquals(34, s.shieldRadius, "minimum shield radius is 34"),
                () -> assertSame(s, ShipShape.parseOrDefault(""), "empty strings give the default"),
                () -> assertSame(s, ShipShape.parseOrDefault("(SH: 1,1 2,2)"), "invalid strings give the default"),
                () -> assertNull(ShipShape.parse(null), "null parses to null"));
        }

        @Test
        @DisplayName("parses the 3.2 format including defaults for missing points")
        void parseNewFormat() {
            ShipShape s = ShipShape.parse("(NM:Arrow)(AU:Me)(SH: 15,0 -9,8 -9,-8)(EN: -8,0)(LG: 0,2 0,3)"
                + "(RG: 0,-2)(LR: -8,4)(RR: -8,-4)(XX: ignored)");
            ShipShape d = ShipShape.parse("(SH: 15,0 -9,8 -9,-8)");
            assertAll(
                () -> assertEquals("Arrow", s.name, "reads the name"),
                () -> assertEquals("Me", s.author, "reads the author"),
                () -> assertEquals(-8, s.engineX, "reads the engine"),
                () -> assertArrayEquals(new int[] {0, 0}, s.leftGunX, "reads all left guns"),
                () -> assertArrayEquals(new int[] {2, 3}, s.leftGunY, "reads all left gun y"),
                () -> assertArrayEquals(new int[] {-2}, s.rightGunY, "reads right guns"),
                () -> assertArrayEquals(new int[] {4}, s.leftRearGunY, "reads left rear guns"),
                () -> assertArrayEquals(new int[] {-4}, s.rightRearGunY, "reads right rear guns"),
                () -> assertEquals(15, d.mainGunX, "main gun defaults to the foremost point"),
                () -> assertEquals(0, d.mainGunY, "main gun defaults to the foremost point y"),
                () -> assertArrayEquals(new int[] {8}, d.leftLightY, "left light defaults to the leftmost point"),
                () -> assertArrayEquals(new int[] {-8}, d.rightLightY, "right light defaults to the rightmost point"),
                () -> assertEquals(-9, d.engineX, "engine defaults to the rearmost x"),
                () -> assertEquals(0, d.engineY, "engine defaults to y 0"),
                () -> assertArrayEquals(new int[] {15}, d.missileRackX, "missile rack defaults to the main gun"),
                () -> assertEquals("", d.name, "missing name is empty"));
        }

        @Test
        @DisplayName("parses the 3.1 format and the cmdline.c default/tank shapes")
        void parseOldFormatAndOptionDefaults() {
            ShipShape old = ShipShape.parse("(3,1,2)(15,0)(-9,8)(-9,-8)");
            ShipShape optDefault = ShipShape.parse(
                "(NM:Default)(AU:Unknown)(SH: 15,0 -9,8 -9,-8)(MG: 15,0)(LG: 15,0)(RG: 15,0)(EN: -9,0)"
                    + "(LR: -9,8)(RR: -9,-8)(LL: -9,8)(RL: -9,-8)(MR: 15,0)");
            ShipShape tank = ShipShape.parse("(NM:fueltank)(AU:John E. Norlin)(SH: 15,0 14,-5 9,-8 -5,-8 -3,-8 -3,0 "
                + "2,0 2,2 -3,2 -3,6 5,6 5,8 -5,8 -5,-8 -9,-8 -14,-5 -15,0 -14,5 -9,8 9,8 14,5)(EN: -15,0)(MG: 15,0)");
            assertAll(
                () -> assertArrayEquals(new int[] {15, -9, -9}, old.ptX, "old format reads points"),
                () -> assertEquals(15, old.mainGunX, "old format main gun is point 0"),
                () -> assertArrayEquals(new int[] {8}, old.leftLightY, "old format left light is the given index"),
                () -> assertArrayEquals(new int[] {-8}, old.rightLightY, "old format right light is the given index"),
                () -> assertEquals(-9, old.engineX, "old format engine is between the lights"),
                () -> assertEquals(0, old.engineY, "old format engine y is between the lights"),
                () -> assertNull(ShipShape.parse("(2,0,1)(15,0)(-9,8)"), "old format needs at least 3 points"),
                () -> assertEquals(24, ShipShape.parse("(24,1,2)(15,0)(-9,8)(-9,-8)" + "(-9,-8)".repeat(21)).numPoints(),
                    "old format accepts exactly 24 points (C MAX_SHIP_PTS)"),
                () -> assertNull(ShipShape.parse("(25,1,2)(15,0)(-9,8)(-9,-8)" + "(-9,-8)".repeat(22)),
                    "old format rejects 25 points"),
                () -> assertEquals(3, ShipShape.parse("(3,0,2)(15,0)(-9,8)(-9,-8)").numPoints(),
                    "old format accepts light index 0"),
                () -> assertNull(ShipShape.parse("(3,-1,2)(15,0)(-9,8)(-9,-8)"), "old format rejects a negative left index"),
                () -> assertNull(ShipShape.parse("(3,1,-1)(15,0)(-9,8)(-9,-8)"), "old format rejects a negative right index"),
                () -> assertNull(ShipShape.parse("(3,1,3)(15,0)(-9,8)(-9,-8)"), "old format rejects right index == n"),
                () -> assertNull(ShipShape.parse("(3,0,1)(15,0)(-9,8)"), "old format needs all declared points"),
                () -> assertNull(ShipShape.parse("(3,0,5)(15,0)(-9,8)(-9,-8)"), "old format light index in range"),
                () -> assertEquals(3, optDefault.numPoints(), "the defaultShipShape option parses"),
                () -> assertEquals(1, optDefault.leftRearGunX.length, "with its rear guns"),
                () -> assertEquals(21, tank.numPoints(), "the tankShipShape option parses"),
                () -> assertEquals(-15, tank.engineX, "tank engine is explicit"));
        }

        @Test
        @DisplayName("enforces the C shape limits")
        void limits() {
            assertAll(
                () -> assertNull(ShipShape.parse("(SH: 15,0 -9,8)"), "needs 3 points"),
                () -> assertNull(ShipShape.parse("(SH: 16,0 -9,8 -9,-8)"), "points must be within +-15"),
                () -> assertNull(ShipShape.parse("(SH: 15,0 -9,15 -9,-8 -16,0)"), "x must be within +-15"),
                () -> assertNull(ShipShape.parse("(SH: 9,0 -9,8 -9,-8)"), "must be big enough (sum >= 38)"),
                () -> assertNull(ShipShape.parse("(SH: 15,0 7,8 -9,-8)"), "needs a point at the rear"),
                () -> assertNull(ShipShape.parse("(SH: 15,15 9,8 -15,-15 -8,-9)"), "needs width >= 12 across the long axis"),
                () -> assertNull(ShipShape.parse("(SH: 15,0 -9,8 -9,-8)(MG: 15,8)"), "main gun must be inside"),
                () -> assertNull(ShipShape.parse("(SH: 15,0 -9,8 -9,-8)(LG: -15,15)"), "guns must be inside"),
                () -> assertNull(ShipShape.parse("(SH: 15,0 -9,8 -9,-8)(MR: 20,0)"), "racks must be inside the grid"),
                () -> assertEquals(3, ShipShape.parse("(SH: 15,0 -9,8 -9,-8)(MG: 0,0)").numPoints(),
                    "an interior main gun is accepted"),
                () -> assertNull(ShipShape.parse("(SH 15,0)"), "a keyword without colon is skipped"),
                () -> assertEquals(-9, ShipShape.parse("(SH: 15,0 -9,8 -9,-8)(EN: -9,0)(EN: 3,0)").engineX,
                    "only the first engine counts"));
        }

        @Test
        @DisplayName("the engine must be inside the hull; old-format engines are moved onto the axis")
        void engine() {
            String chevron = "(SH: 15,0 -9,8 0,0 -9,-8)";
            ShipShape oldChevron = ShipShape.parse("(4,1,3)(15,0)(-9,8)(0,0)(-9,-8)");
            assertAll(
                () -> assertNull(ShipShape.parse(chevron + "(EN: -9,0)"), "a new-format engine in the notch is rejected"),
                () -> assertEquals(0, ShipShape.parse(chevron + "(EN: 0,0)").engineX, "an engine on the hull is accepted"),
                () -> assertNull(ShipShape.parse(chevron), "the default engine (rearmost x, y 0) lies in the notch: rejected"),
                () -> assertEquals(0, oldChevron.engineX, "an old-format engine moves to the first hull point on y=0"),
                () -> assertEquals(0, oldChevron.engineY, "the moved engine stays on the axis"),
                () -> assertNull(ShipShape.parse("(SH: 15,0 -9,8 -9,-8)(EN: 15,15)"), "an engine outside is rejected"),
                () -> assertNull(ShipShape.parse("(SH: 15,0 -9,8 -9,-8)(EN: 20,0)"), "an engine off the grid is rejected"));
        }

        @Test
        @DisplayName("size, width and grid helpers")
        void helpers() {
            int[] tx = {15, -9, -9};
            int[] ty = {0, 8, -8};
            byte[][] g = ShipShape.outsideGrid(tx, ty, 3);
            byte[][] empty = new byte[31][31];
            for (byte[] row : empty) {
                java.util.Arrays.fill(row, (byte) 2);
            }
            assertAll(
                () -> assertTrue(ShipShape.sizeLimitsOk(tx, ty, 3), "the default triangle meets the size limits"),
                () -> assertFalse(ShipShape.sizeLimitsOk(new int[] {15, -9, -9}, new int[] {0, 16, -8}, 3), "y above 15 fails"),
                () -> assertFalse(ShipShape.sizeLimitsOk(new int[] {15, -9, -9}, new int[] {0, 8, -16}, 3), "y below -15 fails"),
                () -> assertFalse(ShipShape.sizeLimitsOk(new int[] {15, -16, -9}, new int[] {0, 8, -8}, 3), "x below -15 fails"),
                () -> assertTrue(ShipShape.sizeLimitsOk(new int[] {15, -15, -15}, new int[] {0, 15, -15}, 3),
                    "exactly +-15 is allowed"),
                () -> assertTrue(ShipShape.sizeLimitsOk(new int[] {8, -8, -8}, new int[] {0, 8, -8}, 3) == false,
                    "a sum of 32 (< 38) is too small"),
                () -> assertTrue(ShipShape.sizeLimitsOk(new int[] {11, -8, -8}, new int[] {0, 9, -10}, 3),
                    "a sum of exactly 38 is big enough (9+10+11+8)"),
                () -> assertFalse(ShipShape.sizeLimitsOk(new int[] {11, -8, -8}, new int[] {0, 9, -9}, 3),
                    "a sum of 37 is too small"),
                () -> assertFalse(ShipShape.sizeLimitsOk(new int[] {15, -7, -7}, new int[] {0, 8, -8}, 3),
                    "needs a point with x <= -8"),
                () -> assertFalse(ShipShape.sizeLimitsOk(new int[] {15, -9, -9}, new int[] {0, 8, -7}, 3),
                    "needs a point with y <= -8"),
                () -> assertFalse(ShipShape.sizeLimitsOk(new int[] {7, -9, -9}, new int[] {0, 8, -8}, 3),
                    "needs a point with x >= 8"),
                () -> assertFalse(ShipShape.sizeLimitsOk(new int[] {15, -9, -9}, new int[] {0, 7, -8}, 3),
                    "needs a point with y >= 8"),
                () -> assertFalse(ShipShape.sizeLimitsOk(new int[] {15, -15}, new int[] {15, -15}, 2),
                    "needs three qualifying points"),
                () -> assertTrue(ShipShape.widthOk(new int[] {15, -15, 0}, new int[] {0, 0, 12}, 3),
                    "width exactly 12 across the long axis passes"),
                () -> assertFalse(ShipShape.widthOk(new int[] {15, -15, 0}, new int[] {0, 0, 11}, 3),
                    "width 11 fails"),
                () -> assertTrue(ShipShape.widthOk(new int[] {0, 0, 12}, new int[] {15, -15, 0}, 3),
                    "the long axis may be vertical"),
                () -> assertEquals(0, g[15][15], "the centre is inside"),
                () -> assertEquals(1, g[30][15], "the nose is on the outline"),
                () -> assertEquals(1, g[6][15 + 4], "the left wing edge is rasterized"),
                () -> assertEquals(2, g[0][30], "the corner is outside"),
                () -> assertEquals(2, g[30][15 + 1], "beside the nose is outside"),
                () -> assertEquals(-9, ShipShape.firstInsideOnAxis(g), "the first axis point not outside is x=-9"),
                () -> assertEquals(ShipShape.NO_POINT, ShipShape.firstInsideOnAxis(empty), "an all-outside axis has none"),
                () -> assertTrue(ShipShape.outside(g, 16, 0), "off-grid points are outside"),
                () -> assertTrue(ShipShape.outside(g, 0, -16), "off-grid y is outside"),
                () -> assertFalse(ShipShape.outside(g, 0, 0), "inside points are not outside"));
        }

        @Test
        @DisplayName("shield radius rounds an odd diameter down to even + 2")
        void oddShield() {
            ShipShape s = ShipShape.parse("(SH: 15,14 -15,-14 15,-14)(EN: 10,0)");
            assertEquals(42, s.shieldRadius, "r = (int)(2*sqrt(421)) = 41 (odd) gives 41 + 2 - 1 = 42");
        }

        @Test
        @DisplayName("keyword forms, list caps and syntax tolerance")
        void syntax() {
            ShipShape full = ShipShape.parse("(shape: 15,0 -9,8 -9,-8)(mainGun: 14,0)(leftGun: 0,2)(rightGun: 0,-2)"
                + "(leftLight: -8,7)(rightLight: -8,-7)(engine: -8,0)(missileRack: 10,0 5,0)(name:N)(author:A)"
                + "(leftRearGun: -8,1)(rightRearGun: -8,-1)");
            ShipShape caps = ShipShape.parse("(SH: 15,0 -9,8 -9,-8" + " -9,-8".repeat(22) + ")(LG: 0,1 0,2 0,3 0,4)"
                + "(RG: 0,-1 0,-2 0,-3 0,-4)(LR: -5,1 -5,2 -5,3 -5,4)(RR: -5,-1 -5,-2 -5,-3 -5,-4)"
                + "(LL: -8,1 -8,2 -8,3 -8,4)(RL: -8,-1 -8,-2 -8,-3 -8,-4)(MR: 1,0 2,0 3,0 4,0 5,0)");
            ShipShape ties = ShipShape.parse("(SH: 15,3 15,-1 -9,8 -9,-8)");
            ShipShape lightTies = ShipShape.parse("(SH: 15,8 -9,8 -9,-8 15,-8)");
            ShipShape spaced = ShipShape.parse("(SH: +15,0  -9,8   -9,-8)(MG:  15,0)(MG: 0,0)");
            ShipShape open = ShipShape.parse("(SH: 15,0 -9,8 -9,-8");
            assertAll(
                () -> assertEquals(14, full.mainGunX, "full keyword mainGun"),
                () -> assertArrayEquals(new int[] {2}, full.leftGunY, "full keyword leftGun"),
                () -> assertArrayEquals(new int[] {-2}, full.rightGunY, "full keyword rightGun"),
                () -> assertArrayEquals(new int[] {7}, full.leftLightY, "full keyword leftLight"),
                () -> assertArrayEquals(new int[] {-7}, full.rightLightY, "full keyword rightLight"),
                () -> assertEquals(-8, full.engineX, "full keyword engine"),
                () -> assertArrayEquals(new int[] {10, 5}, full.missileRackX, "full keyword missileRack"),
                () -> assertEquals("N", full.name, "full keyword name"),
                () -> assertEquals("A", full.author, "full keyword author"),
                () -> assertArrayEquals(new int[] {1}, full.leftRearGunY, "full keyword leftRearGun"),
                () -> assertArrayEquals(new int[] {-1}, full.rightRearGunY, "full keyword rightRearGun"),
                () -> assertEquals(24, caps.numPoints(), "at most 24 outline points"),
                () -> assertArrayEquals(new int[] {1, 2, 3}, caps.leftGunY, "at most 3 left guns"),
                () -> assertArrayEquals(new int[] {-1, -2, -3}, caps.rightGunY, "at most 3 right guns"),
                () -> assertArrayEquals(new int[] {1, 2, 3}, caps.leftRearGunY, "at most 3 left rear guns"),
                () -> assertArrayEquals(new int[] {-1, -2, -3}, caps.rightRearGunY, "at most 3 right rear guns"),
                () -> assertArrayEquals(new int[] {1, 2, 3}, caps.leftLightY, "at most 3 left lights"),
                () -> assertArrayEquals(new int[] {-1, -2, -3}, caps.rightLightY, "at most 3 right lights"),
                () -> assertArrayEquals(new int[] {1, 2, 3, 4}, caps.missileRackX, "at most 4 missile racks"),
                () -> assertEquals(-1, ties.mainGunY, "default main gun: foremost, then smallest |y|"),
                () -> assertEquals(-9, ties.engineX, "default engine: first rearmost x"),
                () -> assertArrayEquals(new int[] {-9}, lightTies.leftLightX, "left light tie picks the rearmost"),
                () -> assertArrayEquals(new int[] {-9}, lightTies.rightLightX, "right light tie picks the rearmost"),
                () -> assertEquals(8, lightTies.mainGunY, "main gun tie on |y| keeps the first"),
                () -> assertArrayEquals(new int[] {15, -9, -9}, spaced.ptX, "signs and spaces before numbers are accepted"),
                () -> assertArrayEquals(new int[] {0, 8, -8}, spaced.ptY, "several spaces between pairs are accepted"),
                () -> assertNull(ShipShape.parse("(SH: 15,0 -9, 8 -9,-8)"),
                    "like C, a space after a comma makes the next scan fail (only 2 points kept)"),
                () -> assertEquals(15, spaced.mainGunX, "only the first main gun counts"),
                () -> assertEquals(3, open.numPoints(), "a missing final ')' is tolerated"),
                () -> assertNull(ShipShape.parse("(SH: 15 0 -9,8 -9,-8)"), "a missing comma ends the list"),
                () -> assertNull(ShipShape.parse("(SH: 999999,0 -9,8 -9,-8)"), "huge numbers fail the limits"),
                () -> assertNull(ShipShape.parse("(SH: 15,x -9,8 -9,-8)"), "a non-number y ends the list"),
                () -> assertNull(ShipShape.parse("(averyveryverylongkey: 1,2)"), "a colon beyond 19 chars is no keyword"),
                () -> assertEquals(3, ShipShape.parse("(SH: 15,0 -9,8 -9,-8)(1x: 3)").numPoints(),
                    "keywords starting with a digit are ignored"),
                () -> assertEquals(3, ShipShape.parse("(SH: 15,0 -9,8 -9,-8)(Zz: 3)").numPoints(),
                    "unknown upper-case keywords are ignored"),
                () -> assertEquals(3, ShipShape.parse("(SH: 15,0 -9,8 -9,-8)(zz: 3)").numPoints(),
                    "unknown lower-case keywords are ignored"));
        }

        @Test
        @DisplayName("outsideGrid matches the hull picture of a diamond")
        void grid() {
            byte[][] g = ShipShape.outsideGrid(new int[] {10, 0, -10, 0}, new int[] {0, 10, 0, -10}, 4);
            StringBuilder sb = new StringBuilder();
            for (int y = 11; y >= -11; y--) {
                for (int x = -11; x <= 11; x++) {
                    sb.append(".*o".charAt(g[x + 15][y + 15]) == 'o' ? ' ' : ".*o".charAt(g[x + 15][y + 15]));
                }
                sb.append('\n');
            }
            String picture = sb.toString();
            assertAll(
                () -> assertEquals(String.join("\n",
                    "                       ",
                    "           *           ",
                    "          *.*          ",
                    "         *...*         ",
                    "        *.....*        ",
                    "       *.......*       ",
                    "      *.........*      ",
                    "     *...........*     ",
                    "    *.............*    ",
                    "   *...............*   ",
                    "  *.................*  ",
                    " *...................* ",
                    "  *.................*  ",
                    "   *...............*   ",
                    "    *.............*    ",
                    "     *...........*     ",
                    "      *.........*      ",
                    "       *.......*       ",
                    "        *.....*        ",
                    "         *...*         ",
                    "          *.*          ",
                    "           *           ",
                    "                       ") + "\n", picture,
                    "outline '*', inside '.', outside ' ' for the diamond (10,0) (0,10) (-10,0) (0,-10)"));
        }

        private String picture(int[] xs, int[] ys) {
            byte[][] g = ShipShape.outsideGrid(xs, ys, xs.length);
            StringBuilder sb = new StringBuilder();
            for (int y = 15; y >= -15; y--) {
                for (int x = -15; x <= 15; x++) {
                    byte v = g[x + 15][y + 15];
                    sb.append(v == 2 ? ' ' : (v == 1 ? '*' : '.'));
                }
                sb.append('\n');
            }
            return sb.toString();
        }

        @Test
        @DisplayName("outsideGrid rasterizes steep and shallow edges exactly like shape2wire")
        void steepPictures() {
            String kite = String.join("\n", "                               ",
"                               ",
"                               ",
"                 *             ",
"                *.*            ",
"               *..*            ",
"               *...*           ",
"              *....*           ",
"              *.....*          ",
"             *......*          ",
"             *.......*         ",
"            *........*         ",
"            *........*         ",
"           *........*          ",
"           *........*          ",
"          *........*           ",
"          *........*           ",
"         *.........*           ",
"         *........*            ",
"          *.......*            ",
"           *.....*             ",
"           *.....*             ",
"            *....*             ",
"             *..*              ",
"             *..*              ",
"              **               ",
"               *               ",
"               *               ",
"                               ",
"                               ",
"                               ") + "\n";
            String tri = String.join("\n", "                               ",
"                               ",
"                               ",
"                               ",
"                               ",
"                               ",
"    ****                       ",
"     *..****                   ",
"      *.....****               ",
"      *.........****           ",
"       *............****       ",
"        *...............****   ",
"        *...................*  ",
"         *.................*   ",
"          *...............*    ",
"          *...............*    ",
"           *.............*     ",
"            *............*     ",
"            *...........*      ",
"             *..........*      ",
"              *........*       ",
"              *........*       ",
"               *......*        ",
"                *.....*        ",
"                *....*         ",
"                 *...*         ",
"                  *.*          ",
"                  *.*          ",
"                   *           ",
"                   *           ",
"                               ") + "\n";
            assertAll(
                () -> assertEquals(kite, picture(new int[] {2, -6, 0, 6}, new int[] {12, -3, -12, 3}),
                    "all-steep kite (2,12) (-6,-3) (0,-12) (6,3): y-major rasterization from the lower end"),
                () -> assertEquals(tri, picture(new int[] {13, -11, 4}, new int[] {3, 9, -14}),
                    "mixed shallow/steep triangle (13,3) (-11,9) (4,-14)"));
        }

        @Test
        @DisplayName("pockets open in each direction are outside; cut-off border rows/columns are outside")
        void floodDirections() {
            assertAll(
                () -> assertTrue(ShipShape.parse("(SH: 15,0 -9,8 0,0 -9,-8)(EN: 0,0)") != null
                        && ShipShape.parse("(SH: -15,0 9,8 0,0 9,-8)(EN: -10,0)(MG: 0,0)") != null
                        && ShipShape.parse("(SH: 0,15 8,-9 0,0 -8,-9)(MG: 0,0)(EN: 0,0)") != null
                        && ShipShape.parse("(SH: 0,-15 8,9 0,0 -8,9)(MG: 0,0)(EN: 0,0)") != null
                        && ShipShape.parse("(SH: 15,14 -15,14 -15,-15)(MG: 0,0)") != null
                        && ShipShape.parse("(SH: 14,15 14,-15 -15,0)(MG: 0,0)") != null,
                    "each probe shape is valid without the probed point"),
                () -> assertNull(ShipShape.parse("(SH: 15,0 -9,8 0,0 -9,-8)(EN: 0,0)(MR: -5,0)"),
                    "a rack in a notch open to the left (reached by +x moves) is outside"),
                () -> assertNull(ShipShape.parse("(SH: -15,0 9,8 0,0 9,-8)(EN: -10,0)(MG: 0,0)(MR: 5,0)"),
                    "a rack in a notch open to the right (reached by -x moves) is outside"),
                () -> assertNull(ShipShape.parse("(SH: 0,15 8,-9 0,0 -8,-9)(MG: 0,0)(EN: 0,-5)"),
                    "an engine in a notch open downward (reached by +y moves) is outside"),
                () -> assertNull(ShipShape.parse("(SH: 0,-15 8,9 0,0 -8,9)(MG: 0,0)(EN: 0,5)"),
                    "an engine in a notch open upward (reached by -y moves) is outside"),
                () -> assertNull(ShipShape.parse("(SH: 15,14 -15,14 -15,-15)(MG: 0,15)"),
                    "a top row cut off by a full-width outline is still outside (seeded from the border)"),
                () -> assertNull(ShipShape.parse("(SH: 14,15 14,-15 -15,0)(MG: 15,0)"),
                    "a right column cut off by a full-height outline is still outside"));
        }

        @Test
        @DisplayName("default light selection breaks ties toward the rearmost point; 24-point defaults work")
        void defaultTies() {
            ShipShape tie = ShipShape.parse("(SH: -9,8 15,8 15,-8 -9,-8)");
            String many = "(SH: 15,0 -9,8 -9,-8" + " -9,-8".repeat(21) + ")";
            ShipShape full = ShipShape.parse(many);
            assertAll(
                () -> assertArrayEquals(new int[] {-9}, tie.leftLightX, "an earlier rear point keeps the left light on a y tie"),
                () -> assertArrayEquals(new int[] {-9}, tie.rightLightX, "an earlier rear point keeps the right light on a y tie"),
                () -> assertEquals(24, full.numPoints(), "24 points with all defaults computed"),
                () -> assertEquals(15, full.mainGunX, "default main gun over 24 points"),
                () -> assertArrayEquals(new int[] {8}, full.leftLightY, "default left light over 24 points"),
                () -> assertEquals(-9, full.engineX, "default engine over 24 points"));
        }

        @Test
        @DisplayName("old format: light indices and the engine between them")
        void oldFormatLights() {
            ShipShape s = ShipShape.parse("(4,2,1)(15,0)(-9,8)(-5,0)(-9,-8)");
            assertAll(
                () -> assertArrayEquals(new int[] {-5}, s.leftLightX, "left light is point 2, not the leftmost point"),
                () -> assertArrayEquals(new int[] {8}, s.rightLightY, "right light is point 1, not the rightmost point"),
                () -> assertEquals(-7, s.engineX, "engine x is the lights' midpoint (-5 + -9) / 2"),
                () -> assertEquals(4, s.engineY, "engine y is the lights' midpoint (0 + 8) / 2"),
                () -> assertEquals(3, ShipShape.parse("(3,1,0)(15,0)(-9,8)(-9,-8)").numPoints(), "right index 0 is valid"),
                () -> assertEquals(15, ShipShape.parse("(4,1,3)(15,0)(-15,15)(15,0)(-15,-15)").engineX,
                    "an outside old-format engine moves to (15,0) when that is the only hull point on the axis"),
                () -> assertNull(ShipShape.parse("(3,3,1)(15,0)(-9,8)(-9,-8)"), "left index == n is rejected"));
        }

        @Test
        @DisplayName("parser edge cases that must not throw or mis-scan")
        void scanning() {
            String base = "(SH: 15,0 -9,8 -9,-8)";
            assertAll(
                () -> assertEquals(0, ShipShape.parse(base + "(MG: 0, 1)").mainGunX, "whitespace after the comma is skipped"),
                () -> assertEquals(1, ShipShape.parse(base + "(MG: 0, 1)").mainGunY, "the y after the space is read"),
                () -> assertEquals(15, ShipShape.parse(base + "(MG: 0,)").mainGunX, "a missing y leaves the default gun"),
                () -> assertEquals(15, ShipShape.parse(base + "(MG: 5)").mainGunX, "a missing comma leaves the default gun"),
                () -> assertEquals(3, ShipShape.parse("(SH: 15,0 -9,8 -9,-8 )").numPoints(), "a trailing space ends the list"),
                () -> assertNull(ShipShape.parse("("), "a lone '(' is no shape"),
                () -> assertEquals(3, ShipShape.parse("(" + "a".repeat(19) + ":" + base).numPoints(),
                    "a colon after 19 keyword chars is not a keyword; scanning resumes at the next '('"),
                () -> assertNull(ShipShape.parse("(:" + base), "an empty keyword swallows its group up to ')'"),
                () -> assertEquals(3, ShipShape.parse(base + "(x): 1,1)").numPoints(), "')' inside a keyword is harmless"),
                () -> assertEquals(3, ShipShape.parse("(MG: 0,0()" + base).numPoints(),
                    "scanning resumes after the group's ')' even if the value ends with '('"),
                () -> assertEquals(3, ShipShape.parse(base + "(1x: 0,0)").numPoints(), "a digit keyword adds no points"),
                () -> assertNull(ShipShape.parse("(SH: 15,0 -9,8 -9,-8)(MG: 16,0)"), "a main gun off the grid is outside"),
                () -> assertEquals(3, ShipShape.parse(base + "(Zz: 0,0)").numPoints(), "an unknown upper-case keyword adds no points"),
                () -> assertEquals(3, ShipShape.parse(base + "(zz: 0,0)").numPoints(), "an unknown lower-case keyword adds no points"),
                () -> assertEquals(3, ShipShape.parse("(SH: 8,0 -15,15 -15,-15)").numPoints(), "x = 8 counts as a front point"));
        }

        @Test
        @DisplayName("width check: axis choice, normalization and projection")
        void widthCases() {
            assertAll(
                () -> assertFalse(ShipShape.widthOk(new int[] {0, 0, 11}, new int[] {10, -10, 0}, 3),
                    "vertical long axis: width 11 fails (needs a normalized x component)"),
                () -> assertFalse(ShipShape.widthOk(new int[] {-6, -13, -4, -7}, new int[] {13, 12, -1, -2}, 4),
                    "pair distances use y differences"),
                () -> assertTrue(ShipShape.widthOk(new int[] {-13, 8, -6, 13}, new int[] {0, -12, 9, -13}, 4),
                    "on equal longest distances the first pair defines the axis"),
                () -> assertFalse(ShipShape.widthOk(new int[] {-7, 3, 14, 13}, new int[] {6, 4, 4, 15}, 4),
                    "the axis vector is normalized"),
                () -> assertFalse(ShipShape.widthOk(new int[] {-12, 6, -14}, new int[] {5, -5, 8}, 3),
                    "projections add the x and y parts"),
                () -> assertFalse(ShipShape.widthOk(new int[] {12, -4, -7, 13}, new int[] {-1, 1, 13, -2}, 4),
                    "projections use y differences"));
        }

        @Test
        @DisplayName("rotation helpers and shield radius")
        void rotation() {
            ShipShape big = ShipShape.parse("(SH: 15,0 -15,15 -15,-15)");
            assertAll(
                () -> assertEquals(0.0, ShipShape.rotX(1, 0, 0, 1), EPS, "x of (1,0) rotated a quarter turn is 0"),
                () -> assertEquals(2 * 0.6 - 3 * 0.8, ShipShape.rotX(2, 3, 0.6, 0.8), EPS, "rotX = x cos - y sin"),
                () -> assertEquals(2 * 0.8 + 3 * 0.6, ShipShape.rotY(2, 3, 0.6, 0.8), EPS, "rotY = x sin + y cos"),
                () -> assertEquals(1.0, ShipShape.rotY(1, 0, 0, 1), EPS, "y of (1,0) rotated a quarter turn is 1"),
                () -> assertEquals(-1.0, ShipShape.rotX(0, 1, 0, 1), EPS, "x of (0,1) rotated a quarter turn is -1"),
                () -> assertEquals(44, big.shieldRadius, "shield radius = (int)(2*maxR) + 2 - oddBit (42 + 2 - 0)"));
        }
    }

    @Nested
    @DisplayName("Rng")
    class RngTest {
        @Test
        @DisplayName("deterministic, seed-dependent, uniform in [0,1)")
        void rng() {
            Rng a = new Rng(5);
            Rng b = new Rng(5);
            Rng c = new Rng(6);
            boolean same = true;
            boolean differs = false;
            for (int i = 0; i < 100; i++) {
                int x = a.nextInt();
                same &= x == b.nextInt();
                differs |= x != c.nextInt();
            }
            a.seed(5);
            Rng fresh = new Rng(5);
            int firstAgain = a.nextInt();
            int firstFresh = fresh.nextInt();
            Rng u = new Rng(123);
            double sum = 0;
            float min = 1;
            float max = 0;
            for (int i = 0; i < 100_000; i++) {
                float f = u.nextFloat();
                sum += f;
                min = Math.min(min, f);
                max = Math.max(max, f);
            }
            double mean = sum / 100_000;
            boolean fSame = same;
            boolean fDiffers = differs;
            float fmin = min;
            float fmax = max;
            assertAll(
                () -> assertTrue(fSame, "equal seeds give equal sequences"),
                () -> assertTrue(fDiffers, "different seeds give different sequences"),
                () -> assertEquals(firstFresh, firstAgain, "seed() restarts the sequence"),
                () -> assertEquals(0.5, mean, 0.01, "floats average 0.5 (tolerance: sampling error of 1e5 draws)"),
                () -> assertTrue(fmin >= 0f && fmin < 0.001f, "floats reach down to 0"),
                () -> assertTrue(fmax < 1f && fmax > 0.999f, "floats stay below 1"));
        }
    }

    @Test
    @DisplayName("Rng golden sequence is stable (server and older clients must agree)")
    void rngGolden() {
        Rng z = new Rng(0);
        Rng one = new Rng(1);
        Rng neg = new Rng(-1);
        assertAll(
            () -> assertEquals(List.of(844830715, 1143974537, -1633381130), List.of(z.nextInt(), z.nextInt(), z.nextInt()),
                "seed 0 sequence"),
            () -> assertEquals(List.of(-1204393971, -741760938, 530670935),
                List.of(one.nextInt(), one.nextInt(), one.nextInt()), "seed 1 sequence"),
            () -> assertEquals(List.of(-274070852, 1346862338, 1124809937),
                List.of(neg.nextInt(), neg.nextInt(), neg.nextInt()), "seed -1 sequence"),
            () -> assertEquals(0.19670242f, new Rng(0).nextFloat(), "first float of seed 0 is the top 24 bits / 2^24"));
    }

    @Nested
    @DisplayName("DebrisBurst")
    class Burst {
        @Test
        @DisplayName("reproduces Make_debris draws exactly from the seed")
        void generate() {
            DebrisBurst d = new DebrisBurst();
            d.vx = 1;
            d.vy = -1;
            d.seed = 77;
            d.count = 50;
            d.minDir = 120;
            d.dirSpan = 20;
            d.minSpeed = 2;
            d.maxSpeed = 6;
            d.minLife = 3;
            d.maxLife = 9;
            List<float[]> got = new ArrayList<>();
            d.generate(new Rng(999), (i, vx, vy, speed, life) -> got.add(new float[] {i, vx, vy, life}));
            List<float[]> again = new ArrayList<>();
            d.generate(new Rng(1), (i, vx, vy, speed, life) -> again.add(new float[] {i, vx, vy, life}));
            // Independent re-derivation of play.c Make_debris with the same draws.
            Rng r = new Rng(77);
            List<float[]> expect = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int dir = (120 + (int) (r.nextFloat() * 20)) & 127;
                int dirPlus = (dir + 1) & 127;
                float off = r.nextFloat();
                float c0 = (float) StrictMath.cos(dir * 2.0 * Math.PI / 128);
                float c1 = (float) StrictMath.cos(dirPlus * 2.0 * Math.PI / 128);
                float s0 = (float) StrictMath.sin(dir * 2.0 * Math.PI / 128);
                float s1 = (float) StrictMath.sin(dirPlus * 2.0 * Math.PI / 128);
                float speed = 2 + r.nextFloat() * 4;
                int life = (int) (3 + r.nextFloat() * 6 + 1);
                expect.add(new float[] {i, 1 + (c0 + (c1 - c0) * off) * speed, -1 + (s0 + (s1 - s0) * off) * speed, life});
            }
            int minLife = 99;
            int maxLife = 0;
            for (float[] p : got) {
                minLife = Math.min(minLife, (int) p[3]);
                maxLife = Math.max(maxLife, (int) p[3]);
            }
            int lo = minLife;
            int hi = maxLife;
            assertAll(
                () -> assertEquals(50, got.size(), "emits count particles"),
                () -> assertEquals(toString(expect), toString(got), "matches the C formula draw by draw"),
                () -> assertEquals(toString(got), toString(again), "the seed field, not the passed RNG state, decides"),
                () -> assertTrue(lo >= 4 && hi <= 9, "life is in [minLife+1, maxLife]"));
        }

        private String toString(List<float[]> l) {
            StringBuilder sb = new StringBuilder();
            for (float[] p : l) {
                sb.append(java.util.Arrays.toString(p)).append(';');
            }
            return sb.toString();
        }
    }

    @Nested
    @DisplayName("DebrisBurst limits")
    class BurstLimits {
        @Test
        @DisplayName("more than 255 particles, the hypotenuse life clamp and the speed passed to the sink")
        void limits() {
            DebrisBurst d = new DebrisBurst();
            d.count = 300;
            d.dirSpan = 128;
            d.minSpeed = 10;
            d.maxSpeed = 10;
            d.minLife = 100;
            d.maxLife = 100;
            List<float[]> clamped = new ArrayList<>();
            d.hypotenuse = 500;
            d.generate(new Rng(0), (i, vx, vy, speed, life) -> clamped.add(new float[] {speed, life}));
            List<float[]> free = new ArrayList<>();
            d.hypotenuse = 0;
            d.generate(new Rng(0), (i, vx, vy, speed, life) -> free.add(new float[] {speed, life}));
            d.hypotenuse = 1010;
            List<float[]> exact = new ArrayList<>();
            d.generate(new Rng(0), (i, vx, vy, speed, life) -> exact.add(new float[] {speed, life}));
            assertAll(
                () -> assertEquals(300, clamped.size(), "a burst may hold more than 255 particles"),
                () -> assertEquals(10f, clamped.get(0)[0], "the sink receives the particle's own speed"),
                () -> assertEquals(50f, clamped.get(0)[1], "life*speed 1010 > hypotenuse 500 gives life 500/10 = 50"),
                () -> assertEquals(101f, free.get(0)[1], "hypotenuse 0 disables the clamp (life = 100 + 1)"),
                () -> assertEquals(101f, exact.get(0)[1], "life*speed equal to the hypotenuse is not clamped"));
        }
    }

    @Nested
    @DisplayName("ThrustSparks")
    class Thrust {
        @Test
        @DisplayName("heading quantization and seeds")
        void dirsAndSeeds() {
            assertAll(
                () -> assertEquals(0, ThrustSparks.dirFromU16(255), "just below half a RES step rounds down"),
                () -> assertEquals(1, ThrustSparks.dirFromU16(256), "half a RES step rounds up"),
                () -> assertEquals(32, ThrustSparks.dirFromU16(16384), "a quarter turn is RES 32"),
                () -> assertEquals(0, ThrustSparks.dirFromU16(65535), "just below a full turn wraps to 0"),
                () -> assertEquals(32, ThrustSparks.dirFromRadians(Math.PI / 2), "pi/2 is RES 32"),
                () -> assertEquals(ThrustSparks.seed(10, 3), ThrustSparks.seed(10, 3), "seeds are deterministic"),
                () -> assertTrue(ThrustSparks.seed(10, 3) != ThrustSparks.seed(3, 10), "seeds depend on order"),
                () -> assertTrue(ThrustSparks.seed(10, 3) != ThrustSparks.seed(11, 3), "seeds depend on the tick"),
                () -> assertEquals(-274424599, ThrustSparks.seed(7, 3), "golden seed for tick 7, ship 3 (wire compatibility)"));
        }

        @Test
        @DisplayName("ports C Thrust(): directions, speeds, lives, counts and the afterburner split")
        void compute() {
            DebrisBurst n = new DebrisBurst();
            DebrisBurst a = new DebrisBurst();
            Rng rng = new Rng(0);
            ThrustSparks.compute(1234, 100, 200, 0, 1.5f, -2f, 45, 0, 60, 14, 9000, rng, n, a);
            // independent replay of the draws
            Rng r = new Rng(1234);
            int thisRand = r.nextInt() & 3;
            int perTick = (int) (45 * 0.15 + thisRand + 1);
            int seedA = r.nextInt();
            int seedB = r.nextInt();
            DebrisBurst n2 = new DebrisBurst();
            DebrisBurst a2 = new DebrisBurst();
            ThrustSparks.compute(1234, 100, 200, 0, 1.5f, -2f, 45, 15, 60, 14, 9000, rng, n2, a2);
            int alt = ((perTick - 1) * 15 >> 4) + 1;
            DebrisBurst one = new DebrisBurst();
            DebrisBurst oneAlt = new DebrisBurst();
            ThrustSparks.compute(1234, 100, 200, 0, 1.5f, -2f, 45, 1, 60, 14, 9000, rng, one, oneAlt);
            DebrisBurst capped = new DebrisBurst();
            ThrustSparks.compute(1, 0, 0, 100, 0, 0, 45, 0, 10, 10, 0, rng, capped, new DebrisBurst());
            DebrisBurst uncapped = new DebrisBurst();
            ThrustSparks.compute(1, 0, 0, 100, 0, 0, 45, 0, 5, 14, 0, rng, uncapped, new DebrisBurst());
            DebrisBurst tiny = new DebrisBurst();
            ThrustSparks.compute(1, 0, 0, 100, 0, 0, 45, 0, 2, 1, 0, rng, tiny, new DebrisBurst());
            DebrisBurst equalFps = new DebrisBurst();
            ThrustSparks.compute(1, 0, 0, 100, 0, 0, 45, 0, 14, 14, 0, rng, equalFps, new DebrisBurst());
            assertAll(
                () -> assertEquals(perTick, n.count, "per C frame (= sim tick): (int)(power*0.15 + rand + 1) sparks"),
                () -> assertTrue(n.count >= 7 && n.count <= 10, "power 45 gives 7..10 sparks"),
                () -> assertEquals(0, a.count, "no afterburner sparks without afterburners"),
                () -> assertEquals(37, n.minDir, "min dir = (int)(dir + 64 - 25.6 - 1)"),
                () -> assertEquals(53, n.dirSpan, "max dir (int)(dir + 90.6) = 90, span 53"),
                () -> assertEquals(1.0f, n.minSpeed, "min speed 1"),
                () -> assertEquals((float) (1 + 45 * 0.14), n.maxSpeed, "max speed 1 + power*0.14"),
                () -> assertEquals(3, n.minLife, "min life 3"),
                () -> assertEquals(18, n.maxLife, "max life 3 + (int)(power*0.35)"),
                () -> assertEquals(100f, n.x, "starts at the engine x"),
                () -> assertEquals(200f, n.y, "starts at the engine y"),
                () -> assertEquals(1.5f, n.vx, "inherits the ship velocity x"),
                () -> assertEquals(-2f, n.vy, "inherits the ship velocity y"),
                () -> assertEquals(9000f, n.hypotenuse, "passes the hypotenuse"),
                () -> assertEquals(seedA, n.seed, "the normal burst gets the third draw as seed"),
                () -> assertEquals(seedB, a.seed, "the alt burst gets the fourth draw as seed"),
                () -> assertEquals(alt, a2.count, "afterburners 15: ((n-1)*15 >> 4) + 1 blue sparks"),
                () -> assertEquals(perTick - alt, n2.count, "the rest stay red"),
                () -> assertEquals(9, capped.minDir, "heading 100: (int)137.4 = 137 wraps to 9 mod RES"),
                () -> assertEquals(10, capped.maxLife, "shotLife >= fps caps the max life at shotLife"),
                () -> assertEquals(18, uncapped.maxLife, "shotLife < fps does not cap"),
                () -> assertEquals(2, tiny.minLife, "a shotLife below 3 also lowers the min life"),
                () -> assertEquals(2, tiny.maxLife, "and the max life"),
                () -> assertEquals(1, oneAlt.count, "one afterburner: ((n-1)*1 >> 4) + 1 = 1 blue spark, as C"),
                () -> assertEquals(perTick - 1, one.count, "one afterburner leaves the rest red"),
                () -> assertEquals(14, equalFps.maxLife, "shotLife == fps also caps the max life"));
        }

        @Test
        @DisplayName("computeWire quantizes the ship state like the wire, then computes")
        void computeWire() {
            Rng rng = new Rng(0);
            DebrisBurst n = new DebrisBurst();
            DebrisBurst a = new DebrisBurst();
            ThrustSparks.computeWire(7, 3, 100.30, 200.80, 1.25f, -2.5f, 96, 1.53, -0.44, 45.5f, 3, 60, 14,
                9000, rng, n, a);
            DebrisBurst n2 = new DebrisBurst();
            DebrisBurst a2 = new DebrisBurst();
            ThrustSparks.compute(ThrustSparks.seed(7, 3), 100.5f + 1.25f, 201.0f - 2.5f, 96, 1.5f, -0.4375f, 45.5f,
                3, 60, 14, 9000, rng, n2, a2);
            assertAll(
                () -> assertEquals(100.5f, ThrustSparks.wirePos(100.30), "positions round to half pixels"),
                () -> assertEquals(201.0f, ThrustSparks.wirePos(200.80), "up as well"),
                () -> assertEquals(-0.4375f, ThrustSparks.wireVel(-0.44), "velocities round to 1/16"),
                () -> assertEquals(n2.x, n.x, "engine x = quantized x + engine offset"),
                () -> assertEquals(n2.y, n.y, "engine y = quantized y + engine offset"),
                () -> assertEquals(n2.vx, n.vx, "velocity x quantized"),
                () -> assertEquals(n2.vy, n.vy, "velocity y quantized"),
                () -> assertEquals(n2.seed, n.seed, "seeded from (tick, id)"),
                () -> assertEquals(n2.count, n.count, "same count"),
                () -> assertEquals(a2.count, a.count, "same afterburner split"),
                () -> assertEquals(n2.maxSpeed, n.maxSpeed, "power passes through as the float"));
        }
    }

    @Test
    @DisplayName("Item ids follow item.h")
    void items() {
        assertAll(
            () -> assertEquals(Item.COUNT, Item.values().length, "COUNT matches the enum"),
            () -> assertEquals(9, Item.MISSILE.ordinal(), "ITEM_MISSILE is 9"),
            () -> assertEquals(20, Item.ARMOR.ordinal(), "ITEM_ARMOR is 20"),
            () -> assertSame(Item.ECM, Item.of(10), "looks up by id"),
            () -> assertSame(Item.FUEL, Item.of(0), "id 0 is FUEL"),
            () -> assertNull(Item.of(21), "no item above 20"),
            () -> assertNull(Item.of(-1), "no item below 0"),
            () -> assertEquals(1 << 9, Item.MISSILE.bit(), "bit is 1 << id"));
    }
}
