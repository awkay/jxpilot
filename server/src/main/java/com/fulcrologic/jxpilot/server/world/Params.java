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
 */
public final class Params {
    /** Server simulation rate. */
    public static final int SIM_HZ = 60;

    public int fps;
    /** Original frames per sim tick ({@code fps / SIM_HZ}). */
    public double dt;
    public double hypotenuse;

    public double shipMass;
    public double shotMass;
    public double shotSpeed;
    public int shotLife;
    public int fireRepeatRate;
    public int maxPlayerShots;
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
    public double maxObjectWallBounceSpeed;
    public double maxShieldedWallBounceSpeed;
    public double maxUnshieldedWallBounceSpeed;
    /** In RES units ({@code mp.max_shielded_angle}). */
    public int maxShieldedAngle;
    public int maxUnshieldedAngle;
    public double playerWallBrakeFactor;
    public double objectWallBrakeFactor;
    public double objectWallBounceLifeFactor;
    public double wallBounceFuelDrainMult;
    public double wallBounceExplosionMult;
    /** {@code mp.obj_bounce_mask}, {@code obj_cannon_mask}, {@code obj_target_mask}, {@code obj_treasure_mask}. */
    public int objBounceMask;
    public int objCannonMask;
    public int objTargetMask;
    public int objTreasureMask;

    public double friction;
    public double blockFriction;
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

    public boolean limitedLives;
    public int worldLives;
    public boolean turnThrust;
    public boolean cloakedExhaust;
    public int wormTime;

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

    public double shotKillScoreMult;
    public double clusterKillScoreMult;
    public double runoverKillScoreMult;
    public double explosionKillScoreMult;
    public double shoveKillScoreMult;
    public double crashScoreMult;
    public double selfKillScoreMult;
    public double selfDestructScoreMult;
    public double unownedKillScoreMult;
    public double tankKillScoreMult;
    public double cannonPoints;
    public double cannonMaxScore;

    public double minVisibilityDistance;
    public double maxVisibilityDistance;

