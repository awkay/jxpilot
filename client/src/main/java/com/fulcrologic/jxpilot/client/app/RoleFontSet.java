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

import com.fulcrologic.jxpilot.client.game.hud.FontRole;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.render.Font;
import com.fulcrologic.jxpilot.client.render.FontSpec;
import com.fulcrologic.jxpilot.client.render.TextWriter;

import java.util.Arrays;

/**
 * The baked font of every {@link FontRole}: its metrics in logical pixels (for the HUD layout) and its
 * {@link TextWriter} (for drawing), plus what they were baked from so the caller can tell when to rebake.
 * Roles sharing a font share its writer. {@link #of} and {@link #needs} allocate nothing.
 */
public final class RoleFontSet implements FontRole.Metrics {
    private static final int N = FontRole.values().length;

    private final FontMetrics[] metrics = new FontMetrics[N];
    private final TextWriter[] writers = new TextWriter[N];
    private FontSpec[] specs;
    private double ratio;

    public RoleFontSet() {
        for (int i = 0; i < N; i++) {
            metrics[i] = new FontMetrics();
        }
    }

    /**
     * Use freshly baked fonts.
     * <p>
     * BEHAVIOR: role i measures and draws with {@code fonts[i]} at {@code pixelRatio}; roles with the same
     * {@link Font} object share one writer; remembers {@code baked} and the ratio for {@link #needs}.
     *
     * @param baked the specs the fonts were baked from, by role ordinal (copied)
     * @param fonts the baked font of each role, by role ordinal
     */
    public void set(FontSpec[] baked, Font[] fonts, double pixelRatio) {
        for (int i = 0; i < N; i++) {
            TextWriter w = null;
            for (int j = 0; j < i && w == null; j++) {
                if (fonts[j] == fonts[i]) {
                    w = writers[j];
                }
            }
            writers[i] = w != null ? w : new TextWriter(fonts[i]);
            metrics[i].set(fonts[i], pixelRatio);
        }
        specs = baked.clone();
        ratio = pixelRatio;
    }

    /** BEHAVIOR: true iff nothing is baked yet, or {@code wanted} or the ratio differ from the last {@link #set}. */
    public boolean needs(FontSpec[] wanted, double pixelRatio) {
        return specs == null || pixelRatio != ratio || !Arrays.equals(specs, wanted);
    }

    /** The role's metrics in logical pixels. */
    @Override
    public TextMetrics of(FontRole role) {
        return metrics[role.ordinal()];
    }

    /** The role's writer. */
    public TextWriter writer(FontRole role) {
        return writers[role.ordinal()];
    }

    /** Writers by role ordinal (the array itself; HudListPainter copies it). */
    public TextWriter[] writers() {
        return writers;
    }
}
