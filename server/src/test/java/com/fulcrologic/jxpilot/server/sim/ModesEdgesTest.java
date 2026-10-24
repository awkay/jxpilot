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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ModeFx.ballOf;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.raceSim;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.ship;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.targetSim;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.teamSim;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.tow;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.obj;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Game-mode edge cases (boundaries and branches)")
class ModesEdgesTest {
    static final double EPS = 1e-9;
    static final int FULL = ModeState.TARGET_DAMAGE;

    @Nested
    @DisplayName("race")
    class RaceEdges {
        @Test
        @DisplayName("positions after earlier finishers shift the points; 1 point is singular; 0 points just the position")
        void positions() {
            Simulation s = raceSim("");
            World w = s.world;
            Player[] p = new Player[3];
            for (int i = 0; i < 3; i++) {
                p[i] = ship(s, "P" + i, 0, 100 + 40 * i, 100);
            }
            p[0].status |= Obj.GAME_OVER | Obj.RACE_OVER;
            p[1].status |= Obj.GAME_OVER | Obj.FINISH;
            p[2].round = 1;
            long from = w.events.seq();
            s.race.gameStatus();
            List<String> second = messages(w, from);
            Simulation many = TestWorlds.sim(SimFixture.FPS14 + "timing: yes\nlimitedLives: yes\nworldLives: 3\n",
                manyRows());
            World mw = many.world;
            Player[] q = new Player[6];
            for (int i = 0; i < 6; i++) {
                q[i] = ship(many, "Q" + i, 0, 100 + 30 * i, 100);
                q[i].status |= Obj.GAME_OVER | Obj.RACE_OVER;
            }
            q[3].status &= ~Obj.RACE_OVER;
            q[3].status |= Obj.FINISH;
            q[5].status &= ~(Obj.RACE_OVER | Obj.GAME_OVER);
            q[5].round = 2;
            long from2 = mw.events.seq();
            many.race.gameStatus();
            List<String> late = messages(mw, from2);
            assertAll(
                () -> assertEquals("P1 finishes in position 2 scoring 8.00 points.", second.get(0),
                    "after one RACE_OVER: position 2, (10 + 2*3) >> 1 = 8"),
                () -> assertEquals("Q3 finishes in position 5 scoring 1.00 point.", late.get(0),
                    "position 5 of 6 active: 22 >> 4 = 1 point, singular"));
        }

        static String[] manyRows() {
            String[] rows = SimFixture.open(30, 10, "______");
            rows[4] = "          A         B         ";
            return rows;
        }

        @Test
        @DisplayName("pts 0: just the position; waiting players are counted apart; one racer on lap 0 keeps a limited-lives race going")
        void zeroAndWaiting() {
            Simulation s = TestWorlds.sim(SimFixture.FPS14 + "timing: yes\nlimitedLives: yes\nworldLives: 3\n",
                RaceEdges.manyRows());
            World w = s.world;
            Player[] q = new Player[6];
            for (int i = 0; i < 6; i++) {
                q[i] = ship(s, "Q" + i, 0, 100 + 30 * i, 100);
                q[i].status |= Obj.GAME_OVER | Obj.RACE_OVER;
            }
            q[5].status &= ~Obj.RACE_OVER;
            q[5].status |= Obj.FINISH;
            long from = w.events.seq();
            s.race.gameStatus();
            List<String> m = messages(w, from);
            Simulation t = TestWorlds.sim(SimFixture.FPS14 + "timing: yes\nlimitedLives: yes\nworldLives: 3\n",
                RaceEdges.manyRows());
            Player a = ship(t, "A", 0, 100, 100);
            Player b = ship(t, "B", 0, 140, 100);
            Player wt = ship(t, "W", 0, 180, 100);
            wt.status |= Obj.GAME_OVER;
            wt.mychar = 'W';
            b.status |= Obj.GAME_OVER | Obj.RACE_OVER;
            long from2 = t.world.events.seq();
            t.race.gameStatus();
            List<String> lap0 = messages(t.world, from2);
            assertAll(
                () -> assertEquals("Q5 finishes in position 6.", m.get(0), "(10+12) >> 5 = 0 points: no score"),
                () -> assertEquals(List.of(), lap0, "the last racer has not started a lap: the race goes on"),
                () -> assertEquals(0, a.round, "(A)"));
        }

