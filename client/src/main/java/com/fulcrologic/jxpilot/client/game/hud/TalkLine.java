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
 * The talk line editor of C {@code talk.c}: up to {@link #MAX_CHARS} characters with a cursor,
 * insert/delete editing and a history of sent lines browsed with the talk cursor up/down keys
 * (C {@code Get_msg_from_history}: up goes to older lines, down to newer, both wrap, empty slots are
 * skipped). Allocation free.
 */
public final class TalkLine {
    /** C {@code MAX_CHARS}. */
    public static final int MAX_CHARS = 80;
    /** C default {@code maxLinesInHistory}. */
    public static final int HISTORY = 32;

    private final CharBuf line = new CharBuf(MAX_CHARS);
    private final CharBuf[] history = new CharBuf[HISTORY];
    private int cursor;
    private boolean open;
    private int historyPos;

    public TalkLine() {
        for (int i = 0; i < HISTORY; i++) {
            history[i] = new CharBuf(MAX_CHARS);
        }
    }

    public boolean isOpen() { return open; }

    /** Current text. */
    public CharBuf text() { return line; }

    /** Cursor position 0..length. */
    public int cursor() { return cursor; }

    /** History entry i, 0 = most recently sent. */
    public CharBuf history(int i) { return history[i]; }

    /** BEHAVIOR: opens the line (text kept from an aborted edit, cursor at its end). */
    public void open() {
        open = true;
        cursor = line.length();
        historyPos = -1;
    }

    /** BEHAVIOR: closes without sending; the text is kept for next time (C keeps an unfinished line). */
    public void cancel() {
        open = false;
    }

    /**
     * BEHAVIOR: inserts a printable Latin-1 char (32..126, 160..255) at the cursor when not full;
     * others are ignored; returns whether it was inserted.
     */
    public boolean type(int codepoint) {
        if (!open || line.length() >= MAX_CHARS) {
            return false;
        }
        if (codepoint < 32 || codepoint > 255 || (codepoint > 126 && codepoint < 160)) {
            return false;
        }
        char[] c = line.array();
        int n = line.length();
        System.arraycopy(c, cursor, c, cursor + 1, n - cursor);
        c[cursor] = (char) codepoint;
        line.setLength(n + 1);
        cursor++;
        return true;
    }

    /** BEHAVIOR: deletes the char before the cursor (no-op at the start). */
    public void backspace() {
        if (cursor > 0) {
            cursor--;
            deleteAt(cursor);
        }
    }

    /** BEHAVIOR: deletes the char under the cursor (no-op at the end). */
    public void delete() {
        if (cursor < line.length()) {
            deleteAt(cursor);
        }
    }

    private void deleteAt(int i) {
        char[] c = line.array();
        int n = line.length();
        System.arraycopy(c, i + 1, c, i, n - i - 1);
        line.truncate(n - 1);
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

    /**
     * BEHAVIOR: up = older history line, wrapping to the newest after the oldest; empty slots are
     * skipped; nothing happens when the history is empty. The line and cursor (at the end) are replaced.
     */
    public void historyUp() {
        browse(1);
    }

    /** BEHAVIOR: down = newer history line, wrapping (see {@link #historyUp}). */
    public void historyDown() {
        browse(-1);
    }

    private void browse(int dir) {
        int pos = historyPos;
        for (int i = 0; i < HISTORY; i++) {
            pos += dir;
            if (pos >= HISTORY) {
                pos = 0;
            } else if (pos < 0) {
                pos = HISTORY - 1;
            }
            if (history[pos].length() > 0) {
                historyPos = pos;
                line.clear().append(history[pos]);
                cursor = line.length();
                return;
            }
        }
    }

    /**
     * Finish the line (Return).
     * <p>
     * BEHAVIOR: copies the text into {@code out}, pushes it onto the history (newest first, oldest
     * dropped), clears the line and closes; returns false (nothing copied, line closed) when empty.
     */
    public boolean submit(StringBuilder out) {
        open = false;
        if (line.length() == 0) {
            return false;
        }
        out.setLength(0);
        out.append(line.array(), 0, line.length());
        CharBuf oldest = history[HISTORY - 1];
        System.arraycopy(history, 0, history, 1, HISTORY - 1);
        history[0] = oldest.clear().append(line);
        line.clear();
        cursor = 0;
        historyPos = -1;
        return true;
    }
}
