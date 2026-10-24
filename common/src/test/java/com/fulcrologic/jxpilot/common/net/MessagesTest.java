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

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Protocol messages")
class MessagesTest {

    static ByteBuffer buf() {
        return ByteBuffer.allocate(Protocol.MAX_PACKET);
    }

    @Nested
    @DisplayName("Wire quantization and strings")
    class WireTest {
        @Test
        @DisplayName("quantizers round, clamp and wrap as documented")
        void quantize() {
            assertAll(
                () -> assertEquals(3, Wire.posToU16(1.25), "positions round to the nearest half pixel (1.25 -> 1.5)"),
                () -> assertEquals(2, Wire.posToU16(1.24), "positions round down below the midpoint"),
                () -> assertEquals(0, Wire.posToU16(-5), "negative positions clamp to 0"),
                () -> assertEquals(0xFFFF, Wire.posToU16(40000), "positions clamp to 65535"),
                () -> assertEquals(32767.5f, Wire.u16ToPos((short) 0xFFFF), "decodes the top value unsigned"),
                () -> assertEquals(16, Wire.velToI16(1.0), "velocities are in sixteenths"),
                () -> assertEquals(-32768, Wire.velToI16(-5000), "velocities clamp low"),
                () -> assertEquals(32767, Wire.velToI16(5000), "velocities clamp high"),
                () -> assertEquals(-1.0f, Wire.i16ToVel(-16), "decodes negative velocities"),
                () -> assertEquals(-1.0f, Wire.i16ToVel(0xFFF0), "sign-extends an unsigned 16-bit velocity"),
                () -> assertEquals(16384, Wire.angleToU16(Math.PI / 2), "quarter turn is 16384"),
                () -> assertEquals(0, Wire.angleToU16(2 * Math.PI), "a full turn wraps to 0"),
                () -> assertEquals(49152, Wire.angleToU16(-Math.PI / 2), "negative angles wrap"),
                () -> assertEquals((float) Math.PI, Wire.u16ToAngle((short) 32768), 1e-6f,
                    "decodes the half turn unsigned (tolerance: float rounding)"),
                () -> assertEquals(65535, Wire.fracToU16(2.0), "fractions clamp high"),
                () -> assertEquals(32768, Wire.fracToU16(0.5), "fractions round to nearest (32767.5 -> 32768)"),
                () -> assertEquals(65535, Wire.fracToU16(1.0), "1.0 is exactly 65535"),
                () -> assertEquals(0, Wire.fracToU16(-1.0), "fractions clamp low"),
                () -> assertEquals(1.0f, Wire.u16ToFrac((short) 0xFFFF), "decodes the full fraction"),
                () -> assertEquals(-32766, Wire.relToI16(-16383), "relative -16383 px is -32766 half px"),
                () -> assertEquals(32766, Wire.relToI16(16383), "relative +16383 px is the largest allowed"),
                () -> assertEquals(3, Wire.relToI16(1.25), "relative offsets round to the nearest half pixel"),
                () -> assertEquals(-2, Wire.relToI16(-1.25), "negative midpoints round up"),
                () -> assertThrows(IllegalArgumentException.class, () -> Wire.relToI16(16383.5),
                    "offsets beyond +16383 px are rejected, never clamped"),
                () -> assertThrows(IllegalArgumentException.class, () -> Wire.relToI16(-16383.5),
                    "offsets beyond -16383 px are rejected"),
                () -> assertEquals(0, Wire.toRadar8(0, 1000), "radar cell of 0 is 0"),
                () -> assertEquals(255, Wire.toRadar8(999.9, 1000), "radar cell just below the size is 255"),
                () -> assertEquals(128, Wire.toRadar8(500, 1000), "radar cell of the middle is 128"),
                () -> assertEquals(127, Wire.toRadar8(499.9, 1000), "radar cells floor"),
                () -> assertEquals(0, Wire.toRadar8(-5, 1000), "negative radar coordinates clamp to 0"),
                () -> assertEquals(255, Wire.toRadar8(1000, 1000), "radar coordinates at the size clamp to 255"),
                () -> assertEquals(1.953125f, Wire.fromRadar8(0, 1000), "radar cell 0 decodes to its centre"),
                () -> assertEquals(998.046875f, Wire.fromRadar8((byte) 255, 1000), "radar cell 255 decodes unsigned"));
        }

        @Test
        @DisplayName("strings are length prefixed Latin-1, truncated and validated")
        void strings() {
            ByteBuffer b = buf();
            Wire.putString8(b, "hé€ÿ");
            Wire.putString16(b, "ÿ");
            ByteBuffer nb = ByteBuffer.allocate(4);
            Wire.putString16(nb, null);
            int nullWritten = nb.position();
            Wire.putString8(b, "x".repeat(300));
            b.flip();
            StringBuilder sb = new StringBuilder("junk");
            Wire.getString8(b, sb);
            String first = sb.toString();
            Wire.getString16(b, sb);
            String second = sb.toString();
            Wire.getString8(b, sb);
            int third = sb.length();
            ByteBuffer lying = ByteBuffer.wrap(new byte[] {5, 'a'});
            assertAll(
                () -> assertEquals("hé?ÿ", first, "keeps Latin-1 up to U+00FF and replaces other chars with '?'"),
                () -> assertEquals("ÿ", second, "u16 strings keep U+00FF"),
                () -> assertEquals(2, nullWritten, "null writes an empty u16 string (just the prefix)"),
                () -> assertEquals(255, third, "u8 strings truncate to 255 chars"),
                () -> assertEquals(4, Wire.sizeString8("abc"), "sizeString8 counts prefix and chars"),
                () -> assertEquals(256, Wire.sizeString8("y".repeat(999)), "sizeString8 accounts for truncation"),
                () -> assertEquals(5, Wire.sizeString16("abc"), "sizeString16 counts prefix and chars"),
                () -> assertEquals(2, Wire.sizeString16(null), "sizeString16 of null is the prefix"),
                () -> assertThrows(java.nio.BufferUnderflowException.class, () -> Wire.getString8(lying, sb),
                    "a length beyond the buffer is rejected"));
        }

        @Test
        @DisplayName("strings are clamped to the space left in the buffer")
        void clamped() {
            ByteBuffer small = ByteBuffer.allocate(10);
            Wire.putString16(small, "x".repeat(2000));
            ByteBuffer small8 = ByteBuffer.allocate(4);
            Wire.putString8(small8, "abcdef");
            small.flip();
            small8.flip();
            StringBuilder sb = new StringBuilder();
            Wire.getString16(small, sb);
            String s16 = sb.toString();
            Wire.getString8(small8, sb);
            assertAll(
                () -> assertEquals("x".repeat(8), s16, "u16 strings keep only what fits after the prefix"),
                () -> assertEquals("abc", sb.toString(), "u8 strings keep only what fits after the prefix"));
        }

