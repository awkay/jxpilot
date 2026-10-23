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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.ShipGeom;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RobotManager: robot.c (Robot_update, Robot_create, Robot_delete, Robot_check_leave, Robot_war, kicking). */
class RobotManagerTest {

    /** An open 20x20 world with {@code bases} bases; robots enabled. */
    private static Simulation sim(String extra, String bases) {
        Simulation s = RobotFixture.sim(extra, RobotFixture.open(20, 20, bases));
        s.robots.setEnabled(true);
        return s;
    }

    private static List<String> robots(World w) {
        List<String> l = new ArrayList<>();
        for (int k = 0; k < w.numPlayers; k++) {
            if (w.playerAt(k).robot) {
                l.add(w.playerAt(k).name.toString());
            }
        }
        return l;
    }

    private static void ticks(Simulation s, int n) {
        TestWorlds.run(s, n, 0L);
    }

    @Nested
    @DisplayName("update: joining and leaving by count (Robot_update)")
    class Counts {
        @Test
        @DisplayName("disabled: no robots ever")
        void disabled() {
            Simulation s = RobotFixture.sim("", RobotFixture.open(20, 20, "____"));
            ticks(s, 500);
            assertAll(
                () -> assertFalse(s.robots.enabled(), "off by default"),
                () -> assertEquals(0, s.world.numPlayers, "nobody joined"));
        }

        @Test
        @DisplayName("a robot joins every 2*FPS frames up to maxRobots")
        void joinDelay() {
            Simulation s = sim("maxRobots: 2\n", "____");
            for (int i = 0; i < 119; i++) {
                s.robots.update();
            }
            int before = s.robots.numRobots();
            s.robots.update();
            int at120 = s.robots.numRobots();
            for (int i = 0; i < 400; i++) {
                s.robots.update();
            }
            assertAll(
                () -> assertEquals(0, before, "119 frames: none yet"),
                () -> assertEquals(1, at120, "frame 120 (2 * 60 FPS): the first robot"),
                () -> assertEquals(2, s.robots.numRobots(), "never more than maxRobots"),
                () -> assertEquals(2, robots(s.world).size(), "two robot players"));
        }

        @Test
        @DisplayName("humans count toward maxRobots; minRobots robots stay anyway")
        void humans() {
            Simulation s = sim("maxRobots: 2\nminRobots: 0\n", "____");
            TestWorlds.join(s, "H", 0);
            for (int i = 0; i < 600; i++) {
                s.robots.update();
            }
            Simulation m = sim("maxRobots: 1\nminRobots: 2\n", "____");
            TestWorlds.join(m, "H", 0);
            for (int i = 0; i < 600; i++) {
                m.robots.update();
            }
            assertAll(
                () -> assertEquals(1, s.robots.numRobots(), "1 human + 1 robot = maxRobots"),
                () -> assertEquals(2, m.robots.numRobots(), "minRobots 2 even past maxRobots"),
                () -> assertEquals(3, m.world.numPlayers, "3 players"));
        }

        @Test
        @DisplayName("no free base, no robot; more players than maxRobots removes the lowest scoring robot")
        void bases() {
            Simulation s = sim("maxRobots: 5\nminRobots: 0\n", "__");
            for (int i = 0; i < 600; i++) {
                s.robots.update();
            }
            int full = s.robots.numRobots();
            Player low = s.world.playerAt(0);
            low.score = -3;
            s.world.params.maxRobots = 1;
            long from = s.world.events.seq();
            s.robots.update();
            assertAll(
                () -> assertEquals(2, full, "2 bases: 2 robots"),
                () -> assertEquals(1, s.robots.numRobots(), "one left"),
                () -> assertFalse(low.inUse, "the lowest scorer"),
                () -> assertEquals(1, RobotFixture.events(s.world, Events.LEAVE, from).size(), "a LEAVE event"));
        }

        @Test
        @DisplayName("the join delay restarts while the server is full")
        void delayRestarts() {
            Simulation s = sim("maxRobots: 1\nminRobots: 0\n", "___");
            for (int i = 0; i < 120; i++) {
                s.robots.update();
            }
            s.robots.newRobotDelay = 50;
            s.robots.update();
            assertEquals(0, s.robots.newRobotDelay, "full: delay reset");
        }

