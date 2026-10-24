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

import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * The server's state after every tick, as the metrics need it: the own player's wire-visible state (heading
 * {@code dir}, velocity, position) and every player's position. The single place that reads server
 * {@link Player} fields, so a refactor of them touches only this class.
 */
final class ServerTruth {
    static final int MAX_TICKS = 1 << 15;

    private final int ownId;
    private final int[] dir = new int[MAX_TICKS];
    private final float[] vx = new float[MAX_TICKS];
    private final float[] vy = new float[MAX_TICKS];
    private final long[] shotTime = new long[MAX_TICKS];
    private final int[] shots = new int[MAX_TICKS];
    /** The press's own shot (the first own shot of the fire tick), followed while it lives. */
    private final double[] pressX = new double[MAX_TICKS];
    private final double[] pressY = new double[MAX_TICKS];
    private final boolean[] pressAlive = new boolean[MAX_TICKS];
    private int pressHandle = -1;
    private int pressBorn = -1;
    private int pressDied = -1;
    private double pressLife;
    private final Map<Integer, double[]> xs = new HashMap<>();
    private final Map<Integer, double[]> ys = new HashMap<>();
    private final Map<Integer, boolean[]> present = new HashMap<>();
    private int last = -1;

    ServerTruth(int ownId) {
        this.ownId = ownId;
    }

