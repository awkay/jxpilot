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
package com.fulcrologic.jxpilot.client.paint;

import com.fulcrologic.jxpilot.client.Fixtures;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A 600 px high framebuffer at ratio 1 (framebuffer y = 600 - C y; line ends at pixel centres, +0.5)
 * with the fixture font: advance 7 ('1': 5), ascent 10, descent 3. The HUD centre is (400, 300).
 */
class HudPainterTest {
    static final Palette P = Palette.xpilotDefault();
    static final int HUDC = PaintConfig.HUD_COLOR;
    static final int HUD = P.color(HUDC);
    static final String H = Integer.toHexString(HUD);
    static final String RED = Integer.toHexString(P.color(Palette.RED));
    /** Float coordinates computed through trig tables. */
    static final float EPS = 1e-3f;

    HudPainter hp;
    ScreenSpace ss;
    VertexBatch b;

    @BeforeEach
    void setUp() {
        hp = new HudPainter(new TextWriter(Fixtures.font()), P);
        ss = new ScreenSpace();
        ss.set(600, 1);
        b = Drawn.batch();
    }

    static int[] counts() {
        int[] c = new int[Item.COUNT];
        Arrays.fill(c, -1);
        return c;
    }

    // ================================================================== Paint_lock

    @Nested
    class LockText {
        @Test
        void nameAndDistanceInBlocks() {
            hp.paintLockText(b, ss, 400, 300, "Bob", false, 0, 700, HUDC);
            assertEquals(List.of("'Bob'@389,375#" + H, "'020'@472,375#" + H), Drawn.runStrings(b),
                "name at 400 - (21 + 2) / 2 (C name_width is 2 wider), baseline 300 - 72 - descent; %03d of 700 / 35 at x 472");
        }

        @Test
        void livesReplaceTheDistanceWithLimitedLives() {
            hp.paintLockText(b, ss, 400, 300, "Bob", true, 3, 0, HUDC);
            VertexBatch dead = Drawn.batch();
            hp.paintLockText(dead, ss, 400, 300, "Bob", true, 0, 0, HUDC);
            VertexBatch none = Drawn.batch();
            hp.paintLockText(none, ss, 400, 300, "Bob", false, 0, 0, HUDC);
            VertexBatch far = Drawn.batch();
            hp.paintLockText(far, ss, 400, 300, "Bob", false, 0, 35 * 1234, HUDC);
            assertAll(
                () -> assertEquals("'003'@472,375#" + H, Drawn.runStrings(b).get(1), "lives, even at distance 0"),
                () -> assertEquals("'000'@472,375#" + RED, Drawn.runStrings(dead).get(1), "red with no lives left"),
                () -> assertEquals(List.of("Bob"), Drawn.texts(none), "no number at distance 0 without limited lives"),
                () -> assertEquals("123", Drawn.texts(far).get(1), "only the first 3 chars of '1234'"));
        }

        @Test
        void atRatioTwoFontMetricsAreLogical() {
            ss.set(1200, 2);
            hp.paintLockText(b, ss, 400, 300, "Bob", false, 0, 700, HUDC);
            assertEquals(List.of("'Bob'@788,747#" + H, "'020'@944,747#" + H), Drawn.runStrings(b),
                "name 10.5 + 2 logical wide: x 400 - 6; baseline 228 - 1.5 = 226.5 logical");
        }
    }

    @Nested
    class LockShip {
        final float[] xs = new float[70];
        final float[] ys = new float[70];

        @Test
        void theTargetTurnedToHeading96HalvedAndFilled() {
            xs[0] = 15;
            ys[0] = 0;
            xs[1] = -9;
            ys[1] = 8;
            xs[2] = -9;
            ys[2] = -8;
            hp.paintLockShip(b, ss, 400, 300, xs, ys, 3, HUDC);
            VertexBatch two = Drawn.batch();
            hp.paintLockShip(two, ss, 400, 300, xs, ys, 2, HUDC);
            VertexBatch many = Drawn.batch();
            hp.paintLockShip(many, ss, 400, 300, xs, ys, 70, HUDC);
            assertAll(
                () -> assertEquals(List.of("460,380 460,388 464,376#" + H, "460,380 464,376 456,376#" + H,
                        "460,380 456,376 460,388#" + H), Drawn.tris(b),
                    "points (460, 212), (464, 224), (456, 224) (224.5 truncated): nose up, fanned from (460, 220)"),
                () -> assertEquals(List.of(), Drawn.tris(two), "fewer than 3 points: no ship"),
                () -> assertEquals(64, Drawn.tris(many).size(), "at most 64 points"));
        }
    }

