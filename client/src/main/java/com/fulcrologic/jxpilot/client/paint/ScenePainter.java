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

/**
 * Draws a frame's {@link SceneData} for every visible world copy in four passes, so the whole scene
 * costs four draw commands: fills (mine blocks, paused squares, locked hulls, item symbols), points
 * (particles, shots, marking-light squares), lines (everything else, in C's order: items, balls,
 * mines, wreckage, asteroids, team shots, missiles, lasers, paused frames, ECM, ships, links,
 * marking-light crosses), then text (ship and mine names, the paused "P").
 * <p>
 * Each object is skipped in a copy where its extent (world radius plus a screen-pixel margin for
 * pixel-sized parts) is off-screen. Allocation-free.
 */
public final class ScenePainter {
    /** World radius covering a ship with shields, lights and its cull slack. */
    static final float SHIP_RADIUS = 40;
    /** Screen-pixel margin for pixel-sized parts (item triangles, mines, lights, labels). */
    static final float PIXEL_MARGIN = 24;
    /** Margin for text labels, which reach further than the object. */
    static final float LABEL_MARGIN = 160;

    private final Palette palette;
    private final ShipPainter ships;
    private final ObjectPainter objects;

    public ScenePainter(Palette palette) {
        this.palette = palette;
        this.ships = new ShipPainter(palette);
        this.objects = new ObjectPainter(palette);
    }

