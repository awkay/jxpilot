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
package com.fulcrologic.jxpilot.client.game.options;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Saving ~/.xpilotrc (C Config_save, keeping other lines)")
class RcWriterTest {
    static RcWriter.Resource r(String n, String v) {
        return new RcWriter.Resource(n, v);
    }

    @Test
    @DisplayName("replaces saved resources in place under any prefix, drops their duplicates and continuations, keeps the rest")
    void update() {
        String old = String.join("\n",
            "! my settings",
            "xpilot.showHUD: no",
            "XTerm*font: fixed",
            "*clock: yes",
            "",
            "xpilot.msg1: a long \\",
            "  macro",
            "#include \"other\"",
            "SHOWHUD: yes",
            "xpilot.keyThrust: w",
            "not a resource line") + "\n";
        String out = RcWriter.update(old, List.of(r("showHUD", "True"), r("clock", "False"), r("msg1", "hi"),
            r("power", "45.000")));
        assertEquals(String.join("\n",
            "! my settings",
            "xpilot.showHUD:\t\tTrue",
            "XTerm*font: fixed",
            "xpilot.clock:\t\tFalse",
            "",
            "xpilot.msg1:\t\thi",
            "#include \"other\"",
            "xpilot.keyThrust: w",
            "not a resource line",
            "xpilot.power:\t\t45.000") + "\n", out,
            "comments, blank lines, other applications and unsaved resources stay; the first line of a saved "
                + "resource is replaced, later ones and continuations go; new ones are appended");
    }

    @Test
    @DisplayName("an empty or newline-less file; CRLF lines")
    void edges() {
        assertAll(
            () -> assertEquals("xpilot.name:\t\tAce\n", RcWriter.update("", List.of(r("name", "Ace"))), "empty file"),
            () -> assertEquals("x: 1\nxpilot.name:\t\tAce\n", RcWriter.update("x: 1", List.of(r("name", "Ace"))),
                "a last line without a newline"),
            () -> assertEquals("xpilot.name:\t\tAce\nb: 2\n", RcWriter.update("name: Bo\r\nb: 2\r\n",
                List.of(r("name", "Ace"))), "CRLF lines"),
            () -> assertNull(RcWriter.resourceName("   ! comment"), "indented comment"),
            () -> assertNull(RcWriter.resourceName(":value"), "no name"),
            () -> assertEquals("power", RcWriter.resourceName("xpilot*Power: 3"), "xpilot* prefix, any case"),
            () -> assertNull(RcWriter.resourceName("emacs.font: x"), "another application"));
    }

    @Test
    @DisplayName("save writes FILE.new, keeps the old file as FILE.bak and renames; a missing file is created")
    void save(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(".xpilotrc");
        RcWriter.save(f, List.of(r("name", "Ace")));
        String first = Files.readString(f);
        boolean noBak = !Files.exists(dir.resolve(".xpilotrc.bak"));
        Files.writeString(f, "! keep\nname: Bo\n", StandardCharsets.ISO_8859_1);
        RcWriter.save(f, List.of(r("name", "Ace"), r("msg1", "café")));
        assertAll(
            () -> assertEquals("xpilot.name:\t\tAce\n", first, "created"),
            () -> assertTrue(noBak, "no backup without an old file"),
            () -> assertEquals("! keep\nname: Bo\n", Files.readString(dir.resolve(".xpilotrc.bak")), "old file kept"),
            () -> assertEquals("! keep\nxpilot.name:\t\tAce\nxpilot.msg1:\t\tcafé\n",
                Files.readString(f, StandardCharsets.ISO_8859_1), "new text in Latin-1"),
            () -> assertFalse(Files.exists(dir.resolve(".xpilotrc.new")), "no temporary left"));
    }
}
