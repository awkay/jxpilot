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

import com.fulcrologic.jxpilot.client.game.hud.FontRole;
import com.fulcrologic.jxpilot.client.render.Font;
import com.fulcrologic.jxpilot.client.render.FontSpec;
import com.fulcrologic.jxpilot.client.render.FontSpec.Face;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoleFontSetTest {
    static final int N = FontRole.values().length;

    @Test
    @DisplayName("each role measures and draws with its font; roles sharing a font share a writer")
    void set() {
        Font serif = new Font(10, 10, 20, 6);
        Font mono = new Font(10, 10, 12, 4);
        Font[] fonts = new Font[N];
        FontSpec[] specs = new FontSpec[N];
        for (int i = 0; i < N; i++) {
            fonts[i] = i == FontRole.SCORE_LIST.ordinal() || i == FontRole.TALK.ordinal() ? mono : serif;
            specs[i] = FontSpec.of(Face.SERIF_REGULAR, 10);
        }
        RoleFontSet s = new RoleFontSet();
        s.set(specs, fonts, 2);
        assertAll(
            () -> assertSame(mono, s.writer(FontRole.SCORE_LIST).font(), "score list draws in its font"),
            () -> assertSame(serif, s.writer(FontRole.GAME).font(), "game draws in its font"),
            () -> assertSame(s.writer(FontRole.SCORE_LIST), s.writer(FontRole.TALK), "same font: one writer"),
            () -> assertSame(s.writer(FontRole.GAME), s.writer(FontRole.MESSAGE), "same font: one writer"),
            () -> assertNotSame(s.writer(FontRole.GAME), s.writer(FontRole.TALK), "other font: another writer"),
            () -> assertSame(s.writer(FontRole.TALK), s.writers()[FontRole.TALK.ordinal()], "writers by ordinal"),
            () -> assertEquals(6, s.of(FontRole.SCORE_LIST).ascent(), "score list ascent 12 / ratio 2"),
            () -> assertEquals(10, s.of(FontRole.GAME).ascent(), "game ascent 20 / ratio 2"),
            () -> assertEquals(3, s.of(FontRole.GAME).descent(), "game descent 6 / ratio 2"));
    }

    @Test
    @DisplayName("a rebake replaces every writer with one for the new font")
    void rebake() {
        Font[] first = new Font[N];
        Font[] second = new Font[N];
        for (int i = 0; i < N; i++) {
            first[i] = new Font(10, 10, 1, 1);
            second[i] = new Font(10, 10, 2, 2);
        }
        FontSpec[] specs = RoleFontsTest.all(FontSpec.of(Face.SERIF_REGULAR, 10));
        RoleFontSet s = new RoleFontSet();
        s.set(specs, first, 1);
        s.set(specs, second, 1);
        assertAll(
            () -> assertSame(second[0], s.writer(FontRole.GAME).font(), "the first role draws in its new font"),
            () -> assertSame(second[N - 1], s.writer(FontRole.KEY_LIST).font(), "the last role too"));
    }

    @Test
    @DisplayName("needs a bake until set, and again when the specs or the ratio change")
    void needs() {
        Font f = new Font(10, 10, 1, 1);
        Font[] fonts = new Font[N];
        java.util.Arrays.fill(fonts, f);
        FontSpec[] specs = RoleFontsTest.all(FontSpec.of(Face.SERIF_REGULAR, 10));
        RoleFontSet s = new RoleFontSet();
        boolean fresh = s.needs(specs, 1);
        s.set(specs, fonts, 1);
        boolean same = s.needs(RoleFontsTest.all(FontSpec.of(Face.SERIF_REGULAR, 10)), 1);
        boolean ratio = s.needs(specs, 2);
        FontSpec[] other = specs.clone();
        other[3] = FontSpec.of(Face.MONO_BOLD, 10);
        boolean changed = s.needs(other, 1);
        specs[0] = FontSpec.of(Face.MONO_BOLD, 10);
        boolean copied = s.needs(specs, 1);
        assertAll(
            () -> assertTrue(fresh, "nothing baked yet"),
            () -> assertFalse(same, "equal specs, same ratio: no bake"),
            () -> assertTrue(ratio, "another pixel ratio"),
            () -> assertTrue(changed, "one role's font changed"),
            () -> assertTrue(copied, "set kept a copy: changing the caller's array later is a change"));
    }
}
