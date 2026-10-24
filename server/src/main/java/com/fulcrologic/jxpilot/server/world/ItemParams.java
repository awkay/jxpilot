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
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;

/**
 * Item, weapon and device options (C globals from {@code src/server/cmdline.c}) and the per-item world table
 * {@code World.items[]} ({@code src/server/rules.c} {@code Set_world_items}, {@code Set_item_chance},
 * {@code Set_initial_resources}, {@code Set_misc_item_limits}). Values are C {@code DFLOAT}s (float) where C
 * keeps them as floats.
 */
public final class ItemParams {
    /** C {@code MAX_FUEL} (rules.c) and the other C item maxima: 8 tanks, 15 afterburners, 99 for the rest. */
    static final int MAX_FUEL = 10000;

    // ---- the World.items[] table, indexed by Item ordinal
    /** {@code World.items[i].prob}: the itemXxxProb option. */
    public final float[] prob = new float[Item.COUNT];
    /** {@code chance}: 1 / (itemProbMult * prob * blocks * FPS), at least 1; 0 = never. */
    public final int[] chance = new int[Item.COUNT];
    /** {@code max}: most items of this kind floating in the world. */
    public final int[] max = new int[Item.COUNT];
    /** {@code min_per_pack}, {@code max_per_pack}. */
    public final int[] minPerPack = new int[Item.COUNT];
    public final int[] maxPerPack = new int[Item.COUNT];
    /** {@code limit}: most a player can carry (the maxXxx option within C's maximum). */
    public final int[] limit = new int[Item.COUNT];
    /** {@code initial}: what a player starts with (within the limit). */
    public final int[] initial = new int[Item.COUNT];
    /** {@code cannonprob}: weight for cannons picking up this item. */
    public final float[] cannonProb = new float[Item.COUNT];

    // ---- rules
    public boolean allowNukes;
    public boolean allowClusters;
    public boolean allowModifiers;
    public boolean allowLaserModifiers;
    public boolean allowSmartMissiles;
    public boolean allowHeatSeekers;
    public boolean allowTorpedoes;
    public boolean allowShipShapes;
    public boolean limitedVisibility;
    public boolean cloakedShield;
    public boolean lockOtherTeam;
    /** C {@code allowViewing}: paused players may look through any locked player's eyes. */
    public boolean allowViewing;
    public boolean loseItemDestroys;
    public boolean shieldedItemPickup;
    public boolean shieldedMining;
    public boolean laserIsStunGun;
    public boolean identifyMines;
    public boolean distinguishMissiles;
    public boolean ecmsReprogramMines;
    public boolean ecmsReprogramRobots;
    public boolean playersOnRadar;
    public boolean missilesOnRadar;
    public boolean minesOnRadar;
    public boolean nukesOnRadar;
    public boolean cannonsDefend;

    // ---- weapons
    public int nukeMinSmarts;
    public int nukeMinMines;
    public float nukeClusterDamage;
    /** C {@code mineFuseTime} in frames (0 = owner immune for ever). */
    public int mineFuseTime;
    public int mineLife;
    public float minMineSpeed;
    public int missileLife;
    public int baseMineRange;
    public int mineShotDetonateDistance;
    public int maxMissilesPerPack;
    public int maxMinesPerPack;

    // ---- scoring
    public float torpedoKillScoreMult;
    public float smartKillScoreMult;
    public float heatKillScoreMult;
    public float laserKillScoreMult;
    public float mineScoreMult;

    // ---- item world
    public float movingItemProb;
    public float randomItemProb;
    public float dropItemOnKillProb;
    public float detonateItemOnKillProb;
    public float destroyItemInCollisionProb;
    public float wallBounceDestroyItemProb;
    public float itemProbMult;
    public float cannonItemProbMult;
    public float maxItemDensity;
    public int itemConcentratorRadius;
    public float itemConcentratorProb;
    public float rogueHeatProb;
    public float rogueMineProb;
    public int maxOffensiveItems;
    public int maxDefensiveItems;
    public int tankScoreDecrement;

