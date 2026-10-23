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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.client.net.ConnectionListener;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableType;

import java.nio.ByteBuffer;

/**
 * Everything the client knows about the game, fed by a {@link com.fulcrologic.jxpilot.client.net.ClientConnection}
 * (it is the {@link ConnectionListener}): the {@link ClientWorld} (snapshots), {@link Players},
 * {@link Scoreboard}, {@link MessageLog}, {@link GameEvents}, the map and options, and the connection
 * status line. Reliable events are applied in arrival order. Call {@link #frame(long, double)} once
 * per rendered frame. Pure: no GL, no I/O; allocation free in steady state.
 */
public final class ClientGame implements ConnectionListener {
    /** C {@code SCORE_OBJECT_COUNT}: frames an own score change is shown on the HUD. */
    public static final int SCORE_MESSAGE_FRAMES = 100;

    private final Players players = new Players();
    private final ClientWorld world;
    private final Scoreboard scoreboard = new Scoreboard();
    private final MessageLog messages = new MessageLog();
    private final GameEvents events = new GameEvents(64);
    private final CharBuf status = new CharBuf(160);
    private final Msg.PlayerJoin join = new Msg.PlayerJoin();
    private final Msg.PlayerLeave leave = new Msg.PlayerLeave();
    private final Msg.PlayerTeam team = new Msg.PlayerTeam();
    private final Msg.PlayerThrust thrust = new Msg.PlayerThrust();
    private final Msg.Score score = new Msg.Score();
    private final Msg.TeamScore teamScore = new Msg.TeamScore();
    private final Msg.Text text = new Msg.Text();
    private final Msg.BaseOwner baseOwner = new Msg.BaseOwner();
    private final Msg.GameEvent gameEvent = new Msg.GameEvent();

    private ConnectionState state = ConnectionState.IDLE;
    private WorldMap map;
    private Options options;
    private int simHz = 60;
    private int snapshotHz = 30;
    private float gameFps = 14;
    private int welcomeFlags;
    private final CharBuf serverName = new CharBuf(64);
    private int playerId = Protocol.NO_ID;
    private long reliableCount;
    private long snapshotCount;

    private float scoreDelta;
    private double scoreDeltaLife;

    /** With default {@link ClientWorld} capacities. */
    public ClientGame() {
        world = new ClientWorld(players);
    }

    /** With explicit capacities (see {@link ClientWorld#ClientWorld(Players, int, int, int, int)}). */
    public ClientGame(int shipCap, int shotCap, int particleCap, int radarCap) {
        world = new ClientWorld(players, shipCap, shotCap, particleCap, radarCap);
    }

    public ClientWorld world() {
        return world;
    }

    // ================================================================ ConnectionListener

    /** BEHAVIOR: a new session forgets players, world state and events; the self id is the player id. */
    @Override
    public void onAccepted(int id) {
        playerId = id;
        players.clear();
        players.setSelfId(id);
        events.clear();
        if (world.configured()) {
            world.reset();
        }
    }

    /** BEHAVIOR: records rates, game fps, flags and the server name. */
    @Override
    public void onWelcome(Msg.Welcome w) {
        simHz = w.simHz > 0 ? w.simHz : 60;
        snapshotHz = w.snapshotHz > 0 ? w.snapshotHz : simHz / Protocol.DEFAULT_SNAPSHOT_DIVISOR;
        gameFps = w.gameFps > 0 ? w.gameFps : 14;
        welcomeFlags = w.flags;
        serverName.clear().append(w.serverName);
    }

    /** BEHAVIOR: configures the world and scoreboard for the map (team play/timing from WELCOME flags). */
    @Override
    public void onConfig(Msg.Welcome w, Options opts, WorldMap m) {
        map = m;
        options = opts;
        world.configure(m, opts, w);
        scoreboard.configure(teamPlay(), (w.flags & Msg.Welcome.FLAG_TIMING) != 0,
            opts.getBool(Opt.LIMITED_LIVES), 1);
    }

