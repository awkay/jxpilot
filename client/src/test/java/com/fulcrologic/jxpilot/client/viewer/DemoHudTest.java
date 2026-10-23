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
package com.fulcrologic.jxpilot.client.viewer;

import com.fulcrologic.jxpilot.client.Fixtures;
import com.fulcrologic.jxpilot.client.paint.HudDrawList;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DemoHudTest {
    static final TextWriter TEXT = new TextWriter(Fixtures.font()); // ascent 10, descent 3

    @Test
    void laysOutTheGameClientsHudPiecesAtCPositions() {
        DemoHud h = new DemoHud();
        h.build(800, 600, 300, 0, TEXT, 1);
        assertAll(
            () -> assertEquals(9, h.lineCount(), "2 HUD lines, 5 meter ticks, 2 scoreboard rules"),
            () -> assertEquals(HudDrawList.DASHED, h.lineStyle(0), "HUD lines are dashed"),
            () -> assertEquals(300 - 70f, h.lineY0(0), "upper HUD line at hy - HUD_SIZE + HUD_OFFSET"),
            () -> assertEquals(400 - 90f, h.lineX0(0), "from hx - HUD_SIZE"),
            () -> assertEquals(DemoHud.HUD, h.lineColor(0), "in the HUD colour"),
            () -> assertEquals(6, h.rectCount(), "gauge frame and fill, meter fill and frame, talk border and box"),
            () -> assertEquals(590f, h.rectX(3), "meter frame at viewW - 210"),
            () -> assertFalse(h.rectFilled(3), "an outline"),
            () -> assertEquals(12, h.textCount(), "meter title, 5 messages, talk line, heading, 4 score rows"),
            () -> assertEquals("Fuel", h.text(0).toString(), "meter title first"),
            () -> assertEquals(300 + 6 + 10f, h.textY(7), "scoreboard heading baseline: top + border + ascent"),
            () -> assertEquals(DemoHud.SCORE_DIM, h.textColor(11), "the 'D' row is dimmed"),
            () -> assertEquals(DemoHud.SCORE, h.textColor(8), "others score white"));
    }

    @Test
    void theHudFrameAndGaugeAreLaidOutAsCScalesThem() {
        DemoHud h = new DemoHud();
        h.build(800, 600, 300, 0, TEXT, 1, 0.5);
        assertAll(
            () -> assertEquals(300 - 35f, h.lineY0(0), "unscaled hud_pos (800, 600): WINSCALE(600 - 70) = 265"),
            () -> assertEquals(400 - 45f, h.lineX0(0), "WINSCALE(800 - 90) = 355"),
            () -> assertEquals(445f, h.lineX1(0), "WINSCALE(890)"),
            () -> assertEquals(438f - 1, h.rectX(0), "gauge frame from WINSCALE(876) - 1"),
            () -> assertEquals(4f + 2, h.rectW(0), "WINSCALE(8) + 2 wide"),
            () -> assertEquals(590f, h.rectX(3), "the meter is not scaled"));
    }

    @Test
    void messagesTypeOutAndTurnFromRedToOld() {
        DemoHud fresh = new DemoHud();
        fresh.build(800, 600, 300, 0, TEXT, 1);
        DemoHud later = new DemoHud();
        later.build(800, 600, 300, 200, TEXT, 1);
        assertAll(
            () -> assertEquals(0, fresh.textLength(1), "age 0: nothing typed yet"),
            () -> assertEquals(DemoHud.RED, fresh.textColor(1), "and red"),
            () -> assertEquals(DemoHud.TALK[0].length(), later.textLength(1), "age 200: fully typed"),
            () -> assertEquals(DemoHud.OLD_MESSAGE, later.textColor(1), "past the flash: old colour"),
            () -> assertEquals(10f, later.textX(1), "talk messages from the left border"),
            () -> assertEquals(20f, later.textY(1), "first at border + ascent"));
    }
}
