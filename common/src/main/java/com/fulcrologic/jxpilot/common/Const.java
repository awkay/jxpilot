package com.fulcrologic.jxpilot.common;

/**
 * Game-wide constants mirrored from XPilot 4.5.5 ({@code src/common/const.h},
 * {@code src/server/serverconst.h}). Units are pixels and original XPilot frames
 * unless stated otherwise.
 */
public final class Const {
    private Const() {}

    /** Size of one map block in pixels. */
    public static final int BLOCK_SZ = 35;
    /** XPilot heading resolution (headings per full turn). */
    public static final int RES = 128;
    /** Maximum map width/height in blocks ({@code MAX_MAP_SIZE}). */
    public static final int MAX_MAP_SIZE = 900;
    /** Number of teams (team digits 0-9). */
    public static final int MAX_TEAMS = 10;
    /** Number of checkpoints (letters A-Z). */
    public static final int MAX_CHECKS = 26;
    /** Team value used when no team is assigned (C uses 0xffff). */
    public static final int TEAM_NOT_SET = -1;

    /** Strength of a map gravity block ({@code GRAVS_POWER}). */
    public static final double GRAVS_POWER = 2.7;
    /** Range in blocks of a map gravity block ({@code GRAV_RANGE}). */
    public static final int GRAV_RANGE = 10;

    /** Fuel values in C are fixed point with this many fraction bits. We use plain floats. */
    public static final int FUEL_SCALE_BITS = 8;
    /** Maximum fuel in a fuel station (unscaled). */
    public static final double MAX_STATION_FUEL = 500.0;
    /** Initial fuel in a fuel station (unscaled). */
    public static final double START_STATION_FUEL = 20.0;
    /** Fuel station regeneration per frame (unscaled). */
    public static final double STATION_REGENERATION = 0.06;
    /** Max player fuel (unscaled). */
    public static final double MAX_PLAYER_FUEL = 2600.0;
    /** Min player fuel (unscaled). */
    public static final double MIN_PLAYER_FUEL = 350.0;
    /** Refuel rate per frame (unscaled). */
    public static final double REFUEL_RATE = 5.0;
    /** Damage a target can take (unscaled fuel units). */
    public static final double TARGET_DAMAGE = 250.0;

    /** Ship radius used for hit detection. */
    public static final int SHIP_SZ = 16;
    public static final int BALL_RADIUS = 10;
    public static final int MISSILE_LEN = 15;
    public static final double SPEED_LIMIT = 65.0;
    public static final double VISIBILITY_DISTANCE = 1000.0;

    public static final double MAX_PLAYER_TURNSPEED = 64.0;
    public static final double MIN_PLAYER_TURNSPEED = 4.0;
    public static final double MAX_PLAYER_POWER = 55.0;
    public static final double MIN_PLAYER_POWER = 5.0;
    public static final double MAX_PLAYER_TURNRESISTANCE = 1.0;
    public static final double MIN_PLAYER_TURNRESISTANCE = 0.0;

    /** Max chars in names ({@code MAX_CHARS}). */
    public static final int MAX_CHARS = 80;
    /** Max message length ({@code MSG_LEN}). */
    public static final int MSG_LEN = 256;
    /** Max player name length on the wire. */
    public static final int MAX_NAME_LEN = 16;
}
