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

import com.fulcrologic.jxpilot.client.net.ClientConnection;
import com.fulcrologic.jxpilot.client.net.Endpoint;
import com.fulcrologic.jxpilot.client.net.Transport;
import com.fulcrologic.jxpilot.client.render.Font;
import com.fulcrologic.jxpilot.client.xp.ConnectTarget;
import com.fulcrologic.jxpilot.client.xp.GameConnection;
import com.fulcrologic.jxpilot.client.xp.NativeGameConnection;
import com.fulcrologic.jxpilot.client.xp.XpConnection;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.Records;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.file.Path;
import java.util.List;

import static com.fulcrologic.jxpilot.client.app.AppFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class AppPartsTest {
    @Nested
    @DisplayName("WorldMapState")
    class MapStateTests {
        @Test
        @DisplayName("cannon, fuel and target state from the world; own team; loops; checkpoint 0")
        void state() {
            AppFixture f = new AppFixture(true).players().twice(x -> x
                .self(0, 100, 100, 0, 0xFFFF)
                .cannon(0, Records.CannonState.FLAG_DEAD)
                .fuel(0, 0.5)
                .target(0, Records.TargetState.FLAG_DEAD, 0.25));
            WorldMapState s = new WorldMapState(f.game);
            s.setLoops(42);
            AppFixture fresh = new AppFixture(true).players();
            WorldMapState t = new WorldMapState(fresh.game);
            assertAll(
                () -> assertFalse(s.cannonAlive(0), "dead cannon"),
                () -> assertTrue(t.cannonAlive(0), "fresh world: cannon alive"),
                () -> assertEquals(0.5 * Const.MAX_STATION_FUEL, s.fuel(0), Const.MAX_STATION_FUEL / 65535.0,
                    "half full (u16 fraction quantization)"),
                () -> assertEquals(Const.MAX_STATION_FUEL, t.fuel(0), "fresh: full"),
                () -> assertFalse(s.targetAlive(0), "dead target"),
                () -> assertTrue(t.targetAlive(0), "fresh: alive"),
                () -> assertEquals(0.25 * Const.TARGET_DAMAGE, s.targetDamage(0), Const.TARGET_DAMAGE / 65535.0,
                    "a quarter left (u16 fraction quantization)"),
                () -> assertEquals(Const.TARGET_DAMAGE, t.targetDamage(0), "fresh: intact"),
                () -> assertEquals(1, s.selfTeam(), "own team"),
                () -> assertEquals(42L, s.loops(), "loops as set"),
                () -> assertEquals(0, s.nextCheckpoint(), "SELF checkpoint 0"));
        }

        @Test
        @DisplayName("F4/F12: the next checkpoint from SELF; base owner names from BASE_OWNER and the player list")
        void checkpointAndOwners() {
            AppFixture f = new AppFixture(true).players().twice(x -> {
                x.w.beginSection(com.fulcrologic.jxpilot.common.net.Section.SELF);
                Records.Self.put(x.w, AppFixture.SELF, 0, 100, 100, 0, 0, 0, 700, 1000,
                    com.fulcrologic.jxpilot.common.net.Protocol.NO_ID, 0, 0, 0, 45, 40, 0.12, 0, 0, 3, 0);
                x.w.endSection();
            });
            f.game.world().setBaseOwner(0, AppFixture.SELF);
            WorldMapState s = new WorldMapState(f.game);
            CharSequence owned = s.baseOwnerName(0);
            f.game.world().setBaseOwner(0, 99);
            CharSequence unknown = s.baseOwnerName(0);
            f.game.world().setBaseOwner(0, com.fulcrologic.jxpilot.common.net.Protocol.NO_ID);
            CharSequence free = s.baseOwnerName(0);
            assertAll(
                () -> assertEquals(3, s.nextCheckpoint(), "SELF check 3"),
                () -> assertEquals("Me", owned == null ? null : owned.toString(), "the owner's name"),
                () -> assertNull(unknown, "an owner the player list does not know: no name"),
                () -> assertNull(free, "a free base: no name"));
        }

        @Test
        @DisplayName("own team is TEAM_NOT_SET before the own player is known")
        void noTeam() {
            AppFixture f = new AppFixture(true);
            assertEquals(Const.TEAM_NOT_SET, new WorldMapState(f.game).selfTeam(), "unknown own player");
        }
    }

    @Nested
    @DisplayName("FontMetrics")
    class Metrics {
        @Test
        @DisplayName("ascent, descent and widths in logical pixels (framebuffer / ratio, rounded)")
        void metrics() {
            Font font = new Font(64, 64, 21, 6);
            font.setGlyph('a', 0, 0, 1, 1, 0, 0, 9);
            font.setGlyph('b', 0, 0, 1, 1, 0, 0, 4);
            FontMetrics m = new FontMetrics();
            m.set(font, 2);
            assertAll(
                () -> assertEquals(11, m.ascent(), "21 / 2 = 10.5 rounds to 11"),
                () -> assertEquals(3, m.descent(), "6 / 2"),
                () -> assertEquals(7, m.width("ab", 2), "(9 + 4) / 2 = 6.5 rounds to 7"),
                () -> assertEquals(5, m.width("ab", 1), "only the first len chars: 9 / 2 = 4.5 -> 5"),
                () -> assertEquals(0, m.width("ab", 0), "no chars"));
        }
    }

    @Nested
    @DisplayName("RadarBlips")
    class Blips {
        @Test
        @DisplayName("adds until full; clear empties and hides the own ship")
        void capacity() {
            RadarBlips r = new RadarBlips(2);
            boolean a = r.add(1, 2, 3);
            boolean b = r.add(4, 5, 0x83);
            boolean c = r.add(7, 8, 0);
            r.selfVisible = true;
            int before = r.count();
            float x1 = r.x(1);
            float y1 = r.y(1);
            int s1 = r.sizeByte(1);
            r.clear();
            assertAll(
                () -> assertTrue(a && b, "two fit"),
                () -> assertFalse(c, "third refused"),
                () -> assertEquals(2, before, "count 2"),
                () -> assertEquals(4f, x1, "x"),
                () -> assertEquals(5f, y1, "y"),
                () -> assertEquals(0x83, s1, "size byte"),
                () -> assertEquals(2, r.capacity(), "capacity"),
                () -> assertEquals(0, r.count(), "cleared"),
                () -> assertFalse(r.selfVisible, "own ship hidden"));
        }
    }

    @Nested
    @DisplayName("GameConnector")
    class Connector {
        @Test
        @DisplayName("XPilot login: nick, team, shape, real name reduced to safe characters")
        void xpLogin() {
            XpConnection.Login l = GameConnector.xpLogin("Ace", 4, "(SH: 1,0)", true, "t.käy x");
            XpConnection.Login empty = GameConnector.xpLogin("Ace", 4, "", false, "ä");
            assertAll(
                () -> assertEquals("Ace", l.nick, "nick"),
                () -> assertEquals(4, l.team, "team"),
                () -> assertEquals("(SH: 1,0)", l.shape, "shape"),
                () -> assertEquals("t.kyx", l.realName, "non-ASCII and space dropped"),
                () -> assertEquals("jxpilot", empty.realName, "nothing left: jxpilot"),
                () -> assertTrue(l.audio, "asks for audio when sound is on"),
                () -> assertFalse(empty.audio, "does not ask for audio when sound is off"));
        }

        @Test
        @DisplayName("native connection: CONNECT carries name, team and shape")
        void nativeConnect() {
            Transport t = new Transport() {
                @Override public boolean send(ByteBuffer p) { return true; }

                @Override public int receive(ByteBuffer b) { return 0; }

                @Override public void close() { }
            };
            ClientConnection c = GameConnector.nativeConnection(t, "x", new AppFixture(true).game, "Ace", 3, "(SH: 1,0)");
            assertAll(
                () -> assertEquals("Ace", c.connect.name.toString(), "name"),
                () -> assertEquals(3, c.connect.team, "team"),
                () -> assertEquals("(SH: 1,0)", c.connect.shape.toString(), "shape"));
        }

        @Test
        @DisplayName("open picks the implementation by protocol (UDP sockets on loopback, nothing sent)")
        void open() throws Exception {
            try (DatagramChannel ch = DatagramChannel.open()) {
                ch.bind(new InetSocketAddress("127.0.0.1", 0));
                int port = ((InetSocketAddress) ch.getLocalAddress()).getPort();
                Endpoint ep = new Endpoint("127.0.0.1", port);
                GameConnection n = GameConnector.open(new ConnectTarget(ConnectTarget.Protocol.JXPILOT, ep), null,
                    new AppFixture(true).game, "Ace", 1, "", true, 5);
                GameConnection x = GameConnector.open(new ConnectTarget(ConnectTarget.Protocol.XPILOT, ep), null,
                    new AppFixture(true).game, "Ace", 1, "", true, 5);
                try {
                    assertAll(
                        () -> assertInstanceOf(NativeGameConnection.class, n, "native"),
                        () -> assertEquals("Ace", ((NativeGameConnection) n).connection().connect.name.toString(),
                            "native login applied"),
                        () -> assertInstanceOf(XpConnection.class, x, "XPilot"));
                } finally {
                    n.close();
                    x.close();
                }
            }
        }
    }

    @Nested
    @DisplayName("EmbeddedServer and launcher")
    class Embedded {
        @Test
        @DisplayName("the first provider is used; none is a clear 'not available' error; endpoints keep address and port")
        void provider() {
            com.fulcrologic.jxpilot.common.net.LocalServer.Provider p = (file, opts) -> null;
            String msg = assertThrows(UnsupportedOperationException.class,
                () -> EmbeddedServer.provider(List.of())).getMessage();
            com.fulcrologic.jxpilot.client.net.Endpoint ep =
                EmbeddedServer.endpointOf(new java.net.InetSocketAddress(java.net.InetAddress.ofLiteral("127.0.0.1"), 4321));
            assertAll(
                () -> assertSame(p, EmbeddedServer.provider(List.of(p, (file, opts) -> null)), "first provider"),
                () -> assertTrue(msg.startsWith("--local is not available"), "says --local is unavailable"),
                () -> assertTrue(msg.contains("--connect"), "says what to use instead"),
                () -> assertEquals("127.0.0.1", ep.host(), "endpoint host is the address literal"),
                () -> assertEquals(4321, ep.port(), "endpoint port is the bound port"));
        }

        @Test
        @DisplayName("launcher: HELP exits 2; LOCAL with a map that does not load exits 3 without opening a window")
        void launcher() throws Exception {
            assertAll(
                () -> assertEquals(2, AppLauncher.run(AppOptions.parse(new String[0], "u")), "help: usage, exit 2"),
                () -> assertEquals(3, AppLauncher.run(AppOptions.parse(new String[] {"--local", "--map",
                    Path.of("no-such-map.xp").toString()}, "u")), "the local server cannot start: exit 3"));
        }
    }

    @Nested
    @DisplayName("key configuration loading")
    class KeyLoading {
        @Test
        @DisplayName("--keys reads the file; without it HOME/.xpilotrc when present, else the defaults; problems go to err")
        void sources(@org.junit.jupiter.api.io.TempDir Path home) throws Exception {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.PrintStream err = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8);
            AppOptions plain = AppOptions.parse(new String[] {"--list-keys"}, "u");
            com.fulcrologic.jxpilot.client.game.input.KeyConfig none = AppLauncher.keyConfig(plain, home, err);
            java.nio.file.Files.writeString(home.resolve(".xpilotrc"), "turnSpeed: 30\nkeyDetonateMines: u\nbogus\n");
            com.fulcrologic.jxpilot.client.game.input.KeyConfig rc = AppLauncher.keyConfig(plain, home, err);
            Path other = home.resolve("other.rc");
            java.nio.file.Files.writeString(other, "turnSpeed: 20\n");
            com.fulcrologic.jxpilot.client.game.input.KeyConfig given = AppLauncher.keyConfig(
                AppOptions.parse(new String[] {"--list-keys", "--keys", other.toString()}, "u"), home, err);
            String out = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            AppOptions missing = AppOptions.parse(new String[] {"--list-keys", "--keys", home.resolve("nope").toString()},
                "u");
            assertAll(
                () -> assertEquals("defaults", none.source(), "no file: defaults"),
                () -> assertEquals(30.0, rc.settings().turnSpeed, "HOME/.xpilotrc is read"),
                () -> assertEquals(20.0, given.settings().turnSpeed, "--keys wins over ~/.xpilotrc"),
                () -> assertTrue(out.contains("jxpilot keys: " + home.resolve(".xpilotrc") + ":3: expected"),
                    "problems are printed with file and line"),
                () -> assertTrue(out.contains("jxpilot keys: warning: key bound to more than one function: u: "
                    + "keyDetonateMines keyIdMode"), "conflicts are printed"),
                () -> assertThrows(java.io.IOException.class, () -> AppLauncher.keyConfig(missing, home, err),
                    "a missing --keys file is an error"));
        }

        @Test
        @DisplayName("launcher: --list-keys prints the table and exits 0; an unreadable --keys file exits 2")
        void listKeys(@org.junit.jupiter.api.io.TempDir Path home) throws Exception {
            java.io.PrintStream saved = System.out;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            Path rc = home.resolve("k.rc");
            java.nio.file.Files.writeString(rc, "keyIncreaseTurnspeed: F10\n");
            int code;
            try {
                System.setOut(new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8));
                code = AppLauncher.run(AppOptions.parse(new String[] {"--list-keys", "--keys", rc.toString()}, "u"));
            } finally {
                System.setOut(saved);
            }
            String out = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            int bad = AppLauncher.run(AppOptions.parse(new String[] {"--list-keys", "--keys",
                home.resolve("missing").toString()}, "u"));
            assertAll(
                () -> assertEquals(0, code, "exit 0"),
                () -> assertTrue(out.startsWith("JXPilot key bindings (defaults + " + rc + ")"), "the table header"),
                () -> assertTrue(out.contains("keyIncreaseTurnspeed     F10"), "the effective binding"),
                () -> assertEquals(2, bad, "exit 2 for a missing key file"));
        }
    }
}
