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

import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.Const;

/**
 * Draws a frame's {@link SceneData} for every visible world copy in the order of the C client's
 * deferred flushes ({@code paint.c} {@code Paint_frame}, {@code paintdata.c}): what C draws at once
 * comes in call order, what it queues ({@code Rectangle_add}, {@code Segment_add}, {@code Arc_add})
 * comes when C flushes the queue.
 * <ol>
 *   <li>{@code Paint_shots}' direct draws: mine blocks and item symbols (fills), then item triangles,
 *   mine outlines, wreckage, asteroids, missiles and lasers (lines);</li>
 *   <li>its queues: sparks (with the sparkle) and shots (points), then team-shot arms (lines; with
 *   {@code showNastyShots} every shot is drawn as arms);</li>
 *   <li>{@code Paint_ships}' direct draws: paused squares and locked hulls (fills), the text (paused
 *   "P", ship names, round-delay digits; mine names, which C draws in step 1), then paused frames,
 *   ships with their shields, and the connectors (lines);</li>
 *   <li>its queues: marking-light squares (points), then their crosses (lines), only with
 *   {@code markingLights};</li>
 *   <li>{@link #drawArcs}: the arc queue ({@code Arc_end}), balls and ECM bursts.</li>
 * </ol>
 * With {@code texturedBalls} C fills each ball with its texture at once instead of queueing its arc, so textured
 * balls come with the step 1 fills. With {@code texturedObjects} (C {@code blockBitmaps}) mines, item symbols, balls,
 * shots, paused markers, ships (uncloaked) and refuel beams are C's block bitmaps, drawn where C draws them.
 * {@link #draw} is steps 1-5. A caller interleaving the map's and the HUD's parts in full C order
 * calls {@link #drawBody} (steps 1-4), its own non-arc HUD, then the map's arcs, {@link #drawArcs}
 * and the HUD's arcs. Each step loops over all copies, so the scene costs at most 8 draw commands
 * (adjacent commands of one kind merge).
 * <p>
 * While {@link SceneData#damaged} is positive the caller draws only {@link #damagedFlash}.
 * Each object is skipped in a copy where its extent (world radius plus a screen-pixel margin for
 * pixel-sized parts) is off-screen; a line (connector, beam, ball tether) is drawn only with both ends on
 * screen, as C's {@code wrap()} of each end ({@link #linkShown}). Allocation-free.
 */
public final class ScenePainter {
    /** World radius covering a ship with shields, lights and its cull slack. */
    static final float SHIP_RADIUS = 40;
    /** Screen-pixel margin for pixel-sized parts (item triangles, lights, labels). */
    static final float PIXEL_MARGIN = 24;
    /** Margin for text labels, which reach further than the object. */
    static final float LABEL_MARGIN = 160;
    /** World radius of a mine (its outline spans x-8..x+9 window pixels) and of a shot or spark. */
    static final float MINE_RADIUS = 10;
    static final float SHOT_RADIUS = 2;

    private final Palette palette;
    private final PaintStyle style;
    private final ShipPainter ships;
    private final ObjectPainter objects;
    /** This frame's C dash-offset reset: a connector or tractor beam was drawn ({@link #connectorDrawn}). */
    private boolean dashReset;

    /** With C's default look options. */
    public ScenePainter(Palette palette) {
        this(palette, new PaintStyle());
    }

    /**
     * @param style the live look options: {@code sparkProb}, {@code sparkSize}, {@code shotSize},
     *              {@code showNastyShots}, {@code markingLights}, {@code showShipName}, {@code maxColors}
     */
    public ScenePainter(Palette palette, PaintStyle style) {
        this.palette = palette;
        this.style = style;
        this.ships = new ShipPainter(palette, style);
        this.objects = new ObjectPainter(palette, style);
    }

    /**
     * C option {@code sparkProb} (default {@link PaintConfig#SPARK_PROB}): the chance a spark or debris
     * particle is drawn in a frame (sets the style's {@link PaintStyle#sparkRand}).
     */
    public void setSparkProb(double prob) {
        style.sparkRand = Particles.sparkRand(prob);
    }