        @Test
        @DisplayName("long ship shapes are capped on decode and never overflow PLAYER_JOIN")
        void longShapes() {
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.name.append("n");
            j.shape.append("(SH: 1,1)".repeat(300));
            ReliableChannel ch = new ReliableChannel();
            j.send(ch);
            ByteBuffer p = buf();
            ch.write(p, 0);
            p.flip();
            Msg.PlayerJoin j2 = new Msg.PlayerJoin();
            new ReliableChannel().read(p, (type, payload) -> j2.read(payload));
            Msg.Shape s = new Msg.Shape();
            ByteBuffer b = ByteBuffer.allocate(1000);
            s.shape.append("y".repeat(900));
            s.write(b);
            b.flip();
            Msg.Shape s2 = new Msg.Shape();
            s2.read(b);
            Msg.Connect c = new Msg.Connect();
            c.shape.append("z".repeat(600));
            ByteBuffer cb = ByteBuffer.allocate(1000);
            c.write(cb);
            cb.flip();
            Msg.Connect c2 = new Msg.Connect();
            c2.read(cb);
            assertAll(
                () -> assertEquals(Msg.MAX_SHAPE_LEN, j2.shape.length(), "player join shape is capped at MAX_SHAPE_LEN"),
                () -> assertEquals("n", j2.name.toString(), "the name survives"),
                () -> assertEquals(Msg.MAX_SHAPE_LEN, s2.shape.length(), "shape message is capped at MAX_SHAPE_LEN"),
                () -> assertEquals(Msg.MAX_SHAPE_LEN, c2.shape.length(), "connect shape is capped at MAX_SHAPE_LEN"));
        }

        @Test
        @DisplayName("Connect.tryRead refuses every truncation without throwing and reads a complete body")
        void connectTryRead() {
            Msg.Connect c = new Msg.Connect();
            c.nonce = 77;
            c.team = 2;
            c.name.append("ab");
            c.shape.append("xyz");
            ByteBuffer full = ByteBuffer.allocate(64);
            c.write(full);
            full.flip();
            int size = full.remaining();
            List<Integer> accepted = new ArrayList<>();
            List<Integer> moved = new ArrayList<>();
            for (int n = 0; n <= size; n++) {
                ByteBuffer b = ByteBuffer.allocate(80);
                b.position(3);
                b.put(full.duplicate().limit(n));
                b.flip().position(3);
                if (new Msg.Connect().tryRead(b)) {
                    accepted.add(n);
                } else if (b.position() != 3) {
                    moved.add(n);
                }
            }
            ByteBuffer withTrailer = ByteBuffer.allocate(64).put(full.duplicate()).put((byte) 9).flip();
            Msg.Connect r = new Msg.Connect();
            boolean ok = r.tryRead(withTrailer);
            assertAll(
                () -> assertEquals(13, size, "the test body is 13 bytes"),
                () -> assertEquals(List.of(13), accepted, "only the complete body is accepted (every shorter prefix refused)"),
                () -> assertEquals(List.of(), moved, "a refused body leaves the position unchanged"),
                () -> assertTrue(ok, "a body followed by extra bytes is read"),
                () -> assertEquals(77, r.nonce, "tryRead fills the nonce"),
                () -> assertEquals(2, r.team, "and the team"),
                () -> assertEquals("ab", r.name.toString(), "and the name"),
                () -> assertEquals("xyz", r.shape.toString(), "and the shape"),
                () -> assertEquals(1, withTrailer.remaining(), "and stops at the end of the body"));
        }

        @Test
        @DisplayName("unsigned accessors")
        void unsigned() {
            ByteBuffer b = ByteBuffer.wrap(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
            assertAll(
                () -> assertEquals(255, Wire.u8(b, 0), "u8 is unsigned"),
                () -> assertEquals(65535, Wire.u16(b, 0), "u16 is unsigned"),
                () -> assertEquals(-1, Wire.s16(b, 0), "s16 is signed"),
                () -> assertEquals(0xFFFFFFFFL, Wire.u32(b, 0), "u32 is unsigned"));
        }
    }

    @Nested
    @DisplayName("PacketHeader")
    class Header {
        @Test
        @DisplayName("round trips and rejects bad magic, version and short buffers")
        void header() {
            ByteBuffer b = buf();
            PacketHeader.write(b, PacketType.SNAPSHOT, 0xABCD, 0xFFFE);
            b.flip();
            PacketHeader h = new PacketHeader();
            boolean ok = h.read(b);
            int posAfter = b.position();
            ByteBuffer badMagic = ByteBuffer.wrap(new byte[] {0, 0, 1, 1, 0, 0, 0, 0});
            ByteBuffer badVersion = ByteBuffer.wrap(new byte[] {0x4A, 0x58, 9, 1, 0, 0, 0, 0});
            ByteBuffer shortBuf = ByteBuffer.wrap(new byte[] {0x4A, 0x58, 1});
            boolean magicOk = h.read(badMagic);
            boolean versionOk = h.read(badVersion);
            boolean shortOk = h.read(shortBuf);
            assertAll(
                () -> assertTrue(ok, "accepts its own header"),
                () -> assertEquals(PacketHeader.SIZE, posAfter, "advances past the header"),
                () -> assertEquals(PacketType.SNAPSHOT, h.type(), "keeps the type"),
                () -> assertEquals(0xABCD, h.session(), "keeps the session unsigned"),
                () -> assertEquals(0xFFFE, h.reliableAck(), "keeps the ack unsigned"),
                () -> assertFalse(magicOk, "rejects a wrong magic"),
                () -> assertEquals(0, badMagic.position(), "does not consume a rejected header"),
                () -> assertFalse(versionOk, "rejects a wrong version"),
                () -> assertFalse(shortOk, "rejects a short buffer"),
                () -> assertEquals("SNAPSHOT", PacketType.name(PacketType.SNAPSHOT), "names packet types"),
                () -> assertEquals("UNKNOWN", PacketType.name(99), "names unknown packet types"));
        }
    }

