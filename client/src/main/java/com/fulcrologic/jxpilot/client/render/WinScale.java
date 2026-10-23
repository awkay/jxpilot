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
 * The C client's window scaling ({@code paint.h} {@code WINSCALE}, {@code paintdata.c}
 * {@code Init_scale_array}, enabled by {@code Local.config} {@code WindowScaling}), with the zoom as the
 * scale (C's {@code 1 / scaleFactor}).
 * <p>
 * Positions scale continuously through the camera transform; the C pixel sizes that the client scales
 * (shot, spark and marking-light squares, team-shot and light-cross arms, background dots, mines, dash
 * lengths, the name offsets and the HUD geometry) are rounded with {@link #of}, which never shrinks a
 * non-zero size below one pixel. Item triangles and symbols, text, and missile/laser line widths are
 * not scaled, as in C.
 */
public final class WinScale {
    private WinScale() {}

    /** Largest scaled value ({@code scaleArray} holds shorts). */
    public static final int MAX = 32767;
    /** C {@code MIN_SCALEFACTOR}, {@code MAX_SCALEFACTOR} ({@code client.h}). */
    public static final double MIN_SCALE_FACTOR = 0.2;
    public static final double MAX_SCALE_FACTOR = 8.0;
    /** C defaults of {@code scaleFactor} and {@code altScaleFactor} ({@code default.c}). */
    public static final double SCALE_FACTOR = 1.0;
    public static final double ALT_SCALE_FACTOR = 2.0;

    /**
     * C {@code WINSCALE(n)} at zoom {@code scale}.
     * <p>
     * BEHAVIOR: 0 stays 0; a positive n becomes {@code floor(n * scale + 0.5)}, at least 1 and at most
     * {@link #MAX}; a negative n is the negation of its magnitude's value.
     */
    public static int of(int n, double scale) {
        if (n == 0) {
            return 0;
        }
        int a = n < 0 ? -n : n;
        double v = Math.floor(a * scale + 0.5);
        int r = v < 1 ? 1 : (v > MAX ? MAX : (int) v);
        return n < 0 ? -r : r;
    }

    /**
     * Where the centre of a scaled C square moves, in scaled pixels, relative to where it sits at zoom
     * 1 scaled continuously: a C {@code Rectangle_add} square of side {@code size} with its corner at a
     * world offset covers {@code of(size)} pixels from that corner, so its centre is
     * {@code (of(size) - 1) / 2} pixels from the corner pixel, against {@code (size - 1) / 2 * scale}.
     * Positive is right (x) and down the screen (y).
     * <p>
     * BEHAVIOR: 0 at scale 1; {@code ((of(size, scale) - 1) - (size - 1) * scale) / 2} otherwise.
     */
    public static double squareShift(int size, double scale) {
        return ((of(size, scale) - 1) - (size - 1) * scale) / 2;
    }

    /**
     * Zoom for a C {@code scaleFactor}: {@code 1 / scaleFactor} after C's limits
     * ({@link #MIN_SCALE_FACTOR}..{@link #MAX_SCALE_FACTOR}; 0 means 1, as in {@code default.c}).
     */
    public static double zoomOf(double scaleFactor) {
        double f = scaleFactor == 0 ? 1 : scaleFactor;
        f = Math.max(MIN_SCALE_FACTOR, Math.min(MAX_SCALE_FACTOR, f));
        return 1 / f;
    }
}
