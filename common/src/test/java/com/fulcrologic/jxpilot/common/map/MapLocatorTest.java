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
package com.fulcrologic.jxpilot.common.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MapLocator")
class MapLocatorTest {
    static final BundledMaps BUNDLE = BundledMaps.of(List.of("CAMD.xp", "doggy.xp", "war.xp"));

    @Nested
    @DisplayName("isPlainName")
    class PlainName {
        @Test
        @DisplayName("a plain name has no slash, backslash or drive letter")
        void plain() {
            assertAll(
                () -> assertTrue(MapLocator.isPlainName("doggy"), "a bare name"),
                () -> assertTrue(MapLocator.isPlainName("doggy.xp"), "a file name"),
                () -> assertTrue(MapLocator.isPlainName("my map.xp"), "spaces are part of a name"),
                () -> assertTrue(MapLocator.isPlainName("1:x"), "a digit before a colon is not a drive"),
                () -> assertTrue(MapLocator.isPlainName(":x"), "a leading colon is not a drive"),
                () -> assertTrue(MapLocator.isPlainName("ab:x"), "a colon after two letters is not a drive"),
                () -> assertTrue(MapLocator.isPlainName("d"), "one letter"),
                () -> assertFalse(MapLocator.isPlainName("maps/doggy.xp"), "a slash makes a path"),
                () -> assertFalse(MapLocator.isPlainName("./doggy"), "./ makes a path"),
                () -> assertFalse(MapLocator.isPlainName("/abs.xp"), "an absolute path"),
                () -> assertFalse(MapLocator.isPlainName("maps\\doggy.xp"), "a backslash makes a path"),
                () -> assertFalse(MapLocator.isPlainName("\\\\server\\maps\\x.xp"), "a leading backslash (UNC path)"),
                () -> assertFalse(MapLocator.isPlainName("C:doggy.xp"), "an upper-case drive letter"),
                () -> assertFalse(MapLocator.isPlainName("z:doggy"), "a lower-case drive letter"));
        }

        @Test
        @DisplayName("only an ASCII letter before the colon is a drive")
        void driveLetterBounds() {
            assertAll(
                () -> assertTrue(MapLocator.isPlainName("@:x"), "'@' (before 'A') is not a drive letter"),
                () -> assertTrue(MapLocator.isPlainName("[:x"), "'[' (after 'Z') is not a drive letter"),
                () -> assertTrue(MapLocator.isPlainName("`:x"), "'`' (before 'a') is not a drive letter"),
                () -> assertTrue(MapLocator.isPlainName("{:x"), "'{' (after 'z') is not a drive letter"),
                () -> assertFalse(MapLocator.isPlainName("A:x"), "'A' is"),
                () -> assertFalse(MapLocator.isPlainName("Z:x"), "'Z' is"),
                () -> assertFalse(MapLocator.isPlainName("a:x"), "'a' is"),
                () -> assertFalse(MapLocator.isPlainName("Z:"), "a drive alone is a path"));
        }
    }

    @Nested
    @DisplayName("candidates")
    class Candidates {
        @Test
        @DisplayName("C openMapFile order: as given, then .xp, then .map, unless it already has one")
        void order() {
            assertAll(
                () -> assertEquals(List.of("m", "m.xp", "m.map"), MapLocator.candidates("m"), "no extension: three"),
                () -> assertEquals(List.of("m.xp"), MapLocator.candidates("m.xp"), ".xp: as given only"),
                () -> assertEquals(List.of("d/m.map"), MapLocator.candidates("d/m.map"), ".map: as given only"),
                () -> assertEquals(List.of("m.XP", "m.XP.xp", "m.XP.map"), MapLocator.candidates("m.XP"),
                    "the extension test is case-sensitive, as C's strcmp"));
        }
    }

    @Nested
    @DisplayName("resolve")
    class Resolve {
        @Test
        @DisplayName("a plain name is a bundled map: with or without .xp, in any case")
        void bundled(@TempDir Path dir) throws IOException {
            assertAll(
                () -> assertEquals(MapSource.bundled("doggy.xp"), MapLocator.resolve("doggy", BUNDLE, dir),
                    "the name without extension"),
                () -> assertEquals(MapSource.bundled("doggy.xp"), MapLocator.resolve("doggy.xp", BUNDLE, dir),
                    "the file name"),
                () -> assertEquals(MapSource.bundled("CAMD.xp"), MapLocator.resolve("camd", BUNDLE, dir),
                    "ignoring case"));
        }

