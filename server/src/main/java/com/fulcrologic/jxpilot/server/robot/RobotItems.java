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
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Player;

/**
 * The capability gate between the robot AI and the parts of the game that do not exist yet (items, M2):
 * every item count, ability and item action the C robot code uses goes through here. This base class is
 * the S1 world: no item, special ability, pulse, ball or cloak is usable, so all gated branches of
 * {@link DefaultRobot} are inert, and their actions are no-ops. When items land, a subclass (or this class)
 * answers with the real counts and calls the real actions; tests use a spy subclass to exercise the
 * gated logic now.
 */
public class RobotItems {
    /** Ability bits the S1 simulation implements (shield, refuel, shots and the passive bits). */
    public static final int IMPLEMENTED_ABILITIES = Has.SHIELD | Has.REFUEL | Has.SHOT | Has.COMPASS | Has.REPAIR
        | Has.CONNECTOR | Has.LASER;

    /**
     * Item count the robot may use (C {@code pl->item[i]}).
     * <p>
     * BEHAVIOR: 0 for every item: none is implemented yet.
     */
    public int count(Player pl, Item item) {
        return 0;
    }

    /**
     * The player's abilities (C {@code pl->have}) restricted to the implemented ones.
     * <p>
     * BEHAVIOR: {@code have & IMPLEMENTED_ABILITIES}; emergency shield/thrust, deflector, cloak, tractor beam,
     * phasing and ball read as absent.
     */
    public int have(Player pl) {
        return pl.have & IMPLEMENTED_ABILITIES;
    }

    /**
     * C {@code pl->visibility[j].canSee}.
     * <p>
     * BEHAVIOR: always true: nobody can cloak yet, and C sees every uncloaked ship.
     */
    public boolean canSee(Player viewer, Player target) {
        return true;
    }

    /** C {@code pl->num_pulses}: laser pulses in flight. */
    public int numPulses(Player pl) {
        return 0;
    }

    /** C {@code pl->fuel.num_tanks}. */
    public int numTanks(Player pl) {
        return 0;
    }

    /** C {@code World.items[i].limit}. */
    public int limit(Item item) {
        return 0;
    }

    /** C {@code pl->emergency_shield_left || pl->phasing_left} counts as defensive items. */
    public int activeDefensives(Player pl) {
        return 0;
    }

    /** C {@code World.targets[i].damage} (fixed point); targets cannot be damaged yet, so always full. */
    public double targetDamage(int target) {
        return DefaultRobot.TARGET_DAMAGE;
    }

    /** C {@code World.treasures[i].have}: the treasure has its ball at home. */
    public boolean treasureHasBall(int treasure) {
        return false;
    }

    /** C {@code pl->ball}: pool handle of the ball the player tows, or -1. */
    public int towedBall(Player pl) {
        return -1;
    }

    /** C {@code ball->owner} of a ball object. */
    public int ballOwner(int h) {
        return -1;
    }

    /** C {@code WIRE_PTR(obj)->size} of an asteroid object. */
    public int asteroidSize(int h) {
        return 1;
    }

    /** C {@code obj->info} of an item object: its {@link Item} ordinal. */
    public int itemType(int h) {
        return 0;
    }

    /** C {@code BIT(obj->status, RANDOM_ITEM)}. */
    public boolean randomItem(int h) {
        return false;
    }

    /** C {@code NumPulses}: laser pulses in the world. */
    public int pulseCount() {
        return 0;
    }

    /** Pulse {@code i}: x (pixels). */
    public double pulseX(int i) {
        return 0;
    }

    /** Pulse {@code i}: y (pixels). */
    public double pulseY(int i) {
        return 0;
    }

    /** Pulse {@code i}: direction (RES). */
    public int pulseDir(int i) {
        return 0;
    }

    /** Pulse {@code i}: owner id. */
    public int pulseOwner(int i) {
        return -1;
    }

    /** Pulse {@code i} was reflected. */
    public boolean pulseReflected(int i) {
        return false;
    }

    // ------------------------------------------------------------------ actions (all no-ops in S1)

    /** C {@code Emergency_shield(ind, true)}. */
    public void emergencyShield(Player pl) {
    }

    /** C {@code Emergency_thrust(ind, 1)}. */
    public void emergencyThrust(Player pl) {
    }

    /** C {@code Deflector(ind, true)}. */
    public void deflector(Player pl) {
    }

    /** C {@code Place_mine} ({@code moving} false) or {@code Place_moving_mine} (true). */
    public void placeMine(Player pl, boolean moving) {
    }

    /** C {@code Fire_ecm}. */
    public void fireEcm(Player pl) {
    }

    /** C {@code Do_transporter}. */
    public void transporter(Player pl) {
    }

    /** C {@code Fire_shot(ind, type, dir)} for a missile type. */
    public void fireMissile(Player pl, int objType, int dir) {
    }

    /** C {@code item[HYPERJUMP]--; Add_fuel(ED_HYPERJUMP); do_hyperjump}. */
    public void hyperjump(Player pl) {
    }

    /** C {@code Tank_handle_detach}. */
    public void detachTank(Player pl) {
    }

    /** C {@code Detach_ball(ind, -1)}. */
    public void detachBall(Player pl) {
    }

    /** C {@code Compute_sensor_range}. */
    public void sensorRange(Player pl) {
    }
}
