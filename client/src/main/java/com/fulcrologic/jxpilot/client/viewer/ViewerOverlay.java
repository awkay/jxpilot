package com.fulcrologic.jxpilot.client.viewer;

import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.FrameStats;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;

/**
 * Screen-space overlay of the map viewer: the radar (top-left, {@code radarWidth x radarHeight}
 * logical pixels, scaled by the pixel ratio) with a {@code hudColor} frame and a white marker at the
 * camera centre, frame statistics and camera info (top-right, right-aligned) and a key help line
 * (bottom-left). Allocation-free.
 */
public final class ViewerOverlay {
    /** Logical-pixel margin from the window edges. */
    public static final float MARGIN = 8;
    /** Marker half-size in logical pixels. */
    public static final float MARKER = 2;

    static final String FPS = "FPS ";
    static final String SPACE = " ";
    static final String MS = " ms  max ";
    static final String MS_END = " ms";
    static final String ZOOM = "zoom ";
    static final String AT = "  at ";
    static final String COMMA = ", ";
    static final String HELP = "arrows: pan   +/-: zoom   Tab: next map   F: filled walls   R: radar   Esc: quit";

    private final Palette palette;

    public ViewerOverlay(Palette palette) {
        this.palette = palette;
    }

    /** Where the radar quad goes, in texture coordinates of the UI atlas. */
    public record RadarTexture(int width, int height, float u0, float v0, float u1, float v1) {}

    /** Draw the overlay (sets the batch transform to identity). */
    public void draw(VertexBatch b, TextWriter text, Camera cam, FrameStats stats, CharSequence mapName,
                     RadarTexture radar, boolean showRadar) {
        b.identity();
        float r = (float) cam.pixelRatio();
        float m = MARGIN * r;
        int hud = palette.color(Palette.HUD_COLOR);
        int white = palette.color(Palette.WHITE);
        if (showRadar && radar != null) {
            radar(b, cam, radar, r, m, hud, white);
        }
        float line = text.font().lineHeight();
        float right = cam.viewWidth() - m;
        float base = cam.viewHeight() - m - text.font().ascent();
        statsLine(b, text, stats, right, base, hud);
        rightAligned(b, text, mapName, right, base - line, white);
        cameraLine(b, text, cam, right, base - 2 * line, hud);
        text.draw(b, HELP, m, m + text.font().descent(), hud);
    }

    void radar(VertexBatch b, Camera cam, RadarTexture t, float r, float m, int frame, int marker) {
        float x0 = m;
        float y1 = cam.viewHeight() - m;
        float x1 = x0 + t.width() * r;
        float y0 = y1 - t.height() * r;
        b.texRect(x0, y0, x1, y1, t.u0(), t.v1(), t.u1(), t.v0(), palette.color(Palette.WHITE));
        b.strokeRect(x0, y0, x1, y1, frame);
        double fx = wrap01(cam.centreX() / cam.worldWidth());
        double fy = wrap01(cam.centreY() / cam.worldHeight());
        float px = (float) (x0 + fx * (x1 - x0));
        float py = (float) (y0 + fy * (y1 - y0));
        float h = MARKER * r;
        b.fillRect(px - h, py - h, px + h, py + h, marker);
    }

    /** Fraction clamped to [0,1] (the viewer may fly off a non-wrapping map). */
    static double wrap01(double f) {
        return f < 0 ? 0 : (f > 1 ? 1 : f);
    }

    private void statsLine(VertexBatch b, TextWriter t, FrameStats s, float right, float base, int c) {
        float w = t.width(FPS) + t.widthFixed(s.fps(), 1) + t.width(SPACE) + t.widthFixed(s.averageMillis(), 2)
            + t.width(MS) + t.widthFixed(s.maxMillis(), 2) + t.width(MS_END);
        float x = right - w;
        x = t.draw(b, FPS, x, base, c);
        x = t.drawFixed(b, s.fps(), 1, x, base, c);
        x = t.draw(b, SPACE, x, base, c);
        x = t.drawFixed(b, s.averageMillis(), 2, x, base, c);
        x = t.draw(b, MS, x, base, c);
        x = t.drawFixed(b, s.maxMillis(), 2, x, base, c);
        t.draw(b, MS_END, x, base, c);
    }

    private void cameraLine(VertexBatch b, TextWriter t, Camera cam, float right, float base, int c) {
        int cx = (int) cam.centreX();
        int cy = (int) cam.centreY();
        float w = t.width(ZOOM) + t.widthFixed(cam.zoom(), 2) + t.width(AT) + t.widthInt(cx) + t.width(COMMA)
            + t.widthInt(cy);
        float x = right - w;
        x = t.draw(b, ZOOM, x, base, c);
        x = t.drawFixed(b, cam.zoom(), 2, x, base, c);
        x = t.draw(b, AT, x, base, c);
        x = t.drawInt(b, cx, x, base, c);
        x = t.draw(b, COMMA, x, base, c);
        t.drawInt(b, cy, x, base, c);
    }

    private static void rightAligned(VertexBatch b, TextWriter t, CharSequence s, float right, float base, int c) {
        t.draw(b, s, right - t.width(s), base, c);
    }
}
