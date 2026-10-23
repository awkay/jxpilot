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

import com.fulcrologic.jxpilot.client.render.VertexBatch;

/**
 * X11 {@code LineOnOffDash} lines as segments: a pattern of {@code on} drawn and {@code off} skipped
 * pixels, started {@code offset} pixels into the pattern ({@code dash_offset}) and carried on across
 * the joints of a polyline, as {@code XDrawLines} does.
 * <p>
 * XPilot's dashed lines are zero-width ("thin") lines, which the X server dashes per pixel drawn:
 * the X11 protocol allows thin-line dash lengths to be measured along the line's major axis
 * (x for lines within 45 degrees of horizontal, else y), and the sample server's Bresenham dasher
 * ({@code miZeroDashLine}) does exactly that, one dash unit per pixel step. So a diagonal edge
 * advances the pattern by {@code max(|dx|, |dy|)} pixels, not by its Euclidean length.
 * <p>
 * Coordinates may be in any space (world coordinates through the batch transform, or framebuffer
 * pixels); {@code unit} is the length of one pattern pixel in that space (e.g. {@code 1 / zoom} for
 * world coordinates, the pixel ratio for framebuffer coordinates). Allocation-free.
 */
public final class Dashes {
    private Dashes() {}

    /**
     * Dash a polyline through {@code n} points ({@code n + 1} edges back to the first when
     * {@code closed}).
     * <p>
     * BEHAVIOR: the pattern position starts at {@code offset mod (on + off)} and advances by each
     * edge's major-axis length ({@code max(|dx|, |dy|)}) in pattern pixels; drawn pieces are the parts of
     * each edge inside an "on" stretch.
     * BEHAVIOR: zero-length edges draw nothing and do not advance the pattern; fewer than 2 points draw nothing.
     *
     * @return the pattern position after the last edge (to continue the pattern on a following line)
     */
    public static float polyline(VertexBatch b, float[] xs, float[] ys, int n, boolean closed,
                                 float on, float off, float offset, float unit, int rgba) {
        float period = on + off;
        float p = phase(offset, period);
        if (n < 2) {
            return p;
        }
        int edges = closed ? n : n - 1;
        for (int e = 0; e < edges; e++) {
            int j = e + 1 == n ? 0 : e + 1;
            p = edge(b, xs[e], ys[e], xs[j], ys[j], on, period, p, unit, rgba);
        }
        return p;
    }

    /**
     * One dashed segment starting {@code offset} pixels into the pattern.
     * <p>
     * BEHAVIOR: as {@link #polyline} for a single edge (the offset is reduced modulo the period).
     *
     * @return the pattern position at the end of the segment
     */
    public static float segment(VertexBatch b, float x1, float y1, float x2, float y2,
                                float on, float off, float offset, float unit, int rgba) {
        return edge(b, x1, y1, x2, y2, on, on + off, phase(offset, on + off), unit, rgba);
    }

    /** {@code offset} reduced into [0, period). */
    static float phase(float offset, float period) {
        float p = offset % period;
        return p < 0 ? p + period : p;
    }

    /** Pattern remainders below this many pixels count as reached (guards float stalls). */
    static final double EPS = 1e-4;

    private static float edge(VertexBatch b, float x1, float y1, float x2, float y2,
                              float on, float period, float phase, float unit, int rgba) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double len = Math.max(Math.abs(dx), Math.abs(dy)) / unit;
        if (!(len > 0)) {
            return phase;
        }
        double p = phase;
        double t = 0;
        while (len - t > EPS) {
            boolean drawing = p < on;
            double stretch = (drawing ? on : period) - p;
            if (stretch <= EPS) { // at a boundary: switch without moving
                p = drawing ? on : 0;
                continue;
            }
            double end = Math.min(len, t + stretch);
            if (drawing) {
                b.line((float) (x1 + dx * (t / len)), (float) (y1 + dy * (t / len)),
                    (float) (x1 + dx * (end / len)), (float) (y1 + dy * (end / len)), rgba);
            }
            p += end - t;
            t = end;
            if (p >= period - EPS) {
                p = 0;
            }
        }
        return (float) p;
    }

    /**
     * Dashed circle as X11 {@code XDrawArc} of a full circle: {@code segments} chords counter-clockwise
     * from angle 0 (3 o'clock), dashed as one closed polyline. Uses {@code xs}/{@code ys} as scratch
     * (length at least {@code segments}).
     */
    public static void circle(VertexBatch b, float cx, float cy, float r, int segments, float[] xs, float[] ys,
                              float on, float off, float offset, float unit, int rgba) {
        for (int i = 0; i < segments; i++) {
            double a = 360.0 * i / segments;
            xs[i] = cx + (float) (r * com.fulcrologic.jxpilot.client.render.Trig.cosDeg(a));
            ys[i] = cy + (float) (r * com.fulcrologic.jxpilot.client.render.Trig.sinDeg(a));
        }
        polyline(b, xs, ys, segments, true, on, off, offset, unit, rgba);
    }
}
