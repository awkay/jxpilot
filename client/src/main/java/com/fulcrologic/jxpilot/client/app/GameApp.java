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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.gl.GlRenderer;
import com.fulcrologic.jxpilot.client.gl.Screenshot;
import com.fulcrologic.jxpilot.client.gl.UiAtlas;
import com.fulcrologic.jxpilot.client.gl.Window;
import com.fulcrologic.jxpilot.client.map.MapSymbols;
import com.fulcrologic.jxpilot.client.map.RadarImage;
import com.fulcrologic.jxpilot.client.map.StaticMap;
import com.fulcrologic.jxpilot.client.paint.HudListPainter;
import com.fulcrologic.jxpilot.client.paint.HudPainter;
import com.fulcrologic.jxpilot.client.paint.PaintConfig;
import com.fulcrologic.jxpilot.client.paint.RadarPainter;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.client.paint.ScenePainter;
import com.fulcrologic.jxpilot.client.paint.ScreenSpace;
import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.client.render.WinScale;
import com.fulcrologic.jxpilot.client.xp.GameConnection;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The playable client: a GLFW window running the game loop over a {@link GameConnection}. Layout as the
 * C client's top-level window: a 256-pixel panel on the left (radar on top, score list below) and the
 * game view to its right. Per frame: poll events, {@link ClientFrame#step} (network, game, input, scene
 * and HUD), rebuild the map meshes and radar picture when the map changes, then draw the static map,
 * map symbols, scene, HUD, radar and score list. Thin GL glue; the logic it calls is tested.
 */
public final class GameApp {
    /** Panel width: the radar's 256 pixels (C {@code draw} window starts at x = 258). */
    public static final int PANEL_W = RadarPainter.WIDTH;
    /** Gap between the panel and the game view. */
    public static final int PANEL_GAP = 2;
    static final int DYNAMIC_VERTICES = 65536;
    static final int DYNAMIC_SEGMENTS = 32768;
    static final int DYNAMIC_POINTS = 16384;

    /** Per-frame hook for scripted runs (dev harness): called before the frame's step. */
    public interface Script {
        /** @param frame 0-based frame number */
        void beforeFrame(int frame, ClientFrame f, long nowNanos);

        /** Called after the frame's step and drawing, before the buffer swap. */
        default void afterFrame(int frame, ClientFrame f, long nowNanos) { }

        /**
         * A framebuffer rectangle {x, y, w, h} (pixels, origin bottom-left) to read back without encoding
         * after this frame is drawn (for frame-by-frame captures that must not stall the loop), or null.
         */
        default int[] grabAfter(int frame, int fbWidth, int fbHeight) { return null; }

        /** The pixels of a {@link #grabAfter} rectangle (RGBA rows bottom-up, valid only during the call). */
        default void grabbed(int frame, java.nio.ByteBuffer rgba, int w, int h) { }

        /** A PNG path to save after this frame is drawn, or null. */
        default Path screenshotAfter(int frame) { return null; }

        /** True to end the run after this frame. */
        default boolean done(int frame) { return false; }
    }

    private final AppOptions opts;
    private final GameConnection conn;
    private final ClientFrame frame;
    private final Script script;
    private final String label;
    private final Palette palette = Palette.xpilotDefault();
    private final Camera cam = new Camera();
    private final ScreenSpace viewScreen = new ScreenSpace();
    private final ScreenSpace panelScreen = new ScreenSpace();
    private final FontMetrics metrics = new FontMetrics();
    private final ScenePainter scenePainter = new ScenePainter(palette);
    private final RadarPainter radarPainter = new RadarPainter(palette);
    private final float[] xf = new float[4];
    private final HudFrameList viewList;
    private final HudFrameList panelList;
    private double zoom;
    private double altScaleFactor;

    private Window window;
    private GlRenderer gl;
    private UiAtlas atlas;
    private TextWriter text;
    private HudListPainter hudList;
    private HudPainter hudPainter;
    private ByteBuffer dynTri;
    private ByteBuffer dynSeg;
    private ByteBuffer dynPt;
    private VertexBatch dyn;
    private GlRenderer.Mesh mesh;
    private MapSymbols symbols;
    private WorldMap map;
    private int mapVersion;
    private int radarHeight;
    private long targetsKey = -1;

