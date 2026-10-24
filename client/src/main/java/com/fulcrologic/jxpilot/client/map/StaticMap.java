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

import com.fulcrologic.jxpilot.client.paint.PaintStyle;
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
        PaintStyle st = new PaintStyle();
        st.filledWorld = filled;
        st.showDecor = showDecor;
        return build(m, p, st, false, false);
    }

    /**
     * The static mesh for the live look options ({@link PaintStyle}; C {@code Paint_world},
     * {@code Gui_paint_decor}, {@code Map_blue}, {@code Map_dots}).
     * <p>
     * BEHAVIOR: walls: with {@code filledWorld} or {@code texturedWalls} solid {@code wallColor} triangles
     * ({@link #wallFill}) unless {@code tiledWalls} (the caller draws {@link #wallTriangles} with the texture
     * instead), and no outlines; otherwise block outlines, in outline mode iff {@code outlineWorld}.
     * BEHAVIOR: decorations (only with {@code showDecor}): with {@code filledDecor} or {@code texturedDecor}
     * solid {@code decorColor} block shapes ({@link #decorFill}) unless {@code tiledDecor}; otherwise outlines,
     * in outline mode iff {@code outlineDecor}.
     * BEHAVIOR: background dots ({@link #features}) and, on a hard-edged world, the border in the wall colour.
     */
    public static VertexBatch build(WorldMap m, Palette p, PaintStyle st, boolean tiledWalls, boolean tiledDecor) {
        VertexBatch b = VertexBatch.growable(4096);
        int wall = p.color(st.wallColor);
        int decor = p.color(st.decorColor);
        if (st.wallsFilled() && !tiledWalls) {
            wallFill(m, b, wall);
        }
        if (st.showDecor && st.decorFilled() && !tiledDecor) {
            decorFill(m, b, decor);
        }
        features(m, p, st, b);
        if (!st.wallsFilled()) {
            MapEdges.walls(m, st.outlineWorld, b, wall);
        }
        MapEdges.border(m, b, wall);
        return b;
    }

    /** The wall fill triangles in white, for drawing with the wall texture ({@code texturedWalls}). */
    public static VertexBatch wallTriangles(WorldMap m) {
        VertexBatch b = VertexBatch.growable(1024);
        wallFill(m, b, Palette.pack(255, 255, 255, 255));
        return b;
    }

    /** The decoration block shapes in white, for drawing with the decor texture ({@code texturedDecor}). */
    public static VertexBatch decorTriangles(WorldMap m) {
        VertexBatch b = VertexBatch.growable(1024);
        decorFill(m, b, Palette.pack(255, 255, 255, 255));
        return b;
    }

    /**
     * Filled decorations (C {@code Gui_paint_decor} with {@code filledDecor}/{@code texturedDecor}): each
     * decoration block filled on its own as its shape.
     * <p>
     * BEHAVIOR: DECOR_FILLED a full block; DECOR_RU the triangle with the up and right sides, DECOR_RD down and
     * right, DECOR_LU up and left, DECOR_LD down and left.
     */
    static void decorFill(WorldMap m, VertexBatch b, int rgba) {
        for (int by = 0; by < m.height(); by++) {
            for (int bx = 0; bx < m.width(); bx++) {
                int t = m.block(bx, by);
                if (!BlockType.isDecor(t)) {
                    continue;
                }
                float x0 = bx * S;
                float y0 = by * S;
                float x1 = x0 + S;
                float y1 = y0 + S;
                switch (BlockType.decorShape(t)) {
                    case BlockType.REC_RU -> b.triangle(x0, y1, x1, y1, x1, y0, rgba);
                    case BlockType.REC_RD -> b.triangle(x0, y0, x1, y1, x1, y0, rgba);
                    case BlockType.REC_LU -> b.triangle(x0, y0, x0, y1, x1, y1, rgba);
                    case BlockType.REC_LD -> b.triangle(x0, y0, x0, y1, x1, y0, rgba);
                    default -> b.fillRect(x0, y0, x1, y1, rgba);
                }
            }
        }
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
        PaintStyle st = new PaintStyle();
        st.showDecor = showDecor;
        features(m, p, st, b);
    }

    /**
     * {@link #features(WorldMap, Palette, boolean, VertexBatch)} for the live look options: dots every
     * {@code backgroundPointDist} blocks of {@code backgroundPointSize} pixels; decoration outlines only with
     * {@code showDecor} and without {@code filledDecor}/{@code texturedDecor}, in outline mode iff
     * {@code outlineDecor}, in {@code decorColor}.
     */
    public static void features(WorldMap m, Palette p, PaintStyle st, VertexBatch b) {
        backgroundDots(m, st.showDecor, st.pointDist, st.pointSize, b, p.color(Palette.BLUE));
        if (st.showDecor && !st.decorFilled()) {
            MapEdges.decor(m, st.outlineDecor, b, p.color(st.decorColor));
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

    /** {@link #dotPosition(int, int, boolean, int)} at the default distance {@link #POINT_DIST}. */
    public static boolean dotPosition(int bx, int by, boolean wrap) {
        return dotPosition(bx, by, wrap, POINT_DIST);
    }

    /**
     * True if block (bx, by) carries a background dot (C {@code Map_dots}) at {@code backgroundPointDist}
     * {@code dist}.
     * <p>
     * BEHAVIOR: on a wrapping world the row y=0 and the column x=0 always; otherwise (and further) every
     * {@code dist}th block in both axes, from 0 on a hard-edged world and from {@code dist} on a wrapping one;
     * with {@code dist} 0 only the wrapping world's row and column.
     */
    public static boolean dotPosition(int bx, int by, boolean wrap, int dist) {
        if (wrap && (bx == 0 || by == 0)) {
            return true;
        }
        if (dist <= 0) {
            return false;
        }
        if (wrap && (bx < dist || by < dist)) {
            return false;
        }
        return bx % dist == 0 && by % dist == 0;
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
        sizedDot(b, x, y, POINT_SIZE, rgba);
    }

    /**
     * A {@code size}-pixel background dot ({@code Gui_paint_decor_dot}: {@code Rectangle_add(X(x+17) - size/2,
     * Y(y+17) - size/2, size, size)}).
     * <p>
     * BEHAVIOR: a point of side {@code size} centred on world {@code (x + 17 - size/2 + size/2.0,
     * y + 18 + size/2 - size/2.0)} (integer halves as C's {@code >> 1}); size 2 is the default dot.
     */
    public static void sizedDot(VertexBatch b, int x, int y, int size, int rgba) {
        b.point(x + HALF - (size >> 1) + size / 2f, y + HALF + 1 + (size >> 1) - size / 2f, size, rgba);
    }

    /**
     * {@link #dot} at a zoom: C's {@code Rectangle_add} sizes scale by {@code WINSCALE}, so the side is
     * {@code WinScale.of(2, zoom)} pixels and the centre moves by {@code WinScale.squareShift} pixels
     * right and down (in world units: divided by the zoom).
     */
    public static void dot(VertexBatch b, int x, int y, int rgba, double zoom) {
        sizedDot(b, x, y, POINT_SIZE, rgba, zoom);
    }

    /** {@link #dot(VertexBatch, int, int, int, double)} for a dot of C size {@code size}. */
    public static void sizedDot(VertexBatch b, int x, int y, int size, int rgba, double zoom) {
        float shift = (float) (WinScale.squareShift(size, zoom) / zoom);
        b.point(x + HALF - (size >> 1) + size / 2f + shift, y + HALF + 1 + (size >> 1) - size / 2f - shift,
            WinScale.of(size, zoom), rgba);
    }

    /**
     * BEHAVIOR: a dot ({@link #sizedDot(VertexBatch, int, int, int, int)}) on every {@link #dotPosition} block that is
     * {@link #dotType}; none at all when {@code size} is 0 (C {@code map_point_size > 0}).
     */
    static void backgroundDots(WorldMap m, boolean showDecor, VertexBatch b, int rgba) {
        backgroundDots(m, showDecor, POINT_DIST, POINT_SIZE, b, rgba);
    }

    static void backgroundDots(WorldMap m, boolean showDecor, int dist, int size, VertexBatch b, int rgba) {
        if (size <= 0) {
            return;
        }
        for (int by = 0; by < m.height(); by++) {
            for (int bx = 0; bx < m.width(); bx++) {
                if (dotPosition(bx, by, m.edgeWrap(), dist) && dotType(m.block(bx, by), showDecor)) {
                    sizedDot(b, bx * S, by * S, size, rgba);
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
