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

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Datagram I/O to the one server the client talks to. The real implementation is
 * {@link UdpTransport}; tests use in-memory fakes. Implementations must not allocate per call.
 */
public interface Transport extends AutoCloseable {
    /**
     * Send the buffer's remaining bytes as one datagram.
     *
     * @return false when the datagram could not be sent right now (dropped)
     */
    boolean send(ByteBuffer packet) throws IOException;

    /**
     * Receive one datagram into {@code buf} (relative put from its position) without blocking.
     *
     * @return number of bytes received, 0 when nothing is waiting
     */
    int receive(ByteBuffer buf) throws IOException;

    @Override
    void close();
}
