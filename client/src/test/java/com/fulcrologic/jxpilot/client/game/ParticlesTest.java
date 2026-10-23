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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.ThrustSparks;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Wire;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParticlesTest {
    record P(float x, float y, float vx, float vy, int life, int color) {}

    static List<P> pool(ParticlePool p) {
        List<P> l = new ArrayList<>();
        for (int i = 0; i < p.count(); i++) {
            l.add(new P(p.originX(i), p.originY(i), p.vx(i), p.vy(i), (int) p.life(i), p.color(i)));
        }
        return l;
    }

    @Nested
    @DisplayName("ThrustSparkEmitter")
    class Emitter {
        /**
         * What a server does per PROTOCOL.md (server Debris.thrust), written out independently of the emitter:
         * the engine offset rotated in float over C's tcos/tsin tables, computeWire, then the engine position
         * wrapped into the (wrapping) world.
         */
        List<P> serverSide(int tick, int id, double x, double y, double heading, float vx, float vy, float power,
                           int ab, int ex, int ey) {
            int dir = ThrustSparks.dirFromU16(Wire.angleToU16(heading));
            float c = (float) StrictMath.cos(dir * (2.0 * Math.PI / 128));
            float s = (float) StrictMath.sin(dir * (2.0 * Math.PI / 128));
            DebrisBurst n = new DebrisBurst();
            DebrisBurst a = new DebrisBurst();
            Rng rng = new Rng(0);
            float hyp = (int) StrictMath.hypot(700.0, 350.0);
            ThrustSparks.computeWire(tick, id, x, y, c * ex - s * ey, s * ex + c * ey, dir, vx, vy, power, ab, 60, 15,
                hyp, rng, n, a);
            float engX = (float) wrap(n.x, 700);
            float engY = (float) wrap(n.y, 350);
            List<P> out = new ArrayList<>();
            n.generate(new Rng(1), (i, pvx, pvy, sp, life) -> out.add(new P(engX, engY, pvx, pvy, life,
                Records.ObjColor.RED)));
            a.generate(new Rng(2), (i, pvx, pvy, sp, life) -> out.add(new P(engX, engY, pvx, pvy, life,
                Records.ObjColor.BLUE)));
            return out;
        }

        static double wrap(double v, double size) {
            return v < 0 ? v + size : (v >= size ? v - size : v);
        }

        @Test
        @DisplayName("produces exactly the server-side particles for the same tick, ship and state")
        void matchesServer() {
            ThrustSparkEmitter em = new ThrustSparkEmitter();
            em.configure(60, 15, 700, 350, true);
            ParticlePool p = new ParticlePool(1000);
            double heading = Wire.u16ToAngle(Wire.angleToU16(1.1));
            em.emit(1234, 5, 300.3, 200.2, heading, 3.01f, -1.02f, 55, 15, -9, 4, false, p);
            List<P> expected = serverSide(1234, 5, 300.3, 200.2, heading, 3.01f, -1.02f, 55, 15, -9, 4);
            ParticlePool q = new ParticlePool(1000);
            em.emit(1235, 5, 300, 200, heading, 3, -1, 55, 15, -9, 4, false, q);
            assertAll(
                () -> assertFalse(expected.isEmpty(), "the scenario produces sparks"),
                () -> assertTrue(expected.stream().anyMatch(e -> e.color() == Records.ObjColor.BLUE),
                    "afterburners give blue sparks"),
                () -> assertEquals(expected, pool(p), "same origin, velocities, lives and colours in order"),
                () -> assertEquals(1234.0, p.birth(0), "born at the tick"),
                () -> assertEquals(Records.BurstKind.SPARK, p.kind(0), "spark kind"),
                () -> assertNotEquals(pool(p), pool(q), "another tick gives other sparks"));
        }

        @Test
        @DisplayName("the engine offset is rotated by the RES heading and wrapped into the world; outside a bounded world nothing")
        void engine() {
            ThrustSparkEmitter em = new ThrustSparkEmitter();
            em.configure(60, 15, 700, 350, true);
            ParticlePool p0 = new ParticlePool(1000);
            em.emit(1, 1, 5, 100, 0, 0, 0, 45, 0, -9, 2, false, p0);
            ParticlePool p90 = new ParticlePool(1000);
            em.emit(1, 1, 100, 100, Math.PI / 2, 0, 0, 45, 0, -9, 2, false, p90);
            ThrustSparkEmitter flat = new ThrustSparkEmitter();
            flat.configure(60, 15, 700, 350, false);
            ParticlePool pf = new ParticlePool(1000);
            flat.emit(1, 1, 5, 100, 0, 0, 0, 45, 0, -9, 2, false, pf);
            assertAll(
                () -> assertEquals(696f, p0.originX(0), 1e-4, "heading 0: x 5 - 9 wraps to 696"),
                () -> assertEquals(102f, p0.originY(0), 1e-4, "heading 0: y + 2"),
                () -> assertEquals(98f, p90.originX(0), 1e-4, "heading 90: x - ey"),
                () -> assertEquals(91f, p90.originY(0), 1e-4, "heading 90: y + ex"),
                () -> assertEquals(0, pf.count(), "without edgeWrap an engine outside the world makes no sparks (as the server)"),
                () -> assertEquals(-9.0, ThrustSparkEmitter.rotX(-9, 2, 128), 1e-12, "dir is taken mod RES"),
                () -> assertEquals(2.0, ThrustSparkEmitter.rotY(-9, 2, 0), 1e-12, "rotY at heading 0 is ey"));
        }

        @Test
        @DisplayName("resDir recovers the server's RES dir from SELF f32 and SHIP u16 headings; half steps round up")
        void resDir() {
            List<Integer> fromSelf = new ArrayList<>();
            List<Integer> fromShip = new ArrayList<>();
            List<Integer> dirs = new ArrayList<>();
            for (int dir = 0; dir < 128; dir++) {
                dirs.add(dir);
                fromSelf.add(ThrustSparkEmitter.resDir((float) (dir * 2 * Math.PI / 128)));
                fromShip.add(ThrustSparkEmitter.resDir(Wire.u16ToAngle(dir * 512)));
            }
            double step = 2 * Math.PI / 128;
            assertAll(
                () -> assertEquals(dirs, fromSelf, "SELF f32 dir * 2pi/RES gives dir back"),
                () -> assertEquals(dirs, fromShip, "SHIP u16 dir * 512 gives dir back (as dirFromU16)"),
                () -> assertEquals(6, ThrustSparkEmitter.resDir(5.5 * step), "a half step rounds up"),
                () -> assertEquals(5, ThrustSparkEmitter.resDir(5.49 * step), "below the half step rounds down"),
                () -> assertEquals(0, ThrustSparkEmitter.resDir(127.5 * step), "127.5 rounds up and wraps to 0"),
                () -> assertEquals(127, ThrustSparkEmitter.resDir(-step), "negative headings wrap"));
        }

        @Test
        @DisplayName("quantizers match the wire; the hypotenuse is an int as in C")
        void quantizers() {
            assertAll(
                () -> assertEquals(100.5, ThrustSparkEmitter.quantizePos(100.26), "to the nearest half pixel"),
                () -> assertEquals(100.0, ThrustSparkEmitter.quantizePos(100.24), "down to the half pixel"),
                () -> assertEquals(1.0f, ThrustSparkEmitter.quantizeVel(1.03), "16.48 sixteenths round to 16"),
                () -> assertEquals(1.0625f, ThrustSparkEmitter.quantizeVel(1.04), "16.64 sixteenths round to 17"),
                () -> assertEquals(-1.0625f, ThrustSparkEmitter.quantizeVel(-1.06), "negative velocities"),
                () -> assertEquals(782, ThrustSparkEmitter.hypotenuse(700, 350), "782.6 truncates to 782"));
        }

        @Test
        @DisplayName("the engine offset is bit for bit the server's ShipGeom engine point at every RES heading")
        void engineOffsetMatchesServerGeometry() {
            com.fulcrologic.jxpilot.server.world.ShipGeom g = com.fulcrologic.jxpilot.server.world.ShipGeom.defaultShip();
            int ex = com.fulcrologic.jxpilot.common.math.ShipShape.defaultShip().engineX;
            int ey = com.fulcrologic.jxpilot.common.math.ShipShape.defaultShip().engineY;
            List<Float> serverX = new ArrayList<>();
            List<Float> serverY = new ArrayList<>();
            List<Float> clientX = new ArrayList<>();
            List<Float> clientY = new ArrayList<>();
            for (int dir = 0; dir < 128; dir++) {
                serverX.add(g.engineX[dir]);
                serverY.add(g.engineY[dir]);
                clientX.add(ThrustSparkEmitter.rotX(ex, ey, dir));
                clientY.add(ThrustSparkEmitter.rotY(ex, ey, dir));
            }
            assertAll(
                () -> assertEquals(serverX, clientX, "engine x equals the server's ship->engine[dir].x"),
                () -> assertEquals(serverY, clientY, "engine y equals the server's ship->engine[dir].y"));
        }

        @Test
        @DisplayName("one tick is one C frame: the spark count is exactly C's per-frame count")
        void exactPerFrameCount() {
            List<Integer> expected = new ArrayList<>();
            List<Integer> got = new ArrayList<>();
            List<Integer> expectedAlt = new ArrayList<>();
            List<Integer> gotAlt = new ArrayList<>();
            DebrisBurst n = new DebrisBurst();
            DebrisBurst a = new DebrisBurst();
            for (int seed = 1; seed <= 200; seed++) {
                Rng r = new Rng(0);
                r.seed(seed);
                int total = (int) (55 * 0.15 + (r.nextInt() & 3) + 1);
                expected.add(total);
                expectedAlt.add(((total - 1) * 6 >> 4) + 1);
                ThrustSparks.compute(seed, 100, 100, 0, 0, 0, 55, 6, 60, 14, 782, new Rng(0), n, a);
                got.add(n.count + a.count);
                gotAlt.add(a.count);
            }
            assertAll(
                () -> assertEquals(expected, got, "(int)(power*0.15 + rand(0..3) + 1) sparks per tick, no rounding noise"),
                () -> assertEquals(expectedAlt, gotAlt, "C's afterburner split ((n-1)*ab >> 4) + 1"));
        }
    }

    @Nested
    @DisplayName("ParticlePool")
    class Pool {
        ParticlePool withOne(float x, float vx, int life, double birth) {
            ParticlePool p = new ParticlePool(4);
            DebrisBurst d = new DebrisBurst();
            d.x = x;
            d.y = 50;
            d.vx = vx;
            d.vy = 0;
            d.count = 1;
            d.minLife = life - 1;
            d.maxLife = life - 1;
            d.dirSpan = 0;
            d.minSpeed = 0;
            d.maxSpeed = 0;
            p.spawn(d, new Rng(0), birth, Records.BurstKind.DEBRIS, Records.ObjColor.WHITE);
            return p;
        }

        @Test
        @DisplayName("moves linearly by (renderTick - birth) * frameFraction, hides unborn, expires at life")
        void step() {
            ParticlePool p = withOne(100, 4, 10, 10);
            float life = p.life(0);
            p.step(9, 0.5f, 700, 350, true);
            boolean unborn = p.visible(0);
            float unbornX = p.x(0);
            p.step(14, 0.5f, 700, 350, true);
            float x = p.x(0);
            boolean vis = p.visible(0);
            p.step(29.9, 0.5f, 700, 350, true);
            int alive = p.count();
            p.step(30, 0.5f, 700, 350, true);
            assertAll(
                () -> assertEquals(10f, life, "life 10 frames from the burst"),
                () -> assertFalse(unborn, "a particle born after the render tick is invisible"),
                () -> assertEquals(100f, unbornX, "and sits at its origin"),
                () -> assertTrue(vis, "visible once born"),
                () -> assertEquals(108f, x, 1e-4, "age (14-10)*0.5 = 2 frames at 4 px/frame"),
                () -> assertEquals(1, alive, "alive at age 9.95 frames"),
                () -> assertEquals(0, p.count(), "removed at age 10 = life"));
        }

        @Test
        @DisplayName("wraps in wrapping worlds; leaves the world without wrap")
        void edges() {
            ParticlePool w = withOne(690, 8, 100, 0);
            w.step(10, 0.25f, 700, 350, true);
            ParticlePool n = withOne(690, 8, 100, 0);
            n.step(10, 0.25f, 700, 350, false);
            ParticlePool in = withOne(600, 8, 100, 0);
            in.step(10, 0.25f, 700, 350, false);
            assertAll(
                () -> assertEquals(10f, w.x(0), 1e-4, "690 + 20 wraps to 10"),
                () -> assertEquals(0, n.count(), "removed at the edge without wrap"),
                () -> assertEquals(620f, in.x(0), 1e-4, "inside the world it just moves"));
        }

        static DebrisBurst still(float x, int count, int life) {
            DebrisBurst d = new DebrisBurst();
            d.x = x;
            d.count = count;
            d.minLife = life - 1;
            d.maxLife = life - 1;
            return d;
        }

        @Test
        @DisplayName("removal keeps the spawn order of the survivors")
        void removalKeepsOrder() {
            ParticlePool p = new ParticlePool(8);
            p.spawn(still(10, 1, 100), new Rng(0), 0, 0, 1);
            p.spawn(still(20, 1, 1), new Rng(0), 0, 0, 2);
            p.spawn(still(30, 1, 100), new Rng(0), 0, 0, 3);
            p.spawn(still(40, 1, 100), new Rng(0), 0, 0, 4);
            p.step(8, 0.25f, 700, 350, true);
            p.step(9, 0.25f, 700, 350, true);
            assertAll(
                () -> assertEquals(3, p.count(), "the life-1 particle expired at age 2 frames; stale slots stay dead"),
                () -> assertEquals(List.of(1, 3, 4), List.of(p.color(0), p.color(1), p.color(2)),
                    "the others keep their order"),
                () -> assertEquals(List.of(10f, 30f, 40f), List.of(p.originX(0), p.originX(1), p.originX(2)),
                    "with their own data"));
        }

        @Test
        @DisplayName("a full pool evicts the oldest particles for new ones, also across the ring's wrap")
        void evictsOldest() {
            ParticlePool p = new ParticlePool(3);
            p.spawn(still(10, 2, 100), new Rng(0), 0, 0, 1);
            p.spawn(still(20, 2, 100), new Rng(0), 0, 0, 2);
            List<Integer> afterFirst = List.of(p.color(0), p.color(1), p.color(2));
            p.spawn(still(30, 2, 100), new Rng(0), 0, 0, 3);
            p.step(4, 0.25f, 700, 350, true);
            assertAll(
                () -> assertEquals(List.of(1, 2, 2), afterFirst, "the oldest of the first burst made room"),
                () -> assertEquals(3, p.evicted(), "three evictions"),
                () -> assertEquals(List.of(2, 3, 3), List.of(p.color(0), p.color(1), p.color(2)),
                    "the newest three survive, oldest first"),
                () -> assertEquals(List.of(20f, 30f, 30f), List.of(p.x(0), p.x(1), p.x(2)),
                    "stepped positions follow the ring order"),
                () -> assertTrue(p.visible(0) && p.visible(2), "stepped particles are visible"));
        }

        @Test
        @DisplayName("a burst generates at most min(MAX_BURST, capacity) particles: the generator's first ones")
        void burstCap() {
            ParticlePool small = new ParticlePool(5);
            DebrisBurst d = WorldFixture.burst();
            d.count = 9;
            small.spawn(d, new Rng(0), 0, 0, 1);
            List<Float> expected = new ArrayList<>();
            DebrisBurst e = WorldFixture.burst();
            e.count = 5;
            e.generate(new Rng(0), (k, vx, vy, sp, life) -> expected.add(vx));
            List<Float> got = new ArrayList<>();
            for (int k = 0; k < small.count(); k++) {
                got.add(small.vx(k));
            }
            ParticlePool big = new ParticlePool(4 * ParticlePool.MAX_BURST);
            DebrisBurst huge = WorldFixture.burst();
            huge.count = 0xFFFF;
            big.spawn(huge, new Rng(0), 0, 0, 1);
            assertAll(
                () -> assertEquals(expected, got, "capacity 5: the generator's first 5, in order"),
                () -> assertEquals(0, small.evicted(), "a burst never evicts its own particles"),
                () -> assertEquals(4, small.capped(), "the other 4 are counted as capped"),
                () -> assertEquals(9, d.count, "the burst itself is left unchanged"),
                () -> assertEquals(ParticlePool.MAX_BURST, big.count(), "a u16-sized burst is cut at MAX_BURST"),
                () -> assertEquals(0xFFFF - ParticlePool.MAX_BURST, big.capped(), "the rest counted"));
        }

        @Test
        @DisplayName("own exhaust steps at the own tick, everything else at the render tick")
        void ownTimeBase() {
            ParticlePool p = new ParticlePool(4);
            DebrisBurst d = still(100, 1, 50);
            d.vx = 4;
            p.spawn(d, new Rng(0), 10, 0, 1, false);
            p.spawn(d, new Rng(0), 10, 0, 2, true);
            p.step(12, 14, 0.5f, 700, 350, true);
            assertAll(
                () -> assertFalse(p.own(0), "a normal particle"),
                () -> assertTrue(p.own(1), "own exhaust"),
                () -> assertEquals(104f, p.x(0), 1e-4, "render tick 12: age 1 frame"),
                () -> assertEquals(108f, p.x(1), 1e-4, "own tick 14: age 2 frames"));
        }
    }
}
