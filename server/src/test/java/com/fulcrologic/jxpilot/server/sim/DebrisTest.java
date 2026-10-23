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
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.ThrustSparks;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Wire;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FPS14;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.P;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.copy;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.expand;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.lastBurst;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.live;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Debris (play.c Make_debris and callers)")
class DebrisTest {
    static final double EPS = 1e-9;

    /** Assert the pool objects {@code handles} are exactly the particles {@code ps} of a burst at (x, y). */
    static List<Executable> particlesMatch(World w, List<Integer> handles, List<P> ps, float x, float y,
                                           String what) {
        List<Executable> l = new ArrayList<>();
        Objects o = w.objects;
        l.add(() -> assertEquals(ps.size(), handles.size(), what + ": one object per generated particle"));
        for (int i = 0; i < Math.min(ps.size(), handles.size()); i++) {
            int h = handles.get(i);
            P p = ps.get(i);
            int k = i;
            l.add(() -> assertEquals((double) p.vx(), o.vx[h], 0.0, what + " " + k + ": vx equals the client's"));
            l.add(() -> assertEquals((double) p.vy(), o.vy[h], 0.0, what + " " + k + ": vy equals the client's"));
            l.add(() -> assertEquals((double) p.life(), o.life[h], 0.0, what + " " + k + ": life equals the client's"));
            l.add(() -> assertEquals((double) p.life(), o.fuseLife[h], 0.0, what + " " + k + ": fuse life = life"));
            l.add(() -> assertEquals(Click.fromPixels(x), o.cx[h], what + " " + k + ": starts at the burst x"));
            l.add(() -> assertEquals(Click.fromPixels(y), o.cy[h], what + " " + k + ": starts at the burst y"));
            l.add(() -> assertEquals(o.px[h], o.prevX[h], what + " " + k + ": previous x = x"));
        }
        return l;
    }

    @Nested
    @DisplayName("make (Make_debris)")
    class Make {
        Simulation s;
        World w;
        Player p;

        void setup(String extra, int size) {
            s = sim(extra, size, size, "_");
            w = s.world;
            p = ready(s, "P", 0, 300, 300);
        }

        int make(double x, double y, int type, int burstKind, int min, int max, double minSpeed, double maxSpeed,
                 int minLife, int maxLife) {
            return s.debris.make(x, y, 1.5, -2.25, p.id, 3, type, 3.5, Obj.GRAVITY, Records.ObjColor.BLUE, burstKind,
                8, min, max, 5, 40, minSpeed, maxSpeed, minLife, maxLife);
        }

