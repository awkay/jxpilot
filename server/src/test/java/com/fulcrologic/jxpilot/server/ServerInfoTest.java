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
package com.fulcrologic.jxpilot.server;

import com.fulcrologic.jxpilot.server.net.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C Parse_check_info_request and its outputs (parser.c). */
class ServerInfoTest {

    @Test
    @DisplayName("requests are recognised with one or two dashes anywhere among the arguments; none gives null")
    void requests() {
        assertAll(
            () -> assertNull(ServerInfo.request(new String[] {"--map", "x.xp", "-gravity", "1"}, "1"), "no request"),
            () -> assertEquals("JXPilot server 1.2 (a port of XPilot 4.5.5)\n",
                ServerInfo.request(new String[] {"--map", "x", "--version"}, "1.2"), "--version"),
            () -> assertEquals(ServerInfo.request(new String[] {"-v"}, "9"), ServerInfo.request(new String[] {"-version"}, "9"),
                "-v"),
            () -> assertEquals(ServerInfo.help(), ServerInfo.request(new String[] {"-h"}, "1"), "-h"),
            () -> assertEquals(ServerInfo.help(), ServerInfo.request(new String[] {"--help"}, "1"), "--help"),
            () -> assertEquals(ServerInfo.dumpConfig() + ServerInfo.dumpOptions(),
                ServerInfo.request(new String[] {"-dump"}, "1"), "-dump"),
            () -> assertEquals(ServerInfo.dumpOptions(), ServerInfo.request(new String[] {"-dumpMan"}, "1"), "-dumpMan"),
            () -> assertEquals(ServerInfo.dumpOptions(), ServerInfo.request(new String[] {"-dumpWindows"}, "1"),
                "-dumpWindows"),
            () -> assertEquals(ServerInfo.dumpFlags(), ServerInfo.request(new String[] {"-dumpFlags"}, "1"), "-dumpFlags"),
            () -> assertEquals(ServerInfo.listMaps(), ServerInfo.request(new String[] {"--list-maps"}, "1"), "--list-maps"),
            () -> assertEquals(ServerInfo.listMaps(), ServerInfo.request(new String[] {"-list-maps"}, "1"), "-list-maps"),
            () -> assertEquals("dev", ServerInfo.version(), "no jar manifest in tests"));
    }

    @Test
    @DisplayName("--list-maps: the bundled maps' listing")
    void listMaps() throws java.io.IOException {
        String l = ServerInfo.listMaps();
        assertAll(
            () -> assertEquals(com.fulcrologic.jxpilot.common.map.BundledMaps.listing(
                com.fulcrologic.jxpilot.common.map.BundledMaps.standard().infos()), l, "BundledMaps.listing of every map"),
            () -> assertTrue(l.lines().anyMatch(s -> s.matches("tourmination +80x80 +Tourmination +Bert Gijsbers")),
                "a row with tourmination's name, size, title and author"));
    }

    @Test
    @DisplayName("dump: defaults-file lines padded like C's \"%s:%*s%s\", void options left out")
    void dump() {
        String d = ServerInfo.dumpOptions();
        assertAll(
            () -> assertTrue(d.contains("\ngravity:" + " ".repeat(33) + "-0.14\n"), "gravity padded to column 41"),
            () -> assertTrue(d.contains("\nrobotFile:" + " ".repeat(31) + "\n"), "no default: empty value"),
            () -> assertTrue(d.contains("\nshotHitFuelDrainUsesKineticEnergy:" + " ".repeat(7) + "yes\n"),
                "a name of 33 characters gets 7 spaces"),
            () -> assertFalse(d.contains("\nhelp:"), "void options are not dumped"),
            () -> assertTrue(d.endsWith("\n\n"), "ends with an empty line"),
            () -> assertTrue(ServerInfo.dumpConfig().contains("# DEFAULTS_FILE_NAME = (bundled) xpilot/defaults\n"),
                "the -dump header names the defaults file"));
    }

    @Test
    @DisplayName("help: kind, alias, help text and the non-default flags; dumpFlags lists every origin")
    void helpAndFlags() {
        String h = ServerInfo.help();
        String f = ServerInfo.dumpFlags();
        assertAll(
            () -> assertTrue(h.contains("\n    -fireRepeatRate or fireRepeat <integer>\n\tNumber of frames per automatic fire"),
                "alias and kind"),
            () -> assertTrue(h.contains("\n    -/+shotsGravity <real>") == false && h.contains("\n    -/+shotsGravity \n"),
                "booleans take -/+ and no value"),
            () -> assertTrue(h.contains("\n    -gravity <real>\n\tGravity strength.\n\n"), "an 'any' option has no flags line"),
            () -> assertTrue(h.contains("\n    -/+idleRun or rawMode \n\tDo robots keep on playing even if all human players quit?\n"
                + "\t[ Flags: command, defaults ]\n"), "command+defaults"),
            () -> assertTrue(h.contains("\t[ Flags: command, passwordfile, defaults, invisible ]\n"), "password"),
            () -> assertTrue(h.contains("\n    -help \n\tPrint out this help message.\n\n"),
                "a void option takes '-', and flags 0 print no flags line"),
            () -> assertTrue(f.contains("\nrobotFile:" + " ".repeat(31) + "{command, defaults}\n"), "robotFile flags"),
            () -> assertTrue(f.contains("\ngravity:" + " ".repeat(33) + "{command, defaults, map, visible}\n"), "any+visible"),
            () -> assertTrue(f.startsWith("help:" + " ".repeat(36) + "{}\n"), "no flags"));
    }

    @Test
    @DisplayName("ServerMain prints an information request to stdout and exits 0 without starting")
    void main() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = ServerMain.run(new String[] {"-version"}, new Server.Settings(),
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertAll(
            () -> assertEquals(0, status, "exit 0"),
            () -> assertEquals("JXPilot server dev (a port of XPilot 4.5.5)\n", out.toString(StandardCharsets.UTF_8), "stdout"),
            () -> assertEquals("", err.toString(StandardCharsets.UTF_8), "nothing on stderr"));
    }
}