        @Test
        @DisplayName("team play with restrictRobots: robots join the robot team only while it has room")
        void restrict() {
            String[] rows = RobotFixture.open(20, 20, "");
            char[] b = rows[19].toCharArray();
            b[1] = '0';
            b[3] = '1';
            b[5] = '1';
            rows[19] = new String(b);
            Simulation s = RobotFixture.sim("teamPlay: yes\nmaxRobots: 3\nminRobots: 3\n", rows);
            s.robots.setEnabled(true);
            for (int i = 0; i < 600; i++) {
                s.robots.update();
            }
            assertAll(
                () -> assertEquals(1, s.robots.numRobots(), "the robot team has one base"),
                () -> assertEquals(0, s.world.playerAt(0).team, "on team 0"));
        }
    }

    @Nested
    @DisplayName("create (Robot_create)")
    class Create {
        @Test
        @DisplayName("joins a robot with full turnspeed and power, its ship shape, fuel levels, and says hello")
        void settings() {
            Simulation s = sim("", "__");
            World w = s.world;
            w.rng.seed(4);
            int num = (int) (RobotFixture.rfracs(4, 1)[0] * 38);
            long from = w.events.seq();
            Player p = s.robots.create();
            RobotData d = s.robots.data(p);
            RobotDef def = s.robots.roster().def(num);
            assertAll(
                () -> assertEquals(def.name(), p.name.toString(), "the picked robot's name"),
                () -> assertTrue(p.robot, "a robot"),
                () -> assertSame(s.robots.roster().ship(num), p.ship, "its ship"),
                () -> assertEquals(64.0, p.turnSpeed, "turnspeed 64"),
                () -> assertEquals(64.0, p.turnSpeedS, "alternate turnspeed 64"),
                () -> assertEquals(0.12f, p.turnResistance, "turnresistance (C assigns 0.12 to a float)"),
                () -> assertEquals(0.12f, p.turnResistanceS, "alternate turnresistance"),
                () -> assertEquals(55.0, p.power, "power 55"),
                () -> assertEquals(55.0, p.powerS, "alternate power 55"),
                () -> assertTrue(d.active, "robot data in use"),
                () -> assertEquals(num, d.def, "remembers its roster entry"),
                () -> assertEquals(1, s.robots.roster().used(num), "used once"),
                () -> assertEquals(DefaultRobot.RM_TAKE_OFF, d.robotMode, "taking off"),
                () -> assertTrue(Math.abs(d.fuelL3 - 500 * 256) < 200, "level 3 near 500 units"),
                () -> assertEquals(55.0, d.reportedPower, "power reported"),
                () -> assertEquals(1, s.robots.numRobots(), "counted"),
                () -> assertEquals(List.of(p.id + ":" + Msg.PlayerJoin.FLAG_ROBOT + ":0"),
                    RobotFixture.events(w, Events.JOIN, from), "JOIN carries FLAG_ROBOT"),
                () -> assertEquals(List.of(p.id + ":0:0"), RobotFixture.events(w, Events.THRUST, from), "THRUST with power"),
                () -> assertEquals(2, RobotFixture.messages(w, from).size(), "enter message and round delay message"),
                () -> assertEquals("Player entered. Delaying 0 seconds until next round.",
                    RobotFixture.messages(w, from).get(1), "first player: round delay"));
        }

