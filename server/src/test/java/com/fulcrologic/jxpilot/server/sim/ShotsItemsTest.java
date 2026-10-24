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
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.common.sim.Click;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.ShipGeom;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.lastBurst;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.live;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The item parts of Shots: wide-angle and rear shots (Fire_normal_shots, Fire_*_shot) and the modified
 * explosions of mines and missiles (Delete_shot, shot.c:1217-1320). */
@DisplayName("Shots: wide-angle, rear shots and modified explosions (shot.c)")
class ShotsItemsTest {
    static final double EPS = 1e-9;
    static final int WIDE = Item.WIDEANGLE.ordinal();
    static final int REAR = Item.REARSHOT.ordinal();
    /** The default triangle plus one left gun, two right guns, one left rear and two right rear guns. */
    static final String GUN_SHIP =
        "(SH: 15,0 -9,8 -9,-8)(MG: 15,0)(EN: -9,0)(LG: 0,3)(RG: 0,-3 0,-1)(LR: -8,3)(RR: -8,-3 -8,-1)";

    /** Three rear guns per side at distinct heights. */
    static final String REAR3_SHIP =
        "(SH: 15,0 -9,8 -9,-8)(MG: 15,0)(EN: -9,0)(LR: -8,5 -8,3 -8,1)(RR: -8,-5 -8,-3 -8,-1)";

    Simulation s;
    World w;
    Objects o;
    Player p;

    void setup() {
        s = sim(FRAME, 20, 20, "_");
        w = s.world;
        o = w.objects;
        p = ready(s, "P", 0, 200, 300);
        p.dir = 0;
        w.frames = 100;
    }

    /** The click y of a shot from a gun with these rotated offsets, at heading 0, from the ship at y 300. */
    static int y(float[] gy) {
        return Click.fromPixels(300 + gy[0]);
    }

    List<Integer> dirs() {
        List<Integer> l = new ArrayList<>();
        for (int k = 0; k < o.count(); k++) {
            l.add(o.missileDir[o.active[k]]);
        }
        return l;
    }

    @Nested
    @DisplayName("fireNormalShots: wide-angle (Fire_normal_shots)")
    class WideAngle {
        @Test
        @DisplayName("without side guns each wideangle adds a shot to each side from the main gun, (i+1)*3 off")
        void mainGun() {
            setup();
            p.items[WIDE] = 2;
            s.shots.fireNormalShots(p);
            int g = Click.fromPixels(200 + p.ship.gunX[0]);
            assertAll(
                () -> assertEquals(List.of(0, 3, 125, 6, 122), dirs(), "main, left 3, right -3, left 6, right -6"),
                () -> assertEquals(g, o.cx[o.active[1]], "the left shot from the main gun"),
                () -> assertEquals(g, o.cx[o.active[4]], "the right shot from the main gun"),
                () -> assertEquals(5, p.shots, "five shots counted"));
        }

        @Test
        @DisplayName("the spread modifier narrows the angle to 3 - spread")
        void spread() {
            setup();
            p.items[WIDE] = 1;
            p.mods = Mods.withSpread(0, 2);
            s.shots.fireNormalShots(p);
            int narrow = dirs().size();
            List<Integer> d2 = dirs();
            setup();
            p.items[WIDE] = 2;
            p.mods = Mods.withSpread(0, 3);
            s.shots.fireNormalShots(p);
            assertAll(
                () -> assertEquals(List.of(0, 1, 127), d2, "spread 2: 1 unit apart"),
                () -> assertEquals(3, narrow, "one wideangle gives three shots"),
                () -> assertEquals(List.of(0, 0, 0, 0, 0), dirs(), "spread 3: all along the heading"));
        }

