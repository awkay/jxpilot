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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VertexBatchTest {
    static final int RED = Palette.pack(255, 0, 0, 255);
    static final int GREEN = Palette.pack(0, 255, 0, 255);

    /** Records every flush: triangle vertices, segments, points and commands at that time, and the first segment x. */
    static final class Spy implements VertexBatch.Sink {
        final List<int[]> flushes = new ArrayList<>();
        final List<Float> firstSegX = new ArrayList<>();

        @Override
        public void flush(VertexBatch b) {
            flushes.add(new int[] {b.vertexCount(), b.segmentCount(), b.pointCount(), b.commandCount()});
            firstSegX.add(b.segmentCount() > 0 ? b.segX1(0) : Float.NaN);
        }
    }

    /** A fixed batch with {@code tri} triangle vertices, {@code seg} segments and {@code pt} points. */
    static VertexBatch fixed(int tri, int seg, int pt, int commands, Spy spy) {
        return VertexBatch.fixed(ByteBuffer.allocateDirect(tri * VertexBatch.STRIDE),
            ByteBuffer.allocateDirect(seg * VertexBatch.SEGMENT_STRIDE),
            ByteBuffer.allocateDirect(pt * VertexBatch.POINT_STRIDE), commands, spy);
    }

    static VertexBatch roomy() {
        return Fixtures.batch(64, 8, b -> { });
    }

    @Nested
    class Primitives {
        @Test
        void lineIsOneSegmentInstanceWithTheCurrentWidth() {
            VertexBatch b = roomy();
            b.line(1, 2, 3, 4, RED);
            b.setLineWidth(2.5f);
            b.line(5, 6, 7, 8, GREEN);
            assertAll(
                () -> assertEquals(2, b.segmentCount(), "two segments"),
                () -> assertEquals(0, b.vertexCount(), "no triangle vertices"),
                () -> assertEquals(1f, b.segX1(0), "start x"),
                () -> assertEquals(2f, b.segY1(0), "start y"),
                () -> assertEquals(3f, b.segX2(0), "end x"),
                () -> assertEquals(4f, b.segY2(0), "end y"),
                () -> assertEquals(RED, b.segColor(0), "colour"),
                () -> assertEquals(VertexBatch.DEFAULT_LINE_WIDTH, b.segWidth(0), "default width 1 logical pixel"),
                () -> assertEquals(1f, VertexBatch.DEFAULT_LINE_WIDTH, "XPilot's lines are one pixel"),
                () -> assertEquals(2.5f, b.segWidth(1), "the width set before the second line"),
                () -> assertEquals(2.5f, b.lineWidth(), "width reads back"),
                () -> assertEquals(7f, b.segX2(1), "second segment's own end x"),
                () -> assertEquals(8f, b.segY2(1), "second segment's own end y"),
                () -> assertEquals(GREEN, b.segColor(1), "second segment's colour"),
                () -> assertEquals(VertexBatch.LINES, b.commandMode(0), "as one segment command"));
        }

        @Test
        void pointIsOneInstanceWithCentreSizeAndColour() {
            VertexBatch b = roomy();
            b.point(1, 2, 3, RED);
            b.point(4, 5, 6, GREEN);
            assertAll(
                () -> assertEquals(2, b.pointCount(), "two points"),
                () -> assertEquals(4f, b.pointX(1), "centre x"),
                () -> assertEquals(5f, b.pointY(1), "centre y"),
                () -> assertEquals(6f, b.pointSize(1), "side in logical pixels"),
                () -> assertEquals(GREEN, b.pointColor(1), "colour"),
                () -> assertEquals(3f, b.pointSize(0), "first point's own size"),
                () -> assertEquals(RED, b.pointColor(0), "first point's own colour"),
                () -> assertEquals(1, b.commandCount(), "one command"),
                () -> assertEquals(VertexBatch.POINTS, b.commandMode(0), "of points"));
        }

        @Test
        void triangleWritesThreeVerticesInOrder() {
            VertexBatch b = roomy();
            b.triangle(1, 2, 3, 4, 5, 6, GREEN);
            assertAll(
                () -> assertEquals(3, b.vertexCount(), "three vertices"),
                () -> assertEquals(1f, b.x(0), "first x"),
                () -> assertEquals(2f, b.y(0), "first y"),
                () -> assertEquals(3f, b.x(1), "second x"),
                () -> assertEquals(4f, b.y(1), "second y"),
                () -> assertEquals(5f, b.x(2), "third x"),
                () -> assertEquals(6f, b.y(2), "third y"),
                () -> assertEquals(0f, b.u(0), "solid shapes use u = 0 (white atlas block)"),
                () -> assertEquals(0f, b.v(1), "solid shapes use v = 0"),
                () -> assertEquals(GREEN, b.color(2), "colour on every vertex"),
                () -> assertEquals(VertexBatch.TRIANGLES, b.commandMode(0), "as a triangle command"));
        }

        @Test
        void fillRectIsTwoTrianglesCoveringTheCorners() {
            VertexBatch b = roomy();
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
        void strokeRectIsFourSegmentsBottomRightTopLeft() {
            VertexBatch b = roomy();
            b.strokeRect(0, 0, 10, 5, RED);
            float[][] e = {{0, 0, 10, 0}, {10, 0, 10, 5}, {10, 5, 0, 5}, {0, 5, 0, 0}};
            assertEquals(4, b.segmentCount(), "four segments");
            for (int i = 0; i < 4; i++) {
                int k = i;
                assertAll(
                    () -> assertEquals(e[k][0], b.segX1(k), "segment " + k + " x1"),
                    () -> assertEquals(e[k][1], b.segY1(k), "segment " + k + " y1"),
                    () -> assertEquals(e[k][2], b.segX2(k), "segment " + k + " x2"),
                    () -> assertEquals(e[k][3], b.segY2(k), "segment " + k + " y2"));
            }
        }

        @Test
        void texRectMapsUvCornersToPositionCorners() {
            VertexBatch b = roomy();
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
        void positionsAreScaledThenTranslatedButWidthsAndSizesAreNot() {
            VertexBatch b = roomy();
            b.setTransform(2, 3, 10, 20);
            b.line(1, 1, 0, 0, RED);
            b.point(1, 1, 4, RED);
            b.triangle(1, 1, 0, 0, 0, 0, RED);
            b.identity();
            b.line(1, 1, 0, 0, RED);
            assertAll(
                () -> assertEquals(12f, b.segX1(0), "x * 2 + 10"),
                () -> assertEquals(23f, b.segY1(0), "y * 3 + 20"),
                () -> assertEquals(10f, b.segX2(0), "origin maps to the translation x"),
                () -> assertEquals(20f, b.segY2(0), "origin maps to the translation y"),
                () -> assertEquals(1f, b.segWidth(0), "line width is not scaled"),
                () -> assertEquals(12f, b.pointX(0), "point centre x transformed"),
                () -> assertEquals(23f, b.pointY(0), "point centre y transformed"),
                () -> assertEquals(4f, b.pointSize(0), "point size is not scaled"),
                () -> assertEquals(12f, b.x(0), "triangle x transformed"),
                () -> assertEquals(23f, b.y(0), "triangle y transformed"),
                () -> assertEquals(1f, b.segX1(1), "identity leaves x"),
                () -> assertEquals(1f, b.segY1(1), "identity leaves y"),
                () -> assertEquals(1f, b.scaleX(), "identity scale x"),
                () -> assertEquals(0f, b.translateY(), "identity translation y"));
        }

        @Test
        void accessorsReportTheTransform() {
            VertexBatch b = roomy();
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
        void sameKindExtendsAndKindChangeStartsACommandAtThatStreamsPosition() {
            VertexBatch b = roomy();
            b.line(0, 0, 1, 1, RED);
            b.line(0, 0, 1, 1, RED);
            b.triangle(0, 0, 1, 0, 0, 1, RED);
            b.line(0, 0, 1, 1, RED);
            b.point(0, 0, 1, RED);
            b.triangle(0, 0, 1, 0, 0, 1, RED);
            assertAll(
                () -> assertEquals(5, b.commandCount(), "lines, triangles, lines, points, triangles"),
                () -> assertEquals(0, b.commandFirst(0), "first command starts at segment 0"),
                () -> assertEquals(2, b.commandLength(0), "two lines merged into one command"),
                () -> assertEquals(VertexBatch.TRIANGLES, b.commandMode(1), "second command is triangles"),
                () -> assertEquals(0, b.commandFirst(1), "starting at triangle vertex 0"),
                () -> assertEquals(3, b.commandLength(1), "one triangle"),
                () -> assertEquals(VertexBatch.LINES, b.commandMode(2), "third command is lines"),
                () -> assertEquals(2, b.commandFirst(2), "continuing at segment 2"),
                () -> assertEquals(1, b.commandLength(2), "one line"),
                () -> assertEquals(VertexBatch.POINTS, b.commandMode(3), "fourth is points"),
                () -> assertEquals(0, b.commandFirst(3), "at point 0"),
                () -> assertEquals(3, b.commandFirst(4), "the last triangle continues at vertex 3"));
        }

        @Test
        void clearDropsEverythingButKeepsTransformAndWidth() {
            VertexBatch b = roomy();
            b.setTransform(2, 2, 1, 1);
            b.setLineWidth(3);
            b.line(0, 0, 1, 1, RED);
            b.point(0, 0, 1, RED);
            b.triangle(0, 0, 1, 0, 0, 1, RED);
            b.clear();
            assertAll(
                () -> assertEquals(0, b.vertexCount(), "no vertices"),
                () -> assertEquals(0, b.segmentCount(), "no segments"),
                () -> assertEquals(0, b.pointCount(), "no points"),
                () -> assertEquals(0, b.commandCount(), "no commands"),
                () -> assertEquals(2f, b.scaleX(), "transform kept"),
                () -> assertEquals(3f, b.lineWidth(), "line width kept"));
        }
    }

    @Test
    void buffersExposeTheStoredItemsAndGrowableStartsAtItsInitialCapacity() {
        VertexBatch b = roomy();
        b.triangle(1, 2, 3, 4, 5, 6, RED);
        b.line(7, 8, 9, 10, RED);
        b.point(11, 12, 13, RED);
        VertexBatch g = VertexBatch.growable(5);
        assertAll(
            () -> assertEquals(5f, b.triangleBuffer().getFloat(2 * VertexBatch.STRIDE), "third vertex x in the triangle buffer"),
            () -> assertEquals(9f, b.segmentBuffer().getFloat(8), "segment end x in the segment buffer"),
            () -> assertEquals(13f, b.pointBuffer().getFloat(8), "point size in the point buffer"),
            () -> assertEquals(5, g.triangleCapacity(), "growable: room for the initial triangle vertices"),
            () -> assertEquals(5, g.segmentCapacity(), "segments"),
            () -> assertEquals(5, g.pointCapacity(), "and points"));
    }

    @Nested
    class Overflow {
        @Test
        void fixedBatchFlushesWhenSegmentsRunOutAndContinuesEmpty() {
            Spy spy = new Spy();
            VertexBatch b = fixed(16, 2, 4, 8, spy);
            b.line(1, 0, 0, 0, RED);
            b.line(2, 0, 0, 0, RED);
            b.line(3, 0, 0, 0, RED); // 3 > 2: flush first
            assertAll(
                () -> assertEquals(1, spy.flushes.size(), "flushed once"),
                () -> assertEquals(2, spy.flushes.get(0)[1], "the flushed batch held the first two lines"),
                () -> assertEquals(1, spy.flushes.get(0)[3], "as one command"),
                () -> assertEquals(1f, spy.firstSegX.get(0), "starting with the first line"),
                () -> assertEquals(1, b.segmentCount(), "the third line is in the emptied batch"),
                () -> assertEquals(3f, b.segX1(0), "at index 0"),
                () -> assertEquals(1, b.commandCount(), "under a fresh command"));
        }

        @Test
        void fixedBatchFlushesWhenTrianglesOrPointsRunOut() {
            Spy triSpy = new Spy();
            VertexBatch t = fixed(5, 4, 4, 8, triSpy);
            t.triangle(0, 0, 0, 0, 0, 0, RED);
            t.triangle(9, 0, 0, 0, 0, 0, RED); // 6 > 5
            Spy ptSpy = new Spy();
            VertexBatch p = fixed(4, 4, 1, 8, ptSpy);
            p.point(0, 0, 1, RED);
            p.point(7, 0, 1, RED); // 2 > 1
            assertAll(
                () -> assertEquals(1, triSpy.flushes.size(), "triangle overflow flushes"),
                () -> assertEquals(3, triSpy.flushes.get(0)[0], "with the first triangle"),
                () -> assertEquals(9f, t.x(0), "the second triangle starts the emptied batch"),
                () -> assertEquals(1, ptSpy.flushes.size(), "point overflow flushes"),
                () -> assertEquals(1, ptSpy.flushes.get(0)[2], "with the first point"),
                () -> assertEquals(7f, p.pointX(0), "the second point starts the emptied batch"));
        }

        @Test
        void exactlyFullDoesNotFlush() {
            Spy spy = new Spy();
            VertexBatch b = fixed(6, 2, 1, 8, spy);
            b.line(0, 0, 0, 0, RED);
            b.line(0, 0, 0, 0, RED);
            b.fillRect(0, 0, 1, 1, RED);
            b.point(0, 0, 1, RED);
            assertAll(
                () -> assertEquals(0, spy.flushes.size(), "filling every stream to capacity is fine"),
                () -> assertEquals(2, b.segmentCount(), "both segments kept"),
                () -> assertEquals(6, b.vertexCount(), "all six vertices kept"),
                () -> assertEquals(6, b.triangleCapacity(), "triangle capacity is bytes / stride"),
                () -> assertEquals(2, b.segmentCapacity(), "segment capacity is bytes / stride"),
                () -> assertEquals(1, b.pointCapacity(), "point capacity is bytes / stride"));
        }

        @Test
        void fixedBatchFlushesWhenCommandsRunOut() {
            Spy spy = new Spy();
            VertexBatch b = fixed(64, 64, 64, 2, spy);
            b.line(0, 0, 0, 0, RED);
            b.triangle(0, 0, 0, 0, 0, 0, RED);
            b.line(0, 0, 0, 0, RED); // needs a third command
            b.line(0, 0, 0, 0, RED); // extends it: no flush
            assertAll(
                () -> assertEquals(1, spy.flushes.size(), "flushed once when a third command was needed"),
                () -> assertEquals(2, spy.flushes.get(0)[3], "with both earlier commands"),
                () -> assertEquals(2, b.segmentCount(), "the later lines continue in the emptied batch"),
                () -> assertEquals(1, b.commandCount(), "as one command"));
        }

        @Test
        void growableBatchGrowsToFitAQuadLargerThanDoubleItsCapacity() {
            VertexBatch b = VertexBatch.growable(1);
            b.fillRect(0, 0, 4, 2, RED);
            assertAll(
                () -> assertEquals(6, b.vertexCount(), "all six vertices fit"),
                () -> assertEquals(6, b.triangleCapacity(), "grown to count + n = 6, more than double the 1"),
                () -> assertEquals(2f, b.y(5), "the last vertex is stored"));
        }

        @Test
        void growableBatchGrowsEveryStreamAndKeepsEveryItem() {
            VertexBatch b = VertexBatch.growable(2);
            int n = 100;
            for (int i = 0; i < n; i++) {
                b.line(i + 1, i + 2, i + 3, i + 4, i + 5);
                b.triangle(i + 6, i + 7, i + 8, i + 9, i + 10, i + 11, i + 12);
                b.point(i + 13, i + 14, i + 15, i + 16);
            }
            assertAll(
                () -> assertEquals(n, b.segmentCount(), "all segments kept"),
                () -> assertEquals(3 * n, b.vertexCount(), "all triangle vertices kept"),
                () -> assertEquals(n, b.pointCount(), "all points kept"),
                () -> assertEquals(3 * n, b.commandCount(), "commands grew past the initial 64"),
                () -> assertTrue(b.segmentCapacity() >= n && b.pointCapacity() >= n && b.triangleCapacity() >= 3 * n,
                    "every stream grew"));
            for (int i = 0; i < n; i++) {
                int k = i;
                assertAll("item " + k,
                    () -> assertEquals(k + 1f, b.segX1(k), "segment x1 survives growth"),
                    () -> assertEquals(k + 2f, b.segY1(k), "segment y1"),
                    () -> assertEquals(k + 3f, b.segX2(k), "segment x2"),
                    () -> assertEquals(k + 4f, b.segY2(k), "segment y2"),
                    () -> assertEquals(k + 5, b.segColor(k), "segment colour"),
                    () -> assertEquals(k + 6f, b.x(3 * k), "triangle first x"),
                    () -> assertEquals(k + 9f, b.y(3 * k + 1), "triangle second y"),
                    () -> assertEquals(k + 11f, b.y(3 * k + 2), "triangle third y"),
                    () -> assertEquals(k + 12, b.color(3 * k + 2), "triangle colour"),
                    () -> assertEquals(k + 13f, b.pointX(k), "point x"),
                    () -> assertEquals(k + 15f, b.pointSize(k), "point size"),
                    () -> assertEquals(k + 16, b.pointColor(k), "point colour"),
                    () -> assertEquals(k, b.commandFirst(3 * k + 2), "the k-th point command starts at point k"));
            }
        }
    }
}
