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
package com.fulcrologic.jxpilot.client.map;

import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.client.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

class RadarImageTest {
    static final Palette P = Palette.xpilotDefault();
    static final int BLACK = P.color(Palette.BLACK);
    static final int WALL = P.color(Palette.WALL_RADAR_COLOR);
    static final int TARGET = P.color(Palette.TARGET_RADAR_COLOR);
    static final int DECOR = P.color(Palette.DECOR_RADAR_COLOR);

    static int px(int[] img, int x, int row) {
        return img[row * RadarImage.WIDTH + x];
    }

    static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    @Test
    void heightIsWidthScaledByTheAspectRatio() {
        assertAll(
            () -> assertEquals(256, RadarImage.height(map(false, "..", "..")), "square map: 256"),
            () -> assertEquals(128, RadarImage.height(map(false, "....", "....")), "2:1 map: 128"),
            () -> assertEquals(85, RadarImage.height(map(false, ".........", ".........", ".........")),
                "9x3: 256*3/9 = 85.3 truncates to 85"),
            () -> assertEquals(1, RadarImage.height(map(false, repeat('.', 600))), "never below 1 pixel"));
    }

    @Test
    void colourIndexPerBlockType() {
        assertAll(
            () -> assertEquals(Palette.WALL_RADAR_COLOR, RadarImage.colorIndex(BlockType.FILLED, true), "filled"),
            () -> assertEquals(Palette.WALL_RADAR_COLOR, RadarImage.colorIndex(BlockType.REC_LD, true), "diagonal"),
            () -> assertEquals(Palette.WALL_RADAR_COLOR, RadarImage.colorIndex(BlockType.FUEL, true), "fuel"),
            () -> assertEquals(Palette.TARGET_RADAR_COLOR, RadarImage.colorIndex(BlockType.TARGET, true), "target"),
            () -> assertEquals(Palette.DECOR_RADAR_COLOR, RadarImage.colorIndex(BlockType.DECOR_FILLED, true), "decor shown"),
            () -> assertEquals(-1, RadarImage.colorIndex(BlockType.DECOR_FILLED, false), "decor hidden"),
            () -> assertEquals(-1, RadarImage.colorIndex(BlockType.CANNON, true), "cannons not shown"),
            () -> assertEquals(-1, RadarImage.colorIndex(BlockType.SPACE, true), "space not shown"));
    }

    @Nested
    class TargetRepaint {
        @Test
        void aWideMapsBlockIsOnePixel() {
            int[] px = new int[256];
            RadarImage.block(px, 1, 512, 3, 10, 1, TARGET);
            assertAll(
                () -> assertEquals(TARGET, px[5], "x (int)(10 * 255/511 + 0.5) = 5, the only row"),
                () -> assertEquals(1, java.util.Arrays.stream(px).filter(v -> v == TARGET).count(), "one pixel"));
        }

        @Test
        void aWideMapsBlockRoundsBothAxes() {
            int[] px = new int[256 * 256];
            RadarImage.block(px, 256, 512, 512, 300, 300, TARGET);
            int[] exact = new int[256 * 256];
            RadarImage.block(exact, 256, 256, 3, 0, 0, TARGET);
            assertAll(
                () -> assertEquals(TARGET, px(px, 150, 105), "(int)(300 * 255/511 + 0.5) = 150; row 255 - 150 = 105"),
                () -> assertEquals(1, java.util.Arrays.stream(px).filter(v -> v == TARGET).count(), "one pixel"),
                () -> assertEquals(TARGET, px(exact, 0, 255), "a map exactly 256 wide is walked by block: pixel (0, 255)"),
                () -> assertEquals(1, java.util.Arrays.stream(exact).filter(v -> v == TARGET).count(), "a single pixel"));
        }

        @Test
        void aNarrowMapsBlockFillsThePixelsThatSampleIt() {
            int[] px = new int[256 * 256];
            RadarImage.block(px, 256, 4, 4, 1, 1, TARGET);
            assertAll(
                () -> assertEquals(TARGET, px(px, 43, 128), "x from (int)(0.5 / (3/255)) + 1 = 43, row 255 - 127 = 128"),
                () -> assertEquals(TARGET, px(px, 127, 212), "to x (int)(1.5 * 85) = 127, row 128 + 84"),
                () -> assertEquals(0, px(px, 42, 128), "not left of it"),
                () -> assertEquals(0, px(px, 128, 212), "not right of it"),
                () -> assertEquals(0, px(px, 43, 127), "not above"),
                () -> assertEquals(0, px(px, 43, 213), "not below"),
                () -> assertEquals(85L * 85, java.util.Arrays.stream(px).filter(v -> v == TARGET).count(), "85 x 85 pixels"));
        }

        @Test
        void edgeBlocksAreClippedToTheRadar() {
            int[] px = new int[256 * 256];
            RadarImage.block(px, 256, 2, 2, 0, 1, TARGET);
            assertAll(
                () -> assertEquals(TARGET, px(px, 0, 0), "the top-left block covers from the corner"),
                () -> assertEquals(TARGET, px(px, 127, 127), "to the middle"),
                () -> assertEquals(128L * 128, java.util.Arrays.stream(px).filter(v -> v == TARGET).count(),
                    "the part outside the radar is dropped"));
        }

        @Test
        void theRightEdgeIsClippedNotWrappedIntoTheNextRow() {
            int[] px = new int[256 * 256];
            RadarImage.block(px, 256, 2, 2, 1, 0, TARGET);
            assertAll(
                () -> assertEquals(TARGET, px(px, 255, 128), "the bottom-right block reaches the right edge"),
                () -> assertEquals(0, px(px, 0, 129), "nothing spills to column 0 of the next row"),
                () -> assertEquals(128L * 128, java.util.Arrays.stream(px).filter(v -> v == TARGET).count(), "128 x 128"));
        }

