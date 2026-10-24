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
import com.fulcrologic.jxpilot.client.game.options.ClientOption;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.render.FontSpec;
import com.fulcrologic.jxpilot.client.render.FontSpec.Face;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class RoleFontsTest {
    static final Predicate<String> ANY_FILE = f -> true;
    static final Predicate<String> ONLY_A = f -> f.equals("/x/a.ttf");

    static FontSpec[] all(FontSpec s) {
        FontSpec[] a = new FontSpec[FontRole.values().length];
        java.util.Arrays.fill(a, s);
        return a;
    }

    @Nested
    @DisplayName("per-role C fonts")
    class PerRole {
        @Test
        @DisplayName("C's defaults: Serif HUD and messages, Mono score list, talk, MOTD and key list")
        void defaults() {
            RoleFonts.Resolution r = RoleFonts.resolve(new ClientOptions(), null, AppOptions.FONT_SIZE_UNSET, ANY_FILE);
            assertAll(
                () -> assertEquals(FontSpec.of(Face.SERIF_REGULAR, 18), r.of(FontRole.GAME), "gameFont Times 18"),
                () -> assertEquals(FontSpec.of(Face.SERIF_REGULAR, 14), r.of(FontRole.MESSAGE), "messageFont Times 14"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 13), r.of(FontRole.SCORE_LIST), "scoreListFont fixed bold 13"),
                () -> assertEquals(FontSpec.of(Face.SANS_BOLD_ITALIC, 14), r.of(FontRole.BUTTON), "buttonFont bold oblique 14"),
                () -> assertEquals(FontSpec.of(Face.SERIF_BOLD_ITALIC, 14), r.of(FontRole.TEXT), "textFont bold italic 14"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 15), r.of(FontRole.TALK), "talkFont fixed bold 15"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 14), r.of(FontRole.MOTD), "motdFont courier bold 14"),
                () -> assertEquals(FontSpec.of(Face.MONO_REGULAR, 10), r.of(FontRole.KEY_LIST), "key list fixed 10"),
                () -> assertEquals(List.of(), r.problems(), "no problems"));
        }

        @Test
        @DisplayName("resource values choose the fonts; unusable ones fall back to the role's default with C's message")
        void values() {
            ClientOptions o = new ClientOptions();
            o.setText(ClientOption.GAME_FONT, "FiraCode:16");
            o.setText(ClientOption.MESSAGE_FONT, "/x/a.ttf");
            o.setText(ClientOption.TALK_FONT, "bogus");
            o.setText(ClientOption.SCORE_LIST_FONT, "/missing.ttf:12");
            RoleFonts.Resolution r = RoleFonts.resolve(o, null, AppOptions.FONT_SIZE_UNSET, ONLY_A);
            assertAll(
                () -> assertEquals(FontSpec.of(Face.FIRA_CODE, 16), r.of(FontRole.GAME), "a bundled name with a size"),
                () -> assertEquals(new FontSpec(null, "/x/a.ttf", 14), r.of(FontRole.MESSAGE),
                    "a usable file at the role's default size"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 15), r.of(FontRole.TALK), "unknown: talk's default"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 13), r.of(FontRole.SCORE_LIST),
                    "a rejected file: the score list's default"),
                () -> assertEquals(List.of(
                        "Couldn't find font '/missing.ttf:12' for scoreListFont, using default font (not a readable TrueType file)",
                        "Couldn't find font 'bogus' for talkFont, using default font (unknown font)"),
                    r.problems(), "one C-style line per unusable value, in role order"));
        }

        @Test
        @DisplayName("defaults per role: the resource's C default, the key list's KEY_LIST_FONT")
        void defaultValues() {
            assertAll(
                () -> assertEquals(ClientOption.GAME_FONT.defaultText, RoleFonts.defaultValue(FontRole.GAME), "gameFont"),
                () -> assertEquals(RoleFonts.KEY_LIST_FONT, RoleFonts.defaultValue(FontRole.KEY_LIST), "key list"),
                () -> assertEquals(FontSpec.of(Face.MONO_BOLD, 14), RoleFonts.defaultSpec(FontRole.MOTD), "motd parsed"));
        }
    }

    @Nested
    @DisplayName("--font / --font-size: one font for every role")
    class Override {
        @Test
        @DisplayName("either option replaces every role's font, ignoring the resources")
        void override() {
            ClientOptions o = new ClientOptions();
            o.setText(ClientOption.GAME_FONT, "FiraCode:30");
            assertAll(
                () -> assertArrayEquals(all(new FontSpec(null, "/x/a.ttf", 13)),
                    RoleFonts.resolve(o, "/x/a.ttf", AppOptions.FONT_SIZE_UNSET, ONLY_A).specs(),
                    "--font FILE alone: that file at 13"),
                () -> assertArrayEquals(all(FontSpec.of(Face.FIRA_CODE, 16.5f)),
                    RoleFonts.resolve(o, null, 16.5, ANY_FILE).specs(), "--font-size alone: Fira Code at that size"),
                () -> assertArrayEquals(all(FontSpec.of(Face.SERIF_REGULAR, 20)),
                    RoleFonts.resolve(o, "LiberationSerif:9", 20, ANY_FILE).specs(), "--font-size wins over :SIZE"),
                () -> assertArrayEquals(all(FontSpec.of(Face.MONO_BOLD, 18)),
                    RoleFonts.resolve(o, "LiberationMono-Bold:18", AppOptions.FONT_SIZE_UNSET, ANY_FILE).specs(),
                    "--font NAME:SIZE"));
        }

        @Test
        @DisplayName("an unusable --font is Fira Code, at --font-size when given, with a problem")
        void unusable() {
            RoleFonts.Resolution bad = RoleFonts.resolve(new ClientOptions(), "nope", 15, ANY_FILE);
            RoleFonts.Resolution missing = RoleFonts.resolve(new ClientOptions(), "/y.ttf", AppOptions.FONT_SIZE_UNSET,
                ONLY_A);
            assertAll(
                () -> assertArrayEquals(all(FontSpec.of(Face.FIRA_CODE, 15)), bad.specs(), "Fira Code at 15"),
                () -> assertEquals(List.of("Couldn't find font 'nope' for --font, using default font (unknown font)"),
                    bad.problems(), "the problem names --font"),
                () -> assertArrayEquals(all(RoleFonts.OVERRIDE_DEFAULT), missing.specs(), "Fira Code at 13"),
                () -> assertEquals(1, missing.problems().size(), "a rejected file is a problem"));
        }
    }
}
