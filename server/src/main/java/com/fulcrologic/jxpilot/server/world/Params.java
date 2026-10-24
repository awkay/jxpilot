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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;

/**
 * Game parameters resolved once from {@link Options} at map load: the C globals of {@code cmdline.c}
 * after the limits applied by {@code Move_init} (walls.c), {@code Set_world_rules} and
 * {@code Set_initial_resources} (rules.c) and the robot count resolution in {@code map.c:933-938}.
 * Plain public fields so tests can tweak a copy.
 * <p>
 * The simulation runs one tick per C frame at {@link #fps} (SPEC "Time model"), so every per-frame C rule
 * applies verbatim. Options that C keeps as {@code DFLOAT} (float) are {@code float} fields here, so the
 * physics computes in the same precision as C.
 */
public final class Params {
    /** C {@code MAX_TOTAL_SHOTS}: default object pool capacity. */
    public static final int MAX_TOTAL_SHOTS = 16384;

    /** Simulation ticks (= C frames) per second: the map's {@code framesPerSecond}. */
    public int fps;
    /** C {@code World.hypotenuse}: (int) diagonal of the world in pixels. */
    public double hypotenuse;

    public float shipMass;
    public float minItemMass;
    public float shotMass;
    public float shotSpeed;
    public int shotLife;
    public int fireRepeatRate;
    public int maxPlayerShots;
    /** Object pool capacity (C {@code MAX_TOTAL_SHOTS}). */
    public int maxObjects = MAX_TOTAL_SHOTS;
    public boolean shotsGravity;
    public boolean shotHitFuelDrainUsesKineticEnergy;

    public boolean crashWithPlayer;
    public boolean bounceWithPlayer;
    public boolean playerKillings;
    public boolean playerShielding;
    public boolean playerStartsShielded;
    public boolean selfImmunity;
    public int killingShots;
    public int defHave;
    public int defUsed;

    public boolean edgeWrap;
    public boolean edgeBounce;
    public float maxObjectWallBounceSpeed;
    public float maxShieldedWallBounceSpeed;
    public float maxUnshieldedWallBounceSpeed;
    /** In RES units ({@code mp.max_shielded_angle}). */
    public int maxShieldedAngle;
    public int maxUnshieldedAngle;
    /** Option values in degrees, converted into {@link #maxShieldedAngle}/{@link #maxUnshieldedAngle} by resolve. */
    public float maxShieldedAngleDeg;
    public float maxUnshieldedAngleDeg;
    public float playerWallBrakeFactor;
    public float objectWallBrakeFactor;
    public float objectWallBounceLifeFactor;
    public float wallBounceFuelDrainMult;
    public float wallBounceExplosionMult;
    /** {@code mp.obj_bounce_mask}, {@code obj_cannon_mask}, {@code obj_target_mask}, {@code obj_treasure_mask}. */
    public int objBounceMask;
    public int objCannonMask;
    public int objTargetMask;
    public int objTreasureMask;

    public float friction;
    public float blockFriction;
    public int coriolis;

    public boolean teamPlay;
    public boolean teamImmunity;
    public boolean teamCannons;
    public boolean teamFuel;
    public boolean teamShareScore;
    public boolean teamAssign;
    public boolean targetKillTeam;
    public boolean targetTeamCollision;
    public boolean targetSync;
    public int targetDeadTime;
    public int cannonDeadTime;
    public int cannonSmartness;
    public boolean cannonFlak;
    /** C {@code keepShots}: a leaving player's shots keep flying. */
    public boolean keepShots;

    public boolean limitedLives;
    public int worldLives;
    public boolean turnThrust;
    public boolean cloakedExhaust;
    public int wormTime;

    // ---- rounds (player.c Compute_game_status and friends)
    /** C {@code gameDuration} in minutes (only its sign matters to the simulation). */
    public float gameDuration;
    /** C {@code roundDelaySeconds}. */
    public int roundDelaySeconds;
    /** C {@code maxRoundTime} in seconds. */
    public int maxRoundTime;
    /** C {@code roundsToPlay} (0 = unlimited). */
    public int roundsToPlay;
    /** C {@code endOfRoundReset} (option {@code reset}). */
    public boolean endOfRoundReset;
    /** C {@code resetOnHuman}. */
    public int resetOnHuman;
    /** C {@code maxPauseTime} in frames (0 = never kick). */
    public int maxPauseTime;

    public double initialFuel;
    /** Initial item counts ({@code World.items[i].initial}, limited). */
    public final int[] initialItems = new int[Item.COUNT];

