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
package com.fulcrologic.jxpilot.server;

import com.fulcrologic.jxpilot.server.sim.Digest;
import com.fulcrologic.jxpilot.server.sim.RandomPilot;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Determinism")
class DeterminismTest {
    static long run(String map, int seed, int ticks) {
        Simulation s = TestWorlds.shipped(map, seed);
        for (int i = 0; i < 6; i++) {
            if (s.ships.join("p" + i, i % 2) == null) {
                break;
            }
        }
        RandomPilot pilot = new RandomPilot(seed + 1, s.world.players.length);
        for (int t = 0; t < ticks; t++) {
            s.tick(pilot.next());
        }
        assertTrue(s.world.events.burstSeq() > 0, map + ": the run produced explosions");
        return Digest.of(s.world);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"default.xp", "war.xp", "newdarkhell.xp", "doggy.xp", "blood-music2.xp"})
    @DisplayName("same map, seed and inputs give the same digest; another seed a different one")
    void sameSeedSameDigest(String map) {
        long a = run(map, 11, 1500);
        long b = run(map, 11, 1500);
        long c = run(map, 12, 1500);
        assertAll(
            () -> assertEquals(a, b, map + ": identical runs end in identical states"),
            () -> assertNotEquals(a, c, map + ": a different seed diverges"));
    }
}
