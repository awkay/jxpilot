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

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C fidelity of ShipShape parsing (shipshape.c shape2wire), the no-limits variant and the wire string. */
@DisplayName("ShipShape C fidelity, convert and wire string")
class ShipShapeTest {
    static final String TRIANGLE = "(SH: 15,0 -9,8 -9,-8)";
    static final String DEFAULT_WIRE = "(SH: 15,0 -9,8 -9,-8)(EN: -9,0)(MG: 15,0)(LL: -9,8)(RL: -9,-8)(MR: 15,0)";

    /** Every group except MR of the maximal shape below: 24 points and 3 of each special point. */
    static final String MAX_PREFIX = "(SH: 15,15 -15,15 -15,-1 -15,-2 -15,-3 -15,-4 -15,-5 -15,-6 -15,-7 -15,-8 -15,-9"
        + " -15,-10 -15,-11 -15,-12 -15,-13 -15,-14 -15,-15 -14,-15 -13,-15 -12,-15 -11,-15 -10,-15 -9,-15 15,-15)"
        + "(EN: -11,-10)(MG: -10,-10)(LG: -10,-10 -11,-11 -12,-12)";
    static final String MAX_EXT = "(RG: -10,-11 -11,-12 -12,-13)(LR: -13,-10 -13,-11 -13,-12)"
        + "(RR: -14,-10 -14,-11 -14,-12)(LL: -10,-12 -10,-13 -10,-14)(RL: -11,-13 -11,-14 -12,-14)";
    static final String MAX_MR = "(MR: -12,-10 -12,-11 -13,-13 -13,-14)";
    /** A maximal valid shape, in a scrambled group order to show the canonical order. */
    static final String MAXIMAL = MAX_EXT + MAX_MR + MAX_PREFIX;

    /** The geometry of a shape (everything but name and author, which the wire string does not carry). */
    static String geometry(ShipShape s) {
        return Arrays.toString(s.ptX) + Arrays.toString(s.ptY) + " en " + s.engineX + "," + s.engineY
            + " mg " + s.mainGunX + "," + s.mainGunY
            + " lg " + Arrays.toString(s.leftGunX) + Arrays.toString(s.leftGunY)
            + " rg " + Arrays.toString(s.rightGunX) + Arrays.toString(s.rightGunY)
            + " lr " + Arrays.toString(s.leftRearGunX) + Arrays.toString(s.leftRearGunY)
            + " rr " + Arrays.toString(s.rightRearGunX) + Arrays.toString(s.rightRearGunY)
            + " ll " + Arrays.toString(s.leftLightX) + Arrays.toString(s.leftLightY)
            + " rl " + Arrays.toString(s.rightLightX) + Arrays.toString(s.rightLightY)
            + " mr " + Arrays.toString(s.missileRackX) + Arrays.toString(s.missileRackY);
    }

    @Nested
    @DisplayName("parse follows C shape2wire")
    class Fidelity {
        @Test
        @DisplayName("a keyword's colon may lie at offset 19 at most (C char keyw[20])")
        void keywordLength() {
            String at19 = "(abcdefghijklmnopqrs:" + TRIANGLE + ")";
            String at20 = "(abcdefghijklmnopqrst:" + TRIANGLE + ")";
            String unknown18 = "(abcdefghijklmnopqr:" + TRIANGLE + ")";
            assertAll(
                () -> assertNull(ShipShape.parse(at19),
                    "a colon at offset 19 ends an (unknown) keyword whose value swallows the nested shape"),
                () -> assertNull(ShipShape.parse(unknown18), "an unknown keyword with its colon at 18 is skipped"),
                () -> assertNotNull(ShipShape.parse(at20),
                    "a colon at offset 20 is no keyword: the next '(' is tried and the shape is read"));
        }

        @Test
        @DisplayName("the 3.1 header is C sscanf(\"(%d,%d,%d)\")")
        void oldHeader() {
            String pts = "(15,0)(-9,8)(-9,-8)";
            ShipShape trailing = ShipShape.parse("(3,1,2,7)" + pts);
            ShipShape unclosed = ShipShape.parse("(3,1,2" + pts);
            ShipShape spaced = ShipShape.parse("(3, 1,\t2 )" + pts);
            assertAll(
                () -> assertNotNull(trailing, "text after the third number is not checked"),
                () -> assertArrayEquals(new int[] {-9}, trailing.rightLightX, "the third number is the right light index"),
                () -> assertNotNull(unclosed, "the header's ')' is not needed"),
                () -> assertNotNull(spaced, "whitespace before a number is skipped"),
                () -> assertNull(ShipShape.parse("(3 ,1,2)" + pts), "whitespace before a comma fails the header"),
                () -> assertNull(ShipShape.parse("(3,1,x)" + pts), "a missing number fails the header"),
                () -> assertNull(ShipShape.parse("(3,1)" + pts), "two numbers fail the header"),
                () -> assertNull(ShipShape.parse("x(3,1,2)" + pts), "the header must start the string"),
                () -> assertNull(ShipShape.parse("(3"), "a header ending after its first number fails"),
                () -> assertNull(ShipShape.parse("(3,1,"), "a header ending before its third number fails"));
        }

