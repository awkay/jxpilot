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

import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.VertexBatch;

/**
 * Sparks and debris: XPilot's temperature colouring and the 2-pixel spark square.
 * <p>
 * In C the server picks a colour level from the particle's remaining life ({@code frame.c}
 * {@code Frame_shots}: hotter, i.e. younger, debris gets a higher level) and the client maps the level
 * to a palette index through {@code sparkColors} ({@code paintobjects.c} {@code DEBRIS_COLOR},
 * {@code guiobjects.c} {@code Gui_paint_spark}). Here both steps run on the client, which simulates
 * the particles itself. Default: 4 levels, palette 5, 6, 7, 3 (dark red to red).
 */
public final class Particles {
    private Particles() {}

    /**
     * Colour level of a particle with {@code life} frames left (C server, {@code debris_colors =
     * levels}).
     * <p>
     * BEHAVIOR: with more than 4 levels: blue-owned {@code life >> 1}, else {@code life >> 2}; with 3 or
     * 4 levels: blue {@code life >> 2}, else {@code life >> 3}; capped at {@code levels - 1}.
     * BEHAVIOR: with fewer than 3 levels the particle keeps colour level 0 (C sends the object's own
     * colour, which this client has no use for); negative life is level 0.
     */
    public static int level(int life, boolean blueOwner, int levels) {
        if (levels < 3 || life <= 0) {
            return 0;
        }
        int c;
        if (levels > 4) {
            c = blueOwner ? life >> 1 : life >> 2;
        } else {
            c = blueOwner ? life >> 2 : life >> 3;
        }
        return Math.min(c, levels - 1);
    }

    /**
     * Palette index for a colour level (C {@code spark_color[DEBRIS_COLOR(level)]}).
     * <p>
     * BEHAVIOR: with more than 4 spark colours C interleaves: index {@code ((level & 1) << 2) | (level >> 1)}
     * into {@code sparkColors}; otherwise the level itself.
     */
    public static int colorIndex(int level, int[] sparkColors) {
        int i = sparkColors.length > 4 ? ((level & 1) << 2) | (level >> 1) : level;
        return sparkColors[i];
    }

    /** Default-config palette index for a particle (4 levels, {@link PaintConfig#SPARK_COLORS}). */
    public static int colorIndex(int life, boolean blueOwner) {
        return colorIndex(level(life, blueOwner, PaintConfig.SPARK_COLORS.length), PaintConfig.SPARK_COLORS);
    }

    /**
     * A spark (C {@code Gui_paint_spark}): a {@link PaintConfig#SPARK_SIZE}-pixel square from
     * {@code -size/2}, i.e. centred half a pixel left of and above the particle.
     */
    public static void spark(VertexBatch b, Palette p, float x, float y, int colorIndex, float px) {
        b.point(x - 0.5f * px, y + 0.5f * px, PaintConfig.SPARK_SIZE, p.color(colorIndex));
    }
}
