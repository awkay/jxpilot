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

import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.sim.KernelEvents;
import com.fulcrologic.jxpilot.common.sim.MoveState;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.SeededTieBreak;
import com.fulcrologic.jxpilot.common.sim.ShipBody;

/**
 * The client's {@link KernelEvents} for predicting its own ship (docs/latency-fixes.md §4.4, §4.10). It honours what
 * changes the ship's own state, and records what the predictor needs:
 * <ul>
 *   <li>a crash sets KILLED and is remembered: prediction freezes until the server rules;</li>
 *   <li>a bullet is reported to the {@link ShotSink} with its exact start and velocity (the kernel already applied
 *   the recoil), numbered within the tick;</li>
 *   <li>exhaust is remembered for the own exhaust drawn from the predicted state;</li>
 *   <li>a warp, an unpause key and a pause key are remembered: the predictor stops predicting (wormholes and
 *   unpausing need the server's world, Go_home);</li>
 *   <li>with predictableBounces the bounce tie-break source is reseeded from (tick, own id) at the start of the
 *   fly phase, before the ship turns and moves, as the server does.</li>
 * </ul>
 * Known mispredictions, left to the server's correction: devices (emergency thrust and shield timers, phasing,
 * autopilot), upkeep (device fuel drain, refuelling, target repair), item damage on wall bounces, and every
 * interaction with other objects. Allocation free.
 */
public final class PredictEvents implements KernelEvents {
    /** Where predicted bullets go. */
    public interface ShotSink {
        /**
         * A bullet fired in predicted tick {@code tick}, the {@code ordinal}-th of that tick, at pixel (x, y) with
         * velocity (vx, vy) px per tick; {@code speculative} for the partial tick being collected.
         */
        void fired(int tick, int ordinal, float x, float y, float vx, float vy, boolean speculative);

        /** Tick {@code tick} is about to be stepped (again, on a replay); {@code speculative} for the partial tick. */
        default void beginStep(int tick, boolean speculative) {
        }
    }

    private final SeededTieBreak tieBreak;
    private ShotSink shots;
    private boolean predictable;
    private int ownId;
    private int tick;
    private boolean speculative;
    private int ordinal;
    private boolean crashed;
    private boolean warped;
    private boolean pauseKey;
    private boolean thrust;
    private int fired;

    /** @param tieBreak the bounce tie-break source the predictor's kernel draws from (reseeded here) */
    public PredictEvents(SeededTieBreak tieBreak) {
        this.tieBreak = tieBreak;
    }

    public void setShotSink(ShotSink sink) {
        shots = sink;
    }

    /** BEHAVIOR: with {@code on}, the tie-break source is reseeded per fly; the own id seeds it. */
    public void configure(boolean on, int playerId) {
        predictable = on;
        ownId = playerId;
    }

    /**
     * BEHAVIOR: starts the step of {@code t}: clears the step's records and the bullet numbering, and tells the
     * sink.
     */
    public void beginStep(int t, boolean isSpeculative) {
        tick = t;
        speculative = isSpeculative;
        ordinal = 0;
        crashed = false;
        warped = false;
        pauseKey = false;
        thrust = false;
        fired = 0;
        if (shots != null) {
            shots.beginStep(t, isSpeculative);
        }
    }

    /** The ship crashed in this step (it is KILLED). */
    public boolean crashed() { return crashed; }

    /** The ship went through a wormhole in this step. */
    public boolean warped() { return warped; }

    /** A PAUSE key went down in this step (C pauses or unpauses on the server's world). */
    public boolean pauseKey() { return pauseKey; }

    /** The ship made exhaust in this step. */
    public boolean thrust() { return thrust; }

    /** Bullets fired in this step. */
    public int fired() { return fired; }

    /** BEHAVIOR: at the start of the fly phase with predictableBounces: reseed the tie-break from (tick, id). */
    @Override
    public void begin(ShipBody b, int phase) {
        if (phase == PHASE_FLY && predictable) {
            tieBreak.reseed(tick, ownId);
        }
    }

    /** BEHAVIOR: PAUSE is remembered ({@link #pauseKey}); no key stops the kernel's key handling here. */
    @Override
    public boolean keyPressed(ShipBody b, ControlKey k) {
        if (k == ControlKey.PAUSE) {
            pauseKey = true;
        }
        return false;
    }

    @Override
    public void keyReleased(ShipBody b, ControlKey k) {
    }

    @Override
    public void autopilotOff(ShipBody b) {
    }

    @Override
    public void powerChanged(ShipBody b) {
    }

    /** BEHAVIOR: always room (the server's object pool is not the client's to know). */
    @Override
    public boolean shotRoom() {
        return true;
    }

    /**
     * BEHAVIOR: every bullet counts in {@link #fired} (the server's unpositioned one also expires and gives its
     * shot back); a positioned one goes to the sink with the next ordinal of the tick. Returns -1 (the client has
     * no object handles).
     */
    @Override
    public int shot(ShipBody b, float x, float y, int dir, float vx, float vy, boolean outside) {
        fired++;
        if (outside) {
            return -1;
        }
        if (shots != null) {
            shots.fired(tick, ordinal, x, y, vx, vy, speculative);
        }
        ordinal++;
        return -1;
    }

    @Override
    public void devices(ShipBody b) {
    }

    @Override
    public void upkeep(ShipBody b) {
    }

    /** BEHAVIOR: remembered ({@link #warped}); the warp itself is the server's (false: no C break). */
    @Override
    public boolean warp(ShipBody b) {
        warped = true;
        return false;
    }

    /** BEHAVIOR: remembered ({@link #thrust}). */
    @Override
    public void thrustSparks(ShipBody b) {
        thrust = true;
    }

    @Override
    public void turnSparks(ShipBody b, int count) {
    }

    @Override
    public void sensors(ShipBody b) {
    }

    /** BEHAVIOR: the ship is KILLED and the crash remembered ({@link #crashed}). */
    @Override
    public void crashed(ShipBody b, MoveState m, int point) {
        b.status |= Obj.KILLED;
        crashed = true;
    }

    @Override
    public void wallDamage(ShipBody b) {
    }

    @Override
    public void bounced(ShipBody b, int wallDir, long cost, int target) {
    }
}
