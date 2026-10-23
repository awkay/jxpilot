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
package com.fulcrologic.jxpilot.client.xp;

/**
 * Constants of the original XPilot 4.5.5 client/server protocol (C {@code common/pack.h},
 * {@code common/packet.h}, {@code common/setup.h}, {@code common/rules.h}, {@code common/const.h},
 * {@code common/draw.h}). Only the client side is implemented in JXPilot ({@link XpConnection}).
 */
public final class XpProto {
    private XpProto() {}

    // ---------------------------------------------------------------- pack.h: contact protocol

    /** C {@code SERVER_PORT}: default contact port. */
    public static final int SERVER_PORT = 15345;
    /** C {@code MAGIC}: version 0x4501 in the high word, 0xF4ED in the low word. */
    public static final int MAGIC = 0x4501F4ED;
    /** C {@code MY_VERSION}: the newest protocol version this client speaks. */
    public static final int MY_VERSION = 0x4501;
    /** Oldest server version this client accepts (the 4.2.3 PKT_SELF layout with PKT_SELF_ITEMS). */
    public static final int MIN_SERVER_VERSION = 0x4203;
    /** Low word every XPilot magic shares. */
    public static final int MAGIC_LOW = 0xF4ED;

    /** C {@code VERSION2MAGIC}. */
    public static int versionToMagic(int version) {
        return ((version & 0xFFFF) << 16) | MAGIC_LOW;
    }

    /** C {@code MAGIC2VERSION}. */
    public static int magicToVersion(int magic) {
        return (magic >>> 16) & 0xFFFF;
    }

    public static final int ENTER_GAME_PACK = 0x00;
    public static final int ENTER_QUEUE_PACK = 0x01;
    public static final int REPLY_PACK = 0x10;
    public static final int REPORT_STATUS_PACK = 0x21;
    public static final int CONTACT_PACK = 0x31;

    /** C {@code MAX_NAME_LEN}: nick/real name limit including the NUL. */
    public static final int MAX_NAME_LEN = 16;
    /** C {@code MAX_HOST_LEN}. */
    public static final int MAX_HOST_LEN = 64;

    // contact status codes
    public static final int SUCCESS = 0x00;
    public static final int E_NOT_OWNER = 0x01;
    public static final int E_GAME_FULL = 0x02;
    public static final int E_TEAM_FULL = 0x03;
    public static final int E_TEAM_NOT_SET = 0x04;
    public static final int E_GAME_LOCKED = 0x05;
    public static final int E_NOT_FOUND = 0x07;
    public static final int E_IN_USE = 0x08;
    public static final int E_SOCKET = 0x09;
    public static final int E_INVAL = 0x0A;
    public static final int E_VERSION = 0x0C;
    public static final int E_NOENT = 0x0D;
    public static final int E_UNDEFINED = 0x0E;

    /**
     * Text of a contact status code, as the C client prints it ({@code textinterface.c}).
     * <p>
     * BEHAVIOR: every known code has its C text; unknown codes say so.
     */
    public static String statusText(int status) {
        return switch (status) {
            case SUCCESS -> "success";
            case E_NOT_OWNER -> "permission denied, not owner";
            case E_GAME_FULL -> "game full";
            case E_TEAM_FULL -> "team full";
            case E_TEAM_NOT_SET -> "team play selected and no team specified";
            case E_GAME_LOCKED -> "game locked";
            case E_NOT_FOUND -> "player not found";
            case E_IN_USE -> "nick already in use";
            case E_SOCKET -> "server can't setup socket";
            case E_INVAL -> "invalid input parameters";
            case E_VERSION -> "incompatible version";
            case E_NOENT -> "no such variable";
            case E_UNDEFINED -> "requested operation undefined";
            default -> "unknown error status " + status;
        };
    }

    // ---------------------------------------------------------------- packet.h

    /** C {@code KEYBOARD_SIZE}: bytes of the key bit vector (since 3.8.0). */
    public static final int KEYBOARD_SIZE = 9;

