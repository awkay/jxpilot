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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.game.options.ClientOption;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.paint.PaintStyle;
import com.fulcrologic.jxpilot.client.paint.Particles;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.XColors;

import java.io.PrintStream;

import static com.fulcrologic.jxpilot.client.game.options.ClientOption.*;

/**
 * Pushes the C client's look options from {@link ClientOptions} into the {@link Palette} and the shared
 * {@link PaintStyle} whenever the options change (the CONFIG panel changes them live), as the C client's
 * {@code Parse_options}, {@code Colors_init}, {@code Init_spark_colors} and {@code Init_playing_windows} set
 * its globals:
 * <ul>
 *   <li>{@code maxColors} (4, 8 or 16), then {@code color0..15} (with {@code black}, {@code white},
 *   {@code blue}, {@code red} as aliases of 0..3), entries from {@code maxColors} on repeating the first ones;</li>
 *   <li>the colour indices, validated as C does;</li>
 *   <li>{@code sparkColors}, {@code sparkProb}, {@code sparkSize}, {@code shotSize}, {@code showNastyShots},
 *   {@code markingLights}, {@code showShipName};</li>
 *   <li>the world and decor looks and the background dots, bumping {@link PaintStyle#mapLook} when one of
 *   them, or a colour they use, changed.</li>
 * </ul>
 * One int compare per frame when nothing changed; applying allocates (it parses text), only on a change.
 */
public final class LookOptions {
    private final ClientOptions options;
    private final PrintStream log;
    private int applied = -1;
    private final int[] sparks = new int[PaintStyle.MAX_SPARK_COLORS];
    /** The map-look inputs last applied, to bump {@link PaintStyle#mapLook} only on a real change. */
    private long lookKey = Long.MIN_VALUE;
    private final int[] lookColors = new int[16];

    public LookOptions(ClientOptions options, PrintStream log) {
        this.options = options;
        this.log = log;
    }

    public ClientOptions options() { return options; }

    /**
     * BEHAVIOR: when the options' version differs from the last applied one, sets the palette and the style
     * (see the class comment) and returns true; otherwise changes nothing and returns false.
     */
    public boolean apply(Palette palette, PaintStyle style) {
        if (options.version() == applied) {
            return false;
        }
        applied = options.version();
        int max = maxColors(options.integer(MAX_COLORS));
        style.maxColors = max;
        colors(palette, max);
        style.hudColor = validColor(options.integer(HUD_COLOR), max, 1, Palette.BLUE);
        style.hudLockColor = validColor(options.integer(HUD_LOCK_COLOR), max, 1, style.hudColor);
        style.wallColor = validColor(options.integer(WALL_COLOR), max, 1, Palette.BLUE);
        style.wallRadarColor = validColor(options.integer(WALL_RADAR_COLOR), max, 0, Palette.BLUE);
        style.targetRadarColor = validColor(options.integer(TARGET_RADAR_COLOR), max, 0, Palette.BLUE);
        style.oldMessagesColor = validColor(options.integer(OLD_MESSAGES_COLOR), max, 0, Palette.WHITE);
        style.decorColor = validColor(options.integer(DECOR_COLOR), max, 1, Palette.RED);
        style.decorRadarColor = validColor(options.integer(DECOR_RADAR_COLOR), max, 0, 2);
        int n = sparkColors(options.text(SPARK_COLORS), max, sparks);
        style.setSparkColors(sparks, n);
        style.sparkRand = Particles.sparkRand(options.real(SPARK_PROB));
        style.sparkSize = options.integer(SPARK_SIZE);
        style.shotSize = options.integer(SHOT_SIZE);
        style.nastyShots = options.flag(SHOW_NASTY_SHOTS);
        style.shotTracers = options.flag(SHOW_SHOT_TRACERS);
        style.markingLights = options.flag(MARKING_LIGHTS);
        style.showShipName = options.flag(SHOW_SHIP_NAME);
        style.texturedBalls = options.flag(TEXTURED_BALLS);
        style.texturedObjects = options.flag(TEXTURED_OBJECTS);
        style.outlineWorld = options.flag(OUTLINE_WORLD);
        style.filledWorld = options.flag(FILLED_WORLD);
        style.texturedWalls = options.flag(TEXTURED_WALLS);
        style.showDecor = options.flag(SHOW_DECOR);
        style.outlineDecor = options.flag(OUTLINE_DECOR);
        style.filledDecor = options.flag(FILLED_DECOR);
        style.texturedDecor = options.flag(TEXTURED_DECOR);
        style.pointDist = options.integer(BACKGROUND_POINT_DIST);
        style.pointSize = options.integer(BACKGROUND_POINT_SIZE);
        bumpMapLook(palette, style);
        return true;
    }

