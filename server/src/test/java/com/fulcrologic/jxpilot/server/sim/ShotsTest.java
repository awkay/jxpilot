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
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FPS14;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.lastBurst;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.live;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shots (shot.c)")
class ShotsTest {
    static final double EPS = 1e-9;
    static final double FUEL0 = 1000 * 256;

    @Nested
    @DisplayName("fireMainShot / fire (Fire_main_shot, Fire_general_shot)")
    class Fire {
        Simulation s;
        World w;
        Player p;

        void setup(String extra) {
            s = sim(FRAME + extra, 20, 20, "__");
            w = s.world;
            p = ready(s, "P", 0, 200, 300);
            p.dir = 0;
        }

        @Test
        @DisplayName("a shot from the gun: velocity = ship + shotSpeed along dir, fuel, count, recoil, fuse")
        void mainShot() {
            setup("");
            p.vx = 1;
            p.vy = 2;
            float m = p.mass;
            s.shots.fireMainShot(p, 0);
            Objects o = w.objects;
            int h = o.active[0];
            assertAll(
                () -> assertEquals(1, o.count(), "one object is made"),
                () -> assertEquals(Obj.SHOT, o.type[h], "of type SHOT"),
                () -> assertEquals(Click.fromPixels(200 + p.ship.gunX[0]), o.cx[h], "at the main gun x"),
                () -> assertEquals(Click.fromPixels(300 + p.ship.gunY[0]), o.cy[h], "at the main gun y"),
                () -> assertEquals(o.px[h], o.prevX[h], "previous position = position"),
                () -> assertEquals(1 + 21.0f, o.vx[h], "vx = ship vx + shotSpeed * tcos(0)"),
                () -> assertEquals(2 + 0.0f, o.vy[h], "vy = ship vy + shotSpeed * tsin(0)"),
                () -> assertEquals(0.1f, o.mass[h], "mass shotMass (a C float)"),
                () -> assertEquals(60.0, o.life[h], EPS, "life shotLife"),
                // fuse = (int)(2*SHIP_SZ/shotSpeed + 1) = (int)(32/21 + 1) = 2 (shot.c Fire_general_shot)
                () -> assertEquals(58.0, o.fuseLife[h], EPS, "fuse life = life - (int)(2*SHIP_SZ/speed + 1)"),
                () -> assertEquals(p.id, o.owner[h], "owned by the shooter"),
                () -> assertEquals(p.team, o.team[h], "of the shooter's team"),
                () -> assertEquals(Obj.GRAVITY, o.status[h], "affected by gravity with shotsGravity"),
                () -> assertEquals(0, o.missileDir[h], "remembers its direction"),
                () -> assertEquals(Records.ObjColor.WHITE, o.color[h], "white"),
                () -> assertEquals(FUEL0 - 51, p.fuel, EPS, "costs (long)ED_SHOT = 51 fuel"),
                () -> assertEquals(1, p.shots, "counts one shot in the air"),
                // Recoil: v -= (shot.v - ship.v) * shot.mass / ship.mass
                () -> assertEquals(1f - ((22f - 1f) * 0.1f) / m, p.vx, "recoil x = (shot.vx - vx) * 0.1 / mass (floats)"),
                () -> assertEquals(2.0, p.vy, EPS, "no recoil across the shot direction"));
        }

        @Test
        @DisplayName("heading up: the gun is above the ship and the recoil pushes down")
        void headingUp() {
            setup("");
            p.dir = 32;
            float m = p.mass;
            s.shots.fireMainShot(p, 32);
            Objects o = w.objects;
            int h = o.active[0];
            assertAll(
                () -> assertEquals(Click.fromPixels(300 + p.ship.gunY[32]), o.cy[h], "at the gun y of heading 32"),
                () -> assertEquals(15.0, p.ship.gunY[32], 1e-12, "the gun is 15 px ahead"),
                () -> assertEquals(0f - ((21f - 0f) * 0.1f) / m, p.vy, "recoil y = -(shot.vy - vy) * 0.1 / mass"));
        }

        @Test
        @DisplayName("the fired direction may differ from the heading; the gun stays at the heading")
        void otherDirection() {
            setup("shotsGravity: no\n");
            s.shots.fireMainShot(p, 32);
            Objects o = w.objects;
            int h = o.active[0];
            assertAll(
                () -> assertEquals(Click.fromPixels(200 + p.ship.gunX[0]), o.cx[h], "the gun of the heading"),
                () -> assertEquals(Trig.tcos(32) * 21, o.vx[h], EPS, "vx along dir 32"),
                () -> assertEquals(21.0, o.vy[h], EPS, "vy = shotSpeed upward"),
                () -> assertEquals(0, o.status[h], "no GRAVITY without shotsGravity"),
                () -> assertEquals(32, o.missileDir[h], "remembers dir 32"));
        }