    public static final int PKT_UNDEFINED = 0;
    public static final int PKT_VERIFY = 1;
    public static final int PKT_REPLY = 2;
    public static final int PKT_PLAY = 3;
    public static final int PKT_QUIT = 4;
    public static final int PKT_MESSAGE = 5;
    public static final int PKT_START = 6;
    public static final int PKT_END = 7;
    public static final int PKT_SELF = 8;
    public static final int PKT_DAMAGED = 9;
    public static final int PKT_CONNECTOR = 10;
    public static final int PKT_REFUEL = 11;
    public static final int PKT_SHIP = 12;
    public static final int PKT_ECM = 13;
    public static final int PKT_PAUSED = 14;
    public static final int PKT_ITEM = 15;
    public static final int PKT_MINE = 16;
    public static final int PKT_BALL = 17;
    public static final int PKT_MISSILE = 18;
    public static final int PKT_SHUTDOWN = 19;
    public static final int PKT_STRING = 20;
    public static final int PKT_DESTRUCT = 21;
    public static final int PKT_RADAR = 22;
    public static final int PKT_TARGET = 23;
    public static final int PKT_KEYBOARD = 24;
    public static final int PKT_SEEK = 25;
    public static final int PKT_SELF_ITEMS = 26;
    public static final int PKT_TEAM_SCORE = 27;
    public static final int PKT_PLAYER = 28;
    public static final int PKT_SCORE = 29;
    public static final int PKT_FUEL = 30;
    public static final int PKT_BASE = 31;
    public static final int PKT_CANNON = 32;
    public static final int PKT_LEAVE = 33;
    public static final int PKT_POWER = 34;
    public static final int PKT_POWER_S = 35;
    public static final int PKT_TURNSPEED = 36;
    public static final int PKT_TURNSPEED_S = 37;
    public static final int PKT_TURNRESISTANCE = 38;
    public static final int PKT_TURNRESISTANCE_S = 39;
    public static final int PKT_WAR = 40;
    public static final int PKT_MAGIC = 41;
    public static final int PKT_RELIABLE = 42;
    public static final int PKT_ACK = 43;
    public static final int PKT_FASTRADAR = 44;
    public static final int PKT_TRANS = 45;
    public static final int PKT_ACK_CANNON = 46;
    public static final int PKT_ACK_FUEL = 47;
    public static final int PKT_ACK_TARGET = 48;
    public static final int PKT_SCORE_OBJECT = 49;
    public static final int PKT_AUDIO = 50;
    public static final int PKT_TALK = 51;
    public static final int PKT_TALK_ACK = 52;
    public static final int PKT_TIME_LEFT = 53;
    public static final int PKT_LASER = 54;
    public static final int PKT_DISPLAY = 55;
    public static final int PKT_EYES = 56;
    public static final int PKT_SHAPE = 57;
    public static final int PKT_MOTD = 58;
    public static final int PKT_LOSEITEM = 59;
    public static final int PKT_MODIFIERS = 68;
    public static final int PKT_FASTSHOT = 69;
    public static final int PKT_THRUSTTIME = 70;
    public static final int PKT_MODIFIERBANK = 71;
    public static final int PKT_SHIELDTIME = 72;
    public static final int PKT_POINTER_MOVE = 73;
    public static final int PKT_REQUEST_AUDIO = 74;
    public static final int PKT_ASYNC_FPS = 75;
    public static final int PKT_TIMING = 76;
    public static final int PKT_PHASINGTIME = 77;
    public static final int PKT_ROUNDDELAY = 78;
    public static final int PKT_WRECKAGE = 79;
    public static final int PKT_ASTEROID = 80;
    public static final int PKT_WORMHOLE = 81;
    public static final int PKT_FAILURE = 101;
    public static final int PKT_SUCCESS = 102;
    /** C {@code PKT_DEBRIS}: debris packets are 128 + colour * areas + area. */
    public static final int PKT_DEBRIS = 128;

    /** C {@code DEBRIS_TYPES} = 8 colours * 4 * 4 view areas. */
    public static final int DEBRIS_TYPES = 8 * 4 * 4;

    // ---------------------------------------------------------------- const.h / draw.h

    /** C {@code MAX_CHARS}: limit of a {@code %s} string including the NUL. */
    public static final int MAX_CHARS = 80;
    /** C {@code MSG_LEN}: limit of a {@code %S} string including the NUL. */
    public static final int MSG_LEN = 256;
    /** C {@code RES}: headings per full turn. */
    public static final int RES = 128;
    public static final int BLOCK_SZ = 35;
    /** C {@code FUEL_SCALE_BITS}. */
    public static final int FUEL_SCALE_BITS = 8;
    /** C {@code MAX_STATION_FUEL >> FUEL_SCALE_BITS}: a full fuel station as sent in PKT_FUEL. */
    public static final int MAX_STATION_FUEL_UNITS = 500;
    /** C {@code TARGET_DAMAGE}: an undamaged target (fixed point). */
    public static final int TARGET_DAMAGE = 250 << FUEL_SCALE_BITS;
    /** C {@code MAX_CHECKS}. */
    public static final int MAX_CHECKS = 26;
    /** C {@code NUM_ITEMS}. */
    public static final int NUM_ITEMS = 21;
    /** C {@code ITEM_TANK}. */
    public static final int ITEM_TANK = 7;
    /** C {@code EXPIRED_MINE_ID}. */
    public static final int EXPIRED_MINE_ID = 4096;
    /** C {@code MIN_VIEW_SIZE} / {@code MAX_VIEW_SIZE} / {@code DEF_VIEW_SIZE}. */
    public static final int MIN_VIEW_SIZE = 384;
    public static final int MAX_VIEW_SIZE = 1024;
    public static final int DEF_VIEW_SIZE = 768;
    /** C {@code MAX_SPARK_RAND}: always show sparks. */
    public static final int MAX_SPARK_RAND = 0x80;

