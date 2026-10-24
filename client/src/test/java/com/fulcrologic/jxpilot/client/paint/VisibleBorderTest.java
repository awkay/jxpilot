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

import com.fulcrologic.jxpilot.client.render.VertexBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.paint.PaintTests.*;
import static org.junit.jupiter.api.Assertions.*;

class VisibleBorderTest {
    static List<String> border(int ew, int eh, double zoom) {
        ScreenSpace ss = new ScreenSpace();
        ss.set(2000, 1);
        VertexBatch b = batch();
        VisibleBorder.paint(b, ss, ew, eh, zoom, 0x12345678);
        return segs(b);
    }

    @Test
    void shownOnlyForAViewLargerThanTheServersMaximum() {
        assertAll(
            () -> assertFalse(VisibleBorder.shown(1024, 1024), "1024 x 1024 fits"),
            () -> assertTrue(VisibleBorder.shown(1025, 800), "wider"),
            () -> assertTrue(VisibleBorder.shown(800, 1025), "taller"),
            () -> assertEquals(List.of(), border(1024, 1024, 1), "nothing drawn when it fits"));
    }

    @Test
    void aBoxOfTheMaximumSizeCentredOnTheView() {
        ScreenSpace ss = new ScreenSpace();
        ss.set(2000, 1);
        VertexBatch b = batch();
        VisibleBorder.paint(b, ss, 1301, 800, 1, 0x12345678);
        assertAll(
            () -> assertEquals(List.of("138.5,1087.5-138.5,2111.5", "1162.5,1087.5-1162.5,2111.5",
                    "138.5,1087.5-1162.5,1087.5", "138.5,2111.5-1162.5,2111.5"), segs(b),
                "left, right, bottom, top: x 650 -/+ 512 = 138 and 1162; rows 400 + 512 = 912 and 400 - 512 = -112"
                    + " (framebuffer 2000 - row - 0.5)"),
            () -> assertEquals(0x12345678, b.segColor(0), "in the given colour"));
    }

    @Test
    void scaledByWinscale() {
        assertEquals(List.of("69.5,1543.5-69.5,2055.5", "581.5,1543.5-581.5,2055.5", "69.5,1543.5-581.5,1543.5",
            "69.5,2055.5-581.5,2055.5"), border(1301, 800, 0.5),
            "every coordinate WINSCALEd at zoom 0.5: 69, 581, rows 456 and -56 (framebuffer 2000 - 456.5, 2000 + 55.5)");
    }
}
