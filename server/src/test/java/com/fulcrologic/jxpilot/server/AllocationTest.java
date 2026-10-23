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
        // Warm-up: methods called once per tick reach their final (C2) compile after about 5000 calls, and a
        // compile request shows up as a few hundred thread-allocated bytes; measure after that.
        for (int i = 0; i < 8000; i++) {
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

    @Test
    @DisplayName("16 robots flying, fighting, dying, leaving and joining allocate zero bytes per tick")
    void robotTickIsAllocationFree() {
        Simulation s = new Simulation(new World(TestWorlds.options(
            "gravity: -0.14\nmaxRobots: 16\nminRobots: 16\nrobotsTalk: yes\nrobotLeaveLife: 3\n", Arena.rows()), 5));
        World w = s.world;
        s.robots.setEnabled(true);
        long[] in = new long[w.players.length];
        for (int i = 0; i < 20000; i++) {
            s.tick(in);
        }
        long robotsBefore = s.robots.numRobots();
        long eventsBefore = w.events.seq();
        int joinsBefore = joins(s);
        long start = MX.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 4000; i++) {
            s.tick(in);
        }
        long allocated = MX.getCurrentThreadAllocatedBytes() - start;
        long eventsAfter = w.events.seq();
        int joined = joins(s) - joinsBefore;
        assertAll(
            () -> assertEquals(0, allocated, "4000 ticks with 16 robots allocate no bytes"),
            () -> assertTrue(joined > 0, "robots left and new ones joined in the measured ticks (" + joined + ")"),
            () -> assertEquals(16, robotsBefore, "16 robots were playing"),
            () -> assertTrue(eventsAfter > eventsBefore + 100, "the robots were busy (" + (eventsAfter - eventsBefore)
                + " events)"));
    }

    private static int joins(Simulation s) {
        int n = 0;
        for (int i = 0; i < s.robots.roster().size(); i++) {
            n += s.robots.roster().used(i);
        }
        return n;
    }
}
