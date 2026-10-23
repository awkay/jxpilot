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
package com.fulcrologic.jxpilot.client.game;

/**
 * Estimates the server's simulation tick from the local clock, from snapshot arrivals (SPEC "Time
 * model"). Each arrival gives a sample {@code offset = tick - now * simHz}; the estimate follows
 * samples smoothly and jumps when it is off by more than {@link #RESYNC_TICKS}. Pure, allocation free.
 */
public final class TickClock {
    /** Weight of a new sample in the smoothed offset. */
    public static final double SMOOTHING = 0.1;
    /** A sample this many ticks away from the estimate replaces it outright (pause, reconnect, clock jump). */
    public static final double RESYNC_TICKS = 30;

    private double simHz = 60;
    private boolean synced;
    private double offset;

    /** Set the simulation rate (from WELCOME) and forget the estimate. */
    public void reset(double simHz) {
        this.simHz = simHz;
        synced = false;
        offset = 0;
    }

    public boolean synced() {
        return synced;
    }

    public double simHz() {
        return simHz;
    }

    /** Local time in ticks. */
    double localTicks(long nowNanos) {
        return nowNanos * simHz / 1e9;
    }

    /**
     * Feed a snapshot's tick and arrival time.
     * <p>
     * BEHAVIOR: the first sample sets the estimate exactly.
     * BEHAVIOR: later samples move the offset by {@link #SMOOTHING} of the difference.
     * BEHAVIOR: a sample more than {@link #RESYNC_TICKS} away replaces the offset.
     */
    public void sample(int tick, long nowNanos) {
        double s = tick - localTicks(nowNanos);
        if (!synced || Math.abs(s - offset) > RESYNC_TICKS) {
            offset = s;
            synced = true;
        } else {
            offset += (s - offset) * SMOOTHING;
        }
    }

    /** BEHAVIOR: estimated server tick (fractional) at {@code nowNanos}; 0 before the first sample. */
    public double serverTick(long nowNanos) {
        return synced ? localTicks(nowNanos) + offset : 0;
    }
}
