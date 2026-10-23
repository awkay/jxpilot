package com.fulcrologic.jxpilot.server.world;

/**
 * Object type bits ({@code OBJ_*} in {@code src/server/object.h}) and status bits ({@code src/common/rules.h}).
 * Players and pool objects share the status bits, as in C.
 */
public final class Obj {
    private Obj() {}

    // ---- types (object.h)
    public static final int PLAYER = 1;
    public static final int DEBRIS = 1 << 1;
    public static final int SPARK = 1 << 2;
    public static final int BALL = 1 << 3;
    public static final int SHOT = 1 << 4;
    public static final int SMART_SHOT = 1 << 5;
    public static final int MINE = 1 << 6;
    public static final int TORPEDO = 1 << 7;
    public static final int HEAT_SHOT = 1 << 8;
    public static final int PULSE = 1 << 9;
    public static final int ITEM = 1 << 10;
    public static final int WRECKAGE = 1 << 11;
    public static final int ASTEROID = 1 << 12;
    public static final int CANNON_SHOT = 1 << 13;

    /** C {@code KILLING_SHOTS} with player killing allowed (rules.c). */
    public static final int KILLING_SHOTS = SHOT | CANNON_SHOT | SMART_SHOT | TORPEDO | HEAT_SHOT | PULSE;

    // ---- status bits (rules.h)
    public static final int PLAYING = 1;
    public static final int PAUSE = 1 << 1;
    public static final int GAME_OVER = 1 << 2;
    public static final int THRUSTING = 1 << 3;
    public static final int SELF_DESTRUCT = 1 << 4;
    public static final int KILLED = 1 << 10;
    public static final int GRAVITY = 1 << 11;
    public static final int WARPING = 1 << 12;
    public static final int WARPED = 1 << 13;
    public static final int CONFUSED = 1 << 14;
    public static final int FROMCANNON = 1 << 15;
    public static final int HOVERPAUSE = 1 << 16;
    public static final int RECREATE = 1 << 17;
    public static final int FROMBOUNCE = 1 << 18;
    public static final int OWNERIMMUNE = 1 << 19;
    public static final int COLLISIONSHOVE = 1 << 22;

    /** C {@code KILL_BITS} (rules.c): cleared on death. */
    public static final int KILL_BITS = THRUSTING | PLAYING | KILLED | SELF_DESTRUCT | WARPING | WARPED;

    // ---- modifiers (only the warhead bits are used in M1)
    /** {@code mods.warhead & CLUSTER}. */
    public static final int MOD_CLUSTER = 1;

    /** "No player" owner id (C {@code NO_ID}). */
    public static final int NO_ID = -1;
}
