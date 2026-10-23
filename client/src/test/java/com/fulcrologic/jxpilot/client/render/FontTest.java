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

class FontTest {
    @Test
    @DisplayName("glyph slots cover Latin-1: ASCII and 160..255 have their own; C1 controls and beyond 255 fall back")
    void latin1Slots() {
        int fallback = '?' - Font.FIRST;
        Font f = new Font(64, 64, 10, 3);
        f.setGlyph('ø', 0, 0, 1, 1, 0, 0, 9);
        assertAll(
            () -> assertEquals(0, Font.slot(' '), "space is slot 0"),
            () -> assertEquals('~' - 32, Font.slot('~'), "126 has its own slot"),
            () -> assertEquals(fallback, Font.slot('\u007f'), "DEL falls back"),
            () -> assertEquals(fallback, Font.slot('\u009f'), "159 falls back"),
            () -> assertEquals(160 - 32, Font.slot(' '), "160 has its own slot"),
            () -> assertEquals(248 - 32, Font.slot('ø'), "o-slash has its own slot"),
            () -> assertEquals(255 - 32, Font.slot('ÿ'), "255 has its own slot"),
            () -> assertEquals(fallback, Font.slot('Ā'), "256 falls back"),
            () -> assertEquals(fallback, Font.slot('\u001f'), "below space falls back"),
            () -> assertEquals(9f, f.advance('ø'), "a Latin-1 glyph's own advance"));
    }
}
