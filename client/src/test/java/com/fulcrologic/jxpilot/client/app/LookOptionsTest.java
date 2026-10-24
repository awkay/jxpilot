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

import com.fulcrologic.jxpilot.client.game.options.ClientOption;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.paint.PaintStyle;
import com.fulcrologic.jxpilot.client.render.Palette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;

import static com.fulcrologic.jxpilot.client.game.options.ClientOption.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Look options")
class LookOptionsTest {
    static int[] sparks(String spec, int max) {
        int[] out = new int[PaintStyle.MAX_SPARK_COLORS];
        return Arrays.copyOf(out, LookOptions.sparkColors(spec, max, out));
    }

    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ClientOptions o = new ClientOptions();
    final LookOptions look = new LookOptions(o, new PrintStream(out, true));
    final Palette p = Palette.xpilotDefault();
    final PaintStyle s = new PaintStyle();

    @Nested
    @DisplayName("apply")
    class Apply {
        @Test
        @DisplayName("applies once per options change")
        void oncePerChange() {
            boolean first = look.apply(p, s);
            int look0 = s.mapLook;
            boolean again = look.apply(p, s);
            o.setFlag(SHOW_NASTY_SHOTS, true);
            boolean changed = look.apply(p, s);
            assertAll(
                () -> assertTrue(first, "the first call applies"),
                () -> assertEquals(1, look0, "and bumps the map look"),
                () -> assertFalse(again, "an unchanged version does not"),
                () -> assertTrue(changed, "a change applies again"),
                () -> assertTrue(s.nastyShots, "with the new value"),
                () -> assertEquals(look0, s.mapLook, "nasty shots do not change the map look"));
        }

        @Test
        @DisplayName("copies the painter options")
        void painterOptions() {
            o.apply(SHOT_SIZE, "6");
            o.apply(SPARK_SIZE, "4");
            o.apply(SPARK_PROB, "1.0");
            o.apply(SHOW_NASTY_SHOTS, "yes");
            o.apply(MARKING_LIGHTS, "no");
            o.apply(SHOW_SHIP_NAME, "no");
            o.apply(TEXTURED_BALLS, "yes");
            o.apply(TEXTURED_OBJECTS, "yes");
            o.apply(SPARK_COLORS, "1 2");
            o.apply(BACKGROUND_POINT_DIST, "3");
            o.apply(BACKGROUND_POINT_SIZE, "5");
            look.apply(p, s);
            assertAll(
                () -> assertEquals(6, s.shotSize, "shotSize"),
                () -> assertEquals(4, s.sparkSize, "sparkSize"),
                () -> assertEquals(128, s.sparkRand, "sparkProb 1 is spark_rand 128"),
                () -> assertTrue(s.nastyShots, "showNastyShots"),
                () -> assertFalse(s.markingLights, "markingLights"),
                () -> assertFalse(s.showShipName, "showShipName"),
                () -> assertTrue(s.texturedBalls, "texturedBalls"),
                () -> assertTrue(s.texturedObjects, "texturedObjects"),
                () -> assertEquals(2, s.sparkCount(), "sparkColors count"),
                () -> assertEquals(2, s.sparkColor(1), "sparkColors values"),
                () -> assertEquals(3, s.pointDist, "backgroundPointDist"),
                () -> assertEquals(5, s.pointSize, "backgroundPointSize"));
        }

