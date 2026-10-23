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
package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WorldMap accessors and helpers")
class WorldMapTest {
    /** Double results are exact up to rounding. */
    static final double EPS = 1e-12;

    @Nested
    @DisplayName("grid access")
    class Grid {
        @Test
        @DisplayName("block, blockAt, setBlock, index and sizes")
        void grid() {
            WorldMap flat = TestMaps.build("", "x _", "#  ");
            WorldMap wrap = TestMaps.build("edgeWrap: yes", "x _", "#  ");
            flat.setBlock(1, 0, BlockType.FRICTION);
            assertAll(
                () -> assertEquals(105, flat.pixelWidth(), "pixel width = 3 * 35"),
                () -> assertEquals(70, flat.pixelHeight(), "pixel height = 2 * 35"),
                () -> assertEquals(5, flat.index(2, 1), "index is row-major from the bottom row"),
                () -> assertEquals(BlockType.FILLED, flat.blocks()[flat.index(0, 1)], "blocks() is the row-major grid"),
                () -> assertEquals(BlockType.FRICTION, flat.block(1, 0), "setBlock changes the grid"),
                () -> assertEquals(BlockType.FILLED, flat.blockAt(-1, 0), "left of a flat world is a wall"),
                () -> assertEquals(BlockType.FILLED, flat.blockAt(3, 0), "right of a flat world is a wall"),
                () -> assertEquals(BlockType.FILLED, flat.blockAt(0, -1), "below a flat world is a wall"),
                () -> assertEquals(BlockType.FILLED, flat.blockAt(0, 2), "above a flat world is a wall"),
                () -> assertEquals(BlockType.FUEL, flat.blockAt(0, 0), "inside blockAt equals block"),
                () -> assertEquals(BlockType.BASE, wrap.blockAt(-1, 1), "wrapping: x=-1 is the last column"),
                () -> assertEquals(BlockType.FUEL, wrap.blockAt(3, 2), "wrapping: (3,2) is (0,0)"),
                () -> assertEquals(BlockType.FILLED, wrap.blockAt(0, -1), "wrapping: y=-1 is the top row"),
                () -> assertEquals(BlockType.FILLED, TestMaps.build("", "  _", "#  ").blockAt(3, 0),
                    "x == width is outside a flat world (not the next row's first block)"));
        }
    }

    @Test
    @DisplayName("row-major indexing on a non-square map")
    void indexing() {
        WorldMap m = TestMaps.build("gravity: 0", "-   ", "    ");
        m.setBlock(3, 1, BlockType.FRICTION);
        assertAll(
            () -> assertEquals(GravityFieldTest.F * GravityFieldTest.tx(3, 0), -m.gravityX(3, 1), EPS,
                "gravityX(3,1) is the block 3 right of the '-' at (0,1)"),
            () -> assertEquals(GravityFieldTest.F, m.gravityY(0, 0),
                EPS, "gravityY(0,0) is pulled up by the '-' directly above (tab[0][1].y = 1)"),
            () -> assertEquals(BlockType.FRICTION, m.blocks()[7], "setBlock(3,1) writes index 1*4+3"),
            () -> assertEquals("-  z\n    \n", m.dump(), "dump shows the changed block in the top row"),
            () -> assertEquals(0.0, m.gravityY(3, 1), EPS, "gravityY(3,1) is on the source's row: no y pull"),
            () -> assertEquals(GravityFieldTest.F * 1 / Math.pow(10, 1.5), m.gravityY(3, 0), EPS,
                "gravityY(3,0) is 3 right and 1 below the '-': force * tab[3][1].y"));
    }

    @Nested
    @DisplayName("wrap helpers")
    class Wrap {
        @Test
        @DisplayName("wrapDx/wrapDy/wrapLength only wrap in wrapping maps")
        void wrapHelpers() {
            WorldMap flat = TestMaps.build("", "    ", "    ");
            WorldMap wrap = TestMaps.build("edgeWrap: yes", "    ", "    ");
            assertAll(
                () -> assertEquals(-40.0, wrap.wrapDx(100), EPS, "dx 100 in a 140 px world wraps to -40"),
                () -> assertEquals(100.0, flat.wrapDx(100), EPS, "dx is unchanged without wrap"),
                () -> assertEquals(-30.0, wrap.wrapDy(40), EPS, "dy 40 in a 70 px world wraps to -30"),
                () -> assertEquals(40.0, flat.wrapDy(40), EPS, "dy is unchanged without wrap"),
                () -> assertEquals(50.0, wrap.wrapLength(100, 40), EPS, "length uses the wrapped deltas (30,40,50)"),
                () -> assertEquals(Math.hypot(100, 40), flat.wrapLength(100, 40), EPS, "length is plain without wrap"));
        }

