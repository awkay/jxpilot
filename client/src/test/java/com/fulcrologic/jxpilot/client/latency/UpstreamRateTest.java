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

import com.fulcrologic.jxpilot.common.net.ControlKey;
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
 * Upstream bandwidth (client to server: INPUT plus PING, ACK and reliable traffic) over the link emulator, printed
 * as {@code UPSTREAM}: kbit/s of UDP payload and with 28 bytes of IPv4 + UDP header per datagram, and datagrams per
 * second, for steady flying (keys change every few hundred ms) and key mashing (a key toggled every 2nd render
 * frame).
 * <p>
 * BEHAVIOR: 20 s after a 4 s settle per cell; the bounds are the INPUT budget (perf review: ~18 kbit/s steady, at
 * most one early copy per tick when mashing).
 */
@DisplayName("upstream rate")
class UpstreamRateTest {
    static final long SEC = LatencyRig.SEC;
    static final long MS = 1_000_000L;

    /** Steady: thrust and turns held for 300-700 ms at a time. */
    static long steady(long t) {
        long phase = (t / (450 * MS)) % 5;
        return switch ((int) phase) {
            case 0 -> ControlKey.THRUST.mask();
            case 1 -> ControlKey.THRUST.mask() | ControlKey.TURN_LEFT.mask();
            case 2 -> 0;
            case 3 -> ControlKey.TURN_RIGHT.mask();
            default -> ControlKey.THRUST.mask() | ControlKey.FIRE_SHOT.mask();
        };
    }

    /** Mashing: fire toggled every 2nd 120 Hz frame, turns every 5th, over held thrust. */
    static long mashing(long t) {
        long f = t / (1_000_000_000L / LatencyRig.FRAME_HZ);
        return ControlKey.THRUST.mask() | ((f / 2) % 2 == 0 ? ControlKey.FIRE_SHOT.mask() : 0)
            | ((f / 5) % 2 == 0 ? ControlKey.TURN_LEFT.mask() : ControlKey.TURN_RIGHT.mask());
    }

    /** kbit/s payload, kbit/s with headers, datagrams/s. */
    static double[] run(Scenario s, java.util.function.LongUnaryOperator keys) {
        LatencyRig rig = new LatencyRig(Course.FREE_FLIGHT.serverArgs(0), s.direction(), s.direction(), 3,
            InputToPhoton.tickPhase(3));
        long spawn = rig.runUntilPlaying(LatencyRig.START + 30 * SEC);
        if (spawn < 0) {
            throw new IllegalStateException("never spawned");
        }
        rig.setKeys(keys::applyAsLong);
        rig.runUntil(spawn + 4 * SEC);
        long b0 = rig.link.up().sentBytes();
        long n0 = rig.link.up().sent();
        rig.runUntil(spawn + 24 * SEC);
        double bytes = rig.link.up().sentBytes() - b0;
        double n = rig.link.up().sent() - n0;
        return new double[] {bytes * 8 / 20 / 1000, (bytes + 28 * n) * 8 / 20 / 1000, n / 20};
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @DisplayName("steady flying within the INPUT budget; mashing at most one early copy per tick")
    void rates() {
        List<String> lines = new ArrayList<>();
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (int rtt : new int[] {100, 300}) {
            for (Scenario.Loss l : new Scenario.Loss[] {Scenario.Loss.NONE, Scenario.Loss.BURSTY_5}) {
                for (Scenario.Jitter j : new Scenario.Jitter[] {Scenario.Jitter.NONE, Scenario.Jitter.NORMAL_15}) {
                    Scenario s = new Scenario(rtt, j, l);
                    double[] st = run(s, UpstreamRateTest::steady);
                    double[] ma = run(s, UpstreamRateTest::mashing);
                    lines.add(String.format(Locale.ROOT, "UPSTREAM %-10s steady %5.1f kbit/s (%5.1f with UDP/IP, %4.1f/s)"
                        + "   mashing %5.1f kbit/s (%5.1f, %4.1f/s)", s.label(), st[0], st[1], st[2], ma[0], ma[1], ma[2]));
                    String cell = s.label();
                    checks.add(() -> assertTrue(st[0] <= 18, cell + ": steady payload within 18 kbit/s: " + st[0]));
                    // a final, a repeat and one early copy per tick (14 Hz) plus PINGs and ACKs
                    checks.add(() -> assertTrue(ma[2] <= 3 * 14 + 6, cell + ": mashing at most ~3 datagrams per tick: "
                        + ma[2]));
                }
            }
        }
        lines.forEach(System.out::println);
        assertAll(checks);
    }
}
