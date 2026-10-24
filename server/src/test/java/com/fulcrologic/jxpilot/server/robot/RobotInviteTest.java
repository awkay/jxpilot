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

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.server.sim.Alliances;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.robot.RobotFixture.human;
import static com.fulcrologic.jxpilot.server.robot.RobotFixture.messages;
import static com.fulcrologic.jxpilot.server.robot.RobotFixture.open;
import static com.fulcrologic.jxpilot.server.robot.RobotFixture.robot;
import static com.fulcrologic.jxpilot.server.robot.RobotFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C Robot_default_invite (robotdef.c:319): a robot asked into an alliance answers at once. */
@DisplayName("RobotManager.invite (C Robot_default_invite)")
class RobotInviteTest {
    static Simulation world() {
        return sim("", open(14, 4, "______"));
    }

    @Test
    @DisplayName("an inviter outside alliances: accepted unless at war, not playing, or far below the robot's score")
    void lone() {
        Simulation s = world();
        Alliances a = s.alliances;
        Player bot = robot(s, "Bot", 100, 100);
        Player ok = human(s, "Ok", 200, 100);
        Player enemy = human(s, "Enemy", 300, 100);
        Player paused = human(s, "Paused", 400, 100);
        Player poor = human(s, "Poor", 300, 200);
        bot.score = 300;
        s.robots.data(bot).attack = 50;
        s.robots.data(bot).defense = 20;
        ok.score = 271;
        poor.score = 269;
        enemy.score = 1000;
        paused.status |= Obj.PAUSE;
        DefaultRobot.setWar(s.robots.data(bot), enemy.id);
        long from = s.world.events.seq();
        a.invite(enemy, bot);
        a.invite(paused, bot);
        a.invite(poor, bot);
        List<String> refusals = messages(s.world, from);
        a.invite(ok, bot);
        assertAll(
            () -> assertEquals(List.of(" < Bot has declined your invitation for an alliance >",
                " < Bot has declined your invitation for an alliance >",
                " < Bot has declined your invitation for an alliance >"), refusals,
                "war, pause, and a score below 300 - max(300/10, 20) = 270 are refused"),
            () -> assertTrue(s.world.allied(ok, bot), "271 is enough: accepted"),
            () -> assertEquals(Obj.NO_ID, enemy.invite, "refused invitations are dropped"));
    }

    @Test
    @DisplayName("an inviter in an alliance: refused when a member is at war with the robot or the average is too low")
    void alliance() {
        Simulation s = world();
        Alliances a = s.alliances;
        Player bot = robot(s, "Bot", 100, 100);
        Player x = human(s, "X", 200, 100);
        Player y = human(s, "Y", 300, 100);
        bot.score = 0;
        s.robots.data(bot).defense = 50;
        x.score = -60;
        y.score = -60;
        a.invite(x, y);
        a.accept(y, x);
        a.invite(x, bot);
        boolean lowAvg = s.world.allied(x, bot);
        y.score = -40;
        DefaultRobot.setWar(s.robots.data(bot), y.id);
        a.invite(x, bot);
        boolean war = s.world.allied(x, bot);
        DefaultRobot.setWar(s.robots.data(bot), Obj.NO_ID);
        a.invite(x, bot);
        assertAll(
            () -> assertFalse(lowAvg, "average -60 is below 0 - 50"),
            () -> assertFalse(war, "a member at war"),
            () -> assertTrue(s.world.allied(x, bot), "average -50 is not below -50: the robot joins"),
            () -> assertEquals(3, a.memberCount(x.alliance), "three members"));
    }

    @Test
    @DisplayName("a robot allied with a human leaves the decision to the human: it refuses")
    void humanDecides() {
        Simulation s = world();
        Alliances a = s.alliances;
        Player bot = robot(s, "Bot", 100, 100);
        Player friend = human(s, "Friend", 200, 100);
        Player other = human(s, "Other", 300, 100);
        a.invite(friend, bot);
        boolean allied = s.world.allied(friend, bot);
        a.invite(other, bot);
        assertAll(
            () -> assertTrue(allied, "first the robot joined Friend"),
            () -> assertFalse(s.world.allied(other, bot), "then refuses Other"));
    }

    @Test
    @DisplayName("reloadOptions: the robot type re-reads its options after a run-time change")
    void reload() {
        Simulation s = world();
        s.world.options.force(Opt.MAX_OFFENSIVE_ITEMS, "7");
        s.world.options.force(Opt.ALLOW_NUKES, "yes");
        s.world.options.force(Opt.ROBOT_LEAVE_LIFE, "9");
        int before = s.robots.robot().maxOffensiveItems;
        s.robots.reloadOptions();
        assertAll(
            () -> assertEquals(100, before, "the start value"),
            () -> assertEquals(7, s.robots.robot().maxOffensiveItems, "re-read"),
            () -> assertTrue(s.robots.robot().allowNukes, "re-read"),
            () -> assertEquals(9, s.robots.robotLeaveLife, "the manager's own"));
    }
}
