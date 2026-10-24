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

/**
 * One CONFIG panel option ({@code configure.c} {@code Config_create_bool/int/float}): its label, widget kind,
 * range and the value it edits. Bools read as 0 / 1.
 */
public final class ConfigEntry {
    /** Widget kinds. */
    public enum Kind { BOOL, INT, FLOAT }

    /** The edited value and what a change does (C's variable plus its {@code Config_update_*} callback). */
    public interface Value {
        double get();

        void set(double v);
    }

    public final String label;
    public final Kind kind;
    public final double min;
    public final double max;
    public final Value value;

    public ConfigEntry(String label, Kind kind, double min, double max, Value value) {
        this.label = label;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.value = value;
    }

    /** A Yes/No entry. */
    public static ConfigEntry bool(String label, Value value) {
        return new ConfigEntry(label, Kind.BOOL, 0, 1, value);
    }

    /**
     * C's int arrows ({@code widget.c} {@code Widget_button}, WIDGET_INPUT_INT).
     * <p>
     * BEHAVIOR: right: (int)(v * 1.05 + 0.5), or v + 1 when that equals v; left: (int)(v * 0.95), or v - 1 when
     * that equals v; clamped to min..max.
     */
    public static int stepInt(int v, boolean right, int min, int max) {
        int n;
        if (right) {
            n = (int) (v * 1.05 + 0.5);
            if (n == v) {
                n++;
            }
        } else {
            n = (int) (v * 0.95);
            if (n == v) {
                n--;
            }
        }
        return Math.max(min, Math.min(max, n));
    }

    /**
     * C's float arrows ({@code widget.c} {@code Widget_button}, WIDGET_INPUT_FLOAT): steps of 5% of the distance
     * from the near end of the range (0 when the range spans 0), at least 1% of the range.
     * <p>
     * BEHAVIOR: v is first clamped; the offset from fmin (v &gt;= 0: max(min, 0); v &lt; 0: min(max, 0), counted
     * outwards) grows by 5% (at least range/100) when the arrow points away from fmin, else shrinks by 5% (at least
     * range/100), never crossing 0 from a positive offset; the result is clamped. The 1.05 / 0.95 products are
     * single-precision, as C's {@code (float)} casts.
     */
    public static double stepFloat(double value, boolean right, double min, double max) {
        double v = Math.max(min, Math.min(max, value));
        double delta = max - min;
        double fmin;
        double offset;
        if (v >= 0) {
            fmin = min < 0 ? 0 : min;
            offset = v - fmin;
        } else {
            fmin = max > 0 ? 0 : max;
            offset = -v + fmin;
        }
        double n;
        if (right == (v >= 0)) {
            n = (float) (offset * 1.05);
            if (n - offset < delta / 100.0) {
                n = offset + delta / 100.0;
            }
        } else {
            n = (float) (offset * 0.95);
            if (n - offset > -delta / 100.0) {
                n = offset - delta / 100.0;
            }
            if (n < 0 && offset > 0) {
                n = 0;
            }
        }
        double r = v >= 0 ? fmin + n : fmin - n;
        return Math.max(min, Math.min(max, r));
    }

    /**
     * The value as C's input widget shows it.
     * <p>
     * BEHAVIOR: INT "%d"; FLOAT "%.2f" when max &lt;= 1, "%.1f" when max &lt;= 10, else "%d" of the truncated
     * value; BOOL "Yes"/"No".
     */
    public CharBuf format(CharBuf out) {
        double v = value.get();
        out.clear();
        switch (kind) {
            case BOOL -> out.append(v != 0 ? "Yes" : "No");
            case INT -> out.appendInt((int) v);
            default -> {
                if (max <= 1.0) {
                    out.appendFixed(v, 2, 0);
                } else if (max <= 10.0) {
                    out.appendFixed(v, 1, 0);
                } else {
                    out.appendInt((int) v);
                }
            }
        }
        return out;
    }

    /** BEHAVIOR: a bool flips; an INT or FLOAT steps by one arrow press ({@link #stepInt}, {@link #stepFloat}). */
    public void click(boolean right) {
        double v = value.get();
        switch (kind) {
            case BOOL -> value.set(v != 0 ? 0 : 1);
            case INT -> {
                int n = stepInt((int) v, right, (int) min, (int) max);
                if (n != (int) v) {
                    value.set(n);
                }
            }
            default -> {
                double n = stepFloat(v, right, min, max);
                if (n != v) {
                    value.set(n);
                }
            }
        }
    }
}
