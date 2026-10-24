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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.sim.Obj;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The object pool (C Obj[] with Object_allocate / Object_free_ind and objpos.c). */
class ObjectsTest {
    private static int[] active(Objects o) {
        return Arrays.copyOf(o.active, o.count());
    }

    @Nested
    @DisplayName("alloc")
    class Alloc {
        @Test
        @DisplayName("hands out handles 0.. in order, appends them to active and returns -1 when full")
        void allocUntilFull() {
            Objects o = new Objects(3);
            int a = o.alloc();
            int b = o.alloc();
            int c = o.alloc();
            int d = o.alloc();
            assertAll(
                () -> assertEquals(0, a, "the first handle is 0"),
                () -> assertEquals(1, b, "the second handle is 1"),
                () -> assertEquals(2, c, "the third handle is 2"),
                () -> assertEquals(-1, d, "a full pool returns -1 (Object_allocate NULL)"),
                () -> assertEquals(3, o.count(), "the count is the number of live objects"),
                () -> assertArrayEquals(new int[] {0, 1, 2}, active(o), "active lists the handles in allocation order"),
                () -> assertTrue(o.isLive(1), "an allocated handle is live"));
        }

        @Test
        @DisplayName("resets every field of a reused handle; owner becomes NO_ID")
        void resetsFields() {
            Objects o = new Objects(1);
            int h = o.alloc();
            o.type[h] = Obj.SHOT;
            o.status[h] = 7;
            o.owner[h] = 3;
            o.team[h] = 2;
            o.setClicks(h, 640, 704);
            o.remember(h);
            o.vx[h] = 1;
            o.vy[h] = 2;
            o.accX[h] = 3;
            o.accY[h] = 4;
            o.mass[h] = 5;
            o.life[h] = 6;
            o.fuseLife[h] = 7;
            o.plRange[h] = 8;
            o.plRadius[h] = 9;
            o.color[h] = 10;
            o.missileDir[h] = 11;
            o.mods[h] = 12;
            o.free(h);
            int g = o.alloc();
            assertAll(
                () -> assertEquals(h, g, "the freed handle is reused"),
                () -> assertEquals(0, o.type[g], "type is cleared"),
                () -> assertEquals(0, o.status[g], "status is cleared"),
                () -> assertEquals(Obj.NO_ID, o.owner[g], "owner is NO_ID"),
                () -> assertEquals(0, o.team[g], "team is cleared"),
                () -> assertEquals(0, o.cx[g] | o.cy[g] | o.px[g] | o.py[g], "the position is cleared"),
                () -> assertEquals(0, o.prevX[g] | o.prevY[g], "the previous position is cleared"),
                () -> assertEquals(0.0, Math.abs(o.vx[g]) + Math.abs(o.vy[g]), "velocity is cleared"),
                () -> assertEquals(0.0, Math.abs(o.accX[g]) + Math.abs(o.accY[g]), "acceleration is cleared"),
                () -> assertEquals(0.0, o.mass[g] + o.life[g] + o.fuseLife[g], "mass, life and fuse life are cleared"),
                () -> assertEquals(0, o.plRange[g] | o.plRadius[g] | o.color[g], "range, radius and colour are cleared"),
                () -> assertEquals(0, o.missileDir[g] | o.mods[g], "missile direction and modifiers are cleared"));
        }
    }

    @Nested
    @DisplayName("free")
    class Free {
        @Test
        @DisplayName("swap-removes: the last active handle moves into the freed slot; double free is ignored")
        void swapRemove() {
            Objects o = new Objects(4);
            for (int i = 0; i < 4; i++) {
                o.alloc();
            }
            o.free(1);
            int[] afterFirst = active(o);
            o.free(1);
            int[] afterDouble = active(o);
            o.free(3);
            int[] afterSecond = active(o);
            int reused = o.alloc();
            assertAll(
                () -> assertArrayEquals(new int[] {0, 3, 2}, afterFirst, "handle 3 (last) takes the freed slot 1"),
                () -> assertArrayEquals(new int[] {0, 3, 2}, afterDouble, "freeing a dead handle changes nothing"),
                () -> assertArrayEquals(new int[] {0, 2}, afterSecond, "freeing 3 moves the last handle 2 into its slot"),
                () -> assertFalse(o.isLive(1), "a freed handle is not live"),
                () -> assertEquals(3, reused, "the most recently freed handle is reused first"),
                () -> assertArrayEquals(new int[] {0, 2, 3}, active(o), "the reused handle is appended to active"),
                () -> assertEquals(3, o.count(), "the count follows allocs and frees"));
        }

