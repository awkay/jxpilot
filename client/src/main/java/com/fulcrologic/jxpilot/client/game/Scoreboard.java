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
package com.fulcrologic.jxpilot.client.game;

/**
 * The score list model, a port of C {@code Client_score_table} ({@code src/client/client.c}) and the
 * line format of {@code Paint_score_entry}/{@code Paint_score_start} ({@code src/client/paint.c}).
 * Rows are formatted into reusable {@link CharBuf}s; rebuilding allocates nothing.
 * <p>
 * Order: players by score, descending, stable (a later player with an equal score goes after). The
 * "best" player (underlined in C) has the highest {@code score / (life + 1)} (score alone with
 * limited lives); in timing mode it is the first row. In team play (not timing) team rows follow
 * after one empty line, ordered by team score, ties broken by lives (more first with limited lives,
 * fewer otherwise).
 * <p>
 * Known deviation (race mode is out of scope for now): in timing mode C sorts by race progress (round,
 * checkpoint, lap time) and fills the race column; the protocol does not carry race progress yet, so
 * timing mode keeps the score order, marks the first row best and leaves the race column blank
 * ({@link #appendRace}, the hook for it).
 */
public final class Scoreboard {
    /** C {@code MAX_TEAMS}. */
    public static final int MAX_TEAMS = 10;
    /** Label capacity (C {@code MSG_LEN}). */
    public static final int LABEL_LEN = 80;

    private final CharBuf heading = new CharBuf(LABEL_LEN);
    private final CharBuf[] labels = new CharBuf[Players.CAPACITY + MAX_TEAMS];
    private final int[] rowSlot = new int[Players.CAPACITY + MAX_TEAMS];
    private final int[] rowLine = new int[Players.CAPACITY + MAX_TEAMS];
    private final boolean[] rowDim = new boolean[Players.CAPACITY + MAX_TEAMS];
    private final int[] order = new int[Players.CAPACITY];
    private final float[] teamScore = new float[MAX_TEAMS];
    private final int[] teamLife = new int[MAX_TEAMS];
    private final int[] teamPlaying = new int[MAX_TEAMS];
    private final int[] teamOrder = new int[MAX_TEAMS];
    private int rows;
    private int bestRow = -1;
    private boolean teamPlay;
    private boolean timing;
    private boolean limitedLives;
    private int decimals = 1;
    private int builtVersion = -1;
    private int teamVersion;
    private int builtTeamVersion = -1;

    public Scoreboard() {
        for (int i = 0; i < labels.length; i++) {
            labels[i] = new CharBuf(LABEL_LEN);
        }
    }

    /** Game mode flags (WELCOME / options) and C {@code showScoreDecimals} (default 1). */
    public void configure(boolean teamPlay, boolean timing, boolean limitedLives, int showScoreDecimals) {
        this.teamPlay = teamPlay;
        this.timing = timing;
        this.limitedLives = limitedLives;
        this.decimals = showScoreDecimals;
        builtVersion = -1;
        java.util.Arrays.fill(teamScore, 0);
    }

    /** BEHAVIOR: TEAM_SCORE; teams outside 0..9 are ignored. */
    public void setTeamScore(int team, float score) {
        if (team >= 0 && team < MAX_TEAMS) {
            teamScore[team] = score;
            teamVersion++;
        }
    }

    public float teamScore(int team) { return teamScore[team]; }

    /** The column heading line. */
    public CharBuf heading() { return heading; }

    /** Rows (players, then team rows). */
    public int rowCount() { return rows; }

    public CharBuf label(int row) { return labels[row]; }

    /** Players slot of a row, -1 for a team row. */
    public int rowSlot(int row) { return rowSlot[row]; }

    /** C entry number (line position): players 0..n-1, team rows from n+1 (one blank line). */
    public int rowLine(int row) { return rowLine[row]; }

    /** BEHAVIOR: true for players marked 'D', 'P' or 'W' and team rows marked 'D' (drawn dimmed in C). */
    public boolean dimmed(int row) { return rowDim[row]; }

    /** Row of the best player (underlined), -1 when none. */
    public int bestRow() { return bestRow; }

    /** BEHAVIOR: rebuilds only when the players or team scores changed since the last build. */
    public void update(Players p) {
        if (p.version() == builtVersion && teamVersion == builtTeamVersion) {
            return;
        }
        rebuild(p);
    }

