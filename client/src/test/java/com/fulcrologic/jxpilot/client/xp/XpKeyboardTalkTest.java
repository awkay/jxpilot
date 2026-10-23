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

import com.fulcrologic.jxpilot.common.net.ControlKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Keyboard change/ack protocol and talk queue. */
class XpKeyboardTalkTest {

    static byte[] written(ByteBuffer b) {
        return Arrays.copyOf(b.array(), b.position());
    }

    @Nested
    @DisplayName("XpKeyboard")
    class Keyboard {
        @Test
        void keyChangesAreNumbered() {
            XpKeyboard k = new XpKeyboard();
            boolean first = k.setKeys(ControlKey.THRUST.mask());
            boolean same = k.setKeys(ControlKey.THRUST.mask());
            boolean other = k.setKeys(0);
            assertAll(
                () -> assertTrue(first, "a new mask is a change"),
                () -> assertFalse(same, "the same mask is not"),
                () -> assertTrue(other, "releasing is a change"),
                () -> assertEquals(2, k.change(), "two changes"),
                () -> assertEquals(0, k.keys(), "current mask"));
        }

        @Test
        void forceChangeNumbersTheSameKeysAgain() {
            XpKeyboard k = new XpKeyboard();
            k.forceChange();
            assertAll(
                () -> assertEquals(1, k.change(), "C Net_key_change"),
                () -> assertTrue(k.unacked(), "unacked"));
        }

        @Test
        void acksFollowCReceiveStart() {
            XpKeyboard k = new XpKeyboard();
            k.setKeys(1);
            k.setKeys(2);
            k.setKeys(3);
            boolean ok1 = k.onAck(2);
            boolean old = k.onAck(1);
            int afterOld = k.ack();
            boolean premature = k.onAck(4);
            int afterPremature = k.ack();
            boolean last = k.onAck(3);
            assertAll(
                () -> assertTrue(ok1, "ack within the changes taken"),
                () -> assertTrue(old, "older ack accepted but ignored"),
                () -> assertEquals(2, afterOld, "older ack does not go back"),
                () -> assertFalse(premature, "ack beyond the last change is premature"),
                () -> assertEquals(2, afterPremature, "premature ack not taken"),
                () -> assertTrue(last, "last change acked"),
                () -> assertFalse(k.unacked(), "all acked"));
        }

        @Test
        void resendIsDueOncePerFrameWhileUnacked() {
            XpKeyboard k = new XpKeyboard();
            k.setKeys(1);
            ByteBuffer b = ByteBuffer.allocate(64);
            boolean before = k.resendDue(5);
            k.write(b, 5);
            boolean sameFrame = k.resendDue(5);
            boolean nextFrame = k.resendDue(6);
            k.onAck(1);
            boolean acked = k.resendDue(7);
            assertAll(
                () -> assertTrue(before, "unacked and not sent in frame 5"),
                () -> assertFalse(sameFrame, "already sent during frame 5"),
                () -> assertTrue(nextFrame, "frame 6: resend"),
                () -> assertFalse(acked, "acked: nothing due"),
                () -> assertEquals(5, k.lastUpdate(), "send frame remembered"));
        }

        @Test
        void writeIsCSendKeyboard() {
            XpKeyboard k = new XpKeyboard();
            k.setKeys(ControlKey.THRUST.mask() | ControlKey.LOCK_NEXT.mask() | ControlKey.DEFLECTOR.mask()
                | ControlKey.FIRE_SHOT.mask());
            ByteBuffer b = ByteBuffer.allocate(64);
            k.write(b, 9);
            // keys_t: LOCK_NEXT 1, FIRE_SHOT 6, THRUST 30, DEFLECTOR 64 -> bytes 0 (bits 1, 6), 3 (bit 6), 8 (bit 0)
            assertArrayEquals(new CPackets().c(XpProto.PKT_KEYBOARD).d(1)
                .c(0x42).c(0).c(0).c(0x40).c(0).c(0).c(0).c(0).c(0x01).bytes(), written(b),
                "PKT_KEYBOARD, change, 9-byte vector with BITV_SET(keys_t)");
            assertEquals(XpKeyboard.SIZE, b.position(), "SIZE matches");
        }

        @Test
        void vectorBytesForEveryKey() {
            for (ControlKey key : ControlKey.values()) {
                int bit = key.xpilotId;
                for (int i = 0; i < XpProto.KEYBOARD_SIZE; i++) {
                    int expect = i == bit / 8 ? 1 << (bit % 8) : 0;
                    assertEquals(expect, XpKeyboard.vectorByte(key.mask(), i), key + " byte " + i);
                }
            }
        }

