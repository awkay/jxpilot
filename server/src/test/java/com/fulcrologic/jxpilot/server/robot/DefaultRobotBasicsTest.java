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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DefaultRobot (robotdef.c): creation, war state, geometry helpers, look-ahead rays and evasion. */
class DefaultRobotBasicsTest {

    // ------------------------------------------------------------------ create

    @Nested
    @DisplayName("create (Robot_default_create)")
    class Create {
        private RobotData fresh() {
            RobotData d = new RobotData();
            d.fuelL1 = 100 * 256;
            d.fuelL2 = 200 * 256;
            d.fuelL3 = 500 * 256;
            d.robotMode = 99;
            d.robotCount = 7;
            d.robotLock = 3;
            d.robotLockId = 9;
            d.longtermMode = 5;
            d.lastUsedEcm = 1;
            d.lastDroppedMine = 2;
            d.lastFiredMissile = 3;
            d.lastThrownBall = 4;
            return d;
        }

        @Test
        @DisplayName("a valid config sets attack/defense; speeds and fuel levels follow the C formulas")
        void validConfig() {
            Simulation s = RobotFixture.sim("", RobotFixture.open(6, 4, "_"));
            World w = s.world;
            DefaultRobot r = s.robots.robot();
            RobotData d = fresh();
            w.rng.seed(7);
            double[] f = RobotFixture.rfracs(7, 3);
            r.create(d, "94 20");
            assertAll(
                () -> assertEquals(94, d.attack, "attack from the config"),
                () -> assertEquals(20, d.defense, "defense from the config"),
                () -> assertEquals(DefaultRobot.RM_TAKE_OFF, d.robotMode, "starts taking off"),
                () -> assertEquals(0, d.robotCount, "count 0"),
                () -> assertEquals(DefaultRobot.LOCK_NONE, d.robotLock, "no war"),
                () -> assertEquals(0, d.robotLockId, "no war target"),
                () -> assertEquals(0, d.longtermMode, "no long term mode"),
                () -> assertEquals(6.0, d.normalSpeed, "normal speed 6"),
                () -> assertEquals(18.0, d.attackSpeed, "attack speed 15 + 94/25"),
                () -> assertEquals(31.0, d.maxSpeed, "max speed 30 + 94/50 - 20/50"),
                () -> assertEquals(500 * 256 - 74 + (int) ((f[0] - 0.5f) * 20), d.fuelL3, "level 3"),
                () -> assertEquals(200 * 256 - 29 + (int) ((f[1] - 0.5f) * 8), d.fuelL2, "level 2: 2*(-74)/5 = -29"),
                () -> assertEquals(100 * 256 - 14 + (int) ((f[2] - 0.5f) * 4), d.fuelL1, "level 1: -74/5 = -14"),
                () -> assertEquals(0, d.lastUsedEcm + d.lastDroppedMine + d.lastFiredMissile + d.lastThrownBall,
                    "weapon timers reset"),
                () -> assertEquals(RobotFixture.rfracs(7, 4)[3], w.rfrac(), "exactly three rfrac calls"));
        }

        @Test
        @DisplayName("an unreadable config draws attack = (int)(rfrac*99.5), defense = 100 - attack, limited to 1..99")
        void invalidConfig() {
            Simulation s = RobotFixture.sim("", RobotFixture.open(6, 4, "_"));
            World w = s.world;
            DefaultRobot r = s.robots.robot();
            for (String cfg : new String[] {"abc", "", null, "5"}) {
                RobotData d = fresh();
                w.rng.seed(11);
                int a = (int) (RobotFixture.rfracs(11, 1)[0] * 99.5f);
                r.create(d, cfg);
                assertAll(
                    () -> assertEquals(Math.max(1, Math.min(99, a)), d.attack, "random attack for " + cfg),
                    () -> assertEquals(Math.max(1, Math.min(99, 100 - a)), d.defense, "complementary defense"));
            }
        }

        @Test
        @DisplayName("limits a random attack of 0 or 99+ into 1..99")
        void limits() {
            Simulation s = RobotFixture.sim("", RobotFixture.open(6, 4, "_"));
            World w = s.world;
            DefaultRobot r = s.robots.robot();
            int low = RobotFixture.seedWithFirst(true, 1 / 99.5);
            RobotData d = fresh();
            w.rng.seed(low);
            r.create(d, "x");
            assertAll(
                () -> assertEquals(1, d.attack, "attack 0 is raised to 1"),
                () -> assertEquals(99, d.defense, "defense 100 is lowered to 99"));
            int high = RobotFixture.seedWithFirst(false, 99 / 99.5);
            RobotData e = fresh();
            w.rng.seed(high);
            r.create(e, "x");
            assertAll(
                () -> assertEquals(99, e.attack, "attack 99 stays"),
                () -> assertEquals(1, e.defense, "defense 1 stays"));
        }

