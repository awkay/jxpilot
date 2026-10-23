package com.fulcrologic.jxpilot.client.render;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class PaletteTest {
    @Nested
    class Pack {
        @Test
        void packsComponentsSoLittleEndianBytesAreRgba() {
            int c = Palette.pack(0x11, 0x22, 0x33, 0x44);
            ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, c);
            assertAll(
                () -> assertEquals(0x44332211, c, "packs as 0xAABBGGRR"),
                () -> assertEquals(0x11, b.get(0), "red is the first byte in memory"),
                () -> assertEquals(0x44, b.get(3), "alpha is the last byte in memory"),
                () -> assertEquals(0x000000FF, Palette.pack(0x1FF, 0, 0, 0), "masks red to 8 bits"),
                () -> assertEquals(0x0000FF00, Palette.pack(0, 0x1FF, 0, 0), "masks green to 8 bits"),
                () -> assertEquals(0x00FF0000, Palette.pack(0, 0, 0x1FF, 0), "masks blue to 8 bits"),
                () -> assertEquals(0xFF000000, Palette.pack(0, 0, 0, 0x1FF), "masks alpha to 8 bits"));
        }

        @Test
        void withAlphaReplacesOnlyAlpha() {
            assertAll(
                () -> assertEquals(Palette.pack(1, 2, 3, 128), Palette.withAlpha(Palette.pack(1, 2, 3, 255), 128),
                    "keeps rgb and sets the new alpha"),
                () -> assertEquals(Palette.pack(1, 2, 3, 0xFF), Palette.withAlpha(Palette.pack(1, 2, 3, 0), 0x1FF),
                    "masks the new alpha to 8 bits"));
        }
    }

    @Nested
    class ParseHex {
        @Test
        void parsesOpaqueColoursCaseInsensitively() {
            assertAll(
                () -> assertEquals(Palette.pack(0x4E, 0x7C, 0xFF, 255), Palette.parseHex("#4E7CFF"), "upper case hex"),
                () -> assertEquals(Palette.pack(0x9F, 0x9F, 0x9F, 255), Palette.parseHex("#9f9f9f"), "lower case hex"));
        }

        @Test
        void rejectsMalformedColours() {
            assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> Palette.parseHex("x4E7CFF"), "requires the # prefix"),
                () -> assertThrows(IllegalArgumentException.class, () -> Palette.parseHex("#4E7CF"), "requires six digits"),
                () -> assertThrows(IllegalArgumentException.class, () -> Palette.parseHex("#4E7CFF0"), "rejects seven digits"),
                () -> assertThrows(NumberFormatException.class, () -> Palette.parseHex("#4E7CFG"), "requires hex digits"));
        }
    }

    @Test
    void defaultTableIsXPilotsColorNames() {
        Palette p = Palette.xpilotDefault();
        assertAll(
            () -> assertEquals(16, p.size(), "has 16 colours"),
            () -> assertEquals(Palette.pack(0, 0, 0, 255), p.color(Palette.BLACK), "0 is black"),
            () -> assertEquals(Palette.pack(255, 255, 255, 255), p.color(Palette.WHITE), "1 is white"),
            () -> assertEquals(Palette.pack(0x4E, 0x7C, 0xFF, 255), p.color(Palette.BLUE), "2 is XPilot blue #4E7CFF"),
            () -> assertEquals(Palette.pack(0xFF, 0x3A, 0x27, 255), p.color(Palette.RED), "3 is XPilot red #FF3A27"),
            () -> assertEquals(Palette.pack(0x33, 0xBB, 0x44, 255), p.color(4), "4 is green #33BB44"),
            () -> assertEquals(Palette.pack(0xBB, 0x77, 0x00, 255), p.color(6), "6 is #BB7700"),
            () -> assertEquals(Palette.pack(0x20, 0x20, 0x20, 255), p.color(15), "15 is #202020"),
            () -> assertEquals(2, Palette.WALL_COLOR, "wallColor default is 2"),
            () -> assertEquals(2, Palette.WALL_RADAR_COLOR, "wallRadarColor default is 2"),
            () -> assertEquals(6, Palette.DECOR_COLOR, "decorColor default is 6"),
            () -> assertEquals(6, Palette.DECOR_RADAR_COLOR, "decorRadarColor default is 6"),
            () -> assertEquals(4, Palette.HUD_COLOR, "hudColor default is 4"),
            () -> assertEquals(4, Palette.TARGET_RADAR_COLOR, "targetRadarColor default is 4"));
    }
}
