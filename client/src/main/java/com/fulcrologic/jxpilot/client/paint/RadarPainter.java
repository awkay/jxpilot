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
import com.fulcrologic.jxpilot.common.Const;

/**
 * The moving parts of the radar ({@code paintradar.c}): the own ship's heading line, object blips and
 * the next race checkpoint, drawn over the static radar picture.
 * <p>
 * C order ({@code Paint_radar}): {@link #checkpoint}, the blinking heading line ({@link #self}), then
 * the blips ({@code Paint_objects_radar}), which include the own ship's steady white 3x3 blip
 * ({@link #selfBlip}, added by {@code Handle_ship}); the blip and the line are both white.
 * <p>
 * Blips are the server's radar records ({@code frame.c} {@code Frame_radar}), each with C's size
 * byte: players 3, balls 2, missiles and mines 0 (shown as 1 pixel), nuclear objects blinking 3, and
 * asteroids {@code size + 1} with the 0x80 bit ({@link #asteroidSizeByte}), so on a display with more
 * than 4 colours asteroids show in colour 4 like teammates.
 * <p>
 * Work is in C's radar pixel space: 256 x {@code radarHeight}, origin top-left, y down. Each frame,
 * {@link #begin} gives the radar's framebuffer rectangle (where the radar texture is drawn) and the
 * world size. Lines map pixel centres, filled squares pixel edges. X11 clipped all of this to the
 * radar pixmap; here lines and squares are clipped to the rectangle, and on wrapping worlds the
 * pieces cut off at one edge are repeated at the opposite one, as C does. With {@code slidingRadar}
 * ({@link #slide}) the picture and everything drawn on it are shifted cyclically to centre the own ship.
 * Allocation-free; not thread-safe.
 */
public final class RadarPainter {
    /** Radar width in pixels (C: 256). */
    public static final int WIDTH = 256;
    /** Own heading line length in radar pixels. */
    static final int HEADING_LEN = 8;

    private final Palette palette;
    private final PaintStyle style;
    private float left;
    private float top;
    private float sx = 1;
    private float sy = 1;
    private int height = 1;
    private double worldW = 1;
    private double worldH = 1;
    private boolean wrap;
    private double t0;
    private double t1;
    /** Sliding radar shift (C {@code slidingRadar}): radar pixel p is shown at (p - slide) mod size. */
    private int slideX;
    private int slideY;

    /** With C's default look options. */
    public RadarPainter(Palette palette) {
        this(palette, new PaintStyle());
    }

    /** @param style the live look options ({@code maxColors} picks the friend colour) */
    public RadarPainter(Palette palette, PaintStyle style) {
        this.palette = palette;
        this.style = style;
    }

    /**
     * Set the radar's framebuffer rectangle, its pixel height (C {@code RadarHeight}) and the world
     * size and wrap mode.
     */
    public void begin(float fbLeft, float fbBottom, float fbRight, float fbTop, int radarHeight,
                      double worldWidth, double worldHeight, boolean edgeWrap) {
        left = fbLeft;
        top = fbTop;
        sx = (fbRight - fbLeft) / WIDTH;
        sy = (fbTop - fbBottom) / radarHeight;
        height = radarHeight;
        worldW = worldWidth;
        worldH = worldHeight;
        wrap = edgeWrap;
        slideX = 0;
        slideY = 0;
    }

    /**
     * C {@code slidingRadar} (only on an edge-wrap world): after {@link #begin}, shift the whole radar so the
     * own ship at world (x, y) is at its centre, as C's four {@code XCopyArea}s of the radar pixmap do.
     * <p>
     * BEHAVIOR: with {@code xp = x * 256 / worldWidth}, {@code yp = y * radarHeight / worldHeight} (floats):
     * {@code sx = (int)(xp - 128 + 0.5)}, or {@code (int)(256 + xp - 128 + 0.5)} when {@code xp < 128};
     * {@code sy} likewise about {@code radarHeight / 2}, then {@code radarHeight - sy - 1}; radar pixel p is
     * drawn at {@code (p - s) mod size} on both axes (C's copy quirk leaves a centred ship one row low).
     * BEHAVIOR: on a world without edge wrap nothing moves (C ignores the option there).
     */
    public void slide(double x, double y) {
        if (!wrap) {
            return;
        }
        float xp = (float) (x * WIDTH) / (float) worldW;
        float yp = (float) (y * height) / (float) worldH;
        float xo = WIDTH / 2f;
        float yo = height / 2f;
        int sx = xo <= xp ? (int) (xp - xo + 0.5f) : (int) (WIDTH + xp - xo + 0.5f);
        int sy = yo <= yp ? (int) (yp - yo + 0.5f) : (int) (height + yp - yo + 0.5f);
        slideX = Math.floorMod(sx, WIDTH);
        slideY = Math.floorMod(height - sy - 1, height);
    }

