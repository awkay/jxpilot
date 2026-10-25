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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ObjectMover}: C {@code Move_object}, shared by the server's pool and the client's bullets (the server's
 * trace and digest tests pin it bit for bit; these name its behaviours).
 */
class ObjectMoverTest {
    static final String[] OPEN = ShipMoverTest.OPEN;
    /** A wall column at block x 5 (pixels 175..210), rows 1-3. */
    static final String[] RIGHT_WALL = ShipMoverTest.RIGHT_WALL;

    static ObjectMover mover(ShipWorld w, boolean wallBounce) {
        ObjectMover m = new ObjectMover(w, () -> 0.5);
        m.objectWallBounceLifeFactor = 0.5f;
        m.maxObjectWallBounceSpeed = 100;
        m.objectWallBrakeFactor = 0.5f;
        MoveState ms = m.ms;
        ms.pl = null;
        ms.obj = -1;
        ms.edgeWrap = w.wrap();
        ms.edgeBounce = true;
        ms.wallBounce = wallBounce;
        ms.cannonCrashes = true;
        ms.targetCrashes = true;
        ms.treasureCrashes = wallBounce;
        ms.wormholeWarps = true;
        ms.team = 0;
        ms.targetTeam = 0;
        return m;
    }

    static ObjectBody body(double x, double y, float vx, float vy, int type) {
        ObjectBody b = new ObjectBody();
        b.cx = Click.fromPixels((float) x);
        b.cy = Click.fromPixels((float) y);
        b.vx = vx;
        b.vy = vy;
        b.life = 40;
        b.type = type;
        return b;
    }

    @Nested
    @DisplayName("free flight")
    class Free {
        @Test
        @DisplayName("far from walls the object moves by its truncated clicks and is not traced")
        void free() {
            ObjectMover m = mover(moverWorld("", OPEN), false);
            ObjectBody b = body(157, 157, 3.5f, -2.25f, Obj.SHOT);
            int cx = b.cx;
            int cy = b.cy;
            boolean posUpdate = m.move(b);
            assertAll(
                () -> assertFalse(posUpdate, "no position update"),
                () -> assertFalse(m.traced(), "not traced"),
                () -> assertEquals(cx + Click.fromPixels(3.5f), b.cx, "x by FLOAT_TO_CLICK(vx)"),
                () -> assertEquals(cy + Click.fromPixels(-2.25f), b.cy, "y by FLOAT_TO_CLICK(vy)"),
                () -> assertEquals(3.5f, b.vx, "velocity kept"),
                () -> assertEquals(40, b.life, "life kept"));
        }

        @Test
        @DisplayName("with edge wrap it wraps across the edge")
        void wraps() {
            ShipWorld w = moverWorld("edgeWrap: yes", OPEN);
            ObjectMover m = mover(w, false);
            ObjectBody b = body(314, 157, 5, 0, Obj.SHOT);
            int cx = b.cx;
            m.move(b);
            assertEquals(cx + Click.fromPixels(5f) - w.clickWidth(), b.cx, "315 px wide: 314 + 5 is 4");
        }
    }

    @Test
    @DisplayName("KernelWorld: the object position the wormhole branch reads is the one set for the moving object")
    void kernelWorldMoving() {
        KernelWorld k = KernelWorld.of(KernelFx.options("", OPEN));
        k.movingPx = 12;
        k.movingPy = 34;
        assertAll(
            () -> assertEquals(12, k.objectPx(7), "x for any handle"),
            () -> assertEquals(34, k.objectPy(-1), "y for any handle"));
    }

    @Nested
    @DisplayName("traced")
    class Traced {
        @Test
        @DisplayName("into a wall without wall bounce it crashes there")
        void crash() {
            ObjectMover m = mover(moverWorld("", RIGHT_WALL), false);
            ObjectBody b = body(150, 87, 40, 0, Obj.SHOT);
            boolean posUpdate = m.move(b);
            assertAll(
                () -> assertTrue(m.traced(), "traced"),
                () -> assertEquals(MoveState.CRASH_WALL, m.ms.crash, "a wall crash"),
                () -> assertTrue(posUpdate, "the position is updated"),
                () -> assertEquals(Click.fromPixels(175f), b.cx, "stopped at the wall's face"));
        }

