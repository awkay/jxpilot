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

/**
 * State of one point being moved through the map (C {@code move_state_t} plus its {@code move_info_t},
 * {@code src/server/walls.h}). Positions and movement are in clicks; velocity in pixels per frame, a C
 * float (only its sign and ratios matter for wall tracing; bounces flip and brake it).
 */
public final class MoveState {
    // ---- crash codes (move_crash_t)
    public static final int NOT_A_CRASH = 0;
    public static final int CRASH_UNIVERSE = 0x01;
    public static final int CRASH_WALL = 0x02;
    public static final int CRASH_TARGET = 0x04;
    public static final int CRASH_TREASURE = 0x08;
    public static final int CRASH_CANNON = 0x10;
    public static final int CRASH_UNKNOWN = 0x20;
    public static final int CRASH_WORMHOLE = 0x40;
    public static final int CRASH_WALL_SPEED = 0x80;
    public static final int CRASH_WALL_NO_FUEL = 0x100;
    public static final int CRASH_WALL_ANGLE = 0x200;

    // ---- bounce codes (move_bounce_t)
    public static final int NOT_A_BOUNCE = 0;
    public static final int BOUNCE_HOR_LO = 0x01;
    public static final int BOUNCE_HOR_HI = 0x02;
    public static final int BOUNCE_VER_LO = 0x04;
    public static final int BOUNCE_VER_HI = 0x08;
    public static final int BOUNCE_LEFT_DOWN = 0x10;
    public static final int BOUNCE_LEFT_UP = 0x20;
    public static final int BOUNCE_RIGHT_DOWN = 0x40;
    public static final int BOUNCE_RIGHT_UP = 0x80;
    public static final int BOUNCE_EDGE = 0x100;

    // ---- move_info_t
    public boolean edgeWrap;
    public boolean edgeBounce;
    public boolean wallBounce;
    public boolean cannonCrashes;
    public boolean targetCrashes;
    public boolean treasureCrashes;
    public boolean wormholeWarps;
    public boolean phased;
    /** The player being moved, or null for a pool object. */
    public Player pl;
    /** The pool object being moved (-1 for players). */
    public int obj = -1;
    /** Owner team of the object (players: their team). */
    public int team;
    /** Object status bits (FROMCANNON matters for cannons). */
    public int status;
    /** Object type bits. */
    public int type;
    /**
     * Team that a target treats as its own ({@code targetTeamCollision} off): the ship's or object's team, for a
     * ball its owner's team or TEAM_NOT_SET (walls.c Move_segment).
     */
    public int targetTeam;

    // ---- move_state_t
    public int crash;
    public int bounce;
    public int posX;
    public int posY;
    public float velX;
    public float velY;
    public int todoX;
    public int todoY;
    public int doneX;
    public int doneY;
    public int dir;
    public int cannon = -1;
    public int wormhole = -1;
    public int target = -1;
    public int treasure = -1;

    /**
     * Start a new move from (x, y) clicks with velocity (vx, vy) and {@code todo} clicks.
     * <p>
     * BEHAVIOR: clears crash, bounce, done and the hit cannon/wormhole/target/treasure indices.
     */
    public void start(int x, int y, float vx, float vy, int tx, int ty, int heading) {
        posX = x;
        posY = y;
        velX = vx;
        velY = vy;
        todoX = tx;
        todoY = ty;
        dir = heading;
        crash = NOT_A_CRASH;
        bounce = NOT_A_BOUNCE;
        doneX = 0;
        doneY = 0;
        cannon = -1;
        wormhole = -1;
        target = -1;
        treasure = -1;
    }

    /** Copy the move info (not the state) from another state. */
    public void copyInfo(MoveState o) {
        edgeWrap = o.edgeWrap;
        edgeBounce = o.edgeBounce;
        wallBounce = o.wallBounce;
        cannonCrashes = o.cannonCrashes;
        targetCrashes = o.targetCrashes;
        treasureCrashes = o.treasureCrashes;
        wormholeWarps = o.wormholeWarps;
        phased = o.phased;
        pl = o.pl;
        obj = o.obj;
        team = o.team;
        status = o.status;
        type = o.type;
        targetTeam = o.targetTeam;
    }
}