        @Test
        @DisplayName("a bundled map wins over a file of the same name in the directory")
        void bundledFirst(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("doggy.xp"), "mapName: local\n");
            Files.writeString(dir.resolve("doggy"), "mapName: local\n");
            assertAll(
                () -> assertEquals(MapSource.bundled("doggy.xp"), MapLocator.resolve("doggy", BUNDLE, dir),
                    "the plain name is the bundled map"),
                () -> assertEquals(MapSource.bundled("doggy.xp"), MapLocator.resolve("doggy.xp", BUNDLE, dir),
                    "the plain file name is the bundled map"),
                () -> assertEquals(MapSource.of(dir.resolve("./doggy.xp")),
                    MapLocator.resolve(dir.resolve("./doggy.xp").toString(), BUNDLE, Path.of("")),
                    "a path reaches the file"));
        }

        @Test
        @DisplayName("a plain name that is not bundled is a file in the directory: as given, then .xp, then .map")
        void currentDirectory(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("mine.xp"), "");
            Files.writeString(dir.resolve("old.map"), "");
            Files.writeString(dir.resolve("both"), "");
            Files.writeString(dir.resolve("both.xp"), "");
            Files.createDirectory(dir.resolve("folder"));
            Files.writeString(dir.resolve("folder.xp"), "");
            assertAll(
                () -> assertEquals(MapSource.of(dir.resolve("mine.xp")), MapLocator.resolve("mine.xp", BUNDLE, dir),
                    "the file name as given"),
                () -> assertEquals(MapSource.of(dir.resolve("mine.xp")), MapLocator.resolve("mine", BUNDLE, dir),
                    "with .xp added"),
                () -> assertEquals(MapSource.of(dir.resolve("old.map")), MapLocator.resolve("old", BUNDLE, dir),
                    "with .map added"),
                () -> assertEquals(MapSource.of(dir.resolve("both")), MapLocator.resolve("both", BUNDLE, dir),
                    "the name as given comes before name.xp"),
                () -> assertEquals(MapSource.of(dir.resolve("folder.xp")), MapLocator.resolve("folder", BUNDLE, dir),
                    "a directory is not a map file"));
        }

        @Test
        @DisplayName("a path is only looked up on the file system")
        void pathsAreFiles(@TempDir Path dir) throws IOException {
            Files.createDirectory(dir.resolve("sub"));
            Files.writeString(dir.resolve("sub").resolve("doggy.xp"), "");
            String missing = "sub/war";
            FileNotFoundException e = assertThrows(FileNotFoundException.class,
                () -> MapLocator.resolve(missing, BUNDLE, dir), "a path to nothing does not fall back to the bundle");
            assertAll(
                () -> assertEquals(MapSource.of(dir.resolve("sub/doggy.xp")), MapLocator.resolve("sub/doggy", BUNDLE, dir),
                    "a relative path, with .xp added"),
                () -> assertFalse(MapLocator.resolve("sub/doggy", BUNDLE, dir).isBundled(),
                    "a path is never the bundled map of that name"),
                () -> assertEquals(MapSource.of(dir.resolve("sub/doggy.xp")),
                    MapLocator.resolve(dir.resolve("sub/doggy.xp").toString(), BUNDLE, Path.of("elsewhere")),
                    "an absolute path ignores the directory"),
                () -> assertEquals("map 'sub/war' not found: no file " + dir.resolve("sub/war") + ", "
                        + dir.resolve("sub/war.xp") + ", " + dir.resolve("sub/war.map")
                        + ". Bundled maps: CAMD, doggy, war (--list-maps describes them).", e.getMessage(),
                    "the error names the files tried and the bundled maps"));
        }

        @Test
        @DisplayName("an unknown plain name is an error listing the files tried and the bundled maps")
        void unknown() {
            FileNotFoundException cwd = assertThrows(FileNotFoundException.class,
                () -> MapLocator.resolve("nope", BUNDLE, Path.of("")), "unknown in the current directory");
            FileNotFoundException dir = assertThrows(FileNotFoundException.class,
                () -> MapLocator.resolve("nope.xp", BUNDLE, Path.of("some", "dir")), "unknown in a directory");
            assertAll(
                () -> assertEquals("map 'nope' not found: it is not a bundled map, and the current directory has no"
                        + " file nope, nope.xp, nope.map. Bundled maps: CAMD, doggy, war (--list-maps describes them).",
                    cwd.getMessage(), "says it is not bundled, what was tried here, and the bundled names"),
                () -> assertEquals("map 'nope.xp' not found: it is not a bundled map, and " + Path.of("some", "dir")
                        + " has no file " + Path.of("some", "dir", "nope.xp")
                        + ". Bundled maps: CAMD, doggy, war (--list-maps describes them).",
                    dir.getMessage(), "names the directory looked in"));
        }

        @Test
        @DisplayName("resolve(spec) uses the bundled maps on the class path and the current directory")
        void standard() throws IOException {
            assertAll(
                () -> assertEquals(MapSource.bundled("tourmination.xp"), MapLocator.resolve("Tourmination"),
                    "a bundled name"),
                () -> assertEquals(MapSource.of(Path.of("..", "maps", "doggy.xp")),
                    MapLocator.resolve(Path.of("..", "maps", "doggy").toString()), "a relative path from here"));
        }
    }
}
