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

import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.MoverPlayerTest.killed;
import static com.fulcrologic.jxpilot.server.sim.MoverPlayerTest.lastMessage;
import static com.fulcrologic.jxpilot.server.sim.MoverPlayerTest.ship;
import static com.fulcrologic.jxpilot.server.sim.MoverPlayerTest.sim;
import static com.fulcrologic.jxpilot.common.sim.Click.BLOCK_CLICKS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Mover#turnPlayer}: C {@code Turn_player} (walls.c:2601-2770). The ship turns one RES step at a time
 * toward {@code (int)(floatDir + 0.5)}, tracing every hull point from its old to its new rotated position.
 * <p>
 * Fixture: walls at blocks (5,1) and (5,2), left face x = 11200 clicks; the ship's centre is at (10624, 4480)
 * heading up (32). Its hull point (-9,-8) is then at x + 511: rotating it counter-clockwise moves it right
 * by 28 and 26 clicks for dirs 33 and 34 (11189) and to 11214 for dir 35, so dir 35 is blocked. Rotating
 * clockwise the nose (15,0) reaches x + 571 at dir 19 and x + 609 at dir 18, so dir 18 is blocked.
 */
class MoverTurnTest {
    static final int BC = BLOCK_CLICKS;
    static final String[] WALL = {"       ", "       ", " _   x ", "     x ", "       "};
    static final String[] CANNON = {"       ", "       ", " _     ", "     f ", "       "};
    static final String[] OPEN5 = {"     ", "     ", "     ", "     ", "_    "};

    static Player turnTo(Simulation s, Player p, double floatDir) {
        p.floatDir = (float) floatDir;
        s.mover.turnPlayer(p);
        return p;
    }

    @Nested
    @DisplayName("near a wall")
    class NearWall {
        @Test
        @DisplayName("turning into the wall stops at the last free heading; floatDir snaps back; wall touch")
        void blocked() {
            Simulation s = sim("", WALL);
            Player p = turnTo(s, ship(s, 10624, 4480, 32, 0, 0), 40);
            assertAll(
                () -> assertEquals(34, p.dir, "dirs 33 and 34 are free, 35 hits the wall"),
                () -> assertEquals(34.0, p.floatDir, "floatDir is set to the reached heading"),
                () -> assertEquals(7.0, p.lastWallTouch, "a blocked turn is a wall touch"),
                () -> assertEquals(false, killed(p), "a wall block is not a crash"),
                () -> assertEquals(10624, p.cx, "the ship does not move"));
        }

        @Test
        @DisplayName("turning away from the wall is free; the turn goes the short way round")
        void direction() {
            Simulation s = sim("", WALL);
            Player away = turnTo(s, ship(s, 10624, 4480, 32, 0, 0), 24);
            Simulation l = sim("", WALL);
            Player longWay = turnTo(l, ship(l, 10624, 4480, 32, 0, 0), 102);
            Simulation u = sim("", WALL);
            Player up = turnTo(u, ship(u, 10624, 4480, 100, 0, 0), 30);
            assertAll(
                () -> assertEquals(24, away.dir, "32 -> 24 rotates the hull away from the wall"),
                () -> assertEquals(24.0, away.floatDir, "floatDir is kept when not blocked"),
                () -> assertEquals(-1.0, away.lastWallTouch, "no wall touch"),
                () -> assertEquals(19, longWay.dir, "32 -> 102 goes down through 0 (58 steps, not 70): the nose hits at 18"),
                () -> assertEquals(109, up.dir, "100 -> 30 goes up through 0 (58 steps, not 70): the nose hits at 110"));
        }

        @Test
        @DisplayName("a half turn (64 steps) either way: counter-clockwise from a lower heading, clockwise from a higher one")
        void halfTurns() {
            Simulation a = sim("", WALL);
            Player up = turnTo(a, ship(a, 10624, 4480, 24, 0, 0), 84);
            Simulation h = sim("", WALL);
            Player half = turnTo(h, ship(h, 10624, 4480, 24, 0, 0), 88);
            Simulation b = sim("", WALL);
            Player down = turnTo(b, ship(b, 10624, 4480, 98, 0, 0), 34);
            assertAll(
                () -> assertEquals(34, up.dir, "24 -> 84 (60 steps up): blocked at 34 by the rear point"),
                () -> assertEquals(34, half.dir, "24 -> 88 (exactly RES/2): counter-clockwise, blocked at 34"),
                () -> assertEquals(94, down.dir,
                    "98 -> 34 (exactly RES/2): clockwise, blocked at 94 (counter-clockwise would stop at 109)"));
        }

