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

import com.fulcrologic.jxpilot.client.game.CharBuf;

/**
 * The talk line editor of C {@code talk.c} ({@code Talk_do_event}, with selectionAndHistory on, its
 * default): at most {@link #MAX_CHARS}-2 characters and, when a width limit is set, no wider than the
 * talk window; a cursor; Backspace, DEL (also backspace, as X delivers it) and the Ctrl-A/B/D/E/F/K/U/W
 * line editing keys; a history of sent lines browsed with the talk cursor up/down keys. Allocation free.
 * <p>
 * History (C {@code Add_msg_to_history}/{@code Get_msg_from_history}): a line is saved only when
 * something was typed into it (a line recalled from the history and sent unchanged is not saved again);
 * Up/Down with an unfinished typed line first saves it and clears the line; otherwise Up goes to older
 * lines and Down to newer, both wrapping and skipping empty slots. The repeat-key "hopping" erase of C is not reproduced.
 */
public final class TalkLine {
    /** C {@code MAX_CHARS}; the line holds at most MAX_CHARS - 2. */
    public static final int MAX_CHARS = 80;
    /** Longest line (C {@code oldlen >= MAX_CHARS - 2} refuses more). */
    public static final int MAX_LEN = MAX_CHARS - 2;
    /** C default {@code maxLinesInHistory}. */
    public static final int HISTORY = 32;
    /** C {@code MAX_HIST_MSGS}: the largest {@code maxLinesInHistory}. */
    public static final int MAX_HISTORY = 128;

    private final CharBuf line = new CharBuf(MAX_CHARS);
    private final CharBuf[] history = new CharBuf[MAX_HISTORY];
    /** C {@code maxLinesInHistory}. */
    private int historySize = HISTORY;
    private int cursor;
    private boolean open;
    private int historyPos = -1;
    /** C {@code save_talk_str}: something was typed since the line was last saved, sent or cleared. */
    private boolean typed;
    private TextMetrics metrics;
    private int maxWidth;

    public TalkLine() {
        for (int i = 0; i < MAX_HISTORY; i++) {
            history[i] = new CharBuf(MAX_CHARS);
        }
    }

    /**
     * C {@code maxLinesInHistory}.
     * <p>
     * BEHAVIOR: clamped to 1..{@link #MAX_HISTORY}; lines beyond the new size are forgotten; browsing restarts.
     */
    public void setHistorySize(int n) {
        historySize = Math.max(1, Math.min(MAX_HISTORY, n));
        for (int i = historySize; i < MAX_HISTORY; i++) {
            history[i].clear();
        }
        historyPos = -1;
    }

    public int historySize() { return historySize; }

    public boolean isOpen() { return open; }

    /** Current text. */
    public CharBuf text() { return line; }

    /** Cursor position 0..length. */
    public int cursor() { return cursor; }

    /** History entry i, 0 = most recently saved. */
    public CharBuf history(int i) { return history[i]; }

    /** True when the line holds typed text that is not yet in the history. */
    public boolean typed() { return typed; }

    /**
     * Also refuse input once the text is as wide as the talk window allows (C
     * {@code TALK_WINDOW_WIDTH - 2*TALK_INSIDE_BORDER - 5}).
     *
     * @param windowWidth the talk window width ({@link Hud#talkWindowWidth})
     */
    public void limitWidth(TextMetrics m, int windowWidth) {
        metrics = m;
        maxWidth = windowWidth - 2 * Hud.TALK_INSIDE_BORDER - 5;
    }

    /** BEHAVIOR: opens the line (text kept from a line closed with the talk key, cursor at its end). */
    public void open() {
        open = true;
        cursor = line.length();
        historyPos = -1;
    }

    /** The talk key while open (C toggles the window): closes and keeps the text. */
    public void close() {
        open = false;
        historyPos = -1;
    }

    /** BEHAVIOR: Escape: clears the text (nothing is saved) and closes. */
    public void escape() {
        line.clear();
        cursor = 0;
        typed = false;
        close();
    }

