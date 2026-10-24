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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FontStripsTest {
    @Test
    @DisplayName("strips stack top-down in each region; reset starts over; overfilling takes nothing")
    void strips() {
        FontStrips s = new FontStrips(100, 0, 50, 30, 0, 40, 20, 10);
        int regions = s.regions();
        s.take(0, 12);
        int y1 = s.nextY(0);
        int left1 = s.remaining(0);
        s.take(0, 18);
        int left2 = s.remaining(0);
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class, () -> s.take(1, 11));
        int keptAfterError = s.remaining(1);
        s.take(1, 10);
        int y2 = s.nextY(1);
        s.reset();
        assertAll(
            () -> assertEquals(2, regions, "two regions"),
            () -> assertEquals(100, s.x(0), "region 0 left"),
            () -> assertEquals(50, s.width(0), "region 0 width"),
            () -> assertEquals(0, s.x(1), "region 1 left"),
            () -> assertEquals(20, s.width(1), "region 1 width"),
            () -> assertEquals(12, y1, "the next strip starts under the first"),
            () -> assertEquals(18, left1, "30 - 12 rows left"),
            () -> assertEquals(0, left2, "exactly full"),
            () -> assertEquals("11 rows do not fit in region 1 (10 left)", over.getMessage(), "overfill message"),
            () -> assertEquals(10, keptAfterError, "the failed take took nothing"),
            () -> assertEquals(50, y2, "region 1 starts at its top 40"),
            () -> assertEquals(0, s.nextY(0), "reset: region 0 from its top"),
            () -> assertEquals(40, s.nextY(1), "reset: region 1 from its top"),
            () -> assertEquals(30, s.remaining(0), "reset: all rows free"));
    }
}
