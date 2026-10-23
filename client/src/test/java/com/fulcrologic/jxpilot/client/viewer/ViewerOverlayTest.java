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
package com.fulcrologic.jxpilot.client.viewer;

import com.fulcrologic.jxpilot.client.Fixtures;
import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.FrameStats;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class ViewerOverlayTest {
    static final Palette P = Palette.xpilotDefault();
    static final TextWriter TEXT = new TextWriter(Fixtures.font());
    static final ViewerOverlay.RadarTexture RADAR = new ViewerOverlay.RadarTexture(256, 128, 0.5f, 0f, 0.75f, 0.125f);
    /** UVs are 16-bit normalized. */
    static final float UV_EPS = 1f / 65535;

    static Camera cam() {
        Camera c = new Camera();
        c.setWorld(1000, 1000, false);
        c.setViewport(800, 600, 2);
        c.setZoom(1);
        c.setCentre(250, 750);
        c.update();
        return c;
    }

    static VertexBatch draw(boolean radar) {
        VertexBatch b = Fixtures.batch(8192, 32, x -> { });
        b.setTransform(5, 5, 5, 5);
        new ViewerOverlay(P).draw(b, TEXT, cam(), new FrameStats(), "map", RADAR, radar);
        return b;
    }

    @Test
    void radarQuadIsTopLeftScaledByThePixelRatio() {
        VertexBatch b = draw(true);
        assertAll(
            () -> assertEquals(1f, b.scaleX(), "overlay draws with the identity transform"),
            () -> assertEquals(16f, b.x(0), "margin 8 logical px = 16 framebuffer px"),
            () -> assertEquals(328f, b.y(0), "bottom = 600 - 16 - 128 * 2"),
            () -> assertEquals(528f, b.x(2), "right = 16 + 256 * 2"),
            () -> assertEquals(584f, b.y(2), "top = 600 - 16"),
            () -> assertEquals(0.5f, b.u(0), UV_EPS, "u0 at the left"),
            () -> assertEquals(0.125f, b.v(0), UV_EPS, "bottom samples the radar's last row (v1)"),
            () -> assertEquals(0f, b.v(2), UV_EPS, "top samples the first row (v0)"),
            () -> assertEquals(P.color(Palette.WHITE), b.color(0), "untinted"),
            () -> assertEquals(VertexBatch.LINES, b.commandMode(1), "then the frame"),
            () -> assertEquals(P.color(Palette.HUD_COLOR), b.segColor(0), "frame in hudColor"),
            () -> assertEquals(4, b.segmentCount(), "the frame is four segments"),
            () -> assertEquals(16f, b.segX1(0), "frame bottom-left x"),
            () -> assertEquals(328f, b.segY1(0), "frame bottom-left y"),
            () -> assertEquals(528f, b.segX2(1), "frame right edge"),
            () -> assertEquals(584f, b.segY2(1), "frame top"));
    }

    @Test
    void markerShowsTheCameraCentreOnTheRadar() {
        VertexBatch b = draw(true);
        assertAll(
            () -> assertEquals(140f, b.x(6), "x = 16 + 0.25 * 512 - 4"),
            () -> assertEquals(516f, b.y(6), "y = 328 + 0.75 * 256 - 4"),
            () -> assertEquals(148f, b.x(8), "marker is 2 logical px each side"),
            () -> assertEquals(524f, b.y(8), "marker top"),
            () -> assertEquals(P.color(Palette.WHITE), b.color(6), "white marker"));
    }

    @Test
    void statsLineIsRightAlignedAtTheTop() {
        VertexBatch b = draw(true);
        // "FPS 0.0 0.00 ms  max 0.00 ms" = 28 chars of advance 7 = 196 px; right edge 784
        assertAll(
            () -> assertEquals(589f, b.x(12), "first glyph at 784 - 196 + xoff 1"),
            () -> assertEquals(582f, b.y(14), "baseline 600 - 16 - ascent 10, glyph top 8 above"),
            () -> assertEquals(P.color(Palette.HUD_COLOR), b.color(12), "hud colour"));
    }

    @Test
    void mapNameCameraLineAndHelpArePlacedBelowAndAtTheBottom() {
        VertexBatch b = draw(true);
        // radar quad and marker (12 triangle vertices), then 21 visible stats glyphs (spaces draw nothing)
        int name = 12 + 21 * 6;
        int camera = name + 3 * 6;
        // "zoom 1.00  at 250, 750": 22 chars, the '1' is 5 wide: 152 px; 17 visible glyphs
        int help = camera + 17 * 6;
        assertAll(
            () -> assertEquals(764f, b.x(name), "map name right-aligned: 784 - 21 + 1"),
            () -> assertEquals(569f, b.y(name + 2), "one line (13) below the stats: baseline 561"),
            () -> assertEquals(P.color(Palette.WHITE), b.color(name), "name in white"),
            () -> assertEquals(633f, b.x(camera), "camera line right-aligned: 784 - 152 + 1"),
            () -> assertEquals(556f, b.y(camera + 2), "two lines below: baseline 548"),
            () -> assertEquals(17f, b.x(help), "help line at the left margin"),
            () -> assertEquals(27f, b.y(help + 2), "baseline at margin + descent = 19"));
    }

    @Test
    void withoutRadarTheTextComesFirst() {
        VertexBatch b = draw(false);
        assertAll(
            () -> assertEquals(589f, b.x(0), "stats text is the first quad"),
            () -> assertEquals(1, b.commandCount(), "text only: one triangle command"));
    }

    @Test
    void radarFractionIsClamped() {
        assertAll(
            () -> assertEquals(0.0, ViewerOverlay.wrap01(-0.5), "below 0"),
            () -> assertEquals(1.0, ViewerOverlay.wrap01(1.5), "above 1"),
            () -> assertEquals(0.25, ViewerOverlay.wrap01(0.25), "inside"));
    }

    @Test
    void radarRectIsWhereTheRadarQuadIsDrawn() {
        float[] r = new float[4];
        ViewerOverlay.radarRect(cam(), RADAR, r);
        assertAll(
            () -> assertEquals(16f, r[0], "left = margin 8 * ratio 2"),
            () -> assertEquals(328f, r[1], "bottom = 600 - 16 - 128 * 2"),
            () -> assertEquals(528f, r[2], "right = 16 + 256 * 2"),
            () -> assertEquals(584f, r[3], "top = 600 - 16"));
    }
}
