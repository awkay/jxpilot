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

import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.server.sim.RandomPilot;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.snapshot.SnapshotBuilder;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simulation tick and per-viewer snapshot building allocate nothing in steady state (SPEC GC rules),
 * measured tick by tick: every measured tick must allocate zero bytes, and the first one that does not is
 * reported with its phase. The world is kept busy by topping up objects (allocation free) every 50 ticks,
 * and the measured window includes the rare paths for the first time: every kind of kill message, an event
 * ring overrun and a burst ring wrap. Runs in its own surefire execution with escape analysis off.
 */
@DisplayName("Server hot paths are allocation free")
class AllocationTest {
    private static final com.sun.management.ThreadMXBean MX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Harness: one world, its snapshot builder and buffers, per-tick allocation bookkeeping. */
    static final class Run {
        final Simulation s;
        final World w;
        final RandomPilot pilot;
        final SnapshotBuilder sb;
        final ByteBuffer[] parts;
        final long[] cursor;
        final Rng topUp = new Rng(99);
        final int objects;
        long partsWritten;
        long badTick = -1;
        String badPhase = "";
        long badBytes;
        long ticksMeasured;
        int peakObjects;
        /** Measured ticks that allocated anything, and their bytes. */
        long allocTicks;
        long allocBytes;
        private boolean tickAllocated;

        Run(int seed, int objects, int bufferCount) {
            this.objects = objects;
            s = Arena.busy(seed, objects / 2, objects / 2);
            w = s.world;
            pilot = new RandomPilot(seed + 1, w.players.length);
            sb = new SnapshotBuilder(w);
            parts = new ByteBuffer[bufferCount];
            for (int i = 0; i < parts.length; i++) {
                parts[i] = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
            }
            cursor = new long[w.players.length];
        }

        void record(String phase, long bytes, boolean measured) {
            if (measured && bytes != 0) {
                allocBytes += bytes;
                tickAllocated = true;
                if (badTick < 0) {
                    badTick = w.tick;
                    badPhase = phase;
                    badBytes = bytes;
                }
            }
        }

        /**
         * A one-off JIT side effect may allocate in one measured tick: when a method crosses the C2 threshold,
         * HotSpot resolves its class's string constants and loads its signature classes on the requesting
         * thread (~2 KB; seen once as 1848 B right after the warm-up). Steady-state allocation shows in many ticks.
         * BEHAVIOR: "none", or the first allocating tick when more than one tick or more than 4096 B allocated.
         */
        String verdict() {
            return allocTicks <= 1 && allocBytes <= 4096 ? "none"
                : report() + " (" + allocTicks + " ticks, " + allocBytes + " bytes in all)";
        }

        /** One tick: optional top-up, the scenario hook, the sim tick, a snapshot for every viewer. */
        void step(int i, boolean measured, int scenarioTick) {
            long a = MX.getCurrentThreadAllocatedBytes();
            if (i % 50 == 0 && w.objects.count() < objects) {
                int missing = objects - w.objects.count();
                Arena.fill(s, missing / 2, missing / 2, topUp);
            }
            record("top-up", MX.getCurrentThreadAllocatedBytes() - a, measured);
            if (measured) {
                peakObjects = Math.max(peakObjects, w.objects.count());
            }
            a = MX.getCurrentThreadAllocatedBytes();
            if (scenarioTick >= 0) {
                rare(this, scenarioTick);
            }
            record("scenario", MX.getCurrentThreadAllocatedBytes() - a, measured);
            a = MX.getCurrentThreadAllocatedBytes();
            s.tick(pilot.next());
            record("sim tick", MX.getCurrentThreadAllocatedBytes() - a, measured);
            a = MX.getCurrentThreadAllocatedBytes();
            for (int k = 0; k < w.numPlayers; k++) {
                for (ByteBuffer b : parts) {
                    b.clear();
                }
                Player pl = w.playerAt(k);
                partsWritten += sb.build(pl, w.tick, cursor[pl.slot], (w.tick % 3) == 0, parts);
                cursor[pl.slot] = w.events.burstSeq();
            }
            record("snapshots", MX.getCurrentThreadAllocatedBytes() - a, measured);
            if (measured) {
                ticksMeasured++;
                allocTicks += tickAllocated ? 1 : 0;
                tickAllocated = false;
            }
        }

        String report() {
            return badTick < 0 ? "none" : "tick " + badTick + " phase " + badPhase + ": " + badBytes + " bytes";
        }
    }

    /** An object of {@code owner} on top of {@code pl}, which loses its shields: a hit this tick. */
    static void lethal(World w, Player pl, int owner, int type, int status, int mods, float mass) {
        pl.used &= ~Has.SHIELD;
        Objects o = w.objects;
        int h = o.alloc();
        if (h < 0) {
            return;
        }
        o.type[h] = type;
        o.status[h] = status;
        o.owner[h] = owner;
        o.mods[h] = mods;
        o.mass[h] = mass;
        o.life[h] = 30;
        o.fuseLife[h] = 0;
        o.plRange[h] = type == Obj.DEBRIS ? 8 : 0;
        o.initPixels(h, pl.px, pl.py);
        o.vx[h] = pl.vx;
        o.vy[h] = pl.vy;
    }

