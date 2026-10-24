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
package com.fulcrologic.jxpilot.client.net;

/**
 * Live network measurements for the HUD's net diagnostics line (docs/latency-fixes.md §7.6): round trip time
 * from PING/PONG, snapshot inter-arrival jitter, snapshot loss and late (reordered) snapshots, the input lead and slack
 * (protocol v2, filled by {@link ClientConnection}), and the prediction corrections the phase 1c predictor will fill
 * (NaN until then). Fed by {@link ClientConnection}; pure and allocation
 * free (time is a parameter).
 */
public final class NetDiagnostics {
    /** Weight of a new RTT sample in the smoothed RTT (RFC 6298's alpha). */
    public static final double RTT_GAIN = 1 / 8.0;
    /** Weight of a new transit-time difference in the jitter estimate (RFC 3550 §6.4.1). */
    public static final double JITTER_GAIN = 1 / 16.0;
    /** Snapshot intervals per loss window (2 s at 14 Hz). */
    public static final int LOSS_WINDOW = 28;
    /** A tick jump of more than this many windows restarts the loss window without a reading (server reset). */
    public static final int LOSS_RESTART_WINDOWS = 4;
    /** RTT samples outside [0, this) are ignored (a stale or forged PONG). */
    public static final long MAX_RTT_NANOS = 10_000_000_000L;

    private double srttNanos = Double.NaN;
    private long lastRttNanos = -1;
    /** Until {@link #reset}: XPilot's default 14 frames per second. */
    private double tickNanos = 1e9 / 14;
    private int divisor = 1;
    private boolean haveSnapshot;
    private int lastTick;
    private long lastArrival;
    private double jitterNanos = Double.NaN;
    private int windowStart;
    private int windowReceived;
    private int windowLate;
    /** Ticks counted late in this window (a late tick's further parts must not count again). */
    private final int[] lateTicks = new int[LOSS_WINDOW_LATE_CAP];
    private int lateCount;
    /** Late ticks remembered per window; beyond this, further late ticks of the window are not counted. */
    public static final int LOSS_WINDOW_LATE_CAP = 64;
    private double lossPercent = Double.NaN;
    private double latePercent = Double.NaN;

    /** Input lead of the predicted clock over the server's present, in ticks ({@link PredictedClock}); NaN until it runs. */
    public double leadTicks = Double.NaN;
    /** The server's input slack report in ticks (the snapshot header's inputSlack); NaN until the first report. */
    public double inputSlackTicks = Double.NaN;
    /** Prediction corrections per second (phase 1c); NaN until then. */
    public double correctionsPerSecond = Double.NaN;
    /** Size of the last prediction correction in pixels (phase 1c); NaN until then. */
    public double lastCorrectionPx = Double.NaN;

    /**
     * A new game's timing (from WELCOME).
     * <p>
     * BEHAVIOR: forgets the snapshot jitter and loss (the RTT survives: same path); ticks last 1/simHz s and a
     * snapshot comes every {@code snapshotDivisor} ticks (both at least 1).
     */
    public void reset(int simHz, int snapshotDivisor) {
        tickNanos = 1e9 / Math.max(1, simHz);
        divisor = Math.max(1, snapshotDivisor);
        haveSnapshot = false;
        jitterNanos = Double.NaN;
        lossPercent = Double.NaN;
        latePercent = Double.NaN;
    }

    /**
     * A PONG came back {@code nanos} after its PING.
     * <p>
     * BEHAVIOR: the first sample sets the smoothed RTT, later ones move it by {@link #RTT_GAIN} of the difference;
     * samples below 0 or from {@link #MAX_RTT_NANOS} up are ignored.
     */
    public void rttSample(long nanos) {
        if (nanos < 0 || nanos >= MAX_RTT_NANOS) {
            return;
        }
        lastRttNanos = nanos;
        srttNanos = Double.isNaN(srttNanos) ? nanos : srttNanos + RTT_GAIN * (nanos - srttNanos);
    }

