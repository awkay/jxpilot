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
package com.fulcrologic.jxpilot.common.sim;

import java.util.function.DoubleSupplier;

/**
 * The bounce tie-break source of {@code predictableBounces} (a JXPilot deviation, DEVIATIONS-server.md): instead of
 * the world RNG (C {@code rfrac()}, which a client cannot follow) the draws for a ship's move in tick T come from a
 * generator seeded by (T, player id), so the owner's predictor makes the same choices. One preallocated instance,
 * reseeded in place before each ship's move ({@link #reseed}); allocation free.
 */
public final class SeededTieBreak implements DoubleSupplier {
    private long state;

    /** Start the draws of {@code playerId}'s move in net tick {@code tick}. */
    public void reseed(int tick, int playerId) {
        long z = ((long) tick << 32 | (playerId & 0xFFFF_FFFFL)) + 0x9E37_79B9_7F4A_7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
        state = z ^ (z >>> 31);
    }

    /** BEHAVIOR: the next draw in [0, 1) (SplitMix64 steps, the top 53 bits). */
    @Override
    public double getAsDouble() {
        long z = state += 0x9E37_79B9_7F4A_7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
}
