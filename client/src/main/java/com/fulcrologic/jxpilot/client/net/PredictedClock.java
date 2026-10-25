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

import com.fulcrologic.jxpilot.client.game.TickClock;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;

/**
 * The predicted local tick (docs/latency-fixes.md §4.3, §4.5): the tick this client's input is for, running ahead
 * of the server by the input lead so each entry reaches the server's {@code InputBuffer} before its tick runs.
 * <p>
 * It is its own clock, advanced at the nominal simHz times a time dilation of at most ±{@link #DILATION}; the
 * server's drift (ppm) is well inside that. It does not follow {@link TickClock}'s rate estimate: a change of RTT
 * shifts the arrival times, which the rate regression reads as a slope for seconds, and that would pull the lead
 * away exactly when the slack loop is correcting it. Only the server's feedback steers it:
 * <ul>
 *   <li>the first RTT sample sets the lead to {@code ceil((RTT/2 + 30 ms)/T) + 1} ticks over the server's
 *   present (the arrival-based estimate plus RTT/2); before it the RTT counts as 0;</li>
 *   <li>the snapshot header's {@code inputSlack} is the smallest margin by which entries arrived over the server's
 *   last second, so it lags every change the clock makes. The clock keeps its own adjustment (jumps, settles and
 *   dilation, in ticks) at the time each final entry went out ({@link #sent}) and estimates the present slack as
 *   the report plus the adjustment since the smallest one among the entries the report can cover: those for ticks
 *   the server had run by the report's snapshot (they had all arrived) within its one-second window. That is exact
 *   on a steady link (whichever entry made the minimum, it went out with at least that adjustment), after a move
 *   either way (entries still in flight are left out, so a move down does not read as slack until it arrives), and
 *   it lets the steering act at once instead of waiting a second per correction;</li>
 *   <li>the estimate is held near {@code 0.25 tick + 1σ} ({@link #targetSlack}), σ the spread of the reports
 *   themselves (the uplink's variation of the window minimum, the clock's own moves taken out, a robust running
 *   deviation, {@link #slackSpread}): above it the clock runs slow, below it fast, proportionally and up to ±3% (no change
 *   within {@link #DEADBAND}). The report is already the uplink's minimum margin, so no downlink jitter is added:
 *   every tick of slack is a tick the server applies the input later (a shield comes up later);</li>
 *   <li>a starvation report (the header's starvation counter changed) jumps the clock up at once, by the
 *   estimate's shortfall below the target, at least {@link #MIN_JUMP} (one tick without an estimate); further
 *   reports are ignored until snapshots reach the first tick sent at a boundary after the jump (they describe
 *   entries sent before it);</li>
 *   <li>once per session, after {@code simHz} slack reports, the clock settles: an estimate above the target by
 *   more than {@link #DEADBAND} is removed at once (down to half the deadband above the target), as the initial
 *   lead is deliberately generous and draining it at 3% would take seconds.</li>
 * </ul>
 * Guard rails: a lead below 0 ticks over the server's present (the server estimate jumped forward) moves the
 * clock up to the initial lead; a lead above {@code simHz - 1} (the server would drop the entries as too far ahead)
 * sets it back to the initial lead, the one case where it runs backwards. Pure and allocation free (time is a
 * parameter).
 */
public final class PredictedClock {
    /** Largest time dilation either way (the slew limit {@link TickClock} uses). */
    public static final double DILATION = 0.03;
    /** Slack (ticks) the steering aims for, before the spread margin. */
    public static final double BASE_SLACK = 0.25;
    /** Standard deviations of the slack reports' spread added to {@link #BASE_SLACK}. */
    public static final double SPREAD_SIGMAS = 1;
    /** Weight of a report in the spread's running mean and mean deviation (about 2 s of reports at 14 Hz). */
    public static final double SPREAD_ALPHA = 1.0 / 32;
    /** Largest deviation (ticks) one report adds to the spread: a late copy's deep margin is an outlier, not jitter. */
    public static final double SPREAD_CLAMP = 1;
    /** Standard deviations per mean absolute deviation of a normal distribution (sqrt(pi/2)). */
    static final double SIGMA_PER_MAD = 1.2533141373155003;
    /** Smallest starvation jump (ticks) when the estimate is not short of the target (a loss, a spike). */
    public static final double MIN_JUMP = 0.25;
    /** Safety margin of the initial lead over half the RTT. */
    public static final long INITIAL_MARGIN_NANOS = 30_000_000L;
    /** Slack error (ticks) within which the dilation is 0. */
    public static final double DEADBAND = 0.125;
    /** Slack error (ticks) at which the dilation is full. */
    public static final double FULL_ERROR = 0.5;
    /** Final entries whose adjustment is remembered (a power of two, over a second at any rate). */
    public static final int SENT_RING = 128;

