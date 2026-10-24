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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.server.TestWorlds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** World: slots and ids, wrap helpers (C WRAP_* macros), gravity lookup, teams and wormhole start state. */
class WorldTest {
    /** 11 x 6 blocks = 385 x 210 px, three bases. */
    static final String[] WIDE = {
        "xxxxxxxxxxx",
        "x         x",
        "x _  _  _ x",
        "x    +    x",
        "x         x",
        "xxxxxxxxxxx",
    };

    static World world(String extra, String... rows) {
        return new World(TestWorlds.options(extra, rows), 1);
    }

    @Nested
    @DisplayName("construction")
    class Construction {
        @Test
        void slotsMatchTheBasesAndStationsStartWith20Units() {
            World w = world("", "xxxxx", "x#__x", "xxxxx");
            World none = world("", "xxx", "x x", "xxx");
            assertAll(
                () -> assertEquals(2, w.baseSlots, "one player slot per base"),
                () -> assertEquals(2 + World.MAX_PSEUDO_PLAYERS, w.players.length, "plus the tank slots"),
                () -> assertEquals(1, none.baseSlots, "at least one slot"),
                () -> assertEquals(20 * 256.0, w.fuelLevel[0], "START_STATION_FUEL"),
                () -> assertEquals(5 * 35, w.pixelWidth, "pixel width"),
                () -> assertEquals(5 * 35 * 64, w.clickWidth, "click width"),
                () -> assertEquals(3 * 35 * 64, w.clickHeight, "click height"),
                () -> assertEquals(0, w.numPlayers, "nobody is playing"));
        }

        @Test
        void rfracIsTheSeededRngSequence() {
            World w = world("", WIDE);
            Rng r = new Rng(1);
            assertAll(
                () -> assertEquals(r.nextFloat(), w.rfrac(), "first value"),
                () -> assertEquals(r.nextFloat(), w.rfrac(), "second value"));
        }
    }

    @Nested
    @DisplayName("initWormholes")
    class InitWormholes {
        static final String[] WORMS = {
            "xxxxxxx",
            "x@ ( @x",
            "x  _ )x",
            "xxxxxxx",
        };

        @Test
        void withoutWormTimeEachWormholeStartsWithARandomNonInDestination() {
            World w = world("", WORMS);
            // x-major order: 0 = (1,2) @, 1 = (3,2) IN, 2 = (5,1) OUT, 3 = (5,2) @
            Rng r = new Rng(1);
            int[] expected = new int[4];
            for (int i = 0; i < 4; i++) {
                int j = (int) (r.nextFloat() * 4);
                while (j == 1) {
                    j = (int) (r.nextFloat() * 4);
                }
                expected[i] = j;
            }
            assertAll(
                () -> assertEquals(com.fulcrologic.jxpilot.common.map.Wormhole.Type.IN, w.map.wormholes().get(1).type(),
                    "wormhole 1 is in-only"),
                () -> assertEquals(Arrays.toString(expected), Arrays.toString(w.wormLastDest),
                    "map.c: (int)(rfrac()*n) redrawn while IN"),
                () -> assertEquals(0.0, w.wormCountdown[0], "no countdown running"));
        }

        @Test
        void inOnlyWormholesAreRedrawnAndAllNormalOnesAreUsed() {
            // 0 = (1,1) IN, 1 = (1,2) IN, 2 = (3,1) OUT, 3 = (3,2) OUT, 4 = (5,1) IN
            World mostlyIn = world("", "xxxxxxx", "x( ) _x", "x( ) (x", "xxxxxxx");
            World normal = world("", "xxxxxx", "x@ @_x", "xxxxxx");
            Rng r = new Rng(1);
            int[] expected = new int[5];
            for (int i = 0; i < 5; i++) {
                int j = (int) (r.nextFloat() * 5);
                while (mostlyIn.map.wormholes().get(j).type() == com.fulcrologic.jxpilot.common.map.Wormhole.Type.IN) {
                    j = (int) (r.nextFloat() * 5);
                }
                expected[i] = j;
            }
            Rng r2 = new Rng(1);
            int[] normalExpected = {(int) (r2.nextFloat() * 2), (int) (r2.nextFloat() * 2)};
            assertAll(
                () -> assertEquals(Arrays.toString(expected), Arrays.toString(mostlyIn.wormLastDest),
                    "IN destinations are redrawn"),
                () -> assertEquals(Arrays.toString(normalExpected), Arrays.toString(normal.wormLastDest),
                    "with only normal wormholes every draw is used"));
        }

