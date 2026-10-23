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

import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Target;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ShipFx.C_FRAMES;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The less common branches of {@link Rounds}: team timer verdicts, robots, several best players, resets. */
@DisplayName("Rounds: branches")
class RoundsBranchTest {
    /** Team play, two teams of two bases; team 1 owns a treasure when {@code treasure}. */
    static Simulation teams(String extra, boolean treasure) {
        return TestWorlds.sim(C_FRAMES + "teamPlay: yes\n" + extra, "xxxxxxxxxxxx",
            treasure ? "x1*1    2 2x" : "x1 1    2 2x", "x  !       x", "xxxxxxxxxxxx");
    }

    static Simulation individual(String extra) {
        return TestWorlds.sim(C_FRAMES + "limitedLives: yes\n" + (extra.contains("worldLives") ? "" : "worldLives: 1\n") + extra, "xxxxxxxxxx",
            "x _ _ _ _x", "xxxxxxxxxx");
    }

    @Nested
    @DisplayName("team round timer")
    class TeamTimer {
        void expire(World w) {
            w.params.maxRoundTime = 1;
            w.roundTime = 1;
        }

        @Test
        @DisplayName("two alive teams: nothing until the timer, then the higher summed score wins")
        void byScore() {
            Simulation s = teams("", false);
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 1);
            Player b = TestWorlds.join(s, "b", 2);
            a.score = 5;
            b.score = 3;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            List<String> before = messages(w, seq);
            expire(w);
            s.rounds.computeGameStatus();
            List<String> msgs = messages(w, seq);
            assertAll(
                () -> assertEquals(List.of(), before, "two teams alive and no timer: the round goes on"),
                () -> assertEquals("Timer expired. Round ends now.", msgs.get(0), "the timer ends it"),
                () -> assertEquals(" < Team 1 has won the game by destroying 0 treasures, saving 0, and scoring 5.00 points! >",
                    msgs.get(1), "by summed score"));
        }

        @Test
        @DisplayName("equal scores are a draw between the teams")
        void draw() {
            Simulation s = teams("", false);
            World w = s.world;
            TestWorlds.join(s, "a", 1);
            TestWorlds.join(s, "b", 2);
            expire(w);
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            assertEquals(" < We have a draw between teams 1, 2! >", messages(w, seq).get(1), "a draw names the teams");
        }

