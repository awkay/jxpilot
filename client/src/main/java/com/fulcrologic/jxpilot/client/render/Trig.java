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
package com.fulcrologic.jxpilot.client.render;

import com.fulcrologic.jxpilot.common.Const;

/**
 * Precomputed cosine/sine tables for the renderer's per-frame shapes: angles in steps of
 * 1/{@link #DEG_STEPS} degree (arcs and circles), and XPilot's {@code RES} headings.
 */
public final class Trig {
    private Trig() {}

    /** Table steps per degree. */
    public static final int DEG_STEPS = 8;
    static final int DEG_N = 360 * DEG_STEPS;
    private static final double[] COS_DEG = new double[DEG_N];
    private static final double[] SIN_DEG = new double[DEG_N];
    private static final double[] COS_RES = new double[Const.RES];
    private static final double[] SIN_RES = new double[Const.RES];

    static {
        for (int i = 0; i < DEG_N; i++) {
            double a = i * 2 * Math.PI / DEG_N;
            COS_DEG[i] = Math.cos(a);
            SIN_DEG[i] = Math.sin(a);
        }
        for (int i = 0; i < Const.RES; i++) {
            double a = i * 2 * Math.PI / Const.RES;
            COS_RES[i] = Math.cos(a);
            SIN_RES[i] = Math.sin(a);
        }
    }

    /**
     * Table index of an angle in degrees.
     * <p>
     * BEHAVIOR: rounds to the nearest 1/{@link #DEG_STEPS} degree and wraps any angle (negative or
     * past 360) into the table.
     */
    static int degIndex(double deg) {
        int i = (int) (Math.round(deg * DEG_STEPS) % DEG_N);
        return i < 0 ? i + DEG_N : i;
    }

    /** Cosine of {@code deg} degrees, to the nearest table step. */
    public static double cosDeg(double deg) { return COS_DEG[degIndex(deg)]; }

    /** Sine of {@code deg} degrees, to the nearest table step. */
    public static double sinDeg(double deg) { return SIN_DEG[degIndex(deg)]; }

    /** Cosine of a RES heading (any int, reduced modulo {@code RES}). */
    public static double cosRes(int heading) { return COS_RES[heading & (Const.RES - 1)]; }

    /** Sine of a RES heading (any int, reduced modulo {@code RES}). */
    public static double sinRes(int heading) { return SIN_RES[heading & (Const.RES - 1)]; }
}
