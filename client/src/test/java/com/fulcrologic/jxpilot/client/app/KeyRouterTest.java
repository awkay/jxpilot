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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class KeyRouterTest {
    final InputState input = new InputState(KeyRouter.bindings("F13"));
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
    @DisplayName("implemented() matches the handlers")
    class Implemented {
        /** True when a key bound only to {@code a} does something: an event, a macro, the talk line or its editing. */
        boolean reacts(ClientAction a) {
            com.fulcrologic.jxpilot.client.game.input.KeyBindings b = new com.fulcrologic.jxpilot.client.game.input.KeyBindings();
            b.bind(a, "F13");
            InputState in = new InputState(b);
            TalkLine t = new TalkLine();
            KeyRouter kr = new KeyRouter(in, t);
            if (a.talkCursor()) {
                t.open();
                for (char c : "old".toCharArray()) {
                    t.type(c);
                }
                t.submit(new StringBuilder());
                t.open();
                in.setTalking(true);
                t.type('a');
                t.type('b');
                if (a == ClientAction.TALK_CURSOR_RIGHT) {
                    t.left();
                }
                if (a == ClientAction.TALK_CURSOR_DOWN) {
                    t.historyUp();
                }
            }
            String text = t.text().toString();
            int cursor = t.cursor();
            boolean open = t.isOpen();
            int ev = kr.key(GLFW_KEY_F13, GLFW_PRESS, 0);
            return ev != KeyRouter.NONE || kr.takeMacros() != 0 || t.isOpen() != open || t.cursor() != cursor
                || !t.text().toString().equals(text);
        }

        @Test
        @DisplayName("every client action with a handler is marked implemented, and only those (no drift)")
        void noDrift() {
            List<String> wrong = new java.util.ArrayList<>();
            for (ClientAction a : ClientAction.values()) {
                if (reacts(a) != a.implemented()) {
                    wrong.add(a + (a.implemented() ? " is marked implemented but does nothing" : " works but is not marked"));
                }
            }
            assertAll(
                () -> assertEquals(List.of(), wrong, "ClientAction.implemented() agrees with KeyRouter"),
                () -> assertFalse(reacts(ClientAction.TOGGLE_RECORD), "recording has no handler (not in JXPilot)"),
                () -> assertTrue(reacts(ClientAction.TOGGLE_OWNED_ITEMS) && reacts(ClientAction.MSG_20)
                    && reacts(ClientAction.TALK_CURSOR_DOWN), "the probe sees handlers"));
        }
    }

    @Nested
    @DisplayName("play keys and pointer buttons (C Key_press)")
    class PlayKeys {
        static long act(ClientAction a) {
            return 1L << a.ordinal();
        }

        @Test
        @DisplayName("u, KP_8, KP_9, KP_Enter and Print are their events; F2 and F12 talk macros 1 and 11")
        void events() {
            int id = press(GLFW_KEY_U);
            int items = press(GLFW_KEY_KP_8);
            int msgs = press(GLFW_KEY_KP_9);
            int pointer = press(GLFW_KEY_KP_ENTER);
            int print = press(GLFW_KEY_PRINT_SCREEN);
            int none = r.takeMacros();
            int f2 = press(GLFW_KEY_F2);
            int f12 = press(GLFW_KEY_F12);
            int macros = r.takeMacros();
            assertAll(
                () -> assertEquals(KeyRouter.ID_MODE, id, "keyIdMode u"),
                () -> assertEquals(KeyRouter.TOGGLE_ITEMS, items, "keyToggleOwnedItems KP_8"),
                () -> assertEquals(KeyRouter.TOGGLE_MESSAGES, msgs, "keyToggleMessages KP_9"),
                () -> assertEquals(KeyRouter.POINTER_CONTROL, pointer, "keyPointerControl KP_Enter"),
                () -> assertEquals(KeyRouter.PRINT_MESSAGES, print, "keyPrintMessagesStdout Print"),
                () -> assertEquals(0, none, "no macros yet"),
                () -> assertEquals(KeyRouter.TALK_MACRO, f2, "F2: keySendMsg2"),
                () -> assertEquals(KeyRouter.TALK_MACRO, f12, "F12: keySendMsg12"),
                () -> assertEquals(1 << 1 | 1 << 11, macros, "both macros, once"),
                () -> assertEquals(0, r.takeMacros(), "taken"));
        }

        @Test
        @DisplayName("a pointer button presses and releases its game functions and runs its actions")
        void pointerButton() {
            long base = input.keys();
            int fire = r.pointerButton(true, ControlKey.FIRE_SHOT.mask() | ControlKey.THRUST.mask(), 0);
            long pressed = input.keys();
            r.pointerButton(false, ControlKey.FIRE_SHOT.mask() | ControlKey.THRUST.mask(), 0);
            long released = input.keys();
            int talkEv = r.pointerButton(true, 0, act(ClientAction.TALK));
            boolean opened = talk.isOpen();
            int whileTalking = r.pointerButton(true, ControlKey.THRUST.mask(), act(ClientAction.ID_MODE));
            long talkingKeys = input.keys();
            assertAll(
                () -> assertEquals(KeyRouter.NONE, fire, "game functions are no app event"),
                () -> assertEquals((base & ~ControlKey.SHIELD.mask()) | ControlKey.FIRE_SHOT.mask()
                    | ControlKey.THRUST.mask(), pressed, "fire and thrust set (autoShield drops the shield)"),
                () -> assertEquals(base, released, "released, shield back"),
                () -> assertEquals(KeyRouter.NONE, talkEv, "keyTalk opens the talk line"),
                () -> assertTrue(opened, "talking"),
                () -> assertEquals(KeyRouter.NONE, whileTalking, "buttons do nothing while talking"),
                () -> assertEquals(0, talkingKeys & ControlKey.THRUST.mask(), "no thrust"));
        }

        @Test
        @DisplayName("pointer buttons drive the power keys and the lose-item outline like keys")
        void pointerButtonControls() {
            double before = r.settings().power;
            int ev = r.pointerButton(true, ControlKey.INCREASE_POWER.mask() | ControlKey.LOSE_ITEM.mask(),
                act(ClientAction.MSG_3));
            boolean outline = r.loseSelection().paint(0);
            r.pointerButton(false, ControlKey.INCREASE_POWER.mask() | ControlKey.LOSE_ITEM.mask(), 0);
            assertAll(
                () -> assertEquals(KeyRouter.SETTINGS | KeyRouter.SHOW_CONTROLS | KeyRouter.TALK_MACRO, ev,
                    "power up and macro 3"),
                () -> assertTrue(r.settings().power > before, "power raised"),
                () -> assertTrue(outline, "lose item outline active"),
                () -> assertEquals(1 << 2, r.takeMacros(), "macro 3"),
                () -> assertEquals(0, input.keys() & ControlKey.INCREASE_POWER.mask(), "the power key is not sent"));
        }
    }

    @Nested
    @DisplayName("bindings")
    class Bindings {
        @Test
        @DisplayName("XPilot defaults (Escape swaps settings, as in C), keyQuit unbound, the swap key bound only when given")
        void bindings() {
            KeyBindings b = KeyRouter.bindings("F13");
            KeyBindings none = KeyRouter.bindings(null);
            assertAll(
                () -> assertEquals(ControlKey.SWAP_SETTINGS.mask(), b.gameMask(GLFW_KEY_ESCAPE),
                    "Escape is C's keySwapSettings"),
                () -> assertEquals(java.util.List.of(), b.keysyms(com.fulcrologic.jxpilot.client.game.input.KeyFunctions.of(
                    ClientAction.QUIT)), "keyQuit is unbound"),
                () -> assertEquals(ControlKey.THRUST.mask(), b.gameMask(GLFW_KEY_RIGHT_SHIFT), "defaults kept"),
                () -> assertTrue(KeyBindings.has(b.actionMask(GLFW_KEY_F13), ClientAction.SWAP_SCALEFACTOR),
                    "swap key bound"),
                () -> assertFalse(KeyBindings.has(none.actionMask(GLFW_KEY_F13), ClientAction.SWAP_SCALEFACTOR),
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
        @DisplayName("Escape swaps the settings; the swap key swaps scale; characters are ignored")
        void escapeAndSwap() {
            double power = r.settings().power;
            int esc = press(GLFW_KEY_ESCAPE);
            assertAll(
                () -> assertEquals(KeyRouter.SHOW_CONTROLS, esc, "Escape: settings swapped, meters shown (C)"),
                () -> assertEquals(power, r.settings().altPower, "the power went to the alternate"),
                () -> assertNotEquals(0L, input.keys() & ControlKey.SWAP_SETTINGS.mask(),
                    "and the swap key goes to the server"),
                () -> assertEquals(KeyRouter.SWAP_SCALE, press(GLFW_KEY_F13), "swap key: swap"),
                () -> assertEquals(KeyRouter.TOGGLE_SOUND, press(GLFW_KEY_MINUS), "minus: sound toggle (ClientAction.TOGGLE_SOUND, a key C leaves unbound)"),
                () -> assertEquals(KeyRouter.NET_STATS, press(GLFW_KEY_INSERT), "Insert: net diagnostics line (ClientAction.NET_STATS, unbound in C)"),
                () -> assertEquals(KeyRouter.PREDICTION, press(GLFW_KEY_KP_3), "KP_3: own-ship prediction switch (ClientAction.PREDICTION, unbound in C)"),
                () -> assertEquals(KeyRouter.TOGGLE_ITEMS, press(GLFW_KEY_KP_8),
                    "KP_8: toggle the owned items list (C keyToggleOwnedItems)"),
                () -> assertEquals(KeyRouter.TOGGLE_MESSAGES, press(GLFW_KEY_KP_9),
                    "KP_9: toggle the game messages (C keyToggleMessages)"),
                () -> {
                    type("x");
                    assertFalse(talk.isOpen(), "characters do not open talk");
                    assertEquals(0, talk.text().length(), "nothing typed while not talking");
                });
        }

        @Test
        @DisplayName("keyQuit quits; Command-Q quits iff Command-Q is the platform's quit (and is not sent)")
        void quit() {
            InputState in = new InputState(KeyRouter.bindings(null));
            in.bindings().bind(ClientAction.QUIT, "F13"); // F2-F12 are talk macro keys
            KeyRouter mac = new KeyRouter(in, new TalkLine(), new com.fulcrologic.jxpilot.client.game.input.ControlSettings(),
                true);
            KeyRouter other = new KeyRouter(new InputState(KeyRouter.bindings(null)), new TalkLine(),
                new com.fulcrologic.jxpilot.client.game.input.ControlSettings(), false);
            int f11 = mac.key(GLFW_KEY_F13, GLFW_PRESS, 0);
            int cmdQ = mac.key(GLFW_KEY_Q, GLFW_PRESS, GLFW_MOD_SUPER);
            long sentOnMac = in.keys();
            int plainQ = mac.key(GLFW_KEY_Q, GLFW_PRESS, 0);
            int otherCmdQ = other.key(GLFW_KEY_Q, GLFW_PRESS, GLFW_MOD_SUPER);
            assertAll(
                () -> assertEquals(KeyRouter.QUIT, f11, "keyQuit: quit"),
                () -> assertEquals(KeyRouter.QUIT, cmdQ, "Command-Q on macOS: quit"),
                () -> assertEquals(0L, sentOnMac & ControlKey.HYPERJUMP.mask(), "Command-Q does not hyperjump"),
                () -> assertEquals(KeyRouter.NONE, plainQ, "plain q is the hyperjump key"),
                () -> assertEquals(KeyRouter.NONE, otherCmdQ, "Command-Q elsewhere is just q"));
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
            withSwap.input().bindings().bind(ControlKey.SWAP_SETTINGS, "F13");
            int swap = withSwap.key(GLFW_KEY_F13, GLFW_PRESS, 0);
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
        @DisplayName("refuel/repair/tank keys show the fuel (C Key_press_fuel, Key_release); the keys are still sent")
        void fuelNotify() {
            int tankNext = press(GLFW_KEY_E);
            int tankPrev = press(GLFW_KEY_W);
            int refuel = press(GLFW_KEY_F);
            long sent = input.keys();
            int refuelUp = r.key(GLFW_KEY_F, GLFW_RELEASE, 0);
            int tankUp = r.key(GLFW_KEY_E, GLFW_RELEASE, 0);
            int plainUp = r.key(GLFW_KEY_A, GLFW_RELEASE, 0);
            assertAll(
                () -> assertEquals(KeyRouter.FUEL_NOTIFY, tankNext, "tank next press"),
                () -> assertEquals(KeyRouter.FUEL_NOTIFY, tankPrev, "tank prev press"),
                () -> assertEquals(KeyRouter.FUEL_NOTIFY, refuel, "refuel/repair press"),
                () -> assertEquals(ControlKey.REFUEL.mask() | ControlKey.REPAIR.mask(),
                    sent & (ControlKey.REFUEL.mask() | ControlKey.REPAIR.mask()), "refuel and repair are sent"),
                () -> assertEquals(KeyRouter.FUEL_NOTIFY, refuelUp, "refuel/repair release"),
                () -> assertEquals(KeyRouter.NONE, tankUp, "a tank key release is not"),
                () -> assertEquals(KeyRouter.NONE, plainUp, "another release is not"));
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

    @Nested
    @DisplayName("lose-item selection keys (C Key_press_select_lose_item / Key_release)")
    class LoseItemKeys {
        @Test
        @DisplayName("SELECT_ITEM / LOSE_ITEM presses and releases drive the selection; the keys still go out")
        void pressRelease() {
            int fresh = r.loseSelection().state();
            press(GLFW_KEY_KP_0);
            int select = r.loseSelection().state();
            long sent = input.keys() & ControlKey.SELECT_ITEM.mask();
            press(GLFW_KEY_KP_DECIMAL);
            int both = r.loseSelection().state();
            r.key(GLFW_KEY_KP_DECIMAL, GLFW_RELEASE, 0);
            int oneLeft = r.loseSelection().state();
            r.key(GLFW_KEY_KP_0, GLFW_REPEAT, 0);
            int repeated = r.loseSelection().state();
            r.key(GLFW_KEY_KP_0, GLFW_RELEASE, 0);
            int released = r.loseSelection().state();
            press(GLFW_KEY_RIGHT_SHIFT);
            r.key(GLFW_KEY_RIGHT_SHIFT, GLFW_RELEASE, 0);
            int otherKey = r.loseSelection().state();
            assertAll(
                () -> assertEquals(0, fresh, "starts off"),
                () -> assertEquals(1, select, "SELECT_ITEM press: 1"),
                () -> assertEquals(ControlKey.SELECT_ITEM.mask(), sent, "the key is still sent to the server"),
                () -> assertEquals(2, both, "LOSE_ITEM pressed while selecting: 2"),
                () -> assertEquals(1, oneLeft, "LOSE_ITEM released: back to 1"),
                () -> assertEquals(1, repeated, "a repeat changes nothing"),
                () -> assertEquals(-1, released, "SELECT_ITEM released: fading"),
                () -> assertEquals(-1, otherKey, "other keys do not touch the selection"));
        }

        @Test
        @DisplayName("a key bound to both functions counts twice; keys typed into the talk line count not at all")
        void doubleBindingAndTalk() {
            input.bindings().bind(ControlKey.SELECT_ITEM, "F9");
            input.bindings().bind(ControlKey.LOSE_ITEM, "F9");
            press(GLFW_KEY_F9);
            int pressed = r.loseSelection().state();
            r.key(GLFW_KEY_F9, GLFW_RELEASE, 0);
            int released = r.loseSelection().state();
            KeyRouter t = new KeyRouter(new InputState(KeyRouter.bindings(null)), new TalkLine());
            t.key(GLFW_KEY_M, GLFW_PRESS, 0);
            t.key(GLFW_KEY_KP_0, GLFW_PRESS, 0);
            t.key(GLFW_KEY_KP_0, GLFW_RELEASE, 0);
            int talking = t.loseSelection().state();
            assertAll(
                () -> assertEquals(2, pressed, "one press of both functions: 0 -> 1 -> 2"),
                () -> assertEquals(-1, released, "one release of both: 2 -> 1 -> fading"),
                () -> assertEquals(0, talking, "while talking the keys go to the talk line"));
        }
    }
}
