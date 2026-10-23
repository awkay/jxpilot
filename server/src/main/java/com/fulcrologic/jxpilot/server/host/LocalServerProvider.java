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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.net.LocalServer;
import com.fulcrologic.jxpilot.server.net.BindTarget;
import com.fulcrologic.jxpilot.server.net.Server;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The server module's {@link LocalServer.Provider}: the real {@link Server} with the real game
 * ({@link SimGameHostFactory}) on {@code --bind loopback}, port 0 (the system picks a free one).
 */
public final class LocalServerProvider implements LocalServer.Provider {
    /**
     * BEHAVIOR: serves {@code mapFile} on loopback only; the address is the bound IPv4 loopback socket when there
     * is one, else the first bound socket; the extra options are passed as on the command line.
     */
    @Override
    public LocalServer start(Path mapFile, String... options) throws IOException {
        List<String> args = new ArrayList<>(List.of("--map", mapFile.toString(), "--port", "0", "--bind", "loopback"));
        args.addAll(List.of(options));
        Server server = Server.start(args.toArray(String[]::new), new Server.Settings());
        InetSocketAddress address = pick(server);
        if (address == null) {
            closeQuietly(server);
            throw new IOException("the local server bound no address");
        }
        return new Running(server, address);
    }

    private static InetSocketAddress pick(Server server) {
        InetSocketAddress first = null;
        for (BindTarget t : server.bound()) {
            InetSocketAddress a = server.localAddress(t);
            if (a == null) {
                continue;
            }
            if (a.getAddress() instanceof Inet4Address) {
                return a;
            }
            if (first == null) {
                first = a;
            }
        }
        return first;
    }

    private static void closeQuietly(Server server) {
        try {
            server.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A started server. */
    private record Running(Server server, InetSocketAddress address) implements LocalServer {
        @Override
        public void close() {
            closeQuietly(server);
        }
    }
}
