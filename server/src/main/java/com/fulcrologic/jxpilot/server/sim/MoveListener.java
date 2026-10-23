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

import com.fulcrologic.jxpilot.server.world.Player;

/** Effects of wall movement that belong to the game rules (crashes, targets, bounce sparks). */
public interface MoveListener {
    /** A pool object crashed (C {@code Object_crash}); {@code ms.crash} says into what. */
    void objectCrashed(int h, MoveState ms);

    /** A player crashed or entered a wormhole (C {@code Player_crash}); {@code pt} is the hull point. */
    void playerCrashed(Player p, MoveState ms, int pt, boolean turning);

    /**
     * A player survived a wall bounce that cost fuel (Move_player): make the spark burst and, when the
     * bounce was off a target, damage it.
     *
     * @param wallDir RES direction of the wall normal
     * @param cost    C {@code cost} (unscaled fuel units before the drain multiplier)
     * @param target  target index or -1
     */
    void playerBounced(Player p, int wallDir, long cost, int target);
}
