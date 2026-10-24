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
import com.fulcrologic.jxpilot.client.render.Shapes;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.Trig;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import com.fulcrologic.jxpilot.client.render.WinScale;
import com.fulcrologic.jxpilot.common.Item;

/**
 * The parts of the XPilot 4.5.5 HUD ({@code painthud.c}) that client-net's {@code Hud.build} does not
 * lay out: the lock display ({@code Paint_lock}), the item column, time left, modifiers,
 * "Autopilot" ({@code Paint_HUD}), the HUD radar ({@code Paint_hudradar}) and the on-map score
 * objects ({@code Paint_score_objects}). Pure drawing from plain values: the C state behind them
 * (item-change timers, the lock warning counter, score object counts) belongs to the caller.
 * <p>
 * Layout is in C window coordinates (logical pixels, origin top-left, y down) around the HUD centre
 * {@code (hx, hy)} (C {@code hud_pos_x/y}), converted through {@link ScreenSpace}; font metrics
 * (framebuffer pixels) are divided by the pixel ratio to join them, and C's {@code int / 2} of a
 * pixel width is kept ({@link #half}). Colours are palette indices; lines are 1 logical pixel wide.
 * <p>
 * Window scaling: {@code hx}, {@code hy} and the other window positions passed in are C's unscaled
 * window coordinates (at zoom z the unscaled view is {@code viewWidth / z} wide), and every expression
 * C wraps in {@code WINSCALE} is scaled by {@link WinScale#of} at the zoom set with {@link #setScale};
 * font metrics, item symbols and the offsets C adds after scaling stay in pixels. At zoom 1 nothing
 * changes. Allocation-free; the fields are the scale and scratch buffers.
 */
public final class HudPainter {
    /** {@code HUD_SIZE}: half the HUD frame size. */
    public static final int HUD_SIZE = 90;
    /** {@code HUD_OFFSET}: HUD line inset. */
    public static final int HUD_OFFSET = 20;
    /** {@code WARNING_DISTANCE} = VISIBILITY_DISTANCE * 0.8: closer locks blink. */
    public static final double WARNING_DISTANCE = 1000.0 * 0.8;
    /** {@code BORDER} of {@code Paint_HUD}. */
    static final int HUD_BORDER = 3;
    /** {@code BORDER} of {@code Paint_lock}. */
    static final int LOCK_BORDER = 2;
    /** {@code BLOCK_SZ}. */
    static final int BLOCK_SZ = 35;
    /** {@code SHIP_SZ}: HUD radar blips this close to the centre are skipped. */
    static final int SHIP_SZ = 16;
    /** HUD radar scale ({@code hrscale}). */
    static final int HR_SCALE = 3;
    /** Lock ship heading ({@code dir = 96}) and offset from the HUD centre. */
    static final int LOCK_SHIP_DIR = 96;
    static final int LOCK_SHIP_DX = 60;
    static final int LOCK_SHIP_DY = -80;
    /** Largest lock disc. */
    static final int LOCK_DISC_MAX = 10;
    /** Most lock ship points ({@code XPoint points[64]}). */
    public static final int MAX_SHIP_POINTS = 64;
    /** Chords/triangles per circle or disc. */
    public static final int CIRCLE_SEGMENTS = 16;
    /** UI atlas side in texels (item symbols, see {@link ItemBitmaps}; gl.UiAtlas.SIZE). */
    public static final int ATLAS_SIZE = 2048;

    static final String AUTOPILOT = "Autopilot";

    private final TextWriter text;
    private final Palette palette;
    private final char[] str = new char[32];
    private final float[] shipX = new float[MAX_SHIP_POINTS];
    private final float[] shipY = new float[MAX_SHIP_POINTS];
    private double scale = 1;

    /** {@code text} draws in the C {@code gameFont}. */
    public HudPainter(TextWriter text, Palette palette) {
        this.text = text;
        this.palette = palette;
    }

    /** The zoom used as C's {@code WINSCALE} factor (1 = unscaled). */
    public void setScale(double zoom) {
        this.scale = zoom;
    }

    /** C {@code WINSCALE(n)} at the current scale. */
    int ws(int n) {
        return WinScale.of(n, scale);
    }

    // ================================================================== Paint_lock

    /** {@code mapdiag}: the map diagonal in pixels, {@code (int) LENGTH(x * BLOCK_SZ, y * BLOCK_SZ)}. */
    public static int mapDiag(int blocksX, int blocksY) {
        return (int) Math.hypot((double) blocksX * BLOCK_SZ, (double) blocksY * BLOCK_SZ);
    }