        @Test
        @DisplayName("freeing the first active handle moves the last one to the front")
        void freeFirst() {
            Objects o = new Objects(3);
            o.alloc();
            o.alloc();
            o.alloc();
            o.free(0);
            assertAll(
                () -> assertArrayEquals(new int[] {2, 1}, active(o), "handle 2 takes slot 0"),
                () -> assertFalse(o.isLive(0), "handle 0 is dead"),
                () -> assertTrue(o.isLive(2), "handle 2 is still live"));
        }

        @Test
        @DisplayName("freeing the only handle empties the pool")
        void freeLast() {
            Objects o = new Objects(2);
            int h = o.alloc();
            o.free(h);
            assertAll(
                () -> assertEquals(0, o.count(), "no live objects remain"),
                () -> assertFalse(o.isLive(h), "the handle is dead"));
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {
        @Test
        @DisplayName("frees everything and hands out handles from 0 again")
        void clearResets() {
            Objects o = new Objects(3);
            o.alloc();
            o.alloc();
            o.free(0);
            o.clear();
            int a = o.alloc();
            int b = o.alloc();
            int c = o.alloc();
            assertAll(
                () -> assertEquals(0, a, "first handle after clear is 0"),
                () -> assertEquals(1, b, "then 1"),
                () -> assertEquals(2, c, "then 2"),
                () -> assertEquals(-1, o.alloc(), "capacity is unchanged"),
                () -> assertFalse(new Objects(2).isLive(0), "a fresh pool has no live handles"));
        }
    }

    @Nested
    @DisplayName("positions")
    class Positions {
        @Test
        @DisplayName("setClicks derives pixels by arithmetic shift; initPixels truncates and remembers")
        void positions() {
            Objects o = new Objects(2);
            int h = o.alloc();
            o.setClicks(h, 130, -1);
            int px = o.px[h];
            int py = o.py[h];
            int pvx = o.prevX[h];
            o.initPixels(h, 10.99, -2.5);
            assertAll(
                () -> assertEquals(2, px, "130 clicks is pixel 2"),
                () -> assertEquals(-1, py, "-1 click floors to pixel -1"),
                () -> assertEquals(0, pvx, "setClicks does not touch the previous position"),
                () -> assertEquals(703, o.cx[h], "initPixels: FLOAT_TO_CLICK(10.99) = (int)703.36 = 703"),
                () -> assertEquals(-160, o.cy[h], "initPixels: FLOAT_TO_CLICK(-2.5) = -160"),
                () -> assertEquals(10, o.px[h], "pixel x is 703 >> 6 = 10"),
                () -> assertEquals(-3, o.py[h], "pixel y is -160 >> 6 = -3"),
                () -> assertEquals(10, o.prevX[h], "initPixels makes the new pixel x the previous one"),
                () -> assertEquals(-3, o.prevY[h], "initPixels makes the new pixel y the previous one"));
        }

        @Test
        @DisplayName("remember copies the current pixel position into the previous one")
        void remember() {
            Objects o = new Objects(1);
            int h = o.alloc();
            o.setClicks(h, 64 * 7, 64 * 9);
            o.remember(h);
            o.setClicks(h, 0, 0);
            assertAll(
                () -> assertEquals(7, o.prevX[h], "previous x is the remembered pixel x"),
                () -> assertEquals(9, o.prevY[h], "previous y is the remembered pixel y"));
        }
    }
}
