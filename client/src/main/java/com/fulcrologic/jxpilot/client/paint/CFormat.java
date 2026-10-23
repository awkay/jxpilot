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

import com.fulcrologic.jxpilot.client.render.NumberText;

/**
 * C's padded {@code %d} conversions ({@code %d}, {@code %3d}, {@code %02d}, {@code %03d}) for the HUD
 * painters, written into a {@code char[]} without allocation ({@code NumberText} has no padding,
 * and client-net's {@code CharBuf} is not on this side).
 */
public final class CFormat {
    private CFormat() {}

    /**
     * {@code %<width>d} ({@code zero} false) or {@code %0<width>d} ({@code zero} true).
     * <p>
     * BEHAVIOR: a number shorter than {@code width} is right-aligned, padded with spaces, or with
     * zeros after the minus sign; a longer number is written whole (C never truncates).
     */
    public static int padInt(int v, int width, boolean zero, char[] out, int off) {
        int n = NumberText.formatInt(v, out, off);
        return padLeft(out, off, n, width, zero);
    }

    /** Right-align the {@code n} chars at {@code off} in a field of {@code width}. */
    static int padLeft(char[] out, int off, int n, int width, boolean zero) {
        int pad = width - n;
        if (pad <= 0) {
            return n;
        }
        System.arraycopy(out, off, out, off + pad, n);
        if (zero && out[off + pad] == '-') {
            out[off] = '-';
            for (int i = 1; i <= pad; i++) {
                out[off + i] = '0';
            }
        } else {
            char c = zero ? '0' : ' ';
            for (int i = 0; i < pad; i++) {
                out[off + i] = c;
            }
        }
        return width;
    }
}