    // colours (draw.h)
    public static final int BLACK = 0;
    public static final int WHITE = 1;
    public static final int BLUE = 2;
    public static final int RED = 3;

    // rules.h: Setup->mode bits
    public static final int MODE_LIMITED_LIVES = 1 << 3;
    public static final int MODE_TIMING = 1 << 4;
    public static final int MODE_TEAM_PLAY = 1 << 8;
    public static final int MODE_WRAP_PLAY = 1 << 9;

    // rules.h: player status low byte (PKT_SELF "stat")
    public static final int STATUS_PLAYING = 1;
    public static final int STATUS_PAUSE = 1 << 1;
    public static final int STATUS_GAME_OVER = 1 << 2;
    public static final int STATUS_THRUSTING = 1 << 3;
    public static final int STATUS_SELF_DESTRUCT = 1 << 4;

    // ---------------------------------------------------------------- setup.h

    public static final int SETUP_COMPRESSED = 0x80;
    public static final int SETUP_MAP_ORDER_XY = 1;
    public static final int SETUP_MAP_ORDER_YX = 2;
    public static final int SETUP_MAP_UNCOMPRESSED = 3;

    public static final int SETUP_SPACE = 0;
    public static final int SETUP_FILLED = 1;
    public static final int SETUP_FILLED_NO_DRAW = 2;
    public static final int SETUP_FUEL = 3;
    public static final int SETUP_REC_RU = 4;
    public static final int SETUP_REC_RD = 5;
    public static final int SETUP_REC_LU = 6;
    public static final int SETUP_REC_LD = 7;
    public static final int SETUP_ACWISE_GRAV = 8;
    public static final int SETUP_CWISE_GRAV = 9;
    public static final int SETUP_POS_GRAV = 10;
    public static final int SETUP_NEG_GRAV = 11;
    public static final int SETUP_WORM_NORMAL = 12;
    public static final int SETUP_WORM_IN = 13;
    public static final int SETUP_WORM_OUT = 14;
    public static final int SETUP_CANNON_UP = 15;
    public static final int SETUP_CANNON_RIGHT = 16;
    public static final int SETUP_CANNON_DOWN = 17;
    public static final int SETUP_CANNON_LEFT = 18;
    public static final int SETUP_SPACE_DOT = 19;
    public static final int SETUP_TREASURE = 20;
    public static final int SETUP_BASE_LOWEST = 30;
    public static final int SETUP_BASE_UP = 30;
    public static final int SETUP_BASE_RIGHT = 40;
    public static final int SETUP_BASE_DOWN = 50;
    public static final int SETUP_BASE_LEFT = 60;
    public static final int SETUP_BASE_HIGHEST = 69;
    public static final int SETUP_TARGET = 70;
    public static final int SETUP_CHECK = 80;
    public static final int SETUP_ITEM_CONCENTRATOR = 110;
    public static final int SETUP_DECOR_FILLED = 111;
    public static final int SETUP_DECOR_RU = 112;
    public static final int SETUP_DECOR_RD = 113;
    public static final int SETUP_DECOR_LU = 114;
    public static final int SETUP_DECOR_LD = 115;
    public static final int SETUP_DECOR_DOT_FILLED = 116;
    public static final int SETUP_DECOR_DOT_RU = 117;
    public static final int SETUP_DECOR_DOT_RD = 118;
    public static final int SETUP_DECOR_DOT_LU = 119;
    public static final int SETUP_DECOR_DOT_LD = 120;
    public static final int SETUP_UP_GRAV = 121;
    public static final int SETUP_DOWN_GRAV = 122;
    public static final int SETUP_RIGHT_GRAV = 123;
    public static final int SETUP_LEFT_GRAV = 124;
    public static final int SETUP_ASTEROID_CONCENTRATOR = 125;
}
