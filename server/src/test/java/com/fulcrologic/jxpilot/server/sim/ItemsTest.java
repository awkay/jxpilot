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
import com.fulcrologic.jxpilot.common.map.Concentrator;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.ItemParams;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.fulcrologic.jxpilot.server.sim.ItemFx.fillPool;
import static com.fulcrologic.jxpilot.server.sim.ItemFx.live;
import static com.fulcrologic.jxpilot.server.sim.ItemFx.reseed;
import static com.fulcrologic.jxpilot.server.sim.ItemFx.rf;
import static com.fulcrologic.jxpilot.server.sim.ItemFx.seedWhere;
import static com.fulcrologic.jxpilot.server.sim.ItemFx.skip;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Items: item.c (Place_item, Make_item, ...), update.c spawn, collision.c pickup, event.c keys, shot.c Delete_shot. */
@DisplayName("Items (item.c)")
class ItemsTest {
    static final int FUEL = Item.FUEL.ordinal();
    static final int WIDE = Item.WIDEANGLE.ordinal();
    static final int REAR = Item.REARSHOT.ordinal();
    static final int AFTER = Item.AFTERBURNER.ordinal();
    static final int CLOAK = Item.CLOAK.ordinal();
    static final int SENSOR = Item.SENSOR.ordinal();
    static final int TRANS = Item.TRANSPORTER.ordinal();
    static final int TANK = Item.TANK.ordinal();
    static final int MINE = Item.MINE.ordinal();
    static final int MISSILE = Item.MISSILE.ordinal();
    static final int ECM = Item.ECM.ordinal();
    static final int LASER = Item.LASER.ordinal();
    static final int ETHRUST = Item.EMERGENCY_THRUST.ordinal();
    static final int TRACTOR = Item.TRACTOR_BEAM.ordinal();
    static final int AUTO = Item.AUTOPILOT.ordinal();
    static final int ESHIELD = Item.EMERGENCY_SHIELD.ordinal();
    static final int DEFL = Item.DEFLECTOR.ordinal();
    static final int HYPER = Item.HYPERJUMP.ordinal();
    static final int PHASING = Item.PHASING.ordinal();
    static final int MIRROR = Item.MIRROR.ordinal();
    static final int ARMOR = Item.ARMOR.ordinal();

    /** An open 20x20-block (700x700 px) map, 60 fps, no gravity, one base. */
    static Simulation open() {
        return sim(FRAME, 20, 20, "_");
    }

    /** A 20x20 map of spaces with one character changed at map row {@code row} (0 = top), column {@code col}. */
    static String[] rows(int row, int col, char c) {
        String[] r = SimFixture.open(20, 20, "_");
        char[] line = r[row].toCharArray();
        line[col] = c;
        r[row] = new String(line);
        return r;
    }

    /** The ITEM objects as "kind:count" in pool order. */
    static List<String> itemList(World w) {
        List<String> l = new ArrayList<>();
        for (int h : live(w, Obj.ITEM)) {
            l.add(Item.of(w.objects.info[h]) + ":" + w.objects.counter[h]);
        }
        return l;
    }

    // ================================================================ energy pack

    @Test
    @DisplayName("ENERGY_PACK_FUEL is (500 + (randomMT() & 511)) << 8")
    void energyPackFuel() {
        Simulation s = open();
        Rng m = reseed(s.world, 5);
        double expected = (500 + (m.nextInt() & 511)) << 8;
        assertEquals(expected, s.items.energyPackFuel(), "const.h ENERGY_PACK_FUEL");
    }

    @Test
    @DisplayName("warmSwitches runs the item switch once")
    void warm() {
        assertEquals(0, Items.warmSwitches(), "the FUEL case of the warm-up switch is taken");
    }

    // ================================================================ spawn

    @Nested
    @DisplayName("spawn (update.c:517-523)")
    class Spawn {
        @Test
        @DisplayName("only kinds below their maximum with a chance draw rfrac(); rfrac() * chance >= 1 places nothing")
        void gating() {
            Simulation s = open();
            World w = s.world;
            ItemParams ip = w.items;
            Arrays.fill(ip.chance, 0);
            Arrays.fill(ip.max, 0);
            ip.chance[ECM] = 1 << 30;
            ip.max[ECM] = 1;
            ip.chance[MINE] = 1 << 30;
            ip.max[MINE] = 1;
            w.itemCount[MINE] = 1;
            ip.chance[LASER] = 0;
            ip.max[LASER] = 5;
            int seed = seedWhere(r -> r.nextFloat() > 0);
            Rng m = reseed(w, seed);
            s.items.spawn();
            m.nextInt();
            assertAll(
                () -> assertEquals(m.state(), w.rng.state(),
                    "exactly one draw: for the eligible kind, none at the maximum or with chance 0"),
                () -> assertEquals(List.of(), itemList(w), "r * 2^30 >= 1 places nothing"),
                () -> assertEquals(0, w.itemCount[ECM], "the world count is unchanged"));
        }

        @Test
        @DisplayName("rfrac() * chance < 1 places an item of that kind, in item order")
        void places() {
            Simulation s = open();
            World w = s.world;
            ItemParams ip = w.items;
            Arrays.fill(ip.chance, 0);
            ip.chance[WIDE] = 1;
            ip.chance[ECM] = 1;
            ip.movingItemProb = 0;
            ip.randomItemProb = 0;
            s.items.spawn();
            assertAll(
                () -> assertEquals(List.of("WIDEANGLE:1", "ECM:1"), itemList(w), "chance 1 always places"),
                () -> assertEquals(1, w.itemCount[WIDE], "the wide-angle count went up"),
                () -> assertEquals(1, w.itemCount[ECM], "the ECM count went up"));
        }

        @Test
        @DisplayName("chance 3 places for draws below 1/3 and not at or above it")
        void boundary() {
            int below = seedWhere(r -> {
                float f = r.nextFloat();
                return f < 1 / 3.0 && f > 0.33;
            });
            int above = seedWhere(r -> {
                float f = r.nextFloat();
                return f >= 1 / 3.0 && f < 0.34;
            });
            List<List<String>> got = new ArrayList<>();
            for (int seed : new int[] {below, above}) {
                Simulation s = open();
                World w = s.world;
                Arrays.fill(w.items.chance, 0);
                w.items.chance[ECM] = 3;
                reseed(w, seed);
                s.items.spawn();
                got.add(itemList(w));
            }
            assertAll(
                () -> assertEquals(List.of("ECM:1"), got.get(0), "r * 3 < 1 places"),
                () -> assertEquals(List.of(), got.get(1), "r * 3 >= 1 does not"));
        }
    }

    // ================================================================ Choose_random_item

    @Nested
    @DisplayName("chooseRandom (Choose_random_item)")
    class ChooseRandom {
        @Test
        @DisplayName("the first kind where the running sum reaches r * total; past the end is FUEL; no total is NUM_ITEMS")
        void buckets() {
            Simulation s = open();
            ItemParams ip = s.world.items;
            Arrays.fill(ip.prob, 0);
            ip.prob[WIDE] = 0.25f;
            ip.prob[MINE] = 0.5f;
            ip.prob[ECM] = 0.25f;
            Items it = s.items;
            List<Integer> picks = List.of(it.chooseRandom(0.0), it.chooseRandom(0.1), it.chooseRandom(0.25),
                it.chooseRandom(0.26), it.chooseRandom(0.75), it.chooseRandom(0.9), it.chooseRandom(1.5));
            Arrays.fill(ip.prob, 0);
            int none = it.chooseRandom(0.5);
            ip.prob[ECM] = -1;
            int negative = it.chooseRandom(0.5);
            assertAll(
                () -> assertEquals(List.of(FUEL, WIDE, WIDE, MINE, MINE, ECM, FUEL), picks,
                    "r 0 -> FUEL (sum 0 <= 0 at once, as C), 0.1 and 0.25 (boundary) -> wide-angle, 0.26 and 0.75 "
                        + "(boundary) -> mine, 0.9 -> ECM, 1.5 runs past the end -> FUEL"),
                () -> assertEquals(Item.COUNT, none, "a zero total returns NUM_ITEMS"),
                () -> assertEquals(Item.COUNT, negative, "a negative total returns NUM_ITEMS"));
        }

        @Test
        @DisplayName("without an argument the draw is the world's rfrac()")
        void usesWorldRng() {
            Simulation s = open();
            ItemParams ip = s.world.items;
            Arrays.fill(ip.prob, 0);
            for (int i = 1; i < Item.COUNT; i++) {
                ip.prob[i] = 1;
            }
            Rng m = reseed(s.world, 11);
            int expected = s.items.chooseRandom(rf(m));
            reseed(s.world, 11);
            assertEquals(expected, s.items.chooseRandom(), "chooseRandom() == chooseRandom(rfrac())");
        }
    }

    // ================================================================ Place_item, world items

    @Nested
    @DisplayName("place: world items (Place_item with ind -1)")
    class PlaceWorld {
        @Test
        @DisplayName("a still, plain item lands at (rfrac*width, rfrac*height) with zero velocity")
        void still() {
            Simulation s = open();
            World w = s.world;
            w.items.movingItemProb = 0;
            w.items.randomItemProb = 0;
            Rng m = reseed(w, 7);
            skip(m, 2); // moving, random
            int px = (int) (rf(m) * 700);
            int py = (int) (rf(m) * 700);
            int life = 1500 + (int) (rf(m) * 512);
            s.items.place(ECM, null);
            Objects o = w.objects;
            int h = live(w, Obj.ITEM).get(0);
            assertAll(
                () -> assertEquals(List.of("ECM:1"), itemList(w), "one ECM pack of one"),
                () -> assertEquals(px, o.px[h], "x = (int)(rfrac() * World.width)"),
                () -> assertEquals(py, o.py[h], "y = (int)(rfrac() * World.height)"),
                () -> assertEquals(0f, o.vx[h], "a still item does not move in x"),
                () -> assertEquals(0f, o.vy[h], "a still item does not move in y"),
                () -> assertEquals(0, o.status[h], "neither GRAVITY nor RANDOM_ITEM"),
                () -> assertEquals(life, o.life[h], "life 1500 + rfrac * 512"),
                () -> assertEquals(m.state(), w.rng.state(), "no concentrator draw on a map without concentrators"));
        }

        @Test
        @DisplayName("a moving RANDOM_ITEM starts against Gravity * gravity plus (int)(rfrac*8) - 3 (item.c:332-335)")
        void moving() {
            Simulation s = TestWorlds.sim("framesPerSecond: 60\ngravity: -2\n", SimFixture.open(20, 20, "_"));
            World w = s.world;
            w.items.movingItemProb = 1;
            w.items.randomItemProb = 1;
            Rng m = reseed(w, 9);
            skip(m, 2);
            int px = (int) (rf(m) * 700);
            int py = (int) (rf(m) * 700);
            int kx = (int) (rf(m) * 8);
            int ky = (int) (rf(m) * 8);
            float gy = w.gravityY(px / 35, py / 35);
            s.items.place(ECM, null);
            Objects o = w.objects;
            int h = live(w, Obj.ITEM).get(0);
            assertAll(
                () -> assertEquals(-2f, gy, "the block gravity already includes Gravity (map.c Compute_gravity)"),
                () -> assertEquals(Obj.GRAVITY | Obj.RANDOM_ITEM, o.status[h], "moving and random"),
                // vx -= Gravity * gravity.x (about 1e-16) ; vx += kx - 3
                () -> assertEquals(kx - 3, (int) o.vx[h], "x: (int)(kx - 3)"),
                // vy -= (-2) * (-2) = -4 ; vy += ky - 3
                () -> assertEquals(ky - 7, (int) o.vy[h], "y: -Gravity*gravity.y = -4, plus ky - 3"),
                () -> assertEquals(px, o.px[h], "placed at the random spot"));
        }

