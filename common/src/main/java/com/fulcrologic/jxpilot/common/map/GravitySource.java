package com.fulcrologic.jxpilot.common.map;

/**
 * A local gravity block. {@code type} is one of the {@link BlockType} *_GRAV codes:
 * {@code +} POS_GRAV (repels, force -2.7), {@code -} NEG_GRAV (attracts, +2.7),
 * {@code >} CWISE_GRAV (+2.7), {@code <} ACWISE_GRAV (-2.7),
 * {@code i} UP_GRAV (+2.7), {@code m} DOWN_GRAV (-2.7), {@code k} RIGHT_GRAV (+2.7), {@code j} LEFT_GRAV (-2.7).
 * Its effect is baked into {@link WorldMap#gravityX(int, int)}/{@link WorldMap#gravityY(int, int)}.
 */
public record GravitySource(int index, int bx, int by, byte type, double force) {
}
