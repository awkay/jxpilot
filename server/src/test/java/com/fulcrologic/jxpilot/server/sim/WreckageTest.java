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
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wreckage: C Make_wreckage (ship.c:515-640), its callers, rotation and ship collisions. */
class WreckageTest {

    /** A scripted rfrac: the given draws, then 0.5 forever. */
    static Deque<Double> script(Simulation s, double... draws) {
        Deque<Double> q = new ArrayDeque<>();
        for (double d : draws) {
            q.add(d);
        }
        s.wreckage.rfrac = () -> q.isEmpty() ? 0.5 : q.poll();
        return q;
    }

    static List<Integer> wrecks(World w) {
        List<Integer> out = new ArrayList<>();
        Objects o = w.objects;
        for (int k = 0; k < o.count(); k++) {
            if (o.type[o.active[k]] == Obj.WRECKAGE) {
                out.add(o.active[k]);
            }
        }
        return out;
    }

    /** make() with the common arguments: at (500, 300), mass 4..10 of total 12, 10 pieces max, life 10..20. */
    static int make(Simulation s, int minDir, int maxDir, float minSpeed, float maxSpeed) {
        return s.wreckage.make(500, 300, 1f, -2f, 7, 3, 4f, 10f, 12f, Obj.GRAVITY, Records.ObjColor.WHITE, 10,
            minDir, maxDir, minSpeed, maxSpeed, 10, 20);
    }

    @Nested
    @DisplayName("make (Make_wreckage)")
    class Make {
        @Test
        @DisplayName("each piece draws mass, direction, speed, life, turn speed, rotation and shape, in C's order")
        void piece() {
            Simulation s = sim(FRAME, 40, 40, "_");
            World w = s.world;
            //               mass  dir   speed life  turn  rot   info
            script(s, 0.5, 0.25, 0.5, 0.5, 0.5, 0.25, 0.5, /* second piece */ 0.0);
            int n = make(s, 0, 64, 10f, 20f);
            Objects o = w.objects;
            int h = wrecks(w).get(0);
            int dir = 16; // 0 + (int)(0.25 * 64)
            assertAll(
                () -> assertEquals(2, n, "7, then 4 (11 < 12), then 7 is cut to 1 < 4: two pieces"),
                () -> assertEquals(7f, o.mass[h], "mass = 4 + 0.5 * 6"),
                () -> assertEquals(1f + Trig.tcos(dir) * 15f, o.vx[h], "vx = velx + tcos(dir) * speed (speed 15)"),
                () -> assertEquals(-2f + Trig.tsin(dir) * 15f, o.vy[h], "vy = vely + tsin(dir) * speed"),
                () -> assertEquals(16.0, o.life[h], "life = (int)(10 + 0.5 * 10 + 1)"),
                () -> assertEquals(16.0, o.fuseLife[h], "fuse life = life"),
                () -> assertEquals((float) (0.02 + 0.5 * 0.35), o.turnSpeed[h], "turn speed 0.02 + 0.35 * r"),
                () -> assertEquals(32, o.rotation[h], "rotation (int)(0.25 * RES)"),
                () -> assertEquals(128, o.info[h], "shape (int)(0.5 * 256)"),
                () -> assertEquals(224, o.size[h], "size (int)(384 * 7 / 12)"),
                () -> assertEquals(14, o.plRange[h], "radius 224 * 16 / 256"),
                () -> assertEquals(14, o.plRadius[h], "pl_radius = radius"),
                () -> assertEquals(Obj.GRAVITY, o.status[h], "status"),
                () -> assertEquals(Records.ObjColor.WHITE, o.color[h], "colour"),
                () -> assertEquals(7, o.owner[h], "owner"),
                () -> assertEquals(3, o.team[h], "team"),
                () -> assertEquals(500 * 64, o.cx[h], "position in clicks"),
                () -> assertEquals(300 * 64, o.cy[h], "y in clicks"));
        }

