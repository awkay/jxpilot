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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DefaultRobot (robotdef.c): play_check_objects, play_check_lasers and Robot_default_play. */
class DefaultRobotPlayTest {

    /** 40x40 open world, robot R at (700, 700), human H far away at (1200, 1200). */
    static final class W {
        final Simulation s;
        final World w;
        final Player p;
        final Player h;
        final RobotData d;
        final SpyItems it = new SpyItems();
        final DefaultRobot r;

        W(String extra) {
            this(extra, RobotFixture.open(40, 40, "___"));
        }

        W(String extra, String[] rows) {
            s = RobotFixture.sim(extra, rows);
            w = s.world;
            s.robots.setItems(it);
            r = s.robots.robot();
            p = RobotFixture.robot(s, "R", 700, 700);
            h = RobotFixture.human(s, "H", 1200, 1200);
            d = s.robots.data(p);
            p.dir = 0;
            w.frames = 100;
        }

        void objects() {
            r.refreshCells();
            r.mineI = -1;
            r.mineDist = 16 + 200;
            r.itemI = -1;
            r.itemDist = (int) r.visibilityDistance;
            r.itemImp = 0;
            r.checkObjects(p, d);
        }
    }

    @Nested
    @DisplayName("checkObjects (Robot_default_play_check_objects)")
    class Objects {
        @Test
        @DisplayName("a hostile shot about to hit raises shields and thrust with the evade probability; it is the nearest danger")
        void shot() {
            W t = new W("");
            int sh = RobotFixture.obj(t.w, Obj.SHOT, t.h.id, 730, 700, -10, 0);
            int below = RobotFixture.seedWithFirst(true, 0.91);
            t.w.rng.seed(below);
            t.objects();
            assertAll(
                () -> assertEquals(Has.SHIELD, t.p.used & Has.SHIELD, "rfrac*100 < 85 + 50/7 - 50/50 = 91: shields"),
                () -> assertEquals(Obj.THRUSTING, t.p.status & Obj.THRUSTING, "and thrust"),
                () -> assertEquals(sh, t.r.mineI, "the shot is the nearest danger"),
                () -> assertEquals(20, t.r.mineDist, "one frame ahead it is 20 px away"));
            W u = new W("");
            RobotFixture.obj(u.w, Obj.SHOT, u.h.id, 730, 700, -10, 0);
            u.w.rng.seed(RobotFixture.seedWithFirst(false, 0.91));
            u.objects();
            assertEquals(0, u.p.used & Has.SHIELD, "rfrac*100 >= 91: no shields");
        }

        @Test
        @DisplayName("a shot passing outside the shield range consumes no rfrac and raises nothing")
        void miss() {
            W t = new W("");
            RobotFixture.obj(t.w, Obj.SHOT, t.h.id, 700, 750, 10, 0);
            t.w.rng.seed(9);
            t.objects();
            assertAll(
                () -> assertEquals(0, t.p.used & Has.SHIELD, "no shields"),
                () -> assertEquals(50, t.r.mineDist, "still the nearest danger at 50"),
                () -> assertEquals(RobotFixture.rfracs(9, 1)[0], t.w.rfrac(), "no rfrac drawn"));
        }

        @Test
        @DisplayName("ignored: debris, shots while shielded, own shots with selfImmunity, team mates' shots, mines")
        void ignored() {
            W t = new W("selfImmunity: yes\n");
            RobotFixture.obj(t.w, Obj.DEBRIS, t.h.id, 710, 700, 0, 0);
            RobotFixture.obj(t.w, Obj.SHOT, t.p.id, 710, 700, 0, 0);
            RobotFixture.obj(t.w, Obj.MINE, t.h.id, 710, 700, 0, 0);
            t.objects();
            boolean noneA = t.r.mineI == -1 && (t.p.used & Has.SHIELD) == 0;
            W u = new W("");
            RobotFixture.obj(u.w, Obj.SHOT, u.h.id, 710, 700, 0, 0);
            u.p.used |= Has.SHIELD;
            u.objects();
            assertAll(
                () -> assertTrue(noneA, "debris, own shot with selfImmunity and a mine (not a killing shot)"),
                () -> assertEquals(-1, u.r.mineI, "shots are ignored behind shields"));
        }

