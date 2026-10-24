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

import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import com.fulcrologic.jxpilot.server.world.WormTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Wormholes: the table with temporary wormholes (map.c add/remove_temp_wormhole) and hyperjumps")
class WormholesTest {
    /** 30 x 20, map wormholes '@' at (5, 10) and (25, 10) (indices 0, 1), wormTime as given. */
    static Simulation sim(String extra) {
        String[] rows = SimFixture.open(30, 20, "___");
        char[] r = rows[19 - 10].toCharArray();
        r[5] = '@';
        r[25] = '@';
        rows[19 - 10] = new String(r);
        return TestWorlds.sim(SimFixture.FPS14 + extra, rows);
    }

    @Nested
    @DisplayName("WormTable")
    class Table {
        @Test
        @DisplayName("addTemp: IN then OUT after the map's wormholes, blocks WORMHOLE, IN leads to OUT, countdown wormTime")
        void add() {
            Simulation s = sim("wormTime: 2\n");
            World w = s.world;
            WormTable t = w.worms;
            t.addTemp(10, 5, 20, 15);
            assertAll(
                () -> assertEquals(4, t.count(), "2 map + 2 temporary"),
                () -> assertEquals(2, t.tempCount(), "two temporary"),
                () -> assertEquals(WormTable.IN, t.type(2), "IN"),
                () -> assertEquals(WormTable.OUT, t.type(3), "OUT"),
                () -> assertTrue(t.temporary(2) && t.temporary(3) && !t.temporary(1), "temporary flags"),
                () -> assertEquals(3, t.lastDest(2), "IN leads to OUT"),
                () -> assertEquals(28, t.countdown(2), "wormTime 2 s"),
                () -> assertEquals(BlockType.WORMHOLE, w.map.block(10, 5), "IN block"),
                () -> assertEquals(BlockType.WORMHOLE, w.map.block(20, 15), "OUT block"),
                () -> assertEquals(2, w.map.itemId(10, 5), "IN index"),
                () -> assertEquals(3, w.map.itemId(20, 15), "OUT index"),
                () -> assertEquals(5, t.bx(0), "the map wormhole stays at 0"));
        }

        @Test
        @DisplayName("countDown removes expired temporaries (blocks restored, the last takes the freed index)")
        void countDown() {
            Simulation s = sim("wormTime: 1\n");
            World w = s.world;
            WormTable t = w.worms;
            t.addTemp(10, 5, 20, 15);
            t.setCountdown(2, 1);
            t.setCountdown(3, 5);
            t.countDown();
            int afterOne = t.count();
            int movedIdx = w.map.itemId(20, 15);
            w.wormCountdown[0] = 3;
            t.countDown();
            assertAll(
                () -> assertEquals(3, afterOne, "the expired IN went"),
                () -> assertEquals(BlockType.SPACE, w.map.block(10, 5), "its block is SPACE again"),
                () -> assertEquals(-1, w.map.itemId(10, 5), "and its index restored"),
                () -> assertEquals(2, movedIdx, "the OUT took index 2"),
                () -> assertEquals(20, t.bx(2), "OUT moved to 2"),
                () -> assertEquals(3, t.countdown(2), "its countdown runs on"),
                () -> assertEquals(2, w.wormCountdown[0], "map countdowns run too"));
        }

        @Test
        @DisplayName("no more than TEMP_CAPACITY temporaries")
        void capacity() {
            Simulation s = sim("wormTime: 9\n");
            WormTable t = s.world.worms;
            for (int i = 0; i < WormTable.TEMP_CAPACITY / 2 + 3; i++) {
                t.addTemp(10, 5, 20, 15);
            }
            assertEquals(WormTable.TEMP_CAPACITY, t.tempCount(), "capped");
        }
    }

    @Nested
    @DisplayName("hyperjump (warp with wormHoleHit -1)")
    class Hyperjump {
        @Test
        @DisplayName("lands on a space block; with wormTime between SPACE blocks it leaves a temporary pair")
        void jump() {
            Simulation s = sim("wormTime: 3\n");
            World w = s.world;
            Player p = ready(s, "P", 0, 15.5 * 35, 9.5 * 35);
            p.wormHoleHit = -1;
            p.status |= Obj.WARPING;
            int fromBx = p.bx;
            int fromBy = p.by;
            s.ships.warp(p);
            int bx = p.px / 35;
            int by = p.py / 35;
            assertAll(
                () -> assertEquals(Obj.WARPED, p.status & (Obj.WARPED | Obj.WARPING), "warped"),
                () -> assertEquals(-2, p.wormHoleDest, "a hyperjump destination is -2"),
                () -> assertTrue(bx > 0 && bx < 29 && by > 0 && by < 19, "inside the world"),
                () -> assertEquals(2, w.worms.tempCount(), "a temporary pair"),
                () -> assertEquals(fromBx, w.worms.bx(2), "IN where it jumped from"),
                () -> assertEquals(fromBy, w.worms.by(2), "(y)"),
                () -> assertEquals(bx, w.worms.bx(3), "OUT where it landed"));
        }

