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

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.CRandom;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.server.sim.SimFixture.ready;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The random draws C's Thrust and Make_debris make, counted when a test replays C's generator. */
@DisplayName("Debris: C's thrust draws in a C replay (ship.c Thrust, play.c Make_debris)")
class DebrisCReplayTest {
    /** A C generator whose next value (564929546) is positive, so keep_rand shifts down to 0. */
    static CRandom positiveNext() {
        CRandom c = new CRandom(4357);
        c.next();
        c.next();
        c.next();
        return c;
    }

    static int tot(int keepBits, float power) {
        return (int) ((power * 0.15) + (keepBits & 3) + 1);
    }

    @Test
    @DisplayName("first thrust: keep_rand drawn, then two Make_debris: 1 + 4 per spark each (no alt sparks)")
    void firstThrust() {
        Simulation s = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        World w = s.world;
        Player p = ready(s, "P", 0, 500, 500);
        CRandom c = positiveNext();
        w.rng.useC(c);
        long before = c.draws();
        s.debris.thrust(p);
        long first = c.draws() - before;
        int keep = 564929546;
        int t1 = tot(keep, p.power);
        before = c.draws();
        s.debris.thrust(p);
        long second = c.draws() - before;
        int t2 = tot(keep >> 2, p.power);
        assertAll(
            () -> assertEquals(1 + (1 + 4L * t1) + 1, first, "keep_rand, the red sparks, the (empty) blue ones"),
            () -> assertEquals((1 + 4L * t2) + 1, second, "keep_rand shifted, nothing drawn for it"));
    }

    @Test
    @DisplayName("a positive keep_rand is redrawn once it shifted down to 0; a negative one never is (C's signed shift)")
    void keepRand() {
        Simulation s = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        World w = s.world;
        Player p = ready(s, "P", 0, 500, 500);
        CRandom c = positiveNext();
        w.rng.useC(c);
        int keep = 564929546;
        long expected = 1;
        for (int i = 0; i < 20; i++) {
            long before = c.draws();
            s.debris.thrust(p);
            long d = c.draws() - before;
            if (i > 0) {
                keep >>= 2;
                if (keep == 0) {
                    break;
                }
            }
            expected = 2 + 4L * tot(keep, p.power) + (i == 0 ? 1 : 0);
            assertEquals(expected, d, "thrust " + i);
        }
        Simulation n = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        Player q = ready(n, "Q", 0, 500, 500);
        CRandom neg = new CRandom(4357); // first value 3510405877: negative as a C int
        n.world.rng.useC(neg);
        long redraws = 0;
        int k = (int) 3510405877L;
        for (int i = 0; i < 40; i++) {
            long before = neg.draws();
            n.debris.thrust(q);
            if (i > 0) {
                k >>= 2;
            }
            long sparks = 2 + 4L * tot(k, q.power);
            redraws += neg.draws() - before - sparks;
        }
        assertEquals(1, redraws, "drawn once, never again (it shifts to -1)");
    }

    @Test
    @DisplayName("afterburners split off blue sparks: ((tot - 1) * ab >> 4) + 1 of them")
    void afterburners() {
        Simulation s = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        World w = s.world;
        Player p = ready(s, "P", 0, 500, 500);
        p.items[Item.AFTERBURNER.ordinal()] = 8;
        CRandom c = positiveNext();
        w.rng.useC(c);
        long before = c.draws();
        s.debris.thrust(p);
        int t = tot(564929546, p.power);
        int ab = p.afterburners();
        int alt = (((t - 1) * ab) >> 4) + 1;
        assertEquals(1 + (1 + 4L * (t - alt)) + (1 + 4L * alt), c.draws() - before, "red and blue sparks");
    }

    @Test
    @DisplayName("an engine outside the world makes no Make_debris draws; with edge wrap it wraps and does")
    void outside() {
        Simulation s = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        Player p = ready(s, "P", 0, 1, 500);
        p.dir = 0; // pointing right: the engine is left of the ship, outside x 0
        CRandom c = positiveNext();
        s.world.rng.useC(c);
        long before = c.draws();
        s.debris.thrust(p);
        long out = c.draws() - before;
        Simulation wr = SimFixture.sim(SimFixture.FPS14 + "edgeWrap: yes\n", 30, 30, "_");
        Player q = ready(wr, "Q", 0, 1, 500);
        q.dir = 0;
        CRandom c2 = positiveNext();
        wr.world.rng.useC(c2);
        before = c2.draws();
        wr.debris.thrust(q);
        long wrapped = c2.draws() - before;
        Simulation top = SimFixture.sim(SimFixture.FPS14, 30, 30, "_");
        Player r = ready(top, "R", 0, 500, 1);
        r.dir = 32; // pointing up: the engine is below y 0
        CRandom c3 = positiveNext();
        top.world.rng.useC(c3);
        before = c3.draws();
        top.debris.thrust(r);
        long below = c3.draws() - before;
        int t = tot(564929546, p.power);
        assertAll(
            () -> assertEquals(1, out, "only keep_rand"),
            () -> assertEquals(1 + (1 + 4L * t) + 1, wrapped, "wrapped to the far edge: the sparks' draws"),
            () -> assertEquals(1, below, "below the world: only keep_rand"));
    }
}
