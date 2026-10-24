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
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Target;
import com.fulcrologic.jxpilot.common.map.Treasure;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Rounds and game over: ports of {@code Compute_game_status}, {@code Individual_game_over},
 * {@code Team_game_over}, {@code Compute_end_of_round_values}, {@code Give_best_player_bonus},
 * {@code Give_individual_bonus}, {@code Count_rounds}, {@code Reset_all_players} ({@code src/server/player.c}),
 * {@code Game_Over} (server.c) and the round part of {@code Handle_login} (netserver.c).
 * <p>
 * Not ported: race mode (timing maps play without rounds) and treasure destruction (treasures are not
 * simulated in S1, so every team keeps its treasures: team rounds end when all but one team is out of
 * lives, or when maxRoundTime runs out).
 */
public final class Rounds {

    // ---- message texts as fields: resolved when the class loads, never inside a tick (no allocation)
    private static final String T_AND = Texts.of(" and ");
    private static final String T_AND_SCORING = Texts.of(", and scoring ");
    private static final String T_ARE_THE_DEADLY_PLAYERS_WITH = Texts.of(" are the Deadly Players with kill ratios of ");
    private static final String T_AS_EVERYONE_DIED = Texts.of(" as everyone died");
    private static final String T_BEST_HUMAN_PLAYER = Texts.of("Best human player: ");
    private static final String T_BEST_TEAM = Texts.of("Best team (");
    private static final String T_BETWEEN_TEAMS = Texts.of(" between teams ");
    private static final String T_BY_DESTROYING_0_TREASURES_AND = Texts.of(" by destroying 0 treasures and successfully defending ");
    private static final String T_BY_DESTROYING_0_TREASURES_SAVING = Texts.of(" by destroying 0 treasures, saving ");
    private static final String T_BY_STAYING_ALIVE = Texts.of(" by staying alive");
    private static final String T_COMPLETED = Texts.of(" completed. >");
    private static final String T_DEADLIEST = Texts.of("[Deadliest]");
    private static final String T_DEADLY = Texts.of("[Deadly]");
    private static final String T_DELAYING = Texts.of("Delaying ");
    private static final String T_GAME_OVER = Texts.of("Game over...");
    private static final String T_HAS_WON_THE_GAME = Texts.of(" has won the game! >");
    private static final String T_HAS_WON_THE_GAME_2 = Texts.of(" has won the game");
    private static final String T_IS_THE_DEADLIEST_PLAYER_WITH = Texts.of(" is the Deadliest Player with a kill ratio of ");
    private static final String T_OUT_OF = Texts.of(" out of ");
    private static final String T_PLAYER_ENTERED_DELAYING = Texts.of("Player entered. Delaying ");
    private static final String T_POINTS = Texts.of(" points");
    private static final String T_PTS_TEAM = Texts.of(" Pts): Team ");
    private static final String T_PUNCT = Texts.of(", ");
    private static final String T_PUNCT_2 = Texts.of(" < ");
    private static final String T_PUNCT_3 = Texts.of("! >");
    private static final String T_RACE = Texts.of("race");
    private static final String T_ROUND = Texts.of(" < Round ");
    private static final String T_ROUND_2 = Texts.of("round");
    private static final String T_ROUND_STARTS_NOW = Texts.of("Round starts now.");
    private static final String T_SECONDS_UNTIL_NEXT = Texts.of(" seconds until next ");
    private static final String T_SECONDS_UNTIL_NEXT_ROUND = Texts.of(" seconds until next round.");
    private static final String T_SECONDS_UNTIL_START_OF_NEXT = Texts.of(" seconds until start of next round.");
    private static final String T_TEAM = Texts.of(" < Team ");
    private static final String T_THERE_IS_NO_DEADLY_PLAYER = Texts.of("There is no Deadly Player");
    private static final String T_THE_ROBOTS_HAVE_WON_THE = Texts.of(" < The robots have won the game! >");
    private static final String T_TIMER_EXPIRED_ROUND_ENDS_NOW = Texts.of("Timer expired. Round ends now.");
    private static final String T_WE_HAVE_A_DRAW = Texts.of(" < We have a draw! >");
    private static final String T_WE_HAVE_A_DRAW_2 = Texts.of(" < We have a draw");
    private static final String T_WINNER = Texts.of("[Winner]");
    private static final String T_WORST_HUMAN_PLAYER = Texts.of("Worst human player: ");
    private static final String T_WORST_TEAM = Texts.of("Worst team (");

