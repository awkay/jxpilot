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
import com.fulcrologic.jxpilot.client.net.ConnectionListener;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The XPilot connection state machine against a scripted C server. */
class XpConnectionTest {
    static final long FRAME = 16_000_000L;
    FakeCServer server;
    Recorder rec;
    ClientGame game;
    XpConnection conn;
    XpConnection.Login login;
    long now;
    long keys;

    /** Fans every listener call out to the recorder and a real ClientGame. */
    static final class Both implements ConnectionListener {
        final ConnectionListener a;
        final ConnectionListener b;

        Both(ConnectionListener a, ConnectionListener b) {
            this.a = a;
            this.b = b;
        }

        public void onAccepted(int id) {
            a.onAccepted(id);
            b.onAccepted(id);
        }

        public void onWelcome(Msg.Welcome w) {
            a.onWelcome(w);
            b.onWelcome(w);
        }

        public void onConfig(Msg.Welcome w, Options o, WorldMap m) {
            a.onConfig(w, o, m);
            b.onConfig(w, o, m);
        }

        public void onReliable(int t, ByteBuffer p) {
            int pos = p.position();
            a.onReliable(t, p);
            p.position(pos);
            b.onReliable(t, p);
        }

        public void onSnapshot(ByteBuffer body, long n) {
            int pos = body.position();
            a.onSnapshot(body, n);
            body.position(pos);
            b.onSnapshot(body, n);
        }

        public void onStateChange(ConnectionState s, CharSequence st) {
            a.onStateChange(s, st);
            b.onStateChange(s, st);
        }

        @Override
        public void onIdentity(int id, CharSequence real, CharSequence host) {
            a.onIdentity(id, real, host);
            b.onIdentity(id, real, host);
        }
    }

    @BeforeEach
    void setUp() {
        server = new FakeCServer();
        rec = new Recorder();
        game = new ClientGame();
        login = new XpConnection.Login();
        login.nick = "pilot";
        login.realName = "tester";
        login.team = 4;
        conn = new XpConnection(server, new Both(rec, game), "127.0.0.1:15345", login);
        now = 5_000_000_000L;
    }

    /** One client frame then one server step. */
    void frame() {
        now += FRAME;
        conn.update(now);
        game.frame(now, FRAME / 1e9);
        conn.sendInput(keys, 0, now);
        conn.flush(now);
        server.step();
    }

    void run(int frames) {
        for (int i = 0; i < frames; i++) {
            frame();
        }
    }

    void start() {
        conn.start(now);
        server.step();
    }

    void play() {
        start();
        for (int i = 0; i < 400 && conn.snapshotsReceived() < 5; i++) {
            frame();
        }
        assertEquals(ConnectionState.PLAYING, conn.state(), "fixture reaches PLAYING");
    }

    /** Advance time without traffic handling on the server side (it stays silent). */
    void idle(long nanos) {
        long end = now + nanos;
        while (now < end) {
            now += 100_000_000L;
            conn.update(now);
            server.step();
        }
    }

    @Nested
    @DisplayName("joining")
    class Joining {
        @Test
        @DisplayName("with sound on, the play bundle asks for audio (C Send_audio_request(1))")
        void audioRequested() {
            login.audio = true;
            play();
            assertTrue(server.loginPackets.contains("audio 1"), "PKT_REQUEST_AUDIO 1 is sent");
        }

        @Test
        @DisplayName("the modifier banks go with every play request after PKT_DISPLAY (C Client_power's Send_modifier_bank)")
        void modifierBanks() {
            conn.setModifierBanks(new String[] {"FN C", "", "X4 LS", "x".repeat(100)});
            play();
            int display = server.loginPackets.indexOf("display 1024x1024 colors=0 spark=64");
            assertAll(
                () -> assertEquals(List.of("modbank 0=FN C", "modbank 1=", "modbank 2=X4 LS", "modbank 3=" + "x".repeat(79)),
                    server.loginPackets.subList(display + 2, display + 6),
                    "banks 0..3 in order after PKT_DISPLAY and the MOTD request, a long text cut to MAX_CHARS - 1"),
                () -> assertEquals("FN C", login.modifierBanks[0], "remembered in the login for later joins"),
                () -> assertEquals(List.of(), server.violations, "nothing the C server would reject"));
        }