        @Test
        @DisplayName("findClosestTeam uses the map's bases and wrap")
        void closest() {
            WorldMap m = TestMaps.build("teamPlay: yes\nedgeWrap: yes", "1   2     ");
            WorldMap ffa = TestMaps.build("", "1   2     ");
            assertAll(
                () -> assertEquals(1, m.findClosestTeam(9, 0), "block 9 is 1 from base 1 across the edge"),
                () -> assertEquals(2, m.findClosestTeam(5, 0), "block 5 is next to base 2"),
                () -> assertEquals(Const.TEAM_NOT_SET, ffa.findClosestTeam(5, 0), "no teams without team play"));
        }
    }

    @Nested
    @DisplayName("recomputeGravity")
    class Recompute {
        @Test
        @DisplayName("replaces the field from new options")
        void recompute() {
            WorldMap m = TestMaps.build("", "  ", "  ");
            Options o = new Options();
            ConfigParser.parse("gravity: 0.5\ngravityAngle: 0\n", "t", OptionOrigin.MAP, o);
            double before = m.gravityY(0, 0);
            m.recomputeGravity(o);
            assertAll(
                () -> assertEquals(-0.14, before, EPS, "starts with the default downward gravity"),
                () -> assertEquals(0.5, m.gravityX(1, 1), EPS, "new gravity points along angle 0"),
                () -> assertEquals(0.0, m.gravityY(1, 1), EPS, "old y gravity is replaced, not added"));
        }
    }

    @Nested
    @DisplayName("records")
    class RecordsTest {
        @Test
        @DisplayName("pixel centres and headings")
        void centres() {
            Base b = new Base(0, 2, 3, Dir.LEFT, 1, 1);
            assertAll(
                () -> assertEquals(87.5, b.x(), EPS, "base x is its block centre"),
                () -> assertEquals(122.5, b.y(), EPS, "base y is its block centre"),
                () -> assertEquals(Math.PI, b.headingRadians(), EPS, "LEFT is pi radians"),
                () -> assertEquals(87.5, new FuelStation(0, 2, 3, 0).x(), EPS, "fuel x centre"),
                () -> assertEquals(122.5, new FuelStation(0, 2, 3, 0).y(), EPS, "fuel y centre"),
                () -> assertEquals(87.5, new Target(0, 2, 3, 0).x(), EPS, "target x centre"),
                () -> assertEquals(122.5, new Target(0, 2, 3, 0).y(), EPS, "target y centre"),
                () -> assertEquals(87.5, new Treasure(0, 2, 3, 0, false).x(), EPS, "treasure x centre"),
                () -> assertEquals(122.5, new Treasure(0, 2, 3, 0, false).y(), EPS, "treasure y centre"),
                () -> assertEquals(87.5, new Wormhole(0, 2, 3, Wormhole.Type.IN).x(), EPS, "wormhole x centre"),
                () -> assertEquals(122.5, new Wormhole(0, 2, 3, Wormhole.Type.IN).y(), EPS, "wormhole y centre"),
                () -> assertEquals(87.5, new Concentrator(0, 2, 3).x(), EPS, "concentrator x centre"),
                () -> assertEquals(122.5, new Concentrator(0, 2, 3).y(), EPS, "concentrator y centre"),
                () -> assertEquals(87.5, new Checkpoint(1, 2, 3).x(), EPS, "checkpoint x centre"),
                () -> assertEquals(122.5, new Checkpoint(1, 2, 3).y(), EPS, "checkpoint y centre"),
                () -> assertEquals('B', new Checkpoint(1, 2, 3).letter(), "checkpoint 1 is 'B'"),
                () -> assertEquals(3 * Math.PI / 2, new Cannon(0, 0, 0, Dir.DOWN, 0, 0, 0).headingRadians(), EPS,
                    "a down cannon heads 3pi/2"),
                () -> assertEquals(Math.PI / 2, Dir.toRadians(Dir.UP), EPS, "UP is pi/2"));
        }
    }

