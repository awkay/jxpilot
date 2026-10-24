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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C {@code modifiers} (object.h) packed into an int, and {@code filter_mods} (event.c). */
@DisplayName("Mods (object.h modifiers, event.c filter_mods)")
class ModsTest {
    /** Every field at a distinct value: warhead 3, nuclear 2, velocity 1, mini 2, spread 3, power 1, laser 2. */
    static int all() {
        int m = 0;
        m = Mods.withWarhead(m, 3);
        m = Mods.withNuclear(m, 2);
        m = Mods.withVelocity(m, 1);
        m = Mods.withMini(m, 2);
        m = Mods.withSpread(m, 3);
        m = Mods.withPower(m, 1);
        m = Mods.withLaser(m, 2);
        return m;
    }

    @Nested
    @DisplayName("fields")
    class Fields {
        @Test
        @DisplayName("each 2-bit field reads back independently at its bit position")
        void packing() {
            int m = all();
            assertAll(
                () -> assertEquals(3, Mods.warhead(m), "warhead 3 (C|I)"),
                () -> assertEquals(2, Mods.nuclear(m), "nuclear 2 (FULLNUCLEAR)"),
                () -> assertEquals(1, Mods.velocity(m), "velocity 1"),
                () -> assertEquals(2, Mods.mini(m), "mini 2"),
                () -> assertEquals(3, Mods.spread(m), "spread 3"),
                () -> assertEquals(1, Mods.power(m), "power 1"),
                () -> assertEquals(2, Mods.laser(m), "laser 2 (BLIND)"),
                () -> assertEquals(3 | 2 << 2 | 1 << 4 | 2 << 6 | 3 << 8 | 1 << 10 | 2 << 12, m,
                    "layout: warhead 0-1, nuclear 2-3, velocity 4-5, mini 6-7, spread 8-9, power 10-11, laser 12-13"),
                () -> assertEquals(Obj.MOD_CLUSTER | Obj.MOD_IMPLOSION, Mods.withWarhead(0, 3),
                    "the warhead field holds Obj.MOD_CLUSTER and Obj.MOD_IMPLOSION"));
        }

        @Test
        @DisplayName("setting a field keeps only its low 2 bits and leaves the others untouched")
        void setMasks() {
            int m = all();
            assertAll(
                () -> assertEquals(1, Mods.mini(Mods.withMini(m, 5)), "5 is stored as 5 & 3 = 1 (a 2-bit field)"),
                () -> assertEquals(all() & ~(3 << 6) | 1 << 6, Mods.withMini(m, 5), "no other field changes"),
                () -> assertEquals(0, Mods.withPower(Mods.withPower(0, 3), 0), "setting 0 clears a field"),
                () -> assertEquals(1 << 10, Mods.withPower(0, 1), "power at bit 10"),
                () -> assertEquals(3 << 12, Mods.withLaser(0, -1), "-1 sets both laser bits"),
                () -> assertEquals(2 << 2, Mods.withNuclear(0, 6), "6 & 3 = 2 into nuclear"),
                () -> assertEquals(2 << 4, Mods.withVelocity(0, 2), "velocity at bit 4"),
                () -> assertEquals(3 << 8, Mods.withSpread(0, 3), "spread at bit 8"));
        }

        @Test
        @DisplayName("the predicates test exactly their bit")
        void predicates() {
            int nuke = Mods.withNuclear(0, Mods.NUCLEAR);
            int full = Mods.withNuclear(0, Mods.FULLNUCLEAR);
            assertAll(
                () -> assertTrue(Mods.isNuclear(nuke), "NUCLEAR is nuclear"),
                () -> assertFalse(Mods.isFullNuclear(nuke), "NUCLEAR alone is not full"),
                () -> assertTrue(Mods.isFullNuclear(full), "FULLNUCLEAR is full"),
                () -> assertFalse(Mods.isNuclear(full), "FULLNUCLEAR alone is not nuclear"),
                () -> assertFalse(Mods.isNuclear(0), "no modifiers: not nuclear"),
                () -> assertTrue(Mods.isCluster(Obj.MOD_CLUSTER), "cluster bit"),
                () -> assertFalse(Mods.isCluster(Obj.MOD_IMPLOSION), "implosion is not cluster"),
                () -> assertTrue(Mods.isImplosion(Obj.MOD_IMPLOSION), "implosion bit"),
                () -> assertFalse(Mods.isImplosion(Obj.MOD_CLUSTER), "cluster is not implosion"),
                () -> assertFalse(Mods.isCluster(Mods.withMini(0, 3)), "other fields are not warheads"));
        }
    }

    @Nested
    @DisplayName("filter (filter_mods)")
    class Filter {
        @Test
        @DisplayName("everything allowed keeps every modifier")
        void allAllowed() {
            assertEquals(all() | Mods.withNuclear(0, 3), Mods.filter(all() | Mods.withNuclear(0, 3), true, true, true, true),
                "nothing is dropped");
        }

        @Test
        @DisplayName("each rule drops exactly its modifiers")
        void eachRule() {
            int m = all() | Mods.withNuclear(0, 3);
            int noNukes = Mods.filter(m, false, true, true, true);
            int noClusters = Mods.filter(m, true, false, true, true);
            int noModifiers = Mods.filter(m, true, true, false, true);
            int noLaser = Mods.filter(m, true, true, true, false);
            assertAll(
                () -> assertEquals(Mods.withNuclear(m, 0), noNukes, "without allowNukes nuclear (both bits) is cleared"),
                () -> assertEquals(m & ~Obj.MOD_CLUSTER, noClusters, "without allowClusters only the cluster bit goes"),
                () -> assertEquals(Mods.withLaser(Mods.withNuclear(Obj.MOD_CLUSTER, 3), 2), noModifiers,
                    "without allowModifiers implosion, velocity, mini, spread and power go"),
                () -> assertEquals(Mods.withLaser(m, 0), noLaser, "without allowLaserModifiers laser is cleared"),
                () -> assertEquals(0, Mods.filter(m, false, false, false, false), "nothing allowed leaves nothing"));
        }
    }
}
