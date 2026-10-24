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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shot association on the XPilot path (positions only, integers, one snapshot per C frame at 14 Hz): streams
 * from a firing ship (R2) and large shot counts (R5). Adapted from the correctness review's probe.
 */
@DisplayName("Shot streams and association cost")
class ShotStreamTest {
    static final int VARIANT = Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.WHITE);
    static final int SHIP = 5;

    static final class Rig {
        final Options options = new Options();
        final WorldMap map;
        final Msg.Welcome welcome = new Msg.Welcome();
        final ClientWorld world;
        final ByteBuffer buf = ByteBuffer.allocate(1 << 20);
        final SnapshotWriter w = new SnapshotWriter();
        final int hz;

        Rig(int hz, int blocks, int shotCap) {
            this.hz = hz;
            StringBuilder sb = new StringBuilder();
            sb.append("mapWidth: ").append(blocks).append("\nmapHeight: ").append(blocks)
                .append("\nedgeWrap: yes\nframesPerSecond: ").append(hz).append("\nmapData: \\multiline: E\n");
            for (int r = 0; r < blocks; r++) {
                sb.append(r == 1 ? "  _" + " ".repeat(blocks - 3) : " ".repeat(blocks)).append('\n');
            }
            sb.append("E\n");
            ConfigParser.parse(sb.toString(), "probe", OptionOrigin.MAP, options);
            map = WorldMap.fromOptions(options);
            welcome.simHz = hz;
            welcome.snapshotHz = hz;
            welcome.gameFps = hz;
            world = new ClientWorld(new Players(), 16, shotCap, 4096, 64);
            world.configure(map, options, welcome);
            w.setWorld(map.pixelWidth(), map.pixelHeight(), true);
        }

        long at(double tick) {
            return 1_000_000_000L + Math.round((tick - 100) / hz * 1e9);
        }

        /** Shots as xy pairs without velocity (the XPilot path), an optional firing ship. */
        void frame(int tick, double cx, double cy, double[] xy, int n, double[] ship) {
            buf.clear();
            w.begin(buf, tick, 0, 0, 1, SnapshotWriter.FLAG_LAST_PART, cx, cy);
            if (ship != null) {
                w.beginSection(Section.SHIP);
                Records.Ship.put(w, SHIP, 0, ship[0], ship[1], ship[2], ship[3], ship[4]);
                w.endSection();
            }
            w.beginSection(Section.SHOT, VARIANT);
            for (int i = 0; i < n; i++) {
                Records.Shot.put(w, xy[2 * i], xy[2 * i + 1], 0, 0);
            }
            w.endSection();
            buf.flip();
            world.ingest(buf, at(tick));
        }
    }

    /**
     * A ship at y = 1000 moving at {@code shipV} px/frame along x fires along +x every {@code every} frames:
     * each shot starts 10 px ahead of the ship and flies at shipV + 21 (C: ship velocity + shotSpeed), sent as
     * whole pixels (C). Four display frames per tick; returns {mean, max} |drawn - true| over all drawn shots.
     */
    static double[] stream(double shipV, int every, boolean withShip) {
        Rig r = new Rig(14, 200, 2048);
        List<double[]> shots = new ArrayList<>();
        double sum = 0;
        double max = 0;
        int samples = 0;
        double[] xy = new double[4096];
        List<List<double[]>> order = new ArrayList<>();
        for (int tick = 100; tick < 400; tick++) {
            double sx = 500 + shipV * (tick - 100);
            if ((tick - 100) % every == 0) {
                shots.add(new double[] {tick, sx + 10.4, shipV + 21});
            }
            final int tk = tick;
            shots.removeIf(s -> tk - s[0] > 60);
            List<double[]> ord = new ArrayList<>(shots);
            int n = 0;
            for (double[] s : ord) {
                xy[2 * n] = Math.floor(s[1] + s[2] * (tick - s[0]));
                xy[2 * n + 1] = 1000;
                n++;
            }
            r.frame(tick, sx, 1000, xy, n, withShip ? new double[] {sx, 1000, 0, shipV, 0} : null);
            order.add(ord);
            if (tick > 140) {
                for (int k = 0; k < 4; k++) {
                    double rt = tick - 1 + 0.05 + k * 0.22;
                    r.world.update(r.at(rt + r.world.interpolationDelayTicks()));
                    double renderTick = r.world.renderTick();
                    List<double[]> aOrd = order.get((int) Math.floor(renderTick) - 100);
                    for (int i = 0; i < r.world.shotCount() && i < aOrd.size(); i++) {
                        double[] s = aOrd.get(i);
                        double e = Math.abs(r.world.shotX(i) - (s[1] + s[2] * (renderTick - s[0])));
                        sum += e;
                        max = Math.max(max, e);
                        samples++;
                    }
                }
            }
        }
        return new double[] {sum / samples, max};
    }

    @Nested
    @DisplayName("R2: streams from a firing ship")
    class Streams {
        @Test
        @DisplayName("fireRepeat 1/2/3, ship speed 0/4.3/9.7: drawn shots within 1 px of the truth on average")
        void accurate() {
            List<String> bad = new ArrayList<>();
            StringBuilder all = new StringBuilder();
            for (int every : new int[] {1, 2, 3}) {
                for (double v : new double[] {0, 4.3, 9.7}) {
                    double[] e = stream(v, every, true);
                    all.append(String.format("every=%d v=%.1f mean=%.2f max=%.2f; ", every, v, e[0], e[1]));
                    if (!(e[0] < 1)) {
                        bad.add("every=" + every + " v=" + v + " mean " + e[0]);
                    }
                }
            }
            System.out.println("[R2 streams] " + all);
            assertEquals(List.of(), bad, "every stream has mean |error| < 1 px: " + all);
        }

        @Test
        @DisplayName("without a ship to seed from, an ambiguous stream is not linked backwards (errors stay bounded)")
        void noShip() {
            double[] e = stream(0, 1, false);
            System.out.println("[R2 no ship] " + Arrays.toString(e));
            // unlinked shots step at 14 Hz: at most one frame of travel (21 px) behind or ahead, never sliding back
            assertTrue(e[1] <= 22, "no mis-link drags a shot more than a frame (" + Arrays.toString(e) + ")");
        }
    }

    /** Update time (ms) with a fresh association (shots and points) for n random C-path shots in 800 px. */
    static double[] cost(int n, int ticks) {
        Rig r = new Rig(14, 200, 4096);
        SplittableRandom rnd = new SplittableRandom(42);
        double[] x = new double[n];
        double[] y = new double[n];
        double[] vx = new double[n];
        double[] vy = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = 3000 + rnd.nextDouble() * 800;
            y[i] = 3000 + rnd.nextDouble() * 800;
            double a = rnd.nextDouble() * 2 * Math.PI;
            vx[i] = Math.cos(a) * 25;
            vy[i] = Math.sin(a) * 25;
        }
        double[] xy = new double[2 * n];
        double[] times = new double[ticks];
        long linked = 0;
        for (int tick = 100; tick < 100 + ticks; tick++) {
            for (int i = 0; i < n; i++) {
                xy[2 * i] = Math.floor(x[i] + vx[i] * (tick - 100));
                xy[2 * i + 1] = Math.floor(y[i] + vy[i] * (tick - 100));
            }
            r.frame(tick, 3400, 3400, xy, n, null);
            r.world.invalidateAssociations();
            long t0 = System.nanoTime();
            r.world.update(r.at(tick - 1 + 0.05 + r.world.interpolationDelayTicks()));
            times[tick - 100] = (System.nanoTime() - t0) / 1e6;
            linked += r.world.shotCount();
        }
        Arrays.sort(times, ticks / 2, ticks);
        return new double[] {times[ticks / 2 + (ticks - ticks / 2) / 2], linked};
    }

    @Nested
    @DisplayName("R5: grid association")
    class Cost {
        @Test
        @DisplayName("2000 shots associate (and draw) in under 2 ms (median after warm-up); 1025 shots still link")
        void bench() {
            cost(2000, 200);
            double[] c2000 = cost(2000, 200);
            ClientWorld w1025 = new Rig(14, 200, 4096).world;
            Rig r = new Rig(14, 200, 4096);
            double[] xy = new double[2 * 1025];
            for (int tick = 100; tick < 103; tick++) {
                for (int i = 0; i < 1025; i++) {
                    xy[2 * i] = 1000 + (i % 40) * 50 + 21 * (tick - 100);
                    xy[2 * i + 1] = 1000 + (i / 40) * 50;
                }
                r.frame(tick, 2000, 1500, xy, 1025, null);
            }
            r.world.update(r.at(101.5 + r.world.interpolationDelayTicks()));
            float x0 = r.world.shotX(0);
            System.out.printf("[R5 cost] 2000 C-path shots: median association update %.3f ms%n", c2000[0]);

            assertAll(
                // ~0.3 ms on a dev machine; CI runners measured 0.50. The bound catches an O(n^2) association
                // (tens of ms at 2000 shots), not machine speed.
                () -> assertTrue(c2000[0] < 2.0, "median association update " + c2000[0] + " ms"),
                () -> assertNotNull(w1025, "rig"),
                () -> assertEquals(1000 + 21 * 1.5, x0, 1.0,
                    "1025 shots on a 50 px lattice moving 21 px: the first is interpolated, not stepping (no cliff)"));
        }
    }
}
