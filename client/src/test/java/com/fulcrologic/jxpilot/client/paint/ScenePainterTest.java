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
import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.paint.PaintTests.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenePainterTest {
    static final Palette P = Palette.xpilotDefault();
    static final ShipShape SHIP = ShipShape.defaultShip();
    static final TextWriter TEXT = new TextWriter(Fixtures.font());

    /** 400x400 view at zoom 1 centred on (cx, cy) of a world of the given size. */
    static Camera cam(double w, double h, boolean wrap, double cx, double cy) {
        Camera c = new Camera();
        c.setWorld(w, h, wrap);
        c.setViewport(400, 400, 1);
        c.setZoom(1);
        c.setCentre(cx, cy);
        c.update();
        return c;
    }

    /** One of every kind around (500, 500), plus one far-away ship. */
    static SceneData scene() {
        SceneData s = new SceneData(4, 4, 4, 4);
        s.loops = 3; // no marking lights for ids 0 and 1 (phases 3 and 4)
        s.selfId = 0;
        s.addShip(500, 500, 0, SHIP, 0, 0, Palette.WHITE, false, "self");
        s.addShip(560, 500, 0, SHIP, 1, 0, Palette.BLUE, true, "ally");
        s.addShip(5000, 5000, 0, SHIP, 2, 0, Palette.WHITE, true, "far");
        s.addShot(450, 450, Palette.WHITE, false);
        s.addShot(452, 450, Palette.BLUE, true);
        s.addParticle(440, 440, 6);
        s.addMissile(420, 520, 15, 0);
        s.addLaser(420, 540, 50, 0, Palette.RED);
        s.addMine(600, 600, false, "boss");
        s.addMine(620, 600, true, null);
        s.addBall(400, 400);
        s.addItem(380, 600, 7);
        s.addWreck(650, 400, true, 0, 0, 256);
        s.addAsteroid(350, 650, 0, 0, 1);
        s.addEcm(600, 350, 20);
        s.addPaused(350, 350, 0);
        s.addLink(500, 500, 560, 500, SceneData.LINK_REFUEL);
        s.addLink(500, 500, 400, 400, SceneData.LINK_BALL);
        s.addLink(400, 400, 420, 520, SceneData.LINK_TRACTOR);
        return s;
    }

    @Test
    void fourPassesFillsPointsLinesText() {
        VertexBatch b = batch();
        new ScenePainter(P).draw(b, TEXT, cam(10000, 10000, false, 500, 500), scene());
        assertAll(
            () -> assertEquals(List.of(VertexBatch.TRIANGLES, VertexBatch.POINTS, VertexBatch.LINES, VertexBatch.TRIANGLES),
                modes(b), "four commands"),
            () -> assertEquals(1f, b.scaleX(), "identity transform afterwards"));
    }

    @Test
    void fillsAreMineBlockPausedSquareLockedHullAndItemSymbol() {
        VertexBatch b = batch();
        new ScenePainter(P).draw(b, TEXT, cam(10000, 10000, false, 500, 500), scene());
        int fills = b.commandLength(0);
        assertAll(
            () -> assertEquals(6 + 6 + 3 * 3 + 6, fills,
                "enemy mine block, paused square, the visible locked ship's 3 fan triangles, item quad (team mine and far ship skipped)"),
            () -> assertEquals(P.color(Palette.BLUE), b.color(0), "mine block first, blue"),
            () -> assertEquals(P.color(Palette.BLUE), b.color(12), "locked hull in its ship colour (blue)"),
            () -> assertEquals(P.color(Palette.RED), b.color(21), "item symbol red, last"));
    }

    @Test
    void pointsAreParticlesThenPlainShots() {
        VertexBatch b = batch();
        new ScenePainter(P).draw(b, TEXT, cam(10000, 10000, false, 500, 500), scene());
        assertAll(
            () -> assertEquals(2, b.pointCount(), "one particle, one plain shot (the team shot is lines)"),
            () -> assertEquals(P.color(6), b.pointColor(0), "particle first, in its colour"),
            () -> assertEquals(3f, b.pointSize(1), "then the 3-pixel shot"));
    }

    @Test
    void textIsOtherShipNamesMineNamesAndThePausedLetter() {
        VertexBatch b = batch();
        new ScenePainter(P).draw(b, TEXT, cam(10000, 10000, false, 500, 500), scene());
        int text = b.commandLength(3);
        assertEquals(6 * (4 + 4 + 1), text, "'ally' and 'boss' (4 glyphs each) and 'P'; no label for self or the far ship");
    }

    @Test
    void linesIncludeEveryKindInCOrder() {
        VertexBatch b = batch();
        SceneData s = scene();
        new ScenePainter(P).draw(b, TEXT, cam(10000, 10000, false, 500, 500), s);
        // item 3, ball 24, mine outlines 2x20, wreck 12, asteroid 12, team shot 2, missile 1, laser 1,
        // paused frame 4, ecm 24, two ship hulls 3 + 3, refuel dashes, ball connector 1, tractor dashes
        int fixed = 3 + 24 + 40 + 12 + 12 + 2 + 1 + 1 + 4 + 24 + 6 + 1;
        assertAll(
            () -> assertTrue(b.segmentCount() > fixed, "all fixed-count lines plus dashed links"),
            () -> assertEquals(P.color(Palette.BLUE), b.segColor(0), "the item triangle comes first"),
            () -> assertEquals(4f, b.segWidth(3 + 24 + 40 + 12 + 12 + 2), "the missile after the team shot"),
            () -> assertEquals(3f, b.segWidth(3 + 24 + 40 + 12 + 12 + 2 + 1), "then the laser"));
    }

    @Test
    void wrappedViewsDrawObjectsOncePerVisibleCopy() {
        SceneData s = new SceneData(4, 4, 4, 4);
        s.addShot(5, 50, Palette.WHITE, false);
        VertexBatch b = batch();
        // 1000-wide world, view centred on x 0: copies -1 and 0 both show the shot near the seam
        new ScenePainter(P).draw(b, TEXT, cam(1000, 1000, true, 0, 50), s);
        VertexBatch far = batch();
        new ScenePainter(P).draw(far, TEXT, cam(1000, 1000, true, 500, 50), s);
        assertAll(
            () -> assertEquals(1, b.pointCount(), "only copy 0 holds x 5 inside a view of x -200..200"),
            () -> assertEquals(200f + 5, b.pointX(0), "screen x 205"),
            () -> assertEquals(0, far.pointCount(), "nothing when the view is across the world"));
    }

    @Test
    void diagonalIsADeterministicHashBitTakingBothValues() {
        int x = 0;
        for (int i = 0; i < 64; i++) {
            x += ScenePainter.diagonal(i, 5) ? 1 : 0;
        }
        int count = x;
        assertAll(
            () -> assertEquals(ScenePainter.diagonal(3, 9), ScenePainter.diagonal(3, 9), "same inputs, same bit"),
            () -> assertTrue(count > 16 && count < 48, "roughly half of 64 shots are diagonal: " + count),
            () -> assertNotEquals(ScenePainter.diagonal(0, 0), ScenePainter.diagonal(1, 0) && ScenePainter.diagonal(2, 0)
                && ScenePainter.diagonal(3, 0) && ScenePainter.diagonal(4, 0), "varies with the index"));
    }

    /**
     * Golden equivalence: on a 4-copy view (wrapping 1000-world, zoom 2, centred near the origin so
     * (0,0) is visible in a copy) the scene painter's output equals calling the painters directly,
     * pass by pass and copy by copy, with each copy's transform and the culling rule.
     */
    @Test
    void outputEqualsTheIndividualPaintersPassByPassAndCopyByCopy() {
        Camera cam = new Camera();
        cam.setWorld(1000, 1000, true);
        cam.setViewport(400, 400, 1);
        cam.setZoom(2);
        cam.setCentre(20, 20);
        cam.update();
        SceneData s = new SceneData(4, 4, 4, 4);
        s.loops = 0;
        s.selfId = 9;
        s.addShip(30, 40, 0.3, SHIP, 0, ShipPainter.SHIELD, Palette.WHITE, true, "zero");   // left lights at loops 0
        s.addShip(985, 990, 2.0, SHIP, 2, ShipPainter.CLOAKED, Palette.BLUE, false, null);  // right lights; copy -1
        s.addShip(150, 30, 1.0, SHIP, 9, 0, Palette.WHITE, false, "self");                  // just right of the view
        s.addShot(10, 12, Palette.WHITE, false);
        s.addShot(14, 12, Palette.BLUE, true);
        s.addShot(990, 15, Palette.WHITE, false);
        s.addParticle(25, 5, 5);
        s.addParticle(995, 995, 7);
        s.addMissile(40, 60, 15, 10);
        s.addLaser(60, 40, 30, 70, Palette.RED);
        s.addMine(50, 20, false, "mx");
        s.addMine(55, 25, true, null);
        s.addBall(70, 70);
        s.addItem(5, 60, 3);
        s.addWreck(80, 10, false, 1, 20, 300);
        s.addAsteroid(960, 60, 1, 40, 1);
        s.addEcm(30, 980, 16);
        s.addPaused(70, 30, 0);
        s.addLink(30, 40, 70, 70, SceneData.LINK_BALL);
        s.addLink(30, 40, 985, 990, SceneData.LINK_TRACTOR);
        s.addLink(10, 10, 20, 50, SceneData.LINK_REFUEL);
        s.addLink(400, 400, 420, 420, SceneData.LINK_TRANSPORTER); // off-screen everywhere
        VertexBatch got = batch();
        new ScenePainter(P).draw(got, TEXT, cam, s);

        VertexBatch e = batch();
        ShipPainter sp = new ShipPainter(P);
        ObjectPainter op = new ObjectPainter(P);
        float px = 0.5f;
        float m = ScenePainter.PIXEL_MARGIN;
        float sr = ScenePainter.SHIP_RADIUS;
        int n = cam.offsetCount();
        for (int k = 0; k < n; k++) {
            cam.applyTo(e, k);
            if (ScenePainter.visible(cam, 50, 20, 0, k, m)) op.mineFill(e, 50, 20, px);
            if (ScenePainter.visible(cam, 70, 30, ObjectPainter.HALF_PAUSE + 1, k, 0)) op.pausedFill(e, 70, 30);
            if (ScenePainter.visible(cam, 30, 40, sr, k, 0)) sp.lockedFill(e, SHIP, 30, 40, 0.3, Palette.WHITE);
            if (ScenePainter.visible(cam, 5, 60, 0, k, m)) op.itemSymbol(e, 3, 5, 60, px);
        }
        for (int k = 0; k < n; k++) {
            cam.applyTo(e, k);
            if (ScenePainter.visible(cam, 25, 5, 0, k, 2)) Particles.spark(e, P, 25, 5, 5, px);
            if (ScenePainter.visible(cam, 995, 995, 0, k, 2)) Particles.spark(e, P, 995, 995, 7, px);
            if (ScenePainter.visible(cam, 10, 12, 0, k, 3)) op.shot(e, 10, 12, Palette.WHITE);
            if (ScenePainter.visible(cam, 990, 15, 0, k, 3)) op.shot(e, 990, 15, Palette.WHITE);
            if (ScenePainter.visible(cam, 30, 40, sr, k, m)) sp.markingLightSquares(e, SHIP, 30, 40, 0.3, 0, 0, px);
            if (ScenePainter.visible(cam, 985, 990, sr, k, m)) sp.markingLightSquares(e, SHIP, 985, 990, 2.0, 2, 0, px);
            if (ScenePainter.visible(cam, 150, 30, sr, k, m)) sp.markingLightSquares(e, SHIP, 150, 30, 1.0, 9, 0, px);
        }
        for (int k = 0; k < n; k++) {
            cam.applyTo(e, k);
            if (ScenePainter.visible(cam, 5, 60, 0, k, m)) op.itemTriangle(e, 5, 60, px);
            if (ScenePainter.visible(cam, 70, 70, 10, k, 0)) op.ball(e, 70, 70);
            if (ScenePainter.visible(cam, 50, 20, 0, k, m)) op.mineOutline(e, 50, 20, px);
            if (ScenePainter.visible(cam, 55, 25, 0, k, m)) op.mineOutline(e, 55, 25, px);
            if (ScenePainter.visible(cam, 80, 10, 300 / 16f, k, 0)) op.wreck(e, 80, 10, false, 1, 20, 300);
            if (ScenePainter.visible(cam, 960, 60, 15, k, 0)) op.asteroid(e, 960, 60, 1, 40, 1);
            if (ScenePainter.visible(cam, 14, 12, 0, k, 3)) op.teamShot(e, 14, 12, Palette.BLUE, ScenePainter.diagonal(1, 0), px);
            if (ScenePainter.visible(cam, 40, 60, 15, k, 4)) op.missile(e, 40, 60, 15, 10);
            if (ScenePainter.visible(cam, 60, 40, 30, k, 3)) op.laser(e, 60, 40, 30, 70, Palette.RED);
            if (ScenePainter.visible(cam, 70, 30, ObjectPainter.HALF_PAUSE + 1, k, 0)) op.pausedOutline(e, 70, 30);
            if (ScenePainter.visible(cam, 30, 980, 8, k, 0)) op.ecm(e, 30, 980, 16);
            if (ScenePainter.visible(cam, 30, 40, sr, k, 0)) sp.ship(e, SHIP, 30, 40, 0.3, Palette.WHITE, ShipPainter.SHIELD, 0, px);
            if (ScenePainter.visible(cam, 985, 990, sr, k, 0)) sp.ship(e, SHIP, 985, 990, 2.0, Palette.BLUE, ShipPainter.CLOAKED, 0, px);
            if (ScenePainter.visible(cam, 150, 30, sr, k, 0)) sp.ship(e, SHIP, 150, 30, 1.0, Palette.WHITE, 0, 0, px);
            if (cam.isVisible(30, 40, 70, 70, k, 0)) op.ballConnector(e, 30, 40, 70, 70);
            if (cam.isVisible(30, 40, 985, 990, k, 0)) op.tractorBeam(e, 30, 40, 985, 990, 0, px);
            if (cam.isVisible(10, 10, 20, 50, k, 0)) op.dashedLink(e, 10, 10, 20, 50, 0, px);
            if (ScenePainter.visible(cam, 30, 40, sr, k, m)) sp.markingLightCrosses(e, SHIP, 30, 40, 0.3, 0, 0, px);
            if (ScenePainter.visible(cam, 985, 990, sr, k, m)) sp.markingLightCrosses(e, SHIP, 985, 990, 2.0, 2, 0, px);
            if (ScenePainter.visible(cam, 150, 30, sr, k, m)) sp.markingLightCrosses(e, SHIP, 150, 30, 1.0, 9, 0, px);
        }
        e.identity();
        float lm = ScenePainter.LABEL_MARGIN;
        for (int k = 0; k < n; k++) {
            if (ScenePainter.visible(cam, 30, 40, 0, k, lm)) sp.name(e, TEXT, cam.screenX(30, k), cam.screenY(40, k), "zero", 1);
            if (ScenePainter.visible(cam, 50, 20, 0, k, lm)) op.mineName(e, TEXT, cam.screenX(50, k), cam.screenY(20, k), "mx", 1);
            if (ScenePainter.visible(cam, 70, 30, 0, k, m)) op.pausedLetter(e, TEXT, cam.screenX(70, k), cam.screenY(29, k));
        }
        assertAll(
            () -> assertEquals(4, n, "precondition: 4 world copies"),
            () -> assertEquals(dump(e), dump(got), "identical commands and items"),
            () -> assertTrue(got.segmentCount() > 100 && got.pointCount() >= 2, "precondition: a busy scene"));
    }

    @Test
    void shipsReachingInFromJustOutsideTheViewAreDrawnByTheirRadius() {
        SceneData s = new SceneData(4, 4, 4, 4);
        s.loops = 5;
        // view x [300, 700]; the ship centre is 30 world units right of the view, its 15-unit nose inside
        s.addShip(730, 500, Math.PI, SHIP, 1, 0, Palette.WHITE, false, null);
        VertexBatch b = batch();
        new ScenePainter(P).draw(b, TEXT, cam(10000, 10000, false, 500, 500), s);
        assertEquals(3, b.segmentCount(), "drawn: within the ship radius of the view");
    }
}
