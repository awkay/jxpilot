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
package com.fulcrologic.jxpilot.client;

import com.fulcrologic.jxpilot.client.gl.MapViewerApp;
import com.fulcrologic.jxpilot.client.viewer.LaunchOptions;

/**
 * Client entry point. For now only the map viewer exists:
 * {@code ClientMain --view-map maps/default.xp [--screenshot out.png --frames N] [--filled] ...}
 * (see {@link LaunchOptions}). On macOS the JVM needs {@code -XstartOnFirstThread}.
 */
public final class ClientMain {
    private ClientMain() {}

    public static void main(String[] args) throws Exception {
        LaunchOptions opts;
        try {
            opts = LaunchOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            usage();
            System.exit(2);
            return;
        }
        if (opts.viewMap() == null) {
            usage();
            System.exit(2);
            return;
        }
        new MapViewerApp(opts).run();
    }

    private static void usage() {
        System.err.println("usage: ClientMain --view-map FILE [--screenshot FILE.png] [--frames N] [--size WxH]"
            + " [--zoom Z] [--at X,Y] [--filled] [--no-vsync]");
    }
}
