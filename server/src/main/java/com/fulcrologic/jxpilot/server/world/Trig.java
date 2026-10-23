package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.Const;

/** C {@code tcos}/{@code tsin} tables over RES headings, computed with StrictMath so every JVM agrees. */
public final class Trig {
    private Trig() {}

    private static final double[] SIN = new double[Const.RES];

    static {
        for (int i = 0; i < Const.RES; i++) {
            SIN[i] = StrictMath.sin(i * 2.0 * Math.PI / Const.RES);
        }
    }

    /** BEHAVIOR: sine of a RES heading; any int is taken modulo RES (C {@code MOD2}). */
    public static double tsin(int dir) {
        return SIN[dir & (Const.RES - 1)];
    }

    /** BEHAVIOR: cosine of a RES heading, {@code tsin(dir + RES/4)}. */
    public static double tcos(int dir) {
        return SIN[(dir + Const.RES / 4) & (Const.RES - 1)];
    }

    /** C {@code MOD2(x, RES)}. */
    public static int mod(int dir) {
        return dir & (Const.RES - 1);
    }
}