        @Test
        @DisplayName("side guns are used in turn (i % guns), rotated with the heading")
        void sideGuns() {
            setup();
            p.ship = new ShipGeom(ShipShape.parseOrDefault(GUN_SHIP));
            p.dir = 32;
            p.items[WIDE] = 3;
            s.shots.fireNormalShots(p);
            ShipGeom g = p.ship;
            assertAll(
                () -> assertEquals(1, g.lGunX.length, "the test ship has one left gun"),
                () -> assertEquals(2, g.rGunX.length, "and two right guns"),
                () -> assertEquals(List.of(32, 35, 29, 38, 26, 41, 23), dirs(), "main, then left/right pairs 3 apart"),
                () -> assertEquals(Click.fromPixels(200 + g.lGunX[0][32]), o.cx[o.active[1]], "left 1 from left gun 0"),
                () -> assertEquals(Click.fromPixels(300 + g.lGunY[0][32]), o.cy[o.active[3]], "left 2 from left gun 0 too"),
                () -> assertEquals(Click.fromPixels(200 + g.rGunX[0][32]), o.cx[o.active[2]], "right 1 from right gun 0"),
                () -> assertEquals(Click.fromPixels(200 + g.rGunX[1][32]), o.cx[o.active[4]], "right 2 from right gun 1"),
                () -> assertEquals(Click.fromPixels(200 + g.rGunX[0][32]), o.cx[o.active[6]], "right 3 from right gun 0"),
                () -> assertEquals(Click.fromPixels(300 + g.gunY[32]), o.cy[o.active[0]], "the main shot from the main gun"));
        }
    }

    @Nested
    @DisplayName("fireNormalShots: rear shots")
    class RearShots {
        @Test
        @DisplayName("without rear guns rear shots fan around the tail by (k * 3) / 2 from the ship's centre")
        void centre() {
            setup();
            p.items[REAR] = 3;
            s.shots.fireNormalShots(p);
            List<Integer> three = dirs();
            int c = Click.fromPixels(200);
            boolean allCentre = o.cx[o.active[1]] == c && o.cx[o.active[2]] == c && o.cx[o.active[3]] == c;
            setup();
            p.items[REAR] = 2;
            s.shots.fireNormalShots(p);
            assertAll(
                // k = rear - 1 - 2i: 2, 0, -2 -> 64 + 3, 64, 64 - 3
                () -> assertEquals(List.of(0, 67, 64, 61), three, "three rear shots: 67, 64, 61"),
                () -> assertTrue(allCentre, "all from the ship's centre (Fire_shot)"),
                // k = 1, -1 -> 64 + 3/2 = 65, 64 + (-3)/2 = 63 (C truncates toward zero)
                () -> assertEquals(List.of(0, 65, 63), dirs(), "two rear shots: 65 and 63"));
        }

        @Test
        @DisplayName("rear guns fire the side shots in C's index order; the middle shot comes from the centre")
        void guns() {
            setup();
            p.ship = new ShipGeom(ShipShape.parseOrDefault(GUN_SHIP));
            ShipGeom g = p.ship;
            p.items[REAR] = 4;
            s.shots.fireNormalShots(p);
            List<Integer> four = dirs();
            // i=0: k=3 right gun (4/2-0-1)%2 = 1; i=1: k=1 right gun 0; i=2: k=-1 left (2-5/2)%1 = 0; i=3: k=-3 left 0
            int a = o.active[1];
            int b = o.active[2];
            int c = o.active[3];
            int d = o.active[4];
            boolean right1 = o.cy[a] == Click.fromPixels(300 + g.rrGunY[1][0]);
            boolean right0 = o.cy[b] == Click.fromPixels(300 + g.rrGunY[0][0]);
            boolean left0 = o.cy[c] == Click.fromPixels(300 + g.lrGunY[0][0]) && o.cy[d] == o.cy[c];
            setup();
            p.ship = new ShipGeom(ShipShape.parseOrDefault(GUN_SHIP));
            p.items[REAR] = 3;
            s.shots.fireNormalShots(p);
            assertAll(
                () -> assertEquals(2, g.rrGunX.length, "the test ship has two right rear guns"),
                () -> assertEquals(List.of(0, 68, 65, 63, 60), four, "four rear shots: 64 + 9/2, 3/2, -3/2, -9/2"),
                () -> assertTrue(right1, "k=3 from right rear gun 1"),
                () -> assertTrue(right0, "k=1 from right rear gun 0"),
                () -> assertTrue(left0, "k=-1 and k=-3 from left rear gun 0"),
                () -> assertEquals(Click.fromPixels(300), o.cy[o.active[2]], "the middle (k=0) shot of three is from the centre"),
                () -> assertEquals(Click.fromPixels(300 + g.rrGunY[0][0]), o.cy[o.active[1]], "k=2 from right rear gun 0"),
                () -> assertEquals(Click.fromPixels(300 + g.lrGunY[0][0]), o.cy[o.active[3]], "k=-2 from left rear gun 0"));
        }

