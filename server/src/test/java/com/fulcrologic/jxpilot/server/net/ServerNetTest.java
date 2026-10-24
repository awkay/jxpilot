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
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    static final long MS = 1_000_000L;
    /** ACCEPT on the wire: header, u32 nonce, u16 player id. */
    static final int ACCEPT_BYTES = PacketHeader.SIZE + 6;

    static String randomText(int n, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) (33 + r.nextInt(90)));
        }
        return sb.toString();
    }

    /** The client proves it receives ACCEPT at its address: any packet carrying the session. */
    static void confirm(Harness h, TestClient c, int channel, InetSocketAddress from) {
        h.in(channel, from, c.bare(PacketType.ACK));
    }

    static List<String> joinsAndLeaves(List<String> calls) {
        List<String> out = new ArrayList<>();
        for (String s : calls) {
            if (s.startsWith("join") || s.startsWith("leave")) {
                out.add(s);
            }
        }
        return out;
    }

    static int bytes(List<Harness.Sent> l) {
        int n = 0;
        for (Harness.Sent s : l) {
            n += s.data().remaining();
        }
        return n;
    }

    /** Gap {@code i} of the send times is at least {@code ms} and less than one tick more. */
    static boolean gapOk(List<Long> atMs, int i, long ms) {
        if (atMs.size() <= i) {
            return false;
        }
        long gap = atMs.get(i) - atMs.get(i - 1);
        return gap >= ms && gap <= ms + Harness.TICK / MS + 1; // ms values are floored
    }

    static ByteBuffer withBody(ByteBuffer headerOnly, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(headerOnly.remaining() + body.length);
        b.put(headerOnly).put(body).flip();
        return b;
    }

    @Nested
    @DisplayName("CONNECT and session verification")
    class Connect {
        @Test
        @DisplayName("accepts with ACCEPT only; WELCOME follows once a packet carries the session")
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
            List<Integer> beforeConfirm = h.types(h.take(A));
            boolean verifiedBefore = h.net.isVerified(0, A);
            confirm(h, c, 0, A);
            h.tick();
            h.deliver(c, A);
            assertAll(
                () -> assertEquals(List.of("join Bo team=2 shape=", "join Ann team=2 shape=(SH: 1,0)"), h.host.callsStartingWith("join"),
                    "the host gets the name, team and shape"),
                () -> assertEquals(List.of(PacketType.ACCEPT), immediate, "ACCEPT is sent at once"),
                () -> assertEquals(List.of(), beforeConfirm, "nothing but ACCEPT goes to an unverified session"),
                () -> assertFalse(verifiedBefore, "the session starts unverified"),
                () -> assertTrue(h.net.isVerified(0, A), "a packet with the session id verifies it"),
                () -> assertFalse(h.net.isVerified(0, B), "the other client never confirmed"),
                () -> assertTrue(c.accepted, "the client is accepted (nonce echoed)"),
                () -> assertEquals(8, c.playerId, "ACCEPT carries the host's player id"),
                () -> assertNotEquals(0, c.session, "the session id is non-zero"),
                () -> assertEquals(c.session, h.net.sessionOf(0, A), "the server records that session"),
                () -> assertTrue(c.welcomed, "WELCOME arrives on the reliable channel after verification"),
                () -> assertEquals(8, c.welcome.playerId, "WELCOME player id"),
                () -> assertEquals(3, c.welcome.team, "WELCOME team from the host"),
                () -> assertEquals(14, c.welcome.simHz, "WELCOME simHz: the tick (frame) rate"),
                () -> assertEquals(14, c.welcome.snapshotHz, "WELCOME snapshotHz = simHz / divisor (1 by default)"),
                () -> assertEquals(Msg.Welcome.FLAG_EDGE_WRAP, c.welcome.flags, "WELCOME flags from the config"),
                () -> assertEquals(50f, c.welcome.gameFps, "WELCOME gameFps"),
                () -> assertEquals(h.blob.compressed.length, c.welcome.configCompressedLength, "WELCOME announces the blob size"),
                () -> assertEquals("Srv", c.welcome.serverName.toString(), "WELCOME server name (not appended to the previous one)"),
                () -> assertEquals("mapName: test\n", c.configText, "the config text arrives intact"),
                () -> assertEquals(2, h.net.connections(), "two connections"),
                () -> assertEquals(List.of("join: player 8 'Ann' from " + A + " on channel 0"), h.log,
                    "only the verified join is logged"));
            h.in(0, B, second.connect("Bo", 2, ""));
            h.deliver(second, B);
            confirm(h, second, 0, B);
            h.tick();
            h.deliver(second, B);
            assertEquals("Srv", second.welcome.serverName.toString(), "a second WELCOME's server name is not appended");
        }

        @Test
        @DisplayName("a spoofed CONNECT gets 4 ACCEPTs with doubling gaps, no WELCOME or map, then expires")
        void spoofedConnect() {
            Harness h = new Harness(new ServerNetConfig(), randomText(40_000, 7));
            TestClient spoof = new TestClient(1);
            ByteBuffer conn = spoof.connect("spoof", 255, "");
            int inBytes = conn.remaining();
            long t0 = h.now;
            h.in(0, A, conn);
            List<Long> acceptAtMs = new ArrayList<>();
            List<Integer> types = new ArrayList<>();
            int outBytes = 0;
            boolean aliveAt3700 = false;
            long goneAt = -1;
            for (int i = 0; i < 300; i++) {
                for (Harness.Sent s : h.take(A)) {
                    types.add(s.type());
                    outBytes += s.data().remaining();
                    acceptAtMs.add((h.now - t0) / MS);
                }
                if (h.now - t0 <= 3700 * MS) {
                    aliveAt3700 = h.net.connections() == 1;
                }
                h.tick();
                if (goneAt < 0 && h.net.connections() == 0) {
                    goneAt = (h.now - t0) / MS;
                }
            }
            long gone = goneAt;
            int total = outBytes;
            boolean alive = aliveAt3700;
            assertAll(
                () -> assertEquals(List.of(PacketType.ACCEPT, PacketType.ACCEPT, PacketType.ACCEPT, PacketType.ACCEPT), types,
                    "only ACCEPT, four times (1 + 3 resends), and never DISCONNECT"),
                () -> assertEquals(0L, acceptAtMs.get(0), "the first ACCEPT answers the CONNECT at once"),
                () -> assertTrue(gapOk(acceptAtMs, 1, 250), "the first resend comes 250 ms later (to the next tick): " + acceptAtMs),
                () -> assertTrue(gapOk(acceptAtMs, 2, 500), "the second 500 ms after that: " + acceptAtMs),
                () -> assertTrue(gapOk(acceptAtMs, 3, 1000), "the third 1000 ms after that: " + acceptAtMs),
                () -> assertEquals(4 * ACCEPT_BYTES, total, "the whole reply is four 14-byte ACCEPTs"),
                () -> assertTrue(ACCEPT_BYTES <= inBytes, "each ACCEPT is no larger than the CONNECT"),
                () -> assertTrue(alive, "the unverified session lives until 3.75 s"),
                () -> assertTrue(gone >= 3750 && gone < 3750 + 4 * Harness.TICK / MS,
                    "then it expires, 2 s after the last ACCEPT (" + gone + " ms)"),
                () -> assertEquals(List.of("join spoof team=255 shape=", "leave 0"), joinsAndLeaves(h.host.calls),
                    "the host is told about the join and the leave"),
                () -> assertEquals(List.of(), h.log, "unverified sessions are not logged (a spoofed flood cannot spam the log)"));
        }

        @Test
        @DisplayName("ACCEPT resends fall due exactly at their time: ticks 4, 12, 28 for a 4-tick interval; expiry at 60")
        void acceptScheduleExact() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.acceptResendNanos = 4 * Harness.TICK;
            Harness h = new Harness(cfg, "x: 1\n");
            h.in(0, A, new TestClient(1).connect("a", 255, ""));
            List<Integer> acceptTicks = new ArrayList<>();
            int expiredAt = -1;
            for (int i = 0; i <= 70; i++) {
                if (!h.take(A).isEmpty()) {
                    acceptTicks.add(i);
                }
                if (expiredAt < 0 && h.net.connections() == 0) {
                    expiredAt = i;
                }
                h.tick();
            }
            int expired = expiredAt;
            assertAll(
                () -> assertEquals(List.of(0, 4, 12, 28), acceptTicks, "resent exactly when due, the interval doubling"),
                () -> assertEquals(60, expired, "dropped at tick 60, exactly one doubled interval after the last ACCEPT"));
        }

        @Test
        @DisplayName("a duplicate CONNECT on an unverified session re-sends ACCEPT but does not extend its life")
        void duplicateUnverified() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            long t0 = h.now;
            h.in(0, A, c.connect("Ann", 255, ""));
            h.take(A);
            int dupReplies = 0;
            long goneAt = -1;
            for (int i = 0; i < 300 && goneAt < 0; i++) {
                if (i % 4 == 0) {
                    h.in(0, A, c.connect("Ann", 255, ""));
                    dupReplies += h.take(A).size();
                }
                h.tick();
                h.take(A);
                if (h.net.connections() == 0) {
                    goneAt = (h.now - t0) / MS;
                }
            }
            int replies = dupReplies;
            long gone = goneAt;
            assertAll(
                () -> assertTrue(replies >= 12, "every duplicate CONNECT is answered with one ACCEPT (" + replies + ")"),
                () -> assertTrue(gone >= 3750 && gone < 3750 + 4 * Harness.TICK / MS, "it still expires at 3.75 s (" + gone + " ms)"),
                () -> assertEquals(List.of("join Ann team=255 shape=", "leave 0"), joinsAndLeaves(h.host.calls),
                    "one join; duplicates never join again"));
        }

        @Test
        @DisplayName("only the right session id verifies; a verified session is not resent ACCEPTs")
        void verification() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.in(0, A, c.connect("Ann", 255, ""));
            h.deliver(c, A);
            TestClient wrong = new TestClient(1);
            wrong.session = c.session ^ 0x5555;
            h.in(0, A, wrong.bare(PacketType.ACK));
            boolean afterWrong = h.net.isVerified(0, A);
            h.in(0, B, c.bare(PacketType.ACK));
            boolean afterOtherAddress = h.net.isVerified(0, A);
            confirm(h, c, 0, A);
            boolean afterRight = h.net.isVerified(0, A);
            List<Integer> later = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                h.tick();
                later.addAll(h.types(h.take(A)));
            }
            assertAll(
                () -> assertFalse(afterWrong, "a wrong session id does not verify"),
                () -> assertFalse(afterOtherAddress, "the right id from another address does not verify"),
                () -> assertEquals(2, h.net.stray(), "both are stray"),
                () -> assertTrue(afterRight, "the session id from the client's address verifies"),
                () -> assertFalse(later.contains(PacketType.ACCEPT), "a verified session gets no more ACCEPTs"),
                () -> assertTrue(later.contains(PacketType.RELIABLE), "it gets the reliable stream instead"));
        }

        @Test
        @DisplayName("a new nonce replaces the session after the new join; a refused new join keeps the old one")
        void reconnect() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.maxConnections = 1;
            Harness h = new Harness(cfg, "x: 1\n");
            TestClient c = new TestClient(1);
            h.join(c, 0, A, "Ann");
            int first = h.net.sessionOf(0, A);
            h.take(A);
            h.in(0, A, c.connect("Ann", 255, ""));
            List<Harness.Sent> again = h.take(A);
            h.host.joinAnswer = -Msg.Reject.REASON_OTHER;
            TestClient refused = new TestClient(2);
            h.in(0, A, refused.connect("Ann", 255, ""));
            h.deliver(refused, A);
            int afterRefusal = h.net.sessionOf(0, A);
            h.host.joinAnswer = null;
            TestClient restarted = new TestClient(3);
            h.in(0, A, restarted.connect("Ann", 255, ""));
            h.deliver(restarted, A);
            assertAll(
                () -> assertEquals(1, again.size(), "a duplicate CONNECT gets one reply"),
                () -> assertEquals(PacketType.ACCEPT, again.get(0).type(), "the reply is ACCEPT"),
                () -> assertEquals(first, again.get(0).session(), "with the same session"),
                () -> assertEquals(Msg.Reject.REASON_OTHER, refused.rejectReason, "the host refused the new nonce"),
                () -> assertEquals(first, afterRefusal, "so the old session stays"),
                () -> assertEquals(List.of("join Ann team=255 shape=", "join Ann team=255 shape=", "join Ann team=255 shape=",
                        "leave 0"), joinsAndLeaves(h.host.calls),
                    "the old player leaves only after the new join succeeded (pool of 1 reused)"),
                () -> assertTrue(restarted.accepted, "the restarted client is accepted"),
                () -> assertNotEquals(first, restarted.session, "with a new session"),
                () -> assertEquals(1, h.net.connections(), "still one connection"));
        }

        @Test
        @DisplayName("rejects empty names, host refusals and a full pool, from session 0, never larger than the CONNECT")
        void rejects() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.maxConnections = 1;
            Harness h = new Harness(cfg, "x: 1\n");
            TestClient empty = new TestClient(5);
            ByteBuffer packed = empty.connect("", 255, "");
            ByteBuffer emptyConnect = ByteBuffer.allocate(packed.remaining() + 9);
            emptyConnect.position(9);
            emptyConnect.put(packed).flip().position(9);
            int emptySize = emptyConnect.remaining(); // the datagram starts at offset 9 of its buffer
            h.in(0, A, emptyConnect);
            List<Harness.Sent> emptyReply = h.take(A);
            empty.onPacket(emptyReply.get(0).data().duplicate(), h.now);
            h.host.joinAnswer = -Msg.Reject.REASON_VERSION;
            TestClient refused = new TestClient(6);
            h.in(0, A, refused.connect("Bob", 255, "a long shape string pads the request"));
            h.deliver(refused, A);
            h.host.joinAnswer = null;
            TestClient ok = new TestClient(7);
            h.in(0, A, ok.connect("Cy", 255, ""));
            h.deliver(ok, A);
            TestClient full = new TestClient(8);
            h.in(0, B, full.connect("Di", 255, "padding padding padding"));
            List<Harness.Sent> fullReply = h.take(B);
            full.onPacket(fullReply.get(0).data().duplicate(), h.now);
            assertAll(
                () -> assertEquals(Msg.Reject.REASON_BAD_NAME, empty.rejectReason, "an empty name is refused as a bad name"),
                () -> assertEquals("em", empty.rejectText, "its text is cut so the 16-byte REJECT fits the 16-byte CONNECT"),
                () -> assertEquals(emptySize, emptyReply.get(0).data().remaining(), "the reply is exactly as large as the request"),
                () -> assertEquals(0, emptyReply.get(0).session(), "REJECT carries session 0"),
                () -> assertEquals(5, empty.rejectNonce, "REJECT echoes the nonce"),
                () -> assertEquals(Msg.Reject.REASON_VERSION, refused.rejectReason, "a host refusal passes its reason on"),
                () -> assertEquals("incompatible version", refused.rejectText, "with the reason's text"),
                () -> assertTrue(ok.accepted, "a refusal does not use up the pool"),
                () -> assertEquals(Msg.Reject.REASON_FULL, full.rejectReason, "a full pool refuses"),
                () -> assertEquals("server is full", full.rejectText, "with its text when it fits"),
                () -> assertEquals(0, fullReply.get(0).session(), "from session 0"),
                () -> assertEquals(List.of("join Bob team=255 shape=a long shape string pads the request", "join Cy team=255 shape="),
                    h.host.callsStartingWith("join"), "the host is not asked for empty names or when the pool is full"),
                () -> assertFalse(full.accepted, "the refused client is not accepted"),
                () -> assertEquals("server is full", ServerNet.rejectText(Msg.Reject.REASON_FULL), "text for full"),
                () -> assertEquals("name not accepted", ServerNet.rejectText(Msg.Reject.REASON_BAD_NAME), "text for bad name"),
                () -> assertEquals("join refused", ServerNet.rejectText(Msg.Reject.REASON_OTHER), "text for other"));
        }

        @Test
        @DisplayName("a CONNECT of another protocol version gets REJECT VERSION; other bad headers are malformed")
        void otherVersion() {
            Harness h = new Harness();
            ByteBuffer v2 = new TestClient(0x01020304).connect("Future", 255, "x".repeat(30));
            v2.put(2, (byte) 2);
            h.in(0, A, v2);
            List<Harness.Sent> reply = h.take(A);
            TestClient view = new TestClient(0x01020304);
            view.onPacket(reply.get(0).data().duplicate(), h.now);
            ByteBuffer tiny = ByteBuffer.wrap(new byte[] {0x4A, 0x58, 9, PacketType.CONNECT, 0, 0, 0, 0, 0, 0, 0, 0, 0});
            h.in(0, A, tiny);
            List<Harness.Sent> tinyReply = h.take(A);
            ByteBuffer headerOnly = ByteBuffer.wrap(new byte[] {0x4A, 0x58, 9, PacketType.CONNECT, 0, 0, 0, 0});
            h.in(0, A, headerOnly);
            ByteBuffer offset = ByteBuffer.allocate(40);
            offset.position(5);
            offset.put(new byte[] {0x4A, 0x58, 9, PacketType.CONNECT, 0, 0, 0, 0, 0x11, 0x22, 0x33, 0x44, 1, 0});
            offset.flip().position(5);
            h.in(0, A, offset);
            List<Harness.Sent> offsetReply = h.take(A);
            TestClient offsetView = new TestClient(0x11223344);
            offsetView.onPacket(offsetReply.get(0).data().duplicate(), h.now);
            ByteBuffer v2Input = new TestClient(1).bare(PacketType.INPUT);
            v2Input.put(2, (byte) 2);
            h.in(0, A, v2Input);
            ByteBuffer badMagic = new TestClient(1).connect("x", 255, "");
            badMagic.put(0, (byte) 0);
            h.in(0, A, badMagic);
            List<Harness.Sent> silent = h.take(A);
            assertAll(
                () -> assertEquals(1, reply.size(), "one reply"),
                () -> assertEquals(Msg.Reject.REASON_VERSION, view.rejectReason, "REJECT VERSION"),
                () -> assertEquals(0x01020304, view.rejectNonce, "echoing the CONNECT's nonce"),
                () -> assertEquals("incompatible version", view.rejectText, "with its text"),
                () -> assertEquals(List.of(), tinyReply, "a 13-byte one is not answered: even a bare REJECT (14) would be larger"),
                () -> assertEquals(4, h.net.versionRejects(), "all four, down to a bare 8-byte header, are version rejects"),
                () -> assertEquals(14, offsetReply.get(0).data().remaining(), "a 14-byte one at buffer offset 5 gets a bare 14-byte REJECT"),
                () -> assertEquals(0x11223344, offsetView.rejectNonce, "with the nonce read relative to the datagram start"),
                () -> assertEquals("", offsetView.rejectText, "and no room for text"),
                () -> assertEquals(List.of(), silent, "an other-version INPUT and a bad magic get no reply"),
                () -> assertEquals(2, h.net.malformed(), "and are malformed"),
                () -> assertEquals(0, h.net.connections(), "no session is created"),
                () -> assertTrue(ServerNet.isOtherVersionConnect(v2.rewind()), "the detector accepts the other-version CONNECT"),
                () -> assertFalse(ServerNet.isOtherVersionConnect(ByteBuffer.wrap(new byte[] {0x4A, 0x58, 2, PacketType.CONNECT, 0, 0, 0})),
                    "but not one shorter than a header"),
                () -> assertFalse(ServerNet.isOtherVersionConnect(new TestClient(1).connect("x", 255, "")),
                    "nor a CONNECT of our own version"));
        }

        @Test
        @DisplayName("at most maxPerIp sessions per source (IPv6: per /64); the next gets REJECT FULL")
        void perSourceCap() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.maxPerIp = 2;
            Harness h = new Harness(cfg, "x: 1\n");
            List<TestClient> mine = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                TestClient c = new TestClient(10 + i);
                h.in(0, addr("10.0.0.9", 5000 + i), c.connect("n" + i, 255, "(padding so the full REJECT text fits)"));
                h.deliver(c, addr("10.0.0.9", 5000 + i));
                mine.add(c);
            }
            TestClient replace = new TestClient(99);
            h.in(0, addr("10.0.0.9", 5000), replace.connect("n0", 255, ""));
            h.deliver(replace, addr("10.0.0.9", 5000));
            TestClient other = new TestClient(20);
            h.in(0, B, other.connect("b", 255, ""));
            h.deliver(other, B);
            TestClient v6a = new TestClient(30);
            TestClient v6b = new TestClient(31);
            TestClient v6c = new TestClient(32);
            TestClient v6other = new TestClient(33);
            h.in(0, addr("2001:db8:1:2::1", 1), v6a.connect("a", 255, ""));
            h.in(0, addr("2001:db8:1:2:ffff::7", 1), v6b.connect("b", 255, ""));
            h.in(0, addr("2001:db8:1:2:abcd::9", 1), v6c.connect("c", 255, ""));
            h.in(0, addr("2001:db8:1:3::1", 1), v6other.connect("d", 255, ""));
            h.deliver(v6a, addr("2001:db8:1:2::1", 1));
            h.deliver(v6b, addr("2001:db8:1:2:ffff::7", 1));
            h.deliver(v6c, addr("2001:db8:1:2:abcd::9", 1));
            h.deliver(v6other, addr("2001:db8:1:3::1", 1));
            ServerNetConfig unlimited = new ServerNetConfig();
            unlimited.maxPerIp = 0;
            Harness u = new Harness(unlimited, "x: 1\n");
            for (int i = 0; i < 6; i++) {
                u.in(0, addr("10.0.0.9", 6000 + i), new TestClient(i).connect("u" + i, 255, ""));
            }
            assertAll(
                () -> assertTrue(mine.get(0).accepted && mine.get(1).accepted, "two sessions from one IP are accepted"),
                () -> assertEquals(Msg.Reject.REASON_LOCKED, mine.get(2).rejectReason, "the third from that IP is refused LOCKED (C E_GAME_LOCKED)"),
                () -> assertEquals("too many players from your address", mine.get(2).rejectText, "saying why"),
                () -> assertTrue(replace.accepted, "a reconnect of an existing session does not count itself"),
                () -> assertTrue(other.accepted, "another IP is unaffected"),
                () -> assertTrue(v6a.accepted && v6b.accepted, "two addresses of one IPv6 /64 are accepted"),
                () -> assertEquals(Msg.Reject.REASON_LOCKED, v6c.rejectReason, "a third address in the same /64 is refused (C E_GAME_LOCKED)"),
                () -> assertTrue(v6other.accepted, "the neighbouring /64 is another source"),
                () -> assertEquals(6, u.net.connections(), "maxPerIp 0 means unlimited"));
        }

        @Test
        @DisplayName("session ids are random per seed, never 0, and unique among active peers")
        void sessionIds() {
            Harness h = new Harness();
            Harness other = new Harness(new ServerNetConfig(), "x: 1\n");
            other.net = new ServerNet(other.cfg, other.host, other, other.log::add, other.blob, 12345, 1);
            other.in(0, A, new TestClient(1).connect("a", 255, ""));
            h.in(0, A, new TestClient(1).connect("a", 255, ""));
            // The raw generator with this seed repeats (or yields 0) after k draws: open k + 1 sessions.
            SplittableRandom raw = new SplittableRandom(Harness.SESSION_SEED);
            Set<Integer> seen = new HashSet<>();
            int k = 0;
            while (true) {
                int s = raw.nextInt() & 0xFFFF;
                if (s == 0 || !seen.add(s)) {
                    break;
                }
                k++;
            }
            int needed = k + 1;
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.maxConnections = needed;
            cfg.maxPerIp = 0;
            cfg.connectCost = 0;
            cfg.rateBurst = 1e9;
            Harness many = new Harness(cfg, "x: 1\n");
            Set<Integer> ids = new HashSet<>();
            for (int i = 0; i < needed; i++) {
                InetSocketAddress from = addr("10.1." + (i >> 8) + "." + (i & 255), 1);
                many.in(0, from, new TestClient(i).connect("p", 255, ""));
                ids.add(many.net.sessionOf(0, from));
            }
            assertAll(
                () -> assertNotEquals(h.net.sessionOf(0, A), other.net.sessionOf(0, A), "another seed gives another id"),
                () -> assertTrue(needed > 2, "the raw stream repeats only after a while (" + needed + ")"),
                () -> assertEquals(needed, many.net.connections(), "every session was opened"),
                () -> assertEquals(needed, ids.size(), "ids stay unique although the raw stream repeated"),
                () -> assertFalse(ids.contains(0), "0 is never used"),
                () -> assertFalse(ids.contains(-1), "every peer has a session"));
        }
    }

    @Nested
    @DisplayName("routing, malformed input and rate limits")
    class Routing {
        @Test
        @DisplayName("a peer is (channel, address); replies use the channel it arrived on")
        void pinned() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.maxPerIp = 0; // three peers from one address (default 2)
            Harness h = new Harness(cfg, "mapName: test\n");
            TestClient on0 = new TestClient(1);
            TestClient on4 = new TestClient(2);
            TestClient on20 = new TestClient(3);
            h.in(0, A, on0.connect("a", 255, ""));
            h.in(4, A, on4.connect("b", 255, ""));
            h.in(20, A, on20.connect("c", 255, ""));
            List<Harness.Sent> replies = h.take(A);
            for (Harness.Sent s : replies) {
                TestClient c = s.channel() == 0 ? on0 : s.channel() == 4 ? on4 : on20;
                c.onPacket(s.data().duplicate(), h.now);
            }
            confirm(h, on0, 0, A);
            confirm(h, on4, 4, A);
            confirm(h, on20, 20, A);
            h.tick();
            List<Harness.Sent> later = h.take(A);
            assertAll(
                () -> assertEquals(3, h.net.connections(), "the same address on three channels is three peers"),
                () -> assertEquals(List.of(0, 4, 20), List.of(replies.get(0).channel(), replies.get(1).channel(),
                        replies.get(2).channel()),
                    "each ACCEPT leaves through the contacted channel"),
                () -> assertNotEquals(h.net.sessionOf(0, A), h.net.sessionOf(4, A), "with distinct sessions"),
                () -> assertEquals(Set.of(0, 4, 20), channels(later), "later traffic goes to all three"),
                () -> assertTrue(later.stream().allMatch(s -> s.session() == h.net.sessionOf(s.channel(), A)),
                    "later traffic stays on each peer's own channel"),
                () -> assertEquals(-1, h.net.sessionOf(1, A), "no peer on other channels"));
        }

        private Set<Integer> channels(List<Harness.Sent> l) {
            Set<Integer> out = new HashSet<>();
            for (Harness.Sent s : l) {
                out.add(s.channel());
            }
            return out;
        }

        @Test
        @DisplayName("stray, bad and truncated packets are counted and dropped without exceptions")
        void malformed() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.join(c, 0, A, "a");
            h.host.calls.clear();
            TestClient stranger = new TestClient(9);
            h.in(0, B, stranger.bare(PacketType.ACK));
            TestClient wrongSession = new TestClient(1);
            wrongSession.session = c.session ^ 1;
            h.in(0, A, wrongSession.bare(PacketType.ACK));
            long strays = h.net.stray();

            ByteBuffer badMagic = ByteBuffer.wrap(new byte[] {0, 0, 1, 8, 0, 0, 0, 0});
            h.in(0, A, badMagic);
            h.in(0, A, c.bare(77));
            h.in(0, A, withBody(c.bare(PacketType.RELIABLE), new byte[] {1, 0, 0, 0, 9}));
            h.in(0, A, withBody(c.bare(PacketType.INPUT), new byte[] {0, 0, 0, 1}));
            h.in(0, A, withBody(c.bare(PacketType.PING), new byte[] {1, 2, 3}));
            h.in(0, A, withBody(c.bare(PacketType.CONNECT), new byte[] {1, 2}));
            h.in(0, A, withBody(c.bare(PacketType.CONNECT), new byte[] {1, 2, 3, 4, 5, 9, 'a'}));
            h.host.throwOnJoin = new IllegalStateException("host bug");
            h.in(0, B, new TestClient(3).connect("thrower", 255, ""));
            assertAll(
                () -> assertEquals(-1, h.net.sessionOf(0, B), "a host that throws on join creates no session"),
                () -> assertEquals(2, strays, "unknown peers and wrong sessions are stray"),
                () -> assertEquals(8, h.net.malformed(), "bad magic, unknown type, bad RELIABLE, short INPUT, short PING, "
                    + "a short CONNECT, a CONNECT whose name runs past the end and a host exception are all counted"),
                () -> assertEquals(1, h.net.connections(), "the connection survives"),
                () -> assertEquals(List.of(), h.host.callsStartingWith("input"), "nothing malformed reaches the host as input"));
        }

        @Test
        @DisplayName("source keys: secret, one per IPv4 address or IPv6 /64, every port shared")
        void sourceKeys() {
            Harness h = new Harness();
            ServerNet other = new ServerNet(h.cfg, h.host, h, h.log::add, h.blob, 1, Harness.KEY_SECRET + 1);
            assertAll(
                () -> assertEquals(h.net.sourceKey(addr("10.0.0.1", 1)), h.net.sourceKey(addr("10.0.0.1", 2)),
                    "ports of one IP share a key"),
                () -> assertNotEquals(h.net.sourceKey(addr("10.0.0.1", 1)), h.net.sourceKey(addr("10.0.0.2", 1)),
                    "different IPs differ"),
                () -> assertNotEquals(h.net.sourceKey(addr("10.0.0.1", 1)), other.sourceKey(addr("10.0.0.1", 1)),
                    "another secret gives another key"));
        }

        @Test
        @DisplayName("an attacker who chooses an IPv6 address hash-colliding with a victim does not share its bucket")
        void chosenCollision() throws Exception {
            InetSocketAddress victim = addr("192.0.2.10", 4000);
            int target = victim.getAddress().hashCode();
            byte[] b = java.net.InetAddress.ofLiteral("2001:db8:1:2::").getAddress();
            int want = target - java.net.InetAddress.getByAddress(b).hashCode();
            for (int k = 15; k >= 12; k--) {
                b[k] = (byte) want;
                want = (want - b[k]) >> 8;
            }
            InetSocketAddress attacker = new InetSocketAddress(java.net.InetAddress.getByAddress(b), 1);
            Harness h = new Harness(new ServerNetConfig(), "x: 1\n");
            for (int i = 0; i < 2000; i++) {
                h.in(0, attacker, ByteBuffer.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0}));
            }
            TestClient v = new TestClient(5);
            h.in(0, victim, v.connect("victim", 255, ""));
            assertAll(
                () -> assertEquals(target, attacker.getAddress().hashCode(), "the attacker's address collides on InetAddress.hashCode"),
                () -> assertTrue(h.net.rateLimited() > 1000, "the attacker's flood is limited"),
                () -> assertEquals(1, h.net.connections(), "the victim still connects"));
        }

        @Test
        @DisplayName("two sources sharing a limiter slot share its tokens instead of resetting them")
        void sharedSlot() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.maxPerIp = 0;
            Harness h = new Harness(cfg, "x: 1\n");
            int mask = ServerNet.SOURCE_SLOTS - 1;
            InetSocketAddress first = addr("198.51.100.1", 1);
            InetSocketAddress second = null;
            for (int k = 2; second == null; k++) {
                InetSocketAddress cand = addr("198." + (k >> 16 & 255) + "." + (k >> 8 & 255) + "." + (k & 255), 1);
                if ((h.net.sourceKey(cand) & mask) == (h.net.sourceKey(first) & mask)) {
                    second = cand;
                }
            }
            for (int i = 0; i < 64; i++) {
                InetSocketAddress src = i % 2 == 0 ? first : second;
                h.in(0, addr(src.getAddress().getHostAddress(), 1000 + i), new TestClient(i).connect("x" + i, 255, ""));
            }
            assertAll(
                () -> assertEquals(7, h.net.connections(),
                    "an 800-token bucket pays for 7 CONNECTs of 101 tokens in one instant, whichever source asks"),
                () -> assertEquals(57, h.net.rateLimited(), "the rest are limited"));
        }

        @Test
        @DisplayName("CONNECTs are limited per source, session traffic per session (many players behind one NAT)")
        void rateLimits() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.ratePerSecond = 10;
            cfg.rateBurst = 6;
            cfg.connectCost = 1;
            cfg.sessionRatePerSecond = 10;
            cfg.sessionRateBurst = 20;
            cfg.maxPerIp = 0; // many players behind one NAT
            Harness h = new Harness(cfg, "x: 1\n");
            List<TestClient> nat = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                TestClient c = new TestClient(i + 1);
                h.in(0, addr("10.0.0.1", 4000 + i), c.connect("p" + i, 255, ""));
                h.deliver(c, addr("10.0.0.1", 4000 + i));
                nat.add(c);
            }
            long limitedConnects = h.net.rateLimited();
            TestClient stranger = new TestClient(50);
            h.in(0, addr("10.0.0.1", 4999), stranger.bare(PacketType.ACK));
            long strayLimited = h.net.rateLimited() - limitedConnects;
            int pongs = 0;
            for (int i = 0; i < 3; i++) {
                InetSocketAddress from = addr("10.0.0.1", 4000 + i);
                for (int k = 0; k < 20; k++) {
                    h.in(0, from, nat.get(i).ping(k, k));
                }
                pongs += h.take(from).size();
            }
            int pongsWithinBurst = pongs;
            InetSocketAddress first = addr("10.0.0.1", 4000);
            h.in(0, first, nat.get(0).ping(99, 99));
            int overBurst = h.take(first).size();
            h.now += 100 * MS;
            h.in(0, first, nat.get(0).ping(100, 100));
            h.in(0, first, nat.get(0).ping(101, 101));
            int afterRefill = h.take(first).size();
            assertAll(
                () -> assertTrue(nat.get(0).accepted && nat.get(1).accepted && nat.get(2).accepted,
                    "three CONNECTs (2 tokens each) fit the source's 6-token burst"),
                () -> assertFalse(nat.get(3).accepted, "the fourth is limited"),
                () -> assertEquals(1, limitedConnects, "and counted"),
                () -> assertEquals(1, strayLimited, "a stray from that drained source is limited too"),
                () -> assertEquals(60, pongsWithinBurst, "each of 3 sessions behind that source gets its own 20-packet burst"),
                () -> assertEquals(0, overBurst, "the 21st packet of a session in the same instant is limited"),
                () -> assertEquals(1, afterRefill, "a tenth of a second refills exactly one token at 10/s"));
        }
    }

    @Nested
    @DisplayName("map transfer and initial state")
    class Transfer {
        @Test
        @DisplayName("a large config streams in order, then the initial state, then events")
        void stream() {
            String text = randomText(60_000, 3);
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.reliablePacketsPerSecond = 3 * cfg.simHz;
            Harness h = new Harness(cfg, text);
            TestClient c = new TestClient(1);
            h.in(0, A, c.connect("a", 255, ""));
            h.deliver(c, A);
            h.host.pendingChat.add("early");
            int maxReliablePerTick = 0;
            int ticks = 0;
            while (!c.hasMap() && ticks < 600) {
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
                () -> assertEquals(3, maxRel, "reliable datagrams per tick are capped at reliablePacketsPerSecond / simHz"));
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
        @DisplayName("redundancy entries are applied oldest first; the verifying INPUT itself is applied")
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
            assertAll(
                () -> assertEquals(List.of("input 0 seq=1 keys=10 aim=1", "input 0 seq=2 keys=20 aim=2", "input 0 seq=3 keys=30 aim=3"),
                    h.host.callsStartingWith("input"), "a fresh peer gets all three entries oldest first, with aim turns"),
                () -> assertTrue(h.net.isVerified(0, A), "the INPUT also verified the session"));
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
            confirm(h, c, 0, A);
            boolean snapshotBeforeSync = false;
            for (int i = 0; i < 4; i++) {
                h.tick();
                snapshotBeforeSync |= h.types(h.take(A)).contains(PacketType.SNAPSHOT);
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
                () -> assertEquals(12, c.snapshotTicks.size(), "12 ticks give 12 snapshots (one per frame)"),
                () -> assertEquals(c.snapshotTicks.get(0) + 11, (int) c.snapshotTicks.get(11), "on consecutive ticks"),
                () -> assertEquals(h.host.lastTick, h.net.currentTick(), "the host ticks with the server's tick numbers"),
                () -> assertEquals(4, c.radarSnapshots, "radar in every 3rd snapshot"),
                () -> assertEquals(4, snaps.stream().filter(s -> s.endsWith("radar=true")).count(),
                    "the host is asked for radar on those snapshots only"),
                () -> assertEquals(73, c.lastRadarCellX, "radar is quantized against the configured world size (1000/3500*256)"),
                () -> assertEquals(1000f, c.lastSelfX, "the host's records arrive"),
                () -> assertEquals(2000f, c.lastSelfY, "the host's records arrive (y)"));
        }

        @Test
        @DisplayName("a viewer the host says is not due gets no snapshot that tick (C's paused/dead frame skipping)")
        void notDue() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.in(0, A, c.connect("a", 255, ""));
            h.deliver(c, A);
            confirm(h, c, 0, A);
            h.join(c, 0, A, "a");
            c.snapshotTicks.clear();
            h.host.evenTicksOnly = true;
            for (int i = 0; i < 12; i++) {
                h.step(c, 0, A, 0);
            }
            boolean allEven = c.snapshotTicks.stream().allMatch(t -> (t & 1) == 0);
            assertAll(
                () -> assertEquals(6, c.snapshotTicks.size(), "12 ticks, every other one due: 6 snapshots"),
                () -> assertTrue(allEven, "only on the ticks the host called due"));
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
                () -> assertEquals(4, c.welcome.snapshotHz, "WELCOME says 14 / 3 = 4 Hz"));
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
            List<String> three = new ArrayList<>(c.snapshotParts);
            int t3 = h.net.currentTick();
            h.host.partsNeeded = 10;
            c.snapshotParts.clear();
            h.step(c, 0, A, 0);
            int t4 = h.net.currentTick();
            assertAll(
                () -> assertEquals(List.of(t3 + ":0/3", t3 + ":1/3", t3 + ":2/3L"), three,
                    "three parts, partCount 3, last-part flag only on the last"),
                () -> assertEquals(List.of(t4 + ":0/4", t4 + ":1/4", t4 + ":2/4", t4 + ":3/4L"), c.snapshotParts,
                    "a snapshot needing more is cut at MAX_SNAPSHOT_PARTS"),
                () -> assertEquals(1, h.net.truncatedSnapshots(), "and counted as truncated"));
        }

        @Test
        @DisplayName("an event every tick does not starve snapshots (instant link)")
        void noStarvationInstant() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.in(0, A, c.connect("a", 255, ""));
            h.deliver(c, A);
            int mapAt = -1;
            for (int i = 0; i < 600 && c.snapshotTicks.isEmpty(); i++) {
                if (c.hasMap() && !c.readySent) {
                    c.ready();
                    mapAt = i;
                }
                h.host.pendingChat.add("e" + i);
                h.step(c, 0, A, 0);
            }
            for (int i = 0; i < 60; i++) {
                h.host.pendingChat.add("more" + i);
                h.step(c, 0, A, 0);
            }
            int mapTick = mapAt;
            assertAll(
                () -> assertTrue(mapTick >= 0, "the map arrives"),
                () -> assertEquals(61, c.snapshotTicks.size(), "snapshots start promptly and keep flowing (1 + 60 in 60 ticks)"),
                () -> assertTrue(c.reliableLog.contains("MESSAGE more58"), "while every event is still delivered"));
        }

        @Test
        @DisplayName("an event every tick does not starve snapshots over a 100 ms and a 143 ms round trip")
        void noStarvationRtt() {
            ServerNetConfig at60 = new ServerNetConfig();
            at60.simHz = 60;
            int[] fast = mapAndFirstSnapshot(at60, 3);              // 3 ticks of 16.7 ms each way: 100 ms
            int[] slow = mapAndFirstSnapshot(new ServerNetConfig(), 1); // 1 tick of 71.4 ms each way: 143 ms
            assertAll(
                () -> assertTrue(fast[0] > 0 && slow[0] > 0, "the map arrives on both links"),
                () -> assertTrue(fast[1] > fast[0] && fast[1] - fast[0] <= 4 * 6,
                    "at 60 fps snapshots start within 4 round trips of the map (map " + fast[0] + ", snapshot " + fast[1] + ")"),
                () -> assertTrue(slow[1] > slow[0] && slow[1] - slow[0] <= 4 * 2,
                    "at 14 fps likewise (map " + slow[0] + ", snapshot " + slow[1] + ")"));
        }

        /** Join over a link delaying each datagram {@code delay} ticks each way, one host event per tick. */
        private int[] mapAndFirstSnapshot(ServerNetConfig cfg, int delay) {
            Harness h = new Harness(cfg, randomText(20_000, 5));
            TestClient c = new TestClient(1);
            List<long[]> upDue = new ArrayList<>();
            List<ByteBuffer> up = new ArrayList<>();
            List<long[]> downDue = new ArrayList<>();
            List<ByteBuffer> down = new ArrayList<>();
            int firstSnapshotAt = -1;
            int mapAt = -1;
            for (int t = 0; t < 1200 && firstSnapshotAt < 0; t++) {
                if (!c.accepted && t % 15 == 0) {
                    upDue.add(new long[] {t + delay});
                    up.add(c.connect("far", 255, ""));
                }
                if (c.hasMap() && !c.readySent) {
                    c.ready();
                    mapAt = t;
                }
                for (ByteBuffer b : c.frame(h.now, 0)) {
                    upDue.add(new long[] {t + delay});
                    up.add(b);
                }
                for (int i = 0; i < up.size(); ) {
                    if (upDue.get(i)[0] <= t) {
                        h.in(0, A, up.remove(i));
                        upDue.remove(i);
                    } else {
                        i++;
                    }
                }
                h.host.pendingChat.add("e" + t);
                h.tick();
                for (Harness.Sent s : h.take(A)) {
                    downDue.add(new long[] {t + delay});
                    down.add(s.data());
                }
                for (int i = 0; i < down.size(); ) {
                    if (downDue.get(i)[0] <= t) {
                        c.onPacket(down.remove(i), h.now);
                        downDue.remove(i);
                    } else {
                        i++;
                    }
                }
                if (!c.snapshotTicks.isEmpty()) {
                    firstSnapshotAt = t;
                }
            }
            return new int[] {mapAt, firstSnapshotAt};
        }

        @Test
        @DisplayName("every peer receives every host event")
        void eventsToAllPeers() {
            Harness h = new Harness();
            TestClient a = new TestClient(1);
            TestClient b = new TestClient(2);
            h.join(a, 0, A, "a");
            h.join(b, 0, B, "b");
            h.host.pendingChat.add("both");
            for (int i = 0; i < 4; i++) {
                for (ByteBuffer p : a.frame(h.now, 0)) {
                    h.in(0, A, p);
                }
                for (ByteBuffer p : b.frame(h.now, 0)) {
                    h.in(0, B, p);
                }
                h.tick();
                h.deliver(a, A);
                h.deliver(b, B);
            }
            assertAll(
                () -> assertTrue(a.reliableLog.contains("MESSAGE both"), "the first peer gets the event"),
                () -> assertTrue(b.reliableLog.contains("MESSAGE both"), "the second peer gets it too"));
        }
    }

    @Nested
    @DisplayName("reliable traffic, ping, ack")
    class ReliableTraffic {
        @Test
        @DisplayName("client reliable messages reach the host in order; PING gets PONG; idle ticks ACK")
        void messages() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.snapshotDivisor = 2;
            Harness h = new Harness(cfg, "x: 1\n");
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

        @Test
        @DisplayName("a message the host throws on is skipped and counted; later messages still arrive")
        void hostThrows() {
            Harness h = new Harness();
            TestClient c = new TestClient(1);
            h.join(c, 0, A, "a");
            h.host.calls.clear();
            h.host.throwOnBoom = new BufferUnderflowException();
            h.host.throwOnInput = new IllegalStateException("host bug");
            h.host.throwOnKeys = 77;
            h.in(0, A, c.input(77));
            ByteBuffer m = c.ch.begin(ReliableType.CHAT);
            m.put((byte) 0).putShort((short) 0).putShort((short) 9);
            c.ch.end();
            c.chat("boom");
            c.chat("after");
            for (int i = 0; i < 10; i++) {
                h.step(c, 0, A, 0);
            }
            assertAll(
                () -> assertEquals(List.of("msg 0 CHAT after"), h.host.callsStartingWith("msg"),
                    "the message after the bad ones reaches the host"),
                () -> assertEquals(3, h.net.malformed(),
                    "an undecodable message, a host exception on a message and one on an input count as malformed"),
                () -> assertEquals(0, c.ch.unacked(), "the client's channel is fully acknowledged (not wedged)"),
                () -> assertEquals(1, h.net.connections(), "the peer stays"));
        }
    }

    @Nested
    @DisplayName("leaving")
    class Leaving {
        @Test
        @DisplayName("silence beyond the timeout disconnects; any packet in time keeps the peer")
        void timeout() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.timeoutNanos = 10 * Harness.TICK;
            Harness h = new Harness(cfg, "x: 1\n");
            TestClient c = new TestClient(1);
            h.join(c, 0, A, "a");
            for (int i = 0; i < 40; i++) {
                if (i % 8 == 0) {
                    h.in(0, A, c.bare(PacketType.ACK));
                }
                h.tick();
                h.deliver(c, A);
            }
            boolean keptByActivity = h.net.connections() == 1;
            h.in(0, A, c.bare(PacketType.ACK));
            long t0 = h.now;
            h.now = t0 + cfg.timeoutNanos - Harness.TICK;
            h.tick();
            boolean aliveAtLimit = h.net.connections() == 1;
            h.deliver(c, A);
            h.tick();
            List<Harness.Sent> l = h.take(A);
            for (Harness.Sent s : l) {
                c.onPacket(s.data().duplicate(), h.now);
            }
            assertAll(
                () -> assertTrue(keptByActivity, "a packet every 8 ticks keeps a peer with a 10-tick timeout"),
                () -> assertTrue(aliveAtLimit, "exactly at the timeout the peer is kept"),
                () -> assertEquals(0, h.net.connections(), "one tick later it is dropped"),
                () -> assertEquals(List.of(PacketType.DISCONNECT, PacketType.DISCONNECT, PacketType.DISCONNECT), h.types(l),
                    "DISCONNECT is sent three times"),
                () -> assertEquals(Msg.Disconnect.REASON_TIMEOUT, c.disconnectReason, "with the timeout reason"),
                () -> assertEquals(List.of("leave 0"), h.host.callsStartingWith("leave"), "the host is told once"),
                () -> assertTrue(h.log.get(h.log.size() - 1).endsWith("(timeout)"), "the leave is logged with its cause"));
        }

        @Test
        @DisplayName("a peer that keeps sending but never acknowledges is dropped after the timeout")
        void noAckProgress() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.timeoutNanos = 60 * Harness.TICK;
            Harness h = new Harness(cfg, "x: 1\n");
            TestClient deaf = new TestClient(1);
            TestClient fine = new TestClient(2);
            h.join(deaf, 0, A, "deaf");
            h.join(fine, 0, B, "fine");
            h.host.pendingChat.add("unacked");
            int deafGoneAfter = -1;
            for (int i = 0; i < 200 && deafGoneAfter < 0; i++) {
                h.in(0, A, deaf.input(0));     // frozen ack: deaf never reads what the server sends
                h.sent.removeIf(s -> s.to().equals(A));
                h.step(fine, 0, B, 0);
                if (!h.isConnected(0, A)) {
                    deafGoneAfter = i;
                }
            }
            int gone = deafGoneAfter;
            assertAll(
                () -> assertEquals(61, gone, "dropped once its oldest message waited longer than the timeout (60 ticks)"),
                () -> assertTrue(h.isConnected(0, B), "an acknowledging peer with the same traffic stays"),
                () -> assertTrue(h.log.contains("leave: player 0 (reliable data unacknowledged)"), "the cause is logged"));
        }

        @Test
        @DisplayName("a peer whose reliable backlog exceeds maxOverflow is dropped")
        void backlog() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.maxOverflow = 8;
            Harness h = new Harness(cfg, "x: 1\n");
            TestClient c = new TestClient(1);
            h.join(c, 0, A, "a");
            for (int i = 0; i < Protocol.RELIABLE_WINDOW + 8; i++) {
                h.host.pendingChat.add("e" + i);
            }
            h.in(0, A, c.input(0));
            h.tick();
            h.in(0, A, c.input(0));
            h.tick();
            boolean atLimit = h.isConnected(0, A);
            h.host.pendingChat.add("one more");
            h.in(0, A, c.input(0));
            h.tick();
            h.in(0, A, c.input(0));
            h.tick();
            assertAll(
                () -> assertTrue(atLimit, "a backlog of exactly maxOverflow is kept"),
                () -> assertFalse(h.isConnected(0, A), "one more is dropped"),
                () -> assertTrue(h.log.contains("leave: player 0 (reliable backlog)"), "the cause is logged"));
        }

        @Test
        @DisplayName("a client DISCONNECT removes the peer silently; shutdown disconnects every verified peer")
        void quitAndShutdown() {
            Harness h = new Harness();
            TestClient a = new TestClient(1);
            TestClient b = new TestClient(2);
            TestClient d = new TestClient(3);
            TestClient unverified = new TestClient(4);
            h.in(0, A, a.connect("a", 255, ""));
            h.in(0, B, b.connect("b", 255, ""));
            h.in(1, B, d.connect("d", 255, ""));
            InetSocketAddress u = addr("10.0.0.3", 1);
            h.in(0, u, unverified.connect("u", 255, ""));
            h.deliver(a, A);
            for (Harness.Sent s : h.take(B)) {
                (s.channel() == 0 ? b : d).onPacket(s.data().duplicate(), h.now);
            }
            confirm(h, a, 0, A);
            confirm(h, b, 0, B);
            confirm(h, d, 1, B);
            h.sent.clear();
            h.in(0, A, a.disconnect());
            List<Harness.Sent> afterQuit = h.take(A);
            h.in(0, A, a.bare(PacketType.ACK));
            long stray = h.net.stray();
            h.net.shutdown();
            List<Harness.Sent> toB = h.take(B);
            List<Harness.Sent> toUnverified = h.take(u);
            TestClient bView = new TestClient(2);
            bView.onPacket(toB.get(5).data().duplicate(), h.now);
            assertAll(
                () -> assertEquals(List.of(), afterQuit, "nothing is sent back to a quitting client"),
                () -> assertEquals(1, stray, "its later packets are stray"),
                () -> assertEquals(6, toB.size(), "shutdown sends 3 DISCONNECTs to each remaining verified peer"),
                () -> assertEquals(Set.of(0, 1), Set.of(toB.get(0).channel(), toB.get(3).channel()), "each through its own channel"),
                () -> assertEquals(List.of(), toUnverified, "an unverified peer gets no DISCONNECT"),
                () -> assertEquals(List.of("leave 0", "leave 2", "leave 1", "leave 3"), h.host.callsStartingWith("leave"),
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
                () -> assertTrue(h.log.contains("join: player 1 'b' from " + B + " on channel 0"),
                    "the reused slot logs its own name, not the previous one's: " + h.log),
                () -> assertEquals("mapName: test\n", b.configText, "the new peer gets the full config again"),
                () -> assertEquals(List.of("WELCOME 1", "MAP", "JOIN 1 self"), b.reliableLog, "and its own initial state"),
                () -> assertEquals("input 1 seq=1 keys=0 aim=0", h.host.callsStartingWith("input").get(0),
                    "its input sequencing starts fresh (seq 1 is applied after the previous peer's higher sequences)"));
        }
    }

    @Nested
    @DisplayName("kick (GameHost.Sessions) and the reconnect guard")
    class Kick {
        @Test
        @DisplayName("a verified peer gets DISCONNECT (kicked, reason) 3 times; the host gets one leave; nothing follows")
        void verified() {
            Harness h = new Harness();
            TestClient a = new TestClient(1);
            TestClient b = new TestClient(2);
            h.join(a, 0, A, "a");
            h.join(b, 0, B, "b");
            h.take(A);
            h.take(B);
            h.host.sessions.kick(0, "auto-kicked: paused too long");
            List<Harness.Sent> toA = h.take(A);
            for (Harness.Sent sent : toA) {
                a.onPacket(sent.data().duplicate(), h.now);
            }
            List<String> leaves = h.host.callsStartingWith("leave");
            h.tick();
            assertAll(
                () -> assertEquals(List.of(PacketType.DISCONNECT, PacketType.DISCONNECT, PacketType.DISCONNECT), h.types(toA),
                    "three DISCONNECTs"),
                () -> assertEquals(a.session, toA.get(0).session(), "on the kicked peer's session"),
                () -> assertEquals(Msg.Disconnect.REASON_KICKED, a.disconnectReason, "reason KICKED"),
                () -> assertEquals("auto-kicked: paused too long", a.disconnectText, "with the host's text"),
                () -> assertEquals(List.of("leave 0"), leaves, "the host gets the leave before kick returns"),
                () -> assertFalse(h.isConnected(0, A), "the peer is gone"),
                () -> assertTrue(h.isConnected(0, B), "the other stays"),
                () -> assertEquals(1, h.net.connections(), "one connection is left"),
                () -> assertEquals(List.of(), h.take(A), "nothing more is sent to the kicked address"),
                () -> assertEquals(List.of("leave 0"), h.host.callsStartingWith("leave"), "no second leave"),
                () -> assertTrue(h.log.get(h.log.size() - 1).endsWith("(auto-kicked: paused too long)"),
                    "the leave is logged with the reason: " + h.log));
        }

        @Test
        @DisplayName("an unverified peer is dropped without DISCONNECT; an id no connection has is ignored")
        void unverifiedAndUnknown() {
            Harness h = new Harness();
            TestClient a = new TestClient(1);
            h.in(0, A, a.connect("a", 255, ""));
            h.take(A);
            h.host.sessions.kick(7, "nobody");
            int afterUnknown = h.net.connections();
            List<String> leavesAfterUnknown = h.host.callsStartingWith("leave");
            h.host.sessions.kick(0, "no free base");
            assertAll(
                () -> assertEquals(1, afterUnknown, "kicking an unknown id changes nothing"),
                () -> assertEquals(List.of(), leavesAfterUnknown, "and leaves nobody"),
                () -> assertEquals(List.of(), h.take(A), "an unproven address gets no DISCONNECT"),
                () -> assertEquals(List.of("leave 0"), h.host.callsStartingWith("leave"), "the host gets its leave"),
                () -> assertEquals(0, h.net.connections(), "the connection is gone"));
        }

        @Test
        @DisplayName("a reconnect whose join kicked the old session drops it once; the pool stays consistent")
        void reconnectKickedByJoin() {
            ServerNetConfig cfg = new ServerNetConfig();
            cfg.maxConnections = 2;
            Harness h = new Harness(cfg, "x: 1\n");
            TestClient old = new TestClient(1);
            h.join(old, 0, A, "Ann");
            int oldSession = h.net.sessionOf(0, A);
            h.take(A);
            h.host.kickOnJoin = 0;
            TestClient again = new TestClient(2);
            h.in(0, A, again.connect("Ann", 255, ""));
            List<Harness.Sent> toA = h.take(A);
            for (Harness.Sent sent : toA) {
                again.onPacket(sent.data().duplicate(), h.now);
            }
            TestClient b = new TestClient(3);
            h.in(0, B, b.connect("Bob", 255, ""));
            h.deliver(b, B);
            InetSocketAddress c = addr("10.0.0.3", 4000);
            TestClient third = new TestClient(4);
            h.in(0, c, third.connect("Cid", 255, ""));
            h.deliver(third, c);
            assertAll(
                () -> assertEquals(List.of("join Ann team=255 shape=", "join Ann team=255 shape=", "leave 0",
                        "join Bob team=255 shape="), joinsAndLeaves(h.host.calls),
                    "the old player leaves once, inside the new join"),
                () -> assertEquals(List.of(PacketType.DISCONNECT, PacketType.DISCONNECT, PacketType.DISCONNECT,
                    PacketType.ACCEPT), h.types(toA), "the old session is told it was kicked, then the new one is accepted"),
                () -> assertEquals(oldSession, toA.get(0).session(), "the DISCONNECTs carry the old session"),
                () -> assertTrue(again.accepted, "the reconnecting client is accepted"),
                () -> assertNotEquals(oldSession, again.session, "with a new session"),
                () -> assertTrue(b.accepted, "the second slot is free for Bob"),
                () -> assertEquals(Msg.Reject.REASON_FULL, third.rejectReason, "a third client finds the pool of 2 full"),
                () -> assertEquals(2, h.net.connections(), "two connections"));
        }
    }
}
