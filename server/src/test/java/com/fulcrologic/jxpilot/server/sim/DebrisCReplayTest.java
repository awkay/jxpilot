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
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.ThrustSparks;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C's Thrust and Make_debris in a C replay ({@code World.replay}): their random draws and the particles they make. */
@DisplayName("Debris: C's thrust draws in a C replay (ship.c Thrust, play.c Make_debris)")
class DebrisCReplayTest {
    /** A replay's C draws: the given randomMT() values first, then a seeded generator; counts every draw. */
    static final class Script implements World.Draws {
        private final int[] first;
        private int next;
        private final Rng rng;
        long draws;

        Script(int seed, int... first) {
            this.first = first;
            this.rng = new Rng(seed);
        }

        @Override
        public int randomMT() {
            draws++;
            return next < first.length ? first[next++] : rng.nextInt();
        }

        @Override
        public double rfrac() {
            draws++;
            // C rfrac(): randomMT() / 2^32
            return (rng.nextInt() & 0xFFFFFFFFL) * 0x1p-32;
        }

        long draws() {
            return draws;
        }
    }

    /** Draws whose first randomMT() (564929546) is positive, so keep_rand shifts down to 0. */
    static Script positiveNext() {
        return new Script(4357, 564929546);
    }

    static int tot(int keepBits, float power) {
        return (int) ((power * 0.15) + (keepBits & 3) + 1);
    }

    @Test
    @DisplayName("first thrust: keep_rand drawn, then two Make_debris: 1 + 4 per spark each (no alt sparks)")
    void firstThrust() {
        Simulation s = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        World w = s.world;
        Player p = ready(s, "P", 0, 500, 500);
        Script c = positiveNext();
        w.replay = c;
        long before = c.draws();
        s.debris.thrust(p);
        long first = c.draws() - before;
        int keep = 564929546;
        int t1 = tot(keep, p.power);
        before = c.draws();
        s.debris.thrust(p);
        long second = c.draws() - before;
        int t2 = tot(keep >> 2, p.power);
        assertAll(
            () -> assertEquals(1 + (1 + 4L * t1) + 1, first, "keep_rand, the red sparks, the (empty) blue ones"),
            () -> assertEquals((1 + 4L * t2) + 1, second, "keep_rand shifted, nothing drawn for it"));
    }

    @Test
    @DisplayName("a positive keep_rand is redrawn once it shifted down to 0; a negative one never is (C's signed shift)")
    void keepRand() {
        Simulation s = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        World w = s.world;
        Player p = ready(s, "P", 0, 500, 500);
        Script c = positiveNext();
        w.replay = c;
        int keep = 564929546;
        long expected = 1;
        for (int i = 0; i < 20; i++) {
            long before = c.draws();
            s.debris.thrust(p);
            long d = c.draws() - before;
            if (i > 0) {
                keep >>= 2;
                if (keep == 0) {
                    break;
                }
            }
            expected = 2 + 4L * tot(keep, p.power) + (i == 0 ? 1 : 0);
            assertEquals(expected, d, "thrust " + i);
        }
        Simulation n = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        Player q = ready(n, "Q", 0, 500, 500);
        Script neg = new Script(4357, (int) 3510405877L); // negative as a C int
        n.world.replay = neg;
        long redraws = 0;
        int k = (int) 3510405877L;
        for (int i = 0; i < 40; i++) {
            long before = neg.draws();
            n.debris.thrust(q);
            if (i > 0) {
                k >>= 2;
            }
            long sparks = 2 + 4L * tot(k, q.power);
            redraws += neg.draws() - before - sparks;
        }
        assertEquals(1, redraws, "drawn once, never again (it shifts to -1)");
    }

