package com.fulcrologic.jxpilot.client.render;

/**
 * Rolling frame-time statistics over the last {@link #WINDOW} frames. Times are fed in by the
 * render loop (nanoseconds from an injected clock); nothing here reads a clock.
 */
public final class FrameStats {
    /** Number of frames averaged. */
    public static final int WINDOW = 120;

    private final long[] frames = new long[WINDOW];
    private int next;
    private int filled;
    private long sum;
    private long total;

    /**
     * Record one frame's duration.
     * <p>
     * BEHAVIOR: once the window is full the oldest frame is replaced; negative durations count as 0.
     */
    public void record(long frameNanos) {
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
    }

    /** Frames in the window. */
    public int samples() { return filled; }

    /** Frames recorded since creation. */
    public long totalFrames() { return total; }

    /** Average frames per second over the window (0 with no samples or zero time). */
    public double fps() {
        return sum == 0 ? 0 : filled * 1e9 / sum;
    }

    /** Average frame time in milliseconds (0 with no samples). */
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
}
