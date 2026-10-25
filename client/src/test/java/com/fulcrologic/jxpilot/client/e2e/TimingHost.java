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
package com.fulcrologic.jxpilot.client.e2e;

import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.server.net.GameHost;

import java.nio.ByteBuffer;

/**
 * A {@link GameHost} decorator that records when each tick starts (System.nanoTime) into a preallocated
 * array, so a test can measure the server's tick timing without disturbing it. Everything else is delegated.
 */
final class TimingHost implements GameHost {
    final GameHost host;
    final long[] tickNanos;
    volatile int ticks;

    TimingHost(GameHost host, int capacity) {
        this.host = host;
        this.tickNanos = new long[capacity];
    }

    @Override
    public void attach(Sessions sessions) {
        host.attach(sessions);
    }

    @Override
    public int join(CharSequence name, int team, CharSequence shape) {
        return host.join(name, team, shape);
    }

    @Override
    public int team(int playerId) {
        return host.team(playerId);
    }

    @Override
    public void leave(int playerId) {
        host.leave(playerId);
    }

    @Override
    public void clientMessage(int playerId, int type, ByteBuffer payload) {
        host.clientMessage(playerId, type, payload);
    }

    @Override
    public void input(int playerId, int tick, long keys, long taps, int aimTurn) {
        host.input(playerId, tick, keys, taps, aimTurn);
    }

    @Override
    public void inputLead(int playerId, int ticks) {
        host.inputLead(playerId, ticks);
    }

    @Override
    public void kernelVersion(int version) {
        host.kernelVersion(version);
    }

    @Override
    public void tick(int tick) {
        int n = ticks;
        if (n < tickNanos.length) {
            tickNanos[n] = System.nanoTime();
            ticks = n + 1;
        }
        host.tick(tick);
    }

    @Override
    public double viewX(int viewerId) {
        return host.viewX(viewerId);
    }

    @Override
    public double viewY(int viewerId) {
        return host.viewY(viewerId);
    }

    @Override
    public boolean fillSnapshot(int viewerId, int part, boolean radar, SnapshotWriter w) {
        return host.fillSnapshot(viewerId, part, radar, w);
    }

    @Override
    public void initialState(int viewerId, ReliableChannel out) {
        host.initialState(viewerId, out);
    }

    @Override
    public void drainEvents(int viewerId, ReliableChannel out) {
        host.drainEvents(viewerId, out);
    }
}