    @Nested
    @DisplayName("Msg codecs")
    class Codecs {
        @Test
        @DisplayName("TIMING round trips id (u16), check (u8) and round (u16) in 5 bytes")
        void timing() {
            ByteBuffer b = buf();
            Msg.Timing t = new Msg.Timing();
            t.id = 40000;
            t.check = 25;
            t.round = 65535;
            t.write(b);
            int len = b.position();
            b.flip();
            Msg.Timing t2 = new Msg.Timing();
            t2.read(b);
            assertAll(
                () -> assertEquals(5, len, "5 bytes"),
                () -> assertEquals(40000, t2.id, "id"),
                () -> assertEquals(25, t2.check, "check"),
                () -> assertEquals(65535, t2.round, "round"));
        }

        @Test
        @DisplayName("control messages round trip")
        void control() {
            ByteBuffer b = buf();
            Msg.Connect c = new Msg.Connect();
            c.nonce = 0xCAFEBABE;
            c.team = 3;
            c.name.append("Tony");
            c.shape.append("(SH: 15,0 -9,8 -9,-8)");
            c.write(b);
            Msg.Accept a = new Msg.Accept();
            a.nonce = 7;
            a.playerId = 40000;
            a.write(b);
            Msg.Reject rj = new Msg.Reject();
            rj.nonce = 8;
            rj.reason = Msg.Reject.REASON_FULL;
            rj.text.append("full");
            rj.write(b);
            Msg.Disconnect d = new Msg.Disconnect();
            d.reason = Msg.Disconnect.REASON_TIMEOUT;
            d.text.append("bye");
            d.write(b);
            Msg.Ping p = new Msg.Ping();
            p.id = 5;
            p.timeNanos = Long.MAX_VALUE;
            p.write(b);
            b.flip();
            Msg.Connect c2 = new Msg.Connect();
            c2.read(b);
            Msg.Accept a2 = new Msg.Accept();
            a2.read(b);
            Msg.Reject rj2 = new Msg.Reject();
            rj2.read(b);
            Msg.Disconnect d2 = new Msg.Disconnect();
            d2.read(b);
            Msg.Ping p2 = new Msg.Ping();
            p2.read(b);
            assertAll(
                () -> assertEquals(0xCAFEBABE, c2.nonce, "connect keeps nonce"),
                () -> assertEquals(3, c2.team, "connect keeps team"),
                () -> assertEquals("Tony", c2.name.toString(), "connect keeps name"),
                () -> assertEquals("(SH: 15,0 -9,8 -9,-8)", c2.shape.toString(), "connect keeps shape"),
                () -> assertEquals(7, a2.nonce, "accept keeps nonce"),
                () -> assertEquals(40000, a2.playerId, "accept keeps player id unsigned"),
                () -> assertEquals(8, rj2.nonce, "reject keeps nonce"),
                () -> assertEquals(Msg.Reject.REASON_FULL, rj2.reason, "reject keeps reason"),
                () -> assertEquals("full", rj2.text.toString(), "reject keeps text"),
                () -> assertEquals(Msg.Disconnect.REASON_TIMEOUT, d2.reason, "disconnect keeps reason"),
                () -> assertEquals("bye", d2.text.toString(), "disconnect keeps text"),
                () -> assertEquals(5, p2.id, "ping keeps id"),
                () -> assertEquals(Long.MAX_VALUE, p2.timeNanos, "ping keeps time"),
                () -> assertFalse(b.hasRemaining(), "consumes exactly what was written"));
        }

        /** Captures reliable messages for inspection. */
        static final class Capture implements ReliableHandler {
            final List<Integer> types = new ArrayList<>();
            final List<ByteBuffer> payloads = new ArrayList<>();

            @Override
            public void onReliableMessage(int type, ByteBuffer payload) {
                types.add(type);
                ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
                copy.put(payload).flip();
                payloads.add(copy);
            }
        }

