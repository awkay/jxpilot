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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.server.host.SimGameHost;
import com.fulcrologic.jxpilot.server.host.SimGameHostFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The option sources of {@link Server#readOptions} and {@link DefaultsFile} (C parser.c Parser, option.c). */
class ServerOptionsTest {
    static final String DEFAULT_MAP = Path.of("..", "maps", "default.xp").toString();

    @TempDir
    Path dir;

    /** A small map that sets no physics options at all. */
    private String bareMap(String extra) throws IOException {
        Path p = dir.resolve("bare.xp");
        Files.writeString(p, "mapWidth: 4\nmapHeight: 2\n" + extra + "mapData: \\multiline: EOF\n_   \n    \nEOF\n",
            StandardCharsets.ISO_8859_1);
        return p.toString();
    }

    private Path file(String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.ISO_8859_1);
        return p;
    }

    @Test
    @DisplayName("the bundled file is XPilot's lib/defaults (its first and last settings are there)")
    void bundled() {
        String t = DefaultsFile.bundledText();
        assertAll(
            () -> assertTrue(t.startsWith("# Set maximum density of any given item type"), "the original header"),
            () -> assertTrue(t.contains("define: SoftWalls \\override: \\multiline: EndSoftWalls"), "the SoftWalls macro"),
            () -> assertTrue(t.stripTrailing().endsWith("expand: SoftWalls"), "ends by expanding SoftWalls"));
    }

    @Test
    @DisplayName("a map without settings gets lib/defaults: gravity -0.25, 14 fps, SoftWalls 180s, item probabilities")
    void bareMapGetsDefaults() throws IOException {
        Options o = Server.readOptions(new String[] {"--map", bareMap("")});
        assertAll(
            () -> assertEquals(-0.25, o.getDouble(Opt.GRAVITY), "gravity"),
            () -> assertEquals(OptionOrigin.DEFAULTS, o.originOf(Opt.GRAVITY), "from the defaults file"),
            () -> assertEquals(14, o.framesPerSecond(), "framesPerSecond"),
            () -> assertEquals(180.0, o.getDouble(Opt.MAX_UNSHIELDED_WALL_BOUNCE_SPEED), "unshielded bounce speed"),
            () -> assertEquals(180.0, o.getDouble(Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED), "shielded bounce speed"),
            () -> assertEquals(180.0, o.getDouble(Opt.MAX_UNSHIELDED_PLAYER_WALL_BOUNCE_ANGLE), "unshielded angle"),
            () -> assertEquals(180.0, o.getDouble(Opt.MAX_SHIELDED_PLAYER_WALL_BOUNCE_ANGLE), "shielded angle"),
            () -> assertEquals(2.40292e-09, o.getDouble(Opt.ITEM_TANK_PROB), "itemTankProb"),
            () -> assertEquals(0.3, o.getDouble(Opt.MOVING_ITEM_PROB), "movingItemProb"),
            () -> assertEquals(false, o.getBool(Opt.ALLOW_NUKES), "allowNukes"),
            () -> assertEquals(15345, o.getInt(Opt.CONTACT_PORT), "PrivateSettings is only defined, not expanded"));
    }

    @Test
    @DisplayName("default.xp: its own settings beat lib/defaults (12 fps, bounce speed 60), gravity comes from lib/defaults")
    void mapBeatsDefaults() throws IOException {
        Options o = Server.readOptions(new String[] {"--map", DEFAULT_MAP});
        assertAll(
            () -> assertEquals(-0.25, o.getDouble(Opt.GRAVITY), "gravity -0.25 (default.xp sets none)"),
            () -> assertEquals(12, o.framesPerSecond(), "the map's framesPerSecond"),
            () -> assertEquals(60.0, o.getDouble(Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED), "the map's 60, not SoftWalls' 180"),
            () -> assertEquals(OptionOrigin.MAP, o.originOf(Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED), "origin MAP"));
    }

    @Test
    @DisplayName("--map NAME is the bundled map, in any case and with or without .xp: the same options as the file")
    void bundledMapByName() throws IOException {
        Options file = Server.readOptions(new String[] {"--map", DEFAULT_MAP});
        Options named = Server.readOptions(new String[] {"--map", "default"});
        Options upper = Server.readOptions(new String[] {"--map", "DEFAULT.XP"});
        assertAll(
            () -> assertEquals(file.toConfigText(), named.toConfigText(), "'default' is maps/default.xp"),
            () -> assertEquals(file.toConfigText(), upper.toConfigText(), "'DEFAULT.XP' is the same map"),
            () -> assertEquals("The Newbie Globe", named.getString(Opt.MAP_NAME), "its header"),
            () -> assertEquals(OptionOrigin.MAP, named.originOf(Opt.MAP_NAME), "read as the map"));
    }

    @Test
    @DisplayName("a config file's mapFileName is resolved the same way; an unknown map lists the bundled ones; no map"
        + " at all is the bundled default, as C's MAPDIR/default.xp")
    void configFileMapName() throws IOException {
        Path cfg = file("server.cfg", "mapFileName: doggy\n");
        Options o = Server.readOptions(new String[] {cfg.toString()});
        String msg = assertThrows(java.io.FileNotFoundException.class,
            () -> Server.readOptions(new String[] {"--map", "no-such-map"}), "an unknown map is an error").getMessage();
        Options none = Server.readOptions(new String[0]);
        Options defaultFile = Server.readOptions(new String[] {"--map", DEFAULT_MAP});
        assertAll(
            () -> assertEquals("DoggyStyle", o.getString(Opt.MAP_NAME), "the bundled doggy"),
            () -> assertTrue(msg.startsWith("map 'no-such-map' not found: it is not a bundled map"), "says why: " + msg),
            () -> assertTrue(msg.contains("Bundled maps: blood-music2, CAMD, cloudscape, default,"), "lists them: " + msg),
            () -> assertEquals(defaultFile.toConfigText(), none.toConfigText(), "no map plays maps/default.xp"),
            () -> assertEquals(OptionOrigin.MAP, none.originOf(Opt.MAP_NAME), "read as the map"));
    }

    @Test
    @DisplayName("--defaults none reads no defaults file: the cmdline.c defaults stay")
    void none() throws IOException {
        Options o = Server.readOptions(new String[] {"--map", bareMap(""), "--defaults", "NONE"});
        assertAll(
            () -> assertEquals(-0.14, o.getDouble(Opt.GRAVITY), "cmdline.c gravity"),
            () -> assertEquals(OptionOrigin.INIT, o.originOf(Opt.GRAVITY), "never set"),
            () -> assertEquals(OptionOrigin.INIT, o.originOf(Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED), "no SoftWalls"));
    }

    @Test
    @DisplayName("--defaults FILE replaces the bundled file; \\override: there beats the map; the command line beats both")
    void ownFile() throws IOException {
        Path d = file("mydefaults", "gravity: -0.5\nshotSpeed: \\override: 30\nshotLife: \\override: 40\n");
        String map = bareMap("shotSpeed: 10\nshotLife: 10\nfriction: 0.1\n");
        Options o = Server.readOptions(new String[] {"--map", map, "-defaults", d.toString(), "-shotLife", "50"});
        assertAll(
            () -> assertEquals(-0.5, o.getDouble(Opt.GRAVITY), "the file's gravity"),
            () -> assertEquals(OptionOrigin.INIT, o.originOf(Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED), "not lib/defaults"),
            () -> assertEquals(30.0, o.getDouble(Opt.SHOT_SPEED), "\\override: in the defaults file beats the map"),
            () -> assertEquals(50, o.getInt(Opt.SHOT_LIFE), "the command line beats an override"),
            () -> assertEquals(0.1, o.getDouble(Opt.FRICTION), "the map beats plain defaults"));
    }

    @Test
    @DisplayName("passwordFileName: read after the defaults (origin PASSWORD); only password is taken; a missing file is an error")
    void passwordFile() throws IOException {
        Path pw = file("password", "# set this to a unique word\npassword: \\override:  Sesame\nshotSpeed: 99\n");
        Path d = file("mydefaults", "password: fromDefaults\n");
        String map = bareMap("");
        Options o = Server.readOptions(new String[] {"--map", map, "-defaults", d.toString(), "-passwordFileName",
            pw.toString()});
        Options none = Server.readOptions(new String[] {"--map", map, "-defaults", d.toString()});
        assertAll(
            () -> assertEquals("Sesame", o.getString(Opt.PASSWORD), "the password file beats the defaults file"),
            () -> assertEquals(OptionOrigin.PASSWORD, o.originOf(Opt.PASSWORD), "origin PASSWORD"),
            () -> assertEquals(OptionOrigin.INIT, o.originOf(Opt.SHOT_SPEED), "other options are refused there"),
            () -> assertEquals("fromDefaults", none.getString(Opt.PASSWORD), "without the option no password file is read"),
            () -> assertThrows(IOException.class, () -> Server.readOptions(new String[] {"--map", map,
                "-passwordFileName", dir.resolve("missing").toString()}), "a named file that cannot be read"));
    }

    @Test
    @DisplayName("passwordFileName warnings: an unknown name without its value; an empty password")
    void passwordFileWarnings() throws IOException {
        Path pw = file("password2", "pasword: Sesame\n");
        String map = bareMap("");
        Options o = Server.readOptions(new String[] {"--map", map, "-passwordFileName", pw.toString()});
        Options e = Server.readOptions(new String[] {"--map", map, "-password", ""});
        String unknown = "Unknown option 'pasword' in the password file " + pw + " (its value is not shown).";
        String emptyWarning = "The password is empty: '/password' without an argument gives operator status (as C).";
        assertAll(
            () -> assertTrue(o.warnings().contains(unknown), "the unknown name is warned about: " + o.warnings()),
            () -> assertTrue(o.warnings().stream().noneMatch(w -> w.contains("Sesame")), "its value is never shown"),
            () -> assertFalse(o.warnings().contains(emptyWarning), "no password: no empty-password warning"),
            () -> assertEquals("", e.getString(Opt.PASSWORD), "an empty password"),
            () -> assertTrue(e.warnings().contains(emptyWarning), "is warned about: " + e.warnings()));
    }

    @Test
    @DisplayName("the positional config file is read after lib/defaults and beats it; the map still beats both")
    void configFile() throws IOException {
        Path cfg = file("server.cfg", "gravity: -0.3\nshotSpeed: 25\n");
        Options o = Server.readOptions(new String[] {"--map", bareMap("shotSpeed: 15\n"), cfg.toString()});
        assertAll(
            () -> assertEquals(-0.3, o.getDouble(Opt.GRAVITY), "the config file beats lib/defaults"),
            () -> assertEquals(15.0, o.getDouble(Opt.SHOT_SPEED), "the map beats the config file"),
            () -> assertEquals(180.0, o.getDouble(Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED), "lib/defaults is still read"));
    }

    @Test
    @DisplayName("a named defaults file that cannot be read is a startup error naming it")
    void missing() {
        Path gone = dir.resolve("nope");
        IOException e = assertThrows(IOException.class,
            () -> Server.readOptions(new String[] {"--map", DEFAULT_MAP, "--defaults", gone.toString()}));
        assertTrue(e.getMessage().startsWith("cannot read defaults file " + gone), e.getMessage());
    }

    @Test
    @DisplayName("robotFile from a defaults file reaches the robot roster (robot.c Parse_robot_file)")
    void robotFile() throws IOException {
        Path robots = file("robots", "name: Alpha\ntype: default\npara: 50 50\n\nname: Beta\n");
        Path d = file("d", "robotFile: " + robots + "\n");
        Options o = Server.readOptions(new String[] {"--map", DEFAULT_MAP, "--defaults", d.toString()});
        SimGameHost host = (SimGameHost) new SimGameHostFactory(() -> 1).create(o, WorldMap.fromOptions(o));
        var roster = host.simulation().robots.roster();
        assertAll(
            () -> assertEquals(2, roster.size(), "the file's two robots"),
            () -> assertEquals("Alpha", roster.def(0).name(), "first"),
            () -> assertEquals("Beta", roster.def(1).name(), "second"));
    }
}