        @Test
        @DisplayName("without allowShipShapes robots fly the default ship; later robots skip the round message unless"
            + " a round delay runs, which they restart (one second longer to send)")
        void shapes() {
            Simulation s = sim("allowShipShapes: no\nroundDelay: 3\n", "___");
            TestWorlds.join(s, "H", 0);
            s.world.roundDelay = 0;
            long from = s.world.events.seq();
            Player p = s.robots.create();
            List<String> after = RobotFixture.messages(s.world, from);
            int delayAfter = s.world.roundDelay;
            s.world.roundDelay = 5;
            long f1 = s.world.events.seq();
            s.robots.create();
            List<String> during = RobotFixture.messages(s.world, f1);
            assertAll(
                () -> assertSame(ShipGeom.defaultShip(), p.ship, "default ship"),
                () -> assertEquals(1, after.size(), "after the round delay: only the enter message"),
                () -> assertEquals(0, delayAfter, "after the round delay: no new delay"),
                () -> assertEquals(2, during.size(), "during a round delay: the enter message and the delay message"),
                () -> assertEquals("Player entered. Delaying 3 seconds until next round.", during.get(1),
                    "the delay restarts at roundDelay"),
                () -> assertEquals(3 * s.world.params.fps, s.world.roundDelay, "roundDelay seconds in frames"),
                () -> assertEquals(4 * s.world.params.fps, s.world.roundDelaySend, "sent one second longer"));
            String[] rows = RobotFixture.open(20, 20, "__");
            char[] c = rows[3].toCharArray();
            c[5] = 'A';
            rows[3] = new String(c);
            Simulation race = RobotFixture.sim("roundDelay: 3\ntiming: yes\n", rows);
            long f2 = race.world.events.seq();
            race.robots.create();
            assertEquals("Player entered. Delaying 3 seconds until next race.",
                RobotFixture.messages(race.world, f2).get(1), "race wording and the delay");
        }

        @Test
        @DisplayName("a failed join (no base) changes nothing")
        void full() {
            Simulation s = sim("", "_");
            TestWorlds.join(s, "H", 0);
            Player p = s.robots.create();
            assertAll(
                () -> assertNull(p, "no robot"),
                () -> assertEquals(0, s.robots.numRobots(), "none counted"));
        }

        @Test
        @DisplayName("pick: the least used robot from a random start; robots in play count as most used")
        void pick() {
            Simulation s = sim("", "____");
            RobotRoster r = s.robots.roster();
            for (int i = 0; i < r.size(); i++) {
                r.used[i] = 1;
            }
            r.used[10] = 0;
            r.used[20] = 0;
            int seedPast10 = 0;
            for (int seed = 1; seed < 500; seed++) {
                int start = (int) (RobotFixture.rfracs(seed, 1)[0] * 38);
                if (start > 10 && start <= 20) {
                    seedPast10 = seed;
                    break;
                }
            }
            s.world.rng.seed(seedPast10);
            int got = s.robots.pick();
            s.world.rng.seed(seedPast10);
            Player p = s.robots.create();
            r.used[20] = 0;
            r.used[p == null ? 0 : s.robots.data(p).def] = 0;
            s.world.rng.seed(seedPast10);
            int bumped = s.robots.pick();
            assertAll(
                () -> assertEquals(20, got, "from a start in 11..20 the next least used is 20"),
                () -> assertEquals(20, s.robots.data(p).def, "create uses the pick"),
                () -> assertEquals(1, r.used(20), "a robot in play is counted as most used again"),
                () -> assertEquals(10, bumped, "so the pick wraps around to 10"));
        }

        @Test
        @DisplayName("pickTeam: restrictRobots gives the robot team while it has room")
        void pickRestrict() {
            String[] rows = RobotFixture.open(20, 20, "");
            char[] b = rows[19].toCharArray();
            b[1] = '0';
            b[3] = '1';
            rows[19] = new String(b);
            Simulation s = RobotFixture.sim("teamPlay: yes\n", rows);
            int first = s.robots.pickTeam();
            s.ships.join("R", 0, true);
            assertAll(
                () -> assertEquals(0, first, "robot team 0"),
                () -> assertEquals(Const.TEAM_NOT_SET, s.robots.pickTeam(), "full"));
        }

        @Test
        @DisplayName("pickTeam without restrictRobots: like humans, but the robot team is allowed")
        void pickOpen() {
            String[] rows = RobotFixture.open(20, 20, "");
            char[] b = rows[19].toCharArray();
            b[1] = '0';
            b[3] = '1';
            b[5] = '1';
            b[7] = '2';
            b[9] = '2';
            rows[19] = new String(b);
            Simulation s = RobotFixture.sim("teamPlay: yes\nrestrictRobots: no\n", rows);
            int empty = s.robots.pickTeam();
            Player a = s.ships.join("A", 1);
            int oneTeam = s.robots.pickTeam();
            Player c = s.ships.join("C", 2);
            c.score = -5;
            int two = s.robots.pickTeam();
            s.ships.join("D", 0, true);
            a.status |= Obj.PAUSE;
            int paused = s.robots.pickTeam();
            assertAll(
                () -> assertEquals(1, empty, "nobody plays: among free teams the robot team 0 is skipped on a tie"),
                () -> assertEquals(2, oneTeam, "one team playing: an empty team; team 0 is skipped on ties"),
                () -> assertEquals(2, two, "teams 1 and 2 have one player each: the lower score (team 2)"),
                () -> assertEquals(2, paused, "team 1's player paused: team 2 is the only playing team with room"));
        }

