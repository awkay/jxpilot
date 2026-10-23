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

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ShapesTest {
    /** Trig results are compared with a float tolerance. */
    static final float EPS = 1e-4f;
    static final int C = Palette.pack(9, 9, 9, 255);

    static VertexBatch batch() {
        return VertexBatch.fixed(ByteBuffer.allocateDirect(256 * VertexBatch.STRIDE), 4, b -> { });
    }

    @Test
    void arcIsChainedChordsFromStartSweepingCounterClockwise() {
        VertexBatch b = batch();
        Shapes.arc(b, 10, 20, 5, 90, 180, 2, C);
        assertAll(
            () -> assertEquals(4, b.vertexCount(), "two chords"),
            () -> assertEquals(10f, b.x(0), EPS, "starts at 90 degrees: top x"),
            () -> assertEquals(25f, b.y(0), EPS, "starts at 90 degrees: top y"),
            () -> assertEquals(5f, b.x(1), EPS, "first chord ends at 180 degrees: left"),
            () -> assertEquals(20f, b.y(1), EPS, "at the centre height"),
            () -> assertEquals(5f, b.x(2), EPS, "second chord starts where the first ended"),
            () -> assertEquals(10f, b.x(3), EPS, "ends at 270 degrees: bottom x"),
            () -> assertEquals(15f, b.y(3), EPS, "ends at 270 degrees: bottom y"),
            () -> assertEquals(C, b.color(3), "colour"));
    }

    @Test
    void negativeExtentSweepsClockwise() {
        VertexBatch b = batch();
        Shapes.arc(b, 0, 0, 1, 0, -90, 1, C);
        assertAll(
            () -> assertEquals(1f, b.x(0), EPS, "starts at 0 degrees"),
            () -> assertEquals(-1f, b.y(1), EPS, "ends at -90 degrees (below)"));
    }

    @Test
    void circleClosesOnItself() {
        VertexBatch b = batch();
        Shapes.circle(b, 0, 0, 2, 8, C);
        assertAll(
            () -> assertEquals(16, b.vertexCount(), "eight chords"),
            () -> assertEquals(2f, b.x(0), EPS, "starts at angle 0"),
            () -> assertEquals(2f, b.x(15), EPS, "ends where it started"),
            () -> assertEquals(0f, b.y(15), EPS, "ends where it started (y)"),
            () -> assertEquals(0f, b.x(3), EPS, "quarter way round is at the top"),
            () -> assertEquals(2f, b.y(3), EPS, "top y"));
    }

    @Test
    void closedPolylineJoinsTheLastPointToTheFirst() {
        VertexBatch b = batch();
        Shapes.closedPolyline(b, new float[] {0, 4, 4, 9}, new float[] {0, 0, 3, 9}, 3, C);
        assertAll(
            () -> assertEquals(6, b.vertexCount(), "three edges for three points (the 4th is ignored)"),
            () -> assertEquals(4f, b.x(1), "edge 0 ends at point 1"),
            () -> assertEquals(3f, b.y(3), "edge 1 ends at point 2"),
            () -> assertEquals(4f, b.x(4), "edge 2 starts at point 2"),
            () -> assertEquals(0f, b.x(5), "edge 2 ends at point 0"),
            () -> assertEquals(0f, b.y(5), "edge 2 ends at point 0 (y)"));
    }
}