    @Nested
    class LockDisc {
        VertexBatch disc(int dist, int dir, int diag, boolean friend, boolean phase) {
            VertexBatch c = Drawn.batch();
            c.setLineWidth(3);
            hp.paintLockDisc(c, ss, 400, 300, dist, dir, diag, friend, phase, HUDC, Palette.RED);
            return c;
        }

        @Test
        void shownBeyondWarningDistanceOrInPhase() {
            assertAll(
                () -> assertFalse(HudPainter.lockDiscShown(0, true), "never at distance 0"),
                () -> assertTrue(HudPainter.lockDiscShown(801, false), "beyond 800 regardless of phase"),
                () -> assertFalse(HudPainter.lockDiscShown(800, false), "exactly 800 blinks: off phase"),
                () -> assertTrue(HudPainter.lockDiscShown(800, true), "exactly 800 blinks: on phase"),
                () -> assertFalse(HudPainter.lockDiscShown(500, false), "within: off phase"),
                () -> assertEquals(0, disc(500, 0, 5000, false, false).vertexCount(), "nothing drawn off phase"),
                () -> assertEquals(0, disc(0, 0, 5000, true, true).segmentCount(), "nothing at distance 0"));
        }

        @Test
        void anEnemyIsAFilledDiscInTheLockColourAtTheLockDirection() {
            VertexBatch c = disc(900, 0, 5000, false, false);
            assertAll(
                () -> assertEquals(16, Drawn.tris(c).size(), "a 16-triangle disc"),
                () -> assertEquals(453.5f, c.x(0), EPS, "size 5000 / 900 = 5 with its box at (int)(400 + 54 - 2.5) = 451"),
                () -> assertEquals(300.5f, c.y(0), EPS, "box top (int)(300 - 2.5) = 297; centre 299.5"),
                () -> assertEquals(456f, c.x(1), EPS, "radius 2.5"),
                () -> assertEquals(300.5f, c.y(1), EPS, "first rim point at 3 o'clock"),
                () -> assertEquals(453.5f, c.x(11), EPS, "the 4th triangle ends at 90 degrees: straight up"),
                () -> assertEquals(303f, c.y(11), EPS, "2.5 above the centre"),
                () -> assertEquals(451.732f, c.x(17), 1e-2f, "the 6th ends at 135 degrees"),
                () -> assertEquals(302.268f, c.y(17), 1e-2f, "up and left"),
                () -> assertEquals(456f, c.x(47), EPS, "the fan closes"),
                () -> assertEquals(P.color(Palette.RED), c.color(0), "in the lock colour"));
        }

        @Test
        void theSizeIsMapDiagOverDistanceFromOneToTen() {
            VertexBatch up = disc(900, 32, 5000, false, false);
            VertexBatch big = disc(900, 0, 100000, false, false);
            VertexBatch small = disc(900, 0, 100, false, false);
            assertAll(
                () -> assertEquals(399.5f, up.x(0), EPS, "lockDir 32 (up): box x (int)(400 - 2.5) = 397"),
                () -> assertEquals(354.5f, up.y(0), EPS, "box y (int)(300 - 54 - 2.5) = 243, centre 245.5"),
                () -> assertEquals(459f, big.x(1), EPS, "capped at 10: radius 5 around 454"),
                () -> assertEquals(300f, big.y(0), EPS, "box y 295, centre 300"),
                () -> assertEquals(453.5f, small.x(0), EPS, "at least 1: box x (int)(454 - 0.5) = 453"),
                () -> assertEquals(454f, small.x(1), EPS, "radius 0.5"));
        }

        @Test
        void aFriendIsAOnePixelOutlineInTheHudColour() {
            VertexBatch c = disc(900, 0, 5000, true, false);
            assertAll(
                () -> assertEquals(16, c.segmentCount(), "a 16-chord circle"),
                () -> assertEquals(456.5f, c.segX1(0), EPS, "centre on the pixel centre 454, radius 2.5"),
                () -> assertEquals(300f, c.segY1(0), EPS, "at y 299.5 + 0.5"),
                () -> assertEquals(HUD, c.segColor(0), "HUD colour"),
                () -> assertEquals(1f, c.segWidth(0), "1 px wide whatever the batch had"),
                () -> assertEquals(List.of(), Drawn.tris(c), "not filled"));
        }

