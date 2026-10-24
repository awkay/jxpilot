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
 * Space for baked fonts in the UI atlas: rectangular regions, each filled top-down with full-width strips
 * (one strip per baked font, as tall as the rows the bake used). Pure bookkeeping; the GL atlas bakes into a
 * strip and uploads it.
 */
public final class FontStrips {
    private final int[] rx;
    private final int[] ry;
    private final int[] rw;
    private final int[] rh;
    private final int[] used;

    /** @param regions x, y, width, height of each region, in the order they are filled */
    public FontStrips(int... regions) {
        int n = regions.length / 4;
        rx = new int[n];
        ry = new int[n];
        rw = new int[n];
        rh = new int[n];
        used = new int[n];
        for (int i = 0; i < n; i++) {
            rx[i] = regions[4 * i];
            ry[i] = regions[4 * i + 1];
            rw[i] = regions[4 * i + 2];
            rh[i] = regions[4 * i + 3];
        }
    }

    /** BEHAVIOR: empties every region (a rebake starts over). */
    public void reset() {
        java.util.Arrays.fill(used, 0);
    }

    public int regions() { return rx.length; }

    /** Left edge of region {@code r}'s strips. */
    public int x(int r) { return rx[r]; }

    /** Width of region {@code r}'s strips. */
    public int width(int r) { return rw[r]; }

    /** BEHAVIOR: the top row of the next strip in region {@code r}: its top plus the rows taken. */
    public int nextY(int r) { return ry[r] + used[r]; }

    /** BEHAVIOR: rows still free in region {@code r}. */
    public int remaining(int r) { return rh[r] - used[r]; }

    /**
     * Take the next {@code rows} rows of region {@code r}.
     * <p>
     * BEHAVIOR: moves {@link #nextY} down by {@code rows}; more rows than remain is an error and takes nothing.
     */
    public void take(int r, int rows) {
        if (rows > remaining(r)) {
            throw new IllegalArgumentException(rows + " rows do not fit in region " + r + " (" + remaining(r) + " left)");
        }
        used[r] += rows;
    }
}
