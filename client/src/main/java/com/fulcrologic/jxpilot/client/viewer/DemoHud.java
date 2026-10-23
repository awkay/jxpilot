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
package com.fulcrologic.jxpilot.client.viewer;

import com.fulcrologic.jxpilot.client.paint.HudDrawList;
import com.fulcrologic.jxpilot.client.render.TextWriter;
import com.fulcrologic.jxpilot.client.render.WinScale;

/**
 * A stand-in HUD draw list for the {@code --demo} viewer: the parts the game client's HUD model
 * lays out (dashed HUD lines, fuel gauge, fuel meter, typewritten and fading messages, talk box,
 * scoreboard), at C's window positions ({@code painthud.c}, {@code talk.c}, {@code paint.c}) with fake
 * values. The HUD frame lines and the fuel gauge are laid out as C scales them ({@code WINSCALE} of
 * the unscaled positions at the zoom, {@link WinScale}); the rest is unscaled, as in C. Colour
 * indices follow the game client's {@code HudColor} order. Fixed capacity and constant strings:
 * {@link #build} allocates nothing.
 */
public final class DemoHud implements HudDrawList {
    /** Colour indices (game {@code HudColor} order). */
    static final int HUD = 0;
    static final int WHITE = 1;
    static final int RED = 2;
    static final int OLD_MESSAGE = 3;
    static final int BLACK = 4;
    static final int SCORE = 5;
    static final int SCORE_DIM = 6;

    static final int HUD_SIZE = 90;
    static final int HUD_OFFSET = 20;
    static final int GAUGE = 2 * (HUD_SIZE - HUD_OFFSET - 6);
    /** C {@code FUEL_GAUGE_OFFSET}. */
    static final int GAUGE_OFFSET = 6;
    static final double CHARS_PER_FRAME = 50 / 14.0;
    /** Frames a message stays red (C {@code MSG_DURATION - MSG_FLASH}). */
    static final int FLASH_FRAMES = 1024 - 892;

    static final String[] TALK = {"[pilot02]: incoming on the left!", "[pilot05]: nice shot :)"};
    static final String[] GAME = {"pilot07 was killed by a shot from pilot03.", "pilot11 entered the game.",
        "pilot04 crashed into a wall."};
    static final String TALK_LINE = "gg everyone, one more round?";
    static final String FUEL = "Fuel";
    static final String HEADING = " TM   SCORE  NAME";
    static final String[] SCORES = {"  1    142  pilot05", "* 1     97  self", "  2     55  pilot02",
        "D 2      0  pilot07"};

    private final float[] lx0 = new float[32];
    private final float[] ly0 = new float[32];
    private final float[] lx1 = new float[32];
    private final float[] ly1 = new float[32];
    private final int[] lstyle = new int[32];
    private final int[] lcolor = new int[32];
    private int lines;
    private final float[] rx = new float[16];
    private final float[] ry = new float[16];
    private final float[] rw = new float[16];
    private final float[] rh = new float[16];
    private final boolean[] rfill = new boolean[16];
    private final int[] rcolor = new int[16];
    private int rects;
    private final CharSequence[] txt = new CharSequence[32];
    private final int[] tlen = new int[32];
    private final float[] tx = new float[32];
    private final float[] ty = new float[32];
    private final int[] tcolor = new int[32];
    private int texts;

    /**
     * Lay out the demo HUD for a view of {@code viewW x viewH} logical pixels at game frame
     * {@code loops}, with the scoreboard from row {@code scoreTop}, measuring text with {@code text}
     * (framebuffer pixels, {@code ratio} per logical pixel).
     */
    public void build(int viewW, int viewH, int scoreTop, long loops, TextWriter text, double ratio) {
        build(viewW, viewH, scoreTop, loops, text, ratio, 1);
    }

    /** {@link #build} for a view at {@code zoom} (C's {@code 1 / scaleFactor}). */
    public void build(int viewW, int viewH, int scoreTop, long loops, TextWriter text, double ratio, double zoom) {
        lines = 0;
        rects = 0;
        texts = 0;
        int asc = (int) (text.font().ascent() / ratio);
        int desc = (int) (text.font().descent() / ratio);
        // C's hud_pos: the centre of the unscaled view (ext_view_width / 2)
        int hx = (int) (viewW / zoom) / 2;
        int hy = (int) (viewH / zoom) / 2;
        // HUD frame (horizontal lines, dashed)
        line(w(hx - HUD_SIZE, zoom), w(hy - HUD_SIZE + HUD_OFFSET, zoom), w(hx + HUD_SIZE, zoom),
            w(hy - HUD_SIZE + HUD_OFFSET, zoom), DASHED, HUD);
        line(w(hx - HUD_SIZE, zoom), w(hy + HUD_SIZE - HUD_OFFSET, zoom), w(hx + HUD_SIZE, zoom),
            w(hy + HUD_SIZE - HUD_OFFSET, zoom), DASHED, HUD);
        // fuel gauge: 60..100% fuel, cycling (C Paint_fuel_gauge)
        double fuel = 0.6 + 0.4 * Math.abs(Math.sin(loops / 40.0));
        int size = (int) (GAUGE * fuel);
        int gx = hx + HUD_SIZE - HUD_OFFSET + GAUGE_OFFSET;
        int gy = hy - HUD_SIZE + HUD_OFFSET + GAUGE_OFFSET;
        rect(w(gx, zoom) - 1, w(gy, zoom) - 1, w(HUD_OFFSET - 2 * GAUGE_OFFSET, zoom) + 2, w(GAUGE, zoom) + 2, false, HUD);
        rect(w(gx, zoom) + 1, w(gy + GAUGE - size, zoom) + 1, w(HUD_OFFSET - 2 * GAUGE_OFFSET, zoom), w(size, zoom),
            true, HUD);
        meter(viewW, 20, fuel, text, ratio, asc);
        messages(viewH, loops, asc, desc);
        talk(viewW, viewH, asc, desc);
        scoreboard(8, scoreTop, asc, desc);
    }

