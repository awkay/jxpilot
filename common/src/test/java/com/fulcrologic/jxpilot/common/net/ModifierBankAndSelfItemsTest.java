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

import com.fulcrologic.jxpilot.common.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MODIFIER_BANK message (C PKT_MODIFIERBANK) and SELF_ITEMS' lose item (C PKT_LOSEITEM). */
@DisplayName("MODIFIER_BANK and SELF_ITEMS.loseItem wire formats")
class ModifierBankAndSelfItemsTest {

    @Nested
    @DisplayName("Msg.ModifierBank")
    class Bank {
        @Test
        @DisplayName("u8 bank, str8 text round trip as reliable type 14; a reused holder is overwritten")
        void roundTrip() {
            ReliableChannel tx = new ReliableChannel();
            Msg.ModifierBank m = new Msg.ModifierBank();
            m.bank = 3;
            m.text.append("FN C V2 X4 LS");
            m.send(tx);
            m.bank = 0;
            m.text.setLength(0);
            m.send(tx);
            ByteBuffer p = ByteBuffer.allocate(Protocol.MAX_PACKET);
            tx.write(p, 0);
            p.flip();
            List<Integer> types = new ArrayList<>();
            List<ByteBuffer> bodies = new ArrayList<>();
            new ReliableChannel().read(p, (type, payload) -> {
                types.add(type);
                ByteBuffer copy = ByteBuffer.allocate(payload.remaining());
                copy.put(payload).flip();
                bodies.add(copy);
            });
            ByteBuffer first = bodies.get(0).duplicate();
            int bankByte = first.get(0) & 0xFF;
            int lenByte = first.get(1) & 0xFF;
            int size = first.remaining();
            Msg.ModifierBank r = new Msg.ModifierBank();
            r.read(bodies.get(0));
            int bank1 = r.bank;
            String text1 = r.text.toString();
            r.read(bodies.get(1));
            assertAll(
                () -> assertEquals(List.of(14, 14), types, "MODIFIER_BANK is reliable type 14"),
                () -> assertEquals(14, ReliableType.MODIFIER_BANK, "the constant is 14"),
                () -> assertEquals(3, bankByte, "the body starts with the u8 bank"),
                () -> assertEquals(13, lenByte, "then the u8 text length"),
                () -> assertEquals(2 + 13, size, "then the text bytes and nothing else"),
                () -> assertEquals(3, bank1, "the bank round trips"),
                () -> assertEquals("FN C V2 X4 LS", text1, "the text round trips"),
                () -> assertEquals(0, r.bank, "a second read replaces the bank"),
                () -> assertEquals("", r.text.toString(), "and the text (empty text round trips)"),
                () -> assertEquals(4, Msg.ModifierBank.BANKS, "C NUM_MODBANKS is 4"));
        }

        @Test
        @DisplayName("a bank above 127 reads unsigned (the server range-checks it)")
        void unsignedBank() {
            ByteBuffer b = ByteBuffer.allocate(8);
            Msg.ModifierBank m = new Msg.ModifierBank();
            m.bank = 200;
            m.text.append("I");
            m.write(b);
            b.flip();
            Msg.ModifierBank r = new Msg.ModifierBank();
            r.read(b);
            assertAll(
                () -> assertEquals(200, r.bank, "200 is read as 200"),
                () -> assertEquals("I", r.text.toString(), "the text follows"));
        }
    }

    @Nested
    @DisplayName("Records.SelfItems.loseItem")
    class LoseItem {
        ByteBuffer snapshot(int[] counts, Integer loseItem) {
            ByteBuffer b = ByteBuffer.allocate(256);
            SnapshotWriter w = new SnapshotWriter();
            w.setWorld(1000, 1000, false);
            w.begin(b, 1, 0, 0, 1, SnapshotWriter.FLAG_LAST_PART, 0, 0);
            w.beginSection(Section.SELF_ITEMS);
            if (loseItem == null) {
                Records.SelfItems.put(w, counts);
            } else {
                Records.SelfItems.put(w, counts, loseItem);
            }
            w.endSection();
            return b.flip();
        }

        Records.SelfItems read(SnapshotReader r, ByteBuffer b) {
            r.setWorld(1000, 1000, false);
            r.begin(b);
            r.nextSection();
            return new Records.SelfItems().wrap(r, 0);
        }

        @Test
        @DisplayName("the record is 22 bytes: 21 counts then loseItem; the counts-only put writes loseItem 0")
        void writesLoseItem() {
            int[] counts = new int[Item.COUNT];
            counts[Item.COUNT - 1] = 7;
            counts[3] = 2;
            SnapshotReader r = new SnapshotReader();
            Records.SelfItems s = read(r, snapshot(counts, 9));
            int size = r.recordSize();
            int last = s.count(Item.COUNT - 1);
            int three = s.count(3);
            int lose = s.loseItem();
            Records.SelfItems old = read(new SnapshotReader(), snapshot(counts, null));
            int oldLose = old.loseItem();
            Records.SelfItems neg = read(new SnapshotReader(), snapshot(counts, -4));
            int negLose = neg.loseItem();
            Records.SelfItems big = read(new SnapshotReader(), snapshot(counts, 300));
            int bigLose = big.loseItem();
            assertAll(
                () -> assertEquals(22, Records.SelfItems.SIZE, "SIZE is 21 counts + 1"),
                () -> assertEquals(22, Section.recordSize(Section.SELF_ITEMS), "the section table uses it"),
                () -> assertEquals(22, size, "the transmitted record size is 22"),
                () -> assertEquals(7, last, "the last count keeps its place"),
                () -> assertEquals(2, three, "counts keep their places"),
                () -> assertEquals(9, lose, "loseItem round trips"),
                () -> assertEquals(0, oldLose, "the counts-only put writes loseItem 0"),
                () -> assertEquals(0, negLose, "a negative loseItem is clamped to 0"),
                () -> assertEquals(255, bigLose, "a loseItem above 255 is clamped to 255"));
        }

        @Test
        @DisplayName("an older 21-byte record reads loseItem 0 and its counts")
        void olderRecord() {
            ByteBuffer b = ByteBuffer.allocate(256);
            SnapshotWriter w = new SnapshotWriter();
            w.setWorld(1000, 1000, false);
            w.begin(b, 1, 0, 0, 1, SnapshotWriter.FLAG_LAST_PART, 0, 0);
            b.put((byte) Section.SELF_ITEMS).put((byte) 21).put((byte) 0).putShort((short) 2);
            for (int rec = 0; rec < 2; rec++) {
                for (int i = 0; i < 21; i++) {
                    b.put((byte) (rec * 50 + i));
                }
            }
            b.flip();
            SnapshotReader r = new SnapshotReader();
            r.setWorld(1000, 1000, false);
            r.begin(b);
            boolean has = r.nextSection();
            boolean known = r.known();
            Records.SelfItems first = new Records.SelfItems().wrap(r, 0);
            int lose = first.loseItem();
            int count20 = first.count(20);
            Records.SelfItems second = new Records.SelfItems().wrap(r, 1);
            int secondLose = second.loseItem();
            int secondCount0 = second.count(0);
            assertAll(
                () -> assertTrue(has, "the section is read"),
                () -> assertFalse(known, "a record shorter than this layout is not 'known' (SnapshotReader.known)"),
                () -> assertEquals(20, count20, "the old counts read as before"),
                () -> assertEquals(0, lose, "loseItem reads 0, not the next record's first byte (50)"),
                () -> assertEquals(50, secondCount0, "the 21-byte stride is used"),
                () -> assertEquals(0, secondLose, "the next record reads loseItem 0 too"));
        }
    }
}
