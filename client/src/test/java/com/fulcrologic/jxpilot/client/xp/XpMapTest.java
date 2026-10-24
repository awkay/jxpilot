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
package com.fulcrologic.jxpilot.client.xp;

import com.fulcrologic.jxpilot.client.Fixtures;
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.common.map.Dir;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Setup header parsing, map decoding and C object numbering. */
class XpMapTest {

    private static XpReader reader(byte[] a) {
        return new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
    }

    /** A complete XpSetup for map rows (top row first) as the C server would send it. */
    static XpSetup setupFor(String[] rows, int mode, int fps, String name, boolean compress) {
        int w = rows[0].length();
        int h = rows.length;
        boolean team = (mode & XpProto.MODE_TEAM_PLAY) != 0;
        boolean timing = (mode & XpProto.MODE_TIMING) != 0;
        byte[] blocks = CPackets.setupBlocks(rows, w, h, team, timing);
        byte[] data = compress ? CPackets.compress(blocks) : blocks;
        int order = compress ? XpProto.SETUP_MAP_ORDER_XY : XpProto.SETUP_MAP_UNCOMPRESSED;
        byte[] all = new CPackets().setupHeader(data.length, mode, 3, w, h, fps, order, name, "Tester")
            .raw(data).bytes();
        XpSetup s = new XpSetup();
        XpReader r = reader(all);
        assertTrue(s.readHeader(r) > 0, "fixture header parses");
        s.readMap(r);
        assertTrue(s.complete(), "fixture map complete");
        return s;
    }

    @Nested
    @DisplayName("XpSetup.readHeader / readMap")
    class Header {
        @Test
        void parsesAllFields() {
            byte[] a = new CPackets().setupHeader(10, 0x300, 3, 4, 5, 12, XpProto.SETUP_MAP_ORDER_XY, "Map", "Me")
                .raw(new byte[] {1, 2, 3}).bytes();
            XpSetup s = new XpSetup();
            XpReader r = reader(a);
            int n = s.readHeader(r);
            assertAll(
                () -> assertEquals(XpSetup.FIXED_HEADER + 4 + 3, n, "fixed part + both strings"),
                () -> assertEquals(10, s.mapDataLen, "map data length"),
                () -> assertEquals(0x300, s.mode, "mode"),
                () -> assertEquals(3, s.lives, "lives"),
                () -> assertEquals(4, s.width, "width"),
                () -> assertEquals(5, s.height, "height"),
                () -> assertEquals(12, s.fps, "fps"),
                () -> assertEquals(XpProto.SETUP_MAP_ORDER_XY, s.mapOrder, "order"),
                () -> assertEquals("Map", s.name.toString(), "name"),
                () -> assertEquals("Me", s.author.toString(), "author"),
                () -> assertTrue(s.haveHeader(), "header flag"),
                () -> assertFalse(s.complete(), "map bytes still missing"));
        }

        @Test
        void readMapTakesOnlyWhatIsMissing() {
            XpSetup s = new XpSetup();
            s.readHeader(reader(new CPackets().setupHeader(3, 0, 0, 2, 2, 12, 1, "n", "a").bytes()));
            int first = s.readMap(reader(new byte[] {7, 8}));
            XpReader second = reader(new byte[] {9, 10, 11});
            int took = s.readMap(second);
            assertAll(
                () -> assertEquals(2, first, "first part"),
                () -> assertEquals(1, took, "only the missing byte"),
                () -> assertEquals(2, second.remaining(), "rest left in the reader"),
                () -> assertTrue(s.complete(), "complete"),
                () -> assertEquals(3, s.received(), "received count"),
                () -> assertArrayEquals(new byte[] {7, 8, 9}, java.util.Arrays.copyOf(s.mapData(), 3), "bytes kept"));
        }

        @Test
        void incompleteHeadersConsumeNothing() {
            byte[] full = new CPackets().setupHeader(3, 0, 0, 2, 2, 12, 1, "name", "a").bytes();
            XpSetup s = new XpSetup();
            XpReader fixedOnly = new XpReader().wrap(ByteBuffer.wrap(full), 0, XpSetup.FIXED_HEADER - 1);
            XpReader inName = new XpReader().wrap(ByteBuffer.wrap(full), 0, XpSetup.FIXED_HEADER + 2);
            XpReader inAuthor = new XpReader().wrap(ByteBuffer.wrap(full), 0, full.length - 1);
            assertAll(
                () -> assertEquals(0, s.readHeader(fixedOnly), "fixed part short"),
                () -> assertEquals(0, s.readHeader(inName), "name unterminated"),
                () -> assertEquals(0, inName.pos(), "position restored"),
                () -> assertEquals(0, s.readHeader(inAuthor), "author unterminated"),
                () -> assertFalse(s.haveHeader(), "no header"));
        }

