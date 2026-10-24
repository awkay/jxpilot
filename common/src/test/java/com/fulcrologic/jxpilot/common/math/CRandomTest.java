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
package com.fulcrologic.jxpilot.common.math;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("CRandom (XPilot's randommt.c) and Rng replaying it")
class CRandomTest {
    /** randomMT() as unsigned. */
    static long u(int v) {
        return v & 0xFFFFFFFFL;
    }

    @Test
    @DisplayName("bit-identical to C's randommt.c (values printed by C's own MT_MAIN-style program)")
    void matchesC() {
        CRandom r = new CRandom(4357);
        long[] got = new long[700];
        for (int j = 0; j < 700; j++) {
            got[j] = u(r.next());
        }
        CRandom s42 = new CRandom(42);
        long a = u(s42.next());
        long b = u(s42.next());
        long c = u(s42.next());
        assertAll(
            () -> assertEquals(3510405877L, got[0], "first value"),
            () -> assertEquals(4290933890L, got[1]),
            () -> assertEquals(2191955339L, got[2]),
            () -> assertEquals(564929546L, got[3]),
            () -> assertEquals(152112058L, got[4]),
            () -> assertEquals(730882493L, got[623], "last of the first block"),
            () -> assertEquals(2222118351L, got[624], "first after a reload"),
            () -> assertEquals(1576556523L, got[699]),
            () -> assertEquals(2774742873L, a, "seed 42 (an even seed is made odd)"),
            () -> assertEquals(1067396875L, b),
            () -> assertEquals(1676641486L, c),
            () -> assertEquals(700, r.draws(), "draws counted"));
    }

    @Test
    @DisplayName("rfrac is randomMT() * 2^-32 as a double; seed restarts; load continues a dumped state")
    void rfracSeedLoad() {
        CRandom r = new CRandom(4357);
        double f = r.rfrac();
        r.seed(4357);
        long again = u(r.next());
        CRandom src = new CRandom(9);
        for (int i = 0; i < 700; i++) {
            src.next();
        }
        int[] words = new int[625];
        CRandom probe = new CRandom(9);
        for (int i = 0; i < 700; i++) {
            probe.next();
        }
        long expect = u(probe.next());
        // rebuild 9's state after 700 draws by replaying into a fresh generator and copying via load
        CRandom copy = new CRandom(0);
        copyState(src, copy, words);
        long loaded = u(copy.next());
        assertAll(
            () -> assertEquals(3510405877L * 0x1p-32, f, 0.0, "exact"),
            () -> assertEquals(3510405877L, again, "seed restarts the sequence and the counter"),
            () -> assertEquals(1, r.draws(), "one draw after the reseed"),
            () -> assertEquals(expect, loaded, "a loaded state continues where it was"),
            () -> assertEquals(701, copy.draws(), "the counter starts at the loaded count"),
            () -> assertEquals(3510405877L, u(reseeded()), "C's reload reseeds with 4357 when left < -1"));
    }

    /** A generator loaded with left = -2: its next draw reloads, which reseeds with 4357 (C reloadMT). */
    static int reseeded() {
        CRandom r = new CRandom(1);
        int[] words = new int[625];
        r.snapshot(words);
        r.load(-2, 0, words, 0);
        return r.next();
    }

    /** Copy a generator's state through {@link CRandom#load} using the package-private snapshot. */
    static void copyState(CRandom from, CRandom to, int[] words) {
        from.snapshot(words);
        to.load(from.left(), from.nextIndex(), words, from.draws());
    }

    @Test
    @DisplayName("Rng's C mode: nextInt, nextFrac, nextFloat, skip")
    void rngCMode() {
        Rng rng = new Rng(5);
        int own = new Rng(5).nextInt();
        CRandom c = new CRandom(4357);
        rng.useC(c);
        CRandom ref = new CRandom(4357);
        int i1 = rng.nextInt();
        double f1 = rng.nextFrac();
        float f2 = rng.nextFloat();
        rng.skip(3);
        long afterSkip = c.draws();
        ref.next();
        double rf1 = ref.rfrac();
        float rf2 = (float) ref.rfrac();
        CRandom c2 = rng.c();
        rng.useC(null);
        Rng plain = new Rng(5);
        plain.skip(4);
        assertAll(
            () -> assertEquals(3510405877L, u(i1), "nextInt is randomMT()"),
            () -> assertEquals(rf1, f1, 0.0, "nextFrac is rfrac()"),
            () -> assertEquals(rf2, f2, 0.0f, "nextFloat is rfrac() as a float"),
            () -> assertEquals(6, afterSkip, "skip draws and drops"),
            () -> assertSame(c, c2, "the generator in use"),
            () -> assertNull(rng.c(), "off again"),
            () -> assertEquals(own, plain.nextInt(), "skip does nothing without C"),
            () -> assertEquals((double) new Rng(5).nextFloat(), new Rng(5).nextFrac(), 0.0, "nextFrac widens nextFloat"));
    }
}
