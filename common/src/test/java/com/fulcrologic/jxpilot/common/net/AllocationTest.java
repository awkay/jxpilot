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

import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.Rng;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Steady-state hot paths allocate nothing (SPEC GC rules). */
@DisplayName("Protocol hot paths are allocation free")
class AllocationTest {
    private static final com.sun.management.ThreadMXBean MX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final String LONG_TEXT = "x".repeat(3000);
    private static final String[] TEXTS = {"", "hi", "a medium length chat line", LONG_TEXT};

    private final ByteBuffer buf = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
    private final ByteBuffer big = ByteBuffer.allocateDirect(8 * Protocol.MAX_PACKET);
    private final ByteBuffer held = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
    private final SnapshotWriter writer = new SnapshotWriter();
    private final SnapshotReader reader = new SnapshotReader();
    private final PacketHeader header = new PacketHeader();
    private final Records.Self self = new Records.Self();
    private final Records.Ship ship = new Records.Ship();
    private final Records.Shot shot = new Records.Shot();
    private final Records.Burst burstRec = new Records.Burst();
    private final Records.Radar radar = new Records.Radar();
    private final DebrisBurst burst = new DebrisBurst();
    private final DebrisBurst decoded = new DebrisBurst();
    private final Rng rng = new Rng(1);
    private final InputHistory history = new InputHistory();
    private final InputReader inputReader = new InputReader();
    private final ReliableChannel tx = new ReliableChannel(16, Protocol.MAX_RELIABLE_MESSAGE, 2);
    private final ReliableChannel rx = new ReliableChannel(16, Protocol.MAX_RELIABLE_MESSAGE, 2);
    /** A 2-slot window so every burst of 5 messages overflows and the overflow buffers are recycled. */
    private final ReliableChannel smallTx = new ReliableChannel(2, Protocol.MAX_RELIABLE_MESSAGE, 2);
    private final ReliableChannel smallRx = new ReliableChannel(2, Protocol.MAX_RELIABLE_MESSAGE, 2);
    private final Sink sink = new Sink();
    private final Msg.Score score = new Msg.Score();
    private final Msg.Text text = new Msg.Text();
    private final Msg.Text textIn = new Msg.Text();
    private double checksum;
    private long now;
    private int heldBack;
    private int releasedByLatePacket;
    private int resends;
    private int overflowed;

    private final class Sink implements ReliableHandler, DebrisBurst.Sink {
        int received;

        @Override
        public void onReliableMessage(int type, ByteBuffer payload) {
            if (type == ReliableType.MESSAGE) {
                textIn.read(payload);
                received += textIn.text.length();
            } else {
                received += type + payload.remaining();
            }
        }

        @Override
        public void particle(int index, float vx, float vy, float speed, int life) {
            checksum += vx + vy + speed + life;
        }
    }

