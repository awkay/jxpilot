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

import com.fulcrologic.jxpilot.client.net.ClientConnection;
import com.fulcrologic.jxpilot.client.net.PredictedClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The input lead controller ({@link PredictedClock}, docs/latency-fixes.md §4.3) against the real server and client
 * over the {@link com.fulcrologic.jxpilot.common.net.link.LinkEmulator} in virtual time: RTT steps 100 → 300 → 100 ms,
 * and the loss/jitter matrix with the server's starvation and late counters.
 */
@DisplayName("Input lead control over the link emulator")
class LeadControlTest {
    static final long SEC = LatencyRig.SEC;
    static final long MS = 1_000_000L;

    /** One run: per server tick, the client's lead and last slack report and the server's counters. */
    static final class Run {
        final LatencyRig rig;
        final int playerId;
        final List<long[]> times = new ArrayList<>();
        final List<double[]> lead = new ArrayList<>();
        long spawn;

        /** Ticks where the server applied other keys than the client's final entry (starved ticks aside). */
        int misapplied;
        int checked;
        private long starvedBefore;

        Run(Scenario s, long seed) {
            rig = new LatencyRig(Course.FREE_FLIGHT.serverArgs(0), s.direction(), s.direction(), seed,
                InputToPhoton.tickPhase(seed));
            spawn = rig.runUntilPlaying(LatencyRig.START + 30 * SEC);
            if (spawn < 0) {
                throw new IllegalStateException("never spawned");
            }
            playerId = rig.client.connection().playerId();
            rig.onTick = (r, t) -> {
                ClientConnection c = r.client.connection();
                PredictedClock k = c.predictedClock();
                times.add(new long[] {t, r.server.net.inputStarvations(playerId), r.server.net.inputLate(playerId),
                    k.jumps()});
                lead.add(new double[] {k.leadTicks(t), k.lastSlack(), k.targetSlack(c.netDiagnostics().jitterMs() * 1e6), k.slackEstimate()});
                int tick = r.server.net.currentTick();
                long starved = r.server.net.inputStarvations(playerId);
                com.fulcrologic.jxpilot.server.world.Player p = r.server.world().byId(playerId);
                com.fulcrologic.jxpilot.common.net.InputHistory h = c.input();
                if (p != null && starved == starvedBefore && h.has(tick)) {
                    checked++;
                    if (p.lastReceivedKeys != h.keys(tick)) {
                        misapplied++;
                    }
                }
                starvedBefore = starved;
            };
        }

        void until(long t) {
            rig.runUntil(t);
        }

        void rtt(int ms) {
            rig.link.up().profile().delayMs(ms / 2.0);
            rig.link.down().profile().delayMs(ms / 2.0);
        }

        /** Server starvations in (from, to]. */
        long starvations(long from, long to) {
            return counter(1, from, to);
        }

        long late(long from, long to) {
            return counter(2, from, to);
        }

        long jumps(long from, long to) {
            return counter(3, from, to);
        }

        private long counter(int k, long from, long to) {
            long a = -1;
            long b = 0;
            for (long[] row : times) {
                if (row[0] <= from) {
                    a = row[k];
                }
                if (row[0] <= to) {
                    b = row[k];
                }
            }
            return b - Math.max(0, a);
        }

        /** The time of the last starvation in (from, to], or {@code from} when none. */
        long lastStarvation(long from, long to) {
            long last = from;
            long prev = -1;
            for (long[] row : times) {
                if (row[0] > from && row[0] <= to && prev >= 0 && row[1] > prev) {
                    last = row[0];
                }
                prev = row[1];
            }
            return last;
        }

        /**
         * The first time after {@code from} from which, up to {@code to}, the client's slack estimate (the report
         * corrected for the clock's own moves since, {@link PredictedClock#slackEstimate}) stays within
         * [target - lo, target + hi]; -1 if it never does.
         */
        long settledFrom(long from, long to, double lo, double hi) {
            long since = -1;
            for (int i = 0; i < times.size(); i++) {
                long t = times.get(i)[0];
                if (t <= from || t > to) {
                    continue;
                }
                double slack = lead.get(i)[3];
                double target = lead.get(i)[2];
                boolean in = slack >= target - lo && slack <= target + hi;
                if (in && since < 0) {
                    since = t;
                } else if (!in) {
                    since = -1;
                }
            }
            return since;
        }

        /** |report - estimate| at the last tick up to {@code t}: 0 once the link has been steady for a second. */
        double estimateError(long t) {
            double d = Double.NaN;
            for (int i = 0; i < times.size() && times.get(i)[0] <= t; i++) {
                d = Math.abs(lead.get(i)[1] - lead.get(i)[3]);
            }
            return d;
        }

