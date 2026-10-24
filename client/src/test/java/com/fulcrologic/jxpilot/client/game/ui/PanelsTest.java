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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.game.ui.WidgetsTest.M;
import static com.fulcrologic.jxpilot.client.game.ui.WidgetsTest.rects;
import static com.fulcrologic.jxpilot.client.game.ui.WidgetsTest.texts;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CONFIG panel and information window")
class PanelsTest {
    static final String LONG = "aVeryLongOptionNameForTwoLines";

    static final class Box implements ConfigEntry.Value {
        double v;

        Box(double v) {
            this.v = v;
        }

        @Override
        public double get() { return v; }

        @Override
        public void set(double x) { v = x; }
    }

    final Box hud = new Box(1);
    final Box max = new Box(8);
    final Box power = new Box(45);

    List<ConfigEntry> entries() {
        return List.of(ConfigEntry.bool("showHUD", hud),
            new ConfigEntry("maxMessages", ConfigEntry.Kind.INT, 1, 15, max),
            new ConfigEntry(LONG, ConfigEntry.Kind.FLOAT, 5, 55, power));
    }

    static String geo(ConfigPanel.Widget w) {
        return w.type + " " + w.x + "," + w.y + " " + w.w + "x" + w.h;
    }

    static List<String> page(ConfigPanel p, int i) {
        List<String> l = new ArrayList<>();
        for (ConfigPanel.Widget w : p.widgets(i)) {
            l.add(geo(w));
        }
        return l;
    }

    @Nested
    @DisplayName("ConfigPanel layout")
    class Layout {
        @Test
        @DisplayName("one page: C's button row, one- and two-line rows, the save button centred in what is left")
        void onePage() {
            ConfigPanel p = new ConfigPanel(entries());
            p.layout(300, 400, M, M);
            assertAll(
                () -> assertEquals(1, p.pageCount(), "everything fits"),
                () -> assertEquals(List.of(
                    "PREV 216,379 34x15", "NEXT 111,379 34x15", "CLOSE 6,379 41x15",
                    "LABEL 6,6 55x15", "BOOL 223,6 27x15",
                    "LABEL 6,27 83x15", "INPUT 188,27 32x15", "LEFT 220,27 15x15", "RIGHT 235,27 15x15",
                    "LABEL 6,48 216x15", "INPUT 188,63 32x15", "LEFT 220,63 15x15", "RIGHT 235,63 15x15",
                    "SAVE 62,221 132x15"), page(p, 0),
                    "buttons 400 - 6 - 15 down; rows entry height + 6 apart; the long label takes its own line; "
                        + "save at 84 + (400 - 126) / 2"));
        }

        @Test
        @DisplayName("rows that would reach the buttons go to the next page; the page index survives a re-layout")
        void pages() {
            ConfigPanel p = new ConfigPanel(entries());
            p.layout(0, 100, M, M);
            List<String> p1 = page(p, 1);
            p.open();
            p.press(128, 100 - 6 - 7);
            p.release(128, 100 - 6 - 7);
            int afterNext = p.page();
            p.layout(0, 100, M, M);
            int kept = p.page();
            p.layout(0, 1000, M, M);
            assertAll(
                () -> assertEquals(List.of("PREV 216,79 34x15", "NEXT 111,79 34x15", "CLOSE 6,79 41x15",
                    "LABEL 6,6 216x15", "INPUT 188,21 32x15", "LEFT 220,21 15x15", "RIGHT 235,21 15x15"),
                    p1.subList(0, 7), "the two-line row starts page 2 (48 + 3 * 15 + 12 >= 100)"),
                () -> assertEquals(1, afterNext, "NEXT moved to page 2"),
                () -> assertEquals(1, kept, "an unchanged layout keeps the page"),
                () -> assertEquals(0, p.page(), "a layout with fewer pages resets it"));
        }

        @Test
        @DisplayName("an entry too big for an empty page is still placed (C would drop CONFIG)")
        void tooSmall() {
            ConfigPanel p = new ConfigPanel(entries());
            p.layout(0, 30, M, M);
            assertAll(
                () -> assertTrue(p.pageCount() >= 3, "every row got a page"),
                () -> assertEquals("LABEL 6,6 55x15", geo(p.widgets(0).get(3)), "first row on page 1"));
        }
    }

