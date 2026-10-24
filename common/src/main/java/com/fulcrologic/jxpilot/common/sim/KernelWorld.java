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
 * the round delay, which cannons are phased, the targets' teams and the map wormholes' destinations. This is the
 * world a client predicts its own ship in (it fills the dynamic state from snapshots); tests use it too. It has no
 * pool objects and no temporary wormholes.
 */
public class KernelWorld implements ShipWorld {
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
    private final int[] wormType;

    /** C {@code frame_loops}. */
    public long frames = 1;
    /** C {@code round_delay}. */
    public int roundDelay;
    /** Per map cannon: it uses a phasing device. */
    public final boolean[] cannonPhased;
    /** Per map target: its team (initially the map's in team play, else 0, as the server starts them). */
    public final int[] targetTeam;
    /** Per map wormhole: the last destination (initially -1). */
    public final int[] wormLastDest;
    /** Per map wormhole: frames until it picks a new destination (initially 0). */
    public final int[] wormCountdown;

    /**
     * BEHAVIOR: the size, wrap and wall distances are the map's; targets start with their map team (0 outside team
     * play), wormholes
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
        wormType = new int[n];
        for (int i = 0; i < n; i++) {
            Wormhole.Type t = map.wormholes().get(i).type();
            wormType[i] = t == Wormhole.Type.IN ? WORM_IN : (t == Wormhole.Type.OUT ? WORM_OUT : WORM_NORMAL);
        }
        wormLastDest = new int[n];
        Arrays.fill(wormLastDest, -1);
        wormCountdown = new int[n];
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

    @Override
    public int wormholeCount() {
        return wormType.length;
    }

    @Override
    public int wormholeType(int i) {
        return wormType[i];
    }

    @Override
    public int wormholeBx(int i) {
        return map.wormholes().get(i).bx();
    }

    @Override
    public int wormholeBy(int i) {
        return map.wormholes().get(i).by();
    }

    @Override
    public int wormholeLastDest(int i) {
        return wormLastDest[i];
    }

    @Override
    public int wormholeCountdown(int i) {
        return wormCountdown[i];
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
