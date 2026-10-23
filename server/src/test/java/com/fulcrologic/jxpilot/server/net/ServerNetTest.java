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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.net.InputHistory;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static com.fulcrologic.jxpilot.server.net.Harness.addr;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ServerNet connection layer")
class ServerNetTest {
    static final InetSocketAddress A = addr("10.0.0.1", 4000);
    static final InetSocketAddress B = addr("10.0.0.2", 4000);

    static String randomText(int n, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) (33 + r.nextInt(90)));
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("CONNECT")
    class Connect {
        @Test
        @DisplayName("accepts: ACCEPT then reliable WELCOME, from the host's answers")
        void accept() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.welcomeFlags = Msg.Welcome.FLAG_EDGE_WRAP;
            cfg.gameFps = 50;
            cfg.serverName = "Srv";
            Harness h = new Harness(cfg, "mapName: test\n");
            h.host.nextId = 7;
            h.host.team = 3;
            TestClient c = new TestClient(0xABCD);
            TestClient second = new TestClient(0xBCDE);
            h.in(0, B, second.connect("Bo", 2, ""));
            h.in(0, A, c.connect("Ann", 2, "(SH: 1,0)"));
            h.take(B);
            List<Integer> immediate = h.types(h.sent);
            h.deliver(c, A);
            h.tick();
            h.deliver(c, A);
            assertAll(
                () -> assertEquals(List.of("join Bo team=2 shape=", "join Ann team=2 shape=(SH: 1,0)"), h.host.callsStartingWith("join"),
                    "the host gets the name, team and shape"),
                () -> assertEquals(List.of(PacketType.ACCEPT), immediate, "ACCEPT is sent at once"),
                () -> assertTrue(c.accepted, "the client is accepted (nonce echoed)"),
                () -> assertEquals(8, c.playerId, "ACCEPT carries the host's player id"),
                () -> assertNotEquals(0, c.session, "the session id is non-zero"),
                () -> assertEquals(c.session, h.net.sessionOf(0, A), "the server records that session"),
                () -> assertTrue(c.welcomed, "WELCOME arrives on the reliable channel at the next tick"),
                () -> assertEquals(8, c.welcome.playerId, "WELCOME player id"),
                () -> assertEquals(3, c.welcome.team, "WELCOME team from the host"),
                () -> assertEquals(60, c.welcome.simHz, "WELCOME simHz"),
                () -> assertEquals(30, c.welcome.snapshotHz, "WELCOME snapshotHz = simHz / divisor"),
                () -> assertEquals(Msg.Welcome.FLAG_EDGE_WRAP, c.welcome.flags, "WELCOME flags from the config"),
                () -> assertEquals(50f, c.welcome.gameFps, "WELCOME gameFps"),
                () -> assertEquals(h.blob.compressed.length, c.welcome.configCompressedLength, "WELCOME announces the blob size"),
                () -> assertEquals("Srv", c.welcome.serverName.toString(), "WELCOME server name (not appended to the previous one)"),
                () -> assertEquals("mapName: test\n", c.configText, "the config text arrives intact"),
                () -> assertEquals(2, h.net.connections(), "two connections"),
                () -> assertTrue(h.log.get(1).startsWith("join: player 8 'Ann'"), "the join is logged"));
        }

        @Test
        @DisplayName("a repeated CONNECT is re-ACCEPTed; a new nonce replaces the session")
        void duplicates() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.in(0, A, c.connect("Ann", 255, ""));
            int first = h.net.sessionOf(0, A);
            h.take(A);
            h.in(0, A, c.connect("Ann", 255, ""));
            List<Harness.Sent> again = h.take(A);
            TestClient restarted = new TestClient(2);
            h.in(0, A, restarted.connect("Ann", 255, ""));
            h.deliver(restarted, A);
            assertAll(
                () -> assertEquals(1, again.size(), "a duplicate CONNECT gets one reply"),
                () -> assertEquals(PacketType.ACCEPT, again.get(0).type(), "the reply is ACCEPT"),
                () -> assertEquals(first, again.get(0).session(), "with the same session"),
                () -> assertEquals(List.of("join Ann team=255 shape=", "leave 0", "join Ann team=255 shape="),
                    filter(h.host.calls),
                    "a new nonce from the same address leaves the old player and joins anew"),
                () -> assertNotEquals(first, restarted.session, "the new session differs"),
                () -> assertEquals(1, h.net.connections(), "still one connection"));
        }

        private List<String> filter(List<String> calls) {
            List<String> out = new ArrayList<>();
            for (String s : calls) {
                if (s.startsWith("join") || s.startsWith("leave")) {
                    out.add(s);
                }
            }
            return out;
        }

        @Test
        @DisplayName("rejects empty names, host refusals and a full pool")
        void rejects() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.maxConnections = 1;
            Harness h = new Harness(cfg, "x: 1\n");
            TestClient empty = new TestClient(5);
            h.in(0, A, empty.connect("", 255, ""));
            h.deliver(empty, A);
            h.host.joinAnswer = -Msg.Reject.REASON_VERSION;
            TestClient refused = new TestClient(6);
            h.in(0, A, refused.connect("Bob", 255, ""));
            h.deliver(refused, A);
            h.host.joinAnswer = null;
            TestClient ok = new TestClient(7);
            h.in(0, A, ok.connect("Cy", 255, ""));
            h.deliver(ok, A);
            TestClient full = new TestClient(8);
            h.in(0, B, full.connect("Di", 255, ""));
            h.deliver(full, B);
            assertAll(
                () -> assertEquals(Msg.Reject.REASON_BAD_NAME, empty.rejectReason, "an empty name is refused as a bad name"),
                () -> assertEquals("empty player name", empty.rejectText, "with a reason text"),
                () -> assertEquals(Msg.Reject.REASON_VERSION, refused.rejectReason, "a host refusal passes its reason on"),
                () -> assertEquals("incompatible version", refused.rejectText, "with the reason's text"),
                () -> assertTrue(ok.accepted, "a refusal does not use up the pool"),
                () -> assertEquals(Msg.Reject.REASON_FULL, full.rejectReason, "a full pool refuses"),
                () -> assertEquals(List.of("join Bob team=255 shape=", "join Cy team=255 shape="), h.host.callsStartingWith("join"),
                    "the host is not asked for empty names or when the pool is full"),
                () -> assertFalse(full.accepted, "the refused client is not accepted"));
        }

        @Test
        @DisplayName("session ids count up, wrap at 16 bits and skip 0; reject texts per reason")
        void sessionsAndTexts() {
            ServerNet net = new Harness().net;
            int first = net.nextSession();
            int second = net.nextSession();
            for (int i = 2; i < 0xFFFF; i++) {
                net.nextSession();
            }
            int wrapped = net.nextSession();
            assertAll(
                () -> assertEquals(1, first, "the first session is 1"),
                () -> assertEquals(2, second, "then 2"),
                () -> assertEquals(1, wrapped, "after 65535 it wraps to 1, skipping 0"),
                () -> assertEquals("server is full", ServerNet.rejectText(Msg.Reject.REASON_FULL), "full"),
                () -> assertEquals("name not accepted", ServerNet.rejectText(Msg.Reject.REASON_BAD_NAME), "bad name"),
                () -> assertEquals("join refused", ServerNet.rejectText(Msg.Reject.REASON_OTHER), "other"));
        }
    }

    @Nested
    @DisplayName("routing and malformed input")
    class Routing {
        @Test
        @DisplayName("a peer is (channel, address); replies use the channel it arrived on")
        void pinned() {
            Harness h = new Harness();
            TestClient on0 = new TestClient(1);
            TestClient on3 = new TestClient(2);
            h.in(0, A, on0.connect("a", 255, ""));
            h.in(4, A, on3.connect("b", 255, ""));
            TestClient on9 = new TestClient(3);
            h.in(20, A, on9.connect("c", 255, ""));
            List<Harness.Sent> replies = h.take(A);
            h.tick();
            List<Harness.Sent> later = h.take(A);
            assertAll(
                () -> assertEquals(3, h.net.connections(), "the same address on three channels is three peers"),
                () -> assertEquals(List.of(0, 4, 20), List.of(replies.get(0).channel(), replies.get(1).channel(),
                        replies.get(2).channel()),
                    "each ACCEPT leaves through the contacted channel"),
                () -> assertNotEquals(h.net.sessionOf(0, A), h.net.sessionOf(4, A), "with distinct sessions"),
                () -> assertTrue(later.stream().allMatch(s -> s.session() == h.net.sessionOf(s.channel(), A)),
                    "later traffic stays on each peer's own channel"),
                () -> assertEquals(-1, h.net.sessionOf(1, A), "no peer on other channels"));
        }

        @Test
        @DisplayName("stray, bad and truncated packets are counted and dropped without exceptions")
        void malformed() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.in(0, A, c.connect("a", 255, ""));
            h.deliver(c, A);
            TestClient stranger = new TestClient(9);
            h.in(0, B, stranger.bare(PacketType.ACK));
            TestClient wrongSession = new TestClient(1);
            wrongSession.session = c.session + 1;
            h.in(0, A, wrongSession.bare(PacketType.ACK));
            long strays = h.net.stray();

            ByteBuffer badMagic = ByteBuffer.wrap(new byte[] {0, 0, 1, 8, 0, 0, 0, 0});
            h.in(0, A, badMagic);
            h.in(0, A, c.bare(77));
            ByteBuffer badReliable = c.bare(PacketType.RELIABLE);
            h.in(0, A, withBody(badReliable, new byte[] {1, 0, 0, 0, 9}));
            h.in(0, A, withBody(c.bare(PacketType.INPUT), new byte[] {0, 0, 0, 1}));
            h.in(0, A, withBody(c.bare(PacketType.PING), new byte[] {1, 2, 3}));
            h.in(0, A, withBody(c.bare(PacketType.CONNECT), new byte[] {1, 2}));
            // A CHAT whose str16 length runs past the message: the host's decode underflows.
            h.in(0, A, withBody(c.bare(PacketType.RELIABLE), new byte[] {1, 0, 0, 0, 5, (byte) ReliableType.CHAT, 0, 0, 0, 9}));
            assertAll(
                () -> assertEquals(2, strays, "unknown peers and wrong sessions are stray"),
                () -> assertEquals(7, h.net.malformed(), "bad magic, unknown type, bad RELIABLE, short INPUT, short PING, "
                    + "short CONNECT and an undecodable message are all counted"),
                () -> assertEquals(1, h.net.connections(), "the connection survives"),
                () -> assertEquals(List.of(), h.host.callsStartingWith("input"), "nothing malformed reaches the host as input"));
        }

        private ByteBuffer withBody(ByteBuffer headerOnly, byte[] body) {
            ByteBuffer b = ByteBuffer.allocate(headerOnly.remaining() + body.length);
            b.put(headerOnly).put(body).flip();
            return b;
        }

        @Test
        @DisplayName("source keys: one per IP; unresolved addresses use their hash")
        void sourceKeys() {
            InetSocketAddress u = InetSocketAddress.createUnresolved("h", 1);
            assertAll(
                () -> assertEquals(ServerNet.sourceKey(addr("10.0.0.1", 1)), ServerNet.sourceKey(addr("10.0.0.1", 2)),
                    "ports of one IP share a key"),
                () -> assertNotEquals(ServerNet.sourceKey(addr("10.0.0.1", 1)), ServerNet.sourceKey(addr("10.0.0.2", 1)),
                    "different IPs differ"),
                () -> assertEquals(u.hashCode(), ServerNet.sourceKey(u), "unresolved addresses use their own hash"));
        }

        @Test
        @DisplayName("per-source rate limit drops floods and costly CONNECTs")
        void rateLimit() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.ratePerSecond = 10;
            cfg.rateBurst = 6;
            cfg.connectCost = 3;
            Harness h = new Harness(cfg, "x: 1\n");
            TestClient c = new TestClient(1);
            h.in(0, A, c.connect("a", 255, ""));
            TestClient c2 = new TestClient(2);
            h.in(0, addr("10.0.0.1", 4001), c2.connect("b", 255, ""));
            long limitedConnect = h.net.rateLimited();
            h.deliver(c, A);
            for (int i = 0; i < 5; i++) {
                h.in(0, A, c.ping(i, i));
            }
            List<Harness.Sent> pongs = h.take(A);
            assertAll(
                () -> assertEquals(1, h.net.connections(), "the first CONNECT (1 + 3 tokens) is allowed"),
                () -> assertEquals(1, limitedConnect, "a second session from the same IP is refused for lack of tokens"),
                () -> assertEquals(1, pongs.size(), "with 1 token left only one of five PINGs is answered"),
                () -> assertEquals(5, h.net.rateLimited(), "the other four are counted as rate limited"));
        }
    }

    @Nested
    @DisplayName("map transfer and initial state")
    class Transfer {
        @Test
        @DisplayName("a large config streams in order, then the initial state, then events")
        void stream() {
            String text = randomText(60_000, 3);
            Harness h = new Harness(new ServerNetConfig(), text);
            TestClient c = new TestClient(1);
            h.in(0, A, c.connect("a", 255, ""));
            h.deliver(c, A);
            h.host.pendingChat.add("early");
            int maxReliablePerTick = 0;
            int ticks = 0;
            while (!c.hasMap() && ticks < 300) {
                for (ByteBuffer b : c.frame(h.now, 0)) {
                    h.in(0, A, b);
                }
                h.tick();
                ticks++;
                List<Harness.Sent> l = h.deliver(c, A);
                int rel = 0;
                for (Harness.Sent s : l) {
                    rel += s.type() == PacketType.RELIABLE ? 1 : 0;
                }
                maxReliablePerTick = Math.max(maxReliablePerTick, rel);
            }
            for (int i = 0; i < 5; i++) {
                h.step(c, 0, A, 0);
            }
            int maxRel = maxReliablePerTick;
            assertAll(
                () -> assertTrue(h.blob.compressed.length > 32 * Protocol.DATA_CHUNK_SIZE,
                    "the test blob needs more chunks than the window holds"),
                () -> assertEquals(text, c.configText, "the client reassembles exactly the server's config text"),
                () -> assertEquals(List.of("WELCOME 0", "MAP", "JOIN 0 self", "MESSAGE early"), c.reliableLog,
                    "WELCOME, the map, the initial state, then events, in that order"),
                () -> assertEquals(1, h.host.callsStartingWith("initialState").size(), "the initial state is queued once"),
                () -> assertTrue(maxRel <= h.cfg.maxReliablePacketsPerTick && maxRel > 1,
                    "reliable datagrams per tick are capped (max seen " + maxRel + ")"));
        }
    }

    @Nested
    @DisplayName("input")
    class Input {
        @Test
        @DisplayName("each input reaches the host once, in order, despite redundancy and loss")
        void sequencing() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.join(c, 0, A, "a");
            h.host.calls.clear();
            h.in(0, A, c.input(1));
            h.in(0, A, c.input(2));
            c.input(4);                        // lost
            ByteBuffer late = c.input(8);
            ByteBuffer replay = late.duplicate();
            h.in(0, A, late);
            h.in(0, A, replay);
            h.tick();
            h.tick();
            h.deliver(c, A);
            int seqOffset = c.inputs.latestSeq() - 4;
            assertAll(
                () -> assertEquals(List.of(
                        "input 0 seq=" + (seqOffset + 1) + " keys=1 aim=0",
                        "input 0 seq=" + (seqOffset + 2) + " keys=2 aim=0",
                        "input 0 seq=" + (seqOffset + 3) + " keys=4 aim=0",
                        "input 0 seq=" + (seqOffset + 4) + " keys=8 aim=0"), h.host.callsStartingWith("input"),
                    "redundant copies are skipped, a lost packet is recovered from redundancy, replays are ignored"),
                () -> assertEquals(seqOffset + 4, (int) c.snapshotInputSeqs.get(c.snapshotInputSeqs.size() - 1),
                    "the next snapshot reports the last applied input"));
        }

        @Test
        @DisplayName("sequence comparison survives the 32-bit wrap; snapshots report the last applied input")
        void wrap() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.join(c, 0, A, "a");
            h.host.calls.clear();
            h.in(0, A, rawInput(c, Integer.MAX_VALUE, 5));
            h.in(0, A, rawInput(c, Integer.MIN_VALUE, 6));
            h.in(0, A, rawInput(c, Integer.MAX_VALUE, 7));
            while (h.net.currentTick() % 2 != 1) {
                h.tick();
            }
            h.take(A);
            h.tick();
            h.deliver(c, A);
            assertAll(
                () -> assertEquals(List.of("input 0 seq=" + Integer.MAX_VALUE + " keys=5 aim=0",
                        "input 0 seq=" + Integer.MIN_VALUE + " keys=6 aim=0"), h.host.callsStartingWith("input"),
                    "MIN_VALUE follows MAX_VALUE; the older MAX_VALUE is then stale"),
                () -> assertEquals(Integer.MIN_VALUE, (int) c.snapshotInputSeqs.get(c.snapshotInputSeqs.size() - 1),
                    "the snapshot header carries the last applied sequence"));
        }

        private ByteBuffer rawInput(TestClient c, int seq, long keys) {
            ByteBuffer b = ByteBuffer.allocate(64);
            PacketHeader.write(b, PacketType.INPUT, c.session, c.ch.ack());
            b.putInt(seq).putInt(0).put((byte) 1).putLong(keys).putShort((short) 0);
            return b.flip();
        }

        @Test
        @DisplayName("redundancy entries are applied oldest first")
        void oldestFirst() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.in(0, A, c.connect("a", 255, ""));
            h.deliver(c, A);
            InputHistory hist = new InputHistory();
            hist.record(10, 1);
            hist.record(20, 2);
            hist.record(30, 3);
            ByteBuffer b = ByteBuffer.allocate(128);
            PacketHeader.write(b, PacketType.INPUT, c.session, c.ch.ack());
            hist.write(b, 0, 4);
            h.in(0, A, b.flip());
            assertEquals(List.of("input 0 seq=1 keys=10 aim=1", "input 0 seq=2 keys=20 aim=2", "input 0 seq=3 keys=30 aim=3"),
                h.host.callsStartingWith("input"), "a fresh peer gets all three entries oldest first, with aim turns");
        }
    }

    @Nested
    @DisplayName("snapshots")
    class Snapshots {
        @Test
        @DisplayName("every 2nd tick once synced, radar every 3rd snapshot, host tick numbers")
        void cadence() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.in(0, A, c.connect("a", 255, ""));
            h.deliver(c, A);
            boolean snapshotBeforeSync = false;
            for (int i = 0; i < 4; i++) {
                h.tick();
                snapshotBeforeSync |= h.types(h.deliver(c, A)).contains(PacketType.SNAPSHOT);
            }
            h.join(c, 0, A, "a");
            c.snapshotTicks.clear();
            c.radarSnapshots = 0;
            h.host.calls.clear();
            for (int i = 0; i < 12; i++) {
                h.step(c, 0, A, 0);
            }
            List<String> snaps = h.host.callsStartingWith("snapshot");
            boolean snapBefore = snapshotBeforeSync;
            assertAll(
                () -> assertFalse(snapBefore, "no snapshots before the map and initial state are acknowledged"),
                () -> assertEquals(6, c.snapshotTicks.size(), "12 ticks give 6 snapshots"),
                () -> assertTrue(c.snapshotTicks.stream().allMatch(t -> t % 2 == 0), "snapshots go out on even ticks"),
                () -> assertEquals(h.host.lastTick, h.net.currentTick(), "the host ticks with the server's tick numbers"),
                () -> assertEquals(2, c.radarSnapshots, "radar in every 3rd snapshot"),
                () -> assertEquals(2, snaps.stream().filter(s -> s.endsWith("radar=true")).count(),
                    "the host is asked for radar on those snapshots only"),
                () -> assertEquals(73, c.lastRadarCellX, "radar is quantized against the configured world size (1000/3500*256)"),
                () -> assertEquals(1000f, c.lastSelfX, "the host's records arrive"),
                () -> assertEquals(2000f, c.lastSelfY, "the host's records arrive (y)"));
        }

        @Test
        @DisplayName("radar every radarDivisor-th snapshot also with another snapshot divisor")
        void divisor3() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.snapshotDivisor = 3;
            Harness h = new Harness(cfg, "x: 1\n");
            TestClient c = new TestClient(1);
            h.join(c, 0, A, "a");
            h.host.calls.clear();
            for (int i = 0; i < 18; i++) {
                h.step(c, 0, A, 0);
            }
            List<String> snaps = h.host.callsStartingWith("snapshot");
            assertAll(
                () -> assertEquals(6, snaps.size(), "18 ticks at divisor 3 give 6 snapshots"),
                () -> assertEquals(2, snaps.stream().filter(s -> s.endsWith("radar=true")).count(), "2 of them carry radar"),
                () -> assertEquals(20, c.welcome.snapshotHz, "WELCOME says 20 Hz"));
        }

        @Test
        @DisplayName("multi-part snapshots are numbered, counted and flagged; overlong ones are cut at 4")
        void parts() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.join(c, 0, A, "a");
            h.host.partsNeeded = 3;
            c.snapshotParts.clear();
            h.step(c, 0, A, 0);
            h.step(c, 0, A, 0);
            List<String> three = new ArrayList<>(c.snapshotParts);
            int t3 = h.net.currentTick();
            h.host.partsNeeded = 10;
            c.snapshotParts.clear();
            h.step(c, 0, A, 0);
            h.step(c, 0, A, 0);
            int t4 = h.net.currentTick();
            assertAll(
                () -> assertEquals(List.of(t3 + ":0/3", t3 + ":1/3", t3 + ":2/3L"), three,
                    "three parts, partCount 3, last-part flag only on the last"),
                () -> assertEquals(List.of(t4 + ":0/4", t4 + ":1/4", t4 + ":2/4", t4 + ":3/4L"), c.snapshotParts,
                    "a snapshot needing more is cut at MAX_SNAPSHOT_PARTS"),
                () -> assertEquals(1, h.net.truncatedSnapshots(), "and counted as truncated"));
        }
    }

    @Nested
    @DisplayName("reliable traffic, ping, ack")
    class ReliableTraffic {
        @Test
        @DisplayName("client reliable messages reach the host in order; PING gets PONG; idle ticks ACK")
        void messages() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.join(c, 0, A, "a");
            h.host.calls.clear();
            c.chat("one");
            c.chat("two");
            while (h.net.currentTick() % 2 == 1) {
                h.tick();
            }
            h.take(A);
            for (ByteBuffer b = c.reliable(h.now); b != null; b = c.reliable(h.now)) {
                h.in(0, A, b);
            }
            h.tick();
            List<Integer> oddTick = h.types(h.take(A));
            h.in(0, A, c.ping(5, 123456789L));
            h.deliver(c, A);
            assertAll(
                () -> assertEquals(List.of("msg 0 CHAT one", "msg 0 CHAT two"), h.host.callsStartingWith("msg"),
                    "chat lines arrive in order"),
                () -> assertEquals(List.of(PacketType.ACK), oddTick, "a tick without other traffic acknowledges with ACK"),
                () -> assertEquals(1, c.pongs, "PING is answered"),
                () -> assertEquals(123456789L, c.lastPongNanos, "PONG echoes the timestamp"));
        }

        @Test
        @DisplayName("no ACK when a snapshot carries the ack anyway")
        void ackPiggyback() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.join(c, 0, A, "a");
            c.chat("x");
            while (h.net.currentTick() % 2 == 0) {
                h.tick();
            }
            h.take(A);
            h.in(0, A, c.reliable(h.now));
            int unackedBefore = c.ch.unacked();
            h.tick();
            List<Harness.Sent> l = h.deliver(c, A);
            assertAll(
                () -> assertEquals(1, unackedBefore, "the chat line is outstanding"),
                () -> assertEquals(List.of(PacketType.SNAPSHOT), h.types(l), "only the snapshot goes out"),
                () -> assertEquals(0, c.ch.unacked(), "its header acknowledges the chat line"));
        }
    }

    @Nested
    @DisplayName("leaving")
    class Leaving {
        @Test
        @DisplayName("silence beyond the timeout disconnects; activity keeps the peer")
        void timeout() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.timeoutNanos = 10 * Harness.TICK;
            Harness h = new Harness(cfg, "x: 1\n");
            TestClient c = new TestClient(1);
            h.in(0, A, c.connect("a", 255, ""));
            long t0 = h.now;
            h.now = t0 + cfg.timeoutNanos - Harness.TICK;
            h.tick();
            boolean aliveAtLimit = h.net.connections() == 1;
            h.take(A);
            h.tick();
            List<Harness.Sent> l = h.take(A);
            for (Harness.Sent s : l) {
                c.onPacket(s.data().duplicate(), h.now);
            }
            assertAll(
                () -> assertTrue(aliveAtLimit, "exactly at the timeout the peer is kept"),
                () -> assertEquals(0, h.net.connections(), "one tick later it is dropped"),
                () -> assertEquals(List.of(PacketType.DISCONNECT, PacketType.DISCONNECT, PacketType.DISCONNECT), h.types(l),
                    "DISCONNECT is sent three times"),
                () -> assertEquals(Msg.Disconnect.REASON_TIMEOUT, c.disconnectReason, "with the timeout reason"),
                () -> assertEquals(List.of("leave 0"), h.host.callsStartingWith("leave"), "the host is told once"),
                () -> assertTrue(h.log.get(h.log.size() - 1).contains("timeout"), "the leave is logged with its cause"));
        }

        @Test
        @DisplayName("a client DISCONNECT removes the peer silently; shutdown disconnects everyone")
        void quitAndShutdown() {
            Harness h = new Harness();
            TestClient a = new TestClient(1);
            TestClient b = new TestClient(2);
            TestClient d = new TestClient(3);
            h.in(0, A, a.connect("a", 255, ""));
            h.in(0, B, b.connect("b", 255, ""));
            h.in(1, B, d.connect("d", 255, ""));
            h.deliver(a, A);
            h.deliver(b, B);
            h.sent.clear();
            h.in(0, A, a.disconnect());
            List<Harness.Sent> afterQuit = h.take(A);
            h.in(0, A, a.bare(PacketType.ACK));
            long stray = h.net.stray();
            h.net.shutdown();
            List<Harness.Sent> toB = h.take(B);
            TestClient bView = new TestClient(2);
            bView.onPacket(toB.get(5).data().duplicate(), h.now);
            assertAll(
                () -> assertEquals(List.of(), afterQuit, "nothing is sent back to a quitting client"),
                () -> assertEquals(1, stray, "its later packets are stray"),
                () -> assertEquals(6, toB.size(), "shutdown sends 3 DISCONNECTs to each remaining peer"),
                () -> assertEquals(List.of(0, 1), List.of(toB.get(0).channel(), toB.get(3).channel()),
                    "each through its own channel"),
                () -> assertEquals(List.of("leave 0", "leave 1", "leave 2"), h.host.callsStartingWith("leave"),
                    "every player leaves exactly once"),
                () -> assertEquals(0, h.net.connections(), "no connections remain"),
                () -> assertEquals("shutdown", bView.disconnectText, "the reason text is not appended across peers"));
        }

        @Test
        @DisplayName("a freed connection is reused cleanly")
        void reuse() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.maxConnections = 1;
            Harness h = new Harness(cfg, "mapName: test\n");
            TestClient a = new TestClient(1);
            h.join(a, 0, A, "a");
            h.in(0, A, a.input(3));
            h.in(0, A, a.disconnect());
            TestClient b = new TestClient(2);
            h.host.calls.clear();
            h.join(b, 0, B, "b");
            assertAll(
                () -> assertTrue(b.accepted, "the pool slot is reusable"),
                () -> assertEquals("mapName: test\n", b.configText, "the new peer gets the full config again"),
                () -> assertEquals(List.of("WELCOME 1", "MAP", "JOIN 1 self"), b.reliableLog, "and its own initial state"),
                () -> assertEquals("input 1 seq=1 keys=0 aim=0", h.host.callsStartingWith("input").get(0),
                    "its input sequencing starts fresh (seq 1 is applied after the previous peer's higher sequences)"));
        }
    }
}
