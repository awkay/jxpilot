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
package com.fulcrologic.jxpilot.client.game.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudMeterBitmapTest {
    @Test
    void withBitmapMetersAMeterIsTheMeterBitmapAndItsTitle() {
        Hud h = new Hud();
        h.bitmapMeters = true;
        HudFrame f = new HudFrame();
        h.meter(f, 800, 10, 40, "Power", 50, 100, HudTest.M);
        h.zoom = 2;
        HudFrame z = new HudFrame();
        h.meter(z, 800, 10, 40, "Power", 50, 100, HudTest.M);
        assertAll(
            () -> assertEquals(2, f.rectCount(), "two parts"),
            () -> assertEquals(0, f.lineCount(), "no ticks"),
            () -> assertEquals(HudColor.METER_FULL, f.rectColor(0), "the full part first"),
            () -> assertEquals(10f, f.rectX(0), "at the meter's x"),
            () -> assertEquals(40f, f.rectY(0), "and y (no 2-pixel inset)"),
            () -> assertEquals(98f, f.rectW(0), "197 * 50 / 100 = 98 pixels full"),
            () -> assertEquals(11f, f.rectH(0), "11 high"),
            () -> assertEquals(HudColor.METER_EMPTY, f.rectColor(1), "then the empty part"),
            () -> assertEquals(108f, f.rectX(1), "from the end of the full part"),
            () -> assertEquals(102f, f.rectW(1), "to the meter's 200 pixels"),
            () -> assertEquals("Power", f.text(0).toString(), "the title stays"),
            () -> assertEquals(196f, z.rectW(0), "zoom 2: WINSCALE(98)"),
            () -> assertEquals(22f, z.rectH(0), "WINSCALE(11)"),
            () -> assertEquals(400f - 196f, z.rectW(1), "the rest of WINSCALE(200)"));
    }
}
