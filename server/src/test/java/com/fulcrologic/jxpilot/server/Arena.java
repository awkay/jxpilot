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
package com.fulcrologic.jxpilot.server;

import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * A busy test world: a walled 60x60 arena with 32 bases in the middle, inner wall blocks, diagonals,
 * cannons and fuel, 32 players and a pool pre-filled with shots and debris. Used by the allocation test
 * and the tick benchmark.
 */
public final class Arena {
    private Arena() {}

    /** Map rows: border walls, 4x8 bases in the centre, scattered walls/diagonals/cannons/fuel. */
    public static String[] rows() {
        int n = 60;
        char[][] g = new char[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                g[y][x] = (x == 0 || y == 0 || x == n - 1 || y == n - 1) ? 'x' : ' ';
            }
        }
        for (int i = 0; i < 32; i++) {
            g[24 + (i / 8) * 4][14 + (i % 8) * 4] = '_';
        }
        for (int i = 0; i < 12; i++) {
            int x = 6 + i * 4;
            g[8][x] = 'x';
            g[9][x] = 'r';
            g[50][x] = 'q';
            g[51][x] = 'a';
            g[12][x + 1] = '#';
            g[46][x + 1] = 'w';
        }
        String[] rows = new String[n];
        for (int y = 0; y < n; y++) {
            rows[y] = new String(g[y]);
        }
        return rows;
    }

    /** The arena with 32 joined players and {@code shots} shots plus {@code debris} debris in flight. */
    public static Simulation busy(int seed, int shots, int debris) {
        Simulation s = new Simulation(new World(TestWorlds.options("gravity: -0.14\nmaxPlayerShots: 512", rows()), seed));
        for (int i = 0; i < 32; i++) {
            Player p = s.ships.join("pilot" + i, 0);
            if (p == null) {
                throw new IllegalStateException("arena needs 32 bases");
            }
        }
        fill(s, shots, debris, seed);
        return s;
    }

    /** Add shots and debris flying around the centre with long lives. */
    public static void fill(Simulation s, int shots, int debris, int seed) {
        fill(s, shots, debris, new com.fulcrologic.jxpilot.common.math.Rng(seed));
    }

    /** {@link #fill} with a caller-owned RNG: allocation free (for topping up during allocation tests). */
    public static void fill(Simulation s, int shots, int debris, com.fulcrologic.jxpilot.common.math.Rng r) {
        World w = s.world;
        Objects o = w.objects;
        for (int i = 0; i < shots + debris; i++) {
            int h = o.alloc();
            if (h < 0) {
                return;
            }
            boolean shot = i < shots;
            o.type[h] = shot ? Obj.SHOT : Obj.DEBRIS;
            o.status[h] = Obj.GRAVITY;
            o.owner[h] = shot ? w.playerAt(i % w.numPlayers).id : Obj.NO_ID;
            o.mass[h] = shot ? 0.1f : 3.5f;
            o.life[h] = 200 + r.nextFloat() * 600;
            o.fuseLife[h] = o.life[h];
            o.plRange[h] = shot ? 0 : 8;
            o.plRadius[h] = o.plRange[h];
            o.initPixels(h, 100 + r.nextFloat() * 1900, 100 + r.nextFloat() * 1900);
            o.vx[h] = (r.nextFloat() - 0.5f) * 30;
            o.vy[h] = (r.nextFloat() - 0.5f) * 30;
        }
    }
}
