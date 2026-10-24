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

import java.nio.ByteBuffer;

/**
 * The state the ship kernel reads and writes for one ship: position, velocity, turning, engine settings, mass,
 * fuel, status and ability bits, the item counts mass and thrust depend on, the shot and key state, and the
 * ship's rotated hull ({@link ShipGeom}, not state: it comes with the player). Units and types follow C
 * ({@code player} in {@code src/server/player.h}): positions in clicks (pixels and blocks derived as C
 * {@code objposition}), velocities in pixels per frame, headings in RES units, fuel in C fixed point
 * ({@code FUEL_SCALE_FACT} = 256 per fuel unit). Fields C keeps as {@code DFLOAT} are {@code float} here, so the
 * physics rounds exactly like C.
 * <p>
 * The server's {@code Player} extends this class, so the kernel works on the server's players in place; a
 * client runs it on plain instances. {@link #encode}/{@link #decode} carry the prediction state over the wire,
 * so the wire cannot lag behind what the kernel reads.
 */
public class ShipBody {
    /** C {@code MAX_TANKS}. */
    public static final int MAX_TANKS = 8;

    // ---- position (clicks authoritative, pixels/blocks derived as C objposition)
    public int cx;
    public int cy;
    public int px;
    public int py;
    public int bx;
    public int by;
    public int prevX;
    public int prevY;

    public float vx;
    public float vy;
    public float accX;
    public float accY;
    /** C {@code velocity}: |v| after the last move. */
    public float velocity;
    public float mass;
    public float emptyMass;

    public int status;
    public int have;
    public int used;

    public int dir;
    public float floatDir;
    public float turnVel;
    public float oldTurnVel;
    public float turnAcc;
    public float turnSpeed;
    public float turnResistance;
    public float power;
    public float powerS;
    public float turnSpeedS;
    public float turnResistanceS;

    /** Fuel in C fixed point units (256 per displayed unit, C {@code long fuel.sum}). */
    public double fuel;
    public double fuelMax;
    /** C {@code fuel.tank[]}: main tank (0) and extra tanks (fixed point); {@link #fuel} is their sum. */
    public final double[] tankFuel = new double[1 + MAX_TANKS];
    /** C {@code fuel.num_tanks}. */
    public int numTanks;
    /** C {@code fuel.current}: the tank fuel is added to and taken from. */
    public int currentTank;
    public int shieldTime;
    public int stunned;

    public int shots;
    public int shotMax;
    /** Frame clock value of the last shot (C {@code shot_time}). */
    public long shotTime;
    /** C {@code mods} ({@link Mods}): the spread decides the wide-angle and rear shot angles. */
    public int mods;
    public int team = Const.TEAM_NOT_SET;
    public final int[] items = new int[Item.COUNT];
    public int wormHoleDest = -1;
    public long lastWallTouch;
    /** Frame of the last key activity (C {@code frame_last_busy}): idle pause and maxPauseTime. */
    public long frameLastBusy;

    /** Control keys as last handled ({@link com.fulcrologic.jxpilot.common.net.ControlKey} mask, C {@code prev_keyv}). */
    public long prevKeys;
    /** The key mask the client sent last (whether or not it was handled). */
    public long lastReceivedKeys;
    /**
     * Set by Go_home (C clears {@code last_keyv}/{@code prev_keyv}): keys still held are ignored until the
     * client's key state changes.
     */
    public boolean keysCleared;

    /** The ship's hull, guns and engine rotated to every heading. */
    public ShipGeom ship = ShipGeom.defaultShip();

    /**
     * C {@code Player_position_set_clicks}.
     * <p>
     * BEHAVIOR: pixels are the clicks shifted down (floor); blocks are pixels / BLOCK_SZ truncated toward zero (C pos.bx).
     */
    public void setClicks(int x, int y) {
        cx = x;
        cy = y;
        px = x >> Click.SHIFT;
        py = y >> Click.SHIFT;
        bx = px / Const.BLOCK_SZ;
        by = py / Const.BLOCK_SZ;
    }

    /**
     * C {@code Player_position_init_pixels}: set the position and make it the previous position too.
     * <p>
     * BEHAVIOR: the pixel arguments are C {@code DFLOAT}s: rounded to float, then truncated to clicks.
     */
    public void initPixels(double x, double y) {
        setClicks(Click.fromPixels((float) x), Click.fromPixels((float) y));
        remember();
    }

    /** C {@code Player_position_remember}. */
    public void remember() {
        prevX = px;
        prevY = py;
    }

    /** C {@code BIT(status, PLAYING|PAUSE|GAME_OVER|KILLED) == PLAYING}: alive and flying. */
    public boolean isAlive() {
        return (status & (Obj.PLAYING | Obj.PAUSE | Obj.GAME_OVER | Obj.KILLED)) == Obj.PLAYING;
    }

    /** Both shield bits used (C {@code BIT(used, HAS_SHIELD|HAS_EMERGENCY_SHIELD) == both}). */
    public boolean fullyShielded() {
        return (used & Has.BOTH_SHIELDS) == Has.BOTH_SHIELDS;
    }

