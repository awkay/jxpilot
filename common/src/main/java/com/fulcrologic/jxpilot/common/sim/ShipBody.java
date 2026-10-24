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

    // ---- the PREDICT codec (docs/PROTOCOL.md "PREDICT"): core every snapshot, rare block on change, keys on mismatch

    /** Bytes of the core block ({@link #encodeCore}). */
    public static final int CORE_SIZE = 71;
    /** Bytes of the rare block ({@link #encodeRare}): settings and counts that change seldom. */
    public static final int RARE_SIZE = 62;
    /** Bytes of the keys block ({@link #encodeKeys}): sent only when the key masks differ from the applied input. */
    public static final int KEYS_SIZE = 16;
    /** Bytes of the canonical encoding ({@link #encode}): core, rare and keys blocks. */
    public static final int WIRE_SIZE = CORE_SIZE + RARE_SIZE + KEYS_SIZE;
    /** Core flag: {@link #prevKeys} equals the applied input's keys. */
    public static final int FLAG_PREV_KEYS = 1;
    /** Core flag: {@link #lastReceivedKeys} equals the applied input's keys. */
    public static final int FLAG_RECEIVED_KEYS = 1 << 1;
    /** Core flag: {@link #keysCleared}. */
    public static final int FLAG_KEYS_CLEARED = 1 << 2;
    /** Largest shot age the core carries; older shots read as this old (the fire check only needs "long ago"). */
    public static final int MAX_SHOT_AGE = 0xFFFF;
    /** Offset of the flags byte in the core block. */
    public static final int CORE_FLAGS = 69;

    /** Two canonical encodings per thread for {@link #sameState} (not ShipBody state). */
    private static final ThreadLocal<ByteBuffer[]> SCRATCH = ThreadLocal.withInitial(
        () -> new ByteBuffer[] {ByteBuffer.allocate(WIRE_SIZE), ByteBuffer.allocate(WIRE_SIZE)});

    /**
     * Write the core block at {@code off} (absolute puts, the buffer's byte order): what changes every tick, bit for
     * bit where the kernel needs it.
     * <pre>
     *   i24 cx, i24 cy, f32 vx, vy, velocity, mass, floatDir, turnVel, oldTurnVel, turnAcc, u8 dir,
     *   i32 status, have, used, f64 fuel, u16 shotAge, u16 shieldTime, stunned, shots, mods, u8 flags,
     *   u8 rareVersion                                                                          (71 bytes)
     * </pre>
     * {@code shotAge} is {@code frames - shotTime} clamped to 0..{@link #MAX_SHOT_AGE}; the u16 counts are clamped
     * to 0..65535 (C keeps them far smaller); the clicks are signed 24-bit (±131071 px, beyond any map). The flags
     * say whether the key masks equal {@code appliedKeys} (the input the server applied this tick, which the
     * client has in its own history); when one does not, {@link #encodeKeys} must follow.
     * <p>
     * BEHAVIOR: writes {@link #CORE_SIZE} bytes and returns that count.
     */
    public int encodeCore(ByteBuffer b, int off, long frames, long appliedKeys, int rareVersion) {
        putI24(b, off, cx);
        putI24(b, off + 3, cy);
        b.putFloat(off + 6, vx);
        b.putFloat(off + 10, vy);
        b.putFloat(off + 14, velocity);
        b.putFloat(off + 18, mass);
        b.putFloat(off + 22, floatDir);
        b.putFloat(off + 26, turnVel);
        b.putFloat(off + 30, oldTurnVel);
        b.putFloat(off + 34, turnAcc);
        b.put(off + 38, (byte) dir);
        b.putInt(off + 39, status);
        b.putInt(off + 43, have);
        b.putInt(off + 47, used);
        b.putDouble(off + 51, fuel);
        b.putShort(off + 59, (short) Math.clamp(frames - shotTime, 0, MAX_SHOT_AGE));
        b.putShort(off + 61, (short) u16(shieldTime));
        b.putShort(off + 63, (short) u16(stunned));
        b.putShort(off + 65, (short) u16(shots));
        b.putShort(off + 67, (short) u16(mods));
        b.put(off + CORE_FLAGS, (byte) ((prevKeys == appliedKeys ? FLAG_PREV_KEYS : 0)
            | (lastReceivedKeys == appliedKeys ? FLAG_RECEIVED_KEYS : 0) | (keysCleared ? FLAG_KEYS_CLEARED : 0)));
        b.put(off + 70, (byte) rareVersion);
        return CORE_SIZE;
    }

    /** BEHAVIOR: true when a key mask differs from {@code appliedKeys}, so the keys block must be sent. */
    public boolean keysDiffer(long appliedKeys) {
        return prevKeys != appliedKeys || lastReceivedKeys != appliedKeys;
    }

    /**
     * Write the rare block at {@code off}:
     * <pre>
     *   f32 emptyMass, f64 fuelMax, f32 power, turnSpeed, turnResistance, powerS, turnSpeedS, turnResistanceS,
     *   u16 shotMax, i8 team, i16 wormHoleDest, u8 items[Item.COUNT]                              (62 bytes)
     * </pre>
     * BEHAVIOR: writes {@link #RARE_SIZE} bytes and returns that count; items are clamped to 0..255.
     */
    public int encodeRare(ByteBuffer b, int off) {
        b.putFloat(off, emptyMass);
        b.putDouble(off + 4, fuelMax);
        b.putFloat(off + 12, power);
        b.putFloat(off + 16, turnSpeed);
        b.putFloat(off + 20, turnResistance);
        b.putFloat(off + 24, powerS);
        b.putFloat(off + 28, turnSpeedS);
        b.putFloat(off + 32, turnResistanceS);
        b.putShort(off + 36, (short) u16(shotMax));
        b.put(off + 38, (byte) team);
        b.putShort(off + 39, (short) wormHoleDest);
        for (int i = 0; i < Item.COUNT; i++) {
            b.put(off + 41 + i, (byte) Math.clamp(items[i], 0, 255));
        }
        return RARE_SIZE;
    }

    /** Write the keys block ({@code u64 prevKeys, u64 lastReceivedKeys}); returns {@link #KEYS_SIZE}. */
    public int encodeKeys(ByteBuffer b, int off) {
        b.putLong(off, prevKeys);
        b.putLong(off + 8, lastReceivedKeys);
        return KEYS_SIZE;
    }

    /**
     * Read a core block.
     * <p>
     * BEHAVIOR: every core field takes the written value; pixels and blocks follow the clicks ({@link #setClicks});
     * shotTime is {@code frames - shotAge}; a key mask whose flag is set becomes {@code appliedKeys} (the client's
     * input for the tick), one whose flag is clear is left for {@link #decodeKeys}; returns the rare version byte.
     */
    public int decodeCore(ByteBuffer b, int off, long frames, long appliedKeys) {
        setClicks(getI24(b, off), getI24(b, off + 3));
        vx = b.getFloat(off + 6);
        vy = b.getFloat(off + 10);
        velocity = b.getFloat(off + 14);
        mass = b.getFloat(off + 18);
        floatDir = b.getFloat(off + 22);
        turnVel = b.getFloat(off + 26);
        oldTurnVel = b.getFloat(off + 30);
        turnAcc = b.getFloat(off + 34);
        dir = b.get(off + 38) & 0xFF;
        status = b.getInt(off + 39);
        have = b.getInt(off + 43);
        used = b.getInt(off + 47);
        fuel = b.getDouble(off + 51);
        shotTime = frames - (b.getShort(off + 59) & 0xFFFF);
        shieldTime = b.getShort(off + 61) & 0xFFFF;
        stunned = b.getShort(off + 63) & 0xFFFF;
        shots = b.getShort(off + 65) & 0xFFFF;
        mods = b.getShort(off + 67) & 0xFFFF;
        int flags = b.get(off + CORE_FLAGS);
        if ((flags & FLAG_PREV_KEYS) != 0) {
            prevKeys = appliedKeys;
        }
        if ((flags & FLAG_RECEIVED_KEYS) != 0) {
            lastReceivedKeys = appliedKeys;
        }
        keysCleared = (flags & FLAG_KEYS_CLEARED) != 0;
        return b.get(off + 70) & 0xFF;
    }

    /** BEHAVIOR: reads every rare field as written (team signed, items unsigned); returns {@link #RARE_SIZE}. */
    public int decodeRare(ByteBuffer b, int off) {
        emptyMass = b.getFloat(off);
        fuelMax = b.getDouble(off + 4);
        power = b.getFloat(off + 12);
        turnSpeed = b.getFloat(off + 16);
        turnResistance = b.getFloat(off + 20);
        powerS = b.getFloat(off + 24);
        turnSpeedS = b.getFloat(off + 28);
        turnResistanceS = b.getFloat(off + 32);
        shotMax = b.getShort(off + 36) & 0xFFFF;
        team = b.get(off + 38);
        wormHoleDest = b.getShort(off + 39);
        for (int i = 0; i < Item.COUNT; i++) {
            items[i] = b.get(off + 41 + i) & 0xFF;
        }
        return RARE_SIZE;
    }

    /** BEHAVIOR: reads both key masks; returns {@link #KEYS_SIZE}. */
    public int decodeKeys(ByteBuffer b, int off) {
        prevKeys = b.getLong(off);
        lastReceivedKeys = b.getLong(off + 8);
        return KEYS_SIZE;
    }

    /**
     * The canonical encoding: core (keys compared with 0, rare version 0), rare and keys blocks, {@link #WIRE_SIZE}
     * bytes at {@code off}; the shot age is taken at {@code frames}. Everything the kernel reads before it writes is
     * here, bit for bit: floats as their IEEE bits, fuel as a double. Not here: pixels and blocks (derived from the
     * clicks), and what the kernel only writes or never reads: the previous position, the acceleration, the busy
     * frame, the last wall touch, the tanks (the kernel only adds to the current tank's fuel, never reads it), and
     * the hull.
     * <p>
     * BEHAVIOR: writes exactly {@link #WIRE_SIZE} bytes and returns that count.
     */
    public int encode(ByteBuffer b, int off, long frames) {
        encodeCore(b, off, frames, 0, 0);
        encodeRare(b, off + CORE_SIZE);
        encodeKeys(b, off + CORE_SIZE + RARE_SIZE);
        return WIRE_SIZE;
    }

    /**
     * Read the canonical encoding written by {@link #encode} at the same {@code frames}.
     * <p>
     * BEHAVIOR: every encoded field takes the encoded value; the fields that are not encoded and the hull are left
     * as they were; returns {@link #WIRE_SIZE}.
     */
    public int decode(ByteBuffer b, int off, long frames) {
        decodeCore(b, off, frames, 0);
        decodeRare(b, off + CORE_SIZE);
        decodeKeys(b, off + CORE_SIZE + RARE_SIZE);
        return WIRE_SIZE;
    }

    /**
     * Whether {@code o} has the same prediction state: their canonical encodings at {@code frames} are byte-equal
     * ({@link ByteBuffer#mismatch}), so floats compare by their raw bits (-0.0 differs from 0.0) and shot times by
     * their saturating age. Allocation free after a thread's first call (two per-thread scratch buffers).
     * <p>
     * BEHAVIOR: true iff the encodings match; fields that are not encoded are ignored.
     */
    public boolean sameState(ShipBody o, long frames) {
        ByteBuffer[] s = SCRATCH.get();
        encode(s[0], 0, frames);
        o.encode(s[1], 0, frames);
        return s[0].mismatch(s[1]) < 0;
    }

    private static int u16(int v) {
        return Math.clamp(v, 0, 0xFFFF);
    }

    private static void putI24(ByteBuffer b, int off, int v) {
        b.put(off, (byte) (v >> 16));
        b.putShort(off + 1, (short) v);
    }

    /** Big byte first in either byte order: the sign comes from the top byte. */
    private static int getI24(ByteBuffer b, int off) {
        return b.get(off) << 16 | (b.getShort(off + 1) & 0xFFFF);
    }

}
