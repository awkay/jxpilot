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

import com.fulcrologic.jxpilot.client.render.WinScale;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.Shapes;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Dir;
import com.fulcrologic.jxpilot.common.map.FuelStation;
import com.fulcrologic.jxpilot.common.map.GravitySource;
import com.fulcrologic.jxpilot.common.map.WallGeometry;
import com.fulcrologic.jxpilot.common.map.WorldMap;

/**
 * Builds the map geometry that never changes during a game, in world coordinates, for upload to a
 * static mesh once per map and wall look ({@link #build}):
 * <ul>
 *   <li>filled look only ({@code filledWorld}): solid wall-coloured triangles, plus every fuel block
 *   ({@link #wallFill});</li>
 *   <li>background dots as points, then as segments: decoration outlines, gravity markers and base
 *   lines ({@link #features});</li>
 *   <li>XPilot's default look: wall-coloured block outlines ({@link MapEdges#walls}); both looks: the
 *   world border.</li>
 * </ul>
 * Shapes and coordinates follow {@code src/client/guimap.c} with C integer block fractions
 * ({@code BLOCK_SZ/2 = 17}, {@code BLOCK_SZ/3 = 11}).
 */
public final class StaticMap {
    private StaticMap() {}

    static final int S = Const.BLOCK_SZ;
    static final int HALF = S / 2;
    /** C {@code backgroundPointDist} default. */
    public static final int POINT_DIST = 8;
    /** C {@code backgroundPointSize} default, in (logical) pixels whatever the zoom. */
    public static final int POINT_SIZE = 2;
    /** Chords per full circle. */
    static final int CIRCLE_SEGMENTS = 24;
    /** Chords per 300 degree gravity arc. */
    static final int ARC_SEGMENTS = 20;

    /**
     * The whole static mesh for one wall look: {@code filled} gives fill triangles first and no
     * block outlines; otherwise block outlines. Commands: [triangles,] points, segments.
     */
    public static VertexBatch build(WorldMap m, Palette p, boolean filled, boolean showDecor) {
        VertexBatch b = VertexBatch.growable(4096);
        int wall = p.color(Palette.WALL_COLOR);
        if (filled) {
            wallFill(m, b, wall);
        }
        features(m, p, showDecor, b);
        if (!filled) {
            MapEdges.walls(m, false, b, wall);
        }
        MapEdges.border(m, b, wall);
        return b;
    }

    /**
     * Filled walls ({@code WallGeometry.buildWallTriangles}), then a full block behind every fuel
     * station, in {@code rgba}. C ({@code Paint_world}, filled mode) treats a fuel block like any other
     * wall block in its row fill: after painting the fuel it runs the same run start/close code.
     */
    static void wallFill(WorldMap m, VertexBatch b, int rgba) {
        float[] t = WallGeometry.buildWallTriangles(m);
        for (int i = 0; i < t.length; i += 6) {
            b.triangle(t[i], t[i + 1], t[i + 2], t[i + 3], t[i + 4], t[i + 5], rgba);
        }
        for (FuelStation f : m.fuels()) {
            b.fillRect(f.bx() * S, f.by() * S, f.bx() * S + S, f.by() * S + S, rgba);
        }
    }

    /**
     * Fixed map features: background dots (points, drawn first), then decoration outlines (when
     * {@code showDecor}), the straight parts of gravity markers and base lines. The gravity circles and
     * arcs are C {@code Arc_add}s, flushed after the ships, so {@link MapSymbols#drawArcs} draws them
     * per frame ({@link #gravityArcs}).
     */
    public static void features(WorldMap m, Palette p, boolean showDecor, VertexBatch b) {
        backgroundDots(m, showDecor, b, p.color(Palette.BLUE));
        if (showDecor) {
            MapEdges.decor(m, false, b, p.color(Palette.DECOR_COLOR));
        }
        int red = p.color(Palette.RED);
        for (GravitySource g : m.gravitySources()) {
            gravityLines(b, g.type(), g.bx() * S, g.by() * S, red);
        }
        int white = p.color(Palette.WHITE);
        for (Base base : m.bases()) {
            baseLine(b, base.dir(), base.bx() * S, base.by() * S, white);
        }
    }

    /**
     * True if block (bx, by) carries a background dot (C {@code Map_dots}): every
     * {@link #POINT_DIST}th block in both axes from 0 on a hard-edged world; on a wrapping world the
     * row y=0 and column x=0 plus every {@link #POINT_DIST}th block from {@link #POINT_DIST}.
     */
    public static boolean dotPosition(int bx, int by, boolean wrap) {
        if (wrap) {
            if (bx == 0 || by == 0) {
                return true;
            }
            return bx >= POINT_DIST && by >= POINT_DIST && bx % POINT_DIST == 0 && by % POINT_DIST == 0;
        }
        return bx % POINT_DIST == 0 && by % POINT_DIST == 0;
    }

    /** Block types that may become dots: space, and decorations when they are not shown. */
    static boolean dotType(int t, boolean showDecor) {
        return t == BlockType.SPACE || (!showDecor && BlockType.isDecor(t));
    }

    /**
     * A {@link #POINT_SIZE}-pixel square at the block centre as C draws it ({@code Gui_paint_decor_dot}:
     * {@code Rectangle_add(X(x+17)-1, Y(y+17)-1, 2, 2)} in y-down screen space), covering world
     * {@code [x+16, x+18) x [y+17, y+19)}: centred on {@code (x+17, y+18)}. The size is C's unscaled
     * size: static meshes are drawn with {@code GlRenderer.setPointScale}, which scales it as
     * {@link #dot(VertexBatch, int, int, int, double)} does.
     */
    public static void dot(VertexBatch b, int x, int y, int rgba) {
        b.point(x + HALF, y + HALF + 1, POINT_SIZE, rgba);
    }

