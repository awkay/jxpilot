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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.sim.Click;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.Trig;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.FRAME;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mines: Place_mine, Place_moving_mine, Place_general_mine, Detonate_mines, Move_mine (shot.c), MineCollision and
 * Player_collides_with_mine (collision.c). */
@DisplayName("Mines (shot.c, collision.c)")
class MinesTest {
    static final double EPS = 1e-9;
    static final double FUEL0 = 1000 * 256;
    static final int MINE = Item.MINE.ordinal();
    static final int NUKE = Mods.withNuclear(0, Mods.NUCLEAR);
    static final int FULLNUKE = Mods.withNuclear(0, Mods.NUCLEAR | Mods.FULLNUCLEAR);
    /** (long)CLUSTER_MASS_DRAIN(30) = (long)(30 * 0.9 / 0.1f * -51.2) = (long)-13823.9998 (serverconst.h:228-229). */
    static final long CLUSTER_DRAIN_30 = -13823;

    Simulation s;
    World w;
    Objects o;
    Player p;
    Player q;

    void setup(String extra) {
        s = sim(FRAME + extra, 20, 20, "__");
        w = s.world;
        o = w.objects;
        p = ready(s, "P", 0, 200, 300);
        q = ready(s, "Q", 0, 500, 300);
        p.dir = 0;
        p.items[MINE] = 5;
        w.items.mineFuseTime = 25;
        w.items.mineLife = 300;
    }

    /** Text and receiver id (0 = everyone) of the messages since {@code from}. */
    List<String> msgs(long from) {
        return messages(w, from);
    }

    int receiverOfLast() {
        return w.events.c[Events.index(w.events.seq() - 1)];
    }

    @Nested
    @DisplayName("placeGeneral (Place_general_mine)")
    class PlaceGeneral {
        @Test
        @DisplayName("a dropped mine: blue, owner-immune with the fuse, mass 30, ranges, costs ED_MINE and one mine")
        void dropped() {
            setup("");
            long m0 = w.events.seq();
            s.mines.placeGeneral(p, 3, 0, 210, 320, 0, 0, 0);
            int h = o.active[0];
            assertAll(
                () -> assertEquals(1, o.count(), "one mine"),
                () -> assertEquals(Obj.MINE, o.type[h], "of type MINE"),
                () -> assertEquals(Records.ObjColor.BLUE, o.color[h], "blue"),
                () -> assertEquals(25, o.info[h], "fuse = mineFuseTime"),
                () -> assertEquals(Obj.OWNERIMMUNE, o.status[h], "OWNERIMMUNE and no GRAVITY when dropped"),
                () -> assertEquals(p.id, o.owner[h], "controlled by the layer"),
                () -> assertEquals(p.id, o.mineOwner[h], "laid by the layer"),
                () -> assertEquals(3, o.team[h], "of the given team"),
                () -> assertEquals(Click.fromPixels(210), o.cx[h], "at x"),
                () -> assertEquals(Click.fromPixels(320), o.cy[h], "at y"),
                () -> assertEquals(o.px[h], o.prevX[h], "previous position = position"),
                () -> assertEquals(0f, o.vx[h], "still"),
                () -> assertEquals(0f, o.accX[h], "no spread acceleration"),
                () -> assertEquals(0, o.spreadLeft[h], "no spread time"),
                () -> assertEquals(30f, o.mass[h], "MINE_MASS"),
                () -> assertEquals(300.0, o.life[h], EPS, "life mineLife"),
                () -> assertEquals(100, o.plRange[h], "pl_range MINE_RANGE = 100"),
                () -> assertEquals(8, o.plRadius[h], "pl_radius MINE_RADIUS"),
                () -> assertEquals(0, o.mods[h], "no modifiers"),
                () -> assertEquals(FUEL0 - 60 * 256, p.fuel, EPS, "costs ED_MINE = 60 fuel units"),
                () -> assertEquals(4, p.items[MINE], "uses one mine"),
                () -> assertEquals(List.of(), msgs(m0), "a single mine is not announced"));
        }

