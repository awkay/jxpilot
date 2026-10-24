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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

@DisplayName("Resource file parsing, keysyms, functions and control settings")
class XpilotRcTest {
    static List<String> entries(String text) {
        return XpilotRc.parse(text).entries().stream().map(e -> e.line() + " " + e.name() + "=" + e.value()).toList();
    }

    @Nested
    @DisplayName("XpilotRc.parse")
    class Parse {
        @Test
        @DisplayName("comments, trailing comments, continuations, prefixes and empty values")
        void lines() {
            String text = "! a comment\n"
                + "\n"
                + "xpilot.keyThrust:   Shift_L   ! trailing comment\n"
                + "xpilot*keyShield: space\\\n"
                + "  Caps_Lock\n"
                + "XPilot.keyTalk: m\r\n"
                + "*power: 40\n"
                + ".turnSpeed: 30\n"
                + "keySendMsg11:\n"
                + "keyFireShot: Return!x\n"
                + "   ! indented comment\n";
            assertEquals(List.of(
                "3 keyThrust=Shift_L",
                "4 keyShield=space  Caps_Lock",
                "6 keyTalk=m",
                "7 power=40",
                "8 turnSpeed=30",
                "9 keySendMsg11=",
                "10 keyFireShot=Return!x"), entries(text),
                "'!' starts a comment at the line start or after whitespace only; a backslash joins lines");
        }

        @Test
        @DisplayName("malformed lines are problems with their line numbers")
        void problems() {
            XpilotRc.Result r = XpilotRc.parse("keyThrust Shift_L\n: x\nxterm.font: y\n# define\nok: 1\nlast\\");
            assertAll(
                () -> assertEquals(List.of("1 expected 'resource: value', got: keyThrust Shift_L",
                    "2 missing resource name: : x", "3 not an xpilot resource (ignored): xterm.font",
                    "4 '#' directives are not supported: # define", "6 expected 'resource: value', got: last\\"),
                    r.problems().stream().map(p -> p.line() + " " + p.message()).toList(), "each problem"),
                () -> assertEquals(List.of("5 ok=1"), entries("keyThrust Shift_L\n: x\nxterm.font: y\n# define\nok: 1\n"),
                    "good lines are kept"));
        }

        @Test
        @DisplayName("prefix handling")
        void prefixes() {
            assertAll(
                () -> assertEquals("keyThrust", XpilotRc.stripPrefix("keyThrust"), "no prefix"),
                () -> assertEquals("keyThrust", XpilotRc.stripPrefix("xpilot.keyThrust"), "xpilot."),
                () -> assertEquals("keyThrust", XpilotRc.stripPrefix("XPILOT*keyThrust"), "any case"),
                () -> assertEquals("keyThrust", XpilotRc.stripPrefix("*keyThrust"), "*"),
                () -> assertEquals("keyThrust", XpilotRc.stripPrefix("xpilot*.keyThrust"), "loose binding"),
                () -> assertNull(XpilotRc.stripPrefix("xterm.keyThrust"), "other applications"),
                () -> assertEquals("a b ", XpilotRc.stripComment("a b ! c"), "comment after a space (the caller trims)"),
                () -> assertEquals("a!b", XpilotRc.stripComment("a!b"), "'!' inside a word stays"),
                () -> assertEquals("", XpilotRc.stripComment("!x"), "whole-line comment"));
        }
    }

