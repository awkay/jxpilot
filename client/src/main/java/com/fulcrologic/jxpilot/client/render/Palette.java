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
 * The XPilot 16-entry colour table ({@code src/client/colors.c}, {@code color_names}) plus the
 * default colour-index options from {@code src/client/default.c}.
 * <p>
 * Colours are packed as {@code 0xAABBGGRR}, i.e. the int whose little-endian bytes are
 * {@code R G B A}: the layout {@link VertexBatch} writes for its normalized ubyte4 colour attribute.
 */
public final class Palette {
    /** Colour indices with fixed meaning in the C client. */
    public static final int BLACK = 0;
    public static final int WHITE = 1;
    public static final int BLUE = 2;
    public static final int RED = 3;

    /** {@code wallColor} default. */
    public static final int WALL_COLOR = 2;
    /** {@code wallRadarColor} default. */
    public static final int WALL_RADAR_COLOR = 2;
    /** {@code decorColor} default. */
    public static final int DECOR_COLOR = 6;
    /** {@code decorRadarColor} default. */
    public static final int DECOR_RADAR_COLOR = 6;
    /** {@code hudColor} default. */
    public static final int HUD_COLOR = 4;
    /** {@code targetRadarColor} default. */
    public static final int TARGET_RADAR_COLOR = 4;

    /** {@code color_names} from colors.c, index 0..15. */
    static final String[] DEFAULT_HEX = {
        "#000000", "#FFFFFF", "#4E7CFF", "#FF3A27",
        "#33BB44", "#992200", "#BB7700", "#EE9900",
        "#770000", "#CC4400", "#DD8800", "#FFBB11",
        "#9f9f9f", "#5f5f5f", "#dfdfdf", "#202020"
    };

    private final int[] rgba;

    private Palette(int[] rgba) {
        this.rgba = rgba;
    }

    /** The default XPilot colour table. */
    public static Palette xpilotDefault() {
        int[] c = new int[DEFAULT_HEX.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = parseHex(DEFAULT_HEX[i]);
        }
        return new Palette(c);
    }

    /** Packed colour for a palette index (0..15). */
    public int color(int index) {
        return rgba[index];
    }

    /** Number of entries. */
    public int size() {
        return rgba.length;
    }

    /** Pack 0..255 components as {@code 0xAABBGGRR}. */
    public static int pack(int r, int g, int b, int a) {
        return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
    }

    /**
     * Parse {@code #RRGGBB} as an opaque packed colour.
     * <p>
     * BEHAVIOR: hex digits are case-insensitive; anything other than '#' plus six hex digits throws.
     */
    public static int parseHex(String hex) {
        if (hex.length() != 7 || hex.charAt(0) != '#') {
            throw new IllegalArgumentException("Bad colour: " + hex);
        }
        int v = Integer.parseUnsignedInt(hex.substring(1), 16);
        return pack(v >>> 16, v >>> 8, v, 255);
    }

    /** Same colour with a different alpha (0..255). */
    public static int withAlpha(int rgba, int alpha) {
        return (rgba & 0x00FFFFFF) | (alpha & 0xFF) << 24;
    }
}
