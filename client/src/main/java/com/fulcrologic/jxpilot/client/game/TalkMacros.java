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

import java.util.Locale;

/**
 * XPilot's talk macros ({@code msg1..msg20}, sent by {@code keySendMsg1..20}): a port of
 * {@code client/talkmacros.c} {@code Talk_macro_parse_mesg}, quirks included.
 * <p>
 * Plain characters are copied ({@code '\n'} is dropped); {@code #} starts an escape:
 * <ul>
 * <li>{@code ##} a '#'; {@code #t} the own team digit (team play only); {@code #h} $HOME;</li>
 * <li>{@code #s} / {@code #l} select the own player / the locked player (the watched one while snooping); the
 *     next character is that player's field: {@code n} name, {@code s} score ("%.2f"), {@code t} team digit
 *     (team play), {@code l} lives digit (limited lives); without a locked player the whole message is
 *     dropped;</li>
 * <li>{@code #n} sends what is collected so far as a line of its own;</li>
 * <li>{@code #r[a|b|c]} one field at random; {@code #=[x|y|then|else]} compares the expanded x and y
 *     (20 chars each) and expands then or else; {@code #f[file]} expands the file's contents.</li>
 * </ul>
 * A line reaching {@link #MAX_CHARS} - 2 characters is sent and a new one started. Fields nest by
 * {@code [ ]}. JXPilot only: expansion stops {@link #MAX_DEPTH} files or fields deep (C recursed until it
 * crashed). Allocates (a key press, not the frame loop).
 */
public final class TalkMacros {
    /** C {@code TALK_FAST_NR_OF_MSGS}. */
    public static final int COUNT = 20;
    /** C {@code TALK_FAST_MSG_SIZE}: a macro resource keeps at most this many chars minus one. */
    public static final int MSG_SIZE = 400;
    /** C {@code MAX_CHARS}: the talk buffer. */
    public static final int MAX_CHARS = 80;
    /** C {@code MSG_PARSED_FIELD_LEN}: a compared field's buffer. */
    public static final int FIELD_LEN = 20;
    /** C {@code TALK_FAST_MSG_FNLEN}: the file name buffer. */
    public static final int FNLEN = 100;
    /** Nesting limit (JXPilot). */
    public static final int MAX_DEPTH = 16;

    static final char START = '[';
    static final char END = ']';
    static final char MIDDLE = '|';
    static final char SPECIAL = '#';

    /** What a macro reads and does. */
    public interface Context {
        /** The player table. */
        Players players();

        /** Own player's slot, -1 when not known (C {@code self == NULL}). */
        int selfSlot();

        /** Slot of the locked player, or of the watched one while snooping; -1 when none. */
        int lockSlot();

        boolean teamPlay();

        boolean limitedLives();

        /** $HOME, null when unset. */
        String home();

        /** The file's contents, null when it cannot be read. */
        String readFile(String name);

        /** A random int in [0, n) (C {@code randomMT() % n}). */
        int random(int n);

        /** Send one talk line (C {@code Net_talk}). */
        void talk(CharSequence line);

        /** C {@code error()}: a diagnostic. */
        void error(String text);
    }

    private final Context ctx;

