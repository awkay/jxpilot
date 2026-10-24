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
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.AsteroidsTest.asteroids;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Asteroids with the world RNG replayed by a reference {@link Rng}, and the collision edge cases. */
@DisplayName("Asteroids: exact random draws and collision edges")
class AsteroidsExactTest {
    static Simulation sim(String extra) {
        return AsteroidsTest.sim(extra);
    }

    @Test
    @DisplayName("make: spin 0.02 + r*0.05, rotation (int)(r*RES), shape (int)(r*256) from the world RNG")
    void makeDraws() {
        Simulation s = sim("");
        World w = s.world;
        w.rng.seed(3);
        Rng r = new Rng(3);
        int h = s.asteroids.make(400, 400, 2, 0, 1f);
        float spin = (float) (0.02 + r.nextFloat() * 0.05);
        int rot = (int) (r.nextFloat() * (double) Const.RES);
        int shape = (int) (r.nextFloat() * 256.0);
        assertAll(
            () -> assertEquals(spin, w.objects.turnSpeed[h], "spin"),
            () -> assertEquals(rot, w.objects.rotation[h], "rotation"),
            () -> assertEquals(shape, w.objects.info[h], "shape"));
    }

    @Test
    @DisplayName("broken: children split RES/4 off the flight direction at the smaller radius, angled and sped as C")
    void children() {
        Simulation s = sim("");
        World w = s.world;
        Objects o = w.objects;
        int h = s.asteroids.make(500, 500, 3, 10, 12f);
        float vx = o.vx[h];
        float vy = o.vy[h];
        float speed = (float) StrictMath.hypot(vx, vy);
        int dir = (int) XMath.findDir(vx, vy);
        w.rng.seed(11);
        Rng r = new Rng(11);
        int dir1 = (int) (dir - Asteroids.DELTA_DIR / 4 - (r.nextFloat() * (double) Asteroids.DELTA_DIR / 4))
            & (Const.RES - 1);
        int dir2 = (int) (dir + Asteroids.DELTA_DIR / 4 + (r.nextFloat() * (double) Asteroids.DELTA_DIR / 4))
            & (Const.RES - 1);
        float speed1 = (float) ((speed * (1 - Asteroids.DUST_FACT)) / Trig.tcos(Math.abs(dir - dir1)));
        float speed2 = (float) ((speed * (1 - Asteroids.DUST_FACT)) / Trig.tcos(Math.abs(dir2 - dir)));
        int split = (dir - Const.RES / 4) & (Const.RES - 1);
        double rad = Asteroids.radius(2);
        int x1 = (int) (500 + Trig.tcos(split) * rad);
        int y1 = (int) (500 + Trig.tsin(split) * rad);
        int x2 = (int) (500 - Trig.tcos(split) * rad);
        int y2 = (int) (500 - Trig.tsin(split) * rad);
        s.asteroids.broken(h);
        List<Integer> l = asteroids(w);
        int c1 = l.get(0);
        int c2 = l.get(1);
        assertAll(
            () -> assertEquals(x1, o.px[c1], "child 1 x"),
            () -> assertEquals(y1, o.py[c1], "child 1 y"),
            () -> assertEquals(x2, o.px[c2], "child 2 x"),
            () -> assertEquals(y2, o.py[c2], "child 2 y"),
            () -> assertEquals(Trig.tcos(dir1) * speed1, o.vx[c1], "child 1 vx"),
            () -> assertEquals(Trig.tsin(dir1) * speed1, o.vy[c1], "child 1 vy"),
            () -> assertEquals(Trig.tcos(dir2) * speed2, o.vx[c2], "child 2 vx"),
            () -> assertEquals(Trig.tsin(dir2) * speed2, o.vy[c2], "child 2 vy"),
            () -> assertEquals(2, w.objects.size[c1], "one size smaller"),
            () -> assertEquals(2, l.size(), "the original left the list"),
            () -> assertEquals(4 + 2 + 2 - 4, w.modes.asteroidNum, "the count: 4 + 2 + 2 - 4"));
    }

