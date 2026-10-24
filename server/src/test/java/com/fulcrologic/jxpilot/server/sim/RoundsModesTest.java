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
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.sim.ModeFx.ship;
import static com.fulcrologic.jxpilot.server.sim.ModeFx.teamSim;
import static com.fulcrologic.jxpilot.server.sim.SimFixture.messages;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    @DisplayName("gameDuration: game over after that many minutes of game time, then a 30 s shutdown countdown")
    void duration() {
        Simulation s = two("gameDuration: 0.1\n");
        World w = s.world;
        long end = w.gameOverFrame;
        w.frames = end - 1;
        s.rounds.checkGameDuration();
        int before = w.shutdownDelay;
        w.frames = end;
        long from = w.events.seq();
        s.rounds.checkGameDuration();
        int after = w.shutdownDelay;
        s.rounds.checkGameDuration();
        Simulation off = two("");
        assertAll(
            () -> assertEquals(1 + (long) (0.1f * 60 * 14), end, "6 s of frames after the start (frame 1)"),
            () -> assertEquals(-1, before, "not yet"),
            () -> assertEquals("Game over...", messages(w, from).get(0), "Game_Over"),
            () -> assertEquals(30 * 14, after, "shutdown in 30 s"),
            () -> assertEquals(30 * 14 - 1, w.shutdownDelay, "counting down, and only once over"),
            () -> assertEquals(-1.0f, w.gameDuration, "rounds are no longer judged"),
            () -> assertTrue((w.playerAt(0).status & Obj.GAME_OVER) != 0, "everybody GAME_OVER"),
            () -> assertEquals(-1, off.world.gameOverFrame, "no gameDuration: no timer"));
    }
}