        @Test
        @DisplayName("needs 0.2 fuel (51.2, C compares with the uncast ED_SHOT)")
        void needsFuel() {
            setup("");
            p.fuel = 51;
            int r51 = s.shots.fire(p, 0, false, 200, 300, Obj.SHOT, 0, 0);
            int count51 = w.objects.count();
            double fuel51 = p.fuel;
            p.fuel = 51.2;
            s.shots.fireMainShot(p, 0);
            assertAll(
                () -> assertEquals(-1, r51, "fire returns -1"),
                () -> assertEquals(0, count51, "51 fuel is not enough"),
                () -> assertEquals(51.0, fuel51, EPS, "and is not spent"),
                () -> assertEquals(1, w.objects.count(), "51.2 fuel fires"),
                () -> assertEquals(51.2 - 51, p.fuel, EPS, "leaving 0.2"),
                () -> assertEquals(1, p.shots, "only the successful shot counts"));
        }

        @Test
        @DisplayName("no shot at the shot limit, with shields or while phased")
        void refused() {
            setup("");
            p.shots = p.shotMax;
            s.shots.fireMainShot(p, 0);
            int atMax = w.objects.count();
            p.shots = p.shotMax - 1;
            s.shots.fireMainShot(p, 0);
            int belowMax = w.objects.count();
            p.used |= Has.SHIELD;
            s.shots.fireMainShot(p, 0);
            int shielded = w.objects.count();
            p.used = Has.PHASING_DEVICE;
            s.shots.fireMainShot(p, 0);
            assertAll(
                () -> assertEquals(0, atMax, "at shotMax nothing is fired"),
                () -> assertEquals(1, belowMax, "one below shotMax fires"),
                () -> assertEquals(1, shielded, "shields block firing"),
                () -> assertEquals(1, w.objects.count(), "phasing blocks firing"),
                () -> assertEquals(FUEL0 - 51, p.fuel, EPS, "only the one shot cost fuel"));
        }

        @Test
        @DisplayName("a full pool makes nothing and costs nothing")
        void poolFull() {
            setup("");
            while (w.objects.alloc() >= 0) {
                // fill
            }
            int r = s.shots.fire(p, 0, false, 200, 300, Obj.SHOT, 0, 0);
            assertAll(
                () -> assertEquals(-1, r, "returns -1"),
                () -> assertEquals(FUEL0, p.fuel, EPS, "no fuel spent"),
                () -> assertEquals(0, p.shots, "no shot counted"));
        }

        @Test
        @DisplayName("a start outside a flat world makes nothing (fuel already spent, as C); wrapping maps wrap it")
        void outside() {
            setup("");
            int r = s.shots.fire(p, 0, false, -10, 300, Obj.SHOT, 0, 0);
            Simulation ws = sim(FRAME + "edgeWrap: yes\n", 20, 20, "_");
            Player q = ready(ws, "Q", 0, 200, 300);
            int h = ws.shots.fire(q, 0, false, -10, 300, Obj.SHOT, 0, 0);
            int hy = ws.shots.fire(q, 0, false, 10, 705, Obj.SHOT, 0, 0);
            assertAll(
                () -> assertEquals(-1, r, "no shot outside a flat world"),
                () -> assertEquals(0, w.objects.count(), "nothing allocated"),
                () -> assertEquals(FUEL0 - 51, p.fuel, EPS, "the fuel is spent before the position check (C)"),
                () -> assertEquals(1, p.shots, "and the shot counted (C)"),
                () -> assertEquals(Click.fromPixels(690), ws.world.objects.cx[h], "x wraps to width - 10"),
                () -> assertEquals(Click.fromPixels(5), ws.world.objects.cy[hy], "y wraps to 5"));
        }

