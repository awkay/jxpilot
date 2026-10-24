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
package com.fulcrologic.jxpilot.client.game.predict;

import com.fulcrologic.jxpilot.client.net.NetDiagnostics;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.InputHistory;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.sim.Click;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.KernelWorld;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.SeededTieBreak;
import com.fulcrologic.jxpilot.common.sim.ShipBody;
import com.fulcrologic.jxpilot.common.sim.ShipKernel;

/**
 * The own-ship predictor (docs/latency-fixes.md §4.1, phase 1c): the own ship (and the camera following it) drawn at
 * the predicted tick from the shared ship kernel, so a key shows on the next frame at any RTT.
 * <ul>
 *   <li><b>Ring.</b> A preallocated ring of predicted states, one per tick. Tick k is the state after the server's
 *   tick k, stepped from tick k - 1 with the input entry for k, exactly as the server applies it: the pointer turn,
 *   the keys with the entry's taps, then {@link ShipKernel#step} (keys, autofire, limits, fly). The kernel
 *   world's C frame clock is the PREDICT's plus the ticks since (frames runs one per tick, the PREDICT carries it
 *   after its tick).</li>
 *   <li><b>Reconciliation.</b> A PREDICT for tick S (the server's state after S) is compared with the ring's tick S
 *   ({@link ShipBody#sameState}). Equal: nothing to do. Different: tick S is reseeded with the server's state and
 *   ticks S+1.. are replayed; the move of the predicted ship goes to the {@link CorrectionSmoother}.</li>
 *   <li><b>Speculative partial tick.</b> Each frame draws lerp(pred[K], step(pred[K], the entry being collected),
 *   alpha), K the newest final entry and alpha how far the predicted clock is past it (clamped to [0, 1]): a key
 *   change starts to show on the next frame and ramps over the rest of the tick.</li>
 *   <li><b>Own shots.</b> The kernel's bullets go to the {@link ProvisionalShots} (drawn on this timeline,
 *   confirmed by the snapshots), and the predictor gives each back to the ship's {@code shots} count in the tick it
 *   ends (its life runs out or it crashes into a wall), as the server's shot expiry does (the shot cap gates the
 *   guns). A bullet that hits something on the server is given back earlier there: the next PREDICT corrects the
 *   count.</li>
 *   <li><b>Not predicted</b> (inactive, the snapshot timeline draws the ship): no valid PREDICT yet, no kernel world, a
 *   ship not playing (dead, paused, game over, hover-paused), the round delay, warping or warped, autopilot, a pause
 *   key, or a tick whose input entry the history no longer holds. A predicted crash freezes the ship where
 *   it crashed (still active) until a PREDICT rules.</li>
 * </ul>
 * Allocation free after construction (a kernel world change builds a new kernel).
 */
public final class OwnShipPredictor {
    /** Ticks in the ring (a power of two, more than the input history holds). */
    public static final int RING = 64;
    /** Ticks of exhaust kept for the drawer (a power of two). */
    public static final int EXHAUST_RING = 16;
    /** C {@code RES}: headings per turn. */
    static final double RES = Const.RES;

    /** Reconciliation results, for diagnostics and the latency harness. */
    public interface Listener {
        /** The PREDICT for {@code tick} was compared: {@code exact} when bit-equal, else the correction in px. */
        void reconciled(int tick, boolean exact, double correctionPx);
    }

    private final ShipBody[] ring = new ShipBody[RING];
    private final int[] ringTick = new int[RING];
    private final boolean[] ringHas = new boolean[RING];
    private final ShipBody spec = new ShipBody();
    private final SeededTieBreak tieBreak = new SeededTieBreak();
    private final java.util.function.DoubleSupplier tieSource = this::tie;
    private final PredictEvents ev = new PredictEvents(tieBreak);
    private final CorrectionSmoother smoother = new CorrectionSmoother();
    private final ProvisionalShots shots = new ProvisionalShots();
    private KernelWorld world;
    private ShipKernel kernel;
    private boolean predictable;
    private int roundDelay;
    /** C's default shotLife, while the server's options are not known. */
    static final int DEFAULT_SHOT_LIFE = 60;
    private int ownId;

    private int base;
    private long baseFrames;
    private int head;
    private boolean seeded;
    private long lastPredicts = -1;
    private boolean active;
    private boolean frozen;
    private boolean halted;
    /** Prediction on (the A/B switch). */
    public boolean enabled = true;
    private Listener listener;