        @Test
        @DisplayName("team mates' shots are ignored; own shots without selfImmunity can raise shields but are no danger")
        void owners() {
            String[] rows = RobotFixture.open(40, 40, "");
            char[] b = rows[39].toCharArray();
            b[1] = '1';
            b[3] = '1';
            rows[39] = new String(b);
            Simulation s = RobotFixture.sim("teamPlay: yes\nreserveRobotTeam: no\n", rows);
            Player p = s.ships.join("R", 1, true);
            RobotFixture.place(p, 700, 700);
            p.used &= ~Has.SHIELD;
            Player mate = s.ships.join("M", 1);
            RobotData d = s.robots.data(p);
            DefaultRobot r = s.robots.robot();
            RobotFixture.obj(s.world, Obj.SHOT, mate.id, 710, 700, 0, 0);
            r.refreshCells();
            r.mineI = -1;
            r.mineDist = 216;
            r.itemDist = 1000;
            r.checkObjects(p, d);
            assertAll(
                () -> assertEquals(-1, r.mineI, "team immune"),
                () -> assertEquals(0, p.used & Has.SHIELD, "no shields"));
            W t = new W("");
            RobotFixture.obj(t.w, Obj.SHOT, t.p.id, 710, 700, 0, 0);
            t.w.rng.seed(RobotFixture.seedWithFirst(true, 0.91));
            t.objects();
            assertAll(
                () -> assertEquals(-1, t.r.mineI, "own shot: no danger"),
                () -> assertEquals(Has.SHIELD, t.p.used & Has.SHIELD, "but it can still hit: shields"));
        }

        @Test
        @DisplayName("items: the most important one within the item distance is remembered (random items handy)")
        void items() {
            W t = new W("");
            int far = RobotFixture.obj(t.w, Obj.ITEM, Obj.NO_ID, 900, 700, 0, 0);
            int near = RobotFixture.obj(t.w, Obj.ITEM, Obj.NO_ID, 800, 700, 0, 0);
            t.it.itemType = Item.SENSOR.ordinal();
            t.objects();
            int handy = t.r.itemI;
            int dist = t.r.itemDist;
            t.it.itemType = Item.AUTOPILOT.ordinal();
            t.objects();
            int ignored = t.r.itemI;
            t.it.random = true;
            t.objects();
            assertAll(
                () -> assertEquals(far, handy,
                    "the nearer item ranks best, but C turns item_i into the first unowned object (robotdef.c:2163)"),
                () -> assertEquals(100, dist, "100 px"),
                () -> assertEquals(-1, ignored, "autopilots are ignored"),
                () -> assertEquals(1, t.r.itemImp, "random items are handy"),
                () -> assertEquals(far, t.r.itemI, "and again the first unowned object"));
        }

        @Test
        @DisplayName("C's index mix-ups: mine_i is a cell-list position read as Obj[], item_i is re-found by owner id")
        void cIndexQuirks() {
            W t = new W("");
            int old = RobotFixture.obj(t.w, Obj.DEBRIS, Obj.NO_ID, 3000, 3000, 0, 0);
            int sh = RobotFixture.obj(t.w, Obj.SHOT, t.h.id, 730, 700, -10, 0);
            t.objects();
            int mine = t.r.mineI;
            int mineDist = t.r.mineDist;
            W u = new W("");
            int shot = RobotFixture.obj(u.w, Obj.SHOT, u.h.id, 3000, 3000, 0, 0);
            int item = RobotFixture.obj(u.w, Obj.ITEM, u.h.id, 800, 700, 0, 0);
            u.it.itemType = Item.SENSOR.ordinal();
            u.objects();
            assertAll(
                () -> assertEquals(old, mine, "the danger is first in the cell list, so mine_i = 0 and Obj[0] is read"),
                () -> assertNotEquals(sh, mine, "not the shot itself"),
                () -> assertEquals(20, mineDist, "the distance is the shot's"),
                () -> assertEquals(shot, u.r.itemI, "the first object owned by the item's owner"),
                () -> assertEquals(100, u.r.itemDist, "the distance is the item's"));
        }

        @Test
        @DisplayName("missiles and mines near trigger ECM; heat seekers stop the thrust and drop a tank between levels 1 and 3")
        void missiles() {
            W t = new W("");
            t.it.tanks = 1;
            RobotFixture.obj(t.w, Obj.SMART_SHOT, t.h.id, 790, 700, 0, 0);
            t.objects();
            List<String> smart = List.copyOf(t.it.calls);
            W u = new W("");
            u.it.tanks = 1;
            u.p.fuel = u.d.fuelL2;
            u.p.status |= Obj.THRUSTING;
            RobotFixture.obj(u.w, Obj.HEAT_SHOT, u.h.id, 800, 800, 0, 0);
            u.objects();
            assertAll(
                () -> assertEquals(List.of("ecm"), smart, "a smart missile 90 px away: ECM (< 100)"),
                () -> assertEquals(0, u.p.status & Obj.THRUSTING, "heat seeker: no thrust"),
                () -> assertEquals(List.of("detachTank"), u.it.calls, "fuel between levels: tank dropped"));
        }