    public TalkMacros(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * C {@code Talk_macro}: expand {@code macro} and send it.
     * <p>
     * BEHAVIOR: a null macro does nothing; the expansion's last line is sent iff it is not empty (lines that
     * {@code #n} or the length limit ended were sent on the way).
     */
    public void send(String macro) {
        if (macro == null) {
            return;
        }
        char[] out = new char[MAX_CHARS];
        int n = parse(out, true, macro, 0, MAX_CHARS, 0);
        if (n > 0) {
            ctx.talk(new String(out, 0, n));
        }
    }

    /** C {@code Talk_macro_parse_mesg}: returns the new position in {@code out}. */
    int parse(char[] out, boolean fin, String in, int pos, int max, int depth) {
        if (depth > MAX_DEPTH) {
            return pos;
        }
        Players p = ctx.players();
        int player = -1;
        boolean done = false;
        int i = 0;
        while (!done && i < in.length() && in.charAt(i) != '\0') {
            char c = in.charAt(i++);
            if (pos >= max - 2) {
                if (!fin) {
                    break;
                }
                ctx.talk(new String(out, 0, pos));
                pos = 0;
            }
            if (player >= 0) {
                switch (c) {
                    case 'l' -> {
                        if (ctx.limitedLives()) {
                            out[pos++] = (char) (p.lives(player) + '0');
                        }
                    }
                    case 'n' -> {
                        CharSequence name = p.name(player);
                        for (int k = 0; k < name.length() && pos < max - 2; k++) {
                            out[pos++] = name.charAt(k);
                        }
                    }
                    case 's' -> {
                        if (pos < max - 1 - 6) {
                            String s = String.format(Locale.ROOT, "%.2f", p.score(player));
                            for (int k = 0; k < s.length() && pos < max - 1; k++) {
                                out[pos++] = s.charAt(k);
                            }
                        }
                    }
                    case 't' -> {
                        if (ctx.teamPlay()) {
                            out[pos++] = (char) (p.team(player) + '0');
                        }
                    }
                    default -> { }
                }
                player = -1;
                continue;
            }
            if (c == '\n') {
                continue;
            }
            if (c != SPECIAL) {
                out[pos++] = c;
                continue;
            }
            if (i >= in.length() || in.charAt(i) == '\0') {
                done = true;
                continue;
            }
            c = in.charAt(i++);
            switch (c) {
                case '=' -> {
                    int[] nf = new int[1];
                    int next = fieldsInfo(in, i, nf);
                    if (nf[0] < 3 || nf[0] > 4 || next < 0) {
                        break;
                    }
                    String a = expandField(in, i, 1, FIELD_LEN, depth);
                    String b = expandField(in, i, 2, FIELD_LEN, depth);
                    if (a.equals(b)) {
                        pos = parse(out, fin, field(in, i, 3), pos, max, depth + 1);
                    } else if (nf[0] == 4) {
                        pos = parse(out, fin, field(in, i, 4), pos, max, depth + 1);
                    }
                    i = next;
                }
                case 'f' -> {
                    int[] nf = new int[1];
                    int next = fieldsInfo(in, i, nf);
                    if (nf[0] != 1 || next < 0) {
                        break;
                    }
                    String raw = field(in, i, 1);
                    i = next;
                    String name = expandField(raw, 0, 0, FNLEN, depth);
                    String text = ctx.readFile(name);
                    if (text == null) {
                        ctx.error("Couldn't open file " + raw);
                        break;
                    }
                    pos = parse(out, fin, text, pos, max, depth + 1);
                }
                case 'h' -> {
                    String home = ctx.home();
                    for (int k = 0; home != null && k < home.length() && pos < max - 2; k++) {
                        out[pos++] = home.charAt(k);
                    }
                }
                case 'r' -> {
                    int[] nf = new int[1];
                    int next = fieldsInfo(in, i, nf);
                    if (nf[0] <= 0 || next < 0) {
                        break;
                    }
                    String f = field(in, i, ctx.random(nf[0]) + 1);
                    i = next;
                    pos = parse(out, fin, f, pos, max, depth + 1);
                }
                case 'n' -> {
                    ctx.talk(new String(out, 0, pos));
                    pos = 0;
                }
                case 'l' -> {
                    player = ctx.lockSlot();
                    if (player < 0) {
                        pos = 0;
                        done = true;
                    }
                }
                case 's' -> player = ctx.selfSlot();
                case 't' -> {
                    int self = ctx.selfSlot();
                    if (ctx.teamPlay() && self >= 0) {
                        out[pos++] = (char) (p.team(self) + '0');
                    }
                }
                case SPECIAL -> out[pos++] = c;
                default -> { }
            }
        }
        return pos;
    }

    /**
     * Field {@code wanted} expanded into a buffer of {@code len} (C parses into a fresh malloc'd buffer); field 0
     * is the whole of {@code in} from {@code from}.
     */
    private String expandField(String in, int from, int wanted, int len, int depth) {
        String raw = wanted == 0 ? in.substring(from) : field(in, from, wanted);
        char[] buf = new char[len];
        int n = parse(buf, false, raw, 0, len, depth + 1);
        return new String(buf, 0, n);
    }

    /**
     * C {@code Talk_macro_fields_info}: counts the top-level fields of the bracket group starting at or after
     * {@code from}.
     * <p>
     * BEHAVIOR: returns the index after the closing bracket, the field count in {@code n[0]}; -1 when the text
     * ends first or a ']' closes more than it opened.
     */
    static int fieldsInfo(String in, int from, int[] n) {
        n[0] = 0;
        int level = 0;
        for (int i = from; i < in.length() && in.charAt(i) != '\0'; i++) {
            switch (in.charAt(i)) {
                case START -> {
                    if (level++ == 0) {
                        n[0]++;
                    }
                }
                case MIDDLE -> {
                    if (level == 1) {
                        n[0]++;
                    }
                }
                case END -> {
                    level--;
                    if (level == 0) {
                        return i + 1;
                    }
                    if (level < 0) {
                        return -1;
                    }
                }
                default -> { }
            }
        }
        return -1;
    }

    /**
     * C {@code Talk_macro_get_field}: the raw text of top-level field {@code wanted} (1-based) of the group at or
     * after {@code from}.
     * <p>
     * BEHAVIOR: "" when the group has no such field; the text between its delimiters otherwise.
     */
    static String field(String in, int from, int wanted) {
        int level = 0;
        int field = 0;
        int start = -1;
        for (int i = from; i < in.length() && in.charAt(i) != '\0'; i++) {
            switch (in.charAt(i)) {
                case START -> {
                    if (level == 0) {
                        field++;
                        if (field == wanted) {
                            start = i + 1;
                        }
                    }
                    level++;
                }
                case MIDDLE -> {
                    if (level == 1) {
                        field++;
                        if (field == wanted) {
                            start = i + 1;
                        } else if (field == wanted + 1) {
                            return in.substring(start, i);
                        }
                    }
                }
                case END -> {
                    level--;
                    if (level == 0) {
                        return field == wanted && start >= 0 ? in.substring(start, i) : "";
                    }
                    if (level < 0) {
                        return "";
                    }
                }
                default -> { }
            }
        }
        return "";
    }
}
