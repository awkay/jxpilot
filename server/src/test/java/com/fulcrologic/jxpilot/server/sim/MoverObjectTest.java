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
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.common.sim.Click.BLOCK_CLICKS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Mover#moveObject}: C {@code Move_object} (walls.c:1905-2023).
 * Worlds run at 60 fps unless stated (one tick is one C frame at any fps).
 */
class MoverObjectTest {
    static final int BC = BLOCK_CLICKS;
    static final String FPS60 = "framesPerSecond: 60";
    static final String E9 = "         ";

    /** A 9x9 empty map: block (4,4) has walldist 10, so free flight up to ((10-2)*35)>>1 = 140 px. */
    static Simulation open(String extra) {
        return TestWorlds.sim(extra, E9, E9, E9, E9, E9, E9, E9, E9, E9);
    }

    static int spawn(World w, int type, int cx, int cy, double vx, double vy) {
        Objects o = w.objects;
        int h = o.alloc();
        o.type[h] = type;
        o.setClicks(h, cx, cy);
        o.vx[h] = (float) vx;
        o.vy[h] = (float) vy;
        o.life[h] = 100;
        return h;
    }

    @Nested
    @DisplayName("free flight")
    class FreeFlight {
        @Test
        @DisplayName("moves FLOAT_TO_CLICK(vel) clicks a tick, truncated toward zero like C")
        void truncates() {
            Simulation s = open(FPS60);
            World w = s.world;
            int x0 = 4 * BC + 1000;
            int y0 = 4 * BC + 1000;
            // 1.0078125 px = 64.5 clicks; -0.5078125 px = -32.5 clicks (exact binary fractions)
            int h = spawn(w, Obj.SHOT, x0, y0, 1.0078125, -0.5078125);
            s.mover.moveObject(h);
            int x1 = w.objects.cx[h];
            int y1 = w.objects.cy[h];
            int px1 = w.objects.px[h];
            s.mover.moveObject(h);
            assertAll(
                () -> assertEquals(x0 + 64, x1, "tick 1: (int)64.5 = 64 clicks"),
                () -> assertEquals(y0 - 32, y1, "tick 1: (int)-32.5 = -32 clicks"),
                () -> assertEquals(x0 + 128, w.objects.cx[h], "tick 2: 64 again, the half click is lost as in C"),
                () -> assertEquals(y0 - 64, w.objects.cy[h], "tick 2: -32 again"),
                () -> assertEquals(px1, w.objects.prevX[h], "the previous position is the position before the move"),
                () -> assertEquals((x0 + 128) >> 6, w.objects.px[h], "pixels follow clicks"));
        }

        @Test
        @DisplayName("a moved object joins its cell last (C Cell_add_object), in free flight and when traced")
        void joinsCell() {
            Simulation s = open(FPS60);
            World w = s.world;
            Objects o = w.objects;
            int free = spawn(w, Obj.SHOT, 4 * BC + 1000, 4 * BC + 1000, 1, 0);
            int traced = spawn(w, Obj.SHOT, 100, 4 * BC + 1000, -1, 0);
            int other = spawn(w, Obj.SHOT, 4 * BC + 1000, 4 * BC + 1000, 0, 0);
            s.mover.moveObject(free);
            long afterFree = o.cellSeq[free];
            s.mover.moveObject(traced);
            assertAll(
                () -> assertTrue(afterFree > o.cellSeq[other], "free flight"),
                () -> assertTrue(o.cellSeq[traced] > afterFree, "near a wall (traced through the blocks)"));
        }

        @Test
        @DisplayName("at 14 fps a tick moves one whole frame")
        void wholeFrame() {
            Simulation s = open("");
            World w = s.world;
            int x0 = 4 * BC + 1000;
            int h = spawn(w, Obj.SHOT, x0, 4 * BC + 1000, 3.0, 0);
            for (int i = 0; i < 3; i++) {
                s.mover.moveObject(h);
            }
            assertAll(
                () -> assertEquals(14, w.params.fps, "(fixture: 14 fps)"),
                () -> assertEquals(x0 + 3 * 192, w.objects.cx[h], "three ticks move 3 * 3 px"));
        }