    /**
     * @param label  server label for the window title
     * @param script dev hook, or null
     */
    public GameApp(AppOptions opts, ClientGame game, GameConnection conn, String label, Script script) {
        this.opts = opts;
        this.conn = conn;
        this.label = label;
        this.script = script;
        this.frame = new ClientFrame(game, conn, new InputState(KeyRouter.bindings(opts.swapScaleKey())),
            new SceneData(), new RadarBlips(1024));
        this.viewList = new HudFrameList(frame.viewHud());
        this.panelList = new HudFrameList(frame.panelHud());
        this.zoom = WinScale.zoomOf(opts.scaleFactor());
        this.altScaleFactor = opts.altScaleFactor();
    }

    public ClientFrame frame() {
        return frame;
    }

    /** Open the window, connect and run until quit, window close or the screenshot frame count. */
    public void run() throws IOException {
        window = new Window("JXPilot - " + label, opts.width(), opts.height(), true, opts.vsync());
        try {
            atlas = new UiAtlas(opts.fontFile() == null ? null : Path.of(opts.fontFile()), fontPixels());
            gl = new GlRenderer(atlas.texture());
            makeText();
            dynTri = MemoryUtil.memAlloc(DYNAMIC_VERTICES * VertexBatch.STRIDE);
            dynSeg = MemoryUtil.memAlloc(Math.min(DYNAMIC_SEGMENTS, gl.maxSegments()) * VertexBatch.SEGMENT_STRIDE);
            dynPt = MemoryUtil.memAlloc(Math.min(DYNAMIC_POINTS, gl.maxPoints()) * VertexBatch.POINT_STRIDE);
            dyn = VertexBatch.fixed(dynTri, dynSeg, dynPt, 256, gl);
            installInput();
            conn.start(System.nanoTime());
            loop();
        } finally {
            frame.disconnect(System.nanoTime());
            conn.close();
            dispose();
        }
    }

    private float fontPixels() {
        return (float) (opts.fontSize() * window.pixelRatio());
    }

    private void makeText() {
        text = new TextWriter(atlas.font());
        hudList = HudListPainter.xpilot(text, palette);
        hudPainter = new HudPainter(text, palette);
    }

    private void installInput() {
        long h = window.handle();
        glfwSetKeyCallback(h, (w, key, sc, action, mods) -> frame.key(key, action, mods, System.nanoTime()));
        glfwSetCharCallback(h, (w, cp) -> frame.character(cp));
        glfwSetWindowFocusCallback(h, (w, focused) -> {
            if (!focused) {
                frame.focusLost();
            }
        });
    }

    private void loop() throws IOException {
        int n = 0;
        while (!window.shouldClose()) {
            long now = System.nanoTime();
            glfwPollEvents();
            if (script != null) {
                script.beforeFrame(n, frame, now);
            }
            if (frame.quitRequested()) {
                break;
            }
            if (frame.takeSwapScale()) {
                double current = 1 / zoom;
                zoom = WinScale.zoomOf(altScaleFactor);
                altScaleFactor = current;
            }
            window.refreshSize();
            rebakeFontIfNeeded();
            renderFrame(now);
            if (script != null) {
                script.afterFrame(n, frame, now);
                int[] g = script.grabAfter(n, window.framebufferWidth(), window.framebufferHeight());
                if (g != null) {
                    grab(n, g);
                }
            }
            Path shot = script != null ? script.screenshotAfter(n) : null;
            boolean last = opts.screenshot() != null && n + 1 >= opts.frames();
            if (last && shot == null) {
                shot = Path.of(opts.screenshot());
            }
            if (shot != null) {
                if (shot.getParent() != null) {
                    Files.createDirectories(shot.getParent());
                }
                Screenshot.save(shot, window.framebufferWidth(), window.framebufferHeight());
                System.out.println("screenshot " + shot + " frame " + n + ": " + conn.status());
            }
            window.swap();
            n++;
            if (last || script != null && script.done(n - 1)) {
                break;
            }
        }
    }

