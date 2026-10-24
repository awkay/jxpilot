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

import com.fulcrologic.jxpilot.common.map.Wormhole;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.ShipFx.C_FRAMES;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.sim;
import static com.fulcrologic.jxpilot.server.sim.ShipFx.tick;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wormhole travel: the WARPING part of the update.c player loop (lines 1062-1188). */
class ShipsWarpTest {
    /**
     * Wormholes (x-major order): 0 = (2,5) normal, 1 = (5,3) IN only, 2 = (5,7) normal, 3 = (8,5) normal.
     * Base at (5,5).
     */
    static final String[] WORMS = {
        "xxxxxxxxxxx",
        "x         x",
        "x         x",
        "x    @    x",
        "x         x",
        "x @  _  @ x",
        "x         x",
        "x    (    x",
        "x         x",
        "x         x",
        "xxxxxxxxxxx",
    };

    /** A seed whose first rfrac is below (want = true) or at least (false) 0.10. */
    static int seed(boolean below) {
        for (int s = 1; ; s++) {
            if ((new Rng(s).nextFloat() < 0.10f) == below) {
                return s;
            }
        }
    }

    static Player warping(Simulation s, double vx, double vy, int seed) {
        Player p = s.ships.join("a", 0);
        p.status |= Obj.WARPING;
        p.wormHoleHit = 0;
        p.vx = (float) vx;
        p.vy = (float) vy;
        s.world.wormCountdown[0] = 0;
        s.world.rng.seed(seed);
        return p;
    }

    @Nested
    @DisplayName("warp")
    class Warp {
        @Test
        void theDestinationIsTheWormholeClosestToTheLineOfFlightAhead() {
            Simulation s = sim(C_FRAMES, WORMS);
            World w = s.world;
            int seed = seed(false);
            Player p = warping(s, 5, 0, seed);
            s.ships.warp(p);
            // flying right from (2,5): (8,5) is on the line (proximity 0), (5,7) is off it (5*70 = 350)
            assertAll(
                () -> assertEquals(Wormhole.Type.IN, w.map.wormholes().get(1).type(), "wormhole 1 is in-only"),
                () -> assertEquals(3, p.wormHoleDest, "the wormhole straight ahead is chosen"),
                () -> assertEquals((int) (297.5 * 64), p.cx, "the ship appears at its centre (x)"),
                () -> assertEquals((int) (192.5 * 64), p.cy, "the ship appears at its centre (y)"),
                () -> assertEquals(3, w.wormLastDest[0], "the source remembers the destination"),
                () -> assertEquals(64.0, w.wormCountdown[0], "for WORMCOUNT frames without wormTime"),
                () -> assertEquals(Obj.WARPED, p.status & (Obj.WARPED | Obj.WARPING), "WARPED, no longer WARPING"),
                () -> assertEquals(5.0, p.vx, "WORM_BRAKE_FACTOR 1 keeps the velocity"));
        }

        @Test
        void flyingUpPicksTheUpperWormholeAndSkipsInOnlyOnes() {
            Simulation s = sim(C_FRAMES, WORMS);
            Player p = warping(s, 0, 5, seed(false));
            s.ships.warp(p);
            // (5,7): proximity |5*105| = 525; (8,5): dot 0 counts as ahead, proximity |5*210| = 1050
            assertEquals(2, p.wormHoleDest, "the upper wormhole is closer to the line of flight");
        }

        @Test
        void withNothingAheadARandomOutWormholeIsChosen() {
            Simulation s = sim(C_FRAMES, WORMS);
            int seed = seed(false);
            Player p = warping(s, -5, 0, seed);
            s.ships.warp(p);
            Rng r = new Rng(seed);
            r.nextFloat(); // the 10% test
            int j;
            do {
                j = (int) (r.nextFloat() * 4);
            } while (j == 1 || j == 0);
            int expected = j;
            assertEquals(expected, p.wormHoleDest, "update.c RANDOM_REAR_WORM: random, not IN, not the source");
        }

        @Test
        void tenPercentOfTheTimeARandomOtherWormholeIsChosen() {
            for (int k = 0; k < 3; k++) {
                int seed = k == 0 ? seed(true) : nextBelow(seed(true) + k * 1000);
                Simulation s = sim(C_FRAMES, WORMS);
                Player p = warping(s, 5, 0, seed);
                s.ships.warp(p);
                Rng r = new Rng(seed);
                r.nextFloat();
                int j;
                do {
                    j = (int) (r.nextFloat() * 4);
                } while (j == 1 || j == 0);
                int expected = j;
                int sd = seed;
                assertEquals(expected, p.wormHoleDest, "rfrac < 0.1: a random non-IN other wormhole, seed " + sd);
            }
        }

        @Test
        void whileCountingDownTheLastDestinationIsReused() {
            Simulation s = sim(C_FRAMES, WORMS);
            World w = s.world;
            Player p = warping(s, 5, 0, seed(true));
            w.wormCountdown[0] = 10;
            w.wormLastDest[0] = 2;
            s.ships.warp(p);
            assertAll(
                () -> assertEquals(2, p.wormHoleDest, "the remembered destination is used"),
                () -> assertEquals(64.0, w.wormCountdown[0], "and the countdown restarts"));
        }

        @Test
        void aRememberedDestinationEqualToTheSourceDoesNotRestartTheCountdown() {
            Simulation s = sim(C_FRAMES, WORMS);
            World w = s.world;
            Player p = warping(s, 5, 0, seed(false));
            w.wormCountdown[0] = 10;
            w.wormLastDest[0] = 0;
            s.ships.warp(p);
            assertAll(
                () -> assertEquals(0, p.wormHoleDest, "the ship stays at the source"),
                () -> assertEquals(10.0, w.wormCountdown[0], "the countdown is untouched (j == hit)"));
        }

