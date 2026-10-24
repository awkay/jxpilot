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
import com.fulcrologic.jxpilot.server.TestWorlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** ItemParams: rules.c Set_world_items / Init_item / Set_item_chance / Set_misc_item_limits / Set_initial_resources. */
@DisplayName("ItemParams (rules.c item table)")
class ItemParamsTest {
    /** Option name stems in Item ordinal order: itemXxxProb, initialXxx(s), maxXxx(s). */
    static final String[][] NAMES = {
        {"itemEnergyPackProb", "initialFuel", "maxFuel"},
        {"itemWideangleProb", "initialWideangles", "maxWideangles"},
        {"itemRearshotProb", "initialRearshots", "maxRearshots"},
        {"itemAfterburnerProb", "initialAfterburners", "maxAfterburners"},
        {"itemCloakProb", "initialCloaks", "maxCloaks"},
        {"itemSensorProb", "initialSensors", "maxSensors"},
        {"itemTransporterProb", "initialTransporters", "maxTransporters"},
        {"itemTankProb", "initialTanks", "maxTanks"},
        {"itemMineProb", "initialMines", "maxMines"},
        {"itemMissileProb", "initialMissiles", "maxMissiles"},
        {"itemECMProb", "initialECMs", "maxECMs"},
        {"itemLaserProb", "initialLasers", "maxLasers"},
        {"itemEmergencyThrustProb", "initialEmergencyThrusts", "maxEmergencyThrusts"},
        {"itemTractorBeamProb", "initialTractorBeams", "maxTractorBeams"},
        {"itemAutopilotProb", "initialAutopilots", "maxAutopilots"},
        {"itemEmergencyShieldProb", "initialEmergencyShields", "maxEmergencyShields"},
        {"itemDeflectorProb", "initialDeflectors", "maxDeflectors"},
        {"itemHyperJumpProb", "initialHyperJumps", "maxHyperJumps"},
        {"itemPhasingProb", "initialPhasings", "maxPhasings"},
        {"itemMirrorProb", "initialMirrors", "maxMirrors"},
        {"itemArmorProb", "initialArmor", "maxArmor"},
    };

    static final String[] MAP = {"    ", "    ", "    ", " _  "};

    /** The item table for the options on a 20x20-block map at 14 fps (diagonal (int)hypot(20, 20) = 28). */
    static ItemParams from(String extra) {
        return ItemParams.from(TestWorlds.options(extra, MAP), 20, 20, 14);
    }

    static List<Integer> list(int[] a) {
        return Arrays.stream(a).boxed().toList();
    }

    static List<Float> list(float[] a) {
        Float[] b = new Float[a.length];
        for (int i = 0; i < a.length; i++) {
            b[i] = a[i];
        }
        return List.of(b);
    }

    static int ord(Item i) {
        return i.ordinal();
    }

    @Nested
    @DisplayName("from: the per-item table (Set_world_items, Set_initial_resources)")
    class Table {
        @Test
        @DisplayName("each kind reads its own probability option; pack sizes follow Set_world_items")
        void probsAndPacks() {
            StringBuilder sb = new StringBuilder("maxMinesPerPack: 3\nmaxMissilesPerPack: 5\n");
            for (int i = 0; i < Item.COUNT; i++) {
                sb.append(NAMES[i][0]).append(": ").append((i + 1) / 1024.0).append('\n');
            }
            ItemParams p = from(sb.toString());
            Float[] probs = new Float[Item.COUNT];
            for (int i = 0; i < Item.COUNT; i++) {
                probs[i] = (i + 1) / 1024.0f;
            }
            assertAll(
                () -> assertEquals(List.of(probs), list(p.prob), "prob[i] is the itemXxxProb option of kind i"),
                // rules.c:302-322: fuel 0-0, mines 1-maxMinesPerPack, missiles 1-maxMissilesPerPack, others 1-1
                () -> assertEquals(List.of(0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
                    list(p.minPerPack), "every kind but fuel has packs of at least one"),
                () -> assertEquals(List.of(0, 1, 1, 1, 1, 1, 1, 1, 3, 5, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
                    list(p.maxPerPack), "mines and missiles come in packs up to their maxXxxPerPack"),
                () -> assertEquals(3, p.maxMinesPerPack, "maxMinesPerPack is kept"),
                () -> assertEquals(5, p.maxMissilesPerPack, "maxMissilesPerPack is kept"));
        }

        @Test
        @DisplayName("limits are capped at C's maxima and initial counts at the limits")
        void limitsAbove() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Item.COUNT; i++) {
                sb.append(NAMES[i][2]).append(": 20000\n").append(NAMES[i][1]).append(": 30000\n");
            }
            ItemParams p = from(sb.toString());
            // rules.c:46-64 MAX_FUEL 10000, MAX_TANKS 8, MAX_AFTERBURNER 15, everything else 99
            List<Integer> expected = List.of(10000, 99, 99, 15, 99, 99, 99, 8, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
                99, 99, 99);
            assertAll(
                () -> assertEquals(expected, list(p.limit), "limit = min(maxXxx, C maximum)"),
                () -> assertEquals(expected, list(p.initial), "initial = min(initialXxx, limit)"));
        }

