package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;

/**
 * A home base ({@code _} or team digit).
 *
 * @param index     index in {@link WorldMap#bases()} (C order: x-major, y ascending)
 * @param bx        block x
 * @param by        block y (0 = bottom row)
 * @param dir       heading the ship faces when placed, RES units ({@link Dir})
 * @param team      owning team in team play, else {@link Const#TEAM_NOT_SET}
 * @param teamDigit the digit written in the map (0-9), or -1 for {@code _}
 */
public record Base(int index, int bx, int by, int dir, int team, int teamDigit) {
    /** Block centre x in pixels. */
    public double x() { return (bx + 0.5) * Const.BLOCK_SZ; }

    /** Block centre y in pixels. */
    public double y() { return (by + 0.5) * Const.BLOCK_SZ; }

    /** Heading in radians. */
    public double headingRadians() { return Dir.toRadians(dir); }
}
