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
package com.fulcrologic.jxpilot.common.sim;

import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.Wormhole;
import com.fulcrologic.jxpilot.common.map.WorldMap;

import java.util.Arrays;

/**
 * A {@link ShipWorld} over a map and its kernel parameters, with the dynamic state set explicitly: the frame clock,
 * the round delay, which cannons are phased, the targets' teams and the wormhole table. This is the
 * world a client predicts its own ship in (it fills the dynamic state from snapshots); tests use it too. It has no
 * pool objects.
 * <p>
 * Wormholes: the map's come first (their types and blocks from the map); temporary ones (hyperjumps with
 * {@code wormTime}) are mirrored with {@link #setTempWormhole} and {@link #setWormholeCount}. The server's map blocks
 * may name a wormhole index this table has not been told about (or one past the end, which C and the server read as
 * a stale copy); such an index reads as an IN wormhole with no destination, so a ship entering it warps (the
 * predictor stops predicting) instead of the kernel failing.
 */
public final class KernelWorld implements ShipWorld {
    /** Temporary wormholes the table holds before it grows (the server's {@code WormTable} starts with as many). */
    public static final int TEMP_CAPACITY = 64;

    private final WorldMap map;
    private final ShipParams params;
    private final byte[] wallDist;
    private final int width;
    private final int height;
    private final int pixelWidth;
    private final int pixelHeight;
    private final int clickWidth;
    private final int clickHeight;
    private final boolean wrap;
    private final int mapWormholes;
    private int wormCount;
    private int[] wormBx;
    private int[] wormBy;
    private int[] wormType;
    private int[] wormLastDest;
    private int[] wormCountdown;

    /** C {@code frame_loops}. */
    public long frames = 1;
    /** C {@code round_delay}. */
    public int roundDelay;
    /** Per map cannon: it uses a phasing device. */
    public final boolean[] cannonPhased;
    /** Per map target: its team (initially the map's in team play, else 0, as the server starts them). */
    public final int[] targetTeam;

    /**
     * BEHAVIOR: the size, wrap and wall distances are the map's; targets start with their map team (0 outside team
     * play), the map's wormholes
     * with no destination and no countdown, no cannon is phased, the frame clock is 1 (C's first frame).
     */
    public KernelWorld(WorldMap map, ShipParams params) {
        this.map = map;
        this.params = params;
        this.wallDist = WallDist.compute(map);
        this.width = map.width();
        this.height = map.height();
        this.pixelWidth = map.pixelWidth();
        this.pixelHeight = map.pixelHeight();
        this.clickWidth = pixelWidth << Click.SHIFT;
        this.clickHeight = pixelHeight << Click.SHIFT;
        this.wrap = map.edgeWrap();
        cannonPhased = new boolean[map.cannons().size()];
        targetTeam = new int[map.targets().size()];
        for (int i = 0; i < targetTeam.length; i++) {
            targetTeam[i] = map.teamPlay() ? map.targets().get(i).team() : 0;
        }
        int n = map.wormholes().size();
        mapWormholes = n;
        wormCount = n;
        int cap = n + TEMP_CAPACITY;
        wormBx = new int[cap];
        wormBy = new int[cap];
        wormType = new int[cap];
        wormLastDest = new int[cap];
        wormCountdown = new int[cap];
        Arrays.fill(wormLastDest, -1);
        Arrays.fill(wormType, WORM_IN);
        for (int i = 0; i < n; i++) {
            Wormhole h = map.wormholes().get(i);
            Wormhole.Type t = h.type();
            wormType[i] = t == Wormhole.Type.IN ? WORM_IN : (t == Wormhole.Type.OUT ? WORM_OUT : WORM_NORMAL);
            wormBx[i] = h.bx();
            wormBy[i] = h.by();
        }
    }

    /** BEHAVIOR: the world of the map in {@code o} with the kernel parameters {@link ShipParams#of} gives for it. */
    public static KernelWorld of(Options o) {
        WorldMap m = WorldMap.fromOptions(o);
        return new KernelWorld(m, ShipParams.of(o, m));
    }

    @Override
    public WorldMap map() {
        return map;
    }

