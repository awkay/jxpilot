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
package com.fulcrologic.jxpilot.client.gl;

import com.fulcrologic.jxpilot.client.map.FullMapState;
import com.fulcrologic.jxpilot.client.map.MapSymbols;
import com.fulcrologic.jxpilot.client.map.RadarImage;
import com.fulcrologic.jxpilot.client.map.StaticMap;
import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.FrameStats;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.client.viewer.LaunchOptions;
import com.fulcrologic.jxpilot.client.viewer.MapCatalog;
import com.fulcrologic.jxpilot.client.viewer.ViewerController;
import com.fulcrologic.jxpilot.client.viewer.ViewerOverlay;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The {@code --view-map} mode: GLFW loop wiring the pure pieces (camera, controller, static map
 * builders, symbols, overlay) to {@link GlRenderer}. Thin glue; not unit tested.
 */
public final class MapViewerApp {
    /** Logical font size in pixels. */
    static final float FONT_PX = 13;
    /** Dynamic batch size: 64k vertices. */
    static final int DYNAMIC_VERTICES = 65536;
    /** Game frames per second used to pace the wormhole/concentrator animation (C default). */
    static final double ANIMATION_FPS = 14;

    private final LaunchOptions opts;
    private final Palette palette = Palette.xpilotDefault();
    private final Camera cam = new Camera();
    private final ViewerController ctl = new ViewerController();
    private final FrameStats stats = new FrameStats();
    private final FullMapState state = new FullMapState();
    private final float[] xf = new float[4];

    private Window window;
    private GlRenderer gl;
    private UiAtlas atlas;
    private TextWriter text;
    private ByteBuffer dynMem;
    private VertexBatch dyn;
    private ViewerOverlay overlay;
    private ViewerOverlay.RadarTexture radarTex;

    private MapCatalog catalog;
    private int mapIndex;
    private WorldMap map;
    private String mapLabel = "";
    private MapSymbols symbols;
    private GlRenderer.Mesh outlineWalls;
    private GlRenderer.Mesh filledWalls;
    private GlRenderer.Mesh features;
    private boolean filled;
    private boolean showRadar = true;
    private boolean nextMapRequested;

    public MapViewerApp(LaunchOptions opts) {
        this.opts = opts;
        this.filled = opts.filled();
    }

    public void run() throws IOException {
        boolean shot = opts.screenshot() != null;
        window = new Window("JXPilot map viewer", opts.width(), opts.height(), true, opts.vsync());
        try {
            atlas = new UiAtlas(FONT_PX * (float) window.pixelRatio());
            gl = new GlRenderer(DYNAMIC_VERTICES * VertexBatch.STRIDE, atlas.texture());
            text = new TextWriter(atlas.font());
            dynMem = MemoryUtil.memAlloc(DYNAMIC_VERTICES * VertexBatch.STRIDE);
            dyn = VertexBatch.fixed(dynMem, 256, gl);
            overlay = new ViewerOverlay(palette);
            Path file = Path.of(opts.viewMap()).toAbsolutePath().normalize();
            catalog = MapCatalog.scan(file.getParent());
            mapIndex = catalog.indexOf(file);
            loadMap(file, opts.hasCentre());
            installInput();
            loop(shot);
        } finally {
            dispose();
        }
    }

    private void loadMap(Path file, boolean keepCentre) throws IOException {
        WorldMap m = WorldMap.load(file);
        gl.delete(outlineWalls);
        gl.delete(filledWalls);
        gl.delete(features);
        VertexBatch ow = StaticMap.outlineWalls(m, palette);
        VertexBatch fw = StaticMap.filledWalls(m, palette);
        VertexBatch fe = StaticMap.features(m, palette, true);
        outlineWalls = gl.upload(ow);
        filledWalls = gl.upload(fw);
        features = gl.upload(fe);
        int rh = RadarImage.height(m);
        atlas.setRadar(RadarImage.build(m, palette, true), rh);
        float u0 = (float) UiAtlas.RADAR_X / UiAtlas.SIZE;
        radarTex = new ViewerOverlay.RadarTexture(RadarImage.WIDTH, atlas.radarHeight(), u0, 0,
            u0 + (float) RadarImage.WIDTH / UiAtlas.SIZE, (float) atlas.radarHeight() / UiAtlas.SIZE);
        map = m;
        symbols = new MapSymbols(m, palette);
        cam.setWorld(m.pixelWidth(), m.pixelHeight(), m.edgeWrap());
        if (keepCentre) {
            ctl.reset(opts.centreX(), opts.centreY(), opts.zoom());
        } else if (!m.bases().isEmpty()) {
            Base b = m.bases().get(0);
            ctl.reset(b.x(), b.y(), opts.zoom());
        } else {
            ctl.reset(m.pixelWidth() / 2.0, m.pixelHeight() / 2.0, opts.zoom());
        }
        mapLabel = file.getFileName() + "  " + m.width() + "x" + m.height()
            + (m.edgeWrap() ? "  wrap" : "") + (m.name() != null ? "  \"" + m.name() + "\"" : "");
        System.out.println("map " + mapLabel + ": wall verts " + outlineWalls.vertexCount()
            + ", filled verts " + filledWalls.vertexCount() + ", feature verts " + features.vertexCount());
    }

