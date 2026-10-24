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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.snapshot.SnapshotBuilder;
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
 * The game-mode code paths allocate nothing per tick (SPEC GC rules): balls (connecting, towing, cashing,
 * round resets), targets (damage, destruction, repair), asteroids (spawning, breaking, collisions), wreckage,
 * temporary wormholes, race checkpoints and race ends, with robots playing and a snapshot built for every
 * player each tick. Runs in the allocation-test execution (escape analysis off).
 */
@DisplayName("Game modes are allocation free")
class AllocationTest {
    private static final com.sun.management.ThreadMXBean MX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** 60 x 60 team arena: 2 x 8 bases, 4 treasures and 2 targets per team, an asteroid concentrator. */
    static String[] teamRows() {
        int n = 60;
        char[][] g = new char[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                g[y][x] = (x == 0 || y == 0 || x == n - 1 || y == n - 1) ? 'x' : ' ';
            }
        }
        for (int i = 0; i < 8; i++) {
            g[50][6 + i * 3] = '1';
            g[10][6 + i * 3] = '2';
        }
        for (int i = 0; i < 4; i++) {
            g[47][8 + i * 4] = '*';
            g[13][8 + i * 4] = '*';
        }
        g[44][30] = '!';
        g[44][34] = '!';
        g[16][30] = '!';
        g[16][34] = '!';
        g[30][30] = '&';
        String[] rows = new String[n];
        for (int y = 0; y < n; y++) {
            rows[y] = new String(g[y]);
        }
        return rows;
    }

    /** Measures every tick after warm-up; remembers the first allocating one. */
    static final class Meter {
        final SnapshotBuilder sb;
        final ByteBuffer[] parts = new ByteBuffer[4];
        final World w;
        long bad = -1;
        long badBytes;
        String badPhase = "";

        Meter(World w) {
            this.w = w;
            sb = new SnapshotBuilder(w);
            for (int i = 0; i < parts.length; i++) {
                parts[i] = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
            }
        }

        void check(String phase, long before, boolean measured) {
            check(phase, before, measured, -1, -1);
        }

        /** As {@link #check(String, long, boolean)} with two numbers for the report (no string building). */
        void check(String phase, long before, boolean measured, long x1, long x2) {
            long d = MX.getCurrentThreadAllocatedBytes() - before;
            if (measured && d != 0 && bad < 0) {
                bad = w.tick;
                badBytes = d;
                badPhase = phase + " " + x1 + " " + x2;
            }
        }

        void snapshots(boolean measured) {
            long a = MX.getCurrentThreadAllocatedBytes();
            for (int k = 0; k < w.numPlayers; k++) {
                for (ByteBuffer b : parts) {
                    b.clear();
                }
                sb.build(w.playerAt(k), w.tick, w.events.burstSeq(), (w.tick % 3) == 0, parts);
            }
            check("snapshots", a, measured);
        }

        String report() {
            return bad < 0 ? "none" : "tick " + bad + " " + badPhase + ": " + badBytes + " bytes";
        }
    }

    /** Find a ball handle, or -1. */
    static int aBall(World w, int skip) {
        Objects o = w.objects;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[(k + skip) % o.count()];
            if (o.type[h] == Obj.BALL) {
                return h;
            }
        }
        return -1;
    }

    /** The rare game-mode paths, driven through the simulation's own entry points. */
    static void scenario(Simulation s, int t) {
        World w = s.world;
        Player p = w.playerAt(t % w.numPlayers);
        switch (t % 400) {
            case 10 -> {
                int h = aBall(w, t);
                if (h >= 0 && p.isAlive()) {
                    p.used |= Has.CONNECTOR;
                    w.objects.initPixels(h, p.px + 60, p.py);
                    w.modes.ballOwner[h] = 77;
                }
            }
            case 90 -> s.targets.damage(t % 4, Obj.PLAYER, p.id, p.team == 1 ? 2 : 1, 0, 0, 1, false, 0, 1f, 64000);
            case 130 -> {
                p.used |= Has.REPAIR;
                p.repairTarget = 0;
            }
            case 170 -> {
                p.wormHoleHit = -1;
                p.status |= Obj.WARPING;
            }
            case 210 -> {
                Player q = w.playerAt((t + 1) % w.numPlayers);
                if (q.team != p.team) {
                    s.balls.punishTeam(p, q.team == 1 ? 0 : 4, p.team == 1 ? 0 : 4);
                }
                w.modes.teamTreasuresLeft[q.team] = 0;
                w.modes.teamTreasuresDestroyed[p.team]++;
            }
            case 250 -> s.asteroids.make(30 * 35, 30 * 35, 4, t & 127, 5f);
            default -> { }
        }
    }

    @Test
    @DisplayName("team play with balls, targets, asteroids, wreckage, temporary wormholes and robots: zero bytes per tick")
    void teamModes() {
        Simulation s = new Simulation(new World(TestWorlds.options("teamPlay: yes\ngravity: -0.14\n"
            + "maxAsteroidDensity: 0.05\nasteroidProb: 0.001\nballCollisions: yes\ntreasuresOnRadar: yes\n"
            + "asteroidsOnRadar: yes\nwormTime: 2\ntreasureKillTeam: yes\ntargetKillTeam: yes\ntargetSync: yes\n"
            + "maxRoundTime: 60\nmaxRobots: 6\nminRobots: 6\nrestrictRobots: no\nreserveRobotTeam: no\n"
            + "treasureCollisionMayKill: yes\nwreckageCollisionMayKill: yes\n", teamRows()), 7));
        World w = s.world;
        for (int i = 0; i < 10; i++) {
            s.ships.join("pilot" + i, 1 + (i & 1));
        }
        s.robots.setEnabled(true);
        RandomPilot pilot = new RandomPilot(11, w.players.length);
        Meter m = new Meter(w);
        int warm = 15000;
        int measured = 2000;
        long breaks0 = 0;
        int asteroidsSeen = 0;
        int ballsTowed = 0;
        for (int i = 0; i < warm + measured; i++) {
            boolean me = i >= warm;
            if (i == warm) {
                breaks0 = w.events.seq();
            }
            long a = MX.getCurrentThreadAllocatedBytes();
            scenario(s, i);
            m.check("scenario", a, me);
            long[] in = pilot.next();
            for (int k = 0; k < in.length; k += 2) {
                in[k] |= ControlKey.CONNECTOR.mask();
            }
            a = MX.getCurrentThreadAllocatedBytes();
            s.tick(in);
            m.check("sim tick", a, me);
            m.snapshots(me);
            if (me) {
                asteroidsSeen = Math.max(asteroidsSeen, w.modes.asteroidCount);
                for (int k = 0; k < w.numPlayers; k++) {
                    ballsTowed += (w.playerAt(k).have & Has.BALL) != 0 ? 1 : 0;
                }
            }
        }
        long events = w.events.seq() - breaks0;
        int ast = asteroidsSeen;
        int towed = ballsTowed;
        assertAll(
            () -> assertEquals("none", m.report(), "no measured tick allocates"),
            () -> assertTrue(ast > 0, "asteroids flew (" + ast + ")"),
            () -> assertTrue(towed > 0, "balls were towed (" + towed + " ship-ticks)"),
            () -> assertTrue(events > 100, "the modes were busy (" + events + " events)"));
    }

    @Test
    @DisplayName("a race with robots, checkpoints, laps, finishes and race ends: zero bytes per tick")
    void race() {
        String[] rows = com.fulcrologic.jxpilot.server.Arena.rows();
        char[] r1 = rows[20].toCharArray();
        r1[10] = 'A';
        r1[50] = 'C';
        rows[20] = new String(r1);
        char[] r2 = rows[40].toCharArray();
        r2[30] = 'B';
        rows[40] = new String(r2);
        Simulation s = new Simulation(new World(TestWorlds.options("timing: yes\nraceLaps: 2\ngravity: -0.1\n"
            + "maxRobots: 6\nminRobots: 6\nlimitedLives: no\n", rows), 9));
        World w = s.world;
        for (int i = 0; i < 6; i++) {
            s.ships.join("racer" + i, 0);
        }
        s.robots.setEnabled(true);
        RandomPilot pilot = new RandomPilot(13, w.players.length);
        Meter m = new Meter(w);
        int warm = 15000;
        int measured = 2000;
        int finishes = 0;
        for (int i = 0; i < warm + measured; i++) {
            boolean me = i >= warm;
            long a = MX.getCurrentThreadAllocatedBytes();
            if (i % 200 == 0) {
                Player p = w.playerAt((i / 200) % w.numPlayers);
                for (int c = 0; c < 9 && p.isAlive(); c++) {
                    s.race.passCheckpoint(p);
                }
                if ((p.status & Obj.FINISH) != 0 && me) {
                    finishes++;
                }
            }
            m.check("scenario", a, me);
            a = MX.getCurrentThreadAllocatedBytes();
            s.tick(pilot.next());
            m.check("sim tick", a, me);
            m.snapshots(me);
        }
        int f = finishes;
        assertAll(
            () -> assertEquals("none", m.report(), "no measured tick allocates"),
            () -> assertTrue(f > 0, "racers finished in the window (" + f + ")"));
    }


}
