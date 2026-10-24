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
package com.fulcrologic.jxpilot.client.game.ui;

import com.fulcrologic.jxpilot.client.game.CharBuf;
import com.fulcrologic.jxpilot.client.game.hud.FontRole;
import com.fulcrologic.jxpilot.client.game.hud.HudColor;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;

/**
 * The C client's widget looks ({@code widget.c} {@code Widget_draw_*}) as {@link HudFrame} items, in window
 * pixels. A widget's (x, y) is its X window origin: with a border of width {@code bw} the border's outer corner,
 * the inside starting at (x + bw, y + bw), as {@code XCreateSimpleWindow} places it. Pure; allocation free.
 */
public final class UiDraw {
    private UiDraw() {}

    /**
     * C {@code Widget_draw_button} with its window's border.
     * <p>
     * BEHAVIOR: a white outline of the border when bw &gt; 0 (columns x .. x + w + 2bw - 1); the inside
     * (x + bw, y + bw, w, h) filled RED (C buttonColor), or BLACK when {@code inverse} (pressed with the pointer
     * inside); the label in shadowed white centred: x = (w - width) / 2, baseline (h - (ascent + descent)) / 2
     * + ascent, inside coordinates.
     */
    public static void button(HudFrame out, int x, int y, int w, int h, int bw, CharSequence label, boolean inverse,
                              TextMetrics m) {
        if (bw > 0) {
            out.rect(x, y, w + 2 * bw - 1, h + 2 * bw - 1, false, HudColor.WHITE);
        }
        int ix = x + bw;
        int iy = y + bw;
        out.rect(ix, iy, w, h, true, inverse ? HudColor.BLACK : HudColor.BUTTON);
        int tx = ix + (w - m.width(label, label.length())) / 2;
        int ty = iy + (h - (m.ascent() + m.descent())) / 2 + m.ascent();
        text(out, tx, ty, HudColor.SCORE, FontRole.BUTTON, label);
    }

    /**
     * C {@code Widget_create_label} + {@code WIDGET_LABEL} drawing (no border).
     * <p>
     * BEHAVIOR: shadowed white text at x + (w - width) / 2, baseline y + (h - (ascent + descent)) / 2 + ascent.
     */
    public static void label(HudFrame out, int x, int y, int w, int h, CharSequence s, TextMetrics m) {
        int tx = x + (w - m.width(s, s.length())) / 2;
        int ty = y + (h - (m.ascent() + m.descent())) / 2 + m.ascent();
        text(out, tx, ty, HudColor.SCORE, FontRole.TEXT, s);
    }

    /**
     * C {@code Widget_draw_input} on its black window (no border).
     * <p>
     * BEHAVIOR: the window filled BLACK; plain white text centred as in {@link #label}.
     */
    public static void input(HudFrame out, int x, int y, int w, int h, CharSequence s, TextMetrics m) {
        out.rect(x, y, w, h, true, HudColor.BLACK);
        int tx = x + (w - m.width(s, s.length())) / 2;
        int ty = y + (h - (m.ascent() + m.descent())) / 2 + m.ascent();
        text(out, tx, ty, HudColor.WHITE, FontRole.TEXT, s);
    }

    /**
     * C {@code Widget_draw_arrow} (no border).
     * <p>
     * BEHAVIOR: the window filled BLACK (RED while pressed with the pointer inside) and a filled triangle in RED
     * (BLACK while pressed) with C's integer corners: tip at (l, h/2), base from (r, h/4) to (r, h - h/4) with
     * l = w/4, r = w - l; a right arrow has its tip at r and its base at l. Filled as X fills a polygon: one row per
     * pixel row, covering the pixels whose centres lie between the edges (left edge in, right edge out).
     */
    public static void arrow(HudFrame out, int x, int y, int w, int h, boolean right, boolean pressed) {
        out.rect(x, y, w, h, true, pressed ? HudColor.BUTTON : HudColor.BLACK);
        int fg = pressed ? HudColor.BLACK : HudColor.BUTTON;
        int l = w / 4;
        int r = w - l;
        int top = h / 4;
        int bottom = h - top;
        int mid = h / 2;
        for (int row = top; row < bottom; row++) {
            double cy = row + 0.5;
            double t = cy <= mid ? (mid - cy) / (mid - top) : (cy - mid) / (bottom - mid);
            double offset = (r - l) * t;
            int x0;
            int x1;
            if (right) {
                x0 = l;
                x1 = (int) Math.ceil(r - offset - 0.5);
            } else {
                x0 = (int) Math.ceil(l + offset - 0.5);
                x1 = r;
            }
            if (x1 > x0) {
                out.rect(x + x0, y + row, x1 - x0, 1, true, fg);
            }
        }
    }

    /** BEHAVIOR: a text run (role {@code role}) holding {@code s}; nothing when the list is full. */
    static void text(HudFrame out, int x, int y, int color, FontRole role, CharSequence s) {
        CharBuf t = out.text(x, y, color);
        if (t != null) {
            t.append(s);
        }
    }

    /** BEHAVIOR: true iff (px, py) is inside [x, x + w) x [y, y + h). */
    public static boolean inside(int px, int py, int x, int y, int w, int h) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
