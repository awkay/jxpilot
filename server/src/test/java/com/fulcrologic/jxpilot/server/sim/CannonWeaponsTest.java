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

import com.fulcrologic.jxpilot.common.sim.MoveState;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.CannonItems;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.fulcrologic.jxpilot.server.sim.CannonsTest.CX;
import static com.fulcrologic.jxpilot.server.sim.CannonsTest.CY;
import static com.fulcrologic.jxpilot.common.sim.MoveState.CRASH_CANNON;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CannonWeapons (cannon.c items, weapons and defenses; update.c cannon timers)")
class CannonWeaponsTest {
    Simulation s;
    World w;
    CannonWeapons cw;
    CannonItems ci;

    /** The CannonsTest map: one 'r' cannon (index 0, facing up) at pixel (717.5, 676.655), 60 fps, no gravity. */
    void setup(String extra) {
        s = CannonsTest.sim(FRAME + extra, "____");
        w = s.world;
        cw = s.cannonWeapons;
        ci = w.cannonItems;
    }

    static int it(Item i) {
        return i.ordinal();
    }

    /** The first seed whose RNG stream satisfies {@code p}. */
    static int seedWhere(Predicate<Rng> p) {
        for (int seed = 1; ; seed++) {
            if (p.test(new Rng(seed))) {
                return seed;
            }
        }
    }

    /** An Rng that has made {@code n} draws from {@code seed}. */
    static Rng after(int seed, int n) {
        Rng r = new Rng(seed);
        for (int i = 0; i < n; i++) {
            r.nextInt();
        }
        return r;
    }

    // ================================================================ weapon choice

    @Nested
    @DisplayName("selectWeapon (C Cannon_select_weapon)")
    class SelectWeapon {
        int seed;

        int select(Consumer<CannonItems> prep, Predicate<Rng> stream) {
            setup("");
            prep.accept(ci);
            seed = seedWhere(stream);
            w.rng.seed(seed);
            return cw.selectWeapon(0);
        }

        boolean draws(int n) {
            return after(seed, n).nextInt() == w.rng.nextInt();
        }

        @Test
        @DisplayName("each weapon is taken with its chance, else the next is tried; shots without weapons")
        void chances() {
            List<String> got = new ArrayList<>();
            got.add("none " + select(c -> { }, r -> true) + " " + draws(0));
            got.add("mine " + select(c -> c.set(0, it(Item.MINE), 1), r -> r.nextFloat() < 0.5f) + " " + draws(1));
            got.add("noMine " + select(c -> c.set(0, it(Item.MINE), 1), r -> r.nextFloat() >= 0.5f) + " " + draws(1));
            got.add("missile " + select(c -> c.set(0, it(Item.MISSILE), 1), r -> r.nextFloat() < 0.5f));
            got.add("noMissile " + select(c -> c.set(0, it(Item.MISSILE), 1), r -> r.nextFloat() >= 0.5f));
            got.add("laser " + select(c -> c.set(0, it(Item.LASER), 3), r -> r.nextFloat() >= 0.25f));
            got.add("noLaser " + select(c -> c.set(0, it(Item.LASER), 3), r -> r.nextFloat() < 0.25f));
            got.add("ecm " + select(c -> c.set(0, it(Item.ECM), 1), r -> r.nextFloat() < 0.333f));
            got.add("noEcm " + select(c -> c.set(0, it(Item.ECM), 1), r -> r.nextFloat() >= 0.333f));
            got.add("tractor " + select(c -> c.set(0, it(Item.TRACTOR_BEAM), 1), r -> r.nextFloat() < 0.5f));
            got.add("noTractor " + select(c -> c.set(0, it(Item.TRACTOR_BEAM), 1), r -> r.nextFloat() >= 0.5f));
            got.add("trans " + select(c -> c.set(0, it(Item.TRANSPORTER), 1), r -> r.nextFloat() < 0.333f));
            got.add("noTrans " + select(c -> c.set(0, it(Item.TRANSPORTER), 1), r -> r.nextFloat() >= 0.333f));
            assertEquals(List.of("none 0 true", "mine 1 true", "noMine 0 true", "missile 2", "noMissile 0",
                "laser 3", "noLaser 0", "ecm 4", "noEcm 0", "tractor 5", "noTractor 0", "trans 6", "noTrans 0"), got,
                "mine 1/2, missile 1/2, laser (int)(r * (n+1)) != 0, ECM 1/3, tractor 1/2, transporter 1/3");
        }

        @Test
        @DisplayName("gas jets need fuel and (int)(r * (afterburners or 15 with emergency thrust, + 3)) > 2")
        void gasJet() {
            Consumer<CannonItems> ab = c -> {
                c.set(0, it(Item.AFTERBURNER), 2);
                c.set(0, it(Item.FUEL), 1);
            };
            Consumer<CannonItems> et = c -> {
                c.set(0, it(Item.EMERGENCY_THRUST), 1);
                c.set(0, it(Item.FUEL), 1);
            };
            int abYes = select(ab, r -> (int) ((double) r.nextFloat() * 5) == 3);
            int abNo = select(ab, r -> (int) ((double) r.nextFloat() * 5) == 2);
            int etYes = select(et, r -> (int) ((double) r.nextFloat() * 18) == 3);
            int etNo = select(et, r -> (int) ((double) r.nextFloat() * 18) == 2);
            int noFuel = select(c -> c.set(0, it(Item.AFTERBURNER), 2), r -> true);
            boolean noFuelDraws = draws(0);
            assertAll(
                () -> assertEquals(CannonWeapons.CW_GASJET, abYes, "2 afterburners: (int)(r * 5) = 3 > 2"),
                () -> assertEquals(CannonWeapons.CW_SHOT, abNo, "2 afterburners: (int)(r * 5) = 2"),
                () -> assertEquals(CannonWeapons.CW_GASJET, etYes, "emergency thrust: (int)(r * 18) = 3 > 2"),
                () -> assertEquals(CannonWeapons.CW_SHOT, etNo, "emergency thrust: (int)(r * 18) = 2"),
                () -> assertEquals(CannonWeapons.CW_SHOT, noFuel, "no fuel: no gas jet"),
                () -> assertTrue(noFuelDraws, "no fuel: no draw"));
        }

        @Test
        @DisplayName("the weapons are tried in order mine, missile, laser, ECM, tractor, transporter, gas jet")
        void order() {
            Consumer<CannonItems> all = c -> {
                for (Item i : new Item[] {Item.MINE, Item.MISSILE, Item.LASER, Item.ECM, Item.TRACTOR_BEAM,
                    Item.TRANSPORTER, Item.AFTERBURNER, Item.FUEL}) {
                    c.set(0, it(i), 1);
                }
            };
            int missile = select(all, r -> r.nextFloat() >= 0.5f && r.nextFloat() < 0.5f);
            int laser = select(all, r -> r.nextFloat() >= 0.5f && r.nextFloat() >= 0.5f && r.nextFloat() >= 0.5f);
            int gas = select(all, r -> r.nextFloat() >= 0.5f && r.nextFloat() >= 0.5f && r.nextFloat() < 0.5f
                && r.nextFloat() >= 0.333f && r.nextFloat() >= 0.5f && r.nextFloat() >= 0.333f
                && (int) ((double) r.nextFloat() * 4) > 2);
            boolean seven = draws(7);
            assertAll(
                () -> assertEquals(CannonWeapons.CW_MISSILE, missile, "mine missed, missile taken"),
                () -> assertEquals(CannonWeapons.CW_LASER, laser, "mine and missile missed, laser taken"),
                () -> assertEquals(CannonWeapons.CW_GASJET, gas, "all others missed, gas jet taken"),
                () -> assertTrue(seven, "one draw per weapon tried"));
        }
    }

