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
 * C {@code Paint_score_background} with {@code blockBitmaps} ({@code texturedObjects}): the score window shows the
 * clouds picture ({@link BlockBitmap#SCORE_BG}) from its top and the logo ({@link BlockBitmap#LOGO}) at its bottom,
 * both scaled to the window's width, instead of the plain window colour. The two pictures live in their own texture,
 * the clouds above the logo ({@link #TEXTURE_HEIGHT} rows of {@link #TEXTURE_WIDTH}).
 */
public final class ScoreBackground {
    public static final int TEXTURE_WIDTH = 256;
    public static final int TEXTURE_HEIGHT = BlockBitmap.SCORE_BG.height + BlockBitmap.LOGO.height;

    private ScoreBackground() {}

    /** BEHAVIOR: the texture's pixels: the clouds rows, then the logo rows (both 256 wide). */
    public static int[] texture(int[] clouds, int[] logo) {
        int[] t = new int[TEXTURE_WIDTH * TEXTURE_HEIGHT];
        System.arraycopy(clouds, 0, t, 0, clouds.length);
        System.arraycopy(logo, 0, t, clouds.length, logo.length);
        return t;
    }

    /**
     * The two pictures in window coordinates (the window at (0, {@code top}), {@code width} x {@code height}).
     * <p>
     * BEHAVIOR: the clouds over (0, top) .. (width, top + 442), then the logo over (0, top + height - 223) .. (width,
     * top + height), as two textured quads sampling this class's texture; nothing for an empty window.
     */
    public static void draw(VertexBatch b, ScreenSpace ss, int top, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float bgH = BlockBitmap.SCORE_BG.height;
        float logoH = BlockBitmap.LOGO.height;
        float split = bgH / TEXTURE_HEIGHT;
        b.texRect(ss.edgeX(0), ss.edgeY(top + bgH), ss.edgeX(width), ss.edgeY(top), 0, split, 1, 0, -1);
        b.texRect(ss.edgeX(0), ss.edgeY(top + height), ss.edgeX(width), ss.edgeY(top + height - logoH), 0, 1, 1,
            split, -1);
    }
}