    @Override
    public ShipParams shipParams() {
        return params;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int pixelWidth() {
        return pixelWidth;
    }

    @Override
    public int pixelHeight() {
        return pixelHeight;
    }

    @Override
    public int clickWidth() {
        return clickWidth;
    }

    @Override
    public int clickHeight() {
        return clickHeight;
    }

    @Override
    public boolean wrap() {
        return wrap;
    }

    @Override
    public int wallDist(int bx, int by) {
        return wallDist[by * width + bx] & 0xFF;
    }

    @Override
    public long frames() {
        return frames;
    }

    @Override
    public int roundDelay() {
        return roundDelay;
    }

    @Override
    public boolean cannonPhased(int i) {
        return cannonPhased[i];
    }

    @Override
    public int targetTeam(int i) {
        return targetTeam[i];
    }

    /** The map's wormholes (indices below this are the map's). */
    public int mapWormholes() {
        return mapWormholes;
    }

    /**
     * Mirror the wormhole count: the map's plus the temporary ones.
     * <p>
     * BEHAVIOR: never below the map's count (indices the table does not hold read as unknown, see the class doc).
     */
    public void setWormholeCount(int n) {
        wormCount = Math.max(mapWormholes, n);
    }

    /**
     * Mirror temporary wormhole {@code i} (an index at or above {@link #mapWormholes}): its block and type.
     * <p>
     * BEHAVIOR: a map wormhole's index is ignored (the map defines those); the table grows to hold {@code i}.
     */
    public void setTempWormhole(int i, int bx, int by, int type) {
        if (i < mapWormholes) {
            return;
        }
        ensure(i + 1);
        wormBx[i] = bx;
        wormBy[i] = by;
        wormType[i] = type;
    }

    /**
     * Mirror wormhole {@code i}'s last destination and countdown.
     * <p>
     * BEHAVIOR: the table grows to hold {@code i}.
     */
    public void setWormholeState(int i, int lastDest, int countdown) {
        ensure(i + 1);
        wormLastDest[i] = lastDest;
        wormCountdown[i] = countdown;
    }

    /** Grow the table to at least {@code n} entries (doubling; never in a steady state). */
    private void ensure(int n) {
        if (n <= wormType.length) {
            return;
        }
        int old = wormType.length;
        int cap = Math.max(n, 2 * old);
        wormBx = Arrays.copyOf(wormBx, cap);
        wormBy = Arrays.copyOf(wormBy, cap);
        wormType = Arrays.copyOf(wormType, cap);
        wormLastDest = Arrays.copyOf(wormLastDest, cap);
        wormCountdown = Arrays.copyOf(wormCountdown, cap);
        Arrays.fill(wormType, old, cap, WORM_IN);
        Arrays.fill(wormLastDest, old, cap, -1);
    }

    private boolean known(int i) {
        return i >= 0 && i < wormType.length;
    }

    /** BEHAVIOR: the map's wormholes plus the mirrored temporary ones. */
    @Override
    public int wormholeCount() {
        return wormCount;
    }

    /** BEHAVIOR: the map's or mirrored type; an index the table never held reads as IN. */
    @Override
    public int wormholeType(int i) {
        return known(i) ? wormType[i] : WORM_IN;
    }

    /** BEHAVIOR: the block x; 0 for an index the table never held. */
    @Override
    public int wormholeBx(int i) {
        return known(i) ? wormBx[i] : 0;
    }

    /** BEHAVIOR: the block y; 0 for an index the table never held. */
    @Override
    public int wormholeBy(int i) {
        return known(i) ? wormBy[i] : 0;
    }

    /** BEHAVIOR: the mirrored last destination; -1 (none) initially and for an index the table never held. */
    @Override
    public int wormholeLastDest(int i) {
        return known(i) ? wormLastDest[i] : -1;
    }

    /** BEHAVIOR: the mirrored countdown; 0 initially and for an index the table never held. */
    @Override
    public int wormholeCountdown(int i) {
        return known(i) ? wormCountdown[i] : 0;
    }

    /** BEHAVIOR: 0: this world has no pool objects (only ships are moved in it). */
    @Override
    public int objectPx(int h) {
        return 0;
    }

    /** BEHAVIOR: 0, as {@link #objectPx}. */
    @Override
    public int objectPy(int h) {
        return 0;
    }
}