        @Test
        @DisplayName("with three guns on each rear side the gun indexes follow C's formulas exactly")
        void threeGuns() {
            setup();
            p.ship = new ShipGeom(ShipShape.parseOrDefault(REAR3_SHIP));
            ShipGeom g = p.ship;
            p.items[REAR] = 4;
            s.shots.fireNormalShots(p);
            List<Integer> y4 = new ArrayList<>();
            for (int k = 1; k < o.count(); k++) {
                y4.add(o.cy[o.active[k]]);
            }
            setup();
            p.ship = g;
            p.items[REAR] = 6;
            s.shots.fireNormalShots(p);
            List<Integer> y6 = new ArrayList<>();
            for (int k = 1; k < o.count(); k++) {
                y6.add(o.cy[o.active[k]]);
            }
            assertAll(
                () -> assertEquals(3, g.lrGunX.length, "three left rear guns"),
                () -> assertEquals(3, g.rrGunX.length, "three right rear guns"),
                // rear 4: right (2-i-1) = 1, 0; left (i - 5/2) = 0, 1
                () -> assertEquals(List.of(y(g.rrGunY[1]), y(g.rrGunY[0]), y(g.lrGunY[0]), y(g.lrGunY[1])), y4,
                    "four: right 1, right 0, left 0, left 1"),
                // rear 6: right (3-i-1) = 2, 1, 0; left (i - 7/2) = 0, 1, 2
                () -> assertEquals(List.of(y(g.rrGunY[2]), y(g.rrGunY[1]), y(g.rrGunY[0]), y(g.lrGunY[0]),
                    y(g.lrGunY[1]), y(g.lrGunY[2])), y6, "six: right 2, 1, 0, left 0, 1, 2"));
        }

        @Test
        @DisplayName("side and rear guns refuse at the shot limit, with shields or while phased")
        void gunsRefuse() {
            setup();
            p.ship = new ShipGeom(ShipShape.parseOrDefault(GUN_SHIP));
            float[] gx = p.ship.lGunX[0];
            float[] gy = p.ship.lGunY[0];
            p.shots = p.shotMax;
            s.shots.fireGun(p, 0, gx, gy);
            s.shots.fireShot(p, 0);
            int atMax = o.count();
            p.shots = 0;
            p.used |= Has.SHIELD;
            s.shots.fireGun(p, 0, gx, gy);
            s.shots.fireShot(p, 0);
            int shielded = o.count();
            p.used = Has.PHASING_DEVICE;
            s.shots.fireGun(p, 0, gx, gy);
            s.shots.fireShot(p, 0);
            int phased = o.count();
            p.used = 0;
            p.shots = p.shotMax - 1;
            s.shots.fireGun(p, 7, gx, gy);
            p.shots = p.shotMax - 1;
            s.shots.fireShot(p, 9);
            assertAll(
                () -> assertEquals(0, atMax, "at shotMax: nothing"),
                () -> assertEquals(0, shielded, "shielded: nothing"),
                () -> assertEquals(0, phased, "phased: nothing"),
                () -> assertEquals(List.of(7, 9), dirs(), "one below the limit both fire, in their directions"),
                () -> assertEquals(Click.fromPixels(200 + gx[0]), o.cx[o.active[0]], "the gun shot from the gun"),
                () -> assertEquals(Click.fromPixels(200), o.cx[o.active[1]], "Fire_shot from the centre"));
        }
    }

    @Nested
    @DisplayName("delete: explosions of mines and missiles (Delete_shot)")
    class Explode {
        static final double SMALL = 1e-6;

        void big() {
            s = TestWorlds.sim(FRAME + "shotLife: 59\n", SimFixture.open(150, 150, "_"));
            w = s.world;
            o = w.objects;
            p = ready(s, "P", 0, 200, 300);
            w.params.shotHitFuelDrainUsesKineticEnergy = false;
        }

        /** An exploding object at (1000, 1000), at rest, owned by P (team 3). */
        int boom(int type, int mods, double mass, int status) {
            int h = obj(w, type, p.id, 1000, 1000, 0, 0, mass, 1);
            o.mods[h] = mods;
            o.status[h] = status;
            o.team[h] = 3;
            return h;
        }

        /** The debris count min + (int)(rfrac * (max - min)) for the RNG seed. */
        int count(int seed, int min, int max) {
            return min + (int) (new Rng(seed).nextFloat() * (max - min));
        }

