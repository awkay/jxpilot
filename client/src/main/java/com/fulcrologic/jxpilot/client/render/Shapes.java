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
package com.fulcrologic.jxpilot.client.render;

/**
 * Outline shapes built from line segments. Angles follow X11 arcs: degrees counter-clockwise from
 * +x (y up), which is also how they look on screen.
 */
public final class Shapes {
    private Shapes() {}

    /**
     * Arc of a circle as {@code segments} chords from {@code startDeg} sweeping {@code extentDeg}
     * (negative sweeps clockwise).
     * <p>
     * BEHAVIOR: chord i joins the points at {@code start + extent*i/segments} and {@code start + extent*(i+1)/segments}.
     */
    public static void arc(VertexBatch b, float cx, float cy, float r, float startDeg, float extentDeg,
                           int segments, int rgba) {
        double a0 = Math.toRadians(startDeg);
        double step = Math.toRadians(extentDeg) / segments;
        float px = cx + (float) (r * Math.cos(a0));
        float py = cy + (float) (r * Math.sin(a0));
        for (int i = 1; i <= segments; i++) {
            double a = a0 + step * i;
            float qx = cx + (float) (r * Math.cos(a));
            float qy = cy + (float) (r * Math.sin(a));
            b.line(px, py, qx, qy, rgba);
            px = qx;
            py = qy;
        }
    }

    /** Full circle outline (an arc from 0 sweeping 360 degrees). */
    public static void circle(VertexBatch b, float cx, float cy, float r, int segments, int rgba) {
        arc(b, cx, cy, r, 0, 360, segments, rgba);
    }

    /** Closed polygon outline through {@code n} points {@code (xs[i], ys[i])}. */
    public static void closedPolyline(VertexBatch b, float[] xs, float[] ys, int n, int rgba) {
        for (int i = 0; i < n; i++) {
            int j = i + 1 == n ? 0 : i + 1;
            b.line(xs[i], ys[i], xs[j], ys[j], rgba);
        }
    }
}
