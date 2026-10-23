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

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.server.robot.RobotFixture.SpyItems;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DefaultRobot (robotdef.c): Check_robot_hunt, Detect_hunt, Rank_item_value, Ball_handler, play_check_map. */
class DefaultRobotSensesTest {

    @Nested
    @DisplayName("checkHunt (Check_robot_hunt)")
    class Hunt {
        private Simulation s;
        private Player p;
        private Player h;
        private RobotData d;

        private DefaultRobot setUp(double hx, double hy) {
            s = RobotFixture.sim("", RobotFixture.open(40, 40, "__"));
            p = RobotFixture.robot(s, "R", 700, 700);
            h = RobotFixture.human(s, "H", hx, hy);
            d = s.robots.data(p);
            p.dir = 0;
            DefaultRobot.setWar(d, h.id);
            return s.robots.robot();
        }

        @Test
        @DisplayName("needs a war on someone else, fuel at level 3 or more, and a detectable target")
        void preconditions() {
            DefaultRobot r = setUp(900, 700);
            DefaultRobot.setWar(d, Obj.NO_ID);
            boolean noWar = r.checkHunt(p, d);
            DefaultRobot.setWar(d, p.id);
            boolean self = r.checkHunt(p, d);
            DefaultRobot.setWar(d, h.id);
            p.fuel = d.fuelL3 - 1;
            boolean lowFuel = r.checkHunt(p, d);
            p.fuel = d.fuelL3;
            h.status &= ~Obj.PLAYING;
            boolean dead = r.checkHunt(p, d);
            h.status |= Obj.PLAYING;
            DefaultRobot.setWar(d, 200);
            boolean gone = r.checkHunt(p, d);
            assertAll(
                () -> assertFalse(noWar, "no war"),
                () -> assertFalse(self, "war on itself"),
                () -> assertFalse(lowFuel, "below level 3"),
                () -> assertFalse(dead, "target not playing"),
                () -> assertFalse(gone, "unknown id"),
                () -> assertEquals(DefaultRobot.RM_ROBOT_IDLE, d.robotMode, "nothing changed"));
        }

        @Test
        @DisplayName("at attack speed heading for the target: coast")
        void coast() {
            DefaultRobot r = setUp(900, 708);
            RobotFixture.velocity(p, 10, 0);
            p.status |= Obj.THRUSTING;
            p.turnAcc = 5;
            d.robotMode = DefaultRobot.RM_ATTACK;
            boolean ok = r.checkHunt(p, d);
            assertAll(
                () -> assertTrue(ok, "hunting"),
                () -> assertEquals(0.0, p.turnAcc, "no turn"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "no thrust"),
                () -> assertEquals(DefaultRobot.RM_ROBOT_IDLE, d.robotMode, "mode IDLE"));
        }

        @Test
        @DisplayName("too slow: aim an eighth of a turn off the travel direction; too fast: 3/8")
        void steer() {
            DefaultRobot r = setUp(900, 700);
            r.checkHunt(p, d);
            double slowAcc = p.turnAcc;
            int slowThrust = p.status & Obj.THRUSTING;
            p.dir = 10;
            p.turnAcc = 3;
            r.checkHunt(p, d);
            double alignedAcc = p.turnAcc;
            int alignedThrust = p.status & Obj.THRUSTING;
            p.dir = 0;
            RobotFixture.velocity(p, 20, 0);
            r.checkHunt(p, d);
            assertAll(
                () -> assertEquals(16.0, slowAcc, "wants 16: turn left at 64/4"),
                () -> assertEquals(16.0, p.turnSpeed, "turnspeed 16"),
                () -> assertEquals(0, slowThrust, "16 off is not within RES/8: no thrust"),
                () -> assertEquals(3.0, alignedAcc, "within RES/16: turning unchanged"),
                () -> assertEquals(Obj.THRUSTING, alignedThrust, "within RES/8: thrust"),
                () -> assertEquals(16.0, p.turnAcc, "too fast: wants 48, turn left"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "48 off: no thrust"));
        }

        @Test
        @DisplayName("a target to the right: aim right of the travel direction")
        void right() {
            DefaultRobot r = setUp(700, 500);
            r.checkHunt(p, d);
            assertAll(
                () -> assertEquals(-16.0, p.turnAcc, "wants 112: turn right"),
                () -> assertEquals(0, p.status & Obj.THRUSTING, "16 off: no thrust"));
        }
    }