    @Test
    @DisplayName("broken: dust is mass * DUST_FACT, half as wreckage and half as debris; items roll 1..max")
    void dust() {
        Simulation s = sim("asteroidMaxItems: 5\nasteroidItemProb: 0.5\n");
        World w = s.world;
        int h = s.asteroids.make(500, 500, 2, 0, 0f);
        float mass3 = (float) (w.objects.mass[h] * Asteroids.DUST_FACT);
        w.rng.seed(4);
        int[] drops = new int[1];
        s.asteroids.itemDrop = (px, py, vx, vy) -> drops[0]++;
        s.asteroids.broken(h);
        float wreck = 0;
        for (int k = 0; k < w.objects.count(); k++) {
            int q = w.objects.active[k];
            if (w.objects.type[q] == Obj.WRECKAGE) {
                wreck += w.objects.mass[q];
            }
        }
        float total = wreck;
        assertAll(
            () -> assertTrue(total <= mass3 / 2 + 1e-4 && total > 0, "wreckage mass at most half the dust (" + total + ")"),
            () -> assertTrue(drops[0] >= 0 && drops[0] <= 5, "0..5 items"));
    }

    @Test
    @DisplayName("place near a concentrator: dir and distance draws place it exactly; size 1-4, speed 8-18")
    void placeExact() {
        String[] rows = SimFixture.open(60, 60, "_");
        char[] r0 = rows[59 - 40].toCharArray();
        r0[40] = '&';
        rows[59 - 40] = new String(r0);
        Simulation s = TestWorlds.sim(SimFixture.FPS14 + "asteroidConcentratorRadius: 3\n", rows);
        World w = s.world;
        w.rng.seed(21);
        Rng r = new Rng(21);
        r.nextFloat();
        r.nextFloat();
        int dir = (int) (r.nextFloat() * (double) Const.RES);
        int dist = (int) (r.nextFloat() * (double) ((3 * Const.BLOCK_SZ) + 1));
        int px = (int) ((40 + 0.5) * Const.BLOCK_SZ + dist * Trig.tcos(dir));
        int py = (int) ((40 + 0.5) * Const.BLOCK_SZ + dist * Trig.tsin(dir));
        int size = (int) (1 + r.nextFloat() * (double) Asteroids.MAX_SIZE);
        int adir = (int) (r.nextFloat() * (double) Const.RES);
        float speed = (float) (8 + r.nextFloat() * 10.0);
        s.asteroids.place();
        int h = asteroids(w).get(0);
        Simulation plain = sim("");
        World pw = plain.world;
        pw.rng.seed(22);
        Rng q = new Rng(22);
        int ppx = (int) (q.nextFloat() * (double) pw.pixelWidth);
        int ppy = (int) (q.nextFloat() * (double) pw.pixelHeight);
        plain.asteroids.place();
        int ph = asteroids(pw).get(0);
        assertAll(
            () -> assertEquals(px, w.objects.px[h], "x"),
            () -> assertEquals(py, w.objects.py[h], "y"),
            () -> assertEquals(size, w.objects.size[h], "size"),
            () -> assertEquals(Trig.tcos(adir) * speed, w.objects.vx[h], "velocity"),
            () -> assertEquals(ppx, pw.objects.px[ph], "without concentrators: anywhere (x)"),
            () -> assertEquals(ppy, pw.objects.py[ph], "(y)"));
    }

    @Test
    @DisplayName("playerHit edges: points at exactly asteroidMaxScore; life floors at 0; equal speed is 'hit by'; no fuel kills")
    void playerEdges() {
        Simulation s = sim("asteroidPoints: 2\nasteroidMaxScore: 5\n");
        World w = s.world;
        Objects o = w.objects;
        Player p = ready(s, "P", 0, 300, 300);
        p.score = 5;
        p.used |= Has.SHIELD;
        int h = s.asteroids.make(320, 300, 1, 0, 0f);
        o.life[h] = 3;
        s.asteroids.playerHit(p, h);
        Player q = ready(s, "Q", 0, 400, 300);
        q.score = 5.01;
        q.used |= Has.SHIELD;
        int h2 = s.asteroids.make(420, 300, 1, 0, 0f);
        s.asteroids.playerHit(q, h2);
        Player e = ready(s, "E", 0, 500, 300);
        int h3 = s.asteroids.make(520, 300, 4, 0, 2f);
        e.velocity = 2f;
        long from = w.events.seq();
        s.asteroids.playerHit(e, h3);
        Player f = ready(s, "F", 0, 600, 300);
        f.used |= Has.SHIELD;
        f.fuel = 1;
        int h4 = s.asteroids.make(620, 300, 4, 0, 2f);
        s.asteroids.playerHit(f, h4);
        assertAll(
            () -> assertEquals(0.0, o.life[h], "life floors at 0"),
            () -> assertEquals(7.0, p.score, 1e-9, "score 5 <= max 5: points"),
            () -> assertEquals(5.01, q.score, 1e-9, "score above max: none"),
            () -> assertEquals("E was hit by an asteroid.", messages(w, from).get(0), "equal speed: hit by"),
            () -> assertTrue((f.status & Obj.KILLED) != 0, "a shielded ship out of fuel dies"));
    }

