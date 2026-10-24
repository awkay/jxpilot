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
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.world.CannonItems;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ItemParams;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Cannon items and weapons: ports of {@code Cannon_add_item}, {@code Cannon_throw_items}, {@code Cannon_init},
 * {@code Cannon_check_defense}, {@code Cannon_select_defense}, {@code Cannon_in_danger}, {@code Cannon_defend},
 * {@code Cannon_select_weapon}, the weapon parts of {@code Cannon_aim} and {@code Cannon_fire}
 * ({@code src/server/cannon.c}), and the cannon timers of {@code Update_objects} (update.c:624-685).
 */
public final class CannonWeapons {
    /** C {@code CW_*}. */
    public static final int CW_SHOT = 0;
    public static final int CW_MINE = 1;
    public static final int CW_MISSILE = 2;
    public static final int CW_LASER = 3;
    public static final int CW_ECM = 4;
    public static final int CW_TRACTORBEAM = 5;
    public static final int CW_TRANSPORTER = 6;
    public static final int CW_GASJET = 7;
    static final int CD_EM_SHIELD = 0;
    static final int CD_PHASING = 1;
    /** C {@code CANNON_DROP_ITEM_PROB}. */
    static final double CANNON_DROP_ITEM_PROB = 0.7;
    /** C {@code CANNON_SPREAD}. */
    static final int CANNON_SPREAD = Const.RES / 3;
    /** C {@code THRUST_MASS}. */
    static final float THRUST_MASS = 0.7f;
    private final World w;
    private final Simulation sim;

