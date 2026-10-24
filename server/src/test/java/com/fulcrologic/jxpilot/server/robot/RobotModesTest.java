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

import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Robots and the game modes (race navigation, race lock rule, the world-backed item gate)")
class RobotModesTest {
    /** Race map: checkpoint A at (10, 10), B at (30, 10). */
    static Simulation race() {
        String[] rows = RobotFixture.open(40, 20, "___");
        char[] r = rows[19 - 10].toCharArray();
        r[10] = 'A';
        r[30] = 'B';
        rows[19 - 10] = new String(r);
        return RobotFixture.sim("timing: yes\n", rows);
    }

    @Test
    @DisplayName("race navigation heads for the robot's next checkpoint (its block corner)")
    void navigation() {
        Simulation s = race();
        DefaultRobot r = s.robots.robot();
        Player p = RobotFixture.robot(s, "R", 20 * 35, 10 * 35);
        RobotData d = s.robots.data(p);
        p.check = 0;
        p.dir = 0;
        r.navigateToCheckpoint(p, d);
        float towardA = p.turnAcc;
        p.check = 1;
        p.turnAcc = 0;
        r.navigateToCheckpoint(p, d);
        float towardB = p.turnAcc;
        assertAll(
            () -> assertTrue(towardA != 0, "facing away from A (left of it): it turns"),
            () -> assertEquals(0f, towardB, "facing B (right, on its row): no turn"),
            () -> assertEquals(DefaultRobot.RM_NAVIGATE, d.robotMode, "navigate mode"));
    }

    @Test
    @DisplayName("in a race a much nearer enemy is only locked when it is not behind in checkpoint and lap")
    void raceLock() {
        Simulation s = race();
        DefaultRobot r = s.robots.robot();
        Player p = RobotFixture.robot(s, "R", 700, 350);
        Player behind = RobotFixture.human(s, "H", 900, 350);
        Player other = RobotFixture.human(s, "O", 1000, 350);
        RobotData d = s.robots.data(p);
        p.lockTagged = DefaultRobot.LOCK_PLAYER;
        p.lockPlId = other.id;
        p.lockDistance = 1000;
        p.check = 1;
        p.round = 1;
        behind.check = 0;
        behind.round = 1;
        r.choosePlayerLock(p, d, behind, 300);
        int kept = p.lockPlId;
        behind.check = 1;
        r.choosePlayerLock(p, d, behind, 300);
        assertAll(
            () -> assertEquals(other.id, kept, "a ship behind in checkpoints is not taken"),
            () -> assertEquals(behind.id, p.lockPlId, "level with the robot: locked"));
    }

    @Test
    @DisplayName("the gate reads balls, treasures, targets and asteroid sizes and drops balls")
    void gate() {
        String[] rows = RobotFixture.open(20, 10, "11");
        char[] r = rows[9 - 2].toCharArray();
        r[5] = '*';
        r[8] = '!';
        rows[9 - 2] = new String(r);
        Simulation s = RobotFixture.sim("teamPlay: yes\n", rows);
        World w = s.world;
        RobotItems it = new RobotItems(s);
        Player p = RobotFixture.human(s, "H", 300, 200);
        int ball = 0;
        w.objects.owner[ball] = p.id;
        w.modes.ballOwner[ball] = p.id;
        p.have |= Has.BALL;
        p.ball = 3;
        w.modes.targetDamage[0] = 1234;
        int ast = RobotFixture.obj(w, Obj.ASTEROID, Obj.NO_ID, 100, 100, 0, 0);
        w.objects.size[ast] = 3;
        boolean treasure = it.treasureHasBall(0);
        int towed = it.towedBall(p);
        int have = it.have(p);
        it.detachBall(p);
        assertAll(
            () -> assertEquals(1234.0, it.targetDamage(0), "target damage"),
            () -> assertTrue(treasure, "the treasure has its ball"),
            () -> assertEquals(3, towed, "the ball the connector reaches for"),
            () -> assertEquals(p.id, it.ballOwner(ball), "ball owner"),
            () -> assertEquals(3, it.asteroidSize(ast), "asteroid size"),
            () -> assertEquals(Obj.NO_ID, w.objects.owner[ball], "detachBall drops the ball"),
            () -> assertEquals(Has.BALL, have & Has.BALL, "HAS_BALL is read through the gate"));
    }
}