        @Test
        @DisplayName("the world edges: 0 is inside, width/height are outside")
        void edges() {
            setup("");
            assertAll(
                () -> assertTrue(s.shots.fire(null, 0, true, 0, 300, Obj.CANNON_SHOT, 0, 0) >= 0, "x = 0 is inside"),
                () -> assertTrue(s.shots.fire(null, 0, true, 300, 0, Obj.CANNON_SHOT, 0, 0) >= 0, "y = 0 is inside"),
                () -> assertEquals(-1, s.shots.fire(null, 0, true, 700, 300, Obj.CANNON_SHOT, 0, 0), "x = width is outside"),
                () -> assertEquals(-1, s.shots.fire(null, 0, true, 300, 700, Obj.CANNON_SHOT, 0, 0), "y = height is outside"),
                () -> assertEquals(-1, s.shots.fire(null, 0, true, 300, -0.5f, Obj.CANNON_SHOT, 0, 0), "y < 0 is outside"),
                () -> assertEquals(2, w.objects.count(), "only the two inside shots exist"));
        }

        @Test
        @DisplayName("a cannon shot: life 8 + rand%24, mass 0.4, FROMCANNON, unowned, keeps flak mods")
        void cannonShot() {
            setup("");
            w.rng.seed(5);
            Rng ref = new Rng(5);
            int life = 8 + Integer.remainderUnsigned(ref.nextInt(), 24);
            int h = s.shots.fire(null, 2, true, 100, 120, Obj.CANNON_SHOT, 32, Obj.MOD_CLUSTER);
            int plain = s.shots.fire(p, 0, false, 100, 120, Obj.SHOT, 32, Obj.MOD_CLUSTER);
            Objects o = w.objects;
            assertAll(
                () -> assertEquals(Obj.CANNON_SHOT, o.type[h], "of type CANNON_SHOT"),
                () -> assertEquals((double) life, o.life[h], EPS, "life = 8 + randomMT() % 24"),
                () -> assertEquals(life - 2.0, o.fuseLife[h], EPS, "fuse life = life - 2"),
                () -> assertEquals(0.4f, o.mass[h], "CANNON_SHOT_MASS (assigned to a C float)"),
                () -> assertEquals(Obj.GRAVITY | Obj.FROMCANNON, o.status[h], "GRAVITY | FROMCANNON"),
                () -> assertEquals(Obj.NO_ID, o.owner[h], "unowned"),
                () -> assertEquals(2, o.team[h], "the cannon's team"),
                () -> assertEquals(Obj.MOD_CLUSTER, o.mods[h], "flak keeps CLUSTER"),
                () -> assertEquals(Click.fromPixels(100), o.cx[h], "from the given x"),
                () -> assertEquals(0.0, o.vx[h], 1e-12, "no shooter velocity: vx = 21 * tcos(32)"),
                () -> assertEquals(21.0, o.vy[h], EPS, "vy = 21"),
                () -> assertEquals(0, o.mods[plain], "plain shots drop modifiers"),
                () -> assertEquals(FUEL0 - 51, p.fuel, EPS, "cannon shots cost the player nothing"));
        }
    }

    @Nested
    @DisplayName("fireNormalShots (Fire_normal_shots) and autofire")
    class Repeat {
        @Test
        @DisplayName("fires iff fireRepeatRate frames passed since the last shot")
        void rate() {
            Simulation s = sim(FRAME + "fireRepeatRate: 5\n", 20, 20, "_");
            World w = s.world;
            Player p = ready(s, "P", 0, 200, 300);
            w.frames = 4;
            s.shots.fireNormalShots(p);
            int early = w.objects.count();
            w.frames = 5;
            s.shots.fireNormalShots(p);
            int onTime = w.objects.count();
            double t = p.shotTime;
            w.frames = 9;
            s.shots.fireNormalShots(p);
            int again = w.objects.count();
            w.frames = 10;
            s.shots.fireNormalShots(p);
            assertAll(
                () -> assertEquals(0, early, "4 frames after shot time 0 is too early"),
                () -> assertEquals(1, onTime, "5 frames later it fires"),
                () -> assertEquals(5.0, t, EPS, "remembers the frame clock as shot time"),
                () -> assertEquals(1, again, "9 is too early for the next"),
                () -> assertEquals(2, w.objects.count(), "10 fires again"));
        }

        int heldShots(String extra, int ticks, int releaseAfter) {
            Simulation s = TestWorlds.sim(extra, SimFixture.open(40, 40, "_"));
            Player p = ready(s, "P", 0, 700, 700);
            long fire = ControlKey.FIRE_SHOT.mask();
            TestWorlds.run(s, releaseAfter, fire);
            TestWorlds.run(s, ticks - releaseAfter, 0L);
            return (int) Math.round((FUEL0 - p.fuel) / 51);
        }

