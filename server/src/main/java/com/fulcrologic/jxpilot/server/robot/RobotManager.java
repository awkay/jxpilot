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
package com.fulcrologic.jxpilot.server.robot;

import com.fulcrologic.jxpilot.server.sim.Texts;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.sim.Alliances;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.sim.Sounds;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.ShipGeom;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * The robot manager, a per-frame port of {@code src/server/robot.c} (XPilot 4.5.5): adds robots up to
 * maxRobots/minRobots, removes them when players need room or they play badly ({@code robotsLeave}), makes
 * them declare war, talk, and play every frame through {@link DefaultRobot} (the only robot type compiled
 * into 4.5.5: {@code stratbot.c} needs {@code DEVELOPMENT} or {@code ALLBOTS} and is out of scope).
 * <p>
 * Robots are ordinary {@link Player}s with {@link Player#robot} set, joined and removed through
 * {@code Ships.join/leave}. {@link #update} is C {@code Robot_update}, called once per sim tick (one C frame)
 * after keyboard input and before autofire, as C calls it at the start of {@code Update_objects}.
 * <p>
 * The sim reports kills, crashes, spawns and departures as {@link Events}; the manager reads them with its
 * own cursor right after the collision phase ({@link #afterCollisions}, before the kill loop, as C runs
 * {@code Robot_war}, {@code Robot_reset_war} and the negative-score re-homing of {@code Player_crash} inside
 * the movement and collision code) and at the start of each update (departures and anything later).
 * <p>
 * Off by default ({@link #setEnabled}): C only runs robots when the server runs (a human is playing, or
 * {@code -rawMode}). Allocation free per tick.
 */
public final class RobotManager {

    // ---- message texts as fields: resolved when the class loads, never inside a tick (no allocation)
    private static final String T_LEFT_OUT_OF_DISAPPOINTMENT = Texts.of(" left out of disappointment.");
    private static final String T_PLAYED_TOO_BADLY = Texts.of(" played too badly.");
    private static final String T_PUNCT = Texts.of(" (");
    private static final String T_PUNCT_2 = Texts.of(", ");
    private static final String T_RETIRED = Texts.of(" retired.");
    private static final String T_STARTS_AT_STARTPOS = Texts.of(") starts at startpos ");
    private static final String T_UPSET_THE_GODS_AND_WAS = Texts.of("\" upset the gods and was kicked out of the game.");

    private final World w;
    private final Simulation sim;
    private final RobotData[] data;
    private final RobotTalk talk;
    private RobotRoster roster;
    private RobotItems items;
    private DefaultRobot robot;

    private boolean enabled;
    /** Events before this sequence number are handled. */
    private long cursor;
    /** C {@code static int new_robot_delay}. */
    int newRobotDelay;
    /** C {@code NumRobots}. */
    int numRobots;

    /** Server log for C's {@code logRobots} lines (robot.c:835-837); null logs nothing. */
    public java.util.function.Consumer<CharSequence> log;
    /** The log line, reused (robots join during play: no allocation here). */
    private final StringBuilder logLine = new StringBuilder(80);
    /** Wall clock (epoch ms) and zone of the log lines' C {@code showtime()} stamp (tests replace them). */
    public java.util.function.LongSupplier wallClock = System::currentTimeMillis;
    public java.util.TimeZone logZone = java.util.TimeZone.getDefault();
    boolean logRobots;
    String robotRealName;
    String robotHostName;

    final boolean allowShipShapes;
    int robotLeaveLife;
    int robotLeaveScore;
    int robotLeaveRatio;

    public RobotManager(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        this.data = new RobotData[w.players.length];
        for (int i = 0; i < data.length; i++) {
            data[i] = new RobotData();
        }
        this.talk = new RobotTalk(w);
        this.roster = RobotRoster.defaults();
        this.items = new RobotItems(sim);
        this.robot = new DefaultRobot(w, sim, items);
        allowShipShapes = w.options.getBool(Opt.ALLOW_SHIP_SHAPES);
        reloadOptions();
        // C Robot_init: an out-of-range robotTeam becomes team 0 (for humans too).
        if (w.params.robotTeam < 0 || w.params.robotTeam >= Const.MAX_TEAMS) {
            w.params.robotTeam = 0;
        }
        this.cursor = w.events.seq();
    }

    /**
     * server-commands: (re)read the run-time options this class keeps (at construction, and after an
     * operator's {@code /set}); the robot type's too.
     */
    public void reloadOptions() {
        logRobots = w.options.getBool(Opt.LOG_ROBOTS);
        robotRealName = w.options.getString(Opt.ROBOT_REAL_NAME);
        robotHostName = w.options.getString(Opt.ROBOT_HOST_NAME);
        robotLeaveLife = w.options.getInt(Opt.ROBOT_LEAVE_LIFE);
        robotLeaveScore = w.options.getInt(Opt.ROBOT_LEAVE_SCORE);
        robotLeaveRatio = w.options.getInt(Opt.ROBOT_LEAVE_RATIO);
        if (robot != null) {
            robot.reloadOptions();
        }
    }

    /** Turn robot management on or off (the server's "running" state: humans present or rawMode). */
    public void setEnabled(boolean on) {
        enabled = on;
        cursor = w.events.seq();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Use another robot roster (a {@code robotFile}, see {@link RobotRoster#load}). Call before robots join. */
    public void setRoster(RobotRoster r) {
        roster = r;
    }

    public RobotRoster roster() {
        return roster;
    }

    /** Use another item gate (a test spy). */
    public void setItems(RobotItems it) {
        items = it;
        robot = new DefaultRobot(w, sim, it);
    }

    /** The robot type implementation. */
    public DefaultRobot robot() {
        return robot;
    }

    /** Robot state of a player slot. */
    public RobotData data(Player pl) {
        return data[pl.slot];
    }

    /** C {@code NumRobots}. */
    public int numRobots() {
        return numRobots;
    }

    // ================================================================ Robot_update

    /**
     * C {@code Robot_update}, once per frame.
     * <p>
     * BEHAVIOR: disabled: nothing (events are skipped).
     * BEHAVIOR: first handles the sim events since the last update (spawns, kills, crashes, departures).
     * BEHAVIOR: while fewer than maxRobots players (or fewer than minRobots robots) play, there is a free base,
     * an id and an unused robot, and (team play with restrictRobots) the robot team has room, a robot joins
     * every ROBOT_CREATE_DELAY = 2*FPS frames; otherwise the delay restarts and the lowest scoring robot leaves
     * when there are more players than bases or than maxRobots (with more than minRobots robots).
     * BEHAVIOR: detached tanks (pseudo players) do not count as playing ships.
     * BEHAVIOR: with robots: the visibility distances are updated and every robot
     * either checks whether it leaves (dead robots only on their respawn frame) or plays (not during the round
     * delay).
     */
    public void update() {
        if (!enabled) {
            cursor = w.events.seq();
            return;
        }
        handleEvents();
        Params p = w.params;
        int numAny = w.numPlayers;
        int numPlaying = numAny - tanks();
        int numBases = w.map.bases().size();
        if ((numPlaying < p.maxRobots || numRobots < p.minRobots)
            && numPlaying < numBases
            && numAny < World.MAX_IDS
            && numRobots < roster.size()
            && !(p.teamPlay && p.restrictRobots && robotTeamFull())) {
            if (++newRobotDelay >= 2 * p.fps) {
                create();
                newRobotDelay = 0;
            }
        } else {
            newRobotDelay = 0;
            if (numRobots > 0) {
                if (numPlaying > numBases || numAny > World.MAX_IDS
                    || (numPlaying > p.maxRobots && numRobots > p.minRobots)) {
                    delete(null, false);
                }
            }
        }
        if (numRobots > 0) {
            robot.roundTick(numRobots);
            robot.refreshCells();
            for (int k = 0; k < w.numPlayers; k++) {
                Player pl = w.playerAt(k);
                if (!pl.robot) {
                    continue;
                }
                RobotData d = data[pl.slot];
                if ((pl.status & (Obj.PLAYING | Obj.GAME_OVER)) != Obj.PLAYING) {
                    if (pl.count == 0 && checkLeave(pl)) {
                        k--;
                    }
                    continue;
                }
                if (checkLeave(pl)) {
                    k--;
                    continue;
                }
                if (w.roundDelay > 0) {
                    continue; // robot.c Robot_update: no Robot_play during the round delay
                }
                robot.play(pl, d);
                reportPower(pl, d);
            }
        }
        cursor = w.events.seq();
    }

    private boolean robotTeamFull() {
        int t = w.params.robotTeam;
        return w.teamMembers[t] >= w.map.teamBaseCount(t);
    }

    /** C {@code NumPseudoPlayers}: detached tanks in the game. */
    private int tanks() {
        int n = 0;
        for (int k = 0; k < w.numPlayers; k++) {
            if (w.playerAt(k).tank) {
                n++;
            }
        }
        return n;
    }

    /**
     * Robots set their engine power directly; tell clients (THRUST event) when it changed, as {@code Keys}
     * does for humans, so client-side thrust sparks match.
     */
    private void reportPower(Player pl, RobotData d) {
        if (pl.power != d.reportedPower) {
            d.reportedPower = pl.power;
            w.events.add(Events.THRUST, pl.id, pl.afterburners(), 0, (float) pl.power);
        }
    }

    // ================================================================ events

    /**
     * React to the sim's events since the last update.
     * <p>
     * BEHAVIOR: SPAWN of a robot: C Robot_go_home (mode TAKE_OFF).
     * BEHAVIOR: KILL by its cause ({@code Events.KILL_*}): a weapon kill (shots, balls, lasers) is C
     * Robot_war(victim, killer); a shove is Robot_war(victim, the drawn pusher in aux); a run-over victim robot
     * at war with its killer stops that war (collision.c:447-460); explosions do nothing.
     * BEHAVIOR: CRASH of two ships (b = crasher, c = other): both died, so each robot at war with the other stops
     * the war (collision.c:447-460).
     * BEHAVIOR: a deadly crash of walls.c {@code Player_crash} (CRASH alone with a sub-type other than
     * CRASH_UNIVERSE, which is collision.c's; or a KILL with a crash sub-type in value, a shove into a wall):
     * a robot victim with a negative score gets a new home base.
     * BEHAVIOR: LEAVE: wars on and locks of the departed player are dropped (C Delete_player).
     */
    void handleEvents() {
        Events ev = w.events;
        long from = Math.max(cursor, ev.oldestSeq());
        long to = ev.seq();
        for (long s = from; s < to; s++) {
            int i = Events.index(s);
            int kind = ev.kind[i];
            if (kind == Events.LEAVE) {
                playerLeft(ev.a[i]);
            } else if (kind == Events.GAME_EVENT) {
                int g = ev.a[i];
                if (g == Msg.GameEvent.SPAWN) {
                    Player pl = w.byId(ev.b[i]);
                    if (pl != null && pl.robot) {
                        robot.goHome(data[pl.slot]);
                    }
                } else if (g == Msg.GameEvent.KILL) {
                    Player killer = w.byId(ev.b[i]);
                    Player victim = w.byId(ev.c[i]);
                    if (victim != null) {
                        killed(victim, killer, ev.cause[i], w.byId(ev.aux[i]));
                    }
                    if (victim != null && ev.value[i] != 0) {
                        rehomeIfLosing(victim);
                    }
                } else if (g == Msg.GameEvent.CRASH) {
                    Player pl = w.byId(ev.b[i]);
                    Player other = w.byId(ev.c[i]);
                    if (pl == null) {
                        continue;
                    }
                    if (other != null) {
                        resetWarIfOn(other, pl.id);
                        resetWarIfOn(pl, other.id);
                    } else if ((int) ev.value[i] != Msg.GameEvent.CRASH_UNIVERSE) {
                        rehomeIfLosing(pl);
                    }
                }
            }
        }
        cursor = to;
    }

    /** The C robot hooks of a kill, by cause (see {@link #handleEvents}). */
    private void killed(Player victim, Player killer, int cause, Player warTarget) {
        switch (cause) {
            case Events.KILL_WEAPON -> {
                if (killer != null) {
                    war(victim, killer);
                }
            }
            case Events.KILL_SHOVE -> {
                if (warTarget != null) {
                    war(victim, warTarget);
                }
            }
            case Events.KILL_RUNOVER -> {
                if (killer != null) {
                    resetWarIfOn(victim, killer.id);
                }
            }
            default -> {
                // explosions: C calls no robot hook
            }
        }
    }

    /**
     * The robot hooks of this frame's collisions, in C's place: C calls Robot_war and friends inside the
     * collision code (collision.c:827, 1359; walls.c:2197), before the kill loop can delete a robot out of
     * lives. Called by the simulation right after the collision phase.
     * <p>
     * BEHAVIOR: disabled: nothing; else {@link #handleEvents} for the events since the last call.
     */
    public void afterCollisions() {
        if (enabled) {
            handleEvents();
        }
    }

    private void resetWarIfOn(Player pl, int id) {
        if (pl.robot && DefaultRobot.warOnPlayer(data[pl.slot]) == id) {
            DefaultRobot.setWar(data[pl.slot], Obj.NO_ID);
        }
    }

    /**
     * walls.c: {@code pl->home_base = 0; Pick_startpos(ind)} for a crashed robot with a negative score: C sends
     * the base (BASE_OWNER) even when it is the same; a different base releases the old one first
     * (BASE_OWNER(old, NO_ID)), as the change-home key does.
     */
    private void rehomeIfLosing(Player pl) {
        if (!pl.robot || pl.score >= 0) {
            return;
        }
        int old = pl.homeBase;
        if (sim.ships.pickStartPos(pl)) {
            if (old >= 0 && old != pl.homeBase) {
                w.events.add(Events.BASE_OWNER, old, Obj.NO_ID, 0, 0f);
            }
            w.events.add(Events.BASE_OWNER, pl.homeBase, pl.id, 0, 0f);
            sim.ships.startPosPicked(pl); // Pick_startpos's tail (player.c:131-145)
        }
    }

    /**
     * C {@code Delete_player}'s robot bookkeeping for a departed id.
     * <p>
     * BEHAVIOR: robots at war with it stop (locks on it were already dropped by {@code Ships.leave}).
     */
    void playerLeft(int id) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if (!pl.robot) {
                continue;
            }
            resetWarIfOn(pl, id);
        }
    }

    /**
     * C {@code Robot_war}: {@code victim} was killed by {@code killer}.
     * <p>
     * BEHAVIOR: nothing for a self kill.
     * BEHAVIOR: a robot killer gloats (KILL talk) and ends any war on the victim.
     * BEHAVIOR: a robot victim declares war on the killer (WAR talk) when (int)(rfrac*100) is below the
     * killer's score lead and they are neither team mates nor allies; the rfrac is drawn only for robot victims.
     */
    public void war(Player victim, Player killer) {
        if (killer == victim) {
            return;
        }
        if (killer.robot) {
            talk.says(RobotTalk.KILL, killer.name, victim.name);
            DefaultRobot.setWar(data[killer.slot], Obj.NO_ID);
        }
        if (victim.robot && (int) (w.rfrac() * 100) < killer.score - victim.score && !w.sameTeam(victim, killer)
            && !w.allied(victim, killer)) {
            talk.says(RobotTalk.WAR, victim.name, killer.name);
            RobotData d = data[victim.slot];
            if (DefaultRobot.warOnPlayer(d) != killer.id) {
                Sounds.playAll(w, SoundEvent.DECLARE_WAR);
                DefaultRobot.setWar(d, killer.id);
            }
        }
    }

    /**
     * server-commands: C {@code Robot_invite} for the default robot ({@code Robot_default_invite}, robotdef.c:319):
     * {@code inviter} asks robot {@code pl} into an alliance; the robot accepts or refuses at once
     * ({@code Alliances.accept}/{@code refuse}).
     * <p>
     * BEHAVIOR: a robot already in an alliance with a human refuses (the human decides).
     * BEHAVIOR: limit = max(|score / max(attack / 10, 10)|, defense). An inviter outside alliances is refused when
     * the robot is at war with it, when it is not playing (dead, paused or out of the game), or when its score
     * is below the robot's score minus limit. An inviter in an alliance is refused when the robot is at war with
     * one of its members, or when the members' average score is below the robot's score minus limit.
     * BEHAVIOR: otherwise the robot accepts.
     */
    public void invite(Player pl, Player inviter) {
        Alliances a = sim.alliances;
        if (pl.alliance != Player.ALLIANCE_NOT_SET) {
            for (int k = 0; k < w.numPlayers; k++) {
                Player o = w.playerAt(k);
                if (!o.robot && !o.tank && w.allied(pl, o)) {
                    a.refuse(pl, inviter);
                    return;
                }
            }
        }
        RobotData d = data[pl.slot];
        int warId = DefaultRobot.warOnPlayer(d);
        double limit = Math.max(Math.abs(pl.score / Math.max(d.attack / 10, 10)), d.defense);
        boolean accept = true;
        if (inviter.alliance == Player.ALLIANCE_NOT_SET) {
            if (inviter.id == warId) {
                accept = false;
            }
            if ((inviter.status & (Obj.PLAYING | Obj.GAME_OVER | Obj.PAUSE)) != Obj.PLAYING) {
                accept = false;
            } else if (inviter.score < pl.score - limit) {
                accept = false;
            }
        } else {
            double avg = 0;
            int members = a.memberCount(inviter.alliance);
            for (int k = 0; k < w.numPlayers; k++) {
                Player o = w.playerAt(k);
                if (o.alliance == inviter.alliance) {
                    if (o.id == warId) {
                        accept = false;
                        break;
                    }
                    avg += o.score;
                }
            }
            if (accept && avg / members < pl.score - limit) {
                accept = false;
            }
        }
        if (accept) {
            a.accept(pl, inviter);
        } else {
            a.refuse(pl, inviter);
        }
    }

    // ================================================================ create / delete

    /** Ids of the robots in play, for {@link #pick}. */
    private int robotDefInUse(int i) {
        return data[w.playerAt(i).slot].def;
    }

    /**
     * The robot choice of C {@code Robot_create}.
     * <p>
     * BEHAVIOR: robots in play are counted as used as often as the most used one; then from a random start
     * (rfrac * MAX_ROBOTS) the first robot (cyclically) used no more than the least used one is taken.
     */
    int pick() {
        int[] used = roster.used;
        int n = roster.size();
        int mostUsed = 0;
        for (int i = 0; i < n; i++) {
            if (used[i] > used[mostUsed]) {
                mostUsed = i;
            }
        }
        for (int k = 0; k < w.numPlayers; k++) {
            if (w.playerAt(k).robot) {
                int di = robotDefInUse(k);
                if (used[di] < used[mostUsed]) {
                    used[di] = used[mostUsed];
                }
            }
        }
        int leastUsed = 0;
        for (int i = 0; i < n; i++) {
            if (used[i] < used[leastUsed]) {
                leastUsed = i;
            }
        }
        int num = (int) (w.rfrac() * n);
        while (used[num] > used[leastUsed]) {
            if (++num >= n) {
                num = 0;
            }
        }
        return num;
    }

    /**
     * C {@code Pick_team(PickForRobot)}.
     * <p>
     * BEHAVIOR: with restrictRobots: the robot team if it has a free base, else TEAM_NOT_SET.
     * BEHAVIOR: otherwise as for humans, but the robot team is not reserved: with at most one team playing, the
     * teams without players that have free bases; else the teams with free bases and the fewest players; any
     * team with free bases as a fallback; one candidate is taken, among several the lowest (long) score sum
     * that is not the robot team.
     */
    int pickTeam() {
        return pickTeamInto(w.params);
    }

    private final int[] teamFree = new int[Const.MAX_TEAMS];
    private final int[] teamPlaying = new int[Const.MAX_TEAMS];
    private final long[] teamScore = new long[Const.MAX_TEAMS];
    private final int[] teamAvailable = new int[Const.MAX_TEAMS];

    private int pickTeamInto(Params p) {
        for (int i = 0; i < Const.MAX_TEAMS; i++) {
            teamFree[i] = w.map.teamBaseCount(i) - w.teamMembers[i];
            teamPlaying[i] = 0;
            teamScore[i] = 0;
        }
        if (p.restrictRobots) {
            return teamFree[p.robotTeam] > 0 ? p.robotTeam : Const.TEAM_NOT_SET;
        }
        int playingTeams = 0;
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if ((o.status & Obj.PAUSE) != 0 || o.team < 0) {
                continue;
            }
            if (teamPlaying[o.team]++ == 0) {
                playingTeams++;
            }
            teamScore[o.team] = (long) (teamScore[o.team] + o.score);
        }
        int n = 0;
        if (playingTeams <= 1) {
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (teamPlaying[i] == 0 && teamFree[i] > 0) {
                    teamAvailable[n++] = i;
                }
            }
        } else {
            int least = w.numPlayers;
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (teamPlaying[i] > 0 && teamFree[i] > 0 && teamPlaying[i] < least) {
                    least = teamPlaying[i];
                }
            }
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (teamFree[i] > 0 && (least == w.numPlayers || teamPlaying[i] == least)) {
                    teamAvailable[n++] = i;
                }
            }
        }
        if (n == 0) {
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (teamFree[i] > 0) {
                    teamAvailable[n++] = i;
                }
            }
        }
        if (n == 1) {
            return teamAvailable[0];
        }
        int losing = Const.TEAM_NOT_SET;
        long losingScore = Long.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int t = teamAvailable[i];
            if (teamScore[t] < losingScore && t != p.robotTeam) {
                losing = t;
                losingScore = teamScore[t];
            }
        }
        return losing;
    }

    /**
     * C {@code Robot_create}.
     * <p>
     * BEHAVIOR: picks a robot ({@link #pick}), joins it (its ship shape with allowShipShapes; team per
     * {@link #pickTeam} in team play) with turnspeed 64, power 55, turnresistance 0.12 (also the alternate
     * settings) and fuel levels 100/200/500 units, then lets the robot type initialise it.
     * BEHAVIOR: says ENTER (even without robotsTalk); with logRobots logs "NAME (players, robotRealName) starts at
     * startpos BASE." (robot.c:835-837); then C's round part ({@code Rounds.robotEntered}: as the
     * first player, or during a round delay, it (re)starts and announces the round delay).
     * BEHAVIOR: returns null (nothing changes) when the join fails.
     */
    Player create() {
        int num = pick();
        RobotDef def = roster.def(num);
        int team = w.params.teamPlay ? pickTeamInto(w.params) : Const.TEAM_NOT_SET;
        Player pl = sim.ships.join(def.name(), team, true);
        if (pl == null) {
            return null;
        }
        roster.used[num]++;
        // robot.c:784-785: the options as they are (their tuners clean them when an operator sets them)
        com.fulcrologic.jxpilot.common.Names.copy(pl.realName, robotRealName);
        com.fulcrologic.jxpilot.common.Names.copy(pl.hostName, robotHostName);
        if (allowShipShapes) {
            pl.ship = roster.ship(num); // else Init_player's defaultShipShape (robot.c:776)
        }
        pl.turnSpeed = (float) Const.MAX_PLAYER_TURNSPEED;
        pl.turnSpeedS = (float) Const.MAX_PLAYER_TURNSPEED;
        pl.turnResistance = 0.12f;
        pl.turnResistanceS = 0.12f;
        pl.power = (float) Const.MAX_PLAYER_POWER;
        pl.powerS = (float) Const.MAX_PLAYER_POWER;
        RobotData d = data[pl.slot];
        d.clear();
        d.active = true;
        d.def = num;
        d.fuelL1 = 100 * 256;
        d.fuelL2 = 200 * 256;
        d.fuelL3 = 500 * 256;
        d.reportedPower = pl.power;
        robot.create(d, def.config());
        numRobots++;
        talk.says(RobotTalk.ENTER, pl.name, null);
        if (logRobots && log != null) {
            logLine.setLength(0);
            com.fulcrologic.jxpilot.server.world.ShowTime.append(logLine, wallClock.getAsLong(), logZone).append(' ');
            logLine.append(pl.name).append(T_PUNCT).append(w.numPlayers).append(T_PUNCT_2).append(robotRealName)
                .append(T_STARTS_AT_STARTPOS).append(pl.homeBase).append('.');
            log.accept(logLine);
        }
        sim.rounds.robotEntered();
        w.events.add(Events.THRUST, pl.id, pl.afterburners(), 0, (float) pl.power);
        return pl;
    }

    /**
     * C {@code Robot_delete}: remove {@code pl}, or (null) the robot with the lowest score.
     * <p>
     * BEHAVIOR: the lowest score is the first robot strictly below LONG_MAX, in player order.
     * BEHAVIOR: a kicked robot is announced ("\"NAME\" upset the gods and was kicked out of the game.").
     * BEHAVIOR: the robot leaves (Ships.leave: LEAVE event), its slot forgets it, wars and locks on it end.
     */
    void delete(Player pl, boolean kicked) {
        if (pl == null) {
            double low = Long.MAX_VALUE;
            for (int k = 0; k < w.numPlayers; k++) {
                Player o = w.playerAt(k);
                if (o.robot && o.score < low) {
                    pl = o;
                    low = o.score;
                }
            }
            if (pl == null) {
                return;
            }
        }
        if (kicked) {
            w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append('"').append(pl.name)
                .append(T_UPSET_THE_GODS_AND_WAS);
        }
        int id = pl.id;
        data[pl.slot].clear();
        numRobots--;
        sim.ships.leave(pl);
        pl.robot = false;
        pl.ship = ShipGeom.defaultShip();
        playerLeft(id);
    }

    /** server-commands: C {@code Delete_player} of a robot ({@code /kick}): it leaves without the kick notice. */
    public void remove(Player pl) {
        delete(pl, false);
    }

    /**
     * C {@code Robot_check_leave}: does the robot quit?
     * <p>
     * BEHAVIOR: only with robotsLeave, life &gt; 0, no limited lives, and playing or done counting down.
     * BEHAVIOR: "NAME retired." at robotLeaveLife lives (if &gt; 0); "NAME left out of disappointment." below
     * robotLeaveScore (if not 0); "NAME played too badly." when score/(life+1) is below robotLeaveRatio (if
     * not 0). It then says LEAVE, the reason is announced and the robot is deleted (returns true).
     */
    boolean checkLeave(Player pl) {
        Params p = w.params;
        if (!p.robotsLeave || pl.life <= 0 || p.limitedLives || ((pl.status & Obj.PLAYING) == 0 && pl.count > 0)) {
            return false;
        }
        String reason;
        if (robotLeaveLife > 0 && pl.life >= robotLeaveLife) {
            reason = T_RETIRED;
        } else if (robotLeaveScore != 0 && pl.score < robotLeaveScore) {
            reason = T_LEFT_OUT_OF_DISAPPOINTMENT;
        } else if (robotLeaveRatio != 0 && pl.score / (pl.life + 1) < robotLeaveRatio) {
            reason = T_PLAYED_TOO_BADLY;
        } else {
            return false;
        }
        talk.says(RobotTalk.LEAVE, pl.name, null);
        w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name).append(reason);
        delete(pl, false);
        return true;
    }

    /**
     * player.c:1938-1951 ({@code Player_death_reset}): a robot has just lost its last life (limited lives).
     * <p>
     * BEHAVIOR: outside race and team play, or with robotsLeave and a score below robotLeaveScore, the robot is
     * deleted (C {@code Robot_delete(ind, false)}: no message) and true is returned; {@link #update} later
     * replaces it as usual. Otherwise false: the caller ends its game (GAME_OVER).
     */
    public boolean leaveOutOfLives(Player pl) {
        Params p = w.params;
        if (!(w.map.timing() || p.teamPlay) || (p.robotsLeave && pl.score < robotLeaveScore)) {
            delete(pl, false);
            return true;
        }
        return false;
    }

    // ================================================================ API for the server

    /**
     * C {@code Kick_robot_players} (contact.c): make room for a joining human. At most one robot leaves.
     * <p>
     * BEHAVIOR: no robots: 0. No team: in team play with reserveRobotTeam the lowest scoring robot outside the
     * robot team (lowest below INT_MAX), else the lowest scoring robot overall. A team: the lowest scoring
     * robot of that team, 0 when it has none. The kicked robot is announced. Returns the number kicked.
     */
    public int kickRobotPlayers(int team) {
        if (numRobots == 0) {
            return 0;
        }
        Params p = w.params;
        if (team == Const.TEAM_NOT_SET && !(p.teamPlay && p.reserveRobotTeam)) {
            delete(null, true);
            return 1;
        }
        Player low = null;
        double lowScore = Integer.MAX_VALUE;
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (!o.robot) {
                continue;
            }
            boolean candidate = team == Const.TEAM_NOT_SET ? o.team != p.robotTeam : o.team == team;
            if (candidate && o.score < lowScore) {
                low = o;
                lowScore = o.score;
            }
        }
        if (low == null) {
            return 0;
        }
        delete(low, true);
        return 1;
    }

    /**
     * C tuner {@code maxRobots}: remove robots (kicked) while there are more than maxRobots.
     * <p>
     * BEHAVIOR: minRobots is lowered to maxRobots first.
     */
    public void applyMaxRobots() {
        Params p = w.params;
        if (p.maxRobots < p.minRobots) {
            p.minRobots = p.maxRobots;
        }
        while (p.maxRobots < numRobots) {
            delete(null, true);
        }
    }

    /** C {@code Robot_war_on_player}: the id a robot is at war with, or NO_ID (scoreboard "(target)"). */
    public int warOnPlayer(Player pl) {
        return pl.robot ? DefaultRobot.warOnPlayer(data[pl.slot]) : Obj.NO_ID;
    }

    /** C {@code Robot_program} (ECM reprogramming): the robot goes to war with {@code victimId}. */
    public void program(Player pl, int victimId) {
        if (pl.robot) {
            DefaultRobot.setWar(data[pl.slot], victimId);
        }
    }
}