        @Test
        @DisplayName("race end: GAME_OVER ships only reset, racing ones die; a paused ship moved to a new base goes there; rounds counted")
        void gameOver() {
            Simulation s = raceSim("roundsToPlay: 3\nreset: no\n");
            World w = s.world;
            Player racing = ship(s, "R", 0, 100, 100);
            Player done = ship(s, "D", 0, 140, 100);
            Player paused = ship(s, "P", 0, 180, 100);
            done.status |= Obj.GAME_OVER;
            paused.status |= Obj.PAUSE;
            paused.count = 0;
            racing.bestLap = 50;
            int[] order = w.map.baseOrder();
            racing.homeBase = order[2];
            paused.homeBase = order[0];
            done.homeBase = order[1];
            long bursts = w.events.burstSeq();
            long from = w.events.seq();
            s.race.gameOver();
            List<String> m = messages(w, from);
            assertAll(
                () -> assertEquals(bursts + 1, w.events.burstSeq(), "the racing ship explodes; the GAME_OVER one only resets"),
                () -> assertEquals(order[0], racing.homeBase, "the only lap-timed racer starts first"),
                () -> assertEquals(order[1], done.homeBase, "then the others in player order"),
                () -> assertEquals(order[2], paused.homeBase, "the paused ship moves to the third start"),
                () -> assertEquals((w.map.bases().get(order[2]).bx() + 0.5) * 35, paused.px, 1, "and is moved there"),
                () -> assertTrue(m.contains(" < Round 1 out of 3 completed. >"), "the round is counted"),
                () -> assertTrue(m.contains("R had the best lap time of 3.57s"), "best lap message"));
        }

        @Test
        @DisplayName("only robots racing: no shame message; reset: paused ships and non-race worlds get no start delay")
        void robotsAndReset() {
            Simulation s = raceSim("");
            World w = s.world;
            Player r = ship(s, "R", 0, 100, 100);
            r.robot = true;
            long from = w.events.seq();
            s.race.gameOver();
            List<String> m = messages(w, from);
            Player p = ship(s, "P", 0, 140, 100);
            p.status |= Obj.PAUSE;
            p.count = 7;
            s.race.resetPlayer(p);
            Simulation plain = SimFixture.sim(SimFixture.FPS14, 20, 10, "___");
            Player q = ship(plain, "Q", 0, 100, 100);
            q.count = 4;
            plain.race.resetPlayer(q);
            assertAll(
                () -> assertFalse(m.stream().anyMatch(x -> x.startsWith("No-one")), "robots alone are not shamed"),
                () -> assertEquals(7, p.count, "a paused ship keeps its count"),
                () -> assertEquals(4, q.count, "outside race mode no start delay"));
        }

        @Test
        @DisplayName("%.2f: a fraction of exactly .10 keeps both digits")
        void fixed2() {
            StringBuilder b = new StringBuilder();
            Race.appendFixed2(1.1f, b);
            StringBuilder c = new StringBuilder();
            Race.appendFixed2(0f, c);
            assertAll(
                () -> assertEquals("1.10", b.toString(), "1.10"),
                () -> assertEquals("0.00", c.toString(), "zero"));
        }
    }

    @Nested
    @DisplayName("targets")
    class TargetEdges {
        @Test
        @DisplayName("repair picks the nearer of two own targets by block centre; on a tie the first")
        void pick() {
            Simulation s = targetSim("");
            World w = s.world;
            Player p = ship(s, "P", 2, 576, 3 * 35 + 17);
            p.have |= Has.REPAIR;
            s.targets.startRepair(p);
            int nearer = p.repairTarget;
            p.initPixels(16 * 35 + 17, 3 * 35 + 17);
            s.targets.startRepair(p);
            int tie = p.repairTarget;
            p.initPixels(611, 3 * 35 + 17);
            s.targets.startRepair(p);
            int other = p.repairTarget;
            assertAll(
                () -> assertEquals(1, nearer, "34 px vs 36 px: target 1"),
                () -> assertEquals(1, tie, "35 px each: the first"),
                () -> assertEquals(2, other, "nearer target 2"));
        }

