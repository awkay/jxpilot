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
package com.fulcrologic.jxpilot.client.paint;

import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.Trig;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.Const;

/**
 * Shots, mines, missiles, lasers, balls, wreckage, asteroids, ECM bursts, paused-player markers,
 * connectors and item pickups as the C client draws them ({@code guiobjects.c},
 * {@code paintobjects.c}).
 * <p>
 * Geometry is in world coordinates (y up) through the batch transform. Sizes C gives in screen
 * pixels (shot squares, mine outline, item triangle, dash lengths) are converted with {@code px}, the
 * world length of one logical pixel (1 / zoom); world sizes (ball radius, missile/laser length,
 * wreck and asteroid sizes) stay in world units. Even-sized C squares cover the pixels from
 * {@code -size/2}, so their centre is half a pixel left of and above the object's pixel.
 * Headings are RES units ({@code Const.RES} = 128 per turn) as on the wire. Text methods take
 * framebuffer positions and need the identity transform. Allocation-free; not thread-safe.
 */
public final class ObjectPainter {
    /** Mine outline relative moves (C {@code world_mine_points}, CoordModePrevious, screen y down). */
    static final int[][] MINE_MOVES = {
        {1, 0}, {0, -1}, {4, 0}, {0, -1}, {6, 0}, {0, 1}, {4, 0}, {0, 1}, {1, 0}, {0, 2},
        {-1, 0}, {0, 1}, {-4, 0}, {0, 1}, {-6, 0}, {0, -1}, {-4, 0}, {0, -1}, {-1, 0}, {0, -2}
    };
    /** Wreckage outlines (C {@code WRECKAGE_SHAPE_0..2}). */
    static final int[][][] WRECKAGE = {
        {{-9, 6}, {-2, 8}, {5, 2}, {9, 3}, {10, 0}, {5, -1}, {3, 0}, {-2, -9}, {-5, -6}, {-3, -2}, {-7, -1}, {-5, 2}},
        {{-8, -9}, {-9, -3}, {-7, 3}, {-1, 7}, {8, 9}, {9, 6}, {2, 5}, {-2, 2}, {4, -1}, {2, -5}, {0, -2}, {-5, -2}},
        {{-9, -2}, {-7, 2}, {-2, -3}, {2, -3}, {0, 1}, {1, 10}, {4, 9}, {4, 2}, {7, -2}, {7, -5}, {2, -8}, {-4, -7}}
    };
    /** Asteroid outlines (C {@code ASTEROID_SHAPE_0..1}). */
    static final int[][][] ASTEROID = {
        {{-10, 0}, {-7, 6}, {-2, 8}, {0, 10}, {5, 8}, {9, 4}, {10, 0}, {7, -5}, {6, -9}, {0, -10}, {-5, -7}, {-7, -5}},
        {{-10, 0}, {-8, 7}, {-4, 9}, {0, 10}, {5, 7}, {6, 3}, {10, 0}, {9, -4}, {7, -7}, {0, -10}, {-6, -9}, {-9, -7}}
    };
    /** Chords per ball / ECM circle. */
    static final int CIRCLE_SEGMENTS = 24;
    /** Half side of the paused marker (C {@code 3*BLOCK_SZ/7}). */
    static final int HALF_PAUSE = 3 * Const.BLOCK_SZ / 7;
    /** Width of the UI atlas the item symbols live in. */
    public static final int ATLAS_SIZE = 1024;

    private final Palette palette;
    private final float[] xs = new float[MINE_MOVES.length + 1];
    private final float[] ys = new float[MINE_MOVES.length + 1];

    public ObjectPainter(Palette palette) {
        this.palette = palette;
    }

    // ------------------------------------------------------------------ shots

    /**
     * Own/enemy shot (C {@code Paint_fastshots}, {@code Gui_paint_fastshot}): a
     * {@link PaintConfig#SHOT_SIZE}-pixel square.
     * <p>
     * BEHAVIOR: only white or blue are used; any other colour index draws white.
     */
    public void shot(VertexBatch b, float x, float y, int colorIndex) {
        int c = colorIndex == Palette.BLUE ? Palette.BLUE : Palette.WHITE;
        b.point(x, y, PaintConfig.SHOT_SIZE, palette.color(c));
    }