        @Test
        @DisplayName("a mine: intensity 512, 102..153 red debris, speed 20..128, life 8..256, COLLISIONSHOVE")
        void mine() {
            big();
            int h = boom(Obj.MINE, 0, 30, Obj.OWNERIMMUNE);
            w.rng.seed(3);
            long b0 = w.events.burstSeq();
            s.shots.delete(h);
            DebrisBurst d = lastBurst(w);
            int i = Events.burstIndex(w.events.burstSeq() - 1);
            int first = o.active[0];
            assertAll(
                () -> assertEquals(b0 + 1, w.events.burstSeq(), "one burst"),
                () -> assertEquals(count(3, 102, 153), d.count, "count (int)(0.2f*512) .. (int)(0.3f*512)"),
                () -> assertEquals(d.count, o.count(), "the mine is replaced by its debris"),
                () -> assertEquals(20f, d.minSpeed, "min speed 20"),
                () -> assertEquals(128f, d.maxSpeed, "max speed 512 >> 2"),
                () -> assertEquals(8, d.minLife, "min life 8"),
                () -> assertEquals(256, d.maxLife, "max life 512 >> 1"),
                () -> assertEquals(Records.BurstKind.DEBRIS, w.events.burstKind[i], "a DEBRIS burst"),
                () -> assertEquals(Records.ObjColor.RED, w.events.burstColor[i], "red"),
                () -> assertEquals(p.id, w.events.burstOwner[i], "owned by the mine's owner"),
                () -> assertEquals(Obj.DEBRIS, o.type[first], "debris"),
                () -> assertEquals(4.5f, o.mass[first], "DEBRIS_MASS"),
                () -> assertEquals(Obj.GRAVITY | Obj.COLLISIONSHOVE, o.status[first], "GRAVITY | COLLISIONSHOVE"),
                () -> assertEquals(3, o.team[first], "of the mine's team"),
                () -> assertEquals(6, o.plRadius[first], "radius 6"));
        }

        @Test
        @DisplayName("mini mines make 1/(mini+1) as much debris; power does not weaken mines; a cannon mine keeps FROMCANNON")
        void mineVariants() {
            big();
            w.rng.seed(4);
            s.shots.delete(boom(Obj.MINE, Mods.withMini(0, 1), 15, 0));
            DebrisBurst mini = SimFixture.copy(lastBurst(w));
            for (int k = o.count() - 1; k >= 0; k--) {
                o.free(o.active[k]);
            }
            w.rng.seed(4);
            s.shots.delete(boom(Obj.MINE, Mods.withPower(0, 3), 30, Obj.FROMCANNON));
            DebrisBurst power = lastBurst(w);
            int first = o.active[0];
            assertAll(
                () -> assertEquals(count(4, 51, 76), mini.count, "mini 1: (int)(0.2f*512*0.5f) .. (int)(0.3f*512*0.5f)"),
                () -> assertEquals(256, mini.maxLife, "the same intensity: life up to 256"),
                () -> assertEquals(count(4, 102, 153), power.count, "power 3 leaves a mine's 512"),
                () -> assertEquals(256, power.maxLife, "life up to 256"),
                () -> assertEquals(Obj.GRAVITY | Obj.COLLISIONSHOVE | Obj.FROMCANNON, o.status[first], "FROMCANNON is kept"));
        }

        @Test
        @DisplayName("a nuclear mine: intensity 512 * 3 * mass/30 * (mini+1)")
        void nuclearMine() {
            big();
            w.rng.seed(5);
            s.shots.delete(boom(Obj.MINE, Mods.withNuclear(0, Mods.NUCLEAR), 120, 0));
            DebrisBurst nuke = SimFixture.copy(lastBurst(w));
            int nukeCount = o.count();
            for (int k = o.count() - 1; k >= 0; k--) {
                o.free(o.active[k]);
            }
            w.rng.seed(5);
            s.shots.delete(boom(Obj.MINE, Mods.withMini(Mods.withNuclear(0, Mods.NUCLEAR), 1), 60, 0));
            DebrisBurst miniNuke = lastBurst(w);
            assertAll(
                () -> assertEquals(3072, nuke.maxLife, "nuke factor 3 * 120/30 = 12: 6144 >> 1"),
                () -> assertEquals(count(5, 1228, 1843), nuke.count, "(int)(0.2f*6144) .. (int)(0.3f*6144)"),
                () -> assertEquals(nuke.count, nukeCount, "all made"),
                () -> assertEquals(3072, miniNuke.maxLife, "a half-mass mini nuke: 3 * 60/30 * 2 = 12 too"),
                () -> assertEquals(count(5, 614, 921), miniNuke.count, "with half the debris"));
        }

