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

import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.MoveState.*;
import static com.fulcrologic.jxpilot.server.sim.SegmentTest.BC;
import static com.fulcrologic.jxpilot.server.sim.SegmentTest.obj;
import static com.fulcrologic.jxpilot.server.sim.SegmentTest.world;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The block-type cases of C {@code Move_segment} (walls.c:742-1440): diagonal walls, cannons, targets,
 * treasures, wormholes and phasing. The tested block is always (2,1) of a 5x3 map (rows top first), so a
 * point at offset (ox, oy) inside it is at clicks (2*BC + ox, BC + oy).
 */
class SegmentBlocksTest {
    static World row(String middle) {
        return world("", "     ", middle, "     ");
    }

    /** Move state for a point at offset (ox, oy) of block (2,1) with velocity = todo (only signs/ratios matter). */
    static MoveState at(World w, int ox, int oy, int tx, int ty) {
        return obj(w, 2 * BC + ox, BC + oy, tx, ty, tx, ty);
    }

    static MoveState run(World w, MoveState ms) {
        new Segment(w).move(ms);
        return ms;
    }

    /** {doneX, doneY, todoX, todoY, crash, bounce}. */
    static int[] out(MoveState ms) {
        return new int[] {ms.doneX, ms.doneY, ms.todoX, ms.todoY, ms.crash, ms.bounce};
    }