    /**
     * Team (or blinking nuclear) shot (C {@code Gui_paint_teamshot} → {@code Gui_paint_nastyshot}
     * with arms of {@code shotSize / 2} pixels): an X when {@code diagonal}, else a +. C picks
     * {@code diagonal} at random per shot per frame ({@code rfrac() < 0.5}); the caller supplies it.
     */
    public void teamShot(VertexBatch b, float x, float y, int colorIndex, boolean diagonal, float px) {
        float z = (PaintConfig.SHOT_SIZE / 2) * px;
        int rgba = palette.color(colorIndex);
        if (diagonal) {
            b.line(x - z, y + z, x + z, y - z, rgba);
            b.line(x + z, y + z, x - z, y - z, rgba);
        } else {
            b.line(x - z, y, x + z, y, rgba);
            b.line(x, y + z, x, y - z, rgba);
        }
    }

    /**
     * Missile (C {@code Gui_paint_missile}): a white line {@link PaintConfig#MISSILE_WIDTH} pixels wide
     * from the missile back {@code len} world units against its heading {@code dir} (RES units).
     * Leaves the line width at 1.
     */
    public void missile(VertexBatch b, float x, float y, float len, int dir) {
        b.setLineWidth(PaintConfig.MISSILE_WIDTH);
        b.line(x, y, x - (float) (Trig.cosRes(dir) * len), y - (float) (Trig.sinRes(dir) * len),
            palette.color(Palette.WHITE));
        b.setLineWidth(VertexBatch.DEFAULT_LINE_WIDTH);
    }

    /**
     * Laser beam (C {@code Gui_paint_laser}): {@link PaintConfig#LASER_WIDTH} pixels wide from (x, y)
     * forward {@code len} world units along {@code dir}.
     * <p>
     * BEHAVIOR: colour indices outside 0..3 (C {@code NUM_COLORS}) draw white. Leaves the width at 1.
     */
    public void laser(VertexBatch b, float x, float y, float len, int dir, int colorIndex) {
        int c = colorIndex < 0 || colorIndex >= 4 ? Palette.WHITE : colorIndex;
        b.setLineWidth(PaintConfig.LASER_WIDTH);
        b.line(x, y, x + (float) (Trig.cosRes(dir) * len), y + (float) (Trig.sinRes(dir) * len), palette.color(c));
        b.setLineWidth(VertexBatch.DEFAULT_LINE_WIDTH);
    }

    /**
     * Mine (C {@code Gui_paint_mine}): unless it is a team mine, a blue 15x5 pixel block; then the
     * white outline, 21 points from (-8, -1) screen pixels. Fills come before lines in the batch.
     */
    public void mine(VertexBatch b, float x, float y, boolean teamMine, float px) {
        if (!teamMine) {
            mineFill(b, x, y, px);
        }
        mineOutline(b, x, y, px);
    }

    /** The blue block of an enemy mine: pixels x-7..x+7, y-2..y+2. */
    public void mineFill(VertexBatch b, float x, float y, float px) {
        b.fillRect(x - 7.5f * px, y - 2.5f * px, x + 7.5f * px, y + 2.5f * px, palette.color(Palette.BLUE));
    }

    /** The white mine outline (screen moves flipped to y up). */
    public void mineOutline(VertexBatch b, float x, float y, float px) {
        float cx = x - 8 * px;
        float cy = y + px; // screen (x - 8, y - 1)
        xs[0] = cx;
        ys[0] = cy;
        for (int i = 0; i < MINE_MOVES.length; i++) {
            cx += MINE_MOVES[i][0] * px;
            cy -= MINE_MOVES[i][1] * px;
            xs[i + 1] = cx;
            ys[i + 1] = cy;
        }
        int rgba = palette.color(Palette.WHITE);
        for (int i = 0; i < MINE_MOVES.length; i++) {
            b.line(xs[i], ys[i], xs[i + 1], ys[i + 1], rgba);
        }
    }

