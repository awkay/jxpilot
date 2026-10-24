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

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** RobotTalk: C Robot_talks (robot.c). */
class RobotTalkTest {

    private static World world(boolean talk) {
        Simulation s = RobotFixture.sim("robotsTalk: " + (talk ? "yes" : "no") + "\n", RobotFixture.open(6, 4, "_"));
        return s.world;
    }

    @Nested
    @DisplayName("says")
    class Says {
        @Test
        @DisplayName("picks message next_msg % n, starting at (int)(rfrac*997) + 1 and counting up")
        void sequence() {
            World w = world(true);
            w.rng.seed(5);
            int first = (int) (RobotFixture.rfracs(5, 1)[0] * 997) + 1;
            RobotTalk t = new RobotTalk(w);
            long from = w.events.seq();
            t.says(RobotTalk.ENTER, "Robo", null);
            t.says(RobotTalk.LEAVE, "Robo", null);
            t.says(RobotTalk.KILL, "Robo", "Victim");
            t.says(RobotTalk.WAR, "Robo", "Killer");
            StringBuilder e = new StringBuilder();
            RobotTalk.format(e, RobotTalk.ENTER_MSGS[first % 13], "Robo", null);
            StringBuilder l = new StringBuilder();
            RobotTalk.format(l, RobotTalk.LEAVE_MSGS[(first + 1) % 13], "Robo", null);
            StringBuilder k = new StringBuilder();
            RobotTalk.format(k, RobotTalk.KILL_MSGS[(first + 2) % 10], "Victim", "Robo");
            StringBuilder wr = new StringBuilder();
            RobotTalk.format(wr, RobotTalk.WAR_MSGS[(first + 3) % 9], "Killer", "Robo");
            assertAll(
                () -> assertEquals(List.of(e.toString(), l.toString(), k.toString(), wr.toString()),
                    RobotFixture.messages(w, from), "one message per call, in order, other player first for KILL/WAR"),
                () -> assertEquals(first + 3, t.nextMsg, "the counter advanced once per message"),
                () -> assertEquals(Msg.Text.KIND_GAME + ":" + Obj.NO_ID + ":0",
                    RobotFixture.events(w, Events.MESSAGE, from).get(0), "a game message from the server"));
        }

        @Test
        @DisplayName("wraps the counter from 997 to 0")
        void wrap() {
            World w = world(true);
            RobotTalk t = new RobotTalk(w);
            t.nextMsg = 997;
            long from = w.events.seq();
            t.says(RobotTalk.ENTER, "R", null);
            assertAll(
                () -> assertEquals(0, t.nextMsg, "997 + 1 wraps to 0"),
                () -> assertEquals(List.of("R just can't stand you anymore."), RobotFixture.messages(w, from),
                    "message 0"));
            t.nextMsg = 996;
            t.says(RobotTalk.ENTER, "R", null);
            assertEquals(997, t.nextMsg, "996 + 1 = 997 is still used");
        }

        @Test
        @DisplayName("without robotsTalk only ENTER is said")
        void quiet() {
            World w = world(false);
            RobotTalk t = new RobotTalk(w);
            t.nextMsg = 0;
            long from = w.events.seq();
            t.says(RobotTalk.LEAVE, "R", null);
            t.says(RobotTalk.KILL, "R", "V");
            t.says(RobotTalk.WAR, "R", "V");
            t.says(RobotTalk.ENTER, "R", null);
            assertAll(
                () -> assertEquals(List.of("R has come to give you a hard time."), RobotFixture.messages(w, from),
                    "only the ENTER message (number 1)"),
                () -> assertEquals(1, t.nextMsg, "silenced messages do not count"));
        }
    }

    @Nested
    @DisplayName("format")
    class Format {
        @Test
        @DisplayName("replaces %s in order and keeps other % characters")
        void format() {
            StringBuilder b = new StringBuilder();
            RobotTalk.format(b, "$%#^@#$^#$%  That's the last time you do that %s! [%s]", "K", "R");
            StringBuilder c = new StringBuilder();
            RobotTalk.format(c, "100% %s%", "x", null);
            assertAll(
                () -> assertEquals("$%#^@#$^#$%  That's the last time you do that K! [R]", b.toString(),
                    "two arguments, literal percent signs"),
                () -> assertEquals("100% x%", c.toString(), "a trailing % stays"));
        }
    }
}
