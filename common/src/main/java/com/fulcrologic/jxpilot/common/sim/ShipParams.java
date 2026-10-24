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
package com.fulcrologic.jxpilot.common.sim;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;

/**
 * The game parameters the ship kernel reads: the C globals of {@code cmdline.c} that own-ship movement, wall
 * bounces, turning, thrust, mass and shot recoil depend on, after the limits of {@code Move_init} (walls.c).
 * The server's {@code Params} extends this class and adds everything else; a client builds the same values
 * from the same option text with {@link #of}, so both sides compute one set of numbers in one place.
 * <p>
 * Plain public fields (an operator's {@code /set} assigns them live). Options that C keeps as {@code DFLOAT}
 * are {@code float} here so the physics computes in C's precision.
 */
public class ShipParams {
    /** C {@code World.hypotenuse}: (int) diagonal of the world in pixels (the wall bounce speed limit). */
    public double hypotenuse;

    public float shipMass;
    public float minItemMass;
    public float shotMass;
    public float shotSpeed;
    public int fireRepeatRate;

    public boolean edgeWrap;
    public boolean edgeBounce;
    public float maxShieldedWallBounceSpeed;
    public float maxUnshieldedWallBounceSpeed;
    /** In RES units ({@code mp.max_shielded_angle}). */
    public int maxShieldedAngle;
    public int maxUnshieldedAngle;
    /** Option values in degrees, converted into {@link #maxShieldedAngle}/{@link #maxUnshieldedAngle} by resolve. */
    public float maxShieldedAngleDeg;
    public float maxUnshieldedAngleDeg;
    public float playerWallBrakeFactor;
    public float wallBounceFuelDrainMult;

    public float friction;
    public float blockFriction;
    public int coriolis;

    public boolean teamPlay;
    public boolean teamImmunity;
    public boolean targetTeamCollision;
    public boolean turnThrust;
    public boolean cloakedExhaust;
    public int wormTime;

    /**
     * The kernel's parameters for a map (what a client needs to run the kernel).
     * <p>
     * BEHAVIOR: the values the server's {@code Params.from} gives for the same options and map.
     */
    public static ShipParams of(Options o, WorldMap map) {
        ShipParams p = new ShipParams();
        p.loadShip(o, map);
        p.resolveShip();
        return p;
    }

    /** Read the kernel's options (the server's {@code Params.from} calls this first). */
    protected void loadShip(Options o, WorldMap map) {
        hypotenuse = (int) StrictMath.hypot(map.pixelWidth(), map.pixelHeight());
        shipMass = f(o, Opt.SHIP_MASS);
        shotMass = f(o, Opt.SHOT_MASS);
        shotSpeed = f(o, Opt.SHOT_SPEED);
        fireRepeatRate = o.getInt(Opt.FIRE_REPEAT_RATE);
        edgeWrap = map.edgeWrap();
        edgeBounce = o.getBool(Opt.EDGE_BOUNCE);
        maxShieldedWallBounceSpeed = f(o, Opt.MAX_SHIELDED_WALL_BOUNCE_SPEED);
        maxUnshieldedWallBounceSpeed = f(o, Opt.MAX_UNSHIELDED_WALL_BOUNCE_SPEED);
        maxShieldedAngleDeg = f(o, Opt.MAX_SHIELDED_PLAYER_WALL_BOUNCE_ANGLE);
        maxUnshieldedAngleDeg = f(o, Opt.MAX_UNSHIELDED_PLAYER_WALL_BOUNCE_ANGLE);
        playerWallBrakeFactor = f(o, Opt.PLAYER_WALL_BOUNCE_BRAKE_FACTOR);
        wallBounceFuelDrainMult = f(o, Opt.WALL_BOUNCE_FUEL_DRAIN_MULT);
        friction = f(o, Opt.FRICTION);
        blockFriction = f(o, Opt.BLOCK_FRICTION);
        coriolis = o.getInt(Opt.CORIOLIS);
        teamPlay = map.teamPlay();
        teamImmunity = o.getBool(Opt.TEAM_IMMUNITY);
        targetTeamCollision = o.getBool(Opt.TARGET_TEAM_COLLISION);
        turnThrust = o.getBool(Opt.TURN_THRUST);
        cloakedExhaust = o.getBool(Opt.CLOAKED_EXHAUST);
        wormTime = o.getFrames(Opt.WORM_TIME);
        minItemMass = f(o, Opt.MIN_ITEM_MASS);
    }

    /**
     * The kernel's part of C {@code Move_init}'s limits (the server's {@code Params.resolve} calls this first).
     * <p>
     * BEHAVIOR: wall bounce speeds are limited to [0, hypotenuse], angles to [0, 180] then converted to RES
     * units (truncated), the player brake factor to [0, 1], wallBounceFuelDrainMult to [0, 1000].
     */
    protected void resolveShip() {
        maxShieldedWallBounceSpeed = (float) clamp(maxShieldedWallBounceSpeed, 0, hypotenuse);
        maxUnshieldedWallBounceSpeed = (float) clamp(maxUnshieldedWallBounceSpeed, 0, hypotenuse);
        maxShieldedAngleDeg = (float) clamp(maxShieldedAngleDeg, 0, 180);
        maxUnshieldedAngleDeg = (float) clamp(maxUnshieldedAngleDeg, 0, 180);
        maxShieldedAngle = (int) (maxShieldedAngleDeg * Const.RES / 360);
        maxUnshieldedAngle = (int) (maxUnshieldedAngleDeg * Const.RES / 360);
        playerWallBrakeFactor = (float) clamp(playerWallBrakeFactor, 0, 1);
        wallBounceFuelDrainMult = (float) clamp(wallBounceFuelDrainMult, 0, 1000);
    }

    /** A C {@code DFLOAT} option: the parsed double rounded to float, as C stores it. */
    protected static float f(Options o, Opt opt) {
        return (float) o.getDouble(opt);
    }

    /** C {@code LIMIT}. */
    protected static double clamp(double v, double lo, double hi) {
        return v > hi ? hi : (v < lo ? lo : v);
    }
}
