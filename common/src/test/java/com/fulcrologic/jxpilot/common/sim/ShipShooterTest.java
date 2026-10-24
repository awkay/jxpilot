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
package com.fulcrologic.jxpilot.common.sim;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.common.sim.KernelFx.moverWorld;
import static com.fulcrologic.jxpilot.common.sim.KernelFx.ship;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ShipShooter}: C {@code Fire_normal_shots}, {@code Fire_main_shot}, the side and rear gun shots and the
 * ship's part of {@code Fire_general_shot} with {@code Recoil} (shot.c, ship.c).
 */
class ShipShooterTest {
    static final int WIDE = Item.WIDEANGLE.ordinal();
    static final int REAR = Item.REARSHOT.ordinal();
    /** The default triangle plus one left gun, two right guns, one left rear and two right rear guns. */
    static final String GUN_SHIP =
        "(SH: 15,0 -9,8 -9,-8)(MG: 15,0)(EN: -9,0)(LG: 0,3)(RG: 0,-3 0,-1)(LR: -8,3)(RR: -8,-3 -8,-1)";
    /** Three rear guns per side at distinct heights. */
    static final String REAR3_SHIP =
        "(SH: 15,0 -9,8 -9,-8)(MG: 15,0)(EN: -9,0)(LR: -8,5 -8,3 -8,1)(RR: -8,-5 -8,-3 -8,-1)";

    KernelFx.TestWorld w;
    ShipShooter shooter;
    ShipBody p;
    KernelFx.Rec r;

    /** A ship at (200, 300) px heading 0, at rest, frame 100, never fired. */
    void setup() {
        w = moverWorld("", ShipMoverTest.OPEN);
        w.frames = 100;
        shooter = new ShipShooter(w);
        p = ship(200 << 6, 300 << 6, 0, 0, 0);
        r = new KernelFx.Rec();
    }

    @Nested
    @DisplayName("fire: one bullet")
    class Fire {
        @Test
        @DisplayName("costs (long)ED_SHOT = 51 fuel, counts the shot, starts at the given point with ship velocity + shot speed, and recoils")
        void bullet() {
            setup();
            p.vx = 1.5f;
            p.vy = -0.25f;
            p.dir = 16;
            int h = shooter.fire(p, 210.5f, 300.25f, 16, r);
            ShipParams sp = w.params;
            float svx = (float) ((double) 1.5f + Trig.tcos(16) * sp.shotSpeed);
            float svy = (float) ((double) -0.25f + Trig.tsin(16) * sp.shotSpeed);
            assertAll(
                () -> assertEquals(1, h, "the event's handle is returned"),
                () -> assertEquals(10000 - 51.0, p.fuel, "51 fuel"),
                () -> assertEquals(1, p.shots, "one shot counted"),
                () -> assertEquals(210.5f, r.shotX, "from x"),
                () -> assertEquals(300.25f, r.shotY, "and y"),
                () -> assertEquals(16, r.shotDir, "along dir"),
                () -> assertEquals(svx, r.shotVx, "vx = (float)(ship vx + (float)(tcos * shotSpeed)), added in double"),
                () -> assertEquals(svy, r.shotVy, "vy likewise"),
                () -> assertEquals(1.5f - ((svx - 1.5f) * sp.shotMass) / 20f, p.vx, "recoil (shot.v - v) * mass / ship mass"),
                () -> assertEquals(-0.25f - ((svy - -0.25f) * sp.shotMass) / 20f, p.vy, "in y too"),
                () -> assertEquals(false, r.shotOutside, "inside the world"));
        }

        @Test
        @DisplayName("nothing without room in the pool or with less than 51.2 fuel")
        void refused() {
            setup();
            r.room = false;
            int full = shooter.fire(p, 200, 300, 0, r);
            ShipBody poor = ship(200 << 6, 300 << 6, 0, 0, 0);
            poor.fuel = 51;
            KernelFx.Rec rp = new KernelFx.Rec();
            int none = shooter.fire(poor, 200, 300, 0, rp);
            ShipBody just = ship(200 << 6, 300 << 6, 0, 0, 0);
            just.fuel = 52;
            KernelFx.Rec rj = new KernelFx.Rec();
            shooter.fire(just, 200, 300, 0, rj);
            assertAll(
                () -> assertEquals(-1, full, "pool full: -1"),
                () -> assertEquals("shotRoom", r.log(), "and nothing else happens"),
                () -> assertEquals(10000.0, p.fuel, "no fuel is paid"),
                () -> assertEquals(-1, none, "51 < 51.2: -1"),
                () -> assertEquals(0, poor.shots, "no shot counted"),
                () -> assertEquals(51.0, poor.fuel, "no fuel paid"),
                () -> assertEquals(1, rj.shots, "52 is enough"),
                () -> assertEquals(1.0, just.fuel, "and pays 51"));
        }

