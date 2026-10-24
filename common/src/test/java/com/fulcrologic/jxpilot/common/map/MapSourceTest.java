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

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MapSource")
class MapSourceTest {
    @Test
    @DisplayName("a bundled map reads its resource, parses as Latin-1 with origin MAP, and describes itself")
    void bundled() throws IOException {
        MapSource s = MapSource.bundled("default.xp");
        Options o = new Options();
        s.parseInto(o);
        assertAll(
            () -> assertTrue(s.isBundled(), "is bundled"),
            () -> assertNull(s.file(), "has no file"),
            () -> assertArrayEquals(Files.readAllBytes(Path.of("..", "maps", "default.xp")), s.bytes(),
                "its bytes are the resource"),
            () -> assertEquals("Bjørn Stabell (newbie mods by Bucko)", o.getString(Opt.MAP_AUTHOR),
                "byte 0xF8 decodes to ø (Latin-1)"),
            () -> assertEquals(OptionOrigin.MAP, o.originOf(Opt.MAP_NAME), "values have origin MAP"),
            () -> assertEquals("bundled map default.xp", s.describe(), "describes itself as bundled"));
    }

    @Test
    @DisplayName("a file reads the file, parses as Latin-1 with its file name in warnings, and describes itself")
    void file(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mine.xp");
        Files.write(f, new byte[] {'m', 'a', 'p', 'N', 'a', 'm', 'e', ':', ' ', (byte) 0xE6, '\n', '1', ':', '2', '\n'});
        MapSource s = MapSource.of(f);
        Options o = new Options();
        s.parseInto(o);
        assertAll(
            () -> assertFalse(s.isBundled(), "is not bundled"),
            () -> assertEquals("mine.xp", s.fileName(), "the file name"),
            () -> assertEquals("æ", o.getString(Opt.MAP_NAME), "byte 0xE6 decodes to æ (Latin-1)"),
            () -> assertEquals(OptionOrigin.MAP, o.originOf(Opt.MAP_NAME), "values have origin MAP"),
            () -> assertEquals(java.util.List.of("mine.xp line 2: Names must start with an alphabetic."), o.warnings(),
                "warnings name the file"),
            () -> assertEquals(f.toString(), s.describe(), "describes itself by its path"));
    }
}