    /** C {@code MSG_LEN}: messages are split before they would reach it. */
    static final int MSG_LEN = 80;

    private final World w;
    private final Simulation sim;
    /** Scratch (C mallocs these per call). */
    private final int[] bestPlayers;
    private final int[] teamState = new int[Const.MAX_TEAMS];
    private final boolean[] teamWin = new boolean[Const.MAX_TEAMS];
    private final double[] teamScoreSum = new double[Const.MAX_TEAMS];
    private final int[] treasuresLeft = new int[Const.MAX_TEAMS];
    private final int[] teamScoreInt = new int[Const.MAX_TEAMS];
    private final StringBuilder reason = new StringBuilder(Rounds.MSG_LEN);
    private final StringBuilder names = new StringBuilder(Rounds.MSG_LEN);
    private int numBest;
    private double bestRatio;
    private double averageScore;

    private static final int TEAM_EMPTY = 0;
    private static final int TEAM_DEAD = 1;
    private static final int TEAM_ALIVE = 2;

    public Rounds(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        this.bestPlayers = new int[w.players.length];
        for (Treasure t : w.map.treasures()) {
            if (t.team() >= 0 && t.team() < Const.MAX_TEAMS && !t.empty()) {
                treasuresLeft[t.team()]++;
            }
        }
    }

    /**
     * C {@code Compute_game_status} (the simulation calls it while {@code gameDuration >= 0} or with
     * maxRoundTime).
     * <p>
     * BEHAVIOR: round_delay_send counts down; the round delay counts down and at 0 the round starts
     * ("Round starts now.", the round timer is set, every player counts as busy); the round timer counts down.
     * BEHAVIOR: race mode is not ported (nothing else happens on timing maps).
     * BEHAVIOR: team play: teams of non-paused players are alive if any member is not GAME_OVER, dead if all
     * are; with two or more alive teams the round only ends when the timer expires ("Timer expired. Round
     * ends now.", winner by saved treasures, then by summed score, else a draw); with dead teams and one
     * alive team that team wins " by staying alive", with none it is a draw " as everyone died".
     * BEHAVIOR: individual: among non-paused players, one alive of several wins; none alive is a draw; with
     * the timer expired it is a draw.
     */
    public void computeGameStatus() {
        Params p = w.params;
        if (w.roundDelaySend > 0) {
            w.roundDelaySend--;
        }
        if (w.roundDelay > 0) {
            if (--w.roundDelay == 0) {
                w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(T_ROUND_STARTS_NOW);
                w.roundTime = p.maxRoundTime * p.fps;
                for (int k = 0; k < w.numPlayers; k++) {
                    w.playerAt(k).frameLastBusy = w.frames;
                }
            }
        }
        if (w.roundTime > 0) {
            w.roundTime--;
        }
        if (w.map.timing()) {
            return;
        }
        if (p.teamPlay) {
            teamStatus();
        } else {
            individualStatus();
        }
    }

    private boolean timerExpired() {
        return w.params.maxRoundTime > 0 && w.roundTime == 0;
    }

