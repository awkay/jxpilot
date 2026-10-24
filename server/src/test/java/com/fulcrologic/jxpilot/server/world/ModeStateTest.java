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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ModeState: the asteroid list and team treasure counts")
class ModeStateTest {
    /** A team map: team 0 base and treasure on the left, team 1 base, a treasure and an empty one on the right. */
    static World teams() {
        return TestWorlds.sim("teamPlay: yes\n", new String[] {
            "          ",
            "          ",
            "*0     1*^",
            "xxxxxxxxxx"}).world;
    }

    @Test
    @DisplayName("treasures count for their team in team play: team 0 too; empty ones separately")
    void treasureCounts() {
        World w = teams();
        ModeState m = w.modes;
        assertAll(
            () -> assertEquals(1, m.teamTreasures[0], "team 0 has its treasure"),
            () -> assertEquals(1, m.teamTreasuresLeft[0], "with a ball"),
            () -> assertEquals(2, m.teamTreasures[1], "team 1: two"),
            () -> assertEquals(1, m.teamEmptyTreasures[1], "one of them empty"),
            () -> assertEquals(1, m.teamTreasuresLeft[1], "one with a ball"));
    }

    @Test
    @DisplayName("the list: append until full, remove keeping order, stale entries past the count are not found")
    void list() {
        World w = teams();
        ModeState m = new ModeState(w.map, 4);
        boolean[] added = {m.addAsteroid(10), m.addAsteroid(20), m.addAsteroid(30), m.addAsteroid(40)};
        boolean full = m.addAsteroid(50);
        boolean removedMiddle = m.removeAsteroid(20);
        int[] after = {m.asteroidList[0], m.asteroidList[1], m.asteroidList[2]};
        boolean removedLast = m.removeAsteroid(40);
        boolean missing = m.removeAsteroid(99);
        assertAll(
            () -> assertTrue(added[0] && added[1] && added[2] && added[3], "four fit"),
            () -> assertFalse(full, "the fifth does not"),
            () -> assertTrue(removedMiddle, "20 removed"),
            () -> assertEquals(10, after[0], "order kept"),
            () -> assertEquals(30, after[1]),
            () -> assertEquals(40, after[2]),
            () -> assertTrue(removedLast, "the last removed"),
            () -> assertEquals(2, m.asteroidCount, "two left"),
            () -> assertEquals(-1, m.asteroidIndex(40), "40 still sits past the count but is not found"),
            () -> assertEquals(1, m.asteroidIndex(30), "30 at 1"),
            () -> assertFalse(missing, "an absent handle"));
    }
}
