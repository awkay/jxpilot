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
package com.fulcrologic.jxpilot.client.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.common.net.Records.Self.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Weapon modifier strings (C server/frame.c Frame_status)")
class ModifiersTest {
    static String fmt(int bits) {
        return Modifiers.format(bits, new CharBuf(64)).toString();
    }

    @Test
    @DisplayName("format: letters run together, numbered modifiers spaced, mini shown + 1, laser S or B")
    void format() {
        int all = MOD_FULL_NUCLEAR | MOD_NUCLEAR | MOD_CLUSTER | MOD_IMPLOSION | 2 << MOD_VELOCITY_SHIFT
            | 2 << MOD_MINI_SHIFT | 1 << MOD_SPREAD_SHIFT | 3 << MOD_POWER_SHIFT | MOD_LASER_STUN;
        CharBuf pre = new CharBuf(32).append("x");
        assertAll(
            () -> assertEquals("", fmt(0), "nothing for no modifiers"),
            () -> assertEquals("FNCI V2 X3 Z1 B3 LS", fmt(all), "every modifier"),
            () -> assertEquals("N", fmt(MOD_NUCLEAR), "nuclear"),
            () -> assertEquals("V1", fmt(1 << MOD_VELOCITY_SHIFT), "a numbered one alone has no space"),
            () -> assertEquals("X2", fmt(1 << MOD_MINI_SHIFT), "mini 1 is X2"),
            () -> assertEquals("C B1", fmt(MOD_CLUSTER | 1 << MOD_POWER_SHIFT), "space after letters"),
            () -> assertEquals("LB", fmt(MOD_LASER_BLIND), "blind laser"),
            () -> assertEquals("LS", fmt(MOD_LASER_STUN | MOD_LASER_BLIND), "stun wins (C checks STUN)"),
            () -> assertEquals("I Z3", fmt(MOD_IMPLOSION | 3 << MOD_SPREAD_SHIFT), "implosion and spread"),
            () -> assertEquals("xN", Modifiers.format(MOD_NUCLEAR, pre).toString(), "appends to existing text"),
            () -> assertEquals(3, Modifiers.field(3 << MOD_POWER_SHIFT, MOD_POWER_SHIFT), "field"));
    }

    @Test
    @DisplayName("parse is the inverse of format; unknown characters and missing digits are skipped")
    void parse() {
        int all = MOD_FULL_NUCLEAR | MOD_NUCLEAR | MOD_CLUSTER | MOD_IMPLOSION | 2 << MOD_VELOCITY_SHIFT
            | 2 << MOD_MINI_SHIFT | 1 << MOD_SPREAD_SHIFT | 3 << MOD_POWER_SHIFT | MOD_LASER_BLIND;
        assertAll(
            () -> assertEquals(all, Modifiers.parse(fmt(all)), "round trip"),
            () -> assertEquals(0, Modifiers.parse(""), "empty"),
            () -> assertEquals(MOD_LASER_STUN, Modifiers.parse("LS"), "stun"),
            () -> assertEquals(MOD_LASER_BLIND, Modifiers.parse("LB"), "blind (not power)"),
            () -> assertEquals(0, Modifiers.parse("L"), "a lone L"),
            () -> assertEquals(0, Modifiers.parse("V"), "a number letter at the end"),
            () -> assertEquals(3 << MOD_VELOCITY_SHIFT, Modifiers.parse("V9"), "clamped to 3"),
            () -> assertEquals(0, Modifiers.parse("X1"), "X1 (mini 0) is nothing"),
            () -> assertEquals(MOD_CLUSTER, Modifiers.parse("?C!"), "unknown characters skipped"),
            () -> assertEquals(1 << MOD_POWER_SHIFT | MOD_NUCLEAR, Modifiers.parse("B1 N"), "order free"));
    }
}
