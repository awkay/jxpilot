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
package com.fulcrologic.jxpilot.client.render;

import com.fulcrologic.jxpilot.client.Fixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class TextWriterTest {
    static final int C = Palette.pack(1, 2, 3, 255);
    /** UVs are stored as 16-bit normalized values. */
    static final float UV_EPS = 1f / 65535;

    static VertexBatch batch() {
        return Fixtures.batch(1024, 16, b -> { });
    }

    @Nested
    class Glyphs {
        @Test
        void glyphQuadSitsOnTheBaselineWithTheFontOffsets() {
            TextWriter t = new TextWriter(Fixtures.font());
            VertexBatch b = batch();
            float end = t.draw(b, 'A', 100, 50, C);
            // 'A' = slot 33: atlas x [264, 270], y [0, 9]; offset (1, -8); advance 7
            assertAll(
                () -> assertEquals(107f, end, "pen advances by 7"),
                () -> assertEquals(6, b.vertexCount(), "one quad"),
                () -> assertEquals(101f, b.x(0), "left = pen + xoff"),
                () -> assertEquals(49f, b.y(0), "bottom = baseline - yoff - height = 50 + 8 - 9"),
                () -> assertEquals(107f, b.x(2), "right = left + glyph width 6"),
                () -> assertEquals(58f, b.y(2), "top = baseline - yoff"),
                () -> assertEquals(264f / 1000, b.u(0), UV_EPS, "u0 = atlas x0 / width"),
                () -> assertEquals(9f / 100, b.v(0), UV_EPS, "bottom uses atlas y1 (atlas is y down)"),
                () -> assertEquals(270f / 1000, b.u(2), UV_EPS, "u1 = atlas x1 / width"),
                () -> assertEquals(0f, b.v(2), UV_EPS, "top uses atlas y0"),
                () -> assertEquals(C, b.color(3), "text colour on the vertices"));
        }

        @Test
        void quadsSnapToWholePixels() {
            TextWriter t = new TextWriter(Fixtures.font());
            VertexBatch b = batch();
            t.draw(b, 'A', 100.4f, 50.6f, C);
            assertAll(
                () -> assertEquals(101f, b.x(0), "left rounded"),
                () -> assertEquals(50f, b.y(0), "bottom follows the rounded top"),
                () -> assertEquals(59f, b.y(2), "top rounded"));
        }

        @Test
        void splitGlyphIsCutAtTheLineWithMatchingTexels() {
            TextWriter t = new TextWriter(Fixtures.font());
            VertexBatch b = batch();
            int below = Palette.pack(9, 9, 9, 255);
            float end = t.drawSplit(b, 'A', 100, 50, 54, below, C);
            // 'A': quad x [101, 107], y [49, 58], v from 0.09 (bottom) to 0 (top); a cut at 54 is 5 of 9 px up
            assertAll(
                () -> assertEquals(107f, end, "pen advances by 7"),
                () -> assertEquals(12, b.vertexCount(), "two quads"),
                () -> assertEquals(49f, b.y(0), "lower quad starts at the glyph bottom"),
                () -> assertEquals(54f, b.y(2), "lower quad ends at the cut"),
                () -> assertEquals(below, b.color(0), "lower part in the below colour"),
                () -> assertEquals(0.09f - 0.09f * 5 / 9, b.v(2), UV_EPS, "lower quad's texels end where the cut is"),
                () -> assertEquals(54f, b.y(6), "upper quad starts at the cut"),
                () -> assertEquals(58f, b.y(8), "upper quad ends at the glyph top"),
                () -> assertEquals(C, b.color(6), "upper part in the above colour"),
                () -> assertEquals(b.v(2), b.v(6), UV_EPS, "the texels meet at the cut: none lost or doubled"),
                () -> assertEquals(0f, b.v(8), UV_EPS, "upper quad ends at the atlas top"));
        }

        @Test
        void splitLineIsRoundedToAPixel() {
            TextWriter t = new TextWriter(Fixtures.font());
            VertexBatch b = batch();
            t.drawSplit(b, 'A', 100, 50, 54.4f, C, C);
            assertEquals(54f, b.y(2), "the cut snaps to the pixel row like the quads");
        }

        @Test
        void splitLineOutsideTheGlyphColoursItWhole() {
            TextWriter t = new TextWriter(Fixtures.font());
            int below = Palette.pack(9, 9, 9, 255);
            VertexBatch over = batch();
            t.drawSplit(over, 'A', 100, 50, 80, below, C);
            VertexBatch under = batch();
            t.drawSplit(under, 'A', 100, 50, 10, below, C);
            VertexBatch atTop = batch();
            t.drawSplit(atTop, 'A', 100, 50, 58, below, C);
            VertexBatch atBottom = batch();
            t.drawSplit(atBottom, 'A', 100, 50, 49, below, C);
            assertAll(
                () -> assertEquals(6, over.vertexCount(), "a line over the glyph: one quad"),
                () -> assertEquals(below, over.color(0), "the glyph is all under it: below colour"),
                () -> assertEquals(6, under.vertexCount(), "a line under the glyph: one quad"),
                () -> assertEquals(C, under.color(0), "the glyph is all over it: above colour"),
                () -> assertEquals(below, atTop.color(0), "a line at the top edge: all below"),
                () -> assertEquals(6, atBottom.vertexCount(), "a line at the bottom edge: one quad"),
                () -> assertEquals(C, atBottom.color(0), "a line at the bottom edge: all above"),
                () -> assertEquals(100f + 7, t.drawSplit(batch(), ' ', 100, 50, 54, below, C), "a space advances"),
                () -> {
                    VertexBatch sp = batch();
                    t.drawSplit(sp, ' ', 0, 0, 0, below, C);
                    assertEquals(0, sp.vertexCount(), "a space draws nothing");
                });
        }

        @Test
        void emptyGlyphsAdvanceWithoutAQuad() {
            TextWriter t = new TextWriter(Fixtures.font());
            VertexBatch b = batch();
            float end = t.draw(b, ' ', 0, 0, C);
            assertAll(
                () -> assertEquals(7f, end, "space advances"),
                () -> assertEquals(0, b.vertexCount(), "space draws nothing"));
        }

        @Test
        void glyphsWithZeroWidthOrHeightDrawNothing() {
            Font f = Fixtures.font();
            f.setGlyph('a', 10, 0, 10, 9, 0, -8, 3); // zero width
            f.setGlyph('b', 10, 0, 15, 0, 0, -8, 3); // zero height
            TextWriter t = new TextWriter(f);
            VertexBatch b = batch();
            float end = t.draw(b, "ab", 0, 0, C);
            assertAll(
                () -> assertEquals(0, b.vertexCount(), "no quads"),
                () -> assertEquals(6f, end, "but both advance"));
        }

        @Test
        void glyphHeightAndTopTexelComeFromTheAtlasRectangle() {
            Font f = Fixtures.font();
            f.setGlyph('c', 10, 20, 16, 29, 0, -8, 7);
            TextWriter t = new TextWriter(f);
            VertexBatch b = batch();
            t.draw(b, 'c', 0, 0, C);
            assertAll(
                () -> assertEquals(-1f, b.y(0), "bottom = top 8 - height (29 - 20)"),
                () -> assertEquals(0.2f, b.v(2), UV_EPS, "top uses atlas y0 / height = 20 / 100"),
                () -> assertEquals(0.29f, b.v(0), UV_EPS, "bottom uses atlas y1 / height"));
        }

        @Test
        void lastPrintableCharUsesItsOwnGlyph() {
            TextWriter t = new TextWriter(Fixtures.font());
            VertexBatch b = batch();
            t.draw(b, '~', 0, 0, C);
            assertEquals(('~' - 32) * 8f / 1000, b.u(0), UV_EPS, "'~' is in range");
        }

        @Test
        void outOfRangeCharactersUseTheFallbackGlyph() {
            TextWriter t = new TextWriter(Fixtures.font());
            VertexBatch b = batch();
            t.draw(b, '\u0100', 0, 0, C);
            float u = ('?' - 32) * 8f / 1000;
            VertexBatch low = batch();
            t.draw(low, '\n', 0, 0, C);
            assertAll(
                () -> assertEquals(u, b.u(0), UV_EPS, "a char above Latin-1 draws '?'"),
                () -> assertEquals(u, low.u(0), UV_EPS, "a char below the range draws '?'"),
                () -> assertEquals(7f, t.width('\u0100'), "and advances like '?'"));
        }
    }

    @Nested
    class Strings {
        @Test
        void drawsCharSequenceArraysAndRangesLeftToRight() {
            TextWriter t = new TextWriter(Fixtures.font());
            VertexBatch b = batch();
            float e1 = t.draw(b, "A1B", 0, 0, C);
            VertexBatch b2 = batch();
            float e2 = t.draw(b2, new char[] {'x', 'A', '1', 'B', 'y'}, 1, 3, 0, 0, C);
            VertexBatch b3 = batch();
            float e3 = t.draw(b3, "xA1By", 1, 3, 0, 0, C);
            assertAll(
                () -> assertEquals(19f, e1, "advances 7 + 5 + 7"),
                () -> assertEquals(18, b.vertexCount(), "three quads"),
                () -> assertEquals(8f, b.x(6), "second glyph starts at pen 7 + xoff 1"),
                () -> assertEquals(13f, b.x(12), "third glyph starts at pen 12 + xoff 1"),
                () -> assertEquals(19f, e2, "char array range draws the same"),
                () -> assertEquals(13f, b2.x(12), "same glyph placement from an array"),
                () -> assertEquals(19f, e3, "CharSequence range draws the same"),
                () -> assertEquals(13f, b3.x(12), "same glyph placement from a range"));
        }

        @Test
        void widthsMatchAdvances() {
            TextWriter t = new TextWriter(Fixtures.font());
            assertAll(
                () -> assertEquals(19f, t.width("A1B"), "sum of advances"),
                () -> assertEquals(0f, t.width(""), "empty string"),
                () -> assertEquals(5f, t.width('1'), "single char"),
                () -> assertEquals(5f + 7 + 7, t.widthInt(100), "'100' = 5 + 7 + 7"),
                () -> assertEquals(7f + 5, t.widthInt(-1), "'-1' = 7 + 5"),
                () -> assertEquals(5f + 7 + 7 + 7, t.widthFixed(1.25, 2), "'1.25' = 5 + 7 + 7 + 7"),
                () -> assertSame(Fixtures.font().getClass(), t.font().getClass(), "exposes its font"));
        }
    }

    @Nested
    class Numbers {
        @Test
        void drawIntWritesItsDigits() {
            TextWriter t = new TextWriter(Fixtures.font());
            VertexBatch b = batch();
            float end = t.drawInt(b, -12, 10, 0, C);
            assertAll(
                () -> assertEquals(10f + 7 + 5 + 7, end, "advances over '-', '1', '2'"),
                () -> assertEquals(18, b.vertexCount(), "three glyphs"),
                () -> assertEquals(('-' - 32) * 8f / 1000, b.u(0), UV_EPS, "first glyph is '-'"),
                () -> assertEquals(('1' - 32) * 8f / 1000, b.u(6), UV_EPS, "second glyph is '1'"),
                () -> assertEquals(('2' - 32) * 8f / 1000, b.u(12), UV_EPS, "third glyph is '2'"));
        }

        @Test
        void drawFixedWritesRoundedDecimals() {
            TextWriter t = new TextWriter(Fixtures.font());
            VertexBatch b = batch();
            float end = t.drawFixed(b, 2.345, 1, 0, 0, C);
            assertAll(
                () -> assertEquals(21f, end, "'2.3' is three 7-wide glyphs"),
                () -> assertEquals(('2' - 32) * 8f / 1000, b.u(0), UV_EPS, "first glyph '2'"),
                () -> assertEquals(('.' - 32) * 8f / 1000, b.u(6), UV_EPS, "second glyph '.'"),
                () -> assertEquals(('3' - 32) * 8f / 1000, b.u(12), UV_EPS, "third glyph '3'"));
        }
    }

    @Test
    void fontMetrics() {
        Font f = Fixtures.font();
        assertAll(
            () -> assertEquals(10f, f.ascent(), "ascent"),
            () -> assertEquals(3f, f.descent(), "descent"),
            () -> assertEquals(13f, f.lineHeight(), "line height = ascent + descent"),
            () -> assertEquals(1000, f.atlasWidth(), "atlas width"),
            () -> assertEquals(100, f.atlasHeight(), "atlas height"),
            () -> assertEquals(5f, f.advance('1'), "advance of a char"));
    }
}
