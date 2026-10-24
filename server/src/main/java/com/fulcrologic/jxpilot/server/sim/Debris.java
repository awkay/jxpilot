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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.ThrustSparks;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Sparks and debris: a port of C {@code Make_debris} ({@code src/server/play.c}) and its callers
 * (ship explosions, wall-bounce sparks, thrust, turn thrust). Particles are pool objects and fully
 * simulated (they drain fuel, push ships, can kill). Each explosion is also recorded as a wire-quantized
 * {@link DebrisBurst} in {@link com.fulcrologic.jxpilot.server.world.Events}, and the server expands the
 * <em>quantized</em> burst itself so its particles equal the client's. Thrust sparks are not recorded:
 * clients recompute them from {@link ThrustSparks} with the same seed and wire-quantized ship state.
 */
public final class Debris implements DebrisBurst.Sink {
    private final World w;
    private final DebrisBurst normal = new DebrisBurst();
    private final DebrisBurst alt = new DebrisBurst();
    private final DebrisBurst scratch = new DebrisBurst();

    // ---- particle template for the sink
    private int pType;
    private float pMass;
    private int pStatus;
    private int pOwner;
    private int pTeam;
    private int pColor;
    private int pRadius;
    private boolean pKinetic;
    /** Particles made by the last {@link #make} or {@link #expand}. */
    int lastCount;

    public Debris(World w) {
        this.w = w;
    }

