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
package com.fulcrologic.jxpilot.client.net;

import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.LobbyMsg;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.net.LobbyWire.entry;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ClientConnection} meeting a lobby server: REJECT LOBBY, the list, the choice, and the game after it. */
@DisplayName("ClientConnection with a lobby server")
class ClientConnectionLobbyTest {
    static final long MS = 1_000_000L;
    static final long SEC = 1_000_000_000L;

    final LobbyWire wire = new LobbyWire();
    final List<ConnectionState> states = new ArrayList<>();
    final ClientConnection c = new ClientConnection(wire, new ConnectionListener() {
        @Override
        public void onAccepted(int playerId) { }

        @Override
        public void onWelcome(Msg.Welcome w) { }

        @Override
        public void onConfig(Msg.Welcome w, Options o, WorldMap m) { }

        @Override
        public void onReliable(int type, ByteBuffer p) { }

        @Override
        public void onSnapshot(ByteBuffer body, long now) { }

        @Override
        public void onStateChange(ConnectionState s, CharSequence status) {
            states.add(s);
        }
    }, "lobby:1", 7);
    long now = 100 * SEC;

    ClientConnectionLobbyTest() {
        c.connect.name.append("Ann");
    }

    int lastNonce() {
        return c.connect.nonce;
    }

    long countOf(int type) {
        return wire.sentOf(type).size();
    }

    /** Start, get REJECT LOBBY, and let the connection process it. */
    void intoLobby() {
        c.start(now);
        wire.tell(LobbyWire.rejectLobby(lastNonce()));
        c.update(now);
    }

    void greetAndList() {
        wire.tell(LobbyWire.info(lastNonce(), 0xABCDL, "friends"));
        for (byte[] p : LobbyWire.list(3, List.of(entry(0, "alpha", true, 2), entry(1, "beta", false, 0)), 1000)) {
            wire.tell(p);
        }
        c.update(now);
    }

    @Test
    @DisplayName("REJECT LOBBY for the CONNECT's nonce enters the LOBBY state and sends HELLO with that nonce")
    void rejectLobbyEntersLobby() {
        c.start(now);
        int nonce = lastNonce();
        wire.sent.clear();
        wire.tell(LobbyWire.rejectLobby(nonce));
        c.update(now);
        assertAll(
            () -> assertEquals(ConnectionState.LOBBY, c.state(), "state LOBBY"),
            () -> assertNotNull(c.lobby(), "the lobby dialogue is there"),
            () -> assertEquals(1, countOf(PacketType.LOBBY_HELLO), "a HELLO went out"),
            () -> assertEquals(nonce, LobbyWire.hello(wire.sentOf(PacketType.LOBBY_HELLO).get(0)).nonce,
                "carrying the CONNECT's nonce"),
            () -> assertTrue(c.status().toString().contains("Lobby") && c.status().toString().contains("lobby:1"),
                "status names the lobby: " + c.status()),
            () -> assertEquals(ConnectionState.LOBBY, states.get(states.size() - 1), "the listener was told"));
    }

    @Test
    @DisplayName("a REJECT LOBBY for another nonce is ignored")
    void staleReject() {
        c.start(now);
        wire.tell(LobbyWire.rejectLobby(lastNonce() + 1));
        c.update(now);
        assertAll(
            () -> assertEquals(ConnectionState.CONNECTING, c.state(), "still connecting"),
            () -> assertNull(c.lobby(), "no lobby"));
    }

    @Test
    @DisplayName("lobby() is null before start, while connecting and after close")
    void lobbyOnlyInLobbyState() {
        assertNull(c.lobby(), "idle");
        c.start(now);
        assertNull(c.lobby(), "connecting");
        intoLobby();
        assertNotNull(c.lobby(), "in the lobby");
        c.disconnect(now);
        assertNull(c.lobby(), "closed");
    }

    @Test
    @DisplayName("in the lobby CONNECT is not resent; the dialogue's datagrams go out instead")
    void noConnectInLobby() {
        intoLobby();
        long connects = countOf(PacketType.CONNECT);
        now += 300 * MS;
        c.update(now);
        assertAll(
            () -> assertEquals(connects, countOf(PacketType.CONNECT), "no further CONNECT"),
            () -> assertEquals(2, countOf(PacketType.LOBBY_HELLO), "HELLO resent after 250 ms"));
    }

