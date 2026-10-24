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
import com.fulcrologic.jxpilot.client.net.PacketStats;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("XpConnection packet meters (C Net_measurement, Net_lag_measurement, packet_size)")
class XpPacketStatsTest {
    static final long FRAME = 1_000_000_000L / 60;
    final FakeCServer server = new FakeCServer();
    final ClientGame game = new ClientGame();
    final XpConnection conn;
    long now = 5_000_000_000L;
    long keys;
    int maxLoss;
    int maxDrop;

    XpPacketStatsTest() {
        XpConnection.Login login = new XpConnection.Login();
        login.nick = "pilot";
        login.realName = "tester";
        conn = new XpConnection(server, game, "127.0.0.1:15345", login);
    }

    void frame() {
        now += FRAME;
        conn.update(now);
        game.frame(now, FRAME / 1e9);
        conn.sendInput(keys, 0, now);
        conn.flush(now);
        server.step();
        maxLoss = Math.max(maxLoss, conn.packetStats().loss());
        maxDrop = Math.max(maxDrop, conn.packetStats().drop());
    }

    void play() {
        conn.start(now);
        server.step();
        for (int i = 0; i < 400 && conn.snapshotsReceived() < 5; i++) {
            frame();
        }
        assertEquals(ConnectionState.PLAYING, conn.state(), "fixture reaches PLAYING");
    }

    @Test
    @DisplayName("lost frames count as loss, a late copy of a lost frame as a drop; size and rate follow the frames")
    void lossDropAndSize() {
        play();
        PacketStats st = conn.packetStats();
        while (conn.lastLoops() % 12 != 2) {
            frame();
        }
        int quiet = maxLoss + maxDrop;
        int lost = conn.lastLoops() + 2;
        server.dropToClient = 3;
        frame();
        frame();
        frame();
        frame();
        // a stale copy of one of the lost frames (C Receive_start: last_loops >= loops -> PACKET_DROP)
        server.login.deliver(new CPackets().start(lost, 0).end(lost).bytes());
        for (int i = 0; i < 30; i++) {
            frame();
        }
        assertAll(
            () -> assertEquals(0, quiet, "no loss or drop while every frame arrives"),
            () -> assertEquals(2, maxLoss, "three frames lost in one window, one of them later counted as dropped"),
            () -> assertEquals(1, maxDrop, "the stale copy is a drop"),
            () -> assertEquals(12, st.rate(), "window of the server's 12 fps"),
            () -> assertTrue(st.size() > 50 && st.size() < 600, "the frame datagram size (" + st.size() + " bytes)"),
            () -> assertSame(st, conn.packetStats(), "one stats object per connection"));
    }

    @Test
    @DisplayName("lag: frames from a keyboard change's first send to the newest frame drawn when its ack arrived")
    void lag() {
        server.mapUpdates = false;
        play();
        keys = ControlKey.THRUST.mask();
        for (int i = 0; i < 10; i++) {
            frame();
        }
        int before = conn.packetStats().lag();
        server.dropToServer = 6;
        keys = 0;
        for (int i = 0; i < 10; i++) {
            frame();
        }
        int lag = conn.packetStats().lag();
        assertAll(
            () -> assertEquals(0, before, "acked in the next frame: the frame it was sent at was the newest drawn: 0"),
            () -> assertEquals(6, lag, "the release is sent at frame L and resent each frame; the first 6 sends are"
                + " lost, the one at L + 6 is acked in frame L + 7 while L + 6 was the newest drawn: 6 (C's store reuses"
                + " acked entries first, so the newest change decides)"));
    }
}
