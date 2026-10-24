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
import com.fulcrologic.jxpilot.server.world.Mods;
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

/** DefaultRobot (robotdef.c): Check_robot_target, its thrust rules, weapons and Choose_weapon_modifier. */
class DefaultRobotTargetTest {

    /** A 40x40 open world, one robot at (700, 700) at rest heading 0, the frame clock past the fire delay. */
    static final class W {
        final Simulation s;
        final World w;
        final Player p;
        final RobotData d;
        final DefaultRobot r;

        W(String extra, int wallX, int wallY) {
            String[] rows = RobotFixture.open(40, 40, "___");
            if (wallX >= 0) {
                char[] row = rows[39 - wallY].toCharArray();
                row[wallX] = 'x';
                rows[39 - wallY] = new String(row);
            }
            s = RobotFixture.sim(extra, rows);
            w = s.world;
            p = RobotFixture.robot(s, "R", 700, 700);
            d = s.robots.data(p);
            r = s.robots.robot();
            p.dir = 0;
            w.frames = 100;
        }

        W() {
            this("", -1, 0);
        }

        W spy(SpyItems items) {
            s.robots.setItems(items);
            return new W(this, s.robots.robot());
        }

        private W(W o, DefaultRobot r2) {
            s = o.s;
            w = o.w;
            p = o.p;
            d = o.d;
            r = r2;
        }
    }

