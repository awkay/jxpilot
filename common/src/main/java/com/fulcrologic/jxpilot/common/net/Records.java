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
package com.fulcrologic.jxpilot.common.net;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.XMath;

import com.fulcrologic.jxpilot.common.sim.ShipBody;

import java.nio.ByteBuffer;

/**
 * Snapshot record layouts. Each nested class has:
 * <ul>
 *   <li>{@code SIZE}: bytes per record;</li>
 *   <li>static {@code put(SnapshotWriter, ...)}: append one record to the writer's buffer, quantizing as
 *       documented in {@link Wire}; view-culled positions are encoded relative to the snapshot's view
 *       centre ({@code rel} = i16 half pixels);</li>
 *   <li>instance {@code wrap(SnapshotReader, i)} plus getters: a reusable flyweight reader using absolute
 *       gets (no allocation). Positions are returned in world pixels (view centre + offset).</li>
 * </ul>
 * Velocities are px per original XPilot frame, angles radians. Offsets below are relative to the record start.
 */
public final class Records {
    private Records() {}

    /** Common flyweight state. */
    abstract static class Fly {
        ByteBuffer b;
        int o;
        /** Record size of the bound section (fields appended later read as 0 from shorter records). */
        int sz;
        float ox;
        float oy;
        double ww;
        double wh;
        boolean wrap;

        final void bind(SnapshotReader r, int i) {
            b = r.buffer();
            o = r.recordOffset(i);
            sz = r.recordSize();
            ox = r.viewX();
            oy = r.viewY();
            ww = r.worldWidth();
            wh = r.worldHeight();
            wrap = r.wraps();
        }

        final float relX(int off) {
            float v = ox + Wire.i16ToRel(b.getShort(o + off));
            return wrap ? (float) XMath.wrap(v, ww) : v;
        }

        final float relY(int off) {
            float v = oy + Wire.i16ToRel(b.getShort(o + off));
            return wrap ? (float) XMath.wrap(v, wh) : v;
        }

        final int u8(int off) {
            return b.get(o + off) & 0xFF;
        }

        final int u16(int off) {
            return b.getShort(o + off) & 0xFFFF;
        }
    }

    /** Bits for ship status flags ({@link Self#flags()}, {@link Ship#flags()}). */
    public static final class ShipFlags {
        private ShipFlags() {}

        public static final int SHIELD = 1;
        public static final int THRUSTING = 1 << 1;
        public static final int CLOAKED = 1 << 2;
        public static final int PHASING = 1 << 3;
        /** Ship is dead/exploded (waiting to respawn); not drawn. */
        public static final int DEAD = 1 << 4;
        /** Ship is appearing at its base (respawn flash). */
        public static final int APPEARING = 1 << 5;
        public static final int PAUSED = 1 << 6;
        public static final int SELF_DESTRUCT = 1 << 7;
        public static final int EMERGENCY_SHIELD = 1 << 8;
        public static final int DEFLECTOR = 1 << 9;
        public static final int REFUELING = 1 << 10;
        public static final int REPAIRING = 1 << 11;
        public static final int AUTOPILOT = 1 << 12;
        public static final int EMERGENCY_THRUST = 1 << 13;
        /** Ship carries a ball. */
        public static final int HAS_BALL = 1 << 14;
        /** Team mate of the receiving player (or self). */
        public static final int FRIENDLY = 1 << 15;
    }

    /**
     * Shot kinds: bits 4-6 of the SHOT section variant; bit 7 is {@link #FLAG_HARMLESS}, bits 0-3 the
     * {@link ObjColor}.
     */
    public static final class ShotKind {
        private ShotKind() {}

        public static final int SHOT = 0;
        public static final int CANNON_SHOT = 1;
        /** Cluster / nuke fragments that are dangerous. */
        public static final int CLUSTER = 2;
        public static final int PULSE = 3;
        /**
         * A dangerous shot with nuclear modifiers (nuclear cluster fragments): C frame.c:830 draws it RED as a
         * teamshot every other two frames ({@code frame_loops & 2}), else like any shot. The client blinks it.
         */
        public static final int NUCLEAR = 4;

        /**
         * Variant bit: the shot cannot hurt the receiver (C frame.c draws it in the friendly colour):
         * team immune, owner paused, an unowned shot of the receiver's team in team play, or the
         * receiver's own shot with selfImmunity.
         */
        public static final int FLAG_HARMLESS = 0x80;

        /** Section variant for a kind and {@link ObjColor}. */
        public static int variant(int kind, int color) {
            return ((kind & 0x7) << 4) | (color & 0xF);
        }

        /** BEHAVIOR: section variant for a kind, {@link ObjColor} and the harmless flag. */
        public static int variant(int kind, int color, boolean harmless) {
            return variant(kind, color) | (harmless ? FLAG_HARMLESS : 0);
        }

        /** Kind from a variant. */
        public static int kindOf(int variant) {
            return (variant >> 4) & 0x7;
        }

        /** BEHAVIOR: whether a variant has {@link #FLAG_HARMLESS}. */
        public static boolean harmless(int variant) {
            return (variant & FLAG_HARMLESS) != 0;
        }

        /** Colour from a variant. */
        public static int colorOf(int variant) {
            return variant & 0xF;
        }
    }

    /** Colour hints chosen by the server relative to the receiver. */
    public static final class ObjColor {
        private ObjColor() {}