    /**
     * C {@code Make_debris}: a burst of {@code type} particles.
     * <p>
     * BEHAVIOR: the origin wraps on wrapping maps, is then rounded to the half pixel the wire carries (and
     * wrapped again if that reached the world edge); an origin outside the world makes nothing.
     * BEHAVIOR: lives are capped at shotLife when shotLife &gt;= FPS; speeds are capped so that
     * speed * life stays within the world diagonal (play.c:189-197).
     * BEHAVIOR: count = min + (int)(rfrac * (max - min)), limited to the free pool slots; zero makes nothing.
     * BEHAVIOR: when {@code burstKind >= 0} the quantized burst is recorded as an event for clients.
     * BEHAVIOR: OBJ_SHOT fragments are cluster shots: without shotsGravity they lose GRAVITY, and with
     * shotHitFuelDrainUsesKineticEnergy their mass is mass/(speed/shotSpeed)^2.
     *
     * @param color     {@code Records.ObjColor} of the particles
     * @param burstKind {@code Records.BurstKind}, or -1 for particles clients do not draw as a burst
     * @return number of particles made
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public int make(double x, double y, float vx, float vy, int owner, int team, int type, float mass,
                    int status, int color, int burstKind, int radius, int minDebris, int maxDebris, int minDir,
                    int maxDir, double minSpeed, double maxSpeed, int minLife, int maxLife) {
        Params p = w.params;
        lastCount = 0;
        float fx = (float) x;
        float fy = (float) y;
        if (w.wrap) {
            fx = (float) w.wrapXPixel(fx);
            fy = (float) w.wrapYPixel(fy);
        }
        if (fx < 0 || fx >= w.pixelWidth || fy < 0 || fy >= w.pixelHeight) {
            return 0;
        }
        fx = ThrustSparks.wirePos(fx);
        fy = ThrustSparks.wirePos(fy);
        if (w.wrap) {
            fx = (float) w.wrapXPixel(fx);
            fy = (float) w.wrapYPixel(fy);
        }
        if (fx >= w.pixelWidth || fy >= w.pixelHeight) {
            return 0;
        }
        if (maxLife < minLife) {
            maxLife = minLife;
        }
        if (p.shotLife >= p.fps) {
            if (minLife > p.shotLife) {
                minLife = p.shotLife;
                maxLife = p.shotLife;
            } else if (maxLife > p.shotLife) {
                maxLife = p.shotLife;
            }
        }
        float minSp = (float) minSpeed;
        float maxSp = (float) maxSpeed;
        // C World.hypotenuse is an int: the caps are integer divisions
        if (minSp * maxLife > p.hypotenuse) {
            minSp = (int) p.hypotenuse / maxLife;
        }
        if (maxSp * minLife > p.hypotenuse) {
            maxSp = (int) p.hypotenuse / minLife;
        }
        if (maxSp < minSp) {
            maxSp = minSp;
        }
        if (type == Obj.SHOT && !p.shotsGravity) {
            status &= ~Obj.GRAVITY;
        }
        int num = minDebris + (int) (w.rfrac() * (maxDebris - minDebris));
        int free = w.objects.capacity - w.objects.count();
        if (num > free) {
            num = free;
        }
        if (num <= 0) {
            return 0;
        }
        DebrisBurst d = burstKind >= 0 ? w.events.addBurst(burstKind, color, owner, w.tick) : scratch;
        d.x = fx;
        d.y = fy;
        d.vx = vx;
        d.vy = vy;
        d.seed = w.rng.nextInt();
        // replaying a C server's random stream (tests): C draws dir, offset, speed and life per particle
        // (play.c:216-235); the seed above stands for the first of them
        w.rng.skip(4 * num - 1);
        d.count = num;
        d.minDir = minDir & (Const.RES - 1);
        d.dirSpan = maxDir - minDir;
        d.minSpeed = minSp;
        d.maxSpeed = maxSp;
        d.minLife = minLife;
        d.maxLife = maxLife;
        d.hypotenuse = (float) p.hypotenuse;
        Records.Burst.quantize(d);
        return expand(d, owner, team, type, mass, status, color, radius,
            type == Obj.SHOT && p.shotHitFuelDrainUsesKineticEnergy);
    }

    /**
     * Create the particles of a burst in the pool.
     * <p>
     * BEHAVIOR: each particle starts at the burst origin with life = fuse life = its drawn life and
     * pl_range = pl_radius = radius; stops early if the pool fills.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    int expand(DebrisBurst d, int owner, int team, int type, float mass, int status, int color, int radius,
               boolean kinetic) {
        pType = type;
        pMass = mass;
        pStatus = status;
        pOwner = owner;
        pTeam = team;
        pColor = color;
        pRadius = radius;
        pKinetic = kinetic;
        lastCount = 0;
        scratch.x = d.x;
        scratch.y = d.y;
        d.generate(w.burstRng, this);
        return lastCount;
    }

    @Override
    public void particle(int index, float vx, float vy, float speed, int life) {
        Objects o = w.objects;
        int h = o.alloc();
        if (h < 0) {
            return;
        }
        lastCount++;
        o.type[h] = pType;
        o.status[h] = pStatus;
        o.owner[h] = pOwner;
        o.team[h] = pTeam;
        o.color[h] = pColor;
        o.initPixels(h, scratch.x, scratch.y);
        o.vx[h] = vx;
        o.vy[h] = vy;
        if (pKinetic) {
            float r = speed / w.params.shotSpeed;
            o.mass[h] = pMass / (r * r);
        } else {
            o.mass[h] = pMass;
        }
        if (pType == Obj.SHOT) {
            o.mods[h] = Obj.MOD_CLUSTER;
        }
        o.life[h] = life;
        o.fuseLife[h] = life;
        o.plRange[h] = pRadius;
        o.plRadius[h] = pRadius;
    }

    // ================================================================ callers

    /**
     * C {@code Explode_fighter} debris (wreckage is not made in S1).
     * <p>
     * BEHAVIOR: min = (1 + fuel/(8*256)) / 2, max = (min_unhalved + 2*mass) / 2 particles of mass 3.5,
     * speed 20 .. 20 + (int)mass/2, life 5 .. 5 + 1.5*mass, in every direction, red DEBRIS.
     */
    public void explodeShip(Player pl) {
        Sounds.play(w, pl.px, pl.py, SoundEvent.PLAYER_EXPLOSION);
        int minDebris = (int) (1 + (pl.fuel / (8.0 * 256)));
        int maxDebris = (int) (minDebris + (pl.mass * 2.0));
        minDebris >>= 1;
        maxDebris >>= 1;
        make(pl.px, pl.py, pl.vx, pl.vy, pl.id, pl.team, Obj.DEBRIS,
            3.5f, Obj.GRAVITY, Records.ObjColor.RED, Records.BurstKind.DEBRIS, 8, minDebris, maxDebris, 0,
            Const.RES - 1, 20.0, 20 + (((int) pl.mass) >> 1), 5, (int) (5 + (pl.mass * 1.5)));
    }