        @Test
        @DisplayName("the shape is sent as C Send_shape: converted, canonical buf, then ext")
        void shapeSentCanonically() {
            String defaultWire = "(SH: 15,0 -9,8 -9,-8)(EN: -9,0)(MG: 15,0)(LL: -9,8)(RL: -9,-8)(MR: 15,0)";
            play();
            List<List<String>> empty = List.copyOf(server.shapes);
            setUp();
            login.shape = "(SH: 1,1 2,2 3,3)(LG: 7,7)";
            play();
            List<List<String>> tiny = List.copyOf(server.shapes);
            setUp();
            String g = " -2147483648,-2147483648".repeat(3) + ")";
            login.shape = "(SH: 15,0 -9,8 -9,-8)(LG:" + g + "(RG:" + g + "(LR:" + g + "(RR:" + g;
            play();
            List<String> big = server.shapes.get(0);
            assertAll(
                () -> assertEquals(List.of(List.of(defaultWire, "")), empty, "no shape: the default ship's string"),
                () -> assertEquals(List.of(List.of("(SH: 1,1 2,2 3,3)(EN: 1,0)(MG: 3,3)(LG: 7,7)(LL: 3,3)(RL: 1,1)(MR: 3,3)",
                    "")), tiny, "converted without limits, written canonically"),
                () -> assertEquals(List.of("(SH: 15,0 -9,8 -9,-8)(EN: -9,0)(MG: 15,0)(LG:" + g + "(RG:" + g
                    + "(LL: -9,8)(RL: -9,-8)(MR: 15,0)", "(LR:" + g + "(RR:" + g), big,
                    "groups that overflow the shape string travel in the extension"));
        }

        @Test
        void fullHandshakeReachesPlaying() {
            play();
            assertAll(
                () -> assertEquals(List.of(
                        "CONNECTING: Contacting 127.0.0.1:15345...",
                        "CONNECTING: Joining 127.0.0.1:15345...",
                        "CONNECTING: Queued at position 1 on 127.0.0.1:15345",
                        "JOINING: Receiving map from 127.0.0.1:15345...",
                        "PLAYING: Playing on 127.0.0.1:15345"), rec.states, "state sequence"),
                () -> assertEquals(List.of(XpProto.CONTACT_PACK + " v4501 real=tester",
                    XpProto.ENTER_QUEUE_PACK + " v4501 real=tester"), server.contactRequests,
                    "CONTACT then ENTER_QUEUE with the negotiated version"),
                () -> assertEquals("Pilot", server.nick, "nick capitalised (C Fix_nick_name)"),
                () -> assertEquals(4, server.team, "team sent"),
                () -> assertEquals("Pilot", server.verifiedNick, "verify carries the nick"),
                () -> assertEquals(FakeCServer.LOGIN_PORT, conn.loginPort(), "login port from ENTER_GAME"),
                () -> assertTrue(server.contact.closed, "contact socket closed after the verify"),
                () -> assertEquals(0x1234ABCD, conn.magic(), "connection magic"),
                () -> assertEquals(List.of("shape", "play", "power34=11520", "power35=8960", "power36=8960",
                    "power37=6400", "power38=30", "power39=30", "display 1024x1024 colors=0 spark=64", "motd 0 30720",
                    "modbank 0=", "modbank 1=", "modbank 2=", "modbank 3=", "audio 0", "fps 12"), server.loginPackets,
                    "C Net_start bundle: values * 256, display, the MOTD request (C Client_power), the 4 (empty)"
                    + " modifier banks, audio off, fps"),
                () -> assertEquals(1, server.plays, "one play request"),
                () -> assertEquals(List.of(1), server.keyboardChanges, "keyboard announced once (Net_key_change)"),
                () -> assertEquals(List.of(), server.violations, "nothing the C server would reject"),
                () -> assertEquals(0, conn.malformed(), "nothing malformed"),
                () -> assertEquals(FakeCServer.SELF_ID, conn.playerId(), "own id"),
                () -> assertEquals(XpProto.MY_VERSION, conn.version(), "version"),
                () -> assertEquals(XpConnection.Phase.PLAYING, conn.phase(), "phase"),
                () -> assertNotNull(conn.map(), "map built"),
                () -> assertEquals(12, conn.setup().fps, "setup fps"));
        }

