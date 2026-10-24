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

import java.util.List;
import java.util.Locale;

/**
 * The baseline table (docs/latency-measurements.md): one row per course and link scenario, Markdown. Values are
 * milliseconds for latencies, pixels for errors and smoothness. A metric that cannot be measured yet prints its
 * reason ({@link #NA_PREDICTOR}); a sample without values prints "-". Pure.
 */
final class MetricTable {
    /** What the prediction metrics print until the phase 1c predictor exists. */
    static final String NA_PREDICTOR = "n/a (1c)";

    /** One measured cell. */
    record Row(Course course, Scenario scenario, InputToPhoton.Result i2p, MotionMetrics.Result motion) { }

    static final String HEADER =
        "| course | RTT/jitter/loss | I2P p50 | I2P p95 | p50 - RTT | mean - RTT | turn p50 | thrust p50 | thrust >1px p50 | trials"
            + " | own err p50/p95 | aim err p50/p95 (frames) | smooth own p95/max | smooth others p95"
            + " | snaps/min (teleports) | HUD rtt/jitter/loss | correction p95 | exact % |";
    static final String RULE = "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|";

    private MetricTable() {
    }

    /** BEHAVIOR: the header, the rule, then one line per row in order; every line ends with a newline. */
    static String format(List<Row> rows) {
        StringBuilder sb = new StringBuilder(256 * (rows.size() + 2));
        sb.append(HEADER).append('\n').append(RULE).append('\n');
        for (Row r : rows) {
            sb.append(line(r)).append('\n');
        }
        return sb.toString();
    }

    /** BEHAVIOR: one table line; prediction metrics without data print {@link #NA_PREDICTOR}. */
    static String line(Row r) {
        Stats all = r.i2p().stats(null);
        Stats turn = r.i2p().stats(com.fulcrologic.jxpilot.common.net.ControlKey.TURN_LEFT);
        Stats thrust = r.i2p().stats(com.fulcrologic.jxpilot.common.net.ControlKey.THRUST);
        MotionMetrics.Result m = r.motion();
        return "| " + r.course().name().toLowerCase(Locale.ROOT).replace('_', ' ')
            + " | " + r.scenario().label()
            + " | " + num(all.p50(), 0)
            + " | " + num(all.p95(), 0)
            + " | " + num(all.p50() - r.scenario().rttMs(), 0)
            + " | " + num(r.i2p().mean() - r.scenario().rttMs(), 1)
            + " | " + num(turn.p50(), 0)
            + " | " + num(thrust.p50(), 0)
            + " | " + num(r.i2p().positionStats().p50(), 0)
            + " | " + all.count() + "/" + r.i2p().trials().size()
            + " | " + num(m.ownError().p50(), 1) + "/" + num(m.ownError().p95(), 1)
            + " | " + num(m.aimError().p50(), 1) + "/" + num(m.aimError().p95(), 1) + " (" + m.otherFrames() + ")"
            + " | " + num(m.ownSmooth().p95(), 2) + "/" + num(m.ownSmooth().max(), 1)
            + " | " + num(m.otherSmooth().p95(), 2)
            + " | " + num(m.snapsPerMinute(), 1) + (m.teleports() == 0 ? "" : " (" + m.teleports() + " tp)")
            + " | " + num(m.hudRttMs(), 0) + "/" + num(m.hudJitterMs(), 1) + "/" + num(m.hudLossPercent(), 1)
            + " | " + (m.correction().count() == 0 ? NA_PREDICTOR : num(m.correction().p95(), 1))
            + " | " + (Double.isNaN(m.exactPercent()) ? NA_PREDICTOR : num(m.exactPercent(), 1))
            + " |";
    }

    /** BEHAVIOR: {@code v} with {@code decimals} decimals (ROOT locale; ties round half up, as Formatter does); "-" for NaN. */
    static String num(double v, int decimals) {
        if (Double.isNaN(v)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }
}
