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

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.net.ConnectionListener;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.xp.ConnectTarget;
import com.fulcrologic.jxpilot.client.xp.GameConnection;
import com.fulcrologic.jxpilot.client.xp.GameConnections;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * One real client for end-to-end tests: the production stack ({@link GameConnections#open} → native
 * {@code ClientConnection} over UDP → {@link ClientGame}), plus a recorder of every decoded reliable message so
 * tests can assert what crossed the wire, independently of the client's own models.
 */
final class E2eClient implements ConnectionListener, AutoCloseable {
    /** A PLAYER_JOIN as received. */
    record Join(int id, int team, int flags, String name) {}

    /** A SCORE_OBJECT as received. */
    record ScoreObject(float score, int bx, int by, String text) {}

    /** A MESSAGE as received. */
    record Text(int kind, int from, String text) {}

    /** A GAME_EVENT as received. */
    record GameEvent(int kind, int a, int b, float value) {}

    /** A BASE_OWNER as received. */
    record BaseOwner(int base, int player) {}

    final ClientGame game = new ClientGame();
    final GameConnection conn;
    final String name;
    final List<Join> joins = new ArrayList<>();
    final List<Integer> leaves = new ArrayList<>();
    final List<ScoreObject> scoreObjects = new ArrayList<>();
    final List<Text> texts = new ArrayList<>();
    final List<GameEvent> gameEvents = new ArrayList<>();
    final List<BaseOwner> baseOwners = new ArrayList<>();
    int scores;
    long firstTick = -1;
    private long last = Long.MIN_VALUE;

    private final Msg.PlayerJoin join = new Msg.PlayerJoin();
    private final Msg.PlayerLeave leave = new Msg.PlayerLeave();
    private final Msg.ScoreObject so = new Msg.ScoreObject();
    private final Msg.Text text = new Msg.Text();
    private final Msg.GameEvent ge = new Msg.GameEvent();
    private final Msg.BaseOwner bo = new Msg.BaseOwner();

    E2eClient(int port, String name, int seed) throws IOException {
        this("127.0.0.1", port, name, seed);
    }

    E2eClient(String host, int port, String name, int seed) throws IOException {
        this.name = name;
        this.conn = GameConnections.open(new ConnectTarget(ConnectTarget.Protocol.JXPILOT,
            new com.fulcrologic.jxpilot.client.net.Endpoint(host, port)), this, name, Protocol.NO_TEAM, null, seed);
    }

    void start(long now) {
        conn.start(now);
    }

    /** One client frame: receive, update the game, send this frame's keys, flush reliable data. */
    void step(long now, long keys) {
        double dt = last == Long.MIN_VALUE ? 0 : (now - last) / 1e9;
        last = now;
        conn.update(now);
        game.frame(now, dt);
        conn.sendInput(keys, 0, now);
        conn.flush(now);
        if (firstTick < 0 && game.world().hasSnapshot()) {
            firstTick = game.world().newestTick();
        }
    }

    void chat(String line, long now) {
        conn.sendChat(line, now);
    }

    boolean playing() {
        return conn.state() == ConnectionState.PLAYING;
    }

    int selfId() {
        return game.playerId();
    }

    // ================================================================ ConnectionListener (record, then delegate)

    @Override
    public void onAccepted(int playerId) {
        game.onAccepted(playerId);
    }

    @Override
    public void onWelcome(Msg.Welcome w) {
        game.onWelcome(w);
    }

    @Override
    public void onConfig(Msg.Welcome w, Options options, WorldMap map) {
        game.onConfig(w, options, map);
    }

    @Override
    public void onReliable(int type, ByteBuffer payload) {
        ByteBuffer p = payload.duplicate();
        switch (type) {
            case ReliableType.PLAYER_JOIN -> {
                join.read(p);
                joins.add(new Join(join.id, join.team, join.flags, join.name.toString()));
            }
            case ReliableType.PLAYER_LEAVE -> {
                leave.read(p);
                leaves.add(leave.id);
            }
            case ReliableType.SCORE_OBJECT -> {
                so.read(p);
                scoreObjects.add(new ScoreObject(so.score, so.bx, so.by, so.text.toString()));
            }
            case ReliableType.MESSAGE -> {
                text.read(p);
                texts.add(new Text(text.kind, text.from, text.text.toString()));
            }
            case ReliableType.GAME_EVENT -> {
                ge.read(p);
                gameEvents.add(new GameEvent(ge.kind, ge.a, ge.b, ge.value));
            }
            case ReliableType.BASE_OWNER -> {
                bo.read(p);
                baseOwners.add(new BaseOwner(bo.baseIndex, bo.playerId));
            }
            case ReliableType.SCORE -> scores++;
            default -> { }
        }
        game.onReliable(type, payload);
    }

    @Override
    public void onSnapshot(ByteBuffer body, long nowNanos) {
        game.onSnapshot(body, nowNanos);
    }

    @Override
    public void onStateChange(ConnectionState state, CharSequence status) {
        game.onStateChange(state, status);
    }

    @Override
    public void close() {
        conn.close();
    }
}
