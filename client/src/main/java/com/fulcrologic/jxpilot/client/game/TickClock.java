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
 * model"). Each sample gives {@code offset = tick - now * simHz}; the estimate follows samples smoothly.
 * Feed one sample per receive batch (its newest tick, see {@link ClientWorld}): packets queued behind a
 * stall arrive together, and only the newest of them says anything about the present.
 * <p>
 * A sample more than {@link #RESYNC_SECONDS} ahead of the estimate (we fell behind: reconnect, clock jump)
 * replaces it at once. A sample that far behind is usually a late burst, so it is ignored, unless
 * {@link #BACKWARD_RESYNC_SAMPLES} such samples arrive in a row (the server really went back: pause,
 * restart). Pure, allocation free.
 */
public final class TickClock {
    /** Weight of a new sample in the smoothed offset. */
    public static final double SMOOTHING = 0.1;
    /**
     * A sample this far (in time, so it holds at any tick rate: 30 ticks at 60 Hz, 7 at 14 Hz) from the
     * estimate is an outlier (see the class comment).
     */
    public static final double RESYNC_SECONDS = 0.5;
    /** Consecutive outliers behind the estimate needed before it moves back. */
    public static final int BACKWARD_RESYNC_SAMPLES = 4;

    private double simHz = 60;
    private boolean synced;
    private double offset;
    private int behind;

    /** Set the simulation rate (from WELCOME) and forget the estimate. */
    public void reset(double simHz) {
        this.simHz = simHz;
        synced = false;
        offset = 0;
        behind = 0;
    }

    public boolean synced() {
        return synced;
    }

    public double simHz() {
        return simHz;
    }

    /** {@link #RESYNC_SECONDS} in ticks at the current rate. */
    public double resyncTicks() {
        return RESYNC_SECONDS * simHz;
    }

    /** Local time in ticks. */
    double localTicks(long nowNanos) {
        return nowNanos * simHz / 1e9;
    }

    /**
     * Feed a snapshot's tick and arrival time.
     * <p>
     * BEHAVIOR: the first sample sets the estimate exactly.
     * BEHAVIOR: samples within {@link #resyncTicks()} move the offset by {@link #SMOOTHING} of the difference.
     * BEHAVIOR: a sample more than {@link #resyncTicks()} ahead replaces the offset at once.
     * BEHAVIOR: a sample more than {@link #resyncTicks()} behind is ignored, unless it is the
     * {@link #BACKWARD_RESYNC_SAMPLES}th such sample in a row, which replaces the offset; any other sample
     * restarts that count.
     */
    public void sample(int tick, long nowNanos) {
        double s = tick - localTicks(nowNanos);
        double d = s - offset;
        double resync = resyncTicks();
        if (!synced || d > resync) {
            offset = s;
            synced = true;
            behind = 0;
        } else if (d < -resync) {
            if (++behind >= BACKWARD_RESYNC_SAMPLES) {
                offset = s;
                behind = 0;
            }
        } else {
            offset += d * SMOOTHING;
            behind = 0;
        }
    }

    /** BEHAVIOR: estimated server tick (fractional) at {@code nowNanos}; 0 before the first sample. */
    public double serverTick(long nowNanos) {
        return synced ? localTicks(nowNanos) + offset : 0;
    }
}
