package com.fulcrologic.jxpilot.common.net;

import java.nio.ByteBuffer;

/**
 * Codecs for control datagrams and reliable messages. Each nested class is a reusable mutable
 * holder: set fields then {@code write(buf)}; or {@code read(buf)} to fill fields. Strings are held
 * in {@link StringBuilder}s so reading does not allocate after warm-up. Reliable messages also have
 * {@code send(ReliableChannel)} which wraps begin/write/end.
 * <p>
 * {@code write} uses relative puts at the buffer position; {@code read} consumes from the position.
 * Packet headers are not included.
 */
public final class Msg {
    private Msg() {}

    /** A reliable message payload writer (implemented by the message classes). */
    public interface Payload {
        void write(ByteBuffer b);
    }

    /**
     * Queue {@code p} as one reliable message of {@code type}.
     * <p>
     * BEHAVIOR: on success the message is queued; if writing throws (e.g. an over-long payload) the
     * open message is aborted, so the channel stays usable, and the exception propagates.
     */
    static void send(ReliableChannel ch, Payload p, int type) {
        ByteBuffer b = ch.begin(type);
        boolean ok = false;
        try {
            p.write(b);
            ok = true;
        } finally {
            if (ok) {
                ch.end();
            } else {
                ch.abort();
            }
        }
    }

    /** Ship shape strings are capped to this many chars on decode (C {@code MSG_LEN}). */
    public static final int MAX_SHAPE_LEN = 256;

    // ================================================================ unreliable control

    /** CONNECT body (client to server). */
    public static final class Connect {
        /** Random per-connect-attempt value echoed by ACCEPT/REJECT. */
        public int nonce;
        /** Preferred team 0-9, or {@link Protocol#NO_TEAM}. */
        public int team = Protocol.NO_TEAM;
        public final StringBuilder name = new StringBuilder();
        /** Ship shape string; empty = default ship. */
        public final StringBuilder shape = new StringBuilder();

        public void write(ByteBuffer b) {
            b.putInt(nonce);
            b.put((byte) team);
            Wire.putString8(b, name);
            Wire.putString16(b, shape);
        }

        public void read(ByteBuffer b) {
            nonce = b.getInt();
            team = b.get() & 0xFF;
            Wire.getString8(b, name);
            Wire.getString16(b, shape);
            Wire.cap(shape, MAX_SHAPE_LEN);
        }
    }

    /** ACCEPT body (server to client). The header's sessionId is the new session. */
    public static final class Accept {
        public int nonce;
        public int playerId;

        public void write(ByteBuffer b) {
            b.putInt(nonce);
            b.putShort((short) playerId);
        }

        public void read(ByteBuffer b) {
            nonce = b.getInt();
            playerId = b.getShort() & 0xFFFF;
        }
    }

    /** REJECT body (server to client). */
    public static final class Reject {
        public static final int REASON_FULL = 1;
        public static final int REASON_VERSION = 2;
        public static final int REASON_BAD_NAME = 3;
        public static final int REASON_OTHER = 4;

        public int nonce;
        public int reason;
        public final StringBuilder text = new StringBuilder();

        public void write(ByteBuffer b) {
            b.putInt(nonce);
            b.put((byte) reason);
            Wire.putString8(b, text);
        }

        public void read(ByteBuffer b) {
            nonce = b.getInt();
            reason = b.get() & 0xFF;
            Wire.getString8(b, text);
        }
    }

    /** DISCONNECT body (either way). */
    public static final class Disconnect {
        public static final int REASON_QUIT = 0;
        public static final int REASON_TIMEOUT = 1;
        public static final int REASON_KICKED = 2;
        public static final int REASON_SHUTDOWN = 3;

        public int reason;
        public final StringBuilder text = new StringBuilder();

        public void write(ByteBuffer b) {
            b.put((byte) reason);
            Wire.putString8(b, text);
        }

        public void read(ByteBuffer b) {
            reason = b.get() & 0xFF;
            Wire.getString8(b, text);
        }
    }

    /** PING/PONG body: the receiver of a PING replies PONG with the same values. */
    public static final class Ping {
        public int id;
        public long timeNanos;