        @Test
        void withWormTimeThereIsNoDestinationAndNoRandomNumberIsUsed() {
            World timed = world("wormTime: 3\n", WORMS);
            assertAll(
                () -> assertEquals("[-1, -1, -1, -1]", Arrays.toString(timed.wormLastDest), "wormTime: -1"),
                () -> assertEquals(new Rng(1).nextFloat(), timed.rfrac(), "the RNG is untouched"));
        }
    }

    @Nested
    @DisplayName("addPlayer / removePlayer / byId / playerAt")
    class Slots {
        @Test
        void playersGetTheLowestFreeIdAndJoinOrder() {
            World w = world("", WIDE);
            Player a = w.addPlayer();
            Player b = w.addPlayer();
            Player c = w.addPlayer();
            Player full = w.addPlayer();
            int reserved = w.numPlayers;
            boolean spawnedBefore = a.spawned;
            w.enter(a);
            w.enter(b);
            w.enter(c);
            w.enter(c);
            assertAll(
                () -> assertEquals(0, reserved, "reserved players are not in the game yet"),
                () -> assertFalse(spawnedBefore, "nor marked spawned"),
                () -> assertEquals(3, w.numPlayers, "enter puts them in the game, once each"),
                () -> assertEquals(1, a.id, "ids start at 1"),
                () -> assertEquals(2, b.id, "then 2"),
                () -> assertEquals(3, c.id, "then 3"),
                () -> assertNull(full, "no slot left: null"),
                () -> assertEquals(0, a.pseudoTeam, "pseudo teams count up"),
                () -> assertEquals(2, c.pseudoTeam, "per added player"),
                () -> assertSame(b, w.playerAt(1), "order follows joining"),
                () -> assertSame(c, w.byId(3), "ids map to players"),
                () -> assertTrue(a.inUse && b.inUse && c.inUse, "slots are in use"));
        }

        @Test
        void removingSwapsTheLastIntoTheGapLikeCAndFreesTheId() {
            World w = world("", WIDE);
            Player a = w.addPlayer();
            Player b = w.addPlayer();
            Player c = w.addPlayer();
            w.enter(a);
            w.enter(b);
            w.enter(c);
            w.removePlayer(a);
            w.removePlayer(a);
            Player d = w.addPlayer();
            w.enter(d);
            assertAll(
                () -> assertEquals(3, w.numPlayers, "three players again"),
                () -> assertSame(c, w.playerAt(0), "C Delete_player: the last player (c) takes a's place"),
                () -> assertSame(b, w.playerAt(1), "b keeps its place"),
                () -> assertSame(d, w.playerAt(2), "the new player is last"),
                () -> assertSame(a, d, "the freed slot is reused"),
                () -> assertEquals(1, d.id, "and the freed id"),
                () -> assertEquals(3, d.pseudoTeam, "with a fresh pseudo team"));
        }

        @Test
        void removingTheLastPlayerInOrderWorks() {
            World w = world("", WIDE);
            Player a = w.addPlayer();
            Player b = w.addPlayer();
            w.enter(a);
            w.enter(b);
            w.removePlayer(b);
            Player r = w.addPlayer();
            int idBefore = r.id;
            w.removePlayer(r);
            assertAll(
                () -> assertEquals(2, idBefore, "a reserved player holds an id"),
                () -> assertNull(w.byId(2), "removing a reserved player frees its id"),
                () -> assertFalse(r.inUse, "and its slot"),
                () -> assertEquals(1, w.numPlayers, "one left"),
                () -> assertSame(a, w.playerAt(0), "a stays first"),
                () -> assertNull(w.byId(2), "b's id is free"),
                () -> assertFalse(b.inUse, "b's slot is free"));
        }

