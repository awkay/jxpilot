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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.sim.Mods;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C {@code Receive_modifier_bank} (netserver.c:2844): the bank text parser. */
@DisplayName("ModifierBanks (C Receive_modifier_bank)")
class ModifierBanksTest {
    /** Every rule on. */
    static int all(String s) {
        return ModifierBanks.parse(s, true, true, true, true);
    }

    /** Field summary: "n<nuclear> w<warhead> v<velocity> x<mini> z<spread> b<power> l<laser>". */
    static String f(int m) {
        return "n" + Mods.nuclear(m) + " w" + Mods.warhead(m) + " v" + Mods.velocity(m) + " x" + Mods.mini(m)
            + " z" + Mods.spread(m) + " b" + Mods.power(m) + " l" + Mods.laser(m);
    }

    @Nested
    @DisplayName("letters with every rule on")
    class Letters {
        @Test
        @DisplayName("N, FN, C, I, LS, LB, case-insensitive; others skipped")
        void flags() {
            assertAll(
                () -> assertEquals("n1 w0 v0 x0 z0 b0 l0", f(all("N")), "N sets NUCLEAR"),
                () -> assertEquals("n3 w0 v0 x0 z0 b0 l0", f(all("FN")), "FN sets FULLNUCLEAR and (the N) NUCLEAR"),
                () -> assertEquals("n3 w0 v0 x0 z0 b0 l0", f(all("fn")), "lower case fn too"),
                () -> assertEquals("n0 w0 v0 x0 z0 b0 l0", f(all("F")), "F alone sets nothing"),
                () -> assertEquals("n1 w0 v0 x0 z0 b0 l0", f(all("F N")), "F not directly followed by N: only NUCLEAR"),
                () -> assertEquals("n0 w1 v0 x0 z0 b0 l0", f(all("c")), "C sets CLUSTER"),
                () -> assertEquals("n0 w2 v0 x0 z0 b0 l0", f(all("i")), "I sets IMPLOSION"),
                () -> assertEquals("n0 w3 v0 x0 z0 b0 l0", f(all("CI")), "C and I together"),
                () -> assertEquals("n0 w0 v0 x0 z0 b0 l1", f(all("LS")), "LS sets STUN"),
                () -> assertEquals("n0 w0 v0 x0 z0 b0 l2", f(all("lb")), "lb sets BLIND"),
                () -> assertEquals("n0 w0 v0 x0 z0 b0 l3", f(all("LSLB")), "both laser bits"),
                () -> assertEquals("n0 w0 v0 x0 z0 b0 l0", f(all("qwe-+ 9")), "other chars do nothing"),
                () -> assertEquals("n3 w3 v2 x3 z1 b3 l1", f(all("FN C I V2 X4 Z1 B3 LS")), "a full bank"));
        }

        @Test
        @DisplayName("V, Z, B read 0..3; X reads 1..4 as mini 0..3; out of range is the minimum; no digits is 0")
        void numbers() {
            assertAll(
                () -> assertEquals(3, Mods.velocity(all("V3")), "V3 is velocity 3"),
                () -> assertEquals(0, Mods.velocity(all("V4")), "V4 is out of range: 0"),
                () -> assertEquals(0, Mods.velocity(all("V")), "V without digits: 0"),
                () -> assertEquals(2, Mods.velocity(all("V002")), "leading zeros are read as decimal"),
                () -> assertEquals(3, Mods.spread(all("z3")), "z3 is spread 3"),
                () -> assertEquals(0, Mods.spread(all("Z9")), "Z9 is out of range: 0"),
                () -> assertEquals(1, Mods.power(all("B1")), "B1 is power 1"),
                () -> assertEquals(0, Mods.power(all("B12")), "B12 is out of range: 0"),
                () -> assertEquals(3, Mods.mini(all("X4")), "X4 is mini 3 (4 shots)"),
                () -> assertEquals(1, Mods.mini(all("x2")), "x2 is mini 1"),
                () -> assertEquals(0, Mods.mini(all("X1")), "X1 is mini 0"),
                () -> assertEquals(0, Mods.mini(all("X")), "X without digits: the minimum 1, mini 0"),
                () -> assertEquals(0, Mods.mini(all("X5")), "X5 is out of range: mini 0"),
                () -> assertEquals(0, Mods.velocity(all("V99999999999999999999")),
                    "a huge number is out of range, not wrapped into it"),
                () -> assertEquals(2, Mods.velocity(all("V3V2")), "a later V replaces the earlier one"));
        }

