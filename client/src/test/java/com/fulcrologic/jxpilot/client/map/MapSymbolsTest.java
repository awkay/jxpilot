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
package com.fulcrologic.jxpilot.client.map;

import com.fulcrologic.jxpilot.client.Fixtures;
import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.map.Dir;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.Fixtures.map;
import static com.fulcrologic.jxpilot.client.Fixtures.mapWith;
import static org.junit.jupiter.api.Assertions.*;

class MapSymbolsTest {
    static final Palette P = Palette.xpilotDefault();
    static final int RED = P.color(Palette.RED);
    static final int BLUE = P.color(Palette.BLUE);
    static final int WHITE = P.color(Palette.WHITE);
    static final int BLACK = P.color(Palette.BLACK);
    /** Trig-derived coordinates are compared with a float tolerance. */
    static final float EPS = 1e-3f;

    /** Configurable map state for tests. */
    static final class State implements MapState {
        double fuel = Const.MAX_STATION_FUEL;
        boolean cannons = true;
        boolean targets = true;
        double damage = Const.TARGET_DAMAGE;
        int team = Const.TEAM_NOT_SET;
        int next;
        long loops;

        @Override public double fuel(int i) { return fuel; }
        @Override public boolean cannonAlive(int i) { return cannons; }
        @Override public boolean targetAlive(int i) { return targets; }
        @Override public double targetDamage(int i) { return damage; }
        @Override public int selfTeam() { return team; }
        @Override public int nextCheckpoint() { return next; }
        @Override public long loops() { return loops; }
    }

    static Camera cam(WorldMap m, double cx, double cy, int vw, int vh) {
        Camera c = new Camera();
        c.setWorld(m.pixelWidth(), m.pixelHeight(), m.edgeWrap());
        c.setViewport(vw, vh, 1);
        c.setZoom(1);
        c.setCentre(cx, cy);
        c.update();
        return c;
    }

    @Nested
    class Fuel {
        @Test
        void barHeightIsCIntegerFractionOfThirtyOnePixels() {
            assertAll(
                () -> assertEquals(31, MapSymbols.fuelHeight(Const.MAX_STATION_FUEL), "full station: 31 px"),
                () -> assertEquals(15, MapSymbols.fuelHeight(250), "half: 15.5 truncates to 15"),
                () -> assertEquals(0, MapSymbols.fuelHeight(0), "empty"));
        }

        @Test
        void letterIsBlackOnlyWhenTheBarReachesTheCentre() {
            assertAll(
                () -> assertTrue(MapSymbols.fuelCoversCentre(260), "16 px bar reaches y 18 >= 17.5"),
                () -> assertFalse(MapSymbols.fuelCoversCentre(250), "15 px bar stops at y 17"));
        }

        @Test
        void barIsARectangleInsideTheBorderAndAbsentWhenEmpty() {
            VertexBatch b = Segs.batch();
            MapSymbols.fuelBar(b, 35, 0, 250, RED);
            VertexBatch empty = Segs.batch();
            MapSymbols.fuelBar(empty, 35, 0, 0, RED);
            assertAll(
                () -> assertEquals(List.of("37,2 68,2 68,17", "37,2 68,17 37,17"), Segs.triangles(b),
                    "x [x+2, x+33], y [y+2, y+2+15]"),
                () -> assertEquals(List.of(RED), Segs.colors(b), "red"),
                () -> assertEquals(0, empty.vertexCount(), "nothing for an empty station"));
        }
    }

