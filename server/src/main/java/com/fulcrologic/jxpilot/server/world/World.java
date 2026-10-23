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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.map.Wormhole;
import com.fulcrologic.jxpilot.common.math.Rng;

import java.util.Arrays;

/**
 * All simulation state: the map (owned: blocks change when cannons die), resolved parameters, the
 * seeded RNG, preallocated player slots, the object pool, the spatial grid, the event output and the
 * dynamic state of map features. Pure data plus small helpers; the rules live in {@code server.sim}.
 * No clock: time is {@link #tick} and the original-frame clock {@link #frames}.
 */
public final class World {
    /** C {@code NUM_IDS}: wire ids are 1..MAX_IDS-1 (0 is never used, as C {@code peek_ID}). */
    public static final int MAX_IDS = 256;

    public final Options options;
    public final WorldMap map;
    public final Params params;
    public final int width;
    public final int height;
    public final int pixelWidth;
    public final int pixelHeight;
    public final int clickWidth;
    public final int clickHeight;
    public final boolean wrap;

    /** Simulation RNG (C {@code rfrac()}/{@code randomMT()}). */
    public final Rng rng;
    /** Scratch RNG reseeded per burst expansion. */
    public final Rng burstRng = new Rng(0);
    /** Wall distance in half blocks, see {@link WallDist} (not recomputed when blocks change, as C). */
    public final byte[] wallDist;

    public final Player[] players;
    /** Slots of players in the game, in join order (C {@code Players[0..NumPlayers)}). */
    public final int[] order;
    public int numPlayers;
    private final int[] slotOfId = new int[MAX_IDS];
    private int pseudoTeamNext;

    public final Objects objects;
    public final Grid grid;
    public final Events events = new Events();

    // ---- dynamic map state
    /** Original frames a cannon stays dead; 0 = alive. */
    public final double[] cannonDeadTime;
    /** Fuel left in each station (C fixed point). */
    public final double[] fuelLevel;
    /** Original frames a target stays dead; 0 = alive (targets are solid only in S1). */
    public final double[] targetDeadTime;
    public final int[] wormLastDest;
    public final double[] wormCountdown;

    // ---- teams (C World.teams)
    public final double[] teamScore = new double[Const.MAX_TEAMS];
    public final int[] teamMembers = new int[Const.MAX_TEAMS];
    public final boolean[] teamScoreDirty = new boolean[Const.MAX_TEAMS];

    /** Sim tick counter. */
    public int tick;
    /** Original-frame clock (C {@code frame_loops}): advances by {@code dt} per tick. */
    public double frames;

    /** Build the map from {@code options} (map data + settings) and the initial state. */
    public World(Options options, int seed) {
        this.options = options;
        this.map = WorldMap.fromOptions(options);
        this.params = Params.from(options, map);
        this.width = map.width();
        this.height = map.height();
        this.pixelWidth = map.pixelWidth();
        this.pixelHeight = map.pixelHeight();
        this.clickWidth = pixelWidth << Click.SHIFT;
        this.clickHeight = pixelHeight << Click.SHIFT;
        this.wrap = map.edgeWrap();
        this.rng = new Rng(seed);
        this.wallDist = WallDist.compute(map);
        int cap = Math.max(1, Math.min(MAX_IDS - 1, map.bases().size()));
        players = new Player[cap];
        for (int i = 0; i < cap; i++) {
            players[i] = new Player(i);
        }
        order = new int[cap];
        Arrays.fill(slotOfId, -1);
        objects = new Objects(params.maxObjects);
        grid = new Grid(width, height, wrap, params.maxObjects);

        cannonDeadTime = new double[map.cannons().size()];
        fuelLevel = new double[map.fuels().size()];
        Arrays.fill(fuelLevel, Const.START_STATION_FUEL * 256);
        targetDeadTime = new double[map.targets().size()];
        wormLastDest = new int[map.wormholes().size()];
        wormCountdown = new double[map.wormholes().size()];
        initWormholes();
    }

    /**
     * C map.c:870-877: without wormTime every wormhole starts with a random non-IN last destination
     * (so objects warp before any player has), else -1.
     * <p>
     * BEHAVIOR: with no non-IN wormhole at all, destinations stay -1 (C would loop forever).
     */
    private void initWormholes() {
        Arrays.fill(wormLastDest, -1);
        int n = wormLastDest.length;
        boolean anyOut = false;
        for (int i = 0; i < n; i++) {
            anyOut |= map.wormholes().get(i).type() != Wormhole.Type.IN;
        }
        if (params.wormTime != 0 || !anyOut) {
            return;
        }
        for (int i = 0; i < n; i++) {
            int j = (int) (rfrac() * n);
            while (map.wormholes().get(j).type() == Wormhole.Type.IN) {
                j = (int) (rfrac() * n);
            }
            wormLastDest[i] = j;
        }
    }

    /** C {@code rfrac()}: uniform in [0, 1). */
    public double rfrac() {
        return rng.nextFloat();
    }

    /** Player in the k-th position of {@link #order}. */
    public Player playerAt(int k) {
        return players[order[k]];
    }

