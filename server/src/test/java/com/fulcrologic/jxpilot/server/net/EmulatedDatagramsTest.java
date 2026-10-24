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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.net.link.LinkDirection;
import com.fulcrologic.jxpilot.common.net.link.LinkProfile;
import com.fulcrologic.jxpilot.common.net.link.VirtualClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("EmulatedDatagrams")
class EmulatedDatagramsTest {
    static final long MS = 1_000_000L;

    record Sent(int channel, SocketAddress to, int firstByte, int length, long at) { }

    @Test
    @DisplayName("datagrams reach the inner send after the link delay, with their channel and peer, in order")
    void delays() {
        VirtualClock clock = new VirtualClock(0);
        List<Sent> got = new ArrayList<>();
        Datagrams inner = (ch, to, data) -> got.add(new Sent(ch, to, data.get(data.position()), data.remaining(),
            clock.getAsLong()));
        LinkDirection down = new LinkDirection(new LinkProfile().delayMs(40), 1, 16, 64);
        EmulatedDatagrams d = new EmulatedDatagrams(inner, down, clock, 64);
        SocketAddress a = new InetSocketAddress("127.0.0.1", 1000);
        SocketAddress b = new InetSocketAddress("127.0.0.1", 2000);
        ByteBuffer one = ByteBuffer.wrap(new byte[] {1, 2, 3});
        d.send(2, a, one);
        clock.advanceTo(10 * MS);
        d.send(5, b, ByteBuffer.wrap(new byte[] {9}));
        int early = d.pump();
        long next = d.nextDue();
        clock.advanceTo(40 * MS);
        int first = d.pump();
        clock.advanceTo(60 * MS);
        int second = d.pump();
        assertAll(
            () -> assertEquals(3, one.position(), "send consumes the buffer"),
            () -> assertEquals(0, early, "nothing goes out before the delay"),
            () -> assertEquals(40 * MS, next, "the first is due 40 ms after it was sent"),
            () -> assertEquals(1, first, "at 40 ms the first goes"),
            () -> assertEquals(1, second, "at 60 ms (10 + 40 is past) the second goes"),
            () -> assertEquals(List.of(new Sent(2, a, 1, 3, 40 * MS), new Sent(5, b, 9, 1, 60 * MS)), got,
                "each with its channel, peer, bytes, at its due time"),
            () -> assertEquals(Long.MAX_VALUE, d.nextDue(), "nothing left"));
    }

    @Test
    @DisplayName("a lossy link drops datagrams before they reach the inner send")
    void loses() {
        VirtualClock clock = new VirtualClock(0);
        int[] count = new int[1];
        EmulatedDatagrams d = new EmulatedDatagrams((ch, to, data) -> count[0]++,
            new LinkDirection(new LinkProfile().uniformLoss(1), 1, 16, 64), clock, 64);
        d.send(0, null, ByteBuffer.allocate(4));
        assertAll(
            () -> assertEquals(0, d.pump(), "nothing goes"),
            () -> assertEquals(0, count[0], "the inner send never runs"));
    }
}
