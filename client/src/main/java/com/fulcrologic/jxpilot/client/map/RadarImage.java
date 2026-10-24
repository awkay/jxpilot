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
package com.fulcrologic.jxpilot.client.map;

import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.WorldMap;

import java.util.function.IntPredicate;

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
 * <p>
 * After the walk, C repaints every live target's block in {@code targetRadarColor}
 * ({@code Paint_world_radar} calling {@code Paint_radar_block}), which restores target pixels the
 * walk's run logic painted over; {@link #build(WorldMap, Palette, boolean, IntPredicate)} takes the
 * targets' liveness ({@link MapState#targetAlive}) for that. A game rebuilds the picture (once, not
 * per frame) when a target dies or respawns. The map viewer has no target state, so every target is
 * alive.
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

    /** Build the radar pixels ({@code WIDTH * height(m)} entries) with every target alive. */
    public static int[] build(WorldMap m, Palette p, boolean showDecor) {
        return build(m, p, showDecor, i -> true);
    }

    /**
     * Build the radar pixels, repainting the targets whose index passes {@code targetAlive}.
     * <p>
     * BEHAVIOR: the walk as {@link Walker}, then each live target's block painted by {@link #block}.
     */
    public static int[] build(WorldMap m, Palette p, boolean showDecor, IntPredicate targetAlive) {
        return build(m, p, showDecor, targetAlive, new int[WIDTH * height(m)]);
    }

    /**
     * As {@link #build(WorldMap, Palette, boolean, IntPredicate)} into {@code px} (R14: a rebuild on a target
     * change allocates no pixel array).
     * <p>
     * BEHAVIOR: {@code px} must hold at least {@code WIDTH * height(m)} entries; it is returned.
     */
    public static int[] build(WorldMap m, Palette p, boolean showDecor, IntPredicate targetAlive, int[] px) {
        int h = height(m);
        java.util.Arrays.fill(px, 0, WIDTH * h, p.color(Palette.BLACK));
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
        int target = p.color(Palette.TARGET_RADAR_COLOR);
        for (int i = 0; i < m.targets().size(); i++) {
            if (targetAlive.test(i)) {
                block(px, h, m.width(), m.height(), m.targets().get(i).bx(), m.targets().get(i).by(), target);
            }
        }
        return px;
    }

    /**
     * C {@code Paint_radar_block}: paint the radar area of block (xi, yi) (y up) in {@code rgba}.
     * <p>
     * BEHAVIOR: a map at least {@link #WIDTH} blocks wide sets the single pixel
     * {@code ((int)(xi * xs + 0.5), height - 1 - (int)(yi * ys + 0.5))} with {@code xs = 255 / (w - 1)},
     * {@code ys = (height - 1) / (h - 1)}.
     * BEHAVIOR: a narrower map fills the pixels that sample the block: x from
     * {@code (int)((xi - 0.5) / xs) + 1} to {@code (int)((xi + 0.5) / xs)}, y likewise (flipped), with
     * {@code xs = (w - 1) / 255}, {@code ys = (h - 1) / (height - 1)}; pixels outside the radar are skipped.
     */
    static void block(int[] px, int h, int mapW, int mapH, int xi, int yi, int rgba) {
        if (mapW >= WIDTH) {
            float xs = (float) (WIDTH - 1) / (mapW - 1);
            float ys = (float) (h - 1) / Math.max(1, mapH - 1);
            set(px, h, (int) (xi * xs + 0.5f), h - 1 - (int) (yi * ys + 0.5f), rgba);
        } else {
            float xs = (float) (mapW - 1) / (WIDTH - 1);
            float ys = (float) Math.max(1, mapH - 1) / Math.max(1, h - 1);
            int xp = (int) ((xi - 0.5) / xs) + 1;
            int xw = (int) ((xi + 0.5) / xs);
            int yw = (int) ((yi - 0.5) / ys) + 1;
            int yp = (int) ((yi + 0.5) / ys);
            xw -= xp;
            yw = yp - yw;
            yp = h - 1 - yp;
            for (int y = yp; y <= yp + yw; y++) {
                for (int x = xp; x <= xp + xw; x++) {
                    set(px, h, x, y, rgba);
                }
            }
        }
    }

    /** Set radar pixel (x, row y from the top) if it is on the radar. */
    private static void set(int[] px, int h, int x, int y, int rgba) {
        if (x >= 0 && x < WIDTH && y >= 0 && y < h) {
            px[y * WIDTH + x] = rgba;
        }
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
