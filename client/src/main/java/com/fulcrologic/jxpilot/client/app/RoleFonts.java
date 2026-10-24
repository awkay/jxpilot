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
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.render.FontSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Chooses the font of every {@link FontRole}: C's font resources ({@code gameFont}, {@code messageFont}, ...,
 * read from the {@link ClientOptions}) parsed by {@link FontSpec#parse}, or one font for all roles when
 * {@code --font} / {@code --font-size} is given. A value that cannot be used falls back to the role's C default
 * with a problem in C's words ({@code xinit.c} {@code Set_font}). Pure; file checks are injected.
 */
public final class RoleFonts {
    private RoleFonts() {}

    /** C {@code default.c} {@code KEY_LIST_FONT}: the key list's font (no resource). */
    public static final String KEY_LIST_FONT = "-*-fixed-medium-r-*--10-*-*-*-c-*-iso8859-1";
    /** The {@code --font} / {@code --font-size} font when the other is not given: bundled Fira Code at 13. */
    public static final FontSpec OVERRIDE_DEFAULT =
        FontSpec.of(FontSpec.Face.FIRA_CODE, (float) AppOptions.DEFAULT_FONT_SIZE);
    /** Fallback of the C default values themselves (never used: they all parse). */
    private static final FontSpec LAST_RESORT = FontSpec.of(FontSpec.Face.SERIF_REGULAR, 14);

    /**
     * Fonts by role ordinal, and the problems met choosing them.
     *
     * @param specs    the font of each role, indexed by {@link FontRole#ordinal()}
     * @param problems one line per unusable value, in role order
     */
    public record Resolution(FontSpec[] specs, List<String> problems) {
        /** The font of {@code role}. */
        public FontSpec of(FontRole role) {
            return specs[role.ordinal()];
        }
    }

    /** BEHAVIOR: C's default value of the role's resource; {@link #KEY_LIST_FONT} for the key list. */
    public static String defaultValue(FontRole role) {
        return role.option == null ? KEY_LIST_FONT : role.option.defaultText;
    }

    /** BEHAVIOR: the role's C default font ({@link #defaultValue} parsed). */
    public static FontSpec defaultSpec(FontRole role) {
        return FontSpec.parse(defaultValue(role), LAST_RESORT).spec();
    }

    /**
     * Choose every role's font.
     * <p>
     * BEHAVIOR: with neither override, each role uses its resource's value from {@code options} (the key list
     * its {@link #KEY_LIST_FONT}), parsed with the role's {@link #defaultSpec} as fallback.
     * BEHAVIOR: with {@code fontOverride} and/or {@code sizeOverride} &gt; 0, every role uses one font:
     * {@code fontOverride} parsed with {@link #OVERRIDE_DEFAULT} as fallback (Fira Code 13 when not given), at
     * {@code sizeOverride} when positive.
     * BEHAVIOR: a value that does not parse, or names a file {@code usableFile} rejects, uses the fallback and
     * adds "Couldn't find font 'VALUE' for RESOURCE, using default font (REASON)".
     */
    public static Resolution resolve(ClientOptions options, String fontOverride, double sizeOverride,
                                     Predicate<String> usableFile) {
        FontRole[] roles = FontRole.values();
        FontSpec[] specs = new FontSpec[roles.length];
        List<String> problems = new ArrayList<>();
        if (fontOverride != null || sizeOverride > 0) {
            FontSpec one = fontOverride == null ? OVERRIDE_DEFAULT
                : choose(fontOverride, OVERRIDE_DEFAULT, "--font", usableFile, problems);
            if (sizeOverride > 0) {
                one = one.withSize((float) sizeOverride);
            }
            java.util.Arrays.fill(specs, one);
        } else {
            for (FontRole r : roles) {
                String value = r.option == null ? KEY_LIST_FONT : options.text(r.option);
                String name = r.option == null ? "the key list" : r.option.resource;
                specs[r.ordinal()] = choose(value, defaultSpec(r), name, usableFile, problems);
            }
        }
        return new Resolution(specs, List.copyOf(problems));
    }

    private static FontSpec choose(String value, FontSpec fallback, String name, Predicate<String> usableFile,
                                   List<String> problems) {
        FontSpec.Result r = FontSpec.parse(value, fallback);
        String problem = r.problem();
        FontSpec spec = r.spec();
        if (problem == null && spec.file() != null && !usableFile.test(spec.file())) {
            problem = "not a readable TrueType file";
            spec = fallback;
        }
        if (problem != null) {
            problems.add("Couldn't find font '" + value + "' for " + name + ", using default font (" + problem + ")");
        }
        return spec;
    }
}
