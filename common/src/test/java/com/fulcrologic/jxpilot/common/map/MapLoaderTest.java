package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.Options;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Map decoding (Grok_map port)")
class MapLoaderTest {
    static final double S = Const.BLOCK_SZ;
    /** Tolerance for pixel positions computed from C fractions like 0.333 (exact up to rounding). */
    static final double EPS = 1e-9;

    @Nested
    @DisplayName("fixture map")
    class Fixture {
        @Test
        @DisplayName("decodes exact positions, objects, teams and directions")
        void fixture() throws Exception {
            WorldMap m = WorldMap.load(Path.of("src", "test", "resources", "fixture.xp"));
            assertAll(
                () -> assertEquals("Fixture", m.name(), "reads the trimmed map name"),
                () -> assertEquals("Test Suite", m.author(), "reads the author"),
                () -> assertEquals(10, m.width(), "width from mapWidth"),
                () -> assertEquals(6, m.height(), "height from mapHeight"),
                () -> assertTrue(m.teamPlay(), "team play from options"),
                () -> assertFalse(m.edgeWrap(), "no wrap from options"),
                () -> assertEquals(List.of(new Base(0, 1, 4, Dir.UP, 1, 1), new Base(1, 8, 4, Dir.LEFT, 2, 2)),
                    m.bases(), "bases in x-major order with team digits; '$' left of base 2 points it left"),
                () -> assertEquals(List.of(new Treasure(0, 3, 4, 1, false)), m.treasures(),
                    "the treasure belongs to the closest base's team"),
                () -> assertEquals(List.of(new Target(0, 6, 4, 2)), m.targets(), "the target belongs to team 2"),
                () -> assertEquals(List.of(new FuelStation(0, 1, 2, 1), new FuelStation(1, 8, 2, 2)), m.fuels(),
                    "fuel stations belong to the nearest team"),
                () -> assertEquals(List.of(new Cannon(0, 3, 2, Dir.UP, 3.5 * S, (2 + 0.333) * S, 1)), m.cannons(),
                    "teamCannons assigns the cannon to the nearest team"),
                () -> assertEquals(List.of(new Wormhole(0, 4, 2, Wormhole.Type.NORMAL),
                    new Wormhole(1, 6, 2, Wormhole.Type.NORMAL)), m.wormholes(), "two normal wormholes are kept"),
                () -> assertEquals(BlockType.REC_LU, m.block(1, 3), "'s' at text row 2 is block y 3"),
                () -> assertEquals(BlockType.REC_RU, m.block(8, 3), "'a' decodes to REC_RU"),
                () -> assertEquals(BlockType.REC_LD, m.block(1, 1), "'w' decodes to REC_LD"),
                () -> assertEquals(BlockType.REC_RD, m.block(8, 1), "'q' decodes to REC_RD"),
                () -> assertEquals(BlockType.SPACE, m.block(7, 4), "the '$' becomes space after use"),
                () -> assertEquals(1, m.itemId(8, 2), "itemId indexes the fuel list"),
                () -> assertEquals(-1, m.itemId(0, 0), "walls have no item id"),
                () -> assertEquals(2, m.numTeamBases(), "two teams have bases"),
                () -> assertEquals(1, m.teamBaseCount(2), "team 2 has one base"),
                () -> assertEquals(List.of(), m.warnings(), "a clean map has no warnings"),
                () -> assertEquals(String.join("\n", "xxxxxxxxxx", "x1 *  ! 2x", "xs      ax", "x# r@ @ #x",
                    "xw      qx", "xxxxxxxxxx") + "\n", m.dump(), "dump reproduces the map without '$'"));
        }
    }

