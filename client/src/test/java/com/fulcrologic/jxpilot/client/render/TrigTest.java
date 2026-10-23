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

import com.fulcrologic.jxpilot.common.Const;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrigTest {
    /** Table entries are Math.cos/sin of the step angle: exact to double rounding. */
    static final double EPS = 1e-12;

    @Test
    void degreeIndexRoundsToAnEighthAndWraps() {
        assertAll(
            () -> assertEquals(0, Trig.degIndex(0), "0 degrees is entry 0"),
            () -> assertEquals(8, Trig.degIndex(1), "one degree is 8 steps"),
            () -> assertEquals(1, Trig.degIndex(0.07), "0.07 degrees rounds up to one step (0.56)"),
            () -> assertEquals(0, Trig.degIndex(0.06), "0.06 rounds down (0.48)"),
            () -> assertEquals(0, Trig.degIndex(360), "360 wraps to 0"),
            () -> assertEquals(8, Trig.degIndex(361), "361 wraps to 1 degree"),
            () -> assertEquals(Trig.DEG_N - 8, Trig.degIndex(-1), "-1 degree wraps to 359"),
            () -> assertEquals(Trig.DEG_N - 8, Trig.degIndex(-361), "-361 too"));
    }

    @Test
    void degreeTablesHoldCosAndSin() {
        assertAll(
            () -> assertEquals(1.0, Trig.cosDeg(0), EPS, "cos 0"),
            () -> assertEquals(0.0, Trig.sinDeg(0), EPS, "sin 0"),
            () -> assertEquals(0.0, Trig.cosDeg(90), EPS, "cos 90"),
            () -> assertEquals(1.0, Trig.sinDeg(90), EPS, "sin 90"),
            () -> assertEquals(Math.cos(Math.toRadians(15)), Trig.cosDeg(15), EPS, "cos 15"),
            () -> assertEquals(Math.sin(Math.toRadians(15)), Trig.sinDeg(15), EPS, "sin 15"),
            () -> assertEquals(Math.sin(Math.toRadians(-30)), Trig.sinDeg(-30), EPS, "negative angles"),
            () -> assertEquals(Math.cos(Math.toRadians(359.875)), Trig.cosDeg(359.875), EPS, "the last entry"));
    }

    @Test
    void resTablesHoldCosAndSinOfHeadingsModuloRes() {
        double a = 2 * Math.PI / Const.RES;
        assertAll(
            () -> assertEquals(1.0, Trig.cosRes(0), EPS, "heading 0 points right"),
            () -> assertEquals(1.0, Trig.sinRes(Const.RES / 4), EPS, "RES/4 points up"),
            () -> assertEquals(Math.cos(5 * a), Trig.cosRes(5), EPS, "cos of heading 5"),
            () -> assertEquals(Math.sin(5 * a), Trig.sinRes(5), EPS, "sin of heading 5"),
            () -> assertEquals(Math.cos(5 * a), Trig.cosRes(5 + Const.RES), EPS, "headings wrap past RES"),
            () -> assertEquals(Math.sin(5 * a), Trig.sinRes(5 - Const.RES), EPS, "and below 0"),
            () -> assertEquals(Math.sin(127 * a), Trig.sinRes(127), EPS, "the last heading"));
    }
}
