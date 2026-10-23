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
package com.fulcrologic.jxpilot.server.snapshot;

import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The snapshot shot hash: cell assignment, nearest-first ring order, view clipping, wrapping and dedupe. */
@DisplayName("ShotHash")
class ShotHashTest {
    /** 40 x 20 blocks = 1400 x 700 px: 11 x 6 cells of 128 px. */
    static World world(boolean wrap) {
        String[] rows = new String[20];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = " ".repeat(40);
        }
        return TestWorlds.sim("framesPerSecond: 60\ngravity: 0\nedgeWrap: " + (wrap ? "yes" : "no"), rows).world;
    }

    static int obj(World w, int type, double x, double y, double life) {
        Objects o = w.objects;
        int h = o.alloc();
        o.type[h] = type;
        o.life[h] = life;
        o.initPixels(h, x, y);
        return h;
    }

    static List<Integer> cell(ShotHash sh, int c) {
        List<Integer> r = new ArrayList<>();
        for (int i = sh.start[c]; i < sh.start[c + 1]; i++) {
            r.add(sh.handle[i]);
        }
        return r;
    }

    @Test
    @DisplayName("rebuild: live shots and cannon shots by 128 px cell in pool order, positions in pixels, others left out")
    void rebuild() {
        World w = world(false);
        ShotHash sh = new ShotHash(w);
        int a = obj(w, Obj.SHOT, 10.5, 20, 5);
        int b = obj(w, Obj.CANNON_SHOT, 300, 140, 5);
        int c = obj(w, Obj.SHOT, 100, 100, 5);
        obj(w, Obj.DEBRIS, 12, 12, 5);
        obj(w, Obj.SHOT, 14, 14, 0);
        int far = obj(w, Obj.SHOT, 1399, 699, 1);
        int clamped = obj(w, Obj.SHOT, 5, 5, 3);
        w.objects.px[clamped] = 1500;
        w.objects.py[clamped] = -3;
        sh.rebuild();
        int iA = sh.start[0];
        assertAll(
            () -> assertEquals(11, sh.cw, "1400 px wide: 11 cells (rounded up)"),
            () -> assertEquals(6, sh.ch, "700 px high: 6 cells"),
            () -> assertEquals(5, sh.count, "the debris and the dead shot are left out"),
            () -> assertEquals(List.of(a, c), cell(sh, 0), "cell (0,0) in pool order"),
            () -> assertEquals(List.of(b), cell(sh, 1 * 11 + 2), "cannon shots too: (300,140) is cell (2,1)"),
            () -> assertEquals(List.of(far), cell(sh, 5 * 11 + 10), "the last cell"),
            () -> assertEquals(List.of(clamped), cell(sh, 10), "positions off the grid are clamped into it"),
            () -> assertEquals(10.5, sh.x[iA], 1e-9, "x in pixels (clicks / 64)"),
            () -> assertEquals(20.0, sh.y[iA], 1e-9, "y in pixels"),
            () -> assertEquals(0, sh.start[0], "offsets start at 0"),
            () -> assertEquals(5, sh.start[sh.cw * sh.ch], "and end at the count"));
        w.objects.life[c] = 0;
        sh.rebuild();
        assertAll(
            () -> assertEquals(List.of(a), cell(sh, 0), "a rebuild starts from scratch"),
            () -> assertEquals(4, sh.count, "count follows"));
    }

    @Test
    @DisplayName("a world an exact multiple of 128 px wide has no extra cell column")
    void exactCells() {
        String[] rows = new String[128];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = " ".repeat(128);
        }
        ShotHash sh = new ShotHash(TestWorlds.sim("framesPerSecond: 60\ngravity: 0", rows).world);
        assertAll(
            () -> assertEquals(35, sh.cw, "4480 px / 128 = 35 columns"),
            () -> assertEquals(35, sh.ch, "and rows"));
    }

    @Test
    @DisplayName("the ring starts at the centre cell and never moves away from it out of order")
    void ringOrder() {
        ShotHash sh = new ShotHash(world(false));
        int n = sh.ringX.length;
        boolean sorted = true;
        for (int i = 1; i < n; i++) {
            int d0 = sh.ringX[i - 1] * sh.ringX[i - 1] + sh.ringY[i - 1] * sh.ringY[i - 1];
            int d1 = sh.ringX[i] * sh.ringX[i] + sh.ringY[i] * sh.ringY[i];
            sorted &= d0 <= d1;
        }
        boolean finalSorted = sorted;
        assertAll(
            () -> assertEquals(33 * 33, n, "every offset within RADIUS 16"),
            () -> assertEquals(0, sh.ringX[0] | sh.ringY[0], "the centre first"),
            () -> assertTrue(finalSorted, "by squared distance"),
            () -> assertEquals(16, Math.abs(sh.ringX[n - 1]), "a corner last (x)"),
            () -> assertEquals(16, Math.abs(sh.ringY[n - 1]), "a corner last (y)"));
    }

    static int ringIndex(ShotHash sh, int dx, int dy) {
        for (int k = 0; k < sh.ringX.length; k++) {
            if (sh.ringX[k] == dx && sh.ringY[k] == dy) {
                return k;
            }
        }
        throw new IllegalArgumentException();
    }

    @Test
    @DisplayName("cellAt clips to the view rectangle and the world; wrapping worlds wrap; a walk visits a cell once")
    void cellAt() {
        ShotHash flat = new ShotHash(world(false));
        ShotHash wrap = new ShotHash(world(true));
        int left = ringIndex(flat, -1, 0);
        int up = ringIndex(flat, 0, -1);
        int right2 = ringIndex(flat, 2, 0);
        int down1 = ringIndex(flat, 0, 1);
        int left2 = ringIndex(flat, -2, 0);
        int up1 = ringIndex(flat, 0, -1);
        flat.beginWalk();
        int inside = flat.cellAt(right2, 3, 2, 2, 1);
        int again = flat.cellAt(right2, 3, 2, 2, 1);
        int outX = flat.cellAt(right2, 3, 2, 1, 1);
        int outY = flat.cellAt(down1, 3, 2, 2, 0);
        int offLeft = flat.cellAt(left, 0, 2, 2, 2);
        int offTop = flat.cellAt(up, 3, 0, 2, 2);
        int offRight = flat.cellAt(right2, 10, 2, 2, 2);
        int offBottom = flat.cellAt(down1, 3, 5, 2, 2);
        int edgeLeft = flat.cellAt(left2, 3, 2, 2, 1);
        int edgeUp = flat.cellAt(up1, 3, 2, 2, 1);
        int edgeDown = flat.cellAt(down1, 3, 2, 2, 1);
        flat.beginWalk();
        int nextWalk = flat.cellAt(right2, 3, 2, 2, 1);
        wrap.beginWalk();
        int wLeft = wrap.cellAt(left, 0, 2, 2, 2);
        int wTop = wrap.cellAt(up, 3, 0, 2, 2);
        int wRight = wrap.cellAt(right2, 10, 2, 2, 2);
        int wBottom = wrap.cellAt(down1, 3, 5, 2, 2);
        assertAll(
            () -> assertEquals(2 * 11 + 5, inside, "(3,2) + (2,0) = cell (5,2)"),
            () -> assertEquals(-1, again, "the same cell twice in one walk: skipped"),
            () -> assertEquals(2 * 11 + 1, edgeLeft, "-rx itself is inside"),
            () -> assertEquals(1 * 11 + 3, edgeUp, "-ry itself is inside"),
            () -> assertEquals(3 * 11 + 3, edgeDown, "+ry itself is inside"),
            () -> assertEquals(-1, outX, "outside the view's x range"),
            () -> assertEquals(-1, outY, "outside the view's y range"),
            () -> assertEquals(-1, offLeft, "left of a flat world"),
            () -> assertEquals(-1, offTop, "above a flat world"),
            () -> assertEquals(-1, offRight, "right of a flat world"),
            () -> assertEquals(-1, offBottom, "below a flat world"),
            () -> assertEquals(2 * 11 + 5, nextWalk, "a new walk sees it again"),
            () -> assertEquals(2 * 11 + 10, wLeft, "wraps to the last column"),
            () -> assertEquals(5 * 11 + 3, wTop, "wraps to the last row"),
            () -> assertEquals(2 * 11 + 1, wRight, "wraps to column 1"),
            () -> assertEquals(3, wBottom, "wraps to row 0"));
    }

    @Test
    @DisplayName("when the walk stamp wraps around, every cell is unvisited again")
    void stampWrap() {
        ShotHash sh = new ShotHash(world(false));
        sh.beginWalk();
        int first = sh.cellAt(0, 4, 4, 1, 1);
        sh.stamp = -1;
        sh.beginWalk();
        int same = sh.cellAt(0, 4, 4, 1, 1);
        int other = sh.cellAt(1, 4, 4, 1, 1);
        assertAll(
            () -> assertEquals(4 * 11 + 4, first, "(fixture: the centre cell)"),
            () -> assertEquals(1, sh.stamp, "the stamp restarts at 1, never 0"),
            () -> assertEquals(first, same, "a cell stamped 1 before the wrap is visible again"),
            () -> assertNotEquals(-1, other, "and so are unvisited cells"));
    }
}
