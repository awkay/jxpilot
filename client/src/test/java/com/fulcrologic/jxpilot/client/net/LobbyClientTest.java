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

import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.net.LobbyMsg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static com.fulcrologic.jxpilot.client.net.LobbyWire.entry;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LobbyClient: the client's side of the lobby dialogue")
class LobbyClientTest {
    static final long MS = 1_000_000L;
    static final int NONCE = 0x5EED;
    static final long COOKIE = 0xC00C1EL;

    LobbyWire wire;
    LobbyClient lobby;
    long now;

    @BeforeEach
    void setUp() {
        wire = new LobbyWire();
        lobby = new LobbyClient(wire);
        now = 5_000 * MS;
    }

    /** Deliver a datagram as ClientConnection does: header read, body to the lobby. */
    boolean deliver(byte[] datagram) {
        ByteBuffer b = ByteBuffer.wrap(datagram);
        PacketHeader h = new PacketHeader();
        h.read(b);
        return lobby.onPacket(h.type(), b, now);
    }

    void greet() {
        lobby.begin(now, NONCE);
        deliver(LobbyWire.info(NONCE, COOKIE, "friends"));
        wire.sent.clear();
    }

    void showList(int version, LobbyEntry... entries) {
        for (byte[] page : LobbyWire.list(version, List.of(entries), LobbyMsg.MAX_PAGE_BODY)) {
            deliver(page);
        }
    }

    @Nested
    @DisplayName("greeting")
    class Greeting {
        @Test
        @DisplayName("begin sends one HELLO with the nonce at once, padded to the minimum size")
        void hello() {
            lobby.begin(now, NONCE);
            assertAll(
                () -> assertEquals(1, wire.sent.size(), "one datagram"),
                () -> assertEquals(PacketType.LOBBY_HELLO, LobbyWire.type(wire.sent.get(0)), "a HELLO"),
                () -> assertEquals(NONCE, LobbyWire.hello(wire.sent.get(0)).nonce, "with the nonce"),
                () -> assertEquals(LobbyMsg.MIN_HELLO_SIZE, wire.sent.get(0).length, "padded so INFO cannot be larger"),
                () -> assertFalse(lobby.greeted(), "not greeted yet"));
        }

        @Test
        @DisplayName("HELLO is resent every 250 ms until INFO arrives, and not sooner")
        void resend() {
            lobby.begin(now, NONCE);
            now += 249 * MS;
            lobby.tick(now);
            int early = wire.sent.size();
            now += 1 * MS;
            lobby.tick(now);
            int on = wire.sent.size();
            now += 250 * MS;
            lobby.tick(now);
            assertAll(
                () -> assertEquals(1, early, "nothing at 249 ms"),
                () -> assertEquals(2, on, "a second HELLO at 250 ms"),
                () -> assertEquals(3, wire.sent.size(), "a third 250 ms later"),
                () -> assertTrue(wire.sent.stream().allMatch(p -> LobbyWire.type(p) == PacketType.LOBBY_HELLO),
                    "all HELLO: no list request before INFO"));
        }

        @Test
        @DisplayName("INFO with the nonce stores the cookie and name and asks for the list at once, quoting the cookie")
        void info() {
            lobby.begin(now, NONCE);
            wire.sent.clear();
            boolean ok = deliver(LobbyWire.info(NONCE, COOKIE, "friends"));
            LobbyMsg.ListRequest r = LobbyWire.request(wire.sent.get(0));
            assertAll(
                () -> assertTrue(ok, "a valid datagram of this dialogue"),
                () -> assertTrue(lobby.greeted(), "greeted"),
                () -> assertEquals("friends", lobby.serverName(), "name"),
                () -> assertEquals(PacketType.LOBBY_LIST_REQUEST, LobbyWire.type(wire.sent.get(0)), "asks for the list"),
                () -> assertEquals(COOKIE, r.cookie, "with the cookie"),
                () -> assertEquals(LobbyMsg.NO_VERSION, r.haveVersion, "saying it has no list"));
        }

        @Test
        @DisplayName("an INFO for another nonce is ignored")
        void wrongNonce() {
            lobby.begin(now, NONCE);
            boolean ok = deliver(LobbyWire.info(NONCE + 1, COOKIE, "x"));
            assertAll(
                () -> assertFalse(ok, "not ours"),
                () -> assertFalse(lobby.greeted(), "still waiting"));
        }

        @Test
        @DisplayName("lists and joins before INFO are ignored")
        void beforeInfo() {
            lobby.begin(now, NONCE);
            boolean l = deliver(LobbyWire.list(1, List.of(entry(0, "a", false, 0)), 1000).get(0));
            boolean j = deliver(LobbyWire.join(0, LobbyMsg.JOIN_OK, ""));
            assertAll(
                () -> assertFalse(l, "list ignored"),
                () -> assertFalse(j, "join ignored"),
                () -> assertTrue(lobby.entries().isEmpty(), "no list"),
                () -> assertFalse(lobby.takeJoined(), "no join"));
        }