        @Test
        @DisplayName("near an item concentrator: rfrac*RES direction, rfrac*(radius*BLOCK_SZ+1) distance from its centre")
        void concentrator() {
            Simulation s = TestWorlds.sim(FRAME, rows(10, 10, '%'));
            World w = s.world;
            w.items.movingItemProb = 0;
            w.items.randomItemProb = 0;
            w.items.itemConcentratorProb = 1;
            w.items.itemConcentratorRadius = 2;
            Concentrator c = w.map.itemConcentrators().get(0);
            Rng m = reseed(w, 13);
            skip(m, 4); // moving, random, concentrator probability, which concentrator
            int dir = (int) (rf(m) * Const.RES);
            int dist = (int) (rf(m) * (2 * 35 + 1));
            int px = (int) ((c.bx() + 0.5) * 35 + dist * Trig.tcos(dir));
            int py = (int) ((c.by() + 0.5) * 35 + dist * Trig.tsin(dir));
            s.items.place(ECM, null);
            Objects o = w.objects;
            int h = live(w, Obj.ITEM).get(0);
            assertAll(
                () -> assertEquals(px, o.px[h], "x from the concentrator centre"),
                () -> assertEquals(py, o.py[h], "y from the concentrator centre"),
                () -> assertTrue(Math.abs(o.px[h] - (c.bx() + 0.5) * 35) <= 71, "within the radius in x"));
        }

        @Test
        @DisplayName("itemConcentratorProb 0: the concentrator probability is drawn but the spot is random")
        void concentratorNotChosen() {
            Simulation s = TestWorlds.sim(FRAME, rows(10, 10, '%'));
            World w = s.world;
            w.items.movingItemProb = 0;
            w.items.randomItemProb = 0;
            w.items.itemConcentratorProb = 0;
            Rng m = reseed(w, 13);
            skip(m, 3);
            int px = (int) (rf(m) * 700);
            int py = (int) (rf(m) * 700);
            s.items.place(ECM, null);
            int h = live(w, Obj.ITEM).get(0);
            assertAll(
                () -> assertEquals(px, w.objects.px[h], "x is uniform over the world"),
                () -> assertEquals(py, w.objects.py[h], "y is uniform over the world"));
        }

        /** One concentrator try's position (2 draws). */
        int[] conTry(Rng m, Concentrator c, int radius) {
            int dir = (int) (rf(m) * Const.RES);
            int dist = (int) (rf(m) * (radius * 35 + 1));
            return new int[] {(int) ((c.bx() + 0.5) * 35 + dist * Trig.tcos(dir)),
                (int) ((c.by() + 0.5) * 35 + dist * Trig.tsin(dir))};
        }

        /** Place one ECM near the only concentrator of a map with a '%' at (row, col), radius 3, with a seed. */
        int[] placeAt(String extra, int row, int col, int seed) {
            Simulation s = TestWorlds.sim(FRAME + extra, rows(row, col, '%'));
            World w = s.world;
            w.items.movingItemProb = 0;
            w.items.randomItemProb = 0;
            w.items.itemConcentratorProb = 1;
            w.items.itemConcentratorRadius = 3;
            reseed(w, seed);
            s.items.place(ECM, null);
            List<Integer> l = live(w, Obj.ITEM);
            return l.isEmpty() ? null : new int[] {w.objects.px[l.get(0)], w.objects.py[l.get(0)]};
        }

        @Test
        @DisplayName("a try off the low edges is retried on a flat map and wrapped on a wrapping map")
        void lowEdges() {
            // concentrator at block (0, 0) (bottom-left): find a seed whose first try is off both low edges
            Concentrator c = new Concentrator(0, 0, 0);
            int seed = seedWhere(r -> {
                skip(r, 4);
                int[] a = conTry(r, c, 3);
                int[] b = conTry(r, c, 3);
                return a[0] < 0 && a[1] < 0 && b[0] >= 0 && b[1] >= 0;
            });
            Rng m = skip(new Rng(seed), 4);
            int[] first = conTry(m, c, 3);
            int[] second = conTry(m, c, 3);
            int[] flat = placeAt("", 19, 0, seed);
            int[] wrap = placeAt("edgeWrap: yes\n", 19, 0, seed);
            assertAll(
                () -> assertEquals(List.of(second[0], second[1]), List.of(flat[0], flat[1]),
                    "flat: the off-world first try is skipped and the second used"),
                () -> assertEquals(List.of(first[0] + 700, first[1] + 700), List.of(wrap[0], wrap[1]),
                    "wrap: the first try is wrapped by the world size"));
        }

        @Test
        @DisplayName("a try off the high edges is retried on a flat map and wrapped on a wrapping map")
        void highEdges() {
            Concentrator c = new Concentrator(0, 19, 19);
            int seed = seedWhere(r -> {
                skip(r, 4);
                int[] a = conTry(r, c, 3);
                int[] b = conTry(r, c, 3);
                return a[0] >= 700 && a[1] >= 700 && b[0] < 700 && b[1] < 700;
            });
            Rng m = skip(new Rng(seed), 4);
            int[] first = conTry(m, c, 3);
            int[] second = conTry(m, c, 3);
            int[] flat = placeAt("", 0, 19, seed);
            int[] wrap = placeAt("edgeWrap: yes\n", 0, 19, seed);
            assertAll(
                () -> assertEquals(List.of(second[0], second[1]), List.of(flat[0], flat[1]),
                    "flat: the off-world first try is skipped"),
                () -> assertEquals(List.of(first[0] - 700, first[1] - 700), List.of(wrap[0], wrap[1]),
                    "wrap: the first try is wrapped back by the world size"));
        }

        /** A seed whose first try at c satisfies {@code out} and whose second try is inside the world. */
        int retrySeed(Concentrator c, java.util.function.Predicate<int[]> out) {
            return seedWhere(r -> {
                skip(r, 4);
                int[] a = conTry(r, c, 3);
                int[] b = conTry(r, c, 3);
                return out.test(a) && b[0] >= 0 && b[0] < 700 && b[1] >= 0 && b[1] < 700;
            });
        }

        /** The second try's position for a seed. */
        String second(int seed, Concentrator c) {
            Rng m = skip(new Rng(seed), 6);
            return Arrays.toString(conTry(m, c, 3));
        }

        @Test
        @DisplayName("each edge alone sends a flat-map try round again")
        void singleEdges() {
            Concentrator lo = new Concentrator(0, 0, 10);
            Concentrator hi = new Concentrator(0, 19, 10);
            Concentrator bottom = new Concentrator(0, 10, 0);
            Concentrator top = new Concentrator(0, 10, 19);
            int sLo = retrySeed(lo, a -> a[0] < 0);
            int sHi = retrySeed(hi, a -> a[0] >= 700);
            int sBottom = retrySeed(bottom, a -> a[1] < 0);
            int sTop = retrySeed(top, a -> a[1] >= 700);
            assertAll(
                () -> assertEquals(second(sLo, lo), Arrays.toString(placeAt("", 9, 0, sLo)), "x < 0 is retried"),
                () -> assertEquals(second(sHi, hi), Arrays.toString(placeAt("", 9, 19, sHi)),
                    "x >= width is retried"),
                () -> assertEquals(second(sBottom, bottom), Arrays.toString(placeAt("", 19, 10, sBottom)),
                    "y < 0 is retried"),
                () -> assertEquals(second(sTop, top), Arrays.toString(placeAt("", 0, 10, sTop)),
                    "y >= height is retried"));
        }

        /** A seed whose first try at c satisfies {@code first}. */
        int firstSeed(Concentrator c, java.util.function.Predicate<int[]> first) {
            return seedWhere(r -> first.test(conTry(skip(r, 4), c, 3)));
        }

        @Test
        @DisplayName("tries exactly on the low edges are kept; exactly on the high edges are wrapped to 0")
        void exactEdges() {
            Concentrator lo = new Concentrator(0, 0, 10);
            Concentrator hi = new Concentrator(0, 19, 10);
            Concentrator bottom = new Concentrator(0, 10, 0);
            Concentrator top = new Concentrator(0, 10, 19);
            int sLo = firstSeed(lo, a -> a[0] == 0);
            int sHi = firstSeed(hi, a -> a[0] == 700);
            int sBottom = firstSeed(bottom, a -> a[1] == 0);
            int sTop = firstSeed(top, a -> a[1] == 700);
            int[] a = placeAt("", 9, 0, sLo);
            int[] b = placeAt("edgeWrap: yes\n", 9, 19, sHi);
            int[] c = placeAt("", 19, 10, sBottom);
            int[] d = placeAt("edgeWrap: yes\n", 0, 10, sTop);
            int[] bFirst = conTry(skip(new Rng(sHi), 4), hi, 3);
            int[] dFirst = conTry(skip(new Rng(sTop), 4), top, 3);
            assertAll(
                () -> assertEquals(Arrays.toString(conTry(skip(new Rng(sLo), 4), lo, 3)), Arrays.toString(a),
                    "x = 0 is in the world (flat map)"),
                () -> assertEquals(Arrays.toString(new int[] {0, bFirst[1]}), Arrays.toString(b),
                    "x = width wraps to 0"),
                () -> assertEquals(Arrays.toString(conTry(skip(new Rng(sBottom), 4), bottom, 3)), Arrays.toString(c),
                    "y = 0 is in the world (flat map)"),
                () -> assertEquals(Arrays.toString(new int[] {dFirst[0], 0}), Arrays.toString(d),
                    "y = height wraps to 0"));
        }

        @Test
        @DisplayName("the concentrator is the (int)(rfrac * count)-th")
        void whichConcentrator() {
            String[] r = rows(5, 5, '%');
            char[] line = r[14].toCharArray();
            line[14] = '%';
            r[14] = new String(line);
            Simulation s = TestWorlds.sim(FRAME, r);
            World w = s.world;
            w.items.movingItemProb = 0;
            w.items.randomItemProb = 0;
            w.items.itemConcentratorProb = 1;
            w.items.itemConcentratorRadius = 3;
            int seed = seedWhere(q -> {
                skip(q, 3);
                return q.nextFloat() >= 0.5f;
            });
            Concentrator second = w.map.itemConcentrators().get(1);
            reseed(w, seed);
            s.items.place(ECM, null);
            int h = live(w, Obj.ITEM).get(0);
            assertEquals(Arrays.toString(conTry(skip(new Rng(seed), 4), second, 3)),
                Arrays.toString(new int[] {w.objects.px[h], w.objects.py[h]}), "a draw >= 0.5 of 2 picks the second");
        }

        @Test
        @DisplayName("with gravity at 45 degrees both components push against Gravity * gravity")
        void diagonalGravity() {
            Simulation s = TestWorlds.sim("framesPerSecond: 60\ngravity: -2\ngravityAngle: 45\n",
                SimFixture.open(20, 20, "_"));
            World w = s.world;
            w.items.movingItemProb = 1;
            w.items.randomItemProb = 0;
            int seed = seedWhere(q -> {
                skip(q, 4);
                return (int) (q.nextFloat() * 8) == 5 && (int) (q.nextFloat() * 8) == 5;
            });
            Rng m = reseed(w, seed);
            skip(m, 2);
            int px = (int) (rf(m) * 700);
            int py = (int) (rf(m) * 700);
            float gx = w.gravityX(px / 35, py / 35);
            float gy = w.gravityY(px / 35, py / 35);
            s.items.place(ECM, null);
            int h = live(w, Obj.ITEM).get(0);
            assertAll(
                () -> assertEquals(-1.4142135f, gx, 1e-6f, "gravity x = cos 45 * -2"),
                // vx = -(-2 * -1.414) + (5 - 3) = -2.83 + 2 = -0.83 -> 0; without the second Gravity it is 3.41 -> 3
                () -> assertEquals((int) (-(-2f * gx) + 2), (int) w.objects.vx[h], "x: (int)(-2.83 + 2)"),
                () -> assertEquals((int) (-(-2f * gy) + 2), (int) w.objects.vy[h], "y: (int)(-2.83 + 2)"),
                () -> assertEquals(0, (int) w.objects.vx[h], "which is 0"));
        }

