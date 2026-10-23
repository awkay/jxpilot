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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Expected values follow the formulas of Compute_global_gravity / Compute_local_gravity in map.c. */
@DisplayName("GravityField")
class GravityFieldTest {
    /** Results are sums of a few doubles; 1e-12 covers rounding only. */
    static final double EPS = 1e-12;
    static final double F = Const.GRAVS_POWER;

    /** C grav_tab entry: x component = ax / (ax^2 + ay^2)^1.5. */
    static double tx(int ax, int ay) {
        return ax == 0 && ay == 0 ? 0 : ax / Math.pow(ax * ax + ay * ay, 1.5);
    }

    static double[][] local(int w, int h, boolean wrap, int sx, int sy, byte type, double force) {
        byte[] blocks = new byte[w * h];
        blocks[sy * w + sx] = type;
        double[] gx = new double[w * h];
        double[] gy = new double[w * h];
        GravityField.addLocal(w, h, wrap, blocks, List.of(new GravitySource(0, sx, sy, type, force)), gx, gy);
        return new double[][] {gx, gy};
    }

    @Nested
    @DisplayName("global")
    class Global {
        @Test
        @DisplayName("uniform gravity along gravityAngle")
        void uniform() {
            double[] gx = new double[4];
            double[] gy = new double[4];
            GravityField.global(2, 2, false, -0.5, 0, false, 0, 0, false, false, gx, gy);
            double[] hx = new double[1];
            double[] hy = new double[1];
            GravityField.global(1, 1, false, 2.0, 90, false, 0, 0, false, false, hx, hy);
            assertAll(
                () -> assertEquals(-0.5, gx[3], EPS, "angle 0: x = cos(0) * g"),
                () -> assertEquals(0.0, gy[3], EPS, "angle 0: y = 0"),
                () -> assertEquals(-0.5, gx[0], EPS, "every block gets the same value"),
                () -> assertEquals(2.0, hy[0], EPS, "angle 90: y = g"),
                () -> assertEquals(0.0, hx[0], EPS, "angle 90: x = 0"));
        }

        @Test
        @DisplayName("point source: toward/around the point, wrapped, zero at the point")
        void point() {
            int w = 10;
            int h = 10;
            double[] gx = new double[w * h];
            double[] gy = new double[w * h];
            GravityField.global(w, h, false, -0.14, 90, true, 2, 3, false, false, gx, gy);
            double[] cx = new double[w * h];
            double[] cy = new double[w * h];
            GravityField.global(w, h, false, -0.14, 90, true, 2, 3, true, true, cx, cy);
            double[] ax = new double[w * h];
            double[] ay = new double[w * h];
            GravityField.global(w, h, false, -0.14, 90, true, 2, 3, false, true, ax, ay);
            double[] wx = new double[w * h];
            double[] wy = new double[w * h];
            GravityField.global(w, h, true, -0.14, 90, true, 0, 0, false, false, wx, wy);
            assertAll(
                () -> assertEquals(-0.14, gx[3 * w + 3], EPS, "one block right of the point pulls left by g"),
                () -> assertEquals(0.0, gy[3 * w + 3], EPS, "on the same row there is no y pull"),
                () -> assertEquals(-0.14 * 3 / 5, gx[7 * w + 5], EPS, "x = dx * g / dist (3,4,5)"),
                () -> assertEquals(-0.14 * 4 / 5, gy[7 * w + 5], EPS, "y = dy * g / dist (3,4,5)"),
                () -> assertEquals(0.0, gx[3 * w + 2], EPS, "zero x at the point"),
                () -> assertEquals(0.0, gy[3 * w + 2], EPS, "zero y at the point"),
                () -> assertEquals(0.0, cx[3 * w + 3], EPS, "clockwise: x = dy * s"),
                () -> assertEquals(0.14, cy[3 * w + 3], EPS, "clockwise wins over anticlockwise: y = -dx * s"),
                () -> assertEquals(-0.14, cx[4 * w + 2], EPS, "clockwise one block above: x = dy * s = 35 * -0.14/35"),
                () -> assertEquals(-0.14, ay[3 * w + 3], EPS, "anticlockwise: y = dx * s"),
                () -> assertEquals(0.14, ax[4 * w + 2], EPS, "anticlockwise one block above: x = -35 * -0.14/35"),
                () -> assertEquals(0.14, wx[9], EPS, "with wrap the last column is left of column 0"),
                () -> assertEquals(0.14, wy[9 * w], EPS, "with wrap the top row is below row 0"),
                () -> assertEquals(-0.14 * 4 / 5, gy[7 * w + 5], EPS, "without wrap rows are not wrapped"));
        }
    }

    @Nested
    @DisplayName("addLocal")
    class Local {
        @Test
        @DisplayName("attracting and repelling sources")
        void posNeg() {
            int w = 21;
            double[][] neg = local(w, w, false, 5, 5, BlockType.NEG_GRAV, F);
            double[][] pos = local(w, w, false, 5, 5, BlockType.POS_GRAV, -F);
            double[] gx = neg[0];
            double[] gy = neg[1];
            assertAll(
                () -> assertEquals(-F * tx(2, 0), gx[5 * w + 7], EPS, "'-' two blocks right pulls left by force*tab[2][0]"),
                () -> assertEquals(0.0, gy[5 * w + 7], EPS, "on the same row there is no y pull"),
                () -> assertEquals(F * tx(2, 0), gx[5 * w + 3], EPS, "'-' two blocks left pulls right"),
                () -> assertEquals(-F * tx(2, 0), gy[7 * w + 5], EPS, "'-' two blocks above pulls down"),
                () -> assertEquals(F * tx(2, 0), gy[3 * w + 5], EPS, "'-' two blocks below pulls up"),
                () -> assertEquals(0.0, gx[7 * w + 5], EPS, "directly above there is no x pull"),
                () -> assertEquals(-F * tx(1, 1), gx[6 * w + 6], EPS, "diagonal x uses tab[1][1]"),
                () -> assertEquals(-F * tx(1, 1), gy[6 * w + 6], EPS, "diagonal y uses tab[1][1]"),
                () -> assertEquals(0.0, gx[5 * w + 5], EPS, "no effect on the source block itself"),
                () -> assertEquals(-F * tx(10, 0), gx[5 * w + 15], EPS, "range reaches 10 blocks"),
                () -> assertEquals(0.0, gx[5 * w + 16], EPS, "no effect beyond 10 blocks"),
                () -> assertEquals(-F * tx(10, 10), gx[15 * w + 15], EPS, "the range is a square (corner affected)"),
                () -> assertEquals(F * tx(2, 0), pos[0][5 * w + 7], EPS, "'+' two blocks right pushes right"));
        }