    @Nested
    @DisplayName("detectHunt (Detect_hunt)")
    class Detect {
        @Test
        @DisplayName("visible playing ships yes; dead, killed, paused or phased ships no")
        void visible() {
            Simulation s = RobotFixture.sim("", RobotFixture.open(40, 40, "__"));
            Player p = RobotFixture.robot(s, "R", 700, 700);
            Player h = RobotFixture.human(s, "H", 900, 700);
            DefaultRobot r = s.robots.robot();
            boolean seen = r.detectHunt(p, h);
            h.status |= Obj.KILLED;
            boolean killed = r.detectHunt(p, h);
            h.status &= ~Obj.KILLED;
            h.status |= Obj.PAUSE;
            boolean paused = r.detectHunt(p, h);
            h.status &= ~Obj.PAUSE;
            h.used |= Has.PHASING_DEVICE;
            assertAll(
                () -> assertTrue(seen, "visible"),
                () -> assertFalse(killed, "killed"),
                () -> assertFalse(paused, "paused"),
                () -> assertFalse(r.detectHunt(p, h), "phased"));
        }

        @Test
        @DisplayName("an unseen ship within visibility shows by exhaust (cloakedExhaust), use of devices or a ball")
        void cloaked() {
            SpyItems it = new SpyItems();
            it.see = false;
            Simulation s = RobotFixture.sim("", RobotFixture.open(40, 40, "__"));
            s.robots.setItems(it);
            DefaultRobot r = s.robots.robot();
            Player p = RobotFixture.robot(s, "R", 100, 100);
            Player h = RobotFixture.human(s, "H", 600, 100);
            r.visibilityDistance = 499;
            boolean far = r.detectHunt(p, h);
            r.visibilityDistance = 500;
            boolean quiet = r.detectHunt(p, h);
            h.status |= Obj.THRUSTING;
            boolean exhaust = r.detectHunt(p, h);
            h.status &= ~Obj.THRUSTING;
            h.used |= Has.REFUEL;
            boolean refuel = r.detectHunt(p, h);
            h.used = 0;
            h.have |= Has.BALL;
            boolean ball = r.detectHunt(p, h);
            Simulation s2 = RobotFixture.sim("cloakedExhaust: no\n", RobotFixture.open(40, 40, "__"));
            s2.robots.setItems(it);
            Player p2 = RobotFixture.robot(s2, "R", 100, 100);
            Player h2 = RobotFixture.human(s2, "H", 600, 100);
            h2.status |= Obj.THRUSTING;
            h2.used = 0;
            h2.have = 0;
            assertAll(
                () -> assertFalse(far, "beyond visibility"),
                () -> assertFalse(quiet, "nothing gives it away"),
                () -> assertTrue(exhaust, "exhaust with cloakedExhaust"),
                () -> assertTrue(refuel, "refuelling"),
                () -> assertTrue(ball, "carrying a ball"),
                () -> assertFalse(s2.robots.robot().detectHunt(p2, h2), "no exhaust without cloakedExhaust"));
        }
    }

    @Nested
    @DisplayName("rankItemValue (Rank_item_value)")
    class Rank {
        private Simulation s;
        private Player p;
        private RobotData d;
        private SpyItems it;

        private DefaultRobot setUp(String extra) {
            s = RobotFixture.sim("maxDefensiveItems: 2\nmaxOffensiveItems: 2\n" + extra, RobotFixture.open(40, 10, "_A"));
            it = new SpyItems();
            s.robots.setItems(it);
            p = RobotFixture.robot(s, "R", 100, 100);
            d = s.robots.data(p);
            return s.robots.robot();
        }