    @Test
    void cannonTrianglesSitOnTheirMountingSide() {
        List<String> up = lines(b -> MapSymbols.cannon(b, Dir.UP, 0, 0, WHITE));
        List<String> down = lines(b -> MapSymbols.cannon(b, Dir.DOWN, 0, 0, WHITE));
        List<String> right = lines(b -> MapSymbols.cannon(b, Dir.RIGHT, 0, 0, WHITE));
        List<String> left = lines(b -> MapSymbols.cannon(b, Dir.LEFT, 0, 0, WHITE));
        assertAll(
            () -> assertEquals(List.of("0,0-35,0", "35,0-17,11", "17,11-0,0"), up, "r: base on the floor, apex at 1/3"),
            () -> assertEquals(List.of("0,35-35,35", "35,35-17,23", "17,23-0,35"), down, "c: base on the ceiling"),
            () -> assertEquals(List.of("0,0-0,35", "0,35-11,17", "11,17-0,0"), right, "f: base on the left wall"),
            () -> assertEquals(List.of("35,0-35,35", "35,35-23,17", "23,17-35,0"), left, "d: base on the right wall"));
    }

    @Test
    void targetIsTwoSquaresPlusADamageCrossThatShrinksWithStrength() {
        List<String> whole = lines(b -> MapSymbols.target(b, 0, 0, Const.TARGET_DAMAGE, BLUE, RED));
        VertexBatch hurt = Segs.batch();
        MapSymbols.target(hurt, 0, 0, 125, BLUE, RED);
        assertAll(
            () -> assertEquals(List.of("0,0-35,0", "35,0-35,35", "35,35-0,35", "0,35-0,0",
                "9,9-26,9", "26,9-26,26", "26,26-9,26", "9,26-9,9"), whole, "outline and inner square, no cross"),
            () -> assertEquals(List.of("8,8-27,27", "8,27-27,8"), Segs.lines(hurt, RED),
                "half strength: cross inset by 125*35/500 = 8"),
            () -> assertEquals(8, Segs.lines(hurt, BLUE).size(), "squares in the target colour"));
    }

    @Test
    void treasureIsAnOpenBoxUnderAHalfCircle() {
        List<String> l = lines(b -> MapSymbols.treasure(b, 0, 0, RED));
        assertAll(
            () -> assertEquals(List.of("0,0-0,17", "35,0-35,17", "0,0-35,0"), l.subList(0, 3), "left, right and floor"),
            () -> assertEquals(3 + MapSymbols.CIRCLE_SEGMENTS / 2, l.size(), "plus the half circle chords"),
            () -> assertTrue(l.get(3).startsWith("35,17.5-"), "arc starts at the right"),
            () -> assertTrue(l.get(l.size() - 1).endsWith("-0,17.5"), "and ends at the left"));
    }

    @Test
    void wormholeCirclesFollowTheAnimationOffsets() {
        VertexBatch b0 = Segs.batch();
        MapSymbols.wormhole(b0, 0, 0, 0, RED);
        VertexBatch b2 = Segs.batch();
        MapSymbols.wormhole(b2, 0, 0, 2, RED);
        int per = 2 * MapSymbols.CIRCLE_SEGMENTS;
        assertAll(
            () -> assertEquals(3 * per, b0.vertexCount(), "three circles"),
            () -> assertEquals(33f, b0.x(0), EPS, "outer circle: centre 16.5 + r 16.5"),
            () -> assertEquals(18.5f, b0.y(0), EPS, "outer circle centre y = 35 - 16.5"),
            () -> assertEquals(33f, b0.x(per), EPS, "step 0 middle circle: centre 21.5 + r 11.5"),
            () -> assertEquals(13.5f, b0.y(per), EPS, "step 0 middle circle centre y = 35 - 10 - 11.5"),
            () -> assertEquals(33f, b0.x(2 * per), EPS, "inner circle: centre 26.5 + r 6.5"),
            () -> assertEquals(8.5f, b0.y(2 * per), EPS, "inner circle centre y = 35 - 20 - 6.5"),
            () -> assertEquals(23f, b2.x(per), EPS, "step 2 (offset 0,10): middle centre 11.5 + 11.5"),
            () -> assertEquals(13.5f, b2.y(per), EPS, "same y as step 0"));
    }

