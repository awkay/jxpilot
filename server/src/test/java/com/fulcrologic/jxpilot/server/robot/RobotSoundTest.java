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

import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("robot war sound (robot.c Robot_war)")
class RobotSoundTest {
    @Test
    @DisplayName("a robot declaring war plays DECLARE_WAR to everyone, once per new war")
    void declareWar() {
        Simulation s = RobotFixture.sim("", RobotFixture.open(20, 20, "___"));
        Player v = RobotFixture.robot(s, "Robo", 100, 100);
        Player k = RobotFixture.human(s, "Killer", 300, 300);
        Player other = RobotFixture.human(s, "Other", 500, 500);
        k.score = 1000;
        k.audio.clear();
        other.audio.clear();
        s.robots.war(v, k);
        int first = other.audio.count();
        int event = other.audio.event(0);
        other.audio.clear();
        s.robots.war(v, k);
        assertAll(
            () -> assertEquals(1, first, "the first war queues one sound"),
            () -> assertEquals(SoundEvent.DECLARE_WAR.ordinal(), event, "it is DECLARE_WAR"),
            () -> assertEquals(SoundEvent.MAX_VOLUME, k.audio.volume(0), "the enemy hears it at full volume"),
            () -> assertEquals(0, other.audio.count(), "a war already declared on the same player plays nothing"),
            () -> assertEquals(0, v.audio.count(), "the robot itself hears nothing"));
    }
}
