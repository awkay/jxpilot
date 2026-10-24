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
package com.fulcrologic.jxpilot.server.lobby;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Allocation regression for the lobby's per-datagram path (excluded from PIT like every AllocationTest). */
class RoutedTransportAllocationTest {
    static final SocketAddress A = new InetSocketAddress("127.0.0.1", 4001);

    @Test
    @DisplayName("datagram copies allocate nothing in steady state")
    void noAllocation() {
        RoutedTransport t = new RoutedTransport(new RoutedTransportTest.Sink());
        t.setReceiver((c, f, d, n) -> { });
        ByteBuffer data = ByteBuffer.allocateDirect(200);
        data.limit(100);
        for (int i = 0; i < 2000; i++) { // warm up
            t.offer(0, A, data, i);
            t.poll(0);
        }
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
            .getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 10_000; i++) {
            t.offer(0, A, data, i);
            t.poll(0);
        }
        long used = mx.getThreadAllocatedBytes(id) - before;
        assertEquals(0, used, "offer + poll allocate nothing once warm (measured " + used + " bytes)");
    }
}
