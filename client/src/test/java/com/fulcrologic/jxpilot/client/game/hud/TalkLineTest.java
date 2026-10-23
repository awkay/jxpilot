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

    static TalkLine with(String s, int cursor) {
        TalkLine t = new TalkLine();
        t.open();
        type(t, s);
        t.home();
        for (int i = 0; i < cursor; i++) {
            t.right();
        }
        return t;
    }

    @Test
    @DisplayName("editing: insert at the cursor, bounded movement; Backspace and DEL both erase before the cursor")
    void editing() {
        TalkLine t = new TalkLine();
        boolean closed = t.type('x');
        t.open();
        type(t, "helo");
        t.left();
        t.type('l');
        t.home();
        t.left();
        int leftAtZero = t.cursor();
        t.backspace();
        String atStart = t.text().toString();
        t.end();
        t.right();
        t.delete();
        String afterDel = t.text().toString();
        t.backspace();
        boolean control = t.type(7);
        boolean high = t.type(200);
        boolean c1 = t.type(150);
        boolean wide = t.type(0x263A);
        assertAll(
            () -> assertFalse(closed, "typing into a closed line is ignored"),
            () -> assertEquals("hello", atStart, "inserted l before o; backspace at the start does nothing"),
            () -> assertEquals(0, leftAtZero, "left at 0 stays at 0"),
            () -> assertEquals("hell", afterDel, "DEL erases the char before the cursor, like Backspace in C"),
            () -> assertEquals("helÈ", t.text().toString(), "Latin-1 200 typed"),
            () -> assertFalse(control, "control chars rejected"),
            () -> assertTrue(high, "Latin-1 letters accepted"),
            () -> assertFalse(c1, "C1 controls rejected"),
            () -> assertFalse(wide, "non-Latin-1 rejected"),
            () -> assertEquals(4, t.cursor(), "cursor at the end"));
    }

    @Test
    @DisplayName("at most MAX_CHARS - 2 = 78 chars; with a width limit no wider than the talk window allows")
    void full() {
        TalkLine t = new TalkLine();
        t.open();
        type(t, "a".repeat(78));
        boolean typedFull = t.typed();
        boolean refused = t.type('a');
        type(t, "a".repeat(6));
        TalkLine w = new TalkLine();
        w.limitWidth(HudTest.M, 100);
        w.open();
        type(w, "a".repeat(20));
        int at100 = w.text().length();
        TalkLine edge = new TalkLine();
        edge.limitWidth(HudTest.M, 2 * Hud.TALK_INSIDE_BORDER + 5 + 70);
        edge.open();
        type(edge, "a".repeat(20));
        assertAll(
            () -> assertEquals(78, t.text().length(), "capped at 78"),
            () -> assertFalse(refused, "the 79th is refused"),
            () -> assertTrue(typedFull, "the line counts as typed"),
            () -> assertEquals(TalkLine.MAX_LEN, t.text().length(), "MAX_LEN"),
            () -> assertEquals(13, at100, "7 px chars: 12 chars are 84 px < 100-11; the 13th is refused at 91 px"),
            () -> assertEquals(10, edge.text().length(), "a line exactly as wide as the limit takes no more"),
            () -> assertEquals(488, Hud.talkWindowWidth(588), "talk window width viewW - 2 * (48 + 2)"));
    }

    @Test
    @DisplayName("Ctrl-W/U/K erase like C; Ctrl-A/E/B/F move; other letters are not handled")
    void controlKeys() {
        TalkLine w = with("hello big  world  ", 3);
        w.control('w');
        TalkLine w2 = with("hello big", 9);
        w2.control('W');
        w2.control('W');
        w2.control('W');
        TalkLine u = with("hello", 2);
        u.control('U');
        TalkLine k = with("hello", 2);
        k.control('K');
        TalkLine m = with("hello", 2);
        m.control('E');
        int e = m.cursor();
        m.control('B');
        int b = m.cursor();
        m.control('A');
        int a = m.cursor();
        boolean handled = m.control('F');
        boolean other = m.control('Z');
        assertAll(
            () -> assertEquals("hello big  ", w.text().toString(), "W: trailing blanks and the last word, wherever the cursor is"),
            () -> assertEquals(3, w.cursor(), "the cursor stays when still inside"),
            () -> assertEquals("", w2.text().toString(), "W repeatedly erases word by word to empty"),
            () -> assertEquals(0, w2.cursor(), "cursor clamped to the new length"),
            () -> assertEquals("", u.text().toString(), "U erases the line"),
            () -> assertEquals(0, u.cursor(), "cursor at 0"),
            () -> assertEquals("he", k.text().toString(), "K erases from the cursor to the end"),
            () -> assertEquals(5, e, "E goes to the end"),
            () -> assertEquals(4, b, "B one back"),
            () -> assertEquals(0, a, "A to the start"),
            () -> assertEquals(1, m.cursor(), "F one forward"),
            () -> assertTrue(handled, "F is handled"),
            () -> assertFalse(other, "Z is not a talk control key"));
    }

    @Test
    @DisplayName("Ctrl-D: the char under the cursor; on or after the last char the one before; nothing at 0")
    void ctrlD() {
        TalkLine mid = with("abcd", 1);
        mid.control('d');
        TalkLine last = with("abcd", 3);
        last.control('d');
        TalkLine end = with("abcd", 4);
        end.control('d');
        TalkLine start = with("abcd", 0);
        start.control('d');
        assertAll(
            () -> assertEquals("acd", mid.text().toString(), "under the cursor"),
            () -> assertEquals(1, mid.cursor(), "cursor stays"),
            () -> assertEquals("abd", last.text().toString(), "on the last char C erases the one before (C quirk)"),
            () -> assertEquals(2, last.cursor(), "and moves back"),
            () -> assertEquals("abc", end.text().toString(), "at the end: the last char"),
            () -> assertEquals("abcd", start.text().toString(), "at position 0 nothing (C quirk)"));
    }

    @Test
    @DisplayName("Return sends; a line is saved in the history only if it was typed; Escape clears and closes")
    void submitAndEscape() {
        TalkLine t = new TalkLine();
        StringBuilder out = new StringBuilder("junk");
        t.open();
        boolean empty = t.submit(out);
        String untouched = out.toString();
        t.open();
        type(t, "one");
        t.submit(out);
        boolean openAfterSubmit = t.isOpen();
        boolean typedAfterSubmit = t.typed();
        t.open();
        t.historyUp();
        boolean recalled = t.submit(out);
        String resent = out.toString();
        t.open();
        type(t, "draft");
        t.escape();
        boolean openAfterEscape = t.isOpen();
        t.open();
        String afterEscape = t.text().toString();
        TalkLine k = new TalkLine();
        k.open();
        type(k, "keep");
        k.close();
        k.open();
        assertAll(
            () -> assertFalse(empty, "an empty line is not sent"),
            () -> assertFalse(openAfterSubmit, "Return closes the line"),
            () -> assertFalse(typedAfterSubmit, "and the line is no longer typed"),
            () -> assertEquals("junk", untouched, "nothing copied for an empty line"),
            () -> assertTrue(recalled, "a recalled line is sent"),
            () -> assertEquals("one", resent, "as it was"),
            () -> assertEquals("one", t.history(0).toString(), "typed line saved"),
            () -> assertEquals(0, t.history(1).length(), "the recalled line was not saved again"),
            () -> assertFalse(openAfterEscape, "Escape closes"),
            () -> assertEquals("", afterEscape, "Escape clears the text"),
            () -> assertEquals(0, t.history(1).length(), "the escaped draft is not saved"),
            () -> assertEquals("keep", k.text().toString(), "closing with the talk key keeps the text"),
            () -> assertEquals(4, k.cursor(), "cursor at its end when reopened"));
    }

    @Test
    @DisplayName("history browses with wrap and skips empty slots; Up first saves an unfinished typed line")
    void history() {
        TalkLine t = new TalkLine();
        StringBuilder out = new StringBuilder();
        for (String s : new String[] {"one", "two"}) {
            t.open();
            type(t, s);
            t.submit(out);
        }
        t.open();
        t.historyUp();
        String up1 = t.text().toString();
        t.historyUp();
        String up2 = t.text().toString();
        t.historyUp();
        String wrapped = t.text().toString();
        t.historyDown();
        String down = t.text().toString();
        t.close();
        t.open();
        t.end();
        type(t, "x");
        t.historyUp();
        String cleared = t.text().toString();
        String saved = t.history(0).toString();
        t.historyUp();
        String afterSave = t.text().toString();
        TalkLine none = new TalkLine();
        none.open();
        type(none, "a");
        none.submit(out);
        none.open();
        type(none, "q");
        none.backspace();
        none.historyUp();
        TalkLine empty = new TalkLine();
        empty.open();
        empty.historyDown();
        assertAll(
            () -> assertEquals("two", up1, "up: most recent"),
            () -> assertEquals("one", up2, "up again: older"),
            () -> assertEquals("two", wrapped, "past the oldest wraps to the newest, skipping empty slots"),
            () -> assertEquals("one", down, "down goes back the other way, wrapping"),
            () -> assertEquals("", cleared, "Up with a typed unfinished line clears it"),
            () -> assertEquals("onex", saved, "after saving it as the newest entry"),
            () -> assertEquals("two", afterSave, "the next Up continues after the saved entry (C history_pos = 0)"),
            () -> assertEquals("a", none.history(0).toString(), "a typed line erased to empty is not saved"),
            () -> assertEquals("", none.text().toString(), "and the line is cleared"),
            () -> assertEquals("", empty.text().toString(), "no history: the line is cleared"));
    }
}