        @Test
        @DisplayName("reliable messages round trip through a channel with their type ids")
        void reliable() {
            ReliableChannel tx = new ReliableChannel();
            Msg.Welcome w = new Msg.Welcome();
            w.playerId = 3;
            w.team = 2;
            w.simHz = 60;
            w.snapshotHz = 30;
            w.flags = Msg.Welcome.FLAG_EDGE_WRAP;
            w.gameFps = 14f;
            w.configLength = 1000;
            w.configCompressedLength = 300;
            w.configCrc32 = -5;
            w.serverName.append("srv");
            w.send(tx);
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = 4;
            j.team = 1;
            j.flags = Msg.PlayerJoin.FLAG_ROBOT;
            j.name.append("Robby");
            j.send(tx);
            Msg.PlayerLeave l = new Msg.PlayerLeave();
            l.id = 4;
            l.send(tx);
            Msg.PlayerTeam pt = new Msg.PlayerTeam();
            pt.id = 4;
            pt.team = 7;
            pt.send(tx);
            Msg.Score s = new Msg.Score();
            s.id = 4;
            s.score = -12.5f;
            s.lives = -1;
            s.status = Msg.Score.STATUS_DEAD;
            s.send(tx);
            Msg.TeamScore ts = new Msg.TeamScore();
            ts.team = 2;
            ts.score = 99f;
            ts.send(tx);
            Msg.Text t = new Msg.Text();
            t.kind = Msg.Text.KIND_SERVER;
            t.text.append("hello");
            t.send(tx);
            t.sendChat(tx);
            Msg.BaseOwner bo = new Msg.BaseOwner();
            bo.baseIndex = 12;
            bo.playerId = 4;
            bo.send(tx);
            Msg.GameEvent ge = new Msg.GameEvent();
            ge.kind = Msg.GameEvent.KILL;
            ge.a = 1;
            ge.b = 2;
            ge.value = 3f;
            ge.send(tx);
            Msg.Settings st = new Msg.Settings();
            st.power = 50f;
            st.flags = Msg.Settings.FLAG_AUTO_SHIELD;
            st.send(tx);
            Msg.ChangeTeam ct = new Msg.ChangeTeam();
            ct.team = 9;
            ct.send(tx);
            Msg.Shape sh = new Msg.Shape();
            sh.shape.append("(SH: 1,1)");
            sh.send(tx);
            Msg.sendReady(tx);
            Msg.DataChunk.send(tx, new byte[] {9, 8, 7, 6}, 1, 2);

            ReliableChannel rx = new ReliableChannel();
            Capture cap = new Capture();
            ByteBuffer p = buf();
            tx.write(p, 0);
            p.flip();
            rx.read(p, cap);

            Msg.Welcome w2 = new Msg.Welcome();
            w2.read(cap.payloads.get(0));
            Msg.PlayerJoin j2 = new Msg.PlayerJoin();
            j2.read(cap.payloads.get(1));
            Msg.PlayerLeave l2 = new Msg.PlayerLeave();
            l2.read(cap.payloads.get(2));
            Msg.PlayerTeam pt2 = new Msg.PlayerTeam();
            pt2.read(cap.payloads.get(3));
            Msg.Score s2 = new Msg.Score();
            s2.read(cap.payloads.get(4));
            Msg.TeamScore ts2 = new Msg.TeamScore();
            ts2.read(cap.payloads.get(5));
            Msg.Text t2 = new Msg.Text();
            t2.read(cap.payloads.get(6));
            Msg.BaseOwner bo2 = new Msg.BaseOwner();
            bo2.read(cap.payloads.get(8));
            Msg.GameEvent ge2 = new Msg.GameEvent();
            ge2.read(cap.payloads.get(9));
            Msg.Settings st2 = new Msg.Settings();
            st2.read(cap.payloads.get(10));
            Msg.ChangeTeam ct2 = new Msg.ChangeTeam();
            ct2.read(cap.payloads.get(11));
            Msg.Shape sh2 = new Msg.Shape();
            sh2.read(cap.payloads.get(12));
            byte[] dest = new byte[4];
            int end = Msg.DataChunk.read(cap.payloads.get(14), dest);

            assertAll(
                () -> assertEquals(List.of(ReliableType.WELCOME, ReliableType.PLAYER_JOIN, ReliableType.PLAYER_LEAVE,
                    ReliableType.PLAYER_TEAM, ReliableType.SCORE, ReliableType.TEAM_SCORE, ReliableType.MESSAGE,
                    ReliableType.CHAT, ReliableType.BASE_OWNER, ReliableType.GAME_EVENT, ReliableType.SETTINGS,
                    ReliableType.CHANGE_TEAM, ReliableType.SHAPE, ReliableType.READY, ReliableType.DATA_CHUNK),
                    cap.types, "each send uses its reliable type id, in order"),
                () -> assertEquals(3, w2.playerId, "welcome keeps player id"),
                () -> assertEquals(2, w2.team, "welcome keeps team"),
                () -> assertEquals(60, w2.simHz, "welcome keeps simHz"),
                () -> assertEquals(30, w2.snapshotHz, "welcome keeps snapshotHz"),
                () -> assertEquals(Msg.Welcome.FLAG_EDGE_WRAP, w2.flags, "welcome keeps flags"),
                () -> assertEquals(14f, w2.gameFps, "welcome keeps gameFps"),
                () -> assertEquals(1000, w2.configLength, "welcome keeps config length"),
                () -> assertEquals(300, w2.configCompressedLength, "welcome keeps compressed length"),
                () -> assertEquals(-5, w2.configCrc32, "welcome keeps crc"),
                () -> assertEquals("srv", w2.serverName.toString(), "welcome keeps server name"),
                () -> assertEquals("4/1/1/Robby/", j2.id + "/" + j2.team + "/" + j2.flags + "/" + j2.name + "/" + j2.shape,
                    "player join keeps id, team, flags, name and empty shape"),
                () -> assertEquals(4, l2.id, "player leave keeps id"),
                () -> assertEquals(7, pt2.team, "player team keeps team"),
                () -> assertEquals(4, pt2.id, "player team keeps id"),
                () -> assertEquals(-12.5f, s2.score, "score keeps negative score"),
                () -> assertEquals(-1, s2.lives, "score keeps lives sign"),
                () -> assertEquals(Msg.Score.STATUS_DEAD, s2.status, "score keeps status"),
                () -> assertEquals(4, s2.id, "score keeps id"),
                () -> assertEquals(2, ts2.team, "team score keeps team"),
                () -> assertEquals(99f, ts2.score, "team score keeps score"),
                () -> assertEquals(Msg.Text.KIND_SERVER, t2.kind, "text keeps kind"),
                () -> assertEquals(Protocol.NO_ID, t2.from, "text from defaults to the server id"),
                () -> assertEquals("hello", t2.text.toString(), "text keeps text"),
                () -> assertEquals(12, bo2.baseIndex, "base owner keeps base"),
                () -> assertEquals(4, bo2.playerId, "base owner keeps player"),
                () -> assertEquals("1/1/2/3.0", ge2.kind + "/" + ge2.a + "/" + ge2.b + "/" + ge2.value,
                    "game event keeps kind, ids and value"),
                () -> assertEquals(50f, st2.power, "settings keep power"),
                () -> assertEquals(35f, st2.turnSpeed, "settings default turn speed is XPilot's 35"),
                () -> assertEquals(0.12f, st2.turnResistance, "settings default turn resistance is 0.12"),
                () -> assertEquals(35f, st2.altPower, "settings default alt power is 35"),
                () -> assertEquals(25f, st2.altTurnSpeed, "settings default alt turn speed is 25"),
                () -> assertEquals(0.12f, st2.altTurnResistance, "settings default alt resistance is 0.12"),
                () -> assertEquals(Msg.Settings.FLAG_AUTO_SHIELD, st2.flags, "settings keep flags"),
                () -> assertEquals(9, ct2.team, "change team keeps team"),
                () -> assertEquals("(SH: 1,1)", sh2.shape.toString(), "shape keeps shape"),
                () -> assertEquals(0, cap.payloads.get(13).remaining(), "ready has an empty body"),
                () -> assertEquals(3, end, "data chunk returns offset + length"),
                () -> assertEquals("[0, 8, 7, 0]", java.util.Arrays.toString(dest), "data chunk copies to its offset"));
        }

        @Test
        @DisplayName("DISPLAY: u16 width and height round trip with type 15; out-of-range values saturate")
        void display() {
            ReliableChannel tx = new ReliableChannel();
            Msg.Display d = new Msg.Display();
            Msg.Display defaults = new Msg.Display();
            d.viewWidth = 1100;
            d.viewHeight = 400;
            d.send(tx);
            d.viewWidth = 70000;
            d.viewHeight = -5;
            d.send(tx);
            d.viewWidth = 0xFFFF;
            d.viewHeight = 40000;
            d.send(tx);
            ReliableChannel rx = new ReliableChannel();
            Capture cap = new Capture();
            ByteBuffer p = buf();
            tx.write(p, 0);
            p.flip();
            rx.read(p, cap);
            int bodySize = cap.payloads.get(0).remaining();
            Msg.Display a = new Msg.Display();
            a.read(cap.payloads.get(0));
            Msg.Display sat = new Msg.Display();
            sat.read(cap.payloads.get(1));
            Msg.Display top = new Msg.Display();
            top.read(cap.payloads.get(2));
            assertAll(
                () -> assertEquals(List.of(15, 15, 15), cap.types, "DISPLAY is reliable type 15"),
                () -> assertEquals(4, bodySize, "the body is two u16"),
                () -> assertEquals(1100, a.viewWidth, "width round trips"),
                () -> assertEquals(400, a.viewHeight, "height round trips"),
                () -> assertEquals(0xFFFF, sat.viewWidth, "a width above 65535 saturates to 65535"),
                () -> assertEquals(0, sat.viewHeight, "a negative height saturates to 0"),
                () -> assertEquals(0xFFFF, top.viewWidth, "65535 is read unsigned"),
                () -> assertEquals(40000, top.viewHeight, "values above 32767 are read unsigned"),
                () -> assertEquals(768, defaults.viewWidth, "default width is C DEF_VIEW_SIZE"),
                () -> assertEquals(768, defaults.viewHeight, "default height is C DEF_VIEW_SIZE"));
        }

