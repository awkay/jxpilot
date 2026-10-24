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
package com.fulcrologic.jxpilot.common.sim;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.Item;

/**
 * A ship's bullets: ports of {@code Fire_normal_shots}, {@code Fire_main_shot}, the side and rear gun shots, and
 * the ship's part of {@code Fire_general_shot} for {@code OBJ_SHOT} ({@code src/server/shot.c}) with
 * {@code Recoil} ({@code ship.c}): which guns fire, the fuel a shot costs, where it starts (the rotated gun
 * position), its velocity and the shooter's recoil. The bullet object itself is made by
 * {@link KernelEvents#shot}.
 */
public final class ShipShooter {
    /** C {@code ED_SHOT} after the {@code (long)} cast: fuel per shot (fixed point). */
    public static final long ED_SHOT = (long) (-0.2 * 256);
    /** C {@code -ED_SHOT} uncast (51.2): the fuel a player needs to fire ({@code fuel.sum < -ED_SHOT}). */
    public static final double SHOT_FUEL_NEEDED = 0.2 * 256;

    private final ShipWorld w;

    public ShipShooter(ShipWorld w) {
        this.w = w;
    }

    /**
     * Autorepeat fire for a ship holding fire (update.c:507-514).
     * <p>
     * BEHAVIOR: with fireRepeatRate &gt; 0 a ship using HAS_SHOT fires its normal shots; otherwise nothing.
     */
    public void autofire(ShipBody b, KernelEvents ev) {
        ev.begin(b, KernelEvents.PHASE_AUTOFIRE);
        if (w.shipParams().fireRepeatRate > 0 && (b.used & Has.SHOT) != 0) {
            fireNormalShots(b, ev);
        }
        ev.end(b, KernelEvents.PHASE_AUTOFIRE);
    }

    /**
     * C {@code Fire_normal_shots}.
     * <p>
     * BEHAVIOR: fires iff at least fireRepeatRate original frames passed since the last shot; then
     * remembers the time and fires the main gun along the heading.
     * BEHAVIOR: each wideangle adds a shot to each side, (i+1) * (3 - spread) RES units off the heading, from the
     * side guns in turn (the main gun without side guns); rear shots fan around the tail by (3 - spread)/2 per
     * shot, from the rear guns (the ship's centre without them), the middle one from the centre.
     */
    public void fireNormalShots(ShipBody pl, KernelEvents ev) {
        if (w.frames() < pl.shotTime + w.shipParams().fireRepeatRate) {
            return;
        }
        pl.shotTime = w.frames();
        int shotAngle = Mods.SPREAD_MAX - Mods.spread(pl.mods);
        fireMainShot(pl, pl.dir, ev);
        int wide = pl.items[Item.WIDEANGLE.ordinal()];
        ShipGeom s = pl.ship;
        for (int i = 0; i < wide; i++) {
            int left = Trig.mod(pl.dir + (1 + i) * shotAngle);
            int right = Trig.mod(pl.dir - (1 + i) * shotAngle);
            if (s.lGunX.length > 0) {
                fireGun(pl, left, s.lGunX[i % s.lGunX.length], s.lGunY[i % s.lGunX.length], ev);
            } else {
                fireMainShot(pl, left, ev);
            }
            if (s.rGunX.length > 0) {
                fireGun(pl, right, s.rGunX[i % s.rGunX.length], s.rGunY[i % s.rGunX.length], ev);
            } else {
                fireMainShot(pl, right, ev);
            }
        }
        int rear = pl.items[Item.REARSHOT.ordinal()];
        for (int i = 0; i < rear; i++) {
            int k = rear - 1 - 2 * i;
            int dir = Trig.mod(pl.dir + Const.RES / 2 + (k * shotAngle) / 2);
            if (k < 0) {
                if (s.lrGunX.length > 0) {
                    int g = (i - (rear + 1) / 2) % s.lrGunX.length;
                    fireGun(pl, dir, s.lrGunX[g], s.lrGunY[g], ev);
                } else {
                    fireShot(pl, dir, ev);
                }
            }
            if (k > 0) {
                if (s.rrGunX.length > 0) {
                    int g = (rear / 2 - i - 1) % s.rrGunX.length;
                    fireGun(pl, dir, s.rrGunX[g], s.rrGunY[g], ev);
                } else {
                    fireShot(pl, dir, ev);
                }
            }
            if (k == 0) {
                fireShot(pl, dir, ev);
            }
        }
    }

