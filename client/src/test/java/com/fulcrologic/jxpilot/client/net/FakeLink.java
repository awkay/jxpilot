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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Random;

/**
 * In-memory datagram link between a client {@link Transport} and a {@link FakeServer.Port}, with
 * seeded loss and reordering (toward the client) for robustness tests. Allocates per packet: not for
 * allocation tests.
 */
public final class FakeLink {
    private final ArrayDeque<byte[]> toServer = new ArrayDeque<>();
    private final ArrayDeque<byte[]> toClient = new ArrayDeque<>();
    private final Random rnd;
    /** Probability a server-to-client datagram is dropped. */
    public double lossToClient;
    /** Probability a client-to-server datagram is dropped. */
    public double lossToServer;
    /** Probability a server-to-client datagram is held back and delivered after the next one. */
    public double reorderToClient;
    /** When true nothing is delivered either way (cable pulled). */
    public boolean cut;
    private byte[] held;
    public int sentToClient;
    public int sentToServer;

    public FakeLink(long seed) {
        rnd = new Random(seed);
    }

    private static byte[] copy(ByteBuffer b) {
        byte[] a = new byte[b.remaining()];
        b.get(a);
        return a;
    }

    private static int deliver(ArrayDeque<byte[]> q, ByteBuffer into) {
        byte[] a = q.pollFirst();
        if (a == null) {
            return 0;
        }
        into.put(a);
        return a.length;
    }

    /** The client end. */
    public Transport client() {
        return new Transport() {
            @Override
            public boolean send(ByteBuffer packet) {
                byte[] a = copy(packet);
                sentToServer++;
                if (!cut && rnd.nextDouble() >= lossToServer) {
                    toServer.addLast(a);
                }
                return true;
            }

            @Override
            public int receive(ByteBuffer buf) {
                return deliver(toClient, buf);
            }

            @Override
            public void close() {
            }
        };
    }

    /** The server end. */
    public FakeServer.Port server() {
        return new FakeServer.Port() {
            @Override
            public boolean send(ByteBuffer b) {
                byte[] a = copy(b);
                sentToClient++;
                if (cut || rnd.nextDouble() < lossToClient) {
                    return true;
                }
                if (held == null && rnd.nextDouble() < reorderToClient) {
                    held = a;
                    return true;
                }
                toClient.addLast(a);
                if (held != null) {
                    toClient.addLast(held);
                    held = null;
                }
                return true;
            }

            @Override
            public int receive(ByteBuffer b) {
                return deliver(toServer, b);
            }
        };
    }
}
