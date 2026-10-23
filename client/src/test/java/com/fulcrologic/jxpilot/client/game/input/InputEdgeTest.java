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

import com.fulcrologic.jxpilot.client.game.hud.TalkLine;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

/** Boundaries of key mapping, input state and the talk line. */
class InputEdgeTest {
    @Test
    @DisplayName("keysym character ranges are inclusive at both ends and nothing else maps")
    void ranges() {
        assertAll(
            () -> assertEquals(GLFW_KEY_A, KeySyms.glfwKey("A"), "A"),
            () -> assertEquals(GLFW_KEY_Z, KeySyms.glfwKey("z"), "z"),
            () -> assertEquals(GLFW_KEY_0, KeySyms.glfwKey("0"), "0"),
            () -> assertEquals(GLFW_KEY_9, KeySyms.glfwKey("9"), "9"),
            () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey("@"), "before A"),
            () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey("["), "after Z"),
            () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey("`"), "before a"),
            () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey("{"), "after z"),
            () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey("/"), "before 0"),
            () -> assertEquals(KeySyms.NONE, KeySyms.glfwKey(":"), "after 9"));
    }

    @Test
    @DisplayName("action binding counts keys; table edges")
    void bindings() {
        KeyBindings b = new KeyBindings();
        int n = b.bind(ClientAction.MSG_1, "F1 F2");
        b.bind(ControlKey.FIRE_SHOT, "Pause");
        int last = GLFW_KEY_LAST;
        b.bind(ClientAction.TALK, "a");
        b.unbind(KeyBindings.SIZE);
        long beforeUnbind = b.actionMask(GLFW_KEY_A);
        b.unbind(0);
        assertAll(
            () -> assertEquals(2, n, "two keysyms bound"),
            () -> assertTrue(KeyBindings.has(b.actionMask(GLFW_KEY_F2), ClientAction.MSG_1), "F2 sends macro 1"),
            () -> assertEquals(0L, b.gameMask(KeyBindings.SIZE), "one past the table"),
            () -> assertEquals(0L, b.gameMask(last), "the last code is in the table and unbound"),
            () -> assertEquals(0L, b.actionMask(-1), "negative code"),
            () -> assertTrue(beforeUnbind != 0, "unbinding outside the table changes nothing"),
            () -> assertEquals(ControlKey.FIRE_SHOT.mask(), b.gameMask(GLFW_KEY_PAUSE), "Pause bound"));
    }

    @Test
    @DisplayName("input state edges: table bounds, all cursor keys while talking, releaseAll")
    void state() {
        KeyBindings b = KeyBindings.defaults();
        InputState in = new InputState(b);
        long past = in.press(KeyBindings.SIZE);
        in.release(KeyBindings.SIZE);
        in.release(-1);
        in.press(GLFW_KEY_A);
        in.releaseAll();
        in.press(GLFW_KEY_S);
        in.release(GLFW_KEY_A);
        long afterStaleRelease = in.keys();
        in.setTalking(true);
        boolean talking = in.talking();
        long right = in.press(GLFW_KEY_RIGHT);
        long up = in.press(GLFW_KEY_UP);
        long down = in.press(GLFW_KEY_DOWN);
        assertAll(
            () -> assertEquals(0L, past, "past the table: ignored"),
            () -> assertEquals(ControlKey.SHIELD.mask() | ControlKey.TURN_RIGHT.mask(), afterStaleRelease,
                "releasing a key cleared by releaseAll does not resurrect it"),
            () -> assertTrue(talking, "talking"),
            () -> assertTrue(KeyBindings.has(right, ClientAction.TALK_CURSOR_RIGHT), "Right"),
            () -> assertTrue(KeyBindings.has(up, ClientAction.TALK_CURSOR_UP), "Up"),
            () -> assertTrue(KeyBindings.has(down, ClientAction.TALK_CURSOR_DOWN), "Down"),
            () -> assertSame(b, in.bindings(), "bindings"));
    }

    @Test
    @DisplayName("talk line: exact Latin-1 boundaries, mid-line delete, right, history edges")
    void talk() {
        TalkLine t = new TalkLine();
        t.open();
        boolean open = t.isOpen();
        int[] ok = {32, 126, 160, 255};
        int[] bad = {31, 127, 159, 256};
        StringBuilder accepted = new StringBuilder();
        for (int c : ok) {
            accepted.append(t.type(c) ? 'y' : 'n');
        }
        for (int c : bad) {
            accepted.append(t.type(c) ? 'y' : 'n');
        }
        t.home();
        t.right();
        t.delete();
        String afterDelete = t.text().toString();
        StringBuilder out = new StringBuilder();
        t.submit(out);
        t.open();
        t.historyUp();
        String up1 = t.text().toString();
        t.historyUp();
        String up2 = t.text().toString();
        TalkLine two = new TalkLine();
        two.open();
        two.type('a');
        two.submit(out);
        two.open();
        two.type('b');
        two.submit(out);
        two.open();
        two.historyUp();
        two.historyUp();
        two.historyDown();
        assertAll(
            () -> assertTrue(open, "open"),
            () -> assertEquals("yyyynnnn", accepted.toString(), "32, 126, 160, 255 accepted; 31, 127, 159, 256 not"),
            () -> assertEquals("~\u00a0\u00ff", afterDelete, "DEL erased the char before the cursor (C '\\177')"),
            () -> assertEquals(afterDelete, up1, "the only history line"),
            () -> assertEquals(afterDelete, up2, "up again skips the empty slots back to it"),
            () -> assertEquals("b", two.text().toString(), "down from the older line reaches the newest"));
    }
}
