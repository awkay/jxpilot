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
    /** C's default framesPerSecond: the rate assumed when WELCOME gives none. */
    public static final int DEFAULT_FPS = 14;

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
    private final Msg.Timing timing = new Msg.Timing();
    private final Msg.GameEvent gameEvent = new Msg.GameEvent();
    private final Msg.ScoreObject scoreObject = new Msg.ScoreObject();
    // ---- server-items: SEEK (C Handle_seek)
    private final Msg.Seek seek = new Msg.Seek();
    private final StringBuilder seekText = new StringBuilder(3 * 32 + 32);
    private final Msg.PlayerAlliance alliance = new Msg.PlayerAlliance();

    private ConnectionState state = ConnectionState.IDLE;
    private WorldMap map;
    private Options options;
    /** Until WELCOME: the default framesPerSecond, one tick per C frame, snapshot divisor 1. */
    private int simHz = DEFAULT_FPS;
    private int snapshotHz = DEFAULT_FPS / Protocol.DEFAULT_SNAPSHOT_DIVISOR;
    private float gameFps = DEFAULT_FPS;
    private int welcomeFlags;
    private final CharBuf serverName = new CharBuf(64);
    private int playerId = Protocol.NO_ID;
    private long reliableCount;
    private long snapshotCount;

    private final ScoreObjects scoreObjects = new ScoreObjects();

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
        scoreObjects.clear();
        if (world.configured()) {
            world.reset();
        }
    }

    /**
     * BEHAVIOR: records rates, game fps, flags and the server name. A missing gameFps is
     * {@link #DEFAULT_FPS}; a missing simHz is the (rounded) game fps, as the server ticks once per C frame;
     * a missing snapshotHz is simHz / {@link Protocol#DEFAULT_SNAPSHOT_DIVISOR}.
     */
    @Override
    public void onWelcome(Msg.Welcome w) {
        gameFps = w.gameFps > 0 ? w.gameFps : DEFAULT_FPS;
        simHz = w.simHz > 0 ? w.simHz : Math.max(1, Math.round(gameFps));
        snapshotHz = w.snapshotHz > 0 ? w.snapshotHz : simHz / Protocol.DEFAULT_SNAPSHOT_DIVISOR;
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
            opts.getBool(Opt.LIMITED_LIVES), scoreboard.decimals());
    }

    /**
     * Apply one reliable server message.
     * <p>
     * BEHAVIOR: PLAYER_JOIN/LEAVE/TEAM/THRUST/ALLIANCE and SCORE update {@link Players}; TEAM_SCORE the
     * scoreboard; MESSAGE the message log; BASE_OWNER the world; GAME_EVENT the event ring (sub-types in
     * {@code value} kept as sent). Unknown types are ignored.
     * BEHAVIOR: SEEK adds C's "has programmed ... to seek ..." message ({@link #seek}).
     * BEHAVIOR: SCORE_OBJECT (C {@code Handle_score_object}; the server sends it only to the player whose
     * score changed) becomes a {@link ScoreObjects} entry with its points, label and block; an empty label
     * makes no HUD text. SCORE itself never makes one.
     * BEHAVIOR: a SPAWN GAME_EVENT of the own player marks the own ship as teleported (R15).
     * BEHAVIOR: TIMING (C {@code Handle_timing}) sets the player's race check/round, stamped with the newest
     * tick (C {@code timing_loops}) when they change.
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
                players.setScore(score.id, score.score, score.lives, score.status);
            }
            case ReliableType.SCORE_OBJECT -> {
                scoreObject.read(p);
                scoreObjects.add(scoreObject.score, scoreObject.text, true, scoreObject.bx, scoreObject.by);
            }
            case ReliableType.TIMING -> {
                timing.read(p);
                players.setTiming(timing.id, timing.check, timing.round, world.newestTick());
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
                if (gameEvent.kind == Msg.GameEvent.SPAWN && gameEvent.a == players.selfId()) {
                    world.markSelfTeleport(world.newestTick()); // R15: the next SELF is a new place
                }
            }
            case ReliableType.MOTD -> motdPiece(p);
            case ReliableType.SEEK -> {
                seek.read(p);
                seek(seek.programmerId, seek.robotId, seek.soughtId);
            }
            case ReliableType.PLAYER_ALLIANCE -> {
                alliance.read(p);
                players.setAlliance(alliance.id, alliance.mark);
            }
            default -> { } // newer message types are ignored (PROTOCOL.md "Extending")
        }
    }

    /**
     * C {@code Handle_seek} (client.c:1137): "PROGRAMMER has programmed ROBOT to seek SOUGHT." in the message
     * log (as a game message, C {@code Add_message}).
     * <p>
     * BEHAVIOR: nothing when any of the three players is unknown (C logs "Bad player seek").
     */
    void seek(int programmerId, int robotId, int soughtId) {
        int pr = players.slotOf(programmerId);
        int ro = players.slotOf(robotId);
        int so = players.slotOf(soughtId);
        if (pr < 0 || ro < 0 || so < 0) {
            return;
        }
        seekText.setLength(0);
        seekText.append(players.name(pr)).append(" has programmed ").append(players.name(ro)).append(" to seek ")
            .append(players.name(so)).append('.');
        messages.add(Msg.Text.KIND_GAME, seekText);
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
     * BEHAVIOR: updates the world for {@code nowNanos}, ages messages and score objects by
     * {@code dtSeconds * gameFps} original frames, and re-sorts the scoreboard if players changed.
     */
    public void frame(long nowNanos, double dtSeconds) {
        double frames = dtSeconds * gameFps;
        world.update(nowNanos);
        messages.tick(frames);
        scoreObjects.tick(frames);
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

    /** F11: the server already blinks radar blips (the C server): the client does not blink them again. */
    public boolean radarBlinked() { return (welcomeFlags & Msg.Welcome.FLAG_RADAR_BLINKED) != 0; }

    public CharBuf serverName() { return serverName; }

    // ---- the server's message of the day (C motd), assembled from MOTD pieces
    private final byte[] motd = new byte[Msg.Motd.MAX_SIZE];
    private int motdGot;
    private boolean motdReady;

    private void motdPiece(ByteBuffer p) {
        int len = p.remaining() - 8;
        int total = Msg.Motd.read(p, motd);
        if (total < 0) {
            return;
        }
        motdGot += len;
        if (motdGot >= total) {
            motdReady = true;
            motdGot = total;
        }
    }

    /**
     * The server's MOTD once all of it has arrived, then null again (C pops its MOTD viewer up once).
     * <p>
     * BEHAVIOR: null until every MOTD piece is in; then the Latin-1 text once.
     */
    public String takeMotd() {
        if (!motdReady) {
            return null;
        }
        motdReady = false;
        return new String(motd, 0, motdGot, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public int playerId() { return playerId; }

    public long reliableCount() { return reliableCount; }

    public long snapshotCount() { return snapshotCount; }

    /** Own score changes (HUD stack and game-area texts). */
    public ScoreObjects scoreObjects() { return scoreObjects; }
}
