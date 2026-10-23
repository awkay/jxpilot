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
