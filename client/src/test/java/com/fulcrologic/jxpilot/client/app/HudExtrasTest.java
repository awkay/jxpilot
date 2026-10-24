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

    /** One HUD frame's item count: {@link HudExtras#step} then {@link HudExtras#itemCount}. */
    static int counted(HudExtras e, int item, int num, double frames, double fps) {
        e.step(frames, fps);
        return e.itemCount(item, num);
    }

    static int[] items(int item, int count) {
        int[] c = new int[Item.COUNT];
        c[item] = count;
        return c;
    }

    @Nested
    @DisplayName("fill")
    class Fill {
        @Test
        @DisplayName("lock on a foe in view without a SELF distance: name, lives, shape, not a friend, no distance "
            + "(C lock_dist 0); items; autopilot")
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
                () -> assertEquals(0, e.lockDist, "SELF lock distance 0: none, though the foe is drawn 300 px away"),
                () -> assertEquals(0, e.lockDir, "SELF lock direction"),
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
        @DisplayName("the lock distance and direction are SELF's, also 0; modifiers as C's string")
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
                () -> assertEquals(0, e2.lockDist, "SELF distance 0 stays 0 with the target drawn 300 px away (C)"),
                () -> assertEquals(0, e2.lockDir, "and the direction"),
                () -> assertEquals("", e2.mods.toString(), "no modifiers: empty"));
        }

        @Test
        @DisplayName("lock on a team mate across the wrap seam: SELF's distance and direction; odd loops; no autopilot")
        void lockMateWrapped() {
            AppFixture f = new AppFixture(true).players().twice(x -> x
                .selfControls(45, 40, MATE, 100, 64, 0)
                .ship(MATE, 0, 650, 175, 0));
            e.fill(f.game, hud, 800, 600, 1, 350, 175, 103);
            assertAll(
                () -> assertEquals(100, e.lockDist, "SELF distance as sent"),
                () -> assertEquals(64, e.lockDir, "SELF direction as sent"),
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

    @Nested
    @DisplayName("items (C Paint_HUD showItems / showItemsTime, painthud.c:527-546)")
    class Items {
        static final int MINE = Item.MINE.ordinal();
        static final int ECM = Item.ECM.ordinal();
        static final int FUEL = Item.FUEL.ordinal();

        @Test
        @DisplayName("showItems on: counts above 0 are listed, others and fuel are not")
        void permanent() {
            assertAll(
                () -> assertEquals(3, counted(e, MINE, 3, 1, 50), "count above 0: listed"),
                () -> assertEquals(-1, counted(e, MINE, 0, 1, 50), "count 0: not listed"),
                () -> assertEquals(-1, counted(e, FUEL, 500, 1, 50), "fuel is never listed"));
        }

        @Test
        @DisplayName("showItems off: a changed count (0 too) shows for (int)(showItemsTime * fps) frames")
        void timed() {
            e.showItems = false;
            e.showItemsTime = 0.1; // 5 frames at 50 fps
            int[] shown = new int[7];
            for (int k = 0; k < shown.length; k++) {
                shown[k] = counted(e, MINE, 2, 1, 50);
            }
            int unchangedOther = counted(e, ECM, 0, 1, 50);
            int dropped = counted(e, MINE, 0, 1, 50);
            int halfFrames = counted(e, MINE, 0, 2.5, 50);
            int afterHalf = counted(e, MINE, 0, 2.5, 50);
            int expired = counted(e, MINE, 0, 2.5, 50);
            assertAll(
                () -> assertArrayEquals(new int[] {2, 2, 2, 2, 2, -1, -1}, shown,
                    "a new count shows on 5 frames, then disappears"),
                () -> assertEquals(-1, unchangedOther, "a count that never changed (0) is not shown"),
                () -> assertEquals(0, dropped, "a count that changed to 0 shows as 0"),
                () -> assertEquals(0, halfFrames, "the timer runs down by the frames elapsed (4 left)"),
                () -> assertEquals(0, afterHalf, "1.5 left"),
                () -> assertEquals(-1, expired, "used up: hidden"));
        }

        @Test
        @DisplayName("turning showItems off after a permanent listing shows nothing until a count changes")
        void toggled() {
            counted(e, MINE, 3, 1, 50);
            e.showItems = false;
            int same = counted(e, MINE, 3, 1, 50);
            int changed = counted(e, MINE, 4, 1, 50);
            assertAll(
                () -> assertEquals(-1, same, "the remembered count did not change: hidden"),
                () -> assertEquals(4, changed, "a change shows it"));
        }

        @Test
        @DisplayName("fill uses the timers, aged by the step's frames")
        void viaFill() {
            AppFixture f = new AppFixture(true).players().twice(x -> x.self(0, 100, 100, 0, FOE)
                .items(items(MINE, 3)));
            e.showItems = false;
            e.showItemsTime = 1;
            e.step(1, f.game.gameFps());
            e.fill(f.game, hud, 800, 600, 1, 100, 100, 0);
            int first = e.itemCounts[MINE];
            e.step(1000, f.game.gameFps());
            e.fill(f.game, hud, 800, 600, 1, 100, 100, 0);
            int second = e.itemCounts[MINE];
            e.step(1, f.game.gameFps());
            e.fill(f.game, hud, 800, 600, 1, 100, 100, 0);
            int third = e.itemCounts[MINE];
            assertAll(
                () -> assertEquals(3, first, "the new count shows"),
                () -> assertEquals(3, second, "still above 0 before this frame's ageing"),
                () -> assertEquals(-1, third, "aged out by the 1000 frames"));
        }
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