    /** Afterburners in effect for thrust: {@code MAX_AFTERBURNER} with emergency thrust, else the item count. */
    public int afterburners() {
        return (used & Has.EMERGENCY_THRUST) != 0 ? 15 : items[Item.AFTERBURNER.ordinal()];
    }

    /**
     * C {@code Add_fuel}: the change is clamped so the sum stays in {@code [0, fuelMax]}, then added to the sum
     * and to the current tank.
     * <p>
     * BEHAVIOR: the change is first truncated toward zero, as C converts the argument to its {@code long} parameter.
     */
    public void addFuel(double f) {
        f = (long) f;
        if (fuel + f > fuelMax) {
            f = fuelMax - fuel;
        } else if (fuel + f < 0) {
            f = -fuel;
        }
        fuel += f;
        tankFuel[currentTank] += f;
    }

    /**
     * C {@code Player_hit_armor}: one armor is used up.
     * <p>
     * BEHAVIOR: the last one (count at or below zero afterwards) removes HAS_ARMOR.
     */
    public void hitArmor() {
        if (--items[Item.ARMOR.ordinal()] <= 0) {
            have &= ~Has.ARMOR;
        }
    }

    // ================================================================ copy, compare, wire

    /**
     * Make this body a copy of {@code o}: every field, the arrays element by element, and the same hull.
     * <p>
     * BEHAVIOR: afterwards every field equals {@code o}'s; later changes to either do not affect the other.
     */
    public void copyFrom(ShipBody o) {
        cx = o.cx;
        cy = o.cy;
        px = o.px;
        py = o.py;
        bx = o.bx;
        by = o.by;
        prevX = o.prevX;
        prevY = o.prevY;
        vx = o.vx;
        vy = o.vy;
        accX = o.accX;
        accY = o.accY;
        velocity = o.velocity;
        mass = o.mass;
        emptyMass = o.emptyMass;
        status = o.status;
        have = o.have;
        used = o.used;
        dir = o.dir;
        floatDir = o.floatDir;
        turnVel = o.turnVel;
        oldTurnVel = o.oldTurnVel;
        turnAcc = o.turnAcc;
        turnSpeed = o.turnSpeed;
        turnResistance = o.turnResistance;
        power = o.power;
        powerS = o.powerS;
        turnSpeedS = o.turnSpeedS;
        turnResistanceS = o.turnResistanceS;
        fuel = o.fuel;
        fuelMax = o.fuelMax;
        System.arraycopy(o.tankFuel, 0, tankFuel, 0, tankFuel.length);
        numTanks = o.numTanks;
        currentTank = o.currentTank;
        shieldTime = o.shieldTime;
        stunned = o.stunned;
        shots = o.shots;
        shotMax = o.shotMax;
        shotTime = o.shotTime;
        mods = o.mods;
        team = o.team;
        System.arraycopy(o.items, 0, items, 0, items.length);
        wormHoleDest = o.wormHoleDest;
        lastWallTouch = o.lastWallTouch;
        frameLastBusy = o.frameLastBusy;
        prevKeys = o.prevKeys;
        lastReceivedKeys = o.lastReceivedKeys;
        keysCleared = o.keysCleared;
        ship = o.ship;
    }

    /** Bytes {@link #encode} writes. */
    public static final int WIRE_SIZE = 41 + 44 + 60 + Item.COUNT;

    /**
     * Write the prediction state at {@code off} (absolute puts: the buffer's position does not move), in the
     * buffer's byte order. Everything the kernel reads before it writes is here, bit for bit: floats as their
     * IEEE bits, fuel as a double. Not on the wire: pixels and blocks (derived from the clicks), and what the kernel
     * only writes or never reads: the previous position, the acceleration, the busy frame, the last wall touch,
     * the per-tank fuel and the tank count and index; and the hull. Item counts are bytes (C limits every item to at most 99); the heading is a byte (0..RES-1).
     * <p>
     * BEHAVIOR: writes exactly {@link #WIRE_SIZE} bytes and returns that count.
     */
    public int encode(ByteBuffer b, int off) {
        int o = off;
        b.putInt(o, cx);
        b.putInt(o + 4, cy);
        b.putFloat(o + 8, vx);
        b.putFloat(o + 12, vy);
        b.putFloat(o + 16, velocity);
        b.putFloat(o + 20, mass);
        b.putFloat(o + 24, emptyMass);
        b.putInt(o + 28, status);
        b.putInt(o + 32, have);
        b.putInt(o + 36, used);
        b.put(o + 40, (byte) dir);
        o += 41;
        b.putFloat(o, floatDir);
        b.putFloat(o + 4, turnVel);
        b.putFloat(o + 8, oldTurnVel);
        b.putFloat(o + 12, turnAcc);
        b.putFloat(o + 16, turnSpeed);
        b.putFloat(o + 20, turnResistance);
        b.putFloat(o + 24, power);
        b.putFloat(o + 28, powerS);
        b.putFloat(o + 32, turnSpeedS);
        b.putFloat(o + 36, turnResistanceS);
        b.putInt(o + 40, shieldTime);
        o += 44;
        b.putDouble(o, fuel);
        b.putDouble(o + 8, fuelMax);
        b.putInt(o + 16, stunned);
        b.putInt(o + 20, shots);
        b.putInt(o + 24, shotMax);
        b.putInt(o + 28, mods);
        b.putLong(o + 32, shotTime);
        b.put(o + 40, (byte) team);
        b.putShort(o + 41, (short) wormHoleDest);
        b.putLong(o + 43, prevKeys);
        b.putLong(o + 51, lastReceivedKeys);
        b.put(o + 59, (byte) (keysCleared ? 1 : 0));
        o += 60;
        for (int i = 0; i < Item.COUNT; i++) {
            b.put(o + i, (byte) items[i]);
        }
        return WIRE_SIZE;
    }