    @Nested
    @DisplayName("convert: block characters")
    class Chars {
        @Test
        @DisplayName("every character decodes to its block type")
        void allChars() {
            WorldMap m = TestMaps.build("", "x.#!%&zbhgyt^*Ee $");
            byte[] expect = {BlockType.FILLED, BlockType.SPACE, BlockType.FUEL, BlockType.TARGET,
                BlockType.ITEM_CONCENTRATOR, BlockType.ASTEROID_CONCENTRATOR, BlockType.FRICTION,
                BlockType.DECOR_FILLED, BlockType.DECOR_LU, BlockType.DECOR_RU, BlockType.DECOR_LD,
                BlockType.DECOR_RD, BlockType.TREASURE, BlockType.TREASURE, BlockType.SPACE, BlockType.SPACE,
                BlockType.SPACE, BlockType.SPACE};
            byte[] got = new byte[expect.length];
            for (int i = 0; i < got.length; i++) {
                got[i] = m.block(i, 0);
            }
            assertAll(
                () -> assertArrayEquals(expect, got, "x . # ! % & z b h g y t ^ * E e ' ' $ decode in order"),
                () -> assertEquals(List.of(new Treasure(0, 12, 0, Const.TEAM_NOT_SET, true),
                    new Treasure(1, 13, 0, Const.TEAM_NOT_SET, false)), m.treasures(),
                    "'^' is an empty treasure and '*' a full one; no team outside team play"),
                () -> assertEquals(List.of(new Concentrator(0, 4, 0)), m.itemConcentrators(), "'%' is an item concentrator"),
                () -> assertEquals(List.of(new Concentrator(0, 5, 0)), m.asteroidConcentrators(),
                    "'&' is an asteroid concentrator"),
                () -> assertEquals(List.of(new Target(0, 3, 0, Const.TEAM_NOT_SET)), m.targets(), "'!' is a target"),
                () -> assertEquals(List.of("Unknown map characters treated as space: 'e'", "WARNING: map has no bases!"),
                    m.warnings(), "unknown chars are reported once; a missing base is reported"));
        }

        @Test
        @DisplayName("diagonals, cannons and gravity characters")
        void shapes() {
            WorldMap m = TestMaps.build("", "aswqrdfc+-><imkj");
            assertAll(
                () -> assertEquals(BlockType.REC_RU, m.block(0, 0), "'a' is REC_RU (upper right solid)"),
                () -> assertEquals(BlockType.REC_LU, m.block(1, 0), "'s' is REC_LU (upper left solid)"),
                () -> assertEquals(BlockType.REC_LD, m.block(2, 0), "'w' is REC_LD (lower left solid)"),
                () -> assertEquals(BlockType.REC_RD, m.block(3, 0), "'q' is REC_RD (lower right solid)"),
                () -> assertEquals(List.of(
                    new Cannon(0, 4, 0, Dir.UP, 4.5 * S, 0.333 * S, Const.TEAM_NOT_SET),
                    new Cannon(1, 5, 0, Dir.LEFT, 5.667 * S, 0.5 * S, Const.TEAM_NOT_SET),
                    new Cannon(2, 6, 0, Dir.RIGHT, 6.333 * S, 0.5 * S, Const.TEAM_NOT_SET),
                    new Cannon(3, 7, 0, Dir.DOWN, 7.5 * S, 0.667 * S, Const.TEAM_NOT_SET)), m.cannons(),
                    "r/d/f/c face up/left/right/down with C's firing positions"),
                () -> assertEquals(List.of(
                    new GravitySource(0, 8, 0, BlockType.POS_GRAV, -Const.GRAVS_POWER),
                    new GravitySource(1, 9, 0, BlockType.NEG_GRAV, Const.GRAVS_POWER),
                    new GravitySource(2, 10, 0, BlockType.CWISE_GRAV, Const.GRAVS_POWER),
                    new GravitySource(3, 11, 0, BlockType.ACWISE_GRAV, -Const.GRAVS_POWER),
                    new GravitySource(4, 12, 0, BlockType.UP_GRAV, Const.GRAVS_POWER),
                    new GravitySource(5, 13, 0, BlockType.DOWN_GRAV, -Const.GRAVS_POWER),
                    new GravitySource(6, 14, 0, BlockType.RIGHT_GRAV, Const.GRAVS_POWER),
                    new GravitySource(7, 15, 0, BlockType.LEFT_GRAV, -Const.GRAVS_POWER)), m.gravitySources(),
                    "+ - > < i m k j become gravity sources with C's force signs"),
                () -> assertEquals(BlockType.LEFT_GRAV, m.block(15, 0), "'j' block type is LEFT_GRAV"),
                () -> assertEquals("aswqrdfc+-><imkj\n", m.dump(), "dump inverts the decoding"));
        }

