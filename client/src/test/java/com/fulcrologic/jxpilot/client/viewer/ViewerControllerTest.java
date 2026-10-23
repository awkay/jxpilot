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

import com.fulcrologic.jxpilot.client.render.Camera;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ViewerControllerTest {
    /** Results involve pow(); compare with a tight tolerance. */
    static final double EPS = 1e-9;

    @Test
    void panningMovesAConstantScreenSpeedWhateverTheZoom() {
        ViewerController c = new ViewerController();
        c.reset(100, 200, 2);
        c.press(ViewerController.RIGHT | ViewerController.UP);
        c.update(0.5);
        assertAll(
            () -> assertEquals(100 + 900 * 0.5 / 2, c.x(), EPS, "right: PAN_SPEED * dt / zoom"),
            () -> assertEquals(200 + 900 * 0.5 / 2, c.y(), EPS, "up likewise"),
            () -> assertEquals(2.0, c.zoom(), "zoom unchanged"));
    }

    @Test
    void oppositeKeysCancelAndReleaseStops() {
        ViewerController c = new ViewerController();
        c.reset(0, 0, 1);
        c.press(ViewerController.LEFT | ViewerController.RIGHT | ViewerController.DOWN);
        c.update(1);
        c.release(ViewerController.DOWN);
        c.update(1);
        assertAll(
            () -> assertEquals(0.0, c.x(), "left and right cancel"),
            () -> assertEquals(-900.0, c.y(), "down moved only during the first second"),
            () -> assertEquals(ViewerController.LEFT | ViewerController.RIGHT, c.held(), "released key cleared"));
    }

    @Test
    void zoomKeysScaleExponentiallyAndCancel() {
        ViewerController in = new ViewerController();
        in.reset(0, 0, 1);
        in.press(ViewerController.ZOOM_IN);
        in.update(1);
        ViewerController out = new ViewerController();
        out.reset(0, 0, 1);
        out.press(ViewerController.ZOOM_OUT);
        out.update(0.5);
        ViewerController both = new ViewerController();
        both.reset(0, 0, 1);
        both.press(ViewerController.ZOOM_IN | ViewerController.ZOOM_OUT);
        both.update(1);
        assertAll(
            () -> assertEquals(ViewerController.ZOOM_RATE, in.zoom(), EPS, "one second of zoom-in multiplies by the rate"),
            () -> assertEquals(1 / Math.sqrt(ViewerController.ZOOM_RATE), out.zoom(), EPS, "half a second out divides by sqrt"),
            () -> assertEquals(1.0, both.zoom(), "both cancel"));
    }

    @Test
    void zoomIsClamped() {
        ViewerController c = new ViewerController();
        c.reset(0, 0, 100);
        double high = c.zoom();
        c.wheel(-1000);
        assertAll(
            () -> assertEquals(ViewerController.MAX_ZOOM, high, "reset clamps to the maximum"),
            () -> assertEquals(ViewerController.MIN_ZOOM, c.zoom(), "wheel clamps to the minimum"),
            () -> assertEquals(ViewerController.MAX_ZOOM, ViewerController.clampZoom(9), "clamp above"),
            () -> assertEquals(3.0, ViewerController.clampZoom(3), "within range unchanged"));
    }

    @Test
    void wheelStepsZoom() {
        ViewerController c = new ViewerController();
        c.reset(0, 0, 1);
        c.wheel(2);
        assertEquals(ViewerController.WHEEL_STEP * ViewerController.WHEEL_STEP, c.zoom(), EPS, "two notches");
    }

    @Test
    void cameraRoundTripTakesBackTheWrappedCentre() {
        ViewerController c = new ViewerController();
        c.reset(-10, 1050, 2);
        Camera cam = new Camera();
        cam.setWorld(1000, 1000, true);
        cam.setViewport(100, 100, 1);
        c.applyTo(cam);
        cam.update();
        c.syncFrom(cam);
        assertAll(
            () -> assertEquals(2.0, cam.zoom(), "zoom copied"),
            () -> assertEquals(990.0, c.x(), EPS, "x wrapped by the camera"),
            () -> assertEquals(50.0, c.y(), EPS, "y wrapped by the camera"));
    }
}