        @Test
        @DisplayName("repair edges: exactly 90 px still repairs; fuel exactly REFUEL_RATE stops")
        void repairEdges() {
            Simulation s = targetSim("");
            World w = s.world;
            Player p = ship(s, "P", 2, 15.5 * 35 + 90, 3.5 * 35);
            p.used |= Has.REPAIR;
            p.repairTarget = 1;
            w.modes.targetDamage[1] = 100;
            s.targets.repair(p);
            int at90 = w.modes.targetDamage[1];
            p.fuel = Ships.REFUEL_RATE + 1;
            s.targets.repair(p);
            int justAbove = w.modes.targetDamage[1];
            p.fuel = Ships.REFUEL_RATE;
            s.targets.repair(p);
            assertAll(
                () -> assertEquals(100 + 457, at90, "90 px away: repaired"),
                () -> assertEquals(100 + 2 * 457, justAbove, "fuel above REFUEL_RATE: repaired"),
                () -> assertEquals(0, p.used & Has.REPAIR, "fuel at REFUEL_RATE: stops"));
        }

        @Test
        @DisplayName("damage edges: a non-damaging type changes nothing (not even the change time); ship cost 0 is a quarter; missiles through objectHits")
        void damageEdges() {
            Simulation s = targetSim("");
            World w = s.world;
            Player p = ship(s, "P", 1, 100, 100);
            w.frames = 77;
            s.targets.damage(1, Obj.DEBRIS, p.id, 1, 0, 0, 1, false, 0, 1f, -1);
            long changed = w.modes.targetLastChange[1];
            s.targets.damage(1, Obj.PLAYER, p.id, 1, 0, 0, 1, false, 0, 1f, 0);
            int zeroCost = FULL - w.modes.targetDamage[1];
            w.modes.targetDamage[1] = FULL;
            int h = obj(w, Obj.SMART_SHOT, p.id, 100, 100, 0, 0, 5, 10);
            w.objects.team[h] = 1;
            s.targets.objectHits(1, h);
            assertAll(
                () -> assertEquals(0L, changed, "debris: untouched"),
                () -> assertEquals(FULL / 4, zeroCost, "cost 0: a quarter"),
                () -> assertEquals(FULL - 30720, w.modes.targetDamage[1], "a missile (no modifiers): 120*256"),
                () -> assertEquals(77L, w.modes.targetLastChange[1], "the change time"));
        }

        @Test
        @DisplayName("destruction: debris from the target centre; with two winners each gets Rate*losers/winners")
        void scores() {
            Simulation s = targetSim("");
            World w = s.world;
            Player k = ship(s, "K", 1, 100, 100);
            Player k2 = ship(s, "L", 1, 140, 100);
            Player v = ship(s, "V", 2, 500, 100);
            k.score = 30;
            k2.score = 10;
            v.score = 5;
            w.teamScore[1] = 0;
            w.teamScore[2] = 0;
            w.targetDeadTime[2] = 3;
            w.modes.targetDamage[1] = 1;
            s.targets.damage(1, Obj.PLAYER, k.id, 1, 0, 0, 1, false, 0, 1f, -1);
            var b = SimFixture.lastBurst(w);
            float sc = Scoring.rate(40, 5);
            float por = sc * 1 / 2;
            assertAll(
                () -> assertEquals(15.5f * 35, b.x, 0.5, "debris from the target's centre (x)"),
                () -> assertEquals(3.5f * 35, b.y, 0.5, "(y)"),
                () -> assertEquals(-sc, w.teamScore[2], 1e-6, "the loser loses Rate(40, 5)"),
                () -> assertEquals(2 * por, w.teamScore[1], 1e-6, "each winner gains Rate * 1 / 2"));
        }

        @Test
        @DisplayName("ten targets: no partial message; a tiny partial score is not given")
        void ten() {
            String[] rows = SimFixture.open(30, 8, "");
            rows[7] = " 1 1 1                  2 2 2";
            rows[4] = "  !         !!!!!!!!!!       ";
            Simulation s = TestWorlds.sim(ModeFx.TEAM, rows);
            World w = s.world;
            Player k = ship(s, "K", 1, 100, 100);
            ship(s, "V", 2, 900, 100);
            k.score = 10000;
            w.modes.targetDamage[1] = 1;
            long from = w.events.seq();
            s.targets.damage(1, Obj.PLAYER, k.id, 1, 0, 0, 1, false, 0, 1f, -1);
            assertAll(
                () -> assertEquals(10, w.modes.targetTeam.length - 1, "(ten team 2 targets)"),
                () -> assertEquals(List.of(), messages(w, from), "ten targets: no message"),
                () -> assertEquals(0.0, w.teamScore[1], 1e-9, "Rate(10000, CANNON)/4 * 1/11 < 0.01: nothing"));
        }
    }