    private final TickClock server = new TickClock();
    private double simHz = 60;
    private double tickNanos = 1e9 / 60;
    private boolean running;
    private double tick;
    private long lastNanos;
    private double dilation;
    /** Jumps, settles and dilation so far (ticks): the clock's own share of the slack. */
    private double adjust;
    private final int[] sentTick = new int[SENT_RING];
    private final double[] sentAdjust = new double[SENT_RING];
    private int sentCount;
    private double rttNanos;
    private boolean haveRtt;
    private int guardTick;
    private boolean guard;
    private long jumps;
    private long resyncs;
    private boolean settled;
    private int slackReports;
    private double settledBy;
    private double lastSlack = Double.NaN;
    private double estimate = Double.NaN;
    /** Running mean and mean absolute deviation of the reports with the clock's own moves taken out (ticks). */
    private boolean haveSpread;
    private double spreadMean;
    private double spreadMad;

    /** A new game (WELCOME) or session: forget everything but the rate. */
    public void reset(double simHz) {
        this.simHz = simHz > 0 ? simHz : 60;
        tickNanos = 1e9 / this.simHz;
        server.reset(this.simHz);
        running = false;
        dilation = 0;
        adjust = 0;
        sentCount = 0;
        haveRtt = false;
        rttNanos = 0;
        guard = false;
        jumps = 0;
        resyncs = 0;
        settled = false;
        slackReports = 0;
        settledBy = 0;
        lastSlack = Double.NaN;
        estimate = Double.NaN;
        haveSpread = false;
        spreadMean = 0;
        spreadMad = 0;
    }

    public double simHz() { return simHz; }

    /** BEHAVIOR: a snapshot's tick and arrival time feed the server estimate (one per newer tick). */
    public void snapshot(int tick, long nowNanos) {
        server.sample(tick, nowNanos);
    }

    /** Whether the clock can run: a snapshot has been seen. */
    public boolean ready() {
        return server.synced();
    }

    /**
     * A smoothed RTT (nanoseconds).
     * <p>
     * BEHAVIOR: the first one after a reset re-initializes a running clock from it (only ever forward, counted as
     * an adjustment); later ones only change the RTT used for {@link #leadTicks} and the guard rails; negative and
     * NaN values are ignored.
     */
    public void rtt(double nanos, long nowNanos) {
        if (!(nanos >= 0)) {
            return;
        }
        rttNanos = nanos;
        if (!haveRtt) {
            haveRtt = true;
            if (running) {
                advance(nowNanos);
                move(Math.max(0, initial(nowNanos) - tick));
            }
        }
    }

    /**
     * The final entry for {@code tick} went out now: remember the clock's adjustment with it.
     * <p>
     * BEHAVIOR: kept for the last {@link #SENT_RING} entries.
     */
    public void sent(int tick, long nowNanos) {
        advance(nowNanos);
        int s = sentCount & (SENT_RING - 1);
        sentTick[s] = tick;
        sentAdjust[s] = adjust;
        sentCount++;
    }

    /** {@code ceil((RTT/2 + 30 ms)/T) + 1}: the initial lead in ticks over the server's present. */
    public int initialLead() {
        return (int) Math.ceil((rttNanos / 2 + INITIAL_MARGIN_NANOS) / tickNanos) + 1;
    }

    private double present(long nowNanos) {
        return server.serverTick(nowNanos) + rttNanos / 2 / tickNanos;
    }

    private double initial(long nowNanos) {
        return present(nowNanos) + initialLead();
    }

    /** The slack the steering aims for: {@link #BASE_SLACK} + {@link #SPREAD_SIGMAS} × {@link #slackSpread} (ticks). */
    public double targetSlack() {
        return BASE_SLACK + SPREAD_SIGMAS * slackSpread();
    }

    /**
     * The spread (ticks) of the slack reports, the clock's own moves taken out: their running mean absolute
     * deviation as a normal standard deviation ({@link #SIGMA_PER_MAD}); 0 before a report.
     */
    public double slackSpread() {
        return SIGMA_PER_MAD * spreadMad;
    }

    /**
     * One report's slack with the clock's moves taken out ({@code estimate - adjust}: what the margin would be had
     * the clock never moved), into the running spread.
     * <p>
     * BEHAVIOR: the first sample sets the mean (deviation 0); each later one, its distance from the mean clamped
     * to {@link #SPREAD_CLAMP}, moves the mean and the mean deviation by {@link #SPREAD_ALPHA} of the difference.
     */
    private void spread(double base) {
        if (!haveSpread) {
            haveSpread = true;
            spreadMean = base;
            spreadMad = 0;
            return;
        }
        double d = Math.clamp(base - spreadMean, -SPREAD_CLAMP, SPREAD_CLAMP);
        spreadMean += SPREAD_ALPHA * d;
        spreadMad += SPREAD_ALPHA * (Math.abs(d) - spreadMad);
    }

    /**
     * The present slack (ticks) estimated from a report taken at snapshot {@code snapshotTick}: the report plus the
     * adjustment made since the smallest adjustment among the final entries for ticks in
     * {@code (snapshotTick - simHz, snapshotTick]} (the ones the server's window holds and had certainly arrived).
     * <p>
     * BEHAVIOR: without such entries it is the report itself; entries for later ticks (possibly still in flight)
     * do not count.
     */
    double estimate(int snapshotTick, double slack) {
        double least = Double.MAX_VALUE;
        int from = snapshotTick - (int) Math.ceil(simHz);
        int n = Math.min(sentCount, SENT_RING);
        for (int i = 1; i <= n; i++) {
            int s = (sentCount - i) & (SENT_RING - 1);
            if (sentTick[s] - from <= 0) {
                break;
            }
            if (sentTick[s] - snapshotTick > 0) {
                continue;
            }
            least = Math.min(least, sentAdjust[s]);
        }
        return least == Double.MAX_VALUE ? slack : slack + adjust - least;
    }