        @Test
        @DisplayName("the char after a number is parsed next; L always consumes the next char")
        void positions() {
            int afterNumber = all("V2N");
            int afterEmptyNumber = all("VN");
            int lc = all("LC");
            int lsn = all("LSN");
            int trailingL = all("NL");
            assertAll(
                () -> assertEquals("n1 w0 v2 x0 z0 b0 l0", f(afterNumber), "N right after V2 is parsed"),
                () -> assertEquals("n1 w0 v0 x0 z0 b0 l0", f(afterEmptyNumber), "N right after a bare V is parsed"),
                () -> assertEquals("n0 w0 v0 x0 z0 b0 l0", f(lc), "the C after L is consumed, not a cluster"),
                () -> assertEquals("n1 w0 v0 x0 z0 b0 l1", f(lsn), "parsing goes on after LS"),
                () -> assertEquals("n1 w0 v0 x0 z0 b0 l0", f(trailingL), "a trailing L ends the text"));
        }

        @Test
        @DisplayName("the text ends at a NUL")
        void nul() {
            assertAll(
                () -> assertEquals("n1 w0 v0 x0 z0 b0 l0", f(all("N\0C")), "C after the NUL is not read"),
                () -> assertEquals("n0 w0 v1 x0 z0 b0 l0", f(all("V1\0")), "a number before the NUL is read"));
        }
    }

    @Nested
    @DisplayName("rules")
    class Rules {
        static final String FULL = "FN C I V2 X4 Z1 B3 LS LB";

        @Test
        @DisplayName("each rule off removes only its letters; allowModifiers off empties the bank")
        void eachOff() {
            assertAll(
                () -> assertEquals("n3 w3 v2 x3 z1 b3 l3", f(ModifierBanks.parse(FULL, true, true, true, true)),
                    "every rule on: everything"),
                () -> assertEquals(0, ModifierBanks.parse(FULL, false, true, true, true),
                    "without allowModifiers nothing at all (not even implosion or numbers)"),
                () -> assertEquals("n0 w3 v2 x3 z1 b3 l3", f(ModifierBanks.parse(FULL, true, false, true, true)),
                    "without allowNukes no N or FN"),
                () -> assertEquals("n3 w2 v2 x3 z1 b3 l3", f(ModifierBanks.parse(FULL, true, true, false, true)),
                    "without allowClusters no C (implosion stays)"),
                () -> assertEquals("n3 w3 v2 x3 z1 b3 l0", f(ModifierBanks.parse(FULL, true, true, true, false)),
                    "without allowLaserModifiers no LS/LB"),
                () -> assertEquals("n0 w0 v0 x0 z0 b0 l0", f(ModifierBanks.parse("LC", true, true, true, false)),
                    "without allowLaserModifiers L still consumes the next char"),
                () -> assertEquals("n0 w0 v0 x0 z0 b0 l0", f(ModifierBanks.parse("FN", true, false, true, true)),
                    "without allowNukes FN sets nothing"));
        }
    }

    @Nested
    @DisplayName("accepted")
    class Accepted {
        @Test
        @DisplayName("banks 0..3 with texts up to 79 chars; bank 4+, negative or 80+ chars are not")
        void accepted() {
            assertAll(
                () -> assertTrue(ModifierBanks.accepted(0, ""), "bank 0, empty text"),
                () -> assertTrue(ModifierBanks.accepted(3, "x".repeat(79)), "bank 3, 79 chars (C MAX_CHARS - 1)"),
                () -> assertFalse(ModifierBanks.accepted(4, "N"), "bank 4 (C NUM_MODBANKS) is ignored"),
                () -> assertFalse(ModifierBanks.accepted(-1, "N"), "a negative bank is ignored"),
                () -> assertFalse(ModifierBanks.accepted(0, "x".repeat(80)),
                    "80 chars overflow C's %s scan: ignored"));
        }
    }
}