        @Test
        @DisplayName("digits and whitespace are C's (ASCII digits, isspace)")
        void cCharacterClasses() {
            assertAll(
                () -> assertNull(ShipShape.parse("(٣,1,2)(15,0)(-9,8)(-9,-8)"),
                    "a non-ASCII digit after the first '(' is no 3.1 header (C isdigit)"),
                () -> assertNotNull(ShipShape.parse("(\u0663" + "x".repeat(22) + ")" + TRIANGLE),
                    "after a non-ASCII digit the 3.2 keyword scan goes on to the next '('"),
                () -> assertNull(ShipShape.parse("(SH: 15,\u001C0 -9,8 -9,-8)"),
                    "U+001C is no C whitespace before a number"),
                () -> assertNotNull(ShipShape.parse("(SH: 15,\u000B0 -9,8 -9,-8)"),
                    "a vertical tab is C whitespace before a number"),
                () -> assertEquals(15, ShipShape.parse(TRIANGLE + "(MG:\u001C0,0)").mainGunX,
                    "a main gun after U+001C is unreadable: the default (foremost point) is used"),
                () -> assertEquals(0, ShipShape.parse(TRIANGLE + "(MG:\t0,0)").mainGunX,
                    "a main gun after a tab is read"),
                () -> assertEquals(-8, ShipShape.parse(TRIANGLE + "(EN:\n-8,0)").engineX, "an engine after a newline is read"),
                () -> assertEquals(-8, ShipShape.parse(TRIANGLE + "(EN:\r-8,0)").engineX,
                    "an engine after a carriage return is read"),
                () -> assertEquals(-9, ShipShape.parse(TRIANGLE + "(EN:\u000E-8,0)").engineX,
                    "U+000E (after CR) is no C whitespace"),
                () -> assertEquals(-9, ShipShape.parse(TRIANGLE + "(EN:\u0008-8,0)").engineX,
                    "backspace (before tab) is no C whitespace"),
                () -> assertEquals(-9, ShipShape.parse(TRIANGLE + "(EN:\u001C-8,0)").engineX,
                    "an engine after U+001C is unreadable: the default is used"));
        }
    }

    @Nested
    @DisplayName("convert is C Convert_shape_str (no limits)")
    class Convert {
        @Test
        @DisplayName("empty, null and invalid strings give the default ship")
        void defaults() {
            ShipShape d = ShipShape.defaultShip();
            assertAll(
                () -> assertSame(d, ShipShape.convert(""), "empty gives the default"),
                () -> assertSame(d, ShipShape.convert(null), "null gives the default"),
                () -> assertSame(d, ShipShape.convert("(SH: 15,0 -9,8)"), "fewer than 3 points give the default"),
                () -> assertSame(d, ShipShape.convert("myship"), "a string without '(' gives the default"),
                () -> assertSame(d, ShipShape.convert("(3,1,5)(15,0)(-9,8)(-9,-8)"), "a bad 3.1 header gives the default"));
        }