        public static final int NEUTRAL = 0;
        /** Owned by the receiver. */
        public static final int OWN = 1;
        /** Owned by a team mate. */
        public static final int TEAM = 2;
        /** Owned by an enemy. */
        public static final int ENEMY = 3;
        /** C RED (thrust sparks). */
        public static final int RED = 4;
        /** C BLUE (afterburner sparks). */
        public static final int BLUE = 5;
        /** C WHITE (debris). */
        public static final int WHITE = 6;
    }

    /** Burst kinds ({@link Burst#kind()}). */
    public static final class BurstKind {
        private BurstKind() {}

        /** Thrust/turn exhaust (C OBJ_SPARK). */
        public static final int SPARK = 0;
        /** Explosion debris (C OBJ_DEBRIS). */
        public static final int DEBRIS = 1;
        /** Wall-hit sparks. */
        public static final int WALL_SPARK = 2;
    }

    // ================================================================ M1

    /**
     * Receiving player's own ship, full precision, absolute world coordinates.
     * <pre>
     *  0 u16 id        2 u16 flags      4 f32 x        8 f32 y
     * 12 f32 vx       16 f32 vy        20 f32 heading 24 f32 fuel
     * 28 f32 fuelMax  32 u16 lockId    34 u8 numTanks 35 u8 currentTank
     * 36 u16 respawnFrames (original frames until respawn while dead, else 0)
     * 38 u8 power      39 u8 turnSpeed      40 u8 turnResistance * 255 (C PKT_SELF rounding)
     * 41 u16 lockDist (px; 0 = lock not visible: C frame.c Frame_status)    43 u8 lockDir (RES units)
     * 44 u8 check (next race checkpoint, C pl-&gt;check)   45 u16 mods (weapon modifiers, MOD_* bits)
     * </pre>
     * Readers of a shorter (older) record get 0 for the appended fields.
     */
    public static final class Self extends Fly {
        public static final int SIZE = 47;

        /** {@link #mods()}: nuclear (C NUCLEAR). */
        public static final int MOD_NUCLEAR = 1;
        /** Full nuclear (C FULLNUCLEAR). */
        public static final int MOD_FULL_NUCLEAR = 1 << 1;
        /** Cluster warhead. */
        public static final int MOD_CLUSTER = 1 << 2;
        /** Implosion warhead. */
        public static final int MOD_IMPLOSION = 1 << 3;
        /** Shift of the 2-bit explosion velocity 0..3. */
        public static final int MOD_VELOCITY_SHIFT = 4;
        /** Shift of the 2-bit mini count 0..3 (shown as X2..X4). */
        public static final int MOD_MINI_SHIFT = 6;
        /** Shift of the 2-bit spread 0..3. */
        public static final int MOD_SPREAD_SHIFT = 8;
        /** Shift of the 2-bit power 0..3. */
        public static final int MOD_POWER_SHIFT = 10;
        /** Laser stun (C STUN). */
        public static final int MOD_LASER_STUN = 1 << 12;
        /** Laser blind (C BLIND). */
        public static final int MOD_LASER_BLIND = 1 << 13;

        /** BEHAVIOR: as the full put with no power/turn settings, lock distance, checkpoint or modifiers (all 0). */
        @SuppressWarnings("checkstyle:ParameterNumber")
        public static void put(SnapshotWriter w, int id, int flags, float x, float y, float vx, float vy,
                               float heading, float fuel, float fuelMax, int lockId, int numTanks,
                               int currentTank, int respawnFrames) {
            put(w, id, flags, x, y, vx, vy, heading, fuel, fuelMax, lockId, numTanks, currentTank, respawnFrames,
                0, 0, 0, 0, 0, 0, 0);
        }

        /**
         * BEHAVIOR: respawnFrames and lockDist are clamped to 0..65535; power and turnSpeed are rounded and
         * clamped to 0..255; turnResistance is sent as round(r * 255) clamped to 0..255; lockDir is taken
         * mod 128 (RES); check is clamped to 0..255.
         */
        @SuppressWarnings("checkstyle:ParameterNumber")
        public static void put(SnapshotWriter w, int id, int flags, float x, float y, float vx, float vy,
                               float heading, float fuel, float fuelMax, int lockId, int numTanks,
                               int currentTank, int respawnFrames, double power, double turnSpeed,
                               double turnResistance, int lockDist, int lockDir, int check, int mods) {
            ByteBuffer out = w.buffer();
            out.putShort((short) id);
            out.putShort((short) flags);
            out.putFloat(x);
            out.putFloat(y);
            out.putFloat(vx);
            out.putFloat(vy);
            out.putFloat(heading);
            out.putFloat(fuel);
            out.putFloat(fuelMax);
            out.putShort((short) lockId);
            out.put((byte) numTanks);
            out.put((byte) currentTank);
            out.putShort((short) Math.min(0xFFFF, Math.max(0, respawnFrames)));
            out.put((byte) u8Of(power));
            out.put((byte) u8Of(turnSpeed));
            out.put((byte) u8Of(turnResistance * 255.0));
            out.putShort((short) Math.min(0xFFFF, Math.max(0, lockDist)));
            out.put((byte) (lockDir & 0x7F));
            out.put((byte) Math.min(255, Math.max(0, check)));
            out.putShort((short) mods);
        }

        private static int u8Of(double v) {
            return (int) Math.min(255, Math.max(0, Math.round(v)));
        }

        public Self wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int id() { return u16(0); }

        public int flags() { return u16(2); }

        public float x() { return b.getFloat(o + 4); }

        public float y() { return b.getFloat(o + 8); }

        public float vx() { return b.getFloat(o + 12); }

        public float vy() { return b.getFloat(o + 16); }

        public float heading() { return b.getFloat(o + 20); }