        @Test
        @DisplayName("race mode uses speeds 10, 25 + attack/10 and 50 + attack/20 - defense/50")
        void raceSpeeds() {
            Simulation s = RobotFixture.sim("timing: yes\n", "      ", "  A   ", "      ", " _    ");
            assertTrue(s.world.map.timing(), "race mode is on (map has a checkpoint)");
            RobotData d = fresh();
            s.robots.robot().create(d, "60 50");
            assertAll(
                () -> assertEquals(10.0, d.normalSpeed, "normal 10"),
                () -> assertEquals(31.0, d.attackSpeed, "attack 25 + 6"),
                () -> assertEquals(52.0, d.maxSpeed, "max 50 + 3 - 1"));
        }
    }

    @Nested
    @DisplayName("scanAttackDefense (sscanf \" %d %d\")")
    class Scan {
        @Test
        @DisplayName("reads two optionally signed ints after white space and ignores the rest")
        void scan() {
            DefaultRobot r = RobotFixture.sim("", RobotFixture.open(6, 4, "_")).robots.robot();
            assertAll(
                () -> assertTrue(r.scanAttackDefense("  -3\t+4 rest"), "signed numbers"),
                () -> assertEquals(-3, r.scannedAttack, "first"),
                () -> assertEquals(4, r.scannedDefense, "second"),
                () -> assertFalse(r.scanAttackDefense("12"), "one number is not enough"),
                () -> assertFalse(r.scanAttackDefense("x 1 2"), "must start with a number"),
                () -> assertFalse(r.scanAttackDefense("1 -x"), "a sign alone is not a number"),
                () -> assertFalse(r.scanAttackDefense(null), "null"),
                () -> assertTrue(r.scanAttackDefense("10 90"), "plain"),
                () -> assertEquals(10, r.scannedAttack, "10"),
                () -> assertEquals(90, r.scannedDefense, "90"));
        }
    }

    @Nested
    @DisplayName("war state")
    class War {
        @Test
        @DisplayName("setWar sets or clears LOCK_PLAYER; warOnPlayer reports the id only while at war")
        void war() {
            RobotData d = new RobotData();
            assertEquals(Obj.NO_ID, DefaultRobot.warOnPlayer(d), "no war at first");
            DefaultRobot.setWar(d, 7);
            assertAll(
                () -> assertEquals(7, DefaultRobot.warOnPlayer(d), "at war with 7"),
                () -> assertEquals(DefaultRobot.LOCK_PLAYER, d.robotLock, "LOCK_PLAYER set"));
            d.robotLock |= DefaultRobot.LOCK_VISIBLE;
            DefaultRobot.setWar(d, Obj.NO_ID);
            assertAll(
                () -> assertEquals(Obj.NO_ID, DefaultRobot.warOnPlayer(d), "peace"),
                () -> assertEquals(7, d.robotLockId, "the id stays"),
                () -> assertEquals(DefaultRobot.LOCK_VISIBLE, d.robotLock, "only LOCK_PLAYER is cleared"));
        }

