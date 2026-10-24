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
import com.fulcrologic.jxpilot.common.math.XMath;

import java.util.HashMap;
import java.util.Map;

/**
 * The drawn-motion metrics of docs/latency-fixes.md §7.3, from one game with robots (the "other" ships): how far
 * the drawn ships are from the server's truth, how smooth they move, how often the own ship snaps. Collected
 * per render frame and judged against the truth after the run (the predicted own ship is drawn ahead of the
 * server); the metric arithmetic is in small static methods tested on their own.
 * <ul>
 *   <li><b>Own error</b>: drawn own position vs the server's at the tick it is drawn for
 *   ({@link ClientWorld#selfDrawnTick()}).</li>
 *   <li><b>Others' aim error</b>: a remote ship's drawn position vs the server's position at the own ship's
 *   drawn tick ({@link #referenceTick}): what aiming at it misses by.</li>
 *   <li><b>Smoothness</b> (the {@code SmoothMotionTest} method): |p(i) - 2 p(i-1) + p(i-2)|, the change of the
 *   per-frame step, over three consecutive frames that draw the ship.</li>
 *   <li><b>Snaps</b>: own-ship step changes above {@link #SNAP_PX} (3 ship lengths, §4.2's snap threshold) while
 *   alive, per minute. A step change while the server's own position also jumped that far (wormhole, hyperjump)
 *   is a teleport: counted apart and left out of the smoothness and snap figures.</li>
 *   <li><b>HUD figures</b>: the client's own RTT, jitter and loss readings at the end (what the net line shows),
 *   to check them against the link.</li>
 *   <li><b>Correction size</b> and <b>prediction exactness</b>: the own-ship predictor's reconciliations
 *   ({@link Sink}): the size of each correction (0 when bit-equal) and the bit-equal share. n/a with prediction off
 *   (no reconciliations).</li>
 * </ul>
 */
final class MotionMetrics {
    /** Three ship lengths ({@code SHIP_SZ} 16 is the radius). */
    static final double SNAP_PX = 3 * 32;
    static final int MAX_SAMPLES = 1 << 20;

    /** Where the own-ship predictor reports its reconciliations (one per PREDICT compared with its ring). */
    static final class Sink {
        double[] correction = new double[MAX_SAMPLES];
        int corrections;
        long reconciliations;
        long exact;

        void correction(double px, boolean bitEqual) {
            reconciliations++;
            if (bitEqual) {
                exact++;
            }
            if (corrections < correction.length) {
                correction[corrections++] = px;
            }
        }

        /** BEHAVIOR: the bit-equal share of reconciliations in percent, NaN before any. */
        double exactPercent() {
            return reconciliations == 0 ? Double.NaN : 100.0 * exact / reconciliations;
        }
    }

    /**
     * @param hudRttMs  what the HUD's net line shows at the end: the client's smoothed PING RTT
     * @param hudJitterMs and its snapshot jitter
     * @param hudLossPercent and its snapshot loss
     */
    record Result(Stats ownError, Stats aimError, Stats ownSmooth, Stats otherSmooth, double snapsPerMinute,
                  Stats correction, double exactPercent, long otherFrames, long teleports, double hudRttMs,
                  double hudJitterMs, double hudLossPercent) { }

    private final double[] ownErr = new double[MAX_SAMPLES];
    private final double[] aimErr = new double[MAX_SAMPLES];
    private final double[] ownSmooth = new double[MAX_SAMPLES];
    private final double[] otherSmooth = new double[MAX_SAMPLES];
    private int nOwnSmooth;
    private int nOtherSmooth;
    private long aliveFrames;
    final Sink sink = new Sink();

    /**
     * Own samples per alive frame (x, y, drawn tick, frames in the current alive run), judged against the truth
     * after the run: the predicted ship lives ahead of the server, so its truth is not known when it is drawn.
     */
    private final double[] ownX = new double[MAX_SAMPLES];
    private final double[] ownY = new double[MAX_SAMPLES];
    private final double[] ownTick = new double[MAX_SAMPLES];
    private final int[] ownRunAt = new int[MAX_SAMPLES];
    private int nOwnSamples;
    private int ownId = -1;
    /** Remote-ship samples (id, x, y, reference tick), judged after the run. */
    private final int[] aimId = new int[MAX_SAMPLES];
    private final double[] aimX = new double[MAX_SAMPLES];
    private final double[] aimY = new double[MAX_SAMPLES];
    private final double[] aimTick = new double[MAX_SAMPLES];
    private int nAimSamples;
    private double worldW;
    private double worldH;
    private boolean wraps;