        @Test
        @DisplayName("records the quantized burst and makes exactly the particles a client expands from it")
        void recordAndExpand() {
            setup(FRAME, 40);
            w.tick = 77;
            w.rng.seed(11);
            Rng ref = new Rng(11);
            int num = 10 + (int) (ref.nextFloat() * 10);
            int seed = ref.nextInt();
            long seq = w.events.burstSeq();
            int n = make(500.3, 600.7, Obj.DEBRIS, Records.BurstKind.DEBRIS, 10, 20, 3, 9, 10, 30);
            DebrisBurst d = lastBurst(w);
            int i = Events.burstIndex(seq);
            List<Integer> hs = live(w);
            Objects o = w.objects;
            int h0 = hs.get(0);
            List<Executable> checks = new ArrayList<>(List.of(
                () -> assertEquals(seq + 1, w.events.burstSeq(), "one burst is recorded"),
                () -> assertEquals(num, n, "count = min + (int)(rfrac * (max - min))"),
                () -> assertEquals(num, s.debris.lastCount, "lastCount is the number made"),
                () -> assertEquals(num, d.count, "the burst carries the count"),
                () -> assertEquals(seed, d.seed, "the seed is the next RNG int"),
                () -> assertEquals(500.5f, d.x, "x quantized to half pixels"),
                () -> assertEquals(600.5f, d.y, "y quantized to half pixels"),
                () -> assertEquals(1.5f, d.vx, "vx"),
                () -> assertEquals(-2.25f, d.vy, "vy"),
                () -> assertEquals(5, d.minDir, "min dir"),
                () -> assertEquals(35, d.dirSpan, "dir span = max - min"),
                () -> assertEquals(3.0f, d.minSpeed, "min speed"),
                () -> assertEquals(9.0f, d.maxSpeed, "max speed"),
                () -> assertEquals(10, d.minLife, "min life"),
                () -> assertEquals(30, d.maxLife, "max life"),
                () -> assertEquals(1979.0f, d.hypotenuse, "the world diagonal (int)hypot(1400, 1400)"),
                () -> assertEquals(Records.BurstKind.DEBRIS, w.events.burstKind[i], "burst kind"),
                () -> assertEquals(Records.ObjColor.BLUE, w.events.burstColor[i], "burst colour"),
                () -> assertEquals(p.id, w.events.burstOwner[i], "burst owner"),
                () -> assertEquals(77, w.events.burstTick[i], "burst tick"),
                () -> assertEquals(Obj.DEBRIS, o.type[h0], "particle type"),
                () -> assertEquals(3.5, o.mass[h0], EPS, "particle mass"),
                () -> assertEquals(Obj.GRAVITY, o.status[h0], "particle status"),
                () -> assertEquals(p.id, o.owner[h0], "particle owner"),
                () -> assertEquals(3, o.team[h0], "particle team"),
                () -> assertEquals(Records.ObjColor.BLUE, o.color[h0], "particle colour"),
                () -> assertEquals(8, o.plRange[h0], "pl_range = radius"),
                () -> assertEquals(8, o.plRadius[h0], "pl_radius = radius"),
                () -> assertEquals(0, o.mods[h0], "debris has no modifiers")));
            checks.addAll(particlesMatch(w, hs, expand(copy(d)), d.x, d.y, "particle"));
            assertAll(checks);
        }

        @Test
        @DisplayName("an origin outside a flat world makes nothing and draws no random number")
        void outsideFlat() {
            setup(FRAME, 40);
            w.rng.seed(3);
            int[] r = {
                make(-1, 300, Obj.DEBRIS, 0, 5, 5, 1, 2, 1, 2),
                make(1400, 300, Obj.DEBRIS, 0, 5, 5, 1, 2, 1, 2),
                make(300, -0.5, Obj.DEBRIS, 0, 5, 5, 1, 2, 1, 2),
                make(300, 1400, Obj.DEBRIS, 0, 5, 5, 1, 2, 1, 2)};
            int next = w.rng.nextInt();
            int count = w.objects.count();
            long bursts = w.events.burstSeq();
            int x0 = make(0, 300, Obj.DEBRIS, 0, 1, 1, 1, 2, 1, 2);
            int y0 = make(300, 0, Obj.DEBRIS, 0, 1, 1, 1, 2, 1, 2);
            assertAll(
                () -> assertEquals(0, r[0] + r[1] + r[2] + r[3], "nothing is made"),
                () -> assertEquals(0, count, "no objects"),
                () -> assertEquals(0, bursts, "no burst"),
                () -> assertEquals(new Rng(3).nextInt(), next, "the RNG is untouched"),
                () -> assertEquals(1, x0, "x = 0 is inside"),
                () -> assertEquals(1, y0, "y = 0 is inside"));
        }