        @Test
        void friendsShareATeamInTeamPlayOrAnAlliance() {
            assertAll(
                () -> assertTrue(HudPainter.isFriend(true, true, 2, 2, ' ', ' '), "same team in team play"),
                () -> assertFalse(HudPainter.isFriend(true, false, 2, 2, ' ', ' '), "same team outside team play"),
                () -> assertFalse(HudPainter.isFriend(true, true, 2, 3, ' ', ' '), "other team"),
                () -> assertTrue(HudPainter.isFriend(true, false, 2, 3, 'A', 'A'), "same alliance"),
                () -> assertFalse(HudPainter.isFriend(true, false, 2, 3, 'A', 'B'), "other alliance"),
                () -> assertFalse(HudPainter.isFriend(true, false, 2, 3, ' ', ' '), "no alliance is not an alliance"),
                () -> assertFalse(HudPainter.isFriend(false, true, 2, 2, 'A', 'A'), "no friends without a self"));
        }

        @Test
        void mapDiagIsTheTruncatedDiagonalInPixels() {
            assertAll(
                () -> assertEquals(175, HudPainter.mapDiag(3, 4), "hypot(105, 140) = 175"),
                () -> assertEquals(49, HudPainter.mapDiag(1, 1), "hypot(35, 35) = 49.5 truncated"));
        }
    }

    // ================================================================== items, time, mods, autopilot

    @Nested
    class Items {
        @Test
        void aSymbolAndItsCountLeftOfTheHud() {
            int[] c = counts();
            c[Item.WIDEANGLE.ordinal()] = 3;
            c[Item.FUEL.ordinal()] = 5;
            c[Item.REARSHOT.ordinal()] = 0;
            hp.paintItems(b, ss, 400, 300, c, -1, false, HUDC);
            assertAll(
                () -> assertEquals(List.of("item 1 311,351-327,367 v16#" + H, "item 2 311,334-327,350 v16#" + H),
                    Drawn.icons(b), "fuel never; 16x16 at (400 - 73 - 16, 300 - 67), the next 17 lower; count 0 is listed"),
                () -> assertEquals(List.of("'3'@301,354#" + H, "'0'@301,337#" + H), Drawn.runStrings(b),
                    "counts 3 px left of the symbols, baseline v + 8 + 10 / 2"),
                () -> assertEquals(0, b.segmentCount(), "no lose frame"),
                () -> assertEquals((784 + 16) / 1024f, b.u(2), 1e-4f, "the symbol's right texel edge is atlasX + 16"));
        }

        @Test
        void aColumnWrapsOnlyWhenTheNextEntryWouldPassTheBottom() {
            com.fulcrologic.jxpilot.client.render.Font tall = new com.fulcrologic.jxpilot.client.render.Font(1000, 100, 60, 6);
            for (char ch = '0'; ch <= '9'; ch++) {
                tall.setGlyph(ch, (ch - 32) * 8, 0, (ch - 32) * 8 + 6, 9, 1, -8, 7);
            }
            HudPainter tallHp = new HudPainter(new TextWriter(tall), P);
            int[] c = counts();
            c[1] = 3;
            c[2] = 4;
            c[3] = 5;
            tallHp.paintItems(b, ss, 400, 300, c, -1, false, HUDC);
            assertEquals(List.of("item 1 311,351-327,367 v16#" + H, "item 2 311,284-327,300 v16#" + H,
                    "item 3 279,351-295,367 v16#" + H), Drawn.icons(b),
                "spacing 60 + 6 + 1 = 67: the 2nd entry ends exactly at 367 and stays; the 3rd starts a column 26 + 6 further left");
        }