        @Test
        @DisplayName("the start is wrapped; outside a flat world the object is C's unpositioned one: no velocity, no recoil")
        void outside() {
            setup();
            p.vx = 3;
            int h = shooter.fire(p, -5, 300, 0, r);
            KernelFx.TestWorld wr = moverWorld("edgeWrap: yes", ShipMoverTest.OPEN);
            ShipShooter sw = new ShipShooter(wr);
            ShipBody q = ship(200 << 6, 300 << 6, 0, 0, 0);
            KernelFx.Rec rq = new KernelFx.Rec();
            sw.fire(q, -5, wr.pixelHeight() + 2, 0, rq);
            ShipBody t = ship(200 << 6, 300 << 6, 0, 0, 0);
            KernelFx.Rec rt = new KernelFx.Rec();
            shooter.fire(t, 10, w.pixelHeight(), 0, rt);
            ShipBody u = ship(200 << 6, 300 << 6, 0, 0, 0);
            KernelFx.Rec ru = new KernelFx.Rec();
            shooter.fire(u, w.pixelWidth(), 10, 0, ru);
            ShipBody v = ship(200 << 6, 300 << 6, 0, 0, 0);
            KernelFx.Rec rv = new KernelFx.Rec();
            shooter.fire(v, 10, -1, 0, rv);
            ShipBody z = ship(200 << 6, 300 << 6, 0, 0, 0);
            KernelFx.Rec rz = new KernelFx.Rec();
            shooter.fire(z, 0, 0, 0, rz);
            assertAll(
                () -> assertEquals(-1, h, "-1"),
                () -> assertTrue(r.shotOutside, "the unpositioned object is made"),
                () -> assertEquals(0f, r.shotVx, "with no velocity"),
                () -> assertEquals(3f, p.vx, "no recoil"),
                () -> assertEquals(1, p.shots, "the shot still counts"),
                () -> assertEquals(10000 - 51.0, p.fuel, "and costs"),
                () -> assertEquals(wr.pixelWidth() - 5f, rq.shotX, "on a wrapping map x wraps"),
                () -> assertEquals(2f, rq.shotY, "and y"),
                () -> assertEquals(false, rq.shotOutside, "and the shot is inside"),
                () -> assertTrue(rt.shotOutside, "y = height is outside"),
                () -> assertTrue(ru.shotOutside, "x = width is outside"),
                () -> assertTrue(rv.shotOutside, "y < 0 is outside"),
                () -> assertEquals(false, rz.shotOutside, "(0, 0) is inside"));
        }

        @Test
        @DisplayName("the main gun refuses at the shot limit, with shields or while phased; it fires from the rotated gun")
        void mainGun() {
            setup();
            p.dir = 32;
            shooter.fireMainShot(p, 32, r);
            ShipBody max = ship(200 << 6, 300 << 6, 0, 0, 0);
            max.shots = max.shotMax;
            KernelFx.Rec rm = new KernelFx.Rec();
            shooter.fireMainShot(max, 0, rm);
            ShipBody sh = ship(200 << 6, 300 << 6, 0, 0, 0);
            sh.used = Has.SHIELD;
            KernelFx.Rec rs = new KernelFx.Rec();
            shooter.fireMainShot(sh, 0, rs);
            ShipBody ph = ship(200 << 6, 300 << 6, 0, 0, 0);
            ph.used = Has.PHASING_DEVICE;
            KernelFx.Rec rp = new KernelFx.Rec();
            shooter.fireMainShot(ph, 0, rp);
            ShipBody one = ship(200 << 6, 300 << 6, 0, 0, 0);
            one.shots = one.shotMax - 1;
            KernelFx.Rec ro = new KernelFx.Rec();
            shooter.fireMainShot(one, 0, ro);
            assertAll(
                () -> assertEquals(200 + p.ship.gunX[32], r.shotX, "x: pixel + rotated gun"),
                () -> assertEquals(300 + p.ship.gunY[32], r.shotY, "y: pixel + rotated gun"),
                () -> assertEquals("", rm.log(), "at shotMax: nothing"),
                () -> assertEquals("", rs.log(), "shielded: nothing"),
                () -> assertEquals("", rp.log(), "phased: nothing"),
                () -> assertEquals(1, ro.shots, "one below the limit fires"));
        }

