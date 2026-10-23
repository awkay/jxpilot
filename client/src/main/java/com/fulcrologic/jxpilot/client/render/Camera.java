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
 * World-to-screen mapping with toroidal wrap.
 * <p>
 * Screen space is framebuffer pixels with the origin at the bottom-left and y up (world y is also
 * up, so no flip). {@code screen = (world + offset - centre) * scale + viewport / 2}, where
 * {@code scale = zoom * pixelRatio}: zoom is in logical pixels per world pixel and pixelRatio is
 * the HiDPI framebuffer/window ratio.
 * <p>
 * On an edge-wrapping world the visible area can span the seams. {@link #update()} computes the
 * world-copy offsets (multiples of the world size) whose copy intersects the view; the map is drawn
 * once per offset. A view spanning {@code v} world widths needs at most {@code ceil(v) + 1} copies
 * per axis; the offset arrays grow to fit (allocation happens only when the copy count first rises,
 * e.g. on zooming out). Only a pathological view of more than {@link #MAX_COPIES_PER_AXIS} copies
 * per axis (a tiny map zoomed far out) is cut to that many, nearest the view centre. A non-wrapping
 * world always has the single offset (0,0).
 */
public final class Camera {
    /** Safety bound on world copies per axis. */
    public static final int MAX_COPIES_PER_AXIS = 64;

    private double worldW = 1;
    private double worldH = 1;
    private boolean wrap;
    private double cx;
    private double cy;
    private double zoom = 1;
    private double pixelRatio = 1;
    private int viewW = 1;
    private int viewH = 1;

    private double[] offX = new double[9];
    private double[] offY = new double[9];
    private int offN;

    /** Set the world size in pixels and whether it wraps. */
    public void setWorld(double width, double height, boolean edgeWrap) {
        this.worldW = width;
        this.worldH = height;
        this.wrap = edgeWrap;
    }

    /** Set the framebuffer size and the framebuffer-to-window pixel ratio. */
    public void setViewport(int width, int height, double pixelRatio) {
        this.viewW = Math.max(1, width);
        this.viewH = Math.max(1, height);
        this.pixelRatio = pixelRatio;
    }

    /** Set the world point shown at the viewport centre. */
    public void setCentre(double x, double y) {
        this.cx = x;
        this.cy = y;
    }

    /** Set zoom (logical pixels per world pixel). */
    public void setZoom(double zoom) {
        this.zoom = zoom;
    }

    public double centreX() { return cx; }

    public double centreY() { return cy; }

    public double zoom() { return zoom; }

    /** Framebuffer pixels per world pixel. */
    public double scale() { return zoom * pixelRatio; }

    public int viewWidth() { return viewW; }

    public int viewHeight() { return viewH; }

    public double pixelRatio() { return pixelRatio; }

    public boolean wraps() { return wrap; }

    public double worldWidth() { return worldW; }

    public double worldHeight() { return worldH; }

    /**
     * Normalize the centre into the world (wrap only) and recompute the copy offsets.
     * <p>
     * BEHAVIOR: on a wrapping world the centre is reduced modulo the world size into [0, size).
     * BEHAVIOR: offsets are listed x-major-inner: for each y copy (ascending), each x copy (ascending).
     * BEHAVIOR: a non-wrapping world yields exactly one offset (0,0) and the centre is untouched.
     */
    public void update() {
        if (!wrap) {
            offX[0] = 0;
            offY[0] = 0;
            offN = 1;
            return;
        }
        cx = floorMod(cx, worldW);
        cy = floorMod(cy, worldH);
        double hw = viewW / (2 * scale());
        double hh = viewH / (2 * scale());
        int x0 = copyFirst(cx, hw, worldW);
        int x1 = copyLast(cx, hw, worldW, x0);
        int y0 = copyFirst(cy, hh, worldH);
        int y1 = copyLast(cy, hh, worldH, y0);
        int n = (x1 - x0 + 1) * (y1 - y0 + 1);
        if (n > offX.length) {
            offX = new double[n];
            offY = new double[n];
        }
        offN = 0;
        for (int ky = y0; ky <= y1; ky++) {
            for (int kx = x0; kx <= x1; kx++) {
                offX[offN] = kx * worldW;
                offY[offN] = ky * worldH;
                offN++;
            }
        }
    }

    /**
     * Index of the first world copy intersecting {@code [c-h, c+h]}, restricted so that at most
     * {@link #MAX_COPIES_PER_AXIS} copies centred on the view are used.
     * Copy k spans {@code [k*size, (k+1)*size)}.
     */
    static int copyFirst(double c, double h, double size) {
        int first = (int) Math.floor((c - h) / size);
        int last = lastIndex(c, h, size);
        int n = last - first + 1;
        if (n > MAX_COPIES_PER_AXIS) {
            first += (n - MAX_COPIES_PER_AXIS) / 2;
        }
        return first;
    }

    static int copyLast(double c, double h, double size, int first) {
        return Math.min(lastIndex(c, h, size), first + MAX_COPIES_PER_AXIS - 1);
    }

    /** Copy containing the right/top view edge; an edge exactly on a seam does not need the next copy. */
    private static int lastIndex(double c, double h, double size) {
        return (int) Math.ceil((c + h) / size) - 1;
    }

    /** {@code v} reduced into [0, m). */
    static double floorMod(double v, double m) {
        double r = v % m;
        if (r < 0) {
            r += m;
            if (r >= m) { // a tiny negative r rounds up to exactly m
                r = 0;
            }
        }
        return r + 0.0; // + 0.0 turns -0.0 into 0.0
    }

    /** Number of world copies to draw. */
    public int offsetCount() { return offN; }

    /** World x offset of copy {@code k}. */
    public double offsetX(int k) { return offX[k]; }

    /** World y offset of copy {@code k}. */
    public double offsetY(int k) { return offY[k]; }

    /** Screen x of world x in copy {@code k}. */
    public float screenX(double wx, int k) {
        return (float) ((wx + offX[k] - cx) * scale() + viewW * 0.5);
    }

    /** Screen y of world y in copy {@code k}. */
    public float screenY(double wy, int k) {
        return (float) ((wy + offY[k] - cy) * scale() + viewH * 0.5);
    }

    /** World x at screen x (copy 0, no wrap reduction). */
    public double worldX(double screenX) {
        return (screenX - viewW * 0.5) / scale() + cx;
    }

    /** World y at screen y (copy 0, no wrap reduction). */
    public double worldY(double screenY) {
        return (screenY - viewH * 0.5) / scale() + cy;
    }

    /**
     * True if the world rectangle {@code [x0,x1] x [y0,y1]} of copy {@code k}, grown by
     * {@code marginPx} screen pixels on every side, overlaps the viewport. Touching counts.
     */
    public boolean isVisible(double x0, double y0, double x1, double y1, int k, double marginPx) {
        double s = scale();
        double left = (x0 + offX[k] - cx) * s + viewW * 0.5;
        double right = (x1 + offX[k] - cx) * s + viewW * 0.5;
        double bottom = (y0 + offY[k] - cy) * s + viewH * 0.5;
        double top = (y1 + offY[k] - cy) * s + viewH * 0.5;
        return right >= -marginPx && left <= viewW + marginPx
            && top >= -marginPx && bottom <= viewH + marginPx;
    }

    /** Make {@code b} write world coordinates of copy {@code k} as screen pixels. */
    public void applyTo(VertexBatch b, int k) {
        float s = (float) scale();
        b.setTransform(s, s,
            (float) ((offX[k] - cx) * scale() + viewW * 0.5),
            (float) ((offY[k] - cy) * scale() + viewH * 0.5));
    }

    /**
     * Shader transform for world-coordinate geometry of copy {@code k}: writes
     * {@code (sx, sy, tx, ty)} into {@code out} so that {@code ndc = world * (sx,sy) + (tx,ty)}.
     */
    public void worldToNdc(int k, float[] out) {
        double s = scale();
        out[0] = (float) (2 * s / viewW);
        out[1] = (float) (2 * s / viewH);
        out[2] = (float) (((offX[k] - cx) * s + viewW * 0.5) * 2 / viewW - 1);
        out[3] = (float) (((offY[k] - cy) * s + viewH * 0.5) * 2 / viewH - 1);
    }

    /** Shader transform for screen-pixel geometry (see {@link #worldToNdc}). */
    public void screenToNdc(float[] out) {
        out[0] = 2f / viewW;
        out[1] = 2f / viewH;
        out[2] = -1;
        out[3] = -1;
    }
}