        @Test
        @DisplayName("life: mineLife, else 5000 + randomMT()&255; a killed layer's mine (int)(rfrac*FPS); a cannon's 8 + rand%24")
        void lives() {
            setup("");
            w.items.mineLife = 0;
            w.rng.seed(3);
            Rng ref = new Rng(3);
            int random = 5000 + (ref.nextInt() & 255);
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, 0);
            int hr = o.active[0];
            p.status |= Obj.KILLED;
            int killed = (int) (ref.nextFloat() * 60);
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, 0);
            int hk = o.active[1];
            int cannon = 8 + Integer.remainderUnsigned(ref.nextInt(), 24);
            s.mines.placeGeneral(null, 2, Obj.FROMCANNON, 100, 100, 0, 0, 0);
            int hc = o.active[2];
            assertAll(
                () -> assertEquals((double) random, o.life[hr], EPS, "MINE_LIFETIME = 5000 + (randomMT() & 255)"),
                () -> assertEquals((double) killed, o.life[hk], EPS, "a killed player's mine lives (int)(rfrac * FPS)"),
                () -> assertEquals((double) cannon, o.life[hc], EPS, "a cannon mine lives CANNON_SHOT_LIFE"),
                () -> assertEquals(18f, o.mass[hc], "a cannon mine weighs MINE_MASS * 0.6"),
                () -> assertEquals(Obj.FROMCANNON | Obj.OWNERIMMUNE, o.status[hc], "FROMCANNON | OWNERIMMUNE"),
                () -> assertEquals(Obj.NO_ID, o.owner[hc], "unowned"),
                () -> assertEquals(Obj.NO_ID, o.mineOwner[hc], "never laid by a player"),
                () -> assertEquals(FUEL0 - 2 * 60 * 256, p.fuel, EPS, "only the player's two mines cost fuel"),
                () -> assertEquals(3, p.items[MINE], "and two mines"));
        }

        @Test
        @DisplayName("velocity needs cluster, spread needs mini, nuclear needs nukeMinSmarts > 0")
        void modFilters() {
            setup("");
            w.items.nukeMinSmarts = 0;
            int v = Mods.withSpread(Mods.withVelocity(FULLNUKE, 2), 1);
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, v);
            int plain = o.active[0];
            int keep = Mods.withSpread(Mods.withMini(Mods.withVelocity(Obj.MOD_CLUSTER, 2), 1), 3);
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, keep);
            int kept = o.active[1];
            assertAll(
                () -> assertEquals(Mods.withNuclear(0, Mods.FULLNUCLEAR), o.mods[plain],
                    "without cluster, mini and nukeMinSmarts only FULLNUCLEAR remains"),
                () -> assertEquals(30f, o.mass[plain], "not nuclear: one mine's mass"),
                () -> assertEquals(keep, o.mods[kept], "cluster keeps velocity and mini keeps spread"),
                () -> assertEquals(5 - 1 - 1, p.items[MINE], "each placement uses one mine"));
        }

        @Test
        @DisplayName("a nuke uses nukeMinMines mines (FULLNUCLEAR all), weighs that many and is announced")
        void nuclear() {
            setup("");
            p.items[MINE] = 6;
            long m0 = w.events.seq();
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, NUKE);
            int h = o.active[0];
            int afterNuke = p.items[MINE];
            List<String> nukeMsg = msgs(m0);
            int nukeTo = receiverOfLast();
            p.items[MINE] = 6;
            long m1 = w.events.seq();
            s.mines.placeGeneral(p, 0, Obj.GRAVITY, 100, 100, 0, 0, FULLNUKE);
            int hf = o.active[1];
            List<String> fullMsg = msgs(m1);
            s.mines.placeGeneral(null, 0, 0, 100, 100, 0, 0, NUKE);
            int hu = o.active[2];
            assertAll(
                () -> assertEquals(120f, o.mass[h], "4 (nukeMinMines) * MINE_MASS"),
                () -> assertEquals(2, afterNuke, "uses nukeMinMines = 4 mines"),
                () -> assertEquals(List.of("P has dropped a nuclear mine!"), nukeMsg, "a nuke drop is announced"),
                () -> assertEquals(0, nukeTo, "to everyone"),
                () -> assertEquals(180f, o.mass[hf], "full nuclear: all 6 mines"),
                () -> assertEquals(0, p.items[MINE], "all mines used"),
                () -> assertEquals(List.of("P has thrown a full nuclear bomb!"), fullMsg, "a thrown full nuke"),
                () -> assertEquals(120f, o.mass[hu], "an unowned nuke weighs nukeMinMines mines"),
                () -> assertEquals(FUEL0 - 2 * 60 * 256, p.fuel, EPS, "a nuke costs one ED_MINE"));
        }

        @Test
        @DisplayName("too few mines for a nuke: a message to the layer and nothing else")
        void tooFewForNuke() {
            setup("");
            p.items[MINE] = 3;
            long m0 = w.events.seq();
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, NUKE);
            List<String> drop = msgs(m0);
            int to = receiverOfLast();
            long m1 = w.events.seq();
            s.mines.placeGeneral(p, 0, Obj.GRAVITY, 100, 100, 0, 0, NUKE);
            p.items[MINE] = 4;
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, NUKE);
            assertAll(
                () -> assertEquals(List.of("You need at least 4 mines to drop a nuclear mine!"), drop, "C's message"),
                () -> assertEquals(p.id, to, "sent to the layer only"),
                () -> assertEquals("You need at least 4 mines to throw a nuclear bomb!", msgs(m1).get(0), "thrown: throw a bomb"),
                () -> assertEquals(1, o.count(), "exactly nukeMinMines mines suffice"),
                () -> assertEquals(FUEL0 - 60 * 256, p.fuel, EPS, "only the nuke that was made cost fuel"));
        }

        @Test
        @DisplayName("fuel: ED_MINE plus the cluster drain of the mass; too little gives a message")
        void fuel() {
            setup("");
            p.fuel = 60 * 256 - 1;
            long m0 = w.events.seq();
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, 0);
            List<String> plain = msgs(m0);
            p.fuel = 60 * 256 - CLUSTER_DRAIN_30 - 1;
            long m1 = w.events.seq();
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, Obj.MOD_CLUSTER);
            List<String> cluster = msgs(m1);
            int refused = o.count();
            p.fuel = 60 * 256 - CLUSTER_DRAIN_30;
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, Obj.MOD_CLUSTER);
            double left = p.fuel;
            p.fuel = FUEL0;
            p.items[MINE] = 4;
            long m2 = w.events.seq();
            p.fuel = 100;
            s.mines.placeGeneral(p, 0, Obj.GRAVITY, 100, 100, 0, 0, NUKE | Obj.MOD_CLUSTER);
            assertAll(
                () -> assertEquals(List.of("You need at least 60 fuel units to drop a mine!"), plain, "ED_MINE >> 8 = 60"),
                () -> assertEquals(List.of("You need at least 113 fuel units to drop a cluster mine!"), cluster,
                    "(15360 + 13823) >> 8 = 113"),
                () -> assertEquals(0, refused, "nothing is placed without the fuel"),
                () -> assertEquals(1, o.count(), "exactly the drain is enough"),
                () -> assertEquals(0.0, left, EPS, "and spends it all"),
                // (long)(120 * 0.9 / 0.1f * -51.2) = -55295; (15360 + 55295) >> 8 = 275
                () -> assertEquals(List.of("You need at least 275 fuel units to throw a nuclear cluster bomb!"), msgs(m2),
                    "the cluster drain uses the nuke's mass"),
                () -> assertEquals(4, p.items[MINE], "the refused nuke keeps the mines"));
        }

        @Test
        @DisplayName("baseMineRange: no mining within range of another non-immune, non-tank player's base")
        void baseMining() {
            setup("");
            w.items.baseMineRange = 1;
            Base qb = w.map.bases().get(q.homeBase);
            Base pb = w.map.bases().get(p.homeBase);
            double x = (qb.bx() + 1.5) * Const.BLOCK_SZ;
            double y = (qb.by() + 0.5) * Const.BLOCK_SZ;
            long m0 = w.events.seq();
            s.mines.placeGeneral(p, 0, 0, x, y, 0, 0, 0);
            List<String> refused = msgs(m0);
            int to = receiverOfLast();
            int afterRefused = o.count();
            double fuelRefused = p.fuel;
            s.mines.placeGeneral(p, 0, 0, x, y + Const.BLOCK_SZ, 0, 0, 0);
            int diagonal = o.count();
            s.mines.placeGeneral(p, 0, 0, (pb.bx() + 0.5) * Const.BLOCK_SZ, (pb.by() + 0.5) * Const.BLOCK_SZ, 0, 0, 0);
            int ownBase = o.count();
            q.tank = true;
            s.mines.placeGeneral(p, 0, 0, x, y, 0, 0, 0);
            int tank = o.count();
            q.tank = false;
            w.params.teamPlay = true;
            w.params.teamImmunity = true;
            p.team = 1;
            q.team = 1;
            s.mines.placeGeneral(p, 0, 0, x, y, 0, 0, 0);
            int mate = o.count();
            w.items.baseMineRange = 0;
            w.params.teamPlay = false;
            s.mines.placeGeneral(p, 0, 0, x, y, 0, 0, 0);
            assertAll(
                () -> assertEquals(List.of("No base mining!"), refused, "1 block from another base (1 <= 1) is refused"),
                () -> assertEquals(p.id, to, "told to the layer"),
                () -> assertEquals(0, afterRefused, "and nothing is placed"),
                () -> assertEquals(FUEL0, fuelRefused, EPS, "nor fuel spent"),
                () -> assertEquals(1, diagonal, "1 block across and 1 up (2 > 1) is allowed"),
                () -> assertEquals(2, ownBase, "the layer's own base is allowed"),
                () -> assertEquals(3, tank, "a tank's base does not count"),
                () -> assertEquals(4, mate, "a team-immune mate's base does not count"),
                () -> assertEquals(5, o.count(), "without baseMineRange anything goes"));
        }

        @Test
        @DisplayName("baseMineRange measures whole blocks from the base in x and y (bases above row 0)")
        void baseMiningBlocks() {
            String[] rows = SimFixture.open(20, 20, "");
            char[] r = rows[19 - 5].toCharArray();
            r[3] = '_';
            r[9] = '_';
            rows[19 - 5] = new String(r);
            s = com.fulcrologic.jxpilot.server.TestWorlds.sim(FRAME, rows);
            w = s.world;
            o = w.objects;
            p = ready(s, "P", 0, 200, 300);
            q = ready(s, "Q", 0, 500, 300);
            p.items[MINE] = 5;
            w.items.baseMineRange = 1;
            Base qb = w.map.bases().get(q.homeBase);
            double bx = qb.bx() * Const.BLOCK_SZ;
            double by = qb.by() * Const.BLOCK_SZ;
            s.mines.placeGeneral(p, 0, 0, bx, by + Const.BLOCK_SZ, 0, 0, 0);
            int above = o.count();
            s.mines.placeGeneral(p, 0, 0, bx, by - 1.5 * Const.BLOCK_SZ, 0, 0, 0);
            int belowTrunc = o.count();
            s.mines.placeGeneral(p, 0, 0, bx, by - 2 * Const.BLOCK_SZ, 0, 0, 0);
            int twoBelow = o.count();
            s.mines.placeGeneral(p, 0, 0, bx + 2 * Const.BLOCK_SZ, by, 0, 0, 0);
            int across = o.count();
            w.items.baseMineRange = 2;
            int before2 = o.count();
            s.mines.placeGeneral(p, 0, 0, bx + 2 * Const.BLOCK_SZ, by, 0, 0, 0);
            int two = o.count() - before2;
            s.mines.placeGeneral(p, 0, 0, bx + 2 * Const.BLOCK_SZ, by + Const.BLOCK_SZ, 0, 0, 0);
            int twoOne = o.count() - before2;
            assertAll(
                () -> assertEquals(0, two, "range 2: two blocks across (4 <= 4) is refused"),
                () -> assertEquals(1, twoOne, "range 2: two across and one up (5 > 4) is allowed"),
                () -> assertEquals(5, qb.by(), "Q's base is at block row 5"),
                () -> assertEquals(0, above, "one block above the base corner (0 + 1 <= 1) is refused"),
                () -> assertEquals(0, belowTrunc, "1.5 blocks below truncates to -1 (C's (int) cast): refused"),
                () -> assertEquals(1, twoBelow, "two blocks below (4 > 1) is allowed"),
                () -> assertEquals(2, across, "two blocks across (4 > 1) is allowed"));
        }

        @Test
        @DisplayName("minis spread around the heading, slowing to zero over 18 frames, sharing mass, life and range")
        void minis() {
            setup("");
            p.dir = 10;
            int mods = Mods.withSpread(Mods.withMini(0, 2), 1);
            w.rng.seed(11);
            Rng ref = new Rng(11);
            s.mines.placeGeneral(p, 0, Obj.GRAVITY, 100, 100, 1.5, -2, mods);
            int[] dirs = new int[3];
            float[] mvx = new float[3];
            float[] mvy = new float[3];
            for (int i = 0; i < 3; i++) {
                // shot.c:240-266: space = RES/3 = 42; dir = i*42 + 21 + (3-2)*64 + pl.dir + (int)((rfrac-0.5)*42*0.5)
                int dir = i * 42 + 21 + 64 + 10;
                dir += (int) ((ref.nextFloat() - 0.5f) * 42 * 0.5f);
                dirs[i] = Trig.mod(dir);
                mvx[i] = 8 * Trig.tcos(dirs[i]) / 2.0f;
                mvy[i] = 8 * Trig.tsin(dirs[i]) / 2.0f;
            }
            assertAll(
                () -> assertEquals(3, o.count(), "mini 2 gives three mines"),
                () -> assertEquals(10f, o.mass[o.active[0]], "each a third of the mass"),
                () -> assertEquals(100.0, o.life[o.active[1]], EPS, "and a third of the life"),
                () -> assertEquals(33, o.plRange[o.active[2]], "pl_range (int)(100 / 3)"),
                () -> assertEquals(4, p.items[MINE], "one mine is used"),
                () -> assertEquals((float) (mvx[0] + 1.5 * 1.3), o.vx[o.active[0]], "first: spread + thrown vx * 1.3"),
                () -> assertEquals((float) (mvy[0] + -2 * 1.3), o.vy[o.active[0]], "first: spread + thrown vy * 1.3"),
                () -> assertEquals((float) (mvx[2] + 1.5 * 1.3), o.vx[o.active[2]], "third mine vx"),
                () -> assertEquals((float) (mvy[1] + -2 * 1.3), o.vy[o.active[1]], "second mine vy"),
                () -> assertEquals(-mvx[1] / 18, o.accX[o.active[1]], "the spread slows by mv / 18 per frame"),
                () -> assertEquals(-mvy[2] / 18, o.accY[o.active[2]], "the spread slows by mv / 18 per frame (y)"),
                () -> assertEquals(18, o.spreadLeft[o.active[0]], "for MINI_MINE_SPREAD_TIME frames"),
                () -> assertTrue(Math.abs(Trig.mod(dirs[1] - dirs[0]) - 42) <= 20, "the mines are RES/3 apart, each jittered by at most +-10"),
                () -> assertEquals(Obj.GRAVITY | Obj.OWNERIMMUNE, o.status[o.active[2]], "thrown mines have GRAVITY"));
        }

        @Test
        @DisplayName("an unowned mini mine spreads from direction 0")
        void unownedMinis() {
            setup("");
            w.rng.seed(4);
            Rng ref = new Rng(4);
            s.mines.placeGeneral(null, 0, 0, 100, 100, 0, 0, Mods.withMini(0, 1));
            // space = 64; dir = 0*64 + 32 + 0 + 0 + (int)((r-0.5)*16)
            int dir = Trig.mod(32 + (int) ((ref.nextFloat() - 0.5f) * 64 * 0.5f));
            assertAll(
                () -> assertEquals(2, o.count(), "two mines"),
                () -> assertEquals(8 * Trig.tcos(dir) / 1.0f, o.vx[o.active[0]], "full spread speed 8 without spread"),
                () -> assertEquals(50, o.plRange[o.active[1]], "pl_range 100 / 2"));
        }

        @Test
        @DisplayName("nothing without room for all minis; outside a flat world nothing; wrapping maps wrap")
        void roomAndEdges() {
            setup("");
            int mods = Mods.withMini(0, 2);
            while (o.count() < o.capacity - 2) {
                o.alloc();
            }
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, mods);
            int full = o.count();
            o.free(o.active[0]);
            s.mines.placeGeneral(p, 0, 0, 100, 100, 0, 0, mods);
            int fits = o.count();
            setup("");
            s.mines.placeGeneral(p, 0, 0, -1, 100, 0, 0, 0);
            s.mines.placeGeneral(p, 0, 0, 100, 700, 0, 0, 0);
            s.mines.placeGeneral(p, 0, 0, 700, 100, 0, 0, 0);
            s.mines.placeGeneral(p, 0, 0, 100, -1, 0, 0, 0);
            int outside = o.count();
            double fuelOutside = p.fuel;
            s.mines.placeGeneral(p, 0, 0, 0, 0, 0, 0, 0);
            int corner = o.count();
            Simulation ws = sim(FRAME + "edgeWrap: yes\n", 20, 20, "_");
            Player wp = ready(ws, "W", 0, 100, 100);
            wp.items[MINE] = 5;
            ws.mines.placeGeneral(wp, 0, 0, -10, 710, 0, 0, 0);
            ws.mines.placeGeneral(wp, 0, 0, 705, -5, 0, 0, 0);
            ws.mines.placeGeneral(wp, 0, 0, 0, 0, 0, 0, 0);
            ws.mines.placeGeneral(wp, 0, 0, 700, 700, 0, 0, 0);
            Objects wo = ws.world.objects;
            assertAll(
                () -> assertEquals(o.capacity - 2, full, "count + mini >= capacity: nothing"),
                () -> assertEquals(o.capacity, fits, "count + mini = capacity - 1: all three fit"),
                () -> assertEquals(0, outside, "x or y outside [0, size) places nothing"),
                () -> assertEquals(FUEL0, fuelOutside, EPS, "and costs nothing"),
                () -> assertEquals(1, corner, "(0, 0) is inside"),
                () -> assertEquals(Click.fromPixels(690), wo.cx[wo.active[0]], "x -10 wraps to 690"),
                () -> assertEquals(Click.fromPixels(10), wo.cy[wo.active[0]], "y 710 wraps to 10"),
                () -> assertEquals(Click.fromPixels(5), wo.cx[wo.active[1]], "x 705 wraps to 5"),
                () -> assertEquals(Click.fromPixels(695), wo.cy[wo.active[1]], "y -5 wraps to 695"),
                () -> assertEquals(4, wo.count(), "(0, 0) and (700, 700) are placed too"),
                () -> assertEquals(0, wo.cx[wo.active[2]], "x 0 stays 0"),
                () -> assertEquals(0, wo.cy[wo.active[2]], "y 0 stays 0"),
                () -> assertEquals(0, wo.cx[wo.active[3]], "x 700 (the width) wraps to 0"),
                () -> assertEquals(0, wo.cy[wo.active[3]], "y 700 wraps to 0"));
        }
    }

    @Nested
    @DisplayName("placeMine / placeMovingMine (Place_mine, Place_moving_mine)")
    class Place {
        @Test
        @DisplayName("drop: a still mine at the ship iff the player has mines and no shield/phasing (or shieldedMining)")
        void drop() {
            setup("");
            p.vx = 3;
            p.items[MINE] = 0;
            s.mines.placeMine(p);
            int none = o.count();
            p.items[MINE] = 3;
            p.used |= Has.SHIELD;
            s.mines.placeMine(p);
            int shielded = o.count();
            p.used = Has.PHASING_DEVICE;
            s.mines.placeMine(p);
            int phased = o.count();
            w.items.shieldedMining = true;
            s.mines.placeMine(p);
            int h = o.active[0];
            assertAll(
                () -> assertEquals(0, none, "no mines: nothing"),
                () -> assertEquals(0, shielded, "shielded: nothing"),
                () -> assertEquals(0, phased, "phased: nothing"),
                () -> assertEquals(1, o.count(), "with shieldedMining a phased player mines"),
                () -> assertEquals(p.cx, o.cx[h], "at the ship"),
                () -> assertEquals(0f, o.vx[h], "still, whatever the ship's speed"),
                () -> assertEquals(Obj.OWNERIMMUNE, o.status[h], "without GRAVITY"),
                () -> assertEquals(p.team, o.team[h], "of the ship's team"));
        }

        @Test
        @DisplayName("with minMineSpeed a drop throws a moving mine")
        void dropThrows() {
            setup("");
            w.items.minMineSpeed = 5;
            p.vx = 3;
            p.vy = 4;
            p.velocity = 5;
            s.mines.placeMine(p);
            int h = o.active[0];
            assertAll(
                () -> assertEquals(Obj.GRAVITY | Obj.OWNERIMMUNE, o.status[h], "a GRAVITY mine"),
                () -> assertEquals((float) (3 * 1.3), o.vx[h], "with the ship's velocity * 1.3"));
        }

        @Test
        @DisplayName("throw: the ship's velocity, raised to minMineSpeed when slower, along the heading when nearly still")
        void moving() {
            setup("");
            p.vx = 1.5f;
            p.vy = 2;
            p.velocity = 2.5f;
            s.mines.placeMovingMine(p);
            int plain = o.active[0];
            w.items.minMineSpeed = 5;
            s.mines.placeMovingMine(p);
            int scaled = o.active[1];
            p.velocity = 5;
            s.mines.placeMovingMine(p);
            int fast = o.active[2];
            p.velocity = 0.99f;
            p.dir = 16;
            s.mines.placeMovingMine(p);
            int still = o.active[3];
            p.velocity = 1;
            p.vx = 0.6f;
            p.vy = 0.8f;
            s.mines.placeMovingMine(p);
            int one = o.active[4];
            assertAll(
                () -> assertEquals((float) (1.5 * 1.3), o.vx[plain], "minMineSpeed 0: the ship's vx * 1.3"),
                () -> assertEquals(Obj.GRAVITY | Obj.OWNERIMMUNE, o.status[plain], "a thrown GRAVITY mine"),
                () -> assertEquals((float) (3 * 1.3), o.vx[scaled], "speed 2.5 < 5: vx scaled by 5/2.5"),
                () -> assertEquals((float) (4 * 1.3), o.vy[scaled], "vy scaled by 5/2.5"),
                () -> assertEquals((float) (1.5 * 1.3), o.vx[fast], "speed 5 is not below minMineSpeed: unchanged"),
                () -> assertEquals((float) (5 * Trig.tcos(16) * 1.3), o.vx[still], "below speed 1: minMineSpeed along the heading (x)"),
                () -> assertEquals((float) (5 * Trig.tsin(16) * 1.3), o.vy[still], "and y"),
                () -> assertEquals((float) (0.6f * 5f * 1.3), o.vx[one], "speed exactly 1 is scaled, not redirected"),
                () -> assertEquals(0, p.items[MINE], "each throw used a mine"));
        }

        @Test
        @DisplayName("throw needs mines and no shield/phasing unless shieldedMining")
        void movingRefused() {
            setup("");
            p.items[MINE] = 0;
            s.mines.placeMovingMine(p);
            int none = o.count();
            p.items[MINE] = 2;
            p.used |= Has.SHIELD;
            s.mines.placeMovingMine(p);
            int shielded = o.count();
            w.items.shieldedMining = true;
            s.mines.placeMovingMine(p);
            assertAll(
                () -> assertEquals(0, none, "no mines: nothing"),
                () -> assertEquals(0, shielded, "shielded: nothing"),
                () -> assertEquals(1, o.count(), "shieldedMining allows it"));
        }
    }

    @Nested
    @DisplayName("detonate (Detonate_mines)")
    class Detonate {
        @Test
        @DisplayName("the closest mine the player controls gets life 0; others' and reprogrammed-away mines are kept")
        void closest() {
            setup("");
            int far = obj(w, Obj.MINE, p.id, 140, 300, 0, 0, 30, 100);
            int near = obj(w, Obj.MINE, p.id, 250, 300, 0, 0, 30, 100);
            int stolen = obj(w, Obj.MINE, q.id, 210, 300, 0, 0, 30, 100);
            w.objects.mineOwner[stolen] = p.id;
            int notMine = obj(w, Obj.SMART_SHOT, p.id, 200, 300, 0, 0, 5, 100);
            s.mines.detonate(p);
            double nearLife = o.life[near];
            double farLife = o.life[far];
            s.mines.detonate(q);
            assertAll(
                () -> assertEquals(0.0, nearLife, EPS, "the nearest own mine detonates"),
                () -> assertEquals(100.0, farLife, EPS, "the one 60 px away on the other side is kept"),
                () -> assertEquals(0.0, o.life[stolen], EPS, "a reprogrammed mine is detonated by its reprogrammer"),
                () -> assertEquals(100.0, o.life[notMine], EPS, "missiles are not mines"));
        }

        @Test
        @DisplayName("distance counts in y too")
        void yDistance() {
            setup("");
            int lower = obj(w, Obj.MINE, p.id, 200, 230, 0, 0, 30, 100);
            int upper = obj(w, Obj.MINE, p.id, 200, 360, 0, 0, 30, 100);
            s.mines.detonate(p);
            assertAll(
                () -> assertEquals(0.0, o.life[upper], EPS, "60 px above detonates"),
                () -> assertEquals(100.0, o.life[lower], EPS, "70 px below is kept"));
        }

        @Test
        @DisplayName("a tie goes to the first in pool order; phasing or no mines detonates nothing")
        void tieAndPhased() {
            setup("");
            int a = obj(w, Obj.MINE, p.id, 250, 300, 0, 0, 30, 100);
            int b = obj(w, Obj.MINE, p.id, 150, 300, 0, 0, 30, 100);
            p.used |= Has.PHASING_DEVICE;
            s.mines.detonate(p);
            double phased = o.life[a] + o.life[b];
            p.used = 0;
            s.mines.detonate(p);
            s.mines.detonate(q);
            assertAll(
                () -> assertEquals(200.0, phased, EPS, "phased: nothing detonates"),
                () -> assertEquals(0.0, o.life[a], EPS, "equal distance: the first wins"),
                () -> assertEquals(100.0, o.life[b], EPS, "the second is kept"));
        }

        @Test
        @DisplayName("distances wrap on wrapping maps")
        void wraps() {
            Simulation ws = sim(FRAME + "edgeWrap: yes\n", 20, 20, "_");
            World ww = ws.world;
            Player wp = ready(ws, "W", 0, 20, 300);
            int across = obj(ww, Obj.MINE, wp.id, 690, 300, 0, 0, 30, 100);
            int inside = obj(ww, Obj.MINE, wp.id, 80, 300, 0, 0, 30, 100);
            ws.mines.detonate(wp);
            assertAll(
                () -> assertEquals(0.0, ww.objects.life[across], EPS, "30 px across the edge is closest"),
                () -> assertEquals(100.0, ww.objects.life[inside], EPS, "60 px inside is farther"));
        }
    }

    @Nested
    @DisplayName("move (Move_mine)")
    class Move {
        @Test
        @DisplayName("confusion counts down and ends when the counter reaches 0")
        void confusion() {
            setup("");
            int a = obj(w, Obj.MINE, p.id, 100, 100, 0, 0, 30, 100);
            o.status[a] = Obj.CONFUSED;
            o.counter[a] = 2;
            s.mines.move(a);
            int st1 = o.status[a];
            int c1 = o.counter[a];
            s.mines.move(a);
            int b = obj(w, Obj.MINE, p.id, 100, 100, 0, 0, 30, 100);
            o.counter[b] = 5;
            s.mines.move(b);
            assertAll(
                () -> assertEquals(Obj.CONFUSED, st1, "2 -> 1: still confused"),
                () -> assertEquals(1, c1, "the counter went down"),
                () -> assertEquals(0, o.status[a], "1 -> 0: confusion ends"),
                () -> assertEquals(0, o.counter[a], "at 0"),
                () -> assertEquals(5, o.counter[b], "an unconfused mine keeps its counter"));
        }

        @Test
        @DisplayName("the fuse counts down and owner immunity ends at 0; a zero fuse keeps immunity")
        void fuse() {
            setup("");
            int a = obj(w, Obj.MINE, p.id, 100, 100, 0, 0, 30, 100);
            o.status[a] = Obj.OWNERIMMUNE;
            o.info[a] = 2;
            s.mines.move(a);
            int st1 = o.status[a];
            s.mines.move(a);
            int z = obj(w, Obj.MINE, p.id, 100, 100, 0, 0, 30, 100);
            o.status[z] = Obj.OWNERIMMUNE;
            s.mines.move(z);
            int n = obj(w, Obj.MINE, p.id, 100, 100, 0, 0, 30, 100);
            o.info[n] = 4;
            s.mines.move(n);
            assertAll(
                () -> assertEquals(Obj.OWNERIMMUNE, st1, "fuse 2 -> 1: still immune"),
                () -> assertEquals(0, o.status[a], "fuse 1 -> 0: immunity ends"),
                () -> assertEquals(0, o.info[a], "fuse at 0"),
                () -> assertEquals(Obj.OWNERIMMUNE, o.status[z], "fuse 0 (mineFuseTime 0): immune for life"),
                () -> assertEquals(0, o.info[z], "and the fuse stays 0"),
                () -> assertEquals(4, o.info[n], "a mine that is not owner-immune keeps its fuse"));
        }

        @Test
        @DisplayName("a mini mine's spread acceleration stops once the spread time is used up")
        void spread() {
            setup("");
            int a = obj(w, Obj.MINE, p.id, 100, 100, 0, 0, 30, 100);
            o.mods[a] = Mods.withMini(0, 1);
            o.spreadLeft[a] = 1;
            o.accX[a] = -0.5f;
            o.accY[a] = 0.25f;
            s.mines.move(a);
            float ax1 = o.accX[a];
            s.mines.move(a);
            int b = obj(w, Obj.MINE, p.id, 100, 100, 0, 0, 30, 100);
            o.spreadLeft[b] = 0;
            o.accX[b] = -0.5f;
            s.mines.move(b);
            assertAll(
                () -> assertEquals(-0.5f, ax1, "spread 1 -> 0: still accelerating"),
                () -> assertEquals(0f, o.accX[a], "spread 0: x acceleration stops"),
                () -> assertEquals(0f, o.accY[a], "and y"),
                () -> assertEquals(-1, o.spreadLeft[a], "the post-decrement leaves -1"),
                () -> assertEquals(-0.5f, o.accX[b], "a non-mini mine keeps its acceleration"),
                () -> assertEquals(0, o.spreadLeft[b], "and its spread time"));
        }
    }

    @Nested
    @DisplayName("collide (MineCollision)")
    class Collide {
        @Test
        @DisplayName("a shot within mineShotDetonateDistance + its radius destroys the mine and itself")
        void detonates() {
            setup("");
            w.items.mineShotDetonateDistance = 10;
            int m = obj(w, Obj.MINE, p.id, 300, 300, 0, 0, 30, 100);
            int hit = obj(w, Obj.TORPEDO, q.id, 314, 300, 0, 0, 5, 100);
            o.plRadius[hit] = 5;
            int m2 = obj(w, Obj.MINE, p.id, 300, 500, 0, 0, 30, 100);
            int miss = obj(w, Obj.SHOT, q.id, 315, 500, 0, 0, 0.1, 100);
            o.plRadius[miss] = 5;
            w.grid.rebuild(o);
            s.mines.collide();
            assertAll(
                () -> assertEquals(0.0, o.life[m], EPS, "the mine 14 px from a radius-5 torpedo (closer than 10 + 5) explodes"),
                () -> assertEquals(0.0, o.life[hit], EPS, "and so does the torpedo"),
                () -> assertEquals(100.0, o.life[m2], EPS, "exactly 15 px away the other mine survives (in_range is strict)"),
                () -> assertEquals(100.0, o.life[miss], EPS, "and the shot too"));
        }

        @Test
        @DisplayName("nothing without mineShotDetonateDistance; debris, dying shots and dying mines are ignored")
        void ignored() {
            setup("");
            int m = obj(w, Obj.MINE, p.id, 300, 300, 0, 0, 30, 100);
            int shot = obj(w, Obj.SHOT, q.id, 300, 300, 0, 0, 0.1, 100);
            w.grid.rebuild(o);
            s.mines.collide();
            double off = o.life[m] + o.life[shot];
            w.items.mineShotDetonateDistance = 10;
            o.life[shot] = 0;
            int debris = obj(w, Obj.DEBRIS, q.id, 300, 300, 0, 0, 4.5, 100);
            int dead = obj(w, Obj.MINE, p.id, 300, 300, 0, 0, 30, 0);
            int other = obj(w, Obj.MINE, p.id, 300, 600, 0, 0, 30, 100);
            int live = obj(w, Obj.HEAT_SHOT, q.id, 300, 600, 0, 0, 5, 100);
            o.life[other] = 100;
            w.grid.rebuild(o);
            s.mines.collide();
            assertAll(
                () -> assertEquals(200.0, off, EPS, "distance 0 disables mine collisions"),
                () -> assertEquals(100.0, o.life[m], EPS, "a dying shot and debris do not set a mine off"),
                () -> assertEquals(100.0, o.life[debris], EPS, "debris is untouched"),
                () -> assertEquals(0.0, o.life[other], EPS, "a heat-seeker on a mine sets it off"),
                () -> assertEquals(0.0, o.life[live], EPS, "and dies"),
                () -> assertEquals(0.0, o.life[dead], EPS, "a dying mine stays dying"));
        }

        @Test
        @DisplayName("a dying mine does not take a shot with it, and each mine stops at its first hit")
        void firstHitOnly() {
            setup("");
            w.items.mineShotDetonateDistance = 10;
            int dead = obj(w, Obj.MINE, p.id, 300, 300, 0, 0, 30, 0);
            int nearDead = obj(w, Obj.SHOT, q.id, 300, 300, 0, 0, 0.1, 100);
            int m = obj(w, Obj.MINE, p.id, 300, 500, 0, 0, 30, 100);
            int first = obj(w, Obj.CANNON_SHOT, Obj.NO_ID, 300, 500, 0, 0, 0.4, 100);
            int second = obj(w, Obj.SMART_SHOT, q.id, 300, 500, 0, 0, 5, 100);
            w.grid.rebuild(o);
            s.mines.collide();
            assertAll(
                () -> assertEquals(100.0, o.life[nearDead], EPS, "the shot on a dying mine survives"),
                () -> assertEquals(0.0, o.life[m], EPS, "the live mine explodes"),
                () -> assertEquals(0.0, o.life[second], EPS, "with the first object of its cell (newest first)"),
                () -> assertEquals(100.0, o.life[first], EPS, "the other shot survives"),
                () -> assertEquals(0.0, o.life[dead], EPS, "the dying mine is unchanged"));
        }
    }

    @Nested
    @DisplayName("playerHit (Player_collides_with_mine)")
    class PlayerHit {
        int mine(int controller, int layer, int status) {
            int h = obj(w, Obj.MINE, controller, 300, 300, 0, 0, 30, 100);
            o.mineOwner[h] = layer;
            o.status[h] = status;
            return h;
        }

        @Test
        @DisplayName("a mine controlled by its layer: \"dropped  by\" (C's two spaces) and the layer scores")
        void layer() {
            setup("");
            w.items.mineScoreMult = 0.5f;
            long m0 = w.events.seq();
            s.mines.playerHit(q, mine(p.id, p.id, 0));
            List<String> dropped = msgs(m0);
            double ps = p.score;
            double qs = q.score;
            long m1 = w.events.seq();
            s.mines.playerHit(q, mine(p.id, p.id, Obj.GRAVITY));
            // Rate(0, 0) = RATE_SIZE / 2 = 10; * mineScoreMult 0.5 = 5 (score.c Rate, collision.c:1076)
            assertAll(
                () -> assertTrue(dropped.contains("Q hit a mine dropped  by P."), "C's message with two spaces"),
                () -> assertEquals(5.0, ps, 1e-6, "the layer gains Rate * mineScoreMult"),
                () -> assertEquals(-5.0, qs, 1e-6, "the victim loses it"),
                () -> assertTrue(msgs(m1).contains("Q hit a bomb thrown  by P."), "a thrown mine: \"bomb thrown  by\""));
        }

        @Test
        @DisplayName("an unowned, never-owned mine: a plain message and no score")
        void unowned() {
            setup("");
            w.items.mineScoreMult = 0.5f;
            long m0 = w.events.seq();
            s.mines.playerHit(q, mine(Obj.NO_ID, Obj.NO_ID, 0));
            assertAll(
                () -> assertEquals(List.of("Q hit a mine."), msgs(m0), "\"X hit a mine.\""),
                () -> assertEquals(0.0, q.score, EPS, "no score lost"),
                () -> assertEquals(0.0, p.score, EPS, "no score gained"));
        }

        @Test
        @DisplayName("reprogrammed mines name the reprogrammer, who scores; a left layer is not named")
        void reprogrammed() {
            s = sim(FRAME, 20, 20, "___");
            w = s.world;
            o = w.objects;
            p = ready(s, "P", 0, 200, 300);
            q = ready(s, "Q", 0, 500, 300);
            w.items.mineScoreMult = 0.5f;
            Player r = ready(s, "R", 0, 600, 600);
            long m0 = w.events.seq();
            s.mines.playerHit(q, mine(r.id, Obj.NO_ID, 0));
            List<String> noLayer = msgs(m0);
            double rs = r.score;
            long m1 = w.events.seq();
            s.mines.playerHit(q, mine(r.id, p.id, Obj.GRAVITY));
            List<String> both = msgs(m1);
            long m2 = w.events.seq();
            s.mines.playerHit(q, mine(Obj.NO_ID, p.id, 0));
            List<String> jerk = msgs(m2);
            double ps = p.score;
            assertAll(
                () -> assertTrue(noLayer.contains("Q hit a mine reprogrammed by R."), "layer gone: reprogrammed by R"),
                () -> assertEquals(5.0, rs, 1e-6, "the reprogrammer scores"),
                () -> assertTrue(both.contains("Q hit a bomb thrown  by P and reprogrammed by R."), "both named"),
                () -> assertTrue(jerk.contains("Q hit a mine dropped  by P and reprogrammed by some jerk."),
                    "an unowned controller is \"some jerk\""),
                () -> assertEquals(0.0, ps, EPS, "the layer of a reprogrammed mine does not score"));
        }

        @Test
        @DisplayName("hitting one's own mine names oneself; the plus and minus cancel")
        void ownMine() {
            setup("");
            w.items.mineScoreMult = 0.5f;
            long m0 = w.events.seq();
            s.mines.playerHit(p, mine(p.id, p.id, 0));
            assertAll(
                () -> assertTrue(msgs(m0).contains("P hit a mine dropped  by P."), "self-named"),
                () -> assertEquals(0.0, p.score, 1e-6, "Score_players(self, +sc, self, -sc) nets zero"));
        }

        @Test
        @DisplayName("the hit message describes the mine's modifiers as a hit (no plural)")
        void modsInMessage() {
            setup("");
            long m0 = w.events.seq();
            int h = mine(p.id, p.id, 0);
            o.mods[h] = Mods.withMini(NUKE, 1);
            s.mines.playerHit(q, h);
            assertTrue(msgs(m0).contains("Q hit a mini nuclear mine dropped  by P."), "a mini nuclear mine");
        }
    }
}