        @Test
        @DisplayName("mass: cut to what is left of the total; a piece lighter than the minimum ends the loop, exactly"
            + " the minimum does not")
        void massEdges() {
            Simulation s = sim(FRAME, 40, 40, "_");
            script(s, 1.0, 0, 0, 0, 0, 0, 0, /* 2nd: 10 > 2 left -> 2 < 4 */ 1.0);
            int cut = make(s, 0, 64, 10f, 20f);
            float firstMass = s.world.objects.mass[wrecks(s.world).get(0)];
            Simulation t = sim(FRAME, 40, 40, "_");
            // total 12: 8 then 4 (exactly the minimum, kept)
            script(t, 4.0 / 6, 0, 0, 0, 0, 0, 0, 0.0);
            int exact = make(t, 0, 64, 10f, 20f);
            Simulation u = sim(FRAME, 40, 40, "_");
            script(u, 0.0, 0, 0, 0, 0, 0, 0, 0.0, 0, 0, 0, 0, 0, 0, 0.0);
            int three = make(u, 0, 64, 10f, 20f);
            assertAll(
                () -> assertEquals(1, cut, "10, then 2 left (< 4): one piece"),
                () -> assertEquals(10f, firstMass, "the first piece weighs 10"),
                () -> assertEquals(2, exact, "8 and exactly 4"),
                () -> assertEquals(3, three, "4 + 4 + 4 reaches the total: the loop stops"));
        }

        @Test
        @DisplayName("speeds and lives are limited by the world diagonal with C's integer division")
        void diagonal() {
            // 4 x 2 blocks: 140 x 70 px, hypotenuse (int) 156
            Simulation s = TestWorlds.sim(FRAME, "_   ", "    ");
            assertEquals(156.0, s.world.params.hypotenuse, "diagonal");
            script(s, 0.0, 0.0, 0.0, 0.0);
            s.wreckage.make(50, 30, 0f, 0f, Obj.NO_ID, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 10f, 40f, 10, 20);
            int h = wrecks(s.world).get(0);
            float minSpeedVx = s.world.objects.vx[h];
            Simulation t = TestWorlds.sim(FRAME, "_   ", "    ");
            script(t, 0.0, 0.0, 1.0, 1.0);
            t.wreckage.make(50, 30, 0f, 0f, Obj.NO_ID, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 1f, 40f, 10, 20);
            int g = wrecks(t.world).get(0);
            assertAll(
                () -> assertEquals(7f, minSpeedVx, "min speed 10 * 20 > 156: 156 / 20 = 7 (integer division)"),
                () -> assertEquals(15f, t.world.objects.vx[g], "max speed 40 * 10 > 156: 156 / 10 = 15"),
                () -> assertEquals(10.0, t.world.objects.life[g], "life 21 * 15 > 156: (int)(156 / 15) = 10"));
        }

        @Test
        @DisplayName("with shotLife >= FPS a minimum life above shotLife makes both lives shotLife")
        void shotLife() {
            Simulation s = sim("framesPerSecond: 12\ngravity: 0\nshotLife: 15\n", 40, 40, "_");
            script(s, 0.0, 0.0, 0.0, 1.0);
            s.wreckage.make(500, 300, 0f, 0f, Obj.NO_ID, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 1f, 1f, 20, 40);
            Simulation t = sim("framesPerSecond: 12\ngravity: 0\nshotLife: 15\n", 40, 40, "_");
            script(t, 0.0, 0.0, 0.0, 1.0);
            t.wreckage.make(500, 300, 0f, 0f, Obj.NO_ID, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 1f, 1f, 10, 40);
            Simulation u = sim("framesPerSecond: 12\ngravity: 0\nshotLife: 15\n", 40, 40, "_");
            script(u, 0.0, 0.0, 0.0, 0.0);
            u.wreckage.make(500, 300, 0f, 0f, Obj.NO_ID, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 1f, 1f, 30, 20);
            assertAll(
                () -> assertEquals(16.0, s.world.objects.life[wrecks(s.world).get(0)], "20..40 becomes 15..15 (+1)"),
                () -> assertEquals(41.0, t.world.objects.life[wrecks(t.world).get(0)], "a minimum below shotLife keeps"
                    + " the maximum (unlike debris)"),
                () -> assertEquals(16.0, u.world.objects.life[wrecks(u.world).get(0)], "max < min: max = min = 30,"
                    + " then shotLife"));
        }

