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
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ModeFx.raceSim;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.ship;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.gameEvents;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Race (collision.c checkpoints, Player_pass_checkpoint; player.c race status, Race_game_over)")
class RaceTest {
    static final double EPS = 1e-9;
    /** Checkpoint A's block corner in pixels (C measures to World.check * BLOCK_SZ). */
    static final int AX = 10 * 35;
    static final int AY = 5 * 35;

    static List<String> timing(World w, long from) {
        List<String> out = new ArrayList<>();
        for (long q = from; q < w.events.seq(); q++) {
            int i = Events.index(q);
            if (w.events.kind[i] == Events.TIMING) {
                out.add(w.events.a[i] + ":" + w.events.b[i] + ":" + w.events.c[i]);
            }
        }
        return out;
    }

    @Nested
    @DisplayName("formatting")
    class Format {
        @Test
        @DisplayName("C %.2f of a float: exact half-even rounding of the binary value")
        void fixed2() {
            StringBuilder a = new StringBuilder();
            Race.appendFixed2(1.005f, a);
            StringBuilder b = new StringBuilder();
            Race.appendFixed2(0.125f, b);
            StringBuilder c = new StringBuilder();
            Race.appendFixed2(0.375f, c);
            StringBuilder d = new StringBuilder();
            Race.appendSeconds(d, 100, 14);
            StringBuilder e = new StringBuilder();
            Race.appendFixed2(-2.5f, e);
            StringBuilder f = new StringBuilder();
            Race.appendFixed2(12f, f);
            assertAll(
                () -> assertEquals("1.00", a.toString(), "1.005f is 1.00499999523: rounds down"),
                () -> assertEquals("0.12", b.toString(), "an exact tie rounds to even"),
                () -> assertEquals("0.38", c.toString(), "the other exact tie rounds to even"),
                () -> assertEquals("7.14", d.toString(), "100 frames at 14 fps"),
                () -> assertEquals("-2.50", e.toString(), "negative"),
                () -> assertEquals("12.00", f.toString(), "whole"));
        }
    }

    @Nested
    @DisplayName("nearCheckpoint / playerFrame")
    class Frame {
        @Test
        @DisplayName("within checkpointRadius blocks of the checkpoint's block corner, strictly")
        void near() {
            Simulation s = raceSim("checkpointRadius: 2\n");
            assertAll(
                () -> assertTrue(s.race.nearCheckpoint(AX + 69, AY, 0), "69 px < 70"),
                () -> assertFalse(s.race.nearCheckpoint(AX + 70, AY, 0), "70 px is not < 70"),
                () -> assertTrue(s.race.nearCheckpoint(AX, AY - 69, 0), "any direction"),
                () -> assertFalse(s.race.nearCheckpoint(AX + 17, AY + 17 + 60, 1), "checkpoint B is elsewhere"));
        }

        @Test
        @DisplayName("racing ships count frames after the first checkpoint and pass the next one when near and alive")
        void frames() {
            Simulation s = raceSim("");
            World w = s.world;
            Player p = ship(s, "P", 0, AX, AY);
            s.race.playerFrame(p);
            int t0 = p.time;
            int round0 = p.round;
            p.initPixels(AX + 400, AY);
            s.race.playerFrame(p);
            int t1 = p.time;
            p.status |= Obj.PAUSE;
            s.race.playerFrame(p);
            int tPaused = p.time;
            p.status &= ~Obj.PAUSE;
            p.initPixels(AX + 17, 1 * 35);
            p.status |= Obj.KILLED;
            s.race.playerFrame(p);
            int checkKilled = p.check;
            assertAll(
                () -> assertEquals(0, t0, "no time before the first lap starts"),
                () -> assertEquals(1, round0, "passing A starts lap 1"),
                () -> assertEquals(1, p.round, "(still lap 1)"),
                () -> assertEquals(1, t1, "then each frame counts"),
                () -> assertEquals(1, tPaused, "paused ships do not count"),
                () -> assertEquals(2, p.time, "a killed ship still counts its frame"),
                () -> assertEquals(1, checkKilled, "but does not pass a checkpoint"));
        }