    @Nested
    @DisplayName("ConfigPanel pointer")
    class Pointer {
        ConfigPanel open() {
            ConfigPanel p = new ConfigPanel(entries());
            p.layout(300, 400, M, M);
            p.open();
            return p;
        }

        @Test
        @DisplayName("a Yes/No flips on release over it; moving off first cancels")
        void bool() {
            ConfigPanel p = open();
            boolean took = p.press(230, 310);
            UiAction a = p.release(230, 310);
            double afterClick = hud.v;
            p.press(230, 310);
            p.move(10, 310);
            boolean holding = p.holding();
            p.release(10, 310);
            assertAll(
                () -> assertTrue(took, "taken"),
                () -> assertEquals(UiAction.NONE, a, "no app action"),
                () -> assertEquals(0.0, afterClick, "showHUD flipped to No"),
                () -> assertTrue(holding, "held while dragged off"),
                () -> assertEquals(0.0, hud.v, "released off: unchanged"),
                () -> assertFalse(p.holding(), "released"));
        }

        @Test
        @DisplayName("arrows step the value; labels and fields take the press but do nothing")
        void arrows() {
            ConfigPanel p = open();
            p.press(240, 330);
            p.release(240, 330);
            double up = max.v;
            p.press(225, 330);
            p.release(225, 330);
            p.press(225, 330);
            p.release(225, 330);
            boolean onLabel = p.press(10, 330);
            UiAction labelRelease = p.release(10, 330);
            assertAll(
                () -> assertEquals(9.0, up, "right arrow: 8 -> 9"),
                () -> assertEquals(7.0, max.v, "left twice: 9 -> 8 -> 7"),
                () -> assertTrue(onLabel, "a press on the form is taken"),
                () -> assertEquals(UiAction.NONE, labelRelease, "a label does nothing"),
                () -> assertFalse(p.press(10, 200), "above the form: not taken"));
        }

        @Test
        @DisplayName("CLOSE closes; NEXT and PREV wrap; SAVE is reported; a closed panel takes nothing")
        void buttons() {
            ConfigPanel p = new ConfigPanel(entries());
            p.layout(0, 100, M, M);
            p.open();
            int n = p.pageCount();
            p.press(220, 85);
            p.release(220, 85);
            int prev = p.page();
            p.press(220, 85);
            p.release(220, 85);
            p.press(10, 85);
            p.release(10, 85);
            boolean closed = !p.isOpen();
            boolean closedTakes = p.press(10, 85);
            ConfigPanel q = open();
            q.press(100, 528);
            UiAction save = q.release(100, 528);
            q.saved(false);
            String failed = q.saveLabel();
            q.saved(true);
            assertAll(
                () -> assertEquals(n - 1, prev, "PREV from page 1 wraps to the last"),
                () -> assertEquals(n - 2, p.page(), "PREV again"),
                () -> assertTrue(closed, "CLOSE closed"),
                () -> assertFalse(closedTakes, "a closed panel takes no press"),
                () -> assertEquals(UiAction.SAVE, save, "SAVE"),
                () -> assertEquals("Saving failed...", failed, "a failed save shows on the button"),
                () -> assertEquals("Save Configuration", q.saveLabel(), "success restores it"),
                () -> assertEquals(UiAction.NONE, q.release(100, 528), "release without press"));
        }

        @Test
        @DisplayName("drawing: blue form, labels, faces, fields with values, arrows; nothing while closed")
        void draw() {
            ConfigPanel p = open();
            HudFrame f = new HudFrame(64, 512, 64, 64);
            p.draw(f, M, M);
            HudFrame none = new HudFrame();
            new ConfigPanel(entries()).draw(none, M, M);
            List<String> t = texts(f);
            assertAll(
                () -> assertEquals("fill 0,300 256x400 c" + HudColor.WINDOW, rects(f).get(0), "the form"),
                () -> assertTrue(t.contains("9,317 showHUD c" + HudColor.SCORE), "label"),
                () -> assertTrue(t.contains("226,317 Yes c" + HudColor.SCORE), "Yes on the button"),
                () -> assertTrue(t.contains("200,338 8 c" + HudColor.WHITE), "the value centred in its field"),
                () -> assertTrue(t.contains("197,374 45 c" + HudColor.WHITE), "a float over 10 shows as %d"),
                () -> assertTrue(t.contains("65,532 Save Configuration c" + HudColor.SCORE), "save button"),
                () -> assertEquals(0, none.rectCount() + none.textCount(), "closed: nothing"));
        }
    }

