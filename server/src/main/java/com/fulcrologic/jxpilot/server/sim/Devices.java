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
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Ecms;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Ship devices: ports of {@code Phasing}, {@code Cloak}, {@code Deflector}, {@code Emergency_thrust},
 * {@code Emergency_shield}, {@code Autopilot} and {@code do_Autopilot} ({@code src/server/update.c}),
 * {@code Tractor_beam}, {@code General_tractor_beam}, {@code Do_deflector}, {@code Do_transporter},
 * {@code Do_general_transporter} and {@code do_hyperjump} ({@code src/server/item.c}), and the device parts of
 * the player loop of {@code Update_objects} (update.c:825-870, 932-936) and its second loop (update.c:1254-1274).
 */
public final class Devices {
    /** C {@code ED_PHASING_DEVICE}, {@code ED_CLOAKING_DEVICE}, {@code ED_DEFLECTOR} after the (long) cast. */
    static final long ED_PHASING_DEVICE = (long) (-0.40 * 256);
    static final long ED_CLOAKING_DEVICE = (long) (-0.07 * 256);
    static final long ED_DEFLECTOR = (long) (-0.15 * 256);
    /** C {@code ED_TRANSPORTER}, {@code ED_HYPERJUMP} (-60 fuel units). */
    static final long ED_TRANSPORTER = -60 * 256;
    static final long ED_HYPERJUMP = -60 * 256;
    /** C {@code TRANSPORTER_DISTANCE}. */
    static final float TRANSPORTER_DISTANCE = (float) (Const.VISIBILITY_DISTANCE * 0.2);
    /** C {@code MAX_AFTERBURNER}. */
    static final int MAX_AFTERBURNER = 15;

    private final World w;
    private final Simulation sim;

