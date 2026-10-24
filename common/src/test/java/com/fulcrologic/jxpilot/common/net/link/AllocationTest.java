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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The link emulator allocates nothing in steady state (SPEC GC rules; run with escape analysis off). */
class AllocationTest {
    private static final com.sun.management.ThreadMXBean MX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    static long allocated() {
        return MX.getCurrentThreadAllocatedBytes();
    }

    @Test
    @DisplayName("send and receive with every impairment on allocate nothing")
    void steadyState() {
        LinkProfile p = new LinkProfile().delayMs(50).normalJitterMs(15).paretoSpikes(0.05, 20, 1.5, 300)
            .burstyLoss(0.05, 4).reorder(0.01, 30).duplicate(0.01).bandwidth(1_000_000, 500);
        VirtualClock clock = new VirtualClock(0);
        LinkEmulator e = new LinkEmulator(clock, p, new LinkProfile().copyFrom(p), 7);
        ByteBuffer out = ByteBuffer.allocateDirect(1200);
        ByteBuffer in = ByteBuffer.allocateDirect(2048);
        Object addr = new Object();
        long t = 0;
        for (int round = 0; round < 2; round++) {
            long before = allocated();
            for (int i = 0; i < 20_000; i++) {
                t += 4_000_000L;
                clock.advanceTo(t);
                out.clear().limit(100 + (i % 1000));
                e.up().send(out, t, i, addr);
                out.clear().limit(400);
                e.down().send(out, t, i, addr);
                while (e.up().receive(in.clear(), t) >= 0 || e.down().receive(in.clear(), t) >= 0) {
                    // drain everything due
                }
            }
            long bytes = allocated() - before;
            if (round == 1) {
                assertEquals(0, bytes, "20k packets each way through every impairment allocate nothing");
            }
        }
    }
}
