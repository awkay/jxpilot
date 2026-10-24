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
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The robot reactions to kills by cause (collision.c:447-460, 827, 1359; walls.c:2195-2197; laser.c:307) and
 * the out-of-lives departure of player.c:1938-1951.
 */
class RobotKillsTest {

    private static Simulation sim(String extra, String bases) {
        Simulation s = RobotFixture.sim(extra, RobotFixture.open(20, 20, bases));
        s.robots.setEnabled(true);
        return s;
    }

    /** A race map (timing needs a checkpoint, else MapLoader turns race mode off). */
    private static Simulation race(String extra, String bases) {
        String[] rows = RobotFixture.open(20, 20, bases);
        rows[2] = "     A" + rows[2].substring(6);
        Simulation s = RobotFixture.sim("timing: yes\n" + extra, rows);
        s.robots.setEnabled(true);
        return s;
    }

    @Nested
    @DisplayName("Events.kill")
    class EventRecord {
        @Test
        @DisplayName("records a KILL GAME_EVENT with its cause; aux stays NO_ID; a reused slot is reset to KILL_WEAPON")
        void record() {
            Events e = new Events();
            int i = e.kill(3, 4, Msg.GameEvent.CRASH_WALL, Events.KILL_SHOVE);
            e.aux[i] = 7;
            for (int k = 1; k < Events.CAPACITY; k++) {
                e.add(Events.MESSAGE, 0, 0, 0, 0f);
            }
            int reused = e.add(Events.GAME_EVENT, Msg.GameEvent.KILL, 5, 6, 0f);
            assertAll(
                () -> assertEquals(i, reused, "the ring wrapped onto the same slot"),
                () -> assertEquals(Msg.GameEvent.KILL, e.a[reused], "a KILL"),
                () -> assertEquals(Events.KILL_WEAPON, e.cause[reused], "add resets the cause"),
                () -> assertEquals(Obj.NO_ID, e.aux[reused], "add resets aux"));
        }

        @Test
        @DisplayName("kill fills b, c, value and the cause")
        void fields() {
            Events e = new Events();
            int i = e.kill(3, 4, 2f, Events.KILL_RUNOVER);
            assertAll(
                () -> assertEquals(Events.GAME_EVENT, e.kind[i], "GAME_EVENT"),
                () -> assertEquals(Msg.GameEvent.KILL, e.a[i], "KILL"),
                () -> assertEquals(3, e.b[i], "killer"),
                () -> assertEquals(4, e.c[i], "victim"),
                () -> assertEquals(2f, e.value[i], "crash sub-type"),
                () -> assertEquals(Events.KILL_RUNOVER, e.cause[i], "cause"),
                () -> assertEquals(Obj.NO_ID, e.aux[i], "no war target"));
        }
    }

    @Nested
    @DisplayName("robot hooks by kill cause")
    class Causes {
        Simulation s;
        World w;
        Player robot;
        Player killer;
        Player other;

        void setup() {
            s = sim("robotsTalk: yes\n", "____");
            w = s.world;
            robot = s.robots.create();
            killer = TestWorlds.join(s, "K", 0);
            other = TestWorlds.join(s, "O", 0);
            killer.score = 1000;
            other.score = 1000;
        }

        @Test
        @DisplayName("a weapon kill: the robot victim declares war on its killer")
        void weapon() {
            setup();
            w.events.kill(killer.id, robot.id, 0f, Events.KILL_WEAPON);
            s.robots.handleEvents();
            assertEquals(killer.id, s.robots.warOnPlayer(robot), "war on the killer (rfrac*100 < 1000)");
        }

        @Test
        @DisplayName("a shove: war on the drawn pusher (aux), not on the killer; a departed pusher gives no war")
        void shove() {
            setup();
            int i = w.events.kill(killer.id, robot.id, Msg.GameEvent.CRASH_WALL, Events.KILL_SHOVE);
            w.events.aux[i] = other.id;
            s.robots.handleEvents();
            int war = s.robots.warOnPlayer(robot);
            DefaultRobot.setWar(s.robots.data(robot), Obj.NO_ID);
            int j = w.events.kill(killer.id, robot.id, Msg.GameEvent.CRASH_WALL, Events.KILL_SHOVE);
            w.events.aux[j] = 63;
            s.robots.handleEvents();
            assertAll(
                () -> assertEquals(other.id, war, "war on the drawn pusher"),
                () -> assertEquals(Obj.NO_ID, s.robots.warOnPlayer(robot), "unknown pusher: no war"));
        }