        @Test
        @DisplayName("C object order is x-major then y ascending")
        void order() {
            WorldMap m = TestMaps.build("",
                "# #",
                "## ");
            assertAll(
                () -> assertEquals(List.of(new FuelStation(0, 0, 0, Const.TEAM_NOT_SET),
                    new FuelStation(1, 0, 1, Const.TEAM_NOT_SET), new FuelStation(2, 1, 0, Const.TEAM_NOT_SET),
                    new FuelStation(3, 2, 1, Const.TEAM_NOT_SET)), m.fuels(), "orders by x then by y from the bottom"));
        }
    }

    @Nested
    @DisplayName("convert: bases and teams")
    class Bases {
        @Test
        @DisplayName("team digits apply only in team play")
        void teams() {
            WorldMap tp = TestMaps.build("teamPlay: yes", "_1 2 9");
            WorldMap ffa = TestMaps.build("", "_3");
            assertAll(
                () -> assertEquals(List.of(new Base(0, 0, 0, Dir.UP, 0, -1), new Base(1, 1, 0, Dir.UP, 1, 1),
                    new Base(2, 3, 0, Dir.UP, 2, 2), new Base(3, 5, 0, Dir.UP, 9, 9)), tp.bases(),
                    "team play: '_' is team 0 and digits are their team"),
                () -> assertEquals(4, tp.numTeamBases(), "counts teams with bases"),
                () -> assertEquals(1, tp.teamBaseCount(9), "counts bases per team"),
                () -> assertEquals(List.of(new Base(0, 0, 0, Dir.UP, Const.TEAM_NOT_SET, -1),
                    new Base(1, 1, 0, Dir.UP, Const.TEAM_NOT_SET, 3)), ffa.bases(),
                    "without team play bases have no team but keep their digit"),
                () -> assertEquals(0, ffa.numTeamBases(), "no team bases without team play"));
        }

        @Test
        @DisplayName("two bases of one team count the team once")
        void sameTeam() {
            WorldMap m = TestMaps.build("teamPlay: yes", "1 1");
            assertAll(
                () -> assertEquals(1, m.numTeamBases(), "one team"),
                () -> assertEquals(2, m.teamBaseCount(1), "two bases for team 1"));
        }

        @Test
        @DisplayName("treasures, targets, fuel and cannons go to the closest team only in team play")
        void closestTeam() {
            String[] rows = {"1 *!      ! * 2", "#      r      #"};
            WorldMap m = TestMaps.build("teamPlay: yes\nteamCannons: yes", rows);
            WorldMap noCannons = TestMaps.build("teamPlay: yes", rows);
            WorldMap ffa = TestMaps.build("", rows);
            WorldMap orphan = TestMaps.build("teamPlay: yes", "*!#");
            assertAll(
                () -> assertEquals(List.of(1, 2), List.of(m.treasures().get(0).team(), m.treasures().get(1).team()),
                    "treasures go to the nearer base's team"),
                () -> assertEquals(List.of(1, 2), List.of(m.targets().get(0).team(), m.targets().get(1).team()),
                    "targets go to the nearer base's team"),
                () -> assertEquals(List.of(1, 2), List.of(m.fuels().get(0).team(), m.fuels().get(1).team()),
                    "fuel stations go to the nearer base's team"),
                () -> assertEquals(1, m.cannons().get(0).team(), "an equidistant cannon goes to the first base's team"),
                () -> assertEquals(Const.TEAM_NOT_SET, noCannons.cannons().get(0).team(),
                    "cannons keep no team without teamCannons"),
                () -> assertEquals(Const.TEAM_NOT_SET, ffa.treasures().get(0).team(), "no teams without team play"),
                () -> assertEquals(Const.TEAM_NOT_SET, ffa.fuels().get(0).team(), "no fuel teams without team play"),
                () -> assertEquals(List.of("WARNING: map has no bases!",
                    "Couldn't find a matching team for the treasure.",
                    "Couldn't find a matching team for the target.",
                    "Couldn't find a matching team for the fuelstation."), orphan.warnings(),
                    "objects without any team base are reported"));
        }

