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

import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Lobby overlay panel")
class LobbyPanelTest {
    /** 7 px per char, ascent 10, descent 3: a text line is 15 px. */
    record Mono() implements TextMetrics {
        public int ascent() { return 10; }

        public int descent() { return 3; }

        public int width(CharSequence s, int len) { return 7 * len; }
    }

    static final Mono M = new Mono();

    static LobbyEntry e(int id, String name, boolean active, int humans) {
        return new LobbyEntry(id, name, "Title " + name, "by x", 40, 40, 10, active, humans);
    }

    static List<LobbyEntry> maps(int n) {
        List<LobbyEntry> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(e(i, String.format("m%03d", i), false, 0));
        }
        return l;
    }

    static int find(HudFrame h, String text) {
        for (int i = 0; i < h.textCount(); i++) {
            if (h.text(i).toString().equals(text)) {
                return i;
            }
        }
        return -1;
    }

    static LobbyPanel panel(List<LobbyEntry> rows) {
        LobbyPanel p = new LobbyPanel();
        p.update("friends", rows, "");
        return p;
    }

    @Nested
    @DisplayName("update")
    class Update {
        @Test
        @DisplayName("the first list puts the cursor on its first row")
        void firstList() {
            LobbyPanel p = panel(maps(5));
            assertAll(
                () -> assertEquals(5, p.rows(), "rows"),
                () -> assertEquals(0, p.cursor(), "cursor on row 0"),
                () -> assertEquals("m000", p.current().name(), "the first map is current"));
        }

        @Test
        @DisplayName("a replaced list keeps the cursor on the same map even when it moves to another row")
        void followsMap() {
            LobbyPanel p = panel(List.of(e(1, "a", false, 0), e(2, "b", false, 0), e(3, "c", false, 0)));
            p.move(2); // on c
            p.update("friends", List.of(e(3, "c", true, 1), e(1, "a", false, 0), e(2, "b", false, 0)), "");
            assertAll(
                () -> assertEquals(0, p.cursor(), "c is now row 0 and the cursor went with it"),
                () -> assertEquals(3, p.current().id(), "still map 3"));
        }

        @Test
        @DisplayName("the same list object changes nothing, not even the cursor")
        void sameList() {
            List<LobbyEntry> l = maps(4);
            LobbyPanel p = panel(l);
            p.move(3);
            p.update("friends", l, "hello");
            assertEquals(3, p.cursor(), "cursor stays");
        }

        @Test
        @DisplayName("a list without the cursor's map clamps the row number into the new list")
        void vanished() {
            LobbyPanel p = panel(maps(10));
            p.move(8);
            p.update("friends", List.of(e(100, "x", false, 0), e(101, "y", false, 0)), "");
            assertEquals(1, p.cursor(), "row 8 clamped to the last row");
        }

        @Test
        @DisplayName("an empty list has no current map and ignores movement")
        void empty() {
            LobbyPanel p = panel(List.of());
            p.move(1);
            p.end();
            p.page(1);
            assertAll(
                () -> assertNull(p.current(), "nothing to choose"),
                () -> assertEquals(0, p.cursor(), "cursor at 0"),
                () -> assertNull(p.choose('0'), "no shortcut works"));
        }
    }

    @Nested
    @DisplayName("moving")
    class Moving {
        @Test
        @DisplayName("move stops at the first and last row; there is no wrap-around")
        void clamps() {
            LobbyPanel p = panel(maps(3));
            p.move(-1);
            int atTop = p.cursor();
            p.move(1);
            p.move(1);
            p.move(1);
            assertAll(
                () -> assertEquals(0, atTop, "Up on the first row stays"),
                () -> assertEquals(2, p.cursor(), "Down past the last row stays on it"));
        }

        @Test
        @DisplayName("home and end go to the first and last row")
        void homeEnd() {
            LobbyPanel p = panel(maps(30));
            p.end();
            int last = p.cursor();
            p.home();
            assertAll(
                () -> assertEquals(29, last, "end"),
                () -> assertEquals(0, p.cursor(), "home"));
        }

        @Test
        @DisplayName("page moves one row less than the rows that fit (at least one), after a layout told it the page size")
        void paging() {
            LobbyPanel p = panel(maps(100));
            p.page(1);
            int beforeLayout = p.cursor();
            p.layout(new HudFrame(4, 4, 320, 160), 800, 400, M); // fit = (400 - 20 - 65 - 25) / 15 = 19
            p.page(1);
            int after = p.cursor();
            p.page(-1);
            assertAll(
                () -> assertEquals(9, beforeLayout, "the initial page size is 10 rows"),
                () -> assertEquals(19, p.pageRows(), "19 rows fit"),
                () -> assertEquals(9 + 18, after, "then pages of 18"),
                () -> assertEquals(9, p.cursor(), "and back up"));
        }
    }

    @Nested
    @DisplayName("shortcut keys")
    class Shortcuts {
        @Test
        @DisplayName("a shortcut character moves the cursor to its row and returns that map")
        void choose() {
            LobbyPanel p = panel(maps(40));
            LobbyEntry zero = p.choose('0');
            LobbyEntry a = p.choose('a');
            LobbyEntry cap = p.choose('B');
            assertAll(
                () -> assertEquals("m000", zero.name(), "'0' is row 0"),
                () -> assertEquals("m010", a.name(), "'a' is row 10"),
                () -> assertEquals("m037", cap.name(), "'B' is row 37"),
                () -> assertEquals(37, p.cursor(), "the cursor went there"));
        }

        @Test
        @DisplayName("a shortcut past the end of the list, and any other character, return null and leave the cursor")
        void ignored() {
            LobbyPanel p = panel(maps(5));
            p.move(2);
            assertAll(
                () -> assertNull(p.choose('9'), "row 9 does not exist in a 5-row list"),
                () -> assertNull(p.choose(' '), "space is not a shortcut"),
                () -> assertNull(p.choose('?'), "'?' is not a shortcut"),
                () -> assertEquals(2, p.cursor(), "the cursor did not move"));
        }

        @Test
        @DisplayName("rows past the 62nd have no shortcut and can only be reached by scrolling")
        void beyondSixtyTwo() {
            LobbyPanel p = panel(maps(100));
            HudFrame out = new HudFrame(4, 4, 320, 160);
            p.layout(out, 800, 2000, M); // all 100 rows fit
            long keys = 0;
            for (int i = 0; i < out.textCount(); i++) {
                String t = out.text(i).toString();
                if (t.length() == 3 && t.charAt(0) == '[' && t.charAt(2) == ']') {
                    keys++;
                }
            }
            long keyCount = keys;
            p.end();
            assertAll(
                () -> assertEquals(62, keyCount, "exactly 62 rows show a [key]"),
                () -> assertTrue(find(out, "[Z]") >= 0, "the 62nd row is [Z]"),
                () -> assertEquals(99, p.cursor(), "the last row is reachable with End"),
                () -> assertEquals("m099", p.current().name(), "and is choosable with Enter (current)"));
        }
    }

    @Nested
    @DisplayName("layout")
    class Layout {
        @Test
        @DisplayName("a black panel with a white outline, the heading, instructions, column heads, one line per map")
        void structure() {
            LobbyPanel p = new LobbyPanel();
            p.update("friends", List.of(e(0, "alpha", true, 3), e(1, "beta", false, 0)), "");
            HudFrame out = new HudFrame(4, 4, 320, 160);
            p.layout(out, 1000, 600, M);
            int heading = find(out, "friends: choose a map");
            assertAll(
                () -> assertEquals(3, out.rectCount(), "panel, outline and the cursor bar"),
                () -> assertEquals(HudColor.BLACK, out.rectColor(0), "black panel"),
                () -> assertTrue(out.rectFilled(0), "filled"),
                () -> assertEquals(HudColor.WHITE, out.rectColor(1), "white outline"),
                () -> assertFalse(out.rectFilled(1), "not filled"),
                () -> assertEquals(10f, out.rectX(0), "at the margin"),
                () -> assertEquals(10f, out.rectY(0), "at the margin"),
                () -> assertTrue(heading >= 0, "heading names the lobby"),
                () -> assertEquals(20f, out.textX(heading), "heading at twice the margin"),
                () -> assertEquals(10f + 5 + 10, out.textY(heading), "heading baseline: margin + margin/2 + ascent"),
                () -> assertTrue(find(out, "Up/Down and Enter choose, or press the key in [ ]. Esc quits.") >= 0,
                    "instructions"),
                () -> assertTrue(find(out, "Name") >= 0 && find(out, "Humans") >= 0 && find(out, "Map") >= 0,
                    "column heads"),
                () -> assertTrue(find(out, "alpha") >= 0 && find(out, "beta") >= 0, "both names"),
                () -> assertTrue(find(out, "Title alpha") >= 0, "titles"));
        }

        @Test
        @DisplayName("rows: key in the HUD colour, '*' only for active maps, humans only for active maps, idle rows dim")
        void rows() {
            LobbyPanel p = new LobbyPanel();
            p.update("l", List.of(e(0, "alpha", true, 3), e(1, "beta", false, 0), e(2, "gamma", false, 0)), "");
            p.move(2); // cursor on gamma: alpha and beta show their normal colours
            HudFrame out = new HudFrame(4, 4, 320, 160);
            p.layout(out, 1000, 600, M);
            int alpha = find(out, "alpha");
            int beta = find(out, "beta");
            int gamma = find(out, "gamma");
            int k0 = find(out, "[0]");
            int k1 = find(out, "[1]");
            int three = find(out, "3");
            int stars = 0;
            for (int i = 0; i < out.textCount(); i++) {
                if (out.text(i).toString().equals("*")) {
                    stars++;
                }
            }
            int starCount = stars;
            assertAll(
                () -> assertEquals(HudColor.HUD, out.textColor(k0), "key of a row off the cursor in the HUD colour"),
                () -> assertEquals(HudColor.HUD, out.textColor(k1), "same for row 1"),
                () -> assertEquals(1, starCount, "one active map, one star"),
                () -> assertEquals(HudColor.WHITE, out.textColor(alpha), "active name white"),
                () -> assertEquals(HudColor.OLD_MESSAGE, out.textColor(beta), "idle name dim"),
                () -> assertEquals(HudColor.WHITE, out.textColor(gamma), "the cursor row is white"),
                () -> assertTrue(three >= 0, "alpha shows its 3 humans"),
                () -> assertEquals(out.textY(alpha), out.textY(three), "on alpha's line"),
                () -> assertTrue(out.textX(three) > out.textX(alpha), "right of the name"),
                () -> assertEquals(-1, find(out, "0"), "idle maps and 0 humans print no count"));
        }

        @Test
        @DisplayName("the cursor row has a blue bar behind it at its own line; the bar follows the cursor")
        void cursorBar() {
            LobbyPanel p = panel(maps(5));
            HudFrame out = new HudFrame(4, 4, 320, 160);
            p.layout(out, 1000, 600, M);
            float y0 = out.rectY(2);
            p.move(2);
            p.layout(out, 1000, 600, M);
            float y2 = out.rectY(2);
            assertAll(
                () -> assertEquals(HudColor.EMPHASIZED, out.rectColor(2), "blue bar"),
                () -> assertTrue(out.rectFilled(2), "filled"),
                () -> assertEquals(65f - 1, y0, "row 0 starts at head 65; the bar begins one pixel above it"),
                () -> assertEquals(y0 + 2 * 15, y2, "two lines lower after moving two rows"));
        }

        @Test
        @DisplayName("more rows than fit: only a page is drawn, scrolled so the cursor shows, with the position at the bottom")
        void scrolling() {
            LobbyPanel p = panel(maps(100));
            HudFrame out = new HudFrame(4, 4, 320, 160);
            p.layout(out, 800, 400, M); // 19 rows fit
            boolean firstShown = find(out, "m000") >= 0;
            boolean lastOfPageShown = find(out, "m018") >= 0;
            boolean afterPage = find(out, "m019") >= 0;
            p.end();
            p.layout(out, 800, 400, M);
            assertAll(
                () -> assertTrue(firstShown, "starts at row 0"),
                () -> assertTrue(lastOfPageShown, "shows 19 rows"),
                () -> assertFalse(afterPage, "and no more"),
                () -> assertEquals(81, p.top(), "End scrolls to rows 81..99"),
                () -> assertTrue(find(out, "m099") >= 0, "the last map is shown"),
                () -> assertEquals(-1, find(out, "m000"), "the first is not"),
                () -> assertTrue(find(out, "Maps 82-100 of 100 (PageUp, PageDown)") >= 0, "position line"));
        }

        @Test
        @DisplayName("a message replaces the position line; an empty list says it is waiting")
        void footer() {
            LobbyPanel p = new LobbyPanel();
            p.update("l", List.of(), "");
            HudFrame out = new HudFrame(4, 4, 320, 160);
            p.layout(out, 800, 400, M);
            boolean waiting = find(out, "Waiting for the list of maps...") >= 0;
            p.update("l", maps(100), "Starting m000...");
            p.layout(out, 800, 400, M);
            assertAll(
                () -> assertTrue(waiting, "waiting text for no list"),
                () -> assertTrue(find(out, "Starting m000...") >= 0, "the message shows"),
                () -> assertEquals(-1, find(out, "Maps 1-19 of 100 (PageUp, PageDown)"), "instead of the position"));
        }

        @Test
        @DisplayName("a title too wide for the panel is cut to fit; the view width limits the panel")
        void clipping() {
            LobbyPanel p = new LobbyPanel();
            p.update("l", List.of(new LobbyEntry(0, "a", "T".repeat(300), "x", 1, 1, 1, false, 0)), "");
            HudFrame out = new HudFrame(4, 4, 320, 400);
            p.layout(out, 400, 400, M); // max panel width = 400 - 40 = 360
            int title = -1;
            for (int i = 0; i < out.textCount(); i++) {
                if (out.text(i).toString().startsWith("TTT")) {
                    title = i;
                }
            }
            int t = title;
            assertAll(
                () -> assertTrue(t >= 0, "a title run exists"),
                () -> assertTrue(out.text(t).length() < 300, "it was cut"),
                () -> assertTrue(out.textX(t) + 7 * out.text(t).length() <= 10 + 10 + 360 + 1, "within the panel"),
                () -> assertEquals(360f + 20, out.rectW(0), "the panel is as wide as the view allows"));
        }

        @Test
        @DisplayName("every text is in the key-list font; layout clears what was there")
        void fontAndClear() {
            LobbyPanel p = panel(maps(3));
            HudFrame out = new HudFrame(4, 4, 320, 160);
            p.layout(out, 800, 400, M);
            int first = out.textCount();
            p.layout(out, 800, 400, M);
            boolean allKeyList = true;
            for (int i = 0; i < out.textCount(); i++) {
                allKeyList &= out.textRole(i) == FontRole.KEY_LIST;
            }
            boolean fonts = allKeyList;
            assertAll(
                () -> assertTrue(fonts, "KEY_LIST for every run"),
                () -> assertEquals(first, out.textCount(), "a second layout starts from empty"));
        }

        @Test
        @DisplayName("laying out allocates nothing once the list is set")
        void noAllocation() {
            LobbyPanel p = panel(maps(100));
            HudFrame out = new HudFrame(4, 4, 320, 160);
            for (int i = 0; i < 500; i++) {
                p.layout(out, 800, 400, M);
            }
            com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean();
            long id = Thread.currentThread().threadId();
            p.update("friends", p.current() == null ? List.of() : maps(100), "x");
            long before = mx.getThreadAllocatedBytes(id);
            for (int i = 0; i < 2000; i++) {
                p.layout(out, 800, 400, M);
            }
            long used = mx.getThreadAllocatedBytes(id) - before;
            assertEquals(0, used, "layout allocates nothing (measured " + used + " bytes)");
        }
    }

    @Test
    @DisplayName("heading is 'Lobby' without a server name")
    void defaultHeading() {
        LobbyPanel p = new LobbyPanel();
        p.update("", maps(2), "");
        HudFrame out = new HudFrame(4, 4, 320, 160);
        p.layout(out, 800, 400, M);
        assertAll(
            () -> assertTrue(find(out, "Lobby: choose a map") >= 0, "default heading"),
            () -> assertSame(p.current(), p.current(), "current is stable"));
    }
}
