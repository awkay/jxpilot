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
package com.fulcrologic.jxpilot.client.render;

/**
 * Frame-time statistics. Times are fed in by the render loop (nanoseconds from an injected clock);
 * nothing here reads a clock. Allocation-free.
 * <ul>
 *   <li>Rolling: average, fps and max over the last {@link #WINDOW} frames (for the on-screen line).</li>
 *   <li>Whole run: mean frame time, mean CPU time (the render thread's work before the buffer swap)
 *   and the 99th percentile frame time from a histogram of {@link #BIN_NANOS} bins up to
 *   {@link #HISTOGRAM_NANOS}; longer frames land in an overflow bin reported as the run's max.</li>
 * </ul>
 */
public final class FrameStats {
    /** Number of frames averaged. */
    public static final int WINDOW = 120;
    /** Histogram bin width (10 microseconds). */
    public static final long BIN_NANOS = 10_000;
    /** Frames at least this long go to the overflow bin (100 ms). */
    public static final long HISTOGRAM_NANOS = 100_000_000;
    static final int BINS = (int) (HISTOGRAM_NANOS / BIN_NANOS);

    private final long[] frames = new long[WINDOW];
    private int next;
    private int filled;
    private long sum;
    private long total;

    private final int[] histogram = new int[BINS + 1];
    private long runSum;
    private long runCpuSum;
    private long runMax;

    /**
     * Record one frame.
     * <p>
     * BEHAVIOR: once the window is full the oldest frame is replaced; negative durations count as 0.
     * BEHAVIOR: every frame also feeds the whole-run sums, max and histogram.
     *
     * @param frameNanos time since the previous frame started
     * @param cpuNanos   render-thread time spent on this frame before swapping buffers
     */
    public void record(long frameNanos, long cpuNanos) {
        long d = Math.max(0, frameNanos);
        if (filled == WINDOW) {
            sum -= frames[next];
        } else {
            filled++;
        }
        frames[next] = d;
        sum += d;
        next = (next + 1) % WINDOW;
        total++;
        runSum += d;
        runCpuSum += Math.max(0, cpuNanos);
        runMax = Math.max(runMax, d);
        histogram[(int) Math.min(BINS, d / BIN_NANOS)]++;
    }

    /** Frames in the window. */
    public int samples() { return filled; }

    /** Frames recorded since creation. */
    public long totalFrames() { return total; }

    /** Average frames per second over the window (0 with no samples or zero time). */
    public double fps() {
        return sum == 0 ? 0 : filled * 1e9 / sum;
    }

    /** Average frame time in milliseconds over the window (0 with no samples). */
    public double averageMillis() {
        return filled == 0 ? 0 : sum / 1e6 / filled;
    }

    /** Longest frame in the window, milliseconds. */
    public double maxMillis() {
        long m = 0;
        for (int i = 0; i < filled; i++) {
            m = Math.max(m, frames[i]);
        }
        return m / 1e6;
    }

    /** Mean frame time over the whole run, milliseconds (0 with no frames). */
    public double runMeanMillis() {
        return total == 0 ? 0 : runSum / 1e6 / total;
    }

    /** Mean pre-swap CPU time over the whole run, milliseconds (0 with no frames). */
    public double runCpuMeanMillis() {
        return total == 0 ? 0 : runCpuSum / 1e6 / total;
    }

    /** Longest frame of the whole run, milliseconds. */
    public double runMaxMillis() {
        return runMax / 1e6;
    }

    /**
     * 99th percentile frame time of the whole run, milliseconds.
     * <p>
     * BEHAVIOR: the upper edge of the first bin at which at least 99% of frames ({@code ceil(0.99 n)})
     * are counted; the overflow bin reports the run max; 0 with no frames.
     */
    public double p99Millis() {
        if (total == 0) {
            return 0;
        }
        long need = (total * 99 + 99) / 100;
        long seen = 0;
        for (int i = 0; i < BINS; i++) {
            seen += histogram[i];
            if (seen >= need) {
                return (i + 1) * BIN_NANOS / 1e6;
            }
        }
        return runMaxMillis();
    }
}