        @Test
        @DisplayName("after 8 tries on non-space blocks nothing is made; a cannon block is accepted at once")
        void tries() {
            String[] walls = new String[20];
            String[] cannons = new String[20];
            Arrays.fill(walls, "xxxxxxxxxxxxxxxxxxxx");
            Arrays.fill(cannons, "rrrrrrrrrrrrrrrrrrrr");
            Simulation sw = TestWorlds.sim(FRAME, walls);
            Simulation sc = TestWorlds.sim(FRAME, cannons);
            for (Simulation s : List.of(sw, sc)) {
                s.world.items.movingItemProb = 0;
                s.world.items.randomItemProb = 0;
                s.world.items.max[ECM] = 5;
            }
            Rng mw = reseed(sw.world, 3);
            sw.items.place(ECM, null);
            skip(mw, 2 + 8 * 2);
            Rng mc = reseed(sc.world, 3);
            sc.items.place(ECM, null);
            skip(mc, 2 + 2 + 1);
            assertAll(
                () -> assertEquals(List.of(), itemList(sw.world), "an all-wall map gets no item"),
                () -> assertEquals(mw.state(), sw.world.rng.state(), "two flags then 8 tries of 2 draws"),
                () -> assertEquals(List.of("ECM:1"), itemList(sc.world), "a cannon block takes a world item"),
                () -> assertEquals(mc.state(), sc.world.rng.state(), "one try, then the life draw"));
        }

        @Test
        @DisplayName("a mine pack holds 1 + (int)(rfrac * maxMinesPerPack) mines, drawn first")
        void packs() {
            Simulation s = TestWorlds.sim(FRAME + "maxMinesPerPack: 4\n", SimFixture.open(20, 20, "_"));
            World w = s.world;
            w.items.movingItemProb = 0;
            Rng m = reseed(w, 17);
            int n = 1 + (int) (rf(m) * 4);
            s.items.place(MINE, null);
            assertEquals(List.of("MINE:" + n), itemList(w), "pack size min + (int)(rfrac*(1+max-min))");
        }

        @Test
        @DisplayName("a full pool makes nothing")
        void fullPool() {
            Simulation s = open();
            World w = s.world;
            fillPool(w);
            Rng m = reseed(w, 3);
            s.items.place(ECM, null);
            assertAll(
                () -> assertEquals(List.of(), itemList(w), "nothing placed"),
                () -> assertEquals(0, w.itemCount[ECM], "the count is unchanged"),
                () -> assertEquals(m.state(), w.rng.state(), "no draws"));
        }
    }

    // ================================================================ Place_item, players

    @Nested
    @DisplayName("place: a player's item (Place_item with a player)")
    class PlacePlayer {
        Simulation s = open();
        World w = s.world;
        Player p = ready(s, "A", 0, 350, 350);

        int only() {
            List<Integer> l = live(w, Obj.ITEM);
            assertEquals(1, l.size(), "exactly one item");
            return l.get(0);
        }

        @Test
        @DisplayName("a living player drops one pack a block plus 0-7 px behind, at 0.75 of its speed minus 0-7 along it")
        void aliveFast() {
            p.items[ECM] = 3;
            p.vx = 40;
            p.vy = -30;
            Rng m = reseed(w, 21);
            int kx = (int) (rf(m) * 8);
            int ky = (int) (rf(m) * 8);
            int dvx = (int) (rf(m) * 8);
            int dvy = (int) (rf(m) * 8);
            s.items.place(ECM, p);
            int h = only();
            Objects o = w.objects;
            assertAll(
                () -> assertEquals(2, p.items[ECM], "the player loses one"),
                () -> assertEquals(1, o.counter[h], "a pack of one"),
                () -> assertEquals(350 - 35 - kx, o.px[h], "moving right: dropped to the left"),
                () -> assertEquals(350 + 35 + ky, o.py[h], "moving down: dropped above"),
                // |v| = 50 >= 1: v = 0.75 v - dv * (0.75 v / |v|) (item.c:313-326)
                () -> assertEquals((int) (30 - dvx * 0.6f), (int) o.vx[h], "vx = 30 - dvx * 0.6"),
                () -> assertEquals((int) (-22.5f + dvy * 0.45f), (int) o.vy[h], "vy = -22.5 + dvy * 0.45"),
                () -> assertEquals(Obj.GRAVITY, o.status[h], "a dropped item falls"),
                () -> assertEquals(1, w.itemCount[ECM], "the world count goes up"));
        }

        @Test
        @DisplayName("a slow living player's drop gets 0.75 of its speed minus 0-7 against each direction's sign")
        void aliveSlow() {
            p.items[ECM] = 1;
            p.vx = 0.5f;
            p.vy = -0.25f;
            Rng m = reseed(w, 23);
            skip(m, 2);
            int dvx = (int) (rf(m) * 8);
            int dvy = (int) (rf(m) * 8);
            s.items.place(ECM, p);
            int h = only();
            assertAll(
                () -> assertEquals((int) (0.375f - dvx), (int) w.objects.vx[h], "vx >= 0: 0.375 - dvx"),
                () -> assertEquals((int) (-0.1875f + dvy), (int) w.objects.vy[h], "vy < 0: -0.1875 + dvy"),
                () -> assertEquals(0, p.items[ECM], "the last one is gone"));
        }

        @Test
        @DisplayName("a ship at rest drops against +x/+y; a slow ship moving left/up drops with +dv; |v| = 1 is not slow")
        void slowSigns() {
            p.items[ECM] = 3;
            w.items.max[ECM] = 9;
            p.vx = 0;
            p.vy = 0;
            Rng m = reseed(w, 23);
            skip(m, 2);
            int dvx0 = (int) (rf(m) * 8);
            int dvy0 = (int) (rf(m) * 8);
            s.items.place(ECM, p);
            int h0 = live(w, Obj.ITEM).get(0);
            p.vx = -0.5f;
            p.vy = 0.25f;
            int seed1 = seedWhere(r -> {
                skip(r, 2);
                return (int) (r.nextFloat() * 8) >= 1 && (int) (r.nextFloat() * 8) >= 1;
            });
            Rng m1 = reseed(w, seed1);
            skip(m1, 2);
            int dvx1 = (int) (rf(m1) * 8);
            int dvy1 = (int) (rf(m1) * 8);
            s.items.place(ECM, p);
            int h1 = live(w, Obj.ITEM).get(1);
            p.vx = 1;
            p.vy = 0;
            int seed = seedWhere(r -> {
                skip(r, 2);
                return (int) (r.nextFloat() * 8) >= 2;
            });
            Rng m2 = reseed(w, seed);
            skip(m2, 2);
            int dvx2 = (int) (rf(m2) * 8);
            s.items.place(ECM, p);
            int h2 = live(w, Obj.ITEM).get(2);
            Objects o = w.objects;
            assertAll(
                () -> assertEquals(List.of(-dvx0, -dvy0), List.of((int) o.vx[h0], (int) o.vy[h0]),
                    "at rest (v >= 0 both): v = 0 - dv"),
                () -> assertEquals(List.of((int) (-0.375f + dvx1), (int) (0.1875f - dvy1)),
                    List.of((int) o.vx[h1], (int) o.vy[h1]), "vx < 0: -0.375 + dvx; vy >= 0: 0.1875 - dvy"),
                // |v| = 1 is not < 1: vx = 0.75 - dvx * 0.75 (not 0.75 - dvx)
                () -> assertEquals((int) (0.75f - dvx2 * 0.75f), (int) o.vx[h2], "|v| = 1 uses the scaled push"));
        }

        @Test
        @DisplayName("a drop exactly at 0 stays at 0 and one exactly at the world size wraps to 0")
        void wrapEdges() {
            p.items[ECM] = 2;
            w.items.max[ECM] = 9;
            p.vx = 0;
            p.vy = 0;
            Rng m = reseed(w, 31);
            int kx = (int) (rf(m) * 8);
            int ky = (int) (rf(m) * 8);
            p.prevX = 35 + kx;
            p.prevY = 35 + ky;
            reseed(w, 31);
            s.items.place(ECM, p);
            int h0 = live(w, Obj.ITEM).get(0);
            p.vx = -1;
            p.vy = -1;
            Rng m2 = reseed(w, 33);
            int kx2 = (int) (rf(m2) * 8);
            int ky2 = (int) (rf(m2) * 8);
            p.prevX = 700 - 35 - kx2;
            p.prevY = 700 - 35 - ky2;
            reseed(w, 33);
            s.items.place(ECM, p);
            List<Integer> l = live(w, Obj.ITEM);
            assertAll(
                () -> assertEquals(List.of(0, 0), List.of(w.objects.px[h0], w.objects.py[h0]), "0 is inside"),
                () -> assertEquals(2, l.size(), "the second drop is made"),
                () -> assertEquals(List.of(0, 0), List.of(w.objects.px[l.get(1)], w.objects.py[l.get(1)]),
                    "the world size wraps to 0"));
        }

        @Test
        @DisplayName("drop positions wrap round all four world edges (even on a flat map, as C)")
        void wraps() {
            p.items[ECM] = 2;
            p.prevX = 10;
            p.prevY = 690;
            p.vx = 1;
            p.vy = -1;
            Rng m = reseed(w, 25);
            int kx = (int) (rf(m) * 8);
            int ky = (int) (rf(m) * 8);
            s.items.place(ECM, p);
            int h1 = only();
            int x1 = w.objects.px[h1];
            int y1 = w.objects.py[h1];
            w.objects.free(h1);
            w.itemCount[ECM] = 0;
            p.prevX = 690;
            p.prevY = 10;
            p.vx = -1;
            p.vy = 1;
            Rng m2 = reseed(w, 27);
            int kx2 = (int) (rf(m2) * 8);
            int ky2 = (int) (rf(m2) * 8);
            s.items.place(ECM, p);
            int h2 = only();
            assertAll(
                () -> assertEquals(10 - 35 - kx + 700, x1, "x below 0 wraps up by the width"),
                () -> assertEquals(690 + 35 + ky - 700, y1, "y at or above the height wraps down"),
                () -> assertEquals(690 + 35 + kx2 - 700, w.objects.px[h2], "x at or above the width wraps down"),
                () -> assertEquals(10 - 35 - ky2 + 700, w.objects.py[h2], "y below 0 wraps up"));
        }

        @Test
        @DisplayName("a living player's pack is the drawn size or what it has, whichever is smaller")
        void alivePacks() {
            Simulation s2 = TestWorlds.sim(FRAME + "maxMinesPerPack: 4\n", SimFixture.open(20, 20, "_"));
            World w2 = s2.world;
            Player q = ready(s2, "Q", 0, 350, 350);
            w2.items.max[MINE] = 5;
            int big = seedWhere(r -> 1 + (int) (r.nextFloat() * 4) == 4);
            int small = seedWhere(r -> 1 + (int) (r.nextFloat() * 4) == 2);
            q.items[MINE] = 3;
            reseed(w2, big);
            s2.items.place(MINE, q);
            int left1 = q.items[MINE];
            q.items[MINE] = 5;
            reseed(w2, small);
            s2.items.place(MINE, q);
            assertAll(
                () -> assertEquals(List.of("MINE:3", "MINE:2"), itemList(w2),
                    "a drawn 4 is cut to the 3 it has; a drawn 2 out of 5 stays 2"),
                () -> assertEquals(0, left1, "all 3 are dropped"),
                () -> assertEquals(3, q.items[MINE], "5 - 2 are left"));
        }

