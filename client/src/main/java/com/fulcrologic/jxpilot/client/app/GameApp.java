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
import com.fulcrologic.jxpilot.client.game.hud.FontRole;
import com.fulcrologic.jxpilot.client.game.input.KeyConfig;
import com.fulcrologic.jxpilot.client.gl.FontBaker;
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
import com.fulcrologic.jxpilot.client.render.FontSpec;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.client.render.WinScale;
import com.fulcrologic.jxpilot.client.sound.SoundPlayer;
import com.fulcrologic.jxpilot.client.sound.SoundSystem;
import com.fulcrologic.jxpilot.client.xp.GameConnection;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
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
 * map symbols, scene, HUD, radar and score list, and play the frame's sounds ({@link SoundPlayer}). Thin GL
 * glue; the logic it calls is tested.
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
    /** The per-role fonts (C gameFont, messageFont, ...; RoleFonts), rebaked on a pixel ratio or font change. */
    private final RoleFontSet fonts = new RoleFontSet();
    private int fontOptionsVersion = -1;
    private FontSpec[] wantedFonts;
    private final java.util.Set<String> fontProblems = new java.util.HashSet<>();
    private final ScenePainter scenePainter = new ScenePainter(palette);
    private final RadarPainter radarPainter = new RadarPainter(palette);
    private final float[] xf = new float[4];
    private final HudFrameList viewList;
    private final HudFrameList panelList;
    private final HudFrameList helpList;
    private final HudFrameList overlayList;
    private final HudFrameList aboutList;
    private double zoom;
    private int zoomVersion = -1;
    private double cursorX;
    private double cursorY;

    private Window window;
    private SoundPlayer sound;
    private final int maxVolume;
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
    private final TargetWatch targets = new TargetWatch();
    private final java.util.function.IntPredicate targetAlive = this::targetAlive;
    private int[] radarPixels = new int[0];

    /**
     * @param label  server label for the window title
     * @param script dev hook, or null
     */
    public GameApp(AppOptions opts, ClientGame game, GameConnection conn, String label, Script script) {
        this(opts, game, conn, label, script, KeyConfig.defaults(opts.swapScaleKey()));
    }

    /** @param keys key bindings, control settings and key help */
    public GameApp(AppOptions opts, ClientGame game, GameConnection conn, String label, Script script,
                   KeyConfig keys) {
        this.opts = opts;
        this.maxVolume = AppLauncher.maxVolume(opts, keys);
        this.conn = conn;
        this.label = label;
        this.script = script;
        this.frame = new ClientFrame(game, conn, keys, new SceneData(), new RadarBlips(1024));
        this.viewList = new HudFrameList(frame.viewHud());
        this.panelList = new HudFrameList(frame.panelHud());
        this.helpList = new HudFrameList(frame.helpHud());
        this.overlayList = new HudFrameList(frame.ui().panelOverlay());
        this.aboutList = new HudFrameList(frame.ui().aboutHud());
        this.zoom = WinScale.zoomOf(frame.options().real(
            com.fulcrologic.jxpilot.client.game.options.ClientOption.SCALE_FACTOR));
    }

    /** Where CONFIG's "Save Configuration" writes, and the name it saves (C {@code Get_xpilotrc_file}). */
    public GameApp saveTarget(Path rcFile, String playerName) {
        frame.setSaveTarget(rcFile, playerName);
        return this;
    }

    public ClientFrame frame() {
        return frame;
    }

    /**
     * Open the window, connect and run until quit, window close or the screenshot frame count.
     *
     * @return the exit status: 1 when the server ended the session (C exits with an error then), else 0
     */
    public int run() throws IOException {
        Geometry g = geometry();
        int width = g != null && g.width() > 0 ? g.width() : opts.width();
        int height = g != null && g.height() > 0 ? g.height() : opts.height();
        window = new Window("JXPilot - " + label, width, height, true, opts.vsync());
        if (g != null && g.hasPosition()) {
            org.lwjgl.glfw.GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (mode != null) {
                glfwSetWindowPos(window.handle(), g.left(mode.width(), width), g.top(mode.height(), height));
            }
        }
        try {
            atlas = new UiAtlas();
            gl = new GlRenderer(atlas.texture());
            rebakeFontIfNeeded();
            dynTri = MemoryUtil.memAlloc(DYNAMIC_VERTICES * VertexBatch.STRIDE);
            dynSeg = MemoryUtil.memAlloc(Math.min(DYNAMIC_SEGMENTS, gl.maxSegments()) * VertexBatch.SEGMENT_STRIDE);
            dynPt = MemoryUtil.memAlloc(Math.min(DYNAMIC_POINTS, gl.maxPoints()) * VertexBatch.POINT_STRIDE);
            dyn = VertexBatch.fixed(dynTri, dynSeg, dynPt, 256, gl);
            installInput();
            sound = SoundSystem.open(opts.sound(), maxVolume, SoundSystem::openAl, System.out);
            conn.start(System.nanoTime());
            loop();
            return frame.closedByServer() ? 1 : 0;
        } finally {
            frame.disconnect(System.nanoTime());
            conn.close();
            dispose();
        }
    }

    /** The geometry resource (C {@code -geometry}; {@code --size} sets it too), or null; a bad one is reported. */
    private Geometry geometry() {
        com.fulcrologic.jxpilot.client.game.options.ClientOptions o = frame.options();
        if (!o.given(com.fulcrologic.jxpilot.client.game.options.ClientOption.GEOMETRY)) {
            return null;
        }
        String text = o.text(com.fulcrologic.jxpilot.client.game.options.ClientOption.GEOMETRY);
        Geometry g = Geometry.parse(text);
        if (g == null) {
            System.err.println("jxpilot: bad geometry '" + text + "' (WxH+X+Y), ignored");
        }
        return g;
    }

    private void makeText() {
        text = fonts.writer(FontRole.GAME);
        hudList = HudListPainter.xpilot(fonts.writers(), palette);
        hudPainter = new HudPainter(text, palette);
    }

    /** Every role's font from the font options (or --font/--font-size); new problems go to stderr, as C's. */
    private FontSpec[] resolveFonts() {
        RoleFonts.Resolution r = RoleFonts.resolve(frame.options(), opts.fontFile(), opts.fontSize(),
            FontBaker::isFontFile);
        for (String p : r.problems()) {
            if (fontProblems.add(p)) {
                System.err.println(p);
            }
        }
        return r.specs();
    }

    private void installInput() {
        long h = window.handle();
        glfwSetKeyCallback(h, (w, key, sc, action, mods) -> frame.key(key, action, mods, System.nanoTime()));
        glfwSetCharCallback(h, (w, cp) -> frame.character(cp));
        glfwSetCursorPosCallback(h, (w, x, y) -> {
            cursorX = x;
            cursorY = y;
            if (!frame.mouseMove(x, y, PANEL_W + PANEL_GAP)) {
                frame.viewMouseMove((int) x - (PANEL_W + PANEL_GAP), (int) y);
            }
        });
        glfwSetMouseButtonCallback(h, (w, button, action, mods) -> {
            if (!frame.mouseButton(button, action, cursorX, cursorY, PANEL_W + PANEL_GAP) && cursorX >= PANEL_W + PANEL_GAP) {
                frame.viewMouse(button, action, (int) cursorX - (PANEL_W + PANEL_GAP), (int) cursorY);
            }
        });
        frame.setClipboard(new ClientFrame.Clipboard() {
            @Override
            public String get() {
                return glfwGetClipboardString(h);
            }

            @Override
            public void set(String text) {
                glfwSetClipboardString(h, text);
            }
        });
        glfwSetWindowFocusCallback(h, (w, focused) -> {
            if (!focused) {
                frame.focusLost();
            } else {
                frame.focusGained();
            }
        });
        glfwSetScrollCallback(h, (w, dx, dy) -> frame.scroll(dy, System.nanoTime()));
    }

    /** Left edge of the game view in window coordinates (the panel is on the left). */
    private double viewLeft;
    private boolean cursorGrabbed;

    /** Pointer control: grab and hide the pointer while on (C XGrabPointer), and put it where C warps it. */
    private void applyPointer() {
        com.fulcrologic.jxpilot.client.game.input.PointerControl pc = frame.play().pointer();
        long h = window.handle();
        if (pc.grabbed() != cursorGrabbed) {
            cursorGrabbed = pc.grabbed();
            glfwSetInputMode(h, GLFW_CURSOR, cursorGrabbed ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        }
        if (pc.takeWarp() && cursorGrabbed) {
            glfwSetCursorPos(h, viewLeft + pc.mouseX(), pc.mouseY());
        }
    }

    private final FramePacer pacer = new FramePacer();

    private void loop() throws IOException {
        int n = 0;
        long prevFrame = 0;
        long swapNanos = FramePacer.FAST_SWAP_NANOS;
        while (!window.shouldClose()) {
            long h = window.handle();
            boolean hidden = glfwGetWindowAttrib(h, GLFW_ICONIFIED) == GLFW_TRUE
                || glfwGetWindowAttrib(h, GLFW_VISIBLE) == GLFW_FALSE;
            long early = prevFrame + pacer.minFrameNanos(opts.vsync(), hidden, swapNanos) - System.nanoTime();
            if (early > 0) {
                java.util.concurrent.locks.LockSupport.parkNanos(early);
            }
            long now = System.nanoTime();
            prevFrame = now;
            glfwPollEvents();
            if (script != null) {
                script.beforeFrame(n, frame, now);
            }
            if (frame.quitRequested()) {
                break;
            }
            if (frame.takeSwapScale()) {
                swapScaleFactors();
            }
            followScaleFactor();
            window.refreshSize();
            rebakeFontIfNeeded();
            renderFrame(now);
            sound(now);
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
            long beforeSwap = System.nanoTime();
            window.swap();
            swapNanos = System.nanoTime() - beforeSwap;
            n++;
            if (last || script != null && script.done(n - 1)) {
                break;
            }
        }
    }

    /** C {@code Key_press_swap_scalefactor}: scaleFactor and altScaleFactor change places. */
    private void swapScaleFactors() {
        com.fulcrologic.jxpilot.client.game.options.ClientOptions o = frame.options();
        double sf = o.real(com.fulcrologic.jxpilot.client.game.options.ClientOption.SCALE_FACTOR);
        o.setReal(com.fulcrologic.jxpilot.client.game.options.ClientOption.SCALE_FACTOR,
            o.real(com.fulcrologic.jxpilot.client.game.options.ClientOption.ALT_SCALE_FACTOR));
        o.setReal(com.fulcrologic.jxpilot.client.game.options.ClientOption.ALT_SCALE_FACTOR, sf);
    }

    /** The zoom follows the scaleFactor option (command line, file, CONFIG, swap key). */
    private void followScaleFactor() {
        com.fulcrologic.jxpilot.client.game.options.ClientOptions o = frame.options();
        if (o.version() != zoomVersion) {
            zoomVersion = o.version();
            zoom = WinScale.zoomOf(o.real(com.fulcrologic.jxpilot.client.game.options.ClientOption.SCALE_FACTOR));
        }
    }

    /** The frame's sounds, and the sound toggle key with a HUD message as feedback. */
    private void sound(long now) {
        if (frame.takeToggleSound()) {
            frame.game().messages().add(Msg.Text.KIND_SERVER, sound.toggleMuted() ? "Sound off." : "Sound on.");
        }
        if (frame.ui().maxVolume() != sound.maxVolume()) {
            sound.setMaxVolume(frame.ui().maxVolume());
        }
        sound.frame(frame.game().world().sounds(), frame.game().gameFps(),
            frame.game().world().arrivalIntervalSeconds(), now);
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

    /** Rebake the fonts when a font option changed (options version) or the window's pixel ratio did. */
    private void rebakeFontIfNeeded() throws IOException {
        int v = frame.options().version();
        if (v != fontOptionsVersion) {
            fontOptionsVersion = v;
            wantedFonts = resolveFonts();
        }
        double ratio = window.pixelRatio();
        if (fonts.needs(wantedFonts, ratio)) {
            fonts.set(wantedFonts, atlas.bakeFonts(wantedFonts, ratio), ratio);
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
        viewLeft = panelFb / ratio;
        frame.step(now, viewW, viewH, zoom, fonts, PANEL_W, radarHeight);
        applyPointer();
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
            // C paint.c: an ECM-damaged view is only the flash, but the side panel is still drawn (R11)
            gl.setPointScale(0);
            gl.flush(dyn);
            dyn.clear();
        } else {
            drawView(scene);
        }

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
        hudList.paint(dyn, panelScreen, overlayList);
        gl.flush(dyn);
        dyn.clear();
    }

    /** The game view: map, symbols, scene, HUD, key help (everything but an ECM flash). */
    private void drawView(SceneData scene) {
        int fbH = window.framebufferHeight();
        double ratio = window.pixelRatio();
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
        hudList.setScale(1);
        hudList.paint(dyn, viewScreen, helpList);
        hudList.paint(dyn, viewScreen, aboutList);
        aboutItems();
        gl.flush(dyn);
        dyn.clear();

    }

    /** The information window's item pictures (C {@code Paint_item} in the about window). */
    private void aboutItems() {
        com.fulcrologic.jxpilot.client.game.ui.AboutWindow a = frame.ui().about();
        for (int i = 0; i < a.itemCount(); i++) {
            hudPainter.paintItemIcon(dyn, viewScreen, a.item(i), a.itemX(i), a.itemY(i));
        }
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
        hudPainter.paintItems(dyn, viewScreen, e.hx, e.hy, e.itemCounts, e.loseItem, e.loseActive, hudColor);
        hudPainter.paintTimeLeft(dyn, viewScreen, e.hx, e.hy, e.timeLeft, hudColor);
        hudPainter.paintMods(dyn, viewScreen, e.hx, e.hy, e.mods, hudColor);
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
        // the next race checkpoint before the own ship (C Paint_checkpoint_radar, paintradar.c:133, 287;
        // SceneFiller.radarCheckpoint decides it)
        if (r.checkpointVisible) {
            radarPainter.checkpoint(dyn, r.checkpointBx, r.checkpointBy);
        }
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
        if (m == null) {
            return;
        }
        targets.reset(m.targets().size());
        if (radarPixels.length < RadarImage.WIDTH * RadarImage.height(m)) {
            radarPixels = new int[RadarImage.WIDTH * RadarImage.height(m)];
        }
        mesh = gl.upload(StaticMap.build(m, palette, false, true));
        symbols = new MapSymbols(m, palette);
        radarHeight = RadarImage.height(m);
        cam.setWorld(m.pixelWidth(), m.pixelHeight(), m.edgeWrap());
        window.setTitle("JXPilot - " + (m.name() != null ? m.name() : "untitled map") + " - " + label);
    }

    private boolean targetAlive(int i) {
        return frame.mapState().targetAlive(i);
    }

    /** Rebuild the radar picture when the set of destroyed targets changes (C redraws it then). */
    private void updateRadarPicture() {
        if (map == null || !targets.changed(frame.mapState())) {
            return;
        }
        atlas.setRadar(RadarImage.build(map, palette, true, targetAlive, radarPixels), radarHeight);
    }

    private void dispose() {
        if (sound != null) {
            sound.close();
        }
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