        @Test
        @DisplayName("a turn sweeping the nose up into a ceiling is blocked")
        void ceiling() {
            // wall row y=3 (bottom face 6720); the hull point (-9,8) at heading 0 is 1 px below it
            Simulation s = sim("", "       ", "xxxxxxx", "       ", " _     ", "       ");
            Player p = turnTo(s, ship(s, 7000, 6720 - 576, 0, 0, 0), 20);
            // nose y = 15 sin: 571 clicks at heading 13 (fits under 576), 609 at 14
            assertEquals(13, p.dir, "the nose hits the ceiling going to heading 14");
        }

        @Test
        @DisplayName("the ship's velocity salts a hull point's motion: on a face with no motion across it, moving into it blocks")
        void salt() {
            // heading 49 -> 50: hull point (-9,-8) stays at x + 770 (12.03 px); put it exactly on the wall face
            Simulation a = sim("", WALL);
            Player in = turnTo(a, ship(a, 11200 - 770, 3480, 49, 1, 0), 50);
            Simulation b = sim("", WALL);
            Player still = turnTo(b, ship(b, 11200 - 770, 3480, 49, 0, 0), 50);
            Simulation c = sim("", WALL);
            Player away = turnTo(c, ship(c, 11200 - 770, 3480, 49, -1, 0), 50);
            // heading 81 -> 82: the same point stays at y + 770; put it on a ceiling face
            String[] ceil = {"       ", "xxxxxxx", "       ", " _     ", "       "};
            Simulation d = sim("", ceil);
            Player up = turnTo(d, ship(d, 7000, 6720 - 770, 81, 0, 1), 82);
            Simulation e = sim("", ceil);
            Player level = turnTo(e, ship(e, 7000, 6720 - 770, 81, 0, 0), 82);
            // heading 14 -> 15: hull point (-9,8) stays at x - 770; put it on the right face of a wall to its left
            Simulation f = sim("", MoverPlayerTest.LEFT_WALL);
            Player leftStill = turnTo(f, ship(f, 4480 + 770, 3480, 14, 0, 0), 15);
            Simulation g = sim("", MoverPlayerTest.LEFT_WALL);
            Player leftIn = turnTo(g, ship(g, 4480 + 770, 3480, 14, -1, 0), 15);
            // heading 17 -> 18: hull point (-9,-8) stays at y - 770; put it on a floor face
            String[] floor = {"       ", "     _ ", "       ", "xxxxxxx", "       "};
            Simulation h = sim("", floor);
            Player floorStill = turnTo(h, ship(h, 7000, 4480 + 770, 17, 0, 0), 18);
            Simulation k = sim("", floor);
            Player floorIn = turnTo(k, ship(k, 7000, 4480 + 770, 17, 0, -1), 18);
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
            Simulation s = sim("", OPEN5);
            int cw = s.world.clickWidth;
            Player p = turnTo(s, ship(s, cw - 1 - 770, 5600, 49, 1, 0), 50);
            assertAll(
                () -> assertEquals(50, p.dir, "the hull point on the last click column bounces off the edge, the turn goes on"),
                () -> assertEquals(false, killed(p), "no crash"),
                () -> assertEquals(cw - 1 - 770, p.cx, "the centre is not pushed"));
        }

        @Test
        @DisplayName("a phased ship turns through the wall")
        void phased() {
            Simulation s = sim("", WALL);
            Player p = ship(s, 10624, 4480, 32, 0, 0);
            p.used = Has.PHASING_DEVICE;
            turnTo(s, p, 40);
            assertEquals(40, p.dir, "no wall stops a phased ship");
        }

        @Test
        @DisplayName("turning a hull point into a cannon is a crash: killed, the cannon destroyed")
        void cannon() {
            Simulation s = sim("", CANNON);
            Player p = turnTo(s, ship(s, 10624, 4480, 32, 0, 0), 40);
            assertAll(
                () -> assertTrue(killed(p), "the ship is killed"),
                () -> assertEquals("p smashed against a cannon.", lastMessage(s.world),
                    "hull point 2 (not the nose) hit: no 'head first'"),
                () -> assertTrue(s.world.cannonDeadTime[0] > 0, "the cannon is destroyed"),
                () -> assertEquals(BlockType.SPACE, s.world.map.block(5, 1), "its block becomes space"),
                () -> assertEquals(34, p.dir, "the turn stops before the crashing step"),
                () -> assertEquals(34.0, p.floatDir, "floatDir snaps back"));
        }
    }

