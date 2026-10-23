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
package com.fulcrologic.jxpilot.client.viewer;

import com.fulcrologic.jxpilot.client.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapCatalogTest {
    @Test
    void listsXpFilesSortedCaseInsensitively(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("b.xp"), "");
        Files.writeString(dir.resolve("A.XP"), "");
        Files.writeString(dir.resolve("c.txt"), "");
        Files.createDirectory(dir.resolve("d.xp"));
        MapCatalog c = MapCatalog.scan(dir);
        assertEquals(List.of("A.XP", "b.xp"), c.maps().stream().map(p -> p.getFileName().toString()).toList(),
            ".xp files only (extension case-insensitive), no directories, sorted ignoring case");
    }

    @Test
    void findsAndCyclesMaps(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.xp"), "");
        Files.writeString(dir.resolve("b.xp"), "");
        MapCatalog c = MapCatalog.scan(dir);
        assertAll(
            () -> assertEquals(1, c.indexOf(dir.resolve("x/../b.xp")), "finds a file through a non-normalized path"),
            () -> assertEquals(-1, c.indexOf(dir.resolve("z.xp")), "-1 for an unknown file"),
            () -> assertEquals(1, c.next(0), "next after 0"),
            () -> assertEquals(0, c.next(1), "wraps after the last"),
            () -> assertEquals(0, c.next(-1), "an unknown index starts at 0"),
            () -> assertEquals(0, c.next(5), "an out-of-range index starts at 0"));
    }

    @Test
    void emptyCatalogHasNoNext(@TempDir Path dir) throws IOException {
        assertEquals(-1, MapCatalog.scan(dir).next(0), "-1 when there are no maps");
    }

    @Test
    void repositoryHasNineteenMaps() throws IOException {
        assertEquals(19, MapCatalog.scan(Fixtures.MAPS).maps().size(), "all shipped maps are found");
    }
}
