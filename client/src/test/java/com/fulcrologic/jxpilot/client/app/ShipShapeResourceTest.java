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
package com.fulcrologic.jxpilot.client.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("shipShape / shipShapeFile resources (C Get_shipshape_resource)")
class ShipShapeResourceTest {
    static final String DART = "dart (NM: dart)(SH: 15,0 -9,8 -9,-8)(MG: 15,0)\n";
    static final String FILE = "# my ships\n"
        + "(name:arrow)(SH: 15,0 -9,9 -9,-9)\n"
        + DART
        + "(NM: dart)(SH: 14,0 -9,8 -9,-8)\n"
        + "(NM:open\n"
        + "(NM:trailing )(SH: 15,0 -9,8 -9,-8)\n"
        + "(NM: both)(name:named)(SH: 15,0 -9,8 -9,-8)";

    @Nested
    @DisplayName("lookup / nameOf")
    class Lookup {
        @Test
        @DisplayName("the first line naming the shape is returned whole, newline included")
        void lookup() {
            assertAll(
                () -> assertEquals(DART, ShipShapeResource.lookup(FILE, "dart"),
                    "(NM: name): spaces after the colon skipped; the first match wins, with its prefix and newline"),
                () -> assertEquals("(name:arrow)(SH: 15,0 -9,9 -9,-9)\n", ShipShapeResource.lookup(FILE, "arrow"),
                    "(name:name) is found"),
                () -> assertNull(ShipShapeResource.lookup(FILE, "trailing"), "the name must match exactly (strcmp)"),
                () -> assertEquals("(NM:trailing )(SH: 15,0 -9,8 -9,-8)\n", ShipShapeResource.lookup(FILE, "trailing "),
                    "a trailing space is part of the name"),
                () -> assertNull(ShipShapeResource.lookup(FILE, "both"), "(name: is searched before (NM:"),
                () -> assertEquals("(NM: both)(name:named)(SH: 15,0 -9,8 -9,-8)", ShipShapeResource.lookup(FILE, "named"),
                    "the last line has no newline"),
                () -> assertEquals("(NM:open\n", ShipShapeResource.lookup(FILE, "open\n"),
                    "without ')' the name runs to the end of the line, newline included"),
                () -> assertNull(ShipShapeResource.lookup(FILE, "open"), "so it never equals a plain name"),
                () -> assertNull(ShipShapeResource.lookup(FILE, "nothere"), "an unknown name is null"),
                () -> assertNull(ShipShapeResource.lookup("", "dart"), "an empty file has no shapes"),
                () -> assertEquals("(NM:z)\n", ShipShapeResource.lookup("\n(NM:z)\n", "z"), "an empty first line is a line"));
        }

        @Test
        @DisplayName("lines are read in fgets pieces of at most 1023 chars")
        void longLines() {
            int max = ShipShapeResource.LINE_MAX;
            String pad = "x".repeat(max - 8);
            String split = pad + "(NM: a)b" + "(SH: 15,0 -9,8 -9,-8)\n";
            String second = "x".repeat(max) + "(NM: b)(SH: 15,0 -9,8 -9,-8)\n";
            String exact = "x".repeat(max - 7) + "(NM:c)\n";
            String over = "x".repeat(max - 6) + "(NM:c)\n";
            assertAll(
                () -> assertEquals(pad + "(NM: a)b", ShipShapeResource.lookup(split, "a"),
                    "a long line's first 1023 chars are a line of their own"),
                () -> assertEquals("(NM: b)(SH: 15,0 -9,8 -9,-8)\n", ShipShapeResource.lookup(second, "b"),
                    "the rest of a long line is the next line"),
                () -> assertEquals(exact, ShipShapeResource.lookup(exact, "c"), "1023 chars with the newline are one line"),
                () -> assertEquals(over.substring(0, max), ShipShapeResource.lookup(over, "c"),
                    "at 1024 chars the newline is cut off into the next line"));
        }