    /**
     * Read the options and build the item table for a map of {@code blocksX * blocksY} blocks at {@code fps}.
     * <p>
     * BEHAVIOR: pack sizes as C Set_world_items (fuel 0-0, mines 1-maxMinesPerPack, missiles 1-maxMissilesPerPack,
     * everything else 1-1); limits and initial counts as Set_initial_resources; probabilities limited as
     * Set_misc_item_limits (itemConcentratorRadius to [1, diagonal]); chances as {@link #setChance}.
     */
    public static ItemParams from(Options o, int blocksX, int blocksY, int fps) {
        ItemParams p = new ItemParams();
        p.allowNukes = o.getBool(Opt.ALLOW_NUKES);
        p.allowClusters = o.getBool(Opt.ALLOW_CLUSTERS);
        p.allowModifiers = o.getBool(Opt.ALLOW_MODIFIERS);
        p.allowLaserModifiers = o.getBool(Opt.ALLOW_LASER_MODIFIERS);
        p.allowSmartMissiles = o.getBool(Opt.ALLOW_SMART_MISSILES);
        p.allowHeatSeekers = o.getBool(Opt.ALLOW_HEAT_SEEKERS);
        p.allowTorpedoes = o.getBool(Opt.ALLOW_TORPEDOES);
        p.allowShipShapes = o.getBool(Opt.ALLOW_SHIP_SHAPES);
        p.limitedVisibility = o.getBool(Opt.LIMITED_VISIBILITY);
        p.cloakedShield = o.getBool(Opt.CLOAKED_SHIELD);
        p.lockOtherTeam = o.getBool(Opt.LOCK_OTHER_TEAM);
        p.allowViewing = o.getBool(Opt.ALLOW_VIEWING);
        p.loseItemDestroys = o.getBool(Opt.LOSE_ITEM_DESTROYS);
        p.shieldedItemPickup = o.getBool(Opt.SHIELDED_ITEM_PICKUP);
        p.shieldedMining = o.getBool(Opt.SHIELDED_MINING);
        p.laserIsStunGun = o.getBool(Opt.LASER_IS_STUN_GUN);
        p.identifyMines = o.getBool(Opt.IDENTIFY_MINES);
        p.distinguishMissiles = o.getBool(Opt.DISTINGUISH_MISSILES);
        p.ecmsReprogramMines = o.getBool(Opt.ECMS_REPROGRAM_MINES);
        p.ecmsReprogramRobots = o.getBool(Opt.ECMS_REPROGRAM_ROBOTS);
        p.playersOnRadar = o.getBool(Opt.PLAYERS_ON_RADAR);
        p.missilesOnRadar = o.getBool(Opt.MISSILES_ON_RADAR);
        p.minesOnRadar = o.getBool(Opt.MINES_ON_RADAR);
        p.nukesOnRadar = o.getBool(Opt.NUKES_ON_RADAR);
        p.cannonsDefend = o.getBool(Opt.CANNONS_DEFEND);

        p.nukeMinSmarts = o.getInt(Opt.NUKE_MIN_SMARTS);
        p.nukeMinMines = o.getInt(Opt.NUKE_MIN_MINES);
        p.nukeClusterDamage = f(o, Opt.NUKE_CLUSTER_DAMAGE);
        p.mineFuseTime = o.getFrames(Opt.MINE_FUSE_TIME);
        p.mineLife = o.getInt(Opt.MINE_LIFE);
        p.minMineSpeed = f(o, Opt.MIN_MINE_SPEED);
        p.missileLife = o.getInt(Opt.MISSILE_LIFE);
        p.baseMineRange = o.getInt(Opt.BASE_MINE_RANGE);
        p.mineShotDetonateDistance = o.getInt(Opt.MINE_SHOT_DETONATE_DISTANCE);
        p.maxMissilesPerPack = o.getInt(Opt.MAX_MISSILES_PER_PACK);
        p.maxMinesPerPack = o.getInt(Opt.MAX_MINES_PER_PACK);

        p.torpedoKillScoreMult = f(o, Opt.TORPEDO_KILL_SCORE_MULT);
        p.smartKillScoreMult = f(o, Opt.SMART_KILL_SCORE_MULT);
        p.heatKillScoreMult = f(o, Opt.HEAT_KILL_SCORE_MULT);
        p.laserKillScoreMult = f(o, Opt.LASER_KILL_SCORE_MULT);
        p.mineScoreMult = f(o, Opt.MINE_SCORE_MULT);

        p.movingItemProb = limit01(f(o, Opt.MOVING_ITEM_PROB));
        p.randomItemProb = limit01(f(o, Opt.RANDOM_ITEM_PROB));
        p.dropItemOnKillProb = limit01(f(o, Opt.DROP_ITEM_ON_KILL_PROB));
        p.detonateItemOnKillProb = limit01(f(o, Opt.DETONATE_ITEM_ON_KILL_PROB));
        p.destroyItemInCollisionProb = limit01(f(o, Opt.DESTROY_ITEM_IN_COLLISION_PROB));
        p.wallBounceDestroyItemProb = f(o, Opt.WALL_BOUNCE_DESTROY_ITEM_PROB);
        p.itemProbMult = f(o, Opt.ITEM_PROB_MULT);
        p.cannonItemProbMult = f(o, Opt.CANNON_ITEM_PROB_MULT);
        p.maxItemDensity = f(o, Opt.MAX_ITEM_DENSITY);
        int diagonal = (int) StrictMath.hypot(blocksX, blocksY);
        p.itemConcentratorRadius = Math.max(1, Math.min(o.getInt(Opt.ITEM_CONCENTRATOR_RADIUS), diagonal));
        p.itemConcentratorProb = limit01(f(o, Opt.ITEM_CONCENTRATOR_PROB));
        p.rogueHeatProb = f(o, Opt.ROGUE_HEAT_PROB);
        p.rogueMineProb = f(o, Opt.ROGUE_MINE_PROB);
        p.maxOffensiveItems = o.getInt(Opt.MAX_OFFENSIVE_ITEMS);
        p.maxDefensiveItems = o.getInt(Opt.MAX_DEFENSIVE_ITEMS);
        p.tankScoreDecrement = o.getInt(Opt.TANK_SCORE_DECREMENT);

        item(p, o, Item.FUEL, Opt.ITEM_ENERGY_PACK_PROB, Opt.INITIAL_FUEL, Opt.MAX_FUEL, MAX_FUEL, 0, 0);
        item(p, o, Item.TANK, Opt.ITEM_TANK_PROB, Opt.INITIAL_TANKS, Opt.MAX_TANKS, Player.MAX_TANKS, 1, 1);
        item(p, o, Item.ECM, Opt.ITEM_ECM_PROB, Opt.INITIAL_EC_MS, Opt.MAX_EC_MS, 99, 1, 1);
        item(p, o, Item.ARMOR, Opt.ITEM_ARMOR_PROB, Opt.INITIAL_ARMOR, Opt.MAX_ARMOR, 99, 1, 1);
        item(p, o, Item.MINE, Opt.ITEM_MINE_PROB, Opt.INITIAL_MINES, Opt.MAX_MINES, 99, 1, p.maxMinesPerPack);
        item(p, o, Item.MISSILE, Opt.ITEM_MISSILE_PROB, Opt.INITIAL_MISSILES, Opt.MAX_MISSILES, 99, 1,
            p.maxMissilesPerPack);
        item(p, o, Item.CLOAK, Opt.ITEM_CLOAK_PROB, Opt.INITIAL_CLOAKS, Opt.MAX_CLOAKS, 99, 1, 1);
        item(p, o, Item.SENSOR, Opt.ITEM_SENSOR_PROB, Opt.INITIAL_SENSORS, Opt.MAX_SENSORS, 99, 1, 1);
        item(p, o, Item.WIDEANGLE, Opt.ITEM_WIDEANGLE_PROB, Opt.INITIAL_WIDEANGLES, Opt.MAX_WIDEANGLES, 99, 1, 1);
        item(p, o, Item.REARSHOT, Opt.ITEM_REARSHOT_PROB, Opt.INITIAL_REARSHOTS, Opt.MAX_REARSHOTS, 99, 1, 1);
        item(p, o, Item.AFTERBURNER, Opt.ITEM_AFTERBURNER_PROB, Opt.INITIAL_AFTERBURNERS, Opt.MAX_AFTERBURNERS, 15,
            1, 1);
        item(p, o, Item.TRANSPORTER, Opt.ITEM_TRANSPORTER_PROB, Opt.INITIAL_TRANSPORTERS, Opt.MAX_TRANSPORTERS, 99,
            1, 1);
        item(p, o, Item.MIRROR, Opt.ITEM_MIRROR_PROB, Opt.INITIAL_MIRRORS, Opt.MAX_MIRRORS, 99, 1, 1);
        item(p, o, Item.DEFLECTOR, Opt.ITEM_DEFLECTOR_PROB, Opt.INITIAL_DEFLECTORS, Opt.MAX_DEFLECTORS, 99, 1, 1);
        item(p, o, Item.HYPERJUMP, Opt.ITEM_HYPER_JUMP_PROB, Opt.INITIAL_HYPER_JUMPS, Opt.MAX_HYPER_JUMPS, 99, 1, 1);
        item(p, o, Item.PHASING, Opt.ITEM_PHASING_PROB, Opt.INITIAL_PHASINGS, Opt.MAX_PHASINGS, 99, 1, 1);
        item(p, o, Item.LASER, Opt.ITEM_LASER_PROB, Opt.INITIAL_LASERS, Opt.MAX_LASERS, 99, 1, 1);
        item(p, o, Item.EMERGENCY_THRUST, Opt.ITEM_EMERGENCY_THRUST_PROB, Opt.INITIAL_EMERGENCY_THRUSTS,
            Opt.MAX_EMERGENCY_THRUSTS, 99, 1, 1);
        item(p, o, Item.TRACTOR_BEAM, Opt.ITEM_TRACTOR_BEAM_PROB, Opt.INITIAL_TRACTOR_BEAMS, Opt.MAX_TRACTOR_BEAMS,
            99, 1, 1);
        item(p, o, Item.AUTOPILOT, Opt.ITEM_AUTOPILOT_PROB, Opt.INITIAL_AUTOPILOTS, Opt.MAX_AUTOPILOTS, 99, 1, 1);
        item(p, o, Item.EMERGENCY_SHIELD, Opt.ITEM_EMERGENCY_SHIELD_PROB, Opt.INITIAL_EMERGENCY_SHIELDS,
            Opt.MAX_EMERGENCY_SHIELDS, 99, 1, 1);
        for (int i = 0; i < Item.COUNT; i++) {
            p.setChance(i, blocksX, blocksY, fps);
        }
        return p;
    }