    /**
     * Player with a wire id, or null.
     * <p>
     * BEHAVIOR: returns null for NO_ID, out-of-range and unused ids.
     */
    public Player byId(int id) {
        if (id <= 0 || id >= MAX_IDS) {
            return null;
        }
        int s = slotOfId[id];
        return s < 0 ? null : players[s];
    }

    /**
     * Reserve a free slot and id and append it to {@link #order}.
     * <p>
     * BEHAVIOR: returns null when every slot (one per base) is taken.
     * BEHAVIOR: the id is the lowest unused id &gt;= 1; the pseudo team is a fresh number (C pseudo_team_no++).
     */
    public Player addPlayer() {
        for (Player p : players) {
            if (!p.inUse) {
                int id = 1;
                while (slotOfId[id] >= 0) {
                    id++;
                }
                slotOfId[id] = p.slot;
                p.inUse = true;
                p.id = id;
                p.pseudoTeam = pseudoTeamNext++;
                order[numPlayers++] = p.slot;
                return p;
            }
        }
        return null;
    }

    /**
     * Remove a player from the game.
     * <p>
     * BEHAVIOR: the remaining players keep their relative order; the id becomes free; unknown players are ignored.
     */
    public void removePlayer(Player p) {
        if (!p.inUse) {
            return;
        }
        int k = 0;
        while (order[k] != p.slot) {
            k++;
        }
        System.arraycopy(order, k + 1, order, k, numPlayers - k - 1);
        numPlayers--;
        slotOfId[p.id] = -1;
        p.inUse = false;
    }

    /** Wall distance (half blocks) at an in-range block. */
    public int wallDist(int bx, int by) {
        return wallDist[by * width + bx] & 0xFF;
    }

    /** C {@code WRAP_DX} on pixels. BEHAVIOR: with edgeWrap a dx beyond +-(width &gt;&gt; 1) moves by one width; else unchanged. */
    public double wrapDx(double dx) {
        if (!wrap) {
            return dx;
        }
        int half = pixelWidth >> 1;
        return dx < -half ? dx + pixelWidth : (dx > half ? dx - pixelWidth : dx);
    }

    /** C {@code WRAP_DY} on pixels. BEHAVIOR: as {@link #wrapDx} with the height. */
    public double wrapDy(double dy) {
        if (!wrap) {
            return dy;
        }
        int half = pixelHeight >> 1;
        return dy < -half ? dy + pixelHeight : (dy > half ? dy - pixelHeight : dy);
    }

    /** C {@code Wrap_length}. BEHAVIOR: the length of the wrapped difference. */
    public double wrapLength(double dx, double dy) {
        double x = wrapDx(dx);
        double y = wrapDy(dy);
        return Math.sqrt(x * x + y * y);
    }

    /** C {@code WRAP_XCLICK}. BEHAVIOR: with edgeWrap a position outside [0, width) moves by one width; else unchanged. */
    public int wrapXClick(int x) {
        if (!wrap) {
            return x;
        }
        return x < 0 ? x + clickWidth : (x >= clickWidth ? x - clickWidth : x);
    }

    /** C {@code WRAP_YCLICK}. */
    public int wrapYClick(int y) {
        if (!wrap) {
            return y;
        }
        return y < 0 ? y + clickHeight : (y >= clickHeight ? y - clickHeight : y);
    }

    /** C {@code WRAP_XPIXEL}. */
    public double wrapXPixel(double x) {
        if (!wrap) {
            return x;
        }
        return x < 0 ? x + pixelWidth : (x >= pixelWidth ? x - pixelWidth : x);
    }

    /** C {@code WRAP_YPIXEL}. */
    public double wrapYPixel(double y) {
        if (!wrap) {
            return y;
        }
        return y < 0 ? y + pixelHeight : (y >= pixelHeight ? y - pixelHeight : y);
    }

    /** Gravity x at a block (C {@code World.gravity[bx][by]}). BEHAVIOR: out-of-map blocks are clamped to the edge. */
    public double gravityX(int bx, int by) {
        return map.gravityX(clamp(bx, width), clamp(by, height));
    }

    /** Gravity y at a block, clamped into the map. */
    public double gravityY(int bx, int by) {
        return map.gravityY(clamp(bx, width), clamp(by, height));
    }

    private static int clamp(int b, int n) {
        return b < 0 ? 0 : (b >= n ? n - 1 : b);
    }

    /** C {@code TEAM(i, j)}: team play and the same team. */
    public boolean sameTeam(Player a, Player b) {
        return params.teamPlay && a.team == b.team && a.team != Const.TEAM_NOT_SET;
    }

    /** C {@code Team_immune}: different ids, team immunity on and team mates. */
    public boolean teamImmune(int id1, int id2) {
        if (id1 == id2 || !params.teamImmunity || id1 == Obj.NO_ID || id2 == Obj.NO_ID) {
            return false;
        }
        Player a = byId(id1);
        Player b = byId(id2);
        return a != null && b != null && sameTeam(a, b);
    }
}