        @Test
        void onlyLiveTargetsAreRepainted() {
            // 512 x 3: target at the top of column 0, wall below: the walk leaves the wall colour on row 0
            String targetTop = "!" + repeat('.', 511);
            String wall = "x" + repeat('.', 511);
            String empty = repeat('.', 512);
            int[] dead = RadarImage.build(map(false, targetTop, wall, empty), P, true, i -> false);
            int[] buffer = new int[RadarImage.WIDTH * 300];
            java.util.Arrays.fill(buffer, 12345);
            int[] into = RadarImage.build(map(false, targetTop, wall, empty), P, true, i -> false, buffer);
            int n = RadarImage.WIDTH * RadarImage.height(map(false, targetTop, wall, empty));
            assertSame(buffer, into, "R14: the given buffer is reused");
            assertArrayEquals(dead, java.util.Arrays.copyOf(into, n), "and filled exactly like a fresh build");
            int[] alive = RadarImage.build(map(false, targetTop, wall, empty), P, true, i -> i == 0);
            assertAll(
                () -> assertEquals(WALL, px(dead, 0, 0), "dead target: the walk's colour stays"),
                () -> assertEquals(TARGET, px(alive, 0, 0), "live target 0: repainted"));
        }
    }

    @Nested
    class NarrowMap {
        @Test
        void eachPixelSamplesTheNearestBlockWithRowZeroAtTheTop() {
            int[] img = RadarImage.build(map(false, "x.", ".."), P, true);
            assertAll(
                () -> assertEquals(256 * 256, img.length, "256 x 256 pixels"),
                () -> assertEquals(WALL, px(img, 0, 0), "top-left pixel shows the top-left wall"),
                () -> assertEquals(WALL, px(img, 127, 127), "up to the middle (x, y < 127.5)"),
                () -> assertEquals(BLACK, px(img, 128, 0), "right half is space"),
                () -> assertEquals(BLACK, px(img, 0, 128), "bottom half is space"));
        }

        @Test
        void colourChangeInAColumnRepaintsTheBoundaryWithTheNewColour() {
            // column 0 bottom-up: wall (by 0), target (by 1), space (by 2); ym = (int)(yi * 2/255 + 0.5)
            int[] img = RadarImage.build(map(false, "...", "!..", "x.."), P, true);
            assertAll(
                () -> assertEquals(WALL, px(img, 0, 255 - 63), "yi 63 samples the wall"),
                () -> assertEquals(TARGET, px(img, 0, 255 - 64), "yi 64, the first target pixel, ends up target"),
                () -> assertEquals(TARGET, px(img, 0, 255 - 191), "yi 191 still target"),
                () -> assertEquals(BLACK, px(img, 0, 255 - 192), "yi 192 samples space"));
        }

        @Test
        void decorationsOnlyWhenShown() {
            WorldMap m = map(false, "b.", "..");
            assertAll(
                () -> assertEquals(DECOR, px(RadarImage.build(m, P, true), 0, 0), "decor colour when shown"),
                () -> assertEquals(BLACK, px(RadarImage.build(m, P, false), 0, 0), "black when hidden"));
        }
    }

    @Nested
    class WideMap {
        @Test
        void blocksMapToPixelsAndSeveralBlocksShareOnePixel() {
            // 512 x 4: radar 256 x 2, xs = 255/511, ys = 1/3 (rows 0,1 -> radar y 0; rows 2,3 -> radar y 1)
            String top = "." + "x" + repeat('.', 508) + "xx";
            String empty = repeat('.', 512);
            String second = repeat('.', 100) + "x" + repeat('.', 411);
            int[] img = RadarImage.build(map(false, top, second, empty, empty), P, true);
            assertAll(
                () -> assertEquals(256 * 2, img.length, "256 x 2 pixels"),
                () -> assertEquals(WALL, px(img, 0, 0), "block 1 rounds to pixel 0 (top row)"),
                () -> assertEquals(BLACK, px(img, 0, 1), "bottom row empty"),
                () -> assertEquals(WALL, px(img, 255, 0), "blocks 510-511 land on pixel 255"),
                () -> assertEquals(BLACK, px(img, 128, 0), "space in between"),
                () -> assertEquals(WALL, px(img, 50, 0), "map row 2 (block 100) rounds into the top radar row: 2/3 + 0.5"),
                () -> assertEquals(BLACK, px(img, 50, 1), "not the bottom one"));
        }

        @Test
        void colourChangeOnTheLastCellOfAColumnKeepsTheOldColour() {
            // 512 x 3 -> radar height 1 (ys = 0): every block of a column lands on radar row 0
            String wallTopTargetMiddle = "x" + repeat('.', 511);
            String middle = "!" + repeat('.', 511);
            String targetBottom = repeat('.', 512);
            int[] a = RadarImage.build(map(false, middle, wallTopTargetMiddle, targetBottom), P, true, i -> false);
            int[] b = RadarImage.build(map(false, wallTopTargetMiddle, middle, targetBottom), P, true, i -> false);
            int[] live = RadarImage.build(map(false, middle, wallTopTargetMiddle, targetBottom), P, true);
            assertAll(
                () -> assertEquals(1, RadarImage.height(map(false, middle, wallTopTargetMiddle, targetBottom)), "one row"),
                () -> assertEquals(WALL, px(a, 0, 0), "wall below target: the walk's last-cell change is not repainted (C quirk)"),
                () -> assertEquals(TARGET, px(b, 0, 0), "target below wall: likewise keeps the target colour"),
                () -> assertEquals(TARGET, px(live, 0, 0), "a live target is repainted after the walk, as C does"));
        }
    }
}