    @Test
    @DisplayName("afterburners split off blue sparks: ((tot - 1) * ab >> 4) + 1 of them")
    void afterburners() {
        Simulation s = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        World w = s.world;
        Player p = ready(s, "P", 0, 500, 500);
        p.items[Item.AFTERBURNER.ordinal()] = 8;
        Script c = positiveNext();
        w.replay = c;
        long before = c.draws();
        s.debris.thrust(p);
        int t = tot(564929546, p.power);
        int ab = p.afterburners();
        int alt = (((t - 1) * ab) >> 4) + 1;
        assertEquals(1 + (1 + 4L * (t - alt)) + (1 + 4L * alt), c.draws() - before, "red and blue sparks");
    }

    @Test
    @DisplayName("an engine outside the world makes no Make_debris draws; with edge wrap it wraps and does")
    void outside() {
        Simulation s = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        Player p = ready(s, "P", 0, 1, 500);
        p.dir = 0; // pointing right: the engine is left of the ship, outside x 0
        Script c = positiveNext();
        s.world.replay = c;
        long before = c.draws();
        s.debris.thrust(p);
        long out = c.draws() - before;
        Simulation wr = SimFixture.sim(SimFixture.FPS14 + "edgeWrap: yes\n", 30, 30, "_");
        Player q = ready(wr, "Q", 0, 1, 500);
        q.dir = 0;
        Script c2 = positiveNext();
        wr.world.replay = c2;
        before = c2.draws();
        wr.debris.thrust(q);
        long wrapped = c2.draws() - before;
        Simulation top = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        Player r = ready(top, "R", 0, 500, 1);
        r.dir = 32; // pointing up: the engine is below y 0
        Script c3 = positiveNext();
        top.world.replay = c3;
        before = c3.draws();
        top.debris.thrust(r);
        long below = c3.draws() - before;
        int t = tot(564929546, p.power);
        assertAll(
            () -> assertEquals(1, out, "only keep_rand"),
            () -> assertEquals(1 + (1 + 4L * t) + 1, wrapped, "wrapped to the far edge: the sparks' draws"),
            () -> assertEquals(1, below, "below the world: only keep_rand"));
    }

    /**
     * C's particle values (play.c:207-243) for the {@code num} particles made last, from an independent generator.
     *
     * @return how many lives were cut to the world diagonal
     */
    static int assertCParticles(World w, Script c, int num, float x, float y, float vx, float vy, int minDir,
                                 int maxDir, float minSpeed, float maxSpeed, int minLife, int maxLife, String what) {
        Objects o = w.objects;
        float hypotenuse = (int) w.params.hypotenuse;
        int cut = 0;
        for (int i = 0; i < num; i++) {
            int h = o.active[o.count() - num + i];
            int dir = (minDir + (int) (c.rfrac() * (maxDir - minDir))) & (Const.RES - 1);
            int dirPlus = (dir + 1) & (Const.RES - 1);
            float off = (float) c.rfrac();
            float dx = Trig.tcos(dir) + (Trig.tcos(dirPlus) - Trig.tcos(dir)) * off;
            float dy = Trig.tsin(dir) + (Trig.tsin(dirPlus) - Trig.tsin(dir)) * off;
            float speed = (float) (minSpeed + c.rfrac() * (maxSpeed - minSpeed));
            int life = (int) (minLife + c.rfrac() * (maxLife - minLife) + 1);
            if (life * speed > hypotenuse) {
                life = (int) (hypotenuse / speed);
                cut++;
            }
            String p = what + " particle " + i;
            int lf = life;
            assertAll(
                () -> assertEquals(vx + dx * speed, o.vx[h], p + " vx"),
                () -> assertEquals(vy + dy * speed, o.vy[h], p + " vy"),
                () -> assertEquals(lf, o.life[h], p + " life"),
                () -> assertEquals(lf, o.fuseLife[h], p + " fuse life"),
                () -> assertEquals(Click.fromPixels(x), o.cx[h], p + " x, not rounded to the wire"),
                () -> assertEquals(Click.fromPixels(y), o.cy[h], p + " y"));
        }
        return cut;
    }