        @Test
        @DisplayName("each map-look input bumps the map look")
        void mapLookInputs() {
            look.apply(p, s);
            ClientOption[] flags = {OUTLINE_WORLD, FILLED_WORLD, TEXTURED_WALLS, OUTLINE_DECOR, FILLED_DECOR,
                TEXTURED_DECOR, TEXTURED_OBJECTS};
            for (ClientOption f : flags) {
                int before = s.mapLook;
                o.setFlag(f, true);
                look.apply(p, s);
                assertEquals(before + 1, s.mapLook, f.resource + " bumps the map look");
            }
            int[] bumps = new int[8];
            int b0 = s.mapLook;
            o.setFlag(SHOW_DECOR, false);
            look.apply(p, s);
            bumps[0] = s.mapLook - b0;
            ClientOption[] ints = {BACKGROUND_POINT_DIST, BACKGROUND_POINT_SIZE, WALL_COLOR, DECOR_COLOR, WALL_RADAR_COLOR,
                DECOR_RADAR_COLOR, TARGET_RADAR_COLOR};
            int[] values = {4, 3, 5, 7, 3, 1, 5};
            for (int i = 0; i < ints.length; i++) {
                int before = s.mapLook;
                o.setInteger(ints[i], values[i]);
                look.apply(p, s);
                bumps[i + 1] = s.mapLook - before;
            }
            int before = s.mapLook;
            o.setText(ClientOption.color(3), "#010203");
            look.apply(p, s);
            int colourBump = s.mapLook - before;
            assertAll(
                () -> assertArrayEquals(new int[] {1, 1, 1, 1, 1, 1, 1, 1}, bumps,
                    "showDecor, dot distance and size, wall/decor/radar colours each bump it once"),
                () -> assertEquals(1, colourBump, "a palette colour change bumps it"));
        }

        @Test
        @DisplayName("colours: color0-15, aliases, maxColors and bad names")
        void colours() {
            o.apply(ClientOption.color(1), "red");
            o.apply(BLACK, "#102030");
            o.apply(BLUE, "#ffffff");
            o.apply(ClientOption.color(2), "#00ff00");
            o.apply(ClientOption.color(5), "no such colour");
            o.apply(ClientOption.color(9), "#123456");
            look.apply(p, s);
            int[] eight = new int[16];
            for (int i = 0; i < 16; i++) {
                eight[i] = p.color(i);
            }
            o.apply(MAX_COLORS, "16");
            look.apply(p, s);
            String log = out.toString();
            assertAll(
                () -> assertEquals(Palette.pack(0x10, 0x20, 0x30, 255), eight[0], "black alias sets colour 0"),
                () -> assertEquals(Palette.pack(255, 0, 0, 255), eight[1], "color1 by name"),
                () -> assertEquals(Palette.pack(0, 255, 0, 255), eight[2], "color2 wins over the blue alias"),
                () -> assertEquals(Palette.defaultColor(3), eight[3], "unset: C's default"),
                () -> assertEquals(Palette.defaultColor(5), eight[5], "a bad name keeps the default"),
                () -> assertEquals(eight[1], eight[9], "with 8 colours entry 9 repeats entry 1 (color9 unused)"),
                () -> assertEquals(eight[7], eight[15], "entry 15 repeats entry 7"),
                () -> assertEquals(Palette.pack(0x12, 0x34, 0x56, 255), p.color(9), "with 16 colours color9 is used"),
                () -> assertEquals(16, s.maxColors, "maxColors 16"),
                () -> assertTrue(log.contains("Can't parse color 5 \"no such colour\""), "a bad name is reported as C does"));
        }

