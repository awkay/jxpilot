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
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.net.Transport;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exact timer boundaries, restarts, buffer limits and per-datagram rules of {@link XpConnection}. */
class XpConnectionEdgeTest {
    static final long FRAME = 16_000_000L;
    static final long T0 = 5_000_000_000L;

    /** Opens ports on whichever fake server is current (a restart gets a fresh server). */
    static final class Delegate implements XpConnection.PortOpener {
        FakeCServer target;
        final List<Integer> ports = new ArrayList<>();

        @Override
        public Transport open(int port) throws IOException {
            ports.add(port);
            return target.open(port);
        }
    }

    Delegate opener;
    FakeCServer server;
    ClientGame game;
    Recorder rec;
    XpConnection conn;
    XpConnection.Login login;
    long now;

    @BeforeEach
    void setUp() {
        opener = new Delegate();
        server = new FakeCServer();
        opener.target = server;
        game = new ClientGame();
        rec = new Recorder();
        login = new XpConnection.Login();
        login.nick = "edge";
        conn = new XpConnection(opener, new XpConnectionTest.Both(rec, game), "127.0.0.1:15345", login);
        now = T0;
    }

    void frame() {
        now += FRAME;
        conn.update(now);
        game.frame(now, FRAME / 1e9);
        conn.sendInput(0, 0, now);
        conn.flush(now);
        server.step();
    }

    void play() {
        conn.start(now);
        server.step();
        for (int i = 0; i < 400 && conn.snapshotsReceived() < 5; i++) {
            frame();
        }
        assertEquals(ConnectionState.PLAYING, conn.state(), "fixture plays: " + rec.states);
    }

    /** update() at an exact time, then let the server answer. */
    void at(long t) {
        now = t;
        conn.update(t);
        server.step();
    }

    @Nested
    @DisplayName("timer boundaries")
    class Timers {
        @Test
        void contactResendsAtExactlyOneSecond() {
            server.silentContact = true;
            conn.start(T0);
            server.step();
            at(T0 + XpConnection.RESEND_NANOS - 1);
            int before = server.contactRequests.size();
            at(T0 + XpConnection.RESEND_NANOS);
            assertAll(
                () -> assertEquals(1, before, "not before 1 s"),
                () -> assertEquals(2, server.contactRequests.size(), "at 1 s"));
        }

        @Test
        void giveUpAfterExactlyMaxTries() {
            server.silentContact = true;
            conn.start(T0);
            for (int i = 1; i < XpConnection.MAX_TRIES; i++) {
                at(T0 + i * XpConnection.RESEND_NANOS);
            }
            ConnectionState before = conn.state();
            at(T0 + XpConnection.MAX_TRIES * XpConnection.RESEND_NANOS);
            assertAll(
                () -> assertEquals(ConnectionState.CONNECTING, before, "still trying after 10 sends"),
                () -> assertEquals(XpConnection.MAX_TRIES, server.contactRequests.size(), "10 sends"),
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "closed when the 11th is due"),
                () -> assertTrue(server.contact.closed, "socket closed on failure"));
        }

