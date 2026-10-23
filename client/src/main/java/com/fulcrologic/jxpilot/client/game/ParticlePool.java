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
 * {@code age = (renderTick - b) * frameFraction} original frames, and dies when {@code age >= life}.
 * Positions are therefore a pure function of the render tick, so particles stay in step with the
 * interpolated (delayed) world. Allocation free.
 */
public final class ParticlePool implements DebrisBurst.Sink {
    private final int capacity;
    private final float[] x0;
    private final float[] y0;
    private final float[] vx;
    private final float[] vy;
    private final double[] birth;
    private final float[] life;
    private final byte[] color;
    private final byte[] kind;
    private final float[] x;
    private final float[] y;
    private final boolean[] visible;
    private int count;
    private long dropped;

    // sink state for the burst being expanded
    private float sinkX;
    private float sinkY;
    private double sinkBirth;
    private byte sinkColor;
    private byte sinkKind;

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
        x = new float[capacity];
        y = new float[capacity];
        visible = new boolean[capacity];
    }

    public int capacity() { return capacity; }

    /** Live particles (visible or not yet born); index 0..count-1. */
    public int count() { return count; }

    /** Particles not stored because the pool was full. */
    public long dropped() { return dropped; }

    /** World x at the last {@link #step}. */
    public float x(int i) { return x[i]; }

    public float y(int i) { return y[i]; }

    /** False for particles born after the render tick (draw only visible ones). */
    public boolean visible(int i) { return visible[i]; }

    /** {@link com.fulcrologic.jxpilot.common.net.Records.ObjColor}. */
    public int color(int i) { return color[i]; }

    /** {@link com.fulcrologic.jxpilot.common.net.Records.BurstKind}. */
    public int kind(int i) { return kind[i]; }

    public float vx(int i) { return vx[i]; }

    public float vy(int i) { return vy[i]; }

    /** Birth sim tick. */
    public double birth(int i) { return birth[i]; }

    /** Lifetime in original frames. */
    public float life(int i) { return life[i]; }

    public float originX(int i) { return x0[i]; }

    public float originY(int i) { return y0[i]; }

    /** BEHAVIOR: removes all particles. */
    public void clear() {
        count = 0;
    }

    /**
     * Expand a burst into particles born at {@code birthTick}, all starting at the burst origin.
     * <p>
     * BEHAVIOR: exactly the particles of {@code d.generate(rng, ...)}, in order; particles beyond the
     * capacity are counted in {@link #dropped()}.
     */
    public void spawn(DebrisBurst d, Rng rng, double birthTick, int burstKind, int objColor) {
        sinkX = d.x;
        sinkY = d.y;
        sinkBirth = birthTick;
        sinkColor = (byte) objColor;
        sinkKind = (byte) burstKind;
        d.generate(rng, this);
    }

    /** Sink callback used by {@link #spawn}. */
    @Override
    public void particle(int index, float pvx, float pvy, float speed, int plife) {
        if (count >= capacity) {
            dropped++;
            return;
        }
        int i = count++;
        x0[i] = sinkX;
        y0[i] = sinkY;
        vx[i] = pvx;
        vy[i] = pvy;
        birth[i] = sinkBirth;
        life[i] = plife;
        color[i] = sinkColor;
        kind[i] = sinkKind;
        x[i] = sinkX;
        y[i] = sinkY;
        visible[i] = false;
    }

    /**
     * Advance every particle to {@code renderTick}.
     * <p>
     * BEHAVIOR: age = (renderTick - birth) * frameFraction original frames; age &lt; 0 is kept but
     * invisible; age &gt;= life is removed.
     * BEHAVIOR: position = origin + velocity * age, wrapped into the world when {@code wrap}; without
     * wrap a particle outside [0,W)x[0,H) is removed (it hit the world edge).
     * BEHAVIOR: removal swaps the last particle into the hole (order is not preserved).
     */
    public void step(double renderTick, float frameFraction, double worldW, double worldH, boolean wrap) {
        int i = 0;
        while (i < count) {
            double age = (renderTick - birth[i]) * frameFraction;
            if (age >= life[i]) {
                remove(i);
                continue;
            }
            if (age < 0) {
                visible[i] = false;
                x[i] = x0[i];
                y[i] = y0[i];
                i++;
                continue;
            }
            double px = x0[i] + vx[i] * age;
            double py = y0[i] + vy[i] * age;
            if (wrap) {
                px = XMath.wrap(px, worldW);
                py = XMath.wrap(py, worldH);
            } else if (px < 0 || px >= worldW || py < 0 || py >= worldH) {
                remove(i);
                continue;
            }
            x[i] = (float) px;
            y[i] = (float) py;
            visible[i] = true;
            i++;
        }
    }

    private void remove(int i) {
        int last = --count;
        if (i != last) {
            x0[i] = x0[last];
            y0[i] = y0[last];
            vx[i] = vx[last];
            vy[i] = vy[last];
            birth[i] = birth[last];
            life[i] = life[last];
            color[i] = color[last];
            kind[i] = kind[last];
            x[i] = x[last];
            y[i] = y[last];
            visible[i] = visible[last];
        }
    }
}
