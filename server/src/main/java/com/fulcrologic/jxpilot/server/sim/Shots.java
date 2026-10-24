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
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Mods;
import com.fulcrologic.jxpilot.common.sim.ShipGeom;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.ShipShooter;
import com.fulcrologic.jxpilot.common.sim.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Bullets: ports of {@code Fire_general_shot} (the cannon-shot case and the bullet objects) and
 * {@code Delete_shot} (shots, cannon shots, sparks, debris) from {@code src/server/shot.c}; a ship's bullets
 * ({@code Fire_normal_shots}, {@code Fire_main_shot}, wide-angle and rear shots, {@code Recoil}) are the ship
 * kernel's {@link ShipShooter}; missiles and mines are
 * fired by {@link Missiles} and {@link Mines} and explode here.
 */
public final class Shots {
    /** C {@code ED_SHOT} after the {@code (long)} cast ({@link ShipShooter#ED_SHOT}). */
    static final long ED_SHOT = ShipShooter.ED_SHOT;
    /** C {@code -ED_SHOT} uncast ({@link ShipShooter#SHOT_FUEL_NEEDED}). */
    static final double SHOT_FUEL_NEEDED = ShipShooter.SHOT_FUEL_NEEDED;
    /** C {@code ED_SHOT} uncast (-51.2), as C's cluster drain formulas use it. */
    static final double ED_SHOT_F = -0.2 * 256;
    /** C {@code CANNON_SHOT_MASS} (assigned to a float). */
    static final float CANNON_SHOT_MASS = 0.4f;
    /** C {@code DEBRIS_MASS}. */
    static final float DEBRIS_MASS = 4.5f;

    private final World w;
    private final Simulation sim;

