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
 * The own score change objects of C {@code Handle_score_object} ({@code src/client/client.c}) and
 * {@code Paint_score_objects}/the HUD score stack ({@code src/client/painthud.c}): a ring of
 * {@link #MAX_SCORE_OBJECTS}; each holds the points, the HUD text {@code "<msg> <points>"} (C
 * {@code hud_msg}, only when there is a message), the game-area text {@code "<points>"} (C {@code msg},
 * drawn at the block where it happened) and a frame counter. Points use {@code %.*f} with
 * showScoreDecimals (printf rounding), or {@code %d} of rint when 0.
 * <p>
 * Fed by {@link ClientGame} from SCORE_OBJECT (the server's C {@code Send_score_object}: points, label and
 * block), or the C protocol's {@code PKT_SCORE_OBJECT} on the XPilot path. Allocation free.
 */
public final class ScoreObjects {
    /** C {@code MAX_SCORE_OBJECTS}. */
    public static final int MAX_SCORE_OBJECTS = 10;
    /** C {@code SCORE_OBJECT_COUNT}: frames an object lives. */
    public static final int SCORE_OBJECT_COUNT = 100;
    /** Game-area text capacity (C {@code MAX_CHARS}). */
    public static final int TEXT_LEN = 80;
    /** HUD text capacity (C {@code hud_msg[MAX_CHARS + 10]}). */
    public static final int HUD_TEXT_LEN = TEXT_LEN + 10;

    private final float[] score = new float[MAX_SCORE_OBJECTS];
    private final CharBuf[] hud = new CharBuf[MAX_SCORE_OBJECTS];
    private final CharBuf[] text = new CharBuf[MAX_SCORE_OBJECTS];
    private final double[] count = new double[MAX_SCORE_OBJECTS];
    private final boolean[] hasPos = new boolean[MAX_SCORE_OBJECTS];
    private final int[] bx = new int[MAX_SCORE_OBJECTS];
    private final int[] by = new int[MAX_SCORE_OBJECTS];
    private int next;
    private int decimals = 1;

    public ScoreObjects() {
        for (int i = 0; i < MAX_SCORE_OBJECTS; i++) {
            hud[i] = new CharBuf(HUD_TEXT_LEN);
            text[i] = new CharBuf(TEXT_LEN);
        }
    }

    /** C {@code showScoreDecimals} (default 1). */
    public void setDecimals(int d) {
        decimals = d;
    }

    /** BEHAVIOR: forgets every object. */
    public void clear() {
        for (int i = 0; i < MAX_SCORE_OBJECTS; i++) {
            count[i] = 0;
            hud[i].clear();
        }
        next = 0;
    }

    /**
     * C {@code Handle_score_object}: overwrite the next ring slot.
     * <p>
     * BEHAVIOR: count starts at 1; the game-area text is the formatted points; the HUD text is
     * {@code msg + " " + points} when msg is non-empty, the points alone when msg is null, and none
     * for an empty msg (as C), cut at {@link #HUD_TEXT_LEN}; the position is kept iff {@code withPos}.
     *
     * @param msg the reason (other player's name, "[Wall]", "Target: "...), "" for none, null for the
     *            points alone
     */
    public void add(float points, CharSequence msg, boolean withPos, int blockX, int blockY) {
        int i = next;
        score[i] = points;
        count[i] = 1;
        hasPos[i] = withPos;
        bx[i] = blockX;
        by[i] = blockY;
        format(text[i].clear(), points);
        CharBuf h = hud[i].clear();
        if (msg == null) {
            format(h, points);
        } else if (msg.length() > 0) {
            h.append(msg).append(' ');
            format(h, points);
        }
        next = (next + 1) % MAX_SCORE_OBJECTS;
    }

    private void format(CharBuf b, float points) {
        if (decimals > 0) {
            b.appendFixed(points, decimals, 0);
        } else {
            b.appendInt((int) Math.rint(points));
        }
    }

    /**
     * Age every live object by {@code frames} original frames (C counts painted frames).
     * <p>
     * BEHAVIOR: an object whose count passes {@link #SCORE_OBJECT_COUNT} dies (count 0, HUD text gone).
     */
    public void tick(double frames) {
        for (int i = 0; i < MAX_SCORE_OBJECTS; i++) {
            if (count[i] > 0) {
                count[i] += frames;
                if (count[i] > SCORE_OBJECT_COUNT) {
                    count[i] = 0;
                    hud[i].clear();
                }
            }
        }
    }

    /** Ring slot of the i-th object in C's HUD order: i = 0 is the oldest slot ({@code (i + next) % MAX}). */
    public int hudOrder(int i) {
        return (i + next) % MAX_SCORE_OBJECTS;
    }

    /** True while slot s is alive. */
    public boolean active(int s) { return count[s] > 0; }

    /** Frames slot s has lived (C {@code count}). */
    public double count(int s) { return count[s]; }

    public float score(int s) { return score[s]; }

    /** HUD text of slot s (empty when none). */
    public CharBuf hudText(int s) { return hud[s]; }

    /** Game-area text of slot s (the points). */
    public CharBuf text(int s) { return text[s]; }

    /** True when the block position is known (C draws the points there, blinking: count % 3 != 0). */
    public boolean hasPosition(int s) { return hasPos[s]; }

    public int blockX(int s) { return bx[s]; }

    public int blockY(int s) { return by[s]; }
}
