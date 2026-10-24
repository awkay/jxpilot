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
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.net.InputHistory;
import com.fulcrologic.jxpilot.common.sim.KernelWorld;
import com.fulcrologic.jxpilot.common.sim.ShipBody;

/**
 * What the own-ship predictor reads from a JXPilot (protocol v2) connection: the server's ship state and kernel
 * world ({@link com.fulcrologic.jxpilot.client.net.KernelSync}), the input entries sent for each predicted tick ({@link InputHistory}), the predicted
 * clock, and the entry being collected now. The XPilot-protocol connection has none of these, so it has no source
 * and the own ship is drawn from the snapshot timeline.
 */
public interface PredictionSource {
    /**
     * Whether the newest PREDICT can be used: its rare part matches the core and the key masks are known (the
     * {@code predict*} accessors below are then meaningful).
     */
    boolean predictValid();

    /** PREDICTs received so far (a change means a new one). */
    long predicts();

    /** The tick the newest PREDICT's state is after. */
    int predictTick();

    /** The server's own ship after {@link #predictTick()}. */
    ShipBody predictBody();

    /** The server's C frame clock after that tick. */
    long predictFrames();

    /** The server's round delay after that tick. */
    int predictRoundDelay();

    /** The kernel world (map geometry and ship parameters) from KERNEL_CONFIG; null before one. */
    KernelWorld kernelWorld();

    /** The kernel options from KERNEL_CONFIG; null before one. */
    Options kernelOptions();

    /**
     * The server's visible options from the config transfer (shotLife, predictableBounces and the shot rules the
     * kernel world does not carry); null before it.
     */
    Options serverOptions();

    /** The entries sent, by predicted tick. */
    InputHistory input();

    /** The predicted tick now (fractional; NaN before the clock runs). */
    double predictedTick(long nowNanos);

    /** The newest final entry's tick (entry N is final once the clock reached N); meaningless before one. */
    int finalTick();

    /** Whether any entry has been final yet. */
    boolean haveFinal();

    /** Keys held in the entry being collected (the one after {@link #finalTick}). */
    long collectingKeys();

    /** Keys that went down and up again during the entry being collected. */
    long collectingTaps();

    /** Pointer-control turn summed over the entry being collected. */
    int collectingAim();

    /** The own player id. */
    int ownId();

    /** Where the predictor's correction figures go (the HUD net line); may be null. */
    NetDiagnostics diagnostics();
}