        @Test
        @DisplayName("nothing without useWreckage or outside the world; wrapping maps wrap the origin; the pool caps"
            + " the count")
        void guards() {
            Options off = TestWorlds.options(FRAME, SimFixture.open(40, 40, "_"));
            ConfigParser.parse("useWreckage: no\n", "t", OptionOrigin.MAP, off);
            Simulation none = new Simulation(new World(off, 1));
            Simulation out = sim(FRAME, 40, 40, "_");
            Simulation wrap = sim(FRAME + "edgeWrap: yes\n", 40, 40, "_");
            script(wrap, 0.0, 0.0, 0.0, 0.0);
            wrap.wreckage.make(-10, 1400 + 5, 0f, 0f, Obj.NO_ID, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 1f, 1f, 10, 20);
            int h = wrecks(wrap.world).get(0);
            Simulation full = sim(FRAME, 40, 40, "_");
            Objects o = full.world.objects;
            while (o.count() < o.capacity - 2) {
                o.alloc();
            }
            script(full, 0.0);
            assertAll(
                () -> assertEquals(0, make(none, 0, 64, 1f, 1f), "useWreckage off"),
                () -> assertEquals(0, out.wreckage.make(-1, 5, 0f, 0f, 0, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 1f, 1f, 10, 20),
                    "left of a non-wrapping world"),
                () -> assertEquals(0, out.wreckage.make(5, 1400, 0f, 0f, 0, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 1f, 1f, 10, 20),
                    "below it"),
                () -> assertEquals((1400 - 10) * 64, wrap.world.objects.cx[h], "x -10 wraps to width - 10"),
                () -> assertEquals(5 * 64, wrap.world.objects.cy[h], "y height + 5 wraps to 5"),
                () -> assertEquals(2, full.wreckage.make(500, 300, 0f, 0f, 0, 0, 1f, 1f, 100f, 0, 0, 10, 0, 64, 1f, 1f, 10,
                    20), "only 2 free slots"));
        }

        @Test
        @DisplayName("size is capped at 255 and the radius is at least 8")
        void sizeAndRadius() {
            Simulation s = sim(FRAME, 40, 40, "_");
            script(s, 1.0);
            s.wreckage.make(500, 300, 0f, 0f, 0, 0, 1f, 10f, 10f, 0, 0, 10, 0, 64, 1f, 1f, 10, 20);
            Simulation t = sim(FRAME, 40, 40, "_");
            script(t, 0.0);
            t.wreckage.make(500, 300, 0f, 0f, 0, 0, 1f, 10f, 100f, 0, 0, 1, 0, 64, 1f, 1f, 10, 20);
            int h = wrecks(s.world).get(0);
            int g = wrecks(t.world).get(0);
            assertAll(
                () -> assertEquals(255, s.world.objects.size[h], "(int)(384 * 10 / 10) = 384 -> 255"),
                () -> assertEquals(15, s.world.objects.plRange[h], "255 * 16 / 256"),
                () -> assertEquals(3, t.world.objects.size[g], "(int)(384 * 1 / 100)"),
                () -> assertEquals(8, t.world.objects.plRange[g], "radius 0 -> 8"));
        }
    }

    @Nested
    @DisplayName("make: boundaries")
    class Boundaries {
        private int one(Simulation s, float x, float y) {
            script(s, 0.0, 0.0, 0.0, 0.0);
            return s.wreckage.make(x, y, 0f, 0f, 0, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 1f, 1f, 10, 20);
        }

        @Test
        @DisplayName("the world edges: 0 is inside, the width/height is outside (or wraps to 0)")
        void edges() {
            Simulation wrap0 = sim(FRAME + "edgeWrap: yes\n", 40, 40, "_");
            int at0 = one(wrap0, 0f, 0f);
            int h0 = wrecks(wrap0.world).get(0);
            Simulation wrapNeg = sim(FRAME + "edgeWrap: yes\n", 40, 40, "_");
            one(wrapNeg, 5f, -5f);
            int hn = wrecks(wrapNeg.world).get(0);
            Simulation wrapW = sim(FRAME + "edgeWrap: yes\n", 40, 40, "_");
            int atW = one(wrapW, 1400f, 1400f);
            int hw = wrecks(wrapW.world).get(0);
            assertAll(
                () -> assertEquals(1, at0, "wrap: (0, 0) is inside"),
                () -> assertEquals(0, wrap0.world.objects.cx[h0] + wrap0.world.objects.cy[h0], "and stays at 0"),
                () -> assertEquals((1400 - 5) * 64, wrapNeg.world.objects.cy[hn], "wrap: y -5 becomes height - 5"),
                () -> assertEquals(1, atW, "wrap: (width, height) wraps"),
                () -> assertEquals(0, wrapW.world.objects.cx[hw] + wrapW.world.objects.cy[hw], "to (0, 0)"),
                () -> assertEquals(1, one(sim(FRAME, 40, 40, "_"), 0f, 0f), "no wrap: (0, 0) is inside"),
                () -> assertEquals(0, one(sim(FRAME, 40, 40, "_"), 1400f, 5f), "x = width is outside"),
                () -> assertEquals(0, one(sim(FRAME, 40, 40, "_"), 5f, 1400f), "y = height is outside"),
                () -> assertEquals(0, one(sim(FRAME, 40, 40, "_"), 5f, -0.5f), "y < 0 is outside"));
        }

