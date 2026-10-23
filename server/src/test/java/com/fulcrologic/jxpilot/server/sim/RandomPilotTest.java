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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RandomPilot")
class RandomPilotTest {
    @Test
    @DisplayName("produces the same masks for the same seed, only pilot keys, and every pilot key over time")
    void masks() {
        RandomPilot a = new RandomPilot(5, 3);
        RandomPilot b = new RandomPilot(5, 3);
        RandomPilot c = new RandomPilot(6, 3);
        long allowed = 0;
        for (long k : RandomPilot.KEYS) {
            allowed |= k;
        }
        long seen = 0;
        int thrustFlips = 0;
        long prevThrust = 0;
        boolean differs = false;
        boolean same = true;
        for (int t = 0; t < 2000; t++) {
            long[] ma = a.next().clone();
            long[] mb = b.next();
            long[] mc = c.next();
            same &= Arrays.equals(ma, mb);
            differs |= !Arrays.equals(ma, mc);
            long thrust = ma[0] & RandomPilot.KEYS[0];
            if (thrust != prevThrust) {
                thrustFlips++;
            }
            prevThrust = thrust;
            for (long m : ma) {
                seen |= m;
                assertEquals(0, m & ~allowed, "only pilot keys are ever pressed");
            }
        }
        long seenF = seen;
        long allowedF = allowed;
        boolean sameF = same;
        boolean differsF = differs;
        int flips = thrustFlips;
        assertAll(
            () -> assertTrue(sameF, "equal seeds give equal masks every tick"),
            () -> assertTrue(differsF, "another seed gives other masks"),
            () -> assertEquals(allowedF, seenF, "every pilot key gets pressed at some point"),
            () -> assertTrue(flips > 40 && flips < 130, "THRUST flips with probability 0.04 per tick (~80 of 2000): " + flips),
            () -> assertEquals(3, new RandomPilot(1, 3).next().length, "next() returns one mask per slot"));
    }
}