        @Test
        @DisplayName("nameOf: the declared name or null")
        void nameOf() {
            assertAll(
                () -> assertEquals("a", ShipShapeResource.nameOf("(NM:   a)"), "spaces after the colon are skipped"),
                () -> assertEquals("\ta", ShipShapeResource.nameOf("(NM:\ta)"), "only spaces are skipped"),
                () -> assertEquals("", ShipShapeResource.nameOf("(NM:)"), "an empty name"),
                () -> assertEquals("", ShipShapeResource.nameOf("(NM:   "), "only spaces to the end: empty"),
                () -> assertNull(ShipShapeResource.nameOf("(SH: 15,0)"), "no name keyword: null"),
                () -> assertNull(ShipShapeResource.nameOf("(nm:a)"), "keywords are case-sensitive"));
        }
    }

    @Nested
    @DisplayName("resolve")
    class Resolve {
        final List<String> reads = new ArrayList<>();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintStream err = new PrintStream(out, true, StandardCharsets.UTF_8);

        String resolve(String cmdShape, String cmdFile, String rcShape, String rcFile) {
            return ShipShapeResource.resolve(cmdShape, cmdFile, rcShape, rcFile, path -> {
                reads.add(path);
                if (path.equals("missing")) {
                    throw new NoSuchFileException(path);
                }
                if (path.equals("denied")) {
                    throw new IOException("Permission denied");
                }
                return FILE;
            }, err);
        }

        @Test
        @DisplayName("the command line beats the resource file, for each resource on its own")
        void precedence() {
            assertAll(
                () -> assertEquals("(SH: 1,0)", resolve("(SH: 1,0)", null, "(SH: 2,0)", null), "command-line shape wins"),
                () -> assertEquals("(SH: 2,0)", resolve(null, null, "(SH: 2,0)", null), "else the resource file's"),
                () -> assertEquals("", resolve(null, null, null, null), "else no shape (the default ship)"),
                () -> assertEquals("", resolve("", null, "(SH: 2,0)", null), "an empty command-line shape still wins"),
                () -> assertEquals(DART, resolve("dart", "cmd", null, "rc"), "a name is looked up in the shape file"),
                () -> assertEquals(List.of("cmd"), reads, "the command-line shape file wins"),
                () -> assertEquals(DART, resolve(null, null, "dart", "rc"), "a resource-file name and file"),
                () -> assertEquals(DART, resolve("dart", null, null, "rc"), "a command-line name, resource-file file"),
                () -> assertEquals(List.of("cmd", "rc", "rc"), reads, "each read the chosen file"));
        }

        @Test
        @DisplayName("only a non-empty name with a shape file is looked up")
        void whenLookedUp() {
            assertAll(
                () -> assertEquals("dart", resolve("dart", null, null, null), "no shape file: the name stays"),
                () -> assertEquals("dart", resolve("dart", "", null, "rc"), "an empty command-line file means none"),
                () -> assertEquals("(NM: dart)", resolve("(NM: dart)", "f", null, null), "a value with '(' is a shape"),
                () -> assertEquals("", resolve("", "f", null, null), "an empty shape is not looked up"),
                () -> assertEquals(List.of(), reads, "no file was read"),
                () -> assertEquals("nothere", resolve("nothere", "f", null, null), "an unknown name stays"));
        }

        @Test
        @DisplayName("an unreadable shape file is reported and the name kept")
        void unreadable() {
            String missing = resolve("dart", "missing", null, null);
            String denied = resolve("dart", "denied", null, null);
            assertAll(
                () -> assertEquals("dart", missing, "a missing file keeps the name"),
                () -> assertEquals("dart", denied, "an unreadable file keeps the name"),
                () -> assertEquals("missing: No such file or directory\ndenied: Permission denied\n",
                    out.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n"), "C perror style"));
        }

        @Test
        @DisplayName("the real reader reads Latin-1")
        void realReader(@TempDir Path dir) throws IOException {
            Path f = dir.resolve("ships");
            Files.write(f, new byte[] {'(', 'N', 'M', ':', (byte) 0xE9, ')', '\n'});
            assertEquals("(NM:é)\n", ShipShapeResource.readLatin1(f.toString()), "byte 0xE9 is é");
        }
    }
}
