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
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DashesTest {
    static final int C = 0xFF00FF00;
    /** Positions are sums of float lengths. */
    static final float EPS = 1e-4f;

    static VertexBatch batch() {
        return Fixtures.batch(256, 8, b -> { });
    }

    static List<String> segs(VertexBatch b) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b.segmentCount(); i++) {
            out.add(r(b.segX1(i)) + "," + r(b.segY1(i)) + "-" + r(b.segX2(i)) + "," + r(b.segY2(i)));
        }
        return out;
    }

    static String r(float v) {
        float q = Math.round(v * 1000) / 1000f;
        return q == Math.rint(q) ? Integer.toString((int) q) : Float.toString(q);
    }

    @Test
    void aStraightLineAlternatesOnAndOffFromOffsetZero() {
        VertexBatch b = batch();
        float end = Dashes.segment(b, 0, 0, 30, 0, 8, 4, 0, 1, C);
        assertAll(
            () -> assertEquals(List.of("0,0-8,0", "12,0-20,0", "24,0-30,0"), segs(b), "8 on, 4 off, repeated; last dash cut at the end"),
            () -> assertEquals(6f, end, EPS, "ends 6 pixels into an 'on' stretch"),
            () -> assertEquals(C, b.segColor(0), "in the given colour"));
    }

    @Test
    void theOffsetStartsPartWayIntoThePattern() {
        VertexBatch b = batch();
        Dashes.polyline(b, new float[] {0, 20}, new float[] {0, 0}, 2, false, 8, 4, 10, 1, C);
        VertexBatch c = batch();
        Dashes.polyline(c, new float[] {0, 20}, new float[] {0, 0}, 2, false, 8, 4, 22, 1, C);
        VertexBatch d = batch();
        Dashes.polyline(d, new float[] {0, 20}, new float[] {0, 0}, 2, false, 8, 4, -2, 1, C);
        assertAll(
            () -> assertEquals(List.of("2,0-10,0", "14,0-20,0"), segs(b), "offset 10: 2 px of gap first"),
            () -> assertEquals(segs(b), segs(c), "offset 22 is the same as 10 (mod 12)"),
            () -> assertEquals(segs(b), segs(d), "a negative offset wraps too: -2 is 10"));
    }

    @Test
    void thePatternCarriesAcrossPolylineJointsAndClosesWhenAsked() {
        VertexBatch open = batch();
        float end = Dashes.polyline(open, new float[] {0, 6, 6}, new float[] {0, 0, 6}, 3, false, 8, 4, 0, 1, C);
        VertexBatch closed = batch();
        Dashes.polyline(closed, new float[] {0, 6, 6}, new float[] {0, 0, 6}, 3, true, 8, 4, 0, 1, C);
        assertAll(
            () -> assertEquals(List.of("0,0-6,0", "6,0-6,2"), segs(open), "the first dash turns the corner after 6 px"),
            () -> assertEquals(0f, end, EPS, "12 px of line end exactly one period in"),
            () -> assertEquals(List.of("0,0-6,0", "6,0-6,2", "6,6-0.343,0.343"), segs(closed),
                "closed: the 8.49 px hypotenuse starts a new 8 px dash at 12 px, ending 0.49 px short of the start"));
    }

    @Test
    void unitScalesPatternPixelsIntoTheCoordinateSpace() {
        VertexBatch b = batch();
        Dashes.segment(b, 0, 0, 10, 0, 8, 4, 0, 0.5f, C);
        assertEquals(List.of("0,0-4,0", "6,0-10,0"), segs(b), "at 2 pixels per unit an 8 px dash is 4 units long");
    }

    @Test
    void degenerateInputDrawsNothing() {
        VertexBatch b = batch();
        float p = Dashes.polyline(b, new float[] {1}, new float[] {1}, 1, true, 8, 4, 5, 1, C);
        float q = Dashes.segment(b, 3, 3, 3, 3, 8, 4, 5, 1, C);
        assertAll(
            () -> assertEquals(0, b.segmentCount(), "one point or a zero-length edge: no segments"),
            () -> assertEquals(5f, p, "the pattern position is just the offset"),
            () -> assertEquals(5f, q, "a zero-length edge does not advance it"));
    }

    @Test
    void circleIsAClosedChordPolygonFromThreeOClockCounterClockwise() {
        VertexBatch b = batch();
        float[] xs = new float[8];
        float[] ys = new float[8];
        Dashes.circle(b, 10, 20, 100, 4, xs, ys, 1000, 1, 0, 1, C);
        assertAll(
            () -> assertEquals(List.of("110,20-10,120", "10,120-(-90),20".replace("(", "").replace(")", ""),
                "-90,20-10,-80", "10,-80-110,20"), segs(b), "4 chords, all inside one long dash"),
            () -> assertEquals(110f, xs[0], EPS, "first point at angle 0"),
            () -> assertEquals(120f, ys[1], EPS, "second at 90 degrees (y up)"));
    }

    @Test
    void longEdgesWithFractionalUnitsTerminateWithOneDashPerPeriod() {
        VertexBatch b = Fixtures.batch(8192, 8, x -> { });
        // 50000 pattern pixels at a fractional unit: float sums would stall once t dwarfs the remainder
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
            () -> Dashes.segment(b, 0, 0, 50000 * 0.37f, 0, 8, 4, 7.9999f, 0.37f, C));
        assertAll(
            () -> assertEquals(4167, b.segmentCount(), 1, "about 50000 / 12 dashes"),
            () -> assertEquals(0f, b.segX1(1) - b.segX2(0) - 4 * 0.37f, 1e-2f, "a 4 px gap between dashes"));
    }

    @Test
    void anOffsetAHairBeforeTheGapStartsWithTheGap() {
        VertexBatch b = batch();
        float end = Dashes.segment(b, 0, 0, 10, 0, 8, 4, 7.99995f, 1, C);
        VertexBatch open = batch();
        float p = Dashes.polyline(open, new float[] {0, 5}, new float[] {0, 0}, 2, false, 8, 4, 0, 1, C);
        assertAll(
            () -> assertEquals(List.of("4,0-10,0"), segs(b), "no sliver dash; 4 px gap then the next dash"),
            () -> assertEquals(6f, end, EPS, "6 px into the next dash"),
            () -> assertEquals(5f, p, EPS, "a 5 px open line returns pattern position 5"));
    }
}