        @Test
        @DisplayName("skips the size, width and inside-the-hull checks")
        void noLimits() {
            String tiny = "(SH: 1,1 2,2 3,3)";
            String chevron = "(SH: 15,0 -9,8 0,0 -9,-8)";
            ShipShape oldChevron = ShipShape.convert("(4,1,3)(15,0)(-9,8)(0,0)(-9,-8)");
            ShipShape big = ShipShape.convert("(SH: 100001,0 -9,8 -9,-8)(MG: 2147483648,0)");
            assertAll(
                () -> assertNull(ShipShape.parse(tiny), "parse rejects a tiny ship"),
                () -> assertArrayEquals(new int[] {1, 2, 3}, ShipShape.convert(tiny).ptX, "convert keeps a tiny ship"),
                () -> assertNull(ShipShape.parse(chevron), "parse rejects an engine in the notch"),
                () -> assertEquals(-9, ShipShape.convert(chevron).engineX, "convert keeps the engine in the notch"),
                () -> assertEquals(-9, oldChevron.engineX, "convert never moves an old-format engine"),
                () -> assertEquals(0, ShipShape.parse("(4,1,3)(15,0)(-9,8)(0,0)(-9,-8)").engineX,
                    "while parse moves it onto the hull"),
                () -> assertEquals(100001, big.ptX[0], "numbers are not clamped"),
                () -> assertEquals(Integer.MIN_VALUE, big.mainGunX,
                    "a number beyond int keeps its low 32 bits, as glibc stores a long into an int"),
                () -> assertEquals(Integer.MAX_VALUE, ShipShape.convert(TRIANGLE + "(MG: 2147483647,0)").mainGunX,
                    "the largest int is read exactly"),
                () -> assertEquals(-8, ShipShape.convert(TRIANGLE + "(MG: 9223372036854775800,0)").mainGunX,
                    "a number just below LONG_MAX is read exactly (low 32 bits -8)"),
                () -> assertEquals(-1, ShipShape.convert(TRIANGLE + "(MG: 9223372036854775807,0)").mainGunX,
                    "LONG_MAX itself is read (low 32 bits -1)"),
                () -> assertEquals(-1, ShipShape.convert(TRIANGLE + "(MG: 99999999999999999999,0)").mainGunX,
                    "a number beyond long saturates to LONG_MAX (low bits -1), as strtol"),
                () -> assertEquals(0, ShipShape.convert(TRIANGLE + "(MG: -99999999999999999999,0)").mainGunX,
                    "a negative number beyond long saturates to LONG_MIN (low bits 0)"));
        }

        @Test
        @DisplayName("reads valid shapes as parse does")
        void sameAsParse() {
            ShipShape tank = ShipShape.convert("(NM:fueltank)(SH: 15,0 14,-5 9,-8 -5,-8 -3,-8 -3,0 2,0 2,2 -3,2 -3,6"
                + " 5,6 5,8 -5,8 -5,-8 -9,-8 -14,-5 -15,0 -14,5 -9,8 9,8 14,5)(EN: -15,0)(MG: 15,0)");
            assertAll(
                () -> assertEquals(geometry(ShipShape.parse(MAXIMAL)), geometry(ShipShape.convert(MAXIMAL)),
                    "the maximal shape reads the same"),
                () -> assertEquals(21, tank.numPoints(), "the tank shape reads"),
                () -> assertEquals("fueltank", tank.name, "the name is read"));
        }
    }

    @Nested
    @DisplayName("toWireString is C Convert_ship_2_string (3.2+)")
    class Wire {
        @Test
        @DisplayName("the default ship and a full shape in canonical group order")
        void canonical() {
            ShipShape full = ShipShape.parse("(MR: 10,0 5,0)(RL: -8,-7)(LL: -8,7)(RR: -8,-1)(LR: -8,1)(RG: 0,-2)"
                + "(LG: 0,2 0,3)(MG: 14,0)(EN: -8,0)(SH: 15,0 -9,8 -9,-8)(NM:x)(AU:y)");
            assertAll(
                () -> assertEquals(DEFAULT_WIRE, ShipShape.defaultShip().toWireString(), "the default ship"),
                () -> assertEquals(new ShipShape.WireParts(DEFAULT_WIRE, ""), ShipShape.defaultShip().toWireParts(),
                    "a short shape has no extension"),
                () -> assertEquals("(SH: 15,0 -9,8 -9,-8)(EN: -8,0)(MG: 14,0)(LG: 0,2 0,3)(RG: 0,-2)(LR: -8,1)"
                    + "(RR: -8,-1)(LL: -8,7)(RL: -8,-7)(MR: 10,0 5,0)", full.toWireString(),
                    "SH, EN, MG, then LG RG LR RR LL RL MR; no name or author"),
                () -> assertEquals("(SH: 15,0 -9,8 -9,-8)(EN: -9,0)(MG: 15,0)(LL: -9,8)(RL: -9,-8)(MR: 15,0)",
                    ShipShape.parse("(3,1,2)(15,0)(-9,8)(-9,-8)").toWireString(),
                    "an old-format shape gets the 3.2 format with its defaults explicit"));
        }

        @Test
        @DisplayName("groups that overflow buf go to ext; a group fitting neither is dropped")
        void split() {
            ShipShape.WireParts max = ShipShape.parse(MAXIMAL).toWireParts();
            ShipShape.WireParts back = ShipShape.parse(MAX_PREFIX + MAX_EXT + "(MR: -12,-10)").toWireParts();
            String huge = " -2147483648,-2147483648".repeat(3) + ")";
            ShipShape.WireParts dropped = ShipShape.convert(TRIANGLE + "(LG:" + huge + "(RG:" + huge + "(LR:" + huge
                + "(RR:" + huge + "(LL:" + huge + "(RL:" + huge).toWireParts();
            assertAll(
                () -> assertEquals(new ShipShape.WireParts(MAX_PREFIX, MAX_EXT + MAX_MR), max,
                    "the maximal shape: buf full after LG (238 chars), the rest in ext"),
                () -> assertTrue(max.buf().length() < 256 && max.ext().length() < 256, "both halves stay below MSG_LEN"),
                () -> assertEquals(new ShipShape.WireParts(MAX_PREFIX + "(MR: -12,-10)", MAX_EXT), back,
                    "a later group that fits goes back into buf (buf 251 < 256)"),
                () -> assertEquals(expectedDropped(huge), dropped, "RL fits neither buf nor ext and is dropped"));
        }

