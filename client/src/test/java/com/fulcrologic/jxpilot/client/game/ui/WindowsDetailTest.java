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
package com.fulcrologic.jxpilot.client.game.ui;

import com.fulcrologic.jxpilot.client.game.hud.HudColor;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.game.ui.WidgetsTest.M;
import static com.fulcrologic.jxpilot.client.game.ui.WidgetsTest.rects;
import static com.fulcrologic.jxpilot.client.game.ui.WidgetsTest.texts;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Windows in detail: ABOUT geometry and paging, CONFIG centring and boundaries")
class WindowsDetailTest {
    /** Button font taller than the text font (ascent 12, descent 4, 8 px a char). */
    static final TextMetrics TALL = new TextMetrics() {
        public int ascent() { return 12; }

        public int descent() { return 4; }

        public int width(CharSequence s, int len) { return 8 * len; }
    };

    @Nested
    @DisplayName("AboutWindow")
    class About {
        AboutWindow open() {
            AboutWindow a = new AboutWindow();
            a.layout(600, 704, M);
            a.open();
            return a;
        }

        @Test
        @DisplayName("items: boxes are contiguous from below the heading, each picture at C's place, page 1 continues")
        void items() {
            AboutWindow a = open();
            HudFrame f = new HudFrame(16, 64, 128, 160);
            a.draw(f, M, M);
            int head = a.wrap(new HudFrame(4, 4, 32, 160), M, 0, 0, AboutWindow.BORDER, AboutWindow.BORDER,
                AboutWindow.ITEMS_HEAD);
            List<String> r = rects(f);
            int split = a.itemSplit();
            int count = a.itemCount();
            int y0 = 2 + head + AboutWindow.BORDER;
            int firstPictureY = a.itemY(0);
            HudFrame g = new HudFrame(16, 64, 128, 160);
            a.press(2 + 282 + 1, 2 + 665 + 1);
            a.release();
            a.draw(g, M, M);
            List<String> r1 = rects(g);
            assertAll(
                () -> assertEquals("fill 0,0 604x704 c" + HudColor.WHITE, r.get(0), "frame"),
                () -> assertEquals("fill 12," + y0 + " 52x39 c" + HudColor.BLACK, r.get(2),
                    "first box at x BORDER, 2*16 + 2*10 wide, 5 + 5 + 24 + 5 tall for a one-line text"),
                () -> assertEquals(count, split + 1, "page 0 shows items 0..split"),
                () -> assertEquals(2 + 20 + 16, a.itemX(count - 1), "every picture at x 2 * BORDER + ITEM_SIZE"),
                () -> assertEquals(y0 + 10 + 12, firstPictureY, "the first picture at box top + 10 + ITEM_TRIANGLE_SIZE"),
                () -> assertEquals(split + 1, a.item(0), "page 1 starts after the split"),
                () -> assertEquals(AboutWindow.ITEM_TEXT.length - split - 1, a.itemCount(), "and shows the rest"),
                () -> assertTrue(contiguous(r1), "page 1 boxes touch: " + r1),
                () -> assertTrue(contiguous(r), "page 0 boxes touch"));
        }

        boolean contiguous(List<String> r) {
            int prevBottom = -1;
            for (String s : r) {
                if (!s.startsWith("fill 12,") || !s.endsWith("c" + HudColor.BLACK)) {
                    continue;
                }
                String[] p = s.split("[ ,x]");
                int y = Integer.parseInt(p[2]);
                int h = Integer.parseInt(p[4]);
                if (prevBottom >= 0 && y != prevBottom) {
                    return false;
                }
                prevBottom = y + h;
            }
            return prevBottom > 0;
        }