        @Test
        @DisplayName("a missile in shield range with low fuel hyperjumps (and stops looking)")
        void hyperjump() {
            W t = new W("");
            t.it.with(Item.HYPERJUMP, 1);
            t.p.fuel = t.d.fuelL3 - 1;
            RobotFixture.obj(t.w, Obj.TORPEDO, t.h.id, 720, 700, 0, 0);
            RobotFixture.obj(t.w, Obj.SMART_SHOT, t.h.id, 750, 700, 0, 0);
            t.w.rng.seed(RobotFixture.seedWithFirst(true, 0.91));
            t.objects();
            assertEquals(List.of("hyperjump"), t.it.calls, "jumped before seeing the smart missile");
        }

        @Test
        @DisplayName("an asteroid 2-8 blocks ahead makes the robot shoot; a ball seen sets the connector")
        void asteroidBall() {
            W t = new W("");
            RobotFixture.obj(t.w, Obj.ASTEROID, Obj.NO_ID, 900, 700, 0, 0);
            t.objects();
            int shot = t.p.used & Has.SHOT;
            W u = new W("");
            RobotFixture.obj(u.w, Obj.ASTEROID, Obj.NO_ID, 760, 700, 0, 0);
            u.objects();
            W b = new W("");
            b.d.lastThrownBall = 10;
            b.d.robotCount = 200;
            RobotFixture.obj(b.w, Obj.BALL, Obj.NO_ID, 760, 700, 0, 0);
            b.objects();
            assertAll(
                () -> assertEquals(Has.SHOT, shot, "200 px ahead: shoot"),
                () -> assertEquals(0, u.p.used & Has.SHOT, "60 px is too close"),
                () -> assertEquals(Has.CONNECTOR, b.p.used & Has.CONNECTOR, "a ball: connector"));
        }

        @Test
        @DisplayName("objects beyond the visibility cells are not seen")
        void range() {
            W t = new W("");
            RobotFixture.obj(t.w, Obj.SHOT, t.h.id, 720, 700, 0, 0);
            t.r.visibilityDistance = 30;
            t.r.refreshCells();
            t.r.mineI = -1;
            t.r.mineDist = 216;
            t.r.itemDist = 30;
            t.r.checkObjects(t.p, t.d);
            int seen = t.r.mineI;
            RobotFixture.place(t.p, 1300, 700);
            t.r.mineI = -1;
            t.r.checkObjects(t.p, t.d);
            assertAll(
                () -> assertTrue(seen >= 0, "in the robot's own block"),
                () -> assertEquals(-1, t.r.mineI, "17 blocks away with a 0-block range"));
        }
    }

    @Nested
    @DisplayName("checkLasers (Robot_default_play_check_lasers, gated: pulses)")
    class Lasers {
        @Test
        @DisplayName("a hostile pulse close by, or aimed at us within two pulse lengths, raises shields")
        void pulses() {
            W t = new W("");
            t.it.pulses = 1;
            t.it.pulseOwner = t.h.id;
            t.it.pulseX = 740;
            t.it.pulseY = 700;
            int yes = RobotFixture.seedWithFirst(true, 0.91);
            t.w.rng.seed(yes);
            t.r.checkLasers(t.p, t.d);
            int close = t.p.used & Has.SHIELD;
            t.p.used &= ~Has.SHIELD;
            t.it.pulseX = 600;
            t.it.pulseDir = 0;
            t.w.rng.seed(yes);
            t.r.checkLasers(t.p, t.d);
            int aimed = t.p.used & Has.SHIELD;
            t.p.used &= ~Has.SHIELD;
            t.it.pulseDir = 64;
            t.r.checkLasers(t.p, t.d);
            assertAll(
                () -> assertEquals(Has.SHIELD, close, "40 px"),
                () -> assertEquals(Has.SHIELD, aimed, "100 px coming our way"),
                () -> assertEquals(0, t.p.used & Has.SHIELD, "100 px going away"));
        }