    /** Draw the whole scene (steps 1-5; leaves the batch with the identity transform). */
    public void draw(VertexBatch b, TextWriter text, Camera cam, SceneData s) {
        drawBody(b, text, cam, s);
        drawArcs(b, cam, s);
    }

    /** Steps 1-4: everything but the arcs (leaves the batch with the identity transform). */
    public void drawBody(VertexBatch b, TextWriter text, Camera cam, SceneData s) {
        double zoom = cam.zoom();
        int n = cam.offsetCount();
        for (int k = 0; k < n; k++) {
            cam.applyTo(b, k);
            objectFills(b, cam, k, s, zoom);
        }
        for (int k = 0; k < n; k++) {
            cam.applyTo(b, k);
            objectLines(b, cam, k, s, zoom);
        }
        for (int k = 0; k < n; k++) {
            cam.applyTo(b, k);
            tracers(b, cam, k, s);
        }
        for (int k = 0; k < n; k++) {
            cam.applyTo(b, k);
            shotPoints(b, cam, k, s, zoom);
        }
        for (int k = 0; k < n; k++) {
            cam.applyTo(b, k);
            teamShots(b, cam, k, s, zoom);
        }
        for (int k = 0; k < n; k++) {
            cam.applyTo(b, k);
            shipFills(b, cam, k, s);
        }
        b.identity();
        for (int k = 0; k < n; k++) {
            texts(b, text, cam, k, s, zoom);
        }
        dashReset = connectorDrawn(cam, s);
        for (int k = 0; k < n; k++) {
            cam.applyTo(b, k);
            shipLines(b, cam, k, s, zoom);
        }
        for (int k = 0; k < n; k++) {
            cam.applyTo(b, k);
            lightSquares(b, cam, k, s, zoom);
        }
        for (int k = 0; k < n; k++) {
            cam.applyTo(b, k);
            lightCrosses(b, cam, k, s, zoom);
        }
        b.identity();
    }

    /** Step 5, C's {@code Arc_end} of the scene: balls, then ECM bursts (leaves the identity transform). */
    public void drawArcs(VertexBatch b, Camera cam, SceneData s) {
        for (int k = 0; k < cam.offsetCount(); k++) {
            cam.applyTo(b, k);
            for (int i = 0; i < s.ballCount && !style.texturedBalls && !style.texturedObjects; i++) {
                if (visible(cam, s.ballX[i], s.ballY[i], Const.BALL_RADIUS, k, 0)) {
                    objects.ball(b, s.ballX[i], s.ballY[i]);
                }
            }
            for (int i = 0; i < s.ecmCount; i++) {
                if (visible(cam, s.ecmX[i], s.ecmY[i], s.ecmSize[i] / 2, k, 0)) {
                    objects.ecm(b, s.ecmX[i], s.ecmY[i], s.ecmSize[i]);
                }
            }
        }
        b.identity();
    }

    /**
     * C {@code Paint_frame} while {@code damaged > 0}: nothing of the scene, HUD or radar is painted;
     * instead the window is XOR-filled with blue every frame, so it alternates blue and black.
     * <p>
     * BEHAVIOR: returns whether the view is damaged ({@code s.damaged > 0}); the caller then draws
     * nothing else. When damaged and {@code loops} is even, fills the whole viewport (framebuffer
     * pixels, identity transform) blue; on odd frames it draws nothing (black). Undamaged: draws nothing.
     */
    public boolean damagedFlash(VertexBatch b, Camera cam, SceneData s) {
        if (s.damaged <= 0) {
            return false;
        }
        if ((s.loops & 1) == 0) {
            b.identity();
            b.fillRect(0, 0, cam.viewWidth(), cam.viewHeight(), palette.color(Palette.BLUE));
        }
        return true;
    }

    /** True if a world point with world radius {@code r} plus {@code marginPx} pixels shows in copy k. */
    static boolean visible(Camera cam, float x, float y, float r, int k, float marginPx) {
        return cam.isVisible(x - r, y - r, x + r, y + r, k, marginPx);
    }