        @Test
        @DisplayName("begin forgets the previous dialogue: list, cookie, pending select and message")
        void restart() {
            greet();
            showList(1, entry(0, "a", false, 0));
            lobby.select(0, "a", now);
            lobby.begin(now, NONCE + 1);
            assertAll(
                () -> assertFalse(lobby.greeted(), "not greeted"),
                () -> assertTrue(lobby.entries().isEmpty(), "no list"),
                () -> assertEquals(-1, lobby.selecting(), "no select"),
                () -> assertEquals("", lobby.message(), "no message"),
                () -> assertEquals("", lobby.serverName(), "no name"));
        }
    }

    @Nested
    @DisplayName("the list")
    class TheList {
        @Test
        @DisplayName("pages complete the list; it is shown sorted (active first, then by name) and confirmed with its version")
        void assembleSortConfirm() {
            greet();
            List<byte[]> pages = LobbyWire.list(7, List.of(entry(0, "zulu", false, 0), entry(1, "Bravo", false, 0),
                entry(2, "mike", true, 3), entry(3, "alpha", true, 1)), 120);
            for (byte[] p : pages) {
                deliver(p);
            }
            assertAll(
                () -> assertTrue(pages.size() > 1, "the test list spans several pages"),
                () -> assertEquals(List.of("alpha", "mike", "Bravo", "zulu"),
                    lobby.entries().stream().map(LobbyEntry::name).toList(), "display order"),
                () -> assertEquals(1, lobby.version(), "list version bumped once"),
                () -> assertEquals(1, wire.sent.size(), "one confirmation sent when it completed"),
                () -> assertEquals(7, LobbyWire.request(wire.sent.get(0)).haveVersion, "it names the version held"),
                () -> assertEquals(COOKIE, LobbyWire.request(wire.sent.get(0)).cookie, "and carries the cookie"));
        }

        @Test
        @DisplayName("a new version replaces the list and the same entries object is kept until then")
        void replaced() {
            greet();
            showList(1, entry(0, "a", false, 0));
            List<LobbyEntry> first = lobby.entries();
            deliver(LobbyWire.list(1, List.of(), 1000).get(0)); // keep-alive page of the held version
            boolean same = lobby.entries() == first;
            showList(2, entry(0, "a", true, 2));
            assertAll(
                () -> assertTrue(same, "a keep-alive page changes nothing"),
                () -> assertTrue(lobby.entries().get(0).active(), "the new list is shown"),
                () -> assertEquals(2, lobby.entries().get(0).humans(), "with its humans"),
                () -> assertEquals(2, lobby.version(), "and a new list version"));
        }

        @Test
        @DisplayName("LIST_REQUEST goes out every second while idle, with the held version")
        void keepAlive() {
            greet();
            showList(4, entry(0, "a", false, 0));
            wire.sent.clear();
            now += 999 * MS;
            lobby.tick(now);
            int early = wire.sent.size();
            now += 1 * MS;
            lobby.tick(now);
            assertAll(
                () -> assertEquals(0, early, "not before a second since the last request"),
                () -> assertEquals(1, wire.sent.size(), "one request at 1 s"),
                () -> assertEquals(4, LobbyWire.request(wire.sent.get(0)).haveVersion, "naming the version held"));
        }

        @Test
        @DisplayName("an undecodable list datagram is counted as malformed and ignored")
        void malformed() {
            greet();
            byte[] bad = LobbyWire.dg(PacketType.LOBBY_LIST, b -> b.putInt(3).putShort((short) 9).putShort((short) 2));
            boolean ok = deliver(bad);
            assertAll(
                () -> assertFalse(ok, "not accepted"),
                () -> assertEquals(1, lobby.malformed(), "counted"),
                () -> assertTrue(lobby.entries().isEmpty(), "no list"));
        }
    }

    @Nested
    @DisplayName("choosing a map")
    class Choosing {
        @Test
        @DisplayName("before the greeting is answered a choice is refused")
        void tooEarly() {
            lobby.begin(now, NONCE);
            wire.sent.clear();
            boolean ok = lobby.select(3, "doggy", now);
            assertAll(
                () -> assertFalse(ok, "refused"),
                () -> assertTrue(wire.sent.isEmpty(), "nothing sent"),
                () -> assertEquals(-1, lobby.selecting(), "nothing pending"));
        }

