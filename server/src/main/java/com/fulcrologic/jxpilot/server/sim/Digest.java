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

import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * A 64-bit FNV-1a hash of the simulation state, for determinism checks: tick and frame clock, every
 * player's kinematics, fuel, score and status, every live object, cannon and fuel station state,
 * team scores and the event/burst counts.
 */
public final class Digest {
    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private long h;

    private Digest() {
        h = OFFSET;
    }

    private void add(long v) {
        for (int i = 0; i < 8; i++) {
            h ^= (v >>> (i * 8)) & 0xFF;
            h *= PRIME;
        }
    }

    private void add(double v) {
        add(Double.doubleToLongBits(v));
    }

    /**
     * BEHAVIOR: equal states give equal digests; any change to a hashed field changes it (with
     * overwhelming probability). Reading the digest does not change the world.
     */
    public static long of(World w) {
        Digest d = new Digest();
        d.add(w.tick);
        d.add(w.frames);
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            d.add(p.id);
            d.add(p.cx);
            d.add(p.cy);
            d.add(p.vx);
            d.add(p.vy);
            d.add(p.dir);
            d.add(p.floatDir);
            d.add(p.fuel);
            d.add(p.score);
            d.add(p.status);
            d.add(p.used);
            d.add(p.count);
            d.add(p.kills);
            d.add(p.deaths);
        }
        Objects o = w.objects;
        d.add(o.count());
        for (int k = 0; k < o.count(); k++) {
            int hd = o.active[k];
            d.add(o.type[hd]);
            d.add(o.cx[hd]);
            d.add(o.cy[hd]);
            d.add(o.vx[hd]);
            d.add(o.vy[hd]);
            d.add(o.life[hd]);
        }
        for (double v : w.cannonDeadTime) {
            d.add(v);
        }
        for (double v : w.fuelLevel) {
            d.add(v);
        }
        for (double v : w.teamScore) {
            d.add(v);
        }
        d.add(w.events.seq());
        d.add(w.events.burstSeq());
        return d.h;
    }
}