        @Test
        @DisplayName("over-long texts are truncated to the slot and a failing payload leaves the channel usable")
        void longTextAndFailure() {
            ReliableChannel tx = new ReliableChannel(4, Protocol.MAX_RELIABLE_MESSAGE, 1);
            Msg.Text t = new Msg.Text();
            t.text.append("y".repeat(5000));
            t.send(tx);
            Throwable failed = assertThrows(IllegalStateException.class,
                () -> Msg.send(tx, b -> { throw new IllegalStateException("boom"); }, ReliableType.MESSAGE));
            int unackedAfterFailure = tx.unacked();
            Msg.sendReady(tx);
            ReliableChannel rx = new ReliableChannel(4, Protocol.MAX_RELIABLE_MESSAGE, 1);
            Capture cap = new Capture();
            ByteBuffer p = ByteBuffer.allocate(3 * Protocol.MAX_PACKET);
            tx.write(p, 0);
            p.flip();
            rx.read(p, cap);
            Msg.Text got = new Msg.Text();
            got.read(cap.payloads.get(0));
            Throwable bigChunk = assertThrows(IllegalArgumentException.class,
                () -> Msg.DataChunk.send(tx, new byte[2000], 0, 2000));
            int unackedAfterRefusal = tx.unacked();
            Msg.sendReady(tx);
            int readyAfterRefusal = tx.unacked();
            assertAll(
                () -> assertEquals(Protocol.MAX_RELIABLE_MESSAGE - 1 - 1 - 2 - 2, got.text.length(),
                    "text is cut to what fits in the 1100-byte slot after type, kind, sender and length"),
                () -> assertEquals("boom", failed.getMessage(), "the payload's exception propagates"),
                () -> assertEquals(1, unackedAfterFailure, "a failed send queues nothing"),
                () -> assertEquals(List.of(ReliableType.MESSAGE, ReliableType.READY), cap.types,
                    "the channel keeps working after a failed send"),
                () -> assertEquals("chunk of 2000 bytes exceeds the reliable slot", bigChunk.getMessage(),
                    "an oversized data chunk is refused"),
                () -> assertEquals(2, unackedAfterRefusal, "the refused chunk left nothing queued or open"),
                () -> assertEquals(3, readyAfterRefusal, "the channel accepts a new message after the refusal"));
        }

        @Test
        @DisplayName("thrust appearance travels in PLAYER_JOIN and PLAYER_THRUST")
        void thrust() {
            ReliableChannel tx = new ReliableChannel();
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = 3;
            j.power = 55.5f;
            j.afterburners = 15;
            j.name.append("p");
            j.send(tx);
            Msg.PlayerThrust t = new Msg.PlayerThrust();
            t.id = 3;
            t.power = 20.25f;
            t.afterburners = 7;
            t.send(tx);
            ByteBuffer p = buf();
            tx.write(p, 0);
            p.flip();
            Capture cap = new Capture();
            new ReliableChannel().read(p, cap);
            Msg.PlayerJoin j2 = new Msg.PlayerJoin();
            j2.read(cap.payloads.get(0));
            Msg.PlayerThrust t2 = new Msg.PlayerThrust();
            t2.read(cap.payloads.get(1));
            assertAll(
                () -> assertEquals(List.of(ReliableType.PLAYER_JOIN, ReliableType.PLAYER_THRUST), cap.types,
                    "join then thrust update"),
                () -> assertEquals(55.5f, j2.power, "join carries power"),
                () -> assertEquals(15, j2.afterburners, "join carries afterburners"),
                () -> assertEquals("p", j2.name.toString(), "join still carries the name after the new fields"),
                () -> assertEquals(45.0f, new Msg.PlayerJoin().power, "default power is XPilot's 45"),
                () -> assertEquals(3, t2.id, "thrust update carries the id"),
                () -> assertEquals(20.25f, t2.power, "thrust update carries power"),
                () -> assertEquals(7, t2.afterburners, "thrust update carries afterburners"));
        }

        @Test
        @DisplayName("SCORE_OBJECT carries score, block position and label")
        void scoreObject() {
            ReliableChannel tx = new ReliableChannel();
            Msg.ScoreObject o = new Msg.ScoreObject();
            o.score = -12.5f;
            o.bx = 300;
            o.by = 65535;
            o.text.append("[Wall]");
            o.send(tx);
            ByteBuffer p = buf();
            tx.write(p, 0);
            p.flip();
            Capture cap = new Capture();
            new ReliableChannel().read(p, cap);
            Msg.ScoreObject o2 = new Msg.ScoreObject();
            o2.read(cap.payloads.get(0));
            assertAll(
                () -> assertEquals(List.of(ReliableType.SCORE_OBJECT), cap.types, "sent as SCORE_OBJECT (12)"),
                () -> assertEquals(12, ReliableType.SCORE_OBJECT, "type id 12"),
                () -> assertEquals(-12.5f, o2.score, "carries the (negative) score"),
                () -> assertEquals(300, o2.bx, "carries block x"),
                () -> assertEquals(65535, o2.by, "carries block y as u16"),
                () -> assertEquals("[Wall]", o2.text.toString(), "carries the label"));
        }

