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
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

/**
 * Tick cost with 32 players and thousands of shots/debris (not a pass/fail test; excluded from the normal
 * run). Run: {@code MAVEN_OPTS= mvn -o -pl server test -Dtest=TickBenchmark -Dsurefire.failIfNoSpecifiedTests=false}
 * with the default excludes overridden, or from an IDE.
 */
class TickBenchmark {
    @Test
    void benchmark() {
        for (int objects : new int[] {2000, 6000, 12000}) {
            Simulation s = Arena.busy(5, objects / 2, objects / 2);
            World w = s.world;
            RandomPilot pilot = new RandomPilot(6, w.players.length);
            SnapshotBuilder sb = new SnapshotBuilder(w);
            ByteBuffer[] parts = new ByteBuffer[8];
            for (int i = 0; i < parts.length; i++) {
                parts[i] = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
            }
            for (int i = 0; i < 3000; i++) {
                s.tick(pilot.next());
            }
            int n = 2000;
            long tick = 0;
            long objSum = 0;
            for (int i = 0; i < n; i++) {
                if (i % 50 == 0) {
                    int missing = objects - w.objects.count();
                    if (missing > 0) {
                        Arena.fill(s, missing / 2, missing - missing / 2, i);
                    }
                }
                long t0 = System.nanoTime();
                s.tick(pilot.next());
                tick += System.nanoTime() - t0;
                objSum += w.objects.count();
            }
            int rounds = 200;
            long t1 = System.nanoTime();
            for (int r = 0; r < rounds; r++) {
                for (int k = 0; k < w.numPlayers; k++) {
                    for (ByteBuffer b : parts) {
                        b.clear();
                    }
                    sb.build(w.playerAt(k), 0, w.events.burstSeq() - 64, true, parts);
                }
            }
            long snap = (System.nanoTime() - t1) / rounds;
            System.out.printf("objects %5d avg live %5d: %7.1f us/tick, snapshots for 32 viewers %7.1f us%n",
                objects, objSum / n, tick / 1000.0 / n, snap / 1000.0);
        }
    }
}