    @Nested
    @DisplayName("direct turns")
    class Direct {
        @Test
        @DisplayName("far from walls, or not playing, the heading is set at once; equal headings do nothing")
        void direct() {
            Simulation s = sim("", MoverPlayerTest.OPEN);
            Player p = turnTo(s, ship(s, 4 * BC + 1000, 4 * BC + 1000, 32, 0, 0), 100.4);
            Simulation d = sim("", WALL);
            Player dead = ship(d, 10624, 4480, 32, 0, 0);
            dead.status = 0;
            turnTo(d, dead, 40);
            Simulation e = sim("", WALL);
            Player same = turnTo(e, ship(e, 10624, 4480, 32, 0, 0), 32.3);
            Simulation r = sim("", MoverPlayerTest.OPEN);
            Player round = turnTo(r, ship(r, 4 * BC + 1000, 4 * BC + 1000, 32, 0, 0), 127.6);
            assertAll(
                () -> assertEquals(100, p.dir, "walldist 10: (int)(100.4 + 0.5) = 100 at once"),
                () -> assertEquals(100.4f, p.floatDir, "floatDir is untouched"),
                () -> assertEquals(40, dead.dir, "a ship that is not playing turns through the wall"),
                () -> assertEquals(32, same.dir, "(int)(32.3 + 0.5) = 32: nothing to do"),
                () -> assertEquals(-1.0, same.lastWallTouch, "and no wall touch"),
                () -> assertEquals(0, round.dir, "(int)(127.6 + 0.5) = 128 is heading 0 (MOD RES)"));
        }
    }

    @Nested
    @DisplayName("world edges")
    class Edges {
        @Test
        @DisplayName("without wrap the centre is pushed so the turned hull stays inside the world")
        void clamp() {
            Simulation s = sim("", OPEN5);
            int cw = s.world.clickWidth;
            int ch = s.world.clickHeight;
            Player left = turnTo(s, ship(s, 768, 5600, 32, 0, 0), 64);
            Simulation r = sim("", OPEN5);
            Player right = turnTo(r, ship(r, cw - 768, 5600, 32, 0, 0), 0);
            Simulation re = sim("", OPEN5);
            Player exact = turnTo(re, ship(re, cw - 960, 5600, 1, 0, 0), 0);
            Simulation b = sim("", OPEN5);
            Player bottom = turnTo(b, ship(b, 5600, 768, 0, 0, 0), 96);
            Simulation t = sim("", OPEN5);
            Player top = turnTo(t, ship(t, 5600, ch - 768, 0, 0, 0), 32);
            Simulation w = sim("edgeWrap: yes", "     ", " x   ", "     ", "     ", "_    ");
            Player wrapped = turnTo(w, ship(w, 768, 5600, 32, 0, 0), 64);
            assertAll(
                () -> assertEquals(64, left.dir, "the turn completes"),
                () -> assertEquals(960, left.cx, "the nose (-15 px) at heading 64 puts the centre at 15 px"),
                () -> assertEquals(5600, left.cy, "y is not touched"),
                () -> assertEquals(0, right.dir, "the turn completes"),
                () -> assertEquals(cw - 1 - 960, right.cx, "the nose at heading 0 ends on the last click column"),
                () -> assertEquals(cw - 961, exact.cx, "a nose landing exactly on x = width is pushed to width - 1"),
                () -> assertEquals(96, bottom.dir, "the turn completes"),
                () -> assertEquals(960, bottom.cy, "nose down: centre pushed up to 15 px"),
                () -> assertEquals(32, top.dir, "the turn completes"),
                () -> assertEquals(ch - 1 - 960, top.cy, "nose up: centre pushed down to the last click row"),
                () -> assertEquals(2, w.world.wallDist(0, 2), "(fixture: the wrap map is traced at the ship)"),
                () -> assertEquals(64, wrapped.dir, "with wrap the turn completes"),
                () -> assertEquals(768, wrapped.cx, "and the centre is not pushed"));
        }

        @Test
        @DisplayName("a crash into the universe while turning only blocks")
        void universe() {
            Simulation s = sim("", OPEN5);
            // centre at 100 clicks: the hull point (-9,8) at heading 32 is at -412, outside the world
            Player p = turnTo(s, ship(s, 100, 5600, 32, 0, 0), 33);
            assertAll(
                () -> assertEquals(false, killed(p), "leaving the universe is not a crash while turning"),
                () -> assertEquals(32, p.dir, "but it blocks the turn"),
                () -> assertEquals(32.0, p.floatDir, "floatDir snaps back"),
                () -> assertEquals(483, p.cx, "the centre was pushed in for heading 33 ((-9,8) at 33 is -483 clicks)"));
        }
    }
}