    @Nested
    @DisplayName("balls")
    class BallEdges {
        @Test
        @DisplayName("connect at exactly the length; own-team ball owned before by anyone can be reached")
        void connectEdge() {
            Simulation s = teamSim("");
            World w = s.world;
            Objects o = w.objects;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, o.px[h] - 120, o.py[h]);
            p.used |= Has.CONNECTOR;
            p.ball = h;
            s.balls.connect(p);
            assertEquals(p.id, o.owner[h], "120 px = ballConnectorLength: connected");
        }

        @Test
        @DisplayName("a ball race: an owned ball near its owner's checkpoint passes it (connected, with ballraceConnected)")
        void ballrace() {
            String[] rows = SimFixture.open(20, 10, "___");
            rows[4] = "  *       A         ";
            rows[8] = "          B         ";
            Simulation s = TestWorlds.sim(SimFixture.FPS14 + "timing: yes\nballrace: yes\nballraceConnected: yes\n", rows);
            World w = s.world;
            Objects o = w.objects;
            int h = ballOf(w, 0);
            Player p = ship(s, "P", 0, 100, 100);
            w.modes.ballOwner[h] = p.id;
            w.modes.treasureHave[0] = false;
            o.initPixels(h, 10 * 35, 5 * 35);
            s.balls.collide();
            int loose = p.check;
            o.owner[h] = p.id;
            s.balls.collide();
            assertAll(
                () -> assertEquals(0, loose, "not connected: no pass (ballraceConnected)"),
                () -> assertEquals(1, p.check, "connected: the owner passes A"));
        }

        @Test
        @DisplayName("a ball-ball collision at exactly maxObjectWallBounceSpeed bounces")
        void bounceEdge() {
            Simulation s = teamSim("ballCollisions: yes\nmaxObjectWallBounceSpeed: 20\n");
            World w = s.world;
            Objects o = w.objects;
            int a = obj(w, Obj.BALL, Obj.NO_ID, 300, 150, 10, 0, 50, 100);
            int b = obj(w, Obj.BALL, Obj.NO_ID, 315, 150, -10, 0, 50, 100);
            w.modes.ballTreasure[a] = 1;
            w.modes.ballTreasure[b] = 1;
            o.plRadius[a] = o.plRadius[b] = 10;
            w.grid.rebuild(o);
            s.balls.collide();
            assertEquals(100.0, o.life[a], EPS, "relative speed 20 = max: they bounce, alive");
        }

        @Test
        @DisplayName("move: |ratio| exactly at maxBallConnectorRatio holds; a string at its length pulls")
        void moveEdges() {
            Simulation s = teamSim("maxBallConnectorRatio: 0.25\nconnectorIsString: yes\n");
            World w = s.world;
            int h = ballOf(w, 2);
            Player p = ship(s, "P", 1, 300, 200);
            tow(w, p, h);
            w.objects.initPixels(h, 300 - 150, 200);
            s.balls.move(h);
            int held = w.objects.owner[h];
            w.objects.initPixels(h, 300 - 120, 200);
            w.objects.vx[h] = 0;
            w.objects.vy[h] = 0;
            p.vx = 5;
            s.balls.move(h);
            assertAll(
                () -> assertEquals(p.id, held, "ratio -0.25 = max: still connected"),
                () -> assertTrue(p.vx < 5, "a string at exactly its length is not slack: damping pulls"));
        }

        @Test
        @DisplayName("playerHit outside team play: exact scores for an owned ball; fuel exactly 0 with collisions that may kill")
        void scoring() {
            Simulation s = TestWorlds.sim(SimFixture.FPS14 + "treasureCollisionMayKill: yes\n", ModeFx.teamRows());
            World w = s.world;
            int h = ballOf(w, 0);
            Player k = ship(s, "K", 0, 300, 200);
            Player v = ship(s, "V", 0, 400, 200);
            k.score = 20;
            v.score = 3;
            w.modes.ballOwner[h] = k.id;
            s.balls.playerHit(v, h, 26);
            float sc = Scoring.rate(20, 3);
            Player z = ship(s, "Z", 0, 500, 200);
            z.used |= Has.SHIELD | Has.EMERGENCY_SHIELD;
            z.fuel = 0;
            w.modes.ballOwner[h] = Obj.NO_ID;
            s.balls.playerHit(z, h, 26);
            assertAll(
                () -> assertEquals(20 + sc, k.score, 1e-9, "the owner gains Rate(owner, victim)"),
                () -> assertEquals(3 - sc, v.score, 1e-9, "the victim loses it"),
                () -> assertTrue((z.status & Obj.KILLED) != 0, "no fuel left: even a shielded ship dies"),
                () -> assertTrue(z.score < 0, "Rate(0)*ball*unowned"));
        }

