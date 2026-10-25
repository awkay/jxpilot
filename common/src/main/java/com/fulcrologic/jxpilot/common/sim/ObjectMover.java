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

import com.fulcrologic.jxpilot.common.Const;

import java.util.function.DoubleSupplier;

import static com.fulcrologic.jxpilot.common.sim.MoveState.BOUNCE_EDGE;
import static com.fulcrologic.jxpilot.common.sim.MoveState.CRASH_UNKNOWN;
import static com.fulcrologic.jxpilot.common.sim.MoveState.NOT_A_BOUNCE;
import static com.fulcrologic.jxpilot.common.sim.MoveState.NOT_A_CRASH;

/**
 * One tick's move of a pool object through the map: a port of C {@code Move_object} ({@code src/server/walls.c}),
 * using {@link Segment} for the per-block wall tracing. The server's {@code Mover} moves its pool objects with it, and
 * the client moves its dead-reckoned and provisional bullets with the same code.
 * <p>
 * One tick is one C frame: a move is C's {@code FLOAT_TO_CLICK(vel)} clicks (truncated toward zero, the fraction is
 * lost exactly as in C), and velocities, friction and bounce arithmetic are C floats.
 * <p>
 * The object is an {@link ObjectBody} (its position, velocity, life, status, heading and type); how it meets the map
 * (wall bounce, what it crashes into, its team, wormholes) is set on {@link #ms} before the call
 * ({@link MoveState#copyInfo}'s fields, plus {@code pl = null} and {@code obj}). Allocation free.
 */
public final class ObjectMover {
    private static final int BS = Const.BLOCK_SZ;

    private final ShipWorld w;
    private final Segment seg;
    /** The move: its info fields are the caller's to set before {@link #move}; after it, the crash and bounce. */
    public final MoveState ms = new MoveState();
    /** C {@code objectWallBounceLifeFactor}, {@code maxObjectWallBounceSpeed}, {@code objectWallBrakeFactor}. */
    public float objectWallBounceLifeFactor;
    public float maxObjectWallBounceSpeed;
    public float objectWallBrakeFactor;

    /** @param tieBreak the bounce tie-break source (C {@code rfrac()} on the server) */
    public ObjectMover(ShipWorld w, DoubleSupplier tieBreak) {
        this.w = w;
        this.seg = new Segment(w, tieBreak);
    }

    /** The segment tracer (its treasure hook is the server's to install). */
    public Segment segment() {
        return seg;
    }

    private static int blockOf(int pixel, int n) {
        int b = Math.floorDiv(pixel, BS);
        return b < 0 ? 0 : (b >= n ? n - 1 : b);
    }

    /**
     * Move {@code o} one tick (C {@code Move_object} after its {@code Object_position_remember}).
     * <p>
     * BEHAVIOR: far from walls ({@code walldist > 2} and the step shorter than {@code ((d-2)*35)>>1} px) the object
     * moves freely and wraps; {@link #ms}'s crash is then left as it was (the caller reads it only after a traced
     * move: {@link #traced}).
     * BEHAVIOR: otherwise it is traced through the blocks, its position, velocity and heading taken from the trace,
     * {@link #ms} holding the crash.
     * BEHAVIOR: on a wall bounce: life *= objectWallBounceLifeFactor (truncated); sparks not made by a ship bounce
     * lose owner immunity; faster than maxObjectWallBounceSpeed sets life 0 and ends the move; velocity and remaining
     * movement are multiplied by objectWallBrakeFactor.
     * BEHAVIOR: five consecutive steps without progress are CRASH_UNKNOWN.
     *
     * @return true when the position changed outside the plain step (a crash or a bounce: the server remembers the
     *         new position as the previous one too)
     */
    public boolean move(ObjectBody o) {
        return move(o, o);
    }

    /**
     * As {@link #move(ObjectBody)}, with the life and status read and written through {@code live} at the moment the
     * move uses them (the server's pool slot: a treasure hook may change them in the middle of the trace).
     */
    public boolean move(ObjectBody o, ObjectState live) {
        traced = false;
        int tx = Click.fromPixels(o.vx);
        int ty = Click.fromPixels(o.vy);
        int dist = w.wallDist(blockOf(o.cx >> Click.SHIFT, w.width()), blockOf(o.cy >> Click.SHIFT, w.height()));
        if (dist > 2) {
            int max = ((dist - 2) * BS) >> 1;
            if ((float) (max * max) >= o.vx * o.vx + o.vy * o.vy) {
                o.cx = w.wrapXClick(o.cx + tx);
                o.cy = w.wrapYClick(o.cy + ty);
                return false;
            }
        }
        traced = true;
        int type = o.type;
        ms.status = live.status();
        ms.type = type;
        ms.start(o.cx, o.cy, o.vx, o.vy, tx, ty, o.dir);

        int nothingDone = 0;
        boolean posUpdate = false;
        for (;;) {
            seg.move(ms);
            if ((ms.doneX | ms.doneY) == 0) {
                posUpdate |= (ms.crash | ms.bounce) != 0;
                if (ms.crash != NOT_A_CRASH) {
                    break;
                }
                if (ms.bounce != NOT_A_BOUNCE && ms.bounce != BOUNCE_EDGE) {
                    if (type != Obj.BALL) {
                        live.life((long) ((float) live.life() * objectWallBounceLifeFactor)); // C walls.c:2000
                    }
                    if (live.life() <= 0) {
                        break;
                    }
                    if ((live.status() & Obj.FROMBOUNCE) == 0 && type == Obj.SPARK) {
                        live.status(live.status() & ~Obj.OWNERIMMUNE);
                    }
                    if (ms.velX * ms.velX + ms.velY * ms.velY
                        > maxObjectWallBounceSpeed * maxObjectWallBounceSpeed) {
                        live.life(0);
                        break;
                    }
                    ms.velX *= objectWallBrakeFactor;
                    ms.velY *= objectWallBrakeFactor;
                    ms.todoX = (int) (ms.todoX * objectWallBrakeFactor);
                    ms.todoY = (int) (ms.todoY * objectWallBrakeFactor);
                }
                if (++nothingDone >= 5) {
                    ms.crash = CRASH_UNKNOWN;
                    break;
                }
            } else {
                ms.posX += ms.doneX;
                ms.posY += ms.doneY;
                nothingDone = 0;
            }
            if ((ms.todoX | ms.todoY) == 0) {
                break;
            }
        }
        if (ms.edgeWrap) {
            if (ms.posX < 0) {
                ms.posX += w.clickWidth();
            }
            if (ms.posX >= w.clickWidth()) {
                ms.posX -= w.clickWidth();
            }
            if (ms.posY < 0) {
                ms.posY += w.clickHeight();
            }
            if (ms.posY >= w.clickHeight()) {
                ms.posY -= w.clickHeight();
            }
        }
        o.cx = ms.posX;
        o.cy = ms.posY;
        o.vx = ms.velX;
        o.vy = ms.velY;
        o.dir = ms.dir;
        return posUpdate;
    }

    private boolean traced;

    /** Whether the last {@link #move} was traced through the blocks ({@link #ms}'s crash is then its result). */
    public boolean traced() {
        return traced;
    }
}
