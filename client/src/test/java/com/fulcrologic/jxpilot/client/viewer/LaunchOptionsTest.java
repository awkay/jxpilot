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
package com.fulcrologic.jxpilot.client.viewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LaunchOptionsTest {
    static LaunchOptions parse(String... a) {
        return LaunchOptions.parse(a);
    }

    static String error(String... a) {
        return assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parse(a)).getMessage();
    }

    @Test
    void defaults() {
        LaunchOptions o = parse();
        assertAll(
            () -> assertNull(o.viewMap(), "no map"),
            () -> assertNull(o.screenshot(), "no screenshot"),
            () -> assertEquals(3, o.frames(), "3 frames"),
            () -> assertEquals(1280, o.width(), "1280 wide"),
            () -> assertEquals(800, o.height(), "800 high"),
            () -> assertEquals(1.0, o.zoom(), "zoom 1"),
            () -> assertFalse(o.filled(), "outline walls"),
            () -> assertFalse(o.hasCentre(), "no explicit centre"),
            () -> assertTrue(o.vsync(), "vsync on"));
    }

    @Test
    void parsesEveryOption() {
        LaunchOptions o = parse("--view-map", "m.xp", "--screenshot", "s.png", "--frames", "7", "--size", "640x480",
            "--zoom", "0.5", "--filled", "--at", "10.5,-3", "--no-vsync");
        assertAll(
            () -> assertEquals("m.xp", o.viewMap(), "map file"),
            () -> assertEquals("s.png", o.screenshot(), "screenshot file"),
            () -> assertEquals(7, o.frames(), "frames"),
            () -> assertEquals(640, o.width(), "width"),
            () -> assertEquals(480, o.height(), "height"),
            () -> assertEquals(0.5, o.zoom(), "zoom"),
            () -> assertTrue(o.filled(), "filled walls"),
            () -> assertTrue(o.hasCentre(), "centre given"),
            () -> assertEquals(10.5, o.centreX(), "centre x"),
            () -> assertEquals(-3.0, o.centreY(), "centre y"),
            () -> assertFalse(o.vsync(), "vsync off"));
    }

    @Test
    void rejectsBadInput() {
        assertAll(
            () -> assertEquals("Unknown option: --bogus", error("--bogus"), "unknown flag"),
            () -> assertEquals("--frames needs a value", error("--frames"), "missing value"),
            () -> assertEquals("--frames expects an integer: x", error("--frames", "x"), "not an integer"),
            () -> assertEquals("--frames must be positive: 0", error("--frames", "0"), "zero frames"),
            () -> assertEquals("--size expects WxH: 640", error("--size", "640"), "size without x"),
            () -> assertEquals("--size must be positive: -1", error("--size", "-1x5"), "negative width"),
            () -> assertEquals("--zoom must be positive: 0.0", error("--zoom", "0"), "zero zoom"),
            () -> assertEquals("--zoom expects a number: z", error("--zoom", "z"), "non-numeric zoom"),
            () -> assertEquals("--at expects X,Y: 5", error("--at", "5"), "centre without comma"),
            () -> assertEquals("--at expects a number: a", error("--at", "a,1"), "non-numeric centre"),
            () -> assertEquals("--view-map needs a value", error("--view-map"), "map flag without file"),
            () -> assertEquals("--size expects an integer: ", error("--size", "x5"), "separator first: empty width"),
            () -> assertEquals("--at expects a number: ", error("--at", ",5"), "separator first: empty x"));
    }
}
