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
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.client.render.WinScale;
import com.fulcrologic.jxpilot.common.math.ShipShape;

/**
 * Ships as the C client draws them ({@code guiobjects.c} {@code Gui_paint_ship} and helpers).
 * <p>
 * Shapes are written in world coordinates (y up) through the batch transform. {@code zoom} is the
 * camera zoom; the pixel sizes C scales by {@code WINSCALE} (dash lengths and offset, marking-light
 * squares and arms, the name offset) are {@link WinScale#of} logical pixels, i.e.
 * {@code WinScale.of(n, zoom) / zoom} world units, and C's pixel offsets from a position are world
 * offsets (window pixels are world pixels at scale 1). The hull is the ship's outline points rotated
 * by the heading (radians, counter-clockwise from +x) about the ship's position.
 * <p>
 * Colour indices: {@link #shipColor}. Flag bits match {@code common.net.Records.ShipFlags}.
 * Allocation-free (scratch arrays per instance; not thread-safe).
 */
public final class ShipPainter {
    /** Shield up. */
    public static final int SHIELD = 1;
    /** Cloaked: hull drawn dashed. */
    public static final int CLOAKED = 1 << 2;
    /** Phasing: hull drawn dashed. */
    public static final int PHASING = 1 << 3;
    /** Emergency shield. */
    public static final int EMERGENCY_SHIELD = 1 << 8;
    /** Deflector. */
    public static final int DEFLECTOR = 1 << 9;

    /** Chords per shield circle. */
    static final int CIRCLE_SEGMENTS = 32;
    /** Name label: C draws the baseline at {@code WINSCALE(Y(y) + 16) + ascent}. */
    static final int NAME_OFFSET = 16;
    /** C {@code FIND_NAME_WIDTH}: a name is 2 pixels wider than its text. */
    static final int NAME_PAD = 2;
    /** Marking light: a 6x6 square from (-2,-2) and a 16-pixel cross (C screen pixels). */
    static final int LIGHT_SQUARE = 6;
    static final int LIGHT_ARM = 8;
    /** Right marking light colour when the display has more than 4 colours. */
    static final int RIGHT_LIGHT_COLOR = 4;

    private final Palette palette;
    private final float[] xs = new float[Math.max(ShipShape.MAX_SHIP_PTS, CIRCLE_SEGMENTS)];
    private final float[] ys = new float[Math.max(ShipShape.MAX_SHIP_PTS, CIRCLE_SEGMENTS)];

    public ShipPainter(Palette palette) {
        this.palette = palette;
    }

    /**
     * C {@code Gui_calculate_ship_color} (without the useErase lock outline): blue for a ship of the
     * viewer's team (in team play), alliance or own tank; otherwise white, or red while a round is
     * about to start.
     * <p>
     * BEHAVIOR: the viewer's own ship is never blue (C checks {@code self->id != id}); friendly beats
     * the round-delay red.
     */
    public static int shipColor(boolean friendly, boolean self, boolean roundDelay) {
        if (friendly && !self) {
            return Palette.BLUE;
        }
        return roundDelay ? Palette.RED : Palette.WHITE;
    }

    /**
     * Rotated outline points into the scratch arrays; returns the point count.
     * <p>
     * BEHAVIOR: point i is {@code (x, y) + R(heading) * shape.pt[i]}.
     */
    int outline(ShipShape s, float x, float y, double heading) {
        double c = Math.cos(heading);
        double sn = Math.sin(heading);
        int n = s.numPoints();
        for (int i = 0; i < n; i++) {
            xs[i] = x + (float) ShipShape.rotX(s.ptX[i], s.ptY[i], c, sn);
            ys[i] = y + (float) ShipShape.rotY(s.ptX[i], s.ptY[i], c, sn);
        }
        return n;
    }

    /** Ship dash offset at frame {@code loops}, unscaled ({@code DASHES_LENGTH - loops % DASHES_LENGTH}). */
    public static int dashOffset(long loops) {
        return PaintConfig.DASHES_LENGTH - (int) (loops % PaintConfig.DASHES_LENGTH);
    }

    /**
     * Ship dash offset at frame {@code loops} and zoom (C {@code Gui_paint_ships_begin}:
     * {@code WINSCALE(DASHES_LENGTH - loops % DASHES_LENGTH)}).
     */
    public static int dashOffset(long loops, double zoom) {
        return WinScale.of(dashOffset(loops), zoom);
    }

