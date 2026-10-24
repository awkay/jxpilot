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
package com.fulcrologic.jxpilot.client.xp;

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.net.ClientConnection;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.net.Endpoint;
import com.fulcrologic.jxpilot.client.net.FakeLink;
import com.fulcrologic.jxpilot.client.net.FakeServer;
import com.fulcrologic.jxpilot.client.net.Transport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** Protocol selection and the protocol-neutral connection API. */
class ConnectionSelectionTest {

    @Nested
    @DisplayName("ConnectTarget.parse")
    class Parse {
        @Test
        void schemesChooseTheProtocol() {
            assertAll(
                () -> assertEquals(new ConnectTarget(ConnectTarget.Protocol.XPILOT, new Endpoint("host", 15000)),
                    ConnectTarget.parse("xp://host:15000", null), "xp:// is XPilot"),
                () -> assertEquals(new ConnectTarget(ConnectTarget.Protocol.XPILOT, new Endpoint("host", 15345)),
                    ConnectTarget.parse("XP://host/", "jxpilot"), "scheme wins over the flag, default port, slash"),
                () -> assertEquals(new ConnectTarget(ConnectTarget.Protocol.JXPILOT, new Endpoint("::1", 9)),
                    ConnectTarget.parse("jx://[::1]:9", "xpilot"), "jx:// is native, IPv6"),
                () -> assertEquals(new ConnectTarget(ConnectTarget.Protocol.JXPILOT, new Endpoint("h", 15345)),
                    ConnectTarget.parse(" h ", null), "plain host without flag is native"),
                () -> assertEquals(new ConnectTarget(ConnectTarget.Protocol.XPILOT, new Endpoint("h", 1)),
                    ConnectTarget.parse("h:1", "xpilot"), "plain host with the xpilot flag"));
        }

        @Test
        void protocolFlagValues() {
            assertAll(
                () -> assertEquals(ConnectTarget.Protocol.XPILOT, ConnectTarget.parseProtocol("XPilot"), "xpilot"),
                () -> assertEquals(ConnectTarget.Protocol.XPILOT, ConnectTarget.parseProtocol("xp"), "xp"),
                () -> assertEquals(ConnectTarget.Protocol.JXPILOT, ConnectTarget.parseProtocol("jxpilot"), "jxpilot"),
                () -> assertEquals(ConnectTarget.Protocol.JXPILOT, ConnectTarget.parseProtocol(" JX "), "jx"),
                () -> assertEquals(ConnectTarget.Protocol.JXPILOT, ConnectTarget.parseProtocol("native"), "native"),
                () -> assertEquals("unknown protocol 'udp' (xpilot or jxpilot)", assertThrows(
                    IllegalArgumentException.class, () -> ConnectTarget.parseProtocol("udp")).getMessage(), "unknown"),
                () -> assertThrows(IllegalArgumentException.class, () -> ConnectTarget.parse("xp://", null),
                    "empty host"));
        }

        @Test
        void toStringShowsTheScheme() {
            assertAll(
                () -> assertEquals("xp://h:1", ConnectTarget.parse("xp://h:1", null).toString(), "xp"),
                () -> assertEquals("jx://h:2", ConnectTarget.parse("h:2", null).toString(), "jx"));
        }
    }

    @Nested
    @DisplayName("GameConnections")
    class Open {
        @Test
        void opensTheRightImplementation() throws Exception {
            ClientGame game = new ClientGame();
            try (GameConnection xp = GameConnections.open(ConnectTarget.parse("xp://127.0.0.1:1", null), game, "pilot",
                3, null, 1);
                 GameConnection jx = GameConnections.open(ConnectTarget.parse("127.0.0.1:1", null), game, "pilot",
                     3, null, 1)) {
                assertAll(
                    () -> assertInstanceOf(XpConnection.class, xp, "xp:// opens an XpConnection"),
                    () -> assertEquals(ConnectTarget.Protocol.XPILOT, xp.protocol(), "protocol"),
                    () -> assertEquals(ConnectionState.IDLE, xp.state(), "not started"),
                    () -> assertInstanceOf(NativeGameConnection.class, jx, "plain opens the native connection"),
                    () -> assertEquals(ConnectTarget.Protocol.JXPILOT, jx.protocol(), "protocol"),
                    () -> assertEquals("pilot", ((NativeGameConnection) jx).connection().connect.name.toString(),
                        "nick passed"),
                    () -> assertEquals(3, ((NativeGameConnection) jx).connection().connect.team, "team passed"));
            }
        }

        @Test
        void realNameKeepsOnlySafeCharacters() {
            String old = System.getProperty("user.name");
            try {
                System.setProperty("user.name", "Jöe Doe!_x.y-z" + "q".repeat(30));
                String a = GameConnections.realName();
                System.setProperty("user.name", "åä");
                String b = GameConnections.realName();
                assertAll(
                    () -> assertEquals("JeDoe_x.y-zqqqq", a, "ASCII letters, digits, - _ . and at most 15 chars"),
                    () -> assertEquals("jxpilot", b, "nothing left: jxpilot"));
            } finally {
                System.setProperty("user.name", old);
            }
        }

