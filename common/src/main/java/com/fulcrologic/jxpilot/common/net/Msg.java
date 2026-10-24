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
    /** C {@code MSG_LEN}: initial capacity of the text builders, so normal messages never grow them. */
    public static final int MSG_LEN = 256;

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

        /**
         * Read without throwing, for untrusted input (a malformed CONNECT flood must not allocate an
         * exception per packet).
         * <p>
         * BEHAVIOR: checks every length against the bytes remaining before reading; a body too short
         * for its nonce, team or either string returns false with the buffer position unchanged.
         * BEHAVIOR: a complete body is read as by {@link #read} and returns true.
         */
        public boolean tryRead(ByteBuffer b) {
            int p = b.position();
            int lim = b.limit();
            if (lim - p < 6) {
                return false;
            }
            int s = p + 6 + (b.get(p + 5) & 0xFF);
            if (lim - s < 2 || lim - s - 2 < (b.getShort(s) & 0xFFFF)) {
                return false;
            }
            read(b);
            return true;
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
        public final StringBuilder text = new StringBuilder(MSG_LEN);

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
        public final StringBuilder text = new StringBuilder(MSG_LEN);

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

    /**
     * {@link ReliableType#SCORE_OBJECT}: a score change shown at a map position on the receiver's HUD
     * (C {@code Send_score_object}, sent to the player whose score changed).
     */
    public static final class ScoreObject implements Payload {
        /** Points gained (negative when lost). */
        public float score;
        /** Block coordinates where the points were made. */
        public int bx;
        public int by;
        /** Short label ("[Wall]", a player name, ...); may be empty. Presized: decoding never grows it. */
        public final StringBuilder text = new StringBuilder(MSG_LEN);

        public void write(ByteBuffer b) {
            b.putFloat(score);
            b.putShort((short) bx);
            b.putShort((short) by);
            Wire.putString8(b, text);
        }

        public void read(ByteBuffer b) {
            score = b.getFloat();
            bx = b.getShort() & 0xFFFF;
            by = b.getShort() & 0xFFFF;
            Wire.getString8(b, text);
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.SCORE_OBJECT);
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
        public final StringBuilder text = new StringBuilder(MSG_LEN);

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

    /**
     * {@link ReliableType#TIMING}: race progress of a player (C {@code Send_timing}): u16 id, u8 check (the next
     * checkpoint the player must pass, C {@code pl->check}), u16 round (laps started, C {@code pl->round}).
     * The last checkpoint passed is check - 1 (wrapping), or none while round is 0.
     */
    public static final class Timing implements Payload {
        public int id;
        public int check;
        public int round;

        public void write(ByteBuffer b) {
            b.putShort((short) id);
            b.put((byte) check);
            b.putShort((short) round);
        }

        public void read(ByteBuffer b) {
            id = b.getShort() & 0xFFFF;
            check = b.get() & 0xFF;
            round = b.getShort() & 0xFFFF;
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.TIMING);
        }
    }

    /** {@link ReliableType#GAME_EVENT}: generic event with two ids and a value. */
    public static final class GameEvent implements Payload {
        /**
         * a = killer (or NO_ID), b = victim. A shove into a wall is a KILL by the last pusher with value = the
         * crash sub-type ({@link #CRASH_WALL}...); other kills have value 0.
         */
        public static final int KILL = 1;
        /**
         * a = player who crashed, b = the other ship in a two-ship crash (else NO_ID), value = the crash
         * sub-type ({@link #CRASH_WALL}...).
         */
        public static final int CRASH = 2;
        /** a = player, b = base index. */
        public static final int SPAWN = 3;

        /** CRASH / shove-KILL value: a wall (C CrashWall, CrashWallSpeed, CrashWallNoFuel, CrashWallAngle). */
        public static final int CRASH_WALL = 1;
        /** CRASH value: a target. */
        public static final int CRASH_TARGET = 2;
        /** CRASH value: a treasure. */
        public static final int CRASH_TREASURE = 3;
        /** CRASH value: a cannon. */
        public static final int CRASH_CANNON = 4;
        /** CRASH value: left the known universe. */
        public static final int CRASH_UNIVERSE = 5;
        /** CRASH value: two ships crashed into each other. */
        public static final int CRASH_SHIPS = 6;
        /** CRASH value: C CrashUnknown ("slammed into a programming error"). */
        public static final int CRASH_UNKNOWN = 7;
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

    /**
     * {@link ReliableType#DISPLAY}: the world area the client shows, in world pixels (C {@code PKT_DISPLAY}'s
     * {@code view_width}, {@code view_height}; the C client sends its draw size times its scale factor).
     * The server limits each to [{@link #MIN_VIEW_SIZE}, {@link #MAX_VIEW_SIZE}] (C {@code Receive_display}).
     */
    public static final class Display implements Payload {
        /** C {@code MIN_VIEW_SIZE}, {@code MAX_VIEW_SIZE}, {@code DEF_VIEW_SIZE} (common/draw.h). */
        public static final int MIN_VIEW_SIZE = 384;
        public static final int MAX_VIEW_SIZE = 1024;
        public static final int DEF_VIEW_SIZE = 768;

        public int viewWidth = DEF_VIEW_SIZE;
        public int viewHeight = DEF_VIEW_SIZE;

        /** BEHAVIOR: u16 width, u16 height; values outside 0..65535 are saturated, never wrapped. */
        public void write(ByteBuffer b) {
            b.putShort((short) u16(viewWidth));
            b.putShort((short) u16(viewHeight));
        }

        public void read(ByteBuffer b) {
            viewWidth = b.getShort() & 0xFFFF;
            viewHeight = b.getShort() & 0xFFFF;
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.DISPLAY);
        }

        private static int u16(int v) {
            return Math.max(0, Math.min(0xFFFF, v));
        }
    }

    /**
     * {@link ReliableType#MODIFIER_BANK}: u8 bank (0..3), str8 text (C {@code "%c%c%s"}, PKT_MODIFIERBANK, bank,
     * {@code modBankStr[bank]}). The text is C's modifier string ("FN C V2 X4 LS"); the server parses it.
     */
    public static final class ModifierBank implements Payload {
        /** C {@code NUM_MODBANKS}. */
        public static final int BANKS = 4;

        public int bank;
        public final StringBuilder text = new StringBuilder(com.fulcrologic.jxpilot.common.Const.MAX_CHARS);

        /** BEHAVIOR: u8 bank, then the text with a u8 length (at most 255 chars, see {@link Wire#putString8}). */
        public void write(ByteBuffer b) {
            b.put((byte) bank);
            Wire.putString8(b, text);
        }

        /** BEHAVIOR: reads the bank (0..255, unchecked) and the whole text. */
        public void read(ByteBuffer b) {
            bank = b.get() & 0xFF;
            Wire.getString8(b, text);
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.MODIFIER_BANK);
        }
    }

    /** {@link ReliableType#SEEK}: u16 programmerId, u16 robotId, u16 soughtId (C {@code "%c%hd%hd%hd"}). */
    public static final class Seek implements Payload {
        public int programmerId;
        public int robotId;
        public int soughtId;

        public void write(ByteBuffer b) {
            b.putShort((short) programmerId);
            b.putShort((short) robotId);
            b.putShort((short) soughtId);
        }

        public void read(ByteBuffer b) {
            programmerId = b.getShort() & 0xFFFF;
            robotId = b.getShort() & 0xFFFF;
            soughtId = b.getShort() & 0xFFFF;
        }

        public void send(ReliableChannel ch) {
            Msg.send(ch, this, ReliableType.SEEK);
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
