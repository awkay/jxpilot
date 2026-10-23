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

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.ConfigTransfer;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ClientConnectionTest {
    static final long MS = 1_000_000L;
    static final int SESSION = 0x2345;

    /** Transport fake: scripted incoming datagrams, recorded outgoing ones. */
    static final class Scripted implements Transport {
        final ArrayDeque<byte[]> in = new ArrayDeque<>();
        final List<ByteBuffer> out = new ArrayList<>();

        @Override
        public boolean send(ByteBuffer packet) {
            ByteBuffer c = ByteBuffer.allocate(packet.remaining());
            c.put(packet).flip();
            out.add(c);
            return true;
        }

        @Override
        public int receive(ByteBuffer buf) {
            byte[] a = in.pollFirst();
            if (a == null) {
                return 0;
            }
            buf.put(a);
            return a.length;
        }

        @Override
        public void close() {
        }

        List<Integer> types() {
            List<Integer> t = new ArrayList<>();
            for (ByteBuffer b : out) {
                t.add(b.get(3) & 0xFF);
            }
            return t;
        }

        ByteBuffer last() {
            return out.get(out.size() - 1).duplicate();
        }
    }

    /** Records listener calls. */
    static final class Rec implements ConnectionListener {
        final List<String> calls = new ArrayList<>();
        WorldMap map;
        final List<Integer> snapshotTicks = new ArrayList<>();

        @Override
        public void onAccepted(int playerId) {
            calls.add("accepted " + playerId);
        }

        @Override
        public void onWelcome(Msg.Welcome w) {
            calls.add("welcome " + w.serverName);
        }

        @Override
        public void onConfig(Msg.Welcome w, Options o, WorldMap m) {
            map = m;
            calls.add("config " + m.name());
        }

        @Override
        public void onReliable(int type, ByteBuffer p) {
            calls.add("reliable " + type);
        }

        @Override
        public void onSnapshot(ByteBuffer body, long now) {
            snapshotTicks.add(body.getInt(body.position()));
        }

        @Override
        public void onStateChange(ConnectionState s, CharSequence status) {
            calls.add(s + ": " + status);
        }
    }

    static byte[] pkt(int type, int session, int ack, Consumer<ByteBuffer> body) {
        ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
        PacketHeader.write(b, type, session, ack);
        body.accept(b);
        b.flip();
        byte[] a = new byte[b.remaining()];
        b.get(a);
        return a;
    }

    final Scripted t = new Scripted();
    final Rec rec = new Rec();
    final ClientConnection c = new ClientConnection(t, rec, "srv:1", 99);
    final ReliableChannel server = new ReliableChannel();

    void accept(long now) {
        int nonce = c.connect.nonce;
        t.in.add(pkt(PacketType.ACCEPT, SESSION, 0, b -> {
            Msg.Accept a = new Msg.Accept();
            a.nonce = nonce;
            a.playerId = 7;
            a.write(b);
        }));
        c.update(now);
    }

    /** Queue all due server reliable messages as RELIABLE datagrams. */
    void reliable(long now) {
        while (true) {
            int[] n = new int[1];
            byte[] p = pkt(PacketType.RELIABLE, SESSION, 0, b -> n[0] = server.write(b, now));
            if (n[0] == 0) {
                return;
            }
            t.in.add(p);
        }
    }

    static ConfigTransfer.Blob blob() {
        Options o = new Options();
        ConfigParser.parse(FakeServer.CONFIG, "t", OptionOrigin.MAP, o);
        return ConfigTransfer.encode(o.toConfigText());
    }

    void welcome(ConfigTransfer.Blob blob, int crcDelta) {
        Msg.Welcome w = new Msg.Welcome();
        w.simHz = 60;
        w.snapshotHz = 30;
        w.serverName.append("S");
        blob.fill(w);
        w.configCrc32 += crcDelta;
        w.send(server);
        new ConfigTransfer.Sender(blob).pump(server, 0);
    }

    void playing(long now) {
        c.start(now);
        accept(now);
        welcome(blob(), 0);
        reliable(now);
        c.update(now);
    }

    @Nested
    @DisplayName("handshake")
    class Handshake {
        @Test
        @DisplayName("CONNECT is sent at once and every 250 ms until ACCEPT")
        void connectResend() {
            c.connect.name.append("Ann");
            c.start(0);
            String status = c.status().toString();
            c.update(249 * MS);
            int before = t.out.size();
            c.update(250 * MS);
            ByteBuffer p = t.last();
            PacketHeader h = new PacketHeader();
            h.read(p);
            Msg.Connect m = new Msg.Connect();
            m.read(p);
            assertAll(
                () -> assertEquals(ConnectionState.CONNECTING, c.state(), "connecting"),
                () -> assertEquals("Connecting to srv:1 (attempt 1)...", status, "status of the first attempt"),
                () -> assertEquals(1, before, "nothing resent before 250 ms"),
                () -> assertEquals(2, t.out.size(), "resent at 250 ms"),
                () -> assertEquals(2, c.attempts(), "two attempts"),
                () -> assertEquals(PacketType.CONNECT, h.type(), "CONNECT"),
                () -> assertEquals(0, h.session(), "no session yet"),
                () -> assertEquals(c.connect.nonce, m.nonce, "nonce in the body"),
                () -> assertEquals("Ann", m.name.toString(), "name in the body"));
        }

        @Test
        @DisplayName("ACCEPT with the right nonce starts the session; bad datagrams are ignored or counted")
        void accepting() {
            c.start(0);
            t.in.add(new byte[] {1, 2, 3});
            t.in.add(pkt(PacketType.ACCEPT, 5, 0, b -> b.put((byte) 1)));
            t.in.add(pkt(PacketType.ACCEPT, 5, 0, b -> {
                Msg.Accept a = new Msg.Accept();
                a.nonce = c.connect.nonce + 1;
                a.write(b);
            }));
            t.in.add(pkt(PacketType.SNAPSHOT, 0, 0, b -> b.putInt(1)));
            c.update(MS);
            ConnectionState still = c.state();
            accept(2 * MS);
            assertAll(
                () -> assertEquals(ConnectionState.CONNECTING, still, "garbage, a short ACCEPT, a wrong nonce and other types do not connect"),
                () -> assertEquals(2, c.malformed(), "the bad header and the short ACCEPT are malformed"),
                () -> assertEquals(ConnectionState.JOINING, c.state(), "joining after the right ACCEPT"),
                () -> assertEquals(SESSION, c.session(), "session from the header"),
                () -> assertEquals(7, c.playerId(), "player id"),
                () -> assertTrue(rec.calls.contains("accepted 7"), "listener told"),
                () -> assertEquals("Joining srv:1...", c.status().toString(), "status"));
        }

        @Test
        @DisplayName("WELCOME and the config build the map, then READY is sent and the state is PLAYING")
        void config() {
            playing(0);
            c.flush(MS);
            ByteBuffer p = t.last();
            PacketHeader h = new PacketHeader();
            h.read(p);
            List<Integer> types = new ArrayList<>();
            ReliableChannel rx = new ReliableChannel();
            rx.read(p, (type, b) -> types.add(type));
            assertAll(
                () -> assertEquals(ConnectionState.PLAYING, c.state(), "playing"),
                () -> assertEquals("Fake Arena", rec.map.name(), "map parsed from the config"),
                () -> assertTrue(rec.calls.indexOf("welcome S") < rec.calls.indexOf("config Fake Arena"),
                    "welcome before config"),
                () -> assertEquals(PacketType.RELIABLE, h.type(), "a RELIABLE datagram"),
                () -> assertEquals(SESSION, h.session(), "with the session"),
                () -> assertEquals(List.of(ReliableType.READY), types, "carrying READY"),
                () -> assertEquals("Playing on srv:1", c.status().toString(), "status"));
        }

        @Test
        @DisplayName("a config with a bad CRC closes the connection and says so")
        void badConfig() {
            c.start(0);
            accept(0);
            welcome(blob(), 1);
            reliable(0);
            c.update(MS);
            assertAll(
                () -> assertEquals(ConnectionState.CLOSED, c.state(), "closed"),
                () -> assertEquals("Bad map/config from srv:1: config CRC mismatch", c.status().toString(), "reason"),
                () -> assertEquals(3, t.types().stream().filter(x -> x == PacketType.DISCONNECT).count(),
                    "three DISCONNECTs"));
        }

        @Test
        @DisplayName("REJECT closes with the reason")
        void reject() {
            c.start(0);
            int nonce = c.connect.nonce;
            t.in.add(pkt(PacketType.REJECT, 0, 0, b -> {
                Msg.Reject r = new Msg.Reject();
                r.nonce = nonce + 1;
                r.write(b);
            }));
            t.in.add(pkt(PacketType.REJECT, 0, 0, b -> b.putShort((short) 1)));
            c.update(MS);
            ConnectionState wrongNonce = c.state();
            t.in.add(pkt(PacketType.REJECT, 0, 0, b -> {
                Msg.Reject r = new Msg.Reject();
                r.nonce = nonce;
                r.text.append("full");
                r.write(b);
            }));
            c.update(2 * MS);
            assertAll(
                () -> assertEquals(ConnectionState.CONNECTING, wrongNonce, "another attempt's REJECT is ignored"),
                () -> assertEquals(1, c.malformed(), "a truncated REJECT is malformed"),
                () -> assertEquals(ConnectionState.CLOSED, c.state(), "closed"),
                () -> assertEquals("Rejected by srv:1: full", c.status().toString(), "reason"));
        }
    }

    @Nested
    @DisplayName("while playing")
    class Playing {
        @Test
        @DisplayName("INPUT carries the last 4 inputs and the newest snapshot tick; nothing before PLAYING")
        void input() {
            c.start(0);
            int early = c.sendInput(1, 0, 0);
            int sentEarly = t.out.size();
            accept(0);
            welcome(blob(), 0);
            reliable(0);
            c.update(0);
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(500)));
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(498)));
            c.update(MS);
            for (int i = 1; i <= 5; i++) {
                c.sendInput(i, i * 10, MS);
            }
            ByteBuffer p = t.last();
            PacketHeader h = new PacketHeader();
            h.read(p);
            int latest = p.getInt();
            int ack = p.getInt();
            int n = p.get();
            long k0 = p.getLong();
            short a0 = p.getShort();
            long k1 = p.getLong();
            assertAll(
                () -> assertEquals(0, early, "no input before PLAYING"),
                () -> assertEquals(1, sentEarly, "only the CONNECT went out"),
                () -> assertEquals(PacketType.INPUT, h.type(), "INPUT"),
                () -> assertEquals(5, latest, "latest sequence"),
                () -> assertEquals(500, ack, "the newest snapshot tick, not the late one"),
                () -> assertEquals(4, n, "four redundant entries"),
                () -> assertEquals(5L, k0, "newest keys first"),
                () -> assertEquals(50, a0, "aim of the newest"),
                () -> assertEquals(4L, k1, "then the previous"),
                () -> assertEquals(List.of(500, 498), rec.snapshotTicks, "snapshots delivered in arrival order"),
                () -> assertEquals(2, c.snapshotsReceived(), "counted"));
        }

        @Test
        @DisplayName("snapshots before the map are dropped; short ones are malformed; other sessions ignored")
        void snapshotGating() {
            c.start(0);
            accept(0);
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(10)));
            c.update(MS);
            welcome(blob(), 0);
            reliable(MS);
            c.update(MS);
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.put((byte) 1)));
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION + 1, 0, b -> b.putInt(11)));
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(12)));
            c.update(2 * MS);
            assertAll(
                () -> assertEquals(List.of(12), rec.snapshotTicks, "only the valid snapshot while playing"),
                () -> assertEquals(1, c.malformed(), "the 1-byte body"),
                () -> assertEquals(12, c.latestTick(), "latest tick"));
        }

        @Test
        @DisplayName("PING is answered with an identical PONG; reliable data is acked when idle")
        void pingAndAck() {
            playing(0);
            c.flush(0);
            int before = t.out.size();
            t.in.add(pkt(PacketType.PING, SESSION, 0, b -> b.putInt(42).putLong(777L)));
            c.update(MS);
            ByteBuffer pong = t.last();
            PacketHeader h = new PacketHeader();
            h.read(pong);
            int id = pong.getInt();
            long nanos = pong.getLong();
            Msg.Text m = new Msg.Text();
            m.text.append("hi");
            m.send(server);
            reliable(2 * MS);
            c.update(2 * MS);
            c.flush(2 * MS);
            ByteBuffer ackPkt = t.last();
            int afterAck = t.out.size();
            m.send(server);
            reliable(3 * MS);
            c.update(3 * MS);
            int beforeInput = t.out.size();
            c.sendInput(0, 0, 3 * MS);
            c.flush(3 * MS);
            assertAll(
                () -> assertEquals(before + 2, afterAck, "one PONG then one ACK"),
                () -> assertEquals(PacketType.PONG, h.type(), "PONG"),
                () -> assertEquals(42, id, "same id"),
                () -> assertEquals(777L, nanos, "same time"),
                () -> assertEquals(PacketType.ACK, ackPkt.get(3), "an idle ACK after reliable data"),
                () -> assertEquals(beforeInput + 1, t.out.size(), "with an INPUT in the frame no extra ACK"),
                () -> assertTrue(rec.calls.contains("reliable " + ReliableType.MESSAGE), "MESSAGE forwarded"));
        }

        @Test
        @DisplayName("a server DISCONNECT closes with its text")
        void serverDisconnect() {
            playing(0);
            t.in.add(pkt(PacketType.DISCONNECT, SESSION, 0, b -> {
                Msg.Disconnect d = new Msg.Disconnect();
                d.reason = Msg.Disconnect.REASON_SHUTDOWN;
                d.text.append("bye");
                d.write(b);
            }));
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(1)));
            c.update(MS);
            int sent = t.out.size();
            c.update(20_000 * MS);
            c.flush(20_000 * MS);
            assertAll(
                () -> assertEquals(ConnectionState.CLOSED, c.state(), "closed"),
                () -> assertEquals("Server srv:1 closed the connection: bye", c.status().toString(), "text"),
                () -> assertEquals(List.of(), rec.snapshotTicks, "nothing processed after closing"),
                () -> assertEquals(sent, t.out.size(), "a closed connection sends nothing"));
        }
    }

    @Nested
    @DisplayName("timeouts")
    class Timeouts {
        @Test
        @DisplayName("without auto-reconnect: a silent session closes; an unanswered connect gives up")
        void noReconnect() {
            c.setAutoReconnect(false);
            playing(0);
            c.update(9_999 * MS);
            ConnectionState before = c.state();
            c.update(10_000 * MS);
            String lost = c.status().toString();
            ClientConnection d = new ClientConnection(t, rec, "srv:1", 1);
            d.setAutoReconnect(false);
            d.start(0);
            d.update(10_000 * MS);
            assertAll(
                () -> assertEquals(ConnectionState.PLAYING, before, "still playing at 9.999 s"),
                () -> assertEquals("Connection to srv:1 lost (timeout)", lost, "lost at 10 s"),
                () -> assertEquals(ConnectionState.CLOSED, c.state(), "closed"),
                () -> assertEquals("No answer from srv:1", d.status().toString(), "connect gives up"),
                () -> assertEquals(0, c.reconnects(), "no reconnect"));
        }

        @Test
        @DisplayName("with auto-reconnect an unanswered connect keeps trying with a new nonce")
        void keepTrying() {
            c.start(0);
            int n1 = c.connect.nonce;
            c.update(10_000 * MS);
            assertAll(
                () -> assertEquals(ConnectionState.CONNECTING, c.state(), "still connecting"),
                () -> assertEquals("No answer from srv:1, still trying...", c.status().toString(), "status"),
                () -> assertNotEquals(n1, c.connect.nonce, "new nonce"),
                () -> assertEquals(0, c.reconnects(), "not counted as a reconnect"),
                () -> assertTrue(c.attempts() > 1, "attempts keep counting"));
        }

        @Test
        @DisplayName("received datagrams postpone the timeout")
        void refresh() {
            playing(0);
            t.in.add(pkt(PacketType.ACK, SESSION, 0, b -> { }));
            c.update(9_000 * MS);
            c.update(18_000 * MS);
            ConnectionState mid = c.state();
            c.update(19_000 * MS);
            assertAll(
                () -> assertEquals(ConnectionState.PLAYING, mid, "9 s after the last datagram"),
                () -> assertEquals(ConnectionState.CONNECTING, c.state(), "10 s after it"),
                () -> assertEquals(1, c.reconnects(), "one reconnect"),
                () -> assertTrue(c.packetsReceived() >= 2, "counted"),
                () -> assertTrue(c.packetsSent() > 0, "sent counted"));
        }
    }

    @Test
    @DisplayName("disconnect before a session sends nothing")
    void disconnectEarly() {
        c.start(0);
        c.disconnect(0);
        assertAll(
            () -> assertEquals(List.of(PacketType.CONNECT), t.types(), "only the CONNECT"),
            () -> assertEquals("Disconnected from srv:1", c.status().toString(), "status"),
            () -> assertEquals(ConnectionState.CLOSED, c.state(), "closed"));
    }

    @Nested
    @DisplayName("details")
    class Details {
        @Test
        @DisplayName("DISCONNECT carries reason quit and text; restarting forgets the session")
        void disconnectBodyAndRestart() {
            playing(0);
            c.disconnect(MS);
            ByteBuffer p = t.last();
            new PacketHeader().read(p);
            Msg.Disconnect d = new Msg.Disconnect();
            d.read(p);
            c.disconnect(MS);
            c.start(2 * MS);
            assertAll(
                () -> assertEquals(Msg.Disconnect.REASON_QUIT, d.reason, "reason quit"),
                () -> assertEquals("quit", d.text.toString(), "text quit"),
                () -> assertEquals(0, c.session(), "restart clears the session"),
                () -> assertEquals(Protocol.NO_ID, c.playerId(), "and the player id"),
                () -> assertEquals(0, c.latestTick(), "and the snapshot tick"),
                () -> assertEquals(ConnectionState.CONNECTING, c.state(), "connecting again"),
                () -> assertEquals(1, c.attempts(), "attempts restart"),
                () -> assertNotNull(c.input(), "input history"));
        }

        @Test
        @DisplayName("sendInput returns the sequence; header acks release our reliable messages")
        void sequenceAndAck() {
            playing(0);
            int s1 = c.sendInput(0, 0, MS);
            int s2 = c.sendInput(0, 0, MS);
            c.flush(MS);
            int unackedBefore = c.channel().unacked();
            t.in.add(pkt(PacketType.ACK, SESSION, 1, b -> { }));
            c.update(200 * MS);
            assertAll(
                () -> assertEquals(1, s1, "first sequence"),
                () -> assertEquals(2, s2, "second"),
                () -> assertEquals(1, unackedBefore, "READY in flight"),
                () -> assertEquals(0, c.channel().unacked(), "acked by the header"));
        }

        @Test
        @DisplayName("at most 8 RELIABLE datagrams per flush")
        void flushLimit() {
            playing(0);
            for (int i = 0; i < 12; i++) {
                ByteBuffer b = c.channel().begin(ReliableType.CHAT);
                b.put(new byte[1000]);
                c.channel().end();
            }
            int before = t.out.size();
            c.flush(MS);
            assertEquals(ClientConnection.MAX_RELIABLE_PER_FLUSH, t.out.size() - before, "8 datagrams");
        }

        @Test
        @DisplayName("an empty DISCONNECT text gives a short status; a REJECT and ACCEPT count as received")
        void misc() {
            playing(0);
            long received = c.packetsReceived();
            t.in.add(pkt(PacketType.DISCONNECT, SESSION, 0, b -> new Msg.Disconnect().write(b)));
            c.update(MS);
            assertAll(
                () -> assertEquals("Server srv:1 closed the connection", c.status().toString(), "no colon"),
                () -> assertTrue(received >= 2, "ACCEPT and RELIABLE counted"),
                () -> assertEquals(received + 1, c.packetsReceived(), "DISCONNECT counted"));
        }

        @Test
        @DisplayName("a newer snapshot tick replaces the latest; equal or older does not")
        void ticks() {
            playing(0);
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(-5)));
            c.update(MS);
            int first = c.latestTick();
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(-5)));
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(-4)));
            c.update(MS);
            assertAll(
                () -> assertEquals(-5, first, "the first tick is taken even if negative"),
                () -> assertEquals(-4, c.latestTick(), "newer replaces"));
        }

        @Test
        @DisplayName("the connect status counts attempts without accumulating text")
        void connectStatus() {
            c.start(0);
            c.update(250 * MS);
            c.update(500 * MS);
            assertEquals("Connecting to srv:1 (attempt 3)...", c.status().toString(), "third attempt");
        }

        @Test
        @DisplayName("a WELCOME for an empty config completes at once and CHUNKs after PLAYING are ignored")
        void emptyAndLateChunks() {
            c.start(0);
            accept(0);
            Msg.Welcome w = new Msg.Welcome();
            w.send(server);
            ByteBuffer chunk = server.begin(ReliableType.DATA_CHUNK);
            chunk.putInt(0);
            server.end();
            reliable(0);
            c.update(MS);
            assertAll(
                () -> assertEquals(ConnectionState.CLOSED, c.state(), "an empty config is a bad config"),
                () -> assertTrue(c.status().toString().startsWith("Bad map/config from srv:1"), "reason"));
        }
    }
}