        public void write(ByteBuffer b) {
            b.putInt(id);
            b.putLong(timeNanos);
        }

        public void read(ByteBuffer b) {
            id = b.getInt();
            timeNanos = b.getLong();
        }
    }

    // ================================================================ reliable, server -> client

    /** {@link ReliableType#WELCOME}. */
    public static final class Welcome implements Payload {
        public static final int FLAG_TEAM_PLAY = 1;
        public static final int FLAG_EDGE_WRAP = 2;
        public static final int FLAG_TIMING = 4;

        public int playerId;
        public int team = Protocol.NO_TEAM;
        /** Server simulation ticks per second. */
        public int simHz;
        /** Snapshots per second sent to this client. */
        public int snapshotHz;
        public int flags;
        /** Original XPilot frames per second (framesPerSecond option). */
        public float gameFps;
        /** Uncompressed config text length in bytes (Latin-1). */
        public int configLength;
        /** Deflated length; equals the sum of all DATA_CHUNK payloads. */
        public int configCompressedLength;
        /** CRC32 of the uncompressed config text. */
        public int configCrc32;
        public final StringBuilder serverName = new StringBuilder();

        public void write(ByteBuffer b) {
            b.putShort((short) playerId);
            b.put((byte) team);
            b.put((byte) simHz);
            b.put((byte) snapshotHz);
            b.put((byte) flags);
            b.putFloat(gameFps);
            b.putInt(configLength);
            b.putInt(configCompressedLength);
            b.putInt(configCrc32);
            Wire.putString8(b, serverName);
        }

