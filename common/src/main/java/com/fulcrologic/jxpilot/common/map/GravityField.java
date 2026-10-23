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
 */
public final class GravityField {
    private GravityField() {}

    /**
     * Fill the global gravity.
     * <p>
     * BEHAVIOR: without a point source every block gets {@code (cos(angle) * g, sin(angle) * g)}.
     * BEHAVIOR: with a point source a block at block offset (dx,dy) from the point (in pixels, wrapped
     * when {@code wrap}) gets {@code (dx, dy) * g / |(dx,dy)|}: negative g pulls toward the point.
     * BEHAVIOR: clockwise point gravity gives {@code (dy, -dx) * g/len}; anticlockwise {@code (-dy, dx) * g/len};
     * clockwise wins if both are set.
     * BEHAVIOR: the block at the point itself gets zero.
     * Deviation from C: the y offset wraps with the world height (C used WRAP_DX for y).
     */
    public static void global(int w, int h, boolean wrap, double gravity, double angleDegrees,
                              boolean pointSource, int pointX, int pointY, boolean clockwise,
                              boolean anticlockwise, double[] gx, double[] gy) {
        if (!pointSource) {
            double theta = angleDegrees * Math.PI / 180.0;
            double xf = Math.cos(theta) * gravity;
            double yf = Math.sin(theta) * gravity;
            for (int i = 0; i < w * h; i++) {
                gx[i] = xf;
                gy[i] = yf;
            }
            return;
        }
        int pw = w * Const.BLOCK_SZ;
        int ph = h * Const.BLOCK_SZ;
        for (int xi = 0; xi < w; xi++) {
            double dx = XMath.wrapDelta((xi - pointX) * (double) Const.BLOCK_SZ, pw, wrap);
            for (int yi = 0; yi < h; yi++) {
                double dy = XMath.wrapDelta((yi - pointY) * (double) Const.BLOCK_SZ, ph, wrap);
                int i = yi * w + xi;
                if (dx == 0 && dy == 0) {
                    gx[i] = 0.0;
                    gy[i] = 0.0;
                    continue;
                }
                double strength = gravity / Math.hypot(dx, dy);
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
     * The inverse-square table: entry [x][y] = (x, y) / (x^2 + y^2)^1.5, [0][0] = 0.
     * <p>
     * BEHAVIOR: returns {x-table, y-table} of size (GRAV_RANGE+1)^2.
     */
    static double[][][] table() {
        int gr = Const.GRAV_RANGE;
        double[][] tx = new double[gr + 1][gr + 1];
        double[][] ty = new double[gr + 1][gr + 1];
        for (int x = 0; x <= gr; x++) {
            for (int y = (x == 0 ? 1 : 0); y <= gr; y++) {
                double s = Math.pow((double) (x * x + y * y), -1.5);
                tx[x][y] = x * s;
                ty[x][y] = y * s;
            }
        }
        return new double[][][] {tx, ty};
    }

    /**
     * Add the effect of map gravity blocks within {@link Const#GRAV_RANGE} blocks (a square).
     * With (dx, dy) = source - block and v = table[|dx|][|dy|], f = force signed by the side
     * (f = -force when the block is right of/above the source):
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
        double[][][] tab = table();
        double[][] tabX = tab[0];
        double[][] tabY = tab[1];
        int minXi = wrap ? -Math.min(gr, w) : 0;
        int maxXi = wrap ? w - 1 + Math.min(gr, w) : w - 1;
        int minYi = wrap ? -Math.min(gr, h) : 0;
        int maxYi = wrap ? h - 1 + Math.min(gr, h) : h - 1;
        for (GravitySource g : gravs) {
            int sx = g.bx();
            int sy = g.by();
            double force = g.force();
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
                double fx = dx < 0 ? -force : force;
                int ax = Math.abs(dx);
                int modXi = Math.floorMod(xi, w);
                for (int yi = firstYi; yi <= lastYi; yi++) {
                    int dy = sy - yi;
                    int i = Math.floorMod(yi, h) * w + modXi;
                    if (dx == 0 && dy == 0) {
                        if (vertical) {
                            gy[i] += force;
                        } else if (horizontal) {
                            gx[i] += force;
                        }
                        continue;
                    }
                    double fy = dy < 0 ? -force : force;
                    int ay = Math.abs(dy);
                    double vx = tabX[ax][ay];
                    double vy = tabY[ax][ay];
                    if (rotating) {
                        gx[i] -= fy * vy;
                        gy[i] += fx * vx;
                    } else if (vertical) {
                        gy[i] += force * vx;
                    } else if (horizontal) {
                        gx[i] += force * vy;
                    } else {
                        gx[i] += fx * vx;
                        gy[i] += fy * vy;
                    }
                }
            }
        }
    }
}
