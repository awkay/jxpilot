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

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.function.LongSupplier;

/**
 * A server {@link Datagrams} decorator that sends through an emulated link direction ({@link LinkDirection},
 * usually a {@code LinkEmulator}'s downlink): {@link ServerNet}'s datagrams enter the link at the clock's time,
 * keeping their channel and peer, and reach the inner {@code Datagrams} when {@link #pump()} finds them due.
 * One link serves every peer (one shared path). Allocation free after construction.
 */
public final class EmulatedDatagrams implements Datagrams {
    private final Datagrams inner;
    private final LinkDirection link;
    private final LongSupplier clock;
    private final ByteBuffer scratch;

    public EmulatedDatagrams(Datagrams inner, LinkDirection link, LongSupplier clock, int maxDatagram) {
        this.inner = inner;
        this.link = link;
        this.clock = clock;
        this.scratch = ByteBuffer.allocateDirect(maxDatagram);
    }

    /** BEHAVIOR: the datagram enters the link at the clock's time with its channel and peer (it is consumed). */
    @Override
    public void send(int channel, SocketAddress to, ByteBuffer data) {
        link.send(data, clock.getAsLong(), channel, to);
    }

    /**
     * BEHAVIOR: every datagram due at the clock's time goes to the inner send, in delivery order, through the
     * channel and to the peer it was sent with; returns how many went.
     */
    public int pump() {
        long now = clock.getAsLong();
        int n = 0;
        while (true) {
            scratch.clear();
            if (link.receive(scratch, now) < 0) {
                return n;
            }
            scratch.flip();
            inner.send(link.lastTag(), (SocketAddress) link.lastAddress(), scratch);
            n++;
        }
    }

    /** BEHAVIOR: the next delivery time ({@link Long#MAX_VALUE} when nothing is in flight). */
    public long nextDue() {
        return link.nextDue();
    }
}