    static Player alive(World w, int from) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt((from + k) % w.numPlayers);
            if (p.isAlive()) {
                return p;
            }
        }
        return w.playerAt(0);
    }

    /** The rare paths, each first reached inside the measured window (tick offsets from its start). */
    static void rare(Run r, int t) {
        World w = r.w;
        Player v = alive(w, t);
        Player k = alive(w, t + 7);
        switch (t) {
            case 100 -> lethal(w, v, k.id, Obj.SHOT, 0, 0, 0.1f);
            case 120 -> lethal(w, v, v.id, Obj.SHOT, 0, 0, 0.1f);
            case 140 -> lethal(w, v, Obj.NO_ID, Obj.CANNON_SHOT, Obj.FROMCANNON, Obj.MOD_CLUSTER, 0.4f);
            case 160 -> lethal(w, v, Obj.NO_ID, Obj.SHOT, 0, Obj.MOD_CLUSTER, 0.3f);
            case 180 -> {
                v.fuel = 1;
                lethal(w, v, k.id, Obj.DEBRIS, 0, 0, 4.5f);
            }
            case 200 -> {
                v.fuel = 1;
                lethal(w, v, v.id, Obj.DEBRIS, 0, 0, 4.5f);
            }
            case 220 -> {
                v.fuel = 1;
                lethal(w, v, Obj.NO_ID, Obj.DEBRIS, 0, 0, 4.5f);
            }
            case 240 -> {
                v.used &= ~Has.SHIELD;
                k.used &= ~Has.SHIELD;
                k.initPixels(v.px + 2, v.py);
            }
            case 260 -> {
                v.status |= Obj.SELF_DESTRUCT;
                v.count = 1;
            }
            case 280 -> v.setClicks(-640, v.cy);
            case 300 -> {
                for (int e = 0; e < 3 * Events.CAPACITY; e++) {
                    w.events.message(1, Obj.NO_ID).append(w.playerAt(e & 31).name).append(" ran over ")
                        .append(w.playerAt((e + 1) & 31).name).append('.');
                }
            }
            default -> {
                if (t >= 400 && t < 400 + Events.BURSTS / 8 + 10) {
                    for (int q = 0; q < 8; q++) {
                        DebrisBurst d = w.events.addBurst(0, 0, Obj.NO_ID, w.tick);
                        d.x = 1000;
                        d.y = 1000;
                        d.count = 10;
                        d.minLife = 5;
                        d.maxLife = 20;
                        d.minSpeed = 5;
                        d.maxSpeed = 20;
                        d.dirSpan = 100;
                    }
                }
            }
        }
    }

    static long deaths(World w) {
        long d = 0;
        for (int k = 0; k < w.numPlayers; k++) {
            d += w.playerAt(k).deaths;
        }
        return d;
    }

    @Test
    @DisplayName("a busy 32-player world (3000 objects), rare paths included, allocates zero bytes in every measured tick")
    void busyTicksAreAllocationFree() {
        Run r = new Run(3, 3000, 8);
        // Warm-up past the C2 compile of methods called once per tick (~5000 calls): a compile request on the
        // measured thread shows up as a few hundred to ~2000 thread-allocated bytes in rare runs.
        int warm = 8000;
        int measured = 1200;
        long ev0 = 0;
        long bursts0 = 0;
        long deaths0 = 0;
        for (int i = 0; i < warm + measured; i++) {
            if (i == warm) {
                ev0 = r.w.events.seq();
                bursts0 = r.w.events.burstSeq();
                deaths0 = deaths(r.w);
            }
            r.step(i, i >= warm, i >= warm ? i - warm : -1);
        }
        long events = r.w.events.seq() - ev0;
        long bursts = r.w.events.burstSeq() - bursts0;
        long died = deaths(r.w) - deaths0;
        assertAll(
            () -> assertEquals("none", r.verdict(), "no measured tick allocates beyond one one-off JIT request (first allocating tick and phase)"),
            () -> assertEquals(measured, r.ticksMeasured, "every measured tick was checked"),
            () -> assertTrue(r.w.objects.count() > 1000, "the world stays busy (" + r.w.objects.count() + " objects)"),
            () -> assertTrue(events > 3 * Events.CAPACITY, "the event ring overran in the window (" + events + ")"),
            () -> assertTrue(bursts > Events.BURSTS, "the burst ring wrapped in the window (" + bursts + ")"),
            () -> assertTrue(died >= 9, "the rare kills happened (" + died + " deaths)"),
            () -> assertTrue(r.partsWritten > 0, "snapshots were written"));
    }

    @Test
    @DisplayName("a saturated world (8000+ objects, 4-part snapshots) allocates zero bytes in every measured tick")
    void saturatedTicksAreAllocationFree() {
        Run r = new Run(5, 8400, 4);
        long parts0 = 0;
        for (int i = 0; i < 1500; i++) {
            if (i == 1000) {
                parts0 = r.partsWritten;
            }
            r.step(i, i >= 1000, -1);
        }
        long parts = r.partsWritten - parts0;
        assertAll(
            () -> assertEquals("none", r.verdict(), "no measured tick allocates beyond one one-off JIT request (first allocating tick and phase)"),
            () -> assertTrue(r.peakObjects >= 8000, "saturated: 8000+ objects after each top-up (" + r.peakObjects + ")"),
            () -> assertTrue(parts > 3L * 32 * 500, "most snapshots fill all 4 parts (" + parts + " parts)"));
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
        long eventsBefore = w.events.seq();
        int joinsBefore = joins(s);
        int peakRobots = 0;
        long start = MX.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 4000; i++) {
            s.tick(in);
            peakRobots = Math.max(peakRobots, s.robots.numRobots());
        }
        long allocated = MX.getCurrentThreadAllocatedBytes() - start;
        int robotsPeak = peakRobots;
        long eventsAfter = w.events.seq();
        int joined = joins(s) - joinsBefore;
        assertAll(
            () -> assertEquals(0, allocated, "4000 ticks with 16 robots allocate no bytes"),
            () -> assertTrue(joined > 0, "robots left and new ones joined in the measured ticks (" + joined + ")"),
            () -> assertEquals(16, robotsPeak, "16 robots were playing (a leaver is replaced after 2*FPS frames)"),
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
