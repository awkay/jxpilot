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

import com.fulcrologic.jxpilot.common.math.XMath;

import java.nio.ByteBuffer;

/**
 * Builds a SNAPSHOT body into a datagram buffer (after the {@link PacketHeader}). Reusable and
 * allocation free.
 * <pre>
 *   header: u32 tick, u32 inputTick, i8 inputSlack, u8 part, u8 partCount, u8 flags, u16 viewX, u16 viewY  (16 bytes)
 *   then sections: u8 type, u8 recordSize, u8 variant, u16 count, count * record
 * </pre>
 * {@code viewX/viewY} (u16 half pixels) is the receiver's view centre; view-culled records store
 * positions as i16 half-pixel offsets from it (wrap-aware), see {@link #relX(double)}.
 * Usage:
 * <pre>
 *   w.setWorld(worldW, worldH, wrap);                       // once per map
 *   w.begin(buf, tick, inputTick, inputSlack, 0, 1, FLAG_LAST_PART, camX, camY);
 *   if (w.beginSection(Section.SHOT, ShotKind.SHOT &lt;&lt; 4 | ObjColor.ENEMY)) {
 *       for (...) { if (!w.hasRoom()) break; Records.Shot.put(w, x, y, vx, vy); }
 *       w.endSection();
 *   }
 * </pre>
 * When a tick does not fit in one datagram the server sends several parts (same tick, part
 * 0..partCount-1), highest priority first. Each part is self-contained.
 */
public final class SnapshotWriter {
    /** Snapshot header size. */
    public static final int HEADER_SIZE = 16;
    /** Offset of the part count byte in the header. */
    public static final int PART_COUNT_OFFSET = 10;
    /** Offset of the flags byte in the header. */
    public static final int FLAGS_OFFSET = 11;
    /** {@code inputSlack} value meaning "no input arrived in the measurement window". */
    public static final int NO_SLACK = -128;
    /** Header flag: this part is the last one the server will send for this tick. */
    public static final int FLAG_LAST_PART = 1;
    /**
     * Header flags bits 1-7: this client's starvation count mod 128 (ticks that ran without its input entry, the
     * server holding the last keys). A change from the previous snapshot means the client's lead is too short; a
     * counter rather than a flag, so a lost snapshot does not lose the report.
     */
    public static final int STARVE_SHIFT = 1;
    /** Mask of the starvation counter after {@link #STARVE_SHIFT}. */
    public static final int STARVE_MASK = 0x7F;

    /** BEHAVIOR: the header flags with the starvation count {@code starvations} (mod 128) in bits 1-7. */
    public static int starveFlags(int flags, long starvations) {
        return flags & ~(STARVE_MASK << STARVE_SHIFT) | ((int) starvations & STARVE_MASK) << STARVE_SHIFT;
    }

    /** BEHAVIOR: the starvation counter of a header flags byte. */
    public static int starvations(int flags) {
        return flags >>> STARVE_SHIFT & STARVE_MASK;
    }

    private ByteBuffer out;
    private int sectionPos = -1;
    private int recSize;
    private int partCountPos;
    private double viewX;
    private double viewY;
    private double worldW = 65536;
    private double worldH = 65536;
    private boolean wrap;

    /** World size in pixels and wrap mode, used for wrap-aware relative positions. */
    public void setWorld(double width, double height, boolean edgeWrap) {
        worldW = width;
        worldH = height;
        wrap = edgeWrap;
    }

    /** World width in pixels (for radar quantization). */
    public double worldWidth() {
        return worldW;
    }

    /** World height in pixels. */
    public double worldHeight() {
        return worldH;
    }

    /**
     * Whether a point can be encoded relative to the current view centre.
     * <p>
     * BEHAVIOR: true iff both wrap-aware offsets are within +-{@link Wire#MAX_REL} pixels; the server
     * must cull objects for which this is false (encoding them throws).
     */
    public boolean inView(double x, double y) {
        double dx = XMath.wrapDelta(x - viewX, worldW, wrap);
        double dy = XMath.wrapDelta(y - viewY, worldH, wrap);
        return dx <= Wire.MAX_REL && dx >= -Wire.MAX_REL && dy <= Wire.MAX_REL && dy >= -Wire.MAX_REL;
    }

    /** The buffer being written (records append to it). */
    public ByteBuffer buffer() {
        return out;
    }

