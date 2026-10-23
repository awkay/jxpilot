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
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
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

/** RobotManager boundaries (robot.c) and the default item gate. */
class RobotManagerEdgesTest {

    private static Simulation sim(String extra, String bases) {
        Simulation s = RobotFixture.sim(extra, RobotFixture.open(20, 20, bases));
        s.robots.setEnabled(true);
        return s;
    }

    /** A team play world with the given base team digits on the bottom row. */
    private static Simulation teams(String extra, String digits) {
        String[] rows = RobotFixture.open(30, 20, "");
        char[] b = rows[19].toCharArray();
        for (int i = 0; i < digits.length(); i++) {
            b[1 + 2 * i] = digits.charAt(i);
        }
        rows[19] = new String(b);
        return RobotFixture.sim("teamPlay: yes\n" + extra, rows);
    }

    @Nested
    @DisplayName("counts")
    class Counts {
        @Test
        @DisplayName("never more than maxRobots players, not even for a frame; a full map keeps its robots")
        void steady() {
            Simulation s = sim("maxRobots: 2\n", "____");
            int most = 0;
            for (int i = 0; i < 600; i++) {
                s.robots.update();
                most = Math.max(most, s.robots.numRobots());
            }
            Simulation full = sim("maxRobots: 5\nminRobots: 0\n", "__");
            int fewest = 9;
            for (int i = 0; i < 600; i++) {
                full.robots.update();
                if (i > 300) {
                    fewest = Math.min(fewest, full.robots.numRobots());
                }
            }
            int m = most;
            int f = fewest;
            assertAll(
                () -> assertEquals(2, m, "at most 2"),
                () -> assertEquals(2, f, "2 robots on 2 bases stay"));
        }

        @Test
        @DisplayName("never more robots than the roster has")
        void roster() {
            Simulation s = sim("maxRobots: 3\n", "____");
            s.robots.setRoster(RobotRoster.parse("name: Solo\n"));
            for (int i = 0; i < 600; i++) {
                s.robots.update();
            }
            assertEquals(1, s.robots.numRobots(), "one robot in the roster");
        }

        @Test
        @DisplayName("a dead robot leaves on its respawn frame and the next robot still plays that frame")
        void deadLeaves() {
            Simulation s = sim("maxRobots: 2\nminRobots: 2\n", "___");
            Player a = s.robots.create();
            Player b = s.robots.create();
            a.status &= ~Obj.PLAYING;
            a.count = 0;
            a.life = 1;
            a.score = -100;
            int before = s.robots.data(b).robotCount;
            s.robots.update();
            assertAll(
                () -> assertFalse(a.inUse, "a left"),
                () -> assertTrue(s.robots.data(b).robotCount != before, "b played"));
        }

        @Test
        @DisplayName("each update refreshes the visibility for the robot count and the object cells")
        void perFrame() {
            Simulation s = sim("maxRobots: 2\nminRobots: 2\n", "___");
            s.robots.create();
            s.robots.create();
            s.robots.update();
            Simulation one = sim("maxRobots: 1\n", "___");
            Player a = one.robots.create();
            Player h = TestWorlds.join(one, "H", 0);
            RobotFixture.place(h, 600, 600);
            int shot = RobotFixture.obj(one.world, Obj.SHOT, h.id, a.px + 10, a.py + 40, 0, 0);
            one.robots.update();
            assertAll(
                () -> assertEquals(256 + (744.0 * 254) / 256, s.robots.robot().visibilityDistance, 1e-9, "2 robots"),
                () -> assertEquals(shot, one.robots.robot().mineI, "the cells were rebuilt: the shot is seen"));
        }

        @Test
        @DisplayName("enabled() reports the switch; an out-of-range robotTeam 10 becomes 0")
        void switches() {
            Simulation s = sim("robotTeam: 10\n", "_");
            assertAll(
                () -> assertTrue(s.robots.enabled(), "on"),
                () -> assertEquals(0, s.world.params.robotTeam, "10 is out of range"));
        }
    }