    @Test
    void concentratorsOrbitAndSpinWithTheFrameCounter() {
        MapSymbols s = new MapSymbols(map(false, "."), P);
        VertexBatch tri0 = Segs.batch();
        s.concentrator(tri0, 0, 0, 0, MapSymbols.CONC_TRIS, 3, RED);
        VertexBatch sq0 = Segs.batch();
        s.concentrator(sq0, 0, 0, 0, MapSymbols.CONC_SQRS, 4, RED);
        VertexBatch tri1 = Segs.batch();
        s.concentrator(tri1, 0, 0, 1, MapSymbols.CONC_TRIS, 3, RED);
        double a = 2 * Math.PI / Const.RES;
        assertAll(
            () -> assertEquals(18, tri0.vertexCount(), "three triangles"),
            () -> assertEquals(27f, tri0.x(0), EPS, "loops 0: first triangle centre (17+3, 17), corner at +7"),
            () -> assertEquals(17f, tri0.y(0), EPS, "first corner at the centre height"),
            () -> assertEquals((float) (20 + 7 * Math.cos(42 * a)), tri0.x(1), EPS, "second corner RES/3 = 42 further"),
            () -> assertEquals((float) (17 - 7 * Math.sin(42 * a)), tri0.y(1), EPS, "turning clockwise on screen (y = -sin)"),
            () -> assertEquals(24, sq0.vertexCount(), "three squares"),
            () -> assertEquals(28f, sq0.x(0), EPS, "first square corner at +8"),
            () -> assertEquals(20f, sq0.x(2), EPS, "second corner a quarter turn on: above/below the centre"),
            () -> assertEquals(9f, sq0.y(2), EPS, "C's screen-up quarter turn is world-down: 17 - 8"),
            () -> assertEquals((float) (17 + 5 * Math.cos(3 * a) + 11), tri0.x(6), EPS,
                "second triangle orbits at its direction offset 3, radius 5, size 11"),
            () -> assertEquals((float) (17 - 5 * Math.sin(3 * a)), tri0.y(6), EPS, "orbit direction +3, not -3"),
            () -> assertEquals((float) (17 + 3 * Math.cos(5 * a) + 7 * Math.cos(3 * a)), tri1.x(0), EPS,
                "loops 1: orbit by 5, spin by 3"),
            () -> assertEquals((float) (17 - 3 * Math.sin(5 * a) - 7 * Math.sin(3 * a)), tri1.y(0), EPS,
                "loops 1 y uses -sin"));
    }

    @Test
    void checkpointIsADiamond() {
        assertEquals(List.of("17,0-0,17", "0,17-17,35", "17,35-35,17", "35,17-17,0"),
            lines(b -> MapSymbols.checkpoint(b, 0, 0, BLUE)), "points at the block side midpoints");
    }

    @Test
    void ownershipNeedsAMatchingSetTeam() {
        State st = new State();
        st.team = 2;
        State none = new State();
        assertAll(
            () -> assertTrue(MapSymbols.own(2, st), "same team"),
            () -> assertFalse(MapSymbols.own(1, st), "other team"),
            () -> assertFalse(MapSymbols.own(Const.TEAM_NOT_SET, none), "no team is never own"));
    }

    @Nested
    class Draw {
        static final TextWriter TEXT = new TextWriter(Fixtures.font());

        /** Fuel, cannon (up), target, treasure, wormhole, item and asteroid concentrators, wormhole (C drops a lone one). */
        static WorldMap row(boolean team) {
            return mapWith(false, team ? "teamPlay: yes\n" : "", "#r!*@%&@", "1.......");
        }