    private void grab(int n, int[] r) {
        ByteBuffer px = MemoryUtil.memAlloc(r[2] * r[3] * 4);
        try {
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadBuffer(GL_BACK);
            glReadPixels(r[0], r[1], r[2], r[3], GL_RGBA, GL_UNSIGNED_BYTE, px);
            script.grabbed(n, px, r[2], r[3]);
        } finally {
            MemoryUtil.memFree(px);
        }
    }

    private void rebakeFontIfNeeded() throws IOException {
        float px = fontPixels();
        if (px != atlas.fontPixels()) {
            atlas.bakeFont(px);
            makeText();
        }
    }

    private void renderFrame(long now) {
        int fbW = window.framebufferWidth();
        int fbH = window.framebufferHeight();
        double ratio = window.pixelRatio();
        int panelFb = (int) Math.round((PANEL_W + PANEL_GAP) * ratio);
        int viewFbW = Math.max(1, fbW - panelFb);
        int viewW = (int) (viewFbW / ratio);
        int viewH = (int) (fbH / ratio);
        metrics.set(atlas.font(), ratio);
        frame.step(now, viewW, viewH, zoom, metrics, PANEL_W, radarHeight + ClientFrame.SCORE_GAP);
        if (frame.mapVersion() != mapVersion) {
            mapVersion = frame.mapVersion();
            loadMap(frame.game().map());
        }
        updateRadarPicture();
        cam.setZoom(zoom);
        cam.setCentre(frame.centreX(), frame.centreY());
        cam.setViewport(viewFbW, fbH, ratio);
        cam.update();

        glViewport(0, 0, fbW, fbH);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);

        // ---- game view
        glViewport(panelFb, 0, viewFbW, fbH);
        gl.setView(viewFbW, fbH, ratio);
        cam.screenToNdc(xf);
        gl.setTransform(xf);
        dyn.clear();
        SceneData scene = frame.scene();
        if (scenePainter.damagedFlash(dyn, cam, scene)) {
            gl.setPointScale(0);
            gl.flush(dyn);
            dyn.clear();
            return;
        }
        viewScreen.set(fbH, ratio);
        if (map != null) {
            gl.setPointScale(zoom);
            for (int k = 0; k < cam.offsetCount(); k++) {
                cam.worldToNdc(k, xf);
                gl.setTransform(xf);
                gl.draw(mesh);
            }
            gl.setPointScale(0);
            cam.screenToNdc(xf);
            gl.setTransform(xf);
            symbols.draw(dyn, text, cam, frame.mapState());
            scenePainter.drawBody(dyn, text, cam, scene);
        }
        hud(false);
        if (map != null) {
            symbols.drawArcs(dyn, cam, frame.mapState());
            scenePainter.drawArcs(dyn, cam, scene);
        }
        hud(true);
        gl.flush(dyn);
        dyn.clear();

