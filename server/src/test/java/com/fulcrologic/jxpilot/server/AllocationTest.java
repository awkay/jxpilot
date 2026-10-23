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
package com.fulcrologic.jxpilot.server;

import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.server.sim.RandomPilot;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.snapshot.SnapshotBuilder;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simulation tick and per-viewer snapshot building allocate nothing in steady state (SPEC GC rules).
 * Runs in its own surefire execution with escape analysis off.
 */
@DisplayName("Server hot paths are allocation free")
class AllocationTest {
    private static final com.sun.management.ThreadMXBean MX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    @Test
    @DisplayName("a busy 32-player tick plus snapshots for every viewer allocates zero bytes")
    void busyTickIsAllocationFree() {
        Simulation s = Arena.busy(3, 1500, 1500);
        World w = s.world;
        RandomPilot pilot = new RandomPilot(4, w.players.length);
        SnapshotBuilder sb = new SnapshotBuilder(w);
        ByteBuffer[] parts = new ByteBuffer[8];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
        }
        long[] burstCursor = new long[w.players.length];
        int[] partsSeen = new int[1];
        Runnable step = () -> {
            s.tick(pilot.next());
            if ((w.tick & 1) == 0) {
                for (int k = 0; k < w.numPlayers; k++) {
                    for (ByteBuffer b : parts) {
                        b.clear();
                    }
                    int slot = w.playerAt(k).slot;
                    partsSeen[0] += sb.build(w.playerAt(k), w.tick, burstCursor[slot], (w.tick % 6) == 0, parts);
                    burstCursor[slot] = w.events.burstSeq();
                }
            }
        };
        for (int i = 0; i < 4000; i++) {
            step.run();
        }
        long objectsBefore = w.objects.count();
        long eventsBefore = w.events.seq();
        long burstsBefore = w.events.burstSeq();
        long start = MX.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 2000; i++) {
            step.run();
        }
        long allocated = MX.getCurrentThreadAllocatedBytes() - start;
        long eventsAfter = w.events.seq();
        long burstsAfter = w.events.burstSeq();
        assertAll(
            () -> assertEquals(0, allocated, "2000 busy ticks with snapshots allocate no bytes"),
            () -> assertTrue(objectsBefore > 100, "the world is busy (" + objectsBefore + " objects)"),
            () -> assertTrue(eventsAfter > eventsBefore, "the measured ticks produced reliable events"),
            () -> assertTrue(burstsAfter > burstsBefore, "the measured ticks produced debris bursts"),
            () -> assertTrue(partsSeen[0] > 0, "snapshots were written"));
    }
}