        @Test
        @DisplayName("an item over a wall or cannon block is lost: the player's count drops, nothing is made")
        void blocked() {
            for (char c : new char[] {'x', 'r'}) {
                // column 8 (px 280-314) holds the block; prevX 330 drops at 288-295
                Simulation s2 = TestWorlds.sim(FRAME, rows(10, 8, c));
                World w2 = s2.world;
                Player q = ready(s2, "Q", 0, 350, 350);
                q.prevX = 330;
                q.prevY = (20 - 1 - 10) * 35 + 35 + 20; // dropped one block plus up to 7 lower: block row 9
                q.vx = 0;
                q.vy = 0;
                q.items[ECM] = 2;
                s2.items.place(ECM, q);
                assertAll(
                    () -> assertEquals(List.of(), itemList(w2), "nothing is made on a '" + c + "' block"),
                    () -> assertEquals(1, q.items[ECM], "the item is lost anyway"));
            }
        }

        @Test
        @DisplayName("a killed player drops (int)(excess * dropItemOnKillProb) at its position, pushed 0-6 px/frame")
        void killed() {
            p.items[ECM] = 5;
            w.items.initial[ECM] = 1;
            w.items.dropItemOnKillProb = 0.5f;
            w.items.max[ECM] = 5;
            p.status |= Obj.KILLED;
            p.vx = 4;
            p.vy = -3;
            Rng m = reseed(w, 29);
            float vel = (float) (rf(m) * 6);
            int dir = (int) (rf(m) * Const.RES);
            s.items.place(ECM, p);
            int h = only();
            Objects o = w.objects;
            assertAll(
                () -> assertEquals(1, p.items[ECM], "the player keeps the initial count"),
                () -> assertEquals(2, o.counter[h], "a pack of (int)(4 * 0.5)"),
                () -> assertEquals(350, o.px[h], "at the previous x, no offset"),
                () -> assertEquals(350, o.py[h], "at the previous y"),
                () -> assertEquals((int) (4 + Trig.tcos(dir) * vel), (int) o.vx[h], "vx = 4 + tcos(dir) * vel"),
                () -> assertEquals((int) (-3 + Trig.tsin(dir) * vel), (int) o.vy[h], "vy = -3 + tsin(dir) * vel"));
        }

        @Test
        @DisplayName("a killed player's pack below the minimum size drops nothing but the excess is still lost")
        void killedSmall() {
            p.items[ECM] = 2;
            w.items.initial[ECM] = 1;
            w.items.dropItemOnKillProb = 0.5f;
            p.status |= Obj.KILLED;
            s.items.place(ECM, p);
            assertAll(
                () -> assertEquals(List.of(), itemList(w), "(int)(1 * 0.5) = 0 < min_per_pack 1"),
                () -> assertEquals(1, p.items[ECM], "down to the initial count"));
        }

        @Test
        @DisplayName("nothing to lose: no draws and no change (killed: at the initial count; alive: none)")
        void nothing() {
            w.items.initial[ECM] = 2;
            p.items[ECM] = 2;
            p.status |= Obj.KILLED;
            Rng m = reseed(w, 3);
            s.items.place(ECM, p);
            int killedLeft = p.items[ECM];
            p.status &= ~Obj.KILLED;
            p.items[ECM] = 0;
            s.items.place(ECM, p);
            assertAll(
                () -> assertEquals(2, killedLeft, "a killed player at the initial count keeps it"),
                () -> assertEquals(0, p.items[ECM], "a living player with none stays at none"),
                () -> assertEquals(List.of(), itemList(w), "nothing is made"),
                () -> assertEquals(m.state(), w.rng.state(), "no draws"));
        }

        @Test
        @DisplayName("a full pool: a living player loses one item, a killed one keeps its items")
        void fullPool() {
            fillPool(w);
            p.items[ECM] = 3;
            s.items.place(ECM, p);
            int alive = p.items[ECM];
            p.status |= Obj.KILLED;
            s.items.place(ECM, p);
            assertAll(
                () -> assertEquals(2, alive, "the living player's item is lost"),
                () -> assertEquals(2, p.items[ECM], "the killed player's items are untouched"),
                () -> assertEquals(0, w.itemCount[ECM], "nothing is made"));
        }
    }

    // ================================================================ Make_item

    @Nested
    @DisplayName("make (Make_item)")
    class Make {
        @Test
        @DisplayName("an unowned red OBJ_ITEM of mass 10 with the pack size, kind and ITEM_SIZE/2 ranges")
        void fields() {
            Simulation s = open();
            World w = s.world;
            Rng m = reseed(w, 31);
            int life = 1500 + (int) (rf(m) * 512);
            int h = s.items.make(100, 200, 3, -4, MISSILE, 2, Obj.GRAVITY);
            Objects o = w.objects;
            assertAll(
                () -> assertEquals(Obj.ITEM, o.type[h], "type OBJ_ITEM"),
                () -> assertEquals(MISSILE, o.info[h], "info is the kind"),
                () -> assertEquals(2, o.counter[h], "count is the pack size"),
                () -> assertEquals(Records.ObjColor.RED, o.color[h], "red"),
                () -> assertEquals(Obj.GRAVITY, o.status[h], "the given status"),
                () -> assertEquals(Obj.NO_ID, o.owner[h], "no owner"),
                () -> assertEquals(Const.TEAM_NOT_SET, o.team[h], "no team"),
                () -> assertEquals(List.of(100, 200, 100, 200), List.of(o.px[h], o.py[h], o.prevX[h], o.prevY[h]),
                    "at the position, previous position too"),
                () -> assertEquals(List.of(3f, -4f, 0f, 0f), List.of(o.vx[h], o.vy[h], o.accX[h], o.accY[h]),
                    "the velocity, no acceleration"),
                () -> assertEquals(10f, o.mass[h], "mass 10"),
                () -> assertEquals(life, o.life[h], "life 1500 + (int)(rfrac*512)"),
                () -> assertEquals(List.of(8, 8), List.of(o.plRange[h], o.plRadius[h]), "ITEM_SIZE/2"),
                () -> assertEquals(1, w.itemCount[MISSILE], "the world count goes up"));
        }

        @Test
        @DisplayName("at the world maximum or with a full pool nothing is made")
        void limits() {
            Simulation s = open();
            World w = s.world;
            w.items.max[ECM] = 1;
            w.itemCount[ECM] = 1;
            int atMax = s.items.make(10, 10, 0, 0, ECM, 1, 0);
            w.itemCount[ECM] = 0;
            fillPool(w);
            int full = s.items.make(10, 10, 0, 0, ECM, 1, 0);
            assertAll(
                () -> assertEquals(-1, atMax, "the maximum is reached"),
                () -> assertEquals(-1, full, "the pool is full"),
                () -> assertEquals(0, w.itemCount[ECM], "the count is not raised"));
        }
    }

    // ================================================================ Throw_items

    @Nested
    @DisplayName("throwItems (Throw_items)")
    class Throw {
        Simulation s = open();
        World w = s.world;
        Player p = ready(s, "A", 0, 350, 350);

        @Test
        @DisplayName("a killed player throws each kind above the initial count (not fuel or tanks); flags follow")
        void killed() {
            w.items.dropItemOnKillProb = 1;
            Arrays.fill(w.items.max, 9);
            p.status |= Obj.KILLED;
            p.items[ECM] = 3;
            p.items[MINE] = 2;
            p.items[CLOAK] = 1;
            p.items[FUEL] = 5;
            p.items[TANK] = 2;
            p.have |= Has.CLOAKING_DEVICE;
            w.items.initial[MINE] = 1;
            s.items.throwItems(p);
            assertAll(
                () -> assertEquals(Set.of("CLOAK:1", "MINE:1", "ECM:3"), new HashSet<>(itemList(w)),
                    "one pack per kind with the whole excess"),
                () -> assertEquals(List.of(0, 1, 0, 5, 2), List.of(p.items[ECM], p.items[MINE], p.items[CLOAK],
                    p.items[FUEL], p.items[TANK]), "down to the initial counts; fuel and tanks kept"),
                () -> assertEquals(0, p.have & Has.CLOAKING_DEVICE, "Item_update_flags drops the cloak"));
        }

        @Test
        @DisplayName("without dropItemOnKillProb nothing is thrown")
        void off() {
            w.items.dropItemOnKillProb = 0;
            p.status |= Obj.KILLED;
            p.items[ECM] = 3;
            p.items[CLOAK] = 0;
            p.have |= Has.CLOAKING_DEVICE;
            s.items.throwItems(p);
            assertAll(
                () -> assertEquals(3, p.items[ECM], "items kept"),
                () -> assertEquals(List.of(), itemList(w), "nothing made"),
                () -> assertEquals(Has.CLOAKING_DEVICE, p.have & Has.CLOAKING_DEVICE, "flags are not updated"));
        }

        @Test
        @DisplayName("a living player (self-destruct) throws pack after pack while the excess shrinks")
        void alive() {
            w.items.dropItemOnKillProb = 1;
            w.items.max[ECM] = 9;
            p.items[ECM] = 3;
            s.items.throwItems(p);
            assertAll(
                () -> assertEquals(List.of("ECM:1", "ECM:1", "ECM:1"), itemList(w), "three packs of one"),
                () -> assertEquals(0, p.items[ECM], "all thrown"));
        }

        @Test
        @DisplayName("a living player keeps its initial count; at the initial count nothing is thrown")
        void aliveInitial() {
            w.items.dropItemOnKillProb = 1;
            w.items.max[ECM] = 9;
            w.items.initial[ECM] = 1;
            p.items[ECM] = 3;
            s.items.throwItems(p);
            int left = p.items[ECM];
            List<String> thrown = itemList(w);
            s.items.throwItems(p);
            assertAll(
                () -> assertEquals(List.of("ECM:1", "ECM:1"), thrown, "two packs above the initial one"),
                () -> assertEquals(1, left, "the initial one is kept"),
                () -> assertEquals(2, itemList(w).size(), "at the initial count nothing more is thrown"),
                () -> assertEquals(1, p.items[ECM], "still one"));
        }

        @Test
        @DisplayName("full pool: a living player loses all its excess one by one, a killed player stops at once")
        void fullPool() {
            w.items.dropItemOnKillProb = 1;
            fillPool(w);
            p.items[ECM] = 3;
            s.items.throwItems(p);
            int alive = p.items[ECM];
            p.items[ECM] = 3;
            p.status |= Obj.KILLED;
            s.items.throwItems(p);
            assertAll(
                () -> assertEquals(0, alive, "each failed drop still costs the living player one"),
                () -> assertEquals(3, p.items[ECM], "a failed drop leaves the killed player's count, ending the loop"));
        }
    }

    // ================================================================ Detonate_items

    @Nested
    @DisplayName("detonate (Detonate_items)")
    class Detonate {
        Simulation s = TestWorlds.sim(FRAME + "allowNukes: yes\nnukeMinMines: 4\n", SimFixture.open(20, 20, "__"));
        World w = s.world;
        Player p = ready(s, "A", 0, 350, 350);

        @Test
        @DisplayName("nothing for a ship that is not killed")
        void notKilled() {
            p.items[MINE] = 4;
            p.used |= Has.SHIELD;
            s.items.detonate(p);
            assertAll(
                () -> assertEquals(4, p.items[MINE], "mines kept"),
                () -> assertEquals(Has.SHIELD, p.used & Has.SHIELD, "shield kept"),
                () -> assertEquals(List.of(), live(w, Obj.MINE), "no mines"));
        }

        @Test
        @DisplayName("the initial counts are immune (not below 0) and shields drop; probability 0 launches nothing")
        void immune() {
            w.items.initial[MINE] = 1;
            w.items.initial[MISSILE] = 2;
            w.items.detonateItemOnKillProb = 0;
            p.items[MINE] = 4;
            p.items[MISSILE] = 1;
            p.used |= Has.SHIELD;
            p.status |= Obj.KILLED;
            s.items.detonate(p);
            assertAll(
                () -> assertEquals(3, p.items[MINE], "4 - 1 initial"),
                () -> assertEquals(0, p.items[MISSILE], "1 - 2 initial is limited to 0"),
                () -> assertEquals(0, p.used & Has.SHIELD, "shields drop"),
                () -> assertEquals(List.of(), live(w, Obj.MINE), "probability 0: no mines"));
        }