    /**
     * Mine owner name (C {@code Gui_paint_mine_name}): white, centred, baseline 4 pixels plus the
     * ascent plus 1 below the mine's screen position.
     */
    public void mineName(VertexBatch b, TextWriter t, float sx, float sy, CharSequence name, double ratio) {
        float x = sx - (int) t.width(name) / 2;
        float base = sy - (float) (4 * ratio) - t.font().ascent() - (float) ratio;
        t.draw(b, name, x, base, palette.color(Palette.WHITE));
    }

    /** Ball (C {@code Gui_paint_ball}): a white circle of radius {@code BALL_RADIUS} world units. */
    public void ball(VertexBatch b, float x, float y) {
        circle(b, x, y, Const.BALL_RADIUS, palette.color(Palette.WHITE));
    }

    /** Ball to carrier (C {@code Gui_paint_ball_connecter}): a solid white line. */
    public void ballConnector(VertexBatch b, float x1, float y1, float x2, float y2) {
        b.line(x1, y1, x2, y2, palette.color(Palette.WHITE));
    }

    // ------------------------------------------------------------------ dashed connectors

    /**
     * Refuel line, transporter beam or ball connector drawn with the ship dashes (C
     * {@code Gui_paint_refuel}, {@code Gui_paint_transporter}: white, {8, 4} dashes at the ship dash
     * offset).
     */
    public void dashedLink(VertexBatch b, float x1, float y1, float x2, float y2, long loops, float px) {
        Dashes.segment(b, x1, y1, x2, y2, PaintConfig.DASH_ON, PaintConfig.DASH_OFF,
            ShipPainter.dashOffset(loops), px,
            palette.color(Palette.WHITE));
    }

    /**
     * Tractor/pressor beam (C {@code Gui_paint_connector} with {@code tractor}): white, {3, 9} dashes.
     */
    public void tractorBeam(VertexBatch b, float x1, float y1, float x2, float y2, long loops, float px) {
        Dashes.segment(b, x1, y1, x2, y2, PaintConfig.CDASH_ON, PaintConfig.CDASH_OFF,
            ShipPainter.dashOffset(loops), px,
            palette.color(Palette.WHITE));
    }

    // ------------------------------------------------------------------ wreckage, asteroids, effects

    /**
     * Wreckage (C {@code Gui_paint_wreck}): shape {@code type % 3} rotated by {@code rot} (RES), each
     * point scaled as {@code (int)(p * size) >> 8}, closed; white when deadly, else red.
     */
    public void wreck(VertexBatch b, float x, float y, boolean deadly, int type, int rot, int size) {
        int[][] shape = WRECKAGE[Math.floorMod(type, WRECKAGE.length)];
        double c = Trig.cosRes(rot);
        double s = Trig.sinRes(rot);
        for (int i = 0; i < shape.length; i++) {
            double rx = c * shape[i][0] - s * shape[i][1];
            double ry = s * shape[i][0] + c * shape[i][1];
            xs[i] = x + ((int) (rx * size) >> 8);
            ys[i] = y + ((int) (ry * size) >> 8);
        }
        closed(b, shape.length, palette.color(deadly ? Palette.WHITE : Palette.RED));
    }

    /**
     * Asteroid (C {@code Gui_paint_asteroid}): shape {@code type % 2} rotated by {@code rot}, each
     * point scaled as {@code (int)(p * size * 1.4)}, closed, white.
     */
    public void asteroid(VertexBatch b, float x, float y, int type, int rot, int size) {
        int[][] shape = ASTEROID[Math.floorMod(type, ASTEROID.length)];
        double c = Trig.cosRes(rot);
        double s = Trig.sinRes(rot);
        for (int i = 0; i < shape.length; i++) {
            double rx = c * shape[i][0] - s * shape[i][1];
            double ry = s * shape[i][0] + c * shape[i][1];
            xs[i] = x + (int) (rx * size * 1.4);
            ys[i] = y + (int) (ry * size * 1.4);
        }
        closed(b, shape.length, palette.color(Palette.WHITE));
    }

    /** ECM burst (C {@code Gui_paint_ecm}): a white circle of diameter {@code size} world units. */
    public void ecm(VertexBatch b, float x, float y, float size) {
        circle(b, x, y, size / 2, palette.color(Palette.WHITE));
    }

