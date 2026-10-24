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
import com.fulcrologic.jxpilot.common.lobby.LobbyListAssembler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lobby datagram bodies and the list assembler. */
@DisplayName("Lobby messages")
class LobbyMsgTest {
    static LobbyEntry entry(int id, String name, boolean active, int humans) {
        return new LobbyEntry(id, name, "The " + name + " map", "by " + name, 100 + id, 50 + id, 16 + id, active, humans);
    }

    static ByteBuffer packet(int type, java.util.function.Consumer<ByteBuffer> body) {
        ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
        PacketHeader.write(b, type, 0, 0);
        body.accept(b);
        return b.flip();
    }

    @Nested
    @DisplayName("HELLO and INFO")
    class HelloInfo {
        @Test
        @DisplayName("a HELLO datagram is padded to the minimum size and its nonce round-trips")
        void hello() {
            LobbyMsg.Hello h = new LobbyMsg.Hello();
            h.nonce = 0xCAFEBABE;
            ByteBuffer p = packet(PacketType.LOBBY_HELLO, h::write);
            PacketHeader hd = new PacketHeader();
            hd.read(p);
            LobbyMsg.Hello r = new LobbyMsg.Hello();
            r.read(p);
            assertAll(
                () -> assertEquals(LobbyMsg.MIN_HELLO_SIZE, p.limit(), "the datagram is exactly the minimum size"),
                () -> assertEquals(PacketType.LOBBY_HELLO, hd.type(), "the header carries the HELLO type"),
                () -> assertEquals(0xCAFEBABE, r.nonce, "the nonce round-trips"));
        }

        @Test
        @DisplayName("INFO round-trips its nonce, cookie and name, and a long name is cut so INFO is never larger than HELLO")
        void info() {
            LobbyMsg.Info i = new LobbyMsg.Info();
            i.nonce = 7;
            i.cookie = 0x0123456789ABCDEFL;
            i.name.append("friends lobby");
            ByteBuffer p = packet(PacketType.LOBBY_INFO, i::write);
            p.position(PacketHeader.SIZE);
            LobbyMsg.Info r = new LobbyMsg.Info();
            r.read(p);

            LobbyMsg.Info longName = new LobbyMsg.Info();
            longName.name.append("x".repeat(200));
            ByteBuffer big = packet(PacketType.LOBBY_INFO, longName::write);
            assertAll(
                () -> assertEquals(7, r.nonce, "nonce"),
                () -> assertEquals(0x0123456789ABCDEFL, r.cookie, "cookie, all 64 bits"),
                () -> assertEquals("friends lobby", r.name.toString(), "name"),
                () -> assertEquals(LobbyMsg.MIN_HELLO_SIZE, big.limit(),
                    "a name longer than MAX_NAME fills INFO to exactly the HELLO size and no more"),
                () -> assertEquals(43, LobbyMsg.Info.MAX_NAME, "43 name characters fit"));
        }
    }

    @Nested
    @DisplayName("LIST_REQUEST, SELECT, JOIN")
    class Requests {
        @Test
        @DisplayName("each round-trips every field")
        void roundTrips() {
            LobbyMsg.ListRequest lr = new LobbyMsg.ListRequest();
            lr.cookie = -2L;
            lr.haveVersion = 0xFFFF_FFF0;
            ByteBuffer a = packet(PacketType.LOBBY_LIST_REQUEST, lr::write);
            a.position(PacketHeader.SIZE);
            LobbyMsg.ListRequest lr2 = new LobbyMsg.ListRequest();
            lr2.read(a);

            LobbyMsg.Select se = new LobbyMsg.Select();
            se.cookie = 99L;
            se.mapId = 65000;
            ByteBuffer b = packet(PacketType.LOBBY_SELECT, se::write);
            b.position(PacketHeader.SIZE);
            LobbyMsg.Select se2 = new LobbyMsg.Select();
            se2.read(b);

            LobbyMsg.Join j = new LobbyMsg.Join();
            j.mapId = 12;
            j.result = LobbyMsg.JOIN_FAILED;
            j.text.append("cannot start: bad map");
            ByteBuffer c = packet(PacketType.LOBBY_JOIN, j::write);
            c.position(PacketHeader.SIZE);
            LobbyMsg.Join j2 = new LobbyMsg.Join();
            j2.read(c);
            assertAll(
                () -> assertEquals(-2L, lr2.cookie, "request cookie"),
                () -> assertEquals(0xFFFF_FFF0, lr2.haveVersion, "request version (u32)"),
                () -> assertEquals(99L, se2.cookie, "select cookie"),
                () -> assertEquals(65000, se2.mapId, "select map id (u16)"),
                () -> assertEquals(12, j2.mapId, "join map id"),
                () -> assertEquals(LobbyMsg.JOIN_FAILED, j2.result, "join result"),
                () -> assertEquals("cannot start: bad map", j2.text.toString(), "join text"));
        }

