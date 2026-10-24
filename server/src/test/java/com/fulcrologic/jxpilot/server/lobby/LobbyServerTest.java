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
package com.fulcrologic.jxpilot.server.lobby;

import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.lobby.LobbyListAssembler;
import com.fulcrologic.jxpilot.common.map.BundledMaps;
import com.fulcrologic.jxpilot.common.net.LobbyMsg;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.server.net.NetTransport;
import com.fulcrologic.jxpilot.server.net.Server;
import com.fulcrologic.jxpilot.server.net.UdpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lobby protocol and routing, with no sockets: datagrams are fed in and replies recorded. */
@DisplayName("LobbyServer: lobby protocol and routing")
class LobbyServerTest {
    record Sent(int channel, SocketAddress to, ByteBuffer data, int type) { }

    /** Records the lobby's replies. */
    static final class Wire implements NetTransport {
        final List<Sent> sent = new ArrayList<>();
        UdpTransport.Receiver receiver;

        @Override
        public void setReceiver(UdpTransport.Receiver r) {
            receiver = r;
        }

        @Override
        public void poll(long timeoutNanos) { }

        @Override
        public void wakeup() { }

        @Override
        public void close() { }

        @Override
        public void send(int channel, SocketAddress to, ByteBuffer data) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data.duplicate()).flip();
            int type = copy.remaining() > 3 ? copy.get(3) & 0xFF : -1;
            sent.add(new Sent(channel, to, copy, type));
        }

        List<Sent> of(int type) {
            return sent.stream().filter(s -> s.type() == type).toList();
        }
    }

    static final long SEC = 1_000_000_000L;
    static final SocketAddress ALICE = new InetSocketAddress("10.0.0.1", 5000);
    static final SocketAddress ALICE2 = new InetSocketAddress("10.0.0.1", 5001);
    static final SocketAddress BOB = new InetSocketAddress("10.0.0.2", 6000);

    Wire wire;
    LobbyServer lobby;
    LobbyRegistry registry;
    LobbyCatalog catalog;
    final List<LobbyRegistryTest.FakeGame> games = new ArrayList<>();
    long now = 1000 * SEC;
    final List<String> logLines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        wire = new Wire();
        catalog = LobbyCatalog.build(BundledMaps.standard(), List.of(), Set.of(), w -> { });
        registry = new LobbyRegistry(catalog, (offer, t) -> {
            LobbyRegistryTest.FakeGame g = new LobbyRegistryTest.FakeGame(offer, t);
            games.add(g);
            return g;
        }, wire, Runnable::run, l -> { });
        Server.Settings s = new Server.Settings();
        s.log = logLines::add;
        lobby = LobbyServer.over(wire, registry, "friends lobby", s, 0x5EC12E7L);
    }

    int id(String name) {
        return catalog.offers().stream().filter(o -> o.name().equals(name)).findFirst().orElseThrow().id();
    }

    // ---- datagram builders

    static ByteBuffer dg(int type, Consumer<ByteBuffer> body) {
        ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
        PacketHeader.write(b, type, 0, 0);
        body.accept(b);
        return b.flip();
    }

    ByteBuffer hello(int nonce) {
        LobbyMsg.Hello h = new LobbyMsg.Hello();
        h.nonce = nonce;
        return dg(PacketType.LOBBY_HELLO, h::write);
    }

    ByteBuffer listRequest(long cookie, int have) {
        LobbyMsg.ListRequest r = new LobbyMsg.ListRequest();
        r.cookie = cookie;
        r.haveVersion = have;
        return dg(PacketType.LOBBY_LIST_REQUEST, r::write);
    }

    ByteBuffer select(long cookie, int mapId) {
        LobbyMsg.Select r = new LobbyMsg.Select();
        r.cookie = cookie;
        r.mapId = mapId;
        return dg(PacketType.LOBBY_SELECT, r::write);
    }

    ByteBuffer connect(int nonce, String name, String shape) {
        return dg(PacketType.CONNECT, b -> {
            Msg.Connect c = new Msg.Connect();
            c.nonce = nonce;
            c.team = 255;
            c.name.append(name);
            c.shape.append(shape);
            c.write(b);
        });
    }

    void send(SocketAddress from, ByteBuffer data) {
        lobby.onDatagram(0, from, data, now);
    }

    long cookieOf(SocketAddress who) {
        wire.sent.clear();
        send(who, hello(1));
        ByteBuffer b = wire.of(PacketType.LOBBY_INFO).get(0).data().duplicate();
        b.position(PacketHeader.SIZE);
        LobbyMsg.Info i = new LobbyMsg.Info();
        i.read(b);
        wire.sent.clear();
        return i.cookie;
    }

    /** Reassemble the lists sent to {@code to} so far. */
    List<LobbyEntry> receivedList(SocketAddress to, int[] versionOut) {
        LobbyListAssembler a = new LobbyListAssembler();
        for (Sent s : wire.of(PacketType.LOBBY_LIST)) {
            if (!s.to().equals(to)) {
                continue;
            }
            ByteBuffer b = s.data().duplicate();
            b.position(PacketHeader.SIZE);
            LobbyMsg.ListPage p = new LobbyMsg.ListPage();
            p.read(b);
            a.accept(p.version, p.page, p.pageCount, p.entries);
        }
        if (versionOut != null) {
            versionOut[0] = a.version();
        }
        return a.entries();
    }

    LobbyMsg.Join lastJoin() {
        List<Sent> j = wire.of(PacketType.LOBBY_JOIN);
        ByteBuffer b = j.get(j.size() - 1).data().duplicate();
        b.position(PacketHeader.SIZE);
        LobbyMsg.Join r = new LobbyMsg.Join();
        r.read(b);
        return r;
    }

    // ------------------------------------------------------------------ HELLO

    @Nested
    @DisplayName("HELLO")
    class Hello {
        @Test
        @DisplayName("is answered INFO with its nonce, the lobby's name and a cookie, never larger than the HELLO")
        void info() {
            send(ALICE, hello(0x1234));
            Sent s = wire.of(PacketType.LOBBY_INFO).get(0);
            ByteBuffer b = s.data().duplicate();
            b.position(PacketHeader.SIZE);
            LobbyMsg.Info i = new LobbyMsg.Info();
            i.read(b);
            assertAll(
                () -> assertEquals(1, wire.sent.size(), "exactly one reply"),
                () -> assertEquals(ALICE, s.to(), "to the sender"),
                () -> assertEquals(0x1234, i.nonce, "nonce echoed"),
                () -> assertEquals("friends lobby", i.name.toString(), "lobby name"),
                () -> assertTrue(s.data().remaining() <= LobbyMsg.MIN_HELLO_SIZE, "no larger than the HELLO"));
        }

        @Test
        @DisplayName("the cookie is the same for every port of an address, and differs between addresses")
        void cookiePerAddress() {
            long a1 = cookieOf(ALICE);
            long a2 = cookieOf(ALICE2);
            long b1 = cookieOf(BOB);
            assertAll(
                () -> assertEquals(a1, a2, "same address, other port: same cookie"),
                () -> assertTrue(a1 != b1, "another address: another cookie"));
        }

        @Test
        @DisplayName("a HELLO shorter than the minimum is not answered (it could be used to reflect traffic)")
        void shortHello() {
            ByteBuffer small = dg(PacketType.LOBBY_HELLO, b -> b.putInt(1));
            send(ALICE, small);
            assertAll(
                () -> assertTrue(wire.sent.isEmpty(), "no reply"),
                () -> assertEquals(1, lobby.malformed(), "counted as malformed"));
        }
    }

    // ------------------------------------------------------------------ list

    @Nested
    @DisplayName("LIST_REQUEST")
    class ListRequests {
        @Test
        @DisplayName("without the right cookie nothing is sent (the address has not proved it receives)")
        void wrongCookie() {
            send(ALICE, listRequest(12345L, 0));
            assertAll(
                () -> assertTrue(wire.sent.isEmpty(), "no reply"),
                () -> assertEquals(1, lobby.stray(), "counted"));
        }

        @Test
        @DisplayName("another address's cookie does not work")
        void otherAddressCookie() {
            long bob = cookieOf(BOB);
            send(ALICE, listRequest(bob, 0));
            assertTrue(wire.sent.isEmpty(), "Bob's cookie from Alice's address gets nothing");
        }

        @Test
        @DisplayName("a client without the list gets every page; they reassemble to the registry's list")
        void fullList() {
            long c = cookieOf(ALICE);
            send(ALICE, listRequest(c, LobbyMsg.NO_VERSION));
            int[] v = new int[1];
            List<LobbyEntry> got = receivedList(ALICE, v);
            assertAll(
                () -> assertEquals(registry.current(), got, "the list the registry holds, in its order"),
                () -> assertEquals(registry.version(), v[0], "stamped with the registry's version"),
                () -> assertTrue(wire.of(PacketType.LOBBY_LIST).stream().allMatch(s -> s.data().remaining() <= Protocol.MAX_PACKET),
                    "every page fits a datagram"));
        }

        @Test
        @DisplayName("a client that already has the current version gets one empty page of it, as a keep-alive")
        void upToDate() {
            long c = cookieOf(ALICE);
            send(ALICE, listRequest(c, registry.version()));
            ByteBuffer b = wire.of(PacketType.LOBBY_LIST).get(0).data().duplicate();
            b.position(PacketHeader.SIZE);
            LobbyMsg.ListPage p = new LobbyMsg.ListPage();
            p.read(b);
            assertAll(
                () -> assertEquals(1, wire.sent.size(), "exactly one datagram"),
                () -> assertEquals(registry.version(), p.version, "of the current version"),
                () -> assertEquals(1, p.pageCount, "a single page"),
                () -> assertTrue(p.entries.isEmpty(), "with no entries"),
                () -> assertTrue(wire.sent.get(0).data().remaining() < 30, "small: no amplification"));
        }

        @Test
        @DisplayName("a repeated request with an old version within 200 ms is not answered again; after it, it is")
        void resendGap() {
            long c = cookieOf(ALICE);
            send(ALICE, listRequest(c, 0));
            int first = wire.of(PacketType.LOBBY_LIST).size();
            now += 100_000_000L;
            send(ALICE, listRequest(c, 0));
            int soon = wire.of(PacketType.LOBBY_LIST).size();
            now += 150_000_000L;
            send(ALICE, listRequest(c, 0));
            int later = wire.of(PacketType.LOBBY_LIST).size();
            assertAll(
                () -> assertTrue(first > 0, "answered at first"),
                () -> assertEquals(first, soon, "not again after 100 ms"),
                () -> assertEquals(2 * first, later, "again after 250 ms (a lost page is recovered)"));
        }
    }

    // ------------------------------------------------------------------ pushing changes

    @Nested
    @DisplayName("live updates")
    class Updates {
        @Test
        @DisplayName("when a map becomes active the next tick pushes the new list to listening peers, once")
        void push() {
            long c = cookieOf(ALICE);
            send(ALICE, listRequest(c, 0));
            wire.sent.clear();
            registry.select(id("doggy")); // somebody else starts doggy
            now += LobbyServer.TICK_NANOS;
            lobby.tick(now);
            int[] v = new int[1];
            List<LobbyEntry> pushed = receivedList(ALICE, v);
            int pages = wire.of(PacketType.LOBBY_LIST).size();
            now += LobbyServer.TICK_NANOS;
            lobby.tick(now);
            assertAll(
                () -> assertEquals("doggy", pushed.get(0).name(), "doggy now leads the list"),
                () -> assertTrue(pushed.get(0).active(), "and is active"),
                () -> assertEquals(registry.version(), v[0], "with the new version"),
                () -> assertEquals(pages, wire.of(PacketType.LOBBY_LIST).size(), "a further tick sends nothing more"));
        }

        @Test
        @DisplayName("a human count change is pushed too")
        void humansPush() {
            long c = cookieOf(ALICE);
            registry.select(id("doggy"));
            lobby.tick(now);
            send(ALICE, listRequest(c, 0));
            wire.sent.clear();
            games.get(0).humans = 4;
            now += LobbyServer.TICK_NANOS;
            lobby.tick(now);
            assertEquals(4, receivedList(ALICE, null).get(0).humans(), "doggy shows 4 humans");
        }

        @Test
        @DisplayName("a peer that confirmed the current version is not sent it again")
        void confirmed() {
            long c = cookieOf(ALICE);
            send(ALICE, listRequest(c, 0));
            send(ALICE, listRequest(c, registry.version()));
            wire.sent.clear();
            now += LobbyServer.TICK_NANOS;
            lobby.tick(now);
            assertTrue(wire.sent.isEmpty(), "nothing changed, nothing pushed");
        }

        @Test
        @DisplayName("peers that stopped asking for 15 s are forgotten and get no more pushes")
        void peerExpiry() {
            long c = cookieOf(ALICE);
            send(ALICE, listRequest(c, 0));
            now += LobbyServer.PEER_IDLE_NANOS + 1;
            registry.select(id("doggy"));
            wire.sent.clear();
            lobby.tick(now);
            assertTrue(wire.of(PacketType.LOBBY_LIST).isEmpty(), "Alice was dropped, so nothing is pushed to her");
        }

        @Test
        @DisplayName("at most MAX_PEERS addresses are kept; the next one is not served")
        void peerLimit() {
            for (int i = 0; i < LobbyServer.MAX_PEERS; i++) {
                SocketAddress who = new InetSocketAddress("10.1." + (i / 250) + "." + (i % 250 + 1), 7000);
                long c = cookieOf(who);
                send(who, listRequest(c, registry.version())); // confirms: becomes a peer
            }
            SocketAddress extra = new InetSocketAddress("10.9.9.9", 7000);
            long c = cookieOf(extra);
            wire.sent.clear();
            send(extra, listRequest(c, 0));
            assertTrue(wire.of(PacketType.LOBBY_LIST).isEmpty(), "no list for the 1025th peer");
        }
    }

    // ------------------------------------------------------------------ select

    @Nested
    @DisplayName("SELECT and routing")
    class Selecting {
        @Test
        @DisplayName("without the cookie nothing happens and no game starts")
        void needsCookie() {
            send(ALICE, select(99L, id("doggy")));
            assertAll(
                () -> assertTrue(wire.sent.isEmpty(), "no reply"),
                () -> assertTrue(games.isEmpty(), "no game"));
        }

        @Test
        @DisplayName("an unknown map is answered UNKNOWN_MAP")
        void unknown() {
            long c = cookieOf(ALICE);
            send(ALICE, select(c, 40000));
            LobbyMsg.Join j = lastJoin();
            assertAll(
                () -> assertEquals(LobbyMsg.JOIN_UNKNOWN_MAP, j.result, "result"),
                () -> assertEquals(40000, j.mapId, "the id asked for is echoed"));
        }

        @Test
        @DisplayName("an idle map is started and answered STARTING; asking again joins it and routes the address")
        void startThenJoin() {
            long c = cookieOf(ALICE);
            int doggy = id("doggy");
            send(ALICE, select(c, doggy));
            LobbyMsg.Join first = lastJoin();
            int startedAfterFirst = games.size();
            send(ALICE, select(c, doggy));
            LobbyMsg.Join second = lastJoin();
            wire.sent.clear();
            RoutedTransportProbe probe = new RoutedTransportProbe(games.get(0).transport);
            send(ALICE, connect(77, "alice", ""));
            probe.drain();
            assertAll(
                () -> assertEquals(LobbyMsg.JOIN_STARTING, first.result, "first answer"),
                () -> assertEquals(1, startedAfterFirst, "the game started"),
                () -> assertEquals(LobbyMsg.JOIN_OK, second.result, "second answer"),
                () -> assertEquals(doggy, second.mapId, "for that map"),
                () -> assertTrue(wire.sent.isEmpty(), "the CONNECT after the join is not answered by the lobby"),
                () -> assertEquals(1, probe.delivered.size(), "it went to the game instead"),
                () -> assertEquals(ALICE, probe.delivered.get(0).from(), "from Alice's address"));
        }

        @Test
        @DisplayName("every datagram type from a routed address goes to its game untouched, malformed ones included")
        void forwardsEverything() {
            long c = cookieOf(ALICE);
            send(ALICE, select(c, id("doggy")));
            send(ALICE, select(c, id("doggy")));
            RoutedTransportProbe probe = new RoutedTransportProbe(games.get(0).transport);
            ByteBuffer input = dg(PacketType.INPUT, b -> b.putInt(5));
            ByteBuffer garbage = ByteBuffer.wrap(new byte[] {1, 2, 3});
            ByteBuffer oldVersion = dg(PacketType.CONNECT, b -> b.putInt(1));
            oldVersion.put(2, (byte) 99);
            send(ALICE, input);
            send(ALICE, garbage);
            send(ALICE, oldVersion);
            probe.drain();
            assertAll(
                () -> assertEquals(3, probe.delivered.size(), "all three were forwarded"),
                () -> assertEquals(PacketType.INPUT, probe.delivered.get(0).type(), "INPUT as sent"),
                () -> assertEquals(3, probe.delivered.get(1).size(), "garbage as sent"),
                () -> assertEquals(99, probe.delivered.get(2).version(), "another protocol version as sent: the game answers it"),
                () -> assertTrue(wire.of(PacketType.REJECT).isEmpty(), "the lobby itself said nothing"));
        }

        @Test
        @DisplayName("two ports of one address are routed separately; an unrouted one still gets the lobby")
        void perPort() {
            long c = cookieOf(ALICE);
            send(ALICE, select(c, id("doggy")));
            send(ALICE, select(c, id("doggy")));
            wire.sent.clear();
            send(ALICE2, connect(5, "alice-two", ""));
            assertEquals(1, wire.of(PacketType.REJECT).size(), "the other port is not routed: it gets REJECT LOBBY");
        }

        @Test
        @DisplayName("routes of different clients go to the games of their maps")
        void twoGames() {
            long a = cookieOf(ALICE);
            long b = cookieOf(BOB);
            send(ALICE, select(a, id("doggy")));
            send(ALICE, select(a, id("doggy")));
            send(BOB, select(b, id("tourmination")));
            send(BOB, select(b, id("tourmination")));
            RoutedTransportProbe pa = new RoutedTransportProbe(games.get(0).transport);
            RoutedTransportProbe pb = new RoutedTransportProbe(games.get(1).transport);
            send(ALICE, dg(PacketType.ACK, x -> { }));
            send(BOB, dg(PacketType.ACK, x -> { }));
            pa.drain();
            pb.drain();
            assertAll(
                () -> assertEquals(1, pa.delivered.size(), "doggy's game got Alice's datagram"),
                () -> assertEquals(ALICE, pa.delivered.get(0).from(), "only Alice's"),
                () -> assertEquals(1, pb.delivered.size(), "tourmination's game got Bob's"),
                () -> assertEquals(BOB, pb.delivered.get(0).from(), "only Bob's"));
        }

        @Test
        @DisplayName("a routed address that asks for the list again is back in the lobby: its route is dropped")
        void backToLobby() {
            long c = cookieOf(ALICE);
            send(ALICE, select(c, id("doggy")));
            send(ALICE, select(c, id("doggy")));
            wire.sent.clear();
            send(ALICE, listRequest(c, 0));
            int pages = wire.of(PacketType.LOBBY_LIST).size();
            send(ALICE, connect(8, "alice", ""));
            assertAll(
                () -> assertTrue(pages > 0, "she gets the list"),
                () -> assertEquals(1, wire.of(PacketType.REJECT).size(), "and her CONNECT is no longer routed"));
        }

        @Test
        @DisplayName("a HELLO from a routed address is answered but keeps its route")
        void helloKeepsRoute() {
            long c = cookieOf(ALICE);
            send(ALICE, select(c, id("doggy")));
            send(ALICE, select(c, id("doggy")));
            wire.sent.clear();
            send(ALICE, hello(3));
            RoutedTransportProbe probe = new RoutedTransportProbe(games.get(0).transport);
            send(ALICE, dg(PacketType.ACK, x -> { }));
            probe.drain();
            assertAll(
                () -> assertEquals(1, wire.of(PacketType.LOBBY_INFO).size(), "INFO sent"),
                () -> assertEquals(1, probe.delivered.size(), "the route still works (a spoofed HELLO cannot cut a player off)"));
        }

        @Test
        @DisplayName("a route unused for 20 s expires; use refreshes it")
        void routeExpiry() {
            long c = cookieOf(ALICE);
            send(ALICE, select(c, id("doggy")));
            send(ALICE, select(c, id("doggy")));
            RoutedTransportProbe probe = new RoutedTransportProbe(games.get(0).transport);
            now += 15 * SEC;
            send(ALICE, dg(PacketType.ACK, x -> { })); // 15 s after the route was made: refreshes it
            now += 15 * SEC;
            lobby.tick(now); // 30 s after the route was made, 15 s after its last use
            send(ALICE, dg(PacketType.ACK, x -> { }));
            probe.drain();
            int stillRouted = probe.delivered.size();
            now += LobbyServer.ROUTE_IDLE_NANOS + 1;
            lobby.tick(now);
            wire.sent.clear();
            send(ALICE, connect(9, "alice", ""));
            assertAll(
                () -> assertEquals(2, stillRouted, "both datagrams reached the game: use refreshed the route, so 30 s after it was made it still works"),
                () -> assertEquals(1, wire.of(PacketType.REJECT).size(), "after 20 s of silence it does not"));
        }

        @Test
        @DisplayName("when a game ends its routes go; the clients' next CONNECT gets REJECT LOBBY and the map is idle again")
        void gameEnds() {
            long c = cookieOf(ALICE);
            send(ALICE, select(c, id("doggy")));
            send(ALICE, select(c, id("doggy")));
            games.get(0).running = false;
            now += LobbyServer.TICK_NANOS;
            lobby.tick(now);
            wire.sent.clear();
            send(ALICE, connect(10, "alice", ""));
            assertAll(
                () -> assertEquals(1, wire.of(PacketType.REJECT).size(), "no route any more: back to the lobby"),
                () -> assertEquals(0, registry.runningCount(), "the game is gone"),
                () -> assertFalse(registry.current().stream().anyMatch(LobbyEntry::active), "and the list shows it idle"));
        }
    }

    // ------------------------------------------------------------------ CONNECT without a route

    @Nested
    @DisplayName("CONNECT without a route")
    class Connect {
        @Test
        @DisplayName("is answered REJECT with reason LOBBY and the CONNECT's nonce, no larger than the CONNECT")
        void rejectLobby() {
            ByteBuffer c = connect(0xABCD, "alice", "(shape:)");
            int size = c.remaining();
            send(ALICE, c);
            Sent s = wire.of(PacketType.REJECT).get(0);
            ByteBuffer b = s.data().duplicate();
            b.position(PacketHeader.SIZE);
            Msg.Reject r = new Msg.Reject();
            r.read(b);
            assertAll(
                () -> assertEquals(Msg.Reject.REASON_LOBBY, r.reason, "reason LOBBY"),
                () -> assertEquals(0xABCD, r.nonce, "nonce echoed"),
                () -> assertTrue(s.data().remaining() <= size, "never larger than the request (anti-reflection)"),
                () -> assertEquals(ALICE, s.to(), "to the sender"));
        }

        @Test
        @DisplayName("the smallest possible CONNECT still gets a REJECT that is not larger")
        void smallest() {
            ByteBuffer c = connect(1, "a", "");
            int size = c.remaining();
            send(ALICE, c);
            assertTrue(wire.of(PacketType.REJECT).get(0).data().remaining() <= size, "cut to fit");
        }

        @Test
        @DisplayName("a datagram too short to hold a CONNECT's nonce is dropped as malformed")
        void tooShort() {
            send(ALICE, dg(PacketType.CONNECT, b -> b.put((byte) 1)));
            assertAll(
                () -> assertTrue(wire.sent.isEmpty(), "no reply"),
                () -> assertEquals(1, lobby.malformed(), "counted"));
        }

        @Test
        @DisplayName("a CONNECT of another protocol version is answered REJECT with reason VERSION")
        void otherVersion() {
            ByteBuffer c = connect(0x77, "alice", "");
            c.put(2, (byte) (Protocol.VERSION + 1));
            send(ALICE, c);
            Sent s = wire.of(PacketType.REJECT).get(0);
            ByteBuffer b = s.data().duplicate();
            b.position(PacketHeader.SIZE);
            Msg.Reject r = new Msg.Reject();
            r.read(b);
            assertAll(
                () -> assertEquals(Msg.Reject.REASON_VERSION, r.reason, "reason VERSION"),
                () -> assertEquals(0x77, r.nonce, "nonce"));
        }

        @Test
        @DisplayName("garbage is dropped and counted, with no reply")
        void garbage() {
            send(ALICE, ByteBuffer.wrap(new byte[] {9, 9, 9, 9, 9, 9, 9, 9, 9, 9}));
            assertAll(
                () -> assertTrue(wire.sent.isEmpty(), "no reply"),
                () -> assertEquals(1, lobby.malformed(), "counted"));
        }

        @Test
        @DisplayName("session packets from an unrouted address are strays: dropped, counted, no reply")
        void stray() {
            send(ALICE, dg(PacketType.INPUT, b -> b.putInt(1)));
            assertAll(
                () -> assertTrue(wire.sent.isEmpty(), "no reply"),
                () -> assertEquals(1, lobby.stray(), "counted"));
        }
    }

    // ------------------------------------------------------------------ limits

    @Nested
    @DisplayName("abuse limits")
    class Limits {
        @Test
        @DisplayName("an address that floods unrouted datagrams is limited to its burst; others are unaffected")
        void rateLimit() {
            for (int i = 0; i < 2000; i++) {
                send(ALICE, hello(i));
            }
            int answered = wire.of(PacketType.LOBBY_INFO).size();
            send(BOB, hello(1));
            assertAll(
                () -> assertEquals(800, answered, "the burst of 800, then nothing at the same instant"),
                () -> assertEquals(1200, lobby.rateLimited(), "the rest counted"),
                () -> assertEquals(answered + 1, wire.of(PacketType.LOBBY_INFO).size(), "Bob is answered"));
        }

        @Test
        @DisplayName("routed traffic is not charged to the lobby's limiter (the game limits its own sessions)")
        void routedNotLimited() {
            long c = cookieOf(ALICE);
            send(ALICE, select(c, id("doggy")));
            send(ALICE, select(c, id("doggy")));
            RoutedTransportProbe probe = new RoutedTransportProbe(games.get(0).transport);
            for (int i = 0; i < 2000; i++) {
                send(ALICE, dg(PacketType.ACK, x -> { }));
            }
            probe.drain();
            assertAll(
                () -> assertEquals(0, lobby.rateLimited(), "nothing limited"),
                () -> assertEquals(RoutedTransport.MAX_DRAIN, probe.delivered.size(),
                    "one poll delivers up to its drain limit; the pool holds the rest for the next"));
        }
    }

    // ------------------------------------------------------------------ details and boundaries

    @Nested
    @DisplayName("details")
    class Details {
        @Test
        @DisplayName("the lobby registers itself as the transport's receiver, so datagrams reach it")
        void receiverRegistered() {
            ByteBuffer h = hello(9);
            wire.receiver.onDatagram(0, ALICE, h, now);
            assertEquals(1, wire.of(PacketType.LOBBY_INFO).size(), "a datagram given to the transport's receiver is handled");
        }

        @Test
        @DisplayName("channels beyond the first few work: routes and peers grow with the channel number")
        void manyChannels() {
            for (int channel : new int[] {9, 40}) {
                lobby.onDatagram(channel, ALICE, hello(1), now);
                ByteBuffer b = wire.of(PacketType.LOBBY_INFO).get(wire.of(PacketType.LOBBY_INFO).size() - 1).data().duplicate();
                b.position(PacketHeader.SIZE);
                LobbyMsg.Info i = new LobbyMsg.Info();
                i.read(b);
                lobby.onDatagram(channel, ALICE, listRequest(i.cookie, 0), now);
                lobby.onDatagram(channel, ALICE, select(i.cookie, id("doggy")), now);
                lobby.onDatagram(channel, ALICE, select(i.cookie, id("doggy")), now);
                RoutedTransportProbe probe = new RoutedTransportProbe(games.get(0).transport);
                lobby.onDatagram(channel, ALICE, dg(PacketType.ACK, x -> { }), now);
                probe.drain();
                assertAll(
                    () -> assertEquals(channel, wire.sent.get(wire.sent.size() - 1).channel(), "the JOIN answer left on the arrival channel"),
                    () -> assertEquals(1, probe.delivered.size(), "channel " + channel + ": routed to the game"));
            }
        }

        @Test
        @DisplayName("the HELLO answer's name is the lobby's name each time, not appended")
        void infoNameNotAccumulated() {
            send(ALICE, hello(1));
            send(BOB, hello(2));
            ByteBuffer b = wire.of(PacketType.LOBBY_INFO).get(1).data().duplicate();
            b.position(PacketHeader.SIZE);
            LobbyMsg.Info i = new LobbyMsg.Info();
            i.read(b);
            assertEquals("friends lobby", i.name.toString(), "name of the second answer");
        }

        @Test
        @DisplayName("JOIN texts do not accumulate: a failure's reason is not repeated in the next answer")
        void joinTextReset() {
            long c = cookieOf(ALICE);
            lobby = LobbyServer.over(wire, new LobbyRegistry(catalog, (offer, t) -> {
                throw new IllegalStateException("no memory");
            }, wire, Runnable::run, l -> { }), "x", new Server.Settings(), 1L);
            long cookie = cookieOf(ALICE);
            send(ALICE, select(cookie, id("doggy")));
            send(ALICE, select(cookie, id("doggy")));
            String failed = lastJoin().text.toString();
            send(ALICE, select(cookie, id("doggy")));
            String starting = lastJoin().text.toString();
            assertAll(
                () -> assertEquals("no memory", failed, "the failure's reason"),
                () -> assertEquals("", starting, "the next answer has no text"),
                () -> assertTrue(c != 0 || true, "(cookie of the first lobby unused)"));
        }

        @Test
        @DisplayName("joining logs the address and the map")
        void joinLogged() {
            long c = cookieOf(ALICE);
            send(ALICE, select(c, id("doggy")));
            send(ALICE, select(c, id("doggy")));
            assertTrue(logLines.stream().anyMatch(l -> l.contains("doggy") && l.contains("joins") && l.contains("10.0.0.1")),
                "log: " + logLines);
        }

        @Test
        @DisplayName("a select without a list request leaves the peer count alone: the 1025th subscriber is still refused")
        void joinWithoutPeerDoesNotSkewCount() {
            long c = cookieOf(BOB);
            send(BOB, select(c, id("doggy")));
            send(BOB, select(c, id("doggy"))); // joined, was never a peer
            for (int i = 0; i < LobbyServer.MAX_PEERS; i++) {
                SocketAddress who = new InetSocketAddress("10.1." + (i / 250) + "." + (i % 250 + 1), 7000);
                send(who, listRequest(cookieOf(who), registry.version()));
            }
            SocketAddress extra = new InetSocketAddress("10.9.9.9", 7000);
            long ec = cookieOf(extra);
            wire.sent.clear();
            send(extra, listRequest(ec, 0));
            assertTrue(wire.of(PacketType.LOBBY_LIST).isEmpty(), "still refused at exactly MAX_PEERS");
        }

        @Test
        @DisplayName("a peer that left (expired) frees its place")
        void expiredPeersFreePlaces() {
            for (int i = 0; i < LobbyServer.MAX_PEERS; i++) {
                SocketAddress who = new InetSocketAddress("10.1." + (i / 250) + "." + (i % 250 + 1), 7000);
                send(who, listRequest(cookieOf(who), registry.version()));
            }
            now += LobbyServer.PEER_IDLE_NANOS + 1;
            lobby.tick(now);
            SocketAddress fresh = new InetSocketAddress("10.9.9.9", 7000);
            long c = cookieOf(fresh);
            wire.sent.clear();
            send(fresh, listRequest(c, 0));
            assertFalse(wire.of(PacketType.LOBBY_LIST).isEmpty(), "a new peer is accepted once the old ones expired");
        }

        @Test
        @DisplayName("a peer idle for exactly the limit is kept; one nanosecond more drops it")
        void peerIdleBoundary() {
            long c = cookieOf(ALICE);
            send(ALICE, listRequest(c, 0));
            registry.select(id("doggy"));
            wire.sent.clear();
            now += LobbyServer.PEER_IDLE_NANOS;
            lobby.tick(now);
            int atLimit = wire.of(PacketType.LOBBY_LIST).size();
            registry.select(id("tourmination"));
            wire.sent.clear();
            now += 1;
            lobby.tick(now);
            assertAll(
                () -> assertTrue(atLimit > 0, "at the limit the peer still gets pushes"),
                () -> assertTrue(wire.of(PacketType.LOBBY_LIST).isEmpty(), "1 ns later it is gone"));
        }

        @Test
        @DisplayName("a route idle for exactly the limit still works; 1 ns more drops it")
        void routeIdleBoundary() {
            long c = cookieOf(ALICE);
            send(ALICE, select(c, id("doggy")));
            send(ALICE, select(c, id("doggy")));
            RoutedTransportProbe probe = new RoutedTransportProbe(games.get(0).transport);
            now += LobbyServer.ROUTE_IDLE_NANOS;
            lobby.tick(now);
            send(ALICE, dg(PacketType.ACK, x -> { }));
            probe.drain();
            int atLimit = probe.delivered.size();
            now += LobbyServer.ROUTE_IDLE_NANOS + 1; // idle for limit + 1 since the datagram above
            lobby.tick(now);
            wire.sent.clear();
            send(ALICE, connect(1, "alice", ""));
            assertAll(
                () -> assertEquals(1, atLimit, "kept at the limit"),
                () -> assertEquals(1, wire.of(PacketType.REJECT).size(), "dropped one ns past it"));
        }

        @Test
        @DisplayName("a game ending drops only the routes of its own map")
        void endedGameKeepsOtherRoutes() {
            long a = cookieOf(ALICE);
            long b = cookieOf(BOB);
            send(ALICE, select(a, id("doggy")));
            send(ALICE, select(a, id("doggy")));
            send(BOB, select(b, id("tourmination")));
            send(BOB, select(b, id("tourmination")));
            games.get(0).running = false;
            now += LobbyServer.TICK_NANOS;
            lobby.tick(now);
            RoutedTransportProbe pb = new RoutedTransportProbe(games.get(1).transport);
            send(BOB, dg(PacketType.ACK, x -> { }));
            pb.drain();
            assertEquals(1, pb.delivered.size(), "Bob, on the other map, is still routed");
        }

        @Test
        @DisplayName("CONNECT of exactly the smallest size that holds a REJECT is answered; one byte less is malformed")
        void connectSizeBoundary() {
            ByteBuffer ok = dg(PacketType.CONNECT, b -> b.putInt(1).put((byte) 0).put((byte) 0)); // 8 + 6 = 14
            ByteBuffer small = dg(PacketType.CONNECT, b -> b.putInt(1).put((byte) 0)); // 13
            send(ALICE, small);
            long malformedBefore = lobby.malformed();
            int repliesSmall = wire.of(PacketType.REJECT).size();
            send(ALICE, ok);
            assertAll(
                () -> assertEquals(1, malformedBefore, "13 bytes: malformed"),
                () -> assertEquals(0, repliesSmall, "no reply to it"),
                () -> assertEquals(1, wire.of(PacketType.REJECT).size(), "14 bytes: answered"),
                () -> assertEquals(14, wire.of(PacketType.REJECT).get(0).data().remaining(), "with a REJECT of the same size"));
        }

        @Test
        @DisplayName("a REJECT's text is cut to the request's size, and the next reject starts clean")
        void rejectTextCut() {
            ByteBuffer c = connect(0x1111, "a", "");
            send(ALICE, c);
            ByteBuffer b = wire.of(PacketType.REJECT).get(0).data().duplicate();
            b.position(PacketHeader.SIZE);
            Msg.Reject r = new Msg.Reject();
            r.read(b);
            ByteBuffer v = connect(0x2222, "alice", "(shape)");
            v.put(2, (byte) (Protocol.VERSION + 1));
            int vSize = v.limit();
            send(BOB, v);
            ByteBuffer b2 = wire.of(PacketType.REJECT).get(1).data().duplicate();
            b2.position(PacketHeader.SIZE);
            Msg.Reject r2 = new Msg.Reject();
            r2.read(b2);
            assertAll(
                () -> assertEquals(c.limit(), wire.of(PacketType.REJECT).get(0).data().remaining(), "same size as the CONNECT"),
                () -> assertEquals("lobby: choose a map (this server needs a lobby-aware client)".substring(0, c.limit() - 14),
                    r.text.toString(), "text cut to exactly what fits"),
                () -> assertEquals("incompatible version".substring(0, vSize - 14), r2.text.toString(),
                    "the next reject's text is its own, cut to its request"));
        }

        @Test
        @DisplayName("a CONNECT of another version works at any buffer position and at exactly REJECT size")
        void otherVersionBoundaries() {
            ByteBuffer v = connect(0x3333, "alice", "");
            v.put(2, (byte) (Protocol.VERSION + 1));
            ByteBuffer shifted = ByteBuffer.allocate(v.remaining() + 3);
            shifted.position(3);
            shifted.put(v).flip().position(3);
            send(ALICE, shifted);
            ByteBuffer b = wire.of(PacketType.REJECT).get(0).data().duplicate();
            b.position(PacketHeader.SIZE);
            Msg.Reject r = new Msg.Reject();
            r.read(b);
            ByteBuffer exact = dg(PacketType.CONNECT, x -> x.putInt(0x4444).put((byte) 0).put((byte) 0));
            exact.put(2, (byte) 99);
            ByteBuffer shortOne = dg(PacketType.CONNECT, x -> x.putInt(0x5555).put((byte) 0));
            shortOne.put(2, (byte) 99);
            send(BOB, exact);
            int answeredExact = wire.of(PacketType.REJECT).size();
            send(BOB, shortOne);
            assertAll(
                () -> assertEquals(0x3333, r.nonce, "nonce read from after the header, whatever the position"),
                () -> assertEquals(2, answeredExact, "14 bytes: answered"),
                () -> assertEquals(2, wire.of(PacketType.REJECT).size(), "13 bytes: not"));
        }

        @Test
        @DisplayName("a repeated list request after exactly 200 ms is answered")
        void resendGapBoundary() {
            long c = cookieOf(ALICE);
            send(ALICE, listRequest(c, 0));
            int first = wire.of(PacketType.LOBBY_LIST).size();
            now += LobbyServer.RESEND_GAP_NANOS;
            send(ALICE, listRequest(c, 0));
            assertEquals(2 * first, wire.of(PacketType.LOBBY_LIST).size(), "answered at exactly the gap");
        }
    }

    /** Pulls what was offered to a routed transport. */
    static final class RoutedTransportProbe {
        record Dg(SocketAddress from, int type, int version, int size) { }

        final List<Dg> delivered = new ArrayList<>();

        RoutedTransportProbe(RoutedTransport t) {
            this.transport = t;
            t.setReceiver((channel, from, data, nanos) -> {
                int size = data.remaining();
                int type = size > 3 ? data.get(data.position() + 3) & 0xFF : -1;
                int version = size > 2 ? data.get(data.position() + 2) & 0xFF : -1;
                delivered.add(new Dg(from, type, version, size));
            });
        }

        /** Deliver what is waiting (one poll) into {@link #delivered}. */
        RoutedTransportProbe drain() {
            transport.poll(0);
            return this;
        }

        private final RoutedTransport transport;
    }
}