        @Test
        @DisplayName("shotLife equal to FPS still caps; a minimum life equal to shotLife does not")
        void shotLifeEquality() {
            Simulation s = sim("framesPerSecond: 12\ngravity: 0\nshotLife: 12\n", 40, 40, "_");
            script(s, 0.0, 0.0, 0.0, 1.0);
            s.wreckage.make(500, 300, 0f, 0f, 0, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 1f, 1f, 20, 40);
            Simulation t = sim("framesPerSecond: 12\ngravity: 0\nshotLife: 15\n", 40, 40, "_");
            script(t, 0.0, 0.0, 0.0, 1.0);
            t.wreckage.make(500, 300, 0f, 0f, 0, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 1f, 1f, 15, 40);
            assertAll(
                () -> assertEquals(13.0, s.world.objects.life[wrecks(s.world).get(0)], "shotLife 12 = FPS: 12 (+1)"),
                () -> assertEquals(41.0, t.world.objects.life[wrecks(t.world).get(0)], "min 15 = shotLife: 15..40"));
        }

        @Test
        @DisplayName("speed limits apply only above the diagonal; at exactly the diagonal a non-integer speed stays")
        void exactDiagonal() {
            Simulation s = TestWorlds.sim(FRAME, "_   ", "    ");
            script(s, 0.0, 0.0, 0.0, 0.0);
            s.wreckage.make(50, 30, 0f, 0f, 0, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 9.75f, 9.75f, 10, 16);
            Simulation t = TestWorlds.sim(FRAME, "_   ", "    ");
            script(t, 0.0, 0.0, 1.0, 0.0);
            t.wreckage.make(50, 30, 0f, 0f, 0, 0, 4f, 10f, 4f, 0, 0, 10, 0, 64, 1f, 9.75f, 16, 16);
            assertAll(
                () -> assertEquals(9.75f, s.world.objects.vx[wrecks(s.world).get(0)], "9.75 * 16 = 156: min speed kept"),
                () -> assertEquals(9.75f, t.world.objects.vx[wrecks(t.world).get(0)], "9.75 * 16 = 156: max speed kept"));
        }

        @Test
        @DisplayName("the piece count: maxWreckage, the free pool slots, and no draws after the total mass is reached")
        void counts() {
            Simulation s = sim(FRAME, 40, 40, "_");
            Deque<Double> q = script(s, 0.0, 0, 0, 0, 0, 0, 0, 0.0, 0, 0, 0, 0, 0, 0, 0.0, 0, 0, 0, 0, 0, 0, 0.25);
            make(s, 0, 64, 1f, 1f);
            Simulation one = sim(FRAME, 40, 40, "_");
            script(one, 0.0);
            int single = one.wreckage.make(500, 300, 0f, 0f, 0, 0, 1f, 1f, 100f, 0, 0, 1, 0, 64, 1f, 1f, 10, 20);
            Simulation full = sim(FRAME, 40, 40, "_");
            Objects o = full.world.objects;
            while (o.count() < o.capacity - 2) {
                o.alloc();
            }
            Deque<Double> fq = script(full, new double[15]);
            fq.add(0.75);
            full.wreckage.make(500, 300, 0f, 0f, 0, 0, 1f, 1f, 100f, 0, 0, 10, 0, 64, 1f, 1f, 10, 20);
            assertAll(
                () -> assertEquals(List.of(0.25), List.copyOf(q), "3 x 4 = 12 reaches the total: no 22nd draw"),
                () -> assertEquals(1, single, "maxWreckage 1"),
                () -> assertEquals(2, fq.size(), "2 free slots: 14 draws, no third mass draw"));
        }
    }