        @Test
        @DisplayName("on a wrapping map the origin wraps by one world size")
        void wrapOrigin() {
            setup(FRAME + "edgeWrap: yes\n", 40);
            make(-10, 1405, Obj.DEBRIS, 0, 1, 1, 1, 2, 1, 2);
            DebrisBurst a = copy(lastBurst(w));
            make(1400, -3, Obj.DEBRIS, 0, 1, 1, 1, 2, 1, 2);
            DebrisBurst b = copy(lastBurst(w));
            int n0 = make(0, 1400, Obj.DEBRIS, 0, 1, 1, 1, 2, 1, 2);
            DebrisBurst c = copy(lastBurst(w));
            int n1 = make(700, 0, Obj.DEBRIS, 0, 1, 1, 1, 2, 1, 2);
            assertAll(
                () -> assertEquals(1390.0f, a.x, "x -10 wraps to 1390"),
                () -> assertEquals(5.0f, a.y, "y 1405 wraps to 5"),
                () -> assertEquals(0.0f, b.x, "x 1400 wraps to 0"),
                () -> assertEquals(1397.0f, b.y, "y -3 wraps to 1397"),
                () -> assertEquals(1, n0, "x = 0 is kept, y = 1400 wraps"),
                () -> assertEquals(0.0f, c.x, "x 0 stays 0"),
                () -> assertEquals(0.0f, c.y, "y 1400 wraps to 0"),
                () -> assertEquals(1, n1, "y = 0 is kept"));
        }

        @Test
        @DisplayName("lives are capped at shotLife when shotLife >= FPS; max life is at least min life")
        void lifeClamps() {
            setup(FRAME, 40);
            make(300, 300, Obj.DEBRIS, 0, 1, 1, 1, 2, 70, 80);
            DebrisBurst a = copy(lastBurst(w));
            make(300, 300, Obj.DEBRIS, 0, 1, 1, 1, 2, 10, 80);
            DebrisBurst b = copy(lastBurst(w));
            make(300, 300, Obj.DEBRIS, 0, 1, 1, 1, 2, 20, 10);
            DebrisBurst c = copy(lastBurst(w));
            make(300, 300, Obj.DEBRIS, 0, 1, 1, 1, 2, 60, 60);
            DebrisBurst e = copy(lastBurst(w));
            setup(FRAME + "shotLife: 59\n", 40);
            make(300, 300, Obj.DEBRIS, 0, 1, 1, 1, 2, 70, 80);
            DebrisBurst f = copy(lastBurst(w));
            setup(FPS14 + "shotLife: 14\n", 40);
            make(300, 300, Obj.DEBRIS, 0, 1, 1, 1, 2, 10, 80);
            DebrisBurst g = lastBurst(w);
            assertAll(
                () -> assertEquals(60, a.minLife, "min life above shotLife becomes shotLife"),
                () -> assertEquals(60, a.maxLife, "and so does max life"),
                () -> assertEquals(10, b.minLife, "min life below shotLife stays"),
                () -> assertEquals(60, b.maxLife, "max life above shotLife is capped"),
                () -> assertEquals(20, c.maxLife, "max life below min life is raised to it"),
                () -> assertEquals(60, e.maxLife, "lives equal to shotLife are kept"),
                () -> assertEquals(70, f.minLife, "no cap when shotLife < FPS (59 < 60)"),
                () -> assertEquals(80, f.maxLife, "max life kept when shotLife < FPS"),
                () -> assertEquals(14, g.maxLife, "at 14 fps shotLife 14 >= FPS caps"));
        }

        @Test
        @DisplayName("speeds are capped so speed * life stays within the world diagonal")
        void speedCaps() {
            setup(FRAME, 10);
            // hypotenuse (int)hypot(350, 350) = 494
            make(100, 100, Obj.DEBRIS, 0, 1, 1, 20, 50, 20, 30);
            DebrisBurst a = copy(lastBurst(w));
            make(100, 100, Obj.DEBRIS, 0, 1, 1, 30, 10, 1, 2);
            DebrisBurst b = copy(lastBurst(w));
            make(100, 100, Obj.DEBRIS, 0, 1, 1, 494 / 30.0, 494 / 20.0, 20, 30);
            DebrisBurst c = lastBurst(w);
            assertAll(
                () -> assertEquals(494.0f, a.hypotenuse, "the small world's diagonal"),
                // 494/30 = 16.4667 and 494/20 = 24.7, quantized to 1/16
                () -> assertEquals(263 / 16f, a.minSpeed, "min speed = hypotenuse / max life"),
                () -> assertEquals(395 / 16f, a.maxSpeed, "max speed = hypotenuse / min life"),
                () -> assertEquals(30.0f, b.minSpeed, "min speed within the limit is kept"),
                () -> assertEquals(30.0f, b.maxSpeed, "max speed below min speed is raised to it"),
                () -> assertEquals(263 / 16f, c.minSpeed, "speeds exactly at the limit are kept"));
        }

