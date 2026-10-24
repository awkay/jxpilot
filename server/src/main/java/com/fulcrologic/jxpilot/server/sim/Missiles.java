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
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.ItemParams;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.common.sim.ShipGeom;
import com.fulcrologic.jxpilot.common.sim.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Missiles: the smart, heat and torpedo cases of {@code Fire_general_shot} (with missile racks and mini
 * spread), {@code Fire_shot} and {@code Move_smart_shot} ({@code src/server/shot.c}). One tick is one C frame;
 * velocities and speeds are C floats, the direction a C {@code u_byte} taken modulo RES.
 */
public final class Missiles {

    // ---- message texts as fields: resolved when the class loads, never inside a tick (no allocation)
    private static final String T_FUEL_UNITS_TO_FIRE = Texts.of(" fuel units to fire ");
    private static final String T_HAS_LAUNCHED = Texts.of(" has launched ");
    private static final String T_MISSILES_TO_FIRE = Texts.of(" missiles to fire ");
    private static final String T_YOU_NEED_AT_LEAST = Texts.of("You need at least ");

    static final double MISSILE_POWER_SPEED_FACT = 0.25;
    static final double MISSILE_POWER_TURNSPEED_FACT = 0.75;
    static final int MINI_TORPEDO_SPREAD_TIME = 6;
    static final int MINI_TORPEDO_SPREAD_SPEED = 20;
    static final int MINI_TORPEDO_SPREAD_ANGLE = 90;
    static final int MINI_MISSILE_SPREAD_ANGLE = 45;
    static final int CONFUSED_UPDATE_GRANULARITY = 10;
    static final int CONFUSED_TIME = 3;
    /** C {@code SPEED_LIMIT}. */
    static final double SPEED_LIMIT = 65.0;
    /** C {@code MISSILE_MASS}, {@code MISSILE_RANGE}, {@code MISSILE_LEN}. */
    static final float MISSILE_MASS = 5.0f;
    static final int MISSILE_RANGE = 4;
    static final int MISSILE_LEN = 15;
    /** C {@code SMART_SHOT_*}. */
    static final double SMART_SHOT_ACC = 0.6;
    static final int SMART_SHOT_DECFACT = 3;
    static final double SMART_SHOT_MIN_SPEED = SMART_SHOT_ACC * 8;
    static final double SMART_TURNSPEED = 2.6;
    static final double SMART_SHOT_MAX_SPEED = 22.0;
    static final int SMART_SHOT_LOOK_AH = 4;
    /** C {@code TORPEDO_RANGE}, {@code NUKE_RANGE} (from MINE_RANGE). */
    static final double TORPEDO_RANGE = Mines.MINE_RANGE * 0.45;
    static final double NUKE_RANGE = Mines.MINE_RANGE * 1.5;
    /** C {@code HEAT_*}. */
    static final double HEAT_RANGE = Const.VISIBILITY_DISTANCE / 2;
    static final double HEAT_SPEED_FACT = 1.7;
    static final double HEAT_CLOSE_RANGE = HEAT_RANGE;
    static final int HEAT_CLOSE_ERROR = 0;
    static final double HEAT_MID_RANGE = 2 * HEAT_RANGE;
    static final int HEAT_MID_ERROR = 8;
    static final int HEAT_WIDE_ERROR = 16;
    /** C {@code ED_SMART_SHOT}. */
    static final long ED_SMART_SHOT = -30 * 256;
    /** C {@code NUKE_MASS_MULT}. */
    static final int NUKE_MASS_MULT = 1;
    /** C {@code MAX_AFTERBURNER}. */
    static final int MAX_AFTERBURNER = 15;
    /** Block parts of the look-ahead (C {@code BLOCK_PARTS}). */
    static final int BLOCK_PARTS = 2;
    /** Neighbour offsets of the wall avoidance (C {@code sur}). */
    private static final int[] SUR_DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] SUR_DY = {0, 1, 1, 1, 0, -1, -1, -1};

    private final World w;
    private final Simulation sim;
    /** Handles of the minis of one salvo, for the recoil after all are fired. */
    private final int[] salvo = new int[Mods.MINI_MAX + 1];

    public Missiles(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    /** C {@code CLUSTER_MASS_SHOTS}: fragments a cluster warhead of this mass makes. */
    static double clusterMassShots(double mass, float shotMass) {
        return mass * 0.9 / shotMass;
    }

    /** C {@code HEAT_CLOSE_TIMEOUT}, {@code HEAT_MID_TIMEOUT}, {@code HEAT_WIDE_TIMEOUT}. */
    int heatCloseTimeout() {
        return 2 * w.params.fps;
    }

    int heatMidTimeout() {
        return 4 * w.params.fps;
    }

    int heatWideTimeout() {
        return 8 * w.params.fps;
    }

    /**
     * C {@code Fire_shot} for a missile type (KEY_FIRE_MISSILE/HEAT/TORPEDO and robots).
     * <p>
     * BEHAVIOR: nothing at the shot limit or shielded/phased; otherwise a missile from the ship position.
     */
    public void fireShot(Player pl, int type, int dir) {
        if (pl.shots >= pl.shotMax || (pl.used & (Has.SHIELD | Has.PHASING_DEVICE)) != 0) {
            return;
        }
        fire(pl, pl.team, false, pl.px, pl.py, type, dir, pl.mods, -1);
    }

    /** The missile key press: with missiles left, {@link #fireShot} along the heading. */
    public void key(Player pl, int type) {
        if (pl.items[Item.MISSILE.ordinal()] > 0) {
            fireShot(pl, type, pl.dir);
        }
    }

    /**
     * The missile cases of C {@code Fire_general_shot}, by a player ({@code pl}) or none (cannons, rogue
     * heat-seekers).
     * <p>
     * BEHAVIOR: nothing when the pool is full (or lacks room for the minis) or a player has no missiles.
     * Disallowed smart missiles or heat-seekers become torpedoes when those are allowed, else nothing.
     * BEHAVIOR: velocity needs cluster, spread needs mini, nuclear needs nukeMinSmarts &gt; 0. A nuclear missile
     * uses nukeMinSmarts missiles (all with FULLNUCLEAR), is that many times heavier and has range NUKE_RANGE
     * (torpedo) or MISSILE_RANGE; without enough missiles "You need at least N missiles to fire ...".
     * BEHAVIOR: drain = used * ED_SMART_SHOT plus the cluster drain for a player; a player without that fuel gets
     * "You need at least N fuel units to fire ..."; a killed player's missiles live (int)(rfrac*FPS), others
     * missileLife or MISSILE_LIFETIME. A nuke launch is announced to all with NUKE_LAUNCH; a player's single
     * smart missile or torpedo plays FIRE_SMART_SHOT / FIRE_TORPEDO at it once paid for.
     * BEHAVIOR: heat-seekers lock on nobody (-1) and fly 1.7 times faster (a player's plays FIRE_HEAT_SHOT, even
     * when it then lacks fuel); a player's smart missile needs a
     * lock on a visible player in sensor range, else nothing (a cannon gives the target); torpedoes have no
     * lock and an extra fuse of 8.
     * BEHAVIOR: power raises speed, max speed and turn speed; fuse = 2*SHIP_SZ/speed + 1 (+8 torpedo).
     * BEHAVIOR: a player's minis come from its missile racks in turn, spread by their rack position; torpedo
     * minis get a decaying sideways push; other minis fan out over 45 degrees / (spread+1). All share life
     * and mass; the shooter recoils from all of them at the end.
     *
     * @param target the lock of a smart missile fired without a player
     */
    public void fire(Player pl, int team, boolean cannon, double x, double y, int type, int dir, int mods,
                     int target) {
        Objects o = w.objects;
        ItemParams ip = w.items;
        if (o.count() >= o.capacity) {
            return;
        }
        if (!Mods.isCluster(mods)) {
            mods = Mods.withVelocity(mods, 0);
        }
        if (Mods.mini(mods) == 0) {
            mods = Mods.withSpread(mods, 0);
        }
        int life = w.params.shotLife;
        int status = Obj.GRAVITY;
        if (cannon) {
            life = 8 + Integer.remainderUnsigned(w.randomMT(), 24);
            status |= Obj.FROMCANNON;
        }
        if (type == Obj.SMART_SHOT || type == Obj.HEAT_SHOT) {
            if (type == Obj.HEAT_SHOT ? !ip.allowHeatSeekers : !ip.allowSmartMissiles) {
                if (ip.allowTorpedoes) {
                    type = Obj.TORPEDO;
                } else {
                    return;
                }
            }
        } else if (type != Obj.TORPEDO) {
            return;
        }
        if (o.count() + Mods.mini(mods) >= o.capacity) {
            return;
        }
        if (pl != null && pl.items[Item.MISSILE.ordinal()] <= 0) {
            return;
        }
        if (ip.nukeMinSmarts <= 0) {
            mods = Mods.withNuclear(mods, Mods.nuclear(mods) & ~Mods.NUCLEAR);
        }
        int used;
        float mass;
        int plRange;
        if (Mods.isNuclear(mods)) {
            if (pl != null) {
                used = Mods.isFullNuclear(mods) ? pl.items[Item.MISSILE.ordinal()] : ip.nukeMinSmarts;
                if (pl.items[Item.MISSILE.ordinal()] < ip.nukeMinSmarts) {
                    StringBuilder m = w.events.playerMessage(Msg.Text.KIND_GAME, Obj.NO_ID, pl.id)
                        .append(T_YOU_NEED_AT_LEAST).append(ip.nukeMinSmarts).append(T_MISSILES_TO_FIRE);
                    ShotNames.append(m, type, status, mods, false).append('!');
                    return;
                }
            } else {
                used = ip.nukeMinSmarts;
            }
            mass = MISSILE_MASS * used * NUKE_MASS_MULT;
            plRange = type == Obj.TORPEDO ? (int) NUKE_RANGE : MISSILE_RANGE;
        } else {
            mass = MISSILE_MASS;
            used = 1;
            plRange = type == Obj.TORPEDO ? (int) TORPEDO_RANGE : MISSILE_RANGE;
        }
        plRange /= Mods.mini(mods) + 1;
        int plRadius = MISSILE_LEN;
        long drain = used * ED_SMART_SHOT;
        if (Mods.isCluster(mods) && pl != null) {
            drain += (long) (clusterMassShots(mass, w.params.shotMass) * Shots.ED_SHOT_F);
        }
        if (pl != null && (pl.status & Obj.KILLED) != 0) {
            life = (int) (w.rfrac() * w.params.fps);
        } else if (!cannon) {
            life = ip.missileLife != 0 ? ip.missileLife
                : Integer.remainderUnsigned(w.randomMT(), 64 * w.params.fps - 1) + 128 * w.params.fps;
        }
        int lock = 0;
        int fuse = 0;
        float speed = w.params.shotSpeed;
        float maxSpeed = (float) SPEED_LIMIT;
        float turnspeed = 0;
        switch (type) {
            case Obj.HEAT_SHOT -> {
                lock = -1;
                if (pl != null) {
                    Sounds.play(w, pl.px, pl.py, SoundEvent.FIRE_HEAT_SHOT);
                }
                maxSpeed = (float) (SMART_SHOT_MAX_SPEED * HEAT_SPEED_FACT);
                turnspeed = (float) (SMART_TURNSPEED * HEAT_SPEED_FACT);
                speed *= HEAT_SPEED_FACT;
            }
            case Obj.SMART_SHOT -> {
                if (pl == null) {
                    lock = target;
                } else {
                    Player t = w.byId(pl.lockPlId);
                    if ((pl.lockTagged & Player.LOCK_PLAYER) == 0
                        || (pl.lockDistance > pl.sensorRange && ip.limitedVisibility)
                        || t == null || !pl.canSee[t.ind]) {
                        return;
                    }
                    lock = pl.lockPlId;
                }
                maxSpeed = (float) SMART_SHOT_MAX_SPEED;
                turnspeed = (float) SMART_TURNSPEED;
            }
            default -> {
                lock = -1;
                fuse = 8;
            }
        }
        if (pl != null) {
            if (pl.fuel < -drain) {
                StringBuilder m = w.events.playerMessage(Msg.Text.KIND_GAME, Obj.NO_ID, pl.id)
                    .append(T_YOU_NEED_AT_LEAST).append((-drain) >> 8).append(T_FUEL_UNITS_TO_FIRE);
                ShotNames.append(m, type, status, mods, false).append('!');
                return;
            }
            pl.addFuel(drain);
            pl.items[Item.MISSILE.ordinal()] -= used;
            if (used > 1) {
                StringBuilder m = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name)
                    .append(T_HAS_LAUNCHED);
                ShotNames.append(m, type, status, mods, false).append('!');
                Sounds.playAll(w, SoundEvent.NUKE_LAUNCH);
            } else if (type == Obj.SMART_SHOT) {
                Sounds.play(w, pl.px, pl.py, SoundEvent.FIRE_SMART_SHOT);
            } else if (type == Obj.TORPEDO) {
                Sounds.play(w, pl.px, pl.py, SoundEvent.FIRE_TORPEDO);
            }
        }
        int minis = Mods.mini(mods) + 1;
        int power = Mods.power(mods);
        speed *= (1 + (power * MISSILE_POWER_SPEED_FACT));
        maxSpeed *= (1 + (power * MISSILE_POWER_SPEED_FACT));
        turnspeed *= (1 + (power * MISSILE_POWER_TURNSPEED_FACT));
        float spread = (float) (Mods.spread(mods) + 1);
        fuse += (int) ((2.0 * (float) Const.SHIP_SZ) / speed + 1.0);

        int onThisRack = 0;
        int racksLeft = 0;
        int rackNo = 0;
        int side = 0;
        ShipGeom ship = pl != null ? pl.ship : null;
        int numRacks = ship != null ? ship.rackX.length : 0;
        if (pl != null) {
            racksLeft = numRacks;
            rackNo = pl.missileRack - 1;
            if (++pl.missileRack >= numRacks) {
                pl.missileRack = 0;
            }
        }
        int fired = 0;
        for (int r = 0, i = 0; i < minis; i++, r++) {
            if (!o.hasFree()) {
                break;
            }
            float sx = (float) x;
            float sy = (float) y;
            if (pl != null) {
                if (r == onThisRack) {
                    onThisRack = (minis - i) / racksLeft--;
                    if (onThisRack < 1) {
                        onThisRack = 1;
                    }
                    if (++rackNo >= numRacks) {
                        rackNo = 0;
                    }
                    r = 0;
                }
                sx += ship.rackX[rackNo][pl.dir];
                sy += ship.rackY[rackNo][pl.dir];
                side = (int) ship.rackY[rackNo][0];
            }
            sx = (float) w.wrapXPixel(sx);
            sy = (float) w.wrapYPixel(sy);
            boolean outside = sx < 0 || sx >= w.pixelWidth || sy < 0 || sy >= w.pixelHeight;
            // C allocates first and fills these fields; off the world (no edge wrap) it continues with the object
            // allocated and the rest of it left as its previous use left it (shot.c:991-994)
            int h = outside ? o.allocStale() : o.alloc();
            o.life[h] = life / minis;
            o.fuseLife[h] = o.life[h] - fuse;
            o.mass[h] = mass / minis;
            o.counter[h] = 0;
            o.info[h] = lock;
            o.type[h] = type;
            o.owner[h] = pl != null ? pl.id : Obj.NO_ID;
            o.team[h] = team;
            o.color[h] = Records.ObjColor.WHITE;
            o.turnSpeed[h] = turnspeed;
            o.maxSpeed[h] = maxSpeed;
            if (outside) {
                continue;
            }
            o.initPixels(h, sx, sy);
            float angle;
            if (pl == null) {
                angle = 0.0f;
            } else {
                if (onThisRack <= 1) {
                    angle = 0.0f;
                } else {
                    angle = (float) (onThisRack - 1 - 2 * r);
                    angle = (float) (angle / (3.0 * (float) (onThisRack - 1)));
                }
                angle = (float) (angle + (float) (2 * side) / (float) (3 * Const.SHIP_SZ));
            }
            int ldir;
            float mvx;
            float mvy;
            if (type == Obj.TORPEDO) {
                angle = (float) (angle * ((MINI_TORPEDO_SPREAD_ANGLE / 360.0) * Const.RES));
                ldir = Trig.mod(dir + (int) angle);
                mvx = MINI_TORPEDO_SPREAD_SPEED * Trig.tcos(ldir) / spread;
                mvy = MINI_TORPEDO_SPREAD_SPEED * Trig.tsin(ldir) / spread;
                o.spreadLeft[h] = MINI_TORPEDO_SPREAD_TIME;
                o.accX[h] = -mvx / MINI_TORPEDO_SPREAD_TIME;
                o.accY[h] = -mvy / MINI_TORPEDO_SPREAD_TIME;
                ldir = dir;
            } else {
                angle = (float) (angle * ((MINI_MISSILE_SPREAD_ANGLE / 360.0) * Const.RES / spread));
                ldir = Trig.mod(dir + (int) angle);
                mvx = mvy = 0;
                o.accX[h] = o.accY[h] = 0;
            }
            o.vx[h] = (float) (mvx + (pl != null ? pl.vx : 0.0) + Trig.tcos(ldir) * speed);
            o.vy[h] = (float) (mvy + (pl != null ? pl.vy : 0.0) + Trig.tsin(ldir) * speed);
            o.status[h] = status;
            o.missileDir[h] = ldir;
            o.mods[h] = mods;
            o.plRange[h] = plRange;
            o.plRadius[h] = plRadius;
            salvo[fired++] = h;
        }
        if (pl != null) {
            for (int i = 0; i < fired; i++) {
                int h = salvo[i];
                pl.vx -= ((o.vx[h] - pl.vx) * o.mass[h]) / pl.mass;
                pl.vy -= ((o.vy[h] - pl.vy) * o.mass[h]) / pl.mass;
            }
        }
    }

    // ================================================================ guidance

    /**
     * C {@code Move_smart_shot}: steer a missile before it moves.
     * <p>
     * BEHAVIOR: a torpedo accelerates along its launch direction for TORPEDO_SPEED_TIME frames (a nuke 5 times
     * harder for NUKE_SPEED_TIME), power-scaled; its mini spread acceleration stops when the spread time is over.
     * BEHAVIOR: a heat-seeker follows its target while the target thrusts (error 0/8/16 by range), otherwise
     * its error counter grows and after a timeout it looks for the thrusting engine that is hottest within
     * range (afterburners are easier to see); without a target it does nothing; its aim errs by up to 4*count.
     * BEHAVIOR: a confused smart missile picks a random target every 10th frame (or at once) while the
     * confusion lasts, then re-locks on the ECM-chosen target with 50-100% probability by ECM distance.
     * BEHAVIOR: missiles lead the target by range/speed, slow down before walls ahead (look-ahead), pick the
     * free neighbour direction closest to their heading near walls, turn at most turnspeed per frame toward
     * the aim and speed up by acc up to max speed.
     */
    public void move(int h) {
        Objects o = w.objects;
        int fps = w.params.fps;
        if (o.type[h] == Obj.TORPEDO) {
            double acc;
            if (Mods.isNuclear(o.mods[h])) {
                acc = (o.info[h]++ < 2 * fps) ? 5 * torpedoAcc() : 0.0;
            } else {
                acc = (o.info[h]++ < 2 * fps) ? torpedoAcc() : 0.0;
            }
            float facc = (float) acc;
            facc = (float) (facc * (1 + (Mods.power(o.mods[h]) * MISSILE_POWER_SPEED_FACT)));
            if (o.spreadLeft[h]-- <= 0) {
                o.accX[h] = 0;
                o.accY[h] = 0;
            }
            o.vx[h] += facc * Trig.tcos(o.missileDir[h]);
            o.vy[h] += facc * Trig.tsin(o.missileDir[h]);
            return;
        }
        float acc = (float) SMART_SHOT_ACC;
        float xDif = 0;
        float yDif = 0;
        float range = 0;
        Player pl;
        if (o.type[h] == Obj.HEAT_SHOT) {
            acc = (float) (SMART_SHOT_ACC * HEAT_SPEED_FACT);
            pl = null;
            if (o.info[h] >= 0) {
                pl = w.byId(o.info[h]);
            }
            if (pl != null) {
                range = w.wrapLength(pl.px + pl.ship.engineX[pl.dir] - o.px[h],
                    pl.py + pl.ship.engineY[pl.dir] - o.py[h]);
            } else {
                o.counter[h] = heatWideTimeout() + HEAT_WIDE_ERROR;
            }
            if (pl != null && (pl.status & Obj.THRUSTING) != 0) {
                if (range < HEAT_CLOSE_RANGE) {
                    o.counter[h] = HEAT_CLOSE_ERROR;
                } else if (range < HEAT_MID_RANGE) {
                    o.counter[h] = HEAT_MID_ERROR;
                } else {
                    o.counter[h] = HEAT_WIDE_ERROR;
                }
            } else {
                o.counter[h]++;
                if ((range < HEAT_CLOSE_RANGE && o.counter[h] > heatCloseTimeout() + HEAT_CLOSE_ERROR)
                    || (range < HEAT_MID_RANGE && o.counter[h] > heatMidTimeout() + HEAT_MID_ERROR)
                    || o.counter[h] > heatWideTimeout() + HEAT_WIDE_ERROR) {
                    range = (float) (HEAT_RANGE * (o.counter[h] / heatCloseTimeout()));
                    for (int k = 0; k < w.numPlayers; k++) {
                        Player p = w.playerAt(k);
                        if ((p.status & Obj.THRUSTING) == 0) {
                            continue;
                        }
                        float l = w.wrapLength(p.px + p.ship.engineX[p.dir] - o.px[h],
                            p.py + p.ship.engineY[p.dir] - o.py[h]);
                        l *= MAX_AFTERBURNER + 1 - p.items[Item.AFTERBURNER.ordinal()];
                        l /= MAX_AFTERBURNER + 1;
                        if ((p.have & Has.AFTERBURNER) != 0) {
                            l *= 16 - p.items[Item.AFTERBURNER.ordinal()];
                        }
                        if (l < range) {
                            o.info[h] = p.id;
                            range = l;
                            o.counter[h] = l < HEAT_CLOSE_RANGE ? HEAT_CLOSE_ERROR
                                : (l < HEAT_MID_RANGE ? HEAT_MID_ERROR : HEAT_WIDE_ERROR);
                            pl = p;
                        }
                    }
                }
            }
            if (o.info[h] < 0) {
                return;
            }
            xDif = (int) (w.rfrac() * 4 * o.counter[h]);
            yDif = (int) (w.rfrac() * 4 * o.counter[h]);
        } else {
            if ((o.status[h] & Obj.CONFUSED) != 0
                && ((w.frames % CONFUSED_UPDATE_GRANULARITY) == 0 || o.counter[h] == CONFUSED_TIME)) {
                if (o.counter[h] != 0) {
                    o.info[h] = w.playerAt((int) (w.rfrac() * w.numPlayers)).id;
                    o.counter[h]--;
                } else {
                    o.status[h] &= ~Obj.CONFUSED;
                    float r = (float) ((Ecm.ECM_DISTANCE - o.ecmRange[h]) / Ecm.ECM_DISTANCE);
                    r *= 100.0f;
                    if ((int) (w.rfrac() * 100) <= ((int) (r / 2) + 50)) {
                        o.info[h] = o.newInfo[h];
                    }
                }
            }
            pl = w.byId(o.info[h]);
        }
        if (pl == null) {
            return;
        }
        acc = (float) (acc * (1 + (Mods.power(o.mods[h]) * MISSILE_POWER_SPEED_FACT)));
        float shotSpeed = (float) StrictMath.hypot(o.vx[h], o.vy[h]);
        if (shotSpeed < 1) {
            shotSpeed = 1;
        }
        range = w.wrapLength(pl.px - o.px[h], pl.py - o.py[h]);
        xDif += pl.vx * (range / shotSpeed);
        yDif += pl.vy * (range / shotSpeed);
        int theta = wrapFindDir(pl.px + xDif - o.px[h], pl.py + yDif - o.py[h]);

        float vx = o.vx[h];
        float vy = o.vy[h];
        float xx = shotSpeed / (Const.BLOCK_SZ * BLOCK_PARTS);
        vx /= xx;
        vy /= xx;
        float px = o.px[h];
        float py = o.py[h];
        boolean foundw = false;
        for (int i = SMART_SHOT_LOOK_AH; i > 0 && !foundw; i--) {
            int xi = (int) ((px += vx) / Const.BLOCK_SZ);
            int yi = (int) ((py += vy) / Const.BLOCK_SZ);
            if (w.wrap) {
                if (xi < 0) {
                    xi += w.width;
                } else if (xi >= w.width) {
                    xi -= w.width;
                }
                if (yi < 0) {
                    yi += w.height;
                } else if (yi >= w.height) {
                    yi -= w.height;
                }
            }
            if (xi < 0 || xi >= w.width || yi < 0 || yi >= w.height) {
                break;
            }
            if (isObstacle(w.map.block(xi, yi))) {
                if (range > (SMART_SHOT_LOOK_AH - i) * (Const.BLOCK_SZ / BLOCK_PARTS)) {
                    if (shotSpeed > SMART_SHOT_MIN_SPEED) {
                        shotSpeed = (float) (shotSpeed - acc * (SMART_SHOT_DECFACT + 1));
                    }
                }
                foundw = true;
            }
        }
        int i = ((o.missileDir[h] * 8 / Const.RES) & 7) + 8;
        int xi = o.px[h] / Const.BLOCK_SZ;
        int yi = o.py[h] / Const.BLOCK_SZ;
        int angle = -1;
        int freemax = 0;
        for (int j = 2; j >= -2; --j) {
            int k = 0;
            for (int si = 1; si >= -1; --si) {
                int xt = xi + SUR_DX[(i + j + si) & 7];
                int yt = yi + SUR_DY[(i + j + si) & 7];
                if (xt >= 0 && xt < w.width && yt >= 0 && yt < w.height) {
                    if (isObstacle(w.map.block(xt, yt))) {
                        if (si == 0) {
                            k = -32;
                        }
                    } else {
                        ++k;
                    }
                }
            }
            if (k > freemax || (k == freemax && ((j == -1 && (w.rfrac() < 0.5f)) || j == 0 || j == 1))) {
                freemax = k > 2 ? 2 : k;
                angle = i + j;
            }
            if (k == 3 && j == 0) {
                angle = -1;
                break;
            }
        }
        if (angle >= 0) {
            int ai = angle & 7;
            theta = wrapFindDir((yi + SUR_DY[ai]) * Const.BLOCK_SZ - (o.py[h] + 2 * o.vy[h]),
                (xi + SUR_DX[ai]) * Const.BLOCK_SZ - (o.px[h] - 2 * o.vx[h]));
        }
        angle = theta;
        if (angle < 0) {
            angle += Const.RES;
        }
        angle %= Const.RES;
        int mdir = o.missileDir[h];
        if (angle < mdir) {
            angle += Const.RES;
        }
        angle = angle - mdir - Const.RES / 2;
        float ts = o.turnSpeed[h];
        if (angle < 0) {
            mdir += ((-angle < ts) ? -angle : (int) ts) & 0xFF;
        } else {
            mdir -= ((angle < ts) ? angle : (int) ts) & 0xFF;
        }
        mdir &= 0xFF;
        o.missileDir[h] = Trig.mod(mdir);
        if (shotSpeed < o.maxSpeed[h]) {
            shotSpeed += acc;
        }
        o.vx[h] = Trig.tcos(o.missileDir[h]) * shotSpeed;
        o.vy[h] = Trig.tsin(o.missileDir[h]) * shotSpeed;
    }

    /** C {@code TORPEDO_ACC} = 18 * SMART_SHOT_MAX_SPEED / (FPS * TORPEDO_SPEED_TIME). */
    double torpedoAcc() {
        int fps = w.params.fps;
        return 18.0 * SMART_SHOT_MAX_SPEED / (fps * (2 * fps));
    }

    /** C {@code Wrap_findDir}: findDir of the wrapped float offset, truncated to an int as C's callers do. */
    int wrapFindDir(double dx, double dy) {
        float fx = (float) w.wrapDx((float) dx);
        float fy = (float) w.wrapDy((float) dy);
        return (int) XMath.findDir(fx, fy);
    }

    /** The blocks smart missiles steer around (C's switch in {@code Move_smart_shot}). */
    static boolean isObstacle(int b) {
        return b == BlockType.TARGET || b == BlockType.TREASURE || b == BlockType.FUEL || b == BlockType.FILLED
            || b == BlockType.REC_LU || b == BlockType.REC_RU || b == BlockType.REC_LD || b == BlockType.REC_RD
            || b == BlockType.CANNON;
    }
}
