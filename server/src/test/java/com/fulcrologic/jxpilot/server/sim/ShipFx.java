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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.util.ArrayList;
import java.util.List;

/** Shared scenario helpers for the ship/keys/scoring tests. */
final class ShipFx {
    private ShipFx() {}

    /** 60 fps (one tick = one C frame at every fps), no gravity. */
    static final String C_FRAMES = "framesPerSecond: 60\ngravity: 0\n";
    /** Default 14 fps, no gravity. */
    static final String FPS14 = "gravity: 0\n";

    /** 11x11 room, one base in the centre (block 5,5), far from every wall. */
    static final String[] ROOM = {
        "xxxxxxxxxxx",
        "x         x",
        "x         x",
        "x         x",
        "x         x",
        "x    _    x",
        "x         x",
        "x         x",
        "x         x",
        "x         x",
        "xxxxxxxxxxx",
    };

    static Simulation sim(String extra, String... rows) {
        return TestWorlds.sim(extra, rows);
    }

    /** Key mask of the given keys. */
    static long keys(ControlKey... ks) {
        long m = 0;
        for (ControlKey k : ks) {
            m |= k.mask();
        }
        return m;
    }

    /** One tick where only {@code pl} holds {@code mask} (others hold nothing). */
    static void tick(Simulation s, Player pl, long mask) {
        long[] in = new long[s.world.players.length];
        in[pl.slot] = mask;
        s.tick(in);
    }

    /** n ticks where {@code pl} holds {@code mask}. */
    static void ticks(Simulation s, Player pl, int n, long mask) {
        for (int i = 0; i < n; i++) {
            tick(s, pl, mask);
        }
    }

    /** Lower shields without any key traffic (abilities only). */
    static void unshield(Player pl) {
        pl.used &= ~Has.SHIELD;
    }

    /** Events from sequence {@code from} as "KIND a b c" strings (MESSAGE: "MESSAGE a b text"). */
    static List<String> events(World w, long from) {
        List<String> out = new ArrayList<>();
        Events e = w.events;
        for (long q = from; q < e.seq(); q++) {
            int i = Events.index(q);
            String k = switch (e.kind[i]) {
                case Events.JOIN -> "JOIN";
                case Events.LEAVE -> "LEAVE";
                case Events.TEAM -> "TEAM";
                case Events.SCORE -> "SCORE";
                case Events.TEAM_SCORE -> "TEAM_SCORE";
                case Events.MESSAGE -> "MESSAGE";
                case Events.BASE_OWNER -> "BASE_OWNER";
                case Events.GAME_EVENT -> "GAME_EVENT";
                case Events.THRUST -> "THRUST";
                case Events.SCORE_OBJECT -> "SCORE_OBJECT";
                default -> "?" + e.kind[i];
            };
            if (e.kind[i] == Events.MESSAGE) {
                out.add(k + " " + e.a[i] + " " + e.b[i] + " " + e.text[i]);
            } else if (e.kind[i] == Events.SCORE_OBJECT) {
                out.add(k + " " + e.a[i] + " " + e.b[i] + " " + e.c[i] + " " + e.value[i] + " " + e.text[i]);
            } else if (e.kind[i] == Events.SCORE || e.kind[i] == Events.TEAM_SCORE) {
                out.add(k + " " + e.a[i] + " " + e.b[i] + " " + e.c[i] + " " + e.value[i]);
            } else {
                out.add(k + " " + e.a[i] + " " + e.b[i] + " " + e.c[i]);
            }
        }
        return out;
    }
}
