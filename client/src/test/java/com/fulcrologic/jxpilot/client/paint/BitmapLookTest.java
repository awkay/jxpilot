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
import com.fulcrologic.jxpilot.common.math.ShipShape;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.paint.PaintTests.*;
import static com.fulcrologic.jxpilot.client.paint.ScenePainterTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** {@code texturedObjects} (C {@code blockBitmaps}) in the painters. */
class BitmapLookTest {
    static final float UV = 1e-4f;
    static final float A = ObjectPainter.ATLAS_SIZE;

    static PaintStyle bitmaps() {
        PaintStyle st = new PaintStyle();
        st.texturedObjects = true;
        st.sparkRand = Particles.MAX_SPARK_RAND;
        return st;
    }

    /** True when vertex i samples picture p of b (its u lies inside that picture's columns). */
    static boolean samples(VertexBatch v, int i, BlockBitmap b, int p) {
        float u = v.u(i) * A;
        float t = v.v(i) * A;
        int x = BlockBitmaps.atlasX(b, p);
        int y = BlockBitmaps.atlasY(b, p);
        return u >= x - 0.01f && u <= x + b.width + 0.01f && t >= y - 0.01f && t <= y + b.height + 0.01f;
    }

    @Nested
    class Objects {
        @Test
        void objectPicturesSitWhereCDrawsThem() {
            ObjectPainter o = new ObjectPainter(Palette.xpilotDefault(), bitmaps());
            VertexBatch ball = batch();
            o.bitmapBall(ball, 100, 100);
            VertexBatch mine = batch();
            o.bitmapMine(mine, 100, 100, true);
            o.bitmapMine(mine, 100, 100, false);
            VertexBatch shot = batch();
            o.bullet(shot, 100, 100, false, 1);
            o.bullet(shot, 100, 100, true, 2);
            VertexBatch paused = batch();
            o.bitmapPaused(paused, 100, 100, 0, 0);
            o.bitmapPaused(paused, 100, 100, 5, 1);
            VertexBatch item = batch();
            o.bitmapItemSymbol(item, 7, 100, 100, 1);
            assertAll(
                () -> assertEquals(List.of("90,89 111,89 111,110", "90,89 111,110 90,110"), tris(ball),
                    "the ball: 21 square from (x - 10, y + 10)"),
                () -> assertTrue(samples(ball, 0, BlockBitmap.BALL, 0), "ball picture"),
                () -> assertEquals("90,92 111,92 111,107", tris(mine).get(0), "a mine: 21 x 15 from (x - 10, y + 7)"),
                () -> assertTrue(samples(mine, 0, BlockBitmap.MINE_TEAM, 0), "team mine picture"),
                () -> assertTrue(samples(mine, 6, BlockBitmap.MINE_OTHER, 0), "enemy mine picture"),
                () -> assertEquals("99,93 107,93 107,101", tris(shot).get(0),
                    "a shot of size 3: 8 x 8 pixels from 1 pixel left and up"),
                () -> assertTrue(samples(shot, 0, BlockBitmap.BULLET, 2), "picture shotSize - 1"),
                () -> assertEquals("99.5,96.5 103.5,96.5 103.5,100.5", tris(shot).get(2), "zoom 2: 4 world units"),
                () -> assertTrue(samples(shot, 6, BlockBitmap.BULLET_OWN, 2), "team shots: the own bullet"),
                () -> assertEquals("83,82 118,82 118,117", tris(paused).get(0), "paused: 35 square from (x - 17, y + 17)"),
                () -> assertTrue(samples(paused, 0, BlockBitmap.PAUSED, 1), "shown: picture 1"),
                () -> assertTrue(samples(paused, 6, BlockBitmap.PAUSED, 0), "blinking off: picture 0"),
                () -> assertEquals("92,94 108,94 108,110", tris(item).get(0), "an item: 16 px from (x - 8, y + 10)"),
                () -> assertTrue(samples(item, 0, BlockBitmap.ALL_ITEMS, 7), "the item's picture"));
        }