    static void same(int[] expected, MoveState ms, String what) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, out(ms), what);
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("diagonal REC_LD ('w', solid lower-left, x+y < S)")
    class RecLD {
        final World w = row("  w  ");

        @Test
        @DisplayName("approaching the hypotenuse: stop on it, then bounce BounceLeftDown")
        void twoStep() {
            MoveState ms = run(w, at(w, 2000, 2000, -1000, -1000));
            int[] first = out(ms);
            ms.posX += ms.doneX;
            ms.posY += ms.doneY;
            run(w, ms);
            assertAll(
                // walls.c REC_LD: delta.x = (S - off.x - off.y) / (1 + w) = (2240 - 4000) / 2 = -880
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(
                    new int[] {-880, -880, -120, -120, NOT_A_CRASH, NOT_A_BOUNCE}, first,
                    "the first step stops on the hypotenuse after (-880, -880)"),
                () -> same(new int[] {0, 0, 120, 120, NOT_A_CRASH, BOUNCE_LEFT_DOWN}, ms,
                    "on the hypotenuse the allowed delta is 0: BounceLeftDown swaps and negates todo"),
                () -> assertEquals(1000.0, ms.velX, "velocity (-1000, -1000) becomes (-vel.y, -vel.x)"));
        }

        @Test
        @DisplayName("horizontal-major and vertical-major hits, with and without the one-click correction")
        void hits() {
            assertAll(
                () -> same(new int[] {-71, -19, -764, -216, 0, 0}, run(w, at(w, 952, 1380, -835, -235)),
                    "x-major: delta.x = (int)((S-ox-oy)/(1+w)), delta.y = (int)(dx*w)"),
                () -> same(new int[] {-662, 84, -586, 76, 0, 0}, run(w, at(w, 1662, 1156, -1248, 160)),
                    "x-major: one short of the line after truncation, delta.x++"),
                () -> same(new int[] {-367, -596, -74, -119, 0, 0}, run(w, at(w, 975, 2230, -441, -715)),
                    "y-major: delta.y = (int)((S-ox-oy)/(1+w)) with w = todo.x/todo.y"),
                () -> same(new int[] {57, -685, 30, -344, 0, 0}, run(w, at(w, 1356, 1512, 87, -1029)),
                    "y-major: delta.y++ correction"),
                () -> same(new int[] {0, 0, 79, 184, 0, BOUNCE_LEFT_DOWN}, run(w, at(w, 332, 1908, -184, -79)),
                    "x-major with delta.x = 0: BounceLeftDown"),
                () -> same(new int[] {0, 0, 109, -72, 0, BOUNCE_LEFT_DOWN}, run(w, at(w, 601, 1639, 72, -109)),
                    "y-major with delta.y = 0: BounceLeftDown"));
        }

        @Test
        @DisplayName("solid sides bounce, the solid part crashes, the open part is free")
        void sides() {
            assertAll(
                () -> assertEquals(BOUNCE_HOR_LO, run(w, at(w, 0, 1000, 64, 0)).bounce, "the left side bounces BounceHorLo"),
                () -> assertEquals(BOUNCE_VER_LO, run(w, at(w, 1000, 0, 0, 64)).bounce, "the bottom side bounces BounceVerLo"),
                () -> assertEquals(CRASH_WALL, run(w, at(w, 100, 100, 10, 10)).crash, "inside the triangle crashes"),
                () -> same(new int[] {100, 100, 0, 0, 0, 0}, run(w, at(w, 2000, 2000, 100, 100)),
                    "moving in the open half does not touch the wall"));
        }

        @Test
        @DisplayName("apexes: a diagonal bounce when moving into the hypotenuse, else cleared or crash")
        void apexes() {
            World cov = row(" xw  ");
            World below = world("", "     ", "  w  ", "  x  ");
            MoveState apex = run(cov, obj(cov, 2 * BC, 2 * BC, 0, -1, 0, -64));
            MoveState apexBottom = run(below, obj(below, 3 * BC, BC, -1, 0.5, -64, 32));
            MoveState cleared = run(cov, obj(cov, 2 * BC, 2 * BC, 1, -0.5, 64, -32));
            MoveState clearedBottom = run(below, obj(below, 3 * BC, BC, -0.5, 1, -32, 64));
            MoveState crash = run(cov, obj(cov, 2 * BC, BC + 1000, 1, 0, 64, 0));
            assertAll(
                () -> same(new int[] {0, 0, 64, 0, 0, BOUNCE_LEFT_DOWN}, apex,
                    "top-left apex moving with vel.x + vel.y < 0: BounceLeftDown"),
                () -> same(new int[] {0, 0, -32, 64, 0, BOUNCE_LEFT_DOWN}, apexBottom,
                    "bottom-right apex moving with vel.x + vel.y < 0: BounceLeftDown"),
                () -> same(new int[] {64, -32, 0, 0, 0, 0}, cleared,
                    "top-left apex, covered side face, no diagonal bounce: cleared, the point moves on"),
                () -> same(new int[] {-32, 64, 0, 0, 0, 0}, clearedBottom,
                    "bottom-right apex with vel.x + vel.y >= 0 and a covered bottom face: cleared"),
                () -> assertEquals(CRASH_WALL, crash.crash, "a covered left face away from the apex crashes"));
        }
    }

    @Nested
    @DisplayName("diagonal REC_LU ('s', solid upper-left, x < y)")
    class RecLU {
        final World w = row("  s  ");

        @Test
        @DisplayName("hits, bounces, sides and the apex")
        void all() {
            World cov = row(" xs  ");
            World above = world("", "  x  ", "  s  ", "     ");
            assertAll(
                () -> same(new int[] {-726, 227, -622, 196, 0, 0}, run(w, at(w, 2103, 1148, -1348, 423)),
                    "x-major: delta.x = (int)((oy-ox)/(1-w))"),
                () -> same(new int[] {-542, -170, -363, -115, 0, 0}, run(w, at(w, 1031, 659, -905, -285)),
                    "x-major: delta.x++ correction"),
                () -> same(new int[] {-11, 105, -41, 367, 0, 0}, run(w, at(w, 913, 796, -52, 472)),
                    "y-major: delta.y = (int)((ox-oy)/(1-w))"),
                () -> same(new int[] {13, 342, 17, 393, 0, 0}, run(w, at(w, 469, 140, 30, 735)),
                    "y-major: delta.y-- correction"),
                () -> same(new int[] {0, 0, 14, -134, 0, BOUNCE_LEFT_UP}, run(w, at(w, 1391, 1390, -134, 14)),
                    "x-major with delta.x = 0: BounceLeftUp swaps todo"),
                () -> same(new int[] {0, 0, 56, -53, 0, BOUNCE_LEFT_UP}, run(w, at(w, 1335, 1335, -53, 56)),
                    "y-major with delta.y = 0: BounceLeftUp"),
                () -> assertEquals(BOUNCE_HOR_LO, run(w, at(w, 0, 1000, 64, 0)).bounce, "the left side bounces"),
                () -> assertEquals(BOUNCE_VER_HI, run(w, obj(w, 2 * BC + 1000, 2 * BC, 0, -1, 0, -64)).bounce,
                    "the top side bounces BounceVerHi"),
                () -> assertEquals(CRASH_WALL, run(w, at(w, 100, 2000, 10, 10)).crash, "inside the triangle crashes"),
                () -> same(new int[] {0, 0, 64, 0, 0, BOUNCE_LEFT_UP}, run(w, obj(w, 2 * BC, BC, 0, 1, 0, 64)),
                    "bottom-left apex with vel.x < vel.y: BounceLeftUp"),
                () -> same(new int[] {0, 0, -32, -64, 0, BOUNCE_LEFT_UP},
                    run(above, obj(above, 3 * BC, 2 * BC, -1, -0.5, -64, -32)),
                    "top-right apex with vel.x < vel.y and a covered top face: BounceLeftUp"),
                () -> same(new int[] {64, 32, 0, 0, 0, 0}, run(cov, obj(cov, 2 * BC, BC, 1, 0.5, 64, 32)),
                    "bottom-left apex, covered left face: cleared, moves on"),
                () -> same(new int[] {-32, -64, 0, 0, 0, 0}, run(above, obj(above, 3 * BC, 2 * BC, -0.5, -1, -32, -64)),
                    "top-right apex with vel.x >= vel.y and a covered top face: cleared, moves on"));
        }
    }

    @Nested
    @DisplayName("diagonal REC_RD ('q', solid lower-right, x > y)")
    class RecRD {
        final World w = row("  q  ");

        @Test
        @DisplayName("hits, bounces, sides and the apex")
        void all() {
            World below = world("", "     ", "  q  ", "  x  ");
            World right = row("  qx ");
            assertAll(
                () -> same(new int[] {1133, -175, 233, -37, 0, 0}, run(w, at(w, 467, 1776, 1366, -212)),
                    "x-major: delta.x = (int)((oy-ox)/(1-w))"),
                () -> same(new int[] {204, 29, 534, 79, 0, 0}, run(w, at(w, 627, 802, 738, 108)),
                    "x-major: delta.x-- correction"),
                () -> same(new int[] {248, -275, 787, -872, 0, 0}, run(w, at(w, 923, 1447, 1035, -1147)),
                    "y-major: delta.y = (int)((ox-oy)/(1-w))"),
                () -> same(new int[] {-120, -329, -177, -481, 0, 0}, run(w, at(w, 1297, 1506, -297, -810)),
                    "y-major: delta.y++ correction"),
                () -> same(new int[] {0, 0, -182, 200, 0, BOUNCE_RIGHT_DOWN}, run(w, at(w, 1122, 1122, 200, -182)),
                    "x-major with delta.x = 0: BounceRightDown swaps todo"),
                () -> same(new int[] {0, 0, -99, 44, 0, BOUNCE_RIGHT_DOWN}, run(w, at(w, 643, 644, 44, -99)),
                    "y-major with delta.y = 0: BounceRightDown"),
                () -> assertEquals(BOUNCE_HOR_HI, run(w, obj(w, 3 * BC, BC + 1000, -1, 0, -64, 0)).bounce,
                    "the right side bounces BounceHorHi"),
                () -> assertEquals(BOUNCE_VER_LO, run(w, at(w, 1000, 0, 0, 64)).bounce, "the bottom side bounces"),
                () -> assertEquals(CRASH_WALL, run(w, at(w, 2000, 100, 10, 10)).crash, "inside the triangle crashes"),
                () -> same(new int[] {0, 0, 0, 64, 0, BOUNCE_RIGHT_DOWN}, run(w, at(w, 0, 0, 64, 0)),
                    "bottom-left apex with vel.x > vel.y: BounceRightDown"),
                () -> same(new int[] {0, 0, -64, -32, 0, BOUNCE_RIGHT_DOWN},
                    run(right, obj(right, 3 * BC, 2 * BC, -0.5, -1, -32, -64)),
                    "top-right apex with vel.x > vel.y and a covered right face: BounceRightDown"),
                () -> same(new int[] {32, 64, 0, 0, 0, 0}, run(below, obj(below, 2 * BC, BC, 0.5, 1, 32, 64)),
                    "bottom-left apex with vel.x <= vel.y, covered bottom face: cleared, moves on"));
        }
    }

    @Nested
    @DisplayName("diagonal REC_RU ('a', solid upper-right, x+y > S)")
    class RecRU {
        final World w = row("  a  ");

        @Test
        @DisplayName("hits, bounces, sides and the apex")
        void all() {
            World above = world("", "  x  ", "  a  ", "     ");
            World right = row("  ax ");
            assertAll(
                () -> same(new int[] {1149, 701, 250, 153, 0, 0}, run(w, at(w, 40, 349, 1399, 854)),
                    "x-major: delta.x = (int)((S-ox-oy)/(1+w))"),
                () -> same(new int[] {219, -59, 496, -136, 0, 0}, run(w, at(w, 1072, 1008, 715, -195)),
                    "x-major: delta.x-- correction"),
                () -> same(new int[] {89, 265, 316, 931, 0, 0}, run(w, at(w, 870, 1014, 405, 1196)),
                    "y-major"),
                () -> same(new int[] {-119, 703, -31, 177, 0, 0}, run(w, at(w, 1077, 579, -150, 880)),
                    "y-major: delta.y-- correction"),
                () -> same(new int[] {0, 0, -43, -111, 0, BOUNCE_RIGHT_UP}, run(w, at(w, 1171, 1069, 111, 43)),
                    "x-major with delta.x = 0: BounceRightUp"),
                () -> same(new int[] {0, 0, -168, -149, 0, BOUNCE_RIGHT_UP}, run(w, at(w, 1579, 661, 149, 168)),
                    "y-major with delta.y = 0: BounceRightUp"),
                () -> assertEquals(BOUNCE_HOR_HI, run(w, obj(w, 3 * BC, BC + 1000, -1, 0, -64, 0)).bounce,
                    "the right side bounces"),
                () -> assertEquals(BOUNCE_VER_HI, run(w, obj(w, 2 * BC + 1000, 2 * BC, 0, -1, 0, -64)).bounce,
                    "the top side bounces"),
                () -> assertEquals(CRASH_WALL, run(w, at(w, 2000, 2000, -10, -10)).crash, "inside the triangle crashes"),
                () -> same(new int[] {0, 0, -64, 32, 0, BOUNCE_RIGHT_UP},
                    run(right, obj(right, 3 * BC, BC, -0.5, 1, -32, 64)),
                    "bottom-right apex with vel.x + vel.y > 0 and a covered right face: BounceRightUp"),
                () -> same(new int[] {0, 0, 32, -64, 0, BOUNCE_RIGHT_UP},
                    run(above, obj(above, 2 * BC, 2 * BC, 1, -0.5, 64, -32)),
                    "top-left apex with vel.x + vel.y > 0 and a covered top face: BounceRightUp"),
                () -> same(new int[] {32, -64, 0, 0, 0, 0}, run(above, obj(above, 2 * BC, 2 * BC, 0.5, -1, 32, -64)),
                    "top-left apex, covered top face, vel.x + vel.y <= 0: cleared, moves on"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("cannons")
    class Cannons {
        @Test
        @DisplayName("an up cannon is the triangle (0,0) (S,0) (S/2,S/3): crash inside, clear outside, stop at the edge")
        void upCannon() {
            World w = row("  r  ");
            MoveState partial = run(w, at(w, 100, 700, 1000, 0));
            int[] first = out(partial);
            partial.posX += partial.doneX;
            run(w, partial);
            MoveState fromRight = run(w, at(w, 2140, 700, -1000, 0));
            int[] rFirst = out(fromRight);
            fromRight.posX += fromRight.doneX;
            run(w, fromRight);
            MoveState down = run(w, at(w, 300, 1500, 0, -1400));
            assertAll(
                () -> assertEquals(CRASH_CANNON, run(w, at(w, 1120, 100, 64, 0)).crash, "inside the left half: crash"),
                () -> assertEquals(0, run(w, at(w, 1120, 100, 64, 0)).cannon, "the hit cannon index is recorded"),
                () -> assertEquals(CRASH_CANNON, run(w, at(w, 2000, 100, 64, 0)).crash, "inside the right half: crash"),
                () -> same(new int[] {200, 0, 0, 0, 0, 0}, run(w, at(w, 100, 2000, 200, 0)), "above the left half: clear"),
                () -> same(new int[] {100, 0, 0, 0, 0, 0}, run(w, at(w, 2000, 1000, 100, 0)), "above the right half: clear"),
                // C: d = (3*start.y - 2*start.x) / (2 - 3w) = (2100 - 200) / 2 = 950
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {950, 0, 50, 0, 0, 0}, first,
                    "moving right toward the left edge stops after 950 clicks, on the edge 3y = 2x"),
                () -> assertEquals(CRASH_CANNON, partial.crash, "the next step from the edge is a crash"),
                // C: d = (2S - 2*start.x - 3*start.y) / (2 + 3w) = (4480 - 4280 - 2100) / 2 = -950
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {-950, 0, -50, 0, 0, 0}, rFirst,
                    "moving left toward the right edge stops after 950 clicks"),
                () -> assertEquals(CRASH_CANNON, fromRight.crash, "and then crashes"),
                // C (y-major): d = (2*start.x - 3*start.y) / (3 - 2w) = (600 - 4500) / 3 = -1300
                () -> same(new int[] {0, -1300, 0, -100, 0, 0}, down, "falling onto the left edge stops after 1300"),
                () -> assertEquals(CRASH_CANNON, run(w, at(w, 301, 201, 0, -100)).crash,
                    "a point whose allowed move rounds to 0 crashes"));
        }

        @Test
        @DisplayName("a move ending exactly on an edge line is allowed in full (C accepts a hit only strictly before the end)")
        void endOnEdge() {
            World w = row("  r  ");
            // ends on (300, 200) [3y = 2x] or its mirror (1940, 200) [3y = 2(S-x)]; the edge hit there is at the end
            // of the move and (int)(a.x * w) truncates one click short, so only C's strict < keeps the full move
            assertAll(
                () -> same(new int[] {50, 29, 0, 0, 0, 0}, run(w, at(w, 250, 171, 50, 29)), "x-major onto the left edge"),
                () -> same(new int[] {-50, 29, 0, 0, 0, 0}, run(w, at(w, 1990, 171, -50, 29)), "x-major onto the right edge"),
                () -> same(new int[] {-255, -400, 0, 0, 0, 0}, run(w, at(w, 555, 600, -255, -400)), "y-major onto the left edge"),
                () -> same(new int[] {255, -400, 0, 0, 0, 0}, run(w, at(w, 1685, 600, 255, -400)),
                    "y-major onto the right edge"),
                // row y = 746 = S/3 (int) is the last row of the triangle: its tip is 3 clicks wide (1119..1121)
                () -> same(new int[] {119, 0, 81, 0, 0, 0}, run(w, at(w, 1000, 746, 200, 0)),
                    "a move along row S/3 stops at the left edge (x 1119): start.y + a.y <= S/3 accepts it"),
                () -> same(new int[] {-119, 0, -81, 0, 0, 0}, run(w, at(w, 1240, 746, -200, 0)),
                    "and from the right at the right edge (x 1121)"));
        }

        @Test
        @DisplayName("the other three facings are the up case rotated")
        void rotated() {
            World down = row("  c  ");
            World right = row("  f  ");
            World left = row("  d  ");
            assertAll(
                () -> assertEquals(CRASH_CANNON, run(down, at(down, 1120, 2140, 10, 0)).crash, "down cannon: top centre is solid"),
                () -> same(new int[] {-10, 0, 0, 0, 0, 0}, run(down, at(down, 1120, 100, -10, 0)), "down cannon: bottom is clear"),
                () -> assertEquals(CRASH_CANNON, run(right, at(right, 100, 1120, 0, 10)).crash, "right cannon: left centre is solid"),
                () -> same(new int[] {0, 10, 0, 0, 0, 0}, run(right, at(right, 2000, 1120, 0, 10)), "right cannon: right is clear"),
                () -> assertEquals(CRASH_CANNON, run(left, at(left, 2140, 1120, 0, 10)).crash, "left cannon: right centre is solid"),
                () -> same(new int[] {0, -10, 0, 0, 0, 0}, run(left, at(left, 100, 1120, 0, -10)), "left cannon: left is clear"),
                () -> same(new int[] {0, -950, 0, -50, 0, 0}, run(right, at(right, 700, 2140, 0, -1000)),
                    "right cannon: the up-frame partial move (950 along x) maps back to (0, -950)"));
        }

        @Test
        @DisplayName("cannon rules: no cannon crash, own cannon shots, team immunity")
        void rules() {
            World w = row("  r  ");
            MoveState noCrash = at(w, 1120, 100, 64, 0);
            noCrash.cannonCrashes = false;
            run(w, noCrash);
            MoveState fromCannon = at(w, 1120, 100, 64, 0);
            fromCannon.status = Obj.FROMCANNON;
            run(w, fromCannon);
            World t = row("  r  ");
            t.params.teamPlay = true;
            t.params.teamImmunity = false;
            int team = t.map.cannons().get(0).team();
            MoveState sameTeamShot = at(t, 1120, 100, 64, 0);
            sameTeamShot.status = Obj.FROMCANNON;
            sameTeamShot.team = team;
            run(t, sameTeamShot);
            MoveState otherTeamShot = at(t, 1120, 100, 64, 0);
            otherTeamShot.status = Obj.FROMCANNON;
            otherTeamShot.team = team + 1;
            run(t, otherTeamShot);
            MoveState sameTeamNoImm = at(t, 1120, 100, 64, 0);
            sameTeamNoImm.team = team;
            run(t, sameTeamNoImm);
            World ti = row("  r  ");
            ti.params.teamPlay = true;
            ti.params.teamImmunity = true;
            MoveState immune = at(ti, 1120, 100, 64, 0);
            immune.team = team;
            run(ti, immune);
            assertAll(
                () -> same(new int[] {64, 0, 0, 0, 0, 0}, noCrash, "objects that do not crash on cannons pass through"),
                () -> same(new int[] {64, 0, 0, 0, 0, 0}, fromCannon, "cannon shots pass cannons outside team play"),
                () -> same(new int[] {64, 0, 0, 0, 0, 0}, sameTeamShot, "team play: a cannon shot passes its team's cannon"),
                () -> assertEquals(0, sameTeamShot.cannon, "but the cannon is recorded"),
                () -> assertEquals(CRASH_CANNON, otherTeamShot.crash, "team play: another team's cannon shot crashes"),
                () -> assertEquals(CRASH_CANNON, sameTeamNoImm.crash, "team play without immunity: a team mate's shot crashes"),
                () -> same(new int[] {64, 0, 0, 0, 0, 0}, immune, "team immunity: a team mate's object passes"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("targets")
    class Targets {
        @Test
        @DisplayName("objects crash on targets; players bounce off them recording the target; team mates pass")
        void targets() {
            World w = row("  !  ");
            w.params.targetTeamCollision = true;
            MoveState o = run(w, obj(w, 2 * BC, BC + 1000, 1, 0, 64, 0));
            MoveState p = obj(w, 2 * BC, BC + 1000, 1, 0, 64, 0);
            p.pl = new Player(0);
            run(w, p);
            World nt = row("  !  ");
            nt.params.targetTeamCollision = false;
            int team = nt.modes.targetTeam[0];
            MoveState mate = obj(nt, 2 * BC, BC + 1000, 1, 0, 64, 0);
            mate.targetTeam = team;
            run(nt, mate);
            MoveState foe = obj(nt, 2 * BC, BC + 1000, 1, 0, 64, 0);
            foe.targetTeam = team + 1;
            run(nt, foe);
            MoveState matePl = obj(nt, 2 * BC, BC + 1000, 1, 0, 64, 0);
            matePl.pl = new Player(0);
            matePl.pl.team = team;
            matePl.team = team + 1;
            run(nt, matePl);
            assertAll(
                () -> assertEquals(CRASH_TARGET, o.crash, "an object crashes on the target"),
                () -> assertEquals(0, o.target, "with the target index recorded"),
                () -> assertEquals(BOUNCE_HOR_LO, p.bounce, "a player bounces off it like a wall"),
                () -> assertEquals(0, p.target, "and the target is recorded for the bounce cost"),
                () -> same(new int[] {64, 0, 0, 0, 0, 0}, mate, "without target team collision a team mate passes"),
                () -> assertEquals(CRASH_TARGET, foe.crash, "another team still crashes"),
                () -> same(new int[] {64, 0, 0, 0, 0, 0}, matePl, "a player's own team (pl.team) decides"));
        }
    }

    @Nested
    @DisplayName("treasures")
    class Treasures {
        @Test
        @DisplayName("solid lower half plus a ball of radius S/2; start, middle and end must all be clear")
        void treasure() {
            World w = row("  *  ");
            MoveState solid = at(w, 100, 2100, 100, 0);
            solid.treasureCrashes = false;
            run(w, solid);
            assertAll(
                () -> same(new int[] {100, 0, 0, 0, 0, 0}, run(w, at(w, 100, 2100, 100, 0)), "the upper corner is clear"),
                () -> assertEquals(CRASH_TREASURE, run(w, at(w, 100, 500, 100, 0)).crash, "the lower half crashes"),
                () -> assertEquals(0, run(w, at(w, 100, 500, 100, 0)).treasure, "with the treasure index recorded"),
                () -> assertEquals(CRASH_TREASURE, run(w, at(w, 300, 1900, 1640, 0)).crash,
                    "start and end outside the ball but the midpoint inside: crash"),
                () -> assertEquals(CRASH_TREASURE, run(w, at(w, 100, 1200, 0, -100)).crash,
                    "ending in the lower half crashes"),
                () -> assertEquals(CRASH_TREASURE, run(w, at(w, 600, 1500, 10, 0)).crash, "starting inside the ball crashes"),
                () -> assertEquals(CRASH_TREASURE, run(w, at(w, 100, 1900, 400, 0)).crash,
                    "start and midpoint outside but the end inside the ball: crash"),
                () -> assertEquals(CRASH_WALL, solid.crash, "for objects it does not crash it is a solid block"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("wormholes")
    class Wormholes {
        /** Normal wormholes at blocks (1,1) [index 0] and (3,1) [index 1]. */
        final World w = world("", "     ", " @ @ ", "     ");

        Player playerIn(int bx, int by) {
            Player p = new Player(0);
            p.setClicks(bx * BC + 1000, by * BC + 1000);
            return p;
        }

        @Test
        @DisplayName("a player warps only when its centre is in the wormhole block")
        void player() {
            MoveState in = obj(w, BC, BC + 1000, 1, 0, 64, 0);
            in.pl = playerIn(1, 1);
            run(w, in);
            MoveState outside = obj(w, BC, BC + 1000, 1, 0, 64, 0);
            outside.pl = playerIn(0, 1);
            run(w, outside);
            MoveState fresh = obj(w, BC, BC + 1000, 1, 0, 64, 0);
            fresh.pl = playerIn(1, 1);
            fresh.pl.wormHoleDest = 0;
            run(w, fresh);
            MoveState off = obj(w, BC, BC + 1000, 1, 0, 64, 0);
            off.pl = playerIn(1, 1);
            off.wormholeWarps = false;
            run(w, off);
            assertAll(
                () -> assertEquals(CRASH_WORMHOLE, in.crash, "centre in the hole: CrashWormHole"),
                () -> assertEquals(0, in.wormhole, "with the hole index"),
                () -> assertEquals(CRASH_WORMHOLE, fresh.crash, "the destination check applies only to WARPED players"),
                () -> same(new int[] {64, 0, 0, 0, 0, 0}, outside, "a hull point in the hole with the centre outside passes"),
                () -> same(new int[] {64, 0, 0, 0, 0, 0}, off, "no warping when wormhole_warps is off (turning)"));
        }

        @Test
        @DisplayName("a just-warped player is not warped again on its destination hole; WARPED clears elsewhere")
        void warped() {
            MoveState onDest = obj(w, BC, BC + 1000, 1, 0, 64, 0);
            onDest.pl = playerIn(1, 1);
            onDest.pl.status = Obj.WARPED;
            onDest.pl.wormHoleDest = 0;
            run(w, onDest);
            MoveState otherDest = obj(w, BC, BC + 1000, 1, 0, 64, 0);
            otherDest.pl = playerIn(1, 1);
            otherDest.pl.status = Obj.WARPED;
            otherDest.pl.wormHoleDest = 1;
            run(w, otherDest);
            MoveState notOnHole = obj(w, BC, BC + 1000, 1, 0, 64, 0);
            notOnHole.pl = playerIn(0, 1);
            notOnHole.pl.status = Obj.WARPED;
            notOnHole.pl.wormHoleDest = 0;
            run(w, notOnHole);
            assertAll(
                () -> same(new int[] {64, 0, 0, 0, 0, 0}, onDest, "still on the hole it arrived at: no warp"),
                () -> assertEquals(Obj.WARPED, onDest.pl.status, "and WARPED stays set"),
                () -> assertEquals(CRASH_WORMHOLE, otherDest.crash, "on another hole: warps"),
                () -> assertEquals(0, otherDest.pl.status, "WARPED is cleared"),
                () -> assertEquals(0, notOnHole.pl.status, "centre off any hole: WARPED is cleared"),
                () -> same(new int[] {64, 0, 0, 0, 0, 0}, notOnHole, "and the hull point passes"));
        }

        @Test
        @DisplayName("OUT wormholes never warp; IN wormholes do")
        void outHoles() {
            World io = world("", "     ", " ( ) ", "     ");
            MoveState out = obj(io, 3 * BC, BC + 1000, 1, 0, 64, 0);
            out.pl = playerIn(3, 1);
            run(io, out);
            MoveState in = obj(io, BC, BC + 1000, 1, 0, 64, 0);
            in.pl = playerIn(1, 1);
            run(io, in);
            assertAll(
                () -> same(new int[] {64, 0, 0, 0, 0, 0}, out, "an OUT hole is open space"),
                () -> assertEquals(CRASH_WORMHOLE, in.crash, "an IN hole warps"));
        }

        @Test
        @DisplayName("objects jump to the hole's last destination when it is usable")
        void objects() {
            int[][] cases = new int[6][];
            for (int c = 0; c < 6; c++) {
                World x = world("", "     ", " @ @ ", "     ");
                int h = x.objects.alloc();
                x.objects.setClicks(h, BC - 500, BC + 1000);
                x.params.wormTime = 0;
                x.wormLastDest[0] = c == 1 ? 0 : 1;
                if (c == 2) {
                    x.params.wormTime = 5;
                    x.wormCountdown[0] = 0;
                }
                if (c == 3) {
                    x.params.wormTime = 5;
                    x.wormCountdown[0] = 3;
                }
                if (c == 4) {
                    x.objects.setClicks(h, BC + 10, BC + 1000);
                }
                if (c == 5) {
                    x.wormLastDest[0] = -1;
                }
                MoveState ms = obj(x, BC, BC + 1000, 1, 0, 64, 0);
                ms.obj = h;
                run(x, ms);
                cases[c] = out(ms);
            }
            World inDest = world("", "     ", " @(@ ", "     ");
            int h = inDest.objects.alloc();
            inDest.objects.setClicks(h, BC - 500, BC + 1000);
            inDest.wormLastDest[0] = 1;
            MoveState toIn = obj(inDest, BC, BC + 1000, 1, 0, 64, 0);
            toIn.obj = h;
            run(inDest, toIn);
            World first = world("", "     ", " @ @ ", "     ");
            int hf = first.objects.alloc();
            first.objects.setClicks(hf, 3 * BC - 500, BC + 1000);
            first.params.wormTime = 0;
            first.wormLastDest[1] = 0;
            MoveState toFirst = obj(first, 3 * BC, BC + 1000, 1, 0, 64, 0);
            toFirst.obj = hf;
            run(first, toFirst);
            World diag = world("", " @   ", "     ", "   @ ");
            int hd = diag.objects.alloc();
            diag.objects.setClicks(hd, 3 * BC - 500, 1000);
            diag.params.wormTime = 0;
            diag.wormLastDest[1] = 0;
            MoveState toDiag = obj(diag, 3 * BC, 1000, 1, 0, 64, 0);
            toDiag.obj = hd;
            run(diag, toDiag);
            int[] plain = {64, 0, 0, 0, 0, 0};
            assertAll(
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {64 + 2 * BC, 0, 0, 0, 0, 0}, cases[0],
                    "done includes the jump (3-1)*BC to the last destination"),
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(plain, cases[1], "a hole whose last destination is itself: no jump"),
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(plain, cases[2], "wormTime set and the countdown over: no jump"),
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {64 + 2 * BC, 0, 0, 0, 0, 0}, cases[3],
                    "wormTime set and the countdown running: jump"),
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(plain, cases[4], "an object already in the hole block: no jump"),
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(plain, cases[5], "no destination yet: no jump"),
                () -> same(plain, toIn, "a destination that is an IN hole: no jump"),
                () -> same(new int[] {64 - 2 * BC, 0, 0, 0, 0, 0}, toFirst, "destination index 0 is valid: jump back 2 blocks"),
                () -> same(new int[] {64 - 2 * BC, 2 * BC, 0, 0, 0, 0}, toDiag, "the jump includes the y offset"));
        }
    }

    @Nested
    @DisplayName("diagonal edge cases")
    class DiagonalEdges {
        @Test
        @DisplayName("moving along a solid side of a diagonal (zero velocity across it) is a crash")
        void alongSolidSide() {
            World ld = row("  w  ");
            World lu = row("  s  ");
            World rd = row("  q  ");
            assertAll(
                () -> assertEquals(CRASH_WALL, run(ld, at(ld, 0, 1000, 0, 64)).crash, "REC_LD left side"),
                () -> assertEquals(CRASH_WALL, run(ld, at(ld, 1000, 0, 64, 0)).crash, "REC_LD bottom side"),
                () -> assertEquals(CRASH_WALL, run(lu, at(lu, 0, 1000, 0, 64)).crash, "REC_LU left side"),
                () -> assertEquals(CRASH_WALL, run(rd, at(rd, 1000, 0, 64, 0)).crash, "REC_RD bottom side"));
        }

        @Test
        @DisplayName("apexes with velocity exactly along the diagonal test are cleared (C >= / <=)")
        void apexEquality() {
            World ldBelow = world("", "     ", "  w  ", "  x  ");
            World luAbove = world("", "  x  ", "  s  ", "     ");
            World rdRight = row("  qx ");
            World ruRight = row("  ax ");
            assertAll(
                () -> same(new int[] {-64, 64, 0, 0, 0, 0}, run(ldBelow, obj(ldBelow, 3 * BC, BC, -1, 1, -64, 64)),
                    "REC_LD bottom-right apex, vel.x + vel.y = 0: cleared"),
                () -> same(new int[] {-64, -64, 0, 0, 0, 0}, run(luAbove, obj(luAbove, 3 * BC, 2 * BC, -1, -1, -64, -64)),
                    "REC_LU top-right apex, vel.x = vel.y: cleared"),
                () -> same(new int[] {-64, -64, 0, 0, 0, 0}, run(rdRight, obj(rdRight, 3 * BC, 2 * BC, -1, -1, -64, -64)),
                    "REC_RD top-right apex, vel.x = vel.y: cleared"),
                () -> same(new int[] {-64, 64, 0, 0, 0, 0}, run(ruRight, obj(ruRight, 3 * BC, BC, -1, 1, -64, 64)),
                    "REC_RU bottom-right apex, vel.x + vel.y = 0: cleared"));
        }
    }

    @Nested
    @DisplayName("phasing")
    class Phasing {
        @Test
        @DisplayName("a phased point ignores every block")
        void phased() {
            World w = row("  x  ");
            MoveState ms = at(w, 100, 100, 64, 0);
            ms.phased = true;
            run(w, ms);
            same(new int[] {64, 0, 0, 0, 0, 0}, ms, "moves through the inside of a wall");
        }
    }
}
