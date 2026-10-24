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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static com.fulcrologic.jxpilot.common.sim.MoveState.*;
import static com.fulcrologic.jxpilot.server.sim.SegmentBlocksTest.at;
import static com.fulcrologic.jxpilot.server.sim.SegmentBlocksTest.out;
import static com.fulcrologic.jxpilot.server.sim.SegmentBlocksTest.row;
import static com.fulcrologic.jxpilot.server.sim.SegmentBlocksTest.run;
import static com.fulcrologic.jxpilot.server.sim.SegmentTest.BC;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Differential tests of the arithmetic-heavy block cases of {@code Move_segment} against small oracles
 * transcribed directly from the C source (walls.c: REC_LD/LU/RD/RU at 1131-1395, CANNON at 771-935,
 * TREASURE at 937-969), over many random points strictly inside the tested block (no face contact) with
 * a move that stays inside the block (so delta = todo). Velocity equals todo, so sign(0) = +1 as in C.
 * Each oracle returns {done.x, done.y, todo.x, todo.y, crash, bounce}.
 */
class SegmentOracleTest {
    private static final int CASES = 4000;

    static int[] free(int tx, int ty) {
        return new int[] {tx, ty, 0, 0, NOT_A_CRASH, NOT_A_BOUNCE};
    }

    static int[] crash(int tx, int ty, int what) {
        return new int[] {0, 0, tx, ty, what, NOT_A_BOUNCE};
    }

    static int[] moved(int dx, int dy, int tx, int ty) {
        return new int[] {dx, dy, tx - dx, ty - dy, NOT_A_CRASH, NOT_A_BOUNCE};
    }

    /** C Bounce_wall for the diagonal bounces: LeftDown/RightUp negate-swap, LeftUp/RightDown swap. */
    static int[] diagBounce(int tx, int ty, int bounce) {
        boolean neg = bounce == BOUNCE_LEFT_DOWN || bounce == BOUNCE_RIGHT_UP;
        return neg ? new int[] {0, 0, -ty, -tx, NOT_A_CRASH, bounce} : new int[] {0, 0, ty, tx, NOT_A_CRASH, bounce};
    }

    /** walls.c REC_LD / REC_LU / REC_RD / REC_RU for an interior point. */
    static int[] diagonal(char kind, int ox, int oy, int tx, int ty) {
        int sx = tx < 0 ? -1 : 1;
        int sy = ty < 0 ? -1 : 1;
        int dx = tx;
        int dy = ty;
        switch (kind) {
            case 'w': {   // REC_LD
                if (ox + oy < BC) {
                    return crash(tx, ty, CRASH_WALL);
                }
                if (ox + dx + oy + dy >= BC) {
                    return free(tx, ty);
                }
                if (sx * tx >= sy * ty) {
                    double w = (double) ty / tx;
                    dx = (int) ((BC - ox - oy) / (1 + w));
                    dy = (int) (dx * w);
                    if (ox + dx + oy + dy < BC) {
                        dx++;
                        dy = (int) (dx * w);
                    }
                    if (dx == 0) {
                        return diagBounce(tx, ty, BOUNCE_LEFT_DOWN);
                    }
                } else {
                    double w = (double) tx / ty;
                    dy = (int) ((BC - ox - oy) / (1 + w));
                    dx = (int) (dy * w);
                    if (ox + dx + oy + dy < BC) {
                        dy++;
                        dx = (int) (dy * w);
                    }
                    if (dy == 0) {
                        return diagBounce(tx, ty, BOUNCE_LEFT_DOWN);
                    }
                }
                return moved(dx, dy, tx, ty);
            }
            case 's': {   // REC_LU
                if (ox < oy) {
                    return crash(tx, ty, CRASH_WALL);
                }
                if (ox + dx >= oy + dy) {
                    return free(tx, ty);
                }
                if (sx * tx >= sy * ty) {
                    double w = (double) ty / tx;
                    dx = (int) ((oy - ox) / (1 - w));
                    dy = (int) (dx * w);
                    if (ox + dx < oy + dy) {
                        dx++;
                        dy = (int) (dx * w);
                    }
                    if (dx == 0) {
                        return diagBounce(tx, ty, BOUNCE_LEFT_UP);
                    }
                } else {
                    double w = (double) tx / ty;
                    dy = (int) ((ox - oy) / (1 - w));
                    dx = (int) (dy * w);
                    if (ox + dx < oy + dy) {
                        dy--;
                        dx = (int) (dy * w);
                    }
                    if (dy == 0) {
                        return diagBounce(tx, ty, BOUNCE_LEFT_UP);
                    }
                }
                return moved(dx, dy, tx, ty);
            }
            case 'q': {   // REC_RD
                if (ox > oy) {
                    return crash(tx, ty, CRASH_WALL);
                }
                if (ox + dx <= oy + dy) {
                    return free(tx, ty);
                }
                if (sx * tx >= sy * ty) {
                    double w = (double) ty / tx;
                    dx = (int) ((oy - ox) / (1 - w));
                    dy = (int) (dx * w);
                    if (ox + dx > oy + dy) {
                        dx--;
                        dy = (int) (dx * w);
                    }
                    if (dx == 0) {
                        return diagBounce(tx, ty, BOUNCE_RIGHT_DOWN);
                    }
                } else {
                    double w = (double) tx / ty;
                    dy = (int) ((ox - oy) / (1 - w));
                    dx = (int) (dy * w);
                    if (ox + dx > oy + dy) {
                        dy++;
                        dx = (int) (dy * w);
                    }
                    if (dy == 0) {
                        return diagBounce(tx, ty, BOUNCE_RIGHT_DOWN);
                    }
                }
                return moved(dx, dy, tx, ty);
            }
            default: {    // 'a' REC_RU
                if (ox + oy > BC) {
                    return crash(tx, ty, CRASH_WALL);
                }
                if (ox + dx + oy + dy <= BC) {
                    return free(tx, ty);
                }
                if (sx * tx >= sy * ty) {
                    double w = (double) ty / tx;
                    dx = (int) ((BC - ox - oy) / (1 + w));
                    dy = (int) (dx * w);
                    if (ox + dx + oy + dy > BC) {
                        dx--;
                        dy = (int) (dx * w);
                    }
                    if (dx == 0) {
                        return diagBounce(tx, ty, BOUNCE_RIGHT_UP);
                    }
                } else {
                    double w = (double) tx / ty;
                    dy = (int) ((BC - ox - oy) / (1 + w));
                    dx = (int) (dy * w);
                    if (ox + dx + oy + dy > BC) {
                        dy--;
                        dx = (int) (dy * w);
                    }
                    if (dy == 0) {
                        return diagBounce(tx, ty, BOUNCE_RIGHT_UP);
                    }
                }
                return moved(dx, dy, tx, ty);
            }
        }
    }