    /**
     * A snapshot for {@code tick}, newer than every one before it, arrived at {@code nowNanos}.
     * <p>
     * BEHAVIOR: jitter (RFC 3550): D = (arrival gap) - (tick gap) * tick length; J moves by
     * {@link #JITTER_GAIN} of (|D| - J), starting at |D| on the second snapshot.
     * BEHAVIOR: loss: the first snapshot opens a window; each later one counts as received; once the window spans
     * {@link #LOSS_WINDOW} snapshot intervals the loss reading is 100 * (1 - received / expected), expected being
     * the intervals spanned (each received snapshot is a distinct newer tick, so at most expected), and the next
     * window starts at this snapshot. A jump of more than
     * {@link #LOSS_RESTART_WINDOWS} windows restarts the window with no reading.
     * BEHAVIOR: a tick not newer than the previous one changes nothing.
     */
    public void snapshot(int tick, long nowNanos) {
        if (!haveSnapshot) {
            haveSnapshot = true;
            lastTick = tick;
            lastArrival = nowNanos;
            windowStart = tick;
            windowReceived = 0;
            windowLate = 0;
            lateCount = 0;
            return;
        }
        int ticks = tick - lastTick;
        if (ticks <= 0) {
            return;
        }
        double d = Math.abs((nowNanos - lastArrival) - ticks * tickNanos);
        jitterNanos = Double.isNaN(jitterNanos) ? d : jitterNanos + JITTER_GAIN * (d - jitterNanos);
        lastTick = tick;
        lastArrival = nowNanos;
        int span = tick - windowStart;
        int window = LOSS_WINDOW * divisor;
        if (span > LOSS_RESTART_WINDOWS * window) {
            windowStart = tick;
            windowReceived = 0;
            windowLate = 0;
            lateCount = 0;
            return;
        }
        windowReceived++;
        if (span >= window) {
            double expected = span / (double) divisor;
            lossPercent = 100 * (1 - windowReceived / expected);
            latePercent = 100 * windowLate / expected;
            windowStart = tick;
            windowReceived = 0;
            windowLate = 0;
            lateCount = 0;
        }
    }

    /**
     * A snapshot for {@code tick}, older than the newest one, arrived (reordered or delayed behind a newer one).
     * <p>
     * BEHAVIOR: one inside the current loss window counts as received (so not as lost) and as late, once per tick
     * (further parts or duplicates of the same late tick are not counted again; at most
     * {@link #LOSS_WINDOW_LATE_CAP} late ticks per window); an older one (from a window already read) changes nothing.
     * Jitter is not affected.
     */
    public void lateSnapshot(int tick) {
        if (!haveSnapshot || tick - windowStart <= 0 || tick - lastTick >= 0 || lateCount == LOSS_WINDOW_LATE_CAP) {
            return;
        }
        for (int i = 0; i < lateCount; i++) {
            if (lateTicks[i] == tick) {
                return; // another part (or a duplicate) of a late tick already counted
            }
        }
        lateTicks[lateCount++] = tick;
        windowReceived++;
        windowLate++;
    }

    /** Smoothed round trip time in milliseconds, NaN before the first PONG. */
    public double rttMs() {
        return srttNanos / 1e6;
    }

    /** The newest RTT sample in milliseconds, NaN before the first PONG. */
    public double lastRttMs() {
        return lastRttNanos < 0 ? Double.NaN : lastRttNanos / 1e6;
    }

    /** Snapshot inter-arrival jitter in milliseconds, NaN before two snapshots. */
    public double jitterMs() {
        return jitterNanos / 1e6;
    }

    /** Snapshots of the last full window that never arrived, in percent; NaN before one. Late ones are not lost. */
    public double lossPercent() {
        return lossPercent;
    }

    /** Snapshots of the last full window that arrived late (after a newer one), in percent; NaN before one. */
    public double latePercent() {
        return latePercent;
    }
}
