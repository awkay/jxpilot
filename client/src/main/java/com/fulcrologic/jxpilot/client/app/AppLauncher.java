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

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.gl.MapViewerApp;
import com.fulcrologic.jxpilot.client.net.Endpoint;
import com.fulcrologic.jxpilot.client.xp.ConnectTarget;
import com.fulcrologic.jxpilot.client.xp.GameConnection;

import java.nio.file.Path;

/** Runs a parsed {@link AppOptions}: the viewer, a game on a server, or a game on an embedded server. */
public final class AppLauncher {
    private AppLauncher() {}

    /** @return the process exit code */
    public static int run(AppOptions o) throws Exception {
        switch (o.mode()) {
            case HELP -> {
                System.err.println(AppOptions.USAGE);
                return 2;
            }
            case VIEW_MAP -> {
                new MapViewerApp(o.viewer()).run();
                return 0;
            }
            case CONNECT -> {
                return play(o, o.target());
            }
            case LOCAL -> {
                EmbeddedServer server;
                try {
                    server = EmbeddedServer.start(Path.of(o.localMap()));
                } catch (UnsupportedOperationException e) {
                    System.err.println(e.getMessage());
                    return 3;
                }
                try (server) {
                    Endpoint ep = server.endpoint();
                    return play(o, new ConnectTarget(ConnectTarget.Protocol.JXPILOT, ep));
                }
            }
            default -> throw new IllegalStateException("mode " + o.mode());
        }
    }

    private static int play(AppOptions o, ConnectTarget target) throws Exception {
        ClientGame game = new ClientGame();
        GameConnection conn = GameConnector.open(target, o.bind(), game, o.name(), o.team(), o.shape(),
            (int) System.nanoTime());
        new GameApp(o, game, conn, target.toString(), null).run();
        return 0;
    }
}