        @Test
        void consistencyChecksFailLikeC() {
            XpSetup s = new XpSetup();
            assertAll(
                () -> assertEquals(-1, s.readHeader(reader(new CPackets().setupHeader(0, 0, 0, 2, 2, 12, 1, "n", "a")
                    .bytes())), "length 0"),
                () -> assertEquals("bad map specs from server (0,2,2)", s.error().toString(), "C message"),
                () -> assertEquals(-1, s.readHeader(reader(new CPackets().setupHeader(1, 0, 0, 0, 2, 12, 1, "n", "a")
                    .bytes())), "width 0"),
                () -> assertEquals(-1, s.readHeader(reader(new CPackets().setupHeader(1, 0, 0, 2, 0, 12, 1, "n", "a")
                    .bytes())), "height 0"),
                () -> assertEquals(-1, s.readHeader(reader(new CPackets().setupHeader(5, 0, 0, 2, 2, 12, 1, "n", "a")
                    .bytes())), "length above width * height"),
                () -> assertEquals(-1, s.readHeader(reader(new CPackets().setupHeader(4, 0, 0, 2, 2, 12, 2, "n", "a")
                    .bytes())), "YX order unknown"),
                () -> assertEquals("unknown map order type (2)", s.error().toString(), "order message"),
                () -> assertEquals(4, s.readHeader(reader(new CPackets().setupHeader(4, 0, 0, 2, 2, 12, 3, "n", "a")
                    .bytes())) - XpSetup.FIXED_HEADER, "exactly width * height uncompressed is fine"));
        }

        @Test
        void overlongNameIsAnError() {
            String longName = "x".repeat(XpProto.MAX_CHARS);
            XpSetup s = new XpSetup();
            assertAll(
                () -> assertEquals(-1, s.readHeader(reader(new CPackets().setupHeader(1, 0, 0, 2, 2, 12, 1, longName,
                    "a").bytes())), "80 chars without NUL"),
                () -> assertEquals("map name or author too long", s.error().toString(), "message"),
                () -> assertEquals(-1, s.readHeader(reader(new CPackets().setupHeader(1, 0, 0, 2, 2, 12, 1, "n",
                    longName).bytes())), "author too"));
        }

        @Test
        void resetForgetsTheHeader() {
            XpSetup s = new XpSetup();
            s.readHeader(reader(new CPackets().setupHeader(1, 0, 0, 1, 1, 12, 1, "n", "a").bytes()));
            s.reset();
            assertAll(
                () -> assertFalse(s.haveHeader(), "no header"),
                () -> assertEquals(0, s.received(), "no bytes"),
                () -> assertFalse(s.complete(), "not complete"));
        }
    }

    @Nested
    @DisplayName("uncompress (C Uncompress_map)")
    class Uncompress {
        @Test
        void runLengthDecodesIncludingCountsWithTheHighBit() {
            byte[] blocks = new byte[300];
            java.util.Arrays.fill(blocks, 0, 200, (byte) 1);
            blocks[200] = 3;
            java.util.Arrays.fill(blocks, 201, 300, (byte) 0);
            byte[] c = CPackets.compress(blocks);
            assertAll(
                () -> assertEquals((byte) 0x81, c[0], "compressed byte"),
                () -> assertEquals((byte) 200, c[1], "count 200 has the high bit set"),
                () -> assertArrayEquals(blocks, XpMap.uncompress(c, c.length, XpProto.SETUP_MAP_ORDER_XY, 3, 100),
                    "round trip"));
        }

        @Test
        void runsLongerThan255AreSplitAndJoined() {
            byte[] blocks = new byte[600];
            byte[] c = CPackets.compress(blocks);
            assertAll(
                () -> assertEquals(6, c.length, "255 + 255 + 90"),
                () -> assertArrayEquals(blocks, XpMap.uncompress(c, c.length, XpProto.SETUP_MAP_ORDER_XY, 20, 30),
                    "decoded"));
        }

        @Test
        void uncompressedDataIsCopied() {
            byte[] d = {1, 2, 3, 4};
            assertAll(
                () -> assertArrayEquals(d, XpMap.uncompress(d, 4, XpProto.SETUP_MAP_UNCOMPRESSED, 2, 2), "copied"),
                () -> assertThrows(IllegalArgumentException.class,
                    () -> XpMap.uncompress(d, 3, XpProto.SETUP_MAP_UNCOMPRESSED, 2, 2), "wrong length"));
        }