        public float fuel() { return b.getFloat(o + 24); }

        public float fuelMax() { return b.getFloat(o + 28); }

        /** Locked player id or {@link Protocol#NO_ID}. */
        public int lockId() { return u16(32); }

        public int numTanks() { return u8(34); }

        public int currentTank() { return u8(35); }

        public int respawnFrames() { return u16(36); }

        /** Engine power (C PKT_SELF power), 0 from an older record. */
        public int power() { return sz > 38 ? u8(38) : 0; }

        /** Turn speed (C PKT_SELF turnspeed). */
        public int turnSpeed() { return sz > 39 ? u8(39) : 0; }

        /** Turn resistance * 255. */
        public int turnResistance255() { return sz > 40 ? u8(40) : 0; }

        /** Distance to the locked player in px, 0 when the lock is not visible. */
        public int lockDist() { return sz > 42 ? u16(41) : 0; }

        /** Direction to the locked player, RES units 0..127. */
        public int lockDir() { return sz > 43 ? u8(43) : 0; }

        /** Next race checkpoint index. */
        public int check() { return sz > 44 ? u8(44) : 0; }

        /** Weapon modifiers, MOD_* bits. */
        public int mods() { return sz > 46 ? u16(45) : 0; }
    }

    /**
     * Another ship. {@code 0 u16 id, 2 u16 flags, 4 rel x, 6 rel y, 8 u16 heading, 10 i16 vx, 12 i16 vy}
     * (velocities in 1/16 px/frame). A PAUSED ship is still: its vx carries C's pause count
     * ({@code Send_paused(.., pl->count)}, frames left; the client blinks the P while it is above 0) and its vy is 0.
     * Team, name and shape come from {@link Msg.PlayerJoin}.
     */
    public static final class Ship extends Fly {
        public static final int SIZE = 14;

        public static void put(SnapshotWriter w, int id, int flags, double x, double y, double heading,
                               double vx, double vy) {
            ByteBuffer out = w.buffer();
            out.putShort((short) id);
            out.putShort((short) flags);
            out.putShort((short) w.relX(x));
            out.putShort((short) w.relY(y));
            out.putShort((short) Wire.angleToU16(heading));
            out.putShort((short) Wire.velToI16(vx));
            out.putShort((short) Wire.velToI16(vy));
        }

        public Ship wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int id() { return u16(0); }

        public int flags() { return u16(2); }

        public float x() { return relX(4); }

        public float y() { return relY(6); }

        public float heading() { return Wire.u16ToAngle(b.getShort(o + 8)); }

        public float vx() { return Wire.i16ToVel(b.getShort(o + 10)); }

        public float vy() { return Wire.i16ToVel(b.getShort(o + 12)); }
    }

    /**
     * A bullet: {@code 0 rel x, 2 rel y}, then the velocity in 1/16 px/frame as two signed 12-bit values packed into
     * 3 bytes: {@code 4 [vx bits 11..4], 5 [vx bits 3..0 | vy bits 11..8], 6 [vy bits 7..0]} (range -128..127.9375
     * px/frame, for client extrapolation). Kind and colour are the section variant ({@link ShotKind#variant}). 7 bytes.
     */
    public static final class Shot extends Fly {
        public static final int SIZE = 7;
        /** Largest velocity in 1/16 px/frame; the smallest is {@code -MAX_VEL16 - 1}. */
        public static final int MAX_VEL16 = 0x7FF;

        /**
         * BEHAVIOR: the velocity is rounded to the nearest 1/16 px/frame (halves up: {@code floor(v * 16 + 0.5)} of
         * the server's float) and clamped to -2048..2047 sixteenths.
         */
        public static void put(SnapshotWriter w, double x, double y, double vx, double vy) {
            ByteBuffer out = w.buffer();
            out.putShort((short) w.relX(x));
            out.putShort((short) w.relY(y));
            int qx = vel16(vx) & 0xFFF;
            int qy = vel16(vy) & 0xFFF;
            out.put((byte) (qx >> 4));
            out.put((byte) ((qx & 0xF) << 4 | qy >> 8));
            out.put((byte) qy);
        }

        /** BEHAVIOR: {@code floor(v * 16 + 0.5)} clamped to -2048..2047. */
        public static int vel16(double v) {
            double q = Math.floor(v * 16 + 0.5);
            return (int) Math.max(-MAX_VEL16 - 1, Math.min(MAX_VEL16, q));
        }

        public Shot wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public float x() { return relX(0); }

        public float y() { return relY(2); }

        /** The x velocity in 1/16 px/frame (-2048..2047). */
        public int vx16() {
            int q = (b.get(o + 4) & 0xFF) << 4 | (b.get(o + 5) & 0xFF) >> 4;
            return q << 20 >> 20;
        }

        /** The y velocity in 1/16 px/frame (-2048..2047). */
        public int vy16() {
            int q = (b.get(o + 5) & 0x0F) << 8 | b.get(o + 6) & 0xFF;
            return q << 20 >> 20;
        }

        /** The x velocity in px/frame, exact to 1/16. */
        public float vxf() { return vx16() / 16f; }

        /** The y velocity in px/frame, exact to 1/16. */
        public float vyf() { return vy16() / 16f; }

        /** The x velocity rounded to whole px/frame (halves up), clamped to -127..127 (the version 1 record's). */
        public int vx() { return whole(vx16()); }

        /** The y velocity rounded to whole px/frame (halves up), clamped to -127..127. */
        public int vy() { return whole(vy16()); }