        @Test
        @DisplayName("own unreflected pulses, team mates and the already shielded or shieldless are skipped")
        void skips() {
            W t = new W("");
            t.it.pulses = 1;
            t.it.pulseOwner = t.p.id;
            t.it.pulseX = 710;
            t.it.pulseY = 700;
            t.w.rng.seed(RobotFixture.seedWithFirst(true, 0.91));
            t.r.checkLasers(t.p, t.d);
            int own = t.p.used & Has.SHIELD;
            t.it.reflected = true;
            t.w.rng.seed(RobotFixture.seedWithFirst(true, 0.91));
            t.r.checkLasers(t.p, t.d);
            int reflected = t.p.used & Has.SHIELD;
            W s = new W("selfImmunity: yes\n");
            s.it.pulses = 1;
            s.it.pulseOwner = s.p.id;
            s.it.reflected = true;
            s.it.pulseX = 710;
            s.it.pulseY = 700;
            s.w.rng.seed(RobotFixture.seedWithFirst(true, 0.91));
            s.r.checkLasers(s.p, s.d);
            int immune = s.p.used & Has.SHIELD;
            s.p.have &= ~Has.SHIELD;
            s.it.pulseOwner = s.h.id;
            s.r.checkLasers(s.p, s.d);
            assertAll(
                () -> assertEquals(0, own, "own pulse"),
                () -> assertEquals(Has.SHIELD, reflected, "own reflected pulse"),
                () -> assertEquals(0, immune, "own reflected pulse with selfImmunity"),
                () -> assertEquals(0, s.p.used & Has.SHIELD, "no shield ability"));
        }
    }

    @Nested
    @DisplayName("fuel, repair and locks")
    class Helpers {
        @Test
        @DisplayName("nearbyFuel: below 80%, the first station within 90 px with fuel is used")
        void nearbyFuel() {
            String[] rows = RobotFixture.open(40, 40, "___");
            char[] r = rows[39 - 20].toCharArray();
            r[21] = '#';
            rows[39 - 20] = new String(r);
            W t = new W("", rows);
            t.w.fuelLevel[0] = 400 * 256;
            t.p.fuel = t.p.fuelMax * 0.8;
            t.r.nearbyFuel(t.p);
            int full = t.p.used & Has.REFUEL;
            t.p.fuel = t.p.fuelMax * 0.8 - 1;
            t.r.nearbyFuel(t.p);
            int low = t.p.used & Has.REFUEL;
            int fs = t.p.fs;
            t.w.fuelLevel[0] = 5 * 256;
            t.r.nearbyFuel(t.p);
            int empty = t.p.used & Has.REFUEL;
            t.w.fuelLevel[0] = 400 * 256;
            RobotFixture.place(t.p, 700, 900);
            t.p.used |= Has.REFUEL;
            t.r.nearbyFuel(t.p);
            assertAll(
                () -> assertEquals(0, full, "almost full: no"),
                () -> assertEquals(Has.REFUEL, low, "62 px away"),
                () -> assertEquals(0, fs, "station 0"),
                () -> assertEquals(0, empty, "a station with no more than REFUEL_RATE: off"),
                () -> assertEquals(0, t.p.used & Has.REFUEL, "too far: off"));
        }

        @Test
        @DisplayName("nearbyTarget: an own damaged target within 90 px is repaired")
        void nearbyTarget() {
            String[] rows = RobotFixture.open(40, 40, "");
            char[] b = rows[39].toCharArray();
            b[1] = '1';
            rows[39] = new String(b);
            char[] r = rows[39 - 20].toCharArray();
            r[21] = '!';
            rows[39 - 20] = new String(r);
            Simulation s = RobotFixture.sim("teamPlay: yes\nreserveRobotTeam: no\n", rows);
            SpyItems it = new SpyItems();
            s.robots.setItems(it);
            Player p = s.ships.join("R", 1, true);
            RobotFixture.place(p, 700, 700);
            RobotData d = s.robots.data(p);
            s.robots.robot().nearbyTarget(p, d);
            int intact = p.used & Has.REPAIR;
            it.damage = 1;
            p.used &= ~Has.REPAIR;
            s.robots.robot().nearbyTarget(p, d);
            assertAll(
                () -> assertEquals(0, intact, "undamaged: no repair"),
                () -> assertEquals(Has.REPAIR, p.used & Has.REPAIR, "damaged: repair"),
                () -> assertEquals(0, d.repairTarget, "target 0"));
        }

        @Test
        @DisplayName("choosePlayerLock: lock when free, on a much nearer enemy, or a better scorer")
        void lock() {
            W t = new W("");
            Player g = RobotFixture.human(t.s, "G", 900, 700);
            t.r.choosePlayerLock(t.p, t.d, t.h, 700);
            int first = t.p.lockPlId;
            t.r.choosePlayerLock(t.p, t.d, g, 351);
            int notNear = t.p.lockPlId;
            t.r.choosePlayerLock(t.p, t.d, g, 349);
            int near = t.p.lockPlId;
            t.p.lockDistance = 100;
            t.h.score = 5;
            t.r.choosePlayerLock(t.p, t.d, t.h, 900);
            assertAll(
                () -> assertEquals(t.h.id, first, "free: lock"),
                () -> assertEquals(DefaultRobot.LOCK_PLAYER, t.p.lockTagged, "LOCK_PLAYER"),
                () -> assertEquals(t.h.id, notNear, "351 is not under half of 700"),
                () -> assertEquals(g.id, near, "349 is"),
                () -> assertEquals(t.h.id, t.p.lockPlId, "a higher score takes the lock"),
                () -> assertEquals(900, t.p.lockDistance, "lock distance = enemy distance"),
                () -> assertTrue(t.it.calls.contains("sensorRange"), "sensor range recomputed"));
        }

