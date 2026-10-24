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

import com.fulcrologic.jxpilot.common.net.link.LinkEmulator;
import com.fulcrologic.jxpilot.common.net.link.LinkProfile;
import com.fulcrologic.jxpilot.common.net.link.VirtualClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EmulatedTransport")
class EmulatedTransportTest {
    static final long MS = 1_000_000L;

    /** An inner transport that records what reaches it and hands out queued datagrams. */
    static final class Inner implements Transport {
        final List<String> sent = new ArrayList<>();
        final List<Long> sentAt = new ArrayList<>();
        final ArrayDeque<byte[]> toClient = new ArrayDeque<>();
        final VirtualClock clock;
        boolean closed;
        /** Bytes the next send answers with (a reply caused by the forwarded packet), or null. */
        byte[] reply;

        Inner(VirtualClock clock) {
            this.clock = clock;
        }

        @Override
        public boolean send(ByteBuffer packet) {
            byte[] b = new byte[packet.remaining()];
            packet.get(b);
            sent.add(new String(b));
            sentAt.add(clock.getAsLong());
            if (reply != null) {
                toClient.add(reply);
                reply = null;
            }
            return true;
        }

        @Override
        public int receive(ByteBuffer buf) {
            byte[] b = toClient.poll();
            if (b == null) {
                return 0;
            }
            buf.put(b);
            return b.length;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static String text(ByteBuffer b, int n) {
        byte[] a = new byte[n];
        b.flip().get(a);
        return new String(a);
    }

    @Test
    @DisplayName("client sends reach the inner transport after the uplink delay; send consumes and reports true")
    void uplink() throws Exception {
        VirtualClock clock = new VirtualClock(0);
        Inner inner = new Inner(clock);
        EmulatedTransport t = new EmulatedTransport(inner,
            new LinkEmulator(clock, new LinkProfile().delayMs(30), new LinkProfile(), 1));
        ByteBuffer p = ByteBuffer.wrap("hello".getBytes());
        boolean ok = t.send(p);
        int atOnce = inner.sent.size();
        clock.advanceTo(29 * MS);
        t.pump();
        int early = inner.sent.size();
        clock.advanceTo(30 * MS);
        t.pump();
        assertAll(
            () -> assertTrue(ok, "a send always succeeds, like UDP"),
            () -> assertEquals(5, p.position(), "the packet is consumed"),
            () -> assertEquals(0, atOnce, "nothing arrives at once"),
            () -> assertEquals(0, early, "nothing before 30 ms"),
            () -> assertEquals(List.of("hello"), inner.sent, "the bytes arrive"),
            () -> assertEquals(List.of(30 * MS), inner.sentAt, "at 30 ms"),
            () -> assertEquals(Long.MAX_VALUE, t.link().up().nextDue(), "the uplink is empty"));
    }

    @Test
    @DisplayName("inner arrivals reach the client after the downlink delay, stamped when drained; 0 when none is due")
    void downlink() throws Exception {
        VirtualClock clock = new VirtualClock(0);
        Inner inner = new Inner(clock);
        EmulatedTransport t = new EmulatedTransport(inner,
            new LinkEmulator(clock, new LinkProfile(), new LinkProfile().delayMs(20), 1));
        clock.advanceTo(5 * MS);
        inner.toClient.add("snap".getBytes());
        t.pump(); // the harness drains right after the server sent: stamped at 5 ms
        ByteBuffer buf = ByteBuffer.allocate(64);
        clock.advanceTo(24 * MS);
        int early = t.receive(buf);
        clock.advanceTo(25 * MS);
        int n = t.receive(buf);
        assertAll(
            () -> assertEquals(0, early, "not before 5 + 20 ms"),
            () -> assertEquals(4, n, "delivered at 25 ms"),
            () -> assertEquals("snap", text(buf, n), "the bytes"));
    }

    @Test
    @DisplayName("pump forwards the due uplink first, then queues replies it caused at the same time")
    void replies() throws Exception {
        VirtualClock clock = new VirtualClock(0);
        Inner inner = new Inner(clock);
        EmulatedTransport t = new EmulatedTransport(inner,
            new LinkEmulator(clock, new LinkProfile().delayMs(10), new LinkProfile().delayMs(10), 1));
        t.send(ByteBuffer.wrap("ping".getBytes()));
        inner.reply = "pong".getBytes();
        clock.advanceTo(10 * MS);
        t.pump();
        long due = t.link().down().nextDue();
        ByteBuffer buf = ByteBuffer.allocate(64);
        clock.advanceTo(20 * MS);
        int n = t.receive(buf);
        assertAll(
            () -> assertEquals(10 * MS + 10 * MS, due, "the reply leaves when the ping arrived: due 10 ms later"),
            () -> assertEquals("pong", text(buf, n), "and reaches the client"));
    }

    @Test
    @DisplayName("a forwarded packet the inner transport refuses is lost and counted; later sends are unaffected")
    void innerFailure() throws Exception {
        VirtualClock clock = new VirtualClock(0);
        Inner inner = new Inner(clock);
        Transport failing = new Transport() {
            int calls;

            @Override
            public boolean send(ByteBuffer p) throws java.io.IOException {
                calls++;
                if (calls == 1) {
                    throw new java.io.IOException("no route");
                }
                if (calls == 2) {
                    return false;
                }
                return inner.send(p);
            }

            @Override
            public int receive(ByteBuffer b) {
                return inner.receive(b);
            }

            @Override
            public void close() {
            }
        };
        EmulatedTransport t = new EmulatedTransport(failing, new LinkEmulator(clock, new LinkProfile(), new LinkProfile(), 1));
        boolean a = t.send(ByteBuffer.wrap("a".getBytes()));
        boolean b = t.send(ByteBuffer.wrap("b".getBytes()));
        boolean c = t.send(ByteBuffer.wrap("c".getBytes()));
        assertAll(
            () -> assertTrue(a && b && c, "every send is accepted by the link, whatever happens downstream"),
            () -> assertEquals(2, t.forwardErrors(), "the IOException and the refusal each lost one packet"),
            () -> assertEquals(List.of("c"), inner.sent, "the third arrives"));
    }

    @Test
    @DisplayName("loss on the link drops datagrams silently; close closes the inner transport")
    void lossAndClose() throws Exception {
        VirtualClock clock = new VirtualClock(0);
        Inner inner = new Inner(clock);
        EmulatedTransport t = new EmulatedTransport(inner,
            new LinkEmulator(clock, new LinkProfile().uniformLoss(1), new LinkProfile().uniformLoss(1), 1));
        boolean ok = t.send(ByteBuffer.wrap("x".getBytes()));
        inner.toClient.add("y".getBytes());
        int n = t.receive(ByteBuffer.allocate(8));
        t.close();
        assertAll(
            () -> assertTrue(ok, "a lost send still reports true"),
            () -> assertEquals(List.of(), inner.sent, "nothing reaches the server"),
            () -> assertEquals(0, n, "nothing reaches the client"),
            () -> assertEquals(1, t.link().down().lost(), "the downlink counted its loss"),
            () -> assertTrue(inner.closed, "close is passed on"));
    }
}
