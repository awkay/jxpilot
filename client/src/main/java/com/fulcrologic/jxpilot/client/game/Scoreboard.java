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
 * Timing (race) mode (F4): C sorts by race progress instead (round * MAX_CHECKS + check, more first; equal
 * progress by who got there first; paused, tank and waiting players last in join order), marks the first row
 * best and fills the race column ("%d.%c" round and check letter, "%3d" above 99 rounds). Progress comes
 * from TIMING messages (C PKT_TIMING).
 */
public final class Scoreboard {
    /** C {@code MAX_TEAMS}. */
    public static final int MAX_TEAMS = 10;
    /** Label capacity (C {@code MSG_LEN}). */
    public static final int LABEL_LEN = 128;

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

    /** BEHAVIOR: C {@code showScoreDecimals} (0..2, clamped); labels change from the next {@code update}. */
    public void setDecimals(int showScoreDecimals) {
        int d = Math.max(0, Math.min(2, showScoreDecimals));
        if (d != decimals) {
            decimals = d;
            builtVersion = -1;
        }
    }

    /** C {@code showScoreDecimals} in use. */
    public int decimals() { return decimals; }

    /** Game mode flags (WELCOME / options) and C {@code showScoreDecimals} (default 1). */
    public void configure(boolean teamPlay, boolean timing, boolean limitedLives, int showScoreDecimals) {
        this.teamPlay = teamPlay;
        this.timing = timing;
        this.limitedLives = limitedLives;
        this.decimals = showScoreDecimals;
        builtVersion = -1;
        java.util.Arrays.fill(teamScore, 0);
    }

    private boolean idMode;

    /** C {@code showRealName} (keyIdMode): rows show "NAME=REAL@HOST" instead of the score line. */
    public boolean idMode() { return idMode; }

    /** BEHAVIOR: flips ID mode (C {@code Key_press_id_mode}); the next {@link #update} rebuilds. */
    public void toggleIdMode() {
        setIdMode(!idMode);
    }

    /** BEHAVIOR: sets ID mode (C MENU / SCORE off, PLAYER on); a change makes the next {@link #update} rebuild. */
    public void setIdMode(boolean on) {
        if (on != idMode) {
            idMode = on;
            builtVersion = -1;
        }
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
            int j = timing ? raceSlot(p, s, i) : 0;
            while (!timing && j < i && p.score(order[j]) >= sc) {
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
            if (idMode) {
                labels[rows].clear().append(p.name(s)).append('=').append(p.realName(s)).append('@').append(p.host(s));
            } else {
                format(labels[rows], c, teamPlay ? p.team(s) : -1, p.alliance(s), p.score(s), p.lives(s), p.name(s),
                    p.round(s), p.check(s));
                appendWar(labels[rows], p, s);
            }
            rows++;
        }
        if (teamPlay && !timing) {
            teamRows(n);
        }
    }

    /**
     * C {@code Paint_score_entry}: a robot's war or seek target, " (NAME)" after its name.
     * <p>
     * BEHAVIOR: appended iff the player's war id is a player in the table.
     */
    static void appendWar(CharBuf b, Players p, int slot) {
        int w = p.slotOf(p.warId(slot));
        if (w >= 0) {
            b.append(" (").append(p.name(w)).append(')');
        }
    }

    /** C {@code Client_score_table} in timing mode: where player {@code s} goes among the first i. */
    private int raceSlot(Players p, int s, int i) {
        if (last(p.statusChar(s))) {
            return i;
        }
        int t = p.timing(s);
        int j = 0;
        for (; j < i; j++) {
            int o = order[j];
            if (p.timing(o) < t || last(p.statusChar(o))) {
                break;
            }
            if (p.timing(o) == t && p.timingTick(o) - p.timingTick(s) > 0) {
                break;
            }
        }
        return j;
    }

    private static boolean last(char c) {
        return c == 'P' || c == 'T' || c == 'W';
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
        if (idMode) {
            heading.append("NICK=USER@HOST"); // C Paint_score_start with showRealName
            return;
        }
        heading.append(teamPlay ? " TM " : " AL ");
        heading.append("  SCORE  ");
        if (limitedLives) {
            heading.append("LIFE");
        }
        heading.append(" NAME");
    }

    /**
     * C label {@code "%c %s%s%s%s  %s"}: status char, race column (timing mode), team column (digit and
     * blank in team play, else the alliance char, ' ' for none), score, lives (limited lives only), name.
     */
    void format(CharBuf b, char statusChar, int team, char alliance, float score, int lives, CharSequence name) {
        format(b, statusChar, team, alliance, score, lives, name, 0, 0);
    }

    /** As above with race progress for the race column ({@link #appendRace}). */
    void format(CharBuf b, char statusChar, int team, char alliance, float score, int lives, CharSequence name,
                int round, int check) {
        b.clear();
        b.append(statusChar).append(' ');
        appendRace(b, statusChar, round, check);
        if (team >= 0) {
            b.append((char) ('0' + (team % 10))).append(' ');
        } else {
            b.append(alliance); // C paint.c: outside team play the column is the alliance mark
        }
        appendScoreAndLife(b, score, lives);
        b.append("  ").append(name);
    }

    /**
     * C {@code Paint_score_entry}'s race column (timing mode only).
     * <p>
     * BEHAVIOR: for an active player (' ' or 'R') with round + check above 0: "%3d" rounds above 99, else
     * round, '.', check as a letter ('a' + check); otherwise three blanks.
     */
    private void appendRace(CharBuf b, char statusChar, int round, int check) {
        if (!timing) {
            return;
        }
        if ((statusChar == ' ' || statusChar == 'R') && round + check > 0) {
            if (round > 99) {
                b.appendInt(round, 3, ' ');
            } else {
                b.appendInt(round).append('.').append((char) ('a' + check));
            }
            return;
        }
        b.append("   ");
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
