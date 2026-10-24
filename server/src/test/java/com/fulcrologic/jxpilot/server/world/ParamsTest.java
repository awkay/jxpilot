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

import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.server.TestWorlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Params: C globals after Move_init (walls.c), Set_world_rules / Set_initial_resources (rules.c), map.c robots. */
class ParamsTest {
    /** 11x11 blocks = 385x385 px: hypotenuse (int)hypot(385, 385) = 544. Two bases. */
    static final String[] MAP = {
        "xxxxxxxxxxx",
        "x         x",
        "x         x",
        "x         x",
        "x         x",
        "x  _  _   x",
        "x         x",
        "x         x",
        "x         x",
        "x         x",
        "xxxxxxxxxxx",
    };

    static Params params(String extra) {
        Options o = TestWorlds.options(extra, MAP);
        return Params.from(o, WorldMap.fromOptions(o));
    }

    static int initial(Params p, Item i) {
        return p.initialItems[i.ordinal()];
    }

    @Nested
    @DisplayName("from: time and defaults")
    class Defaults {
        @Test
        void defaultsFollowCmdlineC() {
            Params p = params("");
            assertAll(
                () -> assertEquals(14, p.fps, "framesPerSecond defaults to 14"),
                () -> assertEquals(544.0, p.hypotenuse, "(int) hypot of the world in pixels"),
                () -> assertEquals(20.0, p.shipMass, "shipMass 20"),
                () -> assertEquals(1000.0, p.initialFuel, "initialFuel 1000"),
                () -> assertEquals(Obj.ITEM | Obj.BALL | Obj.ASTEROID, p.objBounceMask,
                    "items, balls and asteroids bounce by default"),
                () -> assertEquals(Has.DEF_HAVE, p.defHave, "abilities are DEF_HAVE"),
                () -> assertEquals(Has.DEF_USED, p.defUsed, "used abilities are DEF_USED"),
                () -> assertEquals(Obj.KILLING_SHOTS, p.killingShots, "player killing is on"));
        }

        @Test
        void floatOptionsAreCFloats() {
            Params p = params("framesPerSecond: 60\nfriction: 0.1\nshotSpeed: 21.3\n");
            assertAll(
                () -> assertEquals(60, p.fps, "the tick rate is framesPerSecond"),
                () -> assertEquals(0.1f, p.friction, "DFLOAT options are rounded to float like C"),
                () -> assertEquals(21.3f, p.shotSpeed, "shotSpeed is a C float"));
        }
    }

    @Nested
    @DisplayName("resolve: Move_init limits")
    class MoveInit {
        @Test
        void bounceLimitsAreClampedAndAnglesConvertedToRes() {
            Params p = params("maxObjectWallBounceSpeed: 1000\nmaxShieldedWallBounceSpeed: -5\n"
                + "maxUnshieldedWallBounceSpeed: 30\nmaxShieldedPlayerWallBounceAngle: 200\n"
                + "maxUnshieldedPlayerWallBounceAngle: -10\nplayerWallBounceBrakeFactor: 2\n"
                + "objectWallBounceBrakeFactor: -1\nobjectWallBounceLifeFactor: 0.5\nwallBounceFuelDrainMult: 2000\n");
            assertAll(
                () -> assertEquals(544.0, p.maxObjectWallBounceSpeed, "speed limited to the hypotenuse"),
                () -> assertEquals(0.0, p.maxShieldedWallBounceSpeed, "speed raised to 0"),
                () -> assertEquals(30.0, p.maxUnshieldedWallBounceSpeed, "an in-range speed is kept"),
                () -> assertEquals(64, p.maxShieldedAngle, "200 deg is limited to 180 = 64 RES"),
                () -> assertEquals(0, p.maxUnshieldedAngle, "-10 deg is raised to 0"),
                () -> assertEquals(1.0, p.playerWallBrakeFactor, "brake factor limited to 1"),
                () -> assertEquals(0.0, p.objectWallBrakeFactor, "brake factor raised to 0"),
                () -> assertEquals(0.5, p.objectWallBounceLifeFactor, "an in-range life factor is kept"),
                () -> assertEquals(1000.0, p.wallBounceFuelDrainMult, "fuel drain limited to 1000"),
                () -> assertEquals((float) Math.sqrt(1000), p.wallBounceExplosionMult, "explosion mult = sqrt(drain), a C float"));
        }

        @Test
        void defaultAnglesConvertWithTruncation() {
            Params p = params("maxUnshieldedPlayerWallBounceAngle: 100\n");
            assertAll(
                () -> assertEquals(32, p.maxShieldedAngle, "90 deg = 32 RES"),
                () -> assertEquals(35, p.maxUnshieldedAngle, "100 * 128 / 360 = 35.6 truncates to 35"));
        }

