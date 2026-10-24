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
import com.fulcrologic.jxpilot.client.game.options.ClientOption;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** msg1..20, pointerControl, pointerButton1..5 and messagesToStdout from the client options (C default.c). */
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
    @DisplayName("update takes the options: macros (cut to 399 chars), buttons, pointerControl, messagesToStdout")
    void update() {
        ClientOptions o = new ClientOptions();
        o.apply(ClientOption.message(1), "hello #ln");
        o.apply(ClientOption.message(20), "x".repeat(500));
        o.apply(ClientOption.pointerButton(2), "keyFireMissile keyBogus");
        o.apply(ClientOption.POINTER_CONTROL, "yes");
        o.apply(ClientOption.MESSAGES_TO_STDOUT, "2");
        PlayResources r = new PlayResources();
        List<String> problems = new ArrayList<>();
        r.update(o, problems::add);
        assertAll(
            () -> assertEquals("hello #ln", r.talkMacros[0], "msg1"),
            () -> assertEquals(TalkMacros.MSG_SIZE - 1, r.talkMacros[19].length(), "msg20 cut to 399 chars"),
            () -> assertEquals("", r.talkMacros[1], "msg2 default"),
            () -> assertEquals(ControlKey.FIRE_SHOT.mask(), r.buttonGame[0], "button 1 default"),
            () -> assertEquals(ControlKey.FIRE_MISSILE.mask(), r.buttonGame[1], "button 2 from the option"),
            () -> assertTrue(r.pointerControl, "pointerControl yes"),
            () -> assertEquals(2, r.messagesToStdout, "messagesToStdout 2"),
            () -> assertEquals(List.of("Unknown key \"keyBogus\" for pointer button 1"), problems, "problems reported"));
    }

    @Test
    @DisplayName("pointer buttons: key function names with or without 'key', any case, at most 3, unknown skipped")
    void buttons() {
        PlayResources r = new PlayResources();
        List<String> problems = new ArrayList<>();
        r.pointerButton(0, "keyThrust  turnleft\tKEYTALK keySendMsg2", problems::add);
        r.pointerButton(4, "keyBogus keyShield", problems::add);
        r.pointerButton(1, "", problems::add);
        r.pointerButton(3, "keyBogus", null);
        assertAll(
            () -> assertEquals(ControlKey.THRUST.mask() | ControlKey.TURN_LEFT.mask(), r.buttonGame[0], "game functions"),
            () -> assertEquals(act(ClientAction.TALK), r.buttonActions[0], "a client action; the 4th is dropped"),
            () -> assertEquals(ControlKey.SHIELD.mask(), r.buttonGame[4], "the known one of button 5"),
            () -> assertEquals(0, r.buttonGame[1], "empty: unbound"),
            () -> assertEquals(0, r.buttonGame[3], "a null sink ignores problems"),
            () -> assertEquals(List.of("Can only have 3 keys bound to pointer button 0",
                "Unknown key \"keyBogus\" for pointer button 4"), problems, "C's texts (0-based numbers)"));
    }

}
