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

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ItemParams;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.host.CommandsTest.reply;
import static com.fulcrologic.jxpilot.server.host.HostFx.TEAMS;
import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Edges of the commands and the tuning: result codes, character-range boundaries, latches. */
@DisplayName("Commands and Tuning: result codes and edges")
class CommandEdgesTest {
    @Nested
    @DisplayName("result codes (C CMD_RESULT_*)")
    class Codes {
        @Test
        @DisplayName("each command reports SUCCESS, ERROR, NOT_OPERATOR or NO_NAME as C's")
        void codes() {
            CommandsTest.Fx f = new CommandsTest.Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            int bob = enter(f.h, "Bob", 0);
            Player a = f.p(ann);
            f.w.options.force(Opt.PASSWORD, "pw");
            Commands c = new Commands(f.h, f.s, new Tuning(f.s, o -> { }));
            assertAll(
                () -> assertEquals(Commands.NOT_OPERATOR, c.advance("x", false), "advance: operator"),
                () -> assertEquals(Commands.NO_NAME, c.advance("", true), "advance: name"),
                () -> assertEquals(Commands.ERROR, c.advance("n".repeat(16), true), "advance: too long"),
                () -> assertEquals(Commands.SUCCESS, c.advance("n", true), "advance: not in queue"),
                () -> assertEquals(Commands.SUCCESS, c.lock("", a, false), "lock: status"),
                () -> assertEquals(Commands.ERROR, c.lock("2", a, true), "lock: bad argument"),
                () -> assertEquals(Commands.SUCCESS, c.lock("0", a, true), "lock: already"),
                () -> assertEquals(Commands.ERROR, c.kick("Zed", a, true), "kick: nobody"),
                () -> assertEquals(Commands.ERROR, c.pause("Zed", a, true), "pause: nobody"),
                () -> assertEquals(Commands.SUCCESS, c.set("shots 9", a, true), "set"),
                () -> assertEquals(Commands.ERROR, c.set("password x", a, true), "set password: C's ERROR path"),
                () -> assertEquals(Commands.ERROR, c.set("shots", a, true), "set: usage"),
                () -> assertEquals(Commands.ERROR, c.set("mapWidth 3", a, true), "set: not at run time"),
                () -> assertEquals(Commands.SUCCESS, c.get("shots"), "get"),
                () -> assertEquals(Commands.ERROR, c.get("password"), "get: refused"),
                () -> assertEquals(Commands.ERROR, c.get("zz"), "get: unknown"),
                () -> assertEquals(Commands.ERROR, c.get(""), "get: usage"),
                () -> assertEquals(Commands.ERROR, c.team("1", a), "team: no team play"),
                () -> assertEquals(Commands.ERROR, c.ally("", a), "ally: usage"),
                () -> assertEquals(Commands.ERROR, c.ally("l", a), "ally: ambiguous"),
                () -> assertEquals(Commands.ERROR, c.ally("invite Zed", a), "ally: nobody"),
                () -> assertEquals(Commands.ERROR, c.ally("leave Bob", a), "ally: no name for leave"),
                () -> assertEquals(Commands.ERROR, c.ally("invite", a), "ally: invite needs a name"),
                () -> assertEquals(Commands.SUCCESS, c.ally("list", a), "ally: list"),
                () -> assertEquals(Commands.SUCCESS, c.ally("invite Bob", a), "ally: invite"),
                () -> assertEquals(Commands.SUCCESS, c.kick("Bob", a, true), "kick"),
                () -> assertTrue(f.w.byId(bob) == null || !f.w.byId(bob).spawned, "Bob is gone"));
        }

        @Test
        @DisplayName("team codes; pause and kick succeed for a player")
        void teamCodes() {
            CommandsTest.Fx f = new CommandsTest.Fx("teamPlay: yes\nreserveRobotTeam: no\n", TEAMS);
            int ann = enter(f.h, "Ann", 1);
            int bob = enter(f.h, "Bob", 2);
            Player a = f.p(ann);
            Commands c = new Commands(f.h, f.s, new Tuning(f.s, o -> { }));
            assertAll(
                () -> assertEquals(Commands.ERROR, c.team("1", a), "already"),
                () -> assertEquals(Commands.ERROR, c.team("10", a), "not a team"),
                () -> assertEquals(Commands.SUCCESS, c.pause("Bob", a, true), "pause"),
                () -> assertEquals(Commands.SUCCESS, c.team("0x", a), "team 0 (the leading digits)"),
                () -> assertEquals(0, a.team, "on team 0"),
                () -> assertTrue((f.p(bob).status & Obj.PAUSE) != 0, "Bob is paused"));
        }
    }

