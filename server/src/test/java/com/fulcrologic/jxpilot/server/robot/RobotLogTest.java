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
package com.fulcrologic.jxpilot.server.robot;

import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.host.SimGameHost;
import com.fulcrologic.jxpilot.server.host.SimGameHostFactory;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** C logRobots (robot.c:835-837). */
class RobotLogTest {

    @Test
    @DisplayName("each robot that joins is logged as \"NAME (players, robotRealName) starts at startpos BASE.\"")
    void logs() {
        Simulation s = RobotFixture.sim("robotRealName: droid\n", RobotFixture.open(20, 20, "___"));
        List<String> log = new ArrayList<>();
        s.robots.log = l -> log.add(l.toString());
        TestWorlds.join(s, "H", 0);
        Player r = s.robots.create();
        assertEquals(List.of(r.name + " (2, droid) starts at startpos " + r.homeBase + "."), log, "one line");
    }

    @Test
    @DisplayName("nothing is logged with logRobots off or without a log")
    void off() {
        Options o = TestWorlds.options(RobotFixture.FRAME, RobotFixture.open(20, 20, "___"));
        com.fulcrologic.jxpilot.common.config.ConfigParser.parse("logRobots: no\n", "t",
            com.fulcrologic.jxpilot.common.config.OptionOrigin.COMMAND, o);
        Simulation s = new Simulation(new com.fulcrologic.jxpilot.server.world.World(o, 1));
        List<String> log = new ArrayList<>();
        s.robots.log = l -> log.add(l.toString());
        s.robots.create();
        Simulation quiet = RobotFixture.sim("", RobotFixture.open(20, 20, "___"));
        quiet.robots.create();
        assertAll(
            () -> assertEquals(List.of(), log, "logRobots off"),
            () -> assertEquals(1, quiet.robots.numRobots(), "no log set: the robot still joins"));
    }

    @Test
    @DisplayName("the factory hands its log to the robots")
    void factory() {
        Options o = TestWorlds.options("", RobotFixture.open(20, 20, "___"));
        List<String> log = new ArrayList<>();
        java.util.function.Consumer<CharSequence> sink = l -> log.add(l.toString());
        SimGameHost host = (SimGameHost) new SimGameHostFactory(() -> 1, sink).create(o, WorldMap.fromOptions(o));
        assertSame(sink, host.simulation().robots.log, "wired");
    }
}
