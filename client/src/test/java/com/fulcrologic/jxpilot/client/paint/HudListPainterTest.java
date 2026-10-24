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
package com.fulcrologic.jxpilot.client.paint;

import com.fulcrologic.jxpilot.client.Fixtures;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A 600 px high framebuffer at ratio 1 with the fixture font; list colours are client-net's HudColor indices. */
class HudListPainterTest {
    static final Palette P = Palette.xpilotDefault();
    /** HudColor indices. */
    static final int HUD = 0, WHITE = 1, RED = 2, OLD_MESSAGE = 3, BLACK = 4, SCORE = 5, SCORE_DIM = 6;

    static String hex(int paletteIndex) {
        return Integer.toHexString(P.color(paletteIndex));
    }

    /** A hand-filled draw list. */
    static final class List_ implements HudDrawList {
        final List<float[]> lines = new ArrayList<>();
        final List<float[]> rects = new ArrayList<>();
        final List<Object[]> texts = new ArrayList<>();

        List_ line(float x0, float y0, float x1, float y1, int style, int color) {
            lines.add(new float[] {x0, y0, x1, y1, style, color});
            return this;
        }

        List_ rect(float x, float y, float w, float h, boolean filled, int color) {
            rects.add(new float[] {x, y, w, h, filled ? 1 : 0, color});
            return this;
        }

        List_ text(String s, int len, float x, float y, int color) {
            return text(s, len, x, y, color, 0);
        }

        List_ text(String s, int len, float x, float y, int color, int font) {
            texts.add(new Object[] {s, len, x, y, color, font});
            return this;
        }

        @Override public int lineCount() { return lines.size(); }
        @Override public float lineX0(int i) { return lines.get(i)[0]; }
        @Override public float lineY0(int i) { return lines.get(i)[1]; }
        @Override public float lineX1(int i) { return lines.get(i)[2]; }
        @Override public float lineY1(int i) { return lines.get(i)[3]; }
        @Override public int lineStyle(int i) { return (int) lines.get(i)[4]; }
        @Override public int lineColor(int i) { return (int) lines.get(i)[5]; }
        @Override public int rectCount() { return rects.size(); }
        @Override public float rectX(int i) { return rects.get(i)[0]; }
        @Override public float rectY(int i) { return rects.get(i)[1]; }
        @Override public float rectW(int i) { return rects.get(i)[2]; }
        @Override public float rectH(int i) { return rects.get(i)[3]; }
        @Override public boolean rectFilled(int i) { return rects.get(i)[4] != 0; }
        @Override public int rectColor(int i) { return (int) rects.get(i)[5]; }
        @Override public int textCount() { return texts.size(); }
        @Override public CharSequence text(int i) { return (String) texts.get(i)[0]; }
        @Override public int textLength(int i) { return (Integer) texts.get(i)[1]; }
        @Override public float textX(int i) { return (Float) texts.get(i)[2]; }
        @Override public float textY(int i) { return (Float) texts.get(i)[3]; }
        @Override public int textColor(int i) { return (Integer) texts.get(i)[4]; }
        @Override public int textFont(int i) { return (Integer) texts.get(i)[5]; }
    }

    /** The fixture font with every glyph 3 px further right (xoff 4), so a run shows which font drew it. */
    static com.fulcrologic.jxpilot.client.render.Font shifted() {
        com.fulcrologic.jxpilot.client.render.Font f = new com.fulcrologic.jxpilot.client.render.Font(1000, 100, 10, 3);
        for (char c = com.fulcrologic.jxpilot.client.render.Font.FIRST; c <= com.fulcrologic.jxpilot.client.render.Font.LAST; c++) {
            float x0 = (c - 32) * 8;
            f.setGlyph(c, x0, 0, x0 + 6, 9, 4, -8, 7);
        }
        return f;
    }

    @Nested
    class Fonts {
        @Test
        void eachRunDrawsWithTheWriterOfItsFontIndex() {
            TextWriter plain = new TextWriter(Fixtures.font());
            TextWriter moved = new TextWriter(shifted());
            HudListPainter byFont = HudListPainter.xpilot(new TextWriter[] {plain, moved, null}, P);
            byFont.paint(b, ss, new List_().text("a", 1, 10, 20, WHITE, 0).text("b", 1, 10, 40, WHITE, 1)
                .text("c", 1, 10, 60, WHITE, 2).text("d", 1, 10, 80, WHITE, 3).text("e", 1, 10, 100, WHITE, -1));
            assertAll(
                () -> assertEquals(List.of("'a'@10,580#" + hex(1), "'b'@13,560#" + hex(1), "'c'@10,540#" + hex(1),
                    "'d'@10,520#" + hex(1), "'e'@10,500#" + hex(1)), Drawn.runStrings(b),
                    "font 0 plain, font 1 shifted by 3; a null writer and indexes 3 (one past the end) and -1 fall back to writer 0"),
                () -> assertSame(moved, byFont.writer(1), "writer 1 is the second"),
                () -> assertSame(plain, byFont.writer(2), "a null entry is writer 0"));
        }