    /** Record the state after server tick {@code tick} (ticks count from 1, as in snapshots). */
    void record(World w, int tick) {
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            put(tick, p.id, VirtualServer.px(p.cx), VirtualServer.px(p.cy), p.isAlive(), p.dir, p.vx, p.vy);
            if (p.id == ownId && tick < MAX_TICKS) {
                putShots(tick, p.shotTime, p.shots);
            }
        }
    }

    /** Record one player's state after {@code tick}; the newest tick put is {@link #lastTick()}. */
    void put(int tick, int id, double x, double y, boolean isAlive, int heading, float velX, float velY) {
        if (tick >= MAX_TICKS) {
            return;
        }
        xs.computeIfAbsent(id, i -> new double[MAX_TICKS])[tick] = x;
        ys.computeIfAbsent(id, i -> new double[MAX_TICKS])[tick] = y;
        present.computeIfAbsent(id, i -> new boolean[MAX_TICKS])[tick] = isAlive;
        if (id == ownId) {
            dir[tick] = heading;
            vx[tick] = velX;
            vy[tick] = velY;
        }
        last = Math.max(last, tick);
    }

    int lastTick() {
        return last;
    }

    /** Record the own player's last fire frame ({@code shotTime}) and live shot count after {@code tick}. */
    void putShots(int tick, long lastFire, int liveShots) {
        if (tick < MAX_TICKS) {
            shotTime[tick] = lastFire;
            shots[tick] = liveShots;
        }
    }

    /**
     * Start following the press's shot at its fire tick: the first own OBJ_SHOT in the pool (the baseline never
     * fires, so every own shot of this tick is the press's). Then call {@link #followPressShot} every tick.
     */
    void startPressShot(World w, int tick) {
        Objects o = w.objects;
        for (int i = 0; i < o.count(); i++) {
            int h = o.active[i];
            if (o.type[h] == Obj.SHOT && o.owner[h] == ownId) {
                pressHandle = h;
                pressBorn = tick;
                pressLife = o.life[h];
                putPress(tick, VirtualServer.px(o.cx[h]), VirtualServer.px(o.cy[h]), true);
                return;
            }
        }
    }

    /**
     * Record where the press's shot is after {@code tick}: alive while its handle is live, still an own OBJ_SHOT
     * and its life has not grown and fell by at most 1.5 since the tick before (a handle freed and reused by a later
     * autofire shot starts again at full life); the first tick it is not marks its death, after which it stays dead.
     */
    void followPressShot(World w, int tick) {
        if (pressHandle < 0 || pressDied >= 0 || tick <= pressBorn) {
            return;
        }
        Objects o = w.objects;
        int h = pressHandle;
        boolean live = false;
        for (int i = 0; i < o.count() && !live; i++) {
            live = o.active[i] == h;
        }
        // a reused handle (a later autofire shot) starts again at full life: the press's own life only runs down
        if (live && o.type[h] == Obj.SHOT && o.owner[h] == ownId && o.life[h] <= pressLife
            && o.life[h] >= pressLife - 1.5) {
            pressLife = o.life[h];
            putPress(tick, VirtualServer.px(o.cx[h]), VirtualServer.px(o.cy[h]), true);
        } else {
            pressDied = tick;
            putPress(tick, 0, 0, false);
        }
    }

    /** Record the press's shot at {@code tick} (also used by tests). */
    void putPress(int tick, double x, double y, boolean alive) {
        if (tick < MAX_TICKS) {
            pressX[tick] = x;
            pressY[tick] = y;
            pressAlive[tick] = alive;
            if (pressBorn < 0 && alive) {
                pressBorn = tick;
            }
            if (!alive && pressDied < 0 && pressBorn >= 0) {
                pressDied = tick;
            }
        }
    }

    /** The first tick the press's shot was dead, -1 while it lives (or before it was fired). */
    int pressDied() {
        return pressDied;
    }

    /** The tick the press's shot was fired, -1 before. */
    int pressBorn() {
        return pressBorn;
    }

    /**
     * Where the press's shot is at fractional tick {@code t} (into {@code out}), as a client interpolating the
     * snapshots around it draws it: linear between ticks a = floor(t) and a + 1 when it lives at both (wrap-aware),
     * else where tick a has it (a shot that died before a + 1 stays where a had it).
     *
     * @return false when it did not live at tick a
     */
    boolean pressShotAt(double t, double worldW, double worldH, boolean wrap, double[] out) {
        int a = (int) Math.floor(t);
        if (a < 0 || a >= MAX_TICKS || !pressAlive[a]) {
            return false;
        }
        out[0] = pressX[a];
        out[1] = pressY[a];
        if (a + 1 < MAX_TICKS && pressAlive[a + 1]) {
            double f = t - a;
            out[0] += f * XMath.wrapDelta(pressX[a + 1] - pressX[a], worldW, wrap);
            out[1] += f * XMath.wrapDelta(pressY[a + 1] - pressY[a], worldH, wrap);
        }
        return true;
    }

    /** BEHAVIOR: true when the own player's last fire frame differs between the recordings at {@code tick}. */
    boolean shotTimeDiffers(ServerTruth o, int tick) {
        return shotTime[tick] != o.shotTime[tick];
    }

    /** BEHAVIOR: true when the own player has more live shots here than in {@code o} at {@code tick}. */
    boolean moreShots(ServerTruth o, int tick) {
        return shots[tick] > o.shots[tick];
    }

    /** BEHAVIOR: true when the own player's heading differs between the two recordings at {@code tick}. */
    boolean headingDiffers(ServerTruth o, int tick) {
        return dir[tick] != o.dir[tick];
    }

    /** BEHAVIOR: true when the own player's velocity (as sent: floats) differs at {@code tick}. */
    boolean velocityDiffers(ServerTruth o, int tick) {
        return Float.floatToIntBits(vx[tick]) != Float.floatToIntBits(o.vx[tick])
            || Float.floatToIntBits(vy[tick]) != Float.floatToIntBits(o.vy[tick]);
    }

    /**
     * BEHAVIOR: true when player {@code id} moved more than {@code px} (wrap-aware) between two consecutive
     * recorded ticks in [{@code from}, {@code to}]: a teleport (wormhole, hyperjump, respawn), not a drawing error.
     */
    boolean jumped(int id, int from, int to, double px, double worldW, double worldH, boolean wrap) {
        double[] x = xs.get(id);
        double[] y = ys.get(id);
        if (x == null) {
            return false;
        }
        for (int k = Math.max(2, from); k <= Math.min(to, last); k++) {
            double dx = XMath.wrapDelta(x[k] - x[k - 1], worldW, wrap);
            double dy = XMath.wrapDelta(y[k] - y[k - 1], worldH, wrap);
            if (dx * dx + dy * dy > px * px) {
                return true;
            }
        }
        return false;
    }

    /**
     * The position of player {@code id} at fractional tick {@code t}, linear between the recorded ticks around it
     * (wrap-aware), into {@code out[0..1]}.
     *
     * @return false when either tick is unrecorded or the player was not alive at either
     */
    boolean positionAt(int id, double t, double worldW, double worldH, boolean wrap, double[] out) {
        double[] x = xs.get(id);
        double[] y = ys.get(id);
        boolean[] in = present.get(id);
        int a = (int) Math.floor(t);
        int b = a + 1;
        if (x == null || a < 1 || b > last || !in[a] || !in[b]) {
            return false;
        }
        double f = t - a;
        out[0] = x[a] + f * XMath.wrapDelta(x[b] - x[a], worldW, wrap);
        out[1] = y[a] + f * XMath.wrapDelta(y[b] - y[a], worldH, wrap);
        return true;
    }
}