        @Test
        @DisplayName("choosePlayerLock: in team play a ball carrier within twice the lock distance is locked")
        void lockBall() {
            W t = new W("teamPlay: yes\nreserveRobotTeam: no\n");
            Player g = RobotFixture.human(t.s, "G", 900, 700);
            t.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            t.p.lockPlId = t.h.id;
            t.p.lockDistance = 100;
            g.have |= Has.BALL;
            t.r.choosePlayerLock(t.p, t.d, g, 199);
            assertEquals(g.id, t.p.lockPlId, "ball carrier");
        }

        @Test
        @DisplayName("releasePlayerLock: dropped when the target dies, hides, a war target plays, or fuel is low")
        void release() {
            W t = new W("");
            t.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            t.p.lockPlId = t.h.id;
            t.p.lockDistance = 50;
            t.r.releasePlayerLock(t.p, t.d);
            boolean kept = t.p.lockTagged == DefaultRobot.LOCK_PLAYER;
            t.p.fuel = t.d.fuelL3;
            t.r.releasePlayerLock(t.p, t.d);
            assertAll(
                () -> assertTrue(kept, "a visible playing target with fuel: kept"),
                () -> assertEquals(0, t.p.lockTagged, "fuel at level 3: dropped"),
                () -> assertEquals(1, t.p.lockPlId, "C sets pl_id 1"),
                () -> assertEquals(0.0, t.p.lockDistance, "distance 0"));
            W u = new W("");
            Player g = RobotFixture.human(u.s, "G", 900, 700);
            u.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            u.p.lockPlId = u.h.id;
            DefaultRobot.setWar(u.d, g.id);
            u.r.releasePlayerLock(u.p, u.d);
            int war = u.p.lockTagged;
            u.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            u.p.lockPlId = u.h.id;
            g.status &= ~Obj.PLAYING;
            u.r.releasePlayerLock(u.p, u.d);
            int warDead = u.p.lockTagged;
            u.h.status &= ~Obj.PLAYING;
            u.r.releasePlayerLock(u.p, u.d);
            int dead = u.p.lockTagged;
            u.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            u.p.lockPlId = 77;
            u.r.releasePlayerLock(u.p, u.d);
            assertAll(
                () -> assertEquals(0, war, "at war with another playing ship"),
                () -> assertEquals(DefaultRobot.LOCK_PLAYER, warDead, "the war target is dead: kept"),
                () -> assertEquals(0, dead, "the locked ship died"),
                () -> assertEquals(0, u.p.lockTagged, "the locked ship left"));
        }

        @Test
        @DisplayName("releasePlayerLock: race mode always drops (C's heading test is always true); team mates too")
        void releaseRace() {
            String[] rows = RobotFixture.open(40, 10, "__");
            char[] r = rows[2].toCharArray();
            r[20] = 'A';
            rows[2] = new String(r);
            Simulation race = RobotFixture.sim("timing: yes\n", rows);
            Player p = RobotFixture.robot(race, "R", 100, 100);
            Player h = RobotFixture.human(race, "H", 200, 100);
            RobotData d = race.robots.data(p);
            p.lockTagged = DefaultRobot.LOCK_PLAYER;
            p.lockPlId = h.id;
            race.robots.robot().releasePlayerLock(p, d);
            W t = new W("teamPlay: yes\nreserveRobotTeam: no\n");
            t.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            t.p.lockPlId = t.h.id;
            t.r.releasePlayerLock(t.p, t.d);
            assertAll(
                () -> assertEquals(0, p.lockTagged, "race: dropped"),
                () -> assertEquals(0, t.p.lockTagged, "same team (team 0 for both): dropped"));
        }
    }

