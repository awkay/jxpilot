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

    /** A painter that draws every particle (sparkProb 1), for tests that are not about the sparkle. */
    static ScenePainter painter() {
        ScenePainter p = new ScenePainter(P);
        p.setSparkProb(1);
        return p;
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a tracer is one line in its colour at its alpha, under the shots; one wholly out"
        + " of view is not drawn; one crossing the view with both ends outside it is")
    void tracers() {
        SceneData s = new SceneData(1, 4, 1, 1);
        s.addTracer(480, 500, 520, 500, Palette.RED, 0.5f);
        s.addTracer(5000, 5000, 5040, 5000, Palette.RED, 1f);
        s.addTracer(100, 520, 900, 520, Palette.BLUE, 1f); // the view is x 300..700
        s.addShot(520, 500, Palette.WHITE, false);
        VertexBatch b = batch();
        painter().draw(b, TEXT, cam(10000, 10000, false, 500, 500), s);
        int red = P.color(Palette.RED);
        int expected = com.fulcrologic.jxpilot.client.render.Palette.withAlpha(red, Math.round((red >>> 24) * 0.5f));
        int blue = P.color(Palette.BLUE);
        int crossing = 0;
        int tracerSegs = 0;
        int other = 0;
        for (int i = 0; i < b.segmentCount(); i++) {
            if (b.segColor(i) == blue) {
                crossing++;
            } else if (b.segColor(i) == expected) {
                tracerSegs++;
            } else if ((b.segColor(i) & 0x00FFFFFF) == (red & 0x00FFFFFF)) {
                other++;
            }
        }
        int segs = tracerSegs;
        int full = other;
        int across = crossing;
        List<Integer> modes = modes(b);
        assertAll(
            () -> assertEquals(1, segs, "one line at half the colour's alpha"),
            () -> assertEquals(0, full, "the tracer out of view is not drawn"),
            () -> assertEquals(1, across, "the one crossing the view (ends at x 100 and 900) is drawn"),
            () -> assertEquals(VertexBatch.LINES, (int) modes.get(0), "lines (the tracer) before"),
            () -> assertEquals(VertexBatch.POINTS, (int) modes.get(1), "the shot's point"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("showShotTracers off: no tracer is drawn, the shots still are")
    void tracersOff() {
        SceneData s = new SceneData(1, 4, 1, 1);
        s.addTracer(480, 500, 520, 500, Palette.RED, 1f);
        s.addShot(520, 500, Palette.WHITE, false);
        PaintStyle st = new PaintStyle();
        st.shotTracers = false;
        ScenePainter off = new ScenePainter(P, st);
        off.setSparkProb(1);
        VertexBatch b = batch();
        off.draw(b, TEXT, cam(10000, 10000, false, 500, 500), s);
        int red = P.color(Palette.RED) & 0x00FFFFFF;
        int lines = 0;
        for (int i = 0; i < b.segmentCount(); i++) {
            lines += (b.segColor(i) & 0x00FFFFFF) == red ? 1 : 0;
        }
        int fLines = lines;
        assertAll(
            () -> assertEquals(0, fLines, "no tracer line"),
            () -> assertTrue(modes(b).contains(VertexBatch.POINTS), "the shot is drawn"));
    }

    @Test
    void commandsFollowCsDeferredFlushes() {
        VertexBatch b = batch();
        painter().draw(b, TEXT, cam(10000, 10000, false, 500, 500), scene());
        VertexBatch lit = batch();
        SceneData s = scene();
        s.loops = 0; // ship 0's left lights flash
        painter().draw(lit, TEXT, cam(10000, 10000, false, 500, 500), s);
        int t = VertexBatch.TRIANGLES;
        int l = VertexBatch.LINES;
        int p = VertexBatch.POINTS;
        assertAll(
            () -> assertEquals(List.of(t, l, p, l, t, l), modes(b),
                "object fills, object lines, sparks and shots, team shots, ship fills and text, ship lines and arcs"),
            () -> assertEquals(List.of(t, l, p, l, t, l, p, l), modes(lit),
                "with marking lights: their squares and crosses come after the ships, the arcs after them"),
            () -> assertEquals(1f, b.scaleX(), "identity transform afterwards"));
    }

    @Test
    void objectFillsAreMineBlocksAndItemSymbolsShipFillsArePausedAndLockedHulls() {
        VertexBatch b = batch();
        painter().draw(b, TEXT, cam(10000, 10000, false, 500, 500), scene());
        int ship = b.commandFirst(4);
        assertAll(
            () -> assertEquals(6 + 6, b.commandLength(0), "enemy mine block and item quad (team mine skipped)"),
            () -> assertEquals(P.color(Palette.BLUE), b.color(0), "mine block first, blue"),
            () -> assertEquals(P.color(Palette.RED), b.color(6), "then the red item symbol"),
            () -> assertEquals(P.color(Palette.BLUE), b.color(ship), "ship fills start with the blue paused square"),
            () -> assertEquals(P.color(Palette.BLUE), b.color(ship + 6), "then the visible locked ship's hull in its colour (blue)"),
            () -> assertEquals(6 + 3 * 3 + 6 * (1 + 4 + 4), b.commandLength(4),
                "paused square, 3 fan triangles (far ship culled), text: 'P', 'ally', 'boss'"));
    }

    @Test
    void pointsAreParticlesThenPlainShots() {
        VertexBatch b = batch();
        painter().draw(b, TEXT, cam(10000, 10000, false, 500, 500), scene());
        assertAll(
            () -> assertEquals(2, b.pointCount(), "one particle, one plain shot (the team shot is lines)"),
            () -> assertEquals(P.color(6), b.pointColor(0), "particle first, in its colour"),
            () -> assertEquals(3f, b.pointSize(1), "then the 3-pixel shot"));
    }

    @Test
    void textIsThePausedLetterOtherShipNamesThenMineNames() {
        VertexBatch b = batch();
        painter().draw(b, TEXT, cam(10000, 10000, false, 500, 500), scene());
        assertEquals(List.of("P", "ally", "boss"), Drawn.texts(b), "no label for self or the far ship");
    }

    @Test
    void linesIncludeEveryKindInCOrder() {
        VertexBatch b = batch();
        painter().draw(b, TEXT, cam(10000, 10000, false, 500, 500), scene());
        // screen = world - 300. Step 1: item 3, mine outlines 2x20, wreck 12, asteroid 12, missile 1, laser 1;
        // step 2: team shot 2; step 3: paused frame 4, two ship hulls, links; arcs: ball 24, ECM 24
        int missile = 3 + 40 + 12 + 12;
        int team = missile + 2;
        int paused = team + 2;
        int n = b.segmentCount();
        List<String> arms = ScenePainter.diagonal(1, 3)
            ? List.of("151,151-153,149", "153,151-151,149")
            : List.of("151,150-153,150", "152,151-152,149");
        assertAll(
            () -> assertEquals(P.color(Palette.BLUE), b.segColor(0), "the item triangle comes first"),
            () -> assertEquals(4f, b.segWidth(missile), "the missile after the asteroid"),
            () -> assertEquals(3f, b.segWidth(missile + 1), "then the laser"),
            () -> assertEquals(arms, segs(b).subList(team, team + 2), "then the team shot's arms (queued after the direct draws)"),
            () -> assertEquals("34,34-66,34", segs(b).get(paused), "then the paused frame (step 3)"),
            () -> assertEquals("110,100", segs(b).get(n - 48).split("-")[0], "the ball circle (an arc) second to last"),
            () -> assertEquals("310,50", segs(b).get(n - 24).split("-")[0], "the ECM circle last"));
    }

    @Test
    void everyEntryPointLeavesTheIdentityTransform() {
        Camera c = cam(10000, 10000, false, 500, 500);
        c.setZoom(2);
        c.update();
        VertexBatch body = batch();
        painter().drawBody(body, TEXT, c, scene());
        VertexBatch arcs = batch();
        painter().drawArcs(arcs, c, scene());
        SceneData hurt = scene();
        hurt.damaged = 1;
        hurt.loops = 2;
        VertexBatch flash = batch();
        flash.setTransform(2, 2, 5, 5);
        painter().damagedFlash(flash, c, hurt);
        assertAll(
            () -> assertEquals(1f, body.scaleX(), "drawBody"),
            () -> assertEquals(1f, arcs.scaleX(), "drawArcs"),
            () -> assertEquals(Drawn.rectTris(0, 0, 400, 400, P.color(Palette.BLUE)), Drawn.tris(flash),
                "the flash is in framebuffer pixels whatever transform the batch had"));
    }

    @Test
    void aReusedSceneDrawsOnlyThisFramesEntries() {
        SceneData s = scene();
        s.clear(); // last frame's ships, mines and links stay in the arrays past the counts
        s.addShip(500, 500, 0, SHIP, 0, 0, Palette.WHITE, false, null);
        VertexBatch b = batch();
        painter().draw(b, TEXT, cam(10000, 10000, false, 500, 500), s);
        assertAll(
            () -> assertEquals(List.of(), Drawn.texts(b), "no stale names, mine names or paused letters"),
            () -> assertEquals(List.of(), Drawn.tris(b), "no stale locked fills or paused squares"),
            () -> assertEquals(3, b.segmentCount(), "just this frame's hull: no stale links or shapes"));
    }

    @Test
    void lockedCloakedOrPhasingShipsGetNoFill() {
        SceneData s = new SceneData(4, 4, 4, 4);
        s.loops = 3;
        s.addShip(500, 500, 0, SHIP, 1, ShipPainter.CLOAKED, Palette.WHITE, true, null);
        s.addShip(520, 500, 0, SHIP, 2, ShipPainter.PHASING, Palette.WHITE, true, null);
        s.addShip(540, 500, 0, SHIP, 3, ShipPainter.SHIELD, Palette.WHITE, true, null);
        VertexBatch b = batch();
        painter().draw(b, TEXT, cam(10000, 10000, false, 500, 500), s);
        assertAll(
            () -> assertEquals(9, b.vertexCount(), "only the shielded locked ship's 3 fan triangles"),
            () -> assertEquals(240f, b.x(0), "fanned from that ship's centre (screen x 240)"));
    }

    @Test
    void roundDelayShowsTheSecondsInPlaceOfEveryShipKeepingTheNames() {
        SceneData s = new SceneData(4, 4, 4, 4);
        s.loops = 0; // ship 0's lights would flash
        s.selfId = 0;
        s.fps = 14;
        s.roundDelay = 3 * 14 + 2; // 2 % 14 < 7: the digit half of the second
        s.addShip(500, 500, 0, SHIP, 0, ShipPainter.SHIELD, Palette.RED, true, "self");
        s.addShip(560, 500, 0, SHIP, 1, 0, Palette.BLUE, true, "ally");
        VertexBatch b = batch();
        painter().draw(b, TEXT, cam(10000, 10000, false, 500, 500), s);
        VertexBatch hull = batch();
        s.roundDelay = 3 * 14 + 7; // second half of the second: ships again
        painter().draw(hull, TEXT, cam(10000, 10000, false, 500, 500), s);
        assertAll(
            () -> assertEquals(List.of("3", "ally", "3"), Drawn.texts(b),
                "per ship its name (none for self) and its seconds left (44 / 14 = 3)"),
            () -> assertEquals(0, b.segmentCount(), "no hulls, shields or light crosses"),
            () -> assertEquals(0, b.pointCount(), "no light squares"),
            () -> assertEquals(List.of(), Drawn.tris(b), "no locked fill"),
            () -> assertEquals(List.of("ally"), Drawn.texts(hull), "second half of the second: no digits"),
            () -> assertTrue(hull.segmentCount() > 6, "and the hulls and shields are back"));
    }

    @Test
    void damagedFlashFillsTheViewBlueOnEvenFramesAndBlackOnOdd() {
        Camera c = cam(10000, 10000, false, 500, 500);
        SceneData s = new SceneData(4, 4, 4, 4);
        VertexBatch none = batch();
        boolean undamaged = painter().damagedFlash(none, c, s);
        s.damaged = 5;
        s.loops = 8;
        VertexBatch blue = batch();
        boolean even = painter().damagedFlash(blue, c, s);
        s.loops = 9;
        VertexBatch black = batch();
        boolean odd = painter().damagedFlash(black, c, s);
        assertAll(
            () -> assertFalse(undamaged, "not damaged: the caller draws normally"),
            () -> assertEquals(0, none.commandCount(), "and nothing is drawn"),
            () -> assertTrue(even, "damaged: the caller draws nothing else"),
            () -> assertEquals(Drawn.rectTris(0, 0, 400, 400, P.color(Palette.BLUE)), Drawn.tris(blue),
                "even frame: the whole 400x400 view blue"),
            () -> assertTrue(odd, "damaged on odd frames too"),
            () -> assertEquals(0, black.commandCount(), "odd frame: nothing over the black clear"));
    }

    @Test
    void particlesSparkleByTheSparkProbability() {
        SceneData s = new SceneData(4, 4, 64, 4);
        s.loops = 11;
        for (int i = 0; i < 64; i++) {
            s.addParticle(500, 500, 6);
        }
        ScenePainter half = new ScenePainter(P);
        VertexBatch b = batch();
        half.draw(b, TEXT, cam(10000, 10000, false, 500, 500), s);
        int expected = 0;
        for (int i = 0; i < 64; i++) {
            expected += Particles.sparkShown(i, 11, 64) ? 1 : 0;
        }
        int want = expected;
        ScenePainter never = new ScenePainter(P);
        never.setSparkProb(0);
        VertexBatch none = batch();
        never.draw(none, TEXT, cam(10000, 10000, false, 500, 500), s);
        VertexBatch all = batch();
        painter().draw(all, TEXT, cam(10000, 10000, false, 500, 500), s);
        assertAll(
            () -> assertEquals(want, b.pointCount(), "default 0.5: exactly the particles whose hash is below 64"),
            () -> assertTrue(want > 16 && want < 48, "precondition: some but not all: " + want),
            () -> assertEquals(0, none.pointCount(), "sparkProb 0: none"),
            () -> assertEquals(64, all.pointCount(), "sparkProb 1: all"));
    }

    @Test
    void connectorsAndTractorBeamsDashFromOffsetZeroWhileRefuelCrawls() {
        SceneData s = new SceneData(4, 4, 4, 8);
        s.addLink(500, 500, 530, 500, SceneData.LINK_CONNECTOR);
        s.addLink(500, 510, 530, 510, SceneData.LINK_TRACTOR);
        s.addLink(500, 520, 530, 520, SceneData.LINK_REFUEL);
        s.addLink(500, 530, 530, 530, SceneData.LINK_TRANSPORTER);
        s.loops = 4;
        VertexBatch b = batch();
        painter().draw(b, TEXT, cam(10000, 10000, false, 500, 500), s);
        assertEquals(List.of(
                "200,200-208,200", "212,200-220,200", "224,200-230,200",
                "200,210-203,210", "212,210-215,210", "224,210-227,210",
                "204,220-212,220", "216,220-224,220", "228,220-230,220",
                "200,230-208,230", "212,230-220,230", "224,230-230,230"), segs(b),
            "connector 8/4 and tractor 3/9 from offset 0; refuel at the ship offset (loops 4: 8); the transporter "
                + "after a drawn connector from offset 0 (C XSetDashes reset)");
    }

    @Test
    void transportersCrawlUnlessADrawnConnectorResetTheDashOffset() {
        Camera c = cam(10000, 10000, false, 500, 500);
        SceneData none = new SceneData(4, 4, 4, 8);
        none.addLink(500, 530, 530, 530, SceneData.LINK_TRANSPORTER);
        none.loops = 4;
        SceneData offScreen = new SceneData(4, 4, 4, 8);
        offScreen.addLink(500, 500, 900, 500, SceneData.LINK_TRACTOR); // far end off screen: C draws nothing
        offScreen.addLink(500, 530, 530, 530, SceneData.LINK_TRANSPORTER);
        offScreen.loops = 4;
        SceneData refuel = new SceneData(4, 4, 4, 8);
        refuel.addLink(500, 520, 530, 520, SceneData.LINK_REFUEL);
        refuel.addLink(500, 530, 530, 530, SceneData.LINK_TRANSPORTER);
        refuel.loops = 4;
        VertexBatch b1 = batch();
        painter().draw(b1, TEXT, c, none);
        VertexBatch b2 = batch();
        painter().draw(b2, TEXT, c, offScreen);
        VertexBatch b3 = batch();
        painter().draw(b3, TEXT, c, refuel);
        List<String> crawl = List.of("204,230-212,230", "216,230-224,230", "228,230-230,230");
        assertAll(
            () -> assertEquals(crawl, segs(b1), "no connector: the transporter dashes at the ship offset"),
            () -> assertEquals(crawl, segs(b2), "an undrawn connector (an end off screen) resets nothing"),
            () -> assertEquals(crawl, segs(b3).subList(3, 6), "a refuel line resets nothing"),
            () -> assertFalse(ScenePainter.connectorDrawn(c, none), "no connector drawn"),
            () -> assertFalse(ScenePainter.connectorDrawn(c, offScreen), "the off-screen beam is not drawn"));
    }

    @Test
    void linesAreDrawnOnlyWithBothEndsOnScreen() {
        Camera c = cam(10000, 10000, false, 500, 500); // view x, y 300..700
        assertAll(
            () -> assertTrue(ScenePainter.linkShown(c, 400, 400, 600, 600, 0), "both ends inside"),
            () -> assertTrue(ScenePainter.linkShown(c, 300, 300, 700, 700, 0), "ends on the edges count"),
            () -> assertFalse(ScenePainter.linkShown(c, 400, 400, 900, 400, 0), "second end off screen"),
            () -> assertFalse(ScenePainter.linkShown(c, 100, 500, 600, 500, 0), "first end off screen"),
            () -> assertFalse(ScenePainter.linkShown(c, 100, 500, 900, 500, 0), "crossing the view, ends outside"));
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
        SceneData s = new SceneData(4, 4, 4, 8);
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
        s.addLink(55, 25, 70, 70, SceneData.LINK_CONNECTOR);
        s.addShot(995, 15, Palette.BLUE, true);   // a team shot in copy (-1, 0), not the last copy
        s.addPaused(990, 985, 0);                 // a paused marker in copy (-1, -1)
        VertexBatch got = batch();
        new ScenePainter(P).draw(got, TEXT, cam, s);

        VertexBatch e = batch();
        ShipPainter sp = new ShipPainter(P);
        ObjectPainter op = new ObjectPainter(P);
        double z = 2;
        int rand = Particles.sparkRand(PaintConfig.SPARK_PROB);
        float m = ScenePainter.PIXEL_MARGIN;
        float sr = ScenePainter.SHIP_RADIUS;
        float mr = ScenePainter.MINE_RADIUS;
        float tr = ScenePainter.SHOT_RADIUS;
        int n = cam.offsetCount();
        for (int k = 0; k < n; k++) { // step 1 fills
            cam.applyTo(e, k);
            if (ScenePainter.visible(cam, 50, 20, mr, k, 2)) op.mineFill(e, 50, 20, z);
            if (ScenePainter.visible(cam, 5, 60, 0, k, m)) op.itemSymbol(e, 3, 5, 60, z);
        }
        for (int k = 0; k < n; k++) { // step 1 lines
            cam.applyTo(e, k);
            if (ScenePainter.visible(cam, 5, 60, 0, k, m)) op.itemTriangle(e, 5, 60, z);
            if (ScenePainter.visible(cam, 50, 20, mr, k, 2)) op.mineOutline(e, 50, 20, z);
            if (ScenePainter.visible(cam, 55, 25, mr, k, 2)) op.mineOutline(e, 55, 25, z);
            if (ScenePainter.visible(cam, 80, 10, 300 / 16f, k, 0)) op.wreck(e, 80, 10, false, 1, 20, 300);
            if (ScenePainter.visible(cam, 960, 60, 15, k, 0)) op.asteroid(e, 960, 60, 1, 40, 1);
            if (ScenePainter.visible(cam, 40, 60, 15, k, 4)) op.missile(e, 40, 60, 15, 10);
            if (ScenePainter.visible(cam, 60, 40, 30, k, 3)) op.laser(e, 60, 40, 30, 70, Palette.RED);
        }
        for (int k = 0; k < n; k++) { // step 2 points
            cam.applyTo(e, k);
            if (Particles.sparkShown(0, 0, rand) && ScenePainter.visible(cam, 25, 5, tr, k, 2)) Particles.spark(e, P, 25, 5, 5, z);
            if (Particles.sparkShown(1, 0, rand) && ScenePainter.visible(cam, 995, 995, tr, k, 2)) Particles.spark(e, P, 995, 995, 7, z);
            if (ScenePainter.visible(cam, 10, 12, tr, k, 2)) op.shot(e, 10, 12, Palette.WHITE, z);
            if (ScenePainter.visible(cam, 990, 15, tr, k, 2)) op.shot(e, 990, 15, Palette.WHITE, z);
        }
        for (int k = 0; k < n; k++) { // step 2 lines
            cam.applyTo(e, k);
            if (ScenePainter.visible(cam, 14, 12, tr, k, 2)) op.teamShot(e, 14, 12, Palette.BLUE, ScenePainter.diagonal(1, 0), z);
            if (ScenePainter.visible(cam, 995, 15, tr, k, 2)) op.teamShot(e, 995, 15, Palette.BLUE, ScenePainter.diagonal(3, 0), z);
        }
        for (int k = 0; k < n; k++) { // step 3 fills
            cam.applyTo(e, k);
            if (ScenePainter.visible(cam, 70, 30, ObjectPainter.HALF_PAUSE + 1, k, 0)) op.pausedFill(e, 70, 30);
            if (ScenePainter.visible(cam, 990, 985, ObjectPainter.HALF_PAUSE + 1, k, 0)) op.pausedFill(e, 990, 985);
            if (ScenePainter.visible(cam, 30, 40, sr, k, 0)) sp.lockedFill(e, SHIP, 30, 40, 0.3, Palette.WHITE);
        }
        e.identity();
        float lm = ScenePainter.LABEL_MARGIN;
        for (int k = 0; k < n; k++) { // step 3 text
            if (ScenePainter.visible(cam, 70, 30, 0, k, m)) op.pausedLetter(e, TEXT, cam.screenX(70, k), cam.screenY(29, k));
            if (ScenePainter.visible(cam, 990, 985, 0, k, m)) op.pausedLetter(e, TEXT, cam.screenX(990, k), cam.screenY(984, k));
            if (ScenePainter.visible(cam, 30, 40, 0, k, lm)) sp.name(e, TEXT, cam.screenX(30, k), cam.screenY(40, k), "zero", 1, z);
            if (ScenePainter.visible(cam, 50, 20, 0, k, lm)) op.mineName(e, TEXT, cam.screenX(50, k), cam.screenY(20, k), "mx", 1, z);
        }
        for (int k = 0; k < n; k++) { // step 3 lines
            cam.applyTo(e, k);
            if (ScenePainter.visible(cam, 70, 30, ObjectPainter.HALF_PAUSE + 1, k, 0)) op.pausedOutline(e, 70, 30);
            if (ScenePainter.visible(cam, 990, 985, ObjectPainter.HALF_PAUSE + 1, k, 0)) op.pausedOutline(e, 990, 985);
            if (ScenePainter.visible(cam, 30, 40, sr, k, 0)) sp.ship(e, SHIP, 30, 40, 0.3, Palette.WHITE, ShipPainter.SHIELD, 0, z);
            if (ScenePainter.visible(cam, 985, 990, sr, k, 0)) sp.ship(e, SHIP, 985, 990, 2.0, Palette.BLUE, ShipPainter.CLOAKED, 0, z);
            if (ScenePainter.visible(cam, 150, 30, sr, k, 0)) sp.ship(e, SHIP, 150, 30, 1.0, Palette.WHITE, 0, 0, z);
            if (ScenePainter.linkShown(cam, 30, 40, 70, 70, k)) op.ballConnector(e, 30, 40, 70, 70);
            if (ScenePainter.linkShown(cam, 30, 40, 985, 990, k)) op.tractorBeam(e, 30, 40, 985, 990, z);
            if (ScenePainter.linkShown(cam, 10, 10, 20, 50, k)) op.dashedLink(e, 10, 10, 20, 50, 0, z);
            if (ScenePainter.linkShown(cam, 55, 25, 70, 70, k)) op.connector(e, 55, 25, 70, 70, z);
        }
        for (int k = 0; k < n; k++) { // step 4 points
            cam.applyTo(e, k);
            if (ScenePainter.visible(cam, 30, 40, sr, k, m)) sp.markingLightSquares(e, SHIP, 30, 40, 0.3, 0, 0, z);
            if (ScenePainter.visible(cam, 985, 990, sr, k, m)) sp.markingLightSquares(e, SHIP, 985, 990, 2.0, 2, 0, z);
            if (ScenePainter.visible(cam, 150, 30, sr, k, m)) sp.markingLightSquares(e, SHIP, 150, 30, 1.0, 9, 0, z);
        }
        for (int k = 0; k < n; k++) { // step 4 lines
            cam.applyTo(e, k);
            if (ScenePainter.visible(cam, 30, 40, sr, k, m)) sp.markingLightCrosses(e, SHIP, 30, 40, 0.3, 0, 0, z);
            if (ScenePainter.visible(cam, 985, 990, sr, k, m)) sp.markingLightCrosses(e, SHIP, 985, 990, 2.0, 2, 0, z);
            if (ScenePainter.visible(cam, 150, 30, sr, k, m)) sp.markingLightCrosses(e, SHIP, 150, 30, 1.0, 9, 0, z);
        }
        for (int k = 0; k < n; k++) { // step 5: arcs
            cam.applyTo(e, k);
            if (ScenePainter.visible(cam, 70, 70, 10, k, 0)) op.ball(e, 70, 70);
            if (ScenePainter.visible(cam, 30, 980, 8, k, 0)) op.ecm(e, 30, 980, 16);
        }
        e.identity();
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