    /**
     * Sparks of a survived wall bounce (walls.c:2522-2535).
     * <p>
     * BEHAVIOR: intensity/2 .. intensity owner-immune, FROMBOUNCE sparks of mass 3.5 within +-RES/4 of the
     * wall normal, speed 20 .. 20 + intensity/4, life 10 .. 10 + intensity/2 (a WALL_SPARK burst).
     */
    public void wallSparks(Player pl, int wallDir, int intensity) {
        make(pl.px, pl.py, pl.vx, pl.vy, pl.id, pl.team, Obj.SPARK, 3.5f,
            Obj.GRAVITY | Obj.OWNERIMMUNE | Obj.FROMBOUNCE, Records.ObjColor.RED, Records.BurstKind.WALL_SPARK, 1,
            intensity >> 1, intensity, wallDir - (Const.RES / 4), wallDir + (Const.RES / 4), 20,
            20 + (intensity >> 2), 10, 10 + (intensity >> 1));
    }

    /**
     * Exhaust of a thrusting ship for one frame (C {@code Thrust}) via {@link ThrustSparks#computeWire}: the
     * tick and id seed, and the position and velocity quantized like the wire, so clients reproduce the
     * same sparks.
     * <p>
     * BEHAVIOR: red sparks of mass THRUST_MASS and blue afterburner sparks of mass
     * THRUST_MASS*ALT_SPARK_MASS_FACT, owner immune, radius 8.
     */
    public void thrust(Player pl) {
        Sounds.play(w, pl.px, pl.py, SoundEvent.THRUST);
        Params p = w.params;
        int dir = pl.dir;
        ThrustSparks.computeWire(w.tick, pl.id, pl.cx / (double) Click.CLICK, pl.cy / (double) Click.CLICK,
            pl.ship.engineX[dir], pl.ship.engineY[dir], dir, pl.vx, pl.vy, pl.power, pl.afterburners(), p.shotLife,
            p.fps, (float) p.hypotenuse, w.burstRng, normal, alt);
        int status = Obj.GRAVITY | Obj.OWNERIMMUNE;
        if (normal.count > 0 && inWorld(normal)) {
            expand(normal, pl.id, pl.team, Obj.SPARK, (float) ThrustSparks.THRUST_MASS, status,
                Records.ObjColor.RED, 8, false);
        }
        if (alt.count > 0 && inWorld(alt)) {
            expand(alt, pl.id, pl.team, Obj.SPARK, (float) (ThrustSparks.THRUST_MASS * ThrustSparks.ALT_SPARK_MASS_FACT),
                status, Records.ObjColor.BLUE, 8, false);
        }
    }

    /** Engine positions just outside the world wrap (Make_debris) or make nothing. */
    private boolean inWorld(DebrisBurst d) {
        if (w.wrap) {
            d.x = (float) w.wrapXPixel(d.x);
            d.y = (float) w.wrapYPixel(d.y);
            return true;
        }
        return d.x >= 0 && d.x < w.pixelWidth && d.y >= 0 && d.y < w.pixelHeight;
    }

    /**
     * C {@code Turn_thrust}: with turnThrust, {@code num} red sparks from hull point 0 sideways
     * (dir +- RES/4 by turn direction, +-0.1 RES), speed 1..3, life 1..2*FPS (a SPARK burst).
     */
    public void turnThrust(Player pl, int num) {
        float x = pl.px + pl.ship.ptX[0][pl.dir];
        float y = pl.py + pl.ship.ptY[0][pl.dir];
        int dir = pl.dir + ((pl.turnAcc > 0.0) ? (Const.RES / 4) : (3 * (Const.RES / 4)));
        if ((pl.used & Has.CLOAKING_DEVICE) != 0 && !w.params.cloakedExhaust) {
            return;
        }
        make((int) x, (int) y, pl.vx, pl.vy, pl.id, pl.team, Obj.SPARK, (float) ThrustSparks.THRUST_MASS,
            Obj.GRAVITY | Obj.OWNERIMMUNE, Records.ObjColor.RED, Records.BurstKind.SPARK, 1, num, num,
            (int) (dir - (Const.RES * 0.1) - 1), (int) (dir + (Const.RES * 0.1) + 1), 1, 3, 1, 2 * w.params.fps);
    }
}