        @Test
        @DisplayName("select sends SELECT with the cookie and map id at once and says what it asks for")
        void select() {
            greet();
            boolean ok = lobby.select(12, "doggy", now);
            LobbyMsg.Select s = LobbyWire.select(wire.sent.get(0));
            assertAll(
                () -> assertTrue(ok, "accepted"),
                () -> assertEquals(PacketType.LOBBY_SELECT, LobbyWire.type(wire.sent.get(0)), "a SELECT"),
                () -> assertEquals(12, s.mapId, "for the map"),
                () -> assertEquals(COOKIE, s.cookie, "with the cookie"),
                () -> assertEquals(12, lobby.selecting(), "pending"),
                () -> assertEquals("Asking for doggy...", lobby.message(), "message"));
        }

        @Test
        @DisplayName("a second choice while one waits is refused")
        void onlyOne() {
            greet();
            lobby.select(1, "a", now);
            wire.sent.clear();
            assertAll(
                () -> assertFalse(lobby.select(2, "b", now), "refused"),
                () -> assertTrue(wire.sent.isEmpty(), "nothing sent"),
                () -> assertEquals(1, lobby.selecting(), "the first stays pending"));
        }

        @Test
        @DisplayName("SELECT is resent every 500 ms and no LIST_REQUEST is sent meanwhile")
        void resendSelect() {
            greet();
            lobby.select(5, "doggy", now);
            wire.sent.clear();
            now += 499 * MS;
            lobby.tick(now);
            int early = wire.sent.size();
            now += 1 * MS;
            lobby.tick(now);
            now += 2000 * MS;
            lobby.tick(now);
            assertAll(
                () -> assertEquals(0, early, "not before 500 ms"),
                () -> assertEquals(2, wire.sent.size(), "a resend at 500 ms and one more after"),
                () -> assertTrue(wire.sent.stream().allMatch(p -> LobbyWire.type(p) == PacketType.LOBBY_SELECT),
                    "only SELECTs: a LIST_REQUEST would drop the route the server may just have made"));
        }

        @Test
        @DisplayName("a list completed while a select waits is not confirmed (the map it starts pushes one); after the"
            + " select is answered (here: failed) the next keep-alive asks with the version held")
        void noConfirmWhileSelecting() {
            greet();
            showList(1, entry(5, "doggy", false, 0));
            lobby.select(5, "doggy", now);
            deliver(LobbyWire.join(5, LobbyMsg.JOIN_STARTING, ""));
            wire.sent.clear();
            showList(2, entry(5, "doggy", true, 0)); // doggy started: the server pushes the new list
            int whileSelecting = wire.sent.size();
            deliver(LobbyWire.join(5, LobbyMsg.JOIN_FAILED, "boom"));
            now += 1000 * MS;
            lobby.tick(now);
            assertAll(
                () -> assertEquals(0, whileSelecting, "no LIST_REQUEST while the select waits: it could overtake the"
                    + " SELECT and drop the route the JOIN makes"),
                () -> assertEquals(2, lobby.version(), "the new list is shown all the same"),
                () -> assertEquals(1, wire.sent.size(), "after the select ends, the keep-alive goes out"),
                () -> assertEquals(PacketType.LOBBY_LIST_REQUEST, LobbyWire.type(wire.sent.get(0)), "a LIST_REQUEST"),
                () -> assertEquals(2, LobbyWire.request(wire.sent.get(0)).haveVersion, "naming the new version"));
        }

        @Test
        @DisplayName("JOIN OK is reported once by takeJoined and ends the pending select")
        void joinOk() {
            greet();
            lobby.select(5, "doggy", now);
            deliver(LobbyWire.join(5, LobbyMsg.JOIN_OK, ""));
            boolean first = lobby.takeJoined();
            boolean second = lobby.takeJoined();
            assertAll(
                () -> assertTrue(first, "joined"),
                () -> assertFalse(second, "reported once"),
                () -> assertEquals(-1, lobby.selecting(), "nothing pending"),
                () -> assertEquals("Joining doggy...", lobby.message(), "message"));
        }

        @Test
        @DisplayName("JOIN STARTING shows the start in progress and keeps asking")
        void starting() {
            greet();
            lobby.select(5, "doggy", now);
            deliver(LobbyWire.join(5, LobbyMsg.JOIN_STARTING, ""));
            assertAll(
                () -> assertEquals("Starting doggy...", lobby.message(), "message"),
                () -> assertEquals(5, lobby.selecting(), "still pending"),
                () -> assertFalse(lobby.takeJoined(), "not joined yet"));
        }