        @Test
        @DisplayName("autopilot, full items and full item classes are ignored")
        void ignored() {
            DefaultRobot r = setUp("");
            it.limit = 3;
            it.with(Item.SENSOR, 3);
            int full = r.rankItemValue(p, d, Item.SENSOR);
            it.with(Item.SENSOR, 2);
            int notFull = r.rankItemValue(p, d, Item.SENSOR);
            it.with(Item.CLOAK, 2);
            int defensive = r.rankItemValue(p, d, Item.ECM);
            it.tanks = 0;
            it.with(Item.MINE, 1).with(Item.LASER, 1);
            int offensive = r.rankItemValue(p, d, Item.WIDEANGLE);
            it.with(Item.CLOAK, 1);
            int oneDef = r.rankItemValue(p, d, Item.ECM);
            it.defensives = 1;
            assertAll(
                () -> assertEquals(0, r.rankItemValue(p, d, Item.AUTOPILOT), "autopilot"),
                () -> assertEquals(0, full, "at the limit"),
                () -> assertEquals(1, notFull, "below the limit: handy"),
                () -> assertEquals(0, defensive, "2 defensive items"),
                () -> assertEquals(0, offensive, "2 offensive items"),
                () -> assertEquals(1, oneDef, "1 defensive item: handy"),
                () -> assertEquals(0, r.rankItemValue(p, d, Item.ECM), "an active shield/phasing counts"));
        }

        @Test
        @DisplayName("fuel: ignored at 90% full, a must below level 2 (level 1 in race mode), else handy")
        void fuel() {
            DefaultRobot r = setUp("");
            p.fuel = p.fuelMax * 0.9;
            int full = r.rankItemValue(p, d, Item.FUEL);
            p.fuel = d.fuelL2 - 1;
            int low = r.rankItemValue(p, d, Item.FUEL);
            p.fuel = d.fuelL2;
            int mid = r.rankItemValue(p, d, Item.FUEL);
            DefaultRobot race = setUp("timing: yes\n");
            p.fuel = d.fuelL1 - 1;
            int raceLow = race.rankItemValue(p, d, Item.FUEL);
            p.fuel = d.fuelL1;
            assertAll(
                () -> assertEquals(0, full, "90% full"),
                () -> assertEquals(2, low, "below level 2"),
                () -> assertEquals(1, mid, "at level 2"),
                () -> assertEquals(2, raceLow, "race: below level 1"),
                () -> assertEquals(1, race.rankItemValue(p, d, Item.FUEL), "race: at level 1"));
        }

        @Test
        @DisplayName("race mode wants speed and dirty tricks, ignores heavy or useless items")
        void race() {
            DefaultRobot r = setUp("timing: yes\n");
            assertTrue(s.world.map.timing(), "race mode");
            for (Item i : new Item[] {Item.AFTERBURNER, Item.EMERGENCY_THRUST, Item.TRANSPORTER, Item.REARSHOT,
                Item.ECM}) {
                assertEquals(2, r.rankItemValue(p, d, i), i + " is a must");
            }
            for (Item i : new Item[] {Item.CLOAK, Item.LASER, Item.MISSILE, Item.SENSOR, Item.TANK, Item.TRACTOR_BEAM,
                Item.HYPERJUMP, Item.PHASING, Item.DEFLECTOR, Item.ARMOR}) {
                assertEquals(0, r.rankItemValue(p, d, i), i + " is ignored");
            }
            it.with(Item.LASER, 0);
            assertAll(
                () -> assertEquals(2, r.rankItemValue(p, d, Item.MINE), "mines are a must"),
                () -> assertEquals(1, r.rankItemValue(p, d, Item.WIDEANGLE), "others are handy"));
        }

        @Test
        @DisplayName("normal play: defensive items handy with shields; shot items musts when shots kill")
        void normal() {
            DefaultRobot r = setUp("");
            assertAll(
                () -> assertEquals(1, r.rankItemValue(p, d, Item.DEFLECTOR), "deflector with shields: handy"),
                () -> assertEquals(1, r.rankItemValue(p, d, Item.ARMOR), "armor with shields: handy"),
                () -> assertEquals(2, r.rankItemValue(p, d, Item.REARSHOT), "rear shots when shots kill"),
                () -> assertEquals(2, r.rankItemValue(p, d, Item.WIDEANGLE), "wide angle when shots kill"),
                () -> assertEquals(2, r.rankItemValue(p, d, Item.MISSILE), "missiles when shots kill"),
                () -> assertEquals(2, r.rankItemValue(p, d, Item.MINE), "mines"),
                () -> assertEquals(2, r.rankItemValue(p, d, Item.CLOAK), "cloaks"),
                () -> assertEquals(2, r.rankItemValue(p, d, Item.LASER), "lasers with killing"),
                () -> assertEquals(0, r.rankItemValue(p, d, Item.PHASING), "phasing"),
                () -> assertEquals(1, r.rankItemValue(p, d, Item.SENSOR), "others handy"));
            DefaultRobot nk = setUp("allowPlayerKilling: no\n");
            assertAll(
                () -> assertEquals(1, nk.rankItemValue(p, d, Item.WIDEANGLE), "no killing: wide angle handy"),
                () -> assertEquals(0, nk.rankItemValue(p, d, Item.MISSILE), "no killing: missiles ignored"),
                () -> assertEquals(1, nk.rankItemValue(p, d, Item.LASER), "no killing: lasers handy"));
        }