        @Test
        void corruptDataThrows() {
            assertAll(
                () -> assertEquals("map compress count error 1", assertThrows(IllegalArgumentException.class,
                    () -> XpMap.uncompress(new byte[] {(byte) 0x81, 1}, 2, 1, 1, 1)).getMessage(), "count below 2"),
                () -> assertThrows(IllegalArgumentException.class,
                    () -> XpMap.uncompress(new byte[] {(byte) 0x81}, 1, 1, 1, 2), "dangling count"),
                () -> assertThrows(IllegalArgumentException.class,
                    () -> XpMap.uncompress(new byte[] {(byte) 0x81, 5}, 2, 1, 2, 2), "overrun"),
                () -> assertThrows(IllegalArgumentException.class,
                    () -> XpMap.uncompress(new byte[] {1, 1, 1}, 3, 1, 2, 2), "too short"),
                () -> assertArrayEquals(new byte[] {1, 1, 1, 1}, XpMap.uncompress(new byte[] {1, 1, 1, 1}, 4, 1, 2, 2),
                    "exact length of literal bytes is fine"));
        }
    }

    @Nested
    @DisplayName("blockChar / mapText")
    class Chars {
        @Test
        void everySetupCodeInvertsTheMapCharacter() {
            String chars = "x#aqswrfcd%&+-><imkj@()bgthy";
            StringBuilder back = new StringBuilder();
            for (char ch : chars.toCharArray()) {
                back.append(XpMap.blockChar(CPackets.setupCode(ch, false, false), false));
            }
            assertEquals(chars, back.toString(), "map char -> setup code -> map char");
        }

        @Test
        void objectsWithTeamsAndRanges() {
            assertAll(
                () -> assertEquals('3', XpMap.blockChar(XpProto.SETUP_BASE_RIGHT + 3, true), "team base digit"),
                () -> assertEquals('9', XpMap.blockChar(XpProto.SETUP_BASE_HIGHEST, true), "highest base"),
                () -> assertEquals('0', XpMap.blockChar(XpProto.SETUP_BASE_LOWEST, true), "lowest base"),
                () -> assertEquals('_', XpMap.blockChar(XpProto.SETUP_BASE_DOWN + 2, false), "no team play: '_'"),
                () -> assertEquals('*', XpMap.blockChar(XpProto.SETUP_TREASURE + 9, true), "treasure any team"),
                () -> assertEquals('!', XpMap.blockChar(XpProto.SETUP_TARGET + 4, true), "target any team"),
                () -> assertEquals('A', XpMap.blockChar(XpProto.SETUP_CHECK, false), "first checkpoint"),
                () -> assertEquals('Z', XpMap.blockChar(XpProto.SETUP_CHECK + 25, false), "last checkpoint"),
                () -> assertEquals(' ', XpMap.blockChar(XpProto.SETUP_SPACE_DOT, false), "space dot"),
                () -> assertEquals('x', XpMap.blockChar(XpProto.SETUP_FILLED_NO_DRAW, false), "filled no draw"),
                () -> assertEquals('b', XpMap.blockChar(XpProto.SETUP_DECOR_DOT_FILLED, false), "dot decor"),
                () -> assertEquals('g', XpMap.blockChar(XpProto.SETUP_DECOR_DOT_RU, false), "dot decor RU"),
                () -> assertEquals('t', XpMap.blockChar(XpProto.SETUP_DECOR_DOT_RD, false), "dot decor RD"),
                () -> assertEquals('h', XpMap.blockChar(XpProto.SETUP_DECOR_DOT_LU, false), "dot decor LU"),
                () -> assertEquals('y', XpMap.blockChar(XpProto.SETUP_DECOR_DOT_LD, false), "dot decor LD"),
                () -> assertEquals(0, XpMap.blockChar(XpProto.SETUP_CHECK + 26, false), "beyond checkpoints unknown"),
                () -> assertEquals(0, XpMap.blockChar(108, false), "between checkpoints and concentrators unknown"),
                () -> assertEquals(0, XpMap.blockChar(127, false), "unknown code"));
        }

        @Test
        void mapTextIsTopRowFirstAndCountsUnknownBlocks() {
            // 2 wide, 2 high; index x * h + y with y up: (0,0)=fuel, (0,1)=wall, (1,0)=unknown, (1,1)=space
            byte[] blocks = {XpProto.SETUP_FUEL, XpProto.SETUP_FILLED, 127, XpProto.SETUP_SPACE};
            int[] unknown = new int[1];
            String t = XpMap.mapText(blocks, 2, 2, false, unknown);
            assertAll(
                () -> assertEquals("x \n# \n", t, "top row is y = 1"),
                () -> assertEquals(1, unknown[0], "one unknown code drawn as space"));
        }
    }