        @Test
        @DisplayName("mines go off as the ship's GRAVITY mines; each one placed shrinks the loop (as C)")
        void mines() {
            w.items.detonateItemOnKillProb = 1;
            p.items[MINE] = 4;
            p.status |= Obj.KILLED;
            p.team = 0;
            s.items.detonate(p);
            List<Integer> mines = live(w, Obj.MINE);
            Objects o = w.objects;
            assertAll(
                () -> assertEquals(2, mines.size(), "i=0 (4 left), i=1 (3 left), then i=2 >= 2: two mines"),
                () -> assertEquals(2, p.items[MINE], "each mine used one"),
                () -> assertEquals(p.id, o.owner[mines.get(0)], "owned by the ship"),
                () -> assertEquals(Obj.GRAVITY, o.status[mines.get(0)] & Obj.GRAVITY, "thrown (GRAVITY)"),
                () -> assertFalse(Mods.isNuclear(o.mods[mines.get(0)]), "not nuclear"));
        }

        @Test
        @DisplayName("a detonated mine gets the ship's velocity plus rfrac*4 in direction rfrac*RES (times MINE_SPEED_FACT)")
        void mineVelocity() {
            w.items.detonateItemOnKillProb = 1;
            p.items[MINE] = 1;
            p.vx = 2;
            p.vy = -1;
            p.status |= Obj.KILLED;
            Rng m = reseed(w, 37);
            skip(m, 1);
            int dir = (int) (rf(m) * Const.RES);
            float vel = (float) (rf(m) * 4.0f);
            s.items.detonate(p);
            int h = live(w, Obj.MINE).get(0);
            assertAll(
                () -> assertEquals((float) ((2 + vel * Trig.tcos(dir)) * 1.3), w.objects.vx[h], "x"),
                () -> assertEquals((float) ((-1 + vel * Trig.tsin(dir)) * 1.3), w.objects.vy[h], "y"));
        }

        @Test
        @DisplayName("nuclear mines stay nuclear only with at least nukeMinMines left")
        void nukes() {
            w.items.detonateItemOnKillProb = 1;
            p.mods = Mods.withNuclear(0, Mods.NUCLEAR);
            p.items[MINE] = 8;
            p.status |= Obj.KILLED;
            s.items.detonate(p);
            List<Integer> big = live(w, Obj.MINE);
            boolean bigNuke = Mods.isNuclear(w.objects.mods[big.get(0)]);
            int left = p.items[MINE];
            Simulation s2 = TestWorlds.sim(FRAME + "allowNukes: yes\nnukeMinMines: 4\n", SimFixture.open(20, 20, "_"));
            Player q = ready(s2, "B", 0, 350, 350);
            s2.world.items.detonateItemOnKillProb = 1;
            q.mods = Mods.withNuclear(0, Mods.NUCLEAR);
            q.items[MINE] = 3;
            q.status |= Obj.KILLED;
            s2.items.detonate(q);
            List<Integer> small = live(s2.world, Obj.MINE);
            assertAll(
                () -> assertEquals(2, big.size(), "8 mines: two nukes of 4"),
                () -> assertTrue(bigNuke, "with 8 >= 4 the mine is nuclear"),
                () -> assertEquals(0, left, "each nuke used nukeMinMines"),
                () -> assertEquals(2, small.size(), "3 mines: plain mines (i=0, i=1)"),
                () -> assertFalse(Mods.isNuclear(s2.world.objects.mods[small.get(0)]),
                    "with 3 < 4 the nuclear modifier is cleared"));
        }

        @Test
        @DisplayName("missiles fire locked on a random player while the shot limit allows")
        void missiles() {
            Player b = ready(s, "B", 0, 600, 600);
            w.items.detonateItemOnKillProb = 1;
            p.items[MISSILE] = 3;
            p.status |= Obj.KILLED;
            p.lockTagged = 0;
            s.items.detonate(p);
            List<Integer> fired = new ArrayList<>();
            fired.addAll(live(w, Obj.TORPEDO));
            fired.addAll(live(w, Obj.HEAT_SHOT));
            fired.addAll(live(w, Obj.SMART_SHOT));
            int n1 = fired.size();
            int owner = n1 > 0 ? w.objects.owner[fired.get(0)] : -9;
            int fromCannon = w.objects.status[fired.get(0)] & Obj.FROMCANNON;
            int team = w.objects.team[fired.get(0)];
            boolean tagged = (p.lockTagged & Player.LOCK_PLAYER) != 0;
            int lock = p.lockPlId;
            Simulation s2 = open();
            Player q = ready(s2, "Q", 0, 350, 350);
            s2.world.items.detonateItemOnKillProb = 1;
            q.items[MISSILE] = 3;
            s2.world.items.allowSmartMissiles = false;
            s2.world.items.allowHeatSeekers = false;
            q.shots = q.shotMax;
            q.status |= Obj.KILLED;
            s2.items.detonate(q);
            assertAll(
                () -> assertEquals(2, n1, "3 missiles: i=0, i=1 fire (each uses one), then the loop ends"),
                () -> assertEquals(p.id, owner, "the ship owns them"),
                // item.c:484 Fire_general_shot(owner_ind, 0, pl->team, ...): team 0, cannon = the ship's team != 0
                () -> assertEquals(Obj.FROMCANNON, fromCannon, "a ship not in team 0 fires them as cannon shots (C)"),
                () -> assertEquals(0, team, "with team 0 (C passes 0 as the team)"),
                () -> assertTrue(tagged, "LOCK_PLAYER is set"),
                () -> assertTrue(lock == p.id || lock == b.id, "locked on one of the players"),
                () -> assertEquals(List.of(), live(s2.world, Obj.TORPEDO), "at the shot limit no torpedo"),
                () -> assertEquals(List.of(), live(s2.world, Obj.HEAT_SHOT), "no heat-seeker"),
                () -> assertEquals(List.of(), live(s2.world, Obj.SMART_SHOT), "no smart missile"),
                () -> assertEquals(3, q.items[MISSILE], "missiles kept at the limit"));
        }

        @Test
        @DisplayName("each missile draws: launch, the locked player's index, the type (rfrac*3), the direction")
        void missileDraws() {
            Player b = ready(s, "B", 0, 600, 600);
            w.items.detonateItemOnKillProb = 1;
            p.items[MISSILE] = 1;
            p.status |= Obj.KILLED;
            int seed = seedWhere(r -> {
                r.nextInt();
                return r.nextFloat() >= 0.5f && (int) (r.nextFloat() * 3) == 1;
            });
            Rng m = reseed(w, seed);
            skip(m, 3);
            int dir = (int) (rf(m) * Const.RES);
            s.items.detonate(p);
            List<Integer> torps = live(w, Obj.HEAT_SHOT);
            assertAll(
                () -> assertEquals(b.id, p.lockPlId, "a draw >= 0.5 of 2 players locks the second"),
                () -> assertEquals(1, torps.size(), "type draw 1 is a heat-seeker"),
                () -> assertEquals(dir, w.objects.missileDir[torps.get(0)], "fired at (int)(rfrac * RES)"));
        }

        @Test
        @DisplayName("nuclear missiles stay nuclear only with at least nukeMinSmarts left")
        void nuclearMissiles() {
            List<Boolean> nuclear = new ArrayList<>();
            for (int n : new int[] {7, 6}) {
                Simulation s2 = TestWorlds.sim(FRAME + "allowNukes: yes\nnukeMinSmarts: 7\n",
                    SimFixture.open(20, 20, "_"));
                Player q = ready(s2, "Q", 0, 350, 350);
                s2.world.items.detonateItemOnKillProb = 1;
                s2.world.items.allowSmartMissiles = false;
                s2.world.items.allowHeatSeekers = false;
                q.mods = Mods.withNuclear(0, Mods.NUCLEAR);
                q.items[MISSILE] = n;
                q.status |= Obj.KILLED;
                s2.items.detonate(q);
                int h = live(s2.world, Obj.TORPEDO).get(0);
                nuclear.add(Mods.isNuclear(s2.world.objects.mods[h]));
            }
            assertEquals(List.of(true, false), nuclear, "7 missiles: nuclear; 6 < 7: plain");
        }

        @Test
        @DisplayName("one shot below the limit missiles still fire")
        void belowShotLimit() {
            w.items.detonateItemOnKillProb = 1;
            w.items.allowSmartMissiles = false;
            w.items.allowHeatSeekers = false;
            p.items[MISSILE] = 1;
            p.shots = p.shotMax - 1;
            p.status |= Obj.KILLED;
            s.items.detonate(p);
            assertEquals(1, live(w, Obj.TORPEDO).size(), "shots < shot_max: fired");
        }

        @Test
        @DisplayName("a team-0 ship's detonated missiles are not cannon shots")
        void teamZero() {
            Simulation s2 = TestWorlds.sim(FRAME + "teamPlay: yes\nreserveRobotTeam: no\n", SimFixture.open(20, 20, "0"));
            Player q = ready(s2, "Q", 0, 350, 350);
            s2.world.items.detonateItemOnKillProb = 1;
            s2.world.items.allowSmartMissiles = false;
            s2.world.items.allowHeatSeekers = false;
            q.items[MISSILE] = 1;
            q.status |= Obj.KILLED;
            s2.items.detonate(q);
            List<Integer> torps = live(s2.world, Obj.TORPEDO);
            assertAll(
                () -> assertEquals(1, torps.size(), "one torpedo"),
                () -> assertEquals(0, s2.world.objects.status[torps.get(0)] & Obj.FROMCANNON, "cannon = team 0 = false"));
        }

        @Test
        @DisplayName("a tank's mines are launched for its owner, who pays for them; without an owner the tank does")
        void tank() {
            Player owner = ready(s, "O", 0, 100, 100);
            w.items.detonateItemOnKillProb = 1;
            p.tank = true;
            p.lockPlId = owner.id;
            p.items[MINE] = 2;
            owner.items[MINE] = 5;
            double fuel0 = owner.fuel;
            p.status |= Obj.KILLED;
            s.items.detonate(p);
            List<Integer> mines = live(w, Obj.MINE);
            Simulation s2 = open();
            Player t = ready(s2, "T", 0, 350, 350);
            s2.world.items.detonateItemOnKillProb = 1;
            t.tank = true;
            t.lockPlId = 77;
            t.items[MINE] = 2;
            t.status |= Obj.KILLED;
            s2.items.detonate(t);
            List<Integer> own = live(s2.world, Obj.MINE);
            assertAll(
                () -> assertEquals(2, mines.size(), "the tank's 2 mines both go off (the tank's count is the bound)"),
                () -> assertEquals(owner.id, w.objects.owner[mines.get(0)], "owned by the tank's owner"),
                () -> assertEquals(List.of(3, 2), List.of(owner.items[MINE], p.items[MINE]),
                    "the owner's mines are used, the tank's are not"),
                () -> assertEquals(fuel0 + 2 * Mines.ED_MINE, owner.fuel, "the owner pays ED_MINE twice"),
                () -> assertEquals(t.id, s2.world.objects.owner[own.get(0)], "an ownerless tank owns its mines"));
        }
    }

    // ================================================================ Item_damage

    @Nested
    @DisplayName("damage (Item_damage)")
    class Damage {
        Simulation s = open();
        World w = s.world;
        Player p = ready(s, "A", 0, 350, 350);

        void stock() {
            Arrays.fill(p.items, 0);
            p.items[ECM] = 4;
            p.items[MIRROR] = 1;
            p.items[FUEL] = 7;
            p.items[TANK] = 3;
            p.have |= Has.MIRROR;
        }

