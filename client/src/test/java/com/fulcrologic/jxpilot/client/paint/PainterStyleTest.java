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

import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.client.paint.PaintTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** The painters' use of the live {@link PaintStyle} outside the scene painter. */
class PainterStyleTest {
    static final Palette P = Palette.xpilotDefault();
    static final float UV = 1e-4f;

    @Test
    void fastShotColoursAreBlueOrWhite() {
        assertAll(
            () -> assertEquals(Palette.BLUE, ObjectPainter.fastShotColor(Palette.BLUE), "blue stays blue"),
            () -> assertEquals(Palette.WHITE, ObjectPainter.fastShotColor(Palette.RED), "red is white"),
            () -> assertEquals(Palette.WHITE, ObjectPainter.fastShotColor(7), "anything else is white"));
    }

    @Test
    void theShotSquareSizeIsLive() {
        PaintStyle st = new PaintStyle();
        ObjectPainter o = new ObjectPainter(P, st);
        VertexBatch b = batch();
        o.shot(b, 100, 100, Palette.WHITE, 1);
        st.shotSize = 5;
        o.shot(b, 100, 100, Palette.WHITE, 1);
        assertAll(
            () -> assertEquals(3f, b.pointSize(0), "default 3"),
            () -> assertEquals(5f, b.pointSize(1), "changed to 5 without a new painter"),
            () -> assertEquals(100f, b.pointX(1), UV, "size 5 from -2: corner 98, centre 98 + 2 = 100"));
    }

    @Test
    void theTexturedBallSamplesItsAtlasCellUnscaled() {
        ObjectPainter o = new ObjectPainter(P);
        VertexBatch one = batch();
        o.texturedBall(one, 100, 100, 1);
        VertexBatch big = batch();
        o.texturedBall(big, 100, 100, 2);
        int last = 3 * 24 - 1;
        assertAll(
            () -> assertEquals(24 * 3, one.vertexCount(), "24 fan triangles"),
            () -> assertEquals((768 + 10f) / ObjectPainter.ATLAS_SIZE, one.u(0), UV, "centre: 10 texels right of the corner"),
            () -> assertEquals((32 + 10f) / ObjectPainter.ATLAS_SIZE, one.v(0), UV, "and 10 below"),
            () -> assertEquals((768 + 20f) / ObjectPainter.ATLAS_SIZE, one.u(1), UV, "the rim at angle 0: 20 texels right"),
            () -> assertEquals(110f, one.x(1), UV, "at x + radius"),
            () -> assertEquals((32 + 0f) / ObjectPainter.ATLAS_SIZE, one.v(17), UV, "the top of the disc (90 degrees) is texel row 0"),
            () -> assertEquals(110f, one.x(last), UV, "the fan closes at angle 360"),
            () -> assertEquals((768 + 20f) / ObjectPainter.ATLAS_SIZE, big.u(0), UV, "zoom 2: the centre is 20 texels in (unscaled tile)"),
            () -> assertEquals((768 + 40f) / ObjectPainter.ATLAS_SIZE, big.u(1), UV, "and the rim 40 texels in"),
            () -> assertEquals(P.color(Palette.WHITE), one.color(0), "untinted"));
    }

    @Test
    void fourColourDisplaysUseWhiteRadarFriendsAndBlueRightLights() {
        PaintStyle st = new PaintStyle();
        st.maxColors = 4;
        RadarPainter r = new RadarPainter(P, st);
        r.begin(0, 0, 256, 128, 128, 2560, 1280, false);
        VertexBatch blip = batch();
        r.blip(blip, 1280, 640, 3, true);
        ShipPainter s = new ShipPainter(P, st);
        VertexBatch lights = batch();
        s.markingLightSquares(lights, ShipShape.defaultShip(), 0, 0, 0, 0, 2, 1);
        assertAll(
            () -> assertEquals(P.color(Palette.WHITE), blip.color(0), "friend blip white with 4 colours"),
            () -> assertEquals(P.color(Palette.BLUE), lights.pointColor(0), "right light blue with 4 colours"));
    }
}
