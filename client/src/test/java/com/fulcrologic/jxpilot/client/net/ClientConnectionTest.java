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
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
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

    /** A decoded INPUT datagram (v2): entry i is for tick newest - i. */
    record Input(int type, int newest, List<Long> keys, List<Long> taps, List<Integer> aims) {
        static Input of(ByteBuffer p) {
            PacketHeader h = new PacketHeader();
            h.read(p);
            int newest = p.getInt();
            int n = p.get();
            List<Long> keys = new ArrayList<>();
            List<Long> taps = new ArrayList<>();
            List<Integer> aims = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                keys.add(p.getLong());
                taps.add(p.getLong());
                aims.add((int) p.getShort());
            }
            return new Input(h.type(), newest, keys, taps, aims);
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

    /** A snapshot with a full v2 header: tick, the applied inputTick, inputSlack (1/16 tick) and flags. */
    static byte[] snapshot(int tick, int inputTick, int slack16, int flags) {
        return pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(tick).putInt(inputTick).put((byte) slack16)
            .put((byte) 0).put((byte) 1).put((byte) flags).putShort((short) 0).putShort((short) 0));
    }

    static byte[] snapshot(int tick, int inputTick) {
        return snapshot(tick, inputTick, SnapshotWriter.NO_SLACK, SnapshotWriter.FLAG_LAST_PART);
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

    /** The newest PING sent, positioned after its header. */
    ByteBuffer lastPing() {
        for (int i = t.out.size() - 1; i >= 0; i--) {
            ByteBuffer b = t.out.get(i).duplicate();
            PacketHeader h = new PacketHeader();
            if (h.read(b) && h.type() == PacketType.PING) {
                return b;
            }
        }
        throw new AssertionError("no PING sent");
    }

    long pings() {
        return t.types().stream().filter(x -> x == PacketType.PING).count();
    }

    /** First snapshot tick of {@link #ticking}. */
    static final int T0 = 500;
    /** The first entry's tick: T0 plus the initial lead without an RTT, ceil(30 ms / (1/60 s)) + 1 = 3. */
    static final int E0 = T0 + 3;

    /** PLAYING with the predicted clock started: snapshot T0 at time 0 (60 Hz, no RTT yet). */
    void ticking() {
        playing(0);
        t.in.add(snapshot(T0, 0));
        c.update(0);
    }

    /** Time k ticks after the first snapshot, 1 ms into the tick (1e9/60 is not a whole number of nanoseconds). */
    static long at(int k) {
        return k * INTERVAL + MS;
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
        @DisplayName("INPUT: one entry per predicted tick, newest first, from the first snapshot on; nothing before")
        void input() {
            c.start(0);
            int early = c.sendInput(1, 0, 0);
            int sentEarly = t.out.size();
            accept(0);
            welcome(blob(), 0);
            reliable(0);
            c.update(0);
            int noClock = c.sendInput(1, 0, 0);
            t.in.add(snapshot(T0, 0));
            t.in.add(snapshot(T0 - 2, 0));
            c.update(0);
            int[] ticks = new int[5];
            for (int i = 0; i < 5; i++) {
                ticks[i] = c.sendInput(i + 1, (i + 1) * 10, at(i));
            }
            Input in = Input.of(t.last());
            assertAll(
                () -> assertEquals(0, early, "no input before PLAYING"),
                () -> assertEquals(1, sentEarly, "only the CONNECT went out"),
                () -> assertEquals(0, noClock, "no input before a snapshot starts the predicted clock"),
                () -> assertArrayEquals(new int[] {E0, E0 + 1, E0 + 2, E0 + 3, E0 + 4}, ticks,
                    "one entry per tick, starting at the initial lead (3 ticks) over the snapshot tick"),
                () -> assertEquals(PacketType.INPUT, in.type, "INPUT"),
                () -> assertEquals(E0 + 4, in.newest, "the newest tick"),
                () -> assertEquals(List.of(5L, 4L, 3L, 2L, 1L), in.keys, "every entry: the server reported none applied"),
                () -> assertEquals(List.of(0L, 0L, 0L, 0L, 0L), in.taps, "no taps"),
                () -> assertEquals(List.of(50, 40, 30, 20, 10), in.aims, "each entry's aim"),
                () -> assertEquals(List.of(T0, T0 - 2), rec.snapshotTicks, "snapshots delivered in arrival order"),
                () -> assertEquals(2, c.snapshotsReceived(), "counted"),
                () -> assertEquals(3, c.netDiagnostics().leadTicks, 0.1, "the HUD's lead: 3 ticks"));
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
            t.in.add(snapshot(T0, 0));
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
        @DisplayName("our PING goes out on the first PLAYING flush and every second; a matching PONG is an RTT sample")
        void rttProbe() {
            playing(0);
            c.flush(0);
            ByteBuffer p = lastPing();
            int id = p.getInt();
            long sentAt = p.getLong();
            c.flush(999 * MS);
            int pings = (int) pings();
            t.in.add(pkt(PacketType.PONG, SESSION, 0, b -> b.putInt(id).putLong(sentAt)));
            c.update(180 * MS);
            double rtt = c.netDiagnostics().rttMs();
            c.flush(1000 * MS);
            int two = (int) pings();
            assertAll(
                () -> assertEquals(1, pings, "a PING on the first flush (the input lead needs the RTT), one by 999 ms"),
                () -> assertEquals(0L, sentAt, "carrying its send time"),
                () -> assertEquals(180, rtt, 1e-9, "the PONG 180 ms later is a 180 ms RTT"),
                () -> assertTrue(c.predictedClock().haveRtt(), "the predicted clock has its RTT"),
                () -> assertEquals(2, two, "the next at 1 s"));
        }

        @Test
        @DisplayName("a PONG that returns after newer PINGs went out still counts (RTT over 1 s); a duplicate does not")
        void slowPong() {
            playing(0);
            c.flush(0);
            c.flush(1000 * MS);
            ByteBuffer p = lastPing();
            int id = p.getInt();
            long sentAt = p.getLong();
            c.flush(2000 * MS);
            c.flush(3000 * MS); // two newer PINGs out
            t.in.add(pkt(PacketType.PONG, SESSION, 0, b -> b.putInt(id).putLong(sentAt)));
            c.update(3500 * MS);
            double rtt = c.netDiagnostics().rttMs();
            t.in.add(pkt(PacketType.PONG, SESSION, 0, b -> b.putInt(id).putLong(sentAt)));
            c.update(3600 * MS);
            assertAll(
                () -> assertEquals(2500, rtt, 1e-9, "the first PING's PONG after 2.5 s"),
                () -> assertEquals(2500, c.netDiagnostics().lastRttMs(), 1e-9, "the duplicate is no second sample"));
        }

        @Test
        @DisplayName("an older snapshot tick is late, not lost; another part of the newest tick is neither")
        void lateSnapshots() {
            playing(0);
            // WELCOME: 60 Hz, 30 snapshots/s: a snapshot every 2 ticks, the loss window 28 snapshots = 56 ticks
            for (int k = 2; k <= 56; k += 2) {
                if (k != 20) {
                    int tk = k;
                    t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(tk)));
                }
            }
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(56))); // another part of 56
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(20))); // late
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(20))); // its second part: one late tick
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(58))); // closes the window
            c.update(MS);
            assertAll(
                () -> assertEquals(100 / 28.0, c.netDiagnostics().latePercent(), 1e-9, "tick 20 was late"),
                () -> assertEquals(0, c.netDiagnostics().lossPercent(), 1e-9, "every snapshot arrived; the extra part counts for nothing"));
        }

        @Test
        @DisplayName("late frames keep the PING schedule's phase; after a stall the schedule restarts without a burst")
        void probeSchedule() {
            playing(0);
            c.flush(0);
            c.flush(1004 * MS); // due at 1000, sent 4 ms late
            c.flush(1999 * MS);
            long beforePhase = pings();
            c.flush(2000 * MS); // the next is still due at 2000, not 2004
            long atPhase = pings();
            c.flush(7300 * MS); // a long stall: one PING, then due 1 s later
            long afterStall = pings();
            c.flush(7310 * MS);
            long noBurst = pings();
            c.flush(8299 * MS);
            long early = pings();
            c.flush(8300 * MS);
            assertAll(
                () -> assertEquals(2, beforePhase, "the first at 0, the second at 1004 ms, none more by 1999 ms"),
                () -> assertEquals(3, atPhase, "the third at 2000 ms: the phase is kept"),
                () -> assertEquals(4, afterStall, "one PING after the stall"),
                () -> assertEquals(4, noBurst, "no catch-up burst"),
                () -> assertEquals(4, early, "none before 8300 ms"),
                () -> assertEquals(5, pings(), "the next 1 s after the stall's PING"));
        }

        @Test
        @DisplayName("a flush exactly one interval late sends one PING and the next a full interval later")
        void probeExactlyLate() {
            playing(0);
            c.flush(0);
            c.flush(2000 * MS); // due at 1000, exactly one interval late
            long one = pings();
            c.flush(2008 * MS);
            long stillOne = pings();
            c.flush(3000 * MS);
            assertAll(
                () -> assertEquals(2, one, "the first at 0, one at 2000 ms"),
                () -> assertEquals(2, stillOne, "not again on the next frame"),
                () -> assertEquals(3, pings(), "the next at 3000 ms"));
        }

        @Test
        @DisplayName("a PONG of an older PING, a forged time or a short body is no RTT sample (short: malformed)")
        void stalePong() {
            playing(0);
            c.flush(0);
            c.flush(1000 * MS);
            ByteBuffer p = t.last();
            new PacketHeader().read(p);
            int id = p.getInt();
            long sentAt = p.getLong();
            t.in.add(pkt(PacketType.PONG, SESSION, 0, b -> b.putInt(id - 1).putLong(sentAt)));
            t.in.add(pkt(PacketType.PONG, SESSION, 0, b -> b.putInt(id).putLong(sentAt - 5 * MS)));
            t.in.add(pkt(PacketType.PONG, SESSION, 0, b -> b.putInt(id)));
            long bad = c.malformed();
            c.update(1100 * MS);
            assertAll(
                () -> assertTrue(Double.isNaN(c.netDiagnostics().rttMs()), "no sample from an older id or another time"),
                () -> assertEquals(bad + 1, c.malformed(), "the 4-byte body is malformed"));
        }

        @Test
        @DisplayName("snapshots feed the diagnostics; WELCOME sets their tick length")
        void snapshotDiagnostics() {
            playing(0);
            NetDiagnostics d = c.netDiagnostics();
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(10)));
            c.update(100 * MS);
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, b -> b.putInt(11)));
            c.update(100 * MS + 1_000_000_000L / 60 + 4 * MS);
            // tolerance: 1e9/60 is not a whole number of nanoseconds
            assertEquals(4, d.jitterMs(), 1e-5, "one tick (1/60 s, the WELCOME's simHz) late by 4 ms: jitter 4 ms");
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
        @DisplayName("sendInput returns the entry's tick (0 when nothing went out); header acks release our reliable messages")
        void sequenceAndAck() {
            ticking();
            int s1 = c.sendInput(0, 0, at(0));
            int none = c.sendInput(0, 0, at(0) + MS);
            int s2 = c.sendInput(1, 0, at(1));
            c.flush(MS);
            int unackedBefore = c.channel().unacked();
            t.in.add(pkt(PacketType.ACK, SESSION, 7, b -> { }));
            c.update(200 * MS);
            assertAll(
                () -> assertEquals(E0, s1, "the first entry's tick"),
                () -> assertEquals(0, none, "same tick, same keys: nothing"),
                () -> assertEquals(E0 + 1, s2, "the next tick"),
                () -> assertEquals(7, unackedBefore, "SETTINGS, DISPLAY, 4 MODIFIER_BANKs and READY in flight"),
                () -> assertEquals(0, c.channel().unacked(), "acked by the header"));
        }

        @Test
        @DisplayName("at most 8 RELIABLE datagrams per flush")
        void flushLimit() {
            playing(0);
            c.flush(0); // the first PLAYING flush also sends the first PING
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
    @DisplayName("INPUT per predicted tick")
    class InputTicks {
        @Test
        @DisplayName("entry N is final when the clock reaches N; a change within the tick sends an early copy of N+1")
        void finalAndEarly() {
            ticking();
            int fin0 = c.sendInput(0, 0, at(0));
            int inGapAfterFinal = c.sendInput(4, 0, at(0) + 3 * MS);
            int early = c.sendInput(4, 0, at(0) + 10 * MS);
            Input earlyIn = Input.of(t.last());
            int releasedInGap = c.sendInput(0, 0, at(0) + 12 * MS);
            int fin1 = c.sendInput(0, 0, at(1));
            Input fin1In = Input.of(t.last());
            int pressAgain = c.sendInput(8, 0, at(1) + 9 * MS);
            Input againIn = Input.of(t.last());
            c.sendInput(8, 0, at(2));
            c.sendInput(0, 0, at(2) + 9 * MS);
            int fin3 = c.sendInput(0, 0, at(3));
            Input fin3In = Input.of(t.last());
            assertAll(
                () -> assertEquals(E0, fin0, "entry E0 at its tick"),
                () -> assertEquals(0, inGapAfterFinal, "a press 3 ms after a send waits for the 1/120 s ceiling"),
                () -> assertEquals(E0 + 1, early, "then it sends the next entry early"),
                () -> assertEquals(4L, (long) earlyIn.keys.get(0), "with the key held so far"),
                () -> assertEquals(E0 + 1, earlyIn.newest, "as entry E0+1"),
                () -> assertEquals(0, releasedInGap, "the release 2 ms later waits"),
                () -> assertEquals(E0 + 1, fin1, "the final E0+1 at its tick"),
                () -> assertEquals(0L, (long) fin1In.keys.get(0), "keys up"),
                () -> assertEquals(4L, (long) fin1In.taps.get(0), "the press and release within the tick are a tap"),
                () -> assertEquals(E0 + 2, pressAgain, "an early copy of E0+2, not a final: E0+1 stays the last final"),
                () -> assertEquals(8L, (long) againIn.keys.get(0), "with 8"),
                () -> assertEquals(E0 + 3, fin3, "E0+3"),
                () -> assertEquals(0L, (long) fin3In.taps.get(0),
                    "a key pressed in an earlier tick and released in this one is a release, not a tap"),
                () -> assertEquals(8L, (long) fin3In.keys.get(1), "E0+2 held 8"));
        }

        @Test
        @DisplayName("steady keys send two INPUTs per tick at any frame rate (the final and a mid-tick repeat);"
            + " toggling keys at most 3 per tick")
        void rate() {
            ticking();
            int steady = 0;
            for (int f = 0; f < 1000; f++) {
                if (c.sendInput(7, 0, f * MS) != 0) {
                    steady++;
                }
            }
            int toggling = 0;
            for (int f = 1000; f < 2000; f++) {
                if (c.sendInput(f & 1, 0, f * MS) != 0) {
                    toggling++;
                }
            }
            int fSteady = steady;
            int fToggling = toggling;
            assertAll(
                () -> assertEquals(120, fSteady, 1, "steady keys at 1000 fps: the final and a repeat per tick (60 Hz)"),
                () -> assertTrue(fToggling <= 180, "toggling: the final per tick plus early copies 1/120 s apart ("
                    + fToggling + ")"),
                () -> assertTrue(fToggling >= 120, "early copies do go out (" + fToggling + ")"),
                () -> assertEquals(fSteady + fToggling, inputs().size(), "one INPUT datagram per send"));
        }

        @Test
        @DisplayName("taps: a press and release on consecutive frames; early copies carry the taps so far; the gap and"
            + " repeat edges are inclusive")
        void tapsAndEdges() {
            ticking();
            c.sendInput(0, 0, at(0));
            c.sendInput(4, 0, at(0) + 9 * MS);
            c.sendInput(0, 0, at(0) + 12 * MS);
            int fin = c.sendInput(0, 0, at(1));
            Input tapped = Input.of(t.last());
            c.sendInput(2, 0, at(1) + 3 * MS);
            c.sendInput(0, 0, at(1) + 5 * MS);
            int early = c.sendInput(8, 0, at(1) + ClientConnection.MIN_INPUT_GAP_NANOS);
            Input earlyIn = Input.of(t.last());
            c.sendInput(8, 0, at(2));
            int repeat = c.sendInput(8, 0, at(2) + INTERVAL / 2);
            assertAll(
                () -> assertEquals(E0 + 1, fin, "the final entry"),
                () -> assertEquals(4L, (long) tapped.taps.get(0), "a key down one frame and up the next is a tap"),
                () -> assertEquals(E0 + 2, early, "an early copy exactly 1/120 s after the final send"),
                () -> assertEquals(2L, (long) earlyIn.taps.get(0), "it carries the tap of 2 so far"),
                () -> assertEquals(8L, (long) earlyIn.keys.get(0), "and the held 8"),
                () -> assertEquals(E0 + 2, repeat, "a repeat exactly half a tick after the last send"));
        }

        @Test
        @DisplayName("PLAYER_JOIN of the own id gives the kernel its hull; a restart forgets the PREDICT state")
        void kernelHooks() {
            ticking();
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = 7;
            j.flags = Msg.PlayerJoin.FLAG_SELF;
            j.shape.append("(SH: 15,0 -8,8 -8,-8)(EN: -8,0)(MG: 15,0)");
            j.send(server);
            com.fulcrologic.jxpilot.common.sim.ShipBody b = new com.fulcrologic.jxpilot.common.sim.ShipBody();
            t.in.add(pkt(PacketType.SNAPSHOT, SESSION, 0, p -> p.put(KernelSyncTest.predict(T0 + 1, 0, b, 0, 0, true))));
            reliable(at(1));
            c.update(at(1));
            boolean hull = c.kernel().hull() != com.fulcrologic.jxpilot.common.sim.ShipGeom.defaultShip();
            boolean had = c.kernel().have();
            c.start(at(2));
            assertAll(
                () -> assertTrue(hull, "the own join's shape is the kernel's hull"),
                () -> assertTrue(had, "the PREDICT was read"),
                () -> assertFalse(c.kernel().have(), "a restart forgets it"),
                () -> assertTrue(c.kernel().hull() == com.fulcrologic.jxpilot.common.sim.ShipGeom.defaultShip(),
                    "and the hull"));
        }

        @Test
        @DisplayName("half a tick after the last send, unchanged keys repeat the INPUT (a second copy before the deadline)")
        void repeat() {
            ticking();
            c.sendInput(3, 0, at(0));
            int early = c.sendInput(3, 0, at(0) + 7 * MS);
            int half = c.sendInput(3, 0, at(0) + 9 * MS);
            Input in = Input.of(t.last());
            int again = c.sendInput(3, 0, at(0) + 15 * MS);
            assertAll(
                () -> assertEquals(0, early, "nothing before half a tick (8.3 ms)"),
                () -> assertEquals(E0, half, "the repeat carries the newest entry"),
                () -> assertEquals(E0, in.newest, "for E0"),
                () -> assertEquals(3L, (long) in.keys.get(0), "unchanged"),
                () -> assertEquals(0, again, "not again within the half tick"),
                () -> assertEquals(2, inputs().size(), "two datagrams"));
        }

        @Test
        @DisplayName("aim is summed per entry (early copies carry the sum so far) and saturates at the i16 range")
        void aim() {
            ticking();
            c.sendInput(1, 0, at(0));
            c.sendInput(1, 30_000, at(0) + MS);
            c.sendInput(2, 30_000, at(0) + 9 * MS);
            Input early = Input.of(t.last());
            c.sendInput(2, 5, at(0) + 10 * MS);
            c.sendInput(2, 0, at(1));
            Input fin = Input.of(t.last());
            c.sendInput(2, -40_000, at(1) + MS);
            c.sendInput(2, 0, at(2));
            Input next = Input.of(t.last());
            assertAll(
                () -> assertEquals(E0 + 1, early.newest, "an early copy of E0+1"),
                () -> assertEquals(32767, (int) early.aims.get(0), "60000 so far, clamped"),
                () -> assertEquals(32767, (int) fin.aims.get(0), "the final: still clamped"),
                () -> assertEquals(-32768, (int) next.aims.get(0), "the next entry starts from 0: -40000 clamped"),
                () -> assertEquals(32767, (int) next.aims.get(1), "the previous entry unchanged"));
        }

        @Test
        @DisplayName("redundancy: every entry newer than the newest snapshot's inputTick, at least 1, at most 8")
        void redundancy() {
            ticking();
            for (int i = 0; i < 12; i++) {
                c.sendInput(i, 0, at(i));
            }
            int capped = inputs().get(11).keys.size();
            t.in.add(snapshot(T0 + 1, E0 + 9));
            c.update(at(12));
            c.sendInput(12, 0, at(12));
            Input afterAck = inputs().get(12);
            int acked = c.ackedInputTick();
            t.in.add(snapshot(T0 + 2, E0 + 99));
            t.in.add(snapshot(T0 + 1, E0 + 12));
            c.update(at(13));
            c.sendInput(13, 0, at(13));
            Input ignored = inputs().get(13);
            t.in.add(snapshot(T0 + 3, E0 + 13));
            c.update(at(14));
            c.sendInput(14, 0, at(14));
            int allApplied = inputs().get(14).keys.size();
            assertAll(
                () -> assertEquals(8, capped, "12 unacknowledged entries: capped at 8"),
                () -> assertEquals(3, afterAck.keys.size(), "E0+10..E0+12 are newer than the applied E0+9"),
                () -> assertEquals(E0 + 9, acked, "the applied tick is kept"),
                () -> assertEquals(4, ignored.keys.size(),
                    "an inputTick beyond the newest entry is ignored, and so is an older snapshot's header"),
                () -> assertEquals(1, allApplied, "everything applied: only the newest"));
        }

        @Test
        @DisplayName("a new session starts with a fresh history: one entry, not the old session's backlog")
        void newSession() {
            ticking();
            for (int i = 0; i < 10; i++) {
                c.sendInput(i, 0, at(i));
            }
            c.start(at(20));
            accept(at(20));
            welcome(blob(), 0);
            reliable(at(20));
            c.update(at(20));
            t.in.add(snapshot(10, 0));
            c.update(at(20));
            int tick = c.sendInput(1, 0, at(20));
            Input first = inputs().get(inputs().size() - 1);
            assertAll(
                () -> assertEquals(10 + 3, tick, "the new session's clock: snapshot 10 plus the initial lead"),
                () -> assertEquals(1, first.keys.size(), "one entry"),
                () -> assertEquals(13, first.newest, "for tick 13, though the old session was past 500"));
        }

        @Test
        @DisplayName("after a stall the entry is for the tick reached; the skipped ticks hold the previous keys")
        void stall() {
            ticking();
            c.sendInput(5, 0, at(0));
            int after = c.sendInput(6, 0, at(4));
            Input in = Input.of(t.last());
            int nextFrame = c.sendInput(6, 0, at(4) + 2 * MS);
            assertAll(
                () -> assertEquals(E0 + 4, after, "the tick reached"),
                () -> assertEquals(List.of(6L, 5L, 5L, 5L, 5L), in.keys, "the skipped ticks hold 5"),
                () -> assertEquals(0, nextFrame, "no burst after the stall"));
        }

        @Test
        @DisplayName("header feedback: a starvation flag jumps the clock a tick; the slack reaches the HUD; the RTT sets the lead")
        void feedback() {
            ticking();
            c.sendInput(0, 0, at(0));
            t.in.add(snapshot(T0 + 1, 0, 8, SnapshotWriter.FLAG_LAST_PART | SnapshotWriter.FLAG_INPUT_STARVED));
            c.update(at(1));
            int jumped = c.sendInput(0, 0, at(1));
            double slack = c.netDiagnostics().inputSlackTicks;
            c.flush(at(1));
            ByteBuffer p = lastPing();
            int id = p.getInt();
            long sentAt = p.getLong();
            t.in.add(pkt(PacketType.PONG, SESSION, 0, b -> b.putInt(id).putLong(sentAt)));
            c.update(at(1) + 100 * MS);
            int withRtt = c.sendInput(0, 0, at(1) + 100 * MS);
            double lead = c.netDiagnostics().leadTicks;
            assertAll(
                () -> assertEquals(E0 + 2, jumped, "one tick later and one tick up"),
                () -> assertEquals(1, c.predictedClock().jumps(), "one jump"),
                () -> assertEquals(0.5, slack, 1e-9, "inputSlack 8/16 tick"),
                () -> assertEquals(T0 + 7 + 3 + 6, withRtt,
                    "100 ms RTT: the server's present (snapshot + 7 ticks + RTT/2 = 3 ticks) + ceil((50+30)/16.7)+1 = 6"),
                () -> assertEquals(6, lead, 0.1, "the HUD's lead over the server's present"));
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
            ticking();
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
            ticking();
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
            ticking();
            t.failSend = new IOException("no route to host");
            long sent = c.packetsSent();
            int seq = c.sendInput(1, 0, MS);
            int paused = c.sendInput(2, 0, MS + FRAME);
            t.failSend = null;
            t.in.add(pkt(PacketType.ACK, SESSION, 0, b -> { }));
            c.update(MS + 2 * FRAME);
            int afterValid = c.sendInput(3, 0, MS + 2 * FRAME);
            assertAll(
                () -> assertEquals(E0, seq, "the attempt recorded its entry"),
                () -> assertEquals(sent, c.packetsSent() - 1, "only the later INPUT counts as sent"),
                () -> assertEquals(1, c.ioErrors(), "the failure counted"),
                () -> assertEquals(0, paused, "the next frame is inside the pause"),
                () -> assertEquals(E0 + 2, afterValid, "a valid datagram from the server ends the pause at once"));
        }
    }
}