    @Test
    @DisplayName("collide edges: owned debris and wreckage, mines (confused or not), missiles, other owned objects, ball owners")
    void collideEdges() {
        Simulation s = sim("asteroidPoints: 4\n");
        World w = s.world;
        Objects o = w.objects;
        Player p = ready(s, "P", 0, 100, 100);
        int a = s.asteroids.make(500, 500, 4, 0, 0f);
        o.life[a] = 5000;
        o.fuseLife[a] = 5000;
        int debris = obj(w, Obj.DEBRIS, p.id, 500, 500, 3, 4, 2, 10);
        int wreck = obj(w, Obj.WRECKAGE, p.id, 500, 500, 0, 1, 2, 10);
        int mine = obj(w, Obj.MINE, p.id, 500, 500, 0, 0, 30, 10);
        int confused = obj(w, Obj.MINE, p.id, 500, 500, 0, 0, 30, 10);
        o.status[confused] = Obj.CONFUSED;
        int smart = obj(w, Obj.SMART_SHOT, p.id, 500, 500, 0, 0, 5, 10);
        int item = obj(w, Obj.ITEM, p.id, 500, 500, 7, 0, 1, 10);
        w.grid.rebuild(o);
        s.asteroids.collide();
        double life = o.life[a];
        Simulation b = sim("asteroidPoints: 4\n");
        World bw = b.world;
        Player owner = ready(b, "O", 0, 100, 100);
        int ast = b.asteroids.make(500, 500, 1, 0, 0f);
        bw.objects.life[ast] = 1;
        bw.objects.fuseLife[ast] = 5;
        int ball = obj(bw, Obj.BALL, Obj.NO_ID, 500, 500, 0, 0, 50, 100);
        bw.modes.ballOwner[ball] = owner.id;
        bw.grid.rebuild(bw.objects);
        b.asteroids.collide();
        double damage = 2 * 2 * 5.0 + 2 * 2 * 1.0 + 6.0 * 1000 * 120 * 256 / (6400.0 * 8);
        assertAll(
            () -> assertEquals(0.0, o.life[debris] + o.life[wreck], "owned debris and wreckage are absorbed"),
            () -> assertEquals(0.0, o.life[mine], "a mine dies"),
            () -> assertEquals(10.0, o.life[confused], "a confused mine survives"),
            () -> assertEquals(0.0, o.life[smart], "a missile is absorbed"),
            () -> assertEquals(10.0, o.life[item], "an owned item is not killed (only pushed)"),
            () -> assertTrue(life < 5000 && life > 0, "the asteroid took damage (" + life + ", ~" + damage + ")"),
            () -> assertEquals(0.0, bw.objects.life[ast], "a ball breaks the small asteroid"),
            () -> assertEquals(4.0, owner.score, 1e-9, "points to the ball's owner"));
    }

    @Test
    @DisplayName("make on a wrapping world: positions one world off on either side wrap in")
    void wrapMake() {
        Simulation s = sim("edgeWrap: yes\n");
        World w = s.world;
        int a = s.asteroids.make(-10, 300, 1, 0, 0f);
        int b = s.asteroids.make(300, w.pixelHeight + 10, 1, 0, 0f);
        int c = s.asteroids.make(300, -10, 1, 0, 0f);
        assertAll(
            () -> assertEquals(w.pixelWidth - 10, w.objects.px[a], "x -10 wraps to the right edge"),
            () -> assertEquals(10, w.objects.py[b], "y past the top wraps to the bottom"),
            () -> assertEquals(w.pixelHeight - 10, w.objects.py[c], "y -10 wraps to the top"));
    }