        @Test
        @DisplayName("without shields the defensive items are musts; no shots (max 0 or life 0) make shot items handy")
        void noShields() {
            DefaultRobot r = setUp("");
            p.have &= ~Has.SHIELD;
            int shield = r.rankItemValue(p, d, Item.EMERGENCY_SHIELD);
            DefaultRobot noShots = setUp("maxPlayerShots: 0\n");
            int ns = noShots.rankItemValue(p, d, Item.REARSHOT);
            DefaultRobot noLife = setUp("shotLife: 0\n");
            assertAll(
                () -> assertEquals(2, shield, "emergency shield without shields: must"),
                () -> assertEquals(1, ns, "maxPlayerShots 0: rear shot handy"),
                () -> assertEquals(1, noLife.rankItemValue(p, d, Item.REARSHOT), "shotLife 0: handy"));
        }
    }

    @Nested
    @DisplayName("ballHandler (Ball_handler, gated: balls)")
    class Balls {
        private Simulation s;
        private World w;
        private Player p;
        private Player enemy;
        private RobotData d;
        private SpyItems it;
        private DefaultRobot r;

        /** Team 1 robot at (300, 175); its treasure at block (3, 5), team 2's at (26, 5). */
        private void setUp() {
            String[] rows = RobotFixture.open(30, 10, "");
            char[] mid = rows[9 - 5].toCharArray();
            mid[3] = '*';
            mid[26] = '*';
            rows[9 - 5] = new String(mid);
            char[] bottom = rows[9].toCharArray();
            bottom[1] = '1';
            bottom[28] = '2';
            rows[9] = new String(bottom);
            s = RobotFixture.sim("teamPlay: yes\nreserveRobotTeam: no\n", rows);
            w = s.world;
            it = new SpyItems();
            s.robots.setItems(it);
            r = s.robots.robot();
            p = s.ships.join("R", 1, true);
            RobotFixture.place(p, 300, 175);
            enemy = s.ships.join("E", 2);
            RobotFixture.place(enemy, 980, 175);
            d = s.robots.data(p);
            d.robotCount = 500;
            d.normalSpeed = 6;
            d.attackSpeed = 17;
            d.maxSpeed = 30;
        }

        @Test
        @DisplayName("without balls anywhere it does nothing")
        void nothing() {
            setUp();
            assertAll(
                () -> assertFalse(r.ballHandler(p, d), "false"),
                () -> assertEquals(0, d.longtermMode, "no FETCH_TREASURE"));
        }

        @Test
        @DisplayName("an enemy treasure with its ball within count/10 blocks: navigate there")
        void fetch() {
            setUp();
            it.treasureBall = true;
            boolean ok = r.ballHandler(p, d);
            d.robotCount = 100;
            d.longtermMode = 0;
            boolean tooFar = r.ballHandler(p, d);
            assertAll(
                () -> assertTrue(ok, "heading for the treasure"),
                () -> assertEquals(DefaultRobot.RM_NAVIGATE, d.robotMode, "NAVIGATE"),
                () -> assertFalse(tooFar, "count 100: 10 blocks < 627 px"));
        }

