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
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.sim.Alliances;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * The talk commands, a port of {@code src/server/command.c}: a chat line starting with '/' (C
 * {@code Receive_talk}) is a command. {@code /advance /ally /get /help /kick /lock /password /pause /queue
 * /reset /set /team /version}, matched by their abbreviation as in C's table, with C's reply texts. Replies go
 * to the commanding player with " [*Server reply*]" appended (C {@code Set_player_message}); notices go to
 * everyone (C {@code Set_message}). Operator status ({@link Player#isOperator}) comes from {@code /password},
 * checked against the {@code password} option (command line, defaults file or {@code passwordFileName}).
 * <p>
 * JXPilot has no login queue (a full game refuses a newcomer), so {@code /queue} always finds it empty and
 * {@code /advance} never finds the player. Commands run between ticks and may allocate.
 */
final class Commands {
    static final int SUCCESS = 0;
    static final int ERROR = -1;
    static final int NOT_OPERATOR = -2;
    static final int NO_NAME = -3;
    /** C {@code VERSION}. */
    static final String VERSION = "4.5.5";
    /** C {@code MAX_CHARS}: the buffer {@code Get_option_value} fills for {@code /get} and {@code /set}. */
    static final int MAX_CHARS = 80;
    /** {@link #playerByName}: no player matches. */
    static final int NO_MATCH = -1;
    /** {@link #playerByName}: several players match. */
    static final int SEVERAL = -2;

    /** One row of C's {@code commands} table. */
    record Command(String name, String abbrev, String help, boolean operOnly) {}

    /** C's table, sorted alphabetically (command.c:145-241). */
    static final Command[] COMMANDS = {
        new Command("advance", "ad", "/advance <name of player in the queue>. "
            + "Move the player to the front of the queue.  (operator)", true),
        new Command("ally", "al", "/ally {invite|cancel|refuse|accept|leave|list} [<player name>]. "
            + "Manages alliances and invitations for them.", false),
        new Command("get", "g", "/get <option>.  Gets a server option.", false),
        new Command("help", "h", "Print command list.  /help <command> gives more info.", false),
        new Command("kick", "k", "/kick <player name or ID number>.  Remove a player from game.  (operator)", true),
        new Command("lock", "l", "Just /lock tells lock status.  /lock 1 locks, /lock 0 unlocks.  (operator)", false),
        new Command("password", "pas", "/password <string>.  If string matches -password option "
            + "gives operator status.", false),
        new Command("pause", "pau", "/pause <player name or ID number>.  Pauses player.  (operator)", true),
        new Command("queue", "q", "/queue.  Show the names of players waiting to enter.", false),
        new Command("reset", "r", "Just /reset re-starts the round. "
            + "/reset.  Resets all scores to 0.  (operator)", true),
        new Command("set", "s", "/set <option> <value>.  Sets a server option.  (operator)", true),
        new Command("team", "t", "/team <team number> swaps you to given team.", false),
        new Command("version", "v", "Print server version.", false),
    };

    static final String ALLY_USAGE = "Usage: /ally {invite|cancel|refuse|accept|leave|list} [<player name>]";
    private static final String[] ALLY_CMDS = {"invite", "cancel", "refuse", "accept", "leave", "list"};
    private static final int ALLY_INVITE = 0;
    private static final int ALLY_CANCEL = 1;
    private static final int ALLY_REFUSE = 2;
    private static final int ALLY_ACCEPT = 3;
    private static final int ALLY_LEAVE = 4;
    private static final int ALLY_LIST = 5;

    private final SimGameHost host;
    private final Simulation sim;
    private final World w;
    private final Tuning tuning;
    private final StringBuilder msg = new StringBuilder(Const.MSG_LEN);

    Commands(SimGameHost host, Simulation sim, Tuning tuning) {
        this.host = host;
        this.sim = sim;
        this.w = sim.world;
        this.tuning = tuning;
    }

    // ================================================================ dispatch

    /**
     * C {@code Handle_player_command}: {@code cmd} is the chat line without its leading '/'.
     * <p>
     * BEHAVIOR: an empty command: "No command given.  Type /help for help.  [*Server reply*]".
     * BEHAVIOR: the arguments start after the first space found from the second character on; the command is
     * the first table entry whose abbreviation starts the command (any case); none: "Unknown command 'CMD'.
     * [*Server reply*]".
     * BEHAVIOR: the command runs with the player's operator status; an error without its own message says
     * "ErrOr.", a missing operator status "You need operator status to use this command.", a missing name "You
     * must give a player name as an argument."; a non-empty reply gets " [*Server reply*]", cut to MSG_LEN - 1
     * characters, and goes to the player.
     */
    void handle(Player pl, CharSequence cmd) {
        if (cmd.length() == 0) {
            tell(pl, "No command given.  Type /help for help.  [*Server reply*]");
            return;
        }
        String line = cmd.toString();
        int space = line.indexOf(' ', 1);
        String name = space < 0 ? line : line.substring(0, space);
        String args = space < 0 ? "" : line.substring(space + 1);
        Command c = null;
        for (Command k : COMMANDS) {
            if (name.regionMatches(true, 0, k.abbrev, 0, k.abbrev.length())) {
                c = k;
                break;
            }
        }
        if (c == null) {
            tell(pl, "Unknown command '" + name + "'.  [*Server reply*]");
            return;
        }
        msg.setLength(0);
        int result = run(c, args, pl, pl.isOperator);
        switch (result) {
            case SUCCESS -> { }
            case ERROR -> {
                if (msg.isEmpty()) {
                    msg.append("ErrOr.");
                }
            }
            case NOT_OPERATOR -> {
                if (msg.isEmpty()) {
                    msg.append("You need operator status to use this command.");
                }
            }
            case NO_NAME -> {
                if (msg.isEmpty()) {
                    msg.append("You must give a player name as an argument.");
                }
            }
            default -> msg.setLength(0);
        }
        if (!msg.isEmpty()) {
            msg.append(" [*Server reply*]");
            if (msg.length() > Const.MSG_LEN - 1) {
                msg.setLength(Const.MSG_LEN - 1);
            }
            tell(pl, msg);
        }
    }

    private int run(Command c, String arg, Player pl, boolean oper) {
        return switch (c.name) {
            case "advance" -> advance(arg, oper);
            case "ally" -> ally(arg, pl);
            case "get" -> get(arg);
            case "help" -> help(arg);
            case "kick" -> kick(arg, pl, oper);
            case "lock" -> lock(arg, pl, oper);
            case "password" -> password(arg, pl);
            case "pause" -> pause(arg, pl, oper);
            case "queue" -> queue();
            case "reset" -> reset(arg, pl, oper);
            case "set" -> set(arg, pl, oper);
            case "team" -> team(arg, pl);
            default -> version();
        };
    }

    // ================================================================ commands

    /**
     * C {@code Cmd_advance} with C {@code Queue_advance_player} over JXPilot's (always empty) queue.
     * <p>
     * BEHAVIOR: not an operator: NOT_OPERATOR; no name: NO_NAME; a name of 16 characters or more: "Name too
     * long." (ERROR); otherwise "Player \"NAME\" not in queue." (SUCCESS).
     */
    int advance(String arg, boolean oper) {
        if (!oper) {
            return NOT_OPERATOR;
        }
        if (arg.isEmpty()) {
            return NO_NAME;
        }
        if (arg.length() >= Const.MAX_NAME_LEN) {
            msg.append("Name too long.");
            return ERROR;
        }
        msg.append("Player \"").append(arg).append("\" not in queue.");
        return SUCCESS;
    }

    /** C {@code Cmd_queue} with {@code Queue_show_list}: BEHAVIOR: "The queue is empty." (SUCCESS). */
    int queue() {
        msg.append("The queue is empty.");
        return SUCCESS;
    }

    /** C {@code Cmd_version}: BEHAVIOR: "XPilot version 4.5.5." (SUCCESS). */
    int version() {
        msg.append("XPilot version ").append(VERSION).append('.');
        return SUCCESS;
    }

    /**
     * C {@code Cmd_help}.
     * <p>
     * BEHAVIOR: no argument: "Commands: " and every command name followed by a space.
     * BEHAVIOR: an argument: the help of the first command whose name matches the argument over the length of
     * the command's abbreviation (any case), else "No help for nonexistent command 'ARG'." Always SUCCESS.
     */
    int help(String arg) {
        if (arg.isEmpty()) {
            msg.append("Commands: ");
            for (Command c : COMMANDS) {
                msg.append(c.name).append(' ');
            }
            return SUCCESS;
        }
        for (Command c : COMMANDS) {
            if (arg.regionMatches(true, 0, c.name, 0, c.abbrev.length())) {
                msg.append(c.help);
                return SUCCESS;
            }
        }
        msg.append("No help for nonexistent command '").append(arg).append("'.");
        return SUCCESS;
    }

    /**
     * C {@code Cmd_password}.
     * <p>
     * BEHAVIOR: without a password option, or with an argument other than the password (exact): "Wrong.", and an
     * operator loses the status ("Wrong.  You lost operator status."). The password: operator status, "You got
     * operator status.". Always SUCCESS.
     */
    int password(String arg, Player pl) {
        String password = w.options.getString(Opt.PASSWORD);
        if (password == null || !arg.equals(password)) {
            msg.append("Wrong.");
            if (pl.isOperator) {
                pl.isOperator = false;
                msg.append("  You lost operator status.");
            }
        } else {
            pl.isOperator = true;
            msg.append("You got operator status.");
        }
        return SUCCESS;
    }

    /**
     * C {@code Cmd_lock}.
     * <p>
     * BEHAVIOR: no argument: "The game is currently locked." / "... unlocked." for anyone (SUCCESS).
     * BEHAVIOR: otherwise operators only (NOT_OPERATOR); an argument other than "1" or "0": "Invalid argument
     * 'ARG'.  Specify either 0 or 1." (ERROR); the current state: "Game is already locked." / "... unlocked.";
     * else the lock changes (new players are refused, {@link SimGameHost#join}) and everyone gets " < The game
     * has been locked by NAME! >" / "unlocked" (SUCCESS).
     */
    int lock(String arg, Player pl, boolean oper) {
        if (arg.isEmpty()) {
            msg.append("The game is currently ").append(host.gameLock ? "locked" : "unlocked").append('.');
            return SUCCESS;
        }
        if (!oper) {
            return NOT_OPERATOR;
        }
        boolean lock;
        if (arg.equals("1")) {
            lock = true;
        } else if (arg.equals("0")) {
            lock = false;
        } else {
            msg.append("Invalid argument '").append(arg).append("'.  Specify either 0 or 1.");
            return ERROR;
        }
        if (lock == host.gameLock) {
            msg.append("Game is already ").append(host.gameLock ? "locked" : "unlocked").append('.');
        } else {
            host.gameLock = lock;
            all().append(" < The game has been ").append(lock ? "locked" : "unlocked").append(" by ").append(pl.name)
                .append("! >");
        }
        return SUCCESS;
    }

    /**
     * C {@code Cmd_kick}.
     * <p>
     * BEHAVIOR: operators only (NOT_OPERATOR); no name: NO_NAME; the player (by {@link #playerByName}) leaves the
     * game (a robot or tank is deleted, a human's connection dropped with "kicked out"), then everyone gets "OPER kicked
     * NAME out! [*Server notice*]" (SUCCESS); no match: "Name does not match any player.", several: "Name
     * matches several players." (ERROR).
     */
    int kick(String arg, Player pl, boolean oper) {
        if (!oper) {
            return NOT_OPERATOR;
        }
        if (arg.isEmpty()) {
            return NO_NAME;
        }
        Player p = find(arg);
        if (p == null) {
            return ERROR;
        }
        String notice = pl.name + " kicked " + p.name + " out! [*Server notice*]";
        if (host.connected(p)) {
            host.kickByCommand(p, pl);
        } else if (p.robot) {
            sim.robots.remove(p);
        } else {
            sim.ships.leave(p); // a tank (C Delete_player)
        }
        all().append(notice);
        return SUCCESS;
    }

    /**
     * C {@code Cmd_pause}.
     * <p>
     * BEHAVIOR: operators only (NOT_OPERATOR); no name: NO_NAME; a connected player (not a robot) found by
     * {@link #playerByName}: a ship flying is killed first (C {@code Kill_player}), then the player is paused
     * and everyone gets "NAME was paused by OPER." (SUCCESS); anything else: "Invalid player id." (ERROR).
     */
    int pause(String arg, Player pl, boolean oper) {
        if (!oper) {
            return NOT_OPERATOR;
        }
        if (arg.isEmpty()) {
            return NO_NAME;
        }
        int[] code = new int[1];
        Player p = playerByName(arg, code);
        if (p != null && host.connected(p)) {
            if (p.isAlive()) {
                sim.ships.kill(p);
            }
            sim.ships.pause(p, true);
            all().append(p.name).append(" was paused by ").append(pl.name).append('.');
            return SUCCESS;
        }
        msg.append("Invalid player id.");
        return ERROR;
    }

    /**
     * C {@code Cmd_reset}.
     * <p>
     * BEHAVIOR: operators only (NOT_OPERATOR). "all" (any case): every player's and team's score becomes 0,
     * every player is reset (a new round), a finished game restarts, the round count is 0, and everyone gets
     * " < Total reset by NAME! >". Anything else: players reset, a finished game restarts, " < Round reset by
     * NAME! >". SUCCESS.
     */
    int reset(String arg, Player pl, boolean oper) {
        if (!oper) {
            return NOT_OPERATOR;
        }
        boolean total = arg.equalsIgnoreCase("all");
        if (total) {
            for (int k = w.numPlayers - 1; k >= 0; k--) {
                Player p = w.playerAt(k);
                p.score = 0;
                p.scoreDirty = true;
            }
            for (int t = 0; t < Const.MAX_TEAMS; t++) {
                w.teamScore[t] = 0;
                w.teamScoreDirty[t] = true;
            }
        }
        sim.rounds.resetAllPlayers();
        if (w.gameDuration == -1) {
            w.gameDuration = 0;
        }
        if (total) {
            w.roundsPlayed = 0;
        }
        all().append(total ? " < Total reset by " : " < Round reset by ").append(pl.name).append("! >");
        return SUCCESS;
    }

    /**
     * C {@code Cmd_set}.
     * <p>
     * BEHAVIOR: operators only (NOT_OPERATOR); the option and the value are the first two space-separated words,
     * else "Usage: /set option value." (ERROR).
     * BEHAVIOR: set ({@link Tuning#tune}): for {@code password} "Operation successful."; for any other option
     * everyone gets " < Option OPTION set to VALUE by NAME. >" (VALUE as {@code /get} shows it) (SUCCESS).
     * BEHAVIOR: refused: "Invalid value.", "This option cannot be changed at runtime." or "No option named
     * 'OPTION'." (ERROR).
     */
    int set(String arg, Player pl, boolean oper) {
        if (!oper) {
            return NOT_OPERATOR;
        }
        String[] words = strtok(arg, " ", 2);
        if (words.length < 2) {
            msg.append("Usage: /set option value.");
            return ERROR;
        }
        String option = words[0];
        int i = tuning.tune(option, words[1]);
        if (i == Tuning.OK) {
            if (option.equalsIgnoreCase("password")) {
                msg.append("Operation successful.");
            } else {
                StringBuilder value = new StringBuilder();
                tuning.get(option, value);
                all().append(" < Option ").append(option).append(" set to ").append(cut(value)).append(" by ")
                    .append(pl.name).append(". >");
                return SUCCESS;
            }
        } else if (i == Tuning.INVALID) {
            msg.append("Invalid value.");
        } else if (i == Tuning.UNDEFINED) {
            msg.append("This option cannot be changed at runtime.");
        } else {
            msg.append("No option named '").append(option).append("'.");
        }
        return ERROR;
    }

    /**
     * C {@code Cmd_get}.
     * <p>
     * BEHAVIOR: no argument: "Usage: /get option." (ERROR); "password" or "mapData" (any case): "Cannot retrieve
     * that option." (ERROR).
     * BEHAVIOR: the value ({@link Tuning#get}, cut to MAX_CHARS - 1 characters): "The value of ARG is VALUE."
     * (SUCCESS); else "No option named ARG.", "Cannot show the value of this option.", "No value has been set
     * for option ARG." or "Generic error." (ERROR).
     */
    int get(String arg) {
        if (arg.isEmpty()) {
            msg.append("Usage: /get option.");
            return ERROR;
        }
        if (arg.equalsIgnoreCase("password") || arg.equalsIgnoreCase("mapData")) {
            msg.append("Cannot retrieve that option.");
            return ERROR;
        }
        StringBuilder value = new StringBuilder();
        switch (tuning.get(arg, value)) {
            case Tuning.OK -> {
                msg.append("The value of ").append(arg).append(" is ").append(cut(value)).append('.');
                return SUCCESS;
            }
            case Tuning.NO_OPTION -> msg.append("No option named ").append(arg).append('.');
            case Tuning.NOT_VISIBLE -> msg.append("Cannot show the value of this option.");
            case Tuning.NO_VALUE -> msg.append("No value has been set for option ").append(arg).append('.');
            default -> msg.append("Generic error.");
        }
        return ERROR;
    }

    /** C's {@code char value[MAX_CHARS]} that {@code Get_option_value} fills with strlcpy. */
    private static CharSequence cut(StringBuilder value) {
        if (value.length() > MAX_CHARS - 1) {
            value.setLength(MAX_CHARS - 1);
        }
        return value;
    }

    // ================================================================ /team

    /**
     * C {@code Cmd_team}: {@code pl} asks to swap to the team in {@code arg} (also the CHANGE_TEAM message).
     * <p>
     * BEHAVIOR: refused with a reply (ERROR): "No team play going on."; "You do not currently have a team.";
     * an argument not starting with a digit: "Invalid team specification."; team (the leading digits) of 10 or
     * more: "Team N is not a valid team."; "You already are on team N."; "There are no bases for team N on this
     * map."; the reserved robot team: "You cannot join the robot team on this server."; "Team N is full."
     * (members &gt;= bases).
     * BEHAVIOR: otherwise ({@link #swapTeam}) SUCCESS without a reply.
     */
    int team(String arg, Player pl) {
        var p = w.params;
        if (!p.teamPlay) {
            msg.append("No team play going on.");
            return ERROR;
        }
        if (pl.team == Const.TEAM_NOT_SET) {
            msg.append("You do not currently have a team.");
            return ERROR;
        }
        if (arg.isEmpty() || arg.charAt(0) < '0' || arg.charAt(0) > '9') {
            msg.append("Invalid team specification.");
            return ERROR;
        }
        int digits = 0;
        while (digits < arg.length() && arg.charAt(digits) >= '0' && arg.charAt(digits) <= '9') {
            digits++;
        }
        long team = Talk.parseTeam(arg, digits);
        if (team >= Const.MAX_TEAMS) {
            msg.append("Team ").append(team).append(" is not a valid team.");
        } else if (team == pl.team) {
            msg.append("You already are on team ").append(team).append('.');
        } else if (w.map.teamBaseCount((int) team) == 0) {
            msg.append("There are no bases for team ").append(team).append(" on this map.");
        } else if (p.reserveRobotTeam && team == p.robotTeam) {
            msg.append("You cannot join the robot team on this server.");
        } else if (w.map.teamBaseCount((int) team) <= w.teamMembers[(int) team]) {
            msg.append("Team ").append(team).append(" is full.");
        } else {
            swapTeam(pl, (int) team);
            return SUCCESS;
        }
        return ERROR;
    }

    /**
     * The swap of C {@code Cmd_team} (command.c:409-437).
     * <p>
     * BEHAVIOR: everyone gets "NAME has swapped to team N."; a ship holding a ball drops it (C
     * {@code Detach_ball(ind, -1)}); the member counts move; with teamShareScore the
     * player's score is taken from the old team and given to the new one (C's order, so the score the new team
     * gets is the old team's new share); with limitedLives, when an opponent is in the game (not paused) the
     * swapper waits for the next round ('W', no lives, GAME_OVER, no self-destruct, count -1); a new home base of
     * the team is picked (the ship keeps flying); PLAYER_TEAM, SCORE and BASE_OWNER tell everyone (C
     * {@code Send_info_about_player}).
     */
    void swapTeam(Player pl, int team) {
        var p = w.params;
        all().append(pl.name).append(" has swapped to team ").append(team).append('.');
        if ((pl.have & com.fulcrologic.jxpilot.server.world.Has.BALL) != 0) {
            sim.balls.detach(pl, -1); // C Detach_ball(ind, -1)
        }
        w.teamMembers[pl.team]--;
        if (p.teamShareScore) {
            sim.scoring.teamScore(pl.team, -pl.score);
        }
        pl.team = team;
        w.teamMembers[pl.team]++;
        if (p.teamShareScore) {
            sim.scoring.teamScore(pl.team, pl.score);
        }
        if (p.limitedLives) {
            for (int k = 0; k < w.numPlayers; k++) {
                Player o = w.playerAt(k);
                if (!w.sameTeam(pl, o) && (o.status & Obj.PAUSE) == 0) {
                    if (pl.mychar == ' ') {
                        pl.mychar = 'W';
                    }
                    pl.life = 0;
                    pl.status |= Obj.GAME_OVER | Obj.PLAYING;
                    pl.status &= ~Obj.SELF_DESTRUCT;
                    pl.count = -1;
                    break;
                }
            }
        }
        sim.ships.pickStartPos(pl);
        w.events.add(Events.TEAM, pl.id, pl.team, 0, 0f);
        pl.scoreDirty = true;
        w.events.add(Events.BASE_OWNER, pl.homeBase, pl.id, 0, 0f);
    }

    // ================================================================ /ally

    /**
     * C {@code Cmd_ally}.
     * <p>
     * BEHAVIOR: alliances off: "Alliances are not allowed." (ERROR); no sub-command: the usage (ERROR).
     * BEHAVIOR: the sub-command is the first word (spaces or tabs), matched as a prefix of invite, cancel,
     * refuse, accept, leave, list; none or several match: the usage (ERROR). The rest of the line, leading
     * spaces dropped, is the player name.
     * BEHAVIOR: with a name (by {@link #playerByName}): invite, refuse or accept that player
     * ({@link Alliances}); other sub-commands: the usage; no match: "Name does not match any player.", several:
     * "Name matches several players." (ERROR).
     * BEHAVIOR: without: cancel the invitation, refuse or accept all, leave, list; invite: the usage (ERROR).
     */
    int ally(String arg, Player pl) {
        Alliances a = sim.alliances;
        if (!a.enabled) {
            msg.append("Alliances are not allowed.");
            return ERROR;
        }
        int start = skip(arg, 0, " \t");
        if (start == arg.length()) {
            msg.append(ALLY_USAGE);
            return ERROR;
        }
        int end = start;
        while (end < arg.length() && " \t".indexOf(arg.charAt(end)) < 0) {
            end++;
        }
        String command = arg.substring(start, end);
        String name = null;
        if (end + 1 < arg.length()) {
            name = arg.substring(skip(arg, end + 1, " "));
        }
        int cmd = -1;
        for (int i = 0; i < ALLY_CMDS.length; i++) {
            if (ALLY_CMDS[i].regionMatches(true, 0, command, 0, command.length())
                && command.length() <= ALLY_CMDS[i].length()) {
                cmd = cmd == -1 ? i : -2;
            }
        }
        if (cmd < 0) {
            msg.append(ALLY_USAGE);
            return ERROR;
        }
        if (name != null) {
            Player other = find(name);
            if (other == null) {
                return ERROR;
            }
            switch (cmd) {
                case ALLY_INVITE -> a.invite(pl, other);
                case ALLY_REFUSE -> a.refuse(pl, other);
                case ALLY_ACCEPT -> a.accept(pl, other);
                default -> {
                    msg.append(ALLY_USAGE);
                    return ERROR;
                }
            }
            return SUCCESS;
        }
        switch (cmd) {
            case ALLY_CANCEL -> a.cancelInvitation(pl);
            case ALLY_REFUSE -> a.refuseAll(pl);
            case ALLY_ACCEPT -> a.acceptAll(pl);
            case ALLY_LEAVE -> a.leave(pl);
            case ALLY_LIST -> a.list(pl);
            default -> {
                msg.append(ALLY_USAGE);
                return ERROR;
            }
        }
        return SUCCESS;
    }

    // ================================================================ helpers

    /**
     * {@link #playerByName}, with C's reply for a failure: "Name does not match any player." or "Name matches
     * several players.".
     */
    private Player find(String name) {
        int[] code = new int[1];
        Player p = playerByName(name, code);
        if (p == null) {
            msg.append(code[0] == SEVERAL ? "Name matches several players." : "Name does not match any player.");
        }
        return p;
    }

    /**
     * C {@code Get_player_index_by_name}.
     * <p>
     * BEHAVIOR: empty: none. Starting with a digit: the player in the game with that id (C {@code atoi}), else
     * none. Otherwise an exact name (any case), else the only player whose name, or a robot's real name
     * (robotRealName; humans have none on our protocol), starts with it (any case); several: SEVERAL.
     *
     * @param code receives NO_MATCH or SEVERAL when the result is null
     */
    Player playerByName(String name, int[] code) {
        code[0] = NO_MATCH;
        if (name.isEmpty()) {
            return null;
        }
        if (name.charAt(0) >= '0' && name.charAt(0) <= '9') {
            int digits = 0;
            while (digits < name.length() && name.charAt(digits) >= '0' && name.charAt(digits) <= '9') {
                digits++;
            }
            long id = Talk.parseTeam(name, digits);
            Player p = id < World.MAX_IDS ? w.byId((int) id) : null;
            return p != null && p.spawned ? p : null;
        }
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (p.name.length() == name.length() && Talk.equalsIgnoreCase(p.name, name, 0, name.length())) {
                return p;
            }
        }
        String realName = w.options.getString(Opt.ROBOT_REAL_NAME);
        Player found = null;
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (Talk.equalsIgnoreCase(name, p.name, 0, name.length())
                || (p.robot && realName != null && Talk.equalsIgnoreCase(name, realName, 0, name.length()))) {
                if (found != null) {
                    code[0] = SEVERAL;
                    return null;
                }
                found = p;
            }
        }
        return found;
    }

    /** C {@code strtok(s, delims)} repeated: at most {@code max} words. */
    static String[] strtok(String s, String delims, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = 0;
        while (out.size() < max) {
            i = skip(s, i, delims);
            if (i == s.length()) {
                break;
            }
            int end = i;
            while (end < s.length() && delims.indexOf(s.charAt(end)) < 0) {
                end++;
            }
            out.add(s.substring(i, end));
            i = end;
        }
        return out.toArray(new String[0]);
    }

    private static int skip(String s, int i, String chars) {
        while (i < s.length() && chars.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return i;
    }

    private void tell(Player to, CharSequence text) {
        w.events.playerMessage(Msg.Text.KIND_SERVER, Obj.NO_ID, to.id).append(text);
    }

    private StringBuilder all() {
        return w.events.message(Msg.Text.KIND_SERVER, Obj.NO_ID);
    }
}
