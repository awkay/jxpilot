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

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("C client widgets: drawing, button bar, entries")
class WidgetsTest {
    /** Fixed-width font: ascent 10, descent 3, 7 pixels a char. */
    record Mono() implements TextMetrics {
        @Override
        public int ascent() { return 10; }

        @Override
        public int descent() { return 3; }

        @Override
        public int width(CharSequence s, int len) { return 7 * len; }
    }

    static final Mono M = new Mono();

    static List<String> rects(HudFrame f) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < f.rectCount(); i++) {
            l.add((f.rectFilled(i) ? "fill " : "box ") + (int) f.rectX(i) + "," + (int) f.rectY(i) + " "
                + (int) f.rectW(i) + "x" + (int) f.rectH(i) + " c" + f.rectColor(i));
        }
        return l;
    }

    static List<String> texts(HudFrame f) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < f.textCount(); i++) {
            l.add((int) f.textX(i) + "," + (int) f.textY(i) + " " + f.text(i) + " c" + f.textColor(i));
        }
        return l;
    }

    @Nested
    @DisplayName("UiDraw")
    class Draw {
        @Test
        @DisplayName("a bordered button: white border, red face (black when inverse), centred shadowed label")
        void button() {
            HudFrame f = new HudFrame();
            UiDraw.button(f, 10, 20, 84, 21, 1, "QUIT", false, M);
            HudFrame g = new HudFrame();
            UiDraw.button(g, 10, 20, 50, 15, 0, "No", true, M);
            assertAll(
                () -> assertEquals(List.of("box 10,20 85x22 c" + HudColor.WHITE, "fill 11,21 84x21 c" + HudColor.BUTTON),
                    rects(f), "border around the 84x21 face"),
                () -> assertEquals(List.of("39,35 QUIT c" + HudColor.SCORE), texts(f),
                    "label centred: x 11 + (84 - 28) / 2, baseline 21 + (21 - 13) / 2 + 10"),
                () -> assertEquals(List.of("fill 10,20 50x15 c" + HudColor.BLACK), rects(g),
                    "no border, inverse face black"),
                () -> assertEquals(List.of("28,31 No c" + HudColor.SCORE), texts(g), "label centred in 50x15"));
        }

        @Test
        @DisplayName("labels are shadowed, inputs plain white on black, both centred")
        void labelAndInput() {
            HudFrame f = new HudFrame();
            UiDraw.label(f, 6, 6, 55, 15, "showHUD", M);
            UiDraw.input(f, 100, 6, 32, 15, "45", M);
            assertAll(
                () -> assertEquals(List.of("9,17 showHUD c" + HudColor.SCORE, "109,17 45 c" + HudColor.WHITE), texts(f),
                    "label at x + (55 - 49) / 2, input at x + (32 - 14) / 2, baselines y + 1 + 10"),
                () -> assertEquals(List.of("fill 100,6 32x15 c" + HudColor.BLACK), rects(f), "the input is black"));
        }

        @Test
        @DisplayName("arrows: black square with a red triangle (colours swap while pressed), pointing left or right")
        void arrows() {
            HudFrame l = new HudFrame(4, 64, 4, 4);
            UiDraw.arrow(l, 100, 50, 15, 15, false, false);
            HudFrame r = new HudFrame(4, 64, 4, 4);
            UiDraw.arrow(r, 100, 50, 15, 15, true, true);
            List<String> lr = rects(l);
            List<String> rr = rects(r);
            assertAll(
                () -> assertEquals("fill 100,50 15x15 c" + HudColor.BLACK, lr.get(0), "black background"),
                () -> assertEquals("fill 111,53 1x1 c" + HudColor.BUTTON, lr.get(1),
                    "row 3: the upper edge reaches x 10.9 there, one pixel before the base at 12"),
                () -> assertEquals("fill 104,57 8x1 c" + HudColor.BUTTON, lr.get(5),
                    "row 7 (centre 7.5, below the tip at 7): the lower edge at 3.9, pixels 4..11"),
                () -> assertEquals("fill 104,56 8x1 c" + HudColor.BUTTON, lr.get(4), "row 6 (above the tip): the upper edge at 4.1"),
                () -> assertEquals(10, lr.size(), "background plus rows 3..11"),
                () -> assertEquals("fill 100,50 15x15 c" + HudColor.BUTTON, rr.get(0), "pressed: red background"),
                () -> assertEquals("fill 103,57 8x1 c" + HudColor.BLACK, rr.get(5),
                    "right arrow: from the base at 3 to the edge at 11.1 (pixels 3..10), black"),
                () -> assertTrue(UiDraw.inside(5, 5, 5, 5, 1, 1), "inside at the corner"),
                () -> assertFalse(UiDraw.inside(6, 5, 5, 5, 1, 1), "outside past the width"),
                () -> assertFalse(UiDraw.inside(5, 6, 5, 5, 1, 1), "outside past the height"),
                () -> assertFalse(UiDraw.inside(4, 5, 5, 5, 1, 1), "outside left"),
                () -> assertFalse(UiDraw.inside(5, 4, 5, 5, 1, 1), "outside above"));
        }

        @Test
        @DisplayName("truncate drops tentative rectangles and text, never grows the lists")
        void truncate() {
            HudFrame f = new HudFrame();
            f.rect(0, 0, 1, 1, true, 0);
            f.text(0, 0, 0);
            f.rect(0, 0, 2, 2, true, 0);
            f.text(1, 1, 0);
            f.truncate(1, 1);
            int r1 = f.rectCount();
            int t1 = f.textCount();
            f.truncate(5, -2);
            assertAll(
                () -> assertEquals(1, r1, "rects cut to 1"),
                () -> assertEquals(1, t1, "texts cut to 1"),
                () -> assertEquals(1, f.rectCount(), "a larger count does not grow"),
                () -> assertEquals(0, f.textCount(), "a negative count empties"));
        }
    }

    @Nested
    @DisplayName("ButtonBar")
    class Bar {
        ButtonBar bar() {
            ButtonBar b = new ButtonBar();
            b.layout(200, M);
            return b;
        }

        @Test
        @DisplayName("layout: C's ButtonHeight, buttons at i + 84i, the form and the pulldown geometry")
        void layout() {
            ButtonBar b = bar();
            HudFrame f = new HudFrame();
            HudFrame o = new HudFrame();
            b.draw(f, o, M);
            b.press(200, 205);
            HudFrame o2 = new HudFrame();
            b.draw(new HudFrame(), o2, M);
            assertAll(
                () -> assertEquals(21, b.buttonHeight(), "ascent + descent + 2 * BTN_BORDER"),
                () -> assertEquals(23, b.height(), "form height ButtonHeight + 2"),
                () -> assertEquals(List.of("fill 0,200 256x23 c" + HudColor.WINDOW,
                    "box 0,200 85x22 c" + HudColor.WHITE, "fill 1,201 84x21 c" + HudColor.BUTTON,
                    "box 85,200 85x22 c" + HudColor.WHITE, "fill 86,201 84x21 c" + HudColor.BUTTON,
                    "box 170,200 85x22 c" + HudColor.WHITE, "fill 171,201 84x21 c" + HudColor.BUTTON), rects(f),
                    "blue form, then QUIT, ABOUT, MENU"),
                () -> assertEquals(0, o.rectCount(), "no pulldown while MENU is up"),
                () -> assertEquals("fill 170,222 86x111 c" + HudColor.WINDOW, rects(o2).get(0),
                    "pulldown under MENU: x 171 - 1, y 201 + 21, 5 * 22 + 1 tall"),
                () -> assertEquals("box 170,244 85x22 c" + HudColor.WHITE, rects(o2).get(3),
                    "entry 1 one ButtonHeight + 1 lower"),
                () -> assertTrue(b.menuOpen(), "menu open while held"));
        }

        @Test
        @DisplayName("QUIT and ABOUT act on release over them; moving off cancels; inverse only while over")
        void buttons() {
            ButtonBar b = bar();
            boolean took = b.press(10, 210);
            HudFrame held = new HudFrame();
            b.draw(held, new HudFrame(), M);
            UiAction quit = b.release(12, 212);
            b.press(100, 210);
            b.move(100, 400);
            HudFrame off = new HudFrame();
            b.draw(off, new HudFrame(), M);
            UiAction cancelled = b.release(100, 400);
            b.press(100, 210);
            UiAction about = b.release(100, 210);
            assertAll(
                () -> assertTrue(took, "a press on QUIT is taken"),
                () -> assertEquals("fill 1,201 84x21 c" + HudColor.BLACK, rects(held).get(2), "QUIT inverse while held"),
                () -> assertEquals(UiAction.QUIT, quit, "released over QUIT"),
                () -> assertEquals("fill 86,201 84x21 c" + HudColor.BUTTON, rects(off).get(4), "not inverse when off"),
                () -> assertEquals(UiAction.NONE, cancelled, "released elsewhere: nothing"),
                () -> assertEquals(UiAction.ABOUT, about, "ABOUT"),
                () -> assertFalse(b.press(10, 199), "above the bar is not taken"),
                () -> assertFalse(b.press(300, 210), "right of the bar is not taken"),
                () -> assertEquals(UiAction.NONE, b.release(10, 210), "a release without a press does nothing"),
                () -> assertFalse(b.holding(), "nothing held"));
        }

        @Test
        @DisplayName("MENU: hold, move onto an entry (drawn inverse), release chooses it; released off the menu: nothing")
        void menu() {
            ButtonBar b = bar();
            UiAction[] got = new UiAction[5];
            for (int i = 0; i < 5; i++) {
                b.press(200, 210);
                b.move(200, 230 + 22 * i);
                got[i] = b.release(200, 230 + 22 * i);
            }
            b.press(200, 210);
            b.move(200, 252);
            HudFrame o = new HudFrame();
            b.draw(new HudFrame(), o, M);
            boolean menuDrawnInverse = rects(o).get(4).endsWith("c" + HudColor.BLACK);
            UiAction off = b.release(10, 600);
            assertAll(
                () -> assertArrayEquals(new UiAction[] {UiAction.KEYS, UiAction.CONFIG, UiAction.SCORE, UiAction.PLAYER,
                    UiAction.MOTD}, got, "each entry"),
                () -> assertTrue(menuDrawnInverse, "the entry under the pointer is inverse"),
                () -> assertEquals(UiAction.NONE, off, "released off the pulldown"),
                () -> assertFalse(b.menuOpen(), "the pulldown closed"));
        }

        @Test
        @DisplayName("a wide entry widens the pulldown (C Widget_add_pulldown_entry)")
        void wide() {
            ButtonBar b = new ButtonBar();
            b.layout(0, new TextMetrics() {
                public int ascent() { return 10; }

                public int descent() { return 3; }

                public int width(CharSequence s, int len) { return 16 * len; }
            });
            b.press(200, 5);
            HudFrame o = new HudFrame();
            b.draw(new HudFrame(), o, M);
            assertAll(
                () -> assertEquals("fill 170,22 106x111 c" + HudColor.WINDOW, rects(o).get(0),
                    "CONFIG/PLAYER: 6 * 16 + 8 = 104 + 2"),
                () -> assertEquals("box 170,22 85x22 c" + HudColor.WHITE, rects(o).get(1),
                    "KEYS (64 + 8 < 84) keeps the menu width"),
                () -> assertEquals("box 170,44 105x22 c" + HudColor.WHITE, rects(o).get(3), "CONFIG is 104 wide"));
        }
    }

    @Nested
    @DisplayName("ConfigEntry")
    class Entry {
        @Test
        @DisplayName("int arrows: 5% steps, at least 1, clamped")
        void ints() {
            assertAll(
                () -> assertEquals(9, ConfigEntry.stepInt(8, true, 1, 15), "8 * 1.05 + 0.5 = 8.9: 8 -> 9"),
                () -> assertEquals(7, ConfigEntry.stepInt(8, false, 1, 15), "8 * 0.95 = 7.6 -> 7"),
                () -> assertEquals(525, ConfigEntry.stepInt(500, true, 0, 1000), "500 -> 525"),
                () -> assertEquals(475, ConfigEntry.stepInt(500, false, 0, 1000), "500 -> 475"),
                () -> assertEquals(1, ConfigEntry.stepInt(0, true, 0, 2), "0 -> 1 (same value: +1)"),
                () -> assertEquals(0, ConfigEntry.stepInt(1, false, 0, 2), "1 * 0.95 = 0"),
                () -> assertEquals(15, ConfigEntry.stepInt(15, true, 1, 15), "clamped at max"),
                () -> assertEquals(1, ConfigEntry.stepInt(1, false, 1, 15), "clamped at min"));
        }

        @Test
        @DisplayName("float arrows: 5% of the distance from the near end, at least 1% of the range")
        void floats() {
            assertAll(
                () -> assertEquals(47.0, ConfigEntry.stepFloat(45, true, 5, 55), 1e-4, "45: offset 40 -> 42 from 5"),
                () -> assertEquals(43.0, ConfigEntry.stepFloat(45, false, 5, 55), 1e-4, "offset 40 -> 38"),
                () -> assertEquals(0.2, ConfigEntry.stepFloat(0, true, -10, 10), 1e-9, "0 grows by range/100"),
                () -> assertEquals(-0.2, ConfigEntry.stepFloat(0, false, -10, 10), 1e-9,
                    "0 left: offset 0 shrinks below 0, so goes negative"),
                () -> assertEquals(-1.2, ConfigEntry.stepFloat(-1, false, -10, 10), 1e-6,
                    "negative values grow outward with the left arrow (5% of 1 is under range/100 = 0.2)"),
                () -> assertEquals(-0.8, ConfigEntry.stepFloat(-1, true, -10, 10), 1e-6, "and shrink with the right"),
                () -> assertEquals(0.0, ConfigEntry.stepFloat(0.1, false, -10, 10), 1e-9,
                    "a positive offset stops at 0"),
                () -> assertEquals(1.0, ConfigEntry.stepFloat(1, true, 0, 1), 1e-9, "clamped at max"),
                () -> assertEquals(0.12, ConfigEntry.stepFloat(0.12, true, 0, 1), 0.011,
                    "small values step at least 0.01"),
                () -> assertEquals(-2.95, ConfigEntry.stepFloat(-3, true, -5, -2), 1e-6,
                    "an all-negative range counts from its max (offset 1 shrinks to 0.95, as C)"));
        }

        @Test
        @DisplayName("formats as C's widgets and clicks change the value only when it moves")
        void formatAndClick() {
            double[] v = {0.5};
            int[] sets = {0};
            ConfigEntry.Value val = new ConfigEntry.Value() {
                public double get() { return v[0]; }

                public void set(double x) {
                    v[0] = x;
                    sets[0]++;
                }
            };
            com.fulcrologic.jxpilot.client.game.CharBuf b = new com.fulcrologic.jxpilot.client.game.CharBuf(16);
            ConfigEntry f1 = new ConfigEntry("p", ConfigEntry.Kind.FLOAT, 0, 1, val);
            ConfigEntry f10 = new ConfigEntry("p", ConfigEntry.Kind.FLOAT, -10, 10, val);
            ConfigEntry f55 = new ConfigEntry("p", ConfigEntry.Kind.FLOAT, 5, 55, val);
            ConfigEntry i = new ConfigEntry("i", ConfigEntry.Kind.INT, 0, 2, val);
            ConfigEntry bo = ConfigEntry.bool("b", val);
            String s1 = f1.format(b).toString();
            String s10 = f10.format(b).toString();
            v[0] = 45.7;
            String s55 = f55.format(b).toString();
            v[0] = 2;
            String si = i.format(b).toString();
            i.click(true);
            int afterNoMove = sets[0];
            bo.click(true);
            String sb = bo.format(b).toString();
            bo.click(false);
            assertAll(
                () -> assertEquals("0.50", s1, "max <= 1: %.2f"),
                () -> assertEquals("0.5", s10, "max <= 10: %.1f"),
                () -> assertEquals("45", s55, "larger: %d"),
                () -> assertEquals("2", si, "int"),
                () -> assertEquals(0, afterNoMove, "an int at its max does not change"),
                () -> assertEquals("No", sb, "2 is truthy, so the bool flipped to No"),
                () -> assertEquals(1.0, v[0], "and back to Yes"),
                () -> assertEquals("Yes", bo.format(b).toString(), "Yes"));
        }
    }
}
