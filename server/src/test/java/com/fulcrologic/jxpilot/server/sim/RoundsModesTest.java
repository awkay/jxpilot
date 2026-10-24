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

import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ModeFx.ship;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.teamSim;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Rounds with treasures (player.c Compute_game_status team part) and the gameDuration timer")
class RoundsModesTest {
    /** Team 1 (treasure 0) and team 2 (treasure 2, plus the empty treasure 1) with one player each. */
    static Simulation two(String extra) {
        Simulation s = teamSim(extra);
        ship(s, "A", 1, 100, 200);
        ship(s, "B", 2, 600, 200);
        return s;
    }

    static String winMessage(World w, long from) {
        for (String m : messages(w, from)) {
            if (m.startsWith(" < ")) {
                return m;
            }
        }
        return "";
    }

    @Test
    @DisplayName("treasure counts at the start: per team, empty ones apart")
    void counts() {
        ModeState m = two("").world.modes;
        assertAll(
            () -> assertEquals(1, m.teamTreasures[1], "team 1: one treasure"),
            () -> assertEquals(2, m.teamTreasures[2], "team 2: two"),
            () -> assertEquals(1, m.teamEmptyTreasures[2], "one of them empty"),
            () -> assertEquals(1, m.teamTreasuresLeft[2], "one left to lose"));
    }

    @Test
    @DisplayName("the round goes on while both teams have treasures; destroying the last enemy treasure wins")
    void destroyed() {
        Simulation s = two("");
        World w = s.world;
        long from = w.events.seq();
        s.rounds.computeGameStatus();
        List<String> none = messages(w, from);
        w.modes.teamTreasuresLeft[2] = 0;
        w.modes.teamTreasuresDestroyed[1] = 1;
        s.rounds.computeGameStatus();
        assertAll(
            () -> assertEquals(List.of(), none, "nothing to judge"),
            () -> assertEquals(" < Team 1 has won the game by destroying 1 treasures! >", winMessage(w, from), "win"),
            () -> assertEquals(1, w.modes.teamTreasuresLeft[2], "the next round starts with all treasures"),
            () -> assertEquals(0, w.modes.teamTreasuresDestroyed[1], "and no destroyed count"));
    }

    @Test
    @DisplayName("on the timer: most destroyed, then most saved, then most points, else a draw between teams")
    void timer() {
        Simulation saved = two("maxRoundTime: 10\n");
        World w = saved.world;
        w.roundTime = 0;
        w.modes.teamTreasuresLeft[2] = 0;
        long from = w.events.seq();
        saved.rounds.computeGameStatus();
        Simulation points = two("maxRoundTime: 10\n");
        World pw = points.world;
        pw.roundTime = 0;
        pw.playerAt(1).score = 7.5;
        long from2 = pw.events.seq();
        points.rounds.computeGameStatus();
        Simulation draw = two("maxRoundTime: 10\n");
        World dw = draw.world;
        dw.roundTime = 0;
        long from3 = dw.events.seq();
        draw.rounds.computeGameStatus();
        assertAll(
            () -> assertTrue(messages(w, from).contains("Timer expired. Round ends now."), "timer message"),
            () -> assertEquals(" < Team 1 has won the game by destroying 0 treasures and successfully defending 1! >",
                winMessage(w, from), "most saved"),
            () -> assertEquals(" < Team 2 has won the game by destroying 0 treasures, saving 1, and scoring 7.50 points! >",
                winMessage(pw, from2), "most points"),
            () -> assertEquals(" < We have a draw between teams 1, 2! >", winMessage(dw, from3), "a draw"));
    }

    @Test
    @DisplayName("one team left after a treasure was destroyed wins by staying in the game")
    void stayingIn() {
        Simulation s = teamSim("");
        World w = s.world;
        Player a = ship(s, "A", 1, 100, 200);
        w.modes.teamTreasuresLeft[2] = 0;
        long from = w.events.seq();
        s.rounds.computeGameStatus();
        assertAll(
            () -> assertEquals(" < Team 1 has won the game by staying in the game! >", winMessage(w, from), "win"),
            () -> assertTrue(a.id > 0, "(a)"));
    }

    @Nested
    @DisplayName("gameDuration (server.c Main_loop, frame.c Frame_update: wall clock from the first human)")
    class Duration {
        /** A world whose wall clock reads {@code now[0]}. */
        Simulation timed(String extra, long[] now) {
            Simulation s = two(extra);
            s.world.wallSeconds = () -> now[0];
            return s;
        }