    /** Draw the scene (leaves the batch with the identity transform). */
    public void draw(VertexBatch b, TextWriter text, Camera cam, SceneData s) {
        float px = (float) (1 / cam.zoom());
        for (int k = 0; k < cam.offsetCount(); k++) {
            cam.applyTo(b, k);
            fills(b, cam, k, s, px);
        }
        for (int k = 0; k < cam.offsetCount(); k++) {
            cam.applyTo(b, k);
            points(b, cam, k, s, px);
        }
        for (int k = 0; k < cam.offsetCount(); k++) {
            cam.applyTo(b, k);
            lines(b, cam, k, s, px);
        }
        b.identity();
        for (int k = 0; k < cam.offsetCount(); k++) {
            texts(b, text, cam, k, s);
        }
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

    private void fills(VertexBatch b, Camera cam, int k, SceneData s, float px) {
        for (int i = 0; i < s.mineCount; i++) {
            if (!s.mineTeam[i] && visible(cam, s.mineX[i], s.mineY[i], 0, k, PIXEL_MARGIN)) {
                objects.mineFill(b, s.mineX[i], s.mineY[i], px);
            }
        }
        for (int i = 0; i < s.pausedCount; i++) {
            if (visible(cam, s.pausedX[i], s.pausedY[i], ObjectPainter.HALF_PAUSE + 1, k, 0)) {
                objects.pausedFill(b, s.pausedX[i], s.pausedY[i]);
            }
        }
        for (int i = 0; i < s.shipCount; i++) {
            if (s.shipLocked[i] && visible(cam, s.shipX[i], s.shipY[i], SHIP_RADIUS, k, 0)) {
                ships.lockedFill(b, s.shipShape[i], s.shipX[i], s.shipY[i], s.shipHeading[i], s.shipColor[i]);
            }
        }
        for (int i = 0; i < s.itemCount; i++) {
            if (visible(cam, s.itemX[i], s.itemY[i], 0, k, PIXEL_MARGIN)) {
                objects.itemSymbol(b, s.itemType[i], s.itemX[i], s.itemY[i], px);
            }
        }
    }

    private void points(VertexBatch b, Camera cam, int k, SceneData s, float px) {
        for (int i = 0; i < s.particleCount; i++) {
            if (visible(cam, s.particleX[i], s.particleY[i], 0, k, PaintConfig.SPARK_SIZE)) {
                Particles.spark(b, palette, s.particleX[i], s.particleY[i], s.particleColor[i], px);
            }
        }
        for (int i = 0; i < s.shotCount; i++) {
            if (!s.shotTeam[i] && visible(cam, s.shotX[i], s.shotY[i], 0, k, PaintConfig.SHOT_SIZE)) {
                objects.shot(b, s.shotX[i], s.shotY[i], s.shotColor[i]);
            }
        }
        for (int i = 0; i < s.shipCount; i++) {
            if (visible(cam, s.shipX[i], s.shipY[i], SHIP_RADIUS, k, PIXEL_MARGIN)) {
                ships.markingLightSquares(b, s.shipShape[i], s.shipX[i], s.shipY[i], s.shipHeading[i], s.shipId[i],
                    s.loops, px);
            }
        }
    }

    private void lines(VertexBatch b, Camera cam, int k, SceneData s, float px) {
        for (int i = 0; i < s.itemCount; i++) {
            if (visible(cam, s.itemX[i], s.itemY[i], 0, k, PIXEL_MARGIN)) {
                objects.itemTriangle(b, s.itemX[i], s.itemY[i], px);
            }
        }
        for (int i = 0; i < s.ballCount; i++) {
            if (visible(cam, s.ballX[i], s.ballY[i], com.fulcrologic.jxpilot.common.Const.BALL_RADIUS, k, 0)) {
                objects.ball(b, s.ballX[i], s.ballY[i]);
            }
        }
        for (int i = 0; i < s.mineCount; i++) {
            if (visible(cam, s.mineX[i], s.mineY[i], 0, k, PIXEL_MARGIN)) {
                objects.mineOutline(b, s.mineX[i], s.mineY[i], px);
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
        for (int i = 0; i < s.shotCount; i++) {
            if (s.shotTeam[i] && visible(cam, s.shotX[i], s.shotY[i], 0, k, PaintConfig.SHOT_SIZE)) {
                objects.teamShot(b, s.shotX[i], s.shotY[i], s.shotColor[i], diagonal(i, s.loops), px);
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
        for (int i = 0; i < s.pausedCount; i++) {
            if (ObjectPainter.pausedBlinkOn(s.pausedCountdown[i], s.loops)
                && visible(cam, s.pausedX[i], s.pausedY[i], ObjectPainter.HALF_PAUSE + 1, k, 0)) {
                objects.pausedOutline(b, s.pausedX[i], s.pausedY[i]);
            }
        }
        for (int i = 0; i < s.ecmCount; i++) {
            if (visible(cam, s.ecmX[i], s.ecmY[i], s.ecmSize[i] / 2, k, 0)) {
                objects.ecm(b, s.ecmX[i], s.ecmY[i], s.ecmSize[i]);
            }
        }
        for (int i = 0; i < s.shipCount; i++) {
            if (visible(cam, s.shipX[i], s.shipY[i], SHIP_RADIUS, k, 0)) {
                ships.ship(b, s.shipShape[i], s.shipX[i], s.shipY[i], s.shipHeading[i], s.shipColor[i], s.shipFlags[i],
                    s.loops, px);
            }
        }
        for (int i = 0; i < s.linkCount; i++) {
            if (linkVisible(cam, s, i, k)) {
                link(b, s, i, px);
            }
        }
        for (int i = 0; i < s.shipCount; i++) {
            if (visible(cam, s.shipX[i], s.shipY[i], SHIP_RADIUS, k, PIXEL_MARGIN)) {
                ships.markingLightCrosses(b, s.shipShape[i], s.shipX[i], s.shipY[i], s.shipHeading[i], s.shipId[i],
                    s.loops, px);
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

    private void link(VertexBatch b, SceneData s, int i, float px) {
        switch (s.linkKind[i]) {
            case SceneData.LINK_BALL -> objects.ballConnector(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i]);
            case SceneData.LINK_TRACTOR -> objects.tractorBeam(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i],
                s.loops, px);
            default -> objects.dashedLink(b, s.linkX1[i], s.linkY1[i], s.linkX2[i], s.linkY2[i], s.loops, px);
        }
    }

    private void texts(VertexBatch b, TextWriter text, Camera cam, int k, SceneData s) {
        double ratio = cam.pixelRatio();
        for (int i = 0; i < s.shipCount; i++) {
            if (s.shipName[i] != null && s.shipId[i] != s.selfId
                && visible(cam, s.shipX[i], s.shipY[i], 0, k, LABEL_MARGIN)) {
                ships.name(b, text, cam.screenX(s.shipX[i], k), cam.screenY(s.shipY[i], k), s.shipName[i], ratio);
            }
        }
        for (int i = 0; i < s.mineCount; i++) {
            if (s.mineName[i] != null && visible(cam, s.mineX[i], s.mineY[i], 0, k, LABEL_MARGIN)) {
                objects.mineName(b, text, cam.screenX(s.mineX[i], k), cam.screenY(s.mineY[i], k), s.mineName[i], ratio);
            }
        }
        for (int i = 0; i < s.pausedCount; i++) {
            if (ObjectPainter.pausedBlinkOn(s.pausedCountdown[i], s.loops)
                && visible(cam, s.pausedX[i], s.pausedY[i], 0, k, PIXEL_MARGIN)) {
                objects.pausedLetter(b, text, cam.screenX(s.pausedX[i], k), cam.screenY(s.pausedY[i] - 1, k));
            }
        }
    }
}
