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
        s.record(10_000_000);
        s.record(20_000_000);
        s.record(30_000_000);
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
        s.record(0);
        assertEquals(0.0, s.fps(), "zero total time reports 0 fps");
    }

    @Test
    void negativeDurationsCountAsZero() {
        FrameStats s = new FrameStats();
        s.record(-5_000_000);
        s.record(10_000_000);
        assertAll(
            () -> assertEquals(5.0, s.averageMillis(), 1e-9, "negative clamped to 0 in the average"),
            () -> assertEquals(10.0, s.maxMillis(), 1e-9, "max ignores the clamped frame"));
    }

    @Test
    void windowReplacesTheOldestFrame() {
        FrameStats s = new FrameStats();
        s.record(100_000_000);
        for (int i = 0; i < FrameStats.WINDOW; i++) {
            s.record(10_000_000);
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
        s.record(100_000_000);
        for (int i = 0; i < FrameStats.WINDOW - 1; i++) {
            s.record(10_000_000);
        }
        assertAll(
            () -> assertEquals(FrameStats.WINDOW, s.samples(), "exactly full"),
            () -> assertEquals(100.0, s.maxMillis(), 1e-9, "the first frame is still in the window"));
    }
}