        @Test
        @DisplayName("closestTeam measures wrapped pixel distance and skips unteamed bases")
        void closestTeamFunction() {
            List<Base> bases = List.of(new Base(0, 0, 0, Dir.UP, Const.TEAM_NOT_SET, -1),
                new Base(1, 1, 0, Dir.UP, 4, 4), new Base(2, 8, 0, Dir.UP, 7, 7));
            List<Base> near = List.of(new Base(0, 1, 0, Dir.UP, 4, 4), new Base(1, 5, 0, Dir.UP, 7, 7));
            assertAll(
                () -> assertEquals(4, MapLoader.closestTeam(bases, 0, 0, 10, 1, false),
                    "ignores the unteamed base on the same block"),
                () -> assertEquals(7, MapLoader.closestTeam(bases, 9, 0, 10, 1, false), "picks the nearest base"),
                () -> assertEquals(7, MapLoader.closestTeam(near, 9, 0, 10, 1, false),
                    "without wrap block 9 is nearer to x=5 (4) than x=1 (8)"),
                () -> assertEquals(4, MapLoader.closestTeam(near, 9, 0, 10, 1, true),
                    "with wrap block 9 is 2 from x=1 across the edge"),
                () -> assertEquals(4, MapLoader.closestTeam(near, 3, 0, 10, 1, false),
                    "an exact tie goes to the earlier base"),
                () -> assertEquals(Const.TEAM_NOT_SET, MapLoader.closestTeam(List.of(bases.get(0)), 3, 0, 10, 1, false),
                    "no teamed base gives TEAM_NOT_SET"),
                () -> assertEquals(4, MapLoader.closestTeam(List.of(new Base(0, 1, 0, Dir.UP, 4, 4),
                        new Base(1, 8, 0, Dir.UP, 7, 7)), 3, 0, 20, 1, false),
                    "query between bases picks the nearer smaller-x base (2 vs 5 blocks)"),
                () -> assertEquals(4, MapLoader.closestTeam(List.of(new Base(0, 0, 1, Dir.UP, 4, 4),
                        new Base(1, 0, 8, Dir.UP, 7, 7)), 0, 3, 1, 20, false),
                    "vertically too: (0,3) is nearer to y=1 than y=8"),
                () -> assertEquals(7, MapLoader.closestTeam(List.of(new Base(0, 0, 1, Dir.UP, 4, 4),
                        new Base(1, 0, 5, Dir.UP, 7, 7)), 0, 4, 1, 20, false),
                    "(0,4) is nearer to y=5 than y=1 (the query y counts)"),
                () -> assertEquals(4, MapLoader.closestTeam(List.of(new Base(0, 0, 0, Dir.UP, 4, 4),
                        new Base(1, 0, 5, Dir.UP, 7, 7)), 0, 9, 1, 10, true),
                    "vertical wrap: y=9 is 1 from y=0 across the edge"));
        }
    }

    @Nested
    @DisplayName("baseDirection")
    class BaseDirection {
        @Test
        @DisplayName("points against gravity, rounded to an axis, unless an attractor applies")
        void directions() {
            assertAll(
                () -> assertEquals(Dir.UP, MapLoader.baseDirection(0, 0, false, false, false, false),
                    "zero gravity faces up"),
                () -> assertEquals(Dir.UP, MapLoader.baseDirection(0, -0.14, false, false, false, false),
                    "gravity down faces up"),
                () -> assertEquals(Dir.DOWN, MapLoader.baseDirection(0, 0.14, false, false, false, false),
                    "gravity up faces down"),
                () -> assertEquals(Dir.RIGHT, MapLoader.baseDirection(-1, 0, false, false, false, false),
                    "gravity left faces right"),
                () -> assertEquals(Dir.LEFT, MapLoader.baseDirection(1, 0, false, false, false, false),
                    "gravity right faces left"),
                () -> assertEquals(Dir.RIGHT, MapLoader.baseDirection(-1, -0.9, false, false, false, false),
                    "a 42 degree anti-gravity rounds to right"),
                () -> assertEquals(Dir.UP, MapLoader.baseDirection(-1, -1, false, false, false, false),
                    "exactly 45 degrees (RES 16) rounds counter-clockwise to up"),
                () -> assertEquals(Dir.UP, MapLoader.baseDirection(-1, -1.1, false, false, false, false),
                    "a 48 degree anti-gravity rounds to up"),
                () -> assertEquals(Dir.RIGHT, MapLoader.baseDirection(-1, 1e-9, false, false, false, false),
                    "anti-gravity just below +x (RES 127) wraps to right, not 128"),
                () -> assertEquals(Dir.UP, MapLoader.baseDirection(0, -1, true, true, true, true),
                    "an up attractor wins first"),
                () -> assertEquals(Dir.DOWN, MapLoader.baseDirection(0, -1, false, true, true, true),
                    "without up, a down attractor wins next"),
                () -> assertEquals(Dir.RIGHT, MapLoader.baseDirection(0, -1, false, false, true, true),
                    "then right"),
                () -> assertEquals(Dir.LEFT, MapLoader.baseDirection(0, -1, false, false, false, true), "then left"),
                () -> assertEquals(Dir.RIGHT, MapLoader.baseDirection(-1, 0, true, false, true, false),
                    "a later attractor matching the gravity heading overrides an earlier one"),
                () -> assertEquals(Dir.DOWN, MapLoader.baseDirection(0, 1, true, true, false, false),
                    "down overrides up when gravity faces down"),
                () -> assertEquals(Dir.LEFT, MapLoader.baseDirection(1, 0, false, true, false, true),
                    "left overrides down when gravity faces left"));
        }

