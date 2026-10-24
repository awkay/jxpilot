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
package com.fulcrologic.jxpilot.client.game.options;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Client options (C default.c resources)")
class ClientOptionsTest {
    @Nested
    @DisplayName("the option table")
    class Table {
        @Test
        @DisplayName("lookup by resource ignores case; the numbered families map 1:1; names are unique")
        void lookup() {
            Set<String> names = new HashSet<>();
            for (ClientOption o : ClientOption.values()) {
                names.add(o.resource.toLowerCase());
            }
            assertAll(
                () -> assertEquals(ClientOption.SHOW_HUD_RADAR, ClientOption.byResource("showHUDRadar"), "exact name"),
                () -> assertEquals(ClientOption.SHOW_HUD_RADAR, ClientOption.byResource("SHOWHUDRADAR"), "any case"),
                () -> assertNull(ClientOption.byResource("keyThrust"), "keys are not client options"),
                () -> assertNull(ClientOption.byResource("power"), "control settings stay in KeyConfig"),
                () -> assertEquals(ClientOption.values().length, names.size(), "resource names are unique"),
                () -> assertEquals(ClientOption.MSG1, ClientOption.message(1), "msg1"),
                () -> assertEquals(ClientOption.MSG20, ClientOption.message(20), "msg20"),
                () -> assertThrows(IllegalArgumentException.class, () -> ClientOption.message(0), "no msg0"),
                () -> assertThrows(IllegalArgumentException.class, () -> ClientOption.message(21), "no msg21"),
                () -> assertEquals(ClientOption.COLOR0, ClientOption.color(0), "color0"),
                () -> assertEquals(ClientOption.COLOR15, ClientOption.color(15), "color15"),
                () -> assertThrows(IllegalArgumentException.class, () -> ClientOption.color(-1), "no color-1"),
                () -> assertThrows(IllegalArgumentException.class, () -> ClientOption.color(16), "no color16"),
                () -> assertEquals(ClientOption.POINTER_BUTTON1, ClientOption.pointerButton(1), "pointerButton1"),
                () -> assertEquals(ClientOption.POINTER_BUTTON5, ClientOption.pointerButton(5), "pointerButton5"),
                () -> assertThrows(IllegalArgumentException.class, () -> ClientOption.pointerButton(0), "no button 0"),
                () -> assertThrows(IllegalArgumentException.class, () -> ClientOption.pointerButton(6), "no button 6"));
        }
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {
        @Test
        @DisplayName("every option starts at C's default and is not marked given")
        void defaults() {
            ClientOptions o = new ClientOptions();
            assertAll(
                () -> assertTrue(o.flag(ClientOption.SHOW_HUD), "showHUD Yes"),
                () -> assertFalse(o.flag(ClientOption.SHOW_HUD_RADAR), "showHUDRadar No"),
                () -> assertEquals(500, o.integer(ClientOption.FUEL_NOTIFY), "fuelNotify 500"),
                () -> assertEquals(0.5, o.real(ClientOption.SPARK_PROB), "sparkProb 0.50"),
                () -> assertEquals(2.0, o.real(ClientOption.SHOW_ITEMS_TIME), "showItemsTime 2.0"),
                () -> assertEquals("5,6,7,3", o.text(ClientOption.SPARK_COLORS), "sparkColors"),
                () -> assertEquals("keyFireShot", o.text(ClientOption.POINTER_BUTTON1), "pointerButton1"),
                () -> assertEquals("", o.text(ClientOption.MSG1), "msg1 empty"),
                () -> assertEquals("-*-times-*-*-*--18-*-*-*-*-*-iso8859-1", o.text(ClientOption.GAME_FONT),
                    "gameFont is C's Times 18"),
                () -> assertEquals(-1, o.integer(ClientOption.TEAM), "no team"),
                () -> assertFalse(o.given(ClientOption.SHOW_HUD), "defaults are not given"),
                () -> assertEquals(0, o.version(), "version 0"));
        }
    }