    @Nested
    @DisplayName("climbOrIdle (end of Robot_default_play)")
    class Climb {
        @Test
        @DisplayName("first half of 64 counts: climb, turning toward straight up")
        void climb() {
            W t = new W("");
            t.d.robotCount = 31;
            t.p.dir = 0;
            t.r.climbOrIdle(t.p, t.d);
            double fromRight = t.p.turnAcc;
            int thrust = t.p.status & Obj.THRUSTING;
            t.p.dir = 50;
            t.r.climbOrIdle(t.p, t.d);
            double fromLeft = t.p.turnAcc;
            t.p.dir = 40;
            t.r.climbOrIdle(t.p, t.d);
            double near = t.p.turnAcc;
            t.p.dir = 100;
            t.r.climbOrIdle(t.p, t.d);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_ROBOT_CLIMB, t.d.robotMode, "CLIMB"),
                () -> assertEquals(32.0, fromRight, "from heading 0 turn left at 32"),
                () -> assertEquals(Obj.THRUSTING, thrust, "rising slower than 3: thrust"),
                () -> assertEquals(-32.0, fromLeft, "from 50 turn right"),
                () -> assertEquals(0.0, near, "within RES/16 of up: no turn"),
                () -> assertEquals(32.0, t.p.turnAcc, "from 100 (pointing down-right) turn left"),
                () -> assertEquals(27.5, t.p.power, "half power"));
        }

        @Test
        @DisplayName("climbing: stop above normal speed upwards; keep between")
        void climbThrust() {
            W t = new W("");
            t.d.robotCount = 31;
            RobotFixture.velocity(t.p, 0, 6.5);
            t.p.status |= Obj.THRUSTING;
            t.r.climbOrIdle(t.p, t.d);
            int above = t.p.status & Obj.THRUSTING;
            RobotFixture.velocity(t.p, 0, 4);
            t.p.status |= Obj.THRUSTING;
            t.r.climbOrIdle(t.p, t.d);
            int between = t.p.status & Obj.THRUSTING;
            RobotFixture.velocity(t.p, 20, 0);
            t.p.status &= ~Obj.THRUSTING;
            t.r.climbOrIdle(t.p, t.d);
            assertAll(
                () -> assertEquals(0, above, "6.5 up: stop"),
                () -> assertEquals(Obj.THRUSTING, between, "4 up: keep"),
                () -> assertEquals(0, t.p.status & Obj.THRUSTING, "faster than attack speed overall: no thrust"));
        }

        @Test
        @DisplayName("second half: idle, thrusting only below half the normal speed; falling fast always climbs")
        void idle() {
            W t = new W("");
            t.d.robotCount = 32;
            t.p.turnAcc = 9;
            t.r.climbOrIdle(t.p, t.d);
            int slow = t.p.status & Obj.THRUSTING;
            RobotFixture.velocity(t.p, 4, 0);
            t.r.climbOrIdle(t.p, t.d);
            int mid = t.p.status & Obj.THRUSTING;
            RobotFixture.velocity(t.p, 0, -6.5);
            t.r.climbOrIdle(t.p, t.d);
            assertAll(
                () -> assertEquals(Obj.THRUSTING, slow, "at rest: thrust"),
                () -> assertEquals(0, mid, "4 >= 3: off"),
                () -> assertEquals(DefaultRobot.RM_ROBOT_CLIMB, t.d.robotMode, "falling at 6.5: climb"));
            t.d.robotCount = 32;
            RobotFixture.velocity(t.p, 7, 0);
            t.r.climbOrIdle(t.p, t.d);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_ROBOT_IDLE, t.d.robotMode, "IDLE"),
                () -> assertEquals(0.0, t.p.turnAcc, "no turning"),
                () -> assertEquals(0, t.p.status & Obj.THRUSTING, "7 > 6: off"));
        }

        @Test
        @DisplayName("gravity counts double against the velocity")
        void gravity() {
            W t = new W("gravity: -2\n");
            t.d.robotCount = 32;
            RobotFixture.velocity(t.p, 0, -2);
            t.r.climbOrIdle(t.p, t.d);
            assertEquals(DefaultRobot.RM_ROBOT_IDLE, t.d.robotMode, "-2 - 2*(-2) = 2 up: idle");
        }
    }

    @Nested
    @DisplayName("play (Robot_default_play)")
    class Play {
        @Test
        @DisplayName("refills the count at 0 with 1000 + rfrac*32 and counts down; releases shots, shields, cloak, laser")
        void count() {
            W t = new W("");
            t.d.robotCount = 0;
            t.p.used |= Has.SHOT | Has.SHIELD | Has.LASER;
            t.w.rng.seed(8);
            t.r.play(t.p, t.d);
            assertAll(
                () -> assertEquals(1000 + (int) (RobotFixture.rfracs(8, 1)[0] * 32) - 1, t.d.robotCount, "refilled - 1"),
                () -> assertEquals(0, t.p.used & (Has.SHOT | Has.LASER), "released"));
        }

        @Test
        @DisplayName("gated abilities are switched on when the item gate grants them")
        void abilities() {
            W t = new W("");
            t.p.have |= Has.EMERGENCY_SHIELD | Has.EMERGENCY_THRUST | Has.DEFLECTOR | Has.CLOAKING_DEVICE;
            t.r.play(t.p, t.d);
            int cloak = t.p.used & Has.CLOAKING_DEVICE;
            W u = new W("");
            u.p.have |= Has.CLOAKING_DEVICE;
            u.p.fuel = u.d.fuelL2;
            u.p.used |= Has.EMERGENCY_SHIELD;
            u.r.play(u.p, u.d);
            assertAll(
                () -> assertEquals(List.of("emergencyShield", "emergencyThrust", "deflector"),
                    t.it.calls.subList(0, 3), "in C's order"),
                () -> assertTrue(cloak == Has.CLOAKING_DEVICE || (t.p.used & Has.SHIELD) != 0, "cloaked unless shielded"),
                () -> assertEquals(0, u.p.used & Has.CLOAKING_DEVICE, "fuel at level 2: no cloak"),
                () -> assertFalse(u.it.calls.contains("emergencyShield"), "already in use"));
        }

        @Test
        @DisplayName("the real item gate lets a robot with a cloak and fuel above level 2 cloak; without one it does not")
        void defaultGate() {
            Simulation s = RobotFixture.sim("", RobotFixture.open(40, 40, "___"));
            Player p = RobotFixture.robot(s, "R", 700, 700);
            s.robots.robot().play(p, s.robots.data(p));
            int without = p.used & Has.CLOAKING_DEVICE;
            p.have |= Has.CLOAKING_DEVICE;
            s.robots.robot().play(p, s.robots.data(p));
            assertAll(
                () -> assertEquals(0, without, "no cloaking device: not cloaked"),
                () -> assertEquals(Has.CLOAKING_DEVICE, p.used & Has.CLOAKING_DEVICE, "with one: cloaked"));
        }

        @Test
        @DisplayName("at fuel level 1 it self-destructs (count 150); above it the countdown stops")
        void selfDestruct() {
            W t = new W("");
            t.p.fuel = t.d.fuelL1;
            t.r.play(t.p, t.d);
            int sd = t.p.status & Obj.SELF_DESTRUCT;
            double count = t.p.count;
            t.p.count = 77;
            t.r.play(t.p, t.d);
            double kept = t.p.count;
            t.p.fuel = t.d.fuelL1 + 1;
            t.r.play(t.p, t.d);
            assertAll(
                () -> assertEquals(Obj.SELF_DESTRUCT, sd, "self destruct"),
                () -> assertEquals(150.0, count, "150 frames"),
                () -> assertEquals(77.0, kept, "an ongoing countdown is not restarted"),
                () -> assertEquals(0, t.p.status & Obj.SELF_DESTRUCT, "stopped"));
        }

        @Test
        @DisplayName("ECM-blinded: shields up and nothing else")
        void blinded() {
            W t = new W("");
            t.p.damaged = 5;
            t.p.turnAcc = 7;
            t.d.robotMode = 0;
            t.r.play(t.p, t.d);
            assertAll(
                () -> assertEquals(Has.SHIELD, t.p.used & Has.SHIELD, "shields"),
                () -> assertEquals(7.0, t.p.turnAcc, "no steering"),
                () -> assertEquals(0, t.d.robotMode, "no mode"));
        }

        @Test
        @DisplayName("NEED_FUEL below level 3; cleared again only when not refuelling")
        void needFuel() {
            W t = new W("");
            t.p.fuel = t.d.fuelL3 - 1;
            t.r.play(t.p, t.d);
            int set = t.d.longtermMode & DefaultRobot.NEED_FUEL;
            t.p.fuel = t.p.fuelMax;
            t.p.used |= Has.REFUEL;
            t.r.play(t.p, t.d);
            int refuelling = t.d.longtermMode & DefaultRobot.NEED_FUEL;
            t.p.used &= ~Has.REFUEL;
            t.r.play(t.p, t.d);
            assertAll(
                () -> assertEquals(DefaultRobot.NEED_FUEL, set, "set below level 3"),
                () -> assertEquals(DefaultRobot.NEED_FUEL, refuelling, "kept while refuelling"),
                () -> assertEquals(0, t.d.longtermMode & DefaultRobot.NEED_FUEL, "full and not refuelling: cleared"));
        }

        @Test
        @DisplayName("within a block of the home base corner it thrusts to take off")
        void takeOff() {
            W t = new W("");
            com.fulcrologic.jxpilot.common.map.Base b = t.w.map.bases().get(t.p.homeBase);
            RobotFixture.place(t.p, b.bx() * 35 + 10, b.by() * 35 + 20);
            t.p.dir = 32;
            t.d.robotCount = 33;
            t.r.play(t.p, t.d);
            assertEquals(Obj.THRUSTING, t.p.status & Obj.THRUSTING, "thrust (idle keeps it below speed 3)");
        }

        @Test
        @DisplayName("picks an enemy (count % 3 == 0 and count % 100 < attack), locks it and attacks it")
        void attack() {
            W t = new W("");
            RobotFixture.place(t.h, 900, 700);
            t.d.robotCount = 31;
            t.r.play(t.p, t.d);
            assertAll(
                () -> assertEquals(t.h.id, t.p.lockPlId, "locked on H (count 30 after the decrement)"),
                () -> assertEquals(DefaultRobot.LOCK_PLAYER, t.p.lockTagged & DefaultRobot.LOCK_PLAYER, "LOCK_PLAYER"),
                () -> assertEquals(DefaultRobot.RM_ATTACK, t.d.robotMode, "attacking"),
                () -> assertEquals(1, t.p.shots, "and firing (even count, clear path)"));
        }

        @Test
        @DisplayName("an aggression roll that fails picks no enemy; the robot climbs")
        void calm() {
            W t = new W("");
            RobotFixture.place(t.h, 900, 700);
            t.d.robotCount = 113;
            t.r.play(t.p, t.d);
            assertAll(
                () -> assertEquals(0, t.p.lockTagged, "112 % 3 != 0: no enemy picked"),
                () -> assertEquals(DefaultRobot.RM_ROBOT_IDLE, t.d.robotMode, "112 % 64 >= 32: idle"));
        }

        @Test
        @DisplayName("a ship closer than 3 ship sizes raises shields and is evaded")
        void closeShip() {
            W t = new W("");
            RobotFixture.place(t.h, 740, 710);
            t.d.robotCount = 82;
            t.r.play(t.p, t.d);
            assertAll(
                () -> assertEquals(Has.SHIELD, t.p.used & Has.SHIELD, "shields"),
                () -> assertTrue(t.d.robotMode == DefaultRobot.RM_EVADE_LEFT || t.d.robotMode == DefaultRobot.RM_EVADE_RIGHT,
                    "evading"));
        }

        @Test
        @DisplayName("at war with fuel exactly at level 3 and the target beyond visibility (not locked): hunt it, "
            + "with shields when players start shielded and cannot shield")
        void hunt() {
            W t = new W("allowShields: no\nplayerStartsShielded: yes\n");
            t.p.have |= Has.SHIELD;
            RobotFixture.place(t.h, 700, 1300);
            DefaultRobot.setWar(t.d, t.h.id);
            t.d.robotCount = 82;
            t.p.fuel = t.d.fuelL3;
            t.r.visibilityDistance = 500;
            t.r.play(t.p, t.d);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_ROBOT_IDLE, t.d.robotMode, "hunting sets IDLE"),
                () -> assertEquals(16.0, Math.abs(t.p.turnAcc), "turning at 64/4"),
                () -> assertEquals(Has.SHIELD, t.p.used & Has.SHIELD, "shielded"),
                () -> assertEquals(t.h.id, t.d.lockLastX == 700 ? t.h.id : -1, "the target's position remembered"));
        }

        @Test
        @DisplayName("a war target is not avoided; low fuel near another ship hyperjumps when possible")
        void hyperjumpAway() {
            W t = new W("");
            t.it.with(Item.HYPERJUMP, 1);
            RobotFixture.place(t.h, 900, 700);
            t.p.fuel = t.d.fuelL3;
            t.d.robotCount = 82;
            t.r.play(t.p, t.d);
            assertEquals(List.of("hyperjump"), t.it.calls, "jumped");
        }

        @Test
        @DisplayName("needing fuel with nothing else to do: heads for a station")
        void refuel() {
            String[] rows = RobotFixture.open(40, 40, "___");
            char[] r = rows[39 - 20].toCharArray();
            r[30] = '#';
            rows[39 - 20] = new String(r);
            W t = new W("", rows);
            t.w.fuelLevel[0] = 400 * 256;
            t.p.fuel = t.d.fuelL3 - 1;
            t.d.robotCount = 82;
            t.r.play(t.p, t.d);
            assertAll(
                () -> assertEquals(DefaultRobot.RM_REFUEL, t.d.robotMode, "REFUEL"),
                () -> assertEquals(Has.REFUEL, t.p.used & Has.REFUEL, "refuelling on"));
        }
    }
}
