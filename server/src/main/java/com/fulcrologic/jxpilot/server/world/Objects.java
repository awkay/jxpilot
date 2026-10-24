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

import com.fulcrologic.jxpilot.common.sim.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.Click;
/**
 * Preallocated struct-of-arrays pool of moving objects (shots, sparks, debris; C {@code Obj[]} of
 * {@code object}). Objects are addressed by an int handle that stays valid until {@link #free}. Live
 * handles are also kept densely in {@link #active} (C {@code Obj[0..NumObjs)}), compacted by
 * swap-remove like C {@code Object_free_ind}.
 * <p>
 * Positions are authoritative in clicks ({@link #cx}/{@link #cy}); {@link #px}/{@link #py} are the
 * pixel positions C keeps alongside ({@code pos.x = CLICK_TO_PIXEL(cx)}). Velocity, acceleration and mass
 * are floats like C's {@code DFLOAT}; life counts whole frames (C {@code long}).
 */
public final class Objects {
    public final int capacity;

    public final int[] type;
    public final int[] status;
    /** Owner player id or {@link Obj#NO_ID}. */
    public final int[] owner;
    public final int[] team;
    public final int[] cx;
    public final int[] cy;
    public final int[] px;
    public final int[] py;
    public final int[] prevX;
    public final int[] prevY;
    public final float[] vx;
    public final float[] vy;
    public final float[] accX;
    public final float[] accY;
    public final float[] mass;
    /** Original frames left to live. */
    public final double[] life;
    /** Life value below which the owner is no longer immune (C {@code fuselife}). */
    public final double[] fuseLife;
    public final int[] plRange;
    public final int[] plRadius;
    /**
     * When the object last joined its map cell (C {@code Cell_add_object}: created or moved); {@link Grid}
     * lists each cell's objects latest first, like C's cell lists.
     */
    public final long[] cellSeq;
    private long cellClock;
    public final int[] color;
    public final int[] missileDir;
    /** Modifier bits ({@link Mods}). */
    public final int[] mods;
    // ---- C wireobject fields (wreckage; asteroids use the same): rotation in RES units, turn per frame as a
    // fraction of a turn, size 0..255, info (wreckage shape)
    public final int[] rotation;
    /** Wreckage/asteroid turn per frame; C missile {@code turnspeed}. */
    public final float[] turnSpeed;
    public final int[] size;
    /** C {@code info}: wreckage shape; item type, missile target id (-1 none), mine fuse frames, torpedo frame
     * counter (server-items). */
    public final int[] info;
    // ---- C object.info / count and the mine/missile extensions (server-items)
    /** C {@code count}: item pack size, heat-seeker error counter, confusion counter. */
    public final int[] counter;
    /** C missile {@code max_speed}. */
    public final float[] maxSpeed;
    /** C mine/torpedo {@code spread_left}. */
    public final int[] spreadLeft;
    /** C mine/smart {@code ecm_range}. */
    public final float[] ecmRange;
    /** C smart {@code new_info}: the id an ECM-confused missile may re-lock on. */
    public final int[] newInfo;
    /** C mine {@code owner}: who laid the mine ({@link #owner} is who controls it after ECM reprogramming). */
    public final int[] mineOwner;
    /**
     * Protocol v2 (OWN_SHOTS): a player's bullet's net tick of spawn and its index among the shots its ship fired
     * that tick (ShipShooter order); -1 for every other object. Not C state: nothing in the simulation reads it.
     */
    public final int[] spawnTick;
    public final int[] shotOrdinal;

    /** Dense list of live handles, {@code active[0..count)}. */
    public final int[] active;
    private final int[] slot;
    private final int[] freeStack;
    private int freeTop;
    private int count;
    /** Bumped on every alloc and free: lets lookup structures know the live set changed (no allocation). */
    public int version;

    public Objects(int capacity) {
        this.capacity = capacity;
        type = new int[capacity];
        status = new int[capacity];
        owner = new int[capacity];
        team = new int[capacity];
        cx = new int[capacity];
        cy = new int[capacity];
        px = new int[capacity];
        py = new int[capacity];
        prevX = new int[capacity];
        prevY = new int[capacity];
        vx = new float[capacity];
        vy = new float[capacity];
        accX = new float[capacity];
        accY = new float[capacity];
        mass = new float[capacity];
        life = new double[capacity];
        fuseLife = new double[capacity];
        plRange = new int[capacity];
        plRadius = new int[capacity];
        cellSeq = new long[capacity];
        color = new int[capacity];
        missileDir = new int[capacity];
        mods = new int[capacity];
        rotation = new int[capacity];
        turnSpeed = new float[capacity];
        size = new int[capacity];
        info = new int[capacity];
        counter = new int[capacity];
        maxSpeed = new float[capacity];
        spreadLeft = new int[capacity];
        ecmRange = new float[capacity];
        newInfo = new int[capacity];
        mineOwner = new int[capacity];
        spawnTick = new int[capacity];
        shotOrdinal = new int[capacity];
        active = new int[capacity];
        slot = new int[capacity];
        freeStack = new int[capacity];
        clear();
    }

