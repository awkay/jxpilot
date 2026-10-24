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
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.TEAMS;
import static com.fulcrologic.jxpilot.server.host.HostFx.TWO;
import static com.fulcrologic.jxpilot.server.host.HostFx.addRobots;
import static com.fulcrologic.jxpilot.server.host.HostFx.empty;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static com.fulcrologic.jxpilot.server.host.HostFx.events;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SimGameHost login (C contact.c Check_queue, Check_names, Kick_*_players), team, leave and the id mapping. */
@DisplayName("SimGameHost: login, team, leave")
class SimGameHostLoginTest {
    static final int FULL = -Msg.Reject.REASON_FULL;
    static final int BAD_NAME = -Msg.Reject.REASON_BAD_NAME;
    static final int OTHER = -Msg.Reject.REASON_OTHER;

    static Player byName(World w, String name) {
        for (Player p : w.players) {
            if (p.inUse && p.name.toString().equals(name)) {
                return p;
            }
        }
        return null;
    }

    static Player firstRobot(World w) {
        for (int k = 0; k < w.numPlayers; k++) {
            if (w.playerAt(k).robot) {
                return w.playerAt(k);
            }
        }
        return null;
    }

    @Nested
    @DisplayName("join: room in the game")
    class Reserve {
        @Test
        @DisplayName("reserves a slot and id: nothing in the game sees the player before READY")
        void reserves() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            long from = w.events.seq();
            int first = h.join("Ann", 3, "(1,2)");
            int second = h.join("Bob", Protocol.NO_TEAM, "");
            Player ann = w.byId(first);
            assertAll(
                () -> assertEquals(1, first, "the first player gets sim id 1, which is its network id"),
                () -> assertEquals(2, second, "the next one id 2"),
                () -> assertEquals("Ann", ann.name.toString(), "the name is the player's"),
                () -> assertTrue(ann.inUse, "the slot is taken"),
                () -> assertFalse(ann.spawned, "the player is not in the game before READY"),
                () -> assertEquals(0, w.numPlayers, "nobody is in the game"),
                () -> assertEquals(List.of(), events(w, from), "no event announces a reserved player"),
                () -> assertEquals(Protocol.NO_TEAM, h.team(first), "outside team play the request is ignored: NO_TEAM"),
                () -> assertEquals(Protocol.NO_TEAM, h.team(0), "an id that is not a player has NO_TEAM"),
                () -> assertEquals(Protocol.NO_TEAM, h.team(7), "an id nobody joined with has NO_TEAM"),
                () -> assertEquals(Protocol.NO_TEAM, h.team(World.MAX_IDS), "an id beyond the range has NO_TEAM"));
        }
    }

    @Nested
    @DisplayName("join: a full game (C Check_queue)")
    class FullGame {
        @Test
        @DisplayName("kicks a robot first (Kick_robot_players) and takes its slot")
        void kicksRobot() {
            Simulation s = sim("maxRobots: 1\nminRobots: 1\n", TWO);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            HostFx.Kicks kicks = new HostFx.Kicks(h);
            int ann = enter(h, "Ann", 0);
            addRobots(s, 1);
            Player robot = firstRobot(w);
            int robotId = robot.id;
            String robotName = robot.name.toString();
            long from = w.events.seq();
            int cid = h.join("Cid", 0, "");
            assertAll(
                () -> assertEquals(robotId, cid, "the joiner gets the kicked robot's id (the lowest free one)"),
                () -> assertEquals(0, s.robots.numRobots(), "the robot is gone"),
                () -> assertEquals(1, w.numPlayers, "only Ann is left in the game"),
                () -> assertTrue(events(w, from).contains("MESSAGE 4 -1 0 \"" + robotName
                    + "\" upset the gods and was kicked out of the game."), "the kick is announced: " + events(w, from)),
                () -> assertTrue(events(w, from).contains("LEAVE " + robotId + " 0 0"), "the robot leaves the game"),
                () -> assertEquals(List.of(), kicks.calls, "no connection is dropped for a robot"),
                () -> assertEquals(1, ann, "Ann keeps her id"));
        }

        @Test
        @DisplayName("else kicks every paused human, last first, each announced after its leave (C Kick_paused_players)")
        void kicksPaused() {
            Simulation s = sim("", TWO);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            HostFx.Kicks kicks = new HostFx.Kicks(h);
            int ann = enter(h, "Ann", 0);
            int bob = enter(h, "Bob", 0);
            s.ships.pause(w.byId(ann), true);
            s.ships.pause(w.byId(bob), true);
            long from = w.events.seq();
            int cid = h.join("Cid", 0, "");
            assertAll(
                () -> assertEquals(List.of(bob + " no pause with full game", ann + " no pause with full game"), kicks.calls,
                    "both connections are dropped, the last player first, with C's reason"),
                () -> assertEquals(List.of(
                        "BASE_OWNER 0 -1 0", "LEAVE 2 0 0",
                        "MESSAGE 3 -1 0 The paused \"Bob\" was kicked because the game is full.",
                        "BASE_OWNER 1 -1 0", "LEAVE 1 0 0",
                        "MESSAGE 3 -1 0 The paused \"Ann\" was kicked because the game is full."),
                    events(w, from), "C order: Destroy_connection (the leave) then Set_message"),
                () -> assertEquals(1, cid, "the joiner gets the lowest freed id"),
                () -> assertEquals(0, w.numPlayers, "nobody is left in the game"));
        }

        @Test
        @DisplayName("kicks only the paused humans")
        void kicksOnlyPaused() {
            Simulation s = sim("", TWO);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            HostFx.Kicks kicks = new HostFx.Kicks(h);
            int ann = enter(h, "Ann", 0);
            enter(h, "Bob", 0);
            s.ships.pause(w.byId(ann), true);
            int cid = h.join("Cid", 0, "");
            assertAll(
                () -> assertEquals(List.of(ann + " no pause with full game"), kicks.calls, "only Ann was paused"),
                () -> assertEquals(ann, cid, "Cid takes Ann's id"),
                () -> assertEquals("Bob", w.playerAt(0).name.toString(), "Bob plays on"));
        }

        @Test
        @DisplayName("queues (REASON_QUEUED, C contact.c) when nothing can be kicked; reserved players count as taken")
        void refuses() {
            Simulation s = sim("", TWO);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            HostFx.Kicks kicks = new HostFx.Kicks(h);
            enter(h, "Ann", 0);
            enter(h, "Bob", 0);
            long from = w.events.seq();
            int playing = h.join("Cid", 0, "");

            Simulation r = sim("", TWO);
            SimGameHost hr = new SimGameHost(r);
            hr.join("Ann", 0, "");
            hr.join("Bob", 0, "");
            int reserved = hr.join("Cid", 0, "");
            assertAll(
                () -> assertEquals(-Msg.Reject.REASON_QUEUED, playing, "two humans playing on two bases: Cid waits"),
                () -> assertEquals(1, h.queuePosition(), "first in line"),
                () -> assertEquals(List.of(), kicks.calls, "nobody is kicked"),
                () -> assertEquals(List.of(), events(w, from), "nothing happens in the game"),
                () -> assertEquals(2, w.numPlayers, "both stay"),
                () -> assertEquals(-Msg.Reject.REASON_QUEUED, reserved, "two reserved players on two bases: Cid waits"));
        }
    }

    @Nested
    @DisplayName("join: names (C Check_names)")
    class Names {
        @Test
        @DisplayName("a name in use is refused with REASON_BAD_NAME, compared without case after the trailing blanks")
        void duplicates() {
            Simulation s = sim("", THREE);
            SimGameHost h = new SimGameHost(s);
            enter(h, "Ann", 0);
            h.join("Res", 0, "");
            assertAll(
                () -> assertEquals(BAD_NAME, h.join("ann", 0, ""), "the same name in another case"),
                () -> assertEquals(BAD_NAME, h.join("ANN \t ", 0, ""), "with trailing blanks"),
                () -> assertEquals(BAD_NAME, h.join("rES", 0, ""), "a reserved player's name is taken too"),
                () -> assertEquals(BAD_NAME, h.join("   ", 0, ""), "a name of blanks only is empty"),
                () -> assertEquals(3, h.join("Ann2", 0, ""), "another name is accepted"));
        }

        @Test
        @DisplayName("names are compared as stored: cut to 16 chars")
        void clamped() {
            Simulation s = sim("", THREE);
            SimGameHost h = new SimGameHost(s);
            enter(h, "abcdefghijklmnop", 0);
            assertAll(
                () -> assertEquals(BAD_NAME, h.join("ABCDEFGHIJKLMNOPQ", 0, ""), "17 chars whose first 16 are taken"),
                () -> assertEquals(2, h.join("abcdefghijklmno", 0, ""), "15 of them are another name"),
                () -> assertEquals("abcdefghijklmno", s.world.byId(2).name.toString(), "stored as given"));
        }

        @Test
        @DisplayName("clampName: trailing ASCII blanks stripped, 16 chars, non-Latin-1 as '?'")
        void clampName() {
            StringBuilder out = new StringBuilder("old");
            SimGameHost.clampName("  ab \t ", out);
            String blanks = out.toString();
            SimGameHost.clampName("abcdefghijklmnopqrst", out);
            String cut = out.toString();
            SimGameHost.clampName("aĀbé", out);
            String latin = out.toString();
            SimGameHost.clampName("ab ", out);
            String wide = out.toString();
            SimGameHost.clampName("\u00ff\u0100", out);
            String edge = out.toString();
            SimGameHost.clampName("", out);
            String none = out.toString();
            assertAll(
                () -> assertEquals("  ab", blanks, "trailing blanks go, leading ones stay"),
                () -> assertEquals("abcdefghijklmnop", cut, "cut to MAX_NAME_LEN = 16"),
                () -> assertEquals("a?bé", latin, "a char above 0xFF becomes '?', Latin-1 stays"),
                () -> assertEquals("ab?", wide, "a non-ASCII blank is not stripped (C isascii), it becomes '?'"),
                () -> assertEquals("\u00ff?", edge, "0xFF is the last Latin-1 char kept, 0x100 the first replaced"),
                () -> assertEquals("", none, "an empty name stays empty (the buffer is cleared)"));
        }
    }

    @Nested
    @DisplayName("join: team play (C Check_queue team part)")
    class Teams {
        static final String TEAM = "teamPlay: yes\n";

        @Test
        @DisplayName("a valid team with room is kept; team() reports it and READY spawns the player on it")
        void requested() {
            Simulation s = sim(TEAM, TEAMS);
            SimGameHost h = new SimGameHost(s);
            int id = h.join("Ann", 2, "");
            int team = h.team(id);
            h.clientMessage(id, ReliableType.READY, empty());
            assertAll(
                () -> assertEquals(2, team, "the login team is 2"),
                () -> assertEquals(2, s.world.byId(id).team, "the spawn uses it"),
                () -> assertEquals(1, s.world.teamMembers[2], "team 2 has a member"));
        }

        @Test
        @DisplayName("with teamAssign a missing, invalid or reserved robot team is picked by C Pick_team")
        void assigned() {
            Simulation s = sim(TEAM, TEAMS);
            SimGameHost h = new SimGameHost(s);
            int none = h.join("Ann", Protocol.NO_TEAM, "");
            int robotTeam = h.join("Bob", 0, "");
            int invalid = h.join("Cid", Const.MAX_TEAMS, "");
            assertAll(
                () -> assertEquals(1, h.team(none), "no team: Pick_team gives team 1 (empty, lowest score, not the robot team)"),
                () -> assertEquals(1, h.team(robotTeam), "the reserved robot team 0 is replaced the same way"),
                () -> assertEquals(1, h.team(invalid), "team 10 does not exist"));
        }

        @Test
        @DisplayName("without teamAssign: no team is REASON_OTHER, the reserved robot team REASON_FULL")
        void notAssigned() {
            Simulation s = sim(TEAM + "teamAssign: no\n", TEAMS);
            SimGameHost h = new SimGameHost(s);
            assertAll(
                () -> assertEquals(OTHER, h.join("Ann", Protocol.NO_TEAM, ""), "C E_TEAM_NOT_SET"),
                () -> assertEquals(OTHER, h.join("Ann", -1, ""), "a negative team is no team"),
                () -> assertEquals(FULL, h.join("Ann", 0, ""), "C E_TEAM_FULL for the reserved robot team"),
                () -> assertEquals(1, h.join("Ann", 1, ""), "a real team is fine"));
        }

        @Test
        @DisplayName("an unreserved robot team may be asked for without teamAssign")
        void unreservedRobotTeam() {
            Simulation s = sim(TEAM + "teamAssign: no\nreserveRobotTeam: no\n", TEAMS);
            SimGameHost h = new SimGameHost(s);
            int id = h.join("Ann", 0, "");
            assertEquals(0, h.team(id), "team 0 is an ordinary team");
        }

        @Test
        @DisplayName("a full team with nothing to kick is REASON_FULL")
        void fullTeam() {
            Simulation s = sim(TEAM, TEAMS);
            SimGameHost h = new SimGameHost(s);
            HostFx.Kicks kicks = new HostFx.Kicks(h);
            enter(h, "Dee", 2);
            assertAll(
                () -> assertEquals(FULL, h.join("Eve", 2, ""), "team 2 has one base, taken by Dee"),
                () -> assertEquals(List.of(), kicks.calls, "Dee is not paused: nobody is kicked"));
        }

        @Test
        @DisplayName("a full team kicks its paused humans, then the team is picked again (as C)")
        void fullTeamKicksPaused() {
            Simulation s = sim(TEAM, TEAMS);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            HostFx.Kicks kicks = new HostFx.Kicks(h);
            int ann = enter(h, "Ann", 1);
            int dee = enter(h, "Dee", 2);
            s.ships.pause(w.byId(dee), true);
            s.ships.pause(w.byId(ann), true);
            long from = w.events.seq();
            int eve = h.join("Eve", 2, "");
            assertAll(
                () -> assertEquals(List.of(dee + " no pause with full team"), kicks.calls,
                    "Dee is dropped with C's reason; Ann, paused on team 1, is not"),
                () -> assertTrue(events(w, from).contains("MESSAGE 3 -1 0 The paused \"Dee\" was kicked because team 2 is full."),
                    "and announced: " + events(w, from)),
                () -> assertEquals(dee, eve, "Eve gets the freed id"),
                () -> assertEquals(1, h.team(eve),
                    "Pick_team again: no team playing (Ann is paused), teams 1 and 2 have room, the tie goes to team 1"),
                () -> assertEquals(1, h.team(ann), "Ann is untouched"));
        }

        @Test
        @DisplayName("a full team kicks one of its robots first")
        void fullTeamKicksRobot() {
            Simulation s = sim(TEAM + "robotTeam: 2\nreserveRobotTeam: no\nmaxRobots: 1\nminRobots: 1\n", TEAMS);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            addRobots(s, 1);
            Player robot = firstRobot(w);
            int robotTeam = robot.team;
            int eve = h.join("Eve", 2, "");
            assertAll(
                () -> assertEquals(2, robotTeam, "the robot plays on its team 2 (restrictRobots)"),
                () -> assertEquals(0, s.robots.numRobots(), "the robot is kicked"),
                () -> assertEquals(0, h.team(eve), "Pick_team: every team is empty; the lowest-scoring non-robot team is 0"));
        }

        @Test
        @DisplayName("with teamAssign, when only robots' teams have room a robot outside the robot team is kicked")
        void assignKicksRobot() {
            String[] rows = {
                "xxxxxxxxxxxxxx",
                "x            x",
                "x 0 0 1 1 2  x",
                "x            x",
                "xxxxxxxxxxxxxx",
            };
            Simulation s = sim(TEAM + "restrictRobots: no\nmaxRobots: 3\nminRobots: 3\n", rows);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            addRobots(s, 3);
            String teams = w.playerAt(0).team + "," + w.playerAt(1).team + "," + w.playerAt(2).team;
            int firstRobot = w.playerAt(0).id;
            int ann = h.join("Ann", Protocol.NO_TEAM, "");
            assertAll(
                () -> assertEquals("1,2,1", teams, "the robots fill teams 1 and 2 (Pick_team for robots)"),
                () -> assertEquals(2, s.robots.numRobots(), "one robot is kicked"),
                () -> assertEquals(firstRobot, ann,
                    "the first lowest-scoring robot outside team 0 went: Ann gets its id (the lowest free one)"),
                () -> assertEquals(1, h.team(ann), "Ann gets its team 1"));
        }

        @Test
        @DisplayName("with teamAssign, when only the reserved robot team has room and its robots are all there: REASON_OTHER")
        void assignRefused() {
            String[] rows = {
                "xxxxxxxxxxxxxx",
                "x            x",
                "x 0 0 1 1 2  x",
                "x            x",
                "xxxxxxxxxxxxxx",
            };
            Simulation s = sim(TEAM + "maxRobots: 1\nminRobots: 1\n", rows);
            SimGameHost h = new SimGameHost(s);
            addRobots(s, 1);
            enter(h, "Ann", 1);
            enter(h, "Bob", 1);
            enter(h, "Cid", 2);
            assertAll(
                () -> assertEquals(0, firstRobot(s.world).team, "the robot plays on the robot team"),
                () -> assertEquals(OTHER, h.join("Dee", Protocol.NO_TEAM, ""), "C E_TEAM_NOT_SET"),
                () -> assertEquals(1, s.robots.numRobots(), "no robot is kicked"));
        }
    }

    @Nested
    @DisplayName("leave")
    class Leave {
        @Test
        @DisplayName("a reserved player frees its slot silently; a spawned one leaves the game; other ids are ignored")
        void leaves() {
            Simulation s = sim("", THREE);
            World w = s.world;
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            int res = h.join("Res", 0, "");
            Player resP = w.byId(res);
            long from = w.events.seq();
            h.leave(res);
            List<String> reservedLeave = events(w, from);
            long from2 = w.events.seq();
            h.leave(ann);
            List<String> spawnedLeave = events(w, from2);
            long from3 = w.events.seq();
            h.leave(ann);
            h.leave(0);
            h.leave(-1);
            h.leave(World.MAX_IDS);
            h.leave(7);
            assertAll(
                () -> assertEquals(List.of(), reservedLeave, "a reserved player leaves without events"),
                () -> assertFalse(resP.inUse, "its slot is free"),
                () -> assertEquals(List.of("BASE_OWNER 2 -1 0", "LEAVE 1 0 0"), spawnedLeave,
                    "a spawned player releases its base and leaves (Ships.leave)"),
                () -> assertEquals(0, w.numPlayers, "nobody is in the game"),
                () -> assertEquals(List.of(), events(w, from3), "repeated, unknown and out-of-range ids are ignored"),
                () -> assertEquals(1, h.join("Ann", 0, ""), "the id can be reused"));
        }
    }

    @Nested
    @DisplayName("constructor")
    class Construct {
        @Test
        @DisplayName("applies maxRobots (minRobots lowered to it) and listens for humans entering")
        void construct() {
            Simulation s = sim("maxRobots: 1\nminRobots: 3\n", THREE);
            int before = s.world.params.minRobots;
            new SimGameHost(s);
            assertAll(
                () -> assertEquals(3, before, "minRobots 3 from the options"),
                () -> assertEquals(1, s.world.params.minRobots, "lowered to maxRobots 1 (C tuner)"),
                () -> assertTrue(s.ships.enterListener != null, "the welcome message listener is installed"));
        }
    }

    @Nested
    @DisplayName("wire mapping")
    class Mapping {
        @Test
        @DisplayName("NO_ID and negative ids become 0xFFFF, TEAM_NOT_SET 0xFF; others pass through")
        void ids() {
            assertAll(
                () -> assertEquals(Protocol.NO_ID, SimGameHost.wireId(-1), "Obj.NO_ID"),
                () -> assertEquals(Protocol.NO_ID, SimGameHost.wireId(-7), "any negative id"),
                () -> assertEquals(0, SimGameHost.wireId(0), "0 passes"),
                () -> assertEquals(255, SimGameHost.wireId(255), "the highest sim id passes"),
                () -> assertEquals(Protocol.NO_TEAM, SimGameHost.wireTeam(Const.TEAM_NOT_SET), "TEAM_NOT_SET"),
                () -> assertEquals(Protocol.NO_TEAM, SimGameHost.wireTeam(-2), "any negative team"),
                () -> assertEquals(0, SimGameHost.wireTeam(0), "team 0 passes"),
                () -> assertEquals(9, SimGameHost.wireTeam(9), "team 9 passes"));
        }
    }
}
