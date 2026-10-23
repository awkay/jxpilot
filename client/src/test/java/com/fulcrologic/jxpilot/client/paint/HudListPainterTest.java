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
            texts.add(new Object[] {s, len, x, y, color});
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
                () -> assertArrayEquals(new int[] {4, 1, 3, 1, 0, 1, 12}, HudListPainter.XPILOT_PALETTE,
                    "HUD 4, WHITE 1, RED 3, OLD_MESSAGE 1, BLACK 0, SCORE 1, SCORE_DIM 12"),
                () -> assertArrayEquals(new boolean[] {false, false, false, false, false, true, false},
                    HudListPainter.XPILOT_SHADOW, "only SCORE text is shadowed"),
                () -> assertEquals(P.color(4), lp.rgba(HUD), "HUD is the default hudColor 4"),
                () -> assertEquals(P.color(12), lp.rgba(SCORE_DIM), "SCORE_DIM is grey 12"));
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
    }

    @Nested
    class Texts {
        @Test
        void textSitsOnItsBaselineCutToItsLength() {
            lp.paint(b, ss, new List_().text("hello", 3, 10, 20, WHITE).text("old", 3, 10, 40, OLD_MESSAGE)
                .text("dim", 3, 10, 60, SCORE_DIM).text("bg", 2, 10, 80, BLACK).text("hud", 3, 10, 100, HUD));
            assertEquals(List.of("'hel'@10,580#" + hex(1), "'old'@10,560#" + hex(1), "'dim'@10,540#" + hex(12),
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