        @Test
        void aDrawListWithoutFontsUsesWriterZero() {
            HudDrawList noFonts = new HudDrawList() {
                @Override public int lineCount() { return 0; }
                @Override public float lineX0(int i) { return 0; }
                @Override public float lineY0(int i) { return 0; }
                @Override public float lineX1(int i) { return 0; }
                @Override public float lineY1(int i) { return 0; }
                @Override public int lineStyle(int i) { return 0; }
                @Override public int lineColor(int i) { return 0; }
                @Override public int rectCount() { return 0; }
                @Override public float rectX(int i) { return 0; }
                @Override public float rectY(int i) { return 0; }
                @Override public float rectW(int i) { return 0; }
                @Override public float rectH(int i) { return 0; }
                @Override public boolean rectFilled(int i) { return false; }
                @Override public int rectColor(int i) { return 0; }
                @Override public int textCount() { return 1; }
                @Override public CharSequence text(int i) { return "z"; }
                @Override public int textLength(int i) { return 1; }
                @Override public float textX(int i) { return 10; }
                @Override public float textY(int i) { return 20; }
                @Override public int textColor(int i) { return WHITE; }
            };
            assertEquals(0, noFonts.textFont(0), "the default font index is 0, the game font");
        }
    }

    HudListPainter lp;
    ScreenSpace ss;
    VertexBatch b;

    @BeforeEach
    void setUp() {
        lp = HudListPainter.xpilot(new TextWriter(Fixtures.font()), P);
        ss = new ScreenSpace();
        ss.set(600, 1);
        b = Drawn.batch();
    }

    @Nested
    class Defaults {
        @Test
        void hudColorsMapToTheXPilotPalette() {
            assertAll(
                () -> assertArrayEquals(new int[] {4, 1, 3, 1, 0, 1, 0, 2, 3}, HudListPainter.XPILOT_PALETTE,
                    "HUD 4, WHITE 1, RED 3, OLD_MESSAGE 1, BLACK 0, SCORE 1, SCORE_DIM 0 (C black), WINDOW 2, BUTTON 3"),
                () -> assertArrayEquals(new boolean[] {false, false, false, false, false, true, false, false, false},
                    HudListPainter.XPILOT_SHADOW, "only SCORE text is shadowed"),
                () -> assertEquals(P.color(4), lp.rgba(HUD), "HUD is the default hudColor 4"),
                () -> assertEquals(P.color(0), lp.rgba(SCORE_DIM), "SCORE_DIM is black, as C on its blue window"));
        }

        @Test
        void aListColourCanBeRemapped() {
            lp.setPaletteIndex(SCORE_DIM, 12);
            assertEquals(P.color(12), lp.rgba(SCORE_DIM), "SCORE_DIM remapped to grey 12 (C blockBitmaps)");
        }

        @Test
        void aCustomMapIsCopiedAndAShortShadowArrayMeansNoShadow() {
            int[] map = {5};
            HudListPainter custom = new HudListPainter(new TextWriter(Fixtures.font()), P, map, new boolean[0]);
            map[0] = 7;
            custom.paint(b, ss, new List_().text("a", 1, 10, 20, 0));
            assertAll(
                () -> assertEquals(P.color(5), custom.rgba(0), "the map is copied at construction"),
                () -> assertEquals(List.of("'a'@10,580#" + hex(5)), Drawn.runStrings(b), "drawn once, unshadowed"));
        }
    }