        @Test
        void columnsWrapLeftwardsBySevenAtTheFixtureSpacing() {
            int[] c = counts();
            for (int i = 1; i <= 15; i++) {
                c[i] = 5;
            }
            c[3] = 22;
            hp.paintItems(b, ss, 400, 300, c, -1, false, HUDC);
            List<String> icons = Drawn.icons(b);
            List<Drawn.Run> runs = Drawn.runs(b);
            assertAll(
                () -> assertEquals(15, icons.size(), "items 1..15"),
                () -> assertEquals("item 7 311,249-327,265 v16#" + H, icons.get(6), "7th entry 6 * 17 lower (spacing max(16, 13) + 1)"),
                () -> assertEquals("item 8 272,351-288,367 v16#" + H, icons.get(7),
                    "8th starts a column (335 + 17 + 17 > 367) left by the widest entry 14 + 3 + 16 plus 6"),
                () -> assertEquals("item 14 272,249-288,265 v16#" + H, icons.get(13), "7 in the second column too"),
                () -> assertEquals("item 15 240,351-256,367 v16#" + H, icons.get(14),
                    "third column: the widest is measured per column (7 + 19 + 6 = 32)"),
                () -> assertEquals("'22'@294,320#" + H, runs.get(2).toString(), "a wider count is right-aligned too"));
        }

        @Test
        void itemsPastTheCountsArrayOrSymbolRowsAreHandled() {
            int[] c = new int[Item.COUNT];
            Arrays.fill(c, -1);
            c[20] = 1;
            hp.paintItems(b, ss, 400, 300, c, -1, false, HUDC);
            VertexBatch shortArray = Drawn.batch();
            hp.paintItems(shortArray, ss, 400, 300, new int[] {-1, 2}, -1, false, HUDC);
            assertAll(
                () -> assertEquals(List.of("item 20 311,351-327,367 v32#" + H), Drawn.icons(b), "item 20 is on atlas row 1"),
                () -> assertEquals(1, Drawn.icons(shortArray).size(), "a shorter counts array lists what it has"));
        }

        @Test
        void theLoseItemGetsAOnePixelFrameWhileActive() {
            int[] c = counts();
            c[1] = 3;
            b.setLineWidth(3);
            hp.paintItems(b, ss, 400, 300, c, 1, true, HUDC);
            VertexBatch off = Drawn.batch();
            hp.paintItems(off, ss, 400, 300, c, 1, false, HUDC);
            VertexBatch other = Drawn.batch();
            hp.paintItems(other, ss, 400, 300, c, 2, true, HUDC);
            assertAll(
                () -> assertEquals(Drawn.rectSegs(309.5f, 368.5f, 327.5f, 350.5f), Drawn.segs(b), "18x18 outline at (311 - 2, 233 - 2)"),
                () -> assertEquals(1f, b.segWidth(0), "1 px wide"),
                () -> assertEquals(HUD, b.segColor(0), "HUD colour"),
                () -> assertEquals(0, off.segmentCount(), "not while inactive"),
                () -> assertEquals(0, other.segmentCount(), "not around another item"));
        }

        @Test
        void atRatioTwoTheSpacingUsesLogicalFontMetrics() {
            ss.set(1200, 2);
            int[] c = counts();
            c[1] = 3;
            c[2] = 4;
            hp.paintItems(b, ss, 400, 300, c, -1, false, HUDC);
            assertAll(
                () -> assertEquals(List.of("item 1 622,702-654,734 v16#" + H, "item 2 622,668-654,700 v16#" + H),
                    Drawn.icons(b), "symbols 16 logical px, still 17 apart (max(16, 6.5) + 1)"),
                () -> assertEquals("'3'@609,714#" + H, Drawn.runStrings(b).get(0),
                    "count 3.5 logical wide at 327 - 19 - 3.5, baseline 233 + 8 + floor(5 / 2)"));
        }
    }

    @Nested
    class TimeModsAutopilot {
        String time(int t) {
            VertexBatch c = Drawn.batch();
            hp.paintTimeLeft(c, ss, 400, 300, t, HUDC);
            return Drawn.runStrings(c).toString();
        }

        @Test
        void timeLeftIsMinutesAndSecondsLeftOfTheHudTop() {
            assertAll(
                () -> assertEquals("['2:05'@299,376#" + H + "]", time(125),
                    "'  2:05' (42 wide) ends at 400 - 73, baseline 227 - descent"),
                () -> assertEquals("['62:05'@292,376#" + H + "]", time(3725), "' 62:05'"),
                () -> assertEquals("['1:01'@303,376#" + H + "]", time(61), "'  1:01' is 38 wide"),
                () -> assertEquals("[]", time(0), "not at 0"),
                () -> assertEquals("[]", time(-5), "not below 0"));
        }

