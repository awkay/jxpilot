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

import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ShipFx.events;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rounds: Compute_game_status, Individual_game_over / Team_game_over, the end-of-round bonuses,
 * Reset_all_players, Count_rounds, Game_Over (player.c, server.c) and the round delay gating of
 * Update_objects, Handle_keyboard and Check_collision.
 */
@DisplayName("Rounds")
class RoundsTest {
    /** Kill a player until it is out of lives (C Player_death_reset with limitedLives). */
    static void killOut(Simulation s, Player p) {
        while ((p.status & Obj.GAME_OVER) == 0) {
            s.ships.kill(p);
        }
    }

    static long[] noKeys(Simulation s) {
        return new long[s.world.players.length];
    }

    @Nested
    @DisplayName("individual limited lives (tournament.xp, 3 lives)")
    class Individual {
        @Test
        @DisplayName("the last ship with lives wins: messages, Deadliest bonus, then every player is reset")
        void lastAliveWins() {
            Simulation s = TestWorlds.shipped("tournament.xp", 3);
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            a.kills = 2;
            killOut(s, b);
            int bDeaths = b.deaths;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            List<String> msgs = messages(w, seq);
            assertAll(
                () -> assertEquals(4, bDeaths, "(fixture: 3 lives = 4 deaths to GAME_OVER)"),
                () -> assertEquals(" < a has won the game! >", msgs.get(0), "the winner is announced"),
                () -> assertEquals("a is the Deadliest Player with a kill ratio of 2/0.", msgs.get(1),
                    "the best kills/(deaths+1) ratio is announced"),
                () -> assertEquals(2, msgs.size(), "no delay message without roundDelay; the winner is the best player"),
                () -> assertEquals(2 * Scoring.rate(0, 0), a.score, 1e-9, "Deadliest: ratio * Rate(score, average)"),
                () -> assertEquals(0.0, b.score, "the loser gets no bonus"),
                () -> assertEquals(0, b.status & Obj.GAME_OVER, "GAME_OVER is cleared"),
                () -> assertEquals(3, a.life, "lives are restored for a"),
                () -> assertEquals(3, b.life, "and for b"),
                () -> assertEquals(0, a.kills + a.deaths + b.kills + b.deaths, "kills and deaths are reset"),
                () -> assertEquals(0, (a.status | b.status) & Obj.PLAYING, "reset (endOfRoundReset): every ship is killed"),
                () -> assertEquals(3 * w.params.fps, a.count, "and recovers home"),
                () -> assertTrue(w.objects.count() > 0, "(the explosions made debris)"),
                () -> assertTrue(allDead(w), "every shot, spark and debris dies at once"),
                () -> assertTrue(a.scoreDirty && b.scoreDirty, "every score is resent"));
        }

        @Test
        @DisplayName("nobody alive is a draw; without endOfRoundReset ships keep flying; status bits per mychar")
        void drawAndNoReset() {
            Simulation s = TestWorlds.shipped("tournament.xp", 3);
            World w = s.world;
            w.params.endOfRoundReset = false;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            killOut(s, a);
            killOut(s, b);
            int statusDead = Simulation.scoreStatus(a);
            char dead = a.mychar;
            a.status |= Obj.PLAYING;
            b.status |= Obj.PLAYING;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            assertAll(
                () -> assertEquals('D', dead, "out of lives is C mychar 'D'"),
                () -> assertEquals(Msg.Score.STATUS_DEAD | Msg.Score.STATUS_OUT_OF_LIVES, statusDead,
                    "SCORE status: dead and out of lives"),
                () -> assertEquals(List.of(" < We have a draw! >", "There is no Deadly Player"), messages(w, seq),
                    "a draw, and nobody killed anyone"),
                () -> assertEquals(Obj.PLAYING, a.status & (Obj.PLAYING | Obj.GAME_OVER), "no reset: a keeps flying"),
                () -> assertEquals(' ', a.mychar, "and plays again"));
        }

