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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.sim.Click;
import com.fulcrologic.jxpilot.common.sim.KernelWorld;
import com.fulcrologic.jxpilot.common.sim.MoveState;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.Segment;

/**
 * Provisional own shots (docs/latency-fixes.md §4.7): the bullets the own-ship predictor fires, drawn on the
 * predicted timeline from the kernel's exact spawn and velocity.
 * <ul>
 *   <li><b>Motion</b>, as the server moves a SHOT each tick (its {@code moveObjects} and C {@code Move_object}):
 *   gravity at its block with shotsGravity, then free flight (wrapped) far from walls, else traced through the
 *   blocks with the common {@link Segment}; a crash (wall, cannon, target, treasure) ends it; with
 *   shotsWallBounce a bounce costs life (objectWallBounceLifeFactor) and speed (objectWallBounceBrakeFactor), and
 *   one faster than maxObjectWallBounceSpeed ends it. The server code is not shared (it moves pool objects), so this
 *   is a port of the SHOT branch onto the client's {@link KernelWorld}; wormholes do not warp a provisional shot
 *   (the kernel world has no objects: the object branch of wormholes finds none).</li>
 *   <li><b>Life</b>: shotLife ticks after the tick it was fired, as the server's expiry (the state after tick
 *   f + shotLife - 1 no longer has it).</li>
 *   <li><b>Replays</b>: a shot is keyed by (tick, ordinal); stepping a tick again ({@link #beginStep}) first drops the
 *   tick's unconfirmed shots, so a replay that fires differently leaves only what it fires. The speculative
 *   partial tick's shots are dropped at every speculative step and drawn from their spawn.</li>
 *   <li><b>Drawing</b>: each live shot at the drawn tick: between its state after the newest final tick and one
 *   more step, by the same alpha as the ship ({@link #draw}).</li>
 * </ul>
 * Allocation free after construction (a new kernel world builds a new {@link Segment}).
 */
public final class ProvisionalShots implements PredictEvents.ShotSink {
    /** Provisional shots at most (C's shotMax default is 300 per player; more are not drawn). */
    public static final int CAP = 512;

    private static final byte FREE = 0;
    private static final byte LIVE = 1;
    private static final byte SPEC = 2;

    private final byte[] state = new byte[CAP];
    private final int[] tick = new int[CAP];
    private final int[] ordinal = new int[CAP];
    /** The tick the state is after (tick - 1: the spawn, not moved yet). */
    private final int[] at = new int[CAP];
    private final int[] cx = new int[CAP];
    private final int[] cy = new int[CAP];
    private final float[] vx = new float[CAP];
    private final float[] vy = new float[CAP];
    private final long[] life = new long[CAP];
    /** The spawn (clicks, velocity), to step a shot to any tick for {@link #confirm}. */
    private final int[] spawnCx = new int[CAP];
    private final int[] spawnCy = new int[CAP];
    private final float[] spawnVx = new float[CAP];
    private final float[] spawnVy = new float[CAP];
    private final boolean[] confirmed = new boolean[CAP];
    private final byte[] misses = new byte[CAP];
    private int used;
    private final boolean[] taken = new boolean[CAP];

    private final MoveState ms = new MoveState();
    private KernelWorld world;
    private Segment seg;
    private int shotLife = OwnShipPredictor.DEFAULT_SHOT_LIFE;
    private boolean gravity = true;
    private boolean wallBounce;
    private boolean edgeBounce = true;
    private float bounceLifeFactor = 0.8f;
    private float maxBounceSpeed = 40;
    private float brakeFactor = 0.95f;

    // the next state of one shot (scratch for draw and step)
    private int nx;
    private int ny;
    private float nvx;
    private float nvy;
    private long nlife;

    private final float[] outX = new float[CAP];
    private final float[] outY = new float[CAP];
    private final int[] outTick = new int[CAP];
    private final int[] outOrdinal = new int[CAP];
    private int outCount;
    private long fired;
    private long crashed;

    /**
     * BEHAVIOR: a new kernel world: every shot is dropped, and the shot rules are read from the server's visible
     * options (C's defaults without them).
     */
    public void configure(KernelWorld w, Options o) {
        world = w;
        seg = new Segment(w, () -> 0.5);
        if (o != null) {
            shotLife = o.getInt(Opt.SHOT_LIFE);
            gravity = o.getBool(Opt.SHOTS_GRAVITY);
            wallBounce = o.getBool(Opt.SHOTS_WALL_BOUNCE);
            edgeBounce = o.getBool(Opt.EDGE_BOUNCE);
            bounceLifeFactor = (float) o.getDouble(Opt.OBJECT_WALL_BOUNCE_LIFE_FACTOR);
            maxBounceSpeed = (float) o.getDouble(Opt.MAX_OBJECT_WALL_BOUNCE_SPEED);
            brakeFactor = (float) o.getDouble(Opt.OBJECT_WALL_BOUNCE_BRAKE_FACTOR);
        }
        clear();
    }

