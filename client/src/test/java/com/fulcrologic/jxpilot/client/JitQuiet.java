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
package com.fulcrologic.jxpilot.client;

import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Warm-up helper for allocation tests: runs chunks of frames until the JIT has stopped compiling.
 * <p>
 * Why: HotSpot's {@code CompileBroker::compile_method} resolves the class's unresolved String constants
 * ({@code resolve_string_constants}) on the Java thread that queues a C2 compile. So the first C2 request for a
 * method of a class allocates those Strings on the measured thread, in a frame of otherwise allocation-free code.
 * Total compile time (CompilationMXBean, whole milliseconds) misses the many sub-millisecond compiles, so it
 * declared the JIT done while C2 was still queueing methods. This counts {@code jdk.Compilation} events (JFR,
 * threshold 0) instead and stops once {@code quietChunks} chunks in a row compiled nothing. JFR is closed before
 * the caller measures.
 */
public final class JitQuiet implements AutoCloseable {
    private final RecordingStream stream = new RecordingStream();
    private final AtomicLong compiles = new AtomicLong();
    private final AtomicLong flushes = new AtomicLong();

    public JitQuiet() {
        stream.enable("jdk.Compilation").withThreshold(Duration.ZERO);
        stream.onEvent("jdk.Compilation", e -> compiles.incrementAndGet());
        stream.onFlush(flushes::incrementAndGet);
        stream.startAsync();
    }

    /**
     * Run {@code chunk} frames at a time until {@code quietChunks} consecutive chunks compiled nothing, at most
     * {@code maxFrames} frames (at least {@code minFrames}).
     *
     * @return the frames run
     */
    public int warm(Runnable frame, int chunk, int minFrames, int maxFrames, int quietChunks) throws InterruptedException {
        int frames = 0;
        int quiet = 0;
        while (frames < maxFrames) {
            long before = compiles.get();
            for (int i = 0; i < chunk; i++) {
                frame.run();
            }
            frames += chunk;
            settle();
            quiet = compiles.get() == before ? quiet + 1 : 0;
            if (quiet >= quietChunks && frames >= minFrames) {
                break;
            }
        }
        return frames;
    }

    /** Compilations seen so far. */
    public long compiles() {
        return compiles.get();
    }

    /** Wait for two JFR flushes, so the events of the chunk just run have been delivered (at most 5 s). */
    private void settle() throws InterruptedException {
        long target = flushes.get() + 2;
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (flushes.get() < target && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    @Override
    public void close() {
        stream.close();
    }
}