    @Nested
    @DisplayName("character and length boundaries")
    class Boundaries {
        @Test
        @DisplayName("ids and teams start with a digit: '/' and ':' (around '0'-'9') do not; an id needs a player")
        void digits() {
            CommandsTest.Fx f = new CommandsTest.Fx("teamPlay: yes\nreserveRobotTeam: no\n", TEAMS);
            int ann = enter(f.h, "Ann", 1);
            f.op(ann);
            assertAll(
                () -> assertEquals(List.of(reply(ann, "Invalid team specification.")), f.say(ann, "/team :"), "':'"),
                () -> assertEquals(List.of(reply(ann, "Invalid team specification.")), f.say(ann, "/team /"), "'/'"),
                () -> assertEquals(List.of(reply(ann, "You already are on team 1.")), f.say(ann, "/team 1:"),
                    "digits end at ':'"),
                () -> assertEquals(List.of(reply(ann, "You already are on team 1.")), f.say(ann, "/team 1/"),
                    "digits end at '/'"),
                () -> assertEquals(List.of(reply(ann, "There are no bases for team 9 on this map.")), f.say(ann, "/team 9"),
                    "9 is a team"),
                () -> assertEquals(List.of(reply(ann, "Name does not match any player.")), f.say(ann, "/kick :nn"),
                    "':' starts a name"),
                () -> assertEquals(List.of(reply(ann, "Name does not match any player.")), f.say(ann, "/kick /nn"),
                    "'/' starts a name"),
                () -> assertEquals(List.of(reply(ann, "Name does not match any player.")), f.say(ann, "/kick 256"),
                    "ids stop at 255"),
                () -> assertEquals(List.of(reply(ann, "Name does not match any player.")), f.say(ann, "/kick 255"),
                    "no player 255"));
        }

        @Test
        @DisplayName("an id followed by other characters is that id; /ally list with one trailing space has no name")
        void idAndTrailing() {
            CommandsTest.Fx f = new CommandsTest.Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            int bob = enter(f.h, "Bob", 0);
            f.op(ann);
            List<String> paused = f.say(ann, "/pause " + bob + "x");
            assertAll(
                () -> assertEquals(List.of(CommandsTest.all("Bob was paused by Ann.")), HostFx.only(paused, "MESSAGE"),
                    "atoi: the leading digits"),
                () -> assertEquals(List.of("MESSAGE 3 -1 " + ann + "  < You are not a member of any alliance >"),
                    f.say(ann, "/ally list "), "nothing after the space: no name"));
        }

        @Test
        @DisplayName("/get values are cut at 79 characters, not before")
        void cut() {
            CommandsTest.Fx f = new CommandsTest.Fx("", THREE);
            int ann = enter(f.h, "Ann", 0);
            f.w.options.force(Opt.MAP_NAME, "m".repeat(79));
            List<String> exact = f.say(ann, "/get mapName");
            f.w.options.force(Opt.MAP_NAME, "m".repeat(80));
            List<String> over = f.say(ann, "/get mapName");
            assertAll(
                () -> assertEquals(List.of(reply(ann, "The value of mapName is " + "m".repeat(79) + ".")), exact, "79"),
                () -> assertEquals(exact, over, "80 is cut to 79"));
        }

        @Test
        @DisplayName("a swapping ship holding a ball drops it first (C Detach_ball(ind, -1))")
        void ball() {
            CommandsTest.Fx f = new CommandsTest.Fx("teamPlay: yes\nreserveRobotTeam: no\n", TEAMS);
            int ann = enter(f.h, "Ann", 1);
            Player a = f.p(ann);
            com.fulcrologic.jxpilot.server.world.Objects o = f.w.objects;
            int h = o.alloc();
            o.type[h] = Obj.BALL;
            o.owner[h] = ann;
            a.have |= Has.BALL;
            a.ball = h;
            a.used |= Has.CONNECTOR;
            f.say(ann, "/team 2");
            assertAll(
                () -> assertEquals(2, a.team, "swapped"),
                () -> assertEquals(Obj.NO_ID, o.owner[h], "the ball is loose"),
                () -> assertEquals(0, a.have & Has.BALL, "no ball any more"),
                () -> assertEquals(-1, a.ball, "not reaching for it"));
        }

