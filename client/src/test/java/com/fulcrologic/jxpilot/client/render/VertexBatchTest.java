package com.fulcrologic.jxpilot.client.render;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VertexBatchTest {
    static final int RED = Palette.pack(255, 0, 0, 255);
    static final int GREEN = Palette.pack(0, 255, 0, 255);

    /** Records every flush with the vertex/command counts and first vertex x at that time. */
    static final class Spy implements VertexBatch.Sink {
        final List<int[]> flushes = new ArrayList<>();
        final List<Float> firstX = new ArrayList<>();

        @Override
        public void flush(VertexBatch b) {
            flushes.add(new int[] {b.vertexCount(), b.commandCount()});
            firstX.add(b.x(0));
        }
    }

    static VertexBatch fixed(int vertices, int commands, Spy spy) {
        return VertexBatch.fixed(ByteBuffer.allocateDirect(vertices * VertexBatch.STRIDE), commands, spy);
    }

    @Nested
    class Primitives {
        @Test
        void lineWritesTwoSolidVertices() {
            VertexBatch b = fixed(16, 4, new Spy());
            b.line(1, 2, 3, 4, RED);
            assertAll(
                () -> assertEquals(2, b.vertexCount(), "two vertices"),
                () -> assertEquals(1f, b.x(0), "first x"),
                () -> assertEquals(2f, b.y(0), "first y"),
                () -> assertEquals(3f, b.x(1), "second x"),
                () -> assertEquals(4f, b.y(1), "second y"),
                () -> assertEquals(0f, b.u(0), "solid shapes use u = 0 (white atlas block)"),
                () -> assertEquals(0f, b.v(1), "solid shapes use v = 0"),
                () -> assertEquals(RED, b.color(0), "stores the colour"),
                () -> assertEquals(VertexBatch.LINES, b.commandMode(0), "as a line command"));
        }

        @Test
        void triangleWritesThreeVerticesInOrder() {
            VertexBatch b = fixed(16, 4, new Spy());
            b.triangle(1, 2, 3, 4, 5, 6, GREEN);
            assertAll(
                () -> assertEquals(3, b.vertexCount(), "three vertices"),
                () -> assertEquals(5f, b.x(2), "third x"),
                () -> assertEquals(6f, b.y(2), "third y"),
                () -> assertEquals(GREEN, b.color(2), "colour on every vertex"),
                () -> assertEquals(VertexBatch.TRIANGLES, b.commandMode(0), "as a triangle command"));
        }

        @Test
        void fillRectIsTwoTrianglesCoveringTheCorners() {
            VertexBatch b = fixed(16, 4, new Spy());
            b.fillRect(0, 0, 10, 5, RED);
            float[][] expected = {{0, 0}, {10, 0}, {10, 5}, {0, 0}, {10, 5}, {0, 5}};
            assertEquals(6, b.vertexCount(), "six vertices");
            for (int i = 0; i < 6; i++) {
                int k = i;
                assertAll(
                    () -> assertEquals(expected[k][0], b.x(k), "vertex " + k + " x"),
                    () -> assertEquals(expected[k][1], b.y(k), "vertex " + k + " y"));
            }
        }

        @Test
        void strokeRectIsFourEdgesBottomRightTopLeft() {
            VertexBatch b = fixed(16, 4, new Spy());
            b.strokeRect(0, 0, 10, 5, RED);
            float[] xs = {0, 10, 10, 10, 10, 0, 0, 0};
            float[] ys = {0, 0, 0, 5, 5, 5, 5, 0};
            assertEquals(8, b.vertexCount(), "four lines");
            for (int i = 0; i < 8; i++) {
                int k = i;
                assertAll(
                    () -> assertEquals(xs[k], b.x(k), "vertex " + k + " x"),
                    () -> assertEquals(ys[k], b.y(k), "vertex " + k + " y"));
            }
        }

        @Test
        void texRectMapsUvCornersToPositionCorners() {
            VertexBatch b = fixed(16, 4, new Spy());
            b.texRect(0, 0, 10, 5, 0.25f, 0.5f, 0.75f, 1f, RED);
            float tol = 1f / 65535; // 16-bit normalized storage
            assertAll(
                () -> assertEquals(0.25f, b.u(0), tol, "u0 at x0"),
                () -> assertEquals(0.5f, b.v(0), tol, "v0 at y0"),
                () -> assertEquals(0.75f, b.u(1), tol, "u1 at x1"),
                () -> assertEquals(0.5f, b.v(1), tol, "v0 at y0 on the right"),
                () -> assertEquals(1f, b.v(2), tol, "v1 at y1"),
                () -> assertEquals(0.25f, b.u(5), tol, "u0 at the top-left"),
                () -> assertEquals(1f, b.v(5), tol, "v1 at the top-left"));
        }

        @Test
        void unorm16ClampsAndRounds() {
            assertAll(
                () -> assertEquals((short) 0, VertexBatch.unorm16(-1), "below 0 clamps to 0"),
                () -> assertEquals((short) 0xFFFF, VertexBatch.unorm16(2), "above 1 clamps to 65535"),
                () -> assertEquals((short) 32768, VertexBatch.unorm16(0.5f), "0.5 rounds to 32768"),
                () -> assertEquals((short) 0xFFFF, VertexBatch.unorm16(1), "1 maps to 65535"));
        }
    }

    @Nested
    class Transform {
        @Test
        void positionsAreScaledThenTranslated() {
            VertexBatch b = fixed(16, 4, new Spy());
            b.setTransform(2, 3, 10, 20);
            b.line(1, 1, 0, 0, RED);
            b.identity();
            b.line(1, 1, 0, 0, RED);
            assertAll(
                () -> assertEquals(12f, b.x(0), "x * 2 + 10"),
                () -> assertEquals(23f, b.y(0), "y * 3 + 20"),
                () -> assertEquals(10f, b.x(1), "origin maps to the translation x"),
                () -> assertEquals(20f, b.y(1), "origin maps to the translation y"),
                () -> assertEquals(1f, b.x(2), "identity leaves x"),
                () -> assertEquals(1f, b.y(2), "identity leaves y"),
                () -> assertEquals(1f, b.scaleX(), "identity scale x"),
                () -> assertEquals(0f, b.translateY(), "identity translation y"));
        }

        @Test
        void accessorsReportTheTransform() {
            VertexBatch b = fixed(16, 4, new Spy());
            b.setTransform(2, 3, 4, 5);
            assertAll(
                () -> assertEquals(2f, b.scaleX(), "scale x"),
                () -> assertEquals(3f, b.scaleY(), "scale y"),
                () -> assertEquals(4f, b.translateX(), "translate x"),
                () -> assertEquals(5f, b.translateY(), "translate y"));
        }
    }

    @Nested
    class Commands {
        @Test
        void sameModeExtendsAndModeChangeStartsANewCommand() {
            VertexBatch b = fixed(64, 8, new Spy());
            b.line(0, 0, 1, 1, RED);
            b.line(0, 0, 1, 1, RED);
            b.triangle(0, 0, 1, 0, 0, 1, RED);
            b.line(0, 0, 1, 1, RED);
            assertAll(
                () -> assertEquals(3, b.commandCount(), "lines, triangles, lines"),
                () -> assertEquals(0, b.commandFirst(0), "first command starts at 0"),
                () -> assertEquals(4, b.commandVertices(0), "two lines merged into one command"),
                () -> assertEquals(4, b.commandFirst(1), "triangles start after the lines"),
                () -> assertEquals(3, b.commandVertices(1), "one triangle"),
                () -> assertEquals(VertexBatch.TRIANGLES, b.commandMode(1), "second command is triangles"),
                () -> assertEquals(7, b.commandFirst(2), "third command starts after the triangle"),
                () -> assertEquals(VertexBatch.LINES, b.commandMode(2), "third command is lines"));
        }

        @Test
        void clearDropsVerticesAndCommandsButKeepsTheTransform() {
            VertexBatch b = fixed(16, 4, new Spy());
            b.setTransform(2, 2, 1, 1);
            b.line(0, 0, 1, 1, RED);
            b.clear();
            assertAll(
                () -> assertEquals(0, b.vertexCount(), "no vertices"),
                () -> assertEquals(0, b.commandCount(), "no commands"),
                () -> assertEquals(2f, b.scaleX(), "transform kept"));
        }
    }

    @Nested
    class Overflow {
        @Test
        void fixedBatchFlushesWhenVerticesRunOutAndContinuesEmpty() {
            Spy spy = new Spy();
            VertexBatch b = fixed(5, 8, spy);
            b.line(1, 0, 0, 0, RED);
            b.line(2, 0, 0, 0, RED);
            b.line(3, 0, 0, 0, RED); // 6 > 5: flush first
            assertAll(
                () -> assertEquals(1, spy.flushes.size(), "flushed once"),
                () -> assertEquals(4, spy.flushes.get(0)[0], "the flushed batch held the first two lines"),
                () -> assertEquals(1, spy.flushes.get(0)[1], "as one command"),
                () -> assertEquals(1f, spy.firstX.get(0), "starting with the first line"),
                () -> assertEquals(2, b.vertexCount(), "the third line is in the emptied batch"),
                () -> assertEquals(3f, b.x(0), "at index 0"),
                () -> assertEquals(1, b.commandCount(), "under a fresh command"));
        }

        @Test
        void exactlyFullDoesNotFlush() {
            Spy spy = new Spy();
            VertexBatch b = fixed(4, 8, spy);
            b.line(0, 0, 0, 0, RED);
            b.line(0, 0, 0, 0, RED);
            assertAll(
                () -> assertEquals(0, spy.flushes.size(), "filling to capacity is fine"),
                () -> assertEquals(4, b.vertexCount(), "all four vertices kept"),
                () -> assertEquals(4, b.capacity(), "capacity is bytes / stride"));
        }

        @Test
        void fixedBatchFlushesWhenCommandsRunOut() {
            Spy spy = new Spy();
            VertexBatch b = fixed(64, 2, spy);
            b.line(0, 0, 0, 0, RED);
            b.triangle(0, 0, 0, 0, 0, 0, RED);
            b.line(0, 0, 0, 0, RED); // needs a third command
            b.line(0, 0, 0, 0, RED); // extends it: no flush
            assertAll(
                () -> assertEquals(1, spy.flushes.size(), "flushed once when a third command was needed"),
                () -> assertEquals(2, spy.flushes.get(0)[1], "with both earlier commands"),
                () -> assertEquals(4, b.vertexCount(), "the later lines continue in the emptied batch"),
                () -> assertEquals(1, b.commandCount(), "as one command"));
        }

        @Test
        void growableBatchGrowsAndKeepsContents() {
            VertexBatch b = VertexBatch.growable(2);
            for (int i = 0; i < 100; i++) {
                b.line(i, 0, 0, 0, RED);
                b.triangle(i, 0, 0, 0, 0, 0, GREEN);
            }
            assertAll(
                () -> assertEquals(500, b.vertexCount(), "all vertices kept"),
                () -> assertTrue(b.capacity() >= 500, "capacity grew"),
                () -> assertEquals(200, b.commandCount(), "commands grew past the initial 64"),
                () -> assertEquals(0f, b.x(0), "first line kept"),
                () -> assertEquals(99f, b.x(495), "last line kept"),
                () -> assertEquals(GREEN, b.color(499), "last triangle colour kept"));
        }
    }
}