    /** Rebuild rows from the players unconditionally. */
    public void rebuild(Players p) {
        builtVersion = p.version();
        builtTeamVersion = teamVersion;
        buildHeading();
        int n = p.count();
        java.util.Arrays.fill(teamLife, 0);
        java.util.Arrays.fill(teamPlaying, 0);
        int best = -1;
        double bestRatio = -1e7;
        for (int i = 0; i < n; i++) {
            int s = p.slotAt(i);
            float sc = p.score(s);
            int life = Math.max(0, p.lives(s));
            double ratio = limitedLives ? sc : sc / (life + 1.0);
            if (best == -1 || ratio > bestRatio) {
                bestRatio = ratio;
                best = s;
            }
            int j = 0;
            while (j < i && p.score(order[j]) >= sc) {
                j++;
            }
            System.arraycopy(order, j, order, j + 1, i - j);
            order[j] = s;
            if (teamPlay && !timing) {
                accumulateTeam(p, s, life);
            }
        }
        if (timing && n > 0) {
            best = order[0];
        }
        rows = 0;
        bestRow = -1;
        for (int i = 0; i < n; i++) {
            int s = order[i];
            if (s == best) {
                bestRow = rows;
            }
            char c = p.statusChar(s);
            rowSlot[rows] = s;
            rowLine[rows] = i;
            rowDim[rows] = c == 'D' || c == 'P' || c == 'W';
            format(labels[rows], c, teamPlay ? p.team(s) : -1, p.alliance(s), p.score(s), p.lives(s), p.name(s));
            rows++;
        }
        if (teamPlay && !timing) {
            teamRows(n);
        }
    }

    private void accumulateTeam(Players p, int s, int life) {
        int t = p.team(s);
        if (t < 0 || t >= MAX_TEAMS) {
            return;
        }
        char c = p.statusChar(s);
        if (c == 'P' || c == 'W' || c == 'T') {
            return;
        }
        if (c == ' ' || c == 'R') {
            teamLife[t] += limitedLives ? life + 1 : life;
        }
        teamPlaying[t]++;
    }

    private void teamRows(int numPlayers) {
        int playing = 0;
        for (int t = 0; t < MAX_TEAMS; t++) {
            if (teamPlaying[t] == 0) {
                continue;
            }
            int j = 0;
            while (j < playing && !teamBefore(t, teamOrder[j])) {
                j++;
            }
            System.arraycopy(teamOrder, j, teamOrder, j + 1, playing - j);
            teamOrder[j] = t;
            playing++;
        }
        int line = numPlayers + 1;
        for (int i = 0; i < playing; i++) {
            int t = teamOrder[i];
            char c = limitedLives && teamLife[t] == 0 ? 'D' : ' ';
            CharBuf b = labels[rows];
            b.clear();
            b.append(c).append(' ');
            b.append((char) ('0' + t)).append(' ');
            appendScoreAndLife(b, teamScore[t], teamLife[t]);
            b.append("  Team ").appendInt(t);
            rowSlot[rows] = -1;
            rowLine[rows] = line++;
            rowDim[rows] = c == 'D';
            rows++;
        }
    }

    /** C's team comparison: higher score first; equal scores by lives. */
    private boolean teamBefore(int t, int other) {
        if (teamScore[t] != teamScore[other]) {
            return teamScore[t] > teamScore[other];
        }
        return limitedLives ? teamLife[t] > teamLife[other] : teamLife[t] < teamLife[other];
    }

    private void buildHeading() {
        heading.clear();
        heading.append(teamPlay ? " TM " : " AL ");
        heading.append("  SCORE  ");
        if (limitedLives) {
            heading.append("LIFE");
        }
        heading.append(" NAME");
    }

    /**
     * C label {@code "%c %s%s%s%s  %s"}: status char, race column (3 blanks in timing mode), team
     * column (digit and blank in team play, else the alliance blank), score, lives (limited lives only),
     * name.
     */
    void format(CharBuf b, char statusChar, int team, char alliance, float score, int lives, CharSequence name) {
        b.clear();
        b.append(statusChar).append(' ');
        appendRace(b);
        if (team >= 0) {
            b.append((char) ('0' + (team % 10))).append(' ');
        } else {
            b.append(alliance); // C paint.c: outside team play the column is the alliance mark
        }
        appendScoreAndLife(b, score, lives);
        b.append("  ").append(name);
    }

    /** The race column: blank in timing mode (race progress is not on the wire yet, see the class comment). */
    private void appendRace(CharBuf b) {
        if (timing) {
            b.append("   ");
        }
    }

    private void appendScoreAndLife(CharBuf b, float score, int lives) {
        if (decimals > 0) {
            b.appendFixed(score, decimals, 8 - decimals);
        } else {
            b.appendInt((int) Math.rint(score), 5, ' ');
        }
        if (limitedLives) {
            b.append(' ').appendInt(lives, 3, ' ');
        }
    }
}
