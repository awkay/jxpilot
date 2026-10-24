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
import com.fulcrologic.jxpilot.common.math.XMath;

import java.util.List;

/**
 * Per-block gravity, a port of {@code Compute_global_gravity}, {@code Compute_grav_tab} and
 * {@code Compute_local_gravity} in {@code src/server/map.c}. Arrays are indexed {@code by*w + bx}.
 * Pure functions over plain arrays.
 * <p>
 * C keeps gravity in {@code DFLOAT} (float) vectors and the option values in floats, so every stored
 * value here is rounded to float at the same steps as C, with {@link StrictMath} for the transcendental
 * functions: the server simulation is frame-exact against C traces only with these exact values. The
 * arrays stay {@code double[]} (every element holds a float value).
 */
public final class GravityField {
    private GravityField() {}

    /**
     * Fill the global gravity.
     * <p>
     * BEHAVIOR: without a point source every block gets {@code (float)(cos(angle) * g), (float)(sin(angle) * g)}
     * with g and the angle taken as C floats.
     * BEHAVIOR: with a point source a block at block offset (dx,dy) from the point (in pixels, wrapped
     * when {@code wrap}) gets {@code (dx, dy) * s} with {@code s = (float)(g / |(dx,dy)|)}: negative g pulls
     * toward the point.
     * BEHAVIOR: clockwise point gravity gives {@code (dy, -dx) * s}; anticlockwise {@code (-dy, dx) * s};
     * clockwise wins if both are set.
     * BEHAVIOR: the block at the point itself gets zero.
     * BEHAVIOR: as C (map.c:1179, a C bug kept for parity), the y offset wraps with the world WIDTH
     * ({@code WRAP_DX}), which differs from wrapping with the height only on a non-square wrapping map.
     */
    public static void global(int w, int h, boolean wrap, double gravity, double angleDegrees,
                              boolean pointSource, int pointX, int pointY, boolean clockwise,
                              boolean anticlockwise, double[] gx, double[] gy) {
        float g = (float) gravity;
        if (!pointSource) {
            double theta = ((float) angleDegrees * Math.PI) / 180.0;
            float xf = (float) (StrictMath.cos(theta) * g);
            float yf = (float) (StrictMath.sin(theta) * g);
            for (int i = 0; i < w * h; i++) {
                gx[i] = xf;
                gy[i] = yf;
            }
            return;
        }
        int pw = w * Const.BLOCK_SZ;
        for (int xi = 0; xi < w; xi++) {
            int dx = (int) XMath.wrapDelta((xi - pointX) * Const.BLOCK_SZ, pw, wrap);
            for (int yi = 0; yi < h; yi++) {
                // C bug kept for parity: dy = WRAP_DX(dy) wraps the y offset with the world width (map.c:1179)
                int dy = (int) XMath.wrapDelta((yi - pointY) * Const.BLOCK_SZ, pw, wrap);
                int i = yi * w + xi;
                if (dx == 0 && dy == 0) {
                    gx[i] = 0.0;
                    gy[i] = 0.0;
                    continue;
                }
                float strength = (float) (g / StrictMath.hypot(dx, dy));
                if (clockwise) {
                    gx[i] = dy * strength;
                    gy[i] = -dx * strength;
                } else if (anticlockwise) {
                    gx[i] = -dy * strength;
                    gy[i] = dx * strength;
                } else {
                    gx[i] = dx * strength;
                    gy[i] = dy * strength;
                }
            }
        }
    }

    /**
     * The inverse-square table: entry [x][y] = (float)(x, y) / (x^2 + y^2)^1.5, [0][0] = 0 (C stores floats).
     * <p>
     * BEHAVIOR: returns {x-table, y-table} of size (GRAV_RANGE+1)^2.
     */
    static float[][][] table() {
        int gr = Const.GRAV_RANGE;
        float[][] tx = new float[gr + 1][gr + 1];
        float[][] ty = new float[gr + 1][gr + 1];
        for (int x = 0; x <= gr; x++) {
            for (int y = (x == 0 ? 1 : 0); y <= gr; y++) {
                double s = StrictMath.pow((double) (x * x + y * y), -1.5);
                tx[x][y] = (float) (x * s);
                ty[x][y] = (float) (y * s);
            }
        }
        return new float[][][] {tx, ty};
    }