        @Test
        @DisplayName("a loose enemy ball beyond the connector length: navigate to it")
        void looseBall() {
            setUp();
            int b = RobotFixture.obj(w, Obj.BALL, enemy.id, 700, 175, 0, 0);
            boolean ok = r.ballHandler(p, d);
            w.objects.owner[b] = p.id;
            d.longtermMode = 0;
            boolean own = r.ballHandler(p, d);
            w.objects.owner[b] = Obj.NO_ID;
            it.ballOwner = 3;
            boolean unowned = r.ballHandler(p, d);
            RobotFixture.place(p, 650, 175);
            assertAll(
                () -> assertTrue(ok, "enemy ball 400 px away"),
                () -> assertEquals(DefaultRobot.FETCH_TREASURE, d.longtermMode & DefaultRobot.FETCH_TREASURE, "fetching"),
                () -> assertFalse(own, "a ball held by the own team is ignored"),
                () -> assertTrue(unowned, "a free ball that has an owner"),
                () -> assertFalse(r.ballHandler(p, d), "within the connector length (50 < 120)"));
        }

        @Test
        @DisplayName("carrying: throw at the own treasure when lined up and fast, else fly it home")
        void carrying() {
            setUp();
            int b = RobotFixture.obj(w, Obj.BALL, p.id, 600, 193, -10, 0);
            it.towed = b;
            d.longtermMode = DefaultRobot.FETCH_TREASURE;
            boolean thrown = r.ballHandler(p, d);
            List<String> calls = List.copyOf(it.calls);
            w.objects.vx[b] = 0;
            w.objects.vy[b] = -10;
            boolean home = r.ballHandler(p, d);
            assertAll(
                () -> assertFalse(thrown, "thrown: returns false"),
                () -> assertEquals(List.of("detachBall"), calls, "detached"),
                () -> assertEquals(500, d.lastThrownBall, "remembered"),
                () -> assertTrue(home, "not lined up: fly home"),
                () -> assertEquals(DefaultRobot.FETCH_TREASURE, d.longtermMode & DefaultRobot.FETCH_TREASURE, "fetching"));
        }

        @Test
        @DisplayName("carrying (HAS_BALL) without a ball object or own treasure: nothing")
        void carryingNoBall() {
            setUp();
            p.have |= Has.BALL;
            assertFalse(r.ballHandler(p, d), "no ball object");
            RobotFixture.obj(w, Obj.BALL, p.id, 600, 193, -10, 0);
            p.team = 3;
            assertFalse(r.ballHandler(p, d), "no treasure of team 3");
        }
    }

    @Nested
    @DisplayName("checkMap (Robot_default_play_check_map)")
    class MapFeatures {
        private Simulation s;
        private World w;
        private Player p;
        private RobotData d;

        private DefaultRobot setUp(String extra, String feature) {
            String[] rows = RobotFixture.open(40, 40, "__");
            char[] row = rows[39 - 20].toCharArray();
            for (int i = 0; i < feature.length(); i++) {
                if (feature.charAt(i) != '.') {
                    row[22 + i] = feature.charAt(i);
                }
            }
            rows[39 - 20] = new String(row);
            s = RobotFixture.sim(extra, rows);
            w = s.world;
            p = RobotFixture.robot(s, "R", 700, 700);
            d = s.robots.data(p);
            d.robotCount = 501;
            return s.robots.robot();
        }

        @Test
        @DisplayName("needing fuel: heads for the nearest station with at least 100 units and starts refuelling")
        void fuel() {
            DefaultRobot r = setUp("", "..#");
            w.fuelLevel[0] = 100 * 256;
            d.longtermMode = DefaultRobot.NEED_FUEL;
            int got = r.checkMap(p, d);
            d.longtermMode = 0;
            p.used &= ~Has.REFUEL;
            int noNeed = r.checkMap(p, d);
            d.longtermMode = DefaultRobot.NEED_FUEL;
            w.fuelLevel[0] = 100 * 256 - 1;
            assertAll(
                () -> assertEquals(1, got, "took it on"),
                () -> assertEquals(DefaultRobot.RM_REFUEL, d.robotMode, "REFUEL"),
                () -> assertEquals(0, p.fs, "station 0"),
                () -> assertEquals(0, noNeed, "not needed: nothing"),
                () -> assertEquals(0, p.used & Has.REFUEL, "not needed: no refuel"),
                () -> assertEquals(0, r.checkMap(p, d), "an almost empty station is ignored"));
        }