        @Test
        @DisplayName("colour indices are validated as Init_playing_windows does")
        void indices() {
            o.apply(MAX_COLORS, "4");
            o.apply(HUD_COLOR, "5");
            o.apply(HUD_LOCK_COLOR, "0");
            o.apply(WALL_COLOR, "0");
            o.apply(WALL_RADAR_COLOR, "7");
            o.apply(TARGET_RADAR_COLOR, "6");
            o.apply(OLD_MESSAGES_COLOR, "9");
            o.apply(DECOR_COLOR, "4");
            o.apply(DECOR_RADAR_COLOR, "4");
            look.apply(p, s);
            PaintStyle ok = new PaintStyle();
            ClientOptions o2 = new ClientOptions();
            o2.apply(HUD_COLOR, "3");
            o2.apply(HUD_LOCK_COLOR, "1");
            o2.apply(WALL_RADAR_COLOR, "0");
            o2.apply(OLD_MESSAGES_COLOR, "0");
            new LookOptions(o2, new PrintStream(out)).apply(Palette.xpilotDefault(), ok);
            assertAll(
                () -> assertEquals(4, s.maxColors, "maxColors 4"),
                () -> assertEquals(Palette.BLUE, s.hudColor, "hudColor not below maxColors: blue"),
                () -> assertEquals(Palette.BLUE, s.hudLockColor, "hudLockColor 0: the hud colour"),
                () -> assertEquals(Palette.BLUE, s.wallColor, "wallColor 0: blue"),
                () -> assertEquals(Palette.BLUE, s.wallRadarColor, "wallRadarColor too big: blue"),
                () -> assertEquals(Palette.BLUE, s.targetRadarColor, "targetRadarColor too big: blue"),
                () -> assertEquals(Palette.WHITE, s.oldMessagesColor, "oldMessagesColor too big: white"),
                () -> assertEquals(Palette.RED, s.decorColor, "decorColor too big: red"),
                () -> assertEquals(2, s.decorRadarColor, "decorRadarColor too big: 2"),
                () -> assertEquals(3, ok.hudColor, "valid hudColor kept"),
                () -> assertEquals(1, ok.hudLockColor, "valid hudLockColor kept"),
                () -> assertEquals(0, ok.wallRadarColor, "radar colours may be 0"),
                () -> assertEquals(0, ok.oldMessagesColor, "old messages may be 0"));
        }
    }

    @Nested
    @DisplayName("helpers")
    class Helpers {
        @Test
        @DisplayName("maxColors rounds down to 4, 8 or 16")
        void maxColors() {
            assertAll(
                () -> assertEquals(4, LookOptions.maxColors(7), "7 is 4"),
                () -> assertEquals(8, LookOptions.maxColors(8), "8 is 8"),
                () -> assertEquals(8, LookOptions.maxColors(15), "15 is 8"),
                () -> assertEquals(16, LookOptions.maxColors(16), "16 is 16"),
                () -> assertEquals(16, LookOptions.maxColors(99), "more is 16"));
        }

        @Test
        @DisplayName("sparkColors reads digit runs under maxColors")
        void sparkColors() {
            assertAll(
                () -> assertArrayEquals(new int[] {5, 6, 7, 3}, sparks("5,6,7,3", 8), "the default"),
                () -> assertArrayEquals(new int[] {1, 2, 3}, sparks("x1;2  3!", 8), "any separator"),
                () -> assertArrayEquals(new int[] {1, 7}, sparks("1 8 12 7", 8), "colours not below maxColors dropped"),
                () -> assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6, 7, 0, 1}, sparks("1 2 3 4 5 6 7 0 1 2 3", 8),
                    "at most nine"),
                () -> assertArrayEquals(new int[] {5, 6, 7, 3}, sparks("", 8), "none: 5..7 then red"),
                () -> assertArrayEquals(new int[] {3}, sparks("9", 4), "none with 4 colours: red only"),
                () -> assertArrayEquals(new int[] {5, 6, 7, 8, 9, 10, 11, 3}, sparks("x", 16), "none with 16: 5..11, red"),
                () -> assertArrayEquals(new int[] {3}, sparks("99999999999 3", 8), "a huge number is skipped"));
        }

        @Test
        @DisplayName("validColor replaces out-of-range indices")
        void validColor() {
            assertAll(
                () -> assertEquals(3, LookOptions.validColor(3, 4, 1, 9), "in range"),
                () -> assertEquals(9, LookOptions.validColor(4, 4, 1, 9), "equal to maxColors: fallback"),
                () -> assertEquals(9, LookOptions.validColor(0, 4, 1, 9), "below the minimum: fallback"),
                () -> assertEquals(0, LookOptions.validColor(0, 4, 0, 9), "0 allowed with minimum 0"));
        }
    }
}
