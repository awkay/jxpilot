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

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.MapSource;
import com.fulcrologic.jxpilot.server.net.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Lobby config file and per-map option sources")
class LobbyConfigTest {
    static LobbyConfig parse(String text, List<String> warnings) {
        return LobbyConfig.parse(text, warnings::add);
    }

    @Nested
    @DisplayName("LobbyConfig.parse")
    class Parse {
        @Test
        @DisplayName("lines before the first [section] are the server-wide part; each section collects its own lines")
        void split() {
            LobbyConfig c = parse("maxRobots: 2\n# comment\n[doggy]\nmaxRobots: 0\n\n[Tourmination.xp]\ngravity: -0.3\n",
                new ArrayList<>());
            assertAll(
                () -> assertEquals("maxRobots: 2\n# comment\n", c.top(), "server-wide lines"),
                () -> assertEquals(java.util.List.of("doggy", "tourmination"), List.copyOf(c.sectionNames()),
                    "section names lower-cased without extension, in file order"),
                () -> assertTrue(c.sectionFor("doggy").contains("maxRobots: \\override: 0"), "doggy's line"),
                () -> assertTrue(!c.sectionFor("doggy").contains("gravity"), "not the other section's"),
                () -> assertTrue(c.sectionFor("TOURMINATION").contains("gravity: \\override: -0.3"),
                    "sections are found ignoring case"));
        }

        @Test
        @DisplayName("header forms: blanks inside, a trailing comment, CRLF line ends")
        void headerForms() {
            LobbyConfig c = parse("[ a ]  # note\r\nx: 1\r\n[b.map]\r\ny: 2\r\n", new ArrayList<>());
            assertAll(
                () -> assertEquals(List.of("a", "b"), List.copyOf(c.sectionNames()), "both headers read"),
                () -> assertTrue(c.sectionFor("a").contains("x: \\override: 1"), "a's line"),
                () -> assertTrue(c.sectionFor("b").contains("y: \\override: 2"), "b's line"));
        }

        @Test
        @DisplayName("a section named twice collects both blocks")
        void duplicate() {
            LobbyConfig c = parse("[m]\na: 1\n[n]\nb: 2\n[M]\nc: 3\n", new ArrayList<>());
            assertAll(
                () -> assertEquals(2, c.sectionNames().size(), "two sections"),
                () -> assertTrue(c.sectionFor("m").contains("a:") && c.sectionFor("m").contains("c:"), "m has a and c"));
        }

        @Test
        @DisplayName("an empty section name is a warning and its lines are dropped")
        void emptyName() {
            List<String> w = new ArrayList<>();
            LobbyConfig c = parse("[]\nx: 1\n[ok]\ny: 2\n", w);
            assertAll(
                () -> assertEquals(1, w.size(), "one warning: " + w),
                () -> assertTrue(w.get(0).contains("line 1"), "it names the line"),
                () -> assertEquals(List.of("ok"), List.copyOf(c.sectionNames()), "only the good section"),
                () -> assertTrue(!c.sectionFor("ok").contains("x:"), "the dropped lines are not in it"));
        }

        @Test
        @DisplayName("a [bracket] line inside a \\multiline: value is text, not a header")
        void multiline() {
            LobbyConfig c = parse("[m]\ntext: \\multiline: END\n[not a header]\nEND\nz: 1\n", new ArrayList<>());
            assertAll(
                () -> assertEquals(List.of("m"), List.copyOf(c.sectionNames()), "still one section"),
                () -> assertTrue(c.sectionFor("m").contains("[not a header]"), "the line is in the value"),
                () -> assertTrue(c.sectionFor("m").contains("z: \\override: 1"), "and the next line is read normally"));
        }

        @Test
        @DisplayName("an unknown map has no section text")
        void none() {
            assertEquals("", LobbyConfig.EMPTY.sectionFor("anything"), "empty config, empty section");
        }
    }

    @Nested
    @DisplayName("LobbyConfig.forceOverride")
    class ForceOverride {
        @Test
        @DisplayName("each name: value gains \\override:; existing \\override:, comments, blanks and colon-less lines stay")
        void lines() {
            String out = LobbyConfig.forceOverride("a: 1\n\n# note\nb: \\override: 2\nplain line\n   c  :  3  \n");
            assertEquals("a: \\override: 1\n\n# note\nb: \\override: 2\nplain line\nc  : \\override: 3\n\n", out,
                "lines rewritten one by one");
        }

        @Test
        @DisplayName("a line that starts with the colon still gets \\override: (the parser reports the missing name)")
        void colonFirst() {
            assertEquals(": \\override: x\n\n", LobbyConfig.forceOverride(":x\n"), "rewritten");
        }

        @Test
        @DisplayName("a \\multiline: value keeps its body verbatim up to the delimiter, with \\override: before \\multiline:")
        void multiline() {
            String out = LobbyConfig.forceOverride("t: \\multiline: E\na: 1\n  b\nE\nz: 9\n");
            assertEquals("t: \\override: \\multiline: E\na: 1\n  b\nE\nz: \\override: 9\n\n", out,
                "the body lines are not touched");
        }
    }

    @Nested
    @DisplayName("per-map options through Server.readOptions")
    class Sources {
        static final String[] NO_ARGS = {"--defaults", "none"};