    /** Parameters with C defaults for a map (for tests that only need a world size). */
    public static Params from(Options o, WorldMap map) {
        Params p = new Params();
        p.fps = o.framesPerSecond();
        p.dt = (double) p.fps / SIM_HZ;
        p.hypotenuse = (int) Math.hypot(map.pixelWidth(), map.pixelHeight());

        p.shipMass = o.getDouble(Opt.SHIP_MASS);
        p.shotMass = o.getDouble(Opt.SHOT_MASS);
        p.shotSpeed = o.getDouble(Opt.SHOT_SPEED);
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
        p.maxObjectWallBounceSpeed = o.getDouble(Opt.MAX_OBJECT_WALL_BOUNCE_SPEED);
        p.maxShieldedWallBounceSpeed = o.getDouble(Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED);
        p.maxUnshieldedWallBounceSpeed = o.getDouble(Opt.MAX_UNSHIELDED_WALL_BOUNCE_SPEED);
        p.maxShieldedAngle = (int) (o.getDouble(Opt.MAX_SHIELDED_PLAYER_WALL_BOUNCE_ANGLE));
        p.maxUnshieldedAngle = (int) (o.getDouble(Opt.MAX_UNSHIELDED_PLAYER_WALL_BOUNCE_ANGLE));
        p.playerWallBrakeFactor = o.getDouble(Opt.PLAYER_WALL_BOUNCE_BRAKE_FACTOR);
        p.objectWallBrakeFactor = o.getDouble(Opt.OBJECT_WALL_BOUNCE_BRAKE_FACTOR);
        p.objectWallBounceLifeFactor = o.getDouble(Opt.OBJECT_WALL_BOUNCE_LIFE_FACTOR);
        p.wallBounceFuelDrainMult = o.getDouble(Opt.WALL_BOUNCE_FUEL_DRAIN_MULT);
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

        p.friction = o.getDouble(Opt.FRICTION);
        p.blockFriction = o.getDouble(Opt.BLOCK_FRICTION);
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
        p.targetDeadTime = o.getInt(Opt.TARGET_DEAD_TIME);
        p.cannonDeadTime = o.getInt(Opt.CANNON_DEAD_TIME);
        p.cannonSmartness = o.getInt(Opt.CANNON_SMARTNESS);
        p.cannonFlak = o.getBool(Opt.CANNON_FLAK);

        p.limitedLives = o.getBool(Opt.LIMITED_LIVES);
        p.worldLives = o.getInt(Opt.WORLD_LIVES);
        p.turnThrust = o.getBool(Opt.TURN_THRUST);
        p.cloakedExhaust = o.getBool(Opt.CLOAKED_EXHAUST);
        p.wormTime = o.getInt(Opt.WORM_TIME);

        p.initialFuel = o.getInt(Opt.INITIAL_FUEL);
        readItems(o, p.initialItems);

        p.maxRobots = o.getInt(Opt.MAX_ROBOTS);
        p.minRobots = o.getInt(Opt.MIN_ROBOTS);
        p.robotTeam = o.getInt(Opt.ROBOT_TEAM);
        p.restrictRobots = o.getBool(Opt.RESTRICT_ROBOTS);
        p.reserveRobotTeam = o.getBool(Opt.RESERVE_ROBOT_TEAM);
        p.robotsTalk = o.getBool(Opt.ROBOTS_TALK);
        p.robotsLeave = o.getBool(Opt.ROBOTS_LEAVE);

        p.shotKillScoreMult = o.getDouble(Opt.SHOT_KILL_SCORE_MULT);
        p.clusterKillScoreMult = o.getDouble(Opt.CLUSTER_KILL_SCORE_MULT);
        p.runoverKillScoreMult = o.getDouble(Opt.RUNOVER_KILL_SCORE_MULT);
        p.explosionKillScoreMult = o.getDouble(Opt.EXPLOSION_KILL_SCORE_MULT);
        p.shoveKillScoreMult = o.getDouble(Opt.SHOVE_KILL_SCORE_MULT);
        p.crashScoreMult = o.getDouble(Opt.CRASH_SCORE_MULT);
        p.selfKillScoreMult = o.getDouble(Opt.SELF_KILL_SCORE_MULT);
        p.selfDestructScoreMult = o.getDouble(Opt.SELF_DESTRUCT_SCORE_MULT);
        p.unownedKillScoreMult = o.getDouble(Opt.UNOWNED_KILL_SCORE_MULT);
        p.tankKillScoreMult = o.getDouble(Opt.TANK_KILL_SCORE_MULT);
        p.cannonPoints = o.getDouble(Opt.CANNON_POINTS);
        p.cannonMaxScore = o.getDouble(Opt.CANNON_MAX_SCORE);
        p.minVisibilityDistance = o.getDouble(Opt.MIN_VISIBILITY_DISTANCE);
        p.maxVisibilityDistance = o.getDouble(Opt.MAX_VISIBILITY_DISTANCE);

        p.resolve(map.bases().size());
        return p;
    }

