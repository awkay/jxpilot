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
package com.fulcrologic.jxpilot.common.math;

import com.fulcrologic.jxpilot.common.Const;

/**
 * Parameters of one spark/debris burst, a port of the particle distribution of C {@code Make_debris}
 * ({@code src/server/play.c}). The server sends bursts instead of individual particles; server and
 * client call {@link #generate} with the same (wire-quantized) parameters and seed and get identical
 * particles. The server keeps its copies authoritative for gameplay (sparks push ships and balls,
 * debris drains fuel and can kill); the client's copies are cosmetic.
 * <p>
 * Server-side clamps of Make_debris (world-hypotenuse speed/life limits, shotLife cap, object pool
 * limits) are applied by the server before filling the parameters.
 */
public final class DebrisBurst {
    /** cos/sin of RES headings computed with StrictMath so every JVM agrees. */
    private static final float[] COS = new float[Const.RES];
    private static final float[] SIN = new float[Const.RES];

    static {
        for (int i = 0; i < Const.RES; i++) {
            COS[i] = (float) StrictMath.cos(i * 2.0 * Math.PI / Const.RES);
            SIN[i] = (float) StrictMath.sin(i * 2.0 * Math.PI / Const.RES);
        }
    }

    /** Receives generated particles. */
    public interface Sink {
        /**
         * @param vx    velocity x, px per original frame (emitter velocity included)
         * @param vy    velocity y
         * @param speed the particle's own speed draw (for C's cluster mass {@code mass / (speed/shotSpeed)^2})
         * @param life  lifetime in original frames
         */
        void particle(int index, float vx, float vy, float speed, int life);
    }

    /** Burst origin (world px). */
    public float x;
    public float y;
    /** Velocity added to every particle (usually the emitter's), px/frame. */
    public float vx;
    public float vy;
    public int seed;
    /** Number of particles. */
    public int count;
    /** First heading in RES units (0..127). */
    public int minDir;
    /** Heading span in RES units: headings are minDir + [0, span). */
    public int dirSpan;
    public float minSpeed;
    public float maxSpeed;
    /** Lifetimes in original frames. */
    public int minLife;
    public int maxLife;
    /**
     * World diagonal in pixels (C {@code World.hypotenuse}); a particle whose {@code life * speed}
     * exceeds it gets {@code life = (int)(hypotenuse / speed)}. 0 disables the clamp. Not sent on the
     * wire: both sides know the map.
     */
    public float hypotenuse;

    /**
     * Generate the particles in Make_debris order of random draws (dir, dir fraction, speed, life).
     * <p>
     * BEHAVIOR: emits exactly {@link #count} particles to the sink with indices 0..count-1.
     * BEHAVIOR: heading = (minDir + (int)(r * dirSpan)) mod RES, interpolated toward the next heading
     * by a second draw; speed = minSpeed + r * (maxSpeed - minSpeed); velocity = (vx, vy) + dir * speed.
     * BEHAVIOR: life = (int)(minLife + r * (maxLife - minLife) + 1), then if hypotenuse &gt; 0 and
     * life * speed &gt; hypotenuse, life = (int)(hypotenuse / speed) (C play.c:236).
     * BEHAVIOR: the same parameters and seed always produce the same particles.
     */
    public void generate(Rng rng, Sink sink) {
        rng.seed(seed);
        for (int i = 0; i < count; i++) {
            int dir = (minDir + (int) (rng.nextFloat() * dirSpan)) & (Const.RES - 1);
            int dirPlus = (dir + 1) & (Const.RES - 1);
            float off = rng.nextFloat();
            float dx = COS[dir] + (COS[dirPlus] - COS[dir]) * off;
            float dy = SIN[dir] + (SIN[dirPlus] - SIN[dir]) * off;
            float speed = minSpeed + rng.nextFloat() * (maxSpeed - minSpeed);
            int life = (int) (minLife + rng.nextFloat() * (maxLife - minLife) + 1);
            if (hypotenuse > 0 && life * speed > hypotenuse) {
                life = (int) (hypotenuse / speed);
            }
            sink.particle(i, vx + dx * speed, vy + dy * speed, speed, life);
        }
    }
}