    /** BEHAVIOR: no shots. */
    public void clear() {
        used = 0;   // slots at or past used are never read: free() hands them out afresh
        outCount = 0;
    }

    /**
     * BEHAVIOR: before the predictor steps tick {@code k}: its earlier shots of that tick are dropped (a replay fires
     * them again if it still does); before a speculative step every speculative shot is dropped.
     */
    @Override
    public void beginStep(int k, boolean speculative) {
        for (int i = 0; i < used; i++) {
            if (speculative ? state[i] == SPEC : state[i] != FREE && tick[i] == k) {
                state[i] = FREE;
            }
        }
        trim();
    }

    /** BEHAVIOR: a bullet of tick {@code k} starts at its spawn (clicks of the float pixels, as the server's). */
    @Override
    public void fired(int k, int ord, float x, float y, float svx, float svy, boolean speculative) {
        if (world == null) {
            return;
        }
        int i = free();
        if (i < 0) {
            return;
        }
        state[i] = speculative ? SPEC : LIVE;
        tick[i] = k;
        ordinal[i] = ord;
        at[i] = k - 1;
        cx[i] = Click.fromPixels(x);
        cy[i] = Click.fromPixels(y);
        vx[i] = svx;
        vy[i] = svy;
        life[i] = shotLife;
        spawnCx[i] = cx[i];
        spawnCy[i] = cy[i];
        spawnVx[i] = svx;
        spawnVy[i] = svy;
        confirmed[i] = false;
        misses[i] = 0;
        fired++;
    }

    private int free() {
        for (int i = 0; i < used; i++) {
            if (state[i] == FREE) {
                return i;
            }
        }
        return used < CAP ? used++ : -1;
    }

    private void trim() {
        while (used > 0 && state[used - 1] == FREE) {
            used--;
        }
    }

    /**
     * Step every final shot to the state after tick {@code head} (the predictor's newest final tick).
     * <p>
     * BEHAVIOR: each tick a shot moves once ({@link #next}); a crash or its life running out ends it.
     */
    public void advanceTo(int head) {
        for (int i = 0; i < used; i++) {
            while (state[i] == LIVE && head - at[i] > 0) {
                if (next(i)) {
                    cx[i] = nx;
                    cy[i] = ny;
                    vx[i] = nvx;
                    vy[i] = nvy;
                    life[i] = nlife;
                    at[i]++;
                } else {
                    if (nlife > 0) {
                        crashed++;
                    }
                    state[i] = FREE;
                }
            }
        }
        trim();
    }

    /**
     * The drawn shots at {@code alpha} past {@code head}: each final shot between its state after head and its next,
     * a speculative one between its spawn and its first move; a shot that dies in that step is drawn at its state.
     */
    public void draw(int head, double alpha) {
        outCount = 0;
        if (world == null) {
            return;
        }
        double w = world.pixelWidth();
        double h = world.pixelHeight();
        boolean wrap = world.wrap();
        for (int i = 0; i < used; i++) {
            if (state[i] == FREE || state[i] == LIVE && at[i] != head) {
                continue;
            }
            double x0 = cx[i] / (double) Click.CLICK;
            double y0 = cy[i] / (double) Click.CLICK;
            double x = x0;
            double y = y0;
            if (next(i)) {
                x = x0 + alpha * XMath.wrapDelta(nx / (double) Click.CLICK - x0, w, wrap);
                y = y0 + alpha * XMath.wrapDelta(ny / (double) Click.CLICK - y0, h, wrap);
            } else if (state[i] == SPEC) {
                continue;
            }
            if (wrap) {
                x -= Math.floor(x / w) * w;
                y -= Math.floor(y / h) * h;
            }
            outX[outCount] = (float) x;
            outY[outCount] = (float) y;
            outTick[outCount] = tick[i];
            outOrdinal[outCount] = ordinal[i];
            outCount++;
        }
    }

    /**
     * The state after one more tick of shot {@code i} into the scratch fields, as the server: gravity at its block
     * (shotsGravity), then the move; false when it ends in that tick (crash, too fast a bounce, life out).
     */
    boolean next(int i) {
        float svx = vx[i];
        float svy = vy[i];
        int px = cx[i] >> Click.SHIFT;
        int py = cy[i] >> Click.SHIFT;
        if (gravity) {
            svx += world.gravityX(px / Const.BLOCK_SZ, py / Const.BLOCK_SZ);
            svy += world.gravityY(px / Const.BLOCK_SZ, py / Const.BLOCK_SZ);
        }
        nlife = life[i];
        return move(cx[i], cy[i], svx, svy) && --nlife > 0;
    }