    /**
     * C {@code Talk_paste} (not overwriting): the text goes in at the cursor as far as the line takes it.
     * <p>
     * BEHAVIOR: newlines and tabs become blanks, other unprintable chars are dropped; chars are inserted as
     * {@link #type} does until the line refuses one (full or too wide); returns how many went in; nothing while the
     * line is closed.
     */
    public int paste(CharSequence text) {
        int n = 0;
        for (int i = 0; i < text.length() && open; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                c = ' ';
            }
            if (c < 32 || c > 255 || (c > 126 && c < 160)) {
                continue;
            }
            if (!type(c)) {
                break;
            }
            n++;
        }
        return n;
    }

    /** BEHAVIOR: C {@code Talk_place_cursor}: the cursor goes to {@code index}, clamped to 0..length. */
    public void setCursor(int index) {
        cursor = Math.max(0, Math.min(line.length(), index));
    }

    /**
     * BEHAVIOR: inserts a printable Latin-1 char (32..126, 160..255) at the cursor, marking the line as
     * typed; refused (false) when the line has {@link #MAX_LEN} chars or, with a width limit, is already
     * that wide; other codes are ignored.
     */
    public boolean type(int codepoint) {
        if (!open || codepoint < 32 || codepoint > 255 || (codepoint > 126 && codepoint < 160)) {
            return false;
        }
        typed = true;
        int n = line.length();
        if (n >= MAX_LEN || metrics != null && metrics.width(line, n) >= maxWidth) {
            return false;
        }
        char[] c = line.array();
        System.arraycopy(c, cursor, c, cursor + 1, n - cursor);
        c[cursor] = (char) codepoint;
        line.setLength(n + 1);
        cursor++;
        return true;
    }

    /** BEHAVIOR: Backspace: deletes the char before the cursor (no-op at the start). */
    public void backspace() {
        if (cursor > 0) {
            cursor--;
            deleteAt(cursor);
        }
    }

    /** BEHAVIOR: DEL: X delivers it as '\177', which C erases like Backspace. */
    public void delete() {
        backspace();
    }

    private void deleteAt(int i) {
        char[] c = line.array();
        int n = line.length();
        System.arraycopy(c, i + 1, c, i, n - i - 1);
        line.truncate(n - 1);
    }

    /**
     * A control key, C {@code Talk_do_event}.
     * <p>
     * BEHAVIOR: A start, E end, B back, F forward; D erases like C (the char under the cursor, but with the
     * cursor on or after the last char the one before it, and nothing at position 0); W erases trailing
     * blanks and then the last word of the line; U erases the line; K erases from the cursor to the end.
     * The cursor is clamped to the new length. Returns false for other letters.
     */
    public boolean control(char letter) {
        int n = line.length();
        switch (Character.toUpperCase(letter)) {
            case 'A' -> home();
            case 'E' -> end();
            case 'B' -> left();
            case 'F' -> right();
            case 'D' -> {
                if (cursor > 0) {
                    if (cursor >= n - 1) {
                        cursor--;
                    }
                    deleteAt(cursor);
                }
            }
            case 'W' -> {
                int k = n;
                while (k > 0 && line.array()[k - 1] == ' ') {
                    k--;
                }
                while (k > 0 && line.array()[k - 1] != ' ') {
                    k--;
                }
                cut(k);
            }
            case 'U' -> cut(0);
            case 'K' -> cut(cursor);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void cut(int len) {
        line.truncate(len);
        cursor = Math.min(cursor, line.length());
    }

    /** BEHAVIOR: moves the cursor one left, stopping at 0. */
    public void left() {
        if (cursor > 0) {
            cursor--;
        }
    }

    /** BEHAVIOR: moves the cursor one right, stopping at the end. */
    public void right() {
        if (cursor < line.length()) {
            cursor++;
        }
    }

    public void home() {
        cursor = 0;
    }

    public void end() {
        cursor = line.length();
    }

    /** BEHAVIOR: talk cursor up: older history line (see the class comment). */
    public void historyUp() {
        browse(1);
    }

    /** BEHAVIOR: talk cursor down: newer history line (see the class comment). */
    public void historyDown() {
        browse(-1);
    }

    private void browse(int dir) {
        if (typed) {
            if (line.length() > 0) {
                save();
            }
            typed = false;
            clearLine();
            return;
        }
        int pos = historyPos;
        for (int i = 0; i < historySize; i++) {
            pos += dir;
            if (pos >= historySize) {
                pos = 0;
            } else if (pos < 0) {
                pos = historySize - 1;
            }
            if (history[pos].length() > 0) {
                historyPos = pos;
                line.clear().append(history[pos]);
                cursor = line.length();
                return;
            }
        }
        // empty history: the line is empty too (any text in it would be typed text, handled above)
    }

    private void clearLine() {
        line.clear();
        cursor = 0;
    }

    /** C {@code Add_msg_to_history}: newest first, oldest dropped; browsing restarts at the newest. */
    private void save() {
        CharBuf oldest = history[historySize - 1];
        System.arraycopy(history, 0, history, 1, historySize - 1);
        history[0] = oldest.clear().append(line);
        historyPos = 0;
        typed = false;
    }

    /**
     * Finish the line (Return).
     * <p>
     * BEHAVIOR: with text: copies it into {@code out}, saves it in the history iff it was typed, clears
     * the line and returns true. Empty: returns false with nothing copied. Closes either way.
     */
    public boolean submit(StringBuilder out) {
        boolean sent = line.length() > 0;
        if (sent) {
            out.setLength(0);
            out.append(line.array(), 0, line.length());
            if (typed) {
                save();
            }
            clearLine();
        }
        typed = false;
        close();
        return sent;
    }
}
