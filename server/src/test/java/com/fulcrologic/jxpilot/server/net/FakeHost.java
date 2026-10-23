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

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Spy {@link GameHost}: records every call as a readable line and lets tests script answers
 * (join results, snapshot part counts, events to drain). Recording allocates, so the allocation
 * test uses {@code record = false}.
 */
final class FakeHost implements GameHost {
    final List<String> calls = new ArrayList<>();
    boolean record = true;
    /** Next join answer; negative = reject. When null, ids are handed out from 0 upward. */
    Integer joinAnswer;
    int nextId;
    int team = Protocol.NO_TEAM;
    /** Parts the next snapshot of each viewer needs (default 1). */
    int partsNeeded = 1;
    /** Messages queued by drainEvents for each viewer on the next drain. */
    final List<String> pendingChat = new ArrayList<>();
    /** Snapshot SELF x per viewer is 1000 + id, y is 2000 + id. */
    int ticks;
    int lastTick;
    long inputCount;
    long keySum;
    private final Msg.PlayerJoin join = new Msg.PlayerJoin();
    private final Msg.Text text = new Msg.Text();

    private void rec(String s) {
        if (record) {
            calls.add(s);
        }
    }

    List<String> callsStartingWith(String prefix) {
        List<String> out = new ArrayList<>();
        for (String c : calls) {
            if (c.startsWith(prefix)) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public int join(CharSequence name, int t, CharSequence shape) {
        rec("join " + name + " team=" + t + " shape=" + shape);
        if (joinAnswer != null) {
            return joinAnswer;
        }
        return nextId++;
    }

    @Override
    public int team(int playerId) {
        return team;
    }

    @Override
    public void leave(int playerId) {
        rec("leave " + playerId);
    }

    @Override
    public void clientMessage(int playerId, int type, ByteBuffer payload) {
        if (type == com.fulcrologic.jxpilot.common.net.ReliableType.CHAT) {
            text.read(payload);
            rec("msg " + playerId + " CHAT " + text.text);
        } else {
            rec("msg " + playerId + " type=" + type + " len=" + payload.remaining());
        }
    }

    @Override
    public void input(int playerId, int seq, long keys, int aimTurn) {
        inputCount++;
        keySum += keys;
        rec("input " + playerId + " seq=" + seq + " keys=" + keys + " aim=" + aimTurn);
    }

    @Override
    public void tick(int tick) {
        ticks++;
        lastTick = tick;
    }

    @Override
    public double viewX(int viewerId) {
        return 1000 + viewerId;
    }

    @Override
    public double viewY(int viewerId) {
        return 2000 + viewerId;
    }

    @Override
    public boolean fillSnapshot(int viewerId, int part, boolean radar, SnapshotWriter w) {
        rec("snapshot " + viewerId + " part=" + part + " radar=" + radar);
        if (w.beginSection(Section.SELF)) {
            Records.Self.put(w, viewerId, part, 1000 + viewerId, 2000 + viewerId, 0, 0, 0, 0, 0, Protocol.NO_ID, 0, 0, 0);
            w.endSection();
        }
        if (radar && part == 0 && w.beginSection(Section.RADAR)) {
            Records.Radar.put(w, 1000 + viewerId, 2000 + viewerId, Records.Radar.FLAG_SELF);
            w.endSection();
        }
        return part + 1 < partsNeeded;
    }

    @Override
    public void initialState(int viewerId, ReliableChannel out) {
        rec("initialState " + viewerId);
        join.id = viewerId;
        join.flags = Msg.PlayerJoin.FLAG_SELF;
        join.name.setLength(0);
        join.name.append("p").append(viewerId);
        join.send(out);
    }

    @Override
    public void drainEvents(int viewerId, ReliableChannel out) {
        if (pendingChat.isEmpty()) {
            return;
        }
        for (String s : pendingChat) {
            text.kind = Msg.Text.KIND_SERVER;
            text.from = Protocol.NO_ID;
            text.text.setLength(0);
            text.text.append(s);
            text.send(out);
        }
        pendingChat.clear();
    }
}
