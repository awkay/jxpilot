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
package com.fulcrologic.jxpilot.common;

/** Item types, ordinal equals the C {@code ITEM_*} value in {@code src/common/item.h}. */
public enum Item {
    FUEL, WIDEANGLE, REARSHOT, AFTERBURNER, CLOAK, SENSOR, TRANSPORTER, TANK, MINE, MISSILE, ECM,
    LASER, EMERGENCY_THRUST, TRACTOR_BEAM, AUTOPILOT, EMERGENCY_SHIELD, DEFLECTOR, HYPERJUMP, PHASING,
    MIRROR, ARMOR;

    /** Number of item types ({@code NUM_ITEMS}). */
    public static final int COUNT = 21;

    private static final Item[] VALUES = values();

    /** Item for a wire id, or null. */
    public static Item of(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }

    /** Bit for item masks. */
    public int bit() {
        return 1 << ordinal();
    }
}