        @Test
        @DisplayName("an out-of-range robotTeam becomes 0 (C Robot_init)")
        void robotTeam() {
            Simulation s = RobotFixture.sim("robotTeam: 12\n", RobotFixture.open(10, 10, "_"));
            Simulation n = RobotFixture.sim("robotTeam: -1\n", RobotFixture.open(10, 10, "_"));
            Simulation ok = RobotFixture.sim("robotTeam: 9\n", RobotFixture.open(10, 10, "_"));
            assertAll(
                () -> assertEquals(0, s.world.params.robotTeam, "12"),
                () -> assertEquals(0, n.world.params.robotTeam, "-1"),
                () -> assertEquals(9, ok.world.params.robotTeam, "9 is kept"));
        }
    }

    @Nested
    @DisplayName("leaving (Robot_check_leave, Robot_delete)")
    class Leave {
        @Test
        @DisplayName("retired at robotLeaveLife lives, disappointed below robotLeaveScore, bad below robotLeaveRatio")
        void reasons() {
            Simulation s = sim("robotsTalk: yes\n", "____");
            Player a = s.robots.create();
            a.life = 50;
            String aName = a.name.toString();
            long f1 = s.world.events.seq();
            boolean retired = s.robots.checkLeave(a);
            List<String> m1 = RobotFixture.messages(s.world, f1);
            Player b = s.robots.create();
            b.life = 1;
            b.score = -91;
            boolean disappointed = s.robots.checkLeave(b);
            Player c = s.robots.create();
            c.life = 4;
            c.score = -26;
            long f3 = s.world.events.seq();
            boolean bad = s.robots.checkLeave(c);
            Player d = s.robots.create();
            d.life = 4;
            d.score = -24;
            assertAll(
                () -> assertTrue(retired, "50 lives"),
                () -> assertEquals(2, m1.size(), "a LEAVE talk and the reason"),
                () -> assertEquals(aName + " retired.", m1.get(1), "retired"),
                () -> assertTrue(disappointed, "score -91 < -90"),
                () -> assertTrue(bad, "-26/5 < -5"),
                () -> assertTrue(RobotFixture.messages(s.world, f3).get(1).endsWith(" played too badly."), "too badly"),
                () -> assertFalse(s.robots.checkLeave(d), "-24/5 >= -5: stays"),
                () -> assertEquals(1, s.robots.numRobots(), "three left"));
        }

        @Test
        @DisplayName("nobody leaves without robotsLeave, with life 0, limited lives, or while dead and counting")
        void guards() {
            Simulation s = sim("robotsLeave: no\n", "____");
            Player a = s.robots.create();
            a.life = 99;
            Simulation l = sim("limitedLives: yes\nworldLives: 99\n", "____");
            Player b = l.robots.create();
            b.life = 99;
            Simulation n = sim("", "____");
            Player c = n.robots.create();
            c.life = 0;
            c.score = -1000;
            Player e = n.robots.create();
            e.life = 99;
            e.status &= ~Obj.PLAYING;
            e.count = 5;
            boolean counting = n.robots.checkLeave(e);
            e.count = 0;
            assertAll(
                () -> assertFalse(s.robots.checkLeave(a), "robotsLeave off"),
                () -> assertFalse(l.robots.checkLeave(b), "limited lives"),
                () -> assertFalse(n.robots.checkLeave(c), "life 0"),
                () -> assertFalse(counting, "dead with count 5"),
                () -> assertTrue(n.robots.checkLeave(e), "dead with count 0: checked"));
        }

        @Test
        @DisplayName("the thresholds 0 switch their reason off")
        void zeroThresholds() {
            Simulation s = sim("robotLeaveLife: 0\nrobotLeaveScore: 0\nrobotLeaveRatio: 0\n", "____");
            Player a = s.robots.create();
            a.life = 1000;
            a.score = -1000;
            assertFalse(s.robots.checkLeave(a), "no reason applies");
        }