        @Test
        @DisplayName("a data chunk past the destination is rejected")
        void chunkOutOfRange() {
            ByteBuffer b = ByteBuffer.allocate(8);
            b.putInt(3).put(new byte[] {1, 2}).flip();
            ByteBuffer neg = ByteBuffer.allocate(5);
            neg.putInt(-1).put((byte) 1).flip();
            ByteBuffer huge = ByteBuffer.allocate(6);
            huge.putInt(Integer.MAX_VALUE - 1).put(new byte[] {1, 2}).flip();
            ByteBuffer emptyAtEnd = ByteBuffer.allocate(4);
            emptyAtEnd.putInt(4).flip();
            ReliableChannel fullSlot = new ReliableChannel();
            Msg.DataChunk.send(fullSlot, new byte[Protocol.MAX_RELIABLE_MESSAGE - 5], 0, Protocol.MAX_RELIABLE_MESSAGE - 5);
            Msg.Welcome w0 = new Msg.Welcome();
            w0.configCompressedLength = 1;
            ByteBuffer empty0 = ByteBuffer.allocate(4);
            empty0.putInt(0).flip();
            boolean emptyFirst = new ConfigTransfer.Receiver(w0).onChunk(empty0);
            ByteBuffer exact = ByteBuffer.allocate(6);
            exact.putInt(2).put(new byte[] {1, 2}).flip();
            assertAll(
                () -> assertEquals(-1, Msg.DataChunk.read(b, new byte[4]), "rejects a chunk that overruns"),
                () -> assertEquals(-1, Msg.DataChunk.read(neg, new byte[4]), "rejects a negative offset"),
                () -> assertEquals(-1, Msg.DataChunk.read(huge, new byte[4]), "rejects an offset near Integer.MAX_VALUE"),
                () -> assertEquals(4, Msg.DataChunk.read(exact, new byte[4]), "accepts a chunk ending exactly at the end"),
                () -> assertEquals(4, Msg.DataChunk.read(emptyAtEnd, new byte[4]), "accepts an empty chunk at the end"),
                () -> assertEquals(1, fullSlot.unacked(), "a chunk exactly filling the 1100-byte slot is accepted"),
                () -> assertFalse(emptyFirst, "an empty first chunk is accepted and is not completion"));
        }
    }

    @Nested
    @DisplayName("Input history/reader")
    class Input {
        @Test
        @DisplayName("writes newest-first redundant entries that the reader decodes")
        void roundTrip() {
            InputHistory h = new InputHistory();
            for (int i = 1; i <= 10; i++) {
                h.record(ControlKey.THRUST.mask() * i, -i);
            }
            ByteBuffer b = buf();
            h.write(b, 555, 3);
            b.flip();
            InputReader r = new InputReader();
            boolean ok = r.wrap(b);
            InputHistory seqs = new InputHistory();
            int firstSeq = seqs.record(1L, 0);
            int secondSeq = seqs.record(2L, 0);
            InputHistory few = new InputHistory();
            few.record(1L, 0);
            ByteBuffer b2 = buf();
            few.write(b2, 0, 8);
            b2.flip();
            InputReader r2 = new InputReader();
            r2.wrap(b2);
            ByteBuffer wb = buf();
            h.write(wb, 0, 3);
            int written = wb.position();
            ByteBuffer headerOnly = ByteBuffer.allocate(InputHistory.HEADER_SIZE);
            headerOnly.putInt(1).putInt(1).put((byte) 0).flip();
            ByteBuffer eight = buf();
            h.write(eight, 0, 8);
            eight.flip();
            boolean eightOk = new InputReader().wrap(eight);
            ByteBuffer shortB = ByteBuffer.allocate(5);
            ByteBuffer lying = ByteBuffer.allocate(20);
            lying.putInt(1).putInt(1).put((byte) 3).flip();
            ByteBuffer tooMany = ByteBuffer.allocate(200);
            tooMany.putInt(1).putInt(1).put((byte) 9).position(200).flip();
            assertAll(
                () -> assertTrue(ok, "accepts a well formed body"),
                () -> assertEquals(1, firstSeq, "record returns sequence 1 first"),
                () -> assertEquals(2, secondSeq, "record returns increasing sequences"),
                () -> assertEquals(10, r.latestSeq(), "carries the newest sequence"),
                () -> assertEquals(10, h.latestSeq(), "history reports the newest sequence"),
                () -> assertEquals(555, r.ackTick(), "carries the acked tick"),
                () -> assertEquals(3, r.count(), "limits entries to the requested redundancy"),
                () -> assertEquals(9, r.seq(1), "entry i has sequence latest - i"),
                () -> assertEquals(ControlKey.THRUST.mask() * 10, r.keys(0), "newest entry first"),
                () -> assertEquals(ControlKey.THRUST.mask() * 8, r.keys(2), "older entries follow"),
                () -> assertEquals(-9, r.aimTurn(1), "keeps signed aim turns"),
                () -> assertEquals(1, r2.count(), "never sends more entries than recorded"),
                () -> assertFalse(new InputReader().wrap(shortB), "rejects a short body"),
                () -> assertFalse(new InputReader().wrap(lying), "rejects a count larger than the body"),
                () -> assertFalse(new InputReader().wrap(tooMany), "rejects more than MAX_REDUNDANCY entries"),
                () -> assertEquals(InputHistory.HEADER_SIZE + 3 * InputHistory.ENTRY_SIZE, written,
                    "write emits exactly header + n entries"),
                () -> assertTrue(new InputReader().wrap(headerOnly), "a body with zero entries is valid"),
                () -> assertTrue(eightOk, "exactly MAX_REDUNDANCY entries are accepted"));
        }
    }

    @Nested
    @DisplayName("ControlKey")
    class Keys {
        @Test
        @DisplayName("mirrors keys.h ids and bit operations")
        void keys() {
            long m0 = ControlKey.THRUST.set(0L, true);
            long m = ControlKey.FIRE_SHOT.set(m0, true);
            long cleared = ControlKey.THRUST.set(m, false);
            assertAll(
                () -> assertEquals(64, ControlKey.values().length, "exactly 64 game keys fit a long"),
                () -> assertEquals(1, ControlKey.LOCK_NEXT.xpilotId, "LOCK_NEXT is KEY 1"),
                () -> assertEquals(5, ControlKey.SHIELD.xpilotId, "SHIELD is KEY 5"),
                () -> assertEquals(30, ControlKey.THRUST.xpilotId, "THRUST is KEY 30"),
                () -> assertEquals(64, ControlKey.DEFLECTOR.xpilotId, "DEFLECTOR is KEY 64"),
                () -> assertEquals(Long.MIN_VALUE, ControlKey.DEFLECTOR.mask(), "DEFLECTOR uses bit 63"),
                () -> assertEquals("a", ControlKey.TURN_LEFT.defaultKeys, "turn left defaults to 'a'"),
                () -> assertEquals("Shift_R Shift_L", ControlKey.THRUST.defaultKeys, "thrust defaults to shift"),
                () -> assertTrue(ControlKey.THRUST.isDown(m), "set marks a key down"),
                () -> assertFalse(ControlKey.SHIELD.isDown(m), "other keys stay up"),
                () -> assertFalse(ControlKey.THRUST.isDown(cleared), "set(false) clears the key"),
                () -> assertTrue(ControlKey.FIRE_SHOT.isDown(cleared), "set(false) leaves other keys"),
                () -> assertTrue(ControlKey.THRUST.pressed(0L, m), "detects a press edge"),
                () -> assertFalse(ControlKey.THRUST.pressed(m, m), "held is not a press"),
                () -> assertFalse(ControlKey.THRUST.pressed(0L, 0L), "staying up is not a press"),
                () -> assertFalse(ControlKey.THRUST.pressed(m, 0L), "a release is not a press"),
                () -> assertFalse(ControlKey.THRUST.released(0L, m), "a press is not a release"),
                () -> assertTrue(ControlKey.THRUST.released(m, 0L), "detects a release edge"),
                () -> assertFalse(ControlKey.THRUST.released(0L, 0L), "up is not a release"),
                () -> assertSame(ControlKey.THRUST, ControlKey.ofXpilotId(30), "looks up by C id"),
                () -> assertSame(ControlKey.LOCK_NEXT, ControlKey.ofBit(0), "looks up bit 0"),
                () -> assertSame(ControlKey.DEFLECTOR, ControlKey.ofBit(63), "looks up bit 63"),
                () -> assertNull(ControlKey.ofBit(64), "no key above bit 63"),
                () -> assertNull(ControlKey.ofBit(-1), "no key below bit 0"));
        }
    }