    @Nested
    @DisplayName("AboutWindow")
    class About {
        @Test
        @DisplayName("layout and paging: centred, buttons at C's places, NEXT/PREV wrap and skip page 1 when items fit")
        void paging() {
            AboutWindow a = new AboutWindow();
            a.layout(1000, 800, M);
            a.open();
            HudFrame f = new HudFrame(16, 64, 128, 160);
            a.draw(f, M, M);
            int split = a.itemSplit();
            boolean takes = a.press(198 + 2 + 282 + 1, 48 + 2 + 665 + 1);
            a.release();
            int p1 = a.page();
            a.press(198 + 2 + 554, 50 + 665);
            a.release();
            a.press(198 + 2 + 554, 50 + 665);
            a.release();
            int wrapped = a.page();
            a.press(198 + 2 + 10, 50 + 665);
            a.release();
            assertAll(
                () -> assertEquals("fill 198,48 604x704 c" + HudColor.WHITE, rects(f).get(0), "centred frame"),
                () -> assertEquals("fill 200,50 600x700 c" + HudColor.WINDOW, rects(f).get(1), "blue inside"),
                () -> assertTrue(split >= 0 && split < AboutWindow.ITEM_TEXT.length - 1,
                    "the items do not fit one page at this font: split " + split),
                () -> assertTrue(takes, "NEXT press taken"),
                () -> assertEquals(1, p1, "NEXT shows the continued items"),
                () -> assertEquals(3, wrapped, "PREV twice from 1: 0, then wraps to 3"),
                () -> assertFalse(a.isOpen(), "CLOSE closes"),
                () -> assertEquals(0, a.page(), "and resets to page 0"),
                () -> assertFalse(a.press(0, 0), "a closed window takes nothing"));
        }

        @Test
        @DisplayName("wrap: one run per line, newlines break, long lines wrap at WIDTH - BORDER")
        void wrap() {
            AboutWindow a = new AboutWindow();
            HudFrame f = new HudFrame(4, 4, 32, 128);
            int end = a.wrap(f, M, 100, 50, 10, 10, "aaa bbb\nccc");
            HudFrame g = new HudFrame(4, 4, 32, 128);
            a.wrap(g, M, 0, 0, 10, 0, "wwwwwwwwww ".repeat(9).trim());
            assertAll(
                () -> assertEquals(List.of("110,70 aaa bbb c" + HudColor.SCORE, "110,84 ccc c" + HudColor.SCORE), texts(f),
                    "line runs offset by the window origin, 14 apart"),
                () -> assertEquals(38, end, "last baseline 34 + descent + 1"),
                () -> assertEquals(0, a.wrap(f, M, 0, 0, 10, 10, ""), "empty text: 0"),
                () -> assertEquals(2, g.textCount(), "9 words of 70 px wrap after 7"),
                () -> assertEquals(7 * 10 + 6, g.text(0).length(), "the first line holds 7 words"));
        }

        @Test
        @DisplayName("item pictures sit in their boxes at C's Paint_item positions")
        void items() {
            AboutWindow a = new AboutWindow();
            a.layout(600, 704, M);
            a.open();
            a.draw(new HudFrame(16, 64, 128, 160), M, M);
            assertAll(
                () -> assertEquals(0, a.item(0), "first item: fuel"),
                () -> assertEquals(2 + 2 * 10 + 16, a.itemX(0), "x: frame + 2 * BORDER + ITEM_SIZE"),
                () -> assertTrue(a.itemY(1) > a.itemY(0), "items go down"));
        }
    }
}