        @Test
        @DisplayName("delete frees the slot, clears the robot flag and ship, kicked robots are announced")
        void delete() {
            Simulation s = sim("", "___");
            Player a = s.robots.create();
            Player b = s.robots.create();
            RobotData bd = s.robots.data(b);
            DefaultRobot.setWar(bd, a.id);
            bd.lockTagged = DefaultRobot.LOCK_PLAYER;
            bd.lockPlId = a.id;
            b.used |= Has.TRACTOR_BEAM;
            int aid = a.id;
            a.score = -1;
            long from = s.world.events.seq();
            s.robots.delete(null, true);
            assertAll(
                () -> assertFalse(a.inUse, "the lowest scorer left"),
                () -> assertFalse(a.robot, "flag cleared"),
                () -> assertSame(ShipGeom.defaultShip(), a.ship, "default ship again"),
                () -> assertFalse(s.robots.data(a).active, "slot free"),
                () -> assertEquals(List.of("\"" + a.name + "\" upset the gods and was kicked out of the game."),
                    RobotFixture.messages(s.world, from), "kicked"),
                () -> assertEquals(List.of(aid + ":0:0"), RobotFixture.events(s.world, Events.LEAVE, from), "LEAVE"),
                () -> assertEquals(Obj.NO_ID, DefaultRobot.warOnPlayer(bd), "wars on it end"),
                () -> assertEquals(0, bd.lockTagged, "locks on it end"),
                () -> assertEquals(0, b.used & Has.TRACTOR_BEAM, "with the tractor beam"));
        }

        @Test
        @DisplayName("update: a playing robot that should leave leaves; the others play on")
        void updateLeaves() {
            Simulation s = sim("maxRobots: 2\nminRobots: 2\n", "___");
            Player a = s.robots.create();
            Player b = s.robots.create();
            a.score = -100;
            a.life = 1;
            int count = s.robots.data(b).robotCount;
            s.robots.update();
            assertAll(
                () -> assertFalse(a.inUse, "a left"),
                () -> assertTrue(b.inUse, "b stays"),
                () -> assertNotSame(count, s.robots.data(b).robotCount, "b played (its count moved)"));
        }
    }

    @Nested
    @DisplayName("war (Robot_war) and events")
    class War {
        @Test
        @DisplayName("a robot killer gloats and ends its war on the victim")
        void killer() {
            Simulation s = sim("robotsTalk: yes\n", "___");
            Player k = s.robots.create();
            Player v = TestWorlds.join(s, "Victim", 0);
            DefaultRobot.setWar(s.robots.data(k), v.id);
            long from = s.world.events.seq();
            s.robots.war(v, k);
            assertAll(
                () -> assertEquals(Obj.NO_ID, s.robots.warOnPlayer(k), "peace"),
                () -> assertTrue(RobotFixture.messages(s.world, from).get(0).contains("Victim"), "gloats about the victim"));
        }

        @Test
        @DisplayName("a robot victim declares war when (int)(rfrac*100) < the killer's score lead")
        void victim() {
            Simulation s = sim("", "___");
            Player v = s.robots.create();
            Player k = TestWorlds.join(s, "K", 0);
            k.score = 50;
            int low = RobotFixture.seedWithFirst(true, 0.5);
            s.world.rng.seed(low);
            s.robots.war(v, k);
            int declared = s.robots.warOnPlayer(v);
            DefaultRobot.setWar(s.robots.data(v), Obj.NO_ID);
            s.world.rng.seed(RobotFixture.seedWithFirst(false, 0.5));
            s.robots.war(v, k);
            assertAll(
                () -> assertEquals(k.id, declared, "war"),
                () -> assertEquals(Obj.NO_ID, s.robots.warOnPlayer(v), "rfrac*100 >= 50: no war"),
                () -> assertEquals(Obj.NO_ID, s.robots.warOnPlayer(k), "humans have no war"));
        }