    @Nested
    @DisplayName("create, pick, delete")
    class CreateDelete {
        @Test
        @DisplayName("create resets a slot's leftover robot state")
        void cleanSlot() {
            Simulation s = sim("", "__");
            Player p = TestWorlds.join(s, "H", 0);
            RobotData d = s.robots.data(p);
            d.lockTagged = 3;
            d.modMini = 2;
            s.ships.leave(p);
            Player r = s.robots.create();
            assertAll(
                () -> assertEquals(p.slot, r.slot, "the same slot"),
                () -> assertEquals(0, s.robots.data(r).lockTagged, "no lock"),
                () -> assertEquals(0, s.robots.data(r).modMini, "no modifiers"));
        }

        @Test
        @DisplayName("pick searches upward (wrapping) from the random start; robots in play count as most used")
        void pickWraps() {
            Simulation s = sim("", "____");
            Player p = s.robots.create();
            RobotRoster r = s.robots.roster();
            for (int i = 0; i < r.size(); i++) {
                r.used[i] = 1;
            }
            s.robots.data(p).def = 30;
            r.used[30] = 0;
            r.used[0] = 0;
            int seed = 1;
            while (true) {
                int start = (int) (RobotFixture.rfracs(seed, 1)[0] * 38);
                if (start >= 1 && start <= 29) {
                    break;
                }
                seed++;
            }
            s.world.rng.seed(seed);
            int got = s.robots.pick();
            for (int i = 0; i < r.size(); i++) {
                r.used[i] = 1;
            }
            r.used[5] = 0;
            r.used[20] = 0;
            int seed2 = 1;
            while (true) {
                int start = (int) (RobotFixture.rfracs(seed2, 1)[0] * 38);
                if (start >= 11 && start <= 19) {
                    break;
                }
                seed2++;
            }
            s.world.rng.seed(seed2);
            s.robots.data(p).def = 37;
            int up = s.robots.pick();
            assertAll(
                () -> assertEquals(0, got, "robot 30 is in play (counted used 1): wrap to 0"),
                () -> assertEquals(20, up, "from 11..19 upward to 20, not down to 5"));
        }

        @Test
        @DisplayName("delete(null) takes the first of equal lowest scores")
        void ties() {
            Simulation s = sim("", "___");
            Player a = s.robots.create();
            Player b = s.robots.create();
            s.robots.delete(null, false);
            assertAll(
                () -> assertFalse(a.inUse, "the first"),
                () -> assertTrue(b.inUse, "the second stays"));
        }

        @Test
        @DisplayName("checkLeave boundaries: score exactly robotLeaveScore and ratio exactly robotLeaveRatio stay")
        void leaveEdges() {
            Simulation s = sim("", "___");
            Player a = s.robots.create();
            a.life = 49;
            a.score = -90;
            Player b = s.robots.create();
            b.life = 4;
            b.score = -25;
            assertAll(
                () -> assertFalse(s.robots.checkLeave(a), "-90 is not < -90 (and -90/50 is a fine ratio)"),
                () -> assertFalse(s.robots.checkLeave(b), "-25/5 = -5 is not < -5"));
        }
    }

    @Nested
    @DisplayName("pickTeam")
    class PickTeam {
        @Test
        @DisplayName("counts players per team: the team with fewer players gets the robot")
        void fewest() {
            Simulation s = teams("restrictRobots: no\nreserveRobotTeam: no\n", "0111223");
            s.ships.join("A", 1);
            s.ships.join("B", 1);
            s.ships.join("C", 2);
            assertEquals(2, s.robots.pickTeam(), "teams 1 (2 players) and 2 (1): team 2");
        }

        @Test
        @DisplayName("a team whose bases are all taken (by a paused player) is not a candidate")
        void taken() {
            Simulation s = teams("restrictRobots: no\nreserveRobotTeam: no\n", "0122");
            Player a = s.ships.join("A", 1);
            a.status |= Obj.PAUSE;
            assertEquals(2, s.robots.pickTeam(), "team 1 has no free base: team 2");
        }