        @Test
        @DisplayName("on a wrapping map the position wraps across the edge")
        void wraps() {
            Simulation s = open(FPS60 + "\nedgeWrap: yes");
            World w = s.world;
            int h = spawn(w, Obj.SHOT, 10, 4 * BC, -1, 0);
            int g = spawn(w, Obj.SHOT, 4 * BC, w.clickHeight - 10, 0, 1);
            s.mover.moveObject(h);
            s.mover.moveObject(g);
            assertAll(
                () -> assertEquals(w.clickWidth - 54, w.objects.cx[h], "x = 10 - 64 wraps to width - 54"),
                () -> assertEquals(54, w.objects.cy[g], "y = height - 10 + 64 wraps to 54"));
        }
    }

    @Nested
    @DisplayName("walls")
    class Walls {
        /** 5x3, wall at block (2,1), objects start 100 clicks left of its face moving right 320 clicks. */
        Simulation wall() {
            return TestWorlds.sim(FPS60, "     ", "  x  ", "     ");
        }

        @Test
        @DisplayName("an object that does not bounce crashes at the face: life 0, position on the face")
        void crash() {
            Simulation s = wall();
            World w = s.world;
            w.params.objBounceMask = 0;
            int h = spawn(w, Obj.SHOT, 2 * BC - 100, BC + 1000, 5, 0);
            s.mover.moveObject(h);
            assertAll(
                () -> assertEquals(0.0, w.objects.life[h], "Object_crash sets life to 0"),
                () -> assertEquals(2 * BC, w.objects.cx[h], "it stops on the wall face"),
                () -> assertEquals(5.0f, w.objects.vx[h], "velocity is unchanged"),
                () -> assertEquals((2 * BC) >> 6, w.objects.prevX[h], "a crash makes the new position the previous one"));
        }

        @Test
        @DisplayName("a bouncing spark: life *= lifeFactor (truncated), brakes, loses owner immunity, heading reflects")
        void bounce() {
            Simulation s = wall();
            World w = s.world;
            Params p = w.params;
            p.objBounceMask = Obj.SPARK;
            p.objectWallBounceLifeFactor = 0.333f;
            p.objectWallBrakeFactor = 0.5f;
            p.maxObjectWallBounceSpeed = 10;
            int h = spawn(w, Obj.SPARK, 2 * BC - 100, BC + 1000, 5, 0);
            w.objects.status[h] = Obj.OWNERIMMUNE | Obj.GRAVITY;
            s.mover.moveObject(h);
            // 320 clicks: 100 to the face, bounce, the remaining 220 reflected and halved: -110
            assertAll(
                () -> assertEquals(2 * BC - 110, w.objects.cx[h], "100 in, bounce, (int)(-220 * 0.5) = 110 back out"),
                () -> assertEquals(33.0, w.objects.life[h], "life (long)(100 * 0.333) = 33"),
                () -> assertEquals(-2.5, w.objects.vx[h], "velocity reflected and multiplied by the brake factor"),
                () -> assertEquals(Obj.GRAVITY, w.objects.status[h], "OWNERIMMUNE is cleared, other bits kept"),
                () -> assertEquals(64, w.objects.missileDir[h], "heading 0 reflects to RES/2"),
                () -> assertEquals((2 * BC - 110) >> 6, w.objects.prevX[h], "and remembers the new position"));
        }

