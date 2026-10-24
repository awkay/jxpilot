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
package com.fulcrologic.jxpilot.client.game.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** C xevent.c pointer control: state, movement sums and the re-centring rule. */
class PointerControlTest {
    PointerControl on(int w, int h) {
        PointerControl p = new PointerControl(false);
        p.setWindow(w, h);
        p.toggle();
        p.takeWarp();
        return p;
    }

    @Nested
    @DisplayName("state")
    class State {
        @Test
        @DisplayName("the key toggles; turning on centres the pointer; the resource only arms it for the first focus")
        void toggle() {
            PointerControl p = new PointerControl(false);
            p.setWindow(800, 600);
            boolean before = p.on();
            p.toggle();
            boolean warp = p.takeWarp();
            boolean warpTwice = p.takeWarp();
            double cx = p.mouseX();
            double cy = p.mouseY();
            p.toggle();
            PointerControl armed = new PointerControl(true);
            boolean armedOn = armed.on();
            armed.focusGained(true);
            boolean talkingFocus = armed.on();
            armed.focusGained(false);
            assertAll(
                () -> assertFalse(before, "off at start"),
                () -> assertTrue(warp, "turning on warps the pointer"),
                () -> assertFalse(warpTwice, "once"),
                () -> assertEquals(400, cx, "to the centre x"),
                () -> assertEquals(300, cy, "and y"),
                () -> assertFalse(p.on(), "the key turns it off again"),
                () -> assertFalse(p.grabbed(), "and releases the pointer"),
                () -> assertFalse(armedOn, "pointerControl: Yes is not on before focus"),
                () -> assertFalse(talkingFocus, "focus while talking keeps it armed"),
                () -> assertTrue(armed.on(), "focus without talking turns it on"));
        }

        @Test
        @DisplayName("talking and losing focus suspend it; closing the talk line or focus resume it; only if it was on")
        void suspend() {
            PointerControl p = on(800, 600);
            p.talkOpened();
            boolean whileTalking = p.on();
            p.talkClosed();
            boolean afterTalk = p.on();
            p.focusLost();
            boolean unfocused = p.on();
            p.focusGained(false);
            PointerControl off = new PointerControl(false);
            off.talkOpened();
            off.talkClosed();
            off.focusLost();
            off.focusGained(false);
            assertAll(
                () -> assertFalse(whileTalking, "off while talking (C Talk_set_state)"),
                () -> assertTrue(afterTalk, "back after"),
                () -> assertFalse(unfocused, "off without focus (C UnmapNotify_event)"),
                () -> assertTrue(p.on(), "back with focus"),
                () -> assertFalse(off.on(), "never on when it was off"));
        }
    }

    @Nested
    @DisplayName("movement (C MotionNotify_event / xevent_pointer)")
    class Movement {
        @Test
        @DisplayName("horizontal steps sum per frame; the sum restarts each frame")
        void sums() {
            PointerControl p = on(800, 600);
            p.moved(410, 300, false);
            p.moved(405.7, 290, false);
            int first = p.takeMovement(false);
            int empty = p.takeMovement(false);
            p.moved(395, 300, false);
            assertAll(
                () -> assertEquals(5, first, "+10 then -5 (pixels truncated as X's ints)"),
                () -> assertEquals(0, empty, "nothing new"),
                () -> assertEquals(-10, p.takeMovement(false), "relative to the last position"),
                () -> assertFalse(p.takeWarp(), "no warp near the centre"));
        }

        @Test
        @DisplayName("talking: motion is tracked but not summed; nothing is sent; off: nothing at all")
        void talkingAndOff() {
            PointerControl p = on(800, 600);
            p.moved(450, 300, true);
            int talking = p.takeMovement(true);
            p.moved(460, 300, false);
            int after = p.takeMovement(false);
            PointerControl off = new PointerControl(false);
            off.setWindow(800, 600);
            off.moved(500, 300, false);
            assertAll(
                () -> assertEquals(0, talking, "not while talking"),
                () -> assertEquals(10, after, "the talking motion moved the reference point"),
                () -> assertEquals(0, off.takeMovement(false), "off: nothing"),
                () -> assertEquals(0, off.mouseX(), "off: not even tracked"));
        }

        @Test
        @DisplayName("a pointer more than 3/8 width or 1/8 height from the centre is put back, after a move")
        void recentre() {
            PointerControl x = on(800, 600);
            x.moved(400 + 300, 300, false);
            int atLimit = x.takeMovement(false);
            boolean noWarp = x.takeWarp();
            x.moved(400 + 301, 300, false);
            x.takeMovement(false);
            boolean warpX = x.takeWarp();
            double backX = x.mouseX();
            PointerControl y = on(800, 600);
            y.moved(401, 300 - 76, false);
            y.takeMovement(false);
            boolean warpY = y.takeWarp();
            PointerControl still = on(800, 600);
            still.moved(400, 0, false);
            still.takeMovement(false);
            assertAll(
                () -> assertEquals(300, atLimit, "the move is sent"),
                () -> assertFalse(noWarp, "exactly 3/8 of the width away: stays"),
                () -> assertTrue(warpX, "one more pixel: back to the centre"),
                () -> assertEquals(400, backX, "centre x"),
                () -> assertTrue(warpY, "more than 1/8 of the height (75) vertically"),
                () -> assertFalse(still.takeWarp(), "only after a horizontal move (C: movement != 0)"));
        }
    }
}
