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
}