    static int round(double d) {
        return d < 0 ? -(int) (0.5 - d) : (int) (d + 0.5);
    }

    /** walls.c CANNON (771-935): mx, my, mirx, miry per facing ('r' up, 'c' down, 'f' right, 'd' left). */
    static int[] cannon(char kind, int ox, int oy, int tx, int ty) {
        int mxx;
        int mxy;
        int myx;
        int myy;
        int mirxx = 0;
        int mirxy = 0;
        int miryx = 0;
        int miryy = 0;
        switch (kind) {
            case 'r' -> { mxx = 1; mxy = 0; myx = 0; myy = 1; }
            case 'c' -> { mxx = 1; mxy = 0; myx = 0; myy = -1; miryy = BC; }
            case 'f' -> { mxx = 0; mxy = 1; myx = -1; myy = 0; miryx = BC; }
            default -> { mxx = 0; mxy = -1; myx = 1; myy = 0; mirxy = BC; }
        }
        int sx = tx < 0 ? -1 : 1;
        int sy = ty < 0 ? -1 : 1;
        int startX = mirxx + mxx * ox + miryx + myx * oy;
        int startY = mirxy + mxy * ox + miryy + myy * oy;
        int diffX = mxx * tx + myx * ty;
        int diffY = mxy * tx + myy * ty;
        int dirX = mxx * sx + myx * sy;
        int dirY = mxy * sx + myy * sy;
        int todoX = diffX;
        int todoY = diffY;
        int endX = startX + diffX;
        int endY = startY + diffY;
        if (startX <= BC / 2) {
            if (3 * startY <= 2 * startX) {
                return crash(tx, ty, CRASH_CANNON);
            }
            if (endX <= BC / 2 && 3 * endY > 2 * endX) {
                return free(tx, ty);
            }
        } else {
            if (3 * startY <= 2 * (BC - startX)) {
                return crash(tx, ty, CRASH_CANNON);
            }
            if (endX > BC / 2 && 3 * endY > 2 * (BC - endX)) {
                return free(tx, ty);
            }
        }
        int doneX = diffX;
        int doneY = diffY;
        if (dirX * diffX >= dirY * diffY) {
            double w = (double) todoY / todoX;
            if (3 * todoY != 2 * todoX) {
                double d = (3 * startY - 2 * startX) / (2 - 3 * w);
                int ax = round(d);
                int ay = (int) (ax * w);
                if (dirX * ax < dirX * doneX && dirX * ax >= 0 && startY + ay <= BC / 3) {
                    doneX = ax;
                    doneY = ay;
                    if ((doneX | doneY) == 0) {
                        return crash(tx, ty, CRASH_CANNON);
                    }
                }
            }
            if (-3 * todoY != 2 * todoX) {
                double d = (2 * BC - 2 * startX - 3 * startY) / (2 + 3 * w);
                int bx = round(d);
                int by = (int) (bx * w);
                if (dirX * bx < dirX * doneX && dirX * bx >= 0 && startY + by <= BC / 3) {
                    doneX = bx;
                    doneY = by;
                    if ((doneX | doneY) == 0) {
                        return crash(tx, ty, CRASH_CANNON);
                    }
                }
            }
        } else {
            double w = (double) todoX / todoY;
            double d = (2 * startX - 3 * startY) / (3 - 2 * w);
            int ay = round(d);
            int ax = (int) (ay * w);
            if (dirY * ay < dirY * doneY && dirY * ay >= 0 && startY + ay <= BC / 3) {
                doneX = ax;
                doneY = ay;
                if ((doneX | doneY) == 0) {
                    return crash(tx, ty, CRASH_CANNON);
                }
            }
            d = (2 * BC - 2 * startX - 3 * startY) / (3 + 2 * w);
            int by = round(d);
            int bx = (int) (by * w);
            if (dirY * by < dirY * doneY && dirY * by >= 0 && startY + by <= BC / 3) {
                doneX = bx;
                doneY = by;
                if ((doneX | doneY) == 0) {
                    return crash(tx, ty, CRASH_CANNON);
                }
            }
        }
        return moved(mxx * doneX + mxy * doneY, myx * doneX + myy * doneY, tx, ty);
    }