    /**
     * Read the prediction state written by {@link #encode} at {@code off} (absolute gets).
     * <p>
     * BEHAVIOR: every encoded field takes the encoded value; pixels and blocks are derived from the clicks
     * ({@link #setClicks}); the fields that are not on the wire and the hull are left as they were.
     * BEHAVIOR: the team and item bytes are read signed and unsigned as written (team -1, items 0..255);
     * returns {@link #WIRE_SIZE}.
     */
    public int decode(ByteBuffer b, int off) {
        int o = off;
        setClicks(b.getInt(o), b.getInt(o + 4));
        vx = b.getFloat(o + 8);
        vy = b.getFloat(o + 12);
        velocity = b.getFloat(o + 16);
        mass = b.getFloat(o + 20);
        emptyMass = b.getFloat(o + 24);
        status = b.getInt(o + 28);
        have = b.getInt(o + 32);
        used = b.getInt(o + 36);
        dir = b.get(o + 40) & 0xFF;
        o += 41;
        floatDir = b.getFloat(o);
        turnVel = b.getFloat(o + 4);
        oldTurnVel = b.getFloat(o + 8);
        turnAcc = b.getFloat(o + 12);
        turnSpeed = b.getFloat(o + 16);
        turnResistance = b.getFloat(o + 20);
        power = b.getFloat(o + 24);
        powerS = b.getFloat(o + 28);
        turnSpeedS = b.getFloat(o + 32);
        turnResistanceS = b.getFloat(o + 36);
        shieldTime = b.getInt(o + 40);
        o += 44;
        fuel = b.getDouble(o);
        fuelMax = b.getDouble(o + 8);
        stunned = b.getInt(o + 16);
        shots = b.getInt(o + 20);
        shotMax = b.getInt(o + 24);
        mods = b.getInt(o + 28);
        shotTime = b.getLong(o + 32);
        team = b.get(o + 40);
        wormHoleDest = b.getShort(o + 41);
        prevKeys = b.getLong(o + 43);
        lastReceivedKeys = b.getLong(o + 51);
        keysCleared = b.get(o + 59) != 0;
        o += 60;
        for (int i = 0; i < Item.COUNT; i++) {
            items[i] = b.get(o + i) & 0xFF;
        }
        return WIRE_SIZE;
    }

    /**
     * Whether {@code o} has the same prediction state (the {@link #encode wire} fields) bit for bit: floats and
     * doubles compared by their raw bits, so -0.0 differs from 0.0 and NaNs compare by payload.
     * <p>
     * BEHAVIOR: true iff every wire field is bit-identical; fields that are not on the wire are ignored.
     */
    public boolean sameState(ShipBody o) {
        if (cx != o.cx || cy != o.cy || bits(vx) != bits(o.vx) || bits(vy) != bits(o.vy)
            || bits(velocity) != bits(o.velocity) || bits(mass) != bits(o.mass)
            || bits(emptyMass) != bits(o.emptyMass) || status != o.status || have != o.have || used != o.used
            || dir != o.dir || bits(floatDir) != bits(o.floatDir) || bits(turnVel) != bits(o.turnVel)
            || bits(oldTurnVel) != bits(o.oldTurnVel) || bits(turnAcc) != bits(o.turnAcc)
            || bits(turnSpeed) != bits(o.turnSpeed) || bits(turnResistance) != bits(o.turnResistance)
            || bits(power) != bits(o.power) || bits(powerS) != bits(o.powerS)
            || bits(turnSpeedS) != bits(o.turnSpeedS) || bits(turnResistanceS) != bits(o.turnResistanceS)
            || shieldTime != o.shieldTime || bits(fuel) != bits(o.fuel) || bits(fuelMax) != bits(o.fuelMax)
            || stunned != o.stunned || shots != o.shots || shotMax != o.shotMax || mods != o.mods
            || shotTime != o.shotTime || team != o.team || wormHoleDest != o.wormHoleDest || prevKeys != o.prevKeys
            || lastReceivedKeys != o.lastReceivedKeys || keysCleared != o.keysCleared) {
            return false;
        }
        for (int i = 0; i < Item.COUNT; i++) {
            if (items[i] != o.items[i]) {
                return false;
            }
        }
        return true;
    }

    private static int bits(float f) {
        return Float.floatToRawIntBits(f);
    }

    private static long bits(double d) {
        return Double.doubleToRawLongBits(d);
    }
}