        @Test
        @DisplayName("attractors across the world edge count only with wrap")
        void wrappedAttractors() {
            String[] rows = {"_ ", "  ", "$ "};
            WorldMap wrap = TestMaps.build("edgeWrap: yes\ngravity: 0.14", rows);
            WorldMap noWrap = TestMaps.build("gravity: 0\ngravityAngle: 0", "  ", "_ ", "$ ");
            WorldMap edgeNoWrap = TestMaps.build("gravity: 0.14", rows);
            WorldMap leftWrap = TestMaps.build("edgeWrap: yes\ngravity: 0", "_ $");
            WorldMap downWrap = TestMaps.build("edgeWrap: yes\ngravity: 0", "$", " ", "_");
            WorldMap rightWrap = TestMaps.build("edgeWrap: yes\ngravity: 0", "$ _");
            WorldMap localGrav = TestMaps.build("gravity: 0", "_ -", "   ", "   ");
            WorldMap belowSource = TestMaps.build("gravity: 0", "_  ", "-  ", "   ");
            WorldMap rotating = TestMaps.build("gravity: 0", "_  ", " > ", "   ");
            assertAll(
                () -> assertEquals(Dir.UP, wrap.bases().get(0).dir(), "a '$' on the bottom row is above a top base (beats upward gravity)"),
                () -> assertEquals(Dir.DOWN, noWrap.bases().get(0).dir(), "a '$' directly below points down"),
                () -> assertEquals(Dir.DOWN, edgeNoWrap.bases().get(0).dir(),
                    "without wrap the far '$' is ignored (upward gravity faces down)"),
                () -> assertEquals(Dir.LEFT, leftWrap.bases().get(0).dir(), "'$' at the right end is left of x=0"),
                () -> assertEquals(Dir.DOWN, downWrap.bases().get(0).dir(), "'$' on the top row is below y=0"),
                () -> assertEquals(Dir.RIGHT, rightWrap.bases().get(0).dir(), "'$' at x=0 is right of the last column"),
                () -> assertEquals(Dir.LEFT, localGrav.bases().get(0).dir(),
                    "a base at (0,2) reads the gravity of its own block (pulled right by the '-')"),
                () -> assertEquals(Dir.UP, belowSource.bases().get(0).dir(),
                    "a '-' below the base pulls down at the base's block, so it faces up"),
                () -> assertEquals(Dir.DOWN, rotating.bases().get(0).dir(),
                    "a '>' at (1,1) pushes the base's block up-right, so it faces down (x and y from its own block)"));
        }
    }

    @Nested
    @DisplayName("readGrid")
    class ReadGrid {
        @Test
        @DisplayName("pads short rows, truncates long rows and reports them")
        void padTruncate() {
            List<String> warn = new ArrayList<>();
            char[] g = MapLoader.readGrid("xx\nxxxxxxx\nx\n", 4, 4, false, warn);
            assertAll(
                () -> assertEquals("x   ", new String(g, 4, 4), "row 3 (y=1) is padded"),
                () -> assertEquals("xxxx", new String(g, 8, 4), "row 2 (y=2) is truncated to the width"),
                () -> assertEquals("xx  ", new String(g, 12, 4), "row 1 (y=3) is padded"),
                () -> assertEquals("    ", new String(g, 0, 4), "a missing row is all spaces"),
                () -> assertEquals(List.of("Not enough map data on map data line 1",
                    "Map data contains extraneous characters on line 2",
                    "Not enough map data on map data line 3",
                    "Not enough map data on map data line 4"), warn, "reports each bad row with its 1-based line"));
        }