        @Test
        @DisplayName("a run-over: no new war and no taunt; a robot victim at war with its killer makes peace,"
            + " a war on someone else stays")
        void runOver() {
            setup();
            long from = w.events.seq();
            w.events.kill(killer.id, robot.id, 0f, Events.KILL_RUNOVER);
            s.robots.handleEvents();
            int noWar = s.robots.warOnPlayer(robot);
            List<String> talk = RobotFixture.messages(w, from);
            DefaultRobot.setWar(s.robots.data(robot), killer.id);
            w.events.kill(killer.id, robot.id, 0f, Events.KILL_RUNOVER);
            s.robots.handleEvents();
            int peace = s.robots.warOnPlayer(robot);
            DefaultRobot.setWar(s.robots.data(robot), other.id);
            w.events.kill(killer.id, robot.id, 0f, Events.KILL_RUNOVER);
            s.robots.handleEvents();
            assertAll(
                () -> assertEquals(Obj.NO_ID, noWar, "no Robot_war for a run-over"),
                () -> assertEquals(List.of(), talk, "nobody talks"),
                () -> assertEquals(Obj.NO_ID, peace, "the war on the killer ends"),
                () -> assertEquals(other.id, s.robots.warOnPlayer(robot), "a war on a third ship stays"));
        }

        @Test
        @DisplayName("a robot that runs over another ship neither gloats nor ends its war on the victim")
        void robotRunsOver() {
            setup();
            DefaultRobot.setWar(s.robots.data(robot), killer.id);
            long from = w.events.seq();
            w.events.kill(robot.id, killer.id, 0f, Events.KILL_RUNOVER);
            s.robots.handleEvents();
            assertAll(
                () -> assertEquals(killer.id, s.robots.warOnPlayer(robot), "the war goes on"),
                () -> assertEquals(List.of(), RobotFixture.messages(w, from), "no KILL talk"));
        }

        @Test
        @DisplayName("an explosion kill (or a run-over without a known killer) changes nothing")
        void explosion() {
            setup();
            DefaultRobot.setWar(s.robots.data(robot), killer.id);
            w.events.kill(killer.id, robot.id, 0f, Events.KILL_EXPLOSION);
            w.events.kill(Obj.NO_ID, robot.id, 0f, Events.KILL_RUNOVER);
            w.events.kill(Obj.NO_ID, robot.id, 0f, Events.KILL_WEAPON);
            s.robots.handleEvents();
            assertEquals(killer.id, s.robots.warOnPlayer(robot), "the war is untouched");
        }
    }

    @Nested
    @DisplayName("hook timing (C runs Robot_war inside the collision code)")
    class Timing {
        @Test
        @DisplayName("a robot killer's hook runs in the frame of the kill, before the kill loop deletes a robot victim"
            + " out of lives: it gloats about the victim and ends its war")
        void beforeKillLoop() {
            Simulation s = sim("robotsTalk: yes\nlimitedLives: yes\nworldLives: 3\nmaxRobots: 2\nminRobots: 2\n",
                "____");
            World w = s.world;
            Player victim = s.robots.create();
            Player killer = s.robots.create();
            Player human = TestWorlds.join(s, "H", 0);
            for (int i = 0; i < 400 && (victim.status & Obj.PLAYING) == 0; i++) {
                TestWorlds.run(s, 1, 0L);
            }
            victim.life = 0;
            victim.used &= ~com.fulcrologic.jxpilot.server.world.Has.SHIELD;
            victim.fuel = 0;
            DefaultRobot.setWar(s.robots.data(killer), human.id);
            int h = w.objects.alloc();
            w.objects.type[h] = Obj.SHOT;
            w.objects.owner[h] = killer.id;
            w.objects.life[h] = 20;
            w.objects.mass[h] = w.params.shotMass;
            w.objects.initPixels(h, victim.px, victim.py);
            long from = w.events.seq();
            s.tick(new long[w.players.length]);
            List<String> msgs = RobotFixture.messages(w, from);
            assertAll(
                () -> assertFalse(victim.inUse, "the victim lost its last life and left in the kill loop"),
                () -> assertEquals(Obj.NO_ID, s.robots.warOnPlayer(killer), "the killer's Robot_war ended its war"),
                () -> assertTrue(msgs.stream().anyMatch(m -> m.contains(victim.name)
                    && m.endsWith("[" + killer.name + "]")), "and it gloated about the victim: " + msgs));
        }

        @Test
        @DisplayName("robots do not play during the round delay (robot.c Robot_update)")
        void roundDelay() {
            Simulation s = sim("", "___");
            World w = s.world;
            Player r = s.robots.create();
            TestWorlds.join(s, "H", 0);
            for (int i = 0; i < 400 && (r.status & Obj.PLAYING) == 0; i++) {
                TestWorlds.run(s, 1, 0L);
            }
            w.roundDelay = 50;
            int before = s.robots.data(r).robotCount;
            s.robots.update();
            int during = s.robots.data(r).robotCount;
            w.roundDelay = 0;
            s.robots.update();
            assertAll(
                () -> assertTrue((r.status & Obj.PLAYING) != 0, "the robot is in play"),
                () -> assertEquals(before, during, "no Robot_play while round_delay > 0"),
                () -> assertTrue(s.robots.data(r).robotCount != during, "it plays again afterwards"));
        }
    }