        @Test
        @DisplayName("a half holds at most MSG_LEN - 1 = 255 chars (C buflen + tmplen < MSG_LEN)")
        void msgLenBoundaries() {
            String g = " -2147483648,-2147483648".repeat(3) + ")";
            String lr = "(LR: -2147483648,-2147483648 -2147483648,-2147483648 100,100)";
            String base = "(SH: 15,0 -9,8 -9,-8)(EN: -9,0)(MG: 15,0)";
            ShipShape.WireParts bufFull = ShipShape.convert(base + "(LG:" + g + "(RG:" + g + lr).toWireParts();
            String sh = "(SH: 15,0 -9,8 -9,-8" + " -1000,-100".repeat(12) + ")(EN: -9,0)(MG: 15,0)";
            String ll = "(LL: 1000,1000 1000,1000)";
            ShipShape.WireParts extFull = ShipShape.convert(sh + "(LG:" + g + "(RG:" + g + "(LR:" + g + "(RR:" + g + ll
                + "(RL: -1000,-100)(MR: 15,0)").toWireParts();
            assertAll(
                () -> assertEquals(256, base.length() + 2 * ("(LG:" + g).length() + lr.length(),
                    "(the first case's LR would make buf exactly 256 chars)"),
                () -> assertEquals(new ShipShape.WireParts(base + "(LG:" + g + "(RG:" + g + "(LL: -9,8)(RL: -9,-8)(MR: 15,0)",
                    lr), bufFull, "a group that would make buf 256 chars goes to ext"),
                () -> assertEquals(250, extFull.buf().length(), "(the second case fills buf to 250 chars)"),
                () -> assertEquals(256, 3 * ("(LG:" + g).length() + ll.length(), "(and LL would make ext exactly 256)"),
                () -> assertEquals(new ShipShape.WireParts(sh + "(LG:" + g,
                    "(RG:" + g + "(LR:" + g + "(RR:" + g + "(RL: -1000,-100)"), extFull,
                    "a group that would make ext 256 chars is dropped (LL), and so is MR (257)"));
        }

        /** The {@code dropped} case of {@link #split}: LG RG in buf (205), LR RR LL in ext (231), RL dropped, MR in buf. */
        static ShipShape.WireParts expectedDropped(String g) {
            return new ShipShape.WireParts("(SH: 15,0 -9,8 -9,-8)(EN: -9,0)(MG: 15,0)(LG:" + g + "(RG:" + g
                + "(MR: 15,0)", "(LR:" + g + "(RR:" + g + "(LL:" + g);
        }

        @Test
        @DisplayName("parse(toWireString(s)) has the geometry of s")
        void roundTrip() {
            ShipShape d = ShipShape.defaultShip();
            ShipShape max = ShipShape.parse(MAXIMAL);
            ShipShape tank = ShipShape.parse("(SH: 15,0 14,-5 9,-8 -5,-8 -3,-8 -3,0 2,0 2,2 -3,2 -3,6 5,6 5,8 -5,8"
                + " -5,-8 -9,-8 -14,-5 -15,0 -14,5 -9,8 9,8 14,5)(EN: -15,0)(MG: 15,0)");
            ShipShape old = ShipShape.parse("(4,1,3)(15,0)(-9,8)(0,0)(-9,-8)");
            ShipShape tiny = ShipShape.convert("(SH: 1,1 2,2 3,3)(LG: 7,7)");
            assertAll(
                () -> assertEquals(geometry(d), geometry(ShipShape.parse(d.toWireString())), "the default ship"),
                () -> assertEquals(geometry(max), geometry(ShipShape.parse(max.toWireString())), "the maximal shape"),
                () -> assertEquals(geometry(tank), geometry(ShipShape.parse(tank.toWireString())), "the tank shape"),
                () -> assertEquals(geometry(old), geometry(ShipShape.parse(old.toWireString())),
                    "an old-format shape with its moved engine"),
                () -> assertEquals(geometry(tiny), geometry(ShipShape.convert(tiny.toWireString())),
                    "a converted shape round-trips through convert"),
                () -> assertNotSame(d, ShipShape.parse(d.toWireString()), "(a new instance with the same geometry)"));
        }
    }
}