        @Test
        @DisplayName("side and rear guns and the centre shot refuse at the shot limit, with shields or while phased")
        void gunsRefuse() {
            setup();
            p.ship = new ShipGeom(ShipShape.parseOrDefault(GUN_SHIP));
            float[] gx = p.ship.lGunX[0];
            float[] gy = p.ship.lGunY[0];
            int[] used = {0, Has.SHIELD, Has.PHASING_DEVICE};
            int[] shots = {p.shotMax, 0, 0};
            int fired = 0;
            for (int i = 0; i < 3; i++) {
                p.used = used[i];
                p.shots = shots[i];
                shooter.fireGun(p, 0, gx, gy, r);
                shooter.fireShot(p, 0, r);
                fired += r.shots;
            }
            int refused = fired;
            p.used = 0;
            p.shots = p.shotMax - 1;
            shooter.fireGun(p, 7, gx, gy, r);
            float gunX = r.shotX;
            p.shots = p.shotMax - 1;
            shooter.fireShot(p, 9, r);
            assertAll(
                () -> assertEquals(0, refused, "nothing at the limit, shielded or phased"),
                () -> assertEquals(200 + gx[0], gunX, "a gun shot starts at the rotated gun"),
                () -> assertEquals(200f, r.shotX, "a centre shot at the ship's centre"),
                () -> assertEquals(List.of(7, 9), r.shotDirs, "along their directions"));
        }
    }

    @Nested
    @DisplayName("fireNormalShots")
    class Normal {
        @Test
        @DisplayName("fires iff fireRepeatRate frames passed since the last shot; remembers the time")
        void repeat() {
            setup();
            int rate = w.params.fireRepeatRate;
            p.shotTime = 100 - rate + 1;
            shooter.fireNormalShots(p, r);
            int early = r.shots;
            p.shotTime = 100 - rate;
            shooter.fireNormalShots(p, r);
            assertAll(
                () -> assertTrue(rate > 0, "(fixture: a repeat rate)"),
                () -> assertEquals(0, early, "one frame early: nothing"),
                () -> assertEquals(1, r.shots, "exactly the rate: fires"),
                () -> assertEquals(100L, p.shotTime, "and remembers the frame"),
                () -> assertEquals(List.of(0), r.shotDirs, "the main gun along the heading"));
        }

        @Test
        @DisplayName("without side guns each wideangle adds a shot to each side from the main gun, (i+1)*(3-spread) off")
        void wide() {
            setup();
            p.items[WIDE] = 2;
            shooter.fireNormalShots(p, r);
            KernelFx.Rec first = r;
            setup();
            p.items[WIDE] = 2;
            p.mods = Mods.withSpread(0, 2);
            KernelFx.Rec narrow = new KernelFx.Rec();
            shooter.fireNormalShots(p, narrow);
            float g = 200 + p.ship.gunX[0];
            assertAll(
                () -> assertEquals(List.of(0, 3, 125, 6, 122), first.shotDirs, "main, left 3, right -3, left 6, right -6"),
                () -> assertEquals(List.of(g, g, g, g, g), first.shotXs, "all from the main gun"),
                () -> assertEquals(List.of(0, 1, 127, 2, 126), narrow.shotDirs, "spread 2: 1 unit apart"));
        }

        @Test
        @DisplayName("side guns are used in turn (i % guns), rotated with the heading")
        void sideGuns() {
            setup();
            p.ship = new ShipGeom(ShipShape.parseOrDefault(GUN_SHIP));
            p.dir = 32;
            p.items[WIDE] = 3;
            shooter.fireNormalShots(p, r);
            ShipGeom g = p.ship;
            assertAll(
                () -> assertEquals(List.of(32, 35, 29, 38, 26, 41, 23), r.shotDirs, "main, then left/right pairs 3 apart"),
                () -> assertEquals(200 + g.lGunX[0][32], r.shotXs.get(1), "left 1 from left gun 0"),
                () -> assertEquals(300 + g.lGunY[0][32], r.shotYs.get(3), "left 2 from left gun 0 too"),
                () -> assertEquals(200 + g.rGunX[0][32], r.shotXs.get(2), "right 1 from right gun 0"),
                () -> assertEquals(200 + g.rGunX[1][32], r.shotXs.get(4), "right 2 from right gun 1"),
                () -> assertEquals(200 + g.rGunX[0][32], r.shotXs.get(6), "right 3 from right gun 0"),
                () -> assertEquals(300 + g.gunY[32], r.shotYs.get(0), "the main shot from the main gun"));
        }