    /**
     * C {@code Fire_left_shot}/{@code Fire_right_shot}/{@code Fire_left_rshot}/{@code Fire_right_rshot}: a shot from
     * a side or rear gun (rotated offsets {@code gx}/{@code gy}).
     * <p>
     * BEHAVIOR: nothing at the shot limit or shielded/phased.
     */
    public void fireGun(ShipBody pl, int dir, float[] gx, float[] gy, KernelEvents ev) {
        if (pl.shots >= pl.shotMax || (pl.used & (Has.SHIELD | Has.PHASING_DEVICE)) != 0) {
            return;
        }
        fire(pl, pl.px + gx[pl.dir], pl.py + gy[pl.dir], dir, ev);
    }

    /** C {@code Fire_shot} for a bullet: from the ship's centre; nothing at the shot limit or shielded/phased. */
    public void fireShot(ShipBody pl, int dir, KernelEvents ev) {
        if (pl.shots >= pl.shotMax || (pl.used & (Has.SHIELD | Has.PHASING_DEVICE)) != 0) {
            return;
        }
        fire(pl, pl.px, pl.py, dir, ev);
    }

    /**
     * C {@code Fire_main_shot}.
     * <p>
     * BEHAVIOR: nothing when the player has shotMax shots in the air or has shields/phasing up; otherwise a
     * shot from the main gun position (C float: pixel position + rotated gun offset).
     */
    public void fireMainShot(ShipBody pl, int dir, KernelEvents ev) {
        if (pl.shots >= pl.shotMax || (pl.used & (Has.SHIELD | Has.PHASING_DEVICE)) != 0) {
            return;
        }
        float x = pl.px + pl.ship.gunX[pl.dir];
        float y = pl.py + pl.ship.gunY[pl.dir];
        fire(pl, x, y, dir, ev);
    }

    /**
     * The ship's part of C {@code Fire_general_shot} for an OBJ_SHOT from (x, y) pixels (C floats) along {@code dir}.
     * <p>
     * BEHAVIOR: nothing when the object pool is full ({@link KernelEvents#shotRoom}) or the ship has less than 0.2
     * fuel (51.2 fixed, C compares with the uncast ED_SHOT); otherwise the shot costs (long)ED_SHOT = 51 and the
     * shot count goes up.
     * BEHAVIOR: the start is wrapped; outside a flat world C's unpositioned object is made (no velocity, no recoil).
     * BEHAVIOR: velocity = (float)(ship velocity + (float)(tcos(dir) * shotSpeed)); the ship recoils by
     * (shot.v - ship.v) * shotMass / ship.mass (C Recoil, float).
     *
     * @return the object handle from {@link KernelEvents#shot}, or -1
     */
    public int fire(ShipBody pl, float x, float y, int dir, KernelEvents ev) {
        if (!ev.shotRoom()) {
            return -1;
        }
        ShipParams p = w.shipParams();
        if (pl.fuel < SHOT_FUEL_NEEDED) {
            return -1;
        }
        pl.addFuel(ED_SHOT);
        pl.shots++;
        x = (float) w.wrapXPixel(x);
        y = (float) w.wrapYPixel(y);
        if (x < 0 || x >= w.pixelWidth() || y < 0 || y >= w.pixelHeight()) {
            return ev.shot(pl, x, y, dir, 0f, 0f, true);
        }
        // C: the DFLOAT velocity plus the float product, added in double (shot.c Fire_general_shot)
        float svx = (float) ((double) pl.vx + Trig.tcos(dir) * p.shotSpeed);
        float svy = (float) ((double) pl.vy + Trig.tsin(dir) * p.shotSpeed);
        int h = ev.shot(pl, x, y, dir, svx, svy, false);
        float mass = p.shotMass;
        pl.vx -= ((svx - pl.vx) * mass) / pl.mass;
        pl.vy -= ((svy - pl.vy) * mass) / pl.mass;
        return h;
    }
}