    @Nested
    @DisplayName("ConfigTransfer")
    class Transfer {
        @Test
        @DisplayName("ships a map through the reliable channel and the client rebuilds the same world")
        void endToEnd() throws Exception {
            WorldMap original = WorldMap.load(Path.of("..", "maps", "planetx.xp"));
            Options serverOpts = new Options();
            com.fulcrologic.jxpilot.common.config.ConfigParser.parseFile(Path.of("..", "maps", "planetx.xp"),
                com.fulcrologic.jxpilot.common.config.OptionOrigin.MAP, serverOpts);
            serverOpts.force(Opt.SHOT_SPEED, "33");
            ConfigTransfer.Blob blob = ConfigTransfer.encode(serverOpts.toConfigText());
            Msg.Welcome w = new Msg.Welcome();
            blob.fill(w);

            ReliableChannel tx = new ReliableChannel();
            ReliableChannel rx = new ReliableChannel();
            ConfigTransfer.Sender sender = new ConfigTransfer.Sender(blob);
            ConfigTransfer.Receiver receiver = new ConfigTransfer.Receiver(w);
            boolean[] complete = {false};
            ReliableHandler h = (type, payload) -> complete[0] = receiver.onChunk(payload);
            long now = 0;
            int rounds = 0;
            while (!complete[0] && rounds++ < 1000) {
                sender.pump(tx, 4);
                ByteBuffer p = buf();
                tx.write(p, now);
                p.flip();
                rx.read(p, h);
                tx.onAck(rx.ack(), now);
                now += 1_000_000L;
            }
            Options clientOpts = receiver.options();
            WorldMap rebuilt = WorldMap.fromOptions(clientOpts);
            assertAll(
                () -> assertTrue(blob.compressed.length < blob.length, "compresses the config"),
                () -> assertTrue(sender.done(), "sender finishes"),
                () -> assertTrue(receiver.complete(), "receiver completes"),
                () -> assertEquals(33.0, clientOpts.getDouble(Opt.SHOT_SPEED), "carries server overrides"),
                () -> assertEquals(original.dump(), rebuilt.dump(), "client rebuilds an identical map"),
                () -> assertEquals(original.bases(), rebuilt.bases(), "client gets identical bases"));
        }

        @Test
        @DisplayName("sender respects the reserve; receiver reports completion only at the end")
        void pumping() {
            byte[] text = new byte[5000];
            new java.util.Random(3).nextBytes(text);
            ConfigTransfer.Blob blob = ConfigTransfer.encode(new String(text, java.nio.charset.StandardCharsets.ISO_8859_1));
            ReliableChannel tx = new ReliableChannel(4, Protocol.MAX_RELIABLE_MESSAGE, 1);
            ConfigTransfer.Sender sender = new ConfigTransfer.Sender(blob);
            boolean allAtOnce = new ConfigTransfer.Sender(ConfigTransfer.encode("x")).pump(new ReliableChannel(), 0);
            boolean firstDone = sender.pump(tx, 2);
            boolean[] midComplete = {true};
            int queued = tx.unacked();
            boolean doneEarly = sender.done();
            Msg.Welcome w = new Msg.Welcome();
            blob.fill(w);
            ConfigTransfer.Receiver rx = new ConfigTransfer.Receiver(w);
            ReliableChannel rch = new ReliableChannel(4, Protocol.MAX_RELIABLE_MESSAGE, 1);
            List<Boolean> completions = new ArrayList<>();
            long now = 0;
            for (int round = 0; round < 20 && !sender.done() || tx.unacked() > 0 && round < 40; round++) {
                sender.pump(tx, 0);
                ByteBuffer p = ByteBuffer.allocate(8 * Protocol.MAX_PACKET);
                tx.write(p, now);
                p.flip();
                rch.read(p, (type, payload) -> {
                    completions.add(rx.onChunk(payload));
                    if (completions.size() == 1) {
                        midComplete[0] = rx.complete();
                    }
                });
                tx.onAck(rch.ack(), now);
                now += 2_000_000L;
            }
            int chunks = (blob.compressed.length + Protocol.DATA_CHUNK_SIZE - 1) / Protocol.DATA_CHUNK_SIZE;
            List<Boolean> expected = new ArrayList<>();
            for (int i = 0; i < chunks; i++) {
                expected.add(i == chunks - 1);
            }
            assertAll(
                () -> assertTrue(blob.compressed.length > 2 * Protocol.DATA_CHUNK_SIZE, "random data needs several chunks"),
                () -> assertFalse(firstDone, "the first pump cannot queue everything"),
                () -> assertTrue(allAtOnce, "pump reports true once the last chunk is queued"),
                () -> assertEquals(2, queued, "with window 4 and reserve 2 exactly 2 chunks are queued"),
                () -> assertFalse(doneEarly, "the sender is not done after the first pump"),
                () -> assertEquals(expected, completions, "onChunk reports completion exactly on the last chunk"),
                () -> assertFalse(midComplete[0], "complete() is false after the first of several chunks"),
                () -> assertEquals(new String(text, java.nio.charset.StandardCharsets.ISO_8859_1), rx.text(),
                    "the text decodes exactly"));
        }

