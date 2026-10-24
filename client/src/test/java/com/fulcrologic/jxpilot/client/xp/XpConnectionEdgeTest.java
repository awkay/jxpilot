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

    /** Read what the server still has queued; the time of the last datagram read. */
    long drain() {
        long last = now;
        for (int i = 0; i < 3; i++) {
            long seen = conn.packetsReceived();
            at(now + FRAME);
            if (conn.packetsReceived() != seen) {
                last = now;
            }
        }
        return last;
    }

    int noResponses() {
        int n = 0;
        for (int i = 0; i < game.messages().gameCount(); i++) {
            if (game.messages().game(i).text().toString().equals(XpConnection.NO_RESPONSE)) {
                n++;
            }
        }
        return n;
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
            long lastReply = q0 + XpConnection.QUEUE_RESEND_NANOS - 2; // the queue reply is read here
            at(lastReply + XpConnection.QUEUE_TIMEOUT_NANOS - 1);
            ConnectionState justBefore = conn.state();
            at(lastReply + XpConnection.QUEUE_TIMEOUT_NANOS);
            assertAll(
                () -> assertEquals(2, before, "contact + queue, no repeat before 2 s"),
                () -> assertEquals(3, after, "repeat at 2 s"),
                () -> assertEquals(ConnectionState.CONNECTING, justBefore, "still queued 1 ns before 12 s without a reply"),
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "timeout 12 s after the last reply"));
        }

        @Test
        void aQueueReplyRestartsTheQueueTimeout() {
            server.queueSteps = Integer.MAX_VALUE;
            conn.start(T0);
            server.step();
            at(T0 + 1);
            long q0 = now; // the first queue reply is read at the next update
            at(q0 + 1);
            long first = now;
            server.queueAcks = true;
            at(first + XpConnection.QUEUE_TIMEOUT_NANOS - 2);
            server.queueAcks = false;
            at(first + XpConnection.QUEUE_TIMEOUT_NANOS - 1);
            long ack = now; // the re-ack is read here
            at(first + XpConnection.QUEUE_TIMEOUT_NANOS);
            ConnectionState afterFirstLimit = conn.state();
            at(ack + XpConnection.QUEUE_TIMEOUT_NANOS - 1);
            ConnectionState justBefore = conn.state();
            at(ack + XpConnection.QUEUE_TIMEOUT_NANOS);
            assertAll(
                () -> assertEquals(ConnectionState.CONNECTING, afterFirstLimit, "a reply moved the limit"),
                () -> assertEquals(ConnectionState.CONNECTING, justBefore, "alive 1 ns before 12 s after the reply"),
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "closed 12 s after the reply"));
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
            at(last + XpConnection.PLAY_TIMEOUT_NANOS - 1);
            ConnectionState before = conn.state();
            at(last + XpConnection.PLAY_TIMEOUT_NANOS + 3 * FRAME);
            assertAll(
                () -> assertEquals(ConnectionState.PLAYING, before, "alive just before 60 s of silence"),
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "lost after"),
                () -> assertEquals("Connection to 127.0.0.1:15345 lost (timeout)", conn.status().toString(), "status"));
        }

        @Test
        void noResponseEveryTenSecondsOfSilence() {
            play();
            server.silentFrames = true;
            long last = drain();
            int base = noResponses();
            at(last + XpConnection.NO_RESPONSE_NANOS - 1);
            int justBefore = noResponses() - base;
            at(last + XpConnection.NO_RESPONSE_NANOS);
            int atTen = noResponses() - base;
            at(last + XpConnection.NO_RESPONSE_NANOS + FRAME);
            int sameTen = noResponses() - base;
            at(last + 2 * XpConnection.NO_RESPONSE_NANOS);
            int atTwenty = noResponses() - base;
            server.silentFrames = false;
            at(now + FRAME);
            at(now + FRAME);
            server.silentFrames = true;
            long back = drain();
            at(back + XpConnection.NO_RESPONSE_NANOS - 1);
            int beforeNext = noResponses() - base;
            at(back + XpConnection.NO_RESPONSE_NANOS);
            int next = noResponses() - base;
            assertAll(
                () -> assertEquals(0, justBefore, "none before 10 s"),
                () -> assertEquals(1, atTen, "C's text at 10 s"),
                () -> assertEquals(1, sameTen, "once per 10 s"),
                () -> assertEquals(2, atTwenty, "again at 20 s"),
                () -> assertEquals(2, beforeNext, "frames reset the silence"),
                () -> assertEquals(3, next, "10 s after the last frame again"),
                () -> assertEquals(ConnectionState.PLAYING, conn.state(), "still playing"));
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
    @DisplayName("pointer control (C Send_pointer_move)")
    class PointerMoves {
        @Test
        void movesGoOutAsPointerMovePackets() {
            play();
            int changes = server.keyboardChanges.size();
            now += FRAME;
            conn.update(now);
            int sent = conn.sendInput(0, -37, now);
            conn.flush(now);
            server.step();
            now += FRAME;
            conn.update(now);
            conn.sendInput(0, 0, now);
            conn.flush(now);
            server.step();
            now += FRAME;
            conn.update(now);
            conn.sendInput(0, 100_000, now);
            conn.flush(now);
            server.step();
            assertAll(
                () -> assertEquals(0, sent, "no keyboard change"),
                () -> assertEquals(List.of(-37, (int) Short.MAX_VALUE), server.pointerMoves,
                    "each non-zero move once, as %hd (clamped); none for 0"),
                () -> assertEquals(changes, server.keyboardChanges.size(), "no new keyboard change for a move"),
                () -> assertEquals(List.of(), server.violations, "fine for the server"));
        }

        @Test
        void notBeforePlaying() {
            server.ignorePlay = true;
            conn.start(now);
            server.step();
            for (int i = 0; i < 30; i++) {
                now += FRAME;
                conn.update(now);
                conn.sendInput(0, 12, now);
                conn.flush(now);
                server.step();
            }
            assertAll(
                () -> assertEquals(XpConnection.Phase.START, conn.phase(), "still starting"),
                () -> assertEquals(List.of(), server.pointerMoves, "no pointer moves"));
        }
    }

    @Nested
    @DisplayName("transport errors (C Sockbuf_read / Sockbuf_flush carry on)")
    class TransportErrors {
        @Test
        void noServerAtAllEndsAfterMaxTriesWithoutThrowing() {
            server.silentContact = true;
            server.sendFailures = Integer.MAX_VALUE;
            server.receiveFailures = Integer.MAX_VALUE;
            conn.start(T0);
            for (int i = 1; i <= XpConnection.MAX_TRIES; i++) {
                at(T0 + i * XpConnection.RESEND_NANOS);
            }
            assertAll(
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "MAX_TRIES ends the contact"),
                () -> assertEquals("No answer from 127.0.0.1:15345", conn.status().toString(), "C's reason"),
                () -> assertEquals(0, conn.packetsSent(), "failed sends are not counted as sent"),
                () -> assertTrue(conn.ioErrors() >= 2L * XpConnection.MAX_TRIES,
                    "every send and the receive after it failed: " + conn.ioErrors()));
        }

        @Test
        void aFailedReceiveWhilePlayingIsNothingReceived() {
            play();
            long frames = conn.snapshotsReceived();
            server.receiveFailures = 1;
            server.sendFailures = 1;
            frame();
            ConnectionState during = conn.state();
            for (int i = 0; i < 10; i++) {
                frame();
            }
            assertAll(
                () -> assertEquals(2, conn.ioErrors(), "one receive and one send error counted"),
                () -> assertEquals(ConnectionState.PLAYING, during, "still playing"),
                () -> assertTrue(conn.snapshotsReceived() > frames, "frames flow again afterwards"),
                () -> assertEquals(List.of(), server.violations, "nothing the server rejects"));
        }

        @Test
        void aFailedLoginSendIsResentByItsTimer() {
            server.sendFailures = 0;
            conn.start(T0);
            server.step();
            at(T0 + 1);
            server.sendFailures = 1; // the first VERIFY is lost to the error
            at(T0 + 2);
            int afterFailure = server.verifies;
            for (int i = 1; i <= 3 && conn.phase() == XpConnection.Phase.VERIFY; i++) {
                at(T0 + 2 + i * XpConnection.RESEND_NANOS);
            }
            assertAll(
                () -> assertEquals(0, afterFailure, "the verify that failed never reached the server"),
                () -> assertTrue(server.verifies >= 1, "the resend timer sent it again"),
                () -> assertEquals(1, conn.ioErrors(), "one error"));
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
