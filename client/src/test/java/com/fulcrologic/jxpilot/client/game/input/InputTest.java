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

import com.fulcrologic.jxpilot.common.net.ControlKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class InputTest {
    @Nested
    @DisplayName("KeySyms")
    class Syms {
        @Test
        @DisplayName("maps X11 keysym names to GLFW codes")
        void mapping() {
            assertAll(
                () -> assertEquals(GLFW_KEY_A, KeySyms.glfwKey("a"), "lower-case letter"),
                () -> assertEquals(GLFW_KEY_Z, KeySyms.glfwKey("Z"), "upper-case letter"),
                () -> assertEquals(GLFW_KEY_5, KeySyms.glfwKey("5"), "digit"),
                () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey("#"), "other single char"),
                () -> assertEquals(GLFW_KEY_F1, KeySyms.glfwKey("F1"), "F1"),
                () -> assertEquals(GLFW_KEY_F12, KeySyms.glfwKey("F12"), "F12"),
                () -> assertEquals(GLFW_KEY_F25, KeySyms.glfwKey("F25"), "F25"),
                () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey("F26"), "no F26"),
                () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey("F0"), "no F0"),
                () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey("Fx"), "F + non-digits"),
                () -> assertEquals(GLFW_KEY_KP_7, KeySyms.glfwKey("KP_7"), "keypad digit"),
                () -> assertEquals(GLFW_KEY_RIGHT_SHIFT, KeySyms.glfwKey("Shift_R"), "named key"),
                () -> assertEquals(GLFW_KEY_APOSTROPHE, KeySyms.glfwKey("quoteright"), "quoteright"),
                () -> assertEquals(GLFW_KEY_GRAVE_ACCENT, KeySyms.glfwKey("quoteleft"), "quoteleft"),
                () -> assertEquals(GLFW_KEY_PAGE_DOWN, KeySyms.glfwKey("Next"), "Next"),
                () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey("Linefeed"), "no GLFW Linefeed"),
                () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey("KP_Insert"), "no GLFW KP_Insert"));
        }

        @Test
        @DisplayName("every default keysym of default.c maps, except the four without a GLFW key")
        void defaults() {
            List<String> unmapped = new ArrayList<>();
            for (ControlKey k : ControlKey.values()) {
                for (String s : k.defaultKeys.split(" ")) {
                    if (KeySyms.glfwKey(s) == KeySyms.NONE) {
                        unmapped.add(s);
                    }
                }
            }
            for (ClientAction a : ClientAction.values()) {
                for (String s : a.defaultKeys.trim().split(" ")) {
                    if (!s.isEmpty() && KeySyms.glfwKey(s) == KeySyms.NONE) {
                        unmapped.add(s);
                    }
                }
            }
            assertEquals(List.of("Select", "Linefeed", "KP_Delete", "KP_Insert"), unmapped,
                "only keysyms GLFW lacks are skipped");
        }
    }

    @Nested
    @DisplayName("KeyBindings defaults")
    class Bindings {
        @Test
        @DisplayName("bind the XPilot defaults, sharing keys as default.c does")
        void table() {
            KeyBindings b = KeyBindings.defaults();
            assertAll(
                () -> assertEquals(ControlKey.TURN_LEFT.mask(), b.gameMask(GLFW_KEY_A), "a turns left"),
                () -> assertEquals(ControlKey.TURN_RIGHT.mask(), b.gameMask(GLFW_KEY_S), "s turns right"),
                () -> assertEquals(ControlKey.THRUST.mask(), b.gameMask(GLFW_KEY_LEFT_SHIFT), "left shift thrusts"),
                () -> assertEquals(ControlKey.THRUST.mask(), b.gameMask(GLFW_KEY_RIGHT_SHIFT), "right shift thrusts"),
                () -> assertEquals(ControlKey.FIRE_SHOT.mask(), b.gameMask(GLFW_KEY_ENTER), "Return fires"),
                () -> assertEquals(ControlKey.SHIELD.mask(), b.gameMask(GLFW_KEY_SPACE), "space shields"),
                () -> assertEquals(ControlKey.REFUEL.mask() | ControlKey.REPAIR.mask(), b.gameMask(GLFW_KEY_F),
                    "f refuels and repairs"),
                () -> assertEquals(ControlKey.CHANGE_HOME.mask() | ControlKey.TOGGLE_AUTOPILOT.mask(),
                    b.gameMask(GLFW_KEY_H), "h changes home and toggles autopilot"),
                () -> assertEquals(ControlKey.REFUEL.mask() | ControlKey.CONNECTOR.mask(),
                    b.gameMask(GLFW_KEY_LEFT_CONTROL), "Control_L refuels and connects"),
                () -> assertEquals(ControlKey.SWAP_SETTINGS.mask(), b.gameMask(GLFW_KEY_ESCAPE), "Escape swaps settings"),
                () -> assertEquals(0L, b.gameMask(GLFW_KEY_M), "m is not sent: it opens the talk line"),
                () -> assertTrue(KeyBindings.has(b.actionMask(GLFW_KEY_M), ClientAction.TALK), "m talks"),
                () -> assertTrue(KeyBindings.has(b.actionMask(GLFW_KEY_F1), ClientAction.MSG_1), "F1 talk macro 1"),
                () -> assertTrue(KeyBindings.has(b.actionMask(GLFW_KEY_U), ClientAction.ID_MODE), "u id mode"),
                () -> assertEquals(ControlKey.LOCK_NEXT_CLOSE.mask(), b.gameMask(GLFW_KEY_DOWN), "Down locks next close"),
                () -> assertTrue(KeyBindings.has(b.actionMask(GLFW_KEY_DOWN), ClientAction.TALK_CURSOR_DOWN),
                    "Down is also the talk cursor"),
                () -> assertEquals(0L, b.gameMask(-1), "negative codes are unbound"),
                () -> assertEquals(0L, b.actionMask(KeyBindings.SIZE), "codes past the table are unbound"));
        }

        @Test
        @DisplayName("bind counts mapped keysyms; unbind clears a key")
        void rebind() {
            KeyBindings b = new KeyBindings();
            int n = b.bind(ControlKey.FIRE_SHOT, " x  Linefeed y ");
            int none = b.bind(ClientAction.TALK, "");
            b.unbind(GLFW_KEY_X);
            b.unbind(-5);
            assertAll(
                () -> assertEquals(2, n, "x and y bound, Linefeed skipped"),
                () -> assertEquals(0, none, "empty binding"),
                () -> assertEquals(0L, b.gameMask(GLFW_KEY_X), "x unbound"),
                () -> assertEquals(ControlKey.FIRE_SHOT.mask(), b.gameMask(GLFW_KEY_Y), "y still fires"));
        }
    }

    @Nested
    @DisplayName("InputState")
    class State {
        @Test
        @DisplayName("the mask is the OR of held keys; a shared function stays on while any of its keys is held")
        void mask() {
            InputState in = new InputState(KeyBindings.defaults());
            in.press(GLFW_KEY_LEFT_SHIFT);
            in.press(GLFW_KEY_RIGHT_SHIFT);
            in.press(GLFW_KEY_A);
            long both = in.keys();
            in.release(GLFW_KEY_LEFT_SHIFT);
            long oneShift = in.keys();
            in.release(GLFW_KEY_RIGHT_SHIFT);
            in.release(GLFW_KEY_RIGHT_SHIFT);
            in.release(999);
            long acts = in.press(GLFW_KEY_M);
            long cursor = in.press(GLFW_KEY_DOWN);
            assertAll(
                () -> assertEquals(ControlKey.THRUST.mask() | ControlKey.TURN_LEFT.mask(), both, "thrust and turn"),
                () -> assertEquals(both, oneShift, "thrust stays on with one shift still held"),
                () -> assertTrue(KeyBindings.has(acts, ClientAction.TALK), "m returns the talk action"),
                () -> assertFalse(KeyBindings.has(cursor, ClientAction.TALK_CURSOR_DOWN),
                    "talk cursor actions are not reported outside talk mode"),
                () -> assertEquals(ControlKey.TURN_LEFT.mask() | ControlKey.LOCK_NEXT_CLOSE.mask(), in.keys(),
                    "both shifts released; Down's game key is held"),
                () -> assertEquals(0L, in.press(-1), "bad codes ignored"));
        }

        @Test
        @DisplayName("talking releases all game keys, ignores game keys and reports only talk cursor keys")
        void talking() {
            InputState in = new InputState(KeyBindings.defaults());
            in.press(GLFW_KEY_A);
            in.setTalking(true);
            long afterOpen = in.keys();
            long a = in.press(GLFW_KEY_S);
            long cursor = in.press(GLFW_KEY_LEFT);
            long keysWhileTalking = in.keys();
            in.setTalking(false);
            in.press(GLFW_KEY_S);
            assertAll(
                () -> assertEquals(0L, afterOpen, "opening the talk line releases keys"),
                () -> assertTrue(in.talking() == false, "closed again"),
                () -> assertEquals(0L, a, "no action for s while talking"),
                () -> assertTrue(KeyBindings.has(cursor, ClientAction.TALK_CURSOR_LEFT), "Left moves the talk cursor"),
                () -> assertEquals(0L, keysWhileTalking, "no game keys while talking"),
                () -> assertEquals(ControlKey.TURN_RIGHT.mask(), in.keys(), "keys work after closing"),
                () -> assertTrue(ClientAction.TALK_CURSOR_UP.talkCursor(), "Up is a talk cursor action"),
                () -> assertFalse(ClientAction.TALK.talkCursor(), "TALK is not"));
        }
    }
}
