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

/**
 * An X geometry string ({@code geometry} resource, C {@code XParseGeometry}): {@code [WxH][{+-}X{+-}Y]}.
 *
 * @param width  0 when not given
 * @param height 0 when not given
 * @param x      offset from the left (negative: from the right, as X's "-X"), meaningful iff {@code hasPosition}
 * @param y      offset from the top (negative: from the bottom), meaningful iff {@code hasPosition}
 */
public record Geometry(int width, int height, int x, int y, boolean xNegative, boolean yNegative,
                       boolean hasPosition) {
    /**
     * BEHAVIOR: "WxH", "WxH+X+Y", "+X+Y", "-X-Y" and mixes; an optional leading '='; sizes must be positive;
     * anything else (garbage, a missing number, trailing text) gives null. "-0" is a right/bottom offset of 0.
     */
    public static Geometry parse(String s) {
        String t = s.trim();
        if (t.startsWith("=")) {
            t = t.substring(1);
        }
        int i = 0;
        int w = 0;
        int h = 0;
        if (i < t.length() && Character.isDigit(t.charAt(i))) {
            int x = t.indexOf('x', i);
            if (x < 0) {
                x = t.indexOf('X', i);
            }
            if (x < 0) {
                return null;
            }
            int end = x + 1;
            while (end < t.length() && Character.isDigit(t.charAt(end))) {
                end++;
            }
            try {
                w = Integer.parseInt(t.substring(i, x));
                h = Integer.parseInt(t.substring(x + 1, end));
            } catch (NumberFormatException e) {
                return null;
            }
            if (w <= 0 || h <= 0) {
                return null;
            }
            i = end;
        }
        if (i == t.length()) {
            return w > 0 ? new Geometry(w, h, 0, 0, false, false, false) : null;
        }
        int[] off = new int[2];
        boolean[] neg = new boolean[2];
        for (int k = 0; k < 2; k++) {
            if (i >= t.length() || (t.charAt(i) != '+' && t.charAt(i) != '-')) {
                return null;
            }
            neg[k] = t.charAt(i) == '-';
            int start = ++i;
            while (i < t.length() && Character.isDigit(t.charAt(i))) {
                i++;
            }
            if (i == start) {
                return null;
            }
            try {
                off[k] = Integer.parseInt(t.substring(start, i));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (i != t.length()) {
            return null;
        }
        return new Geometry(w, h, off[0], off[1], neg[0], neg[1], true);
    }

    /**
     * The window's top-left on a screen of {@code screenW x screenH} for a window of {@code w x h}.
     * <p>
     * BEHAVIOR: "+X" is X from the left, "-X" puts the right edge X from the screen's right; the same for Y.
     */
    public int left(int screenW, int w) {
        return xNegative ? screenW - w - x : x;
    }

    /** See {@link #left}. */
    public int top(int screenH, int h) {
        return yNegative ? screenH - h - y : y;
    }
}