        private static int whole(int v16) {
            return Math.max(-127, Math.min(127, Math.floorDiv(v16 + 8, 16)));
        }
    }

    /**
     * Spark/debris spawn event, expanded on the client with {@link DebrisBurst#generate}.
     * <pre>
     *  0 rel x       2 rel y        4 i16 vx (1/16)  6 i16 vy (1/16)  8 u32 seed
     * 12 u8 kind    13 u8 color    14 u16 count     16 u8 minDir (RES) 17 u8 dirSpan (RES)
     * 18 u16 minSpeed (1/16)       20 u16 maxSpeed (1/16)
     * 22 u16 minLife (frames)      24 u16 maxLife (frames)            26 u8 age (sim ticks since spawn)
     * </pre>
     * The server must generate its own authoritative particles from {@link #quantize}d parameters so
     * both sides match exactly.
     */
    public static final class Burst extends Fly {
        public static final int SIZE = 27;

        /**
         * Round burst parameters to their wire precision (in place).
         * <p>
         * BEHAVIOR: position to half pixels, velocity and speeds to 1/16, count to 0..65535,
         * minDir to 0..127, dirSpan/lives clamped to their field ranges.
         */
        public static void quantize(DebrisBurst d) {
            d.x = Wire.posToU16(d.x) * 0.5f;
            d.y = Wire.posToU16(d.y) * 0.5f;
            d.vx = Wire.i16ToVel(Wire.velToI16(d.vx));
            d.vy = Wire.i16ToVel(Wire.velToI16(d.vy));
            d.count = Math.max(0, Math.min(0xFFFF, d.count));
            d.minDir = d.minDir & 127;
            d.dirSpan = Math.max(0, Math.min(255, d.dirSpan));
            d.minSpeed = Wire.toU16Sixteenths(d.minSpeed) / 16f;
            d.maxSpeed = Wire.toU16Sixteenths(d.maxSpeed) / 16f;
            d.minLife = Math.max(0, Math.min(0xFFFF, d.minLife));
            d.maxLife = Math.max(0, Math.min(0xFFFF, d.maxLife));
        }

        /** Append a burst (call {@link #quantize} first so the server's own particles match). */
        public static void put(SnapshotWriter w, DebrisBurst d, int kind, int color, int ageTicks) {
            ByteBuffer out = w.buffer();
            out.putShort((short) w.relX(d.x));
            out.putShort((short) w.relY(d.y));
            out.putShort((short) Wire.velToI16(d.vx));
            out.putShort((short) Wire.velToI16(d.vy));
            out.putInt(d.seed);
            out.put((byte) kind);
            out.put((byte) color);
            out.putShort((short) d.count);
            out.put((byte) (d.minDir & 127));
            out.put((byte) d.dirSpan);
            out.putShort((short) Wire.toU16Sixteenths(d.minSpeed));
            out.putShort((short) Wire.toU16Sixteenths(d.maxSpeed));
            out.putShort((short) d.minLife);
            out.putShort((short) d.maxLife);
            out.put((byte) Math.min(255, Math.max(0, ageTicks)));
        }

        public Burst wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        /** Fill {@code d} with this record's parameters (positions in world pixels). */
        public void read(DebrisBurst d) {
            d.x = relX(0);
            d.y = relY(2);
            d.vx = Wire.i16ToVel(b.getShort(o + 4));
            d.vy = Wire.i16ToVel(b.getShort(o + 6));
            d.seed = b.getInt(o + 8);
            d.count = u16(14);
            d.minDir = u8(16);
            d.dirSpan = u8(17);
            d.minSpeed = u16(18) / 16f;
            d.maxSpeed = u16(20) / 16f;
            d.minLife = u16(22);
            d.maxLife = u16(24);
        }

        /** {@link BurstKind}. */
        public int kind() { return u8(12); }

        /** {@link ObjColor}. */
        public int color() { return u8(13); }

        /** Sim ticks between the spawn and this snapshot's tick. */
        public int age() { return u8(26); }
    }

    /** Cannon state. {@code 0 u16 index (WorldMap.cannons()), 2 u8 flags}. */
    public static final class CannonState extends Fly {
        public static final int SIZE = 3;
        public static final int FLAG_DEAD = 1;
        public static final int FLAG_PHASING = 2;
        public static final int FLAG_SHIELD = 4;

        public static void put(SnapshotWriter w, int index, int flags) {
            w.buffer().putShort((short) index).put((byte) flags);
        }

        public CannonState wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int index() { return u16(0); }

        public int flags() { return u8(2); }
    }

    /** Fuel station level. {@code 0 u16 index (WorldMap.fuels()), 2 u16 fraction of MAX_STATION_FUEL}. */
    public static final class FuelState extends Fly {
        public static final int SIZE = 4;

        public static void put(SnapshotWriter w, int index, double fraction) {
            w.buffer().putShort((short) index).putShort((short) Wire.fracToU16(fraction));
        }

        public FuelState wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int index() { return u16(0); }

        /** 0..1 of {@code Const.MAX_STATION_FUEL}. */
        public float fraction() { return Wire.u16ToFrac(b.getShort(o + 2)); }
    }

    /** Target state. {@code 0 u16 index (WorldMap.targets()), 2 u8 flags, 3 u16 damage fraction left}. */
    public static final class TargetState extends Fly {
        public static final int SIZE = 5;
        public static final int FLAG_DEAD = 1;

        public static void put(SnapshotWriter w, int index, int flags, double damageLeft) {
            w.buffer().putShort((short) index).put((byte) flags).putShort((short) Wire.fracToU16(damageLeft));
        }

