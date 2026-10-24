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

/**
 * The C client's look options as the painters read them, live: one mutable instance shared by the painters
 * and filled by {@code app.LookOptions} whenever the options change (C reads its globals in the same way).
 * A new instance holds C's defaults ({@code default.c}), so painters built without one look like stock C.
 * Reads are field reads (allocation free).
 * <p>
 * The colour fields are palette indices after C's {@code Init_playing_windows} validation; the world and decor
 * looks and the background dots feed the static map mesh, so a change of them bumps {@link #mapLook}.
 */
public final class PaintStyle {
    /** Most spark colours (C {@code Init_spark_colors}: "protocol will allow max 9"). */
    public static final int MAX_SPARK_COLORS = 9;

    /** {@code shotSize}: own/enemy shot square side (C {@code shot_size}). */
    public int shotSize = PaintConfig.SHOT_SIZE;
    /** C {@code spark_rand} of {@code sparkProb} ({@link Particles#sparkRand}). */
    public int sparkRand = Particles.sparkRand(PaintConfig.SPARK_PROB);
    /** {@code sparkSize}. */
    public int sparkSize = PaintConfig.SPARK_SIZE;
    /** {@code showNastyShots}: fast shots as an X or + instead of a square (C {@code Gui_paint_fastshot}). */
    public boolean nastyShots;
    /** {@code markingLights}. */
    public boolean markingLights = true;
    /** {@code showShipName}. */
    public boolean showShipName = true;
    /** {@code texturedBalls}, and a ball texture was loaded. */
    public boolean texturedBalls;
    /** C {@code maxColors} after {@code Colors_init}: 4, 8 or 16. */
    public int maxColors = 8;

    public int hudColor = PaintConfig.HUD_COLOR;
    public int hudLockColor = PaintConfig.HUD_LOCK_COLOR;
    public int wallColor = Palette.WALL_COLOR;
    public int wallRadarColor = Palette.WALL_RADAR_COLOR;
    public int decorColor = Palette.DECOR_COLOR;
    public int decorRadarColor = Palette.DECOR_RADAR_COLOR;
    public int targetRadarColor = Palette.TARGET_RADAR_COLOR;
    public int oldMessagesColor = PaintConfig.OLD_MESSAGES_COLOR;

    /** {@code outlineWorld}, {@code filledWorld}, {@code texturedWalls}. */
    public boolean outlineWorld;
    public boolean filledWorld;
    public boolean texturedWalls;
    /** {@code showDecor}, {@code outlineDecor}, {@code filledDecor}, {@code texturedDecor}. */
    public boolean showDecor = true;
    public boolean outlineDecor;
    public boolean filledDecor;
    public boolean texturedDecor;
    /** {@code backgroundPointDist} (blocks; 0 = only the wrap row and column) and {@code backgroundPointSize}. */
    public int pointDist = 8;
    public int pointSize = 2;
    /** Bumped whenever an input of the static map mesh or radar picture changes (looks, dots, colours). */
    public int mapLook;

    private final int[] sparkColors = new int[MAX_SPARK_COLORS];
    private int sparkCount;

    public PaintStyle() {
        setSparkColors(PaintConfig.SPARK_COLORS, PaintConfig.SPARK_COLORS.length);
    }

    /** BEHAVIOR: takes the first {@code n} (1..9) palette indices of {@code colors} as the spark colours. */
    public void setSparkColors(int[] colors, int n) {
        System.arraycopy(colors, 0, sparkColors, 0, n);
        sparkCount = n;
    }

    /** C {@code num_spark_colors}: the number of temperature levels. */
    public int sparkCount() {
        return sparkCount;
    }

    /** Spark colour {@code i} (palette index). */
    public int sparkColor(int i) {
        return sparkColors[i];
    }

    /**
     * Palette index of debris colour level {@code level} (C {@code spark_color[DEBRIS_COLOR(level)]}).
     * <p>
     * BEHAVIOR: with more than 4 spark colours C interleaves the levels ({@code ((level & 1) << 2) | (level >> 1)});
     * otherwise the level itself. An index past the configured colours takes the last one (C pads
     * {@code spark_color} with it); a negative level is level 0.
     */
    public int levelColor(int level) {
        int l = Math.max(0, level);
        int i = sparkCount > 4 ? ((l & 1) << 2) | (l >> 1) : l;
        return sparkColors[Math.min(i, sparkCount - 1)];
    }

    /** C {@code Paint_objects_radar}: friends in colour 4 when {@code maxColors > 4}, else white. */
    public int radarFriendColor() {
        return maxColors > 4 ? PaintConfig.RADAR_FRIEND_COLOR : Palette.WHITE;
    }

    /** C {@code Gui_paint_marking_lights}: the right lights in colour 4 when {@code maxColors > 4}, else blue. */
    public int rightLightColor() {
        return maxColors > 4 ? 4 : Palette.BLUE;
    }

    /** C: walls are filled ({@code filledWorld} or {@code texturedWalls}); then no outlines are drawn. */
    public boolean wallsFilled() {
        return filledWorld || texturedWalls;
    }

    /** C {@code Map_blue}'s outline mode: any of {@code outlineWorld}, {@code filledWorld}, {@code texturedWalls}. */
    public boolean wallsOutlined() {
        return outlineWorld || filledWorld || texturedWalls;
    }

    /** C {@code Gui_paint_decor}: decorations are filled ({@code filledDecor} or {@code texturedDecor}). */
    public boolean decorFilled() {
        return filledDecor || texturedDecor;
    }
}