        @Test
        @DisplayName("without rear guns rear shots fan around the tail by (k * 3) / 2 from the ship's centre")
        void rearCentre() {
            setup();
            p.items[REAR] = 3;
            shooter.fireNormalShots(p, r);
            KernelFx.Rec first = r;
            setup();
            p.items[REAR] = 2;
            KernelFx.Rec two = new KernelFx.Rec();
            shooter.fireNormalShots(p, two);
            assertAll(
                () -> assertEquals(List.of(0, 67, 64, 61), first.shotDirs, "three rear shots: 67, 64, 61"),
                () -> assertEquals(List.of(200f, 200f, 200f), first.shotXs.subList(1, 4), "all from the ship's centre"),
                () -> assertEquals(List.of(0, 65, 63), two.shotDirs, "two rear shots: 65 and 63 (C truncates)"));
        }

        @Test
        @DisplayName("rear guns fire the side shots in C's index order; the middle shot comes from the centre")
        void rearGuns() {
            setup();
            p.ship = new ShipGeom(ShipShape.parseOrDefault(GUN_SHIP));
            ShipGeom g = p.ship;
            p.items[REAR] = 4;
            shooter.fireNormalShots(p, r);
            KernelFx.Rec first = r;
            setup();
            p.ship = g;
            p.items[REAR] = 3;
            KernelFx.Rec three = new KernelFx.Rec();
            shooter.fireNormalShots(p, three);
            assertAll(
                () -> assertEquals(List.of(0, 68, 65, 63, 60), first.shotDirs, "four rear shots: 64 + 9/2, 3/2, -3/2, -9/2"),
                () -> assertEquals(300 + g.rrGunY[1][0], first.shotYs.get(1), "k=3 from right rear gun 1"),
                () -> assertEquals(300 + g.rrGunY[0][0], first.shotYs.get(2), "k=1 from right rear gun 0"),
                () -> assertEquals(300 + g.lrGunY[0][0], first.shotYs.get(3), "k=-1 from left rear gun 0"),
                () -> assertEquals(300 + g.lrGunY[0][0], first.shotYs.get(4), "k=-3 from left rear gun 0"),
                () -> assertEquals(300f, three.shotYs.get(2), "the middle (k=0) shot of three is from the centre"),
                () -> assertEquals(300 + g.rrGunY[0][0], three.shotYs.get(1), "k=2 from right rear gun 0"),
                () -> assertEquals(300 + g.lrGunY[0][0], three.shotYs.get(3), "k=-2 from left rear gun 0"));
        }

        @Test
        @DisplayName("with three guns on each rear side the gun indexes follow C's formulas exactly")
        void rearThree() {
            setup();
            p.ship = new ShipGeom(ShipShape.parseOrDefault(REAR3_SHIP));
            ShipGeom g = p.ship;
            p.items[REAR] = 6;
            shooter.fireNormalShots(p, r);
            KernelFx.Rec six = r;
            setup();
            p.ship = g;
            p.items[REAR] = 4;
            shooter.fireNormalShots(p, r);
            KernelFx.Rec four = r;
            assertAll(
                () -> assertEquals(List.of(300 + g.rrGunY[2][0], 300 + g.rrGunY[1][0], 300 + g.rrGunY[0][0],
                    300 + g.lrGunY[0][0], 300 + g.lrGunY[1][0], 300 + g.lrGunY[2][0]), six.shotYs.subList(1, 7),
                    "six: right 2, 1, 0, left 0, 1, 2"),
                () -> assertEquals(List.of(300 + g.rrGunY[1][0], 300 + g.rrGunY[0][0], 300 + g.lrGunY[0][0],
                    300 + g.lrGunY[1][0]), four.shotYs.subList(1, 5), "four: right 1, 0, left 0, 1"));
        }
    }

    @Nested
    @DisplayName("autofire")
    class Autofire {
        @Test
        @DisplayName("a ship using HAS_SHOT fires its normal shots iff fireRepeatRate > 0; the phase is always reported")
        void autofire() {
            setup();
            p.used = Has.SHOT;
            shooter.autofire(p, r);
            KernelFx.Rec first = r;
            setup();
            KernelFx.Rec idle = new KernelFx.Rec();
            shooter.autofire(p, idle);
            setup();
            w.params.fireRepeatRate = 0;
            p.used = Has.SHOT;
            KernelFx.Rec off = new KernelFx.Rec();
            shooter.autofire(p, off);
            assertAll(
                () -> assertEquals("begin1 shotRoom shot end1", first.log(), "fires inside the phase"),
                () -> assertEquals("begin1 end1", idle.log(), "not firing: nothing"),
                () -> assertEquals("begin1 end1", off.log(), "repeat rate 0: no autofire"));
        }
    }
}
