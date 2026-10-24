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
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.host.CommandsTest.reply;
import static com.fulcrologic.jxpilot.server.host.HostFx.TEAMS;
import static com.fulcrologic.jxpilot.server.host.HostFx.TWO;
import static com.fulcrologic.jxpilot.server.host.HostFx.empty;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The login queue (C contact.c Queue_player, Queue_loop, Queue_show_list, Queue_advance_player). */
@DisplayName("Login queue (C contact.c)")
class LoginQueueTest {
    static final int QUEUED = -Msg.Reject.REASON_QUEUED;

    /** A full two-base game (Ann and Bob playing) at 60 fps: timeout 1800 ticks, admissions 17 ticks apart. */
    static final class Fx {
        final Simulation s;
        final World w;
        final SimGameHost h;
        final HostFx.Kicks kicks;
        final int ann;
        final int bob;
        int tick;

        Fx(String extra, String... rows) {
            s = HostFx.sim(extra, rows.length == 0 ? TWO : rows);
            w = s.world;
            h = new SimGameHost(s);
            kicks = new HostFx.Kicks(h);
            ann = enter(h, "Ann", 1);
            bob = enter(h, "Bob", 2);
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                h.tick(++tick);
            }
        }

        int join(String name, long source) {
            return h.join(name, 255, "", source);
        }

