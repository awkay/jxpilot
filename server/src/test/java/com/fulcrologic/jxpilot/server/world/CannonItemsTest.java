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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.Rng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("CannonItems (cannon.c Cannon_add_item, Cannon_init)")
class CannonItemsTest {
    static final int FUEL = Item.FUEL.ordinal();
    static final int TANK = Item.TANK.ordinal();
    static final int MINE = Item.MINE.ordinal();

    /** C ENERGY_PACK_FUEL >> FUEL_SCALE_BITS: 500 + (randomMT() & 511). */
    static int pack(Rng r) {
        return 500 + (r.nextInt() & 511);
    }

    static ItemParams params() {
        ItemParams ip = new ItemParams();
        ip.limit[FUEL] = 10000;
        ip.limit[TANK] = 8;
        ip.limit[MINE] = 5;
        return ip;
    }

    @Nested
    @DisplayName("addItem")
    class AddItem {
        @Test
        @DisplayName("ordinary items add the amount within 0..limit")
        void ordinary() {
            CannonItems ci = new CannonItems(2);
            ItemParams ip = params();
            Rng rng = new Rng(1);
            ci.addItem(ip, rng, 1, MINE, 3);
            int three = ci.item(1, MINE);
            ci.addItem(ip, rng, 1, MINE, 4);
            int capped = ci.item(1, MINE);
            ci.addItem(ip, rng, 1, MINE, -10);
            assertAll(
                () -> assertEquals(3, three, "3 mines are added"),
                () -> assertEquals(5, capped, "3 + 4 is limited to maxMines 5"),
                () -> assertEquals(0, ci.item(1, MINE), "a negative result is limited to 0"),
                () -> assertEquals(0, ci.item(0, MINE), "another cannon is not touched"),
                () -> assertEquals(new Rng(1).nextInt(), rng.nextInt(), "ordinary items draw no random numbers"));
        }

        @Test
        @DisplayName("fuel units become whole packs; the limit (in packs) is drawn once, twice when it applies")
        void fuel() {
            CannonItems ci = new CannonItems(1);
            ItemParams ip = params();
            Rng rng = new Rng(5);
            Rng ref = new Rng(5);
            int p1 = pack(ref);
            int max1 = (int) (10000 / pack(ref) + 0.5);
            int expect = 2000 / p1;
            ci.addItem(ip, rng, 0, FUEL, 2000);
            int got = ci.item(0, FUEL);
            int afterTwo = rng.nextInt();
            int refTwo = ref.nextInt();
            ci.set(0, FUEL, 40);
            rng.seed(9);
            ref.seed(9);
            int p2 = pack(ref);
            int max2 = (int) (10000 / pack(ref) + 0.5);
            int capped = (int) (10000 / pack(ref) + 0.5);
            ci.addItem(ip, rng, 0, FUEL, 600);
            int over = ci.item(0, FUEL);
            int afterThree = rng.nextInt();
            int refThree = ref.nextInt();
            ci.set(0, FUEL, 0);
            ci.addItem(ip, rng, 0, FUEL, -5000);
            assertAll(
                () -> assertEquals(expect, got, "2000 units / one pack (integer division) packs are added"),
                () -> assertEquals(true, expect <= max1, "below the limit"),
                () -> assertEquals(refTwo, afterTwo, "below the limit: two pack draws (amount and limit)"),
                () -> assertEquals(true, 40 + 600 / p2 > max2, "40 packs is above the limit of about 13"),
                () -> assertEquals(capped, over, "above the limit: C's LIMIT draws the limit again for the value"),
                () -> assertEquals(refThree, afterThree, "above the limit: three pack draws"),
                () -> assertEquals(0, ci.item(0, FUEL), "a negative result is limited to 0"));
        }

        @Test
        @DisplayName("exactly at the fuel limit nothing is capped and only two packs are drawn")
        void fuelAtLimit() {
            CannonItems ci = new CannonItems(2);
            ItemParams ip = params();
            Rng ref = new Rng(5);
            int p1 = pack(ref);
            int max = (int) (10000 / pack(ref) + 0.5);
            int add = 3000 / p1;
            ci.set(1, FUEL, max - add);
            Rng rng = new Rng(5);
            ci.addItem(ip, rng, 1, FUEL, 3000);
            assertAll(
                () -> assertEquals(max, ci.item(1, FUEL), "v == limit is kept (C compares val > hi)"),
                () -> assertEquals(ref.nextInt(), rng.nextInt(), "no third draw at the limit"),
                () -> assertEquals(0, ci.item(0, FUEL), "cannon 0 is untouched"));
        }

        @Test
        @DisplayName("add and item address the right cannon")
        void addressing() {
            CannonItems ci = new CannonItems(3);
            ci.add(2, Item.ARMOR, 4);
            ci.add(1, Item.ARMOR, 1);
            assertAll(
                () -> assertEquals(4, ci.item(2, Item.ARMOR), "cannon 2 has 4 armors"),
                () -> assertEquals(1, ci.item(1, Item.ARMOR), "cannon 1 has 1 armor"),
                () -> assertEquals(0, ci.item(0, Item.ARMOR), "cannon 0 has none"),
                () -> assertEquals(4, ci.item(2, Item.ARMOR.ordinal()), "the ordinal accessor agrees"));
        }

