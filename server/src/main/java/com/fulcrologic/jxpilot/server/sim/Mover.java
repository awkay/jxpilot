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
import com.fulcrologic.jxpilot.common.sim.ObjectMover;
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

    private final World w;
    private final Simulation sim;
    /** The pool objects' C Move_object (common: the client moves its bullets with the same code). */
    private final ObjectMover objects;
    /** The moving slot's life and status, live in the pool (the balls' treasure hook changes them mid-move). */
    private final Slot live = new Slot();

    private final class Slot implements com.fulcrologic.jxpilot.common.sim.ObjectState {
        int h;

        @Override
        public double life() { return w.objects.life[h]; }

        @Override
        public void life(double v) { w.objects.life[h] = v; }

        @Override
        public int status() { return w.objects.status[h]; }

        @Override
        public void status(int v) { w.objects.status[h] = v; }
    }

    /** Install the ball reactions to treasures (balls exist once the simulation is built). */
    void setTreasureHook(Segment.TreasureHook hook) {
        objects.segment().treasureHook = hook;
    }

    public Mover(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
        this.objects = new ObjectMover(w, w::rfrac);
    }

    // ================================================================ objects

    /**
     * Move a pool object one tick (C {@code Move_object}): the slot through the common {@link ObjectMover}, with the
     * object's crash rules from the server's masks.
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
        o.remember(h);
        float vx = o.vx[h];
        float vy = o.vy[h];
        // Move_object's fast path on the pool arrays (ObjectMover.free: the same test the client's bullets use)
        if (ObjectMover.free(w.wallDist(ObjectMover.blockOf(o.px[h], w.width), ObjectMover.blockOf(o.py[h], w.height)),
            vx, vy)) {
            o.setClicks(h, w.wrapXClick(o.cx[h] + Click.fromPixels(vx)), w.wrapYClick(o.cy[h] + Click.fromPixels(vy)));
            o.touchCell(h);
            return;
        }
        Params p = w.params;
        int type = o.type[h];
        MoveState ms = objects.ms;
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
        objects.objectWallBounceLifeFactor = p.objectWallBounceLifeFactor;
        objects.maxObjectWallBounceSpeed = p.maxObjectWallBounceSpeed;
        objects.objectWallBrakeFactor = p.objectWallBrakeFactor;
        live.h = h;
        boolean posUpdate = objects.trace(o.cx[h], o.cy[h], vx, vy, o.missileDir[h], type, o.status[h], live);
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

    /** Move a ship one tick: C {@code Move_player} ({@link ShipMover#movePlayer}). */
    public void movePlayer(Player pl) {
        sim.kernel.mover.movePlayer(pl, sim.effects);
    }

    /** Turn a ship one RES step at a time toward its float heading: C {@code Turn_player} ({@link ShipMover#turnPlayer}). */
    public void turnPlayer(Player pl) {
        sim.kernel.mover.turnPlayer(pl, sim.effects);
    }
}