        @Test
        void aRefuelBeamIsSixteenFramedPictures() {
            ObjectPainter o = new ObjectPainter(Palette.xpilotDefault(), bitmaps());
            VertexBatch b = batch();
            o.bitmapRefuel(b, 0, 0, 160, 0, 3, 1);
            assertAll(
                () -> assertEquals(16 * 6, b.vertexCount(), "16 pictures"),
                () -> assertEquals("-4,-4 4,-4 4,4", tris(b).get(0), "8 px centred on the start"),
                () -> assertEquals("6,-4 14,-4 14,4", tris(b).get(2), "the next a sixteenth further"),
                () -> assertTrue(samples(b, 0, BlockBitmap.REFUEL, ObjectPainter.REFUEL_FRAMES[3]), "frame (3 + 16 - 0) % 16"),
                () -> assertTrue(samples(b, 6, BlockBitmap.REFUEL, ObjectPainter.REFUEL_FRAMES[2]), "frame (3 + 16 - 1) % 16"));
        }

        @Test
        void shipsPickTheirPictureAndTurn() {
            ShipPainter s = new ShipPainter(Palette.xpilotDefault(), bitmaps());
            VertexBatch b = batch();
            s.bitmapShip(b, 100, 100, 0, Palette.BLUE, false, 1);
            s.bitmapShip(b, 100, 100, 0, Palette.WHITE, true, 1);
            s.bitmapShip(b, 100, 100, Math.PI / 2, Palette.WHITE, false, 1);
            VertexBatch hull = batch();
            s.shipWithoutHull(hull, ShipShape.defaultShip(), 100, 100, 0, Palette.WHITE, 0, 0, 1);
            VertexBatch cloaked = batch();
            s.shipWithoutHull(cloaked, ShipShape.defaultShip(), 100, 100, 0, Palette.WHITE, ShipPainter.CLOAKED, 0, 1);
            assertAll(
                () -> assertTrue(samples(b, 0, BlockBitmap.SHIP_FRIEND, 0), "blue: the friend picture"),
                () -> assertTrue(samples(b, 6, BlockBitmap.SHIP_SELF, 0), "the viewer: the own picture"),
                () -> assertTrue(samples(b, 12, BlockBitmap.SHIP_ENEMY, 0), "others: the enemy picture"),
                () -> assertEquals("84,84 116,84 116,116", tris(b).get(0), "32 px centred on the ship"),
                () -> assertEquals("116,84 116,116 84,116", tris(b).get(4), "heading up: a quarter turn"),
                () -> assertEquals(0, hull.segmentCount(), "no hull lines under the picture"),
                () -> assertTrue(cloaked.segmentCount() > 0, "a cloaked ship keeps its dashed hull"),
                () -> assertTrue(ShipPainter.bitmapShown(0), "picture when visible"),
                () -> assertFalse(ShipPainter.bitmapShown(ShipPainter.PHASING), "none when phasing"));
        }
    }

    @Nested
    class Scene {
        @Test
        void theSceneUsesTheBitmapsInsteadOfTheShapes() {
            SceneData s = scene();
            ScenePainter p = new ScenePainter(P, bitmaps());
            VertexBatch b = batch();
            p.draw(b, new TextWriter(Fixtures.font()), cam(10000, 10000, false, 500, 500), s);
            VertexBatch plain = batch();
            PaintStyle st = bitmaps();
            st.texturedObjects = false;
            new ScenePainter(P, st).draw(plain, new TextWriter(Fixtures.font()), cam(10000, 10000, false, 500, 500), s);
            assertAll(
                () -> assertEquals(1, b.pointCount(), "only the particle is a point (shots are pictures)"),
                () -> assertEquals(List.of("boss"), Drawn.texts(b).subList(Drawn.texts(b).size() - 1, Drawn.texts(b).size()),
                    "mine names stay"),
                () -> assertFalse(Drawn.texts(b).contains("P"), "the paused P is in the picture"),
                () -> assertTrue(plain.segmentCount() - b.segmentCount() > 40 + 24 + 4,
                    "no mine outlines, ball circle, paused frame or hulls"),
                () -> assertTrue(samples(b, 0, BlockBitmap.MINE_OTHER, 0), "fills start with the enemy mine picture"),
                () -> assertTrue(samples(b, 6, BlockBitmap.MINE_TEAM, 0), "then the team mine (C draws both)"));
        }
    }

