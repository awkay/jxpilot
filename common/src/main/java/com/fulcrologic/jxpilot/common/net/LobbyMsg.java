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

import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Bodies of the lobby datagrams (docs/PROTOCOL.md "Lobby"). They follow the 8-byte {@link PacketHeader}, whose
 * session id and reliable ack are 0. The lobby is a few request/answer pairs the client repeats until answered,
 * so there is no session and no reliable channel in it.
 * <p>
 * Anti-reflection: only HELLO (padded to {@link #MIN_HELLO_SIZE}) is answered without proof, and its answer INFO
 * is never larger than the HELLO. INFO carries a cookie the server derives from the sender's address; every later
 * request has to quote it, so the bigger answers (LIST) only go to addresses that received INFO.
 */
public final class LobbyMsg {
    private LobbyMsg() {}

    /** A HELLO datagram is at least this long (header included), so INFO can be no larger than it. */
    public static final int MIN_HELLO_SIZE = 64;
    /** Most body bytes of one LIST page. */
    public static final int MAX_PAGE_BODY = 1100;
    /** Most maps a lobby offers. */
    public static final int MAX_MAPS = 2048;
    /** LIST version meaning "I have no list". */
    public static final int NO_VERSION = 0;

    /** SELECT answer: the address now routes to the map's game; send CONNECT. */
    public static final int JOIN_OK = 0;
    /** SELECT answer: the map's game is being started; ask again. */
    public static final int JOIN_STARTING = 1;
    /** SELECT answer: no such map. */
    public static final int JOIN_UNKNOWN_MAP = 2;
    /** SELECT answer: the map could not be started (text says why). */
    public static final int JOIN_FAILED = 3;
    /** SELECT answer: the map's game is full or the lobby cannot host more games. */
    public static final int JOIN_FULL = 4;

    /** C to S: u32 nonce, then zero padding up to {@link #MIN_HELLO_SIZE} bytes of datagram. */
    public static final class Hello {
        public int nonce;

        /** BEHAVIOR: writes the nonce and pads with zeros until the buffer's position is {@link #MIN_HELLO_SIZE}. */
        public void write(ByteBuffer b) {
            b.putInt(nonce);
            while (b.position() < MIN_HELLO_SIZE) {
                b.put((byte) 0);
            }
        }

        public void read(ByteBuffer b) {
            nonce = b.getInt();
        }
    }

    /** S to C: u32 nonce (of the HELLO), u64 cookie, str8 lobby name. */
    public static final class Info {
        /** Longest name that keeps an INFO within {@link #MIN_HELLO_SIZE}. */
        public static final int MAX_NAME = MIN_HELLO_SIZE - PacketHeader.SIZE - 4 - 8 - 1;

        public int nonce;
        public long cookie;
        public final StringBuilder name = new StringBuilder();

        /** BEHAVIOR: the name is cut to {@link #MAX_NAME} characters so the datagram fits {@link #MIN_HELLO_SIZE}. */
        public void write(ByteBuffer b) {
            b.putInt(nonce);
            b.putLong(cookie);
            Wire.putString8(b, name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name);
        }

        public void read(ByteBuffer b) {
            nonce = b.getInt();
            cookie = b.getLong();
            Wire.getString8(b, name);
        }
    }

    /** C to S: u64 cookie, u32 version of the list the client holds completely ({@link #NO_VERSION} for none). */
    public static final class ListRequest {
        public long cookie;
        public int haveVersion;

        public void write(ByteBuffer b) {
            b.putLong(cookie);
            b.putInt(haveVersion);
        }

        public void read(ByteBuffer b) {
            cookie = b.getLong();
            haveVersion = b.getInt();
        }
    }

    /**
     * S to C: one page of the list. Body: u32 version, u16 page (0-based), u16 pageCount, u8 count, then per entry
     * u16 id, u8 flags (1 active), u16 humans, u16 maxPlayers, u16 width, u16 height, str8 name, str8 title,
     * str8 author. A list is complete when every page of one version has arrived.
     */
    public static final class ListPage {
        private static final int HEAD = 4 + 2 + 2 + 1;
        private static final int FLAG_ACTIVE = 1;

        public int version;
        public int page;
        public int pageCount;
        /** The entries of the page just read. */
        public final List<LobbyEntry> entries = new ArrayList<>();

        /**
         * Split a list into pages of at most {@code maxBody} body bytes each (the pages are bodies: write the
         * header yourself). An empty list is one empty page.
         * <p>
         * BEHAVIOR: entries keep their order; a page is filled while the next entry still fits; every page is
         * flipped, ready to copy.
         */
        public static List<ByteBuffer> encode(int version, List<LobbyEntry> list, int maxBody) {
            List<List<LobbyEntry>> groups = new ArrayList<>();
            List<LobbyEntry> cur = new ArrayList<>();
            int size = HEAD;
            for (LobbyEntry e : list) {
                int n = entrySize(e);
                if (!cur.isEmpty() && size + n > maxBody) {
                    groups.add(cur);
                    cur = new ArrayList<>();
                    size = HEAD;
                }
                cur.add(e);
                size += n;
            }
            groups.add(cur);
            List<ByteBuffer> pages = new ArrayList<>(groups.size());
            for (int i = 0; i < groups.size(); i++) {
                ByteBuffer b = ByteBuffer.allocate(maxBody);
                b.putInt(version);
                b.putShort((short) i);
                b.putShort((short) groups.size());
                b.put((byte) groups.get(i).size());
                for (LobbyEntry e : groups.get(i)) {
                    writeEntry(b, e);
                }
                b.flip();
                pages.add(b);
            }
            return pages;
        }

        private static int entrySize(LobbyEntry e) {
            return 2 + 1 + 2 + 2 + 2 + 2 + Wire.sizeString8(e.name()) + Wire.sizeString8(e.title())
                + Wire.sizeString8(e.author());
        }

        private static void writeEntry(ByteBuffer b, LobbyEntry e) {
            b.putShort((short) e.id());
            b.put((byte) (e.active() ? FLAG_ACTIVE : 0));
            b.putShort((short) e.humans());
            b.putShort((short) e.maxPlayers());
            b.putShort((short) e.width());
            b.putShort((short) e.height());
            Wire.putString8(b, e.name());
            Wire.putString8(b, e.title());
            Wire.putString8(b, e.author());
        }

        /**
         * BEHAVIOR: reads a page into the fields and {@link #entries} (cleared first).
         * BEHAVIOR: a page number not below the page count is a decode error (IllegalArgumentException).
         */
        public void read(ByteBuffer b) {
            version = b.getInt();
            page = b.getShort() & 0xFFFF;
            pageCount = b.getShort() & 0xFFFF;
            int n = b.get() & 0xFF;
            if (pageCount == 0 || page >= pageCount) {
                throw new IllegalArgumentException("bad lobby page " + page + "/" + pageCount);
            }
            entries.clear();
            StringBuilder name = new StringBuilder();
            StringBuilder title = new StringBuilder();
            StringBuilder author = new StringBuilder();
            for (int i = 0; i < n; i++) {
                int id = b.getShort() & 0xFFFF;
                boolean active = (b.get() & FLAG_ACTIVE) != 0;
                int humans = b.getShort() & 0xFFFF;
                int max = b.getShort() & 0xFFFF;
                int w = b.getShort() & 0xFFFF;
                int h = b.getShort() & 0xFFFF;
                Wire.getString8(b, name);
                Wire.getString8(b, title);
                Wire.getString8(b, author);
                entries.add(new LobbyEntry(id, name.toString(), title.toString(), author.toString(), w, h, max,
                    active, humans));
            }
        }
    }

    /** C to S: u64 cookie, u16 map id, until a JOIN answers. */
    public static final class Select {
        public long cookie;
        public int mapId;

        public void write(ByteBuffer b) {
            b.putLong(cookie);
            b.putShort((short) mapId);
        }

        public void read(ByteBuffer b) {
            cookie = b.getLong();
            mapId = b.getShort() & 0xFFFF;
        }
    }

    /** S to C: u16 map id, u8 result ({@code JOIN_*}), str8 text. */
    public static final class Join {
        public int mapId;
        public int result;
        public final StringBuilder text = new StringBuilder();

        public void write(ByteBuffer b) {
            b.putShort((short) mapId);
            b.put((byte) result);
            Wire.putString8(b, text);
        }

        public void read(ByteBuffer b) {
            mapId = b.getShort() & 0xFFFF;
            result = b.get() & 0xFF;
            Wire.getString8(b, text);
        }
    }
}
