package com.fulcrologic.jxpilot.client;

import com.fulcrologic.jxpilot.client.map.FullMapState;
import com.fulcrologic.jxpilot.client.map.MapSymbols;
import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.FrameStats;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.client.viewer.ViewerController;
import com.fulcrologic.jxpilot.client.viewer.ViewerOverlay;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-frame render path (controller, camera, map symbols, overlay text with numbers, batch
 * fill including overflow flushes) allocates nothing in steady state.
 */
class AllocationTest {
    @Test
    void steadyStateFrameAllocatesZeroBytes() throws Exception {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        WorldMap map = WorldMap.load(Fixtures.MAPS.resolve("teamball.xp")); // has every symbol kind but asteroids
        Palette p = Palette.xpilotDefault();
        long[] flushed = new long[1];
        VertexBatch b = VertexBatch.fixed(ByteBuffer.allocateDirect(512 * VertexBatch.STRIDE), 64,
            batch -> flushed[0] += batch.vertexCount());
        TextWriter text = new TextWriter(Fixtures.font());
        MapSymbols symbols = new MapSymbols(map, p);
        ViewerOverlay overlay = new ViewerOverlay(p);
        ViewerOverlay.RadarTexture radar = new ViewerOverlay.RadarTexture(256, 200, 0.5f, 0, 0.75f, 0.2f);
        Camera cam = new Camera();
        cam.setWorld(map.pixelWidth(), map.pixelHeight(), true); // wrap: exercise several copies
        cam.setViewport(2560, 1600, 2);
        ViewerController ctl = new ViewerController();
        ctl.reset(10, 10, 0.5);
        ctl.press(ViewerController.RIGHT | ViewerController.ZOOM_IN);
        FrameStats stats = new FrameStats();
        FullMapState state = new FullMapState();

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
                state.setLoops(n);
                b.clear();
                symbols.draw(b, text, cam, state);
                overlay.draw(b, text, cam, stats, "teamball.xp", radar, true);
                text.drawInt(b, (int) n, 0, 0, 0);
                text.drawFixed(b, n * 0.37, 2, 0, 0, 0);
            }
        };
        for (int i = 0; i < 20_000; i++) {
            frame.run();
        }
        long before = mx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 2_000; i++) {
            frame.run();
        }
        long after = mx.getCurrentThreadAllocatedBytes();
        assertAll(
            () -> assertTrue(cam.offsetCount() > 1, "the view spans world copies"),
            () -> assertTrue(flushed[0] > 0, "the batch overflowed and flushed during the run"),
            () -> assertEquals(0, after - before, "zero bytes allocated per frame in steady state"));
    }
}
