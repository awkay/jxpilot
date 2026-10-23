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
package com.fulcrologic.jxpilot.common.net;

import java.nio.ByteBuffer;

/**
 * Iterates a SNAPSHOT body (see {@link SnapshotWriter}). Reusable, allocation free:
 * <pre>
 *   if (r.begin(buf)) {
 *       while (r.nextSection()) {
 *           switch (r.type()) {
 *               case Section.SHOT -&gt; { for (int i = 0; i &lt; r.count(); i++) { shot.wrap(r, i); ... } }
 *               default -&gt; { } // unknown: skipped
 *           }
 *       }
 *   }
 * </pre>
 * Records with relative positions decode to {@code view centre + offset}, wrapped into the world
 * when {@link #setWorld} says the map wraps. Interpolate between snapshots with
 * {@link com.fulcrologic.jxpilot.common.math.XMath#wrapDelta} so objects crossing the seam move the short way.
 */
public final class SnapshotReader {
    private ByteBuffer buf;
    private int pos;
    private int end;
    private int tick;
    private int lastInputSeq;
    private int part;
    private int partCount;
    private int flags;
    private float viewX;
    private float viewY;
    private int type;
    private int recordSize;
    private int variant;
    private int count;
    private int data;
    private boolean error;
    private double worldW = 65536;
    private double worldH = 65536;
    private boolean wrap;

    /**
     * World size and wrap mode, set once per map.
     * <p>
     * BEHAVIOR: with wrap, relative positions decode into [0,W)x[0,H); without wrap they decode as
     * view centre + offset. Radar cells are scaled by the world size.
     */
    public void setWorld(double width, double height, boolean edgeWrap) {
        worldW = width;
        worldH = height;
        wrap = edgeWrap;
    }

    /** World width set by {@link #setWorld}. */
    public double worldWidth() { return worldW; }

    /** World height set by {@link #setWorld}. */
    public double worldHeight() { return worldH; }

    /** Whether decoded positions are wrapped. */
    public boolean wraps() { return wrap; }

    /**
     * Read the header at the buffer's position; sections run to the buffer's limit.
     * <p>
     * BEHAVIOR: returns false and flags {@link #malformed()} when shorter than the header.
     */
    public boolean begin(ByteBuffer in) {
        buf = in;
        int p = in.position();
        end = in.limit();
        error = false;
        if (end - p < SnapshotWriter.HEADER_SIZE) {
            error = true;
            return false;
        }
        tick = in.getInt(p);
        lastInputSeq = in.getInt(p + 4);
        part = in.get(p + 8) & 0xFF;
        partCount = in.get(p + 9) & 0xFF;
        flags = in.get(p + 10) & 0xFF;
        viewX = Wire.u16ToPos(in.getShort(p + 11));
        viewY = Wire.u16ToPos(in.getShort(p + 13));
        pos = p + SnapshotWriter.HEADER_SIZE;
        return true;
    }

    /**
     * Advance to the next section.
     * <p>
     * BEHAVIOR: false at the end of the data.
     * BEHAVIOR: false with {@link #malformed()} for a partial header, zero record size or truncated records.
     */
    public boolean nextSection() {
        if (pos >= end) {
            return false;
        }
        if (end - pos < Section.HEADER_SIZE) {
            error = true;
            return false;
        }
        type = buf.get(pos) & 0xFF;
        recordSize = buf.get(pos + 1) & 0xFF;
        variant = buf.get(pos + 2) & 0xFF;
        count = buf.getShort(pos + 3) & 0xFFFF;
        data = pos + Section.HEADER_SIZE;
        long next = (long) data + (long) recordSize * count;
        if (recordSize == 0 || next > end) {
            error = true;
            return false;
        }
        pos = (int) next;
        return true;
    }

    public int tick() { return tick; }

    /** Newest client input sequence the server had applied when producing this snapshot. */
    public int lastInputSeq() { return lastInputSeq; }

    public int part() { return part; }

    public int partCount() { return partCount; }

    public int flags() { return flags; }

    /** View centre x (world px) that relative records are measured from. */
    public float viewX() { return viewX; }

    /** View centre y (world px). */
    public float viewY() { return viewY; }

    /** {@link Section} type of the current section. */
    public int type() { return type; }

    /** Per-section variant byte (e.g. shot kind/colour). */
    public int variant() { return variant; }

    /** Transmitted record size (stride). */
    public int recordSize() { return recordSize; }

    public int count() { return count; }

    /**
     * BEHAVIOR: true iff the type is known and the transmitted record is at least as large as this
     * implementation's layout (larger records are newer versions with appended fields).
     */
    public boolean known() {
        int s = Section.recordSize(type);
        return s > 0 && recordSize >= s;
    }

    /** Absolute buffer offset of record i of the current section. */
    public int recordOffset(int i) { return data + i * recordSize; }

    public ByteBuffer buffer() { return buf; }

    /** True if the last begin/nextSection found malformed data. */
    public boolean malformed() { return error; }
}
