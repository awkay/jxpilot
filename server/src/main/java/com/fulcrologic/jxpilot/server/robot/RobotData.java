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

/**
 * Per-robot state, one preallocated instance per player slot: the robot manager's {@code robot_data_t}, the
 * default robot's {@code robot_default_data_t} ({@code src/server/robot.h}), and the parts of C
 * {@code player} that only robots use and that {@link com.fulcrologic.jxpilot.server.world.Player} does not
 * have (fuel levels, the repair target). The lock, the modifiers and the tractor beam direction are the
 * player's own ({@code Player.lockTagged}, {@code Player.mods}, {@code Player.tractorIsPressor}).
 */
public final class RobotData {
    /** The slot holds a robot. */
    public boolean active;
    /** Index into the {@link RobotRoster} (C {@code robots_ind}). */
    public int def = -1;

    // ---- robot_default_data_t
    /** C {@code robot_lock}: {@link DefaultRobot#LOCK_PLAYER} when at war. */
    public int robotLock;
    /** C {@code robot_lock_id}: the player the robot is at war with. */
    public int robotLockId;
    /** C {@code robot_mode}: one of the {@code RM_*} modes of {@link DefaultRobot}. */
    public int robotMode;
    /** C {@code robot_count}: counts down every frame, used instead of random numbers. */
    public int robotCount;
    /** How aggressive (1-99). */
    public int attack;
    /** How defensive (1-99). */
    public int defense;
    public double normalSpeed;
    public double attackSpeed;
    public double maxSpeed;
    public int lastUsedEcm;
    public int lastDroppedMine;
    public int lastFiredMissile;
    public int lastThrownBall;
    /** C {@code longterm_mode}: FETCH_TREASURE, TARGET_KILL, NEED_FUEL bits. */
    public int longtermMode;
    public int lockLastSeen;
    public int lockLastX;
    public int lockLastY;

    // ---- C player fields only robots use
    /** C {@code fuel.l1/l2/l3}: fuel warning levels (fixed point). */
    public double fuelL1;
    public double fuelL2;
    public double fuelL3;
    /** C {@code repair_target}. */
    public int repairTarget;
    /** Last engine power reported in a THRUST event (robots change power directly, not via keys). */
    public double reportedPower;

    /** Forget everything (slot freed or reused). */
    void clear() {
        active = false;
        def = -1;
        robotLock = 0;
        robotLockId = 0;
        robotMode = 0;
        robotCount = 0;
        attack = 0;
        defense = 0;
        normalSpeed = 0;
        attackSpeed = 0;
        maxSpeed = 0;
        lastUsedEcm = 0;
        lastDroppedMine = 0;
        lastFiredMissile = 0;
        lastThrownBall = 0;
        longtermMode = 0;
        lockLastSeen = 0;
        lockLastX = 0;
        lockLastY = 0;
        fuelL1 = 0;
        fuelL2 = 0;
        fuelL3 = 0;
        repairTarget = 0;
        reportedPower = 0;
    }
}
