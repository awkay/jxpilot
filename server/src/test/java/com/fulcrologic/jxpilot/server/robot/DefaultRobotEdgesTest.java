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
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.server.robot.DefaultRobotPlayTest.W;
import com.fulcrologic.jxpilot.server.robot.RobotFixture.SpyItems;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DefaultRobot boundaries and exact arithmetic of robotdef.c, case by case. */
class DefaultRobotEdgesTest {

    /** A 40x40 world (bases on row 0) with map characters placed at block (x, y). */
    static String[] rows(Object... cells) {
        String[] rows = RobotFixture.open(40, 40, "___");
        for (int i = 0; i < cells.length; i += 3) {
            int x = (Integer) cells[i];
            int y = (Integer) cells[i + 1];
            char c = (Character) cells[i + 2];
            char[] r = rows[39 - y].toCharArray();
            r[x] = c;
            rows[39 - y] = new String(r);
        }
        return rows;
    }

    /** A W world with extra map cells. */
    static W world(String extra, Object... cells) {
        return new W(extra, rows(cells));
    }

    /** A seed whose first rfrac r has (int)(r * 100) == v. */
    static int seedPercent(int v) {
        for (int s = 1; ; s++) {
            if ((int) (RobotFixture.rfracs(s, 1)[0] * 100) == v) {
                return s;
            }
        }
    }

    // ------------------------------------------------------------------ evade

    @Nested
    @DisplayName("checkEvade edges")
    class Evade {
        @Test
        @DisplayName("the stop distance is RES*v/(64*0.12) + v^2*mass/110: at v 5 a wall at 95..102 px is seen, at 130 not")
        void stopDistance() {
            W near = world("", 23, 20, 'x');
            RobotFixture.place(near.p, 710, 700);
            RobotFixture.velocity(near.p, 5, 0);
            long stop = (long) ((128 * 5.0) / (64 * 0.12) + (25 * near.p.mass) / 110);
            boolean seen = near.r.checkEvade(near.p, near.d, -1, null);
            W far = world("", 24, 20, 'x');
            RobotFixture.place(far.p, 710, 700);
            RobotFixture.velocity(far.p, 5, 0);
            assertAll(
                () -> assertEquals(89, stop, "stop distance 89: steps 0..102"),
                () -> assertTrue(seen, "block 23 (805) is reached at step 102 (812)"),
                () -> assertFalse(far.r.checkEvade(far.p, far.d, -1, null), "block 24 (840) is not"));
        }

        @Test
        @DisplayName("the side rays are one safe width left and right of the travel direction (here: up)")
        void sideRays() {
            W t = world("", 19, 21, 'x', 19, 22, 'x', 19, 23, 'x');
            RobotFixture.place(t.p, 712, 700);
            RobotFixture.velocity(t.p, 0, 10);
            t.d.defense = 200;
            t.p.dir = 32;
            boolean e = t.r.checkEvade(t.p, t.d, -1, null);
            W u = world("");
            RobotFixture.place(u.p, 712, 700);
            RobotFixture.velocity(u.p, 0, u.d.maxSpeed + 1);
            u.d.defense = 200;
            u.p.dir = 42;
            u.r.checkEvade(u.p, u.d, -1, null);
            assertAll(
                () -> assertTrue(e, "the wall left of the left ray (x 696) is seen"),
                () -> assertEquals(DefaultRobot.RM_EVADE_RIGHT, t.d.robotMode, "left blocked: evade right"),
                () -> assertEquals(DefaultRobot.RM_EVADE_LEFT, u.d.robotMode, "nothing blocked, heading left of travel"));
        }

        @Test
        @DisplayName("a danger exactly a quarter turn left or right of the travel direction blocks nothing")
        void quarterTurns() {
            W t = new W("");
            int up = RobotFixture.obj(t.w, Obj.MINE, Obj.NO_ID, 700, 750, 0, 0);
            boolean atUp = t.r.checkEvade(t.p, t.d, up, null);
            int down = RobotFixture.obj(t.w, Obj.MINE, Obj.NO_ID, 700, 650, 0, 0);
            boolean atDown = t.r.checkEvade(t.p, t.d, down, null);
            int justLeft = RobotFixture.obj(t.w, Obj.MINE, Obj.NO_ID, 701, 750, 0, 0);
            boolean left = t.r.checkEvade(t.p, t.d, justLeft, null);
            assertAll(
                () -> assertFalse(atUp, "straight up (32)"),
                () -> assertFalse(atDown, "straight down (96)"),
                () -> assertTrue(left, "31: left blocked"));
        }