        @Test
        void everyBounceOptionAddsItsObjectTypes() {
            Params p = params("sparksWallBounce: yes\ndebrisWallBounce: yes\nshotsWallBounce: yes\n"
                + "itemsWallBounce: no\nmissilesWallBounce: yes\nminesWallBounce: yes\nballsWallBounce: no\n"
                + "asteroidsWallBounce: no\n");
            Params none = params("itemsWallBounce: no\nballsWallBounce: no\nasteroidsWallBounce: no\n");
            int expected = Obj.SPARK | Obj.DEBRIS | Obj.SHOT | Obj.CANNON_SHOT | Obj.SMART_SHOT | Obj.TORPEDO
                | Obj.HEAT_SHOT | Obj.MINE;
            assertAll(
                () -> assertEquals(expected, p.objBounceMask, "sparks, debris, shots, missiles and mines"),
                () -> assertEquals(0, none.objBounceMask, "nothing bounces when every option is off"),
                () -> assertEquals(expected | Obj.BALL | Obj.PULSE, p.objTreasureMask,
                    "treasures: bouncing objects plus balls and pulses"));
        }

        @Test
        void crashMasksFollowMoveInit() {
            Params p = params("");
            Params noKill = params("allowPlayerKilling: no\n");
            Params items = params("cannonsUseItems: yes\n");
            int cannon = Obj.KILLING_SHOTS | Obj.MINE | Obj.SHOT | Obj.PULSE | Obj.SMART_SHOT | Obj.TORPEDO
                | Obj.HEAT_SHOT | Obj.ASTEROID;
            int noKillCannon = Obj.MINE | Obj.SHOT | Obj.PULSE | Obj.SMART_SHOT | Obj.TORPEDO | Obj.HEAT_SHOT
                | Obj.ASTEROID;
            assertAll(
                () -> assertEquals(cannon, p.objCannonMask, "cannons are hit by killing shots and the listed types"),
                () -> assertEquals(cannon | Obj.BALL | Obj.SPARK, p.objTargetMask, "targets add balls and sparks"),
                () -> assertEquals(0, noKill.killingShots, "without player killing no shot kills (Set_world_rules)"),
                () -> assertEquals(noKillCannon, noKill.objCannonMask, "cannon shots then stop hitting cannons"),
                () -> assertEquals(cannon | Obj.ITEM, items.objCannonMask, "cannonsUseItems adds items"));
        }
    }

    @Nested
    @DisplayName("resolve: abilities and robots")
    class Rules {
        @Test
        void withoutShieldsNobodyHasThem() {
            Params p = params("allowShields: no\n");
            assertAll(
                () -> assertEquals(Has.DEF_HAVE & ~Has.SHIELD, p.defHave, "HAS_SHIELD is removed"),
                () -> assertEquals(Has.COMPASS, p.defUsed, "DEF_USED &= DEF_HAVE"));
        }

        @Test
        void initialItemsGrantTheirAbilities() {
            Params p = params("initialCloaks: 1\ninitialEmergencyThrusts: 1\ninitialEmergencyShields: 1\n"
                + "initialPhasings: 1\ninitialTractorBeams: 1\ninitialAutopilots: 1\ninitialDeflectors: 1\n"
                + "initialMirrors: 1\ninitialArmor: 1\n");
            int granted = Has.CLOAKING_DEVICE | Has.EMERGENCY_THRUST | Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE
                | Has.TRACTOR_BEAM | Has.AUTOPILOT | Has.DEFLECTOR | Has.MIRROR | Has.ARMOR;
            assertAll(
                () -> assertEquals(Has.DEF_HAVE | granted, p.defHave, "each initial item grants its HAS_ bit"),
                () -> assertEquals(Has.DEF_USED, p.defUsed, "but none is used by default"));
        }

        @Test
        void robotCountsResolveMinusOne() {
            Params all = params("maxRobots: -1\n");
            Params some = params("maxRobots: 3\nminRobots: 1\n");
            assertAll(
                () -> assertEquals(2, all.maxRobots, "maxRobots -1 = one per base"),
                () -> assertEquals(2, all.minRobots, "minRobots -1 = maxRobots"),
                () -> assertEquals(3, some.maxRobots, "explicit maxRobots is kept"),
                () -> assertEquals(1, some.minRobots, "explicit minRobots is kept"));
        }
    }