    @Nested
    @DisplayName("aimRange, visualRange, hidden (C Cannon_aim)")
    class AimRange {
        String range(int weapon) {
            cw.aimRange(0, weapon);
            return cw.aimSpeed + "/" + cw.aimRange;
        }

        @Test
        @DisplayName("speed/range per weapon (shotSpeed 10, CANNON_SHOT_LIFE_MAX 32)")
        void perWeapon() {
            setup("shotSpeed: 10\ncannonSmartness: 2\n");
            ci.set(0, it(Item.TRACTOR_BEAM), 2);
            String shot = range(CannonWeapons.CW_SHOT);
            String mine = range(CannonWeapons.CW_MINE);
            String laser = range(CannonWeapons.CW_LASER);
            String tractor = range(CannonWeapons.CW_TRACTORBEAM);
            String gas = range(CannonWeapons.CW_GASJET);
            w.params.cannonSmartness = 5;
            w.params.shotSpeed = 21;
            String mine5 = range(CannonWeapons.CW_MINE);
            w.params.cannonSmartness = 2;
            w.params.shotSpeed = 10;
            ci.set(0, it(Item.EMERGENCY_THRUST), 1);
            String bigGas = range(CannonWeapons.CW_GASJET);
            assertAll(
                () -> assertEquals("10/320", shot, "shots: shotSpeed, 32 * shotSpeed"),
                () -> assertEquals("5/160", mine, "mines: (int)(10 * 0.5 + 0.2), (int)(320 * 0.5 + 0.2)"),
                () -> assertEquals("90/405", laser, "lasers: PULSE_SPEED 90, (int)(PULSE_LIFE(1) = 4.5 + 1/4 (int) * 90)"),
                () -> assertEquals("10/300", tractor, "tractor: its range 200 + 2 * 50"),
                () -> assertEquals("10/320", gas, "gas jet: shot speed and range"),
                () -> assertEquals("11/336", mine5, "mines, shotSpeed 21, smartness 5: (int)(10.5 + 0.5), (int)(336 + 0.5)"),
                () -> assertEquals("20/640", bigGas, "gas jet with emergency thrust: doubled"));
        }

        @Test
        @DisplayName("ECM: smarter cannons wait for a random range between 400/smartness and 400")
        void ecm() {
            setup("cannonSmartness: 1\n");
            w.rng.seed(4);
            String one = range(CannonWeapons.CW_ECM);
            int next = w.rng.nextInt();
            int seed = seedWhere(r -> r.nextFloat() >= 0.5f);
            setup("cannonSmartness: 2\n");
            w.rng.seed(seed);
            float r2 = new Rng(seed).nextFloat();
            String two = range(CannonWeapons.CW_ECM);
            setup("cannonSmartness: 3\n");
            w.rng.seed(seed);
            String three = range(CannonWeapons.CW_ECM);
            int third = (int) (400.0 / 3 + (int) ((double) r2 * (int) (400 - 400.0 / 3)));
            assertAll(
                () -> assertEquals("21/400", one, "smartness 1: ECM_DISTANCE 400"),
                () -> assertEquals(new Rng(4).nextInt(), next, "smartness 1 draws nothing"),
                () -> assertEquals("21/" + (200 + (int) ((double) r2 * 200)), two, "smartness 2: 200 + (int)(r * 200)"),
                () -> assertEquals("21/" + third, three, "smartness 3: (int)(133.3 + (int)(r * 266))"));
        }

        @Test
        @DisplayName("transporter: smartness 3 always aims at 200 px; lower ones by chance (int)(r*(s+1)^2) != 0")
        void transporter() {
            setup("shotSpeed: 10\ncannonSmartness: 3\n");
            w.rng.seed(4);
            String three = range(CannonWeapons.CW_TRANSPORTER);
            int next = w.rng.nextInt();
            setup("shotSpeed: 10\ncannonSmartness: 1\n");
            w.rng.seed(seedWhere(r -> (int) ((double) r.nextFloat() * 4) != 0));
            String lucky = range(CannonWeapons.CW_TRANSPORTER);
            w.rng.seed(seedWhere(r -> (int) ((double) r.nextFloat() * 4) == 0));
            String unlucky = range(CannonWeapons.CW_TRANSPORTER);
            setup("shotSpeed: 10\ncannonSmartness: 2\n");
            w.rng.seed(seedWhere(r -> (int) ((double) r.nextFloat() * 9) == 0));
            String two = range(CannonWeapons.CW_TRANSPORTER);
            setup("shotSpeed: 10\ncannonSmartness: 0\n");
            String dumb = range(CannonWeapons.CW_TRANSPORTER);
            assertAll(
                () -> assertEquals("10/200", three, "smartness 3: TRANSPORTER_DISTANCE"),
                () -> assertEquals(new Rng(4).nextInt(), next, "smartness 3 draws nothing"),
                () -> assertEquals("10/200", lucky, "smartness 1, (int)(r * 4) != 0: TRANSPORTER_DISTANCE"),
                () -> assertEquals("10/320", unlucky, "smartness 1, (int)(r * 4) == 0: the shot range"),
                () -> assertEquals("10/320", two, "smartness 2, (int)(r * 9) == 0: the shot range"),
                () -> assertEquals("10/320", dumb, "smartness 0: (int)(r * 1) is always 0: the shot range"));
        }

        @Test
        @DisplayName("visual range: CANNON_DISTANCE 500 + 2 blocks per sensor")
        void visual() {
            setup("");
            int none = cw.visualRange(0);
            ci.set(0, it(Item.SENSOR), 2);
            assertAll(
                () -> assertEquals(500, none, "no sensor: 500 px"),
                () -> assertEquals(640, cw.visualRange(0), "two sensors: 500 + 2 * 2 * 35"));
        }

        @Test
        @DisplayName("hidden: a cloaked, not forced visible ship hides iff (int)(r*(cloaks+1)) > (int)(r*(sensors+1))")
        void hidden() {
            setup("");
            Player p = ready(s, "P", 0, CX, CY + 100);
            w.rng.seed(4);
            boolean plain = cw.hidden(0, p);
            p.used |= Has.CLOAKING_DEVICE;
            p.items[it(Item.CLOAK)] = 1;
            p.forceVisible = 3;
            boolean forced = cw.hidden(0, p);
            int next = w.rng.nextInt();
            p.forceVisible = 0;
            w.rng.seed(seedWhere(r -> r.nextFloat() >= 0.5f));
            boolean hides = cw.hidden(0, p);
            w.rng.seed(seedWhere(r -> r.nextFloat() < 0.5f));
            boolean shows = cw.hidden(0, p);
            ci.set(0, it(Item.SENSOR), 3);
            w.rng.seed(seedWhere(r -> (int) ((double) r.nextFloat() * 2) == 1 && (int) ((double) r.nextFloat() * 4) == 1));
            boolean sensed = cw.hidden(0, p);
            w.rng.seed(seedWhere(r -> (int) ((double) r.nextFloat() * 2) == 1 && (int) ((double) r.nextFloat() * 4) == 0));
            boolean notSensed = cw.hidden(0, p);
            assertAll(
                () -> assertFalse(plain, "an uncloaked ship is not hidden"),
                () -> assertFalse(forced, "a forced visible ship is not hidden"),
                () -> assertEquals(new Rng(4).nextInt(), next, "neither draws a number"),
                () -> assertTrue(hides, "1 cloak, no sensor: (int)(r*2) = 1 > 0 hides"),
                () -> assertFalse(shows, "1 cloak, no sensor: (int)(r*2) = 0 does not"),
                () -> assertFalse(sensed, "3 sensors: 1 > 1 does not hide"),
                () -> assertTrue(notSensed, "3 sensors: 1 > 0 hides"));
        }
    }

