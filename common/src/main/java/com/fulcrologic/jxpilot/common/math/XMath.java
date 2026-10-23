package com.fulcrologic.jxpilot.common.math;

import com.fulcrologic.jxpilot.common.Const;

/**
 * Allocation-free math helpers: wrap-aware positions/deltas (C {@code WRAP_*} macros),
 * angle conversions and small utilities. All methods are static and pure.
 */
public final class XMath {
    private XMath() {}

    public static final double TWO_PI = 2.0 * Math.PI;

    /**
     * Wrap a coordinate into {@code [0, size)} (toroidal world).
     * <p>
     * BEHAVIOR: values already inside are returned unchanged.
     * BEHAVIOR: any multiple of size away wraps into range; the result is never equal to size.
     */
    public static double wrap(double v, double size) {
        if (v >= 0 && v < size) {
            return v;
        }
        v %= size;
        if (v < 0) {
            v += size;
        }
        return v >= size ? 0.0 : v;
    }

    /** Wrap a float coordinate into {@code [0, size)}. */
    public static float wrap(float v, float size) {
        return (float) wrap((double) v, size);
    }

    /** Wrap a block index into {@code [0, n)}. */
    public static int wrapBlock(int v, int n) {
        return Math.floorMod(v, n);
    }

    /**
     * Shortest signed difference for a wrapping axis of length {@code size}, as C {@code WRAP_DX}.
     * <p>
     * BEHAVIOR: without wrap returns d unchanged.
     * BEHAVIOR: with wrap, d &gt; floor(size/2) loses one size and d &lt; -floor(size/2) gains one; exactly half stays.
     */
    public static double wrapDelta(double d, double size, boolean wrap) {
        if (!wrap) {
            return d;
        }
        double half = Math.floor(size / 2);
        if (d < -half) {
            return d + size;
        }
        if (d > half) {
            return d - size;
        }
        return d;
    }

    /** Wrap-aware distance between (x1,y1) and (x2,y2) in a world of w x h pixels. */
    public static double wrapDistance(double x1, double y1, double x2, double y2, double w, double h, boolean wrap) {
        double dx = wrapDelta(x2 - x1, w, wrap);
        double dy = wrapDelta(y2 - y1, h, wrap);
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Wrap-aware squared distance. */
    public static double wrapDistanceSq(double x1, double y1, double x2, double y2, double w, double h, boolean wrap) {
        double dx = wrapDelta(x2 - x1, w, wrap);
        double dy = wrapDelta(y2 - y1, h, wrap);
        return dx * dx + dy * dy;
    }

    /** BEHAVIOR: returns the equivalent angle in {@code [0, 2pi)}. */
    public static double normalizeAngle(double a) {
        a %= TWO_PI;
        return a < 0 ? a + TWO_PI : a;
    }

    /** BEHAVIOR: returns the signed smallest difference {@code b - a} in {@code (-pi, pi]}. */
    public static double angleDiff(double a, double b) {
        double d = (b - a) % TWO_PI;
        if (d > Math.PI) {
            d -= TWO_PI;
        } else if (d <= -Math.PI) {
            d += TWO_PI;
        }
        return d;
    }

    /** XPilot RES heading to radians. */
    public static double resToRadians(double res) {
        return res * (TWO_PI / Const.RES);
    }

    /** Radians to XPilot RES heading units (not wrapped). */
    public static double radiansToRes(double rad) {
        return rad * (Const.RES / TWO_PI);
    }

    /** C {@code findDir}. BEHAVIOR: direction of (x,y) in RES units in {@code [0, RES)}; (0,0) gives 0. */
    public static double findDir(double x, double y) {
        double angle = (x != 0.0 || y != 0.0) ? Math.atan2(y, x) / TWO_PI : 0.0;
        if (angle < 0) {
            angle++;
        }
        return angle * Const.RES;
    }

    /** Clamp to [lo, hi]. */
    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Clamp to [lo, hi]. */
    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Block coordinate of a pixel coordinate. */
    public static int toBlock(double px) {
        return (int) Math.floor(px / Const.BLOCK_SZ);
    }

    /** Pixel centre of a block coordinate. */
    public static double blockCenter(int b) {
        return (b + 0.5) * Const.BLOCK_SZ;
    }

    /** C {@code LENGTH}: hypot. */
    public static double length(double x, double y) {
        return Math.sqrt(x * x + y * y);
    }
}
