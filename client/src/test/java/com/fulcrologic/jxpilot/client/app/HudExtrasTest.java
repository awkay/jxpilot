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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.game.hud.Hud;
import com.fulcrologic.jxpilot.client.game.hud.LoseItemSelection;
import com.fulcrologic.jxpilot.client.paint.HudPainter;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.fulcrologic.jxpilot.client.app.AppFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class HudExtrasTest {
    final HudExtras e = new HudExtras();
    final Hud hud = new Hud();

    static int[] items(int item, int count) {
        int[] c = new int[Item.COUNT];
        c[item] = count;
        return c;
    }

    @Nested
    @DisplayName("fill")
    class Fill {
        @Test
        @DisplayName("lock on a foe in view: name, lives, distance, direction, shape, not a friend; items; autopilot")
        void lockFoe() {
            AppFixture f = new AppFixture(true).players().score(FOE, 0, 2).twice(x -> x
                .self(Records.ShipFlags.AUTOPILOT, 100, 100, 0, FOE)
                .ship(FOE, 0, 400, 100, 0)
                .items(items(Item.AFTERBURNER.ordinal(), 2)));
            e.fill(f.game, hud, 800, 600, 1, 100, 100, 102);
            int[] expectedItems = new int[Item.COUNT];
            Arrays.fill(expectedItems, -1);
            expectedItems[Item.AFTERBURNER.ordinal()] = 2;
            ShipShape d = ShipShape.defaultShip();
            assertAll(
                () -> assertTrue(e.active, "own ship: active"),
                () -> assertEquals(400, e.hx, "hx = view / 2"),
                () -> assertEquals(300, e.hy, "hy = view / 2"),
                () -> assertTrue(e.lock, "lock target known"),
                () -> assertEquals("Foe", e.lockName.toString(), "name"),
                () -> assertTrue(e.limitedLives, "limitedLives from the options"),
                () -> assertEquals(2, e.lockLives, "lives from SCORE"),
                () -> assertEquals(300, e.lockDist, "300 px to the right"),
                () -> assertEquals(0, e.lockDir, "direction 0 (east)"),
                () -> assertFalse(e.lockFriend, "other team"),
                () -> assertTrue(e.warningPhase, "loops 102 even"),
                () -> assertEquals(HudPainter.mapDiag(20, 10), e.mapDiag, "map diagonal"),
                () -> assertEquals(d.numPoints(), e.lockShipPoints, "default ship points"),
                () -> assertEquals((float) d.ptX[1], e.lockShipX[1], "point x at heading 0"),
                () -> assertEquals((float) d.ptY[1], e.lockShipY[1], "point y at heading 0"),
                () -> assertArrayEquals(expectedItems, e.itemCounts, "counts above 0, else -1"),
                () -> assertTrue(e.autopilot, "AUTOPILOT flag"));
        }

        @Test
        @DisplayName("the server's lock distance and direction (SELF) win when non-zero; modifiers as C's string")
        void serverLockAndMods() {
            int mods = Records.Self.MOD_CLUSTER | 2 << Records.Self.MOD_MINI_SHIFT;
            AppFixture f = new AppFixture(true).players().twice(x -> x
                .selfControls(45, 40, FOE, 777, 32, mods)
                .ship(FOE, 0, 400, 100, 0));
            e.fill(f.game, hud, 800, 600, 1, 350, 175, 100);
            AppFixture g = new AppFixture(true).players().twice(x -> x
                .selfControls(45, 40, FOE, 0, 0, 0)
                .ship(FOE, 0, 650, 175, 0));
            HudExtras e2 = new HudExtras();
            e2.fill(g.game, hud, 800, 600, 1, 350, 175, 100);
            assertAll(
                () -> assertEquals(777, e.lockDist, "SELF lock distance (C lock_dist)"),
                () -> assertEquals(32, e.lockDir, "SELF lock direction"),
                () -> assertEquals("C X3", e.mods.toString(), "modifier string"),
                () -> assertEquals(300, e2.lockDist, "no server distance: computed from the ships"),
                () -> assertEquals(0, e2.lockDir, "and the direction"),
                () -> assertEquals("", e2.mods.toString(), "no modifiers: empty"));
        }

        @Test
        @DisplayName("lock across the wrap seam on a team mate; odd loops; no autopilot")
        void lockMateWrapped() {
            AppFixture f = new AppFixture(true).players().twice(x -> x
                .self(0, 50, 100, 0, MATE)
                .ship(MATE, 0, 650, 100, 0));
            e.fill(f.game, hud, 800, 600, 1, 50, 100, 103);
            assertAll(
                () -> assertEquals(100, e.lockDist, "wrapped: 100 px to the left"),
                () -> assertEquals(64, e.lockDir, "west is RES 64"),
                () -> assertTrue(e.lockFriend, "same team in team play"),
                () -> assertFalse(e.warningPhase, "odd loops"),
                () -> assertFalse(e.autopilot, "no autopilot"));
        }

        @Test
        @DisplayName("a locked player out of view has distance 0; an unknown lock id is no lock")
        void lockOutOfView() {
            AppFixture f = new AppFixture(true).players().twice(x -> x.self(0, 50, 100, 0, FOE));
            e.fill(f.game, hud, 800, 600, 1, 50, 100, 0);
            boolean lock = e.lock;
            int dist = e.lockDist;
            AppFixture g = new AppFixture(true).players().twice(x -> x.self(0, 50, 100, 0, 44));
            e.fill(g.game, hud, 800, 600, 1, 50, 100, 0);
            assertAll(
                () -> assertTrue(lock, "known player locked"),
                () -> assertEquals(0, dist, "not in view: 0"),
                () -> assertFalse(e.lock, "unknown id: no lock"));
        }

        @Test
        @DisplayName("without an own ship or with the HUD off: inactive, no lock, no autopilot")
        void inactive() {
            AppFixture f = new AppFixture(true).players().twice(x -> x
                .self(Records.ShipFlags.AUTOPILOT, 100, 100, 0, FOE));
            hud.showHud = false;
            e.fill(f.game, hud, 800, 600, 1, 100, 100, 0);
            boolean off = e.active || e.lock || e.autopilot;
            AppFixture none = new AppFixture(true).players();
            none.game.frame(T0, 0);
            hud.showHud = true;
            e.fill(none.game, hud, 800, 600, 1, 100, 100, 0);
            assertAll(
                () -> assertFalse(off, "HUD off: nothing"),
                () -> assertFalse(e.active, "no own ship: inactive"),
                () -> assertFalse(e.lock, "no lock"));
        }

        @Test
        @DisplayName("hx/hy use C's ext_view_width at the zoom and the Hud's zoom is set")
        void zoom() {
            AppFixture f = new AppFixture(true).players().twice(x -> x.self(0, 100, 100, 0, 0xFFFF));
            e.fill(f.game, hud, 801, 600, 2, 100, 100, 0);
            assertAll(
                () -> assertEquals(200, e.hx, "(int)(801 / 2 + 0.5) / 2 = 200"),
                () -> assertEquals(150, e.hy, "300 / 2"),
                () -> assertEquals(2.0, hud.zoom, "Hud zoom set"));
        }
    }

    @Nested
    @DisplayName("items (C showItems / showItemsTime)")
    class Items {
        final int mines = com.fulcrologic.jxpilot.common.Item.MINE.ordinal();
        final int fuel = com.fulcrologic.jxpilot.common.Item.FUEL.ordinal();

        @Test
        @DisplayName("showItems: counts above 0 always, else -1")
        void shown() {
            assertAll(
                () -> assertEquals(3, e.itemCount(mines, 3, 1, 28), "owned"),
                () -> assertEquals(-1, e.itemCount(mines, 0, 1, 28), "none"),
                () -> assertEquals(3, e.itemCount(mines, 3, 0, 28), "no paint: same"));
        }

        @Test
        @DisplayName("showItems off: a change shows the count for showItemsTime * FPS paints, 0 included")
        void timed() {
            e.showItems = false;
            List<Integer> seen = new java.util.ArrayList<>();
            seen.add(e.itemCount(mines, 2, 1, 3));
            seen.add(e.itemCount(mines, 2, 1, 3));
            seen.add(e.itemCount(mines, 2, 0, 3));
            seen.add(e.itemCount(mines, 2, 1, 3));
            seen.add(e.itemCount(mines, 2, 1, 3));
            seen.add(e.itemCount(mines, 2, 0, 3));
            seen.add(e.itemCount(mines, 0, 1, 3));
            seen.add(e.itemCount(mines, 0, 3, 3));
            seen.add(e.itemCount(mines, 5, 2, 3));
            seen.add(e.itemCount(mines, 5, 1, 3));
            seen.add(e.itemCount(mines, 5, 1, 3));
            assertEquals(List.of(2, 2, 2, 2, -1, -1, 0, -1, 5, 5, -1), seen,
                "3 paints after the change to 2; the change to 0 shows '0'; n paints at once count n times");
        }

        @Test
        @DisplayName("fuel is never timed")
        void fuelUntimed() {
            e.showItems = false;
            assertAll(
                () -> assertEquals(500, e.itemCount(fuel, 500, 1, 0), "fuel as with showItems"),
                () -> assertEquals(-1, e.itemCount(fuel, 0, 1, 0), "no fuel"));
        }
    }

    @Nested
    @DisplayName("score objects")
    class ScoreObjects {
        AppFixture crashed() {
            AppFixture f = new AppFixture(true).players().score(SELF, 1, -1)
                .twice(x -> x.self(0, 100, 100, 0, 0xFFFF));
            f.event(Msg.GameEvent.CRASH, SELF, Protocol.NO_ID).scoreObject(-5, 2, 2, "[Wall]").score(SELF, -4, -1);
            return f;
        }

        @Test
        @DisplayName("a SCORE_OBJECT at a block is placed at C's X()/Y() of the block centre")
        void placed() {
            AppFixture f = crashed();
            e.fill(f.game, hud, 800, 600, 1, 100, 100, 0);
            assertAll(
                () -> assertEquals(1, e.scoreObjectCount(), "one object"),
                () -> assertTrue(f.game.scoreObjects().hasPosition(e.scoreObjectSlot(0)), "the crash object"),
                () -> assertEquals(387, e.scoreObjectX(0), "400 + (87 - 100)"),
                () -> assertEquals(313, e.scoreObjectY(0), "300 - (87 - 100)"));
        }

        @Test
        @DisplayName("hidden on counts that are multiples of 3, and when off screen (wrap-aware)")
        void hidden() {
            AppFixture f = crashed();
            f.game.scoreObjects().tick(2);
            e.fill(f.game, hud, 800, 600, 1, 100, 100, 0);
            int atThree = e.scoreObjectCount();
            f.game.scoreObjects().tick(1);
            e.fill(f.game, hud, 800, 600, 1, 100 + 350, 100, 0);
            int farX = e.scoreObjectCount();
            e.fill(f.game, hud, 100, 100, 1, 100 + 60, 100, 0);
            int offLeft = e.scoreObjectCount();
            e.fill(f.game, hud, 100, 100, 1, 100 - 60 + 700, 100, 0);
            int wrapped = e.scoreObjectCount();
            int wrappedX = wrapped > 0 ? e.scoreObjectX(0) : -1;
            assertAll(
                () -> assertEquals(0, atThree, "count 3: blink off"),
                () -> assertEquals(1, farX, "count 4, -363 px wraps to 337 px right in an 800 view: shown"),
                () -> assertEquals(0, offLeft, "73 px left in a 100 px view: off screen"),
                () -> assertEquals(1, wrapped, "47 px right across the seam: shown"),
                () -> assertEquals(97, wrappedX, "50 + 47"));
        }
    }

    @Test
    @DisplayName("direction rounds to RES and wraps into 0..127")
    void direction() {
        assertAll(
            () -> assertEquals(0, HudExtras.direction(1, 0), "east"),
            () -> assertEquals(32, HudExtras.direction(0, 1), "north"),
            () -> assertEquals(96, HudExtras.direction(0, -1), "south: -32 wraps to 96"),
            () -> assertEquals(1, HudExtras.direction(Math.cos(0.04), Math.sin(0.04)), "0.815 rounds up to 1"),
            () -> assertEquals(0, HudExtras.direction(Math.cos(0.02), Math.sin(0.02)), "0.41 rounds down to 0"));
    }

    @Nested
    @DisplayName("loseItem (C painthud.c:558-566)")
    class LoseItem {
        static final int MINE = Item.MINE.ordinal();
        static final int ECM = Item.ECM.ordinal();
        static final int FUEL = Item.FUEL.ordinal();

        AppFixture with(int loseItem) {
            int[] c = new int[Item.COUNT];
            c[MINE] = 3;
            c[FUEL] = 500;
            return new AppFixture(true).players().twice(x -> x.self(0, 100, 100, 0, FOE).loseItems(loseItem, c));
        }

        @Test
        @DisplayName("outlines the server's lose item iff the selection is on and the item is listed")
        void listed() {
            AppFixture f = with(MINE);
            LoseItemSelection sel = new LoseItemSelection();
            e.fill(f.game, hud, 800, 600, 1, 100, 100, 0);
            e.loseItem(sel, 0.1);
            boolean off = e.loseActive;
            int item = e.loseItem;
            sel.press();
            e.loseItem(sel, 0.1);
            boolean on = e.loseActive;
            AppFixture unlisted = with(ECM);
            e.fill(unlisted.game, hud, 800, 600, 1, 100, 100, 0);
            e.loseItem(sel, 0.1);
            boolean notListed = e.loseActive;
            AppFixture fuel = with(FUEL);
            e.fill(fuel.game, hud, 800, 600, 1, 100, 100, 0);
            e.loseItem(sel, 0.1);
            boolean fuelOff = e.loseActive;
            assertAll(
                () -> assertEquals(MINE, item, "loseItem is SELF_ITEMS' loseItem"),
                () -> assertFalse(off, "selection off: no outline"),
                () -> assertTrue(on, "selection on and the mines listed: outline"),
                () -> assertFalse(notListed, "an item with no count shown is not outlined"),
                () -> assertFalse(fuelOff, "fuel is never listed, so never outlined"));
        }

        @Test
        @DisplayName("the fade ages only on frames that outline the item; an inactive HUD outlines nothing")
        void ageing() {
            LoseItemSelection sel = new LoseItemSelection();
            sel.release();
            e.fill(with(ECM).game, hud, 800, 600, 1, 100, 100, 0);
            e.loseItem(sel, 5);
            int unlisted = sel.state();
            hud.showHud = false;
            e.fill(with(MINE).game, hud, 800, 600, 1, 100, 100, 0);
            e.loseItem(sel, 5);
            boolean hidden = e.loseActive;
            int hiddenState = sel.state();
            hud.showHud = true;
            e.fill(with(MINE).game, hud, 800, 600, 1, 100, 100, 0);
            e.loseItem(sel, 5);
            boolean last = e.loseActive;
            e.loseItem(sel, 0);
            boolean after = e.loseActive;
            assertAll(
                () -> assertEquals(-1, unlisted, "an unlisted item does not age the fade"),
                () -> assertFalse(hidden, "HUD off: no outline"),
                () -> assertEquals(-1, hiddenState, "and no ageing"),
                () -> assertTrue(last, "listed: the fade's last frame outlines"),
                () -> assertFalse(after, "then the fade is over"));
        }
    }
}
