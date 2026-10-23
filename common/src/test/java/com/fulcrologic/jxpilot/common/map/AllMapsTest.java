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
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: every shipped map loads, and every cell decodes to the type an independent
 * char table predicts (the y flip included). Object positions are compared to positions found in the
 * raw text in C order (x-major, then y from the bottom).
 */
@DisplayName("Shipped maps")
class AllMapsTest {
    static final Path MAPS = Path.of("..", "maps");

    /** Independent char table (README.MAPS2). Checkpoint letters and wormholes are handled separately. */
    static final Map<Character, Byte> TYPES = new HashMap<>();

    static {
        String chars = " .xaswq#rdfc*^!%&_0123456789+-><imkj@()zbhgyt$";
        byte[] types = {BlockType.SPACE, BlockType.SPACE, BlockType.FILLED, BlockType.REC_RU, BlockType.REC_LU,
            BlockType.REC_LD, BlockType.REC_RD, BlockType.FUEL, BlockType.CANNON, BlockType.CANNON, BlockType.CANNON,
            BlockType.CANNON, BlockType.TREASURE, BlockType.TREASURE, BlockType.TARGET, BlockType.ITEM_CONCENTRATOR,
            BlockType.ASTEROID_CONCENTRATOR, BlockType.BASE, BlockType.BASE, BlockType.BASE, BlockType.BASE,
            BlockType.BASE, BlockType.BASE, BlockType.BASE, BlockType.BASE, BlockType.BASE, BlockType.BASE,
            BlockType.BASE, BlockType.POS_GRAV, BlockType.NEG_GRAV, BlockType.CWISE_GRAV, BlockType.ACWISE_GRAV,
            BlockType.UP_GRAV, BlockType.DOWN_GRAV, BlockType.RIGHT_GRAV, BlockType.LEFT_GRAV, BlockType.WORMHOLE,
            BlockType.WORMHOLE, BlockType.WORMHOLE, BlockType.FRICTION, BlockType.DECOR_FILLED, BlockType.DECOR_LU,
            BlockType.DECOR_RU, BlockType.DECOR_LD, BlockType.DECOR_RD, BlockType.SPACE};
        for (int i = 0; i < chars.length(); i++) {
            TYPES.put(chars.charAt(i), types[i]);
        }
    }

    static List<Path> mapFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(MAPS)) {
            s.filter(p -> p.toString().endsWith(".xp")).sorted().forEach(out::add);
        }
        return out;
    }

    /** Positions of chars in {@code set}, x-major then y ascending, as "x,y". */
    static List<String> positions(String[] rows, int w, int h, String set) {
        List<String> out = new ArrayList<>();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (set.indexOf(rows[h - 1 - y].charAt(x)) >= 0) {
                    out.add(x + "," + y);
                }
            }
        }
        return out;
    }

    @TestFactory
    @DisplayName("every map decodes exactly")
    Stream<DynamicTest> allMapsLoad() throws IOException {
        List<Path> files = mapFiles();
        assertEquals(19, files.size(), "the repository ships 19 maps");
        return files.stream().map(p -> DynamicTest.dynamicTest(p.getFileName().toString(), () -> check(p)));
    }

    private static void check(Path p) throws IOException {
        Options o = new Options();
        ConfigParser.parseFile(p, OptionOrigin.MAP, o);
        String data = o.mapData();
        int w = o.getInt(Opt.MAP_WIDTH);
        int h = o.getInt(Opt.MAP_HEIGHT);
        WorldMap m = WorldMap.fromOptions(o);
        String[] rows = data.split("\n", -1);
        boolean wormsKept = !m.wormholes().isEmpty();

        byte[] expected = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                char c = rows[h - 1 - y].charAt(x);
                byte t = c >= 'A' && c <= 'Z' ? (m.timing() ? BlockType.CHECK : BlockType.SPACE) : TYPES.get(c);
                if (t == BlockType.WORMHOLE && !wormsKept) {
                    t = BlockType.SPACE;
                }
                expected[y * w + x] = t;
            }
        }
        List<String> bases = new ArrayList<>();
        for (Base b : m.bases()) {
            bases.add(b.bx() + "," + b.by());
        }
        List<String> fuels = new ArrayList<>();
        for (FuelStation f : m.fuels()) {
            fuels.add(f.bx() + "," + f.by());
        }
        List<String> cannons = new ArrayList<>();
        for (Cannon c : m.cannons()) {
            cannons.add(c.bx() + "," + c.by());
        }
        assertAll(p.getFileName().toString(),
            () -> assertEquals(w, m.width(), "width equals mapWidth"),
            () -> assertEquals(h, m.height(), "height equals mapHeight"),
            () -> assertEquals(List.of(), m.warnings(), "loads without warnings"),
            () -> assertArrayEquals(expected, m.blocks(), "every cell decodes per the char table with row 0 on top"),
            () -> assertEquals(positions(rows, w, h, "_0123456789"), bases, "bases at their text positions in C order"),
            () -> assertEquals(positions(rows, w, h, "#"), fuels, "fuel stations at their text positions"),
            () -> assertEquals(positions(rows, w, h, "rdfc"), cannons, "cannons at their text positions"),
            () -> assertTrue(m.bases().size() > 0, "every shipped map has bases"),
            () -> assertTrue(m.bases().stream().allMatch(b -> m.teamPlay() == (b.team() != Const.TEAM_NOT_SET)),
                "bases have teams exactly when team play is on"),
            () -> assertTrue(m.treasures().stream().allMatch(t -> m.teamPlay() == (t.team() != Const.TEAM_NOT_SET)),
                "treasures have teams exactly when team play is on"),
            () -> assertTrue(WallGeometry.buildEdges(m).length > 0, "wall outlines exist"));
    }

    @Nested
    @DisplayName("specific maps")
    class Specific {
        @Test
        @DisplayName("grandprix is a race map with checkpoints A-M")
        void grandprix() throws IOException {
            WorldMap m = WorldMap.load(MAPS.resolve("grandprix.xp"));
            List<Character> letters = new ArrayList<>();
            m.checkpoints().forEach(c -> letters.add(c.letter()));
            assertAll(
                () -> assertTrue(m.timing(), "race mode is on"),
                () -> assertEquals(List.of('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M'), letters,
                    "checkpoints A..M in order"),
                () -> assertEquals(m.bases().size(), m.baseOrder().length, "every base has a start position"));
        }

        @Test
        @DisplayName("default.xp keeps its wormholes and wraps")
        void defaultMap() throws IOException {
            WorldMap m = WorldMap.load(MAPS.resolve("default.xp"));
            assertAll(
                () -> assertTrue(m.edgeWrap(), "edge wrap is on"),
                () -> assertFalse(m.teamPlay(), "team play is off"),
                () -> assertFalse(m.wormholes().isEmpty(), "wormholes are consistent and kept"),
                () -> assertTrue(m.bases().stream().anyMatch(b -> b.teamDigit() == 3), "keeps the '3' digit"));
        }

        @Test
        @DisplayName("war.xp assigns digit teams")
        void war() throws IOException {
            WorldMap m = WorldMap.load(MAPS.resolve("war.xp"));
            assertAll(
                () -> assertTrue(m.teamPlay(), "team play is on"),
                () -> assertTrue(m.bases().stream().allMatch(b -> b.team() == b.teamDigit()), "team equals digit"),
                () -> assertEquals(3, m.numTeamBases(), "teams 0, 1 and 2 have bases"));
        }
    }
}
