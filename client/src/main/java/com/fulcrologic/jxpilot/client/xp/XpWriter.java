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
package com.fulcrologic.jxpilot.client.xp;

import java.nio.ByteBuffer;

/**
 * Field encoders matching C {@code Packet_printf} ({@code common/net.c}): big endian, and
 * NUL-terminated Latin-1 strings. Unlike C, an over-long string is truncated to {@code max - 1}
 * chars instead of failing the packet, and chars above 255 become {@code '?'}. Allocation free.
 */
public final class XpWriter {
    private XpWriter() {}

    /** {@code %c}. */
    public static void u8(ByteBuffer b, int v) {
        b.put((byte) v);
    }

    /** {@code %hd} / {@code %hu}. */
    public static void s16(ByteBuffer b, int v) {
        b.putShort((short) v);
    }

    /** {@code %d} / {@code %u} / {@code %ld}. */
    public static void s32(ByteBuffer b, int v) {
        b.putInt(v);
    }

    /**
     * {@code %s} / {@code %S}.
     * <p>
     * BEHAVIOR: writes at most {@code max - 1} chars then a NUL; chars above 255 and NULs inside the
     * text are written as '?'; returns the bytes written.
     */
    public static int string(ByteBuffer b, CharSequence s, int max) {
        int n = Math.min(s.length(), max - 1);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            b.put((byte) (c > 255 || c == 0 ? '?' : c));
        }
        b.put((byte) 0);
        return n + 1;
    }

    /** Bytes {@link #string} writes for {@code s}. */
    public static int stringSize(CharSequence s, int max) {
        return Math.min(s.length(), max - 1) + 1;
    }
}
