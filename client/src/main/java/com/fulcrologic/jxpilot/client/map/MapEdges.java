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
package com.fulcrologic.jxpilot.client.map;

import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.WallGeometry;
import com.fulcrologic.jxpilot.common.map.WorldMap;

/**
 * Wall and decoration outlines exactly as the C client marks them ({@code Map_blue} in
 * {@code client.c}, {@code Gui_paint_walls} / {@code Gui_paint_decor} in {@code guimap.c}).
 * <p>
 * Per block, a shape side (see {@link WallGeometry#sides}) is drawn:
 * <ul>
 *   <li>left / down: unless the neighbour on that side has the facing side (right / up);</li>
 *   <li>right / up: always in XPilot's default grid look; with {@code outline} (C
 *       {@code outlineWorld}/{@code filledWorld}, {@code outlineDecor}) only unless the neighbour has
 *       the facing side.</li>
 * </ul>
 * Only blocks of the same family count as neighbours (walls: FILLED, FUEL, REC_*; decor: DECOR_*).
 * Past a non-wrapping world edge there is no neighbour; with edge wrap the opposite edge's block is.
 * Diagonal blocks also get their diagonal. Segments are emitted per block, rows bottom-up and x
 * ascending, in C order: left, down, right, up, diagonal.
 */
public final class MapEdges {
    private MapEdges() {}

    private static final float S = Const.BLOCK_SZ;

    /** Wall outlines (FILLED, FUEL, REC_*) in world coordinates. */
    public static void walls(WorldMap m, boolean outline, VertexBatch b, int rgba) {
        edges(m, outline, false, b, rgba);
    }

    /** Decoration outlines (DECOR_*) in world coordinates. */
    public static void decor(WorldMap m, boolean outline, VertexBatch b, int rgba) {
        edges(m, outline, true, b, rgba);
    }

    /** The four world border lines (C {@code Gui_paint_border}), only for a non-wrapping world. */
    public static void border(WorldMap m, VertexBatch b, int rgba) {
        if (m.edgeWrap()) {
            return;
        }
        float w = m.pixelWidth();
        float h = m.pixelHeight();
        b.line(0, 0, 0, h, rgba);
        b.line(w, 0, w, h, rgba);
        b.line(0, 0, w, 0, rgba);
        b.line(0, h, w, h, rgba);
    }

    /** Side bits of a block for one family, 0 for blocks outside the family. */
    static int familySides(int t, boolean decor) {
        boolean member = decor ? BlockType.isDecor(t) : BlockType.isWallOutline(t);
        return member ? WallGeometry.sides(t) : 0;
    }

    /** Side bits of the neighbour block at possibly out-of-range coordinates (0 past a hard edge). */
    static int neighbour(WorldMap m, int bx, int by, boolean decor) {
        if (!m.edgeWrap() && (bx < 0 || bx >= m.width() || by < 0 || by >= m.height())) {
            return 0;
        }
        return familySides(m.blockAt(bx, by), decor);
    }

    private static void edges(WorldMap m, boolean outline, boolean decor, VertexBatch b, int rgba) {
        for (int by = 0; by < m.height(); by++) {
            for (int bx = 0; bx < m.width(); bx++) {
                int t = m.block(bx, by);
                int s = familySides(t, decor);
                if (s == 0) {
                    continue;
                }
                float x0 = bx * S;
                float y0 = by * S;
                float x1 = x0 + S;
                float y1 = y0 + S;
                if ((s & WallGeometry.SIDE_LEFT) != 0
                    && (neighbour(m, bx - 1, by, decor) & WallGeometry.SIDE_RIGHT) == 0) {
                    b.line(x0, y0, x0, y1, rgba);
                }
                if ((s & WallGeometry.SIDE_DOWN) != 0
                    && (neighbour(m, bx, by - 1, decor) & WallGeometry.SIDE_UP) == 0) {
                    b.line(x0, y0, x1, y0, rgba);
                }
                if ((s & WallGeometry.SIDE_RIGHT) != 0
                    && (!outline || (neighbour(m, bx + 1, by, decor) & WallGeometry.SIDE_LEFT) == 0)) {
                    b.line(x1, y0, x1, y1, rgba);
                }
                if ((s & WallGeometry.SIDE_UP) != 0
                    && (!outline || (neighbour(m, bx, by + 1, decor) & WallGeometry.SIDE_DOWN) == 0)) {
                    b.line(x0, y1, x1, y1, rgba);
                }
                int shape = BlockType.decorShape(t);
                if (shape == BlockType.REC_LU || shape == BlockType.REC_RD) {
                    b.line(x0, y0, x1, y1, rgba);
                } else if (shape == BlockType.REC_RU || shape == BlockType.REC_LD) {
                    b.line(x0, y1, x1, y0, rgba);
                }
            }
        }
    }
}