        @Test
        @DisplayName("a truncated body throws instead of yielding a partial message")
        void truncated() {
            ByteBuffer p = ByteBuffer.allocate(8);
            p.putLong(0, 1L).limit(5);
            assertThrows(java.nio.BufferUnderflowException.class, () -> new LobbyMsg.ListRequest().read(p),
                "a request cut inside its cookie does not decode");
        }
    }

    @Nested
    @DisplayName("list pages")
    class Pages {
        @Test
        @DisplayName("entries round-trip through a page, including every field and Latin-1 text")
        void pageRoundTrip() {
            List<LobbyEntry> in = List.of(entry(3, "tourmination", true, 4), entry(900, "café", false, 0));
            List<ByteBuffer> pages = LobbyMsg.ListPage.encode(41, in, LobbyMsg.MAX_PAGE_BODY);
            LobbyMsg.ListPage p = new LobbyMsg.ListPage();
            p.read(pages.get(0));
            assertAll(
                () -> assertEquals(1, pages.size(), "two entries fit one page"),
                () -> assertEquals(41, p.version, "version"),
                () -> assertEquals(0, p.page, "page index"),
                () -> assertEquals(1, p.pageCount, "page count"),
                () -> assertEquals(in, p.entries, "entries equal field by field"));
        }

        @Test
        @DisplayName("a long list splits into pages that fit the body limit, in order, and reassemble to the list")
        void splitting() {
            List<LobbyEntry> in = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                in.add(entry(i, "map" + i, i % 3 == 0, i % 5));
            }
            List<ByteBuffer> pages = LobbyMsg.ListPage.encode(2, in, 300);
            List<LobbyEntry> out = new ArrayList<>();
            LobbyMsg.ListPage p = new LobbyMsg.ListPage();
            int total = 0;
            boolean fits = true;
            for (int i = 0; i < pages.size(); i++) {
                fits &= pages.get(i).remaining() <= 300;
                total += pages.get(i).remaining();
                p.read(pages.get(i));
                assertEquals(i, p.page, "page " + i + " is in order");
                assertEquals(pages.size(), p.pageCount, "every page states the page count");
                out.addAll(p.entries);
            }
            boolean allFit = fits;
            assertAll(
                () -> assertTrue(pages.size() > 5, "100 entries of ~45 bytes need many 300-byte pages"),
                () -> assertTrue(allFit, "no page exceeds the limit"),
                () -> assertEquals(in, out, "the pages concatenate to the list, order kept"));
        }

        @Test
        @DisplayName("an empty list is one empty page, so a client can tell it from no answer")
        void emptyList() {
            List<ByteBuffer> pages = LobbyMsg.ListPage.encode(5, List.of(), LobbyMsg.MAX_PAGE_BODY);
            LobbyMsg.ListPage p = new LobbyMsg.ListPage();
            p.read(pages.get(0));
            assertAll(
                () -> assertEquals(1, pages.size(), "one page"),
                () -> assertEquals(1, p.pageCount, "it says it is the only page"),
                () -> assertTrue(p.entries.isEmpty(), "with no entries"));
        }

        @Test
        @DisplayName("a page number outside its page count is refused")
        void badPageNumber() {
            ByteBuffer b = ByteBuffer.allocate(16);
            b.putInt(1).putShort((short) 3).putShort((short) 3).put((byte) 0).flip();
            assertThrows(IllegalArgumentException.class, () -> new LobbyMsg.ListPage().read(b),
                "page 3 of 3 does not exist");
        }

