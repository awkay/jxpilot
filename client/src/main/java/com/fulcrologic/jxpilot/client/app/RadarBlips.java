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
 * The radar's moving parts for one frame, filled by {@link SceneFiller}: object blips as world
 * positions with C's size byte ({@code Frame_radar}: bit 0x80 friend, the rest the size; drawn with
 * {@code RadarPainter.blipByte}) and the own ship (heading line and steady blip). Fixed capacity;
 * allocation free.
 */
public final class RadarBlips {
    private final float[] x;
    private final float[] y;
    private final int[] sizeByte;
    private int count;

    /** True when the own ship is drawn on the radar (it exists and is not dead). */
    public boolean selfVisible;
    public float selfX;
    public float selfY;
    /** Radians. */
    public float selfHeading;
    /** True when the next race checkpoint is marked (C {@code Paint_checkpoint_radar}). */
    public boolean checkpointVisible;
    /** Its block (map block coordinates, y up). */
    public int checkpointBx;
    public int checkpointBy;

    public RadarBlips(int capacity) {
        x = new float[capacity];
        y = new float[capacity];
        sizeByte = new int[capacity];
    }

    /** BEHAVIOR: no blips, no own ship and no checkpoint. */
    public void clear() {
        count = 0;
        selfVisible = false;
        checkpointVisible = false;
    }

    /** BEHAVIOR: appends a blip; false (nothing added) when full. */
    public boolean add(float wx, float wy, int size) {
        if (count == x.length) {
            return false;
        }
        x[count] = wx;
        y[count] = wy;
        sizeByte[count] = size;
        count++;
        return true;
    }

    public int count() { return count; }

    public float x(int i) { return x[i]; }

    public float y(int i) { return y[i]; }

    /** C's size byte: 0x80 = friend colour, low bits the square size (0 draws one pixel). */
    public int sizeByte(int i) { return sizeByte[i]; }

    public int capacity() { return x.length; }
}
