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

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Asteroid edges: world bounds and wrap, a full pool, the REC_LU diagonal, culling, placement, items, collisions. */
@DisplayName("Asteroids edges (asteroid.c, collision.c AsteroidCollision)")
class AsteroidsEdgesTest {
    /** 30 x 30 open map (1050 px square), no gravity, bases on the bottom row at x 1, 3, 5. */
    static Simulation sim(String extra) {
        return SimFixture.sim(SimFixture.FPS14 + extra, 30, 30, "___");
    }

    @Nested
    @DisplayName("make: bounds, wrap, pool, sizes, the REC_LU diagonal")
    class Make {
        @Test
        @DisplayName("without wrap: pixel 0 is inside, the world size is outside, as are negatives; sizes 1..4 only")
        void bounds() {
            Simulation s = sim("");
            Asteroids a = s.asteroids;
            assertAll(
                () -> assertTrue(a.make(0, 500, 1, 0, 0f) >= 0, "x 0"),
                () -> assertTrue(a.make(500, 0, 1, 0, 0f) >= 0, "y 0"),
                () -> assertTrue(a.make(1049.5, 500, 1, 0, 0f) >= 0, "x just inside"),
                () -> assertEquals(-1, a.make(1050, 500, 1, 0, 0f), "x at the width"),
                () -> assertEquals(-1, a.make(500, 1050, 1, 0, 0f), "y at the height"),
                () -> assertEquals(-1, a.make(-0.5, 500, 1, 0, 0f), "x negative"),
                () -> assertEquals(-1, a.make(500, -0.5, 1, 0, 0f), "y negative"),
                () -> assertTrue(a.make(600, 600, Asteroids.MAX_SIZE, 0, 0f) >= 0, "size 4"),
                () -> assertEquals(-1, a.make(600, 600, 0, 0, 0f), "size 0"),
                () -> assertEquals(-1, a.make(600, 600, Asteroids.MAX_SIZE + 1, 0, 0f), "size 5"));
        }

        @Test
        @DisplayName("with wrap: 0 stays, the world size wraps to 0, -1 wraps to the far edge")
        void wrap() {
            Simulation s = sim("edgeWrap: yes\n");
            Objects o = s.world.objects;
            Asteroids a = s.asteroids;
            int x0 = a.make(0, 500, 1, 0, 0f);
            int xw = a.make(1050, 500, 1, 0, 0f);
            int xm = a.make(-1, 500, 1, 0, 0f);
            int y0 = a.make(500, 0, 1, 0, 0f);
            int yh = a.make(500, 1050, 1, 0, 0f);
            int ym = a.make(500, -1, 1, 0, 0f);
            assertAll(
                () -> assertEquals(0, o.px[x0], "x 0 stays"),
                () -> assertEquals(0, o.px[xw], "x 1050 wraps to 0"),
                () -> assertEquals(1049, o.px[xm], "x -1 wraps to 1049"),
                () -> assertEquals(0, o.py[y0], "y 0 stays"),
                () -> assertEquals(0, o.py[yh], "y 1050 wraps to 0"),
                () -> assertEquals(1049, o.py[ym], "y -1 wraps to 1049"));
        }

        @Test
        @DisplayName("a full object pool refuses")
        void fullPool() {
            Simulation s = sim("");
            Objects o = s.world.objects;
            while (o.count() < o.capacity) {
                o.alloc();
            }
            assertEquals(-1, s.asteroids.make(500, 500, 1, 0, 0f), "no room");
        }

        @Test
        @DisplayName("REC_LU ('s'): a point on the diagonal or above it is free, below it is wall")
        void diagonal() {
            String[] rows = SimFixture.open(30, 30, "___");
            char[] r = rows[29 - 5].toCharArray();
            r[5] = 's';
            rows[29 - 5] = new String(r);
            Simulation s = TestWorlds.sim(SimFixture.FPS14, rows);
            Asteroids a = s.asteroids;
            assertAll(
                () -> assertTrue(a.make(5 * 35 + 10, 5 * 35 + 10, 1, 0, 0f) >= 0, "on the diagonal (dx == dy)"),
                () -> assertTrue(a.make(5 * 35 + 20, 5 * 35 + 10, 1, 0, 0f) >= 0, "dx > dy"),
                () -> assertEquals(-1, a.make(5 * 35 + 10, 5 * 35 + 20, 1, 0, 0f), "dx < dy: the solid half"));
        }
    }