    /** BEHAVIOR: frees every object; handles are handed out again from 0 upward. */
    public void clear() {
        count = 0;
        freeTop = 0;
        for (int h = capacity - 1; h >= 0; h--) {
            freeStack[freeTop++] = h;
            slot[h] = -1;
        }
    }

    /** Live object count (C {@code NumObjs}). */
    public int count() {
        return count;
    }

    /** True when {@link #alloc} would succeed. */
    public boolean hasFree() {
        return freeTop > 0;
    }

    /**
     * C {@code Object_allocate} itself: the next free handle with only {@code type = DEBRIS} and {@code life = 0};
     * every other field keeps what the handle's previous object left (the free list is LIFO like C's
     * {@code Obj[]} pool, so this is the object C would reuse). Only for C code that uses an object it never
     * initialises (shot.c:991-994).
     * <p>
     * BEHAVIOR: -1 when the pool is full.
     */
    public int allocStale() {
        if (freeTop == 0) {
            return -1;
        }
        int h = freeStack[--freeTop];
        slot[h] = count;
        active[count++] = h;
        version++;
        type[h] = Obj.DEBRIS;
        life[h] = 0;
        shotOrdinal[h] = -1;
        return h;
    }

    /**
     * Allocate an object with all fields zeroed except {@code owner = NO_ID}.
     * <p>
     * BEHAVIOR: returns -1 when the pool is full (C {@code Object_allocate} returning NULL).
     * BEHAVIOR: the new handle is appended to {@link #active}.
     */
    public int alloc() {
        if (freeTop == 0) {
            return -1;
        }
        int h = freeStack[--freeTop];
        slot[h] = count;
        active[count++] = h;
        version++;
        cellSeq[h] = ++cellClock;
        type[h] = 0;
        status[h] = 0;
        owner[h] = Obj.NO_ID;
        team[h] = 0;
        cx[h] = 0;
        cy[h] = 0;
        px[h] = 0;
        py[h] = 0;
        prevX[h] = 0;
        prevY[h] = 0;
        vx[h] = 0;
        vy[h] = 0;
        accX[h] = 0;
        accY[h] = 0;
        mass[h] = 0;
        life[h] = 0;
        fuseLife[h] = 0;
        plRange[h] = 0;
        plRadius[h] = 0;
        color[h] = 0;
        missileDir[h] = 0;
        mods[h] = 0;
        rotation[h] = 0;
        turnSpeed[h] = 0;
        size[h] = 0;
        info[h] = 0;
        counter[h] = 0;
        maxSpeed[h] = 0;
        spreadLeft[h] = 0;
        ecmRange[h] = 0;
        newInfo[h] = 0;
        mineOwner[h] = Obj.NO_ID;
        shotOrdinal[h] = -1;
        return h;
    }

    /**
     * Release a handle.
     * <p>
     * BEHAVIOR: the last active handle moves into the freed slot of {@link #active} (swap-remove).
     * BEHAVIOR: freeing a handle that is not live is ignored.
     */
    public void free(int h) {
        int s = slot[h];
        if (s < 0) {
            return;
        }
        int last = active[--count];
        active[s] = last;
        slot[last] = s;
        slot[h] = -1;
        freeStack[freeTop++] = h;
        version++;
    }

    /** C {@code Cell_add_object} after a move: the object is now the first of its cell. */
    public void touchCell(int h) {
        cellSeq[h] = ++cellClock;
    }

    /** True if the handle is live. */
    public boolean isLive(int h) {
        return slot[h] >= 0;
    }

    /**
     * Set the position in clicks (C {@code Object_position_set_clicks}).
     * <p>
     * BEHAVIOR: pixel position is the click position shifted down (floor).
     */
    public void setClicks(int h, int x, int y) {
        cx[h] = x;
        cy[h] = y;
        px[h] = x >> Click.SHIFT;
        py[h] = y >> Click.SHIFT;
    }

    /**
     * C {@code Object_position_init_pixels}: set the position and make it the previous position too.
     * <p>
     * BEHAVIOR: the pixel arguments are C {@code DFLOAT}s: rounded to float, then truncated to clicks.
     */
    public void initPixels(int h, double x, double y) {
        setClicks(h, Click.fromPixels((float) x), Click.fromPixels((float) y));
        prevX[h] = px[h];
        prevY[h] = py[h];
    }

    /** C {@code Object_position_remember}. */
    public void remember(int h) {
        prevX[h] = px[h];
        prevY[h] = py[h];
    }
}