        public void read(ByteBuffer b) {
            playerId = b.getShort() & 0xFFFF;
            team = b.get() & 0xFF;
            simHz = b.get() & 0xFF;
            snapshotHz = b.get() & 0xFF;
            flags = b.get() & 0xFF;
            gameFps = b.getFloat();
            configLength = b.getInt();
            configCompressedLength = b.getInt();
            configCrc32 = b.getInt();
            Wire.getString8(b, serverName);
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.WELCOME);
        }
    }

    /** {@link ReliableType#DATA_CHUNK}: {@code u32 offset} then raw bytes to the end of the message. */
    public static final class DataChunk {
        private DataChunk() {}

        /** Queue a chunk of {@code data[offset, offset+len)}. */
        public static void send(ReliableChannel ch, byte[] data, int offset, int len) {
            ByteBuffer b = ch.begin(ReliableType.DATA_CHUNK);
            if (b.remaining() < 4 + len) {
                ch.abort();
                throw new IllegalArgumentException("chunk of " + len + " bytes exceeds the reliable slot");
            }
            b.putInt(offset);
            b.put(data, offset, len);
            ch.end();
        }

        /**
         * Copy a received chunk into {@code dest} at its offset.
         *
         * @return the end offset (offset + length) or -1 if it does not fit
         */
        public static int read(ByteBuffer b, byte[] dest) {
            int off = b.getInt();
            int len = b.remaining();
            if (off < 0 || off > dest.length || len > dest.length - off) {
                return -1;
            }
            b.get(dest, off, len);
            return off + len;
        }
    }

    /** {@link ReliableType#PLAYER_JOIN}. */
    public static final class PlayerJoin implements Payload {
        public static final int FLAG_ROBOT = 1;
        public static final int FLAG_TANK = 2;
        /** The joining player is the receiving client itself. */
        public static final int FLAG_SELF = 4;
        public static final int FLAG_PAUSED = 8;

        public int id;
        public int team = Protocol.NO_TEAM;
        public int flags;
        /** Engine power (for client-side thrust sparks, see {@link PlayerThrust}). */
        public float power = 45.0f;
        /** Afterburner count 0..15 (thrust spark colour split). */
        public int afterburners;
        public final StringBuilder name = new StringBuilder();
        /** Ship shape string; empty = default ship. */
        public final StringBuilder shape = new StringBuilder();

        public void write(ByteBuffer b) {
            b.putShort((short) id);
            b.put((byte) team);
            b.put((byte) flags);
            b.putFloat(power);
            b.put((byte) afterburners);
            Wire.putString8(b, name);
            Wire.putString16(b, shape);
        }

        public void read(ByteBuffer b) {
            id = b.getShort() & 0xFFFF;
            team = b.get() & 0xFF;
            flags = b.get() & 0xFF;
            power = b.getFloat();
            afterburners = b.get() & 0xFF;
            Wire.getString8(b, name);
            Wire.getString16(b, shape);
            Wire.cap(shape, MAX_SHAPE_LEN);
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.PLAYER_JOIN);
        }
    }

    /**
     * {@link ReliableType#PLAYER_THRUST}: a player's current engine power and afterburner count, sent
     * when they change (power keys, settings swap, afterburner pickup/loss) so clients can generate the
     * same thrust sparks as the server ({@link com.fulcrologic.jxpilot.common.math.ThrustSparks}).
     */
    public static final class PlayerThrust implements Payload {
        public int id;
        public float power;
        public int afterburners;

        public void write(ByteBuffer b) {
            b.putShort((short) id);
            b.putFloat(power);
            b.put((byte) afterburners);
        }

        public void read(ByteBuffer b) {
            id = b.getShort() & 0xFFFF;
            power = b.getFloat();
            afterburners = b.get() & 0xFF;
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.PLAYER_THRUST);
        }
    }

    /** {@link ReliableType#PLAYER_LEAVE}. */
    public static final class PlayerLeave implements Payload {
        public int id;

        public void write(ByteBuffer b) {
            b.putShort((short) id);
        }

        public void read(ByteBuffer b) {
            id = b.getShort() & 0xFFFF;
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.PLAYER_LEAVE);
        }
    }

    /** {@link ReliableType#PLAYER_TEAM}. */
    public static final class PlayerTeam implements Payload {
        public int id;
        public int team;

        public void write(ByteBuffer b) {
            b.putShort((short) id);
            b.put((byte) team);
        }

        public void read(ByteBuffer b) {
            id = b.getShort() & 0xFFFF;
            team = b.get() & 0xFF;
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.PLAYER_TEAM);
        }
    }

    /** {@link ReliableType#SCORE}. Status flags mirror XPilot's scoreboard markers. */
    public static final class Score implements Payload {
        public static final int STATUS_DEAD = 1;
        public static final int STATUS_PAUSED = 2;
        public static final int STATUS_WAITING = 4;
        public static final int STATUS_OUT_OF_LIVES = 8;

        public int id;
        public float score;
        /** Remaining lives (limitedLives) or -1. */
        public int lives = -1;
        public int status;

        public void write(ByteBuffer b) {
            b.putShort((short) id);
            b.putFloat(score);
            b.putShort((short) lives);
            b.put((byte) status);
        }

        public void read(ByteBuffer b) {
            id = b.getShort() & 0xFFFF;
            score = b.getFloat();
            lives = b.getShort();
            status = b.get() & 0xFF;
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.SCORE);
        }
    }

    /** {@link ReliableType#TEAM_SCORE}. */
    public static final class TeamScore implements Payload {
        public int team;
        public float score;

        public void write(ByteBuffer b) {
            b.put((byte) team);
            b.putFloat(score);
        }

        public void read(ByteBuffer b) {
            team = b.get() & 0xFF;
            score = b.getFloat();
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.TEAM_SCORE);
        }
    }

    /** {@link ReliableType#MESSAGE} (server to client) and {@link ReliableType#CHAT} (client to server). */
    public static final class Text implements Payload {
        public static final int KIND_CHAT = 0;
        public static final int KIND_TEAM_CHAT = 1;
        public static final int KIND_PRIVATE = 2;
        public static final int KIND_SERVER = 3;
        public static final int KIND_GAME = 4;

        public int kind;
        /** Sender player id or {@link Protocol#NO_ID} for the server. */
        public int from = Protocol.NO_ID;
        public final StringBuilder text = new StringBuilder();

        public void write(ByteBuffer b) {
            b.put((byte) kind);
            b.putShort((short) from);
            Wire.putString16(b, text);
        }

        public void read(ByteBuffer b) {
            kind = b.get() & 0xFF;
            from = b.getShort() & 0xFFFF;
            Wire.getString16(b, text);
        }

        /** Send as a server MESSAGE. */
        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.MESSAGE);
        }

        /** Send as a client CHAT. */
        public void sendChat(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.CHAT);
        }
    }

    /** {@link ReliableType#BASE_OWNER}. */
    public static final class BaseOwner implements Payload {
        public int baseIndex;
        /** Owner or {@link Protocol#NO_ID}. */
        public int playerId = Protocol.NO_ID;

        public void write(ByteBuffer b) {
            b.putShort((short) baseIndex);
            b.putShort((short) playerId);
        }

        public void read(ByteBuffer b) {
            baseIndex = b.getShort() & 0xFFFF;
            playerId = b.getShort() & 0xFFFF;
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.BASE_OWNER);
        }
    }

    /** {@link ReliableType#GAME_EVENT}: generic event with two ids and a value. */
    public static final class GameEvent implements Payload {
        /** a = killer (or NO_ID), b = victim. */
        public static final int KILL = 1;
        /** a = player who crashed. */
        public static final int CRASH = 2;
        /** a = player, b = base index. */
        public static final int SPAWN = 3;
        /** a = winning team or player, value = score. */
        public static final int ROUND_END = 4;
        /** a = player, b = target/treasure index. */
        public static final int TARGET_DESTROYED = 5;
        public static final int TREASURE_DESTROYED = 6;
        /** a = player, b = checkpoint number, value = lap time. */
        public static final int CHECKPOINT = 7;

        public int kind;
        public int a = Protocol.NO_ID;
        public int b = Protocol.NO_ID;
        public float value;

        public void write(ByteBuffer buf) {
            buf.put((byte) kind);
            buf.putShort((short) a);
            buf.putShort((short) b);
            buf.putFloat(value);
        }

        public void read(ByteBuffer buf) {
            kind = buf.get() & 0xFF;
            a = buf.getShort() & 0xFFFF;
            b = buf.getShort() & 0xFFFF;
            value = buf.getFloat();
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.GAME_EVENT);
        }
    }

    // ================================================================ reliable, client -> server

    /** {@link ReliableType#SETTINGS}: XPilot power/turnspeed/turnresistance and their alternates. */
    public static final class Settings implements Payload {
        public static final int FLAG_AUTO_SHIELD = 1;

        /** Defaults from {@code src/client/default.c}. */
        public float power = 45.0f;
        public float turnSpeed = 35.0f;
        public float turnResistance = 0.12f;
        public float altPower = 35.0f;
        public float altTurnSpeed = 25.0f;
        public float altTurnResistance = 0.12f;
        public int flags;

        public void write(ByteBuffer b) {
            b.putFloat(power);
            b.putFloat(turnSpeed);
            b.putFloat(turnResistance);
            b.putFloat(altPower);
            b.putFloat(altTurnSpeed);
            b.putFloat(altTurnResistance);
            b.put((byte) flags);
        }

        public void read(ByteBuffer b) {
            power = b.getFloat();
            turnSpeed = b.getFloat();
            turnResistance = b.getFloat();
            altPower = b.getFloat();
            altTurnSpeed = b.getFloat();
            altTurnResistance = b.getFloat();
            flags = b.get() & 0xFF;
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.SETTINGS);
        }
    }

    /** {@link ReliableType#CHANGE_TEAM}. */
    public static final class ChangeTeam implements Payload {
        public int team;

        public void write(ByteBuffer b) {
            b.put((byte) team);
        }

        public void read(ByteBuffer b) {
            team = b.get() & 0xFF;
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.CHANGE_TEAM);
        }
    }

    /** {@link ReliableType#SHAPE}. */
    public static final class Shape implements Payload {
        public final StringBuilder shape = new StringBuilder();

        public void write(ByteBuffer b) {
            Wire.putString16(b, shape);
        }

        public void read(ByteBuffer b) {
            Wire.getString16(b, shape);
            Wire.cap(shape, MAX_SHAPE_LEN);
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.SHAPE);
        }
    }

    /** Queue an empty {@link ReliableType#READY}. */
    public static void sendReady(ReliableChannel ch) {
        ch.begin(ReliableType.READY);
        ch.end();
    }
}
