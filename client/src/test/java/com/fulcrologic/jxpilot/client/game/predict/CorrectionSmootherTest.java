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
package com.fulcrologic.jxpilot.client.game.predict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link CorrectionSmoother}: corrections become a decaying offset; large ones snap. */
class CorrectionSmootherTest {
    static final long MS = 1_000_000L;

    @Nested
    @DisplayName("corrections")
    class Corrections {
        @Test
        @DisplayName("a correction becomes the opposite offset, and corrections add up")
        void opposite() {
            CorrectionSmoother s = new CorrectionSmoother();
            s.correct(3, -4, 2, false);
            double x1 = s.offsetX();
            double y1 = s.offsetY();
            double h1 = s.offsetHeading();
            double px1 = s.offsetPx();
            s.correct(1, 1, -1, false);
            assertAll(
                () -> assertEquals(-3, x1, "x offset is minus the move"),
                () -> assertEquals(4, y1, "y offset is minus the move"),
                () -> assertEquals(-2, h1, "heading offset is minus the turn"),
                () -> assertEquals(5, px1, 1e-12, "offsetPx is the length of the position offset"),
                () -> assertEquals(-4, s.offsetX(), "a second correction adds to x"),
                () -> assertEquals(3, s.offsetY(), "a second correction adds to y"),
                () -> assertEquals(-1, s.offsetHeading(), "a second correction adds to the heading"),
                () -> assertEquals(0, s.snaps(), "no snap"));
        }

        @Test
        @DisplayName("an asked-for snap drops the offset without counting; a too-large one drops it and counts")
        void snaps() {
            CorrectionSmoother s = new CorrectionSmoother();
            s.correct(10, 0, 3, false);
            s.correct(1, 1, 1, true);
            double xAsked = s.offsetX();
            double hAsked = s.offsetHeading();
            long asked = s.snaps();
            s.correct(CorrectionSmoother.SNAP_PX - 1, 0, 0, false);
            double xEdge = s.offsetX();
            s.correct(2, 0, 5, false);
            assertAll(
                () -> assertEquals(0, xAsked, "an asked-for snap clears the position"),
                () -> assertEquals(0, hAsked, "an asked-for snap clears the heading"),
                () -> assertEquals(0, asked, "an asked-for snap is not counted"),
                () -> assertEquals(-(CorrectionSmoother.SNAP_PX - 1), xEdge, "just under SNAP_PX glides"),
                () -> assertEquals(0, s.offsetX(), "over SNAP_PX in total snaps"),
                () -> assertEquals(0, s.offsetHeading(), "the snap clears the heading too"),
                () -> assertEquals(1, s.snaps(), "the unrequested snap is counted"));
        }

        @Test
        @DisplayName("exactly SNAP_PX still glides")
        void boundary() {
            CorrectionSmoother s = new CorrectionSmoother();
            s.correct(0, CorrectionSmoother.SNAP_PX, 0, false);
            assertAll(
                () -> assertEquals(-CorrectionSmoother.SNAP_PX, s.offsetY(), "the offset is kept"),
                () -> assertEquals(0, s.snaps(), "not a snap"));
        }
    }

    @Nested
    @DisplayName("decay")
    class Decay {
        @Test
        @DisplayName("the first decay only starts the clock; then offsets halve every half-life")
        void halves() {
            CorrectionSmoother s = new CorrectionSmoother();
            s.correct(-8, -4, -2, false);
            s.decay(1000 * MS, false);
            double x0 = s.offsetX();
            s.decay(1100 * MS, false);
            double x1 = s.offsetX();
            double y1 = s.offsetY();
            double h1 = s.offsetHeading();
            s.decay(1150 * MS, false);
            assertAll(
                () -> assertEquals(8, x0, "the first call does not decay"),
                () -> assertEquals(4, x1, 1e-9, "one half-life (100 ms) halves x"),
                () -> assertEquals(2, y1, 1e-9, "and y"),
                () -> assertEquals(1, h1, 1e-9, "and the heading"),
                () -> assertEquals(4 * Math.sqrt(0.5), s.offsetX(), 1e-9, "half a half-life: times sqrt(1/2)"));
        }

        @Test
        @DisplayName("near walls the wall half-life applies; the tunable half-life applies elsewhere")
        void wall() {
            CorrectionSmoother s = new CorrectionSmoother();
            s.correct(-8, 0, 0, false);
            s.decay(0, true);
            s.decay(30 * MS, true);
            double wall = s.offsetX();
            CorrectionSmoother t = new CorrectionSmoother();
            t.halfLifeSeconds = 0.05;
            t.correct(-8, 0, 0, false);
            t.decay(0, false);
            t.decay(50 * MS, false);
            assertAll(
                () -> assertEquals(4, wall, 1e-9, "30 ms at a wall halves"),
                () -> assertEquals(4, t.offsetX(), 1e-9, "a 50 ms half-life halves in 50 ms"));
        }

        @Test
        @DisplayName("time going backwards or standing still changes nothing")
        void backwards() {
            CorrectionSmoother s = new CorrectionSmoother();
            s.correct(-8, 0, 0, false);
            s.decay(1000 * MS, false);
            s.decay(900 * MS, false);
            s.decay(1000 * MS, false);
            double x = s.offsetX();
            s.decay(1100 * MS, false);
            assertAll(
                () -> assertEquals(8, x, "an earlier or equal time does not decay"),
                () -> assertEquals(4, s.offsetX(), 1e-9, "the clock was not moved back by the earlier time"));
        }

        @Test
        @DisplayName("an offset under EPSILON on every axis becomes 0; one axis over keeps all")
        void epsilon() {
            CorrectionSmoother s = new CorrectionSmoother();
            s.correct(-1.5e-3, 0, 0, false);
            s.decay(0, false);
            s.decay(100 * MS, false);
            CorrectionSmoother t = new CorrectionSmoother();
            t.correct(-1.5e-3, -1, 0, false);
            t.decay(0, false);
            t.decay(100 * MS, false);
            assertAll(
                () -> assertEquals(0, s.offsetX(), "0.75e-3 px is dropped"),
                () -> assertEquals(0.75e-3, t.offsetX(), 1e-12, "kept while another axis is over epsilon"));
        }

        @Test
        @DisplayName("clear drops the offset and restarts the clock")
        void clear() {
            CorrectionSmoother s = new CorrectionSmoother();
            s.decay(0, false);
            s.correct(-8, -8, -8, false);
            s.clear();
            double x = s.offsetX();
            double y = s.offsetY();
            double h = s.offsetHeading();
            s.correct(-8, 0, 0, false);
            s.decay(500 * MS, false);
            assertAll(
                () -> assertEquals(0, x, "x cleared"),
                () -> assertEquals(0, y, "y cleared"),
                () -> assertEquals(0, h, "heading cleared"),
                () -> assertEquals(8, s.offsetX(), "the first decay after clear only starts the clock"));
        }
    }
}