    @Nested
    @DisplayName("KeySyms helpers")
    class Syms {
        @Test
        @DisplayName("known, reserved and case suggestions")
        void helpers() {
            assertAll(
                () -> assertTrue(KeySyms.known("Linefeed"), "a keysym without a GLFW key is known"),
                () -> assertTrue(KeySyms.known("minus"), "a mapped keysym is known"),
                () -> assertFalse(KeySyms.known("Minus"), "keysyms are case-sensitive"),
                () -> assertTrue(KeySyms.reserved("Super_L"), "Command is reserved"),
                () -> assertFalse(KeySyms.reserved("Alt_L"), "Option is not"),
                () -> assertEquals("Page_Up", KeySyms.suggest("page_up"), "named key"),
                () -> assertEquals("Linefeed", KeySyms.suggest("LINEFEED"), "a no-key keysym"),
                () -> assertEquals("F10", KeySyms.suggest("f10"), "function key"),
                () -> assertEquals("KP_7", KeySyms.suggest("kp_7"), "keypad digit"),
                () -> assertNull(KeySyms.suggest("f99"), "no such function key"),
                () -> assertNull(KeySyms.suggest("bogus"), "nothing close"),
                () -> assertEquals(GLFW_KEY_MINUS, KeySyms.glfwKey("minus"), "minus"),
                () -> assertEquals(GLFW_KEY_EQUAL, KeySyms.glfwKey("equal"), "equal"),
                () -> assertEquals(GLFW_KEY_LEFT_BRACKET, KeySyms.glfwKey("bracketleft"), "bracketleft"),
                () -> assertEquals(GLFW_KEY_GRAVE_ACCENT, KeySyms.glfwKey("grave"), "grave"),
                () -> assertEquals(GLFW_KEY_KP_9, KeySyms.glfwKey("KP_Prior"), "KP_Prior is keypad 9"),
                () -> assertEquals(GLFW_KEY_PAUSE, KeySyms.glfwKey("Pause"), "Pause"));
        }
    }

    @Nested
    @DisplayName("KeyFunctions")
    class Functions {
        @Test
        @DisplayName("every game key but TALK and every client action, findable by resource name")
        void table() {
            assertAll(
                () -> assertEquals(63 + ClientAction.values().length, KeyFunctions.count(), "count"),
                () -> assertEquals("keyTurnLeft", KeyFunctions.all().get(0).resource(), "C's first key resource"),
                () -> assertEquals(ControlKey_TURN_SPEED_UP, KeyFunctions.byResource("keyIncreaseTurnspeed").key(),
                    "exact name"),
                () -> assertEquals(ControlKey_TURN_SPEED_UP, KeyFunctions.byResource("KEYINCREASETURNSPEED").key(),
                    "any case"),
                () -> assertNull(KeyFunctions.byResource("keyNothing"), "unknown"),
                () -> assertEquals(ClientAction.HELP, KeyFunctions.byResource("keyHelp").action(), "keyHelp"),
                () -> assertNull(KeyFunctions.of(com.fulcrologic.jxpilot.common.net.ControlKey.TALK), "TALK is an action"),
                () -> assertEquals("keyToggleAutoPilot",
                    KeyFunctions.of(com.fulcrologic.jxpilot.common.net.ControlKey.TOGGLE_AUTOPILOT).resource(),
                    "C's spelling"),
                () -> assertEquals("keySendMsg20", KeyFunctions.of(ClientAction.MSG_20).resource(), "action lookup"),
                () -> assertEquals("F2", KeyFunctions.of(ClientAction.MSG_2).defaultKeys(), "action default keys"),
                () -> assertEquals("Shift_R Shift_L", KeyFunctions.byResource("keyThrust").defaultKeys(),
                    "game key default keys"));
        }

        static final com.fulcrologic.jxpilot.common.net.ControlKey ControlKey_TURN_SPEED_UP =
            com.fulcrologic.jxpilot.common.net.ControlKey.INCREASE_TURNSPEED;

        @Test
        @DisplayName("the implemented client actions")
        void implemented() {
            assertAll(
                () -> assertTrue(ClientAction.TALK.implemented(), "talk"),
                () -> assertTrue(ClientAction.TALK_CURSOR_UP.implemented(), "talk cursor"),
                () -> assertTrue(ClientAction.SWAP_SCALEFACTOR.implemented(), "scale swap"),
                () -> assertTrue(ClientAction.HELP.implemented(), "help"),
                () -> assertTrue(ClientAction.ID_MODE.implemented(), "ID mode"),
                () -> assertTrue(ClientAction.MSG_1.implemented(), "talk macros"),
                () -> assertTrue(ClientAction.POINTER_CONTROL.implemented(), "pointer control"),
                () -> assertFalse(ClientAction.TOGGLE_RECORD.implemented(), "recording is not"));
        }

