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

import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.render.Font;

/**
 * {@link TextMetrics} in logical pixels of a {@link Font} baked at {@code ratio} framebuffer pixels per
 * logical pixel (what client-net's {@code Hud} layout needs). Allocation free.
 */
public final class FontMetrics implements TextMetrics {
    private Font font;
    private double ratio = 1;

    /** Use {@code f}, baked for {@code pixelRatio}. */
    public void set(Font f, double pixelRatio) {
        font = f;
        ratio = pixelRatio;
    }

    /** BEHAVIOR: the font's ascent / ratio, rounded. */
    @Override
    public int ascent() {
        return (int) Math.round(font.ascent() / ratio);
    }

    /** BEHAVIOR: the font's descent / ratio, rounded. */
    @Override
    public int descent() {
        return (int) Math.round(font.descent() / ratio);
    }

    /** BEHAVIOR: the summed advances of the first {@code len} chars / ratio, rounded. */
    @Override
    public int width(CharSequence s, int len) {
        float w = 0;
        for (int i = 0; i < len; i++) {
            w += font.advance(s.charAt(i));
        }
        return (int) Math.round(w / ratio);
    }
}