        double leadAt(long t) {
            double l = Double.NaN;
            for (int i = 0; i < times.size() && times.get(i)[0] <= t; i++) {
                l = lead.get(i)[0];
            }
            return l;
        }
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @DisplayName("RTT 100 -> 300 -> 100 ms: up converges in under 2 s with an immediate jump; no starvation once converged;"
        + " down drains at 3%")
    void rttSteps() {
        Run r = new Run(new Scenario(100, Scenario.Jitter.NONE, Scenario.Loss.NONE), 11);
        long s0 = r.spawn;
        long up = s0 + 6 * SEC;
        long down = up + 8 * SEC;
        long end = down + 12 * SEC;
        r.until(up);
        double leadBefore = r.leadAt(up);
        r.rtt(300);
        r.until(down);
        double leadUp = r.leadAt(down);
        r.rtt(100);
        r.until(end);
        double band = PredictedClock.DEADBAND + 1 / 16.0;
        long upConverged = Math.max(r.lastStarvation(up, down), r.settledFrom(up, down, band, 10)) - up;
        long firstJump = -1;
        for (long[] row : r.times) {
            if (row[0] > up && firstJump < 0 && r.jumps(up, row[0]) > 0) {
                firstJump = row[0] - up;
            }
        }
        long downSettled = r.settledFrom(down, end, band, band);
        long starvedSettled = r.starvations(s0 + 3 * SEC, up);
        double errBefore = r.estimateError(up);
        double errUp = r.estimateError(down);
        double errEnd = r.estimateError(end);
        long starvedAfterUp = r.starvations(up + upConverged, down);
        long starvedAfterDown = r.starvations(down, end);
        long fj = firstJump;
        System.out.printf(Locale.ROOT, "[lead steps] lead %.2f -> %.2f ticks; up step: first jump %.0f ms, converged"
                + " %.0f ms, %d jumps, %d starvations; down step: in the band after %.0f ms (lead %.2f)%n",
            leadBefore, leadUp, fj / 1e6, upConverged / 1e6, r.jumps(up, down), r.starvations(up, down),
            downSettled < 0 ? Double.NaN : (downSettled - down) / 1e6, r.leadAt(end));
        assertAll(
            () -> assertEquals(0, starvedSettled, "no starvation at a steady 100 ms once settled"),
            () -> assertEquals(0, errBefore + errUp + errEnd, 1 / 16.0 + 1e-9,
                "on a steady link the estimate is the server's report (within its 1/16 tick resolution)"),
            () -> assertTrue(fj >= 0 && fj < 600 * MS, "the step up starves and the clock jumps within ~RTT: " + fj / 1e6),
            () -> assertTrue(upConverged < 2 * SEC, "the step up converges in under 2 s: " + upConverged / 1e6 + " ms"),
            () -> assertEquals(0, starvedAfterUp, "no starvation after converging at 300 ms"),
            () -> assertEquals(0, starvedAfterDown, "the step down never starves (the lead only drifts down)"),
            () -> assertTrue(downSettled > 0 && downSettled - down < 8 * SEC,
                "the step down drains 1.4 ticks of slack at 3%, in the band within 8 s: " + (downSettled - down) / 1e6));
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @DisplayName("reordered datagrams: taps within ticks (early copies that differ from the final), every tick applies"
        + " the client's final entry without loss (reported with 1% loss)")
    void reorderedInputs() {
        StringBuilder out = new StringBuilder("[lead reorder] cell: misapplied/checked ticks, starved\n");
        List<String> bad = new ArrayList<>();
        for (int rtt : new int[] {100, 300}) {
            for (Scenario.Loss l : new Scenario.Loss[] {Scenario.Loss.NONE, Scenario.Loss.UNIFORM_1}) {
                Scenario s = new Scenario(rtt, Scenario.Jitter.REORDER, l);
                Run r = new Run(s, 41 + rtt);
                long spawn = r.spawn;
                // a 12 ms tap every 97 ms drifts through the tick: some land at its end, where the early copy (key
                // down) and the final send (key up, the tap) differ and a held-back early copy arrives last
                long tap = com.fulcrologic.jxpilot.common.net.ControlKey.TURN_LEFT.mask();
                r.rig.setKeys(t -> (t - spawn) % (97 * MS) < 12 * MS ? tap : 0L);
                r.until(spawn + 20 * SEC);
                long starved = r.starvations(spawn + 6 * SEC, spawn + 20 * SEC);
                out.append(String.format(Locale.ROOT, "  %-12s %d/%d, %d (reordered %d, sent %d)%n", s.label(), r.misapplied,
                    r.checked, starved, r.rig.link.up().reordered(), r.rig.client.connection().packetsSent()));
                // with loss a final send can be lost while its early copy got through: the server then applies the
                // early copy, legitimately (the client learns it from PREDICT's applied-input check)
                if ((l == Scenario.Loss.NONE && r.misapplied != 0) || r.checked < 200) {
                    bad.add(s.label() + " misapplied " + r.misapplied + " of " + r.checked);
                }
            }
        }
        System.out.print(out);
        assertEquals(List.of(), bad, "with reordering and no loss every applied entry is the client's final one");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @DisplayName("the loss/jitter matrix: starvation and late counters after convergence (0 without loss at jitter <= 30 ms)")
    void matrix() {
        int[] rtts = {0, 100, 200, 300};
        StringBuilder table = new StringBuilder(
            "[lead matrix] cell: starved/late in the 20 s after a 6 s settle (lead ticks at the end)\n");
        List<String> failures = new ArrayList<>();
        for (int rtt : rtts) {
            for (Scenario.Jitter j : Scenario.Jitter.values()) {
                for (Scenario.Loss l : Scenario.Loss.values()) {
                    Scenario s = new Scenario(rtt, j, l);
                    Run r = new Run(s, 5 + rtt);
                    long from = r.spawn + 6 * SEC;
                    long to = from + 20 * SEC;
                    r.until(to);
                    long st = r.starvations(from, to);
                    long late = r.late(from, to);
                    table.append(String.format(Locale.ROOT, "  %-12s %3d/%-3d (lead %.2f)%n", s.label(), st, late,
                        r.leadAt(to)));
                    if (j != Scenario.Jitter.PARETO && l == Scenario.Loss.NONE && st != 0) {
                        failures.add(s.label() + " starved " + st);
                    }
                }
            }
        }
        System.out.print(table);
        assertEquals(List.of(), failures, "no starvation after convergence without loss at jitter <= 30 ms");
    }
}
