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
 *   <li>its queues: sparks (with the sparkle) and shots (points), then team-shot arms (lines);</li>
 *   <li>{@code Paint_ships}' direct draws: paused squares and locked hulls (fills), the text (paused
 *   "P", ship names, round-delay digits; mine names, which C draws in step 1), then paused frames,
 *   ships with their shields, and the connectors (lines);</li>
 *   <li>its queues: marking-light squares (points), then their crosses (lines);</li>
 *   <li>{@link #drawArcs}: the arc queue ({@code Arc_end}), balls and ECM bursts.</li>
 * </ol>
 * {@link #draw} is steps 1-5. A caller interleaving the map's and the HUD's parts in full C order
 * calls {@link #drawBody} (steps 1-4), its own non-arc HUD, then the map's arcs, {@link #drawArcs}
 * and the HUD's arcs. Each step loops over all copies, so the scene costs at most 8 draw commands
 * (adjacent commands of one kind merge).
 * <p>
 * While {@link SceneData#damaged} is positive the caller draws only {@link #damagedFlash}.
 * Each object is skipped in a copy where its extent (world radius plus a screen-pixel margin for
 * pixel-sized parts) is off-screen. Allocation-free.
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
    private final ShipPainter ships;
    private final ObjectPainter objects;
    private int sparkRand = Particles.sparkRand(PaintConfig.SPARK_PROB);

    public ScenePainter(Palette palette) {
        this.palette = palette;
        this.ships = new ShipPainter(palette);
        this.objects = new ObjectPainter(palette);
    }

    /**
     * C option {@code sparkProb} (default {@link PaintConfig#SPARK_PROB}): the chance a spark or debris
     * particle is drawn in a frame.
     */
    public void setSparkProb(double prob) {
        sparkRand = Particles.sparkRand(prob);
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
            for (int i = 0; i < s.ballCount; i++) {
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
        for (int i = 0; i < s.mineCount; i++) {
            if (!s.mineTeam[i] && visible(cam, s.mineX[i], s.mineY[i], MINE_RADIUS, k, 2)) {
                objects.mineFill(b, s.mineX[i], s.mineY[i], zoom);
            }
        }
        for (int i = 0; i < s.itemCount; i++) {
            if (visible(cam, s.itemX[i], s.itemY[i], 0, k, PIXEL_MARGIN)) {
                objects.itemSymbol(b, s.itemType[i], s.itemX[i], s.itemY[i], zoom);
            }
        }
    }

    private void objectLines(VertexBatch b, Camera cam, int k, SceneData s, double zoom) {
        for (int i = 0; i < s.itemCount; i++) {
            if (visible(cam, s.itemX[i], s.itemY[i], 0, k, PIXEL_MARGIN)) {
                objects.itemTriangle(b, s.itemX[i], s.itemY[i], zoom);
            }
        }
        for (int i = 0; i < s.mineCount; i++) {
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

    private void shotPoints(VertexBatch b, Camera cam, int k, SceneData s, double zoom) {
        for (int i = 0; i < s.particleCount; i++) {
            if (Particles.sparkShown(i, s.loops, sparkRand)
                && visible(cam, s.particleX[i], s.particleY[i], SHOT_RADIUS, k, 2)) {
                Particles.spark(b, palette, s.particleX[i], s.particleY[i], s.particleColor[i], zoom);
            }
        }
        for (int i = 0; i < s.shotCount; i++) {
            if (!s.shotTeam[i] && visible(cam, s.shotX[i], s.shotY[i], SHOT_RADIUS, k, 2)) {
                objects.shot(b, s.shotX[i], s.shotY[i], s.shotColor[i], zoom);
            }
        }
    }

    private void teamShots(VertexBatch b, Camera cam, int k, SceneData s, double zoom) {
        for (int i = 0; i < s.shotCount; i++) {
            if (s.shotTeam[i] && visible(cam, s.shotX[i], s.shotY[i], SHOT_RADIUS, k, 2)) {
                objects.teamShot(b, s.shotX[i], s.shotY[i], s.shotColor[i], diagonal(i, s.loops), zoom);
            }
        }
    }

    // ------------------------------------------------------------------ step 3: Paint_ships, direct

    private void shipFills(VertexBatch b, Camera cam, int k, SceneData s) {
        for (int i = 0; i < s.pausedCount; i++) {
            if (visible(cam, s.pausedX[i], s.pausedY[i], ObjectPainter.HALF_PAUSE + 1, k, 0)) {
                objects.pausedFill(b, s.pausedX[i], s.pausedY[i]);
            }
        }
        if (delayed(s)) {
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
        for (int i = 0; i < s.pausedCount; i++) {
            if (ObjectPainter.pausedBlinkOn(s.pausedCountdown[i], s.loops)
                && visible(cam, s.pausedX[i], s.pausedY[i], 0, k, PIXEL_MARGIN)) {
                objects.pausedLetter(b, text, cam.screenX(s.pausedX[i], k), cam.screenY(s.pausedY[i] - 1, k));
            }
        }
        boolean delay = delayed(s);
        for (int i = 0; i < s.shipCount; i++) {
            if (s.shipName[i] != null && s.shipId[i] != s.selfId
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
        for (int i = 0; i < s.pausedCount; i++) {
            if (ObjectPainter.pausedBlinkOn(s.pausedCountdown[i], s.loops)
                && visible(cam, s.pausedX[i], s.pausedY[i], ObjectPainter.HALF_PAUSE + 1, k, 0)) {
                objects.pausedOutline(b, s.pausedX[i], s.pausedY[i]);
            }
        }
        if (!delayed(s)) {
            for (int i = 0; i < s.shipCount; i++) {
                if (visible(cam, s.shipX[i], s.shipY[i], SHIP_RADIUS, k, 0)) {
                    ships.ship(b, s.shipShape[i], s.shipX[i], s.shipY[i], s.shipHeading[i], s.shipColor[i],
                        s.shipFlags[i], s.loops, zoom);
                }
            }
        }
        for (int i = 0; i < s.linkCount; i++) {
            if (linkVisible(cam, s, i, k)) {
                link(b, s, i, zoom);
            }
        }
    }

    // ------------------------------------------------------------------ step 4: Paint_ships, queued

    private void lightSquares(VertexBatch b, Camera cam, int k, SceneData s, double zoom) {
        if (delayed(s)) {
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
        if (delayed(s)) {
            return;
        }
        for (int i = 0; i < s.shipCount; i++) {
            if (visible(cam, s.shipX[i], s.shipY[i], SHIP_RADIUS, k, PIXEL_MARGIN)) {
                ships.markingLightCrosses(b, s.shipShape[i], s.shipX[i], s.shipY[i], s.shipHeading[i], s.shipId[i],
                    s.loops, zoom);
            }
        }
    }

    private static boolean linkVisible(Camera cam, SceneData s, int i, int k) {
        float x0 = Math.min(s.linkX1[i], s.linkX2[i]);
        float x1 = Math.max(s.linkX1[i], s.linkX2[i]);
        float y0 = Math.min(s.linkY1[i], s.linkY2[i]);
        float y1 = Math.max(s.linkY1[i], s.linkY2[i]);
        return cam.isVisible(x0, y0, x1, y1, k, 0);
    }

    private void link(VertexBatch b, SceneData s, int i, double zoom) {
        switch (s.linkKind[i]) {
            case SceneData.LINK_BALL -> objects.ballConnector(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i]);
            case SceneData.LINK_CONNECTOR -> objects.connector(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i],
                zoom);
            case SceneData.LINK_TRACTOR -> objects.tractorBeam(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i],
                zoom);
            default -> objects.dashedLink(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i], s.loops, zoom);
        }
    }
}