    /**
     * Write the snapshot header at the buffer's position.
     * <p>
     * BEHAVIOR: the view centre is stored quantized to half pixels and relative positions are
     * measured from that quantized value, so decoding adds back exactly.
     * BEHAVIOR: inputSlack is clamped to i8 (-127..127; {@link #NO_SLACK} -128 is kept as "no report").
     *
     * @param inputTick  the tick whose client input the server applied in this tick (the held one on starvation)
     * @param inputSlack the smallest margin (1/16 tick) by which this client's inputs arrived ahead of their tick
     *                   over the last second, or {@link #NO_SLACK}
     */
    public void begin(ByteBuffer buf, int tick, int inputTick, int inputSlack, int part, int partCount, int flags,
                      double viewCenterX, double viewCenterY) {
        out = buf;
        sectionPos = -1;
        buf.putInt(tick);
        buf.putInt(inputTick);
        buf.put((byte) (inputSlack == NO_SLACK ? NO_SLACK : Math.max(-127, Math.min(127, inputSlack))));
        buf.put((byte) part);
        partCountPos = buf.position();
        buf.put((byte) partCount);
        buf.put((byte) flags);
        int vx = Wire.posToU16(viewCenterX);
        int vy = Wire.posToU16(viewCenterY);
        buf.putShort((short) vx);
        buf.putShort((short) vy);
        viewX = vx * 0.5;
        viewY = vy * 0.5;
    }

    /** {@link #begin(ByteBuffer, int, int, int, int, int, int, double, double)} with no slack report. */
    public void begin(ByteBuffer buf, int tick, int inputTick, int part, int partCount, int flags,
                      double viewCenterX, double viewCenterY) {
        begin(buf, tick, inputTick, NO_SLACK, part, partCount, flags, viewCenterX, viewCenterY);
    }

    /** Patch the part count after the fact (e.g. when the split is decided while writing). */
    public void setPartCount(int partCount) {
        out.put(partCountPos, (byte) partCount);
    }

    /** Patch the flags byte. */
    public void setFlags(int flags) {
        out.put(partCountPos + 1, (byte) flags);
    }

    /**
     * i16 half-pixel offset of world x from the view centre.
     * <p>
     * BEHAVIOR: takes the short way around in wrapping worlds.
     * BEHAVIOR: throws IllegalArgumentException beyond +-{@link Wire#MAX_REL} (see {@link #inView}).
     */
    public int relX(double x) {
        return Wire.relToI16(XMath.wrapDelta(x - viewX, worldW, wrap));
    }

    /** i16 half-pixel offset of world y from the view centre (see {@link #relX}). */
    public int relY(double y) {
        return Wire.relToI16(XMath.wrapDelta(y - viewY, worldH, wrap));
    }

    /** Open a section with variant 0 (see {@link #beginSection(int, int)}). */
    public boolean beginSection(int type) {
        return beginSection(type, 0);
    }

    /**
     * Open a section of {@code type}; {@code variant} is a per-section byte (e.g. shot kind/colour).
     * <p>
     * BEHAVIOR: returns false and writes nothing if not even the header plus one record fits.
     * BEHAVIOR: throws for unknown types and when a section is already open.
     */
    public boolean beginSection(int type, int variant) {
        if (sectionPos >= 0) {
            throw new IllegalStateException("section already open");
        }
        int size = Section.recordSize(type);
        if (size <= 0) {
            throw new IllegalArgumentException("unknown section type " + type);
        }
        if (out.remaining() < Section.HEADER_SIZE + size) {
            return false;
        }
        recSize = size;
        sectionPos = out.position();
        out.put((byte) type);
        out.put((byte) size);
        out.put((byte) variant);
        out.putShort((short) 0);
        return true;
    }

    /**
     * BEHAVIOR: true if one more record of the open section fits and the count stays below 65535.
     */
    public boolean hasRoom() {
        return out.remaining() >= recSize
            && (out.position() - sectionPos - Section.HEADER_SIZE) / recSize < 0xFFFF;
    }

    /**
     * Close the open section, patching its count.
     * <p>
     * BEHAVIOR: an empty section is removed from the buffer; returns the record count.
     * BEHAVIOR: throws if no section is open or a partial record was written.
     */
    public int endSection() {
        if (sectionPos < 0) {
            throw new IllegalStateException("no open section");
        }
        int bytes = out.position() - sectionPos - Section.HEADER_SIZE;
        if (bytes % recSize != 0) {
            throw new IllegalStateException("partial record in section");
        }
        int count = bytes / recSize;
        if (count == 0) {
            out.position(sectionPos);
        } else {
            out.putShort(sectionPos + 3, (short) count);
        }
        sectionPos = -1;
        return count;
    }
}