    @Nested
    @DisplayName("update: culling, spin, placement cap")
    class Update {
        @Test
        @DisplayName("over asteroidMax: live asteroids die in list order until the size-1 count is at most the max; dead ones are skipped")
        void cull() {
            Simulation s = sim("");
            World w = s.world;
            Objects o = w.objects;
            w.modeParams.asteroidChance = 0;
            int dead = s.asteroids.make(200, 500, 1, 0, 0f);
            int a = s.asteroids.make(400, 500, 1, 0, 0f);
            int b = s.asteroids.make(600, 500, 1, 0, 0f);
            int c = s.asteroids.make(800, 500, 1, 0, 0f);
            o.life[dead] = 0;
            w.modeParams.asteroidMax = 2;
            s.asteroids.update();
            Simulation keep = sim("");
            keep.world.modeParams.asteroidChance = 0;
            int k1 = keep.asteroids.make(200, 500, 1, 0, 0f);
            keep.asteroids.make(400, 500, 1, 0, 0f);
            keep.world.modeParams.asteroidMax = 2;
            keep.asteroids.update();
            assertAll(
                () -> assertEquals(0.0, o.life[a], "4 > 2: the first live one dies (3)"),
                () -> assertEquals(0.0, o.life[b], "and the next (2)"),
                () -> assertTrue(o.life[c] > 0, "2 <= 2: the rest live"),
                () -> assertTrue(keep.world.objects.life[k1] > 0, "exactly at the max: nobody dies"));
        }

        @Test
        @DisplayName("no new asteroid unless the count plus 8 stays below asteroidMax")
        void placementCap() {
            Simulation at = sim("");
            at.world.modeParams.asteroidChance = 1;
            at.world.modeParams.asteroidMax = 8;
            at.asteroids.update();
            Simulation below = sim("");
            below.world.modeParams.asteroidChance = 1;
            below.world.modeParams.asteroidMax = 9;
            for (int i = 0; i < 20 && below.world.modes.asteroidCount == 0; i++) {
                below.asteroids.update();
            }
            assertAll(
                () -> assertEquals(0, at.world.modes.asteroidCount, "0 + 8 < 8 fails"),
                () -> assertEquals(1, below.world.modes.asteroidCount, "0 + 8 < 9: placed (chance 1)"));
        }
    }

    @Nested
    @DisplayName("broken: items and wreckage")
    class Broken {
        static final String ITEMS = "maxItemDensity: 1\nitemMineProb: 1\nasteroidItemProb: 1\n";

        List<Integer> items(World w) {
            List<Integer> l = new ArrayList<>();
            for (int k = 0; k < w.objects.count(); k++) {
                int h = w.objects.active[k];
                if (w.objects.type[h] == Obj.ITEM) {
                    l.add(h);
                }
            }
            return l;
        }

        @Test
        @DisplayName("asteroidMaxItems 0 drops nothing; 1 drops exactly one; up to 3 sometimes several")
        void counts() {
            Simulation none = sim(ITEMS + "asteroidMaxItems: 0\n");
            none.asteroids.broken(none.asteroids.make(500, 500, 1, 0, 0f));
            Simulation one = sim(ITEMS + "asteroidMaxItems: 1\n");
            for (int i = 0; i < 4; i++) {
                one.asteroids.broken(one.asteroids.make(200 + 150 * i, 500, 1, 0, 0f));
            }
            Simulation three = sim(ITEMS + "asteroidMaxItems: 3\n");
            for (int i = 0; i < 6; i++) {
                three.asteroids.broken(three.asteroids.make(150 + 120 * i, 500, 1, 0, 0f));
            }
            int many = items(three.world).size();
            assertAll(
                () -> assertEquals(List.of(), items(none.world), "max 0: no roll at all"),
                () -> assertEquals(4, items(one.world).size(), "max 1: one per asteroid"),
                () -> assertTrue(many > 6 && many <= 18, "max 3: 1-3 per asteroid, more than one somewhere (" + many + ")"));
        }

        @Test
        @DisplayName("items fly at most 10 from the asteroid's velocity; randomItemProb makes them RANDOM_ITEM")
        void speedAndRandom() {
            Simulation s = sim(ITEMS + "asteroidMaxItems: 3\n");
            s.world.items.randomItemProb = 1;
            for (int i = 0; i < 6; i++) {
                s.asteroids.broken(s.asteroids.make(150 + 120 * i, 500, 1, 0, 0f));
            }
            Simulation plain = sim(ITEMS + "asteroidMaxItems: 3\n");
            plain.world.items.randomItemProb = 0;
            plain.asteroids.broken(plain.asteroids.make(500, 500, 1, 0, 0f));
            Objects o = s.world.objects;
            boolean bounded = true;
            boolean moving = false;
            boolean allRandom = true;
            for (int h : items(s.world)) {
                bounded &= Math.abs(o.vx[h]) <= 10 && Math.abs(o.vy[h]) <= 10;
                moving |= Math.abs(o.vx[h]) + Math.abs(o.vy[h]) > 1;
                allRandom &= (o.status[h] & Obj.RANDOM_ITEM) != 0;
            }
            boolean noneRandom = true;
            for (int h : items(plain.world)) {
                noneRandom &= (plain.world.objects.status[h] & Obj.RANDOM_ITEM) == 0;
            }
            boolean b = bounded;
            boolean m = moving;
            boolean r = allRandom;
            boolean n = noneRandom;
            assertAll(
                () -> assertTrue(b, "within 10 of the asteroid (at rest)"),
                () -> assertTrue(m, "some move"),
                () -> assertTrue(r, "randomItemProb 1: all RANDOM_ITEM"),
                () -> assertTrue(n, "randomItemProb 0: none"));
        }

