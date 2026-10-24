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

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static com.fulcrologic.jxpilot.server.host.HostFx.TEAMS;
import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.addRobots;
import static com.fulcrologic.jxpilot.server.host.HostFx.chat;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static com.fulcrologic.jxpilot.server.host.HostFx.events;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The talk commands (C command.c) through the real host: a CHAT line starting with '/' as the client sends it.
 * Events render as "MESSAGE kind from to text" (to 0 = everyone; replies and notices are KIND_SERVER from NO_ID).
 */
@DisplayName("Commands (C command.c)")
class CommandsTest {
    static final int SERVER = Msg.Text.KIND_SERVER;

    /** A host with players; {@link #say} returns the events one chat line makes. */
    static final class Fx {
        final Simulation s;
        final World w;
        final SimGameHost h;
        final HostFx.Kicks kicks;

        Fx(String extra, String... rows) {
            s = sim(extra, rows);
            w = s.world;
            h = new SimGameHost(s);
            kicks = new HostFx.Kicks(h);
        }

        List<String> say(int from, String line) {
            long seq = w.events.seq();
            h.clientMessage(from, ReliableType.CHAT, chat(line));
            return events(w, seq);
        }

        /** Give {@code id} operator status the C way. */
        void op(int id) {
            w.options.force(Opt.PASSWORD, "pw");
            say(id, "/password pw");
        }

        Player p(int id) {
            return w.byId(id);
        }
    }

    static String reply(int to, String text) {
        return "MESSAGE " + SERVER + " -1 " + to + " " + text + " [*Server reply*]";
    }

    static String all(String text) {
        return "MESSAGE " + SERVER + " -1 0 " + text;
    }