    /**
     * Paused player (C {@code Gui_paint_paused}, fill part): a blue square of
     * {@code 2 * 15 + 1} world pixels centred on the player.
     */
    public void pausedFill(VertexBatch b, float x, float y) {
        float h = HALF_PAUSE + 0.5f;
        b.fillRect(x - h, y - h, x + h, y + h, palette.color(Palette.BLUE));
    }

    /**
     * Paused marker outline (C: drawn when {@code count <= 0 || loops % 10 >= 5}): white square one
     * pixel outside the fill.
     */
    public void pausedOutline(VertexBatch b, float x, float y) {
        float h = HALF_PAUSE + 1;
        b.strokeRect(x - h, y - h, x + h, y + h, palette.color(Palette.WHITE));
    }

    /** True when the paused outline and its "P" show (C: {@code count <= 0 || loops % 10 >= 5}). */
    public static boolean pausedBlinkOn(int count, long loops) {
        return count <= 0 || loops % 10 >= 5;
    }

    /** The white "P" of a paused marker, centred at the screen position of world (x, y - 1). */
    public void pausedLetter(VertexBatch b, TextWriter t, float sx, float sy) {
        float x = sx - (int) t.width('P') / 2;
        t.draw(b, 'P', x, sy - (int) t.font().ascent() / 2, palette.color(Palette.WHITE));
    }

    // ------------------------------------------------------------------ items

    /**
     * Item pickup triangle (C {@code Paint_item}): blue, corners at screen (-S,-S), (0,+S), (+S,-S)
     * with S = {@link PaintConfig#ITEM_TRIANGLE_SIZE}, i.e. pointing down.
     */
    public void itemTriangle(VertexBatch b, float x, float y, float px) {
        float s = PaintConfig.ITEM_TRIANGLE_SIZE * px;
        int rgba = palette.color(Palette.BLUE);
        b.line(x - s, y + s, x, y - s, rgba);
        b.line(x, y - s, x + s, y + s, rgba);
        b.line(x + s, y + s, x - s, y + s, rgba);
    }

    /**
     * Item symbol (C {@code Paint_item_symbol} from {@code Paint_item}): the 16x16 symbol of
     * {@code item} in red, top-left at screen (x - 8, y - S + 2).
     */
    public void itemSymbol(VertexBatch b, int item, float x, float y, float px) {
        symbol(b, item, x - 8 * px, y + (PaintConfig.ITEM_TRIANGLE_SIZE - 2) * px, ItemBitmaps.SIZE * px,
            palette.color(Palette.RED));
    }

    /**
     * An item symbol quad with its top-left corner at (left, top) (y up: it extends down to
     * {@code top - side}), tinted {@code rgba}. Usable in world or framebuffer coordinates.
     */
    public static void symbol(VertexBatch b, int item, float left, float top, float side, int rgba) {
        float u0 = (float) ItemBitmaps.atlasX(item) / ATLAS_SIZE;
        float u1 = (float) (ItemBitmaps.atlasX(item) + ItemBitmaps.SIZE) / ATLAS_SIZE;
        float vTop = (float) ItemBitmaps.atlasY(item) / ATLAS_SIZE;
        float vBottom = (float) (ItemBitmaps.atlasY(item) + ItemBitmaps.SIZE) / ATLAS_SIZE;
        b.texRect(left, top - side, left + side, top, u0, vBottom, u1, vTop, rgba);
    }

    // ------------------------------------------------------------------ helpers

    private void closed(VertexBatch b, int n, int rgba) {
        for (int i = 0; i < n; i++) {
            int j = i + 1 == n ? 0 : i + 1;
            b.line(xs[i], ys[i], xs[j], ys[j], rgba);
        }
    }

    private static void circle(VertexBatch b, float x, float y, float r, int rgba) {
        float px0 = x + r;
        float py0 = y;
        for (int i = 1; i <= CIRCLE_SEGMENTS; i++) {
            double a = 360.0 * i / CIRCLE_SEGMENTS;
            float qx = x + (float) (r * Trig.cosDeg(a));
            float qy = y + (float) (r * Trig.sinDeg(a));
            b.line(px0, py0, qx, qy, rgba);
            px0 = qx;
            py0 = qy;
        }
    }
}
