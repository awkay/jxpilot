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
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Wreckage: a port of {@code Make_wreckage} and the wreckage part of {@code Explode_fighter}
 * ({@code src/server/ship.c}). Wreckage pieces are pool objects of type {@link Obj#WRECKAGE} with the C
 * {@code wireobject} fields in {@link ModeState} (size, rotation, turnspeed, shape); they spin each frame
 * ({@link #rotate}), hurt ships like debris and are sent to clients as WRECKAGE records.
 */
public final class Wreckage {
    private final World w;

    public Wreckage(World w) {
        this.w = w;
    }

    /**
     * C {@code Make_wreckage}.
     * <p>
     * BEHAVIOR: nothing without useWreckage, or from outside the world (positions wrap first on wrapping maps).
     * BEHAVIOR: lives cap at shotLife when shotLife &gt;= FPS; speeds cap so speed * life fits the world
     * diagonal (int divisions, as C); at most maxWreckage pieces and the free pool slots.
     * BEHAVIOR: pieces of min..max mass are made until the total mass is used up (the last piece gets the
     * rest; less than minMass left stops); each flies at a random direction in [minDir, maxDir) (mod RES) and
     * speed in [minSpeed, maxSpeed) added to (vx, vy), lives min..max frames (+1, capped by the diagonal),
     * spins at 0.02-0.37 RES per frame from a random rotation, has size 256*1.5*mass/total (max 255), a random
     * shape, and radius size*16/256 (at least 8).
     *
     * @return the number of pieces made
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public int make(double x, double y, float vx, float vy, int owner, int team, float minMass, float maxMass,
                    float totalMass, int status, int color, int maxWreckage, int minDir, int maxDir, float minSpeed,
                    float maxSpeed, int minLife, int maxLife) {
        Params p = w.params;
        if (!w.modeParams.useWreckage) {
            return 0;
        }
        float fx = (float) x;
        float fy = (float) y;
        if (w.wrap) {
            fx = (float) w.wrapXPixel(fx);
            fy = (float) w.wrapYPixel(fy);
        }
        if (fx < 0 || fx >= w.pixelWidth || fy < 0 || fy >= w.pixelHeight) {
            return 0;
        }
        if (maxLife < minLife) {
            maxLife = minLife;
        }
        if (p.shotLife >= p.fps && minLife > p.shotLife) {
            minLife = p.shotLife;
            maxLife = p.shotLife;
        }
        int hyp = (int) p.hypotenuse;
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
        ModeState m = w.modes;
        int free = o.capacity - o.count();
        if (maxWreckage > free) {
            maxWreckage = free;
        }
        float sumMass = 0.0f;
        int made = 0;
        for (int i = 0; i < maxWreckage && sumMass < totalMass; i++) {
            float mass = (float) (minMass + w.rfrac() * (maxMass - minMass));
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
            o.color[h] = color;
            o.owner[h] = owner;
            o.team[h] = team;
            o.type[h] = Obj.WRECKAGE;
            o.initPixels(h, fx, fy);
            int dir = (minDir + (int) (w.rfrac() * ((maxDir - minDir) & (Const.RES - 1)))) & (Const.RES - 1);
            float speed = (float) (minSpeed + w.rfrac() * (maxSpeed - minSpeed));
            o.vx[h] = vx + Trig.tcos(dir) * speed;
            o.vy[h] = vy + Trig.tsin(dir) * speed;
            o.mass[h] = mass;
            sumMass += mass;
            int life = (int) (minLife + w.rfrac() * (maxLife - minLife) + 1);
            if (life * speed > hyp) {
                life = (int) (hyp / speed);
            }
            o.life[h] = life;
            o.fuseLife[h] = life;
            m.wireTurnspeed[h] = (float) (0.02 + w.rfrac() * 0.35);
            m.wireRotation[h] = (int) (w.rfrac() * Const.RES);
            int size = (int) (256.0 * 1.5 * mass / totalMass);
            if (size > 255) {
                size = 255;
            }
            m.wireSize[h] = size;
            m.wireInfo[h] = (int) (w.rfrac() * 256);
            int radius = size * 16 / 256;
            if (radius < 8) {
                radius = 8;
            }
            o.plRange[h] = radius;
            o.plRadius[h] = radius;
            o.status[h] = status;
            made++;
        }
        return made;
    }

    /**
     * The wreckage of C {@code Explode_fighter}: only for a ship that was KILLED (not one reset at a round
     * end), 2 * mass of white wreckage, pieces of max(mass/8, 0.33)..mass, at most 10, every direction, speed
     * 10..10+(int)mass/2, life 5..5+1.5*mass.
     */
    public void explodeShip(Player pl) {
        if ((pl.status & Obj.KILLED) == 0) {
            return;
        }
        make(pl.px, pl.py, pl.vx, pl.vy, pl.id, pl.team, (float) Math.max(pl.mass / 8.0, 0.33), pl.mass,
            (float) (2.0 * pl.mass), Obj.GRAVITY, Records.ObjColor.WHITE, 10, 0, Const.RES - 1, 10.0f,
            10 + (((int) pl.mass) >> 1), 5, (int) (5 + (pl.mass * 1.5)));
    }

    /**
     * Wreckage spin in the C object loop: rotation += (int)(turnspeed * RES), mod RES.
     */
    public void rotate(int h) {
        ModeState m = w.modes;
        m.wireRotation[h] = (m.wireRotation[h] + (int) (m.wireTurnspeed[h] * Const.RES)) % Const.RES;
    }
}