        @Test
        @DisplayName("a bounce costs life (truncated) and brakes the velocity; a spark loses its owner immunity")
        void bounce() {
            ObjectMover m = mover(moverWorld("", RIGHT_WALL), true);
            ObjectBody b = body(150, 87, 40, 0, Obj.SPARK);
            b.life = 41;
            b.status = Obj.OWNERIMMUNE | Obj.GRAVITY;
            m.move(b);
            ObjectBody fb = body(150, 87, 40, 0, Obj.SPARK);
            fb.status = Obj.OWNERIMMUNE | Obj.FROMBOUNCE;
            m.move(fb);
            ObjectBody ball = body(150, 87, 40, 0, Obj.BALL);
            m.move(ball);
            assertAll(
                () -> assertEquals(MoveState.NOT_A_CRASH, m.ms.crash, "no crash"),
                () -> assertEquals(20, b.life, "41 * 0.5 truncated"),
                () -> assertEquals(-20f, b.vx, "back at half the speed"),
                () -> assertEquals(Click.fromPixels(175f) - 480, b.cx, "the 15 px left after the wall braked to 7.5"),
                () -> assertEquals(Obj.GRAVITY, b.status, "owner immunity gone, the rest kept"),
                () -> assertEquals(Obj.OWNERIMMUNE | Obj.FROMBOUNCE, fb.status, "a spark from a bounce keeps it"),
                () -> assertEquals(40, ball.life, "a ball's life is not shortened"));
        }

        @Test
        @DisplayName("faster than maxObjectWallBounceSpeed a bounce kills it; a life truncated to 0 stops the move")
        void fatal() {
            ObjectMover m = mover(moverWorld("", RIGHT_WALL), true);
            m.maxObjectWallBounceSpeed = 30;
            ObjectBody b = body(150, 87, 40, 0, Obj.SHOT);
            m.move(b);
            ObjectMover n = mover(moverWorld("", RIGHT_WALL), true);
            ObjectBody one = body(150, 87, 40, 0, Obj.SHOT);
            one.life = 1;
            n.move(one);
            assertAll(
                () -> assertEquals(0, b.life, "too fast: life 0"),
                () -> assertEquals(0, one.life, "1 * 0.5 truncates to 0"),
                () -> assertEquals(-40f, one.vx, "reflected but not braked: the move stopped at the bounce"));
        }

        @Test
        @DisplayName("life and status go through the live state, not the body")
        void live() {
            ObjectMover m = mover(moverWorld("", RIGHT_WALL), true);
            ObjectBody b = body(150, 87, 40, 0, Obj.SPARK);
            b.life = 99;
            ObjectBody store = new ObjectBody();
            store.life = 10;
            store.status = Obj.OWNERIMMUNE;
            m.move(b, store);
            assertAll(
                () -> assertEquals(5, store.life, "the store's 10 halved"),
                () -> assertEquals(0, store.status, "the store's status changed"),
                () -> assertEquals(99, b.life, "the body's life untouched"));
        }
    }

    @Nested
    @DisplayName("more of the loop")
    class More {
        /** A wall row at the top (block y 4, pixels 140..175). */
        static final String[] TOP_WALL = {"xxxxxxx", "       ", " _     ", "       ", "       "};

        @Test
        @DisplayName("free flight up to ((walldist - 2) * 35) >> 1 px per tick; one step longer is traced")
        void freeLimit() {
            KernelFx.TestWorld w = moverWorld("", OPEN);
            int d = w.wallDist(4, 4);
            float max = ((d - 2) * 35) >> 1;
            ObjectMover m = mover(w, false);
            m.move(body(157, 157, max, 0, Obj.SHOT));
            boolean atLimit = m.traced();
            m.move(body(157, 157, max + 0.5f, 0, Obj.SHOT));
            boolean over = m.traced();
            m.move(body(157, 157, 0, max + 0.5f, Obj.SHOT));
            boolean overY = m.traced();
            assertAll(
                () -> assertTrue(d > 2, "the centre is far from walls: " + d),
                () -> assertFalse(atLimit, "at the limit: free"),
                () -> assertTrue(over, "over it: traced"),
                () -> assertTrue(overY, "over it in y: traced"));
        }

        @Test
        @DisplayName("a vertical bounce brakes vy; a traced move that hits nothing is no position update")
        void vertical() {
            ObjectMover m = mover(moverWorld("", TOP_WALL), true);
            ObjectBody b = body(100, 125, 0, 30, Obj.SHOT);
            m.move(b);
            ObjectMover n = mover(moverWorld("", RIGHT_WALL), false);
            ObjectBody near = body(150, 87, 5, 2, Obj.SHOT);
            int cx = near.cx;
            int cy = near.cy;
            boolean update = n.move(near);
            assertAll(
                () -> assertEquals(-15f, b.vy, "back down at half the speed"),
                () -> assertEquals(Click.fromPixels(140f) - 480, b.cy, "the 15 px left after the wall braked to 7.5"),
                () -> assertTrue(b.cy < Click.fromPixels(140f), "below the wall: " + b.cy / 64.0),
                () -> assertTrue(n.traced(), "near the wall: traced"),
                () -> assertFalse(update, "no bounce, no crash: no position update"),
                () -> assertEquals(cx + Click.fromPixels(5f), near.cx, "moved in x"),
                () -> assertEquals(cy + Click.fromPixels(2f), near.cy, "and in y"));
        }

