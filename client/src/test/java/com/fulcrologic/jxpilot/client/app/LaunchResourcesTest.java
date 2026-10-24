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

import com.fulcrologic.jxpilot.client.game.input.KeyConfig;
import com.fulcrologic.jxpilot.client.xp.ConnectTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Launch resources: geometry, version, port, client port range")
class LaunchResourcesTest {
    @Nested
    @DisplayName("Geometry")
    class Geo {
        @Test
        @DisplayName("X geometry strings: size, offsets from either edge, and rejects")
        void parse() {
            Geometry full = Geometry.parse("800x600+10-20");
            Geometry size = Geometry.parse("=640X480");
            Geometry pos = Geometry.parse("-0+5");
            assertAll(
                () -> assertEquals(new Geometry(800, 600, 10, 20, false, true, true), full, "size and offsets"),
                () -> assertEquals(10, full.left(1920, 800), "+X from the left"),
                () -> assertEquals(1080 - 600 - 20, full.top(1080, 600), "-Y from the bottom"),
                () -> assertEquals(new Geometry(640, 480, 0, 0, false, false, false), size, "'=' and capital X"),
                () -> assertEquals(1920 - 300, pos.left(1920, 300), "-0: flush right"),
                () -> assertEquals(5, pos.top(1080, 300), "+5 from the top"),
                () -> assertEquals(0, pos.width(), "no size"),
                () -> assertNull(Geometry.parse("big"), "garbage"),
                () -> assertNull(Geometry.parse("800x"), "missing height"),
                () -> assertNull(Geometry.parse("0x600"), "zero width"),
                () -> assertNull(Geometry.parse("800x600+1"), "one offset"),
                () -> assertNull(Geometry.parse("800x600+1+2z"), "trailing text"),
                () -> assertNull(Geometry.parse("800"), "no x"),
                () -> assertNull(Geometry.parse(""), "empty"),
                () -> assertNull(Geometry.parse("+99999999999+1"), "an offset too large"),
                () -> assertNull(Geometry.parse("99999999999x5"), "a size too large"),
                () -> assertNull(Geometry.parse("+-1+1"), "a sign without digits"));
        }
    }

    @Nested
    @DisplayName("launcher")
    class Launcher {
        @Test
        @DisplayName("--version prints C's -version equivalent and exits 0")
        void version() throws Exception {
            AppOptions o = AppOptions.parse(new String[] {"--connect", "h", "-version"}, "u");
            assertAll(
                () -> assertEquals(AppOptions.Mode.VERSION, o.mode(), "version wins over the launch mode"),
                () -> assertTrue(AppLauncher.versionText().startsWith("JXPilot client "), "names the client"),
                () -> assertTrue(AppLauncher.versionText().endsWith("(a Java port of the XPilot 4.5.5 client)"),
                    "and what it is"),
                () -> assertEquals(0, AppLauncher.run(o), "exit 0"));
        }

        @Test
        @DisplayName("the port resource fills in a port the target does not name")
        void port() {
            KeyConfig withPort = KeyConfig.load("port: 15400\n", "rc", null);
            KeyConfig none = KeyConfig.defaults(null);
            AppOptions bare = AppOptions.parse(new String[] {"--connect", "xp://example.org"}, "u");
            AppOptions named = AppOptions.parse(new String[] {"--connect", "example.org:16000"}, "u");
            assertAll(
                () -> assertEquals(15400, AppLauncher.target(bare, withPort).endpoint().port(), "the resource's port"),
                () -> assertEquals(ConnectTarget.Protocol.XPILOT, AppLauncher.target(bare, withPort).protocol(),
                    "the protocol kept"),
                () -> assertEquals("example.org", AppLauncher.target(bare, withPort).endpoint().host(), "the host kept"),
                () -> assertEquals(16000, AppLauncher.target(named, withPort).endpoint().port(),
                    "an explicit port wins"),
                () -> assertEquals(15345, AppLauncher.target(bare, none).endpoint().port(), "no resource: 15345"),
                () -> assertEquals("example.org", bare.connectText().substring(5), "the raw target is kept"));
        }

        @Test
        @DisplayName("clientPortStart..clientPortEnd: inclusive, an end below the start is the start alone, limited")
        void portRange() throws Exception {
            java.net.DatagramSocket busy = new java.net.DatagramSocket(0);
            int taken = busy.getLocalPort();
            try {
                var t = GameConnector.openUdp(new com.fulcrologic.jxpilot.client.net.Endpoint("127.0.0.1", 15345), null,
                    taken, taken + 50);
                t.close();
                assertAll(
                    () -> assertArrayEquals(new int[] {5, 6, 7}, GameConnector.portRange(5, 7), "inclusive"),
                    () -> assertArrayEquals(new int[] {9}, GameConnector.portRange(9, 0), "C's default end 0"),
                    () -> assertArrayEquals(new int[] {65535}, GameConnector.portRange(70000, 80000), "limited"),
                    () -> assertThrows(java.io.IOException.class, () -> GameConnector.openUdp(
                        new com.fulcrologic.jxpilot.client.net.Endpoint("127.0.0.1", 15345), null, taken, taken),
                        "the only port in the range is busy"));
            } finally {
                busy.close();
            }
        }
    }

    @Test
    @DisplayName("autoServerMotdPopup off: the MOTD waits for the menu; asked for, it shows on arrival")
    void motdPopup() {
        AppFixture f = new AppFixture(true).players();
        KeyConfig k = KeyConfig.load("autoServerMotdPopup: no\n", "rc", null);
        FakeConnection conn = new FakeConnection();
        ClientFrame cf = new ClientFrame(f.game, conn, k, new com.fulcrologic.jxpilot.client.paint.SceneData(),
            new RadarBlips(4));
        f.game.onReliable(com.fulcrologic.jxpilot.common.net.ReliableType.MOTD, motd("Welcome"));
        cf.step(1_000_000L, 800, 600, 1, ClientFrameTest.M, 256, 100);
        int hidden = cf.helpHud().textCount();
        cf.ui().act(com.fulcrologic.jxpilot.client.game.ui.UiAction.MOTD);
        cf.step(2_000_000L, 800, 600, 1, ClientFrameTest.M, 256, 100);
        int shown = cf.helpHud().textCount();
        assertAll(
            () -> assertEquals(0, hidden, "not popped up"),
            () -> assertTrue(shown > 0, "MENU / MOTD shows it"),
            () -> assertEquals(List.of("fps 0"), conn.requests,
                "only the first step's fps request: a MOTD in hand needs no request without refreshMotd"));
    }

    static java.nio.ByteBuffer motd(String s) {
        byte[] text = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return java.nio.ByteBuffer.allocate(8 + text.length).putInt(0).putInt(text.length).put(text).flip();
    }
}
