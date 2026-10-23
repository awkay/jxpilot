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

import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.fulcrologic.jxpilot.server.sim.MoveState.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C {@code Move_segment}, {@code Bounce_wall}, {@code Bounce_edge} (src/server/walls.c:341-1550). Positions in
 * clicks: a block is {@code BC} = 35*64 = 2240 clicks; block (bx, by) spans [bx*BC, (bx+1)*BC). Map rows are
 * written top first, so block y = height-1-row.
 */
class SegmentTest {
    static final int BC = Click.BLOCK_CLICKS;

    static World world(String extra, String... rows) {
        return new World(TestWorlds.options(extra, rows), 1);
    }

    /** A pool-object move state with every crash/bounce rule on. */
    static MoveState obj(World w, int x, int y, double vx, double vy, int tx, int ty) {
        MoveState ms = new MoveState();
        ms.edgeWrap = w.wrap;
        ms.edgeBounce = true;
        ms.wallBounce = true;
        ms.cannonCrashes = true;
        ms.targetCrashes = true;
        ms.treasureCrashes = true;
        ms.wormholeWarps = true;
        ms.team = 0;
        ms.start(x, y, vx, vy, tx, ty, 0);
        return ms;
    }

    static void move(World w, MoveState ms) {
        new Segment(w).move(ms);
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("filled blocks")
    class Filled {
        /** 5x3, wall at block (2,1). */
        final World w = world("", "     ", "  x  ", "     ");

        @Test
        @DisplayName("open space: the move stops at the block boundary and keeps the rest as todo")
        void stopsAtBoundary() {
            MoveState ms = obj(w, 2 * BC - 100, BC + 1000, 4.6875, 0, 300, 0);
            move(w, ms);
            assertAll(
                () -> assertEquals(100, ms.doneX, "moves the 100 clicks to the block boundary"),
                () -> assertEquals(0, ms.doneY, "no y movement"),
                () -> assertEquals(200, ms.todoX, "200 clicks remain"),
                () -> assertEquals(NOT_A_CRASH, ms.crash, "no crash in open space"),
                () -> assertEquals(NOT_A_BOUNCE, ms.bounce, "no bounce in open space"));
        }

        @Test
        @DisplayName("hitting the left face reflects x (BounceHorLo) and turns an object's heading RES/2 - dir")
        void bounceLeftFace() {
            MoveState ms = obj(w, 2 * BC, BC + 1000, 4.6875, 1, 300, 64);
            ms.dir = 10;
            move(w, ms);
            assertAll(
                () -> assertEquals(BOUNCE_HOR_LO, ms.bounce, "bounces off the low-x face"),
                () -> assertEquals(-300, ms.todoX, "todo x is negated"),
                () -> assertEquals(64, ms.todoY, "todo y is kept"),
                () -> assertEquals(-4.6875, ms.velX, "velocity x is negated"),
                () -> assertEquals(1.0, ms.velY, "velocity y is kept"),
                () -> assertEquals(54, ms.dir, "heading becomes MOD2(RES/2 - 10) = 54"),
                () -> assertEquals(0, ms.doneX | ms.doneY, "a bounce moves nothing"),
                () -> assertEquals(NOT_A_CRASH, ms.crash, "a bounce is not a crash"));
        }

        @Test
        @DisplayName("a player's heading is not changed by a bounce")
        void playerHeadingKept() {
            MoveState ms = obj(w, 2 * BC, BC + 1000, 4.6875, 0, 300, 0);
            ms.pl = new Player(0);
            ms.dir = 10;
            move(w, ms);
            assertAll(
                () -> assertEquals(BOUNCE_HOR_LO, ms.bounce, "bounces"),
                () -> assertEquals(10, ms.dir, "the move state heading of a player is untouched"));
        }

        @Test
        @DisplayName("without wall bouncing the same hit is CRASH_WALL and nothing changes")
        void crashWithoutBounce() {
            MoveState ms = obj(w, 2 * BC, BC + 1000, 4.6875, 0, 300, 0);
            ms.wallBounce = false;
            move(w, ms);
            assertAll(
                () -> assertEquals(CRASH_WALL, ms.crash, "crashes into the wall"),
                () -> assertEquals(NOT_A_BOUNCE, ms.bounce, "no bounce"),
                () -> assertEquals(300, ms.todoX, "todo is unchanged"),
                () -> assertEquals(4.6875, ms.velX, "velocity is unchanged"),
                () -> assertEquals(0, ms.doneX, "nothing moved"));
        }

        @Test
        @DisplayName("the other three faces give BounceHorHi, BounceVerHi and BounceVerLo")
        void otherFaces() {
            MoveState right = obj(w, 3 * BC, BC + 1000, -2, 0, -300, 0);
            move(w, right);
            MoveState top = obj(w, 2 * BC + 1000, 2 * BC, 0, -2, 0, -300);
            top.dir = 96;
            move(w, top);
            MoveState bottom = obj(w, 2 * BC + 1000, BC, 0, 2, 0, 300);
            bottom.dir = 32;
            move(w, bottom);
            assertAll(
                () -> assertEquals(BOUNCE_HOR_HI, right.bounce, "from the right: high-x face"),
                () -> assertEquals(300, right.todoX, "x todo reflected"),
                () -> assertEquals(64, right.dir, "heading 0 becomes RES/2"),
                () -> assertEquals(BOUNCE_VER_HI, top.bounce, "from above: high-y face"),
                () -> assertEquals(300, top.todoY, "y todo reflected"),
                () -> assertEquals(2.0, top.velY, "y velocity reflected"),
                () -> assertEquals(32, top.dir, "heading 96 becomes MOD2(RES - 96) = 32"),
                () -> assertEquals(BOUNCE_VER_LO, bottom.bounce, "from below: low-y face"),
                () -> assertEquals(-300, bottom.todoY, "y todo reflected"),
                () -> assertEquals(96, bottom.dir, "heading 32 becomes 96"));
        }

        @Test
        @DisplayName("a point strictly inside the block crashes")
        void insideCrashes() {
            MoveState ms = obj(w, 2 * BC + 100, BC + 100, -1, 0, -64, 0);
            move(w, ms);
            assertEquals(CRASH_WALL, ms.crash, "entering from inside is a crash");
        }

        @Test
        @DisplayName("on a face: moving along it or away from it is free, a todo across it without velocity crashes")
        void alongFace() {
            MoveState along = obj(w, 2 * BC, BC + 500, 0, 1, 0, 64);
            move(w, along);
            MoveState away = obj(w, 2 * BC, BC + 1000, -1, 0, -300, 0);
            move(w, away);
            MoveState still = obj(w, 2 * BC, BC + 1000, 0, 0, 0, 0);
            move(w, still);
            MoveState grazing = obj(w, 2 * BC, BC + 1000, 0, 1, 5, 64);
            move(w, grazing);
            MoveState bottomAlong = obj(w, 2 * BC + 500, BC, 1, 0, 64, 0);
            move(w, bottomAlong);
            assertAll(
                () -> assertEquals(64, along.doneY, "slides 64 clicks along the face"),
                () -> assertEquals(NOT_A_CRASH | NOT_A_BOUNCE, along.crash | along.bounce, "no crash or bounce sliding"),
                () -> assertEquals(-300, away.doneX, "moving away from the face is unobstructed"),
                () -> assertEquals(NOT_A_CRASH, still.crash, "no movement at all on the face is not a crash"),
                () -> assertEquals(0, still.doneX | still.doneY, "and moves nothing"),
                () -> assertEquals(CRASH_WALL, grazing.crash, "x todo into the face with zero x velocity crashes"),
                () -> assertEquals(64, bottomAlong.doneX, "sliding along the bottom face is free"),
                () -> assertEquals(NOT_A_CRASH, bottomAlong.crash, "no crash sliding along the bottom"));
        }

        @Test
        @DisplayName("fuel and (non-crashing) targets are solid squares too")
        void fuelAndTargetSolid() {
            World f = world("", "     ", "  #  ", "     ");
            MoveState fuel = obj(f, 2 * BC, BC + 1000, 2, 0, 300, 0);
            move(f, fuel);
            World t = world("", "     ", "  !  ", "     ");
            MoveState target = obj(t, 2 * BC, BC + 1000, 2, 0, 300, 0);
            target.targetCrashes = false;
            move(t, target);
            MoveState inTarget = obj(t, 2 * BC + 50, BC + 1000, 2, 0, 300, 0);
            inTarget.targetCrashes = false;
            move(t, inTarget);
            assertAll(
                () -> assertEquals(BOUNCE_HOR_LO, fuel.bounce, "a fuel block bounces like a wall"),
                () -> assertEquals(BOUNCE_HOR_LO, target.bounce, "a target that does not crash this object bounces it"),
                () -> assertEquals(-1, target.target, "and is not recorded as hit"),
                () -> assertEquals(CRASH_WALL, inTarget.crash, "inside a target block is a wall crash"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("bounce feasibility and choice")
    class Feasibility {
        @Test
        @DisplayName("a face covered by the neighbouring block is not bounceable: CRASH_WALL")
        void coveredFaceCrashes() {
            World w = world("", "     ", " xx  ", "     ");
            MoveState ms = obj(w, 2 * BC, BC + 1000, 2, 0, 300, 0);
            move(w, ms);
            assertEquals(CRASH_WALL, ms.crash, "the low-x face of (2,1) is covered by the wall at (1,1)");
        }

        @Test
        @DisplayName("diagonal neighbours cover a face only with their solid side")
        void diagonalNeighbours() {
            String[] covering = {"a", "q"};       // REC_RU, REC_RD: solid right side
            String[] open = {"w", "s"};           // REC_LD, REC_LU: open right side
            int[] crash = new int[2];
            int[] bounce = new int[2];
            for (int i = 0; i < 2; i++) {
                World w = world("", "     ", " " + covering[i] + "x  ", "     ");
                MoveState ms = obj(w, 2 * BC, BC + 1000, 2, 0, 300, 0);
                move(w, ms);
                crash[i] = ms.crash;
                World w2 = world("", "     ", " " + open[i] + "x  ", "     ");
                MoveState ms2 = obj(w2, 2 * BC, BC + 1000, 2, 0, 300, 0);
                move(w2, ms2);
                bounce[i] = ms2.bounce;
            }
            World up = world("", "  a  ", "  x  ", "     ");
            MoveState fromTop = obj(up, 2 * BC + 1000, 2 * BC, 0, -2, 0, -300);
            move(up, fromTop);
            World up2 = world("", "  q  ", "  x  ", "     ");
            MoveState fromTop2 = obj(up2, 2 * BC + 1000, 2 * BC, 0, -2, 0, -300);
            move(up2, fromTop2);
            World right = world("", "     ", "  xs ", "     ");
            MoveState fromRight = obj(right, 3 * BC, BC + 1000, -2, 0, -300, 0);
            move(right, fromRight);
            World right2 = world("", "     ", "  xa ", "     ");
            MoveState fromRight2 = obj(right2, 3 * BC, BC + 1000, -2, 0, -300, 0);
            move(right2, fromRight2);
            World below = world("", "     ", "  x  ", "  s  ");
            MoveState fromBelow = obj(below, 2 * BC + 1000, BC, 0, 2, 0, 300);
            move(below, fromBelow);
            World below2 = world("", "     ", "  x  ", "  w  ");
            MoveState fromBelow2 = obj(below2, 2 * BC + 1000, BC, 0, 2, 0, 300);
            move(below2, fromBelow2);
            assertAll(
                () -> assertEquals(CRASH_WALL, crash[0], "REC_RU on the left covers the low-x face"),
                () -> assertEquals(CRASH_WALL, crash[1], "REC_RD on the left covers the low-x face"),
                () -> assertEquals(BOUNCE_HOR_LO, bounce[0], "REC_LD on the left leaves the face open"),
                () -> assertEquals(BOUNCE_HOR_LO, bounce[1], "REC_LU on the left leaves the face open"),
                () -> assertEquals(BOUNCE_VER_HI, fromTop.bounce, "REC_RU above has an open bottom: the top face bounces"),
                () -> assertEquals(CRASH_WALL, fromTop2.crash, "REC_RD above covers the top face"),
                () -> assertEquals(CRASH_WALL, fromRight.crash, "REC_LU on the right covers the high-x face"),
                () -> assertEquals(BOUNCE_HOR_HI, fromRight2.bounce, "REC_RU on the right leaves it open"),
                () -> assertEquals(CRASH_WALL, fromBelow.crash, "REC_LU below has a solid top: it covers the bottom face"),
                () -> assertEquals(BOUNCE_VER_LO, fromBelow2.bounce, "REC_LD below leaves the bottom face open"));
        }

        @Test
        @DisplayName("targets and treasures cover a face only for objects they do not crash")
        void targetTreasureNeighbours() {
            World t = world("", "     ", " !x  ", "     ");
            MoveState crashes = obj(t, 2 * BC, BC + 1000, 2, 0, 300, 0);
            move(t, crashes);
            MoveState noCrash = obj(t, 2 * BC, BC + 1000, 2, 0, 300, 0);
            noCrash.targetCrashes = false;
            move(t, noCrash);
            World r = world("", "     ", " *x  ", "     ");
            MoveState tCrashes = obj(r, 2 * BC, BC + 1000, 2, 0, 300, 0);
            move(r, tCrashes);
            MoveState tNoCrash = obj(r, 2 * BC, BC + 1000, 2, 0, 300, 0);
            tNoCrash.treasureCrashes = false;
            move(r, tNoCrash);
            assertAll(
                () -> assertEquals(BOUNCE_HOR_LO, crashes.bounce, "a target that crashes this object is no cover"),
                () -> assertEquals(CRASH_WALL, noCrash.crash, "a target that does not crash it covers the face"),
                () -> assertEquals(BOUNCE_HOR_LO, tCrashes.bounce, "a treasure that crashes this object is no cover"),
                () -> assertEquals(CRASH_WALL, tNoCrash.crash, "a treasure that does not crash it covers the face"));
        }

        @Test
        @DisplayName("at the world edge a face is bounceable only when the map wraps (neighbour across the edge)")
        void edgeNeighbour() {
            World flat = world("", "     ", "x    ", "     ");
            MoveState f = obj(flat, 0, BC + 1000, 2, 0, 300, 0);
            move(flat, f);
            World wrap = world("edgeWrap: yes", "     ", "x    ", "     ");
            MoveState g = obj(wrap, 0, BC + 1000, 2, 0, 300, 0);
            move(wrap, g);
            World wrapCovered = world("edgeWrap: yes", "     ", "x   x", "     ");
            MoveState c = obj(wrapCovered, 0, BC + 1000, 2, 0, 300, 0);
            move(wrapCovered, c);
            World wrapHi = world("edgeWrap: yes", "     ", "    x", "     ");
            MoveState hi = obj(wrapHi, 0, BC + 1000, -2, 0, -300, 0);
            move(wrapHi, hi);
            World flatTop = world("", "  x  ", "     ", "     ");
            MoveState top = obj(flatTop, 2 * BC + 1000, 2 * BC, 0, 2, 0, 300);
            move(flatTop, top);
            World wrapTop = world("edgeWrap: yes", "  x  ", "     ", "     ");
            MoveState wtop = obj(wrapTop, 2 * BC + 1000, 2 * BC, 0, 2, 0, 300);
            move(wrapTop, wtop);
            MoveState wbot = obj(wrapTop, 2 * BC + 1000, 0, 0, -2, 0, -300);
            move(wrapTop, wbot);
            World wrapTopCovered = world("edgeWrap: yes", "  x  ", "     ", "  x  ");
            MoveState wbotc = obj(wrapTopCovered, 2 * BC + 1000, 0, 0, -2, 0, -300);
            move(wrapTopCovered, wbotc);
            World flatBottom = world("", "     ", "     ", "  x  ");
            MoveState fbot = obj(flatBottom, 2 * BC + 1000, BC, 0, -2, 0, -300);
            move(flatBottom, fbot);
            MoveState fbotUp = obj(flatBottom, 2 * BC + 1000, 0, 0, 2, 0, 300);
            move(flatBottom, fbotUp);
            assertAll(
                () -> assertEquals(CRASH_WALL, f.crash, "without wrap the low-x face of a block at x=0 is no bounce"),
                () -> assertEquals(BOUNCE_HOR_LO, g.bounce, "with wrap the neighbour is the last column (space)"),
                () -> assertEquals(CRASH_WALL, c.crash, "with wrap a wall in the last column covers it"),
                () -> assertEquals(BOUNCE_HOR_HI, hi.bounce, "with wrap the high-x neighbour of the last column is column 0"),
                () -> assertEquals(BOUNCE_VER_LO, top.bounce, "the low-y face of a top-row block has the row below as neighbour"),
                () -> assertEquals(BOUNCE_VER_LO, wtop.bounce, "same with wrap"),
                () -> assertEquals(BOUNCE_VER_HI, wbot.bounce,
                    "with wrap the top row's high-y face is reached from y=0 going down; its neighbour is row 0"),
                () -> assertEquals(CRASH_WALL, wbotc.crash, "with wrap a wall in row 0 covers that face"),
                () -> assertEquals(BOUNCE_VER_HI, fbot.bounce, "the top face of a bottom-row block bounces"),
                () -> assertEquals(CRASH_WALL, fbotUp.crash, "without wrap the low-y face of a bottom-row block is no bounce"));
        }

        @Test
        @DisplayName("a corner whose two faces are both covered becomes the diagonal bounce")
        void cornerToDiagonal() {
            // plus-shaped walls around block (2,2); diagonal blocks are open
            World w = world("", "     ", "  x  ", " xxx ", "  x  ", "     ");
            MoveState ld = obj(w, 2 * BC, 2 * BC, 1, 1, 64, 32);
            ld.dir = 16;
            move(w, ld);
            MoveState lu = obj(w, 2 * BC, 3 * BC, 1, -1, 64, -32);
            lu.dir = 112;
            move(w, lu);
            MoveState rd = obj(w, 3 * BC, 2 * BC, -1, 1, -64, 32);
            rd.dir = 48;
            move(w, rd);
            MoveState ru = obj(w, 3 * BC, 3 * BC, -1, -1, -64, -32);
            ru.dir = 80;
            move(w, ru);
            assertAll(
                () -> assertEquals(BOUNCE_LEFT_DOWN, ld.bounce, "lower-left corner: BounceLeftDown"),
                () -> assertEquals(-32, ld.todoX, "LeftDown: todo x = -todo y"),
                () -> assertEquals(-64, ld.todoY, "LeftDown: todo y = -todo x"),
                () -> assertEquals(-1.0, ld.velX, "LeftDown: vel x = -vel y"),
                () -> assertEquals(80, ld.dir, "LeftDown: heading MOD2(3RES/4 - 16) = 80"),
                () -> assertEquals(BOUNCE_LEFT_UP, lu.bounce, "upper-left corner: BounceLeftUp"),
                () -> assertEquals(-32, lu.todoX, "LeftUp: todo x = todo y"),
                () -> assertEquals(64, lu.todoY, "LeftUp: todo y = todo x"),
                () -> assertEquals(48, lu.dir, "LeftUp: heading MOD2(RES/4 - 112) = 48"),
                () -> assertEquals(BOUNCE_RIGHT_DOWN, rd.bounce, "lower-right corner: BounceRightDown"),
                () -> assertEquals(32, rd.todoX, "RightDown: todo x = todo y"),
                () -> assertEquals(-64, rd.todoY, "RightDown: todo y = todo x"),
                () -> assertEquals(112, rd.dir, "RightDown: heading MOD2(RES/4 - 48) = 112"),
                () -> assertEquals(BOUNCE_RIGHT_UP, ru.bounce, "upper-right corner: BounceRightUp"),
                () -> assertEquals(32, ru.todoX, "RightUp: todo x = -todo y"),
                () -> assertEquals(64, ru.todoY, "RightUp: todo y = -todo x"),
                () -> assertEquals(1.0, ru.velY, "RightUp: vel y = -vel x"),
                () -> assertEquals(16, ru.dir, "RightUp: heading MOD2(3RES/4 - 80) = 16"));
        }

        @Test
        @DisplayName("with two feasible faces at a corner one is chosen with rfrac(): (int)(rfrac*2) picks the bit")
        void randomChoice() {
            Set<Integer> seen = new HashSet<>();
            for (int seed = 1; seed <= 16; seed++) {
                World w = new World(TestWorlds.options("", "     ", "     ", "  x  ", "     ", "     "), seed);
                MoveState ms = obj(w, 2 * BC, 2 * BC, 1, 1, 64, 64);
                move(w, ms);
                int pick = (int) (new Rng(seed).nextFloat() * 2);
                int expected = pick == 0 ? BOUNCE_HOR_LO : BOUNCE_VER_LO;
                assertEquals(expected, ms.bounce, "seed " + seed + ": bounce number " + pick + " (low bit first)");
                seen.add(ms.bounce);
            }
            assertEquals(Set.of(BOUNCE_HOR_LO, BOUNCE_VER_LO), seen, "both faces get chosen for some seed");
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("world edges")
    class Edges {
        final World flat = world("", "   ", "   ", "   ");
        /** A wall in the middle keeps every other block at walldist 2 so moves are traced. */
        final World wrap = world("edgeWrap: yes", "   ", " x ", "   ");

        @Test
        @DisplayName("with edgeBounce the point is reflected off each edge (BounceEdge)")
        void edgeBounce() {
            int cw = flat.clickWidth;
            int ch = flat.clickHeight;
            MoveState left = obj(flat, 0, BC + 1000, -1, 0.5, -64, 32);
            left.dir = 60;
            move(flat, left);
            MoveState right = obj(flat, cw - 1, BC + 1000, 1, 0.5, 64, 32);
            right.dir = 4;
            move(flat, right);
            MoveState bottom = obj(flat, 1000, 0, 0.5, -1, 32, -64);
            bottom.dir = 100;
            move(flat, bottom);
            MoveState top = obj(flat, 1000, ch - 1, -0.5, 1, -32, 64);
            top.dir = 40;
            move(flat, top);
            assertAll(
                () -> assertEquals(BOUNCE_EDGE, left.bounce, "left edge: BounceEdge"),
                () -> assertEquals(64, left.todoX, "left edge reflects todo x"),
                () -> assertEquals(1.0, left.velX, "left edge reflects vel x"),
                () -> assertEquals(32, left.todoY, "left edge keeps todo y"),
                () -> assertEquals(4, left.dir, "left edge heading MOD2(RES/2 - 60) = 4"),
                () -> assertEquals(BOUNCE_EDGE, right.bounce, "right edge (x = width-1 moving right): BounceEdge"),
                () -> assertEquals(-64, right.todoX, "right edge reflects todo x"),
                () -> assertEquals(60, right.dir, "right edge heading MOD2(RES/2 - 4) = 60"),
                () -> assertEquals(BOUNCE_EDGE, bottom.bounce, "bottom edge: BounceEdge"),
                () -> assertEquals(64, bottom.todoY, "bottom edge reflects todo y"),
                () -> assertEquals(1.0, bottom.velY, "bottom edge reflects vel y"),
                () -> assertEquals(28, bottom.dir, "bottom edge heading MOD2(RES - 100) = 28"),
                () -> assertEquals(BOUNCE_EDGE, top.bounce, "top edge: BounceEdge"),
                () -> assertEquals(-64, top.todoY, "top edge reflects todo y"),
                () -> assertEquals(88, top.dir, "top edge heading MOD2(RES - 40) = 88"),
                () -> assertEquals(0, left.doneX | right.doneX | bottom.doneY | top.doneY, "an edge bounce moves nothing"));
        }

        @Test
        @DisplayName("without edgeBounce the motion across the edge stops and an object heads along the edge")
        void edgeStop() {
            int cw = flat.clickWidth;
            int ch = flat.clickHeight;
            MoveState left = obj(flat, 0, BC + 1000, -1, 0.5, -64, 32);
            left.edgeBounce = false;
            move(flat, left);
            MoveState right = obj(flat, cw - 1, BC + 1000, 1, -0.5, 64, -32);
            right.edgeBounce = false;
            move(flat, right);
            MoveState bottom = obj(flat, 1000, 0, -0.5, -1, -32, -64);
            bottom.edgeBounce = false;
            move(flat, bottom);
            MoveState top = obj(flat, 1000, ch - 1, 0.5, 1, 32, 64);
            top.edgeBounce = false;
            top.dir = 7;
            move(flat, top);
            MoveState player = obj(flat, 0, BC + 1000, -1, 0.5, -64, 32);
            player.edgeBounce = false;
            player.pl = new Player(0);
            player.dir = 50;
            move(flat, player);
            assertAll(
                () -> assertEquals(0, left.todoX, "left: todo x is zeroed"),
                () -> assertEquals(0.0, left.velX, "left: vel x is zeroed"),
                () -> assertEquals(32, left.todoY, "left: todo y is kept"),
                () -> assertEquals(32, left.dir, "left with vel y >= 0: heading RES/4"),
                () -> assertEquals(96, right.dir, "right with vel y < 0: heading 3RES/4"),
                () -> assertEquals(0, right.todoX, "right: todo x is zeroed"),
                () -> assertEquals(0, bottom.todoY, "bottom: todo y is zeroed"),
                () -> assertEquals(0.0, bottom.velY, "bottom: vel y is zeroed"),
                () -> assertEquals(64, bottom.dir, "bottom with vel x < 0: heading RES/2"),
                () -> assertEquals(0, top.dir, "top with vel x >= 0: heading 0"),
                () -> assertEquals(BOUNCE_EDGE, top.bounce, "stopping at the edge is still BounceEdge"),
                () -> assertEquals(50, player.dir, "a player's heading is not touched"));
        }

        @Test
        @DisplayName("moving right onto the last click column: leave.x is pulled back one click")
        void lastClick() {
            int cw = flat.clickWidth;
            int ch = flat.clickHeight;
            MoveState ms = obj(flat, cw - 240, BC + 1000, 1, 0, 240, 0);
            move(flat, ms);
            MoveState up = obj(flat, 1000, ch - 240, 0, 1, 0, 240);
            move(flat, up);
            MoveState wrapped = obj(wrap, cw - 240, BC + 1000, 1, 0, 240, 0);
            move(wrap, wrapped);
            assertAll(
                () -> assertEquals(239, ms.doneX, "stops at x = width-1: 239 of 240 clicks"),
                () -> assertEquals(1, ms.todoX, "one click remains"),
                () -> assertEquals(239, up.doneY, "same at the top edge"),
                () -> assertEquals(2, wrap.wallDist(2, 1), "(fixture: traced, not fast-pathed)"),
                () -> assertEquals(240, wrapped.doneX, "with wrap the full 240 clicks are moved"));
        }

        @Test
        @DisplayName("with wrap a point at x=0 moving left continues in the last column; positions outside are wrapped")
        void wrapping() {
            int cw = wrap.clickWidth;
            int ch = wrap.clickHeight;
            MoveState ms = obj(wrap, 0, BC + 1000, -1, 0, -64, 0);
            move(wrap, ms);
            MoveState down = obj(wrap, 1000, 0, 0, -1, 0, -64);
            move(wrap, down);
            MoveState outside = obj(wrap, -64, BC + 1000, -1, 0, -64, 0);
            move(wrap, outside);
            MoveState outHigh = obj(wrap, cw + 5, ch + 7, 1, 1, 10, 10);
            move(wrap, outHigh);
            MoveState outLowY = obj(wrap, 5, -7, 1, -1, 10, -10);
            move(wrap, outLowY);
            assertAll(
                () -> assertEquals(-64, ms.doneX, "moves 64 clicks into the wrapped column"),
                () -> assertEquals(NOT_A_BOUNCE, ms.bounce, "no edge bounce with wrap"),
                () -> assertEquals(-64, down.doneY, "same through the bottom edge"),
                () -> assertEquals(cw - 64, outside.posX, "x = -64 is wrapped to width - 64"),
                () -> assertEquals(-64, outside.doneX, "and moves on"),
                () -> assertEquals(5, outHigh.posX, "x = width + 5 is wrapped to 5"),
                () -> assertEquals(7, outHigh.posY, "y = height + 7 is wrapped to 7"),
                () -> assertEquals(ch - 7, outLowY.posY, "y = -7 is wrapped to height - 7"));
        }

        @Test
        @DisplayName("CRASH_UNIVERSE outside the world without wrap, or more than one world away with wrap")
        void universe() {
            int cw = wrap.clickWidth;
            int ch = wrap.clickHeight;
            MoveState a = obj(flat, -1, 100, 1, 0, 64, 0);
            move(flat, a);
            MoveState b = obj(flat, 100, flat.clickHeight, 1, 0, 64, 0);
            move(flat, b);
            MoveState c = obj(wrap, -cw - 1, 100, 1, 0, 64, 0);
            move(wrap, c);
            MoveState d = obj(wrap, 2 * cw, 100, 1, 0, 64, 0);
            move(wrap, d);
            MoveState e = obj(wrap, 100, -ch - 1, 1, 0, 64, 0);
            move(wrap, e);
            MoveState f = obj(wrap, 100, 2 * ch, 1, 0, 64, 0);
            move(wrap, f);
            MoveState g = obj(flat, flat.clickWidth, 100, 1, 0, 64, 0);
            move(flat, g);
            MoveState h = obj(flat, 100, -1, 1, 0, 64, 0);
            move(flat, h);
            assertAll(
                () -> assertEquals(CRASH_UNIVERSE, a.crash, "x = -1 without wrap"),
                () -> assertEquals(CRASH_UNIVERSE, b.crash, "y = height without wrap"),
                () -> assertEquals(CRASH_UNIVERSE, g.crash, "x = width without wrap"),
                () -> assertEquals(CRASH_UNIVERSE, h.crash, "y = -1 without wrap"),
                () -> assertEquals(CRASH_UNIVERSE, c.crash, "x below -width with wrap"),
                () -> assertEquals(CRASH_UNIVERSE, d.crash, "x at 2*width with wrap"),
                () -> assertEquals(CRASH_UNIVERSE, e.crash, "y below -height with wrap"),
                () -> assertEquals(CRASH_UNIVERSE, f.crash, "y at 2*height with wrap"),
                () -> assertEquals(0, a.doneX | c.doneX, "nothing moves"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("block boundary arithmetic")
    class Boundary {
        final World w = world("", "x    ", "     ", "     ");

        @Test
        @DisplayName("a diagonal move leaving the block is cut where the first coordinate leaves, rounded half away")
        void needAdjust() {
            MoveState a = obj(w, 2000, 1000, 2, 1, 480, 240);
            move(w, a);
            MoveState b = obj(w, 2000, 1000, 2, 1, 480, 241);
            move(w, b);
            MoveState c = obj(w, BC + 240, 1000, -2, -1, -480, -241);
            move(w, c);
            MoveState d = obj(w, 1000, 2000, 1, 2, 240, 480);
            move(w, d);
            MoveState e = obj(w, 1000, 2000, 0, 2, 0, 480);
            move(w, e);
            assertAll(
                () -> assertEquals(240, a.doneX, "x leaves after 240 clicks"),
                () -> assertEquals(120, a.doneY, "y = 240 * 240/480 = 120"),
                () -> assertEquals(121, b.doneY, "y = 241 * 0.5 = 120.5 rounds to 121"),
                () -> assertEquals(-240, c.doneX, "moving left x leaves after 240 clicks"),
                () -> assertEquals(-121, c.doneY, "y = -120.5 rounds away from zero to -121"),
                () -> assertEquals(120, d.doneX, "y leaves first: x = 240 * 240/480 = 120"),
                () -> assertEquals(240, d.doneY, "y moves the 240 clicks to the boundary"),
                () -> assertEquals(240, e.doneY, "an axis-aligned move needs no adjustment"),
                () -> assertEquals(240, e.todoY, "and keeps the rest"));
        }

        @Test
        @DisplayName("far from walls (walldist > 2) the move goes up to (walldist-2)*BC/2 clicks in one step")
        void wallDistFastPath() {
            String e = "         ";
            World open = world("", e, e, e, e, e, e, e, e, e);
            int cx = 4 * BC + 1000;
            int cy = 4 * BC + 1000;
            MoveState whole = obj(open, cx, cy, 1, 1, 100, 50);
            move(open, whole);
            MoveState horiz = obj(open, cx, cy, 1, 1, 10000, 5000);
            move(open, horiz);
            MoveState vert = obj(open, cx, cy, 1, -1, 3000, -10000);
            move(open, vert);
            MoveState equal = obj(open, cx, cy, -1, 1, -9000, 9000);
            move(open, equal);
            assertAll(
                () -> assertEquals(10, open.wallDist(4, 4), "(fixture: the centre of a 9x9 map has walldist 10)"),
                () -> assertEquals(100, whole.doneX, "a short move is done entirely: x"),
                () -> assertEquals(50, whole.doneY, "a short move is done entirely: y"),
                () -> assertEquals(0, whole.todoX | whole.todoY, "nothing remains"),
                () -> assertEquals(8960, horiz.doneX, "horizontal-major: x = maxcl = (10-2)*2240/2 = 8960"),
                () -> assertEquals(4480, horiz.doneY, "y = 5000 * 8960 / 10000 = 4480"),
                () -> assertEquals(1040, horiz.todoX, "x todo keeps the rest"),
                () -> assertEquals(520, horiz.todoY, "y todo keeps the rest"),
                () -> assertEquals(2688, vert.doneX, "vertical-major: x = 3000 * 8960 / 10000 = 2688"),
                () -> assertEquals(-8960, vert.doneY, "y = -maxcl"),
                () -> assertEquals(-8960, equal.doneX, "equal magnitudes take the vertical branch: x = -9000*8960/9000"),
                () -> assertEquals(8960, equal.doneY, "y = maxcl"));
        }

        @Test
        @DisplayName("walldist 4 allows one block in one step; walldist 2 is traced block by block")
        void wallDistSmall() {
            String e = "     ";
            World small = world("", e, e, e, e, e);
            MoveState fast = obj(small, BC + 1000, BC + 1000, 1, 0, 2000, 0);
            move(small, fast);
            MoveState traced = obj(small, 1000, 1000, 1, 0, 2000, 0);
            move(small, traced);
            assertAll(
                () -> assertEquals(4, small.wallDist(1, 1), "(fixture: block (1,1) of a 5x5 map has walldist 4)"),
                () -> assertEquals(2000, fast.doneX, "maxcl (4-2)*2240/2 = 2240 covers the whole 2000 across the boundary"),
                () -> assertEquals(1240, traced.doneX, "at walldist 2 the move stops at the block boundary"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("boundary cases")
    class Boundaries {
        final World flat = world("", "     ", "  x  ", "     ");
        final World wrap = world("edgeWrap: yes", "   ", " x ", "   ");

        @Test
        @DisplayName("edge stop with zero velocity along the edge heads RES/4 (left edge) or 0 (bottom edge)")
        void edgeStopZero() {
            MoveState left = obj(flat, 0, BC + 1000, -1, 0, -64, 0);
            left.edgeBounce = false;
            move(flat, left);
            MoveState bottom = obj(flat, 1000, 0, 0, -1, 0, -64);
            bottom.edgeBounce = false;
            move(flat, bottom);
            assertAll(
                () -> assertEquals(32, left.dir, "vel.y = 0 is not < 0: RES/4"),
                () -> assertEquals(0, bottom.dir, "vel.x = 0 is not < 0: heading 0"));
        }

        @Test
        @DisplayName("wrapping exactly one world: x 0 stays, -width and width become 0, same for y")
        void exactWrap() {
            int cw = wrap.clickWidth;
            int ch = wrap.clickHeight;
            MoveState a = obj(wrap, 0, -10, 1, 1, 10, 10);
            move(wrap, a);
            MoveState b = obj(wrap, -cw, 100, 1, 1, 10, 10);
            move(wrap, b);
            MoveState c = obj(wrap, cw, 100, 1, 1, 10, 10);
            move(wrap, c);
            MoveState d = obj(wrap, 100, -ch, 1, 1, 10, 10);
            move(wrap, d);
            MoveState e = obj(wrap, 100, ch, 1, 1, 10, 10);
            move(wrap, e);
            MoveState f = obj(wrap, -10, 0, 1, 1, 10, 10);
            move(wrap, f);
            assertAll(
                () -> assertEquals(0, a.posX, "x = 0 is not wrapped when only y is outside"),
                () -> assertEquals(ch - 10, a.posY, "y = -10 wraps"),
                () -> assertEquals(0, b.posX, "x = -width wraps to 0"),
                () -> assertEquals(NOT_A_CRASH, b.crash, "and is no universe crash"),
                () -> assertEquals(0, c.posX, "x = width wraps to 0"),
                () -> assertEquals(0, d.posY, "y = -height wraps to 0"),
                () -> assertEquals(0, e.posY, "y = height wraps to 0"),
                () -> assertEquals(0, f.posY, "y = 0 is not wrapped when only x is outside"),
                () -> assertEquals(cw - 10, f.posX, "x = -10 wraps"));
        }

        @Test
        @DisplayName("block boundaries: leaving block 1 into block 0 is free; zero velocity at the last click is no edge")
        void blockZeroAndLastClick() {
            int cw = flat.clickWidth;
            int ch = flat.clickHeight;
            MoveState left = obj(flat, BC, BC + 1000, -1, 0, -64, 0);
            move(flat, left);
            MoveState down = obj(flat, 1000, BC, 0, -1, 0, -64);
            move(flat, down);
            MoveState right = obj(flat, cw - 1, BC + 1000, 0, 1, 0, 64);
            move(flat, right);
            MoveState top = obj(flat, 1000, ch - 1, 1, 0, 64, 0);
            move(flat, top);
            assertAll(
                () -> assertEquals(-64, left.doneX, "from x = BC moving left into block 0"),
                () -> assertEquals(-64, down.doneY, "from y = BC moving down into block 0"),
                () -> assertEquals(64, right.doneY, "at x = width-1 with vel.x = 0: moves along the edge"),
                () -> assertEquals(64, top.doneX, "at y = height-1 with vel.y = 0: moves along the edge"));
        }

        @Test
        @DisplayName("moving down onto a wall stops on its top face; up onto one stops on its bottom face")
        void stopOnFace() {
            MoveState down = obj(flat, 2 * BC + 1000, 2 * BC + 100, 0, -1, 0, -300);
            move(flat, down);
            MoveState up = obj(flat, 2 * BC + 1000, BC - 100, 0, 1, 0, 300);
            move(flat, up);
            assertAll(
                () -> assertEquals(-100, down.doneY, "100 clicks to the face"),
                () -> assertEquals(-200, down.todoY, "200 left"),
                () -> assertEquals(100, up.doneY, "100 clicks to the face"),
                () -> assertEquals(200, up.todoY, "200 left"));
        }

        @Test
        @DisplayName("resolve: the face of block 1 toward block 0 is checked against block 0 (not the edge)")
        void neighbourZero() {
            World w = world("", "     ", " x   ", "     ");
            MoveState ms = obj(w, BC, BC + 1000, 1, 0, 64, 0);
            move(w, ms);
            World t = world("", "     ", "     ", " x   ");
            MoveState up = obj(t, BC + 1000, 0, 0, 1, 0, 64);
            move(t, up);
            World wv = world("edgeWrap: yes", "x    ", "     ", "  x  ");
            MoveState wup = obj(wv, 2 * BC + 1000, 0, 0, 1, 0, 64);
            move(wv, wup);
            World wv2 = world("edgeWrap: yes", "  x  ", "     ", "  x  ");
            MoveState wup2 = obj(wv2, 2 * BC + 1000, 0, 0, 1, 0, 64);
            move(wv2, wup2);
            World hi = world("edgeWrap: yes", "x    ", "    x", "     ");
            MoveState fromLeft = obj(hi, 0, BC + 1000, -2, 0, -300, 0);
            move(hi, fromLeft);
            assertAll(
                () -> assertEquals(BOUNCE_HOR_LO, ms.bounce, "block 0 is space: bounce"),
                () -> assertEquals(CRASH_WALL, up.crash, "row 0's low face without wrap: no neighbour, crash"),
                () -> assertEquals(BOUNCE_VER_LO, wup.bounce, "with wrap row 0's low face has the top row as neighbour"),
                () -> assertEquals(CRASH_WALL, wup2.crash, "a wall in the top row covers it"),
                () -> assertEquals(BOUNCE_HOR_HI, fromLeft.bounce,
                    "the last column's high face neighbour is column 0 of the same row (space), not of the next row"));
        }

        @Test
        @DisplayName("C quirk: sliding along a column of walls with zero velocity across it crashes at the block joint")
        void slideJoint() {
            World w = world("", "     ", "  x  ", "  x  ", "     ");
            MoveState ms = obj(w, 2 * BC, 2 * BC - 7, 0, 37, 0, 37);
            move(w, ms);
            int[] first = {ms.doneX, ms.doneY, ms.crash};
            ms.posY += ms.doneY;
            move(w, ms);
            assertAll(
                () -> org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {0, 7, NOT_A_CRASH}, first,
                    "the slide along the lower block's face reaches the joint"),
                () -> assertEquals(CRASH_WALL, ms.crash,
                    "at the joint the upper block's low face is flagged (vel.y > 0) and covered by the lower block"));
        }

        @Test
        @DisplayName("a single feasible bounce consumes no rfrac()")
        void singleBounceNoRandom() {
            World w = world("", "     ", "  x  ", "     ");
            move(w, obj(w, 2 * BC, BC + 1000, 1, 0, 64, 0));
            assertEquals(new com.fulcrologic.jxpilot.common.math.Rng(1).nextFloat(), w.rfrac(),
                "the world RNG is untouched");
        }
    }
}
