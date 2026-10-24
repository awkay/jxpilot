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

import com.fulcrologic.jxpilot.client.render.FontSpec.Face;
import com.fulcrologic.jxpilot.client.render.FontSpec.Family;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FontSpecTest {
    /** The fallback every parse below gets: a size no value gives, so a fallback size is visible. */
    static final FontSpec FB = FontSpec.of(Face.SERIF_ITALIC, 21);

    static FontSpec spec(String v) {
        FontSpec.Result r = FontSpec.parse(v, FB);
        assertNull(r.problem(), "'" + v + "' parses without a problem");
        return r.spec();
    }

    static String problem(String v) {
        FontSpec.Result r = FontSpec.parse(v, FB);
        assertSame(FB, r.spec(), "'" + v + "' falls back");
        return r.problem();
    }

    @Nested
    @DisplayName("XLFD names")
    class Xlfd {
        @Test
        @DisplayName("C's default font resources (default.c) map to the Liberation faces")
        void cDefaults() {
            assertAll(
                () -> assertEquals(FontSpec.of(Face.SERIF_REGULAR, 18), spec("-*-times-*-*-*--18-*-*-*-*-*-iso8859-1"),
                    "gameFont: Times 18 is Serif Regular 18"),
                () -> assertEquals(FontSpec.of(Face.SERIF_REGULAR, 14), spec("-*-times-*-*-*--14-*-*-*-*-*-iso8859-1"),
                    "messageFont: Serif 14"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 13), spec("-*-fixed-bold-*-*--13-*-*-*-c-*-iso8859-1"),
                    "scoreListFont: fixed bold 13 is Mono Bold"),
                () -> assertEquals(FontSpec.of(Face.SANS_BOLD_ITALIC, 14), spec("-*-*-bold-o-*--14-*-*-*-*-*-iso8859-1"),
                    "buttonFont: any family, bold oblique, is Sans Bold Italic"),
                () -> assertEquals(FontSpec.of(Face.SERIF_BOLD_ITALIC, 14), spec("-*-*-bold-i-*--14-*-*-*-p-*-iso8859-1"),
                    "textFont: any family, bold italic proportional, is Serif Bold Italic"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 15), spec("-*-fixed-bold-*-*--15-*-*-*-c-*-iso8859-1"),
                    "talkFont: Mono Bold 15"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 14), spec("-*-courier-bold-r-*--14-*-*-*-*-*-iso8859-1"),
                    "motdFont: Courier bold is Mono Bold"),
                () -> assertEquals(FontSpec.of(Face.MONO_REGULAR, 10), spec("-*-fixed-medium-r-*--10-*-*-*-c-*-iso8859-1"),
                    "key list: fixed medium 10 is Mono Regular"));
        }

        @Test
        @DisplayName("families, weights and slants")
        void families() {
            assertAll(
                () -> assertEquals(Face.SANS_REGULAR, spec("-adobe-helvetica-medium-r-normal--12-*").face(), "helvetica"),
                () -> assertEquals(Face.SANS_BOLD, spec("-b&h-lucida-demibold-r-*--12-*").face(), "lucida demibold"),
                () -> assertEquals(Face.SERIF_BOLD_ITALIC, spec("-*-new century schoolbook-bold-i-*--12-*").face(),
                    "new century schoolbook bold italic"),
                () -> assertEquals(Face.MONO_REGULAR, spec("-*-clean-medium-r-*--12-*").face(), "clean"),
                () -> assertEquals(Face.MONO_BOLD, spec("-*-DejaVu Sans Mono-Bold-R-*--12-*").face(),
                    "mono wins over sans, any case"),
                () -> assertEquals(Face.SANS_REGULAR, spec("-*-sans serif-*-*-*--12-*").face(), "sans wins over serif"),
                () -> assertEquals(Face.SERIF_REGULAR, spec("-*-lucidabright-*-*-*--12-*").face(),
                    "lucidabright is a serif"),
                () -> assertEquals(Face.SERIF_ITALIC, spec("-*-utopia-medium-ri-*--12-*").face(), "reverse italic"),
                () -> assertEquals(Face.SERIF_ITALIC, spec("-*-charter-light-ro-*--12-*").face(), "reverse oblique"),
                () -> assertEquals(Face.SERIF_BOLD, spec("-*-palatino-black-r-*--12-*").face(), "black is bold"),
                () -> assertEquals(Face.SERIF_BOLD, spec("-*-bookman-heavy-R-*--12-*").face(), "heavy is bold; R upright"),
                () -> assertEquals(Face.SERIF_REGULAR, spec("-*-georgia-*-*-*--12-*").face(), "georgia"),
                () -> assertEquals(Face.SANS_REGULAR, spec("-*-arial-*-*-*--12-*").face(), "arial"),
                () -> assertEquals(Face.SANS_REGULAR, spec("-*-avantgarde-*-*-*--12-*").face(), "avantgarde"),
                () -> assertEquals(Face.SANS_REGULAR, spec("-*-gothic-*-*-*--12-*").face(), "gothic"),
                () -> assertEquals(Face.SANS_REGULAR, spec("-*-verdana-*-*-*--12-*").face(), "verdana"),
                () -> assertEquals(Face.MONO_REGULAR, spec("-*-lucidatypewriter-*-*-*--12-*").face(), "typewriter"),
                () -> assertEquals(Face.MONO_REGULAR, spec("-*-terminal-*-*-*--12-*").face(), "terminal"),
                () -> assertEquals(Face.MONO_BOLD, spec("-*-*-bold-i-*--12-*-*-*-m-*").face(),
                    "any family, spacing m: Mono (bold italic is Mono Bold)"),
                () -> assertEquals(Face.MONO_REGULAR, spec("-*-whatever-*-*-*--12-*-*-*-C-*").face(),
                    "unknown family, spacing C: Mono"),
                () -> assertEquals(Face.SERIF_REGULAR, spec("-*-whatever-*-*-*--12-*-*-*-p-*").face(),
                    "unknown family, proportional: Serif"),
                () -> assertEquals(Face.SANS_REGULAR, spec("-*-*-medium-o-*--12-*").face(),
                    "any family, oblique, spacing unspecified: Sans (italic falls back to upright)"));
        }

        @Test
        @DisplayName("sizes: pixel size, else point size / 10, else the fallback's")
        void sizes() {
            assertAll(
                () -> assertEquals(16f, spec("-*-times-*-*-*--16-140-*").size(), "pixel size wins"),
                () -> assertEquals(14f, spec("-*-times-*-*-*--*-140-*").size(), "point size 140 is 14 px"),
                () -> assertEquals(15f, spec("-*-times-*-*-*--0-145-*").size(), "pixel 0 (scalable): 14.5 rounds to 15"),
                () -> assertEquals(21f, spec("-*-times-*-*-*--*-*-*").size(), "both wildcards: the fallback size"),
                () -> assertEquals(21f, spec("-*-helvetica-bold").size(), "leading fields only: the fallback size"),
                () -> assertEquals(Face.SANS_BOLD, spec("-*-helvetica-bold").face(), "leading fields only still map"),
                () -> assertEquals(21f, spec("-*-times-*-*-*--12345-*").size(), "a five-digit size is junk"),
                () -> assertEquals(21f, spec("-*-times-*-*-*--1x-*").size(), "a non-number is junk"),
                () -> assertEquals(Face.SERIF_REGULAR, spec(" -*-times-*-*-*--18-* ").face(), "blanks around are ignored"));
        }

        @Test
        @DisplayName("too many fields or an out-of-range size fall back with a problem")
        void unusable() {
            assertAll(
                () -> assertEquals("not an XLFD font name", problem("-a-b-c-d-e-f-g-h-i-j-k-l-m-n-o"),
                    "15 fields after the dash"),
                () -> assertEquals("size 300 is outside 4..200", problem("-*-times-*-*-*--300-*"), "too large"),
                () -> assertEquals("size 3 is outside 4..200", problem("-*-times-*-*-*--3-*"), "too small"),
                () -> assertEquals(4f, spec("-*-times-*-*-*--4-*").size(), "4 is the smallest"),
                () -> assertEquals(200f, spec("-*-times-*-*-*--200-*").size(), "200 is the largest"),
                () -> assertEquals(Face.SERIF_REGULAR, spec("-a-b-c-d-e-f-g-h-i-j-k-l-m-n").face(), "14 fields are fine"));
        }
    }

    @Nested
    @DisplayName("files, bundled names and X aliases")
    class Named {
        @Test
        @DisplayName("TrueType paths, with an optional :SIZE")
        void paths() {
            assertAll(
                () -> assertEquals(new FontSpec(null, "/f/a.ttf", 21), spec("/f/a.ttf"), "absolute path, fallback size"),
                () -> assertEquals(new FontSpec(null, "/f/a.ttf", 16), spec("/f/a.ttf:16"), "path with a size"),
                () -> assertEquals(new FontSpec(null, "fonts\\b", 21), spec("fonts\\b"), "backslash separator"),
                () -> assertEquals(new FontSpec(null, "my.TTF", 21), spec("my.TTF"), ".TTF ending, any case"),
                () -> assertEquals(new FontSpec(null, "x.otf", 9), spec("x.otf:9"), ".otf"),
                () -> assertEquals(new FontSpec(null, "x.ttc", 21), spec("x.ttc"), ".ttc"),
                () -> assertEquals(new FontSpec(null, "C:\\f.ttf", 21), spec("C:\\f.ttf"),
                    "a drive colon is not a size"),
                () -> assertEquals("unknown font", problem("a.ttf:"), "an empty size is part of the name, no longer a .ttf"),
                () -> assertEquals("size 0 is outside 4..200", problem("a.ttf:0"), "size 0"));
        }

        @Test
        @DisplayName("bundled names, any case and spacing, with an optional :SIZE")
        void bundled() {
            assertAll(
                () -> assertEquals(FontSpec.of(Face.FIRA_CODE, 21), spec("FiraCode"), "Fira Code by family"),
                () -> assertEquals(FontSpec.of(Face.FIRA_CODE, 20), spec("firacode:20"), "lower case, sized"),
                () -> assertEquals(FontSpec.of(Face.FIRA_CODE, 21), spec("FiraCode-Regular"), "by file name"),
                () -> assertEquals(FontSpec.of(Face.SERIF_REGULAR, 21), spec("LiberationSerif"), "a family is its regular face"),
                () -> assertEquals(FontSpec.of(Face.SANS_BOLD, 21), spec("Liberation Sans-Bold"), "spaces ignored"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 11), spec("LiberationMono-Bold:11"), "sized face"),
                () -> assertEquals("unknown font", problem("LiberationSans-Italic"), "a face not bundled"),
                () -> assertEquals("unknown font", problem("FiraCode:12345"), "a five-digit size is part of the name"),
                () -> assertEquals("size 201 is outside 4..200", problem("FiraCode:201"), "too large"));
        }

        @Test
        @DisplayName("X aliases: fixed, WxH and WxHbold")
        void aliases() {
            assertAll(
                () -> assertEquals(FontSpec.of(Face.MONO_REGULAR, 13), spec("fixed"), "fixed is 6x13"),
                () -> assertEquals(FontSpec.of(Face.MONO_REGULAR, 20), spec("fixed:20"), "sized alias"),
                () -> assertEquals(FontSpec.of(Face.MONO_REGULAR, 15), spec("9x15"), "9x15"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 15), spec("9x15bold"), "9x15bold"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 13), spec("8x13B"), "8x13B"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 18), spec("7x13bold:18"), "sized bold alias"),
                () -> assertEquals("unknown font", problem("x15"), "no width"),
                () -> assertEquals("unknown font", problem("ax15"), "a non-number width"),
                () -> assertEquals("unknown font", problem("9x"), "no height"),
                () -> assertEquals("unknown font", problem("9xbold"), "bold without a height"),
                () -> assertEquals("unknown font", problem("9x1y"), "a non-number height"));
        }

        @Test
        @DisplayName("null, blank and unknown values fall back with a problem")
        void unknown() {
            assertAll(
                () -> assertEquals("no font given", problem(null), "null"),
                () -> assertEquals("no font given", problem("  "), "blank"),
                () -> assertEquals("unknown font", problem("Comic Sans"), "unknown name"));
        }
    }

    @Nested
    @DisplayName("faces and helpers")
    class Faces {
        @Test
        @DisplayName("nearest face, names, resources")
        void faces() {
            assertAll(
                () -> assertEquals(Face.SERIF_BOLD_ITALIC, Face.of(Family.SERIF, true, true), "exact face"),
                () -> assertEquals(Face.MONO_BOLD, Face.of(Family.MONO, true, true), "Mono bold italic: Mono Bold"),
                () -> assertEquals(Face.MONO_REGULAR, Face.of(Family.MONO, false, true), "Mono italic: Mono Regular"),
                () -> assertEquals(Face.SANS_REGULAR, Face.of(Family.SANS, false, true), "Sans italic: Sans Regular"),
                () -> assertNull(Face.byName("Liberation"), "a partial name is nothing"),
                () -> assertEquals("/fonts/LiberationSerif-Regular.ttf", Face.SERIF_REGULAR.resource(), "resource path"),
                () -> assertEquals("/fonts/FiraCode-Regular.ttf", Face.FIRA_CODE.resource(), "Fira Code resource"));
        }

        @Test
        @DisplayName("equal specs share the first one's bake; withSize keeps the face or file")
        void sharing() {
            FontSpec a = FontSpec.of(Face.SERIF_REGULAR, 14);
            FontSpec b = FontSpec.of(Face.MONO_BOLD, 13);
            FontSpec file = new FontSpec(null, "a.ttf", 14);
            assertAll(
                () -> assertArrayEquals(new int[] {0, 1, 0, 3, 1},
                    FontSpec.shareIndex(new FontSpec[] {a, b, FontSpec.of(Face.SERIF_REGULAR, 14), file, b}),
                    "an equal spec points at the first; others at themselves"),
                () -> assertArrayEquals(new int[0], FontSpec.shareIndex(new FontSpec[0]), "no specs"),
                () -> assertEquals(FontSpec.of(Face.SERIF_REGULAR, 9), a.withSize(9), "face kept"),
                () -> assertEquals(new FontSpec(null, "a.ttf", 9), file.withSize(9), "file kept"));
        }
    }
}
