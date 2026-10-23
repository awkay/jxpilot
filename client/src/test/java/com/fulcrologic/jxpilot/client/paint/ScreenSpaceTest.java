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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScreenSpaceTest {
    @Test
    void mapsCPixelsToFramebufferCentresAndEdges() {
        ScreenSpace s = new ScreenSpace();
        s.set(600, 2);
        assertAll(
            () -> assertEquals(21f, s.x(10), "column 10's centre: (10 + 0.5) * 2"),
            () -> assertEquals(579f, s.y(10), "row 10's centre from the top: 600 - 10.5 * 2"),
            () -> assertEquals(20f, s.edgeX(10), "column 10's left edge"),
            () -> assertEquals(580f, s.edgeY(10), "row 10's top edge"),
            () -> assertEquals(580f, s.baseline(10), "text drawn at row 10 sits on its top edge"),
            () -> assertEquals(7f, s.len(3.5), "lengths scale by the ratio"),
            () -> assertEquals(2.0, s.ratio(), "ratio"),
            () -> assertEquals(300, s.logicalHeight(), "600 framebuffer rows are 300 logical"),
            () -> assertEquals(400, s.logicalWidth(801), "logical width truncates"));
    }
}