    /**
     * Stand-in for C's per-shot {@code rfrac() < 0.5}: a hash bit of the shot index and frame, so team
     * shots flicker between X and + without shared random state.
     */
    static boolean diagonal(int i, long loops) {
        int h = (int) (i * 0x9E3779B1L + loops * 0x85EBCA6BL);
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h & 1) != 0;
    }

    /** Whether ship i shows the round-delay digit instead of its hull, lights and shields. */
    private static boolean delayed(SceneData s) {
        return ShipPainter.showRoundDelay(s.roundDelay, s.fps);
    }

    // ------------------------------------------------------------------ step 1: Paint_shots, direct

    private void objectFills(VertexBatch b, Camera cam, int k, SceneData s, double zoom) {
        boolean bm = style.texturedObjects;
        for (int i = 0; i < s.mineCount; i++) {
            if ((bm || !s.mineTeam[i]) && visible(cam, s.mineX[i], s.mineY[i], MINE_RADIUS, k, 2)) {
                if (bm) {
                    objects.bitmapMine(b, s.mineX[i], s.mineY[i], s.mineTeam[i]);
                } else {
                    objects.mineFill(b, s.mineX[i], s.mineY[i], zoom);
                }
            }
        }
        for (int i = 0; i < s.itemCount; i++) {
            if (visible(cam, s.itemX[i], s.itemY[i], 0, k, PIXEL_MARGIN)) {
                if (bm) {
                    objects.bitmapItemSymbol(b, s.itemType[i], s.itemX[i], s.itemY[i], zoom);
                } else {
                    objects.itemSymbol(b, s.itemType[i], s.itemX[i], s.itemY[i], zoom);
                }
            }
        }
        for (int i = 0; i < s.ballCount && (style.texturedBalls || bm); i++) {
            if (visible(cam, s.ballX[i], s.ballY[i], Const.BALL_RADIUS, k, 0)) {
                if (bm) {
                    objects.bitmapBall(b, s.ballX[i], s.ballY[i]);
                } else {
                    objects.texturedBall(b, s.ballX[i], s.ballY[i], zoom);
                }
            }
        }
    }

    private void objectLines(VertexBatch b, Camera cam, int k, SceneData s, double zoom) {
        for (int i = 0; i < s.itemCount; i++) {
            if (visible(cam, s.itemX[i], s.itemY[i], 0, k, PIXEL_MARGIN)) {
                objects.itemTriangle(b, s.itemX[i], s.itemY[i], zoom);
            }
        }
        for (int i = 0; i < s.mineCount && !style.texturedObjects; i++) {
            if (visible(cam, s.mineX[i], s.mineY[i], MINE_RADIUS, k, 2)) {
                objects.mineOutline(b, s.mineX[i], s.mineY[i], zoom);
            }
        }
        for (int i = 0; i < s.wreckCount; i++) {
            if (visible(cam, s.wreckX[i], s.wreckY[i], s.wreckSize[i] / 16f, k, 0)) {
                objects.wreck(b, s.wreckX[i], s.wreckY[i], s.wreckDeadly[i], s.wreckType[i], s.wreckRot[i],
                    s.wreckSize[i]);
            }
        }
        for (int i = 0; i < s.asteroidCount; i++) {
            if (visible(cam, s.asteroidX[i], s.asteroidY[i], s.asteroidSize[i] * 15f, k, 0)) {
                objects.asteroid(b, s.asteroidX[i], s.asteroidY[i], s.asteroidType[i], s.asteroidRot[i],
                    s.asteroidSize[i]);
            }
        }
        for (int i = 0; i < s.missileCount; i++) {
            if (visible(cam, s.missileX[i], s.missileY[i], s.missileLen[i], k, PaintConfig.MISSILE_WIDTH)) {
                objects.missile(b, s.missileX[i], s.missileY[i], s.missileLen[i], s.missileDir[i]);
            }
        }
        for (int i = 0; i < s.laserCount; i++) {
            if (visible(cam, s.laserX[i], s.laserY[i], s.laserLen[i], k, PaintConfig.LASER_WIDTH)) {
                objects.laser(b, s.laserX[i], s.laserY[i], s.laserLen[i], s.laserDir[i], s.laserColor[i]);
            }
        }
    }

    // ------------------------------------------------------------------ step 2: Paint_shots, queued

    /**
     * Tracers of new bullets (prediction on, JXPilot only): a thin line from the bullet's source to where it is drawn,
     * in its colour, fading out; under the shots.
     * <p>
     * BEHAVIOR: drawn when the segment's bounding box meets copy k's view, at the tracer's alpha times the colour's;
     * none with {@code showShotTracers} off ({@link PaintStyle#shotTracers}).
     */
    private void tracers(VertexBatch b, Camera cam, int k, SceneData s) {
        for (int i = 0; i < s.tracerCount && style.shotTracers; i++) {
            // the segment's bounding box (its start is unwrapped next to its end): one crossing the view with both
            // ends outside it is drawn too
            if (cam.isVisible(Math.min(s.tracerX1[i], s.tracerX2[i]), Math.min(s.tracerY1[i], s.tracerY2[i]),
                Math.max(s.tracerX1[i], s.tracerX2[i]), Math.max(s.tracerY1[i], s.tracerY2[i]), k, 0)) {
                int rgba = palette.color(s.tracerColor[i]);
                int alpha = Math.round(((rgba >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, s.tracerAlpha[i])));
                b.line(s.tracerX1[i], s.tracerY1[i], s.tracerX2[i], s.tracerY2[i],
                    com.fulcrologic.jxpilot.client.render.Palette.withAlpha(rgba, alpha));
            }
        }
    }

    private void shotPoints(VertexBatch b, Camera cam, int k, SceneData s, double zoom) {
        int sparkRand = style.sparkRand;
        int sparkSize = style.sparkSize;
        for (int i = 0; i < s.particleCount; i++) {
            if (Particles.sparkShown(i, s.loops, sparkRand)
                && visible(cam, s.particleX[i], s.particleY[i], SHOT_RADIUS, k, 2)) {
                Particles.spark(b, palette, s.particleX[i], s.particleY[i], s.particleColor[i], sparkSize, zoom);
            }
        }
        if (style.nastyShots && !style.texturedObjects) {
            return;
        }
        for (int i = 0; i < s.shotCount; i++) {
            if (!s.shotTeam[i] && visible(cam, s.shotX[i], s.shotY[i], SHOT_RADIUS, k, 2)) {
                if (style.texturedObjects) {
                    objects.bullet(b, s.shotX[i], s.shotY[i], false, zoom);
                } else {
                    objects.shot(b, s.shotX[i], s.shotY[i], s.shotColor[i], zoom);
                }
            }
        }
    }

    /**
     * C's Segment_add shots: team shots, and with {@code showNastyShots} the fast shots too (C
     * {@code Gui_paint_fastshot}: an X or + in the fast shot's colour, white or blue, instead of the square).
     */
    private void teamShots(VertexBatch b, Camera cam, int k, SceneData s, double zoom) {
        boolean bm = style.texturedObjects;
        boolean nasty = style.nastyShots && !bm;
        for (int i = 0; i < s.shotCount; i++) {
            boolean team = s.shotTeam[i];
            if (bm && team && visible(cam, s.shotX[i], s.shotY[i], SHOT_RADIUS, k, 2)) {
                objects.bullet(b, s.shotX[i], s.shotY[i], true, zoom);
            } else if (!bm && (team || nasty) && visible(cam, s.shotX[i], s.shotY[i], SHOT_RADIUS, k, 2)) {
                int c = team ? s.shotColor[i] : ObjectPainter.fastShotColor(s.shotColor[i]);
                objects.teamShot(b, s.shotX[i], s.shotY[i], c, diagonal(i, s.loops), zoom);
            }
        }
    }

    // ------------------------------------------------------------------ step 3: Paint_ships, direct

    private void shipFills(VertexBatch b, Camera cam, int k, SceneData s) {
        boolean bm = style.texturedObjects;
        for (int i = 0; i < s.pausedCount; i++) {
            if (visible(cam, s.pausedX[i], s.pausedY[i], ObjectPainter.HALF_PAUSE + 1, k, 0)) {
                if (bm) {
                    objects.bitmapPaused(b, s.pausedX[i], s.pausedY[i], s.pausedCountdown[i], s.loops);
                } else {
                    objects.pausedFill(b, s.pausedX[i], s.pausedY[i]);
                }
            }
        }
        if (delayed(s)) {
            return;
        }
        if (bm) {
            for (int i = 0; i < s.shipCount; i++) {
                if (ShipPainter.bitmapShown(s.shipFlags[i]) && visible(cam, s.shipX[i], s.shipY[i], SHIP_RADIUS, k, 0)) {
                    ships.bitmapShip(b, s.shipX[i], s.shipY[i], s.shipHeading[i], s.shipColor[i],
                        s.shipId[i] == s.selfId, cam.zoom());
                }
            }
            return;
        }
        for (int i = 0; i < s.shipCount; i++) {
            if (ShipPainter.lockedFillShown(s.shipLocked[i], s.shipFlags[i])
                && visible(cam, s.shipX[i], s.shipY[i], SHIP_RADIUS, k, 0)) {
                ships.lockedFill(b, s.shipShape[i], s.shipX[i], s.shipY[i], s.shipHeading[i], s.shipColor[i]);
            }
        }
    }

    private void texts(VertexBatch b, TextWriter text, Camera cam, int k, SceneData s, double zoom) {
        double ratio = cam.pixelRatio();
        for (int i = 0; i < s.pausedCount && !style.texturedObjects; i++) {
            if (ObjectPainter.pausedBlinkOn(s.pausedCountdown[i], s.loops)
                && visible(cam, s.pausedX[i], s.pausedY[i], 0, k, PIXEL_MARGIN)) {
                objects.pausedLetter(b, text, cam.screenX(s.pausedX[i], k), cam.screenY(s.pausedY[i] - 1, k));
            }
        }
        boolean delay = delayed(s);
        for (int i = 0; i < s.shipCount; i++) {
            if (style.showShipName && s.shipName[i] != null && s.shipId[i] != s.selfId
                && visible(cam, s.shipX[i], s.shipY[i], 0, k, LABEL_MARGIN)) {
                ships.name(b, text, cam.screenX(s.shipX[i], k), cam.screenY(s.shipY[i], k), s.shipName[i], ratio, zoom);
            }
            if (delay && visible(cam, s.shipX[i], s.shipY[i], 0, k, PIXEL_MARGIN)) {
                ships.roundDelay(b, text, cam.screenX(s.shipX[i], k), cam.screenY(s.shipY[i], k), s.roundDelay / s.fps);
            }
        }
        for (int i = 0; i < s.mineCount; i++) {
            if (s.mineName[i] != null && visible(cam, s.mineX[i], s.mineY[i], 0, k, LABEL_MARGIN)) {
                objects.mineName(b, text, cam.screenX(s.mineX[i], k), cam.screenY(s.mineY[i], k), s.mineName[i], ratio,
                    zoom);
            }
        }
    }

    private void shipLines(VertexBatch b, Camera cam, int k, SceneData s, double zoom) {
        for (int i = 0; i < s.pausedCount && !style.texturedObjects; i++) {
            if (ObjectPainter.pausedBlinkOn(s.pausedCountdown[i], s.loops)
                && visible(cam, s.pausedX[i], s.pausedY[i], ObjectPainter.HALF_PAUSE + 1, k, 0)) {
                objects.pausedOutline(b, s.pausedX[i], s.pausedY[i]);
            }
        }
        if (!delayed(s)) {
            for (int i = 0; i < s.shipCount; i++) {
                if (visible(cam, s.shipX[i], s.shipY[i], SHIP_RADIUS, k, 0)) {
                    if (style.texturedObjects) {
                        ships.shipWithoutHull(b, s.shipShape[i], s.shipX[i], s.shipY[i], s.shipHeading[i],
                            s.shipColor[i], s.shipFlags[i], s.loops, zoom);
                    } else {
                        ships.ship(b, s.shipShape[i], s.shipX[i], s.shipY[i], s.shipHeading[i], s.shipColor[i],
                            s.shipFlags[i], s.loops, zoom);
                    }
                }
            }
        }
        for (int i = 0; i < s.linkCount; i++) {
            if (linkShown(cam, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i], k)) {
                link(b, s, i, zoom);
            }
        }
    }

    // ------------------------------------------------------------------ step 4: Paint_ships, queued

    private void lightSquares(VertexBatch b, Camera cam, int k, SceneData s, double zoom) {
        if (delayed(s) || !style.markingLights) {
            return;
        }
        for (int i = 0; i < s.shipCount; i++) {
            if (visible(cam, s.shipX[i], s.shipY[i], SHIP_RADIUS, k, PIXEL_MARGIN)) {
                ships.markingLightSquares(b, s.shipShape[i], s.shipX[i], s.shipY[i], s.shipHeading[i], s.shipId[i],
                    s.loops, zoom);
            }
        }
    }

    private void lightCrosses(VertexBatch b, Camera cam, int k, SceneData s, double zoom) {
        if (delayed(s) || !style.markingLights) {
            return;
        }
        for (int i = 0; i < s.shipCount; i++) {
            if (visible(cam, s.shipX[i], s.shipY[i], SHIP_RADIUS, k, PIXEL_MARGIN)) {
                ships.markingLightCrosses(b, s.shipShape[i], s.shipX[i], s.shipY[i], s.shipHeading[i], s.shipId[i],
                    s.loops, zoom);
            }
        }
    }

    /**
     * C {@code Paint_refuel}/{@code Paint_connectors}/{@code Paint_transporters}/{@code Paint_balls}: a line is drawn
     * only when {@code wrap()} accepts both of its ends, i.e. each end lies in the view (edges included).
     * <p>
     * BEHAVIOR: true iff both endpoints are inside copy k's viewport (touching counts); a line with an end off
     * screen is not drawn, even when it crosses the view.
     */
    static boolean linkShown(Camera cam, float x1, float y1, float x2, float y2, int k) {
        return cam.isVisible(x1, y1, x1, y1, k, 0) && cam.isVisible(x2, y2, x2, y2, k, 0);
    }

    /**
     * C guiobjects.c:527-546: each drawn connector (ball pick-up line or tractor/pressor beam) sets the GC dashes with
     * {@code XSetDashes(gc, 0, ...)}, which also resets the dash offset to 0; the transporter beams drawn after it in
     * the frame ({@code Paint_all_connectors}: refuel, then connectors, then transporters) therefore dash from offset
     * 0 instead of the crawling ship offset. Refuel lines come first and always crawl; the next frame's first dashed
     * ship or {@code Gui_paint_all_connectors_begin} restores the crawl.
     * <p>
     * BEHAVIOR: true iff some LINK_CONNECTOR or LINK_TRACTOR link is {@link #linkShown} in some copy.
     */
    static boolean connectorDrawn(Camera cam, SceneData s) {
        for (int i = 0; i < s.linkCount; i++) {
            int kind = s.linkKind[i];
            if (kind != SceneData.LINK_CONNECTOR && kind != SceneData.LINK_TRACTOR) {
                continue;
            }
            for (int k = 0; k < cam.offsetCount(); k++) {
                if (linkShown(cam, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i], k)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void link(VertexBatch b, SceneData s, int i, double zoom) {
        switch (s.linkKind[i]) {
            case SceneData.LINK_BALL -> objects.ballConnector(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i]);
            case SceneData.LINK_CONNECTOR -> objects.connector(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i],
                zoom);
            case SceneData.LINK_TRACTOR -> objects.tractorBeam(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i],
                zoom);
            case SceneData.LINK_REFUEL -> {
                if (style.texturedObjects) {
                    objects.bitmapRefuel(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i], s.loops, zoom);
                } else {
                    objects.dashedLink(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i], s.loops, zoom);
                }
            }
            case SceneData.LINK_TRANSPORTER -> objects.transporter(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i],
                s.loops, zoom, dashReset);
            default -> objects.dashedLink(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i], s.loops, zoom);
        }
    }
}