    @Nested
    @DisplayName("callers and flight")
    class Callers {
        @Test
        @DisplayName("ship: a KILLED ship leaves up to 10 pieces of 2*mass in total; a ship that is only reset none")
        void ship() {
            Simulation s = sim(FRAME, 40, 40, "_");
            Player p = ready(s, "A", 0, 500, 300);
            p.vx = 3;
            p.vy = -1;
            script(s, 0.5, 0, 0, 0, 0, 0, 0);
            p.status |= Obj.KILLED;
            int before = s.world.objects.count();
            s.wreckage.ship(p);
            List<Integer> made = wrecks(s.world);
            Objects o = s.world.objects;
            int h = made.get(0);
            float mass = p.mass;
            float min = (float) Math.max(mass / 8.0, 0.33);
            Simulation alive = sim(FRAME, 40, 40, "_");
            Player q = ready(alive, "B", 0, 500, 300);
            alive.wreckage.ship(q);
            assertAll(
                () -> assertTrue(made.size() > 1 && s.world.objects.count() - before == made.size(), "pieces made"),
                () -> assertEquals((float) (min + 0.5 * (mass - min)), o.mass[h], "mass between max(mass/8, 0.33) and mass"),
                () -> assertEquals(3f + 10f, o.vx[h], "ship velocity + speed 10 at direction 0"),
                () -> assertEquals(p.id, o.owner[h], "owned by the ship"),
                () -> assertEquals(Records.ObjColor.WHITE, o.color[h], "white"),
                () -> assertEquals(Obj.GRAVITY, o.status[h], "gravity"),
                () -> assertEquals(6.0, o.life[h], "life 5 .. +1"),
                () -> assertEquals(List.of(), wrecks(alive.world), "not KILLED: no wreckage"));
        }

        @Test
        @DisplayName("ship: speed up to 10 + (int)mass/2 and life up to 5 + 1.5*mass (+1)")
        void shipMaxima() {
            Simulation s = sim(FRAME, 40, 40, "_");
            Player p = ready(s, "A", 0, 500, 300);
            p.status |= Obj.KILLED;
            script(s, 0.0, 0.0, 1.0, 1.0);
            s.wreckage.ship(p);
            int h = wrecks(s.world).get(0);
            float speed = 10 + (((int) p.mass) >> 1);
            assertAll(
                () -> assertEquals(speed, s.world.objects.vx[h], "max speed"),
                () -> assertEquals((int) (5 + p.mass * 1.5) + 1.0, s.world.objects.life[h], "max life + 1"));
        }

        @Test
        @DisplayName("cannon: from (dir - 0.2 RES) at the minimum speed (10, limited by this small world's diagonal)")
        void cannonDirection() {
            Simulation c = TestWorlds.sim(FRAME, "      ", "  r   ", "_     ");
            Cannon cannon = c.world.map.cannons().get(0);
            script(c, 0.0, 0.0, 0.0, 0.0);
            c.wreckage.cannon(cannon, 100, 50);
            int h = wrecks(c.world).get(0);
            int dir = Trig.mod((int) (cannon.dir() - Const.RES * 0.2));
            float speed = ((int) c.world.params.hypotenuse) / 68;
            assertAll(
                () -> assertEquals(3f, speed, "10 * 68 > 234: 234 / 68"),
                () -> assertEquals(Trig.tcos(dir) * speed, c.world.objects.vx[h], "vx"),
                () -> assertEquals(Trig.tsin(dir) * speed, c.world.objects.vy[h], "vy"),
                () -> assertEquals(9.0, c.world.objects.life[h], "life 8 (+1)"));
            Simulation d = TestWorlds.sim(FRAME, "      ", "  r   ", "_     ");
            script(d, 0.0, 0.999, 0.0, 0.0);
            d.wreckage.cannon(cannon, 100, 50);
            int g = wrecks(d.world).get(0);
            int span = Trig.mod((int) (cannon.dir() + Const.RES * 0.2) - (int) (cannon.dir() - Const.RES * 0.2));
            int last = Trig.mod((int) (cannon.dir() - Const.RES * 0.2) + (int) (0.999 * span));
            assertEquals(Trig.tcos(last) * 3f, d.world.objects.vx[g], "the far edge of the +-0.2 RES fan");
        }

