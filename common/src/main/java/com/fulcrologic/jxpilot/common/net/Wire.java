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
 * Allocation-free encode/decode helpers: quantization and Latin-1 strings.
 * <ul>
 *   <li>Relative position: i16 half pixels from the snapshot's view centre (+-16383 px).</li>
 *   <li>Position: u16 in half pixels ({@code round(px*2)}), covers worlds up to 32767 px
 *       (900 blocks * 35 = 31500).</li>
 *   <li>Velocity: i16 in 1/16 pixel per original frame (+-2047 px/frame).</li>
 *   <li>Angle: u16, full turn = 65536, counter-clockwise from +x.</li>
 *   <li>Fraction: u16, 0..65535 maps to 0..1.</li>
 *   <li>Strings: u8 or u16 length followed by Latin-1 bytes (chars above 255 become '?').</li>
 * </ul>
 */
public final class Wire {
    private Wire() {}

    private static final double ANGLE_SCALE = 65536.0 / (2.0 * Math.PI);

    /** BEHAVIOR: rounds to the nearest half pixel and clamps to 0..65535. */
    public static int posToU16(double px) {
        int v = (int) Math.floor(px * 2.0 + 0.5);
        return v < 0 ? 0 : (v > 0xFFFF ? 0xFFFF : v);
    }

    /** u16 half-pixels (as read, any sign extension is removed) to pixels. */
    public static float u16ToPos(int v) {
        return (v & 0xFFFF) * 0.5f;
    }

    /** Largest |offset| in pixels a relative position may have ({@link #relToI16}). */
    public static final double MAX_REL = 16383.0;

    /**
     * Relative offset in pixels to i16 half pixels.
     * <p>
     * BEHAVIOR: rounds to the nearest half pixel.
     * BEHAVIOR: throws IllegalArgumentException when |px| &gt; {@link #MAX_REL}; callers cull first
     * ({@link SnapshotWriter#inView}), so nothing is ever silently clamped.
     */
    public static int relToI16(double px) {
        if (px > MAX_REL || px < -MAX_REL) {
            throw new IllegalArgumentException("relative offset out of range");
        }
        return (int) Math.floor(px * 2.0 + 0.5);
    }

    /**
     * World coordinate to a u8 radar cell: {@code floor(v / size * 256)} clamped to 0..255.
     * <p>
     * BEHAVIOR: 0 maps to 0, values just below size map to 255, out-of-range values clamp.
     */
    public static int toRadar8(double v, double size) {
        int q = (int) Math.floor(v / size * 256.0);
        return q < 0 ? 0 : (q > 255 ? 255 : q);
    }

    /** BEHAVIOR: u8 radar cell to the world coordinate of the cell centre. */
    public static float fromRadar8(int v, double size) {
        return (float) (((v & 0xFF) + 0.5) * size / 256.0);
    }

    /** i16 half pixels (as read via getShort) to a pixel offset. */
    public static float i16ToRel(int v) {
        return ((short) v) * 0.5f;
    }

    /** Velocity (px/frame) to i8 whole pixels per frame (rounded, clamped to +-127). */
    public static int velToI8(double v) {
        int q = (int) Math.floor(v + 0.5);
        return q < -127 ? -127 : (q > 127 ? 127 : q);
    }

    /** Non-negative value to u16 sixteenths (rounded, clamped 0..65535); used for speeds. */
    public static int toU16Sixteenths(double v) {
        int q = (int) Math.floor(v * 16.0 + 0.5);
        return q < 0 ? 0 : (q > 0xFFFF ? 0xFFFF : q);
    }

    /** BEHAVIOR: rounds to the nearest 1/16 px/frame and clamps to the i16 range. */
    public static int velToI16(double v) {
        int q = (int) Math.floor(v * 16.0 + 0.5);
        return q < -32768 ? -32768 : (q > 32767 ? 32767 : q);
    }

    /** i16 sixteenths (as read via getShort) to px/frame. */
    public static float i16ToVel(int v) {
        return ((short) v) / 16.0f;
    }

    /** BEHAVIOR: rounds to 2pi/65536 steps and wraps any angle into 0..65535. */
    public static int angleToU16(double rad) {
        return ((int) Math.floor(rad * ANGLE_SCALE + 0.5)) & 0xFFFF;
    }

    /** u16 angle to radians in [0, 2pi). */
    public static float u16ToAngle(int v) {
        return (float) ((v & 0xFFFF) / ANGLE_SCALE);
    }

    /** Fraction 0..1 to u16 (clamped). */
    public static int fracToU16(double f) {
        int v = (int) Math.floor(f * 65535.0 + 0.5);
        return v < 0 ? 0 : (v > 0xFFFF ? 0xFFFF : v);
    }

    /** u16 to fraction 0..1. */
    public static float u16ToFrac(int v) {
        return (v & 0xFFFF) / 65535.0f;
    }

    public static int u8(ByteBuffer b, int off) {
        return b.get(off) & 0xFF;
    }

    public static int u16(ByteBuffer b, int off) {
        return b.getShort(off) & 0xFFFF;
    }

    public static int s16(ByteBuffer b, int off) {
        return b.getShort(off);
    }

    public static long u32(ByteBuffer b, int off) {
        return b.getInt(off) & 0xFFFFFFFFL;
    }

    /** Write a string with a u8 length prefix (truncated to 255 chars and to the space left). */
    public static void putString8(ByteBuffer b, CharSequence s) {
        int n = s == null ? 0 : Math.min(Math.min(255, s.length()), b.remaining() - 1);
        b.put((byte) n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            b.put((byte) (c > 255 ? '?' : c));
        }
    }

    /** Write a string with a u16 length prefix (truncated to 65535 chars and to the space left). */
    public static void putString16(ByteBuffer b, CharSequence s) {
        int n = s == null ? 0 : Math.min(Math.min(0xFFFF, s.length()), b.remaining() - 2);
        b.putShort((short) n);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            b.put((byte) (c > 255 ? '?' : c));
        }
    }

    /** Read a u8-length string into {@code out} (cleared first). */
    public static void getString8(ByteBuffer b, StringBuilder out) {
        int n = b.get() & 0xFF;
        readChars(b, n, out);
    }

    /** Read a u16-length string into {@code out} (cleared first). */
    public static void getString16(ByteBuffer b, StringBuilder out) {
        int n = b.getShort() & 0xFFFF;
        readChars(b, n, out);
    }

    /** Shorten {@code sb} to at most {@code max} chars. */
    public static void cap(StringBuilder sb, int max) {
        if (sb.length() > max) {
            sb.setLength(max);
        }
    }

    private static void readChars(ByteBuffer b, int n, StringBuilder out) {
        out.setLength(0);
        if (n > b.remaining()) {
            throw new java.nio.BufferUnderflowException();
        }
        for (int i = 0; i < n; i++) {
            out.append((char) (b.get() & 0xFF));
        }
    }

    /** Bytes needed by {@link #putString8}. */
    public static int sizeString8(CharSequence s) {
        return 1 + (s == null ? 0 : Math.min(255, s.length()));
    }

    /** Bytes needed by {@link #putString16}. */
    public static int sizeString16(CharSequence s) {
        return 2 + (s == null ? 0 : Math.min(0xFFFF, s.length()));
    }
}