    @Nested
    @DisplayName("Cannons.aim and checkFire with items")
    class AimWithItems {
        @Test
        @DisplayName("sensors extend the visual range; hidden ships and (smartness 3) phased ships are skipped")
        void candidates() {
            setup("cannonSmartness: 0\n");
            Player p = ready(s, "P", 0, CX, CY + 600);
            boolean far = s.cannons.aim(0);
            ci.set(0, it(Item.SENSOR), 2);
            boolean sensed = s.cannons.aim(0);
            p.initPixels(CX, CY + 100);
            p.used |= Has.CLOAKING_DEVICE;
            p.items[it(Item.CLOAK)] = 1;
            ci.set(0, it(Item.SENSOR), 0);
            w.rng.seed(seedWhere(r -> r.nextFloat() >= 0.5f));
            boolean cloaked = s.cannons.aim(0);
            p.used &= ~Has.CLOAKING_DEVICE;
            p.used |= Has.PHASING_DEVICE;
            boolean phased0 = s.cannons.aim(0);
            setup("cannonSmartness: 3\n");
            Player q = ready(s, "Q", 0, CX, CY + 100);
            q.used |= Has.PHASING_DEVICE;
            boolean phased3 = s.cannons.aim(0);
            q.used &= ~Has.PHASING_DEVICE;
            boolean unphased3 = s.cannons.aim(0);
            assertAll(
                () -> assertFalse(far, "600 px is beyond 500 without sensors"),
                () -> assertTrue(sensed, "two sensors see 640 px"),
                () -> assertFalse(cloaked, "a hidden cloaked ship is not targeted"),
                () -> assertTrue(phased0, "smartness 0 targets phased ships"),
                () -> assertFalse(phased3, "smartness 3 ignores phased ships"),
                () -> assertTrue(unphased3, "smartness 3 targets unphased ships"));
        }

        @Test
        @DisplayName("checkFire: shots with wideangles go through the item weapons (3 shots), plain shots one")
        void dispatch() {
            setup("cannonSmartness: 0\ncannonFlak: no\n");
            ready(s, "P", 0, CX, CY + 200);
            ci.set(0, it(Item.WIDEANGLE), 1);
            s.cannons.checkFire(0);
            List<Integer> dirs = new ArrayList<>();
            Objects o = w.objects;
            for (int k = 0; k < o.count(); k++) {
                dirs.add(o.missileDir[o.active[k]]);
            }
            java.util.Collections.sort(dirs);
            assertEquals(List.of(28, 32, 36), dirs, "1 + 2 wideangles fanned by 4 - smartness 0 = 4");
        }
    }

    @Nested
    @DisplayName("Cannons.update and checkFire with items")
    class UpdateWithItems {
        @Test
        @DisplayName("a cannon in danger shields itself, then (not free to fire) picks up an item, then its timers run")
        void defendsPicksTicks() {
            setup("cannonsUseItems: yes\ncannonSmartness: 1\n");
            ci.clear(0);
            java.util.Arrays.fill(w.items.cannonProb, 1f);
            java.util.Arrays.fill(w.items.limit, 99);
            w.items.cannonItemProbMult = 3600;
            SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, CX, CY + 100, 0, 0, 1, 100);
            ci.set(0, it(Item.EMERGENCY_SHIELD), 1);
            w.rng.seed(seedWhere(r -> r.nextFloat() < 0.65
                && (int) ((double) r.nextFloat() * Item.COUNT) == it(Item.MINE)));
            s.cannons.update();
            assertAll(
                () -> assertEquals(Has.EMERGENCY_SHIELD, ci.used[0], "the cannon shields itself"),
                () -> assertEquals(240 - 1, ci.emergencyShieldLeft[0], "and its shield timer already ran a frame"),
                () -> assertEquals(1, ci.item(0, Item.MINE), "a shielded cannon does not fire but picks up a mine"));
        }