        @Test
        @DisplayName("sparks from a ship bounce (FROMBOUNCE) and non-sparks keep owner immunity")
        void keepsImmunity() {
            Simulation s = wall();
            World w = s.world;
            w.params.objBounceMask = Obj.SPARK | Obj.DEBRIS;
            w.params.maxObjectWallBounceSpeed = 10;
            int a = spawn(w, Obj.SPARK, 2 * BC - 100, BC + 1000, 5, 0);
            w.objects.status[a] = Obj.OWNERIMMUNE | Obj.FROMBOUNCE;
            int b = spawn(w, Obj.DEBRIS, 2 * BC - 100, BC + 500, 5, 0);
            w.objects.status[b] = Obj.OWNERIMMUNE;
            s.mover.moveObject(a);
            s.mover.moveObject(b);
            assertAll(
                () -> assertEquals(Obj.OWNERIMMUNE | Obj.FROMBOUNCE, w.objects.status[a], "a FROMBOUNCE spark stays immune"),
                () -> assertEquals(Obj.OWNERIMMUNE, w.objects.status[b], "debris stays immune"),
                () -> assertTrue(w.objects.vx[a] < 0 && w.objects.vx[b] < 0, "both bounced"));
        }

        @Test
        @DisplayName("bouncing faster than maxObjectWallBounceSpeed kills; a life factor of 0 kills")
        void killedByBounce() {
            Simulation s = wall();
            World w = s.world;
            w.params.objBounceMask = Obj.SPARK;
            w.params.maxObjectWallBounceSpeed = 4.99f;
            int fast = spawn(w, Obj.SPARK, 2 * BC - 100, BC + 1000, 5, 0);
            s.mover.moveObject(fast);
            Simulation s2 = wall();
            World w2 = s2.world;
            w2.params.objBounceMask = Obj.SPARK;
            w2.params.maxObjectWallBounceSpeed = 5;
            w2.params.objectWallBounceLifeFactor = 1;
            w2.params.objectWallBrakeFactor = 1;
            int exact = spawn(w2, Obj.SPARK, 2 * BC - 100, BC + 1000, 5, 0);
            s2.mover.moveObject(exact);
            Simulation s3 = wall();
            World w3 = s3.world;
            w3.params.objBounceMask = Obj.SPARK;
            w3.params.maxObjectWallBounceSpeed = 10;
            w3.params.objectWallBounceLifeFactor = 0.001f;
            int shortLived = spawn(w3, Obj.SPARK, 2 * BC - 100, BC + 1000, 5, 0);
            s3.mover.moveObject(shortLived);
            assertAll(
                () -> assertEquals(0.0, w.objects.life[fast], "speed 5 > 4.99: life 0"),
                () -> assertEquals(2 * BC, w.objects.cx[fast], "it stays on the face"),
                () -> assertEquals(-5.0, w.objects.vx[fast], "with the reflected, unbraked velocity"),
                () -> assertEquals(100.0, w2.objects.life[exact], "speed exactly at the limit survives"),
                () -> assertEquals(2 * BC - 220, w2.objects.cx[exact], "and moves the full remaining 220 back"),
                () -> assertEquals(0.0, w3.objects.life[shortLived], "(long)(100 * 0.001) = 0: dead"),
                () -> assertEquals(2 * BC, w3.objects.cx[shortLived], "and stops at the face"));
        }

        @Test
        @DisplayName("an edge bounce costs no life and no braking")
        void edgeBounce() {
            Simulation s = TestWorlds.sim(FPS60, "x    ", "     ", "     ");
            World w = s.world;
            w.params.objBounceMask = Obj.SPARK;
            w.params.objectWallBounceLifeFactor = 0.5f;
            w.params.objectWallBrakeFactor = 0.5f;
            int h = spawn(w, Obj.SPARK, 100, 1000, -5, 0);
            s.mover.moveObject(h);
            assertAll(
                () -> assertEquals(100.0, w.objects.life[h], "life is unchanged"),
                () -> assertEquals(5.0, w.objects.vx[h], "velocity is reflected without braking"),
                () -> assertEquals(220, w.objects.cx[h], "100 to the edge, then 220 back: x = 220"));
        }

