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
import com.fulcrologic.jxpilot.common.net.Records;

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
 * I2P frame must draw what the baseline frame does not: another heading (turn), the THRUSTING flag (thrust)
 * ({@link Result#invisible}).
 * <p>
 * <b>Phase sampling.</b> I2P depends on when the key goes down relative to the server's tick grid. Trial j of N
 * is placed so that the INPUT, after the uplink's base delay, reaches the server at phase (j + 1/2) T / N of a
 * tick ({@link #arrivalPhase}): the phases are stratified exactly over [0, T). The tick k chosen has
 * k mod P = j mod P, P = {@link #phasePeriodTicks} (7 at 14 Hz: 7 ticks are exactly 60 render frames), so the
 * trial's position relative to the render frames depends only on j, not on when the ship happened to spawn, and
 * the trials cycle through the P positions of the tick grid against the frame grid (the frame phase at which the
 * changed SELF arrives). With the same seed, a
 * trial's I2P at RTT r and at RTT 0 then differ by exactly r for RTTs whose half is a whole number of frames
 * (100 ms: 50 ms = 6 frames). Trials alternate turn and thrust, so each key covers every other stratum.
 * <p>
 * For thrust trials it also records when the drawn position first moves more than 1 px away from the
 * baseline's ({@link Trial#positionMs}): the F1 error blending lets the ship drift into the new path.
 */
final class InputToPhoton {
    static final long MS = 1_000_000L;
    /** First trial this long after the spawn (the clock has settled; the course's thrust is over). */
    static final long WARMUP_MS = 1500;
    /** Trials of a block start at least this far apart. */
    static final long SPACING_MS = 263;
    /** A trial without a departure within this window counts as {@link Outcome#NO_DEPARTURE}. */
    static final long WINDOW_MS = 2000;
    static final long SPAWN_LIMIT = 30 * LatencyRig.SEC;
    /** The drawn-position threshold of {@link Trial#positionMs}. */
    static final double POSITION_PX = 1.0;

    /**
     * MEASURED; DEAD_AT_T0: the baseline ship was not in play at t0; DIED: the ship died (crashed) after t0 before
     * the key showed; NO_DEPARTURE: it lived but the key did not show within {@link #WINDOW_MS}.
     */
    enum Outcome { MEASURED, DEAD_AT_T0, DIED, NO_DEPARTURE }

    /**
     * @param phaseNanos the INPUT's planned arrival phase within a server tick
     * @param positionMs thrust only: t0 to the first frame whose drawn position is more than 1 px from the
     *                   baseline's; NaN for turns and when it did not happen in the window
     */
    record Trial(ControlKey key, long t0, double ms, Outcome outcome, long phaseNanos, double positionMs) {
        Trial(ControlKey key, long t0, double ms, Outcome outcome) {
            this(key, t0, ms, outcome, 0, Double.NaN);
        }
    }

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

        /** {@link Trial#positionMs} of the measured thrust trials. */
        Stats positionStats() {
            double[] v = new double[trials.size()];
            int n = 0;
            for (Trial t : trials) {
                if (t.outcome == Outcome.MEASURED && t.key == ControlKey.THRUST) {
                    v[n++] = t.positionMs;
                }
            }
            return new Stats(v, n);
        }

        /** BEHAVIOR: the mean I2P of the measured trials (NaN without any). */
        double mean() {
            double sum = 0;
            int n = 0;
            for (Trial t : trials) {
                if (t.outcome == Outcome.MEASURED) {
                    sum += t.ms;
                    n++;
                }
            }
            return n == 0 ? Double.NaN : sum / n;
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
        final int[] flags;
        final boolean[] playing;
        int n;

        Trace(int cap) {
            heading = new float[cap];
            vx = new float[cap];
            vy = new float[cap];
            x = new float[cap];
            y = new float[cap];
            flags = new int[cap];
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
            flags[frame] = w.selfFlags();
            playing[frame] = w.hasSelf() && w.selfPlaying();
            n = Math.max(n, frame + 1);
        }

        /** BEHAVIOR: true when frame {@code frame} of {@code w} draws the own ship exactly as this trace did. */
        boolean same(int frame, ClientWorld w) {
            return frame < n && heading[frame] == w.selfHeading() && vx[frame] == w.selfVx() && vy[frame] == w.selfVy()
                && x[frame] == w.selfX() && y[frame] == w.selfY() && flags[frame] == w.selfFlags()
                && playing[frame] == (w.hasSelf() && w.selfPlaying());
        }
    }

    private InputToPhoton() {
    }

    /** BEHAVIOR: the earliest t0 of trial {@code i} of a block: the warm-up, then {@link #SPACING_MS} apart. */
    static long earliestT0(long spawn, int i) {
        return spawn + (WARMUP_MS + i * SPACING_MS) * MS;
    }

    static ControlKey keyOf(int trial) {
        return trial % 2 == 0 ? ControlKey.TURN_LEFT : ControlKey.THRUST;
    }

    /** BEHAVIOR: the stratified arrival phase of trial j of n at {@code hz}: (j + 1/2) / n of a tick, in ns. */
    static long arrivalPhase(int j, int n, int hz) {
        return Math.round((j + 0.5) * 1e9 / hz / n);
    }

    /**
     * BEHAVIOR: ticks after which the tick grid lines up with the render grid again: hz / gcd(hz, FRAME_HZ) (7 at
     * 14 Hz, 1 at 12 Hz).
     */
    static int phasePeriodTicks(int hz) {
        int a = hz;
        int b = LatencyRig.FRAME_HZ;
        while (b != 0) {
            int r = a % b;
            a = b;
            b = r;
        }
        return hz / a;
    }

    /**
     * BEHAVIOR: t0 = tickTime(k) + phase - upDelay for the smallest k with k mod {@code period} = {@code residue} and
     * t0 at or after {@code earliest}: the key reaches the server, after the uplink's base delay, {@code phase} after
     * tick k.
     */
    static long scheduleT0(java.util.function.LongUnaryOperator tickTime, long earliest, long phase, long upDelay,
                           int period, int residue) {
        long k = Math.floorMod(residue, period);
        while (tickTime.applyAsLong(k + 64L * period) + phase - upDelay < earliest) {
            k += 64L * period;
        }
        while (tickTime.applyAsLong(k) + phase - upDelay < earliest) {
            k += period;
        }
        return tickTime.applyAsLong(k) + phase - upDelay;
    }

    /** Trials per baseline game (a block). */
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
     * crash sooner or later). Trial j (over all blocks) gets arrival phase {@link #arrivalPhase}(j, trials).
     */
    static Result measure(Course course, Scenario scenario, int trials, long seed) {
        List<Trial> out = new ArrayList<>();
        long[] counts = new long[3];
        for (int b = 0; b * BLOCK < trials; b++) {
            measureBlock(course, scenario, b * BLOCK, Math.min(BLOCK, trials - b * BLOCK), trials, blockSeed(seed, b),
                out, counts);
        }
        return new Result(out, counts[0], (int) counts[1], (int) counts[2]);
    }

    /** One baseline game and its trials; adds the trials to {@code out} and the check counts to {@code counts}. */
    private static void measureBlock(Course course, Scenario scenario, int first, int trials, int total, long seed,
                                     List<Trial> out, long[] counts) {
        String[] args = course.serverArgs(0);
        LatencyRig base = new LatencyRig(args, scenario.direction(), scenario.direction(), seed, tickPhase(seed));
        long spawn = base.runUntilPlaying(LatencyRig.START + SPAWN_LIMIT);
        if (spawn < 0) {
            throw new IllegalStateException(course + " " + scenario.label() + ": the ship never spawned");
        }
        int hz = base.server.hz;
        int period = phasePeriodTicks(hz);
        long upDelay = scenario.direction().delayNanos;
        long[] t0s = new long[trials];
        long[] phases = new long[trials];
        for (int i = 0; i < trials; i++) {
            phases[i] = arrivalPhase(first + i, total, hz);
            t0s[i] = scheduleT0(base::tickTime, earliestT0(spawn, i), phases[i], upDelay, period, first + i);
        }
        ServerTruth baseTruth = new ServerTruth(base.client.connection().playerId());
        long end = t0s[trials - 1] + WINDOW_MS * MS;
        Trace trace = new Trace((int) ((end - LatencyRig.START) / (LatencyRig.SEC / LatencyRig.FRAME_HZ)) + 2);
        base.setKeys(t -> course.keys(t - spawn));
        base.onTick = (r, t) -> baseTruth.record(r.server.world(), r.server.net.currentTick());
        base.onFrame = (r, t) -> trace.record((int) r.frames() - 1, r.world());
        base.runUntil(end);

        for (int i = 0; i < trials; i++) {
            ControlKey key = keyOf(first + i);
            long t0 = t0s[i];
            long phase = phases[i];
            int t0Frame = (int) Math.ceil((t0 - LatencyRig.START) * (double) LatencyRig.FRAME_HZ / LatencyRig.SEC);
            if (t0Frame >= trace.n || !trace.playing[t0Frame]) {
                out.add(new Trial(key, t0, Double.NaN, Outcome.DEAD_AT_T0, phase, Double.NaN));
                continue;
            }
            LatencyRig rig = new LatencyRig(args, scenario.direction(), scenario.direction(), seed, tickPhase(seed));
            long s = rig.runUntilPlaying(LatencyRig.START + SPAWN_LIMIT);
            if (s != spawn) {
                counts[2]++;
            }
            ServerTruth truth = new ServerTruth(rig.client.connection().playerId());
            long mask = key.mask();
            boolean thrust = key == ControlKey.THRUST;
            rig.setKeys(t -> course.keys(t - spawn) | (t >= t0 ? mask : 0));
            int[] changed = {-1};
            long[] found = {-1};
            long[] moved = {-1};
            boolean[] visible = {false};
            boolean[] died = {false};
            rig.onTick = (r, t) -> {
                int tick = r.server.net.currentTick();
                truth.record(r.server.world(), tick);
                if (changed[0] < 0 && t >= t0 && tick <= baseTruth.lastTick()) {
                    boolean differs = thrust ? truth.velocityDiffers(baseTruth, tick)
                        : truth.headingDiffers(baseTruth, tick);
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
                    if (found[0] < 0) {
                        died[0] = true;
                    }
                    r.stop();
                    return;
                }
                if (thrust && moved[0] < 0 && frame < trace.n && MotionMetrics.distance(w.selfX(), w.selfY(),
                    trace.x[frame], trace.y[frame], w.worldWidth(), w.worldHeight(), w.wraps()) > POSITION_PX) {
                    moved[0] = t;
                }
                if (found[0] < 0 && changed[0] >= 0 && w.newestSelfTick() - changed[0] >= 0) {
                    found[0] = t;
                    visible[0] = frame < trace.n && (thrust
                        ? (w.selfFlags() & Records.ShipFlags.THRUSTING) != 0
                            && (trace.flags[frame] & Records.ShipFlags.THRUSTING) == 0
                        : w.selfHeading() != trace.heading[frame]);
                }
                if (found[0] >= 0 && (!thrust || moved[0] >= 0)) {
                    r.stop();
                }
            };
            rig.runUntil(t0 + WINDOW_MS * MS);
            if (died[0]) {
                out.add(new Trial(key, t0, Double.NaN, Outcome.DIED, phase, Double.NaN));
            } else if (found[0] < 0) {
                out.add(new Trial(key, t0, Double.NaN, Outcome.NO_DEPARTURE, phase, Double.NaN));
            } else {
                out.add(new Trial(key, t0, (found[0] - t0) / 1e6, Outcome.MEASURED, phase,
                    moved[0] < 0 ? Double.NaN : (moved[0] - t0) / 1e6));
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
