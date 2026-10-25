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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Press-to-apply: from a key going down to the server tick that applies it ({@link InputToPhoton.Trial#tickMs}),
 * the part of a shield's reaction time no client prediction can hide (the shield is the server's verdict). Printed
 * as {@code PRESS-TO-APPLY}, mean / p50 / p95 per RTT and jitter, with the lead the client ran at.
 * <p>
 * BEHAVIOR: measured with the I2P trials (stratified over the tick phase) on the free-flight course; the latency
 * minus RTT/2 is the key-poll wait (0-8.3 ms) plus the input's wait in the server's buffer (the lead's slack).
 */
@DisplayName("press-to-apply latency (shield reaction)")
class ShieldReactionTest {
    static final int TRIALS = Integer.getInteger("jxpilot.latency.trials", 48);

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @DisplayName("every cell measures its trials; the apply latency is printed")
    void pressToApply() {
        List<String> lines = new ArrayList<>();
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (Scenario.Jitter j : new Scenario.Jitter[] {Scenario.Jitter.NONE, Scenario.Jitter.NORMAL_15}) {
            for (int rtt : new int[] {0, 100, 300}) {
                Scenario s = new Scenario(rtt, j, Scenario.Loss.NONE);
                InputToPhoton.Result r = InputToPhoton.measure(Course.FREE_FLIGHT, s, TRIALS, 1);
                double[] v = new double[r.trials().size()];
                int n = 0;
                for (InputToPhoton.Trial t : r.trials()) {
                    if (t.outcome() == InputToPhoton.Outcome.MEASURED) {
                        v[n++] = t.tickMs();
                    }
                }
                Stats st = new Stats(v, n);
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += v[i];
                }
                double mean = sum / Math.max(1, st.count());
                String line = String.format(Locale.ROOT,
                    "PRESS-TO-APPLY rtt %3d jitter %-4s: mean %6.1f  p50 %6.1f  p95 %6.1f  (minus RTT/2: mean %5.1f  p95 %5.1f)"
                        + "  n=%d", rtt, j.label, mean, st.p50(), st.p95(), mean - rtt / 2.0, st.p95() - rtt / 2.0,
                    st.count());
                lines.add(line);
                int count = st.count();
                checks.add(() -> assertTrue(count >= TRIALS * 3 / 4, s.label() + ": trials measured (" + count + ")"));
            }
        }
        lines.forEach(System.out::println);
        assertAll(checks);
    }
}
