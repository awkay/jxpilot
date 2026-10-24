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
package com.fulcrologic.jxpilot.server.lobby;

import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.map.BundledMaps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LobbyCatalog: the maps on offer")
class LobbyCatalogTest {
    static final BundledMaps BUNDLED = BundledMaps.standard();

    static List<String> names(LobbyCatalog c) {
        return c.offers().stream().map(LobbyCatalog.Offer::name).toList();
    }

    static void copyBundled(String file, Path to) throws IOException {
        Files.write(to, BundledMaps.bytes(file));
    }

    @Nested
    @DisplayName("bundled maps")
    class Bundled {
        @Test
        @DisplayName("every bundled map is offered once, ids are the alphabetical positions, and each starts idle")
        void allBundled() {
            List<String> warnings = new ArrayList<>();
            LobbyCatalog c = LobbyCatalog.build(BUNDLED, List.of(), Set.of(), warnings::add);
            List<String> expected = BUNDLED.names().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
            assertAll(
                () -> assertEquals(expected, names(c), "all bundled names, alphabetical ignoring case"),
                () -> assertTrue(warnings.isEmpty(), "every bundled map loads: " + warnings),
                () -> assertEquals(java.util.stream.IntStream.range(0, expected.size()).boxed().toList(),
                    c.offers().stream().map(LobbyCatalog.Offer::id).toList(), "ids are 0..n-1 in list order"),
                () -> assertTrue(c.entries().stream().noneMatch(LobbyEntry::active), "nothing is running"),
                () -> assertTrue(c.entries().stream().allMatch(e -> e.humans() == 0), "no humans yet"),
                () -> assertTrue(c.offers().stream().allMatch(o -> o.source().isBundled()), "all from the jar"));
        }

        @Test
        @DisplayName("an entry describes the map: title, author, size in blocks, one player per base")
        void entryDescribesMap() {
            LobbyCatalog c = LobbyCatalog.build(BUNDLED, List.of(), Set.of(), w -> { });
            LobbyEntry e = c.entries().stream().filter(x -> x.name().equals("doggy")).findFirst().orElseThrow();
            com.fulcrologic.jxpilot.common.config.Options o = new com.fulcrologic.jxpilot.common.config.Options();
            try {
                com.fulcrologic.jxpilot.common.map.MapSource.bundled("doggy.xp").parseInto(o);
            } catch (IOException ex) {
                throw new AssertionError(ex);
            }
            var map = com.fulcrologic.jxpilot.common.map.WorldMap.fromOptions(o);
            assertAll(
                () -> assertEquals(o.getString(com.fulcrologic.jxpilot.common.config.Opt.MAP_NAME), e.title(), "title"),
                () -> assertEquals(o.getString(com.fulcrologic.jxpilot.common.config.Opt.MAP_AUTHOR), e.author(), "author"),
                () -> assertEquals(map.width(), e.width(), "width in blocks"),
                () -> assertEquals(map.height(), e.height(), "height in blocks"),
                () -> assertEquals(map.bases().size(), e.maxPlayers(), "max players is the number of bases"),
                () -> assertTrue(e.maxPlayers() > 0, "a playable map has bases"));
        }

        @Test
        @DisplayName("no bundled maps (null) leaves only what the directories hold")
        void noBundled(@TempDir Path dir) throws IOException {
            copyBundled("doggy.xp", dir.resolve("mine.xp"));
            LobbyCatalog c = LobbyCatalog.build(null, List.of(dir), Set.of(), w -> { });
            assertEquals(List.of("mine"), names(c), "only the directory's map");
        }
    }

    @Nested
    @DisplayName("directories")
    class Directories {
        @Test
        @DisplayName(".xp and .map files are offered by their name without extension, in any case of extension")
        void extensions(@TempDir Path dir) throws IOException {
            copyBundled("doggy.xp", dir.resolve("one.xp"));
            copyBundled("doggy.xp", dir.resolve("two.map"));
            copyBundled("doggy.xp", dir.resolve("three.XP"));
            copyBundled("doggy.xp", dir.resolve("notamap.txt"));
            Files.createDirectory(dir.resolve("sub.xp"));
            List<String> warnings = new ArrayList<>();
            LobbyCatalog c = LobbyCatalog.build(null, List.of(dir), Set.of(), warnings::add);
            assertAll(
                () -> assertEquals(List.of("one", "three", "two"), names(c),
                    "one.xp, two.map and three.XP; the .txt file and the directory named sub.xp are not maps"),
                () -> assertTrue(warnings.isEmpty(), "and they are not even tried: " + warnings));
        }

        @Test
        @DisplayName("a directory map replaces the bundled map of the same name (ignoring case), and a later directory replaces an earlier one")
        void replacement(@TempDir Path a, @TempDir Path b) throws IOException {
            copyBundled("doggy.xp", a.resolve("DOGGY.xp"));
            copyBundled("doggy.xp", a.resolve("shared.xp"));
            copyBundled("doggy.xp", b.resolve("shared.xp"));
            LobbyCatalog c = LobbyCatalog.build(BUNDLED, List.of(a, b), Set.of(), w -> { });
            LobbyCatalog.Offer doggy = c.offers().stream().filter(o -> o.name().equalsIgnoreCase("doggy")).findFirst()
                .orElseThrow();
            LobbyCatalog.Offer shared = c.offers().stream().filter(o -> o.name().equals("shared")).findFirst()
                .orElseThrow();
            assertAll(
                () -> assertFalse(doggy.source().isBundled(), "doggy now comes from the directory"),
                () -> assertEquals(1, names(c).stream().filter(n -> n.equalsIgnoreCase("doggy")).count(),
                    "and is listed once"),
                () -> assertEquals(b.resolve("shared.xp"), shared.source().file(), "shared comes from the later directory"));
        }

