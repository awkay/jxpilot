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

import com.fulcrologic.jxpilot.common.map.WorldMap;

/**
 * The read-only view of the world the ship kernel needs: the map and its wall distances, the world size, edge
 * wrapping, the kernel parameters, the frame clock and round delay, and the map features whose state decides
 * whether they block a ship (cannon phasing, target teams, wormhole state; pool object positions for the object
 * branch of wormholes). The server's {@code World} implements it over its live state; a client implements it
 * over its copy of the map and the state its snapshots carry ({@link KernelWorld}).
 */
public interface ShipWorld {
    /** Wormhole types ({@code WormTable}, C {@code wormType}). */
    int WORM_NORMAL = 0;
    int WORM_IN = 1;
    int WORM_OUT = 2;

    /** The map (blocks change when cannons die and temporary wormholes come and go). */
    WorldMap map();

    /** The kernel parameters. */
    ShipParams shipParams();

    /** Map width in blocks. */
    int width();

    /** Map height in blocks. */
    int height();

    /** World width in pixels. */
    int pixelWidth();

    /** World height in pixels. */
    int pixelHeight();

    /** World width in clicks. */
    int clickWidth();

    /** World height in clicks. */
    int clickHeight();

    /** The map wraps at its edges ({@code edgeWrap}). */
    boolean wrap();

    /** Wall distance (half blocks, {@link WallDist}) at an in-range block. */
    int wallDist(int bx, int by);

    /** C {@code frame_loops}: the frame clock. */
    long frames();

    /** C {@code round_delay}: frames until the next round starts (keys are limited while it is &gt; 0). */
    int roundDelay();

    /** Cannon {@code i} uses a phasing device (ships pass through it). */
    boolean cannonPhased(int i);

    /** The team target {@code i} belongs to now. */
    int targetTeam(int i);

    /** Wormholes, the map's and the temporary ones. */
    int wormholeCount();

    /** Type of wormhole {@code i}: {@link #WORM_NORMAL}, {@link #WORM_IN} or {@link #WORM_OUT}. */
    int wormholeType(int i);

    /** Block x of wormhole {@code i}. */
    int wormholeBx(int i);

    /** Block y of wormhole {@code i}. */
    int wormholeBy(int i);

    /** The destination wormhole {@code i} last sent something to, or -1. */
    int wormholeLastDest(int i);

    /** Frames until wormhole {@code i} picks a new destination. */
    int wormholeCountdown(int i);

    /** Pixel x of pool object {@code h} (the object branch of wormholes; never asked for ships). */
    int objectPx(int h);

    /** Pixel y of pool object {@code h}. */
    int objectPy(int h);

    /** C {@code WRAP_XCLICK}. BEHAVIOR: with edgeWrap a position outside [0, width) moves by one width; else unchanged. */
    default int wrapXClick(int x) {
        if (!wrap()) {
            return x;
        }
        int w = clickWidth();
        return x < 0 ? x + w : (x >= w ? x - w : x);
    }

    /** C {@code WRAP_YCLICK}. BEHAVIOR: as {@link #wrapXClick} with the height. */
    default int wrapYClick(int y) {
        if (!wrap()) {
            return y;
        }
        int h = clickHeight();
        return y < 0 ? y + h : (y >= h ? y - h : y);
    }

    /** C {@code WRAP_XPIXEL}. BEHAVIOR: with edgeWrap a position outside [0, width) moves by one width; else unchanged. */
    default double wrapXPixel(double x) {
        if (!wrap()) {
            return x;
        }
        int w = pixelWidth();
        return x < 0 ? x + w : (x >= w ? x - w : x);
    }

    /** C {@code WRAP_YPIXEL}. BEHAVIOR: as {@link #wrapXPixel} with the height. */
    default double wrapYPixel(double y) {
        if (!wrap()) {
            return y;
        }
        int h = pixelHeight();
        return y < 0 ? y + h : (y >= h ? y - h : y);
    }

    /**
     * Gravity x at a block (C {@code World.gravity[bx][by]}, a float).
     * <p>
     * BEHAVIOR: out-of-map blocks are clamped to the edge.
     */
    default float gravityX(int bx, int by) {
        return (float) map().gravityX(clampBlock(bx, width()), clampBlock(by, height()));
    }

    /** Gravity y at a block (a float), clamped into the map. */
    default float gravityY(int bx, int by) {
        return (float) map().gravityY(clampBlock(bx, width()), clampBlock(by, height()));
    }

    /** BEHAVIOR: {@code b} limited to [0, n-1]. */
    static int clampBlock(int b, int n) {
        return b < 0 ? 0 : (b >= n ? n - 1 : b);
    }
}