    @Nested
    @DisplayName("out of lives (player.c:1938-1951)")
    class OutOfLives {
        private Player lastLife(Simulation s) {
            Player r = s.robots.create();
            r.life = 0;
            return r;
        }

        @Test
        @DisplayName("without team play or race a robot losing its last life leaves silently and is replaced later")
        void leaves() {
            Simulation s = sim("limitedLives: yes\nworldLives: 3\nmaxRobots: 1\nminRobots: 1\n", "___");
            World w = s.world;
            Player r = lastLife(s);
            int id = r.id;
            r.status |= Obj.KILLED;
            long from = w.events.seq();
            TestWorlds.run(s, 1, 0L);
            boolean gone = !r.inUse;
            int robotsAfter = s.robots.numRobots();
            List<String> leaves = RobotFixture.events(w, Events.LEAVE, from);
            List<String> msgs = RobotFixture.messages(w, from);
            TestWorlds.run(s, 2 * w.params.fps + 1, 0L);
            assertAll(
                () -> assertTrue(gone, "the robot left the game"),
                () -> assertEquals(0, robotsAfter, "NumRobots dropped"),
                () -> assertEquals(List.of(id + ":0:0"), leaves, "LEAVE"),
                () -> assertTrue(msgs.stream().noneMatch(m -> m.contains("upset the gods")), "not a kick"),
                () -> assertEquals(1, s.robots.numRobots(), "Robot_update brings a new robot"));
        }

        @Test
        @DisplayName("team play or race: the robot stays (GAME_OVER) unless robotsLeave and its score is below"
            + " robotLeaveScore")
        void staysInTeamsAndRaces() {
            String[] rows = RobotFixture.open(20, 20, "");
            char[] b = rows[19].toCharArray();
            b[1] = '1';
            b[3] = '1';
            rows[19] = new String(b);
            Simulation team = RobotFixture.sim("limitedLives: yes\nteamPlay: yes\nreserveRobotTeam: no\n", rows);
            Player t = team.ships.join("T", 1, true);
            t.score = -50;
            boolean teamLeaves = team.robots.leaveOutOfLives(t);
            t.score = -90;
            boolean atLimit = team.robots.leaveOutOfLives(t);
            t.score = -91;
            boolean lowLeaves = team.robots.leaveOutOfLives(t);

            Simulation race = race("limitedLives: yes\nrobotsLeave: no\n", "___");
            Player r = race.robots.create();
            r.score = -1000;
            boolean isRace = race.world.map.timing();
            boolean raceLeaves = race.robots.leaveOutOfLives(r);

            Simulation free = sim("limitedLives: yes\nrobotsLeave: no\n", "___");
            Player f = free.robots.create();
            boolean freeLeaves = free.robots.leaveOutOfLives(f);
            assertAll(
                () -> assertFalse(teamLeaves, "team play, score -50 >= robotLeaveScore -90: stays"),
                () -> assertFalse(atLimit, "score -90 = robotLeaveScore: stays"),
                () -> assertTrue(lowLeaves, "team play, score -91 < -90 with robotsLeave: leaves"),
                () -> assertTrue(isRace, "the race map is in race mode"),
                () -> assertFalse(raceLeaves, "race without robotsLeave: stays whatever the score"),
                () -> assertTrue(r.inUse, "and is still in the game"),
                () -> assertTrue(freeLeaves, "no teams, no race: leaves even without robotsLeave"));
        }

        @Test
        @DisplayName("a robot that stays ends its game: life 0, GAME_OVER, mychar 'D'")
        void gameOver() {
            Simulation s = race("limitedLives: yes\nrobotsLeave: no\n", "___");
            Player r = lastLife(s);
            s.ships.deathReset(r);
            assertAll(
                () -> assertTrue(r.inUse, "still playing"),
                () -> assertEquals(0, r.life, "no lives"),
                () -> assertTrue((r.status & Obj.GAME_OVER) != 0, "GAME_OVER"),
                () -> assertEquals('D', r.mychar, "'D'"));
        }

        @Test
        @DisplayName("humans with no lives left never leave this way")
        void humansStay() {
            Simulation s = sim("limitedLives: yes\n", "___");
            Player h = TestWorlds.join(s, "H", 0);
            h.life = 0;
            s.ships.deathReset(h);
            assertAll(
                () -> assertTrue(h.inUse, "the human stays"),
                () -> assertTrue((h.status & Obj.GAME_OVER) != 0, "game over"));
        }
    }
}
