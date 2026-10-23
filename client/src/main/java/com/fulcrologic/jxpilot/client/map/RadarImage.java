package com.fulcrologic.jxpilot.client.map;

import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.WorldMap;

/**
 * The static radar picture of the map (C {@code Paint_world_radar} in {@code paintradar.c}),
 * rendered once into an RGBA pixel array for a texture.
 * <p>
 * The radar is {@link #WIDTH} pixels wide and {@code WIDTH * mapHeight / mapWidth} high (C
 * {@code RadarHeight}). Walls and fuel use {@code wallRadarColor}, live targets
 * {@code targetRadarColor}, decorations {@code decorRadarColor} when shown; the rest is black.
 * Like C, a map at least {@link #WIDTH} blocks wide is walked block by block (columns map to radar
 * x, vertical runs of same-coloured blocks become line spans), and a narrower map is walked pixel by
 * pixel, sampling the nearest block.
 * <p>
 * Pixels are packed like {@link Palette} and stored row-major with row 0 at the TOP.
 */
public final class RadarImage {
    private RadarImage() {}

    /** Radar width in pixels (C {@code RadarWidth}). */
    public static final int WIDTH = 256;

    /** Radar height for a map: {@code WIDTH * height / width} (integer division), at least 1. */
    public static int height(WorldMap m) {
        return Math.max(1, WIDTH * m.height() / m.width());
    }

    /**
     * Palette index for a block on the radar, or -1 if the block is not shown.
     * <p>
     * BEHAVIOR: FILLED, REC_*, FUEL use wallRadarColor; TARGET targetRadarColor;
     * DECOR_* decorRadarColor iff {@code showDecor}; everything else is not shown.
     */
    static int colorIndex(int type, boolean showDecor) {
        if (type == BlockType.FILLED || BlockType.isDiagonal(type) || type == BlockType.FUEL) {
            return Palette.WALL_RADAR_COLOR;
        }
        if (type == BlockType.TARGET) {
            return Palette.TARGET_RADAR_COLOR;
        }
        if (showDecor && BlockType.isDecor(type)) {
            return Palette.DECOR_RADAR_COLOR;
        }
        return -1;
    }

    /** Build the radar pixels ({@code WIDTH * height(m)} entries). */
    public static int[] build(WorldMap m, Palette p, boolean showDecor) {
        int h = height(m);
        int[] px = new int[WIDTH * h];
        java.util.Arrays.fill(px, p.color(Palette.BLACK));
        Walker w = new Walker(px, h, p);
        if (m.width() >= WIDTH) {
            float xs = (float) (WIDTH - 1) / (m.width() - 1);
            float ys = (float) (h - 1) / Math.max(1, m.height() - 1);
            for (int xi = 0; xi < m.width(); xi++) {
                w.startColumn((int) (xi * xs + 0.5f));
                for (int yi = 0; yi < m.height(); yi++) {
                    w.cell(colorIndex(m.block(xi, yi), showDecor), (int) (yi * ys + 0.5f), yi == m.height() - 1);
                }
            }
        } else {
            float xs = (float) (m.width() - 1) / (WIDTH - 1);
            float ys = (float) (m.height() - 1) / Math.max(1, h - 1);
            for (int xi = 0; xi < WIDTH; xi++) {
                int xm = (int) (xi * xs + 0.5f);
                w.startColumn(xi);
                for (int yi = 0; yi < h; yi++) {
                    int ym = (int) (yi * ys + 0.5f);
                    w.cell(colorIndex(m.block(xm, ym), showDecor), yi, yi == h - 1);
                }
            }
        }
        return px;
    }

    /**
     * The C run logic for one radar column: consecutive visible cells of the same colour form a
     * span from the first cell's radar y to the last's; a colour change closes the span (including
     * the changing cell, which the next span then repaints; on the column's last cell nothing repaints it,
     * as in C).
     */
    static final class Walker {
        private final int[] px;
        private final int h;
        private final Palette p;
        private int x;
        private int start = -1;
        private int end = -1;
        private int color = -1;

        Walker(int[] px, int h, Palette p) {
            this.px = px;
            this.h = h;
            this.p = p;
        }

        void startColumn(int x) {
            this.x = x;
            start = -1;
            end = -1;
        }

        void cell(int ci, int yp, boolean last) {
            boolean vis = ci >= 0;
            boolean change = false;
            if (vis) {
                if (start == -1) {
                    start = yp;
                    end = yp;
                    color = ci;
                } else {
                    end = yp;
                    change = ci != color;
                }
            }
            if (start != -1 && (!vis || last || change)) {
                span(start, end, color);
                start = -1;
                end = -1;
            }
            if (change) {
                start = yp;
                end = yp;
                color = ci;
            }
        }

        private void span(int from, int to, int ci) {
            int c = p.color(ci);
            int lo = Math.min(from, to);
            int hi = Math.max(from, to);
            for (int y = lo; y <= hi; y++) {
                px[(h - 1 - y) * WIDTH + x] = c;
            }
        }
    }
}
