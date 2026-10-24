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
package com.fulcrologic.jxpilot.server.world;

/**
 * Weapon modifiers: C's {@code modifiers} bit-field struct ({@code src/server/object.h}) packed into one int,
 * so objects and players store them in a plain int field. Layout: warhead bits 0-1 ({@link Obj#MOD_CLUSTER},
 * {@link Obj#MOD_IMPLOSION}), nuclear 2-3 ({@link #NUCLEAR}, {@link #FULLNUCLEAR}), velocity 4-5, mini 6-7,
 * spread 8-9, power 10-11, laser 12-13 ({@link #STUN}, {@link #BLIND}). Each field is 2 bits wide like C's.
 */
public final class Mods {
    private Mods() {}

    /** {@code mods.nuclear & NUCLEAR} (a field value, see {@link #nuclear}). */
    public static final int NUCLEAR = 1;
    /** {@code mods.nuclear & FULLNUCLEAR}. */
    public static final int FULLNUCLEAR = 2;
    /** {@code mods.laser & STUN}. */
    public static final int STUN = 1;
    /** {@code mods.laser & BLIND}. */
    public static final int BLIND = 2;

    /** C {@code MODS_*_MAX}. */
    public static final int NUCLEAR_MAX = 2;
    public static final int WARHEAD_MAX = 3;
    public static final int VELOCITY_MAX = 3;
    public static final int MINI_MAX = 3;
    public static final int SPREAD_MAX = 3;
    public static final int POWER_MAX = 3;
    public static final int LASER_MAX = 2;

    static final int WARHEAD_SHIFT = 0;
    static final int NUCLEAR_SHIFT = 2;
    static final int VELOCITY_SHIFT = 4;
    static final int MINI_SHIFT = 6;
    static final int SPREAD_SHIFT = 8;
    static final int POWER_SHIFT = 10;
    static final int LASER_SHIFT = 12;

    public static int warhead(int m) {
        return (m >>> WARHEAD_SHIFT) & 3;
    }

    public static int nuclear(int m) {
        return (m >>> NUCLEAR_SHIFT) & 3;
    }

    public static int velocity(int m) {
        return (m >>> VELOCITY_SHIFT) & 3;
    }

    public static int mini(int m) {
        return (m >>> MINI_SHIFT) & 3;
    }

    public static int spread(int m) {
        return (m >>> SPREAD_SHIFT) & 3;
    }

    public static int power(int m) {
        return (m >>> POWER_SHIFT) & 3;
    }

    public static int laser(int m) {
        return (m >>> LASER_SHIFT) & 3;
    }

    /** BEHAVIOR: replaces the 2-bit field at {@code shift} with {@code v & 3} (C assigns to a 2-bit field). */
    static int set(int m, int shift, int v) {
        return (m & ~(3 << shift)) | ((v & 3) << shift);
    }

    public static int withWarhead(int m, int v) {
        return set(m, WARHEAD_SHIFT, v);
    }

    public static int withNuclear(int m, int v) {
        return set(m, NUCLEAR_SHIFT, v);
    }

    public static int withVelocity(int m, int v) {
        return set(m, VELOCITY_SHIFT, v);
    }

    public static int withMini(int m, int v) {
        return set(m, MINI_SHIFT, v);
    }

    public static int withSpread(int m, int v) {
        return set(m, SPREAD_SHIFT, v);
    }

    public static int withPower(int m, int v) {
        return set(m, POWER_SHIFT, v);
    }

    public static int withLaser(int m, int v) {
        return set(m, LASER_SHIFT, v);
    }

    /** BEHAVIOR: true iff the NUCLEAR bit is set. */
    public static boolean isNuclear(int m) {
        return (nuclear(m) & NUCLEAR) != 0;
    }

    /** BEHAVIOR: true iff the FULLNUCLEAR bit is set. */
    public static boolean isFullNuclear(int m) {
        return (nuclear(m) & FULLNUCLEAR) != 0;
    }

    /** BEHAVIOR: true iff the CLUSTER warhead bit is set. */
    public static boolean isCluster(int m) {
        return (m & Obj.MOD_CLUSTER) != 0;
    }

    /** BEHAVIOR: true iff the IMPLOSION warhead bit is set. */
    public static boolean isImplosion(int m) {
        return (m & Obj.MOD_IMPLOSION) != 0;
    }

    /**
     * C {@code filter_mods} (event.c): drop the modifiers the rules do not allow.
     * <p>
     * BEHAVIOR: without allowNukes nuclear is cleared; without allowClusters the cluster bit; without
     * allowModifiers implosion, velocity, mini, spread and power; without allowLaserModifiers laser.
     */
    public static int filter(int m, boolean nukes, boolean clusters, boolean modifiers, boolean laserModifiers) {
        if (!nukes) {
            m = withNuclear(m, 0);
        }
        if (!clusters) {
            m &= ~Obj.MOD_CLUSTER;
        }
        if (!modifiers) {
            m &= ~Obj.MOD_IMPLOSION;
            m = withVelocity(m, 0);
            m = withMini(m, 0);
            m = withSpread(m, 0);
            m = withPower(m, 0);
        }
        if (!laserModifiers) {
            m = withLaser(m, 0);
        }
        return m;
    }
}