        @Test
        @DisplayName("missiles: intensity 32 / (1 + power); nuclear 32 * 4 * mass/5")
        void missiles() {
            big();
            int[] counts = new int[5];
            int[] maxLife = new int[5];
            float[] maxSpeed = new float[5];
            int[] types = {Obj.TORPEDO, Obj.TORPEDO, Obj.SMART_SHOT, Obj.HEAT_SHOT, Obj.TORPEDO};
            int[] mods = {0, Mods.withPower(0, 1), Mods.withPower(0, 3), Mods.withPower(0, 2),
                Mods.withNuclear(0, Mods.NUCLEAR)};
            double[] mass = {5, 5, 5, 5, 15};
            for (int k = 0; k < 5; k++) {
                w.rng.seed(6);
                s.shots.delete(boom(types[k], mods[k], mass[k], Obj.GRAVITY));
                DebrisBurst d = lastBurst(w);
                counts[k] = d.count;
                maxLife[k] = d.maxLife;
                maxSpeed[k] = d.maxSpeed;
            }
            assertAll(
                () -> assertEquals(count(6, 6, 9), counts[0], "a torpedo: 32 -> 6..9"),
                () -> assertEquals(16, maxLife[0], "life up to 16"),
                () -> assertEquals(20f, maxSpeed[0], "speed 8 raised to 20"),
                () -> assertEquals(count(6, 3, 4), counts[1], "power 1: 16 -> 3..4"),
                () -> assertEquals(8, maxLife[1], "life up to 8"),
                () -> assertEquals(count(6, 1, 2), counts[2], "a smart missile with power 3: 8 -> 1..2"),
                () -> assertEquals(8, maxLife[2], "max life 4 raised to the min 8"),
                () -> assertEquals(count(6, 2, 3), counts[3], "a heat-seeker with power 2: 10 -> 2..3"),
                () -> assertEquals(count(6, 76, 115), counts[4], "a nuclear torpedo of 3 missiles: 32*12 = 384"),
                () -> assertEquals(192, maxLife[4], "life up to 384 >> 1"),
                () -> assertEquals(96f, maxSpeed[4], "speed up to 384 >> 2"));
        }

        /** Particle lives and speeds of the cluster fragments (no burst event). */
        record Frag(int n, int minLife, int maxLife, float minSpeed, float maxSpeed, float mass, int type, int mods,
                    int color, int status) {}

        Frag frags() {
            List<Integer> l = live(w);
            int lo = Integer.MAX_VALUE;
            int hi = 0;
            float slo = Float.MAX_VALUE;
            float shi = 0;
            for (int h : l) {
                lo = Math.min(lo, (int) o.life[h]);
                hi = Math.max(hi, (int) o.life[h]);
                float sp = (float) Math.hypot(o.vx[h], o.vy[h]);
                slo = Math.min(slo, sp);
                shi = Math.max(shi, sp);
            }
            int f = l.get(0);
            return new Frag(l.size(), lo, hi, slo, shi, o.mass[f], o.type[f], o.mods[f], o.color[f], o.status[f]);
        }

        @Test
        @DisplayName("a cluster torpedo: (int)(5*0.9/0.1f) = 44, 4x the shots, life *0.2, sent as cluster shots")
        void cluster() {
            big();
            w.rng.seed(7);
            long b0 = w.events.burstSeq();
            s.shots.delete(boom(Obj.TORPEDO, Obj.MOD_CLUSTER, 5, Obj.GRAVITY));
            Frag f = frags();
            assertAll(
                () -> assertEquals(b0, w.events.burstSeq(), "no burst event"),
                () -> assertEquals(count(7, 35, 52), f.n(), "(int)(0.2f*44*4) .. (int)(0.3f*44*4) fragments"),
                () -> assertTrue(f.minLife() >= 1 && f.maxLife() <= 4, "lives within (int)(8*0.2f)=1 .. (int)(22*0.2f)=4"),
                () -> assertEquals(20f, f.minSpeed(), 0.02, "speed 20 (11 raised to 20; chords <= 0.02 short)"),
                () -> assertEquals(20f, f.maxSpeed(), 0.02, "all at speed 20"),
                () -> assertEquals(0.3f, f.mass(), "3 * shotMass (kinetic mass off)"),
                () -> assertEquals(Obj.SHOT, f.type(), "shots"),
                () -> assertEquals(Obj.MOD_CLUSTER, f.mods(), "marked CLUSTER"),
                () -> assertEquals(Records.ObjColor.WHITE, f.color(), "white"),
                () -> assertEquals(Obj.GRAVITY, f.status(), "GRAVITY, no COLLISIONSHOVE for missiles"));
        }