        @Test
        @DisplayName("one draw per kind held (not fuel or tanks); below prob the count becomes (int)(n * prob + 0.5)")
        void hits() {
            stock();
            int seed = seedWhere(r -> r.nextFloat() < 0.25f && r.nextFloat() < 0.25f);
            Rng m = reseed(w, seed);
            s.items.damage(p, 0.25f);
            skip(m, 2);
            assertAll(
                () -> assertEquals(1, p.items[ECM], "(int)(4 * 0.25 + 0.5) = 1"),
                () -> assertEquals(0, p.items[MIRROR], "(int)(1 * 0.25 + 0.5) = 0"),
                () -> assertEquals(List.of(7, 3), List.of(p.items[FUEL], p.items[TANK]), "fuel and tanks untouched"),
                () -> assertEquals(0, p.have & Has.MIRROR, "the last mirror takes the ability (Item_update_flags)"),
                () -> assertEquals(m.state(), w.rng.state(), "exactly two draws: ECM and mirror"));
        }

        @Test
        @DisplayName("draws at or above prob leave the counts")
        void misses() {
            stock();
            int seed = seedWhere(r -> r.nextFloat() >= 0.25f && r.nextFloat() >= 0.25f);
            reseed(w, seed);
            s.items.damage(p, 0.25f);
            assertAll(
                () -> assertEquals(4, p.items[ECM], "ECM kept"),
                () -> assertEquals(1, p.items[MIRROR], "mirror kept"),
                () -> assertEquals(Has.MIRROR, p.have & Has.MIRROR, "ability kept"));
        }

        @Test
        @DisplayName("prob >= 1 does nothing (no draws, no flag update); a negative prob draws but never hits")
        void edges() {
            stock();
            p.items[CLOAK] = 0;
            p.have |= Has.CLOAKING_DEVICE;
            Rng m = reseed(w, 3);
            s.items.damage(p, 1.0f);
            boolean noDraw = m.state() == w.rng.state();
            boolean cloakKept = (p.have & Has.CLOAKING_DEVICE) != 0;
            s.items.damage(p, -0.5f);
            skip(m, 2);
            assertAll(
                () -> assertTrue(noDraw, "prob 1 draws nothing"),
                () -> assertTrue(cloakKept, "prob 1 does not update flags"),
                () -> assertEquals(m.state(), w.rng.state(), "a negative prob still draws for ECM and mirror"),
                () -> assertEquals(List.of(4, 1), List.of(p.items[ECM], p.items[MIRROR]),
                    "loss limited to 0: nothing is lost"),
                () -> assertEquals(0, p.have & Has.CLOAKING_DEVICE, "a prob below 1 updates the flags"));
        }
    }

    // ================================================================ Item_update_flags

    @Nested
    @DisplayName("updateFlags (Item_update_flags)")
    class Flags {
        Simulation s = TestWorlds.sim(FRAME + "allowShields: no\n", SimFixture.open(20, 20, "_"));
        World w = s.world;
        Player p = ready(s, "A", 0, 350, 350);

        static final int ALL = Has.CLOAKING_DEVICE | Has.MIRROR | Has.DEFLECTOR | Has.AFTERBURNER
            | Has.PHASING_DEVICE | Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD | Has.TRACTOR_BEAM | Has.AUTOPILOT
            | Has.ARMOR | Has.SHIELD;

        @Test
        @DisplayName("abilities go with their last item; cloak asks for a visibility update")
        void gone() {
            Arrays.fill(p.items, 0);
            p.shieldTime = 0;
            p.have |= ALL;
            p.used |= Has.SHIELD;
            p.updateVisibility = false;
            s.items.updateFlags(p);
            assertAll(
                () -> assertEquals(0, p.have & ALL, "every item ability (and shields, not a default ability) is gone"),
                () -> assertEquals(0, p.used & Has.SHIELD, "the shield is no longer used either"),
                () -> assertTrue(p.updateVisibility, "losing the cloak updates visibility"));
        }

        @Test
        @DisplayName("abilities stay while their items last")
        void kept() {
            for (int i : new int[] {CLOAK, MIRROR, DEFL, AFTER, PHASING, ETHRUST, ESHIELD, TRACTOR, AUTO, ARMOR}) {
                p.items[i] = 1;
            }
            p.have |= ALL;
            p.updateVisibility = false;
            s.items.updateFlags(p);
            assertAll(
                () -> assertEquals(ALL, p.have & ALL, "all kept"),
                () -> assertFalse(p.updateVisibility, "no visibility update"));
        }

        @Test
        @DisplayName("a cloak not held does not update visibility")
        void noCloak() {
            Arrays.fill(p.items, 0);
            p.have &= ~Has.CLOAKING_DEVICE;
            p.updateVisibility = false;
            s.items.updateFlags(p);
            assertFalse(p.updateVisibility, "no cloak lost, no update");
        }

        @Test
        @DisplayName("phasing, emergency thrust and emergency shield stay while in use or still running")
        void running() {
            Arrays.fill(p.items, 0);
            int three = Has.PHASING_DEVICE | Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD;
            p.have |= three | Has.SHIELD;
            p.used |= three;
            s.items.updateFlags(p);
            int whileUsed = p.have & (three | Has.SHIELD);
            p.used &= ~three;
            p.phasingLeft = 5;
            p.emergencyThrustLeft = 5;
            p.emergencyShieldLeft = 5;
            s.items.updateFlags(p);
            int whileRunning = p.have & (three | Has.SHIELD);
            assertAll(
                () -> assertEquals(three | Has.SHIELD, whileUsed, "kept while used"),
                () -> assertEquals(three | Has.SHIELD, whileRunning, "kept while time is left"));
        }

        @Test
        @DisplayName("the last emergency shield keeps shields with shield time left or with default shields")
        void shieldsKept() {
            Arrays.fill(p.items, 0);
            p.have |= Has.EMERGENCY_SHIELD | Has.SHIELD;
            p.used |= Has.SHIELD;
            p.shieldTime = 1;
            s.items.updateFlags(p);
            int timed = p.have & (Has.EMERGENCY_SHIELD | Has.SHIELD);
            Simulation s2 = open();
            Player q = ready(s2, "Q", 0, 350, 350);
            Arrays.fill(q.items, 0);
            q.have |= Has.EMERGENCY_SHIELD | Has.SHIELD;
            q.used |= Has.SHIELD;
            q.shieldTime = 0;
            s2.items.updateFlags(q);
            assertAll(
                () -> assertEquals(Has.SHIELD, timed, "emergency shield gone, shield kept (shield_time > 0)"),
                () -> assertEquals(Has.SHIELD, q.have & (Has.EMERGENCY_SHIELD | Has.SHIELD),
                    "shield kept when shields are a default ability"),
                () -> assertEquals(Has.SHIELD, q.used & Has.SHIELD, "still in use"));
        }

        @Test
        @DisplayName("without the emergency shield ability, shields are not touched")
        void noEmergencyShield() {
            Arrays.fill(p.items, 0);
            p.shieldTime = 0;
            p.have = (p.have & ~Has.EMERGENCY_SHIELD) | Has.SHIELD;
            p.used |= Has.SHIELD;
            s.items.updateFlags(p);
            assertAll(
                () -> assertEquals(Has.SHIELD, p.have & Has.SHIELD, "have kept"),
                () -> assertEquals(Has.SHIELD, p.used & Has.SHIELD, "used kept"));
        }

        @Test
        @DisplayName("losing the autopilot while it flies switches it off (power restored)")
        void autopilot() {
            Arrays.fill(p.items, 0);
            p.have |= Has.AUTOPILOT;
            p.used |= Has.AUTOPILOT;
            p.autoPowerS = 33;
            p.power = 30;
            s.items.updateFlags(p);
            float off = p.power;
            p.have |= Has.AUTOPILOT;
            p.power = 30;
            s.items.updateFlags(p);
            assertAll(
                () -> assertEquals(33f, off, "Autopilot(off) restores the saved power"),
                () -> assertEquals(0, p.used & Has.AUTOPILOT, "autopilot no longer used"),
                () -> assertEquals(0, p.have & Has.AUTOPILOT, "ability gone"),
                () -> assertEquals(30f, p.power, "an autopilot not in use is not switched off"));
        }
    }

    // ================================================================ keys

    @Nested
    @DisplayName("loseItem (do_lose_item) and selectItem (KEY_SELECT_ITEM)")
    class Keys {
        Simulation s = open();
        World w = s.world;
        Player p = ready(s, "A", 0, 350, 350);

        @Test
        @DisplayName("the selected kind is dropped as a pack and flags are updated")
        void drops() {
            p.items[CLOAK] = 1;
            p.have |= Has.CLOAKING_DEVICE;
            p.loseItem = CLOAK;
            s.items.loseItem(p);
            assertAll(
                () -> assertEquals(List.of("CLOAK:1"), itemList(w), "a cloak pack is dropped"),
                () -> assertEquals(0, p.items[CLOAK], "the cloak is gone"),
                () -> assertEquals(0, p.have & Has.CLOAKING_DEVICE, "and its ability"));
        }

        @Test
        @DisplayName("with loseItemDestroys, or while phased, the item is just destroyed")
        void destroys() {
            p.items[ECM] = 2;
            p.loseItem = ECM;
            w.items.loseItemDestroys = true;
            s.items.loseItem(p);
            int destroyed = p.items[ECM];
            w.items.loseItemDestroys = false;
            p.used |= Has.PHASING_DEVICE;
            p.items[CLOAK] = 1;
            p.have |= Has.CLOAKING_DEVICE;
            p.loseItem = CLOAK;
            s.items.loseItem(p);
            assertAll(
                () -> assertEquals(1, destroyed, "loseItemDestroys: one ECM fewer"),
                () -> assertEquals(0, p.items[CLOAK], "phased: one cloak fewer"),
                () -> assertEquals(0, p.have & Has.CLOAKING_DEVICE, "flags are updated"),
                () -> assertEquals(List.of(), itemList(w), "nothing is dropped"));
        }

        @Test
        @DisplayName("fuel, tanks, items not held and bad selections are not lost")
        void refused() {
            p.items[FUEL] = 3;
            p.items[TANK] = 2;
            p.items[ECM] = 0;
            p.items[CLOAK] = 0;
            p.have |= Has.CLOAKING_DEVICE;
            for (int sel : new int[] {FUEL, TANK, ECM, -1, Item.COUNT}) {
                p.loseItem = sel;
                s.items.loseItem(p);
            }
            assertAll(
                () -> assertEquals(List.of(3, 2, 0), List.of(p.items[FUEL], p.items[TANK], p.items[ECM]),
                    "counts unchanged"),
                () -> assertEquals(List.of(), itemList(w), "nothing dropped"),
                () -> assertEquals(Has.CLOAKING_DEVICE, p.have & Has.CLOAKING_DEVICE, "flags are not updated"));
        }

        @Test
        @DisplayName("select steps to the next held kind, skipping fuel and tanks and wrapping round")
        void select() {
            Arrays.fill(p.items, 0);
            p.items[FUEL] = 1;
            p.items[TANK] = 1;
            p.items[CLOAK] = 1;
            p.items[ARMOR] = 1;
            p.loseItem = CLOAK;
            p.loseItemState = 0;
            s.items.selectItem(p);
            int a = p.loseItem;
            int state = p.loseItemState;
            s.items.selectItem(p);
            int b = p.loseItem;
            p.items[CLOAK] = 0;
            p.items[ARMOR] = 0;
            p.loseItem = SENSOR;
            p.loseItemState = 0;
            s.items.selectItem(p);
            assertAll(
                () -> assertEquals(ARMOR, a, "from cloak on to armor (the kinds between are not held, the tank is skipped)"),
                () -> assertEquals(2, state, "the selection is shown (lose_item_state 2)"),
                () -> assertEquals(CLOAK, b, "from armor round past fuel to cloak"),
                () -> assertEquals(SENSOR, p.loseItem, "with nothing to lose it goes all the way round"),
                () -> assertEquals(0, p.loseItemState, "and is not shown"));
        }
    }