        @Test
        @DisplayName("the first frame with a human starts the clock once: end = (time_t)(minutes * 60) + now")
        void start() {
            long[] now = {1000};
            Simulation s = timed("gameDuration: 0.1\n", now);
            World w = s.world;
            s.rounds.startGameDuration(false);
            long beforeHuman = w.gameOverTime;
            boolean waiting = w.noPlayersEnteredYet;
            s.rounds.startGameDuration(true);
            long set = w.gameOverTime;
            now[0] = 2000;
            s.rounds.startGameDuration(true);
            Simulation tiny = timed("gameDuration: 0.01\n", now);
            tiny.rounds.startGameDuration(true);
            Simulation none = timed("", now);
            none.rounds.startGameDuration(true);
            assertAll(
                () -> assertEquals(0, beforeHuman, "robots alone do not start it"),
                () -> assertTrue(waiting, "still waiting for a human"),
                () -> assertEquals(1006, set, "0.1 min = 6 s after the human came"),
                () -> assertEquals(1006, w.gameOverTime, "only the first human counts"),
                () -> assertEquals(2000, tiny.world.gameOverTime, "0.01 min = 0.6 s truncates to 0 s"),
                () -> assertEquals(0, none.world.gameOverTime, "no gameDuration: no end time"),
                () -> assertFalse(none.world.noPlayersEnteredYet, "but the first human is noted"));
        }

        @Test
        @DisplayName("game over when the seconds left change to <= 0; then a 30 * FPS shutdown; only once")
        void over() {
            long[] now = {1000};
            Simulation s = timed("gameDuration: 0.1\n", now);
            World w = s.world;
            s.rounds.startGameDuration(true);
            s.rounds.checkGameDuration();
            long left0 = w.oldTimeLeft;
            now[0] = 1005;
            s.rounds.checkGameDuration();
            int notYet = w.shutdownServer;
            now[0] = 1006;
            long from = w.events.seq();
            s.rounds.checkGameDuration();
            int shutdown = w.shutdownServer;
            long leftAtEnd = w.oldTimeLeft;
            now[0] = 1010;
            w.shutdownServer = 77;
            s.rounds.checkGameDuration();
            assertAll(
                () -> assertEquals(6, left0, "6 s left at the start"),
                () -> assertEquals(-1, notYet, "1 s left: still running"),
                () -> assertEquals("Game over...", messages(w, from).get(0), "Game_Over"),
                () -> assertEquals(30 * 14, shutdown, "the server stops in 30 s of frames"),
                () -> assertEquals(-1.0f, w.gameDuration, "rounds are no longer judged"),
                () -> assertTrue((w.playerAt(0).status & Obj.GAME_OVER) != 0, "everybody GAME_OVER"),
                () -> assertEquals(0, leftAtEnd, "(the last seconds left)"),
                () -> assertEquals(77, w.shutdownServer, "nothing happens again"),
                () -> assertEquals(0, w.oldTimeLeft, "the timer is off: 0 left"));
        }

        @Test
        @DisplayName("C quirks: 0 s left at the start waits for the next second; robots alone (rawMode) end at once")
        void quirks() {
            long[] now = {1000};
            Simulation tiny = timed("gameDuration: 0.01\n", now);
            tiny.rounds.startGameDuration(true);
            tiny.rounds.checkGameDuration();
            int sameSecond = tiny.world.shutdownServer;
            now[0] = 1001;
            tiny.rounds.checkGameDuration();
            Simulation raw = timed("gameDuration: 5\n", now);
            raw.rounds.startGameDuration(false);
            raw.rounds.checkGameDuration();
            Simulation ended = timed("gameDuration: 5\n", now);
            ended.rounds.startGameDuration(true);
            ended.world.gameDuration = -1;
            now[0] = 999_999;
            ended.rounds.checkGameDuration();
            assertAll(
                () -> assertEquals(-1, sameSecond, "0 left equals the initial 0: no change, no game over"),
                () -> assertEquals(30 * 14, tiny.world.shutdownServer, "one second later: -1 left, game over"),
                () -> assertEquals(30 * 14, raw.world.shutdownServer, "no human: end time 0, already past"),
                () -> assertEquals(-1, ended.world.shutdownServer, "a game ended by rounds stops the timer"));
        }

        @Test
        @DisplayName("shutdownTick: -1 does nothing; it counts down to 0, then the server is to stop")
        void shutdownTick() {
            Simulation s = two("");
            World w = s.world;
            boolean idle = s.rounds.shutdownTick();
            int idleValue = w.shutdownServer;
            w.shutdownServer = 2;
            boolean t1 = s.rounds.shutdownTick();
            boolean t2 = s.rounds.shutdownTick();
            int zero = w.shutdownServer;
            boolean t3 = s.rounds.shutdownTick();
            assertAll(
                () -> assertFalse(idle, "no shutdown pending"),
                () -> assertEquals(-1, idleValue, "stays -1"),
                () -> assertFalse(t1 || t2, "2 -> 1 -> 0 keep running"),
                () -> assertEquals(0, zero, "counted down to 0"),
                () -> assertTrue(t3, "at 0 the server stops (End_game)"));
        }
    }
}
