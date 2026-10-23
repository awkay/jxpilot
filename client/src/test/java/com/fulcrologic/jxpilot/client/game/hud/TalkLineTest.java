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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TalkLineTest {
    static void type(TalkLine t, String s) {
        for (int i = 0; i < s.length(); i++) {
            t.type(s.charAt(i));
        }
    }

    @Test
    @DisplayName("editing: insert at the cursor, bounded movement, backspace and delete")
    void editing() {
        TalkLine t = new TalkLine();
        boolean closed = t.type('x');
        t.open();
        type(t, "helo");
        t.left();
        t.type('l');
        t.home();
        t.left();
        t.backspace();
        t.delete();
        String afterDelete = t.text().toString();
        t.end();
        t.right();
        t.delete();
        t.backspace();
        boolean control = t.type(7);
        boolean high = t.type(200);
        boolean c1 = t.type(150);
        boolean wide = t.type(0x263A);
        assertAll(
            () -> assertFalse(closed, "typing into a closed line is ignored"),
            () -> assertEquals("ello", afterDelete, "inserted l before o, then deleted the h at the start"),
            () -> assertEquals("ellÈ", t.text().toString(), "backspace at the end; Latin-1 200 typed"),
            () -> assertFalse(control, "control chars rejected"),
            () -> assertTrue(high, "Latin-1 letters accepted"),
            () -> assertFalse(c1, "C1 controls rejected"),
            () -> assertFalse(wide, "non-Latin-1 rejected"),
            () -> assertEquals(4, t.cursor(), "cursor at the end"));
    }

    @Test
    @DisplayName("at most 80 chars")
    void full() {
        TalkLine t = new TalkLine();
        t.open();
        type(t, "a".repeat(85));
        assertEquals(TalkLine.MAX_CHARS, t.text().length(), "capped at MAX_CHARS");
    }

    @Test
    @DisplayName("submit sends, records history and closes; history browses with wrap and skips empty slots")
    void submitAndHistory() {
        TalkLine t = new TalkLine();
        StringBuilder out = new StringBuilder("junk");
        t.open();
        boolean empty = t.submit(out);
        t.open();
        type(t, "one");
        t.submit(out);
        t.open();
        type(t, "two");
        boolean sent = t.submit(out);
        t.open();
        t.historyUp();
        String up1 = t.text().toString();
        t.historyUp();
        String up2 = t.text().toString();
        t.historyUp();
        String wrapped = t.text().toString();
        t.historyDown();
        String down = t.text().toString();
        t.cancel();
        boolean open = t.isOpen();
        t.open();
        int cursor = t.cursor();
        TalkLine none = new TalkLine();
        none.open();
        none.historyUp();
        assertAll(
            () -> assertFalse(empty, "an empty line is not sent"),
            () -> assertTrue(sent, "a line is sent"),
            () -> assertEquals("two", out.toString(), "the sent text"),
            () -> assertEquals("two", t.history(0).toString(), "newest history first"),
            () -> assertEquals("two", up1, "up: most recent"),
            () -> assertEquals("one", up2, "up again: older"),
            () -> assertEquals("two", wrapped, "past the oldest wraps to the newest, skipping empty slots"),
            () -> assertEquals("one", down, "down goes back the other way, wrapping"),
            () -> assertFalse(open, "cancel closes"),
            () -> assertEquals(3, cursor, "the cancelled text is kept with the cursor at its end"),
            () -> assertEquals(0, none.text().length(), "no history: nothing happens"));
    }
}