    @Nested
    class Hud {
        @Test
        void meterPartsCropTheirPictures() {
            HudListPainter lp = HudListPainter.xpilot(new TextWriter(Fixtures.font()), Palette.xpilotDefault());
            ScreenSpace ss = new ScreenSpace();
            ss.set(600, 1);
            VertexBatch b = batch();
            HudListPainterTest.List_ list = new HudListPainterTest.List_()
                .rect(10, 20, 50, 11, true, HudListPainter.METER_FULL)
                .rect(60, 20, 150, 11, true, HudListPainter.METER_EMPTY)
                .rect(60, 20, 0, 11, true, HudListPainter.METER_EMPTY);
            lp.paint(b, ss, list);
            int fx = BlockBitmaps.atlasX(BlockBitmap.METER, 1);
            int ex = BlockBitmaps.atlasX(BlockBitmap.METER, 0);
            assertAll(
                () -> assertEquals(List.of("10,569 60,569 60,580", "10,569 60,580 10,580", "60,569 210,569 210,580",
                    "60,569 210,580 60,580"), tris(b), "two quads over their rects; the empty rect draws nothing"),
                () -> assertEquals(fx / A, b.u(0), UV, "full picture from its left end"),
                () -> assertEquals((fx + 50) / A, b.u(1), UV, "50 of 200 columns"),
                () -> assertEquals((ex + 50) / A, b.u(6), UV, "the empty picture's last 150 columns"),
                () -> assertEquals((ex + 200) / A, b.u(7), UV, "to its right end"));
        }

        @Test
        void hudItemSymbolsArePicturesWhenAsked() {
            HudPainter hp = new HudPainter(new TextWriter(Fixtures.font()), Palette.xpilotDefault());
            ScreenSpace ss = new ScreenSpace();
            ss.set(600, 1);
            VertexBatch plain = batch();
            hp.symbol(plain, ss, 3, 10, 20, -1);
            hp.setBitmapItems(true);
            VertexBatch bm = batch();
            hp.symbol(bm, ss, 3, 10, 20, 0x12345678);
            assertAll(
                () -> assertEquals(tris(plain), tris(bm), "same place"),
                () -> assertTrue(samples(bm, 0, BlockBitmap.ALL_ITEMS, 3), "the item's picture"),
                () -> assertEquals(-1, bm.color(0), "untinted"),
                () -> assertFalse(samples(plain, 0, BlockBitmap.ALL_ITEMS, 3), "off: the symbol"));
        }

        @Test
        void theScoreBackgroundIsCloudsThenLogo() {
            ScreenSpace ss = new ScreenSpace();
            ss.set(1000, 1);
            VertexBatch b = batch();
            ScoreBackground.draw(b, ss, 100, 256, 800);
            VertexBatch none = batch();
            ScoreBackground.draw(none, ss, 100, 256, 0);
            float split = 442f / ScoreBackground.TEXTURE_HEIGHT;
            int[] tex = ScoreBackground.texture(new int[256 * 442], filled(256 * 223, 7));
            assertAll(
                () -> assertEquals(List.of("0,458 256,458 256,900", "0,458 256,900 0,900", "0,100 256,100 256,323",
                    "0,100 256,323 0,323"), tris(b), "clouds from the window top, the logo at its bottom"),
                () -> assertEquals(split, b.v(0), UV, "the clouds end at the split"),
                () -> assertEquals(1f, b.v(6), UV, "the logo ends at the texture's bottom"),
                () -> assertEquals(0, none.vertexCount(), "an empty window draws nothing"),
                () -> assertEquals(256 * 665, tex.length, "the texture holds both"),
                () -> assertEquals(7, tex[256 * 442], "the logo after the clouds"),
                () -> assertEquals(0, tex[256 * 442 - 1], "the clouds first"));
        }

        int[] filled(int n, int v) {
            int[] a = new int[n];
            java.util.Arrays.fill(a, v);
            return a;
        }
    }
}
