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
package com.fulcrologic.jxpilot.client.game.hud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C {@code lose_item_active}: xevent.c:481-497, 640-648 and painthud.c:558-566. */
@DisplayName("LoseItemSelection (C lose_item_active)")
class LoseItemSelectionTest {

    @Nested
    @DisplayName("press and release")
    class Keys {
        @Test
        @DisplayName("press: 0 -> 1, 1 -> 2, 2 -> 1, fading -> 1; release: 2 -> 1, else fade")
        void transitions() {
            LoseItemSelection s = new LoseItemSelection();
            int fresh = s.state();
            s.press();
            int one = s.state();
            s.press();
            int two = s.state();
            s.press();
            int backToOne = s.state();
            s.press();
            s.release();
            int releasedFromTwo = s.state();
            s.release();
            int releasedFromOne = s.state();
            s.press();
            int pressedWhileFading = s.state();
            s.release();
            s.release();
            int releasedWhileFading = s.state();
            LoseItemSelection idle = new LoseItemSelection();
            idle.release();
            int releasedFromOff = idle.state();
            assertAll(
                () -> assertEquals(0, fresh, "starts off"),
                () -> assertEquals(1, one, "a press from off is 1"),
                () -> assertEquals(2, two, "a second press while 1 is 2 (the other lose key)"),
                () -> assertEquals(1, backToOne, "a press while 2 is 1 again (C: anything but 1 becomes 1)"),
                () -> assertEquals(1, releasedFromTwo, "releasing from 2 goes back to 1 (one key still held)"),
                () -> assertEquals(-1, releasedFromOne, "releasing from 1 starts the fade"),
                () -> assertEquals(1, pressedWhileFading, "a press while fading is 1"),
                () -> assertEquals(-1, releasedWhileFading, "a release while fading restarts the fade"),
                () -> assertEquals(-1, releasedFromOff, "a release from off fades too (C sets -FPS)"));
        }
    }

    @Nested
    @DisplayName("paint")
    class Paint {
        @Test
        @DisplayName("off draws nothing; held (1 or 2) draws without ageing")
        void heldAndOff() {
            LoseItemSelection s = new LoseItemSelection();
            boolean off = s.paint(5);
            s.press();
            boolean held = s.paint(5);
            boolean stillHeld = s.paint(5);
            s.press();
            boolean two = s.paint(5);
            int after = s.state();
            assertAll(
                () -> assertFalse(off, "off: no outline"),
                () -> assertTrue(held, "1: outline"),
                () -> assertTrue(stillHeld, "1 does not age away"),
                () -> assertTrue(two, "2: outline"),
                () -> assertEquals(2, after, "painting does not change a held state"));
        }

        @Test
        @DisplayName("the fade draws for one second of painted frames, including the frame that ends it")
        void fade() {
            LoseItemSelection s = new LoseItemSelection();
            s.press();
            s.release();
            boolean a = s.paint(0.5);
            boolean b = s.paint(0.25);
            int beforeLast = s.state();
            boolean c = s.paint(0.25);
            int afterLast = s.state();
            boolean d = s.paint(0.25);
            LoseItemSelection jump = new LoseItemSelection();
            jump.release();
            boolean big = jump.paint(3);
            boolean afterBig = jump.paint(0);
            LoseItemSelection frozen = new LoseItemSelection();
            frozen.release();
            boolean zero = frozen.paint(0);
            int frozenState = frozen.state();
            assertAll(
                () -> assertTrue(a, "0.5 s in: drawn"),
                () -> assertTrue(b, "0.75 s in: drawn"),
                () -> assertEquals(-1, beforeLast, "still fading at 0.75 s"),
                () -> assertTrue(c, "the frame reaching 1 s still draws (C: -1 counts up to 0 and draws)"),
                () -> assertEquals(0, afterLast, "then the selection is off"),
                () -> assertFalse(d, "later frames draw nothing"),
                () -> assertTrue(big, "a long frame past the whole fade still draws once"),
                () -> assertFalse(afterBig, "and ends it"),
                () -> assertTrue(zero, "a frame of no time draws"),
                () -> assertEquals(-1, frozenState, "and does not age the fade"),
                () -> assertEquals(1.0, LoseItemSelection.FADE_SECONDS, "C's -FPS frames: one second"));
        }
    }
}