        @Test
        void wormTimeSetsTheCountdownInFrames() {
            Simulation s = sim(C_FRAMES + "wormTime: 2\n", WORMS);
            World w = s.world;
            Player p = warping(s, 5, 0, seed(false));
            s.ships.warp(p);
            assertEquals(120.0, w.wormCountdown[0], "wormTime 2 s = 120 frames at 60 fps");
        }

        @Test
        void anInvalidWormholeJustEndsTheWarp() {
            Simulation s = sim(C_FRAMES, WORMS);
            Player p = warping(s, 5, 0, seed(false));
            p.wormHoleHit = -2;
            int x = p.cx;
            s.ships.warp(p);
            int noHit = p.status & (Obj.WARPING | Obj.WARPED);
            p.status |= Obj.WARPING;
            p.wormHoleHit = 4;
            s.ships.warp(p);
            assertAll(
                () -> assertEquals(0, noHit, "hit below -1 (-1 is a hyperjump): WARPING cleared, not WARPED"),
                () -> assertEquals(0, p.status & (Obj.WARPING | Obj.WARPED), "hit past the last wormhole: same"),
                () -> assertEquals(x, p.cx, "the ship stays put"),
                () -> assertEquals(-1, p.wormHoleDest, "no destination"));
        }

        @Test
        void aWarpingShipIsWarpedDuringItsUpdateAndTheCountdownRunsDown() {
            Simulation s = sim(C_FRAMES, WORMS);
            World w = s.world;
            Player p = warping(s, 5, 0, seed(false));
            tick(s, p, 0);
            assertAll(
                () -> assertEquals(3, p.wormHoleDest, "the update moved the ship through the wormhole"),
                () -> assertEquals(63.0, w.wormCountdown[0], "the countdown ran one frame in the same tick"),
                () -> assertEquals((int) (297.5 * 64) + 5 * 64, p.cx, "and the ship moved on from the exit"));
            tick(s, p, 0);
            assertAll(
                () -> assertEquals((int) (297.5 * 64) + 10 * 64, p.cx, "a WARPED ship is not warped again"),
                () -> assertEquals(62.0, w.wormCountdown[0], "the countdown keeps running"));
        }
    }

    @Nested
    @DisplayName("nearest wormhole ahead (RANDOM_REAR_WORM)")
    class NearestAhead {
        /** 0 = (2,5), 1 = (5,2), 2 = (5,5), 3 = (8,3), 4 = (8,7); base at (9,9). */
        static final String[] FIVE = {
            "xxxxxxxxxxx",
            "x        _x",
            "x         x",
            "x       @ x",
            "x         x",
            "x @  @    x",
            "x         x",
            "x       @ x",
            "x    @    x",
            "x         x",
            "xxxxxxxxxxx",
        };

        int dest(int hit, double vx, double vy, int seed) {
            Simulation s = sim(C_FRAMES, FIVE);
            Player p = s.ships.join("a", 0);
            p.status |= Obj.WARPING;
            p.wormHoleHit = hit;
            p.vx = (float) vx;
            p.vy = (float) vy;
            s.world.wormCountdown[hit] = 0;
            s.world.rng.seed(seed);
            s.ships.warp(p);
            return p.wormHoleDest;
        }

        @Test
        void proximityIsVyWxPlusVxWyAndTheFirstOfEqualsWins() {
            int seed = seed(false);
            // update.c: wx, wy = block offset * 35; ahead iff vx*wx + vy*wy >= 0; proximity |vy*wx + vx*wy|
            assertAll(
                // from 2 moving (5,0): 1 dot 0 prox 525; 3 and 4 dot 525 prox 350 (tie); 0 behind
                () -> assertEquals(3, dest(2, 5, 0, seed), "of the tied 3 and 4 the first is kept"),
                // from 0 moving (0,5): 2 dot 0 prox 525; 4 dot 350 prox 1050; 1 and 3 behind
                () -> assertEquals(2, dest(0, 0, 5, seed), "a perpendicular wormhole (dot 0) counts as ahead"),
                // from 0 moving (5,5): 1 prox |525 - 525| = 0; 2: 525; 3: 700; 4: 1400
                () -> assertEquals(1, dest(0, 5, 5, seed), "C's (not geometric) proximity picks 1"),
                // from 2 moving (-5,0): 0 straight ahead (prox 0); 1 dot 0 prox 525; 3, 4 behind
                () -> assertEquals(0, dest(2, -5, 0, seed), "wormhole 0 can be the nearest"));
        }

        @Test
        void whenNoneIsAheadTheRandomChoiceUsesTheRng() {
            // from 4 (8,7) moving (5,1): every other wormhole is behind (3, straight below, has dot -140)
            for (int seed = 1; seed < 40; seed++) {
                if (new Rng(seed).nextFloat() < 0.10f) {
                    continue;
                }
                Rng r = new Rng(seed);
                r.nextFloat();
                int j;
                do {
                    j = (int) (r.nextFloat() * 5);
                } while (j == 4);
                int expected = j;
                int sd = seed;
                assertEquals(expected, dest(4, 5, 1, seed), "random non-source wormhole, seed " + sd);
            }
        }
    }

    static int nextBelow(int from) {
        for (int s = from; ; s++) {
            if (new Rng(s).nextFloat() < 0.10f) {
                return s;
            }
        }
    }
}
