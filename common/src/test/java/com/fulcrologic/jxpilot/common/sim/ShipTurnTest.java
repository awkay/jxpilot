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
package com.fulcrologic.jxpilot.common.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.common.sim.KernelFx.moverWorld;
import static com.fulcrologic.jxpilot.common.sim.KernelFx.ship;
import static com.fulcrologic.jxpilot.common.sim.MoveState.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ShipMover#turnPlayer}: C {@code Turn_player} (walls.c:2601-2770). The ship turns one RES step at a time
 * toward {@code (int)(floatDir + 0.5)}, tracing every hull point from its old to its new rotated position.
 * <p>
 * Fixture: walls at blocks (5,1) and (5,2), left face x = 11200 clicks; the ship's centre is at (10624, 4480)
 * heading up (32). Its hull point (-9,-8) is then at x + 511: rotating it counter-clockwise moves it right
 * by 28 and 26 clicks for dirs 33 and 34 (11189) and to 11214 for dir 35, so dir 35 is blocked. Rotating
 * clockwise the nose (15,0) reaches x + 571 at dir 19 and x + 609 at dir 18, so dir 18 is blocked.
 */
class ShipTurnTest {
    static final int BC = Click.BLOCK_CLICKS;
    static final String[] WALL = {"       ", "       ", " _   x ", "     x ", "       "};
    static final String[] CANNON = {"       ", "       ", " _     ", "     f ", "       "};
    static final String[] OPEN5 = {"     ", "     ", "     ", "     ", "_    "};

    /** Set floatDir and run Turn_player; returns the events. */
    static KernelFx.Rec turnTo(KernelFx.TestWorld w, ShipBody p, double floatDir) {
        p.floatDir = (float) floatDir;
        KernelFx.Rec r = new KernelFx.Rec();
        new ShipMover(w, w::rfrac).turnPlayer(p, r);
        return r;
    }

    @Nested
    @DisplayName("near a wall")
    class NearWall {
        @Test
        @DisplayName("turning into the wall stops at the last free heading; floatDir snaps back; wall touch")
        void blocked() {
            ShipBody p = ship(10624, 4480, 32, 0, 0);
            KernelFx.Rec r = turnTo(moverWorld("", WALL), p, 40);
            assertAll(
                () -> assertEquals(34, p.dir, "dirs 33 and 34 are free, 35 hits the wall"),
                () -> assertEquals(34.0, p.floatDir, "floatDir is set to the reached heading"),
                () -> assertEquals(7L, p.lastWallTouch, "a blocked turn is a wall touch"),
                () -> assertEquals(0, r.crashes, "a wall block is not a crash"),
                () -> assertEquals(10624, p.cx, "the ship does not move"));
        }

        @Test
        @DisplayName("turning away from the wall is free; the turn goes the short way round")
        void direction() {
            ShipBody away = ship(10624, 4480, 32, 0, 0);
            turnTo(moverWorld("", WALL), away, 24);
            ShipBody longWay = ship(10624, 4480, 32, 0, 0);
            turnTo(moverWorld("", WALL), longWay, 102);
            ShipBody up = ship(10624, 4480, 100, 0, 0);
            turnTo(moverWorld("", WALL), up, 30);
            assertAll(
                () -> assertEquals(24, away.dir, "32 -> 24 rotates the hull away from the wall"),
                () -> assertEquals(24.0, away.floatDir, "floatDir is kept when not blocked"),
                () -> assertEquals(-1L, away.lastWallTouch, "no wall touch"),
                () -> assertEquals(19, longWay.dir, "32 -> 102 goes down through 0 (58 steps, not 70): the nose hits at 18"),
                () -> assertEquals(109, up.dir, "100 -> 30 goes up through 0 (58 steps, not 70): the nose hits at 110"));
        }

        @Test
        @DisplayName("a half turn (64 steps) either way: counter-clockwise from a lower heading, clockwise from a higher one")
        void halfTurns() {
            ShipBody up = ship(10624, 4480, 24, 0, 0);
            turnTo(moverWorld("", WALL), up, 84);
            ShipBody half = ship(10624, 4480, 24, 0, 0);
            turnTo(moverWorld("", WALL), half, 88);
            ShipBody down = ship(10624, 4480, 98, 0, 0);
            turnTo(moverWorld("", WALL), down, 34);
            assertAll(
                () -> assertEquals(34, up.dir, "24 -> 84 (60 steps up): blocked at 34 by the rear point"),
                () -> assertEquals(34, half.dir, "24 -> 88 (exactly RES/2): counter-clockwise, blocked at 34"),
                () -> assertEquals(94, down.dir,
                    "98 -> 34 (exactly RES/2): clockwise, blocked at 94 (counter-clockwise would stop at 109)"));
        }