    /** Own ship and the target are friends: same team in team play, or the same (non-blank) alliance. */
    public static boolean isFriend(boolean selfPresent, boolean teamPlay, int selfTeam, int targetTeam,
                                   char selfAlliance, char targetAlliance) {
        return selfPresent
            && ((selfTeam == targetTeam && teamPlay) || (selfAlliance != ' ' && selfAlliance == targetAlliance));
    }

    /**
     * Whether {@code Paint_lock} draws the direction disc.
     * <p>
     * BEHAVIOR: never at distance 0; always beyond {@link #WARNING_DISTANCE}; within it only when
     * {@code warningPhase} is on. C's phase is {@code warningCount++ % 2 == 0} with the counter
     * advanced only on paints within the warning distance (the caller keeps it).
     */
    public static boolean lockDiscShown(int lockDist, boolean warningPhase) {
        return lockDist != 0 && (lockDist > WARNING_DISTANCE || warningPhase);
    }

    /**
     * {@code Paint_lock}'s text: the target name and its distance or lives.
     * <p>
     * BEHAVIOR: the name, HUD colour, at {@code WINSCALE(hx) - name_width / 2} with C's
     * {@code name_width} = text width + 2 ({@code FIND_NAME_WIDTH}), baseline
     * {@code WINSCALE(hy - 72) - descent}.
     * BEHAVIOR: with limited lives or a non-zero distance: the first 3 chars of {@code "%03d"} of the
     * lives (limited lives) or {@code lockDist / 35}, at ({@code WINSCALE(hx + 72)}, same baseline), red
     * iff limited lives and none left, else HUD colour.
     */
    public void paintLockText(VertexBatch b, ScreenSpace ss, int hx, int hy, CharSequence name, boolean limitedLives,
                              int targetLife, int lockDist, int hudColor) {
        int hud = palette.color(hudColor);
        double y = ws(hy - HUD_SIZE + HUD_OFFSET - LOCK_BORDER) - desc(ss);
        double nameW = text.width(name) / ss.ratio() + ShipPainter.NAME_PAD;
        text.draw(b, name, ss.edgeX(ws(hx) - half(nameW)), ss.baseline(y), hud);
        if (limitedLives || lockDist != 0) {
            CFormat.padInt(limitedLives ? targetLife : lockDist / BLOCK_SZ, 3, true, str, 0);
            int col = limitedLives && targetLife == 0 ? palette.color(Palette.RED) : hud;
            draw(b, ss, str, 3, ws(hx + HUD_SIZE - HUD_OFFSET + LOCK_BORDER), y, col);
        }
    }

    /**
     * C shows the lock mini ship only while it is at least about 10 pixels wide:
     * {@code 10 * scaleFactor <= 15}, i.e. zoom at least 2/3 (with a rounding tolerance for 1/1.5).
     */
    public static boolean lockShipShown(double zoom) {
        return 10 / zoom <= 15 + 1e-9;
    }

    /**
     * {@code Paint_lock}'s mini ship: the target's ship shape at heading 0 ({@code ship->pts[i][0]},
     * world units, y up) turned to heading 96, halved, offset (+60, -80) from the HUD centre,
     * truncated to whole pixels and scaled ({@code WINSCALE((int)(...))}), filled in the HUD colour as a
     * triangle fan from {@code WINSCALE(hx + 60, hy - 80)}.
     * <p>
     * BEHAVIOR: nothing for fewer than 3 points or when {@link #lockShipShown} is false at the scale;
     * at most {@link #MAX_SHIP_POINTS} are used.
     */
    public void paintLockShip(VertexBatch b, ScreenSpace ss, int hx, int hy, float[] xs, float[] ys, int n,
                              int hudColor) {
        n = Math.min(n, MAX_SHIP_POINTS);
        if (n < 3 || !lockShipShown(scale)) {
            return;
        }
        int rgba = palette.color(hudColor);
        double c = Trig.cosRes(LOCK_SHIP_DIR);
        double sn = Trig.sinRes(LOCK_SHIP_DIR);
        for (int i = 0; i < n; i++) {
            double px = c * xs[i] - sn * ys[i];
            double py = sn * xs[i] + c * ys[i];
            shipX[i] = ss.edgeX(ws((int) (hx + px / 2 + LOCK_SHIP_DX)));
            shipY[i] = ss.edgeY(ws((int) (hy + py / 2 + LOCK_SHIP_DY)));
        }
        float cx = ss.edgeX(ws(hx + LOCK_SHIP_DX));
        float cy = ss.edgeY(ws(hy + LOCK_SHIP_DY));
        for (int i = 0; i < n; i++) {
            int j = i + 1 == n ? 0 : i + 1;
            b.triangle(cx, cy, shipX[i], shipY[i], shipX[j], shipY[j], rgba);
        }
    }