        @Test
        @DisplayName("the size-1 dust: wreckage pieces weigh mass/2/20 .. mass/2/3")
        void wreckageMass() {
            Simulation s = sim("");
            Objects o = s.world.objects;
            int h = s.asteroids.make(500, 500, 1, 0, 0f);
            float mass = o.mass[h] / 2;
            s.asteroids.broken(h);
            boolean inRange = true;
            int pieces = 0;
            for (int k = 0; k < o.count(); k++) {
                int q = o.active[k];
                if (o.type[q] == Obj.WRECKAGE) {
                    pieces++;
                    inRange &= o.mass[q] >= mass / 20 - 1e-4 && o.mass[q] <= mass / 3 + 1e-4;
                }
            }
            int p = pieces;
            boolean ok = inRange;
            assertAll(
                () -> assertTrue(p > 0, "(wreckage made)"),
                () -> assertTrue(ok, "every piece within the C mass range"));
        }
    }

    @Nested
    @DisplayName("collisions")
    class Collide {
        /** An asteroid at (500, 500) past its fuse, at rest, and the world's grid rebuilt after {@code more}. */
        int rock(Simulation s) {
            int a = s.asteroids.make(500, 500, 2, 0, 0f);
            s.world.objects.life[a] = 900;
            return a;
        }

        @Test
        @DisplayName("sparks, debris, wreckage and other owned objects die and push the asteroid; debris hurts it")
        void pushes() {
            Simulation s = sim("");
            World w = s.world;
            Objects o = w.objects;
            Player p = ready(s, "P", 0, 100, 100);
            int a = rock(s);
            int spark = obj(w, Obj.SPARK, p.id, 505, 500, 30, 0, 5, 20);
            w.grid.rebuild(o);
            s.asteroids.collide();
            float sparkPush = o.vx[a];
            Simulation s2 = sim("");
            Player p2 = ready(s2, "P", 0, 100, 100);
            int a2 = rock(s2);
            int debris = obj(s2.world, Obj.DEBRIS, p2.id, 505, 500, 30, 0, 5, 20);
            s2.world.grid.rebuild(s2.world.objects);
            s2.asteroids.collide();
            Simulation s3 = sim("");
            Player p3 = ready(s3, "P", 0, 100, 100);
            int a3 = rock(s3);
            int item = obj(s3.world, Obj.ITEM, p3.id, 505, 500, 30, 0, 5, 20);
            s3.world.grid.rebuild(s3.world.objects);
            s3.asteroids.collide();
            Simulation s4 = sim("");
            int a4 = rock(s4);
            int unowned = obj(s4.world, Obj.DEBRIS, Obj.NO_ID, 505, 500, 30, 0, 5, 20);
            s4.world.grid.rebuild(s4.world.objects);
            s4.asteroids.collide();
            assertAll(
                () -> assertEquals(0.0, o.life[spark], "the spark is gone"),
                () -> assertTrue(sparkPush > 0, "and pushed the asteroid"),
                () -> assertEquals(900.0, o.life[a], "sparks do no damage"),
                () -> assertEquals(0.0, s2.world.objects.life[debris], "debris gone"),
                () -> assertTrue(s2.world.objects.vx[a2] > 0, "debris pushes"),
                () -> assertTrue(s2.world.objects.life[a2] < 900, "debris hurts (-|2 m v|)"),
                () -> assertTrue(s3.world.objects.vx[a3] > 0, "an owned item pushes (default case)"),
                () -> assertEquals(900.0, s3.world.objects.life[a3], "without damage"),
                () -> assertEquals(20.0, s4.world.objects.life[unowned], "unowned debris is ignored"),
                () -> assertEquals(0f, s4.world.objects.vx[a4], "(no push)"));
        }

