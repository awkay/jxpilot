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
package com.fulcrologic.jxpilot.client.latency;

import java.util.Arrays;

/** Order statistics of a sample, for the metric tables. Pure. */
final class Stats {
    private final double[] sorted;

    /** BEHAVIOR: NaN values are left out; the rest are kept sorted (the input is not changed). */
    Stats(double[] values, int count) {
        double[] v = new double[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (!Double.isNaN(values[i])) {
                v[n++] = values[i];
            }
        }
        sorted = Arrays.copyOf(v, n);
        Arrays.sort(sorted);
    }

    static Stats of(double... values) {
        return new Stats(values, values.length);
    }

    int count() {
        return sorted.length;
    }

    /**
     * BEHAVIOR: the nearest-rank percentile: the smallest value with at least {@code p}% of the sample at or below
     * it (p 0 is the minimum, 100 the maximum); NaN for an empty sample.
     */
    double percentile(double p) {
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    double p50() {
        return percentile(50);
    }

    double p95() {
        return percentile(95);
    }

    /** BEHAVIOR: the largest value, NaN for an empty sample. */
    double max() {
        return sorted.length == 0 ? Double.NaN : sorted[sorted.length - 1];
    }
}