        @Test
        @DisplayName("the largest catalog (2048 maps with the longest text) needs fewer pages than a u16 can count")
        void maximumCatalog() {
            List<LobbyEntry> in = new ArrayList<>();
            for (int i = 0; i < LobbyMsg.MAX_MAPS; i++) {
                in.add(new LobbyEntry(i, "n".repeat(255), "t".repeat(255), "a".repeat(255), 1, 1, 1, false, 0));
            }
            List<ByteBuffer> pages = LobbyMsg.ListPage.encode(1, in, LobbyMsg.MAX_PAGE_BODY);
            LobbyMsg.ListPage p = new LobbyMsg.ListPage();
            p.read(pages.get(pages.size() - 1));
            assertAll(
                () -> assertEquals(LobbyMsg.MAX_MAPS, pages.size(), "worst case is one entry per page"),
                () -> assertEquals(LobbyMsg.MAX_MAPS - 1, p.page, "the last page index survives the u16 field"),
                () -> assertEquals(LobbyMsg.MAX_MAPS, p.pageCount, "and so does the page count"));
        }
    }

    @Nested
    @DisplayName("LobbyListAssembler")
    class Assembler {
        List<LobbyEntry> list(int n) {
            List<LobbyEntry> l = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                l.add(entry(i, "m" + i, false, 0));
            }
            return l;
        }

        @Test
        @DisplayName("pages in any order, with duplicates, complete the list exactly once")
        void outOfOrder() {
            LobbyListAssembler a = new LobbyListAssembler();
            List<LobbyEntry> one = list(1);
            List<LobbyEntry> two = List.of(entry(7, "x", true, 1));
            boolean r1 = a.accept(3, 1, 2, two);
            boolean dup = a.accept(3, 1, 2, two);
            boolean r2 = a.accept(3, 0, 2, one);
            boolean late = a.accept(3, 0, 2, one);
            assertAll(
                () -> assertFalse(r1, "one of two pages is not a list"),
                () -> assertFalse(dup, "a duplicate page changes nothing"),
                () -> assertTrue(r2, "the second page completes the list"),
                () -> assertFalse(late, "a page of the completed version is not completing it again"),
                () -> assertEquals(3, a.version(), "version"),
                () -> assertEquals(List.of(one.get(0), two.get(0)), a.entries(), "entries in page order"));
        }

        @Test
        @DisplayName("a newer version replaces a half-built one; older pages are ignored")
        void versions() {
            LobbyListAssembler a = new LobbyListAssembler();
            a.accept(5, 0, 2, list(1));
            boolean newer = a.accept(6, 0, 1, List.of(entry(1, "new", true, 2)));
            boolean older = a.accept(5, 1, 2, list(1));
            assertAll(
                () -> assertTrue(newer, "the single page of version 6 completes it"),
                () -> assertFalse(older, "the late page of version 5 is ignored"),
                () -> assertEquals(6, a.version(), "still version 6"),
                () -> assertEquals("new", a.entries().get(0).name(), "its entries"));
        }

        @Test
        @DisplayName("versions compare as a wrapping counter")
        void wrapping() {
            LobbyListAssembler a = new LobbyListAssembler();
            a.accept(-1, 0, 1, list(1));
            boolean wrapped = a.accept(1, 0, 1, List.of(entry(1, "after", false, 0)));
            assertAll(
                () -> assertTrue(wrapped, "1 is newer than 0xFFFFFFFF"),
                () -> assertEquals("after", a.entries().get(0).name(), "the newer list is held"));
        }

        @Test
        @DisplayName("before any page there is no version and no entries; a page with a different count is dropped")
        void startAndMismatch() {
            LobbyListAssembler a = new LobbyListAssembler();
            assertAll(
                () -> assertEquals(LobbyMsg.NO_VERSION, a.version(), "no version yet"),
                () -> assertTrue(a.entries().isEmpty(), "no entries yet"));
            a.accept(2, 0, 2, list(1));
            assertFalse(a.accept(2, 1, 3, list(1)), "page 1 of 3 does not belong to a 2-page assembly");
        }
    }
}
