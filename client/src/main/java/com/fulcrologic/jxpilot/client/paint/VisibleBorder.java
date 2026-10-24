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
import com.fulcrologic.jxpilot.client.render.WinScale;

/**
 * The border of the area the server sends objects for (M8; C {@code paintmap.c} {@code Paint_world} with
 * {@code guimap.c} {@code Gui_paint_visible_border}): when the view is wider or taller than the server's
 * largest visible area ({@link #MAX_VIEW_SIZE}, C {@code MAX_VIEW_SIZE}), a {@code hudColor} rectangle of that
 * size centred on the view shows where objects stop. Allocation free.
 */
public final class VisibleBorder {
    /** C {@code MAX_VIEW_SIZE}: the server never sends a wider or taller view. */
    public static final int MAX_VIEW_SIZE = 1024;

    private VisibleBorder() {}

    /** BEHAVIOR: drawn iff the view extent (world pixels) exceeds {@link #MAX_VIEW_SIZE} in either axis. */
    public static boolean shown(int viewExtentW, int viewExtentH) {
        return viewExtentW > MAX_VIEW_SIZE || viewExtentH > MAX_VIEW_SIZE;
    }

    /**
     * C's four {@code Segment_add}s, in window coordinates scaled by {@code WINSCALE} at {@code zoom}.
     * <p>
     * BEHAVIOR: with {@code ew}, {@code eh} the view extent in world pixels (C {@code ext_view_width/height}):
     * x from {@code ws(ew/2 - 512)} to {@code ws(ew/2 + 512)}, y from {@code ws(eh - eh/2 - 512)} to
     * {@code ws(eh - eh/2 + 512)} (C {@code X()}/{@code Y()} of the box corners, integer halves); left, right,
     * bottom then top line, through pixel centres; nothing unless {@link #shown}.
     */
    public static void paint(VertexBatch b, ScreenSpace ss, int ew, int eh, double zoom, int rgba) {
        if (!shown(ew, eh)) {
            return;
        }
        int half = MAX_VIEW_SIZE / 2;
        int x0 = WinScale.of(ew / 2 - half, zoom);
        int x1 = WinScale.of(ew / 2 + half, zoom);
        int yBottom = WinScale.of(eh - eh / 2 + half, zoom);
        int yTop = WinScale.of(eh - eh / 2 - half, zoom);
        HudPainter.seg(b, ss, x0, yBottom, x0, yTop, rgba);
        HudPainter.seg(b, ss, x1, yBottom, x1, yTop, rgba);
        HudPainter.seg(b, ss, x0, yBottom, x1, yBottom, rgba);
        HudPainter.seg(b, ss, x0, yTop, x1, yTop, rgba);
    }
}