        List<String> names() {
            return h.loginQueue.entries.stream().map(e -> e.name + (e.admitted != null ? "*" : "")).toList();
        }
    }

    @Nested
    @DisplayName("Queue_player")
    class Enqueue {
        @Test
        @DisplayName("a full game queues newcomers with their position; asking again keeps the place")
        void positions() {
            Fx f = new Fx("");
            int cid = f.join("Cid", 7);
            int cidPos = f.h.queuePosition();
            int dan = f.join("Dan", 8);
            int danPos = f.h.queuePosition();
            int again = f.join("Cid", 7);
            int againPos = f.h.queuePosition();
            int stolen = f.join("Cid", 9);
            assertAll(
                () -> assertEquals(QUEUED, cid, "Cid waits"),
                () -> assertEquals(1, cidPos, "first"),
                () -> assertEquals(QUEUED, dan, "Dan waits"),
                () -> assertEquals(2, danPos, "second"),
                () -> assertEquals(QUEUED, again, "Cid asking again"),
                () -> assertEquals(1, againPos, "still first"),
                () -> assertEquals(-Msg.Reject.REASON_BAD_NAME, stolen, "Cid's name from another address (E_IN_USE)"),
                () -> assertEquals(List.of("Cid", "Dan"), f.names(), "two entries"),
                () -> assertEquals(2, f.w.numPlayers, "nobody joined the game"));
        }

        @Test
        @DisplayName("limits: 20 entries (REASON_FULL); two per address (a third is E_IN_USE); names taken in game")
        void limits() {
            Fx f = new Fx("");
            f.join("A1", 1);
            f.join("A2", 1);
            int third = f.join("A3", 1);
            int taken = f.join("ann", 2);
            for (int i = 0; i < 18; i++) {
                f.join("P" + i, 100 + i);
            }
            int full = f.join("Late", 999);
            assertAll(
                () -> assertEquals(-Msg.Reject.REASON_BAD_NAME, third, "a third from one address"),
                () -> assertEquals(-Msg.Reject.REASON_BAD_NAME, taken, "a name in the game (any case)"),
                () -> assertEquals(20, f.h.loginQueue.size(), "20 queued"),
                () -> assertEquals(-Msg.Reject.REASON_FULL, full, "the 21st is refused"));
        }

        @Test
        @DisplayName("a newcomer waits behind the queue even when a base is free; a locked game still keeps places")
        void behindAndLocked() {
            Fx f = new Fx("");
            f.join("Cid", 7);
            f.h.leave(f.bob);
            int dan = f.join("Dan", 8);
            f.h.gameLock = true;
            int cidAgain = f.join("Cid", 7);
            int eve = f.join("Eve", 9);
            assertAll(
                () -> assertEquals(QUEUED, dan, "behind Cid though a base is free"),
                () -> assertEquals(QUEUED, cidAgain, "a waiting player keeps its place in a locked game (C)"),
                () -> assertEquals(-Msg.Reject.REASON_LOCKED, eve, "a newcomer is refused"));
        }
    }

    @Nested
    @DisplayName("Queue_loop")
    class Loop {
        @Test
        @DisplayName("a free base admits the first waiting player (a reserved slot); its next request joins")
        void admit() {
            Fx f = new Fx("");
            f.join("Cid", 7);
            f.join("Dan", 8);
            f.h.leave(f.bob);
            f.ticks(1);
            List<String> afterTick = f.names();
            int danPos;
            f.join("Dan", 8);
            danPos = f.h.queuePosition();
            int cid = f.join("Cid", 7);
            f.h.clientMessage(cid, ReliableType.READY, empty());
            Player c = f.w.byId(cid);
            assertAll(
                () -> assertEquals(List.of("Cid*", "Dan"), afterTick, "Cid admitted"),
                () -> assertEquals(1, danPos, "Dan is next"),
                () -> assertTrue(cid > 0, "Cid gets in"),
                () -> assertTrue(c.spawned, "and plays"),
                () -> assertEquals("Cid", c.name.toString(), "as Cid"),
                () -> assertEquals(List.of("Dan"), f.names(), "Cid left the queue"));
        }

        @Test
        @DisplayName("admissions are 2 + FPS/4 ticks apart")
        void pacingAndRobots() {
            Fx f = new Fx("", "xxxxxxxxxx", "x _  _  _x", "xxxxxxxxxx");
            enter(f.h, "Eve", 0);
            f.join("Cid", 7);
            f.join("Dan", 8);
            f.h.leave(f.ann);
            f.h.leave(f.bob);
            f.ticks(1);
            int one = (int) f.h.loginQueue.entries.stream().filter(e -> e.admitted != null).count();
            f.ticks(17);
            int still = (int) f.h.loginQueue.entries.stream().filter(e -> e.admitted != null).count();
            f.ticks(1);
            int two = (int) f.h.loginQueue.entries.stream().filter(e -> e.admitted != null).count();
            assertAll(
                () -> assertEquals(1, one, "one admission"),
                () -> assertEquals(1, still, "not before 2 + 60/4 + 1 ticks"),
                () -> assertEquals(2, two, "then the next"));
        }

        @Test
        @DisplayName("a robot holding the free base is kicked for the first waiting player")
        void robot() {
            Fx f = new Fx("maxRobots: 1\nminRobots: 1\n", "xxxxxxxxxx", "x _  _  _x", "xxxxxxxxxx");
            int eve = enter(f.h, "Eve", 0);
            f.join("Cid", 7);
            f.h.leave(eve);
            HostFx.addRobots(f.s, 1);
            f.ticks(1);
            assertAll(
                () -> assertEquals(0, f.s.robots.numRobots(), "the robot was kicked"),
                () -> assertEquals(List.of("Cid*"), f.names(), "Cid admitted in its place"));
        }

        @Test
        @DisplayName("entries nobody asks for in 30*FPS ticks go; an unclaimed admission frees its slot")
        void timeouts() {
            Fx f = new Fx("");
            f.join("Cid", 7);
            f.join("Dan", 8);
            f.h.leave(f.bob);
            f.ticks(1);
            int inUse = (int) java.util.Arrays.stream(f.w.players).filter(p -> p.inUse).count();
            f.ticks(1798);
            List<String> before = f.names();
            f.ticks(3);
            List<String> after = f.names();
            int inUseAfter = (int) java.util.Arrays.stream(f.w.players).filter(p -> p.inUse).count();
            assertAll(
                () -> assertEquals(2, inUse, "Ann and Cid's reserved slot"),
                () -> assertEquals(List.of("Cid*", "Dan"), before, "within 30*FPS both stay"),
                () -> assertFalse(after.contains("Cid*"), "the unclaimed admission went: " + after),
                () -> assertTrue(inUseAfter <= 2, "its slot was released"));
        }

        @Test
        @DisplayName("team play: an admitted player gets its team if it has room, else C Pick_team's")
        void team() {
            Fx f = new Fx("teamPlay: yes\nreserveRobotTeam: no\n", "xxxxxxxx", "x 1  2 x", "xxxxxxxx");
            f.h.join("Cid", 1, "", 7);
            f.h.leave(f.bob);
            f.ticks(1);
            int cid = f.h.join("Cid", 1, "", 7);
            f.h.clientMessage(cid, ReliableType.READY, empty());
            assertEquals(2, f.w.byId(cid).team, "team 1 is full: Cid gets team 2's free base");
        }
    }

    @Nested
    @DisplayName("/queue and /advance")
    class QueueCommands {
        @Test
        @DisplayName("the list in order (admitted entries too); advance with C's answers")
        void listAndAdvance() {
            Fx f = new Fx("");
            f.w.options.force(com.fulcrologic.jxpilot.common.config.Opt.PASSWORD, "pw");
            f.join("Cid", 7);
            f.join("Dan", 8);
            f.join("Eve", 9);
            List<String> list = say(f, f.ann, "/queue");
            say(f, f.ann, "/password pw");
            List<String> advanced = say(f, f.ann, "/advance eve");
            List<String> order = f.names();
            List<String> first = say(f, f.ann, "/advance Eve");
            f.h.leave(f.bob);
            f.ticks(1);
            List<String> admittedFirst = f.names();
            List<String> entering = say(f, f.ann, "/advance Eve");
            List<String> behind = say(f, f.ann, "/advance Dan");
            List<String> order2 = f.names();
            assertAll(
                () -> assertEquals(List.of(reply(f.ann, "Queue: 1. Cid  2. Dan  3. Eve")), list, "the list"),
                () -> assertEquals(List.of(reply(f.ann, "Done.")), advanced, "advanced (any case)"),
                () -> assertEquals(List.of("Eve", "Cid", "Dan"), order, "Eve first"),
                () -> assertEquals(List.of(reply(f.ann, "Already first.")), first, "already first"),
                () -> assertEquals(List.of("Eve*", "Cid", "Dan"), admittedFirst, "Eve admitted"),
                () -> assertEquals(List.of(reply(f.ann, "Already first.")), entering, "the first entry"),
                () -> assertEquals(List.of(reply(f.ann, "Done.")), behind, "Dan moves"),
                () -> assertEquals(List.of("Eve*", "Dan", "Cid"), order2, "behind the admitted Eve"),
                () -> assertEquals(List.of(reply(f.ann, "Already entering game.")), sayAfterMove(f), "admitted not first"));
        }

        private List<String> sayAfterMove(Fx f) {
            f.h.loginQueue.entries.add(0, f.h.loginQueue.entries.remove(1));
            return say(f, f.ann, "/advance Eve");
        }

        private List<String> say(Fx f, int from, String line) {
            long seq = f.w.events.seq();
            f.h.clientMessage(from, ReliableType.CHAT, HostFx.chat(line));
            return HostFx.events(f.w, seq);
        }

        @Test
        @DisplayName("advance reports success (C 0) for every answer but the long name")
        void advanceResult() {
            LoginQueue q = new LoginQueue();
            q.entries.add(new LoginQueue.Entry("Cid", 1, 255, "", 0));
            q.entries.add(new LoginQueue.Entry("Dan", 2, 255, "", 0));
            StringBuilder sb = new StringBuilder();
            assertAll(
                () -> assertTrue(q.advance("Dan", sb), "done"),
                () -> assertTrue(q.advance("Dan", sb), "already first"),
                () -> assertTrue(q.advance("Zed", sb), "not in queue"),
                () -> assertFalse(q.advance("x".repeat(16), sb), "too long"));
        }

        @Test
        @DisplayName("an empty queue; a long list stops before MSG_LEN - 32")
        void emptyAndLong() {
            Fx f = new Fx("");
            List<String> empty;
            long seq = f.w.events.seq();
            f.h.clientMessage(f.ann, ReliableType.CHAT, HostFx.chat("/queue"));
            empty = HostFx.events(f.w, seq);
            for (int i = 0; i < 20; i++) {
                f.join("Player" + (char) ('a' + i) + "xxxxxxxx", 100 + i);
            }
            StringBuilder sb = new StringBuilder();
            f.h.loginQueue.showList(sb);
            final List<String> e = empty;
            assertAll(
                () -> assertEquals(List.of(reply(f.ann, "The queue is empty.")), e, "empty"),
                () -> assertTrue(sb.length() + 2 + 32 >= 256 && sb.length() + 2 - 23 + 32 < 256,
                    "the entry that reached MSG_LEN - 32 is the last: " + sb.length()),
                () -> assertFalse(sb.toString().endsWith(" "), "no trailing spaces"));
        }
    }
}