        @Test
        @DisplayName("the count is limited by the free pool slots; zero makes nothing")
        void poolLimit() {
            setup(FRAME, 40);
            int cap = w.objects.capacity;
            for (int i = 0; i < cap - 3; i++) {
                w.objects.alloc();
            }
            long seq = w.events.burstSeq();
            int n = make(300, 300, Obj.DEBRIS, 0, 10, 10, 1, 2, 1, 2);
            int countAfter = lastBurst(w).count;
            long seq2 = w.events.burstSeq();
            int none = make(300, 300, Obj.DEBRIS, 0, 10, 10, 1, 2, 1, 2);
            long seq3 = w.events.burstSeq();
            setup(FRAME, 40);
            int zero = make(300, 300, Obj.DEBRIS, 0, 0, 0, 1, 2, 1, 2);
            assertAll(
                () -> assertEquals(3, n, "only the 3 free slots are used"),
                () -> assertEquals(3, countAfter, "the burst says 3"),
                () -> assertEquals(seq + 1, seq2, "a burst was recorded"),
                () -> assertEquals(0, none, "a full pool makes nothing"),
                () -> assertEquals(seq2, seq3, "no burst for a full pool"),
                () -> assertEquals(0, zero, "a zero count makes nothing"),
                () -> assertEquals(0, w.events.burstSeq(), "and records no burst"));
        }

        @Test
        @DisplayName("burstKind -1 makes the same particles without recording a burst")
        void unrecorded() {
            setup(FRAME, 40);
            w.rng.seed(4);
            make(300, 300, Obj.DEBRIS, 0, 5, 9, 1, 2, 3, 7);
            DebrisBurst rec = copy(lastBurst(w));
            List<Integer> recorded = live(w);
            Objects o = w.objects;
            double[] vx = recorded.stream().mapToDouble(h -> o.vx[h]).toArray();
            setup(FRAME, 40);
            w.rng.seed(4);
            int n = make(300, 300, Obj.DEBRIS, -1, 5, 9, 1, 2, 3, 7);
            double[] vx2 = live(w).stream().mapToDouble(h -> w.objects.vx[h]).toArray();
            assertAll(
                () -> assertEquals(rec.count, n, "the same count"),
                () -> assertEquals(0, w.events.burstSeq(), "no burst recorded"),
                () -> assertEquals(java.util.Arrays.toString(vx), java.util.Arrays.toString(vx2),
                    "the same particle velocities"));
        }

