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
package com.fulcrologic.jxpilot.client.game.predict;

/**
 * How a bullet moves for one server tick (the server's {@code moveObjects} for a SHOT or CANNON_SHOT, then its
 * expiry): what the client steps its own provisional shots and every dead-reckoned shot with. One implementation
 * today ({@link ClientShotMotion}); the server's object mover moved into common will replace it.
 */
public interface ShotMotion {
    /**
     * One tick of a bullet at clicks (x, y) with velocity (vx, vy) px per tick and {@code life} ticks left, into
     * {@code out}: gravity at its block (shotsGravity), the move through the map, one tick of life.
     *
     * @param cannon a cannon's shot (CANNON_SHOT, FROMCANNON: it flies through cannons outside team play), else a
     *               player's SHOT
     * @return false when it ends in that tick (a crash, a fatal bounce, its life out)
     */
    boolean tick(int x, int y, float vx, float vy, long life, boolean cannon, ShotStep out);
}
