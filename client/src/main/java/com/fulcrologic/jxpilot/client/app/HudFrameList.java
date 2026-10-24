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

import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.paint.HudDrawList;

/**
 * {@link HudDrawList} view of client-net's {@link HudFrame} (pure delegation; the colour indices match, and a
 * run's font index is its {@code FontRole} ordinal).
 */
public final class HudFrameList implements HudDrawList {
    private final HudFrame f;

    public HudFrameList(HudFrame f) {
        this.f = f;
    }

    public HudFrame frame() { return f; }

    @Override public int lineCount() { return f.lineCount(); }
    @Override public float lineX0(int i) { return f.lineX0(i); }
    @Override public float lineY0(int i) { return f.lineY0(i); }
    @Override public float lineX1(int i) { return f.lineX1(i); }
    @Override public float lineY1(int i) { return f.lineY1(i); }
    @Override public int lineStyle(int i) { return f.lineStyle(i); }
    @Override public int lineColor(int i) { return f.lineColor(i); }
    @Override public int rectCount() { return f.rectCount(); }
    @Override public float rectX(int i) { return f.rectX(i); }
    @Override public float rectY(int i) { return f.rectY(i); }
    @Override public float rectW(int i) { return f.rectW(i); }
    @Override public float rectH(int i) { return f.rectH(i); }
    @Override public boolean rectFilled(int i) { return f.rectFilled(i); }
    @Override public int rectColor(int i) { return f.rectColor(i); }
    @Override public int textCount() { return f.textCount(); }
    @Override public CharSequence text(int i) { return f.text(i); }
    @Override public int textLength(int i) { return f.textLength(i); }
    @Override public float textX(int i) { return f.textX(i); }
    @Override public float textY(int i) { return f.textY(i); }
    @Override public int textColor(int i) { return f.textColor(i); }
    @Override public int textFont(int i) { return f.textRole(i).ordinal(); }
}