    private static void item(ItemParams p, Options o, Item item, Opt prob, Opt initial, Opt max, int cMax,
                             int minPP, int maxPP) {
        int i = item.ordinal();
        p.prob[i] = f(o, prob);
        p.limit[i] = Math.max(0, Math.min(o.getInt(max), cMax));
        p.initial[i] = Math.max(0, Math.min(o.getInt(initial), p.limit[i]));
        p.minPerPack[i] = minPP;
        p.maxPerPack[i] = maxPP;
    }

    /** C {@code CANNON_USE_ITEM} (cannon.c:68): the items cannons can pick up. */
    public static final int CANNON_USE_ITEM = Item.FUEL.bit() | Item.WIDEANGLE.bit() | Item.REARSHOT.bit()
        | Item.AFTERBURNER.bit() | Item.SENSOR.bit() | Item.TRANSPORTER.bit() | Item.TANK.bit() | Item.MINE.bit()
        | Item.ECM.bit() | Item.LASER.bit() | Item.EMERGENCY_THRUST.bit() | Item.ARMOR.bit()
        | Item.TRACTOR_BEAM.bit() | Item.MISSILE.bit() | Item.PHASING.bit();

    /**
     * C {@code Set_item_chance}: per-frame spawn chance, world maximum and cannon weight of one item.
     * <p>
     * BEHAVIOR: chance = max(1, (int)(1 / (itemProbMult * prob * blocks * fps))) when itemProbMult * prob &gt; 0,
     * else 0; max = (int)(itemProbMult * maxItemDensity * blocks), at least 1 when positive, else 0.
     * BEHAVIOR: cannonProb = prob * (n / sum) * (maxItemDensity / 0.00012) over the cannon items with prob &gt; 0,
     * and 0 for items cannons cannot use.
     */
    void setChance(int item, int blocksX, int blocksY, int fps) {
        float mx = itemProbMult * maxItemDensity * blocksX * blocksY;
        if (itemProbMult * prob[item] > 0) {
            chance[item] = (int) (1.0 / (itemProbMult * prob[item] * blocksX * blocksY * fps));
            chance[item] = Math.max(chance[item], 1);
        } else {
            chance[item] = 0;
        }
        if (mx > 0) {
            max[item] = mx < 1 ? 1 : (int) mx;
        } else {
            max[item] = 0;
        }
        if ((CANNON_USE_ITEM & (1 << item)) == 0) {
            cannonProb[item] = 0;
            return;
        }
        float sum = 0;
        int num = 0;
        for (int i = 0; i < Item.COUNT; i++) {
            if (prob[i] > 0 && (CANNON_USE_ITEM & (1 << i)) != 0) {
                sum += prob[i];
                num++;
            }
        }
        cannonProb[item] = num != 0 ? (float) (prob[item] * (num / sum) * (maxItemDensity / 0.00012)) : 0;
    }

    private static float f(Options o, Opt opt) {
        return (float) o.getDouble(opt);
    }

    private static float limit01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
