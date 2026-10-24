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
package com.fulcrologic.jxpilot.client.paint;

import com.fulcrologic.jxpilot.client.render.Palette;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaintStyleTest {
    @Nested
    class Defaults {
        @Test
        void aNewStyleHoldsCsDefaults() {
            PaintStyle s = new PaintStyle();
            assertAll(
                () -> assertEquals(3, s.shotSize, "shotSize 3"),
                () -> assertEquals(2, s.sparkSize, "sparkSize 2"),
                () -> assertEquals(64, s.sparkRand, "sparkProb 0.5"),
                () -> assertEquals(4, s.sparkCount(), "sparkColors 5,6,7,3"),
                () -> assertEquals(3, s.sparkColor(3), "last spark colour red"),
                () -> assertFalse(s.nastyShots, "plain shots"),
                () -> assertTrue(s.markingLights, "marking lights"),
                () -> assertTrue(s.showShipName, "ship names"),
                () -> assertEquals(8, s.maxColors, "8 colours"),
                () -> assertEquals(4, s.hudColor, "hud 4"),
                () -> assertEquals(2, s.wallColor, "walls blue"),
                () -> assertEquals(6, s.decorColor, "decor 6"),
                () -> assertTrue(s.showDecor, "decorations shown"),
                () -> assertEquals(8, s.pointDist, "dots every 8 blocks"),
                () -> assertEquals(2, s.pointSize, "2-pixel dots"));
        }
    }

    @Nested
    class LevelColor {
        @Test
        void upToFourColoursMapLevelsDirectlyAndClampToTheLast() {
            PaintStyle s = new PaintStyle();
            assertAll(
                () -> assertEquals(5, s.levelColor(0), "level 0 is the first colour"),
                () -> assertEquals(7, s.levelColor(2), "level 2 the third"),
                () -> assertEquals(3, s.levelColor(9), "past the colours: the last one (C pads)"),
                () -> assertEquals(5, s.levelColor(-1), "a negative level is level 0"));
        }

        @Test
        void moreThanFourColoursInterleave() {
            PaintStyle s = new PaintStyle();
            s.setSparkColors(new int[] {10, 11, 12, 13, 14, 15, 1, 2, 3}, 6);
            assertAll(
                () -> assertEquals(6, s.sparkCount(), "six colours"),
                () -> assertEquals(10, s.levelColor(0), "level 0 -> index 0"),
                () -> assertEquals(14, s.levelColor(1), "level 1 -> index 4"),
                () -> assertEquals(11, s.levelColor(2), "level 2 -> index 1"),
                () -> assertEquals(15, s.levelColor(3), "level 3 -> index 5"),
                () -> assertEquals(15, s.levelColor(5), "level 5 -> index 6, past the six: the last"));
        }
    }

    @Nested
    class Derived {
        @Test
        void colourCountAndLooksDecideTheDerivedValues() {
            PaintStyle s = new PaintStyle();
            boolean filled0 = s.wallsFilled();
            boolean outlined0 = s.wallsOutlined();
            int friend8 = s.radarFriendColor();
            int light8 = s.rightLightColor();
            s.maxColors = 4;
            s.outlineWorld = true;
            boolean outlinedByOutline = s.wallsOutlined();
            boolean filledByOutline = s.wallsFilled();
            s.outlineWorld = false;
            s.texturedWalls = true;
            boolean filledByTexture = s.wallsFilled();
            boolean outlinedByTexture = s.wallsOutlined();
            s.texturedWalls = false;
            s.filledWorld = true;
            boolean outlinedByFill = s.wallsOutlined();
            boolean decor0 = s.decorFilled();
            s.texturedDecor = true;
            boolean decorTextured = s.decorFilled();
            s.texturedDecor = false;
            s.filledDecor = true;
            assertAll(
                () -> assertFalse(filled0, "default walls are not filled"),
                () -> assertFalse(outlined0, "nor outlined"),
                () -> assertEquals(4, friend8, "friends green with 8 colours"),
                () -> assertEquals(4, light8, "right lights green with 8 colours"),
                () -> assertEquals(Palette.WHITE, s.radarFriendColor(), "friends white with 4 colours"),
                () -> assertEquals(Palette.BLUE, s.rightLightColor(), "right lights blue with 4 colours"),
                () -> assertTrue(outlinedByOutline, "outlineWorld outlines"),
                () -> assertFalse(filledByOutline, "but does not fill"),
                () -> assertTrue(filledByTexture, "texturedWalls fills"),
                () -> assertTrue(outlinedByTexture, "and is outline mode"),
                () -> assertTrue(outlinedByFill, "filledWorld is outline mode"),
                () -> assertTrue(s.wallsFilled(), "filledWorld fills"),
                () -> assertFalse(decor0, "decor not filled by default"),
                () -> assertTrue(decorTextured, "texturedDecor fills decor"),
                () -> assertTrue(s.decorFilled(), "filledDecor fills decor"));
        }
    }
}
