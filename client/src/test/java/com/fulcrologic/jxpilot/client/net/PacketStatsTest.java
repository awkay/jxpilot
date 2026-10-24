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
package com.fulcrologic.jxpilot.client.net;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketStatsTest {
    @Nested
    class Measurement {
        @Test
        void aFinishedWindowCountsLossAndDrop() {
            PacketStats s = new PacketStats();
            s.reset(10);
            s.measure(105, PacketStats.DRAW);         // starts the window at 100
            s.measure(112, PacketStats.DRAW);         // window 110: the first window was all DRAW (initial)
            int loss0 = s.loss();
            s.measure(113, PacketStats.DROP);
            s.measure(114, PacketStats.DROP);
            s.measure(114, PacketStats.DRAW);         // dropped, then drawn: DRAW replaces DROP
            s.measure(115, PacketStats.DRAW);
            s.measure(115, PacketStats.DROP);         // drawn stays drawn
            s.measure(105, PacketStats.DROP);         // before the window: ignored
            s.measure(121, PacketStats.DRAW);         // window 120 counts window 110
            assertAll(
                () -> assertEquals(0, loss0, "the initial all-DRAW window counts nothing"),
                () -> assertEquals(6, s.loss(), "110 slots never heard of: 110, 111, 116..119 (112, 113, 114, 115 seen)"),
                () -> assertEquals(1, s.drop(), "113 dropped; 114 dropped then drawn counts as drawn"),
                () -> assertEquals(10, s.rate(), "window of 10"));
        }

        @Test
        void theFirstFrameOnlyStartsTheWindowAndResetClears() {
            PacketStats s = new PacketStats();
            s.reset(4);
            s.measure(9, PacketStats.DROP);           // starts window 8, slot not marked
            s.measure(13, PacketStats.DRAW);          // window 12 counts window 8: initial DRAW everywhere
            s.measure(17, PacketStats.DRAW);          // window 16 counts 12: only 13 drawn
            int loss = s.loss();
            s.frameSize(500);
            s.keyboardSent(1, 10);
            s.keyboardAck(1, 14);
            s.reset(0);
            assertAll(
                () -> assertEquals(3, loss, "12, 14, 15 lost"),
                () -> assertEquals(0, s.loss(), "reset clears loss"),
                () -> assertEquals(0, s.drop(), "and drop"),
                () -> assertEquals(0, s.lag(), "and lag"),
                () -> assertEquals(0, s.size(), "and size"),
                () -> assertEquals(1, s.rate(), "a rate below 1 is 1"));
        }

        @Test
        void rateIsClamped() {
            PacketStats s = new PacketStats();
            s.reset(1000);
            assertEquals(PacketStats.MAX_RATE, s.rate(), "at most MAX_RATE");
        }
    }

    @Nested
    class Lag {
        @Test
        void lagIsTheMeanAckMinusSendOfAckedChanges() {
            PacketStats s = new PacketStats();
            s.keyboardSent(1, 100);
            s.keyboardSent(1, 105);                   // already tracked: keeps 100
            s.keyboardSent(2, 110);
            s.keyboardAck(9, 111);                    // unknown change, nothing acked yet: lag stays 0
            int none = s.lag();
            s.keyboardAck(1, 104);                    // 4 frames
            int one = s.lag();
            s.keyboardAck(2, 117);                    // 7 frames: mean (4 + 7) / 2 = 5
            s.keyboardAck(2, 130);                    // already acked: no change of its ack
            assertAll(
                () -> assertEquals(0, none, "no acked entry: unchanged"),
                () -> assertEquals(4, one, "one acked: its round trip"),
                () -> assertEquals(5, s.lag(), "integer mean of 4 and 7"));
        }

        @Test
        void aFullStoreReusesAckedEntriesThenTheLatestSent() {
            PacketStats s = new PacketStats();
            for (int i = 0; i < PacketStats.KEYBOARD_STORE; i++) {
                s.keyboardSent(i, 100 + i);
            }
            s.keyboardSent(50, 200);                  // full, none acked: replaces the largest send frame (119)
            s.keyboardAck(19, 205);                   // 19 was replaced: no entry
            int afterLost = s.lag();
            s.keyboardAck(50, 203);                   // 3
            int replaced = s.lag();
            s.keyboardSent(60, 300);                  // takes the acked entry of 50
            s.keyboardAck(60, 310);                   // 10; 50's ack is gone with its entry
            assertAll(
                () -> assertEquals(0, afterLost, "the replaced change cannot be acked"),
                () -> assertEquals(3, replaced, "the new change took a slot"),
                () -> assertEquals(10, s.lag(), "an acked entry is reused first"));
        }
    }

    @Nested
    class Size {
        @Test
        void sizeFallsAtMostSixteenPerFrame() {
            PacketStats s = new PacketStats();
            s.frameSize(500);
            int a = s.size();
            s.frameSize(400);
            int b = s.size();
            s.frameSize(470);
            int c = s.size();
            s.frameSize(600);
            assertAll(
                () -> assertEquals(500, a, "first size taken"),
                () -> assertEquals(484, b, "a much smaller one lowers by 16"),
                () -> assertEquals(470, c, "within 16 below: taken"),
                () -> assertEquals(600, s.size(), "a larger one is taken"),
                () -> assertEquals(0, PacketStats.NONE.size(), "the unfed stats stay 0"));
        }
    }
}
