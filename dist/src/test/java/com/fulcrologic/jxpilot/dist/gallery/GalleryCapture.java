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
package com.fulcrologic.jxpilot.dist.gallery;

import com.fulcrologic.jxpilot.client.app.AppOptions;
import com.fulcrologic.jxpilot.client.app.RoleFontSet;
import com.fulcrologic.jxpilot.client.app.RoleFonts;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.gl.FontBaker;
import com.fulcrologic.jxpilot.client.gl.GlRenderer;
import com.fulcrologic.jxpilot.client.game.hud.FontRole;
import com.fulcrologic.jxpilot.client.render.FontSpec;
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
import com.fulcrologic.jxpilot.client.viewer.DemoHud;
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Renders the guide's "What you see on screen" pictures with the real client drawing code (static map
 * meshes, {@link MapSymbols}, {@link ScenePainter}, the HUD painters and the radar), the same calls as
 * the map viewer's {@code --demo} mode, but with scripted scenes and map states from {@link GalleryShots}
 * instead of random ones, and a fixed frame counter, so every run gives the same pictures. Text uses the
 * client's per-role fonts at their defaults ({@link RoleFonts}: C's gameFont and the others, drawn with the
 * bundled Liberation faces), as the game does. The panel, windows and look options come from the real
 * client instead ({@code capture-gallery.sh} runs the client's DevRun for them).
 * <p>
 * {@code GalleryCapture MAPFILE OUTDIR FRAMEDIR}: still pictures go to OUTDIR as PNGs; animations go
 * to FRAMEDIR/NAME/fNNN.png for {@code capture-gallery.sh} to turn into GIFs. Needs a display (GL).
 */
public final class GalleryCapture {
    /** Logical window size. */
    static final int WIDTH = 1280;
    static final int HEIGHT = 800;
    static final int DYNAMIC_VERTICES = 65536;
    static final int DYNAMIC_SEGMENTS = 32768;
    static final int DYNAMIC_POINTS = 16384;

    private final Palette palette = Palette.xpilotDefault();
    private final Camera cam = new Camera();
    private final SceneData scene = new SceneData();
    private final ScenePainter scenePainter = new ScenePainter(palette);
    private final RadarPainter radarPainter = new RadarPainter(palette);
    private final DemoHud demoHud = new DemoHud();
    private final ScreenSpace screen = new ScreenSpace();
    private final float[] xf = new float[4];

    private Window window;
    private GlRenderer gl;
    private UiAtlas atlas;
    private final RoleFontSet fonts = new RoleFontSet();
    private TextWriter text;
    private HudListPainter hudList;
    private HudPainter hudPainter;
    private VertexBatch dyn;
    private WorldMap map;
    private MapSymbols symbols;
    private GlRenderer.Mesh outlineMesh;
    private GlRenderer.Mesh filledMesh;
    private int radarHeight;

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: GalleryCapture OUTDIR FRAMEDIR MAPFILE...");
            System.exit(2);
        }
        List<Path> maps = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            maps.add(Path.of(args[i]));
        }
        new GalleryCapture().run(maps, Path.of(args[0]), Path.of(args[1]));
    }

    void run(List<Path> mapFiles, Path outDir, Path frameDir) throws IOException {
        window = new Window("JXPilot gallery capture", WIDTH, HEIGHT, true, false);
        ByteBuffer tri = null;
        ByteBuffer seg = null;
        ByteBuffer pt = null;
        try {
            double ratio = window.pixelRatio();
            atlas = new UiAtlas();
            gl = new GlRenderer(atlas.texture());
            FontSpec[] specs = RoleFonts.resolve(new ClientOptions(), null, AppOptions.FONT_SIZE_UNSET,
                FontBaker::isFontFile).specs();
            fonts.set(specs, atlas.bakeFonts(specs, ratio), ratio);
            text = fonts.writer(FontRole.GAME);
            hudList = HudListPainter.xpilot(fonts.writers(), palette);
            hudPainter = new HudPainter(text, palette);
            tri = MemoryUtil.memAlloc(DYNAMIC_VERTICES * VertexBatch.STRIDE);
            seg = MemoryUtil.memAlloc(Math.min(DYNAMIC_SEGMENTS, gl.maxSegments()) * VertexBatch.SEGMENT_STRIDE);
            pt = MemoryUtil.memAlloc(Math.min(DYNAMIC_POINTS, gl.maxPoints()) * VertexBatch.POINT_STRIDE);
            dyn = VertexBatch.fixed(tri, seg, pt, 256, gl);
            Files.createDirectories(outDir);
            for (Path mapFile : mapFiles) {
                loadMap(mapFile);
                capture(new GalleryShots(map), outDir, frameDir);
            }
        } finally {
            if (gl != null) {
                gl.delete(outlineMesh);
                gl.delete(filledMesh);
                gl.dispose();
            }
            if (atlas != null) {
                atlas.dispose();
            }
            if (tri != null) {
                MemoryUtil.memFree(tri);
                MemoryUtil.memFree(seg);
                MemoryUtil.memFree(pt);
            }
            window.dispose();
        }
    }

    private void loadMap(Path mapFile) throws IOException {
        gl.delete(outlineMesh);
        gl.delete(filledMesh);
        Options o = new Options();
        ConfigParser.parseFile(mapFile, OptionOrigin.MAP, o);
        map = WorldMap.fromOptions(o);
        outlineMesh = gl.upload(StaticMap.build(map, palette, false, true));
        filledMesh = gl.upload(StaticMap.build(map, palette, true, true));
        radarHeight = RadarImage.height(map);
        atlas.setRadar(RadarImage.build(map, palette, true), radarHeight);
        symbols = new MapSymbols(map, palette);
    }

    private void capture(GalleryShots shots, Path outDir, Path frameDir) throws IOException {
        for (GalleryShots.Shot s : shots.all()) {
            if (s.frames() == 1) {
                BufferedImage img = render(s, 0);
                ImageIO.write(img, "png", outDir.resolve(s.name() + ".png").toFile());
                System.out.println("wrote " + s.name() + ".png " + img.getWidth() + "x" + img.getHeight());
            } else {
                Path dir = frameDir.resolve(s.name());
                Files.createDirectories(dir);
                for (int f = 0; f < s.frames(); f++) {
                    BufferedImage img = render(s, f);
                    ImageIO.write(img, "png", dir.resolve(String.format("f%03d.png", f)).toFile());
                }
                System.out.println("wrote " + s.frames() + " frames of " + s.name());
            }
        }
    }

    /** Render frame {@code f} of shot {@code s} and return its crop. */
    BufferedImage render(GalleryShots.Shot s, int f) {
        glfwPollEvents();
        window.refreshSize();
        int fbW = window.framebufferWidth();
        int fbH = window.framebufferHeight();
        double ratio = window.pixelRatio();
        long loops = s.loops0() + f;
        GalleryShots.State st = s.state();
        st.loops = loops;
        scene.clear();
        scene.loops = loops;
        scene.fps = 14;
        scene.selfId = -1;
        scene.damaged = 0;
        scene.roundDelay = 0;
        s.scene().fill(scene, loops);

        cam.setWorld(map.pixelWidth(), map.pixelHeight(), map.edgeWrap());
        cam.setViewport(fbW, fbH, ratio);
        cam.setCentre(s.x(), s.y());
        cam.setZoom(s.zoom());
        cam.update();

        glViewport(0, 0, fbW, fbH);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        gl.resetStats();
        gl.setView(fbW, fbH, ratio);
        dyn.clear();
        if (s.map()) {
            gl.setPointScale(cam.zoom());
            for (int k = 0; k < cam.offsetCount(); k++) {
                cam.worldToNdc(k, xf);
                gl.setTransform(xf);
                gl.draw(s.filled() ? filledMesh : outlineMesh);
            }
        }
        gl.setPointScale(0);
        cam.screenToNdc(xf);
        gl.setTransform(xf);
        if (s.map()) {
            symbols.draw(dyn, text, cam, st);
        }
        scenePainter.drawBody(dyn, text, cam, scene);
        if (s.hud()) {
            hud(false, s, loops);
        }
        if (s.map()) {
            symbols.drawArcs(dyn, cam, st);
        }
        scenePainter.drawArcs(dyn, cam, scene);
        if (s.hud()) {
            hud(true, s, loops);
        }
        if (s.radar()) {
            radar(s, loops);
        }
        gl.flush(dyn);
        dyn.clear();
        glFinish();
        BufferedImage img = grab(fbW, fbH);
        window.swap();
        return crop(img, s, ratio);
    }

    /** The HUD as the viewer's demo draws it: the demo draw list plus the directly painted pieces. */
    private void hud(boolean arcs, GalleryShots.Shot s, long loops) {
        screen.set(window.framebufferHeight(), window.pixelRatio());
        double zoom = cam.zoom();
        int viewW = screen.logicalWidth(window.framebufferWidth());
        int viewH = screen.logicalHeight();
        int hudColor = PaintConfig.HUD_COLOR;
        dyn.identity();
        hudPainter.setScale(zoom);
        int hx = (int) (viewW / zoom) / 2;
        int hy = (int) (viewH / zoom) / 2;
        GalleryShots.Hud h = s.hudData();
        if (arcs) {
            return;
        }
        int scoreTop = (int) (8 + (s.radar() ? radarHeight : 0) + 4);
        demoHud.build(viewW, viewH, scoreTop, loops, text, window.pixelRatio(), zoom);
        hudList.setScale(zoom);
        hudList.paint(dyn, screen, demoHud);
        ShipShape shape = ShipShape.defaultShip();
        float[] xs = new float[shape.numPoints()];
        float[] ys = new float[shape.numPoints()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = shape.ptX[i];
            ys[i] = shape.ptY[i];
        }
        hudPainter.paintLockText(dyn, screen, hx, hy, h.lockName(), false, 0, h.lockDist(), hudColor);
        hudPainter.paintLockShip(dyn, screen, hx, hy, xs, ys, xs.length, hudColor);
        hudPainter.paintLockDisc(dyn, screen, hx, hy, h.lockDist(), h.lockDir(),
            HudPainter.mapDiag(map.width(), map.height()), false, true, hudColor, PaintConfig.HUD_LOCK_COLOR);
        hudPainter.paintItems(dyn, screen, hx, hy, h.items(), -1, false, hudColor);
        hudPainter.paintTimeLeft(dyn, screen, hx, hy, h.timeLeft(), hudColor);
        hudPainter.paintMods(dyn, screen, hx, hy, h.mods(), hudColor);
        hudPainter.paintScoreObject(dyn, screen, h.scoreText(), hx + 240, hy + 110, hudColor);
    }

    /** The radar picture (top-left) with the own heading line and blips, as the viewer draws it. */
    private void radar(GalleryShots.Shot s, long loops) {
        float r = (float) window.pixelRatio();
        float m = 8 * r;
        float x0 = m;
        float y1 = cam.viewHeight() - m;
        float x1 = x0 + RadarImage.WIDTH * r;
        float y0 = y1 - radarHeight * r;
        float u0 = (float) UiAtlas.RADAR_X / UiAtlas.SIZE;
        float u1 = u0 + (float) RadarImage.WIDTH / UiAtlas.SIZE;
        float v1 = (float) radarHeight / UiAtlas.SIZE;
        dyn.identity();
        dyn.texRect(x0, y0, x1, y1, u0, v1, u1, 0, palette.color(Palette.WHITE));
        dyn.strokeRect(x0, y0, x1, y1, palette.color(Palette.HUD_COLOR));
        radarPainter.begin(x0, y0, x1, y1, radarHeight, map.pixelWidth(), map.pixelHeight(), map.edgeWrap());
        for (int i = 0; i < scene.shipCount; i++) {
            if (scene.shipId[i] == scene.selfId) {
                radarPainter.self(dyn, scene.shipX[i], scene.shipY[i], scene.shipHeading[i], loops);
                radarPainter.selfBlip(dyn, scene.shipX[i], scene.shipY[i]);
            } else {
                radarPainter.blip(dyn, scene.shipX[i], scene.shipY[i], 3, scene.shipColor[i] == Palette.BLUE);
            }
        }
        for (int i = 0; i < scene.asteroidCount; i++) {
            radarPainter.blipByte(dyn, scene.asteroidX[i], scene.asteroidY[i],
                RadarPainter.asteroidSizeByte(scene.asteroidSize[i]));
        }
    }

    private static BufferedImage grab(int w, int h) {
        ByteBuffer px = MemoryUtil.memAlloc(w * h * 4);
        try {
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadBuffer(GL_BACK);
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = ((h - 1 - y) * w + x) * 4;
                    img.setRGB(x, y, (px.get(i) & 0xFF) << 16 | (px.get(i + 1) & 0xFF) << 8 | (px.get(i + 2) & 0xFF));
                }
            }
            return img;
        } finally {
            MemoryUtil.memFree(px);
        }
    }

    /**
     * The shot's crop: {@code cropW x cropH} logical pixels, centred on the view centre plus the shot's
     * offset (or anchored at the top-left / bottom-left for screen-anchored HUD parts), in framebuffer pixels.
     */
    private static BufferedImage crop(BufferedImage img, GalleryShots.Shot s, double ratio) {
        int w = (int) Math.round(s.cropW() * ratio);
        int h = (int) Math.round(s.cropH() * ratio);
        int x;
        int y;
        switch (s.anchor()) {
            case TOP_LEFT -> {
                x = (int) Math.round(s.cropX() * ratio);
                y = (int) Math.round(s.cropY() * ratio);
            }
            case BOTTOM_LEFT -> {
                x = (int) Math.round(s.cropX() * ratio);
                y = img.getHeight() - h - (int) Math.round(s.cropY() * ratio);
            }
            default -> {
                x = img.getWidth() / 2 - w / 2 + (int) Math.round(s.cropX() * ratio);
                y = img.getHeight() / 2 - h / 2 + (int) Math.round(s.cropY() * ratio);
            }
        }
        x = Math.clamp(x, 0, img.getWidth() - w);
        y = Math.clamp(y, 0, img.getHeight() - h);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(img.getSubimage(x, y, w, h), 0, 0, null);
        return out;
    }
}
