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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Key help overlay layout")
class KeyHelpTest {
    record Mono() implements TextMetrics {
        public int ascent() { return 10; }

        public int descent() { return 3; }

        public int width(CharSequence s, int len) { return 7 * len; }
    }

    static final Mono M = new Mono();

    @Test
    @DisplayName("hidden draws nothing; shown: panel, title and entries in columns, conflicts red")
    void layout() {
        List<String[]> e = new ArrayList<>();
        e.add(new String[] {"a", "Turn left."});
        e.add(new String[] {"Shift_L", "Thrust."});
        e.add(new String[] {"* u", "bound to x y"});
        KeyHelp h = new KeyHelp("Keys", e);
        HudFrame out = new HudFrame();
        h.layout(out, 800, 600, M);
        int hidden = out.textCount() + out.rectCount();
        h.toggle();
        // view height 100: line height 15, top = 10 + 10 + 15 = 35, rows = (100 - 35 - 20) / 15 = 3 -> 1 column
        h.layout(out, 800, 100, M);
        HudFrame two = new HudFrame();
        // view height 80: rows = (80 - 55) / 15 = 1 -> 3 columns
        h.layout(two, 800, 80, M);
        assertAll(
            () -> assertEquals(0, hidden, "hidden: nothing"),
            () -> assertTrue(h.visible(), "toggled on"),
            () -> assertEquals(2, out.rectCount(), "black panel and white outline"),
            () -> assertTrue(out.rectFilled(0), "the panel is filled"),
            () -> assertEquals(HudColor.BLACK, out.rectColor(0), "black"),
            () -> assertEquals(HudColor.WHITE, out.rectColor(1), "white outline"),
            // width = keys 7*7 + gap 12 + help 12*7 = 145 -> panel 145 + 20
            () -> assertEquals(165f, out.rectW(0), "panel width = widest keys + gap + widest text + margins"),
            () -> assertEquals(35f + 3 * 15, out.rectH(0), "panel height = title + three rows"),
            () -> assertEquals(7, out.textCount(), "title plus two runs per entry"),
            () -> assertEquals("Keys", out.text(0).toString(), "title first"),
            () -> assertEquals(25f, out.textY(0), "title baseline at margin + margin/2 + ascent"),
            () -> assertEquals("a", out.text(1).toString(), "first keys"),
            () -> assertEquals(20f, out.textX(1), "keys at 2 * margin"),
            () -> assertEquals(45f, out.textY(1), "first row baseline at top + ascent"),
            () -> assertEquals(HudColor.HUD, out.textColor(1), "keys in the HUD colour"),
            () -> assertEquals(20f + 49 + 12, out.textX(2), "descriptions after the widest keys + gap"),
            () -> assertEquals(HudColor.WHITE, out.textColor(2), "descriptions white"),
            () -> assertEquals(60f, out.textY(3), "next row one line lower"),
            () -> assertEquals(HudColor.RED, out.textColor(5), "conflict keys red"),
            () -> assertEquals(HudColor.RED, out.textColor(6), "conflict text red"),
            () -> assertEquals(20f + 7 + 12 + 70 + 12, two.textX(3), "one row per column: the second entry starts a column"),
            () -> assertEquals(45f, two.textY(3), "at the top"),
            () -> assertEquals(35f + 15, two.rectH(0), "the panel holds one row"));
    }

    @Test
    @DisplayName("a long title widens the panel; toggling twice hides it")
    void title() {
        KeyHelp h = new KeyHelp("A much longer title than any entry", List.<String[]>of(new String[] {"a", "b"}));
        HudFrame out = new HudFrame();
        h.toggle();
        h.layout(out, 800, 600, M);
        float w = out.rectW(0);
        h.toggle();
        h.layout(out, 800, 600, M);
        assertAll(
            () -> assertEquals(34 * 7 + 20f, w, "title width + margins"),
            () -> assertEquals(0, out.textCount(), "hidden again"),
            () -> assertEquals(1, h.entries(), "entries"),
            () -> assertEquals("A much longer title than any entry", h.title(), "title"));
    }
}