    /** C {@code Move_object} for a SHOT, into the scratch fields; false on a crash or a fatal bounce. */
    private boolean move(int x, int y, float mvx, float mvy) {
        int tx = Click.fromPixels(mvx);
        int ty = Click.fromPixels(mvy);
        int dist = world.wallDist((x >> Click.SHIFT) / Const.BLOCK_SZ, (y >> Click.SHIFT) / Const.BLOCK_SZ);
        if (dist > 2) {
            int max = ((dist - 2) * Const.BLOCK_SZ) >> 1;
            if ((float) (max * max) >= mvx * mvx + mvy * mvy) {
                nx = world.wrapXClick(x + tx);
                ny = world.wrapYClick(y + ty);
                nvx = mvx;
                nvy = mvy;
                return true;
            }
        }
        ms.pl = null;
        ms.obj = -1;
        ms.edgeWrap = world.wrap();
        ms.edgeBounce = edgeBounce;
        ms.wallBounce = wallBounce;
        ms.cannonCrashes = true;
        ms.targetCrashes = true;
        ms.treasureCrashes = wallBounce;
        ms.wormholeWarps = false;
        ms.phased = false;
        ms.team = Const.TEAM_NOT_SET;
        ms.targetTeam = Const.TEAM_NOT_SET;
        ms.status = gravity ? Obj.GRAVITY : 0;
        ms.type = Obj.SHOT;
        ms.start(x, y, mvx, mvy, tx, ty, 0);
        int nothingDone = 0;
        for (;;) {
            seg.move(ms);
            if ((ms.doneX | ms.doneY) == 0) {
                if (ms.crash != MoveState.NOT_A_CRASH) {
                    return false;
                }
                if (ms.bounce != MoveState.NOT_A_BOUNCE && ms.bounce != MoveState.BOUNCE_EDGE) {
                    nlife = (long) ((float) nlife * bounceLifeFactor);
                    if (nlife <= 0) {
                        return false;
                    }
                    if (ms.velX * ms.velX + ms.velY * ms.velY > maxBounceSpeed * maxBounceSpeed) {
                        return false;
                    }
                    ms.velX *= brakeFactor;
                    ms.velY *= brakeFactor;
                    ms.todoX = (int) (ms.todoX * brakeFactor);
                    ms.todoY = (int) (ms.todoY * brakeFactor);
                }
                if (++nothingDone >= 5) {
                    return false;
                }
            } else {
                ms.posX += ms.doneX;
                ms.posY += ms.doneY;
                nothingDone = 0;
            }
            if ((ms.todoX | ms.todoY) == 0) {
                break;
            }
        }
        // Segment wraps the position it moves on an edge-wrapped map (Move_object's own wrap is a no-op here)
        nx = ms.posX;
        ny = ms.posY;
        nvx = ms.velX;
        nvy = ms.velY;
        return true;
    }

    /** A server shot this close (px) to where a provisional one is at the snapshot's tick is that shot. */
    public static final double MATCH_PX = 4;
    /** Adopt the server's position when it differs from ours by more than this (px; the wire has half pixels). */
    public static final double ADOPT_PX = 1;
    /** A confirmed shot missing from this many complete snapshots in a row ended on the server (a hit). */
    public static final int MISSES = 2;

