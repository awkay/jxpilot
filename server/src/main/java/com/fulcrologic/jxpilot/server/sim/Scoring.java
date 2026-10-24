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
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Score rules, a port of {@code src/server/score.c}. Changed players and
 * teams are marked dirty; {@link Simulation} emits SCORE/TEAM_SCORE events once per tick for them. Every
 * player score change also emits a SCORE_OBJECT event for that player's HUD (C {@code Send_score_object}).
 */
public final class Scoring {
    /** C {@code CANNON_SCORE}. */
    public static final double CANNON_SCORE = -1436;
    /** C {@code WALL_SCORE}. */
    public static final double WALL_SCORE = 2000;
    /** C {@code RATE_SIZE}. */
    static final double RATE_SIZE = 20;
    /** C {@code RATE_RANGE}. */
    static final double RATE_RANGE = 1024;

    private final World w;

    public Scoring(World w) {
        this.w = w;
    }

    /**
     * C {@code Rate}: points for beating someone.
     * <p>
     * BEHAVIOR: t = (RATE_SIZE/2 * RATE_RANGE) / (|loser - winner| + RATE_RANGE); equal scores give 10.
     * BEHAVIOR: when the loser has the higher score the result is RATE_SIZE - t (more than 10).
     */
    public static double rate(double winner, double loser) {
        double t = ((RATE_SIZE / 2) * RATE_RANGE) / (Math.abs(loser - winner) + RATE_RANGE);
        if (loser > winner) {
            t = RATE_SIZE - t;
        }
        return t;
    }

    /**
     * C {@code SCORE(ind, points, x, y, msg)}.
     * <p>
     * BEHAVIOR: in team play the team score changes and the player's own score changes only without
     * teamShareScore; otherwise, for a player in an alliance with teamShareScore, the points are shared
     * among the alliance's members (C {@code Alliance_score}), else the player's score changes. The player
     * is marked dirty.
     * BEHAVIOR: a human player gets a SCORE_OBJECT event (a = id, b = block x, c = block y, value = points,
     * text = the label).
     */
    public void score(Player pl, double points, int bx, int by, CharSequence msg) {
        if (w.params.teamPlay) {
            if (!w.params.teamShareScore) {
                pl.score += points;
            }
            teamScore(pl.team, points);
        } else if (pl.alliance != Player.ALLIANCE_NOT_SET && w.params.teamShareScore) {
            allianceScore(pl.alliance, points); // server-commands: score.c:58-59
        } else {
            pl.score += points;
        }
        pl.scoreDirty = true;
        if (!pl.robot) {
            w.events.text[w.events.add(Events.SCORE_OBJECT, pl.id, bx, by, (float) points)].append(msg);
        }
    }

    /**
     * C {@code TEAM_SCORE}.
     * <p>
     * BEHAVIOR: no-op for TEAM_NOT_SET; with teamShareScore every member's score becomes team score / members.
     */
    public void teamScore(int team, double points) {
        teamScore(team, points, w.numPlayers);
    }

    /**
     * {@link #teamScore(int, double)} that shares among the first {@code n} players of the game order only (C's
     * loop runs to NumPlayers, which {@code Delete_player} has already decremented, see {@link Ships#leave}).
     */
    void teamScore(int team, double points, int n) {
        if (team == Const.TEAM_NOT_SET || team < 0 || team >= Const.MAX_TEAMS) {
            return;
        }
        w.teamScore[team] += points;
        w.teamScoreDirty[team] = true;
        if (w.params.teamShareScore && w.teamMembers[team] > 0) {
            double share = w.teamScore[team] / w.teamMembers[team];
            for (int k = 0; k < n; k++) {
                Player p = w.playerAt(k);
                if (p.team == team) {
                    p.score = share;
                    p.scoreDirty = true;
                }
            }
        }
    }

    /**
     * server-commands: C {@code Alliance_score}.
     * <p>
     * BEHAVIOR: every member of alliance {@code id} gets points / (number of members) and is marked dirty.
     */
    void allianceScore(int id, double points) {
        int members = 0;
        for (int k = 0; k < w.numPlayers; k++) {
            if (w.playerAt(k).alliance == id) {
                members++;
            }
        }
        double share = points / members;
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (p.alliance == id) {
                p.score += share;
                p.scoreDirty = true;
            }
        }
    }

    /**
     * C {@code Score_players}.
     * <p>
     * BEHAVIOR: team mates, allies and a player who kills its own tank get both scores made non-positive
     * (score.c:128-140: no profit from killing your own side), then each gets their score via {@link #score} at the
     * loser's block position with their own label.
     */
    public void scorePlayers(Player winner, double winnerScore, CharSequence winnerMsg, Player loser,
                             double loserScore, CharSequence loserMsg) {
        if (w.sameTeam(winner, loser) || w.allied(winner, loser)
            || (loser.tank && loser.lockPlId == winner.id)) {
            if (winnerScore > 0) {
                winnerScore = -winnerScore;
            }
            if (loserScore > 0) {
                loserScore = -loserScore;
            }
        }
        score(winner, winnerScore, loser.bx, loser.by, winnerMsg);
        score(loser, loserScore, loser.bx, loser.by, loserMsg);
    }
}
