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
package com.fulcrologic.jxpilot.client.net;

import com.fulcrologic.jxpilot.common.net.InputHistory;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;
import com.fulcrologic.jxpilot.common.sim.ShipBody;

import java.nio.ByteBuffer;

/**
 * What the own-ship predictor (phase 1c) starts from, as the server sends it to the owner (protocol v2): the
 * {@link ShipBody} from the PREDICT sections of the newest snapshot, with the C frame clock and roundDelay it was
 * taken at. Fed by {@link ClientConnection}, which owns the input history the key flags refer to. Allocation free.
 */
public final class KernelSync {
    private final SnapshotReader reader = new SnapshotReader();
    private final Records.Predict predict = new Records.Predict();
    private final Records.PredictRare rare = new Records.PredictRare();
    private final Records.PredictKeys keys = new Records.PredictKeys();
    private final ShipBody body = new ShipBody();
    private boolean have;
    private int tick;
    private long frames;
    private int roundDelay;
    private int coreVersion;
    private int rareVersion = -1;
    private boolean keysKnown;
    private long predicts;

    /** Forget the ship (a new session). */
    public void reset() {
        have = false;
        rareVersion = -1;
        keysKnown = false;
        predicts = 0;
    }

    /**
     * Read the PREDICT sections of a snapshot (part 0 of a tick newer than every one before), the packet body at the
     * buffer's position (nothing is moved).
     * <p>
     * BEHAVIOR: PREDICT decodes the core block into {@link #body}; its key flags refer to the client's own entry for
     * the header's inputTick ({@code input}); when that entry is no longer (or not yet) in the history, the keys are
     * unknown unless a PREDICT_KEYS section carries them. PREDICT_RARE decodes the rare block and records its
     * version. A snapshot without PREDICT changes nothing.
     */
    public void onSnapshot(ByteBuffer in, InputHistory input) {
        if (!reader.begin(in)) {
            return;
        }
        int inputTick = reader.inputTick();
        boolean known = input.has(inputTick);
        long applied = known ? input.keys(inputTick) : 0;
        boolean got = false;
        boolean keysSent = false;
        while (reader.nextSection()) {
            int n = reader.count();
            if (n == 0) {
                continue;
            }
            switch (reader.type()) {
                case Section.PREDICT -> {
                    Records.Predict p = predict.wrap(reader, 0);
                    frames = p.frames();
                    roundDelay = p.roundDelay();
                    coreVersion = p.read(body, applied);
                    got = true;
                }
                case Section.PREDICT_RARE -> {
                    Records.PredictRare r = rare.wrap(reader, 0);
                    r.read(body);
                    rareVersion = r.version();
                }
                case Section.PREDICT_KEYS -> {
                    keys.wrap(reader, 0).read(body);
                    keysSent = true;
                }
                default -> { }
            }
        }
        if (got) {
            have = true;
            tick = reader.tick();
            keysKnown = known || keysSent;
            predicts++;
        }
    }

    /** The own ship as of {@link #tick()} (valid only when {@link #valid()}). */
    public ShipBody body() { return body; }

    /** Whether a PREDICT has arrived, its rare block is the version it refers to and its key masks are known. */
    public boolean valid() {
        return have && rareVersion == coreVersion && keysKnown;
    }

    /** Whether any PREDICT has arrived since the reset. */
    public boolean have() { return have; }

    /** The snapshot tick of the newest PREDICT. */
    public int tick() { return tick; }

    /** The C frame clock at that tick (ShipKernel's {@code frames()}, not the net tick). */
    public long frames() { return frames; }

    public int roundDelay() { return roundDelay; }

    /** PREDICT sections read since the reset. */
    public long predicts() { return predicts; }
}
