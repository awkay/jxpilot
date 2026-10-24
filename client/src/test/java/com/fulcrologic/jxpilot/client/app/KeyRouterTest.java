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

import com.fulcrologic.jxpilot.client.game.hud.TalkLine;
import com.fulcrologic.jxpilot.client.game.input.ClientAction;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.game.input.KeyBindings;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class KeyRouterTest {
    final InputState input = new InputState(KeyRouter.bindings("F12"));
    final TalkLine talk = new TalkLine();
    final KeyRouter r = new KeyRouter(input, talk);

    int press(int key) {
        return r.key(key, GLFW_PRESS, 0);
    }

    void type(String s) {
        for (int i = 0; i < s.length(); i++) {
            r.character(s.charAt(i));
        }
    }

    /** Open the talk line with 'm' (its character event is swallowed). */
    void open() {
        press(GLFW_KEY_M);
        r.character('m');
    }

    @Nested
    @DisplayName("bindings")
    class Bindings {
        @Test
        @DisplayName("XPilot defaults, Escape freed from swap-settings, the swap key bound only when given")
        void bindings() {
            KeyBindings b = KeyRouter.bindings("F12");
            KeyBindings none = KeyRouter.bindings(null);
            assertAll(
                () -> assertEquals(0L, b.gameMask(GLFW_KEY_ESCAPE), "Escape sends nothing"),
                () -> assertEquals(ControlKey.THRUST.mask(), b.gameMask(GLFW_KEY_RIGHT_SHIFT), "defaults kept"),
                () -> assertTrue(KeyBindings.has(b.actionMask(GLFW_KEY_F12), ClientAction.SWAP_SCALEFACTOR),
                    "swap key bound"),
                () -> assertFalse(KeyBindings.has(none.actionMask(GLFW_KEY_F12), ClientAction.SWAP_SCALEFACTOR),
                    "unbound without a key, as C"));
        }
    }

    @Nested
    @DisplayName("playing")
    class Playing {
        @Test
        @DisplayName("presses and releases drive the key mask; repeats are ignored")
        void gameKeys() {
            long base = input.keys();
            int ev = press(GLFW_KEY_RIGHT_SHIFT);
            long thrust = input.keys();
            r.key(GLFW_KEY_RIGHT_SHIFT, GLFW_RELEASE, 0);
            long released = input.keys();
            r.key(GLFW_KEY_A, GLFW_REPEAT, 0);
            assertAll(
                () -> assertEquals(KeyRouter.NONE, ev, "a game key is no app event"),
                () -> assertEquals(base | ControlKey.THRUST.mask(), thrust, "press sets THRUST"),
                () -> assertEquals(base, released, "release clears it"),
                () -> assertEquals(base, input.keys(), "a repeat sets nothing"));
        }

        @Test
        @DisplayName("Escape quits; the swap key swaps scale; characters are ignored")
        void escapeAndSwap() {
            assertAll(
                () -> assertEquals(KeyRouter.QUIT, press(GLFW_KEY_ESCAPE), "Escape: quit"),
                () -> assertEquals(KeyRouter.SWAP_SCALE, press(GLFW_KEY_F12), "swap key: swap"),
                () -> assertEquals(KeyRouter.TOGGLE_SOUND, press(GLFW_KEY_MINUS), "minus: sound toggle (ClientAction.TOGGLE_SOUND, a key C leaves unbound)"),
                () -> {
                    type("x");
                    assertFalse(talk.isOpen(), "characters do not open talk");
                    assertEquals(0, talk.text().length(), "nothing typed while not talking");
                });
        }

        @Test
        @DisplayName("the talk key opens the talk line, releases game keys and does not type itself")
        void openTalk() {
            press(GLFW_KEY_RIGHT_SHIFT);
            open();
            assertAll(
                () -> assertTrue(talk.isOpen(), "talk open"),
                () -> assertTrue(input.talking(), "input in talk mode"),
                () -> assertEquals(0L, input.keys() & ControlKey.THRUST.mask(), "game keys released"),
                () -> assertEquals("", talk.text().toString(), "the 'm' of the talk key is not typed"));
        }

        @Test
        @DisplayName("focus loss releases game keys")
        void focus() {
            press(GLFW_KEY_RIGHT_SHIFT);
            r.focusLost();
            assertEquals(0L, input.keys() & ControlKey.THRUST.mask(), "thrust released");
        }
    }

    @Nested
    @DisplayName("talking")
    class Talking {
        @Test
        @DisplayName("typing then Return sends the line, closes talk and gives the keys back")
        void send() {
            open();
            type("hi there");
            int ev = press(GLFW_KEY_ENTER);
            assertAll(
                () -> assertEquals(KeyRouter.CHAT, ev, "chat event"),
                () -> assertEquals("hi there", r.chat().toString(), "the line"),
                () -> assertFalse(talk.isOpen(), "closed"),
                () -> assertFalse(input.talking(), "game keys back"));
        }

        @Test
        @DisplayName("keypad Enter sends too; an empty line sends nothing but closes")
        void kpEnterAndEmpty() {
            open();
            type("x");
            int sent = press(GLFW_KEY_KP_ENTER);
            open();
            int empty = press(GLFW_KEY_ENTER);
            assertAll(
                () -> assertEquals(KeyRouter.CHAT, sent, "keypad Enter sends"),
                () -> assertEquals(KeyRouter.NONE, empty, "empty: no chat"),
                () -> assertFalse(talk.isOpen(), "closed either way"));
        }

        @Test
        @DisplayName("Escape cancels: text cleared, talk closed, no quit")
        void escape() {
            open();
            type("abc");
            int ev = press(GLFW_KEY_ESCAPE);
            assertAll(
                () -> assertEquals(KeyRouter.NONE, ev, "no quit while talking"),
                () -> assertFalse(talk.isOpen(), "closed"),
                () -> assertEquals("", talk.text().toString(), "cleared"),
                () -> assertFalse(input.talking(), "game keys back"));
        }

        @Test
        @DisplayName("editing keys: Backspace, Delete, Home, End, Left, Right and Ctrl-letters; releases ignored")
        void editing() {
            open();
            type("abcd");
            press(GLFW_KEY_BACKSPACE);
            String afterBs = talk.text().toString();
            press(GLFW_KEY_HOME);
            int home = talk.cursor();
            press(GLFW_KEY_RIGHT);
            int right = talk.cursor();
            press(GLFW_KEY_END);
            press(GLFW_KEY_LEFT);
            int left = talk.cursor();
            press(GLFW_KEY_DELETE);
            String afterDel = talk.text().toString();
            r.key(GLFW_KEY_U, GLFW_PRESS, GLFW_MOD_CONTROL);
            String afterCtrlU = talk.text().toString();
            r.key(GLFW_KEY_ENTER, GLFW_RELEASE, 0);
            type("q");
            r.key(GLFW_KEY_A, GLFW_REPEAT, GLFW_MOD_CONTROL);
            assertAll(
                () -> assertEquals("abc", afterBs, "Backspace erases the last char"),
                () -> assertEquals(0, home, "Home: start"),
                () -> assertEquals(1, right, "Right: one on"),
                () -> assertEquals(2, left, "End then Left: before the last char"),
                () -> assertEquals("ac", afterDel, "Delete erases before the cursor, as C"),
                () -> assertEquals("", afterCtrlU, "Ctrl-U erases the line"),
                () -> assertTrue(talk.isOpen(), "a release does nothing (Return release did not send)"),
                () -> assertEquals(0, talk.cursor(), "a Ctrl-A repeat edits too: cursor to the start"));
        }

        @Test
        @DisplayName("Up and Down browse the history")
        void history() {
            open();
            type("one");
            press(GLFW_KEY_ENTER);
            open();
            type("two");
            press(GLFW_KEY_ENTER);
            open();
            press(GLFW_KEY_UP);
            String up = talk.text().toString();
            press(GLFW_KEY_UP);
            String up2 = talk.text().toString();
            press(GLFW_KEY_DOWN);
            assertAll(
                () -> assertEquals("two", up, "Up: newest"),
                () -> assertEquals("one", up2, "Up again: older"),
                () -> assertEquals("two", talk.text().toString(), "Down: newer"));
        }

        @Test
        @DisplayName("game keys do nothing while talking; Ctrl with a non-letter is not an edit")
        void noGameKeys() {
            open();
            long before = input.keys();
            press(GLFW_KEY_RIGHT_SHIFT);
            r.key(GLFW_KEY_1, GLFW_PRESS, GLFW_MOD_CONTROL);
            assertAll(
                () -> assertEquals(before, input.keys(), "no thrust while talking"),
                () -> assertEquals("", talk.text().toString(), "nothing typed by key events"));
        }

        @Test
        @DisplayName("only the opening key's character is swallowed: a key event in between ends it")
        void swallowOnce() {
            press(GLFW_KEY_M);
            r.key(GLFW_KEY_LEFT, GLFW_PRESS, 0);
            type("m");
            assertEquals("m", talk.text().toString(), "after another key event the next char is typed");
        }
    }

    @Nested
    @DisplayName("control settings keys (C xevent.c)")
    class Controls {
        @Test
        @DisplayName("power and turn speed keys change the settings client-side and are never sent; swap is sent")
        void keys() {
            com.fulcrologic.jxpilot.client.game.input.ControlSettings st = r.settings();
            int up = press(GLFW_KEY_KP_ADD);
            double t1 = st.turnSpeed;
            int down = press(GLFW_KEY_KP_SUBTRACT);
            double t2 = st.turnSpeed;
            int pUp = press(GLFW_KEY_KP_MULTIPLY);
            double p1 = st.power;
            int pDown = press(GLFW_KEY_KP_DIVIDE);
            double p2 = st.power;
            long sent = input.keys();
            KeyRouter withSwap = new KeyRouter(new InputState(KeyRouter.bindings(null)), new TalkLine());
            withSwap.input().bindings().bind(ControlKey.SWAP_SETTINGS, "F8");
            int swap = withSwap.key(GLFW_KEY_F8, GLFW_PRESS, 0);
            int plain = press(GLFW_KEY_A);
            assertAll(
                () -> assertEquals(KeyRouter.SETTINGS | KeyRouter.SHOW_CONTROLS, up, "turn speed up: send and show"),
                () -> assertEquals(40 * 1.05, t1, 1e-12, "turn speed * 1.05"),
                () -> assertEquals(KeyRouter.SETTINGS | KeyRouter.SHOW_CONTROLS, down, "turn speed down"),
                () -> assertEquals(40 * 1.05 * 0.95, t2, 1e-12, "turn speed * 0.95"),
                () -> assertEquals(KeyRouter.SETTINGS | KeyRouter.SHOW_CONTROLS, pUp, "power up"),
                () -> assertEquals(45 * 1.10, p1, 1e-12, "power * 1.10"),
                () -> assertEquals(KeyRouter.SETTINGS | KeyRouter.SHOW_CONTROLS, pDown, "power down"),
                () -> assertEquals(45 * 1.10 * 0.90, p2, 1e-12, "power * 0.90"),
                () -> assertEquals(0L, sent & InputState.CLIENT_SIDE_KEYS, "none of them is in the sent keys"),
                () -> assertEquals(KeyRouter.SHOW_CONTROLS, swap, "swap only shows the meters"),
                () -> assertEquals(25.0, withSwap.settings().turnSpeed, "swap exchanged the settings"),
                () -> assertEquals(ControlKey.SWAP_SETTINGS.mask(), withSwap.input().keys() & ControlKey.SWAP_SETTINGS.mask(),
                    "and the swap key is sent"),
                () -> assertEquals(KeyRouter.NONE, plain, "a plain game key is no event"));
        }

        @Test
        @DisplayName("F1 is HELP; while talking the control keys do nothing")
        void helpAndTalking() {
            int help = press(GLFW_KEY_F1);
            open();
            double before = r.settings().turnSpeed;
            int talking = press(GLFW_KEY_KP_ADD);
            assertAll(
                () -> assertEquals(KeyRouter.HELP, help, "F1 toggles the help"),
                () -> assertEquals(KeyRouter.NONE, talking, "no event while talking"),
                () -> assertEquals(before, r.settings().turnSpeed, "settings unchanged while talking"));
        }
    }
}