        @Test
        @DisplayName("with one team playing and every other team full, the playing team is the fallback")
        void fallback() {
            Simulation s = teams("restrictRobots: no\nreserveRobotTeam: no\n", "1123");
            s.ships.join("A", 1);
            Player b = s.ships.join("B", 2);
            b.status |= Obj.PAUSE;
            Player c = s.ships.join("C", 3);
            c.status |= Obj.PAUSE;
            assertEquals(1, s.robots.pickTeam(), "team 1 still has a free base");
        }
    }

    @Nested
    @DisplayName("events and war")
    class Events_ {
        @Test
        @DisplayName("a crash of two: the robot at war with the other stops, whichever of the pair it is")
        void crashPair() {
            Simulation s = sim("", "___");
            Player v = s.robots.create();
            Player k = TestWorlds.join(s, "K", 0);
            DefaultRobot.setWar(s.robots.data(v), k.id);
            s.world.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, k.id, v.id, Msg.GameEvent.CRASH_SHIPS);
            s.robots.handleEvents();
            assertEquals(Obj.NO_ID, s.robots.warOnPlayer(v), "peace");
        }

        @Test
        @DisplayName("a wall crash at score exactly 0 keeps the home base")
        void zeroScore() {
            Simulation s = sim("", "____");
            Player a = s.robots.create();
            a.score = 0;
            long from = s.world.events.seq();
            s.world.events.add(Events.GAME_EVENT, Msg.GameEvent.CRASH, a.id, Obj.NO_ID, Msg.GameEvent.CRASH_WALL);
            s.robots.handleEvents();
            assertEquals(List.of(), RobotFixture.events(s.world, Events.BASE_OWNER, from), "no new base");
        }

        @Test
        @DisplayName("war needs (int)(rfrac*100) strictly below killer score - victim score; the robot says so")
        void warRoll() {
            Simulation s = sim("robotsTalk: yes\n", "___");
            Player v = s.robots.create();
            Player k = TestWorlds.join(s, "K", 0);
            v.score = 30;
            k.score = 80;
            s.world.rng.seed(DefaultRobotEdgesTest.seedPercent(50));
            s.robots.war(v, k);
            int at50 = s.robots.warOnPlayer(v);
            s.world.rng.seed(DefaultRobotEdgesTest.seedPercent(49));
            long from = s.world.events.seq();
            s.robots.war(v, k);
            assertAll(
                () -> assertEquals(Obj.NO_ID, at50, "50 is not < 80 - 30"),
                () -> assertEquals(k.id, s.robots.warOnPlayer(v), "49 is"),
                () -> assertEquals(1, RobotFixture.messages(s.world, from).size(), "a war message"),
                () -> assertTrue(RobotFixture.messages(s.world, from).get(0).contains("K"), "naming the killer"));
        }

        @Test
        @DisplayName("departures: locks on the leaver go (also LOCK_VISIBLE only); other locks stay unless alone")
        void departures() {
            Simulation s = sim("", "____");
            Player a = s.robots.create();
            Player h = TestWorlds.join(s, "H", 0);
            Player g = TestWorlds.join(s, "G", 0);
            RobotData d = s.robots.data(a);
            d.lockTagged = DefaultRobot.LOCK_VISIBLE;
            d.lockPlId = h.id;
            s.robots.playerLeft(h.id);
            int visible = d.lockTagged;
            d.lockTagged = DefaultRobot.LOCK_PLAYER;
            d.lockPlId = g.id;
            s.robots.playerLeft(h.id);
            int other = d.lockTagged;
            s.ships.leave(h);
            s.ships.leave(g);
            s.robots.playerLeft(h.id);
            assertAll(
                () -> assertEquals(0, visible, "lock on the leaver"),
                () -> assertEquals(DefaultRobot.LOCK_PLAYER, other, "a lock on someone else"),
                () -> assertEquals(0, d.lockTagged, "alone: every lock goes"));
        }

