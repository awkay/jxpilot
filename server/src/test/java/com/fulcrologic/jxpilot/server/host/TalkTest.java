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

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.host.HostFx.TEAMS;
import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.events;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Talk: C Handle_talk (netserver.c) as MESSAGE events "MESSAGE kind from to text" (to 0 = everyone). */
@DisplayName("Talk (C Handle_talk)")
class TalkTest {
    static final int CHAT = Msg.Text.KIND_CHAT;
    static final int TEAM = Msg.Text.KIND_TEAM_CHAT;
    static final int PRIVATE = Msg.Text.KIND_PRIVATE;
    static final int SERVER = Msg.Text.KIND_SERVER;

    /** Events of one line said by {@code from}. */
    static List<String> say(World w, Talk t, Player from, String line) {
        long seq = w.events.seq();
        t.say(from, line);
        return events(w, seq);
    }

    @Nested
    @DisplayName("say: to everyone")
    class Everyone {
        @Test
        @DisplayName("without an addressing colon the line goes to everyone as \"text [sender]\"")
        void everyone() {
            Simulation s = sim("", THREE);
            World w = s.world;
            Player ann = s.ships.join("Ann", 0);
            s.ships.join("Bob", 0);
            Talk t = new Talk(w);
            assertAll(
                () -> assertEquals(List.of("MESSAGE " + CHAT + " 1 0 hello [Ann]"), say(w, t, ann, "hello"), "no colon"),
                () -> assertEquals(List.of("MESSAGE " + CHAT + " 1 0 :bob hi [Ann]"), say(w, t, ann, ":bob hi"),
                    "a colon first addresses nobody"),
                () -> assertEquals(List.of("MESSAGE " + CHAT + " 1 0 bob:) [Ann]"), say(w, t, ann, "bob:)"), "a smiley ')'"),
                () -> assertEquals(List.of("MESSAGE " + CHAT + " 1 0 bob:-x [Ann]"), say(w, t, ann, "bob:-x"), "a smiley '-'"),
                () -> assertEquals(List.of("MESSAGE " + CHAT + " 1 0 bob:] [Ann]"), say(w, t, ann, "bob:]"), "the last smiley ']'"),
                () -> assertEquals(List.of("MESSAGE " + CHAT + " 1 0 bob: [Ann]"), say(w, t, ann, "bob:"),
                    "a colon at the end (C matches the NUL)"),
                () -> assertEquals(List.of("MESSAGE " + CHAT + " 1 0  [Ann]"), say(w, t, ann, ""), "an empty line"));
        }
    }

    @Nested
    @DisplayName("say: to a team")
    class Teams {
        @Test
        @DisplayName("each member gets KIND_TEAM_CHAT \"text [sender]:[team]\"; a sender off the team gets a copy")
        void members() {
            Simulation s = sim("teamPlay: yes\n", TEAMS);
            World w = s.world;
            Player ann = s.ships.join("Ann", 1);
            Player bob = s.ships.join("Bob", 1);
            Player cid = s.ships.join("Cid", 2);
            Talk t = new Talk(w);
            assertAll(
                () -> assertEquals(List.of(1, 1, 2), List.of(ann.team, bob.team, cid.team), "Ann and Bob on team 1, Cid on 2"),
                () -> assertEquals(List.of("MESSAGE " + TEAM + " 3 1 go [Cid]:[1]", "MESSAGE " + TEAM + " 3 2 go [Cid]:[1]",
                    "MESSAGE " + TEAM + " 3 3 go [Cid]:[1]"), say(w, t, cid, "1:go"), "Ann, Bob, then Cid's copy"),
                () -> assertEquals(List.of("MESSAGE " + TEAM + " 1 1 go [Ann]:[1]", "MESSAGE " + TEAM + " 1 2 go [Ann]:[1]"),
                    say(w, t, ann, "01:go"), "a member gets no extra copy; leading zeros are the same team"),
                () -> assertEquals(List.of("MESSAGE " + TEAM + " 1 3 hi:there [Ann]:[2]", "MESSAGE " + TEAM + " 1 1 hi:there [Ann]:[2]"),
                    say(w, t, ann, "2:hi:there"), "only the first colon addresses"));
        }