        @Test
        @DisplayName("a tank adds one tank within its limit and then its fuel as packs")
        void tank() {
            CannonItems ci = new CannonItems(1);
            ItemParams ip = params();
            Rng rng = new Rng(3);
            Rng ref = new Rng(3);
            int p = pack(ref);
            ci.addItem(ip, rng, 0, TANK, 3000);
            int tanks = ci.item(0, TANK);
            int fuel = ci.item(0, FUEL);
            ci.set(0, TANK, 8);
            ci.addItem(ip, rng, 0, TANK, 0);
            assertAll(
                () -> assertEquals(1, tanks, "one tank is added"),
                () -> assertEquals(3000 / p, fuel, "its 3000 units are added as packs"),
                () -> assertEquals(8, ci.item(0, TANK), "the tank count is limited to maxTanks"));
        }
    }

    @Nested
    @DisplayName("init and clear")
    class Init {
        @Test
        @DisplayName("without cannonsUseItems everything is zero, no tractor target, and no numbers are drawn")
        void noItems() {
            CannonItems ci = new CannonItems(1);
            ci.set(0, MINE, 4);
            ci.used[0] = Has.EMERGENCY_SHIELD;
            ci.damaged[0] = 3;
            ci.tractorCount[0] = 2;
            ci.tractorIsPressor[0] = true;
            ci.emergencyShieldLeft[0] = 9;
            ci.phasingLeft[0] = 8;
            Rng rng = new Rng(4);
            ci.init(0, params(), rng, false);
            assertAll(
                () -> assertEquals(0, ci.item(0, MINE), "items are cleared"),
                () -> assertEquals(0, ci.used[0], "no device is in use"),
                () -> assertEquals(0, ci.damaged[0], "no damage"),
                () -> assertEquals(-1, ci.tractorTarget[0], "no tractor target (-1)"),
                () -> assertEquals(0, ci.tractorCount[0], "no tractor beam"),
                () -> assertEquals(false, ci.tractorIsPressor[0], "not a pressor"),
                () -> assertEquals(0, ci.emergencyShieldLeft[0], "no emergency shield"),
                () -> assertEquals(0, ci.phasingLeft[0], "no phasing"),
                () -> assertEquals(new Rng(4).nextInt(), rng.nextInt(), "no numbers are drawn"));
        }

        @Test
        @DisplayName("with cannonsUseItems each kind gets (int)(rfrac * (initial + 1)), and always one tank (C quirk)")
        void withItems() {
            CannonItems ci = new CannonItems(1);
            ItemParams ip = params();
            ip.initial[MINE] = 3;
            ip.initial[FUEL] = 1000;
            Rng rng = new Rng(11);
            Rng ref = new Rng(11);
            int fuel = 0;
            int mines = 0;
            for (int i = 0; i < Item.COUNT; i++) {
                int n = (int) (ref.nextFloat() * (ip.initial[i] + 1));
                if (i == FUEL || i == TANK) {
                    int v = fuel + n / pack(ref);
                    int max = (int) (10000 / pack(ref) + 0.5);
                    fuel = v > max ? (int) (10000 / pack(ref) + 0.5) : v;
                } else if (i == MINE) {
                    mines = n;
                }
            }
            int f = fuel;
            int m = mines;
            ci.init(0, ip, rng, true);
            assertAll(
                () -> assertEquals(m, ci.item(0, MINE), "mines: (int)(rfrac * 4)"),
                () -> assertEquals(f, ci.item(0, FUEL), "fuel: (int)(rfrac * 1001) units as packs"),
                () -> assertEquals(1, ci.item(0, TANK), "Cannon_add_item(TANK, 0) still adds a tank"),
                () -> assertEquals(0, ci.item(0, Item.LASER.ordinal()), "kinds without initial items get none"),
                () -> assertEquals(ref.nextInt(), rng.nextInt(), "one rfrac per kind plus the pack draws"));
        }

        @Test
        @DisplayName("init draws (int)(rfrac * (initial + 1)): a high draw gives the full initial count")
        void initialCount() {
            ItemParams ip = params();
            ip.initial[MINE] = 3;
            // draws before the mine's: fuel (rfrac + 2 packs), 6 kinds, tank (rfrac + 2 packs) = 12
            int seed = 1;
            while (true) {
                Rng r = new Rng(seed);
                for (int i = 0; i < 12; i++) {
                    r.nextInt();
                }
                if (r.nextFloat() >= 0.75f) {
                    break;
                }
                seed++;
            }
            CannonItems ci = new CannonItems(1);
            ci.init(0, ip, new Rng(seed), true);
            assertEquals(3, ci.item(0, MINE), "(int)(r * 4) = 3 for r >= 0.75");
        }

        @Test
        @DisplayName("clear zeroes every count and timer, tractor target included")
        void clear() {
            CannonItems ci = new CannonItems(2);
            for (int i = 0; i < Item.COUNT; i++) {
                ci.set(1, i, i + 1);
                ci.set(0, i, 7);
            }
            ci.used[1] = 5;
            ci.damaged[1] = 5;
            ci.tractorTarget[1] = 5;
            ci.tractorCount[1] = 5;
            ci.tractorIsPressor[1] = true;
            ci.emergencyShieldLeft[1] = 5;
            ci.phasingLeft[1] = 5;
            ci.clear(1);
            int sum = 0;
            for (int i = 0; i < Item.COUNT; i++) {
                sum += ci.item(1, i);
            }
            int total = sum;
            assertAll(
                () -> assertEquals(0, total, "every item count is 0"),
                () -> assertEquals(7, ci.item(0, Item.ARMOR.ordinal()), "the other cannon keeps its items"),
                () -> assertEquals(0, ci.used[1] + ci.damaged[1] + ci.tractorTarget[1] + ci.tractorCount[1]
                    + ci.emergencyShieldLeft[1] + ci.phasingLeft[1], "every timer and the tractor target are 0"),
                () -> assertEquals(false, ci.tractorIsPressor[1], "not a pressor"));
        }
    }
}