        @Test
        @DisplayName("Ships.kill of a KILLED ship makes wreckage after the debris; a dead cannon does too")
        void hooks() {
            Simulation s = sim(FRAME, 40, 40, "_");
            Player p = ready(s, "A", 0, 500, 300);
            p.status |= Obj.KILLED;
            s.ships.kill(p);
            int shipWrecks = wrecks(s.world).size();
            Simulation c = TestWorlds.sim(FRAME, "      ", "  r   ", "_     ");
            c.crashes.cannonDies(0, Obj.NO_ID);
            Cannon cannon = c.world.map.cannons().get(0);
            List<Integer> cw = wrecks(c.world);
            Objects o = c.world.objects;
            assertAll(
                () -> assertTrue(shipWrecks > 0, "ship wreckage"),
                () -> assertFalse(cw.isEmpty(), "cannon wreckage"),
                () -> assertTrue(cw.stream().allMatch(h -> o.owner[h] == Obj.NO_ID && o.team[h] == cannon.team()),
                    "unowned, the cannon's team"),
                () -> assertTrue(cw.stream().allMatch(h -> o.mass[h] >= 3.5f && o.mass[h] <= 23f), "mass 3.5..23"),
                () -> assertTrue(cw.stream().mapToDouble(h -> o.mass[h]).sum() <= 28.0001, "28 in total"));
        }

        @Test
        @DisplayName("each frame a piece turns by (int)(turnspeed * RES), modulo RES")
        void rotate() {
            Simulation s = sim(FRAME, 40, 40, "_");
            int h = obj(s.world, Obj.WRECKAGE, Obj.NO_ID, 500, 300, 0, 0, 1, 10);
            s.world.objects.turnSpeed[h] = 0.3f;
            s.world.objects.rotation[h] = 120;
            s.moveObjects();
            int once = s.world.objects.rotation[h];
            assertEquals((120 + 38) % Const.RES, once, "0.3 * 128 = 38.4 -> 38");
        }

        @Test
        @DisplayName("a ship hit by wreckage pays fuel like debris; with wreckageCollisionMayKill an unshielded ship"
            + " dies or, with armor, loses one")
        void collisions() {
            Simulation plain = sim(FRAME, 40, 40, "__");
            Player a = ready(plain, "A", 0, 500, 300);
            double fuel = a.fuel;
            obj(plain.world, Obj.WRECKAGE, Obj.NO_ID, 500, 300, 3, 4, 2, 10);
            CollisionsTest.check(plain);
            Options o = TestWorlds.options(FRAME + "wreckageCollisionMayKill: yes\n", SimFixture.open(40, 40, "__"));
            Simulation deadly = new Simulation(new World(o, 1));
            Player b = ready(deadly, "B", 0, 500, 300);
            obj(deadly.world, Obj.WRECKAGE, Obj.NO_ID, 500, 300, 3, 4, 2, 10);
            CollisionsTest.check(deadly);
            Simulation armored = new Simulation(new World(o, 1));
            Player c = ready(armored, "C", 0, 500, 300);
            c.have |= Has.ARMOR;
            c.items[Item.ARMOR.ordinal()] = 2;
            obj(armored.world, Obj.WRECKAGE, Obj.NO_ID, 500, 300, 3, 4, 2, 10);
            CollisionsTest.check(armored);
            Simulation shielded = new Simulation(new World(o, 1));
            Player d = ready(shielded, "D", 0, 500, 300);
            d.used |= Has.SHIELD;
            obj(shielded.world, Obj.WRECKAGE, Obj.NO_ID, 500, 300, 3, 4, 2, 10);
            CollisionsTest.check(shielded);
            assertAll(
                () -> assertEquals(fuel - 20, a.fuel, 1e-9, "|(long)(2 * 2 * 5)| = 20 fuel"),
                () -> assertEquals(0, a.status & Obj.KILLED, "harmless wreckage does not kill"),
                () -> assertTrue((b.status & Obj.KILLED) != 0, "deadly wreckage kills an unshielded ship"),
                () -> assertEquals(0, c.status & Obj.KILLED, "armor saves the ship"),
                () -> assertEquals(1, c.items[Item.ARMOR.ordinal()], "and loses one armor"),
                () -> assertEquals(0, d.status & Obj.KILLED, "shields save the ship"));
        }
    }
}
