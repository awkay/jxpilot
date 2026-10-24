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

import com.fulcrologic.jxpilot.client.paint.RadarPainter;

/**
 * The radar blips as C's {@code radar_ptr} holds them, for the HUD radar ({@code showHUDRadar},
 * {@code painthud.c} {@code Paint_hudradar}, drawn by {@code HudPainter.paintHudRadar}): radar pixel
 * coordinates (y up) and C's size byte. Preallocated; allocation free.
 */
public final class HudRadarBlips {
    public final int[] x;
    public final int[] y;
    public final int[] size;
    private int count;

    public HudRadarBlips(int capacity) {
        x = new int[capacity];
        y = new int[capacity];
        size = new int[capacity];
    }

    /**
     * Take this frame's blips.
     * <p>
     * BEHAVIOR: blip i becomes radar pixel {@code ((int)(floor(wx) * 256 / worldWidth), (int)(floor(wy) *
     * radarHeight / worldHeight))} (the server's fast radar truncation, as {@code RadarPainter.blip}) with its
     * size byte; at most the capacity are taken.
     */
    public void fill(RadarBlips r, double worldWidth, double worldHeight, int radarHeight) {
        count = Math.min(r.count(), x.length);
        for (int i = 0; i < count; i++) {
            x[i] = (int) (Math.floor(r.x(i)) * RadarPainter.WIDTH / worldWidth);
            y[i] = (int) (Math.floor(r.y(i)) * radarHeight / worldHeight);
            size[i] = r.sizeByte(i);
        }
    }

    public int count() { return count; }
}