        @Test
        void resetClearsEverything() {
            XpKeyboard k = new XpKeyboard();
            k.setKeys(5);
            k.write(ByteBuffer.allocate(64), 3);
            k.reset();
            assertAll(
                () -> assertEquals(0, k.keys(), "keys"),
                () -> assertEquals(0, k.change(), "change"),
                () -> assertEquals(0, k.ack(), "ack"),
                () -> assertEquals(0, k.lastUpdate(), "last update"));
        }
    }

    @Nested
    @DisplayName("XpTalk")
    class Talk {
        @Test
        void firstLineIsDueAtOnceWithSequenceOne() {
            XpTalk t = new XpTalk();
            boolean added = t.add("hello", 10);
            ByteBuffer b = ByteBuffer.allocate(128);
            boolean due = t.due(10);
            t.write(b, 10);
            assertAll(
                () -> assertTrue(added, "queued"),
                () -> assertTrue(due, "due immediately"),
                () -> assertEquals(1, t.pending(), "sequence 1"),
                () -> assertArrayEquals(new CPackets().c(XpProto.PKT_TALK).d(1).s("hello").bytes(), written(b),
                    "PKT_TALK %ld%s"),
                () -> assertEquals(b.position(), t.size(), "size matches"));
        }

        @Test
        void resentEveryTalkRetryFrames() {
            XpTalk t = new XpTalk();
            t.add("x", 10);
            t.write(ByteBuffer.allocate(64), 10);
            assertAll(
                () -> assertFalse(t.due(11), "one frame later: not yet"),
                () -> assertTrue(t.due(10 + XpTalk.TALK_RETRY), "TALK_RETRY frames later: resend"));
        }

        @Test
        void acksCompleteLinesInOrder() {
            XpTalk t = new XpTalk();
            t.add("one", 1);
            t.add("two", 1);
            t.onAck(0, 2);
            int stillPending = t.pending();
            t.onAck(1, 3);
            ByteBuffer b = ByteBuffer.allocate(64);
            boolean due = t.due(3);
            t.write(b, 3);
            assertAll(
                () -> assertEquals(1, stillPending, "older ack ignored"),
                () -> assertEquals(1, t.queued(), "first line done"),
                () -> assertEquals(2, t.pending(), "next line gets sequence 2"),
                () -> assertTrue(due, "and is due at once"),
                () -> assertArrayEquals(new CPackets().c(XpProto.PKT_TALK).d(2).s("two").bytes(), written(b), "second"));
        }

        @Test
        void lastAckLeavesItIdle() {
            XpTalk t = new XpTalk();
            t.add("one", 1);
            t.onAck(5, 2);
            t.onAck(6, 2);
            assertAll(
                () -> assertEquals(0, t.queued(), "empty"),
                () -> assertEquals(0, t.pending(), "idle"),
                () -> assertFalse(t.due(100), "nothing due"));
        }

        @Test
        void emptyLinesAndFullQueueAreRefused() {
            XpTalk t = new XpTalk();
            boolean empty = t.add("", 0);
            for (int i = 0; i < XpTalk.QUEUE; i++) {
                assertTrue(t.add("l" + i, 0), "line " + i + " fits");
            }
            assertAll(
                () -> assertFalse(empty, "empty refused"),
                () -> assertFalse(t.add("more", 0), "queue full"),
                () -> assertEquals(XpTalk.QUEUE, t.queued(), "eight queued"));
        }

        @Test
        void queueWrapsAround() {
            XpTalk t = new XpTalk();
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < XpTalk.QUEUE; i++) {
                    t.add("r" + round + "l" + i, 0);
                }
                for (int i = 0; i < XpTalk.QUEUE; i++) {
                    ByteBuffer b = ByteBuffer.allocate(64);
                    t.write(b, 0);
                    assertArrayEquals(new CPackets().c(XpProto.PKT_TALK).d(t.pending()).s("r" + round + "l" + i)
                        .bytes(), written(b), "round " + round + " line " + i + " in order");
                    t.onAck(t.pending(), 0);
                }
            }
        }

        @Test
        void longLinesAreCutToCLimit() {
            XpTalk t = new XpTalk();
            t.add("z".repeat(200), 0);
            ByteBuffer b = ByteBuffer.allocate(256);
            t.write(b, 0);
            assertEquals(1 + 4 + XpProto.MAX_CHARS, b.position(), "79 chars + NUL");
        }

        @Test
        void resetDropsEverything() {
            XpTalk t = new XpTalk();
            t.add("a", 0);
            t.reset();
            t.add("b", 0);
            assertAll(
                () -> assertEquals(1, t.queued(), "only the new line"),
                () -> assertEquals(1, t.pending(), "sequence restarts"));
        }
    }
}