    /**
     * {@code Paint_lock}'s direction disc.
     * <p>
     * BEHAVIOR: drawn iff {@link #lockDiscShown}: a circle of {@code min(mapDiag / lockDist, 10)} (at
     * least 1) pixels whose bounding box's top-left is {@code (int)(hx + 54 cos(dir) - size / 2)},
     * {@code (int)(hy - 54 sin(dir) - size / 2)}; an outline in the HUD colour for a friend (C
     * {@code Arc_add}: box from {@code WINSCALE(x)}, {@code WINSCALE(x + size) - WINSCALE(x)} wide), else
     * filled in the lock colour ({@code fillArc} of {@code WINSCALE(size)} at {@code WINSCALE(x, y)}).
     */
    public void paintLockDisc(VertexBatch b, ScreenSpace ss, int hx, int hy, int lockDist, int lockDir, int mapDiag,
                              boolean friend, boolean warningPhase, int hudColor, int lockColor) {
        if (!lockDiscShown(lockDist, warningPhase)) {
            return;
        }
        b.setLineWidth(VertexBatch.DEFAULT_LINE_WIDTH);
        int size = Math.max(1, Math.min(mapDiag / lockDist, LOCK_DISC_MAX));
        int x = (int) (hx + HUD_SIZE * 0.6 * Trig.cosRes(lockDir) - size * 0.5);
        int y = (int) (hy - HUD_SIZE * 0.6 * Trig.sinRes(lockDir) - size * 0.5);
        if (friend) {
            int sx = ws(x);
            int sy = ws(y);
            double r = (ws(x + size) - sx) / 2.0;
            double ry = (ws(y + size) - sy) / 2.0;
            Shapes.circle(b, ss.x(sx + r), ss.y(sy + ry), ss.len(r), CIRCLE_SEGMENTS, palette.color(hudColor));
        } else {
            double r = ws(size) / 2.0;
            disc(b, ss.edgeX(ws(x) + r), ss.edgeY(ws(y) + r), ss.len(r), palette.color(lockColor));
        }
    }

    // ================================================================== Paint_HUD pieces

    /**
     * Item symbols with their counts, in columns growing leftwards from {@code WINSCALE(hx - 73)},
     * starting at {@code WINSCALE(hy - 67)} (C: "find the scaled location, then work in pixels").
     * <p>
     * BEHAVIOR: item {@code i} (by {@link Item} ordinal) is listed iff {@code counts[i] >= 0}; fuel
     * never is. (C's choice of counts: with {@code showItems}, counts above 0, others -1; without, a
     * count for {@code showItemsTime * FPS} frames after it changes; that timer is the caller's.)
     * BEHAVIOR: each entry: the 16x16 symbol with its top-left at (h - 16, v), the count right-aligned
     * 3 px left of it with its baseline at {@code v + 8 + ascent / 2}; the next entry is
     * {@code max(16, ascent + descent) + 1} lower.
     * BEHAVIOR: when the next entry would pass {@code WINSCALE(hy + 67)}, a new column starts at the top,
     * {@code widest (count + 3 + 16) + 6} further left.
     * BEHAVIOR: the {@code loseItem} entry gets an 18x18 outline at (h - 18, v - 2) iff {@code loseActive}.
     */
    public void paintItems(VertexBatch b, ScreenSpace ss, int hx, int hy, int[] counts, int loseItem,
                           boolean loseActive, int hudColor) {
        b.setLineWidth(VertexBatch.DEFAULT_LINE_WIDTH);
        int rgba = palette.color(hudColor);
        double spacing = Math.max(PaintConfig.ITEM_SIZE, asc(ss) + desc(ss)) + 1;
        double top = ws(hy - HUD_SIZE + HUD_OFFSET + HUD_BORDER);
        double bottom = ws(hy + HUD_SIZE - HUD_OFFSET - HUD_BORDER);
        double vert = top;
        double horiz = ws(hx - HUD_SIZE + HUD_OFFSET - HUD_BORDER);
        double maxWidth = -1;
        int size = PaintConfig.ITEM_SIZE;
        int n = Math.min(counts.length, Item.COUNT);
        for (int i = 0; i < n; i++) {
            int num = counts[i];
            if (i == Item.FUEL.ordinal() || num < 0) {
                continue;
            }
            symbol(b, ss, i, horiz - size, vert, rgba);
            if (i == loseItem && loseActive) {
                rect(b, ss, horiz - size - 2, vert - 2, size + 2, size + 2, rgba);
            }
            int len = CFormat.padInt(num, 0, false, str, 0);
            double width = width(str, len, ss);
            draw(b, ss, str, len, horiz - size - HUD_BORDER - width, vert + size / 2 + half(asc(ss)), rgba);
            maxWidth = Math.max(maxWidth, width + HUD_BORDER + size);
            vert += spacing;
            if (vert + spacing > bottom) {
                horiz -= maxWidth + 2 * HUD_BORDER;
                vert = top;
                maxWidth = -1;
            }
        }
    }