    @Nested
    @DisplayName("apply (resource text)")
    class Apply {
        @Test
        @DisplayName("bools follow C's ON(): true/on/yes prefixes in any case, anything else false")
        void bools() {
            ClientOptions o = new ClientOptions();
            assertAll(
                () -> assertNull(o.apply(ClientOption.SHOW_HUD_RADAR, "Yes"), "no problem"),
                () -> assertTrue(o.flag(ClientOption.SHOW_HUD_RADAR), "Yes is true"),
                () -> assertTrue(given(o, ClientOption.SHOW_HUD_RADAR), "marked given"),
                () -> {
                    o.apply(ClientOption.SHOW_HUD_RADAR, "nope");
                    assertFalse(o.flag(ClientOption.SHOW_HUD_RADAR), "nope is false");
                },
                () -> {
                    o.apply(ClientOption.SHOW_HUD_RADAR, "  TRUE");
                    assertTrue(o.flag(ClientOption.SHOW_HUD_RADAR), "TRUE with blanks is true");
                },
                () -> {
                    o.apply(ClientOption.SHOW_HUD_RADAR, "on");
                    assertTrue(o.flag(ClientOption.SHOW_HUD_RADAR), "on is true");
                },
                () -> {
                    o.apply(ClientOption.SHOW_HUD_RADAR, "1");
                    assertFalse(o.flag(ClientOption.SHOW_HUD_RADAR), "1 is false (C ON)");
                });
        }

        boolean given(ClientOptions o, ClientOption c) {
            return o.given(c);
        }

        @Test
        @DisplayName("numbers parse, clamp to the range with a report, and keep the old value on garbage")
        void numbers() {
            ClientOptions o = new ClientOptions();
            String clamped = o.apply(ClientOption.SHOT_SIZE, "12");
            int afterClamp = o.integer(ClientOption.SHOT_SIZE);
            int v1 = o.version();
            String bad = o.apply(ClientOption.SPARK_SIZE, "big");
            String trunc = o.apply(ClientOption.FUEL_NOTIFY, " 250.9 ");
            String low = o.apply(ClientOption.SPARK_PROB, "-0.5");
            assertAll(
                () -> assertEquals("shotSize: 12 is outside 1-8, using 8", clamped, "clamped and reported"),
                () -> assertEquals(8, afterClamp, "clamped to the maximum"),
                () -> assertTrue(o.given(ClientOption.SHOT_SIZE), "a clamped value is still given"),
                () -> assertEquals(1, v1, "a clamped value bumps the version"),
                () -> assertEquals("sparkSize: not a number: 'big'", bad, "garbage reported"),
                () -> assertEquals(2, o.integer(ClientOption.SPARK_SIZE), "garbage keeps the default"),
                () -> assertFalse(o.given(ClientOption.SPARK_SIZE), "garbage is not given"),
                () -> assertNull(trunc, "an int accepts a decimal number"),
                () -> assertEquals(250, o.integer(ClientOption.FUEL_NOTIFY), "and truncates it (C atoi)"),
                () -> assertEquals("sparkProb: -0.5 is outside 0-1, using 0", low, "below the minimum"),
                () -> assertEquals(0.0, o.real(ClientOption.SPARK_PROB), "clamped to the minimum"),
                () -> assertEquals("speedFactHUD: not a number: 'NaN'", o.apply(ClientOption.SPEED_FACT_HUD, "NaN"),
                    "NaN is no number"),
                () -> assertEquals("speedFactHUD: not a number: 'Infinity'",
                    o.apply(ClientOption.SPEED_FACT_HUD, "Infinity"), "infinity is no number"),
                () -> assertEquals(3, o.version(), "only the stored values bumped the version"));
        }

        @Test
        @DisplayName("an option without a range takes any number; strings are kept as given")
        void unbounded() {
            ClientOptions o = new ClientOptions();
            assertAll(
                () -> assertNull(o.apply(ClientOption.MSG3, "  hi %n  "), "no problem"),
                () -> assertEquals("  hi %n  ", o.text(ClientOption.MSG3), "string kept verbatim"),
                () -> assertNull(o.apply(ClientOption.FUEL_WARNING, "1000"), "at the maximum"),
                () -> assertEquals(1000, o.integer(ClientOption.FUEL_WARNING), "max kept"));
        }
    }