    public Devices(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    // ================================================================ on/off switches

    /**
     * C {@code Phasing}.
     * <p>
     * BEHAVIOR: on: a new 4*FPS phase (one item used) when the last one ran out; phasing on, refuel, repair and
     * tractor beam off, and no gravity. Off: phasing off; the ability goes when the phase and the items are
     * used up; gravity again. Either way PHASING_ON/OFF plays at the player.
     */
    public void phasing(Player pl, boolean on) {
        int time = 4 * w.params.fps;
        if (on) {
            if (pl.phasingLeft <= 0) {
                pl.phasingLeft = time;
                pl.phasingMax = time;
                pl.items[Item.PHASING.ordinal()]--;
            }
            pl.used |= Has.PHASING_DEVICE;
            pl.used &= ~(Has.REFUEL | Has.REPAIR | Has.TRACTOR_BEAM);
            pl.status &= ~Obj.GRAVITY;
            Sounds.play(w, pl.px, pl.py, SoundEvent.PHASING_ON);
        } else {
            pl.used &= ~Has.PHASING_DEVICE;
            if (pl.phasingLeft <= 0 && pl.items[Item.PHASING.ordinal()] <= 0) {
                pl.have &= ~Has.PHASING_DEVICE;
            }
            pl.status |= Obj.GRAVITY;
            Sounds.play(w, pl.px, pl.py, SoundEvent.PHASING_OFF);
        }
    }

    /**
     * C {@code Cloak}.
     * <p>
     * BEHAVIOR: on (when not cloaked and a cloak item is left): without cloakedShield emergency shield and
     * deflector go off and shields are lost; the player hears CLOAK, visibility is updated and the cloak goes
     * on.
     * BEHAVIOR: off: a running cloak goes off (CLOAK to the player, visibility updated); without items the
     * ability goes. Without
     * cloakedShield an emergency shield comes back on, default shields come back, and shields go up again if
     * the shield key is held.
     */
    public void cloak(Player pl, boolean on) {
        boolean cloakedShield = w.items.cloakedShield;
        if (on) {
            if ((pl.used & Has.CLOAKING_DEVICE) == 0 && pl.items[Item.CLOAK.ordinal()] > 0) {
                if (!cloakedShield) {
                    if ((pl.used & Has.EMERGENCY_SHIELD) != 0) {
                        emergencyShield(pl, false);
                    }
                    if ((pl.used & Has.DEFLECTOR) != 0) {
                        deflector(pl, false);
                    }
                    pl.used &= ~Has.SHIELD;
                    pl.have &= ~Has.SHIELD;
                }
                Sounds.playPlayer(pl, SoundEvent.CLOAK);
                pl.updateVisibility = true;
                pl.used |= Has.CLOAKING_DEVICE;
            }
        } else {
            if ((pl.used & Has.CLOAKING_DEVICE) != 0) {
                Sounds.playPlayer(pl, SoundEvent.CLOAK);
                pl.updateVisibility = true;
                pl.used &= ~Has.CLOAKING_DEVICE;
            }
            if (pl.items[Item.CLOAK.ordinal()] == 0) {
                pl.have &= ~Has.CLOAKING_DEVICE;
            }
            if (!cloakedShield) {
                if ((pl.have & Has.EMERGENCY_SHIELD) != 0) {
                    pl.have |= Has.SHIELD;
                    emergencyShield(pl, true);
                }
                if ((w.params.defHave & Has.SHIELD) != 0 && (pl.have & Has.SHIELD) == 0) {
                    pl.have |= Has.SHIELD;
                }
                if (!pl.keysCleared && ControlKey.SHIELD.isDown(pl.lastReceivedKeys)) {
                    pl.used |= Has.SHIELD;
                }
            }
        }
    }

    /**
     * C {@code Deflector}.
     * <p>
     * BEHAVIOR: on (when off and a deflector item is left) only when cloakedShield or not cloaked. Off: it goes
     * off; without items the ability goes. Turning it on or off plays DEFLECTOR to the player.
     */
    public void deflector(Player pl, boolean on) {
        if (on) {
            if ((pl.used & Has.DEFLECTOR) == 0 && pl.items[Item.DEFLECTOR.ordinal()] > 0
                && (w.items.cloakedShield || (pl.used & Has.CLOAKING_DEVICE) == 0)) {
                pl.used |= Has.DEFLECTOR;
                Sounds.playPlayer(pl, SoundEvent.DEFLECTOR);
            }
        } else {
            if ((pl.used & Has.DEFLECTOR) != 0) {
                pl.used &= ~Has.DEFLECTOR;
                Sounds.playPlayer(pl, SoundEvent.DEFLECTOR);
            }
            if (pl.items[Item.DEFLECTOR.ordinal()] == 0) {
                pl.have &= ~Has.DEFLECTOR;
            }
        }
    }

    /**
     * C {@code Emergency_thrust}.
     * <p>
     * BEHAVIOR: on: a new 4*FPS run (one item used) when the last ran out, and it goes on (a THRUST event,
     * since the effective afterburners change). Off: it goes off; the ability goes when run and items are used
     * up. Switching it plays EMERGENCY_THRUST_ON/OFF at the player.
     */
    public void emergencyThrust(Player pl, boolean on) {
        int time = 4 * w.params.fps;
        int before = pl.afterburners();
        if (on) {
            if (pl.emergencyThrustLeft <= 0) {
                pl.emergencyThrustLeft = time;
                pl.emergencyThrustMax = time;
                pl.items[Item.EMERGENCY_THRUST.ordinal()]--;
            }
            if ((pl.used & Has.EMERGENCY_THRUST) == 0) {
                pl.used |= Has.EMERGENCY_THRUST;
                Sounds.play(w, pl.px, pl.py, SoundEvent.EMERGENCY_THRUST_ON);
            }
        } else {
            if ((pl.used & Has.EMERGENCY_THRUST) != 0) {
                pl.used &= ~Has.EMERGENCY_THRUST;
                Sounds.play(w, pl.px, pl.py, SoundEvent.EMERGENCY_THRUST_OFF);
            }
            if (pl.emergencyThrustLeft <= 0 && pl.items[Item.EMERGENCY_THRUST.ordinal()] <= 0) {
                pl.have &= ~Has.EMERGENCY_THRUST;
            }
        }
        if (pl.afterburners() != before) {
            w.events.add(com.fulcrologic.jxpilot.server.world.Events.THRUST, pl.id, pl.afterburners(), 0, pl.power);
        }
    }

    /**
     * C {@code Emergency_shield}.
     * <p>
     * BEHAVIOR: on (with the ability): a new 4*FPS run (one item used) when the last ran out; unless cloaked
     * without cloakedShield, shields become available and the emergency shield goes on.
     * BEHAVIOR: off: the ability goes when run and items are used up; without default shields shields are lost;
     * the emergency shield goes off. Switching it plays EMERGENCY_SHIELD_ON/OFF at the player.
     */
    public void emergencyShield(Player pl, boolean on) {
        int time = 4 * w.params.fps;
        if (on) {
            if ((pl.have & Has.EMERGENCY_SHIELD) != 0) {
                if (pl.emergencyShieldLeft <= 0) {
                    pl.emergencyShieldLeft = time;
                    pl.emergencyShieldMax = time;
                    pl.items[Item.EMERGENCY_SHIELD.ordinal()]--;
                }
                if (w.items.cloakedShield || (pl.used & Has.CLOAKING_DEVICE) == 0) {
                    pl.have |= Has.SHIELD;
                    if ((pl.used & Has.EMERGENCY_SHIELD) == 0) {
                        pl.used |= Has.EMERGENCY_SHIELD;
                        Sounds.play(w, pl.px, pl.py, SoundEvent.EMERGENCY_SHIELD_ON);
                    }
                }
            }
        } else {
            if (pl.emergencyShieldLeft <= 0 && pl.items[Item.EMERGENCY_SHIELD.ordinal()] <= 0) {
                pl.have &= ~Has.EMERGENCY_SHIELD;
            }
            if ((w.params.defHave & Has.SHIELD) == 0) {
                pl.have &= ~Has.SHIELD;
                pl.used &= ~Has.SHIELD;
            }
            if ((pl.used & Has.EMERGENCY_SHIELD) != 0) {
                pl.used &= ~Has.EMERGENCY_SHIELD;
                Sounds.play(w, pl.px, pl.py, SoundEvent.EMERGENCY_SHIELD_OFF);
            }
        }
    }

    /**
     * C {@code Autopilot}: always stops thrusting.
     * <p>
     * BEHAVIOR: on: power, turnspeed and turnresistance are saved and set to the middle of their ranges and
     * 0.2. Off: they are restored, turnacc becomes 0. Either way AUTOPILOT_ON/OFF plays at the player.
     */
    public void autopilot(Player pl, boolean on) {
        pl.status &= ~Obj.THRUSTING;
        float oldPower = pl.power;
        if (on) {
            pl.autoPowerS = pl.power;
            pl.autoTurnSpeedS = pl.turnSpeed;
            pl.autoTurnResistanceS = pl.turnResistance;
            pl.used |= Has.AUTOPILOT;
            pl.power = (float) ((Const.MIN_PLAYER_POWER + Const.MAX_PLAYER_POWER) / 2.0);
            pl.turnSpeed = (float) ((Const.MIN_PLAYER_TURNSPEED + Const.MAX_PLAYER_TURNSPEED) / 2.0);
            pl.turnResistance = 0.2f;
            Sounds.play(w, pl.px, pl.py, SoundEvent.AUTOPILOT_ON);
        } else {
            pl.power = pl.autoPowerS;
            pl.turnAcc = 0;
            pl.turnSpeed = pl.autoTurnSpeedS;
            pl.turnResistance = pl.autoTurnResistanceS;
            pl.used &= ~Has.AUTOPILOT;
            Sounds.play(w, pl.px, pl.py, SoundEvent.AUTOPILOT_OFF);
        }
        if (pl.power != oldPower) {
            w.events.add(com.fulcrologic.jxpilot.server.world.Events.THRUST, pl.id, pl.afterburners(), 0, pl.power);
        }
    }

    /**
     * C {@code do_Autopilot}: steer and thrust to come to rest against gravity (C floats).
     * <p>
     * BEHAVIOR: does nothing within a frame of a wall touch.
     * BEHAVIOR: below 0.5 px/frame the ship stops dead and returns to its previous position.
     * BEHAVIOR: power and turnspeed move by delta (15/FPS times the autopilot items, at least 150/FPS with
     * emergency thrust) toward what is needed to stop now, limited to their maxima; it turns away from its
     * motion (or against gravity), and thrusts only when pointing right and not overpowered.
     */
    public void doAutopilot(Player pl) {
        if (pl.lastWallTouch + 1 >= w.frames) {
            return;
        }
        int fps = w.params.fps;
        final float emergencyDelta = 150.0f / fps;
        float delta = 15.0f / fps;
        if (pl.items[Item.AUTOPILOT.ordinal()] != 0) {
            delta *= pl.items[Item.AUTOPILOT.ordinal()];
        }
        int afterburners;
        if ((pl.used & Has.EMERGENCY_THRUST) != 0) {
            afterburners = MAX_AFTERBURNER;
            if (delta < emergencyDelta) {
                delta = emergencyDelta;
            }
        } else {
            afterburners = pl.items[Item.AFTERBURNER.ordinal()];
        }
        float gx = w.gravityX(pl.bx, pl.by);
        float gy = w.gravityY(pl.bx, pl.by);
        float vel = (float) StrictMath.hypot(pl.vx, pl.vy);
        if (vel < 0.5f) {
            pl.vx = pl.vy = 0;
            vel = 0;
            pl.setClicks(com.fulcrologic.jxpilot.server.world.Click.fromPixels(pl.prevX),
                com.fulcrologic.jxpilot.server.world.Click.fromPixels(pl.prevY));
        }
        float acc = (float) StrictMath.hypot(gx, gy) + vel;
        float power = acc * pl.mass;
        if (afterburners != 0) {
            power = (float) (power / (1.0 + afterburners * ((4.2 - 1.0) / (MAX_AFTERBURNER + 1.0))));
        }
        int vad;
        if (vel == 0.0f) {
            vad = (gx == 0 && gy == 0) ? pl.dir : (int) XMath.findDir(-gx, -gy);
        } else {
            vad = (int) XMath.findDir(-pl.vx, -pl.vy);
        }
        vad = Trig.mod(vad - pl.dir);
        int dir;
        if (vad > Const.RES / 2) {
            vad = Const.RES - vad;
            dir = -1;
        } else {
            dir = 1;
        }
        float turnspeed = vad / pl.turnResistance - pl.turnVel;
        if (turnspeed < 0) {
            turnspeed = -turnspeed;
            dir = -dir;
        }
        if (turnspeed < pl.turnSpeed) {
            pl.turnSpeed -= delta;
            if (turnspeed > pl.turnSpeed) {
                pl.turnSpeed = turnspeed;
            }
        } else if (turnspeed > pl.turnSpeed) {
            pl.turnSpeed += delta;
            if (turnspeed < pl.turnSpeed) {
                pl.turnSpeed = turnspeed;
            }
        }
        if (pl.turnSpeed > Const.MAX_PLAYER_TURNSPEED) {
            pl.turnSpeed = (float) Const.MAX_PLAYER_TURNSPEED;
        }
        if (pl.turnSpeed > turnspeed * 2.5f) {
            pl.turnAcc = 0;
            pl.turnVel = 0;
        } else {
            pl.turnAcc = dir * pl.turnSpeed;
        }
        float oldPower = pl.power;
        if (power < pl.power) {
            pl.power -= delta;
            if (power > pl.power) {
                pl.power = power;
            }
        } else if (power > pl.power) {
            pl.power += delta;
            if (power < pl.power) {
                pl.power = power;
            }
        }
        if (pl.power > Const.MAX_PLAYER_POWER) {
            pl.power = (float) Const.MAX_PLAYER_POWER;
        }
        if (pl.power != oldPower) {
            w.events.add(com.fulcrologic.jxpilot.server.world.Events.THRUST, pl.id, pl.afterburners(), 0, pl.power);
        }
        if (pl.turnSpeed != turnspeed && vad > Const.RES / 32) {
            pl.status &= ~Obj.THRUSTING;
            return;
        }
        if (pl.power > power) {
            pl.status &= ~Obj.THRUSTING;
        } else {
            pl.status |= Obj.THRUSTING;
        }
    }

    // ================================================================ per-frame player updates

    /**
     * The device timers of the player loop (update.c:825-857).
     * <p>
     * BEHAVIOR: a phasing ship's phase runs down; at its end the next item starts a new phase, or phasing ends.
     * BEHAVIOR: emergency thrust runs down only while thrusting with fuel, emergency shield only while shielded
     * with fuel; at the end the next item restarts it or it goes off.
     */
    public void timers(Player pl) {
        if ((pl.used & Has.PHASING_DEVICE) != 0 && --pl.phasingLeft <= 0) {
            phasing(pl, pl.items[Item.PHASING.ordinal()] != 0);
        }
        if ((pl.used & Has.EMERGENCY_THRUST) != 0 && pl.fuel > 0 && (pl.status & Obj.THRUSTING) != 0
            && --pl.emergencyThrustLeft <= 0) {
            emergencyThrust(pl, pl.items[Item.EMERGENCY_THRUST.ordinal()] != 0);
        }
        if ((pl.used & Has.EMERGENCY_SHIELD) != 0 && pl.fuel > 0 && (pl.used & Has.SHIELD) != 0
            && --pl.emergencyShieldLeft <= 0) {
            emergencyShield(pl, pl.items[Item.EMERGENCY_SHIELD.ordinal()] != 0);
        }
    }

    /**
     * Lasers, deflector and autopilot of the player loop (update.c:859-879).
     * <p>
     * BEHAVIOR: a laser in use fires ({@link Lasers#fire}) unless the lasers are gone or the ship is phased
     * (then it goes off); a deflector in use works ({@link #doDeflector}); the autopilot flies when on, or
     * when hover-paused and not blinded.
     */
    public void use(Player pl) {
        if ((pl.used & Has.LASER) != 0) {
            if (pl.items[Item.LASER.ordinal()] <= 0 || (pl.used & Has.PHASING_DEVICE) != 0) {
                pl.used &= ~Has.LASER;
            } else {
                sim.lasers.fire(pl);
            }
        }
        if ((pl.used & Has.DEFLECTOR) != 0) {
            doDeflector(pl);
        }
        if ((pl.used & Has.AUTOPILOT) != 0 || ((pl.status & Obj.HOVERPAUSE) != 0 && pl.damaged == 0)) {
            doAutopilot(pl);
        }
    }

    /** BEHAVIOR: phasing and cloak cost fuel every frame (update.c:932-936). */
    public void drain(Player pl) {
        if ((pl.used & Has.PHASING_DEVICE) != 0) {
            pl.addFuel(ED_PHASING_DEVICE);
        }
        if ((pl.used & Has.CLOAKING_DEVICE) != 0) {
            pl.addFuel(ED_CLOAKING_DEVICE);
        }
    }

    // ================================================================ deflector

    /**
     * C {@code Do_deflector}: push approaching objects away.
     * <p>
     * BEHAVIOR: without 0.15 fuel the deflector goes off; otherwise it costs that fuel.
     * BEHAVIOR: objects within range = (deflectors * 0.5 + 1) blocks of the hull (not own immune objects,
     * team-immune ones or balls in their treasure) that move toward the ship are pushed out along the line
     * from the ship with force ((range-dist)/range)^2 * deflectors * 0.2 * (how head-on they come).
     */
    public void doDeflector(Player pl) {
        int n = pl.items[Item.DEFLECTOR.ordinal()];
        float range = (float) ((n * 0.5 + 1) * Const.BLOCK_SZ);
        float maxforce = (float) (n * 0.2);
        if (pl.fuel < -ED_DEFLECTOR) {
            if ((pl.used & Has.DEFLECTOR) != 0) {
                deflector(pl, false);
            }
            return;
        }
        pl.addFuel(ED_DEFLECTOR);
        Objects o = w.objects;
        w.grid.rebuild(o);
        w.grid.query(pl.bx, pl.by, (int) (range / Const.BLOCK_SZ + 1), 200);
        int cnt = w.grid.resultCount;
        for (int i = 0; i < cnt; i++) {
            int h = w.grid.result[i];
            if (o.life[h] <= 0) {
                continue;
            }
            if (o.owner[h] == pl.id) {
                if ((o.status[h] & Obj.OWNERIMMUNE) != 0 || o.fuseLife[h] < o.life[h] || w.params.selfImmunity) {
                    continue;
                }
            } else if (w.teamImmune(o.owner[h], pl.id)) {
                continue;
            }
            if ((o.type[h] & Obj.BALL) != 0 && (o.status[h] & Obj.GRAVITY) == 0) {
                continue;
            }
            long dx = o.px[h] - pl.px;
            long dy = o.py[h] - pl.py;
            dx = (long) w.wrapDx(dx);
            dy = (long) w.wrapDy(dy);
            long dist = (long) (StrictMath.hypot(dx, dy) - Const.SHIP_SZ);
            if (dist < range && dist > 0) {
                int dir = (int) XMath.findDir(dx, dy);
                int idir = Trig.mod((int) (dir - (float) XMath.findDir(o.vx[h], o.vy[h])));
                if (idir > Const.RES * 0.25 && idir < Const.RES * 0.75) {
                    float force = (float) (((float) (range - dist) / range) * ((float) (range - dist) / range)
                        * maxforce * ((Const.RES * 0.25) - Math.abs(idir - Const.RES * 0.5)) / (Const.RES * 0.25));
                    float dv = force / Math.abs(o.mass[h]);
                    o.vx[h] += Trig.tcos(dir) * dv;
                    o.vy[h] += Trig.tsin(dir) * dv;
                }
            }
        }
    }

    // ================================================================ tractor beam

    /** C {@code TRACTOR_MAX_RANGE}. */
    static float tractorMaxRange(int items) {
        return 200 + items * 50;
    }

    /** C {@code TRACTOR_MAX_FORCE}. */
    static float tractorMaxForce(int items) {
        return -40 + items * -20;
    }

    /** C {@code TRACTOR_PERCENT}. */
    static float tractorPercent(float dist, float maxdist) {
        return (float) (1.0 - (0.5 * dist / maxdist));
    }

    /** C {@code TRACTOR_COST}, truncated as C assigns it to a long. */
    static long tractorCost(float percent) {
        return (long) (-1.5 * 256 * percent);
    }

    /**
     * C {@code Tractor_beam} (second player loop, update.c:1266).
     * <p>
     * BEHAVIOR: the beam goes off unless the lock is on a visible, playing, unphased player within range and the
     * ship is not phased, and when the fuel does not cover the cost; otherwise
     * {@link #generalTractorBeam} pulls (or pushes) between them.
     */
    public void tractorBeam(Player pl) {
        float maxdist = tractorMaxRange(pl.items[Item.TRACTOR_BEAM.ordinal()]);
        Player t = w.byId(pl.lockPlId);
        if ((pl.lockTagged & (Player.LOCK_PLAYER | Player.LOCK_VISIBLE)) != (Player.LOCK_PLAYER | Player.LOCK_VISIBLE)
            || t == null || !t.isAlive() || pl.lockDistance >= maxdist
            || (pl.used & Has.PHASING_DEVICE) != 0 || (t.used & Has.PHASING_DEVICE) != 0) {
            pl.used &= ~Has.TRACTOR_BEAM;
            return;
        }
        float percent = tractorPercent(pl.lockDistance, maxdist);
        long cost = tractorCost(percent);
        if (pl.fuel < -cost) {
            pl.used &= ~Has.TRACTOR_BEAM;
            return;
        }
        generalTractorBeam(pl, pl.px, pl.py, pl.items[Item.TRACTOR_BEAM.ordinal()], t, pl.tractorIsPressor);
    }

    /**
     * C {@code General_tractor_beam} from a player ({@code pl}) or a cannon ({@code pl == null}) at (x, y).
     * <p>
     * BEHAVIOR: nothing beyond the range. PRESSOR_BEAM or TRACTOR_BEAM plays at (x, y); the player pays the
     * cost; force = percent * maxforce (negated for a
     * pressor) along the direction from the victim to the beam source: the source is pushed by +force/mass, the
     * victim by -force/mass; both record the other as a shover.
     */
    public void generalTractorBeam(Player pl, float x, float y, int items, Player victim, boolean pressor) {
        float maxdist = tractorMaxRange(items);
        float maxforce = tractorMaxForce(items);
        float dist = w.wrapLength(x - victim.px, y - victim.py);
        if (dist > maxdist) {
            return;
        }
        float percent = tractorPercent(dist, maxdist);
        long cost = tractorCost(percent);
        float force = percent * maxforce * (pressor ? -1 : 1);
        Sounds.play(w, x, y, pressor ? SoundEvent.PRESSOR_BEAM : SoundEvent.TRACTOR_BEAM);
        if (pl != null) {
            pl.addFuel(cost);
        }
        int theta = (int) XMath.findDir(w.wrapDx(x - victim.px), w.wrapDy(y - victim.py));
        if (pl != null) {
            pl.vx += Trig.tcos(theta) * (force / pl.mass);
            pl.vy += Trig.tsin(theta) * (force / pl.mass);
            sim.ships.recordShove(pl, victim);
            sim.ships.recordShove(victim, pl);
        }
        victim.vx -= Trig.tcos(theta) * (force / victim.mass);
        victim.vy -= Trig.tsin(theta) * (force / victim.mass);
    }

    /**
     * The second player loop of {@code Update_objects} (update.c:1254-1274).
     * <p>
     * BEHAVIOR: the visibility update request is cleared; forceVisible counts down and asks for an update when
     * it reaches 0; a tractor beam in use works; the lock distance is refreshed.
     */
    public void afterMove() {
        for (int k = 0; k < w.numPlayers; k++) {
            Player pl = w.playerAt(k);
            pl.updateVisibility = false;
            if (pl.forceVisible != 0) {
                pl.forceVisible--;
                if (pl.forceVisible == 0) {
                    pl.updateVisibility = true;
                }
            }
            if ((pl.used & Has.TRACTOR_BEAM) != 0) {
                tractorBeam(pl);
            }
            if ((pl.lockTagged & Player.LOCK_PLAYER) != 0) {
                Player t = w.byId(pl.lockPlId);
                if (t != null) {
                    pl.lockDistance = w.wrapLength(pl.px - t.px, pl.py - t.py);
                }
            }
        }
    }

    // ================================================================ transporter, hyperjump

    /**
     * C {@code Do_transporter}.
     * <p>
     * BEHAVIOR: nothing without a transporter, with too little fuel or while phased.
     * BEHAVIOR: the closest playing, unphased, non-team-immune player that is not a tank within
     * TRANSPORTER_DISTANCE is robbed ({@link #generalTransporter}); without one TRANSPORTER_FAIL plays and the
     * item and its fuel are spent for nothing.
     */
    public void transporter(Player pl) {
        if (pl.items[Item.TRANSPORTER.ordinal()] == 0 || pl.fuel < -ED_TRANSPORTER
            || (pl.used & Has.PHASING_DEVICE) != 0) {
            return;
        }
        Player target = null;
        float closest = TRANSPORTER_DISTANCE;
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (p == pl || (p.status & (Obj.PLAYING | Obj.PAUSE | Obj.GAME_OVER)) != Obj.PLAYING
                || w.teamImmune(pl.id, p.id) || p.tank || (p.used & Has.PHASING_DEVICE) != 0) {
                continue;
            }
            float dist = w.wrapLength(pl.px - p.px, pl.py - p.py);
            if (dist < closest) {
                closest = dist;
                target = p;
            }
        }
        if (target == null) {
            Sounds.play(w, pl.px, pl.py, SoundEvent.TRANSPORTER_FAIL);
            pl.addFuel(ED_TRANSPORTER);
            pl.items[Item.TRANSPORTER.ordinal()]--;
            return;
        }
        generalTransporter(pl, pl.px, pl.py, target);
    }

