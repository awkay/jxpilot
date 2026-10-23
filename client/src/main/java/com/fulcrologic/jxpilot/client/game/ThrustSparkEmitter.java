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
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Wire;

/**
 * Client-side thrust sparks (PROTOCOL.md "Sparks and debris"): for each sim tick (one C frame) a ship is
 * thrusting, calls {@link ThrustSparks#computeWire} exactly as the server does, and expands the red
 * (normal) and blue (afterburner) bursts into a {@link ParticlePool}. The engine offset is the shape's
 * engine point rotated to the RES heading in float over C's {@code tcos}/{@code tsin} tables (C
 * {@code Rotate_point}, the server's {@code ShipGeom}), so it is bit for bit the server's
 * {@code ship->engine[dir]}.
 * <p>
 * Inputs are what the server seeds from (PROTOCOL.md "Sparks and debris"): {@code computeWire} quantizes the
 * position (half pixels) and velocity (1/16 px per frame) like the wire, so the full-precision SELF record
 * and the already-quantized SHIP records give the server's values; the heading becomes the integer RES
 * heading with {@link #resDir}. Allocation free.
 */
public final class ThrustSparkEmitter {
    /** C {@code tbl_sin}/{@code tbl_cos}: {@code (float)} sine/cosine of {@code i * 2pi/RES} (two tables, as C). */
    private static final float[] TSIN = new float[Const.RES];
    private static final float[] TCOS = new float[Const.RES];

    static {
        for (int i = 0; i < Const.RES; i++) {
            TSIN[i] = (float) StrictMath.sin(i * (2.0 * Math.PI / Const.RES));
            TCOS[i] = (float) StrictMath.cos(i * (2.0 * Math.PI / Const.RES));
        }
    }

    private final Rng rng = new Rng(0);
    private final DebrisBurst normal = new DebrisBurst();
    private final DebrisBurst alt = new DebrisBurst();
    private int shotLife = 60;
    private int fps = 14;
    private int hypotenuse;
    private double worldW = 65536;
    private double worldH = 65536;
    private boolean wrap;

    /** Set the map/option parameters (shotLife, framesPerSecond, world size and wrap). */
    public void configure(int shotLife, int fps, double worldW, double worldH, boolean wrap) {
        this.shotLife = shotLife;
        this.fps = fps;
        this.worldW = worldW;
        this.worldH = worldH;
        this.wrap = wrap;
        this.hypotenuse = hypotenuse(worldW, worldH);
    }

    /** BEHAVIOR: C's {@code World.hypotenuse}: the world diagonal truncated to an int. */
    public static int hypotenuse(double worldW, double worldH) {
        return (int) StrictMath.hypot(worldW, worldH);
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

    /**
     * BEHAVIOR: x of the engine offset (ex, ey) rotated to RES heading dir (any int, taken mod RES), in float as
     * C {@code Rotate_point}: {@code tcos(dir) * ex - tsin(dir) * ey}.
     */
    public static float rotX(int ex, int ey, int dir) {
        return tcos(dir) * (float) ex - tsin(dir) * (float) ey;
    }

    /** BEHAVIOR: y of the engine offset (ex, ey) rotated to RES heading dir: {@code tsin(dir) * ex + tcos(dir) * ey}. */
    public static float rotY(int ex, int ey, int dir) {
        return tsin(dir) * (float) ex + tcos(dir) * (float) ey;
    }

    static float tsin(int dir) {
        return TSIN[dir & (Const.RES - 1)];
    }

    static float tcos(int dir) {
        return TCOS[dir & (Const.RES - 1)];
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
     * BEHAVIOR: the bursts are {@code ThrustSparks.computeWire(tick, shipId, x, y, rotX, rotY, dir, vx, vy, ...)}
     * with dir = {@link #resDir}(heading) and the engine offset rotated to dir.
     * BEHAVIOR: as the server ({@code Debris.thrust}), the engine position is then wrapped into a wrapping
     * world; in a bounded world a burst whose engine position is outside the world makes nothing.
     * BEHAVIOR: the normal burst becomes RED SPARK particles and the afterburner burst BLUE SPARK
     * particles, all born at {@code tick}, marked as own exhaust iff {@code own}.
     *
     * @param x       world position (quantized like the wire inside)
     * @param heading radians
     * @param vx      px per frame (quantized like the wire inside)
     * @param own     the own ship's exhaust ({@link ParticlePool} steps it at the present)
     */
    public void emit(int tick, int shipId, double x, double y, double heading, float vx, float vy, float power,
                     int afterburners, int engineX, int engineY, boolean own, ParticlePool pool) {
        int dir = resDir(heading);
        ThrustSparks.computeWire(tick, shipId, x, y, rotX(engineX, engineY, dir), rotY(engineX, engineY, dir), dir,
            vx, vy, power, afterburners, shotLife, fps, hypotenuse, rng, normal, alt);
        if (normal.count > 0 && inWorld(normal)) {
            pool.spawn(normal, rng, tick, Records.BurstKind.SPARK, Records.ObjColor.RED, own);
        }
        if (alt.count > 0 && inWorld(alt)) {
            pool.spawn(alt, rng, tick, Records.BurstKind.SPARK, Records.ObjColor.BLUE, own);
        }
    }

    /** The server's {@code Debris.inWorld}: wrap the engine position (C WRAP_XPIXEL), or reject it outside. */
    private boolean inWorld(DebrisBurst d) {
        if (wrap) {
            d.x = (float) wrapPixel(d.x, worldW);
            d.y = (float) wrapPixel(d.y, worldH);
            return true;
        }
        return d.x >= 0 && d.x < worldW && d.y >= 0 && d.y < worldH;
    }

    /** C {@code WRAP_XPIXEL}: one world size added or subtracted. */
    private static double wrapPixel(double v, double size) {
        return v < 0 ? v + size : (v >= size ? v - size : v);
    }
}