        @Test
        @DisplayName("SHOT fragments are cluster shots: no GRAVITY without shotsGravity, kinetic mass")
        void clusterShots() {
            setup(FRAME + "shotsGravity: no\n", 40);
            make(300, 300, Obj.SHOT, 0, 4, 4, 10, 30, 3, 7);
            List<P> ps = expand(copy(lastBurst(w)));
            List<Integer> hs = live(w);
            Objects o = w.objects;
            int h0 = hs.get(0);
            int h1 = hs.get(1);
            double k0 = 3.5 / ((ps.get(0).speed() / 21.0) * (ps.get(0).speed() / 21.0));
            double k1 = 3.5 / ((ps.get(1).speed() / 21.0) * (ps.get(1).speed() / 21.0));
            int st = o.status[h0];
            double m0 = o.mass[h0];
            double m1 = o.mass[h1];
            int mods = o.mods[h0];
            setup(FRAME, 40);
            make(300, 300, Obj.SHOT, 0, 4, 4, 10, 30, 3, 7);
            int stGrav = w.objects.status[live(w).get(0)];
            setup(FRAME, 40);
            w.params.shotHitFuelDrainUsesKineticEnergy = false;
            make(300, 300, Obj.SHOT, 0, 4, 4, 10, 30, 3, 7);
            double flat = w.objects.mass[live(w).get(0)];
            assertAll(
                () -> assertEquals(0, st, "GRAVITY is cleared without shotsGravity"),
                () -> assertEquals(Obj.GRAVITY, stGrav, "GRAVITY is kept with shotsGravity"),
                () -> assertEquals(Obj.MOD_CLUSTER, mods, "fragments carry CLUSTER"),
                () -> assertEquals(k0, m0, 1e-6, "mass / (speed/shotSpeed)^2 for the first fragment"),
                () -> assertEquals(k1, m1, 1e-6, "and for the second"),
                () -> assertTrue(Math.abs(m0 - m1) > 1e-6, "different speeds give different masses"),
                () -> assertEquals(3.5, flat, EPS, "without kinetic drain the mass is unchanged"));
        }
    }

    @Nested
    @DisplayName("callers")
    class Callers {
        Simulation s;
        World w;
        Player p;

        void setup(String extra) {
            s = sim(extra, 40, 40, "_");
            w = s.world;
            p = ready(s, "P", 0, 500, 600);
            p.vx = 2;
            p.vy = -1;
        }

        @Test
        @DisplayName("explodeShip (Explode_fighter): counts from fuel and mass, speeds and lives from mass")
        void explodeShip() {
            setup(FRAME);
            double m = p.mass;
            int min = (int) (1 + p.fuel / (8.0 * 256)) >> 1;
            int max = (int) ((int) (1 + p.fuel / (8.0 * 256)) + m * 2.0) >> 1;
            w.rng.seed(21);
            Rng ref = new Rng(21);
            int num = min + (int) (ref.nextFloat() * (max - min));
            s.debris.explodeShip(p);
            DebrisBurst d = lastBurst(w);
            int i = Events.burstIndex(w.events.burstSeq() - 1);
            int h0 = live(w).get(0);
            assertAll(
                () -> assertEquals(63, min, "min = (1 + 256000/2048) / 2 = 63"),
                () -> assertEquals(num, d.count, "count between (1 + fuel/2048)/2 and (that + 2 mass)/2"),
                () -> assertEquals(500.0f, d.x, "at the ship"),
                () -> assertEquals(2.0f, d.vx, "with the ship's velocity"),
                () -> assertEquals(0, d.minDir, "every direction"),
                () -> assertEquals(Const.RES - 1, d.dirSpan, "span RES-1"),
                () -> assertEquals(20.0f, d.minSpeed, "speed from 20"),
                () -> assertEquals(20.0f + (((int) m) >> 1), d.maxSpeed, "to 20 + (int)mass/2"),
                () -> assertEquals(5, d.minLife, "life from 5"),
                () -> assertEquals((int) (5 + m * 1.5), d.maxLife, "to 5 + 1.5 mass"),
                () -> assertEquals(Records.BurstKind.DEBRIS, w.events.burstKind[i], "a DEBRIS burst"),
                () -> assertEquals(Records.ObjColor.RED, w.events.burstColor[i], "red"),
                () -> assertEquals(p.id, w.events.burstOwner[i], "owned by the ship"),
                () -> assertEquals(Obj.DEBRIS, w.objects.type[h0], "debris"),
                () -> assertEquals(3.5, w.objects.mass[h0], EPS, "of mass 3.5"),
                () -> assertEquals(Obj.GRAVITY, w.objects.status[h0], "with gravity"),
                () -> assertEquals(8, w.objects.plRange[h0], "radius 8"),
                () -> assertEquals(p.id, w.objects.owner[h0], "owned by the ship"));
        }

