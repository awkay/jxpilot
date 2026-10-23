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
package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;

import java.util.Arrays;

/**
 * Wall geometry shared by server collision and client rendering. All coordinates are world pixels,
 * y up, block {@code (bx,by)} spanning {@code [bx*35, bx*35+35] x [by*35, by*35+35]}.
 * <p>
 * Solid shapes follow {@code src/server/walls.c}:
 * <ul>
 *   <li>FILLED, FUEL, TARGET: the whole block.</li>
 *   <li>REC_*: the triangle at the named corner (see {@link BlockType}).</li>
 *   <li>CANNON: triangle with its base on the mounting side and apex 1/3 block inward
 *       (up-facing {@code r}: (0,0) (35,0) (17.5,11.67)).</li>
 *   <li>TREASURE: lower half rectangle plus a circle of radius 17.5 at the block centre.</li>
 * </ul>
 * Outline edges follow the C client's {@code Map_blue}: FILLED, FUEL and REC_* are "wall" blocks,
 * a block side is drawn unless the neighbour covers the opposite side; diagonals are always drawn;
 * at a non-wrapping world edge the side is drawn.
 */
public final class WallGeometry {
    private WallGeometry() {}

    /** Maximum vertices returned by {@link #solidPolygon}. */
    public static final int MAX_VERTS = 12;

    /** Side bits as in the C client ({@code BLUE_*}). */
    public static final int SIDE_UP = 1;
    public static final int SIDE_RIGHT = 2;
    public static final int SIDE_DOWN = 4;
    public static final int SIDE_LEFT = 8;

    private static final float S = Const.BLOCK_SZ;

    /** Full sides of a block shape (FILLED/FUEL all four; REC_* the two at its corner). */
    public static int sides(int type) {
        return switch (type) {
            case BlockType.FILLED, BlockType.FUEL, BlockType.DECOR_FILLED -> SIDE_UP | SIDE_RIGHT | SIDE_DOWN | SIDE_LEFT;
            case BlockType.REC_RU, BlockType.DECOR_RU -> SIDE_RIGHT | SIDE_UP;
            case BlockType.REC_RD, BlockType.DECOR_RD -> SIDE_RIGHT | SIDE_DOWN;
            case BlockType.REC_LU, BlockType.DECOR_LU -> SIDE_LEFT | SIDE_UP;
            case BlockType.REC_LD, BlockType.DECOR_LD -> SIDE_LEFT | SIDE_DOWN;
            default -> 0;
        };
    }

    /**
     * Write the solid polygon of a block as CCW (x,y) pairs into {@code out}
     * (length at least {@code 2*MAX_VERTS}).
     *
     * BEHAVIOR: FILLED/FUEL/TARGET give the square; REC_* their corner triangle; CANNON a triangle on its
     * mounting side; TREASURE 11 vertices (bottom half plus an 8-segment top arc); others none.
     *
     * @return number of vertices, 0 if the block has no solid area
     */
    public static int solidPolygon(WorldMap m, int bx, int by, float[] out) {
        int t = m.block(bx, by);
        float x0 = bx * S;
        float y0 = by * S;
        switch (t) {
            case BlockType.FILLED, BlockType.FUEL, BlockType.TARGET:
                return put(out, x0, y0, 0, 0, S, 0, S, S, 0, S);
            case BlockType.REC_LD:
                return put(out, x0, y0, 0, 0, S, 0, 0, S);
            case BlockType.REC_LU:
                return put(out, x0, y0, 0, 0, S, S, 0, S);
            case BlockType.REC_RD:
                return put(out, x0, y0, 0, 0, S, 0, S, S);
            case BlockType.REC_RU:
                return put(out, x0, y0, S, 0, S, S, 0, S);
            case BlockType.CANNON: {
                int dir = m.cannons().get(m.itemId(bx, by)).dir();
                return switch (dir) {
                    case Dir.UP -> put(out, x0, y0, 0, 0, S, 0, S / 2, S / 3);
                    case Dir.DOWN -> put(out, x0, y0, 0, S, S / 2, 2 * S / 3, S, S);
                    case Dir.RIGHT -> put(out, x0, y0, 0, 0, S / 3, S / 2, 0, S);
                    default -> put(out, x0, y0, S, 0, S, S, 2 * S / 3, S / 2);
                };
            }
            case BlockType.TREASURE: {
                int n = 0;
                float r = S / 2;
                out[n++] = x0;
                out[n++] = y0;
                out[n++] = x0 + S;
                out[n++] = y0;
                for (int k = 0; k <= 8; k++) {
                    double a = Math.PI * k / 8;
                    out[n++] = x0 + r + (float) (r * Math.cos(a));
                    out[n++] = y0 + r + (float) (r * Math.sin(a));
                }
                return n / 2;
            }
            default:
                return 0;
        }
    }