        @Test
        @DisplayName("damage is added to the life in C's float: a hit smaller than half a float step at 900 changes nothing")
        void floatDamage() {
            Simulation s = sim("");
            World w = s.world;
            Objects o = w.objects;
            Player p = ready(s, "P", 0, 100, 100);
            int a = rock(s);
            // damage -|2 m v| = -2e-5, ASTEROID_FUEL_HIT = -2e-5 * 1000 / (256 * 25 * 2): 900 - 1.6e-6 is 900f
            obj(w, Obj.DEBRIS, p.id, 505, 500, 1e-5f, 0, 1, 20);
            w.grid.rebuild(o);
            s.asteroids.collide();
            assertEquals(900.0, o.life[a], "(long) (900f - 1.6e-6f) = 900; in double it would be 899");
        }

        @Test
        @DisplayName("a ball is repelled and (treasureCollisionDestroys) destroyed; its owner scores the break at max score exactly")
        void ballAndScore() {
            Simulation s = sim("asteroidPoints: 2\nasteroidMaxScore: 50\n");
            World w = s.world;
            Objects o = w.objects;
            Player p = ready(s, "P", 0, 100, 100);
            p.score = 50;
            int a = rock(s);
            o.life[a] = 1;
            int ball = obj(w, Obj.BALL, Obj.NO_ID, 505, 500, 3, 0, 50, 1000);
            w.modes.ballOwner[ball] = p.id;
            long from = w.events.seq();
            w.grid.rebuild(o);
            s.asteroids.collide();
            int bx = -1;
            int by = -1;
            for (long q = from; q < w.events.seq(); q++) {
                int i = Events.index(q);
                if (w.events.kind[i] == Events.SCORE_OBJECT) {
                    bx = w.events.b[i];
                    by = w.events.c[i];
                }
            }
            int sx = bx;
            int sy = by;
            assertAll(
                () -> assertEquals(0.0, o.life[ball], "the ball is destroyed"),
                () -> assertNotEquals(3f, o.vx[ball], "and repelled"),
                () -> assertEquals(0.0, o.life[a], "the asteroid broke"),
                () -> assertEquals(52.0, p.score, 1e-9, "the ball's owner scores at exactly asteroidMaxScore"),
                () -> assertEquals(500 / 35, sx, "score object block x"),
                () -> assertEquals(500 / 35, sy, "score object block y"));
        }

        @Test
        @DisplayName("a dead asteroid, a dead object and a fresh asteroid (life above fuse) do not collide; life == fuse does")
        void skips() {
            Simulation s = sim("");
            World w = s.world;
            Objects o = w.objects;
            Player p = ready(s, "P", 0, 100, 100);
            int dead = rock(s);
            o.life[dead] = 0;
            int shot = obj(w, Obj.SHOT, p.id, 505, 500, 10, 0, 1, 20);
            w.grid.rebuild(o);
            s.asteroids.collide();
            Simulation s2 = sim("");
            Player p2 = ready(s2, "P", 0, 100, 100);
            int a2 = rock(s2);
            int deadShot = obj(s2.world, Obj.SHOT, p2.id, 505, 500, 10, 0, 1, 0);
            s2.world.grid.rebuild(s2.world.objects);
            s2.asteroids.collide();
            Simulation s3 = sim("");
            int x = s3.asteroids.make(500, 500, 2, 0, 0f);
            int y = s3.asteroids.make(520, 500, 2, 0, 0f);
            Objects o3 = s3.world.objects;
            o3.life[x] = 900;
            o3.fuseLife[x] = 900;
            o3.vx[x] = 2;
            o3.life[y] = 900;
            o3.fuseLife[y] = 899; // y itself is fresh: as the outer asteroid it skips asteroids
            s3.world.grid.rebuild(o3);
            s3.asteroids.collide();
            assertAll(
                () -> assertEquals(20.0, o.life[shot], "a dead asteroid hits nothing"),
                () -> assertEquals(0f, s2.world.objects.vx[a2], "a dead shot is ignored"),
                () -> assertTrue(o3.life[y] < 900, "life == fuse: the asteroids collide"));
        }

        @Test
        @DisplayName("an unshielded ship with armor: asteroidCollisionMayKill takes an armor instead of the ship")
        void armor() {
            Simulation s = sim("asteroidCollisionMayKill: yes\n");
            World w = s.world;
            Player p = ready(s, "P", 0, 500, 500);
            p.used &= ~Has.SHIELD;
            p.have |= Has.ARMOR;
            p.items[Item.ARMOR.ordinal()] = 2;
            int a = s.asteroids.make(520, 500, 1, 0, 0f);
            s.asteroids.playerHit(p, a);
            assertAll(
                () -> assertEquals(1, p.items[Item.ARMOR.ordinal()], "one armor gone"),
                () -> assertFalse((p.status & Obj.KILLED) != 0, "alive"));
        }
    }
}
