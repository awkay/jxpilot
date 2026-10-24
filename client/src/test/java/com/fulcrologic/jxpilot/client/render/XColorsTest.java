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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XColorsTest {
    static long rgb(int r, int g, int b) {
        return Palette.pack(r, g, b, 255) & 0xFFFFFFFFL;
    }

    @Nested
    class Hash {
        @Test
        void digitsAreTheHighBitsOfEachComponent() {
            assertAll(
                () -> assertEquals(rgb(0xF0, 0xA0, 0x00), XColors.parse("#fa0"), "#RGB: one digit is the top 4 bits, not repeated"),
                () -> assertEquals(rgb(0x4E, 0x7C, 0xFF), XColors.parse("#4E7CFF"), "#RRGGBB as given"),
                () -> assertEquals(rgb(0x4E, 0x7C, 0xFF), XColors.parse("#4E07C0FF0"), "#RRRGGGBBB: top 8 of 12 bits"),
                () -> assertEquals(rgb(0xFF, 0xBB, 0x11), XColors.parse("#FFFFBBBB1111"), "#RRRRGGGGBBBB: top 8 of 16 bits"),
                () -> assertEquals(rgb(0x12, 0x34, 0x56), XColors.parse("  #123456 "), "blanks around the spec are ignored"),
                () -> assertEquals(XColors.INVALID, XColors.parse("#12345"), "5 digits: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse("#"), "no digits: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse("#1234567890abc"), "13 digits: not a multiple of 3"),
                () -> assertEquals(XColors.INVALID, XColors.parse("#123456789abcdef"), "15 digits: more than 12"),
                () -> assertEquals(XColors.INVALID, XColors.parse("#12g"), "a non-hex digit: invalid"));
        }
    }

    @Nested
    class Rgb {
        @Test
        void fieldsAreScaledToTheFullRange() {
            assertAll(
                () -> assertEquals(rgb(0xFF, 0xAA, 0x00), XColors.parse("rgb:f/a/0"), "1 digit: f is full, a is 0xaaaa"),
                () -> assertEquals(rgb(0x12, 0x34, 0x56), XColors.parse("RGB:12/34/56"), "2 digits as is; prefix in any case"),
                () -> assertEquals(rgb(0x80, 0x00, 0xFF), XColors.parse("rgb:800/000/fff"), "3 digits: 0x800 * 65535 / 4095"),
                () -> assertEquals(rgb(0x12, 0xAB, 0xFF), XColors.parse("rgb:1234/abcd/ffff"), "4 digits: top byte"),
                () -> assertEquals(XColors.INVALID, XColors.parse("rgb:1/2"), "two fields: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse("rgb:1/2/3/4"), "four fields: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse("rgb:12345/0/0"), "5 digits: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse("rgb://0"), "an empty field: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse("rgb:x/0/0"), "a non-hex field: invalid"));
        }

        @Test
        void rgbiFieldsAreFractions() {
            assertAll(
                () -> assertEquals(rgb(0xFF, 0x80, 0x00), XColors.parse("rgbi:1.0/0.5/0"), "0.5 is (int)(0.5 * 65535 + 0.5) = 0x8000, top byte 0x80"),
                () -> assertEquals(XColors.INVALID, XColors.parse("rgbi:1.5/0/0"), "above 1: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse("rgbi:-0.1/0/0"), "below 0: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse("rgbi:a/0/0"), "not a number: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse("rgbi:1/0"), "two fields: invalid"));
        }
    }

    @Nested
    class Names {
        @Test
        void namesComeFromTheBundledRgbTxtIgnoringCaseAndBlanks() {
            assertAll(
                () -> assertEquals(rgb(255, 165, 0), XColors.parse("orange"), "orange"),
                () -> assertEquals(rgb(248, 248, 255), XColors.parse("Ghost White"), "a name with a blank"),
                () -> assertEquals(rgb(248, 248, 255), XColors.parse("GHOSTWHITE"), "case and blanks ignored"),
                () -> assertEquals(rgb(0, 0, 0), XColors.parse("black"), "black"),
                () -> assertEquals(XColors.INVALID, XColors.parse("notacolour"), "an unknown name: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse(""), "empty: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse("   "), "blank: invalid"),
                () -> assertEquals(XColors.INVALID, XColors.parse(null), "null: invalid"));
        }

        @Test
        void parseNamesSkipsCommentsAndKeepsTheFirstDefinition() {
            Map<String, Integer> m = XColors.parseNames("! comment\n# other\n\n1 2 3 Dark Thing\n4 5 6 darkthing\n"
                + "bad line here\n7 8 x broken\n9 9 short\n");
            assertAll(
                () -> assertEquals(Map.of("darkthing", Palette.pack(1, 2, 3, 255)), m,
                    "only the first darkthing; comments, blank, non-numeric and three-field lines skipped"),
                () -> assertEquals("ab", XColors.key("A\tb"), "tabs are blanks too"));
        }
    }
}