    /**
     * C {@code Paint_item} in a window (the information window's item list), window coordinates y down.
     * <p>
     * BEHAVIOR: a BLUE triangle outline through (x - S, y - S), (x, y + S), (x + S, y - S) with
     * S = {@link PaintConfig#ITEM_TRIANGLE_SIZE}, and the item's symbol in RED with its top-left corner at
     * (x - ITEM_SIZE / 2, y - S + 2).
     */
    public void paintItemIcon(VertexBatch b, ScreenSpace ss, int item, int x, int y) {
        b.setLineWidth(VertexBatch.DEFAULT_LINE_WIDTH);
        int s = PaintConfig.ITEM_TRIANGLE_SIZE;
        int blue = palette.color(Palette.BLUE);
        seg(b, ss, x - s, y - s, x, y + s, blue);
        seg(b, ss, x, y + s, x + s, y - s, blue);
        seg(b, ss, x + s, y - s, x - s, y - s, blue);
        symbol(b, ss, item, x - PaintConfig.ITEM_SIZE / 2, y - s + 2, palette.color(Palette.RED));
    }

    /**
     * BEHAVIOR: iff {@code timeLeft > 0} (seconds), {@code "%3d:%02d"} (minutes, seconds) right-aligned
     * at {@code WINSCALE(hx - 73)}, baseline {@code WINSCALE(hy - 73) - descent}.
     */
    public void paintTimeLeft(VertexBatch b, ScreenSpace ss, int hx, int hy, int timeLeft, int hudColor) {
        if (timeLeft <= 0) {
            return;
        }
        int n = CFormat.padInt(timeLeft / 60, 3, false, str, 0);
        str[n++] = ':';
        n += CFormat.padInt(timeLeft % 60, 2, true, str, n);
        double w = width(str, n, ss);
        double edge = ws(hx - HUD_SIZE + HUD_OFFSET - HUD_BORDER);
        draw(b, ss, str, n, edge - w, ws(hy - HUD_SIZE + HUD_OFFSET - HUD_BORDER) - desc(ss), palette.color(hudColor));
    }

    /**
     * BEHAVIOR: the modifiers string right-aligned at {@code WINSCALE(hx - 73)}, baseline
     * {@code WINSCALE(hy + 73) + ascent}.
     */
    public void paintMods(VertexBatch b, ScreenSpace ss, int hx, int hy, CharSequence mods, int hudColor) {
        double w = text.width(mods) / ss.ratio();
        text.draw(b, mods, ss.edgeX(ws(hx - HUD_SIZE + HUD_OFFSET - HUD_BORDER) - w),
            ss.baseline(ws(hy + HUD_SIZE - HUD_OFFSET + HUD_BORDER) + asc(ss)), palette.color(hudColor));
    }

    /**
     * BEHAVIOR: "Autopilot" ({@code autopilotLight}) at {@code WINSCALE(hx) - width / 2}, baseline
     * {@code WINSCALE(hy - 73) - 2 descent - ascent}.
     */
    public void paintAutopilot(VertexBatch b, ScreenSpace ss, int hx, int hy, int hudColor) {
        double w = text.width(AUTOPILOT) / ss.ratio();
        text.draw(b, AUTOPILOT, ss.edgeX(ws(hx) - half(w)),
            ss.baseline(ws(hy - HUD_SIZE + HUD_OFFSET - HUD_BORDER) - 2 * desc(ss) - asc(ss)), palette.color(hudColor));
    }

    // ================================================================== Paint_hudradar