    static double sqr(double v) {
        return v * v;
    }

    /** walls.c TREASURE (937-969) for an object that crashes on treasures. */
    static int[] treasure(int ox, int oy, int tx, int ty) {
        double r = 0.5f * BC;
        int o2x = ox + tx;
        int o2y = oy + ty;
        int mx = (ox + o2x) / 2;
        int my = (oy + o2y) / 2;
        if (oy > r && o2y > r && sqr(mx - r) + sqr(my - r) > sqr(r) && sqr(o2x - r) + sqr(o2y - r) > sqr(r)
            && sqr(ox - r) + sqr(oy - r) > sqr(r)) {
            return free(tx, ty);
        }
        return crash(tx, ty, CRASH_TREASURE);
    }

    interface Oracle {
        int[] expect(int ox, int oy, int tx, int ty);
    }

    /** Random interior points; todo stays inside the block; some cases biased to start near the hit line. */
    static void compare(char block, Oracle oracle, long seed) {
        World w = row("  " + block + "  ");
        SplittableRandom r = new SplittableRandom(seed);
        for (int i = 0; i < CASES; i++) {
            int ox = 1 + r.nextInt(BC - 1);
            int oy = 1 + r.nextInt(BC - 1);
            int span = (i & 1) == 0 ? 300 : BC;
            int tx = Math.max(-ox + 1, Math.min(BC - ox - 1, r.nextInt(-span, span + 1)));
            int ty = Math.max(-oy + 1, Math.min(BC - oy - 1, r.nextInt(-span, span + 1)));
            if ((i % 7) == 0) {
                tx = 0;
            } else if ((i % 11) == 0) {
                ty = 0;
            }
            int[] expected = oracle.expect(ox, oy, tx, ty);
            int[] got = out(run(w, at(w, ox, oy, tx, ty)));
            int fx = ox;
            int fy = oy;
            int ftx = tx;
            int fty = ty;
            assertArrayEquals(expected, got,
                () -> "block '" + block + "' offset (" + fx + "," + fy + ") todo (" + ftx + "," + fty + ")");
        }
    }

    @Nested
    @DisplayName("diagonal walls")
    class Diagonals {
        @Test
        @DisplayName("REC_LD, REC_LU, REC_RD and REC_RU agree with the C transcription on random moves")
        void random() {
            for (char k : new char[] {'w', 's', 'q', 'a'}) {
                compare(k, (ox, oy, tx, ty) -> diagonal(k, ox, oy, tx, ty), k);
            }
        }
    }

    @Nested
    @DisplayName("cannons")
    class Cannons {
        @Test
        @DisplayName("all four cannon facings agree with the C transcription on random moves")
        void random() {
            for (char k : new char[] {'r', 'c', 'f', 'd'}) {
                compare(k, (ox, oy, tx, ty) -> cannon(k, ox, oy, tx, ty), k * 31L);
            }
        }
    }

    @Nested
    @DisplayName("treasures")
    class Treasures {
        @Test
        @DisplayName("the treasure ball test agrees with the C transcription on random moves")
        void random() {
            compare('*', SegmentOracleTest::treasure, 99);
        }

        @Test
        @DisplayName("points exactly on the ball's circle count as hits")
        void onCircle() {
            World w = row("  *  ");
            // (1792, 2016) is on the circle: 672^2 + 896^2 = 1120^2
            assertArrayEquals(crash(100, 100, CRASH_TREASURE), out(run(w, at(w, 1792, 2016, 100, 100))),
                "starting on the circle crashes");
            assertArrayEquals(crash(-100, -100, CRASH_TREASURE), out(run(w, at(w, 1892, 2116, -100, -100))),
                "ending on the circle crashes");
            assertArrayEquals(crash(400, -300, CRASH_TREASURE), out(run(w, at(w, 1592, 2166, 400, -300))),
                "a tangent move whose midpoint touches the circle crashes");
        }
    }
}
