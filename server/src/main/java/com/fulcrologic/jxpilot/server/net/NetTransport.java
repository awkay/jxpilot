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

import java.io.Closeable;
import java.io.IOException;

/**
 * What a {@link Server}'s loop needs from its datagram edge: deliver received datagrams to a
 * {@link UdpTransport.Receiver} while it waits, send, wake the waiting loop, and close. {@link UdpTransport} is the
 * socket implementation; the lobby gives each map's game a routed one that is fed by the lobby's socket.
 * <p>
 * {@link #poll} and the receiver belong to the loop thread; {@link #send} and {@link #wakeup} may be called from
 * any thread.
 */
public interface NetTransport extends Datagrams, Closeable {
    /** Where inbound datagrams go; set before the first {@link #poll}. */
    void setReceiver(UdpTransport.Receiver r);

    /**
     * Wait up to {@code timeoutNanos} for datagrams and deliver everything that is ready to the receiver.
     * Returns early after {@link #wakeup}.
     */
    void poll(long timeoutNanos) throws IOException;

    /** Wake a thread blocked in {@link #poll}. */
    void wakeup();

    @Override
    void close();
}
