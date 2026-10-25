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

import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.SeededTieBreak;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEHAVIOR (predictableBounces, a JXPilot deviation): off, a ship's bounce tie-break draws from the world RNG (C
 * rfrac); on, from a source reseeded by (net tick, player id) at the start of each ship's flying phase in a real
 * tick, so the choice is the same whatever the world RNG holds. SeededTieBreak: the same seed gives the same draws, in [0, 1); different
 * ticks or ids give different ones.
 */
@DisplayName("predictableBounces")
class PredictableBouncesTest {
    /** Like the kernel tests' LEFT_WALL: a wall at block x 1, a base at (5, 2). */
    static final String[] LEFT_WALL = {"       ", " x     ", " x   _ ", " x     ", "       "};

    /** A corner: the wall block (3, 3) whose lower-left corner the nose hits exactly (two feasible faces). */
    static final String[] CORNER = {"       ", "       ", "   x   ", "       ", " _     ", "       "};

    /**
     * A real sim tick (Simulation.tick: keys, Ships.update, ShipKernel.fly) of a ship flying into a tie: two hull
     * points on the wall together (LEFT_WALL) or a corner with two feasible bounces (CORNER).
     *
     * @return x, y, vx, vy after the tick, the seeded source's tick, id and draws, and the ship's id
     */
    static double[] tick(String[] map, boolean predictable, int worldSeed, int cx, int cy, float vx, float vy) {
        Simulation s = new Simulation(new com.fulcrologic.jxpilot.server.world.World(TestWorlds.options(
            "framesPerSecond: 60\ngravity: 0\nmaxRobots: 0\npredictableBounces: " + predictable + "\n", map),
            worldSeed));
        Player p = TestWorlds.join(s, "Ann", 0);
        p.used = 0;
        p.setClicks(cx, cy);
        p.remember();
        p.dir = 0;
        p.floatDir = 0;
        p.vx = vx;
        p.vy = vy;
        p.velocity = (float) Math.hypot(vx, vy);
        s.tick(7, new long[s.world.players.length]);
        return new double[] {p.cx, p.cy, p.vx, p.vy, s.tieBreak.seedTick(), s.tieBreak.seedId(), s.tieBreak.draws(),
            p.id};
    }

    static final int BC = 35 * 64;

    @Test
    @DisplayName("a real tick: on, the fly phase reseeds from (tick, id) and the tie draws from it, whatever the"
        + " world RNG; off, the seeded source is never drawn")
    void realTick() {
        double[] off = tick(LEFT_WALL, false, 1, 4480 + 576 + 64, 5600, -2, 0);
        double[] on1 = tick(LEFT_WALL, true, 1, 4480 + 576 + 64, 5600, -2, 0);
        double[] on2 = tick(LEFT_WALL, true, 99, 4480 + 576 + 64, 5600, -2, 0);
        double[] corner = tick(CORNER, true, 1, 3 * BC - 960, 3 * BC, 1, 1);
        assertAll(
            () -> assertEquals(0, off[6], "off: the seeded source is not drawn"),
            () -> assertEquals(7, on1[4], "on: reseeded with the net tick"),
            () -> assertEquals(on1[7], on1[5], "and the ship's id"),
            () -> assertTrue(on1[6] >= 1, "the two-point tie drew from it (" + on1[6] + ")"),
            () -> assertEquals(on1[0], on2[0], "the same x whatever the world seed"),
            () -> assertEquals(on1[2], on2[2], "and vx"),
            () -> assertEquals(on1[3], on2[3], "and vy"),
            () -> assertEquals(7, corner[4], "the corner: reseeded with the tick"),
            () -> assertTrue(corner[6] >= 1, "and its two feasible faces drew from it (" + corner[6] + ")"));
    }

    @Test
    @DisplayName("SeededTieBreak: deterministic per (tick, id), in [0, 1), different seeds differ")
    void seeded() {
        SeededTieBreak a = new SeededTieBreak();
        SeededTieBreak b = new SeededTieBreak();
        a.reseed(10, 3);
        b.reseed(10, 3);
        double a1 = a.getAsDouble();
        double a2 = a.getAsDouble();
        double b1 = b.getAsDouble();
        b.reseed(11, 3);
        double tick = b.getAsDouble();
        b.reseed(10, 4);
        double id = b.getAsDouble();
        boolean inRange = true;
        SeededTieBreak c = new SeededTieBreak();
        for (int i = 0; i < 10_000; i++) {
            c.reseed(i, i % 7);
            double v = c.getAsDouble();
            inRange &= v >= 0 && v < 1;
        }
        boolean range = inRange;
        assertAll(
            () -> assertEquals(a1, b1, "the same seed: the same first draw"),
            () -> assertNotEquals(a1, a2, "successive draws differ"),
            () -> assertNotEquals(a1, tick, "another tick differs"),
            () -> assertNotEquals(a1, id, "another id differs"),
            () -> assertTrue(range, "draws are in [0, 1)"),
            () -> assertFalse(Double.isNaN(a1), "a number"));
    }
}
