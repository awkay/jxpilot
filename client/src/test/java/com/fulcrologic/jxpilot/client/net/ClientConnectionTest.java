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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ClientConnectionTest {
    static final long MS = 1_000_000L;
    static final long FRAME = 1_000_000_000L / 60;
    static final long INTERVAL = 1_000_000_000L / 60;
    static final int SESSION = 0x2345;

    /** A decoded INPUT datagram. */
    record Input(int type, int latest, int ackTick, List<Long> keys, List<Integer> aims) {
        static Input of(ByteBuffer p) {
            PacketHeader h = new PacketHeader();
            h.read(p);
            int latest = p.getInt();
            int ack = p.getInt();
            int n = p.get();
            List<Long> keys = new ArrayList<>();
            List<Integer> aims = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                keys.add(p.getLong());
                aims.add((int) p.getShort());
            }
            return new Input(h.type(), latest, ack, keys, aims);
        }
    }

    /** INPUT datagrams sent so far. */
    List<Input> inputs() {
        List<Input> r = new ArrayList<>();
        for (ByteBuffer b : t.out) {
            if ((b.get(3) & 0xFF) == PacketType.INPUT) {
                r.add(Input.of(b.duplicate()));
            }
        }
        return r;
    }

    /** A snapshot body: tick and the echoed lastInputSeq. */
    static byte[] snapshot(int tick, int lastInputSeq) {
        return pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(tick).putInt(lastInputSeq));
    }

    /** Transport fake: scripted incoming datagrams, recorded outgoing ones. */
    static final class Scripted implements Transport {
        final ArrayDeque<byte[]> in = new ArrayDeque<>();
        final List<ByteBuffer> out = new ArrayList<>();
        /** When set, send/receive throw it (network down). */
        IOException failSend;
        IOException failReceive;
        /** Server port closed: every datagram sent comes back as one ICMP error on a later receive. */
        boolean portClosed;
        int pendingIcmp;
        int sendAttempts;
        int receiveAttempts;

        @Override
        public boolean send(ByteBuffer packet) throws IOException {
            sendAttempts++;
            if (failSend != null) {
                throw failSend;
            }
            if (portClosed) {
                pendingIcmp++;
            }
            ByteBuffer c = ByteBuffer.allocate(packet.remaining());
            c.put(packet).flip();
            out.add(c);
            return true;
        }

        @Override
        public int receive(ByteBuffer buf) throws IOException {
            receiveAttempts++;
            if (failReceive != null) {
                throw failReceive;
            }
            if (pendingIcmp > 0) {
                pendingIcmp--;
                throw new java.net.PortUnreachableException("ICMP port unreachable");
            }
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
    static class Rec implements ConnectionListener {
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
        @DisplayName("CONNECT resend timing is relative to the last send, also when time does not start at 0")
        void connectResendLaterStart() {
            c.start(5_000 * MS);
            c.update(5_249 * MS);
            int early = t.out.size();
            c.update(5_250 * MS);
            assertAll(
                () -> assertEquals(1, early, "not resent 249 ms after the first"),
                () -> assertEquals(2, t.out.size(), "resent at 250 ms"));
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
        @DisplayName("every ACCEPT of the session (the first and repeats while joining) is answered with a bare ACK"
            + " carrying the session id: the server's proof that the client receives at its address")
        void acceptIsAnswered() {
            c.start(0);
            int sentBefore = t.out.size();
            accept(MS);
            List<ByteBuffer> first = new ArrayList<>(t.out.subList(sentBefore, t.out.size()));
            int afterFirst = t.out.size();
            accept(2 * MS);
            List<ByteBuffer> repeat = new ArrayList<>(t.out.subList(afterFirst, t.out.size()));
            assertAll(
                () -> assertEquals(1, first.size(), "one datagram right after the ACCEPT"),
                () -> assertEquals(PacketType.ACK, first.get(0).get(3) & 0xFF, "a bare ACK"),
                () -> assertEquals(PacketHeader.SIZE, first.get(0).remaining(), "header only"),
                () -> assertEquals(SESSION, first.get(0).getShort(4) & 0xFFFF, "with the new session id"),
                () -> assertEquals(1, repeat.size(), "a repeated ACCEPT is answered again"),
                () -> assertEquals(PacketType.ACK, repeat.get(0).get(3) & 0xFF, "with another bare ACK"));
        }

        @Test
        @DisplayName("WELCOME and the config build the map, then SETTINGS, DISPLAY, the 4 MODIFIER_BANKs and READY are sent and the state is PLAYING")
        void config() {
            c.modifierBanks[0] = "FN C";
            c.modifierBanks[3] = "X4 LS";
            c.settings.turnSpeed = 40f;
            c.settings.power = 50f;
            c.display.viewWidth = 1100;
            c.display.viewHeight = 700;
            playing(0);
            c.flush(MS);
            ByteBuffer p = t.last();
            PacketHeader h = new PacketHeader();
            h.read(p);
            List<Integer> types = new ArrayList<>();
            Msg.Settings sent = new Msg.Settings();
            Msg.Display display = new Msg.Display();
            Msg.ModifierBank bank = new Msg.ModifierBank();
            List<String> banks = new ArrayList<>();
            ReliableChannel rx = new ReliableChannel();
            rx.read(p, (type, b) -> {
                types.add(type);
                if (type == ReliableType.SETTINGS) {
                    sent.read(b);
                } else if (type == ReliableType.DISPLAY) {
                    display.read(b);
                } else if (type == ReliableType.MODIFIER_BANK) {
                    bank.read(b);
                    banks.add(bank.bank + ":" + bank.text);
                }
            });
            assertAll(
                () -> assertEquals(ConnectionState.PLAYING, c.state(), "playing"),
                () -> assertEquals("Fake Arena", rec.map.name(), "map parsed from the config"),
                () -> assertTrue(rec.calls.contains("welcome S"), "WELCOME reported"),
                () -> assertTrue(rec.calls.indexOf("welcome S") < rec.calls.indexOf("config Fake Arena"),
                    "welcome before config"),
                () -> assertEquals(PacketType.RELIABLE, h.type(), "a RELIABLE datagram"),
                () -> assertEquals(SESSION, h.session(), "with the session"),
                () -> assertEquals(List.of(ReliableType.SETTINGS, ReliableType.DISPLAY, ReliableType.MODIFIER_BANK,
                        ReliableType.MODIFIER_BANK, ReliableType.MODIFIER_BANK, ReliableType.MODIFIER_BANK, ReliableType.READY),
                    types, "carrying the control settings, the view size and the modifier banks, then READY (C Net_start: "
                        + "PKT_PLAY ... PKT_DISPLAY ... PKT_MODIFIERBANK x 4)"),
                () -> assertEquals(List.of("0:FN C", "1:", "2:", "3:X4 LS"), banks,
                    "banks 0..3 in order with the connection's texts, empty ones too (C sends all NUM_MODBANKS)"),
                () -> assertEquals(1100, display.viewWidth, "the connection's view width"),
                () -> assertEquals(700, display.viewHeight, "and view height"),
                () -> assertEquals(40f, sent.turnSpeed, "the connection's turn speed"),
                () -> assertEquals(50f, sent.power, "and power"),
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
        @DisplayName("server-commands: REJECT QUEUED keeps waiting (same nonce, CONNECT every second) and shows the position;"
            + " the ACCEPT that follows ends the queue")
        void queued() {
            c.start(0);
            int nonce = c.connect.nonce;
            t.in.add(pkt(PacketType.REJECT, 0, 0, b -> {
                Msg.Reject r = new Msg.Reject();
                r.nonce = nonce;
                r.reason = Msg.Reject.REASON_QUEUED;
                r.position = 2;
                r.text.append("queued at position 2");
                r.write(b);
            }));
            c.update(MS);
            int sent = t.out.size();
            c.update(500 * MS);
            int at500 = t.out.size();
            c.update(1002 * MS);
            int at1002 = t.out.size();
            String status = c.status().toString();
            int pos = c.queuePosition();
            ConnectionState waiting = c.state();
            t.in.add(pkt(PacketType.ACCEPT, 77, 0, b -> {
                Msg.Accept a = new Msg.Accept();
                a.nonce = nonce;
                a.playerId = 4;
                a.write(b);
            }));
            c.update(1100 * MS);
            assertAll(
                () -> assertEquals(ConnectionState.CONNECTING, waiting, "still connecting"),
                () -> assertEquals("Waiting in queue on srv:1 (position 2)", status, "the position"),
                () -> assertEquals(2, pos, "queuePosition"),
                () -> assertEquals(sent, at500, "no CONNECT at the usual 250 ms while queued"),
                () -> assertEquals(sent + 1, at1002, "one a second later"),
                () -> assertEquals(nonce, c.connect.nonce, "the same nonce"),
                () -> assertEquals(ConnectionState.JOINING, c.state(), "accepted"),
                () -> assertEquals(0, c.queuePosition(), "no longer queued"));
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
        @DisplayName("INPUT carries the unacknowledged inputs and the newest snapshot tick; nothing before PLAYING")
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
                c.sendInput(i, i * 10, i * FRAME);
            }
            Input in = Input.of(t.last());
            assertAll(
                () -> assertEquals(0, early, "no input before PLAYING"),
                () -> assertEquals(1, sentEarly, "only the CONNECT went out"),
                () -> assertEquals(PacketType.INPUT, in.type, "INPUT"),
                () -> assertEquals(5, in.latest, "latest sequence"),
                () -> assertEquals(500, in.ackTick, "the newest snapshot tick, not the late one"),
                () -> assertEquals(5, in.keys.size(), "all five inputs: the server echoed none yet"),
                () -> assertEquals(List.of(5L, 4L, 3L, 2L, 1L), in.keys, "newest keys first"),
                () -> assertEquals(List.of(50, 40, 30, 20, 10), in.aims, "aims of the entries"),
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
        @DisplayName("sendInput returns the sequence (0 when nothing went out); header acks release our reliable messages")
        void sequenceAndAck() {
            playing(0);
            int s1 = c.sendInput(0, 0, MS);
            int s2 = c.sendInput(1, 0, MS + FRAME);
            c.flush(MS);
            int unackedBefore = c.channel().unacked();
            t.in.add(pkt(PacketType.ACK, SESSION, 7, b -> { }));
            c.update(200 * MS);
            assertAll(
                () -> assertEquals(1, s1, "first sequence"),
                () -> assertEquals(2, s2, "second"),
                () -> assertEquals(7, unackedBefore, "SETTINGS, DISPLAY, 4 MODIFIER_BANKs and READY in flight"),
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

    @Test
    @DisplayName("an empty config fails at WELCOME without any chunk; a chunk outside the announced size is a bad config")
    void configEdges() {
        c.start(0);
        accept(0);
        new Msg.Welcome().send(server);
        reliable(0);
        c.update(MS);
        ConnectionState emptyState = c.state();
        Scripted tt = new Scripted();
        ClientConnection cc = new ClientConnection(tt, new Rec(), "srv:1", 99);
        ReliableChannel srv = new ReliableChannel();
        cc.start(0);
        int nonce = cc.connect.nonce;
        tt.in.add(pkt(PacketType.ACCEPT, SESSION, 0, b -> {
            Msg.Accept a = new Msg.Accept();
            a.nonce = nonce;
            a.playerId = 7;
            a.write(b);
        }));
        cc.update(0);
        Msg.Welcome w = new Msg.Welcome();
        w.configLength = 10;
        w.configCompressedLength = 4;
        w.send(srv);
        ByteBuffer chunk = srv.begin(ReliableType.DATA_CHUNK);
        chunk.putInt(2).put(new byte[8]);
        srv.end();
        tt.in.add(pkt(PacketType.RELIABLE, SESSION, 0, b -> srv.write(b, 0)));
        cc.update(MS);
        assertAll(
            () -> assertEquals(ConnectionState.CLOSED, emptyState, "empty config: closed at WELCOME"),
            () -> assertEquals(ConnectionState.CLOSED, cc.state(), "chunk out of range: closed"),
            () -> assertEquals("Bad map/config from srv:1: DATA_CHUNK out of range", cc.status().toString(), "reason"));
    }

    @Test
    @DisplayName("a RELIABLE body with bad framing counts as malformed")
    void badReliableFraming() {
        playing(0);
        long bad = c.malformed();
        t.in.add(pkt(PacketType.RELIABLE, SESSION, 0, b -> b.put((byte) 1)));
        c.update(MS);
        assertEquals(bad + 1, c.malformed(), "count 1 but no message header");
    }

    @Nested
    @DisplayName("INPUT rate")
    class InputRate {
        @Test
        @DisplayName("at 144 fps unchanged keys still average simHz: the send schedule keeps its phase")
        void phaseKept() {
            playing(0);
            long frame = 1_000_000_000L / 144;
            int sent = 0;
            for (int f = 0; f < 1440; f++) {
                if (c.sendInput(3, 0, 100 * MS + f * frame) != 0) {
                    sent++;
                }
            }
            int fSent = sent;
            assertTrue(fSent >= 598 && fSent <= 601, "about 600 in 10 s (not 1 per 3 frames = 480): " + fSent);
        }

        @Test
        @DisplayName("a 1000 fps render loop sends at most 120 INPUTs per second; steady keys at simHz")
        void thousandFps() {
            playing(0);
            int steady = 0;
            for (long f = 1; f <= 1000; f++) {
                if (c.sendInput(7, 0, f * MS) != 0) {
                    steady++;
                }
            }
            int toggling = 0;
            for (long f = 1001; f <= 2000; f++) {
                if (c.sendInput(f & 1, 0, f * MS) != 0) {
                    toggling++;
                }
            }
            int fSteady = steady;
            int fToggling = toggling;
            assertAll(
                () -> assertTrue(fSteady >= 58 && fSteady <= 61, "unchanged keys: about simHz = 60 per second, got " + fSteady),
                () -> assertTrue(fToggling >= 110 && fToggling <= 120, "keys changing every frame: capped near 120/s, got "
                    + fToggling),
                () -> assertEquals(fSteady + fToggling, c.input().latestSeq(), "a sequence only for each datagram sent"),
                () -> assertEquals(fSteady + fToggling, inputs().size(), "one INPUT datagram per sequence"));
        }

        @Test
        @DisplayName("a key change at 60 fps is sent the same frame; unchanged keys wait for 1/simHz")
        void changeSentAtOnce() {
            playing(0);
            long t0 = 100 * MS;
            int first = c.sendInput(0, 0, t0);
            int sameKeysNextFrame = c.sendInput(0, 0, t0 + INTERVAL - 1);
            int sameKeysDue = c.sendInput(0, 0, t0 + INTERVAL);
            int pressed = c.sendInput(4, 0, t0 + INTERVAL + FRAME);
            assertAll(
                () -> assertEquals(1, first, "the first input goes out at once"),
                () -> assertEquals(0, sameKeysNextFrame, "unchanged keys are not resent before 1/simHz"),
                () -> assertEquals(2, sameKeysDue, "unchanged keys are resent at exactly 1/simHz"),
                () -> assertEquals(3, pressed, "a changed mask goes out in the frame it changed"),
                () -> assertEquals(4L, inputs().get(2).keys.get(0), "with the new mask"));
        }

        @Test
        @DisplayName("a change within 1/120 s is coalesced: sent at the ceiling with the current mask and summed aim")
        void coalesce() {
            playing(0);
            long t0 = 100 * MS;
            c.sendInput(1, 5, t0);
            int tooSoon = c.sendInput(2, 7, t0 + 2 * MS);
            int stillSoon = c.sendInput(3, 11, t0 + ClientConnection.MIN_INPUT_GAP_NANOS - 1);
            int atCeiling = c.sendInput(3, 13, t0 + ClientConnection.MIN_INPUT_GAP_NANOS);
            Input last = inputs().get(inputs().size() - 1);
            assertAll(
                () -> assertEquals(0, tooSoon, "2 ms after a send: held back"),
                () -> assertEquals(0, stillSoon, "just under 1/120 s: held back"),
                () -> assertEquals(2, atCeiling, "1/120 s after the last send: sent"),
                () -> assertEquals(3L, last.keys.get(0), "the coalesced INPUT carries the current mask"),
                () -> assertEquals(7 + 11 + 13, (int) last.aims.get(0), "aim of the held frames is summed"),
                () -> assertEquals(5, (int) last.aims.get(1), "the earlier input keeps its own aim"),
                () -> assertEquals(2, c.input().latestSeq(), "held frames record no sequence"));
        }

        @Test
        @DisplayName("summed aim saturates at the i16 range")
        void aimClamp() {
            playing(0);
            c.sendInput(1, 0, 100 * MS);
            c.sendInput(1, 30_000, 101 * MS);
            c.sendInput(1, 30_000, 102 * MS);
            c.sendInput(1, 0, 100 * MS + INTERVAL);
            c.sendInput(1, -30_000, 100 * MS + INTERVAL + MS);
            c.sendInput(1, -30_000, 100 * MS + INTERVAL + 2 * MS);
            c.sendInput(1, -30_000, 100 * MS + INTERVAL + 3 * MS);
            c.sendInput(1, 0, 100 * MS + 2 * INTERVAL);
            List<Input> in = inputs();
            assertAll(
                () -> assertEquals(32767, (int) in.get(1).aims.get(0), "positive sum clamps to 32767"),
                () -> assertEquals(-32768, (int) in.get(2).aims.get(0), "negative sum clamps to -32768"));
        }

        @Test
        @DisplayName("redundancy: every entry newer than the echoed lastInputSeq, at least 1 and at most 8")
        void redundancyFollowsAck() {
            playing(0);
            for (int i = 1; i <= 12; i++) {
                c.sendInput(i, 0, i * INTERVAL);
            }
            int capped = inputs().get(11).keys.size();
            t.in.add(snapshot(100, 9));
            c.update(13 * INTERVAL);
            c.sendInput(13, 0, 13 * INTERVAL);
            Input afterAck = inputs().get(12);
            t.in.add(snapshot(102, 13));
            c.update(14 * INTERVAL);
            c.sendInput(14, 0, 14 * INTERVAL);
            int allAcked = inputs().get(13).keys.size();
            t.in.add(snapshot(104, 99));
            t.in.add(snapshot(103, 14));
            t.in.add(snapshot(106, 5));
            c.update(15 * INTERVAL);
            int ignored = c.ackedInputSeq();
            assertAll(
                () -> assertEquals(8, capped, "12 unacknowledged inputs: capped at 8"),
                () -> assertEquals(List.of(13L, 12L, 11L, 10L), afterAck.keys, "acked up to 9: entries 10..13"),
                () -> assertEquals(1, allAcked, "everything older acked: only the new entry"),
                () -> assertEquals(13, ignored,
                    "a lastInputSeq ahead of anything sent, from an older tick, or older than the acked one, is ignored"));
        }

        @Test
        @DisplayName("a new session starts with nothing acknowledged: one entry, not the old session's backlog")
        void redundancyResetsWithSession() {
            playing(0);
            for (int i = 1; i <= 12; i++) {
                c.sendInput(i, 0, i * INTERVAL);
            }
            c.start(20 * INTERVAL);
            accept(20 * INTERVAL);
            welcome(blob(), 0);
            reliable(20 * INTERVAL);
            c.update(20 * INTERVAL);
            c.sendInput(1, 0, 21 * INTERVAL);
            Input first = inputs().get(inputs().size() - 1);
            assertAll(
                () -> assertEquals(13, first.latest, "sequences continue across sessions"),
                () -> assertEquals(List.of(1L), first.keys, "only the new session's input"));
        }

        @Test
        @DisplayName("WELCOME's simHz sets the interval; after a long stall the schedule restarts without a burst")
        void intervalAndStall() {
            c.start(0);
            accept(0);
            Msg.Welcome w = new Msg.Welcome();
            w.simHz = 30;
            w.snapshotHz = 15;
            blob().fill(w);
            w.send(server);
            new ConfigTransfer.Sender(blob()).pump(server, 0);
            reliable(0);
            c.update(0);
            long iv = c.inputIntervalNanos();
            long t0 = 100 * MS;
            c.sendInput(0, 0, t0);
            int changeSoon = c.sendInput(5, 0, t0 + ClientConnection.MIN_INPUT_GAP_NANOS);
            int beforeInterval = c.sendInput(5, 0, t0 + iv - 1);
            int atInterval = c.sendInput(5, 0, t0 + iv);
            int afterStall = c.sendInput(5, 0, t0 + 50 * iv + 3);
            int nextFrame = c.sendInput(5, 0, t0 + 50 * iv + 3 + FRAME);
            int nextInterval = c.sendInput(5, 0, t0 + 51 * iv + 3);
            assertAll(
                () -> assertEquals(1_000_000_000L / 30, iv, "1/30 s at simHz 30"),
                () -> assertEquals(2, changeSoon, "a key change 8.3 ms later goes out at once, not after half a tick"),
                () -> assertEquals(0, beforeInterval, "unchanged keys: not before the interval"),
                () -> assertEquals(3, atInterval, "at the interval"),
                () -> assertEquals(4, afterStall, "after a stall: sent at once"),
                () -> assertEquals(0, nextFrame, "no catch-up burst after a stall"),
                () -> assertEquals(5, nextInterval, "one interval after the stall send"));
        }
    }

    @Nested
    @DisplayName("hostile server packets")
    class Hostile {
        @Test
        @DisplayName("PONG echoes only the 12-byte PING body; an oversized PING cannot overflow the send buffer")
        void oversizePing() {
            playing(0);
            byte[] big = new byte[1600];
            ByteBuffer.wrap(big).put(pkt(PacketType.PING, SESSION, 0, b -> b.putInt(42).putLong(777L)));
            t.in.add(big);
            c.update(MS);
            ByteBuffer pong = t.last();
            int size = pong.remaining();
            new PacketHeader().read(pong);
            Msg.Ping p = new Msg.Ping();
            p.read(pong);
            assertAll(
                () -> assertEquals(PacketType.PONG, pong.get(3), "a PONG went out"),
                () -> assertEquals(PacketHeader.SIZE + ClientConnection.PING_SIZE, size, "header plus 12 bytes"),
                () -> assertEquals(42, p.id, "the id echoed"),
                () -> assertEquals(777L, p.timeNanos, "the time echoed"),
                () -> assertEquals(ConnectionState.PLAYING, c.state(), "still playing"));
        }

        @Test
        @DisplayName("a PING shorter than 12 bytes is malformed and answered with nothing")
        void shortPing() {
            playing(0);
            int before = t.out.size();
            long bad = c.malformed();
            t.in.add(pkt(PacketType.PING, SESSION, 0, b -> b.putInt(42).putInt(1)));
            c.update(MS);
            assertAll(
                () -> assertEquals(before, t.out.size(), "no PONG"),
                () -> assertEquals(bad + 1, c.malformed(), "counted as malformed"));
        }

        @Test
        @DisplayName("WELCOME announcing a negative or huge config size closes as a bad config without throwing")
        void hostileWelcome() {
            for (int[] sizes : new int[][] {{10, -5}, {-1, 10}, {10, ConfigTransfer.MAX_LENGTH + 1}}) {
                Scripted tt = new Scripted();
                ClientConnection cc = new ClientConnection(tt, new Rec(), "srv:1", 99);
                ReliableChannel srv = new ReliableChannel();
                cc.start(0);
                int nonce = cc.connect.nonce;
                tt.in.add(pkt(PacketType.ACCEPT, SESSION, 0, b -> {
                    Msg.Accept a = new Msg.Accept();
                    a.nonce = nonce;
                    a.playerId = 7;
                    a.write(b);
                }));
                cc.update(0);
                Msg.Welcome w = new Msg.Welcome();
                w.simHz = 60;
                w.configLength = sizes[0];
                w.configCompressedLength = sizes[1];
                w.send(srv);
                tt.in.add(pkt(PacketType.RELIABLE, SESSION, 0, b -> srv.write(b, 0)));
                assertDoesNotThrow(() -> cc.update(MS), "update never throws");
                assertAll(
                    () -> assertEquals(ConnectionState.CLOSED, cc.state(), "closed for sizes " + sizes[0] + "/" + sizes[1]),
                    () -> assertEquals("Bad map/config from srv:1: config size out of range", cc.status().toString(),
                        "the reason"),
                    () -> assertEquals(3, tt.types().stream().filter(x -> x == PacketType.DISCONNECT).count(),
                        "the server is told three times"));
            }
        }

        @Test
        @DisplayName("a reliable message whose handling throws is counted and skipped; the next one still arrives")
        void poisonReliable() {
            List<String> seen = new ArrayList<>();
            Rec throwing = new Rec() {
                @Override
                public void onReliable(int type, ByteBuffer p) {
                    if (type == ReliableType.BASE_OWNER) {
                        throw new IndexOutOfBoundsException("boom");
                    }
                    seen.add("reliable " + type);
                }
            };
            ClientConnection cc = new ClientConnection(t, throwing, "srv:1", 99);
            cc.start(0);
            int nonce = cc.connect.nonce;
            t.in.add(pkt(PacketType.ACCEPT, SESSION, 0, b -> {
                Msg.Accept a = new Msg.Accept();
                a.nonce = nonce;
                a.playerId = 7;
                a.write(b);
            }));
            cc.update(0);
            welcome(blob(), 0);
            reliable(0);
            cc.update(0);
            long bad = cc.malformed();
            Msg.BaseOwner bo = new Msg.BaseOwner();
            bo.send(server);
            Msg.Text m = new Msg.Text();
            m.text.append("after");
            m.send(server);
            reliable(MS);
            assertDoesNotThrow(() -> cc.update(MS), "update never throws");
            m.send(server);
            reliable(2 * MS);
            cc.update(2 * MS);
            assertAll(
                () -> assertEquals(bad + 1, cc.malformed(), "the throwing message counts as malformed"),
                () -> assertEquals(List.of("reliable " + ReliableType.MESSAGE, "reliable " + ReliableType.MESSAGE), seen,
                    "the channel moved on: both later messages arrived"),
                () -> assertEquals(ConnectionState.PLAYING, cc.state(), "still playing"));
        }

        @Test
        @DisplayName("a snapshot whose decoding throws is counted as malformed and does not escape update")
        void throwingSnapshot() {
            Rec throwing = new Rec() {
                @Override
                public void onSnapshot(ByteBuffer body, long now) {
                    throw new java.nio.BufferUnderflowException();
                }
            };
            ClientConnection cc = new ClientConnection(t, throwing, "srv:1", 99);
            cc.start(0);
            int nonce = cc.connect.nonce;
            t.in.add(pkt(PacketType.ACCEPT, SESSION, 0, b -> {
                Msg.Accept a = new Msg.Accept();
                a.nonce = nonce;
                a.playerId = 7;
                a.write(b);
            }));
            cc.update(0);
            welcome(blob(), 0);
            reliable(0);
            cc.update(0);
            long bad = cc.malformed();
            t.in.add(snapshot(10, 0));
            t.in.add(snapshot(12, 0));
            assertDoesNotThrow(() -> cc.update(MS), "update never throws");
            assertAll(
                () -> assertEquals(bad + 2, cc.malformed(), "both counted"),
                () -> assertEquals(12, cc.latestTick(), "later datagrams of the batch were still read"));
        }

        @Test
        @DisplayName("server text in the status line is cut at 80 chars")
        void longReject() {
            c.start(0);
            int nonce = c.connect.nonce;
            t.in.add(pkt(PacketType.REJECT, 0, 0, b -> {
                Msg.Reject r = new Msg.Reject();
                r.nonce = nonce;
                r.text.append("x".repeat(200));
                r.write(b);
            }));
            c.update(MS);
            assertEquals("Rejected by srv:1: " + "x".repeat(80), c.status().toString(), "80 chars of the reason");
        }
    }

    @Nested
    @DisplayName("transport errors")
    class TransportErrors {
        @Test
        @DisplayName("a receive IOException is 'nothing received': counted, sends pause, the timeout still reconnects")
        void receiveFails() {
            playing(0);
            c.sendInput(0, 0, MS);
            t.failReceive = new IOException("network is unreachable");
            int before = t.out.size();
            assertDoesNotThrow(() -> c.update(10 * MS), "update never throws");
            int paused = c.sendInput(1, 0, 10 * MS);
            c.flush(10 * MS);
            int sentInPause = t.out.size() - before;
            t.failReceive = null;
            int resumed = c.sendInput(1, 0, 10 * MS + ClientConnection.IO_BACKOFF_NANOS);
            long errors = c.ioErrors();
            int fResumed = resumed;
            assertAll(
                () -> assertEquals(1, errors, "one error counted"),
                () -> assertEquals(0, paused, "no INPUT during the pause"),
                () -> assertEquals(0, sentInPause, "nothing at all sent during the pause"),
                () -> assertTrue(fResumed > 0, "INPUT resumes after the pause"));
        }

        @Test
        @DisplayName("server port closed (ICMP per datagram): the pause keeps errors far below one per frame; the timeout reconnects")
        void portUnreachableOutage() {
            playing(0);
            t.portClosed = true;
            for (long ms = 1; ms <= 10_100; ms += 1) {
                c.update(ms * MS);
                c.sendInput(ms / 50 & 1, 0, ms * MS);
                c.flush(ms * MS);
            }
            assertAll(
                () -> assertTrue(c.ioErrors() > 0, "errors are seen"),
                () -> assertTrue(c.ioErrors() <= 2 * 10_100 / 250 + 2,
                    "about one error per send pause (250 ms), not one per 1 ms frame: " + c.ioErrors()),
                () -> assertEquals(ConnectionState.CONNECTING, c.state(), "10 s without data: timed out and reconnecting"),
                () -> assertEquals(1, c.reconnects(), "counted as a reconnect"));
        }

        @Test
        @DisplayName("a send IOException is 'not sent': counted, nothing escapes, and a valid datagram ends the pause")
        void sendFails() {
            playing(0);
            t.failSend = new IOException("no route to host");
            long sent = c.packetsSent();
            int seq = c.sendInput(1, 0, MS);
            int paused = c.sendInput(2, 0, MS + FRAME);
            t.failSend = null;
            t.in.add(pkt(PacketType.ACK, SESSION, 0, b -> { }));
            c.update(MS + 2 * FRAME);
            int afterValid = c.sendInput(3, 0, MS + 2 * FRAME);
            assertAll(
                () -> assertEquals(1, seq, "the attempt recorded a sequence"),
                () -> assertEquals(sent, c.packetsSent() - 1, "only the later INPUT counts as sent"),
                () -> assertEquals(1, c.ioErrors(), "the failure counted"),
                () -> assertEquals(0, paused, "the next frame is inside the pause"),
                () -> assertEquals(2, afterValid, "a valid datagram from the server ends the pause at once"));
        }
    }
}