        @Test
        @DisplayName("five consecutive steps without progress are CRASH_UNKNOWN (life 0)")
        void stuck() {
            // walls at (1,1),(2,1),(1,2),(2,2): from their common corner every bounce leads into another
            Simulation s = TestWorlds.sim(FPS60, "    ", " xx ", " xx ", "    ");
            World w = s.world;
            Params p = w.params;
            p.objBounceMask = Obj.SPARK;
            p.objectWallBounceLifeFactor = 1;
            p.objectWallBrakeFactor = 1;
            p.maxObjectWallBounceSpeed = 100;
            int h = spawn(w, Obj.SPARK, 2 * BC, 2 * BC, 1, 1);
            s.mover.moveObject(h);
            assertAll(
                () -> assertEquals(0.0, w.objects.life[h], "the object is removed"),
                () -> assertEquals(2 * BC, w.objects.cx[h], "it never moved"),
                () -> assertEquals(-1.0, w.objects.vx[h], "after 5 diagonal bounces (odd) the velocity is reversed"));
        }

        @Test
        @DisplayName("a shot hitting a cannon destroys it (CRASH_CANNON -> Cannon_dies)")
        void cannon() {
            Simulation s = TestWorlds.sim(FPS60, "     ", "  r  ", "     ");
            World w = s.world;
            int h = spawn(w, Obj.SHOT, 2 * BC - 100, BC + 100, 5, 0);
            s.mover.moveObject(h);
            assertAll(
                () -> assertEquals(0.0, w.objects.life[h], "the shot is gone"),
                () -> assertTrue(w.cannonDeadTime[0] > 0, "the cannon is dead"),
                () -> assertEquals(BlockType.SPACE, w.map.block(2, 1), "its block is space"));
        }

        @Test
        @DisplayName("near a wall the traced move also truncates the velocity to clicks")
        void tracedTruncates() {
            Simulation s = wall();
            World w = s.world;
            int h = spawn(w, Obj.SHOT, BC + 100, BC + 100, 0, 1.0078125);
            s.mover.moveObject(h);
            s.mover.moveObject(h);
            assertAll(
                () -> assertEquals(2, w.wallDist(1, 1), "(fixture: traced)"),
                () -> assertEquals(BC + 100 + 128, w.objects.cy[h], "64 + 64 clicks in two ticks"));
        }

        @Test
        @DisplayName("on a wrapping map a traced move ending across the edge is wrapped")
        void tracedWrap() {
            Simulation s = TestWorlds.sim(FPS60 + "\nedgeWrap: yes", "x    ", "     ", "     ");
            World w = s.world;
            int left = spawn(w, Obj.SHOT, 10, 1000, -1, -1);
            int right = spawn(w, Obj.SHOT, w.clickWidth - 10, w.clickHeight - 10, 1, 1);
            s.mover.moveObject(left);
            s.mover.moveObject(right);
            assertAll(
                () -> assertEquals(w.clickWidth - 54, w.objects.cx[left], "x 10 - 64 wraps to the right edge"),
                () -> assertEquals(1000 - 64, w.objects.cy[left], "y simply decreases"),
                () -> assertEquals(54, w.objects.cx[right], "x width-10+64 wraps to 54"),
                () -> assertEquals(54, w.objects.cy[right], "y height-10+64 wraps to 54"));
        }
        @Test
        @DisplayName("an object outside a non-wrapping world (block clamped to the edge) leaves the universe")
        void outsideWorld() {
            Simulation s = open(FPS60);
            World w = s.world;
            // pixel x 320 is block 9 = width: clamped to block 8 for the walldist lookup
            int h = spawn(w, Obj.SHOT, 320 * 64, 297 * 64, 1, 0);
            s.mover.moveObject(h);
            assertEquals(0.0, w.objects.life[h], "CRASH_UNIVERSE: life 0");
        }

