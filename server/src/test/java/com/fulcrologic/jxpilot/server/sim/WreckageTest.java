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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Wreckage (ship.c Make_wreckage, Explode_fighter wreckage, wreckage hits)")
class WreckageTest {
    static Simulation sim(String extra) {
        return SimFixture.sim(SimFixture.FPS14 + extra, 30, 30, "___");
    }

    static List<Integer> wrecks(World w) {
        List<Integer> l = new ArrayList<>();
        for (int k = 0; k < w.objects.count(); k++) {
            if (w.objects.type[w.objects.active[k]] == Obj.WRECKAGE) {
                l.add(w.objects.active[k]);
            }
        }
        return l;
    }

    @Test
    @DisplayName("pieces share the total mass, at most maxWreckage; size, radius, spin, life and speed caps")
    void make() {
        Simulation s = sim("");
        World w = s.world;
        Objects o = w.objects;
        int n = s.wreckage.make(500, 500, 1f, 0f, 7, 2, 2f, 5f, 12f, Obj.GRAVITY, 6, 10, 0, Const.RES - 1, 10f, 20f,
            5, 10);
        float sum = 0;
        boolean shapes = true;
        for (int h : wrecks(w)) {
            sum += o.mass[h];
            int size = w.modes.wireSize[h];
            shapes &= size == Math.min(255, (int) (256.0 * 1.5 * o.mass[h] / 12f));
            shapes &= o.plRadius[h] == Math.max(8, size * 16 / 256);
            shapes &= w.modes.wireTurnspeed[h] >= 0.02f && w.modes.wireTurnspeed[h] < 0.37f;
            shapes &= o.life[h] >= 6 && o.life[h] <= 11;
            shapes &= o.owner[h] == 7 && o.team[h] == 2 && o.status[h] == Obj.GRAVITY;
        }
        float total = sum;
        boolean ok = shapes;
        Simulation few = sim("");
        int capped = few.wreckage.make(500, 500, 0f, 0f, -1, 0, 1f, 1f, 100f, 0, 6, 3, 0, 0, 1f, 1f, 5, 5);
        Simulation off = sim("useWreckage: no\n");
        int none = off.wreckage.make(500, 500, 0f, 0f, -1, 0, 1f, 1f, 100f, 0, 6, 3, 0, 0, 1f, 1f, 5, 5);
        int outside = few.wreckage.make(-5, 500, 0f, 0f, -1, 0, 1f, 1f, 100f, 0, 6, 3, 0, 0, 1f, 1f, 5, 5);
        assertAll(
            () -> assertTrue(n >= 3 && n <= 6, "12 mass in pieces of 2..5: 3 to 6 pieces"),
            () -> assertEquals(12f, total, 1e-4, "the pieces use up the total mass"),
            () -> assertTrue(ok, "size, radius, spin, life, owner/team/status per piece"),
            () -> assertEquals(3, capped, "at most maxWreckage"),
            () -> assertEquals(0, none, "useWreckage off: none"),
            () -> assertEquals(0, outside, "outside a non-wrapping world: none"));
    }

    @Test
    @DisplayName("speed caps divide the int diagonal by the lives; a lone too-small remainder is not made")
    void caps() {
        Simulation s = sim("");
        World w = s.world;
        int hyp = (int) w.params.hypotenuse;
        s.wreckage.make(500, 500, 0f, 0f, -1, 0, 5f, 5f, 5f, 0, 6, 10, 0, 0, 500f, 500f, 10, 10);
        int h = wrecks(w).get(0);
        float speed = (float) Math.hypot(w.objects.vx[h], w.objects.vy[h]);
        Simulation r = sim("");
        int made = r.wreckage.make(500, 500, 0f, 0f, -1, 0, 4f, 4f, 6f, 0, 6, 10, 0, 0, 1f, 1f, 5, 5);
        assertAll(
            () -> assertEquals(hyp / 10, speed, 1e-3, "speed capped at (int)hypotenuse / life"),
            () -> assertEquals(1, made, "4 + a 2 remainder below min mass 4: one piece"));
    }

    @Test
    @DisplayName("a killed ship leaves wreckage; a reset one does not; pieces spin each frame")
    void ships() {
        Simulation s = sim("");
        World w = s.world;
        Player p = ready(s, "P", 0, 300, 300);
        s.wreckage.explodeShip(p);
        int alive = wrecks(w).size();
        p.status |= Obj.KILLED;
        s.wreckage.explodeShip(p);
        List<Integer> ws = wrecks(w);
        int h = ws.get(0);
        int rot = w.modes.wireRotation[h];
        s.wreckage.rotate(h);
        assertAll(
            () -> assertEquals(0, alive, "not KILLED: none"),
            () -> assertTrue(ws.size() >= 2 && ws.size() <= 10, "KILLED: 2..10 pieces"),
            () -> assertEquals((rot + (int) (w.modes.wireTurnspeed[h] * Const.RES)) % Const.RES,
                w.modes.wireRotation[h], "rotation += (int)(spin*RES) mod RES"));
    }

    @Test
    @DisplayName("wreckage hits ships like debris; with wreckageCollisionMayKill an unshielded ship dies, armor saves it")
    void hits() {
        Simulation s = sim("wreckageCollisionMayKill: yes\n");
        World w = s.world;
        Player p = ready(s, "P", 0, 300, 300);
        int h = obj(w, Obj.WRECKAGE, Obj.NO_ID, 300, 300, 0.1, 0, 1, 10);
        s.collisions.debris(p, h);
        Player a = ready(s, "A", 0, 400, 300);
        a.have |= Has.ARMOR;
        a.items[Item.ARMOR.ordinal()] = 1;
        int h2 = obj(w, Obj.WRECKAGE, Obj.NO_ID, 400, 300, 0.1, 0, 1, 10);
        s.collisions.debris(a, h2);
        Simulation soft = sim("");
        Player q = ready(soft, "Q", 0, 300, 300);
        int h3 = obj(soft.world, Obj.WRECKAGE, Obj.NO_ID, 300, 300, 0.1, 0, 1, 10);
        soft.collisions.debris(q, h3);
        assertAll(
            () -> assertTrue((p.status & Obj.KILLED) != 0, "an unshielded ship dies"),
            () -> assertEquals(0, a.status & Obj.KILLED, "armor saves it"),
            () -> assertEquals(0, a.have & Has.ARMOR, "and is used"),
            () -> assertEquals(0, q.status & Obj.KILLED, "without wreckageCollisionMayKill wreckage only costs fuel"));
    }
}