        // ---- panel: radar and score list
        glViewport(0, 0, panelFb, fbH);
        gl.setView(panelFb, fbH, ratio);
        xf[0] = 2f / panelFb;
        xf[1] = 2f / fbH;
        xf[2] = -1;
        xf[3] = -1;
        gl.setTransform(xf);
        panelScreen.set(fbH, ratio);
        if (map != null) {
            radar(ratio, fbH, scene.loops);
        }
        hudList.setScale(1);
        hudList.paint(dyn, panelScreen, panelList);
        gl.flush(dyn);
        dyn.clear();
    }

    /** The view HUD: the draw list and the directly painted parts; {@code arcs}: only C's Arc_add parts. */
    private void hud(boolean arcs) {
        HudExtras e = frame.extras();
        int hudColor = PaintConfig.HUD_COLOR;
        dyn.identity();
        hudPainter.setScale(zoom);
        if (arcs) {
            if (e.active && e.lock && e.lockFriend) {
                hudPainter.paintLockDisc(dyn, viewScreen, e.hx, e.hy, e.lockDist, e.lockDir, e.mapDiag, true,
                    e.warningPhase, hudColor, PaintConfig.HUD_LOCK_COLOR);
            }
            return;
        }
        hudList.setScale(zoom);
        hudList.paint(dyn, viewScreen, viewList);
        for (int i = 0; i < e.scoreObjectCount(); i++) {
            hudPainter.paintScoreObject(dyn, viewScreen, frame.game().scoreObjects().text(e.scoreObjectSlot(i)),
                e.scoreObjectX(i), e.scoreObjectY(i), hudColor);
        }
        if (!e.active) {
            return;
        }
        if (e.lock) {
            hudPainter.paintLockText(dyn, viewScreen, e.hx, e.hy, e.lockName, e.limitedLives, e.lockLives, e.lockDist,
                hudColor);
            if (HudPainter.lockShipShown(zoom)) {
                hudPainter.paintLockShip(dyn, viewScreen, e.hx, e.hy, e.lockShipX, e.lockShipY, e.lockShipPoints,
                    hudColor);
            }
            if (!e.lockFriend) {
                hudPainter.paintLockDisc(dyn, viewScreen, e.hx, e.hy, e.lockDist, e.lockDir, e.mapDiag, false,
                    e.warningPhase, hudColor, PaintConfig.HUD_LOCK_COLOR);
            }
        }
        hudPainter.paintItems(dyn, viewScreen, e.hx, e.hy, e.itemCounts, -1, false, hudColor);
        if (e.autopilot) {
            hudPainter.paintAutopilot(dyn, viewScreen, e.hx, e.hy, hudColor);
        }
    }

    private void radar(double ratio, int fbH, long loops) {
        float left = 0;
        float top = fbH;
        float right = (float) (RadarImage.WIDTH * ratio);
        float bottom = (float) (fbH - radarHeight * ratio);
        float u0 = (float) UiAtlas.RADAR_X / UiAtlas.SIZE;
        float u1 = u0 + (float) RadarImage.WIDTH / UiAtlas.SIZE;
        float v1 = (float) atlas.radarHeight() / UiAtlas.SIZE;
        dyn.identity();
        dyn.texRect(left, bottom, right, top, u0, v1, u1, 0, palette.color(Palette.WHITE));
        radarPainter.begin(left, bottom, right, top, radarHeight, map.pixelWidth(), map.pixelHeight(), map.edgeWrap());
        RadarBlips r = frame.radar();
        if (r.selfVisible) {
            radarPainter.self(dyn, r.selfX, r.selfY, r.selfHeading, loops);
            radarPainter.selfBlip(dyn, r.selfX, r.selfY);
        }
        for (int i = 0; i < r.count(); i++) {
            radarPainter.blipByte(dyn, r.x(i), r.y(i), r.sizeByte(i));
        }
    }

    /** New map: static meshes, symbols, radar picture, camera world and window title. */
    private void loadMap(WorldMap m) {
        gl.delete(mesh);
        mesh = null;
        map = m;
        targetsKey = -1;
        if (m == null) {
            return;
        }
        mesh = gl.upload(StaticMap.build(m, palette, false, true));
        symbols = new MapSymbols(m, palette);
        radarHeight = RadarImage.height(m);
        cam.setWorld(m.pixelWidth(), m.pixelHeight(), m.edgeWrap());
        window.setTitle("JXPilot - " + (m.name() != null ? m.name() : "untitled map") + " - " + label);
    }

    /** Rebuild the radar picture when the set of destroyed targets changes (C redraws it then). */
    private void updateRadarPicture() {
        if (map == null) {
            return;
        }
        long key = 0;
        int n = Math.min(64, map.targets().size());
        for (int i = 0; i < n; i++) {
            if (!frame.mapState().targetAlive(i)) {
                key |= 1L << i;
            }
        }
        if (key != targetsKey) {
            targetsKey = key;
            atlas.setRadar(RadarImage.build(map, palette, true, i -> frame.mapState().targetAlive(i)), radarHeight);
        }
    }

    private void dispose() {
        if (gl != null) {
            gl.delete(mesh);
            gl.dispose();
        }
        if (atlas != null) {
            atlas.dispose();
        }
        if (dynTri != null) {
            MemoryUtil.memFree(dynTri);
            MemoryUtil.memFree(dynSeg);
            MemoryUtil.memFree(dynPt);
        }
        if (window != null) {
            window.dispose();
        }
    }
}