        @Test
        @DisplayName("limits repeated reports with 'And so on...'")
        void limitReports() {
            List<String> warn = new ArrayList<>();
            MapLoader.readGrid("", 2, 8, false, warn);
            assertEquals(List.of("Not enough map data on map data line 1", "Not enough map data on map data line 2",
                "Not enough map data on map data line 3", "Not enough map data on map data line 4",
                "Not enough map data on map data line 5", "And so on..."), warn,
                "reports five rows, then one 'And so on...', then nothing");
        }

        @Test
        @DisplayName("a long last row without newline is truncated at the end of data")
        void longLastRow() {
            List<String> warn = new ArrayList<>();
            char[] g = MapLoader.readGrid("abc", 2, 1, false, warn);
            assertAll(
                () -> assertEquals("ab", new String(g), "keeps the first width chars"),
                () -> assertEquals(List.of("Map data contains extraneous characters on line 1"), warn,
                    "reports the long row"));
        }

        @Test
        @DisplayName("extraBorder surrounds the data with walls")
        void extraBorder() {
            WorldMap m = TestMaps.build(2, 2, "extraBorder: yes", "_ ", "#x");
            assertAll(
                () -> assertEquals(4, m.width(), "width grows by 2"),
                () -> assertEquals(4, m.height(), "height grows by 2"),
                () -> assertEquals("xxxx\nx_ x\nx#xx\nxxxx\n", m.dump(), "data sits inside a ring of walls"),
                () -> assertEquals(List.of(), m.warnings(), "the border does not cause row warnings"));
        }
    }

    @Nested
    @DisplayName("race mode")
    class Race {
        @Test
        @DisplayName("checkpoints exist only in race mode, which disables team play and needs checkpoints")
        void checkpoints() {
            WorldMap off = TestMaps.build("", "_A B");
            WorldMap race = TestMaps.build("timing: yes\nteamPlay: yes", "_A B");
            WorldMap noChecks = TestMaps.build("timing: yes", "_   ");
            assertAll(
                () -> assertEquals(BlockType.SPACE, off.block(1, 0), "letters are space outside race mode"),
                () -> assertEquals(List.of(), off.checkpoints(), "no checkpoints outside race mode"),
                () -> assertEquals(List.of(), TestMaps.build("", "_AZ").warnings(),
                    "letters A and Z outside race mode are silent spaces, not unknown characters"),
                () -> assertEquals("_0\n", TestMaps.build("", "_0").dump(), "dump shows team digit 0 as '0'"),
                () -> assertTrue(race.timing(), "race mode is on"),
                () -> assertFalse(race.teamPlay(), "race mode turns team play off"),
                () -> assertEquals(BlockType.CHECK, race.block(1, 0), "letters are checkpoints in race mode"),
                () -> assertEquals(1, race.itemId(3, 0), "a checkpoint's item id is its letter number"),
                () -> assertEquals(List.of(new Checkpoint(0, 1, 0), new Checkpoint(1, 3, 0)), race.checkpoints(),
                    "checkpoints are listed by letter"),
                () -> assertEquals("_A B\n", race.dump(), "dump shows checkpoints as their letters"),
                () -> assertEquals(List.of("Cannot teamplay while in race mode -- ignoring teamplay"), race.warnings(),
                    "reports dropping team play"),
                () -> assertFalse(noChecks.timing(), "race mode without checkpoints turns off"),
                () -> assertEquals(List.of(
                    "No checkpoints found while race mode (timing) was set. Turning off race mode."),
                    noChecks.warnings(), "reports turning race mode off"),
                () -> assertArrayEquals(new int[0], noChecks.baseOrder(), "no base order outside race mode"));
        }