        @Test
        @DisplayName("nothing outside race mode or in a ball race")
        void off() {
            Simulation s = raceSim("ballrace: yes\n");
            Player p = ship(s, "P", 0, AX, AY);
            s.race.playerFrame(p);
            Simulation plain = TestWorldsBridge.plain();
            Player q = ship(plain, "Q", 0, 100, 100);
            q.round = 3;
            plain.race.playerFrame(q);
            assertAll(
                () -> assertEquals(0, p.round, "a ball race: ships do not pass checkpoints themselves"),
                () -> assertEquals(0, q.time, "not a race: no counting"));
        }
    }

    /** A plain non-race simulation. */
    static final class TestWorldsBridge {
        static Simulation plain() {
            return SimFixture.sim(SimFixture.FPS14, 20, 10, "___");
        }
    }

    @Nested
    @DisplayName("passCheckpoint (Player_pass_checkpoint)")
    class Pass {
        @Test
        @DisplayName("lap start, lap completion with best lap, finish after raceLaps; check wraps; events")
        void laps() {
            Simulation s = raceSim("raceLaps: 2\n");
            World w = s.world;
            Player p = ship(s, "P", 0, 100, 100);
            p.dir = 17;
            long from = w.events.seq();
            s.race.passCheckpoint(p);
            List<String> start = messages(w, from);
            int afterStartCheck = p.check;
            s.race.passCheckpoint(p);
            int wrapped = p.check;
            p.time = 140;
            long from2 = w.events.seq();
            s.race.passCheckpoint(p);
            List<String> lap1 = messages(w, from2);
            int best1 = p.bestLap;
            s.race.passCheckpoint(p);
            p.time = 280 + 70;
            long from3 = w.events.seq();
            s.race.passCheckpoint(p);
            List<String> fin = messages(w, from3);
            assertAll(
                () -> assertEquals(List.of("P starts lap 1 of 2"), start, "lap 1 starts"),
                () -> assertEquals(1, afterStartCheck, "next: B"),
                () -> assertEquals(0, wrapped, "after the last checkpoint: A again"),
                () -> assertEquals(List.of("P completes lap 1 in 10.00s. Personal race best lap time: 10.00s."), lap1,
                    "the first completed lap (round 2) is the first best lap"),
                () -> assertEquals(140, best1, "best lap 140 frames"),
                () -> assertEquals(List.of("P finished the race. Last lap time: 15.00s. Personal race best lap time: 10.00s."),
                    fin, "after raceLaps laps the ship finishes; a slower lap keeps the best"),
                () -> assertEquals(Obj.GAME_OVER | Obj.FINISH, p.status & (Obj.GAME_OVER | Obj.FINISH), "GAME_OVER|FINISH"),
                () -> assertEquals('D', p.mychar, "'D'"),
                () -> assertEquals(3, p.round, "round 3 = finished 2 laps"),
                () -> assertEquals(17, p.lastCheckDir, "the heading is remembered"),
                () -> assertTrue(p.timingDirty, "a TIMING update is due"),
                () -> assertEquals("7:" + p.id + ":1", gameEvents(w, from3).get(0), "CHECKPOINT(player, next check)"));
        }

        @Test
        @DisplayName("best lap: only a faster lap after the first, never at time 0; in a ball race the finisher's balls lose their owner")
        void bestAndBallrace() {
            Simulation s = raceSim("raceLaps: 1\nballrace: yes\n");
            World w = s.world;
            Player p = ship(s, "P", 0, 100, 100);
            p.round = 1;
            p.check = 0;
            p.time = 0;
            s.race.passCheckpoint(p);
            int atZero = p.bestLap;
            int h = SimFixture.obj(w, Obj.BALL, Obj.NO_ID, 300, 300, 0, 0, 50, 100);
            w.modes.ballOwner[h] = p.id;
            p.round = 1;
            p.check = 0;
            p.time = 50;
            p.lastLap = 10;
            p.bestLap = 30;
            p.status &= ~(Obj.GAME_OVER | Obj.FINISH);
            s.race.passCheckpoint(p);
            assertAll(
                () -> assertEquals(0, atZero, "a lap at time 0 is not a best lap"),
                () -> assertEquals(30, p.bestLap, "a slower lap (40) does not replace 30"),
                () -> assertEquals(40, p.lastLapTime, "but is the last lap"),
                () -> assertEquals(Obj.NO_ID, w.modes.ballOwner[h], "ball race: the finisher's balls are free again"));
        }
    }

