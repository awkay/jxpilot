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
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

import java.util.function.DoubleSupplier;

/**
 * Wreckage: C {@code Make_wreckage} (ship.c:515-640) and its two callers, {@code Explode_fighter} (ship.c:671)
 * and {@code Cannon_dies} (walls.c:1583). Wreckage pieces are pool objects of type {@link Obj#WRECKAGE}: they
 * fly like debris (gravity, die at walls), turn ({@link #rotate}), hurt ships like debris and are sent to
 * clients in the WRECKAGE section. With {@code useWreckage} off nothing is made. Allocation free.
 */
public final class Wreckage {
    private final World w;
    private final boolean useWreckage;
    /** C {@code rfrac()}: the world RNG; the golden test replays C's draws through it. */
    DoubleSupplier rfrac;

    public Wreckage(World w) {
        this.w = w;
        this.useWreckage = w.options.getBool(Opt.USE_WRECKAGE);
        this.rfrac = w::rfrac;
    }

    /**
     * C {@code Make_wreckage}, with C's float, double and int arithmetic.
     * <p>
     * BEHAVIOR: nothing without useWreckage; the origin wraps on wrapping maps and nothing is made outside
     * the world.
     * BEHAVIOR: lives: max at least min; with shotLife &gt;= FPS a min life above shotLife makes both shotLife.
     * Speeds are limited so speed * life stays within the world diagonal (integer division, as C), and at most
     * the free pool slots are made.
     * BEHAVIOR: pieces are made while fewer than maxWreckage and their mass sum is below totalMass. Each draws,
     * in order: mass in [minMass, maxMass) (cut to what is left of totalMass; below minMass ends the loop),
     * direction in [minDir, maxDir) mod RES, speed in [minSpeed, maxSpeed), life min..max (+1, limited by the
     * diagonal), turn speed 0.02..0.37, rotation and shape (info).
     * BEHAVIOR: size = (int)(384 * mass / totalMass) up to 255; pl_range = pl_radius = max(8, size*16/256).
     *
     * @return the number of pieces made
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public int make(float x, float y, float velx, float vely, int owner, int team, float minMass, float maxMass,
                    float totalMass, int status, int color, int maxWreckage, int minDir, int maxDir, float minSpeed,
                    float maxSpeed, int minLife, int maxLife) {
        if (!useWreckage) {
            return 0;
        }
        Params p = w.params;
        int hyp = (int) p.hypotenuse;
        if (w.wrap) {
            if (x < 0) {
                x += w.pixelWidth;
            } else if (x >= w.pixelWidth) {
                x -= w.pixelWidth;
            }
            if (y < 0) {
                y += w.pixelHeight;
            } else if (y >= w.pixelHeight) {
                y -= w.pixelHeight;
            }
        }
        if (x < 0 || x >= w.pixelWidth || y < 0 || y >= w.pixelHeight) {
            return 0;
        }
        if (maxLife < minLife) {
            maxLife = minLife;
        }
        if (p.shotLife >= p.fps && minLife > p.shotLife) {
            minLife = p.shotLife;
            maxLife = p.shotLife;
        }
        if (minSpeed * maxLife > hyp) {
            minSpeed = hyp / maxLife;
        }
        if (maxSpeed * minLife > hyp) {
            maxSpeed = hyp / minLife;
        }
        if (maxSpeed < minSpeed) {
            maxSpeed = minSpeed;
        }
        Objects o = w.objects;
        int free = o.capacity - o.count();
        if (maxWreckage > free) {
            maxWreckage = free;
        }
        float sumMass = 0;
        int made = 0;
        for (int i = 0; i < maxWreckage && sumMass < totalMass; i++) {
            float mass = (float) (minMass + rfrac.getAsDouble() * (maxMass - minMass));
            if (sumMass + mass > totalMass) {
                mass = totalMass - sumMass;
            }
            if (mass < minMass) {
                break;
            }
            int h = o.alloc();
            if (h < 0) {
                break;
            }
            made++;
            o.color[h] = color;
            o.owner[h] = owner;
            o.team[h] = team;
            o.type[h] = Obj.WRECKAGE;
            o.initPixels(h, x, y);
            int dir = Trig.mod(minDir + (int) (rfrac.getAsDouble() * Trig.mod(maxDir - minDir)));
            float speed = (float) (minSpeed + rfrac.getAsDouble() * (maxSpeed - minSpeed));
            o.vx[h] = velx + Trig.tcos(dir) * speed;
            o.vy[h] = vely + Trig.tsin(dir) * speed;
            o.mass[h] = mass;
            sumMass += mass;
            int life = (int) (minLife + rfrac.getAsDouble() * (maxLife - minLife) + 1);
            if (life * speed > hyp) {
                life = (int) (hyp / speed);
            }
            o.life[h] = life;
            o.fuseLife[h] = life;
            o.turnSpeed[h] = (float) (0.02 + rfrac.getAsDouble() * 0.35);
            o.rotation[h] = (int) (rfrac.getAsDouble() * Const.RES);
            int size = (int) (256.0 * 1.5 * mass / totalMass);
            o.size[h] = Math.min(size, 255);
            o.info[h] = (int) (rfrac.getAsDouble() * 256);
            int radius = Math.max(8, o.size[h] * 16 / 256);
            o.plRange[h] = radius;
            o.plRadius[h] = radius;
            o.status[h] = status;
        }
        return made;
    }

    /**
     * C {@code Explode_fighter}'s wreckage (ship.c:669-687), only for a KILLED ship.
     * <p>
     * BEHAVIOR: up to 10 white pieces of mass max(mass/8, 0.33)..mass totalling 2*mass, in every direction at
     * 10 .. 10 + (int)mass/2 on top of the ship's velocity, life 5 .. 5 + 1.5*mass, owned by the ship.
     */
    public void ship(Player pl) {
        if ((pl.status & Obj.KILLED) == 0) {
            return;
        }
        float mass = pl.mass;
        make(pl.cx * (1.0f / Click.CLICK), pl.cy * (1.0f / Click.CLICK), pl.vx, pl.vy, pl.id, pl.team,
            (float) Math.max(mass / 8.0, 0.33), mass, (float) (2.0 * mass), Obj.GRAVITY, Records.ObjColor.WHITE, 10,
            0, Const.RES - 1, 10.0f, 10 + (((int) mass) >> 1), 5, (int) (5 + (mass * 1.5)));
    }

    /**
     * C {@code Cannon_dies}' wreckage (walls.c:1582-1596).
     * <p>
     * BEHAVIOR: up to 10 white unowned pieces of the cannon's team, mass 3.5..23 totalling 28, out of the
     * cannon's face (dir +- RES*0.2), speed 10..25, life 8..68.
     */
    public void cannon(Cannon c, int x, int y) {
        make(x, y, 0f, 0f, Obj.NO_ID, c.team(), 3.5f, 23f, 28f, Obj.GRAVITY, Records.ObjColor.WHITE, 10,
            (int) (c.dir() - (Const.RES * 0.2)), (int) (c.dir() + (Const.RES * 0.2)), 10f, 25f, 8, 68);
    }

    /** C update.c:567-571: a wreckage piece turns by (int)(turnspeed * RES) each frame. */
    public void rotate(int h) {
        Objects o = w.objects;
        o.rotation[h] = (o.rotation[h] + (int) (o.turnSpeed[h] * Const.RES)) % Const.RES;
    }
}