    private static int put(float[] out, float x0, float y0, float... local) {
        for (int i = 0; i < local.length; i += 2) {
            out[i] = x0 + local[i];
            out[i + 1] = y0 + local[i + 1];
        }
        return local.length / 2;
    }

    /**
     * True if world point (x, y) lies in a solid part of the map (static geometry; dead targets etc.
     * are the caller's business).
     * <p>
     * BEHAVIOR: outside a non-wrapping world is solid; with wrap the point is wrapped first.
     * BEHAVIOR: diagonal blocks are solid strictly inside their triangle (the diagonal itself is open).
     * BEHAVIOR: treasure: solid when oy &lt;= 17.5 or within the centred circle; cannon: inside its triangle.
     */
    public static boolean isSolidPoint(WorldMap m, double x, double y) {
        int bx = (int) Math.floor(x / S);
        int by = (int) Math.floor(y / S);
        int w = m.width();
        int h = m.height();
        if (bx < 0 || bx >= w || by < 0 || by >= h) {
            if (!m.edgeWrap()) {
                return true;
            }
            int nbx = Math.floorMod(bx, w);
            int nby = Math.floorMod(by, h);
            x += (nbx - bx) * (double) S;
            y += (nby - by) * (double) S;
            bx = nbx;
            by = nby;
        }
        double ox = x - bx * (double) S;
        double oy = y - by * (double) S;
        int t = m.block(bx, by);
        switch (t) {
            case BlockType.FILLED, BlockType.FUEL, BlockType.TARGET:
                return true;
            case BlockType.REC_LU:
                return ox < oy;
            case BlockType.REC_RU:
                return ox + oy > S;
            case BlockType.REC_LD:
                return ox + oy < S;
            case BlockType.REC_RD:
                return ox > oy;
            case BlockType.TREASURE: {
                double r = S / 2.0;
                return oy <= r || (ox - r) * (ox - r) + (oy - r) * (oy - r) <= r * r;
            }
            case BlockType.CANNON: {
                int dir = m.cannons().get(m.itemId(bx, by)).dir();
                double sx;
                double sy;
                switch (dir) {
                    case Dir.UP -> { sx = ox; sy = oy; }
                    case Dir.DOWN -> { sx = ox; sy = S - oy; }
                    case Dir.RIGHT -> { sx = S - oy; sy = ox; }
                    default -> { sx = oy; sy = S - ox; }
                }
                return sx <= S / 2.0 ? 3 * sy <= 2 * sx : 3 * sy <= 2 * (S - sx);
            }
            default:
                return false;
        }
    }

    /**
     * Exposed outline segments of wall blocks (FILLED, FUEL, REC_*), as {@code x1,y1,x2,y2} quads.
     * <p>
     * BEHAVIOR: per block (rows bottom-up, x ascending) emits left, right, bottom, top sides that the
     * neighbour does not cover, then the diagonal for REC_*.
     * BEHAVIOR: at a non-wrapping world edge sides are emitted; with wrap the opposite edge's block decides.
     * Build once per map; use for the static wall VBO and radar.
     */
    public static float[] buildEdges(WorldMap m) {
        return edges(m, false);
    }

    /** Exposed outline segments of decoration blocks ({@code b h g y t}), same format as {@link #buildEdges}. */
    public static float[] buildDecorEdges(WorldMap m) {
        return edges(m, true);
    }