    @Nested
    @DisplayName("readItems / initial fuel: Set_initial_resources limits")
    class InitialResources {
        @Test
        void initialItemsAreLimitedByTheirMaximumsWhichAreLimitedByC() {
            Params p = params("initialArmor: 50\nmaxArmor: 10\ninitialMines: 150\nmaxMines: 200\n"
                + "initialAfterburners: 20\nmaxAfterburners: 30\ninitialTanks: 10\nmaxTanks: 20\n"
                + "initialMissiles: -3\ninitialCloaks: 4\nmaxCloaks: -1\ninitialLasers: 3\n");
            assertAll(
                () -> assertEquals(10, initial(p, Item.ARMOR), "initialArmor limited to maxArmor"),
                () -> assertEquals(99, initial(p, Item.MINE), "maxMines limited to 99, then the initial"),
                () -> assertEquals(15, initial(p, Item.AFTERBURNER), "afterburners at most MAX_AFTERBURNER 15"),
                () -> assertEquals(8, initial(p, Item.TANK), "tanks at most MAX_TANKS 8"),
                () -> assertEquals(0, initial(p, Item.MISSILE), "a negative initial count is 0"),
                () -> assertEquals(0, initial(p, Item.CLOAK), "a negative maximum allows none"),
                () -> assertEquals(3, initial(p, Item.LASER), "an in-range count is kept"),
                () -> assertEquals(0, initial(p, Item.FUEL), "fuel is not an item count"));
        }

        @Test
        void everyItemUsesItsOwnOptions() {
            Params p = params("initialTanks: 1\ninitialECMs: 2\ninitialArmor: 3\ninitialMines: 4\ninitialMissiles: 5\n"
                + "initialCloaks: 6\ninitialSensors: 7\ninitialWideangles: 8\ninitialRearshots: 9\n"
                + "initialAfterburners: 10\ninitialTransporters: 1\ninitialMirrors: 2\ninitialDeflectors: 3\n"
                + "initialHyperJumps: 4\ninitialPhasings: 5\ninitialLasers: 4\ninitialEmergencyThrusts: 7\n"
                + "initialTractorBeams: 3\ninitialAutopilots: 9\ninitialEmergencyShields: 10\n");
            int[] expected = new int[Item.COUNT];
            expected[Item.TANK.ordinal()] = 1;
            expected[Item.ECM.ordinal()] = 2;
            expected[Item.ARMOR.ordinal()] = 3;
            expected[Item.MINE.ordinal()] = 4;
            expected[Item.MISSILE.ordinal()] = 5;
            expected[Item.CLOAK.ordinal()] = 6;
            expected[Item.SENSOR.ordinal()] = 7;
            expected[Item.WIDEANGLE.ordinal()] = 8;
            expected[Item.REARSHOT.ordinal()] = 9;
            expected[Item.AFTERBURNER.ordinal()] = 10;
            expected[Item.TRANSPORTER.ordinal()] = 1;
            expected[Item.MIRROR.ordinal()] = 2;
            expected[Item.DEFLECTOR.ordinal()] = 3;
            expected[Item.HYPERJUMP.ordinal()] = 4;
            expected[Item.PHASING.ordinal()] = 5;
            expected[Item.LASER.ordinal()] = 4;
            expected[Item.EMERGENCY_THRUST.ordinal()] = 7;
            expected[Item.TRACTOR_BEAM.ordinal()] = 3;
            expected[Item.AUTOPILOT.ordinal()] = 9;
            expected[Item.EMERGENCY_SHIELD.ordinal()] = 10;
            assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(p.initialItems),
                "each initialXxx option fills its own item (all within their default maximums)");
        }

        @Test
        void defaultMaximumsLimitLasersAndTractorBeams() {
            Params p = params("initialLasers: 9\ninitialTractorBeams: 9\n");
            assertAll(
                () -> assertEquals(5, initial(p, Item.LASER), "maxLasers defaults to 5"),
                () -> assertEquals(4, initial(p, Item.TRACTOR_BEAM), "maxTractorBeams defaults to 4"));
        }

        @Test
        void initialFuelIsLimitedByMaxFuelWhichIsLimitedTo10000() {
            assertAll(
                () -> assertEquals(10000.0, params("initialFuel: 20000\nmaxFuel: 50000\n").initialFuel,
                    "maxFuel is at most MAX_FUEL 10000"),
                () -> assertEquals(500.0, params("maxFuel: 500\n").initialFuel, "initialFuel at most maxFuel"),
                () -> assertEquals(0.0, params("initialFuel: -5\n").initialFuel, "initialFuel at least 0"),
                () -> assertEquals(0.0, params("maxFuel: -5\n").initialFuel, "a negative maxFuel allows none"),
                () -> assertEquals(2000.0, params("initialFuel: 2000\n").initialFuel, "an in-range value is kept"));
        }
    }
}