        @Test
        @DisplayName("negative limits and initial counts become 0; values inside the limits are kept")
        void limitsBelowAndInside() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Item.COUNT; i++) {
                boolean even = i % 2 == 0;
                sb.append(NAMES[i][2]).append(even ? ": -1\n" : ": 7\n");
                sb.append(NAMES[i][1]).append(even ? ": 5\n" : (i % 4 == 1 ? ": 3\n" : ": -4\n"));
            }
            ItemParams p = from(sb.toString());
            int[] lim = new int[Item.COUNT];
            int[] ini = new int[Item.COUNT];
            for (int i = 0; i < Item.COUNT; i++) {
                lim[i] = i % 2 == 0 ? 0 : 7;
                ini[i] = i % 2 == 0 ? 0 : (i % 4 == 1 ? 3 : 0);
            }
            assertAll(
                () -> assertEquals(list(lim), list(p.limit), "a negative maxXxx limits to 0, 7 is kept"),
                () -> assertEquals(list(ini), list(p.initial),
                    "initial is limited to [0, limit]: 5 over a 0 limit is 0, 3 is kept, -4 is 0"));
        }

        @Test
        @DisplayName("the table's chances and maxima come from Set_item_chance for every kind")
        void chancesForEveryKind() {
            // 20x20 blocks, 14 fps; prob 1/1024, itemProbMult 1: chance = (int)(1/(1/1024*400*14)) = (int)0.18 -> 1;
            // maxItemDensity 1/64: max = (int)(400/64) = 6
            StringBuilder sb = new StringBuilder("itemProbMult: 1\nmaxItemDensity: 0.015625\n");
            for (int i = 0; i < Item.COUNT; i++) {
                sb.append(NAMES[i][0]).append(": ").append(i == ord(Item.CLOAK) ? "0" : "0.0009765625").append('\n');
            }
            ItemParams p = from(sb.toString());
            int[] chance = new int[Item.COUNT];
            Arrays.fill(chance, 1);
            chance[ord(Item.CLOAK)] = 0;
            int[] max = new int[Item.COUNT];
            Arrays.fill(max, 6);
            assertAll(
                () -> assertEquals(list(chance), list(p.chance), "every kind with a probability gets chance 1, cloak 0"),
                () -> assertEquals(list(max), list(p.max), "every kind gets max 6"));
        }
    }

    @Nested
    @DisplayName("from: rules, weapons, scoring and item-world options")
    class Scalars {
        @Test
        @DisplayName("boolean rules are read from their options")
        void rules() {
            ItemParams on = from("allowNukes: yes\nallowClusters: yes\nallowModifiers: yes\nallowLaserModifiers: yes\n"
                + "allowSmartMissiles: yes\nallowHeatSeekers: yes\nallowTorpedoes: yes\nallowShipShapes: yes\n"
                + "limitedVisibility: yes\ncloakedShield: yes\nlockOtherTeam: yes\nloseItemDestroys: yes\n"
                + "shieldedItemPickup: yes\nshieldedMining: yes\nlaserIsStunGun: yes\nidentifyMines: yes\n"
                + "distinguishMissiles: yes\necmsReprogramMines: yes\necmsReprogramRobots: yes\n"
                + "playersOnRadar: yes\nmissilesOnRadar: yes\nminesOnRadar: yes\nnukesOnRadar: yes\n"
                + "cannonsDefend: yes\n");
            ItemParams off = from("allowNukes: no\nallowClusters: no\nallowModifiers: no\nallowLaserModifiers: no\n"
                + "allowSmartMissiles: no\nallowHeatSeekers: no\nallowTorpedoes: no\nallowShipShapes: no\n"
                + "limitedVisibility: no\ncloakedShield: no\nlockOtherTeam: no\nloseItemDestroys: no\n"
                + "shieldedItemPickup: no\nshieldedMining: no\nlaserIsStunGun: no\nidentifyMines: no\n"
                + "distinguishMissiles: no\necmsReprogramMines: no\necmsReprogramRobots: no\n"
                + "playersOnRadar: no\nmissilesOnRadar: no\nminesOnRadar: no\nnukesOnRadar: no\n"
                + "cannonsDefend: no\n");
            boolean[] a = {on.allowNukes, on.allowClusters, on.allowModifiers, on.allowLaserModifiers,
                on.allowSmartMissiles, on.allowHeatSeekers, on.allowTorpedoes, on.allowShipShapes, on.limitedVisibility,
                on.cloakedShield, on.lockOtherTeam, on.loseItemDestroys, on.shieldedItemPickup, on.shieldedMining,
                on.laserIsStunGun, on.identifyMines, on.distinguishMissiles, on.ecmsReprogramMines,
                on.ecmsReprogramRobots, on.playersOnRadar, on.missilesOnRadar, on.minesOnRadar, on.nukesOnRadar,
                on.cannonsDefend};
            boolean[] b = {off.allowNukes, off.allowClusters, off.allowModifiers, off.allowLaserModifiers,
                off.allowSmartMissiles, off.allowHeatSeekers, off.allowTorpedoes, off.allowShipShapes,
                off.limitedVisibility, off.cloakedShield, off.lockOtherTeam, off.loseItemDestroys,
                off.shieldedItemPickup, off.shieldedMining, off.laserIsStunGun, off.identifyMines,
                off.distinguishMissiles, off.ecmsReprogramMines, off.ecmsReprogramRobots, off.playersOnRadar,
                off.missilesOnRadar, off.minesOnRadar, off.nukesOnRadar, off.cannonsDefend};
            boolean[] allTrue = new boolean[a.length];
            Arrays.fill(allTrue, true);
            assertAll(
                () -> assertEquals(Arrays.toString(allTrue), Arrays.toString(a), "every rule is on when its option is"),
                () -> assertEquals(Arrays.toString(new boolean[b.length]), Arrays.toString(b),
                    "every rule is off when its option is"));
        }

        @Test
        @DisplayName("weapon, scoring and item-world numbers are read (probabilities limited to [0, 1] as C)")
        void numbers() {
            ItemParams p = from("nukeMinSmarts: 3\nnukeMinMines: 5\nnukeClusterDamage: 0.5\nmineFuseTime: 2\n"
                + "mineLife: 77\nminMineSpeed: 1.5\nmissileLife: 88\nbaseMineRange: 4\nmineShotDetonateDistance: 9\n"
                + "torpedoKillScoreMult: 0.25\nsmartKillScoreMult: 0.375\nheatKillScoreMult: 0.625\n"
                + "laserKillScoreMult: 0.75\nmineScoreMult: 0.875\n"
                + "movingItemProb: 0.25\nrandomItemProb: 0.5\ndropItemOnKillProb: 0.75\ndetonateItemOnKillProb: 0.125\n"
                + "destroyItemInCollisionProb: 0.375\nwallBounceDestroyItemProb: 0.625\nitemProbMult: 2.5\n"
                + "cannonItemProbMult: 1.5\nmaxItemDensity: 0.5\nitemConcentratorRadius: 12\n"
                + "itemConcentratorProb: 0.875\nrogueHeatProb: 0.0625\nrogueMineProb: 0.1875\n"
                + "maxOffensiveItems: 11\nmaxDefensiveItems: 13\ntankScoreDecrement: 250\ngravity: -0.5\n"
                + "framesPerSecond: 14\n");
            assertAll(
                () -> assertEquals(3, p.nukeMinSmarts, "nukeMinSmarts"),
                () -> assertEquals(5, p.nukeMinMines, "nukeMinMines"),
                () -> assertEquals(0.5f, p.nukeClusterDamage, "nukeClusterDamage"),
                () -> assertEquals(28, p.mineFuseTime, "mineFuseTime is in frames: 2 s * 14 fps"),
                () -> assertEquals(77, p.mineLife, "mineLife"),
                () -> assertEquals(1.5f, p.minMineSpeed, "minMineSpeed"),
                () -> assertEquals(88, p.missileLife, "missileLife"),
                () -> assertEquals(4, p.baseMineRange, "baseMineRange"),
                () -> assertEquals(9, p.mineShotDetonateDistance, "mineShotDetonateDistance"),
                () -> assertEquals(List.of(0.25f, 0.375f, 0.625f, 0.75f, 0.875f),
                    List.of(p.torpedoKillScoreMult, p.smartKillScoreMult, p.heatKillScoreMult, p.laserKillScoreMult,
                        p.mineScoreMult), "torpedo, smart, heat, laser kill and mine score multipliers"),
                () -> assertEquals(List.of(0.25f, 0.5f, 0.75f, 0.125f, 0.375f, 0.625f),
                    List.of(p.movingItemProb, p.randomItemProb, p.dropItemOnKillProb, p.detonateItemOnKillProb,
                        p.destroyItemInCollisionProb, p.wallBounceDestroyItemProb), "item-world probabilities"),
                () -> assertEquals(List.of(2.5f, 1.5f, 0.5f, 0.875f, 0.0625f, 0.1875f),
                    List.of(p.itemProbMult, p.cannonItemProbMult, p.maxItemDensity, p.itemConcentratorProb,
                        p.rogueHeatProb, p.rogueMineProb), "multipliers, density and rogue probabilities"),
                () -> assertEquals(12, p.itemConcentratorRadius, "a radius inside [1, diagonal] is kept"),
                () -> assertEquals(List.of(11, 13, 250), List.of(p.maxOffensiveItems, p.maxDefensiveItems,
                    p.tankScoreDecrement), "item caps and tankScoreDecrement"),
                () -> assertEquals(-0.5f, p.gravity, "Gravity (for Place_item's extra factor)"));
        }

        @Test
        @DisplayName("Set_misc_item_limits: probabilities above 1 become 1; wallBounceDestroyItemProb and rogue probs are not limited")
        void probsAbove() {
            ItemParams p = from("movingItemProb: 2\nrandomItemProb: 3\ndropItemOnKillProb: 4\n"
                + "detonateItemOnKillProb: 5\ndestroyItemInCollisionProb: 6\nitemConcentratorProb: 7\n"
                + "wallBounceDestroyItemProb: 2\nrogueHeatProb: 3\nrogueMineProb: 4\nitemConcentratorRadius: 29\n");
            assertAll(
                () -> assertEquals(List.of(1f, 1f, 1f, 1f, 1f, 1f),
                    List.of(p.movingItemProb, p.randomItemProb, p.dropItemOnKillProb, p.detonateItemOnKillProb,
                        p.destroyItemInCollisionProb, p.itemConcentratorProb), "LIMIT(prob, 0, 1) caps at 1"),
                () -> assertEquals(List.of(2f, 3f, 4f), List.of(p.wallBounceDestroyItemProb, p.rogueHeatProb,
                    p.rogueMineProb), "C does not limit these"),
                () -> assertEquals(28, p.itemConcentratorRadius, "the radius is limited to the diagonal 28"));
        }

        @Test
        @DisplayName("Set_misc_item_limits: negative probabilities become 0, exactly 0 and 1 are kept")
        void probsBelowAndEdges() {
            ItemParams neg = from("movingItemProb: -1\nrandomItemProb: -2\ndropItemOnKillProb: -3\n"
                + "detonateItemOnKillProb: -4\ndestroyItemInCollisionProb: -5\nitemConcentratorProb: -6\n"
                + "itemConcentratorRadius: 0\n");
            ItemParams edge = from("movingItemProb: 0\nrandomItemProb: 1\ndropItemOnKillProb: 0\n"
                + "detonateItemOnKillProb: 1\ndestroyItemInCollisionProb: 0\nitemConcentratorProb: 1\n"
                + "itemConcentratorRadius: 28\n");
            ItemParams one = from("itemConcentratorRadius: 1\n");
            assertAll(
                () -> assertEquals(List.of(0f, 0f, 0f, 0f, 0f, 0f),
                    List.of(neg.movingItemProb, neg.randomItemProb, neg.dropItemOnKillProb, neg.detonateItemOnKillProb,
                        neg.destroyItemInCollisionProb, neg.itemConcentratorProb), "LIMIT(prob, 0, 1) raises to 0"),
                () -> assertEquals(List.of(0f, 1f, 0f, 1f, 0f, 1f),
                    List.of(edge.movingItemProb, edge.randomItemProb, edge.dropItemOnKillProb,
                        edge.detonateItemOnKillProb, edge.destroyItemInCollisionProb, edge.itemConcentratorProb),
                    "the limits themselves are kept"),
                () -> assertEquals(1, neg.itemConcentratorRadius, "a radius below 1 becomes 1"),
                () -> assertEquals(28, edge.itemConcentratorRadius, "a radius equal to the diagonal is kept"),
                () -> assertEquals(1, one.itemConcentratorRadius, "a radius of 1 is kept"));
        }
    }

    @Nested
    @DisplayName("setChance (Set_item_chance)")
    class SetChance {
        /** A fresh table with exactly representable inputs on a 16x16 map at 16 fps. */
        ItemParams p(float mult, float density) {
            ItemParams p = new ItemParams();
            p.itemProbMult = mult;
            p.maxItemDensity = density;
            return p;
        }

        @Test
        @DisplayName("chance = max(1, (int)(1 / (itemProbMult * prob * blocks * FPS))) when positive, else 0")
        void chance() {
            ItemParams p = p(0.5f, 0);
            int fuel = ord(Item.FUEL);
            int ecm = ord(Item.ECM);
            int mine = ord(Item.MINE);
            int tank = ord(Item.TANK);
            p.prob[fuel] = 1 / 65536f;   // 0.5/65536 * 256 * 16 = 1/32 -> 32
            p.prob[ecm] = 1 / 512f;      // 0.5/512 * 4096 = 4 -> (int)0.25 = 0 -> 1
            p.prob[mine] = 0;            // never
            p.prob[tank] = -1 / 64f;     // negative: never
            for (int i : new int[] {fuel, ecm, mine, tank}) {
                p.setChance(i, 16, 16, 16);
            }
            ItemParams zero = p(0, 0);
            zero.prob[fuel] = 1;
            zero.setChance(fuel, 16, 16, 16);
            ItemParams exact = p(1, 0);
            exact.prob[fuel] = 1 / 4096f; // 1/4096 * 4096 = 1 -> chance exactly 1
            exact.setChance(fuel, 16, 16, 16);
            assertAll(
                () -> assertEquals(32, p.chance[fuel], "1 / (0.5 * 2^-16 * 256 * 16) = 32"),
                () -> assertEquals(1, p.chance[ecm], "a chance below 1 is raised to 1"),
                () -> assertEquals(1, exact.chance[fuel], "a product of exactly 1 gives chance 1"),
                () -> assertEquals(0, p.chance[mine], "probability 0 never spawns"),
                () -> assertEquals(0, p.chance[tank], "a negative probability never spawns"),
                () -> assertEquals(0, zero.chance[fuel], "itemProbMult 0 never spawns"));
        }

        @Test
        @DisplayName("max = (int)(itemProbMult * maxItemDensity * blocks), 1 for a positive fraction, else 0")
        void max() {
            int fuel = ord(Item.FUEL);
            ItemParams four = p(1, 1 / 64f);           // 256/64 = 4
            ItemParams frac = p(1, 1 / 1024f);         // 0.25 -> 1
            ItemParams twoHalf = p(0.625f, 1 / 64f);   // 2.5 -> 2
            ItemParams one = p(1, 1 / 256f);           // exactly 1
            ItemParams none = p(1, 0);
            ItemParams neg = p(-1, 1 / 64f);
            for (ItemParams q : List.of(four, frac, twoHalf, one, none, neg)) {
                q.setChance(fuel, 16, 16, 16);
            }
            assertAll(
                () -> assertEquals(4, four.max[fuel], "1/64 per block on 256 blocks allows 4"),
                () -> assertEquals(1, frac.max[fuel], "a positive maximum below 1 allows 1"),
                () -> assertEquals(2, twoHalf.max[fuel], "2.5 truncates to 2"),
                () -> assertEquals(1, one.max[fuel], "exactly 1 allows 1"),
                () -> assertEquals(0, none.max[fuel], "density 0 allows none"),
                () -> assertEquals(0, neg.max[fuel], "a negative product allows none"));
        }

        @Test
        @DisplayName("cannonProb = prob * (n / sum) * (maxItemDensity / 0.00012) over the cannon items with prob > 0")
        void cannonProb() {
            ItemParams p = p(1, 0.00024f);
            int fuel = ord(Item.FUEL);
            int mine = ord(Item.MINE);
            int cloak = ord(Item.CLOAK);
            int laser = ord(Item.LASER);
            p.prob[fuel] = 0.25f;
            p.prob[mine] = 0.5f;
            p.prob[cloak] = 1;       // not a cannon item: not in the sum
            p.prob[laser] = -0.125f; // not positive: not in the sum
            for (int i : new int[] {fuel, mine, cloak, laser}) {
                p.setChance(i, 16, 16, 16);
            }
            // rules.c Set_item_chance: num = 2 (fuel, mine), sum = 0.75
            float fuelExpected = (float) (0.25f * (2 / 0.75f) * (0.00024f / 0.00012));
            float mineExpected = (float) (0.5f * (2 / 0.75f) * (0.00024f / 0.00012));
            float laserExpected = (float) (-0.125f * (2 / 0.75f) * (0.00024f / 0.00012));
            assertAll(
                () -> assertEquals(fuelExpected, p.cannonProb[fuel], "fuel: 0.25 * 2/0.75 * 2"),
                () -> assertEquals(mineExpected, p.cannonProb[mine], "mine: 0.5 * 2/0.75 * 2"),
                () -> assertEquals(laserExpected, p.cannonProb[laser],
                    "a cannon item with a negative probability still gets its (negative) weight"),
                () -> assertEquals(0f, p.cannonProb[cloak], "an item cannons cannot use gets 0"));
        }

        @Test
        @DisplayName("cannonProb is 0 (not NaN) when no cannon item has a positive probability")
        void cannonProbNone() {
            ItemParams p = p(1, 0.00012f);
            int fuel = ord(Item.FUEL);
            p.prob[ord(Item.CLOAK)] = 1;
            p.prob[fuel] = 0;
            p.cannonProb[fuel] = 5;
            p.setChance(fuel, 16, 16, 16);
            assertEquals(0f, p.cannonProb[fuel], "num == 0 gives cannonprob 0");
        }

        @Test
        @DisplayName("CANNON_USE_ITEM holds exactly the kinds cannon.c lets cannons use")
        void cannonUseItem() {
            int mask = 0;
            for (Item i : List.of(Item.FUEL, Item.WIDEANGLE, Item.REARSHOT, Item.AFTERBURNER, Item.SENSOR,
                Item.TRANSPORTER, Item.TANK, Item.MINE, Item.ECM, Item.LASER, Item.EMERGENCY_THRUST, Item.ARMOR,
                Item.TRACTOR_BEAM, Item.MISSILE, Item.PHASING)) {
                mask |= i.bit();
            }
            assertEquals(mask, ItemParams.CANNON_USE_ITEM, "cannon.c:68 CANNON_USE_ITEM");
        }
    }
}
