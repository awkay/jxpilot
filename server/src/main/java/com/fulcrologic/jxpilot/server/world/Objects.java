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
    public final int[] color;
    public final int[] missileDir;
    /** Modifier bits ({@link Mods}). */
    public final int[] mods;
    // ---- C object.info / count and the mine/missile extensions (server-items)
    /** C {@code info}: item type, missile target id (-1 none), mine fuse frames, torpedo frame counter. */
    public final int[] info;
    /** C {@code count}: item pack size, heat-seeker error counter, confusion counter. */
    public final int[] counter;
    /** C missile {@code turnspeed}. */
    public final float[] turnSpeed;
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

    /** Dense list of live handles, {@code active[0..count)}. */
    public final int[] active;
    private final int[] slot;
    private final int[] freeStack;
    private int freeTop;
    private int count;

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
        color = new int[capacity];
        missileDir = new int[capacity];
        mods = new int[capacity];
        info = new int[capacity];
        counter = new int[capacity];
        turnSpeed = new float[capacity];
        maxSpeed = new float[capacity];
        spreadLeft = new int[capacity];
        ecmRange = new float[capacity];
        newInfo = new int[capacity];
        mineOwner = new int[capacity];
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
        info[h] = 0;
        counter[h] = 0;
        turnSpeed[h] = 0;
        maxSpeed[h] = 0;
        spreadLeft[h] = 0;
        ecmRange[h] = 0;
        newInfo[h] = 0;
        mineOwner[h] = Obj.NO_ID;
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