    @Nested
    class Rects {
        @Test
        void fillsCoverWByHAndOutlinesWPlusOne() {
            b.setLineWidth(3);
            lp.paint(b, ss, new List_().rect(10, 20, 5, 3, true, RED).rect(10, 20, 5, 3, false, WHITE)
                .rect(10, 20, 0, 3, true, RED).rect(10, 20, 5, 0, true, RED));
            assertAll(
                () -> assertEquals(Drawn.rectTris(10, 580, 15, 577, P.color(Palette.RED)), Drawn.tris(b),
                    "fill: pixels 10..14 x 20..22 in red; zero-width or zero-height fills draw nothing"),
                () -> assertEquals(Drawn.rectSegs(10.5f, 579.5f, 15.5f, 576.5f), Drawn.segs(b),
                    "outline through the centres of columns 10..15 and rows 20..23"),
                () -> assertEquals(P.color(Palette.WHITE), b.segColor(0), "in white"),
                () -> assertEquals(1f, b.segWidth(0), "1 px lines whatever the batch had"));
        }
    }

    @Nested
    class Lines {
        @Test
        void solidLinesJoinPixelCentresAndDashedOnesAre8On4Off() {
            lp.paint(b, ss, new List_().line(0, 0, 10, 0, HudDrawList.SOLID, HUD)
                .line(0, 10, 30, 10, HudDrawList.DASHED, HUD));
            assertAll(
                () -> assertEquals(List.of("0.5,599.5-10.5,599.5", "0.5,589.5-8.5,589.5", "12.5,589.5-20.5,589.5",
                    "24.5,589.5-30.5,589.5"), Drawn.segs(b), "solid as is; dashed from offset 0"),
                () -> assertEquals(P.color(4), b.segColor(3), "in the HUD colour"));
        }

        @Test
        void dashesAreLogicalPixelsAtRatioTwo() {
            ss.set(1200, 2);
            lp.paint(b, ss, new List_().line(0, 10, 30, 10, HudDrawList.DASHED, HUD));
            assertEquals(List.of("1,1179-17,1179", "25,1179-41,1179", "49,1179-61,1179"), Drawn.segs(b),
                "16 framebuffer px on, 8 off");
        }

        @Test
        void dashLengthsAreWinscaledByTheZoom() {
            lp.setScale(0.5);
            lp.paint(b, ss, new List_().line(0, 10, 30, 10, HudDrawList.DASHED, HUD).line(0, 0, 10, 0, HudDrawList.SOLID, HUD));
            assertEquals(List.of("0.5,589.5-4.5,589.5", "6.5,589.5-10.5,589.5", "12.5,589.5-16.5,589.5",
                    "18.5,589.5-22.5,589.5", "24.5,589.5-28.5,589.5", "0.5,599.5-10.5,599.5"), Drawn.segs(b),
                "zoom 0.5: WINSCALE(8) = 4 on, WINSCALE(4) = 2 off (C Scale_dashes); the list's coordinates unchanged");
        }
    }

    @Nested
    class Texts {
        @Test
        void textSitsOnItsBaselineCutToItsLength() {
            lp.paint(b, ss, new List_().text("hello", 3, 10, 20, WHITE).text("old", 3, 10, 40, OLD_MESSAGE)
                .text("dim", 3, 10, 60, SCORE_DIM).text("bg", 2, 10, 80, BLACK).text("hud", 3, 10, 100, HUD));
            assertEquals(List.of("'hel'@10,580#" + hex(1), "'old'@10,560#" + hex(1), "'dim'@10,540#" + hex(0),
                    "'bg'@10,520#" + hex(0), "'hud'@10,500#" + hex(4)), Drawn.runStrings(b),
                "pen at x, baseline at y, textLength chars, each colour mapped");
        }

        @Test
        void scoreTextIsShadowed() {
            lp.paint(b, ss, new List_().text("ab", 2, 10, 20, SCORE));
            assertEquals(List.of("'ab'@11,579#" + hex(0), "'ab'@9,581#" + hex(1)), Drawn.runStrings(b),
                "ShadowDrawString: black at (+1, +1), then white at (-1, -1)");
        }
    }

    @Nested
    class Order {
        @Test
        void rectsThenLinesThenText() {
            lp.paint(b, ss, new List_().text("t", 1, 50, 50, WHITE).line(0, 0, 5, 0, HudDrawList.SOLID, HUD)
                .rect(0, 0, 4, 4, true, BLACK));
            assertAll(
                () -> assertEquals(3, b.commandCount(), "three draw commands"),
                () -> assertEquals(VertexBatch.TRIANGLES, b.commandMode(0), "the fill first"),
                () -> assertEquals(VertexBatch.LINES, b.commandMode(1), "then the line"),
                () -> assertEquals(VertexBatch.TRIANGLES, b.commandMode(2), "then the text"),
                () -> assertEquals(6, b.commandFirst(2), "the text's quad follows the fill's"));
        }
    }
}
