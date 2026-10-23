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
package com.fulcrologic.jxpilot.server.net;

/**
 * Fixed-rate tick timing for the server loop. Pure: give it the clock reading, it says how many
 * ticks are due and how long to wait. Tick n is due at {@code start + n * period}. When the loop falls
 * more than {@code maxCatchUp} ticks behind (a GC pause, a suspended laptop), it runs
 * {@code maxCatchUp} ticks and re-bases the schedule instead of fast-forwarding through the backlog.
 */
public final class TickScheduler {
    private final long periodNanos;
    private final int maxCatchUp;
    private long next;
    private long skipped;

    public TickScheduler(int hz, int maxCatchUp, long startNanos) {
        this.periodNanos = 1_000_000_000L / hz;
        this.maxCatchUp = maxCatchUp;
        this.next = startNanos + periodNanos;
    }

    /**
     * Consume the ticks due at {@code now}.
     * <p>
     * BEHAVIOR: returns the number of whole periods elapsed since the last due tick (0 before the next
     * deadline) and advances the deadline by that many periods.
     * BEHAVIOR: above maxCatchUp it returns maxCatchUp, counts the rest in {@link #skipped()}, and puts
     * the next deadline one period after {@code now}.
     */
    public int due(long now) {
        if (now < next) {
            return 0;
        }
        long n = (now - next) / periodNanos + 1;
        if (n > maxCatchUp) {
            skipped += n - maxCatchUp;
            next = now + periodNanos;
            return maxCatchUp;
        }
        next += n * periodNanos;
        return (int) n;
    }

    /** BEHAVIOR: nanoseconds until the next deadline, 0 if it has passed. */
    public long nanosUntilNext(long now) {
        return Math.max(0, next - now);
    }

    /** Ticks dropped by catch-up limiting so far. */
    public long skipped() {
        return skipped;
    }

    public long periodNanos() {
        return periodNanos;
    }
}