        @Test
        @DisplayName("on a map of walls with a few space blocks every jump lands on one of them; with none it stays")
        void landsOnSpace() {
            String[] rows = new String[6];
            java.util.Arrays.fill(rows, "xxxxxx");
            rows[2] = "x  xxx";
            rows[5] = "x_xxxx";
            Simulation s = TestWorlds.sim(SimFixture.FPS14, rows);
            World w = s.world;
            Player p = ready(s, "P", 0, 1.5 * 35, 3.5 * 35);
            boolean allSpace = true;
            for (int i = 0; i < 30; i++) {
                p.wormHoleHit = -1;
                p.status |= Obj.WARPING;
                s.ships.warp(p);
                int t = w.map.block(p.px / 35, p.py / 35);
                allSpace &= t == BlockType.SPACE || t == BlockType.BASE;
            }
            boolean ok = allSpace;
            // only the base block (1, 0) is space-like: most jumps fail 20 tries and stay
            String[] walls = new String[20];
            java.util.Arrays.fill(walls, "x".repeat(20));
            walls[19] = "x_" + "x".repeat(18);
            Simulation n = TestWorlds.sim(SimFixture.FPS14, walls);
            Player q = ready(n, "Q", 0, 1.5 * 35, 0.5 * 35);
            int stays = 0;
            boolean neverInWall = true;
            for (int i = 0; i < 10; i++) {
                int x = q.cx;
                int y = q.cy;
                q.wormHoleHit = -1;
                q.status |= Obj.WARPING;
                n.ships.warp(q);
                if (q.cx == x && q.cy == y) {
                    stays++;
                }
                neverInWall &= q.px / 35 == 1 && q.py / 35 == 0;
            }
            int st = stays;
            boolean nw = neverInWall;
            assertAll(
                () -> assertTrue(ok, "never inside a wall"),
                () -> assertTrue(st > 0, "20 failed tries: the ship stays (" + st + " of 10)"),
                () -> assertTrue(nw, "and is never put into a wall"));
        }

        @Test
        @DisplayName("without wormTime no wormholes; the 10% and nearest choices skip temporaries")
        void noTemp() {
            Simulation s = sim("");
            World w = s.world;
            Player p = ready(s, "P", 0, 15.5 * 35, 9.5 * 35);
            p.wormHoleHit = -1;
            s.ships.warp(p);
            Simulation t = sim("wormTime: 3\n");
            World tw = t.world;
            tw.worms.addTemp(10, 5, 20, 15);
            Player q = ready(t, "Q", 0, 5.5 * 35, 10.5 * 35);
            q.vx = 5;
            boolean neverTemp = true;
            for (int i = 0; i < 30; i++) {
                q.status |= Obj.WARPING;
                q.wormHoleHit = 0;
                tw.wormCountdown[0] = 0;
                t.ships.warp(q);
                neverTemp &= q.wormHoleDest == 1;
            }
            boolean only = neverTemp;
            assertAll(
                () -> assertEquals(0, w.worms.tempCount(), "no wormTime: none"),
                () -> assertTrue(only, "a warp from map wormhole 0 always goes to map wormhole 1, never a temporary"));
        }

        @Test
        @DisplayName("an object entering a temporary IN wormhole (near a wall: C's walldist is not updated) comes out at its OUT")
        void objects() {
            Simulation s = sim("wormTime: 3\n");
            World w = s.world;
            w.map.setBlock(3, 5, BlockType.FILLED);
            System.arraycopy(com.fulcrologic.jxpilot.server.world.WallDist.compute(w.map), 0, w.wallDist, 0,
                w.wallDist.length);
            w.worms.addTemp(1, 5, 20, 15);
            int h = obj(w, Obj.SHOT, Obj.NO_ID, 2 * 35 + 3, 5.5 * 35, -6, 0, 1, 10);
            s.mover.moveObject(h);
            assertAll(
                () -> assertEquals(20, w.objects.px[h] / 35, "out at the OUT block (x)"),
                () -> assertEquals(15, w.objects.py[h] / 35, "out at the OUT block (y)"),
                () -> assertFalse(Click.fromPixels(0f) != 0, "(clicks)"));
        }
    }

    @Test
    @DisplayName("C's break: a warp to a wormhole index that went away ends the whole player loop for the frame")
    void warpBreak() {
        Simulation s = sim("");
        World w = s.world;
        Player a = ready(s, "A", 0, 300, 300);
        Player b = ready(s, "B", 0, 600, 300);
        a.status |= Obj.WARPING;
        a.wormHoleHit = 7;
        b.vx = 5;
        int bx = b.cx;
        long[] in = new long[w.players.length];
        s.tick(in);
        int bxAfterBreak = b.cx;
        boolean warpingCleared = (a.status & Obj.WARPING) == 0;
        s.tick(in);
        int bxNext = b.cx;
        Simulation t = sim("");
        Player c = ready(t, "C", 0, 300, 300);
        Player d = ready(t, "D", 0, 600, 300);
        c.status |= Obj.WARPING;
        c.wormHoleHit = -3;
        d.vx = 5;
        int dx = d.cx;
        boolean guard = t.ships.update(c);
        t.tick(new long[t.world.players.length]);
        assertAll(
            () -> assertTrue(warpingCleared, "the warp ends"),
            () -> assertEquals(bx, bxAfterBreak, "the ship after it is not updated (does not move) this frame"),
            () -> assertTrue(bxNext > bx, "next frame it moves again"),
            () -> assertFalse(guard, "an index below -1 (not reachable in C) just ends the warp"),
            () -> assertTrue(d.cx > dx, "and the loop goes on"));
    }
}
