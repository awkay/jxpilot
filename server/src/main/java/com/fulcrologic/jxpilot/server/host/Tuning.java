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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.CNum;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ItemParams;
import com.fulcrologic.jxpilot.server.world.ModeParams;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Option changes at run time: C {@code Tune_option} and {@code Get_option_value} (parser.c:490-610) with the
 * tuner function of every option in C's option table (cmdline.c; the tuners in tuner.c, rules.c, walls.c).
 * <p>
 * {@link #tune} stores the new value in {@link World#options} (C stores it in the option's variable), runs the
 * option's tuner, and brings {@link World#params} up to date: the fields whose value the change alters (a
 * {@link Params#from} before and after the change; likewise {@code ItemParams} and {@code ModeParams}) are copied into the live parameters, so state the
 * simulation adjusted on its own is kept. C's latched rules stay latched: {@code KILLING_SHOTS} and
 * {@code DEF_USED} only ever lose bits ({@code Set_world_rules}), the crash masks change only when
 * {@code Move_init} runs, and alliances follow {@code allowAlliances} only when the world rules are computed.
 * Classes that keep option values of their own re-read them afterwards ({@code reloadOptions}).
 * <p>
 * Commands run between ticks; this class allocates freely.
 */
final class Tuning {
    /** {@link #tune}: the value was set (C 1). */
    static final int OK = 1;
    /** {@link #tune}: the value does not parse (C 0). */
    static final int INVALID = 0;
    /** {@link #tune}: the option cannot change at run time (C -1); {@link #get}: generic error. */
    static final int UNDEFINED = -1;
    /** No such option (C -2). */
    static final int NO_OPTION = -2;
    /** {@link #get}: the option is not visible (C -3). */
    static final int NOT_VISIBLE = -3;
    /** {@link #get}: the string option has no value (C -4). */
    static final int NO_VALUE = -4;

    /** C {@code WORMCOUNT}. */
    static final int WORMCOUNT = 64;
    /** C {@code MAX_HOST_LEN}. */
    static final int MAX_HOST_LEN = 64;

    /** The tuner functions of C's option table. */
    enum Tuner {
        /** {@code tuner_none}: cannot change at run time. */
        NONE,
        /** {@code tuner_dummy} (and {@code tuner_plock}, which locks memory in C): the value only. */
        DUMMY,
        GRAVITY, SHIP_MASS, BALL_MASS, MAX_ROBOTS, MIN_ROBOTS, REAL_NAME, HOST_NAME, SHOTS_MAX, WORLD_RULES,
        PLAYER_SHIELDING, PLAYER_STARTS_SHIELDED, MOVE_INIT, DENY_HOSTS, WORLD_LIVES, ALLOW_ALLIANCES,
        ANNOUNCE_ALLIANCES, TEAM_CANNONS, CANNON_SMARTNESS, CANNONS_USE_ITEMS, WORM_TIME, MODIFIERS, ITEM_PACKS,
        MINE_LIFE, MISSILE_LIFE, MISC_ITEM_LIMITS, ITEM_PROBS, ASTEROID_PROB, INITIAL_RESOURCES, GAME_DURATION,
        RACE_LAPS
    }

    private static final Map<Opt, Tuner> TUNERS = new EnumMap<>(Opt.class);

    private static void put(Tuner t, String... names) {
        for (String n : names) {
            Opt o = Opt.find(n);
            if (o == null) {
                throw new IllegalStateException("unknown option " + n);
            }
            TUNERS.put(o, t);
        }
    }

    static {
        // Generated from the option table of src/server/cmdline.c; every option not listed is tuner_none.
        put(Tuner.GRAVITY, "gravity", "gravityPoint", "gravityAngle", "gravityPointSource", "gravityClockwise",
            "gravityAnticlockwise");
        put(Tuner.SHIP_MASS, "shipMass");
        put(Tuner.BALL_MASS, "ballMass");
        put(Tuner.DUMMY, "minItemMass", "shotMass", "shotSpeed", "shotLife", "shotHitFuelDrainUsesKineticEnergy",
            "fireRepeatRate", "robotsTalk", "robotsLeave", "robotLeaveLife", "robotLeaveScore", "robotLeaveRatio",
            "robotTeam", "restrictRobots", "reserveRobotTeam", "tankScoreDecrement", "turnThrust", "selfImmunity",
            "shotsGravity", "idleRun", "noQuit", "logRobots", "ballCollisions", "ballSparkCollisions",
            "cloakedExhaust", "cloakedShield", "maxClientsPerIP", "minVisibilityDistance", "maxVisibilityDistance",
            "reset", "resetOnHuman", "teamFuel", "cannonFlak", "cannonDeadTime", "keepShots", "teamAssign",
            "teamImmunity", "teamShareScore", "ecmsReprogramMines", "ecmsReprogramRobots", "targetKillTeam",
            "targetTeamCollision", "targetSync", "targetDeadTime", "treasureKillTeam", "captureTheFlag",
            "treasureCollisionDestroys", "ballConnectorSpringConstant", "ballConnectorDamping",
            "maxBallConnectorRatio", "ballConnectorLength", "connectorIsString", "treasureCollisionMayKill",
            "wreckageCollisionMayKill", "asteroidCollisionMayKill", "ballrace", "ballraceConnected", "edgeBounce",
            "allowSmartMissiles", "allowHeatSeekers", "allowTorpedoes", "playersOnRadar", "missilesOnRadar",
            "minesOnRadar", "nukesOnRadar", "treasuresOnRadar", "asteroidsOnRadar", "distinguishMissiles",
            "identifyMines", "shieldedItemPickup", "shieldedMining", "laserIsStunGun", "nukeMinSmarts",
            "nukeMinMines", "nukeClusterDamage", "mineFuseTime", "minMineSpeed", "baseMineRange",
            "mineShotDetonateDistance", "shotKillScoreMult", "torpedoKillScoreMult", "smartKillScoreMult",
            "heatKillScoreMult", "clusterKillScoreMult", "laserKillScoreMult", "tankKillScoreMult",
            "runoverKillScoreMult", "ballKillScoreMult", "explosionKillScoreMult", "shoveKillScoreMult",
            "crashScoreMult", "mineScoreMult", "selfKillScoreMult", "selfDestructScoreMult",
            "unownedKillScoreMult", "asteroidPoints", "cannonPoints", "asteroidMaxScore", "cannonMaxScore",
            "cannonItemProbMult", "rogueHeatProb", "rogueMineProb", "allowViewing", "friction", "blockFriction",
            "coriolis", "checkpointRadius", "lockOtherTeam", "loseItemDestroys", "useWreckage",
            "maxOffensiveItems", "maxDefensiveItems", "roundDelay", "maxRoundTime", "roundsToPlay",
            "maxVisibleObject", "pLockServer", "password", "clientPortStart", "clientPortEnd", "maxPauseTime",
            "ignore20MaxFPS");
        put(Tuner.MAX_ROBOTS, "maxRobots");
        put(Tuner.MIN_ROBOTS, "minRobots");
        put(Tuner.REAL_NAME, "robotRealName", "tankRealName");
        put(Tuner.HOST_NAME, "robotHostName", "tankHostName");
        put(Tuner.SHOTS_MAX, "maxPlayerShots");
        put(Tuner.WORLD_RULES, "allowPlayerCrashes", "allowPlayerBounces", "allowPlayerKilling",
            "limitedVisibility");
        put(Tuner.PLAYER_SHIELDING, "allowShields");
        put(Tuner.PLAYER_STARTS_SHIELDED, "playerStartsShielded");
        put(Tuner.MOVE_INIT, "shotsWallBounce", "ballsWallBounce", "minesWallBounce", "itemsWallBounce",
            "missilesWallBounce", "sparksWallBounce", "debrisWallBounce", "asteroidsWallBounce",
            "maxObjectWallBounceSpeed", "maxShieldedWallBounceSpeed", "maxUnshieldedWallBounceSpeed",
            "maxShieldedPlayerWallBounceAngle", "maxUnshieldedPlayerWallBounceAngle",
            "playerWallBounceBrakeFactor", "objectWallBounceBrakeFactor", "objectWallBounceLifeFactor",
            "wallBounceFuelDrainMult", "wallBounceDestroyItemProb");
        put(Tuner.DENY_HOSTS, "denyHosts");
        put(Tuner.WORLD_LIVES, "worldLives");
        put(Tuner.ALLOW_ALLIANCES, "allowAlliances");
        put(Tuner.ANNOUNCE_ALLIANCES, "announceAlliances");
        put(Tuner.TEAM_CANNONS, "teamCannons");
        put(Tuner.CANNON_SMARTNESS, "cannonSmartness");
        put(Tuner.CANNONS_USE_ITEMS, "cannonsUseItems");
        put(Tuner.WORM_TIME, "wormTime");
        put(Tuner.MODIFIERS, "allowNukes", "allowClusters", "allowModifiers", "allowLaserModifiers");
        put(Tuner.ITEM_PACKS, "maxMissilesPerPack", "maxMinesPerPack");
        put(Tuner.MINE_LIFE, "mineLife");
        put(Tuner.MISSILE_LIFE, "missileLife");
        put(Tuner.MISC_ITEM_LIMITS, "movingItemProb", "randomItemProb", "dropItemOnKillProb",
            "detonateItemOnKillProb", "destroyItemInCollisionProb", "asteroidItemProb", "asteroidMaxItems",
            "itemConcentratorRadius", "itemConcentratorProb");
        put(Tuner.ITEM_PROBS, "itemProbMult", "maxItemDensity", "itemEnergyPackProb", "itemTankProb",
            "itemECMProb", "itemArmorProb", "itemMineProb", "itemMissileProb", "itemCloakProb", "itemSensorProb",
            "itemWideangleProb", "itemRearshotProb", "itemAfterburnerProb", "itemTransporterProb",
            "itemMirrorProb", "itemDeflectorProb", "itemHyperJumpProb", "itemPhasingProb", "itemLaserProb",
            "itemEmergencyThrustProb", "itemTractorBeamProb", "itemAutopilotProb", "itemEmergencyShieldProb");
        put(Tuner.ASTEROID_PROB, "asteroidProb", "maxAsteroidDensity", "asteroidConcentratorRadius",
            "asteroidConcentratorProb");
        put(Tuner.INITIAL_RESOURCES, "initialFuel", "initialTanks", "initialECMs", "initialArmor", "initialMines",
            "initialMissiles", "initialCloaks", "initialSensors", "initialWideangles", "initialRearshots",
            "initialAfterburners", "initialTransporters", "initialMirrors", "initialDeflectors",
            "initialHyperJumps", "initialPhasings", "initialLasers", "initialEmergencyThrusts",
            "initialTractorBeams", "initialAutopilots", "initialEmergencyShields", "maxFuel", "maxTanks",
            "maxECMs", "maxMines", "maxMissiles", "maxCloaks", "maxSensors", "maxWideangles", "maxRearshots",
            "maxAfterburners", "maxTransporters", "maxArmor", "maxMirrors", "maxDeflectors", "maxPhasings",
            "maxHyperJumps", "maxEmergencyThrusts", "maxLasers", "maxTractorBeams", "maxAutopilots",
            "maxEmergencyShields");
        put(Tuner.GAME_DURATION, "gameDuration");
        put(Tuner.RACE_LAPS, "raceLaps");
    }

    /**
     * BEHAVIOR: the C tuner of {@code o}; options C's table gives {@code tuner_none} and the JXPilot-only
     * network options are NONE.
     */
    static Tuner tuner(Opt o) {
        return TUNERS.getOrDefault(o, Tuner.NONE);
    }

    private final Simulation sim;
    private final World w;
    private final Options opts;
    /** Told the option after a successful change (the host re-reads what it keeps; the network layer too). */
    private final Consumer<Opt> changed;
    /** C {@code KILLING_SHOTS}: cleared for good once the rules forbid player killing. */
    private int killingShots;
    /** C {@code DEF_USED}: {@code &= DEF_HAVE} at every rules update, never widened. */
    private int defUsed;

    Tuning(Simulation sim, Consumer<Opt> changed) {
        this.sim = sim;
        this.w = sim.world;
        this.opts = w.options;
        this.changed = changed;
        this.killingShots = w.params.killingShots;
        this.defUsed = w.params.defUsed;
        // the values C's start-up code leaves in the variables (map.c:935-940, Robot_init), for /get
        writeBack(Opt.MAX_ROBOTS, w.params.maxRobots);
        writeBack(Opt.MIN_ROBOTS, w.params.minRobots);
        writeBack(Opt.ROBOT_TEAM, w.params.robotTeam);
    }

    // ================================================================ Tune_option

    /**
     * C {@code Tune_option}.
     * <p>
     * BEHAVIOR: an unknown name (either name, any case) is NO_OPTION; a NONE tuner is UNDEFINED.
     * BEHAVIOR: int: strtol (base 0) with at least one digit, else INVALID; bool: a value starting with
     * true/on/yes or false/off/no (any case), else INVALID; real and per-second: strtod with a digit, else
     * INVALID; seconds: an int, as int; string: the value as given; position, list and void options are
     * UNDEFINED.
     * BEHAVIOR: the value is stored, the tuner runs ({@link #runTuner}), the parameters are brought up to date,
     * the reloaders run, and the result is OK.
     */
    int tune(String name, String value) {
        Opt o = find(name);
        if (o == null) {
            return NO_OPTION;
        }
        Tuner t = tuner(o);
        if (t == Tuner.NONE) {
            return UNDEFINED;
        }
        String stored;
        switch (o.type()) {
            case INT -> {
                long[] r = new long[1];
                if (!CNum.strtol(value, r)) {
                    return INVALID;
                }
                stored = Integer.toString((int) r[0]);
            }
            case SEC -> {
                long[] r = new long[1];
                if (!CNum.strtol(value, r)) {
                    return INVALID;
                }
                int fps = opts.framesPerSecond();
                int frames = (int) r[0] * fps; // C: ival * FPS in int, wrapping
                stored = Double.toString((frames + Math.copySign(0.5, frames)) / fps); // exactly frames frames
            }
            case BOOL -> {
                if (on(value)) {
                    stored = "true";
                } else if (off(value)) {
                    stored = "false";
                } else {
                    return INVALID;
                }
            }
            case REAL, PER_SEC -> {
                double[] r = new double[1];
                if (!CNum.strtod(value, r)) {
                    return INVALID;
                }
                stored = value;
            }
            case STRING -> stored = value;
            default -> {
                return UNDEFINED;
            }
        }
        Params before = Params.from(opts, w.map);
        ItemParams beforeItems = ItemParams.from(opts, w.width, w.height, w.params.fps);
        ModeParams beforeModes = ModeParams.from(opts, w.map, w.params.fps);
        opts.force(o, stored);
        fixValues(t, o);
        int cannonMask = w.params.objCannonMask;
        int targetMask = w.params.objTargetMask;
        int treasureMask = w.params.objTreasureMask;
        copyChanges(before, Params.from(opts, w.map), w.params);
        copyChanges(beforeItems, ItemParams.from(opts, w.width, w.height, w.params.fps), w.items);
        copyChanges(beforeModes, ModeParams.from(opts, w.map, w.params.fps), w.modeParams);
        w.params.objCannonMask = cannonMask;
        w.params.objTargetMask = targetMask;
        w.params.objTreasureMask = treasureMask;
        runTuner(t);
        w.params.killingShots = killingShots;
        w.params.defUsed = defUsed;
        sim.robots.reloadOptions();
        sim.collisions.reloadOptions();
        sim.wreckage.reloadOptions();
        changed.accept(o);
        return OK;
    }

    /**
     * C {@code Find_option_by_name}: an exact name, any case.
     * <p>
     * BEHAVIOR: a name containing whitespace is no option (C compares the whole string; {@link Opt#find} would
     * drop the whitespace); otherwise {@link Opt#find}.
     */
    static Opt find(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (Character.isWhitespace(name.charAt(i))) {
                return null;
            }
        }
        return Opt.find(name);
    }

    /** C {@code ON}: BEHAVIOR: the value starts with "true", "on" or "yes", any case. */
    static boolean on(String v) {
        return startsIgnoreCase(v, "true") || startsIgnoreCase(v, "on") || startsIgnoreCase(v, "yes");
    }

    /** C {@code OFF}: BEHAVIOR: the value starts with "false", "off" or "no", any case. */
    static boolean off(String v) {
        return startsIgnoreCase(v, "false") || startsIgnoreCase(v, "off") || startsIgnoreCase(v, "no");
    }

    private static boolean startsIgnoreCase(String v, String prefix) {
        return v.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * The tuners' changes to option variables (so {@code /get} shows what C's variable holds).
     * <p>
     * BEHAVIOR: maxRobots &lt; 0 becomes the number of bases, and minRobots drops to it; minRobots &lt; 0
     * becomes maxRobots, and maxRobots rises to it; with shields allowed playerStartsShielded is forced on;
     * worldLives, wormTime, mineLife and missileLife below 0 become 0; cannonSmartness is limited to 0..3; the
     * item probabilities of {@code Set_misc_item_limits} to [0, 1], itemConcentratorRadius to [1, diagonal in
     * blocks] and asteroidMaxItems to at least 0; real names get C {@code Fix_real_name}, host names
     * {@code Fix_host_name}; Move_init's limits on the wall bounce options (speeds 0..hypotenuse, angles 0..180,
     * factors 0..1, fuel drain 0..1000) and Set_initial_resources' on the item options (each limit 0..its C
     * maximum, each initial 0..its limit); a robotTeam outside 0..9 becomes 0 (Robot_init's clamp; C's dummy tuner leaves it
     * out of range and indexes past its team table).
     */
    void fixValues(Tuner t, Opt o) {
        switch (t) {
            case MAX_ROBOTS -> {
                int max = opts.getInt(Opt.MAX_ROBOTS);
                if (max < 0) {
                    max = w.map.bases().size();
                    writeBack(Opt.MAX_ROBOTS, max);
                }
                if (max < opts.getInt(Opt.MIN_ROBOTS)) {
                    writeBack(Opt.MIN_ROBOTS, max);
                }
            }
            case MIN_ROBOTS -> {
                int min = opts.getInt(Opt.MIN_ROBOTS);
                if (min < 0) {
                    min = opts.getInt(Opt.MAX_ROBOTS);
                    writeBack(Opt.MIN_ROBOTS, min);
                }
                if (opts.getInt(Opt.MAX_ROBOTS) < min) {
                    writeBack(Opt.MAX_ROBOTS, min);
                }
            }
            case PLAYER_STARTS_SHIELDED -> {
                if (opts.getBool(Opt.ALLOW_SHIELDS)) {
                    opts.force(Opt.PLAYER_STARTS_SHIELDED, "true");
                }
            }
            case WORLD_LIVES, WORM_TIME, MINE_LIFE, MISSILE_LIFE -> {
                if (opts.getInt(o) < 0) {
                    writeBack(o, 0);
                }
            }
            case CANNON_SMARTNESS -> writeBack(o, Math.clamp(opts.getInt(o), 0, 3));
            case MOVE_INIT, CANNONS_USE_ITEMS -> {
                double hyp = w.params.hypotenuse;
                limit(Opt.MAX_OBJECT_WALL_BOUNCE_SPEED, 0, hyp);
                limit(Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED, 0, hyp);
                limit(Opt.MAX_UNSHIELDED_WALL_BOUNCE_SPEED, 0, hyp);
                limit(Opt.MAX_SHIELDED_PLAYER_WALL_BOUNCE_ANGLE, 0, 180);
                limit(Opt.MAX_UNSHIELDED_PLAYER_WALL_BOUNCE_ANGLE, 0, 180);
                limit(Opt.PLAYER_WALL_BOUNCE_BRAKE_FACTOR, 0, 1);
                limit(Opt.OBJECT_WALL_BOUNCE_BRAKE_FACTOR, 0, 1);
                limit(Opt.OBJECT_WALL_BOUNCE_LIFE_FACTOR, 0, 1);
                limit(Opt.WALL_BOUNCE_FUEL_DRAIN_MULT, 0, 1000);
            }
            case INITIAL_RESOURCES -> {
                for (Opt[] pair : ITEM_LIMITS) {
                    int max = Math.clamp(opts.getInt(pair[1]), 0, ITEM_MAX[indexOf(pair)]);
                    writeBack(pair[1], max);
                    writeBack(pair[0], Math.clamp(opts.getInt(pair[0]), 0, max));
                }
            }
            case MISC_ITEM_LIMITS -> {
                for (Opt p : new Opt[] {Opt.DROP_ITEM_ON_KILL_PROB, Opt.DETONATE_ITEM_ON_KILL_PROB,
                    Opt.MOVING_ITEM_PROB, Opt.RANDOM_ITEM_PROB, Opt.DESTROY_ITEM_IN_COLLISION_PROB,
                    Opt.ITEM_CONCENTRATOR_PROB, Opt.ASTEROID_ITEM_PROB}) {
                    limit(p, 0, 1);
                }
                int diagonal = (int) Math.hypot(w.width, w.height);
                writeBack(Opt.ITEM_CONCENTRATOR_RADIUS, Math.clamp(opts.getInt(Opt.ITEM_CONCENTRATOR_RADIUS), 1, diagonal));
                if (opts.getInt(Opt.ASTEROID_MAX_ITEMS) < 0) {
                    writeBack(Opt.ASTEROID_MAX_ITEMS, 0);
                }
            }
            case ASTEROID_PROB -> {
                int diagonal = (int) Math.hypot(w.width, w.height);
                writeBack(Opt.ASTEROID_CONCENTRATOR_RADIUS,
                    Math.clamp(opts.getInt(Opt.ASTEROID_CONCENTRATOR_RADIUS), 1, diagonal));
                limit(Opt.ASTEROID_CONCENTRATOR_PROB, 0, 1);
            }
            case REAL_NAME -> opts.force(o, fixRealName(opts.getString(o)));
            case HOST_NAME -> opts.force(o, fixHostName(opts.getString(o)));
            default -> { }
        }
        if (o == Opt.ROBOT_TEAM) {
            int team = opts.getInt(o);
            if (team < 0 || team >= Const.MAX_TEAMS) {
                writeBack(o, 0); // Robot_init's clamp (robot.c:601); C indexes World.teams out of range here
            }
        }
    }

    /** Set_initial_resources's pairs (initial, limit), fuel first (rules.c:215-245). */
    static final Opt[][] ITEM_LIMITS = {
        {Opt.INITIAL_FUEL, Opt.MAX_FUEL}, {Opt.INITIAL_TANKS, Opt.MAX_TANKS}, {Opt.INITIAL_EC_MS, Opt.MAX_EC_MS},
        {Opt.INITIAL_ARMOR, Opt.MAX_ARMOR}, {Opt.INITIAL_MINES, Opt.MAX_MINES},
        {Opt.INITIAL_MISSILES, Opt.MAX_MISSILES}, {Opt.INITIAL_CLOAKS, Opt.MAX_CLOAKS},
        {Opt.INITIAL_SENSORS, Opt.MAX_SENSORS}, {Opt.INITIAL_WIDEANGLES, Opt.MAX_WIDEANGLES},
        {Opt.INITIAL_REARSHOTS, Opt.MAX_REARSHOTS}, {Opt.INITIAL_AFTERBURNERS, Opt.MAX_AFTERBURNERS},
        {Opt.INITIAL_TRANSPORTERS, Opt.MAX_TRANSPORTERS}, {Opt.INITIAL_MIRRORS, Opt.MAX_MIRRORS},
        {Opt.INITIAL_DEFLECTORS, Opt.MAX_DEFLECTORS}, {Opt.INITIAL_HYPER_JUMPS, Opt.MAX_HYPER_JUMPS},
        {Opt.INITIAL_PHASINGS, Opt.MAX_PHASINGS}, {Opt.INITIAL_LASERS, Opt.MAX_LASERS},
        {Opt.INITIAL_EMERGENCY_THRUSTS, Opt.MAX_EMERGENCY_THRUSTS},
        {Opt.INITIAL_TRACTOR_BEAMS, Opt.MAX_TRACTOR_BEAMS}, {Opt.INITIAL_AUTOPILOTS, Opt.MAX_AUTOPILOTS},
        {Opt.INITIAL_EMERGENCY_SHIELDS, Opt.MAX_EMERGENCY_SHIELDS},
    };
    /** The C maxima of {@link #ITEM_LIMITS}: MAX_FUEL 10000, MAX_TANKS 8, MAX_AFTERBURNER 15, the rest 99. */
    static final int[] ITEM_MAX = {10000, 8, 99, 99, 99, 99, 99, 99, 99, 99, 15, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99};

    private static int indexOf(Opt[] pair) {
        for (int i = 0; i < ITEM_LIMITS.length; i++) {
            if (ITEM_LIMITS[i] == pair) {
                return i;
            }
        }
        return -1;
    }

    private void writeBack(Opt o, int v) {
        if (opts.getInt(o) != v || opts.getString(o) == null) {
            opts.force(o, Integer.toString(v));
        }
    }

    private void limit(Opt o, double lo, double hi) {
        double v = opts.getDouble(o);
        if (v < lo || v > hi) {
            opts.force(o, Double.toString(v < lo ? lo : hi));
        }
    }

    /**
     * C {@code Fix_real_name}.
     * <p>
     * BEHAVIOR: cut to 15 characters; empty becomes "X"; every character that is not a printable non-space
     * ASCII character becomes 'x'.
     */
    static String fixRealName(String name) {
        String n = name.length() > Const.MAX_NAME_LEN - 1 ? name.substring(0, Const.MAX_NAME_LEN - 1) : name;
        if (n.isEmpty()) {
            return "X";
        }
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c <= ' ' || c >= 127) {
                sb.setCharAt(i, 'x');
            }
        }
        return sb.toString();
    }

    /**
     * C {@code Fix_host_name}.
     * <p>
     * BEHAVIOR: cut to 63 characters; a name not starting with a letter or digit becomes "xxx.xxx"; any other
     * character that is not a letter or digit becomes 'x', except a '.' or '-' followed by a letter or digit.
     */
    static String fixHostName(String name) {
        String n = name.length() > MAX_HOST_LEN - 1 ? name.substring(0, MAX_HOST_LEN - 1) : name;
        if (n.isEmpty() || !alnum(n.charAt(0))) {
            return "xxx.xxx";
        }
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (!alnum(c)) {
                boolean sep = c == '.' || c == '-';
                boolean nextOk = i + 1 < sb.length() && sb.charAt(i + 1) != '.' && sb.charAt(i + 1) != '-';
                if (!sep || !nextOk) {
                    sb.setCharAt(i, 'x');
                }
            }
        }
        return sb.toString();
    }

    private static boolean alnum(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }

    /**
     * Copy into {@code live} every field of {@code after} that differs from {@code before} (arrays element by
     * element, into the live array).
     * <p>
     * BEHAVIOR: fields equal in before and after keep their live value; changed primitives, strings and array
     * elements take the after value.
     */
    static void copyChanges(Object before, Object after, Object live) {
        try {
            for (Field f : live.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object b = f.get(before);
                Object a = f.get(after);
                if (a instanceof int[] ai) {
                    int[] bi = (int[]) b;
                    int[] li = (int[]) f.get(live);
                    for (int i = 0; i < ai.length; i++) {
                        if (ai[i] != bi[i]) {
                            li[i] = ai[i];
                        }
                    }
                } else if (a instanceof float[] af) {
                    float[] bf = (float[]) b;
                    float[] lf = (float[]) f.get(live);
                    for (int i = 0; i < af.length; i++) {
                        if (Float.compare(af[i], bf[i]) != 0) {
                            lf[i] = af[i];
                        }
                    }
                } else if (a instanceof double[] ad) {
                    double[] bd = (double[]) b;
                    double[] ld = (double[]) f.get(live);
                    for (int i = 0; i < ad.length; i++) {
                        if (Double.compare(ad[i], bd[i]) != 0) {
                            ld[i] = ad[i];
                        }
                    }
                } else if (!java.util.Objects.equals(a, b) && !Modifier.isFinal(f.getModifiers())) {
                    f.set(live, a);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ tuners

    /**
     * The world side of C's tuner functions (after the value is stored and the parameters updated).
     * <p>
     * BEHAVIOR: GRAVITY recomputes the gravity field; SHIP_MASS sets every player's empty mass; SHOTS_MAX every
     * player's shot limit; MAX_ROBOTS removes robots (kicked) while there are more than maxRobots.
     * BEHAVIOR: WORLD_RULES, PLAYER_SHIELDING, WORLD_LIVES and MODIFIERS recompute the world rules
     * ({@link #setWorldRules}). PLAYER_SHIELDING then gives every player shields (raised unless it is
     * firing, shield time 0) when shields are allowed, else 2 seconds of shield time.
     * BEHAVIOR: WORLD_LIVES with limitedLives resets every player (a new round) and a finished game
     * (gameDuration -1) restarts; RACE_LAPS does the same in race mode.
     * BEHAVIOR: MOVE_INIT and CANNONS_USE_ITEMS recompute the crash masks from the latched killing shots.
     * BEHAVIOR: ALLOW_ALLIANCES: in team play alliances are off; when they are off, existing alliances are
     * dissolved (C does not recompute the rules here, so turning alliances on takes effect at the next rules
     * update).
     * BEHAVIOR: WORM_TIME: every wormhole's countdown becomes wormTime, or WORMCOUNT without wormTime.
     * BEHAVIOR: GAME_DURATION sets the game's duration (the world's copy of the variable).
     */
    void runTuner(Tuner t) {
        Params p = w.params;
        switch (t) {
            case GRAVITY -> w.map.recomputeGravity(opts);
            case SHIP_MASS -> {
                for (int k = 0; k < w.numPlayers; k++) {
                    w.playerAt(k).emptyMass = p.shipMass;
                }
            }
            case SHOTS_MAX -> {
                for (int k = 0; k < w.numPlayers; k++) {
                    w.playerAt(k).shotMax = p.maxPlayerShots;
                }
            }
            case MAX_ROBOTS -> sim.robots.applyMaxRobots();
            case WORLD_RULES -> setWorldRules();
            case MODIFIERS -> {
                setWorldRules();
                var ip = w.items;
                for (int k = 0; k < w.numPlayers; k++) {
                    Player pl = w.playerAt(k);
                    pl.mods = com.fulcrologic.jxpilot.server.world.Mods.filter(pl.mods, ip.allowNukes,
                        ip.allowClusters, ip.allowModifiers, ip.allowLaserModifiers);
                }
            }
            case BALL_MASS -> {
                var o = w.objects;
                for (int k = 0; k < o.count(); k++) {
                    int h = o.active[k];
                    if (o.type[h] == com.fulcrologic.jxpilot.server.world.Obj.BALL) {
                        o.mass[h] = w.modeParams.ballMass;
                    }
                }
            }
            case MINE_LIFE -> reLife(com.fulcrologic.jxpilot.server.world.Obj.MINE, true);
            case MISSILE_LIFE -> reLife(com.fulcrologic.jxpilot.server.world.Obj.SMART_SHOT
                | com.fulcrologic.jxpilot.server.world.Obj.HEAT_SHOT | com.fulcrologic.jxpilot.server.world.Obj.TORPEDO,
                false);
            case ITEM_PROBS -> deleteExcessItems();
            case PLAYER_SHIELDING -> {
                setWorldRules();
                for (int k = 0; k < w.numPlayers; k++) {
                    Player pl = w.playerAt(k);
                    if (p.playerShielding) {
                        if (pl.tank) {
                            continue; // tuner.c:103-115: not tanks
                        }
                        if ((pl.used & Has.SHOT) == 0) {
                            pl.used |= Has.SHIELD;
                        }
                        pl.have |= Has.SHIELD;
                        pl.shieldTime = 0;
                    } else {
                        pl.shieldTime = 2 * p.fps;
                    }
                }
            }
            case WORLD_LIVES -> {
                setWorldRules();
                if (p.limitedLives) {
                    restartGame();
                }
            }
            case RACE_LAPS -> {
                if (w.map.timing()) {
                    restartGame();
                }
            }
            case MOVE_INIT -> {
                p.killingShots = killingShots;
                p.resolveMasks();
            }
            case CANNONS_USE_ITEMS -> {
                p.killingShots = killingShots;
                p.resolveMasks();
                for (int c = 0; c < w.map.cannons().size(); c++) {
                    for (int j = 0; j < com.fulcrologic.jxpilot.common.Item.COUNT; j++) {
                        w.cannonItems.set(c, j, 0);
                        if (p.cannonsUseItems) {
                            w.cannonItems.addItem(w.items, w.rng, c, j,
                                (int) (w.rfrac() * (w.items.initial[j] + 1)));
                        }
                    }
                }
            }
            case TEAM_CANNONS -> {
                for (int c = 0; c < w.map.cannons().size(); c++) {
                    var cn = w.map.cannons().get(c);
                    w.map.setCannonTeam(c, p.teamCannons ? w.map.closestTeam(cn.bx(), cn.by()) : Const.TEAM_NOT_SET);
                }
            }
            case ALLOW_ALLIANCES -> {
                if (p.teamPlay) {
                    sim.alliances.enabled = false;
                }
                if (!sim.alliances.enabled && sim.alliances.count() > 0) {
                    sim.alliances.dissolveAll();
                }
            }
            case WORM_TIME -> {
                var worms = w.worms;
                for (int i = 0; i < worms.count(); i++) {
                    if (p.wormTime != 0) {
                        worms.setCountdown(i, p.wormTime);
                    } else if (worms.temporary(i)) {
                        worms.removeTemp(i); // C's loop then skips the wormhole moved into i (tuner.c:228-232)
                    } else {
                        worms.setCountdown(i, WORMCOUNT);
                    }
                }
            }
            case ANNOUNCE_ALLIANCES -> {
                for (int k = 0; k < w.numPlayers; k++) {
                    Player pl = w.playerAt(k);
                    w.events.add(com.fulcrologic.jxpilot.server.world.Events.ALLIANCE, pl.id, pl.alliance, 0, 0f);
                }
            }
            case GAME_DURATION -> {
                w.gameDuration = p.gameDuration;
                long now = w.wallSeconds.getAsLong();
                w.gameOverTime = p.gameDuration <= 0 ? now : (long) (p.gameDuration * 60) + now;
            }
            default -> { }
        }
    }

    /**
     * C {@code tuner_minelife} / {@code tuner_missilelife}: every object of {@code types} not fired by a cannon
     * gets life (int)(rfrac() * L), L = (mineLife or else the default lifetime) / (mini + 1). The default is
     * MINE_LIFETIME (5000 + randomMT()&amp;255) for mines and MISSILE_LIFETIME for missiles, drawn per object;
     * as in C the missile tuner also uses mineLife, not missileLife (tuner.c:264-267).
     */
    void reLife(int types, boolean mines) {
        var o = w.objects;
        int mineLife = w.items.mineLife;
        int fps = w.params.fps;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if ((o.type[h] & types) == 0 || (o.status[h] & com.fulcrologic.jxpilot.server.world.Obj.FROMCANNON) != 0) {
                continue;
            }
            int base = mineLife != 0 ? mineLife
                : mines ? 5000 + (w.rng.nextInt() & 255)
                : Integer.remainderUnsigned(w.rng.nextInt(), 64 * fps - 1) + 128 * fps;
            int life = base / (com.fulcrologic.jxpilot.server.world.Mods.mini(o.mods[h]) + 1);
            o.life[h] = (int) (w.rfrac() * life);
        }
    }

    /**
     * C {@code Tune_item_probs}: for each item kind, items in the world beyond the new maximum are deleted, first
     * found first.
     */
    void deleteExcessItems() {
        var o = w.objects;
        for (int i = 0; i < com.fulcrologic.jxpilot.common.Item.COUNT; i++) {
            int excess = w.itemCount[i] - w.items.max[i];
            if (excess <= 0) {
                continue;
            }
            for (int k = 0; k < o.count(); k++) {
                int h = o.active[k];
                if (o.type[h] == com.fulcrologic.jxpilot.server.world.Obj.ITEM && o.info[h] == i) {
                    sim.items.delete(h);
                    k--;
                    if (--excess == 0) {
                        break;
                    }
                }
            }
        }
    }

    /** C {@code Reset_all_players} and {@code if (gameDuration == -1) gameDuration = 0}. */
    private void restartGame() {
        sim.rounds.resetAllPlayers();
        if (w.gameDuration == -1) {
            w.gameDuration = 0;
        }
    }

    /**
     * C {@code Set_world_rules}: the rules follow the options again; without player killing the killing shots
     * are cleared for good; {@code DEF_USED &= DEF_HAVE}; alliances follow allowAlliances outside team play.
     */
    void setWorldRules() {
        Params p = w.params;
        if (!p.playerKillings) {
            killingShots = 0;
        }
        defUsed &= p.defHave;
        sim.alliances.rulesChanged();
    }

    // ================================================================ Get_option_value

    /**
     * C {@code Get_option_value} into {@code out}.
     * <p>
     * BEHAVIOR: an unknown name is NO_OPTION; an option without the visible flag NOT_VISIBLE; a string without
     * a value NO_VALUE; position, list and void options UNDEFINED (C's position case falls through to the
     * error).
     * BEHAVIOR: int "%d"; real "%g" of the float; bool "true"/"false"; string as is; seconds the frames
     * (int)(s * fps) divided by fps (int). (C's per-second case is unreachable: no option has that type.)
     */
    int get(String name, StringBuilder out) {
        Opt o = find(name);
        if (o == null) {
            return NO_OPTION;
        }
        if (!o.visible()) {
            return NOT_VISIBLE;
        }
        int fps = opts.framesPerSecond();
        switch (o.type()) {
            case INT -> out.append(opts.getInt(o));
            case REAL -> out.append(CFormat.g(o == Opt.GAME_DURATION ? w.gameDuration : (float) opts.getDouble(o)));
            case BOOL -> out.append(opts.getBool(o) ? "true" : "false");
            case STRING -> {
                String v = opts.getString(o);
                if (v == null) {
                    return NO_VALUE;
                }
                out.append(v);
            }
            case SEC -> out.append(opts.getFrames(o) / fps);
            default -> {
                return UNDEFINED;
            }
        }
        return OK;
    }
}
