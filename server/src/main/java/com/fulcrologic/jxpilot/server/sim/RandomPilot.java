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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.ControlKey;

/**
 * Seeded scripted inputs for headless runs, benchmarks and determinism tests: each slot holds a key mask
 * of THRUST, TURN_LEFT, TURN_RIGHT, FIRE_SHOT and SHIELD, and every tick each of those keys flips with a
 * small probability. Same seed, same inputs. Allocation free.
 */
public final class RandomPilot {
    static final long[] KEYS = {
        ControlKey.THRUST.mask(), ControlKey.TURN_LEFT.mask(), ControlKey.TURN_RIGHT.mask(),
        ControlKey.FIRE_SHOT.mask(), ControlKey.SHIELD.mask()
    };
    /** Per-tick flip probability of each key (THRUST, LEFT, RIGHT, FIRE, SHIELD). */
    static final float[] FLIP = {0.04f, 0.05f, 0.05f, 0.08f, 0.01f};

    private final Rng rng;
    private final long[] masks;

    public RandomPilot(int seed, int slots) {
        rng = new Rng(seed);
        masks = new long[slots];
    }

    /**
     * Advance every slot's mask by one tick and return the array (owned by this object).
     * <p>
     * BEHAVIOR: only the five pilot keys are ever set; the sequence depends only on the seed.
     */
    public long[] next() {
        for (int s = 0; s < masks.length; s++) {
            for (int k = 0; k < KEYS.length; k++) {
                if (rng.nextFloat() < FLIP[k]) {
                    masks[s] ^= KEYS[k];
                }
            }
        }
        return masks;
    }
}
