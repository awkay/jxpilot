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

import com.fulcrologic.jxpilot.common.Item;

/**
 * The item and device state of each cannon (C {@code cannon_t} fields {@code item[]}, {@code used},
 * {@code damaged}, {@code tractor_*}, {@code emergency_shield_left}, {@code phasing_left}), indexed by the
 * {@code WorldMap.cannons()} index. Cannons only get items with cannonsUseItems, but ECM damage applies always.
 */
public final class CannonItems {
    /** {@code item[c * Item.COUNT + i]}. */
    private final int[] items;
    /** Device bits ({@link Has#EMERGENCY_SHIELD}, {@link Has#PHASING_DEVICE}). */
    public final int[] used;
    /** Frames of ECM damage left: a damaged cannon does not fire. */
    public final int[] damaged;
    /** Player id the cannon's tractor beam holds. */
    public final int[] tractorTarget;
    /** Frames of tractor beam left. */
    public final int[] tractorCount;
    public final boolean[] tractorIsPressor;
    public final int[] emergencyShieldLeft;
    public final int[] phasingLeft;

    public CannonItems(int cannons) {
        items = new int[cannons * Item.COUNT];
        used = new int[cannons];
        damaged = new int[cannons];
        tractorTarget = new int[cannons];
        tractorCount = new int[cannons];
        tractorIsPressor = new boolean[cannons];
        emergencyShieldLeft = new int[cannons];
        phasingLeft = new int[cannons];
    }

    /** Count of {@code item} at cannon {@code c}. */
    public int item(int c, Item item) {
        return items[c * Item.COUNT + item.ordinal()];
    }

    /** Count of item ordinal {@code i} at cannon {@code c}. */
    public int item(int c, int i) {
        return items[c * Item.COUNT + i];
    }

    /** Set the count of item ordinal {@code i}. */
    public void set(int c, int i, int n) {
        items[c * Item.COUNT + i] = n;
    }

    /** Add {@code n} to the count of {@code item}. */
    public void add(int c, Item item, int n) {
        items[c * Item.COUNT + item.ordinal()] += n;
    }

    /** BEHAVIOR: every count and timer of cannon {@code c} is 0 (C {@code Cannon_init}). */
    public void clear(int c) {
        for (int i = 0; i < Item.COUNT; i++) {
            items[c * Item.COUNT + i] = 0;
        }
        used[c] = 0;
        damaged[c] = 0;
        tractorTarget[c] = 0;
        tractorCount[c] = 0;
        tractorIsPressor[c] = false;
        emergencyShieldLeft[c] = 0;
        phasingLeft[c] = 0;
    }
}
