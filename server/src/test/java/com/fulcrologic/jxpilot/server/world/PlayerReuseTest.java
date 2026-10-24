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

import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Player indexes and struct reuse follow C's Players[] (player.c:1785-1794, Init_player)")
class PlayerReuseTest {
    static final String[] MAP = {"xxxxxxxxxx", "x _ _ _ _x", "x        x", "xxxxxxxxxx"};

    @Test
    @DisplayName("a leaver's place goes to the last player; the leaver's struct, tables and all, is the next joiner's")
    void reuse() {
        Simulation s = TestWorlds.sim("gravity: 0\n", MAP);
        World w = s.world;
        Player a = s.ships.join("A", 0);
        Player b = s.ships.join("B", 0);
        Player c = s.ships.join("C", 0);
        b.canSee[0] = true;
        b.canSee[2] = false;
        int[] before = {a.ind, b.ind, c.ind};
        w.removePlayer(b);
        int cAfter = c.ind;
        int bAfter = b.ind;
        Player d = s.ships.join("D", 0);
        assertAll(
            () -> assertEquals("0 1 2", before[0] + " " + before[1] + " " + before[2], "indexes in join order"),
            () -> assertEquals(1, cAfter, "the last player C takes B's index"),
            () -> assertEquals(-1, bAfter, "B has none any more"),
            () -> assertSame(b, d, "the next joiner gets B's struct (C swaps it to Players[NumPlayers])"),
            () -> assertEquals(2, d.ind, "at the end of the order"),
            () -> assertTrue(d.canSee[0], "with B's visibility entries, as C leaves them"),
            () -> assertFalse(d.canSee[2], "untouched until the next visibility update"));
    }
}