        @Test
        @DisplayName("checkFire fires the selected item weapon at the aimed target")
        void itemWeapon() {
            setup("cannonSmartness: 0\n");
            ready(s, "P", 0, CX, CY + 150);
            ci.set(0, it(Item.MINE), 1);
            w.rng.seed(seedWhere(r -> r.nextFloat() < 0.5f));
            s.cannons.checkFire(0);
            assertAll(
                () -> assertEquals(Obj.MINE, w.objects.type[w.objects.active[0]], "a mine is placed or thrown"),
                () -> assertEquals(0, ci.item(0, Item.MINE), "the mine is used"));
        }
    }

    // ================================================================ firing

    @Nested
    @DisplayName("fire (C Cannon_fire)")
    class Fire {
        Player target;

        void arm(String extra) {
            setup(extra);
            target = ready(s, "T", 0, CX, CY + 150);
        }

        List<Integer> types() {
            List<Integer> l = new ArrayList<>();
            Objects o = w.objects;
            for (int k = 0; k < o.count(); k++) {
                l.add(o.type[o.active[k]]);
            }
            return l;
        }

        @Test
        @DisplayName("shots: 1 + 2 * wideangles fanned by 4 - smartness, rear shots behind, flak makes clusters")
        void shots() {
            arm("cannonSmartness: 1\ncannonFlak: yes\n");
            ci.set(0, it(Item.WIDEANGLE), 1);
            ci.set(0, it(Item.REARSHOT), 2);
            cw.fire(0, CannonWeapons.CW_SHOT, target, 32);
            List<Integer> dirs = new ArrayList<>();
            Objects o = w.objects;
            boolean allFlak = true;
            for (int k = 0; k < o.count(); k++) {
                int h = o.active[k];
                dirs.add(o.missileDir[h]);
                allFlak &= o.mods[h] == Obj.MOD_CLUSTER && o.type[h] == Obj.CANNON_SHOT;
            }
            boolean flak = allFlak;
            arm("cannonSmartness: 1\ncannonFlak: no\n");
            ci.set(0, it(Item.REARSHOT), 1);
            cw.fire(0, CannonWeapons.CW_SHOT, target, 127);
            List<Integer> dirs2 = new ArrayList<>();
            for (int k = 0; k < w.objects.count(); k++) {
                dirs2.add(w.objects.missileDir[w.objects.active[k]]);
            }
            assertAll(
                () -> assertEquals(List.of(29, 32, 35, 94, 97), dirs,
                    "wide: 32 + 3 * (-1, 0, 1); rear: (int)(32 + 64 + 3 * (-0.5, 0.5))"),
                () -> assertTrue(flak, "cannonFlak: cluster cannon shots"),
                () -> assertEquals(List.of(127, 63), dirs2, "directions wrap: 127 and (127 + 64) mod 128"));
        }

        @Test
        @DisplayName("mines: placed at the cannon or thrown; random cluster and modifiers; one mine used")
        void mines() {
            arm("cannonSmartness: 1\n");
            w.items.allowClusters = true;
            w.items.allowModifiers = true;
            ci.set(0, it(Item.MINE), 2);
            int seed = seedWhere(r -> r.nextFloat() < 0.25f && r.nextFloat() >= 0.2f && r.nextFloat() >= 0.5f
                && r.nextFloat() >= 0.5f && r.nextFloat() < 0.5f);
            Rng ref = new Rng(seed);
            ref.nextFloat();
            ref.nextFloat();
            int power = (int) ((double) ref.nextFloat() * 4);
            int vel = (int) ((double) ref.nextFloat() * 4);
            w.rng.seed(seed);
            cw.fire(0, CannonWeapons.CW_MINE, target, 32);
            Objects o = w.objects;
            int h = o.active[0];
            int placedMods = o.mods[h];
            int placedStatus = o.status[h];
            float placedVy = o.vy[h];
            int placedCount = o.count();
            int left = ci.item(0, Item.MINE);
            arm("cannonSmartness: 1\n");
            w.items.allowClusters = true;
            w.items.allowModifiers = true;
            ci.set(0, it(Item.MINE), 2);
            int seed2 = seedWhere(r -> r.nextFloat() >= 0.25f && r.nextFloat() < 0.2f && r.nextFloat() >= 0
                && r.nextFloat() >= 0 && r.nextFloat() >= 0.5f && r.nextFloat() >= 2 / 3f && r.nextFloat() >= 0.5f);
            Rng ref2 = after(seed2, 5);
            int mini = (int) ((double) ref2.nextFloat() * 3) + 1;
            int spread = (int) ((double) ref2.nextFloat() * 4);
            w.rng.seed(seed2);
            cw.fire(0, CannonWeapons.CW_MINE, target, 32);
            int h2 = w.objects.active[0];
            int thrownMods = w.objects.mods[h2];
            int thrownStatus = w.objects.status[h2];
            int thrownCount = w.objects.count();
            int thrownSpread = Mods.spread(thrownMods);
            assertAll(
                () -> assertEquals(1, placedCount, "a placed mine without minis is one mine"),
                () -> assertEquals(Obj.MINE, o.type[h], "a mine"),
                () -> assertTrue(Mods.isCluster(placedMods), "rfrac < 0.25: cluster"),
                () -> assertTrue((placedMods & Obj.MOD_IMPLOSION) != 0, "rfrac >= 0.2: implosion"),
                () -> assertTrue(power >= 2 && vel >= 2, "high draws give power and velocity of 2 or 3"),
                () -> assertEquals(power, Mods.power(placedMods), "power (int)(rfrac * 4)"),
                () -> assertEquals(vel, Mods.velocity(placedMods), "velocity (int)(rfrac * 4) (kept for clusters)"),
                () -> assertEquals(0, placedStatus & Obj.GRAVITY, "a placed mine has no gravity"),
                () -> assertTrue((placedStatus & Obj.FROMCANNON) != 0, "it is from a cannon"),
                () -> assertEquals(0f, placedVy, "a placed mine does not move"),
                () -> assertEquals(1, left, "one mine is used"),
                () -> assertEquals(mini + 1, thrownCount, "a thrown mine with mini m is m + 1 mines"),
                () -> assertEquals(3, mini, "a high draw gives mini 3"),
                () -> assertEquals(mini, Mods.mini(thrownMods), "mini (int)(rfrac * 3) + 1"),
                () -> assertTrue(spread >= 2, "a high draw gives spread 2 or 3"),
                () -> assertEquals(spread, thrownSpread, "spread (int)(rfrac * 4)"),
                () -> assertFalse(Mods.isCluster(thrownMods), "rfrac >= 0.25: no cluster"),
                () -> assertEquals(0, thrownMods & Obj.MOD_IMPLOSION, "rfrac < 0.2: no implosion"),
                () -> assertTrue((thrownStatus & Obj.GRAVITY) != 0, "a thrown mine feels gravity"),
                () -> assertEquals(1, ci.item(0, Item.MINE), "one mine is used"));
        }

        @Test
        @DisplayName("mines without the rules draw only the place-or-throw number; thrown at speed*0.5+0.1*smartness")
        void plainMine() {
            arm("cannonSmartness: 5\nshotSpeed: 21\n");
            ci.set(0, it(Item.MINE), 1);
            int seed = seedWhere(r -> r.nextFloat() >= 0.5f);
            w.rng.seed(seed);
            cw.fire(0, CannonWeapons.CW_MINE, target, 16);
            int h = w.objects.active[0];
            assertAll(
                () -> assertEquals((float) (Trig.tcos(16) * 11 * 1.3), w.objects.vx[h], 1e-5f, "thrown along dir 16, x"),
                () -> assertEquals(1, w.objects.count(), "one mine"),
                () -> assertEquals(0, w.objects.mods[h], "no modifiers"),
                () -> assertEquals((float) (Trig.tsin(16) * 11 * 1.3), w.objects.vy[h], 1e-5f,
                    "thrown at (int)(21 * 0.5 + 0.1 * 5) = 11 px/frame (mines move 1.3 times that)"));
        }

        @Test
        @DisplayName("missiles: smarter cannons use smart missiles, heat-seekers at thrusting targets, else torpedoes")
        void missiles() {
            List<String> got = new ArrayList<>();
            for (int smart = 0; smart <= 3; smart++) {
                for (boolean thrusting : new boolean[] {false, true}) {
                    arm("cannonSmartness: " + smart + "\n");
                    ci.set(0, it(Item.MISSILE), 3);
                    if (thrusting) {
                        target.status |= Obj.THRUSTING;
                    }
                    int sm = smart;
                    int pick = Math.min(smart, 2);
                    w.rng.seed(seedWhere(r -> (int) ((double) r.nextFloat() * (1 + sm)) == pick));
                    cw.fire(0, CannonWeapons.CW_MISSILE, target, 32);
                    got.add(smart + (thrusting ? "T " : "  ") + types() + " " + ci.item(0, Item.MISSILE));
                }
            }
            arm("cannonSmartness: 3\n");
            w.items.allowSmartMissiles = false;
            target.status |= Obj.THRUSTING;
            ci.set(0, it(Item.MISSILE), 1);
            w.rng.seed(seedWhere(r -> (int) ((double) r.nextFloat() * 4) >= 2));
            cw.fire(0, CannonWeapons.CW_MISSILE, target, 32);
            got.add("noSmart " + types());
            int sm = Obj.SMART_SHOT;
            int ht = Obj.HEAT_SHOT;
            int tp = Obj.TORPEDO;
            assertEquals(List.of("0  [" + tp + "] 2", "0T [" + tp + "] 2", "1  [" + tp + "] 2", "1T [" + ht + "] 2",
                "2  [" + sm + "] 2", "2T [" + sm + "] 2", "3  [" + sm + "] 2", "3T [" + sm + "] 2",
                "noSmart [" + ht + "]"), got,
                "pick (int)(r * (1 + smartness)): 0 torpedo, 1 heat at a thrusting target, >= 2 smart; one used");
        }

        @Test
        @DisplayName("missile modifiers: cluster 1/3, implosion 3/4, power and velocity; never minis")
        void missileMods() {
            arm("cannonSmartness: 0\n");
            w.items.allowClusters = true;
            w.items.allowModifiers = true;
            ci.set(0, it(Item.MISSILE), 1);
            int seed = seedWhere(r -> r.nextFloat() < 0.333f && r.nextFloat() >= 0.25f && r.nextFloat() >= 0.5f
                && r.nextFloat() >= 0.5f);
            Rng ref = after(seed, 2);
            int power = (int) ((double) ref.nextFloat() * 4);
            int vel = (int) ((double) ref.nextFloat() * 4);
            w.rng.seed(seed);
            cw.fire(0, CannonWeapons.CW_MISSILE, target, 32);
            int m = w.objects.mods[w.objects.active[0]];
            assertAll(
                () -> assertTrue(Mods.isCluster(m), "rfrac < 0.333: cluster"),
                () -> assertTrue((m & Obj.MOD_IMPLOSION) != 0, "rfrac >= 0.25: implosion"),
                () -> assertEquals(power, Mods.power(m), "power (int)(rfrac * 4)"),
                () -> assertEquals(vel, Mods.velocity(m), "velocity (int)(rfrac * 4)"),
                () -> assertEquals(0, Mods.mini(m), "no minis"),
                () -> assertEquals(1, w.objects.count(), "one missile"));
        }

        @Test
        @DisplayName("lasers: a pulse along dir, sometimes with a laser modifier ((r * (8 - smartness)) >= 1)")
        void laser() {
            arm("cannonSmartness: 1\n");
            w.items.allowLaserModifiers = true;
            ci.set(0, it(Item.LASER), 2);
            int seed = seedWhere(r -> r.nextFloat() * 7 >= 1 && (int) ((double) r.nextFloat() * 3) == 2);
            w.rng.seed(seed);
            cw.fire(0, CannonWeapons.CW_LASER, target, 40);
            var pu = w.pulses;
            int mods = pu.mods[0];
            int dir = pu.dir[0];
            int owner = pu.owner[0];
            int lasers = ci.item(0, Item.LASER);
            arm("cannonSmartness: 1\n");
            w.items.allowLaserModifiers = true;
            w.rng.seed(seedWhere(r -> {
                float f = r.nextFloat();
                return f * 7 < 1 && f * 9 >= 1 && (int) ((double) r.nextFloat() * 3) >= 1;
            }));
            cw.fire(0, CannonWeapons.CW_LASER, target, 40);
            int plain = w.pulses.mods[0];
            assertAll(
                () -> assertEquals(40, dir, "the pulse goes along the aimed dir"),
                () -> assertEquals(Obj.NO_ID, owner, "the pulse has no owner"),
                () -> assertEquals(2, Mods.laser(mods), "laser modifier (int)(rfrac * 3) = 2"),
                () -> assertEquals(0, plain, "r * (8 - 1) < 1 (although r * 9 >= 1): no laser modifier"),
                () -> assertEquals(2, lasers, "lasers are not used up"));
        }

        @Test
        @DisplayName("ECM: a blast at the cannon, one ECM used")
        void ecm() {
            arm("");
            ci.set(0, it(Item.ECM), 2);
            cw.fire(0, CannonWeapons.CW_ECM, target, 40);
            assertAll(
                () -> assertEquals(1, w.ecms.count(), "one ECM blast"),
                () -> assertEquals(CX, (int) w.ecms.x[0], "at the cannon's x"),
                () -> assertEquals(Obj.NO_ID, w.ecms.owner[0], "without an owner"),
                () -> assertEquals(1, ci.item(0, Item.ECM), "one ECM used"));
        }

        @Test
        @DisplayName("tractor: a pressor when r * (s + 1) >= 1, held 11 + (int)(r * (3s + 1)) frames on the target")
        void tractor() {
            arm("cannonSmartness: 2\n");
            int seed = seedWhere(r -> r.nextFloat() * 3 >= 1);
            Rng ref = after(seed, 1);
            int count = 11 + (int) ((double) ref.nextFloat() * 7);
            w.rng.seed(seed);
            cw.fire(0, CannonWeapons.CW_TRACTORBEAM, target, 40);
            boolean pressor = ci.tractorIsPressor[0];
            int frames = ci.tractorCount[0];
            w.rng.seed(seedWhere(r -> r.nextFloat() * 3 < 1));
            cw.fire(0, CannonWeapons.CW_TRACTORBEAM, target, 40);
            assertAll(
                () -> assertTrue(pressor, "r * 3 >= 1: pressor"),
                () -> assertEquals(count, frames, "11 + (int)(r * 7) frames"),
                () -> assertEquals(target.id, ci.tractorTarget[0], "the target is held"),
                () -> assertFalse(ci.tractorIsPressor[0], "r * 3 < 1: tractor"));
        }

        @Test
        @DisplayName("transporter: one used; a target closer than 200 px is robbed and the loot added to the cannon")
        void transporter() {
            arm("");
            java.util.Arrays.fill(target.items, 0);
            target.items[it(Item.MINE)] = 1;
            target.fuel = 0;
            target.tankFuel[0] = 0;
            ci.set(0, it(Item.TRANSPORTER), 2);
            cw.fire(0, CannonWeapons.CW_TRANSPORTER, target, 40);
            int loot = ci.item(0, Item.MINE);
            int victim = target.items[it(Item.MINE)];
            target.items[it(Item.MINE)] = 1;
            target.initPixels(CX, CY + 200);
            cw.fire(0, CannonWeapons.CW_TRANSPORTER, target, 40);
            arm("");
            java.util.Arrays.fill(target.items, 0);
            target.fuel = 0;
            target.tankFuel[0] = 0;
            ci.set(0, it(Item.TRANSPORTER), 1);
            ci.set(0, it(Item.MINE), 0);
            cw.fire(0, CannonWeapons.CW_TRANSPORTER, target, 40);
            int nothing = ci.item(0, Item.MINE);
            assertAll(
                () -> assertEquals(1, loot, "the cannon gets the mine"),
                () -> assertEquals(0, victim, "the target loses it"),
                () -> assertEquals(0, nothing, "nothing to steal: nothing is added"),
                () -> assertEquals(0, ci.item(0, Item.TRANSPORTER), "each try uses a transporter"));
        }

        @Test
        @DisplayName("transporter out of range: used up, nothing stolen")
        void transporterFar() {
            arm("");
            java.util.Arrays.fill(target.items, 0);
            target.items[it(Item.MINE)] = 1;
            target.fuel = 0;
            target.tankFuel[0] = 0;
            target.initPixels(CX, CY + 200);
            ci.set(0, it(Item.TRANSPORTER), 1);
            cw.fire(0, CannonWeapons.CW_TRANSPORTER, target, 40);
            assertAll(
                () -> assertEquals(1, target.items[it(Item.MINE)], "200 px is not closer than 200: no theft"),
                () -> assertEquals(0, ci.item(0, Item.TRANSPORTER), "the transporter is used"));
        }

        @Test
        @DisplayName("gas jet: 300-700 sparks with an emergency thrust (one used), else 150-350; one fuel pack used")
        void gasJet() {
            arm("cannonSmartness: 1\nshotSpeed: 10\n");
            ci.set(0, it(Item.EMERGENCY_THRUST), 1);
            ci.set(0, it(Item.FUEL), 3);
            int seed = seedWhere(r -> r.nextFloat() * 2 >= 1);
            int big = 300 + (int) ((double) after(seed, 1).nextFloat() * 400);
            w.rng.seed(seed);
            cw.fire(0, CannonWeapons.CW_GASJET, target, 40);
            DebrisBurst b1 = SimFixture.copy(SimFixture.lastBurst(w));
            int n1 = s.debris.lastCount;
            int et = ci.item(0, Item.EMERGENCY_THRUST);
            int fuel1 = ci.item(0, Item.FUEL);
            w.objects.clear();
            ci.set(0, it(Item.EMERGENCY_THRUST), 1);
            int seed2 = seedWhere(r -> r.nextFloat() * 2 < 1);
            int small = 150 + (int) ((double) after(seed2, 1).nextFloat() * 200);
            w.rng.seed(seed2);
            cw.fire(0, CannonWeapons.CW_GASJET, target, 40);
            DebrisBurst b2 = SimFixture.lastBurst(w);
            int n2 = s.debris.lastCount;
            assertAll(
                () -> assertEquals(big, b1.count, "300 + (int)(r * 400) sparks"),
                () -> assertEquals(big, n1, "all of them are made"),
                () -> assertEquals(40 - 12, b1.minDir, "from dir - 4 * (4 - 1)"),
                () -> assertEquals(24, b1.dirSpan, "to dir + 4 * (4 - 1)"),
                () -> assertEquals(40f, b1.maxSpeed, 0.5f, "up to 4 * shotSpeed"),
                () -> assertEquals(0, et, "the emergency thrust is used"),
                () -> assertEquals(2, fuel1, "one fuel pack is used"),
                () -> assertEquals(small, b2.count, "150 + (int)(r * 200) sparks"),
                () -> assertEquals(small, n2, "all of them are made"),
                () -> assertEquals(40 - 9, b2.minDir, "from dir - 3 * (4 - 1)"),
                () -> assertEquals(18, b2.dirSpan, "to dir + 3 * (4 - 1)"),
                () -> assertEquals(20f, b2.maxSpeed, 0.5f, "up to 2 * shotSpeed"),
                () -> assertEquals(1, ci.item(0, Item.EMERGENCY_THRUST), "no emergency thrust used"),
                () -> assertEquals(1, ci.item(0, Item.FUEL), "one fuel pack used"));
        }
    }

    // ================================================================ per frame

    @Nested
    @DisplayName("defend (C Cannon_check_defense, Cannon_select_defense, Cannon_in_danger, Cannon_defend)")
    class Defend {
        /** A cannon with items and a shot 100 px above it. */
        int danger(String extra) {
            setup("cannonsUseItems: yes\ncannonSmartness: 1\n" + extra);
            ci.clear(0);
            return SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, CX, CY + 100, 0, 0, 1, 100);
        }

        @Test
        @DisplayName("an emergency shield (4*FPS, one item) is preferred, then phasing (ends the tractor beam)")
        void defenses() {
            danger("");
            ci.set(0, it(Item.EMERGENCY_SHIELD), 1);
            ci.set(0, it(Item.PHASING), 1);
            ci.emergencyShieldLeft[0] = 5;
            w.rng.seed(seedWhere(r -> r.nextFloat() < 0.65));
            cw.defend(0);
            int esLeft = ci.emergencyShieldLeft[0];
            int esUsed = ci.used[0];
            int esItems = ci.item(0, Item.EMERGENCY_SHIELD);
            int phItems = ci.item(0, Item.PHASING);
            ci.used[0] = 0;
            ci.tractorCount[0] = 9;
            w.rng.seed(seedWhere(r -> r.nextFloat() < 0.65));
            cw.defend(0);
            assertAll(
                () -> assertEquals(5 + 240, esLeft, "the emergency shield gets 4 * FPS more frames"),
                () -> assertEquals(Has.EMERGENCY_SHIELD, esUsed, "the emergency shield is on"),
                () -> assertEquals(0, esItems, "one emergency shield is used"),
                () -> assertEquals(1, phItems, "phasing is kept while a shield is available"),
                () -> assertEquals(Has.PHASING_DEVICE, ci.used[0], "without shields phasing goes on"),
                () -> assertEquals(240, ci.phasingLeft[0], "for 4 * FPS frames"),
                () -> assertEquals(0, ci.item(0, Item.PHASING), "one phasing device is used"),
                () -> assertEquals(0, ci.tractorCount[0], "phasing ends the tractor beam"));
        }

        @Test
        @DisplayName("no defense with cannonsUseItems or cannonsDefend off, at the 35% draw, while protected, or smartness 0")
        void guards() {
            List<String> got = new ArrayList<>();
            for (String extra : new String[] {"cannonsUseItems: no\n", "cannonsDefend: no\n"}) {
                danger(extra);
                ci.set(0, it(Item.EMERGENCY_SHIELD), 1);
                w.rng.seed(4);
                cw.defend(0);
                got.add(ci.used[0] + " " + (w.rng.nextInt() == new Rng(4).nextInt()));
            }
            danger("");
            ci.set(0, it(Item.EMERGENCY_SHIELD), 1);
            int seed = seedWhere(r -> r.nextFloat() >= 0.65);
            w.rng.seed(seed);
            cw.defend(0);
            got.add("draw " + ci.used[0] + " " + (w.rng.nextInt() == after(seed, 1).nextInt()));
            ci.used[0] = Has.PHASING_DEVICE;
            got.add("protected " + cw.selectDefense(0));
            ci.used[0] = Has.EMERGENCY_SHIELD;
            got.add("shielded " + cw.selectDefense(0));
            ci.used[0] = 0;
            ci.set(0, it(Item.EMERGENCY_SHIELD), 0);
            got.add("none " + cw.selectDefense(0));
            danger("cannonSmartness: 0\n");
            ci.set(0, it(Item.EMERGENCY_SHIELD), 1);
            got.add("dumb " + cw.selectDefense(0) + " " + cw.inDanger(0));
            assertEquals(List.of("0 true", "0 true", "draw 0 true", "protected -1", "shielded -1", "none -1",
                "dumb -1 false"), got, "each guard stops the defense (no draw when the options are off)");
        }

        @Test
        @DisplayName("danger: a killing object not from a cannon within (4.5 - smartness) blocks, looking ahead")
        void danger() {
            List<String> got = new ArrayList<>();
            int h = danger("");
            got.add("near " + cw.inDanger(0));
            w.objects.initPixels(h, CX, CY + 122);
            got.add("122 " + cw.inDanger(0));
            w.objects.initPixels(h, CX, CY + 123);
            got.add("123 " + cw.inDanger(0));
            w.objects.vy[h] = -10;
            got.add("approaching1 " + cw.inDanger(0));
            w.params.cannonSmartness = 2;
            w.objects.initPixels(h, CX, CY + 97);
            got.add("2:87 " + cw.inDanger(0));
            w.objects.initPixels(h, CX, CY + 98);
            got.add("2:88 " + cw.inDanger(0));
            w.objects.initPixels(h, CX, CY + 110);
            got.add("2:100 " + cw.inDanger(0));
            w.objects.accY[h] = -50;
            got.add("2:noAcc " + cw.inDanger(0));
            w.objects.accY[h] = 0;
            w.objects.vy[h] = 0;
            w.objects.vx[h] = -60;
            w.objects.initPixels(h, CX + 140, CY);
            got.add("2:x80 " + cw.inDanger(0));
            w.objects.vx[h] = 0;
            w.params.cannonSmartness = 3;
            w.objects.vy[h] = 0;
            w.objects.accY[h] = -48;
            w.objects.initPixels(h, CX, CY + 100);
            got.add("3:52 " + cw.inDanger(0));
            w.objects.accY[h] = -47;
            got.add("3:53 " + cw.inDanger(0));
            w.objects.accY[h] = 0;
            w.objects.accX[h] = -48;
            w.objects.initPixels(h, CX + 100, CY);
            got.add("3:x52 " + cw.inDanger(0));
            w.objects.accX[h] = 0;
            w.params.cannonSmartness = 1;
            w.objects.initPixels(h, CX, CY + 50);
            w.objects.status[h] |= Obj.FROMCANNON;
            got.add("fromCannon " + cw.inDanger(0));
            w.objects.status[h] = 0;
            w.objects.type[h] = Obj.DEBRIS;
            got.add("debris " + cw.inDanger(0));
            w.objects.type[h] = Obj.MINE;
            got.add("mine " + cw.inDanger(0));
            w.objects.life[h] = 0;
            got.add("dead " + cw.inDanger(0));
            assertEquals(List.of("near true", "122 true", "123 false", "approaching1 false", "2:87 true", "2:88 false",
                "2:100 false", "2:noAcc false", "2:x80 true", "3:52 true", "3:53 false", "3:x52 true", "fromCannon false", "debris false", "mine true", "dead false"), got,
                "smartness 1: 122.5 px around the cannon; 2: 87.5 px after one frame of velocity; 3: 52.5 px with acc");
        }

        @Test
        @DisplayName("team immunity: objects of the cannon's team are no danger in team play")
        void team() {
            setup("cannonsUseItems: yes\ncannonSmartness: 1\nteamPlay: yes\nteamImmunity: yes\nreserveRobotTeam: no\n"
                + "teamCannons: yes\n");
            int team = w.map.cannons().get(0).team();
            int h = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, CX, CY + 100, 0, 0, 1, 100);
            w.objects.team[h] = team;
            boolean own = cw.inDanger(0);
            w.objects.team[h] = team + 1;
            assertAll(
                () -> assertFalse(own, "a shot of the cannon's team is no danger"),
                () -> assertTrue(cw.inDanger(0), "another team's shot is"));
        }
    }

    @Test
    @DisplayName("mayFire: not while emergency-shielded, phased, damaged or holding a tractor beam")
    void mayFire() {
        setup("");
        List<Boolean> got = new ArrayList<>();
        got.add(cw.mayFire(0));
        ci.used[0] = Has.EMERGENCY_SHIELD;
        got.add(cw.mayFire(0));
        ci.used[0] = Has.PHASING_DEVICE;
        got.add(cw.mayFire(0));
        ci.used[0] = 0;
        ci.damaged[0] = 1;
        got.add(cw.mayFire(0));
        ci.damaged[0] = 0;
        ci.tractorCount[0] = 1;
        got.add(cw.mayFire(0));
        assertEquals(List.of(true, false, false, false, false), got, "free / shield / phasing / damaged / tractor");
    }

    @Nested
    @DisplayName("pickItem (update.c:639-652)")
    class PickItem {
        void items(String extra) {
            setup("cannonsUseItems: yes\n" + extra);
            ci.clear(0);
            java.util.Arrays.fill(w.items.cannonProb, 1f);
            java.util.Arrays.fill(w.items.limit, 99);
            w.items.limit[it(Item.FUEL)] = 10000;
            w.items.cannonItemProbMult = 3600;
            w.items.itemProbMult = 1;
        }

        @Test
        @DisplayName("a random kind is added when (int)(r * 60 * FPS) < cannonItemProbMult * its cannon weight")
        void adds() {
            items("");
            int seed = seedWhere(r -> (int) ((double) r.nextFloat() * Item.COUNT) == it(Item.MINE));
            w.rng.seed(seed);
            cw.pickItem(0);
            int mine = ci.item(0, Item.MINE);
            items("");
            w.items.cannonItemProbMult = 1;
            int seed2 = seedWhere(r -> (int) ((double) r.nextFloat() * Item.COUNT) == it(Item.MINE)
                && (int) ((double) r.nextFloat() * 3600) == 1);
            w.rng.seed(seed2);
            cw.pickItem(0);
            int unlucky = ci.item(0, Item.MINE);
            int seed3 = seedWhere(r -> (int) ((double) r.nextFloat() * Item.COUNT) == it(Item.MINE)
                && (int) ((double) r.nextFloat() * 3600) < 1);
            w.rng.seed(seed3);
            cw.pickItem(0);
            int lucky = ci.item(0, Item.MINE);
            w.items.cannonProb[it(Item.MINE)] = 2;
            w.rng.seed(seed2);
            cw.pickItem(0);
            int weighted = ci.item(0, Item.MINE);
            w.items.cannonProb[it(Item.MINE)] = 0;
            w.rng.seed(seed3);
            cw.pickItem(0);
            int noWeight = ci.item(0, Item.MINE);
            boolean oneDraw = w.rng.nextInt() == after(seed3, 1).nextInt();
            assertAll(
                () -> assertEquals(1, mine, "one mine is added"),
                () -> assertEquals(0, unlucky, "(int)(r * 3600) = 1, not < 1 * 1: nothing"),
                () -> assertEquals(1, lucky, "(int)(r * 3600) = 0 < 1: one mine"),
                () -> assertEquals(2, weighted, "(int)(r * 3600) = 1 < 1 * weight 2: one more mine"),
                () -> assertEquals(2, noWeight, "a kind without cannon weight is not added"),
                () -> assertTrue(oneDraw, "and only the kind is drawn"));
        }

        @Test
        @DisplayName("fuel comes as one energy pack (500 + (randomMT() & 511) units) stored in packs")
        void fuel() {
            items("");
            int seed = seedWhere(r -> (int) ((double) r.nextFloat() * Item.COUNT) == it(Item.FUEL)
                && r.nextFloat() >= 0 && (500 + (r.nextInt() & 511)) >= 2 * (500 + (r.nextInt() & 511)) / 2 + 300);
            Rng ref = after(seed, 2);
            int units = 500 + (ref.nextInt() & 511);
            int packs = units / (500 + (ref.nextInt() & 511));
            w.rng.seed(seed);
            cw.pickItem(0);
            assertAll(
                () -> assertEquals(1, packs, "a big pack over a small pack is one pack"),
                () -> assertEquals(packs, ci.item(0, Item.FUEL), "the pack's units divided by another pack size"));
        }

        @Test
        @DisplayName("nothing (and no draw) without cannonsUseItems, itemProbMult or cannonItemProbMult")
        void guards() {
            List<Boolean> same = new ArrayList<>();
            for (int k = 0; k < 3; k++) {
                items(k == 0 ? "cannonsUseItems: no\n" : "");
                if (k == 1) {
                    w.items.itemProbMult = 0;
                } else if (k == 2) {
                    w.items.cannonItemProbMult = 0;
                }
                w.rng.seed(4);
                cw.pickItem(0);
                same.add(w.rng.nextInt() == new Rng(4).nextInt());
            }
            assertEquals(List.of(true, true, true), same, "each switch stops it before any draw");
        }
    }

    @Nested
    @DisplayName("tick (update.c:654-685)")
    class Tick {
        @Test
        @DisplayName("damage counts down; emergency shield and phasing time out")
        void timers() {
            setup("");
            ci.damaged[0] = 2;
            ci.used[0] = Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE;
            ci.emergencyShieldLeft[0] = 2;
            ci.phasingLeft[0] = 1;
            cw.tick(0);
            int used1 = ci.used[0];
            cw.tick(0);
            cw.tick(0);
            assertAll(
                () -> assertEquals(Has.EMERGENCY_SHIELD, used1, "phasing ends when its last frame runs out"),
                () -> assertEquals(0, ci.used[0], "the shield ends one frame later"),
                () -> assertEquals(0, ci.damaged[0], "damage stops at 0"),
                () -> assertEquals(0, ci.emergencyShieldLeft[0], "the shield counter stops at 0"),
                () -> assertEquals(0, ci.phasingLeft[0], "the phasing counter stops at 0"));
        }

        @Test
        @DisplayName("a tractor beam pulls a playing target in range each frame and stops otherwise")
        void tractor() {
            setup("");
            Player p = ready(s, "P", 0, CX, CY + 100);
            ci.set(0, it(Item.TRACTOR_BEAM), 1);
            ci.tractorTarget[0] = p.id;
            ci.tractorCount[0] = 3;
            cw.tick(0);
            int count = ci.tractorCount[0];
            float vy = p.vy;
            Cannon cn = w.map.cannons().get(0);
            float dist = w.wrapLength((float) cn.x() - p.px, (float) cn.y() - p.py);
            float percent = Devices.tractorPercent(dist, 250);
            int theta = (int) com.fulcrologic.jxpilot.common.math.XMath.findDir(w.wrapDx((float) cn.x() - p.px),
                w.wrapDy((float) cn.y() - p.py));
            float want = -Trig.tsin(theta) * ((percent * -60f) / p.mass);
            p.status |= Obj.KILLED;
            cw.tick(0);
            int dead = ci.tractorCount[0];
            p.status &= ~Obj.KILLED;
            p.initPixels(CX, CY + 251);
            ci.tractorCount[0] = 3;
            cw.tick(0);
            int far = ci.tractorCount[0];
            ci.tractorTarget[0] = 999;
            ci.tractorCount[0] = 3;
            cw.tick(0);
            assertAll(
                () -> assertEquals(2, count, "one frame of the beam is used"),
                () -> assertEquals(want, vy, "the target is pulled toward the cannon by General_tractor_beam"),
                () -> assertTrue(vy < 0, "toward the cannon (down)"),
                () -> assertEquals(0, dead, "a dead target ends the beam"),
                () -> assertEquals(0, far, "a target 250+ px away ends the beam"),
                () -> assertEquals(0, ci.tractorCount[0], "a target that left ends the beam"));
        }
    }

    @Test
    @DisplayName("throwItems: stock above the initial counts is thrown as packs with dropItemOnKillProb * 0.7")
    void throwItems() {
        setup("");
        var ip = w.items;
        ip.dropItemOnKillProb = 1f;
        ip.initial[it(Item.MINE)] = 1;
        ip.minPerPack[it(Item.MINE)] = 1;
        ip.maxPerPack[it(Item.MINE)] = 3;
        ip.initial[it(Item.ARMOR)] = 0;
        ip.minPerPack[it(Item.ARMOR)] = 1;
        ip.maxPerPack[it(Item.ARMOR)] = 1;
        ci.set(0, it(Item.MINE), 6);
        ci.set(0, it(Item.ARMOR), 1);
        ci.set(0, it(Item.FUEL), 5);
        w.rng.seed(21);
        Rng ref = new Rng(21);
        List<String> want = new ArrayList<>();
        int[] kinds = {it(Item.MINE), it(Item.ARMOR)};
        int[] left = {5, 1};
        float firstVel = -1;
        int firstDir = 0;
        int firstLife = 0;
        for (int k = 0; k < 2; k++) {
            int i = kinds[k];
            while (left[k] > 0) {
                int amount = ip.maxPerPack[i] - (int) ((double) ref.nextFloat() * (1 + ip.maxPerPack[i] - ip.minPerPack[i]));
                amount = Math.min(amount, left[k]);
                if (ref.nextFloat() < 0.7) {
                    int dir = Trig.mod((int) (32 - 21.0 + (double) ref.nextFloat() * 42));
                    float vel = (float) ((double) ref.nextFloat() * 6);
                    int life = 1500 + (int) ((double) ref.nextFloat() * 512);
                    if (firstVel < 0) {
                        firstVel = vel;
                        firstDir = dir;
                        firstLife = life;
                    }
                    want.add(i + "x" + amount);
                }
                left[k] -= amount;
            }
        }
        cw.throwItems(0);
        Objects o = w.objects;
        List<String> got = new ArrayList<>();
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            got.add(o.info[h] + "x" + o.counter[h]);
        }
        int h = o.active[0];
        float v = firstVel;
        int dir = firstDir;
        int life = firstLife;
        assertAll(
            () -> assertTrue(want.size() > 1, "several packs are thrown"),
            () -> assertEquals(want, got, "packs of max - (int)(r * (1 + max - min)), each thrown with 0.7"),
            () -> assertEquals(Obj.ITEM, o.type[h], "an item"),
            () -> assertEquals(Records.ObjColor.RED, o.color[h], "red"),
            () -> assertEquals(Obj.GRAVITY, o.status[h], "with gravity"),
            () -> assertEquals(Obj.NO_ID, o.owner[h], "without an owner"),
            () -> assertEquals(Const.TEAM_NOT_SET, o.team[h], "without a team"),
            () -> assertEquals(CX, o.px[h], "from the cannon's x"),
            () -> assertEquals(Trig.tcos(dir) * v, o.vx[h], "out of the cannon's face, x"),
            () -> assertEquals(Trig.tsin(dir) * v, o.vy[h], "out of the cannon's face, y"),
            () -> assertEquals(10f, o.mass[h], "mass 10"),
            () -> assertEquals((double) life, o.life[h], "life 1500 + (int)(r * 512)"),
            () -> assertEquals(8, o.plRange[h], "ITEM_SIZE / 2"),
            () -> assertEquals(0, ci.item(0, Item.MINE), "the stock is used up"),
            () -> assertEquals(5, ci.item(0, Item.FUEL), "fuel is never thrown"),
            () -> assertEquals(want.size() - (int) want.stream().filter(x -> x.startsWith(it(Item.ARMOR) + "x")).count(),
                w.itemCount[it(Item.MINE)], "the world counts the thrown mine packs"));
    }

    // ================================================================ crashes

    @Nested
    @DisplayName("cannon crashes (walls.c Object_crash, Player_crash, Cannon_dies)")
    class CannonCrash {
        MoveState ms() {
            MoveState m = new MoveState();
            m.crash = CRASH_CANNON;
            m.cannon = 0;
            return m;
        }

        @Test
        @DisplayName("an item hitting a cannon is added to its stock; the item dies")
        void item() {
            setup("");
            int h = SimFixture.obj(w, Obj.ITEM, Obj.NO_ID, CX, CY + 10, 0, 0, 10, 100);
            w.objects.info[h] = it(Item.MINE);
            w.objects.counter[h] = 2;
            s.crashes.objectCrashed(h, ms());
            assertAll(
                () -> assertEquals(2, ci.item(0, Item.MINE), "the cannon gets the 2 mines"),
                () -> assertEquals(0.0, w.objects.life[h], "the item is used up"),
                () -> assertEquals(0, w.cannonDeadTime[0], "the cannon lives"));
        }

        @Test
        @DisplayName("a shot uses up an armor, is stopped by an emergency shield, else destroys the cannon")
        void shots() {
            setup("cannonDeadTime: 5\n");
            int h = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, CX, CY + 10, 0, 0, 1, 100);
            ci.set(0, it(Item.ARMOR), 1);
            s.crashes.objectCrashed(h, ms());
            int armor = ci.item(0, Item.ARMOR);
            int alive1 = w.cannonDeadTime[0];
            ci.used[0] = Has.EMERGENCY_SHIELD;
            s.crashes.objectCrashed(h, ms());
            int alive2 = w.cannonDeadTime[0];
            ci.used[0] = 0;
            ci.set(0, it(Item.SENSOR), 4);
            ci.tractorTarget[0] = 5;
            ci.damaged[0] = 3;
            s.crashes.objectCrashed(h, ms());
            Cannon cn = w.map.cannons().get(0);
            assertAll(
                () -> assertEquals(0, armor, "the armor is used up"),
                () -> assertEquals(0, alive1, "the armored cannon lives"),
                () -> assertEquals(0, alive2, "the shielded cannon lives"),
                () -> assertEquals(5 * 60, w.cannonDeadTime[0], "the bare cannon dies for cannonDeadTime (5 s at 60 fps)"),
                () -> assertEquals(BlockType.SPACE, w.map.block(cn.bx(), cn.by()), "its block becomes space"),
                () -> assertEquals(0, ci.item(0, Item.SENSOR), "it is re-initialized (no items without cannonsUseItems)"),
                () -> assertEquals(-1, ci.tractorTarget[0], "its tractor target is reset"),
                () -> assertEquals(0, ci.damaged[0], "its damage is reset"));
        }

        @Test
        @DisplayName("a dying cannon throws its items")
        void throwsItems() {
            setup("");
            w.items.dropItemOnKillProb = 1f;
            w.items.initial[it(Item.SENSOR)] = 0;
            w.items.minPerPack[it(Item.SENSOR)] = 1;
            w.items.maxPerPack[it(Item.SENSOR)] = 1;
            ci.set(0, it(Item.SENSOR), 20);
            int h = SimFixture.obj(w, Obj.SHOT, Obj.NO_ID, CX, CY + 10, 0, 0, 1, 100);
            s.crashes.objectCrashed(h, ms());
            int items = 0;
            for (int k = 0; k < w.objects.count(); k++) {
                if (w.objects.type[w.objects.active[k]] == Obj.ITEM) {
                    items++;
                }
            }
            int n = items;
            assertTrue(n > 5 && n < 20, "about 70% of the 20 sensor packs fly off, got " + n);
        }

        @Test
        @DisplayName("a ship crashing into an emergency-shielded cannon dies but the cannon lives")
        void shieldedCannon() {
            setup("");
            Player p = ready(s, "P", 0, CX, CY + 10);
            ci.used[0] = Has.EMERGENCY_SHIELD;
            s.crashes.playerCrashed(p, ms(), 1);
            boolean killed = (p.status & Obj.KILLED) != 0;
            int alive = w.cannonDeadTime[0];
            Player q = ready(s, "Q", 0, CX, CY + 10);
            ci.used[0] = 0;
            s.crashes.playerCrashed(q, ms(), 1);
            assertAll(
                () -> assertTrue(killed, "the ship is killed"),
                () -> assertEquals(0, alive, "the shielded cannon lives"),
                () -> assertTrue(w.cannonDeadTime[0] > 0, "an unshielded cannon dies"));
        }
    }
}