    private void individualStatus() {
        int alive = 0;
        int active = 0;
        int aliveRobots = 0;
        int activeHumans = 0;
        int winner = -1;
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if ((pl.status & Obj.PAUSE) != 0 || pl.tank) {
                continue;
            }
            if ((pl.status & Obj.GAME_OVER) == 0) {
                alive++;
                if (pl.robot) {
                    aliveRobots++;
                }
                winner = k;
            } else if (!pl.robot) {
                activeHumans++;
            }
            active++;
        }
        if (alive == 1 && active > 1) {
            individualGameOver(winner);
        } else if (alive == 0 && active >= 1) {
            individualGameOver(-1);
        } else if (aliveRobots > 1 && alive == aliveRobots && activeHumans > 0) {
            individualGameOver(-2);
        } else if (timerExpired()) {
            message().append(T_TIMER_EXPIRED_ROUND_ENDS_NOW);
            individualGameOver(-1);
        }
    }

    private void teamStatus() {
        for (int i = 0; i < Const.MAX_TEAMS; i++) {
            teamState[i] = TEAM_EMPTY;
        }
        int deadTeams = 0;
        int aliveTeams = 0;
        int winning = -1;
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if ((pl.status & Obj.PAUSE) != 0 || pl.tank || pl.team < 0 || pl.team >= Const.MAX_TEAMS) {
                continue;
            }
            if ((pl.status & Obj.GAME_OVER) != 0) {
                if (teamState[pl.team] == TEAM_EMPTY) {
                    deadTeams++;
                    teamState[pl.team] = TEAM_DEAD;
                }
            } else if (teamState[pl.team] != TEAM_ALIVE) {
                if (teamState[pl.team] == TEAM_DEAD) {
                    deadTeams--;
                }
                teamState[pl.team] = TEAM_ALIVE;
                aliveTeams++;
                winning = pl.team;
            }
        }
        if (aliveTeams > 1) {
            int maxLeft = 0;
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                teamWin[i] = teamState[i] == TEAM_ALIVE;
            }
            // No treasure is ever destroyed in S1 (max_destroyed = 0): the round only ends on the timer.
            if (!timerExpired()) {
                return;
            }
            message().append(T_TIMER_EXPIRED_ROUND_ENDS_NOW);
            // Every alive team destroyed 0 treasures (the most), so all of them are still candidates.
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (teamWin[i] && treasuresLeft[i] > maxLeft) {
                    maxLeft = treasuresLeft[i];
                }
                teamScoreSum[i] = 0;
            }
            for (int k = 0; k < w.numPlayers; k++) {
                Player pl = w.playerAt(k);
                if ((pl.status & Obj.PAUSE) == 0 && !pl.tank && pl.team >= 0 && pl.team < Const.MAX_TEAMS) {
                    teamScoreSum[pl.team] += pl.score;
                }
            }
            double maxScore = 0;
            int winners = 0;
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (!teamWin[i]) {
                    continue;
                }
                if (treasuresLeft[i] == maxLeft) {
                    if (teamScoreSum[i] > maxScore) {
                        maxScore = teamScoreSum[i];
                    }
                    winning = i;
                    winners++;
                } else {
                    teamWin[i] = false;
                }
            }
            StringBuilder r = reason;
            r.setLength(0);
            if (winners == 1) {
                r.append(T_BY_DESTROYING_0_TREASURES_AND).append(maxLeft);
                teamGameOver(winning, r);
                return;
            }
            winners = 0;
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (!teamWin[i]) {
                    continue;
                }
                if (teamScoreSum[i] == maxScore) {
                    winning = i;
                    winners++;
                } else {
                    teamWin[i] = false;
                }
            }
            if (winners == 1) {
                r.append(T_BY_DESTROYING_0_TREASURES_SAVING).append(maxLeft).append(T_AND_SCORING);
                appendFixed2(r, maxScore);
                r.append(T_POINTS);
                teamGameOver(winning, r);
                return;
            }
            r.append(T_BETWEEN_TEAMS);
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (teamWin[i]) {
                    r.append((char) ('0' + i)).append(T_PUNCT);
                }
            }
            r.setLength(r.length() - 2);
            teamGameOver(-1, r);
        } else if (deadTeams > 0) {
            if (aliveTeams == 1) {
                teamGameOver(winning, T_BY_STAYING_ALIVE);
            } else {
                teamGameOver(-1, T_AS_EVERYONE_DIED);
            }
        }
    }

    /** BEHAVIOR: appends v with two decimals, rounded half up (C {@code %.2f} for the scores used here). */
    static void appendFixed2(StringBuilder sb, double v) {
        long c = Math.round(v * 100);
        if (c < 0) {
            sb.append('-');
            c = -c;
        }
        sb.append(c / 100).append('.');
        long f = c % 100;
        if (f < 10) {
            sb.append('0');
        }
        sb.append(f);
    }

    private StringBuilder message() {
        return w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID);
    }

    /**
     * C {@code Compute_end_of_round_values}: the average score over all players (paused ones counted in the
     * divisor only) and the players with the best kills/(deaths+1) ratio, ignoring paused players whose
     * pause count ran out.
     */
    void endOfRoundValues() {
        averageScore = 0;
        numBest = 0;
        bestRatio = -1.0;
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if (pl.tank || ((pl.status & Obj.PAUSE) != 0 && pl.count <= 0)) {
                continue;
            }
            averageScore += pl.score;
            float ratio = (float) pl.kills / (pl.deaths + 1);
            if (ratio > bestRatio) {
                bestRatio = ratio;
                bestPlayers[0] = k;
                numBest = 1;
            } else if (ratio == bestRatio) {
                bestPlayers[numBest++] = k;
            }
        }
        averageScore /= w.numPlayers;
    }

    /**
     * C {@code Give_best_player_bonus}.
     * <p>
     * BEHAVIOR: best ratio 0: "There is no Deadly Player"; one best player: "NAME is the Deadliest Player with
     * a kill ratio of K/D." and ratio * Rate(score, average) points ("[Deadliest]"); several: each gets
     * (int)(ratio * (Rate(score, average) + n) / n) points ("[Deadly]") and "A, B and C are the Deadly Players
     * with kill ratios of K/D." (split before C's 80 char message limit). Nobody eligible: no message
     * (C would read an unset entry).
     */
    void bestPlayerBonus() {
        if (numBest == 0) {
            return;
        }
        if (bestRatio == 0) {
            message().append(T_THERE_IS_NO_DEADLY_PLAYER);
        } else if (numBest == 1) {
            Player bp = w.playerAt(bestPlayers[0]);
            message().append(bp.name).append(T_IS_THE_DEADLIEST_PLAYER_WITH).append(bp.kills)
                .append('/').append(bp.deaths).append('.');
            double points = bestRatio * Scoring.rate(bp.score, averageScore);
            sim.scoring.score(bp, points, bp.bx, bp.by, T_DEADLIEST);
        } else {
            StringBuilder msg = names;
            msg.setLength(0);
            for (int i = 0; i < numBest; i++) {
                Player bp = w.playerAt(bestPlayers[i]);
                float ratio = (float) Scoring.rate(bp.score, averageScore);
                float score = (ratio + numBest) / numBest;
                if (msg.length() > 0) {
                    msg.append(i == numBest - 1 ? T_AND : T_PUNCT);
                }
                if (msg.length() + 8 + bp.name.length() >= MSG_LEN) {
                    message().append(msg);
                    msg.setLength(0);
                }
                msg.append(bp.name);
                double points = (int) (bestRatio * score);
                sim.scoring.score(bp, points, bp.bx, bp.by, T_DEADLY);
            }
            if (msg.length() + 64 >= MSG_LEN) {
                message().append(msg);
                msg.setLength(0);
            }
            Player first = w.playerAt(bestPlayers[0]);
            msg.append(T_ARE_THE_DEADLY_PLAYERS_WITH).append(first.kills).append('/')
                .append(first.deaths).append('.');
            message().append(msg);
        }
    }

    /** C {@code Give_individual_bonus}: kills/(deaths+1) * Rate(score, average) points ("[Winner]"). */
    void individualBonus(Player pl) {
        float ratio = (float) pl.kills / (pl.deaths + 1);
        double points = ratio * Scoring.rate(pl.score, averageScore);
        sim.scoring.score(pl, points, pl.bx, pl.by, T_WINNER);
    }

    private boolean isBest(int k) {
        for (int i = 0; i < numBest; i++) {
            if (bestPlayers[i] == k) {
                return true;
            }
        }
        return false;
    }

    /**
     * C {@code Individual_game_over}.
     * <p>
     * BEHAVIOR: announces the winner (k = position in the player order), a draw (-1) or the robots (-2); gives
     * the best-player bonus, then the winner (or each robot, for -2) the individual bonus unless already a
     * best player; then resets every player ({@link #resetAllPlayers}). Rounds are not counted (C).
     */
    void individualGameOver(int winner) {
        endOfRoundValues();
        if (winner == -1) {
            message().append(T_WE_HAVE_A_DRAW);
            Sounds.playAll(w, SoundEvent.PLAYER_DRAW);
        } else if (winner == -2) {
            message().append(T_THE_ROBOTS_HAVE_WON_THE);
            Sounds.playAll(w, SoundEvent.PLAYER_WIN);
        } else {
            message().append(T_PUNCT_2).append(w.playerAt(winner).name).append(T_HAS_WON_THE_GAME);
            Sounds.playAll(w, SoundEvent.PLAYER_WIN);
        }
        bestPlayerBonus();
        if (winner >= 0) {
            if (!isBest(winner)) {
                individualBonus(w.playerAt(winner));
            }
        } else if (winner == -2) {
            for (int k = 0; k < w.numPlayers; k++) {
                if (w.playerAt(k).robot && !isBest(k)) {
                    individualBonus(w.playerAt(k));
                }
            }
        }
        resetAllPlayers();
    }

    /**
     * C {@code Team_game_over}.
     * <p>
     * BEHAVIOR: announces the winning team (or a draw) with the reason, gives the best-player bonus and the
     * individual bonus to winning team members (not paused-and-counted-out, not waiting with no score, not
     * already a best player), resets every player and counts the round.
     */
    void teamGameOver(int winningTeam, CharSequence reason) {
        endOfRoundValues();
        if (winningTeam != -1) {
            message().append(T_TEAM).append(winningTeam).append(T_HAS_WON_THE_GAME_2).append(reason)
                .append(T_PUNCT_3);
            Sounds.playAll(w, SoundEvent.TEAM_WIN);
        } else {
            message().append(T_WE_HAVE_A_DRAW_2).append(reason).append(T_PUNCT_3);
            Sounds.playAll(w, SoundEvent.TEAM_DRAW);
        }
        bestPlayerBonus();
        if (winningTeam != -1) {
            for (int k = 0; k < w.numPlayers; k++) {
                Player pl = w.playerAt(k);
                if (pl.team != winningTeam) {
                    continue;
                }
                if (pl.tank || ((pl.status & Obj.PAUSE) != 0 && pl.count <= 0)
                    || ((pl.status & Obj.GAME_OVER) != 0 && pl.mychar == 'W' && pl.score == 0)) {
                    continue;
                }
                if (!isBest(k)) {
                    individualBonus(pl);
                }
            }
        }
        resetAllPlayers();
        countRounds();
    }

    /** C {@code Count_rounds}: with roundsToPlay, announce the round and end the game after the last one. */
    void countRounds() {
        Params p = w.params;
        if (p.roundsToPlay == 0) {
            return;
        }
        ++w.roundsPlayed;
        message().append(T_ROUND).append(w.roundsPlayed).append(T_OUT_OF).append(p.roundsToPlay)
            .append(T_COMPLETED);
        if (w.roundsPlayed >= p.roundsToPlay) {
            gameOver();
        }
    }

    /**
     * C {@code Game_Over}: "Game over...", the best and worst team (team play, by summed int score) and human
     * player; every player is GAME_OVER and rounds stop being judged (gameDuration = -1).
     */
    void gameOver() {
        message().append(T_GAME_OVER);
        w.gameDuration = -1.0f;
        if (w.params.teamPlay) {
            final int unused = 1234567;
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                teamScoreInt[i] = unused;
            }
            for (int k = 0; k < w.numPlayers; k++) {
                Player pl = w.playerAt(k);
                if (!pl.robot && pl.team >= 0 && pl.team < Const.MAX_TEAMS) {
                    if (teamScoreInt[pl.team] == unused) {
                        teamScoreInt[pl.team] = 0;
                    }
                    teamScoreInt[pl.team] = (int) (teamScoreInt[pl.team] + (float) pl.score);
                }
            }
            long maxsc = -32767;
            long minsc = 32767;
            int win = -1;
            int lose = -1;
            for (int i = 0; i < Const.MAX_TEAMS; i++) {
                if (teamScoreInt[i] != unused) {
                    if (teamScoreInt[i] > maxsc) {
                        maxsc = teamScoreInt[i];
                        win = i;
                    }
                    if (teamScoreInt[i] < minsc) {
                        minsc = teamScoreInt[i];
                        lose = i;
                    }
                }
            }
            if (win != -1) {
                message().append(T_BEST_TEAM).append(maxsc).append(T_PTS_TEAM).append(win);
            }
            if (lose != -1 && lose != win) {
                message().append(T_WORST_TEAM).append(minsc).append(T_PTS_TEAM).append(lose);
            }
        }
        long maxsc = -32767;
        long minsc = 32767;
        Player win = null;
        Player lose = null;
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            pl.status |= Obj.GAME_OVER;
            pl.scoreDirty = true;
            if (!pl.robot) {
                if (pl.score > maxsc) {
                    maxsc = (long) pl.score;
                    win = pl;
                }
                if (pl.score < minsc) {
                    minsc = (long) pl.score;
                    lose = pl;
                }
            }
        }
        if (win != null) {
            message().append(T_BEST_HUMAN_PLAYER).append(win.name);
        }
        if (lose != null && lose != win) {
            message().append(T_WORST_HUMAN_PLAYER).append(lose.name);
        }
    }

    /**
     * C {@code Reset_all_players}.
     * <p>
     * BEHAVIOR: with endOfRoundReset (option {@code reset}) every ship is killed (paused ones only reset; a
     * robot that loses its last life this way may leave, and the loop goes on with the player moved into its place)
     * and every shot, mine, debris, spark, cannon shot, missile and item dies (explosives lose their mass, so
     * nothing explodes); targets are repaired in team play.
     * BEHAVIOR: every player leaves GAME_OVER with kills and deaths 0; players that are not paused are busy
     * now, get full lives and mychar ' '.
     * BEHAVIOR: with roundDelay the next round waits roundDelay seconds ("Delaying N seconds until start of
     * next round."), else the round timer restarts; every score is resent.
     */
    void resetAllPlayers() {
        Params p = w.params;
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            if (p.endOfRoundReset) {
                if ((pl.status & Obj.PAUSE) != 0) {
                    sim.ships.deathReset(pl);
                } else {
                    sim.ships.kill(pl);
                    if (!pl.inUse) {
                        k--; // a tank or a robot out of lives was deleted (player.c:690-694)
                        continue;
                    }
                }
            }
            pl.status &= ~Obj.GAME_OVER;
            pl.have &= ~Has.BALL;
            pl.kills = 0;
            pl.deaths = 0;
            if ((pl.status & Obj.PAUSE) == 0) {
                pl.mychar = ' ';
                pl.frameLastBusy = w.frames;
                pl.life = p.worldLives;
            }
            if (pl.tank) {
                pl.mychar = 'T';
            }
            pl.scoreDirty = true;
        }
        if (p.teamPlay && p.endOfRoundReset) {
            for (int i = 0; i < w.targetDeadTime.length; i++) {
                Target t = w.map.targets().get(i);
                if (w.targetDeadTime[i] != 0) {
                    w.map.setBlock(t.bx(), t.by(), BlockType.TARGET);
                    w.targetDeadTime[i] = 0;
                }
            }
        }
        if (p.endOfRoundReset) {
            Objects o = w.objects;
            for (int k = 0; k < o.count(); k++) {
                int h = o.active[k];
                int type = o.type[h];
                if ((type & (Obj.SHOT | Obj.MINE | Obj.DEBRIS | Obj.SPARK | Obj.CANNON_SHOT | Obj.TORPEDO
                    | Obj.SMART_SHOT | Obj.HEAT_SHOT | Obj.ITEM)) != 0) {
                    o.life[h] = 0;
                    if ((type & (Obj.TORPEDO | Obj.SMART_SHOT | Obj.HEAT_SHOT | Obj.CANNON_SHOT | Obj.MINE)) != 0) {
                        o.mass[h] = 0;
                    }
                }
            }
        }
        if (w.roundDelaySend > 0) {
            w.roundDelaySend--;
        }
        if (p.roundDelaySeconds != 0) {
            w.roundDelay = p.roundDelaySeconds * p.fps;
            w.roundDelaySend = w.roundDelay + p.fps;
            w.roundTime = -1;
            message().append(T_DELAYING).append(p.roundDelaySeconds).append(T_SECONDS_UNTIL_START_OF_NEXT);
        } else {
            w.roundTime = p.maxRoundTime * p.fps;
        }
    }

    /**
     * The round part of C {@code Handle_login}, after the player entered the game.
     * <p>
     * BEHAVIOR: with resetOnHuman = N &gt; 0, while at most N humans are in the game and no round delay runs,
     * the round ends (team play: a draw; limitedLives: an individual draw) so the newcomer plays at once.
     * BEHAVIOR: during a round delay, or for the first player, the delay (re)starts at roundDelay seconds
     * (and the round timer at maxRoundTime when there is no delay) with "Player entered. Delaying N seconds
     * until next round.".
     */
    public void playerEntered(Player pl) {
        Params p = w.params;
        if (p.resetOnHuman > 0 && sim.humans() <= p.resetOnHuman && w.roundDelay == 0 && !w.map.timing()) {
            if (p.teamPlay) {
                teamGameOver(-1, "");
            } else if (p.limitedLives) {
                individualGameOver(-1);
            }
        }
        if (w.roundDelay > 0 || w.numPlayers == 1) {
            w.roundDelay = p.roundDelaySeconds * p.fps;
            w.roundTime = p.maxRoundTime > 0 && p.roundDelaySeconds == 0 ? p.maxRoundTime * p.fps : -1;
            message().append(T_PLAYER_ENTERED_DELAYING).append(p.roundDelaySeconds)
                .append(T_SECONDS_UNTIL_NEXT_ROUND);
        }
    }

    /**
     * The round part of C {@code Robot_create} (robot.c), after the robot entered the game and said ENTER.
     * <p>
     * BEHAVIOR: during a round delay, or for the first player, the delay (re)starts at roundDelay seconds and
     * is sent one second longer (C {@code round_delay_send}); the round timer starts at maxRoundTime when there
     * is no delay, else none; the message is "Player entered. Delaying N seconds until next round." ("race" on
     * timing maps). Otherwise nothing changes. (No resetOnHuman: C checks it only for human logins.)
     */
    public void robotEntered() {
        Params p = w.params;
        if (w.roundDelay > 0 || w.numPlayers == 1) {
            w.roundDelay = p.roundDelaySeconds * p.fps;
            w.roundDelaySend = w.roundDelay + p.fps;
            w.roundTime = p.maxRoundTime > 0 && p.roundDelaySeconds == 0 ? p.maxRoundTime * p.fps : -1;
            message().append(T_PLAYER_ENTERED_DELAYING).append(p.roundDelaySeconds)
                .append(T_SECONDS_UNTIL_NEXT).append(w.map.timing() ? T_RACE : T_ROUND_2).append('.');
        }
    }
}