        @Test
        void byIdRejectsUnusedAndOutOfRangeIds() {
            World w = world("", WIDE);
            w.addPlayer();
            assertAll(
                () -> assertNull(w.byId(Obj.NO_ID), "NO_ID"),
                () -> assertNull(w.byId(0), "0 is never used"),
                () -> assertNull(w.byId(World.MAX_IDS), "past the id range"),
                () -> assertNull(w.byId(World.MAX_IDS - 1), "an unused id in range"),
                () -> assertNull(w.byId(2), "an unused id"));
        }
    }

    @Nested
    @DisplayName("wrap helpers")
    class Wrap {
        @Test
        void withoutEdgeWrapNothingWraps() {
            World w = world("", WIDE);
            assertAll(
                () -> assertEquals(300.0, w.wrapDx(300), "dx unchanged"),
                () -> assertEquals(-300.0, w.wrapDy(-300), "dy unchanged"),
                () -> assertEquals(500.0, w.wrapLength(300, 400), "plain length"),
                () -> assertEquals(-5, w.wrapXClick(-5), "x clicks unchanged"),
                () -> assertEquals(999999, w.wrapYClick(999999), "y clicks unchanged"),
                () -> assertEquals(-1.5, w.wrapXPixel(-1.5), "x pixels unchanged"),
                () -> assertEquals(400.0, w.wrapYPixel(400), "y pixels unchanged"));
        }

        @Test
        void wrapDifferencesUseHalfTheWorldAsTheBoundary() {
            World w = world("edgeWrap: yes\n", WIDE);
            // 385 x 210 px: WRAP_DX half = 385 >> 1 = 192, WRAP_DY half = 105
            assertAll(
                () -> assertEquals(192.0, w.wrapDx(192), "dx = half is kept"),
                () -> assertEquals(193 - 385.0, w.wrapDx(193), "dx > half wraps down"),
                () -> assertEquals(-192.0, w.wrapDx(-192), "dx = -half is kept"),
                () -> assertEquals(-193 + 385.0, w.wrapDx(-193), "dx < -half wraps up"),
                () -> assertEquals(105.0, w.wrapDy(105), "dy = half is kept"),
                () -> assertEquals(106 - 210.0, w.wrapDy(106), "dy > half wraps"),
                () -> assertEquals(-105.0, w.wrapDy(-105), "dy = -half is kept"),
                () -> assertEquals(-106 + 210.0, w.wrapDy(-106), "dy < -half wraps"),
                () -> assertEquals(5.0, w.wrapLength(382, -206), "Wrap_length: |(-3, 4)| after wrapping"));
        }

        @Test
        void wrapPositionsMoveByOneWorldSize() {
            World w = world("edgeWrap: yes\n", WIDE);
            int cw = 385 * 64;
            int ch = 210 * 64;
            assertAll(
                () -> assertEquals(cw - 1, w.wrapXClick(-1), "x below 0 wraps up"),
                () -> assertEquals(0, w.wrapXClick(cw), "x at the width wraps to 0"),
                () -> assertEquals(cw - 1, w.wrapXClick(cw - 1), "x inside is kept"),
                () -> assertEquals(0, w.wrapXClick(0), "x = 0 is kept"),
                () -> assertEquals(ch - 1, w.wrapYClick(-1), "y below 0 wraps up"),
                () -> assertEquals(0, w.wrapYClick(ch), "y at the height wraps to 0"),
                () -> assertEquals(ch - 1, w.wrapYClick(ch - 1), "y inside is kept"),
                () -> assertEquals(0, w.wrapYClick(0), "y = 0 is kept"),
                () -> assertEquals(0.0, w.wrapYPixel(0), "y pixel 0 is kept"),
                () -> assertEquals(384.0, w.wrapXPixel(384), "x pixel inside is kept"),
                () -> assertEquals(384.5, w.wrapXPixel(-0.5), "x pixel below 0 wraps"),
                () -> assertEquals(0.0, w.wrapXPixel(385), "x pixel at the width wraps"),
                () -> assertEquals(0.0, w.wrapXPixel(0), "x pixel 0 is kept"),
                () -> assertEquals(209.5, w.wrapYPixel(-0.5), "y pixel below 0 wraps"),
                () -> assertEquals(0.0, w.wrapYPixel(210), "y pixel at the height wraps"),
                () -> assertEquals(209.0, w.wrapYPixel(209), "y pixel inside is kept"));
        }
    }

