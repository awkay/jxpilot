package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;

/** XPilot headings in RES (=128) units: 0 = right, 32 = up, 64 = left, 96 = down (y up). */
public final class Dir {
    private Dir() {}

    public static final int RIGHT = 0;
    public static final int UP = Const.RES / 4;
    public static final int LEFT = Const.RES / 2;
    public static final int DOWN = 3 * Const.RES / 4;

    /** Convert a RES heading to radians (counter-clockwise from +x, y up). */
    public static double toRadians(int resHeading) {
        return resHeading * (2.0 * Math.PI / Const.RES);
    }
}
