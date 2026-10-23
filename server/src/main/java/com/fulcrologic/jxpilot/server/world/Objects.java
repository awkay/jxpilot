package com.fulcrologic.jxpilot.server.world;

/**
 * Preallocated struct-of-arrays pool of moving objects (shots, sparks, debris; C {@code Obj[]} of
 * {@code object}). Objects are addressed by an int handle that stays valid until {@link #free}. Live
 * handles are also kept densely in {@link #active} (C {@code Obj[0..NumObjs)}), compacted by
 * swap-remove like C {@code Object_free_ind}.
 * <p>
 * Positions are authoritative in clicks ({@link #cx}/{@link #cy}); {@link #px}/{@link #py} are the
 * pixel positions C keeps alongside ({@code pos.x = CLICK_TO_PIXEL(cx)}), and {@link #remX}/{@link #remY}
 * carry the sub-click remainder of the per-tick move.
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
    public final double[] remX;
    public final double[] remY;
    public final double[] vx;
    public final double[] vy;
    public final double[] accX;
    public final double[] accY;
    public final double[] mass;
    /** Original frames left to live. */
    public final double[] life;
    /** Life value below which the owner is no longer immune (C {@code fuselife}). */
    public final double[] fuseLife;
    public final int[] plRange;
    public final int[] plRadius;
    public final int[] color;
    public final int[] missileDir;
    /** Modifier bits ({@link Obj#MOD_CLUSTER}). */
    public final int[] mods;

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
        remX = new double[capacity];
        remY = new double[capacity];
        vx = new double[capacity];
        vy = new double[capacity];
        accX = new double[capacity];
        accY = new double[capacity];
        mass = new double[capacity];
        life = new double[capacity];
        fuseLife = new double[capacity];
        plRange = new int[capacity];
        plRadius = new int[capacity];
        color = new int[capacity];
        missileDir = new int[capacity];
        mods = new int[capacity];
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
        remX[h] = 0;
        remY[h] = 0;
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

    /** C {@code Object_position_init_pixels}: set the position and make it the previous position too. */
    public void initPixels(int h, double x, double y) {
        setClicks(h, Click.fromPixels(x), Click.fromPixels(y));
        prevX[h] = px[h];
        prevY[h] = py[h];
    }

    /** C {@code Object_position_remember}. */
    public void remember(int h) {
        prevX[h] = px[h];
        prevY[h] = py[h];
    }
}