    /**
     * The server's feedback from a snapshot header (part 0 of a newer tick).
     * <p>
     * BEHAVIOR: a slack report (not {@link SnapshotWriter#NO_SLACK}) gives the estimate ({@link #estimate}) and a
     * sample of the spread ({@link #slackSpread}).
     * BEHAVIOR: a starvation report jumps the clock up at once by max({@link #MIN_JUMP}, target - estimate) (1
     * without an estimate), unless the snapshot is older than the guard tick of the previous jump; the guard is the
     * first tick after the one the clock reached with the jump.
     * BEHAVIOR: otherwise, once the session has {@code simHz} slack reports, the clock settles once (see the class
     * comment); every other estimate sets the dilation: 0 within {@link #DEADBAND} of the target, else
     * -(error / {@link #FULL_ERROR}) × {@link #DILATION}, clamped to ±{@link #DILATION} (too much slack runs slow).
     *
     * @param snapshotTick the snapshot's tick
     * @param slack16      the header's inputSlack (1/16 tick) or NO_SLACK
     * @param starved      the header's starvation counter changed
     */
    public void feedback(int snapshotTick, int slack16, boolean starved, long nowNanos) {
        boolean report = slack16 != SnapshotWriter.NO_SLACK;
        if (report) {
            lastSlack = slack16 / 16.0;
        }
        if (!running) {
            return;
        }
        advance(nowNanos);
        if (report) {
            estimate = estimate(snapshotTick, lastSlack);
            spread(estimate - adjust);
        }
        double target = targetSlack();
        if (guard && snapshotTick - guardTick >= 0) {
            guard = false;
        }
        if (starved && !guard) {
            double by = report ? Math.max(MIN_JUMP, target - estimate) : 1;
            move(by);
            jumps++;
            guard = true;
            guardTick = (int) Math.floor(tick) + 1;
            dilation = 0;
            return;
        }
        if (!report) {
            return;
        }
        double e = estimate - target;
        if (!settled && ++slackReports >= simHz) {
            settled = true;
            if (e > DEADBAND) {
                double by = e - DEADBAND / 2;
                move(-by);
                settledBy = by;
                dilation = 0;
                return;
            }
        }
        dilation = Math.abs(e) < DEADBAND ? 0 : -Math.clamp(e / FULL_ERROR * DILATION, -DILATION, DILATION);
    }

    private void move(double ticks) {
        tick += ticks;
        adjust += ticks;
    }

    /**
     * The predicted tick (fractional) at {@code nowNanos}.
     * <p>
     * BEHAVIOR: NaN before the first snapshot. The first call after it starts the clock at the initial lead over
     * the server's present; later calls advance it by the elapsed local ticks × (1 + dilation)
     * (never backwards), then apply the guard rails.
     */
    public double tick(long nowNanos) {
        if (!server.synced()) {
            return Double.NaN;
        }
        if (!running) {
            running = true;
            lastNanos = nowNanos;
            tick = initial(nowNanos);
            return tick;
        }
        advance(nowNanos);
        double lead = tick - present(nowNanos);
        if (lead < 0 || lead > simHz - 1) {
            double t = initial(nowNanos);
            if (t > tick || lead > simHz - 1) {
                move(t - tick);
                guard = false;
                resyncs++;
            }
        }
        return tick;
    }

    private void advance(long nowNanos) {
        long dt = nowNanos - lastNanos;
        if (dt > 0) {
            double nominal = dt / tickNanos;
            tick += nominal * (1 + dilation);
            adjust += nominal * dilation;
            lastNanos = nowNanos;
        }
    }

    /** The lead (ticks) over the server's present at the last {@link #tick} (NaN before it runs). */
    public double leadTicks(long nowNanos) {
        return running ? tick - present(nowNanos) : Double.NaN;
    }

    /** The current time dilation (-DILATION..DILATION). */
    public double dilation() { return dilation; }

    /** The last slack report (ticks), NaN before one. */
    public double lastSlack() { return lastSlack; }

    /** The last slack estimate (ticks, see {@link #estimate}), NaN before one. */
    public double slackEstimate() { return estimate; }

    /** The clock's own adjustment so far (ticks): jumps, settles, guard rails and dilation. */
    public double adjustment() { return adjust; }

    /** Starvation jumps since the reset. */
    public long jumps() { return jumps; }

    /** Guard-rail re-initializations since the reset. */
    public long resyncs() { return resyncs; }

    /** Whether the one-time settle has run, and by how many ticks it moved the clock down (0: not needed). */
    public boolean settled() { return settled; }

    public double settledBy() { return settledBy; }

    /** Whether an RTT sample has arrived since the reset. */
    public boolean haveRtt() { return haveRtt; }
}
