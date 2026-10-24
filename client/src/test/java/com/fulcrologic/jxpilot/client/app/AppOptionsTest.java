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

import com.fulcrologic.jxpilot.client.net.Endpoint;
import com.fulcrologic.jxpilot.client.xp.ConnectTarget;
import com.fulcrologic.jxpilot.common.net.Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppOptionsTest {
    static AppOptions parse(String... args) {
        return AppOptions.parse(args, "tony");
    }

    static String error(String... args) {
        return assertThrows(IllegalArgumentException.class, () -> parse(args)).getMessage();
    }

    @Nested
    @DisplayName("modes")
    class Modes {
        @Test
        @DisplayName("no arguments is HELP")
        void help() {
            assertEquals(AppOptions.Mode.HELP, parse().mode(), "help");
        }

        @Test
        @DisplayName("--view-map anywhere hands every argument to the viewer")
        void viewer() {
            AppOptions o = parse("--demo", "--view-map", "maps/x.xp", "--frames", "5");
            assertAll(
                () -> assertEquals(AppOptions.Mode.VIEW_MAP, o.mode(), "viewer mode"),
                () -> assertEquals("maps/x.xp", o.viewer().viewMap(), "viewer map"),
                () -> assertTrue(o.viewer().demo(), "viewer demo flag"),
                () -> assertEquals(5, o.viewer().frames(), "viewer frames"),
                () -> assertNull(o.target(), "no target"));
        }

        @Test
        @DisplayName("viewer errors propagate")
        void viewerError() {
            assertTrue(error("--view-map", "m.xp", "--frames", "0").contains("--frames"), "viewer validation");
        }

        @Test
        @DisplayName("--connect with defaults")
        void connectDefaults() {
            AppOptions o = parse("--connect", "example.org");
            assertAll(
                () -> assertEquals(AppOptions.Mode.CONNECT, o.mode(), "connect mode"),
                () -> assertEquals(new ConnectTarget(ConnectTarget.Protocol.JXPILOT, new Endpoint("example.org", 15345)),
                    o.target(), "native, default port"),
                () -> assertNull(o.bind(), "any local address"),
                () -> assertEquals("Tony", o.name(), "name from the user name"),
                () -> assertEquals(Protocol.NO_TEAM, o.team(), "no team"),
                () -> assertNull(o.shape(), "no shape given (the resource file's or the default ship)"),
                () -> assertNull(o.shipShapeFile(), "no shape file given"),
                () -> assertNull(o.localMap(), "no local map"),
                () -> assertNull(o.screenshot(), "no screenshot"),
                () -> assertEquals(AppOptions.DEFAULT_FRAMES, o.frames(), "default frames"),
                () -> assertEquals(1280, o.width(), "width"),
                () -> assertEquals(800, o.height(), "height"),
                () -> assertTrue(o.vsync(), "vsync"),
                () -> assertEquals(1.0, o.scaleFactor(), "scaleFactor 1"),
                () -> assertEquals(2.0, o.altScaleFactor(), "altScaleFactor 2"),
                () -> assertNull(o.swapScaleKey(), "swap key unbound"),
                () -> assertNull(o.fontFile(), "bundled font"),
                () -> assertEquals(13.0, o.fontSize(), "font size 13"),
                () -> assertNull(o.keysFile(), "keys from ~/.xpilotrc"),
                () -> assertTrue(o.sound(), "sound on"),
                () -> assertEquals(100, o.maxVolume(), "maxVolume 100 (C default)"));
        }

        @Test
        @DisplayName("--keys names the key file; --list-keys selects LIST_KEYS whatever else is given")
        void keys() {
            AppOptions c = parse("--connect", "h", "--keys", "my.rc");
            AppOptions l = parse("--list-keys", "--keys", "k.rc", "--swap-scale-key", "F12");
            AppOptions both = parse("--connect", "h", "--list-keys");
            assertAll(
                () -> assertEquals("my.rc", c.keysFile(), "--keys"),
                () -> assertEquals(AppOptions.Mode.CONNECT, c.mode(), "still connecting"),
                () -> assertEquals(AppOptions.Mode.LIST_KEYS, l.mode(), "--list-keys alone"),
                () -> assertEquals("k.rc", l.keysFile(), "keeps --keys"),
                () -> assertEquals("F12", l.swapScaleKey(), "keeps the swap key"),
                () -> assertEquals(AppOptions.Mode.LIST_KEYS, both.mode(), "--list-keys wins over --connect"),
                () -> assertTrue(error("--keys").contains("--keys needs a value"), "--keys needs a file"));
        }

        @Test
        @DisplayName("sound: --no-sound / --sound (the last wins) and --volume 0..255")
        void sound() {
            AppOptions off = parse("--connect", "h", "--no-sound", "--volume", "0");
            AppOptions on = parse("--local", "--map", "m.xp", "--no-sound", "--sound", "--volume", "255");
            assertAll(
                () -> assertFalse(off.sound(), "--no-sound turns it off"),
                () -> assertEquals(0, off.maxVolume(), "volume 0 is allowed (no sound, as C)"),
                () -> assertTrue(on.sound(), "a later --sound turns it back on"),
                () -> assertEquals(255, on.maxVolume(), "255 is the largest"),
                () -> assertEquals("--volume must be 0..255: 256", error("--connect", "h", "--volume", "256"), "too loud"),
                () -> assertEquals("--volume must be 0..255: -1", error("--connect", "h", "--volume", "-1"), "negative"),
                () -> assertEquals("--volume expects an integer: x", error("--connect", "h", "--volume", "x"), "not a number"),
                () -> assertTrue(parse("--view-map", "m.xp").sound(), "the viewer's options default to sound on"),
                () -> assertEquals(100, parse().maxVolume(), "help defaults to maxVolume 100"));
        }

        @Test
        @DisplayName("--connect with every option")
        void connectAll() {
            AppOptions o = parse("--connect", "[::1]:4000", "--protocol", "xpilot", "--bind", "10.0.0.2:5000",
                "--name", "Ace", "--team", "3", "--shape", "(SH: 15,0 -9,8 -9,-8)(GU: 15,0)", "--screenshot", "a.png",
                "--frames", "9", "--size", "640x480", "--no-vsync", "--scale-factor", "1.5", "--alt-scale-factor", "3",
                "--swap-scale-key", "F12", "--font", "f.ttf", "--font-size", "16");
            assertAll(
                () -> assertEquals(new ConnectTarget(ConnectTarget.Protocol.XPILOT, new Endpoint("::1", 4000)), o.target(),
                    "IPv6 with port, protocol flag"),
                () -> assertEquals(new Endpoint("10.0.0.2", 5000), o.bind(), "bind"),
                () -> assertEquals("Ace", o.name(), "name"),
                () -> assertEquals(3, o.team(), "team"),
                () -> assertEquals("(SH: 15,0 -9,8 -9,-8)(GU: 15,0)", o.shape(), "shape"),
                () -> assertEquals("a.png", o.screenshot(), "screenshot"),
                () -> assertEquals(9, o.frames(), "frames"),
                () -> assertEquals(640, o.width(), "width"),
                () -> assertEquals(480, o.height(), "height"),
                () -> assertFalse(o.vsync(), "no vsync"),
                () -> assertEquals(1.5, o.scaleFactor(), "scale factor"),
                () -> assertEquals(3.0, o.altScaleFactor(), "alt scale factor"),
                () -> assertEquals("F12", o.swapScaleKey(), "swap key"),
                () -> assertEquals("f.ttf", o.fontFile(), "font"),
                () -> assertEquals(16.0, o.fontSize(), "font size"));
        }

        @Test
        @DisplayName("--shipShape is --shape (the last one wins); --shipShapeFile names the shape file")
        void shipShape() {
            AppOptions o = parse("--connect", "h", "--shape", "a", "--shipShape", "dart", "--shipShapeFile", "ships.txt");
            AppOptions p = parse("--connect", "h", "--shipShape", "a", "--shape", "(SH: 15,0 -9,8 -9,-8)");
            assertAll(
                () -> assertEquals("dart", o.shape(), "--shipShape after --shape wins"),
                () -> assertEquals("(SH: 15,0 -9,8 -9,-8)", p.shape(), "--shape after --shipShape wins"),
                () -> assertEquals("ships.txt", o.shipShapeFile(), "shape file"),
                () -> assertNull(p.shipShapeFile(), "no shape file unless given"),
                () -> assertTrue(error("--connect", "h", "--shipShapeFile").contains("--shipShapeFile needs a value"),
                    "the shape file needs a value"));
        }

        @Test
        @DisplayName("an xp:// scheme selects the XPilot protocol and wins over --protocol")
        void scheme() {
            assertEquals(ConnectTarget.Protocol.XPILOT,
                parse("--connect", "xp://h:1", "--protocol", "jxpilot").target().protocol(), "scheme wins");
        }

        @Test
        @DisplayName("--local --map")
        void local() {
            AppOptions o = parse("--local", "--map", "maps/default.xp", "--name", "Bo");
            assertAll(
                () -> assertEquals(AppOptions.Mode.LOCAL, o.mode(), "local mode"),
                () -> assertEquals("maps/default.xp", o.localMap(), "map"),
                () -> assertNull(o.target(), "no target"),
                () -> assertEquals("Bo", o.name(), "name"));
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {
        @Test
        @DisplayName("mode conflicts and missing pieces")
        void modes() {
            assertAll(
                () -> assertTrue(error("--connect", "h", "--local", "--map", "m").contains("exclude"), "both modes"),
                () -> assertTrue(error("--local").contains("--map"), "local without map"),
                () -> assertTrue(error("--connect", "h", "--map", "m").contains("--local"), "map without local"),
                () -> assertTrue(error("--local", "--map", "m", "--protocol", "xp").contains("--connect"),
                    "protocol with local"),
                () -> assertTrue(error("--local", "--map", "m", "--bind", "1.2.3.4").contains("--connect"), "bind with local"),
                () -> assertTrue(error("--name", "x").contains("nothing to do"), "no mode"),
                () -> assertTrue(error("--bogus").contains("Unknown option: --bogus"), "unknown option"),
                () -> assertTrue(error("--connect").contains("--connect needs a value"), "missing value"));
        }

        @Test
        @DisplayName("value validation names the option")
        void values() {
            assertAll(
                () -> assertTrue(error("--connect", "h", "--name", "").contains("--name"), "empty name"),
                () -> assertTrue(error("--connect", "h", "--name", "x".repeat(33)).contains("--name"), "33 chars"),
                () -> assertEquals("x".repeat(32), parse("--connect", "h", "--name", "x".repeat(32)).name(), "32 ok"),
                () -> assertTrue(error("--connect", "h", "--team", "10").contains("0..9"), "team 10"),
                () -> assertTrue(error("--connect", "h", "--team", "-1").contains("0..9"), "team -1"),
                () -> assertEquals(0, parse("--connect", "h", "--team", "0").team(), "team 0 ok"),
                () -> assertEquals(9, parse("--connect", "h", "--team", " 9").team(), "team 9 ok, trimmed"),
                () -> assertTrue(error("--connect", "h", "--team", "x").contains("integer"), "team not a number"),
                () -> assertTrue(error("--connect", "h", "--shape", "(junk)").contains("--shape is not a valid ship shape"),
                    "a bad shape string (holding '(') is an error"),
                () -> assertTrue(error("--connect", "h", "--shipShape", "(SH: 1,1 2,2 3,3)").contains("--shipShape"),
                    "--shipShape is checked as --shape, under the server's limits"),
                () -> assertEquals("junk", parse("--connect", "h", "--shape", "junk").shape(),
                    "a value without '(' is a shape name, resolved later"),
                () -> assertEquals("", parse("--connect", "h", "--shape", "").shape(), "an empty shape is kept: the default ship"),
                () -> assertTrue(error("--connect", "h", "--frames", "0").contains("positive"), "frames 0"),
                () -> assertEquals(1, parse("--connect", "h", "--frames", "1").frames(), "frames 1 ok"),
                () -> assertTrue(error("--connect", "h", "--size", "640").contains("WxH"), "size without x"),
                () -> assertTrue(error("--connect", "h", "--size", "0x5").contains("positive"), "size 0"),
                () -> assertTrue(error("--connect", "h", "--scale-factor", "0").contains("positive"), "scale 0"),
                () -> assertTrue(error("--connect", "h", "--scale-factor", "NaN").contains("positive"), "scale NaN"),
                () -> assertTrue(error("--connect", "h", "--font-size", "x").contains("number"), "font size not a number"),
                () -> assertTrue(error("--connect", "h", "--swap-scale-key", "nosuchkey").contains("keysym"), "bad key"),
                () -> assertTrue(error("--connect", "h", "--bind", "[::1").contains("--bind"), "bad bind"),
                () -> assertThrows(IllegalArgumentException.class, () -> parse("--connect", "h:99999"), "bad port"),
                () -> assertThrows(IllegalArgumentException.class, () -> parse("--connect", "h", "--protocol", "x25"),
                    "bad protocol"));
        }
    }

    @Nested
    @DisplayName("defaultName")
    class DefaultName {
        @Test
        @DisplayName("keeps ASCII letters, digits, - _ . up to 32, capitalises, Pilot when empty")
        void names() {
            assertAll(
                () -> assertEquals("Tony", AppOptions.defaultName("tony"), "capitalised"),
                () -> assertEquals("A.b-c_9", AppOptions.defaultName("a.b-c_9"), "allowed punctuation kept"),
                () -> assertEquals("Jrgen", AppOptions.defaultName("jür gen!"), "non-ASCII, space and ! dropped"),
                () -> assertEquals("X".repeat(1) + "x".repeat(31), AppOptions.defaultName("x".repeat(40)), "32 kept"),
                () -> assertEquals("Pilot", AppOptions.defaultName("éé"), "nothing left"),
                () -> assertEquals("Pilot", AppOptions.defaultName(null), "no user name"));
        }
    }
}
