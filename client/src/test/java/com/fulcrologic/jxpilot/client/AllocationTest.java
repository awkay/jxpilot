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
package com.fulcrologic.jxpilot.client;

import com.fulcrologic.jxpilot.client.map.MapState;
import com.fulcrologic.jxpilot.client.map.MapSymbols;
import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.FrameStats;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.client.viewer.ViewerController;
import com.fulcrologic.jxpilot.client.viewer.ViewerOverlay;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-frame render path (controller, camera, map symbols, overlay text with numbers, batch
 * fill including overflow flushes) allocates nothing in steady state.
 * <p>
 * Runs in its own surefire execution with escape analysis off (see the pom), so a pass does not
 * depend on the JIT scalar-replacing objects the code would otherwise allocate, nor on other tests
 * sharing the JVM.
 */
class AllocationTest {
    static final int WARMUP = 40_000;
    static final int WINDOWS = 5;
    static final int WINDOW_FRAMES = 2_000;

    @Test
    void steadyStateFrameAllocatesZeroBytes() throws Exception {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        WorldMap map = WorldMap.load(Fixtures.MAPS.resolve("teamball.xp")); // has every symbol kind but asteroids
        Palette p = Palette.xpilotDefault();
        long[] flushed = new long[1];
        long[] points = new long[1];
        VertexBatch b = Fixtures.batch(512, 64, batch -> {
            flushed[0] += batch.vertexCount() + batch.segmentCount();
            points[0] += batch.pointCount();
        });
        TextWriter text = new TextWriter(Fixtures.font());
        MapSymbols symbols = new MapSymbols(map, p);
        // a cannon on background-dot position (0,0), drawn in the same frames as teamball
        WorldMap dotMap = Fixtures.map(true, "1.......", "r.......");
        MapSymbols dotSymbols = new MapSymbols(dotMap, p);
        Camera dotCam = new Camera();
        dotCam.setWorld(dotMap.pixelWidth(), dotMap.pixelHeight(), true);
        dotCam.setViewport(800, 600, 1);
        dotCam.setCentre(17, 17);
        dotCam.update();
        ViewerOverlay overlay = new ViewerOverlay(p);
        ViewerOverlay.RadarTexture radar = new ViewerOverlay.RadarTexture(256, 200, 0.5f, 0, 0.75f, 0.2f);
        Camera cam = new Camera();
        cam.setWorld(map.pixelWidth(), map.pixelHeight(), true); // wrap: exercise several copies
        cam.setViewport(2560, 1600, 2);
        ViewerController ctl = new ViewerController();
        ctl.reset(10, 10, 0.5);
        ctl.press(ViewerController.RIGHT | ViewerController.ZOOM_IN);
        FrameStats stats = new FrameStats();
        // cannons die and revive frame by frame so dead-cannon dots (points) are exercised too
        long[] loops = new long[1];
        MapState state = new MapState() {
            @Override public double fuel(int i) { return (loops[0] + i) % 500; }
            @Override public boolean cannonAlive(int i) { return ((loops[0] + i) & 1) == 0; }
            @Override public boolean targetAlive(int i) { return true; }
            @Override public double targetDamage(int i) { return Const.TARGET_DAMAGE; }
            @Override public int selfTeam() { return 1; }
            @Override public int nextCheckpoint() { return 0; }
            @Override public long loops() { return loops[0]; }
        };

        Runnable frame = new Runnable() {
            long n;

            @Override
            public void run() {
                n++;
                stats.record(16_000_000 + n, 0);
                ctl.update(0.0001);
                ctl.applyTo(cam);
                cam.update();
                ctl.syncFrom(cam);
                loops[0] = n;
                b.clear();
                symbols.draw(b, text, cam, state);
                dotSymbols.draw(b, text, dotCam, state);
                overlay.draw(b, text, cam, stats, "teamball.xp", radar, true);
                text.drawInt(b, (int) n, 0, 0, 0);
                text.drawFixed(b, n * 0.37, 2, 0, 0, 0);
                points[0] += b.pointCount();
            }
        };
        for (int i = 0; i < WARMUP; i++) {
            frame.run();
        }
        // Several back-to-back windows: a late JIT (re)install can allocate once inside the JVM, so the
        // claim is about the steady state, i.e. the last window; all windows are shown on failure.
        long[] windows = new long[WINDOWS];
        for (int w = 0; w < WINDOWS; w++) {
            long before = mx.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < WINDOW_FRAMES; i++) {
                frame.run();
            }
            windows[w] = mx.getCurrentThreadAllocatedBytes() - before;
        }
        String all = java.util.Arrays.toString(windows);
        assertAll(
            () -> assertTrue(cam.offsetCount() > 1, "the view spans world copies"),
            () -> assertTrue(flushed[0] > 0, "the batch overflowed and flushed during the run"),
            () -> assertTrue(points[0] > 0, "dead-cannon dots were drawn"),
            () -> assertEquals(0, windows[WINDOWS - 1], "zero bytes allocated in the last window; all windows: " + all));
    }
}
