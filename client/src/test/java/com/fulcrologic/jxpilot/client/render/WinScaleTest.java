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

import static org.junit.jupiter.api.Assertions.*;

class WinScaleTest {
    @Test
    void ofIsCsScaleArrayAtTheZoom() {
        assertAll(
            () -> assertEquals(0, WinScale.of(0, 2), "0 stays 0"),
            () -> assertEquals(8, WinScale.of(8, 1), "zoom 1: unchanged"),
            () -> assertEquals(16, WinScale.of(8, 2), "zoom 2: doubled"),
            () -> assertEquals(4, WinScale.of(3, 1.25), "floor(3.75 + 0.5) = 4: rounds"),
            () -> assertEquals(3, WinScale.of(5, 0.5), "floor(2.5 + 0.5) = 3: halves round up"),
            () -> assertEquals(2, WinScale.of(5, 0.45), "floor(2.25 + 0.5) = 2: rounds down below the half"),
            () -> assertEquals(1, WinScale.of(1, 0.25), "floor(0.75) = 0 is kept at 1: never below one pixel"),
            () -> assertEquals(1, WinScale.of(3, 0.1), "floor(0.8) = 0 -> 1"),
            () -> assertEquals(-16, WinScale.of(-8, 2), "negative: the negated magnitude"),
            () -> assertEquals(-1, WinScale.of(-1, 0.1), "negative small: -1"),
            () -> assertEquals(WinScale.MAX, WinScale.of(40000, 1), "capped at the largest short"));
    }

    @Test
    void squareShiftIsTheScaledCentreLessTheContinuousOne() {
        assertAll(
            () -> assertEquals(0, WinScale.squareShift(2, 1), 1e-12, "zoom 1: no shift"),
            () -> assertEquals(0.5, WinScale.squareShift(2, 2), 1e-12, "side 4: (3 - 1 * 2) / 2"),
            () -> assertEquals(-0.25, WinScale.squareShift(2, 0.5), 1e-12, "side 1: (0 - 0.5) / 2"),
            () -> assertEquals(0.25, WinScale.squareShift(3, 1.25), 1e-12, "side 4: (3 - 2 * 1.25) / 2"));
    }

    @Test
    void zoomOfIsOneOverTheLimitedScaleFactor() {
        assertAll(
            () -> assertEquals(1, WinScale.zoomOf(1), 1e-12, "1.0 (scaleFactor default)"),
            () -> assertEquals(0.5, WinScale.zoomOf(2), 1e-12, "2.0 (altScaleFactor default)"),
            () -> assertEquals(1, WinScale.zoomOf(0), 1e-12, "0 means 1, as default.c"),
            () -> assertEquals(5, WinScale.zoomOf(0.1), 1e-12, "limited to MIN_SCALEFACTOR 0.2"),
            () -> assertEquals(0.125, WinScale.zoomOf(20), 1e-12, "limited to MAX_SCALEFACTOR 8"));
    }
}