        @Test
        @DisplayName("one encoded blob serves many senders identically")
        void sharedBlob() {
            ConfigTransfer.Blob blob = ConfigTransfer.encode("mapWidth: 3\n".repeat(2000));
            byte[] before = blob.compressed.clone();
            List<String> streams = new ArrayList<>();
            for (int client = 0; client < 2; client++) {
                ReliableChannel tx = new ReliableChannel();
                ConfigTransfer.Sender s1 = new ConfigTransfer.Sender(blob);
                s1.pump(tx, 0);
                ByteBuffer p = ByteBuffer.allocate(8 * Protocol.MAX_PACKET);
                tx.write(p, 0);
                streams.add(java.util.Arrays.toString(java.util.Arrays.copyOf(p.array(), p.position())));
            }
            assertAll(
                () -> assertEquals(streams.get(0), streams.get(1), "two clients get byte-identical chunk streams"),
                () -> assertArrayEquals(before, blob.compressed, "sending does not modify the shared blob"));
        }

        @Test
        @DisplayName("detects corrupt data and CRC mismatch")
        void corrupt() {
            ConfigTransfer.Blob blob = ConfigTransfer.encode("mapWidth: 3\n");
            Msg.Welcome w = new Msg.Welcome();
            blob.fill(w);
            w.configCrc32 ^= 1;
            ConfigTransfer.Receiver badCrc = new ConfigTransfer.Receiver(w);
            ByteBuffer chunk = ByteBuffer.allocate(4 + blob.compressed.length);
            chunk.putInt(0).put(blob.compressed).flip();
            badCrc.onChunk(chunk);
            Msg.Welcome w2 = new Msg.Welcome();
            blob.fill(w2);
            w2.configLength += 5;
            ConfigTransfer.Receiver badLen = new ConfigTransfer.Receiver(w2);
            chunk.rewind();
            badLen.onChunk(chunk);
            Msg.Welcome w3 = new Msg.Welcome();
            w3.configLength = 3;
            w3.configCompressedLength = 3;
            ConfigTransfer.Receiver garbage = new ConfigTransfer.Receiver(w3);
            ByteBuffer g = ByteBuffer.allocate(7);
            g.putInt(0).put(new byte[] {1, 2, 3}).flip();
            garbage.onChunk(g);
            ConfigTransfer.Blob big = ConfigTransfer.encode("mapName: x\n".repeat(500) + "a");
            Msg.Welcome wt = new Msg.Welcome();
            big.fill(wt);
            wt.configCompressedLength = big.compressed.length / 2;
            ConfigTransfer.Receiver half = new ConfigTransfer.Receiver(wt);
            ByteBuffer hb = ByteBuffer.allocate(4 + wt.configCompressedLength);
            hb.putInt(0).put(big.compressed, 0, wt.configCompressedLength).flip();
            half.onChunk(hb);
            Throwable truncated = assertThrows(IllegalStateException.class, half::text);
            Msg.Welcome w5 = new Msg.Welcome();
            w5.configLength = 1;
            w5.configCompressedLength = 1025;
            ConfigTransfer.Receiver two = new ConfigTransfer.Receiver(w5);
            ByteBuffer c1 = ByteBuffer.allocate(4 + 1024);
            c1.putInt(0).put(new byte[1024]).flip();
            boolean firstOfTwo = two.onChunk(c1);
            ByteBuffer c2 = ByteBuffer.allocate(5);
            c2.putInt(1024).put((byte) 0).flip();
            boolean lastOfTwo = two.onChunk(c2);
            ByteBuffer over = ByteBuffer.allocate(7);
            over.putInt(2).put(new byte[] {1, 2, 3}).flip();
            assertAll(
                () -> assertThrows(IllegalStateException.class, badCrc::text, "rejects a CRC mismatch"),
                () -> assertThrows(IllegalStateException.class, badLen::text, "rejects a length mismatch"),
                () -> assertThrows(IllegalStateException.class, garbage::text, "rejects corrupt deflate data"),
                () -> assertEquals("config length mismatch", truncated.getMessage(),
                    "a stream that ends early (inflater needs input) is a length mismatch"),
                () -> assertFalse(firstOfTwo, "1024 of 1025 announced bytes is not complete"),
                () -> assertTrue(lastOfTwo, "the 1025th byte completes the transfer"),
                () -> assertThrows(IllegalStateException.class, () -> new ConfigTransfer.Receiver(w3).onChunk(over),
                    "rejects a chunk outside the announced size"));
        }

        @Test
        @DisplayName("a receiver accepts announced sizes 0..MAX_LENGTH and rejects others with IllegalStateException")
        void receiverSizeGuard() {
            assertAll(
                () -> assertTrue(new ConfigTransfer.Receiver(welcome(0, 0)).complete(), "zero sizes are accepted"),
                () -> assertFalse(new ConfigTransfer.Receiver(welcome(ConfigTransfer.MAX_LENGTH,
                    ConfigTransfer.MAX_LENGTH)).complete(), "MAX_LENGTH is accepted"),
                () -> assertThrows(IllegalStateException.class, () -> new ConfigTransfer.Receiver(welcome(10, -1)),
                    "a negative compressed length"),
                () -> assertThrows(IllegalStateException.class, () -> new ConfigTransfer.Receiver(welcome(-1, 10)),
                    "a negative text length"),
                () -> assertThrows(IllegalStateException.class,
                    () -> new ConfigTransfer.Receiver(welcome(10, ConfigTransfer.MAX_LENGTH + 1)),
                    "a compressed length above MAX_LENGTH"),
                () -> assertThrows(IllegalStateException.class,
                    () -> new ConfigTransfer.Receiver(welcome(ConfigTransfer.MAX_LENGTH + 1, 10)),
                    "a text length above MAX_LENGTH"));
        }

        private static Msg.Welcome welcome(int length, int compressed) {
            Msg.Welcome w = new Msg.Welcome();
            w.configLength = length;
            w.configCompressedLength = compressed;
            return w;
        }
    }

    @Test
    @DisplayName("text builders start with MSG_LEN capacity so ordinary messages never grow them")
    void textBuildersPresized() {
        assertAll(
            () -> assertTrue(new Msg.Text().text.capacity() >= Msg.MSG_LEN, "MESSAGE/CHAT text"),
            () -> assertTrue(new Msg.Reject().text.capacity() >= Msg.MSG_LEN, "REJECT text"),
            () -> assertTrue(new Msg.Disconnect().text.capacity() >= Msg.MSG_LEN, "DISCONNECT text"),
            () -> assertTrue(new Msg.ScoreObject().text.capacity() >= Msg.MSG_LEN, "SCORE_OBJECT label"));
    }
}