        @Test
        @DisplayName("wallSparks: intensity/2..intensity owner-immune bounce sparks around the wall normal")
        void wallSparks() {
            setup(FRAME);
            w.rng.seed(8);
            Rng ref = new Rng(8);
            int num = 10 + (int) (ref.nextFloat() * 10);
            s.debris.wallSparks(p, 32, 20);
            DebrisBurst d = copy(lastBurst(w));
            int i = Events.burstIndex(w.events.burstSeq() - 1);
            int h0 = live(w).get(0);
            Objects o = w.objects;
            int st = o.status[h0];
            double mass = o.mass[h0];
            int range = o.plRange[h0];
            s.debris.wallSparks(p, 0, 20);
            DebrisBurst e = lastBurst(w);
            assertAll(
                () -> assertEquals(num, d.count, "count = 10 + (int)(rfrac * 10)"),
                () -> assertEquals(0, d.minDir, "from wall dir - RES/4"),
                () -> assertEquals(64, d.dirSpan, "over RES/2"),
                () -> assertEquals(96, e.minDir, "wall dir 0 starts at -32 = 96"),
                () -> assertEquals(20.0f, d.minSpeed, "speed from 20"),
                () -> assertEquals(25.0f, d.maxSpeed, "to 20 + intensity/4"),
                () -> assertEquals(10, d.minLife, "life from 10"),
                () -> assertEquals(20, d.maxLife, "to 10 + intensity/2"),
                () -> assertEquals(Records.BurstKind.WALL_SPARK, w.events.burstKind[i], "a WALL_SPARK burst"),
                () -> assertEquals(Obj.GRAVITY | Obj.OWNERIMMUNE | Obj.FROMBOUNCE, st, "owner-immune bounce sparks"),
                () -> assertEquals(3.5, mass, EPS, "of mass 3.5"),
                () -> assertEquals(1, range, "radius 1"));
        }

        /** The two bursts ThrustSparks computes for the ship's wire-quantized state this tick. */
        DebrisBurst[] expected() {
            DebrisBurst n = new DebrisBurst();
            DebrisBurst a = new DebrisBurst();
            int dir = p.dir;
            double qx = Wire.posToU16(p.cx / 64.0) * 0.5;
            double qy = Wire.posToU16(p.cy / 64.0) * 0.5;
            ThrustSparks.compute(ThrustSparks.seed(w.tick, p.id), (float) (qx + p.ship.engineX[dir]),
                (float) (qy + p.ship.engineY[dir]), dir, Wire.i16ToVel(Wire.velToI16(p.vx)),
                Wire.i16ToVel(Wire.velToI16(p.vy)), p.power, p.afterburners(), w.params.shotLife, w.params.fps,
                (float) w.params.dt, (float) w.params.hypotenuse, new Rng(0), n, a);
            return new DebrisBurst[] {n, a};
        }