    /** Item and amount a cannon's transporter got ({@link #generalTransporter} with no player). */
    public int stolenItem;
    public long stolenAmount;

    /**
     * C {@code Do_general_transporter} by a player ({@code pl}) or a cannon ({@code pl == null}) at (x, y).
     * <p>
     * BEHAVIOR: up to 50 random draws pick an item kind the victim has (tanks count as the tank count, fuel
     * as the fuel sum); after 50 misses only TRANSPORTER_FAIL plays at (x, y) (a cannon gets item -1),
     * otherwise TRANSPORTER_SUCCESS does.
     * BEHAVIOR: a transporter beam is shown for 5 frames. The victim loses one of the item (missiles: up to
     * 3; a tank: a random extra tank with its fuel; fuel: 10-50% of its fuel) and the abilities that go with
     * it; the message says who stole what from whom.
     * BEHAVIOR: a cannon gets the item and amount ({@link #stolenItem}, {@link #stolenAmount}; fuel in units);
     * a player pays a transporter and its fuel, and gains the loot (and abilities) within its item limit.
     */
    public void generalTransporter(Player pl, float x, float y, Player victim) {
        int item = Item.FUEL.ordinal();
        int i;
        for (i = 0; i < 50; i++) {
            item = (int) (w.rfrac() * Item.COUNT);
            if (victim.items[item] != 0 || (item == Item.TANK.ordinal() && victim.numTanks != 0)
                || (item == Item.FUEL.ordinal() && victim.fuel != 0)) {
                break;
            }
        }
        if (i == 50) {
            Sounds.play(w, x, y, SoundEvent.TRANSPORTER_FAIL);
            if (pl == null) {
                stolenAmount = 0;
                stolenItem = -1;
            }
            return;
        }
        Sounds.play(w, x, y, SoundEvent.TRANSPORTER_SUCCESS);
        Ecms e = w.ecms;
        int t = e.addTransporter();
        if (t >= 0) {
            e.transX[t] = x;
            e.transY[t] = y;
            e.transTarget[t] = victim.id;
            e.transOwner[t] = pl != null ? pl.id : Obj.NO_ID;
            e.transCount[t] = 5;
        }
        long amount = 1;
        Item kind = Item.of(item);
        if (kind != Item.MISSILE && kind != Item.FUEL && kind != Item.TANK) {
            victim.items[item]--;
        }
        String what = null;
        boolean fullMsg = false;
        CharSequence thief = pl != null ? pl.name : "A cannon";
        StringBuilder msg = null;
        switch (kind) {
            case AFTERBURNER -> {
                what = "an afterburner";
                if (victim.items[item] == 0) {
                    victim.have &= ~Has.AFTERBURNER;
                }
            }
            case MISSILE -> {
                amount = Math.min(victim.items[item], 3);
                msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID);
                if (amount == 1) {
                    msg.append(thief).append(" stole a missile from ").append(victim.name).append('.');
                } else {
                    msg.append(thief).append(" stole ").append(amount).append(" missiles from ").append(victim.name);
                }
                fullMsg = true;
            }
            case CLOAK -> {
                what = "a cloaking device";
                victim.updateVisibility = true;
                if (victim.items[item] == 0) {
                    cloak(victim, false);
                }
            }
            case WIDEANGLE -> what = "a wideangle";
            case REARSHOT -> what = "a rearshot";
            case MINE -> what = "a mine";
            case SENSOR -> {
                what = "a sensor";
                victim.updateVisibility = true;
            }
            case ECM -> what = "an ECM";
            case ARMOR -> {
                what = "an armor";
                if (victim.items[item] == 0) {
                    victim.have &= ~Has.ARMOR;
                }
            }
            case TRANSPORTER -> what = "a transporter";
            case MIRROR -> {
                what = "a mirror";
                if (victim.items[item] == 0) {
                    victim.have &= ~Has.MIRROR;
                }
            }
            case DEFLECTOR -> {
                what = "a deflector";
                if (victim.items[item] == 0) {
                    deflector(victim, false);
                }
            }
            case HYPERJUMP -> what = "a hyperjump";
            case PHASING -> {
                what = "a phasing device";
                if (victim.items[item] == 0) {
                    if ((victim.used & Has.PHASING_DEVICE) != 0) {
                        phasing(victim, false);
                    }
                    victim.have &= ~Has.PHASING_DEVICE;
                }
            }
            case LASER -> what = "a laser";
            case EMERGENCY_THRUST -> {
                what = "an emergency thrust";
                if (victim.items[item] == 0) {
                    if ((victim.used & Has.EMERGENCY_THRUST) != 0) {
                        emergencyThrust(victim, false);
                    }
                    victim.have &= ~Has.EMERGENCY_THRUST;
                }
            }
            case EMERGENCY_SHIELD -> {
                what = "an emergency shield";
                if (victim.items[item] == 0) {
                    if ((victim.used & Has.EMERGENCY_SHIELD) != 0) {
                        emergencyShield(victim, false);
                    }
                    victim.have &= ~Has.EMERGENCY_SHIELD;
                    if ((w.params.defHave & Has.SHIELD) == 0) {
                        victim.have &= ~Has.SHIELD;
                        victim.used &= ~Has.SHIELD;
                    }
                }
            }
            case TRACTOR_BEAM -> {
                what = "a tractor beam";
                if (victim.items[item] == 0) {
                    victim.have &= ~Has.TRACTOR_BEAM;
                }
            }
            case AUTOPILOT -> {
                what = "an autopilot";
                if (victim.items[item] == 0) {
                    if ((victim.used & Has.AUTOPILOT) != 0) {
                        autopilot(victim, false);
                    }
                    victim.have &= ~Has.AUTOPILOT;
                }
            }
            case TANK -> {
                what = "a tank";
                int ti = (int) (w.rfrac() * victim.numTanks) + 1;
                amount = (long) victim.tankFuel[ti];
                sim.tanks.remove(victim, ti);
            }
            case FUEL -> {
                float pr = (float) w.rfrac();
                float percent = 10.0f + 40.0f * pr;
                amount = (long) (victim.fuel * percent / 100);
                msg = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID);
                msg.append(thief).append(" stole ").append(amount >> 8).append(" units (")
                    .append((int) (percent + 0.5)).append("%) of fuel from ").append(victim.name).append('.');
                fullMsg = true;
                victim.addFuel(-amount);
            }
            default -> { }
        }
        if (!fullMsg) {
            w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(thief).append(" stole ").append(what)
                .append(" from ").append(victim.name).append('.');
        }
        if (pl == null) {
            stolenItem = item;
            stolenAmount = (kind == Item.FUEL || kind == Item.TANK) ? amount >> 8 : amount;
            return;
        }
        pl.items[Item.TRANSPORTER.ordinal()]--;
        pl.addFuel(ED_TRANSPORTER);
        if (kind != Item.FUEL && kind != Item.TANK) {
            pl.items[item] += (int) amount;
        }
        switch (kind) {
            case AFTERBURNER -> {
                pl.have |= Has.AFTERBURNER;
                pl.items[item] = Math.max(0, Math.min(pl.items[item], MAX_AFTERBURNER));
            }
            case CLOAK -> {
                pl.have |= Has.CLOAKING_DEVICE;
                pl.updateVisibility = true;
            }
            case SENSOR -> pl.updateVisibility = true;
            case MIRROR -> pl.have |= Has.MIRROR;
            case ARMOR -> pl.have |= Has.ARMOR;
            case DEFLECTOR -> pl.have |= Has.DEFLECTOR;
            case PHASING -> pl.have |= Has.PHASING_DEVICE;
            case EMERGENCY_THRUST -> pl.have |= Has.EMERGENCY_THRUST;
            case EMERGENCY_SHIELD -> pl.have |= Has.EMERGENCY_SHIELD;
            case TRACTOR_BEAM -> pl.have |= Has.TRACTOR_BEAM;
            case AUTOPILOT -> pl.have |= Has.AUTOPILOT;
            case TANK -> {
                if (pl.numTanks < Player.MAX_TANKS) {
                    sim.tanks.add(pl, amount);
                }
            }
            case FUEL -> pl.addFuel(amount);
            default -> { }
        }
        int lim = w.items.limit[item];
        pl.items[item] = Math.max(0, Math.min(pl.items[item], lim));
    }

    /** C KEY_HYPERJUMP: with a hyperjump and enough fuel, one is used and {@code do_hyperjump} starts warping. */
    public void hyperjumpKey(Player pl) {
        if (pl.items[Item.HYPERJUMP.ordinal()] > 0 && pl.fuel > -ED_HYPERJUMP) {
            pl.items[Item.HYPERJUMP.ordinal()]--;
            pl.addFuel(ED_HYPERJUMP);
            hyperjump(pl);
        }
    }

    /** C {@code do_hyperjump}: WARPING with no wormhole (a random destination). */
    public void hyperjump(Player pl) {
        pl.status |= Obj.WARPING;
        pl.wormHoleHit = -1;
    }

    /** Destination of the last {@link #hyperjumpDestination} (pixels). */
    public float jumpX;
    public float jumpY;

    /**
     * The hyperjump destination of the warp code (update.c:1137-1167, temporary wormholes not made).
     * <p>
     * BEHAVIOR: up to 20 random positions until one is on a space block; after 20 failures the ship stays
     * where it is.
     */
    public void hyperjumpDestination(Player pl) {
        int counter;
        float x = 0;
        float y = 0;
        for (counter = 20; counter > 0; counter--) {
            x = (int) (w.rfrac() * w.pixelWidth);
            y = (int) (w.rfrac() * w.pixelHeight);
            int bx = (int) (x / Const.BLOCK_SZ);
            int by = (int) (y / Const.BLOCK_SZ);
            if ((com.fulcrologic.jxpilot.common.map.BlockType.bit(w.map.block(bx, by)) & Items.SPACE_BLOCKS) != 0) {
                break;
            }
        }
        if (counter == 0) {
            x = pl.px;
            y = pl.py;
        }
        jumpX = x;
        jumpY = y;
    }

    /**
     * Initialize this class's enum switch table now (javac's synthetic SwitchMap class), so the first item switch
     * in a tick does not allocate.
     */
    static int warmSwitches() {
        return switch (Item.FUEL) {
            case FUEL -> 0;
            default -> 1;
        };
    }
}