        @Test
        @DisplayName("goHome takes off again and forgets the long term mode")
        void goHome() {
            RobotData d = new RobotData();
            d.robotMode = DefaultRobot.RM_ATTACK;
            d.longtermMode = DefaultRobot.NEED_FUEL;
            RobotFixture.sim("", RobotFixture.open(6, 4, "_")).robots.robot().goHome(d);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_TAKE_OFF, d.robotMode, "TAKE_OFF"),
                () -> assertEquals(0, d.longtermMode, "cleared"));
        }

        @Test
        @DisplayName("within(now, then, diff) is now <= then and then - now < diff")
        void within() {
            assertAll(
                () -> assertTrue(DefaultRobot.within(5, 5, 1), "same time"),
                () -> assertTrue(DefaultRobot.within(5, 14, 10), "9 apart"),
                () -> assertFalse(DefaultRobot.within(5, 15, 10), "10 apart"),
                () -> assertFalse(DefaultRobot.within(6, 5, 10), "then before now"));
        }
    }

    @Nested
    @DisplayName("roundTick (Robot_default_round_tick)")
    class RoundTick {
        @Test
        @DisplayName("visibility shrinks with the number of robots; enemy distance is the hypotenuse when it fits")
        void small() {
            Simulation s = RobotFixture.sim("", RobotFixture.open(6, 4, "_"));
            DefaultRobot r = s.robots.robot();
            r.roundTick(4);
            assertAll(
                () -> assertEquals(256 + (744.0 * 252) / 256, r.visibilityDistance, 1e-9, "256 + 744*252/256"),
                () -> assertEquals(252.0, r.maxEnemyDistance, "hypot(210, 140) = 252 < visibility"));
        }

        @Test
        @DisplayName("a world larger than the visibility limits the enemy distance too")
        void large() {
            Simulation s = RobotFixture.sim("", RobotFixture.open(60, 60, "_"));
            DefaultRobot r = s.robots.robot();
            r.roundTick(16);
            double hyp = s.world.params.hypotenuse;
            assertAll(
                () -> assertEquals(2969.0, hyp, "hypot(2100, 2100) truncated"),
                () -> assertEquals(256 + (744.0 * 240) / 256, r.visibilityDistance, 1e-9, "16 robots"),
                () -> assertEquals(512 + ((hyp - 512) * 240) / 256, r.maxEnemyDistance, 1e-9, "512 + (hyp-512)*240/256"));
        }
    }

    // ------------------------------------------------------------------ geometry

    @Nested
    @DisplayName("travelDir")
    class TravelDir {
        @Test
        @DisplayName("is the gravity direction at speed 0.2 or less, else the velocity direction")
        void travel() {
            Simulation s = RobotFixture.sim("gravity: -0.5\n", RobotFixture.open(6, 4, "_"));
            DefaultRobot r = s.robots.robot();
            Player p = RobotFixture.robot(s, "R", 100, 70);
            RobotFixture.velocity(p, 0.1, 0.1);
            int slow = r.travelDir(p);
            p.velocity = Math.nextDown(0.2f);
            int edge = r.travelDir(p);
            p.velocity = 0.2f;
            int above = r.travelDir(p);
            RobotFixture.velocity(p, 3, 3);
            int fast = r.travelDir(p);
            assertAll(
                () -> assertEquals(96, slow, "gravity points down"),
                () -> assertEquals(96, edge, "the largest float not above 0.2 still counts as slow"),
                () -> assertEquals(16, above, "the float 0.2f is above C's double 0.2: the velocity's direction"),
                () -> assertEquals(16, fast, "45 degrees"));
        }
    }

    @Nested
    @DisplayName("reallyEmptySpace (Really_empty_space)")
    class Empty {
        static final String[] ROWS = {"xqwas#r!*@", "+%&       ", "1       2 "};

        @Test
        @DisplayName("outside team play: space-like blocks are empty, walls/fuel/cannon/target/treasure are not")
        void solo() {
            Simulation s = RobotFixture.sim("", ROWS);
            DefaultRobot r = s.robots.robot();
            Player p = RobotFixture.robot(s, "R", 100, 17);
            int y = 2;
            assertAll(
                () -> assertFalse(r.reallyEmptySpace(p, 0, y), "filled"),
                () -> assertFalse(r.reallyEmptySpace(p, 1, y), "diagonal q"),
                () -> assertFalse(r.reallyEmptySpace(p, 2, y), "diagonal w"),
                () -> assertFalse(r.reallyEmptySpace(p, 3, y), "diagonal a"),
                () -> assertFalse(r.reallyEmptySpace(p, 4, y), "diagonal s"),
                () -> assertFalse(r.reallyEmptySpace(p, 5, y), "fuel"),
                () -> assertFalse(r.reallyEmptySpace(p, 6, y), "cannon"),
                () -> assertFalse(r.reallyEmptySpace(p, 7, y), "target"),
                () -> assertFalse(r.reallyEmptySpace(p, 8, y), "treasure"),
                () -> assertTrue(r.reallyEmptySpace(p, 9, y), "wormhole"),
                () -> assertTrue(r.reallyEmptySpace(p, 0, 1), "gravity"),
                () -> assertTrue(r.reallyEmptySpace(p, 1, 1), "item concentrator"),
                () -> assertTrue(r.reallyEmptySpace(p, 2, 1), "asteroid concentrator"),
                () -> assertTrue(r.reallyEmptySpace(p, 5, 1), "space"),
                () -> assertTrue(r.reallyEmptySpace(p, 0, 0), "base"));
        }

        @Test
        @DisplayName("in team play an own target is empty without targetTeamCollision, an own cannon with teamImmunity")
        void teams() {
            String opts = "teamPlay: yes\nreserveRobotTeam: no\nteamCannons: yes\n";
            Simulation open = RobotFixture.sim(opts + "targetTeamCollision: no\n", ROWS);
            Player own = open.ships.join("R", 2, true);
            Player other = open.ships.join("S", 1, true);
            DefaultRobot r = open.robots.robot();
            Simulation strict = RobotFixture.sim(opts + "targetTeamCollision: yes\nteamImmunity: no\n", ROWS);
            Player own2 = strict.ships.join("R", 2, true);
            DefaultRobot r2 = strict.robots.robot();
            Simulation notTeam = RobotFixture.sim("teamCannons: yes\ntargetTeamCollision: no\n", ROWS);
            Player loner = RobotFixture.robot(notTeam, "L", 100, 17);
            assertAll(
                () -> assertEquals(2, own.team, "on team 2"),
                () -> assertEquals(2, open.world.map.targets().get(0).team(), "the target is team 2's"),
                () -> assertEquals(2, open.world.map.cannons().get(0).team(), "the cannon is team 2's"),
                () -> assertTrue(r.reallyEmptySpace(own, 7, 2), "own target without targetTeamCollision"),
                () -> assertTrue(r.reallyEmptySpace(own, 6, 2), "own cannon with teamImmunity"),
                () -> assertFalse(r.reallyEmptySpace(other, 7, 2), "an enemy target"),
                () -> assertFalse(r.reallyEmptySpace(other, 6, 2), "an enemy cannon"),
                () -> assertFalse(r2.reallyEmptySpace(own2, 7, 2), "own target with targetTeamCollision"),
                () -> assertFalse(r2.reallyEmptySpace(own2, 6, 2), "own cannon without teamImmunity"),
                () -> assertFalse(notTeam.robots.robot().reallyEmptySpace(loner, 7, 2), "no team play: target"),
                () -> assertFalse(notTeam.robots.robot().reallyEmptySpace(loner, 6, 2), "no team play: cannon"));
        }

        @Test
        @DisplayName("SPACE_BLOCKS holds the C walls.c space blocks")
        void mask() {
            assertAll(
                () -> assertTrue((DefaultRobot.SPACE_BLOCKS & BlockType.bit(BlockType.DECOR_FILLED)) != 0, "decor"),
                () -> assertTrue((DefaultRobot.SPACE_BLOCKS & BlockType.bit(BlockType.CHECK)) != 0, "checkpoint"),
                () -> assertTrue((DefaultRobot.SPACE_BLOCKS & BlockType.bit(BlockType.FRICTION)) != 0, "friction"),
                () -> assertFalse((DefaultRobot.SPACE_BLOCKS & BlockType.bit(BlockType.TARGET)) != 0, "target"),
                () -> assertEquals(Integer.bitCount(DefaultRobot.SPACE_BLOCKS), 20, "20 block types"));
        }
    }

    @Nested
    @DisplayName("rayBlocked (the Check_robot_evade scan)")
    class Ray {
        @Test
        @DisplayName("an empty path is clear; a wall within the stop distance + half a block blocks it")
        void walls() {
            String[] rows = RobotFixture.open(20, 20, "_");
            char[] r10 = rows[19 - 10].toCharArray();
            r10[14] = 'x';
            rows[19 - 10] = new String(r10);
            Simulation s = RobotFixture.sim("", rows);
            DefaultRobot r = s.robots.robot();
            Player p = RobotFixture.robot(s, "R", 360, 360);
            assertAll(
                () -> assertFalse(r.rayBlocked(p, 360, 360, 0, 0, 0), "only the own block"),
                () -> assertFalse(r.rayBlocked(p, 360, 360, 0, 119, 0), "steps stop at 119 (x 479, block 13)"),
                () -> assertTrue(r.rayBlocked(p, 360, 360, 0, 120, 0), "136 < 120 + 17: x 496 is the wall block 14"),
                () -> assertFalse(r.rayBlocked(p, 360, 360, 32, 300, 0), "upwards is open"));
        }

        @Test
        @DisplayName("leaving the world blocks without edgeWrap; with it the scan wraps around")
        void edges() {
            Simulation s = RobotFixture.sim("", RobotFixture.open(10, 10, "_"));
            Player p = RobotFixture.robot(s, "R", 340, 175);
            Simulation wrap = RobotFixture.sim("edgeWrap: yes\n", RobotFixture.open(10, 10, "__"));
            Player q = RobotFixture.robot(wrap, "R", 340, 175);
            Player q2 = RobotFixture.robot(wrap, "S", 10, 10);
            assertAll(
                () -> assertTrue(s.robots.robot().rayBlocked(p, 340, 175, 0, 20, 0), "past the right edge"),
                () -> assertTrue(s.robots.robot().rayBlocked(p, 340, 175, 32, 200, 32), "past the top"),
                () -> assertFalse(wrap.robots.robot().rayBlocked(q, 340, 175, 0, 20, 0), "wraps right"),
                () -> assertFalse(wrap.robots.robot().rayBlocked(q, 340, 175, 32, 200, 32), "wraps up"),
                () -> assertFalse(wrap.robots.robot().rayBlocked(q2, 10, 10, 64, 60, 64), "wraps left onto x=9"),
                () -> assertFalse(wrap.robots.robot().rayBlocked(q2, 10, 40, 96, 60, 96), "wraps down"));
        }

        @Test
        @DisplayName("strong gravity blocks when C's gravity direction is within a quarter turn of the travel")
        void gravity() {
            Simulation s = RobotFixture.sim("gravity: -1\n", RobotFixture.open(20, 20, "_"));
            DefaultRobot r = s.robots.robot();
            Player p = RobotFixture.robot(s, "R", 175, 175);
            int gd = (int) com.fulcrologic.jxpilot.common.math.XMath.findDir(0 - 175, -1 - 175);
            Simulation weak = RobotFixture.sim("gravity: -0.7\n", RobotFixture.open(20, 20, "_"));
            Player w = RobotFixture.robot(weak, "R", 175, 175);
            assertAll(
                () -> assertEquals(80, gd, "findDir(gravity - position) points to the origin"),
                () -> assertTrue(r.rayBlocked(p, 175, 175, 0, 0, gd), "travelling that way"),
                () -> assertTrue(r.rayBlocked(p, 175, 175, 0, 0, gd - 32), "a quarter turn off"),
                () -> assertFalse(r.rayBlocked(p, 175, 175, 0, 0, gd - 33), "more than a quarter turn"),
                () -> assertTrue(r.rayBlocked(p, 175, 175, 0, 0, gd + 32), "a quarter turn the other way"),
                () -> assertFalse(r.rayBlocked(p, 175, 175, 0, 0, gd + 33), "beyond"),
                () -> assertFalse(weak.robots.robot().rayBlocked(w, 175, 175, 0, 0, 80),
                    "gravity 0.7: 0.49 < 0.5 is not strong"));
        }
    }

    // ------------------------------------------------------------------ evade

    @Nested
    @DisplayName("checkEvade (Check_robot_evade)")
    class Evade {
        private Simulation wallWorld(int wallX, int wallY) {
            String[] rows = RobotFixture.open(40, 40, "__");
            if (wallX >= 0) {
                char[] r = rows[39 - wallY].toCharArray();
                r[wallX] = 'x';
                rows[39 - wallY] = new String(r);
            }
            return RobotFixture.sim("", rows);
        }

        @Test
        @DisplayName("with no reason to evade returns false and changes nothing")
        void calm() {
            Simulation s = wallWorld(-1, 0);
            Player p = RobotFixture.robot(s, "R", 700, 700);
            RobotData d = s.robots.data(p);
            RobotFixture.velocity(p, 5, 0);
            p.turnSpeed = 10;
            p.power = 20;
            p.turnAcc = 3;
            boolean e = s.robots.robot().checkEvade(p, d, -1, null);
            assertAll(
                () -> assertFalse(e, "no evasion"),
                () -> assertEquals(10, p.turnSpeed, "turnspeed untouched"),
                () -> assertEquals(20, p.power, "power untouched"),
                () -> assertEquals(3, p.turnAcc, "turning untouched"),
                () -> assertEquals(DefaultRobot.RM_ROBOT_IDLE, d.robotMode, "mode untouched"));
        }

        @Test
        @DisplayName("a wall ahead: maximum turnspeed and power, turn away (left first), stop thrusting")
        void wallAhead() {
            Simulation s = wallWorld(14, 10);
            Player p = RobotFixture.robot(s, "R", 360, 360);
            RobotData d = s.robots.data(p);
            RobotFixture.velocity(p, 10, 0);
            p.dir = 0;
            p.status |= Obj.THRUSTING;
            p.turnSpeed = 10;
            boolean e = s.robots.robot().checkEvade(p, d, -1, null);
            long stop = (long) ((128 * 10.0) / (64 * 0.12) + (100 * p.mass) / 110);
            assertAll(
                () -> assertTrue(stop > 136 && stop < 300, "the stop distance reaches the wall (" + stop + ")"),
                () -> assertTrue(e, "evades"),
                () -> assertEquals(Const.MAX_PLAYER_TURNSPEED, p.turnSpeed, "turnspeed 64"),
                () -> assertEquals(Const.MAX_PLAYER_POWER, p.power, "power 55"),
                () -> assertEquals(DefaultRobot.RM_EVADE_LEFT, d.robotMode, "both sides clear after RES/16: heading decides"),
                () -> assertEquals(64.0, p.turnAcc, "turns left"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "stops thrusting while facing the wall"));
        }

        @Test
        @DisplayName("only the left side blocked (defense 200 gives a safe width of one ship): evade right")
        void leftBlocked() {
            Simulation s = wallWorld(14, 11);
            Player p = RobotFixture.robot(s, "R", 360, 375);
            RobotData d = s.robots.data(p);
            d.defense = 200;
            RobotFixture.velocity(p, 10, 0);
            p.dir = 0;
            boolean e = s.robots.robot().checkEvade(p, d, -1, null);
            assertAll(
                () -> assertTrue(e, "evades"),
                () -> assertEquals(DefaultRobot.RM_EVADE_RIGHT, d.robotMode, "the right is free"),
                () -> assertEquals(-64.0, p.turnAcc, "turns right"));
        }

        @Test
        @DisplayName("only the right side blocked: evade left; an evading robot keeps its side")
        void rightBlocked() {
            Simulation s = wallWorld(14, 9);
            Player p = RobotFixture.robot(s, "R", 360, 335);
            RobotData d = s.robots.data(p);
            d.defense = 200;
            RobotFixture.velocity(p, 10, 0);
            p.dir = 0;
            assertTrue(s.robots.robot().checkEvade(p, d, -1, null), "evades");
            assertEquals(DefaultRobot.RM_EVADE_LEFT, d.robotMode, "the left is free");
            d.robotMode = DefaultRobot.RM_EVADE_RIGHT;
            s.robots.robot().checkEvade(p, d, -1, null);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_EVADE_RIGHT, d.robotMode, "keeps evading right"),
                () -> assertEquals(-64.0, p.turnAcc, "turning right"));
        }

        @Test
        @DisplayName("a missile/mine ahead-left blocks the left side, ahead-right the right side")
        void mine() {
            Simulation s = wallWorld(-1, 0);
            World w = s.world;
            Player p = RobotFixture.robot(s, "R", 700, 700);
            RobotData d = s.robots.data(p);
            int left = RobotFixture.obj(w, Obj.MINE, Obj.NO_ID, 750, 720, 0, 0);
            int right = RobotFixture.obj(w, Obj.MINE, Obj.NO_ID, 750, 680, 0, 0);
            int behind = RobotFixture.obj(w, Obj.MINE, Obj.NO_ID, 650, 700, 0, 0);
            p.dir = 0;
            boolean l = s.robots.robot().checkEvade(p, d, left, null);
            int modeLeft = d.robotMode;
            d.robotMode = DefaultRobot.RM_ROBOT_IDLE;
            boolean r = s.robots.robot().checkEvade(p, d, right, null);
            int modeRight = d.robotMode;
            d.robotMode = DefaultRobot.RM_ROBOT_IDLE;
            boolean b = s.robots.robot().checkEvade(p, d, behind, null);
            assertAll(
                () -> assertTrue(l, "evades a mine to the left"),
                () -> assertEquals(DefaultRobot.RM_EVADE_RIGHT, modeLeft, "goes right"),
                () -> assertTrue(r, "evades a mine to the right"),
                () -> assertEquals(DefaultRobot.RM_EVADE_LEFT, modeRight, "goes left"),
                () -> assertFalse(b, "a mine straight behind (half a turn) is no reason"));
        }

        @Test
        @DisplayName("a ship two frames ahead counts like a mine")
        void ship() {
            Simulation s = wallWorld(-1, 0);
            Player p = RobotFixture.robot(s, "R", 700, 700);
            Player o = RobotFixture.human(s, "H", 740, 700);
            RobotData d = s.robots.data(p);
            RobotFixture.velocity(o, 0, 10);
            p.dir = 0;
            boolean e = s.robots.robot().checkEvade(p, d, -1, o);
            int mode = d.robotMode;
            RobotFixture.velocity(o, 0, -10);
            d.robotMode = DefaultRobot.RM_ROBOT_IDLE;
            s.robots.robot().checkEvade(p, d, -1, o);
            assertAll(
                () -> assertTrue(e, "evades"),
                () -> assertEquals(DefaultRobot.RM_EVADE_RIGHT, mode, "ship heading up-left: go right"),
                () -> assertEquals(DefaultRobot.RM_EVADE_LEFT, d.robotMode, "ship heading down-right: go left"));
        }

        @Test
        @DisplayName("too fast evades; the heading decides the side and how to steer")
        void overspeed() {
            Simulation s = wallWorld(-1, 0);
            Player p = RobotFixture.robot(s, "R", 700, 700);
            RobotData d = s.robots.data(p);
            RobotFixture.velocity(p, 0, d.maxSpeed + 0.5);
            p.dir = 32 + 40;
            p.status &= ~Obj.THRUSTING;
            assertTrue(s.robots.robot().checkEvade(p, d, -1, null), "faster than the max speed");
            int mode1 = d.robotMode;
            double acc1 = p.turnAcc;
            int thrust1 = p.status & Obj.THRUSTING;
            d.robotMode = DefaultRobot.RM_ROBOT_IDLE;
            p.dir = 32 + 64;
            s.robots.robot().checkEvade(p, d, -1, null);
            int mode2 = d.robotMode;
            double acc2 = p.turnAcc;
            int thrust2 = p.status & Obj.THRUSTING;
            d.robotMode = DefaultRobot.RM_EVADE_LEFT;
            p.dir = 32 + 63;
            s.robots.robot().checkEvade(p, d, -1, null);
            int mode3 = d.robotMode;
            RobotFixture.velocity(p, 0, d.maxSpeed);
            p.dir = 32;
            d.robotMode = DefaultRobot.RM_ROBOT_IDLE;
            boolean atMax = s.robots.robot().checkEvade(p, d, -1, null);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_EVADE_LEFT, mode1, "heading 40 left of travel: evade left"),
                () -> assertEquals(64.0, acc1, "turning left (3/8 > 40 > 1/4 of a turn: turn, keep thrust)"),
                () -> assertEquals(0, thrust1, "thrust not started"),
                () -> assertEquals(DefaultRobot.RM_EVADE_RIGHT, mode2, "facing backwards (64): re-picked right"),
                () -> assertEquals(0.0, acc2, "no turning when facing backwards"),
                () -> assertEquals(Obj.THRUSTING, thrust2, "thrusts to brake"),
                () -> assertEquals(DefaultRobot.RM_EVADE_LEFT, mode3, "63 off: re-picked left"),
                () -> assertFalse(atMax, "exactly the max speed is no reason"));
        }

        @Test
        @DisplayName("with both sides blocked, looks further out until one side is free")
        void search() {
            String[] rows = RobotFixture.open(40, 40, "_");
            for (int y = 8; y <= 12; y++) {
                char[] r = rows[39 - y].toCharArray();
                r[14] = 'x';
                rows[39 - y] = new String(r);
            }
            char[] top = rows[39 - 13].toCharArray();
            for (int x = 10; x <= 14; x++) {
                top[x] = 'x';
            }
            rows[39 - 13] = new String(top);
            Simulation s = RobotFixture.sim("", rows);
            Player p = RobotFixture.robot(s, "R", 360, 360);
            RobotData d = s.robots.data(p);
            RobotFixture.velocity(p, 10, 0);
            p.dir = 0;
            assertTrue(s.robots.robot().checkEvade(p, d, -1, null), "evades");
            assertAll(
                () -> assertEquals(DefaultRobot.RM_EVADE_RIGHT, d.robotMode, "the wall over the top: right is free first"),
                () -> assertEquals(-64.0, p.turnAcc, "turns right"));
        }
    }
}
