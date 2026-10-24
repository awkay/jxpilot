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

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.robot.RobotRoster;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SimGameHostFactory")
class SimGameHostFactoryTest {
    static Options options() {
        return TestWorlds.options(HostFx.BASE, THREE);
    }

    static SimGameHost create(SimGameHostFactory f, Options o) {
        return (SimGameHost) f.create(o, WorldMap.fromOptions(o));
    }

    @Nested
    @DisplayName("create")
    class Create {
        @Test
        @DisplayName("a world from the options with the next seed, its simulation and a host over it")
        void world() {
            AtomicInteger next = new AtomicInteger(41);
            SimGameHostFactory f = new SimGameHostFactory(next::incrementAndGet);
            Options o = options();
            SimGameHost first = create(f, o);
            SimGameHost second = create(f, o);
            World w = first.simulation().world;
            double firstRandom = w.rfrac();
            double secondRandom = second.simulation().world.rfrac();
            assertAll(
                () -> assertEquals(3, w.map.bases().size(), "the map comes from the options"),
                () -> assertEquals("Arena", w.options.getString(Opt.MAP_NAME), "the options are the world's"),
                () -> assertEquals(new World(o, 42).rfrac(), firstRandom, "the first game's RNG is seeded 42"),
                () -> assertEquals(new World(o, 43).rfrac(), secondRandom, "the second game's 43"),
                () -> assertEquals(RobotRoster.defaults().size(), first.simulation().robots.roster().size(),
                    "no robotFile: the default robots"),
                () -> assertTrue(new SimGameHostFactory().create(o, WorldMap.fromOptions(o)) instanceof SimGameHost,
                    "the clock-seeded factory builds a game too"));
        }

        @Test
        @DisplayName("robotFile replaces the roster; an unreadable or empty one keeps the defaults")
        void robotFile(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("robots");
            Files.writeString(file, "name: Zed\n");
            SimGameHostFactory f = new SimGameHostFactory(() -> 1);
            Options withFile = options();
            withFile.force(Opt.ROBOT_FILE, file.toString());
            RobotRoster fromFile = create(f, withFile).simulation().robots.roster();
            Options missing = options();
            missing.force(Opt.ROBOT_FILE, dir.resolve("none").toString());
            Options empty = options();
            empty.force(Opt.ROBOT_FILE, "");
            assertAll(
                () -> assertEquals(1, fromFile.size(), "one robot in the file"),
                () -> assertEquals("Zed", fromFile.def(0).name(), "named Zed"),
                () -> assertEquals(RobotRoster.defaults().size(), create(f, missing).simulation().robots.roster().size(),
                    "an unreadable file keeps the defaults (as C)"),
                () -> assertEquals(RobotRoster.defaults().size(), create(f, empty).simulation().robots.roster().size(),
                    "an empty name is no file"));
        }
    }
}