        @Test
        @DisplayName("a team that still holds more treasures wins first")
        void byTreasure() {
            Simulation s = teams("", true);
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 1);
            Player b = TestWorlds.join(s, "b", 2);
            b.score = 50;
            expire(w);
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            assertAll(
                () -> assertEquals(1, w.map.treasures().get(0).team(), "(fixture: team 1's treasure)"),
                () -> assertEquals(" < Team 1 has won the game by destroying 0 treasures and successfully defending 1! >",
                    messages(w, seq).get(1), "saved treasures beat score"),
                () -> assertEquals(0.0, a.score, "(no Winner bonus: a killed nobody)"));
        }

        @Test
        @DisplayName("paused players and players without a team are ignored; a lone alive team without deaths goes on")
        void ignored() {
            Simulation s = teams("", false);
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 1);
            Player b = TestWorlds.join(s, "b", 2);
            b.status |= Obj.PAUSE;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            b.status &= ~Obj.PAUSE;
            b.team = -1;
            s.rounds.computeGameStatus();
            assertEquals(List.of(), messages(w, seq), "team 1 alone and nobody out: no verdict");
        }
    }

    @Nested
    @DisplayName("individual verdicts and bonuses")
    class Verdicts {
        @Test
        @DisplayName("robots alive and the humans out: the robots win, each non-best robot gets the Winner bonus")
        void robotsWin() {
            Simulation s = individual("");
            World w = s.world;
            Player h = TestWorlds.join(s, "h", 0);
            Player r1 = TestWorlds.join(s, "r1", 0);
            Player r2 = TestWorlds.join(s, "r2", 0);
            r1.robot = true;
            r2.robot = true;
            r1.kills = 2;
            r2.kills = 1;
            RoundsTest.killOut(s, h);
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            List<String> msgs = messages(w, seq);
            assertAll(
                () -> assertEquals(" < The robots have won the game! >", msgs.get(0), "robots win"),
                () -> assertEquals(2 * Scoring.rate(0, 0), r1.score, 1e-9, "r1 is best (Deadliest)"),
                () -> assertEquals(1 * Scoring.rate(0, 0), r2.score, 1e-9, "r2 gets the Winner bonus"),
                () -> assertEquals(0.0, h.score, "the human gets nothing"));
        }

        @Test
        @DisplayName("several best players share: (int)(ratio * (Rate + n) / n) each, long name lists split at 80 chars")
        void deadlyPlayers() {
            Simulation s = individual("worldLives: 3\n");
            World w = s.world;
            Player a = TestWorlds.join(s, "Abcdefghijklmnop", 0);
            Player b = TestWorlds.join(s, "Bbcdefghijklmnop", 0);
            Player c = TestWorlds.join(s, "Cbcdefghijklmnop", 0);
            Player d = TestWorlds.join(s, "Dbcdefghijklmnop", 0);
            for (Player p : List.of(a, b, c, d)) {
                p.kills = 1;
            }
            long seq = w.events.seq();
            s.rounds.individualGameOver(-1);
            List<String> msgs = messages(w, seq);
            float share = ((float) Scoring.rate(0, 0) + 4) / 4;
            int expected = (int) (1.0 * share);
            assertAll(
                () -> assertEquals(" < We have a draw! >", msgs.get(0), "a draw"),
                () -> assertEquals("Abcdefghijklmnop, Bbcdefghijklmnop, Cbcdefghijklmnop and ", msgs.get(1),
                    "the names are flushed before the next one would reach 80 chars"),
                () -> assertEquals("Dbcdefghijklmnop", msgs.get(2), "the rest is flushed when the tail would not fit"),
                () -> assertEquals(" are the Deadly Players with kill ratios of 1/0.", msgs.get(3),
                    "then the ratio of the first"),
                () -> assertEquals(4, msgs.size(), "no other message"),
                () -> assertEquals(expected, a.score, 1e-9, "each gets (int)(ratio * score)"),
                () -> assertEquals(expected, d.score, 1e-9, "d too"));
        }

        @Test
        @DisplayName("two best players with short names: 'a and b are the Deadly Players'")
        void twoDeadly() {
            Simulation s = individual("");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            a.kills = 1;
            b.kills = 1;
            long seq = w.events.seq();
            s.rounds.individualGameOver(-1);
            assertEquals("a and b are the Deadly Players with kill ratios of 1/0.", messages(w, seq).get(1),
                "names joined with ' and '");
        }

        @Test
        @DisplayName("the average divides by every player but sums only those not paused out; nobody eligible: no message")
        void average() {
            Simulation s = individual("worldLives: 3\n");
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            Player c = TestWorlds.join(s, "c", 0);
            a.score = 30;
            a.kills = 1;
            b.score = 90;
            b.status |= Obj.PAUSE;
            b.count = 0;
            c.status |= Obj.PAUSE;
            c.count = 5;
            s.rounds.endOfRoundValues();
            s.rounds.individualBonus(a);
            Simulation t = individual("");
            Player x = TestWorlds.join(t, "x", 0);
            x.status |= Obj.PAUSE;
            x.count = 0;
            t.rounds.endOfRoundValues();
            long seq = t.world.events.seq();
            t.rounds.bestPlayerBonus();
            assertAll(
                () -> assertEquals(30 + Scoring.rate(30, 10), a.score, 1e-6,
                    "average (30 + 0) / 3: b's 90 is not summed, c's 0 is"),
                () -> assertEquals(List.of(), messages(t.world, seq), "nobody eligible: no Deadly message"),
                () -> assertEquals(0.0, x.score, "and no bonus"));
        }
    }

    static boolean debrisOnlyNear(World w, Player pl) {
        Objects o = w.objects;
        if (o.count() == 0) {
            return false;
        }
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if (Math.abs(o.px[h] - pl.px) > 70 || Math.abs(o.py[h] - pl.py) > 70) {
                return false;
            }
        }
        return true;
    }

    @Nested
    @DisplayName("reset details")
    class Reset {
        @Test
        @DisplayName("a paused ship is only death-reset (no explosion, no death), keeps 'P'; team targets are repaired")
        void pausedAndTargets() {
            Simulation s = teams("", false);
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 1);
            Player b = TestWorlds.join(s, "b", 2);
            s.ships.pause(b, true);
            b.count = 0;
            Target t = w.map.targets().get(0);
            w.targetDeadTime[0] = 50;
            w.map.setBlock(t.bx(), t.by(), BlockType.SPACE);
            int burstsBefore = (int) w.events.burstSeq();
            s.rounds.resetAllPlayers();
            assertAll(
                () -> assertEquals(1, w.events.burstSeq() - burstsBefore, "only a explodes"),
                () -> assertEquals('P', b.mychar, "the paused player keeps 'P'"),
                () -> assertEquals(Obj.PAUSE, b.status & (Obj.PAUSE | Obj.PLAYING), "still paused, not killed"),
                () -> assertEquals(3 * 60, b.count, "but death-reset (respawn count at least 3*FPS)"),
                () -> assertTrue(debrisOnlyNear(w, a), "the explosion debris is a's, none is b's"),
                () -> assertEquals(w.params.defHave & ~com.fulcrologic.jxpilot.server.world.Has.BALL, a.have,
                    "abilities: the defaults without a ball"),
                () -> assertEquals(BlockType.TARGET, w.map.block(t.bx(), t.by()), "the dead target is back"),
                () -> assertEquals(0, w.targetDeadTime[0], "alive"),
                () -> assertEquals(0, a.status & Obj.PLAYING, "(a was killed)"));
        }

        @Test
        @DisplayName("explosives lose their mass, other objects only their life; ball-less players lose HAS_BALL")
        void objects() {
            Simulation s = individual("");
            World w = s.world;
            TestWorlds.join(s, "a", 0);
            Objects o = w.objects;
            int cs = o.alloc();
            o.type[cs] = Obj.CANNON_SHOT;
            o.mass[cs] = 0.4f;
            o.life[cs] = 9;
            int sh = o.alloc();
            o.type[sh] = Obj.SHOT;
            o.mass[sh] = 0.1f;
            o.life[sh] = 9;
            int ball = o.alloc();
            o.type[ball] = Obj.BALL;
            o.life[ball] = 9;
            s.rounds.resetAllPlayers();
            assertAll(
                () -> assertEquals(0.0f, o.mass[cs], "cannon shots lose their mass"),
                () -> assertEquals(0.0, o.life[cs], "and life"),
                () -> assertEquals(0.1f, o.mass[sh], "shots keep their mass"),
                () -> assertEquals(0.0, o.life[sh], "but die"),
                () -> assertEquals(9.0, o.life[ball], "balls are not touched here"));
        }

        @Test
        @DisplayName("round delay bookkeeping: round_delay_send counts down; a delayed start sets the round timer")
        void delayBookkeeping() {
            Simulation s = individual("maxRoundTime: 3\n");
            World w = s.world;
            TestWorlds.join(s, "a", 0);
            w.roundDelaySend = 2;
            w.roundDelay = 1;
            w.roundTime = -1;
            s.rounds.computeGameStatus();
            int send = w.roundDelaySend;
            int timer = w.roundTime;
            w.roundDelaySend = 0;
            s.rounds.computeGameStatus();
            int sendZero = w.roundDelaySend;
            w.roundDelaySend = 5;
            s.rounds.resetAllPlayers();
            int sendNoDelay = w.roundDelaySend;
            w.roundDelaySend = 0;
            s.rounds.resetAllPlayers();
            int sendZeroReset = w.roundDelaySend;
            w.roundDelaySend = 1;
            w.params.roundDelaySeconds = 1;
            s.rounds.resetAllPlayers();
            assertAll(
                () -> assertEquals(1, send, "round_delay_send counts down in Compute_game_status"),
                () -> assertEquals(3 * 60 - 1, timer, "the round starts: maxRoundTime * FPS, then one frame passes"),
                () -> assertEquals(0, sendZero, "round_delay_send stops at 0"),
                () -> assertEquals(4, sendNoDelay, "a reset without delay counts it down once"),
                () -> assertEquals(0, sendZeroReset, "and stops at 0"),
                () -> assertEquals(60 + 60, w.roundDelaySend, "a delayed reset sends the delay plus one second"));
        }
    }

    @Test
    @DisplayName("the world starts the round timer at maxRoundTime * FPS, or -1 without one")
    void initialTimer() {
        assertAll(
            () -> assertEquals(3 * 60, individual("maxRoundTime: 3\n").world.roundTime, "3 s at 60 fps"),
            () -> assertEquals(-1, individual("").world.roundTime, "no timer"));
    }

    @Nested
    @DisplayName("more verdict details")
    class More {
        @Test
        @DisplayName("an expired team timer stays expired until a second team plays, then ends the round")
        void timerStaysExpired() {
            Simulation s = teams("", false);
            World w = s.world;
            TestWorlds.join(s, "a", 1);
            w.params.maxRoundTime = 1;
            w.roundTime = 1;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            s.rounds.computeGameStatus();
            List<String> alone = messages(w, seq);
            TestWorlds.join(s, "b", 2);
            seq = w.events.seq();
            s.rounds.computeGameStatus();
            List<String> msgs = messages(w, seq);
            assertAll(
                () -> assertEquals(List.of(), alone, "one team: no verdict"),
                () -> assertEquals("Timer expired. Round ends now.", msgs.get(0), "the second team ends it at once"));
        }

        @Test
        @DisplayName("a team with a dead member and then an alive one is alive, not dead")
        void deadThenAlive() {
            Simulation s = teams("", false);
            World w = s.world;
            Player q = TestWorlds.join(s, "q", 1);
            TestWorlds.join(s, "a", 1);
            q.status |= Obj.GAME_OVER;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            assertEquals(List.of(), messages(w, seq), "one alive team and no dead team: no verdict");
        }

        @Test
        @DisplayName("consecutive team draws announce the same text (the reason buffer is cleared)")
        void twoDraws() {
            Simulation s = teams("", false);
            World w = s.world;
            TestWorlds.join(s, "a", 1);
            TestWorlds.join(s, "b", 2);
            w.params.maxRoundTime = 1;
            w.roundTime = 1;
            s.rounds.computeGameStatus();
            w.roundTime = 1;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            assertEquals(" < We have a draw between teams 1, 2! >", messages(w, seq).get(1), "same text");
        }

        @Test
        @DisplayName("appendFixed2 prints C %.2f for the scores used here")
        void fixed2() {
            StringBuilder a = new StringBuilder();
            Rounds.appendFixed2(a, 0.0);
            StringBuilder b = new StringBuilder();
            Rounds.appendFixed2(b, 1.1);
            StringBuilder c = new StringBuilder();
            Rounds.appendFixed2(c, -2.05);
            assertAll(
                () -> assertEquals("0.00", a.toString(), "zero has no sign"),
                () -> assertEquals("1.10", b.toString(), "ten hundredths"),
                () -> assertEquals("-2.05", c.toString(), "negative, padded"));
        }

        @Test
        @DisplayName("kill ratios divide by deaths + 1: 2/1 ties 1/0; the Winner bonus uses that ratio too")
        void ratios() {
            Simulation s = individual("worldLives: 3\n");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            Player c = TestWorlds.join(s, "c", 0);
            a.kills = 2;
            a.deaths = 1;
            b.kills = 1;
            c.kills = 3;
            c.deaths = 5;
            long seq = w.events.seq();
            s.rounds.endOfRoundValues();
            s.rounds.bestPlayerBonus();
            double before = c.score;
            s.rounds.individualBonus(c);
            assertAll(
                () -> assertEquals(List.of("a and b are the Deadly Players with kill ratios of 2/1."), messages(w, seq),
                    "2/(1+1) = 1/(0+1)"),
                () -> assertEquals(before + 0.5 * Scoring.rate(0, 0), c.score, 1e-6,
                    "3/(5+1) = 0.5 of Rate(score, average 0)"));
        }

        @Test
        @DisplayName("several Deadly announcements in a row start from empty name lists")
        void twoAnnouncements() {
            Simulation s = individual("worldLives: 3\n");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            a.kills = 1;
            b.kills = 1;
            s.rounds.individualGameOver(-1);
            a.kills = 1;
            b.kills = 1;
            long seq = w.events.seq();
            s.rounds.individualGameOver(-1);
            assertEquals("a and b are the Deadly Players with kill ratios of 1/0.", messages(w, seq).get(1), "same text");
        }

        @Test
        @DisplayName("a name that brings the list to exactly 80 chars is moved to the next message")
        void exactly80() {
            Simulation s = individual("worldLives: 3\n");
            World w = s.world;
            Player[] ps = {
                TestWorlds.join(s, "Abcdefghijklmnop", 0), TestWorlds.join(s, "Bbcdefghijklmnop", 0),
                TestWorlds.join(s, "Cbcdefghijklmnop", 0), TestWorlds.join(s, "Dbcdefghijklmno", 0)};
            for (Player p : ps) {
                p.kills = 1;
            }
            long seq = w.events.seq();
            s.rounds.individualGameOver(-1);
            List<String> msgs = messages(w, seq);
            assertAll(
                () -> assertEquals("Abcdefghijklmnop, Bbcdefghijklmnop, Cbcdefghijklmnop and ", msgs.get(1),
                    "57 + 8 + 15 = 80: flushed"),
                () -> assertEquals("Dbcdefghijklmno are the Deadly Players with kill ratios of 1/0.", msgs.get(2),
                    "15 + 64 < 80: the tail follows the last name"));
        }

        @Test
        @DisplayName("the first player in the order can win and gets the Winner bonus when not the best")
        void firstWins() {
            Simulation s = individual("");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            a.kills = 1;
            RoundsTest.killOut(s, b);
            b.kills = 5;
            int bDeaths = b.deaths;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            assertAll(
                () -> assertEquals(2, bDeaths, "(fixture: 1 life = 2 deaths; b's ratio 5/3 is the best)"),
                () -> assertEquals(" < a has won the game! >", messages(w, seq).get(0), "a (order 0) wins"),
                () -> assertTrue(a.score > 0, "and gets the Winner bonus"));
        }

        @Test
        @DisplayName("team bonus: paused members whose count ran out and waiting members without score get none")
        void teamBonusExclusions() {
            Simulation s = TestWorlds.sim(C_FRAMES + "teamPlay: yes\n", "xxxxxxxxxxxxxx", "x1 1 1 1 1 2 x",
                "x            x", "xxxxxxxxxxxxxx");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 1);
            Player p = TestWorlds.join(s, "p", 1);
            Player q = TestWorlds.join(s, "q", 1);
            Player r = TestWorlds.join(s, "r", 1);
            Player u = TestWorlds.join(s, "u", 1);
            Player b = TestWorlds.join(s, "b", 2);
            u.kills = 1;
            u.count = 0;
            a.kills = 9;
            p.kills = 1;
            p.status |= Obj.PAUSE;
            p.count = 0;
            q.kills = 1;
            q.status |= Obj.GAME_OVER;
            q.mychar = 'W';
            r.kills = 1;
            r.status |= Obj.PAUSE;
            r.count = 5;
            b.status |= Obj.GAME_OVER;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            assertAll(
                () -> assertEquals(" < Team 1 has won the game by staying alive! >", messages(w, seq).get(0), "team 1"),
                () -> assertEquals(0.0, p.score, "paused, count ran out: no bonus"),
                () -> assertEquals(0.0, q.score, "waiting with no score: no bonus"),
                () -> assertTrue(r.score > 0, "paused with count left: bonus"),
                () -> assertTrue(u.score > 0, "playing (count 0, not paused): bonus"),
                () -> assertTrue(a.score > 0, "the Deadliest"));
        }

        @Test
        @DisplayName("a lone player out of lives is a draw; robots alone (no human out) play on")
        void loneAndRobots() {
            Simulation s = individual("");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            RoundsTest.killOut(s, a);
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            List<String> lone = messages(w, seq);
            Simulation t = individual("");
            Player r1 = TestWorlds.join(t, "r1", 0);
            Player r2 = TestWorlds.join(t, "r2", 0);
            r1.robot = true;
            r2.robot = true;
            long seq2 = t.world.events.seq();
            t.rounds.computeGameStatus();
            assertAll(
                () -> assertEquals(" < We have a draw! >", lone.get(0), "nobody alive among one active player"),
                () -> assertEquals(List.of(), messages(t.world, seq2), "two robots and no human: the round goes on"));
        }

        @Test
        @DisplayName("team 0 (not reserved for robots) is a team like the others: alive, scored, treasures, Game_Over")
        void teamZero() {
            String[] rows = {"xxxxxxxxxxxx", "x0*0    1 1x", "x          x", "xxxxxxxxxxxx"};
            Simulation s = TestWorlds.sim(C_FRAMES + "teamPlay: yes\nreserveRobotTeam: no\n", rows);
            World w = s.world;
            Player z = TestWorlds.join(s, "z", 0);
            Player o = TestWorlds.join(s, "o", 1);
            o.status |= Obj.GAME_OVER;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            List<String> alive = messages(w, seq);
            w.params.maxRoundTime = 1;
            w.roundTime = 1;
            seq = w.events.seq();
            s.rounds.computeGameStatus();
            List<String> timer = messages(w, seq);
            String[] plain = {"xxxxxxxxxxxx", "x0 0    1 1x", "x          x", "xxxxxxxxxxxx"};
            Simulation u = TestWorlds.sim(C_FRAMES + "teamPlay: yes\nreserveRobotTeam: no\n", plain);
            Player uz = TestWorlds.join(u, "z", 0);
            TestWorlds.join(u, "o", 1);
            uz.score = 5;
            u.world.params.maxRoundTime = 1;
            u.world.roundTime = 1;
            long seq3 = u.world.events.seq();
            u.rounds.computeGameStatus();
            List<String> scored = messages(u.world, seq3);
            uz.score = 7;
            long seq4 = u.world.events.seq();
            u.rounds.gameOver();
            assertAll(
                () -> assertEquals(" < Team 0 has won the game by staying alive! >", alive.get(0), "team 0 counts as alive"),
                () -> assertEquals(" < Team 0 has won the game by destroying 0 treasures and successfully defending 1! >",
                    timer.get(1), "team 0's treasure counts"),
                () -> assertEquals(" < Team 0 has won the game by destroying 0 treasures, saving 0, and scoring 5.00 points! >",
                    scored.get(1), "team 0's score counts"),
                () -> assertEquals("Best team (7 Pts): Team 0", messages(u.world, seq4).get(1), "and in Game_Over"));
        }

        @Test
        @DisplayName("Game_Over ties: the first best team / human is named, and no worst when it is the same")
        void gameOverTies() {
            Simulation s = teams("", false);
            World w = s.world;
            TestWorlds.join(s, "a", 1);
            TestWorlds.join(s, "b", 2);
            long seq = w.events.seq();
            s.rounds.gameOver();
            assertEquals(List.of("Game over...", "Best team (0 Pts): Team 1", "Best human player: a"), messages(w, seq),
                "equal scores: the first wins and is also the worst");
        }

        @Test
        @DisplayName("resetOnHuman in team play ends the round as a team draw (and counts it)")
        void resetOnHumanTeams() {
            Simulation s = teams("resetOnHuman: 1\nroundsToPlay: 3\n", false);
            World w = s.world;
            long seq = w.events.seq();
            TestWorlds.join(s, "a", 1);
            assertTrue(messages(w, seq).contains(" < Round 1 out of 3 completed. >"), "Team_game_over counts the round");
        }

        @Test
        @DisplayName("only the first player (or one during a delay) restarts the delay; no timer: -1")
        void enteredMessage() {
            Simulation s = individual("");
            World w = s.world;
            long seq = w.events.seq();
            TestWorlds.join(s, "a", 0);
            List<String> first = messages(w, seq);
            int timer = w.roundTime;
            seq = w.events.seq();
            TestWorlds.join(s, "b", 0);
            List<String> second = messages(w, seq);
            assertAll(
                () -> assertTrue(first.contains("Player entered. Delaying 0 seconds until next round."), "first player"),
                () -> assertEquals(-1, timer, "no maxRoundTime: no timer"),
                () -> assertFalse(second.stream().anyMatch(m -> m.startsWith("Player entered")), "second: nothing"));
        }
    }

    @Nested
    @DisplayName("Game_Over")
    class GameOver {
        @Test
        @DisplayName("robots are not best or worst humans; one team or one human gives no 'worst' line")
        void humansOnly() {
            Simulation s = teams("", false);
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 1);
            Player r = TestWorlds.join(s, "r", 2);
            r.robot = true;
            r.score = 99;
            a.score = -3.7;
            long seq = w.events.seq();
            s.rounds.gameOver();
            List<String> msgs = messages(w, seq);
            assertAll(
                () -> assertEquals(List.of("Game over...", "Best team (-3 Pts): Team 1", "Best human player: a"), msgs,
                    "only human team scores (truncated) and the only human"),
                () -> assertTrue((r.status & Obj.GAME_OVER) != 0, "robots are GAME_OVER too"),
                () -> assertFalse(w.gameDuration >= 0, "the game is over"));
        }
    }
}