        @Test
        void theListenerSeesTheSameModelAsNative() {
            play();
            run(5);
            assertAll(
                () -> assertEquals("accepted 65535", rec.events.get(0), "session start"),
                () -> assertEquals("welcome simHz=12 snapHz=12 fps=12 flags=" + (Msg.Welcome.FLAG_EDGE_WRAP | Msg.Welcome.FLAG_RADAR_BLINKED)
                    + " server=127.0.0.1:15345", rec.events.get(1), "one tick per C frame"),
                () -> assertEquals("config Fake Map 100x100", rec.events.get(2), "map"),
                () -> assertEquals("accepted 3", rec.events.get(3), "self id when the server announces it"),
                () -> assertTrue(rec.events.contains("message kind=4 from=65535 Welcome to \"Fake Map\", made by Fake Author."),
                    "server message"),
                () -> assertEquals(FakeCServer.SELF_ID, game.players().selfId(), "ClientGame knows us"),
                () -> assertEquals(2, game.players().count(), "two players"),
                () -> assertTrue(game.world().hasSelf(), "own ship drawn"),
                () -> assertEquals(1, game.world().shipCount(), "robot visible"),
                () -> assertEquals(FakeCServer.ROBOT_ID, game.world().shipId(0), "robot id"),
                () -> assertEquals(conn.snapshotsReceived(), game.snapshotCount(), "every frame is a snapshot"),
                () -> assertEquals(0, game.world().malformedSnapshots(), "snapshots decode"),
                () -> assertTrue(server.mapAcks > 0, "fuel updates acked"),
                () -> assertEquals(server.loops - 1, conn.lastLoops(), "last frame received (the server just sent the next)"));
        }

        @Test
        void olderServersAreUsedWithTheirVersion() {
            server.version = 0x4401;
            play();
            assertAll(
                () -> assertEquals(0x4401, conn.version(), "min(server, ours)"),
                () -> assertEquals(0x4401, server.queueVersion, "ENTER_QUEUE uses the server's version"));
        }

        @Test
        void tooOldServersAreRefused() {
            server.version = 0x4202;
            start();
            run(3);
            assertAll(
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "closed"),
                () -> assertEquals("Server 127.0.0.1:15345 runs protocol 0x4202; at least 0x4203 is needed",
                    conn.status().toString(), "reason"));
        }

