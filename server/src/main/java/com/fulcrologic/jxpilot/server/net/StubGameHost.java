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

import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;

import java.nio.ByteBuffer;

/**
 * A stand-in simulation so the server runs end to end before the real one is wired in. Players
 * float at the world centre and drift up while THRUST is held. Chat is echoed to everyone, and
 * joins and leaves are announced. Allocation free in steady state. Replaced by the adapter to the
 * real simulation.
 */
public final class StubGameHost implements GameHost {
    private static final long THRUST = ControlKey.THRUST.mask();
    private static final int EVENT_RING = 256;
    private static final int EV_JOIN = 1;
    private static final int EV_LEAVE = 2;
    private static final int EV_CHAT = 3;

    private final int capacity;
    private final boolean[] used;
    private final boolean[] ready;
    private final StringBuilder[] names;
    private final int[] teams;
    private final long[] keys;
    private final float[] x;
    private final float[] y;
    private final long[] cursor;
    private final float startX;
    private final float startY;

    private final int[] evType = new int[EVENT_RING];
    private final int[] evId = new int[EVENT_RING];
    private final StringBuilder[] evText = new StringBuilder[EVENT_RING];
    private long evNext;

    private final Msg.PlayerJoin join = new Msg.PlayerJoin();
    private final Msg.PlayerLeave leave = new Msg.PlayerLeave();
    private final Msg.Text text = new Msg.Text();

    public StubGameHost(WorldMap map, int capacity) {
        this.capacity = capacity;
        used = new boolean[capacity];
        ready = new boolean[capacity];
        names = new StringBuilder[capacity];
        teams = new int[capacity];
        keys = new long[capacity];
        x = new float[capacity];
        y = new float[capacity];
        cursor = new long[capacity];
        for (int i = 0; i < capacity; i++) {
            names[i] = new StringBuilder();
        }
        for (int i = 0; i < EVENT_RING; i++) {
            evText[i] = new StringBuilder();
        }
        startX = map.pixelWidth() / 2f;
        startY = map.pixelHeight() / 2f;
    }

    @Override
    public int join(CharSequence name, int team, CharSequence shape) {
        for (int i = 0; i < capacity; i++) {
            if (!used[i]) {
                used[i] = true;
                ready[i] = false;
                names[i].setLength(0);
                names[i].append(name);
                teams[i] = team <= 9 ? team : Protocol.NO_TEAM;
                keys[i] = 0;
                x[i] = startX;
                y[i] = startY;
                return i;
            }
        }
        return -Msg.Reject.REASON_FULL;
    }

    @Override
    public int team(int playerId) {
        return teams[playerId];
    }

    @Override
    public void leave(int playerId) {
        used[playerId] = false;
        if (ready[playerId]) {
            event(EV_LEAVE, playerId, null);
        }
        ready[playerId] = false;
    }

    @Override
    public void clientMessage(int playerId, int type, ByteBuffer payload) {
        if (type == ReliableType.READY && !ready[playerId]) {
            ready[playerId] = true;
            event(EV_JOIN, playerId, null);
        } else if (type == ReliableType.CHAT) {
            text.read(payload);
            event(EV_CHAT, playerId, text.text);
        }
    }

    @Override
    public void input(int playerId, int seq, long keyMask, int aimTurn) {
        keys[playerId] = keyMask;
    }

    @Override
    public void tick(int tick) {
        for (int i = 0; i < capacity; i++) {
            if (ready[i] && (keys[i] & THRUST) != 0) {
                y[i] += 1;
            }
        }
    }

    @Override
    public double viewX(int viewerId) {
        return x[viewerId];
    }

    @Override
    public double viewY(int viewerId) {
        return y[viewerId];
    }

    @Override
    public boolean fillSnapshot(int viewerId, int part, boolean radar, SnapshotWriter w) {
        if (w.beginSection(Section.SELF)) {
            Records.Self.put(w, viewerId, 0, x[viewerId], y[viewerId], 0, 0, 0, 1000, 1000,
                Protocol.NO_ID, 0, 0, 0);
            w.endSection();
        }
        if (radar && w.beginSection(Section.RADAR)) {
            for (int i = 0; i < capacity && w.hasRoom(); i++) {
                if (ready[i]) {
                    Records.Radar.put(w, x[i], y[i], i == viewerId ? Records.Radar.FLAG_SELF : 0);
                }
            }
            w.endSection();
        }
        return false;
    }

    @Override
    public void initialState(int viewerId, ReliableChannel out) {
        for (int i = 0; i < capacity; i++) {
            if (i == viewerId || ready[i]) {
                sendJoin(out, i, i == viewerId);
            }
        }
        cursor[viewerId] = evNext;
    }

    @Override
    public void drainEvents(int viewerId, ReliableChannel out) {
        long from = Math.max(cursor[viewerId], evNext - EVENT_RING);
        for (long e = from; e < evNext; e++) {
            int s = (int) (e % EVENT_RING);
            int id = evId[s];
            switch (evType[s]) {
                case EV_JOIN -> {
                    if (id != viewerId) {
                        sendJoin(out, id, false);
                    }
                }
                case EV_LEAVE -> {
                    leave.id = id;
                    leave.send(out);
                }
                default -> {
                    text.kind = Msg.Text.KIND_CHAT;
                    text.from = id;
                    text.text.setLength(0);
                    text.text.append(evText[s]);
                    text.send(out);
                }
            }
        }
        cursor[viewerId] = evNext;
    }

    private void sendJoin(ReliableChannel out, int id, boolean self) {
        join.id = id;
        join.team = teams[id];
        join.flags = self ? Msg.PlayerJoin.FLAG_SELF : 0;
        join.name.setLength(0);
        join.name.append(names[id]);
        join.shape.setLength(0);
        join.send(out);
    }

    private void event(int type, int id, CharSequence msg) {
        int s = (int) (evNext % EVENT_RING);
        evType[s] = type;
        evId[s] = id;
        evText[s].setLength(0);
        if (msg != null) {
            evText[s].append(msg);
        }
        evNext++;
    }
}
