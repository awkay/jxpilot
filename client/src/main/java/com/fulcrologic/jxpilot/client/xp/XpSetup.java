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

/**
 * The server setup the C server sends at the start of the reliable stream after the verify reply
 * ({@code Handle_setup} in {@code server/netserver.c}, {@code Net_setup} in {@code client/netclient.c}):
 * a header {@code %ld%ld%hd%hd%hd%hd%hd%s%s} (map data length, mode, lives, width, height,
 * frames per second, map order, map name, map author) followed by the (run-length compressed) map
 * bytes. Filled incrementally from the stream; allocates the map buffer once per header.
 */
public final class XpSetup {
    /** Header without the strings. */
    public static final int FIXED_HEADER = 4 + 4 + 2 * 5;

    public int mapDataLen;
    public int mode;
    public int lives;
    public int width;
    public int height;
    public int fps;
    public int mapOrder;
    public final StringBuilder name = new StringBuilder(XpProto.MAX_CHARS);
    public final StringBuilder author = new StringBuilder(XpProto.MAX_CHARS);
    private byte[] mapData = new byte[0];
    private int received;
    private boolean haveHeader;
    private final StringBuilder error = new StringBuilder();

    /** BEHAVIOR: forgets the header and map bytes. */
    public void reset() {
        haveHeader = false;
        received = 0;
        error.setLength(0);
    }

    public boolean haveHeader() { return haveHeader; }

    /** True when the header and all {@link #mapDataLen} map bytes are in. */
    public boolean complete() { return haveHeader && received == mapDataLen; }

    public byte[] mapData() { return mapData; }

    public int received() { return received; }

    /** Why {@link #readHeader} failed. */
    public CharSequence error() { return error; }

    /**
     * Parse the header at the reader's position.
     * <p>
     * BEHAVIOR: returns the bytes consumed; 0 when the data ends early (nothing consumed); -1 for a
     * string without NUL within {@link XpProto#MAX_CHARS} or the C consistency checks: length &lt;= 0,
     * width or height &lt;= 0, length &gt; width * height, or an unknown map order ({@link #error()}).
     */
    public int readHeader(XpReader r) {
        int start = r.pos();
        if (!r.has(FIXED_HEADER)) {
            return 0;
        }
        int len = r.s32();
        int md = r.s32();
        int lv = r.s16();
        int w = r.s16();
        int h = r.s16();
        int f = r.s16();
        int order = r.s16();
        int n1 = r.string(name, XpProto.MAX_CHARS);
        int n2 = n1 > 0 ? r.string(author, XpProto.MAX_CHARS) : n1;
        if (n1 <= 0 || n2 <= 0) {
            r.pos(start);
            if (n1 == XpReader.OVERFLOW || n2 == XpReader.OVERFLOW) {
                error.setLength(0);
                error.append("map name or author too long");
                return -1;
            }
            return 0;
        }
        error.setLength(0);
        if (len <= 0 || w <= 0 || h <= 0 || len > w * h) {
            error.append("bad map specs from server (").append(len).append(',').append(w).append(',')
                .append(h).append(')');
            return -1;
        }
        if (order != XpProto.SETUP_MAP_ORDER_XY && order != XpProto.SETUP_MAP_UNCOMPRESSED) {
            error.append("unknown map order type (").append(order).append(')');
            return -1;
        }
        mapDataLen = len;
        mode = md;
        lives = lv;
        width = w;
        height = h;
        fps = f;
        mapOrder = order;
        if (mapData.length < len) {
            mapData = new byte[len];
        }
        received = 0;
        haveHeader = true;
        return r.pos() - start;
    }

    /**
     * Copy map bytes from the reader (after the header).
     * <p>
     * BEHAVIOR: takes at most the bytes still missing; returns how many were taken.
     */
    public int readMap(XpReader r) {
        int n = Math.min(r.remaining(), mapDataLen - received);
        for (int i = 0; i < n; i++) {
            mapData[received + i] = (byte) r.u8();
        }
        received += n;
        return n;
    }
}
