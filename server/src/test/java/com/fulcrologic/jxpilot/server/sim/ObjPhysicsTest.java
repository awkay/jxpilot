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

import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Expected values follow C ship.c Obj_repel / Delta_mv / Delta_mv_elastic in float arithmetic. */
@DisplayName("ObjPhysics (ship.c Obj_repel, Delta_mv, Delta_mv_elastic)")
class ObjPhysicsTest {
    static Simulation sim() {
        return SimFixture.sim(SimFixture.FPS14, 20, 10, "_______");
    }

    static int shoves(Player p) {
        int n = 0;
        for (int i = 0; i < Player.MAX_SHOVES; i++) {
            n += p.shovePusher[i] != Obj.NO_ID && p.shovePusher[i] != 0 ? 1 : 0;
        }
        return n;
    }

    @Test
    @DisplayName("repel ship-object: force min(10, dist - distance) along the RES direction, ship by force/dm, object by force*dm")
    void repelShip() {
        Simulation s = sim();
        World w = s.world;
        Player p = ready(s, "P", 0, 100, 100);
        Player other = ready(s, "O", 0, 300, 300);
        p.mass = 20;
        int h = obj(w, Obj.BALL, other.id, 110, 105, 0, 0, 50, 100);
        s.objPhysics.repel(p, h, 20);
        float xd = 10;
        float yd = 5;
        float force = (float) (20 - StrictMath.hypot(xd, yd));
        int theta = (int) XMath.findDir(xd, yd);
        float dm = 20f / 50f;
        Player q = ready(s, "Q", 0, 400, 100);
        q.mass = 20;
        int far = obj(w, Obj.BALL, Obj.NO_ID, 400, 120, 1, 1, 50, 100);
        s.objPhysics.repel(q, far, 20);
        Player r = ready(s, "R", 0, 500, 100);
        r.mass = 20;
        int near = obj(w, Obj.BALL, Obj.NO_ID, 501, 100, 0, 0, 50, 100);
        s.objPhysics.repel(r, near, 40);
        Player self = ready(s, "S", 0, 600, 100);
        int own = obj(w, Obj.BALL, self.id, 610, 100, 0, 0, 50, 100);
        s.objPhysics.repel(self, own, 20);
        int gone = obj(w, Obj.BALL, 200, 610, 100, 0, 0, 50, 100);
        s.objPhysics.repel(self, gone, 20);
        assertAll(
            () -> assertEquals(0, shoves(self), "the ship's own object, or a departed owner: no shover"),
            () -> assertEquals(-(Trig.tcos(theta) * force / dm), p.vx, "ship pushed back (x)"),
            () -> assertEquals(-(Trig.tsin(theta) * force / dm), p.vy, "ship pushed back (y)"),
            () -> assertEquals(Trig.tcos(theta) * force * dm, w.objects.vx[h], "object pushed away (x)"),
            () -> assertEquals(Trig.tsin(theta) * force * dm, w.objects.vy[h], "object pushed away (y)"),
            () -> assertEquals(1, shoves(p), "an owned object records its owner as a shover"),
            () -> assertEquals(other.id, p.shovePusher[0], "the owner"),
            () -> assertEquals(0f, q.vx + q.vy, "exactly at the distance: no force"),
            () -> assertEquals(1f, w.objects.vx[far], "and the object keeps its velocity"),
            () -> assertEquals(-(Trig.tcos(0) * 10 / dm), r.vx, "the force is capped at 10"),
            () -> assertEquals(0, shoves(q) + shoves(r), "unowned objects record no shover"));
    }

    @Test
    @DisplayName("repel object-object: same law, no shoves")
    void repelObjects() {
        Simulation s = sim();
        World w = s.world;
        Objects o = w.objects;
        int a = obj(w, Obj.ASTEROID, Obj.NO_ID, 100, 100, 1, 0, 30, 100);
        int b = obj(w, Obj.BALL, Obj.NO_ID, 106, 108, 0, 2, 50, 100);
        s.objPhysics.repel(a, b, 20);
        float force = (float) (20 - StrictMath.hypot(6, 8));
        int theta = (int) XMath.findDir(6, 8);
        float dm = 30f / 50f;
        int c = obj(w, Obj.ASTEROID, Obj.NO_ID, 300, 100, 1, 0, 30, 100);
        int d = obj(w, Obj.BALL, Obj.NO_ID, 320, 100, 0, 2, 50, 100);
        s.objPhysics.repel(c, d, 20);
        assertAll(
            () -> assertEquals(1 - (Trig.tcos(theta) * force / dm), o.vx[a], "first pushed back (x)"),
            () -> assertEquals(-(Trig.tsin(theta) * force / dm), o.vy[a], "first pushed back (y)"),
            () -> assertEquals(Trig.tcos(theta) * force * dm, o.vx[b], "second pushed away (x)"),
            () -> assertEquals(2 + Trig.tsin(theta) * force * dm, o.vy[b], "second pushed away (y)"),
            () -> assertEquals(1f, o.vx[c], "at the distance: nothing"),
            () -> assertEquals(2f, o.vy[d], "nothing"));
    }