    public int maxRobots;
    public int minRobots;
    public int robotTeam;
    public boolean restrictRobots;
    public boolean reserveRobotTeam;
    public boolean robotsTalk;
    public boolean robotsLeave;

    public float shotKillScoreMult;
    public float clusterKillScoreMult;
    public float runoverKillScoreMult;
    public float explosionKillScoreMult;
    public float shoveKillScoreMult;
    public float crashScoreMult;
    public float selfKillScoreMult;
    public float selfDestructScoreMult;
    public float unownedKillScoreMult;
    public float tankKillScoreMult;
    public float cannonPoints;
    public float cannonMaxScore;

    public float minVisibilityDistance;
    public float maxVisibilityDistance;
    public boolean cannonsUseItems;

    /** Parameters with C defaults for a map (for tests that only need a world size). */
    public static Params from(Options o, WorldMap map) {
        Params p = new Params();
        p.fps = o.framesPerSecond();
        p.hypotenuse = (int) StrictMath.hypot(map.pixelWidth(), map.pixelHeight());

        p.shipMass = f(o, Opt.SHIP_MASS);
        p.shotMass = f(o, Opt.SHOT_MASS);
        p.shotSpeed = f(o, Opt.SHOT_SPEED);
        p.shotLife = o.getInt(Opt.SHOT_LIFE);
        p.fireRepeatRate = o.getInt(Opt.FIRE_REPEAT_RATE);
        p.maxPlayerShots = o.getInt(Opt.MAX_PLAYER_SHOTS);
        p.shotsGravity = o.getBool(Opt.SHOTS_GRAVITY);
        p.shotHitFuelDrainUsesKineticEnergy = o.getBool(Opt.SHOT_HIT_FUEL_DRAIN_USES_KINETIC_ENERGY);

        p.crashWithPlayer = o.getBool(Opt.ALLOW_PLAYER_CRASHES);
        p.bounceWithPlayer = o.getBool(Opt.ALLOW_PLAYER_BOUNCES);
        p.playerKillings = o.getBool(Opt.ALLOW_PLAYER_KILLING);
        p.playerShielding = o.getBool(Opt.ALLOW_SHIELDS);
        p.playerStartsShielded = o.getBool(Opt.PLAYER_STARTS_SHIELDED);
        p.selfImmunity = o.getBool(Opt.SELF_IMMUNITY);

        p.edgeWrap = map.edgeWrap();
        p.edgeBounce = o.getBool(Opt.EDGE_BOUNCE);
        p.maxObjectWallBounceSpeed = f(o, Opt.MAX_OBJECT_WALL_BOUNCE_SPEED);
        p.maxShieldedWallBounceSpeed = f(o, Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED);
        p.maxUnshieldedWallBounceSpeed = f(o, Opt.MAX_UNSHIELDED_WALL_BOUNCE_SPEED);
        p.maxShieldedAngleDeg = f(o, Opt.MAX_SHIELDED_PLAYER_WALL_BOUNCE_ANGLE);
        p.maxUnshieldedAngleDeg = f(o, Opt.MAX_UNSHIELDED_PLAYER_WALL_BOUNCE_ANGLE);
        p.playerWallBrakeFactor = f(o, Opt.PLAYER_WALL_BOUNCE_BRAKE_FACTOR);
        p.objectWallBrakeFactor = f(o, Opt.OBJECT_WALL_BOUNCE_BRAKE_FACTOR);
        p.objectWallBounceLifeFactor = f(o, Opt.OBJECT_WALL_BOUNCE_LIFE_FACTOR);
        p.wallBounceFuelDrainMult = f(o, Opt.WALL_BOUNCE_FUEL_DRAIN_MULT);
        int bounce = 0;
        bounce |= o.getBool(Opt.SPARKS_WALL_BOUNCE) ? Obj.SPARK : 0;
        bounce |= o.getBool(Opt.DEBRIS_WALL_BOUNCE) ? Obj.DEBRIS : 0;
        bounce |= o.getBool(Opt.SHOTS_WALL_BOUNCE) ? Obj.SHOT | Obj.CANNON_SHOT : 0;
        bounce |= o.getBool(Opt.ITEMS_WALL_BOUNCE) ? Obj.ITEM : 0;
        bounce |= o.getBool(Opt.MISSILES_WALL_BOUNCE) ? Obj.SMART_SHOT | Obj.TORPEDO | Obj.HEAT_SHOT : 0;
        bounce |= o.getBool(Opt.MINES_WALL_BOUNCE) ? Obj.MINE : 0;
        bounce |= o.getBool(Opt.BALLS_WALL_BOUNCE) ? Obj.BALL : 0;
        bounce |= o.getBool(Opt.ASTEROIDS_WALL_BOUNCE) ? Obj.ASTEROID : 0;
        p.objBounceMask = bounce;

        p.friction = f(o, Opt.FRICTION);
        p.blockFriction = f(o, Opt.BLOCK_FRICTION);
        p.coriolis = o.getInt(Opt.CORIOLIS);

        p.teamPlay = map.teamPlay();
        p.teamImmunity = o.getBool(Opt.TEAM_IMMUNITY);
        p.teamCannons = o.getBool(Opt.TEAM_CANNONS);
        p.teamFuel = o.getBool(Opt.TEAM_FUEL);
        p.teamShareScore = o.getBool(Opt.TEAM_SHARE_SCORE);
        p.teamAssign = o.getBool(Opt.TEAM_ASSIGN);
        p.targetKillTeam = o.getBool(Opt.TARGET_KILL_TEAM);
        p.targetTeamCollision = o.getBool(Opt.TARGET_TEAM_COLLISION);
        p.targetSync = o.getBool(Opt.TARGET_SYNC);
        p.targetDeadTime = o.getFrames(Opt.TARGET_DEAD_TIME);
        p.cannonDeadTime = o.getFrames(Opt.CANNON_DEAD_TIME);
        p.cannonSmartness = o.getInt(Opt.CANNON_SMARTNESS);
        p.cannonFlak = o.getBool(Opt.CANNON_FLAK);
        p.keepShots = o.getBool(Opt.KEEP_SHOTS);

        p.limitedLives = o.getBool(Opt.LIMITED_LIVES);
        p.worldLives = o.getInt(Opt.WORLD_LIVES);
        p.turnThrust = o.getBool(Opt.TURN_THRUST);
        p.cloakedExhaust = o.getBool(Opt.CLOAKED_EXHAUST);
        p.wormTime = o.getFrames(Opt.WORM_TIME);

        p.gameDuration = f(o, Opt.GAME_DURATION);
        p.roundDelaySeconds = o.getInt(Opt.ROUND_DELAY);
        p.maxRoundTime = o.getInt(Opt.MAX_ROUND_TIME);
        p.roundsToPlay = o.getInt(Opt.ROUNDS_TO_PLAY);
        p.endOfRoundReset = o.getBool(Opt.RESET);
        p.resetOnHuman = o.getInt(Opt.RESET_ON_HUMAN);
        p.maxPauseTime = o.getFrames(Opt.MAX_PAUSE_TIME);

        // C Set_initial_resources: LIMIT(fuel limit, 0, MAX_FUEL = 10000), LIMIT(initial, 0, limit)
        p.initialFuel = clamp(o.getInt(Opt.INITIAL_FUEL), 0, clamp(o.getInt(Opt.MAX_FUEL), 0, 10000));
        p.minItemMass = f(o, Opt.MIN_ITEM_MASS);
        readItems(o, p.initialItems);

        p.maxRobots = o.getInt(Opt.MAX_ROBOTS);
        p.minRobots = o.getInt(Opt.MIN_ROBOTS);
        p.robotTeam = o.getInt(Opt.ROBOT_TEAM);
        p.restrictRobots = o.getBool(Opt.RESTRICT_ROBOTS);
        p.reserveRobotTeam = o.getBool(Opt.RESERVE_ROBOT_TEAM);
        p.robotsTalk = o.getBool(Opt.ROBOTS_TALK);
        p.robotsLeave = o.getBool(Opt.ROBOTS_LEAVE);

        p.shotKillScoreMult = f(o, Opt.SHOT_KILL_SCORE_MULT);
        p.clusterKillScoreMult = f(o, Opt.CLUSTER_KILL_SCORE_MULT);
        p.runoverKillScoreMult = f(o, Opt.RUNOVER_KILL_SCORE_MULT);
        p.explosionKillScoreMult = f(o, Opt.EXPLOSION_KILL_SCORE_MULT);
        p.shoveKillScoreMult = f(o, Opt.SHOVE_KILL_SCORE_MULT);
        p.crashScoreMult = f(o, Opt.CRASH_SCORE_MULT);
        p.selfKillScoreMult = f(o, Opt.SELF_KILL_SCORE_MULT);
        p.selfDestructScoreMult = f(o, Opt.SELF_DESTRUCT_SCORE_MULT);
        p.unownedKillScoreMult = f(o, Opt.UNOWNED_KILL_SCORE_MULT);
        p.tankKillScoreMult = f(o, Opt.TANK_KILL_SCORE_MULT);
        p.cannonPoints = f(o, Opt.CANNON_POINTS);
        p.cannonMaxScore = f(o, Opt.CANNON_MAX_SCORE);
        p.minVisibilityDistance = f(o, Opt.MIN_VISIBILITY_DISTANCE);
        p.maxVisibilityDistance = f(o, Opt.MAX_VISIBILITY_DISTANCE);
        p.cannonsUseItems = o.getBool(Opt.CANNONS_USE_ITEMS);

        p.resolve(map.bases().size());
        return p;
    }

