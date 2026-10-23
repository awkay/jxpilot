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

import com.fulcrologic.jxpilot.client.app.AppLauncher;
import com.fulcrologic.jxpilot.client.app.AppOptions;

/**
 * Client entry point (see {@link AppOptions} for the arguments):
 * <pre>
 *   ClientMain --connect HOST[:PORT] | xp://HOST:PORT [--name N] [--team T] ...   play on a server
 *   ClientMain --local --map FILE                                                  play on an embedded server
 *   ClientMain --view-map FILE [--demo] [--screenshot out.png --frames N] ...      the map viewer
 * </pre>
 * On macOS the JVM needs {@code -XstartOnFirstThread}.
 */
public final class ClientMain {
    private ClientMain() {}

    public static void main(String[] args) throws Exception {
        AppOptions opts;
        try {
            opts = AppOptions.parse(args, System.getProperty("user.name"));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(AppOptions.USAGE);
            System.exit(2);
            return;
        }
        System.exit(AppLauncher.run(opts));
    }
}
