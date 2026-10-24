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

import com.fulcrologic.jxpilot.client.game.TalkMacros;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** msg1..20, pointerControl, pointerButton1..5 and messagesToStdout from ~/.xpilotrc (C default.c). */
class PlayResourcesTest {
    static long act(ClientAction a) {
        return 1L << a.ordinal();
    }

    @Test
    @DisplayName("C defaults: empty macros, pointer control off, buttons fire / thrust / shield, no stdout")
    void defaults() {
        PlayResources r = new PlayResources();
        assertAll(
            () -> assertEquals("", r.talkMacros[0], "msg1 empty"),
            () -> assertEquals("", r.talkMacros[TalkMacros.COUNT - 1], "msg20 empty"),
            () -> assertFalse(r.pointerControl, "pointerControl No"),
            () -> assertEquals(ControlKey.FIRE_SHOT.mask(), r.buttonGame[0], "button 1: keyFireShot"),
            () -> assertEquals(ControlKey.THRUST.mask(), r.buttonGame[1], "button 2: keyThrust"),
            () -> assertEquals(ControlKey.SHIELD.mask(), r.buttonGame[2], "button 3: keyShield"),
            () -> assertEquals(0, r.buttonGame[3] | r.buttonGame[4] | r.buttonActions[0], "4 and 5 unbound"),
            () -> assertEquals(0, r.messagesToStdout, "messagesToStdout 0"));
    }

    @Test
    @DisplayName("resources are taken by name (any case); others are left for the caller")
    void apply() {
        PlayResources r = new PlayResources();
        List<String> problems = new ArrayList<>();
        boolean msg = r.apply(new XpilotRc.Entry("msg1", "hello #ln", 1), (l, m) -> problems.add(l + " " + m));
        boolean msg20 = r.apply(new XpilotRc.Entry("Msg20", "x".repeat(500), 2), (l, m) -> problems.add(l + " " + m));
        boolean msg21 = r.apply(new XpilotRc.Entry("msg21", "no", 3), (l, m) -> problems.add(l + " " + m));
        boolean msg0 = r.apply(new XpilotRc.Entry("msg01", "no", 4), (l, m) -> problems.add(l + " " + m));
        boolean pc = r.apply(new XpilotRc.Entry("pointerControl", "Yes", 5), (l, m) -> problems.add(l + " " + m));
        boolean pcBad = r.apply(new XpilotRc.Entry("pointerControl", "maybe", 6), (l, m) -> problems.add(l + " " + m));
        boolean out = r.apply(new XpilotRc.Entry("messagesToStdout", " 2 ", 7), (l, m) -> problems.add(l + " " + m));
        boolean outBad = r.apply(new XpilotRc.Entry("messagesToStdout", "lots", 8), (l, m) -> problems.add(l + " " + m));
        boolean other = r.apply(new XpilotRc.Entry("power", "40", 9), (l, m) -> problems.add(l + " " + m));
        assertAll(
            () -> assertTrue(msg && msg20 && pc && pcBad && out && outBad, "taken"),
            () -> assertFalse(msg21 || msg0 || other, "msg21, msg01 and others are not"),
            () -> assertEquals("hello #ln", r.talkMacros[0], "msg1"),
            () -> assertEquals(TalkMacros.MSG_SIZE - 1, r.talkMacros[19].length(), "cut to 399 chars"),
            () -> assertTrue(r.pointerControl, "Yes"),
            () -> assertEquals(2, r.messagesToStdout, "2"),
            () -> assertEquals(List.of("6 pointerControl: not a boolean: 'maybe'",
                "8 messagesToStdout: not an integer: 'lots'"), problems, "bad values reported, old values kept"));
    }

    @Test
    @DisplayName("pointer buttons: key function names with or without 'key', any case, at most 3, unknown skipped")
    void buttons() {
        PlayResources r = new PlayResources();
        List<String> problems = new ArrayList<>();
        r.apply(new XpilotRc.Entry("pointerButton1", "keyThrust  turnleft\tKEYTALK keySendMsg2", 1),
            (l, m) -> problems.add(m));
        r.apply(new XpilotRc.Entry("pointerButton5", "keyBogus keyShield", 2), (l, m) -> problems.add(m));
        r.apply(new XpilotRc.Entry("pointerButton2", "", 3), (l, m) -> problems.add(m));
        boolean six = r.apply(new XpilotRc.Entry("pointerButton6", "keyThrust", 4), (l, m) -> problems.add(m));
        assertAll(
            () -> assertEquals(ControlKey.THRUST.mask() | ControlKey.TURN_LEFT.mask(), r.buttonGame[0], "game functions"),
            () -> assertEquals(act(ClientAction.TALK), r.buttonActions[0], "a client action; the 4th is dropped"),
            () -> assertEquals(ControlKey.SHIELD.mask(), r.buttonGame[4], "the known one of button 5"),
            () -> assertEquals(0, r.buttonGame[1], "empty: unbound"),
            () -> assertFalse(six, "there are 5 buttons"),
            () -> assertEquals(List.of("Can only have 3 keys bound to pointer button 0",
                "Unknown key \"keyBogus\" for pointer button 4"), problems, "C's texts (0-based numbers)"),
            () -> assertNull(PlayResources.bool("2"), "not a boolean"),
            () -> assertEquals(Boolean.TRUE, PlayResources.bool(" ON "), "on"),
            () -> assertEquals(Boolean.FALSE, PlayResources.bool("0"), "0"));
    }

    @Test
    @DisplayName("KeyConfig hands the play resources to PlayResources")
    void viaKeyConfig() {
        KeyConfig c = KeyConfig.load("msg3: gg\npointerButton4: keyFireShot\n", "rc", null);
        assertAll(
            () -> assertEquals("gg", c.play().talkMacros[2], "msg3"),
            () -> assertEquals(ControlKey.FIRE_SHOT.mask(), c.play().buttonGame[3], "button 4"),
            () -> assertEquals(List.of(), c.problems(), "no 'not used' problems"));
    }
}
