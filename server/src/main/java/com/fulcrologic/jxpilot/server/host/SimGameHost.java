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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import com.fulcrologic.jxpilot.server.net.GameHost;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.snapshot.SnapshotBuilder;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.nio.ByteBuffer;

/**
 * The network layer's {@link GameHost} over the real simulation ({@link Simulation}, {@link World}): login
 * checks (C contact.c {@code Check_queue}/{@code Check_names}, netserver.c {@code Handle_login}), one sim tick
 * per C frame, input queues, snapshots through {@link SnapshotBuilder}, and the sim's {@link Events} turned into
 * reliable messages per client. Single-threaded (the server loop); allocation free in steady state (joins,
 * leaves and kicks may allocate).
 * <p>
 * Ids: the sim's wire ids (1..255) are the network player ids. Outgoing id fields map {@code Obj.NO_ID} (-1)
 * to {@link Protocol#NO_ID} and team fields {@code TEAM_NOT_SET} to {@link Protocol#NO_TEAM}.
 * <p>
 * A connection {@link #join joins} as a reserved player ({@code Ships.reserve}): nothing in the game sees it
 * until READY spawns it ({@code Ships.spawn}). The game runs (the sim ticks and robots play) only while a
 * human is in it or with {@code rawMode} (C {@code Main_loop}: {@code NumPlayers > NumRobots || RawMode}).
 */
public final class SimGameHost implements GameHost {
    /** Inputs kept per player between two ticks; more overwrite the newest (several packets per tick are rare). */
    public static final int INPUT_QUEUE = 16;
    /** Snapshot parts per viewer (as {@code ServerNet.MAX_SNAPSHOT_PARTS}). */
    static final int SNAPSHOT_PARTS = 4;
    /** Ids are 1..MAX_IDS-1. */
    static final int IDS = World.MAX_IDS;
    /** Bytes before the sections in a snapshot datagram: packet header and snapshot header. */
    static final int SECTIONS_START = PacketHeader.SIZE + SnapshotWriter.HEADER_SIZE;
    /** Reasons given to kicked clients (C {@code Destroy_connection}). */
    static final String KICK_PAUSED_TOO_LONG = "auto-kicked: paused too long";
    static final String KICK_FULL_GAME = "no pause with full game";
    static final String KICK_FULL_TEAM = "no pause with full team";
    static final String KICK_NO_BASE = "no free base";

    private final Simulation sim;
    private final World w;
    private final SnapshotBuilder builder;
    private final Talk talk;
    private final boolean rawMode;
    private final String mapName;
    private final String mapAuthor;

    // ---- per network id
    /** The player a connection holds, or null (ids no connection holds: robots, free ids). */
    private final Player[] bound = new Player[IDS];
    /** Team for the spawn ({@code Ships.spawn}), chosen at login. */
    private final int[] spawnTeam = new int[IDS];
    private final long[][] queue = new long[IDS][INPUT_QUEUE];
    private final int[] queued = new int[IDS];
    /** Next event to deliver to a viewer. */
    private final long[] cursor = new long[IDS];
    /** First burst a viewer has not seen. */
    private final long[] burstCursor = new long[IDS];
    /** Ids announced to a viewer with PLAYER_JOIN and not yet with PLAYER_LEAVE (4 x 64 bits). */
    private final long[][] announced = new long[IDS][4];
    /** SETTINGS that arrived before the spawn, applied at the spawn. */
    private final boolean[] pendingSettings = new boolean[IDS];
    private final float[][] settings = new float[IDS][6];

    // ---- per sim slot
    private final long[] keysBySlot;

    // ---- kicks waiting for the next tick (asked for while a client message was being delivered)
    private final int[] kickIds = new int[IDS];
    private final String[] kickReasons = new String[IDS];
    private int kicks;

    private Sessions sessions = (id, why) -> { };

    // ---- snapshot scratch: the builder's parts for the viewer being served
    private final ByteBuffer[] scratch = new ByteBuffer[SNAPSHOT_PARTS];
    private int scratchParts;