        @Test
        @DisplayName("the free-flight check uses |vel| against ((walldist-2)*35)>>1: a fast object is traced into the wall")
        void fastNearWall() {
            String r = "    x    ";
            Simulation s = TestWorlds.sim(FPS60, r, r, r, r, r, r, r, r, r);
            World w = s.world;
            w.params.objBounceMask = 0;
            // block (2,2) has walldist 4: free flight only up to 35 px; |(55,45)| = 71 px is traced
            int h = spawn(w, Obj.SHOT, 5600, 5600, 55, 45);
            s.mover.moveObject(h);
            int fastX = spawn(w, Obj.SHOT, 5600, 5600, 60, 1);
            s.mover.moveObject(fastX);
            String e = "         ";
            Simulation up = TestWorlds.sim(FPS60, e, e, e, e, "xxxxxxxxx", e, e, e, e);
            up.world.params.objBounceMask = 0;
            int fastY = spawn(up.world, Obj.SHOT, 5600, 5600, 1, 60);
            up.mover.moveObject(fastY);
            assertAll(
                () -> assertEquals(4, w.wallDist(2, 2), "(fixture: walldist 4)"),
                () -> assertEquals(0.0, w.objects.life[h], "it crashes into the wall column"),
                // Move_segment: walldist fast path (2240, 1832), then (1120, round(1048*0.875) = 917) to the face
                () -> assertEquals(8960, w.objects.cx[h], "it stops on the face x = 4*2240"),
                () -> assertEquals(5600 + 1832 + 917, w.objects.cy[h], "y follows the 45/55 slope"),
                () -> assertEquals(0.0, w.objects.life[fastX], "(60, 1): each component counts in |v|^2"),
                () -> assertEquals(8960, w.objects.cx[fastX], "and it stops on the wall face"),
                () -> assertEquals(0.0, up.world.objects.life[fastY], "(1, 60) into a wall row: crashes"),
                () -> assertEquals(8960, up.world.objects.cy[fastY], "on the row's bottom face"));
        }

        @Test
        @DisplayName("crash masks: shots crash on targets/cannons/treasures; other types follow the C masks")
        void masks() {
            Simulation t = TestWorlds.sim(FPS60, "     ", "  !  ", "     ");
            World tw = t.world;
            tw.params.objBounceMask = Obj.SHOT | Obj.DEBRIS;
            tw.params.objectWallBounceLifeFactor = 1;
            tw.params.objectWallBrakeFactor = 1;
            int shot = spawn(tw, Obj.SHOT, 2 * BC - 100, BC + 1000, 5, 0);
            int debris = spawn(tw, Obj.DEBRIS, 2 * BC - 100, BC + 500, 5, 0);
            t.mover.moveObject(shot);
            t.mover.moveObject(debris);
            Simulation c = TestWorlds.sim(FPS60, "     ", "  r  ", "     ");
            World cw = c.world;
            cw.params.objBounceMask = Obj.SPARK;
            int spark = spawn(cw, Obj.SPARK, 2 * BC - 100, BC + 100, 5, 0);
            c.mover.moveObject(spark);
            Simulation r = TestWorlds.sim(FPS60, "     ", "  *  ", "     ");
            World rw = r.world;
            rw.params.objBounceMask = Obj.SHOT;
            rw.params.objTreasureMask = Obj.SHOT | Obj.BALL | Obj.PULSE;  // Move_init: bounce mask | BALL | PULSE
            int rShot = spawn(rw, Obj.SHOT, 2 * BC - 100, BC + 2100, 5, 0);
            int rDebris = spawn(rw, Obj.DEBRIS, 2 * BC - 100, BC + 2000, 5, 0);
            r.mover.moveObject(rShot);
            r.mover.moveObject(rDebris);
            assertAll(
                () -> assertEquals(0.0, tw.objects.life[shot], "a shot crashes on a target"),
                () -> assertEquals(100.0, tw.objects.life[debris], "debris is not in the target mask: it bounces"),
                () -> assertEquals(-5.0, tw.objects.vx[debris], "(reflected)"),
                () -> assertEquals(2 * BC + 220, cw.objects.cx[spark], "a spark is not in the cannon mask: it passes"),
                () -> assertEquals(0.0, cw.cannonDeadTime[0], "and the cannon lives"),
                () -> assertEquals(2 * BC + 220, rw.objects.cx[rShot], "a bouncing shot crashes on treasures: passes above the ball"),
                () -> assertEquals(100.0, rw.objects.life[rShot], "unharmed"),
                () -> assertEquals(0.0, rw.objects.life[rDebris], "non-bouncing debris sees a solid treasure block"),
                () -> assertEquals(2 * BC, rw.objects.cx[rDebris], "and crashes on its face"));
        }

