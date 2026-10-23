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
        return VertexBatch.fixed(ByteBuffer.allocateDirect(1024 * VertexBatch.STRIDE), 16, b -> { });
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
            t.draw(b, 'é', 0, 0, C);
            float u = ('?' - 32) * 8f / 1000;
            VertexBatch low = batch();
            t.draw(low, '\n', 0, 0, C);
            assertAll(
                () -> assertEquals(u, b.u(0), UV_EPS, "a char above the range draws '?'"),
                () -> assertEquals(u, low.u(0), UV_EPS, "a char below the range draws '?'"),
                () -> assertEquals(7f, t.width('é'), "and advances like '?'"));
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
