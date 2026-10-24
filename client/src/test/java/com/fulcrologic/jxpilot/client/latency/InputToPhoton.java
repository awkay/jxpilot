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
import com.fulcrologic.jxpilot.common.net.ControlKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Input-to-photon (docs/latency-fixes.md §7.3): a key goes down at t0 and stays down; the latency is the time
 * from t0 to the first rendered frame (120 Hz) whose drawn own ship comes from server state that the key
 * changed. TURN_LEFT changes the heading the server sends ({@code dir}, RES 128 steps), THRUST the velocity.
 * <p>
 * Method: a baseline game without the key, and for each trial the same game (same seeds, so identical up to
 * t0) with the key. The first server tick after t0 whose sent heading (turn) or velocity (thrust) differs from
 * the baseline's is the tick the key reached; the I2P frame is the first frame drawing a SELF of that tick or
 * later. This uses the server's exact state for "changed", so it does not mistake the link's timing for an
 * effect of the key: an extra INPUT packet shifts the emulator's random stream for the packets behind it. As
 * checks, every frame before t0 must be bit-identical to the baseline ({@link Result#preT0Mismatches}) and the
 * drawn heading or velocity at the I2P frame must differ from the baseline frame
 * ({@link Result#invisible}).
 * <p>
 * Trials alternate turn and thrust and start {@link #SPACING_MS} apart, which walks t0 across the tick and
 * frame phases (263 ms is 3.68 ticks at 14 Hz and 31.6 frames at 120 Hz), {@link #BLOCK} per baseline game.
 */
final class InputToPhoton {
    static final long MS = 1_000_000L;
    /** First trial this long after the spawn (the clock has settled; the course's thrust is over). */
    static final long WARMUP_MS = 1500;
    static final long SPACING_MS = 263;
    /** A trial without a departure within this window counts as {@link Outcome#NO_DEPARTURE}. */
    static final long WINDOW_MS = 2000;
    static final long SPAWN_LIMIT = 30 * LatencyRig.SEC;

    /**
     * MEASURED; DEAD_AT_T0: the baseline ship was not in play at t0; DIED: the ship died (crashed) after t0 before
     * the key showed; NO_DEPARTURE: it lived but the key did not show within {@link #WINDOW_MS}.
     */
    enum Outcome { MEASURED, DEAD_AT_T0, DIED, NO_DEPARTURE }

    record Trial(ControlKey key, long t0, double ms, Outcome outcome) { }

    record Result(List<Trial> trials, long preT0Mismatches, int invisible, int spawnMismatches) {
        /** I2P in ms of the measured trials of {@code key} (null: all keys). */
        Stats stats(ControlKey key) {
            double[] v = new double[trials.size()];
            int n = 0;
            for (Trial t : trials) {
                if (t.outcome == Outcome.MEASURED && (key == null || t.key == key)) {
                    v[n++] = t.ms;
                }
            }
            return new Stats(v, n);
        }

        long count(Outcome o) {
            return trials.stream().filter(t -> t.outcome == o).count();
        }
    }

    /** Per-frame drawn own ship of the baseline. */
    static final class Trace {
        final float[] heading;
        final float[] vx;
        final float[] vy;
        final float[] x;
        final float[] y;
        final boolean[] playing;
        int n;

        Trace(int cap) {
            heading = new float[cap];
            vx = new float[cap];
            vy = new float[cap];
            x = new float[cap];
            y = new float[cap];
            playing = new boolean[cap];
        }

        void record(int frame, ClientWorld w) {
            if (frame >= heading.length) {
                return;
            }
            heading[frame] = w.selfHeading();
            vx[frame] = w.selfVx();
            vy[frame] = w.selfVy();
            x[frame] = w.selfX();
            y[frame] = w.selfY();
            playing[frame] = w.hasSelf() && w.selfPlaying();
            n = Math.max(n, frame + 1);
        }

        /** BEHAVIOR: true when frame {@code frame} of {@code w} draws the own ship exactly as this trace did. */
        boolean same(int frame, ClientWorld w) {
            return frame < n && heading[frame] == w.selfHeading() && vx[frame] == w.selfVx() && vy[frame] == w.selfVy()
                && x[frame] == w.selfX() && y[frame] == w.selfY()
                && playing[frame] == (w.hasSelf() && w.selfPlaying());
        }
    }

    private InputToPhoton() {
    }

    static long t0(long spawn, int trial) {
        return spawn + (WARMUP_MS + trial * SPACING_MS) * MS;
    }

    static ControlKey keyOf(int trial) {
        return trial % 2 == 0 ? ControlKey.TURN_LEFT : ControlKey.THRUST;
    }

    /** Trials per baseline game (a block): the last starts 1.5 + 11 * 0.263 = 4.4 s after the spawn. */
    static final int BLOCK = 12;

    /**
     * BEHAVIOR: the seed of block {@code b} of a measurement seeded {@code seed}: block 0 uses the seed itself, so
     * a one-block measurement is the plain seeded game.
     */
    static long blockSeed(long seed, int b) {
        return seed + 7919L * b;
    }

    /**
     * Run {@code trials} trials of {@code course} over {@code scenario}, in blocks of {@link #BLOCK}: each block is
     * its own baseline game (seed {@link #blockSeed}), so no trial waits long after the spawn (courses end in a
     * crash sooner or later) and the blocks sample other tick phases and link draws.
     */
    static Result measure(Course course, Scenario scenario, int trials, long seed) {
        List<Trial> out = new ArrayList<>();
        long[] counts = new long[3];
        for (int b = 0; b * BLOCK < trials; b++) {
            measureBlock(course, scenario, Math.min(BLOCK, trials - b * BLOCK), blockSeed(seed, b), out, counts);
        }
        return new Result(out, counts[0], (int) counts[1], (int) counts[2]);
    }

    /** One baseline game and its trials; adds the trials to {@code out} and the check counts to {@code counts}. */
    private static void measureBlock(Course course, Scenario scenario, int trials, long seed, List<Trial> out,
                                     long[] counts) {
        String[] args = course.serverArgs(0);
        LatencyRig base = new LatencyRig(args, scenario.direction(), scenario.direction(), seed, tickPhase(seed));
        long spawn = base.runUntilPlaying(LatencyRig.START + SPAWN_LIMIT);
        if (spawn < 0) {
            throw new IllegalStateException(course + " " + scenario.label() + ": the ship never spawned");
        }
        ServerTruth baseTruth = new ServerTruth(base.client.connection().playerId());
        long end = t0(spawn, trials - 1) + WINDOW_MS * MS;
        Trace trace = new Trace((int) ((end - LatencyRig.START) / (LatencyRig.SEC / LatencyRig.FRAME_HZ)) + 2);
        base.setKeys(t -> course.keys(t - spawn));
        base.onTick = (r, t) -> baseTruth.record(r.server.world(), r.server.net.currentTick());
        base.onFrame = (r, t) -> trace.record((int) r.frames() - 1, r.world());
        base.runUntil(end);

        for (int i = 0; i < trials; i++) {
            ControlKey key = keyOf(i);
            long t0 = t0(spawn, i);
            int t0Frame = (int) Math.ceil((t0 - LatencyRig.START) * (double) LatencyRig.FRAME_HZ / LatencyRig.SEC);
            if (t0Frame >= trace.n || !trace.playing[t0Frame]) {
                out.add(new Trial(key, t0, Double.NaN, Outcome.DEAD_AT_T0));
                continue;
            }
            LatencyRig rig = new LatencyRig(args, scenario.direction(), scenario.direction(), seed, tickPhase(seed));
            long s = rig.runUntilPlaying(LatencyRig.START + SPAWN_LIMIT);
            if (s != spawn) {
                counts[2]++;
            }
            ServerTruth truth = new ServerTruth(rig.client.connection().playerId());
            long mask = key.mask();
            rig.setKeys(t -> course.keys(t - spawn) | (t >= t0 ? mask : 0));
            int[] changed = {-1};
            long[] found = {-1};
            boolean[] visible = {false};
            boolean[] died = {false};
            rig.onTick = (r, t) -> {
                int tick = r.server.net.currentTick();
                truth.record(r.server.world(), tick);
                if (changed[0] < 0 && t >= t0 && tick <= baseTruth.lastTick()) {
                    boolean differs = key == ControlKey.TURN_LEFT ? truth.headingDiffers(baseTruth, tick)
                        : truth.velocityDiffers(baseTruth, tick);
                    if (differs) {
                        changed[0] = tick;
                    }
                }
            };
            rig.onFrame = (r, t) -> {
                int frame = (int) r.frames() - 1;
                ClientWorld w = r.world();
                if (t < t0) {
                    if (!trace.same(frame, w)) {
                        counts[0]++;
                    }
                    return;
                }
                if (!(w.hasSelf() && w.selfPlaying())) {
                    died[0] = true;
                    r.stop();
                    return;
                }
                if (changed[0] >= 0 && w.newestSelfTick() - changed[0] >= 0) {
                    found[0] = t;
                    visible[0] = frame < trace.n && (key == ControlKey.TURN_LEFT ? w.selfHeading() != trace.heading[frame]
                        : w.selfVx() != trace.vx[frame] || w.selfVy() != trace.vy[frame]);
                    r.stop();
                }
            };
            rig.runUntil(t0 + WINDOW_MS * MS);
            if (died[0]) {
                out.add(new Trial(key, t0, Double.NaN, Outcome.DIED));
            } else if (found[0] < 0) {
                out.add(new Trial(key, t0, Double.NaN, Outcome.NO_DEPARTURE));
            } else {
                out.add(new Trial(key, t0, (found[0] - t0) / 1e6, Outcome.MEASURED));
                if (!visible[0]) {
                    counts[1]++;
                }
            }
        }
    }

    /** The server tick grid's offset from the frame grid: varies with the seed, fixed per run. */
    static long tickPhase(long seed) {
        return Math.floorMod(seed * 7_919_993L, 71_000_000L);
    }
}
