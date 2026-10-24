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

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A client {@link Transport} decorator that puts a {@link LinkEmulator} between the client and an inner
 * transport (a real {@link UdpTransport}, or an in-memory pipe to a server): what the client sends goes into the
 * uplink and reaches the inner transport when it is due; what the inner transport receives goes into the
 * downlink and reaches the client when it is due. Time is the emulator's clock. Allocation free after
 * construction.
 * <p>
 * Inner arrivals are stamped with the time they are drained. The client drains on every {@link #receive}; a
 * virtual-time harness calls {@link #pump()} right after the server sends, so downlink delays start at the true
 * send time, and when the uplink's next delivery is due ({@link LinkEmulator#nextDue()}).
 */
public final class EmulatedTransport implements Transport {
    private final Transport inner;
    private final LinkEmulator link;
    private final ByteBuffer scratch;
    private long forwardErrors;

    public EmulatedTransport(Transport inner, LinkEmulator link) {
        this.inner = inner;
        this.link = link;
        this.scratch = ByteBuffer.allocateDirect(LinkEmulator.DEFAULT_SLOT_BYTES);
    }

    public LinkEmulator link() {
        return link;
    }

    /**
     * BEHAVIOR: the datagram enters the uplink at the clock's time (consumed, as a channel send) and true is returned:
     * the emulated link accepted it; whether it arrives is the link's business, silently, as with UDP. Due uplink
     * packets are then forwarded ({@link #forward()}); a failure forwarding one of them is that packet's loss
     * ({@link #forwardErrors()}), never this call's exception.
     */
    @Override
    public boolean send(ByteBuffer packet) {
        link.up().send(packet, link.now(), 0, null);
        forward();
        return true;
    }

    /**
     * BEHAVIOR: after {@link #pump()}, the earliest downlink packet due now is put into {@code buf}; its length,
     * or 0 when none is due.
     */
    @Override
    public int receive(ByteBuffer buf) throws IOException {
        pump();
        int n = link.down().receive(buf, link.now());
        return Math.max(0, n);
    }

    /** Uplink packets the inner transport refused (false or IOException): lost on the way. */
    public long forwardErrors() {
        return forwardErrors;
    }

    /**
     * BEHAVIOR: every uplink packet due at the clock's time goes to the inner transport's send, in delivery order; one
     * the inner send refuses (false or IOException) is lost and counted in {@link #forwardErrors()}.
     */
    void forward() {
        long now = link.now();
        while (true) {
            scratch.clear();
            if (link.up().receive(scratch, now) < 0) {
                return;
            }
            scratch.flip();
            try {
                if (!inner.send(scratch)) {
                    forwardErrors++;
                }
            } catch (IOException e) {
                forwardErrors++;
            }
        }
    }

    /**
     * Move what is due.
     * <p>
     * BEHAVIOR: first the due uplink is forwarded ({@link #forward()}); then everything the inner transport has
     * received enters the downlink, stamped now (replies that the forwarded packets caused included). An IOException
     * from the inner receive propagates (the caller's receive failed).
     */
    public void pump() throws IOException {
        long now = link.now();
        forward();
        while (true) {
            scratch.clear();
            if (inner.receive(scratch) <= 0) {
                break;
            }
            scratch.flip();
            link.down().send(scratch, now, 0, null);
        }
    }

    /** BEHAVIOR: closes the inner transport. */
    @Override
    public void close() {
        inner.close();
    }
}