        @Test
        @DisplayName("a turn sweeping the nose up into a ceiling is blocked")
        void ceiling() {
            ShipBody p = ship(7000, 6720 - 576, 0, 0, 0);
            turnTo(moverWorld("", "       ", "xxxxxxx", "       ", " _     ", "       "), p, 20);
            assertEquals(13, p.dir, "the nose hits the ceiling going to heading 14");
        }

        @Test
        @DisplayName("the ship's velocity salts a hull point's motion: on a face with no motion across it, moving into it blocks")
        void salt() {
            ShipBody in = ship(11200 - 770, 3480, 49, 1, 0);
            turnTo(moverWorld("", WALL), in, 50);
            ShipBody still = ship(11200 - 770, 3480, 49, 0, 0);
            turnTo(moverWorld("", WALL), still, 50);
            ShipBody away = ship(11200 - 770, 3480, 49, -1, 0);
            turnTo(moverWorld("", WALL), away, 50);
            String[] ceil = {"       ", "xxxxxxx", "       ", " _     ", "       "};
            ShipBody up = ship(7000, 6720 - 770, 81, 0, 1);
            turnTo(moverWorld("", ceil), up, 82);
            ShipBody level = ship(7000, 6720 - 770, 81, 0, 0);
            turnTo(moverWorld("", ceil), level, 82);
            ShipBody leftStill = ship(4480 + 770, 3480, 14, 0, 0);
            turnTo(moverWorld("", ShipMoverTest.LEFT_WALL), leftStill, 15);
            ShipBody leftIn = ship(4480 + 770, 3480, 14, -1, 0);
            turnTo(moverWorld("", ShipMoverTest.LEFT_WALL), leftIn, 15);
            String[] floor = {"       ", "     _ ", "       ", "xxxxxxx", "       "};
            ShipBody floorStill = ship(7000, 4480 + 770, 17, 0, 0);
            turnTo(moverWorld("", floor), floorStill, 18);
            ShipBody floorIn = ship(7000, 4480 + 770, 17, 0, -1);
            turnTo(moverWorld("", floor), floorIn, 18);
            assertAll(
                () -> assertEquals(49, in.dir, "moving toward the wall: salt +1e-6 makes it a bounce, blocked"),
                () -> assertEquals(50, still.dir, "not moving: no salt, the point slides along the face"),
                () -> assertEquals(50, away.dir, "moving away: the point is in the open block"),
                () -> assertEquals(81, up.dir, "moving up into the ceiling: blocked"),
                () -> assertEquals(82, level.dir, "not moving vertically: free"),
                () -> assertEquals(15, leftStill.dir, "on a left wall's face, not moving: free"),
                () -> assertEquals(14, leftIn.dir, "on a left wall's face, moving left (salt -1e-6): blocked"),
                () -> assertEquals(18, floorStill.dir, "on a floor face, not moving: free"),
                () -> assertEquals(17, floorIn.dir, "on a floor face, moving down: blocked"));
        }

        @Test
        @DisplayName("an edge bounce while turning is tolerated")
        void edgeBounce() {
            KernelFx.TestWorld s = moverWorld("", OPEN5);
            int cw = s.clickWidth();
            ShipBody p = ship(cw - 1 - 770, 5600, 49, 1, 0);
            KernelFx.Rec r = turnTo(s, p, 50);
            assertAll(
                () -> assertEquals(50, p.dir, "the hull point on the last click column bounces off the edge, the turn goes on"),
                () -> assertEquals(0, r.crashes, "no crash"),
                () -> assertEquals(cw - 1 - 770, p.cx, "the centre is not pushed"));
        }

        @Test
        @DisplayName("a phased ship turns through the wall")
        void phased() {
            ShipBody p = ship(10624, 4480, 32, 0, 0);
            p.used = Has.PHASING_DEVICE;
            turnTo(moverWorld("", WALL), p, 40);
            assertEquals(40, p.dir, "no wall stops a phased ship");
        }

        @Test
        @DisplayName("turning a hull point into a cannon is a crash reported for that hull point")
        void cannon() {
            ShipBody p = ship(10624, 4480, 32, 0, 0);
            KernelFx.Rec r = turnTo(moverWorld("", CANNON), p, 40);
            assertAll(
                () -> assertEquals(CRASH_CANNON, r.crashType, "the crash is CRASH_CANNON"),
                () -> assertEquals(2, r.crashPoint, "hull point 2 (not the nose) hit"),
                () -> assertEquals(0, r.crashCannon, "the cannon is named"),
                () -> assertEquals(34, p.dir, "the turn stops before the crashing step"),
                () -> assertEquals(34.0, p.floatDir, "floatDir snaps back"),
                () -> assertEquals(7L, p.lastWallTouch, "a blocked turn is a wall touch"));
        }
    }