    /**
     * {@code Paint_hudradar}: enemy radar blips scaled 3x around the view centre. Blips are C's
     * {@code radar_ptr}: radar coordinates (y up) and a size byte (bit 0x80 = friend).
     * <p>
     * BEHAVIOR: friend blips are skipped; each other blip sits at
     * {@code (int)(radar * 3 - (world + view / 2) * 768 / mapWidth)} (y likewise with
     * {@code 3 * radarHeight / mapHeight}), relative to the view centre (y up); {@code world} is the view's
     * lower-left world position ({@code world.x/y}) and {@code view} the unscaled view size
     * ({@code ext_view_width/height}).
     * BEHAVIOR: blips strictly within 16 px of the centre on both axes are skipped.
     * BEHAVIOR: with wrap play, offsets beyond half the scaled radar wrap by its size.
     * BEHAVIOR: each is a circle outline of {@code 3 * size} (3 for size 0) with its bounding box's
     * top-left at {@code (viewW/2 + x - sz/2, viewH/2 - y - sz/2)}, scaled as C's {@code Arc_add} does:
     * the box runs from {@code WINSCALE(left)} to {@code WINSCALE(left + sz)}.
     */
    public void paintHudRadar(VertexBatch b, ScreenSpace ss, int viewW, int viewH, int[] radarX, int[] radarY,
                              int[] radarSize, int n, int radarHeight, int mapWidth, int mapHeight, int worldX,
                              int worldY, boolean wrapPlay, int hudColor) {
        b.setLineWidth(VertexBatch.DEFAULT_LINE_WIDTH);
        int hud = palette.color(hudColor);
        int hrw = HR_SCALE * 256;
        int hrh = HR_SCALE * radarHeight;
        float xf = (float) hrw / (float) mapWidth;
        float yf = (float) hrh / (float) mapHeight;
        for (int i = 0; i < n; i++) {
            int sz = radarSize[i];
            if ((sz & 0x80) != 0) {
                continue;
            }
            int x = (int) (radarX[i] * HR_SCALE - (worldX + viewW / 2) * xf);
            int y = (int) (radarY[i] * HR_SCALE - (worldY + viewH / 2) * yf);
            if (x < SHIP_SZ && x > -SHIP_SZ && y < SHIP_SZ && y > -SHIP_SZ) {
                continue;
            }
            if (wrapPlay) {
                x = wrapHalf(x, hrw);
                y = wrapHalf(y, hrh);
            }
            sz = sz > 0 ? sz * HR_SCALE : HR_SCALE;
            int left = x + viewW / 2 - sz / 2;
            int top = -y + viewH / 2 - sz / 2;
            int sl = ws(left);
            int st = ws(top);
            double r = (ws(left + sz) - sl) / 2.0;
            double ry = (ws(top + sz) - st) / 2.0;
            Shapes.circle(b, ss.x(sl + r), ss.y(st + ry), ss.len(r), CIRCLE_SEGMENTS, hud);
        }
    }

    /** BEHAVIOR: an offset beyond half of {@code span} (either side) moves by {@code span} towards 0. */
    static int wrapHalf(int v, int span) {
        if (v < 0) {
            if (-v > span / 2) {
                v += span;
            }
        } else if (v > span / 2) {
            v -= span;
        }
        return v;
    }

    // ================================================================== Paint_score_objects

    /**
     * {@code Paint_score_objects}' blink: an object with frame count {@code count} is drawn iff the
     * count is positive and not a multiple of 3.
     */
    public static boolean scoreObjectVisible(int count) {
        return count > 0 && count % 3 != 0;
    }

    /**
     * One on-map score object: {@code msg} at {@code WINSCALE(x) - width / 2} with its baseline at
     * {@code WINSCALE(y) + ascent / 2}, where (x, y) are C's unscaled window coordinates of the block
     * centre ({@code X()}/{@code Y()} after {@code wrap}).
     */
    public void paintScoreObject(VertexBatch b, ScreenSpace ss, CharSequence msg, int x, int y, int hudColor) {
        double w = text.width(msg) / ss.ratio();
        text.draw(b, msg, ss.edgeX(ws(x) - half(w)), ss.baseline(ws(y) + half(asc(ss))), palette.color(hudColor));
    }

    // ================================================================== primitives in C coordinates

    /** C's {@code int / 2} of a non-negative pixel quantity. */
    static double half(double v) {
        return Math.floor(v / 2);
    }

    private double asc(ScreenSpace ss) {
        return text.font().ascent() / ss.ratio();
    }

    private double desc(ScreenSpace ss) {
        return text.font().descent() / ss.ratio();
    }