    public CannonWeapons(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    private boolean useItems() {
        return w.params.cannonsUseItems;
    }

    /** C {@code ENERGY_PACK_FUEL >> FUEL_SCALE_BITS}: 500 + (randomMT() &amp; 511) units. */
    private int energyPackUnits() {
        return 500 + (w.randomMT() & 511);
    }

    // ================================================================ inventory

    /** C {@code Cannon_add_item} ({@link CannonItems#addItem}). */
    public void addItem(int c, int item, int amount) {
        w.cannonItems.addItem(w.items, w.rng, c, item, amount);
    }

    private static int limit(int v, int max) {
        return v < 0 ? 0 : (v > max ? max : v);
    }

    /** C {@code Cannon_init} ({@link CannonItems#init}). */
    public void init(int c) {
        w.cannonItems.init(c, w.items, w.rng, useItems());
    }

    /**
     * C {@code Cannon_throw_items}: a dying cannon drops what it has above the initial counts.
     * <p>
     * BEHAVIOR: for every kind but fuel, packs of max_per_pack - (int)(rfrac*(1+max-min)) (within what is left)
     * are each thrown with dropItemOnKillProb * 0.7 out of the cannon's face (life 1500-2011, up to 6 px/frame);
     * the stock always drops by the pack size.
     */
    public void throwItems(int c) {
        CannonItems ci = w.cannonItems;
        ItemParams ip = w.items;
        Cannon cn = w.map.cannons().get(c);
        Objects o = w.objects;
        for (int i = 0; i < Item.COUNT; i++) {
            if (i == Item.FUEL.ordinal()) {
                continue;
            }
            ci.set(c, i, ci.item(c, i) - ip.initial[i]);
            while (ci.item(c, i) > 0) {
                int amount = ip.maxPerPack[i] - (int) (w.rfrac() * (1 + ip.maxPerPack[i] - ip.minPerPack[i]));
                amount = limit(amount, ci.item(c, i));
                int h;
                if (w.rfrac() < (ip.dropItemOnKillProb * CANNON_DROP_ITEM_PROB) && (h = o.alloc()) >= 0) {
                    o.type[h] = Obj.ITEM;
                    o.info[h] = i;
                    o.color[h] = Records.ObjColor.RED;
                    o.status[h] = Obj.GRAVITY;
                    int dir = (int) (cn.dir() - (CANNON_SPREAD * 0.5) + (w.rfrac() * CANNON_SPREAD));
                    dir = Trig.mod(dir);
                    o.owner[h] = Obj.NO_ID;
                    o.team[h] = Const.TEAM_NOT_SET;
                    o.initPixels(h, cn.x(), cn.y());
                    float velocity = (float) (w.rfrac() * 6);
                    o.vx[h] = Trig.tcos(dir) * velocity;
                    o.vy[h] = Trig.tsin(dir) * velocity;
                    o.mass[h] = 10;
                    o.life[h] = 1500 + (int) (w.rfrac() * 512);
                    o.counter[h] = amount;
                    o.plRange[h] = Items.ITEM_SIZE / 2;
                    o.plRadius[h] = Items.ITEM_SIZE / 2;
                    w.itemCount[i]++;
                }
                ci.set(c, i, ci.item(c, i) - amount);
            }
        }
    }

    // ================================================================ per frame

    /**
     * C {@code Cannon_check_defense} (update.c:627-631): with cannonsUseItems and cannonsDefend, 65% of the
     * frames (one rfrac()) a cannon with a defense and a hazardous object close by uses it.
     * <p>
     * BEHAVIOR: defenses: an emergency shield or a phasing device (4*FPS more, one item), not with smartness 0
     * or while one is already on; EMERGENCY_SHIELD_ON or PHASING_ON plays at the cannon (C Cannon_defend).
     * Danger: a killing object not from a cannon (and not of the cannon's team with
     * team immunity) within (4.5 - smartness) blocks, looking ahead by its velocity (and acceleration).
     */
    public void defend(int c) {
        if (!useItems() || !w.items.cannonsDefend || !(w.rfrac() < 0.65)) {
            return;
        }
        int defense = selectDefense(c);
        if (defense >= 0 && inDanger(c)) {
            CannonItems ci = w.cannonItems;
            Cannon cn = w.map.cannons().get(c);
            int fps = w.params.fps;
            if (defense == CD_EM_SHIELD) {
                ci.emergencyShieldLeft[c] += 4 * fps;
                ci.used[c] |= Has.EMERGENCY_SHIELD;
                ci.add(c, Item.EMERGENCY_SHIELD, -1);
                Sounds.play(w, cn.x(), cn.y(), SoundEvent.EMERGENCY_SHIELD_ON);
            } else {
                ci.phasingLeft[c] += 4 * fps;
                ci.used[c] |= Has.PHASING_DEVICE;
                ci.tractorCount[c] = 0;
                ci.add(c, Item.PHASING, -1);
                Sounds.play(w, cn.x(), cn.y(), SoundEvent.PHASING_ON);
            }
        }
    }

    int selectDefense(int c) {
        CannonItems ci = w.cannonItems;
        if (w.params.cannonSmartness == 0) {
            return -1;
        }
        if ((ci.used[c] & (Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE)) != 0) {
            return -1;
        }
        if (ci.item(c, Item.EMERGENCY_SHIELD) != 0) {
            return CD_EM_SHIELD;
        }
        if (ci.item(c, Item.PHASING) != 0) {
            return CD_PHASING;
        }
        return -1;
    }

    boolean inDanger(int c) {
        int smart = w.params.cannonSmartness;
        if (smart == 0) {
            return false;
        }
        Cannon cn = w.map.cannons().get(c);
        Objects o = w.objects;
        int killShots = w.params.killingShots | Obj.MINE | Obj.SHOT | Obj.PULSE | Obj.SMART_SHOT | Obj.HEAT_SHOT
            | Obj.TORPEDO | Obj.ASTEROID;
        int cpx = (int) cn.x();
        int cpy = (int) cn.y();
        w.grid.rebuild(o);
        w.grid.query(cn.bx(), cn.by(), 4 * Const.BLOCK_SZ, 100);
        int n = w.grid.resultCount;
        for (int k = 0; k < n; k++) {
            int h = w.grid.result[k];
            if (o.life[h] <= 0 || (o.type[h] & killShots) == 0
                || (o.status[h] & Obj.FROMCANNON) != 0) {
                continue;
            }
            if (w.params.teamPlay && w.params.teamImmunity && o.team[h] == cn.team()) {
                continue;
            }
            int npx = o.px[h];
            int npy = o.py[h];
            if (smart > 1) {
                npx = (int) (npx + o.vx[h]);
                npy = (int) (npy + o.vy[h]);
                if (smart > 2) {
                    npx = (int) (npx + o.accX[h]);
                    npy = (int) (npy + o.accY[h]);
                }
            }
            int tdx = (int) w.wrapDx(npx - cpx);
            int tdy = (int) w.wrapDy(npy - cpy);
            if (StrictMath.hypot(tdx, tdy) <= ((4.5 - smart) * Const.BLOCK_SZ)) {
                return true;
            }
        }
        return false;
    }

    /** BEHAVIOR: a cannon looks for a target only when not emergency-shielded, phased, damaged or tractoring. */
    public boolean mayFire(int c) {
        CannonItems ci = w.cannonItems;
        return (ci.used[c] & (Has.EMERGENCY_SHIELD | Has.PHASING_DEVICE)) == 0 && ci.damaged[c] == 0
            && ci.tractorCount[c] == 0;
    }

    /**
     * Item pickup of a cannon that did not look for a target (update.c:639-652): with cannonsUseItems,
     * itemProbMult and cannonItemProbMult, a random kind with a cannon weight is added (one, or an energy pack's
     * fuel) about once a minute.
     */
    public void pickItem(int c) {
        ItemParams ip = w.items;
        if (!useItems() || ip.itemProbMult <= 0 || ip.cannonItemProbMult <= 0) {
            return;
        }
        int item = (int) (w.rfrac() * Item.COUNT);
        if (ip.cannonProb[item] > 0 && ip.cannonItemProbMult > 0
            && (int) (w.rfrac() * (60 * w.params.fps)) < (ip.cannonItemProbMult * ip.cannonProb[item])) {
            addItem(c, item, item == Item.FUEL.ordinal() ? energyPackUnits() : 1);
        }
    }

    /**
     * The cannon timers (update.c:654-685).
     * <p>
     * BEHAVIOR: damage counts down; a tractor beam pulls its target while it is playing and in range (one frame
     * less each time) and stops otherwise; emergency shield and phasing time out (EMERGENCY_SHIELD_OFF /
     * PHASING_OFF at the cannon).
     */
    public void tick(int c) {
        CannonItems ci = w.cannonItems;
        if (ci.damaged[c] > 0) {
            ci.damaged[c]--;
        }
        if (ci.tractorCount[c] > 0) {
            Player p = w.byId(ci.tractorTarget[c]);
            Cannon cn = w.map.cannons().get(c);
            int items = ci.item(c, Item.TRACTOR_BEAM);
            if (p != null && w.wrapLength(p.px - (float) cn.x(), p.py - (float) cn.y()) < Devices.tractorMaxRange(items)
                && p.isAlive()) {
                sim.devices.generalTractorBeam(null, (float) cn.x(), (float) cn.y(), items, p, ci.tractorIsPressor[c]);
                ci.tractorCount[c]--;
            } else {
                ci.tractorCount[c] = 0;
            }
        }
        if (ci.emergencyShieldLeft[c] > 0 && --ci.emergencyShieldLeft[c] <= 0) {
            ci.used[c] &= ~Has.EMERGENCY_SHIELD;
            Cannon cn = w.map.cannons().get(c);
            Sounds.play(w, cn.x(), cn.y(), SoundEvent.EMERGENCY_SHIELD_OFF);
        }
        if (ci.phasingLeft[c] > 0 && --ci.phasingLeft[c] <= 0) {
            ci.used[c] &= ~Has.PHASING_DEVICE;
            Cannon cn = w.map.cannons().get(c);
            Sounds.play(w, cn.x(), cn.y(), SoundEvent.PHASING_OFF);
        }
    }

    // ================================================================ weapons

    /**
     * C {@code Cannon_select_weapon}: each weapon the cannon has is tried in order with its own chance (one
     * rfrac() per weapon tried): mine 1/2, missile 1/2, laser n/(n+1), ECM 1/3, tractor beam 1/2, transporter
     * 1/3, gas jet (afterburner or emergency thrust with fuel); otherwise shots.
     */
    public int selectWeapon(int c) {
        CannonItems ci = w.cannonItems;
        if (ci.item(c, Item.MINE) != 0 && w.rfrac() < 0.5f) {
            return CW_MINE;
        }
        if (ci.item(c, Item.MISSILE) != 0 && w.rfrac() < 0.5f) {
            return CW_MISSILE;
        }
        int lasers = ci.item(c, Item.LASER);
        if (lasers != 0 && (int) (w.rfrac() * (lasers + 1)) != 0) {
            return CW_LASER;
        }
        if (ci.item(c, Item.ECM) != 0 && w.rfrac() < 0.333f) {
            return CW_ECM;
        }
        if (ci.item(c, Item.TRACTOR_BEAM) != 0 && w.rfrac() < 0.5f) {
            return CW_TRACTORBEAM;
        }
        if (ci.item(c, Item.TRANSPORTER) != 0 && w.rfrac() < 0.333f) {
            return CW_TRANSPORTER;
        }
        int et = ci.item(c, Item.EMERGENCY_THRUST);
        if ((ci.item(c, Item.AFTERBURNER) != 0 || et != 0) && ci.item(c, Item.FUEL) != 0
            && (int) (w.rfrac() * ((et != 0 ? Devices.MAX_AFTERBURNER : ci.item(c, Item.AFTERBURNER)) + 3)) > 2) {
            return CW_GASJET;
        }
        return CW_SHOT;
    }

    /** Aim parameters of a weapon (outputs of {@link #aimRange}). */
    int aimSpeed;
    int aimRange;

    /**
     * The weapon part of C {@code Cannon_aim}: speed and range for the weapon.
     * <p>
     * BEHAVIOR: shots: shotSpeed and CANNON_SHOT_LIFE_MAX * speed; mines half (+0.1*smartness); lasers PULSE_SPEED
     * and PULSE_LIFE(1) * that; ECM a random range between ECM_DISTANCE/smartness and ECM_DISTANCE for smartness
     * &gt; 1; tractor its range; transporter TRANSPORTER_DISTANCE (smartness &gt; 2, else by chance); a gas jet with
     * emergency thrust doubles speed and range.
     */
    void aimRange(int c, int weapon) {
        CannonItems ci = w.cannonItems;
        int smart = w.params.cannonSmartness;
        int speed = (int) w.params.shotSpeed;
        int range = Cannons.CANNON_SHOT_LIFE_MAX * speed;
        switch (weapon) {
            case CW_MINE -> {
                speed = (int) (speed * 0.5 + 0.1 * smart);
                range = (int) (range * 0.5 + 0.1 * smart);
            }
            case CW_LASER -> {
                speed = Lasers.PULSE_SPEED;
                range = (int) (Lasers.pulseLife(1) * speed);
            }
            case CW_ECM -> {
                if (smart > 1) {
                    range = (int) ((Ecm.ECM_DISTANCE / smart
                        + (int) (w.rfrac() * (int) (Ecm.ECM_DISTANCE - Ecm.ECM_DISTANCE / smart))));
                } else {
                    range = (int) Ecm.ECM_DISTANCE;
                }
            }
            case CW_TRACTORBEAM -> range = (int) Devices.tractorMaxRange(ci.item(c, Item.TRACTOR_BEAM));
            case CW_TRANSPORTER -> {
                if (smart > 2 || (int) (w.rfrac() * ((smart + 1) * (smart + 1))) != 0) {
                    range = (int) Devices.TRANSPORTER_DISTANCE;
                }
            }
            case CW_GASJET -> {
                if (ci.item(c, Item.EMERGENCY_THRUST) != 0) {
                    speed *= 2;
                    range *= 2;
                }
            }
            default -> { }
        }
        aimSpeed = speed;
        aimRange = range;
    }

    /** C {@code Cannon_aim}'s visual range: CANNON_DISTANCE + 2 blocks per sensor. */
    int visualRange(int c) {
        return (int) (Cannons.CANNON_DISTANCE + 2 * w.cannonItems.item(c, Item.SENSOR) * Const.BLOCK_SZ);
    }

    /**
     * C {@code Cannon_aim}'s cloak test: a cloaked player that is not forced visible hides when
     * (int)(rfrac*(cloaks+1)) &gt; (int)(rfrac*(sensors+1)) (two rfrac() draws).
     */
    boolean hidden(int c, Player pl) {
        if (pl.forceVisible != 0 || (pl.used & Has.CLOAKING_DEVICE) == 0) {
            return false;
        }
        return (int) (w.rfrac() * (pl.items[Item.CLOAK.ordinal()] + 1))
            > (int) (w.rfrac() * (w.cannonItems.item(c, Item.SENSOR) + 1));
    }

    /**
     * C {@code Cannon_fire} for every weapon.
     * <p>
     * BEHAVIOR: mines: random cluster (1/4) and modifiers (implosion 4/5, power, velocity); half the time placed at
     * the cannon, else thrown at the target (with mini and spread); one mine used.
     * BEHAVIOR: missiles: cluster 1/3, modifiers; a smart missile, a heat-seeker at a thrusting target, or a
     * torpedo, smarter cannons preferring the advanced types; one missile used.
     * BEHAVIOR: lasers, sometimes with a laser modifier; ECM (one used); tractor/pressor beam for 11 + up to
     * 3*smartness frames; transporter (one used) when the target is close enough; gas jets of 300-700 sparks
     * (with an emergency thrust, one used) or 150-350 (one fuel pack used).
     * BEHAVIOR: shots: 1 + 2 * wideangles cannon shots fanned by (4 - smartness), flak with cannonFlak, plus
     * rear shots.
     * BEHAVIOR: last, the weapon's sound plays at the cannon: DROP_MINE / DROP_MOVING_MINE, FIRE_SMART_SHOT /
     * FIRE_HEAT_SHOT / FIRE_TORPEDO, FIRE_LASER, ECM, TRANSPORTER_FAIL (target too far), THRUST (gas jet),
     * CANNON_FIRE (shots); none for a tractor beam or a transporter used.
     */
    public void fire(int c, int weapon, Player target, int dir) {
        CannonItems ci = w.cannonItems;
        ItemParams ip = w.items;
        Cannon cn = w.map.cannons().get(c);
        int cpx = (int) cn.x();
        int cpy = (int) cn.y();
        int smart = w.params.cannonSmartness;
        int speed = (int) w.params.shotSpeed;
        int mods = 0;
        SoundEvent sound = SoundEvent.CANNON_FIRE;
        switch (weapon) {
            case CW_MINE -> {
                if (ip.allowClusters && w.rfrac() < 0.25f) {
                    mods |= Obj.MOD_CLUSTER;
                }
                if (ip.allowModifiers) {
                    if (w.rfrac() >= 0.2f) {
                        mods |= Obj.MOD_IMPLOSION;
                    }
                    mods = Mods.withPower(mods, (int) (w.rfrac() * (Mods.POWER_MAX + 1)));
                    mods = Mods.withVelocity(mods, (int) (w.rfrac() * (Mods.VELOCITY_MAX + 1)));
                }
                if (w.rfrac() < 0.5f) {
                    sim.mines.placeGeneral(null, cn.team(), Obj.FROMCANNON, cpx, cpy, 0, 0, mods);
                    sound = SoundEvent.DROP_MINE;
                } else {
                    if (ip.allowModifiers) {
                        mods = Mods.withMini(mods, (int) (w.rfrac() * Mods.MINI_MAX) + 1);
                        mods = Mods.withSpread(mods, (int) (w.rfrac() * (Mods.SPREAD_MAX + 1)));
                    }
                    speed = (int) (speed * 0.5 + 0.1 * smart);
                    sim.mines.placeGeneral(null, cn.team(), Obj.GRAVITY | Obj.FROMCANNON, cpx, cpy,
                        Trig.tcos(dir) * speed, Trig.tsin(dir) * speed, mods);
                    sound = SoundEvent.DROP_MOVING_MINE;
                }
                ci.add(c, Item.MINE, -1);
            }
            case CW_MISSILE -> {
                if (ip.allowClusters && w.rfrac() < 0.333f) {
                    mods |= Obj.MOD_CLUSTER;
                }
                if (ip.allowModifiers) {
                    if (w.rfrac() >= 0.25f) {
                        mods |= Obj.MOD_IMPLOSION;
                    }
                    mods = Mods.withPower(mods, (int) (w.rfrac() * (Mods.POWER_MAX + 1)));
                    mods = Mods.withVelocity(mods, (int) (w.rfrac() * (Mods.VELOCITY_MAX + 1)));
                }
                int pick = (int) (w.rfrac() * (1 + smart));
                boolean fired = false;
                if (pick >= 2 && ip.allowSmartMissiles) {
                    sim.missiles.fire(null, cn.team(), true, cpx, cpy, Obj.SMART_SHOT, dir, mods, target.id);
                    sound = SoundEvent.FIRE_SMART_SHOT;
                    fired = true;
                }
                if (!fired && pick >= 1 && ip.allowHeatSeekers && (target.status & Obj.THRUSTING) != 0) {
                    sim.missiles.fire(null, cn.team(), true, cpx, cpy, Obj.HEAT_SHOT, dir, mods, target.id);
                    sound = SoundEvent.FIRE_HEAT_SHOT;
                    fired = true;
                }
                if (!fired) {
                    sim.missiles.fire(null, cn.team(), true, cpx, cpy, Obj.TORPEDO, dir, mods, -1);
                    sound = SoundEvent.FIRE_TORPEDO;
                }
                ci.add(c, Item.MISSILE, -1);
            }
            case CW_LASER -> {
                if (ip.allowLaserModifiers && (w.rfrac() * (8 - smart)) >= 1) {
                    mods = Mods.withLaser(mods, (int) (w.rfrac() * (Mods.LASER_MAX + 1)));
                }
                sim.lasers.fireGeneral(null, cn.team(), cpx, cpy, dir, mods);
                sound = SoundEvent.FIRE_LASER;
            }
            case CW_ECM -> {
                sim.ecm.fireGeneral(null, cn.team(), cpx, cpy);
                ci.add(c, Item.ECM, -1);
                sound = SoundEvent.ECM;
            }
            case CW_TRACTORBEAM -> {
                ci.tractorIsPressor[c] = w.rfrac() * (smart + 1) >= 1;
                ci.tractorTarget[c] = target.id;
                ci.tractorCount[c] = 11 + (int) (w.rfrac() * ((3 * smart) + 1));
                sound = null;
            }
            case CW_TRANSPORTER -> {
                ci.add(c, Item.TRANSPORTER, -1);
                if ((int) w.wrapLength(target.px - cpx, target.py - cpy) < Devices.TRANSPORTER_DISTANCE) {
                    Devices d = sim.devices;
                    d.stolenItem = -1;
                    d.stolenAmount = 0;
                    d.generalTransporter(null, cpx, cpy, target);
                    if (d.stolenItem != -1) {
                        addItem(c, d.stolenItem, (int) d.stolenAmount);
                    }
                    sound = null;
                } else {
                    sound = SoundEvent.TRANSPORTER_FAIL;
                }
            }
            case CW_GASJET -> {
                if ((w.rfrac() * (ci.item(c, Item.EMERGENCY_THRUST) + 1)) >= 1) {
                    sim.debris.make(cpx, cpy, 0f, 0f, Obj.NO_ID, cn.team(), Obj.SPARK, THRUST_MASS,
                        Obj.GRAVITY | Obj.FROMCANNON, Records.ObjColor.RED, Records.BurstKind.SPARK, 8, 300, 700,
                        dir - 4 * (4 - smart), dir + 4 * (4 - smart), 0.1, speed * 4, 3, 20);
                    ci.add(c, Item.EMERGENCY_THRUST, -1);
                } else {
                    sim.debris.make(cpx, cpy, 0f, 0f, Obj.NO_ID, cn.team(), Obj.SPARK, THRUST_MASS,
                        Obj.GRAVITY | Obj.FROMCANNON, Records.ObjColor.RED, Records.BurstKind.SPARK, 8, 150, 350,
                        dir - 3 * (4 - smart), dir + 3 * (4 - smart), 0.1, speed * 2, 3, 20);
                }
                ci.add(c, Item.FUEL, -1);
                sound = SoundEvent.THRUST;
            }
            default -> {
                if (w.params.cannonFlak) {
                    mods = Obj.MOD_CLUSTER;
                }
                int wide = ci.item(c, Item.WIDEANGLE);
                for (int i = 0; i < 1 + 2 * wide; i++) {
                    int aDir = Trig.mod(dir + (4 - smart) * (-wide + i));
                    sim.shots.fire(null, cn.team(), true, cpx, cpy, Obj.CANNON_SHOT, aDir, mods);
                }
                int rear = ci.item(c, Item.REARSHOT);
                for (int i = 0; i < rear; i++) {
                    int aDir = (int) (dir + (Const.RES / 2) + (4 - smart) * (-((rear - 1) * 0.5) + i));
                    sim.shots.fire(null, cn.team(), true, cpx, cpy, Obj.CANNON_SHOT, Trig.mod(aDir), mods);
                }
            }
        }
        if (sound != null) {
            Sounds.play(w, cpx, cpy, sound);
        }
    }
}
