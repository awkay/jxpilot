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
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exact wreckage pieces: the world RNG is replayed with a reference {@link Rng} of the same seed, so every
 * random draw of C's {@code Make_wreckage} (mass, direction, speed, life, spin, rotation, shape) is known.
 */
@DisplayName("Wreckage (Make_wreckage) with replayed random draws")
class WreckageExactTest {
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

    /** The expected first piece: {mass, dir, speed, vx, vy, life, spin, rotation, size, shape, radius}. */
    static double[] piece(Rng r, float vx, float vy, float minMass, float maxMass, float total, int minDir, int maxDir,
                          float minSpeed, float maxSpeed, int minLife, int maxLife, int hyp) {
        float mass = (float) (minMass + r.nextFloat() * (double) (maxMass - minMass));
        if (mass > total) {
            mass = total;
        }
        int dir = (minDir + (int) (r.nextFloat() * (double) ((maxDir - minDir) & (Const.RES - 1)))) & (Const.RES - 1);
        float speed = (float) (minSpeed + r.nextFloat() * (double) (maxSpeed - minSpeed));
        int life = (int) (minLife + r.nextFloat() * (double) (maxLife - minLife) + 1);
        if (life * speed > hyp) {
            life = (int) (hyp / speed);
        }
        float spin = (float) (0.02 + r.nextFloat() * 0.35);
        int rot = (int) (r.nextFloat() * (double) Const.RES);
        int size = Math.min(255, (int) (256.0 * 1.5 * mass / total));
        int shape = (int) (r.nextFloat() * 256.0);
        return new double[] {mass, dir, speed, vx + Trig.tcos(dir) * speed, vy + Trig.tsin(dir) * speed, life, spin,
            rot, size, shape, Math.max(8, size * 16 / 256)};
    }

    static void check(World w, int h, double[] e, String what) {
        Objects o = w.objects;
        assertAll(what,
            () -> assertEquals((float) e[0], o.mass[h], "mass"),
            () -> assertEquals((float) e[3], o.vx[h], "vx = vx + tcos(dir) * speed"),
            () -> assertEquals((float) e[4], o.vy[h], "vy"),
            () -> assertEquals(e[5], o.life[h], "life"),
            () -> assertEquals(e[5], o.fuseLife[h], "fuse = life"),
            () -> assertEquals((float) e[6], w.modes.wireTurnspeed[h], "spin"),
            () -> assertEquals((int) e[7], w.modes.wireRotation[h], "rotation"),
            () -> assertEquals((int) e[8], w.modes.wireSize[h], "size 256*1.5*mass/total"),
            () -> assertEquals((int) e[9], w.modes.wireInfo[h], "shape"),
            () -> assertEquals((int) e[10], o.plRadius[h], "radius"),
            () -> assertEquals((int) e[10], o.plRange[h], "range"));
    }

    @Test
    @DisplayName("the first piece of a 3-piece explosion matches C's draws exactly")
    void exactPiece() {
        Simulation s = sim("");
        World w = s.world;
        w.rng.seed(5);
        Rng r = new Rng(5);
        int hyp = (int) w.params.hypotenuse;
        double[] e = piece(r, 1f, -2f, 2f, 5f, 12f, 10, 40, 3f, 7f, 4, 9, hyp);
        s.wreckage.make(500, 400, 1f, -2f, 7, 2, 2f, 5f, 12f, Obj.GRAVITY, 6, 3, 10, 40, 3f, 7f, 4, 9);
        check(w, wrecks(w).get(0), e, "first piece");
        assertEquals(500, w.objects.px[wrecks(w).get(0)], "at the explosion");
    }

    @Test
    @DisplayName("a ship's wreckage: pieces of max(mass/8, 0.33)..mass, speed 10..10+(int)mass/2, life 5..5+1.5*mass")
    void ship() {
        Simulation s = sim("");
        World w = s.world;
        Player p = ready(s, "P", 0, 300, 300);
        p.mass = 21.7f;
        p.vx = 2;
        p.status |= Obj.KILLED;
        w.rng.seed(9);
        Rng r = new Rng(9);
        double[] e = piece(r, 2f, 0f, (float) Math.max(21.7f / 8.0, 0.33), 21.7f, (float) (2.0 * 21.7f), 0,
            Const.RES - 1, 10f, 10 + (21 >> 1), 5, (int) (5 + 21.7f * 1.5), (int) w.params.hypotenuse);
        s.wreckage.explodeShip(p);
        check(w, wrecks(w).get(0), e, "first piece of the ship");
    }