        @Test
        @DisplayName("buttons: C's places and sizes, red (black while held), shadowed labels; presses elsewhere hold nothing")
        void buttons() {
            AboutWindow a = open();
            HudFrame f = new HudFrame(16, 64, 128, 160);
            a.draw(f, M, M);
            List<String> r = rects(f);
            List<String> t = texts(f);
            boolean onText = a.press(300, 300);
            boolean holdsNone = !a.holding();
            a.release();
            int page = a.page();
            a.press(2 + 554 + 1, 2 + 665 + 1);
            boolean holding = a.holding();
            HudFrame g = new HudFrame(16, 64, 128, 160);
            a.draw(g, M, M);
            String pressedPrev = rects(g).get(rects(g).size() - 1);
            assertAll(
                () -> assertEquals("fill 12,667 43x21 c" + HudColor.BUTTON, r.get(r.size() - 3),
                    "CLOSE at (BORDER, 700 - 10 - 21 - 4), 8 + 35 wide"),
                () -> assertEquals("fill 284,667 36x21 c" + HudColor.BUTTON, r.get(r.size() - 2),
                    "NEXT at 300 - 4 - 14"),
                () -> assertEquals("fill 556,667 36x21 c" + HudColor.BUTTON, r.get(r.size() - 1),
                    "PREV at 600 - 10 - 8 - 28"),
                () -> assertTrue(t.contains("16,681 CLOSE c" + HudColor.SCORE), "label at (BTN_BORDER, ascent + 4)"),
                () -> assertTrue(onText, "a press on the window is taken"),
                () -> assertTrue(holdsNone, "but holds no button"),
                () -> assertEquals(0, page, "and its release does nothing"),
                () -> assertTrue(holding, "PREV held"),
                () -> assertEquals("fill 556,667 36x21 c" + HudColor.BLACK, pressedPrev, "held: black"),
                () -> assertFalse(open().holding(), "nothing held at first"));
        }