        public TargetState wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int index() { return u16(0); }

        public int flags() { return u8(2); }

        /** Remaining strength 0..1 of {@code Const.TARGET_DAMAGE}. */
        public float damageLeft() { return Wire.u16ToFrac(b.getShort(o + 3)); }
    }

    /**
     * Radar blip for the whole world (not view culled): {@code 0 u8 x, 1 u8 y, 2 u8 flags} where x/y are
     * 1/256 of the world size ({@link Wire#toRadar8}). Sent every {@link Protocol#DEFAULT_RADAR_DIVISOR}th snapshot.
     */
    public static final class Radar extends Fly {
        public static final int SIZE = 3;
        public static final int FLAG_FRIENDLY = 1;
        public static final int FLAG_SELF = 2;
        public static final int FLAG_MISSILE = 4;
        public static final int FLAG_MINE = 8;
        public static final int FLAG_BALL = 16;
        public static final int FLAG_NUKE = 32;
        public static final int FLAG_ASTEROID = 64;
        public static final int FLAG_LOCKED = 128;

        public static void put(SnapshotWriter w, double x, double y, int flags) {
            w.buffer().put((byte) Wire.toRadar8(x, w.worldWidth())).put((byte) Wire.toRadar8(y, w.worldHeight()))
                .put((byte) flags);
        }

        public Radar wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        /** Cell centre x in world pixels. */
        public float x() { return Wire.fromRadar8(u8(0), ww); }

        /** Cell centre y in world pixels. */
        public float y() { return Wire.fromRadar8(u8(1), wh); }

        /** Raw cell x (0..255). */
        public int cellX() { return u8(0); }

        /** Raw cell y (0..255). */
        public int cellY() { return u8(1); }

        public int flags() { return u8(2); }
    }

    // ================================================================ M2

    /** Floating item. {@code 0 rel x, 2 rel y, 4 u8 itemType ({@link Item} ordinal)}. */
    public static final class ItemObj extends Fly {
        public static final int SIZE = 5;

        public static void put(SnapshotWriter w, double x, double y, int itemType) {
            w.buffer().putShort((short) w.relX(x)).putShort((short) w.relY(y)).put((byte) itemType);
        }

        public ItemObj wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public float x() { return relX(0); }

        public float y() { return relY(2); }

        public int itemType() { return u8(4); }
    }

    /** Mine. {@code 0 rel x, 2 rel y, 4 u8 flags, 5 u16 ownerId}. */
    public static final class Mine extends Fly {
        public static final int SIZE = 7;
        public static final int FLAG_ARMED = 1;
        public static final int FLAG_FRIENDLY = 2;
        public static final int FLAG_NUCLEAR = 4;
        public static final int FLAG_OWN = 8;
        /**
         * {@link #ownerId} of an identified mine nobody owns (C {@code EXPIRED_MINE_ID}, const.h:121, frame.c:873):
         * C's client labels it "Expired"; NO_ID means the owner is not identified.
         */
        public static final int EXPIRED_MINE_ID = 4096;

        public static void put(SnapshotWriter w, double x, double y, int flags, int ownerId) {
            w.buffer().putShort((short) w.relX(x)).putShort((short) w.relY(y)).put((byte) flags)
                .putShort((short) ownerId);
        }

        public Mine wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public float x() { return relX(0); }

        public float y() { return relY(2); }

        public int flags() { return u8(4); }

        public int ownerId() { return u16(5); }
    }

    /** Missile. {@code 0 u16 id, 2 rel x, 4 rel y, 6 u16 heading, 8 u8 kind, 9 u8 length}. */
    public static final class Missile extends Fly {
        public static final int SIZE = 10;
        public static final int KIND_SMART = 0;
        public static final int KIND_HEAT = 1;
        public static final int KIND_TORPEDO = 2;
        /** Bit or-ed into kind. */
        public static final int KIND_NUCLEAR = 0x80;

        public static void put(SnapshotWriter w, int id, double x, double y, double heading, int kind, int length) {
            w.buffer().putShort((short) id).putShort((short) w.relX(x)).putShort((short) w.relY(y))
                .putShort((short) Wire.angleToU16(heading)).put((byte) kind).put((byte) length);
        }

        public Missile wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int id() { return u16(0); }

        public float x() { return relX(2); }

        public float y() { return relY(4); }

        public float heading() { return Wire.u16ToAngle(b.getShort(o + 6)); }

        public int kind() { return u8(8); }

        public int length() { return u8(9); }
    }

    /** Ball. {@code 0 u16 id, 2 rel x, 4 rel y, 6 u8 team, 7 u16 connectedPlayer}. */
    public static final class Ball extends Fly {
        public static final int SIZE = 9;

        public static void put(SnapshotWriter w, int id, double x, double y, int team, int connectedPlayer) {
            w.buffer().putShort((short) id).putShort((short) w.relX(x)).putShort((short) w.relY(y))
                .put((byte) team).putShort((short) connectedPlayer);
        }

        public Ball wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int id() { return u16(0); }

        public float x() { return relX(2); }

        public float y() { return relY(4); }

        public int team() { return u8(6); }

        /** Player holding the ball or {@link Protocol#NO_ID}. */
        public int connectedPlayer() { return u16(7); }
    }

    /** Laser pulse. {@code 0 rel x, 2 rel y, 4 u16 heading, 6 u16 length (px), 8 u8 color}. */
    public static final class Laser extends Fly {
        public static final int SIZE = 9;