        /** C model: a shot whenever frame_loops (1, 2, ...) >= shot_time (initially 0) + rate. */
        int model(int ticks, int rate) {
            long f = 1;
            long last = 0;
            int n = 0;
            for (int k = 0; k < ticks; k++) {
                if (f >= last + rate) {
                    n++;
                    last = f;
                }
                f++;
            }
            return n;
        }

        @Test
        @DisplayName("holding fire repeats every fireRepeatRate original frames; releasing stops it")
        void autofire() {
            assertAll(
                () -> assertEquals(5, heldShots(FRAME, 10, 10),
                    "frame_loops starts at 1: the press at frame 1 is too early, then shots at frames 2, 4, 6, 8, 10"),
                () -> assertEquals(model(10, 2), heldShots(FRAME, 10, 10), "matches the frame-clock model"),
                () -> assertEquals(model(14, 2), heldShots(FPS14, 14, 14), "at 14 fps a tick is a frame too"),
                () -> assertEquals(7, heldShots(FPS14, 14, 14), "14 frames of holding give 7 shots"),
                () -> assertEquals(2, heldShots(FRAME, 20, 5), "releasing after 5 ticks stops at 2 shots"));
        }
    }

    @Nested
    @DisplayName("delete (Delete_shot)")
    class Delete {
        Simulation s;
        World w;
        Player p;

        void setup() {
            s = sim(FRAME, 20, 20, "_");
            w = s.world;
            p = ready(s, "P", 0, 200, 300);
        }

        @Test
        @DisplayName("a player's own plain shot returns its slot, never below zero")
        void ownShot() {
            setup();
            p.shots = 2;
            int h = obj(w, Obj.SHOT, p.id, 50, 50, 0, 0, 0.1, 1);
            s.shots.delete(h);
            int afterOne = p.shots;
            boolean liveAfter = w.objects.isLive(h);
            p.shots = 0;
            s.shots.delete(obj(w, Obj.SHOT, p.id, 50, 50, 0, 0, 0.1, 1));
            int clamped = p.shots;
            p.shots = 3;
            int fc = obj(w, Obj.SHOT, p.id, 50, 50, 0, 0, 0.1, 1);
            w.objects.status[fc] = Obj.FROMCANNON;
            s.shots.delete(fc);
            int cl = obj(w, Obj.SHOT, p.id, 50, 50, 0, 0, 0.1, 1);
            w.objects.mods[cl] = Obj.MOD_CLUSTER;
            s.shots.delete(cl);
            s.shots.delete(obj(w, Obj.SHOT, Obj.NO_ID, 50, 50, 0, 0, 0.1, 1));
            s.shots.delete(obj(w, Obj.SHOT, 77, 50, 50, 0, 0, 0.1, 1));
            assertAll(
                () -> assertEquals(1, afterOne, "own shot gives back one slot"),
                () -> assertFalse(liveAfter, "the shot is freed"),
                () -> assertEquals(0, clamped, "the count does not go below zero"),
                () -> assertEquals(3, p.shots, "cannon, cluster, unowned and orphan shots give nothing back"),
                () -> assertEquals(0, w.objects.count(), "all deleted objects are freed"));
        }

        @Test
        @DisplayName("sparks and debris just vanish; a cannon shot without mass makes no explosion")
        void vanish() {
            setup();
            long bursts = w.events.burstSeq();
            s.shots.delete(obj(w, Obj.SPARK, p.id, 50, 50, 0, 0, 0.7, 1));
            s.shots.delete(obj(w, Obj.DEBRIS, p.id, 50, 50, 0, 0, 4.5, 1));
            s.shots.delete(obj(w, Obj.CANNON_SHOT, Obj.NO_ID, 50, 50, 0, 0, 0, 1));
            assertAll(
                () -> assertEquals(0, w.objects.count(), "nothing remains"),
                () -> assertEquals(bursts, w.events.burstSeq(), "no burst"));
        }

