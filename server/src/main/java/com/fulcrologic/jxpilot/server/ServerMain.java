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
package com.fulcrologic.jxpilot.server;

import com.fulcrologic.jxpilot.server.net.Server;

import java.util.concurrent.CountDownLatch;

/**
 * Headless server entry point:
 * {@code ServerMain --map <file> [--port N] [--bind sel ...] [--bindWait] [config file]}.
 * Any XPilot server option may also be given as {@code -name value}. Runs until interrupted (Ctrl-C).
 */
public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        Server server;
        try {
            server = Server.start(args, new Server.Settings());
        } catch (IllegalArgumentException e) {
            System.err.println("jxpilot server: " + e.getMessage());
            System.err.println("usage: ServerMain --map <file> [--port N] [--bind sel ...] [--bindWait] [config file]");
            System.exit(1);
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.countDown();
        }, "jxpilot-shutdown"));
        done.await();
    }
}
