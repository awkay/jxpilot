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
import com.fulcrologic.jxpilot.common.Item;

import java.util.function.DoubleSupplier;

/**
 * The deterministic own-ship kernel: the ship's part of C {@code Update_objects} ({@code src/server/update.c}) with
 * {@code Player_set_mass} (player.c), plus the movement ({@link ShipMover}), key ({@link ShipKeys}) and shot
 * ({@link ShipShooter}) parts it uses. One implementation for the server, which steps its players with it, and for
 * a client predicting its own ship. Reads the world through {@link ShipWorld}, changes only the
 * {@link ShipBody}, and reports everything else through {@link KernelEvents} at C's points in the frame.
 * <p>
 * Deterministic: integer, float and double arithmetic and the {@code StrictMath} tables of {@link Trig} only, and
 * the one random choice (bounce tie-breaks) from the injected source. Allocation free.
 * One sim tick is one C frame; every rule is C's per-frame rule in C's float ({@code DFLOAT}) arithmetic.
 */
public final class ShipKernel {
    /** C {@code ED_SHIELD} after the {@code (long)} cast in update.c: fuel per frame with shields up. */
    public static final long ED_SHIELD = (long) (-0.20 * 256);
    /** C {@code REFUEL_RATE} (fixed point). */
    public static final int REFUEL_RATE = 5 * 256;
    /** C {@code TURN_FUEL} factor: 0.005 * FUEL_SCALE_FACT. */
    public static final double TURN_FUEL = 0.005 * 256;

    public final ShipWorld world;
    public final ShipMover mover;
    public final ShipShooter shooter;
    public final ShipKeys keys;

    /**
     * @param world    the world the ship flies in
     * @param tieBreak C {@code rfrac()}: uniform draws in [0, 1) for bounce tie-breaks (the server passes its
     *                 world RNG, keeping C's random stream)
     */
    public ShipKernel(ShipWorld world, DoubleSupplier tieBreak) {
        this.world = world;
        this.mover = new ShipMover(world, tieBreak);
        this.shooter = new ShipShooter(world);
        this.keys = new ShipKeys(world, shooter);
    }

    /** Replace the bounce tie-break source (e.g. a per-(tick, player) one for predictable bounces). */
    public void tieBreak(DoubleSupplier source) {
        mover.tieBreak(source);
    }

    /**
     * One own-ship frame as a client predicts it: the keys, autofire, the setting limits and, while playing outside
     * the round delay, {@link #fly}. The server runs the same pieces at their places in its tick.
     * <p>
     * BEHAVIOR: keys and autofire always run; a ship that is not playing (dead, paused, game over) or any ship
     * during the round delay does not fly.
     * BEHAVIOR: returns {@link #fly}'s result, false when it did not fly.
     */
    public boolean step(ShipBody b, long keyMask, KernelEvents ev) {
        keys.apply(b, keyMask, ev);
        shooter.autofire(b, ev);
        limits(b);
        if (!playing(b) || world.roundDelay() > 0) {
            return false;
        }
        return fly(b, ev);
    }

    /**
     * C's per-frame limits of the engine settings (update.c:735-740).
     * <p>
     * BEHAVIOR: power, turnspeed and turnresistance are limited to their C ranges (C {@code LIMIT}).
     */
    public void limits(ShipBody b) {
        b.power = clamp(b.power, Const.MIN_PLAYER_POWER, Const.MAX_PLAYER_POWER);
        b.turnSpeed = clamp(b.turnSpeed, Const.MIN_PLAYER_TURNSPEED, Const.MAX_PLAYER_TURNSPEED);
        b.turnResistance = clamp(b.turnResistance, Const.MIN_PLAYER_TURNRESISTANCE, Const.MAX_PLAYER_TURNRESISTANCE);
    }

    /** C {@code LIMIT} of a float field against double bounds. */
    private static float clamp(float v, double lo, double hi) {
        return v > hi ? (float) hi : (v < lo ? (float) lo : v);
    }

    /** BEHAVIOR: true iff PLAYING is set and neither GAME_OVER nor PAUSE (C's player-loop status test). */
    public static boolean playing(ShipBody b) {
        return (b.status & (Obj.PLAYING | Obj.GAME_OVER | Obj.PAUSE)) == Obj.PLAYING;
    }