        @Test
        @DisplayName("a live cannon is attacked (aimed 0.1 block in front of it) when the scan stays out of its block")
        void cannon() {
            DefaultRobot r = setUp("", "...r");
            RobotFixture.place(p, 892, 714 - 116);
            boolean blockedFromAbove = r.checkMap(p, d) == 0;
            RobotFixture.place(p, 892, 714 - 117);
            int got = r.checkMap(p, d);
            int mode = d.robotMode;
            w.cannonDeadTime[0] = 5;
            assertAll(
                () -> assertEquals(892 + 0 * 714, (int) ((25 + 0.5) * 35), "cannon 'r' (facing up) aim x"),
                () -> assertTrue(blockedFromAbove, "116 px below: the last step (y 700) is inside the cannon block"),
                () -> assertEquals(1, got, "117 px below: the last step (y 699) stays out: attacking"),
                () -> assertEquals(DefaultRobot.RM_CANNON_KILL, mode, "CANNON_KILL"),
                () -> assertEquals(0, r.checkMap(p, d), "a dead cannon is ignored"));
        }

        @Test
        @DisplayName("only within visibility")
        void visibility() {
            DefaultRobot r = setUp("", "...r");
            r.visibilityDistance = 100;
            assertEquals(0, r.checkMap(p, d), "the cannon 3 blocks away is not seen");
        }

        /** Team play: bases 1 at (1,0), 2 at (38,0); own (team 1) station at (19,20), team 2 target (25,20), team 2 station (36,2). */
        private Simulation teamWorld() {
            String[] rows = RobotFixture.open(40, 40, "");
            char[] bottom = rows[39].toCharArray();
            bottom[1] = '1';
            bottom[38] = '2';
            rows[39] = new String(bottom);
            char[] row = rows[39 - 20].toCharArray();
            row[19] = '#';
            row[25] = '!';
            rows[39 - 20] = new String(row);
            char[] row2 = rows[39 - 2].toCharArray();
            row2[36] = '#';
            rows[39 - 2] = new String(row2);
            Simulation t = RobotFixture.sim("teamPlay: yes\nreserveRobotTeam: no\nteamFuel: yes\n", rows);
            t.world.fuelLevel[0] = 400 * 256;
            t.world.fuelLevel[1] = 400 * 256;
            return t;
        }

        @Test
        @DisplayName("team play: refuel at an own station nearer than the enemy target")
        void teamFuel() {
            s = teamWorld();
            p = s.ships.join("R", 1, true);
            RobotFixture.place(p, 700, 700);
            s.ships.join("E", 2);
            d = s.robots.data(p);
            d.longtermMode = DefaultRobot.NEED_FUEL;
            int got = s.robots.robot().checkMap(p, d);
            assertAll(
                () -> assertEquals(1, s.world.map.fuels().get(0).team(), "station 0 is team 1's"),
                () -> assertEquals(2, s.world.map.targets().get(0).team(), "the target is team 2's"),
                () -> assertEquals(1, got, "refuelling"),
                () -> assertEquals(0, p.fs, "at station 0"),
                () -> assertEquals(DefaultRobot.RM_REFUEL, d.robotMode, "REFUEL"));
        }

        @Test
        @DisplayName("team play: an enemy target is tried (TARGET_KILL), cleared again when its block is in the way")
        void teamTarget() {
            s = teamWorld();
            p = s.ships.join("R", 1, true);
            RobotFixture.place(p, 700, 700);
            s.ships.join("E", 2);
            d = s.robots.data(p);
            int got = s.robots.robot().checkMap(p, d);
            assertAll(
                () -> assertEquals(0, got, "the scan ends inside the target block: blocked, nothing else to do"),
                () -> assertEquals(0, d.longtermMode & DefaultRobot.TARGET_KILL, "TARGET_KILL cleared"));
        }

        @Test
        @DisplayName("team play with teamFuel: another team's station is skipped; an own target is not attacked")
        void teamOther() {
            s = teamWorld();
            s.ships.join("E", 1);
            p = s.ships.join("R", 2, true);
            RobotFixture.place(p, 700, 700);
            d = s.robots.data(p);
            d.longtermMode = DefaultRobot.NEED_FUEL;
            int got = s.robots.robot().checkMap(p, d);
            assertAll(
                () -> assertEquals(1, got, "refuelling"),
                () -> assertEquals(1, p.fs, "at its own team's station 1, not the nearer station 0"),
                () -> assertEquals(0, d.longtermMode & DefaultRobot.TARGET_KILL, "no target attack"));
        }
    }
}
