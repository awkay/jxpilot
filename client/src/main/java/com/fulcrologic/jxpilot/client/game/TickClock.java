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
 * restart).
 * <p>
 * The estimate the renderer reads ({@link #serverTick}) never jumps on a smoothed sample: it slews toward
 * the smoothed offset at most {@link #SLEW} ticks per tick of local time, so a correction changes the
 * apparent speed of everything by a few percent for a moment instead of displacing it in one frame
 * (at 12 Hz a 0.014-tick jump moves a fast ship 1-2 px at once). The first sample and resyncs still jump,
 * and so does a shown offset more than {@link #SNAP_TICKS} from the smoothed one (F7).
 * <p>
 * F7/R4: the server's rate is estimated from the arrivals (least squares over the last {@link #RATE_WINDOW}
 * accepted samples spanning at least {@link #RATE_MIN_SPAN_SECONDS}), so a server running a few percent slow
 * or fast is followed instead of drifting until a resync. Local time is counted from the first sample.
 * Pure, allocation free.
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
    /** Largest change of the shown offset per tick of local time (a 3% rate change). */
    public static final double SLEW = 0.03;
    /** Time constant (ticks) of the shown offset's approach to the smoothed offset, below the SLEW cap. */
    public static final double SLEW_TICKS = 2;

    /** Samples kept for the rate estimate (about 4.5 s at 14 Hz). */
    public static final int RATE_WINDOW = 64;
    /** Samples needed before the rate is estimated. */
    public static final int RATE_MIN_SAMPLES = 16;
    /** Local time (s) the rate samples must span before the rate is estimated. */
    public static final double RATE_MIN_SPAN_SECONDS = 1.0;
    /** The estimated rate stays within [1/RATE_LIMIT, RATE_LIMIT] of the nominal one. */
    public static final double RATE_LIMIT = 1.25;
    /** F7: the shown offset snaps (instead of slewing) when it is this far (ticks) from the smoothed one. */
    public static final double SNAP_TICKS = 0.5;

    private double simHz = 60;
    private boolean synced;
    private double offset;
    /** Server ticks per local tick (F7/R4: a server running slow or fast); 1 = nominal. */
    private double rate = 1;
    private final double[] sampleTick = new double[RATE_WINDOW];
    private final double[] sampleLocal = new double[RATE_WINDOW];
    private int samples;
    private int sampleHead;
    private double anchor;
    private int behind;
    /** Offset {@link #serverTick} uses: slews toward {@link #offset}. */
    private double shown;
    private boolean jump = true;
    private double lastLocal;

    /** Set the simulation rate (from WELCOME) and forget the estimate. */
    public void reset(double simHz) {
        this.simHz = simHz;
        synced = false;
        offset = 0;
        behind = 0;
        shown = 0;
        jump = true;
        rate = 1;
        samples = 0;
        sampleHead = 0;
    }

    /** Server ticks per local tick at the nominal rate (1 until estimated). */
    public double rate() {
        return rate;
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
        double local = localTicks(nowNanos);
        if (!synced) {
            anchor = local;
        }
        double l = local - anchor;
        double s = tick - rate * l;
        double d = s - offset;
        double resync = resyncTicks();
        if (!synced || d > resync) {
            offset = s;
            synced = true;
            behind = 0;
            jump = true;
            samples = 0;
        } else if (d < -resync) {
            if (++behind >= BACKWARD_RESYNC_SAMPLES) {
                offset = s;
                behind = 0;
                jump = true;
                samples = 0;
            }
            return;
        } else {
            offset += d * SMOOTHING;
            behind = 0;
        }
        remember(tick, l);
        estimateRate(l);
    }

    private void remember(int tick, double l) {
        sampleTick[sampleHead] = tick;
        sampleLocal[sampleHead] = l;
        sampleHead = (sampleHead + 1) % RATE_WINDOW;
        samples = Math.min(samples + 1, RATE_WINDOW);
    }

    /**
     * Least-squares slope of the remembered samples; the offsets are re-based so the estimate at {@code l}
     * does not move when the rate changes.
     */
    private void estimateRate(double l) {
        if (samples < RATE_MIN_SAMPLES) {
            return;
        }
        double mt = 0;
        double ml = 0;
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int i = 0; i < samples; i++) {
            mt += sampleTick[i];
            ml += sampleLocal[i];
            lo = Math.min(lo, sampleLocal[i]);
            hi = Math.max(hi, sampleLocal[i]);
        }
        if ((hi - lo) / simHz < RATE_MIN_SPAN_SECONDS) {
            return;
        }
        mt /= samples;
        ml /= samples;
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < samples; i++) {
            double dl = sampleLocal[i] - ml;
            sxy += dl * (sampleTick[i] - mt);
            sxx += dl * dl;
        }
        if (sxx <= 0) {
            return;
        }
        double r = Math.max(1 / RATE_LIMIT, Math.min(RATE_LIMIT, sxy / sxx));
        offset += (rate - r) * l;
        shown += (rate - r) * l;
        rate = r;
    }

    /**
     * Estimated server tick (fractional) at {@code nowNanos}; call it once per rendered frame.
     * <p>
     * BEHAVIOR: 0 before the first sample. After the first sample or a resync the estimate is exactly
     * {@code local ticks + offset}; otherwise the offset it uses moves toward the smoothed offset by
     * (gap * elapsed / {@link #SLEW_TICKS}), at most {@link #SLEW} times the local ticks elapsed since the
     * previous call (none when time went backwards).
     */
    public double serverTick(long nowNanos) {
        if (!synced) {
            return 0;
        }
        double local = localTicks(nowNanos) - anchor;
        if (jump || Math.abs(offset - shown) > SNAP_TICKS) {
            shown = offset;
            jump = false;
        } else {
            double elapsed = Math.max(0, local - lastLocal);
            double max = elapsed * SLEW;
            // approach the offset exponentially (time constant SLEW_TICKS), never faster than SLEW: a bang-bang
            // step of +-SLEW every frame near the target would make the apparent speed chatter by 2 * SLEW
            double step = (offset - shown) * Math.min(1, elapsed / SLEW_TICKS);
            shown += Math.max(-max, Math.min(max, step));
        }
        lastLocal = local;
        return rate * local + shown;
    }

    /** The smoothed offset samples converge to (ticks; {@link #serverTick} slews toward it). */
    public double offset() {
        return offset;
    }
}