    @Nested
    @DisplayName("path and mode")
    class Path {
        @Test
        @DisplayName("a blocked path fails without changing anything (except in NAVIGATE)")
        void blocked() {
            W t = new W("", 22, 20);
            t.p.turnSpeed = 10;
            t.p.power = 20;
            boolean ok = t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertFalse(ok, "the wall at block 22 blocks the way to x 900"),
                () -> assertEquals(10, t.p.turnSpeed, "turnspeed untouched"),
                () -> assertEquals(20, t.p.power, "power untouched"),
                () -> assertEquals(DefaultRobot.RM_ROBOT_IDLE, t.d.robotMode, "mode untouched"),
                () -> assertEquals(DefaultRobot.RM_ROBOT_IDLE, t.d.robotMode, "mode untouched"));
            assertTrue(t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_NAVIGATE), "NAVIGATE goes on");
        }

        @Test
        @DisplayName("ATTACK straight ahead: no turning, thrust to the normal band, fire a shot, mode ATTACK")
        void attackAhead() {
            W t = new W();
            t.d.robotCount = 500;
            t.w.rng.seed(3);
            boolean ok = t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertTrue(ok, "clear path"),
                () -> assertEquals(27.5, t.p.power, "half power outside race mode"),
                () -> assertEquals(0.0, t.p.turnSpeed, "within RES/64 of the heading: turnspeed 0"),
                () -> assertEquals(0.0, Math.abs(t.p.turnAcc), "no turning"),
                () -> assertEquals(Obj.THRUSTING, t.p.status & Obj.THRUSTING, "below 2*normal (200 < 8 blocks): thrust"),
                () -> assertEquals(1, t.p.shots, "fired a shot (even count, clear path)"),
                () -> assertEquals(DefaultRobot.RM_ATTACK, t.d.robotMode, "mode ATTACK"),
                () -> assertEquals(RobotFixture.rfracs(3, 3)[2], t.w.rfrac(),
                    "two rfrac: the missile chance and the spread (count % 4 == 0)"));
        }

        @Test
        @DisplayName("an item more than a quarter turn from the travel direction: turn hard and slow down")
        void sideways() {
            W t = new W();
            t.d.robotCount = 501;
            boolean ok = t.r.checkTarget(t.p, t.d, 700, 900, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertTrue(ok, "clear"),
                () -> assertEquals(64.0, t.p.turnSpeed, "maximum turnspeed"),
                () -> assertEquals(64.0, t.p.turnAcc, "turn left toward heading 32"),
                () -> assertEquals(Obj.THRUSTING, t.p.status & Obj.THRUSTING, "slowing: thrust"),
                () -> assertEquals(0, t.p.shots, "odd count: no shot"));
        }

        @Test
        @DisplayName("HARVEST (and NAVIGATE) reverse the travel direction by 5/8 of a turn")
        void harvestReverse() {
            W t = new W();
            RobotFixture.velocity(t.p, 10, 0);
            t.r.checkTarget(t.p, t.d, 600, 800, DefaultRobot.RM_HARVEST);
            double harvest = t.p.turnAcc;
            W u = new W();
            RobotFixture.velocity(u.p, 10, 0);
            u.p.dir = 72;
            u.r.checkTarget(u.p, u.d, 600, 800, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(-64.0, harvest, "heading 80 (0 + 5/8 turn) from heading 0: turn right"),
                () -> assertEquals(DefaultRobot.RM_HARVEST, t.d.robotMode, "mode HARVEST"),
                () -> assertEquals(-64.0, u.p.turnAcc, "ATTACK keeps the item direction 48: from 72 turn right"));
            W v = new W();
            RobotFixture.velocity(v.p, 10, 0);
            v.p.dir = 72;
            v.r.checkTarget(v.p, v.d, 600, 800, DefaultRobot.RM_HARVEST);
            assertEquals(64.0, v.p.turnAcc, "HARVEST wants 80: from 72 turn left");
        }

        @Test
        @DisplayName("turning stops within RES/64 of the wanted heading")
        void deadBand() {
            W t = new W();
            t.p.dir = 2;
            t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            double at2 = t.p.turnSpeed;
            t.p.dir = 3;
            t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            double acc3 = t.p.turnAcc;
            t.p.dir = 126;
            t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            double at126 = t.p.turnSpeed;
            t.p.dir = 125;
            t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(0.0, at2, "2 off: no turning"),
                () -> assertEquals(-64.0, acc3, "3 off to the left: turn right at 64"),
                () -> assertEquals(0.0, at126, "2 off the other way"),
                () -> assertEquals(64.0, t.p.turnAcc, "3 off to the right: turn left"));
        }

        @Test
        @DisplayName("REFUEL aims 90 px short: inside that it stops thrusting")
        void refuel() {
            W t = new W();
            t.p.status |= Obj.THRUSTING;
            t.r.checkTarget(t.p, t.d, 780, 700, DefaultRobot.RM_REFUEL);
            int inside = t.p.status & Obj.THRUSTING;
            t.r.checkTarget(t.p, t.d, 800, 700, DefaultRobot.RM_REFUEL);
            assertAll(
                () -> assertEquals(0, inside, "80 px: distance -10 < 0, no thrust"),
                () -> assertEquals(Obj.THRUSTING, t.p.status & Obj.THRUSTING, "100 px: 10 < 3 blocks and slow: thrust"),
                () -> assertEquals(DefaultRobot.RM_REFUEL, t.d.robotMode, "mode REFUEL"));
        }

        @Test
        @DisplayName("CANNON_KILL aims 4 blocks short; closer it turns away, further it fires")
        void cannon() {
            W t = new W();
            t.d.robotCount = 502;
            t.r.checkTarget(t.p, t.d, 800, 700, DefaultRobot.RM_CANNON_KILL);
            assertAll(
                () -> assertEquals(-64.0, t.p.turnAcc, "100 - 140 <= 0: turn half around"),
                () -> assertEquals(64.0, t.p.turnSpeed, "hard"),
                () -> assertEquals(Obj.THRUSTING, t.p.status & Obj.THRUSTING, "slowing"),
                () -> assertEquals(0, t.p.shots, "no shot while slowing"));
            t.p.status &= ~Obj.THRUSTING;
            t.r.checkTarget(t.p, t.d, 1000, 700, DefaultRobot.RM_CANNON_KILL);
            assertAll(
                () -> assertEquals(1, t.p.shots, "fires at a cannon 300 px away (even count, clear)"),
                () -> assertEquals(Obj.THRUSTING, t.p.status & Obj.THRUSTING, "normal band: thrust"),
                () -> assertEquals(DefaultRobot.RM_CANNON_KILL, t.d.robotMode, "mode"));
            t.d.robotCount = 503;
            t.r.checkTarget(t.p, t.d, 1000, 700, DefaultRobot.RM_CANNON_KILL);
            assertEquals(1, t.p.shots, "odd count: no further shot");
        }

        @Test
        @DisplayName("NAVIGATE into a wall: 3/4 turn away at 4-8 blocks, half a turn nearer")
        void navigateBlocked() {
            W far = new W("", 26, 20);
            RobotFixture.velocity(far.p, 10, 0);
            boolean ok = far.r.checkTarget(far.p, far.d, 1400, 700, DefaultRobot.RM_NAVIGATE);
            W near = new W("", 23, 20);
            RobotFixture.velocity(near.p, 10, 0);
            near.r.checkTarget(near.p, near.d, 1400, 700, DefaultRobot.RM_NAVIGATE);
            assertAll(
                () -> assertTrue(ok, "NAVIGATE succeeds with a blocked path"),
                () -> assertEquals(238, far.r.lastScanDist, "the scan stopped one step past the wall at 221"),
                () -> assertEquals(-64.0, far.p.turnAcc, "wants heading 96: turn right"),
                () -> assertEquals(Obj.THRUSTING, far.p.status & Obj.THRUSTING, "slowing"),
                () -> assertEquals(136, near.r.lastScanDist, "wall at 119"),
                () -> assertEquals(-64.0, near.p.turnAcc, "wants heading 64: turn right"));
        }

        @Test
        @DisplayName("an item on the robot itself lies opposite the gravity")
        void onTop() {
            W t = new W("gravity: -0.5\n", -1, 0);
            t.p.dir = 32;
            t.r.checkTarget(t.p, t.d, 700, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(0.0, t.p.turnSpeed, "the item is straight up (heading 32)"),
                () -> assertEquals(Obj.THRUSTING, t.p.status & Obj.THRUSTING, "travel (gravity, 96) is half a turn off"));
        }

        @Test
        @DisplayName("race mode flies at full power")
        void race() {
            Simulation s = RobotFixture.sim("timing: yes\n", "          ", "  A       ", "          ", " _        ");
            Player p = RobotFixture.robot(s, "R", 50, 50);
            s.robots.robot().checkTarget(p, s.robots.data(p), 200, 50, DefaultRobot.RM_NAVIGATE);
            assertEquals(55.0, p.power, "power 55");
        }
    }

    @Nested
    @DisplayName("thrust rules")
    class Thrust {
        private int thrust(int itemX, int mode, double v, boolean shield) {
            W t = new W();
            RobotFixture.velocity(t.p, v, 0);
            if (shield) {
                t.p.used |= Has.SHIELD;
            }
            t.p.status &= ~Obj.THRUSTING;
            t.r.checkTarget(t.p, t.d, itemX, 700, mode);
            return t.p.status & Obj.THRUSTING;
        }

        private int thrustFrom(boolean on, int itemX, int mode, double v) {
            W t = new W();
            RobotFixture.velocity(t.p, v, 0);
            if (on) {
                t.p.status |= Obj.THRUSTING;
            }
            t.r.checkTarget(t.p, t.d, itemX, 700, mode);
            return t.p.status & Obj.THRUSTING;
        }

        @Test
        @DisplayName("near (< 3 blocks): normal/2..normal; HARVEST near uses the 2..3 normal band")
        void near() {
            assertAll(
                () -> assertEquals(Obj.THRUSTING, thrust(800, DefaultRobot.RM_ATTACK, 2.9, false), "slower than 3: thrust"),
                () -> assertEquals(0, thrustFrom(true, 800, DefaultRobot.RM_ATTACK, 6.1), "faster than 6: stop"),
                () -> assertEquals(Obj.THRUSTING, thrustFrom(true, 800, DefaultRobot.RM_ATTACK, 5), "between: keep"),
                () -> assertEquals(0, thrustFrom(false, 800, DefaultRobot.RM_ATTACK, 5), "between: keep off"),
                () -> assertEquals(Obj.THRUSTING, thrust(800, DefaultRobot.RM_HARVEST, 11.9, false), "HARVEST: below 12"),
                () -> assertEquals(0, thrustFrom(true, 800, DefaultRobot.RM_HARVEST, 18.1), "HARVEST: above 18"),
                () -> assertEquals(Obj.THRUSTING, thrust(800, DefaultRobot.RM_ATTACK, 25, true), "shields up: thrust"));
        }

        @Test
        @DisplayName("ATTACK beyond 8 blocks: attack/2..attack")
        void attackFar() {
            assertAll(
                () -> assertEquals(Obj.THRUSTING, thrust(1000, DefaultRobot.RM_ATTACK, 8.4, false), "below 8.5"),
                () -> assertEquals(0, thrustFrom(true, 1000, DefaultRobot.RM_ATTACK, 17.1), "above 17"),
                () -> assertEquals(Obj.THRUSTING, thrustFrom(true, 1000, DefaultRobot.RM_ATTACK, 12), "keep"),
                () -> assertEquals(Obj.THRUSTING, thrust(900, DefaultRobot.RM_ATTACK, 11.9, false), "within 8 blocks: below 12"),
                () -> assertEquals(0, thrustFrom(true, 900, DefaultRobot.RM_ATTACK, 18.1), "within 8 blocks: above 18"));
        }

        @Test
        @DisplayName("NAVIGATE: attack band under 12 blocks, max band beyond 18, attack..max-normal between")
        void navigate() {
            assertAll(
                () -> assertEquals(Obj.THRUSTING, thrust(1000, DefaultRobot.RM_NAVIGATE, 8.4, false), "300 px: attack band"),
                () -> assertEquals(0, thrustFrom(true, 1000, DefaultRobot.RM_NAVIGATE, 17.1), "300 px: above 17"),
                () -> assertEquals(Obj.THRUSTING, thrust(1200, DefaultRobot.RM_NAVIGATE, 16.9, false), "500 px: below 17"),
                () -> assertEquals(0, thrustFrom(true, 1200, DefaultRobot.RM_NAVIGATE, 24.1), "500 px: above 24"),
                () -> assertEquals(Obj.THRUSTING, thrust(1400, DefaultRobot.RM_NAVIGATE, 23.9, false), "700 px: below 24"),
                () -> assertEquals(0, thrustFrom(true, 1400, DefaultRobot.RM_NAVIGATE, 30.1), "700 px: above 30"),
                () -> assertEquals(Obj.THRUSTING, thrustFrom(true, 1400, DefaultRobot.RM_NAVIGATE, 27), "700 px: keep"));
        }

        @Test
        @DisplayName("NAVIGATE turned 3/8..5/8 away from the item uses the normal band")
        void navigateTurned() {
            W t = new W();
            RobotFixture.velocity(t.p, 13, 0);
            t.p.dir = 64;
            t.p.status |= Obj.THRUSTING;
            t.r.checkTarget(t.p, t.d, 1400, 700, DefaultRobot.RM_NAVIGATE);
            assertEquals(Obj.THRUSTING, t.p.status & Obj.THRUSTING, "13 is inside 12..18");
            RobotFixture.velocity(t.p, 18.5, 0);
            t.r.checkTarget(t.p, t.d, 1400, 700, DefaultRobot.RM_NAVIGATE);
            assertEquals(0, t.p.status & Obj.THRUSTING, "18.5 > 3*normal: stop");
        }

        @Test
        @DisplayName("thrustBand: on below low, off above high, unchanged between")
        void band() {
            Player p = new Player(0);
            DefaultRobot.thrustBand(p, 1, 2, 4);
            int below = p.status;
            DefaultRobot.thrustBand(p, 3, 2, 4);
            int inside = p.status;
            DefaultRobot.thrustBand(p, 5, 2, 4);
            assertAll(
                () -> assertEquals(Obj.THRUSTING, below, "below"),
                () -> assertEquals(Obj.THRUSTING, inside, "unchanged"),
                () -> assertEquals(0, p.status, "above"));
        }
    }

    @Nested
    @DisplayName("weapons (gated items)")
    class Weapons {
        @Test
        @DisplayName("ECM within 100 px, else transporter within 200 px with fuel")
        void ecmTransporter() {
            SpyItems a = new SpyItems().with(Item.ECM, 1).with(Item.TRANSPORTER, 1);
            W t = new W().spy(a);
            t.d.robotCount = 501;
            t.r.checkTarget(t.p, t.d, 790, 700, DefaultRobot.RM_ATTACK);
            t.r.checkTarget(t.p, t.d, 810, 700, DefaultRobot.RM_ATTACK);
            SpyItems b = new SpyItems().with(Item.TRANSPORTER, 1);
            W u = new W().spy(b);
            u.d.robotCount = 501;
            u.r.checkTarget(u.p, u.d, 899, 700, DefaultRobot.RM_ATTACK);
            u.r.checkTarget(u.p, u.d, 900, 700, DefaultRobot.RM_ATTACK);
            u.p.fuel = 60 * 256;
            u.r.checkTarget(u.p, u.d, 800, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(List.of("ecm", "transporter"), a.calls, "ECM at 90, transporter at 110"),
                () -> assertEquals(List.of("transporter"), b.calls, "199 px yes, 200 no, no fuel no"));
        }

        @Test
        @DisplayName("laser: fires when the target is in range and between its sides; then no turning")
        void laser() {
            SpyItems it = new SpyItems().with(Item.LASER, 2);
            W t = new W().spy(it);
            Player h = RobotFixture.human(t.s, "H", 800, 700);
            DefaultRobot.setWar(t.d, h.id);
            t.d.robotCount = 501;
            t.r.checkTarget(t.p, t.d, 800, 700, DefaultRobot.RM_ATTACK);
            int used = t.p.used & Has.LASER;
            double acc = t.p.turnAcc;
            t.p.used &= ~Has.LASER;
            t.p.dir = 10;
            t.r.checkTarget(t.p, t.d, 800, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(Has.LASER, used, "aligned: laser on"),
                () -> assertEquals(0.0, acc, "no turning while firing"),
                () -> assertEquals(0, t.p.used & Has.LASER, "heading 10 misses"));
        }

        @Test
        @DisplayName("aimLaser uses the player lock without war, and needs a playing target in pulse range")
        void laserTargets() {
            SpyItems it = new SpyItems().with(Item.LASER, 2);
            W t = new W().spy(it);
            Player h = RobotFixture.human(t.s, "H", 800, 700);
            t.r.aimLaser(t.p, t.d);
            int noTarget = t.p.used & Has.LASER;
            t.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            t.p.lockPlId = h.id;
            t.r.aimLaser(t.p, t.d);
            int locked = t.p.used & Has.LASER;
            t.p.used &= ~Has.LASER;
            RobotFixture.place(h, 1200, 700);
            t.r.aimLaser(t.p, t.d);
            int far = t.p.used & Has.LASER;
            RobotFixture.place(h, 800, 700);
            h.status &= ~Obj.PLAYING;
            t.r.aimLaser(t.p, t.d);
            assertAll(
                () -> assertEquals(0, noTarget, "no war, no lock: nothing"),
                () -> assertEquals(Has.LASER, locked, "the locked player"),
                () -> assertEquals(0, far, "485 px is beyond 90 * 5 + 16"),
                () -> assertEquals(0, t.p.used & Has.LASER, "a dead target"));
        }

        @Test
        @DisplayName("tractor beam: pressor on a close ship, pull on one moving away, only at normal speed")
        void tractor() {
            SpyItems it = new SpyItems();
            W t = new W().spy(it);
            Player h = RobotFixture.human(t.s, "H", 750, 700);
            t.p.have |= Has.TRACTOR_BEAM;
            t.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            t.p.lockPlId = h.id;
            t.p.lockDistance = 50;
            t.d.robotCount = 501;
            t.r.checkTarget(t.p, t.d, 750, 700, DefaultRobot.RM_ATTACK);
            int close = t.p.used & Has.TRACTOR_BEAM;
            boolean pressor = t.p.tractorIsPressor;
            int visible = t.p.lockTagged & DefaultRobot.LOCK_VISIBLE;
            RobotFixture.place(h, 850, 700);
            RobotFixture.velocity(h, 10, 0);
            t.p.lockDistance = 150;
            t.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            t.r.tractorBeam(t.p, t.d);
            int pull = t.p.used & Has.TRACTOR_BEAM;
            boolean pullPressor = t.p.tractorIsPressor;
            RobotFixture.velocity(t.p, 7, 0);
            t.r.tractorBeam(t.p, t.d);
            int fast = t.p.used & Has.TRACTOR_BEAM;
            RobotFixture.velocity(t.p, 0, 0);
            t.p.lockDistance = 200;
            t.r.tractorBeam(t.p, t.d);
            assertAll(
                () -> assertEquals(Has.TRACTOR_BEAM, close, "50 px: beam on"),
                () -> assertTrue(pressor, "as a pressor"),
                () -> assertEquals(DefaultRobot.LOCK_VISIBLE, visible, "the lock becomes visible"),
                () -> assertEquals(Has.TRACTOR_BEAM, pull, "moving away at 10 (6..30): pull"),
                () -> assertFalse(pullPressor, "not a pressor"),
                () -> assertEquals(0, fast, "the robot above normal speed: off"),
                () -> assertEquals(0, t.p.used & Has.TRACTOR_BEAM, "out of range (200 + 0 items)"));
        }

        @Test
        @DisplayName("tractor beam: a ship closing faster than attack speed is pushed; a slow one is left")
        void tractorClosing() {
            SpyItems it = new SpyItems();
            W t = new W().spy(it);
            Player h = RobotFixture.human(t.s, "H", 850, 700);
            t.p.lockTagged = DefaultRobot.LOCK_PLAYER;
            t.p.lockPlId = h.id;
            t.p.lockDistance = 150;
            RobotFixture.velocity(h, -18, 0);
            t.r.tractorBeam(t.p, t.d);
            int closing = t.p.used & Has.TRACTOR_BEAM;
            boolean pressor = t.p.tractorIsPressor;
            RobotFixture.velocity(h, -5, 0);
            t.r.tractorBeam(t.p, t.d);
            int slow = t.p.used & Has.TRACTOR_BEAM;
            t.p.fuel = t.d.fuelL3;
            RobotFixture.velocity(h, -18, 0);
            t.r.tractorBeam(t.p, t.d);
            assertAll(
                () -> assertEquals(Has.TRACTOR_BEAM, closing, "closing at 18 > 17"),
                () -> assertTrue(pressor, "as a pressor"),
                () -> assertEquals(0, slow, "closing slowly"),
                () -> assertEquals(0, t.p.used & Has.TRACTOR_BEAM, "fuel at level 3 is not enough"));
        }

        @Test
        @DisplayName("missiles: smart/heat/torpedo by count % 5 when count % 10 is below the stock, 10 counts apart")
        void missiles() {
            SpyItems it = new SpyItems().with(Item.MISSILE, 5);
            W t = new W().spy(it);
            t.d.lastFiredMissile = 100;
            t.d.robotCount = 500;
            t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            t.d.robotCount = 483;
            t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            int thrustAfterHeat = t.p.status & Obj.THRUSTING;
            t.d.robotCount = 464;
            t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            int thrustAfterTorpedo = t.p.status & Obj.THRUSTING;
            t.d.robotCount = 455;
            t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            t.d.robotCount = 488;
            t.d.lastFiredMissile = 100;
            t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(List.of("missile:" + Obj.SMART_SHOT + ":0", "missile:" + Obj.HEAT_SHOT + ":0",
                    "missile:" + Obj.TORPEDO + ":0"), it.calls, "500 smart, 483 heat, 464 torpedo; 455 within 10 "
                    + "of 464 and 488 (count % 10 = 8 >= 5) fire none"),
                () -> assertEquals(0, thrustAfterHeat, "a heat seeker stops the thrust"),
                () -> assertEquals(Obj.THRUSTING, thrustAfterTorpedo, "a torpedo does not"),
                () -> assertEquals(100, t.d.lastFiredMissile, "no missile on 488"));
        }

        @Test
        @DisplayName("heat seekers stop the thrust; an unseen locked target or no smart missiles give heat seekers")
        void heat() {
            SpyItems it = new SpyItems().with(Item.MISSILE, 5);
            it.see = false;
            W t = new W("allowSmartMissiles: no\n", -1, 0).spy(it);
            Player h = RobotFixture.human(t.s, "H", 800, 700);
            h.used |= Has.SHOT;
            t.p.lockPlId = h.id;
            t.d.robotCount = 500;
            t.d.lastFiredMissile = 100;
            t.p.status |= Obj.THRUSTING;
            t.r.checkTarget(t.p, t.d, 1000, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(List.of("missile:" + Obj.HEAT_SHOT + ":0"), it.calls, "smart turned heat"),
                () -> assertEquals(0, t.p.status & Obj.THRUSTING, "heat seeker: thrust off"),
                () -> assertEquals(500, t.d.lastFiredMissile, "remembered"));
        }

        @Test
        @DisplayName("a random smart missile on even counts when rfrac*64 is below the missile count")
        void randomSmart() {
            SpyItems it = new SpyItems().with(Item.MISSILE, 64);
            W t = new W().spy(it);
            t.d.robotCount = 506;
            t.d.lastFiredMissile = 510;
            t.r.checkTarget(t.p, t.d, 900, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(List.of("missile:" + Obj.SMART_SHOT + ":0"), it.calls, "a smart missile"),
                () -> assertEquals(0, t.p.shots, "instead of a shot"),
                () -> assertEquals(506, t.d.lastFiredMissile, "remembered"));
        }

        @Test
        @DisplayName("mines: dropped by count when not within 10 counts of the last; low fuel drops the cloak")
        void mines() {
            SpyItems it = new SpyItems().with(Item.MINE, 31);
            W t = new W().spy(it);
            t.d.robotCount = 503;
            t.d.lastDroppedMine = 100;
            t.r.checkTarget(t.p, t.d, 1100, 700, DefaultRobot.RM_ATTACK);
            int afterFirst = t.d.lastDroppedMine;
            t.d.robotCount = 497;
            t.r.checkTarget(t.p, t.d, 1100, 700, DefaultRobot.RM_ATTACK);
            t.d.robotCount = 491;
            t.p.fuel = t.d.fuelL3;
            t.p.used |= Has.CLOAKING_DEVICE;
            t.r.checkTarget(t.p, t.d, 1100, 700, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(503, afterFirst, "dropped at 503"),
                () -> assertEquals(List.of("mine", "mine"), it.calls, "497 is within 10 of 503: skipped; 491 drops"),
                () -> assertEquals(0, t.p.used & Has.CLOAKING_DEVICE, "low fuel: cloak off"));
        }

        @Test
        @DisplayName("a mine is dropped when slowing near the item; the robot then evades a random side")
        void slowingMine() {
            SpyItems it = new SpyItems().with(Item.MINE, 3);
            W t = new W().spy(it);
            t.d.robotCount = 501;
            int low = RobotFixture.seedWithFirst(true, 0.5);
            t.w.rng.seed(low);
            t.r.checkTarget(t.p, t.d, 700, 900, DefaultRobot.RM_ATTACK);
            int right = t.d.robotMode;
            int high = RobotFixture.seedWithFirst(false, 0.5);
            t.w.rng.seed(high);
            t.r.checkTarget(t.p, t.d, 700, 900, DefaultRobot.RM_ATTACK);
            t.r.checkTarget(t.p, t.d, 700, 1000, DefaultRobot.RM_ATTACK);
            assertAll(
                () -> assertEquals(List.of("movingMine", "movingMine"), it.calls, "moving mines outside race mode; none at 300 px"),
                () -> assertEquals(DefaultRobot.RM_EVADE_RIGHT, right, "rfrac < 0.5: evade right"),
                () -> assertEquals(DefaultRobot.RM_ATTACK, t.d.robotMode, "last call (no mine) attacks"));
        }
    }

    @Nested
    @DisplayName("chooseWeaponModifier (Choose_weapon_modifier)")
    class Modifiers {
        private W mods(String extra) {
            return new W("allowNukes: yes\nallowClusters: yes\nallowModifiers: yes\nallowLaserModifiers: yes\n" + extra,
                -1, 0);
        }

        @Test
        @DisplayName("laser and shots pick a random setting every 4th count; the tractor beam clears all")
        void simple() {
            W t = mods("");
            t.d.robotCount = 8;
            t.w.rng.seed(2);
            double[] f = RobotFixture.rfracs(2, 2);
            t.r.chooseWeaponModifier(t.p, t.d, Has.LASER);
            int laser = Mods.laser(t.p.mods);
            t.r.chooseWeaponModifier(t.p, t.d, Obj.SHOT);
            int spread = Mods.spread(t.p.mods);
            t.r.chooseWeaponModifier(t.p, t.d, Has.TRACTOR_BEAM);
            int afterTractor = Mods.spread(t.p.mods);
            t.d.robotCount = 9;
            t.p.mods = Mods.withLaser(t.p.mods, 5);
            t.r.chooseWeaponModifier(t.p, t.d, Has.LASER);
            assertAll(
                () -> assertEquals((int) (f[0] * 3), laser, "laser 0..2"),
                () -> assertEquals((int) (f[1] * 4), spread, "spread 0..3"),
                () -> assertEquals(0, afterTractor, "tractor clears"),
                () -> assertEquals(0, Mods.laser(t.p.mods), "count 9: laser 0"));
        }

        @Test
        @DisplayName("mines and missiles: nuclear by attack, full nuclear by stock; cluster/implosion by fuel; mini every 10th")
        void nuclear() {
            SpyItems it = new SpyItems().with(Item.MINE, 5).with(Item.MISSILE, 7);
            W t = mods("").spy(it);
            t.d.attack = 50;
            t.d.robotCount = 40;
            t.w.rng.seed(4);
            double[] f = RobotFixture.rfracs(4, 4);
            t.r.chooseWeaponModifier(t.p, t.d, Obj.MINE);
            assertAll(
                () -> assertEquals(DefaultRobot.NUCLEAR | DefaultRobot.FULLNUCLEAR, Mods.nuclear(t.p.mods),
                    "40 % 100 <= 50; 5 > 4 mines and < 8: full nuclear"),
                () -> assertEquals(DefaultRobot.CLUSTER, Mods.warhead(t.p.mods), "fuel above level 3, even count: cluster"),
                () -> assertEquals((int) (f[0] * 3) + 1, Mods.velocity(t.p.mods), "every 8th: velocity 1..3"),
                () -> assertEquals((int) (f[1] * 4), Mods.mini(t.p.mods), "every 10th: mini"),
                () -> assertEquals((int) (f[2] * 4), Mods.spread(t.p.mods), "and spread"));
            t.d.robotCount = 63;
            t.p.fuel = t.d.fuelL3;
            t.r.chooseWeaponModifier(t.p, t.d, Obj.HEAT_SHOT);
            assertAll(
                () -> assertEquals(0, Mods.nuclear(t.p.mods), "63 % 100 > 50: no nuke"),
                () -> assertEquals(DefaultRobot.IMPLOSION, Mods.warhead(t.p.mods), "low fuel, count % 3 == 0: implosion"),
                () -> assertEquals(0, Mods.power(t.p.mods), "no power pick on 63"));
            t.d.robotCount = 4;
            t.w.rng.seed(4);
            t.r.chooseWeaponModifier(t.p, t.d, Obj.SMART_SHOT);
            assertAll(
                () -> assertEquals((int) (f[0] * 4), Mods.power(t.p.mods), "count % 4 == 0: power"),
                () -> assertEquals(DefaultRobot.NUCLEAR, Mods.nuclear(t.p.mods), "7 smarts = the minimum: nuclear, not full"),
                () -> assertEquals(0, Mods.warhead(t.p.mods), "low fuel, 4 % 3 != 0: no warhead"));
        }

        @Test
        @DisplayName("full nuclear needs more than the minimum and (less than twice it or an even count)")
        void fullNuclear() {
            SpyItems it = new SpyItems().with(Item.MINE, 8);
            W t = mods("").spy(it);
            t.d.attack = 99;
            t.d.robotCount = 41;
            t.r.chooseWeaponModifier(t.p, t.d, Obj.MINE);
            int odd = Mods.nuclear(t.p.mods);
            it.with(Item.MINE, 4);
            t.r.chooseWeaponModifier(t.p, t.d, Obj.MINE);
            int min = Mods.nuclear(t.p.mods);
            it.with(Item.MINE, 3);
            t.r.chooseWeaponModifier(t.p, t.d, Obj.MINE);
            assertAll(
                () -> assertEquals(DefaultRobot.NUCLEAR, odd, "8 = twice the minimum, odd count: nuclear only"),
                () -> assertEquals(DefaultRobot.NUCLEAR, min, "exactly the minimum: nuclear only"),
                () -> assertEquals(0, Mods.nuclear(t.p.mods), "below the minimum: none"));
        }

        @Test
        @DisplayName("the rules strip forbidden modifiers; other weapon types change nothing")
        void rules() {
            SpyItems it = new SpyItems().with(Item.MINE, 9);
            W t = new W().spy(it);
            t.d.robotCount = 40;
            t.r.chooseWeaponModifier(t.p, t.d, Obj.MINE);
            assertAll(
                () -> assertEquals(0, Mods.nuclear(t.p.mods), "no nukes"),
                () -> assertEquals(0, Mods.warhead(t.p.mods), "no clusters"),
                () -> assertEquals(0, Mods.velocity(t.p.mods) + Mods.mini(t.p.mods) + Mods.spread(t.p.mods), "no modifiers"));
            t.r.setModifiers(t.p, 1, 3, 1, 1, 1, 1, 1);
            assertAll(
                () -> assertEquals(0, Mods.warhead(t.p.mods), "cluster needs clusters, implosion needs modifiers"),
                () -> assertEquals(0, Mods.laser(t.p.mods), "no laser modifiers"),
                () -> assertEquals(0, Mods.power(t.p.mods), "no power"));
            t.p.mods = Mods.withMini(t.p.mods, 2);
            t.r.chooseWeaponModifier(t.p, t.d, Obj.DEBRIS);
            assertEquals(2, Mods.mini(t.p.mods), "an unknown weapon leaves the modifiers");
        }
    }
}
