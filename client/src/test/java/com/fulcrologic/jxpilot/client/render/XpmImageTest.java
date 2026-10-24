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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class XpmImageTest {
    static final int RED = Palette.pack(255, 0, 0, 255);
    static final int BLUE = Palette.pack(0, 0, 255, 255);

    static String xpm(String... strings) {
        StringBuilder b = new StringBuilder("/* XPM */\nstatic char *x[] = {\n/* values */\n");
        for (String s : strings) {
            b.append('"').append(s).append("\",\n");
        }
        return b.append("};\n").toString();
    }

    @Nested
    class Parse {
        @Test
        void readsSizeColoursAndRowsTopFirst() {
            XpmImage i = XpmImage.parse(xpm("3 2 3 1", "r c #FF0000", "b c blue", ". c None", "rb.", "..r"));
            assertAll(
                () -> assertEquals(3, i.width, "width"),
                () -> assertEquals(2, i.height, "height"),
                () -> assertArrayEquals(new int[] {RED, BLUE, 0, 0, 0, RED}, i.pixels,
                    "row 0 first; None is transparent 0; names and # specs parse"));
        }

        @Test
        void twoCharactersPerPixelAndHotspotFieldsAreRead() {
            XpmImage i = XpmImage.parse(xpm("2 1 2 2 0 0", "aa c #ff0000", "ab c #0000ff", "abaa"));
            assertArrayEquals(new int[] {BLUE, RED}, i.pixels, "pixels are cpp-character keys; extra values ignored");
        }

        @Test
        void commentsBetweenStringsAreSkipped() {
            String t = "/* XPM */\nstatic char *x[] = {\n\"1 1 1 1\",\n/* \"9 9 9 9\" */\n\"x c #0000ff\",\n\"x\"};";
            assertArrayEquals(new int[] {BLUE}, XpmImage.parse(t).pixels, "a quoted string inside a comment is not a line");
        }

        @Test
        void badInputIsReported() {
            assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> XpmImage.parse("\"1 1 1 1\"\n\"x c red\"\n\"x\""),
                    "no XPM header"),
                () -> assertThrows(IllegalArgumentException.class, () -> XpmImage.parse("/* XPM */"), "no strings"),
                () -> assertThrows(IllegalArgumentException.class, () -> XpmImage.parse(xpm("1 1 1")), "short values line"),
                () -> assertThrows(IllegalArgumentException.class, () -> XpmImage.parse(xpm("a 1 1 1", "x c red", "x")),
                    "non-numeric values"),
                () -> assertThrows(IllegalArgumentException.class, () -> XpmImage.parse(xpm("0 1 1 1", "x c red", "")),
                    "zero width"),
                () -> assertThrows(IllegalArgumentException.class, () -> XpmImage.parse(xpm("1 2 1 1", "x c red", "x")),
                    "too few rows"),
                () -> assertThrows(IllegalArgumentException.class, () -> XpmImage.parse(xpm("2 1 1 1", "x c red", "x")),
                    "short row"),
                () -> assertThrows(IllegalArgumentException.class, () -> XpmImage.parse(xpm("1 1 1 1", "x c red", "y")),
                    "undefined colour"),
                () -> assertThrows(IllegalArgumentException.class, () -> XpmImage.parse(xpm("1 1 1 2", "x", "xx")),
                    "colour line shorter than cpp"),
                () -> assertThrows(IllegalArgumentException.class, () -> XpmImage.parse(xpm("1 1 1 1", "x c nonesuch", "x")),
                    "unparsable colour"));
        }
    }

    @Nested
    class Color {
        @Test
        void prefersTheColourKeyThenGreyThenMono() {
            assertAll(
                () -> assertEquals(BLUE, XpmImage.color("\ts topShadowColor m white c #0000ff"),
                    "c wins over m; the symbolic name s is ignored"),
                () -> assertEquals(Palette.pack(255, 255, 255, 255), XpmImage.color(" m white"), "only m: mono"),
                () -> assertEquals(RED, XpmImage.color(" g4 red m blue"), "g4 before m"),
                () -> assertEquals(BLUE, XpmImage.color(" g blue g4 red"), "g before g4"),
                () -> assertEquals(Palette.pack(248, 248, 255, 255), XpmImage.color(" c ghost white"),
                    "a value may span words"),
                () -> assertEquals(0, XpmImage.color(" c NONE"), "None in any case is transparent"),
                () -> assertThrows(IllegalArgumentException.class, () -> XpmImage.color(" s name"), "no colour key"));
        }

        @Test
        void stringsAreTheQuotedTexts() {
            assertEquals(List.of("a", "b c"), XpmImage.strings("x \"a\" /* \"no\" */ \"b c\" \"unterminated"),
                "comment skipped, unterminated tail dropped");
        }
    }
}