        @Test
        @DisplayName("paging with a split: NEXT 0 1 2 3 0, PREV 0 3 2 1 0; centred in a larger view")
        void paging() {
            AboutWindow a = open();
            a.draw(new HudFrame(16, 64, 128, 160), M, M);
            List<Integer> next = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                a.press(2 + 282 + 1, 2 + 665 + 1);
                a.release();
                next.add(a.page());
            }
            List<Integer> prev = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                a.press(2 + 554 + 1, 2 + 665 + 1);
                a.release();
                prev.add(a.page());
            }
            AboutWindow c = new AboutWindow();
            c.layout(1000, 900, M);
            c.open();
            HudFrame f = new HudFrame(16, 64, 128, 160);
            c.draw(f, M, M);
            assertAll(
                () -> assertEquals(List.of(1, 2, 3, 0), next, "NEXT through every page"),
                () -> assertEquals(List.of(3, 2, 1, 0), prev, "PREV back"),
                () -> assertEquals("fill 198,98 604x704 c" + HudColor.WHITE, rects(f).get(0),
                    "centred: (1000 - 604) / 2, (900 - 704) / 2"),
                () -> assertTrue(c.covers(198, 98), "covers its corner"),
                () -> assertFalse(c.covers(197, 98), "not left of it"),
                () -> assertFalse(c.covers(198 + 604, 98), "not right of it"));
        }

        @Test
        @DisplayName("wrap: a word exactly at the limit stays; one pixel over breaks; tabs are blanks")
        void wrapEdge() {
            AboutWindow a = new AboutWindow();
            // 590 - 10 = 580 px for the line: 82 chars of 7 px = 574 fit, 83 = 581 do not
            HudFrame fit = new HudFrame(4, 4, 8, 160);
            a.wrap(fit, M, 0, 0, 10, 0, "a".repeat(82) + " b");
            HudFrame over = new HudFrame(4, 4, 8, 160);
            a.wrap(over, M, 0, 0, 10, 0, "a".repeat(83));
            HudFrame tab = new HudFrame(4, 4, 8, 160);
            a.wrap(tab, M, 0, 0, 10, 0, "x\ty");
            assertAll(
                () -> assertEquals(2, fit.textCount(), "the 82-char word fits; ' b' wraps"),
                () -> assertEquals(82, fit.text(0).length(), "first line"),
                () -> assertEquals(1, over.textCount(), "an 83-char word breaks before itself: one run"),
                () -> assertEquals(M.ascent() + 14, (int) over.textY(0), "one line lower than the first baseline"),
                () -> assertEquals("x\ty", tab.text(0).toString(), "a tab is a blank between words"));
        }
    }

    @Nested
    @DisplayName("ConfigPanel")
    class Config {
        final PanelsTest.Box a = new PanelsTest.Box(1);
        final PanelsTest.Box b = new PanelsTest.Box(5);

        @Test
        @DisplayName("text and button fonts of different heights: rows centre each widget in the entry height")
        void centring() {
            ConfigPanel p = new ConfigPanel(List.of(ConfigEntry.bool("on", a),
                new ConfigEntry("n", ConfigEntry.Kind.INT, 0, 10, b)));
            p.layout(0, 400, M, TALL);
            List<String> g = PanelsTest.page(p, 0);
            assertAll(
                () -> assertEquals("PREV 212,376 38x18", g.get(0), "button height 2 + 16; PREV 6 + 32 wide, 6 from the edge"),
                () -> assertEquals("LABEL 6,7 20x15", g.get(3), "label centred: (18 - 15) / 2 = 1 down"),
                () -> assertEquals("BOOL 220,6 30x18", g.get(4), "Yes/No: width('Yes') 24 + 6, button height"),
                () -> assertEquals("LABEL 6,31 13x15", g.get(5), "next row 18 + 6 lower"),
                () -> assertEquals("INPUT 184,31 36x15", g.get(6), "int field 4 + width('1000') 32"),
                () -> assertEquals("LEFT 220,31 15x15", g.get(7), "arrow: text-height square"),
                () -> assertEquals("RIGHT 235,31 15x15", g.get(8), "then the right arrow"));
        }

        @Test
        @DisplayName("page boundaries: a row ending exactly at the limit still breaks (C >=); PREV from page 1 goes to 0")
        void boundaries() {
            List<ConfigEntry> five = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                five.add(ConfigEntry.bool("e" + i, a));
            }
            ConfigPanel p = new ConfigPanel(five);
            // rows at 6, 27, 48: the third needs 48 + 30 + 12 = 90 < limit; formH 90 refuses it
            p.layout(0, 90, M, M);
            int perPage = (int) PanelsTest.page(p, 0).stream().filter(s -> s.startsWith("BOOL")).count();
            p.open();
            int pages = p.pageCount();
            p.press(128, 90 - 6 - 7);
            p.release(128, 90 - 6 - 7);
            p.press(230, 90 - 6 - 7);
            p.release(230, 90 - 6 - 7);
            int back = p.page();
            ConfigPanel q = new ConfigPanel(five.subList(0, 3));
            q.layout(0, 91, M, M);
            assertAll(
                () -> assertEquals(2, perPage, "two rows per page at formH 90"),
                () -> assertTrue(pages >= 3, "several pages: " + pages),
                () -> assertEquals(0, back, "NEXT then PREV is page 0 again"),
                () -> assertEquals(3, PanelsTest.page(q, 0).stream().filter(s -> s.startsWith("BOOL")).count(),
                    "at formH 91 the third row fits"));
        }

        @Test
        @DisplayName("a label exactly fitting stays on one line; one char more takes two")
        void labelFit() {
            // offset = 256 - (6 + 27) = 223; one line iff 6 + width + 6 <= 223: 30 chars (210) fit, 31 (217) do not
            ConfigPanel p = new ConfigPanel(List.of(ConfigEntry.bool("x".repeat(30), a),
                ConfigEntry.bool("y".repeat(31), b)));
            p.layout(0, 400, M, M);
            List<String> g = PanelsTest.page(p, 0);
            assertAll(
                () -> assertEquals("BOOL 223,6 27x15", g.get(4), "30 chars: the button on the label's line"),
                () -> assertEquals("LABEL 6,27 223x15", g.get(5), "31 chars: label line"),
                () -> assertEquals("BOOL 223,42 27x15", g.get(6), "and the button one entry lower"));
        }

        @Test
        @DisplayName("drawing: arrows and every button kind; a release elsewhere without a move does nothing")
        void drawAndRelease() {
            ConfigPanel p = new ConfigPanel(List.of(new ConfigEntry("n", ConfigEntry.Kind.INT, 0, 10, b)));
            p.layout(0, 300, M, M);
            p.open();
            HudFrame f = new HudFrame(16, 128, 32, 64);
            p.draw(f, M, M);
            List<String> t = texts(f);
            p.press(240, 10);
            UiAction off = p.release(10, 200);
            assertAll(
                () -> assertTrue(t.contains("9,290 CLOSE c" + HudColor.SCORE), "CLOSE label"),
                () -> assertTrue(t.contains("114,290 NEXT c" + HudColor.SCORE), "NEXT label"),
                () -> assertTrue(t.contains("219,290 PREV c" + HudColor.SCORE), "PREV label"),
                () -> assertTrue(rects(f).contains("fill 220,6 15x15 c" + HudColor.BLACK), "left arrow square"),
                () -> assertTrue(rects(f).contains("fill 235,6 15x15 c" + HudColor.BLACK), "right arrow square"),
                () -> assertEquals(UiAction.NONE, off, "released off the arrow"),
                () -> assertEquals(5.0, b.v, "unchanged"));
        }
    }
}
