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
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Asteroids (asteroid.c, collision.c AsteroidCollision / Player_collides_with_asteroid)")
class AsteroidsTest {
    static final double EPS = 1e-9;

    /** 30 x 30 open map (3 bases at the bottom), no gravity, with extra options. */
    static Simulation sim(String extra) {
        return SimFixture.sim(SimFixture.FPS14 + extra, 30, 30, "______");
    }

    static List<Integer> asteroids(World w) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < w.modes.asteroidCount; i++) {
            l.add(w.modes.asteroidList[i]);
        }
        return l;
    }

    @Nested
    @DisplayName("make (Make_asteroid)")
    class Make {
        @Test
        @DisplayName("fields: velocity, mass ShipMass*3*2.25^(size-1), life 1000/fuse 999, radius 0.8*SHIP_SZ*size, list and count")
        void fields() {
            Simulation s = sim("shipMass: 20\n");
            World w = s.world;
            Objects o = w.objects;
            int h = s.asteroids.make(300, 400, 3, 32, 10f);
            ModeState m = w.modes;
            assertAll(
                () -> assertEquals(Obj.ASTEROID, o.type[h], "an asteroid"),
                () -> assertEquals(Trig.tcos(32) * 10f, o.vx[h], "vx = tcos(dir) * speed"),
                () -> assertEquals(Trig.tsin(32) * 10f, o.vy[h], "vy = tsin(dir) * speed"),
                () -> assertEquals((float) (60 * StrictMath.pow(2.25, 2)), o.mass[h], "mass"),
                () -> assertEquals(1000.0, o.life[h], EPS, "life"),
                () -> assertEquals(999.0, o.fuseLife[h], EPS, "fuse"),
                () -> assertEquals((int) (0.8 * Const.SHIP_SZ * 3), o.plRadius[h], "radius"),
                () -> assertEquals(Obj.GRAVITY, o.status[h], "gravity"),
                () -> assertEquals(3, m.wireSize[h], "size"),
                () -> assertTrue(m.wireTurnspeed[h] >= 0.02f && m.wireTurnspeed[h] < 0.07f, "spin 0.02-0.07"),
                () -> assertTrue(m.wireRotation[h] >= 0 && m.wireRotation[h] < Const.RES, "rotation"),
                () -> assertEquals(List.of(h), asteroids(w), "in the list"),
                () -> assertEquals(4, m.asteroidNum, "count += 2^(size-1)"));
        }

        @Test
        @DisplayName("refused: size 0 or 5, off the world, C's block-type-as-bits test and diagonal halves; walls are not refused (C quirk)")
        void refusals() {
            String[] rows = SimFixture.open(30, 30, "___");
            char[] r = rows[29 - 10].toCharArray();
            r[5] = '#';
            r[6] = 'x';
            r[7] = 's';
            r[8] = 'w';
            rows[29 - 10] = new String(r);
            Simulation s = TestWorlds.sim(SimFixture.FPS14, rows);
            int y = 10 * 35 + 17;
            assertAll(
                () -> assertEquals(-1, s.asteroids.make(300, 300, 0, 0, 1f), "size 0"),
                () -> assertEquals(-1, s.asteroids.make(300, 300, 5, 0, 1f), "size 5"),
                () -> assertEquals(-1, s.asteroids.make(-1, 300, 1, 0, 1f), "outside a non-wrapping world"),
                () -> assertEquals(-1, s.asteroids.make(5 * 35 + 17, y, 1, 0, 1f), "FUEL (type 7 has bit 2)"),
                () -> assertTrue(s.asteroids.make(6 * 35 + 17, y, 1, 0, 1f) >= 0, "FILLED (type 2) is not refused"),
                () -> assertEquals(-1, s.asteroids.make(7 * 35 + 5, 10 * 35 + 30, 1, 0, 1f),
                    "REC_LU solid half (x < y in the block)"),
                () -> assertTrue(s.asteroids.make(7 * 35 + 30, 10 * 35 + 5, 1, 0, 1f) >= 0, "REC_LU open half"),
                () -> assertEquals(-1, s.asteroids.make(8 * 35 + 17, y, 1, 0, 1f), "REC_LD (type 4 has bit 2)"));
        }

        @Test
        @DisplayName("on a wrapping world a position past the edge wraps")
        void wraps() {
            Simulation s = sim("edgeWrap: yes\n");
            World w = s.world;
            int h = s.asteroids.make(w.pixelWidth + 10, 300, 1, 0, 1f);
            assertEquals(10, w.objects.px[h], "x wrapped");
        }
    }

    @Nested
    @DisplayName("update (Asteroid_update)")
    class Update {
        @Test
        @DisplayName("too many: marked to die in list order until the count fits; live ones spin and move")
        void tooMany() {
            Simulation s = sim("maxAsteroidDensity: 0.002\n");
            World w = s.world;
            Objects o = w.objects;
            int a = s.asteroids.make(300, 300, 1, 0, 0f);
            int b = s.asteroids.make(400, 300, 1, 0, 2f);
            int c = s.asteroids.make(500, 300, 1, 0, 2f);
            int rotB = w.modes.wireRotation[b];
            int cxB = o.cx[b];
            s.asteroids.update();
            assertAll(
                () -> assertEquals(1, w.modeParams.asteroidMax, "max (int)(0.002 * 900) = 1"),
                () -> assertEquals(0.0, o.life[a], EPS, "first marked"),
                () -> assertEquals(0.0, o.life[b], EPS, "second marked (count 3 -> 1)"),
                () -> assertEquals(1000.0, o.life[c], EPS, "the third survives"),
                () -> assertEquals(rotB, w.modes.wireRotation[b], "dead ones do not spin"),
                () -> assertEquals(cxB, o.cx[b], "or move"),
                () -> assertEquals((w.modes.wireRotation[c]), (w.modes.wireRotation[c]) % Const.RES, "(rotation in range)"),
                () -> assertTrue(o.cx[c] > Click.fromPixels(500f), "live ones move"));
        }

        @Test
        @DisplayName("placement: only with asteroidProb, room for a largest one, the chance roll; never near humans")
        void placement() {
            Simulation s = sim("asteroidProb: 1\nmaxAsteroidDensity: 0.02\n");
            World w = s.world;
            for (int i = 0; i < 20; i++) {
                s.asteroids.update();
            }
            int made = w.modes.asteroidCount;
            Simulation none = sim("maxAsteroidDensity: 0.02\n");
            none.asteroids.update();
            Simulation full = sim("asteroidProb: 1\nmaxAsteroidDensity: 0.009\n");
            full.asteroids.update();
            Simulation near = SimFixture.sim(SimFixture.FPS14 + "asteroidProb: 1\nmaxAsteroidDensity: 0.5\n", 6, 6, "_");
            Player p = ready(near, "P", 0, 105, 105);
            for (int i = 0; i < 30; i++) {
                near.asteroids.update();
            }
            assertAll(
                () -> assertEquals(1, w.modeParams.asteroidChance, "chance at least 1"),
                () -> assertTrue(made >= 1, "asteroids appear"),
                () -> assertTrue(w.modes.asteroidNum + 8 >= w.modeParams.asteroidMax, "until no largest one fits"),
                () -> assertEquals(0, none.world.modes.asteroidCount, "no asteroidProb: none"),
                () -> assertEquals(0, full.world.modes.asteroidCount, "no room for size 4 (max 8): none"),
                () -> assertEquals(0, near.world.modes.asteroidCount, "a tiny map: always within 5 blocks of the human"),
                () -> assertTrue(p.id > 0, "(human)"));
        }

        @Test
        @DisplayName("near an asteroid concentrator with asteroidConcentratorProb 1")
        void concentrator() {
            String[] rows = SimFixture.open(60, 60, "_");
            char[] r = rows[59 - 40].toCharArray();
            r[40] = '&';
            rows[59 - 40] = new String(r);
            Simulation s = TestWorlds.sim(SimFixture.FPS14 + "asteroidConcentratorRadius: 3\n", rows);
            for (int i = 0; i < 30; i++) {
                s.asteroids.place();
            }
            World w = s.world;
            boolean allNear = true;
            for (int h : asteroids(w)) {
                double d = Math.hypot(w.objects.px[h] - 40.5 * 35, w.objects.py[h] - 40.5 * 35);
                allNear &= d <= 3 * 35 + 2;
            }
            boolean all = allNear;
            assertAll(
                () -> assertTrue(w.modes.asteroidCount > 0, "asteroids appear"),
                () -> assertTrue(all, "all within the concentrator radius"));
        }
    }

    @Nested
    @DisplayName("broken (Break_asteroid)")
    class Broken {
        @Test
        @DisplayName("size > 1: two smaller ones split sideways, wreckage and debris; count and list updated")
        void splits() {
            Simulation s = sim("");
            World w = s.world;
            Objects o = w.objects;
            int h = s.asteroids.make(500, 500, 3, 0, 10f);
            long bursts = w.events.burstSeq();
            s.asteroids.broken(h);
            o.free(h);
            List<Integer> left = asteroids(w);
            int wreck = 0;
            for (int k = 0; k < o.count(); k++) {
                if (o.type[o.active[k]] == Obj.WRECKAGE) {
                    wreck++;
                }
            }
            int c1 = left.get(0);
            int c2 = left.get(1);
            int w0 = wreck;
            assertAll(
                () -> assertEquals(2, left.size(), "two children"),
                () -> assertEquals(2, w.modes.wireSize[c1] + w.modes.wireSize[c2] - 2, "both size 2"),
                () -> assertEquals(4, w.modes.asteroidNum, "count: 4 - 4 + 2 + 2"),
                () -> assertTrue(o.py[c1] != o.py[c2], "split across the flight direction"),
                () -> assertTrue(o.vx[c1] > 0 && o.vx[c2] > 0, "both keep flying forward"),
                () -> assertTrue(o.vy[c1] * o.vy[c2] < 0, "to either side"),
                () -> assertTrue(w0 > 0, "wreckage"),
                () -> assertEquals(bursts + 1, w.events.burstSeq(), "a debris burst"));
        }

        @Test
        @DisplayName("size 1: only wreckage and debris; item drops with asteroidMaxItems and asteroidItemProb")
        void dust() {
            Simulation s = sim("asteroidMaxItems: 3\nasteroidItemProb: 1\n");
            World w = s.world;
            List<String> drops = new ArrayList<>();
            s.asteroids.itemDrop = (px, py, vx, vy) -> drops.add(px + "," + py);
            int h = s.asteroids.make(500, 500, 1, 0, 0f);
            s.asteroids.broken(h);
            Simulation noItems = sim("asteroidMaxItems: 3\nasteroidItemProb: 0\n");
            List<String> none = new ArrayList<>();
            noItems.asteroids.itemDrop = (px, py, vx, vy) -> none.add("x");
            int h2 = noItems.asteroids.make(500, 500, 1, 0, 0f);
            noItems.asteroids.broken(h2);
            Simulation noHook = sim("asteroidMaxItems: 3\nasteroidItemProb: 1\n");
            int h3 = noHook.asteroids.make(500, 500, 1, 0, 0f);
            noHook.asteroids.broken(h3);
            assertAll(
                () -> assertEquals(0, w.modes.asteroidCount, "no children"),
                () -> assertEquals(0, w.modes.asteroidNum, "count back to 0"),
                () -> assertTrue(drops.size() >= 1 && drops.size() <= 3, "1..3 items dropped"),
                () -> assertEquals("500,500", drops.get(0), "at the asteroid"),
                () -> assertEquals(List.of(), none, "probability 0: none"),
                () -> assertEquals(0, noHook.world.modes.asteroidCount, "no item code: nothing happens"));
        }

        @Test
        @DisplayName("an asteroid whose life runs out is deleted and breaks (Delete_shot)")
        void expiry() {
            Simulation s = sim("");
            World w = s.world;
            int h = s.asteroids.make(500, 500, 2, 0, 0f);
            w.objects.life[h] = 1;
            s.expireObjects();
            assertEquals(2, w.modes.asteroidCount, "two size 1 children replace it");
        }
    }

    @Nested
    @DisplayName("playerHit (Player_collides_with_asteroid)")
    class PlayerHit {
        @Test
        @DisplayName("damage to the asteroid, points when it breaks, fuel cost unless fully shielded, may kill")
        void hit() {
            Simulation s = sim("asteroidPoints: 2\n");
            World w = s.world;
            Objects o = w.objects;
            Player p = ready(s, "P", 0, 300, 300);
            int h = s.asteroids.make(320, 300, 4, 0, 3f);
            o.life[h] = 999;
            double fuel = p.fuel;
            s.asteroids.playerHit(p, h);
            double cost = Math.abs((long) (2 * o.mass[h] * 3f));
            long from = w.events.seq();
            assertAll(
                () -> assertEquals((long) (999 + Asteroids.fuelHit(-25600, 4)), o.life[h], EPS, "life -= 500"),
                () -> assertEquals(Math.max(0, fuel - cost), p.fuel, 1, "fuel -|2*m*v|"),
                () -> assertTrue((p.status & Obj.KILLED) != 0, "an unshielded ship dies"),
                () -> assertEquals(-Scoring.rate(0, 0) * w.params.unownedKillScoreMult, p.score, 1e-6,
                    "Rate(0)*unownedKillScoreMult"),
                () -> assertTrue(from > 0, "(events)"));
        }

        @Test
        @DisplayName("message by speed; points for breaking it; shields, armor and asteroidCollisionMayKill")
        void variants() {
            Simulation s = sim("asteroidPoints: 2\n");
            World w = s.world;
            Objects o = w.objects;
            Player fast = ready(s, "F", 0, 300, 300);
            fast.velocity = 50;
            int h = s.asteroids.make(320, 300, 1, 0, 3f);
            long from = w.events.seq();
            s.asteroids.playerHit(fast, h);
            List<String> m = messages(w, from);
            double fastScore = fast.score;
            Player shield = ready(s, "S", 0, 400, 300);
            shield.used |= Has.SHIELD | Has.EMERGENCY_SHIELD;
            double fuel = shield.fuel;
            int h2 = s.asteroids.make(420, 300, 4, 0, 3f);
            s.asteroids.playerHit(shield, h2);
            Player armor = ready(s, "A", 0, 500, 300);
            armor.have |= Has.ARMOR;
            armor.items[com.fulcrologic.jxpilot.common.Item.ARMOR.ordinal()] = 1;
            s.asteroids.playerHit(armor, h2);
            Simulation safe = sim("asteroidCollisionMayKill: no\n");
            Player q = ready(safe, "Q", 0, 300, 300);
            int h3 = safe.asteroids.make(320, 300, 4, 0, 3f);
            safe.asteroids.playerHit(q, h3);
            Player slow = ready(s, "L", 0, 600, 300);
            int h4 = s.asteroids.make(620, 300, 4, 0, 3f);
            long from2 = w.events.seq();
            s.asteroids.playerHit(slow, h4);
            assertAll(
                () -> assertEquals("F smashed into an asteroid.", m.get(0), "faster than the asteroid"),
                () -> assertEquals(List.of("L was hit by an asteroid."), messages(w, from2), "slower"),
                () -> assertEquals(0.0, o.life[h], EPS, "a size 1 asteroid breaks"),
                () -> assertEquals(2 - Scoring.rate(0, 2) * w.params.unownedKillScoreMult, fastScore, 1e-6,
                    "asteroidPoints for breaking it, then the death"),
                () -> assertEquals(fuel, shield.fuel, EPS, "fully shielded: no fuel"),
                () -> assertEquals(0, shield.status & Obj.KILLED, "and alive"),
                () -> assertEquals(0, armor.status & Obj.KILLED, "armor survives"),
                () -> assertEquals(0, armor.have & Has.ARMOR, "and is used up"),
                () -> assertEquals(0, q.status & Obj.KILLED, "asteroidCollisionMayKill off: survives"));
        }
    }

    @Nested
    @DisplayName("collide (AsteroidCollision)")
    class Collide {
        @Test
        @DisplayName("a shot hits: absorbed, ED_SHOT_HIT damage, the owner gets points when it breaks")
        void shot() {
            Simulation s = sim("asteroidPoints: 3\n");
            World w = s.world;
            Objects o = w.objects;
            Player p = ready(s, "P", 0, 100, 100);
            int a = s.asteroids.make(500, 500, 1, 0, 0f);
            o.life[a] = 50;
            int shot = obj(w, Obj.SHOT, p.id, 505, 500, 10, 0, 1, 20);
            w.grid.rebuild(o);
            s.asteroids.collide();
            assertAll(
                () -> assertEquals(0.0, o.life[shot], EPS, "the shot is absorbed"),
                () -> assertEquals(0.0, o.life[a], EPS, "1000*-6400/(6400) = -1000: broken"),
                () -> assertEquals(3.0, p.score, EPS, "the owner gets asteroidPoints"),
                () -> assertEquals(10f / (o.mass[a] + 1), o.vx[a], 1e-6, "momentum shared"));
        }

        @Test
        @DisplayName("asteroid-asteroid: elastic, both damaged once (fuse), not while overlapping after a break")
        void asteroids() {
            Simulation s = sim("");
            World w = s.world;
            Objects o = w.objects;
            int a = s.asteroids.make(500, 500, 2, 0, 2f);
            int b = s.asteroids.make(520, 500, 2, 64, 2f);
            o.life[a] = 900;
            o.life[b] = 900;
            w.grid.rebuild(o);
            s.asteroids.collide();
            Simulation fresh = sim("");
            int c = fresh.asteroids.make(500, 500, 2, 0, 2f);
            int d = fresh.asteroids.make(520, 500, 2, 64, 2f);
            fresh.world.grid.rebuild(fresh.world.objects);
            fresh.asteroids.collide();
            assertAll(
                () -> assertTrue(o.life[b] < 900, "the other asteroid is damaged"),
                () -> assertEquals(o.life[b] - 1, o.fuseLife[b], EPS, "and its fuse set so it does not hit back"),
                () -> assertTrue(o.vx[a] < 2f, "elastic exchange"),
                () -> assertEquals(1000.0, fresh.world.objects.life[c] + 0, EPS, "fresh asteroids (life > fuse) skip each other"),
                () -> assertEquals(1000.0, fresh.world.objects.life[d], EPS, "both"));
        }

        @Test
        @DisplayName("ignored: unowned debris/sparks/items, itself; a ball bounces and dies; sparks are absorbed")
        void others() {
            Simulation s = sim("");
            World w = s.world;
            Objects o = w.objects;
            int a = s.asteroids.make(500, 500, 2, 0, 0f);
            o.life[a] = 900;
            int debris = obj(w, Obj.DEBRIS, Obj.NO_ID, 500, 500, 0, 0, 1, 10);
            int spark = obj(w, Obj.SPARK, 5, 500, 500, 0, 0, 1, 10);
            int ball = obj(w, Obj.BALL, Obj.NO_ID, 510, 500, 0, 0, 50, 100);
            o.plRadius[ball] = 10;
            w.grid.rebuild(o);
            s.asteroids.collide();
            assertAll(
                () -> assertEquals(10.0, o.life[debris], EPS, "unowned debris passes"),
                () -> assertEquals(0.0, o.life[spark], EPS, "an owned spark is absorbed"),
                () -> assertEquals(0.0, o.life[ball], EPS, "treasureCollisionDestroys: the ball dies"),
                () -> assertTrue(o.life[a] < 900, "and damages the asteroid"));
        }
    }
}