    /** A C {@code DFLOAT} option: the parsed double rounded to float, as C stores it. */
    private static float f(Options o, Opt opt) {
        return (float) o.getDouble(opt);
    }

    /**
     * Initial items as C {@code Set_initial_resources} (rules.c) leaves them.
     * <p>
     * BEHAVIOR: each item's limit (its {@code maxXxx} option) is limited to [0, C maximum] (8 tanks,
     * 15 afterburners, 99 for the rest), then the initial count to [0, limit].
     */
    static void readItems(Options o, int[] items) {
        item(o, items, Item.TANK, Opt.INITIAL_TANKS, Opt.MAX_TANKS, 8);
        item(o, items, Item.ECM, Opt.INITIAL_EC_MS, Opt.MAX_EC_MS, 99);
        item(o, items, Item.ARMOR, Opt.INITIAL_ARMOR, Opt.MAX_ARMOR, 99);
        item(o, items, Item.MINE, Opt.INITIAL_MINES, Opt.MAX_MINES, 99);
        item(o, items, Item.MISSILE, Opt.INITIAL_MISSILES, Opt.MAX_MISSILES, 99);
        item(o, items, Item.CLOAK, Opt.INITIAL_CLOAKS, Opt.MAX_CLOAKS, 99);
        item(o, items, Item.SENSOR, Opt.INITIAL_SENSORS, Opt.MAX_SENSORS, 99);
        item(o, items, Item.WIDEANGLE, Opt.INITIAL_WIDEANGLES, Opt.MAX_WIDEANGLES, 99);
        item(o, items, Item.REARSHOT, Opt.INITIAL_REARSHOTS, Opt.MAX_REARSHOTS, 99);
        item(o, items, Item.AFTERBURNER, Opt.INITIAL_AFTERBURNERS, Opt.MAX_AFTERBURNERS, 15);
        item(o, items, Item.TRANSPORTER, Opt.INITIAL_TRANSPORTERS, Opt.MAX_TRANSPORTERS, 99);
        item(o, items, Item.MIRROR, Opt.INITIAL_MIRRORS, Opt.MAX_MIRRORS, 99);
        item(o, items, Item.DEFLECTOR, Opt.INITIAL_DEFLECTORS, Opt.MAX_DEFLECTORS, 99);
        item(o, items, Item.HYPERJUMP, Opt.INITIAL_HYPER_JUMPS, Opt.MAX_HYPER_JUMPS, 99);
        item(o, items, Item.PHASING, Opt.INITIAL_PHASINGS, Opt.MAX_PHASINGS, 99);
        item(o, items, Item.LASER, Opt.INITIAL_LASERS, Opt.MAX_LASERS, 99);
        item(o, items, Item.EMERGENCY_THRUST, Opt.INITIAL_EMERGENCY_THRUSTS, Opt.MAX_EMERGENCY_THRUSTS, 99);
        item(o, items, Item.TRACTOR_BEAM, Opt.INITIAL_TRACTOR_BEAMS, Opt.MAX_TRACTOR_BEAMS, 99);
        item(o, items, Item.AUTOPILOT, Opt.INITIAL_AUTOPILOTS, Opt.MAX_AUTOPILOTS, 99);
        item(o, items, Item.EMERGENCY_SHIELD, Opt.INITIAL_EMERGENCY_SHIELDS, Opt.MAX_EMERGENCY_SHIELDS, 99);
    }