        @Test
        void modifiersAndAutopilot() {
            hp.paintMods(b, ss, 400, 300, "FX", HUDC);
            hp.paintAutopilot(b, ss, 400, 300, HUDC);
            assertEquals(List.of("'FX'@313,217#" + H, "'Autopilot'@369,389#" + H), Drawn.runStrings(b),
                "mods right-aligned at 327, baseline 373 + ascent; Autopilot centred at baseline 227 - 6 - 10");
        }

        @Test
        void atRatioTwoTheyUseLogicalMetrics() {
            ss.set(1200, 2);
            hp.paintTimeLeft(b, ss, 400, 300, 125, HUDC);
            hp.paintMods(b, ss, 400, 300, "FX", HUDC);
            hp.paintAutopilot(b, ss, 400, 300, HUDC);
            assertEquals(List.of("'2:05'@626,749#" + H, "'FX'@640,444#" + H, "'Autopilot'@770,762#" + H),
                Drawn.runStrings(b), "widths and ascent/descent halved: time at 327 - 21, mods at 320, autopilot at 385");
        }
    }

    // ================================================================== Paint_hudradar

    @Nested
    class HudRadar {
        final int[] rx = new int[4];
        final int[] ry = new int[4];
        final int[] size = new int[4];

        /** Radar 768 x 768 for a 768 x 768 px map: the scale factors are exactly 1. */
        VertexBatch radar(int n, int worldX, int worldY, boolean wrap) {
            VertexBatch c = Drawn.batch();
            c.setLineWidth(3);
            hp.paintHudRadar(c, ss, 800, 600, rx, ry, size, n, 256, 768, 768, worldX, worldY, wrap, HUDC);
            return c;
        }

        /** One blip at radar (0, 0), placed at offset (x, y) from the centre by moving the view. */
        boolean drawn(int x, int y) {
            return radar(1, -x - 400, -y - 300, false).segmentCount() > 0;
        }

        @Test
        void aBlipIsACircleOfThreeTimesItsSize() {
            rx[0] = 200;
            ry[0] = 100;
            size[0] = 2;
            VertexBatch c = radar(1, 0, 0, false);
            size[0] = 0x82;
            VertexBatch friend = radar(1, 0, 0, false);
            size[0] = 0;
            VertexBatch zero = radar(1, 0, 0, false);
            assertAll(
                () -> assertEquals(16, c.segmentCount(), "a 16-chord circle"),
                () -> assertEquals(603.5f, c.segX1(0), EPS, "x = 600 - 400 = 200, box left 597, centre 600.5, radius 3"),
                () -> assertEquals(299.5f, c.segY1(0), EPS, "y = 300 - 300 = 0: box top 297, centre row 300"),
                () -> assertEquals(HUD, c.segColor(0), "HUD colour"),
                () -> assertEquals(1f, c.segWidth(0), "1 px lines"),
                () -> assertEquals(0, friend.segmentCount(), "size bit 0x80 (friend) is skipped"),
                () -> assertEquals(602.5f, zero.segX1(0), EPS, "size 0 draws 3 wide: radius 1.5 around 601"),
                () -> assertEquals(0, radar(0, 0, 0, false).segmentCount(), "n = 0 draws nothing"));
        }

        @Test
        void theScaleIsTheRadarOverTheMapSize() {
            rx[0] = 100;
            ry[0] = 100;
            VertexBatch c = Drawn.batch();
            hp.paintHudRadar(c, ss, 800, 600, rx, ry, size, 1, 128, 1536, 768, 0, 0, false, HUDC);
            assertAll(
                () -> assertEquals(502.5f, c.segX1(0), EPS, "x = 300 - 400 * 768 / 1536 = 100: box left 499, centre 501"),
                () -> assertEquals(449f, c.segY1(0), EPS,
                    "y = 300 - 300 * 384 / 768 = 150 up: box top 300 - 150 - 1 = 149, centre row 150.5"));
        }