        public static void put(SnapshotWriter w, double x, double y, double heading, int length, int color) {
            w.buffer().putShort((short) w.relX(x)).putShort((short) w.relY(y))
                .putShort((short) Wire.angleToU16(heading)).putShort((short) length).put((byte) color);
        }

        public Laser wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public float x() { return relX(0); }

        public float y() { return relY(2); }

        public float heading() { return Wire.u16ToAngle(b.getShort(o + 4)); }

        public int length() { return u16(6); }

        public int color() { return u8(8); }
    }

    /** Asteroid. {@code 0 rel x, 2 rel y, 4 u8 size (low 4 bits; the shape type in the high 4), 5 u16 rotation}. */
    public static final class Asteroid extends Fly {
        public static final int SIZE = 7;

        public static void put(SnapshotWriter w, double x, double y, int size, double rotation) {
            w.buffer().putShort((short) w.relX(x)).putShort((short) w.relY(y)).put((byte) size)
                .putShort((short) Wire.angleToU16(rotation));
        }

        public Asteroid wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public float x() { return relX(0); }

        public float y() { return relY(2); }

        /** C {@code type_size}: the outline type in the high nibble, the size (1-4) in the low one. */
        public int size() { return u8(4); }

        public float rotation() { return Wire.u16ToAngle(b.getShort(o + 5)); }
    }

    /** Wreckage. {@code 0 rel x, 2 rel y, 4 u8 shape (bit 7 = deadly, as C), 5 u8 size, 6 u16 rotation}. */
    public static final class Wreckage extends Fly {
        public static final int SIZE = 8;

        public static void put(SnapshotWriter w, double x, double y, int shape, int size, double rotation) {
            w.buffer().putShort((short) w.relX(x)).putShort((short) w.relY(y)).put((byte) shape).put((byte) size)
                .putShort((short) Wire.angleToU16(rotation));
        }

        public Wreckage wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public float x() { return relX(0); }

        public float y() { return relY(2); }

        public int shape() { return u8(4); }

        public int size() { return u8(5); }

        public float rotation() { return Wire.u16ToAngle(b.getShort(o + 6)); }
    }

    /** ECM flash. {@code 0 rel x, 2 rel y, 4 u16 radius (px)}. */
    public static final class Ecm extends Fly {
        public static final int SIZE = 6;

        public static void put(SnapshotWriter w, double x, double y, int radius) {
            w.buffer().putShort((short) w.relX(x)).putShort((short) w.relY(y)).putShort((short) radius);
        }

        public Ecm wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public float x() { return relX(0); }

        public float y() { return relY(2); }

        public int radius() { return u16(4); }
    }

    /** Line between two points. {@code 0 rel x0, 2 rel y0, 4 rel x1, 6 rel y1, 8 u8 kind (KIND_*)}. */
    public static final class Connector extends Fly {
        public static final int SIZE = 9;
        public static final int KIND_BALL = 0;
        public static final int KIND_TRACTOR = 1;
        public static final int KIND_PRESSOR = 2;
        /** Refuel beam: x0,y0 the fuel station, x1,y1 the ship (C PKT_REFUEL, Paint_refuel). */
        public static final int KIND_REFUEL = 3;
        /** Repair beam: x0,y0 the ship, x1,y1 the target being repaired. */
        public static final int KIND_REPAIR = 4;

        public static void put(SnapshotWriter w, double x0, double y0, double x1, double y1, int kind) {
            w.buffer().putShort((short) w.relX(x0)).putShort((short) w.relY(y0))
                .putShort((short) w.relX(x1)).putShort((short) w.relY(y1)).put((byte) kind);
        }

        public Connector wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public float x0() { return relX(0); }

        public float y0() { return relY(2); }

        public float x1() { return relX(4); }

        public float y1() { return relY(6); }

        public int kind() { return u8(8); }
    }

    /** Transporter beam. {@code 0 rel x0, 2 rel y0, 4 rel x1, 6 rel y1}. */
    public static final class Transporter extends Fly {
        public static final int SIZE = 8;

        public static void put(SnapshotWriter w, double x0, double y0, double x1, double y1) {
            w.buffer().putShort((short) w.relX(x0)).putShort((short) w.relY(y0))
                .putShort((short) w.relX(x1)).putShort((short) w.relY(y1));
        }

        public Transporter wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public float x0() { return relX(0); }

        public float y0() { return relY(2); }

        public float x1() { return relX(4); }

        public float y1() { return relY(6); }
    }

    /**
     * Receiver's item counts and lose item: {@code 0 21 x u8 counts} (by {@link Item} ordinal), {@code 21 u8 loseItem}
     * (C {@code pl->lose_item}, the item KEY_LOSE_ITEM drops: C sends it as PKT_LOSEITEM while the selection is
     * shown; it only changes with that, so it rides along in every record).
     */
    public static final class SelfItems extends Fly {
        public static final int SIZE = Item.COUNT + 1;
        /** Offset of loseItem; a record of this size or less (before loseItem existed) reads loseItem 0. */
        static final int LOSE_ITEM = Item.COUNT;

        /** BEHAVIOR: as {@link #put(SnapshotWriter, int[], int)} with loseItem 0. */
        public static void put(SnapshotWriter w, int[] counts) {
            put(w, counts, 0);
        }

        /**
         * BEHAVIOR: writes counts[0..20], missing entries as 0, each clamped to 0..255, then loseItem clamped to
         * 0..255.
         */
        public static void put(SnapshotWriter w, int[] counts, int loseItem) {
            ByteBuffer out = w.buffer();
            for (int i = 0; i < Item.COUNT; i++) {
                int c = i < counts.length ? counts[i] : 0;
                out.put((byte) clampU8(c));
            }
            out.put((byte) clampU8(loseItem));
        }