    /** C {@code Paint_meter(-10, y, "Fuel", ...)}: red fill, white frame, scale ticks, title on the left. */
    private void meter(int viewW, int y, double frac, TextWriter text, double ratio, int asc) {
        int x = viewW - (200 + 10);
        rect(x + 2, y + 2, (int) ((200 - 3) * frac), 8 - 3, true, RED);
        rect(x, y, 200, 8, false, WHITE);
        line(x, y - 4, x, y + 8 + 4, SOLID, WHITE);
        line(x + 200, y - 4, x + 200, y + 8 + 4, SOLID, WHITE);
        line(x + 100, y - 3, x + 100, y + 8 + 3, SOLID, WHITE);
        line(x + 50, y - 1, x + 50, y + 8 + 1, SOLID, WHITE);
        line(x + 150, y - 1, x + 150, y + 8 + 1, SOLID, WHITE);
        int w = (int) (text.width(FUEL) / ratio);
        text(FUEL, FUEL.length(), x - (5 + w), y + (asc + 8) / 2, WHITE);
    }

    /** Talk messages from the top, game messages from the bottom; typed out, red while fresh. */
    private void messages(int viewH, long loops, int asc, int desc) {
        int spacing = asc + desc + 1;
        for (int i = 0; i < TALK.length; i++) {
            message(TALK[i], 10, 10 + asc + i * spacing, (loops + 90 * i) % 300);
        }
        for (int i = 0; i < GAME.length; i++) {
            message(GAME[i], 10, viewH - desc - 10 - i * spacing, (loops + 70 * i) % 260);
        }
    }

    private void message(String s, int x, int y, long age) {
        int len = (int) Math.min(s.length(), CHARS_PER_FRAME * age);
        text(s, len, x, y, age < FLASH_FRAMES ? RED : OLD_MESSAGE);
    }

    /** C talk window: white 2-pixel border around a black box, the line inside. */
    private void talk(int viewW, int viewH, int asc, int desc) {
        int h = asc + desc + 2 * 3;
        int x = 50 - 2;
        int y = viewH * 3 / 4 - h / 2;
        int w = viewW - 2 * 50;
        rect(x, y, w + 4, h + 4, true, WHITE);
        rect(x + 2, y + 2, w, h, true, BLACK);
        text(TALK_LINE, TALK_LINE.length(), x + 2 + 3, y + 2 + 3 + asc, WHITE);
    }

    /** C score list: shadowed heading and rows, a line under the heading and under the best player. */
    private void scoreboard(int x0, int y0, int asc, int desc) {
        int line = y0 + 6 + asc;
        text(HEADING, HEADING.length(), x0 + 6, line, SCORE);
        line(x0 + 6, line, x0 + 250, line, SOLID, WHITE);
        int spacing = asc + desc + 3;
        int first = y0 + 2 * 6 + asc + spacing;
        for (int i = 0; i < SCORES.length; i++) {
            int y = first + spacing * i;
            text(SCORES[i], SCORES[i].length(), x0 + 6, y, SCORES[i].charAt(0) == 'D' ? SCORE_DIM : SCORE);
            if (i == 0) {
                line(x0 + 6, y, x0 + 250, y, SOLID, WHITE);
            }
        }
    }

    private static int w(int n, double zoom) {
        return WinScale.of(n, zoom);
    }

    private void line(float x0, float y0, float x1, float y1, int style, int color) {
        lx0[lines] = x0;
        ly0[lines] = y0;
        lx1[lines] = x1;
        ly1[lines] = y1;
        lstyle[lines] = style;
        lcolor[lines] = color;
        lines++;
    }

    private void rect(float x, float y, float w, float h, boolean filled, int color) {
        rx[rects] = x;
        ry[rects] = y;
        rw[rects] = w;
        rh[rects] = h;
        rfill[rects] = filled;
        rcolor[rects] = color;
        rects++;
    }

    private void text(CharSequence s, int len, float x, float y, int color) {
        txt[texts] = s;
        tlen[texts] = len;
        tx[texts] = x;
        ty[texts] = y;
        tcolor[texts] = color;
        texts++;
    }

    @Override public int lineCount() { return lines; }
    @Override public float lineX0(int i) { return lx0[i]; }
    @Override public float lineY0(int i) { return ly0[i]; }
    @Override public float lineX1(int i) { return lx1[i]; }
    @Override public float lineY1(int i) { return ly1[i]; }
    @Override public int lineStyle(int i) { return lstyle[i]; }
    @Override public int lineColor(int i) { return lcolor[i]; }
    @Override public int rectCount() { return rects; }
    @Override public float rectX(int i) { return rx[i]; }
    @Override public float rectY(int i) { return ry[i]; }
    @Override public float rectW(int i) { return rw[i]; }
    @Override public float rectH(int i) { return rh[i]; }
    @Override public boolean rectFilled(int i) { return rfill[i]; }
    @Override public int rectColor(int i) { return rcolor[i]; }
    @Override public int textCount() { return texts; }
    @Override public CharSequence text(int i) { return txt[i]; }
    @Override public int textLength(int i) { return tlen[i]; }
    @Override public float textX(int i) { return tx[i]; }
    @Override public float textY(int i) { return ty[i]; }
    @Override public int textColor(int i) { return tcolor[i]; }
}