        @Test
        @DisplayName("thrust sparks equal ThrustSparks from the wire-quantized state; afterburner sparks are blue and heavier")
        void thrust() {
            setup(FPS14);
            p.initPixels(500.3, 600.9);
            p.vx = 1.23;
            p.vy = -0.51;
            p.dir = 10;
            p.items[Item.AFTERBURNER.ordinal()] = 3;
            w.tick = 1234;
            DebrisBurst[] e = expected();
            long bursts = w.events.burstSeq();
            for (int t = 0; t < 40 && e[0].count + e[1].count == 0; t++) {
                w.tick++;
                e = expected();
            }
            DebrisBurst[] ex = e;
            s.debris.thrust(p);
            List<Integer> hs = live(w);
            List<P> pn = expand(ex[0]);
            List<P> pa = expand(ex[1]);
            List<Executable> checks = new ArrayList<>();
            checks.add(() -> assertTrue(ex[0].count > 0 && ex[1].count > 0, "both bursts have sparks this tick"));
            checks.add(() -> assertEquals(bursts, w.events.burstSeq(), "thrust sparks are not recorded"));
            checks.addAll(particlesMatch(w, hs.subList(0, Math.min(pn.size(), hs.size())), pn, ex[0].x, ex[0].y,
                "normal spark"));
            checks.addAll(particlesMatch(w, hs.subList(Math.min(pn.size(), hs.size()), hs.size()), pa, ex[1].x, ex[1].y,
                "afterburner spark"));
            Objects o = w.objects;
            int hn = hs.get(0);
            int ha = hs.get(hs.size() - 1);
            checks.add(() -> assertEquals(ThrustSparks.THRUST_MASS, o.mass[hn], EPS, "normal sparks: THRUST_MASS"));
            checks.add(() -> assertEquals(Records.ObjColor.RED, o.color[hn], "normal sparks are red"));
            checks.add(() -> assertEquals(ThrustSparks.THRUST_MASS * 4.2, o.mass[ha], EPS,
                "afterburner sparks: THRUST_MASS * ALT_SPARK_MASS_FACT"));
            checks.add(() -> assertEquals(Records.ObjColor.BLUE, o.color[ha], "afterburner sparks are blue"));
            checks.add(() -> assertEquals(Obj.GRAVITY | Obj.OWNERIMMUNE, o.status[hn], "owner immune with gravity"));
            checks.add(() -> assertEquals(Obj.SPARK, o.type[ha], "sparks"));
            checks.add(() -> assertEquals(8, o.plRange[ha], "radius 8"));
            checks.add(() -> assertEquals(p.id, o.owner[hn], "owned by the ship"));
            assertAll(checks);
        }

        @Test
        @DisplayName("thrust sparks from an engine outside a flat world are dropped; a wrapping map wraps them")
        void thrustOutside() {
            setup(FRAME);
            p.initPixels(2, 600);
            p.dir = 0;
            int made = 0;
            for (int t = 0; t < 20; t++) {
                w.tick = t;
                s.debris.thrust(p);
                made += w.objects.count();
            }
            int flat = made;
            setup(FRAME + "edgeWrap: yes\n");
            p.initPixels(2, 600);
            p.dir = 0;
            w.tick = 5;
            DebrisBurst[] e = expected();
            s.debris.thrust(p);
            int h0 = live(w).get(0);
            float wx = (float) w.wrapXPixel(e[0].x);
            assertAll(
                () -> assertTrue(p.ship.engineX[0] < -2, "the engine is behind the ship (x < 0)"),
                () -> assertEquals(0, flat, "nothing is made outside a flat world"),
                () -> assertTrue(w.objects.count() > 0, "sparks are made on a wrapping map"),
                () -> assertEquals(Click.fromPixels(wx), w.objects.cx[h0], "at the wrapped engine x"));
        }

        /** Sparks made by one thrust tick with the ship at (x, y) heading dir, on a tick with normal sparks. */
        int thrustAt(double x, double y, int dir) {
            w.objects.clear();
            p.initPixels(x, y);
            p.vx = 0;
            p.vy = 0;
            p.dir = dir;
            w.tick = 0;
            while (expected()[0].count == 0) {
                w.tick++;
            }
            s.debris.thrust(p);
            return w.objects.count();
        }

        @Test
        @DisplayName("an engine exactly on the low edge is inside, on the high edge outside")
        void thrustEdges() {
            setup(FRAME);
            assertAll(
                () -> assertEquals(-9.0, p.ship.engineX[0], 1e-12, "the engine is 9 px behind"),
                () -> assertTrue(thrustAt(9, 600, 0) > 0, "engine at x = 0 makes sparks"),
                () -> assertEquals(0, thrustAt(1391, 600, 64), "engine at x = 1400 makes none"),
                () -> assertTrue(thrustAt(600, 9, 32) > 0, "engine at y = 0 makes sparks"),
                () -> assertEquals(0, thrustAt(600, 1391, 96), "engine at y = 1400 makes none"),
                () -> assertEquals(0, thrustAt(600, 8, 32), "engine at y = -1 makes none"));
        }

