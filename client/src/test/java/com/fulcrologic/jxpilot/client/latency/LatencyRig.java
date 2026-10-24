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
package com.fulcrologic.jxpilot.client.latency;

import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.net.ClientSim;
import com.fulcrologic.jxpilot.client.net.EmulatedTransport;
import com.fulcrologic.jxpilot.common.net.link.LinkEmulator;
import com.fulcrologic.jxpilot.common.net.link.LinkProfile;
import com.fulcrologic.jxpilot.common.net.link.VirtualClock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.LongUnaryOperator;

/**
 * One headless game in virtual time: the real server ({@link VirtualServer}) and the real client pipeline
 * ({@link ClientSim}: connection, world, input) joined by a {@link LinkEmulator} (an {@link EmulatedTransport}
 * over a {@link LoopbackPipe}). Events run in time order: uplink deliveries reach the server when due, the
 * server ticks at its frame rate, and the client runs render frames at {@link #FRAME_HZ} (receive, update the
 * drawn world, send input). Same seeds, same inputs: the same game, frame for frame.
 */
final class LatencyRig {
    /** Render rate (docs/latency-fixes.md §7: frames sampled at 120 Hz). */
    static final int FRAME_HZ = 120;
    static final long SEC = 1_000_000_000L;
    /** Virtual time of the first event (any value works; not 0, so no code mistakes it for "unset"). */
    static final long START = 10 * SEC;

    /** Called after every client frame. */
    interface FrameListener {
        void frame(LatencyRig rig, long now);
    }

    /** Called after every server tick. */
    interface TickListener {
        void tick(LatencyRig rig, long now);
    }

    final VirtualClock clock = new VirtualClock(START);
    final LinkEmulator link;
    final LoopbackPipe pipe;
    final EmulatedTransport transport;
    final VirtualServer server;
    final ClientSim client;
    private final long tickPhase;
    private long ticks;
    private long frames;
    private long nextTick;
    private long nextFrame;
    private long keys;
    private LongUnaryOperator keyScript = t -> 0L;
    FrameListener onFrame = (r, t) -> { };
    TickListener onTick = (r, t) -> { };
    private boolean stop;

    /**
     * @param serverArgs  {@code --map FILE} and server options
     * @param up          uplink impairments (client to server)
     * @param down        downlink impairments
     * @param seed        seeds the server world, the client and the link
     * @param tickPhase   offset of the server's tick grid from the render grid (nanoseconds)
     */
    LatencyRig(String[] serverArgs, LinkProfile up, LinkProfile down, long seed, long tickPhase) {
        link = new LinkEmulator(clock, up, down, seed);
        pipe = new LoopbackPipe(clock);
        server = new VirtualServer(serverArgs, (int) seed, pipe, clock);
        pipe.connect(server.net);
        transport = new EmulatedTransport(pipe, link);
        client = new ClientSim(transport, "virtual", "Pilot", com.fulcrologic.jxpilot.common.net.Protocol.NO_TEAM,
            (int) (seed * 31 + 7));
        client.setKeys(f -> keys);
        this.tickPhase = tickPhase;
        nextTick = START + tickPhase;
        nextFrame = START;
        client.start(START);
    }

    /** Keys held at each virtual time (absolute nanoseconds); polled once per render frame, as the client does. */
    void setKeys(LongUnaryOperator script) {
        keyScript = script;
    }

    ClientWorld world() {
        return client.game().world();
    }

    long now() {
        return clock.getAsLong();
    }

    long tickNanos() {
        return SEC / server.hz;
    }

    /** The virtual time of server tick {@code k} (counted from 0). */
    long tickTime(long k) {
        return START + tickPhase + Math.round(k * 1e9 / server.hz);
    }

    /** The virtual time of render frame {@code j} (counted from 0). */
    static long frameTime(long j) {
        return START + Math.round(j * 1e9 / FRAME_HZ);
    }

    long frames() {
        return frames;
    }

    /** Make the running {@link #runUntil} return after the current event (a listener calls it). */
    void stop() {
        stop = true;
    }

    /** Run every event up to and including time {@code end}, or until a listener calls {@link #stop()}. */
    void runUntil(long end) {
        stop = false;
        try {
            while (!stop) {
                long up = link.up().nextDue();
                long next = Math.min(up, Math.min(nextTick, nextFrame));
                if (next > end) {
                    clock.advanceTo(end);
                    return;
                }
                clock.advanceTo(next);
                if (up <= next) {
                    transport.pump();
                } else if (nextTick <= next) {
                    server.tick(next);
                    transport.pump();
                    ticks++;
                    nextTick = tickTime(ticks);
                    onTick.tick(this, next);
                } else {
                    keys = keyScript.applyAsLong(next);
                    client.step(next);
                    frames++;
                    nextFrame = frameTime(frames);
                    onFrame.frame(this, next);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Run until the own ship is in play (the world draws it and it is alive), at most until {@code limit}.
     *
     * @return the first frame time it was, or -1
     */
    long runUntilPlaying(long limit) {
        while (now() < limit) {
            runUntil(Math.min(limit, nextFrame));
            ClientWorld w = world();
            if (w.hasSelf() && w.selfPlaying()) {
                return now();
            }
        }
        return -1;
    }
}
