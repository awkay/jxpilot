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
package com.fulcrologic.jxpilot.common.net.link;

/**
 * The link emulator's random source: SplitMix64, with uniform, normal and Pareto draws. Seeded, so a run is
 * reproducible; uses only {@code StrictMath} log, exp and sqrt, so it is reproducible on every JVM. Allocation free.
 */
public final class LinkRandom {
    private long state;

    public LinkRandom(long seed) {
        this.state = seed;
    }

    /** BEHAVIOR: the SplitMix64 sequence of the seed (same seed, same sequence). */
    public long nextLong() {
        long z = state += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** BEHAVIOR: uniform in [0, 1), from the top 53 bits of one {@link #nextLong}. */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /**
     * BEHAVIOR: true with probability {@code p} (never for p &lt;= 0, always for p &gt;= 1); always consumes exactly
     * one draw, so enabling an impairment does not shift the draws that follow it.
     */
    public boolean chance(double p) {
        return nextDouble() < p;
    }

    /** BEHAVIOR: uniform in the open interval (0, 1) (the midpoint of a 53-bit cell), from one draw. */
    public double nextOpen() {
        return ((nextLong() >>> 11) + 0.5) * 0x1.0p-53;
    }

    /**
     * BEHAVIOR: a standard normal draw, {@link #inverseNormal} of one {@link #nextOpen} draw (so exactly one draw,
     * and no cached second value).
     */
    public double nextGaussian() {
        return inverseNormal(nextOpen());
    }

    /**
     * BEHAVIOR: a Pareto draw with scale {@code xm} and shape {@code alpha}: {@code xm * exp(-ln(u) / alpha)} for
     * one {@link #nextOpen} u, so never below {@code xm}; exactly one draw.
     */
    public double nextPareto(double xm, double alpha) {
        return xm * StrictMath.exp(-StrictMath.log(nextOpen()) / alpha);
    }

    private static final double[] A = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
        1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
    private static final double[] B = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
        6.680131188771972e+01, -1.328068155288572e+01};
    private static final double[] C = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
        -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
    private static final double[] D = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
        3.754408661907416e+00};
    private static final double P_LOW = 0.02425;

    /**
     * The standard normal quantile of {@code p} in (0, 1), by Acklam's rational approximation (relative error below
     * 1.2e-9), using only {@code StrictMath.log} and {@code sqrt}: those are reproducible on every JVM and, unlike
     * {@code StrictMath.cos} and {@code pow}, do not allocate.
     * <p>
     * BEHAVIOR: the central region |p - 0.5| &lt;= 0.5 - 0.02425 uses the central rational function, each tail the
     * tail function (the upper tail mirrored), so the result is odd about p = 0.5.
     */
    static double inverseNormal(double p) {
        if (p < P_LOW) {
            return tail(p);
        }
        if (p > 1 - P_LOW) {
            return -tail(1 - p);
        }
        double q = p - 0.5;
        double r = q * q;
        return (((((A[0] * r + A[1]) * r + A[2]) * r + A[3]) * r + A[4]) * r + A[5]) * q
            / (((((B[0] * r + B[1]) * r + B[2]) * r + B[3]) * r + B[4]) * r + 1);
    }

    private static double tail(double p) {
        double q = StrictMath.sqrt(-2 * StrictMath.log(p));
        return (((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5])
            / ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1);
    }
}