    /**
     * The flying part of the C player loop for one frame (update.c:782-1218), for a playing ship outside the round
     * delay.
     * <p>
     * BEHAVIOR: a stun ticks down and drops shields, laser, shot and thrust; the shield timer runs
     * ({@link #shieldTime}); then {@link KernelEvents#devices}; the ship turns ({@link #turn},
     * {@link ShipMover#turnPlayer}); shields drain fuel; {@link KernelEvents#upkeep}; fuel exhaustion drops
     * shields, cloak, deflector and thrust; a nearly full ship stops refuelling; thrust sets the acceleration and
     * uses fuel; the mass is updated.
     * BEHAVIOR: a warping ship goes through {@link KernelEvents#warp}; when it returns true this returns true (C's
     * {@code break} of the player loop) and the rest is skipped.
     * BEHAVIOR: an unpaused ship gets gravity (with GRAVITY) and its acceleration, and moves
     * ({@link ShipMover#movePlayer}).
     * BEHAVIOR: unless cloaked (without cloakedExhaust) or phased: a thrusting ship makes exhaust and, with
     * turnThrust, a paid turn makes 5 + (fuel &gt;&gt; 2) turn sparks; then {@link KernelEvents#sensors}, and
     * abilities not had are no longer used.
     */
    public boolean fly(ShipBody pl, KernelEvents ev) {
        ev.begin(pl, KernelEvents.PHASE_FLY);
        boolean stop = flyFrame(pl, ev);
        ev.end(pl, KernelEvents.PHASE_FLY);
        return stop;
    }

    private boolean flyFrame(ShipBody pl, KernelEvents ev) {
        ShipParams p = world.shipParams();
        if (pl.stunned > 0) {
            pl.stunned--;
            pl.used &= ~(Has.SHIELD | Has.LASER | Has.SHOT);
            pl.status &= ~Obj.THRUSTING;
        }
        shieldTime(pl);
        ev.devices(pl);
        long tf = turn(pl);
        mover.turnPlayer(pl, ev);

        if ((pl.used & Has.SHIELD) != 0) {
            pl.addFuel(ED_SHIELD);
        }
        ev.upkeep(pl);
        if (pl.fuel <= 0) {
            pl.used &= ~(Has.SHIELD | Has.CLOAKING_DEVICE | Has.DEFLECTOR);
            pl.status &= ~Obj.THRUSTING;
        }
        if (pl.fuel > pl.fuelMax - REFUEL_RATE) {
            pl.used &= ~Has.REFUEL;
        }
        thrustAcceleration(pl);
        setMass(pl);
        if ((pl.status & Obj.WARPING) != 0 && ev.warp(pl)) {
            return true;
        }
        if ((pl.status & Obj.PAUSE) == 0) {
            if ((pl.status & Obj.GRAVITY) != 0) {
                pl.vx += pl.accX + world.gravityX(pl.bx, pl.by);
                pl.vy += pl.accY + world.gravityY(pl.bx, pl.by);
            } else {
                pl.vx += pl.accX;
                pl.vy += pl.accY;
            }
            mover.movePlayer(pl, ev);
        }
        if (((pl.used & Has.CLOAKING_DEVICE) == 0 || p.cloakedExhaust) && (pl.used & Has.PHASING_DEVICE) == 0) {
            if ((pl.status & Obj.THRUSTING) != 0) {
                ev.thrustSparks(pl);
            }
            if (tf != 0 && p.turnThrust) {
                ev.turnSparks(pl, (int) (5 + (tf >> 2)));
            }
        }
        ev.sensors(pl);
        pl.used &= pl.have;
        return false;
    }