    /**
     * The ship's lines (C {@code Gui_paint_ship} from the colour choice on): solid hull unless cloaked
     * or phasing, dashed hull if either, then shields and deflector.
     * <p>
     * BEHAVIOR: solid hull iff neither {@link #CLOAKED} nor {@link #PHASING}; dashed hull iff either,
     * with scaled {8, 4} dashes at the scaled ship dash offset.
     * BEHAVIOR: shield/deflector circles follow {@link #shields}.
     */
    public void ship(VertexBatch b, ShipShape s, float x, float y, double heading, int colorIndex, int flags,
                     long loops, double zoom) {
        int n = outline(s, x, y, heading);
        int rgba = palette.color(colorIndex);
        boolean hidden = (flags & (CLOAKED | PHASING)) != 0;
        if (!hidden) {
            for (int i = 0; i < n; i++) {
                int j = i + 1 == n ? 0 : i + 1;
                b.line(xs[i], ys[i], xs[j], ys[j], rgba);
            }
        } else {
            Dashes.polyline(b, xs, ys, n, true, WinScale.of(PaintConfig.DASH_ON, zoom),
                WinScale.of(PaintConfig.DASH_OFF, zoom), dashOffset(loops, zoom), (float) (1 / zoom), rgba);
        }
        shields(b, s, x, y, colorIndex, flags, loops, zoom);
    }

    /**
     * C {@code Gui_paint_shields_deflectors}: dashed circles of diameter {@code shieldRadius}
     * (shield) and {@code shieldRadius + 4} (outer: deflector or emergency shield), world sizes, with
     * the ship's scaled dashes.
     * <p>
     * BEHAVIOR: shield alone: inner circle in the ship colour. Deflector alone: outer circle red when
     * {@code loops & 2}, else blue.
     * BEHAVIOR: emergency shield with shield: with a deflector the inner circle takes the deflector colour
     * and the outer the ship colour; without one both are in the ship colour. Emergency shield without
     * shield draws nothing extra.
     */
    void shields(VertexBatch b, ShipShape s, float x, float y, int shipColor, int flags, long loops, double zoom) {
        boolean shield = (flags & SHIELD) != 0;
        boolean deflector = (flags & DEFLECTOR) != 0;
        boolean eshield = (flags & EMERGENCY_SHIELD) != 0;
        int scolor = shield ? shipColor : -1;
        int ecolor = deflector ? ((loops & 0x02) != 0 ? Palette.RED : Palette.BLUE) : -1;
        if (eshield && shield) {
            if (ecolor != -1) {
                scolor = ecolor;
                ecolor = shipColor;
            } else {
                scolor = shipColor;
                ecolor = shipColor;
            }
        }
        int offset = dashOffset(loops, zoom);
        int on = WinScale.of(PaintConfig.DASH_ON, zoom);
        int off = WinScale.of(PaintConfig.DASH_OFF, zoom);
        float px = (float) (1 / zoom);
        if (ecolor != -1) {
            Dashes.circle(b, x, y, (s.shieldRadius + 4) / 2f, CIRCLE_SEGMENTS, xs, ys, on, off, offset, px,
                palette.color(ecolor));
        }
        if (scolor != -1) {
            Dashes.circle(b, x, y, s.shieldRadius / 2f, CIRCLE_SEGMENTS, xs, ys, on, off, offset, px,
                palette.color(scolor));
        }
    }

    /**
     * The filled hull C draws over a locked ship (non-useErase {@code fillPolygon}), as a triangle fan
     * from the ship's position (ship outlines are star-shaped about it). C fills only the solid hull
     * ({@code Gui_paint_ship_uncloaked}), so callers skip cloaked or phasing ships ({@link #lockedFillShown}).
     * <p>
     * BEHAVIOR: one triangle per outline edge: (centre, p[i], p[i+1]), wrapping to p[0].
     */
    public void lockedFill(VertexBatch b, ShipShape s, float x, float y, double heading, int colorIndex) {
        int n = outline(s, x, y, heading);
        int rgba = palette.color(colorIndex);
        for (int i = 0; i < n; i++) {
            int j = i + 1 == n ? 0 : i + 1;
            b.triangle(x, y, xs[i], ys[i], xs[j], ys[j], rgba);
        }
    }

    /** C: a locked ship's hull is filled iff it is locked and neither cloaked nor phasing. */
    public static boolean lockedFillShown(boolean locked, int flags) {
        return locked && (flags & (CLOAKED | PHASING)) == 0;
    }

    /**
     * C {@code Gui_paint_marking_lights}: on frames where {@code (loops + id) & 15} is 0 the left
     * lights flash red, where it is 2 the right lights flash in colour 4; each light is a 6x6 square
     * from (-2,-2) screen pixels and a cross of 8-pixel arms, both scaled.
     * <p>
     * BEHAVIOR: other frames draw nothing; squares first (one points command), then the crosses.
     */
    public void markingLights(VertexBatch b, ShipShape s, float x, float y, double heading, int id, long loops,
                              double zoom) {
        markingLightSquares(b, s, x, y, heading, id, loops, zoom);
        markingLightCrosses(b, s, x, y, heading, id, loops, zoom);
    }