        @Test
        @DisplayName("no war on a self kill or a team mate; a human victim draws no rfrac")
        void noWar() {
            Simulation s = sim("", "___");
            Player v = s.robots.create();
            s.robots.war(v, v);
            Player h = TestWorlds.join(s, "H", 0);
            Player h2 = TestWorlds.join(s, "H2", 0);
            s.world.rng.seed(3);
            s.robots.war(h, h2);
            double next = s.world.rfrac();
            assertAll(
                () -> assertEquals(Obj.NO_ID, s.robots.warOnPlayer(v), "self kill"),
                () -> assertEquals(RobotFixture.rfracs(3, 1)[0], next, "no rfrac for a human victim"));
            String[] rows = RobotFixture.open(20, 20, "");
            char[] b = rows[19].toCharArray();
            b[1] = '1';
            b[3] = '1';
            rows[19] = new String(b);
            Simulation t = RobotFixture.sim("teamPlay: yes\nreserveRobotTeam: no\n", rows);
            Player rv = t.ships.join("R", 1, true);
            Player rk = t.ships.join("K", 1);
            rk.score = 1000;
            t.robots.war(rv, rk);
            assertEquals(Obj.NO_ID, t.robots.warOnPlayer(rv), "team mates");
        }

        @Test
        @DisplayName("events: a kill makes war, a spawn takes off, a crash of two ends their wars")
        void events() {
            Simulation s = sim("", "___");
            World w = s.world;
            Player v = s.robots.create();
            Player k = TestWorlds.join(s, "K", 0);
            k.score = 200;
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, k.id, v.id, 0f);
            s.robots.handleEvents();
            int war = s.robots.warOnPlayer(v);
            s.robots.data(v).robotMode = DefaultRobot.RM_ATTACK;
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.SPAWN, v.id, 0, 0f);
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.SPAWN, k.id, 0, 0f);
            s.robots.handleEvents();
            int mode = s.robots.data(v).robotMode;
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, v.id, k.id, Msg.GameEvent.CRASH_SHIPS);
            s.robots.handleEvents();
            assertAll(
                () -> assertEquals(k.id, war, "war on the killer (rfrac*100 < 200)"),
                () -> assertEquals(DefaultRobot.RM_TAKE_OFF, mode, "spawned: TAKE_OFF"),
                () -> assertEquals(Obj.NO_ID, s.robots.warOnPlayer(v), "crashed together: peace"));
        }

        @Test
        @DisplayName("events: a wall crash re-homes a robot with a negative score; departures end wars")
        void wallCrash() {
            Simulation s = sim("", "____");
            World w = s.world;
            Player a = s.robots.create();
            Player b = s.robots.create();
            a.score = -1;
            b.score = 1;
            long from = w.events.seq();
            int oldBase = a.homeBase;
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, a.id, Obj.NO_ID, Msg.GameEvent.CRASH_WALL);
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, b.id, Obj.NO_ID, Msg.GameEvent.CRASH_WALL);
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, 99, Obj.NO_ID, Msg.GameEvent.CRASH_WALL);
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, 99, a.id, 0f);
            s.robots.handleEvents();
            List<String> owners = RobotFixture.events(w, Events.BASE_OWNER, from);
            DefaultRobot.setWar(s.robots.data(b), 42);
            w.events.add(Events.LEAVE, 42, 0, 0, 0f);
            s.robots.handleEvents();
            List<String> expected = oldBase == a.homeBase ? List.of(a.homeBase + ":" + a.id + ":0")
                : List.of(oldBase + ":" + Obj.NO_ID + ":0", a.homeBase + ":" + a.id + ":0");
            assertAll(
                () -> assertEquals(expected, owners, "only the negative robot moves home (the unknown ids change nothing)"),
                () -> assertEquals(Obj.NO_ID, s.robots.warOnPlayer(b), "war on a departed player ends"));
        }

        @Test
        @DisplayName("a losing robot's walls.c crash (CRASH with a wall sub-type, or a shove KILL) moves it to the picked base"
            + " and releases the old one; a universe crash (collision.c) does not")
        void rehomeReleases() {
            Simulation s = sim("", "____");
            World w = s.world;
            Player a = s.robots.create();
            Player b = s.robots.create();
            a.score = -1;
            int first = a.homeBase;
            List<Integer> free = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                if (i != b.homeBase) {
                    free.add(i);
                }
            }
            int away = free.get(0) != first ? 0 : 1;
            int target = free.get(away);
            int back = free.indexOf(first);
            long from = w.events.seq();
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, a.id, Obj.NO_ID, Msg.GameEvent.CRASH_UNIVERSE);
            s.robots.handleEvents();
            List<String> universe = RobotFixture.events(w, Events.BASE_OWNER, from);
            w.rng.seed(DefaultRobotEdgesTest.seedPercent((int) ((away + 0.5) * 100 / 3)));
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, a.id, Obj.NO_ID, Msg.GameEvent.CRASH_WALL);
            s.robots.handleEvents();
            List<String> wall = RobotFixture.events(w, Events.BASE_OWNER, from);
            long mid = w.events.seq();
            w.rng.seed(DefaultRobotEdgesTest.seedPercent((int) ((back + 0.5) * 100 / 3)));
            w.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, 99, a.id, Msg.GameEvent.CRASH_WALL);
            s.robots.handleEvents();
            List<String> shove = RobotFixture.events(w, Events.BASE_OWNER, mid);
            assertAll(
                () -> assertEquals(List.of(), universe, "leaving the known universe keeps the home base"),
                () -> assertEquals(List.of(first + ":" + Obj.NO_ID + ":0", target + ":" + a.id + ":0"), wall,
                    "a wall crash releases the old base, then owns the picked one"),
                () -> assertEquals(List.of(target + ":" + Obj.NO_ID + ":0", first + ":" + a.id + ":0"), shove,
                    "a shove into a wall (KILL with a crash sub-type) re-homes the victim too"),
                () -> assertEquals(first, a.homeBase, "the robot is home at the last picked base"));
        }

        @Test
        @DisplayName("update handles events first and skips its own; disabled updates skip everything")
        void cursor() {
            Simulation s = sim("", "___");
            Player v = s.robots.create();
            Player k = TestWorlds.join(s, "K", 0);
            k.score = 200;
            s.robots.setEnabled(false);
            s.world.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, k.id, v.id, 0f);
            s.robots.update();
            s.robots.setEnabled(true);
            s.robots.update();
            assertEquals(Obj.NO_ID, s.robots.warOnPlayer(v), "the kill happened while disabled");
            s.world.events.add(Events.GAME_EVENT, Msg.GameEvent.KILL, k.id, v.id, 0f);
            s.robots.update();
            assertEquals(k.id, s.robots.warOnPlayer(v), "now it counts");
        }

        @Test
        @DisplayName("locks are refreshed to the wrapped distance; THRUST is sent when a robot's power changes")
        void locksAndPower() {
            Simulation s = sim("maxRobots: 1\n", "___");
            Player r = s.robots.create();
            Player h = TestWorlds.join(s, "H", 0);
            RobotFixture.place(r, 100, 100);
            RobotFixture.place(h, 400, 500);
            RobotData d = s.robots.data(r);
            d.lockTagged = DefaultRobot.LOCK_PLAYER;
            d.lockPlId = h.id;
            r.power = 30;
            long from = s.world.events.seq();
            s.robots.update();
            assertAll(
                () -> assertTrue(d.lockDistance == 500.0 || d.lockTagged == 0, "distance 500 (unless dropped while playing)"),
                () -> assertFalse(RobotFixture.events(s.world, Events.THRUST, from).isEmpty(), "power changed: THRUST"));
        }

        @Test
        @DisplayName("program (ECM) starts a war; warOnPlayer of a human is NO_ID")
        void program() {
            Simulation s = sim("", "___");
            Player r = s.robots.create();
            Player h = TestWorlds.join(s, "H", 0);
            s.robots.program(r, h.id);
            s.robots.program(h, r.id);
            assertAll(
                () -> assertEquals(h.id, s.robots.warOnPlayer(r), "programmed"),
                () -> assertEquals(Obj.NO_ID, s.robots.warOnPlayer(h), "humans"));
        }
    }

    @Nested
    @DisplayName("server API")
    class Api {
        @Test
        @DisplayName("kickRobotPlayers: one robot (lowest score) leaves for a human; none without robots")
        void kick() {
            Simulation s = sim("", "___");
            int none = s.robots.kickRobotPlayers(Const.TEAM_NOT_SET);
            Player a = s.robots.create();
            Player b = s.robots.create();
            b.score = -2;
            int one = s.robots.kickRobotPlayers(Const.TEAM_NOT_SET);
            assertAll(
                () -> assertEquals(0, none, "no robots"),
                () -> assertEquals(1, one, "kicked one"),
                () -> assertFalse(b.inUse, "the lowest"),
                () -> assertTrue(a.inUse, "the other stays"));
        }

        @Test
        @DisplayName("kickRobotPlayers in team play: from the given team, or outside the reserved robot team")
        void kickTeams() {
            String[] rows = RobotFixture.open(20, 20, "");
            char[] b = rows[19].toCharArray();
            b[1] = '0';
            b[3] = '1';
            b[5] = '1';
            rows[19] = new String(b);
            Simulation s = RobotFixture.sim("teamPlay: yes\nrestrictRobots: no\n", rows);
            Player r0 = s.ships.join("R0", 0, true);
            Player r1 = s.ships.join("R1", 1, true);
            s.robots.numRobots = 2;
            int team2 = s.robots.kickRobotPlayers(2);
            int any = s.robots.kickRobotPlayers(Const.TEAM_NOT_SET);
            int team0 = s.robots.kickRobotPlayers(0);
            assertAll(
                () -> assertEquals(0, team2, "no robot on team 2"),
                () -> assertEquals(1, any, "one outside the robot team"),
                () -> assertFalse(r1.inUse, "the team 1 robot"),
                () -> assertEquals(1, team0, "the team 0 robot when asked for team 0"),
                () -> assertFalse(r0.inUse, "gone"),
                () -> assertEquals(0, s.robots.kickRobotPlayers(Const.TEAM_NOT_SET), "none left"));
        }

        @Test
        @DisplayName("kickRobotPlayers: only robot-team robots left and the robot team reserved: none")
        void kickReserved() {
            String[] rows = RobotFixture.open(20, 20, "");
            char[] b = rows[19].toCharArray();
            b[1] = '0';
            b[3] = '1';
            rows[19] = new String(b);
            Simulation s = RobotFixture.sim("teamPlay: yes\n", rows);
            s.ships.join("R0", 0, true);
            s.robots.numRobots = 1;
            assertEquals(0, s.robots.kickRobotPlayers(Const.TEAM_NOT_SET), "the robot team's robots stay");
        }

        @Test
        @DisplayName("applyMaxRobots: minRobots drops to maxRobots, extra robots are kicked")
        void applyMax() {
            Simulation s = sim("maxRobots: 3\nminRobots: 3\n", "___");
            s.robots.create();
            s.robots.create();
            s.robots.create();
            s.world.params.maxRobots = 1;
            s.robots.applyMaxRobots();
            assertAll(
                () -> assertEquals(1, s.world.params.minRobots, "minRobots lowered"),
                () -> assertEquals(1, s.robots.numRobots(), "one robot left"));
        }

        @Test
        @DisplayName("setRoster and setItems replace the roster and the item gate")
        void replace() {
            Simulation s = sim("", "___");
            RobotRoster r = RobotRoster.parse("name: Solo\npara: 1 2\n");
            s.robots.setRoster(r);
            Player p = s.robots.create();
            DefaultRobot before = s.robots.robot();
            s.robots.setItems(new RobotItems());
            assertAll(
                () -> assertEquals("Solo", p.name.toString(), "from the new roster"),
                () -> assertNotSame(before, s.robots.robot(), "a new robot type instance with the gate"));
        }

        @Test
        @DisplayName("Ships.join for robots: may take the reserved robot team; humans may not")
        void join() {
            String[] rows = RobotFixture.open(20, 20, "");
            char[] b = rows[19].toCharArray();
            b[1] = '0';
            b[3] = '1';
            rows[19] = new String(b);
            Simulation s = RobotFixture.sim("teamPlay: yes\n", rows);
            Player h = s.ships.join("H", 0);
            Player r = s.ships.join("R", 0, true);
            assertAll(
                () -> assertEquals(1, h.team, "a human is moved off the robot team"),
                () -> assertFalse(h.robot, "human"),
                () -> assertEquals(0, r.team, "a robot gets it"),
                () -> assertTrue(r.robot, "robot"));
        }
    }
}
