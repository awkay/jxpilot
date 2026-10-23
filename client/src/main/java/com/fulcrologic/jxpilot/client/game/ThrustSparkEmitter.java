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

/**
 * Client-side thrust sparks (PROTOCOL.md "Sparks and debris"): for each sim tick a ship is thrusting,
 * calls {@link ThrustSparks#compute} with seed {@link ThrustSparks#seed}(tick, shipId) exactly as the
 * server does, and expands the red (normal) and blue (afterburner) bursts into a {@link ParticlePool}.
 * The engine position is the ship position plus the shape's engine offset rotated to the RES heading
 * with StrictMath cos/sin tables (as C {@code Rotate_point} / the server's ship geometry).
 * Allocation free.
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
    private float hypotenuse;
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
        this.hypotenuse = (float) Math.sqrt(worldW * worldW + worldH * worldH);
    }

    /** World diagonal used as {@link DebrisBurst#hypotenuse}. */
    public float hypotenuse() {
        return hypotenuse;
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
     * BEHAVIOR: seed = ThrustSparks.seed(tick, shipId); dir = ThrustSparks.dirFromRadians(heading).
     * BEHAVIOR: the engine is at (x, y) + rotated engine offset, wrapped into the world when it wraps.
     * BEHAVIOR: the normal burst becomes RED SPARK particles and the afterburner burst BLUE SPARK
     * particles, all born at {@code tick}.
     *
     * @param heading radians (the wire-quantized heading for other ships)
     * @param vx      px per original frame
     */
    public void emit(int tick, int shipId, double x, double y, double heading, float vx, float vy, float power,
                     int afterburners, int engineX, int engineY, ParticlePool pool) {
        int dir = ThrustSparks.dirFromRadians(heading);
        double ex = x + rotX(engineX, engineY, dir);
        double ey = y + rotY(engineX, engineY, dir);
        if (wrap) {
            ex = XMath.wrap(ex, worldW);
            ey = XMath.wrap(ey, worldH);
        }
        ThrustSparks.compute(ThrustSparks.seed(tick, shipId), (float) ex, (float) ey, dir, vx, vy, power,
            afterburners, shotLife, fps, frameFraction, hypotenuse, rng, normal, alt);
        pool.spawn(normal, rng, tick, Records.BurstKind.SPARK, Records.ObjColor.RED);
        pool.spawn(alt, rng, tick, Records.BurstKind.SPARK, Records.ObjColor.BLUE);
    }
}