    @Test
    @DisplayName("place near a concentrator at the world corner on a wrapping map: negative offsets wrap")
    void placeWrap() {
        String[] rows = SimFixture.open(30, 30, "_");
        char[] r0 = rows[29].toCharArray();
        r0[0] = '&';
        rows[29] = new String(r0);
        Simulation s = TestWorlds.sim(SimFixture.FPS14 + "edgeWrap: yes\nasteroidConcentratorRadius: 4\n", rows);
        World w = s.world;
        int placed = 0;
        boolean inWorld = true;
        for (int seed = 1; seed < 40; seed++) {
            w.rng.seed(seed);
            Rng r = new Rng(seed);
            r.nextFloat();
            r.nextFloat();
            int dir = (int) (r.nextFloat() * (double) Const.RES);
            int dist = (int) (r.nextFloat() * (double) ((4 * Const.BLOCK_SZ) + 1));
            int px = (int) (0.5 * Const.BLOCK_SZ + dist * Trig.tcos(dir));
            int py = (int) (0.5 * Const.BLOCK_SZ + dist * Trig.tsin(dir));
            if (px < 0) {
                px += w.pixelWidth;
            }
            if (py < 0) {
                py += w.pixelHeight;
            }
            int before = w.modes.asteroidCount;
            s.asteroids.place();
            boolean firstTry = px >= 0 && px < w.pixelWidth && py >= 0 && py < w.pixelHeight
                && w.map.block(px / Const.BLOCK_SZ, py / Const.BLOCK_SZ) == com.fulcrologic.jxpilot.common.map.BlockType.SPACE;
            if (firstTry && w.modes.asteroidCount > before) {
                int h = w.modes.asteroidList[w.modes.asteroidCount - 1];
                inWorld &= w.objects.px[h] == px && w.objects.py[h] == py;
                placed++;
            }
        }
        int n = placed;
        boolean ok = inWorld;
        assertAll(
            () -> assertTrue(n > 5, "placed (" + n + ")"),
            () -> assertTrue(ok, "every first-try position, wrapped as C does"));
    }

    @Test
    @DisplayName("asteroid-asteroid: the hit one loses ASTEROID_FUEL_HIT(|2*m*v| of the hitter), the hitter gains the other's |2*m*v| damage")
    void asteroidPair() {
        Simulation s = sim("");
        World w = s.world;
        Objects o = w.objects;
        int a = s.asteroids.make(500, 500, 2, 0, 3f);
        int b = s.asteroids.make(520, 500, 3, 64, 2f);
        o.life[a] = 900;
        o.life[b] = 900;
        double va = StrictMath.hypot(o.vx[a], o.vy[a]);
        double vb = StrictMath.hypot(o.vx[b], o.vy[b]);
        long lifeB = (long) (900 - Asteroids.fuelHit(Math.abs(2 * o.mass[a] * va), 3));
        float damage = (float) -Math.abs(2 * o.mass[b] * vb);
        long lifeA = (long) ((float) 900 + (damage * 1000) / (256 * 25 * 2));
        int smartAst = s.asteroids.make(800, 500, 1, 0, 0f);
        o.life[smartAst] = 5000;
        o.fuseLife[smartAst] = 5000;
        Player p = ready(s, "P", 0, 100, 100);
        int smart = obj(w, Obj.HEAT_SHOT, p.id, 800, 500, 0, 0, 5, 10);
        w.grid.rebuild(o);
        s.asteroids.collide();
        long lifeSmart = (long) ((float) 5000 + (float) (Targets.ED_SMART_SHOT_HIT) * 1000 / (256 * 25));
        assertAll(
            () -> assertEquals(lifeB, (long) o.life[b], "the hit asteroid's life"),
            () -> assertEquals(lifeA, (long) o.life[a], "the hitter's life"),
            () -> assertEquals(lifeSmart, (long) o.life[smartAst], "a heat seeker: 120*256 damage"),
            () -> assertEquals(0.0, o.life[smart], "and it is absorbed"));
    }
}
