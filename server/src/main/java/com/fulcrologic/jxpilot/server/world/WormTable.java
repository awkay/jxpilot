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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.sim.ShipWorld;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Wormhole;
import com.fulcrologic.jxpilot.common.map.WorldMap;

/**
 * All wormholes, C {@code World.wormHoles[0..NumWormholes)}: the map's wormholes (indices below
 * {@link #mapCount}, their last destination and countdown in {@link World#wormLastDest} and
 * {@link World#wormCountdown}) followed by the temporary ones hyperjumps make with {@code wormTime}
 * ({@code add_temp_wormholes} / {@code remove_temp_wormhole}, map.c). Preallocated: at most {@link #TEMP_CAPACITY}
 * temporary wormholes exist at a time.
 */
public final class WormTable {
    /** Temporary wormholes the table holds before it grows (C reallocs without a limit; so does this table). */
    public static final int TEMP_CAPACITY = 64;
    /** {@link Wormhole.Type} ordinals as ints. */
    public static final int NORMAL = ShipWorld.WORM_NORMAL;
    public static final int IN = ShipWorld.WORM_IN;
    public static final int OUT = ShipWorld.WORM_OUT;

    private final World w;
    private final WorldMap map;
    public final int mapCount;
    private int tempCount;
    private int[] tBx = new int[TEMP_CAPACITY];
    private int[] tBy = new int[TEMP_CAPACITY];
    private int[] tType = new int[TEMP_CAPACITY];
    private int[] tLastDest = new int[TEMP_CAPACITY];
    private int[] tCountdown = new int[TEMP_CAPACITY];
    private byte[] tLastBlock = new byte[TEMP_CAPACITY];
    private int[] tLastId = new int[TEMP_CAPACITY];

    WormTable(World w) {
        this.w = w;
        this.map = w.map;
        this.mapCount = map.wormholes().size();
    }

    /** C {@code World.NumWormholes}. */
    public int count() {
        return mapCount + tempCount;
    }

    /**
     * Indices whose entries exist: the map's wormholes plus the temporary slots (a block may name a slot past
     * {@link #count()}, which reads its stale copy, see {@link #removeTemp}).
     */
    public int capacity() {
        return mapCount + tBx.length;
    }

    /** Temporary wormholes now. */
    public int tempCount() {
        return tempCount;
    }

    public int bx(int i) {
        return i < mapCount ? map.wormholes().get(i).bx() : tBx[i - mapCount];
    }

    public int by(int i) {
        return i < mapCount ? map.wormholes().get(i).by() : tBy[i - mapCount];
    }

    /** {@link #NORMAL}, {@link #IN} or {@link #OUT}. */
    public int type(int i) {
        return i < mapCount ? map.wormholes().get(i).type().ordinal() : tType[i - mapCount];
    }

    /** C {@code temporary}. */
    public boolean temporary(int i) {
        return i >= mapCount;
    }

    public int lastDest(int i) {
        return i < mapCount ? w.wormLastDest[i] : tLastDest[i - mapCount];
    }

    public void setLastDest(int i, int dest) {
        if (i < mapCount) {
            w.wormLastDest[i] = dest;
        } else {
            tLastDest[i - mapCount] = dest;
        }
    }

    public int countdown(int i) {
        return i < mapCount ? w.wormCountdown[i] : tCountdown[i - mapCount];
    }

    public void setCountdown(int i, int frames) {
        if (i < mapCount) {
            w.wormCountdown[i] = frames;
        } else {
            tCountdown[i - mapCount] = frames;
        }
    }

    /**
     * C {@code add_temp_wormholes}: an IN wormhole at (xin, yin) leading to an OUT wormhole at (xout, yout), both
     * for wormTime frames; their blocks become WORMHOLE (the old block and object index are kept for later).
     * <p>
     * BEHAVIOR: without a limit, as C's realloc: a full table doubles (the only allocation, never in a steady
     * state).
     */
    public void addTemp(int xin, int yin, int xout, int yout) {
        if (tempCount + 2 > tBx.length) {
            int n = tBx.length * 2;
            tBx = java.util.Arrays.copyOf(tBx, n);
            tBy = java.util.Arrays.copyOf(tBy, n);
            tType = java.util.Arrays.copyOf(tType, n);
            tLastDest = java.util.Arrays.copyOf(tLastDest, n);
            tCountdown = java.util.Arrays.copyOf(tCountdown, n);
            tLastBlock = java.util.Arrays.copyOf(tLastBlock, n);
            tLastId = java.util.Arrays.copyOf(tLastId, n);
        }
        int in = count();
        put(tempCount, xin, yin, IN, in + 1);
        put(tempCount + 1, xout, yout, OUT, -1);
        tempCount += 2;
        map.setBlock(xin, yin, BlockType.WORMHOLE);
        map.setBlock(xout, yout, BlockType.WORMHOLE);
        map.setItemId(xin, yin, in);
        map.setItemId(xout, yout, in + 1);
    }

    private void put(int t, int bx, int by, int type, int lastDest) {
        tBx[t] = bx;
        tBy[t] = by;
        tType[t] = type;
        tLastDest[t] = lastDest;
        tCountdown[t] = w.params.wormTime;
        tLastBlock[t] = map.block(bx, by);
        tLastId[t] = map.itemId(bx, by);
    }

    /**
     * C {@code remove_temp_wormhole}: the block and object index return, and the last temporary wormhole takes
     * the freed index.
     * <p>
     * BEHAVIOR: as C (map.c:1368-1382), the moved wormhole's block keeps its old object index, the slot it left:
     * entering it finds the copy left there (C reads its shrunk array past the end, which in practice holds the
     * same copy) until a new temporary wormhole takes that index and the block leads into the new one.
     */
    public void removeTemp(int i) {
        int t = i - mapCount;
        map.setBlock(tBx[t], tBy[t], tLastBlock[t]);
        map.setItemId(tBx[t], tBy[t], tLastId[t]);
        tempCount--;
        if (t != tempCount) {
            tBx[t] = tBx[tempCount];
            tBy[t] = tBy[tempCount];
            tType[t] = tType[tempCount];
            tLastDest[t] = tLastDest[tempCount];
            tCountdown[t] = tCountdown[tempCount];
            tLastBlock[t] = tLastBlock[tempCount];
            tLastId[t] = tLastId[tempCount];
        }
    }

    /**
     * The wormhole loop at the end of C's player loop (update.c): every countdown runs down, and a temporary
     * wormhole whose countdown ran out is removed. Last index first.
     */
    public void countDown() {
        for (int i = count() - 1; i >= 0; i--) {
            if (countdown(i) > 0) {
                setCountdown(i, countdown(i) - 1);
            }
            if (temporary(i) && countdown(i) <= 0) {
                removeTemp(i);
            }
        }
    }
}
