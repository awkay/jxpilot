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
package com.fulcrologic.jxpilot.server.robot;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Pulses;

/**
 * The gate between the robot AI and the item parts of the game: every item count, ability and item action the C
 * robot code uses goes through here, so tests can substitute a spy. This class answers from the simulation and
 * calls its real actions, balls, treasures, targets and asteroids included.
 */
public class RobotItems {
    /** The simulation (null only for test spies that override everything they use). */
    protected final Simulation sim;

    public RobotItems(Simulation sim) {
        this.sim = sim;
    }

    /** BEHAVIOR: C {@code pl->item[i]}. */
    public int count(Player pl, Item item) {
        return pl.items[item.ordinal()];
    }

    /** BEHAVIOR: C {@code pl->have}. */
    public int have(Player pl) {
        return pl.have;
    }

    /** BEHAVIOR: C {@code pl->visibility[j].canSee}. */
    public boolean canSee(Player viewer, Player target) {
        return viewer.canSee[target.ind];
    }

    /** BEHAVIOR: C {@code pl->num_pulses}: own laser pulses in flight. */
    public int numPulses(Player pl) {
        return pl.numPulses;
    }

    /** BEHAVIOR: C {@code pl->fuel.num_tanks}. */
    public int numTanks(Player pl) {
        return pl.numTanks;
    }

    /** BEHAVIOR: C {@code World.items[i].limit}. */
    public int limit(Item item) {
        return sim.world.items.limit[item.ordinal()];
    }

    /** BEHAVIOR: one each for a running emergency shield and phasing device (C CountDefensiveItems). */
    public int activeDefensives(Player pl) {
        return (pl.emergencyShieldLeft != 0 ? 1 : 0) + (pl.phasingLeft != 0 ? 1 : 0);
    }

    /** C {@code World.targets[i].damage} (fixed point). */
    public double targetDamage(int target) {
        return sim.world.modes.targetDamage[target];
    }

    /** C {@code World.treasures[i].have}: the treasure has its ball at home. */
    public boolean treasureHasBall(int treasure) {
        return sim.world.modes.treasureHave[treasure];
    }

    /** C {@code pl->ball}: the ball the connector reaches for, or -1. */
    public int towedBall(Player pl) {
        return pl.ball;
    }

    /** C {@code ball->owner} of a ball object. */
    public int ballOwner(int h) {
        return sim.world.modes.ballOwner[h];
    }

    /** C {@code WIRE_PTR(obj)->size} of an asteroid object. */
    public int asteroidSize(int h) {
        return sim.world.objects.size[h];
    }

    /** BEHAVIOR: C {@code obj->info} of an item object: its {@link Item} ordinal. */
    public int itemType(int h) {
        return sim.world.objects.info[h];
    }

    /** BEHAVIOR: C {@code BIT(obj->status, RANDOM_ITEM)}. */
    public boolean randomItem(int h) {
        return (sim.world.objects.status[h] & Obj.RANDOM_ITEM) != 0;
    }

    /** BEHAVIOR: C {@code NumPulses}: laser pulses in the world. */
    public int pulseCount() {
        return sim.world.pulses.count();
    }

    /** Pulse {@code i}: x (pixels). */
    public double pulseX(int i) {
        return sim.world.pulses.x[i];
    }

    /** Pulse {@code i}: y (pixels). */
    public double pulseY(int i) {
        return sim.world.pulses.y[i];
    }

    /** Pulse {@code i}: direction (RES). */
    public int pulseDir(int i) {
        return sim.world.pulses.dir[i];
    }

    /** Pulse {@code i}: owner id. */
    public int pulseOwner(int i) {
        return sim.world.pulses.owner[i];
    }

    /** Pulse {@code i} was reflected. */
    public boolean pulseReflected(int i) {
        Pulses p = sim.world.pulses;
        return p.refl[i];
    }

    // ------------------------------------------------------------------ actions

    /** C {@code Emergency_shield(ind, true)}. */
    public void emergencyShield(Player pl) {
        sim.devices.emergencyShield(pl, true);
    }

    /** C {@code Emergency_thrust(ind, 1)}. */
    public void emergencyThrust(Player pl) {
        sim.devices.emergencyThrust(pl, true);
    }

    /** C {@code Deflector(ind, true)}. */
    public void deflector(Player pl) {
        sim.devices.deflector(pl, true);
    }

    /** C {@code Place_mine} ({@code moving} false) or {@code Place_moving_mine} (true). */
    public void placeMine(Player pl, boolean moving) {
        if (moving) {
            sim.mines.placeMovingMine(pl);
        } else {
            sim.mines.placeMine(pl);
        }
    }

    /** C {@code Fire_ecm}. */
    public void fireEcm(Player pl) {
        sim.ecm.fire(pl);
    }

    /** C {@code Do_transporter}. */
    public void transporter(Player pl) {
        sim.devices.transporter(pl);
    }

    /** C {@code Fire_shot(ind, type, dir)} for a missile type. */
    public void fireMissile(Player pl, int objType, int dir) {
        sim.missiles.fireShot(pl, objType, dir);
    }

    /** C {@code item[HYPERJUMP]--; Add_fuel(ED_HYPERJUMP); do_hyperjump} (robots check the stock and fuel). */
    public void hyperjump(Player pl) {
        pl.items[Item.HYPERJUMP.ordinal()]--;
        pl.addFuel(-60 * 256);
        sim.devices.hyperjump(pl);
    }

    /** C {@code Tank_handle_detach}. */
    public void detachTank(Player pl) {
        sim.tanks.detach(pl);
    }

    /** C {@code Detach_ball(ind, -1)}. */
    public void detachBall(Player pl) {
        sim.balls.detach(pl, -1);
    }

    /** C {@code Compute_sensor_range}. */
    public void sensorRange(Player pl) {
        sim.locks.sensorRange(pl);
    }
}