        @Test
        @DisplayName("while two ships have lives, or one plays alone, the round goes on")
        void goesOn() {
            Simulation s = TestWorlds.shipped("tournament.xp", 3);
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            s.ships.kill(b);
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            List<String> two = messages(w, seq);
            Simulation alone = TestWorlds.shipped("tournament.xp", 3);
            TestWorlds.join(alone, "solo", 0);
            long seq2 = alone.world.events.seq();
            alone.rounds.computeGameStatus();
            assertAll(
                () -> assertEquals(List.of(), two, "two players with lives: no round end"),
                () -> assertEquals(2, b.life, "b just lost a life"),
                () -> assertEquals(List.of(), messages(alone.world, seq2), "a lone player never wins a round"),
                () -> assertEquals(Obj.PLAYING, a.status & Obj.PLAYING, "a keeps playing"));
        }

        @Test
        @DisplayName("a joiner after someone lost a life waits ('W', WAITING) and gets no Winner bonus")
        void lateJoinerWaits() {
            Simulation s = TestWorlds.shipped("tournament.xp", 3);
            Player a = TestWorlds.join(s, "a", 0);
            s.ships.kill(a);
            Player late = TestWorlds.join(s, "late", 0);
            assertAll(
                () -> assertEquals('W', late.mychar, "C mychar 'W'"),
                () -> assertEquals(0, late.life, "no lives"),
                () -> assertEquals(Obj.GAME_OVER, late.status & Obj.GAME_OVER, "GAME_OVER"),
                () -> assertEquals(Msg.Score.STATUS_WAITING, Simulation.scoreStatus(late) & ~Msg.Score.STATUS_DEAD,
                    "SCORE status WAITING (not OUT_OF_LIVES)"));
        }
    }

    @Nested
    @DisplayName("team limited lives (blood-music2.xp, 3 lives)")
    class Team {
        @Test
        @DisplayName("the team that still has lives wins 'by staying alive'; its members get the Winner bonus")
        void teamWins() {
            Simulation s = TestWorlds.shipped("blood-music2.xp", 3);
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 2);
            Player a2 = TestWorlds.join(s, "a2", 2);
            Player b = TestWorlds.join(s, "b", 3);
            a.kills = 3;
            a2.kills = 1;
            killOut(s, b);
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            List<String> msgs = messages(w, seq);
            assertAll(
                () -> assertEquals(2, a.team, "(fixture: teams 2 and 3)"),
                () -> assertEquals(" < Team 2 has won the game by staying alive! >", msgs.get(0), "team 2 wins"),
                () -> assertEquals("a is the Deadliest Player with a kill ratio of 3/0.", msgs.get(1), "a is best"),
                () -> assertTrue(a2.score > 0, "a2 (winning team, not best) gets the Winner bonus"),
                () -> assertEquals(1 * Scoring.rate(0, 0), a2.score, 1e-9,
                    "kills/(deaths+1) * Rate(score, average), all scores 0 before"),
                () -> assertEquals(0, b.status & Obj.GAME_OVER, "the round is reset"));
        }

