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
package com.fulcrologic.jxpilot.server.robot;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.map.FuelStation;
import com.fulcrologic.jxpilot.common.map.Target;
import com.fulcrologic.jxpilot.common.map.Treasure;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Grid;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * The default robot type, a per-frame port of {@code src/server/robotdef.c} (XPilot 4.5.5): wall avoidance
 * ({@link #checkEvade}), flying to a point ({@link #checkTarget}), hunting a war target
 * ({@link #checkHunt}), target selection, shooting, shielding, refuelling and cannon/target attacks
 * ({@link #play}).
 * <p>
 * Like C, a robot drives its ship by writing the player's control state directly, never through keys. The
 * whole write surface is: {@code status} THRUSTING and SELF_DESTRUCT, {@code count}, {@code used} bits
 * (SHOT, SHIELD, CLOAKING_DEVICE, LASER, REFUEL, REPAIR, CONNECTOR, TRACTOR_BEAM), {@code fs},
 * {@code turnAcc}, {@code turnSpeed} and {@code power}, plus {@code Shots.fireNormalShots}. Everything that
 * needs items (M2) goes through {@link RobotItems}, whose S1 implementation makes those branches inert.
 * <p>
 * Numeric types follow C: pixel positions and block indices are ints, distances computed into {@code long}
 * or {@code int} variables are truncated as C does. ({@code DFLOAT} is {@code float} in 4.5.5; we compute in
 * double.) All randomness comes from {@link World#rfrac()}. No allocation.
 */
public final class DefaultRobot {
    // ---- robot modes (robotdef.c RM_*)
    public static final int RM_ROBOT_IDLE = 1 << 2;
    public static final int RM_EVADE_LEFT = 1 << 3;
    public static final int RM_EVADE_RIGHT = 1 << 4;
    public static final int RM_ROBOT_CLIMB = 1 << 5;
    public static final int RM_HARVEST = 1 << 6;
    public static final int RM_ATTACK = 1 << 7;
    public static final int RM_TAKE_OFF = 1 << 8;
    public static final int RM_CANNON_KILL = 1 << 9;
    public static final int RM_REFUEL = 1 << 10;
    public static final int RM_NAVIGATE = 1 << 11;

    // ---- long term modes
    public static final int FETCH_TREASURE = 1;
    public static final int TARGET_KILL = 1 << 1;
    public static final int NEED_FUEL = 1 << 2;

    // ---- lock bits (object.h)
    public static final int LOCK_NONE = 0;
    public static final int LOCK_PLAYER = 1;
    public static final int LOCK_VISIBLE = 2;

    // ---- item ranks
    static final int ROBOT_MUST_HAVE_ITEM = 2;
    static final int ROBOT_HANDY_ITEM = 1;
    static final int ROBOT_IGNORE_ITEM = 0;

    // ---- modifiers (object.h)
    static final int NUCLEAR = 1;
    static final int FULLNUCLEAR = 2;
    static final int CLUSTER = 1;
    static final int IMPLOSION = 2;
    static final int MODS_VELOCITY_MAX = 3;
    static final int MODS_MINI_MAX = 3;
    static final int MODS_SPREAD_MAX = 3;
    static final int MODS_POWER_MAX = 3;
    static final int MODS_LASER_MAX = 2;

    // ---- constants (const.h, serverconst.h, score.h)
    static final int RES = Const.RES;
    static final int BLOCK_SZ = Const.BLOCK_SZ;
    static final int SHIP_SZ = Const.SHIP_SZ;
    static final double MAX_TS = Const.MAX_PLAYER_TURNSPEED;
    static final double MAX_POWER = Const.MAX_PLAYER_POWER;
    static final double SPEED_LIMIT = 65.0;
    static final double VISIBILITY_DISTANCE = 1000.0;
    static final double ECM_DISTANCE = VISIBILITY_DISTANCE * 0.4;
    static final double TRANSPORTER_DISTANCE = VISIBILITY_DISTANCE * 0.2;
    static final double ENERGY_RANGE_FACTOR = 2.5 / 256;
    static final int PULSE_SPEED = 90;
    static final int PULSE_LENGTH = PULSE_SPEED - 5;
    static final double PULSE_MIN_LIFE = 4.5;
    static final double FUEL_SCALE = 256;
    static final double ED_TRANSPORTER = -60 * FUEL_SCALE;
    static final double ED_HYPERJUMP = -60 * FUEL_SCALE;
    static final double ED_LASER = -10 * FUEL_SCALE;
    static final double REFUEL_RATE = 5 * FUEL_SCALE;
    /** C {@code TARGET_DAMAGE} (250 &lt;&lt; FUEL_SCALE_BITS). */
    public static final double TARGET_DAMAGE = 250 << 8;
    /** C {@code NUM_IDS}. */
    static final int NUM_IDS = World.MAX_IDS;
    /** C max_objs in {@code Robot_default_play_check_objects}. */
    static final int MAX_OBJS = 1000;

    /** C {@code SPACE_BLOCKS} (walls.c): blocks robots treat as empty. */
    static final int SPACE_BLOCKS = BlockType.bit(BlockType.SPACE) | BlockType.bit(BlockType.BASE)
        | BlockType.bit(BlockType.WORMHOLE) | BlockType.bit(BlockType.POS_GRAV) | BlockType.bit(BlockType.NEG_GRAV)
        | BlockType.bit(BlockType.CWISE_GRAV) | BlockType.bit(BlockType.ACWISE_GRAV)
        | BlockType.bit(BlockType.UP_GRAV) | BlockType.bit(BlockType.DOWN_GRAV)
        | BlockType.bit(BlockType.RIGHT_GRAV) | BlockType.bit(BlockType.LEFT_GRAV)
        | BlockType.bit(BlockType.DECOR_LU) | BlockType.bit(BlockType.DECOR_LD) | BlockType.bit(BlockType.DECOR_RU)
        | BlockType.bit(BlockType.DECOR_RD) | BlockType.bit(BlockType.DECOR_FILLED) | BlockType.bit(BlockType.CHECK)
        | BlockType.bit(BlockType.ITEM_CONCENTRATOR) | BlockType.bit(BlockType.FRICTION)
        | BlockType.bit(BlockType.ASTEROID_CONCENTRATOR);

    private final World w;
    private final Simulation sim;
    private final RobotItems items;
    /** Our own object buckets (C {@code Cell_get_objects}), rebuilt each frame before the robots look. */
    private final Grid cells;

    // ---- options the C globals hold that Params does not (re-read by reloadOptions after /set)
    boolean cloakedShield;
    boolean wormholeVisible;
    boolean limitedVisibility;
    boolean allowSmartMissiles;
    boolean allowNukes;
    boolean allowClusters;
    boolean allowModifiers;
    boolean allowLaserModifiers;
    int nukeMinMines;
    int nukeMinSmarts;
    double ballConnectorLength;
    int maxOffensiveItems;
    int maxDefensiveItems;
    /** C {@code KILLING_SHOTS} plus balls/wreckage/asteroids when their collisions may kill. */
    int killingShots;

    /** C {@code Visibility_distance} (file static, set by {@link #roundTick}). */
    double visibilityDistance = VISIBILITY_DISTANCE;
    /** C {@code Max_enemy_distance}. */
    double maxEnemyDistance;

    public DefaultRobot(World w, Simulation sim, RobotItems items) {
        this.w = w;
        this.sim = sim;
        this.items = items;
        this.cells = new Grid(w.width, w.height, w.wrap, w.params.maxObjects);
        reloadOptions();
        maxEnemyDistance = w.params.hypotenuse;
    }

    /**
     * server-commands: (re)read the options this class keeps (at construction, and after an operator's
     * {@code /set} changed an option at run time).
     */
    public void reloadOptions() {
        Options o = w.options;
        cloakedShield = o.getBool(Opt.CLOAKED_SHIELD);
        wormholeVisible = o.getBool(Opt.WORMHOLE_VISIBLE);
        limitedVisibility = o.getBool(Opt.LIMITED_VISIBILITY);
        allowSmartMissiles = o.getBool(Opt.ALLOW_SMART_MISSILES);
        allowNukes = o.getBool(Opt.ALLOW_NUKES);
        allowClusters = o.getBool(Opt.ALLOW_CLUSTERS);
        allowModifiers = o.getBool(Opt.ALLOW_MODIFIERS);
        allowLaserModifiers = o.getBool(Opt.ALLOW_LASER_MODIFIERS);
        nukeMinMines = o.getInt(Opt.NUKE_MIN_MINES);
        nukeMinSmarts = o.getInt(Opt.NUKE_MIN_SMARTS);
        ballConnectorLength = o.getDouble(Opt.BALL_CONNECTOR_LENGTH);
        maxOffensiveItems = o.getInt(Opt.MAX_OFFENSIVE_ITEMS);
        maxDefensiveItems = o.getInt(Opt.MAX_DEFENSIVE_ITEMS);
        int ks = w.params.killingShots;
        if (o.getBool(Opt.TREASURE_COLLISION_MAY_KILL)) {
            ks |= Obj.BALL;
        }
        if (o.getBool(Opt.WRECKAGE_COLLISION_MAY_KILL)) {
            ks |= Obj.WRECKAGE;
        }
        if (o.getBool(Opt.ASTEROID_COLLISION_MAY_KILL)) {
            ks |= Obj.ASTEROID;
        }
        killingShots = ks;
    }

    // ================================================================ type methods

    /**
     * C {@code Robot_default_round_tick}, called every frame while there are robots.
     * <p>
     * BEHAVIOR: visibility = 256 + (744 * (NUM_IDS - robots)) / NUM_IDS.
     * BEHAVIOR: enemy distance = the world hypotenuse, reduced to 512 + ((hyp - 512) * (NUM_IDS - robots)) /
     * NUM_IDS when the hypotenuse exceeds the visibility.
     */
    void roundTick(int numRobots) {
        double minVisibility = 256.0;
        double minEnemyDistance = 512.0;
        visibilityDistance = minVisibility + (((VISIBILITY_DISTANCE - minVisibility) * (NUM_IDS - numRobots)) / NUM_IDS);
        double hyp = w.params.hypotenuse;
        maxEnemyDistance = hyp;
        if (hyp > visibilityDistance) {
            maxEnemyDistance = minEnemyDistance + (((hyp - minEnemyDistance) * (NUM_IDS - numRobots)) / NUM_IDS);
        }
    }

    /**
     * C {@code Robot_default_create}, after the manager set the fuel levels to 100/200/500 units.
     * <p>
     * BEHAVIOR: mode TAKE_OFF, count 0, no lock, no long term mode.
     * BEHAVIOR: a config scanned as two ints ({@code " %d %d"}) gives attack and defense unchanged; any other
     * non-empty config gives attack = (int)(rfrac*99.5), defense = 100 - attack, both limited to 1..99.
     * An empty config is undefined in C (uninitialised memory) and takes the random path here.
     * BEHAVIOR: speeds: race mode normal 10, attack 25 + attack/10, max 50 + attack/20 - defense/50;
     * otherwise normal 6, attack 15 + attack/25, max 30 + attack/50 - defense/50 (integer divisions).
     * BEHAVIOR: fuel levels move by the defense/attack balance plus a little noise (three rfrac calls).
     */
    void create(RobotData d, String config) {
        d.robotMode = RM_TAKE_OFF;
        d.robotCount = 0;
        d.robotLock = LOCK_NONE;
        d.robotLockId = 0;
        d.longtermMode = 0;
        if (scanAttackDefense(config)) {
            d.attack = scannedAttack;
            d.defense = scannedDefense;
        } else {
            d.attack = (int) (w.rfrac() * 99.5f);
            d.defense = 100 - d.attack;
            d.attack = limit(d.attack, 1, 99);
            d.defense = limit(d.defense, 1, 99);
        }
        if (w.map.timing()) {
            d.normalSpeed = 10.0;
            d.attackSpeed = 25.0 + (d.attack / 10);
            d.maxSpeed = 50.0 + (d.attack / 20) - (d.defense / 50);
        } else {
            d.normalSpeed = 6.0;
            d.attackSpeed = 15.0 + (d.attack / 25);
            d.maxSpeed = 30.0 + (d.attack / 50) - (d.defense / 50);
        }
        d.fuelL3 += d.defense - d.attack + (int) ((w.rfrac() - 0.5f) * 20);
        d.fuelL2 += 2 * (d.defense - d.attack) / 5 + (int) ((w.rfrac() - 0.5f) * 8);
        d.fuelL1 += (d.defense - d.attack) / 5 + (int) ((w.rfrac() - 0.5f) * 4);
        d.lastUsedEcm = 0;
        d.lastDroppedMine = 0;
        d.lastFiredMissile = 0;
        d.lastThrownBall = 0;
        d.longtermMode = 0;
    }

    private static int limit(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Scan position of {@link #scanInt} (a field, so scanning allocates nothing). */
    private int scanPos;
    /** Numbers read by {@link #scanAttackDefense}. */
    int scannedAttack;
    int scannedDefense;

    /**
     * C {@code sscanf(str, " %d %d", &attack, &defense) == 2}.
     * <p>
     * BEHAVIOR: white space, an optionally signed decimal int, white space, another; anything may follow.
     * Returns true and leaves the numbers in {@link #scannedAttack}/{@link #scannedDefense}, or false when
     * either number is missing.
     */
    boolean scanAttackDefense(String s) {
        if (s == null) {
            return false;
        }
        scanPos = 0;
        long a = scanInt(s);
        if (a == Long.MIN_VALUE) {
            return false;
        }
        long b = scanInt(s);
        if (b == Long.MIN_VALUE) {
            return false;
        }
        scannedAttack = (int) a;
        scannedDefense = (int) b;
        return true;
    }

    private long scanInt(String s) {
        int i = scanPos;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            neg = s.charAt(i) == '-';
            i++;
        }
        int start = i;
        long v = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            v = v * 10 + (s.charAt(i) - '0');
            i++;
        }
        if (i == start) {
            return Long.MIN_VALUE;
        }
        scanPos = i;
        return neg ? -v : v;
    }

    /** C {@code Robot_default_go_home}: BEHAVIOR: mode TAKE_OFF and no long term mode. */
    void goHome(RobotData d) {
        d.robotMode = RM_TAKE_OFF;
        d.longtermMode = 0;
    }

    /**
     * C {@code Robot_default_set_war}.
     * <p>
     * BEHAVIOR: NO_ID clears the LOCK_PLAYER bit (the id stays); any other id becomes the war target.
     */
    static void setWar(RobotData d, int victimId) {
        if (victimId == Obj.NO_ID) {
            d.robotLock &= ~LOCK_PLAYER;
        } else {
            d.robotLockId = victimId;
            d.robotLock |= LOCK_PLAYER;
        }
    }

    /** C {@code Robot_default_war_on_player}. BEHAVIOR: the war target id, or NO_ID when not at war. */
    static int warOnPlayer(RobotData d) {
        return (d.robotLock & LOCK_PLAYER) != 0 ? d.robotLockId : Obj.NO_ID;
    }

    // ================================================================ geometry helpers

    /** C {@code (int)findDir(x, y)}. */
    static int findDir(double x, double y) {
        return (int) XMath.findDir(x, y);
    }

    /** C {@code Wrap_findDir} truncated to int. */
    int wrapFindDir(double dx, double dy) {
        return (int) XMath.findDir(w.wrapDx(dx), w.wrapDy(dy));
    }

    /** C {@code WRAP_DX} of an int difference. */
    int wdx(int dx) {
        return (int) w.wrapDx(dx);
    }

    /** C {@code WRAP_DY} of an int difference. */
    int wdy(int dy) {
        return (int) w.wrapDy(dy);
    }

    /**
     * The direction a ship travels for the robot (repeated in several C functions).
     * <p>
     * BEHAVIOR: at speed &lt;= 0.2 the direction of the gravity at the ship's block, else of its velocity.
     */
    int travelDir(Player pl) {
        if (pl.velocity <= 0.2) {
            return findDir(w.gravityX(pl.bx, pl.by), w.gravityY(pl.bx, pl.by));
        }
        return findDir(pl.vx, pl.vy);
    }

    /**
     * C {@code Really_empty_space}: can the robot fly through block (x, y)?
     * <p>
     * BEHAVIOR: SPACE_BLOCKS are empty (wormholes included, so C's WORMHOLE case is unreachable and not ported).
     * BEHAVIOR: walls, fuel and treasures are not; an own-team target is empty only without targetTeamCollision
     * in team play; an own-team cannon only with teamImmunity in team play; anything else is not empty.
     */
    boolean reallyEmptySpace(Player pl, int x, int y) {
        int type = w.map.block(x, y);
        if ((BlockType.bit(type) & SPACE_BLOCKS) != 0) {
            return true;
        }
        Params p = w.params;
        if (type == BlockType.TARGET) {
            return !p.targetTeamCollision && p.teamPlay && w.map.targets().get(w.map.itemId(x, y)).team() == pl.team;
        }
        if (type == BlockType.CANNON) {
            return p.teamImmunity && p.teamPlay && w.map.cannons().get(w.map.itemId(x, y)).team() == pl.team;
        }
        return false;
    }

    /**
     * Wraps a block x in wrap play (one world width at most), as the robot scans do.
     * Returns {@code Integer.MIN_VALUE} when the block is outside the world.
     */
    private int blockX(long bx) {
        if (w.wrap) {
            if (bx < 0) {
                bx += w.width;
            } else if (bx >= w.width) {
                bx -= w.width;
            }
        }
        return bx < 0 || bx >= w.width ? Integer.MIN_VALUE : (int) bx;
    }

    private int blockY(long by) {
        if (w.wrap) {
            if (by < 0) {
                by += w.height;
            } else if (by >= w.height) {
                by -= w.height;
            }
        }
        return by < 0 || by >= w.height ? Integer.MIN_VALUE : (int) by;
    }

    /**
     * One look-ahead ray of {@code Check_robot_evade}: from (x0, y0) along {@code dir} in half-block steps
     * while {@code dist < stopDist + BLOCK_SZ/2}.
     * <p>
     * BEHAVIOR: blocked by a block outside the (unwrapped) world, a block that is not really empty, or a
     * block whose gravity is at least sqrt(0.5) and whose C "gravity direction" (findDir of the gravity minus
     * the ship position, a C quirk kept as is) is within a quarter turn of the travel direction.
     */
    boolean rayBlocked(Player pl, int x0, int y0, int dir, long stopDist, int travelDir) {
        for (long dist = 0; dist < stopDist + BLOCK_SZ / 2; dist += BLOCK_SZ / 2) {
            int x = blockX((long) ((x0 + dist * Trig.tcos(dir)) / BLOCK_SZ));
            int y = blockY((long) ((y0 + dist * Trig.tsin(dir)) / BLOCK_SZ));
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || !reallyEmptySpace(pl, x, y)) {
                return true;
            }
            double gx = w.map.gravityX(x, y);
            double gy = w.map.gravityY(x, y);
            if (gx * gx + gy * gy >= 0.5) {
                int gravityDir = findDir(gx - pl.px, gy - pl.py);
                int dd = Trig.mod(gravityDir - travelDir);
                if (dd <= RES / 4 || dd >= 3 * RES / 4) {
                    return true;
                }
            }
        }
        return false;
    }

    // ================================================================ Check_robot_evade

    /**
     * C {@code Check_robot_evade}: steer away from walls ahead, a nearby missile/mine and a nearby ship.
     * <p>
     * BEHAVIOR: looks ahead the stopping distance (RES*v/(64*turnresistance) + v^2*mass/110 + safe width,
     * v capped at 65, at most 10 blocks) along the travel direction from the ship centre and both sides.
     * BEHAVIOR: a mine/missile whose next position is less than a quarter turn left of the travel direction
     * blocks the left side, more than three quarters (i.e. to the right) the right side; the same for a ship's
     * position two frames ahead. Faster than the robot's max speed is also a reason to evade.
     * BEHAVIOR: no reason: returns false and changes nothing.
     * BEHAVIOR: with both sides blocked, tries directions RES/16 further out on both sides from the centre,
     * up to 7/8 of a turn.
     * BEHAVIOR: turnspeed and power go to maximum; unless already evading, the mode becomes EVADE_LEFT if only
     * the left is free, EVADE_RIGHT if only the right is, else by which side the heading is on.
     * BEHAVIOR: heading within a quarter turn of the travel direction: turn (left for EVADE_LEFT) and stop
     * thrusting; within 3/8: turn; else (facing backwards): stop turning, thrust and re-pick the side.
     *
     * @param mine handle of the nearest missile/mine or -1
     * @param ship the nearest ship to avoid, or null
     */
    boolean checkEvade(Player pl, RobotData d, int mine, Player ship) {
        int safeWidth = (d.defense / 200) * SHIP_SZ;
        double velocity = pl.velocity <= SPEED_LIMIT ? pl.velocity : SPEED_LIMIT;
        long stopDist = (long) ((RES * velocity) / (MAX_TS * pl.turnResistance)
            + (velocity * velocity * pl.mass) / (2 * MAX_POWER) + safeWidth);
        if (stopDist > 10 * BLOCK_SZ) {
            stopDist = 10 * BLOCK_SZ;
        }
        int travelDir = travelDir(pl);
        int auxDir = Trig.mod(travelDir + RES / 4);
        int px0 = pl.px;
        int py0 = pl.py;
        int px1 = (int) (px0 + safeWidth * Trig.tcos(auxDir));
        int py1 = (int) (py0 + safeWidth * Trig.tsin(auxDir));
        int px2 = 2 * px0 - px1;
        int py2 = 2 * py0 - py1;

        boolean centre = rayBlocked(pl, px0, py0, travelDir, stopDist, travelDir);
        boolean leftOk = !rayBlocked(pl, px1, py1, travelDir, stopDist, travelDir);
        boolean rightOk = !rayBlocked(pl, px2, py2, travelDir, stopDist, travelDir);
        boolean evade = centre || !leftOk || !rightOk;

        if (mine >= 0) {
            Objects o = w.objects;
            int a = wrapFindDir(o.px[mine] + o.vx[mine] - pl.px, o.py[mine] + o.vy[mine] - pl.py);
            int dd = Trig.mod(a - travelDir);
            if (dd < RES / 4) {
                leftOk = false;
                evade = true;
            }
            if (dd > RES * 3 / 4) {
                rightOk = false;
                evade = true;
            }
        }
        if (ship != null) {
            int a = wrapFindDir(ship.px - pl.px + ship.vx * 2, ship.py - pl.py + ship.vy * 2);
            int dd = Trig.mod(a - travelDir);
            if (dd < RES / 4) {
                leftOk = false;
                evade = true;
            }
            if (dd > RES * 3 / 4) {
                rightOk = false;
                evade = true;
            }
        }
        if (pl.velocity > d.maxSpeed) {
            evade = true;
        }
        if (!evade) {
            return false;
        }
        int deltaDir = 0;
        while (!leftOk && !rightOk && deltaDir < 7 * RES / 8) {
            deltaDir += RES / 16;
            leftOk = !rayBlocked(pl, px0, py0, Trig.mod(travelDir + deltaDir), stopDist, travelDir);
            rightOk = !rayBlocked(pl, px0, py0, Trig.mod(travelDir - deltaDir), stopDist, travelDir);
        }
        pl.turnSpeed = (float) MAX_TS;
        pl.power = (float) MAX_POWER;
        deltaDir = Trig.mod(pl.dir - travelDir);
        if (d.robotMode != RM_EVADE_LEFT && d.robotMode != RM_EVADE_RIGHT) {
            if (leftOk && !rightOk) {
                d.robotMode = RM_EVADE_LEFT;
            } else if (rightOk && !leftOk) {
                d.robotMode = RM_EVADE_RIGHT;
            } else {
                d.robotMode = deltaDir < RES / 2 ? RM_EVADE_LEFT : RM_EVADE_RIGHT;
            }
        }
        if (deltaDir < RES / 4 || deltaDir > 3 * RES / 4) {
            pl.turnAcc = d.robotMode == RM_EVADE_LEFT ? pl.turnSpeed : -pl.turnSpeed;
            pl.status &= ~Obj.THRUSTING;
        } else if (deltaDir < 3 * RES / 8 || deltaDir > 5 * RES / 8) {
            pl.turnAcc = d.robotMode == RM_EVADE_LEFT ? pl.turnSpeed : -pl.turnSpeed;
        } else {
            pl.turnAcc = 0;
            pl.status |= Obj.THRUSTING;
            d.robotMode = deltaDir < RES / 2 ? RM_EVADE_LEFT : RM_EVADE_RIGHT;
        }
        return true;
    }

    // ================================================================ modifiers

    /**
     * C {@code Robot_check_new_modifiers}: drop modifiers the rules forbid, then make them the player's
     * ({@link Player#mods}).
     * <p>
     * BEHAVIOR: no nukes clears nuclear; no clusters clears CLUSTER; no modifiers clears velocity, mini,
     * spread, power and IMPLOSION; no laser modifiers clears laser.
     */
    void setModifiers(Player pl, int nuclear, int warhead, int velocity, int mini, int spread, int power,
                      int laser) {
        if (!allowNukes) {
            nuclear = 0;
        }
        if (!allowClusters) {
            warhead &= ~CLUSTER;
        }
        if (!allowModifiers) {
            velocity = 0;
            mini = 0;
            spread = 0;
            power = 0;
            warhead &= ~IMPLOSION;
        }
        if (!allowLaserModifiers) {
            laser = 0;
        }
        int m = Mods.withNuclear(0, nuclear);
        m = Mods.withWarhead(m, warhead);
        m = Mods.withVelocity(m, velocity);
        m = Mods.withMini(m, mini);
        m = Mods.withSpread(m, spread);
        m = Mods.withPower(m, power);
        pl.mods = Mods.withLaser(m, laser);
    }

    /**
     * C {@code Choose_weapon_modifier}: the robot's modifiers ({@link Player#mods}) for its next weapon.
     * <p>
     * BEHAVIOR: tractor beam: no modifiers. Laser: every 4th count a random laser setting. Shots: every 4th
     * count a random spread. Other types return without changing the modifiers.
     * BEHAVIOR: mines/missiles (missiles pick a random power every 4th count): with at least the nuke minimum
     * in stock, nuclear when count%100 &lt;= attack, full nuclear with more than the minimum and (less than
     * twice it or an even count).
     * BEHAVIOR: above fuel level 3, even counts give cluster (and every 8th a random velocity); else every 3rd
     * count implosion. Every 10th count random mini and spread.
     */
    void chooseWeaponModifier(Player pl, RobotData d, int weaponType) {
        int nuclear = 0;
        int warhead = 0;
        int velocity = 0;
        int mini = 0;
        int spread = 0;
        int power = 0;
        int laser = 0;
        int stock;
        int min;
        int count = d.robotCount;
        if (weaponType == Has.TRACTOR_BEAM) {
            setModifiers(pl, 0, 0, 0, 0, 0, 0, 0);
            return;
        } else if (weaponType == Has.LASER) {
            if ((count % 4) == 0) {
                laser = (int) (w.rfrac() * (MODS_LASER_MAX + 1));
            }
            setModifiers(pl, 0, 0, 0, 0, 0, 0, laser);
            return;
        } else if (weaponType == Obj.SHOT) {
            if ((count % 4) == 0) {
                spread = (int) (w.rfrac() * (MODS_SPREAD_MAX + 1));
            }
            setModifiers(pl, 0, 0, 0, 0, spread, 0, 0);
            return;
        } else if (weaponType == Obj.MINE) {
            stock = items.count(pl, Item.MINE);
            min = nukeMinMines;
        } else if (weaponType == Obj.SMART_SHOT || weaponType == Obj.HEAT_SHOT || weaponType == Obj.TORPEDO) {
            stock = items.count(pl, Item.MISSILE);
            min = nukeMinSmarts;
            if ((count % 4) == 0) {
                power = (int) (w.rfrac() * (MODS_POWER_MAX + 1));
            }
        } else {
            return;
        }
        if (stock >= min) {
            if ((count % 100) <= d.attack) {
                nuclear |= NUCLEAR;
                if (stock > min && (stock < (2 * min) || (count % 2) == 0)) {
                    nuclear |= FULLNUCLEAR;
                }
            }
        }
        if (pl.fuel > d.fuelL3) {
            if ((count % 2) == 0) {
                if ((count % 8) == 0) {
                    velocity = (int) (w.rfrac() * MODS_VELOCITY_MAX) + 1;
                }
                warhead |= CLUSTER;
            }
        } else if ((count % 3) == 0) {
            warhead |= IMPLOSION;
        }
        if ((count % 10) == 0) {
            mini = (int) (w.rfrac() * (MODS_MINI_MAX + 1));
            spread = (int) (w.rfrac() * (MODS_SPREAD_MAX + 1));
        }
        setModifiers(pl, nuclear, warhead, velocity, mini, spread, power, laser);
    }

    // ================================================================ Check_robot_target

    /** C {@code WITHIN(NOW, THEN, DIFF)}: BEHAVIOR: {@code now <= then && then - now < diff}. */
    static boolean within(int now, int then, int diff) {
        return now <= then && (then - now) < diff;
    }

    /** Scratch out-values of the last {@link #checkTarget} path scan (for tests and the NAVIGATE logic). */
    long lastScanDist;

    /**
     * C {@code Check_robot_target}: fly towards (itemX, itemY) in {@code newMode} and use weapons on the way.
     * <p>
     * BEHAVIOR: the path is scanned in half-block steps up to the distance (90 less for REFUEL); a blocked
     * path fails (returns false, no change) except in NAVIGATE mode. CANNON_KILL aims 4 blocks short.
     * BEHAVIOR: turnspeed 32 and power 27.5 (55 in race mode); when the item is more than a quarter turn away
     * from the travel direction the robot turns hard and slows down (HARVEST and NAVIGATE reverse the travel
     * direction by 5/8 turn); a CANNON_KILL target closer than 4 blocks makes it turn away.
     * BEHAVIOR: NAVIGATE with a blocked path turns 3/4 away when the wall is 4-8 blocks ahead, half a turn when
     * nearer.
     * BEHAVIOR: turning stops within RES/64 of the wanted heading; otherwise turnAcc = +-64.
     * BEHAVIOR: thrust rules by mode and distance keep the speed between the robot's normal/attack/max speeds.
     * BEHAVIOR: ATTACK (and NAVIGATE in race mode) uses ECM, transporter, laser, tractor beam, missiles, normal
     * shots (on even counts within visibility, if the path is clear or every 50th count) and mines; a
     * CANNON_KILL that is not slowing fires on even counts with a clear path within visibility.
     * BEHAVIOR: the robot mode becomes newMode (or the EVADE mode chosen after dropping a mine); returns true.
     */
    boolean checkTarget(Player pl, RobotData d, int itemX, int itemY, int newMode) {
        Params p = w.params;
        long dx = wdx(itemX - pl.px);
        long dy = wdy(itemY - pl.py);
        long itemDist = (long) Math.hypot(dy, dx);
        int itemDir;
        if (dx == 0 && dy == 0) {
            itemDir = findDir(w.gravityX(pl.bx, pl.by), w.gravityY(pl.bx, pl.by));
            itemDir = Trig.mod(itemDir + RES / 2);
        } else {
            itemDir = findDir(dx, dy);
        }
        if (newMode == RM_REFUEL) {
            itemDist = itemDist - 90;
        }
        boolean clearPath = true;
        long dist;
        for (dist = 0; clearPath && dist < itemDist; dist += BLOCK_SZ / 2) {
            int x = blockX((long) ((pl.px + dist * Trig.tcos(itemDir)) / BLOCK_SZ));
            int y = blockY((long) ((pl.py + dist * Trig.tsin(itemDir)) / BLOCK_SZ));
            if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || !reallyEmptySpace(pl, x, y)) {
                clearPath = false;
            }
        }
        lastScanDist = dist;
        if (newMode == RM_CANNON_KILL) {
            itemDist -= 4 * BLOCK_SZ;
        }
        if (!clearPath && newMode != RM_NAVIGATE) {
            return false;
        }
        int travelDir = travelDir(pl);
        pl.turnSpeed = (float) (MAX_TS / 2);
        pl.power = (float) (w.map.timing() ? MAX_POWER : MAX_POWER / 2);
        boolean slowing;
        int deltaDir = Trig.mod(itemDir - travelDir);
        if (deltaDir >= RES / 4 && deltaDir <= 3 * RES / 4) {
            if (newMode == RM_HARVEST || (newMode == RM_NAVIGATE && (clearPath || dist > 8 * BLOCK_SZ))) {
                itemDir = Trig.mod(travelDir + (deltaDir > RES / 2 ? -5 * RES / 8 : 5 * RES / 8));
            }
            pl.turnSpeed = (float) MAX_TS;
            slowing = true;
            if (items.count(pl, Item.MINE) > 0 && itemDist < 8 * BLOCK_SZ) {
                chooseWeaponModifier(pl, d, Obj.MINE);
                items.placeMine(pl, !w.map.timing());
                newMode = (w.rfrac() < 0.5f) ? RM_EVADE_RIGHT : RM_EVADE_LEFT;
            }
        } else if (newMode == RM_CANNON_KILL && itemDist <= 0) {
            pl.turnSpeed = (float) MAX_TS;
            itemDir = Trig.mod(itemDir + RES / 2);
            slowing = true;
        } else {
            slowing = false;
        }
        if (newMode == RM_NAVIGATE && !clearPath) {
            if (dist <= 8 * BLOCK_SZ && dist > 4 * BLOCK_SZ) {
                itemDir = Trig.mod(itemDir + (deltaDir > RES / 2 ? -3 * RES / 4 : 3 * RES / 4));
            } else if (dist <= 4 * BLOCK_SZ) {
                itemDir = Trig.mod(itemDir + RES / 2);
            }
            pl.turnSpeed = (float) MAX_TS;
            slowing = true;
        }

        deltaDir = Trig.mod(itemDir - pl.dir);
        if (deltaDir > RES / 64 && deltaDir < 63 * RES / 64) {
            // C tests RES/8, RES/16 and RES/64 in turn; every branch sets the maximum turn speed.
            pl.turnSpeed = (float) MAX_TS;
        } else {
            pl.turnSpeed = 0.0f;
        }
        pl.turnAcc = deltaDir < RES / 2 ? pl.turnSpeed : -pl.turnSpeed;

        thrustTowards(pl, d, newMode, slowing, itemDist, dist, deltaDir, clearPath);

        if (newMode == RM_ATTACK || (w.map.timing() && newMode == RM_NAVIGATE)) {
            attackWeapons(pl, d, newMode, itemDist, clearPath);
        }
        if (newMode == RM_CANNON_KILL && !slowing) {
            if ((d.robotCount % 2) == 0 && itemDist < visibilityDistance && clearPath) {
                chooseWeaponModifier(pl, d, Obj.SHOT);
                sim.shots.fireNormalShots(pl);
            }
        }
        d.robotMode = newMode;
        return true;
    }

    /** The thrust decision of {@code Check_robot_target}. */
    private void thrustTowards(Player pl, RobotData d, int newMode, boolean slowing, long itemDist, long dist,
                               int deltaDir, boolean clearPath) {
        double v = pl.velocity;
        if (slowing || (pl.used & Has.SHIELD) != 0) {
            pl.status |= Obj.THRUSTING;
        } else if (itemDist < 0) {
            pl.status &= ~Obj.THRUSTING;
        } else if (itemDist < 3 * BLOCK_SZ && newMode != RM_HARVEST) {
            thrustBand(pl, v, d.normalSpeed / 2, d.normalSpeed);
        } else if ((newMode != RM_ATTACK && newMode != RM_NAVIGATE)
            || itemDist < 8 * BLOCK_SZ
            || (newMode == RM_NAVIGATE && deltaDir > 3 * RES / 8 && deltaDir < 5 * RES / 8)) {
            thrustBand(pl, v, 2 * d.normalSpeed, 3 * d.normalSpeed);
        } else if (newMode == RM_ATTACK
            || (newMode == RM_NAVIGATE && (dist < 12 * BLOCK_SZ || (deltaDir > RES / 8 && deltaDir < 7 * RES / 8)))) {
            thrustBand(pl, v, d.attackSpeed / 2, d.attackSpeed);
        } else if (clearPath && (deltaDir < RES / 8 || deltaDir > 7 * RES / 8) && itemDist > 18 * BLOCK_SZ) {
            thrustBand(pl, v, d.maxSpeed - d.normalSpeed, d.maxSpeed);
        } else {
            thrustBand(pl, v, d.attackSpeed, d.maxSpeed - d.normalSpeed);
        }
    }

    /** BEHAVIOR: thrust below {@code low}, stop above {@code high}, keep the current setting in between. */
    static void thrustBand(Player pl, double v, double low, double high) {
        if (v < low) {
            pl.status |= Obj.THRUSTING;
        }
        if (v > high) {
            pl.status &= ~Obj.THRUSTING;
        }
    }

    /** The weapons part of {@code Check_robot_target} for ATTACK (and NAVIGATE in race mode). */
    private void attackWeapons(Player pl, RobotData d, int newMode, long itemDist, boolean clearPath) {
        if (items.count(pl, Item.ECM) > 0 && itemDist < ECM_DISTANCE / 4) {
            items.fireEcm(pl);
        } else if (items.count(pl, Item.TRANSPORTER) > 0 && itemDist < TRANSPORTER_DISTANCE
            && pl.fuel > -ED_TRANSPORTER) {
            items.transporter(pl);
        } else if (items.count(pl, Item.LASER) > items.numPulses(pl) && pl.fuel + ED_LASER > d.fuelL3
            && newMode == RM_ATTACK) {
            aimLaser(pl, d);
        } else if ((items.have(pl) & Has.TRACTOR_BEAM) != 0) {
            tractorBeam(pl, d);
        }
        if ((pl.used & Has.LASER) != 0) {
            pl.turnAcc = 0.0f;
            chooseWeaponModifier(pl, d, Has.LASER);
        } else if ((d.robotCount % 10) < items.count(pl, Item.MISSILE)
            && !within(d.robotCount, d.lastFiredMissile, 10)) {
            int type;
            switch (d.robotCount % 5) {
                case 0, 1, 2 -> type = Obj.SMART_SHOT;
                case 3 -> type = Obj.HEAT_SHOT;
                default -> type = Obj.TORPEDO;
            }
            Player locked = w.byId(pl.lockPlId);
            if (locked != null && detectHunt(pl, locked) && !items.canSee(pl, locked)) {
                type = Obj.HEAT_SHOT;
            }
            if (type == Obj.SMART_SHOT && !allowSmartMissiles) {
                type = Obj.HEAT_SHOT;
            }
            chooseWeaponModifier(pl, d, type);
            items.fireMissile(pl, type, pl.dir);
            if (type == Obj.HEAT_SHOT) {
                pl.status &= ~Obj.THRUSTING;
            }
            d.lastFiredMissile = d.robotCount;
        } else if ((d.robotCount % 2) == 0 && itemDist < visibilityDistance) {
            if ((int) (w.rfrac() * 64) < items.count(pl, Item.MISSILE)) {
                chooseWeaponModifier(pl, d, Obj.SMART_SHOT);
                items.fireMissile(pl, Obj.SMART_SHOT, pl.dir);
                d.lastFiredMissile = d.robotCount;
            } else if ((newMode == RM_ATTACK && clearPath) || (d.robotCount % 50) == 0) {
                chooseWeaponModifier(pl, d, Obj.SHOT);
                sim.shots.fireNormalShots(pl);
            }
        } else if ((d.robotCount % 32) < items.count(pl, Item.MINE)
            && !within(d.robotCount, d.lastDroppedMine, 10)) {
            if (pl.fuel > d.fuelL3) {
                chooseWeaponModifier(pl, d, Obj.MINE);
                items.placeMine(pl, false);
            } else {
                items.placeMine(pl, false);
                pl.used &= ~Has.CLOAKING_DEVICE;
            }
            d.lastDroppedMine = d.robotCount;
        }
    }

    /**
     * Laser aiming of {@code Check_robot_target} (gated: needs lasers).
     * <p>
     * BEHAVIOR: aims at the war target if it plays, else at the locked player; fires (sets used LASER) when the
     * target, one frame ahead, is in pulse range and the heading lies between the directions to its two sides.
     */
    void aimLaser(Player pl, RobotData d) {
        Player ship = null;
        Player war = w.byId(d.robotLockId);
        if ((d.robotLock & LOCK_PLAYER) != 0 && war != null && playing(war)) {
            ship = war;
        } else if ((pl.lockTagged & LOCK_PLAYER) != 0) {
            ship = w.byId(pl.lockPlId);
        }
        if (ship == null || !playing(ship)) {
            return;
        }
        double x1 = pl.px + pl.vx + pl.ship.gunX[pl.dir];
        double y1 = pl.py + pl.vy + pl.ship.gunY[pl.dir];
        double x3 = ship.px + ship.vx;
        double y3 = ship.py + ship.vy;
        double shipDist = w.wrapLength(x3 - x1, y3 - y1);
        if (shipDist < PULSE_SPEED * (PULSE_MIN_LIFE + (items.count(pl, Item.LASER) / 4)) + SHIP_SZ) {
            double dir3 = XMath.findDir(w.wrapDx(x3 - x1), w.wrapDy(y3 - y1));
            double x4 = x3 + Trig.tcos(Trig.mod((int) (dir3 - RES / 4))) * SHIP_SZ;
            double y4 = y3 + Trig.tsin(Trig.mod((int) (dir3 - RES / 4))) * SHIP_SZ;
            double x5 = x3 + Trig.tcos(Trig.mod((int) (dir3 + RES / 4))) * SHIP_SZ;
            double y5 = y3 + Trig.tsin(Trig.mod((int) (dir3 + RES / 4))) * SHIP_SZ;
            double dir4 = XMath.findDir(w.wrapDx(x4 - x1), w.wrapDy(y4 - y1));
            double dir5 = XMath.findDir(w.wrapDx(x5 - x1), w.wrapDy(y5 - y1));
            if ((dir4 > dir5) ? (pl.dir >= dir4 || pl.dir <= dir5) : (pl.dir >= dir4 && pl.dir <= dir5)) {
                pl.used |= Has.LASER;
            }
        }
    }

    /**
     * Tractor beam use of {@code Check_robot_target} (gated: needs the tractor beam).
     * <p>
     * BEHAVIOR: off unless locked, above fuel level 3 and in range (200 + 50 per item); at normal speed or
     * below: push (pressor) a ship within 4 ship sizes or one closing faster than the attack speed, pull one
     * moving away between normal and max speed. A used beam marks the lock VISIBLE.
     */
    void tractorBeam(Player pl, RobotData d) {
        pl.used &= ~Has.TRACTOR_BEAM;
        pl.tractorIsPressor = false;
        Player ship = w.byId(pl.lockPlId);
        if ((pl.lockTagged & LOCK_PLAYER) != 0 && ship != null && pl.fuel > d.fuelL3
            && pl.lockDistance < 200 + items.count(pl, Item.TRACTOR_BEAM) * 50) {
            double xvd = ship.vx - pl.vx;
            double yvd = ship.vy - pl.vy;
            double vel = Math.hypot(xvd, yvd);
            long dir = (long) (XMath.findDir(pl.px - ship.px, pl.py - ship.py) - XMath.findDir(xvd, yvd));
            dir = Trig.mod((int) dir);
            boolean away = dir >= RES / 4 && dir <= 3 * RES / 4;
            if (pl.velocity <= d.normalSpeed) {
                if (pl.lockDistance < (SHIP_SZ * 4) || (!away && vel > d.attackSpeed)) {
                    pl.used |= Has.TRACTOR_BEAM;
                    pl.tractorIsPressor = true;
                } else if (away && vel < d.maxSpeed && vel > d.normalSpeed) {
                    pl.used |= Has.TRACTOR_BEAM;
                }
            }
            if ((pl.used & Has.TRACTOR_BEAM) != 0) {
                pl.lockTagged |= LOCK_VISIBLE;
            }
        }
    }

    // ================================================================ hunting

    /** {@code BIT(status, PLAYING|PAUSE|GAME_OVER) == PLAYING}. */
    static boolean playing(Player p) {
        return (p.status & (Obj.PLAYING | Obj.PAUSE | Obj.GAME_OVER)) == Obj.PLAYING;
    }

    /**
     * C {@code Check_robot_hunt}: fly alongside the war target.
     * <p>
     * BEHAVIOR: only at war (not with itself), at or above fuel level 3, with the target detected.
     * BEHAVIOR: at a speed between half the attack speed and the attack speed, travelling within RES/16 of
     * the target: coast (no turn, no thrust).
     * BEHAVIOR: otherwise aim a quarter turn off the travel direction toward the target side (half that when
     * too slow, 1.5 times when too fast); turn at 16 when more than RES/16 off; thrust when within RES/8.
     * BEHAVIOR: the mode becomes IDLE and it returns true.
     */
    boolean checkHunt(Player pl, RobotData d) {
        if ((d.robotLock & LOCK_PLAYER) == 0 || d.robotLockId == pl.id) {
            return false;
        }
        if (pl.fuel < d.fuelL3) {
            return false;
        }
        Player ship = w.byId(d.robotLockId);
        if (ship == null || !detectHunt(pl, ship)) {
            return false;
        }
        int shipDir = wrapFindDir(ship.px - pl.px, ship.py - pl.py);
        int travelDir = travelDir(pl);
        int deltaDir = Trig.mod(shipDir - travelDir);
        boolean tooslow = pl.velocity < d.attackSpeed / 2;
        boolean toofast = pl.velocity > d.attackSpeed;
        if (!tooslow && !toofast && (deltaDir <= RES / 16 || deltaDir >= 15 * RES / 16)) {
            pl.turnAcc = 0;
            pl.status &= ~Obj.THRUSTING;
            d.robotMode = RM_ROBOT_IDLE;
            return true;
        }
        int adjDir = deltaDir < RES / 2 ? RES / 4 : -RES / 4;
        if (tooslow) {
            adjDir = adjDir / 2;
        }
        if (toofast) {
            adjDir = 3 * adjDir / 2;
        }
        adjDir = Trig.mod(travelDir + adjDir);
        deltaDir = Trig.mod(adjDir - pl.dir);
        if (deltaDir >= RES / 16 && deltaDir <= 15 * RES / 16) {
            pl.turnSpeed = (float) (MAX_TS / 4);
            pl.turnAcc = deltaDir < RES / 2 ? pl.turnSpeed : -pl.turnSpeed;
        }
        if (deltaDir < RES / 8 || deltaDir > 7 * RES / 8) {
            pl.status |= Obj.THRUSTING;
        } else {
            pl.status &= ~Obj.THRUSTING;
        }
        d.robotMode = RM_ROBOT_IDLE;
        return true;
    }

    /**
     * C {@code Detect_hunt}: can the robot go after {@code ship}?
     * <p>
     * BEHAVIOR: not if the ship is not playing (or killed) or phased; yes if visible; a cloaked ship only
     * within visibility distance when its exhaust shows (thrusting with cloakedExhaust), it uses shots,
     * laser, refuel, repair, connector or tractor beam, or it carries a ball.
     */
    boolean detectHunt(Player pl, Player ship) {
        if ((ship.status & (Obj.PLAYING | Obj.PAUSE | Obj.GAME_OVER | Obj.KILLED)) != Obj.PLAYING) {
            return false;
        }
        if ((ship.used & Has.PHASING_DEVICE) != 0) {
            return false;
        }
        if (items.canSee(pl, ship)) {
            return true;
        }
        int dx = wdx(ship.px - pl.px);
        int dy = wdy(ship.py - pl.py);
        if ((double) dx * dx + (double) dy * dy > visibilityDistance * visibilityDistance) {
            return false;
        }
        if ((ship.status & Obj.THRUSTING) != 0 && w.params.cloakedExhaust) {
            return true;
        }
        if ((ship.used & (Has.SHOT | Has.LASER | Has.REFUEL | Has.REPAIR | Has.CONNECTOR | Has.TRACTOR_BEAM)) != 0) {
            return true;
        }
        return (ship.have & Has.BALL) != 0;
    }

    // ================================================================ items

    /** C {@code IsOffensiveItem} (collision.c). */
    static final int OFFENSIVE_ITEMS = Item.WIDEANGLE.bit() | Item.REARSHOT.bit() | Item.MINE.bit() | Item.MISSILE.bit()
        | Item.LASER.bit();
    /** C {@code IsDefensiveItem}. */
    static final int DEFENSIVE_ITEMS = Item.CLOAK.bit() | Item.ECM.bit() | Item.TRANSPORTER.bit()
        | Item.TRACTOR_BEAM.bit() | Item.EMERGENCY_SHIELD.bit() | Item.MIRROR.bit() | Item.DEFLECTOR.bit()
        | Item.HYPERJUMP.bit() | Item.PHASING.bit() | Item.TANK.bit() | Item.ARMOR.bit();
    private static final Item[] ITEMS = Item.values();

    /** C {@code CountOffensiveItems}: the offensive item counts summed. */
    int countOffensive(Player pl) {
        int n = 0;
        for (Item i : ITEMS) {
            if ((OFFENSIVE_ITEMS & i.bit()) != 0) {
                n += items.count(pl, i);
            }
        }
        return n;
    }

    /**
     * C {@code CountDefensiveItems}: the defensive item counts (tanks counted by the tank list, not the
     * TANK item) plus one for an active emergency shield or phasing.
     */
    int countDefensive(Player pl) {
        int n = items.numTanks(pl) + items.activeDefensives(pl);
        for (Item i : ITEMS) {
            if ((DEFENSIVE_ITEMS & i.bit()) != 0 && i != Item.TANK) {
                n += items.count(pl, i);
            }
        }
        return n;
    }

    /**
     * C {@code Rank_item_value}: 2 must have, 1 handy, 0 ignore.
     * <p>
     * BEHAVIOR: autopilot, a full item and a full offensive/defensive class are ignored.
     * BEHAVIOR: fuel: ignored at 90% full, must-have below level 1 (race) / level 2, else handy.
     * BEHAVIOR: race mode: afterburner, emergency thrust, transporter, rear shot, mine and ECM are musts;
     * cloak, laser, missile, sensor, tank, tractor beam, hyperjump, phasing, deflector and armor ignored.
     * BEHAVIOR: otherwise emergency shield, deflector, armor are handy with shields else musts; rear shot and
     * wide angle handy when shots cannot kill else musts; missiles ignored when shots cannot kill else musts;
     * mines and cloaks musts; lasers musts with player killing else handy; phasing ignored. Others handy.
     */
    int rankItemValue(Player pl, RobotData d, Item item) {
        Params p = w.params;
        if (item == Item.AUTOPILOT) {
            return ROBOT_IGNORE_ITEM;
        }
        if (items.count(pl, item) >= items.limit(item)) {
            return ROBOT_IGNORE_ITEM;
        }
        if (((DEFENSIVE_ITEMS & item.bit()) != 0 && countDefensive(pl) >= maxDefensiveItems)
            || ((OFFENSIVE_ITEMS & item.bit()) != 0 && countOffensive(pl) >= maxOffensiveItems)) {
            return ROBOT_IGNORE_ITEM;
        }
        boolean timing = w.map.timing();
        if (item == Item.FUEL) {
            if (pl.fuel >= pl.fuelMax * 0.90) {
                return ROBOT_IGNORE_ITEM;
            } else if (pl.fuel < (timing ? d.fuelL1 : d.fuelL2)) {
                return ROBOT_MUST_HAVE_ITEM;
            }
            return ROBOT_HANDY_ITEM;
        }
        boolean shotsKill = !(p.maxPlayerShots <= 0 || p.shotLife <= 0 || !p.playerKillings);
        if (timing) {
            switch (item) {
                case AFTERBURNER, EMERGENCY_THRUST, TRANSPORTER, REARSHOT, MINE, ECM -> {
                    return ROBOT_MUST_HAVE_ITEM;
                }
                case CLOAK, LASER, MISSILE, SENSOR, TANK, TRACTOR_BEAM, HYPERJUMP, PHASING, DEFLECTOR, ARMOR -> {
                    return ROBOT_IGNORE_ITEM;
                }
                default -> {
                    return ROBOT_HANDY_ITEM;
                }
            }
        }
        switch (item) {
            case EMERGENCY_SHIELD, DEFLECTOR, ARMOR -> {
                return (pl.have & Has.SHIELD) != 0 ? ROBOT_HANDY_ITEM : ROBOT_MUST_HAVE_ITEM;
            }
            case REARSHOT, WIDEANGLE -> {
                return shotsKill ? ROBOT_MUST_HAVE_ITEM : ROBOT_HANDY_ITEM;
            }
            case MISSILE -> {
                return shotsKill ? ROBOT_MUST_HAVE_ITEM : ROBOT_IGNORE_ITEM;
            }
            case MINE, CLOAK -> {
                return ROBOT_MUST_HAVE_ITEM;
            }
            case LASER -> {
                return p.playerKillings ? ROBOT_MUST_HAVE_ITEM : ROBOT_HANDY_ITEM;
            }
            case PHASING -> {
                return ROBOT_IGNORE_ITEM;
            }
            default -> {
                return ROBOT_HANDY_ITEM;
            }
        }
    }

    // ================================================================ Ball_handler

    /**
     * C {@code Ball_handler} (gated: balls do not exist yet, see {@link RobotItems#towedBall}).
     * <p>
     * BEHAVIOR: carrying a ball: throw it (detach) when it flies straight at the nearest own treasure with a
     * clear path, faster than sqrt(60) and nobody is nearer to it; else navigate to that treasure.
     * BEHAVIOR: not carrying: navigate to the nearest enemy treasure that has its ball (team with members),
     * if no loose enemy ball is nearer and it is within count/10 blocks; or to the nearest such ball beyond
     * the connector length.
     */
    boolean ballHandler(Player pl, RobotData d) {
        java.util.List<Treasure> ts = w.map.treasures();
        int closestT = -1;
        int closestNt = -1;
        int closestTDist = Integer.MAX_VALUE;
        int closestNtDist = Integer.MAX_VALUE;
        int towed = items.towedBall(pl);
        boolean carrying = (pl.have & Has.BALL) != 0 || towed >= 0;
        for (int i = 0; i < ts.size(); i++) {
            Treasure t = ts.get(i);
            if (carrying && t.team() == pl.team) {
                int dist = (int) w.wrapLength((t.bx() + 0.5) * BLOCK_SZ - pl.px, (t.by() + 0.5) * BLOCK_SZ - pl.py);
                if (dist < closestTDist) {
                    closestT = i;
                    closestTDist = dist;
                }
            } else if (t.team() != pl.team && t.team() >= 0 && w.teamMembers[t.team()] > 0 && !carrying
                && items.treasureHasBall(i)) {
                int dist = (int) w.wrapLength((t.bx() + 0.5) * BLOCK_SZ - pl.px, (t.by() + 0.5) * BLOCK_SZ - pl.py);
                if (dist < closestNtDist) {
                    closestNt = i;
                    closestNtDist = dist;
                }
            }
        }
        Objects o = w.objects;
        if (carrying) {
            int ball = towed;
            if (ball < 0) {
                for (int k = 0; k < o.count(); k++) {
                    int h = o.active[k];
                    if (o.type[h] == Obj.BALL && o.owner[h] == pl.id) {
                        ball = h;
                        break;
                    }
                }
            }
            if (ball < 0 || closestT < 0) {
                return false;
            }
            int distNp = Integer.MAX_VALUE;
            for (int k = 0; k < w.numPlayers; k++) {
                Player q = w.playerAt(k);
                int dist = (int) Math.hypot(o.px[ball] - q.px, o.py[ball] - q.py);
                if (q.id != pl.id && playing(q) && dist < distNp) {
                    distNp = dist;
                }
            }
            Treasure t = ts.get(closestT);
            int bdir = findDir(o.vx[ball], o.vy[ball]);
            int tdir = wrapFindDir((t.bx() + 0.5) * BLOCK_SZ - o.px[ball], (t.by() + 0.5) * BLOCK_SZ - o.py[ball]);
            int ballBx = o.px[ball] / BLOCK_SZ;
            int ballBy = o.py[ball] / BLOCK_SZ;
            int xdist = t.bx() - ballBx;
            int ydist = t.by() - ballBy;
            boolean clearPath = true;
            for (int dist = 0; clearPath && dist < (closestTDist - BLOCK_SZ); dist += BLOCK_SZ / 2) {
                double fraction = (double) dist / closestTDist;
                int x = blockX((int) ((fraction * xdist) + ballBx));
                int y = blockY((int) ((fraction * ydist) + ballBy));
                if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE
                    || (BlockType.bit(w.map.block(x, y)) & SPACE_BLOCKS) == 0) {
                    clearPath = false;
                }
            }
            if (tdir == bdir && distNp > closestTDist && clearPath
                && o.vx[ball] * o.vx[ball] + o.vy[ball] * o.vy[ball] > 60) {
                items.detachBall(pl);
                pl.used &= ~Has.CONNECTOR;
                d.lastThrownBall = d.robotCount;
                d.longtermMode &= ~FETCH_TREASURE;
                return false;
            }
            d.longtermMode |= FETCH_TREASURE;
            return checkTarget(pl, d, (int) (t.bx() + 0.5) * BLOCK_SZ, (int) (t.by() + 0.5) * BLOCK_SZ, RM_NAVIGATE);
        }
        int closestBallDist = closestNtDist;
        int closestBall = -1;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if (o.type[h] != Obj.BALL) {
                continue;
            }
            Player holder = w.byId(o.owner[h]);
            boolean enemy = o.owner[h] == Obj.NO_ID ? items.ballOwner(h) != Obj.NO_ID
                : holder != null && holder.team != pl.team;
            if (enemy) {
                int ballDist = (int) Math.hypot(pl.px - o.px[h], pl.py - o.py[h]);
                if (ballDist < closestBallDist) {
                    closestBallDist = ballDist;
                    closestBall = h;
                }
            }
        }
        if (closestBall == -1 && closestNt >= 0 && closestNtDist < (d.robotCount / 10) * BLOCK_SZ) {
            Treasure t = ts.get(closestNt);
            d.longtermMode |= FETCH_TREASURE;
            return checkTarget(pl, d, (int) (t.bx() + 0.5) * BLOCK_SZ, (int) (t.by() + 0.5) * BLOCK_SZ, RM_NAVIGATE);
        } else if (closestBall >= 0 && closestBallDist < (d.robotCount / 10) * BLOCK_SZ
            && closestBallDist > ballConnectorLength) {
            d.longtermMode |= FETCH_TREASURE;
            return checkTarget(pl, d, o.px[closestBall], o.py[closestBall], RM_NAVIGATE);
        }
        return false;
    }

    // ================================================================ map features

    /**
     * C {@code Robot_default_play_check_map}: refuel, attack targets and cannons.
     * <p>
     * BEHAVIOR: finds the nearest fuel station with at least 100 fuel (own team's with teamFuel), the nearest
     * live enemy target of a team with members, and the nearest live non-own-team cannon, all within
     * visibility (per-axis then Euclidean, as C).
     * BEHAVIOR: needing fuel, it heads for the station (setting REFUEL and fs) when that is nearer than the
     * target or outside team play; then attacks the target (TARGET_KILL while trying), then the cannon (aimed
     * 0.1 block in front of it), then the station if not tried yet. Returns 1 when one of them was taken on.
     */
    int checkMap(Player pl, RobotData d) {
        Params p = w.params;
        boolean fuelChecked = false;
        int cannonI = -1;
        int cannonDist = (int) visibilityDistance;
        int fuelI = -1;
        int fuelDist = (int) visibilityDistance;
        int targetI = -1;
        int targetDist = (int) visibilityDistance;
        java.util.List<FuelStation> fuels = w.map.fuels();
        for (int j = 0; j < fuels.size(); j++) {
            FuelStation f = fuels.get(j);
            if (w.fuelLevel[j] < 100 * FUEL_SCALE) {
                continue;
            }
            if (p.teamPlay && p.teamFuel && f.team() != pl.team) {
                continue;
            }
            int dx = wdx((int) (f.x() - pl.px));
            if (Math.abs(dx) >= fuelDist) {
                continue;
            }
            int dy = wdy((int) (f.y() - pl.py));
            if (Math.abs(dy) >= fuelDist) {
                continue;
            }
            int distance = (int) Math.hypot(dx, dy);
            if (distance < fuelDist && w.map.block(f.bx(), f.by()) == BlockType.FUEL) {
                fuelI = j;
                fuelDist = distance;
            }
        }
        java.util.List<Target> targets = w.map.targets();
        for (int j = 0; j < targets.size(); j++) {
            Target t = targets.get(j);
            if (w.targetDeadTime[j] > 0 || pl.team == t.team() || t.team() < 0 || w.teamMembers[t.team()] == 0) {
                continue;
            }
            int dx = wdx(t.bx() * BLOCK_SZ + BLOCK_SZ / 2 - pl.px);
            if (Math.abs(dx) >= targetDist) {
                continue;
            }
            int dy = wdy(t.by() * BLOCK_SZ + BLOCK_SZ / 2 - pl.py);
            if (Math.abs(dy) >= targetDist) {
                continue;
            }
            int distance = (int) Math.hypot(dx, dy);
            if (distance < targetDist) {
                targetI = j;
                targetDist = distance;
            }
        }
        if (fuelI >= 0 && (targetDist > fuelDist || !p.teamPlay) && (d.longtermMode & NEED_FUEL) != 0) {
            fuelChecked = true;
            FuelStation f = fuels.get(fuelI);
            pl.used |= Has.REFUEL;
            pl.fs = fuelI;
            if (checkTarget(pl, d, (int) f.x(), (int) f.y(), RM_REFUEL)) {
                return 1;
            }
        }
        if (targetI >= 0) {
            Target t = targets.get(targetI);
            d.longtermMode |= TARGET_KILL;
            if (checkTarget(pl, d, (int) ((t.bx() + 0.5) * BLOCK_SZ), (int) ((t.by() + 0.5) * BLOCK_SZ),
                RM_CANNON_KILL)) {
                return 1;
            }
            d.longtermMode &= ~TARGET_KILL;
        }
        java.util.List<Cannon> cannons = w.map.cannons();
        for (int j = 0; j < cannons.size(); j++) {
            Cannon c = cannons.get(j);
            if (w.cannonDeadTime[j] > 0) {
                continue;
            }
            if (p.teamPlay && c.team() == pl.team) {
                continue;
            }
            int dx = wdx((int) (c.x() - pl.px));
            if (Math.abs(dx) >= cannonDist) {
                continue;
            }
            int dy = wdy((int) (c.y() - pl.py));
            if (Math.abs(dy) >= cannonDist) {
                continue;
            }
            int distance = (int) Math.hypot(dx, dy);
            if (distance < cannonDist) {
                cannonI = j;
                cannonDist = distance;
            }
        }
        if (cannonI >= 0) {
            Cannon c = cannons.get(cannonI);
            int dx = (int) c.x();
            dx = (int) (dx + BLOCK_SZ * 0.1 * Trig.tcos(c.dir()));
            int dy = (int) c.y();
            dy = (int) (dy + BLOCK_SZ * 0.1 * Trig.tsin(c.dir()));
            if (checkTarget(pl, d, dx, dy, RM_CANNON_KILL)) {
                return 1;
            }
        }
        if (fuelI >= 0 && !fuelChecked && (d.longtermMode & NEED_FUEL) != 0) {
            FuelStation f = fuels.get(fuelI);
            pl.used |= Has.REFUEL;
            pl.fs = fuelI;
            if (checkTarget(pl, d, (int) f.x(), (int) f.y(), RM_REFUEL)) {
                return 1;
            }
        }
        return 0;
    }

    // ================================================================ objects

    /** Results of {@link #checkObjects}: C's item_i/item_dist/item_imp/mine_i/mine_dist out-parameters. */
    int itemI;
    int itemDist;
    int itemImp;
    int mineI;
    int mineDist;

    /**
     * Rebuild the robot's object buckets (call once per frame before any robot plays): C keeps its cells
     * up to date as objects are created and deleted; ours are rebuilt from the live pool.
     */
    void refreshCells() {
        cells.rebuild(w.objects);
    }

    /**
     * C {@code Robot_default_play_check_objects}: shield against danger, note the nearest missile/mine and item.
     * <p>
     * BEHAVIOR: looks at up to 1000 objects in the cells within visibility, nearest cells first; debris and
     * sparks are ignored; a ball seen more than 3*FPS frames after throwing one sets used CONNECTOR; shots are
     * ignored while shielded.
     * BEHAVIOR: non-killing objects: the most important item (ranked, random items handy) within the
     * per-axis item distance becomes the item target.
     * BEHAVIOR: killing objects of team mates (and own ones with selfImmunity) are ignored. Missiles, asteroids,
     * balls, cannon shots, other players' owned shots (not in race mode), others' mines and wreckage (not in
     * race mode) are nearest-danger candidates, now and one frame ahead (relative velocity).
     * BEHAVIOR: an object whose next position is within 21 + SHIP_SZ + its range of ours raises shields and
     * thrust with probability (85 + defense/7 - attack/50)% (one rfrac each); missiles and mines with low fuel
     * or no shield then hyperjump if possible (and stop looking).
     * BEHAVIOR: smart missiles and mines near trigger ECM, heat seekers stop thrust (and detach a tank between
     * fuel levels 1 and 3), asteroids in front at 2-8 blocks make it shoot.
     * Results go to {@link #itemI}, {@link #itemDist}, {@link #itemImp}, {@link #mineI}, {@link #mineDist}.
     */
    void checkObjects(Player pl, RobotData d) {
        Objects o = w.objects;
        Params p = w.params;
        int ks = killingShots;
        cells.query(pl.bx, pl.by, (int) (visibilityDistance / BLOCK_SZ), MAX_OBJS);
        int n = cells.resultCount;
        for (int j = 0; j < n; j++) {
            int h = cells.result[j];
            int type = o.type[h];
            if ((type & (Obj.DEBRIS | Obj.SPARK)) != 0) {
                continue;
            }
            int dx = wdx(o.px[h] - pl.px);
            int dy = wdy(o.py[h] - pl.py);
            if ((type & Obj.BALL) != 0 && !within(d.lastThrownBall, d.robotCount, 3 * p.fps)) {
                pl.used |= Has.CONNECTOR;
            }
            if ((type & (Obj.SHOT | Obj.CANNON_SHOT)) != 0 && (pl.used & Has.SHIELD) != 0) {
                continue;
            }
            if ((type & ks) == 0) {
                if ((type & Obj.ITEM) != 0 && Math.abs(dx) < itemDist && Math.abs(dy) < itemDist) {
                    int imp = items.randomItem(h) ? ROBOT_HANDY_ITEM
                        : rankItemValue(pl, d, Item.of(items.itemType(h)));
                    if (imp > ROBOT_IGNORE_ITEM && imp >= itemImp) {
                        itemImp = imp;
                        itemDist = (int) Math.hypot(dx, dy);
                        itemI = h;
                    }
                }
                continue;
            }
            int owner = o.owner[h];
            if (w.teamImmune(owner, pl.id)) {
                continue;
            }
            if (owner == pl.id && p.selfImmunity) {
                continue;
            }
            boolean timing = w.map.timing();
            if ((type & (Obj.TORPEDO | Obj.SMART_SHOT | Obj.ASTEROID | Obj.HEAT_SHOT | Obj.BALL | Obj.CANNON_SHOT)) != 0
                || ((type & Obj.SHOT) != 0 && !timing && owner != pl.id && owner != Obj.NO_ID)
                || ((type & Obj.MINE) != 0 && owner != pl.id)
                || ((type & Obj.WRECKAGE) != 0 && !timing)) {
                if (Math.abs(dx) < mineDist && Math.abs(dy) < mineDist) {
                    int distance = (int) Math.hypot(dx, dy);
                    if (distance < mineDist) {
                        mineI = h;
                        mineDist = distance;
                    }
                }
                int ax = wdx((int) ((o.px[h] - pl.px) + (o.vx[h] - pl.vx)));
                int ay = wdy((int) ((o.py[h] - pl.py) + (o.vy[h] - pl.vy)));
                if (Math.abs(ax) < mineDist && Math.abs(ay) < mineDist) {
                    int distance = (int) Math.hypot(ax, ay);
                    if (distance < mineDist) {
                        mineI = h;
                        mineDist = distance;
                    }
                }
            }
            int shieldRange = 21 + SHIP_SZ + o.plRange[h];
            int sx = wdx((int) (o.px[h] + o.vx[h] - (pl.px + pl.vx)));
            if (Math.abs(sx) < shieldRange) {
                int sy = wdy((int) (o.py[h] + o.vy[h] - (pl.py + pl.vy)));
                if (Math.abs(sy) < shieldRange && sx * sx + sy * sy <= shieldRange * shieldRange
                    && (int) (w.rfrac() * 100) < (85 + (d.defense / 7) - (d.attack / 50))) {
                    pl.used |= Has.SHIELD;
                    if (!cloakedShield) {
                        pl.used &= ~Has.CLOAKING_DEVICE;
                    }
                    pl.status |= Obj.THRUSTING;
                    if ((type & (Obj.TORPEDO | Obj.SMART_SHOT | Obj.ASTEROID | Obj.HEAT_SHOT | Obj.MINE)) != 0
                        && (pl.fuel < d.fuelL3 || (pl.have & Has.SHIELD) == 0)
                        && items.count(pl, Item.HYPERJUMP) > 0 && pl.fuel > -ED_HYPERJUMP) {
                        items.hyperjump(pl);
                        break;
                    }
                }
            }
            if ((type & Obj.SMART_SHOT) != 0 && mineDist < ECM_DISTANCE / 4) {
                items.fireEcm(pl);
            }
            if ((type & Obj.MINE) != 0 && mineDist < ECM_DISTANCE / 2) {
                items.fireEcm(pl);
            }
            if ((type & Obj.HEAT_SHOT) != 0) {
                pl.status &= ~Obj.THRUSTING;
                if (pl.fuel < d.fuelL3 && pl.fuel > d.fuelL1 && items.numTanks(pl) > 0) {
                    items.detachTank(pl);
                }
            }
            if ((type & Obj.ASTEROID) != 0) {
                int size = items.asteroidSize(h);
                // C: (delta_dir = (dir - Wrap_findDir(..)) < size*(RES/10) || delta_dir > RES - ...) assigns the
                // whole comparison (precedence), with delta_dir still 0 on the right: only the first test counts.
                if (mineDist > (size == 1 ? 2 : 4) * BLOCK_SZ && mineDist < 8 * BLOCK_SZ
                    && pl.dir - XMath.findDir(w.wrapDx(o.px[h] - pl.px), w.wrapDy(o.py[h] - pl.py))
                    < size * (RES / 10)) {
                    pl.used |= Has.SHOT;
                }
            }
        }
    }

    /**
     * C {@code Robot_default_play_check_lasers} (gated: no pulses exist yet).
     * <p>
     * BEHAVIOR: unshielded with shields available, a hostile pulse within PULSE_LENGTH, or within twice that
     * and heading at us within RES/8, raises shields with the evade probability (one rfrac per candidate).
     */
    void checkLasers(Player pl, RobotData d) {
        if ((pl.used & Has.SHIELD) != 0 || (pl.have & Has.SHIELD) == 0) {
            return;
        }
        int n = items.pulseCount();
        for (int j = 0; j < n; j++) {
            int id = items.pulseOwner(j);
            if (id == pl.id && !items.pulseReflected(j)) {
                continue;
            }
            if (w.teamImmune(id, pl.id)) {
                continue;
            }
            if (pl.id == id && w.params.selfImmunity) {
                continue;
            }
            int dx = wdx((int) (pl.px - items.pulseX(j)));
            int dy = wdy((int) (pl.py - items.pulseY(j)));
            int distance2 = dx * dx + dy * dy;
            if ((distance2 < PULSE_LENGTH * PULSE_LENGTH
                || (distance2 < 4 * PULSE_LENGTH * PULSE_LENGTH
                && Math.abs(XMath.findDir(dx, dy) - items.pulseDir(j)) < RES / 8))
                && (int) (w.rfrac() * 100) < (85 + (d.defense / 7) - (d.attack / 50))) {
                pl.used |= Has.SHIELD;
                if (!cloakedShield) {
                    pl.used &= ~Has.CLOAKING_DEVICE;
                }
                break;
            }
        }
    }

    /** C {@code if (!cloakedShield) CLR_BIT(pl->used, HAS_CLOAKING_DEVICE)} after raising shields. */
    private void shieldUp(Player pl) {
        pl.used |= Has.SHIELD;
        if (!cloakedShield) {
            pl.used &= ~Has.CLOAKING_DEVICE;
        }
    }

    // ================================================================ Robot_default_play

    /**
     * C {@code Robot_default_play}: one frame of decisions for a playing robot.
     * <p>
     * BEHAVIOR: the count runs down (refilled to 1000 + rfrac*32 at zero); shots, shields, cloak and laser are
     * released; the gated emergency/cloak/deflector abilities are switched on.
     * BEHAVIOR: at or below fuel level 1 (0 in race mode) it starts self-destructing (count 150), else stops.
     * BEHAVIOR: ECM-blinded (damaged): shields up and nothing else.
     * BEHAVIOR: below 80% fuel, a station within 90 px (unwrapped) with more than REFUEL_RATE fuel is used;
     * NEED_FUEL is set below level 3 (level 1 in race mode) and cleared when not refuelling.
     * BEHAVIOR: then objects (shields), lasers, take-off thrust near the home base, nearest ship and enemy
     * selection (war target first), close-ship shields, hyperjump escape, the player lock, wall evasion,
     * race navigation, harvesting, attacking the locked player, balls, hunting, map features, and finally
     * climbing (half the time or when falling) or idling at normal speed.
     */
    void play(Player pl, RobotData d) {
        Params p = w.params;
        boolean timing = w.map.timing();
        if (d.robotCount <= 0) {
            d.robotCount = 1000 + (int) (w.rfrac() * 32);
        }
        d.robotCount--;

        pl.used &= ~(Has.SHOT | Has.SHIELD | Has.CLOAKING_DEVICE | Has.LASER);
        int have = items.have(pl);
        if ((have & Has.EMERGENCY_SHIELD) != 0 && (pl.used & Has.EMERGENCY_SHIELD) == 0) {
            items.emergencyShield(pl);
        }
        boolean harvestChecked = false;
        boolean navigateChecked = false;

        mineI = -1;
        mineDist = SHIP_SZ + 200;
        itemI = -1;
        itemDist = (int) visibilityDistance;
        itemImp = ROBOT_IGNORE_ITEM;

        if ((have & Has.CLOAKING_DEVICE) != 0 && pl.fuel > d.fuelL2) {
            pl.used |= Has.CLOAKING_DEVICE;
        }
        if ((have & Has.EMERGENCY_THRUST) != 0 && (pl.used & Has.EMERGENCY_THRUST) == 0) {
            items.emergencyThrust(pl);
        }
        if ((have & Has.DEFLECTOR) != 0 && !timing) {
            items.deflector(pl);
        }
        if (pl.fuel <= (timing ? 0 : d.fuelL1)) {
            if ((pl.status & Obj.SELF_DESTRUCT) == 0) {
                pl.status |= Obj.SELF_DESTRUCT;
                pl.count = 150;
            }
        } else {
            pl.status &= ~Obj.SELF_DESTRUCT;
        }
        if (pl.damaged > 0) {
            shieldUp(pl);
            return;
        }
        nearbyFuel(pl);
        if (pl.fuel < (timing ? d.fuelL1 : d.fuelL3)) {
            d.longtermMode |= NEED_FUEL;
        } else if ((pl.used & Has.REFUEL) == 0) {
            d.longtermMode &= ~NEED_FUEL;
        }
        if (p.teamPlay) {
            nearbyTarget(pl, d);
        }
        checkObjects(pl, d);
        checkLasers(pl, d);

        com.fulcrologic.jxpilot.common.map.Base b = w.map.bases().get(pl.homeBase);
        if (Math.abs(pl.px - b.bx() * BLOCK_SZ) + Math.abs(pl.py - b.by() * BLOCK_SZ) < BLOCK_SZ) {
            pl.status |= Obj.THRUSTING;
        }

        Player shipI = null;
        double shipDist = SHIP_SZ * 6;
        Player enemyI = null;
        double enemyDist;
        if (pl.fuel > d.fuelL3) {
            enemyDist = limitedVisibility ? Math.max(pl.fuel * ENERGY_RANGE_FACTOR, visibilityDistance)
                : maxEnemyDistance;
        } else {
            enemyDist = visibilityDistance;
        }
        if ((pl.used & Has.SHIELD) != 0) {
            shipDist = 0;
        }
        if ((d.robotLock & LOCK_PLAYER) != 0) {
            Player ship = w.byId(d.robotLockId);
            if (ship != null && playing(ship) && detectHunt(pl, ship)) {
                d.lockLastSeen = d.robotCount;
                d.lockLastX = ship.px;
                d.lockLastY = ship.py;
                int dx = wdx(ship.px - pl.px);
                int dy = wdy(ship.py - pl.py);
                double distance = Math.hypot(dx, dy);
                if (distance < shipDist) {
                    shipI = ship;
                    shipDist = distance;
                }
                if (distance < enemyDist) {
                    enemyI = ship;
                    enemyDist = distance;
                }
            }
        }
        if (shipI == null || enemyI == null) {
            for (int j = 0; j < w.numPlayers; j++) {
                Player ship = w.playerAt(j);
                if (ship == pl || !playing(ship) || w.teamImmune(pl.id, ship.id)) {
                    continue;
                }
                if (!detectHunt(pl, ship)) {
                    continue;
                }
                int dx = wdx(ship.px - pl.px);
                int dy = wdy(ship.py - pl.py);
                double distance = Math.hypot(dx, dy);
                if (distance < shipDist) {
                    shipI = ship;
                    shipDist = distance;
                }
                if ((d.robotLock & LOCK_PLAYER) == 0) {
                    if ((d.robotCount % 3) == 0 && ((d.robotCount % 100) < d.attack) && distance < enemyDist) {
                        enemyI = ship;
                        enemyDist = distance;
                    }
                }
            }
        }
        if (shipDist < 3 * SHIP_SZ && (pl.have & Has.SHIELD) != 0) {
            shieldUp(pl);
        }
        if (shipDist <= 10 * BLOCK_SZ && pl.fuel <= d.fuelL3 && !timing) {
            if (items.count(pl, Item.HYPERJUMP) > 0 && pl.fuel > -ED_HYPERJUMP) {
                items.hyperjump(pl);
                return;
            }
        }
        if (shipI != null && (d.robotLock & LOCK_PLAYER) != 0 && d.robotLockId == shipI.id) {
            shipI = null;
        }
        if (enemyI != null) {
            choosePlayerLock(pl, d, enemyI, enemyDist);
        }
        if ((pl.lockTagged & LOCK_PLAYER) != 0) {
            releasePlayerLock(pl, d);
        }
        if (checkEvade(pl, d, mineI, shipI)) {
            shieldIfBouncy(pl);
            return;
        }
        if (timing && !navigateChecked) {
            int deltaDir;
            Objects o = w.objects;
            if (itemI >= 0) {
                deltaDir = Trig.mod(pl.dir - wrapFindDir(o.px[itemI] - pl.px, o.py[itemI] - pl.py));
            } else {
                deltaDir = RES;
                itemImp = ROBOT_IGNORE_ITEM;
            }
            if ((itemImp == ROBOT_MUST_HAVE_ITEM && itemDist > 4 * BLOCK_SZ)
                || (itemImp == ROBOT_HANDY_ITEM && itemDist > 2 * BLOCK_SZ)
                || itemImp == ROBOT_IGNORE_ITEM
                || (deltaDir < 3 * RES / 4 && deltaDir > RES / 4)) {
                navigateChecked = true;
                if (navigateToCheckpoint(pl, d)) {
                    return;
                }
            }
        }
        if (itemI >= 0 && 3 * enemyDist > 2 * itemDist && itemDist < 12 * BLOCK_SZ
            && (d.longtermMode & FETCH_TREASURE) == 0
            && ((d.longtermMode & NEED_FUEL) == 0 || items.itemType(itemI) == Item.FUEL.ordinal()
            || items.itemType(itemI) == Item.TANK.ordinal())) {
            if (itemImp != ROBOT_IGNORE_ITEM) {
                harvestChecked = true;
                if (harvest(pl, d)) {
                    return;
                }
            }
        }
        if ((pl.lockTagged & LOCK_PLAYER) != 0) {
            Player ship = w.byId(pl.lockPlId);
            if (ship != null && detectHunt(pl, ship)) {
                int shootTime = (int) (pl.lockDistance / (p.shotSpeed + 1));
                int dx = (int) (long) (ship.px + ship.vx * shootTime);
                int dy = (int) (long) (ship.py + ship.vy * shootTime);
                dx -= (int) (long) (pl.vx * shootTime);
                dy -= (int) (long) (pl.vy * shootTime);
                if (checkTarget(pl, d, dx, dy, RM_ATTACK)
                    && (d.longtermMode & (FETCH_TREASURE | TARGET_KILL | NEED_FUEL)) == 0) {
                    return;
                }
            }
        }
        if (p.teamPlay && w.map.treasures().size() > 0 && pl.team >= 0 && teamTreasures(pl.team) > 0
            && !navigateChecked && (d.longtermMode & (TARGET_KILL | NEED_FUEL)) == 0) {
            navigateChecked = true;
            if (ballHandler(pl, d)) {
                return;
            }
        }
        if (itemI >= 0 && !harvestChecked && itemDist < 12 * BLOCK_SZ) {
            if (itemImp != ROBOT_IGNORE_ITEM && harvest(pl, d)) {
                return;
            }
        }
        if (checkHunt(pl, d)) {
            shieldIfStartsShielded(pl);
            return;
        }
        if (checkMap(pl, d) == 1) {
            return;
        }
        shieldIfStartsShielded(pl);
        climbOrIdle(pl, d);
    }

    /** Number of treasures of a team (C {@code World.teams[team].NumTreasures}). */
    private int teamTreasures(int team) {
        int n = 0;
        java.util.List<Treasure> ts = w.map.treasures();
        for (int i = 0; i < ts.size(); i++) {
            if (ts.get(i).team() == team) {
                n++;
            }
        }
        return n;
    }

    /**
     * The fuel station check of {@code Robot_default_play}.
     * <p>
     * BEHAVIOR: below 80% fuel, the first station (own team's with teamFuel) within 90 px — measured without
     * wrapping, as C — that has more than REFUEL_RATE fuel is used (REFUEL, fs); each station looked at before
     * it clears REFUEL.
     */
    void nearbyFuel(Player pl) {
        Params p = w.params;
        if (pl.fuel >= pl.fuelMax * 0.80) {
            return;
        }
        java.util.List<FuelStation> fuels = w.map.fuels();
        for (int j = 0; j < fuels.size(); j++) {
            FuelStation f = fuels.get(j);
            if (p.teamPlay && p.teamFuel && f.team() != pl.team) {
                continue;
            }
            int dx = (int) (f.x() - pl.px);
            int dy = (int) (f.y() - pl.py);
            if (dx * dx + dy * dy <= 90 * 90 && w.fuelLevel[j] > REFUEL_RATE) {
                pl.fs = j;
                pl.used |= Has.REFUEL;
                break;
            } else {
                pl.used &= ~Has.REFUEL;
            }
        }
    }

    /**
     * The target repair check of {@code Robot_default_play} (team play).
     * <p>
     * BEHAVIOR: the first own damaged, live target within 90 px (unwrapped) becomes the repair target and
     * REPAIR is used. Targets cannot be damaged in S1 ({@link RobotItems#targetDamage}), so this stays idle.
     */
    void nearbyTarget(Player pl, RobotData d) {
        java.util.List<Target> targets = w.map.targets();
        for (int j = 0; j < targets.size(); j++) {
            Target t = targets.get(j);
            if (t.team() == pl.team && items.targetDamage(j) < TARGET_DAMAGE && w.targetDeadTime[j] >= 0) {
                int dx = (t.bx() * BLOCK_SZ + BLOCK_SZ / 2) - pl.px;
                int dy = (t.by() * BLOCK_SZ + BLOCK_SZ / 2) - pl.py;
                if (dx * dx + dy * dy <= 90 * 90) {
                    d.repairTarget = j;
                    pl.used |= Has.REPAIR;
                    break;
                }
            }
        }
    }

    /**
     * The player lock choice of {@code Robot_default_play}.
     * <p>
     * BEHAVIOR: lock on the chosen enemy if not locked, if it is less than half the lock distance away (and in
     * race mode not behind in checks/rounds, which S1 lacks: always true), if within twice the lock distance
     * in team play with a ball, or if it has a higher score than the locked player.
     */
    void choosePlayerLock(Player pl, RobotData d, Player ship, double enemyDist) {
        Player locked = w.byId(pl.lockPlId);
        if ((pl.lockTagged & LOCK_PLAYER) == 0
            || enemyDist < pl.lockDistance / 2
            || (enemyDist < pl.lockDistance * 2 && w.params.teamPlay && (ship.have & Has.BALL) != 0)
            || (locked != null && ship.score > locked.score)) {
            pl.lockPlId = ship.id;
            pl.lockTagged |= LOCK_PLAYER;
            pl.lockDistance = (float) enemyDist;
            items.sensorRange(pl);
        }
    }

    /**
     * The lock release of {@code Robot_default_play}.
     * <p>
     * BEHAVIOR: the lock is dropped (pl_id 1, distance 0, as C) when the locked ship is gone or not playing,
     * the robot is at war with another playing ship, the ship cannot be detected, fuel is at or below level 3
     * (not race mode), in race mode the ship is not ahead (C's always-true heading test), or it is a team mate.
     */
    void releasePlayerLock(Player pl, RobotData d) {
        Player ship = w.byId(pl.lockPlId);
        boolean drop;
        if (ship == null) {
            drop = true;
        } else {
            int deltaDir = Trig.mod(pl.dir - wrapFindDir(ship.px - pl.px, ship.py - pl.py));
            Player war = w.byId(d.robotLockId);
            drop = !playing(ship)
                || ((d.robotLock & LOCK_PLAYER) != 0 && d.robotLockId != pl.lockPlId && war != null && playing(war))
                || !detectHunt(pl, ship)
                || (pl.fuel <= d.fuelL3 && !w.map.timing())
                || (w.map.timing() && (deltaDir < 3 * RES / 4 || deltaDir > RES / 4))
                || w.teamImmune(pl.id, ship.id);
        }
        if (drop) {
            pl.lockTagged &= ~LOCK_PLAYER;
            pl.lockPlId = 1;
            pl.lockDistance = 0;
        }
    }

    /**
     * Shields after an evasion: with playerStartsShielded and no shield key, or when shielded bounces are
     * strictly faster and at least as wide as unshielded ones.
     */
    private void shieldIfBouncy(Player pl) {
        Params p = w.params;
        if (!p.playerShielding && p.playerStartsShielded && (pl.have & Has.SHIELD) != 0) {
            shieldUp(pl);
        } else if (p.maxShieldedWallBounceSpeed > p.maxUnshieldedWallBounceSpeed
            && p.maxShieldedAngle >= p.maxUnshieldedAngle && (pl.have & Has.SHIELD) != 0) {
            shieldUp(pl);
        }
    }

    /** Shields up when shields cannot be switched but players start shielded. */
    private void shieldIfStartsShielded(Player pl) {
        Params p = w.params;
        if (!p.playerShielding && p.playerStartsShielded && (pl.have & Has.SHIELD) != 0) {
            shieldUp(pl);
        }
    }

    /**
     * Race navigation to the next checkpoint (C {@code World.check[pl->check]}); S1 has no checkpoint
     * progress per player, so the robot heads for checkpoint 0.
     */
    private boolean navigateToCheckpoint(Player pl, RobotData d) {
        java.util.List<com.fulcrologic.jxpilot.common.map.Checkpoint> cs = w.map.checkpoints();
        for (int i = 0; i < cs.size(); i++) {
            if (cs.get(i).number() == 0) {
                return checkTarget(pl, d, cs.get(i).bx() * BLOCK_SZ, cs.get(i).by() * BLOCK_SZ, RM_NAVIGATE);
            }
        }
        return false;
    }

    /** Fly to the item, leading it by its velocity (C {@code RM_HARVEST} call). */
    private boolean harvest(Player pl, RobotData d) {
        Objects o = w.objects;
        int dx = o.px[itemI];
        dx += (int) (long) (o.vx[itemI] * (Math.abs(dx - pl.px) / d.normalSpeed));
        int dy = o.py[itemI];
        dy += (int) (long) (o.vy[itemI] * (Math.abs(dy - pl.py) / d.normalSpeed));
        return checkTarget(pl, d, dx, dy, RM_HARVEST);
    }

    /**
     * The end of {@code Robot_default_play}.
     * <p>
     * BEHAVIOR: with the velocity corrected by twice the gravity: when falling faster than the normal speed or
     * in the first half of every 64 counts, CLIMB: turnspeed 32, power 27.5, turn toward straight up unless
     * within RES/16, thrust while rising slower than half the normal speed (and slower than attack speed),
     * stop above the normal speed.
     * BEHAVIOR: otherwise IDLE: no turning, power 27.5, thrust only below half the normal speed.
     */
    void climbOrIdle(Player pl, RobotData d) {
        int x = pl.bx;
        int y = pl.by;
        double xSpeed = pl.vx - 2 * w.gravityX(x, y);
        double ySpeed = pl.vy - 2 * w.gravityY(x, y);
        if (ySpeed < -d.normalSpeed || (d.robotCount % 64) < 32) {
            d.robotMode = RM_ROBOT_CLIMB;
            pl.turnSpeed = (float) (MAX_TS / 2);
            pl.power = (float) (MAX_POWER / 2);
            if (Math.abs(pl.dir - RES / 4) > RES / 16) {
                pl.turnAcc = (pl.dir < RES / 4 || pl.dir >= 3 * RES / 4) ? pl.turnSpeed : -pl.turnSpeed;
            } else {
                pl.turnAcc = 0.0f;
            }
            if (ySpeed < d.normalSpeed / 2 && pl.velocity < d.attackSpeed) {
                pl.status |= Obj.THRUSTING;
            } else if (ySpeed > d.normalSpeed) {
                pl.status &= ~Obj.THRUSTING;
            }
            return;
        }
        d.robotMode = RM_ROBOT_IDLE;
        pl.turnSpeed = (float) (MAX_TS / 2);
        pl.turnAcc = 0;
        pl.power = (float) (MAX_POWER / 2);
        pl.status &= ~Obj.THRUSTING;
        double speed = Math.hypot(xSpeed, ySpeed);
        if (speed < d.normalSpeed / 2) {
            pl.status |= Obj.THRUSTING;
        } else if (speed > d.normalSpeed) {
            pl.status &= ~Obj.THRUSTING;
        }
    }
}