    @Test
    @DisplayName("edges: positions at 0 accepted and at the size refused; reversed lives; shotLife cap; exact speed cap")
    void edges() {
        Simulation s = sim("shotLife: 20\n");
        World w = s.world;
        int pw = w.pixelWidth;
        int atZero = s.wreckage.make(0, 0, 0f, 0f, -1, 0, 1f, 1f, 1f, 0, 6, 1, 0, 0, 1f, 1f, 5, 5);
        int atW = s.wreckage.make(pw, 10, 0f, 0f, -1, 0, 1f, 1f, 1f, 0, 6, 1, 0, 0, 1f, 1f, 5, 5);
        int atH = s.wreckage.make(10, w.pixelHeight, 0f, 0f, -1, 0, 1f, 1f, 1f, 0, 6, 1, 0, 0, 1f, 1f, 5, 5);
        int negY = s.wreckage.make(10, -1, 0f, 0f, -1, 0, 1f, 1f, 1f, 0, 6, 1, 0, 0, 1f, 1f, 5, 5);
        int before = wrecks(w).size();
        s.wreckage.make(100, 100, 0f, 0f, -1, 0, 1f, 1f, 1f, 0, 6, 1, 0, 0, 0f, 0f, 9, 3);
        int reversed = wrecks(w).get(before);
        s.wreckage.make(100, 100, 0f, 0f, -1, 0, 1f, 1f, 1f, 0, 6, 1, 0, 0, 0f, 0f, 30, 40);
        int capped = wrecks(w).get(before + 1);
        int hyp = (int) w.params.hypotenuse;
        s.wreckage.make(100, 100, 0f, 0f, -1, 0, 1f, 1f, 1f, 0, 6, 1, 0, 0, hyp / 10f, hyp / 10f, 10, 10);
        int exact = wrecks(w).get(before + 2);
        float sp = (float) Math.hypot(w.objects.vx[exact], w.objects.vy[exact]);
        Simulation full = sim("");
        while (full.world.objects.alloc() >= 0) {
            // fill the pool
        }
        int none = full.wreckage.make(100, 100, 0f, 0f, -1, 0, 1f, 1f, 5f, 0, 6, 10, 0, 0, 1f, 1f, 5, 5);
        assertAll(
            () -> assertEquals(1, atZero, "(0, 0) is in the world"),
            () -> assertEquals(0, atW, "x = width is not"),
            () -> assertEquals(0, atH, "y = height is not"),
            () -> assertEquals(0, negY, "y < 0 is not"),
            () -> assertEquals(10.0, w.objects.life[reversed], "max life below min becomes min (9 + 1)"),
            () -> assertEquals(21.0, w.objects.life[capped], "min life above shotLife: both capped at shotLife (20 + 1)"),
            () -> assertEquals((float) (hyp / 10f), sp, 1e-3, "speed * life exactly at the diagonal is kept"),
            () -> assertEquals(0, none, "a full pool makes nothing"));
    }

    @Test
    @DisplayName("sizes: capped at 255; tiny pieces have radius 8; a rest below the minimum mass makes no piece")
    void sizes() {
        Simulation s = sim("");
        World w = s.world;
        s.wreckage.make(100, 100, 0f, 0f, -1, 0, 10f, 10f, 10f, 0, 6, 5, 0, 0, 1f, 1f, 5, 5);
        int big = wrecks(w).get(0);
        s.wreckage.make(100, 100, 0f, 0f, -1, 0, 1f, 1f, 100f, 0, 6, 1, 0, 0, 1f, 1f, 5, 5);
        int small = wrecks(w).get(1);
        s.wreckage.make(100, 100, 0f, 0f, -1, 0, 3f, 3f, 7.5f, 0, 6, 5, 0, 0, 1f, 1f, 5, 5);
        List<Integer> ws = wrecks(w);
        assertAll(
            () -> assertEquals(255, w.modes.wireSize[big], "384 is capped at 255"),
            () -> assertEquals(255 * 16 / 256, w.objects.plRadius[big], "radius 15"),
            () -> assertEquals(3, w.modes.wireSize[small], "size 3"),
            () -> assertEquals(8, w.objects.plRadius[small], "radius at least 8"),
            () -> assertEquals(4, ws.size(), "7.5 in pieces of 3: two pieces; the 1.5 rest is below min mass"),
            () -> assertEquals(3f, w.objects.mass[ws.get(3)], "the second piece"));
    }
}
