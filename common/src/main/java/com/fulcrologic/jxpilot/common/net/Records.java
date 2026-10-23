package com.fulcrologic.jxpilot.common.net;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.XMath;

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
        float ox;
        float oy;
        double ww;
        double wh;
        boolean wrap;

        final void bind(SnapshotReader r, int i) {
            b = r.buffer();
            o = r.recordOffset(i);
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

    /** Shot kinds (high nibble of the SHOT section variant). */
    public static final class ShotKind {
        private ShotKind() {}

        public static final int SHOT = 0;
        public static final int CANNON_SHOT = 1;
        /** Cluster / nuke fragments that are dangerous. */
        public static final int CLUSTER = 2;
        public static final int PULSE = 3;

        /** Section variant for a kind and {@link ObjColor}. */
        public static int variant(int kind, int color) {
            return (kind << 4) | (color & 0xF);
        }

        /** Kind from a variant. */
        public static int kindOf(int variant) {
            return (variant >> 4) & 0xF;
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
     * </pre>
     */
    public static final class Self extends Fly {
        public static final int SIZE = 38;

        /** BEHAVIOR: respawnFrames is clamped to 0..65535. */
        @SuppressWarnings("checkstyle:ParameterNumber")
        public static void put(SnapshotWriter w, int id, int flags, float x, float y, float vx, float vy,
                               float heading, float fuel, float fuelMax, int lockId, int numTanks,
                               int currentTank, int respawnFrames) {
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
    }

    /**
     * Another ship. {@code 0 u16 id, 2 u16 flags, 4 rel x, 6 rel y, 8 u16 heading, 10 i16 vx, 12 i16 vy}
     * (velocities in 1/16 px/frame). Team, name and shape come from {@link Msg.PlayerJoin}.
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
     * A bullet: {@code 0 rel x, 2 rel y, 4 i8 vx, 5 i8 vy} (whole px/frame, for client extrapolation).
     * Kind and colour are the section variant ({@link ShotKind#variant}). 6 bytes.
     */
    public static final class Shot extends Fly {
        public static final int SIZE = 6;

        public static void put(SnapshotWriter w, double x, double y, double vx, double vy) {
            ByteBuffer out = w.buffer();
            out.putShort((short) w.relX(x));
            out.putShort((short) w.relY(y));
            out.put((byte) Wire.velToI8(vx));
            out.put((byte) Wire.velToI8(vy));
        }

        public Shot wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public float x() { return relX(0); }

        public float y() { return relY(2); }

        public int vx() { return b.get(o + 4); }

        public int vy() { return b.get(o + 5); }
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

    /** Asteroid. {@code 0 rel x, 2 rel y, 4 u8 size, 5 u16 rotation}. */
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

        public int size() { return u8(4); }

        public float rotation() { return Wire.u16ToAngle(b.getShort(o + 5)); }
    }

    /** Wreckage. {@code 0 rel x, 2 rel y, 4 u8 shape, 5 u8 size, 6 u16 rotation}. */
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

    /** Line between two points. {@code 0 rel x0, 2 rel y0, 4 rel x1, 6 rel y1, 8 u8 kind}. */
    public static final class Connector extends Fly {
        public static final int SIZE = 9;
        public static final int KIND_BALL = 0;
        public static final int KIND_TRACTOR = 1;
        public static final int KIND_PRESSOR = 2;

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

    /** Receiver's item counts: 21 x u8 indexed by {@link Item} ordinal. */
    public static final class SelfItems extends Fly {
        public static final int SIZE = Item.COUNT;

        /** BEHAVIOR: writes counts[0..20], missing entries as 0, each clamped to 0..255. */
        public static void put(SnapshotWriter w, int[] counts) {
            ByteBuffer out = w.buffer();
            for (int i = 0; i < SIZE; i++) {
                int c = i < counts.length ? counts[i] : 0;
                out.put((byte) Math.min(255, Math.max(0, c)));
            }
        }

        public SelfItems wrap(SnapshotReader r, int i) {
            bind(r, i);
            return this;
        }

        public int count(int item) { return u8(item); }
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
}
