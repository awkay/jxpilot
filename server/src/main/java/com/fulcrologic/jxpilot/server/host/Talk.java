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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * C {@code Handle_talk} (netserver.c): a chat line from a player becomes MESSAGE events, for everyone, a team
 * or one player. Writes only to {@link World#events}; allocation free.
 * <p>
 * Without a {@code ':'} (or with it first, or followed by a smiley character {@code -_~)(/\}{[]} or nothing)
 * the line goes to everyone as {@code "text [sender]"}. Otherwise the part before the colon addresses it:
 * digits are a team ({@code "text [sender]:[team]"} to every member and the sender), {@code god} is the
 * server log (not ported: dropped), anything else a player by name, exact first, then by unique prefix
 * ({@code "text [sender]:[receiver]"} to both). C also matches the real name; JXPilot has none.
 */
final class Talk {
    /** C {@code strchr("-_~)(/\\}{[]", cp[1])}: smileys are smileys (the terminating NUL matches too). */
    static final String SMILEY = "-_~)(/\\}{[]";

    private final World w;

    Talk(World w) {
        this.w = w;
    }

    /**
     * Handle one chat line of {@code from}.
     * <p>
     * BEHAVIOR: no addressing colon: one MESSAGE for everyone (KIND_CHAT, from = sender).
     * BEHAVIOR: a team (all digits): KIND_TEAM_CHAT to each player in the game on that team, and to the sender
     * when it is not on it but someone got it; nobody on the team: "Message not sent, nobody in team N!" to the
     * sender (KIND_SERVER).
     * BEHAVIOR: "god" (any case): dropped.
     * BEHAVIOR: a player: KIND_PRIVATE to the receiver and the sender, unless they are the same (nothing);
     * "Message not sent, X matches more than one player!" / "... does not match any player!" to the sender.
     */
    void say(Player from, CharSequence line) {
        int len = line.length();
        int colon = indexOf(line, ':');
        if (colon <= 0 || SMILEY.indexOf(colon + 1 < len ? line.charAt(colon + 1) : '\0') >= 0) {
            body(w.events.message(Msg.Text.KIND_CHAT, from.id), line, 0, len, from);
            return;
        }
        if (allDigits(line, 0, colon)) {
            team(from, line, colon, parseTeam(line, colon));
        } else if (colon == 3 && equalsIgnoreCase("god", line, 0, colon)) {
            return;
        } else {
            player(from, line, colon);
        }
    }

    private void team(Player from, CharSequence line, int colon, long team) {
        int sent = 0;
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (p.team != Const.TEAM_NOT_SET && p.team == team) {
                sent++;
                teamLine(p.id, from, line, colon, team);
            }
        }
        if (sent > 0) {
            if (from.team != team) {
                teamLine(from.id, from, line, colon, team);
            }
        } else {
            w.events.playerMessage(Msg.Text.KIND_SERVER, Obj.NO_ID, from.id).append("Message not sent, nobody in team ")
                .append(team).append('!');
        }
    }

    private void teamLine(int to, Player from, CharSequence line, int colon, long team) {
        StringBuilder sb = w.events.playerMessage(Msg.Text.KIND_TEAM_CHAT, from.id, to);
        body(sb, line, colon + 1, line.length(), from).append(":[").append(team).append(']');
    }

    private void player(Player from, CharSequence line, int colon) {
        Player to = null;
        for (int k = 0; k < w.numPlayers && to == null; k++) {
            Player p = w.playerAt(k);
            if (p.name.length() == colon && equalsIgnoreCase(p.name, line, 0, colon)) {
                to = p;
            }
        }
        boolean ambiguous = false;
        if (to == null) {
            for (int k = 0; k < w.numPlayers; k++) {
                Player p = w.playerAt(k);
                if (p.name.length() >= colon && equalsIgnoreCase(p.name, line, 0, colon)) {
                    ambiguous = to != null;
                    to = p;
                }
            }
        }
        if (ambiguous || to == null) {
            w.events.playerMessage(Msg.Text.KIND_SERVER, Obj.NO_ID, from.id).append("Message not sent, ")
                .append(line, 0, colon).append(ambiguous ? " matches more than one player!" : " does not match any player!");
            return;
        }
        if (to == from) {
            return;
        }
        privateLine(to.id, from, to, line, colon);
        privateLine(from.id, from, to, line, colon);
    }

    private void privateLine(int receiver, Player from, Player to, CharSequence line, int colon) {
        StringBuilder sb = w.events.playerMessage(Msg.Text.KIND_PRIVATE, from.id, receiver);
        body(sb, line, colon + 1, line.length(), from).append(":[").append(to.name).append(']');
    }

    /** {@code "text [sender]"}. */
    private static StringBuilder body(StringBuilder sb, CharSequence line, int start, int end, Player from) {
        return sb.append(line, start, end).append(" [").append(from.name).append(']');
    }

    static int indexOf(CharSequence s, char c) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    static boolean allDigits(CharSequence s, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** C {@code atoi} of the leading digits; long so a silly number cannot overflow into a real team. */
    static long parseTeam(CharSequence s, int end) {
        long v = 0;
        for (int i = 0; i < end && v < Integer.MAX_VALUE; i++) {
            v = v * 10 + (s.charAt(i) - '0');
        }
        return v;
    }

    /** BEHAVIOR: {@code a[0..n)} equals {@code s[start..start+n)} ignoring case (C {@code strncasecmp}). */
    static boolean equalsIgnoreCase(CharSequence a, CharSequence s, int start, int n) {
        if (a.length() < n || s.length() < start + n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            char x = a.charAt(i);
            char y = s.charAt(start + i);
            if (x != y && Character.toLowerCase(x) != Character.toLowerCase(y)) {
                return false;
            }
        }
        return true;
    }

    /** Events MESSAGE c value meaning "everyone". */
    static final int EVERYONE = 0;

    /** BEHAVIOR: whether a MESSAGE event at ring index {@code i} goes to {@code viewerId}. */
    static boolean addressedTo(Events e, int i, int viewerId) {
        return e.c[i] == EVERYONE || e.c[i] == viewerId;
    }
}
