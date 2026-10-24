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
package com.fulcrologic.jxpilot.client.game.hud;

import java.util.List;

/**
 * The in-game key help (JXPilot keyHelp, F1; in the spirit of the C client's keys help): the effective
 * bindings as "keys  description" lines in columns over a black panel, conflicts last in red. The lines are
 * fixed at construction (bindings do not change while playing); {@link #layout} allocates nothing.
 */
public final class KeyHelp {
    /** Panel margin and padding (px). */
    public static final int MARGIN = 10;
    /** Gap between a line's keys and its description, and between columns (px). */
    public static final int GAP = 12;

    private final String title;
    private final String[] keys;
    private final String[] help;
    private final boolean[] warn;
    private boolean visible;

    /**
     * @param title   first line (e.g. "Keys - F1 closes - defaults + ~/.xpilotrc")
     * @param entries {keys, description}; entries whose keys start with "* " are conflicts (red)
     */
    public KeyHelp(String title, List<String[]> entries) {
        this.title = title;
        int n = entries.size();
        keys = new String[n];
        help = new String[n];
        warn = new boolean[n];
        for (int i = 0; i < n; i++) {
            keys[i] = entries.get(i)[0];
            help[i] = entries.get(i)[1];
            warn[i] = keys[i].startsWith("* ");
        }
    }

    /** BEHAVIOR: flips {@link #visible()}. */
    public void toggle() {
        visible = !visible;
    }

    public boolean visible() { return visible; }

    public int entries() { return keys.length; }

    public String title() { return title; }

    /**
     * Lay the help out in a {@code viewW x viewH} view.
     * <p>
     * BEHAVIOR: clears {@code out}; draws nothing while hidden. Visible: a filled black panel with a white
     * outline at {@link #MARGIN}, the title at the top, then the entries top to bottom in as many columns as
     * the panel height needs (line height ascent + descent + 2; at least one row); each column is as wide as
     * its widest keys plus {@link #GAP} plus its widest description; keys in the HUD colour, descriptions
     * white, conflict entries red.
     */
    public void layout(HudFrame out, int viewW, int viewH, TextMetrics m) {
        out.clear();
        if (!visible) {
            return;
        }
        int lineH = m.ascent() + m.descent() + 2;
        int top = MARGIN + MARGIN + lineH;
        int rows = Math.max(1, (viewH - top - 2 * MARGIN) / lineH);
        int n = keys.length;
        int cols = (n + rows - 1) / rows;
        // panel width = sum of column widths
        int width = 0;
        for (int c = 0; c < cols; c++) {
            width += columnKeysWidth(c, rows, m) + GAP + columnHelpWidth(c, rows, m) + (c + 1 < cols ? GAP : 0);
        }
        width = Math.max(width, m.width(title, title.length()));
        int height = top + Math.min(n, rows) * lineH;
        out.rect(MARGIN, MARGIN, width + 2 * MARGIN, height, true, HudColor.BLACK);
        out.rect(MARGIN, MARGIN, width + 2 * MARGIN, height, false, HudColor.WHITE);
        put(out, 2 * MARGIN, MARGIN + MARGIN / 2 + m.ascent(), title, HudColor.WHITE);
        int x = 2 * MARGIN;
        for (int c = 0; c < cols; c++) {
            int kw = columnKeysWidth(c, rows, m);
            for (int r = 0; r < rows; r++) {
                int i = c * rows + r;
                if (i >= n) {
                    break;
                }
                int y = top + r * lineH + m.ascent();
                put(out, x, y, keys[i], warn[i] ? HudColor.RED : HudColor.HUD);
                put(out, x + kw + GAP, y, help[i], warn[i] ? HudColor.RED : HudColor.WHITE);
            }
            x += kw + GAP + columnHelpWidth(c, rows, m) + GAP;
        }
    }

    private static void put(HudFrame out, int x, int y, String s, int color) {
        com.fulcrologic.jxpilot.client.game.CharBuf t = out.text(x, y, color);
        if (t != null) {
            t.append(s);
        }
    }

    private int columnKeysWidth(int c, int rows, TextMetrics m) {
        int w = 0;
        for (int i = c * rows; i < Math.min(keys.length, (c + 1) * rows); i++) {
            w = Math.max(w, m.width(keys[i], keys[i].length()));
        }
        return w;
    }

    private int columnHelpWidth(int c, int rows, TextMetrics m) {
        int w = 0;
        for (int i = c * rows; i < Math.min(help.length, (c + 1) * rows); i++) {
            w = Math.max(w, m.width(help[i], help[i].length()));
        }
        return w;
    }
}
