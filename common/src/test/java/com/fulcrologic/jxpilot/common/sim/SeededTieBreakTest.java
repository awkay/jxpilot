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
package com.fulcrologic.jxpilot.common.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEHAVIOR (SeededTieBreak): the draws after {@code reseed(tick, id)} are a fixed function of (tick, id): the
 * server and the owner's predictor must draw the same numbers, so the values are pinned; they lie in [0, 1).
 */
@DisplayName("SeededTieBreak")
class SeededTieBreakTest {
    static double[] draws(int tick, int id) {
        SeededTieBreak s = new SeededTieBreak();
        s.reseed(tick, id);
        return new double[] {s.getAsDouble(), s.getAsDouble(), s.getAsDouble()};
    }

    @Test
    @DisplayName("pinned draws per (tick, id); a reseed restarts them; all in [0, 1)")
    void pinned() {
        double[] a = draws(10, 3);
        double[] b = draws(0, 0);
        double[] c = draws(123456, 65535);
        SeededTieBreak s = new SeededTieBreak();
        s.reseed(10, 3);
        s.getAsDouble();
        s.reseed(10, 3);
        double again = s.getAsDouble();
        boolean range = true;
        for (int i = 0; i < 20_000; i++) {
            s.reseed(i * 7919, i & 63);
            double v = s.getAsDouble();
            range &= v >= 0 && v < 1;
        }
        boolean inRange = range;
        assertAll(
            () -> assertEquals(0.7360132832983436, a[0], "(10, 3) first draw"),
            () -> assertEquals(0.4961950214936618, a[1], "second"),
            () -> assertEquals(0.8839089405413761, a[2], "third"),
            () -> assertEquals(0.6524484863740322, b[0], "(0, 0)"),
            () -> assertEquals(0.7012121095215252, b[1], "(0, 0) second"),
            () -> assertEquals(0.7575932558132343, c[0], "(123456, 65535)"),
            () -> assertEquals(0.3871241409757855, b[2], "(0, 0) third"),
            () -> assertEquals(a[0], again, "a reseed restarts the sequence"),
            () -> assertTrue(inRange, "draws in [0, 1)"));
    }
}