        private static int clampU8(int v) {
            return Math.min(255, Math.max(0, v));
        }

        public SelfItems wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int count(int item) { return u8(item); }

        /** The item KEY_LOSE_ITEM drops ({@link Item} ordinal); 0 from an older record without it. */
        public int loseItem() { return sz > LOSE_ITEM ? u8(LOSE_ITEM) : 0; }
    }

    /** Treasure state. {@code 0 u16 index (WorldMap.treasures()), 2 u8 flags}. */
    public static final class TreasureState extends Fly {
        public static final int SIZE = 3;
        public static final int FLAG_HAS_BALL = 1;

        public static void put(SnapshotWriter w, int index, int flags) {
            w.buffer().putShort((short) index).put((byte) flags);
        }

        public TreasureState wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int index() { return u16(0); }

        public int flags() { return u8(2); }
    }

    /**
     * The own ship for the predictor (protocol v2, docs/PROTOCOL.md "PREDICT"):
     * {@code 0 ShipBody core block} ({@link ShipBody#encodeCore}, key flags against the input the server applied this
     * tick; flag bit {@link #FLAG_ROUND_DELAY} says a {@link PredictRound} follows), {@code 63 u8 appliedHash}
     * ({@link #hash} of that input), {@code 64 u8 rareVersion} (the {@link PredictRare} it refers to),
     * {@code 65 u8 kernelVersion} (the KERNEL_CONFIG version it was simulated with, mod 256), {@code 66 u16 stateTag}
     * (the tick, mod 2^16, of the newest KERNEL_STATE message the server has queued to this client: the world is the
     * server's as of the snapshot once that one has arrived). The C frame clock is the snapshot tick plus the rare
     * block's frame offset.
     */
    public static final class Predict extends Fly {
        public static final int SIZE = ShipBody.CORE_SIZE + 5;
        /** Core flag (beyond ShipBody's): roundDelay is nonzero, in a {@link PredictRound} of the same part. */
        public static final int FLAG_ROUND_DELAY = 1 << 3;

        /**
         * BEHAVIOR: the core block (shot age at {@code frames}), the round-delay flag when {@code roundDelay > 0},
         * the hash of {@code appliedKeys}, both versions mod 256 and the state tag mod 2^16.
         */
        public static void put(SnapshotWriter w, ShipBody body, long frames, int roundDelay, long appliedKeys,
                               int rareVersion, int kernelVersion, int stateTag) {
            ByteBuffer out = w.buffer();
            int p = out.position();
            body.encodeCore(out, p, frames, appliedKeys);
            if (roundDelay > 0) {
                out.put(p + ShipBody.CORE_FLAGS, (byte) (out.get(p + ShipBody.CORE_FLAGS) | FLAG_ROUND_DELAY));
            }
            out.put(p + ShipBody.CORE_SIZE, (byte) hash(appliedKeys));
            out.put(p + ShipBody.CORE_SIZE + 1, (byte) rareVersion);
            out.put(p + ShipBody.CORE_SIZE + 2, (byte) kernelVersion);
            out.putShort(p + ShipBody.CORE_SIZE + 3, (short) stateTag);
            out.position(p + SIZE);
        }

        /** BEHAVIOR: an 8-bit fold (xor of the bytes) of a key mask; any single-byte change changes it. */
        public static int hash(long keys) {
            long h = keys ^ keys >>> 32;
            h ^= h >>> 16;
            h ^= h >>> 8;
            return (int) h & 0xFF;
        }

        public Predict wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        /** Whether roundDelay is nonzero (a {@link PredictRound} carries it). */
        public boolean roundDelaySet() { return (u8(ShipBody.CORE_FLAGS) & FLAG_ROUND_DELAY) != 0; }

        /** {@link #hash} of the input the server applied. */
        public int appliedHash() { return u8(ShipBody.CORE_SIZE); }

        /** The rare version the core refers to. */
        public int rareVersion() { return u8(ShipBody.CORE_SIZE + 1); }

        /** The kernel config version mod 256. */
        public int kernelVersion() { return u8(ShipBody.CORE_SIZE + 2); }

        /** The newest queued KERNEL_STATE message's tick, mod 2^16. */
        public int stateTag() { return u16(ShipBody.CORE_SIZE + 3); }

        /**
         * BEHAVIOR: decodes the core block into {@code body} ({@link ShipBody#decodeCore}: shot time against
         * {@code frames}, the key flags against {@code appliedKeys}, the client's own entry for the snapshot's
         * inputTick).
         */
        public void read(ShipBody body, long frames, long appliedKeys) {
            body.decodeCore(b, o, frames, appliedKeys);
        }
    }

    /** {@code 0 u16 roundDelay} of the own ship's PREDICT, sent only when nonzero (clamped to 1..65535). */
    public static final class PredictRound extends Fly {
        public static final int SIZE = 2;

        public static void put(SnapshotWriter w, int roundDelay) {
            w.buffer().putShort((short) Math.clamp(roundDelay, 1, 0xFFFF));
        }

        public PredictRound wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int roundDelay() { return u16(0); }
    }

    /**
     * {@code 0 u8 version, 1 i32 frameOffset} (the C frame clock minus the snapshot tick: constant while the sim
     * runs), {@code 5 ShipBody rare block} ({@link ShipBody#encodeRare}).
     */
    public static final class PredictRare extends Fly {
        public static final int SIZE = 5 + ShipBody.RARE_SIZE;

