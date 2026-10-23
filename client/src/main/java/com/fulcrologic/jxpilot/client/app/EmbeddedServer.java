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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.net.Endpoint;
import com.fulcrologic.jxpilot.common.net.LocalServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ServiceLoader;

/**
 * An in-process game server for {@code --local}: it runs its own thread(s), binds {@code loopback} only,
 * and the client connects to {@link #endpoint()} over UDP like to any other server (SPEC "Architecture":
 * same code path, no special casing).
 * <p>
 * The server module provides the implementation through common's {@link LocalServer.Provider}, registered in
 * {@code META-INF/services/com.fulcrologic.jxpilot.common.net.LocalServer$Provider}; the client finds it with
 * {@link #start(Path)} and has no compile-time dependency on server code.
 */
public interface EmbeddedServer extends AutoCloseable {
    /** Where the client connects (a loopback address and the bound port). */
    Endpoint endpoint();

    /** Stop the server threads and release its sockets. */
    @Override
    void close();

    /**
     * Start an embedded server for {@code mapFile} through the first {@link ServiceLoader} provider
     * (see {@link #provider}).
     *
     * @throws IOException              when the map cannot be read or nothing can be bound
     * @throws IllegalArgumentException for a bad map or option
     */
    static EmbeddedServer start(Path mapFile) throws IOException {
        LocalServer s = provider(ServiceLoader.load(LocalServer.Provider.class)).start(mapFile);
        Endpoint ep = endpointOf(s.address());
        return new EmbeddedServer() {
            @Override
            public Endpoint endpoint() {
                return ep;
            }

            @Override
            public void close() {
                s.close();
            }
        };
    }

    /**
     * BEHAVIOR: the first discovered provider; none throws UnsupportedOperationException explaining that
     * this build has no embedded server and to use {@code --connect}.
     */
    static LocalServer.Provider provider(Iterable<LocalServer.Provider> discovered) {
        for (LocalServer.Provider p : discovered) {
            return p;
        }
        throw new UnsupportedOperationException("--local is not available: this build has no embedded server"
            + " (the server module is not on the class path). Start a server and use --connect.");
    }

    /** BEHAVIOR: the endpoint of a bound socket: its address literal and port. */
    static Endpoint endpointOf(InetSocketAddress a) {
        return new Endpoint(a.getAddress().getHostAddress(), a.getPort());
    }
}