        @Test
        @DisplayName("when every team is out it is a draw 'as everyone died'; roundsToPlay counts it and ends the game")
        void drawAndGameOver() {
            Simulation s = TestWorlds.shipped("blood-music2.xp", 3);
            World w = s.world;
            w.params.roundsToPlay = 1;
            Player a = TestWorlds.join(s, "a", 2);
            Player b = TestWorlds.join(s, "b", 3);
            killOut(s, a);
            killOut(s, b);
            a.score = 5;
            b.score = -2;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            List<String> msgs = messages(w, seq);
            long seq2 = w.events.seq();
            s.tick(noKeys(s));
            assertAll(
                () -> assertEquals(" < We have a draw as everyone died! >", msgs.get(0), "a draw"),
                () -> assertTrue(msgs.contains(" < Round 1 out of 1 completed. >"), "the round is counted"),
                () -> assertTrue(msgs.contains("Game over..."), "the last round ends the game"),
                () -> assertTrue(msgs.contains("Best team (5 Pts): Team 2"), "the best team by summed int score"),
                () -> assertTrue(msgs.contains("Worst team (-2 Pts): Team 3"), "the worst team"),
                () -> assertTrue(msgs.contains("Best human player: a"), "the best human"),
                () -> assertTrue(msgs.contains("Worst human player: b"), "the worst human"),
                () -> assertEquals(Obj.GAME_OVER, a.status & Obj.GAME_OVER, "everyone is GAME_OVER after Game_Over"),
                () -> assertEquals(-1.0f, w.gameDuration, "and rounds are no longer judged"),
                () -> assertEquals(List.of(), messages(w, seq2), "so the next tick declares nothing"));
        }
    }

    @Nested
    @DisplayName("timer and delay")
    class Timing {
        @Test
        @DisplayName("maxRoundTime: the timer runs down per frame and its expiry ends the round as a draw")
        void timer() {
            Simulation s = TestWorlds.sim("framesPerSecond: 10\nmaxRoundTime: 1\ngravity: 0", "         ", " _  _    ");
            World w = s.world;
            TestWorlds.join(s, "a", 0);
            TestWorlds.join(s, "b", 0);
            int start = w.roundTime;
            long seq = w.events.seq();
            TestWorlds.run(s, 9, 0L);
            List<String> before = messages(w, seq);
            s.tick(noKeys(s));
            List<String> at = messages(w, seq);
            assertAll(
                () -> assertEquals(10, start, "maxRoundTime * FPS frames"),
                () -> assertEquals(List.of(), before, "nothing before it runs out"),
                () -> assertEquals("Timer expired. Round ends now.", at.get(0), "expiry is announced"),
                () -> assertEquals(" < We have a draw! >", at.get(1), "as a draw"),
                () -> assertEquals(10, w.roundTime, "and the timer restarts"));
        }

        @Test
        @DisplayName("roundDelay holds ships, collisions and most keys; it is announced, counts down and starts the round")
        void delay() {
            Simulation s = TestWorlds.sim("framesPerSecond: 10\nroundDelay: 1\ngravity: 0\nlimitedLives: yes\nworldLives: 1",
                "         ", " _  _    ");
            World w = s.world;
            Player a = TestWorlds.join(s, "a", 0);
            int afterJoin = w.roundDelay;
            Player b = TestWorlds.join(s, "b", 0);
            List<String> joinMsgs = messages(w, 0);
            a.vx = 3;
            int x0 = a.cx;
            long[] in = noKeys(s);
            in[a.slot] = ControlKey.THRUST.mask() | ControlKey.SHIELD.mask();
            in[b.slot] = ControlKey.PAUSE.mask();
            a.used |= Has.SHIELD;
            s.tick(in);
            int thrust = a.status & Obj.THRUSTING;
            int x1 = a.cx;
            in[a.slot] = 0;
            s.tick(in);
            int shield = a.used & Has.SHIELD;
            long seq = w.events.seq();
            TestWorlds.run(s, 8, in);
            assertAll(
                () -> assertEquals(10, afterJoin, "the first player starts a 1 s delay"),
                () -> assertEquals("Player entered. Delaying 1 seconds until next round.", joinMsgs.get(0),
                    "announced (C Handle_login)"),
                () -> assertEquals(2, joinMsgs.stream().filter(m -> m.startsWith("Player entered")).count(),
                    "a join during the delay restarts it"),
                () -> assertEquals(0, thrust, "THRUST presses are ignored during the delay"),
                () -> assertEquals(x0, x1, "ships do not move during the delay"),
                () -> assertEquals(Obj.PAUSE, b.status & Obj.PAUSE, "PAUSE works during the delay"),
                () -> assertEquals(Has.SHIELD, shield, "releasing SHIELD does not lower it during the delay"),
                () -> assertEquals(List.of("Round starts now."), messages(w, seq), "after 10 frames the round starts"),
                () -> assertEquals(0, w.roundDelay, "the delay is over"),
                () -> assertEquals(w.frames - 1, a.frameLastBusy, "every player counts as busy at the start"));
        }

        @Test
        @DisplayName("collisions wait for the round delay")
        void noCollisionsInDelay() {
            Simulation s = TestWorlds.sim("framesPerSecond: 10\nroundDelay: 1\ngravity: 0", "         ", " _  _    ");
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            a.used &= ~Has.SHIELD;
            b.used &= ~Has.SHIELD;
            b.initPixels(a.px + 5, a.py);
            s.tick(noKeys(s));
            assertAll(
                () -> assertTrue(s.world.roundDelay > 0, "(fixture: in the delay)"),
                () -> assertEquals(Obj.PLAYING, a.status & Obj.PLAYING, "overlapping ships do not crash"),
                () -> assertEquals(Obj.PLAYING, b.status & Obj.PLAYING, "neither of them"));
        }

        @Test
        @DisplayName("a reset with roundDelay announces and starts the delay; game status is judged only while gameDuration >= 0")
        void resetDelayAndGameDuration() {
            Simulation s = TestWorlds.shipped("tournament.xp", 3);
            World w = s.world;
            w.params.roundDelaySeconds = 2;
            Player a = TestWorlds.join(s, "a", 0);
            Player b = TestWorlds.join(s, "b", 0);
            killOut(s, b);
            w.roundDelay = 0;
            long seq = w.events.seq();
            s.rounds.computeGameStatus();
            List<String> msgs = messages(w, seq);
            Simulation off = TestWorlds.shipped("tournament.xp", 3);
            off.world.gameDuration = -1;
            TestWorlds.join(off, "a", 0);
            Player ob = TestWorlds.join(off, "b", 0);
            killOut(off, ob);
            off.tick(noKeys(off));
            assertAll(
                () -> assertEquals("Delaying 2 seconds until start of next round.", msgs.get(msgs.size() - 1),
                    "the reset announces the delay"),
                () -> assertEquals(2 * w.params.fps, w.roundDelay, "roundDelay * FPS frames"),
                () -> assertEquals(-1, w.roundTime, "no round timer during the delay"),
                () -> assertEquals(Obj.GAME_OVER, ob.status & Obj.GAME_OVER,
                    "gameDuration < 0 (and no maxRoundTime): no round is judged"),
                () -> assertEquals(0, a.life - 3, "(a untouched)"));
        }

        @Test
        @DisplayName("resetOnHuman: the first N humans to join end a limited-lives round (individual draw)")
        void resetOnHuman() {
            Simulation s = TestWorlds.shipped("tournament.xp", 3);
            World w = s.world;
            w.params.resetOnHuman = 2;
            Player a = TestWorlds.join(s, "a", 0);
            long seq = w.events.seq();
            Player b = TestWorlds.join(s, "b", 0);
            List<String> second = messages(w, seq);
            w.params.resetOnHuman = 1;
            long seq3 = w.events.seq();
            TestWorlds.join(s, "c", 0);
            assertAll(
                () -> assertEquals(" < We have a draw! >", second.get(0), "the second human (<= 2) resets the round"),
                () -> assertEquals(0, (a.status | b.status) & Obj.PLAYING, "everyone was reset (killed) for it"),
                () -> assertFalse(messages(w, seq3).contains(" < We have a draw! >"), "the third human (> 1) does not"));
        }
    }

    static boolean allDead(World w) {
        for (int k = 0; k < w.objects.count(); k++) {
            if (w.objects.life[w.objects.active[k]] > 0) {
                return false;
            }
        }
        return true;
    }

    @Nested
    @DisplayName("helpers")
    class Helpers {
        @Test
        @DisplayName("appendFixed2 prints two decimals, rounded, with the sign")
        void fixed2() {
            StringBuilder a = new StringBuilder();
            Rounds.appendFixed2(a, 12.345);
            StringBuilder b = new StringBuilder();
            Rounds.appendFixed2(b, -3.5);
            StringBuilder c = new StringBuilder();
            Rounds.appendFixed2(c, 0.07);
            assertAll(
                () -> assertEquals("12.35", a.toString(), "rounds to 2 decimals"),
                () -> assertEquals("-3.50", b.toString(), "keeps the sign and pads"),
                () -> assertEquals("0.07", c.toString(), "pads the fraction"));
        }
    }

    @Test
    @DisplayName("event order check: kill events precede the SCORE_OBJECTs they cause")
    void killBeforeScores() {
        Simulation s = TestWorlds.shipped("tournament.xp", 3);
        Player a = TestWorlds.join(s, "a", 0);
        long seq = s.world.events.seq();
        a.status |= Obj.SELF_DESTRUCT;
        a.count = 1;
        s.world.params.selfDestructScoreMult = 1;
        s.ships.update(a);
        List<String> ev = events(s.world, seq);
        assertTrue(ev.get(0).startsWith("GAME_EVENT " + Msg.GameEvent.KILL) && ev.get(1).startsWith("SCORE_OBJECT"),
            "the self kill comes first, then its score popup: " + ev);
    }
}
