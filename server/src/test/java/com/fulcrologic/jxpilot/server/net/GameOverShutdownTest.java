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

import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The server stops by itself when the game asks for it (C End_game after the gameDuration shutdown delay). */
@DisplayName("Server stop on GameHost.shutdownDue")
class GameOverShutdownTest {
    /** Delegates; wants the server to stop from its {@code dueAt}-th tick on and counts the ticks it got. */
    static final class StoppingHost implements GameHost {
        private final GameHost d;
        private final int dueAt;
        volatile int ticks;

        StoppingHost(GameHost delegate, int dueAt) {
            this.d = delegate;
            this.dueAt = dueAt;
        }

        @Override public int join(CharSequence name, int team, CharSequence shape) { return d.join(name, team, shape); }
        @Override public int team(int playerId) { return d.team(playerId); }
        @Override public void leave(int playerId) { d.leave(playerId); }
        @Override public void clientMessage(int playerId, int type, ByteBuffer payload) { d.clientMessage(playerId, type, payload); }
        @Override public void input(int playerId, int seq, long keys, int aimTurn) { d.input(playerId, seq, keys, aimTurn); }
        @Override public void tick(int tick) { ticks++; d.tick(tick); }
        @Override public boolean shutdownDue() { return ticks >= dueAt; }
        @Override public double viewX(int viewerId) { return d.viewX(viewerId); }
        @Override public double viewY(int viewerId) { return d.viewY(viewerId); }
        @Override public boolean fillSnapshot(int viewerId, int part, boolean radar, SnapshotWriter w) { return d.fillSnapshot(viewerId, part, radar, w); }
        @Override public void initialState(int viewerId, ReliableChannel out) { d.initialState(viewerId, out); }
        @Override public void drainEvents(int viewerId, ReliableChannel out) { d.drainEvents(viewerId, out); }
    }

    @Test
    @DisplayName("the loop stops after the tick that asked, logs it, closes its channels and completes termination normally")
    void stops() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Server.Settings st = LoopbackIntegrationTest.settings(
            new LoopbackIntegrationTest.MutableInterfaces(LoopbackIntegrationTest.V4), log);
        StoppingHost[] host = new StoppingHost[1];
        st.factory = (o, m) -> host[0] = new StoppingHost(new StubGameHost(m, 4), 5);
        Server s = Server.start(new String[] {"--map", LoopbackIntegrationTest.MAP, "--port", "0", "--bind", "127.0.0.1"}, st);
        boolean failed;
        try {
            s.termination().get(10, TimeUnit.SECONDS);
            failed = s.termination().isCompletedExceptionally();
        } finally {
            s.close();
        }
        boolean f = failed;
        assertAll(
            () -> assertFalse(f, "a normal end"),
            () -> assertTrue(log.contains("game over: shutting down"), "logged"),
            () -> assertEquals(5, host[0].ticks, "no tick after the one that asked"),
            () -> assertTrue(s.bound().isEmpty(), "the channels are closed"));
    }
}
