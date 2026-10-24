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
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.ShipFx.C_FRAMES;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scoring: score.c Rate, SCORE, TEAM_SCORE, Score_players. */
class ScoringTest {
    /** Team 1: bases 0 and 1; team 2: base 2. */
    static Simulation teams(String extra) {
        return sim(C_FRAMES + "teamPlay: yes\n" + extra, ShipsJoinTest.TEAMS);
    }

    @Nested
    @DisplayName("rate")
    class Rate {
        @Test
        void rateFollowsTheCFormula() {
            // score.c: t = (RATE_SIZE/2 * RATE_RANGE) / (|loser - winner| + RATE_RANGE); loser > winner: 20 - t
            assertAll(
                () -> assertEquals(10.0, Scoring.rate(0, 0), "equal scores give RATE_SIZE/2"),
                () -> assertEquals(15.0, Scoring.rate(0, 1024), "beating someone 1024 better gives 20 - 5"),
                () -> assertEquals(5.0, Scoring.rate(1024, 0), "beating someone 1024 worse gives 5"),
                () -> assertEquals(10240.0f / 1124, Scoring.rate(100, 0), 1e-12, "beating a 100-worse player"),
                () -> assertEquals(20 - 10240.0f / 1124, Scoring.rate(-50, 50), 1e-12, "a 100-better loser"));
        }
    }

    @Nested
    @DisplayName("score")
    class Score {
        @Test
        void outsideTeamPlayThePlayerScores() {
            Simulation s = sim(C_FRAMES, ShipFx.ROOM);
            Player p = s.ships.join("a", 0);
            p.scoreDirty = false;
            s.scoring.score(p, 12.5f, 0, 0, "");
            assertAll(
                () -> assertEquals(12.5, p.score, "points are added"),
                () -> assertTrue(p.scoreDirty, "the player is marked for a SCORE event"),
                () -> assertFalse(s.world.teamScoreDirty[0], "no team is touched"));
        }

        @Test
        void inTeamPlayThePlayerAndTheTeamScore() {
            Simulation s = teams("");
            World w = s.world;
            Player p = s.ships.join("a", 1);
            Player mate = s.ships.join("b", 1);
            s.scoring.score(p, 7, 0, 0, "");
            assertAll(
                () -> assertEquals(7.0, p.score, "without teamShareScore the player scores"),
                () -> assertEquals(0.0, mate.score, "the mate does not"),
                () -> assertEquals(7.0, w.teamScore[1], "the team scores"),
                () -> assertTrue(w.teamScoreDirty[1], "the team is marked for a TEAM_SCORE event"));
        }

        @Test
        void withTeamShareScoreTheTeamScoreIsSharedByAllMembers() {
            Simulation s = teams("teamShareScore: yes\n");
            World w = s.world;
            Player p = s.ships.join("a", 1);
            Player mate = s.ships.join("b", 1);
            Player other = s.ships.join("c", 2);
            mate.scoreDirty = false;
            s.scoring.score(p, 8, 0, 0, "");
            assertAll(
                () -> assertEquals(8.0, w.teamScore[1], "the team scores 8"),
                () -> assertEquals(4.0, p.score, "the scorer gets team score / members"),
                () -> assertEquals(4.0, mate.score, "and so does the mate"),
                () -> assertTrue(mate.scoreDirty, "who is marked for a SCORE event"),
                () -> assertEquals(0.0, other.score, "the other team is untouched"));
        }
    }

    @Nested
    @DisplayName("teamScore")
    class TeamScore {
        @Test
        void noTeamOrAnInvalidTeamIsANoOp() {
            Simulation s = teams("");
            World w = s.world;
            s.scoring.teamScore(Const.TEAM_NOT_SET, 5);
            s.scoring.teamScore(Const.MAX_TEAMS, 5);
            s.scoring.teamScore(-2, 5);
            double sum = 0;
            boolean dirty = false;
            for (int t = 0; t < Const.MAX_TEAMS; t++) {
                sum += w.teamScore[t];
                dirty |= w.teamScoreDirty[t];
            }
            double total = sum;
            boolean anyDirty = dirty;
            assertAll(
                () -> assertEquals(0.0, total, "no team score changes"),
                () -> assertFalse(anyDirty, "no team is marked"));
        }