        @Test
        @DisplayName("a cannon shot explodes into 6-8 red debris at speed 20, life 8..16, from its previous position")
        void cannonDebris() {
            setup();
            int h = obj(w, Obj.CANNON_SHOT, Obj.NO_ID, 300, 300, 5, -3, 0.4, 1);
            w.objects.status[h] = Obj.GRAVITY | Obj.FROMCANNON;
            w.objects.team[h] = 2;
            w.objects.setClicks(h, Click.fromPixels(320), Click.fromPixels(290));
            w.rng.seed(9);
            Rng ref = new Rng(9);
            int num = 6 + (int) (ref.nextFloat() * 3);
            int seed = ref.nextInt();
            s.shots.delete(h);
            DebrisBurst d = lastBurst(w);
            int i = Events.burstIndex(w.events.burstSeq() - 1);
            List<Integer> l = live(w);
            Objects o = w.objects;
            int first = l.get(0);
            assertAll(
                () -> assertEquals(num, d.count, "count = 6 + (int)(rfrac * 3)"),
                () -> assertEquals(seed, d.seed, "seed is the next RNG int"),
                () -> assertEquals(300.0f, d.x, "from the previous x"),
                () -> assertEquals(300.0f, d.y, "from the previous y"),
                () -> assertEquals(5.0f, d.vx, "with the shot's velocity x"),
                () -> assertEquals(-3.0f, d.vy, "with the shot's velocity y"),
                () -> assertEquals(0, d.minDir, "all directions from 0"),
                () -> assertEquals(Const.RES - 1, d.dirSpan, "to RES-1"),
                () -> assertEquals(20.0f, d.minSpeed, "speed 20"),
                () -> assertEquals(20.0f, d.maxSpeed, "max speed (32>>2 = 8) is raised to the min speed 20"),
                () -> assertEquals(8, d.minLife, "min life 8"),
                () -> assertEquals(16, d.maxLife, "max life 32>>1 = 16"),
                () -> assertEquals(Records.BurstKind.DEBRIS, w.events.burstKind[i], "a DEBRIS burst"),
                () -> assertEquals(Records.ObjColor.RED, w.events.burstColor[i], "red"),
                () -> assertEquals(Obj.NO_ID, w.events.burstOwner[i], "unowned"),
                () -> assertEquals(num, l.size(), "the shot is replaced by its debris"),
                () -> assertEquals(Obj.DEBRIS, o.type[first], "debris particles"),
                () -> assertEquals(4.5, o.mass[first], EPS, "of DEBRIS_MASS"),
                () -> assertEquals(Obj.GRAVITY | Obj.FROMCANNON, o.status[first], "GRAVITY | FROMCANNON"),
                () -> assertEquals(6, o.plRange[first], "radius 6"),
                () -> assertEquals(2, o.team[first], "the shot's team"));
        }

        @Test
        @DisplayName("flak explodes into cluster shots: 2 fragments, kinetic mass, life 2, sent as shots not a burst")
        void flak() {
            setup();
            int h = obj(w, Obj.CANNON_SHOT, Obj.NO_ID, 300, 300, 0, 0, 0.4, 1);
            w.objects.status[h] = Obj.GRAVITY;
            w.objects.mods[h] = Obj.MOD_CLUSTER;
            // seed 6 draws 0.527: a wrong min count of 0 would give (int)(0.527 * 3) = 1 fragment
            w.rng.seed(6);
            long bursts = w.events.burstSeq();
            s.shots.delete(h);
            List<Integer> l = live(w);
            Objects o = w.objects;
            int first = l.get(0);
            // intensity = (int)(0.4 * 0.9 / 0.1) = 3; min (int)(0.2f*3*4) = 2, max (int)(0.3f*3*4) = 3
            assertAll(
                () -> assertEquals(2, l.size(), "2 + (int)(rfrac * 1) = 2 fragments"),
                () -> assertEquals(bursts, w.events.burstSeq(), "no burst: the fragments reach clients as CLUSTER shots"),
                () -> assertEquals(20.0f, (float) Math.hypot(o.vx[first], o.vy[first]), 0.01,
                    "speed 20 (max (3>>2) raised to min; the direction between table entries is a chord, <= 0.01 shorter)"),
                () -> assertEquals(Records.ObjColor.WHITE, o.color[first], "white"),
                () -> assertEquals(Obj.SHOT, o.type[first], "fragments are shots"),
                () -> assertEquals(Obj.MOD_CLUSTER, o.mods[first], "marked CLUSTER"),
                () -> assertEquals(Obj.GRAVITY, o.status[first], "without FROMCANNON when the shot had none"),
                // kinetic: mass = 3 * shotMass / (speed / shotSpeed)^2 with speed 20
                () -> assertEquals(0.3 / ((20.0 / 21) * (20.0 / 21)), o.mass[first], 1e-5, "cluster kinetic mass (floats)"),
                () -> assertEquals(2.0, o.life[first], EPS, "life (int)(1 + r*0 + 1) = 2"));
        }
    }
}
