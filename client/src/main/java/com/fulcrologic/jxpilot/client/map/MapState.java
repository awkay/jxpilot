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
package com.fulcrologic.jxpilot.client.map;

/**
 * Changing per-object map state the symbol renderer needs (from the server in a game; constant in
 * the map viewer). Indices are those of the {@code WorldMap} object lists.
 */
public interface MapState {
    /** Fuel in station {@code i}, 0..{@code Const.MAX_STATION_FUEL}. */
    double fuel(int i);

    /** Cannon {@code i} is alive (dead cannons are not drawn). */
    boolean cannonAlive(int i);

    /** Target {@code i} is alive (dead targets are not drawn). */
    boolean targetAlive(int i);

    /** Remaining target strength, {@code Const.TARGET_DAMAGE} when undamaged. */
    double targetDamage(int i);

    /** The viewing player's team, or {@code Const.TEAM_NOT_SET}. Own targets/treasures draw blue. */
    int selfTeam();

    /** Index of the next race checkpoint (drawn filled). */
    int nextCheckpoint();

    /** F12: the name of the player who owns base {@code i} (C Base_info_by_pos), or null when nobody does. */
    default CharSequence baseOwnerName(int i) {
        return null;
    }

    /**
     * Frame counter ({@code loops} in C) driving wormhole and concentrator animation: the server's frame
     * number, advancing once per server tick at the map's {@code framesPerSecond} (one tick = one C frame,
     * SPEC "Time model"); the same value as {@code SceneData.loops}.
     */
    long loops();
}