    @Nested
    @DisplayName("respawn (Go_home / Transport_to_home in race mode)")
    class Respawn {
        @Test
        @DisplayName("after the first checkpoint a ship comes back at the last checkpoint passed")
        void atCheckpoint() {
            Simulation s = raceSim("");
            World w = s.world;
            Player p = ship(s, "P", 0, 100, 100);
            int before = s.race.respawnCheck(p, true);
            p.round = 1;
            p.check = 1;
            int lastA = s.race.respawnCheck(p, true);
            p.check = 0;
            int lastB = s.race.respawnCheck(p, false);
            p.status |= Obj.GAME_OVER;
            int over = s.race.respawnCheck(p, true);
            int overTransport = s.race.respawnCheck(p, false);
            p.status &= ~Obj.GAME_OVER;
            p.check = 1;
            p.lastCheckDir = 40;
            s.ships.goHome(p);
            assertAll(
                () -> assertEquals(-1, before, "before the first checkpoint: the base"),
                () -> assertEquals(0, lastA, "next B: back at A"),
                () -> assertEquals(1, lastB, "next A: back at B (the last)"),
                () -> assertEquals(-1, over, "Go_home of a GAME_OVER ship: its base"),
                () -> assertEquals(1, overTransport, "Transport_to_home ignores GAME_OVER"),
                () -> assertEquals(10.5 * 35, p.px, 1, "at checkpoint A's centre (x)"),
                () -> assertEquals(5.5 * 35, p.py, 1, "at checkpoint A's centre (y)"),
                () -> assertTrue(Math.abs(p.vx) <= 0.05 && Math.abs(p.vy) <= 0.05, "with a tiny random drift"),
                () -> assertTrue(Math.abs(p.dir - 40) <= 8, "facing as it passed, +-RES/16"),
                () -> assertTrue(p.velocity <= 0.08, "velocity = |drift|"));
        }

        @Test
        @DisplayName("Transport_to_home aims at the last checkpoint")
        void transport() {
            Simulation s = raceSim("");
            Player p = ship(s, "P", 0, 10.5 * 35, 5.5 * 35 + 300);
            p.round = 1;
            p.check = 1;
            p.count = 10;
            s.ships.transportToHome(p);
            float m = 2 / 10.5f;
            assertAll(
                () -> assertEquals(0.5f * m, p.vx, 1e-6, "toward A's centre (x 367.5 from 367)"),
                () -> assertEquals((192.5f - 492) * m, p.vy, 1e-4, "toward A's centre (y)"));
        }
    }

    @Nested
    @DisplayName("gameStatus / gameOver (Compute_game_status race part, Race_game_over)")
    class Status {
        @Test
        @DisplayName("finishers this frame share (10+2*active)>>pos points; without limited lives the race then ends")
        void finishScoring() {
            Simulation s = raceSim("");
            World w = s.world;
            Player a = ship(s, "A", 0, 100, 100);
            Player b = ship(s, "B", 0, 200, 100);
            Player c = ship(s, "C", 0, 300, 100);
            a.status |= Obj.GAME_OVER | Obj.FINISH;
            b.status |= Obj.GAME_OVER | Obj.FINISH;
            a.bestLap = 50;
            b.bestLap = 40;
            long from = w.events.seq();
            s.race.gameStatus();
            List<String> m = messages(w, from);
            int pts = ((10 + 2 * 3) >> 0) + ((10 + 2 * 3) >> 1);
            float each = pts / 2;
            assertAll(
                () -> assertEquals("A finishes jointly in position 1 scoring 12.00 points.", m.get(0), "joint finish"),
                () -> assertEquals("B finishes jointly in position 1 scoring 12.00 points.", m.get(1), "both"),
                () -> assertTrue(m.contains("B had the best lap time of 2.86s"), "the best lap bonus at race end"),
                () -> assertEquals(each + 5 + 3, b.score, EPS, "B: 12 + best lap 5 + active 3"),
                () -> assertEquals(each, a.score, EPS, "A: 12"),
                () -> assertEquals(0, a.status & (Obj.RACE_OVER | Obj.FINISH | Obj.GAME_OVER), "everybody reset"),
                () -> assertEquals(0, c.round, "race progress cleared"));
        }