    @Test
    @DisplayName("INFO and the list arrive through update: the lobby holds the sorted maps")
    void listArrives() {
        intoLobby();
        greetAndList();
        LobbyClient l = c.lobby();
        assertAll(
            () -> assertTrue(l.greeted(), "greeted"),
            () -> assertEquals("friends", l.serverName(), "lobby name"),
            () -> assertEquals(List.of("alpha", "beta"), l.entries().stream().map(LobbyEntry::name).toList(), "maps"),
            () -> assertEquals(ConnectionState.LOBBY, c.state(), "still in the lobby"));
    }

    @Test
    @DisplayName("JOIN OK sends the connection back to CONNECTING with a fresh nonce and a CONNECT at once")
    void joinOkConnects() {
        intoLobby();
        greetAndList();
        int oldNonce = lastNonce();
        c.lobby().select(0, "alpha", now);
        wire.sent.clear();
        wire.tell(LobbyWire.join(0, LobbyMsg.JOIN_OK, ""));
        c.update(now);
        assertAll(
            () -> assertEquals(ConnectionState.CONNECTING, c.state(), "connecting again"),
            () -> assertNull(c.lobby(), "no longer in the lobby"),
            () -> assertNotEquals(oldNonce, lastNonce(), "a new nonce (the server's route is per address, the session is new)"),
            () -> assertEquals(1, countOf(PacketType.CONNECT), "a CONNECT went out at once"),
            () -> assertEquals(lastNonce(), connectNonce(wire.sentOf(PacketType.CONNECT).get(0)), "with the new nonce"));
    }

    static int connectNonce(byte[] p) {
        return ByteBuffer.wrap(p).getInt(PacketHeader.SIZE);
    }

    @Test
    @DisplayName("JOIN STARTING keeps the lobby; a lost JOIN is covered by SELECT resends")
    void startingStays() {
        intoLobby();
        greetAndList();
        c.lobby().select(1, "beta", now);
        wire.tell(LobbyWire.join(1, LobbyMsg.JOIN_STARTING, ""));
        c.update(now);
        now += 600 * MS;
        wire.sent.clear();
        c.update(now);
        assertAll(
            () -> assertEquals(ConnectionState.LOBBY, c.state(), "still in the lobby"),
            () -> assertEquals(1, countOf(PacketType.LOBBY_SELECT), "SELECT asked again"),
            () -> assertEquals("Starting beta...", c.lobby().message(), "and the screen says so"));
    }

    @Test
    @DisplayName("a lobby that answers keep-alives never times out; one that falls silent does after 10 s and reconnects")
    void timeout() {
        intoLobby();
        greetAndList();
        for (int s = 0; s < 30; s++) {
            now += SEC;
            wire.tell(LobbyWire.list(3, List.of(), 1000).get(0)); // the keep-alive page
            c.update(now);
        }
        ConnectionState alive = c.state();
        now += 9 * SEC;
        c.update(now);
        ConnectionState nineSilent = c.state();
        now += 2 * SEC;
        wire.sent.clear();
        c.update(now);
        assertAll(
            () -> assertEquals(ConnectionState.LOBBY, alive, "30 s in, kept alive by the answers"),
            () -> assertEquals(ConnectionState.LOBBY, nineSilent, "9 s of silence is not yet a timeout"),
            () -> assertEquals(ConnectionState.CONNECTING, c.state(), "11 s of silence: reconnecting"),
            () -> assertEquals(1, countOf(PacketType.CONNECT), "with a CONNECT"),
            () -> assertEquals(1, c.reconnects(), "counted as a reconnect"));
    }

    @Test
    @DisplayName("datagrams that are not the lobby's (a stray SNAPSHOT) do not keep a silent lobby alive")
    void strayDoesNotRefresh() {
        intoLobby();
        greetAndList();
        for (int s = 0; s < 12; s++) {
            now += SEC;
            wire.tell(LobbyWire.dg(PacketType.SNAPSHOT, b -> b.putInt(1)));
            c.update(now);
        }
        assertEquals(ConnectionState.CONNECTING, c.state(), "12 s without a lobby datagram: timed out");
    }

