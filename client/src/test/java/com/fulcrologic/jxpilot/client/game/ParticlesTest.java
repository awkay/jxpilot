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
        /** What a server does per PROTOCOL.md, written out independently of the emitter. */
        List<P> serverSide(int tick, int id, double x, double y, double heading, float vx, float vy, double power,
                           int ab, int ex, int ey) {
            int dir = ThrustSparks.dirFromU16(Wire.angleToU16(heading));
            double s = StrictMath.sin(dir * 2 * Math.PI / 128);
            double c = StrictMath.sin(((dir + 32) & 127) * 2 * Math.PI / 128);
            float engX = (float) (x + c * ex - s * ey);
            float engY = (float) (y + s * ex + c * ey);
            DebrisBurst n = new DebrisBurst();
            DebrisBurst a = new DebrisBurst();
            Rng rng = new Rng(0);
            float hyp = (float) Math.sqrt(700.0 * 700 + 350.0 * 350);
            ThrustSparks.compute(ThrustSparks.seed(tick, id), engX, engY, dir, vx, vy, power, ab, 60, 15, 0.25f, hyp,
                rng, n, a);
            List<P> out = new ArrayList<>();
            n.generate(new Rng(1), (i, pvx, pvy, sp, life) -> out.add(new P(engX, engY, pvx, pvy, life,
                Records.ObjColor.RED)));
            a.generate(new Rng(2), (i, pvx, pvy, sp, life) -> out.add(new P(engX, engY, pvx, pvy, life,
                Records.ObjColor.BLUE)));
            return out;
        }

        @Test
        @DisplayName("produces exactly the server-side particles for the same tick, ship and state")
        void matchesServer() {
            ThrustSparkEmitter em = new ThrustSparkEmitter();
            em.configure(60, 15, 0.25f, 700, 350, true);
            ParticlePool p = new ParticlePool(1000);
            double heading = Wire.u16ToAngle(Wire.angleToU16(1.1));
            em.emit(1234, 5, 300, 200, heading, 3, -1, 55, 15, -9, 4, p);
            List<P> expected = serverSide(1234, 5, 300, 200, heading, 3, -1, 55, 15, -9, 4);
            ParticlePool q = new ParticlePool(1000);
            em.emit(1235, 5, 300, 200, heading, 3, -1, 55, 15, -9, 4, q);
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
        @DisplayName("the engine offset is rotated by the RES heading and wrapped into the world")
        void engine() {
            ThrustSparkEmitter em = new ThrustSparkEmitter();
            em.configure(60, 15, 0.25f, 700, 350, true);
            ParticlePool p0 = new ParticlePool(1000);
            em.emit(1, 1, 5, 100, 0, 0, 0, 45, 0, -9, 2, p0);
            ParticlePool p90 = new ParticlePool(1000);
            em.emit(1, 1, 100, 100, Math.PI / 2, 0, 0, 45, 0, -9, 2, p90);
            ThrustSparkEmitter flat = new ThrustSparkEmitter();
            flat.configure(60, 15, 0.25f, 700, 350, false);
            ParticlePool pf = new ParticlePool(1000);
            flat.emit(1, 1, 5, 100, 0, 0, 0, 45, 0, -9, 2, pf);
            assertAll(
                () -> assertEquals(696f, p0.originX(0), 1e-4, "heading 0: x 5 - 9 wraps to 696"),
                () -> assertEquals(102f, p0.originY(0), 1e-4, "heading 0: y + 2"),
                () -> assertEquals(98f, p90.originX(0), 1e-4, "heading 90: x - ey"),
                () -> assertEquals(91f, p90.originY(0), 1e-4, "heading 90: y + ex"),
                () -> assertEquals(-4f, pf.originX(0), 1e-4, "no wrap without edgeWrap"),
                () -> assertEquals(-9.0, ThrustSparkEmitter.rotX(-9, 2, 128), 1e-12, "dir is taken mod RES"),
                () -> assertEquals(2.0, ThrustSparkEmitter.rotY(-9, 2, 0), 1e-12, "rotY at heading 0 is ey"));
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

        @Test
        @DisplayName("removal swaps in the last particle; a full pool counts drops")
        void removalAndCapacity() {
            ParticlePool p = new ParticlePool(3);
            DebrisBurst d = new DebrisBurst();
            d.x = 10;
            d.count = 2;
            d.minLife = 0;
            d.maxLife = 0;
            p.spawn(d, new Rng(0), 0, 0, 1);
            d.x = 20;
            d.minLife = 99;
            d.maxLife = 99;
            p.spawn(d, new Rng(0), 0, 0, 2);
            p.step(8, 0.25f, 700, 350, true);
            assertAll(
                () -> assertEquals(1, p.dropped(), "4 particles into 3 slots drops one"),
                () -> assertEquals(1, p.count(), "the two life-1 particles expired at age 2 frames"),
                () -> assertEquals(2, p.color(0), "the long-lived particle moved into slot 0"),
                () -> assertEquals(20f, p.originX(0), "with its own origin"));
        }
    }
}