        @Test
        @DisplayName("the mine's next position counts, relative to a moving robot's travel direction")
        void mineMotion() {
            W t = new W("");
            int m = RobotFixture.obj(t.w, Obj.MINE, Obj.NO_ID, 750, 699, 0, 20);
            t.r.checkEvade(t.p, t.d, m, null);
            int byVy = t.d.robotMode;
            W u = new W("");
            int m2 = RobotFixture.obj(u.w, Obj.MINE, Obj.NO_ID, 699, 750, 20, 0);
            boolean byVx = u.r.checkEvade(u.p, u.d, m2, null);
            W v = new W("");
            RobotFixture.velocity(v.p, 0, 0.5);
            int m3 = RobotFixture.obj(v.w, Obj.MINE, Obj.NO_ID, 681, 746, 0, 0);
            boolean moving = v.r.checkEvade(v.p, v.d, m3, null);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_EVADE_RIGHT, byVy, "moving up to (50, 19): left of travel"),
                () -> assertTrue(byVx, "moving right to (19, 50): left of travel"),
                () -> assertTrue(moving, "travel 32, mine at about 39: 7 to the left"));
        }

        @Test
        @DisplayName("a ship counts at its position plus twice its velocity, relative to the travel direction")
        void shipMotion() {
            W t = new W("");
            RobotFixture.place(t.h, 740, 705);
            RobotFixture.velocity(t.h, 0, -10);
            t.r.checkEvade(t.p, t.d, -1, t.h);
            int down = t.d.robotMode;
            W u = new W("");
            RobotFixture.place(u.h, 740, 705);
            RobotFixture.velocity(u.h, -30, -10);
            boolean behind = u.r.checkEvade(u.p, u.d, -1, u.h);
            W v = new W("");
            RobotFixture.velocity(v.p, 0, 0.5);
            RobotFixture.place(v.h, 681, 746);
            boolean moving = v.r.checkEvade(v.p, v.d, -1, v.h);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_EVADE_LEFT, down, "(40, -15) is right of travel: evade left"),
                () -> assertFalse(behind, "(-20, -15) is behind"),
                () -> assertTrue(moving, "travel 32, ship at about 39"));
        }

        @Test
        @DisplayName("steering by the heading's offset from travel: exactly 1/4, 3/4, 3/8, 5/8 and 1/2 turn")
        void branches() {
            int[] dirs = {32, 96, 48, 80, 64};
            List<String> out = new ArrayList<>();
            for (int dir : dirs) {
                W t = new W("");
                RobotFixture.velocity(t.p, t.d.maxSpeed + 1, 0);
                t.p.dir = dir;
                t.p.status |= Obj.THRUSTING;
                t.r.checkEvade(t.p, t.d, -1, null);
                out.add(dir + ":" + (int) t.p.turnAcc + ":" + ((t.p.status & Obj.THRUSTING) != 0) + ":"
                    + (t.d.robotMode == DefaultRobot.RM_EVADE_LEFT ? "L" : "R"));
            }
            assertEquals(List.of("32:64:true:L", "96:-64:true:R", "48:0:true:L", "80:0:true:R", "64:0:true:R"), out,
                "32/96 turn and keep thrust; 48/64/80 face backwards: no turn, thrust, side by the heading");
        }
    }

    // ------------------------------------------------------------------ target and thrust

    @Nested
    @DisplayName("checkTarget edges")
    class Target {
        @Test
        @DisplayName("the path is scanned while dist < item distance: an item 102 px away is scanned to 85")
        void scanEnd() {
            W t = world("", 23, 20, 'x');
            RobotFixture.place(t.p, 710, 700);
            assertTrue(t.r.checkTarget(t.p, t.d, 812, 700, DefaultRobot.RM_ATTACK), "the step at 102 is not scanned");
        }

        @Test
        @DisplayName("an item exactly a quarter turn off the travel direction makes the robot slow down")
        void quarter() {
            W t = new W("");
            RobotFixture.velocity(t.p, 20, 0);
            t.r.checkTarget(t.p, t.d, 700, 900, DefaultRobot.RM_ATTACK);
            int up = t.p.status & Obj.THRUSTING;
            W u = new W("");
            RobotFixture.velocity(u.p, 20, 0);
            u.r.checkTarget(u.p, u.d, 700, 500, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(Obj.THRUSTING, up, "item at 32: slowing thrust (20 would be too fast otherwise)"),
                () -> assertEquals(Obj.THRUSTING, u.p.status & Obj.THRUSTING, "item at 96 too"));
        }

        @Test
        @DisplayName("NAVIGATE with a clear path reverses the travel direction; HARVEST of an item exactly behind turns +5/8")
        void reverse() {
            W t = new W("");
            RobotFixture.velocity(t.p, 10, 0);
            t.r.checkTarget(t.p, t.d, 600, 800, DefaultRobot.RM_NAVIGATE);
            W u = new W("");
            u.r.checkTarget(u.p, u.d, 500, 700, DefaultRobot.RM_HARVEST);
            assertAll(
                () -> assertEquals(-64.0, t.p.turnAcc, "wants 80, not 48"),
                () -> assertEquals(-64.0, u.p.turnAcc, "64 is not past half a turn: 0 + 80"));
        }

        @Test
        @DisplayName("mines only within 8 blocks; a cannon exactly 4 blocks away counts as too close")
        void boundaries() {
            SpyItems it = new SpyItems().with(Item.MINE, 3);
            W t = new W("");
            t.s.robots.setItems(it);
            DefaultRobot r = t.s.robots.robot();
            t.d.robotCount = 501;
            r.checkTarget(t.p, t.d, 700, 980, DefaultRobot.RM_ATTACK);
            W c = new W("");
            c.r.checkTarget(c.p, c.d, 840, 700, DefaultRobot.RM_CANNON_KILL);
            assertAll(
                () -> assertEquals(List.of(), it.calls, "280 px: no mine"),
                () -> assertEquals(-64.0, c.p.turnAcc, "140 - 140 = 0: turn away"));
        }

        @Test
        @DisplayName("NAVIGATE uses the weapons in race mode only")
        void raceWeapons() {
            String[] rows = rows(20, 30, 'A');
            Simulation s = RobotFixture.sim("timing: yes\n", rows);
            SpyItems it = new SpyItems().with(Item.ECM, 1);
            s.robots.setItems(it);
            Player p = RobotFixture.robot(s, "R", 700, 700);
            RobotData d = s.robots.data(p);
            s.robots.robot().checkTarget(p, d, 790, 700, DefaultRobot.RM_NAVIGATE);
            SpyItems it2 = new SpyItems().with(Item.ECM, 1);
            W t = new W("");
            t.s.robots.setItems(it2);
            t.s.robots.robot().checkTarget(t.p, t.d, 790, 700, DefaultRobot.RM_NAVIGATE);
            assertAll(
                () -> assertEquals(List.of("ecm"), it.calls, "race: ECM"),
                () -> assertEquals(List.of(), it2.calls, "otherwise none"));
        }

        @Test
        @DisplayName("weapon modifiers are chosen when dropping a mine and when shooting a cannon")
        void modifiers() {
            SpyItems it = new SpyItems().with(Item.MINE, 5);
            W t = new W("allowNukes: yes\nallowModifiers: yes\n");
            t.s.robots.setItems(it);
            DefaultRobot r = t.s.robots.robot();
            t.d.robotCount = 41;
            r.checkTarget(t.p, t.d, 700, 900, DefaultRobot.RM_ATTACK);
            W c = new W("allowModifiers: yes\n");
            c.d.robotCount = 500;
            c.w.rng.seed(6);
            c.r.checkTarget(c.p, c.d, 1000, 700, DefaultRobot.RM_CANNON_KILL);
            assertAll(
                () -> assertEquals(DefaultRobot.NUCLEAR | DefaultRobot.FULLNUCLEAR, Mods.nuclear(t.p.mods), "mine modifiers"),
                () -> assertEquals((int) (RobotFixture.rfracs(6, 1)[0] * 4), Mods.spread(c.p.mods), "shot spread drawn"),
                () -> assertEquals(1, c.p.shots, "and fired"));
        }
    }

    @Nested
    @DisplayName("thrust edges")
    class Thrust {
        private int thrust(int itemX, int mode, double v, int dir) {
            W t = new W("");
            RobotFixture.velocity(t.p, v, 0);
            t.p.dir = dir;
            t.p.status &= ~Obj.THRUSTING;
            t.r.checkTarget(t.p, t.d, itemX, 700, mode);
            return t.p.status & Obj.THRUSTING;
        }

        @Test
        @DisplayName("each band boundary: 0 px left to go, 105, 280, 630; headings 48/80 and 16/112 off")
        void edges() {
            int on = Obj.THRUSTING;
            assertAll(
                () -> assertEquals(on, thrust(790, DefaultRobot.RM_REFUEL, 0, 0), "REFUEL 90 px: 0 left is not < 0: thrust"),
                () -> assertEquals(on, thrust(805, DefaultRobot.RM_ATTACK, 4, 0), "105 px: the 12..18 band"),
                () -> assertEquals(0, thrust(980, DefaultRobot.RM_ATTACK, 10, 0), "280 px: attack band, 10 keeps off"),
                () -> assertEquals(0, thrust(1000, DefaultRobot.RM_NAVIGATE, 10, 80), "48 off: not the normal band"),
                () -> assertEquals(0, thrust(1000, DefaultRobot.RM_NAVIGATE, 10, 48), "80 off: not the normal band"),
                () -> assertEquals(on, thrust(1200, DefaultRobot.RM_NAVIGATE, 12, 112), "16 off: the 17..24 band"),
                () -> assertEquals(on, thrust(1200, DefaultRobot.RM_NAVIGATE, 12, 16), "112 off: the 17..24 band"),
                () -> assertEquals(0, thrust(1330, DefaultRobot.RM_NAVIGATE, 20, 0), "630 px is not beyond 18 blocks"),
                () -> assertEquals(0, thrust(1000, DefaultRobot.RM_ATTACK, 10, 0), "attack band low is 8.5"),
                () -> assertEquals(0, thrust(1390, DefaultRobot.RM_NAVIGATE, 25, 0), "max band low is 24"));
        }

        @Test
        @DisplayName("thrustBand at exactly low and high changes nothing")
        void bandEdges() {
            Player p = new Player(0);
            DefaultRobot.thrustBand(p, 2, 2, 4);
            int low = p.status;
            p.status = Obj.THRUSTING;
            DefaultRobot.thrustBand(p, 4, 2, 4);
            assertAll(
                () -> assertEquals(0, low, "v == low: no thrust"),
                () -> assertEquals(Obj.THRUSTING, p.status, "v == high: kept"));
        }
    }

    // ------------------------------------------------------------------ map

    @Nested
    @DisplayName("checkMap edges")
    class MapEdges {
        @Test
        @DisplayName("the cannon aim point is 0.1 block in front of an up-facing cannon (pixel y 711 + 3.5)")
        void upCannon() {
            W t = world("", 25, 20, 'r');
            RobotFixture.place(t.p, 892, 591);
            assertEquals(0, t.r.checkMap(t.p, t.d), "aim y 714: the scan from y 591 ends at 710, inside the block");
        }

        @Test
        @DisplayName("and in front of a right-facing cannon (pixel x 886 + 3.5)")
        void rightCannon() {
            W t = world("", 25, 20, 'f');
            RobotFixture.place(t.p, 780, 717);
            assertEquals(0, t.r.checkMap(t.p, t.d), "aim x 889: the scan from x 780 ends at 882, inside the block");
        }

        @Test
        @DisplayName("team play: own cannons are left alone (teamCannons)")
        void ownCannon() {
            String[] rows = rows(25, 20, 'r');
            char[] b = rows[39].toCharArray();
            b[1] = '1';
            b[3] = '1';
            b[5] = '1';
            rows[39] = new String(b);
            Simulation s = RobotFixture.sim("teamPlay: yes\nreserveRobotTeam: no\nteamCannons: yes\n", rows);
            Player p = s.ships.join("R", 1, true);
            RobotFixture.place(p, 892, 597);
            RobotData d = s.robots.data(p);
            assertEquals(0, s.robots.robot().checkMap(p, d), "the only cannon is team 1's");
        }

        @Test
        @DisplayName("team play, needing fuel, a nearer enemy target: the target is tried first, then the station")
        void fuelAfterTarget() {
            String[] rows = RobotFixture.open(40, 40, "");
            char[] b = rows[39].toCharArray();
            b[1] = '1';
            b[38] = '2';
            rows[39] = new String(b);
            char[] r = rows[39 - 20].toCharArray();
            r[22] = '!';
            r[12] = '#';
            rows[39 - 20] = new String(r);
            Simulation s = RobotFixture.sim("teamPlay: yes\nreserveRobotTeam: no\n", rows);
            s.world.fuelLevel[0] = 400 * 256;
            Player p = s.ships.join("R", 1, true);
            RobotFixture.place(p, 700, 700);
            s.ships.join("E", 2);
            RobotData d = s.robots.data(p);
            d.longtermMode = DefaultRobot.NEED_FUEL;
            int got = s.robots.robot().checkMap(p, d);
            assertAll(
                () -> assertEquals(2, s.world.map.targets().get(0).team(), "enemy target 70 px away"),
                () -> assertEquals(1, got, "the station 280 px away is taken on after the target fails"),
                () -> assertEquals(Has.REFUEL, p.used & Has.REFUEL, "refuelling"),
                () -> assertEquals(DefaultRobot.RM_REFUEL, d.robotMode, "REFUEL"));
        }
    }

    // ------------------------------------------------------------------ objects

    @Nested
    @DisplayName("checkObjects edges")
    class Objects {
        @Test
        @DisplayName("only cells within visibility/BLOCK_SZ blocks are looked at")
        void range() {
            W t = new W("");
            RobotFixture.obj(t.w, Obj.SHOT, t.h.id, 805, 700, 0, 0);
            t.r.visibilityDistance = 70;
            t.objects();
            assertEquals(-1, t.r.mineI, "3 blocks away with a 2-block range");
        }

        @Test
        @DisplayName("a ball within 3*FPS counts of the last throw does not set the connector")
        void recentThrow() {
            W t = new W("");
            t.d.lastThrownBall = 10;
            t.d.robotCount = 100;
            RobotFixture.obj(t.w, Obj.BALL, Obj.NO_ID, 760, 700, 0, 0);
            t.objects();
            assertEquals(0, t.p.used & Has.CONNECTOR, "thrown 90 counts ago");
        }

        @Test
        @DisplayName("shields only hide shots: a smart missile is still handled; a mine is no item")
        void shieldsAndMines() {
            W t = new W("");
            t.p.used |= Has.SHIELD;
            RobotFixture.obj(t.w, Obj.SMART_SHOT, t.h.id, 790, 700, 0, 0);
            t.objects();
            W u = new W("");
            u.it.itemType = Item.SENSOR.ordinal();
            RobotFixture.obj(u.w, Obj.MINE, u.h.id, 760, 700, 0, 0);
            u.objects();
            assertAll(
                () -> assertEquals(List.of("ecm"), t.it.calls, "ECM against the smart missile"),
                () -> assertEquals(-1, u.r.itemI, "a mine is not an item"));
        }

        @Test
        @DisplayName("cannon shots are dangers; the nearest position (now or next frame, relative) wins")
        void dangers() {
            W t = new W("");
            int c = RobotFixture.obj(t.w, Obj.CANNON_SHOT, Obj.NO_ID, 760, 700, 0, 0);
            t.objects();
            W u = new W("");
            RobotFixture.obj(u.w, Obj.SHOT, u.h.id, 730, 700, 10, 0);
            u.objects();
            W v = new W("");
            RobotFixture.velocity(v.p, 5, 0);
            RobotFixture.obj(v.w, Obj.SHOT, v.h.id, 750, 700, -10, 0);
            v.objects();
            W y = new W("");
            RobotFixture.velocity(y.p, 0, 5);
            RobotFixture.obj(y.w, Obj.SHOT, y.h.id, 700, 750, 0, -10);
            y.objects();
            assertAll(
                () -> assertEquals(c, t.r.mineI, "cannon shot"),
                () -> assertEquals(30, u.r.mineDist, "moving away: 30 now, 40 next frame"),
                () -> assertEquals(35, v.r.mineDist, "x: 50 + (-10 - 5)"),
                () -> assertEquals(35, y.r.mineDist, "y: 50 + (-10 - 5)"));
        }

        @Test
        @DisplayName("the shield range is 21 + SHIP_SZ + the object's range; the robot's velocity counts")
        void shieldRange() {
            int low = RobotFixture.seedWithFirst(true, 0.5);
            W t = new W("");
            int h = RobotFixture.obj(t.w, Obj.SHOT, t.h.id, 740, 700, 0, 0);
            t.w.objects.plRange[h] = 10;
            t.w.rng.seed(low);
            t.objects();
            W u = new W("");
            RobotFixture.velocity(u.p, 0, 10);
            RobotFixture.obj(u.w, Obj.SHOT, u.h.id, 700, 730, 0, 0);
            u.w.rng.seed(low);
            u.objects();
            W v = new W("");
            RobotFixture.obj(v.w, Obj.SHOT, v.h.id, 740, 700, 0, 0);
            v.w.rng.seed(low);
            v.objects();
            assertAll(
                () -> assertEquals(Has.SHIELD, t.p.used & Has.SHIELD, "40 < 47"),
                () -> assertEquals(Has.SHIELD, u.p.used & Has.SHIELD, "next frame 730 - 710 = 20"),
                () -> assertEquals(0, v.p.used & Has.SHIELD, "40 >= 37"));
        }

        @Test
        @DisplayName("shield probability (int)(rfrac*100) < 85 + defense/7 - attack/50 exactly")
        void probability() {
            W t = new W("");
            RobotFixture.obj(t.w, Obj.SHOT, t.h.id, 720, 700, 0, 0);
            t.w.rng.seed(seedPercent(91));
            t.objects();
            W u = new W("");
            u.d.attack = 99;
            u.d.defense = 1;
            RobotFixture.obj(u.w, Obj.SHOT, u.h.id, 720, 700, 0, 0);
            u.w.rng.seed(seedPercent(84));
            u.objects();
            W v = new W("cloakedShield: no\n");
            v.p.used |= Has.CLOAKING_DEVICE;
            RobotFixture.obj(v.w, Obj.SHOT, v.h.id, 720, 700, 0, 0);
            v.w.rng.seed(seedPercent(10));
            v.objects();
            W k = new W("");
            k.p.used |= Has.CLOAKING_DEVICE;
            RobotFixture.obj(k.w, Obj.SHOT, k.h.id, 720, 700, 0, 0);
            k.w.rng.seed(seedPercent(10));
            k.objects();
            assertAll(
                () -> assertEquals(0, t.p.used & Has.SHIELD, "91 is not < 91"),
                () -> assertEquals(0, u.p.used & Has.SHIELD, "attack 99, defense 1: 84 is not < 84"),
                () -> assertEquals(0, v.p.used & Has.CLOAKING_DEVICE, "without cloakedShield the cloak drops"),
                () -> assertEquals(Has.CLOAKING_DEVICE, k.p.used & Has.CLOAKING_DEVICE, "with it the cloak stays"));
        }

        @Test
        @DisplayName("hyperjump needs a missile, (low fuel or no shields), an item and the fuel for it")
        void hyperjump() {
            int low = RobotFixture.seedWithFirst(true, 0.5);
            List<String> calls = new ArrayList<>();
            for (int c = 0; c < 4; c++) {
                W t = new W("");
                t.it.with(Item.HYPERJUMP, 1);
                int type = c == 0 ? Obj.SHOT : Obj.TORPEDO;
                if (c == 1) {
                    t.p.fuel = t.d.fuelL3;
                }
                if (c == 2) {
                    t.p.fuel = t.d.fuelL3;
                    t.p.have &= ~Has.SHIELD;
                }
                if (c == 3) {
                    t.p.fuel = 60 * 256;
                }
                RobotFixture.obj(t.w, type, t.h.id, 720, 700, 0, 0);
                t.w.rng.seed(low);
                t.objects();
                calls.add(c + ":" + t.it.calls.contains("hyperjump"));
            }
            assertEquals(List.of("0:false", "1:false", "2:true", "3:false"), calls,
                "a shot: no; fuel at level 3 with shields: no; without shields: yes; 60 fuel units: no");
        }

        @Test
        @DisplayName("ECM below 100 px for smart missiles only; tanks drop strictly between levels 1 and 3")
        void ecmAndTanks() {
            W t = new W("");
            RobotFixture.obj(t.w, Obj.SMART_SHOT, t.h.id, 800, 700, 0, 0);
            t.objects();
            W u = new W("");
            RobotFixture.obj(u.w, Obj.SHOT, u.h.id, 790, 700, 0, 0);
            u.objects();
            W v = new W("");
            v.it.tanks = 1;
            v.p.fuel = v.d.fuelL3;
            RobotFixture.obj(v.w, Obj.HEAT_SHOT, v.h.id, 800, 800, 0, 0);
            v.objects();
            W x = new W("");
            x.it.tanks = 1;
            x.p.fuel = x.d.fuelL1;
            RobotFixture.obj(x.w, Obj.HEAT_SHOT, x.h.id, 800, 800, 0, 0);
            x.objects();
            assertAll(
                () -> assertEquals(List.of(), t.it.calls, "100 px: no ECM"),
                () -> assertEquals(List.of(), u.it.calls, "a shot: no ECM"),
                () -> assertEquals(List.of(), v.it.calls, "fuel at level 3: keep the tank"),
                () -> assertEquals(List.of(), x.it.calls, "fuel at level 1: keep the tank"));
        }

        @Test
        @DisplayName("asteroids: shoot when 2 (4 for big ones) to 8 blocks away and dir - findDir < size*12")
        void asteroids() {
            List<String> out = new ArrayList<>();
            int[][] cases = {{770, 700, 1, 0}, {980, 700, 1, 0}, {800, 700, 2, 0}, {850, 700, 2, 0}, {900, 735, 1, 10},
                {900, 700, 1, 20}, {600, 700, 1, 0}};
            for (int[] c : cases) {
                W t = new W("");
                t.it.asteroid = c[2];
                t.p.dir = c[3];
                RobotFixture.obj(t.w, Obj.ASTEROID, Obj.NO_ID, c[0], c[1], 0, 0);
                t.objects();
                out.add(c[0] + "," + c[1] + "/" + c[2] + "/" + c[3] + ":" + ((t.p.used & Has.SHOT) != 0));
            }
            W s = new W("");
            RobotFixture.obj(s.w, Obj.SHOT, s.h.id, 900, 700, 0, 0);
            s.objects();
            assertAll(
                () -> assertEquals(List.of("770,700/1/0:false", "980,700/1/0:true", "800,700/2/0:false",
                    "850,700/2/0:true", "900,735/1/10:true", "900,700/1/20:false", "600,700/1/0:true"), out,
                    "C tests the nearest danger distance (216 to start), not this asteroid's: 70 px is too close, at 280 "
                        + "the distance stays 216; size 2 needs > 4 blocks; 10 - 5 < 12; 20 - 0 >= 12; "
                        + "C compares the raw difference: behind (0 - 64) shoots too"),
                () -> assertEquals(0, s.p.used & Has.SHOT, "other dangers do not trigger shooting"));
        }

        @Test
        @DisplayName("balls and wreckage are dangers when their collisions may kill")
        void options() {
            W t = new W("treasureCollisionMayKill: yes\nwreckageCollisionMayKill: yes\n");
            int ball = RobotFixture.obj(t.w, Obj.BALL, t.h.id, 760, 700, 0, 0);
            t.objects();
            int seenBall = t.r.mineI;
            W u = new W("wreckageCollisionMayKill: yes\n");
            int wr = RobotFixture.obj(u.w, Obj.WRECKAGE, Obj.NO_ID, 760, 700, 0, 0);
            u.objects();
            W v = new W("");
            RobotFixture.obj(v.w, Obj.WRECKAGE, Obj.NO_ID, 760, 700, 0, 0);
            v.objects();
            assertAll(
                () -> assertEquals(ball, seenBall, "a ball"),
                () -> assertEquals(wr, u.r.mineI, "wreckage"),
                () -> assertEquals(-1, v.r.mineI, "harmless wreckage"));
        }
    }

    // ------------------------------------------------------------------ lasers

    @Nested
    @DisplayName("checkLasers edges")
    class Lasers {
        private int shield(int dx, int dy, int pulseDir, int percent, String extra, int attack, int defense) {
            W t = new W(extra);
            t.d.attack = attack;
            t.d.defense = defense;
            t.it.pulses = 1;
            t.it.pulseOwner = t.h.id;
            t.it.pulseX = 700 - dx;
            t.it.pulseY = 700 - dy;
            t.it.pulseDir = pulseDir;
            t.p.used |= Has.CLOAKING_DEVICE;
            t.w.rng.seed(seedPercent(percent));
            t.r.checkLasers(t.p, t.d);
            return t.p.used & (Has.SHIELD | Has.CLOAKING_DEVICE);
        }

        @Test
        @DisplayName("distance, direction and probability edges")
        void edges() {
            int s = Has.SHIELD | Has.CLOAKING_DEVICE;
            assertAll(
                () -> assertEquals(Has.CLOAKING_DEVICE, shield(85, 0, 64, 10, "", 50, 50), "85 px is not < 85, aimed away"),
                () -> assertEquals(s, shield(84, 0, 64, 10, "", 50, 50), "84 px"),
                () -> assertEquals(Has.CLOAKING_DEVICE, shield(80, 60, 64, 10, "", 50, 50), "100 px aimed away"),
                () -> assertEquals(s, shield(165, -24, 120, 10, "", 50, 50), "167 px, from 125 toward 120: aimed"),
                () -> assertEquals(Has.CLOAKING_DEVICE, shield(0, 160, 16, 10, "", 50, 50), "from 32 vs 16: not aimed"),
                () -> assertEquals(Has.CLOAKING_DEVICE, shield(40, 0, 64, 91, "", 50, 50), "91 is not < 91"),
                () -> assertEquals(Has.CLOAKING_DEVICE, shield(40, 0, 64, 84, "", 99, 1), "84 is not < 85+0-1"),
                () -> assertEquals(Has.SHIELD, shield(40, 0, 64, 10, "cloakedShield: no\n", 50, 50), "cloak drops"));
        }
    }

    // ------------------------------------------------------------------ laser aiming and tractor beam

    @Nested
    @DisplayName("aimLaser")
    class Aim {
        /** C Check_robot_target laser test, written out from robotdef.c. */
        private boolean oracle(Player pl, Player ship, int lasers) {
            double x1 = pl.px + pl.vx + pl.ship.gunX[pl.dir];
            double y1 = pl.py + pl.vy + pl.ship.gunY[pl.dir];
            double x3 = ship.px + ship.vx;
            double y3 = ship.py + ship.vy;
            double dist = Math.hypot(x3 - x1, y3 - y1);
            if (!(dist < 90 * (4.5 + lasers / 4) + 16)) {
                return false;
            }
            double dir3 = XMath.findDir(x3 - x1, y3 - y1);
            double x4 = x3 + Trig.tcos(Trig.mod((int) (dir3 - 32))) * 16;
            double y4 = y3 + Trig.tsin(Trig.mod((int) (dir3 - 32))) * 16;
            double x5 = x3 + Trig.tcos(Trig.mod((int) (dir3 + 32))) * 16;
            double y5 = y3 + Trig.tsin(Trig.mod((int) (dir3 + 32))) * 16;
            double dir4 = XMath.findDir(x4 - x1, y4 - y1);
            double dir5 = XMath.findDir(x5 - x1, y5 - y1);
            return dir4 > dir5 ? (pl.dir >= dir4 || pl.dir <= dir5) : (pl.dir >= dir4 && pl.dir <= dir5);
        }

        @Test
        @DisplayName("fires for exactly the headings C's window allows, with both ships moving")
        void window() {
            int[][] cases = {{200, 40, 3, -4, 5, 2}, {-150, 90, -2, 6, 1, -3}, {60, -300, 0, 0, -8, 0}, {400, 0, 0, 0, 0, 0}};
            for (int[] c : cases) {
                SpyItems it = new SpyItems().with(Item.LASER, 4);
                W t = new W("");
                t.s.robots.setItems(it);
                DefaultRobot r = t.s.robots.robot();
                RobotFixture.place(t.h, 700 + c[0], 700 + c[1]);
                RobotFixture.velocity(t.h, c[2], c[3]);
                RobotFixture.velocity(t.p, c[4], c[5]);
                DefaultRobot.setWar(t.d, t.h.id);
                StringBuilder want = new StringBuilder();
                StringBuilder got = new StringBuilder();
                for (int dir = 0; dir < 128; dir++) {
                    t.p.dir = dir;
                    t.p.used &= ~Has.LASER;
                    r.aimLaser(t.p, t.d);
                    got.append((t.p.used & Has.LASER) != 0 ? '1' : '0');
                    want.append(oracle(t.p, t.h, 4) ? '1' : '0');
                }
                assertEquals(want.toString(), got.toString(), "headings for case " + java.util.Arrays.toString(c));
                assertTrue(want.indexOf("1") >= 0 || c[0] == 400, "the window is not empty");
            }
        }

        @Test
        @DisplayName("the war target must play, else the player lock is used; the lock flag alone is not enough")
        void targets() {
            SpyItems it = new SpyItems().with(Item.LASER, 4);
            W t = new W("");
            t.s.robots.setItems(it);
            DefaultRobot r = t.s.robots.robot();
            RobotFixture.place(t.h, 800, 700);
            t.d.robotLock = DefaultRobot.LOCK_VISIBLE;
            t.d.robotLockId = t.h.id;
            r.aimLaser(t.p, t.d);
            int visibleOnly = t.p.used & Has.LASER;
            t.p.lockTagged = DefaultRobot.LOCK_VISIBLE;
            t.p.lockPlId = t.h.id;
            r.aimLaser(t.p, t.d);
            assertAll(
                () -> assertEquals(0, visibleOnly, "no LOCK_PLAYER war"),
                () -> assertEquals(0, t.p.used & Has.LASER, "no LOCK_PLAYER lock"));
        }
    }

    @Nested
    @DisplayName("tractorBeam edges")
    class Tractor {
        private W setUp(int hx, int hy, double hvx, double hvy, double lockDist) {
            W t = new W("");
            RobotFixture.place(t.h, hx, hy);
            RobotFixture.velocity(t.h, hvx, hvy);
            t.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            t.p.lockPlId = t.h.id;
            t.p.lockDistance = (float) lockDist;
            return t;
        }

        private String beam(W t) {
            t.r.tractorBeam(t.p, t.d);
            return (t.p.used & Has.TRACTOR_BEAM) == 0 ? "off" : (t.p.tractorIsPressor ? "push" : "pull");
        }

        @Test
        @DisplayName("range 200 + 50 per item; relative velocity and direction decide")
        void cases() {
            W range = setUp(850, 700, 10, 0, 250);
            range.it.with(Item.TRACTOR_BEAM, 2);
            W relX = setUp(850, 700, 8, 0, 150);
            RobotFixture.velocity(relX.p, 3, 0);
            W relY = setUp(700, 850, 0, 8, 150);
            RobotFixture.velocity(relY.p, 0, 3);
            W sum = setUp(850, 700, 10, 0, 150);
            W below = setUp(700, 550, 0, -10, 150);
            W quarter = setUp(850, 700, 0, 10, 150);
            W normal = setUp(850, 700, 16, 0, 150);
            RobotFixture.velocity(normal.p, 6, 0);
            W close = setUp(850, 700, 0, 0, 64);
            W attack = setUp(850, 700, -17, 0, 150);
            W max = setUp(850, 700, 30, 0, 150);
            W min = setUp(850, 700, 6, 0, 150);
            W visible = setUp(850, 700, 10, 0, 150);
            visible.p.lockTagged = DefaultRobot.LOCK_VISIBLE;
            assertAll(
                () -> assertEquals("pull", beam(range), "250 < 200 + 2*50"),
                () -> assertEquals("off", beam(relX), "8 - 3 = 5 is not above normal speed"),
                () -> assertEquals("off", beam(relY), "the same vertically"),
                () -> assertEquals("pull", beam(sum), "moving away at 10"),
                () -> assertEquals("pull", beam(below), "below and moving down: away"),
                () -> assertEquals("pull", beam(quarter), "moving at right angles (exactly 1/4 turn) counts as away"),
                () -> assertEquals("pull", beam(normal), "robot at exactly normal speed may use it (relative 10)"),
                () -> assertEquals("off", beam(close), "64 px is not < 4 ship sizes, standing still"),
                () -> assertEquals("off", beam(attack), "closing at exactly attack speed"),
                () -> assertEquals("off", beam(max), "away at exactly max speed"),
                () -> assertEquals("off", beam(min), "away at exactly normal speed"),
                () -> assertEquals("off", beam(visible), "LOCK_VISIBLE alone is no lock"),
                () -> assertEquals(DefaultRobot.LOCK_VISIBLE, visible.p.lockTagged, "no beam: the lock is untouched"));
        }
    }

    // ------------------------------------------------------------------ weapons

    @Nested
    @DisplayName("attack weapon edges")
    class Weapons {
        @Test
        @DisplayName("ECM below 100 px only; laser needs fuel above level 3 + 10 units; no tractor without one")
        void edges() {
            SpyItems a = new SpyItems().with(Item.ECM, 1);
            W t = new W("");
            t.s.robots.setItems(a);
            t.d.robotCount = 501;
            t.s.robots.robot().checkTarget(t.p, t.d, 800, 700, DefaultRobot.RM_ATTACK);
            SpyItems b = new SpyItems().with(Item.LASER, 2);
            W u = new W("");
            u.s.robots.setItems(b);
            DefaultRobot.setWar(u.d, u.h.id);
            RobotFixture.place(u.h, 800, 700);
            u.p.fuel = u.d.fuelL3 + 10 * 256;
            u.d.robotCount = 501;
            u.s.robots.robot().checkTarget(u.p, u.d, 800, 700, DefaultRobot.RM_ATTACK);
            W v = new W("");
            v.p.used |= Has.TRACTOR_BEAM;
            v.p.have &= ~Has.TRACTOR_BEAM;
            v.d.robotCount = 501;
            v.r.checkTarget(v.p, v.d, 900, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(List.of(), a.calls, "100 px: no ECM"),
                () -> assertEquals(0, u.p.used & Has.LASER, "fuel exactly level 3 + 10: no laser"),
                () -> assertEquals(Has.TRACTOR_BEAM, v.p.used & Has.TRACTOR_BEAM, "no tractor beam: not touched"));
        }

        @Test
        @DisplayName("modifiers are chosen for lasers, missiles, random smart missiles and mines")
        void modifierCalls() {
            int seed = RobotFixture.seedWithFirst(false, 0.5);
            double[] f = RobotFixture.rfracs(seed, 2);
            SpyItems it = new SpyItems().with(Item.LASER, 2);
            W t = new W("allowLaserModifiers: yes\n");
            t.s.robots.setItems(it);
            DefaultRobot.setWar(t.d, t.h.id);
            RobotFixture.place(t.h, 800, 700);
            t.d.robotCount = 500;
            t.w.rng.seed(seed);
            t.s.robots.robot().checkTarget(t.p, t.d, 800, 700, DefaultRobot.RM_ATTACK);
            SpyItems m = new SpyItems().with(Item.MISSILE, 5);
            W u = new W("allowModifiers: yes\n");
            u.s.robots.setItems(m);
            u.d.robotCount = 504;
            u.d.lastFiredMissile = 100;
            u.w.rng.seed(seed);
            u.s.robots.robot().checkTarget(u.p, u.d, 900, 700, DefaultRobot.RM_ATTACK);
            SpyItems sm = new SpyItems().with(Item.MISSILE, 64);
            W v = new W("allowModifiers: yes\n");
            v.s.robots.setItems(sm);
            v.d.robotCount = 508;
            v.d.lastFiredMissile = 510;
            v.w.rng.seed(seed);
            v.s.robots.robot().checkTarget(v.p, v.d, 900, 700, DefaultRobot.RM_ATTACK);
            SpyItems mi = new SpyItems().with(Item.MINE, 31);
            W x = new W("allowNukes: yes\n");
            x.s.robots.setItems(mi);
            x.d.robotCount = 509;
            x.s.robots.robot().checkTarget(x.p, x.d, 1100, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(Has.LASER, t.p.used & Has.LASER, "laser"),
                () -> assertEquals((int) (f[0] * 3), Mods.laser(t.p.mods), "laser setting drawn (count % 4 == 0)"),
                () -> assertEquals(List.of("missile:" + Obj.TORPEDO + ":0"), m.calls, "504 % 5 == 4: torpedo"),
                () -> assertEquals((int) (f[0] * 4), Mods.power(u.p.mods), "missile power drawn"),
                () -> assertEquals(List.of("missile:" + Obj.SMART_SHOT + ":0"), sm.calls, "the random smart missile"),
                () -> assertEquals((int) (f[1] * 4), Mods.power(v.p.mods), "its power drawn after the 64 roll"),
                () -> assertEquals(List.of("mine"), mi.calls, "509 % 32 = 29 < 31: a mine"),
                () -> assertEquals(DefaultRobot.NUCLEAR, Mods.nuclear(x.p.mods), "a nuclear mine (9 <= attack 50)"));
        }

        @Test
        @DisplayName("missile choice: visible locked target keeps smart missiles; no smarts only changes smart ones")
        void missileTypes() {
            SpyItems a = new SpyItems().with(Item.MISSILE, 5);
            W t = new W("");
            t.s.robots.setItems(a);
            t.p.lockPlId = t.h.id;
            t.d.robotCount = 500;
            t.d.lastFiredMissile = 100;
            t.s.robots.robot().checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            SpyItems b = new SpyItems().with(Item.MISSILE, 5);
            W u = new W("allowSmartMissiles: no\n");
            u.s.robots.setItems(b);
            u.d.robotCount = 504;
            u.d.lastFiredMissile = 100;
            u.s.robots.robot().checkTarget(u.p, u.d, 900, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(List.of("missile:" + Obj.SMART_SHOT + ":0"), a.calls, "seen: smart"),
                () -> assertEquals(List.of("missile:" + Obj.TORPEDO + ":0"), b.calls, "torpedo stays a torpedo"));
        }

        @Test
        @DisplayName("the random smart missile needs (int)(rfrac*64) < count; shots every 50th count outside ATTACK")
        void chances() {
            SpyItems a = new SpyItems().with(Item.MISSILE, 3);
            W t = new W("");
            t.s.robots.setItems(a);
            t.d.robotCount = 506;
            t.d.lastFiredMissile = 510;
            int seed = 1;
            while ((int) (RobotFixture.rfracs(seed, 1)[0] * 64) != 3) {
                seed++;
            }
            t.w.rng.seed(seed);
            t.s.robots.robot().checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            String[] rows = rows(20, 30, 'A');
            Simulation s = RobotFixture.sim("timing: yes\n", rows);
            Player p = RobotFixture.robot(s, "R", 700, 700);
            RobotData d = s.robots.data(p);
            s.world.frames = 100;
            d.robotCount = 101;
            s.robots.robot().checkTarget(p, d, 900, 700, DefaultRobot.RM_NAVIGATE);
            int at100 = p.shots;
            d.robotCount = 103;
            s.world.frames = 200;
            s.robots.robot().checkTarget(p, d, 900, 700, DefaultRobot.RM_NAVIGATE);
            assertAll(
                () -> assertEquals(List.of(), a.calls, "(int)(r*64) == 3 is not < 3"),
                () -> assertEquals(1, t.p.shots, "then a shot instead"),
                () -> assertEquals(0, at100, "race NAVIGATE, count 101: odd"),
                () -> assertEquals(0, p.shots, "count 103 (102 is not a multiple of 50) and not ATTACK: no shot"));
        }

        @Test
        @DisplayName("race NAVIGATE fires on multiples of 50")
        void fifty() {
            String[] rows = rows(20, 30, 'A');
            Simulation s = RobotFixture.sim("timing: yes\n", rows);
            Player p = RobotFixture.robot(s, "R", 700, 700);
            RobotData d = s.robots.data(p);
            s.world.frames = 100;
            d.robotCount = 100;
            s.robots.robot().checkTarget(p, d, 900, 700, DefaultRobot.RM_NAVIGATE);
            assertEquals(1, p.shots, "count 100");
        }

        @Test
        @DisplayName("a mine needs count % 32 strictly below the stock")
        void mineEdge() {
            SpyItems mi = new SpyItems().with(Item.MINE, 21);
            W x = new W("");
            x.s.robots.setItems(mi);
            x.d.robotCount = 501;
            x.s.robots.robot().checkTarget(x.p, x.d, 1100, 700, DefaultRobot.RM_ATTACK);
            assertEquals(List.of(), mi.calls, "501 % 32 = 21 is not < 21");
        }
    }

    // ------------------------------------------------------------------ balls

    @Nested
    @DisplayName("ballHandler edges")
    class Balls {
        private Simulation s;
        private Player p;
        private Player enemy;
        private RobotData d;
        private SpyItems it;
        private DefaultRobot r;

        /** Team 1 robot at (300, 175); team 1 treasures at (3, 5) and (12, 1), team 2's at (26, 5). */
        private void setUp(boolean extraOwn, boolean wall) {
            String[] rows = RobotFixture.open(30, 10, "");
            char[] mid = rows[9 - 5].toCharArray();
            mid[3] = '*';
            mid[26] = '*';
            if (wall) {
                mid[12] = 'x';
            }
            rows[9 - 5] = new String(mid);
            if (extraOwn) {
                char[] low = rows[9 - 1].toCharArray();
                low[9] = '*';
                rows[9 - 1] = new String(low);
            }
            char[] bottom = rows[9].toCharArray();
            bottom[1] = '1';
            bottom[28] = '2';
            rows[9] = new String(bottom);
            s = RobotFixture.sim("teamPlay: yes\nreserveRobotTeam: no\n", rows);
            // these tests place their own balls: remove the treasure balls the world starts with
            s.world.objects.clear();
            java.util.Arrays.fill(s.world.modes.treasureHave, false);
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
        @DisplayName("throwing keeps other bits, needs a clear path, speed^2 > 60 and nobody nearer the ball")
        void throwing() {
            setUp(false, false);
            int b = RobotFixture.obj(s.world, Obj.BALL, p.id, 600, 193, -10, 0);
            it.towed = b;
            p.used |= Has.CONNECTOR | Has.SHIELD;
            d.longtermMode = DefaultRobot.FETCH_TREASURE | DefaultRobot.NEED_FUEL;
            r.ballHandler(p, d);
            int used = p.used & (Has.CONNECTOR | Has.SHIELD);
            int lt = d.longtermMode;
            setUp(false, false);
            b = RobotFixture.obj(s.world, Obj.BALL, p.id, 600, 193, -7.7, 0);
            it.towed = b;
            r.ballHandler(p, d);
            List<String> slow = List.copyOf(it.calls);
            setUp(false, false);
            b = RobotFixture.obj(s.world, Obj.BALL, p.id, 600, 193, -10, 0);
            it.towed = b;
            RobotFixture.place(enemy, 610, 193);
            r.ballHandler(p, d);
            List<String> crowded = List.copyOf(it.calls);
            setUp(false, true);
            b = RobotFixture.obj(s.world, Obj.BALL, p.id, 600, 193, -10, 0);
            it.towed = b;
            r.ballHandler(p, d);
            assertAll(
                () -> assertEquals(Has.SHIELD, used, "connector off, shield kept"),
                () -> assertEquals(DefaultRobot.NEED_FUEL, lt, "FETCH_TREASURE off, NEED_FUEL kept"),
                () -> assertEquals(List.of(), slow, "7.7^2 = 59.3 is too slow"),
                () -> assertEquals(List.of(), crowded, "the enemy is nearer the ball"),
                () -> assertEquals(List.of(), it.calls, "a wall between ball and treasure"),
                () -> assertEquals(DefaultRobot.FETCH_TREASURE, d.longtermMode & DefaultRobot.FETCH_TREASURE, "fetching"));
        }

        @Test
        @DisplayName("a robot carrying (HAS_BALL) finds its ball object; the towed handle 0 is a ball")
        void finding() {
            setUp(false, false);
            int b = RobotFixture.obj(s.world, Obj.BALL, p.id, 600, 193, -10, 0);
            p.have |= Has.BALL;
            r.ballHandler(p, d);
            List<String> found = List.copyOf(it.calls);
            setUp(false, false);
            RobotFixture.obj(s.world, Obj.BALL, enemy.id, 900, 193, 0, 0);
            int mine = RobotFixture.obj(s.world, Obj.BALL, Obj.NO_ID, 600, 193, -10, 0);
            it.towed = 0;
            s.world.objects.vx[0] = -10;
            s.world.objects.px[0] = 600;
            s.world.objects.py[0] = 193;
            r.ballHandler(p, d);
            assertAll(
                () -> assertEquals(0, b, "the first object"),
                () -> assertEquals(List.of("detachBall"), found, "found by owner and thrown"),
                () -> assertTrue(mine > 0, "another ball"),
                () -> assertEquals(List.of("detachBall"), it.calls, "handle 0 counts as a towed ball"));
        }

        @Test
        @DisplayName("the nearest own treasure is the goal; teams without members are ignored")
        void goals() {
            setUp(true, false);
            int b = RobotFixture.obj(s.world, Obj.BALL, p.id, 600, 193, 0, -10);
            it.towed = b;
            RobotFixture.place(p, 330, 70);
            r.ballHandler(p, d);
            double towardLow = p.turnAcc;
            setUp(false, false);
            s.ships.leave(enemy);
            it.treasureBall = true;
            boolean emptyTeam = r.ballHandler(p, d);
            assertAll(
                () -> assertEquals(-64.0, towardLow, "the treasure at (9,1) is nearer than (3,5): turn right"),
                () -> assertFalse(emptyTeam, "team 2 has nobody: its treasure is not a goal"));
        }

        @Test
        @DisplayName("fetch limits: count/10 blocks for treasures and balls, beyond the connector length")
        void limits() {
            setUp(false, false);
            it.treasureBall = true;
            d.robotCount = 123;
            boolean twelve = r.ballHandler(p, d);
            d.robotCount = 190;
            d.longtermMode = 0;
            boolean nineteen = r.ballHandler(p, d);
            setUp(false, false);
            RobotFixture.obj(s.world, Obj.BALL, enemy.id, 700, 175, 0, 0);
            d.robotCount = 110;
            boolean ball11 = r.ballHandler(p, d);
            d.robotCount = 120;
            boolean ball12 = r.ballHandler(p, d);
            assertAll(
                () -> assertFalse(twelve, "627 px > 12 blocks"),
                () -> assertTrue(nineteen, "< 19 blocks"),
                () -> assertEquals(DefaultRobot.FETCH_TREASURE, d.longtermMode, "fetching"),
                () -> assertFalse(ball11, "400 px >= 11 blocks"),
                () -> assertTrue(ball12, "400 < 12 blocks"));
        }
    }

    // ------------------------------------------------------------------ hunt, climb, helpers

    @Nested
    @DisplayName("checkHunt edges")
    class Hunt {
        private W at(double v, int hx, int hy, int dir) {
            W t = new W("");
            RobotFixture.place(t.h, hx, hy);
            RobotFixture.velocity(t.p, v, 0);
            t.p.dir = dir;
            t.p.turnAcc = 5;
            DefaultRobot.setWar(t.d, t.h.id);
            t.r.checkHunt(t.p, t.d);
            return t;
        }

        @Test
        @DisplayName("speeds exactly attack/2 and attack are fine; RES/16 off still coasts")
        void edges() {
            W half = at(8.5, 1100, 700, 0);
            W full = at(17, 1100, 700, 0);
            W off8 = at(10, 1100, 700 + 165, 0);
            W off9 = at(10, 1100, 700 + 190, 0);
            W right8 = at(10, 1100, 700 - 165, 0);
            assertAll(
                () -> assertEquals(0.0, half.p.turnAcc, "8.5: coast"),
                () -> assertEquals(0.0, full.p.turnAcc, "17: coast"),
                () -> assertEquals(0.0, off8.p.turnAcc, "8 left of travel: coast"),
                () -> assertEquals(16.0, off9.p.turnAcc, "9 left: steer"),
                () -> assertEquals(0.0, right8.p.turnAcc, "8 right: coast"));
        }

        @Test
        @DisplayName("aim offsets: half a turn off steers right; exactly RES/16 off the wanted heading still turns")
        void offsets() {
            W behind = at(0, 300, 700, 0);
            W far = at(0, 1100, 700, 120);
            W edge = at(0, 1100, 700, 8);
            W inside = at(0, 1100, 700, 9);
            assertAll(
                () -> assertEquals(-16.0, behind.p.turnAcc, "target at 64: wants 112: turn right"),
                () -> assertEquals(16.0, far.p.turnAcc, "too slow: wants 16; from 120 that is 24 to the left"),
                () -> assertEquals(16.0, edge.p.turnAcc, "from 8: exactly RES/16 off: turn"),
                () -> assertEquals(5.0, inside.p.turnAcc, "from 9: 7 off: turning unchanged"));
        }
    }

    @Nested
    @DisplayName("climbOrIdle edges")
    class Climb {
        @Test
        @DisplayName("heading exactly 3/4 turn counts as pointing right of up; boundaries of the climb thrust")
        void edges() {
            W t = new W("");
            t.d.robotCount = 31;
            t.p.dir = 96;
            t.r.climbOrIdle(t.p, t.d);
            double at96 = t.p.turnAcc;
            t.p.dir = 32;
            RobotFixture.velocity(t.p, 0, 3);
            t.p.status &= ~Obj.THRUSTING;
            t.r.climbOrIdle(t.p, t.d);
            int y3 = t.p.status & Obj.THRUSTING;
            RobotFixture.velocity(t.p, 0, 6);
            t.p.status |= Obj.THRUSTING;
            t.r.climbOrIdle(t.p, t.d);
            int y6 = t.p.status & Obj.THRUSTING;
            RobotFixture.velocity(t.p, 16.9, 1);
            t.p.velocity = 17;
            t.p.status &= ~Obj.THRUSTING;
            t.r.climbOrIdle(t.p, t.d);
            assertAll(
                () -> assertEquals(32.0, at96, "96: turn left"),
                () -> assertEquals(0, y3, "rising at exactly 3: no thrust"),
                () -> assertEquals(Obj.THRUSTING, y6, "rising at exactly 6: kept"),
                () -> assertEquals(0, t.p.status & Obj.THRUSTING, "at exactly attack speed: no thrust"));
        }

        @Test
        @DisplayName("count % 64 == 32 idles; falling exactly at normal speed idles; idle at exactly 3 does not thrust")
        void idleEdges() {
            W t = new W("");
            t.d.robotCount = 32;
            RobotFixture.velocity(t.p, 0, -6);
            t.r.climbOrIdle(t.p, t.d);
            int mode = t.d.robotMode;
            RobotFixture.velocity(t.p, 3, 0);
            t.r.climbOrIdle(t.p, t.d);
            W g = new W("gravity: -2\ngravityAngle: 0\n");
            g.d.robotCount = 32;
            RobotFixture.velocity(g.p, 3, 0);
            g.r.climbOrIdle(g.p, g.d);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_ROBOT_IDLE, mode, "idle"),
                () -> assertEquals(0, t.p.status & Obj.THRUSTING, "speed 3 is not < 3"),
                () -> assertEquals(0, g.p.status & Obj.THRUSTING, "x: 3 - 2*(-2) = 7 > 6"));
        }
    }

    @Nested
    @DisplayName("helper edges")
    class Helpers {
        @Test
        @DisplayName("nearbyFuel: 90 px is near (truncated as C), team fuel of others is skipped")
        void fuel() {
            W t = world("", 21, 20, '#');
            t.w.fuelLevel[0] = 400 * 256;
            int fx = (int) t.w.map.fuels().get(0).x();
            RobotFixture.place(t.p, fx - 90, 717);
            t.r.nearbyFuel(t.p);
            int at90 = t.p.used & Has.REFUEL;
            RobotFixture.place(t.p, fx - 91, 717);
            t.r.nearbyFuel(t.p);
            int at91 = t.p.used & Has.REFUEL;
            t.w.fuelLevel[0] = 1280;
            RobotFixture.place(t.p, fx - 50, 717);
            t.r.nearbyFuel(t.p);
            String[] rows = rows(21, 20, '#');
            char[] b = rows[39].toCharArray();
            b[1] = '1';
            b[3] = '1';
            b[5] = '2';
            b[7] = ' ';
            rows[39] = new String(b);
            Simulation s = RobotFixture.sim("teamPlay: yes\nreserveRobotTeam: no\nteamFuel: yes\n", rows);
            s.world.fuelLevel[0] = 400 * 256;
            Player q = s.ships.join("Q", 1, true);
            RobotFixture.place(q, fx - 50, 717);
            s.robots.robot().nearbyFuel(q);
            assertAll(
                () -> assertEquals(Has.REFUEL, at90, "90.5 px truncates to 90"),
                () -> assertEquals(0, at91, "91"),
                () -> assertEquals(0, t.p.used & Has.REFUEL, "exactly REFUEL_RATE left is not enough"),
                () -> assertEquals(2, s.world.map.fuels().get(0).team(), "team 2's station"),
                () -> assertEquals(0, q.used & Has.REFUEL, "a team 1 robot may not use it"));
        }

        @Test
        @DisplayName("nearbyTarget: the target centre is its block corner + 17; 90 px inclusive")
        void target() {
            String[] rows = RobotFixture.open(40, 40, "");
            char[] b = rows[39].toCharArray();
            b[1] = '1';
            rows[39] = new String(b);
            char[] r = rows[39 - 20].toCharArray();
            r[21] = '!';
            rows[39 - 20] = new String(r);
            Simulation s = RobotFixture.sim("teamPlay: yes\nreserveRobotTeam: no\n", rows);
            SpyItems it = new SpyItems();
            it.damage = 1;
            s.robots.setItems(it);
            Player p = s.ships.join("R", 1, true);
            RobotData d = s.robots.data(p);
            List<String> out = new ArrayList<>();
            int[][] at = {{752 + 90, 717}, {752 + 91, 717}, {752 - 80, 717 + 30}, {752, 717 + 95}};
            for (int[] a : at) {
                RobotFixture.place(p, a[0], a[1]);
                p.used &= ~Has.REPAIR;
                s.robots.robot().nearbyTarget(p, d);
                out.add(a[0] + "," + a[1] + ":" + ((p.used & Has.REPAIR) != 0));
            }
            assertEquals(List.of("842,717:true", "843,717:false", "672,747:true", "752,812:false"), out,
                "centre (752, 717): 90 in, 91 out, (-80, 30) in, 95 out");
        }

        @Test
        @DisplayName("choosePlayerLock: LOCK_VISIBLE alone counts as no lock")
        void lockVisible() {
            W t = new W("");
            t.p.lockTagged = DefaultRobot.LOCK_VISIBLE;
            t.p.lockPlId = 0;
            t.r.choosePlayerLock(t.p, t.d, t.h, 900);
            assertEquals(t.h.id, t.p.lockPlId, "locked");
        }

        @Test
        @DisplayName("releasePlayerLock: a war target only matters while at war (LOCK_PLAYER)")
        void releaseWar() {
            W t = new W("");
            Player g = RobotFixture.human(t.s, "G", 900, 700);
            t.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            t.p.lockPlId = t.h.id;
            t.d.robotLock = DefaultRobot.LOCK_VISIBLE;
            t.d.robotLockId = g.id;
            t.r.releasePlayerLock(t.p, t.d);
            assertEquals(DefaultRobot.LOCK_PLAYER, t.p.lockTagged, "kept");
        }

        @Test
        @DisplayName("detectHunt: a thrusting cloaked ship beyond visibility is not detected")
        void farExhaust() {
            SpyItems it = new SpyItems();
            it.see = false;
            Simulation s = RobotFixture.sim("", RobotFixture.open(40, 40, "__"));
            s.robots.setItems(it);
            Player p = RobotFixture.robot(s, "R", 100, 100);
            Player h = RobotFixture.human(s, "H", 700, 100);
            h.status |= Obj.THRUSTING;
            s.robots.robot().visibilityDistance = 599;
            assertFalse(s.robots.robot().detectHunt(p, h), "600 px > 599");
        }

        @Test
        @DisplayName("create: max speed subtracts defense/50; level 1 noise is (int)((rfrac-0.5)*4)")
        void create() {
            Simulation s = RobotFixture.sim("", RobotFixture.open(6, 4, "_"));
            RobotData d = new RobotData();
            int seed = 1;
            while (true) {
                double[] f = RobotFixture.rfracs(seed, 3);
                if (f[2] < 0.2) {
                    break;
                }
                seed++;
            }
            s.world.rng.seed(seed);
            double[] f = RobotFixture.rfracs(seed, 3);
            s.robots.robot().create(d, "60 50");
            assertAll(
                () -> assertEquals(30.0, d.maxSpeed, "30 + 60/50 - 50/50"),
                () -> assertEquals(-2 + (int) ((f[2] - 0.5f) * 4), d.fuelL1, "(50-60)/5 = -2 plus noise " + f[2]));
        }

        @Test
        @DisplayName("chooseWeaponModifier: nuclear at count%100 == attack; full nuclear needs < 2*min or even")
        void nukes() {
            SpyItems it = new SpyItems().with(Item.MINE, 5);
            W t = new W("allowNukes: yes\nallowLaserModifiers: yes\n");
            t.s.robots.setItems(it);
            DefaultRobot r = t.s.robots.robot();
            t.d.attack = 41;
            t.d.robotCount = 41;
            r.chooseWeaponModifier(t.p, t.d, Obj.MINE);
            int at = Mods.nuclear(t.p.mods);
            it.with(Item.MINE, 8);
            t.d.robotCount = 40;
            r.chooseWeaponModifier(t.p, t.d, Obj.MINE);
            int even = Mods.nuclear(t.p.mods);
            int seed = RobotFixture.seedWithFirst(false, 0.4);
            t.w.rng.seed(seed);
            t.d.robotCount = 4;
            r.chooseWeaponModifier(t.p, t.d, com.fulcrologic.jxpilot.server.world.Has.LASER);
            assertAll(
                () -> assertEquals(DefaultRobot.NUCLEAR | DefaultRobot.FULLNUCLEAR, at, "41 <= 41; 5 < 8"),
                () -> assertEquals(DefaultRobot.NUCLEAR | DefaultRobot.FULLNUCLEAR, even, "8 = 2*4 but even count"),
                () -> assertEquals((int) (RobotFixture.rfracs(seed, 1)[0] * 3), Mods.laser(t.p.mods), "laser 1..2"));
        }

        @Test
        @DisplayName("item classes: offensive and defensive sums, tanks from the tank list")
        void classes() {
            SpyItems it = new SpyItems();
            W t = new W("");
            t.s.robots.setItems(it);
            DefaultRobot r = t.s.robots.robot();
            int v = 1;
            for (Item i : Item.values()) {
                it.with(i, v);
                v *= 2;
            }
            it.tanks = 1 << 22;
            it.defensives = 1 << 23;
            int off = Item.WIDEANGLE.bit() | Item.REARSHOT.bit() | Item.MINE.bit() | Item.MISSILE.bit() | Item.LASER.bit();
            int def = Item.CLOAK.bit() | Item.ECM.bit() | Item.TRANSPORTER.bit() | Item.TRACTOR_BEAM.bit()
                | Item.EMERGENCY_SHIELD.bit() | Item.MIRROR.bit() | Item.DEFLECTOR.bit() | Item.HYPERJUMP.bit()
                | Item.PHASING.bit() | Item.ARMOR.bit();
            assertAll(
                () -> assertEquals(off, r.countOffensive(t.p), "each offensive item once"),
                () -> assertEquals(def + (1 << 22) + (1 << 23), r.countDefensive(t.p), "defensive items, tanks, active"),
                () -> assertEquals(off, DefaultRobot.OFFENSIVE_ITEMS, "C IsOffensiveItem"),
                () -> assertEquals(def | Item.TANK.bit(), DefaultRobot.DEFENSIVE_ITEMS, "C IsDefensiveItem"));
        }
    }

    // ------------------------------------------------------------------ play paths

    @Nested
    @DisplayName("play paths")
    class Play {
        @Test
        @DisplayName("race mode: no item nearby, navigate to checkpoint 0")
        void race() {
            String[] rows = rows(30, 20, 'A');
            Simulation s = RobotFixture.sim("timing: yes\n", rows);
            Player p = RobotFixture.robot(s, "R", 700, 700);
            RobotData d = s.robots.data(p);
            d.robotCount = 101;
            s.robots.robot().play(p, d);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_NAVIGATE, d.robotMode, "NAVIGATE"),
                () -> assertEquals(-64.0, p.turnAcc, "heading 32 (the base's), checkpoint 0 at heading 0: turn right"));
        }

        @Test
        @DisplayName("an important item within 12 blocks is harvested, leading it by velocity * axis distance / normal")
        void harvest() {
            W t = new W("");
            t.it.itemType = Item.SENSOR.ordinal();
            RobotFixture.obj(t.w, Obj.ITEM, Obj.NO_ID, 900, 900, 6, 0);
            t.p.dir = 9;
            t.d.robotCount = 101;
            t.r.refreshCells();
            t.r.play(t.p, t.d);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_HARVEST, t.d.robotMode, "HARVEST"),
                () -> assertEquals(0.0, t.p.turnSpeed, "aims at (900 + 6*200/6, 900): heading 9, already on it"));
        }

        @Test
        @DisplayName("still refuelling (NEED_FUEL kept), only fuel items are harvested before attacking")
        void harvestNeedFuel() {
            List<String> out = new ArrayList<>();
            for (Item kind : new Item[] {Item.SENSOR, Item.FUEL, Item.TANK}) {
                W t = new W("");
                t.it.itemType = kind.ordinal();
                t.p.fuel = t.d.fuelL3 + 1000;
                t.p.used |= Has.REFUEL;
                t.d.longtermMode = DefaultRobot.NEED_FUEL;
                RobotFixture.place(t.h, 1000, 700);
                t.p.lockTagged = DefaultRobot.LOCK_PLAYER;
                t.p.lockPlId = t.h.id;
                t.p.lockDistance = 300;
                RobotFixture.obj(t.w, Obj.ITEM, Obj.NO_ID, 800, 800, 0, 0);
                t.d.robotCount = 101;
                t.r.refreshCells();
                t.r.play(t.p, t.d);
                out.add(kind + ":" + (t.d.robotMode == DefaultRobot.RM_HARVEST) + ":" + t.p.shots);
            }
            assertEquals(List.of("SENSOR:true:1", "FUEL:true:0", "TANK:true:0"), out,
                "a sensor is fetched only after attacking (a shot); fuel and tanks at once");
        }

        @Test
        @DisplayName("attacking leads the locked target by its velocity minus ours over the shot flight time")
        void lead() {
            W t = new W("");
            RobotFixture.place(t.h, 920, 700);
            RobotFixture.velocity(t.h, 0, 10);
            t.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            t.p.lockPlId = t.h.id;
            t.p.lockDistance = 220;
            t.d.robotCount = 101;
            t.r.play(t.p, t.d);
            W u = new W("");
            RobotFixture.place(u.h, 920, 700);
            RobotFixture.velocity(u.h, 0, 10);
            RobotFixture.velocity(u.p, 0, 10);
            u.p.dir = 0;
            u.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            u.p.lockPlId = u.h.id;
            u.p.lockDistance = 220;
            u.d.robotCount = 101;
            u.r.play(u.p, u.d);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_ATTACK, t.d.robotMode, "ATTACK"),
                () -> assertEquals(64.0, t.p.turnAcc, "aims 100 px above (shoot time 10): turn left"),
                () -> assertEquals(DefaultRobot.RM_ATTACK, u.d.robotMode, "ATTACK"),
                () -> assertEquals(0.0, u.p.turnSpeed, "same velocity: aim straight at it"));
        }

        @Test
        @DisplayName("limited visibility: enemies up to fuel*2.5/256 px away above fuel level 3, else visibility")
        void limitedVisibility() {
            String lv = "limitedVisibility: yes\n";
            String[][] cases = {{lv, "600"}, {lv, "501"}, {lv, "500"}, {"", "501"}};
            List<String> out = new ArrayList<>();
            for (String[] c : cases) {
                W t = new W(c[0]);
                RobotFixture.place(t.p, 200, 200);
                RobotFixture.place(t.h, 1300, 1200);
                t.p.fuel = Integer.parseInt(c[1]) * 256;
                t.d.robotCount = 31;
                t.r.play(t.p, t.d);
                out.add(c[1] + ":" + (t.p.lockPlId == t.h.id));
            }
            assertEquals(List.of("600:true", "501:false", "500:false", "501:true"), out,
                "1486 px: in range of 600 units (1500), not 501 (1252); at level 3 only visibility; "
                    + "without limited visibility the whole map");
        }

        @Test
        @DisplayName("a close ship that is the war target is attacked, not avoided")
        void warTargetNotAvoided() {
            W t = new W("");
            RobotFixture.place(t.h, 760, 700);
            DefaultRobot.setWar(t.d, t.h.id);
            t.d.robotCount = 101;
            t.r.play(t.p, t.d);
            W u = new W("");
            RobotFixture.place(u.h, 760, 700);
            u.d.robotCount = 101;
            u.r.play(u.p, u.d);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_ATTACK, t.d.robotMode, "attacks the war target"),
                () -> assertTrue(u.d.robotMode == DefaultRobot.RM_EVADE_LEFT || u.d.robotMode == DefaultRobot.RM_EVADE_RIGHT,
                    "evades another ship"));
        }

        @Test
        @DisplayName("after evading: shields when shielded bounces are better; not when they are not")
        void bouncyShields() {
            W t = world("", 23, 20, 'x');
            RobotFixture.velocity(t.p, 10, 0);
            t.d.robotCount = 101;
            t.r.play(t.p, t.d);
            W u = world("maxShieldedWallBounceSpeed: 20\n", 23, 20, 'x');
            RobotFixture.velocity(u.p, 10, 0);
            u.d.robotCount = 101;
            u.r.play(u.p, u.d);
            W v = world("maxShieldedPlayerWallBounceAngle: 20\n", 23, 20, 'x');
            RobotFixture.velocity(v.p, 10, 0);
            v.d.robotCount = 101;
            v.r.play(v.p, v.d);
            W x = world("allowShields: no\nplayerStartsShielded: yes\n", 23, 20, 'x');
            x.p.have |= Has.SHIELD;
            RobotFixture.velocity(x.p, 10, 0);
            x.d.robotCount = 101;
            x.r.play(x.p, x.d);
            W y = world("", 23, 20, 'x');
            y.p.have &= ~Has.SHIELD;
            RobotFixture.velocity(y.p, 10, 0);
            y.d.robotCount = 101;
            y.r.play(y.p, y.d);
            assertAll(
                () -> assertEquals(Has.SHIELD, t.p.used & Has.SHIELD, "50 > 20 and 90 >= 30"),
                () -> assertEquals(0, u.p.used & Has.SHIELD, "20 is not > 20"),
                () -> assertEquals(0, v.p.used & Has.SHIELD, "angle 20 < 30"),
                () -> assertEquals(Has.SHIELD, x.p.used & Has.SHIELD, "no shield key, started shielded"),
                () -> assertEquals(0, y.p.used & Has.SHIELD, "no shields at all"));
        }

        @Test
        @DisplayName("each frame looks at objects, lasers, the lock and finally climbs or idles")
        void steps() {
            W t = new W("");
            RobotFixture.obj(t.w, Obj.SHOT, t.h.id, 720, 700, 0, 0);
            t.w.rng.seed(RobotFixture.seedWithFirst(true, 0.5));
            t.d.robotMode = 0;
            t.d.robotCount = 101;
            t.r.refreshCells();
            t.r.play(t.p, t.d);
            W u = new W("");
            u.it.pulses = 1;
            u.it.pulseOwner = u.h.id;
            u.it.pulseX = 720;
            u.it.pulseY = 700;
            u.w.rng.seed(RobotFixture.seedWithFirst(true, 0.5));
            u.d.robotCount = 101;
            u.r.play(u.p, u.d);
            W v = new W("");
            v.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            v.p.lockPlId = v.h.id;
            v.h.status &= ~Obj.PLAYING;
            v.d.robotCount = 102;
            v.d.robotMode = 0;
            v.r.play(v.p, v.d);
            assertAll(
                () -> assertEquals(Has.SHIELD, t.p.used & Has.SHIELD, "a shot about to hit: shields"),
                () -> assertEquals(Has.SHIELD, u.p.used & Has.SHIELD, "a pulse: shields"),
                () -> assertEquals(0, v.p.lockTagged, "the dead lock target is dropped"),
                () -> assertEquals(DefaultRobot.RM_ROBOT_IDLE, v.d.robotMode, "idling (101 % 64 >= 32)"));
        }

        @Test
        @DisplayName("team play: repairs a damaged own target nearby; balls are handled (none here)")
        void teamPlay() {
            String[] rows = RobotFixture.open(40, 40, "");
            char[] b = rows[39].toCharArray();
            b[1] = '1';
            rows[39] = new String(b);
            char[] r = rows[39 - 20].toCharArray();
            r[21] = '!';
            r[30] = '*';
            rows[39 - 20] = new String(r);
            Simulation s = RobotFixture.sim("teamPlay: yes\nreserveRobotTeam: no\n", rows);
            SpyItems it = new SpyItems();
            it.damage = 1;
            s.robots.setItems(it);
            Player p = s.ships.join("R", 1, true);
            RobotFixture.place(p, 700, 700);
            RobotData d = s.robots.data(p);
            d.robotCount = 101;
            d.normalSpeed = 6;
            d.attackSpeed = 17;
            d.maxSpeed = 30;
            d.fuelL1 = 100 * 256;
            d.fuelL2 = 200 * 256;
            d.fuelL3 = 500 * 256;
            s.robots.robot().play(p, d);
            assertAll(
                () -> assertEquals(Has.REPAIR, p.used & Has.REPAIR, "repairing"),
                () -> assertEquals(1, s.world.map.treasures().get(0).team(), "an own treasure exists"));
        }

        @Test
        @DisplayName("the take-off thrust needs |dx| + |dy| < BLOCK_SZ from the home base block's corner")
        void takeOff() {
            List<String> out = new ArrayList<>();
            int[][] at = {{20, 10}, {20, 15}, {-20, 0}, {0, 34}};
            for (int[] a : at) {
                W t = new W("");
                com.fulcrologic.jxpilot.common.map.Base b = t.w.map.bases().get(t.p.homeBase);
                RobotFixture.place(t.p, b.bx() * 35 + a[0], b.by() * 35 + a[1]);
                RobotFixture.velocity(t.p, 0, 4);
                t.p.dir = 32;
                t.d.robotCount = 30;
                t.r.play(t.p, t.d);
                out.add(a[0] + "," + a[1] + ":" + ((t.p.status & Obj.THRUSTING) != 0));
            }
            assertEquals(List.of("20,10:true", "20,15:false", "-20,0:true", "0,34:true"), out,
                "climbing at 4 keeps the thrust state: 30 and 20 thrust, 35 not");
        }
    }
}
