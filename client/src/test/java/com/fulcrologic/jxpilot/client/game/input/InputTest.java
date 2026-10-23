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
        @DisplayName("a press sets the key's functions; any release of a bound key clears them (C), even with another held")
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
                () -> assertEquals(SHIELD | ControlKey.THRUST.mask() | ControlKey.TURN_LEFT.mask(), both,
                    "thrust and turn, with the initial shield (C Key_init)"),
                () -> assertEquals(SHIELD | ControlKey.TURN_LEFT.mask(), oneShift,
                    "releasing one shift clears thrust although the other is held (C xevent.c Key_release)"),
                () -> assertTrue(KeyBindings.has(acts, ClientAction.TALK), "m returns the talk action"),
                () -> assertFalse(KeyBindings.has(cursor, ClientAction.TALK_CURSOR_DOWN),
                    "talk cursor actions are not reported outside talk mode"),
                () -> assertEquals(SHIELD | ControlKey.TURN_LEFT.mask() | ControlKey.LOCK_NEXT_CLOSE.mask(), in.keys(),
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
                () -> assertEquals(SHIELD, afterOpen, "opening the talk line releases keys (the shield stays up)"),
                () -> assertTrue(in.talking() == false, "closed again"),
                () -> assertEquals(0L, a, "no action for s while talking"),
                () -> assertTrue(KeyBindings.has(cursor, ClientAction.TALK_CURSOR_LEFT), "Left moves the talk cursor"),
                () -> assertEquals(SHIELD, keysWhileTalking, "no game keys while talking"),
                () -> assertEquals(SHIELD | ControlKey.TURN_RIGHT.mask(), in.keys(), "keys work after closing"),
                () -> assertTrue(ClientAction.TALK_CURSOR_UP.talkCursor(), "Up is a talk cursor action"),
                () -> assertFalse(ClientAction.TALK.talkCursor(), "TALK is not"));
        }
    }

    static final long SHIELD = ControlKey.SHIELD.mask();
    static final long SHOT = ControlKey.FIRE_SHOT.mask();
    static final long LASER = ControlKey.FIRE_LASER.mask();

    @Nested
    @DisplayName("shields (C autoShield / toggleShield)")
    class Shields {
        @Test
        @DisplayName("autoShield: firing lowers the shield; it comes back when the last fire key is released")
        void autoShieldFire() {
            InputState in = new InputState(KeyBindings.defaults());
            long initial = in.keys();
            in.press(GLFW_KEY_ENTER);
            long firing = in.keys();
            in.press(GLFW_KEY_SLASH);
            in.release(GLFW_KEY_ENTER);
            long laserStill = in.keys();
            in.release(GLFW_KEY_SLASH);
            assertAll(
                () -> assertEquals(SHIELD, initial, "the mask starts shielded (C Key_init), so the server's spawn shield stays"),
                () -> assertTrue(in.autoShield, "autoShield is on by default (default.c)"),
                () -> assertEquals(SHOT, firing, "fire drops the shield"),
                () -> assertEquals(LASER, laserStill, "still down while another fire key is held"),
                () -> assertEquals(SHIELD, in.keys(), "back up after the last fire key"));
        }

        @Test
        @DisplayName("autoShield: releasing the shield key lowers the shields for good until the next spawn")
        void autoShieldKey() {
            InputState in = new InputState(KeyBindings.defaults());
            boolean upAtStart = in.shieldsUp();
            in.press(GLFW_KEY_SPACE);
            long held = in.keys();
            in.release(GLFW_KEY_SPACE);
            long released = in.keys();
            boolean upAfterRelease = in.shieldsUp();
            in.press(GLFW_KEY_ENTER);
            in.release(GLFW_KEY_ENTER);
            long afterFire = in.keys();
            in.setPlaying(true);
            long spawned = in.keys();
            in.release(GLFW_KEY_SPACE);
            in.setPlaying(true);
            long stillPlaying = in.keys();
            in.setPlaying(false);
            in.press(GLFW_KEY_ENTER);
            in.setPlaying(true);
            long spawnedFiring = in.keys();
            in.release(GLFW_KEY_ENTER);
            in.press(GLFW_KEY_SPACE);
            in.release(GLFW_KEY_SPACE);
            in.press(GLFW_KEY_SPACE);
            in.press(GLFW_KEY_ENTER);
            in.release(GLFW_KEY_ENTER);
            long pressedAgain = in.keys();
            in.setTalking(true);
            in.release(GLFW_KEY_SPACE);
            long talkingRelease = in.keys();
            assertAll(
                () -> assertTrue(upAtStart, "shields are considered up at start (C shields = 1)"),
                () -> assertEquals(SHIELD, held, "the shield key holds the shield"),
                () -> assertEquals(0L, released, "releasing it drops the shield"),
                () -> assertFalse(upAfterRelease, "and the shields are no longer considered up"),
                () -> assertEquals(0L, afterFire, "so releasing fire does not raise it"),
                () -> assertEquals(SHIELD, spawned, "entering play raises it (C Reset_shields)"),
                () -> assertEquals(0L, stillPlaying, "only on the transition into play"),
                () -> assertEquals(SHOT, spawnedFiring, "entering play while firing keeps it down"),
                () -> assertEquals(SHIELD, pressedAgain, "pressing the shield key marks the shields up again, so fire restores it"),
                () -> assertEquals(SHIELD, talkingRelease, "key releases are ignored while talking"));
        }

        @Test
        @DisplayName("without autoShield fire and shield are independent; with toggleShield the key toggles")
        void otherModes() {
            InputState off = new InputState(KeyBindings.defaults());
            off.autoShield = false;
            off.press(GLFW_KEY_ENTER);
            long firingShielded = off.keys();
            off.release(GLFW_KEY_SPACE);
            off.release(GLFW_KEY_ENTER);
            off.releaseAll();
            off.resetShields();
            long noReset = off.keys();
            InputState tog = new InputState(KeyBindings.defaults());
            tog.autoShield = false;
            tog.toggleShield = true;
            tog.press(GLFW_KEY_SPACE);
            long toggledOff = tog.keys();
            tog.release(GLFW_KEY_SPACE);
            long releaseIgnored = tog.keys();
            tog.press(GLFW_KEY_SPACE);
            long toggledOn = tog.keys();
            tog.release(GLFW_KEY_SPACE);
            long toggledOnReleased = tog.keys();
            tog.releaseAll();
            long kept = tog.keys();
            tog.press(GLFW_KEY_SPACE);
            tog.resetShields();
            assertAll(
                () -> assertEquals(SHIELD | SHOT, firingShielded, "fire leaves the shield alone"),
                () -> assertEquals(0L, noReset, "releaseAll clears everything and reset does nothing"),
                () -> assertEquals(0L, toggledOff, "toggle: the first press lowers the initial shield"),
                () -> assertEquals(0L, releaseIgnored, "release changes nothing"),
                () -> assertEquals(SHIELD, toggledOn, "the next press raises it"),
                () -> assertEquals(SHIELD, toggledOnReleased, "releasing after toggling on keeps it on"),
                () -> assertEquals(SHIELD, kept, "releaseAll keeps a toggled-on shield"),
                () -> assertEquals(SHIELD, tog.keys(), "reset raises it in toggle mode"));
        }
    }
}
