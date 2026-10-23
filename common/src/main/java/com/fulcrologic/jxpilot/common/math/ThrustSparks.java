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
import com.fulcrologic.jxpilot.common.net.Wire;

/**
 * Exhaust sparks of a thrusting ship, a port of C {@code Thrust()} ({@code src/server/ship.c}).
 * Thrust sparks are not sent on the wire: server and client both call {@link #computeWire} each sim tick
 * (one C frame) a ship is thrusting, with the tick number, the ship id, the ship state quantized like the
 * wire (heading via {@link #dirFromU16}) and the ship's power/afterburners from
 * {@link com.fulcrologic.jxpilot.common.net.Msg.PlayerThrust}. The server's particles are authoritative
 * (they push other ships), the client's are cosmetic.
 */
public final class ThrustSparks {
    private ThrustSparks() {}

    /** C {@code LG2_MAX_AFTERBURNER}. */
    public static final int LG2_MAX_AFTERBURNER = 4;
    /** C {@code MAX_AFTERBURNER}. */
    public static final int MAX_AFTERBURNER = (1 << LG2_MAX_AFTERBURNER) - 1;
    /** C {@code THRUST_MASS}. */
    public static final double THRUST_MASS = 0.7;
    /** C {@code ALT_SPARK_MASS_FACT}. */
    public static final double ALT_SPARK_MASS_FACT = 4.2;

    /**
     * Seed for a ship's sparks in a sim tick.
     * <p>
     * BEHAVIOR: deterministic in (tick, shipId); different ticks or ships give different seeds.
     */
    public static int seed(int tick, int shipId) {
        int z = tick * 0x9E3779B1 + shipId * 0x85EBCA77;
        z = (z ^ (z >>> 15)) * 0x2C1B3C6D;
        z = (z ^ (z >>> 12)) * 0x297A2D39;
        return z ^ (z >>> 15);
    }

    /** BEHAVIOR: RES heading (0..127) of a wire u16 angle, rounded to the nearest RES step. */
    public static int dirFromU16(int angleU16) {
        return (((angleU16 & 0xFFFF) + 256) >> 9) & (Const.RES - 1);
    }

    /**
     * Fill the normal (red) and afterburner (blue) bursts for one C frame (one sim tick) of thrust.
     * <p>
     * BEHAVIOR: headings span C's {@code dir + RES/2 +- (RES*0.2 + 1)} (truncated like C's int casts).
     * BEHAVIOR: speeds 1 .. (float)(1 + power*0.14); lives 3 .. 3 + (int)(power*0.35), capped at shotLife when
     * shotLife &gt;= fps (C Make_debris).
     * BEHAVIOR: C emits (int)(power*0.15 + rand(0..3) + 1) sparks per frame.
     * BEHAVIOR: with afterburners n &gt; 0, alt = ((total-1)*n &gt;&gt; 4) + 1 sparks go to {@code alt}, the rest to
     * {@code normal}; without, {@code alt.count} is 0.
     * BEHAVIOR: both bursts start at the engine position with the ship's velocity and derived seeds.
     *
     * @param engineX world x of the engine (ship position + rotated engine offset)
     * @param engineY world y of the engine
     * @param dir     ship heading in RES units ({@link #dirFromU16})
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static void compute(int seed, float engineX, float engineY, int dir, float vx, float vy, double power,
                               int afterburners, int shotLife, int fps, float hypotenuse,
                               Rng rng, DebrisBurst normal, DebrisBurst alt) {
        rng.seed(seed);
        int minDir = (int) (dir + Const.RES / 2 - Const.RES * 0.2 - 1);
        int maxDir = (int) (dir + Const.RES / 2 + Const.RES * 0.2 + 1);
        float maxSpeed = (float) (1 + power * 0.14);
        int maxLife = 3 + (int) (power * 0.35);
        int minLife = 3;
        if (shotLife >= fps && maxLife > shotLife) {
            maxLife = shotLife;
            minLife = Math.min(minLife, shotLife);
        }
        int thisRand = rng.nextInt() & 3;
        int total = (int) (power * 0.15 + thisRand + 1);
        int n = Math.min(Math.max(afterburners, 0), MAX_AFTERBURNER);
        int altCount = n > 0 ? (((total - 1) * n) >> LG2_MAX_AFTERBURNER) + 1 : 0;
        int seedA = rng.nextInt();
        int seedB = rng.nextInt();
        fill(normal, seedA, total - altCount, engineX, engineY, vx, vy, minDir, maxDir, maxSpeed, minLife,
            maxLife, hypotenuse);
        fill(alt, seedB, altCount, engineX, engineY, vx, vy, minDir, maxDir, maxSpeed, minLife, maxLife, hypotenuse);
    }

    /** BEHAVIOR: a position as the wire carries it (SHIP record): rounded to half pixels, 0..32767.5. */
    public static float wirePos(double px) {
        return Wire.posToU16(px) * 0.5f;
    }

    /** BEHAVIOR: a velocity as the wire carries it (SHIP record): rounded to 1/16 px per frame. */
    public static float wireVel(double v) {
        return Wire.i16ToVel(Wire.velToI16(v));
    }

    /**
     * The one entry point server and client share: {@link #compute} for a ship's thrust in sim tick
     * {@code tick}, from its state quantized exactly like the wire ({@link #wirePos}, {@link #wireVel}), so
     * the server's authoritative sparks and every client's cosmetic copy are identical. The server passes
     * its exact state (SELF precision); clients pass what they decoded (SHIP records, or SELF quantized
     * the same way).
     * <p>
     * BEHAVIOR: the bursts equal {@code compute(seed(tick, shipId), wirePos(x) + engineX, wirePos(y) + engineY,
     * dir, wireVel(vx), wireVel(vy), power, ...)}.
     *
     * @param engineX rotated engine offset x at {@code dir} (C {@code ship->engine[dir].x}, a float)
     * @param engineY rotated engine offset y at {@code dir}
     * @param power   the ship's power as the float C keeps and PLAYER_THRUST carries
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static void computeWire(int tick, int shipId, double x, double y, float engineX, float engineY, int dir,
                                   double vx, double vy, float power, int afterburners, int shotLife, int fps,
                                   float hypotenuse, Rng rng, DebrisBurst normal, DebrisBurst alt) {
        compute(seed(tick, shipId), wirePos(x) + engineX, wirePos(y) + engineY, dir, wireVel(vx), wireVel(vy),
            power, afterburners, shotLife, fps, hypotenuse, rng, normal, alt);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static void fill(DebrisBurst d, int seed, int count, float x, float y, float vx, float vy, int minDir,
                             int maxDir, float maxSpeed, int minLife, int maxLife, float hypotenuse) {
        d.seed = seed;
        d.count = count;
        d.x = x;
        d.y = y;
        d.vx = vx;
        d.vy = vy;
        d.minDir = minDir & (Const.RES - 1);
        d.dirSpan = maxDir - minDir;
        d.minSpeed = 1.0f;
        d.maxSpeed = maxSpeed;
        d.minLife = minLife;
        d.maxLife = maxLife;
        d.hypotenuse = hypotenuse;
    }

    /** BEHAVIOR: the wire-quantized RES heading of a heading in radians (via {@link Wire#angleToU16}). */
    public static int dirFromRadians(double heading) {
        return dirFromU16(Wire.angleToU16(heading));
    }
}