        @Test
        @DisplayName("checkpoint letters: every occurrence counts, A starts the race, problems are reported")
        void checkpointRules() {
            WorldMap m = TestMaps.build("timing: yes", "_B C B");
            WorldMap ok = TestMaps.build("timing: yes", "_A B ");
            assertAll(
                () -> assertEquals(3, m.numChecks(), "every letter occurrence counts (C NumChecks)"),
                () -> assertEquals(List.of(new Checkpoint(1, 5, 0), new Checkpoint(2, 3, 0)), m.checkpoints(),
                    "a duplicate letter keeps its last occurrence in C order"),
                () -> assertEquals(List.of("Checkpoint A is missing; the race starts from block (0,0)",
                    "Checkpoint B appears 2 times", "Checkpoint letters are not contiguous from A"), m.warnings(),
                    "missing A, duplicates and gaps are reported"),
                () -> assertTrue(m.timing(), "race mode stays on with any checkpoint"),
                () -> assertEquals(2, ok.numChecks(), "two letters, two checks"),
                () -> assertEquals(List.of(), ok.warnings(), "contiguous letters from A are clean"));
        }

        @Test
        @DisplayName("checkpointWarnings handles gaps after A and late letters")
        void checkpointWarnings() {
            int[] gapAfterA = new int[26];
            gapAfterA[0] = 1;
            gapAfterA[2] = 1;
            int[] onlyA = new int[26];
            onlyA[0] = 1;
            List<String> w1 = new ArrayList<>();
            List<String> w2 = new ArrayList<>();
            MapLoader.checkpointWarnings(gapAfterA, w1);
            MapLoader.checkpointWarnings(onlyA, w2);
            assertAll(
                () -> assertEquals(List.of("Checkpoint letters are not contiguous from A"), w1, "A, C has a gap"),
                () -> assertEquals(List.of(), w2, "A alone is fine"));
        }

        @Test
        @DisplayName("without A the race order is measured from block (0,0)")
        void missingA() {
            WorldMap m = TestMaps.build("timing: yes", "  _  B _");
            assertArrayEquals(new int[] {0, 1}, m.baseOrder(), "the base at x=2 is nearer to (0,0) than x=7");
        }

        @Test
        @DisplayName("base order is by distance to checkpoint A, ties in base order")
        void baseOrder() {
            WorldMap m = TestMaps.build("timing: yes", "_ _A _ __");
            assertAll(
                () -> assertArrayEquals(new int[] {1, 2, 0, 3, 4}, m.baseOrder(),
                    "bases at distances 3,1,2,4,5 from A are ordered 1,2,0,3,4"),
                () -> assertArrayEquals(new int[] {2, 0, 1}, MapLoader.stableOrder(new double[] {5, 5, 1}),
                    "stableOrder sorts ascending keeping ties in order"),
                () -> assertArrayEquals(new int[] {0, 1, 2}, MapLoader.stableOrder(new double[] {1, 2, 3}),
                    "an ordered input is unchanged"),
                () -> assertArrayEquals(new int[] {2, 1, 0}, MapLoader.stableOrder(new double[] {3, 2, 1}),
                    "a reversed input is reversed"));
        }

        @Test
        @DisplayName("base order distance wraps")
        void baseOrderWraps() {
            WorldMap m = TestMaps.build("timing: yes\nedgeWrap: yes", "A  _    _");
            WorldMap vertical = TestMaps.build("timing: yes", " ", " ", " ", "_", " ", " ", "A", "_");
            WorldMap vwrap = TestMaps.build("timing: yes\nedgeWrap: yes", "A", " ", "_", " ", " ", " ", " ", " ", " ", "_");
            WorldMap vertical2 = TestMaps.build("timing: yes", "_", " ", "A", " ", " ", "_");
            assertAll(
                () -> assertArrayEquals(new int[] {1, 0}, m.baseOrder(), "the base at x=8 is 1 block away across the edge"),
                () -> assertArrayEquals(new int[] {0, 1}, vertical.baseOrder(),
                    "vertical distances: the base 1 below A comes before the one 3 above"),
                () -> assertArrayEquals(new int[] {0, 1}, vwrap.baseOrder(),
                    "vertical wrap: the bottom base is 1 below the top-row A across the edge"),
                () -> assertArrayEquals(new int[] {1, 0}, vertical2.baseOrder(),
                    "A at y=3: the base at y=5 (2 away) precedes the one at y=0 (3 away)"));
        }
    }