        @Test
        @DisplayName("bindings: set replaces, add keeps one copy, functionsOf lists a key's functions")
        void bindings() {
            KeyBindings b = KeyBindings.defaults();
            KeyFunctions.Fn thrust = KeyFunctions.byResource("keyThrust");
            int n = b.add(thrust, "Shift_L Linefeed w");
            List<String> afterAdd = b.keysyms(thrust);
            b.set(thrust, "x");
            assertAll(
                () -> assertEquals(2, n, "two keysyms map (Linefeed does not)"),
                () -> assertEquals(List.of("Shift_R", "Shift_L", "Linefeed", "w"), afterAdd, "duplicates kept once"),
                () -> assertEquals(List.of("x"), b.keysyms(thrust), "set replaces"),
                () -> assertEquals(List.of(thrust, KeyFunctions.byResource("keyToggleMini")),
                    b.functionsOf(GLFW_KEY_X), "x thrusts and toggles mini, in C's order"),
                () -> assertEquals(List.of(), b.functionsOf(-1), "out of range: nothing"),
                () -> assertEquals(List.of(), b.functionsOf(KeyBindings.SIZE), "past the table: nothing"));
        }
    }

    @Nested
    @DisplayName("ControlSettings")
    class Settings {
        @Test
        @DisplayName("C's key handlers: +10%/-10% power, +5%/-5% turn speed, clamped; swap; copy")
        void handlers() {
            ControlSettings s = new ControlSettings();
            s.increasePower();
            double p1 = s.power;
            s.decreasePower();
            double p2 = s.power;
            s.increaseTurnSpeed();
            double t1 = s.turnSpeed;
            s.decreaseTurnSpeed();
            double t2 = s.turnSpeed;
            ControlSettings hi = new ControlSettings();
            hi.power = 54;
            hi.turnSpeed = 63;
            hi.increasePower();
            hi.increaseTurnSpeed();
            ControlSettings lo = new ControlSettings();
            lo.power = 5.2;
            lo.turnSpeed = 4.1;
            lo.decreasePower();
            lo.decreaseTurnSpeed();
            ControlSettings sw = new ControlSettings();
            sw.turnResistance = 0.2;
            sw.swap();
            Msg.Settings m = sw.copyTo(new Msg.Settings());
            assertAll(
                () -> assertEquals(45 * 1.10, p1, 1e-12, "power up 10%"),
                () -> assertEquals(45 * 1.10 * 0.90, p2, 1e-12, "power down 10%"),
                () -> assertEquals(40 * 1.05, t1, 1e-12, "turn speed up 5%"),
                () -> assertEquals(40 * 1.05 * 0.95, t2, 1e-12, "turn speed down 5%"),
                () -> assertEquals(Const.MAX_PLAYER_POWER, hi.power, "power at most 55"),
                () -> assertEquals(Const.MAX_PLAYER_TURNSPEED, hi.turnSpeed, "turn speed at most 64"),
                () -> assertEquals(Const.MIN_PLAYER_POWER, lo.power, "power at least 5"),
                () -> assertEquals(Const.MIN_PLAYER_TURNSPEED, lo.turnSpeed, "turn speed at least 4"),
                () -> assertEquals(35.0, sw.power, "swap: power = alt power"),
                () -> assertEquals(45.0, sw.altPower, "and back"),
                () -> assertEquals(25.0, sw.turnSpeed, "swap: turn speed = alt turn speed"),
                () -> assertEquals(40.0, sw.altTurnSpeed, "and back"),
                () -> assertEquals(0.12, sw.turnResistance, "swap: turn resistance"),
                () -> assertEquals(0.2, sw.altTurnResistance, 1e-12, "and back"),
                () -> assertEquals("35.0 25.0 0.12 45.0 40.0 0.2", m.power + " " + m.turnSpeed + " " + m.turnResistance
                    + " " + m.altPower + " " + m.altTurnSpeed + " " + m.altTurnResistance, "copyTo copies all six"));
        }
    }
}
