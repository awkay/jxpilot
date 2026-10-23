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

import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.game.MessageLog;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Headless client against the scripted fake server over the in-memory link (simulated time). */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ClientSimTest {
    static final long FRAME = 1_000_000_000L / 60;
    static final long KEYS = ControlKey.THRUST.mask() | ControlKey.TURN_LEFT.mask();

    /** Client + server + link in lock step: one 60 Hz server tick per client frame. */
    static final class Rig {
        final FakeLink link;
        final FakeServer server;
        final ClientSim sim;
        long now = 1_000_000_000L;

        Rig(long seed) {
            link = new FakeLink(seed);
            server = new FakeServer(link.server());
            sim = new ClientSim(link.client(), "fake:15345", "Tester", 0, 42);
            sim.setKeys(f -> KEYS);
            sim.start(now);
        }

        void frames(int n) throws IOException {
            for (int i = 0; i < n; i++) {
                now += FRAME;
                server.poll(now);
                server.step(now);
                sim.step(now);
            }
        }

        ClientConnection conn() {
            return sim.connection();
        }
    }

    @Nested
    @DisplayName("joining a clean link")
    class Join {
        @Test
        @DisplayName("handshake, map transfer, players and snapshots arrive; input flows back")
        void joins() throws IOException {
            Rig r = new Rig(1);
            r.server.burstAtSnapshot = 30;
            r.frames(180);
            ClientWorld w = r.sim.game().world();
            assertAll(
                () -> assertEquals(ConnectionState.PLAYING, r.conn().state(), "the client is playing"),
                () -> assertEquals(FakeServer.PLAYER_ID, r.conn().playerId(), "player id from ACCEPT"),
                () -> assertEquals(FakeServer.SESSION, r.conn().session(), "session from the ACCEPT header"),
                () -> assertEquals("Fake Arena", r.sim.game().map().name(), "the map came through the config"),
                () -> assertEquals(700, (int) w.worldWidth(), "world width from the transferred map"),
                () -> assertTrue(w.wraps(), "wrap mode from the transferred map"),
                () -> assertTrue(r.server.ready, "READY reached the server"),
                () -> assertEquals(1, r.server.accepts, "one ACCEPT was needed"),
                () -> assertEquals(2, r.sim.game().players().count(), "self and the robot joined"),
                () -> assertEquals(FakeServer.PLAYER_ID, r.sim.game().players().selfId(), "self id from FLAG_SELF"),
                () -> assertTrue(r.conn().snapshotsReceived() > 60, "snapshots flow while playing"),
                () -> assertEquals(r.server.tick - r.server.tick % 2, w.newestTick(), "newest tick is the last sent"),
                () -> assertEquals(1, w.shipCount(), "the other ship is interpolated (self excluded)"),
                () -> assertEquals(FakeServer.OTHER_ID, w.shipId(0), "the interpolated ship is the robot"),
                () -> assertTrue(w.hasSelf(), "own ship from SELF"),
                () -> assertEquals(2, w.radarCount(), "radar blips from the last radar snapshot"),
                () -> assertEquals(2, w.shotCount(), "both shots"),
                () -> assertTrue(w.particles().count() > 0, "thrust sparks of the robot exist"),
                () -> assertEquals(KEYS, r.server.lastKeys, "the key mask reached the server"),
                () -> assertTrue(r.server.ackTick > 0, "INPUT acknowledges snapshot ticks"),
                () -> assertTrue(r.sim.digest().startsWith("state=PLAYING player=7 map=Fake Arena 20x10"),
                    "digest summarises: " + r.sim.digest()));
        }
    }

    @Nested
    @DisplayName("lossy, reordering link")
    class Lossy {
        @Test
        @DisplayName("joins and delivers reliable messages exactly once in order despite 20% loss and reordering")
        void reliableOrder() throws IOException {
            Rig r = new Rig(7);
            r.link.lossToClient = 0.2;
            r.link.lossToServer = 0.2;
            r.link.reorderToClient = 0.3;
            r.frames(600);
            assertEquals(ConnectionState.PLAYING, r.conn().state(), "joined despite loss: " + r.sim.game().status());
            for (int i = 1; i <= 8; i++) {
                r.server.message(Msg.Text.KIND_SERVER, "event " + i);
                r.server.score(FakeServer.PLAYER_ID, i);
            }
            r.frames(600);
            MessageLog log = r.sim.game().messages();
            List<String> got = new ArrayList<>();
            for (int i = log.gameCount() - 1; i >= 0; i--) {
                got.add(log.game(i).text().toString());
            }
            assertAll(
                () -> assertEquals(List.of("event 1", "event 2", "event 3", "event 4", "event 5", "event 6",
                    "event 7", "event 8"), got, "messages arrive once each, in order"),
                () -> assertEquals(8f, r.sim.game().players().score(
                    r.sim.game().players().slotOf(FakeServer.PLAYER_ID)), "the last SCORE wins"),
                () -> assertEquals("Fake Arena", r.sim.game().map().name(), "the map survived loss"),
                () -> assertTrue(r.conn().snapshotsReceived() > 300, "most snapshots arrive"));
        }
    }

    @Nested
    @DisplayName("failure paths")
    class Failures {
        @Test
        @DisplayName("a REJECT closes the connection with the server's reason")
        void reject() throws IOException {
            Rig r = new Rig(1);
            r.server.reject = true;
            r.frames(10);
            assertAll(
                () -> assertEquals(ConnectionState.CLOSED, r.conn().state(), "closed"),
                () -> assertEquals("Rejected by fake:15345: server full", r.sim.game().status().toString(),
                    "status names the reason"));
        }

        @Test
        @DisplayName("a silent link times out after 10 s and reconnects with a new nonce")
        void timeoutReconnect() throws IOException {
            Rig r = new Rig(1);
            r.frames(120);
            assertEquals(ConnectionState.PLAYING, r.conn().state(), "playing before the cut");
            int firstNonce = r.server.nonce;
            r.link.cut = true;
            r.frames(60 * 10 + 5);
            String lost = r.sim.game().status().toString();
            ConnectionState during = r.conn().state();
            r.link.cut = false;
            r.frames(120);
            assertAll(
                () -> assertEquals(ConnectionState.CONNECTING, during, "connecting again after the timeout"),
                () -> assertEquals("Connection to fake:15345 lost (timeout), reconnecting...", lost, "status line"),
                () -> assertEquals(1, r.conn().reconnects(), "one reconnect"),
                () -> assertNotEquals(firstNonce, r.server.nonce, "the new CONNECT has a new nonce"),
                () -> assertEquals(2, r.server.accepts, "the server accepted twice"),
                () -> assertEquals(ConnectionState.PLAYING, r.conn().state(), "playing again"),
                () -> assertEquals(2, r.sim.game().players().count(), "players re-announced, not duplicated"));
        }

        @Test
        @DisplayName("quitting sends DISCONNECT three times")
        void quit() throws IOException {
            Rig r = new Rig(1);
            r.frames(120);
            r.conn().disconnect(r.now);
            r.server.poll(r.now);
            assertAll(
                () -> assertEquals(3, r.server.disconnectsReceived, "three DISCONNECT datagrams"),
                () -> assertEquals(ConnectionState.CLOSED, r.conn().state(), "closed"));
        }
    }
}