        public static void put(SnapshotWriter w, ShipBody body, int version, int frameOffset) {
            ByteBuffer out = w.buffer();
            int p = out.position();
            out.put((byte) version);
            out.putInt(frameOffset);
            body.encodeRare(out, p + 5);
            out.position(p + SIZE);
        }

        public PredictRare wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int version() { return u8(0); }

        public int frameOffset() { return b.getInt(o + 1); }

        /** BEHAVIOR: decodes the rare block into {@code body}. */
        public void read(ShipBody body) {
            body.decodeRare(b, o + 5);
        }
    }

    /** {@code 0 u64 prevKeys, 8 u64 lastReceivedKeys} ({@link ShipBody#encodeKeys}). */
    public static final class PredictKeys extends Fly {
        public static final int SIZE = ShipBody.KEYS_SIZE;

        public static void put(SnapshotWriter w, ShipBody body) {
            ByteBuffer out = w.buffer();
            int p = out.position();
            body.encodeKeys(out, p);
            out.position(p + SIZE);
        }

        public PredictKeys wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        /** BEHAVIOR: decodes both key masks into {@code body}. */
        public void read(ShipBody body) {
            body.decodeKeys(b, o);
        }
    }

    /** Temporary wormhole. {@code 0 u16 bx, 2 u16 by, 4 u8 type (Wormhole.Type ordinal)}. */
    public static final class TempWormhole extends Fly {
        public static final int SIZE = 5;

        public static void put(SnapshotWriter w, int bx, int by, int type) {
            w.buffer().putShort((short) bx).putShort((short) by).put((byte) type);
        }

        public TempWormhole wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int bx() { return u16(0); }

        public int by() { return u16(2); }

        public int type() { return u8(4); }
    }

    /**
     * HUD timers of the receiving player.
     * <pre>
     *  0 u16 damaged (frames of ECM damage left, C damaged)   2 u16 roundDelay (frames until the round starts, 0 none)
     *  4 i32 timeLeft (seconds of game time left, -1 none)    8 u16 destruct (self-destruct frames left, 0 none)
     * 10 u16 thrustLeft  12 u16 thrustMax (emergency thrust, max 0 = none)
     * 14 u16 shieldLeft  16 u16 shieldMax (emergency shield, max 0 = none)
     * 18 u16 phasingLeft 20 u16 phasingMax (phasing device, max 0 = none)
     * 22 i16 shutdownCount (frames until server shutdown, C ShutdownServer, -1 none)  24 u16 shutdownDelay (C ShutdownDelay)
     * </pre>
     * Readers of a shorter (older) record get 0 for the appended fields (-1 for shutdownCount).
     */
    public static final class Timers extends Fly {
        public static final int SIZE = 26;

        /** BEHAVIOR: as the full put with no destruct, meters or shutdown. */
        public static void put(SnapshotWriter w, int damaged, int roundDelay, int timeLeft) {
            put(w, damaged, roundDelay, timeLeft, 0, 0, 0, 0, 0, 0, 0, -1, 0);
        }

        /** BEHAVIOR: u16 fields are clamped to 0..65535, shutdownCount to -1..32767. */
        @SuppressWarnings("checkstyle:ParameterNumber")
        public static void put(SnapshotWriter w, int damaged, int roundDelay, int timeLeft, int destruct,
                               int thrustLeft, int thrustMax, int shieldLeft, int shieldMax, int phasingLeft,
                               int phasingMax, int shutdownCount, int shutdownDelay) {
            ByteBuffer out = w.buffer();
            out.putShort((short) damaged).putShort((short) roundDelay).putInt(timeLeft);
            out.putShort(u16Of(destruct));
            out.putShort(u16Of(thrustLeft)).putShort(u16Of(thrustMax));
            out.putShort(u16Of(shieldLeft)).putShort(u16Of(shieldMax));
            out.putShort(u16Of(phasingLeft)).putShort(u16Of(phasingMax));
            out.putShort((short) Math.min(32767, Math.max(-1, shutdownCount)));
            out.putShort(u16Of(shutdownDelay));
        }

        private static short u16Of(int v) {
            return (short) Math.min(0xFFFF, Math.max(0, v));
        }

        public Timers wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int damaged() { return u16(0); }

        public int roundDelay() { return u16(2); }

        public int timeLeft() { return b.getInt(o + 4); }

        public int destruct() { return sz > 9 ? u16(8) : 0; }

        public int thrustLeft() { return sz > 11 ? u16(10) : 0; }

        public int thrustMax() { return sz > 13 ? u16(12) : 0; }

        public int shieldLeft() { return sz > 15 ? u16(14) : 0; }

        public int shieldMax() { return sz > 17 ? u16(16) : 0; }

        public int phasingLeft() { return sz > 19 ? u16(18) : 0; }

        public int phasingMax() { return sz > 21 ? u16(20) : 0; }

        public int shutdownCount() { return sz > 23 ? b.getShort(o + 22) : -1; }

        public int shutdownDelay() { return sz > 25 ? u16(24) : 0; }
    }

    /**
     * A sound event for the receiver (C {@code Send_audio}).
     * {@code 0 u8 event (SoundEvent ordinal), 1 u8 volume (SoundEvent.MIN_VOLUME..MAX_VOLUME)}.
     */
    public static final class Sound extends Fly {
        public static final int SIZE = 2;

        public static void put(SnapshotWriter w, int event, int volume) {
            w.buffer().put((byte) event).put((byte) volume);
        }

        public Sound wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int event() { return u8(0); }

        public int volume() { return u8(1); }
    }
}
