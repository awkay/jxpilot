package com.fulcrologic.jxpilot.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameStatsTest {
    @Test
    void emptyStatsAreZero() {
        FrameStats s = new FrameStats();
        assertAll(
            () -> assertEquals(0.0, s.fps(), "no fps without frames"),
            () -> assertEquals(0.0, s.averageMillis(), "no average without frames"),
            () -> assertEquals(0.0, s.maxMillis(), "no max without frames"),
            () -> assertEquals(0, s.samples(), "no samples"));
    }

    @Test
    void averagesFpsAndTracksTheLongestFrame() {
        FrameStats s = new FrameStats();
        s.record(10_000_000, 0);
        s.record(20_000_000, 0);
        s.record(30_000_000, 0);
        assertAll(
            () -> assertEquals(3, s.samples(), "three samples"),
            () -> assertEquals(20.0, s.averageMillis(), 1e-9, "average 20 ms"),
            () -> assertEquals(50.0, s.fps(), 1e-9, "3 frames in 60 ms is 50 fps"),
            () -> assertEquals(30.0, s.maxMillis(), 1e-9, "longest frame 30 ms"),
            () -> assertEquals(3, s.totalFrames(), "three frames total"));
    }

    @Test
    void zeroDurationFramesGiveZeroFpsRatherThanInfinity() {
        FrameStats s = new FrameStats();
        s.record(0, 0);
        assertEquals(0.0, s.fps(), "zero total time reports 0 fps");
    }

    @Test
    void negativeDurationsCountAsZero() {
        FrameStats s = new FrameStats();
        s.record(-5_000_000, 0);
        s.record(10_000_000, 0);
        assertAll(
            () -> assertEquals(5.0, s.averageMillis(), 1e-9, "negative clamped to 0 in the average"),
            () -> assertEquals(10.0, s.maxMillis(), 1e-9, "max ignores the clamped frame"));
    }

    @Test
    void windowReplacesTheOldestFrame() {
        FrameStats s = new FrameStats();
        s.record(100_000_000, 0);
        for (int i = 0; i < FrameStats.WINDOW; i++) {
            s.record(10_000_000, 0);
        }
        assertAll(
            () -> assertEquals(FrameStats.WINDOW, s.samples(), "samples capped at the window"),
            () -> assertEquals(10.0, s.averageMillis(), 1e-9, "the 100 ms frame left the average"),
            () -> assertEquals(10.0, s.maxMillis(), 1e-9, "and the max"),
            () -> assertEquals(FrameStats.WINDOW + 1, s.totalFrames(), "total keeps counting"));
    }

    @Test
    void windowIsNotYetFullJustBelowCapacity() {
        FrameStats s = new FrameStats();
        s.record(100_000_000, 0);
        for (int i = 0; i < FrameStats.WINDOW - 1; i++) {
            s.record(10_000_000, 0);
        }
        assertAll(
            () -> assertEquals(FrameStats.WINDOW, s.samples(), "exactly full"),
            () -> assertEquals(100.0, s.maxMillis(), 1e-9, "the first frame is still in the window"));
    }

    @Test
    void wholeRunMeansCoverEveryFrameNotJustTheWindow() {
        FrameStats s = new FrameStats();
        s.record(100_000_000, 40_000_000);
        for (int i = 0; i < FrameStats.WINDOW; i++) {
            s.record(10_000_000, 1_000_000);
        }
        long n = FrameStats.WINDOW + 1;
        assertAll(
            () -> assertEquals((100.0 + 10.0 * FrameStats.WINDOW) / n, s.runMeanMillis(), 1e-9,
                "run mean includes the frame that left the window"),
            () -> assertEquals((40.0 + 1.0 * FrameStats.WINDOW) / n, s.runCpuMeanMillis(), 1e-9,
                "cpu mean averages the cpu times"),
            () -> assertEquals(100.0, s.runMaxMillis(), 1e-9, "run max keeps the 100 ms frame"),
            () -> assertEquals(10.0, s.maxMillis(), 1e-9, "while the window max has dropped it"));
    }

    @Test
    void emptyRunStatsAreZero() {
        FrameStats s = new FrameStats();
        assertAll(
            () -> assertEquals(0.0, s.runMeanMillis(), "no run mean"),
            () -> assertEquals(0.0, s.runCpuMeanMillis(), "no cpu mean"),
            () -> assertEquals(0.0, s.p99Millis(), "no p99"));
    }

    @Test
    void negativeCpuTimeCountsAsZero() {
        FrameStats s = new FrameStats();
        s.record(10_000_000, -4_000_000);
        s.record(10_000_000, 4_000_000);
        assertEquals(2.0, s.runCpuMeanMillis(), 1e-9, "the negative cpu time adds nothing");
    }

    @Test
    void p99IsTheUpperBinEdgeWhereNinetyNinePercentAreCounted() {
        FrameStats s = new FrameStats();
        for (int i = 0; i < 99; i++) {
            s.record(1_000_000, 0); // bin 100: [1.00, 1.01) ms
        }
        s.record(5_000_000, 0);
        FrameStats t = new FrameStats();
        for (int i = 0; i < 98; i++) {
            t.record(1_000_000, 0);
        }
        t.record(5_000_000, 0);
        t.record(5_000_000, 0);
        assertAll(
            () -> assertEquals(1.01, s.p99Millis(), 1e-9, "99 of 100 frames at 1 ms: p99 is that bin's top"),
            () -> assertEquals(5.01, t.p99Millis(), 1e-9, "98 of 100: the 99th frame is a 5 ms one"));
    }

    @Test
    void p99RoundsTheFrameCountUp() {
        FrameStats s = new FrameStats();
        s.record(1_000_000, 0);
        s.record(2_000_000, 0);
        assertEquals(2.01, s.p99Millis(), 1e-9, "ceil(0.99 * 2) = 2 frames must be counted");
    }

    @Test
    void framesPastTheHistogramReportTheRunMax() {
        FrameStats s = new FrameStats();
        s.record(FrameStats.HISTOGRAM_NANOS - 1, 0);
        FrameStats t = new FrameStats();
        t.record(FrameStats.HISTOGRAM_NANOS + 23_000_000, 0);
        assertAll(
            () -> assertEquals(100.0, s.p99Millis(), 1e-9, "just under the limit is the last regular bin"),
            () -> assertEquals(123.0, t.p99Millis(), 1e-9, "overflow reports the run max"));
    }
}