    // ---- message codecs
    private final Msg.PlayerJoin join = new Msg.PlayerJoin();
    private final Msg.PlayerLeave leaveMsg = new Msg.PlayerLeave();
    private final Msg.PlayerTeam teamMsg = new Msg.PlayerTeam();
    private final Msg.Score score = new Msg.Score();
    private final Msg.TeamScore teamScore = new Msg.TeamScore();
    private final Msg.Text text = new Msg.Text();
    private final Msg.BaseOwner baseOwner = new Msg.BaseOwner();
    private final Msg.GameEvent gameEvent = new Msg.GameEvent();
    private final Msg.PlayerThrust thrust = new Msg.PlayerThrust();
    private final Msg.ScoreObject scoreObject = new Msg.ScoreObject();
    private final Msg.Settings settingsMsg = new Msg.Settings();
    private final StringBuilder nameBuf = new StringBuilder(Const.MAX_CHARS);

    /**
     * @param sim the simulation to host; robots are enabled while the game runs (see the class comment)
     */
    public SimGameHost(Simulation sim) {
        this.sim = sim;
        this.w = sim.world;
        this.builder = new SnapshotBuilder(w);
        String adminFile = w.options.getString(Opt.ADMIN_MESSAGE_FILE_NAME);
        this.talk = new Talk(w, new AdminLog(adminFile == null || adminFile.isEmpty() ? null : java.nio.file.Path.of(adminFile),
            w.options.getInt(Opt.ADMIN_MESSAGE_FILE_SIZE_LIMIT), java.time.ZonedDateTime::now));
        this.rawMode = w.options.getBool(Opt.IDLE_RUN);
        this.mapName = w.options.getString(Opt.MAP_NAME);
        this.mapAuthor = w.options.getString(Opt.MAP_AUTHOR);
        this.keysBySlot = new long[w.players.length];
        for (int i = 0; i < SNAPSHOT_PARTS; i++) {
            scratch[i] = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
        }
        for (int i = 0; i < IDS; i++) {
            spawnTeam[i] = Const.TEAM_NOT_SET;
        }
        scoreObject.text.ensureCapacity(Msg.MSG_LEN);
        join.name.ensureCapacity(Const.MAX_CHARS);
        sim.robots.applyMaxRobots();
        sim.ships.enterListener = this::entered;
    }

    @Override
    public void attach(Sessions s) {
        this.sessions = s;
    }

    /** The hosted simulation. */
    public Simulation simulation() {
        return sim;
    }

    // ================================================================ login

    /**
     * C contact.c {@code Check_queue} + {@code Check_names}, then {@code Ships.reserve}.
     * <p>
     * BEHAVIOR: a full game (no free slot, reserved ones included) first kicks a robot
     * ({@code Kick_robot_players}), else every paused human ({@code Kick_paused_players}, "The paused "X" was
     * kicked because the game is full."), else refuses with REASON_FULL.
     * BEHAVIOR: a name already taken, compared without case after trailing blanks are stripped and cut like
     * {@code Ships.reserve} (16 chars, Latin-1), is refused with REASON_BAD_NAME.
     * BEHAVIOR: team play: a missing, invalid or reserved robot team is refused (REASON_OTHER) without
     * teamAssign; with it C {@code Pick_team} chooses, kicking a robot when only the robots' teams have room. A
     * full requested team kicks one of its robots, else its paused humans, else is refused (REASON_FULL), and
     * the team is picked again (as C). Outside team play the request is ignored.
     * BEHAVIOR: otherwise the player is reserved, its input and drain state reset, and its id returned.
     */
    @Override
    public int join(CharSequence name, int team, CharSequence shape) {
        if (!hasFreeSlot() && sim.robots.kickRobotPlayers(Const.TEAM_NOT_SET) == 0
            && kickPausedPlayers(Const.TEAM_NOT_SET) == 0) {
            return -Msg.Reject.REASON_FULL;
        }
        if (!hasFreeSlot()) {
            return -Msg.Reject.REASON_FULL;
        }
        clampName(name, nameBuf);
        if (nameBuf.isEmpty() || nameTaken(nameBuf)) {
            return -Msg.Reject.REASON_BAD_NAME;
        }
        int t = Const.TEAM_NOT_SET;
        if (w.params.teamPlay) {
            t = loginTeam(team);
            if (t == REFUSE_TEAM_FULL) {
                return -Msg.Reject.REASON_FULL;
            }
            if (t < 0) {
                return -Msg.Reject.REASON_OTHER;
            }
        }
        Player pl = sim.ships.reserve(nameBuf);
        if (pl == null) {
            return -Msg.Reject.REASON_FULL;
        }
        int id = pl.id;
        bound[id] = pl;
        spawnTeam[id] = t;
        queued[id] = 0;
        pendingSettings[id] = false;
        keysBySlot[pl.slot] = 0;
        cursor[id] = w.events.seq();
        burstCursor[id] = w.events.burstSeq();
        java.util.Arrays.fill(announced[id], 0L);
        return id;
    }