    @Test
    @DisplayName("C's own particles: direction, offset, speed and life drawn per particle in C's order and float "
        + "precision, at the unrounded origin; a life whose path passes the diagonal is cut; no burst is recorded")
    void cParticles() {
        Simulation s = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        World w = s.world;
        w.replay = new Script(42);
        Script c = new Script(42);
        long bursts = w.events.seq();
        int n = s.debris.make(100.3, 200.7, 1.5f, -2.25f, 7, 2, Obj.DEBRIS, 4.5f, Obj.GRAVITY,
            Records.ObjColor.RED, Records.BurstKind.DEBRIS, 6, 3, 9, 10, 40, 1.0, 5.0, 3, 8);
        int num = 3 + (int) (c.rfrac() * (9 - 3));
        assertCParticles(w, c, num, 100.3f, 200.7f, 1.5f, -2.25f, 10, 40, 1f, 5f, 3, 8, "slow");
        long made = w.events.seq() - bursts;
        // speeds 20..60 and lives 20..40 (within make's caps) on a 1484 px diagonal: many paths pass it
        int fast = s.debris.make(500, 500, 0, 0, 7, 2, Obj.DEBRIS, 4.5f, Obj.GRAVITY, Records.ObjColor.RED, -1, 6, 40,
            40, 0, Const.RES - 1, 20, 60, 20, 40);
        int fastNum = 40 + (int) (c.rfrac() * 0);
        int cutLives = assertCParticles(w, c, fastNum, 500, 500, 0, 0, 0, Const.RES - 1, 20, 60, 20, 40, "fast");
        assertAll(
            () -> assertEquals(num, n, "count = min + (int)(rfrac * (max - min))"),
            () -> assertEquals(0, made, "no burst event in a C replay"),
            () -> assertEquals(40, fast, "40 fast particles"),
            () -> assertTrue(cutLives > 0, "(some fast lives were cut to the diagonal)"),
            () -> assertEquals(c.draws(), ((Script) w.replay).draws(), "the same draws as C"));
    }

    @Test
    @DisplayName("C replay thrust: C's red sparks of THRUST_MASS from the engine, min/max dir dir+RES/2-+(RES*0.2+1), "
        + "speed 1..1+power*0.14, life 3..3+power*0.35, owner immune with gravity")
    void cThrustSparks() {
        Simulation s = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        World w = s.world;
        Player p = ready(s, "P", 0, 500, 500);
        p.vx = 2;
        p.vy = -1;
        Script gen = positiveNext();
        w.replay = gen;
        Script c = positiveNext();
        int before = w.objects.count();
        s.debris.thrust(p);
        c.randomMT(); // keep_rand
        int t = tot(564929546, p.power);
        int num = t + (int) (c.rfrac() * 0);
        float x = p.px + p.ship.engineX[p.dir];
        float y = p.py + p.ship.engineY[p.dir];
        assertCParticles(w, c, num, x, y, 2, -1, (int) (p.dir + Const.RES / 2 - Const.RES * 0.2 - 1),
            (int) (p.dir + Const.RES / 2 + Const.RES * 0.2 + 1), 1, (float) (1 + p.power * 0.14), 3,
            3 + (int) (p.power * 0.35), "thrust");
        int h = w.objects.active[w.objects.count() - 1];
        assertAll(
            () -> assertEquals(num, w.objects.count() - before, "tot sparks, no blue ones"),
            () -> assertEquals(Obj.SPARK, w.objects.type[h], "sparks"),
            () -> assertEquals((float) ThrustSparks.THRUST_MASS, w.objects.mass[h], "of THRUST_MASS"),
            () -> assertEquals(Obj.GRAVITY | Obj.OWNERIMMUNE, w.objects.status[h], "owner immune, with gravity"),
            () -> assertEquals(p.id, w.objects.owner[h], "owned by the ship"),
            () -> assertEquals(8, w.objects.plRadius[h], "radius 8"));
    }
}