        @Test
        void teamZeroIsAValidTeam() {
            Simulation s = teams("");
            s.scoring.teamScore(0, 4);
            assertAll(
                () -> assertEquals(4.0, s.world.teamScore[0], "team 0 scores"),
                () -> assertTrue(s.world.teamScoreDirty[0], "and is reported"));
        }

        @Test
        void sharingWithNoMembersOnlyChangesTheTeamScore() {
            Simulation s = teams("teamShareScore: yes\n");
            World w = s.world;
            s.scoring.teamScore(2, 6);
            s.scoring.teamScore(9, 1);
            assertAll(
                () -> assertEquals(6.0, w.teamScore[2], "the team score changes"),
                () -> assertEquals(1.0, w.teamScore[9], "the highest team number is valid"),
                () -> assertEquals(0.0, w.teamScore[0], "team 0 untouched so far"),
                () -> assertTrue(w.teamScoreDirty[2], "and is reported"));
        }

        @Test
        void eachDirtyTeamIsReportedOnceAtTheEndOfTheTick() {
            Simulation s = teams("");
            Player p = s.ships.join("a", 1);
            s.tick(new long[3]);
            long seq = s.world.events.seq();
            s.scoring.score(p, 3, 0, 0, "");
            s.scoring.score(p, 2, 0, 0, "");
            s.tick(new long[3]);
            assertEquals(java.util.List.of("SCORE_OBJECT 1 0 0 3.0 ", "SCORE_OBJECT 1 0 0 2.0 ", "SCORE 1 -1 0 5.0",
                    "TEAM_SCORE 1 0 0 5.0"),
                ShipFx.events(s.world, seq), "a score object per change, then one SCORE and one TEAM_SCORE with the totals");
        }
    }

    @Nested
    @DisplayName("score objects (C Send_score_object)")
    class ScoreObjects {
        @Test
        void everyScoreChangeEmitsAScoreObjectForThatPlayerOnlyIfHuman() {
            Simulation s = teams("");
            Player a = s.ships.join("a", 1);
            Player b = s.ships.join("b", 2);
            b.robot = true;
            long seq = s.world.events.seq();
            s.scoring.score(a, -12.5f, 7, 9, "[Wall]");
            s.scoring.score(b, 4, 1, 1, "x");
            s.scoring.scorePlayers(a, 6, "bob", b, -6, "ann");
            assertEquals(java.util.List.of("SCORE_OBJECT " + a.id + " 7 9 -12.5 [Wall]",
                    "SCORE_OBJECT " + a.id + " " + b.bx + " " + b.by + " 6.0 bob"),
                ShipFx.events(s.world, seq),
                "the player's id, block position, points and label; scorePlayers uses the loser's block for both;"
                    + " robots get none");
        }
    }

    @Nested
    @DisplayName("scorePlayers")
    class ScorePlayers {
        @Test
        void teamMatesCannotProfitFromKillingEachOther() {
            Simulation s = teams("");
            Player a = s.ships.join("a", 1);
            Player b = s.ships.join("b", 1);
            s.scoring.scorePlayers(a, 10, "w", b, -10, "l");
            double aMate = a.score;
            double bMate = b.score;
            s.scoring.scorePlayers(a, -3, "w", b, 4, "l");
            assertAll(
                () -> assertEquals(-10.0, aMate, "the killer's positive score becomes negative"),
                () -> assertEquals(-10.0, bMate, "the victim's negative score stays negative"),
                () -> assertEquals(-13.0, a.score, "a negative winner score stays negative"),
                () -> assertEquals(-14.0, b.score, "a positive loser score becomes negative"));
        }

        @Test
        void opponentsGetTheScoresAsGiven() {
            Simulation s = teams("");
            Player a = s.ships.join("a", 1);
            Player c = s.ships.join("c", 2);
            s.scoring.scorePlayers(a, 10, "w", c, -10, "l");
            Simulation free = sim(C_FRAMES, ShipsJoinTest.THREE);
            Player x = free.ships.join("x", 0);
            Player y = free.ships.join("y", 0);
            free.scoring.scorePlayers(x, 6, "w", y, -6, "l");
            assertAll(
                () -> assertEquals(10.0, a.score, "the winner gains"),
                () -> assertEquals(-10.0, c.score, "the loser loses"),
                () -> assertEquals(6.0, x.score, "outside team play the winner gains"),
                () -> assertEquals(-6.0, y.score, "and the loser loses"));
        }
    }
}