        @Test
        @DisplayName("a bounce exactly at maxObjectWallBounceSpeed survives")
        void speedBoundary() {
            ObjectMover m = mover(moverWorld("", RIGHT_WALL), true);
            m.maxObjectWallBounceSpeed = 40;
            ObjectBody b = body(150, 87, 40, 0, Obj.SHOT);
            m.move(b);
            assertTrue(b.life > 0, "40 is not over 40: alive with " + b.life);
        }

        @Test
        @DisplayName("traced next to a wall on a wrapped map, it wraps across every edge")
        void tracedWraps() {
            String[] leftWall = {" x     ", " x     ", " x     ", " x     ", " x     "};
            String[] rightWall = {"     x ", "     x ", "     x ", "     x ", "     x "};
            String[] bottomWall = {"       ", "       ", "       ", "xxxxxxx", "       "};
            String[] topWall = {"       ", "xxxxxxx", "       ", "       ", "       "};
            KernelFx.TestWorld wl = moverWorld("edgeWrap: yes", leftWall);
            ObjectBody l = body(10, 87, -15, 0, Obj.SHOT);
            mover(wl, false).move(l);
            KernelFx.TestWorld wr = moverWorld("edgeWrap: yes", rightWall);
            ObjectBody r = body(240, 87, 10, 0, Obj.SHOT);
            mover(wr, false).move(r);
            KernelFx.TestWorld wb = moverWorld("edgeWrap: yes", bottomWall);
            ObjectBody down = body(100, 10, 0, -15, Obj.SHOT);
            mover(wb, false).move(down);
            KernelFx.TestWorld wt = moverWorld("edgeWrap: yes", topWall);
            ObjectBody up = body(100, 170, 0, 10, Obj.SHOT);
            mover(wt, false).move(up);
            assertAll(
                () -> assertEquals(Click.fromPixels(10f) + Click.fromPixels(-15f) + wl.clickWidth(), l.cx, "left: 240"),
                () -> assertEquals(Click.fromPixels(240f) + Click.fromPixels(10f) - wr.clickWidth(), r.cx, "right: 5"),
                () -> assertEquals(Click.fromPixels(10f) + Click.fromPixels(-15f) + wb.clickHeight(), down.cy,
                    "bottom: 170"),
                () -> assertEquals(Click.fromPixels(170f) + Click.fromPixels(10f) - wt.clickHeight(), up.cy, "top: 5"));
        }

        @Test
        @DisplayName("traced, a move ending exactly on the far edge wraps to 0; a fast vertical bounce kills it")
        void exactEdges() {
            String[] rightWall = {"     x ", "     x ", "     x ", "     x ", "     x "};
            String[] topWall = {"       ", "xxxxxxx", "       ", "       ", "       "};
            ObjectBody r = body(240, 87, 5, 0, Obj.SHOT);
            mover(moverWorld("edgeWrap: yes", rightWall), false).move(r);
            ObjectBody u = body(100, 170, 0, 5, Obj.SHOT);
            mover(moverWorld("edgeWrap: yes", topWall), false).move(u);
            ObjectMover fast = mover(moverWorld("", TOP_WALL), true);
            fast.maxObjectWallBounceSpeed = 20;
            ObjectBody v = body(100, 125, 0, 30, Obj.SHOT);
            fast.move(v);
            assertAll(
                () -> assertEquals(0, r.cx, "240 + 5 = 245 is x 0"),
                () -> assertEquals(0, u.cy, "170 + 5 = 175 is y 0"),
                () -> assertEquals(0, v.life, "30 px per tick up is over 20: killed"));
        }

        @Test
        @DisplayName("the segment is the mover's own (its treasure hook is installed there)")
        void segment() {
            ObjectMover m = mover(moverWorld("", OPEN), false);
            Segment.TreasureHook h = ms -> true;
            m.segment().treasureHook = h;
            assertEquals(h, m.segment().treasureHook, "the same segment each time");
        }
    }
}