    private int ownRun;
    /** Per remote ship id: x(i-1), y(i-1), x(i-2), y(i-2), the frame it was last drawn, run length. */
    private final Map<Integer, double[]> others = new HashMap<>();
    private long frame;

    /** Collect one rendered frame of {@code w}; the truth is consulted by {@link #result}. */
    void frame(ClientWorld w) {
        frame++;
        worldW = w.worldWidth();
        worldH = w.worldHeight();
        wraps = w.wraps();
        boolean alive = w.hasSelf() && w.selfPlaying();
        if (alive) {
            aliveFrames++;
            ownId = w.selfId();
            if (nOwnSamples < MAX_SAMPLES) {
                ownX[nOwnSamples] = w.selfX();
                ownY[nOwnSamples] = w.selfY();
                ownTick[nOwnSamples] = w.selfDrawnTick();
                ownRunAt[nOwnSamples] = ownRun;
                nOwnSamples++;
            }
            ownRun++;
        } else {
            ownRun = 0;
        }
        double ref = referenceTick(w);
        for (int i = 0; i < w.shipCount(); i++) {
            int id = w.shipId(i);
            if (nAimSamples < MAX_SAMPLES) {
                aimId[nAimSamples] = id;
                aimX[nAimSamples] = w.shipX(i);
                aimY[nAimSamples] = w.shipY(i);
                aimTick[nAimSamples] = ref;
                nAimSamples++;
            }
            double[] h = others.computeIfAbsent(id, k -> new double[6]);
            int run = h[4] == frame - 1 ? (int) h[5] : 0;
            if (run >= 2 && nOtherSmooth < MAX_SAMPLES) {
                otherSmooth[nOtherSmooth++] = stepChange(w.shipX(i), w.shipY(i), h[0], h[1], h[2], h[3], worldW, worldH,
                    wraps);
            }
            h[2] = h[0];
            h[3] = h[1];
            h[0] = w.shipX(i);
            h[1] = w.shipY(i);
            h[4] = frame;
            h[5] = run + 1;
        }
    }

    /**
     * BEHAVIOR: the tick the own ship lives at, which aim is judged against: its drawn tick while it is drawn,
     * else the client's estimate of the server tick now. (Phase 1c's predicted ship lives ahead of the server.)
     */
    static double referenceTick(ClientWorld w) {
        return w.hasSelf() ? w.selfDrawnTick() : w.serverTickNow();
    }

    /**
     * The figures, judged against the server's truth {@code t} (recorded for the whole run).
     * <p>
     * BEHAVIOR: own error per alive frame whose drawn tick the truth covers; a step change over {@link #SNAP_PX}
     * (three consecutive alive frames) while the server's own position jumped as far between the earlier frame's
     * drawn tick and one past the newest's is a teleport, else it counts in the smoothness figures and, over
     * {@link #SNAP_PX}, as a snap; aim error per drawn remote ship whose reference tick the truth covers.
     */
    Result result(int frameHz, com.fulcrologic.jxpilot.client.net.NetDiagnostics hud, ServerTruth t) {
        double[] truth = new double[2];
        int nOwn = 0;
        long snaps = 0;
        long teleports = 0;
        for (int i = 0; i < nOwnSamples; i++) {
            if (t.positionAt(ownId, ownTick[i], worldW, worldH, wraps, truth)) {
                ownErr[nOwn++] = distance(ownX[i], ownY[i], truth[0], truth[1], worldW, worldH, wraps);
            }
            if (ownRunAt[i] >= 2) {
                double s = stepChange(ownX[i], ownY[i], ownX[i - 1], ownY[i - 1], ownX[i - 2], ownY[i - 2], worldW,
                    worldH, wraps);
                if (s > SNAP_PX && t.jumped(ownId, (int) Math.floor(ownTick[i - 2]), (int) Math.ceil(ownTick[i]) + 1,
                    SNAP_PX, worldW, worldH, wraps)) {
                    teleports++; // the server moved the ship (wormhole, hyperjump): not a drawing artefact
                } else {
                    if (nOwnSmooth < MAX_SAMPLES) {
                        ownSmooth[nOwnSmooth++] = s;
                    }
                    if (s > SNAP_PX) {
                        snaps++;
                    }
                }
            }
        }
        int nAim = 0;
        for (int i = 0; i < nAimSamples; i++) {
            if (t.positionAt(aimId[i], aimTick[i], worldW, worldH, wraps, truth)) {
                aimErr[nAim++] = distance(aimX[i], aimY[i], truth[0], truth[1], worldW, worldH, wraps);
            }
        }
        double minutes = aliveFrames / (double) frameHz / 60.0;
        return new Result(new Stats(ownErr, nOwn), new Stats(aimErr, nAim), new Stats(ownSmooth, nOwnSmooth),
            new Stats(otherSmooth, nOtherSmooth), minutes == 0 ? Double.NaN : snaps / minutes,
            new Stats(sink.correction, sink.corrections), sink.exactPercent(), nAim, teleports, hud.rttMs(),
            hud.jitterMs(), hud.lossPercent());
    }