        @Test
        @DisplayName("velocity slows and lengthens cluster fragments by 1 << velocity; mini does not reduce them")
        void clusterVelocityMini() {
            big();
            w.rng.seed(8);
            s.shots.delete(boom(Obj.TORPEDO, Mods.withVelocity(Obj.MOD_CLUSTER, 2), 5, Obj.GRAVITY));
            Frag v2 = frags();
            for (int k = o.count() - 1; k >= 0; k--) {
                o.free(o.active[k]);
            }
            w.rng.seed(8);
            s.shots.delete(boom(Obj.TORPEDO, Mods.withMini(Obj.MOD_CLUSTER, 3), 1.25, Obj.GRAVITY));
            Frag mini = frags();
            assertAll(
                () -> assertEquals(count(8, 35, 52), v2.n(), "velocity does not change the count"),
                () -> assertEquals(5f, v2.maxSpeed(), 0.02, "speed 20 / 4 = 5 (max 11/4 raised to 5)"),
                () -> assertEquals(5f, v2.minSpeed(), 0.02, "all at 5"),
                () -> assertTrue(v2.minLife() >= 6 && v2.maxLife() <= 17, "lives (int)(8*0.8f)=6 .. (int)(22*0.8f)=17"),
                () -> assertTrue(v2.maxLife() > 4, "longer than without velocity"),
                // intensity (int)(1.25*0.9/0.1f) = 11: (int)(0.2f*11*4) = 8 .. (int)(0.3f*11*4) = 13
                () -> assertEquals(count(8, 8, 13), mini.n(), "a mini cluster's count is not divided by mini"));
        }

        @Test
        @DisplayName("a nuclear cluster: modv * 4, one shot per unit, nuke factor / nukeClusterDamage")
        void nuclearCluster() {
            big();
            w.items.nukeClusterDamage = 2.0f;
            w.rng.seed(9);
            s.shots.delete(boom(Obj.TORPEDO, Mods.withVelocity(Obj.MOD_CLUSTER | Mods.withNuclear(0, Mods.NUCLEAR), 1),
                15, Obj.GRAVITY));
            Frag f = frags();
            // intensity (int)(15*0.9/0.1f) = 134; nuke 4*15/5 = 12 / 2 = 6 -> 804; modv 2*4 = 8, num_modv 1
            assertAll(
                () -> assertEquals(count(9, 160, 241), f.n(), "(int)(0.2f*804) .. (int)(0.3f*804)"),
                () -> assertTrue(f.minLife() >= 12 && f.maxLife() <= 643, "lives (int)(8*1.6f)=12 .. (int)(402*1.6f)=643"),
                () -> assertTrue(f.maxLife() > 400, "long lives"),
                () -> assertTrue(f.minSpeed() >= 2.5f - 0.02f && f.maxSpeed() <= 25.125f + 0.01f,
                    "speeds 20/8 = 2.5 .. (804>>2)/8 = 25.125"));
        }

        @Test
        @DisplayName("implosion makes negative-mass debris and fragments; a massless mine makes nothing")
        void implosion() {
            big();
            w.rng.seed(10);
            s.shots.delete(boom(Obj.MINE, Obj.MOD_IMPLOSION, 30, 0));
            float debris = o.mass[o.active[0]];
            for (int k = o.count() - 1; k >= 0; k--) {
                o.free(o.active[k]);
            }
            s.shots.delete(boom(Obj.SMART_SHOT, Obj.MOD_IMPLOSION | Obj.MOD_CLUSTER, 5, 0));
            float frag = o.mass[o.active[0]];
            for (int k = o.count() - 1; k >= 0; k--) {
                o.free(o.active[k]);
            }
            long b0 = w.events.burstSeq();
            int dud = boom(Obj.MINE, 0, 0, 0);
            s.shots.delete(dud);
            assertAll(
                () -> assertEquals(-4.5f, debris, "implosion debris has mass -DEBRIS_MASS"),
                () -> assertEquals(-0.3f, frag, SMALL, "implosion fragments -3 * shotMass"),
                () -> assertEquals(0, o.count(), "a mine without mass leaves nothing"),
                () -> assertEquals(b0, w.events.burstSeq(), "and no burst"));
        }
    }
}