        @Test
        void userOverridesAndNamesAreSent() throws Exception {
            try {
                GameConnections.setUser("Real Name");
                String over = GameConnections.realName();
                GameConnection jx = GameConnections.open(ConnectTarget.parse("127.0.0.1:1", null), new ClientGame(),
                    "pilot", 3, null, 1);
                String sentReal = ((NativeGameConnection) jx).connection().connect.realName.toString();
                String sentHost = ((NativeGameConnection) jx).connection().connect.hostName.toString();
                jx.close();
                GameConnections.setUser(null);
                String back = GameConnections.realName();
                assertAll(
                    () -> assertEquals("RealName", over, "--user replaces the login name (cleaned)"),
                    () -> assertEquals("RealName", sentReal, "CONNECT carries the real name"),
                    () -> assertEquals(GameConnections.hostName(), sentHost, "and the host name"),
                    () -> assertFalse(sentHost.isEmpty(), "a host name is always found"),
                    () -> assertNotEquals("RealName", back, "null restores the login name"));
            } finally {
                GameConnections.setUser(null);
            }
        }
    }

    @Nested
    @DisplayName("NativeGameConnection")
    class Native {
        @Test
        void delegatesAndSendsChatOverTheReliableChannel() throws Exception {
            FakeLink link = new FakeLink(1);
            FakeServer server = new FakeServer(link.server());
            ClientGame game = new ClientGame();
            ClientConnection c = new ClientConnection(link.client(), game, "fake", 1);
            c.connect.name.append("me");
            boolean[] closed = {false};
            Transport t = new Transport() {
                public boolean send(ByteBuffer p) {
                    return true;
                }

                public int receive(ByteBuffer b) {
                    return 0;
                }

                public void close() {
                    closed[0] = true;
                }
            };
            NativeGameConnection n = new NativeGameConnection(c, t);
            com.fulcrologic.jxpilot.common.net.Msg.Settings st = new com.fulcrologic.jxpilot.common.net.Msg.Settings();
            st.turnSpeed = 40;
            boolean settingsEarly = n.sendSettings(st, 0);
            boolean displayEarly = n.sendDisplay(900, 700, 0);
            boolean beforeStart = n.sendChat("early", 0);
            long now = 1_000_000_000L;
            n.start(now);
            for (int i = 0; i < 200 && n.snapshotsReceived() < 3; i++) {
                now += 16_000_000L;
                server.poll(now);
                server.step(now);
                n.update(now);
                n.sendInput(0, 0, now);
                n.flush(now);
            }
            st.turnSpeed = 42;
            boolean settingsLater = n.sendSettings(st, now);
            boolean displayLater = n.sendDisplay(1000, 800, now);
            boolean empty = n.sendChat("", now);
            boolean sent = n.sendChat("hello", now);
            n.flush(now);
            server.poll(now);
            n.disconnect(now);
            n.close();
            assertAll(
                () -> assertFalse(beforeStart, "no chat before a session"),
                () -> assertFalse(settingsEarly, "settings are not sent before a session"),
                () -> assertTrue(settingsLater, "but at once while playing"),
                () -> assertEquals(java.util.List.of("45.0/40.0", "45.0/42.0"), server.settings,
                    "the join carried the early settings (before READY), then the change"),
                () -> assertFalse(displayEarly, "the view size is not sent before a session"),
                () -> assertTrue(displayLater, "but at once while playing"),
                () -> assertEquals(java.util.List.of("900x700", "1000x800"), server.displays,
                    "the join carried the early view size (before READY), then the change"),
                () -> assertFalse(empty, "empty text refused"),
                () -> assertTrue(sent, "queued while playing"),
                () -> assertEquals(java.util.List.of("hello"), server.chats, "server got the CHAT"),
                () -> assertEquals(ConnectionState.CLOSED, n.state(), "disconnected"),
                () -> assertTrue(n.packetsReceived() > 0, "received"),
                () -> assertTrue(n.packetsSent() > 0, "sent"),
                () -> assertTrue(n.snapshotsReceived() > 0, "snapshots"),
                () -> assertEquals(0, n.malformed(), "nothing malformed"),
                () -> assertTrue(n.status().toString().startsWith("Disconnected"), "status"),
                () -> assertTrue(closed[0], "transport closed"),
                () -> assertSame(c, n.connection(), "wrapped connection"),
                () -> assertEquals(FakeServer.PLAYER_ID, c.playerId(), "accepted with the server's id"));
        }

        @Test
        void closeWithoutTransportIsSafe() {
            NativeGameConnection n = new NativeGameConnection(new ClientConnection(new FakeLink(1).client(),
                new ClientGame(), "x", 1), null);
            assertDoesNotThrow(n::close, "null transport");
        }
    }
}
