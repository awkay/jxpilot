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

import java.nio.ByteBuffer;

/** A {@link GameHost} that delegates, then throws {@code failure} from its {@code dieAt}-th tick (tests). */
public final class DyingHost implements GameHost {
    private final GameHost d;
    private final int dieAt;
    private final RuntimeException failure;
    private int ticks;

    public DyingHost(GameHost delegate, int dieAt, RuntimeException failure) {
        this.d = delegate;
        this.dieAt = dieAt;
        this.failure = failure;
    }

    @Override public int join(CharSequence name, int team, CharSequence shape) { return d.join(name, team, shape); }
    @Override public int team(int playerId) { return d.team(playerId); }
    @Override public void leave(int playerId) { d.leave(playerId); }
    @Override public void clientMessage(int playerId, int type, ByteBuffer payload) { d.clientMessage(playerId, type, payload); }
    @Override public void input(int playerId, int seq, long keys, int aimTurn) { d.input(playerId, seq, keys, aimTurn); }

    @Override
    public void tick(int tick) {
        if (++ticks >= dieAt) {
            throw failure;
        }
        d.tick(tick);
    }

    @Override public double viewX(int viewerId) { return d.viewX(viewerId); }
    @Override public double viewY(int viewerId) { return d.viewY(viewerId); }
    @Override public boolean fillSnapshot(int viewerId, int part, boolean radar, SnapshotWriter w) { return d.fillSnapshot(viewerId, part, radar, w); }
    @Override public void initialState(int viewerId, ReliableChannel out) { d.initialState(viewerId, out); }
    @Override public void drainEvents(int viewerId, ReliableChannel out) { d.drainEvents(viewerId, out); }
}