    @Test
    @DisplayName("deltaMv objects: both move with (v1*m1 + v2*m2) / (m1 + |m2|)")
    void deltaMv() {
        Simulation s = sim();
        World w = s.world;
        Objects o = w.objects;
        int a = obj(w, Obj.ASTEROID, Obj.NO_ID, 100, 100, 2, -1, 30, 100);
        int b = obj(w, Obj.SHOT, Obj.NO_ID, 100, 100, -4, 6, -0.5, 10);
        s.objPhysics.deltaMv(a, b);
        float m = 30f + 0.5f;
        float vx = (2f * 30f + -4f * -0.5f) / m;
        float vy = (-1f * 30f + 6f * -0.5f) / m;
        assertAll(
            () -> assertEquals(vx, o.vx[a], "first x"),
            () -> assertEquals(vy, o.vy[a], "first y"),
            () -> assertEquals(vx, o.vx[b], "second x"),
            () -> assertEquals(vy, o.vy[b], "second y"));
    }

    @Test
    @DisplayName("deltaMvElastic: v1' = (m1-m2)/ms*v1 + 2*m2/ms*v2, v2' = 2*m1/ms*v1 + (m2-m1)/ms*v2; COLLISIONSHOVE shoves")
    void elastic() {
        Simulation s = sim();
        World w = s.world;
        Objects o = w.objects;
        int a = obj(w, Obj.ASTEROID, Obj.NO_ID, 100, 100, 3, 1, 20, 100);
        int b = obj(w, Obj.ASTEROID, Obj.NO_ID, 100, 100, -1, 2, 50, 100);
        s.objPhysics.deltaMvElastic(a, b);
        float m1 = 20;
        float m2 = 50;
        float ms = m1 + m2;
        Player p = ready(s, "P", 0, 300, 300);
        Player pusher = ready(s, "U", 0, 400, 300);
        p.mass = 20;
        p.vx = 3;
        p.vy = 1;
        int c = obj(w, Obj.MINE, pusher.id, 300, 300, -1, 2, 50, 100);
        o.status[c] = Obj.COLLISIONSHOVE;
        s.objPhysics.deltaMvElastic(p, c);
        Player q = ready(s, "Q", 0, 500, 300);
        int d = obj(w, Obj.ASTEROID, pusher.id, 500, 300, 0, 0, 50, 100);
        s.objPhysics.deltaMvElastic(q, d);
        assertAll(
            () -> assertEquals((m1 - m2) / ms * 3 + 2 * m2 / ms * -1, o.vx[a], "first x"),
            () -> assertEquals((m1 - m2) / ms * 1 + 2 * m2 / ms * 2, o.vy[a], "first y"),
            () -> assertEquals(2 * m1 / ms * 3 + (m2 - m1) / ms * -1, o.vx[b], "second x"),
            () -> assertEquals(2 * m1 / ms * 1 + (m2 - m1) / ms * 2, o.vy[b], "second y"),
            () -> assertEquals((m1 - m2) / ms * 3 + 2 * m2 / ms * -1, p.vx, "ship x"),
            () -> assertEquals((m1 - m2) / ms * 1 + 2 * m2 / ms * 2, p.vy, "ship y"),
            () -> assertEquals(2 * m1 / ms * 3 + (m2 - m1) / ms * -1, o.vx[c], "object x"),
            () -> assertEquals(2 * m1 / ms * 1 + (m2 - m1) / ms * 2, o.vy[c], "object y"),
            () -> assertEquals(pusher.id, p.shovePusher[0], "a COLLISIONSHOVE object records its owner"),
            () -> assertEquals(Obj.NO_ID, q.shovePusher[0] == 0 ? Obj.NO_ID : q.shovePusher[0],
                "without COLLISIONSHOVE no shove"));
    }

    @Test
    @DisplayName("deltaMvElastic rounds in C's float order: 2 * m2 / ms * v2, not 2 * m2 * v2 / ms")
    void elasticFloatOrder() {
        Simulation s = sim();
        World w = s.world;
        Objects o = w.objects;
        // two asteroids (size 1 and 2) with velocities from a C trace
        int a = obj(w, Obj.ASTEROID, Obj.NO_ID, 100, 100, -6.4252863f, 0, 60, 100);
        int b = obj(w, Obj.ASTEROID, Obj.NO_ID, 100, 100, -0.7746947f, 0, 135, 100);
        s.objPhysics.deltaMvElastic(a, b);
        assertEquals(1.3986096f, o.vx[a], "C's float result (the other order gives 1.3986098)");
    }
}