        Options read(LobbyConfig c, String map, MapSource src, String... args) throws IOException {
            return Server.readOptions(args, new LobbyOptionSources(c, map, src));
        }

        @Test
        @DisplayName("a section beats the map file; the same option in the server-wide part is only a default the map beats")
        void sectionBeatsMap() throws IOException {
            MapSource doggy = MapSource.bundled("doggy.xp");
            LobbyConfig sec = parse("[doggy]\nmapAuthor: Section Author\n", new ArrayList<>());
            LobbyConfig top = parse("mapAuthor: Top Author\n", new ArrayList<>());
            LobbyConfig topOverride = parse("mapAuthor: \\override: Forced Author\n", new ArrayList<>());
            assertAll(
                () -> assertEquals("Kjell Irgens", read(LobbyConfig.EMPTY, "doggy", doggy, NO_ARGS).getString(Opt.MAP_AUTHOR),
                    "the map's own author without any config"),
                () -> assertEquals("Section Author", read(sec, "doggy", doggy, NO_ARGS).getString(Opt.MAP_AUTHOR),
                    "the section's value wins over the map's"),
                () -> assertEquals("Kjell Irgens", read(top, "doggy", doggy, NO_ARGS).getString(Opt.MAP_AUTHOR),
                    "a plain server-wide value loses to the map's own"),
                () -> assertEquals("Forced Author", read(topOverride, "doggy", doggy, NO_ARGS).getString(Opt.MAP_AUTHOR),
                    "a server-wide \\override: value beats the map"));
        }

        @Test
        @DisplayName("the command line beats the section, and a map takes only its own section")
        void commandLineWins() throws IOException {
            MapSource doggy = MapSource.bundled("doggy.xp");
            LobbyConfig sec = parse("[doggy]\nmapAuthor: Five\n[other]\nmapAuthor: Nine\n", new ArrayList<>());
            Options cli = read(sec, "doggy", doggy, "--defaults", "none", "--mapAuthor", "Three");
            Options own = read(sec, "doggy", doggy, NO_ARGS);
            assertAll(
                () -> assertEquals("Three", cli.getString(Opt.MAP_AUTHOR), "command line beats the section"),
                () -> assertEquals("Five", own.getString(Opt.MAP_AUTHOR), "else the map's own section, not [other]'s"));
        }

        @Test
        @DisplayName("a section beats a server-wide \\override: line too, whatever their order in the file")
        void sectionBeatsTopOverride() throws IOException {
            LobbyConfig c = parse("mapAuthor: \\override: Eight\n[doggy]\nmapAuthor: One\n", new ArrayList<>());
            assertEquals("One", read(c, "doggy", MapSource.bundled("doggy.xp"), NO_ARGS).getString(Opt.MAP_AUTHOR),
                "section value, not Eight");
        }

        @Test
        @DisplayName("the map comes from the hook, not from mapFileName")
        void mapFromHook() throws IOException {
            Options o = read(LobbyConfig.EMPTY, null, MapSource.bundled("doggy.xp"), "--defaults", "none", "--map",
                "tourmination");
            Options t = Server.readOptions(new String[] {"--defaults", "none", "--map", "tourmination"});
            assertAll(
                () -> assertEquals(Server.readOptions(new String[] {"--defaults", "none", "--map", "doggy"})
                    .getString(Opt.MAP_NAME), o.getString(Opt.MAP_NAME), "the hook's map (doggy) was loaded"),
                () -> assertTrue(!o.getString(Opt.MAP_NAME).equals(t.getString(Opt.MAP_NAME)), "not the named one"));
        }

        @Test
        @DisplayName("server-wide lobby options (lobbyMapsDir, lobbyExclude ...) are read from the config's top")
        void lobbyOptions(@TempDir Path dir) throws IOException {
            LobbyConfig c = parse("lobbyMapsDir: " + dir + "\nlobbyExclude: doggy, default\nlobbyBundled: no\n"
                + "lobbyName: friends\n", new ArrayList<>());
            Options o = read(c, null, MapSource.bundled("doggy.xp"), NO_ARGS);
            assertAll(
                () -> assertEquals(List.of(dir.toString()), o.getList(Opt.LOBBY_MAPS_DIR), "maps directory"),
                () -> assertEquals(List.of("doggy", "default"), o.getList(Opt.LOBBY_EXCLUDE).stream().map(String::strip).toList(),
                    "exclude list (entries may carry the blank after the comma)"),
                () -> assertEquals(false, o.getBool(Opt.LOBBY_BUNDLED), "bundled off"),
                () -> assertEquals("friends", o.getString(Opt.LOBBY_NAME), "lobby name"));
        }

        @Test
        @DisplayName("a lobby config file on disk is read as Latin-1 and parsed like text")
        void fromFile(@TempDir Path dir) throws IOException {
            Path f = dir.resolve("lobby.cfg");
            Files.write(f, "[doggy]\nmapAuthor: café\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            LobbyConfig c = LobbyConfig.read(f, w -> { });
            Options o = read(c, "doggy", MapSource.bundled("doggy.xp"), NO_ARGS);
            assertEquals("café", o.getString(Opt.MAP_AUTHOR), "the accented author survived");
        }
    }
}