    // ================================================================ pickup

    @Nested
    @DisplayName("pickup (Player_collides_with_item)")
    class Pickup {
        Simulation s = open();
        World w = s.world;
        Player p = ready(s, "A", 0, 350, 350);

        int item(int kind, int count) {
            w.items.max[kind] = 99;
            int h = s.items.make(350, 350, 10, 0, kind, count, 0);
            assertTrue(h >= 0, "item made");
            return h;
        }

        void reset() {
            Arrays.fill(p.items, 0);
            p.have = w.params.defHave;
            p.used = w.params.defUsed & ~Has.SHIELD;
            p.updateVisibility = false;
        }

        @Test
        @DisplayName("each kind adds the pack (limited) and grants its ability; sensors and cloaks update visibility")
        void kinds() {
            int[][] grants = {
                {MIRROR, Has.MIRROR}, {DEFL, Has.DEFLECTOR}, {PHASING, Has.PHASING_DEVICE},
                {AFTER, Has.AFTERBURNER}, {CLOAK, Has.CLOAKING_DEVICE}, {ETHRUST, Has.EMERGENCY_THRUST},
                {TRACTOR, Has.TRACTOR_BEAM}, {AUTO, Has.AUTOPILOT}, {ESHIELD, Has.EMERGENCY_SHIELD},
                {WIDE, 0}, {REAR, 0}, {TRANS, 0}, {MINE, 0}, {MISSILE, 0}, {ECM, 0}, {LASER, 0}, {HYPER, 0},
                {SENSOR, 0},
            };
            List<String> got = new ArrayList<>();
            List<String> want = new ArrayList<>();
            for (int[] g : grants) {
                reset();
                p.have |= Has.SHIELD; // no emergency-shield auto start
                int before = p.have;
                int h = item(g[0], 2);
                s.items.pickup(p, h);
                boolean vis = g[0] == SENSOR || g[0] == CLOAK;
                got.add(Item.of(g[0]) + " n=" + p.items[g[0]] + " gained=" + (p.have & ~before) + " vis="
                    + p.updateVisibility + " life=" + w.objects.life[h]);
                want.add(Item.of(g[0]) + " n=2 gained=" + g[1] + " vis=" + vis + " life=0.0");
            }
            assertEquals(want, got, "count += pack, the ability is granted, the item's life ends");
        }

        @Test
        @DisplayName("with a limit of 0 nothing is added and no ability granted (cloak still updates visibility)")
        void limitZero() {
            List<String> got = new ArrayList<>();
            for (int k : new int[] {MIRROR, DEFL, PHASING, AFTER, CLOAK, ETHRUST, TRACTOR, AUTO, ESHIELD, ARMOR}) {
                reset();
                p.have = 0;
                w.items.limit[k] = 0;
                s.items.pickup(p, item(k, 1));
                got.add(Item.of(k) + ":" + p.items[k] + ":" + p.have);
            }
            assertEquals(List.of("MIRROR:0:0", "DEFLECTOR:0:0", "PHASING:0:0", "AFTERBURNER:0:0",
                "CLOAK:0:0", "EMERGENCY_THRUST:0:0", "TRACTOR_BEAM:0:0", "AUTOPILOT:0:0", "EMERGENCY_SHIELD:0:0",
                "ARMOR:0:0"), got, "LIMIT to 0 and no SET_BIT");
        }

        @Test
        @DisplayName("counts are limited to [0, limit]")
        void limits() {
            reset();
            p.items[MINE] = 9;
            s.items.pickup(p, item(MINE, 3));
            int mines = p.items[MINE];
            p.items[ECM] = -5;
            s.items.pickup(p, item(ECM, 1));
            assertAll(
                () -> assertEquals(10, mines, "9 + 3 over maxMines 10 is 10"),
                () -> assertEquals(0, p.items[ECM], "a negative result is limited to 0"));
        }

        @Test
        @DisplayName("armor adds one whatever the pack size")
        void armor() {
            reset();
            p.items[ARMOR] = 1;
            s.items.pickup(p, item(ARMOR, 3));
            assertAll(
                () -> assertEquals(2, p.items[ARMOR], "armor + 1"),
                () -> assertEquals(Has.ARMOR, p.have & Has.ARMOR, "armor ability"));
        }

        @Test
        @DisplayName("an energy pack adds ENERGY_PACK_FUEL to the current tank")
        void energy() {
            reset();
            int h = item(FUEL, 0);
            double f0 = p.fuel;
            Rng m = reseed(w, 41);
            double add = (500 + (m.nextInt() & 511)) << 8;
            s.items.pickup(p, h);
            assertAll(
                () -> assertEquals(f0 + add, p.fuel, "fuel += ENERGY_PACK_FUEL"),
                () -> assertEquals(p.fuel, p.tankFuel[0], "the main tank holds it"),
                () -> assertEquals(0.0, w.objects.life[h], "picked up"));
        }

        @Test
        @DisplayName("a tank adds a tank with TANK_FUEL(n+1) below the limit, else TANK_FUEL(MAX_TANKS) fuel")
        void tank() {
            reset();
            double f0 = p.fuel;
            int h1 = item(TANK, 1);
            Rng m = reseed(w, 43);
            long tf1 = (221866L * (5 + (m.nextInt() & 3))) / 32;
            s.items.pickup(p, h1);
            int tanks = p.numTanks;
            double tank1 = p.tankFuel[1];
            double sum1 = p.fuel;
            w.items.limit[TANK] = 1;
            int h = item(TANK, 1);
            Rng m2 = reseed(w, 45);
            long tf8 = (221866L * (5 + (m2.nextInt() & 3))) / 32;
            s.items.pickup(p, h);
            assertAll(
                () -> assertEquals(1, tanks, "one tank added"),
                () -> assertEquals((double) tf1, tank1, "with (TANK_CAP(1) * (5 + (rand & 3))) / 32 fuel"),
                () -> assertEquals(f0 + tf1, sum1, "the sum grows by it"),
                () -> assertEquals(1, p.numTanks, "at the limit no tank is added"),
                () -> assertEquals(f0 + tf1 + tf8, p.fuel, "but TANK_FUEL(MAX_TANKS) fuel is"),
                () -> assertEquals(1, p.items[TANK], "the TANK count is the tank count"));
        }

        @Test
        @DisplayName("a second tank gets TANK_FUEL(2)")
        void secondTank() {
            reset();
            s.tanks.add(p, 0);
            int h = item(TANK, 1);
            Rng m = reseed(w, 47);
            long tf2 = (221866L * (5 + (m.nextInt() & 3))) / 32;
            s.items.pickup(p, h);
            assertAll(
                () -> assertEquals(2, p.numTanks, "two tanks"),
                () -> assertEquals((double) tf2, p.tankFuel[2], "TANK_FUEL(num_tanks + 1) with the extra-tank capacity"));
        }

        @Test
        @DisplayName("the first emergency shield on a map without shields turns on at once")
        void firstEmergencyShield() {
            Simulation s2 = TestWorlds.sim(FRAME + "allowShields: no\n", SimFixture.open(20, 20, "__"));
            World w2 = s2.world;
            Player q = ready(s2, "Q", 0, 350, 350);
            w2.items.max[ESHIELD] = 9;
            q.have &= ~(Has.SHIELD | Has.EMERGENCY_SHIELD);
            s2.items.pickup(q, s2.items.make(350, 350, 0, 0, ESHIELD, 1, 0));
            int usedFirst = q.used & Has.EMERGENCY_SHIELD;
            int left = q.emergencyShieldLeft;
            int count = q.items[ESHIELD];
            // a second one, while the first runs (have has ES): no new start
            q.used &= ~Has.EMERGENCY_SHIELD;
            q.emergencyShieldLeft = 0;
            s2.items.pickup(q, s2.items.make(350, 350, 0, 0, ESHIELD, 1, 0));
            int usedSecond = q.used & Has.EMERGENCY_SHIELD;
            // a pack of two as the first: count 2 != 1, no start
            Player r = ready(s2, "R", 0, 100, 100);
            r.have &= ~(Has.SHIELD | Has.EMERGENCY_SHIELD);
            r.items[ESHIELD] = 0;
            s2.items.pickup(r, s2.items.make(100, 100, 0, 0, ESHIELD, 2, 0));
            assertAll(
                () -> assertEquals(Has.EMERGENCY_SHIELD, usedFirst, "Emergency_shield(on)"),
                () -> assertEquals(4 * 60, left, "a 4 s run"),
                () -> assertEquals(0, count, "which used the item"),
                () -> assertEquals(0, usedSecond, "with the ability already held it is not started"),
                () -> assertEquals(0, r.used & Has.EMERGENCY_SHIELD, "a first pack of two is not started"),
                () -> assertEquals(2, r.items[ESHIELD], "both kept"));
        }

        @Test
        @DisplayName("with shields available the first emergency shield is not started")
        void shieldsAvailable() {
            reset();
            p.have |= Has.SHIELD;
            s.items.pickup(p, item(ESHIELD, 1));
            assertAll(
                () -> assertEquals(0, p.used & Has.EMERGENCY_SHIELD, "not started"),
                () -> assertEquals(1, p.items[ESHIELD], "kept"));
        }

        @Test
        @DisplayName("at the offensive limit the ship bounces off (Delta_mv) and the item stays")
        void offensiveFull() {
            reset();
            w.items.maxOffensiveItems = 5;
            p.items[WIDE] = 2;
            p.items[MINE] = 3;
            p.vx = 0;
            int h = item(MISSILE, 1);
            double life = w.objects.life[h];
            float m = p.mass;
            s.items.pickup(p, h);
            assertAll(
                () -> assertEquals(0, p.items[MISSILE], "nothing picked up"),
                () -> assertEquals(life, w.objects.life[h], "the item lives on"),
                () -> assertEquals((0 * m + 10f * 10f) / (m + 10f), p.vx, "inelastic: (0*m + 10*10)/(m + 10)"),
                () -> assertEquals(p.vx, w.objects.vx[h], "the item moves with the ship"));
        }

        @Test
        @DisplayName("a pack bigger than the offensive room is cut; a single item or a fitting pack is not")
        void offensiveCut() {
            reset();
            w.items.maxOffensiveItems = 5;
            p.items[MINE] = 3;
            int h = item(MISSILE, 4);
            s.items.pickup(p, h);
            int cut = w.objects.counter[h];
            int missiles = p.items[MISSILE];
            reset();
            p.items[MINE] = 1;
            int h2 = item(MISSILE, 4);
            s.items.pickup(p, h2);
            reset();
            p.items[MINE] = 4;
            int h3 = item(LASER, 1);
            s.items.pickup(p, h3);
            assertAll(
                () -> assertEquals(2, cut, "count = 5 - 3"),
                () -> assertEquals(2, missiles, "two missiles"),
                () -> assertEquals(4, w.objects.counter[h2], "1 + 4 = 5 fits"),
                () -> assertEquals(1, p.items[LASER], "a single item at 4 of 5 is taken"));
        }