    /**
     * The light squares of {@link #markingLights} only (points): C {@code Rectangle_add(X - 2, Y - 2,
     * 6, 6)}, so at zoom 1 the square's centre is half a pixel right of and below the light.
     */
    public void markingLightSquares(VertexBatch b, ShipShape s, float x, float y, double heading, int id,
                                    long loops, double zoom) {
        int phase = lightPhase(id, loops);
        if (phase < 0) {
            return;
        }
        int n = lights(s, x, y, heading, phase);
        int rgba = palette.color(phase == 0 ? Palette.RED : RIGHT_LIGHT_COLOR);
        int side = WinScale.of(LIGHT_SQUARE, zoom);
        for (int i = 0; i < n; i++) {
            b.point(ObjectPainter.squareCentreX(xs[i], -2, LIGHT_SQUARE, zoom),
                ObjectPainter.squareCentreY(ys[i], -2, LIGHT_SQUARE, zoom), side, rgba);
        }
    }

    /** The light crosses of {@link #markingLights} only (segments, arms of {@code WINSCALE(8)} pixels). */
    public void markingLightCrosses(VertexBatch b, ShipShape s, float x, float y, double heading, int id,
                                    long loops, double zoom) {
        int phase = lightPhase(id, loops);
        if (phase < 0) {
            return;
        }
        int n = lights(s, x, y, heading, phase);
        int rgba = palette.color(phase == 0 ? Palette.RED : RIGHT_LIGHT_COLOR);
        float arm = (float) (WinScale.of(LIGHT_ARM, zoom) / zoom);
        for (int i = 0; i < n; i++) {
            b.line(xs[i] - arm, ys[i], xs[i] + arm, ys[i], rgba);
            b.line(xs[i], ys[i] - arm, xs[i], ys[i] + arm, rgba);
        }
    }

    /** 0 for the left-light frame, 2 for the right-light frame, -1 otherwise. */
    static int lightPhase(int id, long loops) {
        int phase = (int) ((loops + id) & 0xF);
        return phase == 0 || phase == 2 ? phase : -1;
    }

    /** Rotated left (phase 0) or right lights into the scratch arrays; returns their count. */
    private int lights(ShipShape s, float x, float y, double heading, int phase) {
        int[] lx = phase == 0 ? s.leftLightX : s.rightLightX;
        int[] ly = phase == 0 ? s.leftLightY : s.rightLightY;
        double c = Math.cos(heading);
        double sn = Math.sin(heading);
        for (int i = 0; i < lx.length; i++) {
            xs[i] = x + (float) ShipShape.rotX(lx[i], ly[i], c, sn);
            ys[i] = y + (float) ShipShape.rotY(lx[i], ly[i], c, sn);
        }
        return lx.length;
    }

    /**
     * C {@code Gui_paint_ship_name}: the name in white at {@code WINSCALE(X(x)) - name_width / 2}, where
     * {@code name_width} is the text width plus {@value #NAME_PAD} ({@code FIND_NAME_WIDTH}, so names sit
     * a pixel left of centre), baseline {@code WINSCALE(}{@value #NAME_OFFSET}{@code )} logical pixels
     * plus the ascent below the ship's screen position ({@code sx}, {@code sy}: framebuffer pixels;
     * {@code ratio} framebuffer pixels per logical pixel; identity transform).
     */
    public void name(VertexBatch b, TextWriter t, float sx, float sy, CharSequence name, double ratio, double zoom) {
        float x = sx - (int) (t.width(name) + NAME_PAD * ratio) / 2;
        float base = sy - (float) (WinScale.of(NAME_OFFSET, zoom) * ratio) - t.font().ascent();
        t.draw(b, name, x, base, palette.color(Palette.WHITE));
    }

    /**
     * C {@code Gui_paint_rounddelay}: seconds until the round starts, white, centred on the ship
     * (baseline half an ascent below its centre). C draws this instead of the ship in the first half
     * of each second.
     */
    public void roundDelay(VertexBatch b, TextWriter t, float sx, float sy, int seconds) {
        float x = sx - (int) t.widthInt(seconds) / 2;
        float base = sy - (int) t.font().ascent() / 2;
        t.drawInt(b, seconds, x, base, palette.color(Palette.WHITE));
    }

    /**
     * C {@code Gui_paint_ship}: the round-delay number replaces the ship (hull, lights, shields; the
     * name is still drawn) while {@code roundDelay > 0 && roundDelay % fps < fps / 2}.
     */
    public static boolean showRoundDelay(int roundDelayFrames, int fps) {
        return roundDelayFrames > 0 && roundDelayFrames % fps < fps / 2;
    }
}