        @Test
        @DisplayName("rotating sources")
        void rotating() {
            int w = 21;
            double[][] cw = local(w, w, false, 5, 5, BlockType.CWISE_GRAV, F);
            double[][] acw = local(w, w, false, 5, 5, BlockType.ACWISE_GRAV, -F);
            assertAll(
                () -> assertEquals(-F * tx(2, 0), cw[1][5 * w + 7], EPS, "'>' right of the source pushes down"),
                () -> assertEquals(0.0, cw[0][5 * w + 7], EPS, "'>' right of the source has no x push"),
                () -> assertEquals(F * tx(2, 0), cw[1][5 * w + 3], EPS, "'>' left of the source pushes up"),
                () -> assertEquals(F * tx(2, 0), cw[0][7 * w + 5], EPS, "'>' above the source pushes right"),
                () -> assertEquals(0.0, cw[1][7 * w + 5], EPS, "'>' above the source has no y push"),
                () -> assertEquals(-F * tx(2, 0), cw[0][3 * w + 5], EPS, "'>' below the source pushes left"),
                () -> assertEquals(F * tx(2, 0), acw[1][5 * w + 7], EPS, "'<' right of the source pushes up"));
        }

        @Test
        @DisplayName("currents")
        void currents() {
            int w = 21;
            double[][] up = local(w, w, false, 5, 5, BlockType.UP_GRAV, F);
            double[][] down = local(w, w, false, 5, 5, BlockType.DOWN_GRAV, -F);
            double[][] right = local(w, w, false, 5, 5, BlockType.RIGHT_GRAV, F);
            double[][] left = local(w, w, false, 5, 5, BlockType.LEFT_GRAV, -F);
            assertAll(
                () -> assertEquals(F, up[1][5 * w + 5], EPS, "'i' adds force upward on its own block"),
                () -> assertEquals(0.0, up[0][5 * w + 5], EPS, "'i' adds no x on its own block"),
                () -> assertEquals(F * tx(1, 0), up[1][5 * w + 6], EPS, "'i' beside: y += force * tab.x"),
                () -> assertEquals(F * tx(2, 1), up[1][6 * w + 7], EPS, "'i' off-axis uses tab[2][1].x"),
                () -> assertEquals(0.0, up[1][6 * w + 5], EPS, "'i' directly above: tab[0][1].x is 0"),
                () -> assertEquals(0.0, up[0][6 * w + 7], EPS, "'i' never adds x"),
                () -> assertEquals(-F, down[1][5 * w + 5], EPS, "'m' adds force downward on its own block"),
                () -> assertEquals(F, right[0][5 * w + 5], EPS, "'k' adds force to the right on its own block"),
                () -> assertEquals(0.0, right[1][5 * w + 5], EPS, "'k' adds no y on its own block"),
                () -> assertEquals(F * tx(1, 0), right[0][6 * w + 5], EPS, "'k' above: x += force * tab.y"),
                () -> assertEquals(0.0, right[0][5 * w + 6], EPS, "'k' beside: tab[1][0].y is 0"),
                () -> assertEquals(0.0, right[1][6 * w + 5], EPS, "'k' never adds y"),
                () -> assertEquals(-F, left[0][5 * w + 5], EPS, "'j' adds force to the left on its own block"));
        }

        @Test
        @DisplayName("wrapping")
        void wrap() {
            int w = 30;
            double[][] wrapped = local(w, w, true, 0, 0, BlockType.NEG_GRAV, F);
            double[][] flat = local(w, w, false, 0, 0, BlockType.NEG_GRAV, F);
            double[][] tiny = local(3, 1, true, 1, 0, BlockType.NEG_GRAV, F);
            // In a 3x1 wrapping world the square spans xi -3..5 and yi -1..1; block 0 is visited
            // from xi = -3, 0, 3 (dx = 4, 1, -2) with dy = 1, 0, -1 each.
            double expected = 0;
            int[] dxs = {4, 1, -2};
            for (int dx : dxs) {
                for (int dy = 1; dy >= -1; dy--) {
                    expected += Math.signum(dx) * F * tx(Math.abs(dx), Math.abs(dy));
                }
            }
            double exp = expected;
            assertAll(
                () -> assertEquals(F * tx(1, 0), wrapped[0][29], EPS, "with wrap the last column feels the pull"),
                () -> assertEquals(F * tx(1, 0), wrapped[1][29 * w], EPS, "with wrap the top row feels the pull"),
                () -> assertEquals(0.0, flat[0][29], EPS, "without wrap the far side is unaffected"),
                () -> assertEquals(exp, tiny[0][0], EPS, "a world smaller than the range accumulates each wrap"));
        }
    }
}
