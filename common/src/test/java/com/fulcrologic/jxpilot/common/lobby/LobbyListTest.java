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
package com.fulcrologic.jxpilot.common.lobby;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lobby list's order and its one-key shortcuts. */
@DisplayName("Lobby list: order and shortcut keys")
class LobbyListTest {
    static LobbyEntry map(int id, String name, boolean active, int humans) {
        return new LobbyEntry(id, name, "title " + name, "author", 10, 10, 8, active, humans);
    }

    static List<String> names(List<LobbyEntry> l) {
        List<String> out = new ArrayList<>();
        for (LobbyEntry e : l) {
            out.add(e.name());
        }
        return out;
    }

    @Nested
    @DisplayName("LobbyOrder.sort")
    class Order {
        @Test
        @DisplayName("active maps come first, each group alphabetical ignoring case")
        void activeFirstThenAlphabetical() {
            List<LobbyEntry> l = new ArrayList<>(List.of(map(0, "zebra", false, 0), map(1, "Beta", true, 2),
                map(2, "alpha", false, 0), map(3, "yak", true, 1), map(4, "Alpha2", false, 0)));
            LobbyOrder.sort(l);
            assertEquals(List.of("Beta", "yak", "alpha", "Alpha2", "zebra"), names(l),
                "active Beta, yak (alphabetical), then idle alpha, Alpha2, zebra");
        }

        @Test
        @DisplayName("an active map is first even when its name sorts last; the player count does not matter")
        void humansDoNotOrder() {
            List<LobbyEntry> l = new ArrayList<>(List.of(map(0, "a", true, 1), map(1, "b", true, 9),
                map(2, "c", false, 0)));
            Collections.reverse(l);
            LobbyOrder.sort(l);
            assertEquals(List.of("a", "b", "c"), names(l), "a, b active in name order whatever their humans; c idle");
        }

        @Test
        @DisplayName("names equal ignoring case sort by exact characters, then by id")
        void ties() {
            List<LobbyEntry> l = new ArrayList<>(List.of(map(5, "map", false, 0), map(2, "Map", false, 0),
                map(1, "Map", false, 0)));
            LobbyOrder.sort(l);
            assertAll(
                () -> assertEquals(List.of(1, 2, 5), l.stream().map(LobbyEntry::id).toList(),
                    "'Map' (id 1, 2) before 'map' (upper case sorts first), ids ascending"));
        }

        @Test
        @DisplayName("an empty list stays empty")
        void empty() {
            List<LobbyEntry> l = new ArrayList<>();
            LobbyOrder.sort(l);
            assertTrue(l.isEmpty(), "nothing to sort");
        }
    }

    @Nested
    @DisplayName("LobbyKeys")
    class Keys {
        @Test
        @DisplayName("rows 0-9 are the digits, 10-35 a-z, 36-61 A-Z")
        void assignment() {
            assertAll(
                () -> assertEquals('0', LobbyKeys.keyFor(0), "row 0 is '0'"),
                () -> assertEquals('9', LobbyKeys.keyFor(9), "row 9 is '9'"),
                () -> assertEquals('a', LobbyKeys.keyFor(10), "row 10 is 'a'"),
                () -> assertEquals('z', LobbyKeys.keyFor(35), "row 35 is 'z'"),
                () -> assertEquals('A', LobbyKeys.keyFor(36), "row 36 is 'A'"),
                () -> assertEquals('Z', LobbyKeys.keyFor(61), "row 61 is 'Z'"),
                () -> assertEquals(62, LobbyKeys.COUNT, "62 shortcuts"));
        }

        @Test
        @DisplayName("rows below 0 and from 62 on have no shortcut")
        void overflow() {
            assertAll(
                () -> assertEquals(0, LobbyKeys.keyFor(62), "row 62 has none"),
                () -> assertEquals(0, LobbyKeys.keyFor(1000), "row 1000 has none"),
                () -> assertEquals(0, LobbyKeys.keyFor(-1), "row -1 has none"));
        }

        @Test
        @DisplayName("rowOf inverts keyFor for every shortcut and is -1 for any other character")
        void inverse() {
            for (int row = 0; row < LobbyKeys.COUNT; row++) {
                int r = row;
                assertEquals(r, LobbyKeys.rowOf(LobbyKeys.keyFor(r)), "row " + r + " round-trips");
            }
            assertAll(
                () -> assertEquals(-1, LobbyKeys.rowOf(' '), "space is not a shortcut"),
                () -> assertEquals(-1, LobbyKeys.rowOf('-'), "'-' is not a shortcut"),
                () -> assertEquals(-1, LobbyKeys.rowOf(0x20AC), "a character above Latin-1 is not a shortcut"),
                () -> assertEquals(-1, LobbyKeys.rowOf(-5), "a negative code is not a shortcut"));
        }

        @Test
        @DisplayName("all 62 shortcuts are distinct")
        void distinct() {
            java.util.Set<Character> seen = new java.util.HashSet<>();
            for (int i = 0; i < LobbyKeys.COUNT; i++) {
                assertTrue(seen.add(LobbyKeys.keyFor(i)), "shortcut for row " + i + " is new");
            }
            assertFalse(seen.contains((char) 0), "no row maps to the 'none' value");
        }
    }
}