        @Test
        @DisplayName("ignored players: a waiting player with a score counts; makeTreasureBall returns the new handle")
        void misc() {
            Simulation s = teamSim("");
            World w = s.world;
            Player wt = ship(s, "W", 2, 300, 200);
            wt.status |= Obj.GAME_OVER;
            wt.mychar = 'W';
            wt.score = 1;
            Player go = ship(s, "G", 2, 340, 200);
            go.status |= Obj.GAME_OVER;
            go.mychar = 'D';
            int h = ballOf(w, 0);
            w.objects.life[h] = 0;
            w.modes.ballOwner[h] = 3;
            w.objects.status[h] &= ~Obj.RECREATE;
            s.shots.delete(h);
            int made = s.balls.makeTreasureBall(0);
            assertAll(
                () -> assertFalse(Balls.ignored(wt), "waiting with a score: counted"),
                () -> assertFalse(Balls.ignored(go), "GAME_OVER but not waiting: counted"),
                () -> assertEquals(ballOf(w, 0), made, "the handle of the new ball"));
        }

        @Test
        @DisplayName("treasure counters after a reset: treasures minus empty ones")
        void resetCounts() {
            Simulation s = teamSim("");
            s.balls.resetRound();
            assertEquals(1, s.world.modes.teamTreasuresLeft[2], "team 2: 2 treasures, 1 empty: 1 left");
        }
    }

    @Nested
    @DisplayName("mode params and state")
    class ParamsState {
        @Test
        @DisplayName("tuneAsteroids: chance (int)(1/(prob*blocks*FPS)) at least 1, 0 without prob; max at least 1, 0 without density")
        void tune() {
            com.fulcrologic.jxpilot.server.world.ModeParams m = new com.fulcrologic.jxpilot.server.world.ModeParams();
            m.asteroidProb = 1e-5f;
            m.maxAsteroidDensity = 0.0001f;
            m.asteroidConcentratorRadius = 100;
            m.asteroidConcentratorProb = 2;
            m.tuneAsteroids(30, 30, 14);
            int chance = m.asteroidChance;
            int max = m.asteroidMax;
            m.asteroidProb = 0;
            m.maxAsteroidDensity = 0;
            m.asteroidConcentratorRadius = 0;
            m.asteroidConcentratorProb = -1;
            m.tuneAsteroids(30, 30, 14);
            com.fulcrologic.jxpilot.server.world.ModeParams b = new com.fulcrologic.jxpilot.server.world.ModeParams();
            b.asteroidProb = 1f;
            b.maxAsteroidDensity = 0.5f;
            b.tuneAsteroids(30, 40, 14);
            assertAll(
                () -> assertEquals((int) (1.0 / (1e-5f * 30 * 30 * 14)), chance, "chance"),
                () -> assertEquals(1, max, "0.09 asteroids: at least 1"),
                () -> assertEquals(0, m.asteroidChance, "no prob: 0"),
                () -> assertEquals(0, m.asteroidMax, "no density: 0"),
                () -> assertEquals(1, m.asteroidConcentratorRadius, "radius at least 1"),
                () -> assertEquals(0f, m.asteroidConcentratorProb, "prob at least 0"),
                () -> assertEquals(1, b.asteroidChance, "a huge prob: 1"),
                () -> assertEquals(600, b.asteroidMax, "0.5 * 1200"),
                () -> assertEquals(50, Math.min(50, (int) StrictMath.hypot(30, 40)), "(diagonal 50)"));
        }

        @Test
        @DisplayName("the asteroid list keeps creation order; index of a missing handle is -1; removing a missing one fails")
        void list() {
            Simulation s = AsteroidsTest.sim("");
            ModeState m = s.world.modes;
            m.addAsteroid(5);
            m.addAsteroid(9);
            m.addAsteroid(7);
            boolean removed = m.removeAsteroid(9);
            boolean missing = m.removeAsteroid(42);
            assertAll(
                () -> assertTrue(removed, "removed"),
                () -> assertFalse(missing, "not there"),
                () -> assertEquals(1, m.asteroidIndex(7), "7 moved up"),
                () -> assertEquals(0, m.asteroidIndex(5), "5 first"),
                () -> assertEquals(-1, m.asteroidIndex(9), "9 gone"),
                () -> assertEquals(2, m.asteroidCount, "two left"));
        }
    }
}
