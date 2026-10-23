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
            int[] a = RadarImage.build(map(false, middle, wallTopTargetMiddle, targetBottom), P, true);
            int[] b = RadarImage.build(map(false, wallTopTargetMiddle, middle, targetBottom), P, true);
            assertAll(
                () -> assertEquals(1, RadarImage.height(map(false, middle, wallTopTargetMiddle, targetBottom)), "one row"),
                () -> assertEquals(WALL, px(a, 0, 0), "wall below target: the last-cell change is not repainted (C quirk)"),
                () -> assertEquals(TARGET, px(b, 0, 0), "target below wall: likewise keeps the target colour"));
        }
    }
}