    @Nested
    @DisplayName("build")
    class Build {
        @Test
        void optionsComeFromTheSetupMode() {
            String[] rows = {"x_x", "   ", "x#x"};
            int mode = XpProto.MODE_WRAP_PLAY | XpProto.MODE_LIMITED_LIVES;
            XpMap.Built b = XpMap.build(setupFor(rows, mode, 16, "Tiny", true));
            Options o = b.options();
            assertAll(
                () -> assertEquals(3, b.map().width(), "width"),
                () -> assertEquals(3, b.map().height(), "height"),
                () -> assertEquals("Tiny", b.map().name(), "name"),
                () -> assertEquals("Tester", b.map().author(), "author"),
                () -> assertTrue(b.map().edgeWrap(), "wrap from WRAP_PLAY"),
                () -> assertFalse(b.map().teamPlay(), "no team play"),
                () -> assertTrue(o.getBool(Opt.LIMITED_LIVES), "limited lives"),
                () -> assertEquals(3, o.getInt(Opt.WORLD_LIVES), "lives"),
                () -> assertEquals(16, o.framesPerSecond(), "fps"),
                () -> assertFalse(o.getBool(Opt.TIMING), "no timing"),
                () -> assertEquals(1, b.map().bases().size(), "the base"),
                () -> assertEquals(1, b.map().fuels().size(), "the fuel station"),
                () -> assertEquals(0, b.unknownBlocks(), "nothing unknown"));
        }

        @Test
        void teamPlayModeKeepsBaseTeams() {
            String[] rows = {"1 2", " A ", "x!x"};
            XpMap.Built b = XpMap.build(setupFor(rows, XpProto.MODE_TEAM_PLAY, 12, "T", false));
            assertAll(
                () -> assertTrue(b.map().teamPlay(), "team play"),
                () -> assertFalse(b.map().timing(), "no timing"),
                () -> assertEquals(List.of(1, 2), b.map().bases().stream().map(x -> x.team()).sorted().toList(),
                    "base teams from the setup codes"),
                () -> assertEquals(0, b.map().checkpoints().size(), "letters are not checkpoints without timing"),
                () -> assertEquals(1, b.map().targets().size(), "target"));
        }

        @Test
        void basesFaceTheWaySetupCodesSay() {
            // C guimap.c draws SETUP_BASE_UP/RIGHT/DOWN/LEFT; the server derived them from gravity and $ attractors
            XpSetup s = setupFor(new String[] {"____", "    "}, 0, 12, "B", false);
            int h = 2;
            s.mapData()[0 * h + 1] = (byte) (XpProto.SETUP_BASE_LEFT + 3);
            s.mapData()[1 * h + 1] = (byte) XpProto.SETUP_BASE_RIGHT;
            s.mapData()[2 * h + 1] = (byte) (XpProto.SETUP_BASE_DOWN + 9);
            s.mapData()[3 * h + 1] = (byte) XpProto.SETUP_BASE_UP;
            WorldMap m = XpMap.build(s).map();
            assertAll(
                () -> assertEquals(List.of(Dir.LEFT, Dir.RIGHT, Dir.DOWN, Dir.UP),
                    m.bases().stream().sorted(java.util.Comparator.comparingInt(b -> b.bx())).map(b -> b.dir()).toList(),
                    "left, right, down, up from codes 63, 40, 59, 30"),
                () -> assertEquals(List.of(0, 1, 2, 3),
                    m.bases().stream().sorted(java.util.Comparator.comparingInt(b -> b.bx())).map(b -> b.bx()).toList(),
                    "positions unchanged"),
                () -> assertEquals(Dir.LEFT, XpMap.baseDir(XpProto.SETUP_BASE_HIGHEST, Dir.UP), "69 is still left"),
                () -> assertEquals(Dir.DOWN, XpMap.baseDir(XpProto.SETUP_BASE_LOWEST - 1, Dir.DOWN), "29 keeps the fallback"),
                () -> assertEquals(Dir.DOWN, XpMap.baseDir(XpProto.SETUP_BASE_HIGHEST + 1, Dir.DOWN), "70 keeps the fallback"),
                () -> assertEquals(Dir.UP, XpMap.baseDir(XpProto.SETUP_BASE_RIGHT - 1, Dir.LEFT), "39 is still up"));
        }