        @Test
        void contactAndQueueErrorsClose() {
            server.contactStatus = XpProto.E_VERSION;
            start();
            run(2);
            String a = conn.status().toString();
            setUp();
            server.queueStatus = XpProto.E_IN_USE;
            start();
            run(3);
            assertAll(
                () -> assertEquals("Server 127.0.0.1:15345 refused: incompatible version", a, "contact error"),
                () -> assertEquals("Server 127.0.0.1:15345 refused: nick already in use", conn.status().toString(),
                    "queue error"),
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "closed"));
        }

        @Test
        void contactIsResentEverySecondUntilGivingUp() {
            server.silentContact = true;
            start();
            idle(XpConnection.SECOND * (XpConnection.MAX_TRIES + 1));
            assertAll(
                () -> assertEquals(XpConnection.MAX_TRIES, server.contactRequests.size(), "MAX_TRIES contacts"),
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "gave up"),
                () -> assertEquals("No answer from 127.0.0.1:15345", conn.status().toString(), "reason"));
        }

        @Test
        void queueIsRepeatedAndWaitsWhileTheServerAcks() {
            server.queueSteps = Integer.MAX_VALUE;
            server.queueAcks = true;
            start();
            idle(5 * XpConnection.SECOND);
            int requests = server.contactRequests.size();
            idle(200 * XpConnection.SECOND);
            ConnectionState longQueued = conn.state();
            server.queueAcks = false;
            idle(XpConnection.QUEUE_TIMEOUT_NANOS + XpConnection.SECOND);
            assertAll(
                () -> assertEquals(1, conn.queuePosition(), "queue position"),
                () -> assertEquals(1 + 3, requests, "contact, queue, then a repeat every 2 s"),
                () -> assertEquals(ConnectionState.CONNECTING, longQueued, "no cap while the server acks (C)"),
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "closed once the acks stop"),
                () -> assertEquals("No answer from 127.0.0.1:15345 (queued at 1)", conn.status().toString(),
                    "reason"));
        }

        @Test
        void verifyFailureAndSilence() {
            server.verifyResult = XpProto.PKT_FAILURE;
            play0();
            String failed = conn.status().toString();
            setUp();
            server.silentLogin = true;
            start();
            idle(XpConnection.SECOND * (XpConnection.MAX_TRIES + 2));
            assertAll(
                () -> assertEquals("Verification failed by 127.0.0.1:15345 (101)", failed, "PKT_FAILURE"),
                () -> assertEquals(XpConnection.MAX_TRIES, server.verifies, "verify resent every second"),
                () -> assertEquals("Can't connect to 127.0.0.1:15345 after 10 verify attempts",
                    conn.status().toString(), "gave up"));
        }

        void play0() {
            start();
            run(50);
        }

        @Test
        void playRefusedOrUnanswered() {
            server.playResult = XpProto.PKT_FAILURE;
            play0();
            String refused = conn.status().toString();
            setUp();
            server.ignorePlay = true;
            start();
            run(20);
            idle(XpConnection.SECOND * (XpConnection.MAX_TRIES + 1));
            assertAll(
                () -> assertEquals("Start play not allowed by 127.0.0.1:15345 (101)", refused, "refused"),
                () -> assertEquals(XpConnection.MAX_TRIES, server.plays, "play request resent every second"),
                () -> assertEquals(XpConnection.SHAPE_TRIES,
                    server.loginPackets.stream().filter("shape"::equals).count(), "shape only in the first 5"),
                () -> assertEquals(XpConnection.MAX_TRIES,
                    server.loginPackets.stream().filter(x -> x.startsWith("motd")).count(),
                    "the MOTD request is in every play request (C Client_power)"),
                () -> assertEquals("Can't start play on 127.0.0.1:15345 after 10 attempts", conn.status().toString(),
                    "gave up"));
        }

        @Test
        void lostSetupChunksAreRetransmitted() {
            server.chunk = 100;
            start();
            run(2);
            server.dropToClient = 3;
            for (int i = 0; i < 600 && conn.snapshotsReceived() < 3; i++) {
                frame();
            }
            assertAll(
                () -> assertEquals(ConnectionState.PLAYING, conn.state(), "recovered"),
                () -> assertEquals(0, conn.malformed(), "gaps are not malformed"),
                () -> assertEquals(List.of(), server.violations, "acks never ahead of the server"));
        }

        @Test
        void badSetupOrMapCloses() {
            server.customSetup = new CPackets().setupHeader(0, 0, 0, 10, 10, 12, 1, "n", "a").bytes();
            play0();
            String badHeader = conn.status().toString();
            setUp();
            server.customSetup = new CPackets().setupHeader(2, 0, 0, 10, 10, 12, 1, "n", "a").c(0x81).c(1).bytes();
            play0();
            assertAll(
                () -> assertEquals("Bad setup from 127.0.0.1:15345: bad map specs from server (0,10,10)", badHeader,
                    "header check"),
                () -> assertEquals("Bad map from 127.0.0.1:15345: map compress count error 1", conn.status().toString(),
                    "map decode"),
                () -> assertTrue(conn.malformed() > 0, "counted"));
        }

        @Test
        void socketFailureCloses() {
            server.openFailure = new IOException("no route");
            conn.start(now);
            assertAll(
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "closed"),
                () -> assertEquals("Cannot open socket to 127.0.0.1:15345: no route", conn.status().toString(), "why"));
        }

        @Test
        void nothingHappensBeforeStart() {
            conn.update(now);
            conn.flush(now);
            assertAll(
                () -> assertEquals(0, conn.sendInput(1, 0, now), "no input"),
                () -> assertFalse(conn.sendChat("x", now), "no chat"),
                () -> assertEquals(ConnectionState.IDLE, conn.state(), "idle"),
                () -> assertEquals(XpConnection.Phase.IDLE, conn.phase(), "idle phase"),
                () -> assertTrue(server.opened.isEmpty(), "no sockets"));
        }
    }

    @Nested
    @DisplayName("playing")
    class Playing {
        @Test
        void keyChangesAreSentOnceAndAcked() {
            play();
            int sentBefore = server.clientSent;
            keys = ControlKey.THRUST.mask() | ControlKey.TURN_LEFT.mask();
            frame();
            int afterChange = server.clientSent;
            run(3);
            keys = 0;
            run(3);
            assertAll(
                () -> assertEquals(List.of(1, 2, 3), server.keyboardChanges, "announce, press, release"),
                () -> assertEquals(0L, server.lastKeys, "released"),
                () -> assertTrue(afterChange > sentBefore, "sent at once"),
                () -> assertEquals(3, conn.keyboard().ack(), "server acked the last change"),
                () -> assertFalse(conn.keyboard().unacked(), "nothing outstanding"),
                () -> assertEquals(List.of(), server.violations, "never two keyboards per datagram"));
        }

        @Test
        void settingsBeforePlayGoWithThePlayAndWhilePlayingAtOnce() {
            Msg.Settings st = new Msg.Settings();
            st.turnSpeed = 40;
            boolean early = conn.sendSettings(st, now);
            play();
            int before = server.loginPackets.size();
            st.turnSpeed = 42;
            st.power = 49.5f;
            boolean sent = conn.sendSettings(st, now);
            server.step();
            List<String> later = List.copyOf(server.loginPackets.subList(before, server.loginPackets.size()));
            boolean again = conn.sendSettings(st, now);
            server.step();
            int afterAgain = server.loginPackets.size();
            assertAll(
                () -> assertFalse(early, "not sent before playing"),
                () -> assertTrue(server.loginPackets.contains("power36=10240"), "the join carried turn speed 40 * 256"),
                () -> assertTrue(sent, "sent while playing"),
                () -> assertEquals(List.of("power34=12672", "power36=10752"), later,
                    "only the changed values, one packet each (C: one Send_power / Send_turnspeed per key)"),
                () -> assertFalse(again, "the same settings again send nothing"),
                () -> assertEquals(before + 2, afterAgain, "no duplicate packets"),
                () -> assertEquals(42f, login.settings.turnSpeed, "and remembered for the next join"),
                () -> assertEquals(List.of(), server.violations, "nothing the C server would reject"));
        }

        @Test
        @DisplayName("the play request asks for the whole MOTD (C Client_power: Startup_server_motd)")
        void motdRequested() {
            play();
            assertEquals(List.of("motd 0 30720"),
                server.loginPackets.stream().filter(x -> x.startsWith("motd")).toList(),
                "one PKT_MOTD for the whole MOTD (offset 0, MAX_MOTD_SIZE)");
        }

        @Test
        void serverSeesTheKeyBits() {
            play();
            keys = ControlKey.THRUST.mask();
            frame();
            assertEquals(ControlKey.THRUST.mask(), server.lastKeys, "C vector >> 1 is the ControlKey mask");
        }

        @Test
        void sendInputReturnsTheChangeNumber() {
            play();
            int none = conn.sendInput(0, 0, now);
            int changed = conn.sendInput(ControlKey.FIRE_SHOT.mask(), 0, now);
            assertAll(
                () -> assertEquals(0, none, "unchanged keys: nothing sent"),
                () -> assertEquals(2, changed, "change 2 sent"));
        }

        @Test
        void aLostKeyboardPacketIsResentNextFrame() {
            play();
            server.dropToServer = 1;
            keys = ControlKey.SHIELD.mask();
            conn.sendInput(keys, 0, now);
            server.step();
            boolean lost = server.lastKeys == 0;
            run(3);
            assertAll(
                () -> assertTrue(lost, "first send lost"),
                () -> assertEquals(ControlKey.SHIELD.mask(), server.lastKeys, "resent after the next frame"),
                () -> assertFalse(conn.keyboard().unacked(), "acked"));
        }

        @Test
        void keepAliveAfterFiveSecondsOfFrames() {
            server.mapUpdates = false;
            play();
            run(3);
            int lastUpdate = conn.keyboard().lastUpdate();
            for (int i = 0; i < 5 * 12 + 3; i++) {
                frame();
            }
            assertTrue(conn.keyboard().lastUpdate() > lastUpdate + 5 * 12,
                "keyboard re-sent as a keep-alive after 5 * fps frames without a send");
        }

        @Test
        void chatGoesOutAndComesBack() {
            play();
            boolean sent = conn.sendChat("hi all", now);
            run(4);
            assertAll(
                () -> assertTrue(sent, "queued"),
                () -> assertEquals(List.of("hi all"), server.talk, "server got PKT_TALK once"),
                () -> assertTrue(rec.events.contains("message kind=4 from=65535 hi all [Pilot]"), "echo arrives"),
                () -> assertEquals(0, conn.talk().queued(), "acked by PKT_TALK_ACK"),
                () -> assertEquals(1, game.messages().talkCount(), "shown as talk"));
        }

        @Test
        void chatIsRefusedWhileNotPlaying() {
            server.ignorePlay = true;
            start();
            run(30);
            assertAll(
                () -> assertEquals(XpConnection.Phase.START, conn.phase(), "waiting for the play reply"),
                () -> assertFalse(conn.sendChat("too early", now), "refused"),
                () -> assertEquals(0, conn.sendInput(1, 0, now), "no keyboard before playing"),
                () -> assertEquals(List.of(), server.violations, "nothing sent the server would reject"));
        }

        @Test
        void silenceTimesOut() {
            play();
            server.silentFrames = true;
            idle(XpConnection.PLAY_TIMEOUT_NANOS + XpConnection.SECOND);
            assertAll(
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "closed"),
                () -> assertEquals("Connection to 127.0.0.1:15345 lost (timeout)", conn.status().toString(), "reason"));
        }

        @Test
        void setupSilenceTimesOutToo() {
            server.chunk = 10;
            start();
            run(3);
            server.silentLogin = true;
            idle(XpConnection.TIMEOUT_NANOS + XpConnection.SECOND);
            assertEquals("No answer from 127.0.0.1:15345 (timeout)", conn.status().toString(), "setup timeout");
        }

        @Test
        void disconnectSendsThreeQuits() {
            play();
            conn.disconnect(now);
            server.step();
            conn.close();
            assertAll(
                () -> assertEquals(3, server.quits, "C Net_cleanup sends PKT_QUIT repeatedly"),
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "closed"),
                () -> assertEquals("Disconnected from 127.0.0.1:15345", conn.status().toString(), "status"),
                () -> assertTrue(server.login.closed, "socket closed"));
        }

        @Test
        void disconnectBeforeTheLoginPortSendsNothing() {
            start();
            conn.disconnect(now);
            assertAll(
                () -> assertEquals(0, server.quits, "no login port, nothing to quit"),
                () -> assertTrue(server.contact.closed, "contact socket closed"));
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @Test
        void serverQuitPacketCloses() {
            play();
            server.login.toClient.add(new CPackets().quit("kicked by owner").bytes());
            frame();
            assertAll(
                () -> assertEquals(ConnectionState.CLOSED, conn.state(), "closed"),
                () -> assertEquals("Server 127.0.0.1:15345 closed the connection: kicked by owner",
                    conn.status().toString(), "reason"));
        }

        @Test
        void quitWithoutReason() {
            play();
            server.login.toClient.add(new byte[] {XpProto.PKT_QUIT, 'x'});
            frame();
            assertEquals("Server 127.0.0.1:15345 closed the connection: unknown reason", conn.status().toString(),
                "missing NUL");
        }

        @Test
        void reliableQuitCloses() {
            play();
            server.stream.put(new CPackets().quit("shutdown").bytes());
            run(3);
            assertEquals("Server 127.0.0.1:15345 closed the connection: shutdown", conn.status().toString(), "reason");
        }

        @Test
        void garbageIsCountedNeverThrown() {
            play();
            long before = conn.malformed();
            server.login.toClient.add(new byte[] {60, 1, 2});
            server.login.toClient.add(new byte[] {XpProto.PKT_SHIP, 1});
            server.login.toClient.add(new CPackets().start(3, 0).end(3).bytes());
            server.login.toClient.add(new CPackets().start(server.loops + 1, 99).end(server.loops + 1).bytes());
            server.login.toClient.add(new CPackets().c(XpProto.PKT_RELIABLE).hd(0).d(0).d(0).bytes());
            frame();
            run(3);
            assertAll(
                () -> assertEquals(1, conn.unknownPackets(), "unknown type 60"),
                () -> assertEquals(before + 4, conn.malformed(), "unknown + truncated ship + premature ack + bad chunk"),
                () -> assertEquals(2, conn.staleFrames(),
                    "old frame, and the server's real frame with the number the premature frame took"),
                () -> assertEquals(ConnectionState.PLAYING, conn.state(), "still playing"),
                () -> assertTrue(conn.snapshotsReceived() > 5, "frames keep flowing"));
        }

        @Test
        void badReliableDataCloses() {
            play();
            server.stream.put(new byte[] {(byte) 99, 0});
            run(3);
            assertAll(
                () -> assertEquals("Bad reliable data (type 99) from 127.0.0.1:15345", conn.status().toString(), "C fatal"),
                () -> assertEquals(1, conn.badStream(), "counted"));
        }

        @Test
        void contactGarbageIsCounted() {
            server.silentContact = true;
            start();
            server.contact.toClient.add(new byte[] {1, 2, 3});
            server.contact.toClient.add(new CPackets().contactReply(0x12345678, XpProto.CONTACT_PACK, 0, -1).bytes());
            frame();
            assertAll(
                () -> assertEquals(2, conn.malformed(), "short and wrong magic"),
                () -> assertEquals(XpConnection.Phase.CONTACT, conn.phase(), "still contacting"),
                () -> assertEquals(2, conn.packetsReceived(), "received"));
        }

        @Test
        void framesBeforeTheMapAreUnknown() {
            server.chunk = 10;
            start();
            run(3);
            server.login.toClient.add(new CPackets().start(1, 0).end(1).bytes());
            frame();
            assertEquals(1, conn.unknownPackets(), "dropped like C (not a reliable packet in setup)");
        }

        @Test
        void unexpectedContactRepliesAreIgnored() {
            play();
            assertDoesNotThrow(() -> conn.update(now), "contact closed: nothing read");
            assertEquals(0, conn.malformed(), "fine");
        }
    }

    @Test
    void clampView() {
        assertAll(
            () -> assertEquals(XpProto.MIN_VIEW_SIZE, XpConnection.clampView(10), "min"),
            () -> assertEquals(XpProto.MAX_VIEW_SIZE, XpConnection.clampView(5000), "max"),
            () -> assertEquals(800, XpConnection.clampView(800), "inside"));
    }

    @Test
    void nickDefaultsAndCapitalises() {
        login.nick = "";
        String empty = conn.nick();
        login.nick = "xy";
        assertAll(
            () -> assertEquals("Jxpilot", empty, "default nick"),
            () -> assertEquals("Xy", conn.nick(), "first letter upper case"));
    }
}