        @Test
        @DisplayName("limited lives: the race waits while two race; a lone survivor ends it once someone finished")
        void limitedLives() {
            Simulation s = raceSim("limitedLives: yes\nworldLives: 3\n");
            World w = s.world;
            Player a = ship(s, "A", 0, 100, 100);
            Player b = ship(s, "B", 0, 200, 100);
            Player c = ship(s, "C", 0, 300, 100);
            a.status |= Obj.GAME_OVER | Obj.RACE_OVER;
            b.round = 1;
            long from = w.events.seq();
            s.race.gameStatus();
            List<String> twoRacing = messages(w, from);
            c.status |= Obj.GAME_OVER;
            s.race.gameStatus();
            assertAll(
                () -> assertEquals(List.of(), twoRacing, "two ships racing: nothing happens"),
                () -> assertTrue(messages(w, from).contains(
                    "No-one even managed to complete one lap, you should be ashamed of yourselves."),
                    "one left on lap 1 after someone finished: the race ends (nobody had a best lap)"),
                () -> assertEquals(0, b.round, "and resets"));
        }

        @Test
        @DisplayName("the timer ends a race; home bases go to the fastest racers in base order")
        void timerAndBases() {
            Simulation s = raceSim("maxRoundTime: 5\n");
            World w = s.world;
            Player a = ship(s, "A", 0, 100, 100);
            Player b = ship(s, "B", 0, 200, 100);
            a.bestLap = 90;
            b.bestLap = 30;
            int[] order = w.map.baseOrder();
            a.homeBase = order[0];
            b.homeBase = order[1];
            w.roundTime = 0;
            long from = w.events.seq();
            s.race.gameStatus();
            assertAll(
                () -> assertEquals("Timer expired. Race ends now.", messages(w, from).get(0), "timer message"),
                () -> assertEquals(order[0], b.homeBase, "the fastest gets the first start position"),
                () -> assertEquals(order[1], a.homeBase, "the slower the next"));
        }

        @Test
        @DisplayName("nothing to judge without active or waiting players; paused players are left out")
        void idle() {
            Simulation s = raceSim("");
            World w = s.world;
            Player a = ship(s, "A", 0, 100, 100);
            a.status |= Obj.PAUSE;
            long from = w.events.seq();
            s.race.gameStatus();
            assertEquals(List.of(), messages(w, from), "only a paused player: nothing");
        }
    }

    @Nested
    @DisplayName("pause (event.c Pause_player off in race mode)")
    class Pause {
        @Test
        @DisplayName("unpausing respawns at the last checkpoint passed, then the race progress starts over")
        void unpause() {
            Simulation s = raceSim("");
            Player p = ship(s, "P", 0, 100, 100);
            p.status |= Obj.PAUSE;
            p.status &= ~Obj.PLAYING;
            p.count = 0;
            p.round = 2;
            p.check = 1;
            p.time = 50;
            p.bestLap = 20;
            s.ships.pause(p, false);
            assertAll(
                () -> assertEquals(10.5 * 35, p.px, 1, "Go_home ran first: at checkpoint A"),
                () -> assertEquals(0, p.round + p.check + p.time + p.bestLap, "then progress cleared"),
                () -> assertEquals(0, p.count, "no start delay"),
                () -> assertTrue((p.status & Obj.PLAYING) != 0, "playing"));
        }
    }

    @Nested
    @DisplayName("round integration (Rounds, Simulation)")
    class Integration {
        @Test
        @DisplayName("race wording, per-player reset with RECOVERY_DELAY, and TIMING events for changed progress")
        void wording() {
            Simulation s = raceSim("roundDelay: 2\n");
            World w = s.world;
            Player p = ship(s, "P", 0, 100, 100);
            p.round = 2;
            p.check = 1;
            p.time = 99;
            long from = w.events.seq();
            s.rounds.resetAllPlayers();
            List<String> m = messages(w, from);
            long from2 = w.events.seq();
            s.emitScores();
            w.roundDelay = 1;
            long from3 = w.events.seq();
            s.rounds.computeGameStatus();
            assertAll(
                () -> assertEquals(List.of("Delaying 2 seconds until start of next race."), m, "next race"),
                () -> assertEquals(0, p.round + p.check + p.time, "progress cleared"),
                () -> assertEquals(3 * 14, p.count, "RECOVERY_DELAY before starting"),
                () -> assertEquals(List.of(p.id + ":0:0"), timing(w, from2), "TIMING(id, check, round)"),
                () -> assertEquals("Race starts now.", messages(w, from3).get(0), "race start"));
        }
    }
}
