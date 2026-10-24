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
import com.fulcrologic.jxpilot.common.config.Options;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BundledMaps")
class BundledMapsTest {
    static final Path MAPS = Path.of("..", "maps");

    static List<String> repositoryMaps() throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(MAPS)) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".xp")).forEach(out::add);
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    @Nested
    @DisplayName("the bundle on the class path")
    class OnClassPath {
        @Test
        @DisplayName("the index lists exactly the repository's maps, sorted ignoring case")
        void indexMatchesMapsDirectory() throws IOException {
            assertAll(
                () -> assertEquals(repositoryMaps(), BundledMaps.standard().files(),
                    "index.txt names every .xp file in maps/ and nothing else, in case-insensitive order"),
                () -> assertEquals(19, BundledMaps.standard().files().size(), "XPilot 4.5.5's 19 maps"));
        }

        @TestFactory
        @DisplayName("every bundled map is the unmodified repository file and parses to a playable map")
        Stream<DynamicTest> everyBundledMapParses() {
            return BundledMaps.standard().files().stream().map(f -> DynamicTest.dynamicTest(f, () -> {
                Options o = new Options();
                MapSource.bundled(f).parseInto(o);
                WorldMap m = WorldMap.fromOptions(o);
                assertAll(f,
                    () -> assertArrayEquals(Files.readAllBytes(MAPS.resolve(f)), BundledMaps.bytes(f),
                        "the resource is byte for byte maps/" + f),
                    () -> assertNotNull(o.mapData(), "the map has map data"),
                    () -> assertEquals(o.getInt(Opt.MAP_WIDTH), m.width(), "it builds at its header width"),
                    () -> assertTrue(!m.bases().isEmpty(), "it has bases to start from"));
            }));
        }

        @Test
        @DisplayName("the maps' README (provenance and licence) is bundled next to them")
        void readmeIsBundled() throws IOException {
            try (var in = BundledMaps.class.getResourceAsStream(BundledMaps.DIR + "README.md")) {
                assertNotNull(in, "xpilot/maps/README.md is on the class path");
                assertArrayEquals(Files.readAllBytes(MAPS.resolve("README.md")), in.readAllBytes(),
                    "it is maps/README.md");
            }
        }

        @Test
        @DisplayName("a file that is not bundled cannot be read")
        void missingResource() {
            IOException e = assertThrows(IOException.class, () -> BundledMaps.bytes("no-such.xp"), "IOException");
            assertEquals("missing resource /xpilot/maps/no-such.xp", e.getMessage(), "names the resource");
        }
    }

    @Nested
    @DisplayName("parseIndex")
    class ParseIndex {
        @Test
        @DisplayName("one trimmed name per line; blank and # lines skipped; order kept")
        void lines() {
            assertEquals(List.of("b.xp", "A.xp", "c d.xp"),
                BundledMaps.parseIndex("# comment\n  b.xp  \n\n\t\nA.xp\r\n  # indented comment\nc d.xp"),
                "names in file order, without blanks, comments or surrounding white space");
        }
    }

    @Nested
    @DisplayName("names and find")
    class Names {
        final BundledMaps b = BundledMaps.of(List.of("CAMD.xp", "doggy.xp", "Upper.XP", "plain"));

        @Test
        @DisplayName("names drop the .xp extension, in any case")
        void names() {
            assertEquals(List.of("CAMD", "doggy", "Upper", "plain"), b.names(),
                "file names without .xp; a file without it keeps its name");
        }

        @Test
        @DisplayName("find matches a name with or without .xp, ignoring case")
        void find() {
            assertAll(
                () -> assertEquals("doggy.xp", b.find("doggy"), "the name without extension"),
                () -> assertEquals("doggy.xp", b.find("doggy.xp"), "the file name"),
                () -> assertEquals("CAMD.xp", b.find("camd"), "a lower-case name finds an upper-case file"),
                () -> assertEquals("doggy.xp", b.find("DOGGY.XP"), "an upper-case file name finds a lower-case file"),
                () -> assertEquals("Upper.XP", b.find("upper"), "an upper-case extension in the index"),
                () -> assertEquals("plain", b.find("plain"), "an exact name without extension"),
                () -> assertNull(b.find("dog"), "a prefix is not a match"),
                () -> assertNull(b.find("doggy.xp.xp"), "no second extension"),
                () -> assertNull(b.find(""), "an empty name matches nothing"));
        }
    }

    @Nested
    @DisplayName("Info and listing")
    class Listing {
        @Test
        @DisplayName("Info takes name, title, author and size from the map options; defaults when absent")
        void info() {
            Options o = new Options();
            o.setValue("mapName", "Doggy Style", false, com.fulcrologic.jxpilot.common.config.OptionOrigin.MAP);
            o.setValue("mapAuthor", "K. I.", false, com.fulcrologic.jxpilot.common.config.OptionOrigin.MAP);
            o.setValue("mapWidth", "49", false, com.fulcrologic.jxpilot.common.config.OptionOrigin.MAP);
            o.setValue("mapHeight", "50", false, com.fulcrologic.jxpilot.common.config.OptionOrigin.MAP);
            assertAll(
                () -> assertEquals(new BundledMaps.Info("doggy", "Doggy Style", "K. I.", 49, 50),
                    BundledMaps.Info.of("doggy.xp", o), "the header values, the name without .xp"),
                () -> assertEquals(new BundledMaps.Info("bare", "unknown", "anonymous", 100, 100),
                    BundledMaps.Info.of("bare.xp", new Options()), "the option defaults for a map without a header"));
        }

        @Test
        @DisplayName("the bundled maps' infos come from their headers")
        void standardInfos() throws IOException {
            List<BundledMaps.Info> infos = BundledMaps.standard().infos();
            assertAll(
                () -> assertEquals(BundledMaps.standard().names(), infos.stream().map(BundledMaps.Info::name).toList(),
                    "one info per bundled map, in index order"),
                () -> assertTrue(infos.contains(new BundledMaps.Info("tourmination", "Tourmination", "Bert Gijsbers",
                    80, 80)), "tourmination's header (lower-case option names in the file)"),
                () -> assertTrue(infos.contains(new BundledMaps.Info("doggy", "DoggyStyle", "Kjell Irgens", 49, 50)),
                    "doggy's header"),
                () -> assertTrue(infos.contains(new BundledMaps.Info("default", "The Newbie Globe",
                    "Bjørn Stabell (newbie mods by Bucko)", 170, 170)), "default's Latin-1 author is decoded"));
        }

        @Test
        @DisplayName("listing: heading, header and one aligned row per map, no trailing spaces")
        void listing() {
            String text = BundledMaps.listing(List.of(
                new BundledMaps.Info("doggy", "DoggyStyle", "Kjell Irgens", 49, 50),
                new BundledMaps.Info("blood-music2", "Blood's Music II", "Blood", 100, 100)));
            assertEquals("""
                Bundled maps (give the NAME to --map; a value with a path is a file):
                NAME          SIZE     TITLE             AUTHOR
                doggy         49x50    DoggyStyle        Kjell Irgens
                blood-music2  100x100  Blood's Music II  Blood
                """, text, "columns padded to the widest entry plus two spaces, rows in the given order");
        }

        @Test
        @DisplayName("listing: header words set the minimum column widths; an empty author leaves no trailing space")
        void narrowListing() {
            assertEquals("""
                Bundled maps (give the NAME to --map; a value with a path is a file):
                NAME  SIZE  TITLE  AUTHOR
                a     1x1   t
                """, BundledMaps.listing(List.of(new BundledMaps.Info("a", "t", "", 1, 1))),
                "short entries pad to the header words");
        }
    }
}
