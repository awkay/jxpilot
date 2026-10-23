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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.math.ThrustSparks;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Wire;

/**
 * Client-side thrust sparks (PROTOCOL.md "Sparks and debris"): for each sim tick a ship is thrusting,
 * calls {@link ThrustSparks#compute} with seed {@link ThrustSparks#seed}(tick, shipId) exactly as the
 * server does, and expands the red (normal) and blue (afterburner) bursts into a {@link ParticlePool}.
 * The engine position is the ship position plus the shape's engine offset rotated to the RES heading
 * with StrictMath cos/sin tables (as C {@code Rotate_point} / the server's ship geometry).
 * <p>
 * Inputs must be what the server seeds from (PROTOCOL.md "Sparks and debris"): the wire-quantized
 * position (half pixels), velocity (1/16 px per frame) and the integer RES heading. SHIP records already
 * carry exactly that; the full-precision SELF record is quantized with {@link #quantizePos},
 * {@link #quantizeVel} and {@link #resDir} first. Allocation free.
 */
public final class ThrustSparkEmitter {
    private static final double[] SIN = new double[Const.RES];

    static {
        for (int i = 0; i < Const.RES; i++) {
            SIN[i] = StrictMath.sin(i * 2.0 * Math.PI / Const.RES);
        }
    }

    private final Rng rng = new Rng(0);
    private final DebrisBurst normal = new DebrisBurst();
    private final DebrisBurst alt = new DebrisBurst();
    private int shotLife = 60;
    private int fps = 14;
    private float frameFraction = 14f / 60f;
    private int hypotenuse;
    private double worldW = 65536;
    private double worldH = 65536;
    private boolean wrap;

    /** Set the map/option parameters (shotLife, framesPerSecond, gameFps/simHz, world size). */
    public void configure(int shotLife, int fps, float frameFraction, double worldW, double worldH, boolean wrap) {
        this.shotLife = shotLife;
        this.fps = fps;
        this.frameFraction = frameFraction;
        this.worldW = worldW;
        this.worldH = worldH;
        this.wrap = wrap;
        this.hypotenuse = hypotenuse(worldW, worldH);
    }

    /** BEHAVIOR: C's {@code World.hypotenuse}: the world diagonal truncated to an int. */
    public static int hypotenuse(double worldW, double worldH) {
        return (int) Math.hypot(worldW, worldH);
    }

    /** World diagonal used as {@link DebrisBurst#hypotenuse} (an int, as in C). */
    public int hypotenuse() {
        return hypotenuse;
    }

    /**
     * The RES heading (0..RES-1) of a heading in radians: round(heading * RES / 2pi) mod RES.
     * <p>
     * BEHAVIOR: the server's integer dir sent as SELF f32 (dir * 2pi/RES) or SHIP u16 (dir * 512) comes
     * back exactly; half steps round up, like {@link ThrustSparks#dirFromU16}.
     */
    public static int resDir(double heading) {
        return (int) Math.floor(heading * (Const.RES / (2 * Math.PI)) + 0.5) & (Const.RES - 1);
    }

    /** BEHAVIOR: a coordinate rounded to the wire's half pixels ({@code Wire.posToU16(x) * 0.5}), as the server seeds. */
    public static double quantizePos(double x) {
        return Wire.posToU16(x) * 0.5;
    }

    /** BEHAVIOR: a velocity rounded to the wire's 1/16 px per frame ({@code Wire.i16ToVel(Wire.velToI16(v))}). */
    public static float quantizeVel(double v) {
        return Wire.i16ToVel(Wire.velToI16(v));
    }

    /** BEHAVIOR: x of the engine offset (ex, ey) rotated to RES heading dir (any int, taken mod RES). */
    public static double rotX(int ex, int ey, int dir) {
        return cos(dir) * ex - sin(dir) * ey;
    }

    /** BEHAVIOR: y of the engine offset (ex, ey) rotated to RES heading dir. */
    public static double rotY(int ex, int ey, int dir) {
        return sin(dir) * ex + cos(dir) * ey;
    }

    static double sin(int dir) {
        return SIN[dir & (Const.RES - 1)];
    }

    static double cos(int dir) {
        return SIN[(dir + Const.RES / 4) & (Const.RES - 1)];
    }

    /** The normal (red) burst of the last {@link #emit}. */
    public DebrisBurst lastNormal() {
        return normal;
    }

    /** The afterburner (blue) burst of the last {@link #emit}. */
    public DebrisBurst lastAlt() {
        return alt;
    }

    /**
     * Emit one sim tick of exhaust for a ship.
     * <p>
     * BEHAVIOR: seed = ThrustSparks.seed(tick, shipId); dir = {@link #resDir}(heading).
     * BEHAVIOR: the engine is at (x, y) + rotated engine offset, wrapped into the world when it wraps.
     * BEHAVIOR: the normal burst becomes RED SPARK particles and the afterburner burst BLUE SPARK
     * particles, all born at {@code tick}, marked as own exhaust iff {@code own}.
     *
     * @param x       wire-quantized (see the class comment)
     * @param heading radians
     * @param vx      px per original frame, wire-quantized
     * @param own     the own ship's exhaust ({@link ParticlePool} steps it at the present)
     */
    public void emit(int tick, int shipId, double x, double y, double heading, float vx, float vy, float power,
                     int afterburners, int engineX, int engineY, boolean own, ParticlePool pool) {
        int dir = resDir(heading);
        double ex = x + rotX(engineX, engineY, dir);
        double ey = y + rotY(engineX, engineY, dir);
        if (wrap) {
            ex = XMath.wrap(ex, worldW);
            ey = XMath.wrap(ey, worldH);
        }
        ThrustSparks.compute(ThrustSparks.seed(tick, shipId), (float) ex, (float) ey, dir, vx, vy, power,
            afterburners, shotLife, fps, frameFraction, hypotenuse, rng, normal, alt);
        pool.spawn(normal, rng, tick, Records.BurstKind.SPARK, Records.ObjColor.RED, own);
        pool.spawn(alt, rng, tick, Records.BurstKind.SPARK, Records.ObjColor.BLUE, own);
    }
}
