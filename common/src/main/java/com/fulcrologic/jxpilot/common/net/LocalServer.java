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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;

/**
 * A game server running inside this process, for the client's {@code --local} (SPEC "Architecture": same code
 * path as a remote server, over loopback UDP). The server module registers its {@link Provider} in
 * {@code META-INF/services/com.fulcrologic.jxpilot.common.net.LocalServer$Provider}; the client finds it with
 * {@link java.util.ServiceLoader} and needs no compile-time dependency on the server.
 */
public interface LocalServer extends AutoCloseable {
    /** Where clients connect: a loopback address and the port actually bound. */
    InetSocketAddress address();

    /** Stop serving (clients get DISCONNECT) and release the sockets. */
    @Override
    void close();

    /** Starts local servers; one is registered by the server module. */
    interface Provider {
        /**
         * Load {@code mapFile}, bind {@code loopback} only on a free port and start serving.
         *
         * @param options extra server options as on the server's command line ({@code -name value})
         * @throws IOException              when the map cannot be read or nothing can be bound
         * @throws IllegalArgumentException for a bad map or option
         */
        LocalServer start(Path mapFile, String... options) throws IOException;
    }
}