        @Test
        void queueResendAndTimeoutBoundaries() {
            server.queueSteps = Integer.MAX_VALUE;
            conn.start(T0);
            server.step();
            at(T0 + 1);
            long q0 = now;
            at(q0 + XpConnection.QUEUE_RESEND_NANOS - 2);
            int before = server.contactRequests.size();
            at(q0 + XpConnection.QUEUE_RESEND_NANOS);
            int after = server.contactRequests.size();
            at(q0 + XpConnection.QUEUE_TIMEOUT_NANOS - 1);
            ConnectionState justBefore = conn.state();
            at(q0 + XpConnection.QUEUE_TIMEOUT_NANOS);
            assertAll(
                () -> assertEquals(2, before, "contact + queue, no repeat before 2 s"),
                () -> assertEquals(3, after, "repeat at 2 s"),
                () -> assertEquals(ConnectionState.CONNECTING, justBefore, "still queued 1 ns before the timeout"),
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "timeout at 120 s"));
        }

        @Test
        void verifyAndPlayResendAtOneSecond() {
            server.silentLogin = true;
            conn.start(T0);
            server.step();
            at(T0 + 1);
            at(T0 + 2);
            long v0 = T0 + 2;
            int first = server.verifies;
            at(v0 + XpConnection.RESEND_NANOS - 1);
            int justBefore = server.verifies;
            at(v0 + XpConnection.RESEND_NANOS);
            assertAll(
                () -> assertEquals(XpConnection.Phase.VERIFY, conn.phase(), "verifying"),
                () -> assertEquals(1, first, "one verify"),
                () -> assertEquals(1, justBefore, "none before 1 s"),
                () -> assertEquals(2, server.verifies, "resent at 1 s"));
        }

        @Test
        void silenceTimeoutBoundary() {
            play();
            server.silentFrames = true;
            long last = now;
            for (int i = 0; i < 3; i++) {
                at(now + FRAME); // drain what is still queued
            }
            at(last + XpConnection.TIMEOUT_NANOS - 1);
            ConnectionState before = conn.state();
            at(last + XpConnection.TIMEOUT_NANOS + 3 * FRAME);
            assertAll(
                () -> assertEquals(ConnectionState.PLAYING, before, "alive just before 15 s of silence"),
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "lost after"));
        }

        @Test
        void keepAliveEveryFiveSecondsOfFramesWithoutSends() {
            server.mapUpdates = false;
            play();
            for (int i = 0; i < 400; i++) {
                frame();
            }
            List<Integer> f = server.keyboardFrames;
            int gap1 = f.get(f.size() - 1) - f.get(f.size() - 2);
            int gap2 = f.get(f.size() - 2) - f.get(f.size() - 3);
            assertAll(
                () -> assertEquals(5 * 12 + 1, gap1, "first frame with loops - last send > 5 * FPS"),
                () -> assertEquals(5 * 12 + 1, gap2, "steadily"),
                () -> assertEquals(List.of(), server.violations, "fine for the server"));
        }

        @Test
        void serverFpsZeroUsesFourteen() {
            server.fps = 0;
            conn.start(now);
            server.step();
            for (int i = 0; i < 60 && server.plays == 0; i++) {
                frame();
            }
            assertTrue(server.loginPackets.contains("fps 14"), "C default when the setup has no FPS");
        }
    }

    @Nested
    @DisplayName("restart and failures")
    class Restart {
        @Test
        void aSecondStartIsAFreshSession() {
            play();
            conn.keyboard().setKeys(7);
            conn.talk().add("left over", 0);
            conn.disconnect(now);
            server = new FakeCServer();
            opener.target = server;
            long snapsBefore = conn.snapshotsReceived();
            conn.start(now);
            server.step();
            for (int i = 0; i < 400 && conn.snapshotsReceived() < snapsBefore + 5; i++) {
                frame();
            }
            assertAll(
                () -> assertEquals(ConnectionState.PLAYING, conn.state(), "playing again"),
                () -> assertEquals(List.of(1), server.keyboardChanges, "keyboard numbering restarts"),
                () -> assertEquals(List.of(), server.talk, "old talk dropped"),
                () -> assertEquals(0, conn.staleFrames(), "frame numbers restart"),
                () -> assertEquals(0, conn.malformed(), "stream offset restarts"),
                () -> assertEquals(List.of(), server.violations, "clean"));
        }

        @Test
        void closeClosesSockets() {
            play();
            conn.close();
            assertTrue(server.login.closed, "closed");
        }

        @Test
        void failureClosesTheLoginSocket() {
            play();
            server.login.toClient.add(new CPackets().quit("bye").bytes());
            frame();
            assertTrue(server.login.closed, "closed on quit");
        }

        @Test
        void contactPortFromTheLabel() {
            XpConnection a = new XpConnection(opener, rec, "host", login);
            XpConnection b = new XpConnection(opener, rec, "host:abc", login);
            XpConnection c = new XpConnection(opener, rec, "[::1]:15000", login);
            a.start(T0);
            b.start(T0);
            c.start(T0);
            assertEquals(List.of(15345, 15345, 15000), opener.ports, "default port, bad port, explicit port");
        }

        @Test
        void exactlyTheOldestVersionIsAccepted() {
            server.version = XpProto.MIN_SERVER_VERSION;
            server.contactStatus = XpProto.SUCCESS;
            conn.start(now);
            server.step();
            frame();
            assertEquals(XpConnection.Phase.QUEUE, conn.phase(), "0x4203 is enough");
        }

        @Test
        void enterGameWithPortZeroIsIgnored() {
            server.queueSteps = Integer.MAX_VALUE;
            conn.start(now);
            server.step();
            frame();
            server.contact.toClient.add(new CPackets().contactReply(XpProto.MAGIC, XpProto.ENTER_GAME_PACK, 0, 0).bytes());
            frame();
            assertEquals(XpConnection.Phase.QUEUE, conn.phase(), "no port: still queued");
        }

        @Test
        void teamsOutsideZeroToNineAreNoTeam() {
            int[] teams = {0, 9, 10, -1, Protocol.NO_TEAM};
            List<Integer> sent = new ArrayList<>();
            for (int t : teams) {
                setUp();
                login.team = t;
                conn.start(now);
                server.step();
                frame();
                sent.add(server.team);
            }
            assertEquals(List.of(0, 9, -1, -1, -1), sent, "C sends -1 for no team");
        }
    }

    @Nested
    @DisplayName("datagram rules")
    class Datagrams {
        @Test
        void updateAloneFlushesAcksAndReachesPlaying() {
            conn.start(now);
            server.step();
            for (int i = 0; i < 400 && conn.snapshotsReceived() < 3; i++) {
                at(now + FRAME);
            }
            assertEquals(ConnectionState.PLAYING, conn.state(), "update() sends acks itself");
        }

        @Test
        void chatIsSentImmediately() {
            play();
            conn.sendChat("now", now);
            server.step();
            assertEquals(List.of("now"), server.talk, "no frame needed");
        }

        @Test
        void manyReliableChunksInOneUpdateSplitTheAcks() {
            play();
            int sentBefore = server.clientSent;
            byte[] dup = new CPackets().reliable(0, 1, new byte[] {1}).bytes();
            for (int i = 0; i < 300; i++) {
                server.login.toClient.add(dup);
            }
            conn.update(now += FRAME);
            int sent = server.clientSent - sentBefore;
            assertAll(
                () -> assertTrue(sent >= 3, "300 acks of 9 bytes need several 1 KB datagrams: " + sent),
                () -> assertEquals(0, conn.malformed(), "duplicates are fine"),
                () -> assertTrue(conn.packetsSent() > sent, "counted"));
        }

        @Test
        void framePacketsAfterAReliableChunkAreDecoded() {
            play();
            server.login.toClient.clear();
            long snaps = conn.snapshotsReceived();
            int l = conn.lastLoops() + 50;
            server.login.toClient.add(new CPackets().reliable(0, 1, new byte[] {1}).start(l, 0).self(10, 10).end(l)
                .bytes());
            conn.update(now += FRAME);
            assertEquals(snaps + 1, conn.snapshotsReceived(), "chunk then frame in one datagram");
        }

        @Test
        void aBadChunkDropsTheRestOfTheDatagram() {
            play();
            server.login.toClient.clear();
            long snaps = conn.snapshotsReceived();
            int l = conn.lastLoops() + 50;
            server.login.toClient.add(new CPackets().c(XpProto.PKT_RELIABLE).hd(0).d(0).d(0).start(l, 0).self(10, 10)
                .end(l).bytes());
            conn.update(now += FRAME);
            assertAll(
                () -> assertEquals(snaps, conn.snapshotsReceived(), "frame after a bad chunk dropped (C)"),
                () -> assertEquals(1, conn.malformed(), "bad chunk counted"));
        }

        @Test
        void badSetupCountersAreExact() {
            server.customSetup = new CPackets().setupHeader(0, 0, 0, 10, 10, 12, 1, "n", "a").bytes();
            conn.start(now);
            server.step();
            for (int i = 0; i < 30; i++) {
                frame();
            }
            assertAll(
                () -> assertEquals(1, conn.badStream(), "one bad stream"),
                () -> assertEquals(1, conn.malformed(), "one malformed"));
        }

        @Test
        void badReliablePacketCountersAreExact() {
            play();
            server.stream.put(new byte[] {(byte) 99});
            for (int i = 0; i < 5; i++) {
                frame();
            }
            assertAll(
                () -> assertEquals(1, conn.badStream(), "one"),
                () -> assertEquals(1, conn.malformed(), "one"));
        }
    }

    @Nested
    @DisplayName("what the model gets")
    class Model {
        @Test
        void welcomeFlagsFollowTheSetupMode() {
            List<Integer> flags = new ArrayList<>();
            int[] modes = {XpProto.MODE_TEAM_PLAY, XpProto.MODE_TIMING, XpProto.MODE_WRAP_PLAY, 0};
            for (int m : modes) {
                setUp();
                server.mode = m;
                conn.start(now);
                server.step();
                for (int i = 0; i < 60 && rec.welcome == null; i++) {
                    frame();
                }
                flags.add(rec.welcome.flags);
            }
            int b = Msg.Welcome.FLAG_RADAR_BLINKED;
            assertEquals(List.of(Msg.Welcome.FLAG_TEAM_PLAY | b, Msg.Welcome.FLAG_TIMING | b, Msg.Welcome.FLAG_EDGE_WRAP | b,
                b), flags, "one flag per mode bit, plus RADAR_BLINKED always (the C server blinks its radar)");
        }

        @Test
        void fastRadarUsesTheMapSize() {
            play();
            assertAll(
                () -> assertEquals(2, game.world().radarCount(), "blip plus own ship"),
                () -> assertEquals((10 + 0.5) * 3500 / 256, game.world().radarX(0), 3500 / 256.0,
                    "radar x within one cell of the C blip"),
                () -> assertNotNull(conn.translator(), "translator"),
                () -> assertNotNull(conn.reliableDecoder(), "decoder"),
                () -> assertEquals(0, conn.translator().culled(), "nothing culled"));
        }
    }
}