        @Test
        @DisplayName("a diagonal bounce: |v| = 5 from (4,3) is checked against the limit; y is braked too")
        void diagonalBounce() {
            Simulation s = wall();
            World w = s.world;
            w.params.objBounceMask = Obj.SPARK;
            w.params.maxObjectWallBounceSpeed = 4.99f;
            int dead = spawn(w, Obj.SPARK, 2 * BC - 128, BC + 1000, 4, 3);
            s.mover.moveObject(dead);
            Simulation s2 = wall();
            World w2 = s2.world;
            w2.params.objBounceMask = Obj.SPARK;
            w2.params.maxObjectWallBounceSpeed = 6;
            w2.params.objectWallBrakeFactor = 0.5f;
            w2.params.objectWallBounceLifeFactor = 1;
            int h = spawn(w2, Obj.SPARK, 2 * BC - 128, BC + 1000, 4, 3);
            s2.mover.moveObject(h);
            // (256, 192): 128 to the face with y 96, bounce, (-128, 96) halved to (-64, 48)
            assertAll(
                () -> assertEquals(0.0, w.objects.life[dead], "4^2 + 3^2 = 25 > 4.99^2: killed"),
                () -> assertEquals(1.5, w2.objects.vy[h], "y velocity braked: 3 * 0.5"),
                () -> assertEquals(-2.0, w2.objects.vx[h], "x velocity reflected and braked"),
                () -> assertEquals(2 * BC - 64, w2.objects.cx[h], "x: back out 64"),
                () -> assertEquals(BC + 1000 + 96 + 48, w2.objects.cy[h], "y: 96 before, (int)(96*0.5) = 48 after"));
        }

        @Test
        @DisplayName("on a wrapping map a traced move ending exactly on an edge is wrapped once (0 stays 0)")
        void tracedWrapExact() {
            Simulation s = TestWorlds.sim(FPS60 + "\nedgeWrap: yes", "x    ", "     ", "     ");
            World w = s.world;
            int zero = spawn(w, Obj.SHOT, 64, 1000, -1, 0);
            int right = spawn(w, Obj.SHOT, w.clickWidth - 64, 1000, 1, 0);
            int top = spawn(w, Obj.SHOT, 3000, w.clickHeight - 64, 0, 1);
            int below = spawn(w, Obj.SHOT, 3000, 10, 0, -1);
            int bottom = spawn(w, Obj.SHOT, 3000, 64, 0, -1);
            for (int h : new int[] {zero, right, top, below, bottom}) {
                s.mover.moveObject(h);
            }
            assertAll(
                () -> assertEquals(0, w.objects.cx[zero], "ending at x = 0 stays 0"),
                () -> assertEquals(0, w.objects.cx[right], "ending at x = width wraps to 0"),
                () -> assertEquals(0, w.objects.cy[top], "ending at y = height wraps to 0"),
                () -> assertEquals(w.clickHeight - 54, w.objects.cy[below], "ending at y = -54 wraps to height - 54"),
                () -> assertEquals(0, w.objects.cy[bottom], "ending at y = 0 stays 0"));
        }
    }
}
