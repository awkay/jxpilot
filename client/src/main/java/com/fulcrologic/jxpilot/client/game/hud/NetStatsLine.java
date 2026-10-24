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

import com.fulcrologic.jxpilot.client.game.CharBuf;
import com.fulcrologic.jxpilot.client.net.NetDiagnostics;

/**
 * JXPilot's network diagnostics line (keyToggleNetStats; docs/latency-fixes.md §7.6), at the top right of the game
 * view: round trip time, interpolation delay, snapshot jitter and loss, and the prediction figures (input lead,
 * input slack, corrections) that phase 1 fills; unknown values print "-". Off by default. Pure layout over a
 * {@link HudFrame}; allocation free.
 */
public final class NetStatsLine {
    /** Pixels from the view's top and right edges. */
    public static final int MARGIN = 10;
    /** Longest line (all figures at their widest). */
    public static final int CAPACITY = 128;

    /** Shown or not ({@link #toggle}). */
    public boolean visible;
    private final CharBuf text = new CharBuf(CAPACITY);

    /** BEHAVIOR: flips {@link #visible}. */
    public void toggle() {
        visible = !visible;
    }

    /** The text of the last {@link #format}. */
    public CharBuf text() {
        return text;
    }

    /**
     * BEHAVIOR: {@code rtt R ms  interp I ms  jitter J ms  loss L% late K%  lead D  slack S  corr C/s P px}: R and I
     * whole milliseconds, J, L and K (late, reordered snapshots, not counted as lost) one decimal, D one decimal tick, S two decimal ticks, C one decimal, P whole pixels. Any
     * value that is NaN (or all of them when {@code d} is null) prints "-" without its unit.
     */
    public CharBuf format(NetDiagnostics d, double interpMs) {
        text.clear();
        field("rtt ", d == null ? Double.NaN : d.rttMs(), 0, " ms");
        field("  interp ", interpMs, 0, " ms");
        field("  jitter ", d == null ? Double.NaN : d.jitterMs(), 1, " ms");
        field("  loss ", d == null ? Double.NaN : d.lossPercent(), 1, "%");
        field("  late ", d == null ? Double.NaN : d.latePercent(), 1, "%");
        field("  lead ", d == null ? Double.NaN : d.leadTicks, 1, "");
        field("  slack ", d == null ? Double.NaN : d.inputSlackTicks, 2, "");
        field("  corr ", d == null ? Double.NaN : d.correctionsPerSecond, 1, "/s");
        if (d != null && !Double.isNaN(d.lastCorrectionPx)) {
            text.append(' ').appendFixed(d.lastCorrectionPx, 0, 0).append(" px");
        }
        return text;
    }

    private void field(String label, double v, int decimals, String unit) {
        text.append(label);
        if (Double.isNaN(v)) {
            text.append('-');
        } else {
            text.appendFixed(v, decimals, 0).append(unit);
        }
    }

    /** BEHAVIOR: right-aligned {@link #MARGIN} from the right edge, but never left of {@link #MARGIN}. */
    static int x(int viewW, int textWidth) {
        return Math.max(MARGIN, viewW - MARGIN - textWidth);
    }

    /** BEHAVIOR: the baseline {@link #MARGIN} plus the font's ascent below the top edge. */
    static int baseline(TextMetrics m) {
        return MARGIN + m.ascent();
    }

    /**
     * Lay the line out into {@code out} for a view {@code viewW} wide (unscaled HUD pixels).
     * <p>
     * BEHAVIOR: hidden: nothing. Shown: one {@link FontRole#GAME} text run in {@link HudColor#HUD} with the
     * {@link #format}ted text at ({@link #x}, {@link #baseline}).
     */
    public void layout(HudFrame out, int viewW, NetDiagnostics d, double interpMs, TextMetrics m) {
        if (!visible) {
            return;
        }
        format(d, interpMs);
        CharBuf run = out.text(x(viewW, m.width(text, text.length())), baseline(m), FontRole.GAME, HudColor.HUD);
        if (run != null) {
            run.append(text);
        }
    }
}
