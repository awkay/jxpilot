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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C Server_log_admin_message (server.c:731-765) behind "god:" chat. */
class AdminLogTest {
    static final ZonedDateTime T = ZonedDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC);

    @TempDir
    Path dir;

    private AdminLog log(Path file, int limit) {
        return new AdminLog(file, limit, () -> T);
    }

    @Test
    @DisplayName("an existing file gets the C entry; the sender gets the echo \"text [name]:[GOD]\"")
    void logs() throws IOException {
        Path f = Files.createFile(dir.resolve("log"));
        Simulation s = sim("", THREE);
        Player ann = s.ships.join("Ann", 0);
        Talk t = new Talk(s.world, log(f, 20202));
        List<String> ev = TalkTest.say(s.world, t, ann, "god:the server is laggy");
        assertAll(
            () -> assertEquals("04 Mar 05:06:07[Ann]{@()|}:\n\tthe server is laggy\n",
                Files.readString(f, StandardCharsets.ISO_8859_1), "appended entry"),
            () -> assertEquals(List.of("MESSAGE " + Msg.Text.KIND_PRIVATE + " 1 1 the server is laggy [Ann]:[GOD]"), ev,
                "echo to the sender only"));
    }

    @Test
    @DisplayName("nothing is written without a file, with a limit <= 0, or once size + 80 or the text no longer fits")
    void limits() throws IOException {
        Path f = Files.writeString(dir.resolve("log"), "x".repeat(100));
        assertAll(
            () -> assertFalse(log(null, 20202).log("A", "hi"), "no file name"),
            () -> assertFalse(log(dir.resolve("missing"), 20202).log("A", "hi"), "the file must already exist"),
            () -> assertFalse(log(dir, 20202).log("A", "hi"), "a directory is no log file"),
            () -> assertFalse(log(f, 0).log("A", "hi"), "limit 0"),
            () -> assertFalse(log(f, 180).log("A", "hi"), "size + 80 = 180 is not below 180"),
            () -> assertFalse(log(f, 183).log("A", "abc"), "room 3 does not exceed the length 3"),
            () -> assertEquals(100, Files.size(f), "untouched"),
            () -> assertTrue(log(f, 184).log("A", "abc"), "room 4 > 3: written"));
    }

    @Test
    @DisplayName("showtime: day, month name and time, zero padded")
    void showtime() {
        assertEquals("04 Mar 05:06:07", AdminLog.showtime(T), "C format");
    }
}
