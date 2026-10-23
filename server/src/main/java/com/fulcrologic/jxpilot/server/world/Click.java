package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.Const;

/** Sub-pixel fixed point positions ({@code src/server/click.h}): 64 clicks per pixel. */
public final class Click {
    private Click() {}

    public static final int SHIFT = 6;
    public static final int CLICK = 1 << SHIFT;
    public static final int BLOCK_CLICKS = Const.BLOCK_SZ << SHIFT;

    /** C {@code FLOAT_TO_CLICK}: truncates toward zero. */
    public static int fromPixels(double px) {
        return (int) (px * CLICK);
    }

    /** C {@code CLICK_TO_PIXEL}: arithmetic shift (floor). */
    public static int toPixel(int c) {
        return c >> SHIFT;
    }

    /** C {@code CLICK_TO_FLOAT}. */
    public static double toFloat(int c) {
        return c * (1.0 / CLICK);
    }

    /** C {@code DOUBLE_TO_INT}: rounds half away from zero. */
    public static int roundHalfAway(double d) {
        return d < 0 ? -(int) (0.5 - d) : (int) (d + 0.5);
    }
}