    @Nested
    @DisplayName("BlockType")
    class BlockTypes {
        @Test
        @DisplayName("classification helpers")
        void classify() {
            assertAll(
                () -> assertEquals(1 << 5, BlockType.bit(5), "bit is 1 << type"),
                () -> assertTrue(BlockType.isWallOutline(BlockType.FUEL), "fuel is drawn as wall outline"),
                () -> assertTrue(BlockType.isWallOutline(BlockType.FILLED), "filled is wall outline"),
                () -> assertTrue(BlockType.isWallOutline(BlockType.REC_RD), "diagonals are wall outline"),
                () -> assertFalse(BlockType.isWallOutline(BlockType.CANNON), "cannons are not wall outline"),
                () -> assertTrue(BlockType.isDiagonal(BlockType.REC_LU), "REC_LU is diagonal"),
                () -> assertTrue(BlockType.isDiagonal(BlockType.REC_LD), "REC_LD is diagonal"),
                () -> assertTrue(BlockType.isDiagonal(BlockType.REC_RU), "REC_RU is diagonal"),
                () -> assertTrue(BlockType.isDiagonal(BlockType.REC_RD), "REC_RD is diagonal"),
                () -> assertFalse(BlockType.isDiagonal(BlockType.DECOR_LU), "decor is not a wall diagonal"),
                () -> assertTrue(BlockType.isSolid(BlockType.TREASURE), "treasure is solid"),
                () -> assertTrue(BlockType.isSolid(BlockType.TARGET), "target is solid"),
                () -> assertTrue(BlockType.isSolid(BlockType.CANNON), "cannon is solid"),
                () -> assertTrue(BlockType.isSolid(BlockType.FUEL), "fuel is solid"),
                () -> assertTrue(BlockType.isSolid(BlockType.FILLED), "filled is solid"),
                () -> assertTrue(BlockType.isSolid(BlockType.REC_RU), "diagonal is solid"),
                () -> assertFalse(BlockType.isSolid(BlockType.WORMHOLE), "wormhole is not solid"),
                () -> assertTrue(BlockType.isGravity(BlockType.POS_GRAV), "POS_GRAV is gravity"),
                () -> assertTrue(BlockType.isGravity(BlockType.NEG_GRAV), "NEG_GRAV is gravity"),
                () -> assertTrue(BlockType.isGravity(BlockType.CWISE_GRAV), "CWISE is gravity"),
                () -> assertTrue(BlockType.isGravity(BlockType.ACWISE_GRAV), "ACWISE is gravity"),
                () -> assertTrue(BlockType.isGravity(BlockType.UP_GRAV), "UP is gravity"),
                () -> assertTrue(BlockType.isGravity(BlockType.DOWN_GRAV), "DOWN is gravity"),
                () -> assertTrue(BlockType.isGravity(BlockType.RIGHT_GRAV), "RIGHT is gravity"),
                () -> assertTrue(BlockType.isGravity(BlockType.LEFT_GRAV), "LEFT is gravity"),
                () -> assertFalse(BlockType.isGravity(BlockType.FRICTION), "friction is not gravity"),
                () -> assertTrue(BlockType.isDecor(BlockType.DECOR_FILLED), "DECOR_FILLED is decor (lower bound)"),
                () -> assertTrue(BlockType.isDecor(BlockType.DECOR_RD), "DECOR_RD is decor (upper bound)"),
                () -> assertFalse(BlockType.isDecor(BlockType.TARGET), "the type below decor is not decor"),
                () -> assertFalse(BlockType.isDecor(BlockType.UP_GRAV), "the type above decor is not decor"),
                () -> assertEquals(BlockType.FILLED, BlockType.decorShape(BlockType.DECOR_FILLED), "decor filled shape"),
                () -> assertEquals(BlockType.REC_LU, BlockType.decorShape(BlockType.DECOR_LU), "decor LU shape"),
                () -> assertEquals(BlockType.REC_LD, BlockType.decorShape(BlockType.DECOR_LD), "decor LD shape"),
                () -> assertEquals(BlockType.REC_RU, BlockType.decorShape(BlockType.DECOR_RU), "decor RU shape"),
                () -> assertEquals(BlockType.REC_RD, BlockType.decorShape(BlockType.DECOR_RD), "decor RD shape"),
                () -> assertEquals(BlockType.FUEL, BlockType.decorShape(BlockType.FUEL), "non-decor is unchanged"));
        }

        @Test
        @DisplayName("names every code")
        void names() {
            List<String> names = new ArrayList<>();
            for (int t = 0; t < BlockType.COUNT; t++) {
                names.add(BlockType.name(t));
            }
            assertAll(
                () -> assertEquals(List.of("SPACE", "BASE", "FILLED", "REC_LU", "REC_LD", "REC_RU", "REC_RD", "FUEL",
                    "CANNON", "CHECK", "POS_GRAV", "NEG_GRAV", "CWISE_GRAV", "ACWISE_GRAV", "WORMHOLE", "TREASURE",
                    "TARGET", "ITEM_CONCENTRATOR", "DECOR_FILLED", "DECOR_LU", "DECOR_LD", "DECOR_RU", "DECOR_RD",
                    "UP_GRAV", "DOWN_GRAV", "RIGHT_GRAV", "LEFT_GRAV", "FRICTION", "ASTEROID_CONCENTRATOR"), names,
                    "names match map.h order"),
                () -> assertEquals("BASE_ATTRACTOR", BlockType.name(BlockType.BASE_ATTRACTOR), "names the attractor"),
                () -> assertEquals("UNKNOWN(99)", BlockType.name(99), "names unknown codes"));
        }
    }
}
