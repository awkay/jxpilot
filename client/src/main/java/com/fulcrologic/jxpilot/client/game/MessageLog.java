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

import com.fulcrologic.jxpilot.common.net.Msg;

/**
 * Talk and game message lists as in C {@code Add_message}/{@code Paint_messages}
 * ({@code src/client/painthud.c}): two lists of {@code maxMessages} entries, newest first. Talk messages
 * (player chat) are drawn from the top of the view, game/server messages from the bottom up. Each
 * message lives {@link #MSG_DURATION} original frames, is drawn flashing (red) while its life is above
 * {@link #MSG_FLASH}, and types out at {@code charsPerSecond}. Allocation free after construction.
 */
public final class MessageLog {
    /** C {@code MSG_DURATION} (common/draw.h), original frames. */
    public static final int MSG_DURATION = 1024;
    /** C {@code MSG_FLASH}: flashing while life &gt; this. */
    public static final int MSG_FLASH = 892;
    /** C {@code MAX_MSGS}. */
    public static final int MAX_MSGS = 15;
    /** C default {@code maxMessages}. */
    public static final int DEFAULT_MAX_MESSAGES = 8;
    /** C default {@code charsPerSecond}. */
    public static final int DEFAULT_CHARS_PER_SECOND = 50;
    /** C {@code MSG_LEN}. */
    public static final int MSG_LEN = 256;

    /** One message. */
    public static final class Entry {
        final CharBuf text = new CharBuf(MSG_LEN);
        double life;
        int kind;

        /** The message text. */
        public CharBuf text() { return text; }

        /** Remaining life in original frames. */
        public double life() { return life; }

        /** {@link Msg.Text} kind. */
        public int kind() { return kind; }

        /** BEHAVIOR: true while life &gt; {@link #MSG_FLASH} (drawn red in C). */
        public boolean flashing() { return life > MSG_FLASH; }
    }

    private final int max;
    private final Entry[] talk;
    private final Entry[] game;
    private int talkCount;
    private int gameCount;
    private int charsPerSecond = DEFAULT_CHARS_PER_SECOND;

    /** BEHAVIOR: maxMessages is clamped to 1..{@link #MAX_MSGS}. */
    public MessageLog(int maxMessages) {
        max = Math.max(1, Math.min(MAX_MSGS, maxMessages));
        talk = new Entry[max];
        game = new Entry[max];
        for (int i = 0; i < max; i++) {
            talk[i] = new Entry();
            game[i] = new Entry();
        }
    }

    public MessageLog() {
        this(DEFAULT_MAX_MESSAGES);
    }

    public int maxMessages() { return max; }

    public void setCharsPerSecond(int cps) { charsPerSecond = cps; }

    public int talkCount() { return talkCount; }

    public int gameCount() { return gameCount; }

    /** Talk message i, 0 = newest. */
    public Entry talk(int i) { return talk[i]; }

    /** Game message i, 0 = newest. */
    public Entry game(int i) { return game[i]; }

    /** BEHAVIOR: forgets all messages. */
    public void clear() {
        talkCount = 0;
        gameCount = 0;
    }

    /**
     * BEHAVIOR: chat kinds (CHAT, TEAM_CHAT, PRIVATE) are talk messages; other kinds are talk messages
     * when the text ends with ']' or starts with " &lt;" (C's rule), else game messages.
     */
    public static boolean isTalk(int kind, CharSequence text) {
        if (kind == Msg.Text.KIND_CHAT || kind == Msg.Text.KIND_TEAM_CHAT || kind == Msg.Text.KIND_PRIVATE) {
            return true;
        }
        int n = text.length();
        return n > 0 && text.charAt(n - 1) == ']' || n >= 2 && text.charAt(0) == ' ' && text.charAt(1) == '<';
    }

    /**
     * Add a message.
     * <p>
     * BEHAVIOR: goes to the front of its list with life {@link #MSG_DURATION}; the oldest falls off when
     * the list is full; text is truncated to {@link #MSG_LEN} chars; empty text is ignored.
     */
    public void add(int kind, CharSequence text) {
        if (text.length() == 0) {
            return;
        }
        boolean isTalk = isTalk(kind, text);
        Entry[] set = isTalk ? talk : game;
        Entry e = set[max - 1];
        System.arraycopy(set, 0, set, 1, max - 1);
        set[0] = e;
        e.text.clear().append(text);
        e.life = MSG_DURATION;
        e.kind = kind;
        if (isTalk) {
            talkCount = Math.min(max, talkCount + 1);
        } else {
            gameCount = Math.min(max, gameCount + 1);
        }
    }

    /**
     * Age all messages by {@code frames} original frames.
     * <p>
     * BEHAVIOR: a message whose life drops below 0 is removed (older ones are older still, so the
     * list is truncated there).
     */
    public void tick(double frames) {
        talkCount = age(talk, talkCount, frames);
        gameCount = age(game, gameCount, frames);
    }

    private static int age(Entry[] set, int n, double frames) {
        for (int i = 0; i < n; i++) {
            set[i].life -= frames;
            if (set[i].life < 0) {
                return i;
            }
        }
        return n;
    }

    /**
     * Characters shown while the message types out: C {@code charsPerTick * (MSG_DURATION - life)} with
     * charsPerTick = charsPerSecond / FPS.
     * <p>
     * BEHAVIOR: (int)(charsPerSecond / fps * (MSG_DURATION - life)), at most the text length.
     */
    public int visibleChars(Entry e, double fps) {
        int n = (int) (charsPerSecond / fps * (MSG_DURATION - e.life));
        return Math.min(e.text.length(), Math.max(0, n));
    }
}