    @Test
    @DisplayName("a REJECT LOBBY while connecting after a join (the route expired or the game ended) shows the lobby again,"
        + " empty, saying the join did not happen")
    void backToLobby() {
        intoLobby();
        greetAndList();
        c.lobby().select(0, "alpha", now);
        wire.tell(LobbyWire.join(0, LobbyMsg.JOIN_OK, ""));
        c.update(now);
        int nonce = lastNonce();
        wire.sent.clear();
        wire.tell(LobbyWire.rejectLobby(nonce));
        c.update(now);
        assertAll(
            () -> assertEquals(ConnectionState.LOBBY, c.state(), "in the lobby again"),
            () -> assertTrue(c.lobby().entries().isEmpty(), "with a fresh dialogue: no list yet"),
            () -> assertFalse(c.lobby().greeted(), "not greeted"),
            () -> assertEquals(1, countOf(PacketType.LOBBY_HELLO), "greeting again"),
            () -> assertEquals("Could not join alpha (the lobby did not hand us to the game). Choose it again.",
                c.lobby().message(), "and saying why the join did not happen (not a silent list)"));
    }

    @Test
    @DisplayName("a game that refuses the player after the lobby routed it (full, locked, name in use) returns to the list with the reason")
    void refusedAfterLobbyJoin() {
        intoLobby();
        greetAndList();
        c.lobby().select(0, "alpha", now);
        wire.tell(LobbyWire.join(0, LobbyMsg.JOIN_OK, ""));
        c.update(now);
        int nonce = lastNonce();
        wire.sent.clear();
        wire.tell(LobbyWire.dg(PacketType.REJECT, b -> {
            Msg.Reject r = new Msg.Reject();
            r.nonce = nonce;
            r.reason = Msg.Reject.REASON_FULL;
            r.text.append("server is full");
            r.write(b);
        }));
        c.update(now);
        assertAll(
            () -> assertEquals(ConnectionState.LOBBY, c.state(), "back in the lobby, not closed"),
            () -> assertEquals("Refused: server is full", c.lobby().message(), "the reason is on the screen"),
            () -> assertEquals(1, countOf(PacketType.LOBBY_HELLO), "the dialogue starts again"),
            () -> assertTrue(c.lobby().entries().isEmpty(), "with a fresh list to come"));
    }

    @Test
    @DisplayName("the lobby's own REJECT after a refusal does not loop: a second refusal still returns to the list")
    void refusedTwice() {
        intoLobby();
        greetAndList();
        for (int round = 0; round < 2; round++) {
            c.lobby().select(0, "alpha", now);
            wire.tell(LobbyWire.join(0, LobbyMsg.JOIN_OK, ""));
            c.update(now);
            int nonce = lastNonce();
            wire.tell(LobbyWire.dg(PacketType.REJECT, b -> {
                Msg.Reject r = new Msg.Reject();
                r.nonce = nonce;
                r.reason = Msg.Reject.REASON_LOCKED;
                r.text.append("game locked");
                r.write(b);
            }));
            c.update(now);
            assertEquals(ConnectionState.LOBBY, c.state(), "round " + round + ": in the lobby");
            wire.tell(LobbyWire.info(lastNonce(), 1L, "friends"));
            wire.tell(LobbyWire.list(1, List.of(entry(0, "alpha", false, 0)), 1000).get(0));
            c.update(now);
        }
    }

    @Test
    @DisplayName("other REJECT reasons still close the connection (the lobby changes nothing for them)")
    void otherRejectsStillClose() {
        c.start(now);
        int nonce = lastNonce();
        wire.tell(LobbyWire.dg(PacketType.REJECT, b -> {
            Msg.Reject r = new Msg.Reject();
            r.nonce = nonce;
            r.reason = Msg.Reject.REASON_FULL;
            r.text.append("server is full");
            r.write(b);
        }));
        c.update(now);
        assertAll(
            () -> assertEquals(ConnectionState.CLOSED, c.state(), "closed"),
            () -> assertTrue(c.status().toString().contains("server is full"), "with the reason: " + c.status()));
    }

    @Test
    @DisplayName("leaving from the lobby closes without sending DISCONNECT (there is no session)")
    void disconnectInLobby() {
        intoLobby();
        wire.sent.clear();
        c.disconnect(now);
        assertAll(
            () -> assertEquals(ConnectionState.CLOSED, c.state(), "closed"),
            () -> assertEquals(0, countOf(PacketType.DISCONNECT), "no DISCONNECT"));
    }

    @Test
    @DisplayName("a REJECT with another reason is not mistaken for LOBBY: CONNECT keeps its protocol version header")
    void headerIsVersioned() {
        c.start(now);
        byte[] connect = wire.sentOf(PacketType.CONNECT).get(0);
        assertEquals(Protocol.VERSION, connect[2] & 0xFF, "the CONNECT is the usual version");
    }
}
