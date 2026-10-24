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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.sim.Click;
import com.fulcrologic.jxpilot.common.sim.MoveState;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.Segment;
import com.fulcrologic.jxpilot.common.sim.ShipMover;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import static com.fulcrologic.jxpilot.common.sim.MoveState.*;

/**
 * Moves pool objects through the map: a port of {@code Move_object} ({@code src/server/walls.c}), using
 * {@link Segment} for the per-block wall tracing. Ships ({@code Move_player}, {@code Turn_player}) are moved by
 * the ship kernel's {@link ShipMover}; the methods for them here delegate to it.
 * <p>
 * One sim tick is one C frame: a move is C's {@code FLOAT_TO_CLICK(vel)} clicks (truncated toward zero, the
 * fraction is lost exactly as in C), and velocities, friction and bounce arithmetic are C floats.
 */
public final class Mover {
    private static final int BS = Const.BLOCK_SZ;

    private final World w;
    private final Simulation sim;
    private final Segment seg;
    private final MoveState ms = new MoveState();

    /** Install the ball reactions to treasures (balls exist once the simulation is built). */
    void setTreasureHook(Segment.TreasureHook hook) {
        seg.treasureHook = hook;
    }

    public Mover(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        this.seg = new Segment(w, w::rfrac);
    }

    private int blockOf(int pixel, int n) {
        int b = Math.floorDiv(pixel, BS);
        return b < 0 ? 0 : (b >= n ? n - 1 : b);
    }

    // ================================================================ objects

    /**
     * Move a pool object one tick (C {@code Move_object}).
     * <p>
     * BEHAVIOR: remembers the previous position first.
     * BEHAVIOR: far from walls ({@code walldist > 2} and the step shorter than {@code ((d-2)*35)>>1} px)
     * the object moves freely and wraps.
     * BEHAVIOR: otherwise it is traced through the blocks; a crash calls {@link Crashes#objectCrashed}.
     * BEHAVIOR: on a wall bounce: life *= objectWallBounceLifeFactor (truncated); sparks not made by a ship
     * bounce lose owner immunity; faster than maxObjectWallBounceSpeed kills it; velocity and remaining
     * movement are multiplied by objectWallBrakeFactor.
     * BEHAVIOR: five consecutive steps without progress are CRASH_UNKNOWN.
     */
    public void moveObject(int h) {
        Objects o = w.objects;
        Params p = w.params;
        o.remember(h);
        int tx = Click.fromPixels(o.vx[h]);
        int ty = Click.fromPixels(o.vy[h]);
        int dist = w.wallDist(blockOf(o.px[h], w.width), blockOf(o.py[h], w.height));
        if (dist > 2) {
            int max = ((dist - 2) * BS) >> 1;
            if ((float) (max * max) >= o.vx[h] * o.vx[h] + o.vy[h] * o.vy[h]) {
                o.setClicks(h, w.wrapXClick(o.cx[h] + tx), w.wrapYClick(o.cy[h] + ty));
                o.touchCell(h);
                return;
            }
        }
        int type = o.type[h];
        ms.pl = null;
        ms.obj = h;
        ms.edgeWrap = w.wrap;
        ms.edgeBounce = p.edgeBounce;
        ms.wallBounce = (p.objBounceMask & type) != 0;
        ms.cannonCrashes = (p.objCannonMask & type) != 0;
        ms.targetCrashes = (p.objTargetMask & type) != 0;
        ms.treasureCrashes = (p.objTreasureMask & type) != 0;
        ms.wormholeWarps = true;
        ms.phased = type == Obj.BALL && sim.balls.phased(h);
        ms.team = o.team[h];
        ms.targetTeam = type == Obj.BALL ? ballTeam(h) : o.team[h];
        ms.status = o.status[h];
        ms.type = type;
        ms.start(o.cx[h], o.cy[h], o.vx[h], o.vy[h], tx, ty, o.missileDir[h]);

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
                        o.life[h] = (long) ((float) o.life[h] * p.objectWallBounceLifeFactor); // C walls.c:2000 float product
                    }
                    if (o.life[h] <= 0) {
                        break;
                    }
                    if ((o.status[h] & Obj.FROMBOUNCE) == 0 && type == Obj.SPARK) {
                        o.status[h] &= ~Obj.OWNERIMMUNE;
                    }
                    if (ms.velX * ms.velX + ms.velY * ms.velY
                        > p.maxObjectWallBounceSpeed * p.maxObjectWallBounceSpeed) {
                        o.life[h] = 0;
                        break;
                    }
                    ms.velX *= p.objectWallBrakeFactor;
                    ms.velY *= p.objectWallBrakeFactor;
                    ms.todoX = (int) (ms.todoX * p.objectWallBrakeFactor);
                    ms.todoY = (int) (ms.todoY * p.objectWallBrakeFactor);
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
                ms.posX += w.clickWidth;
            }
            if (ms.posX >= w.clickWidth) {
                ms.posX -= w.clickWidth;
            }
            if (ms.posY < 0) {
                ms.posY += w.clickHeight;
            }
            if (ms.posY >= w.clickHeight) {
                ms.posY -= w.clickHeight;
            }
        }
        o.setClicks(h, ms.posX, ms.posY);
        o.vx[h] = ms.velX;
        o.vy[h] = ms.velY;
        o.missileDir[h] = ms.dir;
        if (ms.crash != NOT_A_CRASH) {
            sim.crashes.objectCrashed(h, ms);
        }
        if (posUpdate) {
            o.remember(h);
        }
        o.touchCell(h);
    }

    /** A ball's team for its target test: its owner's team, else TEAM_NOT_SET (walls.c Move_segment). */
    private int ballTeam(int h) {
        int owner = w.modes.ballOwner[h];
        Player pl = owner == Obj.NO_ID ? null : w.byId(owner);
        return pl != null ? pl.team : Const.TEAM_NOT_SET;
    }

    // ================================================================ players (the ship kernel)

    /**
     * Move a ship one tick: C {@code Move_player} ({@link ShipMover#movePlayer}).
     * <p>
     * BEHAVIOR: with predictableBounces the tie-break source is first reseeded from (net tick, player id).
     */
    public void movePlayer(Player pl) {
        if (sim.predictableBounces) {
            sim.tieBreak.reseed(w.tick, pl.id);
        }
        sim.kernel.mover.movePlayer(pl, sim.effects);
    }

    /** Turn a ship one RES step at a time toward its float heading: C {@code Turn_player} ({@link ShipMover#turnPlayer}). */
    public void turnPlayer(Player pl) {
        sim.kernel.mover.turnPlayer(pl, sim.effects);
    }
}
