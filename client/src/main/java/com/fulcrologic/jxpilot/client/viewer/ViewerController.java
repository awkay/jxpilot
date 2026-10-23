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

/**
 * Free-flying camera for the map viewer. Held keys are a bit set; {@link #update} integrates them
 * over real elapsed time, so motion is independent of the frame rate.
 * <p>
 * Panning moves {@link #PAN_SPEED} logical screen pixels per second whatever the zoom; zoom keys
 * scale zoom by {@link #ZOOM_RATE} per second. Zoom stays within [{@link #MIN_ZOOM}, {@link #MAX_ZOOM}].
 */
public final class ViewerController {
    public static final int LEFT = 1;
    public static final int RIGHT = 2;
    public static final int UP = 4;
    public static final int DOWN = 8;
    public static final int ZOOM_IN = 16;
    public static final int ZOOM_OUT = 32;

    public static final double PAN_SPEED = 900;
    public static final double ZOOM_RATE = 2.5;
    public static final double WHEEL_STEP = 1.15;
    public static final double MIN_ZOOM = 0.05;
    public static final double MAX_ZOOM = 8;

    private int held;
    private double x;
    private double y;
    private double zoom = 1;

    /** Place the camera. */
    public void reset(double x, double y, double zoom) {
        this.x = x;
        this.y = y;
        this.zoom = clampZoom(zoom);
    }

    public void press(int bits) { held |= bits; }

    public void release(int bits) { held &= ~bits; }

    public int held() { return held; }

    public double x() { return x; }

    public double y() { return y; }

    public double zoom() { return zoom; }

    /**
     * Advance by {@code dt} seconds.
     * <p>
     * BEHAVIOR: opposite pan keys cancel; pan distance is {@code PAN_SPEED * dt / zoom} world pixels.
     * BEHAVIOR: zoom-in alone multiplies zoom by {@code ZOOM_RATE^dt}, zoom-out alone divides; both cancel.
     */
    public void update(double dt) {
        int dx = bit(RIGHT) - bit(LEFT);
        int dy = bit(UP) - bit(DOWN);
        double d = PAN_SPEED * dt / zoom;
        x += dx * d;
        y += dy * d;
        int dz = bit(ZOOM_IN) - bit(ZOOM_OUT);
        if (dz != 0) {
            zoom = clampZoom(zoom * Math.pow(ZOOM_RATE, dz * dt));
        }
    }

    /** Zoom by {@code WHEEL_STEP^steps} (mouse wheel). */
    public void wheel(double steps) {
        zoom = clampZoom(zoom * Math.pow(WHEEL_STEP, steps));
    }

    /** Copy position and zoom into the camera (the camera wraps the centre itself). */
    public void applyTo(Camera cam) {
        cam.setCentre(x, y);
        cam.setZoom(zoom);
    }

    /** Take the camera's (possibly wrapped) centre back so coordinates stay bounded. */
    public void syncFrom(Camera cam) {
        x = cam.centreX();
        y = cam.centreY();
    }

    private int bit(int b) {
        return (held & b) != 0 ? 1 : 0;
    }

    static double clampZoom(double z) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }
}