    /**
     * Add the effect of map gravity blocks within {@link Const#GRAV_RANGE} blocks (a square), in float
     * arithmetic like C. With (dx, dy) = source - block and v = table[|dx|][|dy|], f = force signed by the
     * side (f = -force when the block is right of/above the source):
     * <p>
     * BEHAVIOR: POS/NEG: {@code gx += fx*v.x, gy += fy*v.y} (force &lt; 0 repels, &gt; 0 attracts).
     * BEHAVIOR: CWISE/ACWISE: {@code gx -= fy*v.y, gy += fx*v.x}.
     * BEHAVIOR: UP/DOWN currents: {@code gy += force*v.x}; on the source block {@code gy += force}.
     * BEHAVIOR: LEFT/RIGHT currents: {@code gx += force*v.y}; on the source block {@code gx += force}.
     * BEHAVIOR: blocks beyond the range or outside a non-wrapping world are unaffected; with wrap the
     * square wraps around the world edges.
     */
    public static void addLocal(int w, int h, boolean wrap, byte[] blocks, List<GravitySource> gravs,
                                double[] gx, double[] gy) {
        final int gr = Const.GRAV_RANGE;
        float[][][] tab = table();
        float[][] tabX = tab[0];
        float[][] tabY = tab[1];
        int minXi = wrap ? -Math.min(gr, w) : 0;
        int maxXi = wrap ? w - 1 + Math.min(gr, w) : w - 1;
        int minYi = wrap ? -Math.min(gr, h) : 0;
        int maxYi = wrap ? h - 1 + Math.min(gr, h) : h - 1;
        for (GravitySource g : gravs) {
            int sx = g.bx();
            int sy = g.by();
            float force = (float) g.force();
            int firstXi = Math.max(sx - gr, minXi);
            int lastXi = Math.min(sx + gr, maxXi);
            int firstYi = Math.max(sy - gr, minYi);
            int lastYi = Math.min(sy + gr, maxYi);
            int gtype = blocks[sy * w + sx];
            boolean rotating = gtype == BlockType.CWISE_GRAV || gtype == BlockType.ACWISE_GRAV;
            boolean vertical = gtype == BlockType.UP_GRAV || gtype == BlockType.DOWN_GRAV;
            boolean horizontal = gtype == BlockType.RIGHT_GRAV || gtype == BlockType.LEFT_GRAV;
            for (int xi = firstXi; xi <= lastXi; xi++) {
                int dx = sx - xi;
                float fx = dx < 0 ? -force : force;
                int ax = Math.abs(dx);
                int modXi = Math.floorMod(xi, w);
                for (int yi = firstYi; yi <= lastYi; yi++) {
                    int dy = sy - yi;
                    int i = Math.floorMod(yi, h) * w + modXi;
                    float cgx = (float) gx[i];
                    float cgy = (float) gy[i];
                    if (dx == 0 && dy == 0) {
                        if (vertical) {
                            gy[i] = cgy + force;
                        } else if (horizontal) {
                            gx[i] = cgx + force;
                        }
                        continue;
                    }
                    float fy = dy < 0 ? -force : force;
                    int ay = Math.abs(dy);
                    float vx = tabX[ax][ay];
                    float vy = tabY[ax][ay];
                    if (rotating) {
                        gx[i] = cgx - fy * vy;
                        gy[i] = cgy + fx * vx;
                    } else if (vertical) {
                        gy[i] = cgy + force * vx;
                    } else if (horizontal) {
                        gx[i] = cgx + force * vy;
                    } else {
                        gx[i] = cgx + fx * vx;
                        gy[i] = cgy + fy * vy;
                    }
                }
            }
        }
    }
}
