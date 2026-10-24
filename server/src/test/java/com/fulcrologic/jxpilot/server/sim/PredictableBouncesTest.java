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
 * rfrac); on, from a source seeded by (net tick, player id), so the world RNG is untouched and the choice is the
 * same whatever the world RNG holds. SeededTieBreak: the same seed gives the same draws, in [0, 1); different
 * ticks or ids give different ones.
 */
@DisplayName("predictableBounces")
class PredictableBouncesTest {
    /** Like the kernel tests' LEFT_WALL: a wall at block x 1, a base at (5, 2). */
    static final String[] LEFT_WALL = {"       ", " x     ", " x   _ ", " x     ", "       "};

    /** A ship whose two hull points hit the wall together (the kernel tests' tie-break case), moved once. */
    static double[] bounce(boolean predictable, int worldSeed) {
        return bounce(predictable, worldSeed, -1);
    }

    /**
     * As {@link #bounce(boolean, int)}; with {@code fly} &gt;= 0 moved by the tick's path (ShipKernel.fly), the
     * tie-break source first seeded from (fly, 1) instead of the tick's (what an earlier move would leave in it).
     */
    static double[] bounce(boolean predictable, int worldSeed, int fly) {
        Simulation s = new Simulation(new com.fulcrologic.jxpilot.server.world.World(TestWorlds.options(
            "framesPerSecond: 60\ngravity: 0\nmaxRobots: 0\npredictableBounces: " + predictable + "\n", LEFT_WALL),
            worldSeed));
        Player p = s.ships.join("Ann", 0);
        p.status = Obj.PLAYING;
        p.used = 0;
        p.have = 0;
        p.mass = 20;
        p.setClicks(4480 + 576 + 64, 5600);
        p.remember();
        p.dir = 0;
        p.floatDir = 0;
        p.vx = -2;
        p.vy = 0;
        p.velocity = 2;
        s.world.tick = 7;
        s.world.rng.seed(worldSeed);
        double before = s.world.rfrac();
        s.world.rng.seed(worldSeed);
        if (fly >= 0) {
            s.tieBreak.reseed(fly, 1);
            s.kernel.fly(p, s.effects);
        } else {
            s.mover.movePlayer(p);
        }
        double next = s.world.rfrac();
        return new double[] {p.cx, p.cy, p.vx, p.vy, before, next, s.tieBreak.getAsDouble(), p.id};
    }

    @Test
    @DisplayName("off: the bounce draws C's rfrac; on: the seeded source, whatever the world RNG holds")
    void source() {
        double[] off = bounce(false, 1);
        double[] on1 = bounce(true, 1);
        double[] on2 = bounce(true, 99);
        assertAll(
            () -> assertNotEquals(off[4], off[5], "off: the world RNG advanced (one draw)"),
            () -> assertEquals(on1[4], on1[5], "on: the world RNG did not advance"),
            () -> assertEquals(on1[0], on2[0], "on: the same x whatever the world seed"),
            () -> assertEquals(on1[2], on2[2], "and the same vx"),
            () -> assertEquals(on1[3], on2[3], "and vy"));
    }

    @Test
    @DisplayName("on: the tick's fly reseeds too (ShipEffects.begin), whatever the source held: as the wrapper")
    void flyReseeds() {
        double[] wrapper = bounce(true, 1);
        int differ = 0;
        for (int k = 0; k < 16; k++) {
            double[] fly = bounce(true, 1, k);
            if (fly[0] != wrapper[0] || fly[2] != wrapper[2] || fly[3] != wrapper[3] || fly[6] != wrapper[6]) {
                differ++;
            }
        }
        int differing = differ;
        SeededTieBreak ref = new SeededTieBreak();
        ref.reseed(7, (int) wrapper[7]);
        ref.getAsDouble();
        double second = ref.getAsDouble();
        double[] flyOff = bounce(false, 1, 0);
        double[] off = bounce(false, 1);
        assertAll(
            () -> assertEquals(0, differing, "whatever the source held, fly moves (and leaves the source) as the"
                + " wrapper's reseeded move"),
            () -> assertEquals(second, wrapper[6], "the move took one draw of the source seeded (7, id)"),
            () -> assertEquals(off[0], flyOff[0], "off: fly draws C's rfrac like the wrapper"),
            () -> assertEquals(off[5], flyOff[5], "off: the world RNG moved the same"));
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
