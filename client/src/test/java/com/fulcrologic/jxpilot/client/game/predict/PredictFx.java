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
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.net.InputHistory;
import com.fulcrologic.jxpilot.common.sim.Click;
import com.fulcrologic.jxpilot.common.sim.KernelWorld;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.SeededTieBreak;
import com.fulcrologic.jxpilot.common.sim.ShipBody;
import com.fulcrologic.jxpilot.common.sim.ShipKernel;

/** Fixtures for the own-ship predictor: a settable {@link PredictionSource}, maps, ships and a reference stepper. */
final class PredictFx {
    private PredictFx() {}

    /** 12x12 open map (row 0 is the top). */
    static final String[] OPEN = {
        "            ", "            ", "            ", "            ", "            ", "            ",
        "            ", "            ", "            ", "            ", "            ", "            "};

    /** 12x12 map, a wall column at x = 8. */
    static final String[] WALL = {
        "        x   ", "        x   ", "        x   ", "        x   ", "        x   ", "        x   ",
        "        x   ", "        x   ", "        x   ", "        x   ", "        x   ", "        x   "};

    static Options options(String extra, String... rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("mapWidth: ").append(rows[0].length()).append('\n');
        sb.append("mapHeight: ").append(rows.length).append('\n');
        sb.append(extra).append('\n');
        sb.append("mapData: \\multiline: EndOfMapdata\n");
        for (String r : rows) {
            sb.append(r).append('\n');
        }
        sb.append("EndOfMapdata\n");
        Options o = new Options();
        ConfigParser.parse(sb.toString(), "test", OptionOrigin.MAP, o);
        return o;
    }

    /** A playing default ship at pixel (x, y), heading {@code dir}, at rest, with fuel and 256 shots allowed. */
    static ShipBody ship(double x, double y, int dir) {
        ShipBody p = new ShipBody();
        p.status = Obj.PLAYING;
        p.mass = 25;
        p.emptyMass = 20;
        p.fuel = 256000;
        p.tankFuel[0] = 256000;
        p.fuelMax = 256000 * 4;
        p.initPixels(x, y);
        p.dir = dir;
        p.floatDir = dir;
        p.lastWallTouch = -1;
        p.power = 45;
        p.turnSpeed = 35;
        p.turnResistance = 0.12f;
        p.shotMax = 256;
        p.have = com.fulcrologic.jxpilot.common.sim.Has.SHOT;
        return p;
    }

    static double px(ShipBody b) {
        return b.cx / (double) Click.CLICK;
    }

    static double py(ShipBody b) {
        return b.cy / (double) Click.CLICK;
    }

    /** A settable source: one PREDICT, the input history, the clock and the entry being collected. */
    static class Source implements PredictionSource {
        final InputHistory input = new InputHistory();
        final NetDiagnostics diag = new NetDiagnostics();
        KernelWorld world;
        Options options;
        boolean valid = true;
        long predicts;
        int tick;
        final ShipBody body = new ShipBody();
        long frames;
        int roundDelay;
        double clock = Double.NaN;
        int finalTick;
        boolean haveFinal;
        long keys;
        long taps;
        int aim;
        int id = 3;

        Source(Options o) {
            options = o;
            world = KernelWorld.of(o);
        }

        /** A new PREDICT of {@code s} after tick {@code t} with C frame clock {@code f}. */
        void predict(int t, long f, ShipBody s) {
            tick = t;
            frames = f;
            body.copyFrom(s);
            predicts++;
        }

        /** Entries t0..t1 with these keys (no taps, no aim), final up to t1, the clock at t1 + frac. */
        void hold(int t0, int t1, long k, double frac) {
            for (int t = t0; t <= t1; t++) {
                input.set(t, k, 0, 0);
            }
            finalTick = t1;
            haveFinal = true;
            clock = t1 + frac;
        }

        @Override public boolean predictValid() { return valid; }
        @Override public long predicts() { return predicts; }
        @Override public int predictTick() { return tick; }
        @Override public ShipBody predictBody() { return body; }
        @Override public long predictFrames() { return frames; }
        @Override public int predictRoundDelay() { return roundDelay; }
        @Override public KernelWorld kernelWorld() { return world; }
        @Override public Options kernelOptions() { return options; }
        @Override public Options serverOptions() { return options; }
        @Override public InputHistory input() { return input; }
        @Override public double predictedTick(long nowNanos) { return clock; }
        @Override public int finalTick() { return finalTick; }
        @Override public boolean haveFinal() { return haveFinal; }
        @Override public long collectingKeys() { return keys; }
        @Override public long collectingTaps() { return taps; }
        @Override public int collectingAim() { return aim; }
        @Override public int ownId() { return id; }
        @Override public NetDiagnostics diagnostics() { return diag; }
    }

    /**
     * The server's order for one human tick (SimGameHost input then the ship's kernel step), independent of the
     * predictor: the C frame clock, the pointer turn, keys | taps, keys, {@link ShipKernel#step}.
     */
    static final class Reference {
        final KernelWorld world;
        final ShipKernel kernel;
        final PredictEvents ev = new PredictEvents(new SeededTieBreak());

        Reference(KernelWorld w) {
            world = w;
            kernel = new ShipKernel(w, () -> 0.5);
        }

        void step(ShipBody b, long frames, long keys, long taps, int aim) {
            world.frames = frames;
            world.roundDelay = 0;
            ev.beginStep(0, false);
            if (aim != 0) {
                kernel.keys.pointerMove(b, aim, ev);
            }
            if ((taps & ~keys) != 0) {
                kernel.keys.apply(b, keys | (taps & ~keys), ev);
            }
            kernel.keys.apply(b, keys, ev);
            kernel.step(b, keys, ev);
        }
    }
}
