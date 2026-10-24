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

import com.fulcrologic.jxpilot.client.game.MessageLog;
import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Cut and paste (C selectionAndHistory)")
class SelectionTest {
    static final HudTest.Mono M = new HudTest.Mono(7);

    static MessageLog log() {
        MessageLog l = new MessageLog();
        l.add(Msg.Text.KIND_SERVER, "game one");
        l.add(Msg.Text.KIND_SERVER, "game two");
        l.add(Msg.Text.KIND_CHAT, "talk old [a]");
        l.add(Msg.Text.KIND_CHAT, "talk new [b]");
        return l;
    }

    @Nested
    @DisplayName("TextSelection")
    class Ranges {
        @Test
        @DisplayName("a single line selects between the two chars in either drag direction")
        void single() {
            TextSelection s = new TextSelection();
            s.start(TextSelection.TALK_LINE, 0, 4);
            boolean emptyAtStart = s.selects(TextSelection.TALK_LINE);
            s.drag(TextSelection.TALK_LINE, 0, 1);
            s.drag(TextSelection.TALK_MESSAGES, 3, 9);
            assertAll(
                () -> assertFalse(emptyAtStart, "nothing selected before dragging"),
                () -> assertEquals(1, s.from(TextSelection.TALK_LINE, 0, 10), "from the left char"),
                () -> assertEquals(4, s.to(TextSelection.TALK_LINE, 0, 10), "to the right char"),
                () -> assertEquals(0, s.from(TextSelection.TALK_MESSAGES, 0, 10), "another area: nothing"),
                () -> assertEquals(0, s.to(TextSelection.TALK_MESSAGES, 0, 10), "another area: nothing"),
                () -> assertEquals(3, s.to(TextSelection.TALK_LINE, 0, 3), "clamped to the line length"),
                () -> assertTrue(s.dragging(), "still dragging"));
        }

        @Test
        @DisplayName("game message lines run bottom-up: the screen's top line is the higher index; text joins lines")
        void multi() {
            MessageLog l = log();
            TextSelection s = new TextSelection();
            s.start(TextSelection.GAME_MESSAGES, 0, 4);
            s.drag(TextSelection.GAME_MESSAGES, 1, 5);
            s.finish();
            String text = s.text(new TalkLine(), l);
            TextSelection t = new TextSelection();
            t.start(TextSelection.TALK_MESSAGES, 0, 5);
            t.drag(TextSelection.TALK_MESSAGES, 1, 4);
            String talk = t.text(new TalkLine(), l);
            assertAll(
                () -> assertEquals(1, s.topLine(), "line 1 (older) is on top"),
                () -> assertEquals(0, s.bottomLine(), "line 0 at the bottom"),
                () -> assertEquals(5, s.from(TextSelection.GAME_MESSAGES, 1, 8), "top line from its char"),
                () -> assertEquals(8, s.to(TextSelection.GAME_MESSAGES, 1, 8), "to its end"),
                () -> assertEquals(0, s.from(TextSelection.GAME_MESSAGES, 0, 8), "bottom line from its start"),
                () -> assertEquals(4, s.to(TextSelection.GAME_MESSAGES, 0, 8), "to its char"),
                () -> assertEquals(0, s.to(TextSelection.GAME_MESSAGES, 2, 8), "lines outside: nothing"),
                () -> assertEquals("one\ngame", text, "'one' from line 1, 'game' from line 0"),
                () -> assertEquals("new [b]\ntalk", talk, "talk list top-down"),
                () -> assertFalse(s.dragging(), "finished"));
        }

        @Test
        @DisplayName("the talk line's text; clear forgets; lines past the list are skipped")
        void talkAndClear() {
            TalkLine line = new TalkLine();
            line.open();
            line.paste("hello");
            TextSelection s = new TextSelection();
            s.start(TextSelection.TALK_LINE, 0, 1);
            s.drag(TextSelection.TALK_LINE, 0, 3);
            String sel = s.text(line, log());
            s.clear();
            TextSelection far = new TextSelection();
            far.start(TextSelection.TALK_MESSAGES, 5, 0);
            far.drag(TextSelection.TALK_MESSAGES, 1, 2);
            assertAll(
                () -> assertEquals("el", sel, "chars 1..3"),
                () -> assertEquals(-1, s.area(), "cleared"),
                () -> assertEquals("", s.text(line, log()), "nothing after clear"),
                () -> assertEquals("lk old [a]", far.text(line, log()), "only existing lines: line 1 from char 2; lines 2..5 do not exist"));
        }
    }

