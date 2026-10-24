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
package com.fulcrologic.jxpilot.client.game.hud;

/**
 * A mouse selection of text (C {@code selectionAndHistory}, {@code talk.c} {@code Talk_window_cut} and
 * {@code Talk_cut_from_messages}): in the talk line, or across the lines of one message list. A selection has an
 * anchor (where the button went down) and a head (where the pointer is); it is drawn emphasized (C
 * {@code DRAW_EMPHASIZED}, blue) and its text goes to the clipboard when the button is released. Lines are the
 * message list indices (0 = newest); in the game list they run bottom-up on the screen. Pure; allocation free.
 */
public final class TextSelection {
    /** The talk line (line 0 only). */
    public static final int TALK_LINE = 0;
    /** The talk messages at the top of the view (index 0 at the top). */
    public static final int TALK_MESSAGES = 1;
    /** The game messages at the bottom (index 0 at the bottom). */
    public static final int GAME_MESSAGES = 2;

    private int area = -1;
    private int anchorLine;
    private int anchorChar;
    private int headLine;
    private int headChar;
    private boolean dragging;
    /** C reverseScroll: the message lists run the other way on the screen. */
    private boolean reverse;

    /** BEHAVIOR: whether the message lists are drawn reversed (C reverseScroll), which flips their screen order. */
    public void setReverse(boolean r) {
        reverse = r;
    }

    /** BEHAVIOR: starts a selection at (line, char) of {@code area}, replacing any other; empty until dragged. */
    public void start(int area, int line, int ch) {
        this.area = area;
        anchorLine = line;
        anchorChar = ch;
        headLine = line;
        headChar = ch;
        dragging = true;
    }

    /** BEHAVIOR: while dragging, moves the head within the same area (other areas are ignored). */
    public void drag(int area, int line, int ch) {
        if (dragging && area == this.area) {
            headLine = line;
            headChar = ch;
        }
    }

    /** BEHAVIOR: ends the drag; the selection stays shown. */
    public void finish() {
        dragging = false;
    }

    /** BEHAVIOR: forgets the selection. */
    public void clear() {
        area = -1;
        dragging = false;
    }

    public boolean dragging() { return dragging; }

    /** The area selected in, or -1. */
    public int area() { return area; }

    /** BEHAVIOR: true iff something is selected in {@code area} (anchor and head differ). */
    public boolean selects(int area) {
        return this.area == area && (anchorLine != headLine || anchorChar != headChar);
    }

    /** Screen order of a line: top to bottom. */
    private int order(int line) {
        boolean up = area == GAME_MESSAGES;
        return up != (reverse && area != TALK_LINE) ? -line : line;
    }

    private boolean anchorFirst() {
        int a = order(anchorLine);
        int h = order(headLine);
        return a < h || a == h && anchorChar <= headChar;
    }

    /**
     * The selected chars of one line, as [from, to).
     * <p>
     * BEHAVIOR: for a line of {@code len} chars in the selection's area: the first line from its char, the last
     * up to its char, lines between whole, a single line between the two chars; chars clamped to 0..len; lines
     * outside the selection (or other areas) give from == to == 0.
     */
    public int from(int area, int line, int len) {
        if (!selects(area)) {
            return 0;
        }
        int[] r = range(line, len);
        return r[0];
    }

    /** See {@link #from}. */
    public int to(int area, int line, int len) {
        if (!selects(area)) {
            return 0;
        }
        int[] r = range(line, len);
        return r[1];
    }

    private final int[] scratch = new int[2];

    private int[] range(int line, int len) {
        boolean af = anchorFirst();
        int fl = af ? anchorLine : headLine;
        int fc = af ? anchorChar : headChar;
        int ll = af ? headLine : anchorLine;
        int lc = af ? headChar : anchorChar;
        int o = order(line);
        if (o < order(fl) || o > order(ll)) {
            scratch[0] = 0;
            scratch[1] = 0;
            return scratch;
        }
        int a = o == order(fl) ? fc : 0;
        int b = o == order(ll) ? lc : len;
        scratch[0] = Math.max(0, Math.min(len, a));
        scratch[1] = Math.max(scratch[0], Math.min(len, b));
        return scratch;
    }

    /** The first and last line of the selection in screen order (top line first). */
    public int topLine() {
        return anchorFirst() ? anchorLine : headLine;
    }

    public int bottomLine() {
        return anchorFirst() ? headLine : anchorLine;
    }

    /**
     * C's cut text: the selected chars of each line top to bottom, lines joined by a newline.
     * <p>
     * BEHAVIOR: the talk line's selected chars; for messages, each selected line's chars from {@code log}; lines
     * beyond the list's count are skipped; empty when nothing is selected.
     */
    public String text(TalkLine talk, com.fulcrologic.jxpilot.client.game.MessageLog log) {
        StringBuilder sb = new StringBuilder();
        if (selects(TALK_LINE)) {
            CharSequence s = talk.text();
            sb.append(s, from(TALK_LINE, 0, s.length()), to(TALK_LINE, 0, s.length()));
        } else if (selects(TALK_MESSAGES) || selects(GAME_MESSAGES)) {
            int top = topLine();
            int bottom = bottomLine();
            int step = (area == GAME_MESSAGES) != reverse ? -1 : 1;
            int count = area == GAME_MESSAGES ? log.gameCount() : log.talkCount();
            for (int line = top; ; line += step) {
                if (line >= 0 && line < count) {
                    CharSequence s = (area == GAME_MESSAGES ? log.game(line) : log.talk(line)).text();
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(s, from(area, line, s.length()), to(area, line, s.length()));
                }
                if (line == bottom) {
                    break;
                }
            }
        }
        return sb.toString();
    }
}
