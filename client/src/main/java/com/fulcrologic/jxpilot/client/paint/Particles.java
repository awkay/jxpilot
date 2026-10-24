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
import com.fulcrologic.jxpilot.client.render.WinScale;

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
     * Palette index for a particle with the live spark colours ({@code sparkColors}: as many temperature levels
     * as colours, {@link PaintStyle#levelColor}).
     */
    public static int colorIndex(int life, boolean blueOwner, PaintStyle style) {
        return style.levelColor(level(life, blueOwner, style.sparkCount()));
    }

    /**
     * A spark (C {@code Gui_paint_spark}): {@code Rectangle_add} of a {@link PaintConfig#SPARK_SIZE}
     * square from {@code -size/2}, scaled ({@link WinScale}); at zoom 1 centred half a pixel left of
     * and above the particle.
     */
    public static void spark(VertexBatch b, Palette p, float x, float y, int colorIndex, double zoom) {
        spark(b, p, x, y, colorIndex, PaintConfig.SPARK_SIZE, zoom);
    }

    /** {@link #spark} with C's {@code sparkSize} {@code size}: a {@code size} square from {@code -size/2}, scaled. */
    public static void spark(VertexBatch b, Palette p, float x, float y, int colorIndex, int size, double zoom) {
        int z = -(size / 2);
        b.point(ObjectPainter.squareCentreX(x, z, size, zoom), ObjectPainter.squareCentreY(y, z, size, zoom),
            WinScale.of(size, zoom), p.color(colorIndex));
    }

    /**
     * C {@code spark_rand} for a {@code sparkProb}: {@code (int)(prob * MAX_SPARK_RAND + 0.5)} with
     * {@code MAX_SPARK_RAND} = 128 ({@code default.c}, {@code draw.h}).
     * <p>
     * BEHAVIOR: the probability is limited to [0, 1] first, so the result is 0..128.
     */
    public static int sparkRand(double prob) {
        double q = Math.max(0, Math.min(1, prob));
        return (int) (q * MAX_SPARK_RAND + 0.5);
    }

    /** C {@code MAX_SPARK_RAND}: a particle is always shown at this {@code spark_rand}. */
    public static final int MAX_SPARK_RAND = 0x80;

    /**
     * The sparkle (C server {@code Frame_shots}: a spark or debris particle is not sent when
     * {@code (fuzz & 0x7F) >= spark_rand}): whether particle {@code i} shows at frame {@code loops},
     * from a hash of the two in place of C's random {@code fuzz} bits, so it is deterministic and needs
     * no shared random state.
     * <p>
     * BEHAVIOR: shown iff 7 hash bits of (i, loops) are below {@code sparkRand}: always at 128, never at 0.
     */
    public static boolean sparkShown(int i, long loops, int sparkRand) {
        int h = (int) (i * 0x632BE5ABL ^ loops * 0x9E3779B97F4A7C15L);
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        h ^= h >>> 16;
        return (h & 0x7F) < sparkRand;
    }
}
