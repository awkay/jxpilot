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

import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared scenario helpers for the collision / shot / debris / cannon / crash tests. */
final class SimFixture {
    private SimFixture() {}

    /** One sim tick is one C frame (60 fps), no gravity. */
    static final String FRAME = "framesPerSecond: 60\ngravity: 0\n";
    /** Default 14 fps, no gravity (one tick is still one C frame). */
    static final String FPS14 = "gravity: 0\n";

    /**
     * An open map {@code w} x {@code h} blocks with {@code bases} bases ({@code baseChars[i]} or '_') on the
     * bottom row (block y 0) at x = 1, 3, 5, ...
     */
    static String[] open(int w, int h, String baseChars) {
        String[] rows = new String[h];
        char[] blank = new char[w];
        Arrays.fill(blank, ' ');
        for (int r = 0; r < h - 1; r++) {
            rows[r] = new String(blank);
        }
        char[] bottom = blank.clone();
        for (int i = 0; i < baseChars.length(); i++) {
            bottom[1 + 2 * i] = baseChars.charAt(i);
        }
        rows[h - 1] = new String(bottom);
        return rows;
    }

    /** A simulation over an open map with the given options. */
    static Simulation sim(String extra, int w, int h, String baseChars) {
        return TestWorlds.sim(extra, open(w, h, baseChars));
    }

    /**
     * Join a player, drop the start shields and put them at (x, y) at rest (previous position = position).
     */
    static Player ready(Simulation s, String name, int team, double x, double y) {
        Player p = TestWorlds.join(s, name, team);
        p.used &= ~Has.SHIELD;
        p.initPixels(x, y);
        p.vx = 0;
        p.vy = 0;
        return p;
    }

    /** Allocate a pool object at (x, y) (previous position too) with the given fields. */
    static int obj(World w, int type, int owner, double x, double y, double vx, double vy, double mass, double life) {
        Objects o = w.objects;
        int h = o.alloc();
        o.type[h] = type;
        o.owner[h] = owner;
        o.initPixels(h, x, y);
        o.vx[h] = (float) vx;
        o.vy[h] = (float) vy;
        o.mass[h] = (float) mass;
        o.life[h] = life;
        o.fuseLife[h] = life;
        return h;
    }

    /** MESSAGE texts appended since {@code from}. */
    static List<String> messages(World w, long from) {
        List<String> out = new ArrayList<>();
        Events e = w.events;
        for (long s = from; s < e.seq(); s++) {
            int i = Events.index(s);
            if (e.kind[i] == Events.MESSAGE) {
                out.add(e.text[i].toString());
            }
        }
        return out;
    }

    /** GAME_EVENTs since {@code from} as "kind:first:second". */
    static List<String> gameEvents(World w, long from) {
        List<String> out = new ArrayList<>();
        Events e = w.events;
        for (long s = from; s < e.seq(); s++) {
            int i = Events.index(s);
            if (e.kind[i] == Events.GAME_EVENT) {
                out.add(e.a[i] + ":" + e.b[i] + ":" + e.c[i]);
            }
        }
        return out;
    }

    /** KILL events since {@code from} as "killer:victim:cause:aux" (the server-internal kill cause tag). */
    static List<String> killCauses(World w, long from) {
        List<String> out = new ArrayList<>();
        Events e = w.events;
        for (long s = from; s < e.seq(); s++) {
            int i = Events.index(s);
            if (e.kind[i] == Events.GAME_EVENT && e.a[i] == com.fulcrologic.jxpilot.common.net.Msg.GameEvent.KILL) {
                out.add(e.b[i] + ":" + e.c[i] + ":" + e.cause[i] + ":" + e.aux[i]);
            }
        }
        return out;
    }

    /** The most recently recorded burst. */
    static DebrisBurst lastBurst(World w) {
        return w.events.burst[Events.burstIndex(w.events.burstSeq() - 1)];
    }

    /** Live handles in pool (active) order. */
    static List<Integer> live(World w) {
        List<Integer> l = new ArrayList<>();
        for (int k = 0; k < w.objects.count(); k++) {
            l.add(w.objects.active[k]);
        }
        return l;
    }

    /** One generated particle. */
    record P(int index, float vx, float vy, float speed, int life) {}

    /** Expand a burst with a private RNG (what a client does). */
    static List<P> expand(DebrisBurst d) {
        List<P> l = new ArrayList<>();
        d.generate(new com.fulcrologic.jxpilot.common.math.Rng(0), (i, vx, vy, sp, life) -> l.add(new P(i, vx, vy, sp, life)));
        return l;
    }

    /** A copy of a burst's parameters. */
    static DebrisBurst copy(DebrisBurst d) {
        DebrisBurst c = new DebrisBurst();
        c.x = d.x;
        c.y = d.y;
        c.vx = d.vx;
        c.vy = d.vy;
        c.seed = d.seed;
        c.count = d.count;
        c.minDir = d.minDir;
        c.dirSpan = d.dirSpan;
        c.minSpeed = d.minSpeed;
        c.maxSpeed = d.maxSpeed;
        c.minLife = d.minLife;
        c.maxLife = d.maxLife;
        c.hypotenuse = d.hypotenuse;
        return c;
    }
}