    /** {@link #loginTeam}: the requested team is full and nothing could be kicked (C E_TEAM_FULL). */
    static final int REFUSE_TEAM_FULL = -100;

    /**
     * The team part of {@code Check_queue} for {@code requested} (team play only).
     * <p>
     * BEHAVIOR: see {@link #join}.
     *
     * @return the team, {@code TEAM_NOT_SET} to refuse (C E_TEAM_NOT_SET), or {@link #REFUSE_TEAM_FULL}
     */
    int loginTeam(int requested) {
        var p = w.params;
        boolean robotTeam = requested == p.robotTeam && p.reserveRobotTeam;
        if (requested < 0 || requested >= Const.MAX_TEAMS || robotTeam) {
            if (!p.teamAssign) {
                return robotTeam ? REFUSE_TEAM_FULL : Const.TEAM_NOT_SET;
            }
            int t = sim.ships.pickTeam(null);
            if (t == Const.TEAM_NOT_SET || (t == p.robotTeam && p.reserveRobotTeam)) {
                if (sim.robots.numRobots() > robotsOn(p.robotTeam) && sim.robots.kickRobotPlayers(Const.TEAM_NOT_SET) > 0) {
                    t = sim.ships.pickTeam(null);
                    return t;
                }
                return Const.TEAM_NOT_SET;
            }
            return t;
        }
        if (w.teamMembers[requested] >= w.map.teamBaseCount(requested)) {
            if (sim.robots.kickRobotPlayers(requested) == 0 && kickPausedPlayers(requested) == 0) {
                return REFUSE_TEAM_FULL;
            }
            return sim.ships.pickTeam(null);
        }
        return requested;
    }