    public Shots(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    /** C {@code Fire_normal_shots} ({@link ShipShooter#fireNormalShots}). */
    public void fireNormalShots(Player pl) {
        sim.kernel.shooter.fireNormalShots(pl, sim.effects);
    }

    /** C {@code Fire_main_shot} ({@link ShipShooter#fireMainShot}). */
    public void fireMainShot(Player pl, int dir) {
        sim.kernel.shooter.fireMainShot(pl, dir, sim.effects);
    }

    /**
     * C {@code Fire_general_shot} for OBJ_SHOT / OBJ_CANNON_SHOT.
     * <p>
     * BEHAVIOR: a player's bullet is the ship kernel's ({@link ShipShooter#fire}: fuel, shot count, start, velocity,
     * recoil), which makes its object through {@link #playerShot}; {@code team}, {@code cannon}, {@code type} and
     * {@code mods} then do not apply (a player fires OBJ_SHOTs of its own team).
     * BEHAVIOR: a cannon shot: nothing when the pool is full; mass 0.4, life 8 + random%24, FROMCANNON (and
     * GRAVITY with shotsGravity); velocity (float)(tcos(dir) * shotSpeed); a (wrapped) start outside a flat world
     * makes C's unpositioned object ({@link #spawn}) and returns -1.
     *
     * @param pl   shooter, or null for cannons
     * @param x    start x in pixels (a C float)
     * @param y    start y in pixels
     * @param mods {@link Obj#MOD_CLUSTER} for cannon flak (ignored for OBJ_SHOT)
     * @return the shot handle or -1
     */
    public int fire(Player pl, int team, boolean cannon, float x, float y, int type, int dir, int mods) {
        if (pl != null) {
            return sim.kernel.shooter.fire(pl, x, y, dir, sim.effects);
        }
        Objects o = w.objects;
        Params p = w.params;
        if (o.count() >= o.capacity) {
            return -1;
        }
        int life = p.shotLife;
        float mass = p.shotMass;
        int status = Obj.GRAVITY;
        if (cannon) {
            mass = CANNON_SHOT_MASS;
            life = 8 + Integer.remainderUnsigned(w.randomMT(), 24);
            status |= Obj.FROMCANNON;
        }
        if (type == Obj.SHOT) {
            mods = 0;
        }
        if (!p.shotsGravity) {
            status &= ~Obj.GRAVITY;
        }
        x = (float) w.wrapXPixel(x);
        y = (float) w.wrapYPixel(y);
        boolean outside = x < 0 || x >= w.pixelWidth || y < 0 || y >= w.pixelHeight;
        float vx = (float) (0.0 + Trig.tcos(dir) * p.shotSpeed);
        float vy = (float) (0.0 + Trig.tsin(dir) * p.shotSpeed);
        return spawn(Obj.NO_ID, team, x, y, type, dir, mods, life, mass, status, outside, vx, vy);
    }

    /**
     * The object of a player's bullet (the ship kernel's {@link ShipEffects#shot}): the shot sound, then the object
     * with mass shotMass, life shotLife, GRAVITY with shotsGravity, the player's id and team.
     * <p>
     * BEHAVIOR: as {@link #spawn}; returns the handle, or -1 outside a flat world.
     * BEHAVIOR: the shot is tagged with the net tick and its ordinal: 0 for the ship's first bullet of the tick, then
     * 1, 2, ... in firing order (one outside a flat world still takes its ordinal, as the kernel fired it).
     */
    int playerShot(Player pl, float x, float y, int dir, float vx, float vy, boolean outside) {
        Params p = w.params;
        Sounds.play(w, pl.px, pl.py, SoundEvent.FIRE_SHOT);
        int status = p.shotsGravity ? Obj.GRAVITY : 0;
        if (pl.shotOrdinalTick != w.tick) {
            pl.shotOrdinalTick = w.tick;
            pl.shotOrdinal = 0;
        }
        int ordinal = pl.shotOrdinal++;
        int h = spawn(pl.id, pl.team, x, y, Obj.SHOT, dir, 0, p.shotLife, p.shotMass, status, outside, vx, vy);
        if (h >= 0) {
            w.objects.spawnTick[h] = w.tick;
            w.objects.shotOrdinal[h] = ordinal;
        }
        return h;
    }

    /**
     * Allocate and fill a bullet object (the object part of C {@code Fire_general_shot}).
     * <p>
     * BEHAVIOR: fuse life = life - (int)(2*SHIP_SZ/shotSpeed + 1): the owner is immune while life &gt; fuse life.
     * BEHAVIOR: {@code outside} a flat world makes C's unpositioned object (the last freed one with the life, mass,
     * type, owner and team set: it expires and gives the shot back, shot.c:950-994) and returns -1; otherwise the
     * object is positioned at (x, y) with the velocity, status, heading and mods, and its handle returned.
     */
    private int spawn(int owner, int team, float x, float y, int type, int dir, int mods, int life, float mass,
                      int status, boolean outside, float vx, float vy) {
        Objects o = w.objects;
        int fuse = (int) ((2.0 * (float) Const.SHIP_SZ) / w.params.shotSpeed + 1.0);
        int h = outside ? o.allocStale() : o.alloc();
        o.life[h] = life;
        o.fuseLife[h] = life - fuse;
        o.mass[h] = mass;
        o.counter[h] = 0;
        o.info[h] = 0;
        o.type[h] = type;
        o.owner[h] = owner;
        o.team[h] = team;
        o.color[h] = Records.ObjColor.WHITE;
        if (outside) {
            return -1;
        }
        o.initPixels(h, x, y);
        o.vx[h] = vx;
        o.vy[h] = vy;
        o.status[h] = status;
        o.missileDir[h] = dir;
        o.mods[h] = mods;
        return h;
    }

    /**
     * C {@code Delete_shot} for the S1 object kinds, then free the object.
     * <p>
     * BEHAVIOR: a player's own (non-cluster) shot gives back one shot slot.
     * BEHAVIOR: a cannon shot with mass explodes from its previous position: as cluster shots (flak: 2 white
     * fragments, life 2, kinetic mass) or else 6 + (int)(rfrac*3) red debris of mass 4.5 at speed 20, life 8-16
     * (intensity 32).
     * BEHAVIOR: sparks and debris just vanish.
     * BEHAVIOR: a ball is handled by {@link Balls#deleting}; when it is to be recreated, a new ball is made in
     * its treasure after the old one is freed.
     */
    public void delete(int h) {
        Objects o = w.objects;
        int ballTreasure = -1;
        switch (o.type[h]) {
            case Obj.SHOT -> {
                if (o.owner[h] != Obj.NO_ID && (o.status[h] & Obj.FROMCANNON) == 0
                    && (o.mods[h] & Obj.MOD_CLUSTER) == 0) {
                    Player pl = w.byId(o.owner[h]);
                    if (pl != null && --pl.shots <= 0) {
                        pl.shots = 0;
                    }
                }
            }
            case Obj.CANNON_SHOT, Obj.MINE, Obj.HEAT_SHOT, Obj.TORPEDO, Obj.SMART_SHOT -> explode(h);
            case Obj.BALL -> ballTreasure = sim.balls.deleting(h);
            case Obj.ASTEROID -> sim.asteroids.broken(h);
            case Obj.ITEM -> {
                sim.items.delete(h);
                return;
            }
            default -> { }
        }
        o.free(h);
        if (ballTreasure >= 0) {
            sim.balls.makeTreasureBall(ballTreasure);
        }
    }

    /**
     * The cluster/debris explosion of Delete_shot (shot.c:1217-1320) for shots, cannon shots, mines and missiles,
     * with C's modifier factors (mini, power, velocity, nuclear) in float.
     * <p>
     * BEHAVIOR: cluster (flak) fragments are OBJ_SHOT cluster shots, which reach clients as SHOT records, so
     * they are not also recorded as a burst; debris is recorded as a DEBRIS burst.
     */
    private void explode(int h) {
        Objects o = w.objects;
        if (o.mass[h] == 0) {
            return;
        }
        int objType = o.type[h];
        int m = o.mods[h];
        int status = Obj.GRAVITY;
        if (objType == Obj.MINE) {
            status |= Obj.COLLISIONSHOVE;
        }
        if ((o.status[h] & Obj.FROMCANNON) != 0) {
            status |= Obj.FROMCANNON;
        }
        if (Mods.isNuclear(m)) {
            Sounds.playAll(w, SoundEvent.NUKE_EXPLOSION);
        } else if (objType == Obj.MINE) {
            Sounds.play(w, o.px[h], o.py[h], SoundEvent.MINE_EXPLOSION);
        } else {
            Sounds.play(w, o.px[h], o.py[h], SoundEvent.OBJECT_EXPLOSION);
        }
        int type;
        int color;
        float mass;
        float modv;
        float numModv;
        float lifeModv;
        float speedModv;
        int intensity;
        int burstKind;
        if (Mods.isCluster(m)) {
            type = Obj.SHOT;
            color = Records.ObjColor.WHITE;
            mass = w.params.shotMass;
            mass *= 3;
            modv = 1 << Mods.velocity(m);
            numModv = 4;
            if (Mods.isNuclear(m)) {
                modv *= 4.0f;
                numModv = 1;
            }
            lifeModv = modv * 0.20f;
            speedModv = 1.0f / modv;
            intensity = (int) (o.mass[h] * 0.9 / w.params.shotMass);
            burstKind = -1;
        } else {
            type = Obj.DEBRIS;
            color = Records.ObjColor.RED;
            mass = DEBRIS_MASS;
            modv = 1;
            numModv = 1;
            lifeModv = modv;
            speedModv = modv;
            intensity = objType == Obj.MINE ? 512 : 32;
            numModv = numModv / ((float) Mods.mini(m) + 1.0f);
            burstKind = Records.BurstKind.DEBRIS;
        }
        if (Mods.isNuclear(m)) {
            float nukeFactor;
            if (objType == Obj.MINE) {
                nukeFactor = (float) (NUKE_MINE_EXPL_MULT * o.mass[h] / Mines.MINE_MASS);
            } else {
                nukeFactor = (float) (NUKE_SMART_EXPL_MULT * o.mass[h] / Missiles.MISSILE_MASS);
            }
            nukeFactor = (nukeFactor * (Mods.mini(m) + 1)) / shotMult(m);
            intensity = (int) (intensity * nukeFactor);
        }
        if (Mods.isImplosion(m)) {
            mass = -mass;
        }
        if ((objType & (Obj.TORPEDO | Obj.HEAT_SHOT | Obj.SMART_SHOT)) != 0) {
            intensity /= (1 + Mods.power(m));
        }
        sim.debris.make(o.prevX[h], o.prevY[h], o.vx[h], o.vy[h], o.owner[h], o.team[h], type, mass, status, color,
            burstKind, 6, (int) (0.20f * intensity * numModv), (int) (0.30f * intensity * numModv),
            0, Const.RES - 1, 20 * speedModv, (intensity >> 2) * speedModv, (int) (8 * lifeModv),
            (int) ((intensity >> 1) * lifeModv));
    }

    /** C {@code SHOT_MULT} of a modifier set: nukeClusterDamage for nuclear cluster warheads, else 1. */
    float shotMult(int m) {
        return Mods.isNuclear(m) && Mods.isCluster(m) ? w.items.nukeClusterDamage : 1.0f;
    }

    /** C {@code NUKE_MINE_EXPL_MULT}, {@code NUKE_SMART_EXPL_MULT}. */
    static final int NUKE_MINE_EXPL_MULT = 3;
    static final int NUKE_SMART_EXPL_MULT = 4;
}