        @Test
        @DisplayName("JOIN FAILED shows the server's reason (cut to 80 characters) and allows another choice")
        void failed() {
            greet();
            lobby.select(5, "doggy", now);
            deliver(LobbyWire.join(5, LobbyMsg.JOIN_FAILED, "x".repeat(200)));
            String m = lobby.message();
            assertAll(
                () -> assertTrue(m.startsWith("Cannot start doggy: xxx"), "message: " + m),
                () -> assertEquals("Cannot start doggy: ".length() + LobbyClient.MAX_TEXT, m.length(), "reason cut"),
                () -> assertEquals(-1, lobby.selecting(), "nothing pending"),
                () -> assertTrue(lobby.select(6, "other", now), "another choice is possible"));
        }

        @Test
        @DisplayName("JOIN UNKNOWN_MAP says the server has no such map")
        void unknown() {
            greet();
            lobby.select(5, "doggy", now);
            deliver(LobbyWire.join(5, LobbyMsg.JOIN_UNKNOWN_MAP, "no such map"));
            assertAll(
                () -> assertEquals("The server has no map doggy.", lobby.message(), "message"),
                () -> assertEquals(-1, lobby.selecting(), "nothing pending"));
        }

        @Test
        @DisplayName("a notice shows (cut to 80 characters) until the next choice replaces it")
        void notice() {
            greet();
            lobby.notice("Refused: " + "y".repeat(200));
            String shown = lobby.message();
            lobby.select(1, "a", now);
            assertAll(
                () -> assertEquals(LobbyClient.MAX_TEXT, shown.length(), "cut"),
                () -> assertTrue(shown.startsWith("Refused: yyy"), "starts as given"),
                () -> assertEquals("Asking for a...", lobby.message(), "the next choice replaces it"));
        }

        @Test
        @DisplayName("map id 0 is a real map: a pending select of it blocks other choices and is resent")
        void mapZero() {
            greet();
            lobby.select(0, "first", now);
            boolean second = lobby.select(1, "other", now);
            wire.sent.clear();
            now += 500 * MS;
            lobby.tick(now);
            assertAll(
                () -> assertFalse(second, "refused while map 0 is pending"),
                () -> assertEquals(0, lobby.selecting(), "map 0 pending"),
                () -> assertEquals(1, wire.sent.size(), "SELECT resent"),
                () -> assertEquals(0, LobbyWire.select(wire.sent.get(0)).mapId, "for map 0"));
        }

        @Test
        @DisplayName("texts of exactly 80 characters are kept whole")
        void exactTextLimit() {
            greet();
            lobby.notice("n".repeat(80));
            String notice = lobby.message();
            lobby.select(2, "d", now);
            deliver(LobbyWire.join(2, LobbyMsg.JOIN_FAILED, "r".repeat(80)));
            assertAll(
                () -> assertEquals(80, notice.length(), "notice whole"),
                () -> assertEquals("Cannot start d: ".length() + 80, lobby.message().length(), "reason whole"));
        }

        @Test
        @DisplayName("list pages, completed lists and JOINs of this dialogue are reported as valid datagrams")
        void validDatagramsReported() {
            greet();
            LobbyEntry[] one = {entry(0, "a", false, 0)};
            boolean listOk = deliver(LobbyWire.list(1, List.of(one), 1000).get(0));
            boolean keepAlive = deliver(LobbyWire.list(1, List.of(), 1000).get(0));
            boolean strayJoin = deliver(LobbyWire.join(9, LobbyMsg.JOIN_OK, ""));
            lobby.select(0, "a", now);
            boolean join = deliver(LobbyWire.join(0, LobbyMsg.JOIN_STARTING, ""));
            assertAll(
                () -> assertTrue(listOk, "a page that completes the list"),
                () -> assertTrue(keepAlive, "a keep-alive page"),
                () -> assertTrue(strayJoin, "a JOIN with nothing pending still proves the server is there"),
                () -> assertTrue(join, "the answer to our select"));
        }

        @Test
        @DisplayName("a JOIN for a map not asked for, or with nothing pending, changes nothing")
        void strayJoin() {
            greet();
            deliver(LobbyWire.join(5, LobbyMsg.JOIN_OK, ""));
            lobby.select(5, "doggy", now);
            deliver(LobbyWire.join(9, LobbyMsg.JOIN_OK, ""));
            assertAll(
                () -> assertFalse(lobby.takeJoined(), "no join"),
                () -> assertEquals(5, lobby.selecting(), "still waiting for map 5"));
        }
    }

    @Test
    @DisplayName("a send error is counted, not thrown")
    void sendErrors() {
        LobbyClient broken = new LobbyClient(new Transport() {
            @Override
            public boolean send(ByteBuffer packet) throws java.io.IOException {
                throw new java.io.IOException("unreachable");
            }

            @Override
            public int receive(ByteBuffer buf) {
                return 0;
            }

            @Override
            public void close() { }
        });
        broken.begin(now, 1);
        assertEquals(1, broken.ioErrors(), "the failed HELLO was counted");
    }
}