    private void snapshot(int i) {
        buf.clear();
        PacketHeader.write(buf, PacketType.SNAPSHOT, 1, 0);
        writer.begin(buf, i, i, 0, 1, SnapshotWriter.FLAG_LAST_PART, 3500, 3500);
        writer.beginSection(Section.SELF);
        Records.Self.put(writer, 1, 0, i, i, 1, 1, 0.5f, 100, 1000, Protocol.NO_ID, 1, 0, 0);
        writer.endSection();
        writer.beginSection(Section.SHIP);
        for (int k = 0; k < 16 && writer.hasRoom(); k++) {
            Records.Ship.put(writer, k, 0, 3000 + k * 10.0, 3000 + i % 1000, k * 0.1, 1.0, -1.0);
        }
        writer.endSection();
        writer.beginSection(Section.BURST);
        burst.x = 3400;
        burst.y = 3400;
        burst.seed = i;
        burst.count = 20;
        burst.dirSpan = 128;
        burst.maxSpeed = 10;
        burst.maxLife = 30;
        Records.Burst.quantize(burst);
        Records.Burst.put(writer, burst, Records.BurstKind.SPARK, Records.ObjColor.RED, 1);
        writer.endSection();
        // fill shots until the datagram is full
        writer.beginSection(Section.SHOT, Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.ENEMY));
        int k = 0;
        while (writer.hasRoom()) {
            Records.Shot.put(writer, 3000 + k, 3000 - k, 2.0, 2.0);
            k++;
        }
        writer.endSection();
        buf.flip();
        header.read(buf);
        reader.begin(buf);
        while (reader.nextSection()) {
            for (int j = 0; j < reader.count(); j++) {
                switch (reader.type()) {
                    case Section.SELF -> checksum += self.wrap(reader, j).x() + self.fuel();
                    case Section.SHIP -> checksum += ship.wrap(reader, j).x() + ship.heading() + ship.vx();
                    case Section.SHOT -> checksum += shot.wrap(reader, j).y() + shot.vy();
                    case Section.BURST -> {
                        burstRec.wrap(reader, j).read(decoded);
                        decoded.generate(rng, sink);
                    }
                    case Section.RADAR -> checksum += radar.wrap(reader, j).flags();
                    default -> { }
                }
            }
        }
    }

    private void input(int i) {
        history.record(ControlKey.THRUST.mask(), i & 0xFF);
        buf.clear();
        history.write(buf, i, 4);
        buf.flip();
        inputReader.wrap(buf);
        for (int k = inputReader.count() - 1; k >= 0; k--) {
            checksum += inputReader.keys(k) + inputReader.aimTurn(k);
        }
    }

    private void reliable(int i) {
        // varying text lengths (including one longer than a slot) plus a score
        text.text.setLength(0);
        text.text.append(TEXTS[i & 3]);
        text.send(tx);
        score.id = i & 0xFF;
        score.score = i;
        score.send(tx);
        // overflow: 5 messages into a 2-slot window, drained by write/ack rounds
        for (int k = 0; k < 5; k++) {
            score.send(smallTx);
        }
        if (smallTx.overflowing() == 3) {
            overflowed++;
        }
        while (smallTx.unacked() > 0 || smallTx.overflowing() > 0) {
            big.clear();
            smallTx.write(big, now);
            big.flip();
            smallRx.read(big, sink);
            smallTx.onAck(smallRx.ack(), now);
        }
        big.clear();
        tx.write(big, now);
        big.flip();
        if ((i & 3) == 0) {
            // reorder: hold this packet back, send a newer message first, then deliver the old one
            held.clear();
            held.put(big);
            held.flip();
            score.send(tx);
            big.clear();
            tx.write(big, now); // only the new message: the held ones are not due for resend yet
            big.flip();
            if (rx.read(big, sink) == 0) { // buffered: arrives ahead of a gap
                heldBack++;
            }
            if (rx.read(held, sink) >= 3) { // fills the gap, releases the buffered message too
                releasedByLatePacket++;
            }
        } else {
            rx.read(big, sink);
        }
        // ack lags by one iteration on odd i: resend happens when due
        if ((i & 1) == 0) {
            tx.onAck(rx.ack(), now);
        }
        now += 3_000_000L;
        if (tx.wantsSend(now)) {
            big.clear();
            if (tx.write(big, now) > 0) {
                resends++;
            }
            big.flip();
            rx.read(big, sink);
            tx.onAck(rx.ack(), now);
        }
    }

    private void iteration(int i) {
        snapshot(i);
        input(i);
        reliable(i);
    }

    @Test
    @DisplayName("allocates zero bytes per iteration after warm-up")
    void zeroAllocation() {
        assertTrue(MX.isThreadAllocatedMemorySupported() && MX.isThreadAllocatedMemoryEnabled(),
            "the JVM measures per-thread allocation (otherwise this test proves nothing)");
        writer.setWorld(7000, 7000, true);
        for (int i = 0; i < 20_000; i++) {
            iteration(i);
        }
        int receivedBefore = sink.received;
        heldBack = 0;
        releasedByLatePacket = 0;
        resends = 0;
        overflowed = 0;
        long before = MX.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 20_000; i++) {
            iteration(i);
        }
        long allocated = MX.getCurrentThreadAllocatedBytes() - before;
        int received = sink.received - receivedBefore;
        assertAll(
            () -> assertEquals(0L, allocated,
                "snapshot fill/decode, burst expansion, inputs, texts, out-of-order and resent reliable messages allocate nothing"),
            () -> assertTrue(checksum != 0, "the snapshot work actually ran"),
            () -> assertTrue(received > 20_000, "reliable messages were actually delivered"),
            () -> assertEquals(0, tx.unacked(), "every reliable message was eventually acknowledged"),
            () -> assertEquals(5000, heldBack, "every 4th iteration a newer packet arrived ahead of a gap"),
            () -> assertEquals(5000, releasedByLatePacket, "and the late packet released the buffered message"),
            () -> assertTrue(resends > 1000, "lagging acks caused resends (" + resends + ")"),
            () -> assertEquals(20_000, overflowed, "every iteration overflowed the 2-slot window by 3 and drained it"));
    }
}
