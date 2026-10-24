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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

@DisplayName("Key configuration (~/.xpilotrc)")
class KeyConfigTest {
    /** The user's Mac laptop starter file, shipped as an example. */
    static final Path MAC_LAPTOP = Path.of("..", "docs", "examples", "xpilotrc.mac-laptop");

    static long game(KeyConfig c, int key) {
        return c.bindings().gameMask(key);
    }

    static long acts(KeyConfig c, int key) {
        return c.bindings().actionMask(key);
    }

    static KeyFunctions.Fn fn(String name) {
        return KeyFunctions.byResource(name);
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {
        @Test
        @DisplayName("C defaults plus the Mac/laptop supplement; Escape and Command unbound; turn speed 40")
        void defaults() {
            KeyConfig c = KeyConfig.defaults(null);
            assertAll(
                () -> assertEquals(ControlKey.CONNECTOR.mask(), game(c, GLFW_KEY_LEFT_ALT), "left Option connects"),
                () -> assertEquals(ControlKey.CONNECTOR.mask(), game(c, GLFW_KEY_RIGHT_ALT), "right Option connects"),
                () -> assertEquals(ControlKey.REFUEL.mask() | ControlKey.CONNECTOR.mask(),
                    game(c, GLFW_KEY_LEFT_CONTROL), "Control_L still refuels and connects (C)"),
                () -> assertEquals(ControlKey.SELF_DESTRUCT.mask(), game(c, GLFW_KEY_0), "0 self-destructs (no fn)"),
                () -> assertEquals(ControlKey.SELF_DESTRUCT.mask(), game(c, GLFW_KEY_END), "End still self-destructs"),
                () -> assertEquals(ControlKey.LOCK_NEXT.mask(), game(c, GLFW_KEY_RIGHT), "Right locks next"),
                () -> assertEquals(ControlKey.LOCK_PREV.mask(), game(c, GLFW_KEY_LEFT), "Left locks previous"),
                () -> assertEquals(ControlKey.LOCK_CLOSE.mask(), game(c, GLFW_KEY_UP), "Up locks closest"),
                () -> assertEquals(0L, game(c, GLFW_KEY_ESCAPE) | acts(c, GLFW_KEY_ESCAPE), "Escape is unbound (quits)"),
                () -> assertEquals(0L, game(c, GLFW_KEY_LEFT_SUPER) | game(c, GLFW_KEY_RIGHT_SUPER)
                    | acts(c, GLFW_KEY_LEFT_SUPER) | acts(c, GLFW_KEY_RIGHT_SUPER), "Command is never bound"),
                () -> assertTrue(KeyBindings.has(acts(c, GLFW_KEY_F1), ClientAction.HELP), "F1 is the key help"),
                () -> assertEquals(ControlKey.INCREASE_TURNSPEED.mask(), game(c, GLFW_KEY_KP_ADD),
                    "keypad + still increases turn speed (C)"),
                () -> assertEquals(List.of(), c.conflicts(), "the defaults have no conflicts"),
                () -> assertEquals(List.of(), c.problems(), "and no problems"),
                () -> assertEquals(40.0, c.settings().turnSpeed, "turn speed 40 (JXPilot; C 35)"),
                () -> assertEquals(25.0, c.settings().altTurnSpeed, "alternate turn speed 25 (C)"),
                () -> assertEquals(45.0, c.settings().power, "power 45 (C)"),
                () -> assertEquals(0.12, c.settings().turnResistance, "turn resistance 0.12 (C)"),
                () -> assertEquals("defaults", c.source(), "source"));
        }

        @Test
        @DisplayName("the swap-scale key flag binds the scale swap after everything else")
        void swapKey() {
            KeyConfig c = KeyConfig.load("keySwapScaleFactor: q\n", "rc", "F12");
            assertAll(
                () -> assertTrue(KeyBindings.has(acts(c, GLFW_KEY_F12), ClientAction.SWAP_SCALEFACTOR), "F12 swaps"),
                () -> assertTrue(KeyBindings.has(acts(c, GLFW_KEY_Q), ClientAction.SWAP_SCALEFACTOR),
                    "the file's key too"));
        }
    }

    @Nested
    @DisplayName("the shipped Mac laptop file")
    class MacLaptop {
        @Test
        @DisplayName("loads cleanly with the effective bindings it asks for")
        void starter() throws IOException {
            KeyConfig c = KeyConfig.load(Files.readString(MAC_LAPTOP, StandardCharsets.ISO_8859_1), "mac", null);
            assertAll(
                () -> assertEquals(List.of(), c.problems(), "no problems"),
                () -> assertEquals(ControlKey.INCREASE_TURNSPEED.mask(), game(c, GLFW_KEY_F10), "F10: turn speed up"),
                () -> assertEquals(ControlKey.DECREASE_TURNSPEED.mask(), game(c, GLFW_KEY_F9), "F9: turn speed down"),
                () -> assertEquals(ControlKey.INCREASE_POWER.mask(), game(c, GLFW_KEY_F12), "F12: power up"),
                () -> assertEquals(ControlKey.DECREASE_POWER.mask(), game(c, GLFW_KEY_F11), "F11: power down"),
                () -> assertEquals(0L, acts(c, GLFW_KEY_F9) | acts(c, GLFW_KEY_F10) | acts(c, GLFW_KEY_F11)
                    | acts(c, GLFW_KEY_F12), "F9-F12 are no longer talk macros (empty keySendMsg9-12)"),
                () -> assertEquals(ControlKey.INCREASE_TURNSPEED.mask(), game(c, GLFW_KEY_KP_ADD), "keypad + kept"),
                () -> assertEquals(ControlKey.SELF_DESTRUCT.mask(), game(c, GLFW_KEY_0), "0 self-destructs"),
                () -> assertEquals(ControlKey.SELF_DESTRUCT.mask(), game(c, GLFW_KEY_END), "End self-destructs"),
                () -> assertEquals(ControlKey.PAUSE.mask(), game(c, GLFW_KEY_9), "9 pauses"),
                () -> assertEquals(ControlKey.LOSE_ITEM.mask(), game(c, GLFW_KEY_Y), "y loses the item"),
                () -> assertEquals(ControlKey.LOSE_ITEM.mask(), game(c, GLFW_KEY_KP_DECIMAL), "KP_Delete is keypad ."),
                () -> assertEquals(ControlKey.SELECT_ITEM.mask(), game(c, GLFW_KEY_GRAVE_ACCENT),
                    "grave only selects the item (reprogram moved off C's quoteleft)"),
                () -> assertEquals(ControlKey.REPROGRAM.mask(), game(c, GLFW_KEY_F8), "F8 reprograms"),
                () -> assertFalse(KeyBindings.has(acts(c, GLFW_KEY_F8), ClientAction.MSG_8), "F8 is no longer talk macro 8"),
                () -> assertEquals(ControlKey.DETONATE_MINES.mask(), game(c, GLFW_KEY_U), "u detonates mines"),
                () -> assertFalse(KeyBindings.has(acts(c, GLFW_KEY_U), ClientAction.ID_MODE), "ID mode is unbound"),
                () -> assertEquals(0L, game(c, GLFW_KEY_EQUAL), "= no longer detonates"),
                () -> assertEquals(ControlKey.FIRE_LASER.mask(), game(c, GLFW_KEY_SLASH), "slash fires the laser"),
                () -> assertTrue(KeyBindings.has(acts(c, GLFW_KEY_F2), ClientAction.MSG_2), "F2 keeps its macro"),
                () -> assertEquals(List.of(), c.conflicts(), "no key is bound twice"),
                () -> assertEquals(40.0, c.settings().turnSpeed, "settings keep the defaults"));
        }
    }

    @Nested
    @DisplayName("load: overrides")
    class Overrides {
        @Test
        @DisplayName("a function's line replaces its keys; other functions keep theirs; the last line wins")
        void perFunction() {
            KeyConfig c = KeyConfig.load("xpilot.keyThrust: w\nkeyFireShot: x\nkeyFireShot: z k\n", "rc", null);
            assertAll(
                () -> assertEquals(ControlKey.THRUST.mask() | ControlKey.TANK_PREV.mask(), game(c, GLFW_KEY_W),
                    "w thrusts (and still selects the previous tank)"),
                () -> assertEquals(0L, game(c, GLFW_KEY_LEFT_SHIFT), "Shift no longer thrusts"),
                () -> assertEquals(ControlKey.FIRE_SHOT.mask() | ControlKey.TOGGLE_SPREAD.mask(), game(c, GLFW_KEY_Z),
                    "the last keyFireShot line wins"),
                () -> assertEquals(ControlKey.TOGGLE_MINI.mask(), game(c, GLFW_KEY_X), "the first one is replaced"),
                () -> assertEquals(0L, game(c, GLFW_KEY_ENTER), "Return no longer fires"),
                () -> assertEquals(ControlKey.TURN_LEFT.mask(), game(c, GLFW_KEY_A), "untouched functions keep C's keys"),
                () -> assertEquals(List.of("k: keyFireShot keyClearModifiers", "w: keyThrust keyTankPrev",
                    "z: keyFireShot keyToggleSpread"), sortedFns(c.conflicts()), "new double bindings are conflicts"),
                () -> assertEquals("defaults + rc", c.source(), "source"));
        }

        @Test
        @DisplayName("an empty value unbinds the function, supplement included")
        void unbind() {
            KeyConfig c = KeyConfig.load("keySendMsg2:\nkeyConnector:\n", "rc", null);
            assertAll(
                () -> assertEquals(0L, acts(c, GLFW_KEY_F2), "F2 is free"),
                () -> assertEquals(List.of(), c.bindings().keysyms(fn("keySendMsg2")), "msg 2 has no keys"),
                () -> assertEquals(0L, game(c, GLFW_KEY_LEFT_ALT), "Option no longer connects"),
                () -> assertEquals(ControlKey.REFUEL.mask(), game(c, GLFW_KEY_LEFT_CONTROL), "Control only refuels"),
                () -> assertEquals(List.of(), c.problems(), "no problems"));
        }

        @Test
        @DisplayName("control settings resources set the settings, with or without the prefix and in any case")
        void settings() {
            KeyConfig c = KeyConfig.load("xpilot.power: 50\nturnSpeed: 30.5\nturnresistance: 0.2\naltPower: 20\n"
                + "XPilot*altTurnSpeed: 10\n*altTurnResistance: 0.5\n", "rc", null);
            ControlSettings s = c.settings();
            assertAll(
                () -> assertEquals(50.0, s.power, "power"),
                () -> assertEquals(30.5, s.turnSpeed, "turnSpeed"),
                () -> assertEquals(0.2, s.turnResistance, "turnResistance (lower case name)"),
                () -> assertEquals(20.0, s.altPower, "altPower"),
                () -> assertEquals(10.0, s.altTurnSpeed, "altTurnSpeed with XPilot*"),
                () -> assertEquals(0.5, s.altTurnResistance, "altTurnResistance with *"),
                () -> assertEquals(List.of(), c.problems(), "no problems"));
        }

        @Test
        @DisplayName("shipShape and shipShapeFile are kept for the shape (the last line wins), null when absent")
        void shipShape() {
            KeyConfig c = KeyConfig.load("xpilot.shipShape: first\nshipShape: (SH: 15,0 -9,8 -9,-8)\n"
                + "xpilot.shipShapeFile: /tmp/ships\n", "rc", null);
            KeyConfig none = KeyConfig.load("power: 50\n", "rc", null);
            assertAll(
                () -> assertEquals("(SH: 15,0 -9,8 -9,-8)", c.shipShape(), "the last shipShape line"),
                () -> assertEquals("/tmp/ships", c.shipShapeFile(), "shipShapeFile with the xpilot. prefix"),
                () -> assertEquals(List.of(), c.problems(), "neither is reported as unused"),
                () -> assertNull(none.shipShape(), "no shipShape line: null"),
                () -> assertNull(none.shipShapeFile(), "no shipShapeFile line: null"),
                () -> assertNull(KeyConfig.defaults(null).shipShape(), "the defaults have no shape"));
        }

        @Test
        @DisplayName("maxVolume (C default.c): an integer 0-255, clamped and reported outside, ignored when not a number")
        void maxVolume() {
            KeyConfig set = KeyConfig.load("xpilot.maxVolume: 40\n", "rc", null);
            KeyConfig edge = KeyConfig.load("maxvolume: 255\n", "rc", null);
            KeyConfig high = KeyConfig.load("maxVolume: 300\n", "rc", null);
            KeyConfig low = KeyConfig.load("maxVolume: -3\n", "rc", null);
            KeyConfig bad = KeyConfig.load("maxVolume: 40%\n", "rc", null);
            KeyConfig zero = KeyConfig.load("maxVolume: 0\n", "rc", null);
            assertAll(
                () -> assertEquals(40, set.maxVolume(), "the value, with the xpilot. prefix"),
                () -> assertEquals(List.of(), set.problems(), "no problems"),
                () -> assertEquals(255, edge.maxVolume(), "255 is allowed (any case)"),
                () -> assertEquals(List.of(), edge.problems(), "without a problem"),
                () -> assertEquals(0, zero.maxVolume(), "0 is allowed (no sound)"),
                () -> assertEquals(255, high.maxVolume(), "too high is clamped"),
                () -> assertEquals(List.of("rc:1: maxVolume: 300 is outside 0-255, using 255"), high.problems(), "and reported"),
                () -> assertEquals(0, low.maxVolume(), "negative is clamped to 0"),
                () -> assertEquals(List.of("rc:1: maxVolume: -3 is outside 0-255, using 0"), low.problems(), "and reported"),
                () -> assertEquals(-1, bad.maxVolume(), "not an integer: not set"),
                () -> assertEquals(List.of("rc:1: maxVolume: not an integer: '40%'"), bad.problems(), "and reported"),
                () -> assertEquals(-1, KeyConfig.load("power: 50\n", "rc", null).maxVolume(), "a file without it: not set"),
                () -> assertEquals(-1, KeyConfig.defaults(null).maxVolume(), "the defaults: not set"));
        }
    }

    @Nested
    @DisplayName("load: problems")
    class Problems {
        @Test
        @DisplayName("bad keysyms, names and values are reported with file and line; the rest still applies")
        void reported() {
            String rc = String.join("\n",
                "keyThrust: end Shift_L",
                "keyThurst: a",
                "xpilot.name: Tony",
                "keyShield: Super_L space",
                "keyFireShot: Escape Return",
                "keyDropBall Linefeed",
                "power: lots",
                "turnSpeed: 99",
                "altPower: 1",
                "turnResistance: 2",
                "keyFireLaser: Linefeed slash",
                "Xterm*font: fixed",
                "#include \"x\"",
                "keyCloak: ff");
            KeyConfig c = KeyConfig.load(rc, "rc", null);
            assertAll(
                () -> assertEquals(List.of(
                    "rc:6: expected 'resource: value', got: keyDropBall Linefeed",
                    "rc:12: not an xpilot resource (ignored): Xterm*font",
                    "rc:13: '#' directives are not supported: #include \"x\"",
                    "rc:1: keyThrust: unknown keysym 'end' (did you mean 'End'?), skipped",
                    "rc:2: unknown key function 'keyThurst'",
                    "rc:3: resource 'name' is not used by JXPilot (ignored)",
                    "rc:4: keyShield: Super_L (Command/Super) cannot be bound, skipped",
                    "rc:5: keyFireShot: Escape always quits JXPilot, skipped",
                    "rc:7: power: not a number: 'lots'",
                    "rc:8: turnSpeed: 99 is outside 4-64, using 64",
                    "rc:9: altPower: 1 is outside 5-55, using 5",
                    "rc:10: turnResistance: 2 is outside 0-1, using 1",
                    "rc:11: keyFireLaser: keysym 'Linefeed' has no key JXPilot can read, skipped",
                    "rc:14: keyCloak: unknown keysym 'ff', skipped"), c.problems(), "every problem, parse errors first"),
                () -> assertEquals(ControlKey.THRUST.mask(), game(c, GLFW_KEY_LEFT_SHIFT), "Shift_L still thrusts"),
                () -> assertEquals(0L, game(c, GLFW_KEY_RIGHT_SHIFT), "the line replaced Shift_R"),
                () -> assertEquals(ControlKey.SHIELD.mask(), game(c, GLFW_KEY_SPACE), "space still shields"),
                () -> assertEquals(ControlKey.FIRE_SHOT.mask(), game(c, GLFW_KEY_ENTER), "Return still fires"),
                () -> assertEquals(0L, game(c, GLFW_KEY_ESCAPE), "Escape is not bound"),
                () -> assertEquals(ControlKey.FIRE_LASER.mask(), game(c, GLFW_KEY_SLASH), "slash fires the laser"),
                () -> assertEquals(List.of(), c.bindings().keysyms(fn("keyCloak")), "keyCloak's only keysym was bad"),
                () -> assertEquals(45.0, c.settings().power, "a bad number keeps the default"),
                () -> assertEquals(64.0, c.settings().turnSpeed, "too high is clamped"),
                () -> assertEquals(5.0, c.settings().altPower, "too low is clamped"),
                () -> assertEquals(1.0, c.settings().turnResistance, "turn resistance clamped to 1"));
        }

        @Test
        @DisplayName("NaN is clamped to the low end")
        void nan() {
            KeyConfig c = KeyConfig.load("power: NaN\n", "rc", null);
            assertAll(
                () -> assertEquals(5.0, c.settings().power, "the minimum"),
                () -> assertEquals(List.of("rc:1: power: NaN is outside 5-55, using 5"), c.problems(), "reported"));
        }
    }

    @Nested
    @DisplayName("listing and help")
    class Listing {
        @Test
        @DisplayName("--list-keys lists every function with its keys, marks conflicts and ends with settings")
        void listing() {
            KeyConfig c = KeyConfig.load("keyDetonateMines: u\nkeySendMsg3:\nbogus\n", "rc", null);
            List<String> l = c.listing();
            assertAll(
                () -> assertEquals("JXPilot key bindings (defaults + rc); * = key bound to more than one function",
                    l.get(0), "header names the source"),
                () -> assertEquals(String.format("%-24s %-26s %s", "keyTurnLeft", "a", "Turn left (anti-clockwise)."),
                    l.get(1), "one line per function in C's order"),
                () -> assertTrue(l.contains(String.format("%-24s %-26s %s", "keyDetonateMines", "u*",
                    "Detonate the closest mine you have dropped or thrown.")), "a conflicting key has a *"),
                () -> assertTrue(l.contains(String.format("%-24s %-26s %s", "keySendMsg3", "-",
                    "Sends the talkmessage stored in msg3. (not in JXPilot)")), "unbound: '-'; unimplemented marked"),
                () -> assertTrue(l.contains(String.format("%-24s %-26s %s", "keyConnector", "Control_L Alt_L Alt_R",
                    "Connect to a ball.")), "the supplement is listed"),
                () -> assertTrue(l.contains("Keys bound to more than one function:"), "conflict heading"),
                () -> assertTrue(l.contains("  u: keyDetonateMines keyIdMode"), "the conflict"),
                () -> assertTrue(l.contains("power 45 turnSpeed 40 turnResistance 0.12 altPower 35 altTurnSpeed 25"
                    + " altTurnResistance 0.12"), "settings line"),
                () -> assertEquals("  rc:3: expected 'resource: value', got: bogus", l.get(l.size() - 1),
                    "problems last"),
                () -> assertTrue(KeyConfig.defaults(null).listing().contains("No conflicting keys."),
                    "no conflicts is said"));
        }

        @Test
        @DisplayName("help lines: bound, implemented functions in C's order, then the conflicts")
        void help() {
            KeyConfig c = KeyConfig.load("keyDetonateMines: u\n", "rc", null);
            List<String[]> h = c.helpLines();
            List<String> keys = h.stream().map(e -> e[0] + "|" + e[1]).toList();
            assertAll(
                () -> assertEquals("a|Turn left (anti-clockwise).", keys.get(0), "first entry"),
                () -> assertTrue(keys.contains("u*|Detonate the closest mine you have dropped or thrown."),
                    "conflicting key marked"),
                () -> assertTrue(keys.contains("F1|Show or hide this key help (JXPilot)."), "the help key itself"),
                () -> assertTrue(keys.contains("m|Toggle talk window."), "talk"),
                () -> assertFalse(keys.stream().anyMatch(k -> k.endsWith("|Toggle User mode (show real names).")),
                    "unimplemented client actions are left out"),
                () -> assertFalse(keys.stream().anyMatch(k -> k.startsWith("Left|Move Cursor")),
                    "talk cursor keys are left out"),
                () -> assertFalse(keys.stream().anyMatch(k -> k.endsWith("msg13.")), "unbound functions left out"),
                () -> assertEquals("* u|bound to keyDetonateMines keyIdMode", keys.get(keys.size() - 1),
                    "conflicts last"));
        }
    }

    /** Conflicts with each line's function names sorted (order within a line follows C's table). */
    static List<String> sortedFns(List<String> conflicts) {
        return conflicts.stream().map(s -> {
            String[] p = s.split(": ");
            List<String> f = new java.util.ArrayList<>(List.of(p[1].split(" ")));
            return p[0] + ": " + String.join(" ", f);
        }).toList();
    }

    @Nested
    @DisplayName("modifier banks (C modifierBank1..4)")
    class ModifierBanks {
        @Test
        @DisplayName("modifierBank1..4 set the four texts as written; others stay empty; over 79 chars is cut and reported")
        void banks() {
            KeyConfig c = KeyConfig.load(String.join("\n",
                "modifierBank1: FN C V2",
                "xpilot.modifierBank3:",
                "modifierBank4: X4 LS",
                "modifierBank4: Z1",
                "modifierBank5: N",
                "modifierBank2: " + "B".repeat(85)), "rc", null);
            String[] banks = c.modifierBanks();
            banks[0] = "changed";
            assertAll(
                () -> assertEquals("FN C V2", c.modifierBanks()[0], "bank 1 as written"),
                () -> assertEquals("B".repeat(79), c.modifierBanks()[1], "bank 2 cut to 79 chars (C MAX_CHARS buffer)"),
                () -> assertEquals("", c.modifierBanks()[2], "an empty value is an empty bank"),
                () -> assertEquals("Z1", c.modifierBanks()[3], "the last line for a bank wins"),
                () -> assertEquals(List.of("rc:5: resource 'modifierBank5' is not used by JXPilot (ignored)",
                    "rc:6: modifierBank2: longer than 79 chars, cut"), c.problems(),
                    "only bank 5 (no such bank) and the long text are reported"),
                () -> assertEquals(List.of("", "", "", ""), List.of(KeyConfig.defaults(null).modifierBanks()),
                    "the defaults are four empty banks (C default \"\")"),
                () -> assertEquals("FN C V2", c.modifierBanks()[0], "modifierBanks() returns a copy"));
        }
    }
}