    @Nested
    @DisplayName("direct turns")
    class Direct {
        @Test
        @DisplayName("far from walls, or not playing, the heading is set at once; equal headings do nothing")
        void direct() {
            ShipBody p = ship(4 * BC + 1000, 4 * BC + 1000, 32, 0, 0);
            turnTo(moverWorld("", ShipMoverTest.OPEN), p, 100.4);
            ShipBody dead = ship(10624, 4480, 32, 0, 0);
            dead.status = 0;
            turnTo(moverWorld("", WALL), dead, 40);
            ShipBody same = ship(10624, 4480, 32, 0, 0);
            turnTo(moverWorld("", WALL), same, 32.3);
            ShipBody round = ship(4 * BC + 1000, 4 * BC + 1000, 32, 0, 0);
            turnTo(moverWorld("", ShipMoverTest.OPEN), round, 127.6);
            assertAll(
                () -> assertEquals(100, p.dir, "walldist 10: (int)(100.4 + 0.5) = 100 at once"),
                () -> assertEquals(100.4f, p.floatDir, "floatDir is untouched"),
                () -> assertEquals(40, dead.dir, "a ship that is not playing turns through the wall"),
                () -> assertEquals(32, same.dir, "(int)(32.3 + 0.5) = 32: nothing to do"),
                () -> assertEquals(-1L, same.lastWallTouch, "and no wall touch"),
                () -> assertEquals(0, round.dir, "(int)(127.6 + 0.5) = 128 is heading 0 (MOD RES)"));
        }
    }

    @Nested
    @DisplayName("world edges")
    class Edges {
        @Test
        @DisplayName("without wrap the centre is pushed so the turned hull stays inside the world")
        void clamp() {
            KernelFx.TestWorld s = moverWorld("", OPEN5);
            int cw = s.clickWidth();
            int ch = s.clickHeight();
            ShipBody left = ship(768, 5600, 32, 0, 0);
            turnTo(s, left, 64);
            ShipBody right = ship(cw - 768, 5600, 32, 0, 0);
            turnTo(moverWorld("", OPEN5), right, 0);
            ShipBody exact = ship(cw - 960, 5600, 1, 0, 0);
            turnTo(moverWorld("", OPEN5), exact, 0);
            ShipBody bottom = ship(5600, 768, 0, 0, 0);
            turnTo(moverWorld("", OPEN5), bottom, 96);
            ShipBody top = ship(5600, ch - 768, 0, 0, 0);
            turnTo(moverWorld("", OPEN5), top, 32);
            KernelFx.TestWorld w = moverWorld("edgeWrap: yes", "     ", " x   ", "     ", "     ", "_    ");
            ShipBody wrapped = ship(768, 5600, 32, 0, 0);
            turnTo(w, wrapped, 64);
            assertAll(
                () -> assertEquals(64, left.dir, "the turn completes"),
                () -> assertEquals(960, left.cx, "the nose (-15 px) at heading 64 puts the centre at 15 px"),
                () -> assertEquals(5600, left.cy, "y is not touched"),
                () -> assertEquals(15, left.px, "the pixel position follows the pushed centre"),
                () -> assertEquals(0, right.dir, "the turn completes"),
                () -> assertEquals(cw - 1 - 960, right.cx, "the nose at heading 0 ends on the last click column"),
                () -> assertEquals(cw - 961, exact.cx, "a nose landing exactly on x = width is pushed to width - 1"),
                () -> assertEquals(96, bottom.dir, "the turn completes"),
                () -> assertEquals(960, bottom.cy, "nose down: centre pushed up to 15 px"),
                () -> assertEquals(32, top.dir, "the turn completes"),
                () -> assertEquals(ch - 1 - 960, top.cy, "nose up: centre pushed down to the last click row"),
                () -> assertEquals(2, w.wallDist(0, 2), "(fixture: the wrap map is traced at the ship)"),
                () -> assertEquals(64, wrapped.dir, "with wrap the turn completes"),
                () -> assertEquals(768, wrapped.cx, "and the centre is not pushed"));
        }

        @Test
        @DisplayName("a crash into the universe while turning only blocks")
        void universe() {
            ShipBody p = ship(100, 5600, 32, 0, 0);
            KernelFx.Rec r = turnTo(moverWorld("", OPEN5), p, 33);
            assertAll(
                () -> assertEquals(0, r.crashes, "leaving the universe is not a crash while turning"),
                () -> assertEquals(32, p.dir, "but it blocks the turn"),
                () -> assertEquals(32.0, p.floatDir, "floatDir snaps back"),
                () -> assertEquals(483, p.cx, "the centre was pushed in for heading 33 ((-9,8) at 33 is -483 clicks)"));
        }
    }
}