    /**
     * Reconcile with a complete snapshot of tick {@code t}: its shots of the own normal kind (SHOT records of colour
     * OWN, which the server marks for the receiver) are the server's own bullets.
     * <p>
     * BEHAVIOR: each final shot fired at or before {@code t} is stepped from its spawn to {@code t} (as the server)
     * and matched to the nearest unmatched own SHOT within {@link #MATCH_PX}: matched, it is confirmed, and when the
     * server's is more than {@link #ADOPT_PX} away the shot (and its spawn) moves by the difference (adoption).
     * BEHAVIOR: a shot unmatched in {@link #MISSES} complete snapshots in a row is dropped: never confirmed (and
     * the snapshots past its fire tick), the server did not fire it ({@link #unconfirmed()}); confirmed, it ended on
     * the server first (a hit, or out of the snapshots' view).
     * BEHAVIOR: a shot whose own steps end before {@code t} is left to its own expiry.
     */
    public void confirm(int t, int n, float[] xs, float[] ys, byte[] variants) {
        if (world == null) {
            return;
        }
        for (int j = 0; j < n; j++) {
            taken[j] = (variants[j] & 0x7F) != OWN_SHOT;
        }
        double w = world.pixelWidth();
        double h = world.pixelHeight();
        boolean wrap = world.wrap();
        for (int i = 0; i < used; i++) {
            if (state[i] != LIVE || tick[i] - t > 0 || !positionAt(i, t)) {
                continue;
            }
            double px = nx / (double) Click.CLICK;
            double py = ny / (double) Click.CLICK;
            int best = -1;
            double bestD = MATCH_PX * MATCH_PX;
            for (int j = 0; j < n && j < CAP; j++) {
                if (!taken[j]) {
                    double dx = XMath.wrapDelta(xs[j] - px, w, wrap);
                    double dy = XMath.wrapDelta(ys[j] - py, h, wrap);
                    double d = dx * dx + dy * dy;
                    if (d <= bestD) {
                        bestD = d;
                        best = j;
                    }
                }
            }
            if (best >= 0) {
                taken[best] = true;
                if (!confirmed[i]) {
                    confirmed[i] = true;
                    confirmedCount++;
                }
                misses[i] = 0;
                double d = Math.sqrt(bestD);
                maxMatchPx = Math.max(maxMatchPx, d);
                if (d > ADOPT_PX) {
                    int dcx = (int) Math.round(XMath.wrapDelta(xs[best] - px, w, wrap) * Click.CLICK);
                    int dcy = (int) Math.round(XMath.wrapDelta(ys[best] - py, h, wrap) * Click.CLICK);
                    cx[i] = world.wrapXClick(cx[i] + dcx);
                    cy[i] = world.wrapYClick(cy[i] + dcy);
                    spawnCx[i] = world.wrapXClick(spawnCx[i] + dcx);
                    spawnCy[i] = world.wrapYClick(spawnCy[i] + dcy);
                    adopted++;
                    maxAdoptPx = Math.max(maxAdoptPx, d);
                }
            } else if (!confirmed[i]) {
                if (t - tick[i] > 0 && ++misses[i] >= MISSES) {
                    state[i] = FREE;
                    unconfirmed++;
                }
            } else if (++misses[i] >= MISSES) {
                state[i] = FREE;
                ended++;
            }
        }
        trim();
    }

    /** The own normal bullet's SHOT variant without the harmless bit. */
    private static final int OWN_SHOT = com.fulcrologic.jxpilot.common.net.Records.ShotKind.variant(
        com.fulcrologic.jxpilot.common.net.Records.ShotKind.SHOT, com.fulcrologic.jxpilot.common.net.Records.ObjColor.OWN);

    /** Shot {@code i} stepped from its spawn to the state after tick {@code t} into nx, ny; false if it ended. */
    private boolean positionAt(int i, int t) {
        int x = spawnCx[i];
        int y = spawnCy[i];
        float svx = spawnVx[i];
        float svy = spawnVy[i];
        long l = shotLife;
        for (int k = tick[i]; k - t <= 0; k++) {
            if (gravity) {
                int bx = (x >> Click.SHIFT) / Const.BLOCK_SZ;
                int by = (y >> Click.SHIFT) / Const.BLOCK_SZ;
                svx += world.gravityX(bx, by);
                svy += world.gravityY(bx, by);
            }
            nlife = l;
            if (!move(x, y, svx, svy) || --nlife <= 0) {
                return false;
            }
            l = nlife;
            x = nx;
            y = ny;
            svx = nvx;
            svy = nvy;
        }
        nx = x;
        ny = y;
        return true;
    }

    private long confirmedCount;
    private long unconfirmed;
    private long adopted;
    private long ended;
    private double maxMatchPx;
    private double maxAdoptPx;

    /** Final shots the server confirmed. */
    public long confirmedShots() { return confirmedCount; }

    /** Final shots dropped because the server never had them. */
    public long unconfirmed() { return unconfirmed; }

    /** Confirmations that moved the shot to the server's position. */
    public long adopted() { return adopted; }

    /** Confirmed shots that ended on the server first (hits). */
    public long endedOnServer() { return ended; }

    /** The largest distance of a match (px). */
    public double maxMatchPx() { return maxMatchPx; }

    /** The largest adoption move (px). */
    public double maxAdoptPx() { return maxAdoptPx; }

    /** Shots drawn by the last {@link #draw}. */
    public int count() { return outCount; }

    public float x(int i) { return outX[i]; }

    public float y(int i) { return outY[i]; }

    /** The tick the drawn shot {@code i} was fired in. */
    public int firedTick(int i) { return outTick[i]; }

    /** Its number within that tick. */
    public int firedOrdinal(int i) { return outOrdinal[i]; }

    /** Bullets ever reported (also the replays'). */
    public long fired() { return fired; }

    /** Final shots ended before their life ran out (a crash or a fatal bounce). */
    public long crashed() { return crashed; }
}
