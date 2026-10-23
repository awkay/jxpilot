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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.XMath;

/**
 * Cosmetic sparks and debris (struct of arrays, fixed capacity). Particles are expanded from bursts
 * with {@link DebrisBurst#generate} and move in straight lines: a particle born at sim tick {@code b}
 * with velocity {@code v} (px per original frame) is at {@code origin + v * age} where
 * {@code age = (t - b) * frameFraction} original frames, and dies when {@code age >= life}.
 * Positions are therefore a pure function of the tick they are drawn at: the render tick for
 * everything except the own ship's exhaust, which is drawn at the estimated server tick now (the time
 * the own ship is drawn at), so it stays attached to the engine.
 * <p>
 * Storage is a ring in spawn order: when full, a new particle evicts the oldest one; a burst generates
 * at most {@link #MAX_BURST} particles and never more than the capacity, so one huge (or hostile) burst
 * cannot run the generator for particles that could not be kept. Allocation free.
 */
public final class ParticlePool implements DebrisBurst.Sink {
    /** Most particles generated from one burst (BURST.count is u16). */
    public static final int MAX_BURST = 1024;

    private final int capacity;
    private final float[] x0;
    private final float[] y0;
    private final float[] vx;
    private final float[] vy;
    private final double[] birth;
    private final float[] life;
    private final byte[] color;
    private final byte[] kind;
    private final boolean[] own;
    private final float[] x;
    private final float[] y;
    private final boolean[] visible;
    /** Physical index of the oldest particle. */
    private int head;
    private int count;
    private long evicted;
    private long capped;

    // sink state for the burst being expanded
    private float sinkX;
    private float sinkY;
    private double sinkBirth;
    private byte sinkColor;
    private byte sinkKind;
    private boolean sinkOwn;

    public ParticlePool(int capacity) {
        this.capacity = capacity;
        x0 = new float[capacity];
        y0 = new float[capacity];
        vx = new float[capacity];
        vy = new float[capacity];
        birth = new double[capacity];
        life = new float[capacity];
        color = new byte[capacity];
        kind = new byte[capacity];
        own = new boolean[capacity];
        x = new float[capacity];
        y = new float[capacity];
        visible = new boolean[capacity];
    }

    public int capacity() { return capacity; }

    /** Live particles (visible or not yet born); index 0..count-1, 0 = oldest. */
    public int count() { return count; }

    /** Particles evicted (oldest first) to make room for new ones. */
    public long evicted() { return evicted; }

    /** Particles not generated because a burst exceeded {@link #MAX_BURST} or the capacity. */
    public long capped() { return capped; }

    /** BEHAVIOR: physical slot of logical index i (ring order from the oldest). */
    private int slot(int i) {
        int j = head + i;
        return j >= capacity ? j - capacity : j;
    }

    /** World x at the last {@link #step}. */
    public float x(int i) { return x[slot(i)]; }

    public float y(int i) { return y[slot(i)]; }

    /** False for particles born after the tick they were stepped to (draw only visible ones). */
    public boolean visible(int i) { return visible[slot(i)]; }

    /** {@link com.fulcrologic.jxpilot.common.net.Records.ObjColor}. */
    public int color(int i) { return color[slot(i)]; }

    /** {@link com.fulcrologic.jxpilot.common.net.Records.BurstKind}. */
    public int kind(int i) { return kind[slot(i)]; }

    /** True for the own ship's exhaust (stepped at the present, not the render tick). */
    public boolean own(int i) { return own[slot(i)]; }

    public float vx(int i) { return vx[slot(i)]; }

    public float vy(int i) { return vy[slot(i)]; }

    /** Birth sim tick. */
    public double birth(int i) { return birth[slot(i)]; }

    /** Lifetime in original frames. */
    public float life(int i) { return life[slot(i)]; }

    public float originX(int i) { return x0[slot(i)]; }

    public float originY(int i) { return y0[slot(i)]; }

    /** BEHAVIOR: removes all particles. */
    public void clear() {
        count = 0;
        head = 0;
    }