        @Test
        @DisplayName("a team nobody is on: \"Message not sent, nobody in team N!\" to the sender only")
        void nobody() {
            Simulation s = sim("teamPlay: yes\n", TEAMS);
            World w = s.world;
            Player ann = s.ships.join("Ann", 1);
            Talk t = new Talk(w);
            Simulation solo = sim("", THREE);
            Player zed = solo.ships.join("Zed", 0);
            Talk ts = new Talk(solo.world);
            assertAll(
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 1 Message not sent, nobody in team 2!"),
                    say(w, t, ann, "2:hi"), "team 2 is empty"),
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 1 Message not sent, nobody in team 4294967297!"),
                    say(w, t, ann, "4294967297:hi"), "a huge number does not wrap into team 1"),
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 1 Message not sent, nobody in team 0!"),
                    say(solo.world, ts, zed, "0:hi"), "outside team play nobody has a team"));
        }
    }

    @Nested
    @DisplayName("say: god and players")
    class Players {
        @Test
        @DisplayName("\"god\" (any case) is the admin log; without one GOD is not listening")
        void god() {
            Simulation s = sim("", THREE);
            Player ann = s.ships.join("Ann", 0);
            Talk t = new Talk(s.world);
            assertAll(
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 1  < GOD doesn't seem to be listening>"),
                    say(s.world, t, ann, "GoD:help"), "no admin log file"),
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 1 Message not sent, gods does not match any player!"),
                    say(s.world, t, ann, "gods:help"), "\"gods\" is a player name"));
        }

        @Test
        @DisplayName("a player by exact name, else unique prefix: KIND_PRIVATE to the receiver, then the sender")
        void privateLines() {
            Simulation s = sim("", HostFx.FOUR);
            World w = s.world;
            Player ann = s.ships.join("Ann", 0);
            s.ships.join("Bo", 0);
            s.ships.join("Bob", 0);
            s.ships.join("Cid", 0);
            Talk t = new Talk(w);
            assertAll(
                () -> assertEquals(List.of("MESSAGE " + PRIVATE + " 1 3 hi [Ann]:[Bob]", "MESSAGE " + PRIVATE + " 1 1 hi [Ann]:[Bob]"),
                    say(w, t, ann, "BOB:hi"), "exact name, any case"),
                () -> assertEquals(List.of("MESSAGE " + PRIVATE + " 1 2 hi [Ann]:[Bo]", "MESSAGE " + PRIVATE + " 1 1 hi [Ann]:[Bo]"),
                    say(w, t, ann, "bo:hi"), "an exact name wins over the prefix of Bob"),
                () -> assertEquals(List.of("MESSAGE " + PRIVATE + " 1 4 yo [Ann]:[Cid]", "MESSAGE " + PRIVATE + " 1 1 yo [Ann]:[Cid]"),
                    say(w, t, ann, "c:yo"), "a unique prefix"),
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 1 Message not sent, b matches more than one player!"),
                    say(w, t, ann, "b:yo"), "an ambiguous prefix"),
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 1 Message not sent, Bobby does not match any player!"),
                    say(w, t, ann, "Bobby:yo"), "a longer name matches nobody"),
                () -> assertEquals(List.of("MESSAGE " + SERVER + " -1 1 Message not sent, 1a does not match any player!"),
                    say(w, t, ann, "1a:yo"), "digits and letters are a name"),
                () -> assertEquals(List.of(), say(w, t, ann, "an:me"), "to oneself: nothing"));
        }
    }

    @Nested
    @DisplayName("helpers")
    class Helpers {
        @Test
        @DisplayName("equalsIgnoreCase, indexOf, allDigits, parseTeam, addressedTo")
        void helpers() {
            Events e = new Events();
            int all = e.add(Events.MESSAGE, 0, Obj.NO_ID, 0, 0f);
            int toTwo = e.add(Events.MESSAGE, 0, Obj.NO_ID, 2, 0f);
            assertAll(
                () -> assertTrue(Talk.equalsIgnoreCase("Bob", "xbOb", 1, 3), "a region, any case"),
                () -> assertTrue(Talk.equalsIgnoreCase("Bobby", "bob", 0, 3), "a prefix of a"),
                () -> assertFalse(Talk.equalsIgnoreCase("Bo", "bob", 0, 3), "a shorter than n"),
                () -> assertFalse(Talk.equalsIgnoreCase("Bob", "xbo", 1, 3), "s shorter than start + n"),
                () -> assertFalse(Talk.equalsIgnoreCase("Bob", "bot", 0, 3), "a differing char"),
                () -> assertTrue(Talk.equalsIgnoreCase("", "", 0, 0), "empty regions are equal"),
                () -> assertEquals(-1, Talk.indexOf("abc", ':'), "absent"),
                () -> assertEquals(1, Talk.indexOf("a::", ':'), "the first one"),
                () -> assertTrue(Talk.allDigits("09x", 0, 2), "0 and 9 are digits"),
                () -> assertFalse(Talk.allDigits("0/", 0, 2), "'/' (just below '0') is not"),
                () -> assertFalse(Talk.allDigits(":9", 0, 1), "':' (just above '9') is not"),
                () -> assertEquals(1234L, Talk.parseTeam("1234:", 4), "decimal digits"),
                () -> assertEquals(2147483648L, Talk.parseTeam("214748364800", 12), "stops once past INT_MAX"),
                () -> assertEquals(2147483647L, Talk.parseTeam("21474836470", 11), "and once at INT_MAX"),
                () -> assertTrue(Talk.addressedTo(e, all, 5), "c = 0 is for everyone"),
                () -> assertTrue(Talk.addressedTo(e, toTwo, 2), "c = 2 is for player 2"),
                () -> assertFalse(Talk.addressedTo(e, toTwo, 3), "and not for player 3"));
        }
    }
}
