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
package com.fulcrologic.jxpilot.client.app;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudRadarBlipsTest {
    @Test
    void blipsBecomeTruncatedRadarPixelsWithTheirSizeByte() {
        RadarBlips r = new RadarBlips(8);
        r.add(1285.9f, 645.7f, 3);
        r.add(10f, 2550f, 0x83);
        r.add(-0.5f, 0f, 0);
        HudRadarBlips h = new HudRadarBlips(2);
        h.fill(r, 2560, 1280, 128);
        assertAll(
            () -> assertEquals(2, h.count(), "at most the capacity"),
            () -> assertEquals(128, h.x[0], "(int)(1285 * 256 / 2560) = 128 (floor first, then truncate 128.5)"),
            () -> assertEquals(64, h.y[0], "(int)(645 * 128 / 1280) = 64"),
            () -> assertEquals(3, h.size[0], "size byte"),
            () -> assertEquals(1, h.x[1], "(int)(10 * 256 / 2560)"),
            () -> assertEquals(255, h.y[1], "(int)(2550 * 128 / 1280)"),
            () -> assertEquals(0x83, h.size[1], "the friend bit is kept"));
        HudRadarBlips all = new HudRadarBlips(8);
        all.fill(r, 2560, 1280, 128);
        assertAll(
            () -> assertEquals(3, all.count(), "all three fit"),
            () -> assertEquals(0, all.x[2], "floor(-0.5) = -1, then (int)(-1 * 256 / 2560) truncates to 0"));
    }
}