    private static boolean outlineType(int t, boolean decor) {
        return decor ? BlockType.isDecor(t) : BlockType.isWallOutline(t);
    }

    private static int neighbourSides(WorldMap m, int bx, int by, boolean decor) {
        int w = m.width();
        int h = m.height();
        if (bx < 0 || bx >= w || by < 0 || by >= h) {
            if (!m.edgeWrap()) {
                return 0; // C client: draw the side at a non-wrapping world edge
            }
            bx = Math.floorMod(bx, w);
            by = Math.floorMod(by, h);
        }
        int t = m.block(bx, by);
        return outlineType(t, decor) ? sides(t) : 0;
    }

    private static float[] edges(WorldMap m, boolean decor) {
        FloatList out = new FloatList(4096);
        int w = m.width();
        int h = m.height();
        for (int by = 0; by < h; by++) {
            for (int bx = 0; bx < w; bx++) {
                int t = m.block(bx, by);
                if (!outlineType(t, decor)) {
                    continue;
                }
                int s = sides(t);
                float x0 = bx * S;
                float y0 = by * S;
                float x1 = x0 + S;
                float y1 = y0 + S;
                if ((s & SIDE_LEFT) != 0 && (neighbourSides(m, bx - 1, by, decor) & SIDE_RIGHT) == 0) {
                    out.add(x0, y0, x0, y1);
                }
                if ((s & SIDE_RIGHT) != 0 && (neighbourSides(m, bx + 1, by, decor) & SIDE_LEFT) == 0) {
                    out.add(x1, y0, x1, y1);
                }
                if ((s & SIDE_DOWN) != 0 && (neighbourSides(m, bx, by - 1, decor) & SIDE_UP) == 0) {
                    out.add(x0, y0, x1, y0);
                }
                if ((s & SIDE_UP) != 0 && (neighbourSides(m, bx, by + 1, decor) & SIDE_DOWN) == 0) {
                    out.add(x0, y1, x1, y1);
                }
                int shape = BlockType.decorShape(t);
                if (shape == BlockType.REC_LD || shape == BlockType.REC_RU) {
                    out.add(x1, y0, x0, y1);
                } else if (shape == BlockType.REC_LU || shape == BlockType.REC_RD) {
                    out.add(x0, y0, x1, y1);
                }
            }
        }
        return out.toArray();
    }

    /**
     * Filled wall triangles (FILLED as two triangles, REC_* as one) as {@code x,y} triples
     * (6 floats per triangle), CCW. Fuel, cannons, targets and treasures are drawn separately.
     * <p>
     * BEHAVIOR: fans each polygon from its first vertex, blocks in row-major order.
     */
    public static float[] buildWallTriangles(WorldMap m) {
        FloatList out = new FloatList(8192);
        float[] poly = new float[2 * MAX_VERTS];
        for (int by = 0; by < m.height(); by++) {
            for (int bx = 0; bx < m.width(); bx++) {
                int t = m.block(bx, by);
                if (t != BlockType.FILLED && !BlockType.isDiagonal(t)) {
                    continue;
                }
                int n = solidPolygon(m, bx, by, poly);
                for (int k = 1; k + 1 < n; k++) {
                    out.add(poly[0], poly[1], poly[2 * k], poly[2 * k + 1]);
                    out.add(poly[2 * k + 2], poly[2 * k + 3]);
                }
            }
        }
        return out.toArray();
    }

    /** Minimal growable float array (build-time only). */
    private static final class FloatList {
        private float[] a;
        private int n;

        FloatList(int cap) {
            a = new float[cap];
        }

        void add(float x, float y) {
            ensure(2);
            a[n++] = x;
            a[n++] = y;
        }

        void add(float x1, float y1, float x2, float y2) {
            ensure(4);
            a[n++] = x1;
            a[n++] = y1;
            a[n++] = x2;
            a[n++] = y2;
        }

        private void ensure(int k) {
            if (n + k > a.length) {
                a = Arrays.copyOf(a, Math.max(a.length * 2, n + k));
            }
        }

        float[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }
}