    @Nested
    @DisplayName("Handle_player_command: dispatch")
    class Dispatch {
        @Test
        @DisplayName("an empty command, an unknown one, abbreviations, and a chat line without '/' is talk")
        void dispatch() {
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            assertAll(
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 " + ann
                    + " No command given.  Type /help for help.  [*Server reply*]"), f.say(ann, "/"), "a lone '/'"),
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 " + ann + " Unknown command 'xyz'.  [*Server reply*]"),
                    f.say(ann, "/xyz 1 2"), "unknown: the first word is quoted"),
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 " + ann + " Unknown command 'pa'.  [*Server reply*]"),
                    f.say(ann, "/pa"), "'pa' is neither 'pas' nor 'pau'"),
                () -> assertEquals(List.of(reply(ann, "XPilot version 4.5.5.")), f.say(ann, "/V"), "abbreviation 'v', any case"),
                () -> assertEquals(List.of(reply(ann, "XPilot version 4.5.5.")), f.say(ann, "/versionxyz"),
                    "the abbreviation only has to start the word"),
                () -> assertEquals(List.of(reply(ann, "The game is currently unlocked.")), f.say(ann, "/list"),
                    "'/list' is /lock (abbreviation 'l', as C)"),
                () -> assertEquals(List.of("MESSAGE " + Msg.Text.KIND_CHAT + " " + ann + " 0 hi [Ann]"), f.say(ann, "hi"),
                    "a line without '/' is talk"),
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 " + ann + " Unknown command ' v'.  [*Server reply*]"),
                    f.say(ann, "/ v"), "the arguments start after a space found from the second character on"));
        }

        @Test
        @DisplayName("commands need a player in the game; a reply is cut to MSG_LEN - 1 characters")
        void edges() {
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            int res = f.h.join("Res", 0, "");
            String longArg = "x".repeat(300);
            long seq = f.w.events.seq();
            f.h.commands.handle(f.p(ann), "help " + longArg);
            List<String> got = events(f.w, seq);
            List<String> chat = f.say(ann, "/help " + longArg);
            String prefix = "MESSAGE " + SERVER + " -1 " + ann + " ";
            assertAll(
                () -> assertEquals(List.of(), f.say(res, "/version"), "a reserved player's command is ignored"),
                () -> assertEquals(1, got.size(), "one reply"),
                () -> assertEquals(prefix.length() + 255, got.get(0).length(),
                    "cut to 255 characters (C strlcat into msg[MSG_LEN])"),
                () -> assertEquals(List.of(reply(ann, "No help for nonexistent command '" + "x".repeat(79 - 6) + "'.")),
                    chat, "a CHAT line is cut to 79 characters before the command sees it (C Receive_talk)"));
        }
    }

    @Nested
    @DisplayName("/help, /version, /queue, /advance")
    class Info {
        @Test
        @DisplayName("help lists the commands, explains one by its abbreviation, or names a nonexistent one")
        void help() {
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            assertAll(
                () -> assertEquals(List.of(reply(ann,
                    "Commands: advance ally get help kick lock password pause queue reset set team version ")),
                    f.say(ann, "/help"), "every name followed by a space"),
                () -> assertEquals(List.of(reply(ann, "/set <option> <value>.  Sets a server option.  (operator)")),
                    f.say(ann, "/help sx"), "matched over the abbreviation's length ('s')"),
                () -> assertEquals(List.of(reply(ann, "/password <string>.  If string matches -password option "
                    + "gives operator status.")), f.say(ann, "/h PASSWORD"), "any case"),
                () -> assertEquals(List.of(reply(ann, "No help for nonexistent command 'pa'.")), f.say(ann, "/help pa"),
                    "'pa' is shorter than 'pas'/'pau'"));
        }

        @Test
        @DisplayName("queue is always empty (no login queue); advance needs an operator and a name")
        void queue() {
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            List<String> notOp = f.say(ann, "/advance Bob");
            f.op(ann);
            assertAll(
                () -> assertEquals(List.of(reply(ann, "The queue is empty.")), f.say(ann, "/queue"), "queue"),
                () -> assertEquals(List.of(reply(ann, "You need operator status to use this command.")), notOp,
                    "advance: operators only"),
                () -> assertEquals(List.of(reply(ann, "You must give a player name as an argument.")),
                    f.say(ann, "/advance"), "advance: a name is needed"),
                () -> assertEquals(List.of(reply(ann, "Name too long.")), f.say(ann, "/advance " + "n".repeat(16)),
                    "16 characters are too long"),
                () -> assertEquals(List.of(reply(ann, "Player \"" + "n".repeat(15) + "\" not in queue.")),
                    f.say(ann, "/advance " + "n".repeat(15)), "15 are not"));
        }
    }

    @Nested
    @DisplayName("/password")
    class Password {
        @Test
        @DisplayName("the password gives operator status; a wrong one takes it away; no password option: always wrong")
        void password() {
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            List<String> noOption = f.say(ann, "/password ");
            f.w.options.force(Opt.PASSWORD, "Sesame");
            List<String> wrongCase = f.say(ann, "/password sesame");
            List<String> right = f.say(ann, "/password Sesame");
            boolean op = f.p(ann).isOperator;
            List<String> again = f.say(ann, "/password Sesame");
            List<String> lost = f.say(ann, "/pas nope");
            assertAll(
                () -> assertEquals(List.of(reply(ann, "Wrong.")), noOption, "no password option"),
                () -> assertEquals(List.of(reply(ann, "Wrong.")), wrongCase, "exact comparison"),
                () -> assertEquals(List.of(reply(ann, "You got operator status.")), right, "the password"),
                () -> assertTrue(op, "operator status"),
                () -> assertEquals(List.of(reply(ann, "You got operator status.")), again, "again: the same reply"),
                () -> assertEquals(List.of(reply(ann, "Wrong.  You lost operator status.")), lost,
                    "a wrong password takes it away"),
                () -> assertFalse(f.p(ann).isOperator, "no longer an operator"));
        }

        @Test
        @DisplayName("operator status ends with the player: a new player in the slot is no operator (Init_player)")
        void reset() {
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            f.op(ann);
            boolean was = f.p(ann).isOperator;
            f.h.leave(ann);
            int bob = enter(f.h, "Bob", 0);
            assertAll(
                () -> assertTrue(was, "Ann was an operator"),
                () -> assertEquals(ann, bob, "Bob reuses the slot and id"),
                () -> assertFalse(f.p(bob).isOperator, "Bob is not"));
        }
    }

    @Nested
    @DisplayName("/lock")
    class Lock {
        @Test
        @DisplayName("status for anyone; operators lock and unlock; a locked game refuses new players")
        void lock() {
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            int bob = enter(f.h, "Bob", 0);
            List<String> notOp = f.say(bob, "/lock 1");
            f.op(ann);
            List<String> bad = f.say(ann, "/lock yes");
            List<String> already = f.say(ann, "/lock 0");
            List<String> locked = f.say(ann, "/lock 1");
            List<String> status = f.say(bob, "/lock");
            int refused = f.h.join("Cid", 0, "");
            List<String> again = f.say(ann, "/lock 1");
            List<String> unlocked = f.say(ann, "/l 0");
            int cid = f.h.join("Cid", 0, "");
            assertAll(
                () -> assertEquals(List.of(reply(bob, "You need operator status to use this command.")), notOp,
                    "changing the lock needs an operator"),
                () -> assertEquals(List.of(reply(ann, "Invalid argument 'yes'.  Specify either 0 or 1.")), bad, "0 or 1 only"),
                () -> assertEquals(List.of(reply(ann, "Game is already unlocked.")), already, "no change"),
                () -> assertEquals(List.of(all(" < The game has been locked by Ann! >")), locked, "locked, announced"),
                () -> assertEquals(List.of(reply(bob, "The game is currently locked.")), status, "status for anyone"),
                () -> assertEquals(-Msg.Reject.REASON_LOCKED, refused, "a locked game refuses a newcomer (E_GAME_LOCKED)"),
                () -> assertEquals(List.of(reply(ann, "Game is already locked.")), again, "already locked"),
                () -> assertEquals(List.of(all(" < The game has been unlocked by Ann! >")), unlocked, "unlocked"),
                () -> assertTrue(cid > 0, "then a newcomer gets in"));
        }
    }

    @Nested
    @DisplayName("/kick")
    class Kick {
        @Test
        @DisplayName("kicks a human (connection dropped: \"kicked out\") or a robot, then the notice; failures reply")
        void kick() {
            Fx f = new Fx("maxRobots: 1\nminRobots: 1\n", THREE);
            int ann = enter(f.h, "Ann", 0);
            int bob = enter(f.h, "Bob", 0);
            addRobots(f.s, 1);
            Player robot = null;
            for (int k = 0; k < f.w.numPlayers; k++) {
                if (f.w.playerAt(k).robot) {
                    robot = f.w.playerAt(k);
                }
            }
            String robotName = robot.name.toString();
            List<String> notOp = f.say(bob, "/kick Ann");
            f.op(ann);
            List<String> noName = f.say(ann, "/kick");
            List<String> none = f.say(ann, "/kick Zed");
            List<String> kickedBob = f.say(ann, "/kick bob");
            List<String> kickedRobot = f.say(ann, "/k " + robotName.toLowerCase());
            assertAll(
                () -> assertEquals(List.of(reply(bob, "You need operator status to use this command.")), notOp, "operators only"),
                () -> assertEquals(List.of(reply(ann, "You must give a player name as an argument.")), noName, "a name"),
                () -> assertEquals(List.of(reply(ann, "Name does not match any player.")), none, "no match"),
                () -> assertEquals(List.of("LEAVE " + bob + " 0 0", all("Ann kicked Bob out! [*Server notice*]")),
                    HostFx.only(kickedBob, "LEAVE", "MESSAGE"), "Bob leaves, then the notice"),
                () -> assertEquals(List.of(bob + " kicked out"), f.kicks.calls, "Bob's connection is dropped with \"kicked out\""),
                () -> assertEquals(List.of(all("Ann kicked " + robotName + " out! [*Server notice*]")),
                    HostFx.only(kickedRobot, "MESSAGE"), "a robot is deleted, with the notice (no \"upset the gods\")"),
                () -> assertEquals(0, f.s.robots.numRobots(), "the robot is gone"),
                () -> assertEquals(1, f.w.numPlayers, "only Ann is left"));
        }

        @Test
        @DisplayName("names: by id, exact, unique prefix, several matches; an operator kicking themself goes at the next tick")
        void names() {
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            int anna = enter(f.h, "Anna", 0);
            int bob = enter(f.h, "Bob", 0);
            f.op(ann);
            List<String> several = f.say(ann, "/kick An");
            List<String> badId = f.say(ann, "/kick 9");
            List<String> exact = f.say(ann, "/kick ann ");
            List<String> byId = f.say(ann, "/kick " + bob);
            List<String> self = f.say(ann, "/kick ann");
            boolean stillIn = f.p(ann) != null && f.p(ann).spawned;
            f.h.tick(1);
            assertAll(
                () -> assertEquals(List.of(reply(ann, "Name matches several players.")), several, "Ann and Anna"),
                () -> assertEquals(List.of(reply(ann, "Name does not match any player.")), badId, "no player 9"),
                () -> assertEquals(List.of(reply(ann, "Name does not match any player.")), exact,
                    "the name is the whole rest of the line ('ann ' matches nobody)"),
                () -> assertEquals(List.of(all("Ann kicked Bob out! [*Server notice*]")), HostFx.only(byId, "MESSAGE"),
                    "an id"),
                () -> assertEquals(List.of(all("Ann kicked Ann out! [*Server notice*]")), HostFx.only(self, "MESSAGE"),
                    "an exact name beats Anna's prefix"),
                () -> assertTrue(stillIn, "kicking oneself waits for the tick (the own message is being delivered)"),
                () -> assertEquals(List.of(bob + " kicked out", ann + " kicked out"), f.kicks.calls, "both dropped"),
                () -> assertTrue(f.w.byId(anna).spawned, "Anna stays"));
        }

        @Test
        @DisplayName("a robot also matches by its real name (robotRealName, C realname)")
        void realName() {
            Fx f = new Fx("maxRobots: 2\nminRobots: 2\n", THREE);
            int ann = enter(f.h, "Ann", 0);
            addRobots(f.s, 2);
            f.op(ann);
            List<String> both = f.say(ann, "/kick rob");
            f.w.options.force(Opt.ROBOT_REAL_NAME, "Zork");
            List<String> real = f.say(ann, "/kick zo");
            assertAll(
                () -> assertEquals(List.of(reply(ann, "Name matches several players.")), both, "both robots are 'robot'"),
                () -> assertEquals(List.of(reply(ann, "Name does not match any player.")), real,
                    "robots keep the real name they joined with (robot.c:784)"));
        }
    }

    @Nested
    @DisplayName("/pause")
    class Pause {
        @Test
        @DisplayName("an operator pauses a connected player (a flying ship dies first); robots and unknowns cannot be")
        void pause() {
            Fx f = new Fx("maxRobots: 1\nminRobots: 1\n", THREE);
            int ann = enter(f.h, "Ann", 0);
            int bob = enter(f.h, "Bob", 0);
            addRobots(f.s, 1);
            Player robot = null;
            for (int k = 0; k < f.w.numPlayers; k++) {
                if (f.w.playerAt(k).robot) {
                    robot = f.w.playerAt(k);
                }
            }
            String robotName = robot.name.toString();
            List<String> notOp = f.say(bob, "/pause Ann");
            f.op(ann);
            List<String> noName = f.say(ann, "/pause");
            List<String> robotTry = f.say(ann, "/pause " + robotName);
            int deaths = f.p(bob).deaths;
            List<String> paused = f.say(ann, "/pau Bob");
            assertAll(
                () -> assertEquals(List.of(reply(bob, "You need operator status to use this command.")), notOp, "operators only"),
                () -> assertEquals(List.of(reply(ann, "You must give a player name as an argument.")), noName, "a name"),
                () -> assertEquals(List.of(reply(ann, "Invalid player id.")), robotTry, "a robot has no connection"),
                () -> assertEquals(List.of(all("Bob was paused by Ann.")), HostFx.only(paused, "MESSAGE"), "the notice"),
                () -> assertTrue((f.p(bob).status & Obj.PAUSE) != 0, "Bob is paused"),
                () -> assertEquals(deaths + 1, f.p(bob).deaths, "the flying ship was killed first (C Kill_player)"),
                () -> assertTrue((f.p(bob).status & Obj.PLAYING) == 0, "and is not flying"),
                () -> assertEquals(List.of(reply(ann, "Invalid player id.")), f.say(ann, "/pause Zed"), "unknown"));
        }
    }

    @Nested
    @DisplayName("/reset")
    class Reset {
        @Test
        @DisplayName("an operator resets the round, or with 'all' every score and the round count too")
        void reset() {
            Fx g = new Fx("teamPlay: yes\nreserveRobotTeam: no\n", TEAMS);
            int ann = enter(g.h, "Ann", 1);
            int bob = enter(g.h, "Bob", 2);
            g.p(ann).score = 50;
            g.w.teamScore[1] = 50;
            g.w.roundsPlayed = 3;
            g.w.gameDuration = -1;
            List<String> notOp = g.say(bob, "/reset");
            g.op(ann);
            List<String> round = g.say(ann, "/reset");
            double scoreAfterRound = g.p(ann).score;
            int roundsAfterRound = g.w.roundsPlayed;
            float durationAfter = g.w.gameDuration;
            List<String> total = g.say(ann, "/r ALL");
            assertAll(
                () -> assertEquals(List.of(reply(bob, "You need operator status to use this command.")), notOp, "operators only"),
                () -> assertTrue(HostFx.only(round, "MESSAGE").contains(all(" < Round reset by Ann! >")), "round reset"),
                () -> assertEquals(50.0, scoreAfterRound, "a round reset keeps scores"),
                () -> assertEquals(3, roundsAfterRound, "and the round count"),
                () -> assertEquals(0f, durationAfter, "a finished game (gameDuration -1) restarts"),
                () -> assertTrue(HostFx.only(total, "MESSAGE").contains(all(" < Total reset by Ann! >")), "total reset"),
                () -> assertEquals(0.0, g.p(ann).score, "scores are 0"),
                () -> assertEquals(0.0, g.w.teamScore[1], "team scores are 0"),
                () -> assertEquals(0, g.w.roundsPlayed, "rounds played 0"));
        }
    }

    @Nested
    @DisplayName("/get")
    class Get {
        @Test
        @DisplayName("usage, refused options, values, unknown, invisible and unset options")
        void get() {
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            assertAll(
                () -> assertEquals(List.of(reply(ann, "Usage: /get option.")), f.say(ann, "/get"), "usage"),
                () -> assertEquals(List.of(reply(ann, "Cannot retrieve that option.")), f.say(ann, "/get PassWord"),
                    "password"),
                () -> assertEquals(List.of(reply(ann, "Cannot retrieve that option.")), f.say(ann, "/get mapdata"), "mapData"),
                () -> assertEquals(List.of(reply(ann, "The value of shots is 256.")), f.say(ann, "/g shots"),
                    "by the command-line name, as typed"),
                () -> assertEquals(List.of(reply(ann, "No option named nope.")), f.say(ann, "/get nope"), "unknown"),
                () -> assertEquals(List.of(reply(ann, "Cannot show the value of this option.")), f.say(ann, "/get expand"),
                    "not visible"),
                () -> assertEquals(List.of(reply(ann, "No value has been set for option serverHost.")),
                    f.say(ann, "/get serverHost"), "a visible string without a value"),
                () -> assertEquals(List.of(reply(ann, "Cannot show the value of this option.")),
                    f.say(ann, "/get robotFile"), "robotFile is not visible"),
                () -> assertEquals(List.of(reply(ann, "Generic error.")), f.say(ann, "/get gravityPoint"),
                    "a position (C falls through to the error)"),
                () -> assertEquals(List.of(reply(ann, "The value of defaultShipShape is "
                    + Opt.DEFAULT_SHIP_SHAPE.defaultValue().substring(0, 79) + ".")), f.say(ann, "/get defaultShipShape"),
                    "a value is cut to MAX_CHARS - 1 = 79 characters"));
        }
    }

    @Nested
    @DisplayName("/set")
    class Set {
        @Test
        @DisplayName("operators set options: announced value, password quietly, refusals with C's texts")
        void set() {
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            int bob = enter(f.h, "Bob", 0);
            List<String> notOp = f.say(bob, "/set shotSpeed 30");
            f.op(ann);
            assertAll(
                () -> assertEquals(List.of(reply(bob, "You need operator status to use this command.")), notOp, "operators only"),
                () -> assertEquals(List.of(reply(ann, "Usage: /set option value.")), f.say(ann, "/set shotSpeed"), "usage"),
                () -> assertEquals(List.of(reply(ann, "Usage: /set option value.")), f.say(ann, "/set"), "usage, nothing"),
                () -> assertEquals(List.of(all(" < Option ShotSpeed set to 30.5 by Ann. >")),
                    f.say(ann, "/set ShotSpeed 30.5 extra"), "the option as typed, the value as /get shows it"),
                () -> assertEquals(30.5f, f.w.params.shotSpeed, "the simulation uses it"),
                () -> assertEquals(List.of(reply(ann, "Operation successful.")), f.say(ann, "/s password new"),
                    "password: no announcement"),
                () -> assertEquals("new", f.w.options.getString(Opt.PASSWORD), "the new password"),
                () -> assertEquals(List.of(reply(ann, "Invalid value.")), f.say(ann, "/set shots many"), "not a number"),
                () -> assertEquals(List.of(reply(ann, "This option cannot be changed at runtime.")),
                    f.say(ann, "/set mapWidth 10"), "tuner_none"),
                () -> assertEquals(List.of(reply(ann, "No option named 'zz'.")), f.say(ann, "/set zz 1"), "unknown"));
        }
    }

    @Nested
    @DisplayName("/team and CHANGE_TEAM")
    class Team {
        @Test
        @DisplayName("refusals with C's texts")
        void refusals() {
            Fx none = new Fx("", THREE);
            int solo = enter(none.h, "Solo", 0);
            Fx f = new Fx("teamPlay: yes\nreserveRobotTeam: yes\nrobotTeam: 0\n", TEAMS);
            int ann = enter(f.h, "Ann", 1);
            int bob = enter(f.h, "Bob", 2);
            assertAll(
                () -> assertEquals(List.of(reply(solo, "No team play going on.")), none.say(solo, "/team 1"), "no team play"),
                () -> assertEquals(List.of(reply(ann, "Invalid team specification.")), f.say(ann, "/team"), "no argument"),
                () -> assertEquals(List.of(reply(ann, "Invalid team specification.")), f.say(ann, "/team x1"), "not a digit"),
                () -> assertEquals(List.of(reply(ann, "Team 12 is not a valid team.")), f.say(ann, "/team 12"), "10 or more"),
                () -> assertEquals(List.of(reply(ann, "You already are on team 1.")), f.say(ann, "/t 1"), "own team"),
                () -> assertEquals(List.of(reply(ann, "There are no bases for team 5 on this map.")), f.say(ann, "/team 5"),
                    "no bases"),
                () -> assertEquals(List.of(reply(ann, "You cannot join the robot team on this server.")),
                    f.say(ann, "/team 0"), "the reserved robot team"),
                () -> assertEquals(List.of(reply(ann, "Team 2 is full.")), f.say(ann, "/team 2"), "team 2's base is Bob's"));
        }

        @Test
        @DisplayName("a swap: notice, members, a new home base of the team, PLAYER_TEAM / BASE_OWNER / SCORE out")
        void swap() {
            Fx f = new Fx("teamPlay: yes\nreserveRobotTeam: no\n", TEAMS);
            int ann = enter(f.h, "Ann", 2);
            Player a = f.p(ann);
            int x = a.px;
            List<String> got = f.say(ann, "/team 1");
            f.h.tick(1);
            HostFx.Wire wire = new HostFx.Wire();
            assertAll(
                () -> assertEquals(List.of(all("Ann has swapped to team 1."), "BASE_OWNER " + a.homeBase + " " + ann + " 0",
                    "TEAM " + ann + " 1 0", "BASE_OWNER " + a.homeBase + " " + ann + " 0"), got,
                    "notice, the new base (Pick_startpos), then team and base again (Send_info_about_player); no reply"),
                () -> assertEquals(1, a.team, "on team 1"),
                () -> assertEquals(0, f.w.teamMembers[2], "team 2 lost a member"),
                () -> assertEquals(1, f.w.teamMembers[1], "team 1 gained one"),
                () -> assertEquals(1, f.w.map.bases().get(a.homeBase).team(), "the home base is one of team 1"),
                () -> assertEquals(x, a.px, "the ship keeps flying where it is (C does not send it home)"));
        }

        @Test
        @DisplayName("teamShareScore moves the score in C's order; limitedLives makes the swapper wait")
        void scoreAndLives() {
            Fx f = new Fx("teamPlay: yes\nreserveRobotTeam: no\nteamShareScore: yes\nlimitedLives: yes\nworldLives: 3\n", TEAMS);
            int ann = enter(f.h, "Ann", 1);
            int bob = enter(f.h, "Bob", 1);
            int cid = enter(f.h, "Cid", 0);
            f.s.scoring.teamScore(1, 30);
            f.say(ann, "/team 2");
            Player a = f.p(ann);
            assertAll(
                () -> assertEquals(15.0, f.w.teamScore[1], "team 1 keeps 30 - Ann's 15"),
                () -> assertEquals(15.0, f.p(bob).score, "shared by Bob alone"),
                () -> assertEquals(15.0, f.w.teamScore[2], "team 2 gets Ann's score as re-shared by the old team (C order)"),
                () -> assertEquals(15.0, a.score, "which Ann then holds alone"),
                () -> assertEquals('W', a.mychar, "Ann waits"),
                () -> assertEquals(0, a.life, "without lives"),
                () -> assertTrue((a.status & Obj.GAME_OVER) != 0, "GAME_OVER"),
                () -> assertEquals(-1, a.count, "count -1"));
        }

        @Test
        @DisplayName("CHANGE_TEAM is the command \"/team N\"")
        void changeTeam() {
            Fx f = new Fx("teamPlay: yes\nreserveRobotTeam: no\n", TEAMS);
            int ann = enter(f.h, "Ann", 2);
            long seq = f.w.events.seq();
            f.h.clientMessage(ann, ReliableType.CHANGE_TEAM, ByteBuffer.wrap(new byte[] {1}));
            List<String> got = events(f.w, seq);
            seq = f.w.events.seq();
            f.h.clientMessage(ann, ReliableType.CHANGE_TEAM, ByteBuffer.wrap(new byte[] {(byte) 255}));
            List<String> bad = events(f.w, seq);
            assertAll(
                () -> assertEquals(all("Ann has swapped to team 1."), got.get(0), "swapped"),
                () -> assertEquals(1, f.p(ann).team, "team 1"),
                () -> assertEquals(List.of(reply(ann, "Team 255 is not a valid team.")), bad, "NO_TEAM is no team"));
        }
    }

    @Nested
    @DisplayName("/ally")
    class Ally {
        @Test
        @DisplayName("alliances off, usage, ambiguous and unknown sub-commands, names")
        void parsing() {
            Fx off = new Fx("allowAlliances: no\n", THREE);
            int o = enter(off.h, "Ole", 0);
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            int bob = enter(f.h, "Bob", 0);
            String usage = "Usage: /ally {invite|cancel|refuse|accept|leave|list} [<player name>]";
            assertAll(
                () -> assertEquals(List.of(reply(o, "Alliances are not allowed.")), off.say(o, "/ally list"), "off"),
                () -> assertEquals(List.of(reply(ann, usage)), f.say(ann, "/ally"), "nothing"),
                () -> assertEquals(List.of(reply(ann, usage)), f.say(ann, "/ally \t "), "only blanks"),
                () -> assertEquals(List.of(reply(ann, usage)), f.say(ann, "/ally l"), "leave or list: ambiguous"),
                () -> assertEquals(List.of(reply(ann, usage)), f.say(ann, "/ally invitee Bob"), "longer than invite"),
                () -> assertEquals(List.of(reply(ann, usage)), f.say(ann, "/ally invite"), "invite needs a name"),
                () -> assertEquals(List.of(reply(ann, usage)), f.say(ann, "/ally leave Bob"), "leave takes no name"),
                () -> assertEquals(List.of(reply(ann, "Name does not match any player.")), f.say(ann, "/ally in Zed"),
                    "unknown name"),
                () -> assertEquals(List.of(reply(ann, "Name does not match any player.")), f.say(ann, "/ally invite   "),
                    "blanks after the sub-command are an empty name (C strtok)"),
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 " + bob + "  < Ann seeks an alliance with you >"),
                    f.say(ann, "/ally INV\tbob"), "a tab ends the sub-command; any case; the invitation"));
        }

        @Test
        @DisplayName("the whole cycle through commands: invite, accept, list, leave (dissolves a pair)")
        void cycle() {
            Fx f = new Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            int bob = enter(f.h, "Bob", 0);
            f.say(ann, "/ally invite Bob");
            List<String> accepted = f.say(bob, "/ally accept Ann");
            List<String> list = f.say(ann, "/ally list");
            List<String> left = f.say(bob, "/ally leave");
            assertAll(
                () -> assertEquals(List.of("ALLIANCE " + bob + " 0 0", "ALLIANCE " + ann + " 0 0",
                    "MESSAGE " + SERVER + " -1 " + bob + "  < You have formed an alliance with Ann >",
                    "MESSAGE " + SERVER + " -1 " + ann + "  < You have formed an alliance with Bob >"), accepted,
                    "Bob (the accepting player) is added first"),
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 " + ann + "  < Your alliance: Ann, Bob >"), list, "list"),
                () -> assertEquals(List.of("ALLIANCE " + bob + " -1 0",
                    "MESSAGE " + SERVER + " -1 " + ann + "  < Bob has left your alliance >",
                    "MESSAGE " + SERVER + " -1 " + bob + "  < You have left the alliance >",
                    "ALLIANCE " + ann + " -1 0",
                    "MESSAGE " + SERVER + " -1 " + ann + "  < Your alliance has been dissolved >"), left, "leave, dissolve"));
        }
    }
}