    private double width(char[] s, int len, ScreenSpace ss) {
        float w = 0;
        for (int i = 0; i < len; i++) {
            w += text.width(s[i]);
        }
        return w / ss.ratio();
    }

    private void draw(VertexBatch b, ScreenSpace ss, char[] s, int len, double x, double y, int rgba) {
        text.draw(b, s, 0, len, ss.edgeX(x), ss.baseline(y), rgba);
    }

    /** X11 {@code XDrawLine}/{@code Segment_add}: a line between pixel centres. */
    static void seg(VertexBatch b, ScreenSpace ss, double x1, double y1, double x2, double y2, int rgba) {
        b.line(ss.x(x1), ss.y(y1), ss.x(x2), ss.y(y2), rgba);
    }

    /** X11 {@code LineOnOffDash} line between pixel centres, {@code on}/{@code off} logical pixels from offset 0. */
    static void dash(VertexBatch b, ScreenSpace ss, double x1, double y1, double x2, double y2, int on, int off,
                     int rgba) {
        Dashes.segment(b, ss.x(x1), ss.y(y1), ss.x(x2), ss.y(y2), on, off, 0, (float) ss.ratio(), rgba);
    }

    /** X11 {@code XDrawRectangle}: an outline through the pixel centres of columns x..x+w, rows y..y+h. */
    static void rect(VertexBatch b, ScreenSpace ss, double x, double y, double w, double h, int rgba) {
        b.strokeRect(ss.x(x), ss.y(y), ss.x(x + w), ss.y(y + h), rgba);
    }

    /** X11 {@code XFillRectangle}: pixels x..x+w-1, y..y+h-1; nothing unless both sides are positive. */
    static void fill(VertexBatch b, ScreenSpace ss, double x, double y, double w, double h, int rgba) {
        if (w > 0 && h > 0) {
            b.fillRect(ss.edgeX(x), ss.edgeY(y), ss.edgeX(x + w), ss.edgeY(y + h), rgba);
        }
    }

    /** A filled disc as a fan of {@link #CIRCLE_SEGMENTS} triangles (framebuffer coordinates). */
    static void disc(VertexBatch b, float cx, float cy, float r, int rgba) {
        float px = cx + r;
        float py = cy;
        for (int i = 1; i <= CIRCLE_SEGMENTS; i++) {
            double a = 360.0 * i / CIRCLE_SEGMENTS;
            float qx = cx + (float) (r * Trig.cosDeg(a));
            float qy = cy + (float) (r * Trig.sinDeg(a));
            b.triangle(cx, cy, px, py, qx, qy, rgba);
            px = qx;
            py = qy;
        }
    }

    /** {@code texturedObjects} (C {@code blockBitmaps}): item symbols are the {@code ALL_ITEMS} pictures. */
    private boolean bitmapItems;

    /** C {@code blockBitmaps} for the item symbols ({@link #symbol}). */
    public void setBitmapItems(boolean on) {
        bitmapItems = on;
    }

    /**
     * {@code Paint_item_symbol}: with {@code texturedObjects} the item's {@code ALL_ITEMS} picture (untinted), else
     * {@link #itemSymbol}.
     */
    void symbol(VertexBatch b, ScreenSpace ss, int item, double x, double y, int rgba) {
        if (!bitmapItems) {
            itemSymbol(b, ss, item, x, y, rgba);
            return;
        }
        int size = ItemBitmaps.SIZE;
        BlockBitmaps.draw(b, BlockBitmap.ALL_ITEMS, item, ss.edgeX(x), ss.edgeY(y), ss.len(size), ss.len(size));
    }

    /** {@code Paint_item_symbol(..., ITEM_HUD)}: the 16x16 symbol with its top-left at (x, y), tinted. */
    static void itemSymbol(VertexBatch b, ScreenSpace ss, int item, double x, double y, int rgba) {
        int size = ItemBitmaps.SIZE;
        float u0 = ItemBitmaps.atlasX(item) / (float) ATLAS_SIZE;
        float u1 = (ItemBitmaps.atlasX(item) + size) / (float) ATLAS_SIZE;
        float vTop = ItemBitmaps.atlasY(item) / (float) ATLAS_SIZE;
        float vBottom = (ItemBitmaps.atlasY(item) + size) / (float) ATLAS_SIZE;
        b.texRect(ss.edgeX(x), ss.edgeY(y + size), ss.edgeX(x + size), ss.edgeY(y), u0, vBottom, u1, vTop, rgba);
    }
}