        @Test
        @DisplayName("the RES heading survives the wire: dirFromRadians(dir * 2pi / RES) == dir")
        void headingRoundTrip() {
            List<Executable> l = new ArrayList<>();
            for (int d = 0; d < Const.RES; d++) {
                int dir = d;
                l.add(() -> assertEquals(dir, ThrustSparks.dirFromRadians(dir * 2 * Math.PI / Const.RES),
                    "heading " + dir + " round-trips"));
            }
            assertAll(l);
        }

        @Test
        @DisplayName("turnThrust: num sparks sideways from hull point 0, speed 1..3, life 1..2 FPS")
        void turnThrust() {
            setup(FRAME);
            p.dir = 10;
            p.turnAcc = 1;
            s.debris.turnThrust(p, 5);
            DebrisBurst d = copy(lastBurst(w));
            int i = Events.burstIndex(w.events.burstSeq() - 1);
            int h0 = live(w).get(0);
            int range = w.objects.plRange[h0];
            int st = w.objects.status[h0];
            p.turnAcc = 0;
            s.debris.turnThrust(p, 5);
            DebrisBurst e = lastBurst(w);
            // dir 10 + RES/4 = 42: (int)(42 - 12.8 - 1) = 28 .. (int)(42 + 12.8 + 1) = 55
            assertAll(
                () -> assertEquals(5, d.count, "exactly num sparks"),
                () -> assertEquals(28, d.minDir, "turning left: from dir + RES/4 - RES*0.1 - 1"),
                () -> assertEquals(27, d.dirSpan, "to dir + RES/4 + RES*0.1 + 1"),
                () -> assertEquals(92, e.minDir, "not turning left (turnAcc 0): dir + 3RES/4 = 106 -> (int)(92.2) = 92"),
                () -> assertEquals(1.0f, d.minSpeed, "speed from 1"),
                () -> assertEquals(3.0f, d.maxSpeed, "to 3"),
                () -> assertEquals(1, d.minLife, "life from 1"),
                () -> assertEquals(60, d.maxLife, "to 2*FPS = 120, capped at shotLife 60"),
                () -> assertEquals((float) (int) (500 + p.ship.ptX[0][10]), d.x, "from hull point 0 x (int)"),
                () -> assertEquals((float) (int) (600 + p.ship.ptY[0][10]), d.y, "from hull point 0 y (int)"),
                () -> assertTrue(Math.abs(p.ship.ptY[0][10]) >= 1, "hull point 0 has a y offset at heading 10"),
                () -> assertEquals(Records.BurstKind.SPARK, w.events.burstKind[i], "a SPARK burst"),
                () -> assertEquals(1, range, "radius 1"),
                () -> assertEquals(Obj.GRAVITY | Obj.OWNERIMMUNE, st, "owner immune"));
        }

        @Test
        @DisplayName("a cloaked ship makes no turn sparks unless cloakedExhaust")
        void turnThrustCloaked() {
            setup(FRAME + "cloakedExhaust: no\n");
            p.used |= Has.CLOAKING_DEVICE;
            s.debris.turnThrust(p, 5);
            long none = w.events.burstSeq();
            setup(FRAME + "cloakedExhaust: no\n");
            s.debris.turnThrust(p, 5);
            long uncloaked = w.events.burstSeq();
            setup(FRAME);
            p.used |= Has.CLOAKING_DEVICE;
            s.debris.turnThrust(p, 5);
            assertAll(
                () -> assertEquals(1, uncloaked, "an uncloaked ship makes sparks whatever cloakedExhaust says"),
                () -> assertEquals(0, none, "no sparks without cloakedExhaust"),
                () -> assertEquals(1, w.events.burstSeq(), "sparks with cloakedExhaust"));
        }
    }
}