    private int robotsOn(int team) {
        int n = 0;
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (o.robot && o.team == team) {
                n++;
            }
        }
        return n;
    }

    private boolean hasFreeSlot() {
        return w.hasFreeSlot();
    }

    /** BEHAVIOR: the name as {@code Ships.reserve} stores it: trailing blanks stripped, 16 chars, Latin-1. */
    static void clampName(CharSequence name, StringBuilder out) {
        out.setLength(0);
        int end = name.length();
        while (end > 0 && Character.isWhitespace(name.charAt(end - 1)) && name.charAt(end - 1) < 128) {
            end--;
        }
        int n = Math.min(end, Const.MAX_NAME_LEN);
        for (int i = 0; i < n; i++) {
            char c = name.charAt(i);
            out.append(c > 0xFF ? '?' : c);
        }
    }

    private boolean nameTaken(CharSequence name) {
        for (Player p : w.players) {
            if (p.inUse && p.name.length() == name.length() && Talk.equalsIgnoreCase(p.name, name, 0, name.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * C {@code Kick_paused_players}: every paused human (of {@code team}, or any) is kicked, last first.
     * <p>
     * BEHAVIOR: each is kicked (it leaves the game), then the message follows (C Destroy_connection before
     * Set_message); returns how many.
     */
    int kickPausedPlayers(int team) {
        int n = 0;
        for (int k = w.numPlayers - 1; k >= 0; k--) {
            Player p = w.playerAt(k);
            if (bound[p.id] == p && (p.status & Obj.PAUSE) != 0 && (team == Const.TEAM_NOT_SET || p.team == team)) {
                kickNow(p, team == Const.TEAM_NOT_SET ? KICK_FULL_GAME : KICK_FULL_TEAM);
                StringBuilder msg = w.events.message(Msg.Text.KIND_SERVER, Obj.NO_ID).append("The paused \"").append(p.name);
                if (team == Const.TEAM_NOT_SET) {
                    msg.append("\" was kicked because the game is full.");
                } else {
                    msg.append("\" was kicked because team ").append(team).append(" is full.");
                }
                n++;
            }
        }
        return n;
    }

    /** Remove a connected player from the game and drop its connection now. */
    private void kickNow(Player p, String reason) {
        int id = p.id;
        bound[id] = null;
        queued[id] = 0;
        keysBySlot[p.slot] = 0;
        sim.ships.leave(p);
        sessions.kick(id, reason);
    }

    /** The welcome message of C {@code Handle_login}, set when a human has entered (before the round part). */
    private void entered(Player pl) {
        StringBuilder msg = w.events.message(Msg.Text.KIND_SERVER, Obj.NO_ID);
        if (w.numPlayers == 1) {
            msg.append("Welcome to \"").append(mapName).append("\", made by ").append(mapAuthor).append('.');
        } else if (w.params.teamPlay) {
            msg.append(pl.name).append(" (team ").append(pl.team).append(") has entered \"").append(mapName)
                .append("\", made by ").append(mapAuthor).append('.');
        } else {
            msg.append(pl.name).append(" has entered \"").append(mapName).append("\", made by ").append(mapAuthor)
                .append('.');
        }
    }

    @Override
    public int team(int playerId) {
        return isId(playerId) ? wireTeam(spawnTeam[playerId]) : Protocol.NO_TEAM;
    }

    /**
     * BEHAVIOR: the player leaves the game ({@code Ships.leave}: reserved ones just free their slot) and its
     * connection state is forgotten (a kick still pending for it is dropped, so it cannot hit a later join
     * that reuses the id); ids no connection holds (already kicked) are ignored.
     */
    @Override
    public void leave(int playerId) {
        Player p = boundPlayer(playerId);
        if (p == null) {
            return;
        }
        bound[playerId] = null;
        queued[playerId] = 0;
        keysBySlot[p.slot] = 0;
        cancelKick(playerId);
        sim.ships.leave(p);
    }

    private void cancelKick(int id) {
        for (int i = 0; i < kicks; i++) {
            if (kickIds[i] == id) {
                kickIds[i] = 0; // no connection holds id 0: runKicks skips the entry
            }
        }
    }

    // ================================================================ client messages

    /**
     * BEHAVIOR: READY spawns the reserved player on its login team ({@code Ships.spawn}, which sets the welcome
     * message and applies settings that came early); no base: kicked at the next tick ("no free base").
     * BEHAVIOR: CHAT from a player in the game goes through C {@code Handle_talk} ({@link Talk}).
     * BEHAVIOR: SETTINGS go to {@code Ships.applySettings} (kept for the spawn before it).
     * BEHAVIOR: CHANGE_TEAM, SHAPE, unknown types and messages of unbound ids are ignored.
     */
    @Override
    public void clientMessage(int playerId, int type, ByteBuffer payload) {
        Player p = boundPlayer(playerId);
        if (p == null) {
            return;
        }
        switch (type) {
            case ReliableType.READY -> ready(p);
            case ReliableType.CHAT -> {
                text.read(payload);
                if (p.spawned) {
                    talk.say(p, text.text);
                }
            }
            case ReliableType.SETTINGS -> {
                settingsMsg.read(payload);
                float[] s = settings[playerId];
                s[0] = settingsMsg.power;
                s[1] = settingsMsg.turnSpeed;
                s[2] = settingsMsg.turnResistance;
                s[3] = settingsMsg.altPower;
                s[4] = settingsMsg.altTurnSpeed;
                s[5] = settingsMsg.altTurnResistance;
                if (p.spawned) {
                    applySettings(p);
                } else {
                    pendingSettings[playerId] = true;
                }
            }
            default -> { } // CHANGE_TEAM and SHAPE are not supported; newer types are ignored
        }
    }

    private void ready(Player p) {
        if (p.spawned) {
            return;
        }
        if (!sim.ships.spawn(p, spawnTeam[p.id])) {
            kickLater(p.id, KICK_NO_BASE);
            return;
        }
        keysBySlot[p.slot] = 0;
        if (pendingSettings[p.id]) {
            pendingSettings[p.id] = false;
            applySettings(p);
        }
    }

    private void applySettings(Player p) {
        float[] s = settings[p.id];
        sim.ships.applySettings(p, s[0], s[1], s[2], s[3], s[4], s[5]);
    }

    private void kickLater(int id, String reason) {
        for (int i = 0; i < kicks; i++) {
            if (kickIds[i] == id) {
                return;
            }
        }
        kickIds[kicks] = id;
        kickReasons[kicks] = reason;
        kicks++;
    }

    /**
     * BEHAVIOR: a spawned player's mask is queued for the next tick (the newest replaces the last entry when
     * {@link #INPUT_QUEUE} are waiting); input before READY and for unbound ids is dropped; aimTurn is unused.
     */
    @Override
    public void input(int playerId, int seq, long keys, int aimTurn) {
        Player p = boundPlayer(playerId);
        if (p == null || !p.spawned) {
            return;
        }
        int n = queued[playerId];
        if (n == INPUT_QUEUE) {
            n--;
        }
        queue[playerId][n] = keys;
        queued[playerId] = n + 1;
    }

    // ================================================================ ticking

    /**
     * One C frame, numbered {@code tick}.
     * <p>
     * BEHAVIOR: kicks asked for since the last tick happen first.
     * BEHAVIOR: the game runs only with a human in it or rawMode: then robots are enabled, each spawned human's
     * queued masks are applied in player order (every edge counts, C Handle_keyboard per packet), the last one
     * stays its input, robots get none, and the sim runs tick {@code tick}. Otherwise robots are disabled, the
     * world only takes the tick number and queued input is dropped.
     * BEHAVIOR: a connected human the sim removed during the tick (maxPauseTime) is kicked.
     */
    @Override
    public void tick(int tick) {
        runKicks();
        boolean run = rawMode || humans() > 0;
        if (sim.robots.enabled() != run) {
            sim.robots.setEnabled(run);
        }
        if (!run) {
            w.tick = tick;
            for (int id = 1; id < IDS; id++) {
                queued[id] = 0;
            }
            return;
        }
        applyQueuedInput();
        long from = w.events.seq();
        sim.tick(tick, keysBySlot);
        kickRemoved(from);
    }

    private void runKicks() {
        for (int i = 0; i < kicks; i++) {
            Player p = boundPlayer(kickIds[i]);
            if (p != null) {
                kickNow(p, kickReasons[i]);
            }
            kickReasons[i] = null;
        }
        kicks = 0;
    }

    private int humans() {
        int n = 0;
        for (int k = 0; k < w.numPlayers; k++) {
            if (!w.playerAt(k).robot && !w.playerAt(k).tank) {
                n++;
            }
        }
        return n;
    }

    private void applyQueuedInput() {
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (p.robot || bound[p.id] != p) {
                keysBySlot[p.slot] = 0;
                continue;
            }
            int n = queued[p.id];
            long[] q = queue[p.id];
            for (int i = 0; i < n; i++) {
                sim.keys.apply(p, q[i]);
                keysBySlot[p.slot] = q[i];
            }
            queued[p.id] = 0;
        }
    }

    /** LEAVE events of this tick for players a connection still holds: the sim kicked them. */
    private void kickRemoved(long from) {
        Events e = w.events;
        for (long s = Math.max(from, e.oldestSeq()); s < e.seq(); s++) {
            int i = Events.index(s);
            if (e.kind[i] == Events.LEAVE) {
                int id = e.a[i];
                if (isId(id) && bound[id] != null && !(bound[id].inUse && bound[id].id == id)) {
                    bound[id] = null;
                    queued[id] = 0;
                    sessions.kick(id, KICK_PAUSED_TOO_LONG);
                }
            }
        }
    }

    // ================================================================ snapshots

    /**
     * BEHAVIOR: the viewer's ship position in pixels (exactly what {@link SnapshotBuilder} centres on); before
     * its spawn, the centre of the first base of its login team (any base without a team; the world centre on a
     * map without bases).
     */
    @Override
    public double viewX(int viewerId) {
        Player p = boundPlayer(viewerId);
        if (p != null && p.spawned) {
            return p.cx / (double) Click.CLICK;
        }
        Base b = startBase(viewerId);
        return b == null ? w.pixelWidth / 2.0 : b.x();
    }

    @Override
    public double viewY(int viewerId) {
        Player p = boundPlayer(viewerId);
        if (p != null && p.spawned) {
            return p.cy / (double) Click.CLICK;
        }
        Base b = startBase(viewerId);
        return b == null ? w.pixelHeight / 2.0 : b.y();
    }

    /** The first base of the viewer's login team (any base without one), null for a map without bases. */
    private Base startBase(int viewerId) {
        int team = isId(viewerId) ? spawnTeam[viewerId] : Const.TEAM_NOT_SET;
        for (Base b : w.map.bases()) {
            if (team == Const.TEAM_NOT_SET || b.team() == team) {
                return b;
            }
        }
        return w.map.bases().isEmpty() ? null : w.map.bases().get(0);
    }

    /**
     * BEHAVIOR: part 0 builds the viewer's snapshot ({@link SnapshotBuilder}, bursts since its cursor, which then
     * moves to the newest) into scratch parts; each part p copies the sections of scratch part p after the
     * network layer's header and returns whether more parts follow. A viewer not yet in the game gets no
     * sections.
     * BEHAVIOR: part 0 sends the viewer's queued sounds and empties its queue (C {@code sound_play_queued}), so
     * each sound goes out in one snapshot.
     */
    @Override
    public boolean fillSnapshot(int viewerId, int part, boolean radar, SnapshotWriter out) {
        Player p = boundPlayer(viewerId);
        if (p == null || !p.spawned) {
            return false;
        }
        if (part == 0) {
            for (ByteBuffer b : scratch) {
                b.clear();
                b.position(PacketHeader.SIZE);
            }
            scratchParts = builder.build(p, 0, burstCursor[viewerId], radar, scratch);
            burstCursor[viewerId] = w.events.burstSeq();
            p.audio.clear();
        }
        if (part >= scratchParts) {
            return false;
        }
        ByteBuffer src = scratch[part];
        ByteBuffer dst = out.buffer();
        int end = src.position();
        for (int i = SECTIONS_START; i < end; i++) {
            dst.put(src.get(i));
        }
        return part < scratchParts - 1;
    }

    // ================================================================ reliable state and events

    /**
     * Everything a newcomer needs (C Handle_login's sends to the new player).
     * <p>
     * BEHAVIOR: for each player in the game, in order: PLAYER_JOIN (SELF for the viewer, ROBOT, PAUSED; power,
     * afterburners, name, default shape), BASE_OWNER (its home) and SCORE (score, lives with limitedLives else
     * -1, status); then in team play TEAM_SCORE for every team with members. The event cursor starts at the
     * newest event (the burst cursor at the newest burst).
     */
    @Override
    public void initialState(int viewerId, ReliableChannel out) {
        fullState(viewerId, out);
        cursor[viewerId] = w.events.seq();
        burstCursor[viewerId] = w.events.burstSeq();
    }

    private void fullState(int viewerId, ReliableChannel out) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            sendJoin(viewerId, p, out);
            if (p.homeBase >= 0 && !p.tank) {
                baseOwner.baseIndex = p.homeBase;
                baseOwner.playerId = p.id;
                baseOwner.send(out);
            }
            score.id = p.id;
            score.score = (float) p.score;
            score.lives = w.params.limitedLives ? p.life : -1;
            score.status = Simulation.scoreStatus(p);
            score.send(out);
        }
        if (w.params.teamPlay) {
            for (int t = 0; t < Const.MAX_TEAMS; t++) {
                if (w.teamMembers[t] > 0) {
                    teamScore.team = t;
                    teamScore.score = (float) w.teamScore[t];
                    teamScore.send(out);
                }
            }
        }
    }

    private void sendJoin(int viewerId, Player p, ReliableChannel out) {
        join.id = p.id;
        join.team = wireTeam(p.team);
        join.flags = (p.robot ? Msg.PlayerJoin.FLAG_ROBOT : 0) | (p.id == viewerId ? Msg.PlayerJoin.FLAG_SELF : 0)
            | ((p.status & Obj.PAUSE) != 0 ? Msg.PlayerJoin.FLAG_PAUSED : 0)
            | (p.tank ? Msg.PlayerJoin.FLAG_TANK : 0);
        join.power = p.power;
        join.afterburners = p.afterburners();
        join.name.setLength(0);
        join.name.append(p.name);
        join.shape.setLength(0);
        if (p.tank && w.items.allowShipShapes) {
            join.shape.append(w.options.getString(com.fulcrologic.jxpilot.common.config.Opt.TANK_SHIP_SHAPE));
        }
        join.send(out);
        announced[viewerId][p.id >> 6] |= 1L << (p.id & 63);
    }

    private void sendLeave(int viewerId, int id, ReliableChannel out) {
        leaveMsg.id = id;
        leaveMsg.send(out);
        announced[viewerId][id >> 6] &= ~(1L << (id & 63));
    }

    /**
     * The sim's events since the viewer's cursor as reliable messages.
     * <p>
     * BEHAVIOR: a viewer whose cursor fell out of the ring gets PLAYER_LEAVE for every announced id no longer
     * in the game, then the full state, and continues from the newest event.
     * BEHAVIOR: JOIN: PLAYER_JOIN of the player as it is now (none when it already left); LEAVE: PLAYER_LEAVE;
     * TEAM: PLAYER_TEAM; SCORE / TEAM_SCORE; MESSAGE for everyone or this viewer (from mapped); BASE_OWNER
     * (NO_ID = released); GAME_EVENT (ids mapped, value kept); THRUST: PLAYER_THRUST; SCORE_OBJECT only to its
     * player.
     */
    @Override
    public void drainEvents(int viewerId, ReliableChannel out) {
        Events e = w.events;
        long from = cursor[viewerId];
        if (from < e.oldestSeq()) {
            resync(viewerId, out);
            cursor[viewerId] = e.seq();
            return;
        }
        long to = e.seq();
        for (long s = from; s < to; s++) {
            event(viewerId, Events.index(s), out);
        }
        cursor[viewerId] = to;
    }

    private void resync(int viewerId, ReliableChannel out) {
        long[] a = announced[viewerId];
        for (int id = 1; id < IDS; id++) {
            if ((a[id >> 6] & (1L << (id & 63))) != 0) {
                Player p = w.byId(id);
                if (p == null || !p.spawned) {
                    sendLeave(viewerId, id, out);
                }
            }
        }
        fullState(viewerId, out);
    }

    private void event(int viewerId, int i, ReliableChannel out) {
        Events e = w.events;
        switch (e.kind[i]) {
            case Events.JOIN -> {
                Player p = w.byId(e.a[i]);
                if (p != null && p.spawned) {
                    sendJoin(viewerId, p, out);
                }
            }
            case Events.LEAVE -> sendLeave(viewerId, e.a[i], out);
            case Events.TEAM -> {
                teamMsg.id = e.a[i];
                teamMsg.team = wireTeam(e.b[i]);
                teamMsg.send(out);
            }
            case Events.SCORE -> {
                score.id = e.a[i];
                score.lives = e.b[i];
                score.status = e.c[i];
                score.score = e.value[i];
                score.send(out);
            }
            case Events.TEAM_SCORE -> {
                teamScore.team = e.a[i];
                teamScore.score = e.value[i];
                teamScore.send(out);
            }
            case Events.MESSAGE -> {
                if (Talk.addressedTo(e, i, viewerId)) {
                    text.kind = e.a[i];
                    text.from = wireId(e.b[i]);
                    text.text.setLength(0);
                    text.text.append(e.text[i]);
                    text.send(out);
                }
            }
            case Events.BASE_OWNER -> {
                baseOwner.baseIndex = e.a[i];
                baseOwner.playerId = wireId(e.b[i]);
                baseOwner.send(out);
            }
            case Events.GAME_EVENT -> {
                gameEvent.kind = e.a[i];
                gameEvent.a = wireId(e.b[i]);
                gameEvent.b = wireId(e.c[i]);
                gameEvent.value = e.value[i];
                gameEvent.send(out);
            }
            case Events.THRUST -> {
                thrust.id = e.a[i];
                thrust.afterburners = e.b[i];
                thrust.power = e.value[i];
                thrust.send(out);
            }
            case Events.SCORE_OBJECT -> {
                if (e.a[i] == viewerId) {
                    scoreObject.score = e.value[i];
                    scoreObject.bx = e.b[i];
                    scoreObject.by = e.c[i];
                    scoreObject.text.setLength(0);
                    scoreObject.text.append(e.text[i]);
                    scoreObject.send(out);
                }
            }
            default -> { }
        }
    }

    // ================================================================ helpers

    private static boolean isId(int id) {
        return id > 0 && id < IDS;
    }

    /** The player a connection holds under {@code id}, or null. */
    private Player boundPlayer(int id) {
        if (!isId(id)) {
            return null;
        }
        Player p = bound[id];
        return p != null && p.inUse && p.id == id ? p : null;
    }

    /** BEHAVIOR: a sim id on the wire: {@code Obj.NO_ID} (and any negative id) becomes {@link Protocol#NO_ID}. */
    static int wireId(int id) {
        return id < 0 ? Protocol.NO_ID : id;
    }

    /** BEHAVIOR: a sim team on the wire: {@code TEAM_NOT_SET} (any negative team) becomes {@link Protocol#NO_TEAM}. */
    static int wireTeam(int team) {
        return team < 0 ? Protocol.NO_TEAM : team;
    }
}