    /**
     * {@link #dot} at a zoom: C's {@code Rectangle_add} sizes scale by {@code WINSCALE}, so the side is
     * {@code WinScale.of(2, zoom)} pixels and the centre moves by {@code WinScale.squareShift} pixels
     * right and down (in world units: divided by the zoom).
     */
    public static void dot(VertexBatch b, int x, int y, int rgba, double zoom) {
        float shift = (float) (WinScale.squareShift(POINT_SIZE, zoom) / zoom);
        b.point(x + HALF + shift, y + HALF + 1 - shift, WinScale.of(POINT_SIZE, zoom), rgba);
    }

    static void backgroundDots(WorldMap m, boolean showDecor, VertexBatch b, int rgba) {
        for (int by = 0; by < m.height(); by++) {
            for (int bx = 0; bx < m.width(); bx++) {
                if (dotPosition(bx, by, m.edgeWrap()) && dotType(m.block(bx, by), showDecor)) {
                    dot(b, bx * S, by * S, rgba);
                }
            }
        }
    }

    /**
     * Gravity marker of block type {@code type} for the block at world (x, y) (C
     * {@code Gui_paint_setup_*_grav}): {@link #gravityArcs} then {@link #gravityLines}.
     * <p>
     * BEHAVIOR: + circle with a cross; - circle with a bar; clockwise/anticlockwise a 300 degree
     * arc with an arrowhead at the top; up/down/left/right an arrow across the block.
     */
    static void gravity(VertexBatch b, int type, int x, int y, int c) {
        gravityArcs(b, type, x, y, c);
        gravityLines(b, type, x, y, c);
    }

    /**
     * The circle or arc of a gravity marker (C {@code Arc_add}): + and - a circle of diameter
     * {@code BLOCK_SZ - 2}; clockwise from 90 and anticlockwise from 150 degrees a 300 degree arc of
     * diameter {@code BLOCK_SZ - 10}; nothing for the directional kinds.
     */
    public static void gravityArcs(VertexBatch b, int type, int x, int y, int c) {
        float cx = x + S / 2f;
        float cy = y + S / 2f;
        switch (type) {
            case BlockType.POS_GRAV, BlockType.NEG_GRAV -> Shapes.circle(b, cx, cy, (S - 2) / 2f, CIRCLE_SEGMENTS, c);
            case BlockType.CWISE_GRAV -> Shapes.arc(b, cx, cy, (S - 10) / 2f, 90, 300, ARC_SEGMENTS, c);
            case BlockType.ACWISE_GRAV -> Shapes.arc(b, cx, cy, (S - 10) / 2f, 150, 300, ARC_SEGMENTS, c);
            default -> { }
        }
    }

    /** The straight lines of a gravity marker (C {@code Segment_add}): crosses, bars, arrowheads and arrows. */
    static void gravityLines(VertexBatch b, int type, int x, int y, int c) {
        switch (type) {
            case BlockType.POS_GRAV -> {
                b.line(x + HALF, y + 5, x + HALF, y + S - 5, c);
                b.line(x + 5, y + HALF, x + S - 5, y + HALF, c);
            }
            case BlockType.NEG_GRAV -> b.line(x + 5, y + HALF, x + S - 5, y + HALF, c);
            case BlockType.CWISE_GRAV -> {
                b.line(x + HALF, y + S - 5, x + HALF - 4, y + S - 1, c);
                b.line(x + HALF, y + S - 5, x + HALF - 4, y + S - 9, c);
            }
            case BlockType.ACWISE_GRAV -> {
                b.line(x + HALF, y + S - 5, x + HALF + 4, y + S - 1, c);
                b.line(x + HALF, y + S - 5, x + HALF + 4, y + S - 9, c);
            }
            case BlockType.UP_GRAV -> {
                b.line(x + HALF, y, x + HALF, y + S, c);
                b.line(x + HALF, y + S, x + HALF - 10, y + S - 10, c);
                b.line(x + HALF, y + S, x + HALF + 10, y + S - 10, c);
            }
            case BlockType.DOWN_GRAV -> {
                b.line(x + HALF, y, x + HALF, y + S, c);
                b.line(x + HALF, y, x + HALF - 10, y + 10, c);
                b.line(x + HALF, y, x + HALF + 10, y + 10, c);
            }
            case BlockType.RIGHT_GRAV -> {
                b.line(x, y + HALF, x + S, y + HALF, c);
                b.line(x + S, y + HALF, x + S - 10, y + HALF + 10, c);
                b.line(x + S, y + HALF, x + S - 10, y + HALF - 10, c);
            }
            case BlockType.LEFT_GRAV -> {
                b.line(x, y + HALF, x + S, y + HALF, c);
                b.line(x, y + HALF, x + 10, y + HALF + 10, c);
                b.line(x, y + HALF, x + 10, y + HALF - 10, c);
            }
            default -> { }
        }
    }

    /**
     * The white line a base sits on, one pixel outside the block on the side opposite its heading
     * (C {@code Gui_paint_base}): facing up the line is below the block, etc.
     */
    static void baseLine(VertexBatch b, int dir, int x, int y, int c) {
        switch (dir) {
            case Dir.UP -> b.line(x, y - 1, x + S, y - 1, c);
            case Dir.DOWN -> b.line(x, y + S + 1, x + S, y + S + 1, c);
            case Dir.LEFT -> b.line(x + S + 1, y + S, x + S + 1, y, c);
            default -> b.line(x - 1, y + S, x - 1, y, c);
        }
    }
}