    // ---- drawn state
    private double x;
    private double y;
    private double headingRad;
    private float vx;
    private float vy;
    private double fuel;
    private double fuelMax;
    private int used;
    private int status;
    private double drawnTick;
    private boolean wall;

    // ---- exhaust of newly final ticks
    private final int[] exTick = new int[EXHAUST_RING];
    private final float[] exX = new float[EXHAUST_RING];
    private final float[] exY = new float[EXHAUST_RING];
    private final float[] exVx = new float[EXHAUST_RING];
    private final float[] exVy = new float[EXHAUST_RING];
    private final float[] exHeading = new float[EXHAUST_RING];
    private int exWrite;
    private int exRead;

    // ---- stats
    private long reconciliations;
    private long exact;
    private long corrections;
    private double lastCorrectionPx = Double.NaN;
    private long windowStart = Long.MIN_VALUE;
    private int windowCorrections;
    private double correctionsPerSecond = Double.NaN;

    public OwnShipPredictor() {
        for (int i = 0; i < RING; i++) {
            ring[i] = new ShipBody();
        }
        ev.setShotSink(shots);
    }

    /** The provisional own shots (drawn while {@link #active()}). */
    public ProvisionalShots shots() { return shots; }

    /**
     * BEHAVIOR: the kernel's bounce tie-break: with predictableBounces the source reseeded from (tick, own id) at
     * each fly, as the server's; else 0.5 (the server draws from its random stream, which no client can know).
     */
    double tie() {
        return predictable ? tieBreak.getAsDouble() : 0.5;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Also told of every bullet the kernel fires (tests and probes); the {@link #shots()} get them as well. */
    public void setShotSink(PredictEvents.ShotSink sink) {
        shots.setTap(sink);
    }

    /** The smoother (its half-life is tunable). */
    public CorrectionSmoother smoother() { return smoother; }

    /** BEHAVIOR: forget the ring and the drawn state (a new session, prediction off, or an unpredictable spell). */
    public void reset() {
        seeded = false;
        active = false;
        frozen = false;
        halted = false;
        smoother.clear();
        lastPredicts = -1;
        roundDelay = 0;
        exRead = exWrite;
        shots.clear();
    }

    private static int idx(int tick) {
        return tick & (RING - 1);
    }

    /**
     * Whether the ring holds tick {@code tick}. PREDICTs only move forward and the ring only ever holds ticks up to
     * the head it advanced to, so a slot holding the tick is that tick's prediction.
     */
    private boolean has(int tick) {
        return seeded && ringHas[idx(tick)] && ringTick[idx(tick)] == tick;
    }

    /**
     * One frame: reconcile with a new PREDICT, advance the ring to the newest final entry, and compute the drawn
     * ship for {@code nowNanos}.
     * <p>
     * BEHAVIOR: inactive (and reset) when disabled, without a source, a kernel world or a final entry; the world
     * changing rebuilds the kernel and drops the ring. A PREDICT that is not valid (its rare part or key masks not
     * known) is not compared: before the first valid one nothing is predicted, after it the ring rides on.
     * BEHAVIOR: a new PREDICT for tick S: with the ring holding S, it is compared ({@link Listener}) and on a
     * difference reseeded and replayed (the predicted ship's move is the smoother's correction); without, the ring is
     * seeded at S (no correction). Only a comparison of a ship in play both in the ring and on the server is
     * judged (counted, reported to the {@link Listener} and the diagnostics).
     * BEHAVIOR: the ring then steps to the newest final entry; the speculative partial tick is drawn from the
     * collected entry at alpha = clamp(predicted tick - K, 0, 1).
     * BEHAVIOR: inactive while the state at K is unpredictable (see the class comment); active and frozen at the
     * crash point after a predicted crash.
     */
    public void update(PredictionSource src, long nowNanos) {
        boolean was = active;
        active = false;
        if (!enabled || src == null) {
            if (seeded) {
                reset();
            }
            return;
        }
        if (src.kernelWorld() == null || !src.haveFinal()) {
            if (seeded) {
                reset();
            }
            return;
        }
        if (src.kernelWorld() != world) {
            world = src.kernelWorld();
            kernel = new ShipKernel(world, tieSource);
            com.fulcrologic.jxpilot.common.config.Options o = src.serverOptions();
            predictable = o != null && o.getBool(Opt.PREDICTABLE_BOUNCES);
            shots.configure(world, o);
            reset();
        }
        ownId = src.ownId();
        ev.configure(predictable, ownId);
        double t = src.predictedTick(nowNanos);
        if (Double.isNaN(t)) {
            return;
        }
        InputHistory h = src.input();
        // a PREDICT that cannot be used (its rare part or key masks were lost) is skipped: the ring rides on
        if (src.predictValid() && src.predicts() != lastPredicts) {
            lastPredicts = src.predicts();
            roundDelay = src.predictRoundDelay();
            reconcile(src, h, was);
        }
        if (!seeded || halted || roundDelay > 0) {
            return;
        }
        int fin = src.finalTick();
        while (!halted && fin - head > 0) {
            int k = head + 1;
            if (!h.has(k)) {   // the history (InputHistory.RING) runs out before the ring would wrap
                halted = true;
                break;
            }
            advance(k, h);
            emitExhaust(ring[idx(k)], k);
        }
        if (halted) {
            return;
        }
        ShipBody cur = ring[idx(head)];
        if (!predictableState(cur)) {
            return;
        }
        double alpha = frozen ? 0 : Math.max(0, Math.min(1, t - head));
        shots.advanceTo(head);
        spec.copyFrom(cur);
        if (!frozen) {
            step(spec, head + 1, src.collectingKeys(), src.collectingTaps(), src.collectingAim(), true);
        }
        draw(cur, spec, alpha, nowNanos);
        shots.draw(head, alpha);
        active = true;
        publish(src.diagnostics(), nowNanos);
    }

    /**
     * BEHAVIOR: true unless the ship is not playing (dead, paused, game over), hover-paused, warping or warped, on
     * autopilot; KILLED (a predicted crash) is predictable (frozen).
     */
    static boolean predictableState(ShipBody b) {
        return ShipKernel.playing(b) && (b.status & (Obj.HOVERPAUSE | Obj.WARPING | Obj.WARPED)) == 0
            && (b.used & Has.AUTOPILOT) == 0;
    }

    /** Ring tick k from k - 1: a frozen ship (a predicted crash) stays put, else one kernel step with entry k. */
    private void advance(int k, InputHistory h) {
        ShipBody b = ring[idx(k)];
        b.copyFrom(ring[idx(head)]);
        if (frozen) {
            ev.beginStep(k, false);
        } else {
            step(b, k, h.keys(k), h.taps(k), h.aimTurn(k), false);
        }
        ringTick[idx(k)] = k;
        ringHas[idx(k)] = true;
        head = k;
    }

    private void reconcile(PredictionSource s, InputHistory h, boolean wasDrawn) {
        int sTick = s.predictTick();
        ShipBody server = s.predictBody();
        boolean known = has(sTick);
        double beforeX = 0;
        double beforeY = 0;
        double beforeH = 0;
        int oldHead = head;
        // only a prediction of a ship in play that the server also has in play is judged (a death on the server, a
        // respawn or a pause is not a prediction error: those are drawn from the snapshots)
        boolean judged = known && predictableState(server) && predictableState(ring[idx(sTick)]);
        if (known) {
            boolean same = ring[idx(sTick)].sameState(server, s.predictFrames());
            if (judged) {
                reconciliations++;
            }
            if (same) {
                if (judged) {
                    exact++;
                    if (listener != null) {
                        listener.reconciled(sTick, true, 0);
                    }
                }
                base = sTick;
                baseFrames = s.predictFrames();
                return;
            }
            ShipBody old = ring[idx(head)];
            beforeX = old.cx / (double) (1 << Click.SHIFT);
            beforeY = old.cy / (double) (1 << Click.SHIFT);
            beforeH = old.dir;
        }
        ShipBody b = ring[idx(sTick)];
        b.copyFrom(server);
        ringTick[idx(sTick)] = sTick;
        ringHas[idx(sTick)] = true;
        base = sTick;
        baseFrames = s.predictFrames();
        head = sTick;
        seeded = true;
        frozen = (b.status & Obj.KILLED) != 0;
        halted = false;
        if (!known) {
            smoother.clear();
            return;
        }
        for (int k = sTick + 1; k - oldHead <= 0 && h.has(k); k++) {
            advance(k, h);
        }
        ShipBody now = ring[idx(head)];
        double dx = XMath.wrapDelta(now.cx / (double) (1 << Click.SHIFT) - beforeX, world.pixelWidth(), world.wrap());
        double dy = XMath.wrapDelta(now.cy / (double) (1 << Click.SHIFT) - beforeY, world.pixelHeight(), world.wrap());
        double dh = XMath.wrapDelta(now.dir - beforeH, RES, true);
        boolean snap = !wasDrawn || !predictableState(server);
        smoother.correct(dx, dy, dh, snap);
        if (judged) {
            corrections++;
            windowCorrections++;
            double px = Math.hypot(dx, dy);
            lastCorrectionPx = px;
            if (listener != null) {
                listener.reconciled(sTick, false, px);
            }
        }
    }

    /**
     * One tick of the own ship, as the server applies entry {@code k}: the C frame clock of tick k, the pointer turn,
     * keys | taps (when a tap went down and up), then the kernel step (keys, autofire, limits, fly); then the own
     * bullets that end in tick k (their life runs out, or they crash: {@link ProvisionalShots#endingAt}) are given
     * back, as the server's object expiry does. A wormhole or a pause key stops predicting (the server's world decides) and a crash
     * freezes the ship.
     */
    private void step(ShipBody b, int k, long keys, long taps, int aim, boolean speculative) {
        world.frames = baseFrames + (k - base - 1);
        world.roundDelay = 0;
        ev.beginStep(k, speculative);
        if (aim != 0) {
            kernel.keys.pointerMove(b, aim, ev);
        }
        long down = taps & ~keys;
        if (down != 0) {
            kernel.keys.apply(b, keys | down, ev);
        }
        kernel.step(b, keys, ev);   // its key handling releases the taps (the server's second apply)
        b.shots = Math.max(0, b.shots - shots.endingAt(k));
        if (!speculative) {
            if (ev.warped() || ev.pauseKey()) {
                halted = true;
            }
            if (ev.crashed()) {
                frozen = true;
            }
        }
    }

    /** Exhaust of a newly final tick (the frame loop's; replays of known ticks make none). */
    private void emitExhaust(ShipBody b, int k) {
        if (!ev.thrust()) {
            return;
        }
        int i = exWrite & (EXHAUST_RING - 1);
        exTick[i] = k;
        exX[i] = (float) (b.cx / (double) (1 << Click.SHIFT));
        exY[i] = (float) (b.cy / (double) (1 << Click.SHIFT));
        exVx[i] = b.vx;
        exVy[i] = b.vy;
        exHeading[i] = (float) (b.dir * (2 * Math.PI / RES));
        exWrite++;
        if (exWrite - exRead > EXHAUST_RING) {
            exRead = exWrite - EXHAUST_RING;
        }
    }

    /** Exhaust ticks not taken yet. */
    public int exhaustPending() {
        return exWrite - exRead;
    }

    /** BEHAVIOR: the oldest pending exhaust tick's ring index, and it is taken; -1 when none is pending. */
    public int takeExhaust() {
        if (exWrite == exRead) {
            return -1;
        }
        return exRead++ & (EXHAUST_RING - 1);
    }

    public int exhaustTick(int i) { return exTick[i]; }

    public float exhaustX(int i) { return exX[i]; }

    public float exhaustY(int i) { return exY[i]; }

    public float exhaustVx(int i) { return exVx[i]; }

    public float exhaustVy(int i) { return exVy[i]; }

    public float exhaustHeading(int i) { return exHeading[i]; }

    private void draw(ShipBody a, ShipBody b, double alpha, long nowNanos) {
        double ax = a.cx / (double) (1 << Click.SHIFT);
        double ay = a.cy / (double) (1 << Click.SHIFT);
        double bxp = b.cx / (double) (1 << Click.SHIFT);
        double byp = b.cy / (double) (1 << Click.SHIFT);
        double w = world.pixelWidth();
        double hgt = world.pixelHeight();
        boolean wrap = world.wrap();
        wall = a.lastWallTouch - world.frames >= -1 || b.lastWallTouch - world.frames >= -1;
        smoother.decay(nowNanos, wall);
        double px = ax + alpha * XMath.wrapDelta(bxp - ax, w, wrap) + smoother.offsetX();
        double py = ay + alpha * XMath.wrapDelta(byp - ay, hgt, wrap) + smoother.offsetY();
        x = wrapCoord(px, w);
        y = wrapCoord(py, hgt);
        double fd = a.dir + alpha * XMath.wrapDelta(b.dir - a.dir, RES, true) + smoother.offsetHeading();
        fd = fd - Math.floor(fd / RES) * RES;
        headingRad = fd * (2 * Math.PI / RES);
        vx = (float) (a.vx + alpha * (b.vx - a.vx));
        vy = (float) (a.vy + alpha * (b.vy - a.vy));
        fuel = a.fuel + alpha * (b.fuel - a.fuel);
        fuelMax = a.fuelMax;
        used = b.used;
        status = b.status;
        drawnTick = head + alpha;
    }

    /**
     * Into [0, size). Without edge wrap a position is always inside already (the ship, its next step and the
     * smoother's blend of two drawn positions stay in the map), so this changes nothing there.
     */
    private static double wrapCoord(double v, double size) {
        return v - Math.floor(v / size) * size;
    }

    private void publish(NetDiagnostics d, long nowNanos) {
        if (windowStart == Long.MIN_VALUE) {
            windowStart = nowNanos;
        } else if (nowNanos - windowStart >= 1_000_000_000L) {
            correctionsPerSecond = windowCorrections * 1e9 / (nowNanos - windowStart);
            windowCorrections = 0;
            windowStart = nowNanos;
        }
        if (d != null) {
            d.correctionsPerSecond = correctionsPerSecond;
            d.lastCorrectionPx = lastCorrectionPx;
        }
    }

    /**
     * The wire ship flags of the drawn ship: the newest SELF's ({@code selfFlags}), with the bits the own keys and the
     * kernel decide (shield, thrust, cloak, phasing, emergency shield, deflector, refuel, repair) from the prediction.
     * <p>
     * BEHAVIOR: those eight bits come from the predicted used/status bits; every other bit is the SELF's.
     */
    public int flags(int selfFlags) {
        return mergeFlags(selfFlags, used, status);
    }

    /** As {@link #flags}, for given predicted used and status bits. */
    static int mergeFlags(int selfFlags, int usedBits, int statusBits) {
        int f = selfFlags & ~(Records.ShipFlags.SHIELD | Records.ShipFlags.THRUSTING | Records.ShipFlags.CLOAKED
            | Records.ShipFlags.PHASING | Records.ShipFlags.EMERGENCY_SHIELD | Records.ShipFlags.DEFLECTOR
            | Records.ShipFlags.REFUELING | Records.ShipFlags.REPAIRING);
        if ((usedBits & Has.SHIELD) != 0) {
            f |= Records.ShipFlags.SHIELD;
        }
        if ((statusBits & Obj.THRUSTING) != 0) {
            f |= Records.ShipFlags.THRUSTING;
        }
        if ((usedBits & Has.CLOAKING_DEVICE) != 0) {
            f |= Records.ShipFlags.CLOAKED;
        }
        if ((usedBits & Has.PHASING_DEVICE) != 0) {
            f |= Records.ShipFlags.PHASING;
        }
        if ((usedBits & Has.EMERGENCY_SHIELD) != 0) {
            f |= Records.ShipFlags.EMERGENCY_SHIELD;
        }
        if ((usedBits & Has.DEFLECTOR) != 0) {
            f |= Records.ShipFlags.DEFLECTOR;
        }
        if ((usedBits & Has.REFUEL) != 0) {
            f |= Records.ShipFlags.REFUELING;
        }
        if ((usedBits & Has.REPAIR) != 0) {
            f |= Records.ShipFlags.REPAIRING;
        }
        return f;
    }

    /** Whether the last {@link #update} drew a predicted ship. */
    public boolean active() { return active; }

    /** Whether the predicted ship is frozen at a predicted crash. */
    public boolean frozen() { return frozen; }

    /** Drawn position (pixels) and heading (radians). */
    public double x() { return x; }

    public double y() { return y; }

    public double heading() { return headingRad; }

    public float vx() { return vx; }

    public float vy() { return vy; }

    /** Fuel in C fixed point (256 per unit). */
    public double fuel() { return fuel; }

    public double fuelMax() { return fuelMax; }

    /** The predicted tick the ship is drawn at (newest final tick + alpha). */
    public double drawnTick() { return drawnTick; }

    /** The newest final predicted tick. */
    public int headTick() { return head; }

    /** Whether the drawn ship touches a wall (the smoother decays faster). */
    public boolean touchingWall() { return wall; }

    /** Judged reconciliations: PREDICTs compared while the ship was in play in the ring and on the server. */
    public long reconciliations() { return reconciliations; }

    public long exactReconciliations() { return exact; }

    /**
     * Judged reconciliations (a ship in play predicted and in play on the server) that moved the prediction.
     */
    public long corrections() { return corrections; }

    public double lastCorrectionPx() { return lastCorrectionPx; }

    public double correctionsPerSecond() { return correctionsPerSecond; }
}