        @Test
        @DisplayName("defensive items count tanks and running emergency shield/phasing against the limit")
        void defensive() {
            reset();
            w.items.maxDefensiveItems = 3;
            p.items[CLOAK] = 1;
            p.numTanks = 1;
            p.emergencyShieldLeft = 5;
            int h = item(ECM, 1);
            p.vx = 0;
            float mass = p.mass;
            s.items.pickup(p, h);
            int full = p.items[ECM];
            float bounced = p.vx;
            double life = w.objects.life[h];
            p.emergencyShieldLeft = 0;
            p.phasingLeft = 0;
            int h2 = item(ECM, 3);
            s.items.pickup(p, h2);
            int ecm = p.items[ECM];
            reset();
            p.numTanks = 0;
            p.phasingLeft = 5;
            p.items[CLOAK] = 2;
            int h3 = item(TANK, 1);
            s.items.pickup(p, h3);
            assertAll(
                () -> assertEquals(0, full, "cloak + tank + running shield = 3: no room"),
                () -> assertEquals((0 * mass + 10f * 10f) / (mass + 10f), bounced, "the ship bounces off (Delta_mv)"),
                () -> assertTrue(life > 0, "the item stays"),
                () -> assertEquals(1, w.objects.counter[h2], "a pack of 3 is cut to the 1 left"),
                () -> assertEquals(1, ecm, "one ECM taken"),
                () -> assertEquals(0, p.numTanks, "2 cloaks + running phasing = 3: no tank"),
                () -> assertTrue(w.objects.life[h3] > 0, "the tank stays"));
        }

        @Test
        @DisplayName("fuel is neither offensive nor defensive: limits of 0 do not stop an energy pack")
        void fuelUncapped() {
            reset();
            w.items.maxOffensiveItems = 0;
            w.items.maxDefensiveItems = 0;
            int h = item(FUEL, 0);
            s.items.pickup(p, h);
            assertEquals(0.0, w.objects.life[h], "picked up");
        }

        @Test
        @DisplayName("CountOffensiveItems and CountDefensiveItems add the right kinds")
        void counts() {
            reset();
            for (int i = 0; i < Item.COUNT; i++) {
                p.items[i] = 1 << i;
            }
            p.numTanks = 3;
            int off = (1 << WIDE) + (1 << REAR) + (1 << MINE) + (1 << MISSILE) + (1 << LASER);
            int def = (1 << CLOAK) + (1 << ECM) + (1 << ARMOR) + (1 << TRANS) + (1 << TRACTOR) + (1 << ESHIELD)
                + 3 + (1 << DEFL) + (1 << HYPER) + (1 << PHASING) + (1 << MIRROR);
            int d0 = Items.countDefensive(p);
            p.emergencyShieldLeft = 1;
            int d1 = Items.countDefensive(p);
            p.phasingLeft = 1;
            int d2 = Items.countDefensive(p);
            assertAll(
                () -> assertEquals(off, Items.countOffensive(p), "wide-angle, rear, mines, missiles, lasers"),
                () -> assertEquals(def, d0, "the defensive kinds plus the tank count"),
                () -> assertEquals(def + 1, d1, "a running emergency shield counts one"),
                () -> assertEquals(def + 2, d2, "running phasing counts one more"));
        }
    }

    // ================================================================ Delete_shot

    @Nested
    @DisplayName("delete (Delete_shot, OBJ_ITEM)")
    class Delete {
        Simulation s = TestWorlds.sim(FRAME + "allowClusters: yes\nallowModifiers: yes\n",
            SimFixture.open(20, 20, "_"));
        World w = s.world;

        int item(int kind) {
            w.items.max[kind] = 9;
            return s.items.make(200, 300, 0, 0, kind, 1, 0);
        }

        @Test
        @DisplayName("an expiring missile or mine pack turns white for FPS * WARN_TIME frames first")
        void warns() {
            int hm = item(MISSILE);
            int hn = item(MINE);
            w.objects.life[hm] = 0;
            w.objects.life[hn] = 0;
            Rng m = reseed(w, 3);
            s.items.delete(hm);
            s.items.delete(hn);
            Objects o = w.objects;
            assertAll(
                () -> assertTrue(o.isLive(hm) && o.isLive(hn), "both stay"),
                () -> assertEquals(List.of(Records.ObjColor.WHITE, Records.ObjColor.WHITE),
                    List.of(o.color[hm], o.color[hn]), "white"),
                () -> assertEquals(List.of(120.0, 120.0), List.of(o.life[hm], o.life[hn]), "60 fps * 2 s"),
                () -> assertEquals(List.of(1, 1), List.of(w.itemCount[MISSILE], w.itemCount[MINE]), "still counted"),
                () -> assertEquals(m.state(), w.rng.state(), "no draws"));
        }

        @Test
        @DisplayName("a white missile pack becomes a rogue heat-seeker with rogueHeatProb at its position")
        void rogueHeat() {
            int h = item(MISSILE);
            w.objects.color[h] = Records.ObjColor.WHITE;
            w.objects.life[h] = 0;
            w.items.rogueHeatProb = 1;
            Rng m = reseed(w, 19);
            skip(m, 5); // rogue, cluster, implosion, velocity, power
            int dir = (int) (rf(m) * Const.RES);
            s.items.delete(h);
            List<Integer> heats = live(w, Obj.HEAT_SHOT);
            assertAll(
                () -> assertFalse(w.objects.isLive(h) && w.objects.type[h] == Obj.ITEM, "the item is freed"),
                () -> assertEquals(0, w.itemCount[MISSILE], "the count drops"),
                () -> assertEquals(1, heats.size(), "one heat-seeker"),
                () -> assertEquals(Obj.NO_ID, w.objects.owner[heats.get(0)], "unowned"),
                () -> assertEquals(Const.TEAM_NOT_SET, w.objects.team[heats.get(0)], "no team"),
                () -> assertEquals(dir, w.objects.missileDir[heats.get(0)], "fired at (int)(rfrac * RES)"));
        }

        @Test
        @DisplayName("a white mine pack becomes a rogue mine with rogueMineProb, modifiers drawn as C")
        void rogueMine() {
            int h = item(MINE);
            w.objects.color[h] = Records.ObjColor.WHITE;
            w.objects.life[h] = 0;
            w.items.rogueMineProb = 1;
            int seed = seedWhere(r -> {
                r.nextInt();
                return r.nextFloat() <= 0.333f && r.nextFloat() <= 0.333f && r.nextFloat() >= 0.5f;
            });
            Rng m = reseed(w, seed);
            skip(m, 3); // rogue, cluster, implosion
            int velocity = (int) (rf(m) * (Mods.VELOCITY_MAX + 1));
            int power = (int) (rf(m) * (Mods.POWER_MAX + 1));
            s.items.delete(h);
            List<Integer> mines = live(w, Obj.MINE);
            int mods = w.objects.mods[mines.get(0)];
            assertAll(
                () -> assertEquals(1, mines.size(), "one rogue mine"),
                () -> assertEquals(0, w.itemCount[MINE], "the count drops"),
                () -> assertEquals(Obj.NO_ID, w.objects.owner[mines.get(0)], "unowned"),
                () -> assertEquals(List.of(200, 300), List.of(w.objects.px[mines.get(0)], w.objects.py[mines.get(0)]),
                    "at the item's position"),
                () -> assertTrue(Mods.isCluster(mods), "cluster (rfrac <= 0.333)"),
                () -> assertTrue(Mods.isImplosion(mods), "implosion (rfrac <= 0.333)"),
                () -> assertEquals(power, Mods.power(mods), "power (int)(rfrac * (POWER_MAX + 1))"),
                () -> assertEquals(velocity, Mods.velocity(mods), "velocity (int)(rfrac * (VELOCITY_MAX + 1)), >= 2"));
        }

        @Test
        @DisplayName("modifier draws above 0.333 give no cluster or implosion; without the rules nothing is drawn")
        void rogueModsOff() {
            int h = item(MINE);
            w.objects.color[h] = Records.ObjColor.WHITE;
            w.objects.life[h] = 0;
            w.items.rogueMineProb = 1;
            int seed = seedWhere(r -> {
                r.nextInt();
                return r.nextFloat() > 0.333f && r.nextFloat() > 0.333f;
            });
            reseed(w, seed);
            s.items.delete(h);
            int mods = w.objects.mods[live(w, Obj.MINE).get(0)];
            Simulation s2 = open();
            World w2 = s2.world;
            w2.items.max[MINE] = 9;
            int h2 = s2.items.make(200, 300, 0, 0, MINE, 1, 0);
            w2.objects.color[h2] = Records.ObjColor.WHITE;
            w2.objects.life[h2] = 0;
            w2.items.rogueMineProb = 1;
            Rng m = reseed(w2, 5);
            skip(m, 1);
            boolean grav = rf(m) < 0.5f;
            s2.items.delete(h2);
            int h3 = live(w2, Obj.MINE).get(0);
            assertAll(
                () -> assertFalse(Mods.isCluster(mods), "no cluster above 0.333"),
                () -> assertFalse(Mods.isImplosion(mods), "no implosion above 0.333"),
                () -> assertEquals(0, w2.objects.mods[h3], "no rules: no modifiers"),
                () -> assertEquals(grav ? Obj.GRAVITY : 0, w2.objects.status[h3] & Obj.GRAVITY,
                    "the gravity draw is the second one (GRAVITY below 0.5)"));
        }

        @Test
        @DisplayName("rogue probabilities 0, other kinds and picked-up packs are just deleted")
        void plain() {
            int hm = item(MISSILE);
            int hn = item(MINE);
            int he = item(ECM);
            int hp = item(MISSILE);
            w.objects.color[hm] = Records.ObjColor.WHITE;
            w.objects.color[hn] = Records.ObjColor.WHITE;
            w.objects.life[hm] = 0;
            w.objects.life[hn] = 0;
            w.objects.life[he] = 0;
            w.objects.life[hp] = -1; // picked up: life 0, then expiry took one more
            w.items.rogueHeatProb = 0;
            w.items.rogueMineProb = 0;
            Rng m = reseed(w, 3);
            s.items.delete(hm);
            s.items.delete(hn);
            s.items.delete(he);
            s.items.delete(hp);
            skip(m, 2);
            assertAll(
                () -> assertEquals(List.of(), live(w, Obj.ITEM), "all freed"),
                () -> assertEquals(List.of(), live(w, Obj.MINE), "no rogue mine"),
                () -> assertEquals(List.of(), live(w, Obj.HEAT_SHOT), "no rogue heat-seeker"),
                () -> assertEquals(List.of(0, 0, 0), List.of(w.itemCount[MISSILE], w.itemCount[MINE], w.itemCount[ECM]),
                    "counts drop"),
                () -> assertEquals(m.state(), w.rng.state(),
                    "only the two expired white packs draw (the ECM and the picked-up pack do not)"));
        }

        @Test
        @DisplayName("a rogue draw at or above the probability spawns nothing")
        void rogueMiss() {
            int h = item(MINE);
            w.objects.color[h] = Records.ObjColor.WHITE;
            w.objects.life[h] = 0;
            w.items.rogueMineProb = 0.5f;
            int seed = seedWhere(r -> r.nextFloat() >= 0.5f);
            reseed(w, seed);
            s.items.delete(h);
            int h2 = item(MISSILE);
            w.objects.color[h2] = Records.ObjColor.WHITE;
            w.objects.life[h2] = 0;
            w.items.rogueHeatProb = 0.5f;
            reseed(w, seed);
            s.items.delete(h2);
            assertAll(
                () -> assertEquals(List.of(), live(w, Obj.MINE), "no rogue mine"),
                () -> assertEquals(List.of(), live(w, Obj.HEAT_SHOT), "no rogue heat-seeker"));
        }
    }

    @Test
    @DisplayName("an expiring item in the tick goes through Delete_shot (messages are not involved)")
    void expiryInTick() {
        Simulation s = open();
        World w = s.world;
        w.items.max[ECM] = 9;
        int h = s.items.make(200, 300, 0, 0, ECM, 1, 0);
        w.objects.life[h] = 1;
        long seq = w.events.seq();
        TestWorlds.run(s, 1, 0);
        assertAll(
            () -> assertEquals(0, w.itemCount[ECM], "the ECM expired and was uncounted"),
            () -> assertEquals(List.of(), messages(w, seq), "silently"));
    }
}