    private static void readItems(Options o, int[] items) {
        items[Item.TANK.ordinal()] = o.getInt(Opt.INITIAL_TANKS);
        items[Item.ECM.ordinal()] = o.getInt(Opt.INITIAL_EC_MS);
        items[Item.ARMOR.ordinal()] = o.getInt(Opt.INITIAL_ARMOR);
        items[Item.MINE.ordinal()] = o.getInt(Opt.INITIAL_MINES);
        items[Item.MISSILE.ordinal()] = o.getInt(Opt.INITIAL_MISSILES);
        items[Item.CLOAK.ordinal()] = o.getInt(Opt.INITIAL_CLOAKS);
        items[Item.SENSOR.ordinal()] = o.getInt(Opt.INITIAL_SENSORS);
        items[Item.WIDEANGLE.ordinal()] = Math.min(o.getInt(Opt.INITIAL_WIDEANGLES), o.getInt(Opt.MAX_WIDEANGLES));
        items[Item.REARSHOT.ordinal()] = Math.min(o.getInt(Opt.INITIAL_REARSHOTS), o.getInt(Opt.MAX_REARSHOTS));
        items[Item.AFTERBURNER.ordinal()] = Math.min(o.getInt(Opt.INITIAL_AFTERBURNERS),
            o.getInt(Opt.MAX_AFTERBURNERS));
        items[Item.TRANSPORTER.ordinal()] = o.getInt(Opt.INITIAL_TRANSPORTERS);
        items[Item.MIRROR.ordinal()] = o.getInt(Opt.INITIAL_MIRRORS);
        items[Item.DEFLECTOR.ordinal()] = o.getInt(Opt.INITIAL_DEFLECTORS);
        items[Item.HYPERJUMP.ordinal()] = o.getInt(Opt.INITIAL_HYPER_JUMPS);
        items[Item.PHASING.ordinal()] = o.getInt(Opt.INITIAL_PHASINGS);
        items[Item.LASER.ordinal()] = o.getInt(Opt.INITIAL_LASERS);
        items[Item.EMERGENCY_THRUST.ordinal()] = o.getInt(Opt.INITIAL_EMERGENCY_THRUSTS);
        items[Item.TRACTOR_BEAM.ordinal()] = o.getInt(Opt.INITIAL_TRACTOR_BEAMS);
        items[Item.AUTOPILOT.ordinal()] = o.getInt(Opt.INITIAL_AUTOPILOTS);
        items[Item.EMERGENCY_SHIELD.ordinal()] = o.getInt(Opt.INITIAL_EMERGENCY_SHIELDS);
        for (int i = 0; i < items.length; i++) {
            items[i] = Math.max(0, items[i]);
        }
        // C LIMIT(..., 0, MAX_AFTERBURNER) in Set_initial_resources
        int ab = Item.AFTERBURNER.ordinal();
        items[ab] = Math.min(items[ab], 15);
    }

    /**
     * Derived values and C limits.
     * <p>
     * BEHAVIOR: wall bounce speeds are limited to [0, hypotenuse], angles to [0, 180] then converted to RES
     * units, brake/life factors to [0, 1], wallBounceFuelDrainMult to [0, 1000] (walls.c Move_init).
     * BEHAVIOR: wallBounceExplosionMult = sqrt(wallBounceFuelDrainMult).
     * BEHAVIOR: cannon/target/treasure crash masks per Move_init.
     * BEHAVIOR: without player killing no object type kills; without shields players do not have them
     * (rules.c Set_world_rules); initial cloak/armor/... grant the ability (Set_initial_resources).
     * BEHAVIOR: maxRobots = -1 means one robot per base; minRobots = -1 means maxRobots (map.c:933-938).
     */
    public void resolve(int numBases) {
        maxObjectWallBounceSpeed = clamp(maxObjectWallBounceSpeed, 0, hypotenuse);
        maxShieldedWallBounceSpeed = clamp(maxShieldedWallBounceSpeed, 0, hypotenuse);
        maxUnshieldedWallBounceSpeed = clamp(maxUnshieldedWallBounceSpeed, 0, hypotenuse);
        maxShieldedAngle = (int) (clamp(maxShieldedAngle, 0, 180) * Const.RES / 360);
        maxUnshieldedAngle = (int) (clamp(maxUnshieldedAngle, 0, 180) * Const.RES / 360);
        playerWallBrakeFactor = clamp(playerWallBrakeFactor, 0, 1);
        objectWallBrakeFactor = clamp(objectWallBrakeFactor, 0, 1);
        objectWallBounceLifeFactor = clamp(objectWallBounceLifeFactor, 0, 1);
        wallBounceFuelDrainMult = clamp(wallBounceFuelDrainMult, 0, 1000);
        wallBounceExplosionMult = Math.sqrt(wallBounceFuelDrainMult);

        killingShots = playerKillings ? Obj.KILLING_SHOTS : 0;
        objCannonMask = killingShots | Obj.MINE | Obj.SHOT | Obj.PULSE | Obj.SMART_SHOT | Obj.TORPEDO
            | Obj.HEAT_SHOT | Obj.ASTEROID;
        objTargetMask = objCannonMask | Obj.BALL | Obj.SPARK;
        objTreasureMask = objBounceMask | Obj.BALL | Obj.PULSE;

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

    private int grant(int have, Item item, int bit) {
        return initialItems[item.ordinal()] > 0 ? have | bit : have;
    }

    private static double clamp(double v, double lo, double hi) {
        return v > hi ? hi : (v < lo ? lo : v);
    }
}
