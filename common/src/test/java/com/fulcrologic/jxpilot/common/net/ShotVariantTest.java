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
package com.fulcrologic.jxpilot.common.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SHOT section variant: kind, colour and the harmless flag")
class ShotVariantTest {
    @Test
    @DisplayName("the harmless flag is bit 7 and leaves kind and colour intact")
    void harmlessFlag() {
        int plain = Records.ShotKind.variant(Records.ShotKind.CLUSTER, Records.ObjColor.ENEMY);
        int harmless = Records.ShotKind.variant(Records.ShotKind.CLUSTER, Records.ObjColor.ENEMY, true);
        int notHarmless = Records.ShotKind.variant(Records.ShotKind.CANNON_SHOT, Records.ObjColor.TEAM, false);
        assertAll(
            () -> assertEquals(0x23, plain, "kind 2 in bits 4-6, colour 3 in bits 0-3"),
            () -> assertEquals(0xA3, harmless, "harmless adds 0x80"),
            () -> assertTrue(Records.ShotKind.harmless(harmless), "a flagged variant is harmless"),
            () -> assertFalse(Records.ShotKind.harmless(plain), "an unflagged variant is not"),
            () -> assertEquals(Records.ShotKind.CLUSTER, Records.ShotKind.kindOf(harmless), "kind ignores the flag"),
            () -> assertEquals(Records.ObjColor.ENEMY, Records.ShotKind.colorOf(harmless), "colour ignores the flag"),
            () -> assertEquals(0x12, notHarmless, "harmless=false is the plain variant"),
            () -> assertEquals(0x70, Records.ShotKind.variant(0xF, 0) & 0xF0, "kinds are limited to 3 bits"));
    }
}