    /**
     * Shield timer (update.c:793-805).
     * <p>
     * BEHAVIOR: a running timer counts down; reaching 0 drops the shield unless an emergency shield is in use;
     * once the shield is down the timer stops and, without an emergency shield in use, the shield ability goes.
     */
    public void shieldTime(ShipBody pl) {
        if (pl.shieldTime > 0) {
            if (--pl.shieldTime == 0 && (pl.used & Has.EMERGENCY_SHIELD) == 0) {
                pl.used &= ~Has.SHIELD;
            }
            if ((pl.used & Has.SHIELD) == 0) {
                if ((pl.used & Has.EMERGENCY_SHIELD) == 0) {
                    pl.have &= ~Has.SHIELD;
                }
                pl.shieldTime = 0;
            }
        }
    }

    /**
     * Turning (update.c:860-905), in float like C.
     * <p>
     * BEHAVIOR: turnVel += turnAcc, then *= turnResistance unless it is 0 (pointer control).
     * BEHAVIOR: with turnThrust, turning costs C's double truncation (long)(1.28 * |(long)(oldTurnVel - turnVel)|)
     * fuel (C TURN_FUEL on a long); without enough fuel (fuel &lt;= cost) the turn is cancelled (turnAcc = 0,
     * turnVel restored) and costs nothing. Returns the cost (C {@code tf}, for turn sparks).
     * BEHAVIOR: floatDir += turnVel, wrapped into [0, RES); with resistance 0 turnVel then becomes 0.
     */
    public long turn(ShipBody pl) {
        pl.turnVel += pl.turnAcc;
        if (pl.turnResistance != 0) {
            pl.turnVel *= pl.turnResistance;
        }
        long tf = 0;
        if (world.shipParams().turnThrust) {
            tf = (long) (pl.oldTurnVel - pl.turnVel);
            tf = (long) (TURN_FUEL * Math.abs(tf));
            if (pl.fuel <= tf) {
                tf = 0;
                pl.turnAcc = 0;
                pl.turnVel = pl.oldTurnVel;
            } else {
                pl.addFuel(-tf);
                pl.oldTurnVel = pl.turnVel;
            }
        }
        pl.floatDir += pl.turnVel;
        while (pl.floatDir < 0) {
            pl.floatDir += Const.RES;
        }
        while (pl.floatDir >= Const.RES) {
            pl.floatDir -= Const.RES;
        }
        if (pl.turnResistance == 0) {
            pl.turnVel = 0;
        }
        return tf;
    }

    /**
     * Thrust (update.c:1018-1037), C float arithmetic.
     * <p>
     * BEHAVIOR: acc = power * (tcos, tsin)(dir) / mass, with afterburners power *= 1 + n*(3.2/16); fuel use per
     * frame (long)(-f * 256) with f = (float)(power*0.0008), ((f*(16+2n))/16 with afterburners).
     * BEHAVIOR: without thrust the acceleration is zero.
     */
    public void thrustAcceleration(ShipBody pl) {
        if ((pl.status & Obj.THRUSTING) == 0) {
            pl.accX = pl.accY = 0;
            return;
        }
        float power = pl.power;
        float f = (float) (pl.power * 0.0008);
        int a = pl.afterburners();
        float inert = pl.mass;
        if (a != 0) {
            power = (float) (power * (1.0 + a * ((4.2 - 1.0) / (15 + 1.0))));
            f = (float) ((f * ((15 + 1) + a * (3 - 1))) / (15 + 1.0));
        }
        pl.accX = power * Trig.tcos(pl.dir) / inert;
        pl.accY = power * Trig.tsin(pl.dir) / inert;
        pl.addFuel((long) (-f * 256));
    }

    /**
     * C {@code Player_set_mass}.
     * <p>
     * BEHAVIOR: mass = (float)(emptyMass + fuel*0.005/256 + sum), sum in float of armor*(shipMass/14) and
     * minItemMass per other item (fuel and tanks do not count).
     */
    public void setMass(ShipBody pl) {
        ShipParams p = world.shipParams();
        float items = 0;
        for (int i = 0; i < Item.COUNT; i++) {
            if (i == Item.FUEL.ordinal() || i == Item.TANK.ordinal()) {
                continue;
            }
            if (i == Item.ARMOR.ordinal()) {
                items += pl.items[i] * (p.shipMass / 14);
            } else {
                items += pl.items[i] * p.minItemMass;
            }
        }
        pl.mass = (float) (pl.emptyMass + pl.fuel * 0.005 / 256 + items);
    }
}