    @Nested
    @DisplayName("panel setters")
    class Setters {
        @Test
        @DisplayName("setters clamp, mark given, and bump the version only on a change")
        void setters() {
            ClientOptions o = new ClientOptions();
            o.setFlag(ClientOption.SHOW_HUD, true);
            int v0 = o.version();
            boolean g0 = o.given(ClientOption.SHOW_HUD);
            o.setFlag(ClientOption.SHOW_HUD, false);
            int v1 = o.version();
            o.setInteger(ClientOption.HUD_COLOR, 20);
            int hud = o.integer(ClientOption.HUD_COLOR);
            o.setReal(ClientOption.SPARK_PROB, 0.25);
            o.setReal(ClientOption.SPARK_PROB, 0.25);
            int v3 = o.version();
            o.setReal(ClientOption.SHOW_SCORE_DECIMALS, 1.9);
            o.setText(ClientOption.MSG1, "x");
            o.setText(ClientOption.MSG1, "x");
            assertAll(
                () -> assertEquals(0, v0, "same flag: no new version"),
                () -> assertTrue(g0, "but given"),
                () -> assertEquals(1, v1, "changed flag: new version"),
                () -> assertEquals(15, hud, "colour clamped to 15"),
                () -> assertEquals(3, v3, "same real twice: one bump"),
                () -> assertEquals(1, o.integer(ClientOption.SHOW_SCORE_DECIMALS), "INT via setReal truncates"),
                () -> assertEquals("x", o.text(ClientOption.MSG1), "text set"),
                () -> assertTrue(o.given(ClientOption.MSG1), "text given"),
                () -> assertEquals(4, o.version(), "same text twice: one bump (1.9 truncated to the default 1)"));
        }

        @Test
        @DisplayName("copy is independent and keeps values, given flags and version")
        void copy() {
            ClientOptions o = new ClientOptions();
            o.setFlag(ClientOption.CLOCK, true);
            o.setReal(ClientOption.SPARK_PROB, 0.75);
            o.setText(ClientOption.MSG2, "m");
            ClientOptions c = o.copy();
            o.setFlag(ClientOption.CLOCK, false);
            assertAll(
                () -> assertTrue(c.flag(ClientOption.CLOCK), "flag copied"),
                () -> assertEquals(0.75, c.real(ClientOption.SPARK_PROB), "number copied"),
                () -> assertEquals("m", c.text(ClientOption.MSG2), "text copied"),
                () -> assertTrue(c.given(ClientOption.CLOCK), "given copied"),
                () -> assertEquals(3, c.version(), "version copied"),
                () -> assertFalse(o.flag(ClientOption.CLOCK), "the original changes alone"));
        }
    }

    @Nested
    @DisplayName("saveText (C Config_save formats)")
    class Save {
        @Test
        @DisplayName("True/False, %d, %.3f and verbatim strings")
        void formats() {
            ClientOptions o = new ClientOptions();
            o.setReal(ClientOption.SPEED_FACT_HUD, -1.25);
            assertAll(
                () -> assertEquals("True", o.saveText(ClientOption.SHOW_HUD), "bool true"),
                () -> assertEquals("False", o.saveText(ClientOption.CLOCK), "bool false"),
                () -> assertEquals("500", o.saveText(ClientOption.FUEL_NOTIFY), "int"),
                () -> assertEquals("-1.250", o.saveText(ClientOption.SPEED_FACT_HUD), "float with 3 decimals"),
                () -> assertEquals("5,6,7,3", o.saveText(ClientOption.SPARK_COLORS), "string"));
        }

        @Test
        @DisplayName("ON is the C prefix test")
        void on() {
            assertAll(
                () -> assertTrue(ClientOptions.on("yesplease"), "prefix yes"),
                () -> assertTrue(ClientOptions.on("True"), "True"),
                () -> assertFalse(ClientOptions.on("No"), "No"),
                () -> assertFalse(ClientOptions.on(""), "empty"));
        }
    }
}