    @Nested
    @DisplayName("wormholes")
    class Wormholes {
        @Test
        @DisplayName("inconsistent wormhole sets are removed, consistent ones kept")
        void consistency() {
            WorldMap lonelyIn = TestMaps.build("", "_(  ");
            WorldMap inOut = TestMaps.build("", "_( )");
            WorldMap lonelyNormal = TestMaps.build("", "_@  ");
            WorldMap upper = TestMaps.build("", "_(  ", "xx  ");
            assertAll(
                () -> assertEquals(List.of(), lonelyIn.wormholes(), "an in-hole without out-hole is removed"),
                () -> assertEquals(BlockType.SPACE, lonelyIn.block(1, 0), "removed wormholes become space"),
                () -> assertEquals(-1, lonelyIn.itemId(1, 0), "removed wormholes lose their item id"),
                () -> assertEquals(List.of("Inconsistent use of wormholes, removing them."), lonelyIn.warnings(),
                    "removal is reported"),
                () -> assertEquals(List.of(new Wormhole(0, 1, 0, Wormhole.Type.IN), new Wormhole(1, 3, 0, Wormhole.Type.OUT)),
                    inOut.wormholes(), "an in/out pair is kept"),
                () -> assertEquals(List.of(), lonelyNormal.wormholes(), "a single normal wormhole is removed"),
                () -> assertEquals(BlockType.SPACE, upper.block(1, 1), "a removed wormhole on row 1 becomes space"),
                () -> assertEquals(BlockType.FILLED, upper.block(1, 0), "and nothing else changes"));
        }

        @Test
        @DisplayName("wormholesInconsistent follows the C rule for each case")
        void rule() {
            Wormhole n = new Wormhole(0, 0, 0, Wormhole.Type.NORMAL);
            Wormhole in = new Wormhole(0, 0, 0, Wormhole.Type.IN);
            Wormhole out = new Wormhole(0, 0, 0, Wormhole.Type.OUT);
            assertAll(
                () -> assertFalse(MapLoader.wormholesInconsistent(List.of()), "no wormholes is consistent"),
                () -> assertTrue(MapLoader.wormholesInconsistent(List.of(n)), "one normal is inconsistent"),
                () -> assertFalse(MapLoader.wormholesInconsistent(List.of(n, n)), "two normals are consistent"),
                () -> assertFalse(MapLoader.wormholesInconsistent(List.of(n, out)), "normal + out is consistent"),
                () -> assertTrue(MapLoader.wormholesInconsistent(List.of(n, in)), "normal + in is inconsistent"),
                () -> assertTrue(MapLoader.wormholesInconsistent(List.of(in)), "in without out is inconsistent"),
                () -> assertFalse(MapLoader.wormholesInconsistent(List.of(in, out)), "in + out is consistent"),
                () -> assertTrue(MapLoader.wormholesInconsistent(List.of(out)), "out alone is inconsistent"));
        }
    }

    @Nested
    @DisplayName("build validation")
    class Validation {
        @Test
        @DisplayName("rejects missing data and out-of-range sizes, accepts the limits")
        void sizes() {
            assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> WorldMap.fromOptions(new Options()),
                    "missing mapData is rejected"),
                () -> assertThrows(IllegalArgumentException.class, () -> TestMaps.build(0, 1, "", "x"),
                    "width 0 is rejected"),
                () -> assertThrows(IllegalArgumentException.class, () -> TestMaps.build(1, 0, "", "x"),
                    "height 0 is rejected"),
                () -> assertThrows(IllegalArgumentException.class, () -> TestMaps.build(901, 1, "", "x"),
                    "width 901 is rejected"),
                () -> assertThrows(IllegalArgumentException.class, () -> TestMaps.build(1, 901, "", "x"),
                    "height 901 is rejected"),
                () -> assertEquals(900, TestMaps.build(900, 1, "", "_").width(), "width 900 is accepted"),
                () -> assertEquals(900, TestMaps.build(1, 900, "", "_").height(), "height 900 is accepted"),
                () -> assertEquals(1, TestMaps.build(1, 1, "", "_").width(), "width 1 is accepted"));
        }
    }
}