    @Nested
    @DisplayName("TalkLine paste and cursor")
    class Paste {
        @Test
        @DisplayName("paste inserts at the cursor, blanks for newlines and tabs, drops other control chars, stops when full")
        void paste() {
            TalkLine t = new TalkLine();
            int closed = t.paste("x");
            t.open();
            t.paste("ad");
            t.setCursor(1);
            int n = t.paste("b\tc\u0001");
            String after = t.text().toString();
            t.setCursor(-5);
            int lo = t.cursor();
            t.setCursor(99);
            int hi = t.cursor();
            TalkLine full = new TalkLine();
            full.open();
            int got = full.paste("y".repeat(100));
            assertAll(
                () -> assertEquals(0, closed, "nothing while closed"),
                () -> assertEquals("ab cd", after, "inserted at the cursor, the tab a blank, ^A dropped"),
                () -> assertEquals(3, n, "three chars went in"),
                () -> assertEquals(0, lo, "cursor clamped at 0"),
                () -> assertEquals(5, hi, "cursor clamped at the length"),
                () -> assertEquals(TalkLine.MAX_LEN, got, "a long paste stops at the line limit"));
        }
    }

    @Nested
    @DisplayName("Hud hit tests")
    class Hits {
        @Test
        @DisplayName("charAt splits at the middle of each char")
        void charAt() {
            assertAll(
                () -> assertEquals(0, Hud.charAt("abc", -3, M), "left of the text"),
                () -> assertEquals(0, Hud.charAt("abc", 3, M), "before the middle of a"),
                () -> assertEquals(1, Hud.charAt("abc", 4, M), "past the middle of a"),
                () -> assertEquals(3, Hud.charAt("abc", 99, M), "past the end"));
        }

        @Test
        @DisplayName("messageAt finds talk messages from the top and game messages from the bottom (only when shown)")
        void messages() {
            Hud h = new Hud();
            MessageLog l = log();
            int[] out = new int[3];
            boolean top = h.messageAt(10 + 14, 12, l, 600, M, out);
            String topHit = out[0] + "/" + out[1] + "/" + out[2];
            boolean second = h.messageAt(10, 10 + 14 + 1, l, 600, M, out);
            String secondHit = out[0] + "/" + out[1];
            boolean bottom = h.messageAt(10, 600 - 3 - 10 - 5, l, 600, M, out);
            String bottomHit = out[0] + "/" + out[1];
            boolean between = h.messageAt(10, 300, l, 600, M, out);
            h.showMessages = false;
            boolean hidden = h.messageAt(10, 600 - 3 - 10 - 5, l, 600, M, out);
            assertAll(
                () -> assertTrue(top, "the first talk row"),
                () -> assertEquals("1/0/2", topHit, "talk list, newest, char 2"),
                () -> assertTrue(second, "the second row"),
                () -> assertEquals("1/1", secondHit, "talk list, older"),
                () -> assertTrue(bottom, "the bottom row"),
                () -> assertEquals("2/0", bottomHit, "game list, newest"),
                () -> assertFalse(between, "no message mid-screen"),
                () -> assertFalse(hidden, "game messages hidden: no hit"));
        }

        @Test
        @DisplayName("talkCharAt: the char under the pointer inside the talk window, -1 outside")
        void talk() {
            Hud h = new Hud();
            TalkLine t = new TalkLine();
            t.open();
            t.paste("hey");
            assertAll(
                () -> assertEquals(2, h.talkCharAt(53 + 15, 450, t, 800, 600, M, M), "between e and y"),
                () -> assertEquals(-1, h.talkCharAt(53, 400, t, 800, 600, M, M), "above"),
                () -> assertEquals(-1, h.talkCharAt(53, 470, t, 800, 600, M, M), "below"),
                () -> assertEquals(-1, h.talkCharAt(49, 450, t, 800, 600, M, M), "left of the inside"),
                () -> assertEquals(-1, h.talkCharAt(750, 450, t, 800, 600, M, M), "right of the inside"),
                () -> assertEquals(3, h.talkCharAt(700, 450, t, 800, 600, M, M), "past the text: its end"));
        }
    }
}
