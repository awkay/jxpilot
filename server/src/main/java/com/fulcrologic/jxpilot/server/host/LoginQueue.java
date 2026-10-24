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
import com.fulcrologic.jxpilot.server.world.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The login queue of C contact.c ({@code Queue_player}, {@code Queue_loop}, {@code Queue_show_list},
 * {@code Queue_advance_player}): players who find the game full wait in line. A waiting player keeps asking (the
 * client resends CONNECT, C ENTER_QUEUE) and is told its position; when a base frees up the first waiting player is
 * admitted: a slot is reserved for it (C {@code Setup_connection}, counted as {@code login_in_progress}), and its
 * next request gets in. An entry nobody asks for within 30*FPS ticks is dropped.
 * <p>
 * The entries are in C's list order; positions count only the players still waiting. Runs between ticks and on
 * requests; allocation free except when an entry is added.
 */
final class LoginQueue {
    /** C {@code MaxQueuedPlayers}. */
    static final int MAX_QUEUED = 20;

    /** One {@code struct queued_player}. */
    static final class Entry {
        final String name;
        final long source;
        int team;
        String shape;
        long lastRequest;
        /** The slot reserved at admission (C {@code login_port != -1}), else null. */
        Player admitted;

        Entry(String name, long source, int team, String shape, long now) {
            this.name = name;
            this.source = source;
            this.team = team;
            this.shape = shape;
            this.lastRequest = now;
        }
    }

    final List<Entry> entries = new ArrayList<>();
    /** C {@code last_unqueued_loops}. */
    long lastUnqueued = Long.MIN_VALUE / 2;

    /** The entry for {@code name} (C {@code strcmp} on the nick), or null. */
    Entry find(CharSequence name) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.name.contentEquals(name)) {
                return e;
            }
        }
        return null;
    }

    /** C {@code NumQueuedPlayers}. */
    int size() {
        return entries.size();
    }

    /** Players still waiting (not admitted). */
    int waiting() {
        int n = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.admitted == null) {
                n++;
            }
        }
        return n;
    }

    /** BEHAVIOR: the 1-based position among the waiting players (C qpos); 0 for an admitted entry. */
    int position(Entry x) {
        if (x.admitted != null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.admitted == null) {
                n++;
            }
            if (e == x) {
                return n;
            }
        }
        return 0;
    }

    /** BEHAVIOR: entries from {@code source} (C counts entries with the same host address). */
    int fromSource(long source) {
        int n = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.source == source) {
                n++;
            }
        }
        return n;
    }

    /** The first entry still waiting, or null. */
    Entry head() {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.admitted == null) {
                return e;
            }
        }
        return null;
    }

    /**
     * C {@code Queue_show_list}.
     * <p>
     * BEHAVIOR: "The queue is empty." without entries; else "Queue: " and "N. name  " for each entry, in order,
     * while the text is shorter than MSG_LEN - 32, with the last two spaces cut.
     */
    void showList(StringBuilder msg) {
        if (entries.isEmpty()) {
            msg.append("The queue is empty.");
            return;
        }
        int start = msg.length();
        msg.append("Queue: ");
        int count = 1;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            msg.append(count++).append(". ").append(e.name).append("  ");
            if (msg.length() - start + 32 >= Const.MSG_LEN) {
                break;
            }
        }
        msg.setLength(msg.length() - 2);
    }

    /**
     * C {@code Queue_advance_player}: move {@code name} (any case) to the front of the waiting players.
     * <p>
     * BEHAVIOR: a name of 16 characters or more: "Name too long." (false). The first entry: "Already first.";
     * an admitted one: "Already entering game."; otherwise it moves right after the last admitted entry before
     * it (to the top when there is none): "Done.". Not found: "Player \"NAME\" not in queue.". True except for
     * the long name.
     */
    boolean advance(String name, StringBuilder msg) {
        if (name.length() >= Const.MAX_NAME_LEN) {
            msg.append("Name too long.");
            return false;
        }
        int first = -1;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.name.equalsIgnoreCase(name)) {
                if (i == 0) {
                    msg.append("Already first.");
                } else if (e.admitted != null) {
                    msg.append("Already entering game.");
                } else {
                    entries.remove(i);
                    entries.add(first + 1, e);
                    msg.append("Done.");
                }
                return true;
            } else if (e.admitted != null) {
                first = i;
            }
        }
        msg.append("Player \"").append(name).append("\" not in queue.");
        return true;
    }
}