        @Test
        @DisplayName("a swap in limited lives clears self-destruct; a paused opponent does not make the swapper wait")
        void swapEdges() {
            CommandsTest.Fx f = new CommandsTest.Fx("teamPlay: yes\nreserveRobotTeam: no\nlimitedLives: yes\n", TEAMS);
            int ann = enter(f.h, "Ann", 1);
            int bob = enter(f.h, "Bob", 0);
            f.p(bob).status |= Obj.PAUSE;
            f.say(ann, "/team 2");
            char alone = f.p(ann).mychar;
            f.p(bob).status &= ~Obj.PAUSE;
            f.p(ann).status |= Obj.SELF_DESTRUCT;
            f.say(ann, "/team 1");
            Player a = f.p(ann);
            assertAll(
                () -> assertEquals(' ', alone, "only a paused opponent: no waiting"),
                () -> assertEquals('W', a.mychar, "an active opponent: waiting"),
                () -> assertEquals(0, a.status & Obj.SELF_DESTRUCT, "self-destruct cleared"),
                () -> assertTrue((a.status & Obj.PLAYING) != 0, "PLAYING stays set (C sets GAME_OVER|PLAYING)"));
        }
    }

    @Nested
    @DisplayName("tuning edges")
    class TuningEdges {
        @Test
        @DisplayName("ItemParams follow /set too (playersOnRadar); names at their limits; zero is not below zero")
        void items() {
            TuningTest.Fx f = new TuningTest.Fx("maxRobots: 2\nminRobots: 1\n");
            f.t.tune("playersOnRadar", "no");
            f.t.tune("maxRobots", "0");
            f.t.tune("minRobots", "0");
            assertAll(
                () -> assertFalse(f.w.items.playersOnRadar, "the item parameters"),
                () -> assertEquals("0", f.get("maxRobots"), "0 is not 'below 0' (it stays, not one per base)"),
                () -> assertEquals("0", f.get("minRobots"), "0 stays"),
                () -> assertEquals("abcdefghijklmno", Tuning.fixRealName("abcdefghijklmno"), "15 characters stay"),
                () -> assertEquals("axb", Tuning.fixRealName("a\u007fb"), "DEL is not printable"),
                () -> assertEquals("ax.b", Tuning.fixHostName("a-.b"), "the character after a separator decides"),
                () -> assertEquals("xxx.xxx", Tuning.fixHostName("éa"), "a non-ASCII letter is not alphanumeric"));
        }

        @Test
        @DisplayName("worldLives and allowShields update the rules (alliances latch); allowShields gives back HAS_SHIELD")
        void rules() {
            TuningTest.Fx f = new TuningTest.Fx("");
            Player p = f.s.ships.join("Ann", 0);
            f.t.tune("allowAlliances", "no");
            f.t.tune("worldLives", "3");
            boolean afterLives = f.s.alliances.enabled;
            p.have &= ~Has.SHIELD;
            f.t.tune("allowShields", "yes");
            assertAll(
                () -> assertFalse(afterLives, "the worldLives tuner computes the rules"),
                () -> assertTrue((p.have & Has.SHIELD) != 0, "the player has shields again"));
        }

        @Test
        @DisplayName("copyChanges copies changed float array elements")
        void floats() {
            ItemParams before = ItemParams.from(new com.fulcrologic.jxpilot.common.config.Options(), 10, 10, 14);
            ItemParams after = ItemParams.from(new com.fulcrologic.jxpilot.common.config.Options(), 10, 10, 14);
            ItemParams live = ItemParams.from(new com.fulcrologic.jxpilot.common.config.Options(), 10, 10, 14);
            after.prob[Item.MINE.ordinal()] = 0.5f;
            live.prob[Item.TANK.ordinal()] = 0.25f;
            Tuning.copyChanges(before, after, live);
            assertAll(
                () -> assertEquals(0.5f, live.prob[Item.MINE.ordinal()], "changed"),
                () -> assertEquals(0.25f, live.prob[Item.TANK.ordinal()], "unchanged keeps the live value"));
        }
    }
}