    /** BEHAVIOR: the wrap-aware distance between two points. */
    static double distance(double x0, double y0, double x1, double y1, double ww, double wh, boolean wrap) {
        return Math.hypot(XMath.wrapDelta(x0 - x1, ww, wrap), XMath.wrapDelta(y0 - y1, wh, wrap));
    }

    /**
     * BEHAVIOR: |p - 2 p1 + p2| for the newest point p and the two before it (p1 the previous), wrap-aware: the
     * change of the per-frame step; 0 for uniform motion.
     */
    static double stepChange(double x, double y, double x1, double y1, double x2, double y2, double ww, double wh,
                             boolean wrap) {
        double dx = XMath.wrapDelta(x - x1, ww, wrap) - XMath.wrapDelta(x1 - x2, ww, wrap);
        double dy = XMath.wrapDelta(y - y1, wh, wrap) - XMath.wrapDelta(y1 - y2, wh, wrap);
        return Math.hypot(dx, dy);
    }

    /** Fly {@code course} over {@code scenario} with {@code robots} robots for {@code seconds} after the spawn. */
    static Result measure(Course course, Scenario scenario, int robots, int seconds, long seed) {
        return measure(course, scenario, robots, seconds, seed, false);
    }

    /** As {@link #measure(Course, Scenario, int, int, long)}, the server with predictableBounces when asked. */
    static Result measure(Course course, Scenario scenario, int robots, int seconds, long seed, boolean predictable) {
        return measure(course, scenario, robots, seconds, seed, predictable, course::keys);
    }

    /**
     * As {@link #measure(Course, Scenario, int, int, long, boolean)}, flown with {@code keys} (the keys held as a
     * function of the time since the spawn) instead of the course's.
     */
    static Result measure(Course course, Scenario scenario, int robots, int seconds, long seed, boolean predictable,
                          java.util.function.LongUnaryOperator keys) {
        LatencyRig rig = new LatencyRig(course.serverArgs(robots, predictable), scenario.direction(),
            scenario.direction(), seed, InputToPhoton.tickPhase(seed));
        long spawn = rig.runUntilPlaying(LatencyRig.START + InputToPhoton.SPAWN_LIMIT);
        if (spawn < 0) {
            throw new IllegalStateException(course + " " + scenario.label() + ": the ship never spawned");
        }
        ServerTruth truth = new ServerTruth(rig.client.connection().playerId());
        MotionMetrics m = new MotionMetrics();
        rig.setKeys(t -> keys.applyAsLong(t - spawn));
        rig.onTick = (r, t) -> truth.record(r.server.world(), r.server.net.currentTick());
        rig.client.game().predictor().setListener((tick, exact, px) -> m.sink.correction(px, exact));
        rig.onFrame = (r, t) -> m.frame(r.world());
        rig.runUntil(spawn + seconds * LatencyRig.SEC);
        return m.result(LatencyRig.FRAME_HZ, rig.client.connection().netDiagnostics(), truth);
    }
}
