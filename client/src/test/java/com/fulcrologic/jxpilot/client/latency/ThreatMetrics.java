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

import java.util.HashMap;
import java.util.Map;

/**
 * Incoming-shot metrics (the user's "the shield did not come up although the shot was still far away"): on the
 * dogfight course with robots firing at the ship, how far the drawn enemy and cannon shots are from where the server
 * will have them when the player's input lands, and how long a shield press takes to raise the server's shield.
 * <ul>
 *   <li><b>Shot threat error</b>: per frame and drawn non-own bullet (SHOT and CANNON_SHOT kinds), the distance to the server's position of that
 *   shot at the tick the frame's input lands: the predicted clock's tick (protocol v2 applies the input collected now at
 *   it, whether the own ship is predicted or not). The drawn shot is identified by its source record (the snapshot record it was interpolated or
 *   reckoned from: its tick and half-pixel position, the server's there, within {@link #MATCH_PX}), so neither drawing
 *   can be matched to a neighbouring bullet. A shot the server has destroyed by then counts as
 *   {@link Result#goneByThen}; one without a source bullet as {@link Result#unmatched}.</li>
 *   <li><b>Shield reaction</b>: SHIELD goes down every {@link #PERIOD_MS} for {@link #HOLD_MS}; per press, from the
 *   key to the server tick after which the shield is up (the shield is a server verdict: this is what the slack
 *   costs), for presses while alive whose server shield was down at the key.</li>
 * </ul>
 * Judged after the run against the recorded server truth.
 */
final class ThreatMetrics {
    static final long MS = 1_000_000L;
    /** Not a whole number of ticks (21.6 at 14 Hz), so the presses sample the tick phase. */
    static final long PERIOD_MS = 1543;
    static final long HOLD_MS = 300;
    /** Presses start this long after the spawn (the first release drops the spawn shield). */
    static final long FIRST_MS = 2000;
    /** A source record within this of a server bullet at its tick is that bullet (the wire has half pixels). */
    static final double MATCH_PX = 1;
    static final int MAX = 1 << 21;

    record Result(Stats threatError, long drawnShots, long unmatched, long goneByThen, Stats shieldReaction,
                  int presses) { }

    /** BEHAVIOR: SHIELD held for HOLD_MS every PERIOD_MS from FIRST_MS after the spawn, nothing else. */
    static long keys(long sinceSpawn) {
        if (sinceSpawn < FIRST_MS * MS) {
            return 0;
        }
        return (sinceSpawn / MS - FIRST_MS) % PERIOD_MS < HOLD_MS ? ControlKey.SHIELD.mask() : 0;
    }

    static Result measure(Scenario s, int robots, int seconds, long seed) {
        Course c = Course.DOGFIGHT;
        LatencyRig rig = new LatencyRig(c.serverArgs(robots), s.direction(), s.direction(), seed,
            InputToPhoton.tickPhase(seed));
        long spawn = rig.runUntilPlaying(LatencyRig.START + InputToPhoton.SPAWN_LIMIT);
        if (spawn < 0) {
            throw new IllegalStateException("the ship never spawned");
        }
        ServerTruth truth = new ServerTruth(rig.client.connection().playerId());
        Map<Integer, Long> tickTime = new HashMap<>();
        rig.setKeys(t -> keys(t - spawn));
        rig.onTick = (r, t) -> {
            int tick = r.server.net.currentTick();
            truth.record(r.server.world(), tick);
            truth.recordOtherShots(r.server.world(), tick);
            tickTime.put(tick, t);
        };
        double[] fx = new double[MAX];
        double[] fy = new double[MAX];
        double[] fref = new double[MAX];
        double[] frender = new double[MAX];
        int[] fsrcTick = new int[MAX];
        double[] fsrcX = new double[MAX];
        double[] fsrcY = new double[MAX];
        int[] n = {0};
        double[] world = new double[3];
        long[] pressAt = new long[4096];
        boolean[] pressOk = new boolean[4096];
        int[] presses = {0};
        long[] lastKeys = {0};
        rig.onFrame = (r, t) -> {
            ClientWorld w = r.world();
            world[0] = w.worldWidth();
            world[1] = w.worldHeight();
            world[2] = w.wraps() ? 1 : 0;
            long k = keys(t - spawn);
            if (k != 0 && lastKeys[0] == 0 && presses[0] < pressAt.length) {
                pressAt[presses[0]] = t;
                pressOk[presses[0]] = w.hasSelf() && w.selfPlaying();
                presses[0]++;
            }
            lastKeys[0] = k;
            if (!(w.hasSelf() && w.selfPlaying())) {
                return;
            }
            for (int i = 0; i < w.shotCount() && n[0] < MAX; i++) {
                int kind = w.shotKind(i);
                if (w.shotColor(i) == Records.ObjColor.OWN
                    || kind != Records.ShotKind.SHOT && kind != Records.ShotKind.CANNON_SHOT) {
                    continue; // own bullets are the provisional shots; cluster fragments and pulses are not judged
                }
                fx[n[0]] = w.shotX(i);
                fy[n[0]] = w.shotY(i);
                fref[n[0]] = r.client.connection().predictedTick(t);
                frender[n[0]] = w.renderTick();
                fsrcTick[n[0]] = w.shotSourceTick(i);
                fsrcX[n[0]] = w.shotSourceX(i);
                fsrcY[n[0]] = w.shotSourceY(i);
                n[0]++;
            }
        };
        rig.runUntil(spawn + seconds * LatencyRig.SEC);
        double ww = world[0];
        double wh = world[1];
        boolean wrap = world[2] != 0;
        double[] err = new double[n[0]];
        int ne = 0;
        long unmatched = 0;
        long gone = 0;
        double[] p = new double[2];
        for (int i = 0; i < n[0]; i++) {
            // the drawn shot is the server bullet its source record stands for (the record's half-pixel position is
            // the server's at that tick)
            int id = fsrcTick[i] == Integer.MIN_VALUE ? -1
                : truth.otherShotIdAt(fsrcTick[i], fsrcX[i], fsrcY[i], MATCH_PX, ww, wh, wrap);
            if (id < 0) {
                unmatched++;
            } else if (truth.otherShotAt(id, fref[i], ww, wh, wrap, p)) {
                err[ne++] = MotionMetrics.distance(fx[i], fy[i], p[0], p[1], ww, wh, wrap);
            } else {
                gone++;
            }
        }
        double[] react = new double[presses[0]];
        int nr = 0;
        for (int i = 0; i < presses[0]; i++) {
            if (!pressOk[i]) {
                continue;
            }
            long best = Long.MAX_VALUE;
            int before = Integer.MIN_VALUE;
            for (Map.Entry<Integer, Long> e : tickTime.entrySet()) {
                if (e.getValue() < pressAt[i] && e.getKey() > before) {
                    before = e.getKey();
                }
            }
            if (truth.shieldUp(before)) {
                continue; // the server's shield was already up when the key went down
            }
            for (Map.Entry<Integer, Long> e : tickTime.entrySet()) {
                long tt = e.getValue();
                if (tt >= pressAt[i] && tt < best && truth.shieldUp(e.getKey())
                    && tt - pressAt[i] < 2 * LatencyRig.SEC) {
                    best = tt;
                }
            }
            if (best != Long.MAX_VALUE) {
                react[nr++] = (best - pressAt[i]) / 1e6;
            }
        }
        return new Result(new Stats(err, ne), n[0], unmatched, gone, new Stats(react, nr), nr);
    }
}