        @Test
        void blipsOnTopOfTheShipAreSkipped() {
            assertAll(
                () -> assertFalse(drawn(15, 15), "within 16 on both axes"),
                () -> assertFalse(drawn(-15, -15), "negative side"),
                () -> assertFalse(drawn(15, -15), "mixed"),
                () -> assertFalse(drawn(-15, 15), "mixed"),
                () -> assertTrue(drawn(16, 0), "x at 16"),
                () -> assertTrue(drawn(-16, 0), "x at -16"),
                () -> assertTrue(drawn(0, 16), "y at 16"),
                () -> assertTrue(drawn(0, -16), "y at -16"));
        }

        @Test
        void wrapPlayFoldsFarBlipsAround() {
            float plain = radar(1, -385 - 400, -300, false).segX1(0);
            float wrapped = radar(1, -385 - 400, -300, true).segX1(0);
            float wrappedY = radar(1, -400 - 20, -300 - 385, true).segY1(0);
            assertAll(
                () -> assertEquals(787.5f, plain, EPS, "without wrap: 385 right of centre"),
                () -> assertEquals(19.5f, wrapped, EPS, "with wrap: 385 - 768 = -383"),
                () -> assertEquals(600 - (300 + 383 - 1 + 1.5f + 0.5f), wrappedY, EPS, "y wraps the same way: 385 up is 383 down"));
        }

        @Test
        void wrapHalfMovesOffsetsBeyondHalfTheSpan() {
            assertAll(
                () -> assertEquals(-383, HudPainter.wrapHalf(385, 768), "beyond +384"),
                () -> assertEquals(384, HudPainter.wrapHalf(384, 768), "exactly half stays"),
                () -> assertEquals(383, HudPainter.wrapHalf(-385, 768), "beyond -384"),
                () -> assertEquals(-384, HudPainter.wrapHalf(-384, 768), "exactly minus half stays"),
                () -> assertEquals(0, HudPainter.wrapHalf(0, 768), "0 stays"));
        }
    }

    // ================================================================== Paint_score_objects

    @Nested
    class ScoreObject {
        @Test
        void visibleWhenActiveAndNotAMultipleOfThree() {
            List<Boolean> v = new ArrayList<>();
            for (int count = -1; count <= 4; count++) {
                v.add(HudPainter.scoreObjectVisible(count));
            }
            assertEquals(List.of(false, false, true, true, false, true), v, "counts -1..4: 1, 2 and 4 show");
        }

        @Test
        void centredOnTheBlockWithHalfTheAscentBelow() {
            hp.paintScoreObject(b, ss, "12", 100, 200, 5);
            VertexBatch c = Drawn.batch();
            ss.set(1200, 2);
            hp.paintScoreObject(c, ss, "12", 100, 200, 5);
            String col = Integer.toHexString(P.color(5));
            assertAll(
                () -> assertEquals(List.of("'12'@94,395#" + col), Drawn.runStrings(b), "12 wide: x 100 - 6, baseline 200 + 5"),
                () -> assertEquals(List.of("'12'@194,796#" + col), Drawn.runStrings(c), "ratio 2: x 100 - 3, baseline 200 + 2"));
        }
    }

    // ================================================================== window scaling

    /** C's WINSCALE on the HUD: positions are unscaled window coordinates, scaled; font offsets are not. */
    @Nested
    class WindowScaling {
        @Test
        void lockTextAnchorsScaleButFontOffsetsDoNot() {
            hp.setScale(0.5);
            hp.paintLockText(b, ss, 800, 600, "Bob", false, 0, 700, HUDC);
            assertEquals(List.of("'Bob'@389,339#" + H, "'020'@436,339#" + H), Drawn.runStrings(b),
                "x WINSCALE(800) - 23 / 2 = 389; baseline WINSCALE(528) - 3 = 261; number at WINSCALE(872) = 436");
        }

        @Test
        void theLockShipShowsFromZoomTwoThirdsAndScalesItsPoints() {
            float[] xs = {15, -9, -9};
            float[] ys = {0, 8, -8};
            hp.setScale(2);
            hp.paintLockShip(b, ss, 200, 150, xs, ys, 3, HUDC);
            VertexBatch small = Drawn.batch();
            hp.setScale(0.5);
            hp.paintLockShip(small, ss, 800, 600, xs, ys, 3, HUDC);
            assertAll(
                () -> assertTrue(HudPainter.lockShipShown(1), "zoom 1: shown"),
                () -> assertTrue(HudPainter.lockShipShown(1 / 1.5), "zoom 1/1.5: 10 * 1.5 <= 15, shown"),
                () -> assertFalse(HudPainter.lockShipShown(0.6), "zoom 0.6: hidden"),
                () -> assertEquals(List.of("520,460 520,476 528,452#" + H, "520,460 528,452 512,452#" + H,
                        "520,460 512,452 520,476#" + H), Drawn.tris(b),
                    "zoom 2: WINSCALE of (260, 62), (264, 74), (256, 74), fanned from WINSCALE(260, 70)"),
                () -> assertEquals(List.of(), Drawn.tris(small), "zoom 0.5: too small, not drawn"));
        }

