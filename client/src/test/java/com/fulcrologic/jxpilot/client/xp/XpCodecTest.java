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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** Packet_scanf/Packet_printf equivalents and protocol constants. */
class XpCodecTest {

    private static XpReader reader(byte[] a) {
        return new XpReader().wrap(ByteBuffer.wrap(a), 0, a.length);
    }

    @Nested
    @DisplayName("XpReader fixed fields")
    class Fixed {
        @Test
        void readsBigEndianSignedAndUnsigned() {
            XpReader r = reader(new CPackets().c(0xFE).hd(-2).hd(0xFFFE).d(-5).d(0x01020304).bytes());
            assertAll(
                () -> assertTrue(r.has(13), "13 bytes available"),
                () -> assertFalse(r.has(14), "not 14"),
                () -> assertEquals(0xFE, r.peekU8(), "peek does not advance"),
                () -> assertEquals(0xFE, r.u8(), "%c is unsigned"),
                () -> assertEquals(-2, r.s16(), "%hd sign extends"),
                () -> assertEquals(0xFFFE, r.u16(), "%hu is unsigned"),
                () -> assertEquals(-5, r.s32(), "%d"),
                () -> assertEquals(0x01020304, r.s32(), "%ld big endian"),
                () -> assertEquals(0, r.remaining(), "all consumed"),
                () -> assertEquals(13, r.pos(), "position at end"),
                () -> assertEquals(13, r.limit(), "limit"));
        }

        @Test
        void wrapsASubRange() {
            byte[] a = {9, 1, 2, 9};
            XpReader r = new XpReader().wrap(ByteBuffer.wrap(a), 1, 3);
            r.skip(1);
            assertAll(
                () -> assertEquals(2, r.u8(), "skip then read inside the range"),
                () -> assertFalse(r.has(1), "range end is the limit"));
        }
    }

    @Nested
    @DisplayName("XpReader strings (C %s/%S)")
    class Strings {
        @Test
        void readsNulTerminatedLatin1() {
            XpReader r = reader(new CPackets().s("Bjørn").c(7).bytes());
            StringBuilder out = new StringBuilder("old");
            int n = r.string(out, XpProto.MAX_CHARS);
            assertAll(
                () -> assertEquals(6, n, "consumed chars plus the NUL"),
                () -> assertEquals("Bjørn", out.toString(), "Latin-1 decoded"),
                () -> assertEquals(7, r.u8(), "positioned after the NUL"));
        }

        @Test
        void missingNulIsIncompleteAndRestoresPosition() {
            XpReader r = reader(new byte[] {'a', 'b'});
            StringBuilder out = new StringBuilder();
            assertAll(
                () -> assertEquals(XpReader.INCOMPLETE, r.string(out, 80), "no NUL before the end: incomplete"),
                () -> assertEquals(0, r.pos(), "position unchanged"),
                () -> assertEquals("", out.toString(), "output cleared"));
        }

        @Test
        void maxMinusOneCharsFitAndMaxCharsOverflow() {
            byte[] fits = new CPackets().s("abc").bytes();
            byte[] over = new CPackets().s("abcd").bytes();
            StringBuilder out = new StringBuilder();
            XpReader a = reader(fits);
            XpReader b = reader(over);
            assertAll(
                () -> assertEquals(4, a.string(out, 4), "3 chars + NUL fit a limit of 4"),
                () -> assertEquals(XpReader.OVERFLOW, b.string(out, 4), "4 chars without NUL overflow a limit of 4"),
                () -> assertEquals(0, b.pos(), "overflow leaves the position"));
        }

        @Test
        void skipStringAdvancesOnlyOnSuccess() {
            XpReader r = reader(new CPackets().s("xy").bytes());
            XpReader bad = reader(new byte[] {'x'});
            assertAll(
                () -> assertEquals(3, r.skipString(80), "skips chars and NUL"),
                () -> assertEquals(3, r.pos(), "advanced"),
                () -> assertEquals(XpReader.INCOMPLETE, bad.skipString(80), "incomplete"),
                () -> assertEquals(0, bad.pos(), "not advanced"));
        }
    }

    @Nested
    @DisplayName("XpWriter")
    class Writer {
        @Test
        void writesFieldsBigEndian() {
            ByteBuffer b = ByteBuffer.allocate(16);
            XpWriter.u8(b, 0x1FF);
            XpWriter.s16(b, -2);
            XpWriter.s32(b, 0x01020304);
            assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFE, 1, 2, 3, 4},
                java.util.Arrays.copyOf(b.array(), b.position()), "%c low byte, %hd, %ld big endian");
        }

        @Test
        void stringTruncatesToMaxMinusOneAndReplacesUnencodableChars() {
            ByteBuffer b = ByteBuffer.allocate(16);
            int n = XpWriter.string(b, "aĀ\u0000bcdef", 6);
            assertAll(
                () -> assertEquals(6, n, "5 chars + NUL"),
                () -> assertArrayEquals(new byte[] {'a', '?', '?', 'b', 'c', 0},
                    java.util.Arrays.copyOf(b.array(), b.position()), "chars > 255 and NUL become '?', cut at 5"),
                () -> assertEquals(6, XpWriter.stringSize("aĀ\u0000bcdef", 6), "size matches"),
                () -> assertEquals(3, XpWriter.stringSize("ab", 6), "short string size"));
        }
    }

    @Nested
    @DisplayName("XpProto")
    class Proto {
        @Test
        void magicAndVersionConvert() {
            assertAll(
                () -> assertEquals(0x4501, XpProto.magicToVersion(XpProto.MAGIC), "C MAGIC is version 0x4501"),
                () -> assertEquals(XpProto.MAGIC, XpProto.versionToMagic(0x4501), "VERSION2MAGIC(0x4501) is MAGIC"),
                () -> assertEquals(0x4203F4ED, XpProto.versionToMagic(0x4203), "low word kept"));
        }

        @Test
        void statusTextsFollowTheCClient() {
            assertAll(
                () -> assertEquals("success", XpProto.statusText(XpProto.SUCCESS), "success"),
                () -> assertEquals("permission denied, not owner", XpProto.statusText(XpProto.E_NOT_OWNER), "owner"),
                () -> assertEquals("game full", XpProto.statusText(XpProto.E_GAME_FULL), "full"),
                () -> assertEquals("team full", XpProto.statusText(XpProto.E_TEAM_FULL), "team full"),
                () -> assertEquals("team play selected and no team specified",
                    XpProto.statusText(XpProto.E_TEAM_NOT_SET), "team not set"),
                () -> assertEquals("game locked", XpProto.statusText(XpProto.E_GAME_LOCKED), "locked"),
                () -> assertEquals("player not found", XpProto.statusText(XpProto.E_NOT_FOUND), "not found"),
                () -> assertEquals("nick already in use", XpProto.statusText(XpProto.E_IN_USE), "in use"),
                () -> assertEquals("server can't setup socket", XpProto.statusText(XpProto.E_SOCKET), "socket"),
                () -> assertEquals("invalid input parameters", XpProto.statusText(XpProto.E_INVAL), "inval"),
                () -> assertEquals("incompatible version", XpProto.statusText(XpProto.E_VERSION), "version"),
                () -> assertEquals("no such variable", XpProto.statusText(XpProto.E_NOENT), "noent"),
                () -> assertEquals("requested operation undefined", XpProto.statusText(XpProto.E_UNDEFINED), "undef"),
                () -> assertEquals("unknown error status 99", XpProto.statusText(99), "unknown code"));
        }
    }
}
