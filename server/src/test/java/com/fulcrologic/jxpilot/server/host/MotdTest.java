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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C motdFileName (netserver.c Get_motd, Send_motd): the message of the day after login. */
class MotdTest {
    @TempDir
    Path dir;

    @Test
    @DisplayName("MotdFile: the file's text, re-read when it changes; empty when missing or empty; at most 30 KB")
    void file() throws IOException {
        Path f = Files.writeString(dir.resolve("motd"), "Welcome!\n", StandardCharsets.ISO_8859_1);
        MotdFile m = new MotdFile(f);
        String first = new String(m.text(), StandardCharsets.ISO_8859_1);
        Files.writeString(f, "Rules: none.\n", StandardCharsets.ISO_8859_1);
        Files.setLastModifiedTime(f, FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 5000));
        String changed = new String(m.text(), StandardCharsets.ISO_8859_1);
        Path big = Files.write(dir.resolve("big"), new byte[Msg.Motd.MAX_SIZE + 100]);
        Path empty = Files.write(dir.resolve("empty"), new byte[0]);
        assertAll(
            () -> assertEquals("Welcome!\n", first, "the text"),
            () -> assertEquals("Rules: none.\n", changed, "a changed file is read again"),
            () -> assertEquals(Msg.Motd.MAX_SIZE, new MotdFile(big).text().length, "cut to 30 KB"),
            () -> assertEquals(0, new MotdFile(empty).text().length, "empty file"),
            () -> assertEquals(0, new MotdFile(dir.resolve("missing")).text().length, "missing file"),
            () -> assertEquals(0, new MotdFile(null).text().length, "no motdFileName"));
    }

    @Test
    @DisplayName("the host sends the MOTD once, with the first event drain after the initial state (not inside it)")
    void sentAfterLogin() throws IOException {
        Path f = Files.writeString(dir.resolve("motd"), "Be nice.\n", StandardCharsets.ISO_8859_1);
        com.fulcrologic.jxpilot.common.config.Options o =
            com.fulcrologic.jxpilot.server.TestWorlds.options(HostFx.BASE, HostFx.FOUR);
        com.fulcrologic.jxpilot.common.config.ConfigParser.parse("motdFileName: " + f + "\n", "cmd",
            com.fulcrologic.jxpilot.common.config.OptionOrigin.COMMAND, o);
        Simulation s = new Simulation(new com.fulcrologic.jxpilot.server.world.World(o, 1));
        SimGameHost h = new SimGameHost(s);
        int id = enter(h, "Ann", 0);
        HostFx.Wire wire = new HostFx.Wire();
        h.initialState(id, wire.out);
        List<String> initial = wire.take();
        h.drainEvents(id, wire.out);
        List<String> first = wire.take();
        h.drainEvents(id, wire.out);
        List<String> second = wire.take();
        assertAll(
            () -> assertTrue(initial.stream().noneMatch(l -> l.startsWith("MOTD")), "not part of the initial state"),
            () -> assertEquals("MOTD 0/9 Be nice.\n", first.get(0), "the first drain starts with the MOTD"),
            () -> assertTrue(second.stream().noneMatch(l -> l.startsWith("MOTD")), "only once"));
    }

    @Test
    @DisplayName("without a MOTD file nothing is sent")
    void none() {
        Simulation s = sim("", HostFx.FOUR);
        SimGameHost h = new SimGameHost(s);
        int id = enter(h, "Ann", 0);
        HostFx.Wire wire = new HostFx.Wire();
        h.initialState(id, wire.out);
        wire.take();
        h.drainEvents(id, wire.out);
        assertTrue(wire.take().stream().noneMatch(l -> l.startsWith("MOTD")), "no MOTD");
    }
}