        @Test
        @DisplayName("lock distances are refreshed only for LOCK_PLAYER locks")
        void refreshOnlyPlayerLocks() {
            Simulation s = sim("maxRobots: 1\n", "___");
            Player r = s.robots.create();
            Player h = TestWorlds.join(s, "H", 0);
            RobotData d = s.robots.data(r);
            d.lockTagged = DefaultRobot.LOCK_VISIBLE;
            d.lockPlId = h.id;
            d.lockDistance = 12345;
            r.status &= ~Obj.PLAYING;
            r.count = 10;
            s.robots.update();
            assertEquals(12345.0, d.lockDistance, "not refreshed");
        }
    }

    @Nested
    @DisplayName("kicking")
    class Kick {
        @Test
        @DisplayName("team play without reserveRobotTeam: any robot (the lowest score) is kicked")
        void anyTeam() {
            Simulation s = teams("restrictRobots: no\nreserveRobotTeam: no\n", "0011");
            Player r0 = s.ships.join("R0", 0, true);
            Player r1 = s.ships.join("R1", 1, true);
            r0.score = -5;
            s.robots.numRobots = 2;
            s.robots.kickRobotPlayers(Const.TEAM_NOT_SET);
            assertAll(
                () -> assertFalse(r0.inUse, "the robot team's robot (lowest)"),
                () -> assertTrue(r1.inUse, "the other stays"));
        }
    }

    @Nested
    @DisplayName("RobotRoster and RobotItems defaults")
    class Defaults {
        @Test
        @DisplayName("a file without a final newline still ends its last record")
        void noNewline() {
            assertEquals("Solo", RobotRoster.parse("name: Solo").def(0).name(), "one robot");
        }

        @Test
        @DisplayName("the S1 gate: no items, implemented abilities only, everyone visible, inert actions")
        void gate() {
            Simulation s = sim("", "__");
            Player p = TestWorlds.join(s, "P", 0);
            p.have = Has.SHIELD | Has.CLOAKING_DEVICE | Has.TRACTOR_BEAM | Has.REFUEL;
            RobotItems it = new RobotItems();
            int used = p.used;
            double fuel = p.fuel;
            it.emergencyShield(p);
            it.emergencyThrust(p);
            it.deflector(p);
            it.placeMine(p, true);
            it.fireEcm(p);
            it.transporter(p);
            it.fireMissile(p, Obj.SMART_SHOT, 0);
            it.hyperjump(p);
            it.detachTank(p);
            it.detachBall(p);
            it.sensorRange(p);
            assertAll(
                () -> assertEquals(0, it.count(p, Item.MISSILE), "no items"),
                () -> assertEquals(Has.SHIELD | Has.REFUEL, it.have(p), "implemented abilities"),
                () -> assertTrue(it.canSee(p, p), "visible"),
                () -> assertEquals(0, it.numPulses(p), "no pulses"),
                () -> assertEquals(0, it.numTanks(p), "no tanks"),
                () -> assertEquals(0, it.limit(Item.MINE), "limit 0"),
                () -> assertEquals(0, it.activeDefensives(p), "none active"),
                () -> assertEquals(DefaultRobot.TARGET_DAMAGE, it.targetDamage(0), "targets intact"),
                () -> assertFalse(it.treasureHasBall(0), "no balls at home"),
                () -> assertEquals(-1, it.towedBall(p), "no towed ball"),
                () -> assertEquals(-1, it.ballOwner(0), "no ball owner"),
                () -> assertEquals(1, it.asteroidSize(0), "size 1"),
                () -> assertEquals(0, it.itemType(0), "item 0"),
                () -> assertFalse(it.randomItem(0), "not random"),
                () -> assertEquals(0, it.pulseCount(), "no pulses"),
                () -> assertEquals(0.0, it.pulseX(0) + it.pulseY(0), "pulse position 0"),
                () -> assertEquals(0, it.pulseDir(0), "pulse dir 0"),
                () -> assertEquals(-1, it.pulseOwner(0), "no owner"),
                () -> assertFalse(it.pulseReflected(0), "not reflected"),
                () -> assertEquals(used, p.used, "actions change nothing"),
                () -> assertEquals(fuel, p.fuel, "no fuel used"));
        }
    }
}
