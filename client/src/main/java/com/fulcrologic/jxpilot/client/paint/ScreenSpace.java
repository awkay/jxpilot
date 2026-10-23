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

/**
 * Maps the C client's window coordinates (logical pixels, origin top-left, y down; X11 drawing
 * addresses whole pixels) to framebuffer coordinates (origin bottom-left, y up, {@code ratio}
 * framebuffer pixels per logical pixel). Mutable: set once per frame, no allocation.
 * <p>
 * X11 lines and points cover the pixels they address, so line ends and point centres map to pixel
 * centres ({@link #x}, {@link #y}); filled rectangles cover {@code [x, x+w) x [y, y+h)}, so their
 * corners map to pixel edges ({@link #edgeX}, {@link #edgeY}).
 */
public final class ScreenSpace {
    private double ratio = 1;
    private double fbHeight = 1;

    /** Set the framebuffer height (pixels) and the framebuffer-per-logical pixel ratio. */
    public void set(int framebufferHeight, double pixelRatio) {
        this.fbHeight = framebufferHeight;
        this.ratio = pixelRatio;
    }

    public double ratio() { return ratio; }

    /** Logical view width for a framebuffer width (C {@code ext_view_width}). */
    public int logicalWidth(int framebufferWidth) {
        return (int) (framebufferWidth / ratio);
    }

    /** Logical view height (C {@code ext_view_height}). */
    public int logicalHeight() {
        return (int) (fbHeight / ratio);
    }

    /** Framebuffer x of the centre of pixel column {@code cx}. */
    public float x(double cx) {
        return (float) ((cx + 0.5) * ratio);
    }

    /** Framebuffer y of the centre of pixel row {@code cy}. */
    public float y(double cy) {
        return (float) (fbHeight - (cy + 0.5) * ratio);
    }

    /** Framebuffer x of the left edge of pixel column {@code cx}. */
    public float edgeX(double cx) {
        return (float) (cx * ratio);
    }

    /** Framebuffer y of the top edge of pixel row {@code cy}. */
    public float edgeY(double cy) {
        return (float) (fbHeight - cy * ratio);
    }

    /** A length in logical pixels, in framebuffer pixels. */
    public float len(double logical) {
        return (float) (logical * ratio);
    }

    /**
     * Framebuffer baseline for C text drawn at {@code (x, cy)}: X11 puts glyph bodies on the rows
     * above row {@code cy} (descenders from {@code cy} down), so the baseline is row {@code cy}'s top edge.
     */
    public float baseline(double cy) {
        return edgeY(cy);
    }
}