        @Test
        void aFriendsLockCircleIsArcAddScaledAndAnEnemyDiscFillArcScaled() {
            hp.setScale(0.5);
            VertexBatch friend = Drawn.batch();
            hp.paintLockDisc(friend, ss, 800, 600, 900, 0, 5000, true, false, HUDC, Palette.RED);
            VertexBatch enemy = Drawn.batch();
            hp.paintLockDisc(enemy, ss, 800, 600, 900, 0, 5000, false, false, HUDC, Palette.RED);
            assertAll(
                () -> assertEquals(428.5f, friend.segX1(0), EPS,
                    "box (851, 597) size 5: x WINSCALE 426..428, radius 1, centre pixel 427 + 0.5"),
                () -> assertEquals(299.5f, friend.segY1(0), EPS, "y WINSCALE 299..301, centre row 300"),
                () -> assertEquals(427.5f, enemy.x(0), EPS, "disc of WINSCALE(5) = 3 from WINSCALE(851) = 426: centre 427.5"),
                () -> assertEquals(429f, enemy.x(1), EPS, "radius 1.5"),
                () -> assertEquals(299.5f, enemy.y(0), EPS, "from WINSCALE(597) = 299: centre 300.5 down"));
        }

        @Test
        void theItemColumnStartsAtTheScaledCornerThenWorksInPixels() {
            int[] c = counts();
            c[Item.WIDEANGLE.ordinal()] = 3;
            hp.setScale(0.5);
            hp.paintItems(b, ss, 800, 600, c, -1, false, HUDC);
            assertAll(
                () -> assertEquals(List.of("item 1 348,317-364,333 v16#" + H), Drawn.icons(b),
                    "16 px symbol at (WINSCALE(727) - 16, WINSCALE(533)) = (348, 267)"),
                () -> assertEquals(List.of("'3'@338,320#" + H), Drawn.runStrings(b), "count 3 px left, baseline 267 + 8 + 5"));
        }

        @Test
        void timeModsAndAutopilotScaleTheirAnchors() {
            hp.setScale(2);
            hp.paintTimeLeft(b, ss, 200, 150, 125, HUDC);
            hp.paintMods(b, ss, 200, 150, "FX", HUDC);
            hp.paintAutopilot(b, ss, 200, 150, HUDC);
            assertEquals(List.of("'2:05'@226,449#" + H, "'FX'@240,144#" + H, "'Autopilot'@369,462#" + H), Drawn.runStrings(b),
                "time ends at WINSCALE(127) = 254, baseline WINSCALE(77) - 3; mods at 254 - 14, baseline WINSCALE(223) + 10; "
                    + "autopilot at WINSCALE(200) - 31, baseline 154 - 16");
        }

        @Test
        void hudRadarCirclesAreArcAddScaled() {
            int[] rx = {200};
            int[] ry = {100};
            int[] size = {2};
            hp.setScale(0.5);
            hp.paintHudRadar(b, ss, 1600, 1200, rx, ry, size, 1, 256, 768, 768, 0, 0, false, HUDC);
            assertAll(
                () -> assertEquals(302.5f, b.segX1(0), EPS, "box left 597 -> WINSCALE 299..302: radius 1.5, centre 301"),
                () -> assertEquals(149f, b.segY1(0), EPS, "box top 897 -> 449..452: centre row 450.5"));
        }

        @Test
        void scoreObjectsScaleTheBlockCentre() {
            hp.setScale(2);
            hp.paintScoreObject(b, ss, "12", 100, 200, 5);
            assertEquals(List.of("'12'@194,195#" + Integer.toHexString(P.color(5))), Drawn.runStrings(b),
                "x WINSCALE(100) - 12 / 2, baseline WINSCALE(200) + 5");
        }
    }
}
