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
package com.fulcrologic.jxpilot.server.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Ecm.pow075: the allocation-free 0.75^n of the ECM blindness and visibility")
class EcmPowTest {
    @Test
    @DisplayName("bit-identical to StrictMath.pow(0.75, n) in and outside the 0..255 table")
    void exact() {
        boolean same = true;
        for (int n = 0; n < 256; n++) {
            same &= Double.doubleToRawLongBits(Ecm.pow075(n)) == Double.doubleToRawLongBits(StrictMath.pow(0.75, n));
        }
        boolean all = same;
        assertAll(
            () -> assertEquals(true, all, "0..255 from the table"),
            () -> assertEquals(StrictMath.pow(0.75, 300), Ecm.pow075(300), "above the table"),
            () -> assertEquals(StrictMath.pow(0.75, -1), Ecm.pow075(-1), "below the table"),
            () -> assertEquals(1.0, Ecm.pow075(0), "0.75^0"),
            () -> assertEquals(0.75, Ecm.pow075(1), "0.75^1"));
    }
}