    /** The current sliding shift (0, 0 without sliding). */
    int slideX() { return slideX; }

    int slideY() { return slideY; }

    /**
     * The static radar picture: the texture region (u0, vTop)-(u1, vBottom) over the radar rectangle, tinted
     * {@code rgba}.
     * <p>
     * BEHAVIOR: without a sliding shift one quad; with one, up to four quads that show picture pixel p at
     * {@code (p - slide) mod size}, splitting the texture at the shift.
     */
    public void picture(VertexBatch b, float u0, float vTop, float u1, float vBottom, int rgba) {
        float du = (u1 - u0) / WIDTH;
        float dv = (vBottom - vTop) / height;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                int x0 = i == 0 ? 0 : WIDTH - slideX;
                int x1 = i == 0 ? WIDTH - slideX : WIDTH;
                int y0 = j == 0 ? 0 : height - slideY;
                int y1 = j == 0 ? height - slideY : height;
                if (x0 == x1 || y0 == y1) {
                    continue;
                }
                int srcX = Math.floorMod(x0 + slideX, WIDTH);
                int srcY = Math.floorMod(y0 + slideY, height);
                b.texRect(left + x0 * sx, top - y1 * sy, left + x1 * sx, top - y0 * sy,
                    u0 + srcX * du, vTop + (srcY + y1 - y0) * dv, u0 + (srcX + x1 - x0) * du, vTop + srcY * dv, rgba);
            }
        }
    }

    /** Radar pixel x of world x, rounded (C {@code Handle_ship}, {@code Paint_self_radar}: {@code (int)(x * 256 / width + 0.5)}). */
    int radarX(double wx) {
        return (int) (wx * WIDTH / worldW + 0.5);
    }

    /** Radar pixel row (from the bottom) of world y, rounded, before C's flip. */
    int radarY(double wy) {
        return (int) (wy * height / worldH + 0.5);
    }

    /** Radar pixel x of a server radar record (C fast radar: {@code (256 * (int) x) / World.width}, truncated). */
    int blipX(double wx) {
        return (int) (Math.floor(wx) * WIDTH / worldW);
    }

    /** Radar pixel row (from the bottom) of a server radar record, truncated. */
    int blipY(double wy) {
        return (int) (Math.floor(wy) * height / worldH);
    }

    /** C {@code Frame_radar}'s size byte for an asteroid of size {@code size}: {@code (size + 1) | 0x80}. */
    public static int asteroidSizeByte(int size) {
        return (size + 1) | 0x80;
    }

    /**
     * Own ship (C {@code Paint_self_radar}): a white line from the ship's radar pixel 8 pixels along
     * the heading (radians, y up), shown while {@code loops % 16 < 13}.
     * <p>
     * BEHAVIOR: the start pixel is {@code (radarX, height - radarY - 1)}, moved one radar size
     * forward when not positive; on a wrapping world the line is repeated shifted by the radar size
     * where it leaves the radar.
     */
    public void self(VertexBatch b, double wx, double wy, double heading, long loops) {
        if (loops % 16 >= 13) {
            return;
        }
        int x = radarX(wx);
        int y = height - radarY(wy) - 1;
        if (x <= 0) {
            x += WIDTH;
        }
        if (y <= 0) {
            y += height;
        }
        int x1 = (int) (x + HEADING_LEN * Math.cos(heading));
        int y1 = (int) (y - HEADING_LEN * Math.sin(heading));
        int rgba = palette.color(Palette.WHITE);
        line(b, x, y, x1, y1, rgba);
        if (wrap) {
            int xw = x1 - Math.floorMod(x1 + WIDTH, WIDTH);
            int yw = y1 - Math.floorMod(y1 + height, height);
            if (xw != 0) {
                line(b, x - xw, y, x1 - xw, y1, rgba);
            }
            if (yw != 0) {
                line(b, x, y - yw, x1, y1 - yw, rgba);
                if (xw != 0) {
                    line(b, x - xw, y - yw, x1 - xw, y1 - yw, rgba);
                }
            }
        }
    }

    /**
     * A blip of a server radar record (C {@code Paint_objects_radar}): a filled {@code size}-pixel
     * square (at least 1) around the object's radar pixel, in colour 4 for friends (C's 0x80 bit; white with
     * {@code maxColors} 4) and
     * white otherwise. The radar pixel truncates like the server's fast radar ({@link #blipX}).
     * <p>
     * BEHAVIOR: the square's corner is {@code (rx - s/2, height - ry - 1 - s/2)}, moved one radar size
     * forward when not positive; on a wrapping world a square crossing an edge is repeated at the
     * opposite one.
     */
    public void blip(VertexBatch b, double wx, double wy, int size, boolean friendly) {
        square(b, blipX(wx), blipY(wy), size, friendly);
    }

    /** {@link #blip} from C's size byte: bit 0x80 friend, the rest the size. */
    public void blipByte(VertexBatch b, double wx, double wy, int sizeByte) {
        blip(b, wx, wy, sizeByte & 0x7F, (sizeByte & 0x80) != 0);
    }

    /**
     * The own ship's blip (C {@code Handle_ship} adds {@code Handle_radar(radarx, radary, 3)} for the
     * viewer's ship): a steady white 3x3 square at the rounded radar pixel ({@link #radarX}), drawn
     * like {@link #blip}.
     */
    public void selfBlip(VertexBatch b, double wx, double wy) {
        square(b, radarX(wx), radarY(wy), 3, false);
    }

    private void square(VertexBatch b, int rx, int ry, int size, boolean friendly) {
        int s = size <= 0 ? 1 : size;
        int x = rx - s / 2;
        int y = height - ry - 1 - s / 2;
        if (x <= 0) {
            x += WIDTH;
        }
        if (y <= 0) {
            y += height;
        }
        int rgba = palette.color(friendly ? style.radarFriendColor() : Palette.WHITE);
        rect(b, x, y, s, rgba);
        if (wrap) {
            int xw = x < 0 ? -WIDTH : (x + s >= WIDTH ? WIDTH : 0);
            int yw = y < 0 ? -height : (y + s >= height ? height : 0);
            if (xw != 0) {
                rect(b, x - xw, y, s, rgba);
            }
            if (yw != 0) {
                rect(b, x, y - yw, s, rgba);
                if (xw != 0) {
                    rect(b, x - xw, y - yw, s, rgba);
                }
            }
        }
    }

    /**
     * Next race checkpoint (C {@code Paint_checkpoint_radar}): a white diamond of half-size
     * {@link PaintConfig#RADAR_DIAMOND} above the block's lower-left corner.
     */
    public void checkpoint(VertexBatch b, int bx, int by) {
        int d = PaintConfig.RADAR_DIAMOND;
        int x = (int) (bx * Const.BLOCK_SZ * (WIDTH / worldW) + 0.5);
        int y = height - (int) (by * Const.BLOCK_SZ * (height / worldH) + 0.5) + d - 1;
        if (x <= 0) {
            x += WIDTH;
        }
        if (y <= 0) {
            y += height;
        }
        int rgba = palette.color(Palette.WHITE);
        line(b, x, y, x + d, y - d, rgba);
        line(b, x + d, y - d, x, y - 2 * d, rgba);
        line(b, x, y - 2 * d, x - d, y - d, rgba);
        line(b, x - d, y - d, x, y, rgba);
    }

    // ------------------------------------------------------------------ clipped primitives

    /**
     * A radar-pixel line clipped to the radar (Liang-Barsky on the pixel-centre box
     * [0, 255] x [0, height - 1]).
     */
    void line(VertexBatch b, double x0, double y0, double x1, double y1, int rgba) {
        if (slideX == 0 && slideY == 0) {
            clippedLine(b, x0, y0, x1, y1, rgba);
            return;
        }
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                int ox = i * WIDTH - slideX;
                int oy = j * height - slideY;
                clippedLine(b, x0 + ox, y0 + oy, x1 + ox, y1 + oy, rgba);
            }
        }
    }

    private void clippedLine(VertexBatch b, double x0, double y0, double x1, double y1, int rgba) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        t0 = 0;
        t1 = 1;
        if (clip(-dx, x0) && clip(dx, WIDTH - 1 - x0) && clip(-dy, y0) && clip(dy, height - 1 - y0)) {
            b.line(fx(x0 + t0 * dx), fy(y0 + t0 * dy), fx(x0 + t1 * dx), fy(y0 + t1 * dy), rgba);
        }
    }

    /** One Liang-Barsky boundary test; false when the line is entirely outside. */
    private boolean clip(double p, double q) {
        if (p == 0) {
            return q >= 0;
        }
        double r = q / p;
        if (p < 0) {
            t0 = Math.max(t0, r);
        } else {
            t1 = Math.min(t1, r);
        }
        return t0 <= t1;
    }

    /** A filled radar-pixel square [x, x+s) x [y, y+s), clipped; nothing if fully outside. */
    void rect(VertexBatch b, int x, int y, int s, int rgba) {
        if (slideX == 0 && slideY == 0) {
            clippedRect(b, x, y, s, rgba);
            return;
        }
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                clippedRect(b, x + i * WIDTH - slideX, y + j * height - slideY, s, rgba);
            }
        }
    }

    private void clippedRect(VertexBatch b, int x, int y, int s, int rgba) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(WIDTH, x + s);
        int y1 = Math.min(height, y + s);
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        b.fillRect(left + x0 * sx, top - y1 * sy, left + x1 * sx, top - y0 * sy, rgba);
    }

    private float fx(double x) {
        return (float) (left + (x + 0.5) * sx);
    }

    private float fy(double y) {
        return (float) (top - (y + 0.5) * sy);
    }
}