        @Test
        @DisplayName("x.xp wins over x.map in one directory")
        void xpBeatsMap(@TempDir Path dir) throws IOException {
            copyBundled("doggy.xp", dir.resolve("x.map"));
            copyBundled("doggy.xp", dir.resolve("x.xp"));
            LobbyCatalog c = LobbyCatalog.build(null, List.of(dir), Set.of(), w -> { });
            assertEquals(dir.resolve("x.xp"), c.offers().get(0).source().file(), "the .xp file is the one");
        }

        @Test
        @DisplayName("a directory that does not exist is reported and skipped")
        void missingDirectory(@TempDir Path dir) throws IOException {
            copyBundled("doggy.xp", dir.resolve("ok.xp"));
            List<String> warnings = new ArrayList<>();
            LobbyCatalog c = LobbyCatalog.build(null, List.of(dir.resolve("nope"), dir), Set.of(), warnings::add);
            assertAll(
                () -> assertEquals(List.of("ok"), names(c), "the other directory still counts"),
                () -> assertEquals(1, warnings.size(), "one warning"),
                () -> assertTrue(warnings.get(0).contains("nope"), "it names the directory: " + warnings));
        }

        @Test
        @DisplayName("a file without map data, and a map without bases, are reported and not offered")
        void unplayable(@TempDir Path dir) throws IOException {
            copyBundled("doggy.xp", dir.resolve("good.xp"));
            Files.writeString(dir.resolve("empty.xp"), "mapName: empty\n");
            Files.writeString(dir.resolve("nobases.xp"),
                "mapName: nobases\nmapWidth: 3\nmapHeight: 3\nmapData: \\multiline: EOM\nxxx\nx x\nxxx\nEOM\n");
            List<String> warnings = new ArrayList<>();
            LobbyCatalog c = LobbyCatalog.build(null, List.of(dir), Set.of(), warnings::add);
            assertAll(
                () -> assertEquals(List.of("good"), names(c), "only the playable map"),
                () -> assertEquals(2, warnings.size(), "both skipped maps are reported: " + warnings),
                () -> assertTrue(warnings.stream().anyMatch(w -> w.contains("empty")), "empty named"),
                () -> assertTrue(warnings.stream().anyMatch(w -> w.contains("nobases")), "nobases named"));
        }
    }

    @Nested
    @DisplayName("exclusion and limits")
    class Exclusion {
        @Test
        @DisplayName("excluded names disappear, with or without extension and in any case; ids close the gap")
        void exclude() {
            LobbyCatalog all = LobbyCatalog.build(BUNDLED, List.of(), Set.of(), w -> { });
            LobbyCatalog c = LobbyCatalog.build(BUNDLED, List.of(), Set.of("DOGGY", "default.xp"), w -> { });
            List<String> expected = new ArrayList<>(names(all));
            expected.removeIf(n -> n.equalsIgnoreCase("doggy") || n.equalsIgnoreCase("default"));
            assertAll(
                () -> assertEquals(expected, names(c), "the two are gone, the rest stay in order"),
                () -> assertEquals(c.offers().size() - 1, c.offers().get(c.offers().size() - 1).id(),
                    "ids are contiguous again"),
                () -> assertNull(c.offer(c.offers().size()), "there is no offer past the end"),
                () -> assertNull(c.offer(-1), "or before the start"),
                () -> assertNotNull(c.offer(0), "offer 0 exists"));
        }

        @Test
        @DisplayName("excluding everything gives an empty catalog")
        void excludeAll() {
            Set<String> all = new java.util.HashSet<>(BUNDLED.names());
            assertTrue(LobbyCatalog.build(BUNDLED, List.of(), all, w -> { }).offers().isEmpty(), "nothing left");
        }

        @Test
        @DisplayName("long names, titles and authors are cut in the entry but the offer keeps the whole name")
        void longText(@TempDir Path dir) throws IOException {
            String longName = "n".repeat(100);
            Files.writeString(dir.resolve(longName + ".xp"), new String(BundledMaps.bytes("doggy.xp"),
                java.nio.charset.StandardCharsets.ISO_8859_1) + "\nmapName: \\override: " + "T".repeat(100)
                + "\nmapAuthor: \\override: " + "A".repeat(100) + "\n");
            LobbyCatalog c = LobbyCatalog.build(null, List.of(dir), Set.of(), w -> { });
            LobbyCatalog.Offer o = c.offers().get(0);
            Files.writeString(dir.resolve("exact.xp"), new String(BundledMaps.bytes("doggy.xp"),
                java.nio.charset.StandardCharsets.ISO_8859_1) + "\nmapName: \\override: " + "E".repeat(LobbyCatalog.MAX_TEXT) + "\n");
            LobbyEntry exact = LobbyCatalog.build(null, List.of(dir), Set.of(longName), w -> { }).offers().get(0).info();
            assertAll(
                () -> assertEquals(LobbyCatalog.MAX_TEXT, exact.title().length(), "a title of exactly the limit is kept whole"),
                () -> assertEquals(100, o.name().length(), "the offer's name is whole"),
                () -> assertEquals(LobbyCatalog.MAX_TEXT, o.info().name().length(), "entry name cut"),
                () -> assertEquals(LobbyCatalog.MAX_TEXT, o.info().title().length(), "entry title cut"),
                () -> assertEquals(LobbyCatalog.MAX_TEXT, o.info().author().length(), "entry author cut"));
        }
    }
}
