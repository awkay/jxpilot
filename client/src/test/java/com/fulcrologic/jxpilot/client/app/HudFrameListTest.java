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

import com.fulcrologic.jxpilot.client.game.hud.HudColor;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HudFrameListTest {
    @Test
    @DisplayName("every HudDrawList accessor reads the HudFrame's entry")
    void delegates() {
        HudFrame f = new HudFrame();
        f.line(1, 2, 3, 4, HudFrame.DASHED, HudColor.RED);
        f.rect(5, 6, 7, 8, true, HudColor.WHITE);
        f.text(9, 10, HudColor.SCORE).append("hello");
        f.setTextLength(3);
        HudFrameList l = new HudFrameList(f);
        assertAll(
            () -> assertSame(f, l.frame(), "the frame"),
            () -> assertEquals(1, l.lineCount(), "lines"),
            () -> assertEquals(1f, l.lineX0(0), "x0"),
            () -> assertEquals(2f, l.lineY0(0), "y0"),
            () -> assertEquals(3f, l.lineX1(0), "x1"),
            () -> assertEquals(4f, l.lineY1(0), "y1"),
            () -> assertEquals(HudFrame.DASHED, l.lineStyle(0), "style"),
            () -> assertEquals(HudColor.RED, l.lineColor(0), "line colour"),
            () -> assertEquals(1, l.rectCount(), "rects"),
            () -> assertEquals(5f, l.rectX(0), "rect x"),
            () -> assertEquals(6f, l.rectY(0), "rect y"),
            () -> assertEquals(7f, l.rectW(0), "rect w"),
            () -> assertEquals(8f, l.rectH(0), "rect h"),
            () -> assertTrue(l.rectFilled(0), "filled"),
            () -> assertEquals(HudColor.WHITE, l.rectColor(0), "rect colour"),
            () -> assertEquals(1, l.textCount(), "texts"),
            () -> assertEquals("hello", l.text(0).toString(), "text"),
            () -> assertEquals(3, l.textLength(0), "drawn length"),
            () -> assertEquals(9f, l.textX(0), "text x"),
            () -> assertEquals(10f, l.textY(0), "text y"),
            () -> assertEquals(HudColor.SCORE, l.textColor(0), "text colour"));
    }

    @Test
    @DisplayName("an outline rect reads as not filled")
    void outline() {
        HudFrame f = new HudFrame();
        f.rect(0, 0, 1, 1, false, HudColor.HUD);
        assertFalse(new HudFrameList(f).rectFilled(0), "outline");
    }
}