    private void bumpMapLook(Palette palette, PaintStyle s) {
        long key = bits(s.outlineWorld, 0) | bits(s.filledWorld, 1) | bits(s.texturedWalls, 2) | bits(s.showDecor, 3)
            | bits(s.outlineDecor, 4) | bits(s.filledDecor, 5) | bits(s.texturedDecor, 6)
            | (long) s.pointDist << 8 | (long) s.pointSize << 16 | (long) s.wallColor << 24
            | (long) s.decorColor << 28 | (long) s.wallRadarColor << 32 | (long) s.decorRadarColor << 36
            | (long) s.targetRadarColor << 40 | bits(s.texturedObjects, 44);
        boolean colors = false;
        for (int i = 0; i < lookColors.length; i++) {
            if (lookColors[i] != palette.color(i)) {
                lookColors[i] = palette.color(i);
                colors = true;
            }
        }
        if (key != lookKey || colors) {
            lookKey = key;
            s.mapLook++;
        }
    }

    private static long bits(boolean b, int shift) {
        return b ? 1L << shift : 0;
    }

    /**
     * C {@code Colors_init} on a TrueColor display.
     * <p>
     * BEHAVIOR: 16 or more is 16, 8 to 15 is 8, anything less is 4.
     */
    static int maxColors(int wanted) {
        return wanted >= 16 ? 16 : wanted >= 8 ? 8 : 4;
    }

    /**
     * C {@code Init_playing_windows}' colour index checks.
     * <p>
     * BEHAVIOR: an index below {@code min} (1 for hud, hud lock, wall and decor colours; 0 for the radar and
     * old-message colours) or not below {@code maxColors} is replaced by {@code fallback}.
     */
    static int validColor(int index, int maxColors, int min, int fallback) {
        return index < min || index >= maxColors ? fallback : index;
    }

    /**
     * C {@code default.c} colour names and {@code Parse_colors}, then {@code Colors_init}'s repeat.
     * <p>
     * BEHAVIOR: entry i below {@code maxColors} is {@code color<i>} when given non-blank, else (i &lt; 4) the
     * {@code black}/{@code white}/{@code blue}/{@code red} alias when non-blank, else C's default; a name that
     * does not parse ({@link XColors}) is reported ("Can't parse color i "name"") and the default used.
     * BEHAVIOR: entry i from {@code maxColors} on is entry {@code i % maxColors}.
     */
    void colors(Palette palette, int maxColors) {
        for (int i = 0; i < maxColors; i++) {
            String name = options.text(ClientOption.color(i)).strip();
            if (name.isEmpty() && i < ALIASES.length) {
                name = options.text(ALIASES[i]).strip();
            }
            int c = Palette.defaultColor(i);
            if (!name.isEmpty()) {
                long p = XColors.parse(name);
                if (p == XColors.INVALID) {
                    log.println("Can't parse color " + i + " \"" + name + "\"");
                } else {
                    c = (int) p;
                }
            }
            palette.set(i, c);
        }
        for (int i = maxColors; i < palette.size(); i++) {
            palette.set(i, palette.color(i % maxColors));
        }
    }

    private static final ClientOption[] ALIASES = {BLACK, WHITE, BLUE, RED};

    /**
     * C {@code Init_spark_colors}.
     * <p>
     * BEHAVIOR: every run of decimal digits in {@code spec} is a colour number; numbers below {@code maxColors}
     * are kept, in order, at most {@link PaintStyle#MAX_SPARK_COLORS}; everything else separates.
     * BEHAVIOR: when none is kept: colours 5 up to {@code maxColors - 1} (5 to 11 with 16 colours), then RED.
     *
     * @return the number of colours written to {@code out}
     */
    static int sparkColors(String spec, int maxColors, int[] out) {
        int n = 0;
        int i = 0;
        while (i < spec.length() && n < PaintStyle.MAX_SPARK_COLORS) {
            char ch = spec.charAt(i);
            if (ch < '0' || ch > '9') {
                i++;
                continue;
            }
            long v = 0;
            while (i < spec.length() && spec.charAt(i) >= '0' && spec.charAt(i) <= '9') {
                v = Math.min(Integer.MAX_VALUE, v * 10 + (spec.charAt(i) - '0'));
                i++;
            }
            if (v < maxColors) {
                out[n++] = (int) v;
            }
        }
        if (n == 0) {
            int last = maxColors <= 8 ? maxColors : 12;
            for (int c = 5; c < last; c++) {
                out[n++] = c;
            }
            out[n++] = Palette.RED;
        }
        return n;
    }
}