    @Nested
    @DisplayName("gravity, wallDist")
    class Fields {
        @Test
        void gravityIsLookedUpWithBlocksClampedIntoTheMap() {
            World w = world("gravity: 0\n", WIDE);
            assertAll(
                () -> assertTrue(w.gravityX(4, 2) != 0, "the + source makes a field next to it"),
                () -> assertEquals(w.map.gravityX(4, 2), w.gravityX(4, 2), "in range: the map's x field"),
                () -> assertEquals(w.map.gravityY(5, 3), w.gravityY(5, 3), "in range: the map's y field"),
                () -> assertEquals(w.map.gravityX(0, 3), w.gravityX(-1, 3), "x below 0 clamps to 0"),
                () -> assertEquals(w.map.gravityX(10, 3), w.gravityX(11, 3), "x past the edge clamps"),
                () -> assertEquals(w.map.gravityY(4, 0), w.gravityY(4, -2), "y below 0 clamps to 0"),
                () -> assertEquals(w.map.gravityY(4, 5), w.gravityY(4, 6), "y past the edge clamps"));
        }

        @Test
        void wallDistanceIsReadFromTheTable() {
            World w = world("", WIDE);
            assertEquals(w.wallDist[2 * w.width + 5] & 0xFF, w.wallDist(5, 2), "row-major lookup");
        }
    }

    @Nested
    @DisplayName("sameTeam / teamImmune")
    class Teams {
        static final String[] TEAMS = {"xxxxxxx", "x1 1 2x", "xxxxxxx"};

        @Test
        void teamsAreOnlyEqualInTeamPlayWithATeamSet() {
            World t = world("teamPlay: yes\n", TEAMS);
            Player a = t.addPlayer();
            Player b = t.addPlayer();
            Player c = t.addPlayer();
            a.team = 1;
            b.team = 1;
            c.team = 2;
            World f = world("", TEAMS);
            Player x = f.addPlayer();
            Player y = f.addPlayer();
            Player n1 = new Player(9);
            Player n2 = new Player(8);
            assertAll(
                () -> assertTrue(t.sameTeam(a, b), "team mates"),
                () -> assertFalse(t.sameTeam(a, c), "different teams"),
                () -> assertFalse(t.sameTeam(n1, n2), "TEAM_NOT_SET is nobody's team"),
                () -> assertFalse(f.sameTeam(x, y), "outside team play nobody is a team mate"));
        }

        @Test
        void teamImmunityProtectsDifferentTeamMatesOnly() {
            World t = world("teamPlay: yes\n", TEAMS);
            Player a = t.addPlayer();
            Player b = t.addPlayer();
            Player c = t.addPlayer();
            a.team = 1;
            b.team = 1;
            c.team = 2;
            World off = world("teamPlay: yes\nteamImmunity: no\n", TEAMS);
            Player oa = off.addPlayer();
            Player ob = off.addPlayer();
            oa.team = 1;
            ob.team = 1;
            assertAll(
                () -> assertTrue(t.teamImmune(a.id, b.id), "team mates are immune"),
                () -> assertFalse(t.teamImmune(a.id, a.id), "own objects are never team immune"),
                () -> assertFalse(t.teamImmune(a.id, c.id), "other teams are not"),
                () -> assertFalse(t.teamImmune(a.id, Obj.NO_ID), "NO_ID (cannons) is not"),
                () -> assertFalse(t.teamImmune(Obj.NO_ID, a.id), "in either position"),
                () -> assertFalse(t.teamImmune(a.id, 200), "an unknown id is not"),
                () -> assertFalse(t.teamImmune(200, a.id), "in either position"),
                () -> assertFalse(off.teamImmune(oa.id, ob.id), "without teamImmunity nobody is"));
        }
    }
}