    private static void item(Options o, int[] items, Item item, Opt initial, Opt max, int cMax) {
        int limit = (int) clamp(o.getInt(max), 0, cMax);
        items[item.ordinal()] = (int) clamp(o.getInt(initial), 0, limit);
    }

    /**
     * Derived values and C limits.
     * <p>
     * BEHAVIOR: wall bounce speeds are limited to [0, hypotenuse], angles to [0, 180] then converted to RES
     * units, brake/life factors to [0, 1], wallBounceFuelDrainMult to [0, 1000] (walls.c Move_init).
     * BEHAVIOR: wallBounceExplosionMult = sqrt(wallBounceFuelDrainMult).
     * BEHAVIOR: cannon/target/treasure crash masks per Move_init (cannonsUseItems adds items to the cannon mask).
     * BEHAVIOR: without player killing no object type kills; without shields players do not have them
     * (rules.c Set_world_rules); initial cloak/armor/... grant the ability (Set_initial_resources).
     * BEHAVIOR: maxRobots = -1 means one robot per base; minRobots = -1 means maxRobots (map.c:933-938).
     */
    public void resolve(int numBases) {
        maxObjectWallBounceSpeed = (float) clamp(maxObjectWallBounceSpeed, 0, hypotenuse);
        maxShieldedWallBounceSpeed = (float) clamp(maxShieldedWallBounceSpeed, 0, hypotenuse);
        maxUnshieldedWallBounceSpeed = (float) clamp(maxUnshieldedWallBounceSpeed, 0, hypotenuse);
        maxShieldedAngleDeg = (float) clamp(maxShieldedAngleDeg, 0, 180);
        maxUnshieldedAngleDeg = (float) clamp(maxUnshieldedAngleDeg, 0, 180);
        maxShieldedAngle = (int) (maxShieldedAngleDeg * Const.RES / 360);
        maxUnshieldedAngle = (int) (maxUnshieldedAngleDeg * Const.RES / 360);
        playerWallBrakeFactor = (float) clamp(playerWallBrakeFactor, 0, 1);
        objectWallBrakeFactor = (float) clamp(objectWallBrakeFactor, 0, 1);
        objectWallBounceLifeFactor = (float) clamp(objectWallBounceLifeFactor, 0, 1);
        wallBounceFuelDrainMult = (float) clamp(wallBounceFuelDrainMult, 0, 1000);
        wallBounceExplosionMult = (float) StrictMath.sqrt(wallBounceFuelDrainMult);

        killingShots = playerKillings ? Obj.KILLING_SHOTS : 0;
        resolveMasks();

        int have = Has.DEF_HAVE;
        have = grant(have, Item.CLOAK, Has.CLOAKING_DEVICE);
        have = grant(have, Item.EMERGENCY_THRUST, Has.EMERGENCY_THRUST);
        have = grant(have, Item.EMERGENCY_SHIELD, Has.EMERGENCY_SHIELD);
        have = grant(have, Item.PHASING, Has.PHASING_DEVICE);
        have = grant(have, Item.TRACTOR_BEAM, Has.TRACTOR_BEAM);
        have = grant(have, Item.AUTOPILOT, Has.AUTOPILOT);
        have = grant(have, Item.DEFLECTOR, Has.DEFLECTOR);
        have = grant(have, Item.MIRROR, Has.MIRROR);
        have = grant(have, Item.ARMOR, Has.ARMOR);
        if (!playerShielding) {
            have &= ~Has.SHIELD;
        }
        defHave = have;
        defUsed = Has.DEF_USED & have;

        if (maxRobots == -1) {
            maxRobots = numBases;
        }
        if (minRobots == -1) {
            minRobots = maxRobots;
        }
    }

    /**
     * The crash masks of C {@code Move_init} from {@link #killingShots}, {@link #cannonsUseItems} and
     * {@link #objBounceMask} (also run on its own when an operator's {@code /set} runs Move_init).
     * <p>
     * BEHAVIOR: see {@link #resolve}.
     */
    public void resolveMasks() {
        objCannonMask = killingShots | Obj.MINE | Obj.SHOT | Obj.PULSE | Obj.SMART_SHOT | Obj.TORPEDO
            | Obj.HEAT_SHOT | Obj.ASTEROID;
        if (cannonsUseItems) {
            objCannonMask |= Obj.ITEM;
        }
        objTargetMask = objCannonMask | Obj.BALL | Obj.SPARK;
        objTreasureMask = objBounceMask | Obj.BALL | Obj.PULSE;
    }

    private int grant(int have, Item item, int bit) {
        return initialItems[item.ordinal()] > 0 ? have | bit : have;
    }

    private static double clamp(double v, double lo, double hi) {
        return v > hi ? hi : (v < lo ? lo : v);
    }
}