    /**
     * Apply one reliable server message.
     * <p>
     * BEHAVIOR: PLAYER_JOIN/LEAVE/TEAM/THRUST and SCORE update {@link Players}; TEAM_SCORE the
     * scoreboard; MESSAGE the message log; BASE_OWNER the world; GAME_EVENT the event ring. An own
     * score change is kept for the HUD for {@link #SCORE_MESSAGE_FRAMES} frames. Unknown types are
     * ignored.
     */
    @Override
    public void onReliable(int type, ByteBuffer p) {
        reliableCount++;
        switch (type) {
            case ReliableType.PLAYER_JOIN -> {
                join.read(p);
                players.join(join);
            }
            case ReliableType.PLAYER_LEAVE -> {
                leave.read(p);
                players.leave(leave.id);
            }
            case ReliableType.PLAYER_TEAM -> {
                team.read(p);
                players.setTeam(team.id, team.team);
            }
            case ReliableType.PLAYER_THRUST -> {
                thrust.read(p);
                players.setThrust(thrust.id, thrust.power, thrust.afterburners);
            }
            case ReliableType.SCORE -> {
                score.read(p);
                float d = players.setScore(score.id, score.score, score.lives, score.status);
                if (score.id == players.selfId() && d != 0) {
                    scoreDelta = d;
                    scoreDeltaLife = SCORE_MESSAGE_FRAMES;
                }
            }
            case ReliableType.TEAM_SCORE -> {
                teamScore.read(p);
                scoreboard.setTeamScore(teamScore.team, teamScore.score);
            }
            case ReliableType.MESSAGE -> {
                text.read(p);
                messages.add(text.kind, text.text);
            }
            case ReliableType.BASE_OWNER -> {
                baseOwner.read(p);
                world.setBaseOwner(baseOwner.baseIndex, baseOwner.playerId);
            }
            case ReliableType.GAME_EVENT -> {
                gameEvent.read(p);
                events.add(gameEvent);
            }
            default -> { } // newer message types are ignored (PROTOCOL.md "Extending")
        }
    }

    @Override
    public void onSnapshot(ByteBuffer body, long nowNanos) {
        snapshotCount++;
        world.ingest(body, nowNanos);
    }

    /**
     * BEHAVIOR: keeps the status line; losing or failing a connection (CLOSED, or back to CONNECTING
     * from a session) also adds the status as a server message.
     */
    @Override
    public void onStateChange(ConnectionState s, CharSequence st) {
        ConnectionState prev = state;
        state = s;
        status.clear().append(st);
        boolean lost = s == ConnectionState.CONNECTING
            && (prev == ConnectionState.JOINING || prev == ConnectionState.PLAYING);
        if (s == ConnectionState.CLOSED || lost) {
            messages.add(Msg.Text.KIND_SERVER, st);
        }
    }

    // ================================================================ per frame

    /**
     * Per rendered frame.
     * <p>
     * BEHAVIOR: updates the world for {@code nowNanos}, ages messages and the score message by
     * {@code dtSeconds * gameFps} original frames, and re-sorts the scoreboard if players changed.
     */
    public void frame(long nowNanos, double dtSeconds) {
        double frames = dtSeconds * gameFps;
        world.update(nowNanos);
        messages.tick(frames);
        if (scoreDeltaLife > 0) {
            scoreDeltaLife = Math.max(0, scoreDeltaLife - frames);
        }
        scoreboard.update(players);
    }

    // ================================================================ accessors

    public Players players() { return players; }

    public Scoreboard scoreboard() { return scoreboard; }

    public MessageLog messages() { return messages; }

    public GameEvents events() { return events; }

    /** Connection status line. */
    public CharBuf status() { return status; }

    public ConnectionState state() { return state; }

    /** The map, null before the config transfer. */
    public WorldMap map() { return map; }

    public Options options() { return options; }

    public int simHz() { return simHz; }

    public int snapshotHz() { return snapshotHz; }

    /** Original XPilot frames per second (message and HUD timers count these). */
    public float gameFps() { return gameFps; }

    public boolean teamPlay() { return (welcomeFlags & Msg.Welcome.FLAG_TEAM_PLAY) != 0; }

    public CharBuf serverName() { return serverName; }

    public int playerId() { return playerId; }

    public long reliableCount() { return reliableCount; }

    public long snapshotCount() { return snapshotCount; }

    /** Last own score change, shown while {@link #scoreMessageLife()} &gt; 0. */
    public float scoreDelta() { return scoreDelta; }

    /** Remaining original frames of the score message. */
    public double scoreMessageLife() { return scoreDeltaLife; }
}
