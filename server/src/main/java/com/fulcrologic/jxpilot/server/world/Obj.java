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
 * Object type bits ({@code OBJ_*} in {@code src/server/object.h}) and status bits ({@code src/common/rules.h}).
 * Players and pool objects share the status bits, as in C.
 */
public final class Obj {
    private Obj() {}

    // ---- types (object.h)
    public static final int PLAYER = 1;
    public static final int DEBRIS = 1 << 1;
    public static final int SPARK = 1 << 2;
    public static final int BALL = 1 << 3;
    public static final int SHOT = 1 << 4;
    public static final int SMART_SHOT = 1 << 5;
    public static final int MINE = 1 << 6;
    public static final int TORPEDO = 1 << 7;
    public static final int HEAT_SHOT = 1 << 8;
    public static final int PULSE = 1 << 9;
    public static final int ITEM = 1 << 10;
    public static final int WRECKAGE = 1 << 11;
    public static final int ASTEROID = 1 << 12;
    public static final int CANNON_SHOT = 1 << 13;

    /** C {@code KILLING_SHOTS} with player killing allowed (rules.c). */
    public static final int KILLING_SHOTS = SHOT | CANNON_SHOT | SMART_SHOT | TORPEDO | HEAT_SHOT | PULSE;

    // ---- status bits (rules.h)
    public static final int PLAYING = 1;
    public static final int PAUSE = 1 << 1;
    public static final int GAME_OVER = 1 << 2;
    public static final int THRUSTING = 1 << 3;
    public static final int SELF_DESTRUCT = 1 << 4;
    public static final int KILLED = 1 << 10;
    public static final int GRAVITY = 1 << 11;
    public static final int WARPING = 1 << 12;
    public static final int WARPED = 1 << 13;
    public static final int CONFUSED = 1 << 14;
    public static final int FROMCANNON = 1 << 15;
    public static final int HOVERPAUSE = 1 << 16;
    public static final int RECREATE = 1 << 17;
    public static final int FROMBOUNCE = 1 << 18;
    public static final int OWNERIMMUNE = 1 << 19;
    /** Player is storing a modifier or lock bank (KEY_REPROGRAM held). */
    public static final int REPROGRAM = 1 << 20;
    public static final int NOEXPLOSION = 1 << 21;
    public static final int COLLISIONSHOVE = 1 << 22;
    public static final int FINISH = 1 << 23;
    /** An item that shows up to clients as a random item type. */
    public static final int RANDOM_ITEM = 1 << 25;

    /** C {@code KILL_BITS} (rules.c): cleared on death. */
    public static final int KILL_BITS = THRUSTING | PLAYING | KILLED | SELF_DESTRUCT | WARPING | WARPED;

    // ---- modifiers: C's {@code modifiers} bit fields packed in an int, see {@link Mods}
    /** {@code mods.warhead & CLUSTER}. */
    public static final int MOD_CLUSTER = 1;
    /** {@code mods.warhead & IMPLOSION}. */
    public static final int MOD_IMPLOSION = 1 << 1;

    /** "No player" owner id (C {@code NO_ID}). */
    public static final int NO_ID = -1;
}
