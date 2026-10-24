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

/** Player abilities ({@code HAS_*} bits of {@code player->have/used}, {@code src/server/object.h}) and defaults (rules.c). */
public final class Has {
    private Has() {}

    public static final int EMERGENCY_THRUST = 1 << 30;
    public static final int AUTOPILOT = 1 << 29;
    public static final int TRACTOR_BEAM = 1 << 28;
    public static final int LASER = 1 << 27;
    public static final int CLOAKING_DEVICE = 1 << 26;
    public static final int SHIELD = 1 << 25;
    public static final int REFUEL = 1 << 24;
    public static final int REPAIR = 1 << 23;
    public static final int COMPASS = 1 << 22;
    public static final int AFTERBURNER = 1 << 21;
    public static final int CONNECTOR = 1 << 20;
    public static final int EMERGENCY_SHIELD = 1 << 19;
    public static final int DEFLECTOR = 1 << 18;
    public static final int PHASING_DEVICE = 1 << 17;
    public static final int MIRROR = 1 << 16;
    public static final int ARMOR = 1 << 15;
    public static final int SHOT = 1 << 4;
    public static final int BALL = 1 << 3;

    /** C {@code DEF_HAVE} before {@code Set_world_rules}/{@code Set_initial_resources}. */
    public static final int DEF_HAVE = SHIELD | COMPASS | REFUEL | REPAIR | CONNECTOR | SHOT | LASER;
    /** C {@code DEF_USED}. */
    public static final int DEF_USED = SHIELD | COMPASS;
    /** C {@code USED_KILL}: abilities switched off at death/respawn. */
    public static final int USED_KILL = REFUEL | REPAIR | CONNECTOR | SHOT | LASER | ARMOR | TRACTOR_BEAM
        | CLOAKING_DEVICE | PHASING_DEVICE | DEFLECTOR | MIRROR | EMERGENCY_SHIELD | EMERGENCY_THRUST;
    /** Both shield bits: C tests {@code BIT(used, HAS_SHIELD|HAS_EMERGENCY_SHIELD) == both}. */
    public static final int BOTH_SHIELDS = SHIELD | EMERGENCY_SHIELD;
}
