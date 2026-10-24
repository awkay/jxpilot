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

import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Shared helpers for the item and tank tests: RNG mirrors, seed search, pool inspection. */
final class ItemFx {
    private ItemFx() {}

    /** Reseed the world RNG and return a mirror that replays the same draws. */
    static Rng reseed(World w, int seed) {
        w.rng.seed(seed);
        return new Rng(seed);
    }

    /** C rfrac() as the world draws it (a float widened to double). */
    static double rf(Rng m) {
        return m.nextFloat();
    }

    /** Skip n draws of a mirror. */
    static Rng skip(Rng m, int n) {
        for (int i = 0; i < n; i++) {
            m.nextInt();
        }
        return m;
    }

    /** The first seed (from 1) whose fresh sequence satisfies {@code p}. */
    static int seedWhere(Predicate<Rng> p) {
        for (int s = 1; s < 10_000_000; s++) {
            if (p.test(new Rng(s))) {
                return s;
            }
        }
        throw new IllegalStateException("no seed");
    }

    /** Live handles of the given type in pool (active) order. */
    static List<Integer> live(World w, int type) {
        List<Integer> l = new ArrayList<>();
        Objects o = w.objects;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if (o.type[h] == type) {
                l.add(h);
            }
        }
        return l;
    }

    /** Allocate pool objects until the pool is full; returns how many were taken. */
    static int fillPool(World w) {
        int n = 0;
        while (w.objects.alloc() >= 0) {
            n++;
        }
        return n;
    }
}