        @Test
        void timingModeKeepsCheckpoints() {
            String[] rows = {"_ _", " A ", " B "};
            XpMap.Built b = XpMap.build(setupFor(rows, XpProto.MODE_TIMING, 12, "R", true));
            assertAll(
                () -> assertTrue(b.map().timing(), "timing"),
                () -> assertTrue(b.options().getBool(Opt.TIMING), "timing option"),
                () -> assertEquals(2, b.map().checkpoints().size(), "checkpoints A and B"));
        }

        @Test
        void undecodableMapThrows() {
            XpSetup s = setupFor(new String[] {"xx", "xx"}, 0, 12, "n", true);
            s.mapData()[1] = 1;
            assertThrows(IllegalArgumentException.class, () -> XpMap.build(s), "count 1 is corrupt");
        }

        @Test
        void cNumbersFollowTheXThenYScanOrder() {
            // cannons at (0,top) (2,top) (0,bottom): C numbers them x-major, y from the bottom.
            String[] rows = {"r r", "   ", "c  "};
            XpMap.Built b = XpMap.build(setupFor(rows, 0, 12, "n", true));
            List<Cannon> cs = b.map().cannons();
            List<String> byC = new ArrayList<>();
            for (int c = 0; c < cs.size(); c++) {
                Cannon k = cs.get(b.cannonIndex()[c]);
                byC.add(k.bx() + "," + k.by());
            }
            assertAll(
                () -> assertEquals(List.of("0,0", "0,2", "2,2"), byC, "C order: x then y ascending"),
                () -> assertEquals(-1, XpMap.Built.lookup(b.cannonIndex(), 3), "out of range high"),
                () -> assertEquals(-1, XpMap.Built.lookup(b.cannonIndex(), -1), "out of range low"));
        }

        @Test
        void theNewbieGlobeDecodesToTheSameMapAsTheFile() throws Exception {
            Options file = new Options();
            ConfigParser.parseFile(Fixtures.MAPS.resolve("default.xp"), OptionOrigin.MAP, file);
            WorldMap ref = WorldMap.fromOptions(file);
            String[] rows = file.mapData().split("\n", -1);
            int w = ref.width();
            int h = ref.height();
            String[] padded = new String[h];
            for (int r = 0; r < h; r++) {
                String row = r < rows.length ? rows[r] : "";
                padded[r] = row.length() >= w ? row.substring(0, w) : row + " ".repeat(w - row.length());
            }
            XpSetup s = setupFor(padded, XpProto.MODE_WRAP_PLAY, 12, ref.name(), true);
            XpMap.Built b = XpMap.build(s);
            WorldMap m = b.map();
            assertAll(
                () -> assertEquals(170, m.width(), "width"),
                () -> assertEquals(170, m.height(), "height"),
                () -> assertTrue(s.mapDataLen < w * h / 4, "C compression applied"),
                () -> assertArrayEquals(ref.blocks(), m.blocks(), "identical block grid"),
                () -> assertEquals(ref.bases().size(), m.bases().size(), "bases"),
                () -> assertEquals(ref.cannons().size(), m.cannons().size(), "cannons"),
                () -> assertEquals(ref.fuels().size(), m.fuels().size(), "fuel stations"),
                () -> assertEquals(ref.targets().size(), m.targets().size(), "targets"),
                () -> assertEquals(sortedXy(m.cannons().stream().map(c -> new int[] {c.bx(), c.by()}).toList()),
                    cOrdered(m.cannons().stream().map(c -> new int[] {c.bx(), c.by()}).toList(), b.cannonIndex()),
                    "cannon table lists cannons in C order"),
                () -> assertEquals(sortedXy(m.bases().stream().map(c -> new int[] {c.bx(), c.by()}).toList()),
                    cOrdered(m.bases().stream().map(c -> new int[] {c.bx(), c.by()}).toList(), b.baseIndex()),
                    "base table lists bases in C order"),
                () -> assertEquals(sortedXy(m.fuels().stream().map(c -> new int[] {c.bx(), c.by()}).toList()),
                    cOrdered(m.fuels().stream().map(c -> new int[] {c.bx(), c.by()}).toList(), b.fuelIndex()),
                    "fuel table lists fuel stations in C order"));
        }

        private List<String> sortedXy(List<int[]> xy) {
            return xy.stream().sorted(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]))
                .map(a -> a[0] + "," + a[1]).toList();
        }

        private List<String> cOrdered(List<int[]> xy, int[] table) {
            List<String> out = new ArrayList<>();
            for (int idx : table) {
                out.add(xy.get(idx)[0] + "," + xy.get(idx)[1]);
            }
            return out;
        }
    }
}
