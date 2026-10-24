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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.ModeFx.raceSim;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.ship;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.targetSim;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The modes review's C findings: break positions, tanks in races, punishments, asteroids and repair. */
@DisplayName("modes review (asteroid.c, collision.c, player.c, play.c, walls.c, update.c)")
class ModesReviewTest {
    @Test
    @DisplayName("Break_asteroid splits from the integer pixel position (asteroid.c:131-211)")
    void breakFromIntPixels() {
        Simulation s = SimFixture.sim(SimFixture.FPS14, 30, 30, "______");
        World w = s.world;
        Objects o = w.objects;
        int h = s.asteroids.make(300.625, 300.0, 2, 32, 10f); // moving up: dir 32, split at dir 0
        s.asteroids.broken(h);
        int x1 = o.px[w.modes.asteroidList[0]];
        int x2 = o.px[w.modes.asteroidList[1]];
        assertAll(
            () -> assertEquals(312, x1, "(int)(300 + 12.8)"),
            () -> assertEquals(287, x2, "(int)(300 - 12.8)"));
    }

    @Nested
    @DisplayName("tanks")
    class Tanks {
        @Test
        @DisplayName("a tank never passes a checkpoint (collision.c:540)")
        void noCheckpoint() {
            Simulation s = raceSim("");
            World w = s.world;
            Player t = ship(s, "T", 0, 10 * 35, 5 * 35);
            t.tank = true;
            long from = w.events.seq();
            s.race.playerFrame(t);
            assertAll(
                () -> assertEquals(0, t.round, "no lap"),
                () -> assertEquals(0, t.check, "no checkpoint"),
                () -> assertEquals(java.util.List.of(), SimFixture.messages(w, from), "no message"));
        }

        @Test
        @DisplayName("race status ignores tanks: the last racer alone after a finisher ends the race (player.c:1306)")
        void raceStatus() {
            Simulation s = raceSim("limitedLives: yes\nworldLives: 3\n");
            Player a = ship(s, "A", 0, 100, 100);
            Player b = ship(s, "B", 0, 200, 100);
            Player tank = ship(s, "T", 0, 300, 100);
            tank.tank = true;
            a.status |= Obj.GAME_OVER | Obj.RACE_OVER;
            b.round = 1;
            s.race.gameStatus();
            assertAll(
                () -> assertEquals(0, b.round, "the race ended and was reset"),
                () -> assertEquals(0, tank.status & Obj.KILLED, "Race_game_over leaves the tank alone"));
        }

        @Test
        @DisplayName("a tank killed by an asteroid gives its owner asteroidPoints (collision.c:1182-1188)")
        void asteroidOwnerPoints() {
            Simulation s = SimFixture.sim(SimFixture.FPS14 + "asteroidPoints: 2\n", 30, 30, "______");
            Player owner = ready(s, "O", 0, 700, 700);
            Player tank = ready(s, "T", 0, 300, 300);
            tank.tank = true;
            tank.lockPlId = owner.id;
            int h = s.asteroids.make(305, 300, 4, 0, 3f);
            double before = owner.score;
            s.asteroids.playerHit(tank, h);
            Player robot = ready(s, "R", 0, 500, 500);
            double rBefore = owner.score;
            robot.lockPlId = owner.id;
            s.asteroids.playerHit(robot, s.asteroids.make(505, 500, 4, 0, 3f));
            assertAll(
                () -> assertTrue((tank.status & Obj.KILLED) != 0, "(the tank died)"),
                () -> assertEquals(before + 2, owner.score, 1e-9, "its owner scores"),
                () -> assertEquals(rBefore, owner.score, 1e-9, "a ship that is not a tank does not"));
        }

        @Test
        @DisplayName("asteroids may appear near tanks and robots, not near humans (asteroid.c:412 IS_HUMAN_IND)")
        void placement() {
            Simulation s = SimFixture.sim(SimFixture.FPS14 + "asteroidProb: 1\nmaxAsteroidDensity: 1\n", 6, 6, "_");
            World w = s.world;
            Player t = ready(s, "T", 0, 3 * 35, 3 * 35);
            t.tank = true;
            for (int k = 0; k < 50; k++) {
                s.asteroids.place();
            }
            int nearTank = w.modes.asteroidCount;
            Simulation h = SimFixture.sim(SimFixture.FPS14 + "asteroidProb: 1\nmaxAsteroidDensity: 1\n", 6, 6, "_");
            ready(h, "H", 0, 3 * 35, 3 * 35);
            for (int k = 0; k < 50; k++) {
                h.asteroids.place();
            }
            assertAll(
                () -> assertTrue(nearTank > 0, "a tank does not keep them away"),
                () -> assertEquals(0, h.world.modes.asteroidCount, "a human does (5 blocks cover the 6x6 map)"));
        }

        @Test
        @DisplayName("a target's team with only a tank left has nobody in the game: no team scores (walls.c:1735)")
        void targetTeamOfTanks() {
            Simulation s = targetSim("");
            World w = s.world;
            Player p = ship(s, "P", 1, 300, 100);
            Player tank = ship(s, "T", 2, 500, 100);
            tank.tank = true;
            double pBefore = p.score;
            s.targets.damage(1, Obj.HEAT_SHOT, p.id, 1, 0, 0, 1, true, 0, 1f, -1);
            assertAll(
                () -> assertTrue(w.targetDeadTime[1] > 0, "(the target died)"),
                () -> assertEquals(pBefore, p.score, 1e-9, "no target score"),
                () -> assertEquals(0.0, w.teamScore[2], 1e-9, "no team penalty"));
        }
    }

    @Test
    @DisplayName("target repair goes round all num_tanks + 1 tanks (update.c:1006-1027) and restores the current one")
    void repairTanks() {
        Simulation s = targetSim("");
        Player p = ship(s, "P", 1, 87, 110);
        s.tanks.add(p, 100000);
        s.tanks.add(p, 100000);
        ModeState m = s.world.modes;
        m.targetDamage[0] = ModeState.TARGET_DAMAGE / 2;
        p.have |= Has.REPAIR;
        s.targets.startRepair(p);
        int d0 = m.targetDamage[0];
        double f0 = p.fuel;
        int current = p.currentTank;
        s.targets.repair(p);
        int per = s.targets.fuelRepairPerFrame;
        Player q = ship(s, "Q", 1, 87, 110);
        q.tankFuel[0] = Ships.REFUEL_RATE;
        q.fuel = Ships.REFUEL_RATE;
        q.have |= Has.REPAIR;
        s.targets.startRepair(q);
        s.targets.repair(q);
        assertAll(
            () -> assertEquals(2, p.numTanks, "(two extra tanks)"),
            () -> assertEquals(3 * per, m.targetDamage[0] - d0, "three repairs"),
            () -> assertEquals(-3.0 * Ships.REFUEL_RATE, p.fuel - f0, 1e-9, "three tanks' worth of fuel"),
            () -> assertEquals(current, p.currentTank, "the current tank restored"),
            () -> assertFalse((q.used & Has.REPAIR) != 0, "a tank with only REFUEL_RATE stops the repair"));
    }
}
