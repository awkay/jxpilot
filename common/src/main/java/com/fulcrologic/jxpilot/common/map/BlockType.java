package com.fulcrologic.jxpilot.common.map;

/**
 * Block type codes stored in {@link WorldMap}'s grid. Values equal the C constants in
 * {@code src/server/map.h}.
 * <p>
 * Diagonal walls are named after the solid corner (world y up, origin at the block's lower-left):
 * <pre>
 *   char  type     solid triangle                      solid when (ox,oy in [0,BLOCK_SZ])
 *   'a'   REC_RU   upper-right  (S,0) (S,S) (0,S)      ox + oy &gt; S
 *   's'   REC_LU   upper-left   (0,0) (S,S) (0,S)      ox &lt; oy
 *   'w'   REC_LD   lower-left   (0,0) (S,0) (0,S)      ox + oy &lt; S
 *   'q'   REC_RD   lower-right  (0,0) (S,0) (S,S)      ox &gt; oy
 * </pre>
 * Decorations {@code g h y t b} use the same geometry (RU, LU, LD, RD, filled) but are not solid.
 */
public final class BlockType {
    private BlockType() {}

    public static final byte SPACE = 0;
    public static final byte BASE = 1;
    public static final byte FILLED = 2;
    public static final byte REC_LU = 3;
    public static final byte REC_LD = 4;
    public static final byte REC_RU = 5;
    public static final byte REC_RD = 6;
    public static final byte FUEL = 7;
    public static final byte CANNON = 8;
    public static final byte CHECK = 9;
    public static final byte POS_GRAV = 10;
    public static final byte NEG_GRAV = 11;
    public static final byte CWISE_GRAV = 12;
    public static final byte ACWISE_GRAV = 13;
    public static final byte WORMHOLE = 14;
    public static final byte TREASURE = 15;
    public static final byte TARGET = 16;
    public static final byte ITEM_CONCENTRATOR = 17;
    public static final byte DECOR_FILLED = 18;
    public static final byte DECOR_LU = 19;
    public static final byte DECOR_LD = 20;
    public static final byte DECOR_RU = 21;
    public static final byte DECOR_RD = 22;
    public static final byte UP_GRAV = 23;
    public static final byte DOWN_GRAV = 24;
    public static final byte RIGHT_GRAV = 25;
    public static final byte LEFT_GRAV = 26;
    public static final byte FRICTION = 27;
    public static final byte ASTEROID_CONCENTRATOR = 28;
    /** Only present during loading ({@code $}); replaced by SPACE after base directions are found. */
    public static final byte BASE_ATTRACTOR = 127;

    /** Number of distinct block codes below BASE_ATTRACTOR. */
    public static final int COUNT = 29;

    /** Bit for {@code type} for use in masks. */
    public static int bit(int type) {
        return 1 << type;
    }

    /** Blocks that are wall-like for drawing (C client "blue" walls): FILLED, FUEL, REC_*. */
    public static boolean isWallOutline(int t) {
        return t == FILLED || t == FUEL || t == REC_LU || t == REC_LD || t == REC_RU || t == REC_RD;
    }

    /** Diagonal wall triangle. */
    public static boolean isDiagonal(int t) {
        return t == REC_LU || t == REC_LD || t == REC_RU || t == REC_RD;
    }

    /** Blocks with (some) solid area: walls, fuel, cannon, treasure, target. */
    public static boolean isSolid(int t) {
        return t == FILLED || isDiagonal(t) || t == FUEL || t == CANNON || t == TREASURE || t == TARGET;
    }

    /** Gravity blocks of any kind. */
    public static boolean isGravity(int t) {
        return t == POS_GRAV || t == NEG_GRAV || t == CWISE_GRAV || t == ACWISE_GRAV
            || t == UP_GRAV || t == DOWN_GRAV || t == RIGHT_GRAV || t == LEFT_GRAV;
    }

    /** Decoration blocks. */
    public static boolean isDecor(int t) {
        return t >= DECOR_FILLED && t <= DECOR_RD;
    }

    /**
     * Map a decoration type to its geometric wall equivalent (DECOR_LU to REC_LU etc.),
     * or return {@code t} unchanged.
     */
    public static int decorShape(int t) {
        return switch (t) {
            case DECOR_FILLED -> FILLED;
            case DECOR_LU -> REC_LU;
            case DECOR_LD -> REC_LD;
            case DECOR_RU -> REC_RU;
            case DECOR_RD -> REC_RD;
            default -> t;
        };
    }

    /** Human-readable name for debugging. */
    public static String name(int t) {
        return switch (t) {
            case SPACE -> "SPACE";
            case BASE -> "BASE";
            case FILLED -> "FILLED";
            case REC_LU -> "REC_LU";
            case REC_LD -> "REC_LD";
            case REC_RU -> "REC_RU";
            case REC_RD -> "REC_RD";
            case FUEL -> "FUEL";
            case CANNON -> "CANNON";
            case CHECK -> "CHECK";
            case POS_GRAV -> "POS_GRAV";
            case NEG_GRAV -> "NEG_GRAV";
            case CWISE_GRAV -> "CWISE_GRAV";
            case ACWISE_GRAV -> "ACWISE_GRAV";
            case WORMHOLE -> "WORMHOLE";
            case TREASURE -> "TREASURE";
            case TARGET -> "TARGET";
            case ITEM_CONCENTRATOR -> "ITEM_CONCENTRATOR";
            case DECOR_FILLED -> "DECOR_FILLED";
            case DECOR_LU -> "DECOR_LU";
            case DECOR_LD -> "DECOR_LD";
            case DECOR_RU -> "DECOR_RU";
            case DECOR_RD -> "DECOR_RD";
            case UP_GRAV -> "UP_GRAV";
            case DOWN_GRAV -> "DOWN_GRAV";
            case RIGHT_GRAV -> "RIGHT_GRAV";
            case LEFT_GRAV -> "LEFT_GRAV";
            case FRICTION -> "FRICTION";
            case ASTEROID_CONCENTRATOR -> "ASTEROID_CONCENTRATOR";
            case BASE_ATTRACTOR -> "BASE_ATTRACTOR";
            default -> "UNKNOWN(" + t + ")";
        };
    }
}