    /** {@link #spawn(DebrisBurst, Rng, double, int, int, boolean)} for particles drawn at the render tick. */
    public void spawn(DebrisBurst d, Rng rng, double birthTick, int burstKind, int objColor) {
        spawn(d, rng, birthTick, burstKind, objColor, false);
    }

    /**
     * Expand a burst into particles born at {@code birthTick}, all starting at the burst origin.
     * <p>
     * BEHAVIOR: exactly the first min(count, {@link #MAX_BURST}, capacity) particles of
     * {@code d.generate(rng, ...)}, in order; the rest are not generated and are counted in
     * {@link #capped()}. {@code d} is left unchanged.
     * BEHAVIOR: a full pool evicts its oldest particle for each new one ({@link #evicted()}).
     *
     * @param ownExhaust true for the own ship's thrust sparks (stepped at the present)
     */
    public void spawn(DebrisBurst d, Rng rng, double birthTick, int burstKind, int objColor, boolean ownExhaust) {
        sinkX = d.x;
        sinkY = d.y;
        sinkBirth = birthTick;
        sinkColor = (byte) objColor;
        sinkKind = (byte) burstKind;
        sinkOwn = ownExhaust;
        int requested = d.count;
        int n = Math.min(requested, Math.min(MAX_BURST, capacity));
        if (n < requested) {
            capped += requested - n;
            d.count = n;
        }
        try {
            d.generate(rng, this);
        } finally {
            d.count = requested;
        }
    }

    /** Sink callback used by {@link #spawn}. */
    @Override
    public void particle(int index, float pvx, float pvy, float speed, int plife) {
        if (count >= capacity) {
            head = slot(1);
            count--;
            evicted++;
        }
        int i = slot(count++);
        x0[i] = sinkX;
        y0[i] = sinkY;
        vx[i] = pvx;
        vy[i] = pvy;
        birth[i] = sinkBirth;
        life[i] = plife;
        color[i] = sinkColor;
        kind[i] = sinkKind;
        own[i] = sinkOwn;
        x[i] = sinkX;
        y[i] = sinkY;
        visible[i] = false;
    }

    /** {@link #step(double, double, float, double, double, boolean)} with every particle at the render tick. */
    public void step(double renderTick, float frameFraction, double worldW, double worldH, boolean wrap) {
        step(renderTick, renderTick, frameFraction, worldW, worldH, wrap);
    }

    /**
     * Advance every particle: own-ship exhaust to {@code ownTick}, the rest to {@code renderTick}.
     * <p>
     * BEHAVIOR: age = (tick - birth) * frameFraction original frames; age &lt; 0 is kept but invisible
     * at its origin; age &gt;= life is removed.
     * BEHAVIOR: position = origin + velocity * age, wrapped into the world when {@code wrap}; without
     * wrap a particle outside [0,W)x[0,H) is removed (it hit the world edge).
     * BEHAVIOR: removal keeps the spawn order of the survivors.
     */
    public void step(double renderTick, double ownTick, float frameFraction, double worldW, double worldH,
                     boolean wrap) {
        int w = 0;
        for (int i = 0; i < count; i++) {
            int s = slot(i);
            double age = ((own[s] ? ownTick : renderTick) - birth[s]) * frameFraction;
            if (age >= life[s]) {
                continue;
            }
            double px = x0[s];
            double py = y0[s];
            boolean born = age >= 0;
            if (born) {
                px += vx[s] * age;
                py += vy[s] * age;
                if (wrap) {
                    px = XMath.wrap(px, worldW);
                    py = XMath.wrap(py, worldH);
                } else if (px < 0 || px >= worldW || py < 0 || py >= worldH) {
                    continue;
                }
            }
            int d = slot(w++);
            if (d != s) {
                move(s, d);
            }
            x[d] = (float) px;
            y[d] = (float) py;
            visible[d] = born;
        }
        count = w;
    }

    private void move(int from, int to) {
        x0[to] = x0[from];
        y0[to] = y0[from];
        vx[to] = vx[from];
        vy[to] = vy[from];
        birth[to] = birth[from];
        life[to] = life[from];
        color[to] = color[from];
        kind[to] = kind[from];
        own[to] = own[from];
    }
}
