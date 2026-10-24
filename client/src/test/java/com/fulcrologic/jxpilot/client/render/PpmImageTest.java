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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PpmImageTest {
    static byte[] ppm(String header, int... rgb) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        for (int v : rgb) {
            b.write(v);
        }
        return b.toByteArray();
    }

    @Test
    void readsPixelsWithBlackTransparent() {
        int[] size = new int[2];
        int[] px = PpmImage.parse(ppm("P6\n# made by hand\n2 1\n255\n", 255, 0, 0, 0, 0, 0), size);
        assertAll(
            () -> assertArrayEquals(new int[] {2, 1}, size, "width and height"),
            () -> assertEquals(Palette.pack(255, 0, 0, 255), px[0], "red, opaque"),
            () -> assertEquals(0, px[1], "black is transparent"),
            () -> assertEquals(Palette.pack(1, 0, 0, 255), PpmImage.parse(ppm("P6 1 1 255 ", 1, 0, 0), size)[0],
                "almost black is opaque; blanks separate the header"));
    }

    @Test
    void badFilesAreRefused() {
        int[] size = new int[2];
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> PpmImage.parse(ppm("P3\n1 1\n255\n", 0, 0, 0), size),
                "ASCII PPM"),
            () -> assertThrows(IllegalArgumentException.class, () -> PpmImage.parse(new byte[] {'P'}, size), "too short"),
            () -> assertThrows(IllegalArgumentException.class, () -> PpmImage.parse(ppm("P6\n1 1\n15\n", 0, 0, 0), size),
                "max 15 (C wants 255)"),
            () -> assertThrows(IllegalArgumentException.class, () -> PpmImage.parse(ppm("P6\n0 1\n255\n"), size),
                "zero width"),
            () -> assertThrows(IllegalArgumentException.class, () -> PpmImage.parse(ppm("P6\nx 1\n255\n"), size),
                "no number"),
            () -> assertThrows(IllegalArgumentException.class, () -> PpmImage.parse(ppm("P6\n1 1\n255"), size),
                "no blank after the maximum"),
            () -> assertThrows(IllegalArgumentException.class, () -> PpmImage.parse(ppm("P6\n2 1\n255\n", 1, 2, 3), size),
                "too few pixel bytes"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> PpmImage.parse(ppm("P6\n99999999 1\n255\n"), size), "an absurd size"));
    }

    @Test
    void edgesOfTheHeader() {
        int[] size = new int[2];
        int[] px = PpmImage.parse(ppm("P6\n# one\n# two\n10 9\n255\n", new int[270]), size);
        IllegalArgumentException two = assertThrows(IllegalArgumentException.class,
            () -> PpmImage.parse(new byte[] {'P', '6'}, size));
        assertAll(
            () -> assertArrayEquals(new int[] {10, 9}, size, "comments skipped; digits 0 and 9 read"),
            () -> assertEquals(90, px.length, "90 pixels"),
            () -> assertTrue(two.getMessage().contains("unsupported"), "just the magic: a header without numbers"),
            () -> assertThrows(IllegalArgumentException.class, () -> PpmImage.parse(ppm("P6\n1 0\n255\n"), size),
                "zero height"),
            () -> assertThrows(IllegalArgumentException.class, () -> PpmImage.parse(ppm("P6\n1 2\n255\n", 1, 2, 3), size),
                "two rows need six bytes"),
            () -> assertThrows(IllegalArgumentException.class, () -> PpmImage.parse(ppm("P6 #"), size),
                "a comment to the end"));
    }
}