    private void installInput() {
        glfwSetKeyCallback(window.handle(), (w, key, sc, action, mods) -> {
            int bit = switch (key) {
                case GLFW_KEY_LEFT -> ViewerController.LEFT;
                case GLFW_KEY_RIGHT -> ViewerController.RIGHT;
                case GLFW_KEY_UP -> ViewerController.UP;
                case GLFW_KEY_DOWN -> ViewerController.DOWN;
                case GLFW_KEY_EQUAL, GLFW_KEY_KP_ADD -> ViewerController.ZOOM_IN;
                case GLFW_KEY_MINUS, GLFW_KEY_KP_SUBTRACT -> ViewerController.ZOOM_OUT;
                default -> 0;
            };
            if (action == GLFW_PRESS) {
                ctl.press(bit);
                switch (key) {
                    case GLFW_KEY_ESCAPE, GLFW_KEY_Q -> window.close();
                    case GLFW_KEY_TAB -> nextMapRequested = true;
                    case GLFW_KEY_F -> filled = !filled;
                    case GLFW_KEY_R -> showRadar = !showRadar;
                    default -> { }
                }
            } else if (action == GLFW_RELEASE) {
                ctl.release(bit);
            }
        });
        glfwSetScrollCallback(window.handle(), (w, dx, dy) -> ctl.wheel(dy));
    }

    private void loop(boolean shot) throws IOException {
        com.sun.management.ThreadMXBean mx =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long last = System.nanoTime();
        long start = last;
        long prevCpu = 0;
        long allocMark = 0;
        int frame = 0;
        while (!window.shouldClose()) {
            long now = System.nanoTime();
            long dtNanos = now - last;
            last = now;
            if (frame > 0) {
                stats.record(dtNanos, prevCpu);
            }
            if (shot && frame == opts.frames() / 2) {
                allocMark = mx.getCurrentThreadAllocatedBytes();
            }
            glfwPollEvents();
            if (nextMapRequested) {
                nextMapRequested = false;
                mapIndex = catalog.next(mapIndex);
                loadMap(catalog.maps().get(mapIndex), false);
            }
            ctl.update(dtNanos / 1e9);
            window.refreshSize();
            renderFrame(now - start);
            frame++;
            if (shot && frame >= opts.frames()) {
                long alloc = mx.getCurrentThreadAllocatedBytes() - allocMark;
                Path out = Path.of(opts.screenshot());
                Screenshot.save(out, window.framebufferWidth(), window.framebufferHeight());
                System.out.printf("screenshot %s (%dx%d) after %d frames, %d world copies, %d draw calls/frame%n"
                        + "  frame: mean %.3f ms (%.0f fps), p99 %.2f ms, max %.2f ms; cpu before swap: mean %.3f ms%n"
                        + "  render thread allocated %d bytes over frames %d..%d%n",
                    out, window.framebufferWidth(), window.framebufferHeight(), frame, cam.offsetCount(),
                    gl.drawCalls(), stats.runMeanMillis(), 1000 / stats.runMeanMillis(), stats.p99Millis(),
                    stats.runMaxMillis(), stats.runCpuMeanMillis(), alloc, opts.frames() / 2, frame);
                window.close();
            }
            prevCpu = System.nanoTime() - now;
            window.swap();
        }
    }

    private void renderFrame(long elapsedNanos) {
        gl.resetStats();
        ctl.applyTo(cam);
        cam.setViewport(window.framebufferWidth(), window.framebufferHeight(), window.pixelRatio());
        cam.update();
        ctl.syncFrom(cam);
        state.setLoops((long) (elapsedNanos / 1e9 * ANIMATION_FPS));

        glViewport(0, 0, window.framebufferWidth(), window.framebufferHeight());
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);

        GlRenderer.Mesh walls = filled ? filledWalls : outlineWalls;
        for (int k = 0; k < cam.offsetCount(); k++) {
            cam.worldToNdc(k, xf);
            gl.setTransform(xf);
            gl.draw(features);
            gl.draw(walls);
        }

        cam.screenToNdc(xf);
        gl.setTransform(xf);
        dyn.clear();
        symbols.draw(dyn, text, cam, state);
        overlay.draw(dyn, text, cam, stats, mapLabel, radarTex, showRadar);
        gl.flush(dyn);
        dyn.clear();
    }

    private void dispose() {
        if (gl != null) {
            gl.delete(outlineWalls);
            gl.delete(filledWalls);
            gl.delete(features);
            gl.dispose();
        }
        if (atlas != null) {
            atlas.dispose();
        }
        if (dynMem != null) {
            MemoryUtil.memFree(dynMem);
        }
        if (window != null) {
            window.dispose();
        }
    }
}