        @Test
        void drawsFillsThenLinesThenText() {
            WorldMap m = row(false);
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, cam(m, 140, 35, 400, 200), new State());
            assertAll(
                () -> assertEquals(3, b.commandCount(), "three commands"),
                () -> assertEquals(VertexBatch.TRIANGLES, b.commandMode(0), "fills first"),
                () -> assertEquals(6, b.commandVertices(0), "just the fuel bar"),
                () -> assertEquals(VertexBatch.LINES, b.commandMode(1), "then lines"),
                () -> assertEquals(VertexBatch.TRIANGLES, b.commandMode(2), "then text quads"),
                () -> assertEquals(6, b.commandVertices(2), "just the 'F'"),
                () -> assertEquals(BLACK, b.color(b.commandFirst(2)), "F is black on a full station"),
                () -> assertEquals(1f, b.scaleX(), "text leaves the batch in identity transform"));
        }

        @Test
        void lineCountCoversEveryLineSymbol() {
            WorldMap m = row(false);
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, cam(m, 140, 35, 400, 200), new State());
            int expected = 2 * (3 + 8 + 3 + MapSymbols.CIRCLE_SEGMENTS / 2 + 2 * 3 * MapSymbols.CIRCLE_SEGMENTS + 9 + 12);
            assertEquals(expected, b.commandVertices(1), "cannon, target, treasure, two wormholes, both concentrators");
        }

        /** Objects from block x = 2 on, so block-to-pixel scaling is observable. */
        static WorldMap spread(String bottom) {
            return mapWith(false, bottom.startsWith("1") ? "" : "teamPlay: yes\n", "..#r!*@%&@", bottom);
        }

        @Test
        void symbolsAreDrawnAtTheirBlocksAtAnyZoom() {
            WorldMap m = spread("1.........");
            Camera c = cam(m, 175, 35, 800, 200);
            c.setZoom(2);
            c.update();
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, c, new State());
            List<String> l = worldLines(b, c);
            int wormStart = 3 + 8 + 3 + MapSymbols.CIRCLE_SEGMENTS / 2;
            int concStart = wormStart + 2 * 3 * MapSymbols.CIRCLE_SEGMENTS;
            assertAll(
                () -> assertEquals("72,37 103,37 103,68", worldTriangles(b, c).get(0), "fuel bar in block (2,1)"),
                () -> assertEquals("105,35-140,35", l.get(0), "cannon base in block (3,1)"),
                () -> assertEquals("140,35-175,35", l.get(3), "target outline in block (4,1)"),
                () -> assertEquals("175,35-175,52", l.get(11), "treasure wall in block (5,1)"),
                () -> assertTrue(l.get(wormStart).startsWith("243,53.5-"), "wormhole outer circle in block (6,1)"),
                () -> assertTrue(l.get(concStart).startsWith("272,52-"), "item concentrator in block (7,1)"),
                () -> assertTrue(l.get(concStart + 9).startsWith("308,52-"), "asteroid concentrator in block (8,1)"),
                () -> assertEquals(222f, b.x(b.commandFirst(2)), "F centred on screen x 224 (pen 221 + 1)"),
                () -> assertEquals(137f, b.y(b.commandFirst(2) + 2), "F baseline 134 - 5, top + 8"),
                () -> assertEquals(1f, b.scaleX(), "text pass resets the transform to identity"));
        }

        @Test
        void wormholeAnimationFollowsTheFrameCounterModEight() {
            WorldMap m = spread("1.........");
            Camera c = cam(m, 175, 35, 800, 200);
            State st = new State();
            st.loops = 10; // & 7 = 2
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, c, st);
            List<String> l = worldLines(b, c);
            int middle = 3 + 8 + 3 + MapSymbols.CIRCLE_SEGMENTS / 2 + MapSymbols.CIRCLE_SEGMENTS;
            assertTrue(l.get(middle).startsWith("233,48.5-"), "step 2: middle circle centre (221.5, 48.5), r 11.5");
        }

        @Test
        void teamDigitsArePlacedAndNumbered() {
            WorldMap m = spread("2.........");
            Camera c = cam(m, 175, 35, 800, 200);
            c.setZoom(2);
            c.update();
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, c, new State());
            int t = b.commandFirst(b.commandCount() - 1);
            float u2 = ('2' - 32) * 8f / 1000;
            assertAll(
                () -> assertEquals(24, b.commandVertices(b.commandCount() - 1), "F, base, target, treasure"),
                () -> assertEquals(82f, b.x(t + 6), "base digit under base (0,0): screen 34 - 3 + 1"),
                () -> assertEquals(24f, b.y(t + 8), "base digit baseline 30 - 4 - 10, top + 8"),
                () -> assertEquals(u2, b.u(t + 6), 1f / 65535, "base digit is the team number 2"),
                () -> assertEquals(362f, b.x(t + 12), "target digit centred on screen x 364"),
                () -> assertEquals(137f, b.y(t + 14), "target digit baseline half an ascent below the centre"),
                () -> assertEquals(u2, b.u(t + 12), 1f / 65535, "target digit is its team 2"),
                () -> assertEquals(RED, b.color(t + 12), "enemy target digit is red"),
                () -> assertEquals(432f, b.x(t + 18), "treasure digit centred on screen x 434"),
                () -> assertEquals(148f, b.y(t + 20), "treasure digit baseline at y + 2 * BALL_RADIUS = screen 140"),
                () -> assertEquals(RED, b.color(t + 18), "enemy treasure digit is red"));
        }

        @Test
        void teamZeroGetsDigitsToo() {
            WorldMap m = spread("0.........");
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, cam(m, 175, 35, 800, 200), new State());
            assertEquals(24, b.commandVertices(b.commandCount() - 1), "team 0 base, target and treasure digits are drawn");
        }

        @Test
        void objectsReachingIntoTheMarginAreDrawn() {
            // view [450, 550] at zoom 1, margin 48: cannon block 11 spans [385, 420], right edge inside the margin
            WorldMap m = mapWith(false, "", repeat('.', 11) + "r" + repeat('.', 8), "1" + repeat('.', 19));
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, cam(m, 500, 35, 100, 100), new State());
            assertEquals(6, b.vertexCount(), "drawn");
        }

        @Test
        void farObjectsAreCulledVertically() {
            String[] rows = new String[22];
            rows[0] = "r";
            for (int i = 1; i < 21; i++) {
                rows[i] = ".";
            }
            rows[21] = "1";
            WorldMap m = mapWith(false, "", rows);
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, cam(m, 17, 17, 100, 100), new State());
            assertEquals(0, b.vertexCount(), "cannon at block y 21 is far above a view around y = 17");
        }

        @Test
        void farObjectsAreCulledByTheirBlockPosition() {
            WorldMap m = mapWith(false, "", "....................r", "1....................");
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, cam(m, 17, 35, 100, 100), new State());
            assertEquals(0, b.vertexCount(), "cannon at block 20 is far outside a view around x = 17");
        }

        @Test
        void deadCannonsAndTargetsAndOutWormholesAreNotDrawn() {
            WorldMap m = mapWith(false, "", "r!)", "1..");
            State st = new State();
            st.cannons = false;
            st.targets = false;
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, cam(m, 52.5, 35, 400, 200), st);
            State alive = new State();
            VertexBatch a = Segs.batch();
            new MapSymbols(m, P).draw(a, TEXT, cam(m, 52.5, 35, 400, 200), alive);
            assertAll(
                () -> assertEquals(0, b.vertexCount(), "dead cannon, dead target and an out-wormhole draw nothing"),
                () -> assertEquals(2 * (3 + 8), a.vertexCount(), "alive: cannon and target, still no out-wormhole"));
        }

        @Test
        void offscreenSymbolsAreCulled() {
            WorldMap m = mapWith(false, "", "r......................", "1......................");
            VertexBatch b = Segs.batch();
            // view 100 px wide centred at x=700: cannon block [0,35] is far off-screen
            new MapSymbols(m, P).draw(b, TEXT, cam(m, 700, 35, 100, 100), new State());
            VertexBatch near = Segs.batch();
            new MapSymbols(m, P).draw(near, TEXT, cam(m, 100, 35, 100, 100), new State());
            assertAll(
                () -> assertEquals(0, b.vertexCount(), "nothing drawn far away"),
                () -> assertEquals(6, near.vertexCount(), "drawn when within the cull margin"));
        }

        @Test
        void wrappedCopiesDrawSymbolsOncePerVisibleCopy() {
            WorldMap m = mapWith(true, "", "r...", "1...");
            Camera c = cam(m, 0, 35, 140, 60); // spans the x seam only: copies -1 and 0
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, c, new State());
            assertAll(
                () -> assertEquals(2, c.offsetCount(), "two copies"),
                () -> assertEquals(12, b.vertexCount(), "the cannon in both copies"),
                () -> assertEquals(c.screenX(0, 0), b.x(0), EPS, "first in copy -1"),
                () -> assertEquals(c.screenX(0, 1), b.x(6), EPS, "then in copy 0"));
        }

        @Test
        void teamDigitsOnlyInTeamPlay() {
            WorldMap solo = row(false);
            WorldMap team = row(true);
            VertexBatch s = Segs.batch();
            new MapSymbols(solo, P).draw(s, TEXT, cam(solo, 140, 35, 400, 200), new State());
            VertexBatch t = Segs.batch();
            State st = new State();
            st.team = 1;
            new MapSymbols(team, P).draw(t, TEXT, cam(team, 140, 35, 400, 200), st);
            int last = t.commandCount() - 1;
            assertAll(
                () -> assertEquals(6, s.commandVertices(s.commandCount() - 1), "no team: only the F"),
                () -> assertEquals(4 * 6, t.commandVertices(last), "team: F, base, target and treasure digits"),
                () -> assertEquals(8 + 3 + MapSymbols.CIRCLE_SEGMENTS / 2, Segs.lines(t, BLUE).size(),
                    "own target and treasure are blue"),
                () -> assertEquals(2 * 3 * MapSymbols.CIRCLE_SEGMENTS + 9 + 12, Segs.lines(t, RED).size(),
                    "only wormholes and concentrators stay red"),
                () -> assertEquals(BLUE, t.color(t.commandFirst(last) + 18), "the own treasure digit (4th text glyph) is blue"));
        }

        @Test
        void nextCheckpointIsFilledOthersOutlined() {
            WorldMap m = mapWith(false, "timing: yes\n", "AB", "1.");
            State st = new State();
            st.next = 1;
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, cam(m, 35, 35, 200, 200), st);
            assertAll(
                () -> assertEquals(2, m.checkpoints().size(), "race map has two checkpoints"),
                () -> assertEquals(List.of("52,35 70,52 52,70", "52,35 52,70 35,52"),
                    worldTriangles(b, cam(m, 35, 35, 200, 200)), "B (next) is a filled diamond"),
                () -> assertEquals(List.of("17,35-0,52", "0,52-17,70", "17,70-35,52", "35,52-17,35"),
                    worldLines(b, cam(m, 35, 35, 200, 200)), "A is outlined"));
        }

        @Test
        void firstCheckpointCanBeNext() {
            WorldMap m = mapWith(false, "timing: yes\n", "AB", "1.");
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, cam(m, 35, 35, 200, 200), new State());
            assertEquals(2, Segs.triangles(b).size(), "checkpoint 0 is filled when next");
        }

        @Test
        void nextCheckpointPastTheListFillsNothing() {
            WorldMap m = mapWith(false, "timing: yes\n", ".AB", "1..");
            State st = new State();
            st.next = 2;
            VertexBatch b = Segs.batch();
            new MapSymbols(m, P).draw(b, TEXT, cam(m, 35, 35, 200, 200), st);
            assertAll(
                () -> assertEquals(List.of(), Segs.triangles(b), "no filled diamond"),
                () -> assertEquals(8, Segs.lines(b).size(), "both checkpoints outlined"),
                () -> assertEquals("52,35-35,52", worldLines(b, cam(m, 35, 35, 200, 200)).get(0), "A outlined in block (1,1)"));
        }
    }

    @Nested
    class Text {
        static final TextWriter TEXT = new TextWriter(Fixtures.font());

        @Test
        void centredCharIsCentredWithBaselineHalfAnAscentBelow() {
            VertexBatch b = Segs.batch();
            MapSymbols.centredChar(b, TEXT, 'F', 100, 50, WHITE);
            assertAll(
                () -> assertEquals(98f, b.x(0), "left = 100 - 7/2 (int 3) + xoff 1"),
                () -> assertEquals(53f, b.y(2), "top = baseline (50 - 5) + 8"));
        }

        @Test
        void baseDigitSitsBesideTheBaseOppositeItsLine() {
            WorldMap m = map(false, "...", "...", "...");
            Camera c = cam(m, 52, 52, 104, 104); // world == screen
            float[] up = digit(c, Dir.UP);
            float[] down = digit(c, Dir.DOWN);
            float[] left = digit(c, Dir.LEFT);
            float[] right = digit(c, Dir.RIGHT);
            assertAll(
                () -> assertEquals(50f, up[0], "up: centred under the block: pen 52 - 3, glyph at pen + xoff 1"),
                () -> assertEquals(29f, up[1], "up: baseline 35 - 4 - 10, top = baseline + 8"),
                () -> assertEquals(50f, down[0], "down: centred like up"),
                () -> assertEquals(85f, down[1], "down: baseline 70 + 4 + descent 3 = 77, top = 77 + 8"),
                () -> assertEquals(75f, left[0], "left-facing: right of the block 70 + 4 + xoff 1"),
                () -> assertEquals(55f, left[1], "left-facing: baseline 52 - ascent/2 = 47, top = 47 + 8"),
                () -> assertEquals(25f, right[0], "right-facing: left of the block: pen 35 - 4 - 7, glyph at + 1"),
                () -> assertEquals(55f, right[1], "right-facing: same baseline as left-facing"));
        }

        @Test
        void baseDigitBorderScalesWithThePixelRatio() {
            WorldMap m = map(false, "...", "...", "...");
            Camera c = new Camera();
            c.setWorld(m.pixelWidth(), m.pixelHeight(), false);
            c.setViewport(104, 104, 2);
            c.setZoom(0.5); // scale 1: world == screen
            c.setCentre(52, 52);
            c.update();
            assertEquals(25f, digit(c, Dir.UP)[1], "border 4 * 2: baseline 35 - 8 - 10, top + 8");
        }

        float[] digit(Camera c, int dir) {
            VertexBatch b = Segs.batch();
            MapSymbols.baseDigit(b, TEXT, c, 0, new Base(0, 1, 1, dir, 3, 3), WHITE);
            return new float[] {b.x(0), b.y(2)};
        }
    }

    static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    static List<String> lines(java.util.function.Consumer<VertexBatch> f) {
        VertexBatch b = Segs.batch();
        f.accept(b);
        return Segs.lines(b);
    }

    /** Lines converted back to world coordinates of copy 0. */
    static List<String> worldLines(VertexBatch b, Camera c) {
        return Segs.lines(b).stream().map(s -> {
            String[] pts = s.split("-(?=[0-9])");
            return w(pts[0], c) + "-" + w(pts[1], c);
        }).toList();
    }

    /** Triangles converted back to world coordinates of copy 0. */
    static List<String> worldTriangles(VertexBatch b, Camera c) {
        return Segs.triangles(b).stream().map(s -> {
            String[] pts = s.split(" ");
            return w(pts[0], c) + " " + w(pts[1], c) + " " + w(pts[2], c);
        }).toList();
    }

    static String w(String p, Camera c) {
        String[] xy = p.split(",");
        return Segs.p((float) c.worldX(Float.parseFloat(xy[0])), (float) c.worldY(Float.parseFloat(xy[1])));
    }
}
